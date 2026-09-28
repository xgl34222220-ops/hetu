package io.github.xgl34222220.hetu

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.*
import androidx.test.core.app.ApplicationProvider
import io.github.xgl34222220.hetu.ui.LocalHetuMotionEnabled
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w393dp-h852dp-mdpi", application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PanelUi11Test {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val prefs get() = context.getSharedPreferences("hetu", 0)
    private val calls = mutableListOf<String>()
    private val delays = mutableStateMapOf<String, Long>()
    private val nodes = listOf(ProxyNodeUi("Alpha", "VLESS", true, 80, "P1"), ProxyNodeUi("Beta", "VMess", false, 40, "P2"), ProxyNodeUi("Gamma", "Trojan", true, null, "P1"))
    private fun group(name: String) = ProxyGroupUi(name, "Selector", "Alpha", nodes)
    private fun fixture() = ProxyComposeState(running = true, trafficMode = "rule", groups = listOf(group("节点选择"), group("视频"), group("隐藏").copy(hidden = true), group("GLOBAL")))
    @Before fun clear() { prefs.edit().clear().commit(); calls.clear(); delays.clear() }
    private fun render(state: ProxyComposeState = fixture(), scale: Float = 1f,
        select: suspend (String, String) -> String = { group, node -> calls += "select:$group:$node"; node },
        measure: suspend (String) -> Long = { name -> calls += "delay:$name"; 125L }) {
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalHetuMotionEnabled provides false,
                LocalDensity provides Density(density.density, scale), LocalHomeDockClearance provides 96.dp) {
                MaterialTheme {
                    StrategyPanel11(state, delays, prefs, onTab = { calls += "tab:${it.name}" },
                        refresh = { calls += "refresh" }, select = select, measure = measure)
                }
            }
        }
    }
    private fun node(tag: String) = rule.onNodeWithTag(tag, useUnmergedTree = true)
    private fun groupNode(name: String = "节点选择") = node("panel11-group:$name")
    private fun tile(name: String, group: String = "节点选择") = node("panel11-node:$group:$name")
    private fun expand(name: String = "节点选择") { groupNode(name).performTouchInput { click() } }
    private fun back() { node("panel13-back").performTouchInput { click() } }
    private fun selectTile(name: String) { tile(name).performTouchInput { click(Offset(18f, 14f)) } }
    private fun snapshot(name: String) {
        rule.waitForIdle()
        rule.runOnIdle {
            val view = rule.activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            val path = File("build/outputs/panel11/$name.png"); path.parentFile!!.mkdirs()
            path.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }

    @Test fun toolbarHasFourRealActionsAndIndependentSubtabs() {
        render()
        for (tag in listOf("search-toggle", "filter", "layout", "settings")) node("panel11-$tag").assertIsDisplayed().assertWidthIsEqualTo(48.dp)
        node("panel11-tab-Connections").performTouchInput { click() }
        assertEquals(listOf("tab:Connections"), calls)
        snapshot("toolbar")
    }
    @Test fun secondarySheetDoesNotExpandOrMoveTheParentGrid() {
        render()
        val before = groupNode("视频").getUnclippedBoundsInRoot()
        expand(); tile("Alpha").assertIsDisplayed(); tile("Beta").assertIsDisplayed()
        node("panel13-group-sheet").assertIsDisplayed()
        val a = tile("Alpha").getUnclippedBoundsInRoot(); val b = tile("Beta").getUnclippedBoundsInRoot()
        assertEquals(a.top, b.top); assertTrue(a.right < b.left)
        back(); assertEquals(before, groupNode("视频").getUnclippedBoundsInRoot())
        node("panel13-group-sheet").assertDoesNotExist(); snapshot("group-grid")
    }
    @Test fun differentGroupsHaveIndependentSecondaryDestinations() {
        render(); expand(); back(); expand("视频")
        tile("Alpha", "视频").assertIsDisplayed(); tile("Alpha").assertDoesNotExist()
        node("panel13-group-title").assertTextEquals("视频")
    }
    @Test fun obsoleteAccordionPreferenceCannotChangeNewNavigation() {
        prefs.edit().putBoolean("proxySelectorCollapsePrevious", false).commit()
        render(); node("panel11-filter").performTouchInput { click() }
        node("panel11-exclusive").assertDoesNotExist()
        node("panel11-filter").performClick()
        expand(); back(); expand("视频"); tile("Alpha", "视频").assertIsDisplayed()
        node("panel11-fab").assertIsDisplayed()
    }
    @Test fun searchMatchesActualNodeAndHidesOtherChildren() {
        render(); node("panel11-search-toggle").performTouchInput { click() }
        node("panel11-search-input").performTextInput("Beta")
        tile("Beta").assertDoesNotExist(); groupNode().assertIsDisplayed()
        expand(); node("panel13-node-search").performTextInput("Beta")
        tile("Beta").assertExists(); tile("Alpha").assertDoesNotExist()
        snapshot("secondary-search")
    }
    @Test fun filterButtonActuallyChangesHiddenVisibilityAndStoredPreference() {
        render(); groupNode("隐藏").assertDoesNotExist()
        node("panel11-filter").performTouchInput { click() }
        node("panel11-hidden").performClick()
        assertTrue(prefs.getBoolean("proxySelectorShowHidden", false))
        groupNode("隐藏").assertExists()
    }
    @Test fun layoutColumnAndDensitySwitchesHaveActualGeometricEffects() {
        render(); expand(); val dualHeight = tile("Alpha").getUnclippedBoundsInRoot().height
        back(); node("panel11-layout").performTouchInput { click() }
        node("panel11-columns").performClick(); node("panel11-density").performClick()
        assertEquals(1, prefs.getInt("proxySelectorNodeColumns", 0))
        assertEquals("compact", prefs.getString("proxySelectorDensity", ""))
        node("panel11-layout").performClick()
        expand()
        val a = tile("Alpha").getUnclippedBoundsInRoot(); val b = tile("Beta").getUnclippedBoundsInRoot()
        assertEquals(a.left, b.left); assertTrue(b.top >= a.bottom); assertTrue(a.height < dualHeight)
    }
    @Test fun sortMenuReordersVisibleTilesAndProviderSwitchAddsRealHeadings() {
        render(); node("panel11-layout").performTouchInput { click() }
        node("panel11-sort-latency").performClick()
        node("panel11-layout").performClick()
        expand(); assertTrue(tile("Beta").getUnclippedBoundsInRoot().left < tile("Alpha").getUnclippedBoundsInRoot().left)
        rule.runOnIdle { prefs.edit().putBoolean("proxySelectorGroupByProvider", true).apply() }
        node("panel11-provider:节点选择:P2").assertExists()
    }
    @Test fun blueCheckAndProtocolBelongToActualSelectedNode() {
        render(); expand()
        tile("Alpha").assertIsSelected(); tile("Beta").assertIsNotSelected()
        node("panel11-check:节点选择:Alpha").assertIsDisplayed()
        node("panel11-protocol:节点选择:Alpha").assertTextEquals("VLESS · UDP")
        node("panel11-protocol:节点选择:Beta").assertTextEquals("VMESS")
    }
    @Test fun selectionWaitsForDelayedAcknowledgementAndThenUpdatesCheck() {
        val gate = CompletableDeferred<String>()
        render(select = { _, name -> calls += "select:$name"; gate.await() }); expand(); selectTile("Beta")
        tile("Alpha").assertIsSelected(); tile("Beta").assertIsNotSelected()
        tile("Beta").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "等待核心确认"))
        selectTile("Gamma"); assertEquals(listOf("select:Beta"), calls)
        rule.runOnIdle { gate.complete("Beta") }
        tile("Beta").assertIsSelected(); tile("Alpha").assertIsNotSelected()
    }
    @Test fun failedSelectionLeavesPreviousCheckAndShowsRealError() {
        render(select = { _, _ -> error("backend-denied") }); expand(); selectTile("Beta")
        tile("Alpha").assertIsSelected(); tile("Beta").assertIsNotSelected()
        rule.onNodeWithText("backend-denied").assertExists()
        node("panel13-detail-notice").assertIsDisplayed()
    }
    @Test fun delayTapDoesNotTriggerParentNodeSelection() {
        render(); expand(); node("panel11-delay:节点选择:Beta").performTouchInput { click() }
        assertEquals(listOf("delay:Beta"), calls); assertEquals(125L, delays["Beta"])
        tile("Alpha").assertIsSelected()
    }
    @Test fun groupTestUsesRealCallbacksForEachNodeAndNeverChangesSelection() {
        render(); expand(); node("panel11-test-all:节点选择").performClick()
        rule.waitForIdle()
        assertEquals(setOf("delay:Alpha", "delay:Beta", "delay:Gamma"), calls.toSet())
        tile("Alpha").assertIsSelected(); assertEquals(3, delays.size)
    }
    @Test fun nodeTransportErrorRetainsPreviousMeasurementRatherThanFakeTimeout() {
        delays["Beta"] = 88
        render(measure = { throw java.io.IOException("transport unavailable") }); expand()
        node("panel11-delay:节点选择:Beta").performClick()
        assertEquals(88L, delays["Beta"]); rule.onNodeWithText("transport unavailable").assertExists()
    }
    @Test fun longPressShowsCompleteNameWithoutChangingSelection() {
        render(); expand(); tile("Beta").performTouchInput { longClick(Offset(18f, 14f)) }
        node("native-details-sheet").assertIsDisplayed()
        rule.onNodeWithText("Beta\nVMess\n提供商：P2").assertExists()
        assertTrue(calls.isEmpty())
    }
    @Test fun noFloatingControlAndReturningPreservesGridPosition() {
        val many = fixture().copy(groups = (1..30).map { group("策略 $it") } + group("节点选择"))
        render(many); node("panel11-fab").assertIsDisplayed()
        node("panel11-list").performScrollToNode(hasTestTag("panel11-group:节点选择"))
        val before = groupNode().getUnclippedBoundsInRoot()
        expand(); tile("Alpha").assertIsDisplayed(); back()
        assertEquals(before, groupNode().getUnclippedBoundsInRoot())
    }
    @Test fun hugeGroupUsesLazyRowsAndKeepsSinglePageScrolling() {
        val large = group("节点选择").copy(nodes = (1..1000).map { ProxyNodeUi("N$it", "VLESS") }, now = "N1")
        render(fixture().copy(groups = listOf(large))); expand()
        rule.onAllNodes(hasTestTag("panel11-node:节点选择:N1000"), useUnmergedTree = true).assertCountEquals(0)
        node("panel13-node-list").performScrollToNode(hasTestTag("panel11-node:节点选择:N1000"))
        tile("N1000").assertIsDisplayed()
        node("panel13-detail-header").assertIsDisplayed()
    }
    @Test @Config(qualifiers = "w320dp-h640dp-mdpi") fun largeFontKeepsToolbarAndNodesReachableWithoutNestedGrid() {
        render(scale = 1.6f)
        for (tag in listOf("search-toggle", "filter", "layout", "settings")) node("panel11-$tag").assertIsDisplayed()
        expand(); node("panel13-back").assertIsDisplayed()
        node("panel13-node-list").performScrollToNode(hasTestTag("panel11-node:节点选择:Gamma"))
        tile("Gamma").assertIsDisplayed(); snapshot("320-large-font")
    }
    @Test fun apiSheetRejectsInvalidPortAndDoesNotSave() {
        render(); node("panel11-settings").performClick()
        node("panel11-custom-api").performClick()
        node("panel11-api-port").performScrollTo().performTextReplacement("0")
        node("panel11-api-save").performClick()
        node("panel11-api-error").assertExists()
        assertFalse(prefs.getBoolean("proxyCustomApiEnabled", false))
    }
    @Test fun apiSheetSavesActualPreferencesAndInvokesRefresh() {
        render(); node("panel11-settings").performClick()
        node("panel11-history").performClick()
        node("panel11-custom-delay").performClick()
        node("panel11-delay-url").performTextReplacement("https://example.com/probe")
        node("panel11-api-save").performClick()
        assertTrue(prefs.getBoolean("proxyApiHistoryEnabled", false))
        assertEquals("https://example.com/probe", prefs.getString("proxyCustomDelayUrl", ""))
        assertEquals(listOf("refresh"), calls)
    }
    @Test fun stoppedPanelHasNoFakeNodesAndCanRequestRealRefresh() {
        render(fixture().copy(running = false, groups = emptyList()))
        rule.onNodeWithText("代理未运行").assertIsDisplayed()
        node("panel11-fab").assertDoesNotExist()
        node("panel11-empty-refresh").performClick(); assertEquals(listOf("refresh"), calls)
    }
}
