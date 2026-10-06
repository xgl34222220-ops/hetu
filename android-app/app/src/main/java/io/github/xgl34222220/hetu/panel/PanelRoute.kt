package io.github.xgl34222220.hetu.panel

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import io.github.xgl34222220.hetu.home.HomeDims

/**
 * Stateful host of the panel module: owns the view state and the single floating layer.
 *
 * @param tab hoisted so the shell can deep-link (首页 › 当前节点 → 策略, 首页 › 订阅 → 订阅).
 * @param initialView persisted choices (display options, layout, API settings); read once.
 * @param onViewChange called with every new view state so the host can persist what it cares about.
 * @param expandGroup when non-null, that group is expanded on entry (首页 › 当前节点).
 */
@Composable
internal fun PanelRoute(
    data: PanelData,
    tab: PanelTab,
    onTabChange: (PanelTab) -> Unit,
    actions: PanelActions,
    modifier: Modifier = Modifier,
    initialView: PanelViewState = PanelViewState(),
    onViewChange: (PanelViewState) -> Unit = {},
    expandGroup: String? = null,
    contentPadding: PaddingValues = PaddingValues(bottom = HomeDims.dockClearance),
) {
    var view by remember { mutableStateOf(initialView.copy(tab = tab)) }
    var overlay by remember { mutableStateOf<PanelOverlay?>(null) }
    val listState = rememberLazyListState()

    // Shell-driven tab change (deep link): reset search like a tap on the tab strip would.
    LaunchedEffect(tab) { if (view.tab != tab) view = view.withTab(tab) }
    LaunchedEffect(expandGroup) {
        if (expandGroup != null && expandGroup !in view.expandedGroups) view = view.copy(expandedGroups = listOf(expandGroup))
    }
    // A new tab starts at the top; overlays never survive a tab change or the proxy stopping.
    LaunchedEffect(view.tab) { listState.scrollToItem(0); overlay = null }
    LaunchedEffect(data.running) { if (!data.running) overlay = null }

    BackHandler(enabled = view.searching) { view = view.toggleSearch() }

    PanelScreen(
        data = data,
        view = view,
        overlay = overlay,
        actions = actions,
        onView = { next ->
            val previousTab = view.tab
            view = next
            onViewChange(next)
            if (next.tab != previousTab) onTabChange(next.tab)
        },
        onOverlay = { overlay = it },
        modifier = modifier,
        contentPadding = contentPadding,
        listState = listState,
    )
}
