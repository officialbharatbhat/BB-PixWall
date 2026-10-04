package bb.pix.wall.automation

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import bb.pix.wall.R
import bb.pix.wall.engine.EngineExecutors
import bb.pix.wall.engine.EngineHealth
import bb.pix.wall.engine.RuntimeStatus
import bb.pix.wall.engine.WallpaperController
import bb.pix.wall.engine.WallpaperFiles
import bb.pix.wall.root.RootAccess
import bb.pix.wall.settings.*
import java.util.Calendar
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class WallpaperAutomationService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val applyWorker = Executors.newSingleThreadExecutor { r -> Thread(r, "bbpix-apply").apply { priority = Thread.NORM_PRIORITY + 1 } }
    private val applying = AtomicBoolean(false)
    private var screenRegistered = false
    @Volatile private var lastApplyStartedAt = 0L

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            val s = SettingsStore(this@WallpaperAutomationService).load()
            if (!s.autoChange) return
            val matches = when (s.triggerMode) {
                TriggerMode.SCREEN_OFF -> action == Intent.ACTION_SCREEN_OFF
                TriggerMode.SCREEN_ON -> action == Intent.ACTION_SCREEN_ON
                TriggerMode.SCREEN_OFF_OR_ON -> action == Intent.ACTION_SCREEN_OFF || action == Intent.ACTION_SCREEN_ON
                else -> false
            }
            if (matches) applyIfAllowed(s, "event:${action.substringAfterLast('.')}")
        }
    }

    private val healthTask = object : Runnable {
        override fun run() {
            val latest = SettingsStore(this@WallpaperAutomationService).load()
            EngineExecutors.io {
                runCatching { EngineHealth.auditAndRepair(applicationContext, latest, allowNetworkRefill = true) }
            }
            handler.postDelayed(this, 15 * 60_000L)
        }
    }

    private val intervalTask = object : Runnable {
        override fun run() {
            val s = SettingsStore(this@WallpaperAutomationService).load()
            if (s.autoChange && s.triggerMode == TriggerMode.INTERVAL) applyIfAllowed(s, "interval")
            scheduleNextInterval(s)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(101, NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_tile_next)
            .setContentTitle("BB-PixWall automation")
            .setContentText("Prepared wallpaper engine is active")
            .setOngoing(true).setSilent(true).build())
        val f = IntentFilter().apply { addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_SCREEN_ON) }
        ContextCompat.registerReceiver(this, screenReceiver, f, ContextCompat.RECEIVER_NOT_EXPORTED)
        screenRegistered = true
        handler.postDelayed(healthTask, 45_000L)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        handler.removeCallbacks(intervalTask)
        val s = SettingsStore(this).load()
        if (!s.autoChange) { stopSelf(); return START_NOT_STICKY }

        EngineExecutors.io { runCatching { EngineHealth.auditAndRepair(applicationContext, s, allowNetworkRefill = false) } }

        if (s.engineMode == EngineMode.ADVANCED && s.backgroundGuardEnabled) {
            EngineExecutors.io { runCatching { RootAccess.tuneBackground(applicationContext) } }
        }

        // Fast path: make sure next assets exist from local cache first. Never queue screen-off behind network work.
        applyWorker.execute {
            runCatching { WallpaperController.verifyCacheIntegrity() }
            runCatching { WallpaperController.ensureNext(applicationContext, s, allowNetwork = false) }
        }
        // Slow/network work is separate and never blocks the apply worker.
        EngineExecutors.io {
            runCatching { WallpaperController.primeCache(applicationContext, s) }
            runCatching { WallpaperController.ensureNext(applicationContext, s, allowNetwork = true) }
        }

        if (s.triggerMode == TriggerMode.INTERVAL) {
            maybeRecoverMissedInterval(s)
            scheduleNextInterval(s)
        } else RuntimeStatus.nextRun(applicationContext, 0L)
        return START_STICKY
    }

    private fun scheduleNextInterval(s: AppSettings) {
        handler.removeCallbacks(intervalTask)
        if (!s.autoChange || s.triggerMode != TriggerMode.INTERVAL) return
        val delay = s.intervalMinutes.coerceIn(1, 240) * 60_000L
        val next = System.currentTimeMillis() + delay
        RuntimeStatus.nextRun(applicationContext, next)
        RuntimeStatus.setLong(applicationContext, "interval_due", next)
        handler.postDelayed(intervalTask, delay)
    }

    private fun maybeRecoverMissedInterval(s: AppSettings) {
        val due = RuntimeStatus.getLong(applicationContext, "interval_due", 0L)
        if (due > 0L && System.currentTimeMillis() - due in 1L..(s.intervalMinutes * 60_000L * 3L)) {
            applyIfAllowed(s, "missed-interval")
        }
    }

    private fun applyIfAllowed(s: AppSettings, reason: String) {
        val now = System.currentTimeMillis()
        if (now - lastApplyStartedAt < 1200L) { recordTrigger(reason, "debounced"); return }
        val denied = conditionsDeniedReason(s)
        if (denied != null) { recordTrigger(reason, "blocked:$denied"); return }
        if (!applying.compareAndSet(false, true)) { recordTrigger(reason, "busy"); return }
        lastApplyStartedAt = now
        RuntimeStatus.set(applicationContext, "automation_state", "Applying")
        recordTrigger(reason, "start")

        applyWorker.execute {
            try {
                // Screen/event path is deliberately network-free. It must consume already prepared assets only.
                var changed = WallpaperController.nextWall(applicationContext, allowNetwork = false)
                if (!changed) {
                    runCatching { WallpaperController.ensureNext(applicationContext, s, allowNetwork = false) }
                    changed = WallpaperController.nextWall(applicationContext, allowNetwork = false)
                }
                recordTrigger(reason, if (changed) "applied" else "no-ready-wall")
                RuntimeStatus.set(applicationContext, "automation_state", if (changed) "Ready" else "Waiting for cache")

                // Immediately prepare another local next item, then refill network cache separately.
                runCatching { WallpaperController.ensureNext(applicationContext, SettingsStore(this).load(), allowNetwork = false) }
                EngineExecutors.io {
                    val latest = SettingsStore(this).load()
                    runCatching { WallpaperController.primeCache(applicationContext, latest) }
                    runCatching { WallpaperController.ensureNext(applicationContext, latest, allowNetwork = true) }
                }
            } catch (t: Throwable) {
                RuntimeStatus.failure(applicationContext, "Automation: ${t.message ?: t.javaClass.simpleName}")
                recordTrigger(reason, "error:${t.javaClass.simpleName}")
            } finally { applying.set(false) }
        }
    }

    private fun conditionsDeniedReason(s: AppSettings): String? {
        if (s.quietHoursEnabled && inQuietHours(s.quietStartHour, s.quietEndHour)) return "quiet-hours"
        if (s.pauseBatterySaver && getSystemService(PowerManager::class.java).isPowerSaveMode) return "battery-saver"
        if (s.chargingOnly) {
            val battery = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val status = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            if (status != BatteryManager.BATTERY_STATUS_CHARGING && status != BatteryManager.BATTERY_STATUS_FULL) return "not-charging"
        }
        if (s.pauseLowBattery) {
            val bm = getSystemService(BATTERY_SERVICE) as BatteryManager
            val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            if (level in 0 until s.lowBatteryThreshold.coerceIn(5,50)) return "low-battery"
        }
        return null
    }

    private fun recordTrigger(reason: String, result: String) {
        runCatching {
            WallpaperFiles.ensure()
            WallpaperFiles.triggerHistory.appendText("${System.currentTimeMillis()}\t$reason\t$result\n")
            val lines = WallpaperFiles.triggerHistory.readLines()
            if (lines.size > 250) WallpaperFiles.triggerHistory.writeText(lines.takeLast(250).joinToString("\n", postfix="\n"))
        }
        RuntimeStatus.set(applicationContext, "last_trigger", "$reason • $result")
    }

    private fun inQuietHours(start: Int, end: Int): Boolean {
        val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return if (start == end) false else if (start < end) h in start until end else h >= start || h < end
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (screenRegistered) runCatching { unregisterReceiver(screenReceiver) }
        applyWorker.shutdownNow()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
    private fun createChannel() { getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL, "Wallpaper automation", NotificationManager.IMPORTANCE_LOW)) }
    companion object { const val CHANNEL = "bb_pixwall_automation" }
}
