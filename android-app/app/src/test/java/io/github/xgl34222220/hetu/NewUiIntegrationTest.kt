package io.github.xgl34222220.hetu

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import io.github.xgl34222220.hetu.home.homeStatusOf
import io.github.xgl34222220.hetu.home.HomeStatus
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Render the real launcher root, rather than an unused reference activity or preview. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "zh-rCN-w393dp-h852dp-mdpi", application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NewUiIntegrationTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private lateinit var vm: HetuViewModel
    @Before fun prepare() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        app.getSharedPreferences("hetu", 0).edit().clear()
            .putString("appearance", "light").putBoolean("enableBlur", false).commit()
        vm = HetuViewModel(app)
    }
    private fun root() {
        rule.setContent { HetuAppTheme(appearance = "light", dynamic = false) { HetuRoot(vm) } }
        rule.mainClock.advanceTimeBy(1000)
        rule.waitForIdle()
    }
    @Test fun launcherShowsNewHome() {
        root()
        rule.onNodeWithText("本机直测").assertExists()
        rule.onNodeWithText("尚未启动代理").assertExists()
    }
    @Test fun launcherPanelContainsAllSevenTabs() {
        vm.tab = HxTab.Panel
        root()
        listOf("概览", "策略", "订阅", "连接", "规则", "规则集", "日志").forEach { label ->
            rule.onNodeWithText(label).performScrollTo().performClick()
            rule.onNodeWithText("代理未运行").assertExists()
        }
        assertEquals("logs", vm.panelSection)
    }
    @Test fun resourceDetailHidesDockAndReturns() {
        root()
        rule.onNodeWithContentDescription("查看资源占用").performScrollTo().performClick()
        rule.mainClock.advanceTimeBy(1000)
        rule.onNodeWithText("首页").assertDoesNotExist()
        rule.onNodeWithContentDescription("返回").performClick()
        rule.mainClock.advanceTimeBy(1000)
        rule.onNodeWithText("首页").assertExists()
    }
    @Test fun newHomeFailureUsesLatestOperationState() {
        assertTrue(homeStatusOf(CompactHomeData(), "拒绝授权") is HomeStatus.StartFailed)
        assertEquals(HomeStatus.Starting, homeStatusOf(CompactHomeData(operation = HomeOperation.Start), "旧失败"))
        assertTrue(homeStatusOf(CompactHomeData(running = true), "旧失败") is HomeStatus.Running)
    }
    @Test fun subscriptionDeepLinkReachesNewPanel() {
        vm.openPanel("providers")
        root()
        rule.onNodeWithText("代理未运行").assertExists()
        rule.onNodeWithText("订阅").assertExists()
        assertEquals("providers", vm.panelSection)
    }
    @Test fun newLauncherKeepsToolsAndDiagnostics() {
        vm.tab = HxTab.Tools
        root()
        rule.onNodeWithText("诊断工具").assertExists()
    }
}
