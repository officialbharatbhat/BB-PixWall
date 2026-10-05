package bb.pix.wall.ui.theme

import android.os.Build
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import bb.pix.wall.settings.AppearanceMode

@Immutable
data class BBPixWallDesignTokens(
    val cardRadius: Dp,
    val controlRadius: Dp,

    /*
     * Density is deliberately compact across every profile.
     * Theme personality comes from geometry/motion/type,
     * not from wasting half the display with padding.
     */
    val spacingScale: Float,
    val contentPadding: Dp,
    val sectionGap: Dp,

    val motionDurationMs: Int,
    val easing: Easing,

    val sliderHeight: Dp,
    val cardBorderAlpha: Float,
    val borderWidth: Dp,

    val sectionUppercase: Boolean,

    /*
     * Theme-level visual behavior consumed by reusable UI.
     */
    val glowEnabled: Boolean,
    val glowSweepMs: Int,
    val sharpControls: Boolean,
)

val LocalDesignTokens =
    staticCompositionLocalOf {
        BBPixWallDesignTokens(
            cardRadius = 14.dp,
            controlRadius = 10.dp,
            spacingScale = .82f,
            contentPadding = 11.dp,
            sectionGap = 7.dp,
            motionDurationMs = 260,
            easing =
                CubicBezierEasing(
                    .2f,
                    0f,
                    0f,
                    1f,
                ),
            sliderHeight = 3.dp,
            cardBorderAlpha = .22f,
            borderWidth = 1.dp,
            sectionUppercase = false,
            glowEnabled = true,
            glowSweepMs = 2600,
            sharpControls = false,
        )
    }

private fun profileScheme(
    profile: ThemeProfile,
    dark: Boolean,
): ColorScheme =
    when (profile) {
        ThemeProfile.SIGNATURE ->
            if (dark) {
                darkColorScheme(
                    primary = Color(0xFF8EEBFF),
                    secondary = Color(0xFFAE91FF),
                    tertiary = Color(0xFFFF76BE),
                    background = Color(0xFF080A0E),
                    surface = Color(0xFF101319),
                    surfaceVariant = Color(0xFF181D26),
                    outline = Color(0xFF33404F),
                )
            } else {
                lightColorScheme(
                    primary = Color(0xFF00677A),
                    secondary = Color(0xFF6750A4),
                    tertiary = Color(0xFFA93674),
                    background = Color(0xFFF8FAFC),
                    surface = Color(0xFFFFFFFF),
                    surfaceVariant = Color(0xFFEEF2F6),
                    outline = Color(0xFFBCC7D1),
                )
            }

        ThemeProfile.CINEMATIC ->
            if (dark) {
                darkColorScheme(
                    primary = Color(0xFFECC77C),
                    secondary = Color(0xFFCDA46B),
                    tertiary = Color(0xFFD9D0C1),
                    background = Color(0xFF090908),
                    surface = Color(0xFF11110F),
                    surfaceVariant = Color(0xFF1B1A17),
                    outline = Color(0xFF3B352C),
                )
            } else {
                lightColorScheme(
                    primary = Color(0xFF755316),
                    secondary = Color(0xFF795B31),
                    tertiary = Color(0xFF60584C),
                    background = Color(0xFFF8F5EE),
                    surface = Color(0xFFFFFCF5),
                    surfaceVariant = Color(0xFFF0EBDF),
                    outline = Color(0xFFC9BEAA),
                )
            }

        ThemeProfile.CYBER ->
            if (dark) {
                darkColorScheme(
                    primary = Color(0xFF66FFD1),
                    secondary = Color(0xFF68C7FF),
                    tertiary = Color(0xFFB6FF65),
                    background = Color(0xFF050908),
                    surface = Color(0xFF09110F),
                    surfaceVariant = Color(0xFF0E1B18),
                    outline = Color(0xFF1E5B4C),
                )
            } else {
                lightColorScheme(
                    primary = Color(0xFF006B54),
                    secondary = Color(0xFF006493),
                    tertiary = Color(0xFF496700),
                    background = Color(0xFFF5FBF8),
                    surface = Color(0xFFFFFFFF),
                    surfaceVariant = Color(0xFFE5F4EF),
                    outline = Color(0xFFA8C9BF),
                )
            }

        ThemeProfile.LUXE ->
            if (dark) {
                darkColorScheme(
                    primary = Color(0xFFDCC7FF),
                    secondary = Color(0xFFFFC0D8),
                    tertiary = Color(0xFFFFD59A),
                    background = Color(0xFF0C0A0E),
                    surface = Color(0xFF151218),
                    surfaceVariant = Color(0xFF211C25),
                    outline = Color(0xFF403846),
                )
            } else {
                lightColorScheme(
                    primary = Color(0xFF6A4F86),
                    secondary = Color(0xFF8B5169),
                    tertiary = Color(0xFF765A29),
                    background = Color(0xFFFBF8FC),
                    surface = Color(0xFFFFFFFF),
                    surfaceVariant = Color(0xFFF3EDF5),
                    outline = Color(0xFFCEC3D1),
                )
            }

        ThemeProfile.MATERIAL_PRO ->
            if (dark) {
                darkColorScheme(
                    primary = Color(0xFFBFD3FF),
                    secondary = Color(0xFFC4C7D0),
                    tertiary = Color(0xFFDEC5FF),
                    background = Color(0xFF101114),
                    surface = Color(0xFF17181C),
                    surfaceVariant = Color(0xFF22242A),
                    outline = Color(0xFF45474F),
                )
            } else {
                lightColorScheme(
                    primary = Color(0xFF3E5F8F),
                    secondary = Color(0xFF565E71),
                    tertiary = Color(0xFF70558B),
                    background = Color(0xFFF8F9FC),
                    surface = Color(0xFFFFFFFF),
                    surfaceVariant = Color(0xFFEFEFF4),
                    outline = Color(0xFFC3C5CC),
                )
            }
    }

private fun enforceReadableContent(
    base: ColorScheme,
    dark: Boolean,
): ColorScheme {
    val main =
        if (dark) {
            Color(0xFFF3F2F5)
        } else {
            Color(0xFF17171A)
        }

    val muted =
        if (dark) {
            Color(0xFFC5C3C9)
        } else {
            Color(0xFF5D5C63)
        }

    return base.copy(
        onBackground = main,
        onSurface = main,
        onSurfaceVariant = muted,
    )
}

private fun pitchBlack(
    base: ColorScheme,
): ColorScheme =
    base.copy(
        background = Color.Black,
        surface = Color(0xFF030303),
        surfaceVariant = Color(0xFF090909),
        surfaceContainer = Color(0xFF050505),
        surfaceContainerLow = Color(0xFF020202),
        surfaceContainerHigh = Color(0xFF080808),
        surfaceContainerHighest = Color(0xFF0B0B0B),
        surfaceDim = Color.Black,
        surfaceBright = Color(0xFF0D0D0D),
        outline = Color(0xFF242424),
        outlineVariant = Color(0xFF151515),
        onBackground = Color(0xFFF5F3F6),
        onSurface = Color(0xFFF5F3F6),
        onSurfaceVariant = Color(0xFFC0BBC4),
    )

private fun tokens(
    profile: ThemeProfile,
): BBPixWallDesignTokens =
    when (profile) {
        ThemeProfile.SIGNATURE ->
            BBPixWallDesignTokens(
                cardRadius = 14.dp,
                controlRadius = 10.dp,
                spacingScale = .82f,
                contentPadding = 11.dp,
                sectionGap = 7.dp,
                motionDurationMs = 260,
                easing =
                    CubicBezierEasing(
                        .2f,
                        0f,
                        0f,
                        1f,
                    ),
                sliderHeight = 3.dp,
                cardBorderAlpha = .34f,
                borderWidth = 1.dp,
                sectionUppercase = false,
                glowEnabled = true,
                glowSweepMs = 2400,
                sharpControls = false,
            )

        ThemeProfile.CINEMATIC ->
            BBPixWallDesignTokens(
                cardRadius = 10.dp,
                controlRadius = 7.dp,
                spacingScale = .78f,
                contentPadding = 10.dp,
                sectionGap = 6.dp,
                motionDurationMs = 420,
                easing =
                    CubicBezierEasing(
                        .16f,
                        1f,
                        .3f,
                        1f,
                    ),
                sliderHeight = 2.dp,
                cardBorderAlpha = .30f,
                borderWidth = 1.dp,
                sectionUppercase = true,
                glowEnabled = true,
                glowSweepMs = 3600,
                sharpControls = false,
            )

        ThemeProfile.CYBER ->
            BBPixWallDesignTokens(
                cardRadius = 4.dp,
                controlRadius = 3.dp,
                spacingScale = .72f,
                contentPadding = 9.dp,
                sectionGap = 5.dp,
                motionDurationMs = 140,
                easing =
                    CubicBezierEasing(
                        .2f,
                        0f,
                        .2f,
                        1f,
                    ),
                sliderHeight = 2.dp,
                cardBorderAlpha = .48f,
                borderWidth = 1.dp,
                sectionUppercase = true,
                glowEnabled = true,
                glowSweepMs = 1500,
                sharpControls = true,
            )

        ThemeProfile.LUXE ->
            BBPixWallDesignTokens(
                cardRadius = 18.dp,
                controlRadius = 13.dp,
                spacingScale = .84f,
                contentPadding = 12.dp,
                sectionGap = 8.dp,
                motionDurationMs = 520,
                easing =
                    CubicBezierEasing(
                        .16f,
                        1f,
                        .3f,
                        1f,
                    ),
                sliderHeight = 3.dp,
                cardBorderAlpha = .20f,
                borderWidth = 1.dp,
                sectionUppercase = false,
                glowEnabled = true,
                glowSweepMs = 4200,
                sharpControls = false,
            )

        ThemeProfile.MATERIAL_PRO ->
            BBPixWallDesignTokens(
                cardRadius = 12.dp,
                controlRadius = 10.dp,
                spacingScale = .78f,
                contentPadding = 10.dp,
                sectionGap = 6.dp,
                motionDurationMs = 240,
                easing =
                    CubicBezierEasing(
                        .2f,
                        0f,
                        0f,
                        1f,
                    ),
                sliderHeight = 3.dp,
                cardBorderAlpha = .18f,
                borderWidth = 1.dp,
                sectionUppercase = false,
                glowEnabled = false,
                glowSweepMs = 2600,
                sharpControls = false,
            )
    }

@Composable
private fun typography(
    profile: ThemeProfile,
): Typography {
    val base =
        Typography()

    return when (profile) {
        ThemeProfile.SIGNATURE ->
            base.copy(
                headlineLarge =
                    base.headlineLarge.copy(
                        fontFamily =
                            FontFamily.SansSerif,
                        fontSize = 30.sp,
                        fontWeight =
                            FontWeight.ExtraBold,
                        letterSpacing =
                            (-.7).sp,
                    ),
                titleLarge =
                    base.titleLarge.copy(
                        fontSize = 18.sp,
                        fontWeight =
                            FontWeight.Bold,
                    ),
                bodyLarge =
                    base.bodyLarge.copy(
                        fontSize = 14.sp,
                    ),
            )

        ThemeProfile.CINEMATIC ->
            base.copy(
                headlineLarge =
                    base.headlineLarge.copy(
                        fontFamily =
                            FontFamily.Serif,
                        fontSize = 30.sp,
                        fontWeight =
                            FontWeight.Bold,
                        letterSpacing =
                            .8.sp,
                    ),
                titleLarge =
                    base.titleLarge.copy(
                        fontFamily =
                            FontFamily.Serif,
                        fontSize = 18.sp,
                        fontWeight =
                            FontWeight.Bold,
                        letterSpacing =
                            .6.sp,
                    ),
                bodyLarge =
                    base.bodyLarge.copy(
                        fontSize = 14.sp,
                    ),
            )

        ThemeProfile.CYBER ->
            base.copy(
                headlineLarge =
                    base.headlineLarge.copy(
                        fontFamily =
                            FontFamily.Monospace,
                        fontSize = 26.sp,
                        fontWeight =
                            FontWeight.Bold,
                    ),
                titleLarge =
                    base.titleLarge.copy(
                        fontFamily =
                            FontFamily.Monospace,
                        fontSize = 16.sp,
                        fontWeight =
                            FontWeight.Bold,
                    ),
                titleMedium =
                    base.titleMedium.copy(
                        fontFamily =
                            FontFamily.Monospace,
                    ),
                bodyLarge =
                    base.bodyLarge.copy(
                        fontFamily =
                            FontFamily.Monospace,
                        fontSize = 13.sp,
                    ),
                bodyMedium =
                    base.bodyMedium.copy(
                        fontFamily =
                            FontFamily.Monospace,
                        fontSize = 12.sp,
                    ),
                labelLarge =
                    base.labelLarge.copy(
                        fontFamily =
                            FontFamily.Monospace,
                    ),
            )

        ThemeProfile.LUXE ->
            base.copy(
                headlineLarge =
                    base.headlineLarge.copy(
                        fontFamily =
                            FontFamily.Serif,
                        fontSize = 31.sp,
                        fontWeight =
                            FontWeight.SemiBold,
                        letterSpacing =
                            .2.sp,
                    ),
                titleLarge =
                    base.titleLarge.copy(
                        fontFamily =
                            FontFamily.Serif,
                        fontSize = 19.sp,
                        fontWeight =
                            FontWeight.SemiBold,
                    ),
                bodyLarge =
                    base.bodyLarge.copy(
                        fontSize = 14.sp,
                    ),
            )

        ThemeProfile.MATERIAL_PRO ->
            base.copy(
                headlineLarge =
                    base.headlineLarge.copy(
                        fontSize = 29.sp,
                        fontWeight =
                            FontWeight.Bold,
                    ),
                titleLarge =
                    base.titleLarge.copy(
                        fontSize = 18.sp,
                        fontWeight =
                            FontWeight.SemiBold,
                    ),
                bodyLarge =
                    base.bodyLarge.copy(
                        fontSize = 14.sp,
                    ),
            )
    }
}

@Composable
fun BBPixWallTheme(
    profile: ThemeProfile,
    appearanceMode: AppearanceMode,
    content: @Composable () -> Unit,
) {
    val context =
        LocalContext.current

    val systemDark =
        isSystemInDarkTheme()

    val dark =
        when (appearanceMode) {
            AppearanceMode.LIGHT ->
                false

            AppearanceMode.DARK,
            AppearanceMode.PITCH_BLACK ->
                true

            AppearanceMode.SYSTEM,
            AppearanceMode.SYSTEM_MONET ->
                systemDark
        }

    var colors =
        if (
            appearanceMode ==
                AppearanceMode.SYSTEM_MONET &&
            Build.VERSION.SDK_INT >=
                Build.VERSION_CODES.S
        ) {
            if (dark) {
                dynamicDarkColorScheme(
                    context
                )
            } else {
                dynamicLightColorScheme(
                    context
                )
            }
        } else {
            profileScheme(
                profile,
                dark,
            )
        }

    colors =
        enforceReadableContent(
            colors,
            dark,
        )

    if (
        appearanceMode ==
        AppearanceMode.PITCH_BLACK
    ) {
        colors =
            pitchBlack(
                colors
            )
    }

    val designTokens =
        tokens(
            profile
        )

    CompositionLocalProvider(
        LocalDesignTokens provides
            designTokens
    ) {
        MaterialTheme(
            colorScheme = colors,
            typography =
                typography(profile),
            shapes =
                MaterialTheme.shapes.copy(
                    large =
                        RoundedCornerShape(
                            designTokens.cardRadius
                        ),
                    medium =
                        RoundedCornerShape(
                            designTokens.controlRadius
                        ),
                    small =
                        RoundedCornerShape(
                            if (
                                designTokens.sharpControls
                            ) {
                                2.dp
                            } else {
                                (
                                    designTokens
                                        .controlRadius
                                        .value *
                                        .55f
                                ).dp
                            }
                        ),
                ),
            content = content,
        )
    }
}
