package io.github.xgl34222220.hetu.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.*

/** A menu is drawn after page content, in the same window and outside its Haze source. */
internal class CrystalPopoverController {
    class Request(val owner: Any, val anchor: Rect, val restoreFocus: () -> Unit,
        val body: @Composable (() -> Unit) -> Unit)
    var request by mutableStateOf<Request?>(null)
        private set

    fun show(owner: Any, anchor: Rect, restoreFocus: () -> Unit, body: @Composable (() -> Unit) -> Unit) {
        request = Request(owner, anchor, restoreFocus, body)
    }
    fun move(owner: Any, anchor: Rect) {
        request?.takeIf { it.owner === owner && it.anchor != anchor }?.let {
            request = Request(owner, anchor, it.restoreFocus, it.body)
        }
    }
    fun dismiss(owner: Any? = null, restoreFocus: Boolean = true) {
        val current = request ?: return
        if (owner != null && current.owner !== owner) return
        request = null
        if (restoreFocus) current.restoreFocus()
    }
}

internal val LocalCrystalPopover = staticCompositionLocalOf<CrystalPopoverController?> { null }

/** Placement is relative to this activity root, not a second Popup window. */
internal fun crystalPopoverPosition(anchor: IntRect, window: IntSize, menu: IntSize,
    direction: LayoutDirection, margin: Int, gap: Int): IntOffset {
    val maxX = (window.width - menu.width - margin).coerceAtLeast(margin)
    val x = (if (direction == LayoutDirection.Ltr) anchor.right - menu.width else anchor.left)
        .coerceIn(margin, maxX)
    val below = anchor.bottom + gap
    val maxY = (window.height - menu.height - margin).coerceAtLeast(margin)
    val y = (if (below <= maxY) below else anchor.top - menu.height - gap).coerceIn(margin, maxY)
    return IntOffset(x, y)
}

@Composable
internal fun CrystalPopoverHost(content: @Composable () -> Unit) {
    val host = remember { CrystalPopoverController() }
    var bounds by remember { mutableStateOf(Rect.Zero) }
    val density = LocalDensity.current
    val focus = remember { FocusRequester() }
    val active = host.request
    CompositionLocalProvider(LocalCrystalPopover provides host) {
        Box(Modifier.fillMaxSize().onGloballyPositioned { bounds = it.boundsInWindow() }
            .testTag("crystal-overlay-host")) {
            Box(Modifier.fillMaxSize().then(if (active != null) Modifier.clearAndSetSemantics {} else Modifier)) { content() }
            if (active != null) key(active.owner) {
                BackHandler { host.dismiss() }
                LaunchedEffect(active.owner) { focus.requestFocus() }
                var menuSize by remember { mutableStateOf(IntSize.Zero) }
                val margin = with(density) { 16.dp.roundToPx() }
                val gap = with(density) { 4.dp.roundToPx() }
                val anchor = IntRect(
                    (active.anchor.left-bounds.left).toInt(), (active.anchor.top-bounds.top).toInt(),
                    (active.anchor.right-bounds.left).toInt(), (active.anchor.bottom-bounds.top).toInt())
                val position = crystalPopoverPosition(anchor, IntSize(bounds.width.toInt(),bounds.height.toInt()),
                    menuSize, androidx.compose.ui.platform.LocalLayoutDirection.current, margin, gap)
                Box(Modifier.fillMaxSize().focusRequester(focus).focusable().onPreviewKeyEvent {
                    if (it.type == KeyEventType.KeyUp && it.key == Key.Escape) { host.dismiss(); true } else false
                }.testTag("crystal-menu-overlay")) {
                    // This sibling receives outside taps only; menu actions never bubble to it.
                    Box(Modifier.fillMaxSize().clickable(interactionSource = remember { MutableInteractionSource() },
                        indication = null, onClick = { host.dismiss() }).semantics { contentDescription = "关闭更多工具菜单" })
                    Box(Modifier.offset { position }.onSizeChanged { menuSize = it }) {
                        active.body { host.dismiss() }
                    }
                }
            }
        }
    }
}
