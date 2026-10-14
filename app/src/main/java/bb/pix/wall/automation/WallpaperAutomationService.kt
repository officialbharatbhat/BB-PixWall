package bb.pix.wall.automation

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
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

class WallpaperAutomationService : Service() {
    // All automatic events use one serial worker so screen-off and
    // completed device-unlock events cannot race on separate executors.
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

                if (action == Intent.ACTION_USER_PRESENT) {
                    recordTrigger("receiver:USER_PRESENT", "received")
                }

                val target = targetForEvent(settings,
                    action == Intent.ACTION_SCREEN_OFF,
                    action == Intent.ACTION_USER_PRESENT)
                if (target != null) {
                    applyPrepared(settings, "event:${action.substringAfterLast('.')}", target)
                }
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
                    Intent.ACTION_USER_PRESENT
                )
            }

        ContextCompat.registerReceiver(
            this,
            screenReceiver,
            filter,
            // Both actions are Android protected system broadcasts.
            // Some privileged system senders are excluded by NOT_EXPORTED.
            ContextCompat.RECEIVER_EXPORTED,
        )

        screenRegistered =
            true
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        val settings =
            SettingsStore(this)
                .load()

        if (!settings.autoChange) {
            cancelIntervalAlarms()
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

        val intervalTarget = intent?.getStringExtra(EXTRA_INTERVAL_TARGET)
        when (intervalTarget) {
            WallpaperTargetMode.HOME.name -> {
                if (settings.homeTriggerMode == TriggerMode.INTERVAL && !SafeWall.active(this)) {
                    applyPrepared(settings, "interval:HOME", WallpaperTargetMode.HOME)
                }
                scheduleInterval(settings, WallpaperTargetMode.HOME)
            }
            WallpaperTargetMode.LOCK.name -> {
                if (settings.lockTriggerMode == TriggerMode.INTERVAL && !SafeWall.active(this)) {
                    applyPrepared(settings, "interval:LOCK", WallpaperTargetMode.LOCK)
                }
                scheduleInterval(settings, WallpaperTargetMode.LOCK)
            }
            else -> scheduleNextInterval(settings)
        }

        return START_STICKY
    }

    private fun intervalIntent(target: WallpaperTargetMode): PendingIntent {
        val requestCode = if (target == WallpaperTargetMode.HOME) 201 else 202
        val intent = Intent(this, WallpaperAutomationService::class.java)
            .putExtra(EXTRA_INTERVAL_TARGET, target.name)
        return PendingIntent.getForegroundService(
            this, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun cancelIntervalAlarms() {
        val alarms = getSystemService(AlarmManager::class.java)
        for (target in listOf(WallpaperTargetMode.HOME, WallpaperTargetMode.LOCK)) {
            alarms.cancel(intervalIntent(target))
        }
    }

    private fun scheduleNextInterval(settings: AppSettings) {
        scheduleInterval(settings, WallpaperTargetMode.HOME)
        scheduleInterval(settings, WallpaperTargetMode.LOCK)
    }

    private fun scheduleInterval(settings: AppSettings, target: WallpaperTargetMode) {
        val isHome = target == WallpaperTargetMode.HOME
        val alarms = getSystemService(AlarmManager::class.java)
        val alarmIntent = intervalIntent(target)
        alarms.cancel(alarmIntent)
        val active = if (isHome) settings.homeTriggerMode == TriggerMode.INTERVAL
            else settings.lockTriggerMode == TriggerMode.INTERVAL
        if (!settings.autoChange || !active) return

        val minutes = if (isHome) settings.homeIntervalMinutes else settings.lockIntervalMinutes
        val delay = minutes.coerceIn(1, 300) * 60_000L
        // AlarmManager wakes the process after Doze delays; no perpetual poller.
        // setAndAllowWhileIdle is inexact and subject to Android idle quotas.
        alarms.setAndAllowWhileIdle(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            SystemClock.elapsedRealtime() + delay,
            alarmIntent,
        )
        RuntimeStatus.setLong(
            applicationContext,
            if (isHome) "home_interval_due" else "lock_interval_due",
            System.currentTimeMillis() + delay,
        )
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
            "$reason target=$target",
            "start",
        )

        automationWorker.execute {
            try {
                var changed =
                    if (screenOff) {
                        WallpaperController.nextWall(
                            applicationContext,
                            allowNetwork = false,
                            userInitiated = false,
                            preferLockFirst = true,
                            targetOverride = target,
                            automationTriggered = true,
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
                            WallpaperController.nextWall(
                            applicationContext,
                            allowNetwork = false,
                            userInitiated = false,
                            preferLockFirst = true,
                            targetOverride = target,
                            automationTriggered = true,
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

                // A trigger must not stall cloud I/O, but a cache miss must
                // request replenishment so future independent events can succeed.
                if (!changed) {
                    EngineExecutors.io {
                        runCatching {
                            WallpaperController.primeCache(
                                applicationContext,
                                SettingsStore(applicationContext).load(),
                            )
                        }.onFailure {
                            RuntimeStatus.failure(
                                applicationContext,
                                "Automation refill: ${it.javaClass.simpleName}",
                            )
                        }
                    }
                }

                recordTrigger(
                    "$reason target=$target",
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
        private const val EXTRA_INTERVAL_TARGET = "bb.pix.wall.INTERVAL_TARGET"
        const val CHANNEL =
            "bb_pixwall_lite_automation"

        private const val
            ROOT_TUNE_COOLDOWN_MS =
            30L * 60L * 1000L
    }
}
