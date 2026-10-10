package io.github.xgl34222220.hetu.ui

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest

/**
 * V18.3 motion vocabulary. Every screen draws its timing from here so presses,
 * reveals and page changes share one rhythm instead of per-widget magic numbers.
 * Spatial values may settle with a small overshoot; opacity never overshoots.
 */
internal object HetuMotion {
    const val PressInMs = 90
    const val QuickMs = 160
    const val StandardMs = 240
    const val EmphasizedMs = 320
    const val PressScale = .97f

    /** Material 3 standard curve: most state changes. */
    val Standard = CubicBezierEasing(.2f, 0f, 0f, 1f)
    /** Material 3 emphasized-decelerate: things arriving on screen. */
    val EmphasizedDecelerate = CubicBezierEasing(.05f, .7f, .1f, 1f)
    /** Material 3 emphasized-accelerate: things leaving the screen. */
    val EmphasizedAccelerate = CubicBezierEasing(.3f, 0f, .8f, .15f)

    fun <T> pressIn(motion: Boolean): FiniteAnimationSpec<T> =
        if (motion) tween(PressInMs, easing = Standard) else snap()
    /** Slightly under-damped release gives the "rubber" return users feel as tactile. */
    fun <T> pressOut(motion: Boolean): FiniteAnimationSpec<T> =
        if (motion) spring(dampingRatio = .62f, stiffness = 520f) else snap()
    fun <T> settle(motion: Boolean): FiniteAnimationSpec<T> =
        if (motion) spring(dampingRatio = .86f, stiffness = 380f) else snap()
    fun <T> fade(motion: Boolean, durationMs: Int = QuickMs): FiniteAnimationSpec<T> =
        if (motion) tween(durationMs, easing = Standard) else snap()
    fun <T> enter(motion: Boolean, durationMs: Int = EmphasizedMs, delayMs: Int = 0): FiniteAnimationSpec<T> =
        if (motion) tween(durationMs, delayMillis = delayMs, easing = EmphasizedDecelerate) else snap()
}

internal enum class HetuHaptic { Tap, Tick, ToggleOn, ToggleOff, Confirm, Reject, LongPress }

/**
 * One haptic vocabulary on top of the platform View API. The user switch is read at
 * perform-time (an in-memory SharedPreferences lookup), so no listener per row.
 * The system-wide "touch feedback" setting is still honoured by the platform.
 */
@Stable
internal class HetuHaptics(private val view: View) {
    private val prefs = view.context.getSharedPreferences("hetu", 0)

    fun perform(kind: HetuHaptic) {
        if (!prefs.getBoolean(PREF_KEY, true)) return
        val sdk = Build.VERSION.SDK_INT
        val constant = when (kind) {
            HetuHaptic.Tap -> HapticFeedbackConstants.VIRTUAL_KEY
            HetuHaptic.Tick -> if (sdk >= 34) HapticFeedbackConstants.SEGMENT_TICK else HapticFeedbackConstants.CLOCK_TICK
            HetuHaptic.ToggleOn -> if (sdk >= 34) HapticFeedbackConstants.TOGGLE_ON else HapticFeedbackConstants.CLOCK_TICK
            HetuHaptic.ToggleOff -> if (sdk >= 34) HapticFeedbackConstants.TOGGLE_OFF else HapticFeedbackConstants.CLOCK_TICK
            HetuHaptic.Confirm -> if (sdk >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY
            HetuHaptic.Reject -> if (sdk >= 30) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.LONG_PRESS
            HetuHaptic.LongPress -> HapticFeedbackConstants.LONG_PRESS
        }
        view.performHapticFeedback(constant)
    }

    companion object {
        const val PREF_KEY = "hapticFeedback"
    }
}

@Composable
internal fun rememberHetuHaptics(): HetuHaptics {
    val view = LocalView.current
    return remember(view) { HetuHaptics(view) }
}

/**
 * Interaction-driven press dip. Unlike collectIsPressedAsState + animate*AsState, a quick
 * tap (press and release inside one frame) still produces a visible dip and rebound.
 * Scroll-cancelled presses return without the rebound.
 */
@Composable
internal fun Modifier.hetuPressScale(
    source: InteractionSource,
    enabled: Boolean = true,
    pressedScale: Float = HetuMotion.PressScale,
    motion: Boolean = LocalHetuMotionEnabled.current,
): Modifier {
    val scale = remember { Animatable(1f) }
    val currentEnabled by rememberUpdatedState(enabled)
    LaunchedEffect(source, motion, pressedScale) {
        scale.snapTo(1f)
        if (!motion) return@LaunchedEffect
        source.interactions.collectLatest { interaction ->
            when (interaction) {
                is PressInteraction.Press -> if (currentEnabled) scale.animateTo(pressedScale, HetuMotion.pressIn(true))
                is PressInteraction.Release -> {
                    // Guarantee a perceptible dip for taps shorter than the press-in curve.
                    val halfway = 1f - (1f - pressedScale) * .5f
                    if (currentEnabled && scale.value > halfway) scale.animateTo(pressedScale, tween(55, easing = HetuMotion.Standard))
                    scale.animateTo(1f, HetuMotion.pressOut(true))
                }
                is PressInteraction.Cancel -> scale.animateTo(1f, spring(dampingRatio = 1f, stiffness = 600f))
                else -> Unit
            }
            Unit
        }
    }
    return graphicsLayer { scaleX = scale.value; scaleY = scale.value }
}

/**
 * Grouped-list press highlight (iOS/MIUI settings style). Rows inside one rounded group
 * must not scale; they light up instead, fast in and slow out, and a short tap still flashes.
 */
@Composable
internal fun Modifier.hetuPressHighlight(
    source: InteractionSource,
    color: Color,
    enabled: Boolean = true,
    motion: Boolean = LocalHetuMotionEnabled.current,
): Modifier {
    val progress = remember { Animatable(0f) }
    val currentEnabled by rememberUpdatedState(enabled)
    LaunchedEffect(source, motion) {
        progress.snapTo(0f)
        source.interactions.collectLatest { interaction ->
            when (interaction) {
                is PressInteraction.Press -> if (currentEnabled) {
                    if (motion) progress.animateTo(1f, tween(70, easing = HetuMotion.Standard)) else progress.snapTo(1f)
                }
                is PressInteraction.Release -> if (motion) {
                    if (currentEnabled && progress.value < .75f) progress.animateTo(1f, tween(50))
                    progress.animateTo(0f, tween(320, easing = HetuMotion.Standard))
                } else progress.snapTo(0f)
                is PressInteraction.Cancel ->
                    if (motion) progress.animateTo(0f, tween(HetuMotion.QuickMs)) else progress.snapTo(0f)
                else -> Unit
            }
            Unit
        }
    }
    return drawBehind {
        val alpha = progress.value.coerceIn(0f, 1f)
        if (alpha > 0f) drawRect(color = color, alpha = alpha)
    }
}

/**
 * Page-level entrance coordinator. Items composed during the first ~0.7s of a page
 * cascade in; items composed later (scrolling back into view) appear immediately,
 * so long lists never replay the entrance.
 */
@Stable
internal class HetuStaggerState(initiallySettled: Boolean) {
    var settled by mutableStateOf(initiallySettled)
        internal set
}

@Composable
internal fun rememberHetuStagger(motion: Boolean = LocalHetuMotionEnabled.current): HetuStaggerState {
    val state = remember { HetuStaggerState(!motion) }
    LaunchedEffect(state) {
        delay(700)
        state.settled = true
    }
    return state
}

/** Draw-phase only: no relayout per frame, no semantics change, safe inside lazy items. */
@Composable
internal fun Modifier.hetuStaggerIn(
    state: HetuStaggerState,
    index: Int,
    motion: Boolean = LocalHetuMotionEnabled.current,
    distance: androidx.compose.ui.unit.Dp = 12.dp,
): Modifier {
    val animate = remember { motion && !state.settled }
    // Rows composed after the first cascade (most rows while scrolling a long list) never
    // animate: give them no Animatable and no extra render layer at all.
    if (!animate) return this
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        progress.animateTo(1f, HetuMotion.enter(true, durationMs = 300, delayMs = index.coerceIn(0, 7) * 38))
    }
    return graphicsLayer {
        val p = progress.value.coerceIn(0f, 1f)
        alpha = p
        translationY = (1f - p) * distance.toPx()
    }
}

/**
 * Live lists (connections refresh every 3s, subscriptions/rule sets update in place):
 * inserted rows fade in and survivors glide to their new slot instead of jumping.
 * Removed rows leave immediately (no fade-out): a tab switch replaces every key, and
 * lingering ghosts of the previous tab would overlap the incoming content.
 */
internal fun LazyItemScope.hetuAnimateItem(motion: Boolean): Modifier =
    if (motion) Modifier.animateItem(
        fadeInSpec = tween(220, easing = HetuMotion.Standard),
        placementSpec = spring<IntOffset>(dampingRatio = .9f, stiffness = 420f),
        fadeOutSpec = null,
    ) else Modifier
