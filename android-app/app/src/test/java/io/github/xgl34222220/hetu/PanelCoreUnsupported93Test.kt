package io.github.xgl34222220.hetu

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import io.github.xgl34222220.hetu.home.HetuHomeTheme
import io.github.xgl34222220.hetu.panel.PanelActions
import io.github.xgl34222220.hetu.panel.PanelCoreGate
import io.github.xgl34222220.hetu.panel.PanelData
import io.github.xgl34222220.hetu.panel.PanelRoute
import io.github.xgl34222220.hetu.panel.PanelRuleSet
import io.github.xgl34222220.hetu.panel.PanelStatus
import io.github.xgl34222220.hetu.panel.PanelTab
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** A tab the running core cannot feed shows 「当前核心（X）不支持此功能」, never a read error. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "zh-rCN-w393dp-h852dp-mdpi", application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PanelCoreUnsupported93Test {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private fun gate(core: ProxyRuntimeProfile.Core) = PanelCoreGate.of(
        core.label, ProxyCoreSupport.clashApi(core), ProxyCoreSupport.providers(core), ProxyCoreSupport.singleServer(core), false)

    @Test fun coreWithoutControllerShowsTheCardOnEveryControllerTab() {
        val tab = mutableStateOf(PanelTab.Groups)
        val g = gate(ProxyRuntimeProfile.Core.XRAY)
        rule.setContent {
            HetuHomeTheme(dark = false) {
                PanelRoute(PanelData(status = PanelStatus.Running, core = g), tab.value, { tab.value = it }, PanelActions(),
                    contentPadding = PaddingValues(bottom = 96.dp))
            }
        }
        PanelTab.entries.filter { it != PanelTab.Logs }.forEach { next ->
            rule.runOnIdle { tab.value = next }
            rule.onNodeWithText("当前核心（Xray）不支持此功能").assertIsDisplayed()
            rule.onNodeWithText("无法读取面板").assertDoesNotExist()
            rule.onNodeWithText("全部更新").assertDoesNotExist()
        }
        // The way out lands on a tab the core does support.
        rule.runOnIdle { tab.value = PanelTab.Rules }
        rule.onNodeWithText("查看日志").performClick()
        rule.runOnIdle { assertEquals(PanelTab.Logs, tab.value) }
        rule.onNodeWithText("当前核心（Xray）不支持此功能").assertDoesNotExist()
    }

    /** 6.0.0: sing-box 订阅 / 规则集 show Hetu's own record of the start; no tab carries the card. */
    @Test fun singBoxShowsSubscriptionsAndRuleSetsFromHetusRecord() {
        val tab = mutableStateOf(PanelTab.Subscriptions)
        val sets = listOf(
            PanelRuleSet("geosite-cn", 9304, "domain", "SRS", "HTTP", "10-19 15:31"),
            PanelRuleSet("rp-direct", 3, "", "INLINE", "Inline"),
            PanelRuleSet("mine", -1, "", "JSON", "Local"),
        )
        rule.setContent {
            HetuHomeTheme(dark = false) {
                PanelRoute(PanelData(status = PanelStatus.Running, core = gate(ProxyRuntimeProfile.Core.SING_BOX), ruleSets = sets), tab.value,
                    { tab.value = it }, PanelActions(), contentPadding = PaddingValues(bottom = 96.dp))
            }
        }
        rule.onNodeWithText("当前核心（Sing-Box）不支持此功能").assertDoesNotExist()
        rule.runOnIdle { tab.value = PanelTab.RuleSets }
        rule.onNodeWithText("当前核心（Sing-Box）不支持此功能").assertDoesNotExist()
        rule.onNodeWithText("9304 条规则").assertIsDisplayed()
        rule.onNodeWithText("更新于 10-19 15:31").assertIsDisplayed()
        rule.onNodeWithText("写在配置中，随配置生效").assertIsDisplayed()
        rule.onNodeWithText("规则数未知").assertIsDisplayed()
        rule.onNodeWithText("本地文件，修改后自动生效").assertIsDisplayed()
        // Only the downloaded set offers 更新.
        rule.onAllNodesWithContentDescription("更新规则集").assertCountEquals(1)
    }
}
