package io.github.xgl34222220.hetu.tools

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import io.github.xgl34222220.hetu.home.HomeCard
import io.github.xgl34222220.hetu.home.HomeDims
import io.github.xgl34222220.hetu.home.HomeDivider
import io.github.xgl34222220.hetu.home.HomeIconButton
import io.github.xgl34222220.hetu.home.HomeIcons
import io.github.xgl34222220.hetu.home.HomeMotion
import io.github.xgl34222220.hetu.home.HomeType
import io.github.xgl34222220.hetu.home.LocalHomeColors

internal fun ToolsEntry.icon(): ImageVector = when (this) {
    ToolsEntry.Files -> ToolsIcons.Folder
    ToolsEntry.Scripts -> ToolsIcons.SquareTerminal
    ToolsEntry.Logs -> ToolsIcons.FileText
    ToolsEntry.Apps -> ToolsIcons.LayoutGrid
    ToolsEntry.NetMatch -> ToolsIcons.Wifi
    ToolsEntry.Share -> ToolsIcons.RadioTower
    ToolsEntry.Bypass -> ToolsIcons.Split
    ToolsEntry.Configs -> ToolsIcons.FileCog
    ToolsEntry.SubStore -> ToolsIcons.Link
    ToolsEntry.CnIp -> ToolsIcons.Database
    ToolsEntry.Cores -> ToolsIcons.Cpu
    ToolsEntry.Adblock -> ToolsIcons.ShieldBan
    ToolsEntry.Diag -> ToolsIcons.Activity
    ToolsEntry.WebUi -> ToolsIcons.Monitor
}

/**
 * 工具 (tab root). Stateless.
 *
 * - Page 1: large title, search glyph on the right, the entries stacked in cards.
 * - Page 2: once the large title has scrolled 40 dp away a compact centred title fades in.
 * - Page 3: the search glyph turns into a close glyph and a search box appears under the title;
 *   rows that do not match disappear, hits in the title are tinted, no hit shows an empty state.
 *
 * @param contentPadding bottom padding must clear the floating dock (default 116 dp).
 * @param scroll hoisted so the host can keep the position across tab switches.
 */
@Composable
internal fun ToolsScreen(
    state: ToolsRootState,
    onToggleSearch: () -> Unit,
    onQueryChange: (String) -> Unit,
    onOpen: (ToolsEntry) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(bottom = HomeDims.dockClearance),
    scroll: ScrollState = rememberScrollState(),
) {
    val c = LocalHomeColors.current
    val collapseAt = with(LocalDensity.current) { 40.dp.toPx() }
    val collapsed by remember(scroll, collapseAt) { derivedStateOf { scroll.value > collapseAt } }
    val results = state.results
    val searchIcon = if (state.searching) HomeIcons.X else ToolsIcons.Search
    val searchLabel = if (state.searching) "关闭搜索" else "搜索"

    Box(modifier.fillMaxSize().background(c.bg)) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(scroll)
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(contentPadding)
                .padding(start = HomeDims.gutter, end = HomeDims.gutter, top = 4.dp),
            verticalArrangement = Arrangement.spacedBy(HomeDims.gap),
        ) {
            Row(Modifier.fillMaxWidth().padding(bottom = 4.dp).height(HomeDims.touch), verticalAlignment = Alignment.CenterVertically) {
                Text("工具", Modifier.weight(1f), color = c.t1, style = HomeType.largeTitle)
                HomeIconButton(searchIcon, searchLabel, onToggleSearch, Modifier.padding(end = 0.dp))
            }
            if (state.searching) ToolsSearchField(state.query, onQueryChange, "搜索工具")

            when {
                results == null -> state.groups.forEach { group -> ToolsGroup(group, query = "", brief = false, onOpen = onOpen) }
                results.isEmpty() -> ToolsEmpty(ToolsIcons.Search, "没有匹配的工具", subtitle = "尝试其他关键词")
                else -> results.forEach { group -> ToolsGroup(group, query = state.query, brief = true, onOpen = onOpen) }
            }
        }

        // Compact centred title that fades in once the large title has scrolled 40 dp.
        val barAlpha by animateFloatAsState(if (collapsed) 1f else 0f, tween(HomeMotion.SwitchMs), label = "tools-bar")
        if (barAlpha > 0f) {
            Column(Modifier.fillMaxWidth().alpha(barAlpha).background(c.bg.copy(alpha = .94f))) {
                Box(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).height(HomeDims.barHeight)) {
                    Text("工具", Modifier.align(Alignment.Center), color = c.t1, style = HomeType.barTitle)
                    HomeIconButton(searchIcon, searchLabel, onToggleSearch, Modifier.align(Alignment.CenterEnd).padding(end = HomeDims.gutter))
                }
                HomeDivider()
            }
        }
    }
}

@Composable
private fun ToolsGroup(group: ToolsGroupSpec, query: String, brief: Boolean, onOpen: (ToolsEntry) -> Unit) {
    val c = LocalHomeColors.current
    if (group.title != null) {
        Text(group.title, Modifier.padding(start = 4.dp, end = 4.dp, top = 12.dp), color = c.t2, style = HomeType.section)
    }
    HomeCard(Modifier.fillMaxWidth()) {
        group.entries.forEachIndexed { index, entry ->
            if (index > 0) HomeDivider()
            ToolsRow(
                title = toolsHighlighted(entry.title, ToolsCatalog.highlight(entry.title, query)),
                icon = entry.icon(),
                subtitle = if (brief) entry.brief else entry.summary,
                onClick = { onOpen(entry) },
                trailing = { ToolsChevron() },
            )
        }
    }
}
