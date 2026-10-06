package io.github.xgl34222220.hetu

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import io.github.xgl34222220.hetu.home.HetuHomeTheme
import io.github.xgl34222220.hetu.panel.*
import io.github.xgl34222220.hetu.ui.LocalHetuMotionEnabled
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Actual launcher adapter and PanelRoute interactions; no copy of the scroll-anchor algorithm. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "zh-rCN-w393dp-h852dp-mdpi", application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PanelExpansionAnchorTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private var vm: HetuViewModel? = null
    private var latestView = PanelViewState()

    @Before fun prepare() {
        app.getSharedPreferences("hetu", 0).edit().clear()
            .putString("appearance", "light").putBoolean("enableBlur", false).commit()
        app.getSharedPreferences("proxy_selector_preferences", 0).edit().clear().commit()
    }

    @After fun finish() { vm?.viewModelScope?.cancel() }

    private fun name(index: Int) = "策略" + index.toString().padStart(2, '0')
    private fun nodeName(group: Int, node: Int) = "节点${group.toString().padStart(2, '0')}-$node"
    private fun groups(count: Int = 20, firstNodes: Int = 2): List<PanelGroup> = (1..count).map { group ->
        val nodes = (1..if (group == 1) firstNodes else 2).map { PanelNode(nodeName(group, it), protocol = "VLESS") }
        PanelGroup(name(group), "Selector", nodes, nodes.first().name)
    }

    private fun card(index: Int) = rule.onNodeWithTag("panel-group:${name(index)}", useUnmergedTree = true)
    private fun list() = rule.onNode(hasScrollToIndexAction())
    private fun settle() { rule.mainClock.advanceTimeBy(1000); rule.waitForIdle() }
    private fun expectOpen(index: Int, open: Boolean) {
        card(index).assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, if (open) "已展开" else "已收起"))
    }
    private fun expectSameTop(index: Int, before: Float) {
        card(index).assertIsDisplayed()
        assertEquals("Tapped group header must retain its visible position", before, card(index).getUnclippedBoundsInRoot().top.value, 2f)
    }

    private fun route(data: PanelData, initial: PanelViewState = PanelViewState(), fontScale: Float = 1f) {
        latestView = initial
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalHetuMotionEnabled provides true,
                LocalDensity provides Density(density.density, fontScale)) {
                HetuHomeTheme(dark = false) {
                    PanelRoute(data, PanelTab.Groups, {}, PanelActions(), initialView = initial,
                        onViewChange = { latestView = it }, contentPadding = PaddingValues(bottom = 80.dp))
                }
            }
        }
        settle()
    }

    @Suppress("UNCHECKED_CAST")
    private fun injectState(target: HetuViewModel, state: ProxyComposeState) {
        val field = HetuViewModel::class.java.getDeclaredField("state\$delegate").apply { isAccessible = true }
        (field.get(target) as MutableState<ProxyComposeState>).value = state
    }

    @Test fun launcherLowerHalfExpansionRetainsTheTappedGroupPosition() {
        val target = HetuViewModel(app).also { vm = it }
        target.openPanel("proxies")
        injectState(target, ProxyComposeState(running = true, panelReady = true, trafficMode = "rule",
            groups = groups().map { group -> ProxyGroupUi(group.name, group.type, group.now,
                group.nodes.map { ProxyNodeUi(it.name, it.protocol) }) }))
        rule.setContent {
            CompositionLocalProvider(LocalHetuMotionEnabled provides true) {
                HetuAppTheme(appearance = "light", dynamic = false) { HetuRoot(target) }
            }
        }
        settle()
        val before = card(9).getUnclippedBoundsInRoot()
        val rootBounds = rule.onRoot().getUnclippedBoundsInRoot()
        assertTrue("Fixture must exercise a lower-half card", before.top.value > (rootBounds.bottom.value - rootBounds.top.value) / 2f)
        assertTrue("Tapped card must clear the real dock", before.bottom <= rule.onNodeWithTag("hetu-dock", useUnmergedTree = true).getUnclippedBoundsInRoot().top)
        card(9).performClick()
        settle()
        expectSameTop(9, before.top.value)
        expectOpen(9, true)
        assertEquals("proxies", target.panelSection)
    }

    @Test fun collapsingAnEarlierLargeGroupKeepsTheClickedHeaderAnchored() {
        val data = PanelData(status = PanelStatus.Running, groups = groups(firstNodes = 40))
        val initial = PanelViewState(expandedGroups = listOf(name(1)),
            layout = PanelGroupLayout(groupColumns = 1, compactNodes = true))
        route(data, initial)
        // The first visible item is a node row that the next accordion transition removes.
        val lastNodeRow = PanelLogic.groupRows(data, initial).indexOfLast { it is PanelGroupRow.Nodes && it.group.name == name(1) }
        list().performScrollToIndex(panelHeaderItemCount(data, initial) + lastNodeRow)
        settle()
        rule.onNodeWithTag("panel-node:${nodeName(1, 40)}").assertIsDisplayed()
        val before = card(9).getUnclippedBoundsInRoot().top.value
        assertTrue("Fixture must retain a non-top header", before > 400f)
        card(9).performClick()
        settle()
        expectSameTop(9, before)
        expectOpen(9, true)
        assertEquals(listOf(name(9)), latestView.expandedGroups)
        rule.onNodeWithTag("panel-node:${nodeName(1, 40)}").assertDoesNotExist()
    }

    @Test fun rapidSiblingExpansionsLeaveOnlyTheLatestGroupWithoutAQueuedJump() {
        route(PanelData(status = PanelStatus.Running, groups = groups()))
        val before = card(10).getUnclippedBoundsInRoot().top.value
        val first = card(9).fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        val latest = card(10).fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        // Two actual card actions in one UI turn, before either layout/animation has finished.
        rule.runOnUiThread { assertTrue(first()); assertTrue(latest()) }
        settle()
        expectSameTop(10, before)
        expectOpen(9, false)
        expectOpen(10, true)
        assertEquals(listOf(name(10)), latestView.expandedGroups)
        rule.onNodeWithTag("panel-node:${nodeName(9, 1)}").assertDoesNotExist()
    }

    @Test @Config(qualifiers = "zh-rCN-w320dp-h640dp-mdpi")
    fun lastGroupOnASmallLargeFontScreenStaysVisibleAtItsOriginalOffset() {
        route(PanelData(status = PanelStatus.Running, groups = groups(count = 12)), fontScale = 1.6f)
        list().performScrollToNode(hasTestTag("panel-group:${name(12)}"))
        settle()
        val before = card(12).getUnclippedBoundsInRoot()
        assertTrue("Adaptive layout must keep the final card full-width", before.right.value - before.left.value >= 280f)
        card(12).performClick()
        settle()
        expectSameTop(12, before.top.value)
        expectOpen(12, true)
        assertEquals(listOf(name(12)), latestView.expandedGroups)
    }
}
