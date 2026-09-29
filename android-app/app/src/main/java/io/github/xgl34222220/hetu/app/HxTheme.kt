package io.github.xgl34222220.hetu

import android.app.Activity
import android.os.Build
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

/**
 * Hetu design language.
 *
 * One calm neutral canvas, white (or charcoal) surfaces, a single jade accent and
 * three semantic colours. Everything else is derived from these tokens so that every
 * page looks like it belongs to the same app.
 */
@Immutable
internal data class HxColors(
    val canvas: Color,
    val surface: Color,
    val surfaceMuted: Color,
    val line: Color,
    val text: Color,
    val textMuted: Color,
    val textFaint: Color,
    val accent: Color,
    val accentSoft: Color,
    val onAccent: Color,
    val good: Color,
    val goodSoft: Color,
    val warn: Color,
    val warnSoft: Color,
    val bad: Color,
    val badSoft: Color,
    val dark: Boolean,
)

private val LightHx = HxColors(
    canvas = Color(0xFFF4F5F7),
    surface = Color(0xFFFFFFFF),
    surfaceMuted = Color(0xFFEEF0F3),
    line = Color(0xFFE4E7EB),
    text = Color(0xFF12161A),
    textMuted = Color(0xFF5D6670),
    textFaint = Color(0xFF98A1AA),
    accent = Color(0xFF12806F),
    accentSoft = Color(0xFFDDF1EC),
    onAccent = Color(0xFFFFFFFF),
    good = Color(0xFF16A34A),
    goodSoft = Color(0xFFDCF5E4),
    warn = Color(0xFFD97706),
    warnSoft = Color(0xFFFCEFD9),
    bad = Color(0xFFDC2626),
    badSoft = Color(0xFFFCE4E4),
    dark = false,
)

private val DarkHx = HxColors(
    canvas = Color(0xFF0D1012),
    surface = Color(0xFF171B1E),
    surfaceMuted = Color(0xFF20262A),
    line = Color(0xFF283035),
    text = Color(0xFFE9EDEF),
    textMuted = Color(0xFF9BA5AC),
    textFaint = Color(0xFF69737A),
    accent = Color(0xFF5CCFBC),
    accentSoft = Color(0xFF15403A),
    onAccent = Color(0xFF00302A),
    good = Color(0xFF4ADE80),
    goodSoft = Color(0xFF123522),
    warn = Color(0xFFFBBF24),
    warnSoft = Color(0xFF3A2C0E),
    bad = Color(0xFFF87171),
    badSoft = Color(0xFF3E1717),
    dark = true,
)

internal val LocalHx = staticCompositionLocalOf { LightHx }

internal object Hx {
    val colors: HxColors
        @Composable get() = LocalHx.current

    /** Spacing scale – every gap in the app is one of these. */
    val gutter = 16.dp
    val gap = 12.dp
    val gapSmall = 8.dp

    val cardShape = RoundedCornerShape(20.dp)
    val rowShape = RoundedCornerShape(14.dp)
    val chipShape = RoundedCornerShape(10.dp)
    val pillShape = RoundedCornerShape(50)
}

/** Motion tokens. Short, decelerating, never bouncy on layout. */
internal object HxMotion {
    val Emphasized = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    val Standard = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    val Exit = CubicBezierEasing(0.3f, 0f, 1f, 1f)
    const val Short = 160
    const val Medium = 260
    const val Long = 360

    fun <T> enter(duration: Int = Medium): FiniteAnimationSpec<T> = tween(duration, easing = Emphasized)
    fun <T> exit(duration: Int = Short): FiniteAnimationSpec<T> = tween(duration, easing = Exit)
    fun <T> press(): FiniteAnimationSpec<T> = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)
}

private val HxShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

private fun hxTypography(): Typography {
    val base = Typography()
    return base.copy(
        displaySmall = base.displaySmall.copy(fontWeight = FontWeight.SemiBold, fontSize = 34.sp, lineHeight = 40.sp),
        headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.Bold, fontSize = 28.sp, lineHeight = 34.sp),
        headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 28.sp),
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 26.sp),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp),
        titleSmall = base.titleSmall.copy(fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp),
        bodyLarge = base.bodyLarge.copy(fontSize = 15.sp, lineHeight = 22.sp),
        bodyMedium = base.bodyMedium.copy(fontSize = 14.sp, lineHeight = 20.sp),
        bodySmall = base.bodySmall.copy(fontSize = 12.sp, lineHeight = 16.sp),
        labelLarge = base.labelLarge.copy(fontWeight = FontWeight.SemiBold, fontSize = 14.sp),
        labelMedium = base.labelMedium.copy(fontWeight = FontWeight.Medium, fontSize = 12.sp),
        labelSmall = base.labelSmall.copy(fontWeight = FontWeight.Medium, fontSize = 11.sp),
    )
}

/** Tabular figures keep changing numbers (speed, latency, timers) from jittering. */
internal val HxNumberStyle = TextStyle(fontFeatureSettings = "tnum")

private fun schemeFrom(c: HxColors): ColorScheme = if (c.dark) darkColorScheme(
    primary = c.accent,
    onPrimary = c.onAccent,
    primaryContainer = c.accentSoft,
    onPrimaryContainer = c.text,
    secondary = c.accent,
    onSecondary = c.onAccent,
    secondaryContainer = c.accentSoft,
    onSecondaryContainer = c.text,
    background = c.canvas,
    onBackground = c.text,
    surface = c.surface,
    onSurface = c.text,
    surfaceVariant = c.surfaceMuted,
    onSurfaceVariant = c.textMuted,
    surfaceContainerLowest = c.canvas,
    surfaceContainerLow = c.surface,
    surfaceContainer = c.surface,
    surfaceContainerHigh = c.surfaceMuted,
    surfaceContainerHighest = c.surfaceMuted,
    outline = c.line,
    outlineVariant = c.line,
    error = c.bad,
    errorContainer = c.badSoft,
) else lightColorScheme(
    primary = c.accent,
    onPrimary = c.onAccent,
    primaryContainer = c.accentSoft,
    onPrimaryContainer = c.text,
    secondary = c.accent,
    onSecondary = c.onAccent,
    secondaryContainer = c.accentSoft,
    onSecondaryContainer = c.text,
    background = c.canvas,
    onBackground = c.text,
    surface = c.surface,
    onSurface = c.text,
    surfaceVariant = c.surfaceMuted,
    onSurfaceVariant = c.textMuted,
    surfaceContainerLowest = c.surface,
    surfaceContainerLow = c.surface,
    surfaceContainer = c.surface,
    surfaceContainerHigh = c.surfaceMuted,
    surfaceContainerHighest = c.surfaceMuted,
    outline = c.line,
    outlineVariant = c.line,
    error = c.bad,
    errorContainer = c.badSoft,
)

/**
 * @param appearance "system" | "light" | "dark"
 * @param dynamic use the wallpaper accent (Android 12+) instead of jade.
 */
@Composable
internal fun HetuAppTheme(appearance: String, dynamic: Boolean, accentHex: String = "", content: @Composable () -> Unit) {
    val dark = when (appearance) {
        "dark" -> true
        "light" -> false
        else -> isSystemInDarkTheme()
    }
    val base = if (dark) DarkHx else LightHx
    val context = LocalContext.current
    val colors = if (dynamic && Build.VERSION.SDK_INT >= 31) {
        val wall = if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        base.copy(accent = wall.primary, accentSoft = wall.primaryContainer, onAccent = wall.onPrimary)
    } else if (accentHex.isNotBlank()) {
        val custom = runCatching { Color(android.graphics.Color.parseColor(accentHex)) }.getOrNull()
        if (custom == null) base else base.copy(
            accent = custom,
            accentSoft = androidx.compose.ui.graphics.lerp(custom, base.surface, if (dark) .78f else .86f),
            onAccent = Color.White,
        )
    } else base

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window
            if (window != null) {
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }
        }
    }

    CompositionLocalProvider(LocalHx provides colors) {
        MaterialTheme(
            colorScheme = schemeFrom(colors),
            typography = hxTypography(),
            shapes = HxShapes,
            content = content,
        )
    }
}
