package io.github.xgl34222220.hetu

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import io.github.xgl34222220.hetu.home.*
import io.github.xgl34222220.hetu.panel.*
import io.github.xgl34222220.hetu.ui.HetuStaggerState
import io.github.xgl34222220.hetu.ui.LocalHetuMotionEnabled
import java.io.File
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** New screenshot-feedback regression coverage; existing acceptance assertions are unchanged. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "zh-rCN-w393dp-h852dp-mdpi", application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PanelCompactAlignment94Test {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val longNode = "Hong Kong 04 两年套餐 专线超长节点名称 完整信息"
    private val groups = listOf(
        PanelGroup("短组", "Selector", listOf(PanelNode("短节点")), "短节点"),
        PanelGroup("香港节点非常长的策略组名称", "URLTest", listOf(PanelNode(longNode)), longNode),
        PanelGroup("第三组", "Fallback", listOf(PanelNode("节点选择")), "节点选择"),
        PanelGroup("第四组", "Selector", listOf(PanelNode("Singapore 03 长节点")), "Singapore 03 长节点"),
    )
    private var data by mutableStateOf(PanelData(groups = groups,
        delays = groups.associate { it.now to PanelDelay.Ms(44) }))
    private var clicks = 0
    private var expansions = 0

    @Before fun prepare() {
        ApplicationProvider.getApplicationContext<Application>().getSharedPreferences("hetu", 0)
            .edit().clear().putBoolean("enableBlur", false).putBoolean("enableAnimations", false).commit()
    }

    private fun render(scale: Float = 1f, content: @Composable () -> Unit) {
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalHetuMotionEnabled provides false,
                LocalDensity provides Density(density.density, scale)) {
                HetuHomeTheme(dark = false) {
                    CompositionLocalProvider(LocalHomeMotionEnabled provides false) { content() }
                }
            }
        }
        rule.waitForIdle()
    }

    private fun cards(scale: Float = 1f) = render(scale) {
        LazyColumn(Modifier.fillMaxSize()) {
            panelGroupsTab(groups.chunked(2).map { PanelGroupRow.Cards(it) }, data, PanelViewState(),
                HetuStaggerState(true), { expansions++ }, { _, _ -> }, {}, { clicks++ }, {})
        }
    }

    private fun card(name: String) = rule.onNodeWithTag("panel-group:$name")
    private fun delay(name: String) = rule.onNode(
        hasClickAction() and hasAnyAncestor(hasTestTag("panel-group:$name")) and
            (hasText("44 ms") or hasText("测速中")))

    private fun assertEqualCards() {
        val sizes = groups.map { card(it.name).getUnclippedBoundsInRoot().let { bounds -> bounds.bottom - bounds.top } }
        sizes.forEach { assertEquals("All rows, titles and selected-node lengths use the same density", sizes.first(), it) }
        groups.forEach {
            val bounds = card(it.name).getUnclippedBoundsInRoot()
            val action = delay(it.name)
            action.assertIsDisplayed().assertHeightIsAtLeast(40.dp)
            val actionBounds = action.getUnclippedBoundsInRoot()
            assertTrue(actionBounds.left >= bounds.left && actionBounds.right <= bounds.right)
            assertTrue(actionBounds.top >= bounds.top && actionBounds.bottom <= bounds.bottom)
        }
    }

    private fun capture(name: String) = rule.runOnIdle {
        val view = rule.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        val output = File("build/outputs/ui93/feedback-2230-$name.png")
        output.parentFile!!.mkdirs()
        output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    @Test fun longAndShortNamesAcrossRowsStayAtSmallCardHeightThroughBusyAndResultStates() {
        cards()
        assertEqualCards()
        val before = groups.map { card(it.name).getUnclippedBoundsInRoot() }
        assertTrue("Use the small card rather than stretching every card to the old long-name variant", before.first().bottom - before.first().top <= 110.dp)
        // Ellipsis affects painting only: the complete original node name is still accessible.
        rule.onNodeWithText(longNode, useUnmergedTree = true).assertExists()
        capture("compact-idle")
        rule.runOnIdle { data = data.copy(testingGroups = setOf(groups[1].name, groups[2].name)) }
        assertEqualCards()
        assertEquals(before, groups.map { card(it.name).getUnclippedBoundsInRoot() })
        repeat(3) { delay(groups[1].name).performTouchInput { click() } }
        assertEquals(0, clicks)
        assertEquals(0, expansions)
        capture("compact-mixed-busy")
        rule.runOnIdle { data = data.copy(testingGroups = emptySet()) }
        assertEquals(before, groups.map { card(it.name).getUnclippedBoundsInRoot() })
        delay(groups[1].name).performTouchInput { click() }
        assertEquals(1, clicks)
        assertEquals(0, expansions)
    }

    @Test fun switchingAndUnknownResultsDoNotChangeCompactCardGeometry() {
        cards()
        val before = groups.map { card(it.name).getUnclippedBoundsInRoot() }
        rule.runOnIdle { data = data.copy(switching = mapOf(groups[1].name to groups[1].now), delays = emptyMap()) }
        assertEquals(before, groups.map { card(it.name).getUnclippedBoundsInRoot() })
        rule.onNodeWithText(longNode, useUnmergedTree = true).assertExists()
        capture("compact-switching-unknown")
    }

    @Test fun largeFontUsesOneUniformAdaptiveLayoutWithoutClippingTheDelayTargets() {
        cards(scale = 2f)
        assertEqualCards()
        val before = groups.map { card(it.name).getUnclippedBoundsInRoot() }
        rule.runOnIdle { data = data.copy(testingGroups = groups.map { it.name }.toSet()) }
        assertEqualCards()
        assertEquals(before, groups.map { card(it.name).getUnclippedBoundsInRoot() })
        groups.forEach { group ->
            val name = rule.onNode(hasText(group.now) and hasAnyAncestor(hasTestTag("panel-group:${group.name}")), useUnmergedTree = true)
            assertTrue("Scaled name and result target occupy separate rows", name.getUnclippedBoundsInRoot().bottom <= delay(group.name).getUnclippedBoundsInRoot().top)
        }
        capture("compact-font-2x")
    }

    private fun rules(scale: Float) {
        val items = listOf(PanelRule("DomainSuffix", "stun.mi.wifi.com", "REJECT"),
            PanelRule("DomainRegex", "very.long.domain.and.payload.example.com/another/long/part", "香港节点非常长的策略动作名称"))
        render(scale) {
            LazyColumn(Modifier.fillMaxSize()) { panelRulesTab(items, "", items.size, HetuStaggerState(true)) }
        }
        for (index in items.indices) {
            val card = rule.onNodeWithTag("panel-rule:$index").getUnclippedBoundsInRoot()
            val content = rule.onNodeWithTag("panel-rule-content:$index").getUnclippedBoundsInRoot()
            val action = rule.onNodeWithTag("panel-rule-policy:$index")
            action.assertIsDisplayed()
            val actionBounds = action.getUnclippedBoundsInRoot()
            assertEquals("Policy hugs the card's trailing padding", card.right - HomeDims.cardPadding, actionBounds.right)
            assertEquals(card.left + HomeDims.cardPadding, content.left)
            assertTrue(content.right + 12.dp <= actionBounds.left)
            assertTrue(actionBounds.top >= card.top && actionBounds.bottom <= card.bottom)
            action.assertTextEquals(items[index].policy)
            rule.onNodeWithText(items[index].payload, useUnmergedTree = true).assertExists()
        }
        capture("rules-font-$scale")
    }

    @Test fun rulePoliciesAlignAtTheRightEdgeForShortAndLongContent() = rules(1f)
    @Test fun largeFontRulesKeepContentAndPolicySeparateAndInsideTheirCards() = rules(2f)
}
