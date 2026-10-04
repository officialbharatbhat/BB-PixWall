package bb.pix.wall.ui

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.Settings
import android.os.Environment
import android.os.Build
import android.os.PowerManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import bb.pix.wall.R
import bb.pix.wall.model.WallpaperState
import bb.pix.wall.engine.RuntimeStatus
import bb.pix.wall.engine.WallpaperController
import bb.pix.wall.network.LanInfo
import bb.pix.wall.settings.*
import bb.pix.wall.ui.theme.LocalDesignTokens
import bb.pix.wall.ui.theme.ThemeProfile
import kotlin.math.roundToInt

@Composable
fun HomeScreen(
    selectedTheme: ThemeProfile,
    onThemeSelected: (ThemeProfile) -> Unit,
    settings: AppSettings,
    wallpaperState: WallpaperState,
    onSettingsChange: (AppSettings) -> Unit,
    onRefreshPreviews: () -> Unit,
    onPrepareNext: () -> Unit,
    onRequestAdvanced: () -> Unit,
) {
    val context = LocalContext.current
    val tokens = LocalDesignTokens.current
    val spacing = (16 * tokens.spacingScale).dp
    val localIp = remember { LanInfo.localIpv4() }
    val storageGranted = Environment.isExternalStorageManager()
    var portText by remember(settings.lanPort) { mutableStateOf(settings.lanPort.toString()) }

    Box(Modifier.fillMaxSize().background(themeBackdrop(selectedTheme))) {
        Scaffold(containerColor = Color.Transparent, contentColor = MaterialTheme.colorScheme.onBackground) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())
                .padding(horizontal = (20 * tokens.spacingScale).dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(spacing),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("BB-PixWall", style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.onBackground, modifier = Modifier.weight(1f))
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = if (settings.engineMode == EngineMode.ADVANCED) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Row(Modifier.padding(horizontal = 11.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(7.dp).clip(RoundedCornerShape(99.dp)).background(if (settings.engineMode == EngineMode.ADVANCED) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant))
                        Spacer(Modifier.width(7.dp))
                        Text(settings.engineMode.label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            Text("Reliable wallpaper rotation with mirrored cloud sources and local fallback.", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)

            PreviewGrid(wallpaperState, onRefreshPreviews, onPrepareNext)

            SectionTitle("Sources")
            SourceConfigCard(
                title = "Google Photos",
                subtitle = "Primary public shared-album source",
                value = settings.photosAlbumUrl,
                hint = "https://photos.app.goo.gl/...",
                icon = Icons.Outlined.PhotoLibrary,
                onValue = { onSettingsChange(settings.copy(photosAlbumUrl = it.trim())) },
            )
            SourceConfigCard(
                title = "Google Drive",
                subtitle = "Public mirror folder / automatic fallback",
                value = settings.driveFolderUrl,
                hint = "Public Drive folder link",
                icon = Icons.Outlined.Cloud,
                onValue = { onSettingsChange(settings.copy(driveFolderUrl = it.trim())) },
            )
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Folder, null)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Local repository", fontWeight = FontWeight.SemiBold)
                        Text("/sdcard/wallpaper", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = {
                        runCatching {
                            context.startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}")))
                        }
                    }) { Text(if (storageGranted) "Granted" else "Grant access") }
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    SettingHeader(Icons.Outlined.HealthAndSafety, "Source health")
                    Text("Photos: ${RuntimeStatus.get(context, "source_photos")} • ${RuntimeStatus.get(context, "photos_last_count", "0")} items • Δ ${RuntimeStatus.get(context, "photos_delta", "-")}")
                    Text("Drive: ${RuntimeStatus.get(context, "source_drive")} • ${RuntimeStatus.get(context, "drive_last_count", "0")} items • Δ ${RuntimeStatus.get(context, "drive_delta", "-")}")
                    Text("Local: ${RuntimeStatus.get(context, "source_local")}")
                    Text("Active: ${RuntimeStatus.get(context, "active_source", "None yet")}", color = MaterialTheme.colorScheme.primary)
                }
            }

            SectionTitle("Wallpaper order")
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    SettingHeader(Icons.Outlined.Sort, "Choose how the next wallpaper is picked")
                    WallpaperOrder.entries.forEach { mode ->
                        RadioSetting(settings.wallpaperOrder == mode, mode.label, when (mode) {
                            WallpaperOrder.A_Z -> "Alphabetical A to Z. Exact for local files; best-effort for cloud items."
                            WallpaperOrder.Z_A -> "Alphabetical Z to A."
                            WallpaperOrder.DATE_NEWEST -> "Newest first. Uses real file dates for local wallpapers."
                            WallpaperOrder.DATE_OLDEST -> "Oldest first. Uses real file dates for local wallpapers."
                            WallpaperOrder.SIZE_LOW_HIGH -> "Smaller files first."
                            WallpaperOrder.SIZE_HIGH_LOW -> "Larger files first."
                            WallpaperOrder.SURPRISE -> "Stable daily surprise order without repeating recent walls."
                            WallpaperOrder.RANDOM_SHUFFLE -> "Fresh shuffled order whenever the queue is refilled."
                        }) { onSettingsChange(settings.copy(wallpaperOrder = mode)) }
                    }
                }
            }

            SectionTitle("Engine mode")
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SettingHeader(Icons.Outlined.Speed, "Standard / Advance")
                    RadioSetting(settings.engineMode == EngineMode.STANDARD, "Standard", "No root. Android APIs + persistent foreground automation and configurable prefetch cache.") {
                        onSettingsChange(settings.copy(engineMode = EngineMode.STANDARD))
                    }
                    RadioSetting(settings.engineMode == EngineMode.ADVANCED, "Advance", "Requests su and applies app-specific background/priority tuning. Wallpaper commits still use Android WallpaperManager for Android 15–17+ compatibility.") {
                        if (settings.engineMode != EngineMode.ADVANCED) onRequestAdvanced()
                    }
                    Text("Cache ready: ${bb.pix.wall.engine.WallpaperController.cacheCount()} wallpaper(s)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Last trigger: ${RuntimeStatus.get(context, "last_trigger", "None yet")}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            SectionTitle("Network & cache")
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    SettingSwitch("Data Saver", "When ON, cloud sources request reduced-resolution images where supported. Local wallpapers stay untouched.", settings.dataSaverEnabled) { onSettingsChange(settings.copy(dataSaverEnabled = it)) }
                    SettingSwitch("Wi-Fi only", "Use Wi-Fi for cloud prefetch. Already-cached wallpapers can still rotate offline.", settings.wifiOnly) { onSettingsChange(settings.copy(wifiOnly = it)) }
                    SettingSwitch("Allow mobile data", "Allow cloud prefetch on mobile data when Wi-Fi-only is OFF.", settings.mobileDataAllowed) { onSettingsChange(settings.copy(mobileDataAllowed = it)) }
                    SettingSwitch("Lean local storage", "Keep only the configured prefetch amount even in Advance mode. Google Photos stays primary and Drive remains the mirror/fallback.", settings.leanStorageMode) { onSettingsChange(settings.copy(leanStorageMode = it)) }
                    SettingSwitch("Charging only", "Run automatic wallpaper changes only while charging.", settings.chargingOnly) { onSettingsChange(settings.copy(chargingOnly = it)) }
                    SettingSwitch("Pause in Battery Saver", "Pause automatic changes while Android Battery Saver is active.", settings.pauseBatterySaver) { onSettingsChange(settings.copy(pauseBatterySaver = it)) }
                    Text("Prefetch cache: ${settings.cacheTarget} wallpapers", fontWeight = FontWeight.SemiBold)
                    Slider(value = settings.cacheTarget.toFloat(), onValueChange = { onSettingsChange(settings.copy(cacheTarget = it.roundToInt().coerceIn(4, 36))) }, valueRange = 4f..36f, steps = 31)
                    Text(if (settings.leanStorageMode) "Lean mode: cache stays at this exact target to avoid wasting phone storage." else "Expanded mode: Advance may keep at least 16 ready wallpapers.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            SectionTitle("Background reliability")
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SettingSwitch("Background guard", "Keep the automation foreground service sticky; Advance mode also applies root-only app-specific background tuning.", settings.backgroundGuardEnabled) { onSettingsChange(settings.copy(backgroundGuardEnabled = it)) }
                    SettingSwitch("Pause on low battery", "Avoid automatic changes below the configured threshold.", settings.pauseLowBattery) { onSettingsChange(settings.copy(pauseLowBattery = it)) }
                    if (settings.pauseLowBattery) {
                        Text("Low-battery threshold: ${settings.lowBatteryThreshold}%", fontWeight = FontWeight.SemiBold)
                        Slider(value = settings.lowBatteryThreshold.toFloat(), onValueChange = { onSettingsChange(settings.copy(lowBatteryThreshold = it.roundToInt().coerceIn(5,50))) }, valueRange = 5f..50f, steps = 44)
                    }
                    SettingSwitch("Quiet hours", "Pause automatic changes during your sleep/quiet window.", settings.quietHoursEnabled) { onSettingsChange(settings.copy(quietHoursEnabled = it)) }
                    if (settings.quietHoursEnabled) {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            OutlinedTextField(value = settings.quietStartHour.toString(), onValueChange = { it.toIntOrNull()?.let { h -> onSettingsChange(settings.copy(quietStartHour = h.coerceIn(0,23))) } }, label = { Text("Start hour") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f), singleLine = true)
                            OutlinedTextField(value = settings.quietEndHour.toString(), onValueChange = { it.toIntOrNull()?.let { h -> onSettingsChange(settings.copy(quietEndHour = h.coerceIn(0,23))) } }, label = { Text("End hour") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f), singleLine = true)
                        }
                    }
                    TextButton(onClick = {
                        runCatching { context.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))) }
                            .recoverCatching { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
                    }) { Text("Allow unrestricted battery use") }
                    Text("Accessibility and display-overlay permissions are intentionally not requested because wallpaper rotation does not need them.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            SectionTitle("Wallpaper automation")
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    SettingSwitch("Automatic wallpaper change", "Run the selected trigger automatically.", settings.autoChange) { onSettingsChange(settings.copy(autoChange = it)) }
                    HorizontalDivider()
                    SettingHeader(Icons.Outlined.Schedule, "Trigger")
                    TriggerMode.entries.forEach { mode ->
                        RadioSetting(settings.triggerMode == mode, mode.label, when (mode) {
                            TriggerMode.INTERVAL -> "Change wallpaper on a fixed timer."
                            TriggerMode.SCREEN_OFF -> "Change when the display turns off."
                            TriggerMode.SCREEN_ON -> "Change when the display turns on."
                            TriggerMode.SCREEN_OFF_OR_ON -> "Change on either display off or on, with debounce protection."
                            TriggerMode.MANUAL -> "Only change when you request it."
                        }) { onSettingsChange(settings.copy(triggerMode = mode)) }
                    }
                    if (settings.triggerMode == TriggerMode.INTERVAL) {
                        Text("Interval: ${settings.intervalMinutes} minutes", fontWeight = FontWeight.SemiBold)
                        Slider(
                            value = settings.intervalMinutes.toFloat(),
                            onValueChange = { raw ->
                                val stepped = raw.roundToInt()
                                onSettingsChange(settings.copy(intervalMinutes = stepped.coerceIn(1, 240)))
                            },
                            valueRange = 1f..240f, steps = 238,
                        )
                    }
                }
            }

            SectionTitle("Wallpaper target")
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    SettingHeader(Icons.Outlined.Home, "Where should it change?")
                    WallpaperTargetMode.entries.forEach { mode ->
                        RadioSetting(settings.targetMode == mode, mode.label, when (mode) {
                            WallpaperTargetMode.HOME -> "Only the home-screen wallpaper."
                            WallpaperTargetMode.LOCK -> "Only the lock-screen wallpaper."
                            WallpaperTargetMode.BOTH_SAME -> "Use one image on both screens."
                            WallpaperTargetMode.BOTH_DIFFERENT -> "Pick separate images for Home and Lock."
                        }) { onSettingsChange(settings.copy(targetMode = mode)) }
                    }
                }
            }

            SectionTitle("Quality & aspect")
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SettingHeader(Icons.Outlined.HighQuality, "9:20 quality-first pipeline")
                    SettingSwitch(
                        "Smart Crop (mismatch only)",
                        "Your 9:20-compatible wallpapers are streamed untouched: no crop, resize or BB-PixWall recompression. Only an accidental aspect mismatch may be center-cropped to 9:20.",
                        settings.smartCropEnabled
                    ) { onSettingsChange(settings.copy(smartCropEnabled = it)) }
                    Text("Match tolerance: ${settings.smartCropTolerancePct}% • fixed target 9:20", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Last pipeline: ${RuntimeStatus.get(context, "last_pipeline", "Waiting for first apply")}", color = MaterialTheme.colorScheme.primary)
                    Text("Last aspect: ${RuntimeStatus.get(context, "last_aspect", "Unknown")}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Why this wallpaper: ${RuntimeStatus.get(context, "selection_reason", "Waiting for selection")}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Blur is an explicit processed path; the exact downloaded source is still preserved for Save Wall and unblur.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            SectionTitle("Blur")
            BlurCard("Home screen blur", settings.homeBlurEnabled, settings.homeBlurRadius,
                { onSettingsChange(settings.copy(homeBlurEnabled = it)) },
                { onSettingsChange(settings.copy(homeBlurRadius = it)) })
            BlurCard("Lock screen blur", settings.lockBlurEnabled, settings.lockBlurRadius,
                { onSettingsChange(settings.copy(lockBlurEnabled = it)) },
                { onSettingsChange(settings.copy(lockBlurRadius = it)) })

            SectionTitle("Remote access")
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    SettingHeader(Icons.Outlined.Language, "Wi-Fi dashboard")
                    SettingSwitch("Enable LAN access", "Allow devices on the same Wi-Fi to reach BB-PixWall controls.", settings.lanEnabled) { onSettingsChange(settings.copy(lanEnabled = it)) }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        ReadOnlyField("IP address", localIp, Modifier.weight(1f))
                        OutlinedTextField(
                            modifier = Modifier.weight(.75f), value = portText,
                            onValueChange = { text ->
                                val cleaned = text.filter(Char::isDigit).take(5); portText = cleaned
                                cleaned.toIntOrNull()?.takeIf { it in 1024..65535 }?.let { onSettingsChange(settings.copy(lanPort = it)) }
                            },
                            label = { Text("Port") }, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        )
                    }
                    val url = if (localIp == "Unavailable") "Waiting for Wi-Fi IP" else "http://$localIp:${settings.lanPort}"
                    Text(url, color = MaterialTheme.colorScheme.primary, modifier = Modifier.clickable(enabled = localIp != "Unavailable") {
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                    })
                    Text("LAN server accepts local/private-network clients only.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            SectionTitle("Appearance")
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    SettingHeader(Icons.Outlined.Contrast, "Color mode")
                    AppearanceMode.entries.forEach { mode ->
                        RadioSetting(settings.appearanceMode == mode, mode.label, when (mode) {
                            AppearanceMode.LIGHT -> "Always use light surfaces."
                            AppearanceMode.DARK -> "Always use dark surfaces."
                            AppearanceMode.PITCH_BLACK -> "True-black AMOLED background and near-black cards."
                            AppearanceMode.SYSTEM -> "Follow the phone's current light/dark mode."
                            AppearanceMode.SYSTEM_MONET -> "Follow system light/dark and Android Monet colors."
                        }) { onSettingsChange(settings.copy(appearanceMode = mode)) }
                    }
                    HorizontalDivider(Modifier.padding(vertical = 6.dp))
                    SettingHeader(Icons.Outlined.Palette, "Theme style")
                    ThemeProfile.entries.forEach { profile ->
                        RadioSetting(selectedTheme == profile, profile.title, profile.subtitle) { onThemeSelected(profile) }
                    }
                }
            }

            SectionTitle("Quick Settings tiles")
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    TileInfo(Icons.Outlined.SkipNext, "Next Wall", "Apply the queued wallpaper using the selected target mode.")
                    TileInfo(Icons.Outlined.SaveAlt, "Save Wall", "Save current Home/Lock wallpaper into /sdcard/wallpaper/saved/.")
                    TileInfo(Icons.Outlined.BlurOn, "Blur Wall", "Toggle configured Home/Lock blur values.")
                    TileInfo(Icons.Outlined.Language, "BB-PixWall Web", "Open the current Wi-Fi dashboard URL in the browser.")
                    Text("Add these from Android Quick Settings → Edit tiles.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            SectionTitle("Diagnostics")
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text("Engine: ${settings.engineMode.label}", fontWeight = FontWeight.SemiBold)
                    Text("Storage: ${if (storageGranted) "Granted" else "Needs all-files access"}")
                    Text("Cache: ${bb.pix.wall.engine.WallpaperController.cacheCount()} files • ${bb.pix.wall.engine.WallpaperController.cacheBytes() / (1024 * 1024)} MB")
                    Text("Cache pools: ${bb.pix.wall.engine.WallpaperController.cacheBreakdown()}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Automation: ${if (settings.autoChange) settings.triggerMode.label else "Off"}")
                    Text("Engine health: ${RuntimeStatus.get(context, "engine_health", "Waiting for audit")}")
                    Text("Offline ready: ${bb.pix.wall.engine.WallpaperController.offlineReadyCount()} wallpaper(s)")
                    Text("Cycle progress: ${bb.pix.wall.engine.WallpaperController.cycleProgress(context)}")
                    Text("Last trigger: ${RuntimeStatus.get(context, "last_trigger", "None yet")}")
                    Text("Root: ${RuntimeStatus.get(context, "root_state", if (settings.engineMode == EngineMode.ADVANCED) "Not checked" else "Standard mode")}")
                    Text("Root provider: ${RuntimeStatus.get(context, "root_provider", "Unknown")}")
                    Text("Root capabilities: ${RuntimeStatus.get(context, "root_caps", "Not scanned")}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Root probe latency: ${RuntimeStatus.getLong(context, "root_latency_ms", 0L)} ms")
                    Text("Root priority: ${RuntimeStatus.get(context, "root_priority", "Not tuned")}")
                    Text("Doze whitelist: ${RuntimeStatus.get(context, "root_doze_whitelist", "Unknown")}")
                    Text("AppOps verified: ${RuntimeStatus.get(context, "root_appops_verified", "Unknown")}")
                    Text("OOM score: ${RuntimeStatus.get(context, "root_oom_score", "Unknown")}")
                    Text("Root last action: ${RuntimeStatus.get(context, "root_last_result", "None")}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Active source: ${RuntimeStatus.get(context, "active_source", "None yet")}")
                    Text("Last error: ${RuntimeStatus.get(context, "last_error", "None").ifBlank { "None" }}")
                    val nextRun = RuntimeStatus.getLong(context, "next_run", 0L)
                    Text("Next run: ${if (nextRun > 0L) java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(nextRun)) else "Event/manual"}")
                    Text("LAN: ${if (settings.lanEnabled) RuntimeStatus.get(context, "lan_state", "$localIp:${settings.lanPort}") else "Off"}")
                    Text("Logs: /sdcard/wallpaper/logs/runtime.log", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { bb.pix.wall.engine.WallpaperController.clearCache(); onRefreshPreviews() },
                            modifier = Modifier.weight(1f)
                        ) { Text("Clear cache", maxLines = 1) }
                        OutlinedButton(onClick = onPrepareNext, modifier = Modifier.weight(1f)) { Text("Refill cache", maxLines = 1) }
                    }
                    OutlinedButton(
                        onClick = { WallpaperController.exportDebugReport(context) },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Export debug report", maxLines = 1) }
                }
            }

            SectionTitle("Developer")
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("DEVELOPER", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    Text("Bharat Bhat", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    HorizontalDivider()
                    CompactSocialButton(R.drawable.ic_social_email, "bkbhatinfo@gmail.com") { context.startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:bkbhatinfo@gmail.com"))) }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CompactSocialButton(R.drawable.ic_social_instagram, "@officialbharatbhat", Modifier.weight(1f)) { openUrl(context, "https://instagram.com/officialbharatbhat") }
                        CompactSocialButton(R.drawable.ic_social_facebook, "@officialbharatbhat", Modifier.weight(1f)) { openUrl(context, "https://facebook.com/officialbharatbhat") }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CompactSocialButton(R.drawable.ic_social_youtube, "@sloverbofficial", Modifier.weight(1f)) { openUrl(context, "https://youtube.com/@sloverbofficial") }
                        CompactSocialButton(R.drawable.ic_social_telegram, "@BharatBhat", Modifier.weight(1f)) { openUrl(context, "https://t.me/BharatBhat") }
                    }
                }
            }
            Spacer(Modifier.height(28.dp))
        }
        }
    }
}

@Composable private fun PreviewGrid(state: WallpaperState, refresh: () -> Unit, prepare: () -> Unit) {
    var selected by remember { mutableStateOf<Pair<String, String?>?>(null) }
    selected?.let { item ->
        val bmp = rememberPreviewBitmap(item.second)
        AlertDialog(
            onDismissRequest = { selected = null },
            confirmButton = { TextButton(onClick = { selected = null }) { Text("Close") } },
            title = { Text(item.first) },
            text = {
                if (bmp != null) Image(bmp.asImageBitmap(), item.first, Modifier.fillMaxWidth().aspectRatio(.62f).clip(RoundedCornerShape(18.dp)), contentScale = ContentScale.Crop)
                else Text("Preview unavailable")
            }
        )
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Wallpaper preview", style = MaterialTheme.typography.titleLarge)
            Row { TextButton(onClick = prepare) { Text("Prepare next") }; IconButton(onClick = refresh) { Icon(Icons.Outlined.Refresh, "Refresh") } }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            WallpaperThumb("Current Home", state.currentHome, Modifier.weight(1f)) { selected = "Current Home" to state.currentHome }
            WallpaperThumb("Current Lock", state.currentLock, Modifier.weight(1f)) { selected = "Current Lock" to state.currentLock }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            WallpaperThumb("Next Home", state.nextHome, Modifier.weight(1f)) { selected = "Next Home" to state.nextHome }
            WallpaperThumb("Next Lock", state.nextLock, Modifier.weight(1f)) { selected = "Next Lock" to state.nextLock }
        }
    }
}

@Composable private fun WallpaperThumb(label: String, path: String?, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val bitmap = rememberPreviewBitmap(path)
    val source = remember(path) { WallpaperController.sourceFor(path) }
    Card(modifier = modifier.clickable(enabled = path != null, onClick = onClick), shape = RoundedCornerShape(18.dp)) {
        Box(Modifier.fillMaxWidth().aspectRatio(.82f).background(MaterialTheme.colorScheme.surfaceVariant)) {
            if (bitmap != null) Image(bitmap.asImageBitmap(), label, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else Icon(Icons.Outlined.ImageNotSupported, null, Modifier.align(Alignment.Center).size(34.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Surface(Modifier.align(Alignment.BottomStart).fillMaxWidth(), color = MaterialTheme.colorScheme.surface.copy(alpha = .86f)) {
                Column(Modifier.padding(horizontal = 10.dp, vertical = 7.dp)) {
                    Text(label, style = MaterialTheme.typography.labelMedium)
                    Text(source, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

@Composable private fun SourceConfigCard(title: String, subtitle: String, value: String, hint: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onValue: (String) -> Unit) {
    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SettingHeader(icon, title); Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(value = value, onValueChange = onValue, modifier = Modifier.fillMaxWidth(), label = { Text(hint) }, singleLine = true)
    } }
}

@Composable private fun SectionTitle(text: String) { val t = LocalDesignTokens.current; Text(if (t.sectionUppercase) text.uppercase() else text, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onBackground) }
@Composable private fun SettingHeader(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String) { Row(verticalAlignment = Alignment.CenterVertically) { Icon(icon, null); Spacer(Modifier.width(10.dp)); Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) } }
@Composable private fun SettingSwitch(title: String, subtitle: String, checked: Boolean, onChecked: (Boolean) -> Unit) { Row(verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text(title, fontWeight = FontWeight.SemiBold); Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }; Switch(checked, onChecked) } }
@Composable private fun RadioSetting(selected: Boolean, title: String, subtitle: String, onClick: () -> Unit) { Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) { RadioButton(selected, onClick); Spacer(Modifier.width(8.dp)); Column { Text(title, fontWeight = FontWeight.Medium); Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } } }

@Composable private fun BlurCard(title: String, enabled: Boolean, radius: Int, onEnabled: (Boolean) -> Unit, onRadius: (Int) -> Unit) {
    var liveRadius by remember(radius) { mutableStateOf(radius.toFloat()) }
    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Outlined.BlurOn, null); Spacer(Modifier.width(10.dp)); Column(Modifier.weight(1f)) { Text(title, fontWeight = FontWeight.SemiBold); Text(if (enabled) "${liveRadius.roundToInt()} px intensity" else "Disabled • preset ${liveRadius.roundToInt()} px", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }; Switch(enabled, onEnabled) }
        Text("Blur intensity: ${liveRadius.roundToInt()} px", style = MaterialTheme.typography.labelMedium)
        Slider(
            value = liveRadius,
            onValueChange = { liveRadius = it.coerceIn(0f, 64f) },
            onValueChangeFinished = { onRadius(liveRadius.roundToInt().coerceIn(0, 64)) },
            valueRange = 0f..64f,
        )
    } }
}

@Composable private fun ReadOnlyField(label: String, value: String, modifier: Modifier = Modifier) = OutlinedTextField(modifier = modifier, value = value, onValueChange = {}, readOnly = true, singleLine = true, label = { Text(label) })
@Composable private fun TileInfo(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, text: String) { Row(verticalAlignment = Alignment.Top) { Icon(icon, null); Spacer(Modifier.width(12.dp)); Column { Text(title, fontWeight = FontWeight.SemiBold); Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } } }
@Composable private fun CompactSocialButton(iconRes: Int, label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = modifier.heightIn(min = 42.dp), contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)) {
        Icon(painterResource(iconRes), null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(label, maxLines = 1, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable private fun themeBackdrop(theme: ThemeProfile): Brush {
    val c = MaterialTheme.colorScheme
    return when (theme) {
        ThemeProfile.SIGNATURE -> Brush.verticalGradient(listOf(c.background, c.primary.copy(alpha = .055f), c.background))
        ThemeProfile.MINIMAL -> Brush.linearGradient(listOf(c.background, c.background))
        ThemeProfile.GLASS -> Brush.verticalGradient(listOf(c.background, c.primary.copy(alpha = .10f), c.tertiary.copy(alpha = .07f), c.background))
        ThemeProfile.EDITORIAL -> Brush.verticalGradient(listOf(c.background, c.secondary.copy(alpha = .045f), c.background))
        ThemeProfile.MATERIAL_YOU -> Brush.verticalGradient(listOf(c.background, c.primary.copy(alpha = .08f), c.secondary.copy(alpha = .06f)))
    }
}
@Composable private fun rememberPreviewBitmap(path: String?): android.graphics.Bitmap? {
    val state = produceState<android.graphics.Bitmap?>(initialValue = null, key1 = path) {
        value = if (path.isNullOrBlank()) null else withContext(Dispatchers.IO) { decodePreview(path) }
    }
    return state.value
}

private fun decodePreview(path: String): android.graphics.Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (bounds.outWidth / sample > 720 || bounds.outHeight / sample > 1280) sample *= 2
    return BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = android.graphics.Bitmap.Config.RGB_565 })
}
private fun openUrl(context: android.content.Context, url: String) { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } }
