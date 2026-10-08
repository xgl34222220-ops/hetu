package io.github.xgl34222220.hetu

import android.app.Activity
import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Looper
import android.os.TransactionTooLargeException
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsOwner
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import io.github.xgl34222220.hetu.tools.ToolsDestination
import io.github.xgl34222220.hetu.tools.ToolsFeatureActions
import io.github.xgl34222220.hetu.tools.ToolsFeaturePage
import io.github.xgl34222220.hetu.tools.ToolsPreflight
import io.github.xgl34222220.hetu.tools.rememberToolsFeatureHost
import kotlinx.coroutines.cancel
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.Resetter
import org.robolectric.shadows.ShadowClipboardManager
import org.robolectric.shadows.ShadowToast
import org.yaml.snakeyaml.Yaml
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

/** Actual settings click, Activity.onCreate/hxHost and DiagHost; no regenerate/Root/network. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "zh-rCN-w393dp-h852dp-mdpi", application = Application::class,
    shadows = [StartupFeedbackNoRootShadow::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StartupConfigViewerFeedbackTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val startup get() = RootProxyManager(app).startupFile()
    private val output = File("build/outputs/startup-config-feedback")
    private lateinit var vm: HetuViewModel
    private var activity: ActivityController<ProxyStartupConfigActivity>? = null
    private var original: ByteArray? = null
    private val eof = "feedback-synthetic-EOF-20261008"

    @Before fun prepare() {
        StartupFeedbackNoRootShadow.reset()
        original = if (startup.isFile) startup.readBytes() else null
        app.getSharedPreferences("hetu", 0).edit().clear()
            .putBoolean("hetuLegacyAppDataMigrated", true)
            .putString("appearance", "light").putBoolean("hetuDynamicColor", false)
            .putBoolean("enableBlur", false).putBoolean("predictiveBackAnimation", false)
            .putString("proxyBaseCore", "mihomo").commit()
        vm = HetuViewModel(app)
        rule.mainClock.autoAdvance = false
        output.mkdirs()
    }

    @After fun finish() {
        closeActivity()
        rule.runOnUiThread { rule.activity.setContent {} }
        vm.onBackground(); vm.viewModelScope.cancel()
        original?.let { startup.parentFile!!.mkdirs(); startup.writeBytes(it) } ?: startup.delete()
        // Root is asserted inside each method so cleanup cannot mask an old-source failure.
    }

    private fun frame() {
        repeat(4) {
            rule.mainClock.advanceTimeBy(200)
            rule.runOnUiThread { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100)) }
        }
    }

    private fun show(content: @Composable () -> Unit) {
        rule.runOnUiThread { rule.activity.setContent {
            HetuAppTheme("light", false) {
                CompositionLocalProvider(LocalNav provides HxNav(), LocalHxBlur provides false) {
                    Box(Modifier.fillMaxSize()) { content() }
                }
            }
        } }
        frame()
    }

    private fun nodes(): List<SemanticsNode> {
        val result = mutableListOf<SemanticsNode>()
        fun visitNode(node: SemanticsNode) { result += node; node.children.forEach(::visitNode) }
        fun visitView(view: View) {
            view.javaClass.methods.firstOrNull { it.name == "getSemanticsOwner" }?.let {
                visitNode((it.invoke(view) as SemanticsOwner).rootSemanticsNode)
            }
            if (view is ViewGroup) (0 until view.childCount).forEach { visitView(view.getChildAt(it)) }
        }
        WindowInspector.getGlobalWindowViews().asReversed().forEach(::visitView)
        return result
    }

    private fun labels(node: SemanticsNode) =
        node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } +
            node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()

    private fun has(label: String) = nodes().any { label in labels(it) }
    private fun contains(text: String) = nodes().any { labels(it).any { label -> text in label } }

    private fun eventually(message: String, condition: () -> Boolean) {
        val end = System.nanoTime() + 8_000_000_000L
        while (System.nanoTime() < end) {
            var ready = false
            rule.runOnUiThread { ready = condition() }
            if (ready) return
            Thread.sleep(15); frame()
        }
        rule.runOnUiThread { assertTrue(message, condition()) }
    }

    private fun reveal(label: String) {
        repeat(20) {
            var visible = false
            rule.runOnUiThread {
                visible = has(label)
                if (!visible) nodes().firstOrNull {
                    it.config.getOrNull(SemanticsActions.ScrollBy)?.action != null && it.boundsInWindow.height > 0f
                }?.config?.getOrNull(SemanticsActions.ScrollBy)?.action?.invoke(0f, 360f)
            }
            if (visible) return
            frame()
        }
        rule.runOnUiThread { assertTrue("Actual route must expose $label", has(label)) }
    }

    private fun click(label: String) {
        reveal(label)
        rule.runOnUiThread {
            val target = nodes().filter { label in labels(it) }.asSequence()
                .flatMap { generateSequence(it) { parent -> parent.parent } }
                .first { it.config.getOrNull(SemanticsActions.OnClick)?.action != null }
            assertTrue("Actual $label action must accept the click", target.config[SemanticsActions.OnClick].action!!.invoke())
        }
        frame()
    }

    private fun fixture(text: String): ByteArray {
        val bytes = text.toByteArray(Charsets.UTF_8)
        assertTrue("Synthetic YAML stays below the supported 4 MiB import size", bytes.size < 4 * 1024 * 1024)
        assertNotNull("Fixture is actual valid YAML", Yaml().load<Any>(text))
        startup.parentFile!!.mkdirs(); startup.writeBytes(bytes)
        return bytes
    }

    /** Click the actual SettingsScreen row, actual NetworkSettingsScreen row, then its Intent. */
    private fun openFromSettings() {
        vm.tab = HxTab.Settings
        show { HetuRoot(vm) }
        click("基础代理配置")
        click("查看启动配置")
        rule.runOnUiThread {
            val intent = shadowOf(rule.activity).nextStartedActivity
            assertNotNull("Actual settings navigation must launch an Activity", intent)
            assertEquals(ProxyStartupConfigActivity::class.java.name, intent.component!!.className)
            rule.activity.setContent {}
            activity = Robolectric.buildActivity(ProxyStartupConfigActivity::class.java, intent).setup()
        }
        frame()
    }

    private fun closeActivity() {
        activity?.let { controller -> rule.runOnUiThread { controller.pause().stop().destroy() } }
        activity = null
        frame()
    }

    private fun copyAndVerify(text: String, bytes: ByteArray) {
        click("复制")
        rule.runOnUiThread {
            val clipboard = app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val copied = clipboard.primaryClip!!.getItemAt(0).text.toString()
            assertEquals("Copy uses the original full document, including EOF and Unicode", text, copied)
            assertArrayEquals(bytes, copied.toByteArray(Charsets.UTF_8))
        }
        assertArrayEquals("Viewing and copying must not modify startup bytes", bytes, startup.readBytes())
        assertEquals("Startup viewer may not call Root", 0, StartupFeedbackNoRootShadow.calls.get())
    }

    private fun exportAndCancel(text: String, bytes: ByteArray) {
        val stream = ByteArrayOutputStream()
        val uri = Uri.parse("content://feedback.fixture/startup.yaml")
        shadowOf(app.contentResolver).registerOutputStream(uri, stream)
        click("导出")
        rule.runOnUiThread {
            val actual = activity!!.get()
            val request = shadowOf(actual).nextStartedActivityForResult
            assertEquals(Intent.ACTION_CREATE_DOCUMENT, request.intent.action)
            assertEquals("text/yaml", request.intent.type)
            actual.activityResultRegistry.dispatchResult(request.requestCode, Activity.RESULT_CANCELED, null)
        }
        frame()
        assertEquals("Cancelled picker writes no content", 0, stream.size())
        click("导出")
        rule.runOnUiThread {
            val actual = activity!!.get()
            val request = shadowOf(actual).nextStartedActivityForResult
            actual.activityResultRegistry.dispatchResult(request.requestCode, Activity.RESULT_OK, Intent().setData(uri))
        }
        eventually("Full UTF-8 export must complete") { stream.size() == bytes.size && has("启动配置已导出") }
        assertArrayEquals("Export uses the full original, rather than visible chunks", bytes, stream.toByteArray())
        assertEquals(text, stream.toString(Charsets.UTF_8.name()))
        assertArrayEquals(bytes, startup.readBytes())
        assertEquals("Export may not call Root", 0, StartupFeedbackNoRootShadow.calls.get())
    }

    private fun largeYaml(): String = buildString {
        append("mixed-port: 7890\nmode: rule\n")
        repeat(16_384) { append("# synthetic line ").append(it).append(" — 仅本地夹具 abcdefghijklmnopqrstuvwxyz0123456789\n") }
        append("rules:\n  - MATCH,DIRECT\n# ").append(eof).append('\n')
    }

    private fun singleLineYaml(): String = "mode: rule\r\nprofile-name: '" + "河图😀".repeat(20_000) +
        "-$eof'\r\nrules: [MATCH,DIRECT]\r\n"

    private fun capture(name: String) {
        frame()
        rule.runOnUiThread {
            val base = activity?.get()?.window?.decorView ?: rule.activity.window.decorView
            assertTrue("Actual native window has a layout", base.width > 0 && base.height > 0)
            val bitmap = Bitmap.createBitmap(base.width, base.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            WindowInspector.getGlobalWindowViews().filter { it.visibility == View.VISIBLE && it.width > 0 && it.height > 0 }
                .forEach { view -> val xy = IntArray(2); view.getLocationOnScreen(xy)
                    canvas.save(); canvas.translate(xy[0].toFloat(), xy[1].toFloat()); view.draw(canvas); canvas.restore() }
            File(output, "$name.png").outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            bitmap.recycle()
            File(output, "$name.json").writeText(JSONObject()
                .put("api", 35).put("testOnlySynthetic", true).put("actualStartupActivity", activity != null)
                .put("rootCalls", StartupFeedbackNoRootShadow.calls.get())
                .put("visibleTextLengths", JSONArray(nodes().flatMap(::labels).map { it.length }))
                .put("hasEndNavigation", has("查看末尾")).toString(2))
        }
    }

    // These six old-compatible entrance cases run unchanged against original production.
    // A successful before result documents no reproduction; it is not a phone-crash claim.
    @Test fun realSettingsEntryCreatesThemedActivityAndPreservesShortCopyExport() {
        val text = "mode: rule\r\nprofile-name: '河图😀'\r\nrules: [MATCH,DIRECT]\r\n# $eof\r\n"
        val bytes = fixture(text)
        openFromSettings()
        eventually("Actual Activity reads the complete short fixture") { contains(text) }
        capture("settings-actual-short")
        copyAndVerify(text, bytes)
        exportAndCancel(text, bytes)
        assertEquals(0, StartupFeedbackNoRootShadow.calls.get())
    }

    @Test fun realSettingsEntryReadsLargeValidYamlAndPreservesAllBytes() {
        val text = largeYaml(); val bytes = fixture(text)
        openFromSettings()
        eventually("Actual Activity must complete the large read") { has("复制") && contains("mixed-port: 7890") }
        capture("settings-actual-large-entry")
        copyAndVerify(text, bytes)
        exportAndCancel(text, bytes)
    }

    @Test fun realSettingsEntryReadsLongUnicodeLineAndPreservesEof() {
        val text = singleLineYaml(); val bytes = fixture(text)
        openFromSettings()
        eventually("Actual Activity must complete the long-line read") { contains("mode: rule") && has("复制") }
        capture("settings-actual-long-line-entry")
        copyAndVerify(text, bytes)
    }

    @Test fun realSettingsEntryShowsMissingStartupWithoutRegeneration() {
        startup.delete()
        openFromSettings()
        eventually("Actual missing-file IOException is visible") { has("尚未生成启动配置") && has("暂无启动配置") }
        capture("settings-actual-missing")
        assertFalse("A read-only open must not generate startup config", startup.exists())
        assertEquals(0, StartupFeedbackNoRootShadow.calls.get())
    }

    @Config(shadows = [StartupFeedbackNoRootShadow::class, StartupFeedbackReadErrorShadow::class])
    @Test fun realSettingsEntryShowsReadIOExceptionWithoutFalseSuccess() {
        val bytes = fixture("mode: rule\nrules: [MATCH,DIRECT]\n")
        openFromSettings()
        eventually("Read error from the production I/O boundary stays visible") { has("反馈夹具：启动配置读取失败") && has("暂无启动配置") }
        capture("settings-actual-read-error")
        assertArrayEquals(bytes, startup.readBytes())
        assertEquals(0, StartupFeedbackNoRootShadow.calls.get())
    }

    @Test fun actualToolsDiagClickReadsLargeStartupAndCopiesOriginal() {
        val text = largeYaml(); val bytes = fixture(text)
        val copies = mutableListOf<Pair<String, String>>()
        val actions = ToolsFeatureActions(runPreflight = { ToolsPreflight.Passed() },
            startupConfig = { RootProxyManager(app).startupConfig() }, onCopy = { label, value -> copies += label to value })
        show { ToolsFeaturePage(ToolsDestination.Diag, actions, {}) }
        click("启动配置")
        eventually("Actual DiagHost completes its startup sheet") { has("复制") && contains("mixed-port: 7890") }
        capture("tools-actual-large-entry")
        click("复制")
        assertEquals(listOf("启动配置" to text), copies)
        assertArrayEquals(bytes, copies.single().second.toByteArray(Charsets.UTF_8))
        assertArrayEquals(bytes, startup.readBytes())
        assertEquals(0, StartupFeedbackNoRootShadow.calls.get())
    }

    /** Verify glyph coordinates, rather than accepting any intersection of a tall Text. */
    private fun expectGlyphVisible(marker: String) = rule.runOnUiThread {
        val node = nodes().first { it.config.getOrNull(SemanticsProperties.Text).orEmpty().any { label -> marker in label.text } }
        val layouts = mutableListOf<TextLayoutResult>()
        assertTrue(node.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts))
        val layout = layouts.single()
        val index = layout.layoutInput.text.text.indexOf(marker)
        assertTrue(index >= 0)
        val parents = generateSequence(node) { it.parent }.toList()
        val vertical = parents.first { it.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange) != null }.boundsInWindow
        val horizontal = parents.first { it.config.getOrNull(SemanticsProperties.HorizontalScrollAxisRange) != null }.boundsInWindow
        val viewport = Rect(maxOf(vertical.left, horizontal.left), maxOf(vertical.top, horizontal.top),
            minOf(vertical.right, horizontal.right), minOf(vertical.bottom, horizontal.bottom))
        for (at in index until index + marker.length) {
            val glyph = layout.getBoundingBox(at).translate(node.positionInWindow)
            assertTrue("EOF glyph must be visible vertically: $glyph within $viewport", glyph.top >= viewport.top - 1f && glyph.bottom <= viewport.bottom + 1f)
            assertTrue("EOF glyph must be visible horizontally: $glyph within $viewport", glyph.left >= viewport.left - 1f && glyph.right <= viewport.right + 1f)
        }
    }

    @Test fun boundedViewerCanShowRealEofAndReturnToStartAcrossSettingsAndTools() {
        // Last paragraph is a full block taller than 420dp; EOF must still be visible.
        val text = buildString { append("mode: rule\n"); repeat(46) { append("# bounded line ").append(it).append('\n') }; append("# ").append(eof) }
        val bytes = fixture(text)
        openFromSettings()
        eventually("Large viewer navigation appears") { has("查看末尾") }
        click("查看末尾"); expectGlyphVisible(eof); capture("settings-visible-eof")
        click("回到开头"); expectGlyphVisible("mode: rule")
        closeActivity()
        val actions = ToolsFeatureActions(runPreflight = { ToolsPreflight.Passed() }, startupConfig = { text })
        show { ToolsFeaturePage(ToolsDestination.Diag, actions, {}) }; click("启动配置")
        eventually("Tools startup shares end navigation") { has("查看末尾") }
        click("查看末尾"); expectGlyphVisible(eof); capture("tools-visible-eof")
        click("回到开头"); expectGlyphVisible("mode: rule")
        assertArrayEquals(bytes, startup.readBytes())
        assertEquals(0, StartupFeedbackNoRootShadow.calls.get())
    }

    @Test fun boundedViewerMeasuresOnlySmallUnicodeParagraphsAndShowsLongLineEnd() {
        // A CRLF exactly touching the char-bound split must not become an extra blank item.
        val text = "mode: rule\nprofile-name: '" + "a".repeat(1023 - "profile-name: '".length) +
            "\r\n" + "河图😀".repeat(20_000) + "-$eof'"
        val bytes = fixture(text)
        openFromSettings()
        eventually("Bounded long-line navigation appears") { has("查看末尾") }
        rule.runOnUiThread {
            val paragraphs = nodes().filter { it.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action != null }
                .flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty().map { label -> label.text } }
            assertTrue("The original large String must not be measured as one Text", paragraphs.all { it.length <= 1024 })
            assertTrue("A CRLF delimiter at a chunk boundary must not add a blank paragraph", paragraphs.none { it == " " })
            assertTrue("A split must not begin with an orphan low surrogate", paragraphs.none { it.firstOrNull()?.isLowSurrogate() == true })
            assertTrue("A split must not end with an orphan high surrogate", paragraphs.none { it.lastOrNull()?.isHighSurrogate() == true })
        }
        click("查看末尾"); expectGlyphVisible(eof); capture("settings-long-line-end")
        copyAndVerify(text, bytes)
    }

    @Config(shadows = [StartupFeedbackNoRootShadow::class, StartupFeedbackClipboardShadow::class])
    @Test fun actualSettingsClipboardRejectionAndMissingServiceStayVisibleAndKeepAllBytes() {
        // Keep this fault targeted at ClipboardManager even against the original unbounded viewer.
        // The unchanged large-document tests separately assert complete copy/export bytes.
        val text = "mode: rule\r\nprofile-name: '河图😀 clipboard'\r\nrules: [MATCH,DIRECT]\r\n# $eof\r\n"
        val bytes = fixture(text)
        openFromSettings()
        eventually("Actual Activity reads the copy fixture") { has("复制") && contains(text) }
        val clipboard = app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("existing", "existing clipboard"))
        StartupFeedbackClipboardShadow.resetFaults()
        StartupFeedbackClipboardShadow.reject = true
        ShadowToast.reset()
        try { click("复制") } catch (error: Exception) {
            throw AssertionError("Settings copy click must not throw when the clipboard rejects the request", error)
        }
        eventually("Settings clipboard rejection is actionable") { has("复制失败，剪贴板暂不可用；可改用导出") }
        assertEquals("The real clipboard boundary received the click", 1, StartupFeedbackClipboardShadow.attempts.get())
        assertEquals("Failed copy must leave earlier clipboard content alone", "existing clipboard", clipboard.primaryClip!!.getItemAt(0).text.toString())
        assertEquals("Failed copy must not claim success", 0, ShadowToast.shownToastCount())
        assertArrayEquals(bytes, startup.readBytes())
        capture("settings-clipboard-rejected")
        exportAndCancel(text, bytes)
        StartupFeedbackClipboardShadow.reject = false
        copyAndVerify(text, bytes)
        eventually("A later successful copy replaces the error") { has("启动配置已复制") && !has("复制失败，剪贴板暂不可用；可改用导出") }
        closeActivity()

        // Keep the real ActivityResultRegistryOwner discoverable by the production
        // document picker while this wrapper intercepts only the clipboard service.
        val missing = StartupFeedbackClipboardContext(rule.activity)
        show { CompositionLocalProvider(LocalContext provides missing) { SettingsStartupConfigScreen {} } }
        eventually("The actual settings screen reads without clipboard access") { has("复制") && contains(text) }
        val previousAttempts = StartupFeedbackClipboardShadow.attempts.get()
        ShadowToast.reset()
        click("复制")
        eventually("Missing settings clipboard stays visible") { has("复制失败，剪贴板暂不可用；可改用导出") }
        assertEquals("Missing service must not write to a hidden clipboard", previousAttempts, StartupFeedbackClipboardShadow.attempts.get())
        assertEquals("Missing service must not claim success", 0, ShadowToast.shownToastCount())
        capture("settings-clipboard-missing")
        missing.unavailable = false
        copyAndVerify(text, bytes)
        eventually("The settings button can recover after clipboard returns") { has("启动配置已复制") }
        assertEquals(0, StartupFeedbackNoRootShadow.calls.get())
    }

    @Config(shadows = [StartupFeedbackNoRootShadow::class, StartupFeedbackClipboardShadow::class])
    @Test fun actualToolsClipboardRejectionAndMissingServiceNeverClaimSuccess() {
        val text = "mode: rule\r\nprofile-name: '河图😀 clipboard'\r\nrules: [MATCH,DIRECT]\r\n# $eof\r\n"
        val bytes = fixture(text)
        val clipboard = app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("existing", "existing clipboard"))
        StartupFeedbackClipboardShadow.resetFaults()
        StartupFeedbackClipboardShadow.reject = true
        val context = StartupFeedbackClipboardContext(rule.activity).apply { unavailable = false }
        show {
            CompositionLocalProvider(LocalContext provides context) {
                val production = rememberToolsFeatureHost()
                // Only the read-only local fixture reader is substituted. The copy callback
                // stays the actual host's clipboard + success-toast implementation.
                val actions = ToolsFeatureActions(runPreflight = { ToolsPreflight.Passed() },
                    startupConfig = { RootProxyManager(app).startupConfig() }, onCopy = production.actions.onCopy)
                ToolsFeaturePage(ToolsDestination.Diag, actions, {})
            }
        }
        click("启动配置")
        eventually("Actual Tools sheet reads the complete fixture") { has("复制") && contains(text) }
        ShadowToast.reset()
        try { click("复制") } catch (error: Exception) {
            throw AssertionError("Tools copy click must not throw when the clipboard rejects the request", error)
        }
        eventually("Tools clipboard rejection is visible") { has("复制失败，剪贴板暂不可用") }
        assertEquals("The actual host called the clipboard service", 1, StartupFeedbackClipboardShadow.attempts.get())
        assertEquals("Rejected Tools copy keeps the old clipboard", "existing clipboard", clipboard.primaryClip!!.getItemAt(0).text.toString())
        assertEquals("Rejected Tools copy must not show success toast", 0, ShadowToast.shownToastCount())
        capture("tools-clipboard-rejected")
        assertArrayEquals(bytes, startup.readBytes())

        StartupFeedbackClipboardShadow.reject = false
        click("复制")
        eventually("The actual Tools button clears its rejection after a successful copy") { !has("复制失败，剪贴板暂不可用") }
        assertEquals("已复制启动配置", ShadowToast.getTextOfLatestToast())
        assertEquals(text, clipboard.primaryClip!!.getItemAt(0).text.toString())
        context.unavailable = true
        ShadowToast.reset()
        click("复制")
        eventually("Missing Tools clipboard stays visible") { has("复制失败，剪贴板暂不可用") }
        assertEquals("Missing clipboard cannot issue a write", 2, StartupFeedbackClipboardShadow.attempts.get())
        assertEquals("Missing Tools clipboard must never toast success", 0, ShadowToast.shownToastCount())
        capture("tools-clipboard-missing")
        context.unavailable = false
        click("复制")
        eventually("Successful actual Tools copy clears the old failure") { !has("复制失败，剪贴板暂不可用") }
        assertEquals("已复制启动配置", ShadowToast.getTextOfLatestToast())
        val copied = clipboard.primaryClip!!.getItemAt(0).text.toString()
        assertEquals("Actual host copies the complete original text", text, copied)
        assertArrayEquals(bytes, copied.toByteArray(Charsets.UTF_8))
        assertArrayEquals(bytes, startup.readBytes())
        assertEquals(0, StartupFeedbackNoRootShadow.calls.get())
    }
}

/** Any real Root access is blocked and counted, including accidental polling/regeneration. */
@Implements(value = RootBridge::class, isInAndroidSdk = false)
class StartupFeedbackNoRootShadow {
    companion object {
        val calls = AtomicInteger()
        @JvmStatic @Resetter fun reset() { calls.set(0) }
        @JvmStatic @Implementation fun hasRoot(context: Context): Boolean {
            calls.incrementAndGet(); throw SecurityException("Startup feedback fixture forbids Root")
        }
        @JvmStatic @Implementation fun rootShell(context: Context, command: String, timeoutMs: Long): RootBridge.Result {
            calls.incrementAndGet(); throw SecurityException("Startup feedback fixture forbids Root: $command")
        }
    }
}

/** Only this test overrides the I/O boundary; normal tests use the actual file reader. */
@Implements(value = RootProxyManager::class, isInAndroidSdk = false)
class StartupFeedbackReadErrorShadow {
    @Implementation fun startupConfig(): String = throw IOException("反馈夹具：启动配置读取失败")
}

/** A service absence at the actual Context boundary, without replacing an onCopy callback. */
private class StartupFeedbackClipboardContext(base: Context) : ContextWrapper(base) {
    var unavailable = true
    override fun getApplicationContext(): Context = this
    override fun getSystemService(name: String): Any? =
        if (name == Context.CLIPBOARD_SERVICE && unavailable) null else super.getSystemService(name)
}

/** The real setPrimaryClip call may be refused by Binder, while normal success stays native. */
@Implements(ClipboardManager::class)
class StartupFeedbackClipboardShadow : ShadowClipboardManager() {
    companion object {
        val attempts = AtomicInteger()
        var reject = false
        @JvmStatic @Resetter fun resetFaults() { attempts.set(0); reject = false }
    }
    @Implementation override fun setPrimaryClip(clip: ClipData?) {
        attempts.incrementAndGet()
        if (reject) throw TransactionTooLargeException("Synthetic clipboard Binder rejection")
        super.setPrimaryClip(clip)
    }
}
