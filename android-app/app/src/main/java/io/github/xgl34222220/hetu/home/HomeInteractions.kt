package io.github.xgl34222220.hetu.home

import android.animation.ValueAnimator
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.offset
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import io.github.xgl34222220.hetu.ui.LocalHetuMotionEnabled
import io.github.xgl34222220.hetu.ui.hetuPressScale

/** Shared with the panel so pressed, route and list animations respect the same switch. */
internal val LocalHomeMotionEnabled = staticCompositionLocalOf { true }

@Composable
internal fun homeMotionEnabled(): Boolean = LocalHetuMotionEnabled.current && ValueAnimator.areAnimatorsEnabled()

/**
 * Uses the existing interaction-driven dip; scroll cancellation returns to the resting size.
 * Pass [interactionSource] to draw [homePressTint] from the same presses inside the caller's clip.
 */
@Composable
internal fun Modifier.homeTap(
    enabled: Boolean = true,
    role: Role = Role.Button,
    onClickLabel: String? = null,
    interactionSource: MutableInteractionSource? = null,
    onClick: () -> Unit,
): Modifier {
    val source = interactionSource ?: remember { MutableInteractionSource() }
    return hetuPressScale(source, enabled, pressedScale = .975f, motion = LocalHomeMotionEnabled.current)
        .clickable(source, indication = null, enabled = enabled, role = role, onClickLabel = onClickLabel, onClick = onClick)
}

/** [homeTap] with a long press as well (spoken as its own action by TalkBack). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun Modifier.homeTap(
    enabled: Boolean = true,
    role: Role = Role.Button,
    onClickLabel: String? = null,
    onLongClickLabel: String?,
    onLongClick: () -> Unit,
    interactionSource: MutableInteractionSource? = null,
    onClick: () -> Unit,
): Modifier {
    val source = interactionSource ?: remember { MutableInteractionSource() }
    return hetuPressScale(source, enabled, pressedScale = .975f, motion = LocalHomeMotionEnabled.current)
        .combinedClickable(source, indication = null, enabled = enabled, role = role, onClickLabel = onClickLabel,
            onLongClickLabel = onLongClickLabel, onLongClick = onLongClick, onClick = onClick)
}

/**
 * Pressed feedback that does not depend on motion: a faint ink wash over the surface, beneath
 * the content, while [source] is pressed. With motion it fades in and out; with reduced motion
 * it switches on and off, held briefly so even a quick tap visibly lands. Nothing is laid out
 * again and the page never recomposes for it: the level is read only while drawing.
 * Place it after the caller's clip and background.
 */
@Composable
internal fun Modifier.homePressTint(source: InteractionSource, enabled: Boolean = true): Modifier {
    val c = LocalHomeColors.current
    val motion = LocalHomeMotionEnabled.current
    val level = remember { Animatable(0f) }
    val active by rememberUpdatedState(enabled)
    LaunchedEffect(source, motion) {
        level.snapTo(0f)
        source.interactions.collectLatest { interaction ->
            when (interaction) {
                is PressInteraction.Press -> if (active) { if (motion) level.animateTo(1f, tween(70)) else level.snapTo(1f) }
                is PressInteraction.Release, is PressInteraction.Cancel -> if (motion) {
                    // A tap shorter than the fade-in still shows a full wash before it eases out.
                    if (interaction is PressInteraction.Release && active && level.value < .6f) level.animateTo(1f, tween(60))
                    level.animateTo(0f, tween(240, easing = HomeMotion.Decelerate))
                } else {
                    if (interaction is PressInteraction.Release && level.value > 0f) delay(90L)
                    level.snapTo(0f)
                }
                else -> Unit
            }
            Unit
        }
    }
    val ink = if (c.dark) Color.White.copy(alpha = .08f) else c.t1.copy(alpha = .06f)
    return drawBehind {
        val shown = level.value
        if (shown > 0f) drawRect(ink.copy(alpha = ink.alpha * shown))
    }
}

/**
 * Grows the touch box of a small tappable (a text switch) by [horizontal] and [vertical] on each
 * side without moving or resizing anything: the wrapped node is measured larger, placed back by
 * the same amount and reports its original size. Put it before the click modifier and pad the
 * content by the same amounts after it.
 */
internal fun Modifier.homeTouchExpand(horizontal: Dp, vertical: Dp): Modifier = layout { measurable, constraints ->
    val h = horizontal.roundToPx()
    val v = vertical.roundToPx()
    val placeable = measurable.measure(constraints.offset(2 * h, 2 * v))
    layout((placeable.width - 2 * h).coerceAtLeast(0), (placeable.height - 2 * v).coerceAtLeast(0)) {
        placeable.place(-h, -v)
    }
}

/** A real nested-scroll refresh gesture; the caller owns its request and completion state. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HomeRefreshBox(
    refreshing: Boolean,
    onRefresh: () -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    indicatorPadding: PaddingValues = PaddingValues(),
    content: @Composable BoxScope.() -> Unit,
) {
    val state = rememberPullToRefreshState()
    val colors = LocalHomeColors.current
    val motion = LocalHomeMotionEnabled.current
    // The page follows the finger a little while it is pulled (rubber-banded past the trigger
    // point) and eases back once the refresh starts, so at rest — refreshing or not — nothing
    // is offset. Both values are read in the layer only: pulling never recomposes the page.
    val follow = androidx.compose.animation.core.animateFloatAsState(
        if (refreshing || !motion) 0f else 1f, HomeMotion.glide(motion), label = "home-refresh-follow",
    )
    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = { if (!refreshing) onRefresh() },
        state = state,
        modifier = modifier.testTag("refresh:$label").semantics {
            customActions = listOf(CustomAccessibilityAction(label) {
                if (!refreshing) onRefresh()
                true
            })
        },
        indicator = {
            PullToRefreshDefaults.Indicator(
                state = state,
                isRefreshing = refreshing,
                modifier = Modifier.align(Alignment.TopCenter).padding(indicatorPadding),
                containerColor = colors.surface,
                color = colors.accent,
            )
        },
    ) {
        Box(
            Modifier.fillMaxSize().graphicsLayer {
                val f = state.distanceFraction
                val pull = f.coerceIn(0f, 1f) * 22.dp.toPx() + (f - 1f).coerceAtLeast(0f) * 8.dp.toPx()
                translationY = pull * follow.value
            },
            content = content,
        )
    }
}
