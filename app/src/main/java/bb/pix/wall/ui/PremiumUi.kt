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
