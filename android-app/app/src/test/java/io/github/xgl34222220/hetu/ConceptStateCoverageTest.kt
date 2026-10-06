package io.github.xgl34222220.hetu

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.view.MotionEvent
import android.view.Gravity
import android.view.WindowManager
import android.os.SystemClock
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.AnnotatedString
import androidx.test.core.app.ApplicationProvider
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.*
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Duration

/**
 * Actual production components and semantics actions with isolated test-only data.
 * No onForeground(), Root start/stop, download, real controller or external request.
 * A loopback MockWebServer handles incidental adjacent-pager provider reads.
 * Native Android windows are drawn at their real screen positions. OS status/nav
 * chrome, hardware blur, keyboard and OS window-manager shadows are not emulated.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "zh-rCN-w393dp-h852dp-mdpi", application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ConceptStateCoverageTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private lateinit var vm: HetuViewModel
    private lateinit var server: MockWebServer
    private val output = File("build/outputs/concept-state-coverage")

    @Before fun prepare() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    require(request.method == "GET") { "Coverage must never mutate a controller: ${request.method} ${request.path}" }
                    return MockResponse().setBody(when (request.requestUrl?.encodedPath) {
                        "/providers/proxies" -> """{"providers":{"测试订阅":{"vehicleType":"HTTP","updatedAt":"2026-10-02T00:00:00Z","proxies":["香港 01","香港 02"],"subscriptionInfo":{"Upload":0,"Download":21474836480,"Total":107374182400,"Expire":1798761600}}}}"""
                        "/proxies" -> "{\"proxies\":{}}"
                        else -> "{}"
                    })
                }
            }
            start()
        }
        app.getSharedPreferences("hetu", 0).edit().clear()
            .putString("appearance", "light").putBoolean("hetuDynamicColor", false)
            .putBoolean("enableBlur", false).putBoolean("predictiveBackAnimation", false)
            .putBoolean("proxyCustomApiEnabled", true).putString("proxyCustomApiHost", "127.0.0.1")
            .putInt("proxyCustomApiPort", server.port).putString("proxyCustomApiSecret", "")
            .putInt("proxySelectorGroupColumns", 2).putInt("proxySelectorNodeColumns", 2)
            .putString("proxySelectorDensity", "compact").commit()
        vm = HetuViewModel(app)
        rule.mainClock.autoAdvance = false
        output.mkdirs()
    }
    @After fun finish() {
        rule.runOnUiThread { rule.activity.setContent {} }
        frame()
        vm.onBackground()
        server.shutdown()
    }
    private fun frame() {
        repeat(4) {
            rule.mainClock.advanceTimeBy(200)
            rule.runOnUiThread { Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100)) }
        }
    }
    private fun show(content: @Composable () -> Unit) {
        rule.runOnUiThread { rule.activity.setContent {
            HetuAppTheme("light", false) { CompositionLocalProvider(LocalNav provides HxNav(), LocalHxBlur provides vm.blurEnabled) {
                Box(Modifier.fillMaxSize()) { content() }
            } }
        } }
        frame()
    }
    @Suppress("UNCHECKED_CAST")
    private fun fixture(name: String, value: Any?) {
        val field = HetuViewModel::class.java.getDeclaredField(name + "\$delegate").apply { isAccessible = true }
        (field.get(vm) as MutableState<Any?>).value = value
    }
    private fun nativeNodes(): List<SemanticsNode> {
        val result = mutableListOf<SemanticsNode>()
        fun visitNode(n: SemanticsNode) { result += n; n.children.forEach(::visitNode) }
        fun visitView(v: View) {
            v.javaClass.methods.firstOrNull { it.name == "getSemanticsOwner" }?.let {
                visitNode((it.invoke(v) as SemanticsOwner).rootSemanticsNode)
            }
            if (v is ViewGroup) (0 until v.childCount).forEach { visitView(v.getChildAt(it)) }
        }
        WindowInspector.getGlobalWindowViews().asReversed().forEach(::visitView)
        return result
    }
    private fun labels(n: SemanticsNode): List<String> =
        n.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } +
            n.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
    private fun exists(label: String): Boolean = nativeNodes().any { label in labels(it) }
    private fun expectEventually(label: String) {
        val until = System.nanoTime() + 5_000_000_000L
        while (System.nanoTime() < until) {
            var present = false
            rule.runOnUiThread { present = exists(label) }
            if (present) return
            Thread.sleep(20)
            frame()
        }
        expect(label)
    }
    private fun expect(label: String) = rule.runOnUiThread { assertTrue("Missing $label; present: " + nativeNodes().flatMap(::labels), exists(label)) }
    private fun click(label: String, index: Int = 0) {
        rule.runOnUiThread {
            val matches = nativeNodes().filter { label in labels(it) }
            val nodes = matches.mapNotNull { start ->
                generateSequence(start) { it.parent }.firstOrNull { it.config.getOrNull(SemanticsActions.OnClick)?.action != null }
            }.distinctBy { it.id }
            assertTrue("No clickable $label; present: " + nativeNodes().flatMap(::labels), nodes.size > index)
            assertTrue(nodes[index].config[SemanticsActions.OnClick].action!!.invoke())
        }
        frame()
    }
    /** Dispatch real touch events so pointerInput-owned anchors are exercised too. */
    private fun touch(label: String, index: Int = 0) {
        rule.runOnUiThread {
            val matches = nativeNodes().filter { label in labels(it) }.mapNotNull { start ->
                generateSequence(start) { it.parent }.firstOrNull { it.config.getOrNull(SemanticsActions.OnClick)?.action != null }
            }.distinctBy { it.id }
            val target = matches[index]
            fun contains(view: View): Boolean {
                val getter = view.javaClass.methods.firstOrNull { it.name == "getSemanticsOwner" }
                fun find(n: SemanticsNode): Boolean = n.id == target.id || n.children.any(::find)
                if (getter != null && find((getter.invoke(view) as SemanticsOwner).rootSemanticsNode)) return true
                return view is ViewGroup && (0 until view.childCount).any { contains(view.getChildAt(it)) }
            }
            val window = WindowInspector.getGlobalWindowViews().last { contains(it) }
            val at = target.boundsInWindow.center
            val time = SystemClock.uptimeMillis()
            val down = MotionEvent.obtain(time, time, MotionEvent.ACTION_DOWN, at.x, at.y, 0)
            assertTrue("Touch down was not delivered to $label", window.dispatchTouchEvent(down)); down.recycle()
            val up = MotionEvent.obtain(time, time + 80, MotionEvent.ACTION_UP, at.x, at.y, 0)
            window.dispatchTouchEvent(up); up.recycle()
        }
        frame()
    }
    private fun longClick(label: String) {
        rule.runOnUiThread {
            val n = nativeNodes().filter { label in labels(it) }.asSequence().flatMap { generateSequence(it) { n -> n.parent } }
                .first { it.config.getOrNull(SemanticsActions.OnLongClick)?.action != null }
            assertTrue(n.config[SemanticsActions.OnLongClick].action!!.invoke())
        }
        frame()
    }
    private fun replace(index: Int, value: String) {
        rule.runOnUiThread {
            val fields = nativeNodes().filter { it.config.getOrNull(SemanticsActions.SetText)?.action != null }
            assertTrue("Expected field $index; found ${fields.size}", fields.size > index)
            assertTrue(fields[index].config[SemanticsActions.SetText].action!!.invoke(AnnotatedString(value)))
        }
        frame()
    }
    private fun swipeUp() {
        rule.runOnUiThread {
            val view = rule.activity.window.decorView
            val t = SystemClock.uptimeMillis()
            fun send(action: Int, x: Float, y: Float, elapsed: Long) {
                val e = MotionEvent.obtain(t, t + elapsed, action, x, y, 0)
                view.dispatchTouchEvent(e); e.recycle()
            }
            send(MotionEvent.ACTION_DOWN, 195f, 740f, 0)
            for (i in 1..20) send(MotionEvent.ACTION_MOVE, 195f, 740f - i * 25f, i * 30L)
            send(MotionEvent.ACTION_UP, 195f, 240f, 630)
        }
        frame()
    }
    private fun scroll(pixels: Float) {
        rule.runOnUiThread {
            val node = nativeNodes().first { it.config.getOrNull(SemanticsActions.ScrollBy)?.action != null && it.boundsInWindow.width > 0 && it.boundsInWindow.center.x in 0f..393f }
            node.config[SemanticsActions.ScrollBy].action!!.invoke(0f, pixels)
        }
        frame()
    }
    private fun dismissSheet() {
        rule.runOnUiThread {
            val action = nativeNodes().last { it.config.getOrNull(SemanticsActions.Dismiss)?.action != null }.config[SemanticsActions.Dismiss]
            assertTrue(action.action!!.invoke())
        }
        frame()
    }
    private fun toggle(index: Int) {
        rule.runOnUiThread {
            val controls = nativeNodes().filter { it.config.getOrNull(SemanticsProperties.ToggleableState) != null && it.config.getOrNull(SemanticsActions.OnClick)?.action != null }
            assertTrue(controls[index].config[SemanticsActions.OnClick].action!!.invoke())
        }
        frame()
    }
    private fun expectDock(visible: Boolean) = rule.runOnUiThread {
        assertEquals("Root dock visibility", visible, nativeNodes().any { it.config.getOrNull(SemanticsProperties.TestTag) == "hetu-dock" })
    }
    private fun back() { rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }; frame() }
    private fun capture(id: String) {
        frame()
        rule.runOnUiThread {
            val base = rule.activity.window.decorView
            assertTrue(base.width > 0 && base.height > 0)
            val bitmap = Bitmap.createBitmap(base.width, base.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val windows = WindowInspector.getGlobalWindowViews().filter { it.visibility == View.VISIBLE && it.width > 0 && it.height > 0 }
            val metadata = JSONArray()
            windows.forEach { view ->
                val xy = IntArray(2); view.getLocationOnScreen(xy)
                val params = view.layoutParams as? WindowManager.LayoutParams
                // Robolectric lays a separate dialog decor at (0,0); apply the real
                // WM gravity/dim attributes to compose its native raster with the app.
                if (view !== base && params != null) {
                    val placed = android.graphics.Rect()
                    Gravity.apply(params.gravity, view.width, view.height, android.graphics.Rect(0, 0, base.width, base.height), params.x, params.y, placed)
                    xy[0] = placed.left; xy[1] = placed.top
                    if ((params.flags and WindowManager.LayoutParams.FLAG_DIM_BEHIND) != 0)
                        canvas.drawColor(android.graphics.Color.argb((params.dimAmount * 255).toInt(), 0, 0, 0))
                }
                canvas.save(); canvas.translate(xy[0].toFloat(), xy[1].toFloat()); view.draw(canvas); canvas.restore()
                metadata.put(JSONObject().put("class", view.javaClass.name).put("x", xy[0]).put("y", xy[1]).put("width", view.width).put("height", view.height)
                    .put("gravity", params?.gravity).put("dimAmount", params?.dimAmount))
            }
            File(output, "$id.png").outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            bitmap.recycle()
            File(output, "$id.json").writeText(JSONObject().put("id", id).put("capturedAtUtc", java.time.Instant.now().toString()).put("testOnlySyntheticData", true)
                .put("windowBounds", metadata).put("text", JSONArray(nativeNodes().flatMap(::labels).distinct())).toString(2))
        }
    }
    private fun runningFixture() {
        val nodes = listOf(
            ProxyNodeUi("香港 01", "VLESS", true, 28, "测试订阅"), ProxyNodeUi("香港 02", "VLESS", true, 31, "测试订阅"),
            ProxyNodeUi("日本 01", "VLESS", true, 42, "测试订阅"), ProxyNodeUi("日本 02", "VLESS", true, -1, "测试订阅"),
            ProxyNodeUi("新加坡 01", "VLESS", true, 36, "测试订阅"), ProxyNodeUi("台湾 01", "VLESS", true, 46, "测试订阅"),
            ProxyNodeUi("韩国 01", "VLESS", false, null, "测试订阅"), ProxyNodeUi("美国 01", "VLESS", true, 128, "测试订阅"))
        val groups = listOf("节点选择", "手动选择", "故障转移", "香港节点", "台湾节点", "日本节点", "新加坡节点", "韩国节点")
            .map { ProxyGroupUi(it, if (it == "手动选择") "Selector" else "URLTest", nodes.first().name, nodes) }
        fixture("state", vm.state.copy(running = true, panelReady = true, config = "测试配置.yaml", trafficMode = "rule",
            groups = groups, corePid = 1842, memoryBytes = 82L * 1024 * 1024, downloadTotal = 20L * 1024 * 1024 * 1024))
        fixture("runtime", ProxyRuntimeSnapshot(running = true, pid = 1842, elapsedSeconds = 1920, rssBytes = 82L * 1024 * 1024,
            lanAddress = "192.168.1.12", lanInterface = "wlan0", wanAddress = "203.0.113.24", wanCountryCode = "HK",
            wanCountry = "香港", wanCity = "香港", wanIsp = "Test Network", wanAsn = "AS64500",
            wanOrganization = "Fixture Org", wanIpType = "IPv4", wanTimezone = "Asia/Hong_Kong", wanCoordinates = "22.3193, 114.1694", cpuAffinity = "0-7", currentCpu = 2, wanState = "ready"))
        fixture("providers", listOf(DashboardProviderUi("测试订阅", "HTTP", "https://example.com/204", "", "2026-10-02T00:00:00Z", 0,
            20L*1024*1024*1024, 100L*1024*1024*1024, 1798761600, nodes.map { it.name }.toSet(), true)))
        fixture("coreVersion", "Mihomo test-fixture")
        vm.delays.putAll(nodes.mapNotNull { node -> node.lastDelay?.let { node.name to it } }.toMap())
        vm.resourceSamples.add(HomeResourceSample(2f, 82L*1024*1024))
    }

    @Test fun homeStoppedState() {
        show { HetuRoot(vm) }; expect("未运行"); capture("01-003-stopped")
    }
    @Test fun homeWanAndLanDetailsKeepRealFixtureValues() {
        runningFixture()
        show { HomePublicIpScreen(vm) {} }; expect("203.0.113.24"); expect("Fixture Org"); capture("01-009-public-ip-details")
        click("复制网络运营商")
        val clipboard = rule.activity.getSystemService(android.content.ClipboardManager::class.java)
        assertEquals("Test Network", clipboard.primaryClip!!.getItemAt(0).text.toString())
        click("复制ASN"); assertEquals("AS64500", clipboard.primaryClip!!.getItemAt(0).text.toString())
        click("局域网 IP"); expect("192.168.1.12"); expect("wlan0"); capture("01-010-lan-ip-details")
    }
    @Test fun resourceChartsKeepMissingSamplesAsGaps() {
        runningFixture()
        vm.resourceSamples.clear()
        vm.resourceSamples.addAll(List(60) { index ->
            HomeResourceSample(if (index in 28..31) null else (2.0 + kotlin.math.sin(index*.8)*.25 + kotlin.math.sin(index*.33)*.4).toFloat(),
                ((82.0 + kotlin.math.sin(index*.55)*2) * 1024 * 1024).toLong())
        }.dropLast(1) + HomeResourceSample(2f, 82L*1024*1024))
        show { HomeResourcesScreen(vm) {} }; expect("断开处为缺测"); capture("01-013-resource-gaps")
    }
    @Test fun resourceChartsWaitingStateDoesNotInventZero() {
        vm.resourceSamples.clear()
        show { HomeResourcesScreen(vm) {} }; expect("等待连接进行采样"); capture("01-014-resource-waiting")
    }
    @Test fun homeLifecycleAndSourceSelection() {
        runningFixture()
        show { HetuRoot(vm) }
        expect("运行中"); capture("01-001-running")
        expect("重载"); expect("停止"); expect("重启"); expect("规则"); capture("01-002-controls-expanded")
        rule.runOnUiThread { ProxyRuntimeSettings.markDirty(vm.prefs, "proxyBaseMode"); vm.bumpSettings() }; frame()
        expect("设置已修改，重启后生效")
        click("LAN"); expect("192.168.1.12"); capture("01-004-pending-lan")
        click("网速"); expect("网速数据来源"); capture("01-005-speed-source")
        click("本地模式")
        assertEquals("local", vm.prefs.getString("homeSpeedSource", ""))
        rule.runOnUiThread { fixture("operation", HxRunOp.Restart); fixture("operationText", "请稍候…") }; frame()
        expect("正在重启"); expectDisabledActionLabel("重启"); capture("01-008-restarting")
    }
    @Test fun homeStartAndFailure() {
        fixture("operation", HxRunOp.Start); fixture("operationText", "请稍候…")
        show { HetuRoot(vm) }
        expect("正在启动"); expectDisabledActionLabel("启动中"); capture("01-006-starting")
        rule.runOnUiThread { fixture("operation", null); vm.startupError = "测试夹具：配置加载失败\n第 18 行：缩进无效" }; frame()
        expect("启动失败")
        rule.runOnUiThread { assertTrue("Copy label is visible text, not only an icon description", nativeNodes().any { n -> n.config.getOrNull(SemanticsProperties.Text)?.any { it.text == "复制" } == true }) }
        capture("01-007-failed")
    }
    private fun expectDisabledActionLabel(label: String) = rule.runOnUiThread {
        val text = nativeNodes().firstOrNull { label in labels(it) }
        assertNotNull("Busy operation retains its action label: $label", text)
        assertTrue("Busy action remains disabled without hiding its label", generateSequence(text) { it.parent }
            .any { it.config.contains(SemanticsProperties.Disabled) })
    }
    @Test fun strategyExpansionSearchAndNodeInfo() {
        runningFixture(); vm.openPanel("proxies")
        show { HetuRoot(vm) }
        expect("节点选择"); capture("02-001-strategies")
        click("节点选择"); expect("香港 02"); capture("02-002-expanded")
        rule.runOnUiThread { vm.testingNodes["香港 02"] = true; vm.testingNodes["台湾 01"] = true }; frame()
        capture("02-028-node-statuses")
        longClick("香港 02"); expect("节点信息"); capture("02-013-node-info")
        click("关闭")
        click("搜索"); replace(0, "香港"); capture("02-019-search")
    }
    @Test fun strategySearchKeepsActualGroupCountsAndCurrentNode() {
        runningFixture()
        fixture("state", vm.state.copy(groups = vm.state.groups.take(1)))
        vm.openPanel("proxies")
        show { HetuRoot(vm) }
        expect("URLTest · 6/8")
        click("搜索"); replace(0, "日本 01")
        expect("URLTest · 6/8")
        expect("香港 01")
        rule.runOnUiThread {
            assertEquals(8, vm.state.groups.first().nodes.size)
            assertEquals("香港 01", vm.state.groups.first().now)
        }
    }
    @Test fun strategyMultiTermSearchKeepsMatchingNodeRows() {
        runningFixture()
        fixture("state", vm.state.copy(groups = vm.state.groups.take(1)))
        vm.openPanel("proxies"); show { HetuRoot(vm) }
        click("节点选择"); expect("日本 01"); expect("日本 02")
        click("搜索"); replace(0, "日本 VLESS")
        expect("日本 01"); expect("日本 02")
        rule.runOnUiThread { assertFalse(exists("香港 02")) }
        replace(0, "")
        expect("香港 02")
    }
    @Test fun strategyFilterAndApiDraft() {
        runningFixture(); vm.openPanel("proxies")
        show { HetuRoot(vm) }
        touch("筛选"); expect("显示隐藏策略"); capture("02-010-filter")
        click("显示隐藏策略")
        assertTrue(vm.prefs.getBoolean("proxySelectorShowHidden", false))
        assertFalse("Atlas requires filter to close after choosing", exists("显示隐藏策略"))
        click("排序与布局"); expect("测速与 API")
        rule.runOnUiThread {
            val selected = nativeNodes().first { "按配置" in labels(it) && it.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action != null }
            val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            assertTrue(selected.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts))
            assertEquals(androidx.compose.ui.graphics.Color(0xFF12161A), layouts.single().layoutInput.style.color)
        }
        click("1 列", 0); assertEquals(1, vm.prefs.getInt("proxySelectorGroupColumns", 2))
        click("2 列", 0); assertEquals(2, vm.prefs.getInt("proxySelectorGroupColumns", 1))
        capture("02-011-layout")
        click("测速与 API"); expect("测速地址")
        toggle(2); capture("02-027-api-default")
        toggle(0); toggle(1); toggle(2); replace(0, "https://example.com/generate_204")
        capture("02-012-api-expanded")
        click("取消")
        assertTrue(vm.prefs.getBoolean("proxyCustomApiEnabled", false))
    }
    @Test fun settingsNestedPagesAndMirrorValidation() {
        vm.tab = HxTab.Settings
        show { HetuRoot(vm) }
        expectDock(true)
        click("默认面板"); expectDock(false); expect("默认面板页面"); capture("04-007-default-panel")
        touch("默认面板页面"); capture("04-018-default-panel-menu")
        click("策略"); assertEquals("proxies", vm.prefs.getString("defaultPanelSection", ""))
        click("返回"); expectDock(true); click("开机启动与下载"); expectDock(false); capture("04-006-startup-download")
        click("加速地址"); capture("04-029-mirror-dialog")
        replace(0, "mirror.example"); click("保存"); expect("请填写 http/https 地址"); capture("04-030-mirror-invalid")
        assertEquals("", vm.prefs.getString("downloadMirrorPrefix", ""))
        click("取消"); click("返回"); expectDock(true); click("备份与恢复"); expectDock(false); capture("04-010-backup")
        back(); expectDock(true)
    }
    @Test fun themeMenusAndScroll() {
        show { HxThemeLabScreen(vm) {} }
        capture("04-008-theme")
        touch("语言"); capture("04-019-language-menu"); click("简体中文")
        assertEquals("zh-CN", vm.prefs.getString("appLanguage", ""))
        scroll(630f); capture("04-009-theme-motion")
        touch("顶栏模糊样式"); capture("04-020-top-blur-menu"); click("高斯模糊")
        assertEquals("gaussian", vm.prefs.getString("topBarBlurStyle", ""))
        touch("界面缩放"); capture("04-021-scale-menu"); click("100%")
    }
    @Test fun notificationMenusAreDraftsAndBackDiscards() {
        var left = false
        show { NotificationSettingsScreen(vm) { left = true } }
        capture("04-011-notification")
        touch("刷新频率"); capture("04-022-refresh-menu"); click("5 秒")
        assertEquals(3, vm.prefs.getInt(ProxyStatusNotificationService.PREF_REFRESH_SECONDS, 3))
        touch("点击通知打开")
        rule.runOnUiThread {
            val seventh = nativeNodes().first { "设置" in labels(it) && it.boundsInWindow.height > 0f }
            val viewport = generateSequence(seventh) { it.parent }.first { it.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange) != null }
            assertTrue("All seven PDF target rows fit in the measured scroll viewport", seventh.boundsInWindow.bottom <= viewport.boundsInWindow.bottom + .5f)
        }
        capture("04-023-target-menu"); click("工具")
        assertEquals("Home", vm.prefs.getString(ProxyStatusNotificationService.PREF_CLICK_TARGET, "Home"))
        scroll(580f); capture("04-012-notification-actions")
        touch("动作", 0); capture("04-024-action-one"); click("重载")
        touch("动作", 1); capture("04-025-action-two"); click("重启")
        touch("动作", 2); capture("04-026-action-three"); click("停止")
        click("返回"); assertTrue(left)
        assertEquals(3, vm.prefs.getInt(ProxyStatusNotificationService.PREF_REFRESH_SECONDS, 3))
    }
    @Test fun toolsSearchAndConfigImportValidation() {
        vm.tab = HxTab.Tools
        show { HetuRoot(vm) }
        click("搜索"); replace(0, "网络"); capture("03A-003-tools-search")
        click("关闭搜索")
        click("配置管理"); expect("配置与订阅")
        click("导入配置"); capture("03A-027-import-file-empty")
        click("从链接导入"); replace(0, "https://example.com/config.yaml"); replace(1, "测试配置.yaml")
        capture("03A-024-import-link")
        replace(0, "example.com/config.yaml"); click("导入配置"); expect("请输入有效的 http/https 链接")
        capture("03A-020-import-link-invalid")
        click("返回"); expect("放弃填写？"); capture("03A-012-discard-import")
        click("放弃"); expect("配置与订阅")
    }
    @Test fun subscriptionFormRequiredFieldsAndDiscard() {
        show { ConfigsScreen(vm) }
        click("添加订阅"); capture("03A-005-add-subscription-empty")
        replace(1, "https://example.com/subscription"); click("保存"); expect("名称不能为空"); capture("03A-021-subscription-empty-name")
        replace(0, "测试订阅"); replace(1, "example.com/subscription"); click("保存")
        expect("请输入有效的 http/https 链接"); capture("03A-022-subscription-invalid-url")
        click("返回"); expect("放弃填写？"); click("取消"); expect("添加订阅")
        click("返回"); click("放弃"); expect("配置与订阅")
    }
    private fun panelDataFixture() {
        runningFixture()
        fixture("state", vm.state.copy(connections = listOf(
            ProxyConnectionUi("qa-1", "api.example.com", "DOMAIN-SUFFIX", "example.com", "节点选择 → 香港 01", 128000, 4096000,
                network = "TCP", inbound = "TProxy", appName = "测试应用", packageName = "com.example.qa", startedAt = "2026-10-02T08:00:00Z", addressType = "IPv4"),
            ProxyConnectionUi("qa-2", "direct.example.org", "GEOIP", "CN", "DIRECT", 32000, 256000, network = "UDP", inbound = "TProxy",
                appName = "另一测试应用", packageName = "org.example.qa", startedAt = "2026-10-02T08:01:00Z", addressType = "IPv4"))))
        fixture("rules", listOf(ProxyRuleUi(0, "DOMAIN-SUFFIX", "example.com", "节点选择"), ProxyRuleUi(1, "GEOIP", "CN", "DIRECT"),
            ProxyRuleUi(2, "MATCH", "", "节点选择")))
        fixture("ruleSets", listOf(DashboardRuleSetUi("测试域名规则", "domain", "yaml", "HTTP", 12345, "2026-10-02 08:00"),
            DashboardRuleSetUi("测试直连规则", "ipcidr", "mrs", "HTTP", 54321, "2026-10-02 08:00")))
    }
    @Test fun panelConnectionsMenusDetailsAndCancel() {
        panelDataFixture(); vm.openPanel("conn")
        show { HetuRoot(vm) }
        expect("api.example.com"); capture("02-006-connections")
        touch("连接排序"); capture("02-017-connection-sort"); click("主机")
        touch("连接筛选"); capture("02-021-connection-filter"); click("代理")
        click("api.example.com"); expect("断开此连接"); expect("关闭"); capture("02-014-connection-detail"); click("关闭")
        touch("连接显示"); capture("02-018-connection-display"); click("断开全部连接")
        expect("断开全部连接？"); capture("02-015-disconnect-confirm"); click("取消")
        assertEquals(2, vm.state.connections.size)
        click("搜索"); replace(0, "example"); click("按应用"); click("测试应用"); capture("02-024-grouped-search")
    }
    @Test fun panelRulesProvidersAndTaskStatuses() {
        panelDataFixture(); vm.openPanel("providers")
        show { HetuRoot(vm) }
        expect("测试订阅"); capture("02-005-providers")
        rule.runOnUiThread { vm.providerTasks["测试订阅"] = HxTask(running = true) }; frame()
        click("搜索"); replace(0, "测试"); capture("02-022-provider-updating")
        rule.runOnUiThread { vm.providerTasks["测试订阅"] = HxTask(ok = false, message = "受控测试错误") }; frame()
        capture("02-022-provider-failed")
        // Change only the view destination and local fixtures, never invoke update/retry.
        rule.runOnUiThread { vm.openPanel("rules") }; frame()
        expect("DOMAIN-SUFFIX"); capture("02-007-rules")
        rule.runOnUiThread { vm.openPanel("sets") }; frame()
        expect("测试域名规则"); capture("02-008-rulesets")
        rule.runOnUiThread {
            vm.ruleSetTasks["测试域名规则"] = HxTask(running = true)
            vm.ruleSetTasks["测试直连规则"] = HxTask(ok = false, message = "受控测试错误")
        }; frame(); click("搜索"); replace(0, "测试"); capture("02-025-ruleset-statuses")
        rule.runOnUiThread { vm.openPanel("rules") }; frame()
        click("搜索"); replace(0, "no-match-fixture"); expect("没有匹配的规则"); capture("02-023-rules-empty")
    }
    @Test fun panelOverviewRankingMenus() {
        panelDataFixture(); vm.openPanel("overview")
        vm.rateHistory.addAll(listOf(1000, 6000, 2500, 8000, 5500, 9000).map(Int::toLong))
        vm.upHistory.addAll(listOf(600, 1500, 1200, 2400, 1800, 3000).map(Int::toLong))
        show { HetuRoot(vm) }
        capture("02-003-overview")
        touch("排行方式"); capture("02-020-rank-order"); click("按总流量")
        assertEquals("traffic", vm.prefs.getString("panelOverviewRankSort", ""))
        swipeUp(); capture("02-004-overview-scrolled")
        touch("显示数量"); capture("02-016-rank-count"); click("10")
        assertEquals(10, vm.prefs.getInt("panelOverviewRankCount", 0))
    }
    @Test fun directTargetsRejectDuplicateAndSaveValidDraft() {
        var left = false
        show { ProxyLatencyTargetsScreen { left = true } }
        replace(0, "测试 A"); replace(1, "https://example.com/204")
        replace(2, "测试 A"); replace(3, "https://example.net/204")
        replace(4, "测试 C"); replace(5, "https://example.org/204")
        click("保存"); expect("三个测速目标名称不能重复。"); capture("01-012-direct-targets-invalid")
        assertNotEquals("测试 A", ProxyLatencyTargets.load(vm.prefs)[0].name)
        replace(2, "测试 B"); click("保存"); expect("测速目标已保存；返回首页后立即生效。")
        capture("01-011-direct-targets-saved")
        assertFalse(left); assertEquals("测试 B", ProxyLatencyTargets.load(vm.prefs)[1].name)
    }
    @Test fun bypassDraftAndDiscard() {
        vm.prefs.edit().putStringSet("proxyBypassCidrs", setOf("10.0.0.0/8"))
            .putStringSet("proxyBypassInterfaces", setOf("dummy0")).commit()
        var left = false
        show { BypassRulesScreen(vm) { left = true } }
        capture("03A-036-bypass-rules")
        replace(0, "192.0.2.0/24"); click("返回"); expect("放弃修改？"); capture("03A-037-bypass-discard")
        click("取消"); assertFalse(left)
        click("返回"); click("放弃"); assertTrue(left)
        assertEquals(setOf("10.0.0.0/8"), vm.prefs.getStringSet("proxyBypassCidrs", emptySet()))
    }
    @Test fun networkMatchMenusKeepAutomationDisabled() {
        vm.prefs.edit().putBoolean("networkMatchEnabled", false).putString("networkMatchLastEnvironment", "Wi-Fi · Test Network").commit()
        show { HxNetworkMatchScreen(vm) {} }
        capture("03B-030-network-match")
        touch("匹配成功"); capture("03B-031-match-action"); click("不操作")
        assertEquals("none", vm.prefs.getString("networkMatchAction", ""))
        touch("条件失配"); capture("03B-032-unmatch-action"); click("不操作")
        assertFalse(vm.prefs.getBoolean("networkMatchEnabled", true))
    }
    @Test fun bundledLicenseSheets() {
        show { AboutScreen(vm) }
        capture("04-013-about")
        scroll(250f)
        click("Mihomo"); expectEventually("复制"); capture("04-031-mihomo-license"); dismissSheet()
        click("AdGuard DNS Filter"); expectEventually("复制"); capture("04-032-adguard-license"); dismissSheet()
        click("Lucide Icons"); expectEventually("复制"); capture("04-033-lucide-license"); dismissSheet()
    }
    @Test fun actualAdvancedSettingsActivityAndDnsDialog() {
        show { }
        val controller = Robolectric.buildActivity(ProxyAdvancedSettingsActivity::class.java).setup()
        try {
            frame(); expect("高级代理配置"); capture("04-004-advanced-settings")
            touch("DNS 劫持策略"); expect("REDIRECT"); capture("04-027-dns-dialog")
            click("取消"); scroll(560f); capture("04-005-resource-limits")
        } finally { controller.pause().stop().destroy(); frame() }
    }
    @Test fun corePickerKeepsRuntimeSelectionAndThreeCards() {
        val original = ProxyRuntimeProfile.load(vm.prefs).core
        show { CoresScreen(vm) {} }
        expect("Mihomo"); expect("Xray"); expect("sing-box")
        capture("03A-034-core-three-cards")
        touch("Mihomo"); expect("Mihomo Smart"); expect("其他可管理核心")
        capture("core-mihomo-variant-picker")
        click("Mihomo Smart")
        assertEquals("mihomo-smart", vm.prefs.getString("coreManagerSlot.mihomo", ""))
        assertEquals(original, ProxyRuntimeProfile.load(vm.prefs).core)
        expect("Mihomo Smart"); expect("Xray"); expect("sing-box")
        capture("core-three-cards-smart-selected")
    }
    @Test fun webPanelCreateEditCancelDeleteAndMode() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        HetuWebPanels.save(context, listOf(HetuWebPanel("qa-only", "测试面板", "https://example.com/panel")))
        show { ProxyWebPanelsScreen {} }
        capture("03B-033-web-panels")
        click("添加"); replace(0, "旅行面板"); replace(1, "https://example.org/panel"); capture("03B-034-add-web-panel")
        click("关闭"); assertEquals(1, HetuWebPanels.list(context).size)
        click("编辑 测试面板"); capture("03B-035-edit-web-panel"); replace(0, "已编辑测试面板"); click("保存")
        assertEquals("已编辑测试面板", HetuWebPanels.list(context).single().name)
        click("删除 已编辑测试面板"); capture("03B-036-delete-web-panel"); click("关闭")
        assertEquals(1, HetuWebPanels.list(context).size)
        click("本地面板模式"); capture("03B-037-local-mode"); click("河图内置面板")
        assertEquals("builtin", vm.prefs.getString("proxyWebPanelLocalMode", ""))
    }
}
