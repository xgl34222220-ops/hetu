package io.github.xgl34222220.hetu.panel

import androidx.compose.foundation.ScrollState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Fades the leading/trailing [width] of a horizontally scrolling row while more content lies in
 * that direction, so a clipped last tab reads as "scroll for more" instead of being cut off.
 * Place it before `horizontalScroll` (it masks the viewport). The scroll position is read only
 * in the draw phase: scrolling redraws the mask without recomposing the row.
 */
internal fun Modifier.panelHorizontalEdgeFade(scroll: ScrollState, width: Dp = 24.dp): Modifier =
    graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            val w = width.toPx().coerceAtMost(size.width / 3f)
            if (w <= 0f) return@drawWithContent
            if (scroll.value > 0) {
                drawRect(
                    Brush.horizontalGradient(listOf(Color.Transparent, Color.Black), startX = 0f, endX = w),
                    size = Size(w, size.height), blendMode = BlendMode.DstIn,
                )
            }
            if (scroll.value < scroll.maxValue) {
                drawRect(
                    Brush.horizontalGradient(listOf(Color.Black, Color.Transparent), startX = size.width - w, endX = size.width),
                    topLeft = Offset(size.width - w, 0f), size = Size(w, size.height), blendMode = BlendMode.DstIn,
                )
            }
        }
