package io.github.xgl34222220.hetu.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.materialkolor.DynamicMaterialTheme
import com.materialkolor.PaletteStyle

/** Hetu semantic tokens shared by proxy and ad-block surfaces. */
@Immutable
data class HetuTokens(
    val pageBackground: Color,
    val cardBackground: Color,
    val elevatedCardBackground: Color,
    val heroBackground: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textMuted: Color,
    val success: Color,
    val warning: Color,
    val danger: Color,
    val outline: Color = Color.Unspecified,
    val controlBackground: Color = Color.Unspecified,
    val selectionBackground: Color = Color.Unspecified,
    val textOnPage: Color = Color(0xFF626B76),
    val successContainer: Color = Color(0xFFE6F7ED),
    val warningContainer: Color = Color(0xFFFFF4DB),
    val dangerContainer: Color = Color(0xFFFDECEE),
)

val LocalHetuTokens = staticCompositionLocalOf {
    HetuTokens(
        pageBackground = Color(0xFFF4F5F7),
        cardBackground = Color(0xFFFFFFFF),
        elevatedCardBackground = Color(0xFFF1F5F9),
        heroBackground = Color(0xFFEDF4FF),
        textPrimary = Color(0xFF111827),
        textSecondary = Color(0xFF71767F),
        textMuted = Color(0xFF71767F),
        success = Color(0xFF18794E),
        warning = Color(0xFF946200),
        danger = Color(0xFFC52A34),
        outline = Color(0xFFE2E8F0),
        controlBackground = Color(0xFFF1F5F9),
        selectionBackground = Color(0xFFDBEAFE),
    )
}

private val MiuixShapes = Shapes(
    extraSmall = RoundedCornerShape(9.dp),
    small = RoundedCornerShape(13.dp),
    medium = RoundedCornerShape(17.dp),
    large = RoundedCornerShape(21.dp),
    extraLarge = RoundedCornerShape(27.dp),
)

private val MaterialShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(18.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

private val ExpressiveShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

private val HetuTypography = Typography(
    displaySmall = TextStyle(fontSize = 30.sp, lineHeight = 36.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.4).sp),
    headlineLarge = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.35).sp),
    headlineMedium = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp),
    headlineSmall = TextStyle(fontSize = 20.sp, lineHeight = 27.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp),
    titleLarge = TextStyle(fontSize = 19.sp, lineHeight = 25.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = TextStyle(fontSize = 14.5.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 15.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 12.5.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold),
    labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 17.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 12.sp, lineHeight = 17.sp, fontWeight = FontWeight.Normal),
)

private fun paletteStyle(raw: String): PaletteStyle = when (raw) {
    "Neutral" -> PaletteStyle.Neutral
    "Vibrant" -> PaletteStyle.Vibrant
    "Expressive" -> PaletteStyle.Expressive
    "Rainbow" -> PaletteStyle.Rainbow
    "FruitSalad" -> PaletteStyle.FruitSalad
    "Monochrome" -> PaletteStyle.Monochrome
    "Fidelity" -> PaletteStyle.Fidelity
    else -> PaletteStyle.TonalSpot
}

private fun accentColor(raw: String, dark: Boolean): Color {
    val fallback = if (dark) Color(0xFF3B82F6) else Color(0xFF2563EB)
    return runCatching {
        val parsed = android.graphics.Color.parseColor(raw.ifBlank { if (dark) "#3B82F6" else "#2563EB" })
        Color(parsed)
    }.getOrDefault(fallback)
}

@Composable
fun HetuTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val prefs = context.getSharedPreferences("hetu", 0)
    val appearance = prefs.getString("appearance", "system") ?: "system"
    val dark = appearance == "dark" || (appearance == "system" && isSystemInDarkTheme())
    val pureBlack = dark && prefs.getBoolean("pureBlackDark", false)
    val enableMonet = prefs.getBoolean("enableMonet", false)
    val style = prefs.getString("uiStyle", "Miuix") ?: "Miuix"
    val standard = prefs.getString("colorStandard", "Material3_2021") ?: "Material3_2021"
    val scale = prefs.getFloat("uiScale", 1f).coerceIn(.8f, 1.2f)
    val shapes = when {
        standard == "Material3_Expressive_2025" -> ExpressiveShapes
        style == "Material" -> MaterialShapes
        else -> MiuixShapes
    }

    val fixedPrimary = accentColor(prefs.getString("accentHex", if (dark) "#3B82F6" else "#2563EB") ?: "#2563EB", dark)
    val baseLight = lightColorScheme(
        primary = fixedPrimary,
        primaryContainer = Color(0xFFDBEAFE),
        secondary = fixedPrimary,
        background = Color(0xFFF4F5F7),
        surface = Color.White,
        error = Color(0xFFC52A34),
        onBackground = Color(0xFF111827),
        onSurface = Color(0xFF111827),
    )
    val baseDark = darkColorScheme(
        primary = fixedPrimary,
        primaryContainer = Color(0xFF1E3A8A),
        secondary = fixedPrimary,
        background = if (pureBlack) Color.Black else Color(0xFF121212),
        surface = Color(0xFF1E1E1E),
        error = Color(0xFFF87171),
        onBackground = Color(0xFFF8FAFC),
        onSurface = Color(0xFFF8FAFC),
    )

    val motionEnabled = rememberHetuMotionEnabled()
    val density = LocalDensity.current
    // Density already scales dp and sp. Multiplying fontScale too applied the app
    // size twice to text and caused clipping at larger sizes. Preserve the user's
    // system accessibility font scale independently.
    val scaledDensity = Density(density.density * scale, density.fontScale)

    @Composable
    fun ProvideTokens(inner: @Composable () -> Unit) {
        val scheme = MaterialTheme.colorScheme
        val tokens = if (dark) {
            HetuTokens(
                pageBackground = if (pureBlack) Color.Black else Color(0xFF121212),
                cardBackground = Color(0xFF1E1E1E),
                elevatedCardBackground = Color(0xFF242424),
                heroBackground = Color(0xFF172338),
                textPrimary = Color(0xFFF8FAFC),
                textSecondary = Color(0xFFB4BDCA),
                textMuted = Color(0xFFADB7C4),
                success = Color(0xFF89DCAE),
                warning = Color(0xFFFBBF24),
                danger = Color(0xFFF87171),
                outline = Color(0xFF30343B),
                controlBackground = Color(0xFF24272D),
                selectionBackground = scheme.primaryContainer.copy(alpha = .55f),
                textOnPage = Color(0xFFB4BDCA),
                successContainer = Color(0xFF17382B),
                warningContainer = Color(0xFF3C311E),
                dangerContainer = Color(0xFF43232A),
            )
        } else {
            HetuTokens(
                pageBackground = Color(0xFFF4F5F7),
                cardBackground = Color(0xFFFFFFFF),
                elevatedCardBackground = Color(0xFFF1F5F9),
                heroBackground = Color(0xFFEDF4FF),
                textPrimary = Color(0xFF111827),
                textSecondary = Color(0xFF71767F),
                textMuted = Color(0xFF71767F),
                success = Color(0xFF18794E),
                warning = Color(0xFF946200),
                danger = Color(0xFFC52A34),
                outline = Color(0xFFE2E8F0),
                controlBackground = Color(0xFFF1F5F9),
                selectionBackground = scheme.primaryContainer.copy(alpha = .72f),
            )
        }
        CompositionLocalProvider(
            LocalHetuTokens provides tokens,
            LocalHetuMotionEnabled provides motionEnabled,
            LocalDensity provides scaledDensity,
            content = { CrystalEnvironment(content = inner) },
        )
    }

    if (enableMonet && Build.VERSION.SDK_INT >= 31) {
        MaterialTheme(
            colorScheme = if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context),
            shapes = shapes,
            typography = HetuTypography,
        ) {
            ProvideTokens(content)
        }
    } else if ((prefs.getString("accentHex", "#2563EB") ?: "#2563EB") == "#2563EB" && (prefs.getString("colorPalette", "TonalSpot") ?: "TonalSpot") == "TonalSpot") {
        MaterialTheme(
            colorScheme = if (dark) baseDark else baseLight,
            shapes = shapes,
            typography = HetuTypography,
        ) {
            ProvideTokens(content)
        }
    } else {
        DynamicMaterialTheme(
            seedColor = fixedPrimary,
            useDarkTheme = dark,
            style = paletteStyle(prefs.getString("colorPalette", "TonalSpot") ?: "TonalSpot"),
            shapes = shapes,
            typography = HetuTypography,
            animate = motionEnabled,
        ) {
            ProvideTokens(content)
        }
    }
}
