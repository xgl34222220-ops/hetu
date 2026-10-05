package io.github.xgl34222220.hetu

import io.github.xgl34222220.hetu.ui.HetuHaptic
import io.github.xgl34222220.hetu.ui.ht
import io.github.xgl34222220.hetu.ui.rememberHetuHaptics
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.UnfoldMore
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import kotlinx.coroutines.launch
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import dev.chrisbanes.haze.materials.HazeMaterials
import dev.chrisbanes.haze.rememberHazeState

/* ------------------------------------------------------------------ */
/*  Page scaffold                                                      */
/* ------------------------------------------------------------------ */

internal val HxTopBarHeight = 52.dp

/**
 * Every screen uses this. A large left-aligned title scrolls with the content; once it
 * leaves the viewport the same title appears centred in the pinned bar. Title position
 * never animates sideways, so there is no half-way "slightly left" state.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalHazeMaterialsApi::class)
@Composable
internal fun HxPage(
    title: String,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    bottomPadding: Dp = 0.dp,
    listState: LazyListState = rememberLazyListState(),
    refreshing: Boolean = false,
    onRefresh: (() -> Unit)? = null,
    leadingActions: @Composable RowScope.() -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
    overlay: @Composable BoxScope.() -> Unit = {},
    scrollToTopSignal: Int = 0,
    showScrollTop: Boolean = true,
    largeTitle: Boolean = true,
    largeTitleStartPadding: Dp = Hx.gutter,
    largeTitleTopPadding: Dp = 0.dp,
    largeTitleFontSizeSp: Float? = null,
    compactTitleFontSizeSp: Float = 18f,
    largeTitleBottomPadding: Dp = 10.dp,
    canvasColor: Color? = null,
    flatCanvas: Boolean = false,
    referenceTopBar: Boolean = false,
    referenceLabel: String? = null,
    content: LazyListScope.() -> Unit,
) {
    val embed = LocalHxEmbed.current
    if (embed != null) {
        HxEmbeddedPage(embed, subtitle, refreshing, onRefresh, leadingActions, actions, overlay, content)
        return
    }
    // Re-tapping the current dock item scrolls the page back to the top.
    val initialSignal = remember { scrollToTopSignal }
    LaunchedEffect(scrollToTopSignal) {
        if (scrollToTopSignal != initialSignal) listState.animateScrollToItem(0)
    }
    val c = Hx.colors
    val pageCanvas = canvasColor ?: c.canvas
    val density = LocalDensity.current
    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val navInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val thresholdPx = with(density) { 38.dp.toPx() }
    val collapsed by remember(listState) {
        derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > thresholdPx }
    }
    // 0 → large title fully visible, 1 → scrolled away. Read only inside graphicsLayer so
    // scrolling never recomposes the page.
    val headerProgress = remember(listState) {
        derivedStateOf {
            if (listState.firstVisibleItemIndex > 0) 1f
            else (listState.firstVisibleItemScrollOffset / (thresholdPx * 1.25f)).coerceIn(0f, 1f)
        }
    }
    val scrollTopWanted by remember(listState) { derivedStateOf { listState.firstVisibleItemIndex > 7 } }
    val barAlpha by animateFloatAsState(if (!largeTitle || collapsed) 1f else 0f, tween(HxMotion.Short), label = "barAlpha")
    val listBottom = (if (bottomPadding > 0.dp) bottomPadding else navInset) + 24.dp

    val pageHaze = rememberHazeState()
    val pageBrush = if (c.dark || flatCanvas) {
        Brush.verticalGradient(listOf(pageCanvas, pageCanvas))
    } else {
        Brush.verticalGradient(
            listOf(
                Color(0xFFF0F2FD),
                Color(0xFFECEFFB),
                Color(0xFFE9ECF9),
            ),
        )
    }
    Box(Modifier.fillMaxSize().background(pageBrush)) {
        val list: @Composable () -> Unit = {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().hazeSource(pageHaze),
                contentPadding = PaddingValues(
                    top = statusTop + HxTopBarHeight,
                    bottom = listBottom,
                ),
            ) {
                if (largeTitle) item(key = "hx-page-header") {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(start = largeTitleStartPadding, end = Hx.gutter + 2.dp, top = largeTitleTopPadding, bottom = largeTitleBottomPadding)
                            .graphicsLayer {
                                val p = headerProgress.value
                                alpha = 1f - p * .92f
                                val scale = 1f - .05f * p
                                scaleX = scale
                                scaleY = scale
                                transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 1f)
                                translationY = p * 10.dp.toPx()
                            },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                title,
                                style = MaterialTheme.typography.headlineMedium.copy(
                                    fontSize = largeTitleFontSizeSp?.sp ?: MaterialTheme.typography.headlineMedium.fontSize,
                                    lineHeight = largeTitleFontSizeSp?.let { (it + 8f).sp } ?: MaterialTheme.typography.headlineMedium.lineHeight,
                                    letterSpacing = (-0.35).sp,
                                ),
                                color = c.text,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (!subtitle.isNullOrBlank()) {
                                Spacer(Modifier.height(2.dp))
                                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = c.textMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        if (!referenceLabel.isNullOrBlank()) {
                            Text(
                                referenceLabel,
                                style = MaterialTheme.typography.bodySmall,
                                color = c.textFaint,
                                maxLines = 1,
                            )
                        }
                        if (!collapsed && onBack == null) {
                            Row(verticalAlignment = Alignment.CenterVertically, content = actions)
                        }
                    }
                } else if (!subtitle.isNullOrBlank()) item(key = "hx-page-compact-subtitle") {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = c.textMuted,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = Hx.gutter).padding(top = 8.dp, bottom = 10.dp),
                    )
                }
                content()
            }
        }
        if (onRefresh != null) {
            val pullState = rememberPullToRefreshState()
            PullToRefreshBox(
                isRefreshing = refreshing,
                onRefresh = { if (!refreshing) onRefresh() },
                modifier = Modifier.fillMaxSize(),
                state = pullState,
                indicator = {
                    PullToRefreshDefaults.Indicator(
                        state = pullState,
                        isRefreshing = refreshing,
                        modifier = Modifier.align(Alignment.TopCenter).padding(top = statusTop + HxTopBarHeight - 8.dp),
                        containerColor = c.surface,
                        color = c.accent,
                    )
                },
            ) { list() }
        } else {
            list()
        }

        // Pinned bar: frosted glass over the scrolling content once the large title is gone.
        val blur = LocalHxBlur.current
        val topBarStyle = LocalContext.current.getSharedPreferences("hetu", 0).getString("topBarBlurStyle", "progressive").orEmpty()
        val progressiveBar = topBarStyle != "gaussian"
        Box(Modifier.fillMaxWidth().align(Alignment.TopCenter).height(statusTop + HxTopBarHeight)) {
            // Progressive blur stays denser near the status bar and dissolves into content.
            Box(
                Modifier
                    .matchParentSize()
                    .graphicsLayer { alpha = barAlpha }
                    .then(
                        if (blur) Modifier.hazeEffect(state = pageHaze, style = HazeMaterials.ultraThin()) {
                            blurRadius = if (progressiveBar) 28.dp else 22.dp
                            noiseFactor = .008f
                        } else Modifier,
                    )
                    .then(
                        if (blur && progressiveBar) Modifier.background(
                            Brush.verticalGradient(listOf(pageCanvas.copy(alpha = .38f), pageCanvas.copy(alpha = .20f), pageCanvas.copy(alpha = .035f)))
                        ) else Modifier.background(pageCanvas.copy(alpha = if (blur) .24f else 1f))
                    ),
            )
            // Collapsed title sits dead centre in the bar; back and actions float on either side.
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(top = statusTop, start = 4.dp, end = 4.dp)
                    .height(HxTopBarHeight),
            ) {
                Text(
                    title,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(horizontal = 96.dp)
                        .graphicsLayer {
                            alpha = barAlpha
                            translationY = (1f - barAlpha) * 8.dp.toPx()
                            val s = .94f + .06f * barAlpha
                            scaleX = s
                            scaleY = s
                        },
                    style = MaterialTheme.typography.titleMedium.copy(fontSize = compactTitleFontSizeSp.sp, fontWeight = if (referenceTopBar) FontWeight.Bold else FontWeight.SemiBold),
                    color = c.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
                if (onBack != null) {
                    IconButton(onClick = onBack, modifier = Modifier.align(Alignment.CenterStart)) {
                        Icon(if (referenceTopBar) io.github.xgl34222220.hetu.home.HomeIcons.ChevronLeft else Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = ht("返回"), tint = c.text)
                    }
                }
                if (!largeTitle || collapsed || onBack != null) {
                    Row(
                        modifier = Modifier.align(Alignment.CenterEnd),
                        verticalAlignment = Alignment.CenterVertically,
                        content = actions,
                    )
                }
            }
            if (c.dark) HorizontalDivider(Modifier.align(Alignment.BottomCenter), color = c.line.copy(alpha = barAlpha * .28f), thickness = 0.5.dp)
        }

        // Long lists get a small glass "back to top" button once the user is deep in them.
        val scrollScope = rememberCoroutineScope()
        AnimatedVisibility(
            visible = showScrollTop && scrollTopWanted,
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 18.dp, bottom = listBottom - 8.dp),
            enter = fadeIn(tween(HxMotion.Short)) + scaleIn(HxMotion.pop(), initialScale = .6f),
            exit = fadeOut(tween(HxMotion.Short)) + scaleOut(tween(HxMotion.Short), targetScale = .6f),
        ) {
            HxScrollTopButton { scrollScope.launch { listState.animateScrollToItem(0) } }
        }
        overlay()
    }
}

@Composable
private fun HxScrollTopButton(onClick: () -> Unit) {
    val c = Hx.colors
    val haptics = rememberHetuHaptics()
    val source = remember { MutableInteractionSource() }
    Box(
        Modifier
            .hxPressScale(source, .9f)
            .size(42.dp)
            .hxSoftShadow(CircleShape, 10.dp)
            .clip(CircleShape)
            .background(c.surface.copy(alpha = .96f))
            .border(0.5.dp, c.line, CircleShape)
            .clickable(interactionSource = source, indication = LocalIndication.current) {
                haptics.perform(HetuHaptic.Tick)
                onClick()
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Rounded.KeyboardArrowUp, contentDescription = ht("回到顶部"), tint = c.text, modifier = Modifier.size(22.dp))
    }
}

/**
 * [HxPage] inside an [HxTabbedPage] section: just the list. Its bar actions, overlay and
 * subtitle are handed to the host page through stable wrappers, so they update in place
 * without the host and the section recomposing each other in a loop.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HxEmbeddedPage(
    embed: HxEmbed,
    subtitle: String?,
    refreshing: Boolean,
    onRefresh: (() -> Unit)?,
    leadingActions: @Composable RowScope.() -> Unit,
    actions: @Composable RowScope.() -> Unit,
    overlay: @Composable BoxScope.() -> Unit,
    content: LazyListScope.() -> Unit,
) {
    val c = Hx.colors
    val currentLeadingActions by rememberUpdatedState(leadingActions)
    val currentActions by rememberUpdatedState(actions)
    val currentOverlay by rememberUpdatedState(overlay)
    val stableLeadingActions = remember<@Composable RowScope.() -> Unit> { { currentLeadingActions.invoke(this) } }
    val stableActions = remember<@Composable RowScope.() -> Unit> { { currentActions.invoke(this) } }
    val stableOverlay = remember<@Composable BoxScope.() -> Unit> { { currentOverlay.invoke(this) } }
    val host = embed.host
    val key = embed.key
    DisposableEffect(host, key) {
        host.leadingActions[key] = stableLeadingActions
        host.actions[key] = stableActions
        host.overlays[key] = stableOverlay
        onDispose {
            host.leadingActions.remove(key)
            host.actions.remove(key)
            host.overlays.remove(key)
        }
    }
    SideEffect { if (host.subtitles[key] != subtitle) host.subtitles[key] = subtitle }
    val list: @Composable () -> Unit = {
        LazyColumn(
            state = embed.listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = embed.top, bottom = embed.bottom),
        ) { content() }
    }
    if (onRefresh != null) {
        val pullState = rememberPullToRefreshState()
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = { if (!refreshing) onRefresh() },
            modifier = Modifier.fillMaxSize(),
            state = pullState,
            indicator = {
                PullToRefreshDefaults.Indicator(
                    state = pullState,
                    isRefreshing = refreshing,
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = (embed.top - 6.dp).coerceAtLeast(0.dp)),
                    containerColor = c.surface.copy(alpha = .90f),
                    color = c.accent,
                )
            },
        ) { list() }
    } else {
        list()
    }
}

/* ------------------------------------------------------------------ */
/*  Surfaces                                                            */
/* ------------------------------------------------------------------ */

/** Press feedback shared by every tappable surface: a small, quick scale. */
@Composable
internal fun Modifier.hxPressScale(source: MutableInteractionSource, pressedScale: Float = .975f): Modifier {
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) pressedScale else 1f, HxMotion.press(), label = "press")
    return this.graphicsLayer { scaleX = scale; scaleY = scale }
}

@Composable
internal fun HxCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    color: Color = Hx.colors.surface,
    padding: PaddingValues = PaddingValues(14.dp),
    brush: androidx.compose.ui.graphics.Brush? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    val c = Hx.colors
    Surface(
        modifier = modifier.then(if (onClick != null) Modifier.hxPressScale(source) else Modifier).hxSoftShadow(Hx.cardShape),
        shape = Hx.cardShape,
        color = color,
        border = if (c.dark) BorderStroke(0.5.dp, c.line) else null,
    ) {
        Column(
            Modifier
                .then(if (brush != null) Modifier.background(brush) else Modifier)
                .then(if (onClick != null) Modifier.clickable(interactionSource = source, indication = LocalIndication.current, onClick = onClick) else Modifier)
                .padding(padding),
            content = content,
        )
    }
}

/** A titled group of rows. */
@Composable
internal fun HxSection(
    title: String? = null,
    modifier: Modifier = Modifier,
    trailing: @Composable (RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.fillMaxWidth().padding(horizontal = Hx.gutter).padding(bottom = 10.dp)) {
        if (title != null || trailing != null) {
            Row(
                Modifier.fillMaxWidth().padding(start = 4.dp, end = 2.dp, bottom = 6.dp).heightIn(min = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    title.orEmpty(),
                    style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp, letterSpacing = 0.18.sp),
                    color = Hx.colors.textMuted,
                    modifier = Modifier.weight(1f),
                )
                if (trailing != null) trailing()
            }
        }
        content()
    }
}

/** Rows stacked in one card with inset hairlines. */
@Composable
internal fun HxGroup(modifier: Modifier = Modifier, title: String? = null, content: @Composable ColumnScope.() -> Unit) {
    val c = Hx.colors
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = Hx.cardShape,
        color = c.surface,
        border = if (c.dark) BorderStroke(0.5.dp, c.line.copy(alpha = .72f)) else null,
        shadowElevation = 0.dp,
        tonalElevation = 0.dp,
    ) {
        // Rows that appear/disappear (expanded options, loaded lists) resize the card smoothly.
        Column(Modifier.animateContentSize(tween(HxMotion.Medium, easing = HxMotion.Emphasized)).padding(vertical = 2.dp)) {
            if (title != null) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = Hx.colors.text,
                    modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 2.dp),
                )
            }
            content()
        }
    }
}

@Composable
internal fun HxDivider(inset: Dp = 50.dp) {
    // Rows inside a card are separated by rhythm, not rules; a faint hairline remains in dark mode.
    if (Hx.colors.dark) HorizontalDivider(Modifier.padding(start = inset), thickness = 0.5.dp, color = Hx.colors.line.copy(alpha = .35f))
}

@Composable
internal fun HxIconBadge(icon: ImageVector, tint: Color = Hx.colors.accent, size: Dp = 27.dp) {
    val c = Hx.colors
    val foreground = if (tint == c.bad) c.bad else c.textMuted
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = null, tint = foreground, modifier = Modifier.size(19.dp))
    }
}

/**
 * The single list-row primitive. Title, optional supporting line, optional trailing
 * slot; whole row tappable when [onClick] is set.
 */
@Composable
internal fun HxRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    iconTint: Color = Hx.colors.accent,
    enabled: Boolean = true,
    danger: Boolean = false,
    referenceRow: Boolean = false,
    referenceTextSizeSp: Float = 16f,
    minimumHeight: Dp = 50.dp,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    trailing: @Composable (RowScope.() -> Unit)? = null,
) {
    val c = Hx.colors
    val haptics = rememberHetuHaptics()
    val contentAlpha by animateFloatAsState(if (enabled) 1f else .45f, tween(HxMotion.Medium), label = "rowAlpha")
    Row(
        modifier
            .fillMaxWidth()
            .hxAnchorSource()
            .then(
                if ((onClick != null || onLongClick != null) && enabled) Modifier.hxCombinedClick(
                    onClick = onClick ?: {},
                    onLongClick = onLongClick?.let { long -> { haptics.perform(HetuHaptic.LongPress); long() } },
                ) else Modifier,
            )
            .heightIn(min = minimumHeight)
            .padding(horizontal = if (referenceRow) (if (referenceTextSizeSp >= 18f) 21.dp else 18.dp) else 14.dp, vertical = 8.dp)
            .graphicsLayer { alpha = contentAlpha },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            if (referenceRow) Icon(settingsLineIcon(icon), null, tint = if (danger) c.bad else c.text, modifier = Modifier.size(24.dp))
            else HxIconBadge(icon, if (danger) c.bad else iconTint)
            Spacer(Modifier.width(if (referenceRow) (if (referenceTextSizeSp >= 18f) 24.dp else 16.dp) else 9.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = if (referenceRow) MaterialTheme.typography.bodyLarge.copy(fontSize = referenceTextSizeSp.sp, lineHeight = (referenceTextSizeSp + 5f).sp) else MaterialTheme.typography.bodyLarge,
                fontWeight = if (referenceRow) FontWeight.SemiBold else FontWeight.Medium,
                color = if (danger) c.bad else c.text,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (!subtitle.isNullOrBlank()) {
                Text(subtitle, style = if (referenceRow) MaterialTheme.typography.bodySmall.copy(fontSize = 12.5.sp, lineHeight = 17.sp) else MaterialTheme.typography.bodySmall, color = c.textMuted, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(8.dp))
            trailing()
        }
    }
}

@Composable
internal fun HxChevron() {
    Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = Hx.colors.textFaint, modifier = Modifier.size(18.dp))
}

@Composable
internal fun HxNavRow(
    title: String,
    subtitle: String? = null,
    icon: ImageVector? = null,
    iconTint: Color = Hx.colors.accent,
    value: String? = null,
    enabled: Boolean = true,
    danger: Boolean = false,
    dropdown: Boolean = false,
    onClick: () -> Unit,
) {
    HxRow(title, subtitle = subtitle, icon = icon, iconTint = iconTint, enabled = enabled, danger = danger, onClick = onClick) {
        if (!value.isNullOrBlank()) {
            AnimatedContent(
                targetState = value,
                transitionSpec = { fadeIn(tween(HxMotion.Medium)).togetherWith(fadeOut(tween(HxMotion.Short))) },
                label = "navValue",
            ) { v ->
                Text(
                    v,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Hx.colors.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 150.dp),
                )
            }
            Spacer(Modifier.width(2.dp))
        }
        if (dropdown) Icon(Icons.Rounded.UnfoldMore, contentDescription = null, tint = Hx.colors.textFaint, modifier = Modifier.size(18.dp))
        else HxChevron()
    }
}

@Composable
internal fun HxSwitchRow(
    title: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    subtitle: String? = null,
    icon: ImageVector? = null,
    iconTint: Color = Hx.colors.accent,
    enabled: Boolean = true,
) {
    val haptics = rememberHetuHaptics()
    HxRow(title, subtitle = subtitle, icon = icon, iconTint = iconTint, enabled = enabled, onClick = {
        haptics.perform(if (!checked) HetuHaptic.ToggleOn else HetuHaptic.ToggleOff)
        onChange(!checked)
    }) {
        HxSwitch(checked = checked, onChange = onChange, enabled = enabled)
    }
}

@Composable
internal fun HxSwitch(checked: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean = true) {
    val c = Hx.colors
    val haptics = rememberHetuHaptics()
    val thumbOffset by animateDpAsState(if (checked) 25.dp else 3.dp, tween(HxMotion.Short), label = "switchThumb")
    val track by animateColorAsState(if (checked) c.accent else c.line, tween(HxMotion.Short), label = "switchTrack")
    Box(
        Modifier.size(width = 50.dp, height = 48.dp)
            .toggleable(value = checked, enabled = enabled, role = Role.Switch) { on ->
                haptics.perform(if (on) HetuHaptic.ToggleOn else HetuHaptic.ToggleOff)
                onChange(on)
            }.graphicsLayer { alpha = if (enabled) 1f else .4f },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(Modifier.fillMaxWidth().height(28.dp).clip(Hx.pillShape).background(track))
        Box(Modifier.offset(x = thumbOffset).size(22.dp).clip(Hx.pillShape).background(c.onAccent))
    }
}

/* ------------------------------------------------------------------ */
/*  Small pieces                                                        */
/* ------------------------------------------------------------------ */

internal enum class HxTone { Neutral, Accent, Good, Warn, Bad }

@Composable
internal fun HxTone.fg(): Color = when (this) {
    HxTone.Neutral -> Hx.colors.textMuted
    HxTone.Accent -> Hx.colors.accent
    HxTone.Good -> Hx.colors.good
    HxTone.Warn -> Hx.colors.warn
    HxTone.Bad -> Hx.colors.bad
}

@Composable
internal fun HxTone.bg(): Color = when (this) {
    HxTone.Neutral -> Hx.colors.surfaceMuted
    HxTone.Accent -> Hx.colors.accentSoft
    HxTone.Good -> Hx.colors.goodSoft
    HxTone.Warn -> Hx.colors.warnSoft
    HxTone.Bad -> Hx.colors.badSoft
}

@Composable
internal fun HxPill(text: String, tone: HxTone = HxTone.Neutral, modifier: Modifier = Modifier) {
    val bg by animateColorAsState(tone.bg(), tween(HxMotion.Medium), label = "pillBg")
    val fg by animateColorAsState(tone.fg(), tween(HxMotion.Medium), label = "pillFg")
    Box(
        modifier
            .clip(Hx.pillShape)
            .background(bg)
            .animateContentSize(tween(HxMotion.Short, easing = HxMotion.Emphasized))
            .padding(horizontal = 8.dp, vertical = 3.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MaterialTheme.typography.labelSmall, color = fg, maxLines = 1)
    }
}

@Composable
internal fun HxDot(color: Color, size: Dp = 8.dp) {
    Box(Modifier.size(size).clip(CircleShape).background(color))
}

@Composable
internal fun HxSpinner(size: Dp = 18.dp, color: Color = Hx.colors.accent) {
    CircularProgressIndicator(modifier = Modifier.size(size), color = color, strokeWidth = 2.dp)
}

/** Inline status message with optional action. */
@Composable
internal fun HxBanner(
    text: String,
    tone: HxTone = HxTone.Accent,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    referenceCompact: Boolean = false,
) {
    val bg by animateColorAsState(tone.bg(), tween(HxMotion.Medium), label = "bannerBg")
    if (referenceCompact) {
        Row(modifier.fillMaxWidth().clip(Hx.rowShape).background(bg)
            .animateContentSize(tween(HxMotion.Medium, easing = HxMotion.Emphasized))
            .heightIn(min = 46.dp).padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(18.dp), contentAlignment = Alignment.Center) {
                Icon(if (tone == HxTone.Bad || tone == HxTone.Warn) Icons.Rounded.Error else Icons.Rounded.Info,
                    null, tint = tone.fg(), modifier = Modifier.requiredSize(if (tone == HxTone.Bad || tone == HxTone.Warn) 23.dp else 18.dp))
            }
            Spacer(Modifier.width(8.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium.copy(fontSize = 11.5.sp, lineHeight = 14.sp,
                fontWeight = FontWeight.SemiBold), color = tone.fg(), modifier = Modifier.weight(1f))
            if (actionLabel != null && onAction != null) {
                Box(Modifier.heightIn(min = 32.dp).clickable(role = Role.Button, onClick = onAction)
                    .padding(horizontal = 6.dp), contentAlignment = Alignment.Center) {
                    Text(actionLabel, color = Hx.colors.accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        return
    }
    Row(
        modifier
            .fillMaxWidth()
            .clip(Hx.rowShape)
            .background(bg)
            .animateContentSize(tween(HxMotion.Medium, easing = HxMotion.Emphasized))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (tone == HxTone.Bad || tone == HxTone.Warn) Icons.Rounded.ErrorOutline else Icons.Rounded.Info,
            contentDescription = null,
            tint = tone.fg(),
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = Hx.colors.text, modifier = Modifier.weight(1f))
        if (actionLabel != null && onAction != null) {
            TextButton(onClick = onAction) { Text(actionLabel, color = tone.fg(), fontWeight = FontWeight.SemiBold) }
        }
    }
}

@Composable
internal fun HxEmpty(icon: ImageVector, title: String, description: String? = null, action: (@Composable () -> Unit)? = null) {
    val c = Hx.colors
    // Settles in once instead of popping: the empty state is often the first thing seen.
    val appear = remember { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(Unit) { appear.animateTo(1f, tween(HxMotion.Long, easing = HxMotion.Emphasized)) }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 36.dp)
            .graphicsLayer {
                alpha = appear.value
                translationY = (1f - appear.value) * 12.dp.toPx()
            },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(64.dp)
                .graphicsLayer {
                    val s = .82f + .18f * appear.value
                    scaleX = s
                    scaleY = s
                }
                .clip(CircleShape)
                .background(c.surfaceMuted),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, tint = c.textMuted, modifier = Modifier.size(28.dp))
        }
        Spacer(Modifier.height(14.dp))
        Text(title, style = MaterialTheme.typography.titleSmall, color = c.text, textAlign = TextAlign.Center)
        if (!description.isNullOrBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(description, style = MaterialTheme.typography.bodySmall, color = c.textMuted, textAlign = TextAlign.Center)
        }
        if (action != null) {
            Spacer(Modifier.height(14.dp))
            action()
        }
    }
}

/** Pill-shaped segmented control with a sliding indicator. */
@Composable
internal fun HxSegmented(
    options: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    selectionColor: Color = Hx.colors.surface,
    selectedTextColor: Color = Hx.colors.text,
) {
    val c = Hx.colors
    val haptics = rememberHetuHaptics()
    var widthPx by remember { mutableStateOf(0) }
    val index = options.indexOfFirst { it.first == selected }.coerceAtLeast(0)
    val density = LocalDensity.current
    val segment = if (options.isEmpty()) 0.dp else with(density) { (widthPx / options.size).toDp() }
    // Liquid indicator: the leading edge moves first and the trailing edge catches up, so
    // the thumb stretches toward its destination and settles back to one segment.
    val forward = rememberHxDirection(index)
    val fast = androidx.compose.animation.core.spring<Dp>(dampingRatio = .82f, stiffness = 700f)
    val slow = androidx.compose.animation.core.spring<Dp>(dampingRatio = .86f, stiffness = 260f)
    val left by animateDpAsState(segment * index, if (forward) slow else fast, label = "segLeft")
    val right by animateDpAsState(segment * (index + 1), if (forward) fast else slow, label = "segRight")
    val contentAlpha by animateFloatAsState(if (enabled) 1f else .5f, tween(HxMotion.Medium), label = "segEnabled")
    Box(
        modifier
            .fillMaxWidth()
            .height(36.dp)
            .graphicsLayer { alpha = contentAlpha }
            .clip(Hx.pillShape)
            .background(c.surfaceMuted)
            .padding(2.dp)
            .onSizeChanged { widthPx = it.width },
    ) {
        if (widthPx > 0 && options.isNotEmpty()) {
            Box(
                Modifier
                    .offset(x = left)
                    .width((right - left).coerceAtLeast(0.dp))
                    .height(32.dp)
                    .hxSoftShadow(Hx.pillShape, 3.dp)
                    .clip(Hx.pillShape)
                    .background(selectionColor)
                    .then(if (c.dark) Modifier.border(0.5.dp, c.line, Hx.pillShape) else Modifier),
            )
        }
        Row(Modifier.fillMaxSize()) {
            options.forEachIndexed { i, (id, label) ->
                val active = i == index
                val color by animateColorAsState(if (active) selectedTextColor else c.textMuted, tween(HxMotion.Short), label = "segText")
                val source = remember { MutableInteractionSource() }
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxSize()
                        .hxPressScale(source, .94f)
                        .clip(Hx.pillShape)
                        .clickable(interactionSource = source, indication = null, enabled = enabled && !active) {
                            haptics.perform(HetuHaptic.Tick)
                            onSelect(id)
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                        color = color,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
internal fun HxSearchField(
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    autoFocus: Boolean = false,
) {
    val c = Hx.colors
    val focus = remember { FocusRequester() }
    if (autoFocus) {
        LaunchedEffect(Unit) {
            // Wait one frame so the field is attached before asking for focus + keyboard.
            kotlinx.coroutines.delay(60)
            runCatching { focus.requestFocus() }
        }
    }
    androidx.compose.foundation.text.BasicTextField(
        value = value,
        onValueChange = onChange,
        modifier = modifier.fillMaxWidth().heightIn(min = 44.dp).focusRequester(focus)
            .clip(Hx.pillShape).background(c.surface),
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = c.text, fontSize = 18.sp, lineHeight = 24.sp),
        cursorBrush = androidx.compose.ui.graphics.SolidColor(c.accent),
        decorationBox = { input ->
            Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Search, null, tint = c.textMuted, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(8.dp))
                Box(Modifier.weight(1f).padding(vertical = 10.dp)) {
                    if (value.isEmpty()) Text(placeholder, color = c.textFaint, fontSize = 18.sp, lineHeight = 24.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    input()
                }
                if (value.isNotEmpty()) {
                    IconButton(onClick = { onChange("") }, modifier = Modifier.size(44.dp)) {
                        Icon(Icons.Rounded.Cancel, ht("清除"), tint = c.textFaint, modifier = Modifier.size(22.dp))
                    }
                } else Spacer(Modifier.width(12.dp))
            }
        },
    )
}

/** A metric with a small caption. */
@Composable
internal fun HxMetric(label: String, value: String, modifier: Modifier = Modifier, unit: String? = null, valueColor: Color = Hx.colors.text) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = Hx.colors.textMuted, maxLines = 1)
        Spacer(Modifier.height(2.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                value,
                style = MaterialTheme.typography.titleMedium.merge(HxNumberStyle),
                color = valueColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!unit.isNullOrBlank()) {
                Spacer(Modifier.width(3.dp))
                Text(unit, style = MaterialTheme.typography.labelSmall, color = Hx.colors.textMuted, modifier = Modifier.padding(bottom = 2.dp))
            }
        }
    }
}

/* ------------------------------------------------------------------ */
/*  Sheets and dialogs                                                  */
/* ------------------------------------------------------------------ */

internal fun hxCopy(context: Context, label: String, text: String) {
    val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    manager.setPrimaryClip(ClipData.newPlainText(label, text))
    Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HxSheet(
    onDismiss: () -> Unit,
    title: String? = null,
    showClose: Boolean = false,
    containerColor: Color = Hx.colors.surface,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = Hx.colors
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    // Actions inside a sheet close it with the slide-down animation first, then run —
    // instead of the sheet vanishing in a single frame when its state flips to null.
    val close: (() -> Unit) -> Unit = remember(sheetState, scope) {
        { after ->
            scope.launch {
                try {
                    sheetState.hide()
                } finally {
                    after()
                }
            }
        }
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = containerColor,
        contentColor = c.text,
        scrimColor = Color.Black.copy(alpha = if (c.dark) .52f else .32f),
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        dragHandle = {
            Box(
                Modifier
                    .padding(top = 10.dp, bottom = 12.dp)
                    .size(width = 36.dp, height = 4.dp)
                    .clip(Hx.pillShape)
                    .background(c.textFaint.copy(alpha = .45f)),
            )
        },
    ) {
        CompositionLocalProvider(LocalHxSheetClose provides close) {
            Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 12.dp)) {
                if (title != null) {
                    Row(Modifier.fillMaxWidth().padding(start = 22.dp, end = if (showClose) 8.dp else 22.dp).padding(bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(title, style = MaterialTheme.typography.titleLarge, color = c.text, modifier = Modifier.weight(1f))
                        if (showClose) IconButton(onClick = { close(onDismiss) }) {
                            Icon(Icons.Rounded.Close, ht("关闭详情"), tint = c.textMuted)
                        }
                    }
                }
                content()
            }
        }
    }
}

/**
 * Inside an [HxSheet]: `LocalHxSheetClose.current { … }` animates the sheet away and then
 * runs the block. Outside a sheet it simply runs the block.
 */
internal val LocalHxSheetClose = staticCompositionLocalOf<(() -> Unit) -> Unit> { { after -> after() } }

/** Monospace text viewer (logs, diagnostics, generated config). */
@Composable
internal fun HxTextSheet(title: String, text: String, onDismiss: () -> Unit, onRefresh: (() -> Unit)? = null, wrapLines: Boolean = false, showCopyLabel: Boolean = false, referenceDocument: Boolean = false) {
    val context = LocalContext.current
    val c = Hx.colors
    HxSheet(onDismiss = onDismiss) {
        Row(Modifier.fillMaxWidth().padding(start = 22.dp, end = 10.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = if (referenceDocument) MaterialTheme.typography.titleLarge.copy(fontSize = if (title == "AdGuard") 30.sp else 24.sp, fontWeight = FontWeight.Bold) else if (wrapLines) MaterialTheme.typography.titleLarge.copy(fontSize = 24.sp, fontWeight = FontWeight.Bold) else MaterialTheme.typography.titleLarge, color = c.text, modifier = Modifier.weight(1f))
            if (onRefresh != null) TextButton(onClick = onRefresh) { Text(ht("刷新")) }
            if (showCopyLabel) TextButton(onClick = { hxCopy(context, title, text) }) {
                Icon(Icons.Rounded.ContentCopy, null, tint = c.accent, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(5.dp)); Text(ht("复制"), color = c.accent, fontSize = 12.sp)
            } else IconButton(onClick = { hxCopy(context, title, text) }) { Icon(Icons.Rounded.ContentCopy, ht("复制"), tint = c.textMuted) }
        }
        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(max = if (wrapLines) (LocalConfiguration.current.screenHeightDp * .49f).dp else 520.dp)
                .padding(horizontal = if (referenceDocument) 10.5.dp else 16.dp)
                .clip(Hx.rowShape)
                .background(c.surfaceMuted)
                .verticalScroll(rememberScrollState())
                .then(if (wrapLines) Modifier else Modifier.horizontalScroll(rememberScrollState()))
                .padding(14.dp),
        ) {
            Text(
                text.ifBlank { "（空）" },
                fontFamily = if (wrapLines && !referenceDocument) FontFamily.Default else FontFamily.Monospace,
                fontSize = if (referenceDocument) 10.sp else 11.5.sp,
                lineHeight = if (referenceDocument) 14.sp else 16.sp,
                letterSpacing = if (referenceDocument) 0.sp else androidx.compose.ui.unit.TextUnit.Unspecified,
                color = c.text,
            )
        }
    }
}

internal enum class HxChoicePresentation { Standard, Settings, Language, Scale, Notification }

internal data class HxChoice(val id: String, val label: String, val description: String? = null, val enabled: Boolean = true)

@Composable
internal fun HxChoiceSheet(
    title: String,
    choices: List<HxChoice>,
    selected: String?,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
    footer: String? = null,
    dimBehind: Boolean = true,
    referenceRadios: Boolean = false,
    trailingReferenceRadios: Boolean = false,
    presentation: HxChoicePresentation = HxChoicePresentation.Standard,
    menuWidthOverride: Dp? = null,
    maxVisibleChoices: Int? = null,
    menuItemVerticalPadding: Dp? = null,
    menuItemMinimumHeight: Dp? = null,
    menuItemFontSizeSp: Float? = null,
    menuAnchorEndInset: Dp? = null,
    menuContentTopPadding: Dp? = null,
    menuContentBottomPadding: Dp? = null,
    menuRowPresentation: HxChoicePresentation? = null,
    menuDividers: Boolean? = null,
    menuSelectedHorizontalPadding: Dp? = null,
) {
    val c = Hx.colors
    val haptics = rememberHetuHaptics()
    // Opened from a row: show a dropdown right next to it (the row stays visible).
    val anchor = remember { HxAnchor.take() }
    if (anchor != null) {
        val referenceSettings = presentation != HxChoicePresentation.Standard
        val menuWidth = when (presentation) {
            HxChoicePresentation.Settings -> 116.dp
            HxChoicePresentation.Language -> 130.dp
            HxChoicePresentation.Scale -> 154.dp
            HxChoicePresentation.Notification -> 148.dp
            else -> if (trailingReferenceRadios) 200.dp else if (referenceRadios) 232.dp else if (dimBehind) 152.dp else 128.dp
        }
        val scopedNotification = presentation == HxChoicePresentation.Notification && !referenceRadios && !trailingReferenceRadios &&
            (menuItemVerticalPadding != null || menuItemMinimumHeight != null || menuItemFontSizeSp != null || menuAnchorEndInset != null ||
                menuContentTopPadding != null || menuContentBottomPadding != null || menuRowPresentation != null || menuDividers != null || menuSelectedHorizontalPadding != null)
        val scopedOuterPadding = scopedNotification && (menuContentTopPadding != null || menuContentBottomPadding != null)
        HxAnchoredMenu(anchor, onDismiss, dimBehind = !referenceSettings && (dimBehind || referenceRadios), minWidth = menuWidthOverride ?: menuWidth,
            anchorEndInset = if (scopedNotification) menuAnchorEndInset ?: 14.dp else 14.dp,
            verticalPadding = if (scopedOuterPadding) 0.dp else 6.dp) { close ->
            @Composable fun notificationMenuItem(choice: HxChoice) {
                @Composable fun item() {
                    HxMenuItem(
                        choice.label,
                        onClick = { close { onPick(choice.id) } },
                        description = choice.description,
                        selected = choice.id == selected,
                        selectedTextColor = if (presentation == HxChoicePresentation.Scale || !referenceSettings && dimBehind) c.accent else c.text,
                        enabled = choice.enabled,
                        choicePresentation = menuRowPresentation ?: presentation,
                        compact = menuItemVerticalPadding != null,
                        compactVerticalPadding = menuItemVerticalPadding ?: 6.dp,
                        minimumHeight = menuItemMinimumHeight ?: 0.dp,
                        labelFontSizeSp = menuItemFontSizeSp,
                    )
                }
                if (menuSelectedHorizontalPadding != null && choice.id == selected) {
                    Box(Modifier.fillMaxWidth().padding(horizontal = menuSelectedHorizontalPadding).clip(Hx.pillShape)) { item() }
                } else item()
            }
            if (scopedOuterPadding) Spacer(Modifier.height(menuContentTopPadding ?: 6.dp))
            if (referenceRadios) {
                Text(title, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    textAlign = if (trailingReferenceRadios) TextAlign.Start else TextAlign.Center, fontSize = if (trailingReferenceRadios) 15.sp else 18.sp, fontWeight = FontWeight.Bold, color = c.text)
                Column(Modifier.padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(if (trailingReferenceRadios) 0.dp else 6.dp)) {
                    choices.forEachIndexed { index, choice ->
                        val active = choice.id == selected
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                            .background(if (trailingReferenceRadios) Color.Transparent else if (active) c.accentSoft else if (c.dark) c.surfaceMuted else Color.White)
                            .selectable(selected = active, enabled = choice.enabled, role = Role.RadioButton) { haptics.perform(HetuHaptic.Tick); close { onPick(choice.id) } }
                            .padding(horizontal = if (trailingReferenceRadios) 2.dp else 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            @Composable fun radio() { Box(Modifier.size(20.dp).clip(CircleShape)
                                .background(if (active) c.accent else Color.Transparent)
                                .then(if (active) Modifier else Modifier.border(1.5.dp, c.textFaint, CircleShape)), contentAlignment = Alignment.Center) {
                                if (active) Icon(Icons.Rounded.Check, null, tint = c.onAccent, modifier = Modifier.size(14.dp))
                            }
                            }
                            if (!trailingReferenceRadios) { radio(); Spacer(Modifier.width(14.dp)) }
                            Text(choice.label, Modifier.weight(1f), color = if (active && !trailingReferenceRadios) c.accent else c.text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            if (trailingReferenceRadios) { Spacer(Modifier.width(10.dp)); radio() }
                        }
                        if (trailingReferenceRadios && index < choices.lastIndex) HorizontalDivider(thickness = .5.dp, color = c.line)
                    }
                }
            } else if (maxVisibleChoices != null) {
                val density = LocalDensity.current
                var visibleRowsHeight by remember(maxVisibleChoices, choices) { mutableStateOf<Int?>(null) }
                @Composable fun choiceRow(index: Int, choice: HxChoice) {
                if (scopedNotification) notificationMenuItem(choice) else HxMenuItem(
                    choice.label,
                    onClick = { close { onPick(choice.id) } },
                    description = choice.description,
                    selected = choice.id == selected,
                    selectedTextColor = if (presentation == HxChoicePresentation.Scale || !referenceSettings && dimBehind) c.accent else c.text,
                    enabled = choice.enabled,
                    choicePresentation = presentation,
                )
                if (!scopedNotification || menuDividers != false)
                if (referenceSettings && index < choices.lastIndex) HorizontalDivider(Modifier.padding(horizontal = 12.dp), thickness = .5.dp, color = c.line.copy(alpha = .4f))
                }
                Column(Modifier.fillMaxWidth().heightIn(max = visibleRowsHeight?.let { with(density) { it.toDp() } } ?: (maxVisibleChoices * 48.5f).dp).verticalScroll(rememberScrollState())) {
                    Column(Modifier.onSizeChanged { visibleRowsHeight = it.height }) {
                        choices.take(maxVisibleChoices).forEachIndexed { index, choice -> choiceRow(index, choice) }
                    }
                    choices.drop(maxVisibleChoices).forEachIndexed { index, choice -> choiceRow(index + maxVisibleChoices, choice) }
                }
            } else choices.forEachIndexed { index, choice ->
                if (scopedNotification) notificationMenuItem(choice) else HxMenuItem(
                    choice.label,
                    onClick = { close { onPick(choice.id) } },
                    description = choice.description,
                    selected = choice.id == selected,
                    selectedTextColor = if (presentation == HxChoicePresentation.Scale || !referenceSettings && dimBehind) c.accent else c.text,
                    enabled = choice.enabled,
                    choicePresentation = presentation,
                )
                if (!scopedNotification || menuDividers != false)
                if (referenceSettings && index < choices.lastIndex) HorizontalDivider(Modifier.padding(horizontal = 12.dp), thickness = .5.dp, color = c.line.copy(alpha = .4f))
            }
            if (!footer.isNullOrBlank()) {
                Text(footer, style = if (presentation == HxChoicePresentation.Scale) MaterialTheme.typography.labelSmall.copy(fontSize = 8.5.sp) else MaterialTheme.typography.labelSmall, color = c.textFaint, modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp))
            }
            if (scopedOuterPadding) Spacer(Modifier.height(menuContentBottomPadding ?: 6.dp))
        }
        return
    }
    // The tapped row lights up and its check pops in; the sheet then slides away.
    var picked by remember { mutableStateOf(selected) }
    var closing by remember { mutableStateOf(false) }
    HxSheet(onDismiss = onDismiss, title = title) {
        val close = LocalHxSheetClose.current
        Column(Modifier.padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            choices.forEach { choice ->
                val active = choice.id == picked
                val bg by animateColorAsState(if (active) c.accentSoft else Color.Transparent, tween(HxMotion.Medium), label = "choiceBg")
                val checkScale by animateFloatAsState(if (active) 1f else 0f, HxMotion.pop(), label = "choiceCheck")
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(Hx.rowShape)
                        .background(bg)
                        .clickable(enabled = choice.enabled) {
                            if (!closing) {
                                closing = true
                                haptics.perform(HetuHaptic.Tick)
                                picked = choice.id
                                close { onPick(choice.id) }
                            }
                        }
                        .padding(horizontal = 14.dp, vertical = 12.dp)
                        .graphicsLayer { alpha = if (choice.enabled) 1f else .4f },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            choice.label,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                            color = if (active) c.accent else c.text,
                        )
                        if (!choice.description.isNullOrBlank()) {
                            Text(choice.description, style = MaterialTheme.typography.bodySmall, color = c.textMuted)
                        }
                    }
                    Icon(
                        Icons.Rounded.Check,
                        null,
                        tint = c.accent,
                        modifier = Modifier.size(20.dp).graphicsLayer {
                            scaleX = checkScale
                            scaleY = checkScale
                            alpha = checkScale.coerceIn(0f, 1f)
                        },
                    )
                }
            }
            if (!footer.isNullOrBlank()) {
                Text(footer, style = MaterialTheme.typography.bodySmall, color = c.textMuted, modifier = Modifier.padding(14.dp))
            }
        }
    }
}

internal data class HxField(
    val label: String,
    val initial: String = "",
    val placeholder: String = "",
    val singleLine: Boolean = true,
    val number: Boolean = false,
)

/** Generic form dialog: returns the entered values in field order. */
@Composable
internal fun HxFormDialog(
    title: String,
    fields: List<HxField>,
    confirmLabel: String = "保存",
    message: String? = null,
    configFooter: Boolean = false,
    messageBelowFields: Boolean = false,
    errorField: (String) -> Int? = { null },
    widthFraction: Float? = null,
    errorWidthFraction: Float? = null,
    titleFontSize: androidx.compose.ui.unit.TextUnit = 20.sp,
    titleLineHeight: androidx.compose.ui.unit.TextUnit = 26.sp,
    titleTextAlign: TextAlign = TextAlign.Center,
    centerTitleOnError: Boolean = false,
    hideMessageOnError: Boolean = false,
    highlightError: Boolean = true,
    fieldOutlineColor: Color? = null,
    errorFieldOutlineColor: Color? = null,
    labelFontWeight: FontWeight? = null,
    inputFontWeight: FontWeight? = null,
    confirmColorOverride: Color? = null,
    normalPlainFieldHeight: Dp? = null,
    normalPlainFieldVerticalPadding: Dp? = null,
    plainFields: Boolean = false,
    compactPills: Boolean = false,
    validate: (List<String>) -> String? = { null },
    onConfirm: (List<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    val c = Hx.colors
    val values = remember { fields.map { mutableStateOf(it.initial) } }
    var error by remember { mutableStateOf<String?>(null) }
    val haptics = rememberHetuHaptics()
    // Invalid input: a short horizontal shake plus a reject haptic, like a wrong passcode.
    var shakeTick by remember { mutableIntStateOf(0) }
    val shake = remember { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(shakeTick) {
        if (shakeTick > 0) {
            haptics.perform(HetuHaptic.Reject)
            for (x in listOf(12f, -9f, 6f, -3f, 0f)) shake.animateTo(x, tween(48))
        }
    }
    val submit: () -> Unit = {
        val result = values.map { it.value.trim() }
        val problem = validate(result)
        if (problem != null) { error = problem; shakeTick++ }
        else { haptics.perform(HetuHaptic.Confirm); onConfirm(result) }
    }
    HxReferenceDialog(onDismiss, widthFraction = (if (error != null) errorWidthFraction else null) ?: widthFraction ?: if (configFooter) .74f else .76f, contentPadding = 0.dp) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
            Column(Modifier.padding(horizontal = if (compactPills) 16.dp else if (configFooter) 20.dp else 22.dp, vertical = if (compactPills) 16.dp else if (configFooter) 20.dp else 18.dp)
                .graphicsLayer { translationX = shake.value * density }) {
                Text(title, fontSize = titleFontSize, lineHeight = titleLineHeight, fontWeight = FontWeight.Bold, color = c.text,
                    textAlign = if (centerTitleOnError && error != null) TextAlign.Center else titleTextAlign, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(if (compactPills) 10.dp else if (configFooter) 18.dp else 14.dp))
                if (!message.isNullOrBlank() && !messageBelowFields && !(hideMessageOnError && error != null)) {
                    Text(message, style = MaterialTheme.typography.bodyMedium, color = c.textMuted)
                    Spacer(Modifier.height(12.dp))
                }
                fields.forEachIndexed { index, field ->
                    if (index > 0) Spacer(Modifier.height(12.dp))
                    Text(field.label, color = c.textMuted, fontSize = if (plainFields) 15.sp else 13.sp, lineHeight = if (plainFields) 20.sp else 18.sp, fontWeight = labelFontWeight ?: FontWeight.Medium)
                    Spacer(Modifier.height(if (compactPills) 3.dp else 6.dp))
                    androidx.compose.foundation.text.BasicTextField(
                        value = values[index].value,
                        onValueChange = { values[index].value = it; error = null },
                        singleLine = field.singleLine,
                        maxLines = if (field.singleLine) 1 else 6,
                        keyboardOptions = if (field.number) KeyboardOptions(keyboardType = KeyboardType.Number) else KeyboardOptions.Default,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = c.text, fontSize = if (plainFields) 16.sp else 15.sp, lineHeight = 20.sp, fontWeight = inputFontWeight ?: MaterialTheme.typography.bodyLarge.fontWeight),
                        cursorBrush = androidx.compose.ui.graphics.SolidColor(c.accent),
                        modifier = Modifier.fillMaxWidth().heightIn(min = (if (error == null && plainFields) normalPlainFieldHeight else null) ?: if (compactPills) 36.dp else if (configFooter) 42.dp else if (plainFields) 40.dp else 38.dp).clip(RoundedCornerShape(if (compactPills) 24.dp else 10.dp))
                            .background(if (plainFields) c.surface else c.surfaceMuted).border(.7.dp, if (highlightError && error != null && (errorField(error.orEmpty()) == null || errorField(error.orEmpty()) == index)) c.bad else if (compactPills) Color.Transparent else ((if (error != null) errorFieldOutlineColor else null) ?: fieldOutlineColor ?: c.line), RoundedCornerShape(if (compactPills) 24.dp else 10.dp))
                            .padding(horizontal = 12.dp, vertical = (if (error == null && plainFields) normalPlainFieldVerticalPadding else null) ?: if (compactPills) 7.dp else if (configFooter) 10.dp else 9.dp),
                        decorationBox = { input -> Box {
                            if (values[index].value.isBlank() && field.placeholder.isNotBlank()) Text(field.placeholder, color = c.textFaint, fontSize = 15.sp)
                            input()
                        } },
                    )
                    if (error != null && errorField(error.orEmpty()) == index) Text(error.orEmpty(), color = c.bad,
                        style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
                }
                if (!message.isNullOrBlank() && messageBelowFields && !(hideMessageOnError && error != null)) {
                    Spacer(Modifier.height(if (compactPills) 3.dp else 6.dp)); Text(message, color = c.textMuted, fontSize = 12.sp, lineHeight = 16.sp)
                }
                AnimatedVisibility(error != null && errorField(error.orEmpty()) == null, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                    Text(error.orEmpty(), color = c.bad, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
                }
                if (!configFooter) {
                    Spacer(Modifier.height(14.dp))
                    HxDialogButtons(confirmLabel, confirmColorOverride ?: c.accent, referenceCorners = !compactPills, buttonHeight = if (compactPills) 36.dp else 46.dp, onDismiss = onDismiss, onConfirm = submit)
                }
            }
            if (configFooter) {
                HorizontalDivider(thickness = .5.dp, color = c.line)
                Row(Modifier.fillMaxWidth().height(58.dp)) {
                    Box(Modifier.weight(1f).fillMaxHeight().clickable(onClick = onDismiss), contentAlignment = Alignment.Center) {
                        Text(ht("取消"), color = c.textMuted, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    }
                    Box(Modifier.width(.5.dp).fillMaxHeight().background(c.line))
                    Box(Modifier.weight(1f).fillMaxHeight().clickable(onClick = submit), contentAlignment = Alignment.Center) {
                        Text(confirmLabel, color = c.accent, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

internal enum class HxConfirmStyle { Filled, Text, SoftRow, SoftStack, DestructiveStack }

@Composable
internal fun HxConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String = "确定",
    danger: Boolean = false,
    presentation: HxConfirmStyle = HxConfirmStyle.Filled,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val c = Hx.colors
    HxReferenceDialog(onDismiss = onDismiss, widthFraction = .75f) {
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(12.dp))
        Text(message, style = MaterialTheme.typography.bodyMedium, color = c.textMuted,
            textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(18.dp))
        if (presentation == HxConfirmStyle.Filled) {
            HxDialogButtons(confirmLabel, if (danger) Color(0xFFFF3B30) else c.accent, referenceCorners = true, onDismiss = onDismiss, onConfirm = onConfirm)
        } else {
            val stacked = presentation == HxConfirmStyle.SoftStack || presentation == HxConfirmStyle.DestructiveStack
            @Composable fun action(label: String, destructive: Boolean, modifier: Modifier, click: () -> Unit) {
                val strong = destructive && presentation == HxConfirmStyle.DestructiveStack
                val tint = if (destructive && danger) Color(0xFFFF3B30) else c.accent
                Box(modifier.height(44.dp).clip(RoundedCornerShape(12.dp))
                    .background(when { presentation == HxConfirmStyle.Text -> Color.Transparent; strong -> tint; destructive && danger -> tint.copy(alpha = .09f); else -> c.accentSoft.copy(alpha = .65f) })
                    .clickable(onClick = click), contentAlignment = Alignment.Center) {
                    Text(label, color = if (strong) Color.White else tint, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            if (stacked) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                action(ht("取消"), false, Modifier.fillMaxWidth(), onDismiss)
                action(confirmLabel, true, Modifier.fillMaxWidth(), onConfirm)
            } else Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                action(ht("取消"), false, Modifier.weight(1f), onDismiss)
                action(confirmLabel, true, Modifier.weight(1f), onConfirm)
            }
        }
    }
}

@Composable
internal fun HxReferenceDialog(onDismiss: () -> Unit, widthFraction: Float = .84f, contentPadding: Dp = 16.dp, content: @Composable ColumnScope.() -> Unit) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        val view = LocalView.current
        SideEffect { (view.parent as? DialogWindowProvider)?.window?.setDimAmount(.5f) }
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            // Dismissal is a sibling behind the card, never a clickable ancestor of
            // its heading/inputs (which would make accessibility activation dismiss).
            Box(Modifier.matchParentSize().clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss))
            Column(Modifier.fillMaxWidth(widthFraction).clip(RoundedCornerShape(22.dp)).background(Hx.colors.surface)
                .pointerInput(Unit) { detectTapGestures(onTap = {}) }.padding(contentPadding), content = content)
        }
    }
}

/** Primary filled button in the app's pill shape. */
@Composable
internal fun HxButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    busy: Boolean = false,
    tone: HxTone = HxTone.Accent,
    filled: Boolean = true,
    outlined: Boolean = false,
) {
    val c = Hx.colors
    val haptics = rememberHetuHaptics()
    val source = remember { MutableInteractionSource() }
    val rawBg = if (outlined) c.surface else if (filled) tone.fg() else tone.bg()
    val rawFg = if (outlined) tone.fg() else if (filled) (if (tone == HxTone.Accent) c.onAccent else Color.White) else tone.fg()
    val bg by animateColorAsState(if (enabled) rawBg else c.surfaceMuted, tween(HxMotion.Medium), label = "btnBg")
    val fg by animateColorAsState(if (enabled) rawFg else c.textFaint, tween(HxMotion.Medium), label = "btnFg")
    Row(
        modifier
            .hxPressScale(source, .97f)
            .heightIn(min = 44.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(bg)
            .then(if (outlined) Modifier.border(1.dp, fg, RoundedCornerShape(12.dp)) else Modifier)
            .clickable(interactionSource = source, indication = LocalIndication.current, enabled = enabled && !busy, onClick = { haptics.perform(HetuHaptic.Tap); onClick() })
            .animateContentSize(tween(HxMotion.Short, easing = HxMotion.Emphasized))
            .padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (busy) {
            HxSpinner(16.dp, fg)
            Spacer(Modifier.width(8.dp))
        } else if (icon != null) {
            Icon(icon, null, tint = fg, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge, color = fg, maxLines = 1)
    }
}

/** Small circular icon action used in top bars. */
@Composable
internal fun HxBarAction(icon: ImageVector, description: String, onClick: () -> Unit, busy: Boolean = false, enabled: Boolean = true, anchorMenu: Boolean = false) {
    val c = Hx.colors
    val haptics = rememberHetuHaptics()
    val tint by animateColorAsState(if (enabled) c.text else c.textFaint, tween(HxMotion.Medium), label = "barTint")
    IconButton(onClick = { haptics.perform(HetuHaptic.Tap); onClick() }, enabled = enabled && !busy,
        modifier = Modifier.size(44.dp).then(if (anchorMenu) Modifier.hxAnchorSource() else Modifier)) {
        // Icon swaps (search ↔ close search, idle ↔ busy) rotate/scale through instead of blinking.
        AnimatedContent(
            targetState = if (busy) null else icon,
            transitionSpec = {
                (fadeIn(tween(HxMotion.Short)) + scaleIn(HxMotion.pop(), initialScale = .5f))
                    .togetherWith(fadeOut(tween(90)) + scaleOut(tween(HxMotion.Short), targetScale = .5f))
            },
            contentAlignment = Alignment.Center,
            label = "barAction",
        ) { target ->
            if (target == null) HxSpinner(18.dp) else Icon(target, description, tint = tint)
        }
    }
}

/** Thin rounded progress bar with an animated fill. */
@Composable
internal fun HxProgressBar(ratio: Float, color: Color = Hx.colors.accent, modifier: Modifier = Modifier, height: Dp = 6.dp) {
    val animated by animateFloatAsState(ratio.coerceIn(0f, 1f), tween(HxMotion.Long, easing = HxMotion.Emphasized), label = "progress")
    Box(modifier.fillMaxWidth().height(height).clip(Hx.pillShape).background(Hx.colors.surfaceMuted)) {
        Box(Modifier.fillMaxWidth(animated).height(height).clip(Hx.pillShape).background(color))
    }
}

/**
 * Filled input used in dialogs and sheets: a soft tinted well, no underline, and an accent
 * ring that fades in while focused.
 */
@Composable
internal fun HxTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: (@Composable () -> Unit)? = null,
    placeholder: (@Composable () -> Unit)? = null,
    singleLine: Boolean = false,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    @Suppress("UNUSED_PARAMETER") shape: androidx.compose.ui.graphics.Shape? = null,
) {
    val c = Hx.colors
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val ring by animateColorAsState(if (focused) c.accent else Color.Transparent, tween(HxMotion.Medium), label = "fieldRing")
    val fieldShape = RoundedCornerShape(16.dp)
    androidx.compose.material3.TextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.border(1.5.dp, ring, fieldShape),
        label = label,
        placeholder = placeholder,
        singleLine = singleLine,
        maxLines = maxLines,
        keyboardOptions = keyboardOptions,
        interactionSource = source,
        shape = fieldShape,
        colors = androidx.compose.material3.TextFieldDefaults.colors(
            focusedContainerColor = c.surfaceMuted,
            unfocusedContainerColor = c.surfaceMuted,
            disabledContainerColor = c.surfaceMuted,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            disabledIndicatorColor = Color.Transparent,
            focusedLabelColor = c.accent,
            unfocusedLabelColor = c.textMuted,
            cursorColor = c.accent,
        ),
    )
}


/** Dialog footer: two equal pills — a quiet cancel and a solid confirm. */
@Composable
private fun HxDialogButtons(confirmLabel: String, confirmColor: Color, referenceCorners: Boolean = false, buttonHeight: Dp = 46.dp, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    val c = Hx.colors
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        val cancelSource = remember { MutableInteractionSource() }
        Box(
            Modifier
                .weight(1f)
                .hxPressScale(cancelSource, .96f)
                .height(buttonHeight)
                .clip(if (referenceCorners) RoundedCornerShape(14.dp) else Hx.pillShape)
                .background(c.surfaceMuted)
                .clickable(interactionSource = cancelSource, indication = LocalIndication.current, onClick = onDismiss),
            contentAlignment = Alignment.Center,
        ) {
            Text(ht("取消"), style = MaterialTheme.typography.labelLarge, color = c.text)
        }
        val okSource = remember { MutableInteractionSource() }
        Box(
            Modifier
                .weight(1f)
                .hxPressScale(okSource, .96f)
                .height(buttonHeight)
                .clip(if (referenceCorners) RoundedCornerShape(14.dp) else Hx.pillShape)
                .background(confirmColor)
                .clickable(interactionSource = okSource, indication = LocalIndication.current, onClick = onConfirm),
            contentAlignment = Alignment.Center,
        ) {
            Text(confirmLabel, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = if (confirmColor == c.accent) c.onAccent else Color.White)
        }
    }
}
