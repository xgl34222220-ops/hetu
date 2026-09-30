package io.github.xgl34222220.hetu

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import dev.chrisbanes.haze.materials.HazeMaterials
import dev.chrisbanes.haze.rememberHazeState
import io.github.xgl34222220.hetu.ui.HetuHaptic
import io.github.xgl34222220.hetu.ui.rememberHetuHaptics
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

internal data class HxPageTab(val key: String, val label: String)

/** Per-page bar actions, overlays and subtitles registered by embedded [HxPage]s. */
@Stable
internal class HxEmbedHost {
    val leadingActions = mutableStateMapOf<String, @Composable RowScope.() -> Unit>()
    val actions = mutableStateMapOf<String, @Composable RowScope.() -> Unit>()
    val overlays = mutableStateMapOf<String, @Composable BoxScope.() -> Unit>()
    val subtitles = mutableStateMapOf<String, String?>()
}

/** Present when an [HxPage] is rendered as one section of an [HxTabbedPage]. */
internal class HxEmbed(
    val key: String,
    val listState: LazyListState,
    val top: Dp,
    val bottom: Dp,
    val active: Boolean,
    val host: HxEmbedHost,
)

internal val LocalHxEmbed = compositionLocalOf<HxEmbed?> { null }

/**
 * A page with a large title, a row of chip tabs and horizontally swipeable sections.
 *
 * Scrolling a section up first folds the large title away (the chips stay pinned under
 * the bar and a small centred title fades in); scrolling back to the top unfolds it.
 * Chips and pages stay in sync while dragging, so the highlight slides with the finger.
 */
@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
internal fun HxTabbedPage(
    title: String,
    tabs: List<HxPageTab>,
    selected: String,
    onSelect: (String) -> Unit,
    subtitle: String? = null,
    showSubtitle: Boolean = true,
    minimalHeader: Boolean = false,
    panelReferenceStyle: Boolean = false,
    bottomPadding: Dp = 0.dp,
    scrollToTopSignal: Int = 0,
    actions: @Composable RowScope.() -> Unit = {},
    overlay: @Composable BoxScope.() -> Unit = {},
    /** Whole-screen sections: each is an [HxPage] that renders embedded (bar-less) inside the pager. */
    pageComposable: (@Composable (String) -> Unit)? = null,
    page: LazyListScope.(String) -> Unit = {},
) {
    val host = remember { HxEmbedHost() }
    val shownSubtitle = if (showSubtitle) (host.subtitles[selected] ?: subtitle) else null
    val stackedActions = panelReferenceStyle && LocalDensity.current.fontScale > 1.3f
    val topBarHeight = if (stackedActions) (48f * LocalDensity.current.fontScale + 48f).dp else if (panelReferenceStyle) 68.dp else if (minimalHeader) 48.dp else HxTopBarHeight
    val c = Hx.colors
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val navInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val listBottom = (if (bottomPadding > 0.dp) bottomPadding else navInset) + 24.dp
    val tabsHeight = if (panelReferenceStyle) maxOf(52f, 20f * density.fontScale + 20f).dp else 42.dp
    val headerOpenHeight = when {
        panelReferenceStyle -> 0.dp
        minimalHeader && shownSubtitle.isNullOrBlank() -> 52.dp
        minimalHeader -> 70.dp
        shownSubtitle.isNullOrBlank() -> 66.dp
        else -> 86.dp
    }
    val headerHeightPx = with(density) { headerOpenHeight.toPx() }

    val initialIndex = remember { tabs.indexOfFirst { it.key == selected }.coerceAtLeast(0) }
    val pagerState = rememberPagerState(initialPage = initialIndex) { tabs.size }
    val listStates = remember(tabs.size) { tabs.associate { it.key to LazyListState() } }

    // Header fold: 0 = fully open, headerHeightPx = folded away. While it folds the
    // pager remains full-screen; only its top content inset shrinks. Once folded, rows
    // keep scrolling behind the pinned glass top bar and tabs instead of being clipped
    // below an opaque toolbar.
    var fold by remember { mutableFloatStateOf(0f) }
    val foldConnection = remember(headerHeightPx) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (available.y >= 0f) return Offset.Zero
                val next = (fold - available.y).coerceIn(0f, headerHeightPx)
                val used = next - fold
                fold = next
                return Offset(0f, -used)
            }

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (available.y <= 0f) return Offset.Zero
                val next = (fold - available.y).coerceIn(0f, headerHeightPx)
                val used = fold - next
                fold = next
                return Offset(0f, used)
            }
        }
    }

    // Selection → pager (chip taps, external changes) and pager → selection (swipes).
    LaunchedEffect(selected) {
        val index = tabs.indexOfFirst { it.key == selected }
        if (index >= 0 && index != pagerState.targetPage) pagerState.animateScrollToPage(index)
    }
    val currentSelected by rememberUpdatedState(selected)
    val currentOnSelect by rememberUpdatedState(onSelect)
    LaunchedEffect(pagerState) {
        var firstEmission = true
        snapshotFlow { pagerState.settledPage }.collect { index ->
            if (firstEmission) {
                firstEmission = false
                return@collect
            }
            tabs.getOrNull(index)?.let { if (it.key != currentSelected) currentOnSelect(it.key) }
        }
    }
    val initialSignal = remember { scrollToTopSignal }
    LaunchedEffect(scrollToTopSignal) {
        if (scrollToTopSignal != initialSignal) {
            listStates[tabs[pagerState.currentPage].key]?.animateScrollToItem(0)
            animate(fold, 0f, animationSpec = tween(HxMotion.Medium, easing = HxMotion.Emphasized)) { v, _ -> fold = v }
        }
    }

    val collapse = if (panelReferenceStyle) { if (listStates[selected]?.let { it.firstVisibleItemIndex > 0 || it.firstVisibleItemScrollOffset > 0 } == true) 1f else 0f } else if (headerHeightPx > 0f) (fold / headerHeightPx).coerceIn(0f, 1f) else 0f
    val visibleHeader = with(density) { (headerHeightPx - fold).coerceAtLeast(0f).toDp() }
    val contentTop = statusTop + topBarHeight + tabsHeight + visibleHeader + 2.dp
    val pageCanvas = c.canvas
    val blur = LocalHxBlur.current && androidx.compose.ui.platform.LocalView.current.isHardwareAccelerated
    val pageHaze = rememberHazeState()

    Box(Modifier.fillMaxSize().background(pageCanvas).testTag("hetu-panel")) {
        // Full-screen scrolling source. Content insets track the collapsing header, so
        // after collapse the list naturally passes underneath the pinned glass chrome.
        Box(Modifier.fillMaxSize().hazeSource(pageHaze)) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize().nestedScroll(foldConnection),
                beyondViewportPageCount = 1,
                key = { tabs[it].key },
            ) { index ->
                val key = tabs[index].key
                if (pageComposable != null) {
                    val embed = HxEmbed(key, listStates.getValue(key), contentTop, listBottom, pagerState.currentPage == index, host)
                    CompositionLocalProvider(LocalHxEmbed provides embed) { pageComposable(key) }
                    return@HorizontalPager
                }
                LazyColumn(
                    state = listStates.getValue(key),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(top = contentTop, bottom = listBottom),
                ) {
                    page(key)
                }
            }
        }

        // Top bar glass. It is nearly invisible while expanded and gains blur/tint as
        // the title folds, matching the reference where cards remain visible beneath it.
        Box(
            Modifier
                .fillMaxWidth()
                .height(statusTop + topBarHeight)
                .graphicsLayer { alpha = collapse }
                .hxHardwareEffectFallback(pageCanvas)
                .then(
                    if (blur) Modifier.hazeEffect(state = pageHaze, style = HazeMaterials.ultraThin()) {
                        blurRadius = 30.dp
                        noiseFactor = .008f
                    } else Modifier,
                )
                .background(
                    if (blur) Brush.verticalGradient(
                        listOf(
                            pageCanvas.copy(alpha = .48f),
                            pageCanvas.copy(alpha = .28f),
                            pageCanvas.copy(alpha = .12f),
                        ),
                    ) else Brush.verticalGradient(listOf(pageCanvas, pageCanvas)),
                ),
        )

        // Compact title/actions stay pinned above the glass.
        Box(
            Modifier
                .fillMaxWidth()
                .padding(top = statusTop, start = if (panelReferenceStyle) 14.dp else 4.dp, end = if (panelReferenceStyle) 14.dp else 4.dp)
                .height(topBarHeight),
        ) {
            Text(
                title,
                modifier = Modifier
                    .align(if (stackedActions) Alignment.TopStart else if (panelReferenceStyle) Alignment.CenterStart else Alignment.Center)
                    .padding(horizontal = if (panelReferenceStyle) 2.dp else 96.dp)
                    .graphicsLayer {
                        alpha = if (panelReferenceStyle) 1f else ((collapse - .45f) / .55f).coerceIn(0f, 1f)
                        translationY = (1f - alpha) * 6.dp.toPx()
                    },
                style = if (panelReferenceStyle) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = c.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
            if (!panelReferenceStyle) {
                Row(Modifier.align(Alignment.CenterStart), verticalAlignment = Alignment.CenterVertically) {
                    host.leadingActions[selected]?.invoke(this)
                }
            }
            Row(Modifier.align(if (stackedActions) Alignment.BottomEnd else Alignment.CenterEnd), verticalAlignment = Alignment.CenterVertically) {
                if (panelReferenceStyle) host.leadingActions[selected]?.invoke(this)
                host.actions[selected]?.invoke(this)
                actions()
            }
        }

        // Large title occupies only the space that remains after the fold. It is an
        // overlay, not a solid block, so the pager stays continuous underneath.
        Box(
            Modifier
                .offset(y = statusTop + topBarHeight)
                .fillMaxWidth()
                .height(visibleHeader)
                .clipToBounds(),
        ) {
            Column(
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .padding(start = if (panelReferenceStyle) 26.dp else Hx.gutter + 2.dp, end = Hx.gutter, bottom = if (minimalHeader) 6.dp else 12.dp)
                    .graphicsLayer {
                        alpha = 1f - collapse
                        val s = 1f - .06f * collapse
                        scaleX = s
                        scaleY = s
                        transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 1f)
                    },
            ) {
                Text(
                    title,
                    style = MaterialTheme.typography.headlineMedium.copy(fontSize = if (panelReferenceStyle) 36.sp else 32.sp, lineHeight = if (panelReferenceStyle) 44.sp else MaterialTheme.typography.headlineMedium.lineHeight, letterSpacing = (-0.6).sp),
                    fontWeight = FontWeight.Bold,
                    color = c.text,
                    maxLines = 1,
                )
                if (!shownSubtitle.isNullOrBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(shownSubtitle, style = MaterialTheme.typography.bodyMedium, color = c.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }

        // Pinned tabs become their own thin glass strip as the title disappears. When
        // expanded the strip is transparent, so the page still has the clean open look.
        Box(
            Modifier
                .offset(y = statusTop + topBarHeight + visibleHeader)
                .fillMaxWidth()
                .height(tabsHeight),
        ) {
            // Important: keep the expanded tab rail truly transparent. Applying a Haze
            // material directly to this entire Box creates a white slab even at alpha=0.
            // Only the background glass layer fades in as the large header collapses.
            Box(
                Modifier
                    .matchParentSize()
                    .graphicsLayer { alpha = collapse }
                    .hxHardwareEffectFallback(pageCanvas)
                    .then(
                        if (blur) Modifier.hazeEffect(state = pageHaze, style = HazeMaterials.ultraThin()) {
                            blurRadius = 28.dp
                            noiseFactor = .006f
                        } else Modifier,
                    )
                    .background(pageCanvas.copy(alpha = if (blur) .20f else 1f)),
            )
            HxChipTabs(
                tabs = tabs,
                referenceStyle = panelReferenceStyle,
                pagerState = pagerState,
                onSelect = { index ->
                    val key = tabs[index].key
                    if (key != selected) onSelect(key) else scope.launch { listStates[key]?.animateScrollToItem(0) }
                },
            )
            HorizontalDivider(
                modifier = Modifier.align(Alignment.BottomCenter).graphicsLayer { alpha = collapse * .55f },
                thickness = 0.5.dp,
                color = c.line.copy(alpha = .65f),
            )
        }

        host.overlays[selected]?.invoke(this)
        overlay()
    }
}

/**
 * Horizontally scrolling chip tabs. The white "selected" fill is interpolated from the
 * pager position, so it glides between chips during a swipe instead of jumping.
 */
@Composable
internal fun HxChipTabs(tabs: List<HxPageTab>, pagerState: PagerState, referenceStyle: Boolean = false, onSelect: (Int) -> Unit) {
    val c = Hx.colors
    val haptics = rememberHetuHaptics()
    val scroll = rememberScrollState()
    val density = LocalDensity.current
    val lefts = remember(tabs.size) { IntArray(tabs.size) }
    val position = pagerState.currentPage + pagerState.currentPageOffsetFraction
    val refOutline = c.surface.copy(alpha = .65f)
    val refSelected = c.surface
    LaunchedEffect(pagerState.targetPage) {
        val left = lefts.getOrNull(pagerState.targetPage) ?: return@LaunchedEffect
        val margin = with(density) { 56.dp.toPx() }.toInt()
        scroll.animateScrollTo((left - margin).coerceAtLeast(0))
    }
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(scroll)
            .padding(horizontal = if (referenceStyle) 12.dp else Hx.gutter)
            .padding(bottom = if (referenceStyle) 4.dp else 10.dp),
        horizontalArrangement = Arrangement.spacedBy(if (referenceStyle) 5.dp else 8.dp),
    ) {
        tabs.forEachIndexed { index, tab ->
            val sel = (1f - abs(position - index)).coerceIn(0f, 1f)
            val source = remember { MutableInteractionSource() }
            Box(
                Modifier
                    .onPlaced { lefts[index] = it.positionInParent().x.roundToInt() }
                    .hxPressScale(source, .93f)
                    .testTag("panel-tab-${tab.key}")
                    .semantics { selected = pagerState.targetPage == index }
                    .heightIn(min = if (referenceStyle) 40.dp else 38.dp)
                    .clip(if (referenceStyle) RoundedCornerShape(14.dp) else Hx.pillShape)
                    .background(if (referenceStyle) lerp(c.surface.copy(alpha = .52f), refSelected, sel) else lerp(Color.Transparent, c.surface, sel))
                    .border(
                        .8.dp,
                        if (referenceStyle) lerp(refOutline, c.line, sel) else lerp(c.line, Color.Transparent, sel),
                        if (referenceStyle) RoundedCornerShape(14.dp) else Hx.pillShape,
                    )
                    .clickable(interactionSource = source, indication = null) {
                        haptics.perform(HetuHaptic.Tick)
                        onSelect(index)
                    }
                    .padding(horizontal = if (referenceStyle) 11.dp else 17.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    tab.label,
                    style = MaterialTheme.typography.labelLarge.copy(fontSize = if (referenceStyle) 12.sp else 15.sp),
                    fontWeight = if (sel > .5f) FontWeight.SemiBold else FontWeight.Medium,
                    color = if (referenceStyle) lerp(c.textMuted, c.accent, sel) else lerp(c.textMuted, c.text, sel),
                    maxLines = 1,
                )
            }
        }
    }
}
