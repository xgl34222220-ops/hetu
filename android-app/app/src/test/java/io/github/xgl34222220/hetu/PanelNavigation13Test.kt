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
import dev.chrisbanes.haze.HazeState
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
class PanelNavigation13Test {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val prefs get() = context.getSharedPreferences("hetu", 0)
    private val delays = mutableStateMapOf<String, Long>()
    private val calls = mutableListOf<String>()
    private val nodes = listOf(ProxyNodeUi("Alpha", "VLESS", true, 60), ProxyNodeUi("Beta", "Trojan", true, 90))
    private fun group(name: String) = ProxyGroupUi(name, "Selector", "Alpha", nodes)
    private fun fixture() = ProxyComposeState(running = true, trafficMode = "rule", groups = listOf(group("YouTube"), group("TikTok")))
    @Before fun clear() { prefs.edit().clear().commit(); delays.clear(); calls.clear() }
    private fun node(tag: String) = rule.onNodeWithTag(tag, useUnmergedTree = !tag.startsWith("panel11-tab-"))
    private fun groupNode(name: String = "YouTube") = node("panel11-group:$name")
    private fun tile(name: String, group: String = "YouTube") = node("panel11-node:$group:$name")
    private fun open(name: String = "YouTube") { groupNode(name).performTouchInput { click() } }
    private fun back() { node("panel13-back").performTouchInput { click() } }
    private fun render(value: State<ProxyComposeState> = mutableStateOf(fixture()), motion: Boolean = false,
        action: suspend (String, String) -> String = { g, n -> calls += "$g:$n"; n }) {
        rule.setContent { CompositionLocalProvider(LocalHetuMotionEnabled provides motion, LocalHomeDockClearance provides 96.dp) {
            MaterialTheme { ModalBackdropHost12 {
                StrategyPanel11(value.value, delays, prefs, onTab = { calls += it.label }, refresh = { calls += "refresh" },
                    select = action, measure = { 140L })
            } }
        } }
    }
    private fun screenshot(name: String) { rule.runOnIdle {
        val view = rule.activity.window.decorView
        val image = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(image)); val file = File("build/outputs/panel13/$name.png"); file.parentFile!!.mkdirs()
        file.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }; image.recycle()
    } }
    @Test fun actualSixPageRoutesKeepIdenticalLabelsOrderAndBounds() {
        rule.setContent { CompositionLocalProvider(LocalHetuMotionEnabled provides false, LocalHomeDockClearance provides 96.dp) {
            MaterialTheme {
                var tab by remember { mutableStateOf(RefPanelTab.Overview) }
                RefPanel(ProxyComposeState(), remember { ProxyDashboardRepository(context) }, delays, tab, { tab = it },
                    0, remember { HazeState() }, null, false, {}, {}, {})
            }
        } }
        val labels = listOf("概览", "节点", "订阅", "连接", "规则", "规则集")
        val bounds = PanelTabs13.map { node("panel11-tab-${it.name}").getUnclippedBoundsInRoot() }
        repeat(2) { PanelTabs13.forEach { selected ->
            node("panel11-tab-${selected.name}").performTouchInput { click() }
            PanelTabs13.forEachIndexed { i, tab ->
                node("panel11-tab-${tab.name}").assertTextEquals(labels[i])
                    .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab))
                assertEquals(bounds[i], node("panel11-tab-${tab.name}").getUnclippedBoundsInRoot())
                assertEquals(tab == selected, node("panel11-tab-${tab.name}").fetchSemanticsNode().config[SemanticsProperties.Selected])
            }
            rule.onNodeWithText("策略", substring = false).assertDoesNotExist()
        } }
    }
    @Test fun tenGroupsFitInTwoColumnsWithoutAccordionsOrFloatingControl() {
        render(mutableStateOf(fixture().copy(groups = (1..10).map { group("策略 $it") })))
        (1..10).forEach { groupNode("策略 $it").assertIsDisplayed() }
        val a = groupNode("策略 1").getUnclippedBoundsInRoot(); val b = groupNode("策略 2").getUnclippedBoundsInRoot()
        assertEquals(a.top, b.top); assertEquals(10.dp, b.left-a.right)
        assertTrue(a.height in 70.dp..84.dp)
        node("panel11-fab").assertDoesNotExist(); node("panel13-group-sheet").assertDoesNotExist()
        screenshot("ten-groups")
    }
    @Test fun outerNodeCountsRemainRealWhenSearchMatchesOnlyOneChild() {
        render(); node("panel11-search-toggle").performTouchInput { click() }
        node("panel11-search-input").performTextInput("Beta")
        rule.onAllNodesWithText("Selector · 已测 2/2").assertCountEquals(2)
        open(); tile("Alpha").assertIsDisplayed(); tile("Beta").assertIsDisplayed()
    }
    @Test fun secondaryDestinationHasFixedTitleAndAllTestAction() {
        val huge = fixture().copy(groups = listOf(group("YouTube").copy(nodes = (1..1000).map { ProxyNodeUi("N$it", "VLESS") })))
        render(mutableStateOf(huge)); open()
        val before = node("panel13-detail-header").getUnclippedBoundsInRoot()
        node("panel13-node-list").performScrollToNode(hasTestTag("panel11-node:YouTube:N1000"))
        tile("N1000").assertIsDisplayed(); node("panel11-test-all:YouTube").assertIsDisplayed()
        assertEquals(before, node("panel13-detail-header").getUnclippedBoundsInRoot())
    }
    @Test fun acknowledgedNodeAndMeasuredDelaySyncToParentWithoutRefresh() {
        render(); open(); tile("Beta").performTouchInput { click(Offset(15f,12f)) }
        tile("Beta").assertIsSelected()
        node("panel11-delay:YouTube:Beta").performTouchInput { click() }
        back(); node("panel11-current:YouTube").assertTextEquals("Beta")
        groupNode().assert(hasAnyDescendant(hasText("140 ms")))
        assertEquals(listOf("YouTube:Beta"), calls)
    }
    @Test fun pendingSelectionSurvivesLeavingDetailAndNeverFakesSuccess() {
        val gate = CompletableDeferred<String>()
        render(action = { _, _ -> gate.await() }); open(); tile("Beta").performTouchInput { click(Offset(15f,12f)) }
        back(); node("panel11-current:YouTube").assertTextEquals("Alpha")
        rule.runOnIdle { gate.complete("Beta") }; node("panel11-current:YouTube").assertTextEquals("Beta")
    }
    @Test fun unexpectedCoreAcknowledgementKeepsOldCheckAndParentSelection() {
        render(action = { _, _ -> "Alpha" }); open(); tile("Beta").performTouchInput { click(Offset(15f,12f)) }
        tile("Alpha").assertIsSelected(); tile("Beta").assertIsNotSelected()
        node("panel13-detail-notice").assertIsDisplayed(); back()
        node("panel11-current:YouTube").assertTextEquals("Alpha")
    }
    @Test fun removingActiveGroupClosesOnlyItsSecondaryDestination() {
        val state = mutableStateOf(fixture()); render(state); open()
        rule.runOnIdle { state.value = fixture().copy(groups = listOf(group("TikTok"))) }
        node("panel13-group-sheet").assertDoesNotExist(); groupNode("TikTok").assertIsDisplayed()
    }
    @Test fun parentGridGeometryIsUnaffectedByTwoThousandNodes() {
        val large = group("YouTube").copy(nodes = (1..2000).map { ProxyNodeUi("N$it", "VLESS") })
        render(mutableStateOf(fixture().copy(groups = listOf(large, group("TikTok")))))
        val before = groupNode("TikTok").getUnclippedBoundsInRoot(); open(); back()
        assertEquals(before, groupNode("TikTok").getUnclippedBoundsInRoot())
        tile("N2000").assertDoesNotExist()
    }
    @Test fun secondarySearchDoesNotChangePrimaryTabOrParentSearch() {
        render(); open(); node("panel13-node-search").performTextInput("Beta")
        tile("Alpha").assertDoesNotExist(); tile("Beta").assertExists(); back()
        node("panel11-tab-Groups").assertTextEquals("节点").assertIsSelected()
        groupNode("TikTok").assertIsDisplayed(); open(); tile("Alpha").assertExists()
    }
    @Test fun nestedGroupBackStackIsIndependentAndCycleBounded() {
        assertEquals(listOf("a", "b"), panelPush13(listOf("a"), "b"))
        assertEquals(listOf("a"), panelPush13(listOf("a", "b"), "a"))
        val parent = group("YouTube").copy(nodes = listOf(ProxyNodeUi("TikTok", "Selector")))
        render(mutableStateOf(fixture().copy(groups = listOf(parent, group("TikTok"))))); open()
        rule.onNodeWithContentDescription("展开子策略TikTok").performTouchInput { click() }
        node("panel13-group-title").assertTextEquals("TikTok"); back()
        node("panel13-group-title").assertTextEquals("YouTube"); back()
        node("panel13-group-sheet").assertDoesNotExist()
    }
    @Test fun brandedAndExplicitFlagCardsKeepDistinctIcons() {
        render(mutableStateOf(fixture().copy(groups = fixture().groups + group("🇯🇵 日本"))))
        node("brand-icon:YouTube").assertIsDisplayed(); node("brand-icon:TikTok").assertIsDisplayed()
        node("group-flag:🇯🇵 日本").assertIsDisplayed()
        assertEquals("tiktok", builtInBrandKey("TikTok")); assertEquals("tiktok", builtInBrandKey("抖音"))
        assertNull(explicitGroupFlag13("Tokyo node")); assertEquals("🇯🇵", explicitGroupFlag13("🇯🇵 Japan"))
        screenshot("brand-grid")
    }
    @Test fun stoppedCoreDoesNotAcceptNodeChangesOrMeasurements() {
        render(mutableStateOf(fixture().copy(running = false))); open()
        tile("Beta").performTouchInput { click(Offset(15f,12f)) }
        node("panel11-test-all:YouTube").assertIsNotEnabled()
        assertTrue(calls.isEmpty()); tile("Alpha").assertIsSelected()
    }
    @Test fun tabsStayFixedWhenSearchIsOpenedAndClosed() {
        render(); val before = node("panel11-subtabs").getUnclippedBoundsInRoot()
        node("panel11-search-toggle").performTouchInput { click() }
        assertEquals(before, node("panel11-subtabs").getUnclippedBoundsInRoot())
        node("panel11-search-toggle").performTouchInput { click() }
        assertEquals(before, node("panel11-subtabs").getUnclippedBoundsInRoot())
    }
    @Test fun sheetEntranceAndExitActuallyRetainIntermediateFrames() {
        render(motion = true); rule.mainClock.autoAdvance = false; open()
        rule.mainClock.advanceTimeBy(96); rule.waitForIdle()
        node("panel13-group-sheet").assertExists()
        rule.mainClock.advanceTimeBy(1000); rule.waitForIdle(); node("panel13-back").performTouchInput { click() }
        rule.mainClock.advanceTimeBy(32); rule.waitForIdle(); node("panel13-group-sheet").assertExists()
        rule.mainClock.advanceTimeBy(1000); rule.waitForIdle(); node("panel13-group-sheet").assertDoesNotExist()
    }
    @Test @Config(qualifiers = "w320dp-h640dp-mdpi") fun smallWindowKeepsAllPrimaryLabelsAccessible() {
        rule.setContent { val d=LocalDensity.current
            CompositionLocalProvider(LocalHetuMotionEnabled provides false, LocalDensity provides Density(d.density, 1.7f)) {
                MaterialTheme { FixedPanelTabs13(RefPanelTab.Groups) {} }
            }
        }
        PanelTabs13.forEach { tab -> node("panel11-tab-${tab.name}").performScrollTo().assertIsDisplayed().assertTextEquals(tab.label) }
    }
}
