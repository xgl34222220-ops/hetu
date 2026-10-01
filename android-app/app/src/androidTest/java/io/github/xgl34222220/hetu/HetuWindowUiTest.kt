package io.github.xgl34222220.hetu

import android.app.Application
import android.graphics.Bitmap
import android.os.Build
import android.net.Uri
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
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
import java.util.concurrent.atomic.AtomicReference
import top.yukonga.miuix.kmp.blur.isRuntimeShaderSupported

/** Wait only for a not-yet-available accessibility tree; a real obstruction fails immediately. */
internal fun awaitWindowForeground(
    read: () -> Pair<Boolean, String?>,
    clock: () -> Long,
    pause: (Long) -> Unit,
    timeoutMillis: Long = 5_000,
): List<Pair<Boolean, String?>> {
    require(timeoutMillis >= 0)
    val started = clock()
    val attempts = mutableListOf<Pair<Boolean, String?>>()
    while (true) {
        val snapshot = read()
        attempts += snapshot
        val remaining = timeoutMillis - (clock() - started)
        if (!snapshot.first || snapshot.second != null || remaining <= 0) return attempts
        pause(minOf(50L, remaining))
    }
}

/** Test APK only: actual Android window, real root UI, no runtime start or Root calls. */
@RunWith(AndroidJUnit4::class)
class HetuWindowUiTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val application get() = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var vm: HetuViewModel
    private val server = MockWebServer()
    private val hardwareCanvasSeen = AtomicBoolean(false)
    private val runtimeShaderSupported = AtomicReference<Boolean?>(null)
    private val requests = CopyOnWriteArrayList<String>()
    private val captures = mutableListOf<String>()
    private val foregroundChecks = mutableListOf<JSONObject>()
    private val output get() = File(application.filesDir, "window-ui-qa").apply { mkdirs() }
    private var successful = false
    private var emulatorConfirmed = false
    private var nativeBackupVerified = false

    @Before fun prepare() {
        check(Build.HARDWARE in setOf("ranchu", "goldfish") && Build.PRODUCT.contains("sdk")) {
            "This fixture is restricted to an official disposable Android emulator"
        }
        emulatorConfirmed = true
        // Match the production launcher window without starting its runtime polling lifecycle.
        rule.runOnIdle { rule.activity.enableEdgeToEdge() }
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
        verifyBackupOnAndroidFilesystem()
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
            val shaderSupport = isRuntimeShaderSupported()
            SideEffect { runtimeShaderSupported.set(shaderSupport) }
            HetuAppTheme("light", dynamic = false) {
                Box(Modifier.fillMaxSize().drawWithContent {
                    if (drawContext.canvas.nativeCanvas.isHardwareAccelerated) hardwareCanvasSeen.set(true)
                    drawContent()
                }) { HetuRoot(vm) }
            }
        }
    }

    private fun verifyBackupOnAndroidFilesystem() = runBlocking(Dispatchers.IO) {
        // Fresh official emulator only, before the VM can issue even its loopback preload.
        val prefs = application.getSharedPreferences("hetu", 0)
        val library = ProxyConfigLibrary(application)
        val core = ProxyRuntimeProfile.Core.MIHOMO
        val name = "window-restore.yaml"
        val original = "# original window fixture\nproxies: []\nrules: []\n"
        val restored = "# restored window fixture\nproxies: []\nrules: []\n"
        val entry = library.importConfig(core, name, original.byteInputStream())
        val backup = File(application.filesDir, "window-backup.json")
        backup.writeText(JSONObject().put("schema", 1)
            .put("settings", JSONObject().put("proxySelectedConfig.${core.id}", JSONObject().put("t", "s").put("v", name)))
            .put("configs", org.json.JSONArray().put(JSONObject().put("core", core.id).put("name", name)
                .put("data", Base64.encodeToString(restored.toByteArray(), Base64.NO_WRAP)))).toString())
        assertEquals(1, HetuSettingsBackup.restore(application, Uri.fromFile(backup)))
        assertEquals(original, library.read(entry))
        val selected = requireNotNull(library.selected(core))
        assertNotEquals(name, selected.name)
        assertEquals(restored, library.read(selected))
        prefs.edit().putString("proxyCustomApiSecret", "synthetic-window-secret").commit()
        val before = prefs.all
        backup.writeText(JSONObject().put("schema", 1)
            .put("settings", JSONObject().put("proxyCustomApiHost", JSONObject().put("t", "s").put("v", "replacement.invalid")))
            .put("configs", org.json.JSONArray()).toString())
        val rejected = runCatching { HetuSettingsBackup.restore(application, Uri.fromFile(backup)) }.exceptionOrNull()
        assertNotNull(rejected)
        assertTrue("The endpoint guard must cause rejection", rejected?.message.orEmpty().contains("API 地址或端口"))
        assertEquals(before, prefs.all)
        prefs.edit().putString("proxyCustomApiSecret", "").commit()
        nativeBackupVerified = true
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> seed(name: String, value: T) {
        val field = HetuViewModel::class.java.getDeclaredField("${name}\$delegate").apply { isAccessible = true }
        (field.get(vm) as MutableState<T>).value = value
    }

    private fun assertForeground() {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val attempts = awaitWindowForeground(
            read = {
                val before = rule.runOnIdle { rule.activity.hasWindowFocus() }
                val root = automation.rootInActiveWindow
                val activePackage = root?.packageName?.toString()
                root?.recycle()
                val after = rule.runOnIdle { rule.activity.hasWindowFocus() }
                (before && after) to activePackage
            },
            clock = { android.os.SystemClock.uptimeMillis() },
            pause = { android.os.SystemClock.sleep(it) },
        )
        val (focus, activePackage) = attempts.last()
        foregroundChecks += JSONObject().put("windowFocused", focus)
            .put("activePackage", activePackage ?: JSONObject.NULL)
            .put("treeReadAttempts", org.json.JSONArray(attempts.map { (focused, pkg) ->
                JSONObject().put("windowFocused", focused).put("activePackage", pkg ?: JSONObject.NULL)
            }))
        if (!focus || activePackage != application.packageName) {
            captureDisplay("blocked-foreground")
            fail("Test app is obscured or not foreground: focused=$focus, activePackage=$activePackage")
        }
    }

    private fun visibleTransition(name: String, action: () -> Unit) {
        assertForeground()
        rule.mainClock.autoAdvance = false
        try {
            action()
            repeat(36) { frame ->
                rule.mainClock.advanceTimeByFrame()
                android.os.SystemClock.sleep(20)
                if (frame == 8) capture("motion-$name-middle")
            }
        } finally { rule.mainClock.autoAdvance = true }
        rule.waitForIdle()
    }

    private fun capture(name: String) {
        rule.waitForIdle()
        assertForeground()
        captureDisplay(name)
    }

    private fun captureDisplay(name: String) {
        // UiAutomation copies the composed Android display, including the actual GPU/SwiftShader
        // surface. Never substitute View.draw(BitmapCanvas) in this window-specific test.
        val bitmap = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        File(output, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        captures += "$name.png"
    }

    @Test fun defaultGlassWindowRendersAndInlineNavigationCompletes() {
        rule.waitUntil(30_000) { hardwareCanvasSeen.get() }
        assertTrue("Runtime shader capability must be available", runtimeShaderSupported.get() == true)
        rule.runOnIdle {
            assertTrue(rule.activity.window.decorView.isHardwareAccelerated)
            assertTrue(vm.blurEnabled)
            assertTrue(vm.prefs.getBoolean("liquidGlass", false))
        }
        rule.onNodeWithTag("hetu-dock", useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithText("203.0.113.24").assertIsDisplayed()
        capture("01-home-default-glass")
        visibleTransition("open-panel") { rule.onNodeWithTag("dock-tab-1", useUnmergedTree = true).performTouchInput { click() } }
        rule.onNodeWithTag("strategy-group-节点选择", useUnmergedTree = true).assertIsDisplayed()
        capture("02-strategy-default-glass")
        visibleTransition("expand-nodes") { rule.onNodeWithTag("strategy-group-节点选择", useUnmergedTree = true).performTouchInput { click() } }
        rule.onNodeWithTag("strategy-node-节点选择-日本测试 02", useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithTag("hetu-dock", useUnmergedTree = true).assertIsDisplayed()
        capture("03-inline-nodes-default-glass")
        visibleTransition("collapse-nodes") { rule.onNodeWithContentDescription("收起策略 节点选择").performTouchInput { click() } }
        rule.onNodeWithTag("strategy-expanded-节点选择", useUnmergedTree = true).assertDoesNotExist()
        capture("04-strategy-collapsed")
        visibleTransition("open-overview") { rule.onNodeWithTag("panel-tab-overview", useUnmergedTree = true).performTouchInput { click() } }
        rule.onNodeWithTag("panel-overview-traffic", useUnmergedTree = true).assertIsDisplayed()
        capture("05-overview-default-glass")
        visibleTransition("open-tools") { rule.onNodeWithTag("dock-tab-2", useUnmergedTree = true).performTouchInput { click() } }
        rule.onNodeWithTag("tools-文件与脚本", useUnmergedTree = true).assertIsDisplayed()
        capture("06-tools-default-glass")
        visibleTransition("open-settings") { rule.onNodeWithTag("dock-tab-3", useUnmergedTree = true).performTouchInput { click() } }
        rule.onNodeWithTag("settings-group-0", useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithTag("settings-基础代理配置", useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithTag("settings-关于", useUnmergedTree = true).assertIsDisplayed()
        capture("07-settings-default-glass")
        assertTrue("only the explicitly allowed loopback preload may run", requests.all { it == "GET /providers/proxies" })
        successful = true
    }

    @After fun reportAndClose() {
        if (!emulatorConfirmed) return
        val geometry = rule.runOnIdle {
            val content = rule.activity.findViewById<android.view.View>(android.R.id.content)
            val origin = IntArray(2).also(content::getLocationOnScreen)
            val insets = androidx.core.view.ViewCompat.getRootWindowInsets(content)
            JSONObject().put("contentOriginX", origin[0]).put("contentOriginY", origin[1])
                .put("contentWidth", content.width).put("contentHeight", content.height)
                .put("statusBarInsetTop", insets?.getInsets(androidx.core.view.WindowInsetsCompat.Type.statusBars())?.top ?: JSONObject.NULL)
                .put("navigationBarInsetBottom", insets?.getInsets(androidx.core.view.WindowInsetsCompat.Type.navigationBars())?.bottom ?: JSONObject.NULL)
                .put("density", content.resources.displayMetrics.density)
        }
        val report = JSONObject().put("passed", successful).put("sdk", Build.VERSION.SDK_INT)
            .put("windowGeometry", geometry).put("productionEdgeToEdgeConfigured", true)
            .put("model", Build.MODEL).put("hardwareCanvasSeen", hardwareCanvasSeen.get())
            .put("runtimeShaderSupported", runtimeShaderSupported.get() ?: JSONObject.NULL)
            .put("nativeBackupVerified", nativeBackupVerified)
            .put("captureMethod", "UiAutomation.takeScreenshot")
            .put("defaultGlassEnabled", if (::vm.isInitialized) vm.blurEnabled && vm.prefs.getBoolean("liquidGlass", false) else JSONObject.NULL)
            .put("foregroundChecks", org.json.JSONArray(foregroundChecks)).put("rootOrProxyStarted", false)
            .put("screenshots", org.json.JSONArray(captures)).put("loopbackRequests", org.json.JSONArray(requests))
            .put("limits", "Controlled UI data in androidTest. Emulator rendering does not establish phone GPU performance, FPS, or live Root/VPN connectivity.")
        File(output, "report.json").writeText(report.toString(2))
        if (::vm.isInitialized) rule.runOnIdle { vm.viewModelScope.cancel() }
        server.shutdown()
    }
}
