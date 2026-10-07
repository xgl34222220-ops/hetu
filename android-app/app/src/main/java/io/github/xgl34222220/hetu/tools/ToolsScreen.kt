package io.github.xgl34222220.hetu.tools

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import io.github.xgl34222220.hetu.home.HomeCard
import io.github.xgl34222220.hetu.home.HomeDims
import io.github.xgl34222220.hetu.home.HomeIconButton
import io.github.xgl34222220.hetu.home.HomeIcons
import io.github.xgl34222220.hetu.home.HomeLargeTitleBar
import io.github.xgl34222220.hetu.home.HomeLargeTitleDims
import io.github.xgl34222220.hetu.home.HomeReveal
import io.github.xgl34222220.hetu.home.HomeType
import io.github.xgl34222220.hetu.home.LocalHomeColors
import io.github.xgl34222220.hetu.home.homeEnter
import io.github.xgl34222220.hetu.home.homeGlassSource
import io.github.xgl34222220.hetu.home.homeLargeTitlePadding
import io.github.xgl34222220.hetu.home.rememberHomeBarGlass
import io.github.xgl34222220.hetu.home.rememberHomeStagger
import io.github.xgl34222220.hetu.panel.PanelIcons
import io.github.xgl34222220.hetu.ui.HetuStaggerState
import io.github.xgl34222220.hetu.ui.ht

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
    ToolsEntry.Adblock -> ToolsIcons.ShieldX
    ToolsEntry.Diag -> ToolsIcons.Activity
    ToolsEntry.WebUi -> ToolsIcons.Monitor
}

/**
 * 工具 (tab root). Stateless.
 *
 * - Large title on the left with the search glyph pinned at the right; the entries are stacked
 *   in cards and rise in one after another the first time the page is shown.
 * - As the page scrolls the large title slides away and a compact one fades into a glass bar.
 * - Search: the glyph turns into a close glyph and a search box unfolds under the title; rows
 *   that do not match disappear, hits in the title are tinted, no hit shows an empty state.
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
    val titleHeight = HomeLargeTitleDims.title
    val titlePx = with(LocalDensity.current) { titleHeight.toPx() }
    val lifted by remember(scroll, titlePx) { derivedStateOf { scroll.value > titlePx * .6f } }
    val glass = rememberHomeBarGlass(lifted)
    val stagger = rememberHomeStagger()

    val query = state.query.trim()
    val results = if (state.searching && query.isNotEmpty()) state.groups.mapNotNull { group ->
        val entries = group.entries.filter { entry ->
            val localized = listOf(ht(entry.title), ht(entry.summary), ht(entry.brief))
            ToolsCatalog.matches(entry, query) || localized.any { it.contains(query, ignoreCase = true) }
        }
        if (entries.isEmpty()) null else group.copy(entries = entries)
    } else null

    Box(modifier.fillMaxSize().background(c.bg)) {
        Column(
            Modifier
                .fillMaxSize()
                .homeGlassSource(glass)
                .verticalScroll(scroll)
                .padding(homeLargeTitlePadding(contentPadding, titleHeight)),
        ) {
            // Each block carries its own gap, so the search box can unfold without a jump.
            HomeReveal(state.searching) {
                ToolsSearchField(state.query, onQueryChange, "搜索工具", Modifier.padding(bottom = HomeDims.gap))
            }
            when {
                results == null -> state.groups.forEachIndexed { index, group ->
                    ToolsGroup(group, query = "", brief = false, onOpen = onOpen, stagger = stagger, index = index)
                }
                results.isEmpty() -> ToolsEmpty(PanelIcons.SearchX, "没有匹配的工具", subtitle = ht("尝试其他关键词"))
                else -> results.forEachIndexed { index, group ->
                    ToolsGroup(group, query = state.query, brief = true, onOpen = onOpen, stagger = stagger, index = index)
                }
            }
        }
        HomeLargeTitleBar(
            title = "工具",
            collapse = { if (titlePx <= 0f) 1f else scroll.value / titlePx },
            glass = glass,
            largeTitleHeight = titleHeight,
        ) {
            HomeIconButton(
                if (state.searching) HomeIcons.X else ToolsIcons.Search,
                if (state.searching) "关闭搜索" else "搜索",
                onToggleSearch, glyph = 26.dp,
            )
        }
    }
}

@Composable
private fun ToolsGroup(group: ToolsGroupSpec, query: String, brief: Boolean, onOpen: (ToolsEntry) -> Unit, stagger: HetuStaggerState, index: Int) {
    val c = LocalHomeColors.current
    Column(Modifier.fillMaxWidth().padding(bottom = HomeDims.gap).homeEnter(stagger, index), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (group.title != null) {
            Text(ht(group.title), Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp), color = c.t2, style = HomeType.noteStrong)
        }
        HomeCard(Modifier.fillMaxWidth()) {
            group.entries.forEach { entry ->
                val title = ht(entry.title)
                ToolsRow(
                    title = toolsHighlighted(title, ToolsCatalog.highlight(title, query)),
                    icon = entry.icon(),
                    subtitle = ht(if (brief) entry.brief else entry.summary),
                    subtitleMaxLines = 1,
                    onClick = { onOpen(entry) },
                    trailing = { ToolsChevron() },
                )
            }
        }
    }
}
