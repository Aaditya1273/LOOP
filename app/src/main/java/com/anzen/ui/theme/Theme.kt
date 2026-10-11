package com.anzen.ui.theme

import android.app.UiModeManager
import android.os.Build
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The ANZEN palette. Screens never use these directly; they use [AnzenColors] semantic tokens. */
object Palette {
    val Ink = Color(0xFF111318)
    val Graphite = Color(0xFF20242B)
    val WarmIvory = Color(0xFFF6F3EC)
    val SignalLime = Color(0xFFD3F36B)
    val Slate = Color(0xFF777D87)
    val VerifiedGreen = Color(0xFF237A52)
    val PendingAmber = Color(0xFFB87918)
    val ErrorCrimson = Color(0xFFB83E48)
    val InfoBlue = Color(0xFF3E68C5)
}

/**
 * Semantic tokens. Text tokens are tuned for WCAG AA on their backgrounds (the raw Slate and Amber are too
 * light for small text on ivory, so text uses darker steps of the same hues).
 */
@Immutable
data class AnzenColors(
    val isDark: Boolean,
    val backgroundPrimary: Color,
    val backgroundElevated: Color,
    val surfaceSecondary: Color,
    /** The signature "sealed" surface: ink in light mode, graphite in dark. Holds amounts and passports. */
    val surfaceSealed: Color,
    val onSealed: Color,
    val onSealedSecondary: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val actionPrimary: Color,
    val onActionPrimary: Color,
    val accent: Color,
    val onAccent: Color,
    val statusVerified: Color,
    val statusPending: Color,
    val statusFailed: Color,
    val statusInfo: Color,
    val borderSubtle: Color,
    val borderStrong: Color,
    val focusRing: Color,
    val chrome: Color, // floating navigation material
) {
    fun tint(c: Color) = c.copy(alpha = if (isDark) 0.18f else 0.10f)
}

private val Light = AnzenColors(
    isDark = false,
    backgroundPrimary = Palette.WarmIvory,
    backgroundElevated = Color(0xFFFFFDF8),
    surfaceSecondary = Color(0xFFECE8DE),
    surfaceSealed = Palette.Ink,
    onSealed = Palette.WarmIvory,
    onSealedSecondary = Color(0xFFB5B9C0),
    textPrimary = Palette.Ink,
    textSecondary = Color(0xFF5B616B),
    textTertiary = Color(0xFF6B717B),
    actionPrimary = Palette.Ink,
    onActionPrimary = Palette.WarmIvory,
    accent = Palette.SignalLime,
    onAccent = Palette.Ink,
    statusVerified = Palette.VerifiedGreen,
    statusPending = Color(0xFF8F5C0E),
    statusFailed = Palette.ErrorCrimson,
    statusInfo = Palette.InfoBlue,
    borderSubtle = Palette.Ink.copy(alpha = 0.08f),
    borderStrong = Palette.Ink.copy(alpha = 0.18f),
    focusRing = Palette.InfoBlue,
    chrome = Color(0xFFFFFDF8).copy(alpha = 0.94f),
)

private val Dark = AnzenColors(
    isDark = true,
    backgroundPrimary = Palette.Ink,
    backgroundElevated = Color(0xFF181B20),
    surfaceSecondary = Color(0xFF1C1F25),
    surfaceSealed = Palette.Graphite,
    onSealed = Palette.WarmIvory,
    onSealedSecondary = Color(0xFFA3A8B0),
    textPrimary = Palette.WarmIvory,
    textSecondary = Color(0xFFAEB3BB),
    textTertiary = Color(0xFF8E949D),
    actionPrimary = Palette.SignalLime,
    onActionPrimary = Palette.Ink,
    accent = Palette.SignalLime,
    onAccent = Palette.Ink,
    statusVerified = Color(0xFF5CC491),
    statusPending = Color(0xFFE2A54A),
    statusFailed = Color(0xFFE5737C),
    statusInfo = Color(0xFF86A5EF),
    borderSubtle = Palette.WarmIvory.copy(alpha = 0.08f),
    borderStrong = Palette.WarmIvory.copy(alpha = 0.18f),
    focusRing = Color(0xFF86A5EF),
    chrome = Color(0xFF181B20).copy(alpha = 0.94f),
)

private fun AnzenColors.highContrast() = copy(
    textSecondary = textPrimary.copy(alpha = 0.86f),
    textTertiary = textPrimary.copy(alpha = 0.78f),
    borderSubtle = textPrimary.copy(alpha = 0.22f),
    borderStrong = textPrimary.copy(alpha = 0.40f),
    chrome = backgroundElevated,
)

object Spacing {
    val xs = 4.dp
    val s = 8.dp
    val m = 12.dp
    val l = 16.dp
    val xl = 24.dp
    val xxl = 32.dp
    val gutter = 20.dp
}

/** Three radii only. */
object Radius {
    val small = RoundedCornerShape(10.dp)
    val medium = RoundedCornerShape(16.dp)
    val large = RoundedCornerShape(24.dp)
}

/** Durations (ms) and the one easing curve. Motion confirms an action; nothing loops for decoration. */
object Motion {
    const val micro = 140
    const val standard = 240
    const val page = 300
    val easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)
}

private const val TNUM = "tnum"
private val Sans = FontFamily.Default

/** Type scale. Amounts and counts use tabular numerals. */
object AnzenType {
    val largeTitle = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 32.sp, lineHeight = 38.sp, letterSpacing = (-0.6).sp)
    val title = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 28.sp, letterSpacing = (-0.3).sp)
    val headline = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, lineHeight = 22.sp)
    val value = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 34.sp, lineHeight = 40.sp, letterSpacing = (-0.8).sp, fontFeatureSettings = TNUM)
    val valueSmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, lineHeight = 22.sp, fontFeatureSettings = TNUM)
    val body = TextStyle(fontFamily = Sans, fontSize = 16.sp, lineHeight = 23.sp)
    val callout = TextStyle(fontFamily = Sans, fontSize = 15.sp, lineHeight = 21.sp)
    val caption = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 18.sp, fontFeatureSettings = TNUM)
    val overline = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.8.sp)
    val mono = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 19.sp)
    val label = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 20.sp)
}

val LocalAnzenColors = staticCompositionLocalOf { Light }
val LocalReducedMotion = staticCompositionLocalOf { false }

object Anzen {
    val colors: AnzenColors @Composable @ReadOnlyComposable get() = LocalAnzenColors.current
    val reducedMotion: Boolean @Composable @ReadOnlyComposable get() = LocalReducedMotion.current
}

/** Platform haptics: CONFIRM / REJECT are distinct on Android 11+ (the app's minimum). */
class Haptics(private val view: View) {
    fun confirm() = view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
    fun reject() = view.performHapticFeedback(HapticFeedbackConstants.REJECT)
    fun tick() = view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
}

@Composable
fun rememberHaptics(): Haptics {
    val view = LocalView.current
    return remember(view) { Haptics(view) }
}

@Composable
fun AnzenTheme(appearance: String = "system", content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val dark = when (appearance) {
        "light" -> false
        "dark" -> true
        else -> isSystemInDarkTheme()
    }
    val reduced = remember {
        Settings.Global.getFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
    val highContrast = remember {
        Build.VERSION.SDK_INT >= 34 && (ctx.getSystemService(UiModeManager::class.java)?.contrast ?: 0f) > 0.4f
    }
    val colors = (if (dark) Dark else Light).let { if (highContrast) it.highContrast() else it }
    val scheme = if (dark) darkColorScheme(
        primary = colors.actionPrimary, onPrimary = colors.onActionPrimary, background = colors.backgroundPrimary,
        onBackground = colors.textPrimary, surface = colors.backgroundElevated, onSurface = colors.textPrimary,
        surfaceVariant = colors.surfaceSecondary, onSurfaceVariant = colors.textSecondary, outline = colors.borderStrong,
        outlineVariant = colors.borderSubtle, error = colors.statusFailed, surfaceContainerHigh = colors.backgroundElevated,
        surfaceContainerLow = colors.backgroundElevated, surfaceContainer = colors.backgroundElevated,
    ) else lightColorScheme(
        primary = colors.actionPrimary, onPrimary = colors.onActionPrimary, background = colors.backgroundPrimary,
        onBackground = colors.textPrimary, surface = colors.backgroundElevated, onSurface = colors.textPrimary,
        surfaceVariant = colors.surfaceSecondary, onSurfaceVariant = colors.textSecondary, outline = colors.borderStrong,
        outlineVariant = colors.borderSubtle, error = colors.statusFailed, surfaceContainerHigh = colors.backgroundElevated,
        surfaceContainerLow = colors.backgroundElevated, surfaceContainer = colors.backgroundElevated,
    )
    CompositionLocalProvider(LocalAnzenColors provides colors, LocalReducedMotion provides reduced) {
        MaterialTheme(
            colorScheme = scheme,
            typography = Typography(bodyLarge = AnzenType.body, bodyMedium = AnzenType.callout, labelLarge = AnzenType.label,
                titleLarge = AnzenType.title, titleMedium = AnzenType.headline),
            content = content,
        )
    }
}
