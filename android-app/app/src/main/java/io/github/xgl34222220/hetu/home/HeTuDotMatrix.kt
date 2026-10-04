package io.github.xgl34222220.hetu.home

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos

/** How the 河图 diagram is rendered. */
internal enum class HeTuDotMode {
    /** Accent colour; the five centre dots (中宫) breathe slowly. */
    Running,

    /** Amber; a ripple travels from the centre outwards. Used while starting, restarting or stopping. */
    Transition,

    /** Grey and still. Used while the proxy is not running. */
    Idle,
}

/** One dot on the 84 × 84 design grid. Yang (odd) counts are hollow rings, yin (even) counts are solid. */
internal class HeTuDot(val x: Float, val y: Float, val yin: Boolean, val center: Boolean) {
    /** Ripple delay as a fraction of one [HomeMotion.RippleMs] period: 14 ms per grid unit of Manhattan distance. */
    val ripplePhase: Float = (abs(x - 42f) + abs(y - 42f)) * 14f / HomeMotion.RippleMs
}

/**
 * The classic 河图 arrangement, 55 dots in total:
 * centre 5 (yang) inside 10 (yin, two rows of five); north 2 (yin) and 7 (yang);
 * south 1 (yang) and 6 (yin); east 3 (yang) and 8 (yin) on the left; west 4 (yin) and 9 (yang) on the right.
 */
internal val HeTuDots: List<HeTuDot> = buildList {
    fun add(x: Int, y: Int, yin: Boolean, center: Boolean = false) { add(HeTuDot(x.toFloat(), y.toFloat(), yin, center)) }
    listOf(42 to 42, 36 to 42, 48 to 42, 42 to 36, 42 to 48).forEach { (x, y) -> add(x, y, yin = false, center = true) }
    for (x in 30..54 step 6) { add(x, 28, yin = true); add(x, 56, yin = true) }
    add(42, 65, yin = false)
    for (x in 27..57 step 6) add(x, 74, yin = true)
    add(39, 19, yin = true); add(45, 19, yin = true)
    for (x in 24..60 step 6) add(x, 10, yin = false)
    for (y in 36..48 step 6) add(19, y, yin = false)
    for (y in 21..63 step 6) add(10, y, yin = true)
    for (y in 33..51 step 6) add(65, y, yin = true)
    for (y in 18..66 step 6) add(74, y, yin = false)
}

internal fun HomeStatus.dotMode(): HeTuDotMode = when (this) {
    is HomeStatus.Running, is HomeStatus.PendingRestart -> HeTuDotMode.Running
    HomeStatus.Starting, HomeStatus.Restarting, HomeStatus.Stopping -> HeTuDotMode.Transition
    HomeStatus.NotRunning, is HomeStatus.StartFailed -> HeTuDotMode.Idle
}

/**
 * The 河图 status glyph.
 *
 * @param color defaults to accent (running), warn (transition) or t3 at 55 % (idle).
 * @param animate pass false to honour the user's "reduce motion" switch; the glyph is then static.
 */
@Composable
internal fun HeTuDotMatrix(
    mode: HeTuDotMode,
    modifier: Modifier = Modifier,
    size: Dp = 92.dp,
    color: Color = defaultHeTuColor(mode),
    animate: Boolean = true,
) {
    val base = modifier.size(size).semantics { contentDescription = "河图" }
    if (!animate || mode == HeTuDotMode.Idle) {
        Canvas(base) { drawHeTu(color) { 1f } }
        return
    }
    val transition = rememberInfiniteTransition(label = "hetu-dots")
    val period = if (mode == HeTuDotMode.Running) HomeMotion.BreathMs else HomeMotion.RippleMs
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(period, easing = LinearEasing), RepeatMode.Restart),
        label = "hetu-phase",
    )
    Canvas(base) {
        val t = phase
        drawHeTu(color) { dot ->
            when {
                mode == HeTuDotMode.Running -> if (dot.center) breath(t) else 1f
                else -> breath(t - dot.ripplePhase)
            }
        }
    }
}

@Composable
private fun defaultHeTuColor(mode: HeTuDotMode): Color {
    val c = LocalHomeColors.current
    return when (mode) {
        HeTuDotMode.Running -> c.accent
        HeTuDotMode.Transition -> c.warn
        HeTuDotMode.Idle -> c.t3.copy(alpha = .55f)
    }
}

/** 1 → 0.25 → 1 over one period; any real input is wrapped into 0..1 first. */
private fun breath(phase: Float): Float {
    val wrapped = phase - kotlin.math.floor(phase)
    return .625f + .375f * cos(2.0 * PI * wrapped).toFloat()
}

private inline fun DrawScope.drawHeTu(color: Color, alphaOf: (HeTuDot) -> Float) {
    val unit = size.minDimension / 84f
    val ring = Stroke(width = 1.2f * unit)
    for (dot in HeTuDots) {
        val center = Offset(dot.x * unit, dot.y * unit)
        val tint = color.copy(alpha = color.alpha * alphaOf(dot).coerceIn(0f, 1f))
        if (dot.yin) drawCircle(tint, radius = 2.05f * unit, center = center)
        else drawCircle(tint, radius = 1.7f * unit, center = center, style = ring)
    }
}
