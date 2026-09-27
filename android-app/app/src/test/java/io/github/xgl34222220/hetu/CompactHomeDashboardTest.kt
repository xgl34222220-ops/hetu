package io.github.xgl34222220.hetu

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w393dp-h900dp-mdpi", application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CompactHomeDashboardTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val calls = mutableListOf<String>()

    // Explicit test fixtures, never referenced by production code.
    private fun fixture() = CompactHomeData(
        running = true, uptimeSeconds = 45, core = "Mihomo", mode = "TPROXY",
        config = "自用_tproxy.yaml", delays = mapOf("Baidu" to 44L, "Cloudflare" to 146L, "Google" to 146L),
        wan = "203.0.113.42", lan = "192.168.1.8", countryCode = "JP", region = "测试地区",
        lanInterface = "wlan0", up = 654029, down = 3355443,
        used = 201863462666, total = 743491825336, memory = 97517568, cpu = 100f, connections = 12,
    )

    private fun render(data: CompactHomeData = fixture(), dark: Boolean = false, scale: Float = 1f) {
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, scale)) {
                MaterialTheme(colorScheme = if (dark) darkColorScheme(background = Color(0xFF121212)) else lightColorScheme()) {
                    CompactHomeDashboard(data, { calls += "refresh" }, { calls += "toggle" },
                        { calls += "reload" }, { calls += "restart" }, { calls += "delay" },
                        { calls += "webui" }, { calls += "log" }, { calls += "subscription" },
                        { calls += "connections" }, { calls += "settings" }, { calls += "diagnostics" },
                        { calls += "adblock" }, motionEnabled = false)
                }
            }
        }
    }

    private fun snapshot(name: String) {
        rule.waitForIdle()
        val file = File("build/outputs/compact-home/$name.png")
        file.parentFile?.mkdirs()
        // Robolectric has no live Window compositor for captureToImage's forceRedraw.
        // Draw the actual measured activity view using native Skia instead of PixelCopy.
        // This still renders the production composables, not a mock drawing of the layout.
        rule.runOnIdle {
            val view = rule.activity.window.decorView
            assertTrue("Rendered view must have a real measured size", view.width > 0 && view.height > 0)
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            val pixels = IntArray(view.width * view.height)
            bitmap.getPixels(pixels, 0, view.width, 0, 0, view.width, view.height)
            assertTrue("Snapshot must contain rendered detail, not a blank canvas", pixels.toSet().size > 32)
            file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            bitmap.recycle()
        }
    }

    @Test fun actionsAreEqualWidthAndDispatchRealCallbacks() {
        render()
        val tags = listOf("home-reload", "home-toggle", "home-restart")
        val widths = tags.map { rule.onNodeWithTag(it).fetchSemanticsNode().boundsInRoot.width }
        assertEquals(widths[0], widths[1], .5f)
        assertEquals(widths[1], widths[2], .5f)
        tags.forEach { rule.onNodeWithTag(it).assertIsEnabled().performClick() }
        assertEquals(listOf("reload", "toggle", "restart"), calls)
        snapshot("light-running")
    }

    @Test fun busyDisablesAllProxyActions() {
        render(fixture().copy(busy = true, message = "正在重载配置"))
        listOf("home-reload", "home-toggle", "home-restart").forEach { rule.onNodeWithTag(it).assertIsNotEnabled() }
        rule.onNodeWithText("正在重载配置").assertIsDisplayed()
    }

    @Test fun stoppedOnlyEnablesStartAndDoesNotInventMetrics() {
        render(CompactHomeData(config = "未选择配置"))
        rule.onNodeWithTag("home-toggle").assertIsEnabled().performClick()
        rule.onNodeWithTag("home-reload").assertIsNotEnabled()
        rule.onNodeWithTag("home-restart").assertIsNotEnabled()
        rule.onNodeWithTag("home-latency-refresh").assertIsNotEnabled()
        rule.onNodeWithText("启动").assertIsDisplayed()
        assertEquals(listOf("toggle"), calls)
        snapshot("stopped-unknown")
    }

    @Test fun refreshStateShowsSkeletonAndDisablesDuplicateRequests() {
        render(fixture().copy(testing = true))
        rule.onAllNodesWithText("···").assertCountEquals(3)
        rule.onNodeWithTag("home-latency-refresh").assertIsNotEnabled()
        rule.onNodeWithTag("home-latency-sort").assertIsNotEnabled()
        snapshot("latency-loading")
    }

    @Test fun latencySortIsStableAndUnknownValuesStayLast() {
        val delays = mapOf("Baidu" to -1L, "Google" to 120L, "Cloudflare" to 20L)
        assertEquals(listOf("Cloudflare", "Google", "Baidu"), CompactHomeFormat.ordered(delays, true))
        assertEquals(CompactHomeFormat.sites, CompactHomeFormat.ordered(delays, false))
        render(fixture().copy(delays = delays))
        rule.onNodeWithTag("home-latency-sort").performClick()
        val cf = rule.onNodeWithTag("latency-Cloudflare").fetchSemanticsNode().boundsInRoot.left
        val google = rule.onNodeWithTag("latency-Google").fetchSemanticsNode().boundsInRoot.left
        val baidu = rule.onNodeWithTag("latency-Baidu").fetchSemanticsNode().boundsInRoot.left
        assertTrue(cf < google && google < baidu)
    }

    @Test fun wanIsDefaultAndInlineSwitchShowsLan() {
        render()
        rule.onNodeWithText("WAN").assertIsDisplayed()
        rule.onNodeWithTag("home-network-switch").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("LAN").assertIsDisplayed()
        rule.onNodeWithText("192.168.1.8").assertIsDisplayed()
        rule.onNodeWithTag("home-network-details").performClick()
        rule.onNodeWithText("网络详情").assertIsDisplayed()
    }

    @Test fun shortcutsAndOverflowKeepExistingFunctionsReachable() {
        render()
        rule.onNodeWithTag("home-webui").performClick()
        rule.onNodeWithTag("home-log").performClick()
        rule.onNodeWithContentDescription("更多首页功能").performClick()
        rule.onNodeWithText("网络诊断").performClick()
        assertEquals(listOf("webui", "log", "diagnostics"), calls)
    }

    @Test fun cpuHasBoundedProgressAndUnknownSubscriptionHasNoFakeRemainder() {
        assertNull(CompactHomeFormat.remaining(0, 0))
        assertEquals(0, CompactHomeFormat.remaining(200, 100))
        assertEquals(100, CompactHomeFormat.remaining(-1, 100))
        assertEquals("", CompactHomeFormat.flag("?"))
        assertEquals("—", CompactHomeFormat.bytes(-1))
        render(fixture().copy(cpu = 180f))
        rule.onNodeWithTag("compact-home").performScrollToNode(hasTestTag("home-cpu-bar"))
        val progress = rule.onNodeWithTag("home-cpu-bar").fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo]
        assertEquals(1f, progress.current, 0f)
        rule.onNodeWithText("180.0%").assertIsDisplayed()
        snapshot("resources-warning")
    }

    @Test fun darkThemeRendersWithoutChangingData() {
        render(dark = true)
        rule.onNodeWithText("运行中").assertIsDisplayed()
        snapshot("dark-running")
    }

    @Test fun largeTextLongConfigAndIpv6RemainScrollable() {
        render(fixture().copy(config = "这是用于验证长配置名称以及系统大字体的测试配置.yaml",
            wan = "2001:db8:1234:5678:90ab:cdef:1234:5678"), scale = 1.6f)
        rule.onNodeWithTag("home-toggle").assertIsDisplayed()
        snapshot("large-text-top")
        rule.onNodeWithTag("compact-home").performScrollToNode(hasTestTag("home-cpu-bar"))
        rule.onNodeWithTag("home-cpu-bar").assertIsDisplayed()
        snapshot("large-text-bottom")
    }
}
