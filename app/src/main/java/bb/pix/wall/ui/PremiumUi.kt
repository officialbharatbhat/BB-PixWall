package bb.pix.wall.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import bb.pix.wall.R
import bb.pix.wall.settings.EngineMode
import bb.pix.wall.ui.theme.LocalDesignTokens
import bb.pix.wall.ui.theme.ThemeProfile

@Composable
private fun themeAccent(
    theme: ThemeProfile,
): Color =
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

@Composable
private fun OrbitBorder(
    modifier: Modifier,
    color: Color,
    radius: Dp,
    durationMs: Int,
    strokeWidth: Dp = 1.6.dp,
) {
    val transition =
        rememberInfiniteTransition(
            label = "orbit_border"
        )

    val progress by
        transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec =
                infiniteRepeatable(
                    animation =
                        tween(
                            durationMillis =
                                durationMs,
                            easing =
                                LinearEasing,
                        )
                ),
            label = "orbit_progress",
        )

    Canvas(modifier) {
        if (
            size.width <= 1f ||
            size.height <= 1f
        ) {
            return@Canvas
        }

        val strokePx =
            strokeWidth.toPx()

        val inset =
            strokePx * 2f

        val r =
            radius.toPx()
                .coerceAtMost(
                    minOf(
                        size.width,
                        size.height,
                    ) / 2f
                )

        val border =
            Path().apply {
                addRoundRect(
                    RoundRect(
                        left = inset,
                        top = inset,
                        right =
                            size.width - inset,
                        bottom =
                            size.height - inset,
                        cornerRadius =
                            CornerRadius(r, r),
                    )
                )
            }

        val measure =
            PathMeasure().apply {
                setPath(
                    border,
                    false,
                )
            }

        val total =
            measure.length

        if (total <= 0f) {
            return@Canvas
        }

        /*
         * Only ~9% of the perimeter is illuminated.
         * This is the actual moving runner.
         */
        val runnerLength =
            total * .09f

        val runnerStart =
            total * progress

        fun extractSegment(
            startDistance: Float,
            segmentLength: Float,
        ): Path {
            val out = Path()

            val normalized =
                ((startDistance % total) + total) % total

            val stop =
                normalized + segmentLength

            if (stop <= total) {
                measure.getSegment(
                    normalized,
                    stop,
                    out,
                    true,
                )
            } else {
                measure.getSegment(
                    normalized,
                    total,
                    out,
                    true,
                )

                measure.getSegment(
                    0f,
                    stop - total,
                    out,
                    true,
                )
            }

            return out
        }

        val runner =
            extractSegment(
                runnerStart,
                runnerLength,
            )

        /*
         * Soft halo. Still only around the short runner,
         * never around the whole card/screen.
         */
        drawPath(
            path = runner,
            color =
                color.copy(
                    alpha = .17f
                ),
            style =
                Stroke(
                    width =
                        strokePx * 5f,
                    cap =
                        StrokeCap.Round,
                ),
        )

        drawPath(
            path = runner,
            color =
                color.copy(
                    alpha = .78f
                ),
            style =
                Stroke(
                    width =
                        strokePx * 2.1f,
                    cap =
                        StrokeCap.Round,
                ),
        )

        /*
         * Tiny bright head gives the runner a light-source
         * look instead of a boring moving line.
         */
        val head =
            extractSegment(
                runnerStart +
                    runnerLength * .72f,
                runnerLength * .28f,
            )

        drawPath(
            path = head,
            color = Color.White,
            style =
                Stroke(
                    width =
                        strokePx * 1.15f,
                    cap =
                        StrokeCap.Round,
                ),
        )
    }
}

@Composable
private fun OrbitNameBadge(
    text: String,
    theme: ThemeProfile,
    prominent: Boolean = false,
) {
    val tokens =
        LocalDesignTokens.current

    val accent =
        themeAccent(theme)

    val radius =
        if (tokens.sharpControls) {
            4.dp
        } else {
            11.dp
        }

    Box(
        modifier =
            Modifier.wrapContentSize()
    ) {
        Surface(
            shape =
                RoundedCornerShape(radius),
            color =
                MaterialTheme.colorScheme
                    .surfaceVariant
                    .copy(alpha = .52f),
        ) {
            Text(
                text = text,
                modifier =
                    Modifier.padding(
                        horizontal =
                            if (prominent) {
                                14.dp
                            } else {
                                11.dp
                            },
                        vertical =
                            if (prominent) {
                                7.dp
                            } else {
                                6.dp
                            },
                    ),
                style =
                    if (prominent) {
                        MaterialTheme
                            .typography
                            .titleLarge
                    } else {
                        MaterialTheme
                            .typography
                            .titleMedium
                    },
                fontWeight =
                    FontWeight.Bold,
            )
        }

        OrbitBorder(
            modifier =
                Modifier.matchParentSize(),
            color = accent,
            radius = radius,
            durationMs =
                if (
                    theme ==
                    ThemeProfile.CYBER
                ) {
                    1500
                } else if (
                    theme ==
                    ThemeProfile.CINEMATIC
                ) {
                    3500
                } else {
                    tokens.glowSweepMs
                },
        )
    }
}

@Composable
fun PremiumAppHeader(
    context: Context,
    engineMode: EngineMode,
    theme: ThemeProfile,
) {
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

    val accent =
        themeAccent(theme)

    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = 10.dp,
                    vertical = 4.dp,
                ),
        verticalArrangement =
            Arrangement.spacedBy(6.dp),
    ) {
        Row(
            verticalAlignment =
                Alignment.CenterVertically,
        ) {
            /*
             * IMPORTANT:
             * animation exists ONLY around this
             * BB-PixWall-sized box.
             */
            OrbitNameBadge(
                text = "BB-PixWall",
                theme = theme,
                prominent = true,
            )

            Spacer(
                Modifier.width(8.dp)
            )

            Surface(
                shape =
                    RoundedCornerShape(
                        999.dp
                    ),
                color =
                    MaterialTheme.colorScheme
                        .surfaceVariant
                        .copy(alpha = .72f),
            ) {
                Text(
                    text = "v$version",
                    modifier =
                        Modifier.padding(
                            horizontal = 8.dp,
                            vertical = 4.dp,
                        ),
                    style =
                        MaterialTheme.typography
                            .labelSmall,
                    color =
                        MaterialTheme.colorScheme
                            .onSurfaceVariant,
                )
            }
        }

        Row(
            verticalAlignment =
                Alignment.CenterVertically,
        ) {
            Box(
                modifier =
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
                text = engineMode.label,
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

@Composable
fun CollapsibleSection(
    title: String,
    summary: String,
    initiallyExpanded: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val tokens =
        LocalDesignTokens.current

    var expanded by
        rememberSaveable(title) {
            mutableStateOf(
                initiallyExpanded
            )
        }

    val rotation by
        animateFloatAsState(
            targetValue =
                if (expanded) {
                    180f
                } else {
                    0f
                },
            animationSpec =
                tween(
                    durationMillis =
                        tokens.motionDurationMs,
                    easing =
                        tokens.easing,
                ),
            label =
                "accordion_$title",
        )

    Surface(
        modifier =
            Modifier
                .fillMaxWidth()
                .animateContentSize(
                    animationSpec =
                        tween(
                            durationMillis =
                                tokens.motionDurationMs,
                            easing =
                                tokens.easing,
                        )
                ),
        shape =
            RoundedCornerShape(
                if (tokens.sharpControls) {
                    0.dp
                } else {
                    tokens.cardRadius
                }
            ),
        color =
            MaterialTheme.colorScheme
                .surface
                .copy(alpha = .92f),
        border =
            androidx.compose.foundation
                .BorderStroke(
                    width =
                        tokens.borderWidth,
                    color =
                        MaterialTheme.colorScheme
                            .outline
                            .copy(
                                alpha =
                                    tokens.cardBorderAlpha
                            ),
                ),
    ) {
        Column {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clickable {
                            expanded =
                                !expanded
                        }
                        .padding(
                            horizontal =
                                tokens
                                    .contentPadding,
                            vertical = 9.dp,
                        ),
                verticalAlignment =
                    Alignment.CenterVertically,
            ) {
                Column(
                    modifier =
                        Modifier.weight(1f)
                ) {
                    Text(
                        text =
                            if (
                                tokens.sectionUppercase
                            ) {
                                title.uppercase()
                            } else {
                                title
                            },
                        style =
                            MaterialTheme.typography
                                .titleMedium,
                        fontWeight =
                            FontWeight.SemiBold,
                    )

                    if (
                        !expanded &&
                        summary.isNotBlank()
                    ) {
                        Text(
                            text = summary,
                            style =
                                MaterialTheme
                                    .typography
                                    .bodySmall,
                            color =
                                MaterialTheme
                                    .colorScheme
                                    .onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                }

                Icon(
                    imageVector =
                        Icons.Outlined
                            .KeyboardArrowDown,
                    contentDescription =
                        if (expanded) {
                            "Collapse"
                        } else {
                            "Expand"
                        },
                    modifier =
                        Modifier.rotate(
                            rotation
                        ),
                )
            }

            AnimatedVisibility(
                visible = expanded
            ) {
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(
                                start =
                                    tokens
                                        .contentPadding,
                                end =
                                    tokens
                                        .contentPadding,
                                bottom = 10.dp,
                            ),
                    verticalArrangement =
                        Arrangement.spacedBy(
                            tokens.sectionGap
                        ),
                    content = content,
                )
            }
        }
    }
}

@Composable
private fun BrandButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    colors: List<Color>,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val shape =
        RoundedCornerShape(10.dp)

    Box(
        modifier =
            modifier
                .clip(shape)
                .background(
                    Brush.horizontalGradient(
                        colors
                    )
                )
                .padding(1.dp)
                .clip(shape)
                .background(
                    MaterialTheme.colorScheme
                        .surface
                        .copy(alpha = .97f)
                )
                .clickable(
                    enabled = enabled,
                    onClick = onClick,
                )
                .padding(
                    horizontal = 9.dp,
                    vertical = 8.dp,
                ),
    ) {
        Row(
            verticalAlignment =
                Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint =
                    colors[
                        colors.size / 2
                    ],
                modifier =
                    Modifier.size(18.dp),
            )

            Spacer(
                Modifier.width(6.dp)
            )

            Text(
                text = label,
                style =
                    MaterialTheme.typography
                        .labelMedium,
                maxLines = 1,
            )
        }
    }
}


@Composable
private fun BrandDrawableButton(
    label: String,
    iconRes: Int,
    colors: List<Color>,
    preserveIconColors: Boolean = false,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val shape =
        RoundedCornerShape(10.dp)

    Box(
        modifier =
            modifier
                .wrapContentWidth()
                .clip(shape)
                .background(
                    Brush.horizontalGradient(
                        colors
                    )
                )
                .padding(1.dp)
                .clip(shape)
                .background(
                    MaterialTheme.colorScheme
                        .surface
                        .copy(alpha = .97f)
                )
                .clickable(
                    enabled = enabled,
                    onClick = onClick,
                )
                .padding(
                    horizontal = 10.dp,
                    vertical = 8.dp,
                ),
    ) {
        Row(
            verticalAlignment =
                Alignment.CenterVertically,
        ) {
            if (preserveIconColors) {
                androidx.compose.foundation.Image(
                    painter =
                        painterResource(iconRes),
                    contentDescription = null,
                    modifier =
                        Modifier.size(19.dp),
                )
            } else {
                Icon(
                    painter =
                        painterResource(iconRes),
                    contentDescription = null,
                    tint =
                        MaterialTheme.colorScheme
                            .onSurface,
                    modifier =
                        Modifier.size(19.dp),
                )
            }

            Spacer(
                Modifier.width(7.dp)
            )

            Text(
                text = label,
                style =
                    MaterialTheme.typography
                        .labelMedium,
                maxLines = 1,
            )
        }
    }
}

private fun openUri(
    context: Context,
    uri: String,
) {
    if (uri.isBlank()) {
        return
    }

    runCatching {
        context.startActivity(
            Intent(
                Intent.ACTION_VIEW,
                Uri.parse(uri),
            )
        )
    }
}

@Composable
fun DeveloperIdentityCard(
    context: Context,
    theme: ThemeProfile,
    youtubeUrl: String,
    instagramUrl: String,
    telegramUrl: String,
) {
    val tokens =
        LocalDesignTokens.current

    val accent =
        themeAccent(theme)

    val gmailColors =
        listOf(
            Color(0xFF4285F4),
            Color(0xFFEA4335),
            Color(0xFFFBBC05),
            Color(0xFF34A853),
        )

    val instagramColors =
        listOf(
            Color(0xFF833AB4),
            Color(0xFFE1306C),
            Color(0xFFF77737),
        )

    val githubColors =
        listOf(
            MaterialTheme.colorScheme
                .onSurfaceVariant,
            MaterialTheme.colorScheme
                .onSurface,
        )

    val youtubeColors =
        listOf(
            Color(0xFFFF0000),
            Color(0xFFD90000),
        )

    val telegramColors =
        listOf(
            Color(0xFF229ED9),
            Color(0xFF55BDEB),
        )

    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = 8.dp,
                    vertical = 5.dp,
                )
    ) {
        Surface(
            modifier =
                Modifier.fillMaxWidth(),
            shape =
                RoundedCornerShape(
                    tokens.cardRadius
                ),
            color =
                MaterialTheme.colorScheme
                    .surface
                    .copy(alpha = .94f),
        ) {
            Column(
                modifier =
                    Modifier.padding(
                        horizontal = 12.dp,
                        vertical = 12.dp,
                    ),
                verticalArrangement =
                    Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = "Developer",
                    style =
                        MaterialTheme.typography
                            .labelMedium,
                    color =
                        MaterialTheme.colorScheme
                            .onSurfaceVariant,
                )

                OrbitNameBadge(
                    text = "Bharat Bhat",
                    theme = theme,
                    prominent = true,
                )

                BrandDrawableButton(
                    label =
                        "bkbhatinfo@gmail.com",
                    iconRes =
                        R.drawable.ic_brand_gmail,
                    colors = gmailColors,
                    preserveIconColors = true,
                ) {
                    openUri(
                        context,
                        "mailto:bkbhatinfo@gmail.com",
                    )
                }

                BrandDrawableButton(
                    label =
                        "@officialbharatbhat",
                    iconRes =
                        R.drawable.ic_brand_github,
                    colors = githubColors,
                ) {
                    openUri(
                        context,
                        "https://github.com/officialbharatbhat",
                    )
                }

                Row(
                    modifier =
                        Modifier.fillMaxWidth(),
                    horizontalArrangement =
                        Arrangement.spacedBy(
                            6.dp
                        ),
                ) {
                    BrandButton(
                        label = "YouTube",
                        icon =
                            Icons.Outlined
                                .PlayCircle,
                        colors =
                            youtubeColors,
                        enabled =
                            youtubeUrl
                                .isNotBlank(),
                        modifier =
                            Modifier.weight(1f),
                    ) {
                        openUri(
                            context,
                            youtubeUrl,
                        )
                    }

                    BrandButton(
                        label = "Instagram",
                        icon =
                            Icons.Outlined
                                .PhotoCamera,
                        colors =
                            instagramColors,
                        enabled =
                            instagramUrl
                                .isNotBlank(),
                        modifier =
                            Modifier.weight(1f),
                    ) {
                        openUri(
                            context,
                            instagramUrl,
                        )
                    }

                    BrandButton(
                        label = "Telegram",
                        icon =
                            Icons.Outlined.Send,
                        colors =
                            telegramColors,
                        enabled =
                            telegramUrl
                                .isNotBlank(),
                        modifier =
                            Modifier.weight(1f),
                    ) {
                        openUri(
                            context,
                            telegramUrl,
                        )
                    }
                }
            }
        }

        OrbitBorder(
            modifier =
                Modifier.matchParentSize(),
            color = accent,
            radius =
                tokens.cardRadius,
            durationMs =
                when (theme) {
                    ThemeProfile.CINEMATIC ->
                        4200

                    ThemeProfile.CYBER ->
                        1800

                    ThemeProfile.LUXE ->
                        4600

                    ThemeProfile.SIGNATURE ->
                        2800

                    ThemeProfile.MATERIAL_PRO ->
                        3300
                },
        )
    }
}


/*
 * BB-PixWall subtle thinking indicator.
 *
 * Inspired by the visual behavior of a quiet processing light:
 * a short soft highlight travels over a muted horizontal track.
 *
 * Important:
 * - no rotating border
 * - no whole-card pulsing
 * - no scale animation
 * - tiny 2dp draw area only
 */
@Composable
fun ThinkingSweepLine(
    modifier: Modifier = Modifier,
    accent: Color =
        MaterialTheme.colorScheme.primary,
) {
    val transition =
        rememberInfiniteTransition(
            label =
                "bb_thinking_sweep"
        )

    val progress by
        transition.animateFloat(
            initialValue = -.30f,
            targetValue = 1.30f,
            animationSpec =
                infiniteRepeatable(
                    animation =
                        tween(
                            durationMillis =
                                1650,
                            easing =
                                androidx.compose.animation
                                    .core
                                    .FastOutSlowInEasing,
                        ),
                    repeatMode =
                        RepeatMode.Restart,
                ),
            label =
                "bb_thinking_sweep_progress",
        )

    Box(
        modifier =
            modifier
                .height(2.dp)
                .clip(
                    RoundedCornerShape(
                        999.dp
                    )
                )
                .background(
                    MaterialTheme
                        .colorScheme
                        .outlineVariant
                        .copy(
                            alpha = .20f
                        )
                )
                .drawWithContent {
                    drawContent()

                    val travel =
                        size.width * progress

                    val half =
                        size.width * .18f

                    drawRect(
                        brush =
                            Brush.horizontalGradient(
                                colors =
                                    listOf(
                                        Color.Transparent,
                                        accent.copy(
                                            alpha = .12f
                                        ),
                                        accent.copy(
                                            alpha = .88f
                                        ),
                                        accent.copy(
                                            alpha = .12f
                                        ),
                                        Color.Transparent,
                                    ),
                                startX =
                                    travel - half,
                                endX =
                                    travel + half,
                            ),
                    )
                }
    )
}
