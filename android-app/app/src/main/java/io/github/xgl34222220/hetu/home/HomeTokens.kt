package io.github.xgl34222220.hetu.home

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Design tokens of the v20.48 prototype (Linear / shadcn style): neutral canvas, white cards,
 * 1 px hairlines instead of shadows, three text levels, one accent and three semantic colours.
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
)

/** Eight accent presets. Blue is the prototype default; Jade is the previous V20 source default. */
internal enum class HomeAccent(val label: String, val hex: String, val light: Color, val dark: Color) {
    Blue("蓝", "#2160F3", Color(0xFF2160F3), Color(0xFF7AA2FF)),
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

/**
 * @param accent resolved accent colour for the current brightness (use [HomeAccent.color]).
 * @param pureBlack OLED canvas; only applied when [dark] is true.
 */
internal fun homeColors(
    dark: Boolean = false,
    accent: Color = HomeAccent.Default.color(dark),
    pureBlack: Boolean = false,
): HomeColors = if (!dark) {
    val surface = Color(0xFFFFFFFF)
    val good = Color(0xFF16A34A)
    val warn = Color(0xFFC2700A)
    val bad = Color(0xFFDC2626)
    HomeColors(
        bg = Color(0xFFF6F7F8),
        surface = surface,
        sunken = Color(0xFFEFF1F3),
        line = Color(0xFF12161A).copy(alpha = .08f),
        line2 = Color(0xFF12161A).copy(alpha = .14f),
        t1 = Color(0xFF12161A),
        t2 = Color(0xFF5D6670),
        t3 = Color(0xFF98A1AA),
        accent = accent,
        onAccent = Color(0xFFFFFFFF),
        accentSoft = lerp(surface, accent, .09f),
        good = good,
        goodSoft = lerp(surface, good, .10f),
        warn = warn,
        warnSoft = lerp(surface, warn, .11f),
        bad = bad,
        badSoft = lerp(surface, bad, .09f),
        scrim = Color(0xFF0C0E10).copy(alpha = .36f),
        dark = false,
    )
} else {
    val surface = if (pureBlack) Color(0xFF0F1214) else Color(0xFF171B1E)
    val good = Color(0xFF4ADE80)
    val warn = Color(0xFFFBBF24)
    val bad = Color(0xFFF87171)
    HomeColors(
        bg = if (pureBlack) Color(0xFF000000) else Color(0xFF0D1012),
        surface = surface,
        sunken = if (pureBlack) Color(0xFF191D20) else Color(0xFF20262A),
        line = Color.White.copy(alpha = .07f),
        line2 = Color.White.copy(alpha = .13f),
        t1 = Color(0xFFE9EDEF),
        t2 = Color(0xFF9BA5AC),
        t3 = Color(0xFF69737A),
        accent = accent,
        onAccent = Color(0xFF0B1220),
        accentSoft = lerp(surface, accent, .16f),
        good = good,
        goodSoft = lerp(surface, good, .14f),
        warn = warn,
        warnSoft = lerp(surface, warn, .13f),
        bad = bad,
        badSoft = lerp(surface, bad, .14f),
        scrim = Color.Black.copy(alpha = .56f),
        dark = true,
    )
}

internal val LocalHomeColors = staticCompositionLocalOf { homeColors() }

/** Spacing scale 4 · 8 · 12 · 16 · 24 · 32 and the radii used by the prototype. */
internal object HomeDims {
    val gutter = 16.dp
    val gap = 8.dp
    val cardPadding = 16.dp
    val rowMinHeight = 56.dp
    val rowMinHeightSmall = 48.dp
    val touch = 44.dp
    val barHeight = 52.dp
    val dockClearance = 116.dp

    val cardShape = RoundedCornerShape(16.dp)
    val controlShape = RoundedCornerShape(12.dp)
    val segmentShape = RoundedCornerShape(9.dp)
    val badgeShape = RoundedCornerShape(6.dp)
    val chipShape = RoundedCornerShape(4.dp)
    val sheetShape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    val menuShape = RoundedCornerShape(14.dp)
}

/** Type scale. Numbers always use tabular figures so live values do not jitter. */
internal object HomeType {
    private const val Tnum = "tnum"
    val largeTitle = TextStyle(fontSize = 28.sp, lineHeight = 36.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.56).sp)
    val barTitle = TextStyle(fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.17).sp)
    val barSubtitle = TextStyle(fontSize = 11.5.sp, lineHeight = 15.sp)
    val sheetTitle = TextStyle(fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.18).sp)
    val heroStatus = TextStyle(fontSize = 20.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp)
    val rowTitle = TextStyle(fontSize = 15.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium)
    val body = TextStyle(fontSize = 15.sp, lineHeight = 22.sp)
    val bodySmall = TextStyle(fontSize = 13.5.sp, lineHeight = 20.sp)
    val rowSub = TextStyle(fontSize = 12.5.sp, lineHeight = 17.sp, fontFeatureSettings = Tnum)
    val section = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium)
    val caption = TextStyle(fontSize = 12.sp, lineHeight = 16.sp)
    val note = TextStyle(fontSize = 13.sp, lineHeight = 18.sp)
    val noteStrong = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold)
    val label = TextStyle(fontSize = 14.sp, lineHeight = 20.sp)
    val badge = TextStyle(fontSize = 11.5.sp, lineHeight = 14.sp, fontWeight = FontWeight.Medium)
    val value = TextStyle(fontSize = 15.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold, fontFeatureSettings = Tnum)
    val metric = TextStyle(fontSize = 17.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold, fontFeatureSettings = Tnum)
    val metricLarge = TextStyle(fontSize = 28.sp, lineHeight = 36.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.56).sp, fontFeatureSettings = Tnum)
    val delay = TextStyle(fontSize = 12.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold, fontFeatureSettings = Tnum)
    val button = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium)
    val buttonSmall = TextStyle(fontSize = 13.5.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium)
    val mono = TextStyle(fontSize = 12.sp, lineHeight = 19.sp, fontFamily = FontFamily.Monospace)
    val regionCode = TextStyle(fontSize = 9.5.sp, lineHeight = 12.sp, fontFamily = FontFamily.Monospace, letterSpacing = .2.sp)
}

/** Motion tokens: short, decelerating, never bouncy. */
internal object HomeMotion {
    val Emphasized = CubicBezierEasing(.2f, 0f, 0f, 1f)
    const val PageMs = 320
    const val SwitchMs = 180
    const val BreathMs = 2800
    const val RippleMs = 1200
}

/**
 * Provides [LocalHomeColors]. It deliberately does not wrap MaterialTheme, so it can be nested
 * inside the app's existing HetuTheme without changing other screens.
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
    CompositionLocalProvider(LocalHomeColors provides colors, content = content)
}
