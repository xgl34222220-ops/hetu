package io.github.xgl34222220.hetu.home

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.xgl34222220.hetu.HomeContinuousShape

/**
 * Design tokens shared by 首页 and 面板 (V20.87 concept language).
 *
 * One lavender-tinted canvas, near-white cards with 24 dp continuous corners and no resting
 * shadows, three text levels, one accent and three semantic colours. The values are the same
 * ones the 工具 and 设置 pages already use, so the four tabs read as one app.
 */
@Immutable
internal data class HomeColors(
    val bg: Color,
    val surface: Color,
    val sunken: Color,
    val line: Color,
    val line2: Color,
    val t1: Color,
    val t2: Color,
    val t3: Color,
    val accent: Color,
    val onAccent: Color,
    val accentSoft: Color,
    val good: Color,
    val goodSoft: Color,
    val warn: Color,
    val warnSoft: Color,
    val bad: Color,
    val badSoft: Color,
    val scrim: Color,
    val dark: Boolean,
    /** Tinted surface behind the home status card. */
    val hero: Color = accentSoft,
    /** Menus, dialogs and sheets: one step above [surface]. */
    val raised: Color = surface,
)

/** Amber deep enough to be read as text on [HomeColors.warnSoft] or on a card. */
internal val HomeColors.warnText: Color get() = if (dark) warn else lerp(warn, t1, .30f)

/** Red deep enough to be read as text on [HomeColors.badSoft] or on a card. */
internal val HomeColors.badText: Color get() = if (dark) bad else lerp(bad, t1, .16f)

/** Green deep enough to be read as text on [HomeColors.goodSoft] or on a card. */
internal val HomeColors.goodText: Color get() = if (dark) good else lerp(good, t1, .12f)

/** Eight accent presets. Blue is the concept default. */
internal enum class HomeAccent(val label: String, val hex: String, val light: Color, val dark: Color) {
    Blue("蓝", "#0A62E8", Color(0xFF0A62E8), Color(0xFF7EA6FF)),
    Jade("青", "#12806F", Color(0xFF12806F), Color(0xFF5CCFBC)),
    Sky("天蓝", "#0284C7", Color(0xFF0284C7), Color(0xFF5CC4F5)),
    Indigo("靛", "#4F46E5", Color(0xFF4F46E5), Color(0xFF9A96FF)),
    Violet("紫", "#7C3AED", Color(0xFF7C3AED), Color(0xFFB79CFF)),
    Pink("粉", "#DB2777", Color(0xFFDB2777), Color(0xFFF58AB8)),
    Red("红", "#DC2626", Color(0xFFDC2626), Color(0xFFF87171)),
    Orange("橙", "#D97706", Color(0xFFD97706), Color(0xFFFBBF24));

    fun color(dark: Boolean): Color = if (dark) this.dark else light

    companion object {
        val Default = Blue

        /** Maps a stored `accentHex` preference to a preset; unknown or blank values return null. */
        fun fromHex(hex: String?): HomeAccent? {
            val key = hex?.trim().orEmpty()
            if (key.isEmpty()) return null
            return entries.firstOrNull { it.hex.equals(key, ignoreCase = true) }
        }
    }
}

/** White or deep navy, whichever reads better on [accent] (WCAG contrast ratio). */
internal fun homeOnAccent(accent: Color): Color {
    val luminance = accent.luminance()
    val onWhite = 1.05f / (luminance + .05f)
    val onNavy = (luminance + .05f) / (HomeOnAccentNavy.luminance() + .05f)
    return if (onWhite >= onNavy) Color.White else HomeOnAccentNavy
}

private val HomeOnAccentNavy = Color(0xFF0A1633)
private val DefaultLightAccent = Color(0xFF0A62E8)
private val DefaultDarkAccent = Color(0xFF7EA6FF)

/**
 * @param accent resolved accent colour for the current brightness (use [HomeAccent.color]).
 * @param pureBlack OLED canvas; only applied when [dark] is true.
 * @param onAccent content colour on accent fills; by default whichever of white and deep navy
 *        contrasts more with [accent], so light custom accents stay readable.
 */
internal fun homeColors(
    dark: Boolean = false,
    accent: Color = HomeAccent.Default.color(dark),
    pureBlack: Boolean = false,
    onAccent: Color? = null,
): HomeColors = if (!dark) {
    val surface = Color(0xFFF8F7FD)
    val t1 = Color(0xFF12161A)
    val good = Color(0xFF16A34A)
    val warn = Color(0xFFE08A0B)
    val bad = Color(0xFFE0393E)
    val accentSoft = lerp(surface, accent, .15f)
    HomeColors(
        bg = Color(0xFFECEEFB),
        surface = surface,
        sunken = Color(0xFFE9EBF7),
        line = t1.copy(alpha = .08f),
        line2 = t1.copy(alpha = .18f),
        t1 = t1,
        t2 = Color(0xFF5F6473),
        t3 = Color(0xFF989DAB),
        accent = accent,
        onAccent = onAccent ?: homeOnAccent(accent),
        accentSoft = accentSoft,
        good = good,
        goodSoft = lerp(surface, good, .14f),
        warn = warn,
        warnSoft = lerp(surface, warn, .17f),
        bad = bad,
        badSoft = lerp(surface, bad, .11f),
        scrim = Color(0xFF0C0E16).copy(alpha = .40f),
        dark = false,
        // The concept's status card is lavender under the default blue; other accents tint it themselves.
        hero = if (accent == DefaultLightAccent) Color(0xFFDEDDFC) else lerp(surface, accent, .16f),
        raised = Color(0xFFFBFAFE),
    )
} else {
    val surface = if (pureBlack) Color(0xFF111215) else Color(0xFF181A20)
    val good = Color(0xFF4ADE80)
    val warn = Color(0xFFFBBF24)
    val bad = Color(0xFFF87171)
    HomeColors(
        bg = if (pureBlack) Color(0xFF000000) else Color(0xFF0E0F13),
        surface = surface,
        sunken = if (pureBlack) Color(0xFF1B1D22) else Color(0xFF22252D),
        line = Color.White.copy(alpha = .07f),
        line2 = Color.White.copy(alpha = .16f),
        t1 = Color(0xFFEBEDF2),
        t2 = Color(0xFF9CA2B0),
        t3 = Color(0xFF6A7080),
        accent = accent,
        onAccent = onAccent ?: homeOnAccent(accent),
        accentSoft = lerp(surface, accent, .20f),
        good = good,
        goodSoft = lerp(surface, good, .16f),
        warn = warn,
        warnSoft = lerp(surface, warn, .15f),
        bad = bad,
        badSoft = lerp(surface, bad, .16f),
        scrim = Color.Black.copy(alpha = .58f),
        dark = true,
        hero = if (accent == DefaultDarkAccent) Color(0xFF22243C) else lerp(surface, accent, .15f),
        raised = if (pureBlack) Color(0xFF17191D) else Color(0xFF1E2128),
    )
}

internal val LocalHomeColors = staticCompositionLocalOf { homeColors() }

/**
 * Real-time blur for pinned bars. Off by default so previews and unit tests never touch a
 * RenderEffect; the launcher adapters provide the user's「模糊效果」switch.
 */
internal val LocalHomeBlur = staticCompositionLocalOf { false }

/** Spacing rhythm and radii measured from the concept: 14 dp gutters and gaps, 24 dp cards. */
internal object HomeDims {
    val gutter = 14.dp
    val gap = 14.dp
    val cardPadding = 18.dp
    val rowMinHeight = 64.dp
    val rowMinHeightSmall = 56.dp
    val touch = 48.dp
    val barHeight = 64.dp
    val dockClearance = 116.dp

    val cardShape: Shape = HomeContinuousShape(24.dp)
    /** Cards nested inside a card or a sheet (node tiles, grouped rows). */
    val innerShape: Shape = RoundedCornerShape(18.dp)
    val controlShape: Shape = RoundedCornerShape(16.dp)
    val segmentShape: Shape = RoundedCornerShape(13.dp)
    val badgeShape: Shape = RoundedCornerShape(10.dp)
    val chipShape: Shape = RoundedCornerShape(8.dp)
    val sheetShape: Shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    val menuShape: Shape = RoundedCornerShape(20.dp)
    val pillShape: Shape = RoundedCornerShape(50)
}

/** Type scale. Numbers always use tabular figures so live values do not jitter. */
internal object HomeType {
    private const val Tnum = "tnum"
    val largeTitle = TextStyle(fontSize = 36.sp, lineHeight = 44.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.4).sp)
    val barTitle = TextStyle(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.Bold)
    val barSubtitle = TextStyle(fontSize = 14.sp, lineHeight = 19.sp, fontWeight = FontWeight.Medium)
    val sheetTitle = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold)
    val heroStatus = TextStyle(fontSize = 26.sp, lineHeight = 32.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.2).sp)
    val heroLine = TextStyle(fontSize = 17.sp, lineHeight = 25.sp, fontWeight = FontWeight.Medium, fontFeatureSettings = Tnum)
    val rowTitle = TextStyle(fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.Bold)
    val body = TextStyle(fontSize = 16.sp, lineHeight = 23.sp)
    val bodySmall = TextStyle(fontSize = 14.sp, lineHeight = 21.sp)
    val rowSub = TextStyle(fontSize = 14.sp, lineHeight = 19.sp, fontFeatureSettings = Tnum)
    val section = TextStyle(fontSize = 20.sp, lineHeight = 27.sp, fontWeight = FontWeight.Bold)
    val cardLabel = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold)
    val caption = TextStyle(fontSize = 13.sp, lineHeight = 18.sp)
    val note = TextStyle(fontSize = 14.sp, lineHeight = 20.sp)
    val noteStrong = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold)
    val label = TextStyle(fontSize = 16.sp, lineHeight = 22.sp)
    val badge = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold)
    val value = TextStyle(fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold, fontFeatureSettings = Tnum)
    val metric = TextStyle(fontSize = 20.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold, fontFeatureSettings = Tnum)
    val metricLarge = TextStyle(fontSize = 40.sp, lineHeight = 46.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.6).sp, fontFeatureSettings = Tnum)
    val delay = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold, fontFeatureSettings = Tnum)
    val button = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold)
    val buttonSmall = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold)
    val control = TextStyle(fontSize = 19.sp, lineHeight = 26.sp, fontWeight = FontWeight.Bold)
    val mono = TextStyle(fontSize = 13.sp, lineHeight = 20.sp, fontFamily = FontFamily.Monospace)
    val regionCode = TextStyle(fontSize = 10.sp, lineHeight = 12.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace, letterSpacing = .2.sp)
}

/**
 * Motion tokens. Position and size settle on springs with at most a hint of overshoot;
 * opacity and colour always use the decelerating curve and never bounce.
 */
internal object HomeMotion {
    val Emphasized = CubicBezierEasing(.2f, 0f, 0f, 1f)
    val Decelerate = CubicBezierEasing(.05f, .7f, .1f, 1f)
    const val PageMs = 320
    const val SwitchMs = 180
    const val EnterMs = 360
    const val BreathMs = 2800
    const val RippleMs = 1200

    /** Colour and alpha changes. */
    fun <T> fade(motion: Boolean, ms: Int = SwitchMs): FiniteAnimationSpec<T> =
        if (motion) tween(ms, easing = Emphasized) else snap()

    /** Things that travel: indicators, thumbs, bars. Settles without a visible bounce. */
    fun <T> glide(motion: Boolean): FiniteAnimationSpec<T> =
        if (motion) spring(dampingRatio = .82f, stiffness = 420f) else snap()

    /** Small confirmations: check marks, badges, floating buttons. A touch of overshoot. */
    fun <T> pop(motion: Boolean): FiniteAnimationSpec<T> =
        if (motion) spring(dampingRatio = .62f, stiffness = 520f) else snap()

    /** Content arriving on screen. */
    fun <T> enter(motion: Boolean, ms: Int = EnterMs, delayMs: Int = 0): FiniteAnimationSpec<T> =
        if (motion) tween(ms, delayMillis = delayMs, easing = Decelerate) else snap()
}

/**
 * Provides [LocalHomeColors]. It deliberately does not wrap MaterialTheme, so it can be nested
 * inside the app's existing theme without changing other screens.
 */
@Composable
internal fun HetuHomeTheme(
    dark: Boolean = isSystemInDarkTheme(),
    accent: HomeAccent = HomeAccent.Default,
    pureBlack: Boolean = false,
    customAccent: Color? = null,
    content: @Composable () -> Unit,
) {
    val colors = homeColors(dark, customAccent ?: accent.color(dark), pureBlack)
    CompositionLocalProvider(
        LocalHomeColors provides colors,
        LocalHomeMotionEnabled provides homeMotionEnabled(),
        content = content,
    )
}
