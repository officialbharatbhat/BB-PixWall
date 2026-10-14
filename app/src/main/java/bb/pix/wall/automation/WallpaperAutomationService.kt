package bb.pix.wall.automation

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import bb.pix.wall.R
import bb.pix.wall.engine.EngineExecutors
import bb.pix.wall.engine.RuntimeStatus
import bb.pix.wall.engine.WallpaperController
import bb.pix.wall.engine.WallpaperFiles
import bb.pix.wall.network.NetworkMonitor
import bb.pix.wall.engine.SafeWall
import bb.pix.wall.root.RootAccess
import bb.pix.wall.settings.AppSettings
import bb.pix.wall.settings.EngineMode
import bb.pix.wall.settings.SettingsStore
import bb.pix.wall.settings.TriggerMode
import bb.pix.wall.settings.WallpaperTargetMode
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class WallpaperAutomationService : Service() {
    private val handler =
        Handler(Looper.getMainLooper())

    private val applying =
        AtomicBoolean(false)

    // All automatic events use one serial worker. SCREEN_ON must wait behind
    // SCREEN_OFF instead of racing it on a second executor.
    private val automationWorker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "bbpix-lite-automatic").apply {
            priority = Thread.NORM_PRIORITY + 1
        }
    }
    private val recentEvents = mutableMapOf<String, Long>()

    private var screenRegistered =
        false

    private val screenReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(
                context: Context?,
                intent: Intent?,
            ) {
                val action =
                    intent?.action
                        ?: return

                val settings =
                    SettingsStore(
                        this@WallpaperAutomationService
                    ).load()

                if (!settings.autoChange || bb.pix.wall.engine.SafeWall.active(this@WallpaperAutomationService)) {
                    return
                }

                val target = targetForEvent(settings,
                    action == Intent.ACTION_SCREEN_OFF,
                    action == Intent.ACTION_SCREEN_ON)
                if (target != null) {
                    applyPrepared(settings, "event:${action.substringAfterLast('.')}", target)
                }
            }
        }

    private val homeIntervalTask = object : Runnable {
        override fun run() {
            val config = SettingsStore(this@WallpaperAutomationService).load()
            if (config.autoChange && !SafeWall.active(this@WallpaperAutomationService) &&
                config.homeTriggerMode == TriggerMode.INTERVAL) {
                applyPrepared(config, "interval:HOME", WallpaperTargetMode.HOME)
            }
            scheduleInterval(config, WallpaperTargetMode.HOME)
        }
    }

    private val lockIntervalTask = object : Runnable {
        override fun run() {
            val config = SettingsStore(this@WallpaperAutomationService).load()
            if (config.autoChange && !SafeWall.active(this@WallpaperAutomationService) &&
                config.lockTriggerMode == TriggerMode.INTERVAL) {
                applyPrepared(config, "interval:LOCK", WallpaperTargetMode.LOCK)
            }
            scheduleInterval(config, WallpaperTargetMode.LOCK)
        }
    }

    private val networkRecovered: () -> Unit = {
        if (!SafeWall.active(applicationContext) &&
            bb.pix.wall.engine.LiteResourcePolicy.deferCloudRefill(applicationContext) == null) {
            EngineExecutors.io {
                runCatching {
                    val cfg = SettingsStore(applicationContext).load()
                    if (cfg.autoChange) WallpaperController.primeCache(applicationContext, cfg)
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()

        createChannel()
        NetworkMonitor.register(applicationContext)
        NetworkMonitor.add(networkRecovered)

        startForeground(
            101,
            NotificationCompat
                .Builder(
                    this,
                    CHANNEL,
                )
                .setSmallIcon(
                    R.drawable.ic_tile_next
                )
                .setContentTitle(
                    "BB-PixWall Lite"
                )
                .setContentText(
                    "Fast prepared wallpaper automation"
                )
                .setOngoing(true)
                .setSilent(true)
                .build(),
        )

        val filter =
            IntentFilter().apply {
                addAction(
                    Intent.ACTION_SCREEN_OFF
                )
                addAction(
                    Intent.ACTION_SCREEN_ON
                )
            }

        ContextCompat.registerReceiver(
            this,
            screenReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )

        screenRegistered =
            true
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        handler.removeCallbacks(homeIntervalTask)
        handler.removeCallbacks(lockIntervalTask)

        val settings =
            SettingsStore(this)
                .load()

        if (!settings.autoChange) {
            stopSelf()
            return START_NOT_STICKY
        }

        RuntimeStatus.set(
            applicationContext,
            "automation_state",
            "Lite ready",
        )

        EngineExecutors.io {
            runCatching {
                WallpaperController.primeCache(
                    applicationContext,
                    settings,
                )

                WallpaperController.warmNextBlurCaches(
                    applicationContext,
                    settings,
                )
            }
        }

        if (
            settings.engineMode ==
                EngineMode.ADVANCED &&
            settings.backgroundGuardEnabled
        ) {
            EngineExecutors.io {
                val lastTune =
                    RuntimeStatus.getLong(
                        applicationContext,
                        "root_last_tune",
                        0L,
                    )

                if (
                    System.currentTimeMillis() -
                        lastTune >=
                    ROOT_TUNE_COOLDOWN_MS
                ) {
                    runCatching {
                        RootAccess.tuneBackground(
                            applicationContext
                        )
                    }
                }
            }
        }

        scheduleNextInterval(settings)

        return START_STICKY
    }

    private fun scheduleNextInterval(settings: AppSettings) {
        scheduleInterval(settings, WallpaperTargetMode.HOME)
        scheduleInterval(settings, WallpaperTargetMode.LOCK)
    }

    private fun scheduleInterval(settings: AppSettings, target: WallpaperTargetMode) {
        val isHome = target == WallpaperTargetMode.HOME
        val task = if (isHome) homeIntervalTask else lockIntervalTask
        handler.removeCallbacks(task)
        val active = if (isHome) settings.homeTriggerMode == TriggerMode.INTERVAL
            else settings.lockTriggerMode == TriggerMode.INTERVAL
        if (!settings.autoChange || !active) return
        val duration = if (isHome) settings.homeIntervalMinutes else settings.lockIntervalMinutes
        val delay = duration.coerceIn(1, 300) * 60_000L
        RuntimeStatus.nextRun(applicationContext, System.currentTimeMillis() + delay)
        handler.postDelayed(task, delay)
    }

    private fun combineTargets(home: Boolean, lock: Boolean): WallpaperTargetMode? =
        when {
            home && lock -> WallpaperTargetMode.BOTH_DIFFERENT
            home -> WallpaperTargetMode.HOME
            lock -> WallpaperTargetMode.LOCK
            else -> null
        }

    private fun targetForEvent(settings: AppSettings, off: Boolean, on: Boolean): WallpaperTargetMode? {
        fun matches(mode: TriggerMode): Boolean = when (mode) {
            TriggerMode.SCREEN_OFF -> off
            TriggerMode.SCREEN_ON -> on
            TriggerMode.SCREEN_OFF_OR_ON -> off || on
            else -> false
        }
        return combineTargets(matches(settings.homeTriggerMode), matches(settings.lockTriggerMode))
    }

    private fun applyPrepared(
        settings: AppSettings,
        reason: String,
        target: WallpaperTargetMode,
    ) {
        if (bb.pix.wall.engine.SafeWall.active(this)) return
        // Debounce only duplicate broadcasts of the SAME event. Different
        // targets/events (lock then unlock) must never suppress each other.
        val now = android.os.SystemClock.elapsedRealtime()
        synchronized(recentEvents) {
            val last = recentEvents[reason] ?: 0L
            if (now - last < 250L) {
                recordTrigger(reason, "duplicate")
                return
            }
            recentEvents[reason] = now
        }

        val screenOff =
            reason ==
                "event:SCREEN_OFF"

        val wakeLock =
            if (screenOff) {
                (
                    getSystemService(
                        POWER_SERVICE
                    ) as PowerManager
                )
                    .newWakeLock(
                        PowerManager
                            .PARTIAL_WAKE_LOCK,
                        "$packageName:liteScreenApply",
                    )
                    .apply {
                        setReferenceCounted(false)
                        acquire(15_000L)
                    }
            } else {
                null
            }

        recordTrigger(
            reason,
            "start",
        )

        automationWorker.execute {
            try {
                var changed =
                    if (screenOff) {
                        WallpaperController.screenOffFastWall(
                            applicationContext, targetOverride = target, automationTriggered = true, automationTriggered = true
                        )
                    } else {
                        WallpaperController.nextWall(
                            applicationContext,
                            allowNetwork = false,
                            userInitiated = false,
                            preferLockFirst = false,
                            targetOverride = target, automationTriggered = true,
                        )
                    }

                if (!changed) {
                    runCatching {
                        WallpaperController.ensureNext(
                            applicationContext,
                            settings.copy(targetMode = target),
                            allowNetwork = false,
                        )
                    }

                    changed =
                        if (screenOff) {
                            WallpaperController.screenOffFastWall(
                                applicationContext, targetOverride = target, automationTriggered = true, automationTriggered = true
                            )
                        } else {
                            WallpaperController.nextWall(
                                applicationContext,
                                allowNetwork = false,
                                userInitiated = false,
                                preferLockFirst = false,
                                targetOverride = target, automationTriggered = true,
                            )
                        }
                }

                recordTrigger(
                    reason,
                    if (changed) {
                        "applied"
                    } else {
                        "no-ready-wall"
                    },
                )

                RuntimeStatus.set(
                    applicationContext,
                    "automation_state",
                    if (changed) {
                        "Ready"
                    } else {
                        "Preparing"
                    },
                )

            } catch (t: Throwable) {
                RuntimeStatus.failure(
                    applicationContext,
                    "Lite automation: ${t.message ?: t.javaClass.simpleName}",
                )

                recordTrigger(
                    reason,
                    "error:${t.javaClass.simpleName}",
                )
            } finally {
                if (
                    wakeLock != null &&
                    wakeLock.isHeld
                ) {
                    runCatching {
                        wakeLock.release()
                    }
                }

            }
        }
    }

    private fun recordTrigger(
        reason: String,
        result: String,
    ) {
        runCatching {
            WallpaperFiles.ensure()

            WallpaperFiles
                .triggerHistory
                .appendText(
                    "${System.currentTimeMillis()}\t$reason\t$result\n"
                )

            val lines =
                WallpaperFiles
                    .triggerHistory
                    .readLines()

            if (lines.size > 120) {
                WallpaperFiles
                    .triggerHistory
                    .writeText(
                        lines
                            .takeLast(120)
                            .joinToString(
                                "\n",
                                postfix = "\n",
                            )
                    )
            }
        }

        RuntimeStatus.set(
            applicationContext,
            "last_trigger",
            "$reason • $result",
        )
    }

    override fun onDestroy() {
        NetworkMonitor.remove(networkRecovered)
        handler.removeCallbacksAndMessages(
            null
        )

        if (screenRegistered) {
            runCatching {
                unregisterReceiver(
                    screenReceiver
                )
            }
        }

        automationWorker.shutdownNow()

        super.onDestroy()
    }

    override fun onBind(
        intent: Intent?,
    ): IBinder? =
        null

    private fun createChannel() {
        getSystemService(
            NotificationManager::class.java
        ).createNotificationChannel(
            NotificationChannel(
                CHANNEL,
                "BB-PixWall Lite automation",
                NotificationManager
                    .IMPORTANCE_LOW,
            )
        )
    }

    companion object {
        const val CHANNEL =
            "bb_pixwall_lite_automation"

        private const val
            ROOT_TUNE_COOLDOWN_MS =
            30L * 60L * 1000L
    }
}
