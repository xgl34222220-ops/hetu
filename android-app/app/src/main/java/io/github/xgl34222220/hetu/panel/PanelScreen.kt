package io.github.xgl34222220.hetu.panel

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.xgl34222220.hetu.home.HomeButton
import io.github.xgl34222220.hetu.home.HomeButtonKind
import io.github.xgl34222220.hetu.home.HomeDims
import io.github.xgl34222220.hetu.home.HomeDivider
import io.github.xgl34222220.hetu.home.HomeIconButton
import io.github.xgl34222220.hetu.home.HomeIcons
import io.github.xgl34222220.hetu.home.HomeModalSheet
import io.github.xgl34222220.hetu.home.HomeMotion
import io.github.xgl34222220.hetu.home.HomeRefreshBox
import io.github.xgl34222220.hetu.home.LocalHomeMotionEnabled
import io.github.xgl34222220.hetu.home.HomeType
import io.github.xgl34222220.hetu.home.LocalHomeColors
import kotlinx.coroutines.launch

/** Lazy-list items that precede the tab content: title, tab strip, and the search field while it is open. */
internal fun panelHeaderItemCount(data: PanelData, view: PanelViewState): Int =
    2 + if (data.running && view.searching && view.tab.searchable) 1 else 0

/**
 * 面板 (tab root). Stateless: [data] is what the core reports, [view] is what the user chose,
 * [overlay] is the one floating layer that is open.
 *
 * Layout: a single LazyColumn (large title → tab strip → optional search → tab content) under a
 * transparent action bar. After 40 dp of scroll the bar turns opaque and shows the compact title.
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
    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val bottomPad = contentPadding.calculateBottomPadding()
    val collapseAt = with(LocalDensity.current) { 40.dp.toPx() }
    val collapsed by remember(listState, collapseAt) {
        derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > collapseAt }
    }
    val running = data.running
    val configuration = LocalConfiguration.current
    val effectiveLayout = PanelLogic.layoutForViewport(view.layout, configuration.screenWidthDp, LocalDensity.current.fontScale)
    val displayView = view.copy(layout = effectiveLayout)
    val motion = LocalHomeMotionEnabled.current
    val rows = remember(data.groups, data.delays, data.globalMode, displayView, running) { if (running && view.tab == PanelTab.Groups) PanelLogic.groupRows(data, displayView) else emptyList() }
    val headerItems = panelHeaderItemCount(data, view)
    val closeOverlay = { onOverlay(null) }
    val menu: @Composable (PanelOverlay) -> Unit = { target ->
        if (popups) PanelDropdown(expanded = overlay == target, onDismiss = closeOverlay) { PanelMenuContent(target, view, onView, onOverlay) }
    }

    HomeRefreshBox(
        refreshing = data.refreshing,
        onRefresh = actions.onRefresh,
        label = "刷新${view.tab.label}",
        modifier = modifier.fillMaxSize().background(c.bg),
        indicatorPadding = PaddingValues(top = statusTop + HomeDims.barHeight),
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = listState,
            contentPadding = PaddingValues(top = statusTop + 4.dp, bottom = bottomPad),
        ) {
            item(key = "panel-title") {
                Box(Modifier.panelGutter().padding(bottom = 12.dp).height(44.dp), contentAlignment = Alignment.CenterStart) {
                    Text("面板", color = c.t1, style = HomeType.largeTitle)
                }
            }
            item(key = "panel-tabs") {
                PanelTabStrip(view.tab, { onView(view.withTab(it)) }, Modifier.padding(bottom = 16.dp))
            }
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
                    PanelEmptyState(PanelIcons.CircleX, "无法读取面板", data.readError) {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            HomeButton("重试", actions.onRefresh, loading = data.refreshing)
                            HomeButton("API 设置", { onOverlay(PanelOverlay.ApiReadErrorSheet) }, kind = HomeButtonKind.Primary)
                        }
                    }
                }
                return@LazyColumn
            }
            if (view.searching && view.tab.searchable) item(key = "panel-search") {
                PanelSearchField(view.query, { onView(view.copy(query = it)) }, view.tab.searchHint.orEmpty(), Modifier.panelGutter().padding(bottom = 8.dp), autoFocus = popups)
            }
            when (view.tab) {
                PanelTab.Groups -> panelGroupsTab(
                    rows = rows, data = data, view = displayView,
                    onToggleGroup = { name ->
                        val next = view.toggleGroup(name)
                        onView(next)
                        if (name in next.expandedGroups) {
                            val at = PanelLogic.groupRows(data, next.copy(layout = effectiveLayout)).indexOfFirst { it is PanelGroupRow.Cards && it.groups.any { g -> g.name == name } }
                            if (at >= 0) scope.launch {
                                if (motion) listState.animateScrollToItem(headerItems + at) else listState.scrollToItem(headerItems + at)
                            }
                        }
                    },
                    onSelectNode = actions.onSelectNode,
                    onTestNode = actions.onTestNode,
                    onTestGroup = actions.onTestGroup,
                    onNodeInfo = { onOverlay(PanelOverlay.NodeInfo(it)) },
                )
                PanelTab.Overview -> panelOverviewTab(
                    overview = data.overview, view = view, onView = onView,
                    onOpenSubscriptions = { onView(view.withTab(PanelTab.Subscriptions)) },
                    onRankCountMenu = { onOverlay(PanelOverlay.RankCountMenu) },
                    rankMenu = { menu(PanelOverlay.RankCountMenu) },
                )
                PanelTab.Subscriptions -> panelSubscriptionsTab(PanelLogic.subscriptions(data, view), view.needle, actions.onUpdateSubscription)
                PanelTab.Connections -> panelConnectionsTab(data, view, onView, onOpen = { onOverlay(PanelOverlay.ConnectionDetail(it)) }, onCloseAll = { onOverlay(PanelOverlay.CloseAllDialog) })
                PanelTab.Rules -> panelRulesTab(PanelLogic.rules(data, view), view.needle, data.ruleTotal)
                PanelTab.RuleSets -> panelRuleSetsTab(PanelLogic.ruleSets(data, view), view.needle, actions.onUpdateRuleSet)
                PanelTab.Logs -> panelLogsTab(PanelLogic.logs(data, view), view, onView, onOrderMenu = { onOverlay(PanelOverlay.LogOrderMenu) }, orderMenu = { menu(PanelOverlay.LogOrderMenu) })
            }
        }

        PanelActionBar(data, view, overlay, actions, collapsed, onView, onOverlay, menu)

        // 策略: once a group is open, “定位当前节点” and a collapse pill float above the dock.
        val openGroup = view.expandedGroups.lastOrNull()?.takeIf { running && data.readError.isBlank() && view.tab == PanelTab.Groups }
        if (openGroup != null) {
            Column(
                Modifier.align(Alignment.BottomEnd).padding(end = HomeDims.gutter, bottom = (bottomPad - 18.dp).coerceAtLeast(16.dp)),
                horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PanelFab(PanelIcons.LocateFixed, "定位当前节点", {
                    val now = data.groups.firstOrNull { it.name == openGroup }?.now
                    val at = rows.indexOfFirst { it is PanelGroupRow.Nodes && it.group.name == openGroup && it.nodes.any { n -> n.name == now } }
                    if (at >= 0) scope.launch {
                        if (motion) listState.animateScrollToItem(headerItems + at) else listState.scrollToItem(headerItems + at)
                    }
                })
                PanelFab(PanelIcons.ChevronDown, "收起节点", { onView(view.toggleGroup(openGroup)) }, text = openGroup)
            }
        }

        if (overlay != null) {
            if (popups) PanelPopups(overlay, data, view, actions, onView, onOverlay)
            else PanelInlineOverlay(overlay, data, view, actions, onView, onOverlay, statusTop)
        }
    }
}

/* ------------------------------------------------------------------ */
/*  Action bar                                                          */
/* ------------------------------------------------------------------ */

/**
 * Actions per tab (left → right):
 * 策略: 搜索 · 筛选(menu) · 排序与布局(sheet) ｜ 概览: 排行方式(menu) ｜ 订阅: 搜索 · 全部更新
 * 连接: 搜索 · 筛选(menu) · 排序(menu) · 更多(menu) ｜ 规则: 搜索 · 刷新 ｜ 规则集: 搜索 · 全部更新 ｜ 日志: 搜索 · 刷新
 * No actions while the proxy is not running.
 */
@Composable
private fun PanelActionBar(
    data: PanelData,
    view: PanelViewState,
    overlay: PanelOverlay?,
    actions: PanelActions,
    collapsed: Boolean,
    onView: (PanelViewState) -> Unit,
    onOverlay: (PanelOverlay?) -> Unit,
    menu: @Composable (PanelOverlay) -> Unit,
) {
    val c = LocalHomeColors.current
    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val barAlpha by animateFloatAsState(if (collapsed) 1f else 0f, tween(if (LocalHomeMotionEnabled.current) HomeMotion.SwitchMs else 0), label = "panel-bar")
    val tab = view.tab
    Box(Modifier.fillMaxWidth()) {
        if (barAlpha > 0f) Column(Modifier.fillMaxWidth().alpha(barAlpha).background(c.bg.copy(alpha = .94f))) {
            Spacer(Modifier.height(statusTop + HomeDims.barHeight))
            HomeDivider()
        }
        Box(Modifier.fillMaxWidth().padding(top = statusTop).height(HomeDims.barHeight)) {
            val wide = data.running && tab == PanelTab.Connections
            Text(
                "面板",
                Modifier.align(if (wide) Alignment.CenterStart else Alignment.Center).padding(horizontal = HomeDims.gutter).alpha(barAlpha),
                color = c.t1, style = HomeType.barTitle,
            )
            if (data.running && data.readError.isBlank()) Row(Modifier.align(Alignment.CenterEnd).padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                val anchored: @Composable (ImageVector, String, PanelOverlay, Color) -> Unit = { icon, label, target, tint ->
                    Box {
                        HomeIconButton(icon, label, { onOverlay(if (overlay == target) null else target) }, tint = tint)
                        menu(target)
                    }
                }
                if (tab.searchable) HomeIconButton(if (view.searching) HomeIcons.X else PanelIcons.Search, if (view.searching) "关闭搜索" else "搜索", { onView(view.toggleSearch()) })
                when (tab) {
                    PanelTab.Groups -> {
                        anchored(PanelIcons.ListFilter, "筛选", PanelOverlay.GroupFilterMenu, c.t1)
                        HomeIconButton(HomeIcons.SlidersHorizontal, "排序与布局", { onOverlay(PanelOverlay.LayoutSheet) })
                    }
                    PanelTab.Overview -> anchored(PanelIcons.ArrowDownWideNarrow, "排行方式", PanelOverlay.RankModeMenu, c.t1)
                    PanelTab.Subscriptions -> HomeIconButton(HomeIcons.RefreshCw, "全部更新", actions.onUpdateAllSubscriptions)
                    PanelTab.Connections -> {
                        anchored(PanelIcons.ListFilter, "连接筛选", PanelOverlay.ConnFilterMenu, if (view.connFilter != PanelConnFilter.All) c.accent else c.t1)
                        anchored(PanelIcons.ArrowUpDown, "连接排序", PanelOverlay.ConnSortMenu, c.t1)
                        anchored(PanelIcons.EllipsisVertical, "连接显示", PanelOverlay.ConnMoreMenu, c.t1)
                    }
                    PanelTab.Rules -> HomeIconButton(HomeIcons.RefreshCw, "刷新", actions.onRefreshRules)
                    PanelTab.RuleSets -> HomeIconButton(PanelIcons.Download, "全部更新", actions.onUpdateAllRuleSets)
                    PanelTab.Logs -> HomeIconButton(HomeIcons.RefreshCw, "刷新", actions.onRefreshLogs)
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
                PanelInlineMenu(
                    Modifier.align(Alignment.TopEnd).padding(
                        top = statusTop + if (overlay == PanelOverlay.RankCountMenu) 300.dp else if (inList) 172.dp else HomeDims.barHeight,
                        end = 12.dp,
                    ),
                ) { PanelMenuContent(overlay, view, onView, onOverlay) }
            }
            overlay.isSheet -> {
                Box(Modifier.fillMaxSize().background(c.scrim).clickable { onOverlay(null) })
                Box(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth().clip(HomeDims.sheetShape).background(c.surface).border(1.dp, c.line, HomeDims.sheetShape),
                ) { PanelSheetBody(overlay, data, view, actions, onView, onOverlay) }
            }
            overlay == PanelOverlay.CloseAllDialog -> {
                Box(Modifier.fillMaxSize().background(c.scrim))
                PanelCloseAllDialogCard(onConfirm = {}, onDismiss = {}, modifier = Modifier.align(Alignment.Center))
            }
        }
    }
}
