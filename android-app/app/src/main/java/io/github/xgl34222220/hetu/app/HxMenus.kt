package io.github.xgl34222220.hetu

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import io.github.xgl34222220.hetu.ui.HetuHaptic
import io.github.xgl34222220.hetu.ui.rememberHetuHaptics
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
 * A floating card that grows out of [anchor] (right-aligned, like a native dropdown) over
 * a soft scrim. `close { … }` inside [content] plays the exit animation and then runs.
 */
@Composable
internal fun HxAnchoredMenu(
    anchor: Rect,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.(close: (() -> Unit) -> Unit) -> Unit,
) {
    val c = Hx.colors
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
    val scrim by animateFloatAsState(if (state.targetState) 1f else 0f, tween(HxMotion.Medium), label = "menuScrim")
    var origin by remember { mutableStateOf(TransformOrigin(1f, 0f)) }

    Popup(
        popupPositionProvider = HxFullWindowPosition,
        onDismissRequest = { close(onDismiss) },
        properties = PopupProperties(focusable = true, clippingEnabled = false),
    ) {
        Box(Modifier.fillMaxSize()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = scrim }
                    .background(Color.Black.copy(alpha = if (c.dark) .42f else .22f))
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { close(onDismiss) },
            )
            Box(
                Modifier.layout { measurable, constraints ->
                    val margin = with(density) { 14.dp.toPx() }
                    val placeable = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
                    val maxW = constraints.maxWidth.toFloat()
                    val maxH = constraints.maxHeight.toFloat()
                    val x = (anchor.right - margin - placeable.width).coerceIn(margin, (maxW - margin - placeable.width).coerceAtLeast(margin))
                    val below = anchor.top + with(density) { 6.dp.toPx() }
                    val opensUp = below + placeable.height > maxH - with(density) { 36.dp.toPx() }
                    val y = if (opensUp) (anchor.bottom - placeable.height).coerceAtLeast(with(density) { 40.dp.toPx() }) else below
                    origin = TransformOrigin(
                        ((anchor.right - margin - x) / placeable.width.coerceAtLeast(1)).coerceIn(0f, 1f),
                        if (opensUp) 1f else 0f,
                    )
                    layout(constraints.maxWidth, constraints.maxHeight) { placeable.place(x.roundToInt(), y.roundToInt()) }
                },
            ) {
                AnimatedVisibility(
                    visibleState = state,
                    enter = fadeIn(tween(140)) + scaleIn(HxMotion.pop(), initialScale = .72f, transformOrigin = origin),
                    exit = fadeOut(tween(130)) + scaleOut(tween(150, easing = HxMotion.Exit), targetScale = .85f, transformOrigin = origin),
                ) {
                    Column(
                        Modifier
                            .widthIn(min = 176.dp, max = 290.dp)
                            .width(IntrinsicSize.Max)
                            .shadow(18.dp, RoundedCornerShape(22.dp), clip = false, ambientColor = Color.Black.copy(alpha = .12f), spotColor = Color.Black.copy(alpha = .18f))
                            .clip(RoundedCornerShape(22.dp))
                            .background(c.surface)
                            .then(if (c.dark) Modifier.border(0.5.dp, c.line, RoundedCornerShape(22.dp)) else Modifier)
                            .verticalScroll(rememberScrollState())
                            .padding(vertical = 6.dp),
                    ) {
                        content(close)
                    }
                }
            }
        }
    }
}

/** One menu line: label (+ optional description), check when selected, red when destructive. */
@Composable
internal fun HxMenuItem(
    label: String,
    onClick: () -> Unit,
    description: String? = null,
    selected: Boolean = false,
    enabled: Boolean = true,
    danger: Boolean = false,
    icon: ImageVector? = null,
) {
    val c = Hx.colors
    val haptics = rememberHetuHaptics()
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) {
                haptics.perform(HetuHaptic.Tick)
                onClick()
            }
            .background(if (selected) c.accentSoft.copy(alpha = .55f) else Color.Transparent)
            .padding(horizontal = 18.dp, vertical = if (description.isNullOrBlank()) 13.dp else 10.dp)
            .graphicsLayer { alpha = if (enabled) 1f else .4f },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = if (danger) c.bad else c.textMuted, modifier = Modifier.size(19.dp))
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                label,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                color = when {
                    danger -> c.bad
                    selected -> c.accent
                    else -> c.text
                },
            )
            if (!description.isNullOrBlank()) {
                Text(description, style = MaterialTheme.typography.bodySmall, color = c.textMuted)
            }
        }
        if (selected) {
            Spacer(Modifier.width(12.dp))
            Icon(Icons.Rounded.Check, null, tint = c.accent, modifier = Modifier.size(19.dp))
        }
    }
}

internal data class HxMenuAction(
    val label: String,
    val icon: ImageVector? = null,
    val danger: Boolean = false,
    val onClick: () -> Unit,
)

/**
 * Action list for an item: a dropdown next to the touched element when an anchor is
 * known, otherwise a bottom sheet with the same actions.
 */
@Composable
internal fun HxActionMenu(title: String, actions: List<HxMenuAction>, onDismiss: () -> Unit) {
    val anchor = remember { HxAnchor.take() }
    if (anchor != null) {
        HxAnchoredMenu(anchor, onDismiss) { close ->
            actions.forEach { action ->
                HxMenuItem(action.label, onClick = { close(action.onClick) }, danger = action.danger, icon = action.icon)
            }
        }
    } else {
        HxSheet(onDismiss = onDismiss, title = title) {
            val close = LocalHxSheetClose.current
            Column(Modifier.padding(horizontal = 16.dp)) {
                HxGroup {
                    actions.forEach { action ->
                        HxRow(action.label, icon = action.icon, danger = action.danger, onClick = { close(action.onClick) })
                    }
                }
            }
        }
    }
}
