package bb.pix.wall.tiles

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.content.res.Configuration
import bb.pix.wall.R
import androidx.compose.ui.res.painterResource
import bb.pix.wall.engine.WallpaperFiles
import bb.pix.wall.settings.AppearanceMode
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.BlurOn
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.ArrowForward
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Undo
import bb.pix.wall.engine.WallpaperLock
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.IconButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Surface
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector

import bb.pix.wall.engine.EngineExecutors
import bb.pix.wall.engine.SafeWall
import bb.pix.wall.engine.WallpaperController
import bb.pix.wall.network.LanInfo
import bb.pix.wall.network.LanServerService
import bb.pix.wall.settings.SettingsStore
import bb.pix.wall.ui.theme.BBPixWallTheme
import bb.pix.wall.ui.theme.ThemeProfile

/** On-demand dialog: no timer, observer service, or background state polling. */
class ControlHubActivity : ComponentActivity() {
    companion object { @Volatile var isVisible: Boolean = false; private set }
    private var appearanceMode by mutableStateOf(AppearanceMode.SYSTEM)
    private var previewItems by mutableStateOf<List<HubDiagnostics.Preview>>(emptyList())
    private var diagnosticsVersion = 0
    private var previewSignature = ""
    private var previewRunning = false
    private fun queueSignature(): String =
        listOf(WallpaperFiles.currentHome, WallpaperFiles.currentLock, WallpaperFiles.nextHome, WallpaperFiles.nextLock)
            .joinToString("|") { "${it.length()}:${it.lastModified()}" }
    private val previewWatcher = object : Runnable {
        override fun run() {
            if (!previewActive || isFinishing || isDestroyed) return
            val signature = queueSignature()
            if (signature != previewSignature && !previewRunning) {
                previewSignature = signature
                loadDiagnostics()
            }
            previewHandler.postDelayed(this, 2500L)
        }
    }
    private val previewHandler = Handler(Looper.getMainLooper())
    // Independent preview lane: cloud cache downloads must never delay Hub thumbnails.
    private val previewExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { task ->
        Thread(task, "bbpix-hub-preview").apply { priority = Thread.NORM_PRIORITY - 1 }
    }
    private var previewActive = false
    private var busy by mutableStateOf(false)
    private var status by mutableStateOf("Tap an action")
    private var hubState by mutableStateOf(HubState(false, false, false, false, false))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setFinishOnTouchOutside(false)
        window.setLayout(
            (resources.displayMetrics.widthPixels * 0.94f).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT,
        )
        refreshState()
        setContent {
            BBPixWallTheme(profile = ThemeProfile.MATERIAL_PRO, appearanceMode = appearanceMode) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp),
                    color = if (appearanceMode == AppearanceMode.PITCH_BLACK) Color.Black else MaterialTheme.colorScheme.surface,
                    tonalElevation = 0.dp,
                    shadowElevation = 0.dp,
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().heightIn(max = (resources.displayMetrics.heightPixels / resources.displayMetrics.density * 0.86f).dp).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_tile_hub),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.width(24.dp).height(24.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("BB-PixWall Hub", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                            IconButton(onClick = { finish() }) {
                                Icon(Icons.Outlined.Close, contentDescription = "Close")
                            }
                        }
                        if (status != "Tap an action") {
                            Text(status, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                        }
                        Row(horizontalArrangement = Arrangement.SpaceEvenly, modifier = Modifier.fillMaxWidth()) {
                            listOf("Home", "Lock", "Next H", "Next L").forEachIndexed { index, label ->
                                val bmp = previewItems.getOrNull(index)?.bitmap
                                Column(modifier = Modifier.width(43.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                    Box(
                                        modifier = Modifier.width(39.dp).aspectRatio(9f / 20f).clip(RoundedCornerShape(8.dp)),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        if (bmp != null && !bmp.isRecycled) {
                                            Image(bitmap = bmp.asImageBitmap(), contentDescription = label, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                                        } else {
                                            Text("—", style = MaterialTheme.typography.bodySmall)
                                        }
                                    }
                                    Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                                }
                            }
                        }

                        val snapshot = hubState
                        Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                            HubAction("Next Wallpaper", Icons.Outlined.ArrowForward, null, !busy && !snapshot.locked && !snapshot.safe, Modifier.weight(1f)) { execute("next") }
                            HubAction("Previous Wallpaper", Icons.Outlined.ArrowBack, null, !busy && snapshot.previous && !snapshot.locked && !snapshot.safe, Modifier.weight(1f)) { execute("previous") }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            HubAction("Undo Wallpaper", Icons.Outlined.Undo, null, !busy && snapshot.previous && !snapshot.locked && !snapshot.safe, Modifier.weight(1f)) { execute("undo") }
                            HubAction("Wallpaper Lock", Icons.Outlined.Lock, snapshot.locked, !busy && !snapshot.safe, Modifier.weight(1f)) { execute("lock") }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            HubAction("Safe Wall", Icons.Outlined.Security, snapshot.safe, !busy && (!snapshot.locked || snapshot.safe), Modifier.weight(1f)) { execute("safe") }
                            HubAction("Save Wallpaper", Icons.Outlined.BookmarkBorder, null, !busy, Modifier.weight(1f)) { execute("save") }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            HubAction("Wallpaper Blur", Icons.Outlined.BlurOn, snapshot.blur, !busy && !snapshot.locked && !snapshot.safe, Modifier.weight(1f)) { execute("blur") }
                            HubAction("BB-Remote", Icons.Outlined.Wifi, snapshot.remote, !busy, Modifier.weight(1f)) { openRemote() }
                        }
                        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("Web Remote service", modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Switch(checked = snapshot.remote, enabled = !busy, onCheckedChange = { setRemoteService(it) })
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // Reuse this one dialog rather than creating an overlapping window.
        refreshState()
        loadDiagnostics()
    }

    override fun onResume() {
        super.onResume()
        isVisible = true
        refreshState()
        loadDiagnostics()
        previewSignature = queueSignature()
        previewActive = true
        previewHandler.removeCallbacks(previewWatcher)
        previewHandler.postDelayed(previewWatcher, 2500L)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        refreshState()
    }

    override fun onPause() {
        isVisible = false
        previewActive = false
        previewHandler.removeCallbacks(previewWatcher)
        super.onPause()
    }

    private fun loadDiagnostics() {
        if (previewRunning || isFinishing || isDestroyed || previewExecutor.isShutdown) return
        previewRunning = true
        val request = ++diagnosticsVersion
        val app = applicationContext
        runCatching {
            previewExecutor.execute {
                val result = runCatching { HubDiagnostics.collect(app) }.getOrNull()
                runOnUiThread {
                    previewRunning = false
                    if (result != null && !isFinishing && !isDestroyed && diagnosticsVersion == request) {
                        previewItems = result
                    }
                }
            }
        }.onFailure { previewRunning = false }
    }

    override fun onDestroy() {
        previewActive = false
        previewHandler.removeCallbacksAndMessages(null)
        previewExecutor.shutdownNow()
        super.onDestroy()
    }

    private data class HubState(val safe: Boolean, val blur: Boolean, val remote: Boolean, val previous: Boolean, val locked: Boolean)

    private fun refreshState() {
        val settings = SettingsStore(this).load()
        appearanceMode = settings.appearanceMode
        hubState = HubState(
            safe = SafeWall.active(this),
            blur = WallpaperController.effectiveBlurEnabled(this),
            remote = settings.lanEnabled,
            previous = WallpaperController.previousAvailable(),
            locked = WallpaperLock.active(this),
        )
    }


    @Composable
    private fun HubAction(
        label: String,
        symbol: ImageVector,
        active: Boolean?,
        enabled: Boolean,
        modifier: Modifier,
        action: () -> Unit,
    ) {
        val highlighted = active == true
        val color = if (highlighted) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow
        val ink = if (highlighted) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
        Card(
            modifier = modifier,
            enabled = enabled,
            onClick = action,
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = color, contentColor = ink),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp, horizontal = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Icon(symbol, contentDescription = null, modifier = Modifier.height(19.dp))
                Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 2)
                Text(
                    when (active) {
                        true -> "ON"
                        false -> "OFF"
                        null -> "ACTION"
                    },
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }

    private fun execute(action: String) {
        if (busy) return
        if ((SafeWall.active(this) && action != "safe" && action != "save") ||
            (WallpaperLock.active(this) && action != "lock" && action != "save")) {
            status = "Wallpaper protection active; action blocked"
            return
        }
        busy = true
        status = "Working…"
        val app = applicationContext
        EngineExecutors.userActions {
            val result = runCatching {
                when (action) {
                    "next" -> if (WallpaperController.nextWall(app, allowNetwork = true, userInitiated = true)) "Wallpaper changed" else "No wallpaper available"
                    "previous" -> if (!WallpaperLock.active(app) && WallpaperController.previousWall(app)) "Previous restored" else "Previous unavailable or locked"
                    "undo" -> if (!WallpaperLock.active(app) && WallpaperController.previousWall(app)) "Wallpaper undone" else "Undo unavailable or locked"
                    "lock" -> if (WallpaperLock.toggle(app)) "Wallpaper locked" else "Wallpaper unlocked"
                    "safe" -> if (SafeWall.toggle(app)) (if (SafeWall.active(app)) "Safe Wall ON" else "Safe Wall OFF") else "Select Safe Home and Lock images first"
                    "save" -> "Saved ${WallpaperController.saveCurrent(app).size} wallpaper(s)"
                    "blur" -> if (WallpaperController.toggleBlur(app)) "Blur enabled" else "Blur disabled"
                    else -> "Unknown action"
                }
            }.getOrElse { "Action failed: ${it.javaClass.simpleName}" }
            runOnUiThread {
                busy = false
                status = result
                refreshState()
                loadDiagnostics()
                if (action == "next" || action == "previous" || action == "undo" || action == "blur") {
                    previewHandler.postDelayed({ if (!isFinishing && !isDestroyed) loadDiagnostics() }, 700L)
                    if (action == "blur") previewHandler.postDelayed({
                        if (!isFinishing && !isDestroyed) loadDiagnostics()
                    }, 2400L)

                }
                // Dialog remains foreground. Status is presented inline without a transient toast.
                // Keep state visible so the user can verify the change.
            }
        }
    }

    private fun setRemoteService(enabled: Boolean) {
        val store = SettingsStore(this)
        val current = store.load()
        if (current.lanEnabled == enabled) return
        store.save(current.copy(lanEnabled = enabled))
        val intent = Intent(this, LanServerService::class.java)
        runCatching {
            if (enabled) startForegroundService(intent) else stopService(intent)
        }.onFailure {
            status = "Remote service failed: ${it.javaClass.simpleName}"
        }.onSuccess {
            status = if (enabled) "Remote service ON" else "Remote service OFF"
        }
        refreshState()
    }

    private fun openRemote() {
        val settings = SettingsStore(this).load()
        if (!settings.lanEnabled) {
            status = "Enable Web Remote service first"
            return
        }
        val ip = LanInfo.localIpv4()
        if (ip == "Unavailable") {
            status = "Wi-Fi address unavailable"
            return
        }
        val url = Uri.parse("http://$ip:${settings.lanPort}")
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, url)) }
            .onSuccess { status = "BB-Remote opened" }
            .onFailure { status = "No browser available" }
    }
}
