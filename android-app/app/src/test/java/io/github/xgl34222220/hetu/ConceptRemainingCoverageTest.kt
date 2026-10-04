package io.github.xgl34222220.hetu

import android.app.Application
import android.content.Context
import android.widget.TextView
import android.widget.PopupWindow
import io.github.rosemoe.sora.widget.CodeEditor
import org.robolectric.annotation.Implements
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Resetter
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
@Config(sdk = [35], qualifiers = "zh-rCN-w393dp-h852dp-mdpi", application = Application::class, shadows = [RemainingRootBridgeShadow::class, RemainingRootManagerShadow::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ConceptRemainingCoverageTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private lateinit var vm: HetuViewModel
    private lateinit var server: MockWebServer
    private val output = File("build/outputs/concept-remaining-coverage")

    @Before fun prepare() {
        RemainingRootBridgeShadow.reset()
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
            .putBoolean("hetuLegacyAppDataMigrated", true).putBoolean("enableBlur", false).putBoolean("predictiveBackAnimation", false)
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
        assertEquals("Unexpected Root calls were blocked", emptyList<String>(), RemainingRootBridgeShadow.unexpected.toList())
    }
    private fun frame() {
        repeat(4) {
            rule.mainClock.advanceTimeBy(200)
            rule.runOnUiThread { Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100)) }
        }
    }
    private fun show(content: @Composable () -> Unit) {
        rule.runOnUiThread { rule.activity.setContent {
            HetuAppTheme("light", false) { CompositionLocalProvider(LocalNav provides HxNav()) {
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
    @Suppress("UNCHECKED_CAST")
    private fun windows(): List<View> {
        if (android.os.Build.VERSION.SDK_INT >= 29) return WindowInspector.getGlobalWindowViews()
        val type = Class.forName("android.view.WindowManagerGlobal")
        val instance = type.getMethod("getInstance").invoke(null)
        return type.getDeclaredField("mViews").apply { isAccessible = true }.get(instance) as List<View>
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
        windows().asReversed().forEach(::visitView)
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
            val window = windows().last { contains(it) }
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
            // A real down/cancel supplies the production pointer-owned anchor, then
            // the accessibility long-click action opens the same item menu safely.
            val at = n.boundsInWindow.center
            val window = rule.activity.window.decorView
            val time = SystemClock.uptimeMillis()
            val down = MotionEvent.obtain(time, time, MotionEvent.ACTION_DOWN, at.x, at.y, 0)
            window.dispatchTouchEvent(down); down.recycle()
            val cancel = MotionEvent.obtain(time, time + 10, MotionEvent.ACTION_CANCEL, at.x, at.y, 0)
            window.dispatchTouchEvent(cancel); cancel.recycle()
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
            val node = nativeNodes().lastOrNull { it.config.getOrNull(SemanticsActions.Dismiss)?.action != null }
            if (node != null) assertTrue(node.config[SemanticsActions.Dismiss].action!!.invoke())
            else {
                val window = windows().last()
                val time = SystemClock.uptimeMillis()
                val down = MotionEvent.obtain(time,time,MotionEvent.ACTION_DOWN,3f,800f,0)
                window.dispatchTouchEvent(down); down.recycle()
                val up = MotionEvent.obtain(time,time+80,MotionEvent.ACTION_UP,3f,800f,0)
                window.dispatchTouchEvent(up); up.recycle()
            }
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
            val windows = windows().filter { it.visibility == View.VISIBLE && it.width > 0 && it.height > 0 }
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
            File(output, "$id.json").writeText(JSONObject().put("id", id).put("capturedAtUtc", java.time.Instant.now().toString()).put("testOnlySyntheticData", true).put("apiLevel", android.os.Build.VERSION.SDK_INT).put("interceptedRootCommands", JSONArray(RemainingRootBridgeShadow.commands.toList()))
                .put("windowBounds", metadata).put("text", JSONArray(nativeNodes().flatMap(::labels).distinct())).toString(2))
        }
    }
    private val yaml = """
        mixed-port: 7890
        allow-lan: true
        mode: rule
        log-level: info
        external-controller: 127.0.0.1:9090
        dns:
          enable: true
          enhanced-mode: fake-ip
        proxy-providers:
          机场订阅:
            type: http
            url: 'https://example.com/subscription'
            interval: 86400
        proxy-groups:
          - name: 节点选择
            type: select
            use: [机场订阅]
        rules:
          - MATCH,节点选择
    """.trimIndent()
    private fun configFiles(): ProxyConfigLibrary {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val library = ProxyConfigLibrary(app)
        library.importConfig(ProxyRuntimeProfile.Core.MIHOMO, "备用配置.yaml", yaml.byteInputStream())
        library.importConfig(ProxyRuntimeProfile.Core.MIHOMO, "日常使用.yaml", yaml.byteInputStream())
        vm.prefs.edit().putString("proxyBaseCore", "mihomo").commit()
        val actual = kotlinx.coroutines.runBlocking { vm.controller.configLibrary().map { it.name } }
        assertTrue("Controller must see the isolated config fixture: $actual", actual.containsAll(listOf("备用配置.yaml", "日常使用.yaml")))
        return library
    }
    private fun editor(): CodeEditor {
        fun visit(view: View): CodeEditor? {
            if (view is CodeEditor) return view
            if (view is ViewGroup) for (i in 0 until view.childCount) visit(view.getChildAt(i))?.let { return it }
            return null
        }
        return windows().mapNotNull(::visit).first()
    }
    private fun changeEditor(text: String) { rule.runOnUiThread { editor().setText(text) }; frame() }
    private fun nativeClick(label: String) {
        rule.runOnUiThread {
            fun visit(v: View): View? {
                if ((v as? TextView)?.text?.toString() == label || v.contentDescription?.toString() == label) return v
                if (v is ViewGroup) for (i in 0 until v.childCount) visit(v.getChildAt(i))?.let { return it }
                return null
            }
            val v = windows().asReversed().mapNotNull(::visit).first()
            val target = generateSequence(v) { it.parent as? View }.first { it.isClickable }
            assertTrue(target.performClick())
        }; frame()
    }
    private fun dismissPopup() {
        rule.runOnUiThread { Shadows.shadowOf(ApplicationProvider.getApplicationContext<Application>()).latestPopupWindow?.dismiss() }; frame()
    }
    @Test fun shadowFailsClosedWithoutRunningShell() {
        try { RootBridge.rootShell(rule.activity, "UNEXPECTED_SENTINEL", 1000); fail("Must fail closed") }
        catch (_: SecurityException) { }
        assertEquals(listOf("UNEXPECTED_SENTINEL"), RemainingRootBridgeShadow.unexpected.toList())
        RemainingRootBridgeShadow.unexpected.clear()
    }
    @Test fun configMenusAndCancelledDestructiveDialogs() {
        val library = configFiles()
        show { ConfigsScreen(vm) }; expectEventually("日常使用.yaml")
        longClick("日常使用.yaml"); expect("重命名")
        rule.runOnUiThread {
            val action = nativeNodes().first { "导出配置" in labels(it) }
            val row = generateSequence(action) { it.parent }.first { it.config.getOrNull(SemanticsActions.OnClick)?.action != null }
            assertTrue("Config actions preserve the measured32dp density; actual=${row.boundsInWindow.height}", row.boundsInWindow.height in 31.5f..33f)
        }
        capture("03A-006-current-config-menu"); dismissSheet()
        longClick("备用配置.yaml"); capture("03A-007-other-config-menu"); dismissSheet()
        longClick(ProxyConfigLibrary.BUNDLED_NAME); capture("03A-008-bundled-config-menu"); dismissSheet()
        longClick("日常使用.yaml"); click("重命名"); capture("03A-009-rename-config"); click("取消")
        longClick("日常使用.yaml"); click("删除配置"); expect("删除配置？"); capture("03A-010-delete-config"); click("取消")
        scroll(360f); click("删除订阅"); capture("03A-011-delete-subscription"); click("取消")
        assertEquals("日常使用.yaml", library.selected(ProxyRuntimeProfile.Core.MIHOMO)!!.name)
        assertEquals(1, library.subscriptions(library.selected(ProxyRuntimeProfile.Core.MIHOMO)).size)
    }
    @Config(qualifiers = "zh-rCN-w320dp-h852dp-mdpi")
    @Test fun narrowConfigLongNameKeepsMenuAndCancelReachable() {
        val library = configFiles()
        val name = "出差备用机场配置用于检查较长标题的布局.yaml"
        library.importConfig(ProxyRuntimeProfile.Core.MIHOMO, name, yaml.byteInputStream())
        show { ConfigsScreen(vm) }; expectEventually(name)
        capture("extra-configs-narrow-long-name")
        longClick(name); expect("导出配置"); expect("重命名")
        capture("extra-configs-narrow-long-name-menu")
        click("重命名"); expect("重命名配置"); click("取消")
        assertEquals(name, library.selected(ProxyRuntimeProfile.Core.MIHOMO)!!.name)
        var query by mutableStateOf("香港超长节点名称 Long node ".repeat(8))
        show {
            CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(1f, 1.4f)) {
                HxSearchField(query, { query = it }, "搜索", autoFocus = true)
            }
        }
        rule.runOnUiThread {
            val field = nativeNodes().first { it.config.getOrNull(SemanticsActions.SetText)?.action != null }
            assertTrue("Narrow large-font search stays within viewport", field.boundsInWindow.right <= 321f)
            assertTrue("Large font input grows instead of clipping", field.boundsInWindow.height >= 44f)
        }
        capture("extra-search-narrow-large-font")
        click("清除"); assertEquals("", query)
        replace(0, "重新输入"); assertEquals("重新输入", query)
    }
    @Test fun existingSubscriptionInvalidDraftAndCancel() {
        configFiles(); show { ConfigsScreen(vm) }; expectEventually("日常使用.yaml"); scroll(360f)
        click("机场订阅"); expect("编辑订阅"); capture("03A-026-edit-subscription")
        replace(0, "not-a-url"); click("保存"); expect("请输入有效的 http/https 链接"); capture("03A-023-edit-subscription-invalid")
        click("返回"); click("放弃"); expect("配置与订阅")
    }
    @Test fun yamlEditorOutlineAndDraftDiscard() {
        configFiles(); show { ConfigEditorScreen(vm) {} }; expectEventually("日常使用.yaml")
        capture("03A-025-yaml-editor"); click("语法大纲"); capture("03A-013-yaml-outline"); dismissSheet()
        changeEditor(yaml + "\n# 未保存的本地草稿\n")
        click("返回"); expect("放弃修改？"); capture("03A-019-yaml-discard"); click("取消")
        assertTrue(editor().text.toString().contains("未保存"))
    }
    @Test fun yamlSelectionConflictPreservesDraft() {
        val library = configFiles(); show { ConfigEditorScreen(vm) {} }; expectEventually("日常使用.yaml")
        changeEditor(yaml + "\n# 草稿\n")
        library.select(ProxyRuntimeProfile.Core.MIHOMO, "备用配置.yaml")
        click("保存"); expectEventually("保存遇到冲突"); capture("03A-014-yaml-selection-conflict")
        click("重新读取"); expect("放弃草稿并重新读取？"); capture("03A-018-yaml-reload-confirm"); click("取消")
        assertTrue(editor().text.toString().contains("# 草稿"))
    }
    @Test fun yamlExternalFileConflictPreservesDraft() {
        val library = configFiles(); show { ConfigEditorScreen(vm) {} }; expectEventually("日常使用.yaml")
        changeEditor(yaml + "\n# 草稿\n")
        library.write(library.selected(ProxyRuntimeProfile.Core.MIHOMO), yaml + "\n# 外部修改\n")
        click("保存"); expectEventually("文件已在其他位置修改"); capture("03A-017-yaml-external-conflict")
        click("保留草稿"); assertTrue(editor().text.toString().contains("# 草稿"))
    }
    @Test fun yamlReadAndValidationFailures() {
        val library = configFiles()
        library.selected(ProxyRuntimeProfile.Core.MIHOMO)!!.file.writeBytes(byteArrayOf(0xC3.toByte(),0x28))
        show { ConfigEditorScreen(vm) {} }; expectEventually("配置读取失败"); capture("03A-016-yaml-read-error")
        library.write(library.selected(ProxyRuntimeProfile.Core.MIHOMO), yaml)
        click("重新读取"); expectEventually("日常使用.yaml")
        changeEditor("dns: [invalid\n")
        click("校验"); expectEventually("配置无效：测试夹具：第 1 行 YAML 语法错误")
        capture("03A-015-yaml-validation-error")
    }
    @Config(sdk = [28]) @Test fun runtimeEditorNativeViewMenusJumpSearchAndDiscard() {
        val model = RuntimeEditorModel().apply { content = RuntimeFileContent(yaml, RuntimeFilesRepository.digest(yaml.toByteArray()), true, "UTF-8") }
        show { RuntimeFileEditorScreen("/data/adb/hetu/config.yaml", model) {} }
        expect("config.yaml"); capture("03B-012-runtime-editor")
        touch("更多"); expect("跳转到行"); capture("03B-026-runtime-editor-menu")
        click("跳转到行"); capture("03B-027-runtime-editor-jump"); click("继续编辑")
        touch("更多"); click("搜索"); replace(0, "mode")
        expectEventually("2 个匹配")
        rule.runOnUiThread { assertEquals(2, editor().searcher.matchedPositionCount) }
        capture("03B-029-runtime-editor-search")
        click("下一个匹配"); click("上一个匹配")
        click("清空搜索"); expectEventually("0 个匹配")
        rule.runOnUiThread { assertFalse(editor().searcher.hasQuery()) }
        back(); changeEditor(yaml + "\n# runtime draft\n"); click("返回"); capture("03B-028-runtime-editor-discard"); click("继续编辑")
    }
    @Test fun fileManagerMenusFormsValidationAndCancellations() {
        show { FileManagerScreen(vm) {} }; expectEventually("config.yaml")
        rule.runOnUiThread {
            val heading = nativeNodes().filter { "文件管理" in labels(it) }.maxBy { it.boundsInWindow.height }
            val search = nativeNodes().first { "搜索" in labels(it) }
            assertTrue("Child-page actions stay in the toolbar above the large title", search.boundsInWindow.center.y < heading.boundsInWindow.top)
        }
        capture("03B-013-files-list")
        touch("更多")
        rule.runOnUiThread {
            val lastAction = nativeNodes().first { "新建文件夹" in labels(it) && it.boundsInWindow.height > 0f }
            assertTrue("Topbar more opens its anchored reference menu, not a bottom sheet", lastAction.boundsInWindow.top < 300f)
        }
        capture("03B-015-files-more"); click("新建文件")
        rule.runOnUiThread {
            val title = nativeNodes().first { "新建文件" in labels(it) && it.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action != null }
            val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            assertTrue(title.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts))
            assertEquals(26f, layouts.single().layoutInput.style.fontSize.value, .01f)
            assertEquals(0f, layouts.single().getLineLeft(0), 1f)
        }
        capture("03B-018-files-new-file"); click("取消")
        touch("更多"); click("新建文件夹"); capture("03B-019-files-new-folder"); click("取消")
        touch("更多"); click("下载"); replace(0,"https://example.com/config.yaml"); capture("03B-020-files-download")
        replace(0,"file:///tmp/config.yaml"); click("下载"); expect("请填写 HTTPS 下载地址"); capture("03B-021-files-download-invalid"); click("取消")
        longClick("config.yaml")
        rule.runOnUiThread {
            val label = nativeNodes().first { "编辑" in labels(it) }
            val row = generateSequence(label) { it.parent }.first { it.config.getOrNull(SemanticsActions.OnClick)?.action != null }
            assertTrue("Reference file menu keeps its measured43dp action rows", row.boundsInWindow.height in 42.5f..44.5f)
        }
        capture("03B-016-file-menu"); click("重命名"); capture("03B-022-files-rename")
        click("重命名"); expect("名称没有变化"); capture("03B-025-files-name-unchanged"); click("取消")
        longClick("config.yaml"); click("删除"); capture("03B-023-file-delete"); click("取消")
        longClick("backup")
        rule.runOnUiThread {
            val label = nativeNodes().first { "打开" in labels(it) }
            val row = generateSequence(label) { it.parent }.first { it.config.getOrNull(SemanticsActions.OnClick)?.action != null }
            assertTrue("Reference folder menu keeps its measured36dp action rows", row.boundsInWindow.height in 35.5f..37.5f)
        }
        capture("03B-017-folder-menu"); click("删除"); capture("03B-024-folder-delete"); click("取消")
        click("backup"); expectEventually("backup.yaml"); replace(0,"backup"); capture("03B-014-files-subfolder-search")
    }
    @Config(sdk = [28]) @Test fun realScriptsActivityReadFixturesAndCancelledDialogs() {
        show {}
        val controller = Robolectric.buildActivity(ProxyScriptsActivity::class.java).setup()
        try {
            expectEventually("network-check.sh"); capture("03B-006-scripts")
            click("更多"); capture("03B-007-scripts-menu"); click("脚本环境"); capture("03B-011-script-environment"); dismissSheet()
            click("服务启动前"); expectEventually("脚本内容"); capture("03B-008-pre-start-hook"); dismissSheet()
            click("服务停止后"); expectEventually("脚本内容"); capture("03B-009-post-stop-hook"); dismissSheet()
            click("删除"); capture("03B-010-script-delete"); dismissSheet()
        } finally { controller.pause().stop().destroy(); frame() }
    }
    @Config(sdk = [28]) @Test fun existingScriptNamesCannotCrashTheActualPageWithDuplicateKeys() {
        RemainingRootBridgeShadow.managedListing = "a b.sh\t12\na_b.sh\t34\n"
        show {}
        val controller = Robolectric.buildActivity(ProxyScriptsActivity::class.java).setup()
        try { expectEventually("a b.sh"); expectEventually("a_b.sh"); frame() }
        finally { controller.pause().stop().destroy(); frame() }
    }
    @Config(sdk = [28]) @Test fun rootReadFailureShowsActualErrorDetailsInsteadOfSuccess() {
        RemainingRootBridgeShadow.managedFailure = true
        show {}
        val controller = Robolectric.buildActivity(ProxyScriptsActivity::class.java).setup()
        try { expectEventually("详情"); click("详情"); expectEventually("复制诊断") }
        finally { controller.pause().stop().destroy(); frame() }
    }
    @Test fun logFilesSelectionSearchAndCancelledClear() {
        show { HxLogFilesScreen(vm) {} }; expectEventually("INFO"); capture("03B-002-log-files")
        touch("选择日志"); capture("03B-003-log-file-picker"); click("core.log")
        click("清空当前日志"); capture("03B-004-log-clear"); click("取消")
        click("搜索"); replace(0,"example.com"); click("[TCP] 192.168.1.12:52000 --> example.com:443 match MATCH using 节点选择[香港 01]")
        capture("03B-005-log-search-expanded")
    }
    @Test fun startupConfigReadAndRecoveryCancel() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        RootProxyManager(app).startupFile().apply { parentFile!!.mkdirs(); writeText(yaml) }
        show { SettingsStartupConfigScreen {} }; expectEventually(yaml); capture("04-014-startup-config")
        configFiles(); show { DiagnosticsScreen(vm) {} }; click("启动配置"); expectEventually("复制")
        rule.runOnUiThread { assertTrue("Diagnostic copy affordance includes visible text", nativeNodes().any { n -> n.config.getOrNull(SemanticsProperties.Text)?.any { it.text == "复制" } == true }) }
        capture("03A-041-diagnostic-startup"); dismissSheet()
        click("恢复网络"); capture("03A-042-diagnostic-recovery"); click("取消")
        click("消息与网络诊断"); expectEventually("受控诊断快照：无实际 Root 或网络操作"); capture("03A-043-diagnostic-network"); dismissSheet()
    }
    @Test fun basicProxyActualNativeActivityAndMenus() {
        configFiles(); show {}
        val controller = Robolectric.buildActivity(RootTproxyActivity::class.java).setup()
        try {
            frame(); capture("04-003-basic-proxy")
            nativeClick("代理核心"); capture("04-015-core-choice"); dismissPopup()
            nativeClick("运行模式"); capture("04-016-run-mode-choice"); dismissPopup()
            nativeClick("IPv6"); capture("04-017-ipv6-choice"); dismissPopup()
        } finally { controller.pause().stop().destroy(); frame() }
    }
    @Test fun toolsAndSettingsCollapsedHeaders() {
        fun assertDockVisibleAfterScroll() = rule.runOnUiThread {
            val dock = nativeNodes().single { it.config.getOrNull(SemanticsProperties.TestTag) == "hetu-dock" }
            val viewportBottom = rule.activity.window.decorView.height.toFloat()
            assertTrue("Collapsed root pages retain the reference dock within the viewport",
                dock.boundsInWindow.height >= 48f && dock.boundsInWindow.top < viewportBottom - 40f && dock.boundsInWindow.bottom <= viewportBottom + 1f)
        }
        vm.tab=HxTab.Tools; show { HetuRoot(vm) }; swipeUp(); assertDockVisibleAfterScroll(); capture("03A-002-tools-collapsed")
        rule.runOnUiThread { vm.tab=HxTab.Settings }; frame(); swipeUp(); assertDockVisibleAfterScroll(); capture("04-002-settings-collapsed")
        rule.runOnUiThread {
            val basic = nativeNodes().filter { "基础代理配置" in labels(it) }
            assertTrue("The first settings group can scroll above the collapsed reference header",
                basic.none { it.boundsInWindow.height > 0f && it.boundsInWindow.bottom > 52f })
        }
        click("默认面板"); expectDock(false); back(); expectDock(true); assertDockVisibleAfterScroll()
    }
    @Test fun appListModesSortMenuAndSearch() {
        val apps = listOf("微信" to "com.example.chat", "浏览器" to "com.example.browser", "应用商店" to "com.example.store", "地图" to "com.example.maps", "音乐" to "com.example.music", "设置" to "com.example.settings")
            .mapIndexed { i, pair -> io.github.xgl34222220.hetu.ui.AppItem(pair.first, pair.second, i == 5, uid = 10001 + i) }
        vm.filters.javaClass.getDeclaredField("appCache").apply { isAccessible = true }.set(vm.filters, apps)
        vm.prefs.edit().putString("proxyAppScope", "blacklist").putStringSet("proxyAppPackages", setOf("com.example.chat")).commit()
        show { AppListScreen(vm) }; expectEventually("微信"); capture("03A-028-app-blacklist")
        var summaryBottom = 0f
        rule.runOnUiThread {
            val summary = nativeNodes().single { it.config.getOrNull(SemanticsProperties.TestTag) == "app-routing-summary" }
            summaryBottom = summary.boundsInWindow.bottom
            assertTrue("Selection summary stays at the viewport bottom, not after short results", summaryBottom > rule.activity.window.decorView.height - 24f)
        }
        touch("排序"); capture("03A-029-app-sort"); click("按 UID")
        touch("更多"); capture("03A-030-app-more"); click("隐藏系统应用")
        click("白名单"); capture("03A-031-app-whitelist"); assertEquals("whitelist", vm.prefs.getString("proxyAppScope", ""))
        click("核心"); capture("03A-032-app-core-mode")
        click("黑名单"); click("搜索"); replace(0,"微信"); capture("03A-033-app-search")
        rule.runOnUiThread {
            val summary = nativeNodes().single { it.config.getOrNull(SemanticsProperties.TestTag) == "app-routing-summary" }
            assertEquals("Search cannot pull the summary up into the scrolling list", summaryBottom, summary.boundsInWindow.bottom, 1f)
        }
        click("清除"); expect("浏览器")
        assertEquals(setOf("com.example.chat"), vm.prefs.getStringSet("proxyAppPackages", emptySet()))
    }
    @Test fun panelLogReadSearchAndExpand() {
        fixture("state", vm.state.copy(running=true, panelReady=true)); vm.openPanel("logs"); show { HetuRoot(vm) }
        expectEventually("INFO"); capture("02-009-panel-logs")
        click("搜索"); replace(0,"example.com")
        click("[TCP] 192.168.1.12:52000 --> example.com:443 match MATCH using 节点选择[香港 01]")
        capture("02-026-panel-log-search-expanded")
    }
    @Test fun adblockRulesHelpAndDomainDrafts() {
        vm.prefs.edit().putStringSet("user_allow", setOf("example.com", "example.org")).putStringSet("user_block", setOf("ads.example.net")).commit()
        show { AdblockScreen(vm) }; expectEventually("AdGuard DNS Filter")
        click("说明"); capture("03A-046-adblock-help"); dismissSheet()
        scroll(680f); capture("03A-044-adblock-rules")
        repeat(4) { if (!exists("白名单（永不拦截）")) scroll(350f) }
        click("添加", 0); replace(0,"example.com"); capture("03A-047-adblock-add-allow"); click("取消")
        click("添加", 1); replace(0,"ads.example.net"); capture("03A-048-adblock-add-block"); click("取消")
        assertEquals(setOf("example.com", "example.org"), vm.prefs.getStringSet("user_allow", emptySet()))
    }
    @Test fun adblockRuntimeSnapshotWhitelistCancelAndGlobalWarning() {
        fixture("state", vm.state.copy(running=true, trafficMode="rule"))
        vm.prefs.edit().putBoolean("proxyAdblockLastEffective", true).putLong("proxyAdblockSessionHits", 124L).commit()
        show { AdblockScreen(vm) }; expectEventually("保护中"); expectEventually("ads.example.net"); capture("03A-045-adblock-status")
        click("ads.example.net"); capture("03A-049-adblock-whitelist-confirm"); click("取消")
        rule.runOnUiThread { fixture("state", vm.state.copy(trafficMode="global")) }; frame()
        expect("当前为全局模式"); capture("03B-001-adblock-global-warning")
    }
    @Test fun subStoreControlledProbeStates() {
        val release = java.util.concurrent.CountDownLatch(1)
        var success = true
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                require(request.method == "GET" && request.requestUrl?.encodedPath == "/api/utils/env")
                release.await(5, java.util.concurrent.TimeUnit.SECONDS)
                return if (success) MockResponse().setBody("""{"data":{"backend":"Sub-Store","version":"2.20.0-fixture"}}""") else MockResponse().setResponseCode(503)
            }
        }
        vm.prefs.edit().putString("subStoreBackendUrl", server.url("/").toString()).commit()
        show { ProxySubStoreScreen {} }; click("检测"); expect("检测中"); capture("03B-046-substore-checking")
        release.countDown(); expectEventually("Sub-Store 已连接 · Sub-Store · 2.20.0-fixture"); capture("03B-047-substore-connected")
        success = false; click("检测"); expectEventually("后端不可用（HTTP 503）"); capture("03B-048-substore-unavailable")
    }
    @Test fun realWebViewNativeContainerBoundaries() {
        show {}
        val intent = android.content.Intent(ApplicationProvider.getApplicationContext<Application>(), ProxyWebPanelViewerActivity::class.java)
            .putExtra("panel_name", "示例面板").putExtra("panel_url", "https://example.com")
        val custom = Robolectric.buildActivity(ProxyWebPanelViewerActivity::class.java, intent).setup()
        try { frame(); expect("正在加载外部面板…"); capture("03B-038-custom-web-container") }
        finally { custom.pause().stop().destroy(); frame() }
        val subStore = Robolectric.buildActivity(ProxySubStoreWebActivity::class.java).setup()
        try { frame(); expect("正在加载 Sub-Store…"); capture("03B-039-substore-web-container") }
        finally { subStore.pause().stop().destroy(); frame() }
    }
    @Config(sdk = [28])
    @Test fun failedBootSetupUpdatesBannerWithoutLeavingSettingsPage() {
        show { SettingsScreen(vm, androidx.compose.ui.unit.Dp(0f), "startupDownload") {} }
        expect("开机自启")
        val error = "无法取得 Root 权限，请在 Root 管理器中授权河图"
        rule.runOnUiThread {
            assertFalse(exists(error))
            vm.prefs.edit().putString("proxyRootAutoStartError", error).commit()
            vm.bumpSettings()
        }
        frame()
        expect(error)
        assertFalse(vm.prefs.getBoolean("proxyRootAutoStart", false))
        rule.runOnUiThread {
            vm.prefs.edit().remove("proxyRootAutoStartError").commit()
            vm.bumpSettings()
        }
        frame()
        rule.runOnUiThread { assertFalse("Cleared error remained in the list", exists(error)) }
    }
    @Test fun restoreFileResultRequiresConfirmationAndCancelPreservesSettings() {
        show { SettingsScreen(vm, androidx.compose.ui.unit.Dp(0f), "backup") {} }
        click("选择文件")
        rule.runOnUiThread {
            val request = Shadows.shadowOf(rule.activity).nextStartedActivityForResult
            assertNotNull(request)
            rule.activity.activityResultRegistry.dispatchResult(request.requestCode, android.app.Activity.RESULT_OK,
                android.content.Intent().setData(android.net.Uri.parse("content://qa.fixture/backup.json")))
        }; frame()
        expect("恢复备份？"); capture("04-028-restore-confirm"); click("取消")
        assertEquals("light", vm.prefs.getString("appearance", ""))
    }
    @Config(shadows = [RemainingCoreDownloadShadow::class])
    @Test fun coreDownloadProgressIsOnlyAnInMemoryCallbackFixture() {
        assertEquals("正在下载 · 6.4 MB / 12.8 MB", coreDownloadProgressText(6_400_000L, 12_800_000L))
        assertEquals("正在下载 · 1.5 KB", coreDownloadProgressText(1_500L, -1L))
        assertEquals("正在下载 · 0 B", coreDownloadProgressText(0L, 0L))
        // A cached version is not evidence that the proxy is currently running.
        rule.runOnUiThread { fixture("coreVersion", "Mihomo stale-fixture") }
        show { CoresScreen(vm) {} }; expectEventually("Xray")
        expect("代理未运行")
        rule.runOnUiThread { assertFalse(exists("运行中：Mihomo stale-fixture")) }
        click("更新"); expectEventually("正在下载 · 6.4 MB / 12.8 MB"); expect("处理中")
        fun actionNodes(label: String): List<SemanticsNode> = nativeNodes().filter { label in labels(it) }.mapNotNull { start ->
            generateSequence(start) { it.parent }.firstOrNull { it.config.getOrNull(SemanticsActions.OnClick)?.action != null }
        }.distinctBy { it.id }
        rule.runOnUiThread {
            listOf("处理中" to 1, "下载" to 2, "导入" to 3, "Mihomo" to 1, "Xray" to 1, "sing-box" to 1).forEach { (label, count) ->
                val nodes = actionNodes(label)
                assertEquals("Action count for $label", count, nodes.size)
                nodes.forEach { assertTrue("$label must be disabled during download", it.config.contains(SemanticsProperties.Disabled)) }
            }
            assertFalse("Byte progress must not also render a percentage bar", nativeNodes().any {
                it.config.getOrNull(SemanticsProperties.ProgressBarRangeInfo)?.let { range -> range != ProgressBarRangeInfo.Indeterminate } == true
            })
        }
        capture("03A-035-core-download-progress")
        // Even a direct accessibility invocation cannot start a duplicate transfer or import.
        click("处理中"); click("下载", 0); click("导入", 0)
        assertEquals(1, RemainingCoreDownloadShadow.calls)
        rule.runOnUiThread { assertNull(Shadows.shadowOf(rule.activity).nextStartedActivityForResult) }
        rule.runOnUiThread { RemainingCoreDownloadShadow.emitProgress(9_600_000L, 12_800_000L) }; frame()
        expectEventually("正在下载 · 9.6 MB / 12.8 MB")
        rule.runOnUiThread { RemainingCoreDownloadShadow.emitProgress(9_600_000L, -1L) }; frame()
        expectEventually("正在下载 · 9.6 MB")
        rule.runOnUiThread { RemainingCoreDownloadShadow.cancelFixture() }; frame()
        expect("更新")
        rule.runOnUiThread {
            assertFalse(exists("处理中"))
            assertFalse(exists("正在下载 · 9.6 MB"))
            assertFalse(actionNodes("更新").single().config.contains(SemanticsProperties.Disabled))
        }
        click("更新"); expectEventually("处理中")
        assertEquals(2, RemainingCoreDownloadShadow.calls)
        rule.runOnUiThread { RemainingCoreDownloadShadow.cancelFixture() }; frame()
        rule.runOnUiThread { fixture("state", vm.state.copy(running = true)); fixture("coreVersion", "Mihomo test-fixture") }; frame()
        expect("运行中：Mihomo test-fixture")
        assertTrue(RemainingRootBridgeShadow.commands.isEmpty())
    }
}

/** Test-only interception. No command here reaches a process, socket, device, or Root. */
@Implements(value = RootBridge::class, isInAndroidSdk = false)
class RemainingRootBridgeShadow {
    companion object {
        val unexpected = java.util.Collections.synchronizedList(mutableListOf<String>())
        val commands = java.util.Collections.synchronizedList(mutableListOf<String>())
        var managedFailure = false
        var managedListing = "network-check.sh\t1480\n"
        @JvmStatic @Resetter fun reset() { unexpected.clear(); commands.clear(); managedFailure = false; managedListing = "network-check.sh\t1480\n" }
        @JvmStatic @Implementation fun rootShell(context: Context, command: String, timeoutMs: Long): RootBridge.Result {
            commands += command
            if(managedFailure && command.startsWith("for f in '/data/adb/hetu/scripts'")) return RootBridge.Result(126, "无法读取 Root 脚本列表")
            val response = when {
                command.startsWith("set -e; p=\$(readlink -f '") && command.contains("; for f in") && command.contains("stat -c %Y") ->
                    if (command.contains("readlink -f '/data/adb/hetu/backup'")) "F\tbackup.yaml\t4200\t1790899200\nF\told.yaml\t2800\t1790899200\n"
                    else "D\tbackup\t4096\t1790899200\nD\tbin\t4096\t1790899200\nD\trun\t4096\t1790899200\nD\tscripts\t4096\t1790899200\nF\tconfig.yaml\t12183\t1790899200\nF\trules.txt\t4088\t1790899200\n"
                command == "mkdir -p '/data/adb/hetu/scripts' /data/adb/hetu/run && chmod 700 '/data/adb/hetu/scripts'" -> ""
                command == "if [ -f '/data/adb/hetu/scripts/pre-start.sh' ]; then head -c 131072 '/data/adb/hetu/scripts/pre-start.sh'; fi" -> "#!/system/bin/sh\n# 测试夹具，未执行\necho ready\n"
                command == "if [ -f '/data/adb/hetu/scripts/post-stop.sh' ]; then head -c 131072 '/data/adb/hetu/scripts/post-stop.sh'; fi" -> "#!/system/bin/sh\n# 测试夹具，未执行\necho stopped\n"
                command.startsWith("for f in '/data/adb/hetu/scripts'/*.sh;") && command.endsWith("done") -> managedListing
                command == "find '/data/adb/hetu' -maxdepth 2 -type f -name '*.log' -print 2>/dev/null | head -n 200" -> "/data/adb/hetu/run/core.log\n/data/adb/hetu/run/scripts.log\n"
                command == "tail -n 1600 '/data/adb/hetu/run/core.log' 2>/dev/null" -> "time=\"2026-10-02T09:00:01Z\" level=info msg=\"[TCP] 192.168.1.12:52000 --> example.com:443 match MATCH using 节点选择[香港 01]\"\ntime=\"2026-10-02T09:00:02Z\" level=warning msg=\"DNS timeout\"\n"
                command == "tail -n 1600 '/data/adb/hetu/run/scripts.log' 2>/dev/null" -> "time=\"2026-10-02T09:00:03Z\" level=info msg=\"Hook fixture read only\"\n"
                command.startsWith("echo '--- controller-port ---'; echo ") && command.endsWith("cat /data/adb/hetu/run/last-crash 2>/dev/null || true") -> "time=\"2026-10-02T09:00:01Z\" level=info msg=\"[TCP] 192.168.1.12:52000 --> example.com:443 match MATCH using 节点选择[香港 01]\"\ntime=\"2026-10-02T09:00:02Z\" level=warning msg=\"DNS timeout\"\n"
                command == "if [ -r /data/adb/hetu/run/core.log ]; then tail -c 1048576 /data/adb/hetu/run/core.log 2>/dev/null | grep -Ei 'hetu-adblock|RuleSet/hetu-adblock' | tail -n 4000; else exit 2; fi" -> "[TCP] 127.0.0.1:4321 --> ads.example.net:443 match RuleSet/hetu-adblock using REJECT\n"
                command == "pm list users" -> "Users:\n UserInfo{0:Owner:13} running\n"
                else -> null
            }
            if(response == null) { unexpected += command; throw SecurityException("Unexpected Root command blocked by QA fixture: $command") }
            return RootBridge.Result(0, response)
        }
    }
}
