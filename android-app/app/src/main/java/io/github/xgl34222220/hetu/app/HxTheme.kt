package io.github.xgl34222220.hetu

import android.app.Activity
import android.os.Build
import io.github.xgl34222220.hetu.ui.LocalHetuLanguage
import io.github.xgl34222220.hetu.ui.LocalHetuMotionEnabled
import io.github.xgl34222220.hetu.ui.LocalHetuGlassEffectsEnabled
import io.github.xgl34222220.hetu.ui.rememberHetuMotionEnabled
import io.github.xgl34222220.hetu.ui.rememberHetuPowerConstrained
import io.github.xgl34222220.hetu.ui.rememberHetuLanguage
import io.github.xgl34222220.hetu.ui.HetuHaptic
import io.github.xgl34222220.hetu.ui.rememberHetuHaptics
import io.github.xgl34222220.hetu.home.HomeHaptic
import io.github.xgl34222220.hetu.home.LocalHomeColors
import io.github.xgl34222220.hetu.home.LocalHomeHaptics
import io.github.xgl34222220.hetu.home.LocalHomeMotionEnabled
import io.github.xgl34222220.hetu.home.homeColors
import io.github.xgl34222220.hetu.home.homeMotionEnabled
import androidx.compose.runtime.remember
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
    canvas = Color(0xFFECEEFB),
    surface = Color(0xFFF8F7FD),
    surfaceMuted = Color(0xFFEEEFFA),
    line = Color(0xFFDCE0EC),
    text = Color(0xFF171A24),
    textMuted = Color(0xFF5F6473),
    textFaint = Color(0xFF989DAB),
    accent = Color(0xFF0A62E8),
    accentSoft = Color(0xFFDCE5FF),
    onAccent = Color(0xFFFFFFFF),
    good = Color(0xFF16A34A),
    goodSoft = Color(0xFFDDF5E5),
    warn = Color(0xFFD97706),
    warnSoft = Color(0xFFFCEFD9),
    bad = Color(0xFFE0393E),
    badSoft = Color(0xFFFDE6E6),
    dark = false,
)

private val DarkHx = HxColors(
    canvas = Color(0xFF0E0F13),
    surface = Color(0xFF181A20),
    surfaceMuted = Color(0xFF22252D),
    line = Color(0xFF2A2E37),
    text = Color(0xFFEBEDF2),
    textMuted = Color(0xFF9CA2B0),
    textFaint = Color(0xFF6A7080),
    accent = Color(0xFF7EA6FF),
    accentSoft = Color(0xFF1D2C52),
    onAccent = Color(0xFF0A1633),
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
    val gutter = 12.dp
    val gap = 10.dp
    val gapSmall = 7.dp

    val cardShape = RoundedCornerShape(18.dp)
    val rowShape = RoundedCornerShape(15.dp)
    val chipShape = RoundedCornerShape(11.dp)
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
    const val Route = 420

    fun <T> enter(duration: Int = Medium): FiniteAnimationSpec<T> = tween(duration, easing = Emphasized)
    fun <T> exit(duration: Int = Short): FiniteAnimationSpec<T> = tween(duration, easing = Exit)
    fun <T> press(): FiniteAnimationSpec<T> = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)

    /** Settles with a hint of overshoot – selection indicators, check marks, badges. */
    fun <T> pop(): FiniteAnimationSpec<T> = spring(dampingRatio = .62f, stiffness = Spring.StiffnessMediumLow)

    /** Calm spring for anything that moves in space (indicators, sliding content). */
    fun <T> glide(): FiniteAnimationSpec<T> = spring(dampingRatio = .86f, stiffness = 420f)
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
        displaySmall = base.displaySmall.copy(fontWeight = FontWeight.SemiBold, fontSize = 30.sp, lineHeight = 36.sp),
        headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.SemiBold, fontSize = 26.sp, lineHeight = 32.sp),
        headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 25.sp),
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold, fontSize = 18.sp, lineHeight = 23.sp),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 20.sp, letterSpacing = (-0.1).sp),
        titleSmall = base.titleSmall.copy(fontWeight = FontWeight.SemiBold, fontSize = 13.5.sp, lineHeight = 18.sp),
        bodyLarge = base.bodyLarge.copy(fontSize = 14.sp, lineHeight = 19.sp),
        bodyMedium = base.bodyMedium.copy(fontSize = 13.sp, lineHeight = 18.sp),
        bodySmall = base.bodySmall.copy(fontSize = 11.5.sp, lineHeight = 15.5.sp),
        labelLarge = base.labelLarge.copy(fontWeight = FontWeight.SemiBold, fontSize = 13.sp),
        labelMedium = base.labelMedium.copy(fontWeight = FontWeight.Medium, fontSize = 11.5.sp),
        labelSmall = base.labelSmall.copy(fontWeight = FontWeight.Medium, fontSize = 10.5.sp),
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
 * The custom accent as stored, blank for the default blues. For the few pages that are their
 * own activity and read the theme from preferences instead of the view model.
 */
internal fun hxStoredAccent(prefs: android.content.SharedPreferences): String = (prefs.getString("accentHex", "") ?: "")
    .takeUnless { it.equals("#2563EB", true) || it.equals("#3B82F6", true) || it.equals("#2A62E8", true) || it.equals("#7EA6FF", true) }
    .orEmpty()

/**
 * @param appearance "system" | "light" | "dark"
 * @param dynamic use the wallpaper accent (Android 12+) instead of jade.
 */
@Composable
internal fun HetuAppTheme(appearance: String, dynamic: Boolean, accentHex: String = "", pureBlack: Boolean = false, content: @Composable () -> Unit) {
    val dark = when (appearance) {
        "dark" -> true
        "light" -> false
        else -> isSystemInDarkTheme()
    }
    val base = when {
        dark && pureBlack -> DarkHx.copy(canvas = Color(0xFF000000), surface = Color(0xFF111215), surfaceMuted = Color(0xFF1B1D22), line = Color(0xFF24272E))
        dark -> DarkHx
        else -> LightHx
    }
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

    val language = rememberHetuLanguage()
    val constrained = rememberHetuPowerConstrained()
    val motion = LocalHetuMotionEnabled.current && rememberHetuMotionEnabled() && !constrained
    val glass = LocalHetuGlassEffectsEnabled.current && !constrained
    // The home design kit draws every page of the four tabs, so its palette, haptics and motion
    // switch are provided here once, derived from the same appearance and accent as [LocalHx].
    val palette = remember(colors.accent, dark, pureBlack) { homeColors(dark = dark, accent = colors.accent, pureBlack = dark && pureBlack) }
    // One palette for both vocabularies: whatever is still drawn with [Hx.colors] gets the very
    // colours the home kit uses. Lines stay opaque here because callers thin them with alpha.
    val unified = remember(palette) {
        colors.copy(
            canvas = palette.bg, surface = palette.surface, surfaceMuted = palette.sunken,
            line = androidx.compose.ui.graphics.lerp(palette.surface, palette.t1, if (dark) .11f else .09f),
            text = palette.t1, textMuted = palette.t2, textFaint = palette.t3,
            accentSoft = palette.accentSoft, onAccent = palette.onAccent,
            good = palette.good, goodSoft = palette.goodSoft, warn = palette.warn, warnSoft = palette.warnSoft,
            bad = palette.bad, badSoft = palette.badSoft,
        )
    }
    val hetuHaptics = rememberHetuHaptics()
    val homeHaptics = remember(hetuHaptics) {
        { kind: HomeHaptic ->
            hetuHaptics.perform(
                when (kind) {
                    HomeHaptic.Tap -> HetuHaptic.Tap
                    HomeHaptic.Tick -> HetuHaptic.Tick
                    HomeHaptic.Confirm -> HetuHaptic.Confirm
                    HomeHaptic.Reject -> HetuHaptic.Reject
                },
            )
        }
    }
    CompositionLocalProvider(
        LocalHx provides unified,
        LocalHetuLanguage provides language,
        LocalHomeColors provides palette,
        LocalHomeHaptics provides homeHaptics,
        LocalHetuMotionEnabled provides motion,
        LocalHomeMotionEnabled provides motion,
        LocalHetuGlassEffectsEnabled provides glass,
    ) {
        MaterialTheme(
            colorScheme = schemeFrom(unified),
            typography = hxTypography(),
            shapes = HxShapes,
            content = content,
        )
    }
}
