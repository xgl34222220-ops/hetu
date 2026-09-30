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
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import kotlinx.coroutines.launch
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.animation.core.animate
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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.nativeCanvas
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

/** Runtime shaders cannot draw into software bitmaps even when their window is accelerated.
 * Apply only to the effect/background layer; foreground controls remain separate. */
internal fun Modifier.hxHardwareEffectFallback(color: Color): Modifier = drawWithContent {
    if (drawContext.canvas.nativeCanvas.isHardwareAccelerated) drawContent() else drawRect(color)
}

/* ------------------------------------------------------------------ */
/*  Depth                                                               */
/* ------------------------------------------------------------------ */

/** Soft, diffuse elevation for light mode; dark mode relies on hairline borders instead. */
@Composable
internal fun Modifier.hxSoftShadow(shape: Shape, elevation: Dp = 4.dp): Modifier {
    val c = Hx.colors
    return if (c.dark) this else this.shadow(
        elevation = elevation,
        shape = shape,
        clip = false,
        ambientColor = Color(0xFF302B55).copy(alpha = .018f),
        spotColor = Color(0xFF302B55).copy(alpha = .035f),
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
    if (!LocalHxMotionEnabled.current || stagger.played) return this
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
    if (!LocalHxMotionEnabled.current) { Text(text, style = style.merge(HxNumberStyle), color = color, modifier = modifier); return }
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
    if (!LocalHxMotionEnabled.current) return Brush.linearGradient(listOf(c.surfaceMuted, c.surfaceMuted))
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

/** Placeholder rows that mirror the shape of the content being loaded (one grouped card). */
@Composable
internal fun HxSkeletonRows(count: Int = 5, modifier: Modifier = Modifier) {
    val brush = rememberHxShimmer()
    val c = Hx.colors
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = Hx.gutter)
            .clip(RoundedCornerShape(22.dp))
            .background(c.surface),
    ) {
        repeat(count) { i ->
            if (i > 0) Box(Modifier.padding(start = 58.dp).fillMaxWidth().height(0.5.dp).background(c.line.copy(alpha = .5f)))
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(32.dp).clip(RoundedCornerShape(10.dp)).background(brush))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Box(Modifier.fillMaxWidth(if (i % 3 == 0) .62f else if (i % 3 == 1) .5f else .7f).height(11.dp).clip(Hx.pillShape).background(brush))
                    Spacer(Modifier.height(8.dp))
                    Box(Modifier.fillMaxWidth(if (i % 2 == 0) .34f else .42f).height(9.dp).clip(Hx.pillShape).background(brush))
                }
                Spacer(Modifier.width(12.dp))
                Box(Modifier.size(width = 44.dp, height = 20.dp).clip(RoundedCornerShape(7.dp)).background(brush))
            }
        }
    }
}

/* ------------------------------------------------------------------ */
/*  Floating glass dock                                                 */
/* ------------------------------------------------------------------ */

internal data class HxDockItem(val key: String, val label: String, val icon: ImageVector)

internal val HxDockHeight = 54.dp

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
    visible: Boolean = true,
) {
    val c = Hx.colors
    val haptics = rememberHetuHaptics()
    val density = LocalDensity.current
    val shape = Hx.pillShape
    var widthPx by remember { mutableIntStateOf(0) }
    val index = items.indexOfFirst { it.key == selected }.coerceAtLeast(0)
    val itemWidth = with(density) { (widthPx / items.size.coerceAtLeast(1)).toDp() }
    // Liquid pill: the edge facing the destination leads, the other trails, so the pill
    // stretches across and settles back into one slot.
    val forward = rememberHxDirection(index)
    val lead = spring<Dp>(dampingRatio = .8f, stiffness = 620f)
    val trail = spring<Dp>(dampingRatio = .82f, stiffness = 230f)
    val indicatorLeft by animateDpAsState(itemWidth * index, if (forward) trail else lead, label = "dockLeft")
    val indicatorRight by animateDpAsState(itemWidth * (index + 1), if (forward) lead else trail, label = "dockRight")
    val hide by animateFloatAsState(if (visible) 0f else 1f, spring(dampingRatio = .9f, stiffness = 380f), label = "dockHide")
    val blur = LocalHxBlur.current && hazeState != null
    Box(
        modifier
            .graphicsLayer {
                translationY = hide * (size.height + 40.dp.toPx())
                alpha = 1f - hide * .6f
            }
            .navigationBarsPadding()
            .padding(start = 28.dp, end = 28.dp, bottom = 8.dp)
            .fillMaxWidth()
            .height(HxDockHeight)
            .shadow(
                elevation = 6.dp,
                shape = shape,
                clip = false,
                ambientColor = Color(0xFF12161A).copy(alpha = if (c.dark) .12f else .02f),
                spotColor = Color(0xFF12161A).copy(alpha = if (c.dark) .16f else .045f),
            )
            .clip(shape)
            .then(
                if (blur) Modifier.hazeEffect(state = hazeState!!, style = HazeMaterials.ultraThin()) {
                    blurRadius = 30.dp
                    noiseFactor = .008f
                } else Modifier.background(c.surface),
            )
            .background(
                if (blur) Brush.verticalGradient(
                    if (c.dark) listOf(
                        c.surface.copy(alpha = .24f),
                        c.surface.copy(alpha = .12f),
                    ) else listOf(
                        Color.White.copy(alpha = .11f),
                        Color(0xFFF1EEFA).copy(alpha = .035f),
                    ),
                ) else Brush.verticalGradient(listOf(c.surface, c.surface)),
            )
            .border(0.35.dp, if (c.dark) Color.White.copy(alpha = .09f) else Color.White.copy(alpha = .28f), shape)
            .padding(4.dp)
            .onSizeChanged { widthPx = it.width },
    ) {
        if (widthPx > 0) {
            Box(
                Modifier
                    .offset(x = indicatorLeft)
                    .width((indicatorRight - indicatorLeft).coerceAtLeast(0.dp))
                    .fillMaxHeight()
                    .clip(shape)
                    .background(
                        if (blur) Brush.verticalGradient(
                            if (c.dark) listOf(Color.White.copy(alpha = .10f), Color.White.copy(alpha = .04f))
                            else listOf(Color.White.copy(alpha = .16f), Color(0xFFF2EFFA).copy(alpha = .04f)),
                        ) else Brush.verticalGradient(listOf(c.accentSoft, c.accentSoft)),
                    )
                    .border(0.35.dp, if (c.dark) Color.White.copy(alpha = .11f) else Color.White.copy(alpha = .34f), shape),
            )
        }
        Row(Modifier.fillMaxSize()) {
            items.forEach { item ->
                val active = item.key == selected
                val tint by animateColorAsState(if (active) c.accent else c.text.copy(alpha = .92f), tween(HxMotion.Medium), label = "dockTint")
                val bounce = remember { Animatable(1f) }
                LaunchedEffect(active) {
                    if (active) {
                        bounce.snapTo(.90f)
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
                        modifier = Modifier.size(20.dp).graphicsLayer { scaleX = bounce.value; scaleY = bounce.value },
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        item.label,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = if (active) androidx.compose.ui.text.font.FontWeight.SemiBold else androidx.compose.ui.text.font.FontWeight.Medium,
                        color = tint,
                        maxLines = 1,
                    )
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
    if (!LocalHxMotionEnabled.current) return
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
    if (!LocalHxMotionEnabled.current) return Brush.linearGradient(listOf(c.surface, c.surface))
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

/* ------------------------------------------------------------------ */
/*  Interaction helpers                                                 */
/* ------------------------------------------------------------------ */

/** Tap + optional long-press on one surface (ripple kept). */
@OptIn(ExperimentalFoundationApi::class)
internal fun Modifier.hxCombinedClick(
    enabled: Boolean = true,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
): Modifier = this.combinedClickable(enabled = enabled, onLongClick = onLongClick, onClick = onClick)

/** Same as [hxCombinedClick] but sharing an interaction source (press scale + ripple). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun Modifier.hxCombinedClickSource(
    source: MutableInteractionSource,
    enabled: Boolean = true,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
): Modifier = this.combinedClickable(
    interactionSource = source,
    indication = androidx.compose.foundation.LocalIndication.current,
    enabled = enabled,
    onLongClick = onLongClick,
    onClick = onClick,
)

private class HxDirectionHolder(var last: Int, var forward: Boolean)

/** Remembers whether [index] last moved forward (to a higher index) or backward. */
@Composable
internal fun rememberHxDirection(index: Int): Boolean {
    val holder = remember { HxDirectionHolder(index, true) }
    if (index != holder.last) {
        holder.forward = index > holder.last
        holder.last = index
    }
    return holder.forward
}

/**
 * Swipe the row to the left to reveal a destructive action. Past the threshold the
 * action tints solid and gives a tick; releasing there runs [onAction]. Anything else
 * springs back. Tapping keeps working normally.
 */
@Composable
internal fun HxSwipeAction(
    label: String,
    icon: ImageVector,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    val c = Hx.colors
    val haptics = rememberHetuHaptics()
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    var offset by remember { mutableFloatStateOf(0f) }
    var widthPx by remember { mutableIntStateOf(0) }
    val thresholdPx = with(density) { 96.dp.toPx() }
    var armed by remember { mutableStateOf(false) }
    val actionTint by animateColorAsState(if (armed) Color.White else c.bad, tween(HxMotion.Short), label = "swipeTint")
    val actionBg by animateColorAsState(if (armed) c.bad else c.badSoft, tween(HxMotion.Short), label = "swipeBg")

    suspend fun settle(velocity: Float) {
        if (armed || velocity < -2600f) {
            haptics.perform(HetuHaptic.Confirm)
            animate(offset, -widthPx.toFloat(), animationSpec = tween(HxMotion.Short, easing = HxMotion.Exit)) { v, _ -> offset = v }
            armed = false
            onAction()
            // Normally the row is gone by now; if the action failed, bring it back.
            delay(1400)
            animate(offset, 0f, animationSpec = spring(dampingRatio = .8f, stiffness = Spring.StiffnessMediumLow)) { v, _ -> offset = v }
        } else {
            armed = false
            animate(offset, 0f, animationSpec = spring(dampingRatio = .7f, stiffness = Spring.StiffnessMedium)) { v, _ -> offset = v }
        }
    }

    Box(modifier.fillMaxWidth().onSizeChanged { widthPx = it.width }) {
        if (offset < -1f) {
            Row(
                Modifier.matchParentSize().background(actionBg).padding(end = 20.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val reveal = (-offset / thresholdPx).coerceIn(0f, 1f)
                Icon(
                    icon,
                    null,
                    tint = actionTint,
                    modifier = Modifier.size(20.dp).graphicsLayer {
                        val s = .6f + .4f * reveal
                        scaleX = s
                        scaleY = s
                        alpha = reveal
                    },
                )
                Spacer(Modifier.width(6.dp))
                Text(label, style = MaterialTheme.typography.labelLarge, color = actionTint, modifier = Modifier.graphicsLayer { alpha = reveal })
            }
        }
        Box(
            Modifier
                .graphicsLayer { translationX = offset }
                // Only a leftward drag is claimed; rightward/vertical drags fall through to the
                // pager or list, so rows never block page swipes.
                .pointerInput(enabled) {
                    if (!enabled) return@pointerInput
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        var claimed = false
                        val start = awaitHorizontalTouchSlopOrCancellation(down.id) { change, over ->
                            if (over < 0f || offset < -1f) {
                                change.consume()
                                claimed = true
                                offset = (offset + over).coerceIn(-widthPx.toFloat(), 0f)
                            }
                        }
                        if (start != null && claimed) {
                            val tracker = VelocityTracker()
                            tracker.addPosition(start.uptimeMillis, start.position)
                            horizontalDrag(start.id) { change ->
                                val delta = change.positionChange().x
                                // Rubber-band past the threshold so the row feels attached to the finger.
                                val resist = if (-offset > thresholdPx && delta < 0f) .45f else 1f
                                offset = (offset + delta * resist).coerceIn(-widthPx.toFloat(), 0f)
                                val nowArmed = -offset > thresholdPx
                                if (nowArmed != armed) {
                                    armed = nowArmed
                                    haptics.perform(if (nowArmed) HetuHaptic.Tick else HetuHaptic.Tap)
                                }
                                tracker.addPosition(change.uptimeMillis, change.position)
                                change.consume()
                            }
                            val velocity = tracker.calculateVelocity().x
                            scope.launch { settle(velocity) }
                        }
                    }
                },
        ) { content() }
    }
}
