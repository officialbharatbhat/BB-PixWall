package bb.pix.wall.ui

import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Palette
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import bb.pix.wall.BuildConfig
import bb.pix.wall.engine.RuntimeStatus
import bb.pix.wall.engine.WallpaperFiles
import bb.pix.wall.settings.AppSettings
import bb.pix.wall.settings.AppearanceMode
import bb.pix.wall.settings.EngineMode
import bb.pix.wall.settings.TriggerMode
import bb.pix.wall.settings.WallpaperOrder
import bb.pix.wall.settings.WallpaperTargetMode
import kotlin.math.roundToInt

@Composable
fun LiteHomeScreen(
    settings: AppSettings,
    onSettingsChange: (AppSettings) -> Unit,
    onRequestAdvanced: () -> Unit,
) {
    val context = LocalContext.current
    val storageGranted = Environment.isExternalStorageManager()

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

            LiteSection(
                title = "Sources",
                subtitle = "Google Photos primary • Drive mirror • Local optional",
            ) {
                LiteSourceField(
                    label = "Google Photos",
                    helper = "Primary source",
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
                    helper = "Mirror / fallback",
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
                Text(
                    if (settings.lanEnabled) {
                        "Remote service enabled • port ${settings.lanPort}"
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

            LiteDeveloperCard()

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
                    Text(
                        "BB-PixWall Lite",
                        style =
                            MaterialTheme.typography
                                .headlineSmall,
                        fontWeight =
                            FontWeight.Bold,
                    )
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

                if (
                    settings.engineMode ==
                    EngineMode.STANDARD
                ) {
                    AssistChip(
                        onClick =
                            onRequestAdvanced,
                        label = {
                            Text(
                                "Standard • without root"
                            )
                        },
                    )
                } else {
                    AssistChip(
                        onClick = {},
                        label = {
                            Text(
                                "Advanced • root enabled"
                            )
                        },
                    )
                }
            }

            Text(
                RuntimeStatus.get(
                    LocalContext.current,
                    "last_pipeline",
                    "Original-quality fast apply",
                ),
                style =
                    MaterialTheme.typography
                        .bodySmall,
                color =
                    MaterialTheme.colorScheme
                        .onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun LiteSection(
    title: String,
    subtitle: String,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable Column.() -> Unit,
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
        modifier = modifier,
    )
}

@Composable
private fun LiteSourceField(
    label: String,
    helper: String,
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
            Text(helper)
        },
        singleLine = true,
    )
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
private fun LiteDeveloperCard() {
    val context = LocalContext.current

    Card(
        modifier =
            Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier =
                Modifier.padding(12.dp),
            verticalArrangement =
                Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "Developer Information",
                style =
                    MaterialTheme.typography
                        .titleMedium,
                fontWeight =
                    FontWeight.SemiBold,
            )

            Text(
                "Bharat Bhat",
                fontWeight =
                    FontWeight.Medium,
            )

            Text(
                "bkbhatinfo@gmail.com",
                modifier =
                    Modifier.clickable {
                        runCatching {
                            context.startActivity(
                                Intent(
                                    Intent.ACTION_SENDTO,
                                    Uri.parse(
                                        "mailto:bkbhatinfo@gmail.com"
                                    ),
                                )
                            )
                        }
                    },
                color =
                    MaterialTheme.colorScheme.primary,
            )

            Row(
                modifier =
                    Modifier.fillMaxWidth(),
                horizontalArrangement =
                    Arrangement.spacedBy(8.dp),
            ) {
                LiteLink(
                    "Instagram",
                    "https://www.instagram.com/officialbharatbhat",
                    Modifier.weight(1f),
                )
                LiteLink(
                    "Telegram",
                    "https://t.me/BharatBhat",
                    Modifier.weight(1f),
                )
            }

            Row(
                modifier =
                    Modifier.fillMaxWidth(),
                horizontalArrangement =
                    Arrangement.spacedBy(8.dp),
            ) {
                LiteLink(
                    "Facebook",
                    "https://www.facebook.com/officialbharatbhat",
                    Modifier.weight(1f),
                )
                LiteLink(
                    "YouTube",
                    "https://www.youtube.com/@sloverbofficial",
                    Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun LiteLink(
    label: String,
    url: String,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    OutlinedButton(
        onClick = {
            runCatching {
                context.startActivity(
                    Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse(url),
                    )
                )
            }
        },
        modifier = modifier,
        contentPadding =
            PaddingValues(
                horizontal = 8.dp,
                vertical = 6.dp,
            ),
    ) {
        Text(
            label,
            maxLines = 1,
        )
    }
}
