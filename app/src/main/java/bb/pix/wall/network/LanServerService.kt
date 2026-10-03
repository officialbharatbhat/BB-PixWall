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
            // restart races and the "Web dashboard is not ready" loop.
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
            .setSmallIcon(R.drawable.ic_tile_web)
            .setContentTitle("BB-PixWall Web")
            .setContentText("Local dashboard service is active")
            .setOngoing(true).setSilent(true).build())
        watchdogFuture = EngineExecutors.scheduler.scheduleWithFixedDelay({
            val st = SettingsStore(this).load()
            if (st.lanEnabled && (!running.get() || server?.isClosed != false || !acceptAlive)) {
                restartServer(st.lanPort)
            }
        }, 4, 4, java.util.concurrent.TimeUnit.SECONDS)
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
                            runCatching { handle(socket) }
                                .onFailure { t ->
                                    if (t !is java.net.SocketException &&
                                        t !is java.io.IOException) {
                                        bb.pix.wall.engine.RuntimeStatus.failure(
                                            this,
                                            "LAN client: ${t.message ?: t.javaClass.simpleName}"
                                        )
                                    }
                                }
                        }
                    } else {
                        runCatching { socket.close() }
                    }
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
        when (path) {
            "/health" -> sendJson(s, 200, "{\"ok\":true,\"port\":$boundPort,\"ip\":${quote(LanInfo.localIpv4())},\"state\":${quote(bb.pix.wall.engine.RuntimeStatus.get(this, "lan_state"))}}")
            "/api/next" -> sendJson(s, if (mutationAllowed) 200 else 403, if (mutationAllowed) "{\"ok\":${WallpaperController.nextWall(this)}}" else "{\"error\":\"forbidden\"}")
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
            "/api/logs" -> sendText(s, 200, "text/plain; charset=utf-8", runCatching { WallpaperFiles.runtimeLog.readLines().takeLast(80).joinToString("\n") }.getOrDefault("No logs yet"))
            "/api/triggers" -> sendText(s, 200, "text/plain; charset=utf-8", runCatching { WallpaperFiles.triggerHistory.readLines().takeLast(80).joinToString("\n") }.getOrDefault("No trigger history yet"))
            "/api/queue" -> sendJson(s, 200, queueJson())
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
            "/img/current-home" -> sendFile(s, WallpaperFiles.currentHome)
            "/img/current-lock" -> sendFile(s, WallpaperFiles.currentLock)
            "/img/next-home" -> sendFile(s, WallpaperFiles.nextHome)
            "/img/next-lock" -> sendFile(s, WallpaperFiles.nextLock)
            "/", "/index.html" -> sendText(s, 200, "text/html; charset=utf-8", dashboard(token))
            else -> sendText(s, 404, "text/plain; charset=utf-8", "Not found")
        }
    }

    private fun updateSettings(form: Map<String, String>) {
        val store = SettingsStore(this)
        val old = store.load()
        fun bool(k: String, fallback: Boolean) = form[k]?.let { it == "true" || it == "1" || it == "on" } ?: fallback
        fun <T : Enum<T>> enumValue(raw: String?, values: Array<T>, fallback: T): T = values.firstOrNull { it.name == raw } ?: fallback
        val updated = old.copy(
            photosAlbumUrl = form["photosAlbumUrl"] ?: old.photosAlbumUrl,
            driveFolderUrl = form["driveFolderUrl"] ?: old.driveFolderUrl,
            autoChange = bool("autoChange", old.autoChange),
            triggerMode = enumValue(form["triggerMode"], TriggerMode.entries.toTypedArray(), old.triggerMode),
            intervalMinutes = form["intervalMinutes"]?.toIntOrNull()?.coerceIn(1, 240) ?: old.intervalMinutes,
            targetMode = enumValue(form["targetMode"], WallpaperTargetMode.entries.toTypedArray(), old.targetMode),
            wallpaperOrder = enumValue(form["wallpaperOrder"], WallpaperOrder.entries.toTypedArray(), old.wallpaperOrder),
            appearanceMode = enumValue(form["appearanceMode"], AppearanceMode.entries.toTypedArray(), old.appearanceMode),
            homeBlurEnabled = bool("homeBlurEnabled", old.homeBlurEnabled),
            lockBlurEnabled = bool("lockBlurEnabled", old.lockBlurEnabled),
            homeBlurRadius = form["homeBlurRadius"]?.toIntOrNull()?.coerceIn(0, 64) ?: old.homeBlurRadius,
            lockBlurRadius = form["lockBlurRadius"]?.toIntOrNull()?.coerceIn(0, 64) ?: old.lockBlurRadius,
            dataSaverEnabled = bool("dataSaverEnabled", old.dataSaverEnabled),
            wifiOnly = bool("wifiOnly", old.wifiOnly),
            mobileDataAllowed = bool("mobileDataAllowed", old.mobileDataAllowed),
            chargingOnly = bool("chargingOnly", old.chargingOnly),
            pauseBatterySaver = bool("pauseBatterySaver", old.pauseBatterySaver),
            pauseLowBattery = bool("pauseLowBattery", old.pauseLowBattery),
            lowBatteryThreshold = form["lowBatteryThreshold"]?.toIntOrNull()?.coerceIn(5,50) ?: old.lowBatteryThreshold,
            quietHoursEnabled = bool("quietHoursEnabled", old.quietHoursEnabled),
            quietStartHour = form["quietStartHour"]?.toIntOrNull()?.coerceIn(0,23) ?: old.quietStartHour,
            quietEndHour = form["quietEndHour"]?.toIntOrNull()?.coerceIn(0,23) ?: old.quietEndHour,
            cacheTarget = form["cacheTarget"]?.toIntOrNull()?.coerceIn(4,36) ?: old.cacheTarget,
            backgroundGuardEnabled = bool("backgroundGuardEnabled", old.backgroundGuardEnabled),
        )
        store.save(updated)
        val automationIntent = Intent(this, WallpaperAutomationService::class.java)
        if (updated.autoChange) startForegroundService(automationIntent) else stopService(automationIntent)
        val blurChanged = old.homeBlurEnabled != updated.homeBlurEnabled || old.lockBlurEnabled != updated.lockBlurEnabled || old.homeBlurRadius != updated.homeBlurRadius || old.lockBlurRadius != updated.lockBlurRadius
        if (blurChanged && WallpaperController.blurMasterEnabled(this)) Thread { WallpaperController.setBlurMasterAndReapply(this, true) }.start()
        form["theme"]?.let { raw ->
            ThemeProfile.entries.firstOrNull { it.name == raw }?.let { getSharedPreferences("bb_pixwall_ui", Context.MODE_PRIVATE).edit().putString("theme", it.name).apply() }
        }
        if (old.photosAlbumUrl != updated.photosAlbumUrl || old.driveFolderUrl != updated.driveFolderUrl || old.wallpaperOrder != updated.wallpaperOrder) WallpaperController.invalidateQueue()
        Thread { runCatching { WallpaperController.primeCache(this, updated); WallpaperController.ensureNext(this, updated) } }.start()
    }

    private fun settingsJson(): String {
        val s = SettingsStore(this).load(); val st = WallpaperController.state()
        val theme = getSharedPreferences("bb_pixwall_ui", Context.MODE_PRIVATE).getString("theme", ThemeProfile.SIGNATURE.name)
        return """{"engineMode":"${s.engineMode.name}","cache":${WallpaperController.cacheCount()},"offlineReady":${WallpaperController.offlineReadyCount()},"photosAlbumUrl":${quote(s.photosAlbumUrl)},"driveFolderUrl":${quote(s.driveFolderUrl)},"autoChange":${s.autoChange},"triggerMode":"${s.triggerMode.name}","intervalMinutes":${s.intervalMinutes},"targetMode":"${s.targetMode.name}","wallpaperOrder":"${s.wallpaperOrder.name}","homeBlurEnabled":${s.homeBlurEnabled},"homeBlurRadius":${s.homeBlurRadius},"lockBlurEnabled":${s.lockBlurEnabled},"lockBlurRadius":${s.lockBlurRadius},"appearanceMode":"${s.appearanceMode.name}","dataSaverEnabled":${s.dataSaverEnabled},"wifiOnly":${s.wifiOnly},"mobileDataAllowed":${s.mobileDataAllowed},"chargingOnly":${s.chargingOnly},"pauseBatterySaver":${s.pauseBatterySaver},"pauseLowBattery":${s.pauseLowBattery},"lowBatteryThreshold":${s.lowBatteryThreshold},"quietHoursEnabled":${s.quietHoursEnabled},"quietStartHour":${s.quietStartHour},"quietEndHour":${s.quietEndHour},"cacheTarget":${s.cacheTarget},"backgroundGuardEnabled":${s.backgroundGuardEnabled},"theme":${quote(theme)},"blurMaster":${WallpaperController.blurMasterEnabled(this)},"currentHome":${quote(st.currentHome)},"currentLock":${quote(st.currentLock)},"nextHome":${quote(st.nextHome)},"nextLock":${quote(st.nextLock)},"activeSource":${quote(bb.pix.wall.engine.RuntimeStatus.get(this,"active_source","None"))},"photosHealth":${quote(bb.pix.wall.engine.RuntimeStatus.get(this,"source_photos"))},"driveHealth":${quote(bb.pix.wall.engine.RuntimeStatus.get(this,"source_drive"))},"localHealth":${quote(bb.pix.wall.engine.RuntimeStatus.get(this,"source_local"))},"lastError":${quote(bb.pix.wall.engine.RuntimeStatus.get(this,"last_error",""))},"lastTrigger":${quote(bb.pix.wall.engine.RuntimeStatus.get(this,"last_trigger",""))},"lanState":${quote(bb.pix.wall.engine.RuntimeStatus.get(this,"lan_state","Stopped"))},"nextRun":${bb.pix.wall.engine.RuntimeStatus.getLong(this,"next_run",0L)}}"""
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
        val header = "HTTP/1.1 $code $reason\r\nContent-Type: $type\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\nCache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\nContent-Security-Policy: default-src 'self'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; img-src 'self' data:\r\n\r\n"

        try {
            val out = socket.getOutputStream()
            out.write(header.toByteArray(Charsets.UTF_8))
            out.write(bytes)
            out.flush()
        } catch (_: java.net.SocketException) {
            // Browser/network disappeared while response was being sent.
            // Normal client disconnect, never an application crash.
        } catch (_: java.io.IOException) {
            // Same treatment for ordinary network disconnects.
        }
    }

    private fun dashboard(token: String): String = """
<!doctype html><html><head><meta name='viewport' content='width=device-width,initial-scale=1'><title>BB-PixWall</title>
<style>
:root{color-scheme:dark}*{box-sizing:border-box}body{font-family:system-ui,-apple-system,sans-serif;background:#09090b;color:#f5f5f7;margin:0;padding:20px;max-width:920px;margin:auto}.head{display:flex;align-items:center;gap:12px}.badge{padding:6px 10px;border-radius:99px;background:#232329;font-size:13px}.grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:12px}.thumb{background:#151519;border:1px solid #2d2d34;border-radius:18px;overflow:hidden}.thumb img{width:100%;aspect-ratio:.85;object-fit:cover;background:#0d0d10}.thumb b{display:block;padding:9px 11px}.card{background:#18181c;border:1px solid #2d2d34;border-radius:22px;padding:18px;margin:14px 0}.row{display:flex;gap:10px;flex-wrap:wrap}.field{display:grid;gap:6px;margin:11px 0}input,select{width:100%;background:#101014;color:#f5f5f7;border:1px solid #414148;border-radius:12px;padding:12px;font:inherit}button{font:inherit;font-weight:700;padding:12px 15px;border:0;border-radius:13px;background:#e4473e;color:#fff;cursor:pointer}.card h2{color:#d2a928}.secondary{background:#292930;color:#f5f5f7}.muted{color:#aaaab5}.two{display:grid;grid-template-columns:1fr 1fr;gap:12px}@media(max-width:620px){.two{grid-template-columns:1fr}.grid{gap:8px}}
</style></head><body>
<div class='head'><h1 style='margin-right:auto'>BB-PixWall</h1><span class='badge' id='mode'>Standard</span></div><p class='muted'>Full local Wi-Fi control panel</p><div class='card'><h2>Live status</h2><div class='two'><div>Active source: <b id='activeSource'>-</b></div><div>Cache: <b id='cache'>0</b></div><div>Photos: <b id='photosHealth'>-</b></div><div>Drive: <b id='driveHealth'>-</b></div><div>Local: <b id='localHealth'>-</b></div><div>Next run: <b id='nextRun'>-</b></div></div><p class='muted' id='lastError'></p></div>
<div class='grid'><div class='thumb'><img src='/img/current-home?t=1'><b>Current Home</b></div><div class='thumb'><img src='/img/current-lock?t=1'><b>Current Lock</b></div><div class='thumb'><img src='/img/next-home?t=1'><b>Next Home</b></div><div class='thumb'><img src='/img/next-lock?t=1'><b>Next Lock</b></div></div>
<div class='card'><div class='row'><button onclick="act('/api/next')">Next Wall</button><button onclick="act('/api/save')">Save Wall</button><button onclick="act('/api/blur')">Blur Wall</button><button class='secondary' onclick="act('/api/prepare')">Prepare / Cache</button><button class='secondary' onclick="act('/api/test-sources')">Test sources</button><button class='secondary' onclick="act('/api/cache-clear')">Clear Cache</button></div><p id='status' class='muted'></p></div>
<div class='card'><h2>Sources & order</h2><div class='field'><label>Google Photos public album</label><input id='photosAlbumUrl'></div><div class='field'><label>Google Drive public mirror</label><input id='driveFolderUrl'></div><div class='field'><label>Wallpaper order</label><select id='wallpaperOrder'>${options(WallpaperOrder.entries.map { it.name to it.label })}</select></div></div>
<div class='card'><h2>Automation</h2><div class='two'><div class='field'><label>Automatic change</label><select id='autoChange'><option value='true'>On</option><option value='false'>Off</option></select></div><div class='field'><label>Trigger</label><select id='triggerMode'>${options(TriggerMode.entries.map { it.name to it.label })}</select></div><div class='field'><label>Interval minutes</label><input id='intervalMinutes' type='number' min='1' max='240'></div><div class='field'><label>Target</label><select id='targetMode'>${options(WallpaperTargetMode.entries.map { it.name to it.label })}</select></div></div></div>
<div class='card'><h2>Network, cache & reliability</h2><div class='two'><div class='field'><label>Data Saver</label><select id='dataSaverEnabled'><option value='true'>On</option><option value='false'>Off</option></select></div><div class='field'><label>Wi-Fi only</label><select id='wifiOnly'><option value='true'>On</option><option value='false'>Off</option></select></div><div class='field'><label>Charging only</label><select id='chargingOnly'><option value='true'>On</option><option value='false'>Off</option></select></div><div class='field'><label>Pause in Battery Saver</label><select id='pauseBatterySaver'><option value='true'>On</option><option value='false'>Off</option></select></div><div class='field'><label>Prefetch cache (4–36)</label><input id='cacheTarget' type='number' min='4' max='36'></div><div class='field'><label>Pause below 15% battery</label><select id='pauseLowBattery'><option value='true'>On</option><option value='false'>Off</option></select></div><div class='field'><label>Quiet hours</label><select id='quietHoursEnabled'><option value='true'>On</option><option value='false'>Off</option></select></div><div class='field'><label>Quiet start hour</label><input id='quietStartHour' type='number' min='0' max='23'></div><div class='field'><label>Quiet end hour</label><input id='quietEndHour' type='number' min='0' max='23'></div><div class='field'><label>Background guard</label><select id='backgroundGuardEnabled'><option value='true'>On</option><option value='false'>Off</option></select></div></div></div>
<div class='card'><h2>Blur</h2><div class='two'><div><div class='field'><label>Home blur</label><select id='homeBlurEnabled'><option value='true'>On</option><option value='false'>Off</option></select></div><div class='field'><label>Home intensity 0–64</label><input id='homeBlurRadius' type='range' min='0' max='64'></div></div><div><div class='field'><label>Lock blur</label><select id='lockBlurEnabled'><option value='true'>On</option><option value='false'>Off</option></select></div><div class='field'><label>Lock intensity 0–64</label><input id='lockBlurRadius' type='range' min='0' max='64'></div></div></div></div>
<div class='card'><h2>Appearance</h2><div class='two'><div class='field'><label>Color mode</label><select id='appearanceMode'>${options(AppearanceMode.entries.map { it.name to it.label })}</select></div><div class='field'><label>Theme</label><select id='theme'>${options(ThemeProfile.entries.map { it.name to it.title })}</select></div></div></div>
<div class='card'><h2>Engine</h2><p class='muted'>Advance requests root on the phone, raises BB-PixWall process priority and expands the prefetch cache. Wallpaper application still uses Android WallpaperManager for reliability.</p><div class='row'><button onclick="act('/api/advanced')">Request Advance / su</button><button class='secondary' onclick="act('/api/standard')">Use Standard</button></div><p class='muted'>Advance mode uses a larger persistent cache.</p></div>
<div class='card'><h2>Diagnostics</h2><p class='muted'>Live runtime log</p><pre id='logs' style='white-space:pre-wrap;max-height:260px;overflow:auto;background:#0b0b0d;padding:12px;border-radius:12px'></pre><button onclick='loadLogs()' class='secondary'>Refresh logs</button> <button onclick="act('/api/debug-report')" class='secondary'>Export debug</button></div><div class='card'><button onclick='saveSettings()'>Save all settings</button></div>
<script>
const token=${jsString(token)}; const ids=['photosAlbumUrl','driveFolderUrl','autoChange','triggerMode','intervalMinutes','targetMode','wallpaperOrder','homeBlurEnabled','homeBlurRadius','lockBlurEnabled','lockBlurRadius','appearanceMode','dataSaverEnabled','wifiOnly','chargingOnly','pauseBatterySaver','pauseLowBattery','quietHoursEnabled','quietStartHour','quietEndHour','cacheTarget','backgroundGuardEnabled','theme'];
async function act(p){let r=await fetch(p,{method:'POST',headers:{'X-BBPixWall-Token':token}});document.getElementById('status').textContent=await r.text();setTimeout(load,300)}
async function load(){let s=await (await fetch('/api/settings',{cache:'no-store'})).json();ids.forEach(id=>{let e=document.getElementById(id);if(!e||s[id]===undefined)return;if(e.type==='checkbox')e.checked=Boolean(s[id]);else e.value=String(s[id])});document.getElementById('mode').textContent=s.engineMode==='ADVANCED'?'Advance':'Standard';document.getElementById('cache').textContent=s.cache;document.getElementById('activeSource').textContent=s.activeSource||'None';document.getElementById('photosHealth').textContent=s.photosHealth||'-';document.getElementById('driveHealth').textContent=s.driveHealth||'-';document.getElementById('localHealth').textContent=s.localHealth||'-';document.getElementById('nextRun').textContent=s.nextRun?new Date(s.nextRun).toLocaleTimeString():'Event/manual';document.getElementById('lastError').textContent=s.lastError?'Last error: '+s.lastError:'';document.querySelectorAll('.thumb img').forEach(i=>i.src=i.src.split('?')[0]+'?t='+Date.now())}
async function loadLogs(){document.getElementById('logs').textContent=await (await fetch('/api/logs',{cache:'no-store'})).text()}
async function saveSettings(){let p=new URLSearchParams();ids.forEach(id=>{let e=document.getElementById(id);if(!e)return;p.set(id,e.type==='checkbox'?(e.checked?'true':'false'):e.value)});let r=await fetch('/api/settings',{method:'POST',headers:{'X-BBPixWall-Token':token,'Content-Type':'application/x-www-form-urlencoded'},body:p});document.getElementById('status').textContent=r.ok?'Saved':'Save failed';await load()}
load();loadLogs();setInterval(load,3000)
</script></body></html>
""".trimIndent()

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
    companion object { const val CHANNEL = "bb_pixwall_web" }
}
