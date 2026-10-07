package io.github.xgl34222220.hetu

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import io.github.xgl34222220.hetu.home.HomeDims
import io.github.xgl34222220.hetu.home.HomeMenuDivider
import io.github.xgl34222220.hetu.home.HomeMenuItem
import io.github.xgl34222220.hetu.home.HomeMenuTitle
import io.github.xgl34222220.hetu.home.HomeMotion
import io.github.xgl34222220.hetu.home.LocalHomeColors
import io.github.xgl34222220.hetu.home.LocalHomeMotionEnabled
import kotlin.math.roundToInt

/* ------------------------------------------------------------------ */
/*  Anchors: where the last touch landed, so menus open next to it      */
/* ------------------------------------------------------------------ */

internal object HxAnchor {
    private var rect: Rect? = null
    private var at = 0L

    fun set(bounds: Rect) {
        rect = bounds
        at = System.currentTimeMillis()
    }

    /** The anchor of a touch in the last moment, consumed once. */
    fun take(): Rect? {
        val value = rect?.takeIf { System.currentTimeMillis() - at < 1500L }
        rect = null
        return value
    }
}

private class HxBoundsHolder {
    var bounds: Rect? = null
}

/**
 * Remembers this element's on-screen bounds and marks it as the menu anchor whenever a
 * finger touches it. Never consumes the touch, so clicks behave exactly as before.
 */
@Composable
internal fun Modifier.hxAnchorSource(): Modifier {
    val holder = remember { HxBoundsHolder() }
    return this
        .onGloballyPositioned { holder.bounds = it.boundsInWindow() }
        .pointerInput(Unit) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                holder.bounds?.let { HxAnchor.set(it) }
            }
        }
}

/* ------------------------------------------------------------------ */
/*  Anchored popup menu                                                 */
/* ------------------------------------------------------------------ */

private object HxFullWindowPosition : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset =
        IntOffset.Zero
}

/**
 * The menu card of the app (20 dp corners, soft shadow) growing out of [anchor]: its end edge
 * lines up with the anchor's, it opens downwards when there is room and upwards when there is
 * not, and it never leaves the screen. `close { … }` inside [content] plays the exit and then runs.
 *
 * @param dimBehind lay a light veil over the page, so the menu reads as the one thing to answer.
 * @param emphasizeAnchor keep the anchor itself clear of the veil.
 */
@Composable
internal fun HxAnchoredMenu(
    anchor: Rect,
    onDismiss: () -> Unit,
    dimBehind: Boolean = true,
    dimAmount: Float? = null,
    emphasizeAnchor: Boolean = false,
    minWidth: Dp = 176.dp,
    anchorEndInset: Dp = 14.dp,
    @Suppress("UNUSED_PARAMETER") contextEntryOffset: Dp? = null,
    @Suppress("UNUSED_PARAMETER") leftAnchorPointer: Boolean = false,
    @Suppress("UNUSED_PARAMETER") verticalPadding: Dp = 6.dp,
    content: @Composable ColumnScope.(close: (() -> Unit) -> Unit) -> Unit,
) {
    val c = LocalHomeColors.current
    val motion = LocalHomeMotionEnabled.current
    val density = LocalDensity.current
    val state = remember { MutableTransitionState(false).apply { targetState = true } }
    var after by remember { mutableStateOf<(() -> Unit)?>(null) }
    val close: (() -> Unit) -> Unit = { block ->
        if (state.targetState) {
            after = block
            state.targetState = false
        }
    }
    LaunchedEffect(state.isIdle, state.currentState) {
        if (state.isIdle && !state.currentState && !state.targetState) (after ?: onDismiss)()
    }
    val veil by animateFloatAsState(if (state.targetState) 1f else 0f, HomeMotion.fade(motion, 220), label = "hx-menu-veil")
    var origin by remember { mutableStateOf(TransformOrigin(1f, 0f)) }
    // A veil is a hint, not a modal scrim; a caller may ask for more, up to a point.
    val veilAlpha = (dimAmount ?: if (c.dark) .34f else .14f).coerceIn(0f, if (c.dark) .5f else .34f)

    Popup(
        popupPositionProvider = HxFullWindowPosition,
        onDismissRequest = { close(onDismiss) },
        properties = PopupProperties(focusable = true, clippingEnabled = false),
    ) {
        Box(Modifier.fillMaxSize()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = veil }
                    .drawBehind {
                        if (dimBehind) {
                            val mask = Path().apply {
                                fillType = PathFillType.EvenOdd
                                addRect(Rect(0f, 0f, size.width, size.height))
                                // The same inset rounded shape a row is highlighted with.
                                if (emphasizeAnchor) addRoundRect(RoundRect(
                                    Rect(anchor.left + 6.dp.toPx(), anchor.top + 3.dp.toPx(), anchor.right - 6.dp.toPx(), anchor.bottom - 3.dp.toPx()),
                                    CornerRadius(18.dp.toPx()),
                                ))
                            }
                            drawPath(mask, Color.Black.copy(alpha = veilAlpha))
                        }
                    }
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { close(onDismiss) },
            )
            Box(
                Modifier.layout { measurable, constraints ->
                    val margin = with(density) { 14.dp.toPx() }
                    val gap = with(density) { 6.dp.toPx() }
                    val safeTop = with(density) { 32.dp.toPx() }
                    val safeBottom = with(density) { 24.dp.toPx() }
                    val placeable = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0,
                        maxWidth = (constraints.maxWidth - margin * 2).roundToInt().coerceAtLeast(0),
                        maxHeight = (constraints.maxHeight - safeTop - safeBottom).roundToInt().coerceAtLeast(0)))
                    val maxW = constraints.maxWidth.toFloat()
                    val maxH = constraints.maxHeight.toFloat()
                    val x = (anchor.right - with(density) { anchorEndInset.toPx() } - placeable.width).coerceIn(margin, (maxW - margin - placeable.width).coerceAtLeast(margin))
                    val below = anchor.bottom + gap
                    val opensUp = below + placeable.height > maxH - safeBottom && anchor.top - gap - placeable.height >= safeTop
                    val y = (if (opensUp) anchor.top - placeable.height - gap else below)
                        .coerceIn(safeTop, (maxH - safeBottom - placeable.height).coerceAtLeast(safeTop))
                    origin = TransformOrigin(
                        ((anchor.right - margin - x) / placeable.width.coerceAtLeast(1)).coerceIn(0f, 1f),
                        if (opensUp) 1f else 0f,
                    )
                    layout(constraints.maxWidth, constraints.maxHeight) { placeable.place(x.roundToInt(), y.roundToInt()) }
                },
            ) {
                AnimatedVisibility(
                    visibleState = state,
                    enter = if (motion) fadeIn(HomeMotion.fade(true, 140)) + scaleIn(HomeMotion.pop(true), initialScale = .78f, transformOrigin = origin) else EnterTransition.None,
                    exit = if (motion) fadeOut(HomeMotion.fade(true, 120)) + scaleOut(HomeMotion.fade(true, 150), targetScale = .9f, transformOrigin = origin) else ExitTransition.None,
                ) {
                    Column(
                        Modifier
                            .widthIn(min = minWidth, max = 300.dp)
                            .width(IntrinsicSize.Max)
                            .shadow(22.dp, HomeDims.menuShape, ambientColor = Color.Black.copy(alpha = .10f), spotColor = Color.Black.copy(alpha = .20f))
                            .clip(HomeDims.menuShape)
                            .background(c.raised)
                            .then(if (c.dark) Modifier.border(1.dp, c.line, HomeDims.menuShape) else Modifier)
                            .verticalScroll(rememberScrollState())
                            .padding(6.dp),
                    ) {
                        content(close)
                    }
                }
            }
        }
    }
}

/** One menu line: label (+ optional description), ticked when [selected], red when destructive. */
@Composable
internal fun HxMenuItem(
    label: String,
    onClick: () -> Unit,
    description: String? = null,
    selected: Boolean = false,
    enabled: Boolean = true,
    danger: Boolean = false,
    icon: ImageVector? = null,
    @Suppress("UNUSED_PARAMETER") selectedTextColor: Color = LocalHomeColors.current.accent,
    @Suppress("UNUSED_PARAMETER") compact: Boolean = false,
    minimumHeight: Dp = 0.dp,
    @Suppress("UNUSED_PARAMETER") labelFontSizeSp: Float? = null,
    @Suppress("UNUSED_PARAMETER") choicePresentation: HxChoicePresentation = HxChoicePresentation.Standard,
    @Suppress("UNUSED_PARAMETER") compactVerticalPadding: Dp = 6.dp,
) {
    HomeMenuItem(
        label, onClick, if (minimumHeight > HxMenuRowHeight) Modifier.heightIn(min = minimumHeight) else Modifier,
        icon = icon?.let(::hxLineIcon), description = description, danger = danger, enabled = enabled,
        checked = if (selected) true else null,
    )
}

internal enum class HxFileEntryMenu { File, Folder }

internal data class HxMenuAction(
    val label: String,
    val icon: ImageVector? = null,
    val danger: Boolean = false,
    val onClick: () -> Unit,
)

/**
 * Action list for an item: a menu next to the touched element when an anchor is known,
 * otherwise a bottom sheet with the same actions. Menus that belong to one thing (a file, a
 * config) are headed by its name; destructive actions sit below a divider, away from the rest.
 */
@Composable
internal fun HxActionMenu(
    title: String,
    actions: List<HxMenuAction>,
    onDismiss: () -> Unit,
    referenceFileMenu: Boolean = false,
    anchorEndInset: Dp = 14.dp,
    dimBehind: Boolean? = null,
    dimAmount: Float? = null,
    emphasizeAnchor: Boolean = false,
    entryMenu: HxFileEntryMenu? = null,
    headerIcon: ImageVector? = null,
) {
    val anchor = remember { HxAnchor.take() }
    val headed = entryMenu != null || referenceFileMenu
    if (anchor != null) {
        HxAnchoredMenu(
            anchor, onDismiss,
            dimBehind = dimBehind ?: (entryMenu != null), dimAmount = dimAmount,
            emphasizeAnchor = emphasizeAnchor || entryMenu != null,
            minWidth = if (headed) 200.dp else 176.dp,
            // A row's menu hangs from the row's end; a bar button's menu from the button.
            anchorEndInset = if (entryMenu != null) 18.dp else anchorEndInset.coerceIn(0.dp, 14.dp),
        ) { close ->
            if (headed && title.isNotBlank()) {
                HomeMenuTitle(title, icon = headerIcon?.let(::hxLineIcon))
                HomeMenuDivider()
            }
            HxMenuActions(actions) { action -> close(action.onClick) }
        }
    } else {
        HxSheet(onDismiss = onDismiss, title = title) {
            val close = LocalHxSheetClose.current
            Column(Modifier.padding(horizontal = 8.dp)) {
                HxMenuActions(actions, rowHeight = 54.dp) { action -> close(action.onClick) }
            }
        }
    }
}

@Composable
private fun HxMenuActions(actions: List<HxMenuAction>, rowHeight: Dp = HxMenuRowHeight, onPick: (HxMenuAction) -> Unit) {
    actions.forEachIndexed { index, action ->
        if (action.danger && index > 0 && !actions[index - 1].danger) HomeMenuDivider()
        HomeMenuItem(
            action.label, { onPick(action) }, Modifier.heightIn(min = rowHeight),
            icon = action.icon?.let(::hxLineIcon), danger = action.danger,
        )
    }
}
