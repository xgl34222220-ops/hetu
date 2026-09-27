package io.github.xgl34222220.hetu.ui

import android.content.SharedPreferences
import top.yukonga.miuix.kmp.squircle.squircleSurface
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
    return background(LocalHetuTokens.current.pageBackground)
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
    val fill = when {
        selection -> t.selectionBackground
        tint.isSpecified && tint.alpha > .05f -> tint
        depth == CrystalDepth.Sunken || depth == CrystalDepth.InsetItem -> t.controlBackground
        depth == CrystalDepth.Popover -> t.elevatedCardBackground
        else -> t.cardBackground
    }
    val dark = t.pageBackground.luminance() < .5f
    return when (depth) {
        CrystalDepth.Card, CrystalDepth.Popover -> glassSurface(fill, shape, dark)
        CrystalDepth.InsetItem, CrystalDepth.Sunken -> clip(shape).background(fill, shape)
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
    val softCard = border == null && wantsCrystal(color, shape)
    MaterialSurface(modifier = if (softCard) modifier.glassSurface(color, shape,
            LocalHetuTokens.current.pageBackground.luminance() < .5f) else modifier,
        shape = shape, color = if (softCard) Color.Transparent else color, contentColor = contentColor,
        tonalElevation = tonalElevation, shadowElevation = if (softCard) 0.dp else shadowElevation,
        border = border, content = content)
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
    val softCard = border == null && wantsCrystal(color, shape)
    MaterialSurface(onClick = onClick, modifier = if (softCard) modifier.glassSurface(color, shape,
            LocalHetuTokens.current.pageBackground.luminance() < .5f) else modifier,
        enabled = enabled, shape = shape, color = if (softCard) Color.Transparent else color,
        contentColor = contentColor, tonalElevation = tonalElevation,
        shadowElevation = if (softCard) 0.dp else shadowElevation, border = border,
        interactionSource = interactionSource, content = content)
}
