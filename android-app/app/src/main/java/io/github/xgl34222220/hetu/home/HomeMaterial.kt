package io.github.xgl34222220.hetu.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlin.math.max

/**
 * Static diffuse canvas. Brushes are cached for the measured size; scrolling and live network
 * values do not rebuild them. No bitmap, offscreen blur, shader or perpetual animation is used.
 * An OLED black preference keeps the page exactly black, including while blur is unavailable.
 */
@Composable
internal fun Modifier.homeDiffuseCanvas(background: Color? = null): Modifier {
    val c = LocalHomeColors.current
    val base = background ?: c.bg
    if (c.dark && c.bg == Color.Black && base == c.bg) return this.background(base)
    val cool = lerp(c.accent, if (c.dark) Color(0xFF8E93CC) else Color(0xFFAFA9EB), .62f)
    val warm = if (c.dark) Color(0xFF947D9F) else Color(0xFFD8BCD6)
    val material = remember(c, base) { Modifier.drawWithCache {
        val extent = max(size.width, size.height).coerceAtLeast(1f)
        val top = Brush.radialGradient(
            listOf(cool.copy(alpha = if (c.dark) .13f else .19f), Color.Transparent),
            center = Offset(size.width * .08f, size.height * .05f), radius = extent * .67f,
        )
        val side = Brush.radialGradient(
            listOf(warm.copy(alpha = if (c.dark) .08f else .17f), Color.Transparent),
            center = Offset(size.width * 1.05f, size.height * .49f), radius = extent * .57f,
        )
        val foot = Brush.radialGradient(
            listOf(c.accent.copy(alpha = if (c.dark) .06f else .065f), Color.Transparent),
            center = Offset(size.width * .22f, size.height * 1.08f), radius = extent * .61f,
        )
        onDrawBehind {
            drawRect(base)
            drawRect(top)
            drawRect(side)
            drawRect(foot)
        }
    } }
    return this.then(material)
}

/**
 * Lightweight glass material for content and overlays. The high-opacity fill protects small
 * text from the canvas; raised surfaces are opaque so underlying text cannot show through a
 * menu or dialog. One physical-pixel edge gives definition without a resting shadow.
 * Existing shapes and hit targets are supplied by the caller and do not change.
 */
@Composable
internal fun Modifier.homeGlassPanel(
    shape: Shape = HomeDims.cardShape,
    background: Color = LocalHomeColors.current.surface,
    raised: Boolean = false,
): Modifier {
    val c = LocalHomeColors.current
    val pixel = (1f / LocalDensity.current.density).dp
    val top = lerp(background, Color.White, if (c.dark) .025f else .16f)
        .copy(alpha = if (raised) 1f else if (c.dark) .98f else .96f)
    val bottom = lerp(background, c.accent, if (c.dark) .014f else .016f)
        .copy(alpha = if (raised) 1f else if (c.dark) .96f else .90f)
    val fill = remember(top, background, bottom, raised) {
        Brush.verticalGradient(listOf(top, background.copy(alpha = if (raised) 1f else .95f), bottom))
    }
    val rim = remember(c.dark, c.line) {
        Brush.linearGradient(listOf(
            Color.White.copy(alpha = if (c.dark) .13f else .82f),
            c.line.copy(alpha = if (c.dark) .55f else .74f),
        ))
    }
    val material = remember(shape, fill, rim, pixel) {
        Modifier.clip(shape).background(fill).border(pixel, rim, shape)
    }
    return this.then(material)
}
