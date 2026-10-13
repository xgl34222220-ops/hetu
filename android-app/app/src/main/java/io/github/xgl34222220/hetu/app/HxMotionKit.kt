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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
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

/**
 * The one motion switch every helper here reads: false when the system animator duration
 * scale is 0 (「移除动画」), the in-app 动画 switch is off, or power saving / low-RAM degrades
 * the UI ([HetuAppTheme] folds all three into [io.github.xgl34222220.hetu.ui.LocalHetuMotionEnabled]).
 * With it false every helper renders its final state at once and starts no frame loop.
 */
@Composable
internal fun hxMotion(): Boolean = io.github.xgl34222220.hetu.ui.LocalHetuMotionEnabled.current

/** Whether a perpetual (ambient) animation may run: only while it is visibly active and motion is allowed. */
internal fun hxAmbientAllowed(motion: Boolean, active: Boolean): Boolean = motion && active

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
    // With motion off (system 「移除动画」, the in-app switch, power saving) nothing ever rises.
    val motion = hxMotion()
    val stagger = remember { HxStagger(saved.value || !motion) }
    LaunchedEffect(Unit) {
        delay(900)
        stagger.played = true
        saved.value = true
    }
    return stagger
}

@Composable
internal fun Modifier.hxEnter(stagger: HxStagger, index: Int): Modifier {
    if (stagger.played || !hxMotion()) return this
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
    // Same nodes with motion on or off (tests and TalkBack read one tree); only the specs change.
    val motion = hxMotion()
    Row(modifier) {
        val length = text.length
        text.forEachIndexed { index, char ->
            key(length - index) {
                AnimatedContent(
                    targetState = char,
                    transitionSpec = {
                        if (!motion) {
                            androidx.compose.animation.EnterTransition.None.togetherWith(androidx.compose.animation.ExitTransition.None)
                                .using(SizeTransform(clip = true) { _, _ -> androidx.compose.animation.core.snap() })
                        } else if (initialState.isDigit() && targetState.isDigit()) {
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
    if (!hxMotion()) return Brush.linearGradient(listOf(c.surfaceMuted, c.surfaceMuted))
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

/**
 * Placeholder for a card of rows while its content loads: the card itself with a slow light
 * sweeping across it, and quiet bars where the icons and the two lines of each row will be.
 */
@Composable
internal fun HxSkeletonRows(count: Int = 5, modifier: Modifier = Modifier) {
    val c = io.github.xgl34222220.hetu.home.LocalHomeColors.current
    val bar = if (c.dark) Color.White.copy(alpha = .07f) else c.sunken
    Box(modifier.fillMaxWidth().padding(horizontal = io.github.xgl34222220.hetu.home.HomeDims.gutter).padding(bottom = io.github.xgl34222220.hetu.home.HomeDims.gap)) {
        io.github.xgl34222220.hetu.home.HomeSkeleton(Modifier.matchParentSize(), io.github.xgl34222220.hetu.home.HomeDims.cardShape)
        Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            repeat(count) { i ->
                Row(
                    Modifier.fillMaxWidth().height(68.dp).padding(horizontal = 18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(26.dp).clip(RoundedCornerShape(8.dp)).background(bar))
                    Spacer(Modifier.width(24.dp))
                    Column(Modifier.weight(1f)) {
                        Box(Modifier.fillMaxWidth(if (i % 3 == 0) .62f else if (i % 3 == 1) .5f else .7f).height(13.dp).clip(Hx.pillShape).background(bar))
                        Spacer(Modifier.height(9.dp))
                        Box(Modifier.fillMaxWidth(if (i % 2 == 0) .34f else .42f).height(10.dp).clip(Hx.pillShape).background(bar))
                    }
                }
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
    val motion = hxMotion()
    val lead: androidx.compose.animation.core.AnimationSpec<Dp> = if (motion) spring(dampingRatio = .8f, stiffness = 620f) else androidx.compose.animation.core.snap()
    val trail: androidx.compose.animation.core.AnimationSpec<Dp> = if (motion) spring(dampingRatio = .82f, stiffness = 230f) else androidx.compose.animation.core.snap()
    val indicatorLeft by animateDpAsState(itemWidth * index, if (forward) trail else lead, label = "dockLeft")
    val indicatorRight by animateDpAsState(itemWidth * (index + 1), if (forward) lead else trail, label = "dockRight")
    val hide by animateFloatAsState(if (visible) 0f else 1f, if (motion) spring(dampingRatio = .9f, stiffness = 380f) else androidx.compose.animation.core.snap(), label = "dockHide")
    val blur = LocalHxBlur.current && io.github.xgl34222220.hetu.ui.LocalHetuGlassEffectsEnabled.current && hazeState != null
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
                val tint by animateColorAsState(if (active) c.accent else c.text.copy(alpha = .92f), if (motion) tween(HxMotion.Medium) else androidx.compose.animation.core.snap(), label = "dockTint")
                val bounce = remember { Animatable(1f) }
                LaunchedEffect(active) {
                    if (active && motion) {
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
    val motion = hxMotion()
    // Perpetual values exist only while something moves; at rest (or with motion off) the
    // ring asks for no frames at all. They are read in the draw phase only.
    val spinning = motion && busy
    val breathingNow = motion && breathing && !busy
    val angleState = if (spinning) {
        rememberInfiniteTransition(label = "ring").animateFloat(0f, 360f, infiniteRepeatable(tween(1100, easing = LinearEasing)), label = "ringAngle")
    } else null
    val breathState = if (breathingNow) {
        rememberInfiniteTransition(label = "ringHalo").animateFloat(0f, 1f, infiniteRepeatable(tween(2400), RepeatMode.Reverse), label = "ringBreath")
    } else null
    val busyAlpha by animateFloatAsState(if (busy) 1f else 0f, if (motion) tween(HxMotion.Medium) else androidx.compose.animation.core.snap(), label = "busyAlpha")
    val haloAlpha by animateFloatAsState(if (breathing && !busy) 1f else 0f, if (motion) tween(HxMotion.Long) else androidx.compose.animation.core.snap(), label = "haloAlpha")
    androidx.compose.foundation.Canvas(modifier) {
        val stroke = 3.dp.toPx()
        val angle = angleState?.value ?: -90f
        val breath = breathState?.value ?: 0f
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
    val motion = hxMotion()
    // The drift runs only while the wash is shown and motion is allowed; otherwise it rests mid-way.
    val t = if (motion && active) {
        val transition = rememberInfiniteTransition(label = "aurora")
        val drift by transition.animateFloat(0f, 1f, infiniteRepeatable(tween(9000, easing = LinearEasing), RepeatMode.Reverse), label = "auroraT")
        drift
    } else .5f
    val strength by animateFloatAsState(if (active) 1f else 0f, if (motion) tween(700) else androidx.compose.animation.core.snap(), label = "auroraStrength")
    val a = c.accentSoft.copy(alpha = .9f * strength)
    val b = c.goodSoft.copy(alpha = .55f * strength)
    return Brush.linearGradient(
        colors = listOf(a, b, c.surface.copy(alpha = 0f)),
        start = Offset(-200f + 400f * t, 0f),
        end = Offset(900f + 300f * t, 700f),
    )
}

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

    val motion = hxMotion()
    suspend fun settle(velocity: Float) {
        if (armed || velocity < -2600f) {
            haptics.perform(HetuHaptic.Confirm)
            if (motion) animate(offset, -widthPx.toFloat(), animationSpec = tween(HxMotion.Short, easing = HxMotion.Exit)) { v, _ -> offset = v }
            else offset = -widthPx.toFloat()
            armed = false
            onAction()
            // Normally the row is gone by now; if the action failed, bring it back.
            delay(1400)
            if (motion) animate(offset, 0f, animationSpec = spring(dampingRatio = .8f, stiffness = Spring.StiffnessMediumLow)) { v, _ -> offset = v }
            else offset = 0f
        } else {
            armed = false
            if (motion) animate(offset, 0f, animationSpec = spring(dampingRatio = .7f, stiffness = Spring.StiffnessMedium)) { v, _ -> offset = v }
            else offset = 0f
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

/* ------------------------------------------------------------------ */
/*  Motion pass 2026-10: shared, motion-aware building blocks           */
/* ------------------------------------------------------------------ */

/**
 * Inline banners, form errors and "unsaved changes" rows that come and go inside a list item.
 * Expands from the top and fades (enter 220 ms / exit 140 ms on the calm glide spring);
 * with motion off it appears and disappears in the same frame. The content itself is the
 * same node either way.
 */
@Composable
internal fun HxVisibility(
    visible: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val motion = hxMotion()
    androidx.compose.animation.AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = if (motion) {
            androidx.compose.animation.expandVertically(spring(dampingRatio = .86f, stiffness = 420f), expandFrom = Alignment.Top) +
                fadeIn(tween(220, easing = HxMotion.Emphasized))
        } else androidx.compose.animation.EnterTransition.None,
        exit = if (motion) {
            androidx.compose.animation.shrinkVertically(spring(dampingRatio = .9f, stiffness = 520f), shrinkTowards = Alignment.Top) +
                fadeOut(tween(140, easing = HxMotion.Exit))
        } else androidx.compose.animation.ExitTransition.None,
        label = "hx-visibility",
    ) { content() }
}

/**
 * Hierarchical content swap (folders, nested pickers): deeper slides in from the right a third
 * of the width while the old level drifts a sixth the other way; going up mirrors it.
 * [motion] false → instant swap.
 */
internal fun hxHierarchyTransition(deeper: Boolean, motion: Boolean): androidx.compose.animation.ContentTransform {
    if (!motion) return androidx.compose.animation.EnterTransition.None.togetherWith(androidx.compose.animation.ExitTransition.None)
    return (androidx.compose.animation.slideInHorizontally(tween(HxMotion.Long, easing = HxMotion.Emphasized)) { w -> if (deeper) w / 3 else -w / 3 } +
        fadeIn(tween(HxMotion.Medium)))
        .togetherWith(
            androidx.compose.animation.slideOutHorizontally(tween(HxMotion.Long, easing = HxMotion.Emphasized)) { w -> if (deeper) -w / 6 else w / 6 } +
                fadeOut(tween(HxMotion.Short)),
        )
}

/** Width of the [index]th placeholder bar of a skeleton row, so a stack of rows reads as text, not stripes. */
internal fun hxSkeletonWidth(index: Int, secondLine: Boolean): Float =
    if (secondLine) (if (index % 2 == 0) .34f else .42f)
    else when (index % 3) { 0 -> .62f; 1 -> .5f; else -> .7f }

/**
 * Placeholder rows for a list that is loading inside an existing card or group (configs, rule
 * sources, the editor). Bars in the shape of an icon and two lines of text, under one slow light
 * sweep. The sweep is a single infinite transition read in the draw phase only, so it never
 * recomposes; with motion off the bars are static. Carries no text, so nothing a test or
 * TalkBack looks for changes while it shows.
 */
@Composable
internal fun HxSkeletonLines(
    count: Int = 3,
    modifier: Modifier = Modifier,
    rowHeight: Dp = 60.dp,
    icon: Boolean = true,
) {
    val c = io.github.xgl34222220.hetu.home.LocalHomeColors.current
    val bar = if (c.dark) Color.White.copy(alpha = .07f) else c.sunken
    val glow = if (c.dark) Color.White.copy(alpha = .05f) else c.surface.copy(alpha = .9f)
    val shift = if (hxMotion()) {
        rememberInfiniteTransition(label = "hx-skeleton").animateFloat(
            initialValue = -1f,
            targetValue = 2f,
            animationSpec = infiniteRepeatable(tween(1500, easing = LinearEasing), RepeatMode.Restart),
            label = "hx-skeleton-shift",
        )
    } else null
    Column(
        modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .hxSkeletonSweep(shift, glow),
    ) {
        repeat(count.coerceAtLeast(1)) { i ->
            Row(
                Modifier.fillMaxWidth().height(rowHeight).padding(horizontal = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (icon) {
                    Box(Modifier.size(26.dp).clip(RoundedCornerShape(8.dp)).background(bar))
                    Spacer(Modifier.width(20.dp))
                }
                Column(Modifier.weight(1f)) {
                    Box(Modifier.fillMaxWidth(hxSkeletonWidth(i, false)).height(13.dp).clip(Hx.pillShape).background(bar))
                    Spacer(Modifier.height(9.dp))
                    Box(Modifier.fillMaxWidth(hxSkeletonWidth(i, true)).height(10.dp).clip(Hx.pillShape).background(bar))
                }
            }
        }
    }
}

/** Draws the skeleton's light band over the bars; reads [shift] only while drawing. */
private fun Modifier.hxSkeletonSweep(shift: androidx.compose.runtime.State<Float>?, glow: Color): Modifier =
    if (shift == null) this else this.drawWithContent {
        drawContent()
        val band = size.width * .6f
        val x = shift.value * size.width
        drawRect(
            brush = Brush.horizontalGradient(
                colors = listOf(Color.Transparent, glow, Color.Transparent),
                startX = x - band / 2f,
                endX = x + band / 2f,
            ),
        )
    }

/**
 * A small piece (busy ring, badge, check) that pops in when it is first composed: from
 * [initialScale] and transparent to rest on a lightly overshooting spring, draw phase only.
 * Leaves immediately. With [motion] false it is a no-op modifier.
 */
@Composable
internal fun hxPopIn(motion: Boolean = hxMotion(), initialScale: Float = .6f): Modifier {
    if (!motion) return Modifier
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) { progress.animateTo(1f, spring(dampingRatio = .62f, stiffness = 520f)) }
    return Modifier.graphicsLayer {
        val p = progress.value
        alpha = p.coerceIn(0f, 1f)
        val s = initialScale + (1f - initialScale) * p
        scaleX = s
        scaleY = s
    }
}

/**
 * Lazy-list rows of the Hx pages: inserted rows fade in (260 ms) and survivors glide to their
 * new slot on the calm spring; removed rows leave at once. With motion off the row is placed
 * directly, with no item animation node at all.
 */
@Composable
internal fun androidx.compose.foundation.lazy.LazyItemScope.hxAnimateItem(motion: Boolean = hxMotion()): Modifier =
    if (motion) Modifier.animateItem(fadeInSpec = tween(HxMotion.Medium), placementSpec = HxMotion.glide(), fadeOutSpec = null)
    else Modifier
