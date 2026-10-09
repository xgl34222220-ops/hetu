package io.github.xgl34222220.hetu

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsOwner
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
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
import java.io.File
import java.time.Duration
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Real Activity and production IO/error handling; the shadow never executes Root or a process. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "zh-rCN-w393dp-h852dp-mdpi", application = Application::class,
    shadows = [ScriptFeedbackPlacementRootShadow::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScriptFeedbackPlacement94Test {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private var controller: ActivityController<ProxyScriptsActivity>? = null
    private lateinit var read: ScriptFeedbackPlacementRootShadow.ManagedRead
    private val output = File("build/outputs/ui93")

    @Before fun prepare() {
        ScriptFeedbackPlacementRootShadow.reset()
        ApplicationProvider.getApplicationContext<Application>().getSharedPreferences("hetu", 0).edit().clear()
            .putBoolean("hetuLegacyAppDataMigrated", true)
            .putString("appearance", "light").putBoolean("hetuDynamicColor", false)
            .putBoolean("enableBlur", false).putBoolean("predictiveBackAnimation", false).commit()
        rule.mainClock.autoAdvance = false
        output.mkdirs()
    }

    @After fun finish() {
        // Release before disposing the composition, including when an assertion failed.
        if (::read.isInitialized) read.release.countDown()
        controller?.pause()?.stop()?.destroy()
        frame()
        assertEquals("Unexpected Root commands must remain blocked", emptyList<String>(),
            ScriptFeedbackPlacementRootShadow.unexpected.toList())
    }

    private fun frame() {
        repeat(4) {
            rule.mainClock.advanceTimeBy(200)
            rule.runOnUiThread { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100)) }
        }
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
        controller?.get()?.window?.decorView?.let(::visitView)
        return result
    }

    private fun labels(node: SemanticsNode) =
        node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } +
            node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
    private fun has(label: String) = nodes().any { label in labels(it) }

    private fun eventually(reason: String, condition: () -> Boolean) {
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < until) {
            var ready = false
            rule.runOnUiThread { ready = condition() }
            if (ready) return
            Thread.sleep(20)
            frame()
        }
        rule.runOnUiThread {
            assertTrue("$reason; present: ${nodes().flatMap(::labels)}", condition())
        }
    }

    private fun assertVisible(label: String) {
        val decor = controller!!.get().window.decorView
        val match = nodes().firstOrNull { label in labels(it) }
        assertNotNull("$label must be composed without scrolling", match)
        val bounds = match!!.boundsInWindow
        assertTrue("$label must occupy visible screen space: $bounds", bounds.right - bounds.left > 0f && bounds.bottom - bounds.top > 0f &&
            bounds.left >= 0f && bounds.right <= decor.width.toFloat() &&
            bounds.top >= 0f && bounds.bottom <= decor.height.toFloat())
        val title = nodes().first { "脚本" in labels(it) }
        assertTrue("$label must be below, not behind, the title bar", bounds.top >= title.boundsInWindow.bottom)
    }

    private fun openWithBlockedRead(result: RootBridge.Result) {
        read = ScriptFeedbackPlacementRootShadow.ManagedRead(result)
        ScriptFeedbackPlacementRootShadow.managedRead = read
        controller = Robolectric.buildActivity(ProxyScriptsActivity::class.java).setup()
        eventually("Managed-list IO must enter its controlled gate") { read.entered.count == 0L }
        eventually("Hooks must be laid out before the IO response") {
            nodes().any { "服务启动前" in labels(it) && it.boundsInWindow.bottom - it.boundsInWindow.top > 0f } && has("暂无脚本")
        }
        frame()
        rule.runOnUiThread {
            assertVisible("服务启动前")
            assertFalse("A blocked response cannot report failure", has("详情"))
            assertEquals("IO must still be held after initial layout", 1L, read.returned.count)
        }
    }

    private fun capture(name: String) {
        rule.runOnUiThread {
            val decor = controller!!.get().window.decorView
            assertTrue(decor.width > 0 && decor.height > 0)
            val bitmap = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
            try {
                decor.draw(Canvas(bitmap))
                File(output, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            } finally { bitmap.recycle() }
        }
    }

    @Test fun delayedReadFailureRemainsVisibleAboveHooksAndOpensRealDiagnostics() {
        openWithBlockedRead(RootBridge.Result(126, "无法读取 Root 脚本列表"))
        capture("script-feedback-2230-before-read")
        read.release.countDown()
        eventually("The error response must return") { read.returned.count == 0L }
        eventually("Failure must expose details without scrolling") { has("详情") }
        rule.runOnUiThread {
            assertVisible("详情")
            assertTrue(has("无法读取 Root 脚本列表"))
            assertFalse(has("network-check.sh"))
            val detail = nodes().first { "详情" in labels(it) }
            val button = generateSequence(detail) { it.parent }
                .first { it.config.getOrNull(SemanticsActions.OnClick)?.action != null }
            assertTrue(button.config[SemanticsActions.OnClick].action!!.invoke())
        }
        frame()
        eventually("Actual diagnostic panel must open") { has("复制诊断") }
        rule.runOnUiThread {
            assertVisible("复制诊断")
            val diagnostic = nodes().firstOrNull {
                it.config.getOrNull(SemanticsProperties.TestTag) == "task-inline-details"
            }
            assertNotNull("Must use the production inline diagnostic", diagnostic)
            assertTrue("Root failure must not be replaced by a generic success", "无法读取 Root 脚本列表" in labels(diagnostic!!))
        }
        capture("script-feedback-2230-error-details")
    }

    @Test fun delayedSuccessfulReadShowsActualScriptWithoutErrorFeedback() {
        openWithBlockedRead(RootBridge.Result(0, "network-check.sh\t1480\n"))
        read.release.countDown()
        eventually("Success response must return") { read.returned.count == 0L }
        eventually("Actual parsed managed script must appear") { has("network-check.sh") }
        rule.runOnUiThread {
            assertVisible("network-check.sh")
            assertFalse(has("暂无脚本"))
            assertFalse(has("详情"))
            assertFalse(has("无法读取 Root 脚本列表"))
            assertFalse(nodes().any { it.config.getOrNull(SemanticsProperties.TestTag) == "task-feedback" })
        }
        capture("script-feedback-2230-success")
    }
}

@Implements(value = RootBridge::class, isInAndroidSdk = false)
class ScriptFeedbackPlacementRootShadow {
    class ManagedRead(val result: RootBridge.Result) {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val returned = CountDownLatch(1)
    }
    companion object {
        @Volatile var managedRead: ManagedRead? = null
        val unexpected = Collections.synchronizedList(mutableListOf<String>())
        @JvmStatic @Resetter fun reset() {
            managedRead?.release?.countDown()
            managedRead = null
            unexpected.clear()
        }
        @JvmStatic @Implementation fun rootShell(context: Context, command: String, timeoutMs: Long): RootBridge.Result {
            if (command.startsWith("for f in '/data/adb/hetu/scripts'/*.sh;") && command.endsWith("done")) {
                val read = checkNotNull(managedRead) { "Managed read must be configured before Activity creation" }
                read.entered.countDown()
                // Watchdog only: it must fail closed, never auto-release a successful response.
                check(read.release.await(15, TimeUnit.SECONDS)) { "Managed-list test gate was not released" }
                read.returned.countDown()
                return read.result
            }
            val output = when (command) {
                "mkdir -p '/data/adb/hetu/scripts' /data/adb/hetu/run && chmod 700 '/data/adb/hetu/scripts'" -> ""
                "if [ -f '/data/adb/hetu/scripts/pre-start.sh' ]; then head -c 131072 '/data/adb/hetu/scripts/pre-start.sh'; fi",
                "if [ -f '/data/adb/hetu/scripts/post-stop.sh' ]; then head -c 131072 '/data/adb/hetu/scripts/post-stop.sh'; fi" -> "#!/system/bin/sh\n# fixture only\n"
                else -> {
                    unexpected += command
                    throw SecurityException("Unexpected Root command blocked: $command")
                }
            }
            return RootBridge.Result(0, output)
        }
    }
}
