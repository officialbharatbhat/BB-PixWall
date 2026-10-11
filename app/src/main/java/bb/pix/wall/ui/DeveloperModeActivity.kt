package bb.pix.wall.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import bb.pix.wall.settings.DeveloperFont
import bb.pix.wall.settings.SettingsStore
import bb.pix.wall.ui.theme.BBPixWallTheme
import bb.pix.wall.ui.theme.ThemeProfile
import kotlin.math.roundToInt

class DeveloperModeActivity : ComponentActivity() {
    private var update by mutableIntStateOf(0)
    private var progress by mutableFloatStateOf(1f)
    private var selected by mutableStateOf("App default")
    private val chooseFont = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching { DeveloperFont.install(this, uri) }
                .onSuccess { selected = it; update++; Toast.makeText(this, "Font applied", Toast.LENGTH_SHORT).show() }
                .onFailure { Toast.makeText(this, it.message ?: "Invalid font", Toast.LENGTH_LONG).show() }
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        progress = DeveloperFont.size(this)
        selected = DeveloperFont.name(this)
        setContent {
            val ignored = update
            val appearance = SettingsStore(this).load().appearanceMode
            BBPixWallTheme(profile = ThemeProfile.MATERIAL_PRO, appearanceMode = appearance) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                        TextButton(onClick = { finish() }) { Text("← Back to BB-PixWall Lite") }
                        Text("Developer Mode", style = MaterialTheme.typography.headlineSmall)
                        Text("Developer features can be added here in future updates.", style = MaterialTheme.typography.bodyMedium)
                        HorizontalDivider()
                        Text("Custom Font", style = MaterialTheme.typography.titleLarge)
                        Text("Selected: $selected")
                        Text("BB-PixWall Lite • Hub • Web Remote", style = MaterialTheme.typography.bodySmall)
                        Button(onClick = { chooseFont.launch(arrayOf("font/ttf", "font/otf", "application/x-font-ttf", "application/x-font-otf", "application/octet-stream", "*/*")) }) { Text("Select Font (.ttf / .otf)") }
                        OutlinedButton(onClick = {
                            DeveloperFont.reset(this@DeveloperModeActivity)
                            progress = 1f
                            selected = "App default"
                            update++
                        }) { Text("Reset Font") }
                        Text("Font Size • ${(progress * 100).roundToInt()}%")
                        Slider(value = progress, onValueChange = {
                            progress = it
                            DeveloperFont.updateSize(this@DeveloperModeActivity, it)
                            update++
                        }, valueRange = .80f..1.30f)
                        Text("Preview: BB-PixWall Lite — Automatic Wallpaper Changer", style = MaterialTheme.typography.bodyMedium)
                        Text("The main app and Hub use the selected font when reopened. Remote updates after browser refresh.", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}
