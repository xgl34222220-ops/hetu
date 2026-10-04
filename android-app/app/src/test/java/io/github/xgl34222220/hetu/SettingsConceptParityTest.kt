package io.github.xgl34222220.hetu

import android.app.Application
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.AnnotatedString
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "zh-rCN-w393dp-h852dp-mdpi", application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsConceptParityTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    /* Robolectric's Espresso idler loops on a second Compose dialog window even
       after both windows report no pending measure/layout. Drive real window
       frames explicitly and invoke the same semantics actions as performClick/
       performTextReplacement. Assertions still inspect the production dialog. */
    private fun frame() {
        rule.mainClock.advanceTimeBy(100)
        rule.runOnUiThread { Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100)) }
    }
    private fun dialog(initial: String, save: (String) -> Unit, dismiss: () -> Unit): () -> Unit {
        var shown by mutableStateOf(true)
        rule.mainClock.autoAdvance = false
        rule.runOnUiThread {
            rule.activity.setContent { HetuAppTheme("light", false) { Box(Modifier.fillMaxSize()) {
                if (shown) SettingsMirrorDialog(initial, { save(it); shown = false }, { dismiss(); shown = false })
            } } }
        }
        frame()
        return { rule.runOnUiThread { shown = false }; frame() }
    }
    private fun nodes(): List<SemanticsNode> {
        val result = mutableListOf<SemanticsNode>()
        fun walkNode(node: SemanticsNode) { result += node; node.children.forEach(::walkNode) }
        fun walkView(view: View) {
            val getter = view.javaClass.methods.firstOrNull { it.name == "getSemanticsOwner" }
            if (getter != null) walkNode((getter.invoke(view) as SemanticsOwner).rootSemanticsNode)
            if (view is ViewGroup) (0 until view.childCount).forEach { walkView(view.getChildAt(it)) }
        }
        WindowInspector.getGlobalWindowViews().forEach(::walkView)
        return result
    }
    private fun click(label: String) {
        rule.runOnUiThread {
            val node = nodes().first { node -> node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == label } == true && node.config.getOrNull(SemanticsActions.OnClick) != null }
            assertTrue(node.config[SemanticsActions.OnClick].action!!.invoke())
        }
        frame()
    }
    private fun replaceText(value: String) {
        rule.runOnUiThread {
            val field = nodes().single { it.config.getOrNull(SemanticsActions.SetText) != null }
            assertTrue(field.config[SemanticsActions.SetText].action!!.invoke(AnnotatedString(value)))
        }
        frame()
    }
    @Test fun invalidMirrorKeepsDialogOpenAndDoesNotSave() {
        val saved = mutableListOf<String>()
        val close = dialog("mirror.example", { saved += it }, {})
        try {
            click("保存")
            rule.runOnUiThread { assertTrue(nodes().any { n -> n.config.getOrNull(SemanticsProperties.Text)?.any { it.text == "请填写 http/https 地址" } == true }) }
            assertTrue(saved.isEmpty())
            replaceText("https://mirror.example/")
            click("保存")
            assertEquals(listOf("https://mirror.example/"), saved)
        } finally { close() }
    }
    @Test fun cancelMirrorDoesNotWriteDraft() {
        var dismissed = false
        val saved = mutableListOf<String>()
        val close = dialog("https://existing.example/", { saved += it }, { dismissed = true })
        try {
            replaceText("https://changed.example/")
            click("取消")
            assertTrue(dismissed)
            assertTrue(saved.isEmpty())
        } finally { close() }
    }
    @Test fun disabledResourceInputCannotChangeAndSwitchRowHasOneCallback() {
        var calls = 0
        var value = "0-7"
        rule.setContent { HetuAppTheme("light", false) { Column {
            SettingsSwitchRow("CPU 核心分配", false, { calls++ }, compact = true)
            SettingsInput(value, { value = it }, enabled = false)
        } } }
        rule.onNodeWithText("CPU 核心分配").performClick()
        assertEquals(1, calls)
        rule.onNodeWithText("0-7").assertIsNotEnabled()
        rule.onNode(hasSetTextAction()).assertDoesNotExist()
        assertEquals("0-7", value)
    }
}
