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
    private val applyWorker =
        Executors.newSingleThreadExecutor { r ->
            Thread(r, "bbpix-apply").apply {
                priority = Thread.NORM_PRIORITY + 1
            }
        }

    /*
     * SCREEN_OFF must never wait behind startup/cache maintenance.
     * Keep this lane dedicated to the latency-sensitive apply path.
     */
    private val screenApplyWorker =
        Executors.newSingleThreadExecutor { r ->
            Thread(r, "bbpix-screen-apply").apply {
                priority = Thread.MAX_PRIORITY
            }
        }

    private val applying = AtomicBoolean(false)

    /*
     * Performance Guard:
     * deep maintenance stays single-flight and off the
     * latency-sensitive screen event path.
     */
    private val healthAuditRunning =
        AtomicBoolean(false)

    @Volatile
    private var lastFullHealthAt =
        0L

    private val fullHealthIntervalMs =
        60L * 60L * 1000L

    private val heartbeatIntervalMs =
        5L * 60L * 1000L

    private val startupHealthGraceMs =
        30L * 60L * 1000L

    /*
     * Background maintenance is deliberately slower than wallpaper
     * rotation. One service start / one screen event must never fan out
     * into several cloud/cache jobs.
     */
    private val maintenanceRunning =
        AtomicBoolean(false)

    private val maintenanceCooldownMs =
        10L * 60L * 1000L

    private val rootTuneCooldownMs =
        30L * 60L * 1000L

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
    private val healthTask =
        object : Runnable {
            override fun run() {
                val now =
                    System.currentTimeMillis()

                /*
                 * Cheap heartbeat only.
                 * Deep audit/cache repair is NOT run every heartbeat.
                 */
                RuntimeStatus.setLong(
                    applicationContext,
                    "performance_heartbeat_at",
                    now,
                )

                RuntimeStatus.set(
                    applicationContext,
                    "performance_guard",
                    "Idle-safe • instant screen path armed",
                )

                val elapsed =
                    now -
                        lastFullHealthAt

                if (
                    elapsed >=
                        fullHealthIntervalMs &&
                    healthAuditRunning.compareAndSet(
                        false,
                        true,
                    )
                ) {
                    lastFullHealthAt =
                        now

                    EngineExecutors.io {
                        val started =
                            System.currentTimeMillis()

                        try {
                            val latest =
                                SettingsStore(
                                    this@WallpaperAutomationService
                                ).load()

                            runCatching {
                                EngineHealth.auditAndRepair(
                                    applicationContext,
                                    latest,
                                    allowNetworkRefill =
                                        false,
                                )
                            }

                            if (
                                latest.engineMode ==
                                    EngineMode.ADVANCED &&
                                latest.backgroundGuardEnabled
                            ) {
                                val lastTune =
                                    RuntimeStatus.getLong(
                                        applicationContext,
                                        "root_last_tune",
                                        0L,
                                    )

                                if (
                                    System.currentTimeMillis() -
                                        lastTune >=
                                    30L * 60L * 1000L
                                ) {
                                    runCatching {
                                        RootAccess.tuneBackground(
                                            applicationContext
                                        )
                                    }
                                }
                            }
                        } finally {
                            val finished =
                                System.currentTimeMillis()

                            RuntimeStatus.setLong(
                                applicationContext,
                                "performance_full_audit_at",
                                finished,
                            )

                            RuntimeStatus.setLong(
                                applicationContext,
                                "performance_full_audit_ms",
                                finished - started,
                            )

                            RuntimeStatus.set(
                                applicationContext,
                                "performance_audit_state",
                                "Complete • ${
                                    finished -
                                        started
                                }ms",
                            )

                            healthAuditRunning.set(false)
                        }
                    }
                } else {
                    RuntimeStatus.set(
                        applicationContext,
                        "performance_audit_state",
                        if (
                            healthAuditRunning.get()
                        ) {
                            "Audit already running"
                        } else {
                            "Sleeping • deep audit throttled"
                        },
                    )
                }

                handler.postDelayed(
                    this,
                    heartbeatIntervalMs,
                )
            }
        }


    private fun maybeTuneRoot(
        settings: AppSettings,
    ) {
        if (
            settings.engineMode != EngineMode.ADVANCED ||
            !settings.backgroundGuardEnabled
        ) {
            return
        }

        val now =
            System.currentTimeMillis()

        val lastTune =
            RuntimeStatus.getLong(
                applicationContext,
                "root_last_tune",
                0L,
            )

        if (
            now - lastTune <
            rootTuneCooldownMs
        ) {
            RuntimeStatus.set(
                applicationContext,
                "root_guard_state",
                "Sleeping • root tuning already fresh",
            )

            return
        }

        runCatching {
            RootAccess.tuneBackground(
                applicationContext
            )
        }

        RuntimeStatus.set(
            applicationContext,
            "root_guard_state",
            "Verified",
        )
    }

    private fun scheduleMaintenance(
        settings: AppSettings,
        reason: String,
        allowNetwork: Boolean,
    ) {
        val now =
            System.currentTimeMillis()

        val lastStarted =
            RuntimeStatus.getLong(
                applicationContext,
                "maintenance_last_started",
                0L,
            )

        if (
            now - lastStarted <
            maintenanceCooldownMs
        ) {
            RuntimeStatus.set(
                applicationContext,
                "maintenance_state",
                "Deferred • cooldown • $reason",
            )

            return
        }

        if (
            !maintenanceRunning.compareAndSet(
                false,
                true,
            )
        ) {
            RuntimeStatus.set(
                applicationContext,
                "maintenance_state",
                "Coalesced • already running",
            )

            return
        }

        RuntimeStatus.setLong(
            applicationContext,
            "maintenance_last_started",
            now,
        )

        RuntimeStatus.set(
            applicationContext,
            "maintenance_state",
            "Running • $reason",
        )

        EngineExecutors.io {
            try {
                val latest =
                    SettingsStore(
                        applicationContext
                    ).load()

                /*
                 * First satisfy the queue from existing local/cache files.
                 * This is cheap and network-free.
                 */
                val ready =
                    runCatching {
                        WallpaperController.ensureNext(
                            applicationContext,
                            latest,
                            allowNetwork = false,
                        )
                    }.getOrDefault(false)

                val target =
                    latest.cacheTarget
                        .coerceIn(4, 36)

                /*
                 * Refill only when inventory is actually below target.
                 * primeCache() already applies thermal/battery policy.
                 */
                if (
                    WallpaperController.cacheCount() <
                    target
                ) {
                    runCatching {
                        WallpaperController.primeCache(
                            applicationContext,
                            latest,
                        )
                    }
                }

                /*
                 * Cloud preparation is last-resort only. A prepared local
                 * pair means no network work is required for this pass.
                 */
                if (
                    !ready &&
                    allowNetwork &&
                    bb.pix.wall.engine
                        .WallpaperSourceEngine
                        .networkAvailable(
                            applicationContext
                        )
                ) {
                    runCatching {
                        WallpaperController.ensureNext(
                            applicationContext,
                            latest,
                            allowNetwork = true,
                        )
                    }
                }

                maybeTuneRoot(
                    latest
                )
            } finally {
                val finished =
                    System.currentTimeMillis()

                RuntimeStatus.setLong(
                    applicationContext,
                    "maintenance_last_finished",
                    finished,
                )

                RuntimeStatus.set(
                    applicationContext,
                    "maintenance_state",
                    "Idle",
                )

                maintenanceRunning.set(
                    false
                )
            }
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

        val previousHealth =
            RuntimeStatus.getLong(
                applicationContext,
                "engine_health_last",
                0L,
            )

        if (previousHealth > 0L) {
            lastFullHealthAt =
                previousHealth
        }


        if (
            System.currentTimeMillis() -
                previousHealth >=
            startupHealthGraceMs &&
            healthAuditRunning.compareAndSet(
                false,
                true,
            )
        ) {
            EngineExecutors.io {
                val started =
                    System.currentTimeMillis()

                try {
                    runCatching {
                        EngineHealth.auditAndRepair(
                            applicationContext,
                            s,
                            allowNetworkRefill =
                                false,
                        )
                    }

                    lastFullHealthAt =
                        System.currentTimeMillis()
                } finally {
                    RuntimeStatus.setLong(
                        applicationContext,
                        "performance_full_audit_ms",
                        System.currentTimeMillis() -
                            started,
                    )

                    healthAuditRunning.set(false)
                }
            }
        }

        EngineExecutors.io {
            maybeTuneRoot(
                s
            )
        }

        /*
         * Service startup is intentionally light.
         * Queue preparation is local and immediate; all expensive work is
         * coalesced behind the maintenance guard.
         */
        applyWorker.execute {
            runCatching {
                WallpaperController.ensureNext(
                    applicationContext,
                    s,
                    allowNetwork = false,
                )
            }
        }

        scheduleMaintenance(
            settings = s,
            reason = "service-start",
            allowNetwork = true,
        )

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

    private fun applyIfAllowed(
        s: AppSettings,
        reason: String,
    ) {
        val now =
            System.currentTimeMillis()

        if (now - lastApplyStartedAt < 1200L) {
            recordTrigger(reason, "debounced")
            return
        }

        val denied =
            conditionsDeniedReason(s)

        if (denied != null) {
            RuntimeStatus.set(
                applicationContext,
                "automation_block_reason",
                denied,
            )

            RuntimeStatus.set(
                applicationContext,
                "automation_state",
                "Blocked • $denied",
            )

            recordTrigger(
                reason,
                "blocked:$denied",
            )
            return
        }

        RuntimeStatus.set(
            applicationContext,
            "automation_block_reason",
            "None",
        )

        if (!applying.compareAndSet(false, true)) {
            recordTrigger(reason, "busy")
            return
        }

        lastApplyStartedAt = now

        val screenOff =
            reason == "event:SCREEN_OFF"

        if (screenOff) {
            RuntimeStatus.setLong(
                applicationContext,
                "screen_event_received",
                now,
            )

            RuntimeStatus.set(
                applicationContext,
                "instant_lock_path",
                "Triggered • prepared/offline path",
            )
        }

        RuntimeStatus.set(
            applicationContext,
            "automation_state",
            "Applying",
        )

        recordTrigger(reason, "start")

        val wakeLock =
            if (screenOff) {
                (
                    getSystemService(POWER_SERVICE)
                        as PowerManager
                )
                    .newWakeLock(
                        PowerManager.PARTIAL_WAKE_LOCK,
                        "$packageName:screenOffApply",
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
                screenApplyWorker
            } else {
                applyWorker
            }

        try {
            worker.execute {
                if (screenOff) {
                    RuntimeStatus.setLong(
                        applicationContext,
                        "screen_apply_worker_started",
                        System.currentTimeMillis(),
                    )
                }

                try {
                    /*
                     * Screen/event path is deliberately network-free.
                     * Consume a prepared wallpaper immediately.
                     */
                    var changed =
                        WallpaperController.nextWall(
                            applicationContext,
                            allowNetwork = false,
                            preferLockFirst =
                                screenOff,
                        )

                    if (!changed) {
                        runCatching {
                            WallpaperController.ensureNext(
                                applicationContext,
                                s,
                                allowNetwork = false,
                            )
                        }

                        changed =
                            WallpaperController.nextWall(
                            applicationContext,
                            allowNetwork = false,
                            preferLockFirst =
                                screenOff,
                        )
                    }

                    recordTrigger(
                        reason,
                        if (changed) {
                            "applied"
                        } else {
                            "no-ready-wall"
                        },
                    )

                    if (
                        screenOff &&
                        changed
                    ) {
                        val lockFinished =
                            RuntimeStatus.getLong(
                                applicationContext,
                                "apply_lock_finished",
                                0L,
                            )

                        val received =
                            RuntimeStatus.getLong(
                                applicationContext,
                                "screen_event_received",
                                now,
                            )

                        if (
                            lockFinished >=
                            received
                        ) {
                            RuntimeStatus.setLong(
                                applicationContext,
                                "instant_lock_visible_ms",
                                lockFinished - received,
                            )
                        }
                    }

                    RuntimeStatus.set(
                        applicationContext,
                        "automation_state",
                        if (changed) {
                            "Ready"
                        } else {
                            "Waiting for cache"
                        },
                    )

                    /*
                     * Local preparation happens after the visible apply.
                     * Network refill remains completely outside the
                     * SCREEN_OFF critical path.
                     */
                    runCatching {
                        WallpaperController.ensureNext(
                            applicationContext,
                            SettingsStore(
                                applicationContext
                            ).load(),
                            allowNetwork = false,
                        )
                    }

                    scheduleMaintenance(
                        settings =
                            SettingsStore(
                                applicationContext
                            ).load(),
                        reason =
                            "post-apply",
                        allowNetwork = true,
                    )
                } catch (t: Throwable) {
                    RuntimeStatus.failure(
                        applicationContext,
                        "Automation: ${
                            t.message
                                ?: t.javaClass.simpleName
                        }",
                    )

                    recordTrigger(
                        reason,
                        "error:${t.javaClass.simpleName}",
                    )
                } finally {
                    if (screenOff) {
                        val finished =
                            System.currentTimeMillis()

                        RuntimeStatus.setLong(
                            applicationContext,
                            "screen_apply_finished",
                            finished,
                        )

                        val received =
                            RuntimeStatus.getLong(
                                applicationContext,
                                "screen_event_received",
                                finished,
                            )

                        RuntimeStatus.setLong(
                            applicationContext,
                            "instant_lock_total_ms",
                            finished - received,
                        )

                        RuntimeStatus.set(
                            applicationContext,
                            "instant_lock_path",
                            "Complete • ${
                                finished -
                                    received
                            }ms",
                        )
                    }

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
        } catch (t: Throwable) {
            if (
                wakeLock != null &&
                wakeLock.isHeld
            ) {
                runCatching {
                    wakeLock.release()
                }
            }

            applying.set(false)
            throw t
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
        screenApplyWorker.shutdownNow()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
    private fun createChannel() { getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL, "Wallpaper automation", NotificationManager.IMPORTANCE_LOW)) }
    companion object { const val CHANNEL = "bb_pixwall_automation" }
}
