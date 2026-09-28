package io.github.xgl34222220.hetu

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import androidx.test.core.app.ApplicationProvider

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w393dp-h900dp-mdpi", application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class Ui92IntegrationTest {
    @get:Rule val rule = createComposeRule()
    @Test fun existingToolsPageRetainsUi92Entries() {
        rule.setContent { MaterialTheme { RefTools(ProxyComposeState(), {}) } }
        rule.onNodeWithText("运行文件").assertIsDisplayed()
        rule.onNodeWithText("应用名单").assertIsDisplayed()
        rule.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("内核管理"))
        rule.onNodeWithText("内核管理").assertIsDisplayed()
    }
    @Test fun settingsKeepUi92AndExpose146Functions() {
        rule.setContent { MaterialTheme { RefSettings(ProxyComposeState(), "", {}, {}) } }
        rule.onNodeWithText("更多功能设置").assertIsDisplayed().performClick()
        val next = org.robolectric.Shadows.shadowOf(ApplicationProvider.getApplicationContext<Application>()).nextStartedActivity
        assertEquals(Runtime146FeaturesActivity::class.java.name, next.component?.className)
    }
    @Test fun customLatencyNamesRenderWithoutChangingThreeColumnLayout() {
        val names = listOf("服务一", "服务二", "服务三")
        rule.setContent { MaterialTheme {
            CompactHomeDashboard(CompactHomeData(running = true, config = "fixture.yaml", latencyTargets = names, delays = names.associateWith { 50L }),
                {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, motionEnabled = false)
        } }
        names.forEach { rule.onNodeWithText(it).assertIsDisplayed() }
        val bounds = names.map { rule.onNodeWithTag("latency-$it").fetchSemanticsNode().boundsInRoot }
        val widths = bounds.map { it.width }
        // Equal Row weights distribute integer pixels. A remainder of 1 or 2 pixels
        // cannot be divided into three equal integers, so allow at most one pixel.
        println("Latency column physical widths: $widths")
        assertTrue("All three columns must retain positive width", widths.all { it > 0f })
        assertTrue("Equal-weight columns differ by no more than one physical pixel: $widths",
            widths.max() - widths.min() <= 1f)
        assertTrue("Columns must not overlap", bounds.zipWithNext().all { (a, b) -> a.right <= b.left })
    }
}
