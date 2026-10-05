package io.github.xgl34222220.hetu.tools

import io.github.xgl34222220.hetu.ui.ht
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.xgl34222220.hetu.tools.ToolsButton as HomeButton
import io.github.xgl34222220.hetu.home.HomeButtonKind
import io.github.xgl34222220.hetu.tools.ToolsSurfaceCard as HomeCard
import io.github.xgl34222220.hetu.tools.ToolsDesignDims as HomeDims
import io.github.xgl34222220.hetu.home.HomeHaptic
import io.github.xgl34222220.hetu.tools.ToolsIconButton as HomeIconButton
import io.github.xgl34222220.hetu.home.HomeIcons
import io.github.xgl34222220.hetu.tools.ToolsSegmented as HomeSegmented
import io.github.xgl34222220.hetu.tools.ToolsTypography as HomeType
import io.github.xgl34222220.hetu.home.LocalHomeColors
import io.github.xgl34222220.hetu.home.LocalHomeHaptics

/**
 * 应用管理 (工具 › 应用管理). Stateless.
 *
 * - Pages 28, 31, 32: the three scopes. 黑名单 and 白名单 each keep their own list; in 核心 the
 *   rows are dimmed and inert, because core mode does not filter by Android UID.
 * - Page 29: sort menu under the ⇅ button. Page 30: the «⋮» menu.
 * - Page 33: search box under the bar; the footer keeps counting the whole list.
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
    val ready = state.load is ToolsLoad.Ready
    val core = state.scope == ToolsAppScope.Core
    val menuOffset = with(LocalDensity.current) { 44.dp.roundToPx() }
    val rows = remember(state) { state.visible }

    Column(modifier.fillMaxSize().background(c.bg)) {
        ToolsTopBar(title = "应用管理", onBack = onBack) {
            HomeIconButton(ToolsIcons.Search, if (state.searching) "关闭搜索" else "搜索", onToggleSearch, modifier = Modifier.width(32.dp), enabled = ready)
            Box {
                HomeIconButton(ToolsFeatureIcons.ArrowUpDown, "排序", { onOpenMenu(ToolsAppsMenu.Sort) }, modifier = Modifier.width(32.dp), enabled = ready)
                if (menu == ToolsAppsMenu.Sort) {
                    ToolsMenuPopup(onDismiss = onDismissMenu, offsetY = menuOffset) { ToolsAppSortMenuCard(state, onSortChange, onToggleDescending) }
                }
            }
            HomeIconButton(HomeIcons.CircleCheck, "全选当前结果", onSelectAll, modifier = Modifier.width(32.dp), enabled = ready && !core && rows.isNotEmpty())
            Box {
                HomeIconButton(ToolsFeatureIcons.EllipsisVertical, "更多", { onOpenMenu(ToolsAppsMenu.More) }, modifier = Modifier.width(32.dp), enabled = ready)
                if (menu == ToolsAppsMenu.More) {
                    ToolsMenuPopup(onDismiss = onDismissMenu, offsetY = menuOffset) { ToolsAppMoreMenuCard(state, onToggleSystem, onSelectAll, onClear, onRefresh) }
                }
            }
        }
        Column(Modifier.padding(horizontal = HomeDims.gutter), verticalArrangement = Arrangement.spacedBy(HomeDims.gap)) {
            if (state.searching) ToolsSearchField(state.query, onQueryChange, "搜索应用或包名")
            HomeSegmented(
                options = ToolsAppScope.entries.map { it to it.label },
                selected = state.scope,
                onSelect = onScopeChange,
                enabled = ready,
            )
        }
        ToolsPullRefresh(state.refreshing, onRefresh, Modifier.weight(1f).fillMaxWidth()) {
            when (val load = state.load) {
                ToolsLoad.Loading -> ToolsLoading()
                is ToolsLoad.Failed -> ToolsEmpty(ToolsIcons.LayoutGrid, "应用列表读取失败", subtitle = load.message) {
                    HomeButton("重新读取", onRetry, kind = HomeButtonKind.Primary, icon = HomeIcons.RefreshCw)
                }
                ToolsLoad.Ready -> if (rows.isEmpty()) {
                    val searching = state.searching && state.query.isNotBlank()
                    ToolsEmpty(ToolsIcons.Search, if (searching) "没有匹配的应用" else "没有可显示的应用", subtitle = ht(if (searching) "尝试其他关键词" else "可在「更多」里显示系统应用"))
                } else {
                    LazyColumn(
                        Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = HomeDims.gutter, end = HomeDims.gutter, top = HomeDims.gap, bottom = HomeDims.gap),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(rows, key = { it.key }) { app ->
                            AppRow(app, checked = app.key in state.selected, inert = core || state.refreshing, dimmed = core, onClick = { onToggleApp(app) }, appIcon = appIcon)
                        }
                    }
                }
            }
        }
        if (ready) {
            ToolsFooterBar {
                Text(ht("已选") + " ", color = c.t1, style = HomeType.bodySmall)
                Text(state.selectedCount.toString(), color = c.accent, style = HomeType.value.copy(fontFeatureSettings = "tnum"))
                Text(" " + ht("个"), color = c.t1, style = HomeType.bodySmall)
                Text(ht("  ·  "), color = c.t3, style = HomeType.bodySmall)
                Text(ht(state.scope.tip), Modifier.weight(1f), color = c.t2, style = ToolsType.url, maxLines = 2, overflow = TextOverflow.Ellipsis)
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
    onClick: () -> Unit,
    appIcon: (@Composable (ToolsApp, Modifier) -> Unit)?,
) {
    val c = LocalHomeColors.current
    val haptics = LocalHomeHaptics.current
    HomeCard(Modifier.fillMaxWidth().alpha(if (dimmed) .55f else 1f)) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(enabled = !inert, role = Role.Checkbox) { haptics(HomeHaptic.Tick); onClick() }
                .heightIn(min = 78.dp)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (appIcon != null) appIcon(app, Modifier.size(40.dp).clip(HomeDims.controlShape)) else ToolsAvatar(app.label)
            Column(Modifier.weight(1f)) {
                Text(app.label, color = c.t1, style = HomeType.rowTitle.copy(fontWeight = FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(app.packageName, color = c.t2, style = ToolsType.url, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (app.uid >= 0) Text("#${app.uid}", color = c.accent, style = HomeType.caption.copy(fontFeatureSettings = "tnum", fontWeight = FontWeight.Medium), maxLines = 1)
            ToolsCheckCircle(checked)
        }
    }
}

/** Page 29: the three sort keys with a check on the active one, then the direction toggle. */
@Composable
internal fun ToolsAppSortMenuCard(state: ToolsAppsState, onSortChange: (ToolsAppSort) -> Unit, onToggleDescending: () -> Unit, modifier: Modifier = Modifier) {
    ToolsOptionMenuCard(
        ToolsAppSort.entries.map { sort -> ToolsMenuOption(sort.label, checked = state.sort == sort) { onSortChange(sort) } } +
            ToolsMenuOption(if (state.descending) "改为升序" else "改为降序", dividerBefore = true, onClick = onToggleDescending),
        modifier,
    )
}

/** Page 30. 全选 and 清空 are unavailable in core mode. */
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
            ToolsMenuOption("全选当前结果", enabled = editable, onClick = onSelectAll),
            ToolsMenuOption("清空名单", enabled = editable && state.selected.isNotEmpty(), onClick = onClear),
            ToolsMenuOption("刷新应用", enabled = !state.refreshing, onClick = onRefresh),
        ),
        modifier,
    )
}
