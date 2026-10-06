package bb.pix.wall.network

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import bb.pix.wall.R
import bb.pix.wall.automation.WallpaperAutomationService
import bb.pix.wall.engine.WallpaperController
import bb.pix.wall.engine.WallpaperFiles
import bb.pix.wall.root.RootAccess
import bb.pix.wall.settings.*
import bb.pix.wall.ui.theme.ThemeProfile
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.SecureRandom
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import bb.pix.wall.engine.EngineExecutors
import bb.pix.wall.engine.RuntimeStatus

class LanServerService : Service() {
    private val running = AtomicBoolean(false)
    private val generation = java.util.concurrent.atomic.AtomicLong(0L)
    @Volatile private var server: ServerSocket? = null
    @Volatile private var boundPort: Int = -1
    @Volatile private var requestedPort: Int = -1
    @Volatile private var restartAttempt = 0
    @Volatile private var lastBoundIp: String = "Unavailable"
    private var acceptExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "bbpix-lan-accept") }
    private var clientPool = Executors.newFixedThreadPool(6) { r -> Thread(r, "bbpix-lan-client") }
    private val lastRequest = java.util.concurrent.ConcurrentHashMap<String, Long>()
    @Volatile private var rebindFuture: java.util.concurrent.ScheduledFuture<*>? = null
    @Volatile private var watchdogFuture: java.util.concurrent.ScheduledFuture<*>? = null
    @Volatile private var acceptAlive = false

    private val networkListener: () -> Unit = {
        rebindFuture?.cancel(false)
        rebindFuture = EngineExecutors.scheduler.schedule({
            val st = SettingsStore(this).load()
            if (!st.lanEnabled) return@schedule
            // Listener is bound to 0.0.0.0, so an IP/capability change does not require
            // tearing down a healthy server. Repeated network callbacks previously caused
            // restart races and the "Remote dashboard is not ready" loop.
            lastBoundIp = LanInfo.localIpv4()
            RuntimeStatus.set(this, "lan_ip", lastBoundIp)
            if (!running.get() || server?.isClosed != false || !acceptAlive) restartServer(st.lanPort)
        }, 700, java.util.concurrent.TimeUnit.MILLISECONDS)
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        NetworkMonitor.register(this)
        NetworkMonitor.add(networkListener)
        startForeground(102, NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_tile_remote)
            .setContentTitle("BB-Remote")
            .setContentText("Local dashboard service is active")
            .setOngoing(true).setSilent(true).build())
        watchdogFuture = EngineExecutors.scheduler.scheduleWithFixedDelay({
            val st = SettingsStore(this).load()
            if (st.lanEnabled && (!running.get() || server?.isClosed != false || !acceptAlive)) {
                restartServer(st.lanPort)
            }
        }, 8, 20, java.util.concurrent.TimeUnit.SECONDS)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val st = SettingsStore(this).load()
        if (!st.lanEnabled) { stopSelf(); return START_NOT_STICKY }
        requestedPort = st.lanPort
        val expected = st.lanPort..(st.lanPort + 10).coerceAtMost(65535)
        if (!running.get() || boundPort !in expected || server?.isClosed != false) restartServer(st.lanPort)
        return START_STICKY
    }

    override fun onDestroy() {
        NetworkMonitor.remove(networkListener)
        rebindFuture?.cancel(false)
        watchdogFuture?.cancel(false)
        stopServer()
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null

    @Synchronized private fun restartServer(port: Int) {
        val gen = generation.incrementAndGet()
        requestedPort = port
        running.set(false)
        runCatching { server?.close() }
        server = null
        boundPort = -1
        acceptAlive = false
        runCatching { acceptExecutor.shutdownNow() }
        runCatching { clientPool.shutdownNow() }
        acceptExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "bbpix-lan-accept") }
        clientPool = Executors.newFixedThreadPool(6) { r -> Thread(r, "bbpix-lan-client") }
        running.set(true)
        acceptExecutor.execute { serve(port, gen) }
    }

    @Synchronized private fun stopServer() {
        generation.incrementAndGet()
        running.set(false)
        runCatching { server?.close() }
        server = null
        boundPort = -1
        acceptAlive = false
        runCatching { acceptExecutor.shutdownNow() }
        runCatching { clientPool.shutdownNow() }
        bb.pix.wall.engine.RuntimeStatus.set(this, "lan_state", "Stopped")
    }

    private fun serve(port: Int, gen: Long) {
        var last: Throwable? = null
        var actual = -1
        for (candidate in port..(port + 10).coerceAtMost(65535)) {
            if (!running.get() || generation.get() != gen) return
            try {
                val ss = ServerSocket()
                ss.reuseAddress = true
                ss.bind(java.net.InetSocketAddress("0.0.0.0", candidate), 32)
                if (generation.get() != gen) { ss.close(); return }
                server = ss
                actual = candidate
                boundPort = candidate
                lastBoundIp = LanInfo.localIpv4()
                restartAttempt = 0
                acceptAlive = true
                bb.pix.wall.engine.RuntimeStatus.set(this, "lan_state", "Running :$candidate")
                bb.pix.wall.engine.RuntimeStatus.setLong(this, "lan_port_actual", candidate.toLong())
                bb.pix.wall.engine.RuntimeStatus.set(this, "lan_ip", lastBoundIp)
                while (running.get() && generation.get() == gen && !ss.isClosed) {
                    val socket = try { ss.accept() } catch (_: Throwable) { break }
                    if (!clientPool.isShutdown) {
                        clientPool.execute {
                            runCatching { handle(socket) }.onFailure { t ->
                                if (t !is java.net.SocketException && t !is java.io.IOException) {
                                    bb.pix.wall.engine.RuntimeStatus.failure(this, "LAN client: ${t.message ?: t.javaClass.simpleName}")
                                }
                            }
                        }
                    } else runCatching { socket.close() }
                }
                acceptAlive = false
                break
            } catch (t: Throwable) {
                last = t
                runCatching { server?.close() }
                server = null
            }
        }
        if (generation.get() != gen || !running.get()) return
        if (actual < 0) bb.pix.wall.engine.RuntimeStatus.failure(this, "LAN bind failed: ${last?.message ?: "no port"}")
        val st = SettingsStore(this).load()
        if (st.lanEnabled) {
            val delay = (750L shl restartAttempt.coerceIn(0,4)).coerceAtMost(12_000L)
            restartAttempt++
            bb.pix.wall.engine.RuntimeStatus.set(this, "lan_state", "Restarting")
            EngineExecutors.scheduler.schedule({
                if (generation.get() == gen && SettingsStore(this).load().lanEnabled) restartServer(requestedPort.coerceAtLeast(1024))
            }, delay, java.util.concurrent.TimeUnit.MILLISECONDS)
        }
    }

    private fun handle(socket: Socket) = socket.use { s ->
        if (!(s.inetAddress.isSiteLocalAddress || s.inetAddress.isLoopbackAddress)) return
        val host = s.inetAddress.hostAddress ?: "local"
        s.soTimeout = 10_000
        val reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
        val requestLine = reader.readLine().orEmpty()
        val parts = requestLine.split(' ')
        val method = parts.getOrNull(0).orEmpty().uppercase()
        if (method == "POST") {
            val now = System.currentTimeMillis()
            val prev = lastRequest.put(host, now) ?: 0L
            if (now - prev < 120L) { sendText(s, 429, "text/plain", "Too many requests"); return }
        }
        val rawPath = parts.getOrNull(1) ?: "/"
        val path = rawPath.substringBefore('?')
        val headers = linkedMapOf<String, String>()
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isBlank()) break
            val idx = line.indexOf(':')
            if (idx > 0) headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
        }
        val len = headers["content-length"]?.toIntOrNull()?.coerceIn(0, 64 * 1024) ?: 0
        val body = if (len > 0) CharArray(len).also { reader.read(it, 0, len) }.concatToString() else ""

        val token = dashboardToken()
        val mutationAllowed = method == "POST" && headers["x-bbpixwall-token"] == token


        /*
         * Web history image endpoint.
         *
         * /img/history/<entry-id>/home
         * /img/history/<entry-id>/lock
         */
        if (path.startsWith("/img/history/")) {
            val routeParts =
                path.removePrefix(
                    "/img/history/"
                )
                    .split('/')
                    .filter {
                        it.isNotBlank()
                    }

            if (routeParts.size != 2) {
                sendText(
                    s,
                    404,
                    "text/plain; charset=utf-8",
                    "Invalid history path",
                )
                return
            }

            val historyId =
                URLDecoder.decode(
                    routeParts[0],
                    "UTF-8",
                )

            val side =
                routeParts[1]

            val entry =
                bb.pix.wall.engine
                    .WallpaperLibrary
                    .history()
                    .firstOrNull {
                        it.id == historyId
                    }

            val historyFile =
                when (side) {
                    "home" ->
                        entry?.home

                    "lock" ->
                        entry?.lock

                    else ->
                        null
                }

            if (
                historyFile == null ||
                !historyFile.exists() ||
                !historyFile.isFile
            ) {
                sendText(
                    s,
                    404,
                    "text/plain; charset=utf-8",
                    "History image unavailable",
                )
                return
            }

            sendFile(
                s,
                historyFile,
            )

            return
        }
        when (path) {
            "/health" -> sendJson(s, 200, "{\"ok\":true,\"port\":$boundPort,\"ip\":${quote(LanInfo.localIpv4())},\"state\":${quote(bb.pix.wall.engine.RuntimeStatus.get(this, "lan_state"))}}")
            "/api/next" -> sendJson(s, if (mutationAllowed) 200 else 403, if (mutationAllowed) "{\"ok\":${WallpaperController.nextWall(this, userInitiated = true)}}" else "{\"error\":\"forbidden\"}")
            "/api/previous" ->
                if (mutationAllowed) {
                    sendJson(
                        s,
                        200,
                        "{\"ok\":${WallpaperController.previousWall(this)}}"
                    )
                } else {
                    sendJson(
                        s,
                        403,
                        "{\"error\":\"forbidden\"}"
                    )
                }

            "/api/save" -> if (mutationAllowed) {
                val n = runCatching { WallpaperController.saveCurrent(this).size }.getOrDefault(0); sendJson(s, 200, "{\"saved\":$n}")
            } else sendJson(s, 403, "{\"error\":\"forbidden\"}")
            "/api/blur" -> if (mutationAllowed) sendJson(s, 200, "{\"blur\":${WallpaperController.toggleBlur(this)}}") else sendJson(s, 403, "{\"error\":\"forbidden\"}")
            "/api/test-sources" -> if (mutationAllowed) {
                val set = SettingsStore(this).load()
                val n = runCatching { bb.pix.wall.engine.WallpaperSourceEngine.collect(this, set, true).size }.getOrDefault(0)
                sendJson(s, 200, "{\"candidates\":$n,\"photos\":${quote(bb.pix.wall.engine.RuntimeStatus.get(this,"source_photos"))},\"drive\":${quote(bb.pix.wall.engine.RuntimeStatus.get(this,"source_drive"))},\"local\":${quote(bb.pix.wall.engine.RuntimeStatus.get(this,"source_local"))}}")
            } else sendJson(s, 403, "{\"error\":\"forbidden\"}")
            "/api/prepare" -> if (mutationAllowed) {
                val settings = SettingsStore(this).load()
                runCatching { WallpaperController.primeCache(this, settings); WallpaperController.ensureNext(this, settings) }
                sendJson(s, 200, "{\"ok\":true,\"cache\":${WallpaperController.cacheCount()}}")
            } else sendJson(s, 403, "{\"error\":\"forbidden\"}")
            "/api/cache-clear" -> if (mutationAllowed) sendJson(s, 200, "{\"cleared\":${WallpaperController.clearCache()}}") else sendJson(s, 403, "{\"error\":\"forbidden\"}")
            "/api/refresh-next" ->
                if (mutationAllowed) {
                    WallpaperController.invalidateQueue()

                    listOf(
                        WallpaperFiles.nextHome,
                        WallpaperFiles.nextLock,
                    ).forEach { file ->
                        file.delete()

                        File(
                            file.absolutePath +
                                ".meta"
                        ).delete()
                    }

                    val settings =
                        SettingsStore(this)
                            .load()

                    val ready =
                        runCatching {
                            WallpaperController.ensureNext(
                                this,
                                settings,
                            )
                        }.getOrDefault(false)

                    sendJson(
                        s,
                        200,
                        "{\"ok\":$ready}",
                    )
                } else {
                    sendJson(
                        s,
                        403,
                        "{\"error\":\"forbidden\"}",
                    )
                }

            "/api/surprise" ->
                if (mutationAllowed) {
                    WallpaperController.invalidateQueue()

                    val ok =
                        runCatching {
                            WallpaperController.nextWall(
                                this,
                                userInitiated = true,
                            )
                        }.getOrDefault(false)

                    sendJson(
                        s,
                        200,
                        "{\"ok\":$ok}",
                    )
                } else {
                    sendJson(
                        s,
                        403,
                        "{\"error\":\"forbidden\"}",
                    )
                }

            "/api/weather-refresh" ->
                if (mutationAllowed) {
                    val settings =
                        SettingsStore(this)
                            .load()

                    bb.pix.wall.engine
                        .WeatherMoodEngine
                        .forceRefresh(
                            this,
                            settings,
                        )

                    sendJson(
                        s,
                        200,
                        "{\"ok\":true}",
                    )
                } else {
                    sendJson(
                        s,
                        403,
                        "{\"error\":\"forbidden\"}",
                    )
                }

            "/api/purge-blocked" ->
                if (mutationAllowed) {
                    val removed =
                        WallpaperController
                            .purgeBlockedCached(
                                this
                            )

                    sendJson(
                        s,
                        200,
                        "{\"removed\":$removed}",
                    )
                } else {
                    sendJson(
                        s,
                        403,
                        "{\"error\":\"forbidden\"}",
                    )
                }

            "/api/premium-refresh",
            "/api/reanalyze-current",
            "/api/cache-integrity",
            "/api/premium-self-heal",
            "/api/reset-source-photos",
            "/api/reset-source-drive",
            "/api/export-intelligence",
            "/api/autonomy-audit" ->
                sendJson(
                    s,
                    410,
                    "{\"error\":\"disabled-in-lite\"}",
                )

            "/api/rebuild-next" ->
                if (mutationAllowed) {
                    WallpaperController.invalidateQueue()

                    bb.pix.wall.engine
                        .EngineExecutors
                        .io {
                            runCatching {
                                WallpaperController.ensureNext(
                                    applicationContext,
                                    SettingsStore(
                                        applicationContext
                                    ).load(),
                                    allowNetwork = true,
                                )
                            }
                        }

                    sendJson(
                        s,
                        200,
                        "{\"scheduled\":true}",
                    )
                } else {
                    sendJson(
                        s,
                        403,
                        "{\"error\":\"forbidden\"}",
                    )
                }

            "/api/history" ->
                sendJson(
                    s,
                    200,
                    historyJson(),
                )

            "/api/history-restore" ->
                if (mutationAllowed) {
                    val form =
                        parseForm(body)

                    val id =
                        form["id"]
                            .orEmpty()

                    val entry =
                        bb.pix.wall.engine
                            .WallpaperLibrary
                            .history()
                            .firstOrNull {
                                it.id == id
                            }

                    val ok =
                        entry?.let {
                            WallpaperController
                                .restoreHistory(
                                    this,
                                    it,
                                )
                        } ?: false

                    sendJson(
                        s,
                        200,
                        "{\"ok\":$ok}",
                    )
                } else {
                    sendJson(
                        s,
                        403,
                        "{\"error\":\"forbidden\"}",
                    )
                }

            "/api/library-action" ->
                if (mutationAllowed) {
                    sendJson(
                        s,
                        200,
                        libraryActionJson(
                            parseForm(body)
                        ),
                    )
                } else {
                    sendJson(
                        s,
                        403,
                        "{\"error\":\"forbidden\"}",
                    )
                }

            "/api/logs" -> sendText(s, 200, "text/plain; charset=utf-8", runCatching { WallpaperFiles.runtimeLog.readLines().takeLast(80).joinToString("\n") }.getOrDefault("No logs yet"))
            "/api/triggers" -> sendText(s, 200, "text/plain; charset=utf-8", runCatching { WallpaperFiles.triggerHistory.readLines().takeLast(80).joinToString("\n") }.getOrDefault("No trigger history yet"))
            "/api/queue" -> sendJson(s, 200, queueJson())
            "/api/root-diagnostics" -> sendText(s, 200, "text/plain; charset=utf-8", RootAccess.diagnosticsText(this))
            "/api/debug-report" -> if (mutationAllowed) sendText(s, 200, "text/plain; charset=utf-8", WallpaperController.exportDebugReport(this).readText()) else sendJson(s, 403, "{\"error\":\"forbidden\"}")
            "/api/settings" -> {
                if (method == "GET") sendJson(s, 200, settingsJson())
                else if (mutationAllowed) { updateSettings(parseForm(body)); sendJson(s, 200, settingsJson()) }
                else sendJson(s, 403, "{\"error\":\"forbidden\"}")
            }
            "/api/advanced" -> if (mutationAllowed) {
                val result = RootAccess.requestAndTune(this)
                if (result.granted) {
                    val store = SettingsStore(this); store.save(store.load().copy(engineMode = EngineMode.ADVANCED))
                    sendJson(s, 200, "{\"root\":true}")
                } else sendJson(s, 200, "{\"root\":false,\"detail\":${quote(result.detail)}}")
            } else sendJson(s, 403, "{\"error\":\"forbidden\"}")
            "/api/standard" -> if (mutationAllowed) {
                val store = SettingsStore(this); store.save(store.load().copy(engineMode = EngineMode.STANDARD)); sendJson(s, 200, "{\"ok\":true}")
            } else sendJson(s, 403, "{\"error\":\"forbidden\"}")
            "/img/current-home" ->
                sendFile(
                    s,
                    WallpaperController.previewFile(
                        this,
                        WallpaperFiles.currentHome,
                        true,
                    )
                )
            "/img/current-lock" ->
                sendFile(
                    s,
                    WallpaperController.previewFile(
                        this,
                        WallpaperFiles.currentLock,
                        false,
                    )
                )
            "/img/next-home" ->
                sendFile(
                    s,
                    WallpaperFiles.nextHome,
                )
            "/img/next-lock" ->
                sendFile(
                    s,
                    WallpaperFiles.nextLock,
                )
            "/", "/index.html" -> sendText(s, 200, "text/html; charset=utf-8", dashboard(token))
            else -> sendText(s, 404, "text/plain; charset=utf-8", "Not found")
        }
    }

    private fun updateSettings(
        form: Map<String, String>,
    ) {
        val store =
            SettingsStore(this)

        val old =
            store.load()

        fun bool(
            key: String,
            fallback: Boolean,
        ): Boolean =
            form[key]?.let {
                it == "true" ||
                    it == "1" ||
                    it == "on"
            } ?: fallback

        fun <T : Enum<T>> enumValue(
            raw: String?,
            values: Array<T>,
            fallback: T,
        ): T =
            values.firstOrNull {
                it.name == raw
            } ?: fallback

        val updated =
            old.copy(
                photosAlbumUrl =
                    form["photosAlbumUrl"]
                        ?: old.photosAlbumUrl,

                driveFolderUrl =
                    form["driveFolderUrl"]
                        ?: old.driveFolderUrl,

                autoChange =
                    bool("autoChange", old.autoChange),

                triggerMode =
                    enumValue(
                        form["triggerMode"],
                        TriggerMode.entries.toTypedArray(),
                        old.triggerMode,
                    ),

                intervalMinutes =
                    form["intervalMinutes"]
                        ?.toIntOrNull()
                        ?.coerceIn(1, 300)
                        ?: old.intervalMinutes,

                targetMode =
                    enumValue(
                        form["targetMode"],
                        WallpaperTargetMode.entries.toTypedArray(),
                        old.targetMode,
                    ),

                wallpaperOrder =
                    enumValue(
                        form["wallpaperOrder"],
                        WallpaperOrder.entries.toTypedArray(),
                        old.wallpaperOrder,
                    ),

                appearanceMode =
                    enumValue(
                        form["appearanceMode"],
                        AppearanceMode.entries.toTypedArray(),
                        old.appearanceMode,
                    ),

                aspectPreference =
                    enumValue(
                        form["aspectPreference"],
                        AspectPreference.entries.toTypedArray(),
                        old.aspectPreference,
                    ),

                homeBlurEnabled =
                    bool(
                        "homeBlurEnabled",
                        old.homeBlurEnabled,
                    ),

                lockBlurEnabled =
                    bool(
                        "lockBlurEnabled",
                        old.lockBlurEnabled,
                    ),

                homeBlurRadius =
                    form["homeBlurRadius"]
                        ?.toIntOrNull()
                        ?.coerceIn(0, 64)
                        ?: old.homeBlurRadius,

                lockBlurRadius =
                    form["lockBlurRadius"]
                        ?.toIntOrNull()
                        ?.coerceIn(0, 64)
                        ?: old.lockBlurRadius,

                dataSaverEnabled =
                    bool(
                        "dataSaverEnabled",
                        old.dataSaverEnabled,
                    ),

                wifiOnly =
                    bool(
                        "wifiOnly",
                        old.wifiOnly,
                    ),

                mobileDataAllowed =
                    bool(
                        "mobileDataAllowed",
                        old.mobileDataAllowed,
                    ),

                chargingOnly =
                    bool(
                        "chargingOnly",
                        old.chargingOnly,
                    ),

                pauseBatterySaver =
                    bool(
                        "pauseBatterySaver",
                        old.pauseBatterySaver,
                    ),

                pauseLowBattery =
                    bool(
                        "pauseLowBattery",
                        old.pauseLowBattery,
                    ),

                lowBatteryThreshold =
                    form["lowBatteryThreshold"]
                        ?.toIntOrNull()
                        ?.coerceIn(5, 50)
                        ?: old.lowBatteryThreshold,

                quietHoursEnabled =
                    bool(
                        "quietHoursEnabled",
                        old.quietHoursEnabled,
                    ),

                quietStartHour =
                    form["quietStartHour"]
                        ?.toIntOrNull()
                        ?.coerceIn(0, 23)
                        ?: old.quietStartHour,

                quietEndHour =
                    form["quietEndHour"]
                        ?.toIntOrNull()
                        ?.coerceIn(0, 23)
                        ?: old.quietEndHour,

                cacheMaxMb =
                    form["cacheMaxMb"]
                        ?.toIntOrNull()
                        ?.coerceIn(128, 2048)
                        ?: old.cacheMaxMb,

                lowStorageReserveMb =
                    form["lowStorageReserveMb"]
                        ?.toIntOrNull()
                        ?.coerceIn(256, 8192)
                        ?: old.lowStorageReserveMb,

                backgroundGuardEnabled =
                    bool(
                        "backgroundGuardEnabled",
                        old.backgroundGuardEnabled,
                    ),

                smartCropEnabled = false,
                smartCropTolerancePct =
                    old.smartCropTolerancePct,
                perceptualDistance =
                    old.perceptualDistance,
                leanStorageMode = true,
                smartPairingEnabled = false,
                adaptiveResourceProtectionEnabled = false,
                decisionEngineEnabled = false,
                sourcePriorityMode =
                    SourcePriorityMode.PHOTOS_FIRST,
                cacheTarget =
                    old.cacheTarget.coerceIn(4, 8),
                moodEngineEnabled = false,
                moodAmbientLightEnabled = false,
                moodTimeEnabled = false,
                moodDarkModeEnabled = false,
                moodBatteryContextEnabled = false,
                moodThermalProtectionEnabled = false,
                moodWeatherEnabled = false,
                moodUseDeviceLocation = false,
                moodOutdoorTemperatureEnabled = false,
                moodAutoReactEnabled = false,

            )

        store.save(updated)

        val automationIntent =
            Intent(
                this,
                WallpaperAutomationService::class.java,
            )

        if (updated.autoChange) {
            startForegroundService(
                automationIntent
            )
        } else {
            stopService(
                automationIntent
            )
        }

        val blurChanged =
            old.homeBlurEnabled !=
                updated.homeBlurEnabled ||
                old.lockBlurEnabled !=
                updated.lockBlurEnabled ||
                old.homeBlurRadius !=
                updated.homeBlurRadius ||
                old.lockBlurRadius !=
                updated.lockBlurRadius

        if (blurChanged) {
            Thread {
                WallpaperController
                    .setBlurMasterAndReapply(
                        this,
                        updated.homeBlurEnabled ||
                            updated.lockBlurEnabled,
                    )
            }.start()
        }

        form["theme"]
            ?.let { raw ->
                ThemeProfile.entries
                    .firstOrNull {
                        it.name == raw
                    }
                    ?.let { theme ->
                        getSharedPreferences(
                            "bb_pixwall_ui",
                            Context.MODE_PRIVATE,
                        ).edit()
                            .putString(
                                "theme",
                                theme.name,
                            )
                            .apply()
                    }
            }

        val sourcePipelineChanged =
            old.photosAlbumUrl !=
                updated.photosAlbumUrl ||
                old.driveFolderUrl !=
                updated.driveFolderUrl ||
                old.wallpaperOrder !=
                updated.wallpaperOrder ||
                old.sourcePriorityMode !=
                updated.sourcePriorityMode ||
                old.dataSaverEnabled !=
                updated.dataSaverEnabled ||
                old.aspectPreference !=
                updated.aspectPreference ||
                old.perceptualDistance !=
                updated.perceptualDistance

        if (sourcePipelineChanged) {
            bb.pix.wall.engine
                .WallpaperSourceEngine
                .invalidateCloudIndex()

            WallpaperController
                .invalidateQueue()
        }

        Thread {
            runCatching {
                WallpaperController
                    .ensureNext(
                        this,
                        updated,
                        allowNetwork = true,
                    )
            }
        }.start()
    }

    private fun settingsJson(): String {
        val x =
            SettingsStore(this)
                .load()

        val state =
            WallpaperController
                .state()

        val theme =
            getSharedPreferences(
                "bb_pixwall_ui",
                Context.MODE_PRIVATE,
            ).getString(
                "theme",
                ThemeProfile.SIGNATURE.name,
            )

        return buildString {
            append("{")

            append("\"engineMode\":${quote(x.engineMode.name)},")
            append("\"cache\":${WallpaperController.cacheCount()},")
            append("\"cacheBytes\":${WallpaperController.cacheBytes()},")
            append("\"cacheBreakdown\":${quote(WallpaperController.cacheBreakdown())},")
            append("\"offlineReady\":${WallpaperController.offlineReadyCount()},")
            append("\"cycleProgress\":${quote(WallpaperController.cycleProgress(this@LanServerService))},")

            append("\"photosAlbumUrl\":${quote(x.photosAlbumUrl)},")
            append("\"driveFolderUrl\":${quote(x.driveFolderUrl)},")
            append("\"sourcePriorityMode\":${quote(x.sourcePriorityMode.name)},")

            append("\"autoChange\":${x.autoChange},")
            append("\"triggerMode\":${quote(x.triggerMode.name)},")
            append("\"intervalMinutes\":${x.intervalMinutes},")
            append("\"targetMode\":${quote(x.targetMode.name)},")
            append("\"wallpaperOrder\":${quote(x.wallpaperOrder.name)},")

            append("\"homeBlurEnabled\":${x.homeBlurEnabled},")
            append("\"homeBlurRadius\":${x.homeBlurRadius},")
            append("\"lockBlurEnabled\":${x.lockBlurEnabled},")
            append("\"lockBlurRadius\":${x.lockBlurRadius},")

            append("\"appearanceMode\":${quote(x.appearanceMode.name)},")
            append("\"dataSaverEnabled\":${x.dataSaverEnabled},")
            append("\"wifiOnly\":${x.wifiOnly},")
            append("\"mobileDataAllowed\":${x.mobileDataAllowed},")
            append("\"chargingOnly\":${x.chargingOnly},")
            append("\"pauseBatterySaver\":${x.pauseBatterySaver},")
            append("\"pauseLowBattery\":${x.pauseLowBattery},")
            append("\"lowBatteryThreshold\":${x.lowBatteryThreshold},")
            append("\"quietHoursEnabled\":${x.quietHoursEnabled},")
            append("\"quietStartHour\":${x.quietStartHour},")
            append("\"quietEndHour\":${x.quietEndHour},")

            append("\"cacheTarget\":${x.cacheTarget},")
            append("\"cacheMaxMb\":${x.cacheMaxMb},")
            append("\"lowStorageReserveMb\":${x.lowStorageReserveMb},")
            append("\"backgroundGuardEnabled\":${x.backgroundGuardEnabled},")
            append("\"smartCropEnabled\":${x.smartCropEnabled},")
            append("\"smartCropTolerancePct\":${x.smartCropTolerancePct},")
            append("\"aspectPreference\":${quote(x.aspectPreference.name)},")
            append("\"perceptualDistance\":${x.perceptualDistance},")
            append("\"leanStorageMode\":${x.leanStorageMode},")
            append("\"smartPairingEnabled\":${x.smartPairingEnabled},")
            append("\"adaptiveResourceProtectionEnabled\":${x.adaptiveResourceProtectionEnabled},")
            append("\"decisionEngineEnabled\":${x.decisionEngineEnabled},")

            append("\"moodEngineEnabled\":${x.moodEngineEnabled},")
            append("\"moodAmbientLightEnabled\":${x.moodAmbientLightEnabled},")
            append("\"moodTimeEnabled\":${x.moodTimeEnabled},")
            append("\"moodDarkModeEnabled\":${x.moodDarkModeEnabled},")
            append("\"moodBatteryContextEnabled\":${x.moodBatteryContextEnabled},")
            append("\"moodThermalProtectionEnabled\":${x.moodThermalProtectionEnabled},")
            append("\"moodStrength\":${x.moodStrength},")
            append("\"moodDarkLuxThreshold\":${x.moodDarkLuxThreshold},")
            append("\"moodBrightLuxThreshold\":${x.moodBrightLuxThreshold},")
            append("\"moodWeatherEnabled\":${x.moodWeatherEnabled},")
            append("\"moodUseDeviceLocation\":${x.moodUseDeviceLocation},")
            append("\"moodWeatherCity\":${quote(x.moodWeatherCity)},")
            append("\"moodWeatherInfluence\":${x.moodWeatherInfluence},")
            append("\"moodOutdoorTemperatureEnabled\":${x.moodOutdoorTemperatureEnabled},")
            append("\"moodColdTemperatureC\":${x.moodColdTemperatureC},")
            append("\"moodHotTemperatureC\":${x.moodHotTemperatureC},")
            append("\"moodAutoReactEnabled\":${x.moodAutoReactEnabled},")
            append("\"moodAutoReactCooldownMinutes\":${x.moodAutoReactCooldownMinutes},")

            append("\"theme\":${quote(theme)},")
            append("\"blurMaster\":${WallpaperController.blurMasterEnabled(this@LanServerService)},")

            append("\"currentHome\":${quote(state.currentHome)},")
            append("\"currentLock\":${quote(state.currentLock)},")
            append("\"nextHome\":${quote(state.nextHome)},")
            append("\"nextLock\":${quote(state.nextLock)},")

            append("\"currentHomeInfo\":${wallInfoJson(WallpaperFiles.currentHome, true)},")
            append("\"currentLockInfo\":${wallInfoJson(WallpaperFiles.currentLock, false)},")
            append("\"nextHomeInfo\":${wallInfoJson(WallpaperFiles.nextHome, true, false)},")
            append("\"nextLockInfo\":${wallInfoJson(WallpaperFiles.nextLock, false, false)},")

            append("\"activeSource\":${quote(RuntimeStatus.get(this@LanServerService, "active_source", "None"))},")
            append("\"photosHealth\":${quote(RuntimeStatus.get(this@LanServerService, "source_photos", "Unknown"))},")
            append("\"driveHealth\":${quote(RuntimeStatus.get(this@LanServerService, "source_drive", "Unknown"))},")
            append("\"localHealth\":${quote(RuntimeStatus.get(this@LanServerService, "source_local", "Unknown"))},")

            append("\"lastPipeline\":${quote(RuntimeStatus.get(this@LanServerService, "last_pipeline", "Waiting"))},")
            append("\"lastAspect\":${quote(RuntimeStatus.get(this@LanServerService, "last_aspect", "Unknown"))},")
            append("\"selectionReason\":${quote(RuntimeStatus.get(this@LanServerService, "selection_reason", "Waiting"))},")

            append("\"visualProfile\":${quote(RuntimeStatus.get(this@LanServerService, "visual_last_decision", RuntimeStatus.get(this@LanServerService, "visual_last_profile", "Waiting")))},")
            append("\"visualPalette\":${quote(RuntimeStatus.get(this@LanServerService, "visual_last_palette", "Waiting"))},")
            append("\"visualDetail\":${quote(RuntimeStatus.get(this@LanServerService, "visual_last_detail", "Waiting"))},")
            append("\"visualPairing\":${quote(RuntimeStatus.get(this@LanServerService, "visual_pairing", "Waiting"))},")
            append("\"autonomousGrade\":${quote(RuntimeStatus.get(this@LanServerService, "autonomous_grade", "Waiting"))},")
            append("\"autonomousHealth\":${quote(RuntimeStatus.get(this@LanServerService, "autonomous_health", "Waiting"))},")
            append("\"selfHealLast\":${quote(RuntimeStatus.get(this@LanServerService, "self_heal_last", "Waiting"))},")
            append("\"watchdogState\":${quote(RuntimeStatus.get(this@LanServerService, "watchdog_state", "Waiting"))},")
            append("\"storagePressure\":${quote(RuntimeStatus.get(this@LanServerService, "storage_pressure", "Waiting"))},")
            append("\"adaptiveCacheTargetV3\":${quote(RuntimeStatus.get(this@LanServerService, "adaptive_cache_target_v3", "Waiting"))},")
            append("\"sourcePhotosTrust\":${quote(RuntimeStatus.get(this@LanServerService, "source_photos_trust", "Learning"))},")
            append("\"sourceDriveTrust\":${quote(RuntimeStatus.get(this@LanServerService, "source_drive_trust", "Learning"))},")
            append("\"sourceResilienceLast\":${quote(RuntimeStatus.get(this@LanServerService, "source_resilience_last", "Waiting"))},")
            append("\"wallpaperDna\":${quote(RuntimeStatus.get(this@LanServerService, "wallpaper_dna", "Waiting"))},")
            append("\"wallpaperFamily\":${quote(RuntimeStatus.get(this@LanServerService, "wallpaper_family", "Waiting"))},")
            append("\"wallpaperEntropy\":${quote(RuntimeStatus.get(this@LanServerService, "wallpaper_entropy", "Waiting"))},")
            append("\"familyFatigue\":${quote(RuntimeStatus.get(this@LanServerService, "family_fatigue", "Learning"))},")
            append("\"pairStory\":${quote(RuntimeStatus.get(this@LanServerService, "pair_story", "Waiting"))},")
            append("\"tasteConfidenceV2\":${quote(RuntimeStatus.get(this@LanServerService, "taste_confidence_v2", "Learning"))},")
            append("\"learningMode\":${quote(RuntimeStatus.get(this@LanServerService, "learning_mode", "Explore"))},")
            append("\"diversityBudget\":${quote(RuntimeStatus.get(this@LanServerService, "diversity_budget", "Waiting"))},")
            append("\"contextConfidence\":${quote(RuntimeStatus.get(this@LanServerService, "context_confidence", "Waiting"))},")
            append("\"contextConflict\":${quote(RuntimeStatus.get(this@LanServerService, "context_conflict", "Waiting"))},")
            append("\"contextLuxSmoothed\":${quote(RuntimeStatus.get(this@LanServerService, "context_lux_smoothed", "Waiting"))},")
            append("\"contextWeatherStable\":${quote(RuntimeStatus.get(this@LanServerService, "context_weather_stable", "Waiting"))},")
            append("\"decisionTraceId\":${quote(RuntimeStatus.get(this@LanServerService, "decision_trace_id", "-"))},")
            append("\"decisionConfidenceV2\":${quote(RuntimeStatus.get(this@LanServerService, "decision_confidence_v2", "Waiting"))},")
            append("\"decisionBreakdownV2\":${quote(RuntimeStatus.get(this@LanServerService, "decision_breakdown_v2", "Waiting"))},")
            append("\"decisionWhyV2\":${quote(RuntimeStatus.get(this@LanServerService, "decision_why_v2", "Waiting"))},")
            append("\"decisionWhyNotRunner\":${quote(RuntimeStatus.get(this@LanServerService, "decision_why_not_runner", "Waiting"))},")
            append("\"shadowRank\":${quote(RuntimeStatus.get(this@LanServerService, "shadow_rank", "Waiting"))},")
            append("\"qualityGuardLast\":${quote(RuntimeStatus.get(this@LanServerService, "quality_guard_last", "No rejection"))},")
            append("\"applyJournal\":${quote(RuntimeStatus.get(this@LanServerService, "apply_journal", "Idle"))},")
            append("\"crashRecovery\":${quote(RuntimeStatus.get(this@LanServerService, "crash_recovery", "None"))},")
            append("\"premiumHomeDna\":${quote(RuntimeStatus.get(this@LanServerService, "premium_home_dna", "Waiting"))},")
            append("\"premiumHomeFamily\":${quote(RuntimeStatus.get(this@LanServerService, "premium_home_family", "Waiting"))},")
            append("\"premiumHomePalette\":${quote(RuntimeStatus.get(this@LanServerService, "premium_home_palette", "Waiting"))},")
            append("\"premiumHomeVisual\":${quote(RuntimeStatus.get(this@LanServerService, "premium_home_visual", "Waiting"))},")
            append("\"premiumHomeRole\":${quote(RuntimeStatus.get(this@LanServerService, "premium_home_role", "Waiting"))},")
            append("\"premiumHomeQuality\":${quote(RuntimeStatus.get(this@LanServerService, "premium_home_quality", "Waiting"))},")
            append("\"premiumHomeAmoled\":${quote(RuntimeStatus.get(this@LanServerService, "premium_home_amoled", "Waiting"))},")
            append("\"premiumHomeReadability\":${quote(RuntimeStatus.get(this@LanServerService, "premium_home_readability", "Waiting"))},")
            append("\"premiumHomeCrop\":${quote(RuntimeStatus.get(this@LanServerService, "premium_home_crop", "Waiting"))},")
            append("\"premiumHomeEntropy\":${quote(RuntimeStatus.get(this@LanServerService, "premium_home_entropy", "Waiting"))},")
            append("\"premiumLockDna\":${quote(RuntimeStatus.get(this@LanServerService, "premium_lock_dna", "Waiting"))},")
            append("\"premiumLockFamily\":${quote(RuntimeStatus.get(this@LanServerService, "premium_lock_family", "Waiting"))},")
            append("\"premiumLockPalette\":${quote(RuntimeStatus.get(this@LanServerService, "premium_lock_palette", "Waiting"))},")
            append("\"premiumLockVisual\":${quote(RuntimeStatus.get(this@LanServerService, "premium_lock_visual", "Waiting"))},")
            append("\"premiumLockRole\":${quote(RuntimeStatus.get(this@LanServerService, "premium_lock_role", "Waiting"))},")
            append("\"premiumLockQuality\":${quote(RuntimeStatus.get(this@LanServerService, "premium_lock_quality", "Waiting"))},")
            append("\"premiumLockAmoled\":${quote(RuntimeStatus.get(this@LanServerService, "premium_lock_amoled", "Waiting"))},")
            append("\"premiumLockReadability\":${quote(RuntimeStatus.get(this@LanServerService, "premium_lock_readability", "Waiting"))},")
            append("\"premiumLockCrop\":${quote(RuntimeStatus.get(this@LanServerService, "premium_lock_crop", "Waiting"))},")
            append("\"premiumLockEntropy\":${quote(RuntimeStatus.get(this@LanServerService, "premium_lock_entropy", "Waiting"))},")
            append("\"premiumPairSummary\":${quote(RuntimeStatus.get(this@LanServerService, "premium_pair_summary", "Waiting"))},")
            append("\"premiumPairHarmony\":${quote(RuntimeStatus.get(this@LanServerService, "premium_pair_harmony", "Waiting"))},")
            append("\"premiumPairBrightness\":${quote(RuntimeStatus.get(this@LanServerService, "premium_pair_brightness", "Waiting"))},")
            append("\"premiumPairPalette\":${quote(RuntimeStatus.get(this@LanServerService, "premium_pair_palette", "Waiting"))},")
            append("\"premiumPairStyle\":${quote(RuntimeStatus.get(this@LanServerService, "premium_pair_style", "Waiting"))},")
            append("\"premiumCacheMap\":${quote(RuntimeStatus.get(this@LanServerService, "premium_cache_map", "Waiting"))},")
            append("\"premiumCacheSize\":${quote(RuntimeStatus.get(this@LanServerService, "premium_cache_size", "Waiting"))},")
            append("\"premiumCacheReadiness\":${quote(RuntimeStatus.get(this@LanServerService, "premium_cache_readiness", "Waiting"))},")
            append("\"premiumCacheHealth\":${quote(RuntimeStatus.get(this@LanServerService, "premium_cache_health", "Waiting"))},")
            append("\"premiumSourcePhotos\":${quote(RuntimeStatus.get(this@LanServerService, "premium_source_photos", "Learning"))},")
            append("\"premiumSourceDrive\":${quote(RuntimeStatus.get(this@LanServerService, "premium_source_drive", "Learning"))},")
            append("\"premiumWatchdogAge\":${quote(RuntimeStatus.get(this@LanServerService, "premium_watchdog_age", "Waiting"))},")
            append("\"premiumLastChangeAge\":${quote(RuntimeStatus.get(this@LanServerService, "premium_last_change_age", "Waiting"))},")
            append("\"premiumApplyTiming\":${quote(RuntimeStatus.get(this@LanServerService, "premium_apply_timing", "Waiting"))},")
            append("\"premiumDecision\":${quote(RuntimeStatus.get(this@LanServerService, "premium_decision", "Waiting"))},")
            append("\"premiumLearning\":${quote(RuntimeStatus.get(this@LanServerService, "premium_learning", "Waiting"))},")
            append("\"premiumContext\":${quote(RuntimeStatus.get(this@LanServerService, "premium_context", "Waiting"))},")
            append("\"premiumLibrary\":${quote(RuntimeStatus.get(this@LanServerService, "premium_library", "Waiting"))},")
            append("\"premiumRecovery\":${quote(RuntimeStatus.get(this@LanServerService, "premium_recovery", "Waiting"))},")
            append("\"premiumEventTimeline\":${quote(RuntimeStatus.get(this@LanServerService, "premium_event_timeline", "No events yet"))},")
            append("\"premiumExportPath\":${quote(RuntimeStatus.get(this@LanServerService, "premium_export_path", "-"))},")
            append("\"engineHealth\":${quote(RuntimeStatus.get(this@LanServerService, "engine_health", "Waiting"))},")
            append("\"resourcePolicy\":${quote(RuntimeStatus.get(this@LanServerService, "resource_policy", "Waiting"))},")
            append("\"blockedCachePurge\":${quote(RuntimeStatus.get(this@LanServerService, "blocked_cache_purge", "0"))},")

            append("\"moodProfileLabel\":${quote(RuntimeStatus.get(this@LanServerService, "mood_profile_label", "Waiting"))},")
            append("\"moodProfileSummary\":${quote(RuntimeStatus.get(this@LanServerService, "mood_profile_summary", "Waiting"))},")
            append("\"moodLastFactors\":${quote(RuntimeStatus.get(this@LanServerService, "mood_last_factors", "Waiting"))},")
            append("\"moodAutoReactStatus\":${quote(RuntimeStatus.get(this@LanServerService, "mood_auto_react", "Off"))},")
            append("\"adaptiveMoodContext\":${quote(RuntimeStatus.get(this@LanServerService, "adaptive_mood_context", "Waiting"))},")
            append("\"ambientLux\":${quote(RuntimeStatus.get(this@LanServerService, "adaptive_mood_lux", "Unavailable"))},")
            append("\"dayPhase\":${quote(RuntimeStatus.get(this@LanServerService, "adaptive_mood_phase", "Unknown"))},")
            append("\"thermalStatus\":${quote(RuntimeStatus.get(this@LanServerService, "adaptive_mood_thermal", "Waiting"))},")
            append("\"weatherStatus\":${quote(RuntimeStatus.get(this@LanServerService, "weather_mood_status", "Waiting"))},")
            append("\"locationStatus\":${quote(RuntimeStatus.get(this@LanServerService, "weather_location_status", "Waiting"))},")
            append("\"locationPermission\":${quote(bb.pix.wall.engine.MoodLocationEngine.permissionLabel(this@LanServerService))},")

            append("\"lastError\":${quote(RuntimeStatus.get(this@LanServerService, "last_error", ""))},")
            append("\"lastTrigger\":${quote(RuntimeStatus.get(this@LanServerService, "last_trigger", "None"))},")
            append("\"lanState\":${quote(RuntimeStatus.get(this@LanServerService, "lan_state", "Stopped"))},")
            append("\"nextRun\":${RuntimeStatus.getLong(this@LanServerService, "next_run", 0L)},")

            append("\"rootState\":${quote(RuntimeStatus.get(this@LanServerService, "root_state", "Not checked"))},")
            append("\"rootProvider\":${quote(RuntimeStatus.get(this@LanServerService, "root_provider", "Unknown"))},")
            append("\"rootCaps\":${quote(RuntimeStatus.get(this@LanServerService, "root_caps", "Not scanned"))},")

            append("\"historyCount\":${bb.pix.wall.engine.WallpaperLibrary.history().size},")
            append("\"previousAvailable\":${WallpaperController.previousAvailable()}")

            append("}")
        }
    }

    private fun wallInfoJson(
        file: File,
        home: Boolean,
        allowBlur: Boolean = true,
    ): String {
        val info =
            WallpaperController.displayWallpaperInfo(
                file.takeIf { it.exists() }
                    ?.absolutePath
            )

        val settings =
            SettingsStore(this).load()

        val blur =
            allowBlur &&
            WallpaperController
                .blurMasterEnabled(this) &&
                if (home) {
                    settings.homeBlurEnabled &&
                        settings.homeBlurRadius > 0
                } else {
                    settings.lockBlurEnabled &&
                        settings.lockBlurRadius > 0
                }

        val radius =
            if (home) {
                settings.homeBlurRadius
            } else {
                settings.lockBlurRadius
            }

        return buildString {
            append("{")
            append("\"exists\":${info.exists},")
            append("\"resolution\":${quote(info.resolution)},")
            append("\"format\":${quote(info.format)},")
            append("\"size\":${quote(info.sizeLabel)},")
            append("\"bytes\":${info.bytes},")
            append("\"source\":${quote(info.source)},")
            append("\"quality\":${quote(info.quality)},")
            append("\"blur\":$blur,")
            append("\"radius\":$radius")
            append("}")
        }
    }

    private fun historyJson(): String =
        bb.pix.wall.engine
            .WallpaperLibrary
            .history()
            .take(40)
            .joinToString(
                prefix = "[",
                postfix = "]",
            ) { entry ->
                buildString {
                    append("{")
                    append("\"id\":${quote(entry.id)},")
                    append("\"createdAt\":${entry.createdAt},")
                    append("\"home\":${entry.home != null && entry.home.exists()},")
                    append("\"lock\":${entry.lock != null && entry.lock.exists()}")
                    append("}")
                }
            }

    private fun librarySlot(
        slot: String,
    ): File? =
        when (slot) {
            "current-home" ->
                WallpaperFiles.currentHome

            "current-lock" ->
                WallpaperFiles.currentLock

            "next-home" ->
                WallpaperFiles.nextHome

            "next-lock" ->
                WallpaperFiles.nextLock

            else ->
                null
        }

    private fun libraryActionJson(
        form: Map<String, String>,
    ): String {
        val action =
            form["action"]
                .orEmpty()

        val file =
            librarySlot(
                form["slot"]
                    .orEmpty()
            )
                ?: return "{\"ok\":false,\"error\":\"bad slot\"}"

        if (!file.exists()) {
            return "{\"ok\":false,\"error\":\"unavailable\"}"
        }

        val ok =
            when (action) {
                "favorite" ->
                    bb.pix.wall.engine
                        .WallpaperLibrary
                        .favorite(file) != null

                "unfavorite" ->
                    bb.pix.wall.engine
                        .WallpaperLibrary
                        .removeFavorite(file)

                "pin" ->
                    bb.pix.wall.engine
                        .WallpaperLibrary
                        .setPinned(
                            file,
                            true,
                        )

                "unpin" ->
                    bb.pix.wall.engine
                        .WallpaperLibrary
                        .setPinned(
                            file,
                            false,
                        )

                "save-original" ->
                    bb.pix.wall.engine
                        .WallpaperLibrary
                        .saveOriginal(
                            file,
                            form["slot"]
                                .orEmpty(),
                        ) != null

                "never" -> {
                    val changed =
                        bb.pix.wall.engine
                            .WallpaperLibrary
                            .neverShowAgain(
                                file
                            )

                    if (changed) {
                        WallpaperController
                            .purgeBlockedCached(
                                this
                            )

                        WallpaperController
                            .ensureNext(
                                this,
                                SettingsStore(this)
                                    .load(),
                            )
                    }

                    changed
                }

                else ->
                    false
            }

        return "{\"ok\":$ok}"
    }

    private fun queueJson(): String {
        val allowed = setOf("jpg","jpeg","png","webp","avif")
        val files = listOf(WallpaperFiles.hotCache to "Hot", WallpaperFiles.warmCache to "Warm", WallpaperFiles.queue to "Queue", WallpaperFiles.legacyQueue to "LegacyQueue")
            .flatMap { (dir, source) -> dir.listFiles().orEmpty().filter { it.isFile && it.extension.lowercase() in allowed }.map { source to it } }
            .take(80)
        return files.joinToString(prefix="[", postfix="]") { (source, f) -> "{\"source\":${quote(source)},\"name\":${quote(f.name)},\"bytes\":${f.length()},\"modified\":${f.lastModified()}}" }
    }

    private fun parseForm(body: String): Map<String, String> = body.split('&').mapNotNull { pair ->
        val i = pair.indexOf('='); if (i < 0) null else decode(pair.substring(0, i)) to decode(pair.substring(i + 1))
    }.toMap()
    private fun decode(v: String) = URLDecoder.decode(v, "UTF-8")

    private fun sendFile(socket: Socket, file: File) {
        if (!file.exists()) { sendText(socket, 404, "text/plain", "Not found"); return }
        val bytes = runCatching { file.readBytes() }.getOrNull() ?: run { sendText(socket, 500, "text/plain", "Read failed"); return }
        val type = when {
            bytes.size >= 8 && bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() -> "image/png"
            bytes.size >= 12 && String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" && String(bytes, 8, 4, Charsets.US_ASCII) == "WEBP" -> "image/webp"
            else -> "image/jpeg"
        }
        sendBytes(socket, 200, type, bytes)
    }
    private fun sendJson(socket: Socket, code: Int, body: String) = sendText(socket, code, "application/json; charset=utf-8", body)
    private fun sendText(socket: Socket, code: Int, type: String, body: String) = sendBytes(socket, code, type, body.toByteArray(Charsets.UTF_8))
    private fun sendBytes(socket: Socket, code: Int, type: String, bytes: ByteArray) {
        val reason = when (code) { 200 -> "OK"; 403 -> "Forbidden"; 404 -> "Not Found"; 429 -> "Too Many Requests"; 500 -> "Error"; else -> "OK" }
        val header =
            "HTTP/1.1 $code $reason\r\n" +
                "Content-Type: $type\r\n" +
                "Content-Length: ${bytes.size}\r\n" +
                "Connection: close\r\n" +
                "Cache-Control: no-store\r\n" +
                "X-Content-Type-Options: nosniff\r\n" +
                "X-Frame-Options: DENY\r\n" +
                "Referrer-Policy: no-referrer\r\n" +
                "Content-Security-Policy: default-src 'self'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; img-src 'self' data:\r\n\r\n"
        try {
            val out = socket.getOutputStream()
            out.write(header.toByteArray(Charsets.UTF_8))
            out.write(bytes)
            out.flush()
        } catch (_: java.net.SocketException) {
            // Normal browser/network disconnect. Never crash the app process.
        } catch (_: java.io.IOException) {
            // Same treatment for ordinary client disconnects.
        }
    }

    private fun dashboard(
        token: String,
    ): String =
        WebRemoteDashboard.render(
            token
        )

    private fun options(items: List<Pair<String, String>>) = items.joinToString("") { "<option value='${it.first}'>${escapeHtml(it.second)}</option>" }
    private fun escapeHtml(v: String) = v.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("'", "&#39;")
    private fun dashboardToken(): String {
        val prefs = getSharedPreferences("bb_pixwall_runtime", Context.MODE_PRIVATE)
        prefs.getString("lan_token", null)?.takeIf { it.length >= 32 }?.let { return it }
        val bytes = ByteArray(24).also { SecureRandom().nextBytes(it) }
        val token = bytes.joinToString("") { "%02x".format(it) }
        prefs.edit().putString("lan_token", token).apply(); return token
    }
    private fun quote(v: String?) = if (v == null) "null" else "\"${v.replace("\\", "\\\\").replace("\"", "\\\"")}\""
    private fun jsString(v: String) = quote(v)
    private fun createChannel() { getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL, "Wi-Fi dashboard", NotificationManager.IMPORTANCE_LOW)) }
    companion object { const val CHANNEL = "bb_pixwall_remote" }
}
