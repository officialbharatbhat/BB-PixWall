package bb.pix.wall.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import bb.pix.wall.engine.SafeWall
import bb.pix.wall.engine.LiteUpdateChecker
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Environment
import android.provider.Settings
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Send
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.ui.text.input.ImeAction
import bb.pix.wall.BuildConfig
import bb.pix.wall.R
import bb.pix.wall.engine.RuntimeStatus
import bb.pix.wall.engine.WallpaperFiles
import bb.pix.wall.network.LanInfo
import bb.pix.wall.settings.AppSettings
import bb.pix.wall.settings.AppearanceMode
import bb.pix.wall.settings.EngineMode
import bb.pix.wall.settings.TriggerMode
import bb.pix.wall.settings.WallpaperOrder
import bb.pix.wall.settings.WallpaperTargetMode
import bb.pix.wall.ui.theme.ThemeProfile
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun LiteHomeScreen(
    settings: AppSettings,
    refreshKey: Int,
    onSettingsChange: (AppSettings) -> Unit,
    onRequestAdvanced: () -> Unit,
) {
    val context = LocalContext.current
    val storageGranted =
        remember(refreshKey) {
            Environment.isExternalStorageManager()
        }

    var updateStatus by remember { mutableStateOf("Check for new Lite releases manually") }
    var updateUrl by remember { mutableStateOf("") }
    var safeSelectionVersion by remember { mutableIntStateOf(0) }
    var safeActive by remember(refreshKey, safeSelectionVersion) { mutableStateOf(SafeWall.active(context)) }
    val pickSafeHome = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null && SafeWall.importImage(context, uri, true)) safeSelectionVersion++
    }
    val pickSafeLock = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null && SafeWall.importImage(context, uri, false)) safeSelectionVersion++
    }
    @Suppress("UNUSED_VARIABLE") val selectionState = safeSelectionVersion

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement =
                Arrangement.spacedBy(10.dp),
        ) {
            LiteHeader(
                settings = settings,
                onRequestAdvanced = onRequestAdvanced,
            )

            LiteCurrentPreviewStrip(
                refreshKey = refreshKey,
            )

            LiteSection(
                title = "Safe Wall",
                subtitle = "Choose two original wallpapers • privacy pause",
            ) {
                Text(
                    if (safeActive) "Safe Wall ACTIVE • wallpaper rotation paused"
                    else if (SafeWall.configured(context)) "Both safe wallpapers ready"
                    else "Select Safe Home and Safe Lock wallpapers before using the QS tile.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { pickSafeHome.launch("image/*") },
                        enabled = !safeActive,
                        modifier = Modifier.weight(1f),
                    ) { Text("Choose Home") }
                    OutlinedButton(
                        onClick = { pickSafeLock.launch("image/*") },
                        enabled = !safeActive,
                        modifier = Modifier.weight(1f),
                    ) { Text("Choose Lock") }
                }
                OutlinedButton(onClick = {
                    bb.pix.wall.engine.EngineExecutors.io {
                        SafeWall.toggle(context.applicationContext)
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            safeActive = SafeWall.active(context)
                        }
                    }
                }) {
                    Text(if (safeActive) "Restore normal wallpaper" else "Activate Safe Wall")
                }
                Text(
                    "Safe originals stay outside history/cache cleanup. Use Safe Wall tile for quick toggle.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }


            LiteSection(
                title = "Sources",
                subtitle = "Google Photos primary • Drive mirror • Local optional",
            ) {
                LiteSourceField(
                    label = "Google Photos",
                    sourceKey = "photos",
                    noun = "photos",
                    value = settings.photosAlbumUrl,
                    onValueChange = {
                        onSettingsChange(
                            settings.copy(
                                photosAlbumUrl = it.trim()
                            )
                        )
                    },
                )

                LiteSourceField(
                    label = "Google Drive",
                    sourceKey = "drive",
                    noun = "files",
                    value = settings.driveFolderUrl,
                    onValueChange = {
                        onSettingsChange(
                            settings.copy(
                                driveFolderUrl = it.trim()
                            )
                        )
                    },
                )

                Row(
                    modifier =
                        Modifier.fillMaxWidth(),
                    verticalAlignment =
                        Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Outlined.Folder,
                        null,
                        Modifier.size(18.dp),
                    )
                    Spacer(Modifier.size(8.dp))
                    Column(
                        modifier =
                            Modifier.weight(1f)
                    ) {
                        Text(
                            "Local",
                            fontWeight =
                                FontWeight.SemiBold,
                        )
                        Text(
                            WallpaperFiles.local.absolutePath,
                            style =
                                MaterialTheme.typography
                                    .bodySmall,
                            color =
                                MaterialTheme.colorScheme
                                    .onSurfaceVariant,
                        )
                    }
                    OutlinedButton(
                        onClick = {
                            runCatching {
                                context.startActivity(
                                    Intent(
                                        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                        Uri.parse(
                                            "package:${context.packageName}"
                                        ),
                                    )
                                )
                            }
                        }
                    ) {
                        Text(
                            if (storageGranted) {
                                "Granted"
                            } else {
                                "Grant"
                            }
                        )
                    }
                }
            }

            LiteSection(
                title = "Trigger",
                subtitle = "Automatic wallpaper change",
                trailing = {
                    Switch(
                        checked =
                            settings.autoChange,
                        onCheckedChange = {
                            onSettingsChange(
                                settings.copy(
                                    autoChange = it
                                )
                            )
                        },
                    )
                },
            ) {
                Row(
                    modifier =
                        Modifier.fillMaxWidth(),
                    horizontalArrangement =
                        Arrangement.spacedBy(8.dp),
                ) {
                    LiteChoice(
                        label = "Interval",
                        selected =
                            settings.triggerMode ==
                                TriggerMode.INTERVAL,
                        modifier =
                            Modifier.weight(1f),
                    ) {
                        onSettingsChange(
                            settings.copy(
                                triggerMode =
                                    TriggerMode.INTERVAL
                            )
                        )
                    }

                    LiteChoice(
                        label = "Screen Lock",
                        selected =
                            settings.triggerMode ==
                                TriggerMode.SCREEN_OFF,
                        modifier =
                            Modifier.weight(1f),
                    ) {
                        onSettingsChange(
                            settings.copy(
                                triggerMode =
                                    TriggerMode.SCREEN_OFF
                            )
                        )
                    }

                    LiteChoice(
                        label = "Screen Unlock",
                        selected =
                            settings.triggerMode ==
                                TriggerMode.SCREEN_ON,
                        modifier =
                            Modifier.weight(1f),
                    ) {
                        onSettingsChange(
                            settings.copy(
                                triggerMode =
                                    TriggerMode.SCREEN_ON
                            )
                        )
                    }
                }

                if (
                    settings.triggerMode ==
                    TriggerMode.INTERVAL
                ) {
                    val mins =
                        settings.intervalMinutes
                            .coerceIn(1, 300)

                    Text(
                        if (mins < 60) {
                            "$mins min"
                        } else {
                            val h = mins / 60
                            val m = mins % 60
                            if (m == 0) {
                                "$h hr"
                            } else {
                                "$h hr $m min"
                            }
                        },
                        style =
                            MaterialTheme.typography
                                .labelMedium,
                    )

                    Slider(
                        value = mins.toFloat(),
                        onValueChange = {
                            onSettingsChange(
                                settings.copy(
                                    intervalMinutes =
                                        it.roundToInt()
                                            .coerceIn(
                                                1,
                                                300,
                                            )
                                )
                            )
                        },
                        valueRange = 1f..300f,
                    )
                }
            }

            LiteSection(
                title = "Target",
                subtitle = "Where wallpaper should change",
            ) {
                Row(
                    modifier =
                        Modifier.fillMaxWidth(),
                    horizontalArrangement =
                        Arrangement.spacedBy(8.dp),
                ) {
                    LiteChoice(
                        "Home",
                        settings.targetMode ==
                            WallpaperTargetMode.HOME,
                        Modifier.weight(1f),
                    ) {
                        onSettingsChange(
                            settings.copy(
                                targetMode =
                                    WallpaperTargetMode.HOME
                            )
                        )
                    }
                    LiteChoice(
                        "Lock",
                        settings.targetMode ==
                            WallpaperTargetMode.LOCK,
                        Modifier.weight(1f),
                    ) {
                        onSettingsChange(
                            settings.copy(
                                targetMode =
                                    WallpaperTargetMode.LOCK
                            )
                        )
                    }
                }

                Row(
                    modifier =
                        Modifier.fillMaxWidth(),
                    horizontalArrangement =
                        Arrangement.spacedBy(8.dp),
                ) {
                    LiteChoice(
                        "Both",
                        settings.targetMode ==
                            WallpaperTargetMode.BOTH_SAME,
                        Modifier.weight(1f),
                    ) {
                        onSettingsChange(
                            settings.copy(
                                targetMode =
                                    WallpaperTargetMode.BOTH_SAME
                            )
                        )
                    }
                    LiteChoice(
                        "Home Lock Split",
                        settings.targetMode ==
                            WallpaperTargetMode.BOTH_DIFFERENT,
                        Modifier.weight(1f),
                    ) {
                        onSettingsChange(
                            settings.copy(
                                targetMode =
                                    WallpaperTargetMode.BOTH_DIFFERENT
                            )
                        )
                    }
                }
            }

            LiteSection(
                title = "Wallpaper Order",
                subtitle = "Simple source ordering only",
            ) {
                Row(
                    modifier =
                        Modifier.fillMaxWidth(),
                    horizontalArrangement =
                        Arrangement.spacedBy(8.dp),
                ) {
                    LiteChoice(
                        "A-Z",
                        settings.wallpaperOrder ==
                            WallpaperOrder.A_Z,
                        Modifier.weight(1f),
                    ) {
                        onSettingsChange(
                            settings.copy(
                                wallpaperOrder =
                                    WallpaperOrder.A_Z
                            )
                        )
                    }
                    LiteChoice(
                        "Size ↑",
                        settings.wallpaperOrder ==
                            WallpaperOrder.SIZE_LOW_HIGH,
                        Modifier.weight(1f),
                    ) {
                        onSettingsChange(
                            settings.copy(
                                wallpaperOrder =
                                    WallpaperOrder.SIZE_LOW_HIGH
                            )
                        )
                    }
                    LiteChoice(
                        "Size ↓",
                        settings.wallpaperOrder ==
                            WallpaperOrder.SIZE_HIGH_LOW,
                        Modifier.weight(1f),
                    ) {
                        onSettingsChange(
                            settings.copy(
                                wallpaperOrder =
                                    WallpaperOrder.SIZE_HIGH_LOW
                            )
                        )
                    }
                }

                Row(
                    modifier =
                        Modifier.fillMaxWidth(),
                    horizontalArrangement =
                        Arrangement.spacedBy(8.dp),
                ) {
                    LiteChoice(
                        "Random",
                        settings.wallpaperOrder ==
                            WallpaperOrder.RANDOM_SHUFFLE,
                        Modifier.weight(1f),
                    ) {
                        onSettingsChange(
                            settings.copy(
                                wallpaperOrder =
                                    WallpaperOrder.RANDOM_SHUFFLE
                            )
                        )
                    }
                    LiteChoice(
                        "Surprise",
                        settings.wallpaperOrder ==
                            WallpaperOrder.SURPRISE,
                        Modifier.weight(1f),
                    ) {
                        onSettingsChange(
                            settings.copy(
                                wallpaperOrder =
                                    WallpaperOrder.SURPRISE
                            )
                        )
                    }
                }
            }

            LiteSection(
                title = "Blur",
                subtitle = "Independent Home / Lock blur",
            ) {
                LiteBlurControl(
                    title = "Home screen blur",
                    enabled =
                        settings.homeBlurEnabled,
                    radius =
                        settings.homeBlurRadius,
                    onEnabled = {
                        onSettingsChange(
                            settings.copy(
                                homeBlurEnabled = it
                            )
                        )
                    },
                    onRadius = {
                        onSettingsChange(
                            settings.copy(
                                homeBlurRadius = it
                            )
                        )
                    },
                )

                HorizontalDivider()

                LiteBlurControl(
                    title = "Lock screen blur",
                    enabled =
                        settings.lockBlurEnabled,
                    radius =
                        settings.lockBlurRadius,
                    onEnabled = {
                        onSettingsChange(
                            settings.copy(
                                lockBlurEnabled = it
                            )
                        )
                    },
                    onRadius = {
                        onSettingsChange(
                            settings.copy(
                                lockBlurRadius = it
                            )
                        )
                    },
                )
            }

            LiteSection(
                title = "Remote Access",
                subtitle = "Same LAN/Web dashboard as BB-PixWall",
                trailing = {
                    Switch(
                        checked =
                            settings.lanEnabled,
                        onCheckedChange = {
                            onSettingsChange(
                                settings.copy(
                                    lanEnabled = it
                                )
                            )
                        },
                    )
                },
            ) {
                val ip =
                    remember(refreshKey, settings.lanEnabled) {
                        LanInfo.localIpv4()
                    }

                var portDraft by
                    remember(settings.lanPort) {
                        mutableStateOf(
                            settings.lanPort.toString()
                        )
                    }

                fun commitPort() {
                    val port =
                        portDraft.toIntOrNull()
                            ?.coerceIn(1024, 65535)
                            ?: settings.lanPort

                    portDraft =
                        port.toString()

                    if (port != settings.lanPort) {
                        onSettingsChange(
                            settings.copy(
                                lanPort = port
                            )
                        )
                    }
                }

                Text(
                    if (settings.lanEnabled) {
                        if (ip == "Unavailable") {
                            "IP unavailable • port ${settings.lanPort}"
                        } else {
                            "http://$ip:${settings.lanPort}"
                        }
                    } else {
                        "Remote service off"
                    },
                    style =
                        MaterialTheme.typography
                            .bodySmall,
                    color =
                        MaterialTheme.colorScheme
                            .onSurfaceVariant,
                )

                OutlinedTextField(
                    value = portDraft,
                    onValueChange = { raw ->
                        portDraft =
                            raw.filter(Char::isDigit)
                                .take(5)
                    },
                    label = {
                        Text("Port")
                    },
                    singleLine = true,
                    keyboardOptions =
                        KeyboardOptions(
                            keyboardType =
                                KeyboardType.Number,
                            imeAction =
                                ImeAction.Done,
                        ),
                    keyboardActions =
                        KeyboardActions(
                            onDone = {
                                commitPort()
                            }
                        ),
                    modifier =
                        Modifier
                            .onFocusChanged {
                                if (!it.isFocused) {
                                    commitPort()
                                }
                            },
                )
            }

            LiteSection(
                title = "Appearance",
                subtitle = "Only essential display modes",
            ) {
                Row(
                    modifier =
                        Modifier.fillMaxWidth(),
                    horizontalArrangement =
                        Arrangement.spacedBy(8.dp),
                ) {
                    LiteChoice(
                        "Light",
                        settings.appearanceMode ==
                            AppearanceMode.LIGHT,
                        Modifier.weight(1f),
                    ) {
                        onSettingsChange(
                            settings.copy(
                                appearanceMode =
                                    AppearanceMode.LIGHT
                            )
                        )
                    }
                    LiteChoice(
                        "Dark",
                        settings.appearanceMode ==
                            AppearanceMode.DARK,
                        Modifier.weight(1f),
                    ) {
                        onSettingsChange(
                            settings.copy(
                                appearanceMode =
                                    AppearanceMode.DARK
                            )
                        )
                    }
                }

                Row(
                    modifier =
                        Modifier.fillMaxWidth(),
                    horizontalArrangement =
                        Arrangement.spacedBy(8.dp),
                ) {
                    LiteChoice(
                        "System",
                        settings.appearanceMode ==
                            AppearanceMode.SYSTEM,
                        Modifier.weight(1f),
                    ) {
                        onSettingsChange(
                            settings.copy(
                                appearanceMode =
                                    AppearanceMode.SYSTEM
                            )
                        )
                    }
                    LiteChoice(
                        "Pure Black",
                        settings.appearanceMode ==
                            AppearanceMode.PITCH_BLACK,
                        Modifier.weight(1f),
                    ) {
                        onSettingsChange(
                            settings.copy(
                                appearanceMode =
                                    AppearanceMode.PITCH_BLACK
                            )
                        )
                    }
                }
            }

            LiteSection(
                title = "Updates",
                subtitle = "Official Lite releases • GitHub",
            ) {
                Text("Installed Lite v${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodyMedium)
                Text(updateStatus, style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        updateStatus = "Checking GitHub…"
                        bb.pix.wall.engine.EngineExecutors.io {
                            val result = LiteUpdateChecker.check(context.applicationContext)
                            android.os.Handler(android.os.Looper.getMainLooper()).post {
                                updateStatus = result.message
                                updateUrl = if (result.available) result.page else ""
                            }
                        }
                    }) { Text("Check updates") }
                    if (updateUrl.startsWith("https://github.com/")) {
                        OutlinedButton(onClick = {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(updateUrl)))
                        }) { Text("Open release") }
                    }
                }
            }
            LiteDeveloperIdentityCard()

            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun LiteHeader(
    settings: AppSettings,
    onRequestAdvanced: () -> Unit,
) {
    Card(
        colors =
            CardDefaults.cardColors(
                containerColor =
                    MaterialTheme.colorScheme.surface,
            ),
    ) {
        Column(
            modifier =
                Modifier.padding(14.dp),
            verticalArrangement =
                Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier =
                    Modifier.fillMaxWidth(),
                verticalAlignment =
                    Alignment.CenterVertically,
            ) {
                Column(
                    modifier =
                        Modifier.weight(1f)
                ) {
                    Row(
                        verticalAlignment =
                            Alignment.CenterVertically,
                    ) {
                        Text(
                            "BB-PixWall",
                            style =
                                MaterialTheme.typography
                                    .headlineSmall,
                            fontWeight =
                                FontWeight.Bold,
                        )

                        Spacer(
                            Modifier.size(7.dp)
                        )

                        androidx.compose.material3.Surface(
                            shape =
                                RoundedCornerShape(8.dp),
                            color =
                                Color(0xFF00F5FF)
                                    .copy(alpha = .10f),
                            border =
                                BorderStroke(
                                    1.dp,
                                    Color(0xFF00F5FF)
                                        .copy(alpha = .75f),
                                ),
                        ) {
                            Text(
                                "Lite",
                                modifier =
                                    Modifier.padding(
                                        horizontal = 7.dp,
                                        vertical = 3.dp,
                                    ),
                                style =
                                    MaterialTheme.typography
                                        .labelSmall,
                                fontWeight =
                                    FontWeight.Bold,
                                color =
                                    Color(0xFF72F8FF),
                            )
                        }
                    }

                    Text(
                        "V${BuildConfig.VERSION_NAME.substringBeforeLast(".0")}",
                        style =
                            MaterialTheme.typography
                                .labelMedium,
                        color =
                            MaterialTheme.colorScheme
                                .primary,
                    )
                }

                AssistChip(
                    onClick =
                        if (
                            settings.engineMode ==
                            EngineMode.STANDARD
                        ) {
                            onRequestAdvanced
                        } else {
                            {}
                        },
                    label = {
                        Text(
                            if (
                                settings.engineMode ==
                                EngineMode.ADVANCED
                            ) {
                                "Advance"
                            } else {
                                "Standard"
                            }
                        )
                    },
                )
            }

            CinematicSubtitle()
        }
    }
}


@Composable
private fun CinematicSubtitle() {
    val transition =
        rememberInfiniteTransition(
            label = "lite_subtitle_reflection"
        )

    val shineX by
        transition.animateFloat(
            initialValue = -180f,
            targetValue = 620f,
            animationSpec =
                infiniteRepeatable(
                    animation =
                        tween(
                            durationMillis = 2400,
                        ),
                    repeatMode =
                        RepeatMode.Restart,
                ),
            label = "lite_subtitle_shine",
        )

    val baseStyle =
        MaterialTheme.typography
            .labelLarge

    Box {
        Text(
            text = "Automatic Wallpaper Changer",
            style = baseStyle,
            fontWeight = FontWeight.Bold,
            color =
                MaterialTheme.colorScheme
                    .onSurfaceVariant,
        )

        Text(
            text = "Automatic Wallpaper Changer",
            style =
                baseStyle.copy(
                    brush =
                        Brush.linearGradient(
                            colors =
                                listOf(
                                    Color.Transparent,
                                    Color.White.copy(alpha = .16f),
                                    Color.White.copy(alpha = .88f),
                                    Color.White.copy(alpha = .16f),
                                    Color.Transparent,
                                ),
                            start =
                                Offset(
                                    shineX - 58f,
                                    0f,
                                ),
                            end =
                                Offset(
                                    shineX + 58f,
                                    0f,
                                ),
                        )
                ),
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun LiteCurrentPreviewStrip(
    refreshKey: Int,
) {
    Row(
        modifier =
            Modifier.fillMaxWidth(),
        horizontalArrangement =
            Arrangement.spacedBy(10.dp),
    ) {
        LiteCurrentPreview(
            label = "Current Home",
            file = WallpaperFiles.currentHome,
            refreshKey = refreshKey,
            modifier = Modifier.weight(1f),
        )

        LiteCurrentPreview(
            label = "Current Lock",
            file = WallpaperFiles.currentLock,
            refreshKey = refreshKey,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun LiteCurrentPreview(
    label: String,
    file: java.io.File,
    refreshKey: Int,
    modifier: Modifier = Modifier,
) {
    var preview by
        remember(
            refreshKey,
            file.absolutePath,
            file.lastModified(),
            file.length(),
        ) {
            mutableStateOf<android.graphics.Bitmap?>(null)
        }

    LaunchedEffect(
        refreshKey,
        file.absolutePath,
        file.lastModified(),
        file.length(),
    ) {
        preview =
            withContext(Dispatchers.IO) {
                if (!file.exists() || file.length() <= 0L) {
                    return@withContext null
                }

                val bounds =
                    BitmapFactory.Options().apply {
                        inJustDecodeBounds = true
                    }

                BitmapFactory.decodeFile(
                    file.absolutePath,
                    bounds,
                )

                if (
                    bounds.outWidth <= 0 ||
                    bounds.outHeight <= 0
                ) {
                    return@withContext null
                }

                var sample = 1

                while (
                    bounds.outWidth / sample > 320 ||
                    bounds.outHeight / sample > 420
                ) {
                    sample *= 2
                }

                runCatching {
                    BitmapFactory.decodeFile(
                        file.absolutePath,
                        BitmapFactory.Options().apply {
                            inSampleSize = sample
                            inPreferredConfig =
                                android.graphics.Bitmap.Config.RGB_565
                        },
                    )
                }.getOrNull()
            }
    }

    Card(
        modifier = modifier,
        colors =
            CardDefaults.cardColors(
                containerColor =
                    MaterialTheme.colorScheme.surface,
            ),
    ) {
        Row(
            modifier =
                Modifier.padding(8.dp),
            verticalAlignment =
                Alignment.CenterVertically,
            horizontalArrangement =
                Arrangement.spacedBy(9.dp),
        ) {
            Box(
                modifier =
                    Modifier
                        .size(
                            width = 58.dp,
                            height = 72.dp,
                        )
                        .clip(
                            RoundedCornerShape(10.dp)
                        )
                        .background(
                            MaterialTheme.colorScheme
                                .surfaceVariant
                        ),
                contentAlignment =
                    Alignment.Center,
            ) {
                val bitmap = preview

                if (bitmap != null) {
                    Image(
                        bitmap =
                            bitmap.asImageBitmap(),
                        contentDescription = label,
                        modifier =
                            Modifier.fillMaxSize(),
                        contentScale =
                            ContentScale.Crop,
                    )
                } else {
                    Icon(
                        imageVector =
                            if (label.contains("Home")) {
                                Icons.Outlined.Home
                            } else {
                                Icons.Outlined.Lock
                            },
                        contentDescription = null,
                        modifier =
                            Modifier.size(22.dp),
                        tint =
                            MaterialTheme.colorScheme
                                .onSurfaceVariant,
                    )
                }
            }

            Column(
                modifier =
                    Modifier.weight(1f)
            ) {
                Text(
                    text = label,
                    style =
                        MaterialTheme.typography
                            .labelMedium,
                    fontWeight =
                        FontWeight.SemiBold,
                )

                Text(
                    text =
                        if (preview != null) {
                            "Ready"
                        } else {
                            "Waiting"
                        },
                    style =
                        MaterialTheme.typography
                            .labelSmall,
                    color =
                        MaterialTheme.colorScheme
                            .onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun LiteSection(
    title: String,
    subtitle: String,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier =
            Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier =
                Modifier.padding(12.dp),
            verticalArrangement =
                Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier =
                    Modifier.fillMaxWidth(),
                verticalAlignment =
                    Alignment.CenterVertically,
            ) {
                Column(
                    modifier =
                        Modifier.weight(1f)
                ) {
                    Text(
                        title,
                        style =
                            MaterialTheme.typography
                                .titleMedium,
                        fontWeight =
                            FontWeight.SemiBold,
                    )
                    Text(
                        subtitle,
                        style =
                            MaterialTheme.typography
                                .bodySmall,
                        color =
                            MaterialTheme.colorScheme
                                .onSurfaceVariant,
                    )
                }
                trailing?.invoke()
            }

            content()
        }
    }
}

@Composable
private fun LiteChoice(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = {
            Text(
                label,
                maxLines = 1,
            )
        },
        modifier = Modifier,
    )
}

@Composable
private fun LiteSourceField(
    label: String,
    sourceKey: String,
    noun: String,
    value: String,
    onValueChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier =
            Modifier.fillMaxWidth(),
        label = {
            Text(label)
        },
        supportingText = {
            LiteSourceHealth(
                sourceKey = sourceKey,
                noun = noun,
            )
        },
        singleLine = true,
    )
}

@Composable
private fun LiteSourceHealth(
    sourceKey: String,
    noun: String,
) {
    val context =
        LocalContext.current

    var pulse by
        remember {
            mutableIntStateOf(0)
        }

    LaunchedEffect(Unit) {
        while (true) {
            delay(2_000L)
            pulse++
        }
    }

    val health =
        RuntimeStatus.get(
            context,
            "source_$sourceKey",
            "Waiting",
        )

    val count =
        RuntimeStatus.get(
            context,
            "${sourceKey}_last_count",
            "",
        )

    val latency =
        RuntimeStatus.getLong(
            context,
            "${sourceKey}_last_latency_ms",
            0L,
        )

    val speed =
        when {
            latency <= 0L -> ""
            latency < 1_500L -> "Fast"
            latency < 4_500L -> "Normal"
            else -> "Slow"
        }

    Text(
        buildString {
            append(health)

            if (count.isNotBlank()) {
                append(" • ")
                append(count)
                append(' ')
                append(noun)
            }

            if (latency > 0L) {
                append(" • ")
                append(
                    if (latency < 1_000L) {
                        "${latency}ms"
                    } else {
                        String.format(
                            java.util.Locale.US,
                            "%.1fs",
                            latency / 1000.0,
                        )
                    }
                )

                if (speed.isNotBlank()) {
                    append(" • ")
                    append(speed)
                }
            }
        },
        style =
            MaterialTheme.typography
                .labelSmall,
        color =
            MaterialTheme.colorScheme
                .onSurfaceVariant,
    )

    @Suppress("UNUSED_VARIABLE")
    val keepPulseObserved = pulse
}

@Composable
private fun LiteBlurControl(
    title: String,
    enabled: Boolean,
    radius: Int,
    onEnabled: (Boolean) -> Unit,
    onRadius: (Int) -> Unit,
) {
    val percent =
        ((radius.coerceIn(1, 64) / 64f) * 100f)
            .roundToInt()
            .coerceIn(1, 100)

    Row(
        modifier =
            Modifier.fillMaxWidth(),
        verticalAlignment =
            Alignment.CenterVertically,
    ) {
        Column(
            modifier =
                Modifier.weight(1f)
        ) {
            Text(
                title,
                fontWeight =
                    FontWeight.Medium,
            )
            Text(
                "$percent%",
                style =
                    MaterialTheme.typography
                        .bodySmall,
                color =
                    MaterialTheme.colorScheme
                        .onSurfaceVariant,
            )
        }
        Switch(
            checked = enabled,
            onCheckedChange = onEnabled,
        )
    }

    Slider(
        value = percent.toFloat(),
        onValueChange = { raw ->
            val pct =
                raw.roundToInt()
                    .coerceIn(1, 100)

            val mapped =
                ((pct / 100f) * 64f)
                    .roundToInt()
                    .coerceIn(1, 64)

            onRadius(mapped)
        },
        valueRange = 1f..100f,
    )
}

@Composable
private fun LiteDeveloperIdentityCard() {
    val context = LocalContext.current

    fun open(uri: String) {
        runCatching {
            context.startActivity(
                Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse(uri),
                )
            )
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                "Developer",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Text(
                "Bharat Bhat",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )

            OutlinedButton(
                onClick = {
                    runCatching {
                        context.startActivity(
                            Intent(
                                Intent.ACTION_SENDTO,
                                Uri.parse("mailto:bkbhatinfo@gmail.com"),
                            )
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                androidx.compose.foundation.Image(
                    painter = painterResource(R.drawable.ic_brand_gmail),
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.size(8.dp))
                Text("bkbhatinfo@gmail.com")
            }

            OutlinedButton(
                onClick = {
                    open("https://github.com/officialbharatbhat")
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_brand_github),
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.size(8.dp))
                Text("@officialbharatbhat")
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = {
                        open("https://www.youtube.com/@sloverbofficial")
                    },
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                ) {
                    Icon(
                        Icons.Outlined.PlayCircle,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.size(5.dp))
                    Text("YouTube", maxLines = 1)
                }

                OutlinedButton(
                    onClick = {
                        open("https://www.instagram.com/officialbharatbhat")
                    },
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                ) {
                    Icon(
                        Icons.Outlined.PhotoCamera,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.size(5.dp))
                    Text("Instagram", maxLines = 1)
                }

                OutlinedButton(
                    onClick = {
                        open("https://t.me/BharatBhat")
                    },
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                ) {
                    Icon(
                        Icons.Outlined.Send,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.size(5.dp))
                    Text("Telegram", maxLines = 1)
                }
            }
        }
    }
}

