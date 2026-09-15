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
)

val LocalBichenTokens = staticCompositionLocalOf {
    BichenTokens(
        pageBackground = Color(0xFFF4F6FA),
        cardBackground = Color.White,
        elevatedCardBackground = Color.White,
        textPrimary = Color(0xFF16171B),
        textSecondary = Color(0xFF70727C),
        success = Color(0xFF187B58),
        warning = Color(0xFF956319),
        danger = Color(0xFFA34B39),
    )
}

private val MiuixShapes = Shapes(
    extraSmall = RoundedCornerShape(7.dp),
    small = RoundedCornerShape(11.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(30.dp),
)

private val MiuixTypography = Typography(
    displaySmall = TextStyle(fontSize = 34.sp, lineHeight = 39.sp, fontWeight = FontWeight.Bold),
    headlineLarge = TextStyle(fontSize = 30.sp, lineHeight = 38.sp, fontWeight = FontWeight.Bold),
    headlineMedium = TextStyle(fontSize = 26.sp, lineHeight = 34.sp, fontWeight = FontWeight.Bold),
    headlineSmall = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold),
    titleLarge = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold),
    titleMedium = TextStyle(fontSize = 17.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = TextStyle(fontSize = 15.sp, lineHeight = 21.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 15.sp, lineHeight = 23.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 21.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Bold),
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
    } else Color(0xFF3975D7)
    DynamicMaterialTheme(
        seedColor = seed,
        useDarkTheme = dark,
        style = PaletteStyle.TonalSpot,
        shapes = MiuixShapes,
        typography = MiuixTypography,
        animate = true,
    ) {
        val scheme = MaterialTheme.colorScheme
        val tokens = BichenTokens(
            pageBackground = if (dark) scheme.surfaceContainerLowest else lerp(Color(0xFFF4F6FA), scheme.primaryContainer, .07f),
            cardBackground = if (dark) scheme.surfaceContainerLow else scheme.surfaceContainerLowest,
            elevatedCardBackground = if (dark) scheme.surfaceContainerHigh else lerp(scheme.surfaceContainerLowest, scheme.primaryContainer, .20f),
            textPrimary = scheme.onSurface,
            textSecondary = scheme.onSurfaceVariant,
            success = if (dark) Color(0xFF69D9AD) else Color(0xFF187B58),
            warning = if (dark) Color(0xFFF3C378) else Color(0xFF956319),
            danger = if (dark) Color(0xFFFFB09D) else Color(0xFFA34B39),
        )
        CompositionLocalProvider(LocalBichenTokens provides tokens, content = content)
    }
}
