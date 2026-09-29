@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package io.github.xgl34222220.hetu

import android.os.Build
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.input.nestedscroll.*
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import io.github.xgl34222220.hetu.ui.LocalHetuMotionEnabled
import io.github.xgl34222220.hetu.ui.LocalHetuTokens
import io.github.xgl34222220.hetu.ui.crystalPopoverPosition
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/** Spatial motion may settle elastically; opacity and colors must never overshoot. */
internal object HetuMotion12 {
    const val Damping = .84f
    const val Stiffness = 280f
    fun <T> spatial(enabled: Boolean = true): FiniteAnimationSpec<T> =
        if (enabled) spring(dampingRatio = Damping, stiffness = Stiffness) else snap()
}

private class SheetMotionScheme12(private val enabled: Boolean) : MotionScheme {
    override fun <T> defaultSpatialSpec(): FiniteAnimationSpec<T> = HetuMotion12.spatial(enabled)
    override fun <T> slowSpatialSpec(): FiniteAnimationSpec<T> = HetuMotion12.spatial(enabled)
    override fun <T> fastSpatialSpec(): FiniteAnimationSpec<T> =
        if (enabled) spring(dampingRatio = .85f, stiffness = 340f) else snap()
    override fun <T> defaultEffectsSpec(): FiniteAnimationSpec<T> =
        if (enabled) spring(dampingRatio = 1f, stiffness = 600f) else snap()
    // Material3's native hide path uses FastEffects. A critically damped spring
    // closes decisively without bouncing the panel back onto the screen.
    override fun <T> fastEffectsSpec(): FiniteAnimationSpec<T> =
        if (enabled) spring(dampingRatio = 1f, stiffness = 500f) else snap()
    override fun <T> slowEffectsSpec(): FiniteAnimationSpec<T> =
        if (enabled) spring(dampingRatio = 1f, stiffness = 450f) else snap()
}

@Composable
private fun SheetMotionTheme12(enabled: Boolean, content: @Composable () -> Unit) {
    val scheme = remember(enabled) { SheetMotionScheme12(enabled) }
    // Explicitly inherit every visual subsystem: changing motion cannot replace
    // the user's font, color scheme or density with Expressive defaults.
    MaterialExpressiveTheme(colorScheme = MaterialTheme.colorScheme,
        typography = MaterialTheme.typography, shapes = MaterialTheme.shapes,
        motionScheme = scheme, content = content)
}

internal val ModalProgress12 = SemanticsPropertyKey<Float>("ModalProgress12")
internal val ModalScale12 = SemanticsPropertyKey<Float>("ModalScale12")
internal val ModalBlur12 = SemanticsPropertyKey<Float>("ModalBlurDp12")
internal val MenuProgress12 = SemanticsPropertyKey<Float>("MenuProgress12")
internal val EdgeOffset12 = SemanticsPropertyKey<Float>("EdgeOffsetDp12")

internal fun sheetCoverage12(viewportHeight: Float, sheetHeight: Float, top: Float): Float {
    if (!viewportHeight.isFinite() || !sheetHeight.isFinite() || !top.isFinite() ||
        viewportHeight <= 0f || sheetHeight <= 0f) return 0f
    return ((viewportHeight - top) / sheetHeight).coerceIn(0f, 1f)
}

internal fun backdropScale12(progress: Float, motion: Boolean): Float =
    if (motion) 1f - .04f * progress.coerceIn(0f, 1f) else 1f

internal class ModalLayer12(val state: SheetState) {
    var height by mutableFloatStateOf(0f)
    var anchorViewport by mutableFloatStateOf(0f)
    fun coverage(viewport: Float): Float = sheetCoverage12(anchorViewport.takeIf { it > 0f } ?: viewport, height,
        runCatching { state.requireOffset() }.getOrDefault(Float.NaN))
}
internal class ModalBackdrop12 {
    var viewport by mutableFloatStateOf(0f)
    val layers = mutableStateListOf<ModalLayer12>()
    val progress: Float get() = layers.maxOfOrNull { it.coverage(viewport) } ?: 0f
}
internal val LocalModalBackdrop12 = staticCompositionLocalOf<ModalBackdrop12?> { null }
private val LocalSheetMotion12 = staticCompositionLocalOf { true }

@Composable
internal fun rememberBlurEnabled12(): Boolean {
    val prefs = LocalContext.current.getSharedPreferences("hetu", 0)
    var enabled by remember(prefs) { mutableStateOf(prefs.getBoolean("enableBlur", true)) }
    DisposableEffect(prefs) {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { p, key ->
            if (key == "enableBlur") enabled = p.getBoolean("enableBlur", true)
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return enabled
}

/** The source stays painted edge-to-edge. Only its drawing layer recedes behind the native modal window. */
@Composable
internal fun ModalBackdropHost12(content: @Composable () -> Unit) {
    if (LocalModalBackdrop12.current != null) { content(); return }
    val backdrop = remember { ModalBackdrop12() }
    val background = LocalHetuTokens.current.pageBackground
    val motion = homeMotionAvailable(LocalHetuMotionEnabled.current)
    val blurEnabled = rememberBlurEnabled12()
    val supported = Build.VERSION.SDK_INT >= 31 && LocalView.current.isHardwareAccelerated
    val density = LocalDensity.current.density
    // Reuse a small bank of GPU effects rather than allocate a render effect for
    // every pointer event. CLAMP keeps the full-screen edges opaque during blur.
    val effects = remember(density, supported) {
        if (supported) List<RenderEffect?>(37) { index ->
            if (index == 0) null else BlurEffect(index * .5f * density, index * .5f * density, TileMode.Clamp)
        } else emptyList()
    }
    CompositionLocalProvider(LocalModalBackdrop12 provides backdrop) {
        Box(Modifier.fillMaxSize().background(background).onSizeChanged { backdrop.viewport = it.height.toFloat() }) {
            Box(Modifier.fillMaxSize().graphicsLayer {
                val progress = backdrop.progress
                scaleX = backdropScale12(progress, motion); scaleY = scaleX
                shape = if (progress > 0f && motion) HomeContinuousShape(24.dp) else RectangleShape
                clip = progress > 0f && motion
                val index = if (supported && blurEnabled) (progress * 36).roundToInt().coerceIn(0, 36) else 0
                renderEffect = if (supported) effects[index] else null
            }.testTag("motion12-background").semantics {
                this[ModalProgress12] = backdrop.progress
                this[ModalScale12] = backdropScale12(backdrop.progress, motion)
                this[ModalBlur12] = if (supported && blurEnabled) backdrop.progress * 18f else 0f
            }) { content() }
        }
    }
}

/** Same continuous curvature as home cards; square bottom corners meet the gesture bar cleanly. */
internal data class SheetShape12(val radius: Dp = 30.dp) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val outline = HomeContinuousShape(radius).createOutline(size, layoutDirection, density)
        if (outline !is Outline.Generic || size.height <= 0f) return outline
        val bottom = Path().apply { addRect(Rect(0f, size.height / 2, size.width, size.height)) }
        return Outline.Generic(Path.combine(PathOperation.Union, outline.path, bottom))
    }
}

/**
 * Retains Material3's real anchored drag, velocity tracking, nested-scroll,
 * predictive back, accessibility, keyboard and native modal focus handling.
 * There is deliberately no second pointerInput/detectDragGestures interceptor.
 */
@Composable
internal fun MotionModalSheet12(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    sheetState: SheetState = rememberInteractiveSheetState(),
    sheetGesturesEnabled: Boolean = true,
    shape: Shape = SheetShape12(),
    containerColor: Color = LocalHetuTokens.current.cardBackground,
    contentColor: Color = LocalHetuTokens.current.textPrimary,
    tonalElevation: Dp = 0.dp,
    scrimColor: Color = Color(0xFF12161A).copy(alpha = .26f),
    dragHandle: @Composable (() -> Unit)? = { BottomSheetDefaults.DragHandle() },
    content: @Composable ColumnScope.() -> Unit,
) {
    val backdrop = LocalModalBackdrop12.current
    val layer = remember(sheetState) { ModalLayer12(sheetState) }
    val motion = homeMotionAvailable(LocalHetuMotionEnabled.current)
    val dark = containerColor.luminance() < .5f
    DisposableEffect(backdrop, layer) {
        backdrop?.layers?.add(layer)
        onDispose { backdrop?.layers?.remove(layer) }
    }
    SheetMotionTheme12(motion) {
        CompositionLocalProvider(LocalSheetMotion12 provides motion) {
            ModalBottomSheet(onDismissRequest = onDismissRequest, sheetState = sheetState,
                sheetGesturesEnabled = sheetGesturesEnabled, shape = shape,
                containerColor = containerColor, contentColor = contentColor, tonalElevation = tonalElevation,
                // The scrim remains independently animated by native Material3. Its
                // maximum is deliberately lighter; real background blur is above.
                scrimColor = scrimColor.copy(alpha = if (dark) .32f else .22f), dragHandle = dragHandle,
                modifier = modifier.layout { measurable, constraints ->
                    // The native anchors use these same constraints, including IME resizing.
                    val placeable = measurable.measure(constraints)
                    layer.anchorViewport = constraints.maxHeight.toFloat()
                    layer.height = placeable.height.toFloat()
                    layout(placeable.width, placeable.height) { placeable.placeRelative(0, 0) }
                }.drawWithContent {
                        drawContent()
                        val start = 30.dp.toPx().coerceAtMost(size.width / 2)
                        drawLine(Color.White.copy(alpha = if (dark) .08f else .35f),
                            Offset(start, .5f), Offset(size.width - start, .5f), strokeWidth = 1f)
                    }, content = content)
        }
    }
}

/** Delayed drawing only. No data/network delay and no layout remeasurement per animation frame. */
@Composable
internal fun Modifier.sheetReveal12(order: Int = 0): Modifier {
    val motion = LocalSheetMotion12.current
    val progress = remember { Animatable(if (motion) 0f else 1f) }
    LaunchedEffect(motion) {
        if (!motion) progress.snapTo(1f)
        else progress.animateTo(1f, tween(180, delayMillis = 35 + order.coerceIn(0, 2) * 35, easing = LinearOutSlowInEasing))
    }
    return graphicsLayer { alpha = progress.value.coerceIn(0f, 1f); translationY = (1f - progress.value) * 6.dp.toPx() }
}

/** Native Popup keeps back/outside dismissal and focus; its visual lifecycle is spring-driven. */
@Composable
internal fun MotionPopover12(expanded: Boolean, onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier, shape: Shape = HomeContinuousShape(20.dp),
    containerColor: Color = LocalHetuTokens.current.cardBackground,
    content: @Composable ColumnScope.() -> Unit) {
    val motion = homeMotionAvailable(LocalHetuMotionEnabled.current)
    val visible = remember { MutableTransitionState(false) }
    visible.targetState = expanded
    val transition = updateTransition(visible, label = "popover12")
    val scale by transition.animateFloat(transitionSpec = { HetuMotion12.spatial(motion) }, label = "popover12-scale") { if (it) 1f else .94f }
    val alpha by transition.animateFloat(transitionSpec = { if (motion) tween(if (targetState) 160 else 120) else snap() }, label = "popover12-alpha") { if (it) 1f else 0f }
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    var origin by remember { mutableStateOf(TransformOrigin(1f, 0f)) }
    val position = remember(density.density) {
        object : PopupPositionProvider {
            override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize,
                layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
                val at = crystalPopoverPosition(anchorBounds, windowSize, popupContentSize, layoutDirection,
                    with(density) { 16.dp.roundToPx() }, with(density) { 6.dp.roundToPx() })
                val x = ((anchorBounds.center.x - at.x).toFloat() / popupContentSize.width.coerceAtLeast(1)).coerceIn(0f, 1f)
                val y = if (at.y >= anchorBounds.bottom) 0f else 1f
                origin = TransformOrigin(x, y)
                return at
            }
        }
    }
    if (visible.currentState || visible.targetState || !visible.isIdle) {
        Popup(popupPositionProvider = position, onDismissRequest = onDismissRequest,
            properties = PopupProperties(focusable = true, dismissOnBackPress = true, dismissOnClickOutside = true)) {
            Surface(modifier = Modifier.widthIn(max = (configuration.screenWidthDp - 32).coerceAtLeast(1).dp)
                .heightIn(max = (configuration.screenHeightDp - 80).coerceAtLeast(48).dp)
                .then(modifier).graphicsLayer {
                    scaleX = scale; scaleY = scale; this.alpha = alpha.coerceIn(0f, 1f); transformOrigin = origin
                }.semantics { this[MenuProgress12] = alpha.coerceIn(0f, 1f) },
                shape = shape, color = containerColor.copy(alpha = .98f), tonalElevation = 0.dp, shadowElevation = 10.dp) {
                Column(Modifier.width(IntrinsicSize.Max).verticalScroll(rememberScrollState()).padding(vertical = 8.dp), content = content)
            }
        }
    }
}

/** Monotonic bounded displacement; unlike x*(1-x/max), it never reverses during a longer pull. */
internal fun bottomRubberBand12(rawDp: Float): Float {
    if (!rawDp.isFinite()) return 0f
    val raw = rawDp.coerceIn(0f, 2000f)
    return 64f * (.55f * raw) / (64f + .55f * raw)
}

@Stable
internal class BottomRebound12(private val scope: CoroutineScope, private val density: Float) {
    var offsetPx by mutableFloatStateOf(0f)
        private set
    private var raw = 0f
    private var job: Job? = null
    private var animating = false
    fun drag(fingerDelta: Float): Float {
        if (!fingerDelta.isFinite()) return 0f
        job?.cancel()
        if (animating) {
            // Preserve the current visible displacement when a new drag interrupts a spring.
            val shown = (-offsetPx / density).coerceIn(0f, 63.9f)
            raw = (64f * shown / (.55f * (64f - shown))) * density
            animating = false
        }
        val before = raw
        raw = (raw - fingerDelta).coerceIn(0f, 2000f * density)
        offsetPx = -bottomRubberBand12(raw / density) * density
        return -(raw - before)
    }
    fun release(velocity: Float, motion: Boolean): Boolean {
        if (raw <= 0f && abs(offsetPx) < .01f) return false
        val shown = -offsetPx / density
        val derivative = .55f * (1f - (shown / 64f).coerceIn(0f, 1f)).let { it * it }
        raw = 0f
        job?.cancel()
        if (!motion) { offsetPx = 0f; animating = false; return true }
        animating = true
        job = scope.launch {
            try {
                Animatable(offsetPx).animateTo(0f, spring(dampingRatio = .84f, stiffness = 280f),
                    initialVelocity = if (velocity.isFinite()) (velocity * derivative).coerceIn(-600f * density, 600f * density) else 0f) {
                    offsetPx = value.coerceIn(-64f * density, 0f)
                }
                offsetPx = 0f
            } finally { animating = false }
        }
        return true
    }
    fun reset() { job?.cancel(); raw = 0f; offsetPx = 0f; animating = false }
}

/** Only unused BOTTOM-edge vertical drag is consumed. Top-edge refresh and normal fling stay native. */
@Composable
internal fun rememberBottomRebound12(list: LazyListState, motion: Boolean): Pair<BottomRebound12, NestedScrollConnection> {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current.density
    val state = remember(scope, density) { BottomRebound12(scope, density) }
    val currentMotion by rememberUpdatedState(motion)
    LaunchedEffect(motion) { if (!motion) state.reset() }
    DisposableEffect(state) { onDispose { state.reset() } }
    val connection = remember(list, state) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (!currentMotion || source != NestedScrollSource.UserInput || available.y <= 0f || state.offsetPx >= 0f) return Offset.Zero
                return Offset(0f, state.drag(available.y))
            }
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (!currentMotion || source != NestedScrollSource.UserInput || list.canScrollForward || available.y >= 0f) return Offset.Zero
                return Offset(0f, state.drag(available.y))
            }
            override suspend fun onPreFling(available: Velocity): Velocity =
                if (state.release(available.y, currentMotion)) Velocity(0f, available.y) else Velocity.Zero
        }
    }
    return state to connection
}
