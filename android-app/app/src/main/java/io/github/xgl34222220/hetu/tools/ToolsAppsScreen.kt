package io.github.xgl34222220.hetu.tools

import io.github.xgl34222220.hetu.home.homeDiffuseCanvas
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.xgl34222220.hetu.home.HomeBarBackdrop
import io.github.xgl34222220.hetu.home.HomeButtonKind
import io.github.xgl34222220.hetu.home.HomeDims
import io.github.xgl34222220.hetu.home.HomeHaptic
import io.github.xgl34222220.hetu.home.HomeIconButton
import io.github.xgl34222220.hetu.home.HomeIcons
import io.github.xgl34222220.hetu.home.HomeReveal
import io.github.xgl34222220.hetu.home.HomeRollingText
import io.github.xgl34222220.hetu.home.HomeType
import io.github.xgl34222220.hetu.home.LocalHomeColors
import io.github.xgl34222220.hetu.home.LocalHomeHaptics
import io.github.xgl34222220.hetu.home.LocalHomeMotionEnabled
import io.github.xgl34222220.hetu.home.homeGlassSource
import io.github.xgl34222220.hetu.home.homeRowPressTint
import io.github.xgl34222220.hetu.home.rememberHomeBarGlass
import io.github.xgl34222220.hetu.panel.PanelIcons
import io.github.xgl34222220.hetu.ui.hetuAnimateItem
import io.github.xgl34222220.hetu.ui.ht

/**
 * 应用管理 (工具 › 应用管理). Stateless.
 *
 * - Three scopes behind a sliding tab. 黑名单 and 白名单 each keep their own list; in 核心 the
 *   rows are dimmed and inert, because core mode does not filter by Android UID.
 * - The bar, the search box (which unfolds under it) and the tabs stay pinned; the list scrolls
 *   beneath them and they turn to glass. Rows glide to their new place when the order changes.
 * - A footer card keeps counting the whole list while searching.
 *
 * @param menu which bar menu is open; both are popups anchored to their button.
 * @param appIcon draws the launcher icon of an app; a letter tile is used when null.
 */
@Composable
internal fun ToolsAppsScreen(
    state: ToolsAppsState,
    menu: ToolsAppsMenu?,
    onBack: () -> Unit,
    onToggleSearch: () -> Unit,
    onQueryChange: (String) -> Unit,
    onScopeChange: (ToolsAppScope) -> Unit,
    onToggleApp: (ToolsApp) -> Unit,
    onSelectAll: () -> Unit,
    onOpenMenu: (ToolsAppsMenu) -> Unit,
    onDismissMenu: () -> Unit,
    onSortChange: (ToolsAppSort) -> Unit,
    onToggleDescending: () -> Unit,
    onToggleSystem: () -> Unit,
    onClear: () -> Unit,
    onRefresh: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    appIcon: (@Composable (ToolsApp, Modifier) -> Unit)? = null,
) {
    val c = LocalHomeColors.current
    val density = LocalDensity.current
    val motion = LocalHomeMotionEnabled.current
    val ready = state.load is ToolsLoad.Ready
    val core = state.scope == ToolsAppScope.Core
    val rows = remember(state) { state.visible }
    val list = rememberLazyListState()
    val lifted by remember(list) { derivedStateOf { list.firstVisibleItemIndex > 0 || list.firstVisibleItemScrollOffset > 6 } }
    val glass = rememberHomeBarGlass(lifted && ready && rows.isNotEmpty())
    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    // Height of everything pinned at the top: bar, search box and tabs. Measured, because the
    // search box comes and goes; the first frame uses the size of the bar and the tabs alone.
    var headPx by remember { mutableIntStateOf(0) }
    val headTop = if (headPx > 0) with(density) { headPx.toDp() } else statusTop + HomeDims.barHeight + 70.dp
    val slot = Modifier.width(38.dp)

    Column(modifier.fillMaxSize().homeDiffuseCanvas().imePadding()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            ToolsPullRefresh(state.refreshing, onRefresh, Modifier.fillMaxSize(), indicatorTop = headTop) {
                when (val load = state.load) {
                    ToolsLoad.Loading -> ToolsLoading(Modifier.padding(start = HomeDims.gutter, end = HomeDims.gutter, top = headTop + 6.dp), cards = 6)
                    is ToolsLoad.Failed -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = headTop)) {
                        ToolsEmpty(ToolsIcons.LayoutGrid, "应用列表读取失败", subtitle = load.message) {
                            ToolsButton("重新读取", onRetry, kind = HomeButtonKind.Primary, icon = HomeIcons.RefreshCw)
                        }
                    }
                    ToolsLoad.Ready -> if (rows.isEmpty()) {
                        val searching = state.searching && state.query.isNotBlank()
                        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = headTop)) {
                            ToolsEmpty(
                                if (searching) PanelIcons.SearchX else ToolsIcons.LayoutGrid,
                                if (searching) "没有匹配的应用" else "没有可显示的应用",
                                subtitle = ht(if (searching) "尝试其他关键词" else "可在「更多」里显示系统应用"),
                            )
                        }
                    } else {
                        LazyColumn(
                            Modifier.fillMaxSize().homeGlassSource(glass),
                            state = list,
                            contentPadding = PaddingValues(start = HomeDims.gutter, end = HomeDims.gutter, top = headTop + 6.dp, bottom = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            items(rows, key = { it.key }) { app ->
                                AppRow(
                                    app, checked = app.key in state.selected, inert = core || state.refreshing, dimmed = core,
                                    onToggle = { onToggleApp(app) }, appIcon = appIcon, modifier = hetuAnimateItem(motion),
                                )
                            }
                        }
                    }
                }
            }
            HomeBarBackdrop(glass, Modifier.fillMaxWidth().height(headTop))
            Column(Modifier.fillMaxWidth().onSizeChanged { headPx = it.height }) {
                ToolsWideBar(title = "应用管理", onBack = onBack) {
                    HomeIconButton(if (state.searching) HomeIcons.X else ToolsIcons.Search, if (state.searching) "关闭搜索" else "搜索", onToggleSearch, slot, enabled = ready)
                    Box {
                        HomeIconButton(ToolsFeatureIcons.ArrowUpDown, "排序", { onOpenMenu(ToolsAppsMenu.Sort) }, slot, enabled = ready)
                        if (menu == ToolsAppsMenu.Sort) ToolsMenuPopup(onDismiss = onDismissMenu) { ToolsAppSortMenuCard(state, onSortChange, onToggleDescending) }
                    }
                    HomeIconButton(HomeIcons.CircleCheck, "全选当前结果", onSelectAll, slot, enabled = ready && !core && rows.isNotEmpty())
                    Box {
                        HomeIconButton(ToolsFeatureIcons.EllipsisVertical, "更多", { onOpenMenu(ToolsAppsMenu.More) }, slot, enabled = ready)
                        if (menu == ToolsAppsMenu.More) ToolsMenuPopup(onDismiss = onDismissMenu) { ToolsAppMoreMenuCard(state, onToggleSystem, onSelectAll, onClear, onRefresh) }
                    }
                }
                Column(Modifier.padding(start = HomeDims.gutter, end = HomeDims.gutter, bottom = 8.dp)) {
                    HomeReveal(state.searching) {
                        ToolsSearchField(state.query, onQueryChange, "搜索应用或包名", Modifier.padding(bottom = 10.dp))
                    }
                    ToolsSegmented(
                        options = ToolsAppScope.entries.map { it to it.label },
                        selected = state.scope,
                        onSelect = onScopeChange,
                        enabled = ready,
                    )
                }
            }
        }
        if (ready) {
            Box(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBars).padding(start = HomeDims.gutter, end = HomeDims.gutter, top = 8.dp, bottom = 12.dp)) {
                ToolsFooterBar {
                    Text(ht("已选"), color = c.t1, style = HomeType.noteStrong)
                    Spacer(Modifier.width(6.dp))
                    HomeRollingText(state.selectedCount.toString(), c.accent, HomeType.value.copy(fontWeight = FontWeight.Bold), alignment = Alignment.Center)
                    Spacer(Modifier.width(6.dp))
                    Text(ht("个"), color = c.t1, style = HomeType.noteStrong)
                    Text("  ·  ", color = c.t3, style = HomeType.note)
                    Text(ht(state.scope.tip), Modifier.weight(1f), color = c.t2, style = HomeType.caption, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun AppRow(
    app: ToolsApp,
    checked: Boolean,
    inert: Boolean,
    dimmed: Boolean,
    onToggle: () -> Unit,
    appIcon: (@Composable (ToolsApp, Modifier) -> Unit)?,
    modifier: Modifier,
) {
    val c = LocalHomeColors.current
    val haptics = LocalHomeHaptics.current
    val source = remember { MutableInteractionSource() }
    Row(
        modifier
            .fillMaxWidth()
            .alpha(if (dimmed) .55f else 1f)
            .clip(HomeDims.cardShape)
            .background(c.surface)
            .homeRowPressTint(source)
            .toggleable(value = checked, interactionSource = source, indication = null, enabled = !inert, role = Role.Checkbox) {
                haptics(HomeHaptic.Tick)
                onToggle()
            }
            .heightIn(min = 78.dp)
            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (appIcon != null) appIcon(app, Modifier.size(46.dp).clip(RoundedIcon)) else ToolsAvatar(app.label)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(app.label, color = c.t1, style = HomeType.rowTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(app.packageName, color = c.t2, style = ToolsType.url, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (app.uid >= 0) {
            Spacer(Modifier.width(10.dp))
            Text("#${app.uid}", color = c.accent, style = HomeType.delay, maxLines = 1)
        }
        Spacer(Modifier.width(12.dp))
        ToolsCheckCircle(checked)
    }
}

private val RoundedIcon = androidx.compose.foundation.shape.RoundedCornerShape(13.dp)

/** The three sort keys with a check on the active one, then the direction toggle. */
@Composable
internal fun ToolsAppSortMenuCard(state: ToolsAppsState, onSortChange: (ToolsAppSort) -> Unit, onToggleDescending: () -> Unit, modifier: Modifier = Modifier) {
    ToolsOptionMenuCard(
        ToolsAppSort.entries.map { sort -> ToolsMenuOption(sort.label, checked = state.sort == sort) { onSortChange(sort) } } +
            ToolsMenuOption(if (state.descending) "改为升序" else "改为降序", dividerBefore = true, onClick = onToggleDescending),
        modifier,
    )
}

/** 全选 and 清空 are unavailable in core mode. */
@Composable
internal fun ToolsAppMoreMenuCard(
    state: ToolsAppsState,
    onToggleSystem: () -> Unit,
    onSelectAll: () -> Unit,
    onClear: () -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val editable = state.scope != ToolsAppScope.Core
    ToolsOptionMenuCard(
        listOf(
            ToolsMenuOption("显示系统应用", checked = state.showSystem, onClick = onToggleSystem),
            ToolsMenuOption("全选当前结果", enabled = editable, dividerBefore = true, onClick = onSelectAll),
            ToolsMenuOption("清空名单", enabled = editable && state.selected.isNotEmpty(), onClick = onClear),
            ToolsMenuOption("刷新应用", enabled = !state.refreshing, onClick = onRefresh),
        ),
        modifier,
    )
}
