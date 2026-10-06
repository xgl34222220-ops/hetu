package io.github.xgl34222220.hetu.home

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import io.github.xgl34222220.hetu.ui.HetuStaggerState
import io.github.xgl34222220.hetu.ui.hetuStaggerIn
import io.github.xgl34222220.hetu.ui.rememberHetuStagger

/*
 * Motion building blocks shared by 首页 and 面板. Everything here respects
 * [LocalHomeMotionEnabled]: with motion off each helper renders its final state at once.
 */

/**
 * Page entrance. Cards composed during the first ~0.7 s rise 12 dp and fade in, 38 ms apart;
 * anything composed later (scrolling, state changes) appears immediately, so it never replays.
 */
@Composable
internal fun rememberHomeStagger(): HetuStaggerState = rememberHetuStagger(LocalHomeMotionEnabled.current)

@Composable
internal fun Modifier.homeEnter(state: HetuStaggerState, index: Int): Modifier =
    hetuStaggerIn(state, index, LocalHomeMotionEnabled.current)

/**
 * A live value. A change slides the old text out and the new one in: upwards when a number
 * grows, downwards when it shrinks. At rest this is exactly one [Text] node.
 */
@Composable
internal fun HomeRollingText(
    text: String,
    color: Color,
    style: TextStyle,
    modifier: Modifier = Modifier,
    alignment: Alignment = Alignment.CenterEnd,
) {
    if (!LocalHomeMotionEnabled.current) {
        Text(text, modifier, color = color, style = style, maxLines = 1, softWrap = false)
        return
    }
    AnimatedContent(
        targetState = text,
        modifier = modifier,
        transitionSpec = {
            val up = rollsUp(initialState, targetState)
            (slideInVertically(tween(240, easing = HomeMotion.Emphasized)) { h -> if (up) h / 2 else -h / 2 } +
                fadeIn(tween(180, delayMillis = 40)))
                .togetherWith(
                    slideOutVertically(tween(180, easing = HomeMotion.Emphasized)) { h -> if (up) -h / 2 else h / 2 } +
                        fadeOut(tween(120)),
                )
                .using(SizeTransform(clip = false) { _, _ -> tween<IntSize>(200, easing = HomeMotion.Emphasized) })
        },
        contentAlignment = alignment,
        label = "home-roll",
    ) { value ->
        Text(value, color = color, style = style, maxLines = 1, softWrap = false)
    }
}

/** True when [to] reads as a larger number than [from]; non-numeric text always rolls up. */
private fun rollsUp(from: String, to: String): Boolean {
    val a = leadingNumber(from) ?: return true
    val b = leadingNumber(to) ?: return true
    return b >= a
}

private fun leadingNumber(text: String): Double? {
    val start = text.indexOfFirst { it.isDigit() }
    if (start < 0) return null
    var end = start
    while (end < text.length && (text[end].isDigit() || text[end] == '.')) end++
    return text.substring(start, end).toDoubleOrNull()
}

/** Vertical reveal for rows that come and go inside a column (banners, mode switch, form errors). */
@Composable
internal fun ColumnScope.HomeReveal(
    visible: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val motion = LocalHomeMotionEnabled.current
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = if (motion) expandVertically(HomeMotion.glide(true), expandFrom = Alignment.Top) + fadeIn(HomeMotion.fade(true, 220))
        else EnterTransition.None,
        exit = if (motion) shrinkVertically(HomeMotion.glide(true), shrinkTowards = Alignment.Top) + fadeOut(HomeMotion.fade(true, 120))
        else ExitTransition.None,
        label = "home-reveal",
    ) { content() }
}

/** Scale-and-fade for floating pieces that appear over the page (floating buttons, toasts, chips). */
@Composable
internal fun HomePop(
    visible: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val motion = LocalHomeMotionEnabled.current
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = if (motion) scaleIn(HomeMotion.pop(true), initialScale = .72f) + fadeIn(HomeMotion.fade(true, 160)) else EnterTransition.None,
        exit = if (motion) scaleOut(HomeMotion.fade(true, 140), targetScale = .8f) + fadeOut(HomeMotion.fade(true, 120)) else ExitTransition.None,
        label = "home-pop",
    ) { content() }
}

/** A value that eases towards [target]; used for bars, gauges and chart reveals. */
@Composable
internal fun homeAnimatedFraction(target: Float, label: String = "home-fraction"): Float {
    val value by animateFloatAsState(target.coerceIn(0f, 1f), HomeMotion.glide(LocalHomeMotionEnabled.current), label = label)
    return value
}

/**
 * Plays once when [key] first appears: 0 → 1 over [durationMs]. Used to draw charts in and to
 * give freshly inserted rows a short fade. With motion off it is 1 from the first frame.
 */
@Composable
internal fun rememberHomeReveal(key: Any?, durationMs: Int = 520): () -> Float {
    val motion = LocalHomeMotionEnabled.current
    val progress = remember(key) { Animatable(if (motion) 0f else 1f) }
    LaunchedEffect(key, motion) {
        if (motion) progress.animateTo(1f, tween(durationMs, easing = HomeMotion.Decelerate)) else progress.snapTo(1f)
    }
    return { progress.value }
}

/** Placeholder block with a slow light sweep, shown while the first snapshot is still loading. */
@Composable
internal fun HomeSkeleton(modifier: Modifier = Modifier, shape: Shape = HomeDims.innerShape) {
    val c = LocalHomeColors.current
    val base = if (c.dark) c.sunken else c.surface
    val glow = if (c.dark) Color.White.copy(alpha = .05f) else c.bg.copy(alpha = .9f)
    if (!LocalHomeMotionEnabled.current) {
        Box(modifier.background(base, shape))
        return
    }
    val transition = rememberInfiniteTransition(label = "home-skeleton")
    val shift by transition.animateFloat(
        initialValue = -1f,
        targetValue = 2f,
        animationSpec = infiniteRepeatable(tween(1500, easing = LinearEasing), RepeatMode.Restart),
        label = "home-skeleton-shift",
    )
    Box(
        modifier
            .clip(shape)
            .background(base)
            .drawBehind {
                val band = size.width * .6f
                val x = shift * size.width
                drawRect(
                    brush = Brush.horizontalGradient(
                        colors = listOf(Color.Transparent, glow, Color.Transparent),
                        startX = x - band / 2f,
                        endX = x + band / 2f,
                    ),
                )
            },
    )
}

/** Slow continuous rotation in degrees while [active]; rests at 0. */
@Composable
internal fun homeSpinAngle(active: Boolean, periodMs: Int = 900): Float {
    if (!active || !LocalHomeMotionEnabled.current) return 0f
    val transition = rememberInfiniteTransition(label = "home-spin")
    val angle by transition.animateFloat(0f, 360f, infiniteRepeatable(tween(periodMs, easing = LinearEasing), RepeatMode.Restart), label = "home-spin-angle")
    return angle
}

/** Gentle vertical float for empty-state artwork. */
@Composable
internal fun Modifier.homeFloat(distance: Dp = 4.dp): Modifier {
    if (!LocalHomeMotionEnabled.current) return this
    val transition = rememberInfiniteTransition(label = "home-float")
    val phase by transition.animateFloat(-1f, 1f, infiniteRepeatable(tween(2400, easing = HomeMotion.Emphasized), RepeatMode.Reverse), label = "home-float-phase")
    return graphicsLayer { translationY = phase * distance.toPx() }
}
