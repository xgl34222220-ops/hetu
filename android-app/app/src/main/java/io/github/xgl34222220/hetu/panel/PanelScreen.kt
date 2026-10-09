package io.github.xgl34222220.hetu.panel

import io.github.xgl34222220.hetu.home.homeGlassPanel
import io.github.xgl34222220.hetu.home.homeDiffuseCanvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.ScrollableDefaults
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.gestures.stopScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.xgl34222220.hetu.home.HomeBarBackdrop
import io.github.xgl34222220.hetu.home.HomeBarGlass
import io.github.xgl34222220.hetu.home.HomeButton
import io.github.xgl34222220.hetu.home.HomeButtonKind
import io.github.xgl34222220.hetu.home.HomeDims
import io.github.xgl34222220.hetu.home.HomeIconButton
import io.github.xgl34222220.hetu.home.HomeIcons
import io.github.xgl34222220.hetu.home.HomeModalSheet
import io.github.xgl34222220.hetu.home.HomePop
import io.github.xgl34222220.hetu.home.HomeRefreshBox
import io.github.xgl34222220.hetu.home.HomeSpinner
import io.github.xgl34222220.hetu.home.HomeType
import io.github.xgl34222220.hetu.home.LocalHomeColors
import io.github.xgl34222220.hetu.home.LocalHomeMotionEnabled
import io.github.xgl34222220.hetu.home.homeGlassSource
import io.github.xgl34222220.hetu.home.rememberHomeBarGlass
import io.github.xgl34222220.hetu.home.rememberHomeStagger
import io.github.xgl34222220.hetu.ui.hetuAnimateItem
import io.github.xgl34222220.hetu.ui.ht
import kotlinx.coroutines.launch

/** Lazy-list items that precede the tab content: title space, tab-strip space, and the search field while it is open. */
internal fun panelHeaderItemCount(data: PanelData, view: PanelViewState): Int =
    2 + if (data.running && view.searching && view.tab.searchable) 1 else 0

/**
 * 面板 (tab root). Stateless: [data] is what the core reports, [view] is what the user chose,
 * [overlay] is the one floating layer that is open.
 *
 * Layout: a single LazyColumn under a pinned header. At rest the header is the concept's stack:
 * action row, large title, tab strip. Scrolling slides the large title away beneath the action
 * row while the tab strip rides up and stays pinned, and the pinned part turns to glass.
 *
 * @param popups true at runtime (menus, sheets and the dialog open in their own windows).
 *        Previews pass false and get the same content drawn inline, since popup windows do not
 *        render in a static preview.
 * @param contentPadding bottom padding must clear the floating dock.
 */
@Composable
internal fun PanelScreen(
    data: PanelData,
    view: PanelViewState,
    overlay: PanelOverlay?,
    actions: PanelActions,
    onView: (PanelViewState) -> Unit,
    onOverlay: (PanelOverlay?) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(bottom = HomeDims.dockClearance),
    listState: LazyListState = rememberLazyListState(),
    popups: Boolean = true,
) {
    val c = LocalHomeColors.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val bottomPad = contentPadding.calculateBottomPadding()
    // The title block follows the font scale, so a large system font never spills into the tabs.
    val titleHeight = with(density) { HomeType.largeTitle.lineHeight.toDp() } + 8.dp
    val titlePx = with(density) { titleHeight.toPx() }
    val collapse = remember(listState, titlePx) {
        derivedStateOf { if (listState.firstVisibleItemIndex > 0) titlePx else listState.firstVisibleItemScrollOffset.toFloat().coerceAtMost(titlePx) }
    }
    val collapsed by remember(collapse, titlePx) { derivedStateOf { collapse.value >= titlePx - 1f } }
    val glass = rememberHomeBarGlass(collapsed)
    val running = data.running
    val configuration = LocalConfiguration.current
    val effectiveLayout = PanelLogic.layoutForViewport(view.layout, configuration.screenWidthDp, density.fontScale)
    val displayView = view.copy(layout = effectiveLayout)
    val motion = LocalHomeMotionEnabled.current
    val rows = remember(data.groups, data.delays, data.globalMode, displayView, running) { if (running && view.tab == PanelTab.Groups) PanelLogic.groupRows(data, displayView) else emptyList() }
    val headerItems = panelHeaderItemCount(data, view)
    // A fresh cascade for every tab: its first screenful of cards rises in, later ones just appear.
    val stagger = key(view.tab) { rememberHomeStagger() }
    val closeOverlay = { onOverlay(null) }
    val menu: @Composable (PanelOverlay) -> Unit = { target ->
        if (popups) PanelDropdown(expanded = overlay == target, onDismiss = closeOverlay) { PanelMenuContent(target, view, onView, onOverlay) }
    }

    HomeRefreshBox(
        refreshing = data.refreshing,
        onRefresh = actions.onRefresh,
        label = "刷新${view.tab.label}",
        modifier = modifier.fillMaxSize().homeDiffuseCanvas(),
        indicatorPadding = PaddingValues(top = statusTop + PanelDims.barHeight + 24.dp),
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().homeGlassSource(glass),
            state = listState,
            contentPadding = PaddingValues(top = statusTop + PanelDims.barHeight, bottom = bottomPad),
        ) {
            // The header itself is drawn above the list; these two items only reserve its room.
            item(key = "panel-title") { Spacer(Modifier.height(titleHeight)) }
            item(key = "panel-tabs") { Spacer(Modifier.height(PanelDims.tabBlock)) }
            if (!running) {
                item(key = "panel-stopped") {
                    PanelEmptyState(PanelIcons.ServerOff, "代理未运行", "启动代理后可查看策略与节点") {
                        HomeButton("启动代理", actions.onStart, kind = HomeButtonKind.Primary, icon = PanelIcons.Power, loading = data.status == PanelStatus.Starting)
                    }
                }
                return@LazyColumn
            }
            if (data.readError.isNotBlank()) {
                item(key = "panel-read-error") {
                    PanelEmptyState(PanelIcons.CircleX, "无法读取面板", data.readError, verbatimSubtitle = true) {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            HomeButton("重试", actions.onRefresh, loading = data.refreshing)
                            HomeButton("API 设置", { onOverlay(PanelOverlay.ApiReadErrorSheet) }, kind = HomeButtonKind.Primary)
                        }
                    }
                }
                return@LazyColumn
            }
            if (view.searching && view.tab.searchable) item(key = "panel-search") {
                PanelSearchField(
                    view.query, { onView(view.copy(query = it)) }, view.tab.searchHint.orEmpty(),
                    Modifier.panelGutter().padding(bottom = PanelDims.gap).then(hetuAnimateItem(motion)), autoFocus = popups,
                )
            }
            // Only the tabs fed by the controller snapshot wait for it; rules, rule sets, subscriptions and logs load on their own.
            if (data.loading && (view.tab == PanelTab.Groups || view.tab == PanelTab.Overview || view.tab == PanelTab.Connections)) {
                panelLoadingTab(if (view.tab == PanelTab.Groups) effectiveLayout.groupColumns else 1)
                return@LazyColumn
            }
            when (view.tab) {
                PanelTab.Groups -> panelGroupsTab(
                    rows = rows, data = data, view = displayView, stagger = stagger,
                    onToggleGroup = { name ->
                        // Capture this card row before an accordion removes rows above it. The
                        // stable lazy key matches panelGroupsTab; an old numeric index does not.
                        val cardRow = rows.firstOrNull { it is PanelGroupRow.Cards && it.groups.any { group -> group.name == name } } as? PanelGroupRow.Cards
                        val anchor = cardRow?.let { row -> listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == "g:" + row.groups.first().name } }
                        val next = view.toggleGroup(name)
                        val at = PanelLogic.groupRows(data, next.copy(layout = effectiveLayout)).indexOfFirst { it is PanelGroupRow.Cards && it.groups.any { g -> g.name == name } }
                        onView(next)
                        if (anchor != null && at >= 0) {
                            // Apply with the new rows on the next measure, retaining the tapped
                            // header's visible offset instead of moving it to the top of the page.
                            listState.requestScrollToItem(headerItems + at, -anchor.offset)
                        }
                    },
                    onSelectNode = actions.onSelectNode,
                    onTestNode = actions.onTestNode,
                    onTestGroup = actions.onTestGroup,
                    onNodeInfo = { onOverlay(PanelOverlay.NodeInfo(it)) },
                )
                PanelTab.Overview -> panelOverviewTab(
                    overview = data.overview, view = view, stagger = stagger, onView = onView,
                    onOpenSubscriptions = { onView(view.withTab(PanelTab.Subscriptions)) },
                    onRankCountMenu = { onOverlay(PanelOverlay.RankCountMenu) },
                    rankMenu = { menu(PanelOverlay.RankCountMenu) },
                )
                PanelTab.Subscriptions -> panelSubscriptionsTab(PanelLogic.subscriptions(data, view), view.needle, stagger, actions.onUpdateSubscription)
                PanelTab.Connections -> panelConnectionsTab(data, view, stagger, onView, onOpen = { onOverlay(PanelOverlay.ConnectionDetail(it)) }, onCloseAll = { onOverlay(PanelOverlay.CloseAllDialog) })
                PanelTab.Rules -> panelRulesTab(PanelLogic.rules(data, view), view.needle, data.ruleTotal, stagger)
                PanelTab.RuleSets -> panelRuleSetsTab(PanelLogic.ruleSets(data, view), view.needle, stagger, actions.onUpdateRuleSet)
                PanelTab.Logs -> panelLogsTab(PanelLogic.logs(data, view), view, stagger, onView, onOrderMenu = { onOverlay(PanelOverlay.LogOrderMenu) }, orderMenu = { menu(PanelOverlay.LogOrderMenu) })
            }
        }

        PanelHeader(data, view, overlay, actions, glass, collapse, titleHeight, statusTop, listState, onView, onOverlay, menu)

        // 策略: once a group is open, “定位当前节点” and a collapse capsule float above the dock.
        val openGroup = view.expandedGroups.lastOrNull()?.takeIf { running && data.readError.isBlank() && !data.loading && view.tab == PanelTab.Groups }
        var lastOpen by remember { mutableStateOf("") }
        SideEffect { if (openGroup != null) lastOpen = openGroup }
        val shownGroup = openGroup ?: lastOpen
        Column(
            Modifier.align(Alignment.BottomEnd).padding(end = HomeDims.gutter, bottom = bottomPad.coerceAtLeast(16.dp)),
            horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            HomePop(openGroup != null) {
                PanelFab(PanelIcons.LocateFixed, "定位当前节点", {
                    val now = data.groups.firstOrNull { it.name == shownGroup }?.now
                    val at = rows.indexOfFirst { it is PanelGroupRow.Nodes && it.group.name == shownGroup && it.nodes.any { n -> n.name == now } }
                    if (at >= 0) scope.launch {
                        if (motion) listState.animateScrollToItem(headerItems + at) else listState.scrollToItem(headerItems + at)
                    }
                })
            }
            HomePop(openGroup != null) {
                PanelFab(PanelIcons.ChevronDown, "收起节点", { if (shownGroup in view.expandedGroups) onView(view.toggleGroup(shownGroup)) }, text = shownGroup)
            }
        }

        if (overlay != null) {
            if (popups) PanelPopups(overlay, data, view, actions, onView, onOverlay)
            else PanelInlineOverlay(overlay, data, view, actions, onView, onOverlay, statusTop)
        }
    }
}

/* ------------------------------------------------------------------ */
/*  Pinned header                                                       */
/* ------------------------------------------------------------------ */

/**
 * Action row, large title and tab strip, drawn above the list.
 *
 * Actions per tab, as in the concept: finding things on the left, arranging and refreshing on the right.
 * 策略: 搜索 · 筛选(menu) ‖ 排序与布局(sheet) ｜ 概览: 排行方式(menu) ｜ 订阅: 搜索 ‖ 全部更新
 * 连接: 搜索 · 筛选(menu) ‖ 排序(menu) · 更多(menu) ｜ 规则: 搜索 ‖ 刷新 ｜ 规则集: 搜索 ‖ 全部更新 ｜ 日志: 搜索 ‖ 刷新
 * No actions while the proxy is not running or the controller cannot be read.
 */
@Composable
private fun BoxScope.PanelHeader(
    data: PanelData,
    view: PanelViewState,
    overlay: PanelOverlay?,
    actions: PanelActions,
    glass: HomeBarGlass,
    collapse: State<Float>,
    titleHeight: Dp,
    statusTop: Dp,
    listState: LazyListState,
    onView: (PanelViewState) -> Unit,
    onOverlay: (PanelOverlay?) -> Unit,
    menu: @Composable (PanelOverlay) -> Unit,
) {
    val c = LocalHomeColors.current
    val scope = rememberCoroutineScope()
    val fling = ScrollableDefaults.flingBehavior()
    val titlePx = with(LocalDensity.current) { titleHeight.toPx() }
    val barBottom = statusTop + PanelDims.barHeight
    val tab = view.tab

    HomeBarBackdrop(glass, Modifier.fillMaxWidth().height(barBottom + PanelDims.tabBlock))

    // Large title: leaves upwards beneath the action row and is gone before it gets there.
    Box(
        Modifier.padding(start = 30.dp, top = barBottom).height(titleHeight).graphicsLayer {
            val moved = collapse.value
            translationY = -moved
            alpha = (1f - moved / (titlePx * .62f)).coerceIn(0f, 1f)
        },
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(ht("面板"), color = c.t1, style = HomeType.largeTitle, maxLines = 1)
            // Restored cards are display-only until the first fresh read lands; say so quietly.
            HomePop(data.running && data.syncing) { PanelSyncChip() }
        }
    }

    // The strip floats above the list, so a vertical drag that starts on it is handed to the
    // list by hand; horizontal drags still scroll the strip itself.
    val pageDrag = rememberDraggableState { delta -> listState.dispatchRawDelta(-delta) }
    Box(
        Modifier
            .padding(top = barBottom + titleHeight + 8.dp)
            .graphicsLayer { translationY = -collapse.value }
            .draggable(
                state = pageDrag,
                orientation = Orientation.Vertical,
                onDragStarted = { listState.stopScroll() },
                onDragStopped = { velocity -> scope.launch { listState.scroll { with(fling) { performFling(-velocity) } } } },
            ),
    ) { PanelTabStrip(tab, { onView(view.withTab(it)) }) }

    Box(Modifier.fillMaxWidth().padding(top = statusTop).height(PanelDims.barHeight)) {
        Text(
            ht("面板"),
            Modifier.align(Alignment.Center).graphicsLayer {
                val shown = ((collapse.value / titlePx - .55f) / .45f).coerceIn(0f, 1f)
                alpha = shown
                translationY = (1f - shown) * 8.dp.toPx()
            },
            color = c.t1, style = HomeType.barTitle, maxLines = 1,
        )
        if (data.running && data.readError.isBlank()) {
            val anchored: @Composable (ImageVector, String, PanelOverlay, Color) -> Unit = { icon, label, target, tint ->
                Box {
                    HomeIconButton(icon, label, { onOverlay(if (overlay == target) null else target) }, tint = tint, glyph = 26.dp)
                    menu(target)
                }
            }
            Row(Modifier.align(Alignment.CenterStart).padding(start = 15.dp), verticalAlignment = Alignment.CenterVertically) {
                if (tab.searchable) HomeIconButton(
                    if (view.searching) HomeIcons.X else PanelIcons.Search, if (view.searching) "关闭搜索" else "搜索", { onView(view.toggleSearch()) }, glyph = 26.dp,
                )
                when (tab) {
                    PanelTab.Groups -> {
                        val d = view.display
                        val custom = d.showHidden || !d.globalByMode || d.groupByProvider || !d.collapsePrevious || d.disconnectOnSelect
                        anchored(PanelIcons.Funnel, "筛选", PanelOverlay.GroupFilterMenu, if (custom) c.accent else c.t1)
                    }
                    PanelTab.Overview -> anchored(PanelIcons.ArrowDownWideNarrow, "排行方式", PanelOverlay.RankModeMenu, c.t1)
                    PanelTab.Connections -> anchored(PanelIcons.Funnel, "连接筛选", PanelOverlay.ConnFilterMenu, if (view.connFilter != PanelConnFilter.All) c.accent else c.t1)
                    else -> Unit
                }
            }
            Row(Modifier.align(Alignment.CenterEnd).padding(end = 15.dp), verticalAlignment = Alignment.CenterVertically) {
                when (tab) {
                    PanelTab.Groups -> HomeIconButton(PanelIcons.ArrowDownWideNarrow, "排序与布局", { onOverlay(PanelOverlay.LayoutSheet) }, glyph = 26.dp)
                    PanelTab.Overview -> Unit
                    PanelTab.Subscriptions -> HomeIconButton(
                        HomeIcons.RefreshCw, "全部更新", actions.onUpdateAllSubscriptions,
                        spinning = data.updatingAllSubscriptions || data.subscriptions.any { it.update == PanelUpdate.Updating }, glyph = 26.dp,
                    )
                    PanelTab.Connections -> {
                        anchored(PanelIcons.ArrowDownWideNarrow, "连接排序", PanelOverlay.ConnSortMenu, c.t1)
                        anchored(PanelIcons.EllipsisVertical, "连接显示", PanelOverlay.ConnMoreMenu, if (view.groupByApp) c.accent else c.t1)
                    }
                    PanelTab.Rules -> HomeIconButton(HomeIcons.RefreshCw, "刷新", actions.onRefreshRules, spinning = data.refreshing, glyph = 26.dp)
                    PanelTab.RuleSets -> HomeIconButton(
                        PanelIcons.Download, "全部更新", actions.onUpdateAllRuleSets,
                        loading = data.updatingAllRuleSets || (data.ruleSets.isNotEmpty() && data.ruleSets.all { it.update == PanelUpdate.Updating }), glyph = 26.dp,
                    )
                    PanelTab.Logs -> HomeIconButton(HomeIcons.RefreshCw, "刷新", actions.onRefreshLogs, spinning = data.refreshing, glyph = 26.dp)
                }
            }
        }
    }
}

/* ------------------------------------------------------------------ */
/*  Floating layers                                                     */
/* ------------------------------------------------------------------ */

/** Runtime: sheets and the dialog in their own windows. Menus are opened next to their anchors. */
@Composable
private fun PanelPopups(
    overlay: PanelOverlay,
    data: PanelData,
    view: PanelViewState,
    actions: PanelActions,
    onView: (PanelViewState) -> Unit,
    onOverlay: (PanelOverlay?) -> Unit,
) {
    when {
        overlay.isSheet -> HomeModalSheet(onDismiss = { onOverlay(null) }) { PanelSheetBody(overlay, data, view, actions, onView, onOverlay) }
        overlay == PanelOverlay.CloseAllDialog -> PanelDialog(onDismiss = { onOverlay(null) }) {
            PanelCloseAllDialogCard(onConfirm = { actions.onCloseAllConnections(); onOverlay(null) }, onDismiss = { onOverlay(null) })
        }
    }
}

/** Preview-only: the same layers drawn inside the page. Menu positions are approximate. */
@Composable
private fun PanelInlineOverlay(
    overlay: PanelOverlay,
    data: PanelData,
    view: PanelViewState,
    actions: PanelActions,
    onView: (PanelViewState) -> Unit,
    onOverlay: (PanelOverlay?) -> Unit,
    statusTop: Dp,
) {
    val c = LocalHomeColors.current
    Box(Modifier.fillMaxSize()) {
        when {
            overlay.isMenu -> {
                val inList = overlay == PanelOverlay.RankCountMenu || overlay == PanelOverlay.LogOrderMenu
                val leading = overlay == PanelOverlay.GroupFilterMenu || overlay == PanelOverlay.RankModeMenu || overlay == PanelOverlay.ConnFilterMenu
                PanelInlineMenu(
                    Modifier.align(if (leading) Alignment.TopStart else Alignment.TopEnd).padding(
                        top = statusTop + if (overlay == PanelOverlay.RankCountMenu) 330.dp else if (inList) 230.dp else PanelDims.barHeight,
                        start = 16.dp, end = 16.dp,
                    ),
                ) { PanelMenuContent(overlay, view, onView, onOverlay) }
            }
            overlay.isSheet -> {
                Box(Modifier.fillMaxSize().background(c.scrim).clickable { onOverlay(null) })
                Box(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth().homeGlassPanel(HomeDims.sheetShape, c.raised, raised = true),
                ) { PanelSheetBody(overlay, data, view, actions, onView, onOverlay) }
            }
            overlay == PanelOverlay.CloseAllDialog -> {
                Box(Modifier.fillMaxSize().background(c.scrim))
                PanelCloseAllDialogCard(onConfirm = {}, onDismiss = {}, modifier = Modifier.align(Alignment.Center))
            }
        }
    }
}

/** Quiet “同步中” capsule next to the large title while restored cards await a fresh controller read. */
@Composable
private fun PanelSyncChip() {
    val c = LocalHomeColors.current
    val label = ht("正在同步核心最新状态")
    Row(
        Modifier.clip(HomeDims.pillShape).background(c.accentSoft).padding(horizontal = 10.dp, vertical = 4.dp)
            .semantics(mergeDescendants = true) { contentDescription = label; liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        HomeSpinner(size = 12.dp, color = c.accent, strokeWidth = 1.6.dp)
        Text(ht("同步中"), color = c.accent, style = HomeType.note, maxLines = 1)
    }
}
