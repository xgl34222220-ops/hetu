package io.github.xgl34222220.hetu.ui

import android.os.Build
import androidx.compose.foundation.LocalOverscrollFactory
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
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.style.LineHeightStyle
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
        pageBackground = Color(0xFFF3F4F7),
        cardBackground = Color(0xFFFCFCFE),
        elevatedCardBackground = Color(0xFFFFFFFF),
        heroBackground = Color(0xFFE6EEFD),
        textPrimary = Color(0xFF1B2029),
        textSecondary = Color(0xFF626A78),
        textMuted = Color(0xFF76808D),
        success = Color(0xFF18794E),
        warning = Color(0xFF946200),
        danger = Color(0xFFC52A34),
        outline = Color(0x99FFFFFF),
        controlBackground = Color(0x73FFFFFF),
        selectionBackground = Color(0x66DDEBFF),
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
    displaySmall = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = true), lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None), fontSize = 30.sp, lineHeight = 36.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.55).sp),
    headlineLarge = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = true), lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None), fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.70).sp),
    headlineMedium = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = true), lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None), fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.70).sp),
    headlineSmall = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = true), lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None), fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.30).sp),
    titleLarge = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = true), lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None), fontSize = 19.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.10.sp),
    titleMedium = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = true), lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None), fontSize = 16.sp, lineHeight = 21.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.10.sp),
    titleSmall = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = true), lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None), fontSize = 14.5.sp, lineHeight = 19.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.15.sp),
    bodyLarge = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = true), lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None), fontSize = 14.5.sp, lineHeight = 19.sp, letterSpacing = 0.20.sp),
    bodyMedium = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = true), lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None), fontSize = 14.5.sp, lineHeight = 18.sp, letterSpacing = 0.30.sp),
    bodySmall = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = true), lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None), fontSize = 12.5.sp, lineHeight = 17.sp, letterSpacing = 0.15.sp),
    labelLarge = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = true), lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None), fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.10.sp),
    labelMedium = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = true), lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None), fontSize = 12.sp, lineHeight = 17.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.12.sp),
    labelSmall = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = true), lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None), fontSize = 11.5.sp, lineHeight = 16.sp, fontWeight = FontWeight.Normal, letterSpacing = 0.20.sp),
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
        val parsed = android.graphics.Color.parseColor(raw.ifBlank { if (dark) "#4A82E6" else "#2E70DE" })
        Color(parsed)
    }.getOrDefault(fallback)
}

@Composable
fun HetuTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val prefs = remember(context) { context.getSharedPreferences("hetu", 0) }
    var themeRevision by remember { mutableIntStateOf(0) }
    DisposableEffect(prefs) {
        val keys = setOf("appLanguage", "appearance", "pureBlackDark", "enableMonet", "uiStyle", "colorStandard", "colorPalette", "uiScale", "accentHex", "enableBlur", "topBarBlurStyle", "liquidGlass")
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key -> if (key in keys) themeRevision++ }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    themeRevision
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

    val storedPrimary = accentColor(prefs.getString("accentHex", if (dark) "#4A82E6" else "#2E70DE") ?: "#2E70DE", dark)
    val fixedPrimary = if (!prefs.contains("accentHex")) {
        if (dark) Color(0xFF76A7FF) else Color(0xFF0054C8)
    } else storedPrimary
    val baseLight = lightColorScheme(
        primary = fixedPrimary,
        primaryContainer = Color(0xFFE3EAF6),
        secondary = fixedPrimary,
        background = Color(0xFFF3F4F7),
        surface = Color(0xFFFCFCFE),
        error = Color(0xFFC52A34),
        onBackground = Color(0xFF1B2029),
        onSurface = Color(0xFF1B2029),
    )
    val baseDark = darkColorScheme(
        primary = fixedPrimary,
        primaryContainer = Color(0xFF1E3A8A),
        secondary = fixedPrimary,
        background = if (pureBlack) Color.Black else Color(0xFF0E1014),
        surface = Color(0xFF222129),
        error = Color(0xFFF87171),
        onBackground = Color(0xFFF8FAFC),
        onSurface = Color(0xFFF8FAFC),
    )

    val motionEnabled = rememberHetuMotionEnabled()
    val platformOverscrollFactory = top.yukonga.miuix.kmp.utils.MiuixOverscrollFactory
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
                pageBackground = if (pureBlack) Color.Black else Color(0xFF0E1014),
                cardBackground = Color(0xFF222129),
                elevatedCardBackground = Color(0xFF2B2933),
                heroBackground = Color(0xFF222129),
                textPrimary = Color(0xFFF8FAFC),
                textSecondary = Color(0xFFB4BDCA),
                textMuted = Color(0xFFADB7C4),
                success = Color(0xFF89DCAE),
                warning = Color(0xFFFBBF24),
                danger = Color(0xFFF87171),
                outline = Color.White.copy(alpha = .10f),
                controlBackground = Color.White.copy(alpha = .07f),
                selectionBackground = scheme.primary.copy(alpha = .16f).compositeOver(Color(0xFF222129)),
                textOnPage = Color(0xFFB4BDCA),
                successContainer = Color(0xFF17382B),
                warningContainer = Color(0xFF3C311E),
                dangerContainer = Color(0xFF43232A),
            )
        } else {
            HetuTokens(
                pageBackground = Color(0xFFF3F4F7),
                cardBackground = Color(0xFFFCFCFE),
                elevatedCardBackground = Color(0xFFFFFFFF),
                heroBackground = Color(0xFFE6EEFD),
                textPrimary = Color(0xFF1B2029),
                textSecondary = Color(0xFF626A78),
                textMuted = Color(0xFF76808D),
                success = Color(0xFF18794E),
                warning = Color(0xFF946200),
                danger = Color(0xFFC52A34),
                outline = Color(0xFFE3E7ED),
                controlBackground = Color(0xFFEBEEF3),
                selectionBackground = scheme.primary.copy(alpha = .10f).compositeOver(Color(0xFFFCFCFE)),
            )
        }
        CompositionLocalProvider(
            LocalHetuTokens provides tokens,
            androidx.compose.material3.LocalContentColor provides tokens.textPrimary,
            LocalHetuLanguage provides prefs.getString("appLanguage", "system").orEmpty(),
            LocalHetuMotionEnabled provides motionEnabled,
            LocalOverscrollFactory provides if (motionEnabled) platformOverscrollFactory else null,
            LocalDensity provides scaledDensity,
            top.yukonga.miuix.kmp.squircle.LocalSquircleEnabled provides
                (top.yukonga.miuix.kmp.squircle.LocalSquircleEnabled.current && androidx.compose.ui.platform.LocalView.current.isHardwareAccelerated),
            content = {
                val colors = (if (dark) top.yukonga.miuix.kmp.theme.darkColorScheme() else top.yukonga.miuix.kmp.theme.lightColorScheme()).copy(
                    primary = scheme.primary, primaryVariant = scheme.primary,
                    background = tokens.pageBackground, onBackground = tokens.textPrimary,
                    surface = tokens.pageBackground, onSurface = tokens.textPrimary,
                    surfaceVariant = tokens.cardBackground, surfaceContainer = tokens.cardBackground,
                    onSurfaceContainer = tokens.textPrimary, onSurfaceVariantSummary = tokens.textSecondary,
                    secondaryVariant = tokens.controlBackground, secondaryContainer = tokens.controlBackground,
                    surfaceContainerHigh = tokens.controlBackground,
                    surfaceContainerHighest = tokens.heroBackground,
                    tertiaryContainer = tokens.selectionBackground, onTertiaryContainer = scheme.primary,
                    outline = tokens.outline, dividerLine = tokens.outline,
                )
                top.yukonga.miuix.kmp.theme.MiuixTheme(colors = colors,
                    textStyles = top.yukonga.miuix.kmp.theme.defaultTextStyles(
                        title1 = HetuTypography.displaySmall.copy(fontSize = 32.sp, lineHeight = 40.sp),
                        title2 = HetuTypography.headlineMedium.copy(fontSize = 24.sp, lineHeight = 32.sp),
                        title3 = HetuTypography.titleLarge.copy(fontSize = 20.sp, lineHeight = 28.sp),
                    )) {
                    CompositionLocalProvider(LocalOverscrollFactory provides if (motionEnabled) platformOverscrollFactory else null) {
                        CrystalEnvironment(content = inner)
                    }
                }
            },
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
    } else if ((prefs.getString("accentHex", "#2E70DE") ?: "#2E70DE") == "#2E70DE" && (prefs.getString("colorPalette", "TonalSpot") ?: "TonalSpot") == "TonalSpot") {
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
