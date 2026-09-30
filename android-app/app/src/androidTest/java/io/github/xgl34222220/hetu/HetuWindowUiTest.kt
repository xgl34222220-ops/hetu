package io.github.xgl34222220.hetu

import android.app.Application
import android.graphics.Bitmap
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.MutableState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.cancel
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/** Test APK only: actual Android window, real root UI, no runtime start or Root calls. */
@RunWith(AndroidJUnit4::class)
class HetuWindowUiTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val application get() = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var vm: HetuViewModel
    private val server = MockWebServer()
    private val hardwareCanvasSeen = AtomicBoolean(false)
    private val requests = CopyOnWriteArrayList<String>()
    private val captures = mutableListOf<String>()
    private val output get() = File(application.filesDir, "window-ui-qa").apply { mkdirs() }
    private var successful = false

    @Before fun prepare() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += "${request.method} ${request.path}"
                return if (request.method == "GET" && request.path == "/providers/proxies") {
                    MockResponse().setHeader("Content-Type", "application/json")
                        .setBody("{\"providers\":{}}")
                } else MockResponse().setResponseCode(403).setBody("Unexpected test API path")
            }
        }
        server.start(InetAddress.getByName("127.0.0.1"), 0)
        // Isolated, fresh emulator application data only. Never change device security or fonts.
        application.getSharedPreferences("hetu", 0).edit().clear()
            .putBoolean("enableBlur", true).putBoolean("liquidGlass", true)
            .putBoolean("floatingBottomBar", true).putBoolean("showPanelDock", true)
            .putString("defaultPanelSection", "proxies")
            .putBoolean("proxyRootWanted", false).putBoolean("proxyRootAutoStart", false)
            .putBoolean("proxyApiHistoryEnabled", false)
            .putBoolean("proxyCustomApiEnabled", true).putString("proxyCustomApiHost", "127.0.0.1")
            .putInt("proxyCustomApiPort", server.port).putString("proxyCustomApiSecret", "")
            .commit()
        vm = HetuViewModel(application)
        val nodes = listOf(ProxyNodeUi("香港测试 01", "VLESS", true, 42), ProxyNodeUi("日本测试 02", "Trojan", true, 76), ProxyNodeUi("未知延迟节点", "VLESS"))
        seed("state", ProxyComposeState(running = true, panelReady = true, trafficMode = "rule", config = "Android 窗口测试.yaml",
            memoryBytes = 132120576, uploadTotal = 1_048_576L, downloadTotal = 5_242_880L,
            groups = listOf(ProxyGroupUi("节点选择", "Selector", nodes[0].name, nodes), ProxyGroupUi("自动选择", "URLTest", nodes[1].name, nodes))))
        seed("runtime", ProxyRuntimeSnapshot(running = true, pid = 1234, elapsedSeconds = 3661,
            rssBytes = 132120576L, wanAddress = "203.0.113.24", wanCountryCode = "SG", wanCountry = "测试地区", lanAddress = "192.0.2.10"))
        vm.rateHistory.addAll(listOf(10_000L, 20_000L, 15_000L, 28_000L, 22_000L))
        vm.upHistory.addAll(listOf(2_000L, 4_000L, 3_000L, 5_000L, 4_000L))
        // ComponentActivity does not call HetuActivity.onStart, so VM polling never starts.
        rule.setContent {
            HetuAppTheme("light", dynamic = false) {
                Box(Modifier.fillMaxSize().drawWithContent {
                    if (drawContext.canvas.nativeCanvas.isHardwareAccelerated) hardwareCanvasSeen.set(true)
                    drawContent()
                }) { HetuRoot(vm) }
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> seed(name: String, value: T) {
        val field = HetuViewModel::class.java.getDeclaredField("${name}\$delegate").apply { isAccessible = true }
        (field.get(vm) as MutableState<T>).value = value
    }

    private fun visibleTransition(action: () -> Unit) {
        rule.mainClock.autoAdvance = false
        try {
            action()
            repeat(36) {
                rule.mainClock.advanceTimeByFrame()
                android.os.SystemClock.sleep(20)
            }
        } finally { rule.mainClock.autoAdvance = true }
        rule.waitForIdle()
    }

    private fun capture(name: String) {
        rule.waitForIdle()
        // UiAutomation copies the composed Android display, including the actual GPU/SwiftShader
        // surface. Never substitute View.draw(BitmapCanvas) in this window-specific test.
        val bitmap = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        File(output, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        captures += "$name.png"
    }

    @Test fun defaultGlassWindowRendersAndInlineNavigationCompletes() {
        rule.waitUntil(30_000) { hardwareCanvasSeen.get() }
        rule.runOnIdle {
            assertTrue(rule.activity.window.decorView.isHardwareAccelerated)
            assertTrue(vm.blurEnabled)
            assertTrue(vm.prefs.getBoolean("liquidGlass", false))
        }
        rule.onNodeWithTag("hetu-dock", useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithText("203.0.113.24").assertIsDisplayed()
        capture("01-home-default-glass")
        visibleTransition { rule.onNodeWithTag("dock-tab-1", useUnmergedTree = true).performClick() }
        rule.onNodeWithTag("strategy-group-节点选择", useUnmergedTree = true).assertIsDisplayed()
        capture("02-strategy-default-glass")
        visibleTransition { rule.onNodeWithTag("strategy-group-节点选择", useUnmergedTree = true).performClick() }
        rule.onNodeWithTag("strategy-node-节点选择-日本测试 02", useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithTag("hetu-dock", useUnmergedTree = true).assertIsDisplayed()
        capture("03-inline-nodes-default-glass")
        visibleTransition { rule.onNodeWithContentDescription("收起策略 节点选择").performClick() }
        rule.onNodeWithTag("strategy-expanded-节点选择", useUnmergedTree = true).assertDoesNotExist()
        capture("04-strategy-collapsed")
        visibleTransition { rule.onNodeWithTag("panel-tab-overview", useUnmergedTree = true).performClick() }
        rule.onNodeWithTag("panel-overview-traffic", useUnmergedTree = true).assertIsDisplayed()
        capture("05-overview-default-glass")
        visibleTransition { rule.onNodeWithTag("dock-tab-2", useUnmergedTree = true).performClick() }
        rule.onNodeWithTag("tools-文件与脚本", useUnmergedTree = true).assertIsDisplayed()
        capture("06-tools-default-glass")
        assertTrue("only the explicitly allowed loopback preload may run", requests.all { it == "GET /providers/proxies" })
        successful = true
    }

    @After fun reportAndClose() {
        val report = JSONObject().put("passed", successful).put("sdk", Build.VERSION.SDK_INT)
            .put("model", Build.MODEL).put("hardwareCanvasSeen", hardwareCanvasSeen.get())
            .put("captureMethod", "UiAutomation.takeScreenshot")
            .put("defaultGlassEnabled", true).put("rootOrProxyStarted", false)
            .put("screenshots", org.json.JSONArray(captures)).put("loopbackRequests", org.json.JSONArray(requests))
            .put("limits", "Controlled UI data in androidTest. Emulator rendering does not establish phone GPU performance, FPS, or live Root/VPN connectivity.")
        File(output, "report.json").writeText(report.toString(2))
        if (::vm.isInitialized) rule.runOnIdle { vm.viewModelScope.cancel() }
        server.shutdown()
    }
}
