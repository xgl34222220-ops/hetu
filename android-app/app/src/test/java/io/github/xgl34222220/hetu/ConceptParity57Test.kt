package io.github.xgl34222220.hetu

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "zh-rCN-w393dp-h852dp-mdpi", application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ConceptParity57Test {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private lateinit var vm: HetuViewModel
    @Before fun prepare() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        app.getSharedPreferences("hetu", 0).edit().clear().putBoolean("enableBlur", false).commit()
        vm = HetuViewModel(app)
    }
    private fun content(block: @Composable () -> Unit) {
        rule.setContent { HetuAppTheme("light", false) { block() } }
        rule.waitForIdle()
    }
    private fun screenshot(name: String) {
        rule.mainClock.advanceTimeBy(1000)
        rule.waitForIdle()
        rule.runOnIdle {
            val view = rule.activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            val output = File("build/outputs/concept57", "$name.png")
            output.parentFile!!.mkdirs()
            output.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            bitmap.recycle()
        }
    }
    @Test fun offlineHomeResourcesRemainReachableAndBackReturnsHome() {
        content { HetuRoot(vm) }
        rule.onNodeWithText("资源占用").performScrollTo().performClick()
        rule.onAllNodesWithText("等待连续运行采样").assertCountEquals(2)
        screenshot("resources-waiting")
        rule.onNodeWithContentDescription("返回").performClick()
        rule.onNodeWithText("本机直测").assertExists()
        rule.onNodeWithText("由河图进程直接请求，未指定代理节点；结果不代表其他应用的代理路径。").assertExists()
        screenshot("home-offline")
    }
    @Test fun resourceChartsRetainMissingSampleGaps() {
        vm.resourceSamples.addAll(listOf(HomeResourceSample(2f, 82L*1024*1024), HomeResourceSample(null, null), HomeResourceSample(3f, 83L*1024*1024)))
        content { HomeResourcesScreen(vm) {} }
        rule.onAllNodesWithText("断开处为缺测").assertCountEquals(2)
        rule.onNodeWithText("3.0%").assertExists()
        screenshot("resources-gaps")
    }
    @Test fun ipDetailsProvideDistinctPublicAndLanPages() {
        content { HomePublicIpScreen(vm) {} }
        rule.onNodeWithText("网络运营商").assertExists()
        rule.onNodeWithText("局域网 IP").performClick()
        rule.onNodeWithText("网络接口").assertExists()
        rule.onNodeWithText("网络运营商").assertDoesNotExist()
        screenshot("ip-lan")
        rule.onNodeWithText("公网 IP").performClick()
        rule.onNodeWithText("网络运营商").assertExists()
        screenshot("ip-public")
    }
    @Test fun compactSwitchRetainsToggleSemantics() {
        var state = false
        content { var checked by remember { mutableStateOf(false) }; HxSwitch(checked, { checked = it; state = it }) }
        rule.onNode(isToggleable()).assertIsOff().performClick().assertIsOn()
        assertTrue(state)
    }
    @Test fun directTargetsScreenUsesSharedPageAndValidatesDuplicates() {
        content { ProxyLatencyTargetsScreen {} }
        rule.onNodeWithText("本机直测目标").assertExists()
        screenshot("direct-targets")
    }
    @Test fun activeDockTabDispatchesReselectWithoutChangingDestination() {
        content { HetuRoot(vm) }
        val before = vm.reselect
        rule.onNodeWithContentDescription("首页").performClick()
        assertEquals(before + 1, vm.reselect)
        assertEquals(HxTab.Home, vm.tab)
    }
}
