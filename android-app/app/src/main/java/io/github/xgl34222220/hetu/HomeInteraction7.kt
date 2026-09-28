package io.github.xgl34222220.hetu

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.nestedscroll.*
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import dev.chrisbanes.haze.materials.HazeMaterials
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** A presentation group has one material surface, with independent interactive cells. */
internal val LocalHomeGroupedSurface = staticCompositionLocalOf { false }
internal val HomeHeaderCollapse = SemanticsPropertyKey<Float>("HomeHeaderCollapse")

/** One title node travels from the leading edge to the center. Actions never scroll away. */
@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
internal fun HomeCollapsingHeader(
    collapse: Float,
    motion: Boolean,
    haze: HazeState,
    color: Color,
    stretch: Float = 0f,
    actions: @Composable RowScope.() -> Unit,
) {
    val fraction by animateFloatAsState(collapse.coerceIn(0f, 1f),
        if (motion) tween(160, easing = FastOutSlowInEasing) else snap(), label = "header-collapse")
    val prefs = LocalContext.current.getSharedPreferences("hetu", 0)
    var blurEnabled by remember(prefs) { mutableStateOf(prefs.getBoolean("enableBlur", true)) }
    DisposableEffect(prefs) {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { p, key ->
            if (key == "enableBlur") blurEnabled = p.getBoolean(key, true)
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    val dark = MaterialTheme.colorScheme.background.luminance() < .5f
    val hardware = LocalView.current.isHardwareAccelerated
    Box(Modifier.fillMaxWidth().testTag("home-header").semantics {
        this[HomeHeaderCollapse] = fraction
        stateDescription = if (fraction >= .99f) "折叠标题" else "展开标题"
    }) {
        Box(Modifier.matchParentSize()
            .then(if (blurEnabled && hardware && fraction > .01f) Modifier.hazeEffect(haze, HazeMaterials.ultraThin()) {
                blurRadius = 16.dp
                noiseFactor = 0f
            } else Modifier)
            .background((if (dark) Color(0xFF151C27) else Color.White).copy(alpha = fraction * .80f)))
        Layout(content = {
            Text("河图", Modifier.testTag("home-brand").graphicsLayer {
                val amount = if (motion) stretch.coerceIn(0f, 48f) else 0f
                scaleX = 1f + amount / 2000f
                scaleY = scaleX
            }, color = color, fontSize = (28f - 8f * fraction).sp,
                lineHeight = 36.sp, fontWeight = FontWeight.Black, letterSpacing = .4.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(content = actions)
        }, modifier = Modifier.fillMaxWidth()) { measurables, constraints ->
            val side = 16.dp.roundToPx()
            val width = constraints.maxWidth
            val buttons = measurables[1].measure(constraints.copy(minWidth = 0, minHeight = 0))
            val title = measurables[0].measure(constraints.copy(minWidth = 0, minHeight = 0,
                maxWidth = (width - 2 * side - 2 * buttons.width).coerceAtLeast(1)))
            val height = constraints.constrainHeight(maxOf(56.dp.roundToPx(), title.height, buttons.height))
            layout(width, height) {
                val leading = side.toFloat()
                val centered = (width - title.width) / 2f
                title.placeRelative((leading + (centered - leading) * fraction).toInt(), (height - title.height) / 2)
                buttons.placeRelative(width - side - buttons.width, (height - buttons.height) / 2)
            }
        }
    }
}

/** Only decorates leftover top-edge motion; consumes nothing from real pull-to-refresh. */
@Stable
internal class HomeElasticity(private val scope: CoroutineScope, private val limit: Float) {
    var offset by mutableFloatStateOf(0f)
        private set
    private var settling: Job? = null
    fun pull(delta: Float) {
        settling?.cancel()
        offset = (offset + delta * .24f).coerceIn(0f, limit)
    }
    fun reset() { settling?.cancel(); offset = 0f }
    fun release() {
        settling?.cancel()
        if (offset <= 0f) return
        settling = scope.launch {
            Animatable(offset).animateTo(0f, spring(dampingRatio = .78f, stiffness = 400f)) {
                offset = value.coerceAtLeast(0f)
            }
            offset = 0f
        }
    }
}

@Composable
internal fun rememberHomeElasticity(list: LazyListState, motion: Boolean): Pair<HomeElasticity, NestedScrollConnection> {
    val scope = rememberCoroutineScope()
    val limit = with(LocalDensity.current) { 40.dp.toPx() }
    val state = remember(scope, limit) { HomeElasticity(scope, limit) }
    LaunchedEffect(motion) { if (!motion) state.reset() }
    val connection = remember(list, motion, state) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (motion && source == NestedScrollSource.UserInput && available.y < 0 && state.offset > 0) state.pull(available.y * 4f)
                return Offset.Zero
            }
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (motion && source == NestedScrollSource.UserInput && !list.canScrollBackward && available.y > 0) state.pull(available.y)
                return Offset.Zero
            }
            override suspend fun onPreFling(available: Velocity): Velocity {
                state.release()
                return Velocity.Zero
            }
            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                state.release()
                return Velocity.Zero
            }
        }
    }
    return state to connection
}

/** One text layer exits upward, changes only while invisible, and rolls up into its baseline. */
@Stable
internal class MetricRollState(initial: String) {
    var displayed by mutableStateOf(initial)
        internal set
    var leaving by mutableStateOf(false)
        internal set
    val progress = Animatable(1f)
}

@Composable
internal fun rememberMetricRoll(target: String, motion: Boolean): MetricRollState {
    val state = remember { MetricRollState(target) }
    LaunchedEffect(target, motion) {
        if (!motion) {
            state.displayed = target
            state.leaving = false
            state.progress.snapTo(1f)
        } else {
            if (state.displayed != target) {
                state.leaving = true
                state.progress.animateTo(0f, tween(80, easing = FastOutLinearInEasing))
                state.displayed = target
                state.leaving = false
                state.progress.snapTo(0f)
            } else state.leaving = false
            state.progress.animateTo(1f, tween(200, easing = LinearOutSlowInEasing))
        }
    }
    return state
}

internal fun networkPerspective(progress: Float, leaving: Boolean): Float =
    (1f - progress.coerceIn(0f, 1f)) * if (leaving) -12f else 12f

/** Native anchored gestures retain focus/back/scroll arbitration. No competing touch interceptor. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun rememberInteractiveSheetState(): SheetState {
    val density = LocalDensity.current
    return remember(density.density) {
        SheetState(skipPartiallyExpanded = true,
            positionalThreshold = { with(density) { 120.dp.toPx() } },
            velocityThreshold = { with(density) { 1800.dp.toPx() } })
    }
}
