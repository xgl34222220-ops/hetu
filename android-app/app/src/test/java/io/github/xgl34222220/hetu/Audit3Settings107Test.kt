package io.github.xgl34222220.hetu

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import io.github.xgl34222220.hetu.ui.translateHetuText
import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 2026-10-13 audit3 settings (G3/G4): the notification page asks before dropping edited text or
 * quick buttons and previews the rendered templates; 资源限制 inputs are checked by the same rules
 * hetu-root.sh applies at start and only a valid value is written.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "zh-rCN-w393dp-h852dp-mdpi", application = Application::class)
class Audit3Settings107Test {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private fun source(path: String) = File("src/main/java/io/github/xgl34222220/hetu/$path").readText()
    private var vm: HetuViewModel? = null

    @Before fun clean() {
        app.getSharedPreferences("hetu", 0).edit().clear()
            .putString("appearance", "light").putBoolean("hetuDynamicColor", false).putBoolean("enableBlur", false).commit()
    }

    @After fun finish() { vm?.onBackground() }

    @Test fun cpuAffinityFollowsTheStartScriptRules() {
        assertEquals(0xffL, HxResourceLimits.cpuMask("0-7"))
        assertEquals(0x75L, HxResourceLimits.cpuMask("0,2,4-6"))
        assertEquals(1L shl 23, HxResourceLimits.cpuMask("23"))
        assertEquals(1L, HxResourceLimits.cpuMask("0,"))
        listOf("0~3", "01", "0-24", "3-1", "-1", "1-", "0--2", "0-1-2", "0,,2", ",0", "100", " 0", "0 - 3").forEach {
            assertNull("accepted $it", HxResourceLimits.cpuMask(it))
            assertEquals(HxResourceLimits.CPU_ERROR, HxResourceLimits.cpuError(it))
        }
        assertNull(HxResourceLimits.cpuError(""))
        assertNull(HxResourceLimits.cpuError("0-3"))
        assertEquals(15, HxResourceLimits.cpuBeyondDevice("0-15", available = 8))
        assertNull(HxResourceLimits.cpuBeyondDevice("0-7", available = 8))
        assertNull(HxResourceLimits.cpuBeyondDevice("0~7", available = 8))
    }

    @Test fun memoryAndIoFollowTheStartScriptRules() {
        assertEquals(134_217_728L, HxResourceLimits.memoryBytes("128M"))
        assertEquals(1_073_741_824L, HxResourceLimits.memoryBytes("1g"))
        assertEquals(134_217_728L, HxResourceLimits.memoryBytes("128MB"))
        assertEquals(134_217_728L, HxResourceLimits.memoryBytes("128MiB"))
        assertEquals(33_554_432L, HxResourceLimits.memoryBytes("32M"))
        assertEquals(17_179_869_184L, HxResourceLimits.memoryBytes("16G"))
        listOf("100", "31M", "1.5G", "128 M", "M", "128T").forEach { assertEquals("$it", HxResourceLimits.MEMORY_ERROR, HxResourceLimits.memoryError(it)) }
        assertEquals(HxResourceLimits.MEMORY_MAX_ERROR, HxResourceLimits.memoryError("17G"))
        assertNull(HxResourceLimits.memoryError(""))
        assertNull(HxResourceLimits.memoryError("100M"))
        ('0'..'7').forEach { assertNull(HxResourceLimits.ioError(it.toString())) }
        listOf("8", "9", "07", "-1", " 4").forEach { assertEquals(it, HxResourceLimits.IO_ERROR, HxResourceLimits.ioError(it)) }
        assertNull(HxResourceLimits.ioError(""))
    }

    /** The Kotlin rules give the verdicts (and the CPU mask) of the script's own cpu_mask / mem_bytes. */
    @Test fun kotlinRulesMatchTheRootScriptFunctions() {
        val script = File("src/main/assets/hetu-root.sh").readText()
        fun function(name: String) = Regex("""(?ms)^$name\(\)\{\n.*?^\}\n""").find(script)!!.value
        val functions = function("cpu_mask") + function("mem_bytes")
        fun run(call: String, value: String): String? {
            val process = ProcessBuilder("sh", "-c", "$functions\n$call \"\$1\"", "sh", value).redirectErrorStream(true).start()
            assertTrue(process.waitFor(10, TimeUnit.SECONDS))
            val out = process.inputStream.bufferedReader().readText().trim()
            return out.takeIf { process.exitValue() == 0 }
        }
        listOf("0-7", "0,2,4-6", "23", "0,", "0~3", "01", "0-24", "3-1", "-1", "0--2", "0,,2", ",0", "100", "7-7").forEach { value ->
            assertEquals("cpu $value", run("cpu_mask", value), HxResourceLimits.cpuMask(value)?.let { java.lang.Long.toHexString(it) })
        }
        listOf("128M", "1g", "128MB", "128MiB", "32M", "16G", "17G", "100", "31M", "1.5G", "M", "33554432").forEach { value ->
            assertEquals("mem $value", run("mem_bytes", value), HxResourceLimits.memoryBytes(value)?.toString())
        }
        assertTrue(script.contains("case \"\$START_IO\" in [0-7]) ;;"))
        // The UI reports the script's own wording.
        assertTrue(script.contains(HxResourceLimits.CPU_ERROR) && script.contains(HxResourceLimits.MEMORY_ERROR) && script.contains(HxResourceLimits.IO_ERROR))
    }

    @Test fun resourceFieldsWriteOnlyValidDraftsAndShowTheError() {
        val page = source("ProxyAdvancedSettingsActivity.kt")
        assertTrue(page.contains("validate = HxResourceLimits::cpuError"))
        assertTrue(page.contains("validate = HxResourceLimits::memoryError"))
        assertTrue(page.contains("validate = HxResourceLimits::ioError"))
        assertTrue(page.contains("value = draft, onValueChange = { draft = it }"))
        assertTrue(page.contains("fun commit() { if (latest != stored && check(latest) == null) write(latest) }"))
        assertFalse("each keystroke must not reach prefs", page.contains("value = value, onValueChange = onValue"))
    }

    @Test fun notificationPreviewRendersEveryVariable() {
        val values = hxNotificationPreviewValues()
        val all = listOf("status", "uptime", "upload", "download", "upload_total", "download_total", "cpu", "memory", "connections", "config", "core", "mode")
        assertEquals(all.toSet(), values.keys)
        val body = hxNotificationPreview(ProxyStatusNotificationService.DEFAULT_TEMPLATE, values)
        assertFalse(body, body.contains("{"))
        assertTrue(body.startsWith("运行中 · "))
        assertEquals("河图 · 运行中", hxNotificationPreview(ProxyStatusNotificationService.DEFAULT_TITLE_TEMPLATE, values))
        assertEquals("{unknown} 42", hxNotificationPreview("{unknown} {connections}", values))
    }

    @Test fun newWordsTranslate() {
        listOf("预览 · 示例值", "通知设置的修改还没有保存。", HxResourceLimits.CPU_ERROR, HxResourceLimits.MEMORY_ERROR,
            HxResourceLimits.MEMORY_MAX_ERROR, HxResourceLimits.IO_ERROR, HxResourceLimits.CPU_BEYOND_DEVICE).forEach { key ->
            listOf("en", "zh-TW", "ru").forEach { lang -> assertTrue("$lang: $key", translateHetuText(key, lang) != key) }
        }
        listOf("en", "zh-TW", "ru").forEach { lang -> assertEquals(2, Regex("%s").findAll(translateHetuText(HxResourceLimits.CPU_BEYOND_DEVICE, lang)).count()) }
    }

    @Test fun editedTemplateAsksBeforeLeavingAndShowsThePreview() {
        val model = HetuViewModel(app).also { vm = it }
        var left = false
        rule.setContent {
            HetuAppTheme("light", false) {
                CompositionLocalProvider(LocalNav provides HxNav(), LocalHxBlur provides model.blurEnabled) {
                    NotificationSettingsScreen(model) { left = true }
                }
            }
        }
        rule.onNodeWithText("预览 · 示例值").assertExists()
        rule.onNodeWithText("河图 · 运行中").assertExists()
        rule.onAllNodes(hasSetTextAction())[1].performTextReplacement("{status} 测试107")
        rule.onNodeWithText("运行中 测试107").assertExists()
        rule.onNode(hasClickAction() and hasContentDescription("返回")).performClick()
        rule.waitForIdle()
        assertFalse(left)
        rule.onNodeWithText("放弃修改？").assertExists()
        rule.onAllNodesWithText("放弃")[0].performClick()
        rule.waitForIdle()
        assertTrue(left)
        assertEquals(ProxyStatusNotificationService.DEFAULT_TEMPLATE,
            app.getSharedPreferences("hetu", 0).getString(ProxyStatusNotificationService.PREF_TEMPLATE, ProxyStatusNotificationService.DEFAULT_TEMPLATE))
    }
}
