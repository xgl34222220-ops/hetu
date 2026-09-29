package io.github.xgl34222220.hetu

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import dev.chrisbanes.haze.materials.HazeMaterials
import io.github.xgl34222220.hetu.ui.HetuHaptic
import io.github.xgl34222220.hetu.ui.rememberHetuHaptics
import kotlinx.coroutines.delay

/** Whether frosted-glass bars are drawn (user switch 「模糊效果」). */
internal val LocalHxBlur = staticCompositionLocalOf { true }

/* ------------------------------------------------------------------ */
/*  Depth                                                               */
/* ------------------------------------------------------------------ */

/** Soft, diffuse elevation for light mode; dark mode relies on hairline borders instead. */
@Composable
internal fun Modifier.hxSoftShadow(shape: Shape, elevation: Dp = 10.dp): Modifier {
    val c = Hx.colors
    return if (c.dark) this else this.shadow(
        elevation = elevation,
        shape = shape,
        clip = false,
        ambientColor = Color(0xFF12161A).copy(alpha = .035f),
        spotColor = Color(0xFF12161A).copy(alpha = .07f),
    )
}

/* ------------------------------------------------------------------ */
/*  Staggered first-appearance                                         */
/* ------------------------------------------------------------------ */

/** Per-page entrance: items rise into place once, never again when scrolled back. */
@Stable
internal class HxStagger(played: Boolean) {
    var played by mutableStateOf(played)
}

@Composable
internal fun rememberHxStagger(): HxStagger {
    val saved = rememberSaveable { mutableStateOf(false) }
    val stagger = remember { HxStagger(saved.value) }
    LaunchedEffect(Unit) {
        delay(900)
        stagger.played = true
        saved.value = true
    }
    return stagger
}

@Composable
internal fun Modifier.hxEnter(stagger: HxStagger, index: Int): Modifier {
    if (stagger.played) return this
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(40L * index.coerceAtMost(8))
        progress.animateTo(1f, tween(460, easing = HxMotion.Emphasized))
    }
    return this.graphicsLayer {
        val p = progress.value
        alpha = p
        translationY = (1f - p) * 22.dp.toPx()
        val s = .97f + .03f * p
        scaleX = s
        scaleY = s
    }
}

/* ------------------------------------------------------------------ */
/*  Rolling digits                                                      */
/* ------------------------------------------------------------------ */

/** Each digit rolls up or down to its new value, like an odometer. */
@Composable
internal fun HxRollingText(text: String, style: TextStyle, color: Color, modifier: Modifier = Modifier) {
    Row(modifier) {
        val length = text.length
        text.forEachIndexed { index, char ->
            key(length - index) {
                AnimatedContent(
                    targetState = char,
                    transitionSpec = {
                        if (initialState.isDigit() && targetState.isDigit()) {
                            val up = targetState > initialState
                            (slideInVertically(tween(HxMotion.Medium, easing = HxMotion.Emphasized)) { h -> if (up) h else -h } + fadeIn(tween(HxMotion.Medium)))
                                .togetherWith(slideOutVertically(tween(HxMotion.Medium, easing = HxMotion.Emphasized)) { h -> if (up) -h else h } + fadeOut(tween(HxMotion.Short)))
                                .using(SizeTransform(clip = true))
                        } else {
                            fadeIn(tween(HxMotion.Short)).togetherWith(fadeOut(tween(HxMotion.Short))).using(SizeTransform(clip = true))
                        }
                    },
                    label = "digit",
                ) { value ->
                    Text(value.toString(), style = style.merge(HxNumberStyle), color = color, maxLines = 1)
                }
            }
        }
    }
}

/* ------------------------------------------------------------------ */
/*  Skeleton                                                            */
/* ------------------------------------------------------------------ */

@Composable
internal fun rememberHxShimmer(): Brush {
    val c = Hx.colors
    val transition = rememberInfiniteTransition(label = "shimmer")
    val x by transition.animateFloat(
        initialValue = -1f,
        targetValue = 2f,
        animationSpec = infiniteRepeatable(tween(1300, easing = LinearEasing), RepeatMode.Restart),
        label = "shimmerX",
    )
    val base = c.surfaceMuted
    val highlight = if (c.dark) Color.White.copy(alpha = .06f) else Color.White.copy(alpha = .85f)
    return Brush.linearGradient(
        listOf(base, highlight, base),
        start = Offset(x * 600f, 0f),
        end = Offset((x + 1f) * 600f, 200f),
    )
}

/** Placeholder rows that mirror the shape of the content being loaded. */
@Composable
internal fun HxSkeletonRows(count: Int = 5, modifier: Modifier = Modifier) {
    val brush = rememberHxShimmer()
    Column(modifier.fillMaxWidth().padding(horizontal = Hx.gutter), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        repeat(count) { i ->
            Row(
                Modifier.fillMaxWidth().clip(Hx.rowShape).background(Hx.colors.surface).padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(36.dp).clip(Hx.rowShape).background(brush))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Box(Modifier.fillMaxWidth(if (i % 2 == 0) .62f else .48f).height(12.dp).clip(Hx.pillShape).background(brush))
                    Spacer(Modifier.height(8.dp))
                    Box(Modifier.fillMaxWidth(.34f).height(10.dp).clip(Hx.pillShape).background(brush))
                }
            }
        }
    }
}

/* ------------------------------------------------------------------ */
/*  Floating glass dock                                                 */
/* ------------------------------------------------------------------ */

internal data class HxDockItem(val key: String, val label: String, val icon: ImageVector)

internal val HxDockHeight = 64.dp

/**
 * Floating frosted dock. The selection pill glides between items on a spring and the
 * newly selected icon gives a small bounce; content scrolls underneath the glass.
 */
@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
internal fun HxDock(
    items: List<HxDockItem>,
    selected: String,
    onSelect: (String) -> Unit,
    hazeState: HazeState?,
    modifier: Modifier = Modifier,
) {
    val c = Hx.colors
    val haptics = rememberHetuHaptics()
    val density = LocalDensity.current
    val shape = Hx.pillShape
    var widthPx by remember { mutableIntStateOf(0) }
    val index = items.indexOfFirst { it.key == selected }.coerceAtLeast(0)
    val itemWidth = with(density) { (widthPx / items.size.coerceAtLeast(1)).toDp() }
    val indicatorX by animateDpAsState(
        itemWidth * index,
        spring(dampingRatio = .78f, stiffness = Spring.StiffnessMediumLow),
        label = "dockIndicator",
    )
    val blur = LocalHxBlur.current && hazeState != null
    Box(
        modifier
            .navigationBarsPadding()
            .padding(start = 20.dp, end = 20.dp, bottom = 10.dp)
            .fillMaxWidth()
            .height(HxDockHeight)
            .shadow(
                elevation = 18.dp,
                shape = shape,
                clip = false,
                ambientColor = Color(0xFF12161A).copy(alpha = if (c.dark) .20f else .06f),
                spotColor = Color(0xFF12161A).copy(alpha = if (c.dark) .30f else .12f),
            )
            .clip(shape)
            .then(
                if (blur) Modifier.hazeEffect(state = hazeState!!, style = HazeMaterials.ultraThin()) {
                    blurRadius = 24.dp
                    noiseFactor = .01f
                } else Modifier.background(c.surface),
            )
            .background(if (blur) c.surface.copy(alpha = if (c.dark) .55f else .62f) else Color.Transparent)
            .border(0.5.dp, if (c.dark) Color.White.copy(alpha = .08f) else Color.White.copy(alpha = .7f), shape)
            .padding(5.dp)
            .onSizeChanged { widthPx = it.width },
    ) {
        if (widthPx > 0) {
            Box(
                Modifier
                    .offset(x = indicatorX)
                    .width(itemWidth)
                    .fillMaxHeight()
                    .clip(shape)
                    .background(c.accentSoft),
            )
        }
        Row(Modifier.fillMaxSize()) {
            items.forEach { item ->
                val active = item.key == selected
                val tint by animateColorAsState(if (active) c.accent else c.textMuted, tween(HxMotion.Medium), label = "dockTint")
                val bounce = remember { Animatable(1f) }
                LaunchedEffect(active) {
                    if (active) {
                        bounce.snapTo(.82f)
                        bounce.animateTo(1f, spring(dampingRatio = .45f, stiffness = Spring.StiffnessMedium))
                    }
                }
                val source = remember { MutableInteractionSource() }
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(shape)
                        .hxPressScale(source, .9f)
                        .clickable(interactionSource = source, indication = null) {
                            if (!active) haptics.perform(HetuHaptic.Tick)
                            onSelect(item.key)
                        },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(
                        item.icon,
                        contentDescription = item.label,
                        tint = tint,
                        modifier = Modifier.size(22.dp).graphicsLayer { scaleX = bounce.value; scaleY = bounce.value },
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(item.label, style = MaterialTheme.typography.labelSmall, color = tint, maxLines = 1)
                }
            }
        }
    }
}

/* ------------------------------------------------------------------ */
/*  Rings                                                               */
/* ------------------------------------------------------------------ */

/**
 * Around the power button: a spinning arc while an operation runs, a slow breathing
 * halo while the proxy is healthy, nothing when stopped.
 */
@Composable
internal fun HxPowerRing(busy: Boolean, breathing: Boolean, color: Color, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "ring")
    val angle by transition.animateFloat(0f, 360f, infiniteRepeatable(tween(1100, easing = LinearEasing)), label = "ringAngle")
    val breath by transition.animateFloat(0f, 1f, infiniteRepeatable(tween(2400), RepeatMode.Reverse), label = "ringBreath")
    val busyAlpha by animateFloatAsState(if (busy) 1f else 0f, tween(HxMotion.Medium), label = "busyAlpha")
    val haloAlpha by animateFloatAsState(if (breathing && !busy) 1f else 0f, tween(HxMotion.Long), label = "haloAlpha")
    androidx.compose.foundation.Canvas(modifier) {
        val stroke = 3.dp.toPx()
        if (haloAlpha > 0f) {
            val grow = 1f + breath * .08f
            drawCircle(
                color = color.copy(alpha = (.10f + .10f * (1f - breath)) * haloAlpha),
                radius = size.minDimension / 2f * grow,
            )
        }
        if (busyAlpha > 0f) {
            drawArc(
                color = color.copy(alpha = .9f * busyAlpha),
                startAngle = angle,
                sweepAngle = 100f,
                useCenter = false,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
                topLeft = Offset(stroke / 2, stroke / 2),
                size = androidx.compose.ui.geometry.Size(size.width - stroke, size.height - stroke),
            )
        }
    }
}

/** Slowly drifting two-colour wash used behind the home status card while running. */
@Composable
internal fun rememberHxAurora(active: Boolean): Brush {
    val c = Hx.colors
    val transition = rememberInfiniteTransition(label = "aurora")
    val t by transition.animateFloat(0f, 1f, infiniteRepeatable(tween(9000, easing = LinearEasing), RepeatMode.Reverse), label = "auroraT")
    val strength by animateFloatAsState(if (active) 1f else 0f, tween(700), label = "auroraStrength")
    val a = c.accentSoft.copy(alpha = .9f * strength)
    val b = c.goodSoft.copy(alpha = .55f * strength)
    return Brush.linearGradient(
        colors = listOf(a, b, c.surface.copy(alpha = 0f)),
        start = Offset(-200f + 400f * t, 0f),
        end = Offset(900f + 300f * t, 700f),
    )
}

@Suppress("unused")
private val HxRingShape = CircleShape
