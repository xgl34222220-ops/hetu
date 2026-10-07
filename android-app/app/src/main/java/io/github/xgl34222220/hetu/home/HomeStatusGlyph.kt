package io.github.xgl34222220.hetu.home

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate

/** What the big ring in the status card shows. */
internal enum class HomeGlyphMode {
    /** Running: full accent ring with a check. */
    On,

    /** Starting or stopping: amber ring with a travelling arc around a power glyph. */
    BusyPower,

    /** Restarting: amber ring with a travelling arc around the check. */
    BusyCheck,

    /** Not running: quiet grey ring with a power glyph. */
    Off,

    /** Core is running, but its local connection state is unconfirmed or degraded. No halo. */
    Unconfirmed,
    Attention,
}

internal fun HomeStatus.glyphMode(): HomeGlyphMode = when (this) {
    is HomeStatus.Running, is HomeStatus.PendingRestart -> HomeGlyphMode.On
    HomeStatus.Starting, HomeStatus.Stopping -> HomeGlyphMode.BusyPower
    HomeStatus.Restarting -> HomeGlyphMode.BusyCheck
    HomeStatus.NotRunning, is HomeStatus.StartFailed -> HomeGlyphMode.Off
}

/**
 * The status ring of the home hero card, drawn on a canvas so every transition is continuous:
 *
 * - colour cross-fades between accent, amber and grey;
 * - the power glyph hands over to the check by fading out while the check strokes itself in;
 * - while busy the ring dims and a 250° arc travels around it;
 * - while running a soft halo breathes behind the ring.
 *
 * The caller sizes it (the concept uses 116 dp) and lets the card clip the part that overflows.
 */
@Composable
internal fun HomeStatusGlyph(mode: HomeGlyphMode, modifier: Modifier = Modifier, animate: Boolean = true, tint: Color? = null) {
    val c = LocalHomeColors.current
    val motion = animate && LocalHomeMotionEnabled.current
    val target = tint ?: when (mode) {
        HomeGlyphMode.On -> c.accent
        HomeGlyphMode.BusyPower, HomeGlyphMode.BusyCheck, HomeGlyphMode.Attention -> c.warn
        HomeGlyphMode.Off, HomeGlyphMode.Unconfirmed -> if (c.dark) c.t3 else c.t3.copy(alpha = .86f)
    }
    val color by animateColorAsState(target, HomeMotion.fade(motion, 360), label = "home-glyph-color")
    val busy = mode == HomeGlyphMode.BusyPower || mode == HomeGlyphMode.BusyCheck
    val checked = mode == HomeGlyphMode.On || mode == HomeGlyphMode.BusyCheck
    // 0 = power glyph, 1 = check; the check draws itself over the last 60 % of the hand-over.
    val morph by animateFloatAsState(if (checked) 1f else 0f, if (motion) tween(460, easing = HomeMotion.Emphasized) else tween(0), label = "home-glyph-morph")
    val busyBlend by animateFloatAsState(if (busy) 1f else 0f, HomeMotion.fade(motion, 260), label = "home-glyph-busy")
    val haloBlend by animateFloatAsState(if (mode == HomeGlyphMode.On) 1f else 0f, HomeMotion.fade(motion, 520), label = "home-glyph-halo")

    // Perpetual values are read in the draw phase only, so they never recompose the card.
    val spinState: State<Float>?
    val breathState: State<Float>?
    if (motion && (busy || mode == HomeGlyphMode.On)) {
        val transition = rememberInfiniteTransition(label = "home-glyph")
        spinState = transition.animateFloat(0f, 360f, infiniteRepeatable(tween(1150, easing = LinearEasing), RepeatMode.Restart), label = "home-glyph-spin")
        breathState = transition.animateFloat(0f, 1f, infiniteRepeatable(tween(HomeMotion.BreathMs, easing = HomeMotion.Emphasized), RepeatMode.Reverse), label = "home-glyph-breath")
    } else {
        spinState = null
        breathState = null
    }

    Canvas(modifier) {
        val spin = spinState?.value ?: 0f
        val breath = breathState?.value ?: .5f
        val radius = size.minDimension / 2f
        val centre = Offset(size.width / 2f, size.height / 2f)
        val stroke = radius * .215f
        val ringRadius = radius - stroke / 2f

        if (haloBlend > 0f) {
            val reach = radius * (1.34f + .10f * breath)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(color.copy(alpha = .22f * haloBlend * (.55f + .45f * breath)), Color.Transparent),
                    center = centre,
                    radius = reach,
                ),
                radius = reach,
                center = centre,
            )
        }

        // Ring. While busy the full circle recedes and the travelling arc carries the weight.
        drawCircle(color.copy(alpha = color.alpha * (1f - .70f * busyBlend)), radius = ringRadius, center = centre, style = Stroke(stroke))
        if (busyBlend > 0f) {
            rotate(spin, centre) {
                drawArc(
                    color = color.copy(alpha = color.alpha * busyBlend),
                    startAngle = -90f,
                    sweepAngle = 250f,
                    useCenter = false,
                    topLeft = Offset(centre.x - ringRadius, centre.y - ringRadius),
                    size = Size(ringRadius * 2f, ringRadius * 2f),
                    style = Stroke(stroke, cap = StrokeCap.Round),
                )
            }
        }

        if (mode == HomeGlyphMode.Attention || mode == HomeGlyphMode.Unconfirmed) {
            // An information mark cannot be mistaken for a healthy connection check mark.
            drawLine(color, Offset(centre.x, centre.y - radius * .35f), Offset(centre.x, centre.y + radius * .06f), radius * .14f, StrokeCap.Round)
            drawCircle(color, radius * .075f, Offset(centre.x, centre.y + radius * .31f))
        } else {
            val powerAlpha = (1f - morph / .4f).coerceIn(0f, 1f)
            if (powerAlpha > 0f) drawPower(centre, radius, color.copy(alpha = color.alpha * powerAlpha))
            val checkProgress = ((morph - .4f) / .6f).coerceIn(0f, 1f)
            if (checkProgress > 0f) drawCheck(centre, radius, color, checkProgress)
        }
    }
}

/** Check mark stroked from its short leg to the tip of its long leg; [progress] 0 → 1 trims it. */
private fun DrawScope.drawCheck(centre: Offset, radius: Float, color: Color, progress: Float) {
    val a = Offset(centre.x - radius * .50f, centre.y + radius * .03f)
    val b = Offset(centre.x - radius * .15f, centre.y + radius * .37f)
    val e = Offset(centre.x + radius * .50f, centre.y - radius * .29f)
    val first = (b - a).getDistance()
    val second = (e - b).getDistance()
    val drawn = (first + second) * progress
    val path = Path().apply {
        moveTo(a.x, a.y)
        if (drawn <= first) {
            val t = if (first == 0f) 1f else drawn / first
            lineTo(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)
        } else {
            lineTo(b.x, b.y)
            val t = if (second == 0f) 1f else (drawn - first) / second
            lineTo(b.x + (e.x - b.x) * t, b.y + (e.y - b.y) * t)
        }
    }
    drawPath(path, color, style = Stroke(radius * .205f, cap = StrokeCap.Round, join = StrokeJoin.Round))
}

/** Power glyph: an open circle with a stem through the gap. */
private fun DrawScope.drawPower(centre: Offset, radius: Float, color: Color) {
    val stroke = Stroke(radius * .17f, cap = StrokeCap.Round)
    val arcRadius = radius * .36f
    val arcCentre = Offset(centre.x, centre.y + radius * .05f)
    drawArc(
        color = color,
        startAngle = -90f + 38f,
        sweepAngle = 360f - 76f,
        useCenter = false,
        topLeft = Offset(arcCentre.x - arcRadius, arcCentre.y - arcRadius),
        size = Size(arcRadius * 2f, arcRadius * 2f),
        style = stroke,
    )
    drawLine(color, Offset(centre.x, centre.y - radius * .44f), Offset(centre.x, centre.y - radius * .02f), strokeWidth = stroke.width, cap = StrokeCap.Round)
}
