package io.github.xgl34222220.hetu

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import io.github.xgl34222220.hetu.ui.LocalHetuMotionEnabled
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Uses the actual HomeScreen/VM renderer. Test fixtures never enter production. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w393dp-h852dp-mdpi", application = Application::class, shadows = [ConceptRootBridgeShadow::class, ConceptMihomoClientShadow::class, ConceptRuntimeInspectorShadow::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HomeConceptUiTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var vm: HetuViewModel
    private val nav = HxNav()
    @Before fun prepare() {
        app.getSharedPreferences("hetu", 0).edit().clear().commit()
        vm = newConceptTestVm(app)
    }
    @Suppress("UNCHECKED_CAST")
    private fun <T> seed(name: String, value: T) {
        val field = HetuViewModel::class.java.getDeclaredField(name + "\$delegate")
        field.isAccessible = true
        (field.get(vm) as MutableState<T>).value = value
    }
    private fun running() {
        seed("state", ProxyComposeState(running = true, core = "mihomo", mode = "TProxy", config = "测试配置.yaml", trafficMode = "rule"))
        seed("runtime", ProxyRuntimeSnapshot(running = true, pid = 456, elapsedSeconds = 3661,
            rssBytes = 132120576L, wanAddress = "203.0.113.24", wanCountryCode = "SG", wanCountry = "测试地区",
            lanAddress = "192.0.2.10", lanInterface = "wlan0"))
    }
    private fun render(scale: Float = 1f, content: @androidx.compose.runtime.Composable () -> Unit = { HomeScreen(vm, 92.dp) }) {
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalNav provides nav, LocalHetuMotionEnabled provides false,
                LocalDensity provides Density(density.density, scale)) {
                HetuAppTheme(appearance = "light", dynamic = false) { CompositionLocalProvider(LocalHxMotionEnabled provides false) { content() } }
            }
        }
        rule.waitForIdle()
    }
    private fun capture(name: String) = rule.runOnIdle {
        val view = rule.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        File("build/outputs/hetu-ui-consolidation/$name.png").also { file ->
            file.parentFile!!.mkdirs()
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        bitmap.recycle()
    }
    @Test fun runningHomeHasSingleDirectStopAndFullWidthAddress() {
        running(); render()
        rule.onNodeWithText("停止").assertIsDisplayed()
        rule.onAllNodesWithText("停止").assertCountEquals(1)
        rule.onNodeWithText("203.0.113.24").assertIsDisplayed()
        rule.onNodeWithText("上传速度").assertExists()
        rule.onNodeWithText("下载速度").assertExists()
        rule.onNodeWithText("WebUI").assertDoesNotExist()
        capture("concept-home-running")
        rule.onNodeWithContentDescription("运行控制").performClick()
        rule.onNodeWithText("重载配置").assertIsDisplayed()
        rule.onNodeWithText("重启核心").assertIsDisplayed()
    }
    @Test fun detailsOpenDistinctRouteWhileCardSwitchesWanLan() {
        running(); render()
        rule.onNodeWithText("详情").performClick()
        assertEquals(HxRoute.PublicIp, nav.current)
        nav.pop()
        rule.onNodeWithText("203.0.113.24").performClick()
        rule.onNodeWithText("局域网 IP").assertExists()
        rule.onNodeWithText("192.0.2.10").assertExists()
    }
    @Test fun ipDetailsShowUnknownInsteadOfMockMetadataAndSwitchLan() {
        running(); render { PublicIpDetails(vm) {} }
        rule.onNodeWithText("203.0.113.24").assertIsDisplayed()
        rule.onNodeWithContentDescription("复制IP 地址").assertExists()
        capture("concept-public-ip")
        rule.onNodeWithText("局域网 IP").performClick()
        rule.onNodeWithText("192.0.2.10").assertIsDisplayed()
        rule.onNodeWithText("wlan0").assertIsDisplayed()
    }
    @Test fun stoppedLargeFontRemainsReadableWithNoInventedIp() {
        render(scale = 1.6f)
        rule.onNodeWithText("启动").assertIsDisplayed()
        rule.onNodeWithText("203.0.113.24").assertDoesNotExist()
        capture("concept-home-large-font")
    }
    @Test fun resourcesUseRealSampleAndNoInventedHourHistory() {
        running(); render { CoreDetails(vm) {} }
        rule.onAllNodesWithText("本次查看").assertCountEquals(2)
        rule.onNodeWithText("过去 1 小时").assertDoesNotExist()
        capture("concept-resources")
    }
}
