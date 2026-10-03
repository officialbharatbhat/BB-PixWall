package bb.pix.wall.tiles

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import bb.pix.wall.engine.EngineExecutors
import bb.pix.wall.engine.RuntimeStatus
import bb.pix.wall.network.LanInfo
import bb.pix.wall.network.LanServerService
import bb.pix.wall.settings.SettingsStore
import java.net.InetSocketAddress
import java.net.Socket

class WebTileService : BaseTileService() {
    override fun onStartListening() {
        super.onStartListening()
        val s = SettingsStore(this).load()
        val actual = RuntimeStatus.getLong(this, "lan_port_actual", s.lanPort.toLong()).toInt()
        val state = RuntimeStatus.get(this, "lan_state", "Stopped")
        setState(s.lanEnabled && state.startsWith("Running"), if (s.lanEnabled) ":$actual" else "LAN off")
    }

    override fun onClick() {
        super.onClick()
        val settings = SettingsStore(this).load()
        if (!settings.lanEnabled) { toast("Enable LAN access in BB-PixWall"); return }
        setState(false, "Starting…")
        runCatching { startForegroundService(Intent(this, LanServerService::class.java)) }
            .onFailure { toast("Could not start BB-PixWall Web"); return }

        EngineExecutors.io {
            val port = findHealthyPort(settings.lanPort)
            Handler(Looper.getMainLooper()).post {
                if (port == null) {
                    setState(false, "Not ready")
                    toast("Web dashboard is not ready")
                    return@post
                }
                RuntimeStatus.setLong(this, "lan_port_actual", port.toLong())
                setState(true, ":$port")
                // On the same phone, loopback is far more reliable than racing a Wi‑Fi IP change.
                val uri = Uri.parse("http://127.0.0.1:$port")
                val browserIntent = Intent(Intent.ACTION_VIEW, uri).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                val pending = PendingIntent.getActivity(this, 4090, browserIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                runCatching { startActivityAndCollapse(pending) }
                    .onFailure { toast("Browser unavailable: ${it.javaClass.simpleName}") }
            }
        }
    }

    private fun findHealthyPort(base: Int): Int? {
        repeat(60) { attempt ->
            val runtime = RuntimeStatus.getLong(this, "lan_port_actual", base.toLong()).toInt()
            val candidates = linkedSetOf(runtime).apply { for (p in base..(base + 10).coerceAtMost(65535)) add(p) }
            for (port in candidates) if (healthy(port)) return port
            Thread.sleep(if (attempt < 12) 120L else 220L)
        }
        return null
    }

    private fun healthy(port: Int): Boolean = runCatching {
        Socket().use { socket ->
            socket.connect(InetSocketAddress("127.0.0.1", port), 450)
            socket.soTimeout = 650
            val out = socket.getOutputStream()
            out.write("GET /health HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n".toByteArray())
            out.flush()
            val first = socket.getInputStream().bufferedReader().readLine().orEmpty()
            first.contains(" 200 ")
        }
    }.getOrDefault(false)

}
