package io.github.xgl34222220.hetu

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import io.github.xgl34222220.hetu.home.HetuHomeTheme
import io.github.xgl34222220.hetu.panel.*
import io.github.xgl34222220.hetu.ui.LocalHetuMotionEnabled
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** V20.93 polish: restored-card sync hint and busy group-test feedback keep their touch contracts. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "zh-rCN-w393dp-h852dp-mdpi", application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PanelSyncPolish97Test {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val node = PanelNode("香港 Alpha", protocol = "VLESS")
    private val group = PanelGroup("节点选择", "Selector", listOf(node), node.name)
    private var data by mutableStateOf(PanelData(status = PanelStatus.Running, groups = listOf(group),
        delays = mapOf(node.name to PanelDelay.Ms(44))))
    private var tests = 0

    @Before fun prepare() {
        ApplicationProvider.getApplicationContext<Application>().getSharedPreferences("hetu", 0)
            .edit().clear().putBoolean("enableBlur", false).commit()
    }

    private fun render(expanded: Boolean = false) {
        rule.setContent {
            CompositionLocalProvider(LocalHetuMotionEnabled provides false) {
                HetuHomeTheme(dark = false) {
                    PanelRoute(data, PanelTab.Groups, {}, PanelActions(
                        onTestGroup = { tests++; data = data.copy(testingGroups = setOf(it)) }),
                        initialView = PanelViewState(expandedGroups = if (expanded) listOf(group.name) else emptyList()),
                        contentPadding = PaddingValues(bottom = 100.dp))
                }
            }
        }
        rule.mainClock.advanceTimeBy(1_000)
        rule.waitForIdle()
    }

    @Test fun restoredCardsShowAQuietSyncHintThatLeavesOnceFresh() {
        data = data.copy(syncing = true)
        render()
        rule.onNode(hasContentDescription("正在同步核心最新状态")).assertExists()
        rule.onNodeWithText("44 ms").assertExists()
        data = data.copy(syncing = false)
        rule.mainClock.advanceTimeBy(1_000)
        rule.waitForIdle()
        rule.onNode(hasContentDescription("正在同步核心最新状态")).assertDoesNotExist()
    }

    @Test fun busyGroupHeaderTestIsDisabledAndCannotQueueASecondRun() {
        render(expanded = true)
        rule.onNode(hasText("测速") and hasClickAction()).performClick()
        rule.mainClock.advanceTimeBy(1_000)
        rule.waitForIdle()
        assertEquals(1, tests)
        val busy = rule.onAllNodes(hasText("测速中") and hasClickAction())
        val count = busy.fetchSemanticsNodes().size
        assertTrue("Header and card must both show the in-flight group test", count >= 2)
        repeat(count) { busy[it].assertIsNotEnabled().performClick() }
        assertEquals(1, tests)
        rule.onNode(hasText("测速") and hasClickAction()).assertDoesNotExist()
    }
}
