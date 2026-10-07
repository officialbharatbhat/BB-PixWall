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
import bb.pix.wall.root.RootAccess
import bb.pix.wall.settings.AppSettings
import bb.pix.wall.settings.EngineMode
import bb.pix.wall.settings.SettingsStore
import bb.pix.wall.settings.TriggerMode
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class WallpaperAutomationService : Service() {
    private val handler =
        Handler(Looper.getMainLooper())

    private val applying =
        AtomicBoolean(false)

    private val normalWorker =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(
                runnable,
                "bbpix-lite-apply",
            ).apply {
                priority =
                    Thread.NORM_PRIORITY + 1
            }
        }

    private val screenWorker =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(
                {
                    runCatching {
                        android.os.Process.setThreadPriority(
                            android.os.Process.THREAD_PRIORITY_DISPLAY
                        )
                    }
                    runnable.run()
                },
                "bbpix-lite-screen",
            ).apply {
                priority =
                    Thread.MAX_PRIORITY
            }
        }

    @Volatile
    private var lastApplyStartedAt =
        0L

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

                if (!settings.autoChange) {
                    return
                }

                val matches =
                    when (settings.triggerMode) {
                        TriggerMode.SCREEN_OFF ->
                            action ==
                                Intent.ACTION_SCREEN_OFF

                        TriggerMode.SCREEN_ON ->
                            action ==
                                Intent.ACTION_SCREEN_ON

                        else ->
                            false
                    }

                if (matches) {
                    applyPrepared(
                        settings,
                        "event:${action.substringAfterLast('.')}",
                    )
                }
            }
        }

    private val intervalTask =
        object : Runnable {
            override fun run() {
                val settings =
                    SettingsStore(
                        this@WallpaperAutomationService
                    ).load()

                if (
                    settings.autoChange &&
                    settings.triggerMode ==
                        TriggerMode.INTERVAL
                ) {
                    applyPrepared(
                        settings,
                        "interval",
                    )
                }

                scheduleNextInterval(
                    settings
                )
            }
        }

    override fun onCreate() {
        super.onCreate()

        createChannel()

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
        handler.removeCallbacks(
            intervalTask
        )

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

        if (
            settings.triggerMode ==
            TriggerMode.INTERVAL
        ) {
            scheduleNextInterval(
                settings
            )
        } else {
            RuntimeStatus.nextRun(
                applicationContext,
                0L,
            )
        }

        return START_STICKY
    }

    private fun scheduleNextInterval(
        settings: AppSettings,
    ) {
        handler.removeCallbacks(
            intervalTask
        )

        if (
            !settings.autoChange ||
            settings.triggerMode !=
                TriggerMode.INTERVAL
        ) {
            return
        }

        val delay =
            settings.intervalMinutes
                .coerceIn(
                    1,
                    300,
                ) *
                60_000L

        RuntimeStatus.nextRun(
            applicationContext,
            System.currentTimeMillis() +
                delay,
        )

        handler.postDelayed(
            intervalTask,
            delay,
        )
    }

    private fun applyPrepared(
        settings: AppSettings,
        reason: String,
    ) {
        val now =
            System.currentTimeMillis()

        if (
            now - lastApplyStartedAt <
            900L
        ) {
            recordTrigger(
                reason,
                "debounced",
            )
            return
        }

        if (
            !applying.compareAndSet(
                false,
                true,
            )
        ) {
            recordTrigger(
                reason,
                "busy",
            )
            return
        }

        lastApplyStartedAt =
            now

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

        val worker =
            if (screenOff) {
                screenWorker
            } else {
                normalWorker
            }

        recordTrigger(
            reason,
            "start",
        )

        worker.execute {
            try {
                var changed =
                    if (screenOff) {
                        WallpaperController.screenOffFastWall(
                            applicationContext
                        )
                    } else {
                        WallpaperController.nextWall(
                            applicationContext,
                            allowNetwork = false,
                            userInitiated = false,
                            preferLockFirst = false,
                        )
                    }

                if (!changed) {
                    runCatching {
                        WallpaperController.ensureNext(
                            applicationContext,
                            settings,
                            allowNetwork = false,
                        )
                    }

                    changed =
                        if (screenOff) {
                            WallpaperController.screenOffFastWall(
                                applicationContext
                            )
                        } else {
                            WallpaperController.nextWall(
                                applicationContext,
                                allowNetwork = false,
                                userInitiated = false,
                                preferLockFirst = false,
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

                applying.set(false)
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

        normalWorker.shutdownNow()
        screenWorker.shutdownNow()

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
