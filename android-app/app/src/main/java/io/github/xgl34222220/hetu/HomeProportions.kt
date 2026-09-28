package io.github.xgl34222220.hetu

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.CornerRounding
import androidx.graphics.shapes.RoundedPolygon
import androidx.graphics.shapes.toPath

/** Actual dock occupancy, including its gesture inset and external bottom margin. */
internal val LocalHomeDockClearance = staticCompositionLocalOf<Dp?> { null }
internal val LocalHomeCompactSpacing = staticCompositionLocalOf { false }
internal val LocalHomeShortSpacing = staticCompositionLocalOf { false }
internal data class HomeGridRows(val heading: Dp, val gap: Dp, val first: Dp, val second: Dp, val baseline: Dp = 17.dp, val footer: Dp = 14.dp) {
    val height: Dp get() = 24.dp + heading + gap + first + 4.dp + second + footer
}
internal val LocalHomeGridRows = staticCompositionLocalOf { HomeGridRows(32.dp, 8.dp, 22.dp, 22.dp) }

/** Smooth circular arcs with continuous cubic flank transitions; works on software Canvas too. */
internal data class HomeContinuousShape(val radius: Dp = 28.dp) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        if (size.width <= 0f || size.height <= 0f) return Outline.Rectangle(androidx.compose.ui.geometry.Rect(0f,0f,size.width,size.height))
        val r = with(density) { radius.toPx() }.coerceIn(0f, size.minDimension / 2f)
        return Outline.Generic(RoundedPolygon(
            vertices = floatArrayOf(0f,0f,size.width,0f,size.width,size.height,0f,size.height),
            rounding = CornerRounding(r, smoothing = .6f),
        ).toPath().asComposePath())
    }
}
