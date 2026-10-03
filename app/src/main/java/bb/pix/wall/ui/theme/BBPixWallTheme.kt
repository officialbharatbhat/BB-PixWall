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
    val spacingScale: Float,
    val motionDurationMs: Int,
    val easing: Easing,
    val sliderHeight: Dp,
    val cardBorderAlpha: Float,
    val sectionUppercase: Boolean,
)

val LocalDesignTokens = staticCompositionLocalOf {
    BBPixWallDesignTokens(24.dp,18.dp,1f,280,CubicBezierEasing(.2f,0f,0f,1f),4.dp,.22f,false)
}

private fun profileScheme(p: ThemeProfile, dark: Boolean): ColorScheme = when (p) {
    ThemeProfile.SIGNATURE -> if (dark) darkColorScheme(primary=Color(0xFFFF6258),secondary=Color(0xFFE7B72F),tertiary=Color(0xFFFFD86A),background=Color(0xFF101114),surface=Color(0xFF17181C),surfaceVariant=Color(0xFF23252B)) else lightColorScheme(primary=Color(0xFFB4231D),secondary=Color(0xFF8A6412),background=Color(0xFFF8F6F2),surface=Color.White,surfaceVariant=Color(0xFFF0ECE5))
    ThemeProfile.MINIMAL -> if (dark) darkColorScheme(primary=Color(0xFFF0F0F0),secondary=Color(0xFFAAAAAA),background=Color(0xFF0C0C0C),surface=Color(0xFF121212),surfaceVariant=Color(0xFF1A1A1A)) else lightColorScheme(primary=Color(0xFF111111),secondary=Color(0xFF555555),background=Color(0xFFFCFCFC),surface=Color.White,surfaceVariant=Color(0xFFF1F1F1))
    ThemeProfile.GLASS -> if (dark) darkColorScheme(primary=Color(0xFF9EDCFF),secondary=Color(0xFFB0F1D2),tertiary=Color(0xFFD4B9FF),background=Color(0xFF0B1117),surface=Color(0xFF121C25),surfaceVariant=Color(0xFF1B2A36)) else lightColorScheme(primary=Color(0xFF216387),secondary=Color(0xFF39745D),tertiary=Color(0xFF755A9B),background=Color(0xFFF2F8FC),surface=Color(0xFFF9FCFF),surfaceVariant=Color(0xFFE3EFF6))
    ThemeProfile.EDITORIAL -> if (dark) darkColorScheme(primary=Color(0xFFE9B86B),secondary=Color(0xFFD86B5D),background=Color(0xFF15110D),surface=Color(0xFF1E1813),surfaceVariant=Color(0xFF2C241C)) else lightColorScheme(primary=Color(0xFF6D4720),secondary=Color(0xFF9B3C32),background=Color(0xFFFBF5E9),surface=Color(0xFFFFFBF3),surfaceVariant=Color(0xFFF0E4D2))
    ThemeProfile.MATERIAL_YOU -> if (dark) darkColorScheme(primary=Color(0xFFCCB8FF),secondary=Color(0xFFFFB0C8),tertiary=Color(0xFFFFC66E),background=Color(0xFF121017),surface=Color(0xFF1B1821),surfaceVariant=Color(0xFF292431)) else lightColorScheme(primary=Color(0xFF675087),secondary=Color(0xFF8C4A5F),tertiary=Color(0xFF7B5900),background=Color(0xFFFFF7FF),surface=Color(0xFFFFFBFF),surfaceVariant=Color(0xFFF1E8F4))
}

private fun enforceReadableContent(base: ColorScheme, dark: Boolean): ColorScheme {
    val main = if (dark) Color(0xFFF4F1F6) else Color(0xFF18151B)
    val muted = if (dark) Color(0xFFC9C3CE) else Color(0xFF5F5964)
    return base.copy(
        onBackground = main,
        onSurface = main,
        onSurfaceVariant = muted,
        onPrimary = if (dark) Color(0xFF180503) else Color.White,
        onSecondary = if (dark) Color(0xFF171006) else Color.White,
    )
}

private fun pitchBlack(base: ColorScheme, profile: ThemeProfile) = base.copy(
    background=Color.Black,
    surface=Color(0xFF030303),
    surfaceVariant=Color(0xFF0A0A0A),
    surfaceContainer=Color(0xFF060606),
    surfaceContainerLow=Color(0xFF030303),
    surfaceContainerHigh=Color(0xFF090909),
    surfaceContainerHighest=Color(0xFF0B0B0B),
    surfaceDim=Color(0xFF000000),
    surfaceBright=Color(0xFF0D0D0D),
    outline=Color(0xFF151515), outlineVariant=Color(0xFF0F0F0F),
    onBackground=Color(0xFFF3F0F4), onSurface=Color(0xFFF3F0F4), onSurfaceVariant=Color(0xFFBDB7C2),
    primary=when(profile){ThemeProfile.SIGNATURE->Color(0xFFFF5147);ThemeProfile.MINIMAL->Color(0xFFE8E8E8);ThemeProfile.GLASS->Color(0xFF8FD6FF);ThemeProfile.EDITORIAL->Color(0xFFE2A84C);ThemeProfile.MATERIAL_YOU->Color(0xFFC7AEFF)},
    secondary=when(profile){ThemeProfile.SIGNATURE->Color(0xFFD4A927);ThemeProfile.MINIMAL->Color(0xFF9A9A9A);ThemeProfile.GLASS->Color(0xFF93E5C2);ThemeProfile.EDITORIAL->Color(0xFFD85F52);ThemeProfile.MATERIAL_YOU->Color(0xFFFF9FBD)}
)

private fun tokens(p: ThemeProfile)=when(p){
    ThemeProfile.SIGNATURE->BBPixWallDesignTokens(24.dp,18.dp,1f,260,CubicBezierEasing(.2f,0f,0f,1f),4.dp,.22f,false)
    ThemeProfile.MINIMAL->BBPixWallDesignTokens(8.dp,6.dp,.78f,120,CubicBezierEasing(.2f,0f,.2f,1f),2.dp,.10f,true)
    ThemeProfile.GLASS->BBPixWallDesignTokens(34.dp,28.dp,1.08f,420,CubicBezierEasing(.16f,1f,.3f,1f),6.dp,.34f,false)
    ThemeProfile.EDITORIAL->BBPixWallDesignTokens(2.dp,2.dp,1.18f,200,CubicBezierEasing(.3f,0f,0f,1f),3.dp,.28f,true)
    ThemeProfile.MATERIAL_YOU->BBPixWallDesignTokens(30.dp,22.dp,1.02f,320,CubicBezierEasing(.2f,0f,0f,1f),5.dp,.20f,false)
}

@Composable private fun typography(p: ThemeProfile): Typography {
    val base=Typography()
    return when(p){
        ThemeProfile.SIGNATURE->base.copy(headlineLarge=base.headlineLarge.copy(fontFamily=FontFamily.SansSerif,fontSize=38.sp,fontWeight=FontWeight.ExtraBold,letterSpacing=(-1).sp),titleLarge=base.titleLarge.copy(fontWeight=FontWeight.Bold),bodyLarge=base.bodyLarge.copy(fontSize=16.sp))
        ThemeProfile.MINIMAL->base.copy(headlineLarge=base.headlineLarge.copy(fontFamily=FontFamily.Monospace,fontSize=28.sp,fontWeight=FontWeight.Medium,letterSpacing=(-.5).sp),titleLarge=base.titleLarge.copy(fontFamily=FontFamily.Monospace,fontSize=18.sp,fontWeight=FontWeight.Medium),titleMedium=base.titleMedium.copy(fontFamily=FontFamily.Monospace),bodyLarge=base.bodyLarge.copy(fontFamily=FontFamily.Monospace,fontSize=14.sp),bodyMedium=base.bodyMedium.copy(fontFamily=FontFamily.Monospace,fontSize=13.sp),labelLarge=base.labelLarge.copy(fontFamily=FontFamily.Monospace))
        ThemeProfile.GLASS->base.copy(headlineLarge=base.headlineLarge.copy(fontFamily=FontFamily.SansSerif,fontSize=40.sp,fontWeight=FontWeight.Light,letterSpacing=1.sp),titleLarge=base.titleLarge.copy(fontWeight=FontWeight.Medium,letterSpacing=.6.sp),bodyLarge=base.bodyLarge.copy(fontSize=17.sp,fontWeight=FontWeight.Light))
        ThemeProfile.EDITORIAL->base.copy(headlineLarge=base.headlineLarge.copy(fontFamily=FontFamily.Serif,fontSize=46.sp,fontWeight=FontWeight.Bold,letterSpacing=(-1.2).sp),titleLarge=base.titleLarge.copy(fontFamily=FontFamily.Serif,fontSize=28.sp,fontWeight=FontWeight.Bold),titleMedium=base.titleMedium.copy(fontFamily=FontFamily.Serif,fontWeight=FontWeight.Bold),bodyLarge=base.bodyLarge.copy(fontFamily=FontFamily.Serif,fontSize=18.sp),bodyMedium=base.bodyMedium.copy(fontFamily=FontFamily.Serif,fontSize=16.sp))
        ThemeProfile.MATERIAL_YOU->base.copy(headlineLarge=base.headlineLarge.copy(fontFamily=FontFamily.SansSerif,fontSize=42.sp,fontWeight=FontWeight.Black),titleLarge=base.titleLarge.copy(fontWeight=FontWeight.Bold),bodyLarge=base.bodyLarge.copy(fontSize=16.sp))
    }
}

@Composable fun BBPixWallTheme(profile: ThemeProfile, appearanceMode: AppearanceMode, content: @Composable () -> Unit){
    val context=LocalContext.current; val systemDark=isSystemInDarkTheme()
    val dark=when(appearanceMode){AppearanceMode.LIGHT->false;AppearanceMode.DARK,AppearanceMode.PITCH_BLACK->true;AppearanceMode.SYSTEM,AppearanceMode.SYSTEM_MONET->systemDark}
    var colors=if(appearanceMode==AppearanceMode.SYSTEM_MONET && Build.VERSION.SDK_INT>=Build.VERSION_CODES.S){if(dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)} else profileScheme(profile,dark)
    colors = enforceReadableContent(colors, dark)
    if(appearanceMode==AppearanceMode.PITCH_BLACK) colors=pitchBlack(colors,profile)
    CompositionLocalProvider(LocalDesignTokens provides tokens(profile)){
        MaterialTheme(colorScheme=colors,typography=typography(profile),shapes=MaterialTheme.shapes.copy(large=RoundedCornerShape(LocalDesignTokens.current.cardRadius),medium=RoundedCornerShape(LocalDesignTokens.current.controlRadius),small=RoundedCornerShape((LocalDesignTokens.current.controlRadius.value*.55f).dp)),content=content)
    }
}
