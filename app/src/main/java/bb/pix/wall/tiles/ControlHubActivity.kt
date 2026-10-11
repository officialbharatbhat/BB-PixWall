package bb.pix.wall.tiles

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import bb.pix.wall.engine.EngineExecutors
import bb.pix.wall.engine.SafeWall
import bb.pix.wall.engine.WallpaperController
import bb.pix.wall.network.LanInfo
import bb.pix.wall.network.LanServerService
import bb.pix.wall.settings.SettingsStore
import bb.pix.wall.settings.AppearanceMode
import bb.pix.wall.ui.theme.BBPixWallTheme
import bb.pix.wall.ui.theme.ThemeProfile

/** Trial-only control UI. Does not run any polling or service when closed. */
class ControlHubActivity : ComponentActivity() {
    private var busy by mutableStateOf(false)
    private var status by mutableStateOf("Choose an action")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            BBPixWallTheme(profile = ThemeProfile.MATERIAL_PRO, appearanceMode = AppearanceMode.SYSTEM) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(20.dp),
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text("BB-PixWall Controls", style = MaterialTheme.typography.headlineSmall)
                        Spacer(Modifier.height(8.dp))
                        Text(status, style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(20.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(enabled = !busy, onClick = { execute("next") }, modifier = Modifier.weight(1f)) { Text("Next") }
                            OutlinedButton(enabled = !busy, onClick = { execute("previous") }, modifier = Modifier.weight(1f)) { Text("Previous") }
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedButton(enabled = !busy, onClick = { execute("safe") }, modifier = Modifier.weight(1f)) {
                                Text(if (SafeWall.active(this@ControlHubActivity)) "Safe OFF" else "Safe ON")
                            }
                            OutlinedButton(enabled = !busy, onClick = { execute("save") }, modifier = Modifier.weight(1f)) { Text("Save") }
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedButton(enabled = !busy, onClick = { execute("blur") }, modifier = Modifier.weight(1f)) { Text("Blur") }
                            OutlinedButton(enabled = !busy, onClick = { openRemote() }, modifier = Modifier.weight(1f)) { Text("Remote") }
                        }
                        Spacer(Modifier.height(16.dp))
                        OutlinedButton(onClick = { finish() }, modifier = Modifier.align(Alignment.End)) { Text("Close") }
                    }
                }
            }
        }
    }

    private fun execute(action: String) {
        if (busy) return
        if (SafeWall.active(this) && action != "safe" && action != "save") {
            status = "Safe Wall active; action blocked"
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
                    "safe" -> if (SafeWall.toggle(app)) (if (SafeWall.active(app)) "Safe Wall ON" else "Safe Wall OFF") else "Safe Wall unavailable; select both safe images"
                    "save" -> "Saved ${WallpaperController.saveCurrent(app).size} wallpaper(s)"
                    "blur" -> if (WallpaperController.toggleBlur(app)) "Blur enabled" else "Blur disabled"
                    else -> "Unknown action"
                }
            }.getOrElse { "Action failed: ${it.javaClass.simpleName}" }
            runOnUiThread {
                busy = false
                status = result
                Toast.makeText(app, result, Toast.LENGTH_SHORT).show()
                if (!result.contains("unavailable", ignoreCase = true) && !result.contains("failed", ignoreCase = true) && !result.contains("blocked", ignoreCase = true)) finish()
            }
        }
    }

    private fun openRemote() {
        val appSettings = SettingsStore(this).load()
        val settings = if (!appSettings.lanEnabled) appSettings.copy(lanEnabled = true).also { SettingsStore(this).save(it) } else appSettings
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
