package io.github.xgl34222220.hetu.ui

import android.content.SharedPreferences
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface as MaterialSurface
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.inset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource

/** One ambient capture per activity. Card effects never sample their own text or controls. */
val LocalCrystalBackdrop = staticCompositionLocalOf<HazeState?> { null }
val LocalCrystalBlurEnabled = staticCompositionLocalOf { false }
enum class CrystalDepth { Card, InsetItem, Popover, Sunken }

@Composable
fun CrystalEnvironment(content: @Composable () -> Unit) {
    val prefs = LocalContext.current.getSharedPreferences("hetu", 0)
    var enabled by remember { mutableStateOf(prefs.getBoolean("enableBlur", true) && prefs.getBoolean("liquidGlass", true)) }
    DisposableEffect(prefs) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { p, key ->
            if (key == "enableBlur" || key == "liquidGlass") enabled = p.getBoolean("enableBlur", true) && p.getBoolean("liquidGlass", true)
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    val backdrop = remember { HazeState() }
    CompositionLocalProvider(LocalCrystalBackdrop provides backdrop, LocalCrystalBlurEnabled provides enabled) {
        Box(Modifier.fillMaxSize()) {
            Box(Modifier.matchParentSize().then(if (enabled) Modifier.hazeSource(backdrop, key = "crystal-ambient") else Modifier)
                .crystalPageBackground())
            CrystalPopoverHost(content)
        }
    }
}

/** Static blue/teal ambient light. No synthetic data, no perpetual animations. */
@Composable
fun Modifier.crystalPageBackground(): Modifier {
    val t = LocalHetuTokens.current
    val dark = t.pageBackground.luminance() < .5f
    return drawWithCache {
        val blue = Brush.radialGradient(listOf(if (dark) Color(0xFF173251).copy(alpha = .23f) else Color(0xFFCADFF4).copy(alpha = .34f), Color.Transparent),
            center = Offset(size.width * .86f, size.height * .20f), radius = maxOf(size.width * .95f, 1f))
        val mint = Brush.radialGradient(listOf(if (dark) Color(0xFF12312E).copy(alpha = .17f) else Color(0xFFD2ECE8).copy(alpha = .27f), Color.Transparent),
            center = Offset(size.width * .06f, size.height * .52f), radius = maxOf(size.width * .9f, 1f))
        onDrawBehind { drawRect(t.pageBackground); drawRect(blue); drawRect(mint) }
    }
}

/** Shared material, not a foreground blur. The original composable's semantics are untouched. */
@Composable
fun Modifier.crystalMaterial(
    shape: Shape,
    tint: Color = Color.Unspecified,
    depth: CrystalDepth = CrystalDepth.Card,
    backdrop: HazeState? = LocalCrystalBackdrop.current,
    blurEnabled: Boolean = LocalCrystalBlurEnabled.current,
    selection: Boolean = false,
): Modifier {
    val t = LocalHetuTokens.current
    val primary = MaterialTheme.colorScheme.primary
    val dark = t.pageBackground.luminance() < .5f
    val radius = when (depth) { CrystalDepth.Popover -> 24.dp; CrystalDepth.InsetItem, CrystalDepth.Sunken -> 12.dp; else -> 20.dp }
    val topAlpha = when (depth) { CrystalDepth.Popover -> .94f; CrystalDepth.InsetItem -> .92f; CrystalDepth.Sunken -> 1f; else -> .98f }
    val bottomAlpha = when (depth) { CrystalDepth.Popover -> .84f; CrystalDepth.InsetItem -> .84f; CrystalDepth.Sunken -> 1f; else -> .88f }
    val accent = if (selection) primary else if (tint.isSpecified && tint.alpha > .05f) tint else Color.Unspecified
    val upper = if (dark) Color(0xFF283543).copy(alpha = .82f) else (if (depth == CrystalDepth.Sunken) Color(0xFFEEF2F6) else Color.White).copy(alpha = topAlpha)
    val lower = if (dark) Color(0xFF18232F).copy(alpha = .72f) else (if (depth == CrystalDepth.Sunken) Color(0xFFEEF2F6) else Color(0xFFF8FAFC)).copy(alpha = bottomAlpha)
    val fill = Brush.verticalGradient(listOf(upper, lower))
    // Suppress Haze's default opaque tint; the translucent fill above is the only wash.
    val style = HazeStyle(backgroundColor = Color.Transparent, tints = emptyList(), blurRadius = radius,
        noiseFactor = .005f, fallbackTint = HazeTint(Color.Transparent))
    val shadowSize = when(depth) { CrystalDepth.Popover -> 18.dp; CrystalDepth.InsetItem -> 2.dp; CrystalDepth.Sunken -> 2.dp; else -> 8.dp }
    val blur = if (blurEnabled && backdrop != null) Modifier.hazeEffect(backdrop, style) {
        canDrawArea = { true }
    } else Modifier
    val contact = if (depth == CrystalDepth.Sunken) Modifier else Modifier.shadow(
        1.dp, shape, clip = false, ambientColor = Color(0xFF0F172A).copy(alpha = .03f),
        spotColor = Color(0xFF0F172A).copy(alpha = .03f))
    return then(contact).shadow(shadowSize, shape, clip = false,
        ambientColor = Color(0xFF0F172A).copy(alpha = if (depth == CrystalDepth.Popover) .10f else .03f),
        spotColor = Color(0xFF0F172A).copy(alpha = if (depth == CrystalDepth.Popover) .14f else .05f))
        .clip(shape).then(blur).background(fill, shape)
        .border(
            1.dp,
            if (dark) Color.White.copy(alpha = .14f) else Color.White.copy(alpha = .95f),
            shape,
        )
        .drawWithCache {
            val outline = shape.createOutline(size, layoutDirection, this)
            val rim = Brush.verticalGradient(listOf(
                Color.White.copy(alpha = if (dark) .22f else .95f),
                Color.White.copy(alpha = if (dark) .08f else .72f),
                if (dark) Color(0xFF61778C).copy(alpha = .25f) else Color(0xFFE2E8F0).copy(alpha = .72f)))
            val ambient = Brush.radialGradient(listOf(
                (if (accent.isSpecified) accent else if (dark) Color(0xFF2A6496) else Color(0xFF7BAFDE))
                    .copy(alpha = if (selection) .15f else .105f), Color.Transparent),
                center = Offset(size.width*.94f, size.height*.88f), radius = maxOf(size.width*.9f, 1f))
            val mint = Brush.radialGradient(listOf(Color(0xFF5AB8AD).copy(alpha = if (dark) .04f else .065f), Color.Transparent),
                center = Offset(0f, size.height*.38f), radius = maxOf(size.width*.75f, 1f))
            onDrawWithContent {
                drawRect(ambient); drawRect(mint)
                if (depth == CrystalDepth.Sunken) drawRect(Brush.verticalGradient(
                    listOf(Color(0xFF0F172A).copy(alpha = .04f), Color.Transparent), endY = 5.dp.toPx()))
                drawContent()
                drawOutline(outline, rim, style = Stroke(width = 1f))
                if (size.minDimension > 8.dp.toPx()) inset(1.5.dp.toPx()) {
                    val inner = shape.createOutline(size, layoutDirection, this)
                    drawOutline(inner, Brush.verticalGradient(listOf(
                        Color.White.copy(alpha = if (dark) .08f else .52f), Color.Transparent)), style = Stroke(1f))
                }
                if (selection) drawOutline(outline, primary.copy(alpha = .62f), style = Stroke(1.5.dp.toPx()))
            }
        }
}

@Composable
private fun wantsCrystal(color: Color, shape: Shape): Boolean {
    val t = LocalHetuTokens.current
    if (shape == RectangleShape || shape == CircleShape || color.alpha == 0f) return false
    return color == t.cardBackground || color == t.elevatedCardBackground || color == MaterialTheme.colorScheme.surface || color == Color.White
}

/** Drop-in adapter for content cards only. Status chips, controls and full-screen roots retain their original colors. */
@Composable
fun CrystalSurface(
    modifier: Modifier = Modifier,
    shape: Shape = RectangleShape,
    color: Color = MaterialTheme.colorScheme.surface,
    contentColor: Color = contentColorFor(color),
    tonalElevation: Dp = 0.dp,
    shadowElevation: Dp = 0.dp,
    border: BorderStroke? = null,
    content: @Composable () -> Unit,
) {
    val crystal = wantsCrystal(color, shape)
    if (crystal) {
        Box(modifier.crystalMaterial(shape)) {
            CompositionLocalProvider(LocalContentColor provides contentColor, content = content)
        }
    } else {
        MaterialSurface(
            modifier = modifier,
            shape = shape,
            color = color,
            contentColor = contentColor,
            tonalElevation = tonalElevation,
            shadowElevation = shadowElevation,
            border = border,
            content = content,
        )
    }
}

@Composable
fun CrystalSurface(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = RectangleShape,
    color: Color = MaterialTheme.colorScheme.surface,
    contentColor: Color = contentColorFor(color),
    tonalElevation: Dp = 0.dp,
    shadowElevation: Dp = 0.dp,
    border: BorderStroke? = null,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable () -> Unit,
) {
    val crystal = wantsCrystal(color, shape)
    if (crystal) {
        val source = interactionSource ?: remember { MutableInteractionSource() }
        Box(
            modifier
                .crystalMaterial(shape)
                .clickable(
                    enabled = enabled,
                    interactionSource = source,
                    indication = null,
                    onClick = onClick,
                ),
        ) {
            CompositionLocalProvider(LocalContentColor provides contentColor, content = content)
        }
    } else {
        MaterialSurface(
            onClick = onClick,
            modifier = modifier,
            enabled = enabled,
            shape = shape,
            color = color,
            contentColor = contentColor,
            tonalElevation = tonalElevation,
            shadowElevation = shadowElevation,
            border = border,
            interactionSource = interactionSource,
            content = content,
        )
    }
}
