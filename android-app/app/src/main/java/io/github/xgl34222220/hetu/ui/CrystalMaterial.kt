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
import androidx.compose.material3.LocalContentColor
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

/** Cold-air canvas with restrained ambient light for glass refraction. */
@Composable
fun Modifier.crystalPageBackground(): Modifier {
    val t = LocalHetuTokens.current
    val dark = t.pageBackground.luminance() < .5f
    return drawWithCache {
        val cyan = Brush.radialGradient(
            colors = listOf(
                Color(0xFF82DCFF).copy(alpha = if (dark) .055f else .15f),
                Color.Transparent,
            ),
            center = Offset(size.width * .10f, size.height * .06f),
            radius = maxOf(size.width * .78f, 1f),
        )
        val violet = Brush.radialGradient(
            colors = listOf(
                Color(0xFFB4A0FF).copy(alpha = if (dark) .045f else .12f),
                Color.Transparent,
            ),
            center = Offset(size.width * .92f, size.height * .18f),
            radius = maxOf(size.width * .72f, 1f),
        )
        val blue = Brush.radialGradient(
            colors = listOf(
                Color(0xFFA0D2FF).copy(alpha = if (dark) .025f else .07f),
                Color.Transparent,
            ),
            center = Offset(size.width * .06f, size.height * .92f),
            radius = maxOf(size.width * .82f, 1f),
        )
        onDrawBehind {
            drawRect(t.pageBackground)
            drawRect(cyan)
            drawRect(violet)
            drawRect(blue)
        }
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

    if (!dark) {
        val fill = when (depth) {
            CrystalDepth.Sunken, CrystalDepth.InsetItem -> Color.White.copy(alpha = .42f)
            CrystalDepth.Popover -> Color.White.copy(alpha = .82f)
            CrystalDepth.Card -> t.cardBackground
        }
        val blurRadius = when (depth) {
            CrystalDepth.Popover -> 24.dp
            CrystalDepth.Sunken, CrystalDepth.InsetItem -> 16.dp
            CrystalDepth.Card -> 20.dp
        }
        val hazeStyle = HazeStyle(
            backgroundColor = Color.Transparent,
            tints = emptyList(),
            blurRadius = blurRadius,
            noiseFactor = .008f,
            fallbackTint = HazeTint(fill),
        )
        val elevation = when (depth) {
            CrystalDepth.Popover -> 10.dp
            CrystalDepth.Card -> 4.dp
            CrystalDepth.InsetItem -> 1.dp
            CrystalDepth.Sunken -> 0.dp
        }
        val outline = when {
            selection -> primary.copy(alpha = .32f)
            depth == CrystalDepth.Popover -> Color.White.copy(alpha = .52f)
            depth == CrystalDepth.Sunken -> Color.White.copy(alpha = .18f)
            else -> Color.White.copy(alpha = .30f)
        }
        val blur = if (blurEnabled && backdrop != null) {
            Modifier.hazeEffect(backdrop, hazeStyle) { canDrawArea = { true } }
        } else Modifier
        return then(
            Modifier
                .shadow(
                    elevation = elevation,
                    shape = shape,
                    clip = false,
                    ambientColor = Color(0xFF1F2687).copy(alpha = if (depth == CrystalDepth.Popover) .065f else .025f),
                    spotColor = Color.Black.copy(alpha = if (depth == CrystalDepth.Popover) .075f else .040f),
                )
                .clip(shape)
                .then(blur)
                .background(fill, shape)
                .background(if (selection) primary.copy(alpha = .06f) else Color.Transparent, shape)
                .drawWithCache {
                    val outlineShape = shape.createOutline(size, layoutDirection, this)
                    val highlight = Brush.linearGradient(
                        colors = listOf(
                            Color.White.copy(alpha = if (depth == CrystalDepth.Popover) .30f else .20f),
                            Color.Transparent,
                            Color.White.copy(alpha = .06f),
                        ),
                        start = Offset.Zero,
                        end = Offset(size.width, size.height),
                    )
                    onDrawWithContent {
                        drawContent()
                        drawOutline(
                            outlineShape,
                            highlight,
                            style = Stroke(.45.dp.toPx()),
                        )
                    }
                }
        )
    }

    val radius = when (depth) {
        CrystalDepth.Popover -> 24.dp
        CrystalDepth.InsetItem, CrystalDepth.Sunken -> 12.dp
        else -> 20.dp
    }
    val accent = if (selection) primary else if (tint.isSpecified && tint.alpha > .05f) tint else Color.Unspecified
    val upper = Color(0xFF24262C).copy(alpha = .78f)
    val lower = Color(0xFF1A1C21).copy(alpha = .70f)
    val fill = Brush.verticalGradient(listOf(upper, lower))
    val style = HazeStyle(
        backgroundColor = Color.Transparent,
        tints = emptyList(),
        blurRadius = radius,
        noiseFactor = .005f,
        fallbackTint = HazeTint(Color.Transparent),
    )
    val shadowSize = when (depth) {
        CrystalDepth.Popover -> 12.dp
        CrystalDepth.InsetItem -> 1.dp
        CrystalDepth.Sunken -> 0.dp
        else -> 6.dp
    }
    val blur = if (blurEnabled && backdrop != null) {
        Modifier.hazeEffect(backdrop, style) { canDrawArea = { true } }
    } else Modifier
    return then(
        Modifier
            .shadow(
                shadowSize,
                shape,
                clip = false,
                ambientColor = Color.Black.copy(alpha = .10f),
                spotColor = Color.Black.copy(alpha = .14f),
            )
            .clip(shape)
            .then(blur)
            .background(fill, shape)
            .border(
                if (selection) .8.dp else .4.dp,
                if (selection) primary.copy(alpha = .62f) else Color.White.copy(alpha = .045f),
                shape,
            )
            .drawWithCache {
                val outline = shape.createOutline(size, layoutDirection, this)
                val ambient = Brush.radialGradient(
                    listOf(
                        (if (accent.isSpecified) accent else Color(0xFF6EA8FF)).copy(alpha = if (selection) .14f else .055f),
                        Color.Transparent,
                    ),
                    center = Offset(size.width * .94f, size.height * .88f),
                    radius = maxOf(size.width * .9f, 1f),
                )
                onDrawWithContent {
                    drawRect(ambient)
                    drawContent()
                    drawOutline(outline, Color.White.copy(alpha = .08f), style = Stroke(1f))
                }
            }
    )
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
