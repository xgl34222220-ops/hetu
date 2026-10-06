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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
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
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.CornerRadius
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
 * A floating card that grows out of [anchor] (right-aligned, like a native dropdown) without dimming the underlying page. `close { … }` inside [content] plays the exit animation and then runs.
 */
@Composable
internal fun HxAnchoredMenu(
    anchor: Rect,
    onDismiss: () -> Unit,
    dimBehind: Boolean = true,
    dimAmount: Float? = null,
    emphasizeAnchor: Boolean = false,
    minWidth: Dp = 152.dp,
    anchorEndInset: Dp = 14.dp,
    contextEntryOffset: Dp? = null,
    leftAnchorPointer: Boolean = false,
    verticalPadding: Dp = 6.dp,
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
    var pointerY by remember { mutableStateOf(0f) }

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
                    .drawBehind {
                        if (dimBehind) {
                            val mask = Path().apply {
                                fillType = PathFillType.EvenOdd
                                addRect(Rect(0f, 0f, size.width, size.height))
                                if (emphasizeAnchor) {
                                    val focusAnchor = if (leftAnchorPointer && contextEntryOffset != null)
                                        Rect(anchor.left + 4.dp.toPx(), anchor.top + 6.dp.toPx(), anchor.right - 4.dp.toPx(), anchor.bottom - 6.dp.toPx()) else anchor
                                    addRoundRect(RoundRect(focusAnchor, CornerRadius(12.dp.toPx())))
                                }
                            }
                            drawPath(mask, Color.Black.copy(alpha = (dimAmount ?: if (c.dark) .42f else .26f).coerceIn(0f, 1f)))
                        }
                    }
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { close(onDismiss) },
            )
            Box(
                Modifier.layout { measurable, constraints ->
                    val margin = with(density) { 14.dp.toPx() }
                    val safeTop = with(density) { 32.dp.toPx() }
                    val safeBottom = with(density) { 24.dp.toPx() }
                    val placeable = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0,
                        maxWidth = (constraints.maxWidth - margin * 2).roundToInt().coerceAtLeast(0),
                        maxHeight = (constraints.maxHeight - safeTop - safeBottom).roundToInt().coerceAtLeast(0)))
                    val maxW = constraints.maxWidth.toFloat()
                    val maxH = constraints.maxHeight.toFloat()
                    val x = (anchor.right - with(density) { anchorEndInset.toPx() } - placeable.width).coerceIn(margin, (maxW - margin - placeable.width).coerceAtLeast(margin))
                    val below = anchor.bottom + with(density) { 4.dp.toPx() }
                    val opensUp = below + placeable.height > maxH - with(density) { 36.dp.toPx() }
                    val y = (if (contextEntryOffset != null) anchor.bottom + with(density) { contextEntryOffset.toPx() } - placeable.height
                        else if (opensUp) anchor.top - placeable.height - with(density) { 4.dp.toPx() } else below)
                        .coerceIn(safeTop, (maxH - safeBottom - placeable.height).coerceAtLeast(safeTop))
                    pointerY = anchor.center.y - y
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
                    Box(Modifier.drawBehind {
                        if (leftAnchorPointer) {
                            val cy = pointerY.coerceIn(18.dp.toPx(), (size.height - 18.dp.toPx()).coerceAtLeast(18.dp.toPx()))
                            val pointer = Path().apply {
                                moveTo(1f, cy - 6.dp.toPx()); lineTo(-6.dp.toPx(), cy)
                                lineTo(1f, cy + 6.dp.toPx()); close()
                            }
                            drawPath(pointer, c.surface)
                        }
                    }) {
                    Column(
                        Modifier
                            .widthIn(min = minWidth, max = 290.dp)
                            .width(IntrinsicSize.Max)
                            .shadow(18.dp, RoundedCornerShape(12.dp), clip = false, ambientColor = Color.Black.copy(alpha = .12f), spotColor = Color.Black.copy(alpha = .18f))
                            .clip(RoundedCornerShape(12.dp))
                            .background(c.surface)
                            .then(if (c.dark) Modifier.border(0.5.dp, c.line, RoundedCornerShape(12.dp)) else Modifier)
                            .verticalScroll(rememberScrollState())
                            .padding(vertical = verticalPadding),
                    ) {
                        content(close)
                    }
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
    selectedTextColor: Color = Hx.colors.accent,
    compact: Boolean = false,
    minimumHeight: Dp = 0.dp,
    labelFontSizeSp: Float? = null,
    choicePresentation: HxChoicePresentation = HxChoicePresentation.Standard,
    compactVerticalPadding: Dp = 6.dp,
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
            .background(if (choicePresentation == HxChoicePresentation.Notification && selected) c.surfaceMuted else Color.Transparent)
            .heightIn(min = minimumHeight)
            .padding(horizontal = 14.dp, vertical = when {
                compact -> compactVerticalPadding
                choicePresentation == HxChoicePresentation.Scale -> 8.dp
                choicePresentation == HxChoicePresentation.Language -> 9.dp
                choicePresentation != HxChoicePresentation.Standard -> 11.dp
                description.isNullOrBlank() -> 13.dp
                else -> 10.dp
            })
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
                style = when (choicePresentation) {
                    HxChoicePresentation.Standard -> MaterialTheme.typography.bodyLarge
                    HxChoicePresentation.Scale -> MaterialTheme.typography.bodyLarge.copy(fontSize = 14.sp, lineHeight = 19.sp)
                    else -> MaterialTheme.typography.bodyLarge.copy(fontSize = 16.sp, lineHeight = 20.sp)
                }.let { if (labelFontSizeSp == null) it else it.copy(fontSize = labelFontSizeSp.sp, lineHeight = 20.sp) },
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                color = when {
                    danger -> c.bad
                    selected -> selectedTextColor
                    else -> c.text
                },
            )
            if (!description.isNullOrBlank()) {
                Text(description, style = MaterialTheme.typography.bodySmall, color = c.textMuted)
            }
        }
        if (selected) {
            Spacer(Modifier.width(12.dp))
            Icon(if (choicePresentation == HxChoicePresentation.Language) Icons.Rounded.CheckCircle else Icons.Rounded.Check, null, tint = c.accent, modifier = Modifier.size(19.dp))
        }
    }
}

internal enum class HxFileEntryMenu { File, Folder }

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
internal fun HxActionMenu(title: String, actions: List<HxMenuAction>, onDismiss: () -> Unit, referenceFileMenu: Boolean = false, anchorEndInset: Dp = 14.dp, dimBehind: Boolean? = null, dimAmount: Float? = null, emphasizeAnchor: Boolean = false, entryMenu: HxFileEntryMenu? = null, headerIcon: ImageVector? = null) {
    val anchor = remember { HxAnchor.take() }
    if (anchor != null) {
        HxAnchoredMenu(anchor, onDismiss, dimBehind = dimBehind ?: !referenceFileMenu, dimAmount = dimAmount, emphasizeAnchor = emphasizeAnchor,
            minWidth = if (entryMenu == HxFileEntryMenu.Folder) 148.dp else 152.dp,
            anchorEndInset = when (entryMenu) { HxFileEntryMenu.File -> 34.dp; HxFileEntryMenu.Folder -> 54.dp; else -> anchorEndInset },
            contextEntryOffset = when (entryMenu) { HxFileEntryMenu.File -> 14.dp; HxFileEntryMenu.Folder -> 8.dp; else -> null },
            leftAnchorPointer = entryMenu == HxFileEntryMenu.Folder,
            verticalPadding = if (referenceFileMenu && entryMenu == null) 4.dp else 6.dp) { close ->
            if (entryMenu != null) {
                Row(Modifier.fillMaxWidth().heightIn(min = 34.dp).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (headerIcon != null) { Icon(headerIcon, null, tint = Hx.colors.text, modifier = Modifier.size(22.dp)); Spacer(Modifier.width(12.dp)) }
                    Text(title, color = Hx.colors.text, fontSize = if (entryMenu == HxFileEntryMenu.Folder) 16.sp else 15.sp,
                        lineHeight = 20.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                }
                HorizontalDivider(Modifier.padding(horizontal = 12.dp), thickness = .5.dp, color = Hx.colors.line)
            } else if (referenceFileMenu) {
                Text(title, color = Hx.colors.textMuted, fontSize = 13.sp, lineHeight = 18.sp,
                    maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp))
                HorizontalDivider(Modifier.padding(horizontal = 12.dp), color = Hx.colors.line)
            }
            actions.forEachIndexed { index, action ->
                HxMenuItem(action.label, onClick = { close(action.onClick) }, danger = action.danger, icon = action.icon, compact = referenceFileMenu,
                    minimumHeight = when (entryMenu) { HxFileEntryMenu.File -> 43.dp; HxFileEntryMenu.Folder -> 36.dp; else -> if (referenceFileMenu) 32.dp else 0.dp },
                    labelFontSizeSp = if (entryMenu != null || referenceFileMenu) 15f else null,
                    compactVerticalPadding = if (referenceFileMenu && entryMenu == null) 5.dp else 6.dp)
                if (entryMenu != null && index < actions.lastIndex) HorizontalDivider(Modifier.padding(horizontal = 12.dp), thickness = .5.dp, color = Hx.colors.line)
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
