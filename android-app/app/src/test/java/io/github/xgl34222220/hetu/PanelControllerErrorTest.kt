package io.github.xgl34222220.hetu

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import io.github.xgl34222220.hetu.home.HetuHomeTheme
import io.github.xgl34222220.hetu.panel.*
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** A real rendered control error must not look like live zero measurements or a stopped core. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "zh-rCN-w393dp-h852dp-mdpi", application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PanelControllerErrorTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val reason = "Mihomo 控制接口鉴权失败（401）：请核对自定义 API 的 Secret"
    private var vm: HetuViewModel? = null
    @After fun finish() { vm?.viewModelScope?.cancel() }

    @Test fun everyTabShowsUnavailableInsteadOfEmptyLiveData() {
        val tab = mutableStateOf(PanelTab.Overview)
        rule.setContent {
            HetuHomeTheme(dark = false) {
                PanelRoute(PanelData(status = PanelStatus.Running, readError = reason), tab.value,
                    { tab.value = it }, PanelActions(), contentPadding = PaddingValues(bottom = 96.dp))
            }
        }
        PanelTab.entries.forEach { next ->
            rule.runOnIdle { tab.value = next }
            rule.onNodeWithText("无法读取面板").assertIsDisplayed()
            rule.onNodeWithText(reason).assertIsDisplayed()
            rule.onNodeWithText("代理未运行").assertDoesNotExist()
            rule.onNodeWithText("运行概况").assertDoesNotExist()
            rule.onNodeWithText("0 B/s").assertDoesNotExist()
            rule.onNodeWithText("全部更新").assertDoesNotExist()
        }
    }

    @Test fun retryUsesTheRealRefreshCallbackAndShowsItsBusyState() {
        val refreshing = mutableStateOf(false)
        var calls = 0
        rule.setContent {
            HetuHomeTheme(dark = false) {
                PanelRoute(PanelData(status = PanelStatus.Running, readError = reason,
                    refreshing = refreshing.value), PanelTab.Overview, {},
                    PanelActions(onRefresh = { calls++; refreshing.value = true }),
                    contentPadding = PaddingValues(bottom = 96.dp))
            }
        }
        rule.onNodeWithText("重试").performClick()
        rule.waitForIdle()
        assertEquals(1, calls)
        rule.onNodeWithText("重试").assertIsNotEnabled()
        rule.runOnIdle { refreshing.value = false }
        rule.onNodeWithText("重试").performClick()
        assertEquals(2, calls)
    }

    @Test fun launcherErrorOpensTheActualApiSettingsSheetWithoutStartingProxy() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        app.getSharedPreferences("hetu", 0).edit().clear().putString("appearance", "light").commit()
        val model = HetuViewModel(app).also { vm = it; it.viewModelScope.cancel() }
        model.openPanel("overview")
        val field = HetuViewModel::class.java.getDeclaredField("state\$delegate").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val state = field.get(model) as MutableState<ProxyComposeState>
        state.value = ProxyComposeState(running = true, panelReady = false,
            controllerReadFailed = true, controllerError = reason)
        rule.setContent { HetuAppTheme(appearance = "light", dynamic = false) { NewUiPanel(model, 96.dp) } }
        rule.onNodeWithText("无法读取面板").assertIsDisplayed()
        rule.onNodeWithText("API 设置").performClick()
        rule.onNodeWithText("测速与 API").assertIsDisplayed()
        rule.onNodeWithText("外部 Clash API").assertExists()
        rule.onNodeWithText("使用河图本机核心").assertExists()
        rule.onNodeWithText("取消").performClick()
        rule.onNodeWithText("测速与 API").assertDoesNotExist()
        rule.onNodeWithText("排序与布局").assertDoesNotExist()
        rule.onNodeWithText("无法读取面板").assertIsDisplayed()
        assertTrue(model.state.running)
        assertNull(model.operation)
    }

    @Test fun largeFontErrorActionsStayAboveTheDockAndRecoveryShowsActualCounts() {
        val data = mutableStateOf(PanelData(status = PanelStatus.Running, readError = reason))
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1.6f)) {
                HetuHomeTheme(dark = false) {
                    PanelRoute(data.value, PanelTab.Overview, {}, PanelActions(),
                        contentPadding = PaddingValues(bottom = 96.dp))
                }
            }
        }
        rule.onNodeWithText("API 设置").assertIsDisplayed()
        val retry = rule.onNodeWithText("重试").getUnclippedBoundsInRoot()
        val api = rule.onNodeWithText("API 设置").getUnclippedBoundsInRoot()
        assertTrue(retry.bottom < 756.dp)
        assertTrue(api.bottom < 756.dp)
        rule.runOnIdle {
            data.value = PanelData(status = PanelStatus.Running,
                overview = PanelOverview(strategyCount = 9, ruleCount = 23, connectionCount = 4))
        }
        rule.onNodeWithText("无法读取面板").assertDoesNotExist()
        rule.onNodeWithText("运行概况").assertExists()
        rule.onNodeWithText("9", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("23", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("4", useUnmergedTree = true).assertExists()
    }
}
