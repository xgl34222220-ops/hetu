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
        val widths = names.map { rule.onNodeWithTag("latency-$it").fetchSemanticsNode().boundsInRoot.width }
        assertEquals(widths[0], widths[1], .5f); assertEquals(widths[1], widths[2], .5f)
    }
}
