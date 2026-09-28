package io.github.xgl34222220.hetu

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import io.github.xgl34222220.hetu.ui.LocalHetuMotionEnabled
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@OptIn(ExperimentalTestApi::class)
internal fun SemanticsNodeInteraction.refreshHome11() =
    performCustomAccessibilityActionWithLabel("刷新首页状态")

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w393dp-h852dp-mdpi", application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HomeUi11Test {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val calls = mutableListOf<String>()
    private fun render() {
        rule.setContent { CompositionLocalProvider(LocalHetuMotionEnabled provides false) {
            MaterialTheme {
                CompactHomeDashboard(CompactHomeData(running = true, config = "fixture.yaml"),
                    { calls += "refresh" }, {}, {}, {}, { calls += "latency" }, {}, {}, {}, {}, {}, {}, {},
                    motionEnabled = false, contentInsets = WindowInsets(0.dp, 24.dp, 0.dp, 24.dp),
                    onPullRefresh = { calls += "pull" })
            }
        } }
    }
    @Test fun homeTopRightIsReallyEmptyWithNoHiddenButtons() {
        render()
        rule.onNodeWithContentDescription("刷新状态").assertDoesNotExist()
        rule.onNodeWithContentDescription("更多首页功能").assertDoesNotExist()
        rule.onNodeWithTag("home-header").performTouchInput { click(Offset(width - 20f, center.y)) }
        assertTrue(calls.isEmpty())
        rule.onAllNodesWithTag("home-brand").assertCountEquals(1)
    }
    @Test fun realDownPullAndAccessibleRefreshStillWorkWithoutToolbarIcons() {
        render()
        rule.onNodeWithTag("compact-home").performTouchInput {
            swipe(Offset(center.x, 25f), Offset(center.x, 340f), 700)
        }
        assertEquals(listOf("pull"), calls)
        rule.onNodeWithTag("compact-home").refreshHome11()
        assertEquals(listOf("pull", "pull"), calls)
    }
    @Test fun actualRefPanelGroupsRouteUsesNewInlineScreen() {
        rule.setContent {
            val context = LocalContext.current
            val repo = remember { ProxyDashboardRepository(context) }
            CompositionLocalProvider(LocalHetuMotionEnabled provides false) { MaterialTheme {
                RefPanel(ProxyComposeState(), repo, remember { mutableStateMapOf<String, Long>() },
                    RefPanelTab.Groups, {}, 0, remember { HazeState() }, null, false, {}, {}, {})
            } }
        }
        rule.onNodeWithTag("panel11-toolbar").assertIsDisplayed()
        rule.onNodeWithContentDescription("API 与测速配置").assertIsDisplayed()
    }
}
