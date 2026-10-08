package io.github.xgl34222220.hetu

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import io.github.xgl34222220.hetu.home.*
import io.github.xgl34222220.hetu.panel.*
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

/** New recovery acceptance tests, not a reconstruction of the missing original touch suite. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "zh-rCN-w393dp-h852dp-mdpi", application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PanelTouchFeedback93Test {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val node = PanelNode("香港 专线 Alpha 长节点名称", protocol = "VLESS")
    private val group = PanelGroup("主策略长名称", "Selector", listOf(node), node.name)
    private var data by mutableStateOf(PanelData(status = PanelStatus.Running, groups = listOf(group),
        delays = mapOf(node.name to PanelDelay.Ms(44))))
    private var tests = 0
    private var selections = 0

    @Before fun prepare() {
        ApplicationProvider.getApplicationContext<Application>().getSharedPreferences("hetu", 0)
            .edit().clear().putBoolean("enableBlur", false).commit()
    }

    private fun render(expanded: Boolean = false, compact: Boolean = false, scale: Float = 1f) {
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalHetuMotionEnabled provides false,
                LocalDensity provides Density(density.density, scale)) {
                HetuHomeTheme(dark = false) {
                    PanelRoute(data, PanelTab.Groups, {}, PanelActions(
                        onTestGroup = { tests++; data = data.copy(testingGroups = setOf(it)) },
                        onTestNode = { tests++; data = data.copy(delays = mapOf(it to PanelDelay.Testing)) },
                        onSelectNode = { _, _ -> selections++ }),
                        initialView = PanelViewState(expandedGroups = if (expanded) listOf(group.name) else emptyList(),
                            layout = PanelGroupLayout(compactNodes = compact)),
                        contentPadding = PaddingValues(bottom = 100.dp))
                }
            }
        }
        rule.mainClock.advanceTimeBy(1_000)
        rule.waitForIdle()
    }

    private fun groupState(open: Boolean) = rule.onNodeWithTag("panel-group:${group.name}")
        .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, if (open) "已展开" else "已收起"))

    private fun delay(text: String, parent: String) = rule.onNode(hasText(text) and hasClickAction() and hasAnyAncestor(hasTestTag(parent)))

    private fun capture(name: String) {
        rule.runOnIdle {
            val view = rule.activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            val output = File("build/outputs/ui93/$name.png")
            output.parentFile!!.mkdirs()
            output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }

    @Test fun collapsedGroupDelayRunsMeasurementWithoutExpanding() {
        render()
        delay("44 ms", "panel-group:${group.name}").performTouchInput { click() }
        assertEquals(1, tests)
        groupState(false)
        delay("测速中", "panel-group:${group.name}").assertIsNotEnabled()
        capture("group-collapsed-measuring")
    }

    @Test fun busyGroupPhysicalTapsAreConsumedWithoutExpansionOrDuplicateRequest() {
        data = data.copy(testingGroups = setOf(group.name))
        render()
        val busy = delay("测速中", "panel-group:${group.name}")
        repeat(3) { busy.performTouchInput { click() } }
        assertEquals(0, tests)
        groupState(false)
        rule.onNodeWithTag("panel-group:${group.name}").performTouchInput { click(androidx.compose.ui.geometry.Offset(18f, 18f)) }
        groupState(true)
    }

    @Test fun busyNodePhysicalTapsCannotSelectItsParentCard() {
        data = data.copy(delays = mapOf(node.name to PanelDelay.Testing))
        render(expanded = true)
        repeat(3) { delay("测速中", "panel-node:${node.name}").performTouchInput { click() } }
        assertEquals(0, tests)
        assertEquals(0, selections)
        rule.onNodeWithTag("panel-node:${node.name}").performTouchInput { click(androidx.compose.ui.geometry.Offset(18f, 18f)) }
        assertEquals(1, selections)
    }

    @Test fun failedMeasurementStaysDistinctFromTimeoutAndIsRetryable() {
        data = data.copy(delays = mapOf(node.name to PanelDelay.Failed))
        render(expanded = true)
        delay("失败", "panel-node:${node.name}").assertIsEnabled()
        rule.onNodeWithText("超时").assertDoesNotExist()
        capture("node-measurement-failed")
        delay("失败", "panel-node:${node.name}").performTouchInput { click() }
        assertEquals(1, tests)
        assertEquals(0, selections)
        delay("测速中", "panel-node:${node.name}").assertIsNotEnabled()
    }

    @Test fun compactLargeFontNodeHasIndependentReadableDelayRow() {
        render(expanded = true, compact = true, scale = 1.6f)
        val parent = "panel-node:${node.name}"
        val name = rule.onNode(hasText(node.name) and hasAnyAncestor(hasTestTag(parent)), useUnmergedTree = true)
        val label = delay("44 ms", parent)
        name.assertIsDisplayed()
        label.assertIsDisplayed()
        val nameBounds = name.getUnclippedBoundsInRoot()
        val delayBounds = label.getUnclippedBoundsInRoot()
        assertTrue("Long name and delay touch target must not overlap", nameBounds.bottom <= delayBounds.top)
        label.performTouchInput { click() }
        assertEquals(1, tests)
        assertEquals(0, selections)
        capture("compact-large-font-node")
    }

    @Test fun resultTransitionKeepsItsTouchOwnershipUntilTheCaptionSettles() {
        var result by mutableStateOf<PanelDelay>(PanelDelay.Testing)
        var parentClicks = 0
        rule.mainClock.autoAdvance = false
        rule.setContent {
            HetuHomeTheme(dark = false) {
                CompositionLocalProvider(LocalHomeMotionEnabled provides true) {
                    Box(Modifier.padding(24.dp).homeTap { parentClicks++ }) {
                        PanelDelayLabel(result, Modifier.testTag("transition-delay"), onClick = { tests++ })
                    }
                }
            }
        }
        rule.mainClock.advanceTimeBy(32)
        rule.runOnIdle { result = PanelDelay.Ms(68) }
        rule.mainClock.advanceTimeBy(32)
        val label = rule.onNodeWithTag("transition-delay")
        label.assertIsNotEnabled().performTouchInput { click() }
        assertEquals(0, tests)
        assertEquals(0, parentClicks)
        rule.mainClock.advanceTimeBy(400)
        label.assertIsEnabled()
        rule.onNodeWithText("68 ms").assertIsDisplayed()
        rule.onNodeWithText("测速中").assertDoesNotExist()
        label.performTouchInput { click() }
        assertEquals(1, tests)
        assertEquals(0, parentClicks)
        capture("delay-result-settled")
    }
}
