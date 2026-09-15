package io.github.xgl34222220.bichen.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.materialkolor.DynamicMaterialTheme
import com.materialkolor.PaletteStyle

/**
 * Semantic UI tokens for Bichen.
 *
 * The product is a privacy/network utility, so the visual hierarchy is intentionally calmer
 * than a decorative glass UI: tinted surfaces for structure, glass only for chrome/navigation,
 * and green/amber/red reserved for real state instead of decoration.
 */
@Immutable
data class BichenTokens(
    val pageBackground: Color,
    val cardBackground: Color,
    val elevatedCardBackground: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val success: Color,
    val warning: Color,
    val danger: Color,
    val outline: Color = Color.Unspecified,
    val controlBackground: Color = Color.Unspecified,
    val selectionBackground: Color = Color.Unspecified,
)

val LocalBichenTokens = staticCompositionLocalOf {
    BichenTokens(
        pageBackground = Color(0xFFF3F6FA),
        cardBackground = Color(0xFFFCFDFE),
        elevatedCardBackground = Color(0xFFF0F4F9),
        textPrimary = Color(0xFF161B22),
        textSecondary = Color(0xFF667085),
        success = Color(0xFF168A5B),
        warning = Color(0xFF9A6517),
        danger = Color(0xFFB93A38),
        outline = Color(0xFFD7DEE8),
        controlBackground = Color(0xFFF0F4F9),
        selectionBackground = Color(0xFFE7EFFB),
    )
}

private val BichenShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

private val BichenTypography = Typography(
    displaySmall = TextStyle(fontSize = 34.sp, lineHeight = 40.sp, fontWeight = FontWeight.Bold),
    headlineLarge = TextStyle(fontSize = 30.sp, lineHeight = 38.sp, fontWeight = FontWeight.Bold),
    headlineMedium = TextStyle(fontSize = 26.sp, lineHeight = 34.sp, fontWeight = FontWeight.Bold),
    headlineSmall = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 17.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = TextStyle(fontSize = 15.sp, lineHeight = 21.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 15.5.sp, lineHeight = 23.sp),
    bodyMedium = TextStyle(fontSize = 14.5.sp, lineHeight = 21.sp),
    bodySmall = TextStyle(fontSize = 12.5.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold),
    labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 17.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 11.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium, letterSpacing = .2.sp),
)

@Composable
fun BichenTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val prefs = context.getSharedPreferences("bichen", 0)
    val appearance = prefs.getString("appearance", "system") ?: "system"
    val dark = appearance == "dark" || (appearance == "system" && isSystemInDarkTheme())
    val seed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        Color(context.getColor(android.R.color.system_accent1_500))
    } else Color(0xFF315F8A)

    DynamicMaterialTheme(
        seedColor = seed,
        useDarkTheme = dark,
        style = PaletteStyle.TonalSpot,
        shapes = BichenShapes,
        typography = BichenTypography,
        animate = true,
    ) {
        val scheme = MaterialTheme.colorScheme

        // ui-ux-pro-max's VPN/privacy profile calls for a restrained, trust-oriented hierarchy.
        // Light mode therefore avoids giant pure-white slabs; dark mode stays close to OLED navy.
        val page = if (dark) {
            lerp(Color(0xFF0F172A), scheme.surface, .26f)
        } else {
            lerp(Color(0xFFF3F6FA), scheme.primaryContainer, .04f)
        }
        val card = if (dark) {
            lerp(Color(0xFF192134), scheme.surfaceContainerLow, .34f)
        } else {
            lerp(Color(0xFFFCFDFE), scheme.primaryContainer, .035f)
        }
        val elevated = if (dark) {
            lerp(Color(0xFF1E293B), scheme.surfaceContainerHigh, .30f)
        } else {
            lerp(Color(0xFFF0F4F9), scheme.primaryContainer, .08f)
        }
        val selected = lerp(card, scheme.primaryContainer, if (dark) .42f else .48f)
        val tokens = BichenTokens(
            pageBackground = page,
            cardBackground = card,
            elevatedCardBackground = elevated,
            textPrimary = scheme.onSurface,
            textSecondary = scheme.onSurfaceVariant,
            success = if (dark) Color(0xFF69D9AD) else Color(0xFF168A5B),
            warning = if (dark) Color(0xFFF1C27A) else Color(0xFF9A6517),
            danger = if (dark) Color(0xFFFFAAA2) else Color(0xFFB93A38),
            outline = scheme.outlineVariant.copy(alpha = if (dark) .58f else .48f),
            controlBackground = elevated,
            selectionBackground = selected,
        )
        CompositionLocalProvider(LocalBichenTokens provides tokens, content = content)
    }
}
