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
 * The visual language follows the compact reference layout used by the proxy video:
 * warm neutral canvas, flat light cards, muted brick/coral accent and one-layer surfaces.
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
        pageBackground = Color(0xFFF3F2EF),
        cardBackground = Color(0xFFFCFCFA),
        elevatedCardBackground = Color(0xFFF0EFEC),
        textPrimary = Color(0xFF1D1C1A),
        textSecondary = Color(0xFF72706C),
        success = Color(0xFF2C8662),
        warning = Color(0xFF9A6B28),
        danger = Color(0xFFB44835),
        outline = Color(0xFFE0DDD8),
        controlBackground = Color(0xFFF0EFEC),
        selectionBackground = Color(0xFFF8D8CF),
    )
}

private val BichenShapes = Shapes(
    extraSmall = RoundedCornerShape(7.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(15.dp),
    large = RoundedCornerShape(18.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

private val BichenTypography = Typography(
    displaySmall = TextStyle(fontSize = 32.sp, lineHeight = 38.sp, fontWeight = FontWeight.Bold),
    headlineLarge = TextStyle(fontSize = 28.sp, lineHeight = 35.sp, fontWeight = FontWeight.Bold),
    headlineMedium = TextStyle(fontSize = 24.sp, lineHeight = 31.sp, fontWeight = FontWeight.Bold),
    headlineSmall = TextStyle(fontSize = 21.sp, lineHeight = 27.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontSize = 20.sp, lineHeight = 27.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 23.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = TextStyle(fontSize = 14.5.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 15.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 17.sp),
    labelLarge = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold),
    labelMedium = TextStyle(fontSize = 11.5.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 10.5.sp, lineHeight = 15.sp, fontWeight = FontWeight.Medium, letterSpacing = .15.sp),
)

@Composable
fun BichenTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val prefs = context.getSharedPreferences("bichen", 0)
    val appearance = prefs.getString("appearance", "system") ?: "system"
    val dark = appearance == "dark" || (appearance == "system" && isSystemInDarkTheme())

    // Keep the product identity stable instead of inheriting an arbitrary device accent.
    val seed = Color(0xFFB5533D)

    DynamicMaterialTheme(
        seedColor = seed,
        useDarkTheme = dark,
        style = PaletteStyle.TonalSpot,
        shapes = BichenShapes,
        typography = BichenTypography,
        animate = true,
    ) {
        val scheme = MaterialTheme.colorScheme
        val page = if (dark) Color(0xFF141311) else Color(0xFFF3F2EF)
        val card = if (dark) Color(0xFF201E1C) else Color(0xFFFCFCFA)
        val elevated = if (dark) Color(0xFF292624) else Color(0xFFF0EFEC)
        val selected = if (dark) lerp(card, Color(0xFF8D4334), .46f) else Color(0xFFF8D8CF)
        val tokens = BichenTokens(
            pageBackground = page,
            cardBackground = card,
            elevatedCardBackground = elevated,
            textPrimary = if (dark) Color(0xFFF1EEEA) else Color(0xFF1D1C1A),
            textSecondary = if (dark) Color(0xFFB7B0AA) else Color(0xFF72706C),
            success = if (dark) Color(0xFF70CDA7) else Color(0xFF2C8662),
            warning = if (dark) Color(0xFFE3B96F) else Color(0xFF9A6B28),
            danger = if (dark) Color(0xFFFFA997) else Color(0xFFB44835),
            outline = if (dark) Color(0xFF3B3733) else Color(0xFFE0DDD8),
            controlBackground = elevated,
            selectionBackground = selected,
        )
        CompositionLocalProvider(LocalBichenTokens provides tokens, content = content)
    }
}
