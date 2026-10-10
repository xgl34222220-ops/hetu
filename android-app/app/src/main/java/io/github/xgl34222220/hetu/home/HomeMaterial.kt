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
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.translate
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
    // Restrained liquid-glass canvas: a calm gray-blue wash with one faint vertical gradient and a
    // soft light at the top. No coloured blobs; cached per size, nothing animates.
    val mist = if (c.dark) Color(0xFF243042) else Color(0xFFC9D5E6)
    val material = remember(c, base) { Modifier.drawWithCache {
        val wash = Brush.verticalGradient(listOf(
            lerp(base, if (c.dark) Color(0xFF1B2330) else Color.White, if (c.dark) .30f else .42f),
            base,
            lerp(base, mist, if (c.dark) .28f else .34f),
        ))
        val light = Brush.radialGradient(
            listOf(Color.White.copy(alpha = if (c.dark) .035f else .28f), Color.Transparent),
            center = Offset(size.width * .5f, 0f), radius = max(size.width, 1f) * .95f,
        )
        onDrawBehind {
            drawRect(base)
            drawRect(wash)
            drawRect(light)
        }
    } }
    return this.then(material)
}

/**
 * Restrained iOS liquid-glass material, faked without any blur so it costs nothing while lists
 * scroll: a frosted translucent fill (a cached vertical gradient, opaque enough for small text),
 * a thin bright edge that is strongest along the top, and a soft low shadow drawn once per size.
 * Raised surfaces (menus, dialogs) stay opaque. Shapes and hit targets come from the caller.
 */
@Composable
internal fun Modifier.homeGlassPanel(
    shape: Shape = HomeDims.cardShape,
    background: Color = LocalHomeColors.current.surface,
    raised: Boolean = false,
): Modifier {
    val c = LocalHomeColors.current
    val dark = c.dark
    val black = dark && c.bg == Color.Black
    val pixel = (1f / LocalDensity.current.density).dp
    val top = lerp(background, Color.White, if (dark) .05f else .34f)
        .copy(alpha = if (raised) 1f else if (dark) .92f else .86f)
    val bottom = lerp(background, if (dark) Color(0xFF2A3546) else Color(0xFFD7E0EC), if (dark) .10f else .22f)
        .copy(alpha = if (raised) 1f else if (dark) .90f else .80f)
    val fill = remember(top, background, bottom, raised) {
        Brush.verticalGradient(listOf(top, background.copy(alpha = if (raised) 1f else if (dark) .91f else .84f), bottom))
    }
    val rim = remember(dark, c.line) {
        Brush.verticalGradient(listOf(
            Color.White.copy(alpha = if (dark) .20f else .95f),
            Color.White.copy(alpha = if (dark) .06f else .45f),
            c.line.copy(alpha = if (dark) .45f else .55f),
        ))
    }
    val shadowTint = if (dark) Color.Black.copy(alpha = .30f) else Color(0xFF51617A).copy(alpha = .10f)
    val material = remember(shape, fill, rim, pixel, shadowTint, black) {
        val shadow = if (black) Modifier else Modifier.drawWithCache {
            // Two offset translucent copies of the outline read as a soft contact shadow.
            val near = shape.createOutline(size, layoutDirection, this)
            onDrawBehind {
                translate(0f, 2.dp.toPx()) { drawOutline(near, shadowTint) }
                translate(0f, 5.dp.toPx()) { drawOutline(near, shadowTint.copy(alpha = shadowTint.alpha * .45f)) }
            }
        }
        shadow.clip(shape).background(fill).border(pixel * 1.5f, rim, shape)
    }
    return this.then(material)
}
