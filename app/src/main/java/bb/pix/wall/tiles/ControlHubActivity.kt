package bb.pix.wall.tiles

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.Button
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
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
    private var busy by mutableStateOf(false)
    private var status by mutableStateOf("Tap an action")
    private var hubState by mutableStateOf(HubState(false, false, false, false))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setLayout(
            (resources.displayMetrics.widthPixels * 0.92f).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT,
        )
        refreshState()
        setContent {
            BBPixWallTheme(profile = ThemeProfile.MATERIAL_PRO, appearanceMode = SettingsStore(this@ControlHubActivity).load().appearanceMode) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp),
                    tonalElevation = 6.dp,
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("BB-PixWall Hub", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                            OutlinedButton(onClick = { finish() }, contentPadding = androidx.compose.foundation.layout.PaddingValues(8.dp)) {
                                Icon(Icons.Outlined.Close, contentDescription = "Close")
                            }
                        }
                        Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

                        val snapshot = hubState
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            HubAction("Next Wallpaper", Icons.Outlined.ArrowForward, null, !busy, Modifier.weight(1f)) { execute("next") }
                            HubAction("Previous Wallpaper", Icons.Outlined.ArrowBack, null, !busy && snapshot.previous, Modifier.weight(1f)) { execute("previous") }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            HubAction("Safe Wall", Icons.Outlined.Security, snapshot.safe, !busy, Modifier.weight(1f)) { execute("safe") }
                            HubAction("Save Wallpaper", Icons.Outlined.BookmarkBorder, null, !busy, Modifier.weight(1f)) { execute("save") }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            HubAction("Wallpaper Blur", Icons.Outlined.BlurOn, snapshot.blur, !busy, Modifier.weight(1f)) { execute("blur") }
                            HubAction("BB-Remote", Icons.Outlined.Wifi, snapshot.remote, !busy, Modifier.weight(1f)) { openRemote() }
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshState()
    }

    private data class HubState(val safe: Boolean, val blur: Boolean, val remote: Boolean, val previous: Boolean)

    private fun refreshState() {
        val settings = SettingsStore(this).load()
        hubState = HubState(
            safe = SafeWall.active(this),
            blur = WallpaperController.effectiveBlurEnabled(this),
            remote = settings.lanEnabled,
            previous = WallpaperController.previousAvailable(),
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
        val color = if (highlighted) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
        val ink = if (highlighted) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
        Card(
            modifier = modifier,
            enabled = enabled,
            onClick = action,
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = color, contentColor = ink),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp, horizontal = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(symbol, contentDescription = null)
                Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 2)
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
        if (SafeWall.active(this) && action != "safe" && action != "save") {
            status = "Safe Wall active; this action is blocked"
            return
        }
        busy = true
        status = "Working…"
        val app = applicationContext
        EngineExecutors.serial {
            val result = runCatching {
                when (action) {
                    "next" -> if (WallpaperController.nextWall(app, allowNetwork = true, userInitiated = true)) "Wallpaper changed" else "No wallpaper available"
                    "previous" -> if (WallpaperController.previousWall(app)) "Previous restored" else "Previous unavailable"
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
                Toast.makeText(app, result, Toast.LENGTH_SHORT).show()
                // Keep state visible so the user can verify the change.
            }
        }
    }

    private fun openRemote() {
        val old = SettingsStore(this).load()
        val settings = if (!old.lanEnabled) old.copy(lanEnabled = true).also { SettingsStore(this).save(it) } else old
        refreshState()
        runCatching { startForegroundService(Intent(this, LanServerService::class.java)) }
        val ip = LanInfo.localIpv4()
        if (ip == "Unavailable") {
            status = "Wi-Fi address unavailable"
            return
        }
        val url = Uri.parse("http://$ip:${settings.lanPort}")
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, url)) }
            .onSuccess { finish() }
            .onFailure { status = "No browser available" }
    }
}
