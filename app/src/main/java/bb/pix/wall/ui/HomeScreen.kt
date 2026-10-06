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
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.draw.rotate
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.blur
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
import bb.pix.wall.settings.SettingsBackup
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

    var moodUiTick by
        remember {
            mutableIntStateOf(0)
        }

    val locationPermissionLabel =
        remember(moodUiTick) {
            bb.pix.wall.engine
                .MoodLocationEngine
                .permissionLabel(
                    context
                )
        }

    LaunchedEffect(
        settings.moodEngineEnabled,
        settings.moodWeatherEnabled,
        settings.moodUseDeviceLocation,
    ) {
        if (
            settings.moodEngineEnabled &&
            settings.moodWeatherEnabled
        ) {
            while (true) {
                delay(1500L)
                moodUiTick++
            }
        }
    }

    LaunchedEffect(
        settings.moodAutoReactEnabled
    ) {
        bb.pix.wall.automation
            .MoodAutoReactReceiver
            .schedule(
                context.applicationContext,
                settings.moodAutoReactEnabled,
            )
    }

    val moodLocationPermissionLauncher =
        androidx.activity.compose
            .rememberLauncherForActivityResult(
                androidx.activity.result.contract
                    .ActivityResultContracts
                    .RequestMultiplePermissions()
            ) { grants ->
                val fine =
                    grants[
                        android.Manifest.permission
                            .ACCESS_FINE_LOCATION
                    ] == true

                val coarse =
                    grants[
                        android.Manifest.permission
                            .ACCESS_COARSE_LOCATION
                    ] == true

                moodUiTick++

                if (fine || coarse) {
                    RuntimeStatus.set(
                        context,
                        "weather_location_status",
                        if (fine) {
                            "Precise location granted"
                        } else {
                            "Approximate location granted"
                        },
                    )

                    bb.pix.wall.engine
                        .WeatherMoodEngine
                        .forceRefresh(
                            context.applicationContext,
                            settings.copy(
                                moodUseDeviceLocation =
                                    true
                            ),
                        )
                } else {
                    RuntimeStatus.set(
                        context,
                        "weather_location_status",
                        "Location permission denied • manual fallback available",
                    )
                }
            }
    val tokens = LocalDesignTokens.current
    val spacing = (16 * tokens.spacingScale).dp
    val localIp = remember { LanInfo.localIpv4() }
    val storageGranted = Environment.isExternalStorageManager()
    var portText by remember(settings.lanPort) {
        mutableStateOf(settings.lanPort.toString())
    }

    Box(Modifier.fillMaxSize().background(themeBackdrop(selectedTheme))) {
        Scaffold(containerColor = Color.Transparent, contentColor = MaterialTheme.colorScheme.onBackground) { padding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(
                        horizontal = 0.dp,
                        vertical = 8.dp,
                    ),
            verticalArrangement =
                Arrangement.spacedBy(tokens.sectionGap),
        ) {
            PremiumAppHeader(
                context = context,
                engineMode = settings.engineMode,
                theme = selectedTheme,
            )

            ThinkingSweepLine(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(
                            horizontal =
                                28.dp
                        )
            )


            PreviewGrid(wallpaperState, onRefreshPreviews, onPrepareNext)

            CollapsibleSection(
                title = "Sources",
                summary = "Cloud • Local • Priority",
            ) {
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
            Card(
                Modifier.fillMaxWidth()
            ) {
                Column(
                    Modifier.padding(16.dp),
                    verticalArrangement =
                        Arrangement.spacedBy(
                            4.dp
                        ),
                ) {
                    SettingHeader(
                        Icons.Outlined.Tune,
                        "Source priority",
                    )

                    Text(
                        "Used when multiple usable sources are available. Source health and offline fallback still stay active.",
                        style =
                            MaterialTheme
                                .typography
                                .bodySmall,
                        color =
                            MaterialTheme
                                .colorScheme
                                .onSurfaceVariant,
                    )

                    SourcePriorityMode.entries
                        .forEach { mode ->
                            RadioSetting(
                                settings
                                    .sourcePriorityMode ==
                                    mode,
                                mode.label,
                                when (mode) {
                                    SourcePriorityMode.SMART_BALANCED ->
                                        "Balanced Photos-first behavior with health-aware fallback."

                                    SourcePriorityMode.PHOTOS_FIRST ->
                                        "Give Google Photos the strongest ranking preference."

                                    SourcePriorityMode.DRIVE_FIRST ->
                                        "Prefer Drive whenever Drive candidates are available."

                                    SourcePriorityMode.LOCAL_FIRST ->
                                        "Prefer local/offline candidates whenever available."
                                },
                            ) {
                                onSettingsChange(
                                    settings.copy(
                                        sourcePriorityMode =
                                            mode
                                    )
                                )
                            }
                        }
                }
            }

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

            }

CollapsibleSection(
                title = "Wallpaper automation",
                summary = "Trigger • Schedule",
            ) {
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

            }

CollapsibleSection(
                title = "Wallpaper target",
                summary = "Home • Lock",
            ) {
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

            }

CollapsibleSection(
                title = "Wallpaper order",
                summary = "Ranking • Shuffle",
            ) {
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

            }

CollapsibleSection(
                title = "Engine mode",
                summary = "Standard • Advanced",
            ) {
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

            }

CollapsibleSection(
                title = "Quality & aspect",
                summary = "Visual IQ • Crop",
            ) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SettingHeader(Icons.Outlined.HighQuality, "9:20 quality-first pipeline")

                    Text(
                        "Visual Intelligence V2",
                        fontWeight =
                            FontWeight.SemiBold,
                    )

                    Text(
                        bb.pix.wall.engine.RuntimeStatus.get(
                            context,
                            "visual_last_decision",
                            bb.pix.wall.engine.RuntimeStatus.get(
                                context,
                                "visual_last_profile",
                                "Waiting for analyzed wallpaper",
                            ),
                        ),
                        style =
                            MaterialTheme
                                .typography
                                .bodySmall,
                        color =
                            MaterialTheme
                                .colorScheme
                                .onSurfaceVariant,
                    )

                    Text(
                        "Palette: ${
                            bb.pix.wall.engine.RuntimeStatus.get(
                                context,
                                "visual_last_palette",
                                "Waiting"
                            )
                        }",
                        style =
                            MaterialTheme
                                .typography
                                .bodySmall,
                        color =
                            MaterialTheme
                                .colorScheme
                                .onSurfaceVariant,
                    )

                    Text(
                        "Pairing: ${
                            bb.pix.wall.engine.RuntimeStatus.get(
                                context,
                                "visual_pairing",
                                "Waiting for Home/Lock pair"
                            )
                        }",
                        style =
                            MaterialTheme
                                .typography
                                .bodySmall,
                        color =
                            MaterialTheme
                                .colorScheme
                                .onSurfaceVariant,
                    )

                    Text(
                        "Analyzes palette, AMOLED suitability, readability, composition, crop safety, detail density and visual style locally.",
                        style =
                            MaterialTheme
                                .typography
                                .bodySmall,
                        color =
                            MaterialTheme
                                .colorScheme
                                .onSurfaceVariant,
                    )
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

            }

CollapsibleSection(
                title = "Mood Engine",
                summary = "Context • Weather",
            ) {
                Card(
                    Modifier.fillMaxWidth()
                ) {
                    Column(
                        Modifier.padding(18.dp),
                        verticalArrangement =
                            Arrangement.spacedBy(
                                12.dp
                            ),
                    ) {
                        SettingSwitch(
                            "Adaptive Mood Wallpapers",
                            "Let BB-PixWall gently rank wallpapers using room light, time of day, Android theme, battery and device thermal context. Quality, duplicate protection and learned taste remain higher priority.",
                            settings.moodEngineEnabled,
                        ) {
                            onSettingsChange(
                                settings.copy(
                                    moodEngineEnabled = it
                                )
                            )
                        }

                        if (settings.moodEngineEnabled) {
                            HorizontalDivider()

                            Text(
                                "Current Mood",
                                fontWeight =
                                    FontWeight.Bold,
                            )

                            Text(
                                RuntimeStatus.get(
                                    context,
                                    "mood_profile_label",
                                    "Waiting for first smart decision",
                                ),
                                style =
                                    MaterialTheme
                                        .typography
                                        .titleMedium,
                                color =
                                    MaterialTheme
                                        .colorScheme
                                        .primary,
                            )

                            Text(
                                RuntimeStatus.get(
                                    context,
                                    "mood_profile_summary",
                                    "Environment profile not sampled yet",
                                ),
                                style =
                                    MaterialTheme
                                        .typography
                                        .bodySmall,
                                color =
                                    MaterialTheme
                                        .colorScheme
                                        .onSurfaceVariant,
                            )

                            Text(
                                "Last influence: ${
                                    RuntimeStatus.get(
                                        context,
                                        "mood_last_factors",
                                        "Waiting for ranked selection",
                                    )
                                }",
                                style =
                                    MaterialTheme
                                        .typography
                                        .bodySmall,
                                color =
                                    MaterialTheme
                                        .colorScheme
                                        .onSurfaceVariant,
                            )

                            Text(
                                "Auto-react: ${
                                    RuntimeStatus.get(
                                        context,
                                        "mood_auto_react",
                                        if (
                                            settings
                                                .moodAutoReactEnabled
                                        ) {
                                            "Watching context"
                                        } else {
                                            "Off"
                                        },
                                    )
                                }",
                                style =
                                    MaterialTheme
                                        .typography
                                        .bodySmall,
                                color =
                                    MaterialTheme
                                        .colorScheme
                                        .onSurfaceVariant,
                            )

                            HorizontalDivider()

                            Text(
                                "Current environment",
                                fontWeight =
                                    FontWeight.SemiBold,
                            )

                            Text(
                                RuntimeStatus.get(
                                    context,
                                    "adaptive_mood_context",
                                    "Waiting for next wallpaper decision",
                                ),
                                color =
                                    MaterialTheme
                                        .colorScheme
                                        .primary,
                            )

                            Text(
                                "Mood affects ranking, not hard filtering. Your wallpaper library stays diverse.",
                                style =
                                    MaterialTheme
                                        .typography
                                        .bodySmall,
                                color =
                                    MaterialTheme
                                        .colorScheme
                                        .onSurfaceVariant,
                            )

                            SettingSwitch(
                                "Auto-react to environment",
                                "When the overall environment meaningfully changes, apply an already-cached wallpaper automatically. Cloud fetching is never performed just for auto-react.",
                                settings.moodAutoReactEnabled,
                            ) { enabled ->
                                val updated =
                                    settings.copy(
                                        moodAutoReactEnabled =
                                            enabled
                                    )

                                onSettingsChange(
                                    updated
                                )

                                bb.pix.wall.automation
                                    .MoodAutoReactReceiver
                                    .schedule(
                                        context.applicationContext,
                                        enabled,
                                    )

                                moodUiTick++
                            }

                            if (
                                settings
                                    .moodAutoReactEnabled
                            ) {
                                Text(
                                    "Auto-react cooldown: ${settings.moodAutoReactCooldownMinutes} min",
                                    fontWeight =
                                        FontWeight
                                            .SemiBold,
                                )

                                Slider(
                                    value =
                                        settings
                                            .moodAutoReactCooldownMinutes
                                            .toFloat(),
                                    onValueChange = {
                                        val value =
                                            (
                                                it /
                                                    15f
                                            )
                                                .roundToInt() *
                                                15

                                        onSettingsChange(
                                            settings.copy(
                                                moodAutoReactCooldownMinutes =
                                                    value.coerceIn(
                                                        15,
                                                        180,
                                                    )
                                            )
                                        )
                                    },
                                    valueRange =
                                        15f..180f,
                                    steps = 10,
                                )

                                Text(
                                    "Auto-react uses a 30-minute low-power context check and never wakes the phone just to change wallpaper.",
                                    style =
                                        MaterialTheme
                                            .typography
                                            .bodySmall,
                                    color =
                                        MaterialTheme
                                            .colorScheme
                                            .onSurfaceVariant,
                                )
                            }

                            SettingSwitch(
                                "Ambient Light",
                                "Sample the phone light sensor briefly when BB-PixWall needs a new wallpaper. No continuous sensor monitoring.",
                                settings
                                    .moodAmbientLightEnabled,
                            ) {
                                onSettingsChange(
                                    settings.copy(
                                        moodAmbientLightEnabled =
                                            it
                                    )
                                )
                            }

                            if (
                                settings
                                    .moodAmbientLightEnabled
                            ) {
                                Text(
                                    "Dark room: ≤ ${settings.moodDarkLuxThreshold} lux",
                                    fontWeight =
                                        FontWeight
                                            .SemiBold,
                                )

                                Slider(
                                    value =
                                        settings
                                            .moodDarkLuxThreshold
                                            .toFloat(),
                                    onValueChange = {
                                        val value =
                                            it.roundToInt()
                                                .coerceIn(
                                                    1,
                                                    200,
                                                )

                                        onSettingsChange(
                                            settings.copy(
                                                moodDarkLuxThreshold =
                                                    value
                                            )
                                        )
                                    },
                                    valueRange =
                                        1f..200f,
                                    steps = 198,
                                )

                                Text(
                                    "Bright environment: ≥ ${settings.moodBrightLuxThreshold} lux",
                                    fontWeight =
                                        FontWeight
                                            .SemiBold,
                                )

                                Slider(
                                    value =
                                        settings
                                            .moodBrightLuxThreshold
                                            .toFloat(),
                                    onValueChange = {
                                        val value =
                                            it.roundToInt()
                                                .coerceIn(
                                                    100,
                                                    5000,
                                                )
                                                .coerceAtLeast(
                                                    settings
                                                        .moodDarkLuxThreshold +
                                                        50
                                                )

                                        onSettingsChange(
                                            settings.copy(
                                                moodBrightLuxThreshold =
                                                    value
                                            )
                                        )
                                    },
                                    valueRange =
                                        100f..5000f,
                                    steps = 97,
                                )

                                Text(
                                    "Last light sample: ${
                                        RuntimeStatus.get(
                                            context,
                                            "adaptive_mood_lux",
                                            "Not sampled yet",
                                        )
                                    } lux",
                                    style =
                                        MaterialTheme
                                            .typography
                                            .bodySmall,
                                    color =
                                        MaterialTheme
                                            .colorScheme
                                            .onSurfaceVariant,
                                )
                            }

                            SettingSwitch(
                                "Time Mood",
                                "Use Early Morning, Morning, Afternoon, Evening, Night and Midnight as subtle visual context.",
                                settings.moodTimeEnabled,
                            ) {
                                onSettingsChange(
                                    settings.copy(
                                        moodTimeEnabled = it
                                    )
                                )
                            }

                            SettingSwitch(
                                "Dark Mode Sync",
                                "Give darker wallpapers a small extra preference when Android dark mode is active.",
                                settings.moodDarkModeEnabled,
                            ) {
                                onSettingsChange(
                                    settings.copy(
                                        moodDarkModeEnabled =
                                            it
                                    )
                                )
                            }

                            SettingSwitch(
                                "Battery Context",
                                "Battery Saver and low battery prefer calmer visuals; charging allows a tiny vibrant bias.",
                                settings
                                    .moodBatteryContextEnabled,
                            ) {
                                onSettingsChange(
                                    settings.copy(
                                        moodBatteryContextEnabled =
                                            it
                                    )
                                )
                            }

                            SettingSwitch(
                                "Thermal Protection",
                                "Include Android thermal state in Mood decisions. Heavy-work thermal throttling will also use this signal in the resource-protection upgrade.",
                                settings
                                    .moodThermalProtectionEnabled,
                            ) {
                                onSettingsChange(
                                    settings.copy(
                                        moodThermalProtectionEnabled =
                                            it
                                    )
                                )
                            }

                            Text(
                                "Thermal: ${
                                    RuntimeStatus.get(
                                        context,
                                        "adaptive_mood_thermal",
                                        "Waiting for sample",
                                    )
                                }",
                                style =
                                    MaterialTheme
                                        .typography
                                        .bodySmall,
                                color =
                                    MaterialTheme
                                        .colorScheme
                                        .onSurfaceVariant,
                            )

                            HorizontalDivider()

                            Text(
                                "Mood strength: ${settings.moodStrength}%",
                                fontWeight =
                                    FontWeight.SemiBold,
                            )

                            Slider(
                                value =
                                    settings
                                        .moodStrength
                                        .toFloat(),
                                onValueChange = {
                                    onSettingsChange(
                                        settings.copy(
                                            moodStrength =
                                                it.roundToInt()
                                                    .coerceIn(
                                                        0,
                                                        100,
                                                    )
                                        )
                                    )
                                },
                                valueRange =
                                    0f..100f,
                                steps = 19,
                            )

                            Text(
                                when {
                                    settings.moodStrength <= 25 ->
                                        "Gentle — existing taste and randomness dominate."

                                    settings.moodStrength <= 60 ->
                                        "Balanced — environment has a noticeable but safe influence."

                                    settings.moodStrength <= 85 ->
                                        "Strong — environment meaningfully shapes selection."

                                    else ->
                                        "Maximum — Mood Engine gets its strongest allowed ranking bias."
                                },
                                style =
                                    MaterialTheme
                                        .typography
                                        .bodySmall,
                                color =
                                    MaterialTheme
                                        .colorScheme
                                        .onSurfaceVariant,
                            )

                            HorizontalDivider()

                            SettingSwitch(
                                "Weather Mood",
                                "Use current weather conditions to gently influence wallpaper selection. Weather stays cached, so wallpaper changes do not wait for the internet.",
                                settings.moodWeatherEnabled,
                            ) { enabled ->
                                val updated =
                                    settings.copy(
                                        moodWeatherEnabled =
                                            enabled
                                    )

                                onSettingsChange(
                                    updated
                                )

                                if (enabled) {
                                    val granted =
                                        bb.pix.wall.engine
                                            .MoodLocationEngine
                                            .hasAnyPermission(
                                                context
                                            )

                                    if (
                                        updated
                                            .moodUseDeviceLocation &&
                                        !granted
                                    ) {
                                        moodLocationPermissionLauncher
                                            .launch(
                                                arrayOf(
                                                    android.Manifest.permission.ACCESS_FINE_LOCATION,
                                                    android.Manifest.permission.ACCESS_COARSE_LOCATION,
                                                )
                                            )
                                    } else {
                                        bb.pix.wall.engine
                                            .WeatherMoodEngine
                                            .forceRefresh(
                                                context.applicationContext,
                                                updated,
                                            )
                                    }
                                }
                            }

                            if (settings.moodWeatherEnabled) {
                                SettingSwitch(
                                    "Use Device Location",
                                    "Automatically update Weather Mood when your location changes. Approximate location is enough; precise location is optional.",
                                    settings
                                        .moodUseDeviceLocation,
                                ) { enabled ->
                                    val updated =
                                        settings.copy(
                                            moodUseDeviceLocation =
                                                enabled
                                        )

                                    onSettingsChange(
                                        updated
                                    )

                                    if (enabled) {
                                        val granted =
                                            bb.pix.wall.engine
                                                .MoodLocationEngine
                                                .hasAnyPermission(
                                                    context
                                                )

                                        if (!granted) {
                                            moodLocationPermissionLauncher
                                                .launch(
                                                    arrayOf(
                                                        android.Manifest.permission.ACCESS_FINE_LOCATION,
                                                        android.Manifest.permission.ACCESS_COARSE_LOCATION,
                                                    )
                                                )
                                        } else {
                                            bb.pix.wall.engine
                                                .WeatherMoodEngine
                                                .forceRefresh(
                                                    context.applicationContext,
                                                    updated,
                                                )
                                        }
                                    } else {
                                        bb.pix.wall.engine
                                            .WeatherMoodEngine
                                            .forceRefresh(
                                                context.applicationContext,
                                                updated,
                                            )
                                    }
                                }

                                if (
                                    settings
                                        .moodUseDeviceLocation
                                ) {
                                    Text(
                                        "Location permission: ${
                                            locationPermissionLabel
                                        }",
                                        style =
                                            MaterialTheme
                                                .typography
                                                .bodySmall,
                                        color =
                                            MaterialTheme
                                                .colorScheme
                                                .onSurfaceVariant,
                                    )

                                    Text(
                                        "Current location: ${
                                            RuntimeStatus.get(
                                                context,
                                                "weather_location_status",
                                                "Waiting for location",
                                            )
                                        }",
                                        style =
                                            MaterialTheme
                                                .typography
                                                .bodySmall,
                                        color =
                                            MaterialTheme
                                                .colorScheme
                                                .primary,
                                    )
                                }

                                OutlinedTextField(
                                    value =
                                        settings.moodWeatherCity,
                                    onValueChange = {
                                        onSettingsChange(
                                            settings.copy(
                                                moodWeatherCity =
                                                    it
                                            )
                                        )
                                    },
                                    modifier =
                                        Modifier.fillMaxWidth(),
                                    singleLine = true,
                                    label = {
                                        Text(
                                            if (
                                                settings
                                                    .moodUseDeviceLocation
                                            ) {
                                                "Manual fallback city"
                                            } else {
                                                "Weather city"
                                            }
                                        )
                                    },
                                    placeholder = {
                                        Text(
                                            "Example: Miraj, Maharashtra"
                                        )
                                    },
                                )

                                Text(
                                    RuntimeStatus.get(
                                        context,
                                        "weather_mood_status",
                                        if (
                                            settings
                                                .moodWeatherCity
                                                .isBlank()
                                        ) {
                                            "Enter a city to start Weather Mood"
                                        } else {
                                            "Weather will refresh on the next decision"
                                        },
                                    ),
                                    style =
                                        MaterialTheme
                                            .typography
                                            .bodySmall,
                                    color =
                                        MaterialTheme
                                            .colorScheme
                                            .primary,
                                )

                                TextButton(
                                    enabled =
                                        settings
                                            .moodUseDeviceLocation ||
                                            settings
                                                .moodWeatherCity
                                                .isNotBlank(),
                                    onClick = {
                                        bb.pix.wall.engine
                                            .WeatherMoodEngine
                                            .forceRefresh(
                                                context.applicationContext,
                                                settings,
                                            )

                                        moodUiTick++
                                    }
                                ) {
                                    Text(
                                        if (
                                            settings
                                                .moodUseDeviceLocation
                                        ) {
                                            "Refresh location & weather"
                                        } else {
                                            "Refresh weather"
                                        }
                                    )
                                }

                                Text(
                                    "Weather influence: ${settings.moodWeatherInfluence}%",
                                    fontWeight =
                                        FontWeight
                                            .SemiBold,
                                )

                                Slider(
                                    value =
                                        settings
                                            .moodWeatherInfluence
                                            .toFloat(),
                                    onValueChange = {
                                        onSettingsChange(
                                            settings.copy(
                                                moodWeatherInfluence =
                                                    it.roundToInt()
                                                        .coerceIn(
                                                            0,
                                                            100,
                                                        )
                                            )
                                        )
                                    },
                                    valueRange =
                                        0f..100f,
                                    steps = 19,
                                )

                                Text(
                                    when {
                                        settings.moodWeatherInfluence <= 25 ->
                                            "Gentle weather influence"

                                        settings.moodWeatherInfluence <= 60 ->
                                            "Balanced weather influence"

                                        settings.moodWeatherInfluence <= 85 ->
                                            "Strong weather influence"

                                        else ->
                                            "Maximum weather influence"
                                    },
                                    style =
                                        MaterialTheme
                                            .typography
                                            .bodySmall,
                                    color =
                                        MaterialTheme
                                            .colorScheme
                                            .onSurfaceVariant,
                                )

                                SettingSwitch(
                                    "Outdoor Temperature",
                                    "Use current outdoor temperature together with weather conditions when ranking wallpapers.",
                                    settings
                                        .moodOutdoorTemperatureEnabled,
                                ) {
                                    onSettingsChange(
                                        settings.copy(
                                            moodOutdoorTemperatureEnabled =
                                                it
                                        )
                                    )
                                }

                                if (
                                    settings
                                        .moodOutdoorTemperatureEnabled
                                ) {
                                    Text(
                                        "Cold weather: ≤ ${settings.moodColdTemperatureC}°C",
                                        fontWeight =
                                            FontWeight
                                                .SemiBold,
                                    )

                                    Slider(
                                        value =
                                            settings
                                                .moodColdTemperatureC
                                                .toFloat(),
                                        onValueChange = {
                                            val cold =
                                                it.roundToInt()
                                                    .coerceIn(
                                                        -10,
                                                        30,
                                                    )
                                                    .coerceAtMost(
                                                        settings
                                                            .moodHotTemperatureC -
                                                            2
                                                    )

                                            onSettingsChange(
                                                settings.copy(
                                                    moodColdTemperatureC =
                                                        cold
                                                )
                                            )
                                        },
                                        valueRange =
                                            -10f..30f,
                                        steps = 39,
                                    )

                                    Text(
                                        "Hot weather: ≥ ${settings.moodHotTemperatureC}°C",
                                        fontWeight =
                                            FontWeight
                                                .SemiBold,
                                    )

                                    Slider(
                                        value =
                                            settings
                                                .moodHotTemperatureC
                                                .toFloat(),
                                        onValueChange = {
                                            val hot =
                                                it.roundToInt()
                                                    .coerceIn(
                                                        20,
                                                        50,
                                                    )
                                                    .coerceAtLeast(
                                                        settings
                                                            .moodColdTemperatureC +
                                                            2
                                                    )

                                            onSettingsChange(
                                                settings.copy(
                                                    moodHotTemperatureC =
                                                        hot
                                                )
                                            )
                                        },
                                        valueRange =
                                            20f..50f,
                                        steps = 29,
                                    )
                                }

                                Text(
                                    "Weather is cached for about 45 minutes. Automatic location is rechecked periodically without continuous GPS tracking or background-location access.",
                                    style =
                                        MaterialTheme
                                            .typography
                                            .bodySmall,
                                    color =
                                        MaterialTheme
                                            .colorScheme
                                            .onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }

CollapsibleSection(
                title = "Blur",
                summary = "Home • Lock",
            ) {
            BlurCard("Home screen blur", settings.homeBlurEnabled, settings.homeBlurRadius,
                { onSettingsChange(settings.copy(homeBlurEnabled = it)) },
                { onSettingsChange(settings.copy(homeBlurRadius = it)) })
            BlurCard("Lock screen blur", settings.lockBlurEnabled, settings.lockBlurRadius,
                { onSettingsChange(settings.copy(lockBlurEnabled = it)) },
                { onSettingsChange(settings.copy(lockBlurRadius = it)) })

            }

CollapsibleSection(
                title = "Network & cache",
                summary = "Network • Prefetch",
            ) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    SettingSwitch("Data Saver", "When ON, cloud sources request reduced-resolution images where supported. Local wallpapers stay untouched.", settings.dataSaverEnabled) { onSettingsChange(settings.copy(dataSaverEnabled = it)) }
                    SettingSwitch("Wi-Fi only", "Use Wi-Fi for cloud prefetch. Already-cached wallpapers can still rotate offline.", settings.wifiOnly) { onSettingsChange(settings.copy(wifiOnly = it)) }
                    SettingSwitch("Allow mobile data", "Allow cloud prefetch on mobile data when Wi-Fi-only is OFF.", settings.mobileDataAllowed) { onSettingsChange(settings.copy(mobileDataAllowed = it)) }
                    SettingSwitch("Lean local storage", "Keep only the configured prefetch amount even in Advance mode. Google Photos stays primary and Drive remains the mirror/fallback.", settings.leanStorageMode) { onSettingsChange(settings.copy(leanStorageMode = it)) }
                    SettingSwitch(
                        "Smart Home/Lock pairing",
                        "When Home + Lock are different, rank the second cached wallpaper as a visually intentional companion instead of a near-clone.",
                        settings.smartPairingEnabled,
                    ) {
                        onSettingsChange(
                            settings.copy(
                                smartPairingEnabled = it
                            )
                        )
                    }

                    SettingSwitch(
                        "Adaptive Resource Protection",
                        "Pause or reduce cache analysis/refill under Battery Saver, low battery or elevated thermal state.",
                        settings.adaptiveResourceProtectionEnabled,
                    ) {
                        onSettingsChange(
                            settings.copy(
                                adaptiveResourceProtectionEnabled =
                                    it
                            )
                        )
                    }

                    Text(
                        "Cache storage cap: ${settings.cacheMaxMb} MB",
                        fontWeight =
                            FontWeight.SemiBold,
                    )

                    Slider(
                        value =
                            settings.cacheMaxMb
                                .toFloat(),
                        onValueChange = {
                            val value =
                                (
                                    it.roundToInt() /
                                        64
                                ) *
                                    64

                            onSettingsChange(
                                settings.copy(
                                    cacheMaxMb =
                                        value.coerceIn(
                                            128,
                                            2048,
                                        )
                                )
                            )
                        },
                        valueRange =
                            128f..2048f,
                        steps = 29,
                    )

                    Text(
                        "Resource policy: ${
                            RuntimeStatus.get(
                                context,
                                "resource_policy",
                                "Waiting for engine activity",
                            )
                        }",
                        style =
                            MaterialTheme
                                .typography
                                .bodySmall,
                        color =
                            MaterialTheme
                                .colorScheme
                                .onSurfaceVariant,
                    )
                    SettingSwitch("Charging only", "Run automatic wallpaper changes only while charging.", settings.chargingOnly) { onSettingsChange(settings.copy(chargingOnly = it)) }
                    SettingSwitch("Pause in Battery Saver", "Pause automatic changes while Android Battery Saver is active.", settings.pauseBatterySaver) { onSettingsChange(settings.copy(pauseBatterySaver = it)) }
                    Text("Prefetch cache: ${settings.cacheTarget} wallpapers", fontWeight = FontWeight.SemiBold)
                    Slider(value = settings.cacheTarget.toFloat(), onValueChange = { onSettingsChange(settings.copy(cacheTarget = it.roundToInt().coerceIn(4, 36))) }, valueRange = 4f..36f, steps = 31)

                    Text(if (settings.leanStorageMode) "Lean mode: cache stays at this exact target to avoid wasting phone storage." else "Expanded mode: Advance may keep at least 16 ready wallpapers.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            }

CollapsibleSection(
                title = "Background reliability",
                summary = "Protection • Recovery",
            ) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SettingSwitch("Background guard", "Keep the automation foreground service sticky; Advance mode also applies root-only app-specific background tuning.", settings.backgroundGuardEnabled) { onSettingsChange(settings.copy(backgroundGuardEnabled = it)) }

                    HorizontalDivider()

                    Text(
                        "Autonomous Intelligence Core",
                        fontWeight =
                            FontWeight.SemiBold,
                    )

                    Text(
                        "Engine ${
                            bb.pix.wall.engine.RuntimeStatus.get(
                                context,
                                "autonomous_grade",
                                "Waiting for first autonomous audit"
                            )
                        }",
                        style =
                            MaterialTheme.typography.bodyMedium,
                    )

                    Text(
                        "Self-heal: ${
                            bb.pix.wall.engine.RuntimeStatus.get(
                                context,
                                "self_heal_last",
                                "Waiting"
                            )
                        }",
                        style =
                            MaterialTheme.typography.bodySmall,
                        color =
                            MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Text(
                        "Storage: ${
                            bb.pix.wall.engine.RuntimeStatus.get(
                                context,
                                "storage_pressure",
                                "Waiting"
                            )
                        }",
                        style =
                            MaterialTheme.typography.bodySmall,
                        color =
                            MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Text(
                        "DNA: ${
                            bb.pix.wall.engine.RuntimeStatus.get(
                                context,
                                "wallpaper_dna",
                                "Waiting for analyzed current wallpaper"
                            )
                        }",
                        style =
                            MaterialTheme.typography.bodySmall,
                        color =
                            MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Text(
                        "Family fatigue: ${
                            bb.pix.wall.engine.RuntimeStatus.get(
                                context,
                                "family_fatigue",
                                "Learning"
                            )
                        }",
                        style =
                            MaterialTheme.typography.bodySmall,
                        color =
                            MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Text(
                        "Pair director: ${
                            bb.pix.wall.engine.RuntimeStatus.get(
                                context,
                                "pair_story",
                                "Waiting for Home/Lock profiles"
                            )
                        }",
                        style =
                            MaterialTheme.typography.bodySmall,
                        color =
                            MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Text(
                        "Taste confidence: ${
                            bb.pix.wall.engine.RuntimeStatus.get(
                                context,
                                "taste_confidence_v2",
                                "Learning"
                            )
                        } • ${
                            bb.pix.wall.engine.RuntimeStatus.get(
                                context,
                                "learning_mode",
                                "Explore"
                            )
                        }",
                        style =
                            MaterialTheme.typography.bodySmall,
                        color =
                            MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Text(
                        "Context: ${
                            bb.pix.wall.engine.RuntimeStatus.get(
                                context,
                                "context_confidence",
                                "Waiting"
                            )
                        } • ${
                            bb.pix.wall.engine.RuntimeStatus.get(
                                context,
                                "context_conflict",
                                "Waiting"
                            )
                        }",
                        style =
                            MaterialTheme.typography.bodySmall,
                        color =
                            MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Text(
                        "Decision ${
                            bb.pix.wall.engine.RuntimeStatus.get(
                                context,
                                "decision_confidence_v2",
                                "Waiting"
                            )
                        } • trace ${
                            bb.pix.wall.engine.RuntimeStatus.get(
                                context,
                                "decision_trace_id",
                                "-"
                            )
                        }",
                        style =
                            MaterialTheme.typography.bodySmall,
                        color =
                            MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Text(
                        "Why V2: ${
                            bb.pix.wall.engine.RuntimeStatus.get(
                                context,
                                "decision_why_v2",
                                "Waiting for ranked selection"
                            )
                        }",
                        style =
                            MaterialTheme.typography.bodySmall,
                        color =
                            MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Text(
                        "Shadow: ${
                            bb.pix.wall.engine.RuntimeStatus.get(
                                context,
                                "shadow_rank",
                                "Waiting"
                            )
                        }",
                        style =
                            MaterialTheme.typography.bodySmall,
                        color =
                            MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    TextButton(
                        onClick = {
                            bb.pix.wall.engine.EngineExecutors.io {
                                runCatching {
                                    bb.pix.wall.engine
                                        .AutonomousIntelligenceEngine
                                        .auditAndRepair(
                                            context =
                                                context.applicationContext,
                                            settings =
                                                bb.pix.wall.settings
                                                    .SettingsStore(context)
                                                    .load(),
                                            allowNetworkRefill =
                                                false,
                                        )
                                }
                            }
                        }
                    ) {
                        Text(
                            "Run autonomous audit"
                        )
                    }

                    HorizontalDivider()

                    Text(
                        "Premium Intelligence Control Center",
                        fontWeight =
                            FontWeight.SemiBold,
                    )

                    Text(
                        "HOME DNA • ${
                            RuntimeStatus.get(
                                context,
                                "premium_home_dna",
                                "Waiting"
                            )
                        }",
                        style =
                            MaterialTheme.typography.bodySmall,
                    )

                    Text(
                        "Home palette • ${
                            RuntimeStatus.get(
                                context,
                                "premium_home_palette",
                                "Waiting"
                            )
                        }",
                        style =
                            MaterialTheme.typography.bodySmall,
                        color =
                            MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Text(
                        "Home role ${
                            RuntimeStatus.get(
                                context,
                                "premium_home_role",
                                "Waiting"
                            )
                        } • quality ${
                            RuntimeStatus.get(
                                context,
                                "premium_home_quality",
                                "Waiting"
                            )
                        } • AMOLED ${
                            RuntimeStatus.get(
                                context,
                                "premium_home_amoled",
                                "Waiting"
                            )
                        }",
                        style =
                            MaterialTheme.typography.bodySmall,
                        color =
                            MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Text(
                        "LOCK DNA • ${
                            RuntimeStatus.get(
                                context,
                                "premium_lock_dna",
                                "Waiting"
                            )
                        }",
                        style =
                            MaterialTheme.typography.bodySmall,
                    )

                    Text(
                        "Lock palette • ${
                            RuntimeStatus.get(
                                context,
                                "premium_lock_palette",
                                "Waiting"
                            )
                        }",
                        style =
                            MaterialTheme.typography.bodySmall,
                        color =
                            MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Text(
                        "Lock role ${
                            RuntimeStatus.get(
                                context,
                                "premium_lock_role",
                                "Waiting"
                            )
                        } • readability ${
                            RuntimeStatus.get(
                                context,
                                "premium_lock_readability",
                                "Waiting"
                            )
                        } • crop ${
                            RuntimeStatus.get(
                                context,
                                "premium_lock_crop",
                                "Waiting"
                            )
                        }",
                        style =
                            MaterialTheme.typography.bodySmall,
                        color =
                            MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Text(
                        "Pair • ${
                            RuntimeStatus.get(
                                context,
                                "premium_pair_summary",
                                "Waiting"
                            )
                        }",
                        style =
                            MaterialTheme.typography.bodySmall,
                    )

                    Text(
                        "Pair brightness • ${
                            RuntimeStatus.get(
                                context,
                                "premium_pair_brightness",
                                "Waiting"
                            )
                        }",
                        style =
                            MaterialTheme.typography.bodySmall,
                        color =
                            MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Text(
                        "Cache • ${
                            RuntimeStatus.get(
                                context,
                                "premium_cache_map",
                                "Waiting"
                            )
                        } • ${
                            RuntimeStatus.get(
                                context,
                                "premium_cache_size",
                                "-"
                            )
                        }",
                        style =
                            MaterialTheme.typography.bodySmall,
                    )

                    Text(
                        "Offline readiness • ${
                            RuntimeStatus.get(
                                context,
                                "premium_cache_readiness",
                                "Waiting"
                            )
                        }",
                        style =
                            MaterialTheme.typography.bodySmall,
                        color =
                            MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Text(
                        "Photos • ${
                            RuntimeStatus.get(
                                context,
                                "premium_source_photos",
                                "Learning"
                            )
                        }",
                        style =
                            MaterialTheme.typography.bodySmall,
                        color =
                            MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Text(
                        "Drive • ${
                            RuntimeStatus.get(
                                context,
                                "premium_source_drive",
                                "Learning"
                            )
                        }",
                        style =
                            MaterialTheme.typography.bodySmall,
                        color =
                            MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Text(
                        "Apply timing • ${
                            RuntimeStatus.get(
                                context,
                                "premium_apply_timing",
                                "Waiting"
                            )
                        }",
                        style =
                            MaterialTheme.typography.bodySmall,
                    )

                    Text(
                        "Watchdog age • ${
                            RuntimeStatus.get(
                                context,
                                "premium_watchdog_age",
                                "Waiting"
                            )
                        } • last change ${
                            RuntimeStatus.get(
                                context,
                                "premium_last_change_age",
                                "Waiting"
                            )
                        }",
                        style =
                            MaterialTheme.typography.bodySmall,
                        color =
                            MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Text(
                        "Library • ${
                            RuntimeStatus.get(
                                context,
                                "premium_library",
                                "Waiting"
                            )
                        }",
                        style =
                            MaterialTheme.typography.bodySmall,
                    )

                    Text(
                        "Recovery • ${
                            RuntimeStatus.get(
                                context,
                                "premium_recovery",
                                "Waiting"
                            )
                        }",
                        style =
                            MaterialTheme.typography.bodySmall,
                        color =
                            MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement =
                            Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedButton(
                            onClick = {
                                bb.pix.wall.engine.EngineExecutors.io {
                                    bb.pix.wall.engine
                                        .PremiumIntelligenceCenter
                                        .refresh(
                                            context.applicationContext,
                                            "app-refresh",
                                        )
                                }
                            },
                            modifier =
                                Modifier.weight(1f),
                        ) {
                            Text(
                                "Refresh",
                                maxLines = 1,
                            )
                        }

                        OutlinedButton(
                            onClick = {
                                bb.pix.wall.engine.EngineExecutors.io {
                                    bb.pix.wall.engine
                                        .PremiumIntelligenceCenter
                                        .reanalyzeCurrent(
                                            context.applicationContext
                                        )
                                }
                            },
                            modifier =
                                Modifier.weight(1f),
                        ) {
                            Text(
                                "Reanalyze",
                                maxLines = 1,
                            )
                        }
                    }

                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement =
                            Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedButton(
                            onClick = {
                                bb.pix.wall.engine.EngineExecutors.io {
                                    bb.pix.wall.engine
                                        .PremiumIntelligenceCenter
                                        .rebuildNext(
                                            context.applicationContext
                                        )
                                }
                            },
                            modifier =
                                Modifier.weight(1f),
                        ) {
                            Text(
                                "Rebuild Next",
                                maxLines = 1,
                            )
                        }

                        OutlinedButton(
                            onClick = {
                                bb.pix.wall.engine.EngineExecutors.io {
                                    bb.pix.wall.engine
                                        .PremiumIntelligenceCenter
                                        .cacheIntegrityAudit(
                                            context.applicationContext
                                        )
                                }
                            },
                            modifier =
                                Modifier.weight(1f),
                        ) {
                            Text(
                                "Cache audit",
                                maxLines = 1,
                            )
                        }
                    }

                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement =
                            Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedButton(
                            onClick = {
                                bb.pix.wall.engine.EngineExecutors.io {
                                    bb.pix.wall.engine
                                        .PremiumIntelligenceCenter
                                        .fullRepair(
                                            context.applicationContext
                                        )
                                }
                            },
                            modifier =
                                Modifier.weight(1f),
                        ) {
                            Text(
                                "Self-Heal",
                                maxLines = 1,
                            )
                        }

                        OutlinedButton(
                            onClick = {
                                bb.pix.wall.engine.EngineExecutors.io {
                                    bb.pix.wall.engine
                                        .PremiumIntelligenceCenter
                                        .exportReport(
                                            context.applicationContext
                                        )
                                }
                            },
                            modifier =
                                Modifier.weight(1f),
                        ) {
                            Text(
                                "Export report",
                                maxLines = 1,
                            )
                        }
                    }

                    Text(
                        "Recent intelligence timeline:\n${
                            RuntimeStatus.get(
                                context,
                                "premium_event_timeline",
                                "No events yet"
                            )
                        }",
                        style =
                            MaterialTheme.typography.bodySmall,
                        color =
                            MaterialTheme.colorScheme.onSurfaceVariant,
                    )

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

            }

CollapsibleSection(
                title = "Remote access",
                summary = "LAN • Dashboard",
            ) {
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

            }

CollapsibleSection(
                title = "Appearance",
                summary = "Theme • Display",
            ) {
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

            }

CollapsibleSection(
                title = "Quick Settings tiles",
                summary = "Next • Save • Blur • Remote",
            ) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    TileInfo(Icons.Outlined.SkipNext, "Next Wall", "Apply the queued wallpaper using the selected target mode.")
                    TileInfo(Icons.Outlined.SaveAlt, "Save Wall", "Save current Home/Lock wallpaper into /sdcard/wallpaper/saved/.")
                    TileInfo(Icons.Outlined.BlurOn, "Blur Wall", "Toggle configured Home/Lock blur values.")
                    TileInfo(Icons.Outlined.Language, "BB-Remote", "Open the BB-PixWall Wi-Fi dashboard in the browser.")
                    Text("Add these from Android Quick Settings → Edit tiles.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            }

CollapsibleSection(
                title = "Diagnostics",
                summary = "Health • Runtime",
            ) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text("Engine: ${settings.engineMode.label}", fontWeight = FontWeight.SemiBold)
                    Text("Storage: ${if (storageGranted) "Granted" else "Needs all-files access"}")
                    Text("Cache: ${bb.pix.wall.engine.WallpaperController.cacheCount()} files • ${bb.pix.wall.engine.WallpaperController.cacheBytes() / (1024 * 1024)} MB")
                    Text("Cache pools: ${bb.pix.wall.engine.WallpaperController.cacheBreakdown()}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Automation: ${if (settings.autoChange) settings.triggerMode.label else "Off"}")
                    Text("Engine health: ${RuntimeStatus.get(context, "engine_health", "Waiting for audit")}")
                    Text(
                        "Phase-1 smart policy: ${
                            RuntimeStatus.get(
                                context,
                                "resource_policy",
                                "Waiting"
                            )
                        }",
                        style =
                            MaterialTheme
                                .typography
                                .bodySmall,
                        color =
                            MaterialTheme
                                .colorScheme
                                .onSurfaceVariant,
                    )

                    Text(
                        "Mood profile: ${
                            RuntimeStatus.get(
                                context,
                                "mood_profile_label",
                                "Waiting"
                            )
                        }",
                        style =
                            MaterialTheme
                                .typography
                                .bodySmall,
                        color =
                            MaterialTheme
                                .colorScheme
                                .onSurfaceVariant,
                    )

                    Text(
                        "Blocked cache purged: ${
                            RuntimeStatus.get(
                                context,
                                "blocked_cache_purge",
                                "0"
                            )
                        }",
                        style =
                            MaterialTheme
                                .typography
                                .bodySmall,
                        color =
                            MaterialTheme
                                .colorScheme
                                .onSurfaceVariant,
                    )
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






            }

CollapsibleSection(
                title = "Backup & Restore",
                summary = "Settings • Learning",
            ) {
            Card(
                Modifier.fillMaxWidth()
            ) {
                Column(
                    Modifier.padding(18.dp),
                    verticalArrangement =
                        Arrangement.spacedBy(12.dp),
                ) {
                    val localBackupStatus =
                        androidx.compose.runtime.remember {
                            androidx.compose.runtime.mutableStateOf(
                                SettingsBackup.latestSummary()
                            )
                        }

                    SettingHeader(
                        Icons.Outlined.Backup,
                        "Backup & Restore"
                    )

                    Text(
                        "Portable local backup keeps your settings and learned preferences. Cache images and temporary runtime files are excluded.",
                        style =
                            MaterialTheme.typography.bodySmall,
                        color =
                            MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Button(
                        onClick = {
                            val result =
                                SettingsBackup.create(
                                    context,
                                    includeLearning = true,
                                )

                            localBackupStatus.value =
                                if (result.success) {
                                    SettingsBackup.latestSummary()
                                } else {
                                    result.message
                                }

                            android.widget.Toast
                                .makeText(
                                    context,
                                    result.message,
                                    android.widget.Toast.LENGTH_LONG,
                                )
                                .show()
                        },
                        modifier =
                            Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            "Backup settings + learning"
                        )
                    }

                    Button(
                        onClick = {
                            val validation =
                                SettingsBackup.validateLatest(
                                    context
                                )

                            if (!validation.success) {
                                localBackupStatus.value =
                                    validation.message

                                android.widget.Toast
                                    .makeText(
                                        context,
                                        validation.message,
                                        android.widget.Toast.LENGTH_LONG,
                                    )
                                    .show()
                            } else {
                                val result =
                                    SettingsBackup.restoreLatest(
                                        context
                                    )

                                localBackupStatus.value =
                                    if (result.success) {
                                        "Restore complete • " +
                                            SettingsBackup.latestSummary()
                                    } else {
                                        result.message
                                    }

                                android.widget.Toast
                                    .makeText(
                                        context,
                                        result.message,
                                        android.widget.Toast.LENGTH_LONG,
                                    )
                                    .show()

                                if (result.success) {
                                    (
                                        context as?
                                            android.app.Activity
                                    )?.recreate()
                                }
                            }
                        },
                        modifier =
                            Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            "Restore latest backup"
                        )
                    }

                    Text(
                        localBackupStatus.value,
                        style =
                            MaterialTheme.typography.bodySmall,
                        color =
                            MaterialTheme.colorScheme.primary,
                    )

                    Text(
                        "Backup location: /sdcard/wallpaper/backup/settings/",
                        style =
                            MaterialTheme.typography.bodySmall,
                        color =
                            MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            }



            ThinkingSweepLine(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(
                            horizontal =
                                28.dp
                        )
            )

            DeveloperIdentityCard(
                context = context,
                theme = selectedTheme,
                youtubeUrl = "",
                instagramUrl = "",
                telegramUrl = "https://t.me/BharatBhat",
            )

}
}
}
}

private data class PreviewSelection(
    val label: String,
    val path: String?,
    val home: Boolean,
    val current: Boolean,
)

@Composable
private fun PreviewGrid(
    state: WallpaperState,
    refresh: () -> Unit,
    prepare: () -> Unit,
) {
    val context =
        LocalContext.current

    var selected by
        remember {
            mutableStateOf<PreviewSelection?>(
                null
            )
        }

    var showHistory by
        remember {
            mutableStateOf(false)
        }

    var libraryTick by
        remember {
            mutableStateOf(0)
        }

    fun postUi(
        block: () -> Unit,
    ) {
        android.os.Handler(
            android.os.Looper.getMainLooper()
        ).post(block)
    }

    fun toast(
        message: String,
    ) {
        android.widget.Toast.makeText(
            context,
            message,
            android.widget.Toast.LENGTH_SHORT,
        ).show()
    }

    /*
     * ========================================================
     * HISTORY
     * ========================================================
     */
    if (showHistory) {
        val history =
            remember(
                showHistory,
                libraryTick,
            ) {
                bb.pix.wall.engine
                    .WallpaperLibrary
                    .history()
            }

        AlertDialog(
            onDismissRequest = {
                showHistory = false
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showHistory = false
                    }
                ) {
                    Text("Close")
                }
            },
            title = {
                Text(
                    "Wallpaper History"
                )
            },
            text = {
                if (history.isEmpty()) {
                    Text(
                        "No wallpaper history yet."
                    )
                } else {
                    Column(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .heightIn(
                                    max = 430.dp
                                )
                                .verticalScroll(
                                    rememberScrollState()
                                ),
                        verticalArrangement =
                            Arrangement.spacedBy(
                                8.dp
                            ),
                    ) {
                        history.take(40)
                            .forEach { entry ->
                                val date =
                                    remember(
                                        entry.createdAt
                                    ) {
                                        java.text
                                            .SimpleDateFormat(
                                                "dd MMM • hh:mm a",
                                                java.util.Locale
                                                    .getDefault(),
                                            )
                                            .format(
                                                java.util.Date(
                                                    entry.createdAt
                                                )
                                            )
                                    }

                                Card(
                                    modifier =
                                        Modifier
                                            .fillMaxWidth()
                                ) {
                                    Row(
                                        modifier =
                                            Modifier
                                                .fillMaxWidth()
                                                .padding(
                                                    12.dp
                                                ),
                                        verticalAlignment =
                                            Alignment
                                                .CenterVertically,
                                    ) {
                                        Column(
                                            modifier =
                                                Modifier
                                                    .weight(
                                                        1f
                                                    )
                                        ) {
                                            Text(
                                                date,
                                                fontWeight =
                                                    FontWeight
                                                        .SemiBold,
                                            )

                                            Text(
                                                buildString {
                                                    if (
                                                        entry.home !=
                                                        null
                                                    ) {
                                                        append(
                                                            "Home"
                                                        )
                                                    }

                                                    if (
                                                        entry.lock !=
                                                        null
                                                    ) {
                                                        if (
                                                            isNotEmpty()
                                                        ) {
                                                            append(
                                                                " + "
                                                            )
                                                        }

                                                        append(
                                                            "Lock"
                                                        )
                                                    }
                                                },
                                                style =
                                                    MaterialTheme
                                                        .typography
                                                        .bodySmall,
                                                color =
                                                    MaterialTheme
                                                        .colorScheme
                                                        .onSurfaceVariant,
                                            )
                                        }

                                        TextButton(
                                            onClick = {
                                                bb.pix.wall.engine
                                                    .EngineExecutors
                                                    .io {
                                                        val ok =
                                                            runCatching {
                                                                WallpaperController
                                                                    .restoreHistory(
                                                                        context.applicationContext,
                                                                        entry,
                                                                    )
                                                            }.getOrDefault(
                                                                false
                                                            )

                                                        postUi {
                                                            if (
                                                                ok
                                                            ) {
                                                                libraryTick++
                                                                refresh()
                                                                showHistory =
                                                                    false
                                                                toast(
                                                                    "History restored"
                                                                )
                                                            } else {
                                                                toast(
                                                                    "Restore failed"
                                                                )
                                                            }
                                                        }
                                                    }
                                            }
                                        ) {
                                            Text(
                                                "Restore"
                                            )
                                        }
                                    }
                                }
                            }
                    }
                }
            },
        )
    }

    /*
     * ========================================================
     * SELECTED WALLPAPER ACTION SHEET
     * ========================================================
     */
    selected?.let { item ->
        val bmp =
            rememberPreviewBitmap(
                item.path
            )

        val file =
            remember(
                item.path,
                libraryTick,
            ) {
                item.path
                    ?.let {
                        java.io.File(it)
                    }
            }

        val info =
            remember(
                item.path,
                libraryTick,
            ) {
                WallpaperController
                    .displayWallpaperInfo(
                        item.path
                    )
            }

        val favorite =
            file?.let {
                bb.pix.wall.engine
                    .WallpaperLibrary
                    .isFavorite(it)
            } ?: false

        val pinned =
            file?.let {
                bb.pix.wall.engine
                    .WallpaperLibrary
                    .isPinned(it)
            } ?: false

        AlertDialog(
            onDismissRequest = {
                selected = null
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        selected = null
                    }
                ) {
                    Text("Close")
                }
            },
            title = {
                Text(item.label)
            },
            text = {
                Column(
                    verticalArrangement =
                        Arrangement.spacedBy(
                            10.dp
                        )
                ) {
                    if (bmp != null) {
                        Image(
                            bmp.asImageBitmap(),
                            item.label,
                            Modifier
                                .fillMaxWidth()
                                .aspectRatio(
                                    .62f
                                )
                                .clip(
                                    RoundedCornerShape(
                                        18.dp
                                    )
                                ),
                            contentScale =
                                ContentScale.Crop,
                        )
                    } else {
                        Text(
                            "Preview unavailable"
                        )
                    }

                    Text(
                        "${info.resolution} • " +
                            "${info.sizeLabel} • " +
                            "${info.format}"
                    )

                    Text(
                        "${info.source} • " +
                            "Quality ${info.quality}",
                        style =
                            MaterialTheme
                                .typography
                                .bodySmall,
                        color =
                            MaterialTheme
                                .colorScheme
                                .onSurfaceVariant,
                    )

                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth(),
                        horizontalArrangement =
                            Arrangement
                                .spacedBy(
                                    8.dp
                                ),
                    ) {
                        TextButton(
                            modifier =
                                Modifier
                                    .weight(
                                        1f
                                    ),
                            enabled =
                                file != null,
                            onClick = {
                                val f =
                                    file
                                        ?: return@TextButton

                                val changed =
                                    if (
                                        favorite
                                    ) {
                                        bb.pix.wall.engine
                                            .WallpaperLibrary
                                            .removeFavorite(
                                                f
                                            )
                                    } else {
                                        bb.pix.wall.engine
                                            .WallpaperLibrary
                                            .favorite(
                                                f
                                            ) != null
                                    }

                                if (changed) {
                                    libraryTick++
                                    toast(
                                        if (
                                            favorite
                                        ) {
                                            "Removed from Favorites"
                                        } else {
                                            "Added to Favorites"
                                        }
                                    )
                                }
                            }
                        ) {
                            Text(
                                if (favorite) {
                                    "♥ Favorite"
                                } else {
                                    "♡ Favorite"
                                }
                            )
                        }

                        TextButton(
                            modifier =
                                Modifier
                                    .weight(
                                        1f
                                    ),
                            enabled =
                                file != null,
                            onClick = {
                                val f =
                                    file
                                        ?: return@TextButton

                                val ok =
                                    bb.pix.wall.engine
                                        .WallpaperLibrary
                                        .setPinned(
                                            f,
                                            !pinned,
                                        )

                                if (ok) {
                                    libraryTick++
                                    toast(
                                        if (
                                            pinned
                                        ) {
                                            "Unpinned"
                                        } else {
                                            "Pinned"
                                        }
                                    )
                                }
                            }
                        ) {
                            Text(
                                if (pinned) {
                                    "Unpin"
                                } else {
                                    "Pin"
                                }
                            )
                        }
                    }

                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth(),
                        horizontalArrangement =
                            Arrangement
                                .spacedBy(
                                    8.dp
                                ),
                    ) {
                        TextButton(
                            modifier =
                                Modifier
                                    .weight(
                                        1f
                                    ),
                            enabled =
                                file != null,
                            onClick = {
                                val f =
                                    file
                                        ?: return@TextButton

                                val saved =
                                    bb.pix.wall.engine
                                        .WallpaperLibrary
                                        .saveOriginal(
                                            f,
                                            item.label,
                                        )

                                toast(
                                    if (
                                        saved != null
                                    ) {
                                        "Original saved"
                                    } else {
                                        "Save failed"
                                    }
                                )
                            }
                        ) {
                            Text("Save")
                        }

                        TextButton(
                            modifier =
                                Modifier
                                    .weight(
                                        1f
                                    ),
                            enabled =
                                file != null,
                            onClick = {
                                val f =
                                    file
                                        ?: return@TextButton

                                val blocked =
                                    bb.pix.wall.engine
                                        .WallpaperLibrary
                                        .neverShowAgain(
                                            f
                                        )

                                if (blocked) {
                                    WallpaperController
                                        .purgeBlockedCached(
                                            context.applicationContext
                                        )

                                    /*
                                     * Current wallpaper stays visible.
                                     * A prepared Next wallpaper can be
                                     * discarded immediately and replaced.
                                     */
                                    if (
                                        !item.current
                                    ) {
                                        f.delete()

                                        java.io.File(
                                            f.absolutePath +
                                                ".meta"
                                        ).delete()

                                        bb.pix.wall.engine
                                            .EngineExecutors
                                            .io {
                                                runCatching {
                                                    WallpaperController
                                                        .ensureNext(
                                                            context.applicationContext
                                                        )
                                                }

                                                postUi {
                                                    refresh()
                                                }
                                            }
                                    }

                                    libraryTick++
                                    toast(
                                        "Never Show Again added"
                                    )
                                } else {
                                    toast(
                                        "Unable to block wallpaper"
                                    )
                                }
                            }
                        ) {
                            Text(
                                "Never Again"
                            )
                        }
                    }
                }
            },
        )
    }

    /*
     * ========================================================
     * MAIN PREVIEW GRID
     * ========================================================
     */
    Column(
        verticalArrangement =
            Arrangement.spacedBy(
                10.dp
            )
    ) {
        Text(
            "Wallpaper preview",
            style =
                MaterialTheme
                    .typography
                    .titleLarge,
        )

        Row(
            modifier =
                Modifier
                    .fillMaxWidth(),
            horizontalArrangement =
                Arrangement.spacedBy(
                    6.dp
                ),
        ) {
            TextButton(
                modifier =
                    Modifier.weight(
                        1f
                    ),
                onClick = {
                    showHistory = true
                }
            ) {
                Text("History")
            }

            TextButton(
                modifier =
                    Modifier.weight(
                        1f
                    ),
                onClick = {
                    bb.pix.wall.engine
                        .EngineExecutors
                        .io {
                            val settings =
                                SettingsStore(
                                    context.applicationContext
                                ).load()

                            bb.pix.wall.engine
                                .WallpaperSourceEngine
                                .invalidateCloudIndex()

                            WallpaperController
                                .invalidateQueue()

                            val ok =
                                runCatching {
                                    WallpaperController
                                        .ensureNext(
                                            context.applicationContext,
                                            settings,
                                            allowNetwork = true,
                                        )
                                }.getOrDefault(
                                    false
                                )

                            /*
                             * Refill predictive cache only after
                             * the visible Next pair is ready.
                             */
                            bb.pix.wall.engine
                                .EngineExecutors
                                .io {
                                    runCatching {
                                        WallpaperController
                                            .primeCache(
                                                context.applicationContext,
                                                settings,
                                            )
                                    }
                                }

                            postUi {
                                refresh()

                                toast(
                                    if (ok) {
                                        "Next refreshed"
                                    } else {
                                        "Next refresh queued"
                                    }
                                )
                            }
                        }
                }
            ) {
                Text("Refresh Next")
            }

            TextButton(
                modifier =
                    Modifier.weight(
                        1f
                    ),
                onClick = {
                    bb.pix.wall.engine
                        .EngineExecutors
                        .io {
                            /*
                             * Force a fresh choice, but still use
                             * ready local/predictive cache first.
                             */
                            WallpaperController
                                .invalidateQueue()

                            val changed =
                                runCatching {
                                    WallpaperController
                                        .nextWall(
                                            context.applicationContext,
                                            allowNetwork = true,
                                            userInitiated = true,
                                        )
                                }.getOrDefault(
                                    false
                                )

                            postUi {
                                refresh()

                                toast(
                                    if (changed) {
                                        "Surprise applied"
                                    } else {
                                        "Preparing surprise"
                                    }
                                )
                            }
                        }
                }
            ) {
                Text("Surprise")
            }
        }

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement =
                Arrangement.spacedBy(
                    10.dp
                ),
        ) {
            WallpaperThumb(
                "Current Home",
                state.currentHome,
                true,
                true,
                Modifier.weight(1f),
            ) {
                selected =
                    PreviewSelection(
                        label =
                            "Current Home",
                        path =
                            state.currentHome,
                        home = true,
                        current = true,
                    )
            }

            WallpaperThumb(
                "Current Lock",
                state.currentLock,
                false,
                true,
                Modifier.weight(1f),
            ) {
                selected =
                    PreviewSelection(
                        label =
                            "Current Lock",
                        path =
                            state.currentLock,
                        home = false,
                        current = true,
                    )
            }
        }

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement =
                Arrangement.spacedBy(
                    10.dp
                ),
        ) {
            WallpaperThumb(
                "Next Home",
                state.nextHome,
                true,
                false,
                Modifier.weight(1f),
            ) {
                selected =
                    PreviewSelection(
                        label =
                            "Next Home",
                        path =
                            state.nextHome,
                        home = true,
                        current = false,
                    )
            }

            WallpaperThumb(
                "Next Lock",
                state.nextLock,
                false,
                false,
                Modifier.weight(1f),
            ) {
                selected =
                    PreviewSelection(
                        label =
                            "Next Lock",
                        path =
                            state.nextLock,
                        home = false,
                        current = false,
                    )
            }
        }
    }
}

@Composable
private fun WallpaperThumb(
    label: String,
    path: String?,
    home: Boolean,
    allowBlur: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val context =
        LocalContext.current

    val bitmap =
        rememberWallpaperPreviewBitmap(
            path = path,
            home = home,
            allowBlur = allowBlur,
        )

    val settings =
        bb.pix.wall.settings.SettingsStore(
            context
        ).load()

    val blurMaster =
        WallpaperController
            .blurMasterEnabled(context)

    val blurEnabled =
        allowBlur &&
        blurMaster &&
            if (home) {
                settings.homeBlurEnabled &&
                    settings.homeBlurRadius > 0
            } else {
                settings.lockBlurEnabled &&
                    settings.lockBlurRadius > 0
            }

    val blurRadius =
        if (home) {
            settings.homeBlurRadius
        } else {
            settings.lockBlurRadius
        }

    val info =
        WallpaperController.displayWallpaperInfo(
            path
        )

    Card(
        modifier =
            modifier.clickable(
                enabled = path != null,
                onClick = onClick,
            ),
        shape =
            RoundedCornerShape(18.dp),
    ) {
        Column {
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(.95f)
                    .background(
                        MaterialTheme
                            .colorScheme
                            .surfaceVariant
                    )
            ) {
                if (bitmap != null) {
                    Image(
                        bitmap.asImageBitmap(),
                        label,
                        Modifier
                            .fillMaxSize(),
                        contentScale =
                            ContentScale.Crop,
                    )
                } else {
                    Icon(
                        Icons.Outlined
                            .ImageNotSupported,
                        null,
                        Modifier
                            .align(
                                Alignment.Center
                            )
                            .size(34.dp),
                        tint =
                            MaterialTheme
                                .colorScheme
                                .onSurfaceVariant,
                    )
                }
            }

            Column(
                Modifier.padding(
                    horizontal = 10.dp,
                    vertical = 8.dp,
                )
            ) {
                Text(
                    label,
                    style =
                        MaterialTheme
                            .typography
                            .labelMedium,
                )

                Text(
                    info.source,
                    style =
                        MaterialTheme
                            .typography
                            .labelSmall,
                    color =
                        MaterialTheme
                            .colorScheme
                            .primary,
                )

                Text(
                    if (info.exists) {
                        "${info.resolution} • " +
                            "${info.format} • " +
                            info.sizeLabel
                    } else {
                        "Unavailable"
                    },
                    style =
                        MaterialTheme
                            .typography
                            .labelSmall,
                    color =
                        MaterialTheme
                            .colorScheme
                            .onSurfaceVariant,
                )

                if (info.exists) {
                    Text(
                        (
                            if (blurEnabled) {
                                "Blur ON • ${blurRadius}px"
                            } else {
                                "Blur OFF"
                            }
                        ) +
                            " • Quality ${info.quality}",
                        style =
                            MaterialTheme
                                .typography
                                .labelSmall,
                        color =
                            MaterialTheme
                                .colorScheme
                                .onSurfaceVariant,
                    )
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

@Composable
private fun PremiumAppHeader(
    engineMode: EngineMode,
    theme: ThemeProfile,
) {
    val context = LocalContext.current
    val tokens = LocalDesignTokens.current

    val version =
        remember {
            runCatching {
                context.packageManager
                    .getPackageInfo(
                        context.packageName,
                        0,
                    )
                    .versionName
                    ?: "dev"
            }.getOrDefault("dev")
        }

    val transition =
        rememberInfiniteTransition(
            label = "bb_header_glow"
        )

    val glow by transition.animateFloat(
        initialValue = .18f,
        targetValue = .85f,
        animationSpec =
            infiniteRepeatable(
                animation =
                    tween(
                        durationMillis =
                            tokens.glowSweepMs,
                        easing = tokens.easing,
                    ),
                repeatMode =
                    RepeatMode.Reverse,
            ),
        label = "header_glow",
    )

    val accent =
        when (theme) {
            ThemeProfile.SIGNATURE ->
                MaterialTheme.colorScheme.primary

            ThemeProfile.CINEMATIC ->
                MaterialTheme.colorScheme.tertiary

            ThemeProfile.CYBER ->
                MaterialTheme.colorScheme.primary

            ThemeProfile.LUXE ->
                MaterialTheme.colorScheme.secondary

            ThemeProfile.MATERIAL_PRO ->
                MaterialTheme.colorScheme.primary
        }

    Surface(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
        shape =
            RoundedCornerShape(
                tokens.cardRadius
            ),
        color =
            MaterialTheme.colorScheme
                .surface
                .copy(alpha = .92f),
        border =
            androidx.compose.foundation.BorderStroke(
                tokens.borderWidth,
                accent.copy(
                    alpha =
                        if (tokens.glowEnabled) {
                            .18f + glow * .42f
                        } else {
                            .16f
                        }
                ),
            ),
    ) {
        Column(
            modifier =
                Modifier.padding(
                    horizontal = 12.dp,
                    vertical = 9.dp,
                ),
            verticalArrangement =
                Arrangement.spacedBy(3.dp),
        ) {
            Row(
                modifier =
                    Modifier.fillMaxWidth(),
                verticalAlignment =
                    Alignment.CenterVertically,
            ) {
                Text(
                    "BB-PixWall",
                    style =
                        MaterialTheme.typography
                            .headlineLarge,
                    modifier =
                        Modifier.weight(1f),
                )

                Surface(
                    shape =
                        RoundedCornerShape(999.dp),
                    color =
                        MaterialTheme.colorScheme
                            .surfaceVariant,
                ) {
                    Text(
                        "v$version",
                        modifier =
                            Modifier.padding(
                                horizontal = 8.dp,
                                vertical = 4.dp,
                            ),
                        style =
                            MaterialTheme.typography
                                .labelSmall,
                    )
                }
            }

            Row(
                verticalAlignment =
                    Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(6.dp)
                        .clip(
                            RoundedCornerShape(
                                99.dp
                            )
                        )
                        .background(
                            if (
                                engineMode ==
                                EngineMode.ADVANCED
                            ) {
                                accent
                            } else {
                                MaterialTheme
                                    .colorScheme
                                    .onSurfaceVariant
                            }
                        )
                )

                Spacer(
                    Modifier.width(6.dp)
                )

                Text(
                    engineMode.label,
                    style =
                        MaterialTheme.typography
                            .labelMedium,
                    color =
                        MaterialTheme.colorScheme
                            .onSurfaceVariant,
                )
            }
        }
    }
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
        ThemeProfile.CINEMATIC -> Brush.linearGradient(listOf(c.background, c.background))
        ThemeProfile.CYBER -> Brush.verticalGradient(listOf(c.background, c.primary.copy(alpha = .10f), c.tertiary.copy(alpha = .07f), c.background))
        ThemeProfile.LUXE -> Brush.verticalGradient(listOf(c.background, c.secondary.copy(alpha = .045f), c.background))
        ThemeProfile.MATERIAL_PRO -> Brush.verticalGradient(listOf(c.background, c.primary.copy(alpha = .08f), c.secondary.copy(alpha = .06f)))
    }
}
@Composable
private fun rememberWallpaperPreviewBitmap(
    path: String?,
    home: Boolean,
    allowBlur: Boolean,
): android.graphics.Bitmap? {
    val context = LocalContext.current

    val settings =
        bb.pix.wall.settings.SettingsStore(context)
            .load()

    val blurMaster =
        WallpaperController.blurMasterEnabled(context)

    val blurEnabled =
        allowBlur &&
            blurMaster &&
            if (home) {
                settings.homeBlurEnabled &&
                    settings.homeBlurRadius > 0
            } else {
                settings.lockBlurEnabled &&
                    settings.lockBlurRadius > 0
            }

    val blurRadius =
        if (home) {
            settings.homeBlurRadius
        } else {
            settings.lockBlurRadius
        }

    /*
     * Include effective blur state + radius in the key.
     * The wallpaper path itself does not change when Blur Wall toggles,
     * so path-only produceState would otherwise keep the stale bitmap.
     */
    val previewKey =
        "$path|home=$home|allow=$allowBlur|blur=$blurEnabled|radius=$blurRadius"

    val state =
        produceState<android.graphics.Bitmap?>(
            initialValue = null,
            key1 = previewKey,
        ) {
            value =
                if (path.isNullOrBlank()) {
                    null
                } else {
                    withContext(Dispatchers.IO) {
                        val source =
                            java.io.File(path)

                        val preview =
                            if (blurEnabled) {
                                WallpaperController.previewFile(
                                    context.applicationContext,
                                    source,
                                    home,
                                )
                            } else {
                                source
                            }

                        decodePreview(
                            preview.absolutePath
                        )
                    }
                }
        }

    return state.value
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
