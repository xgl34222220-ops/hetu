package io.github.xgl34222220.bichen.ui

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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.materialkolor.DynamicMaterialTheme
import com.materialkolor.PaletteStyle

/** Clean LuoShu / MIUI-inspired semantic tokens shared by Bichen surfaces. */
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
        pageBackground = Color(0xFFF5F7FB),
        cardBackground = Color(0xFFFFFFFF),
        elevatedCardBackground = Color(0xFFF0F3F8),
        textPrimary = Color(0xFF171A1F),
        textSecondary = Color(0xFF747B86),
        success = Color(0xFF2E956D),
        warning = Color(0xFFA87925),
        danger = Color(0xFFC54E4A),
        outline = Color(0xFFE3E7EE),
        controlBackground = Color(0xFFF0F3F8),
        selectionBackground = Color(0xFFE8EEFF),
    )
}

private val BichenShapes = Shapes(
    extraSmall = RoundedCornerShape(7.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(18.dp),
    extraLarge = RoundedCornerShape(22.dp),
)

private val BichenTypography = Typography(
    displaySmall = TextStyle(fontSize = 30.sp, lineHeight = 36.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.4).sp),
    headlineLarge = TextStyle(fontSize = 27.sp, lineHeight = 34.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.35).sp),
    headlineMedium = TextStyle(fontSize = 23.sp, lineHeight = 30.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp),
    headlineSmall = TextStyle(fontSize = 20.sp, lineHeight = 27.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp),
    titleLarge = TextStyle(fontSize = 19.sp, lineHeight = 25.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = TextStyle(fontSize = 14.5.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 15.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 12.5.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold),
    labelMedium = TextStyle(fontSize = 11.5.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 10.5.sp, lineHeight = 15.sp, fontWeight = FontWeight.Medium, letterSpacing = .1.sp),
)

@Composable
fun BichenTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val prefs = context.getSharedPreferences("bichen", 0)
    val appearance = prefs.getString("appearance", "system") ?: "system"
    val dark = appearance == "dark" || (appearance == "system" && isSystemInDarkTheme())

    // Stable cool accent: closer to modern MIUI/LuoShu than the previous brick-red palette.
    val seed = Color(0xFF5572F6)

    DynamicMaterialTheme(
        seedColor = seed,
        useDarkTheme = dark,
        style = PaletteStyle.TonalSpot,
        shapes = BichenShapes,
        typography = BichenTypography,
        animate = true,
    ) {
        val tokens = if (dark) {
            BichenTokens(
                pageBackground = Color(0xFF0F1115),
                cardBackground = Color(0xFF181B20),
                elevatedCardBackground = Color(0xFF20242B),
                textPrimary = Color(0xFFF3F5F7),
                textSecondary = Color(0xFFA7ADB7),
                success = Color(0xFF72D1A9),
                warning = Color(0xFFE3B96F),
                danger = Color(0xFFFFAAA5),
                outline = Color(0xFF2B3038),
                controlBackground = Color(0xFF22262D),
                selectionBackground = Color(0xFF283354),
            )
        } else {
            BichenTokens(
                pageBackground = Color(0xFFF5F7FB),
                cardBackground = Color(0xFFFFFFFF),
                elevatedCardBackground = Color(0xFFF0F3F8),
                textPrimary = Color(0xFF171A1F),
                textSecondary = Color(0xFF747B86),
                success = Color(0xFF2E956D),
                warning = Color(0xFFA87925),
                danger = Color(0xFFC54E4A),
                outline = Color(0xFFE3E7EE),
                controlBackground = Color(0xFFF0F3F8),
                selectionBackground = Color(0xFFE8EEFF),
            )
        }
        CompositionLocalProvider(LocalBichenTokens provides tokens, content = content)
    }
}
