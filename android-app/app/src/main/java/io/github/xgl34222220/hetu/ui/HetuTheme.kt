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
import androidx.compose.runtime.*
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
    val textOnPage: Color = textSecondary,
    val successContainer: Color = success.copy(alpha = .10f),
    val warningContainer: Color = warning.copy(alpha = .10f),
    val dangerContainer: Color = danger.copy(alpha = .10f),
)

val LocalHetuTokens = staticCompositionLocalOf {
    HetuTokens(
        pageBackground = Color(0xFFF2F0F9),
        cardBackground = Color(0xFFFBFAFD),
        elevatedCardBackground = Color(0xFFF6F3FA),
        heroBackground = Color(0xFFEDE9F7),
        textPrimary = Color(0xFF12161A),
        textSecondary = Color(0xFF686C79),
        textMuted = Color(0xFFA4A6B0),
        success = Color(0xFF10B981),
        warning = Color(0xFFF59E0B),
        danger = Color(0xFFEF4444),
        outline = Color(0xFFE7E2EF),
        controlBackground = Color(0xFFF0ECF7),
        selectionBackground = Color(0xFFEAE5F5),
    )
}

private val MiuixShapes = Shapes(
    extraSmall = RoundedCornerShape(9.dp),
    small = RoundedCornerShape(13.dp),
    medium = RoundedCornerShape(15.dp),
    large = RoundedCornerShape(18.dp),
    extraLarge = RoundedCornerShape(24.dp),
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
    displaySmall = TextStyle(fontSize = 27.sp, lineHeight = 32.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.35).sp),
    headlineLarge = TextStyle(fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.3).sp),
    headlineMedium = TextStyle(fontSize = 21.sp, lineHeight = 27.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.25).sp),
    headlineSmall = TextStyle(fontSize = 18.5.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.15).sp),
    titleLarge = TextStyle(fontSize = 16.5.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 14.5.sp, lineHeight = 19.5.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 13.5.sp, lineHeight = 19.sp),
    bodyMedium = TextStyle(fontSize = 12.75.sp, lineHeight = 18.sp),
    bodySmall = TextStyle(fontSize = 11.5.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontSize = 12.5.sp, lineHeight = 17.sp, fontWeight = FontWeight.SemiBold),
    labelMedium = TextStyle(fontSize = 11.sp, lineHeight = 15.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 10.sp, lineHeight = 14.sp, fontWeight = FontWeight.Medium, letterSpacing = .08.sp),
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
    val fallback = if (dark) Color(0xFF7EA6FF) else Color(0xFF2A62E8)
    return runCatching {
        val parsed = android.graphics.Color.parseColor(raw.ifBlank { if (dark) "#7EA6FF" else "#2A62E8" })
        Color(parsed)
    }.getOrDefault(fallback)
}

@Composable
fun HetuTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val prefs = remember(context) { context.getSharedPreferences("hetu", 0) }
    var themeRevision by remember { mutableIntStateOf(0) }
    DisposableEffect(prefs) {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key in setOf("appearance", "appLanguage", "pureBlackDark", "enableMonet", "uiStyle", "colorStandard", "colorPalette", "uiScale", "accentHex", "enableAnimations")) themeRevision++
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    themeRevision
    val motionEnabled = rememberHetuMotionEnabled()
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

    // The legacy blue default is treated as "not customised" so every page shares the V20 jade accent.
    val storedAccent = (prefs.getString("accentHex", "") ?: "").takeUnless { it.equals("#2563EB", true) || it.equals("#3B82F6", true) }.orEmpty()
    val fixedPrimary = accentColor(storedAccent.ifBlank { if (dark) "#7EA6FF" else "#2A62E8" }, dark)
    val baseLight = lightColorScheme(
        primary = fixedPrimary,
        primaryContainer = Color(0xFFE3EAFD),
        secondary = Color(0xFF2A62E8),
        background = Color(0xFFF2F0F9),
        surface = Color(0xFFFBFAFD),
        error = Color(0xFFEF4444),
        onBackground = Color(0xFF12161A),
        onSurface = Color(0xFF12161A),
    )
    val baseDark = darkColorScheme(
        primary = fixedPrimary,
        primaryContainer = Color(0xFF1D2C52),
        secondary = Color(0xFF7EA6FF),
        background = if (pureBlack) Color.Black else Color(0xFF0D1012),
        surface = Color(0xFF171B1E),
        error = Color(0xFFF87171),
        onBackground = Color(0xFFF4F5F7),
        onSurface = Color(0xFFF4F5F7),
    )

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
                pageBackground = if (pureBlack) Color.Black else Color(0xFF0D1012),
                cardBackground = Color(0xFF171B1E),
                elevatedCardBackground = Color(0xFF20262A),
                heroBackground = Color(0xFF1D2C52),
                textPrimary = Color(0xFFF4F5F7),
                textSecondary = Color(0xFF98A1AA),
                textMuted = Color(0xFF5D6670),
                success = Color(0xFF34D399),
                warning = Color(0xFFFBBF24),
                danger = Color(0xFFF87171),
                outline = Color(0xFF283035),
                controlBackground = Color(0xFF20262A),
                selectionBackground = if (enableMonet) scheme.primaryContainer.copy(alpha = .55f) else Color(0xFF1D2C52),
            )
        } else {
            // V19.3: reference palette, sampled from the BoxProxy recording.
            HetuTokens(
                pageBackground = Color(0xFFF2F0F9),
                cardBackground = Color(0xFFFBFAFD),
                elevatedCardBackground = Color(0xFFF6F3FA),
                heroBackground = Color(0xFFEDE9F7),
                textPrimary = Color(0xFF12161A),
                textSecondary = Color(0xFF686C79),
                textMuted = Color(0xFFA4A6B0),
                success = Color(0xFF10B981),
                warning = Color(0xFFF59E0B),
                danger = Color(0xFFEF4444),
                outline = Color(0xFFE7E2EF),
                controlBackground = Color(0xFFF0ECF7),
                selectionBackground = if (enableMonet) scheme.primaryContainer.copy(alpha = .62f) else Color(0xFFEAE5F5),
            )
        }
        CompositionLocalProvider(
            LocalHetuTokens provides tokens,
            LocalHetuLanguage provides prefs.getString("appLanguage", "system").orEmpty(),
            LocalHetuMotionEnabled provides motionEnabled,
            LocalDensity provides scaledDensity,
            // Every host of this theme also gets the home design kit (palette, bar style, motion
            // switch, haptics), so a page drawn with it looks the same in any activity.
            content = { io.github.xgl34222220.hetu.home.HetuHomeKit(prefs) { io.github.xgl34222220.hetu.ImmersiveUiHost { inner() } } },
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
    } else if ((storedAccent.isBlank() || storedAccent.equals("#2A62E8", true)) && (prefs.getString("colorPalette", "TonalSpot") ?: "TonalSpot") == "TonalSpot") {
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
            isDark = dark,
            style = paletteStyle(prefs.getString("colorPalette", "TonalSpot") ?: "TonalSpot"),
            shapes = shapes,
            typography = HetuTypography,
            animate = true,
        ) {
            ProvideTokens(content)
        }
    }
}
