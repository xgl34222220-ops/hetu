package io.github.xgl34222220.hetu

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import io.github.rosemoe.sora.widget.CodeEditor
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w393dp-h852dp-mdpi", application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ConfigEditorSafetyTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val initial = "mixed-port: 7890\nrules:\n  - MATCH,DIRECT\n"
    private var left = false
    private val saved = mutableListOf<String>()
    private var loadFailure = false
    private var saveFailure = false
    private var gate: CompletableDeferred<Unit>? = null
    private var lastSnapshot: ConfigEditSnapshot? = null
    private var liveSource = initial
    private var liveName = "示例配置.yaml"
    private var liveCore = "mihomo"
    private var saveAttempts = 0
    private val repository = object : ConfigEditorRepository {
        override suspend fun load(): ConfigEditSnapshot {
            if (loadFailure) throw IOException("配置暂时无法读取")
            return ConfigEditSnapshot(liveCore, liveName, liveSource)
        }
        override suspend fun validate(text: String) = Unit
        override suspend fun save(snapshot: ConfigEditSnapshot, text: String) {
            saveAttempts++
            lastSnapshot = snapshot
            snapshot.requireUnchanged(liveCore, liveName, liveSource)
            gate?.await()
            if (saveFailure) throw IOException("测试写入失败")
            snapshot.requireUnchanged(liveCore, liveName, liveSource)
            liveSource = text
            saved += text
        }
    }
    @Before fun clean() { app.getSharedPreferences("hetu", Context.MODE_PRIVATE).edit().clear().commit() }
    private fun findEditor(view: View = rule.activity.window.decorView): CodeEditor? {
        if (view is CodeEditor) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) findEditor(view.getChildAt(index))?.let { return it }
        return null
    }
    private fun render() {
        val vm = HetuViewModel(app)
        rule.setContent { HetuAppTheme(appearance = "light", dynamic = false) {
            ConfigEditorScreen(vm, onBackOverride = { left = true }, editorRepository = repository)
        } }
        rule.waitForIdle()
    }
    private fun typeText(text: String) {
        rule.runOnIdle {
            val native = requireNotNull(findEditor())
            native.requestFocus()
            native.setSelection(0, 0)
            val connection = requireNotNull(native.onCreateInputConnection(EditorInfo()))
            assertTrue(connection.commitText(text, 1))
        }
        rule.waitForIdle()
    }
    private fun screenshot(name: String) = rule.runOnIdle {
        val view = rule.activity.window.decorView
        val image = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(image))
        val out = File("build/outputs/hetu-ui-consolidation/$name.png")
        out.parentFile!!.mkdirs()
        out.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
    }
    @Test fun nativeKeyboardCanEditAndSaveTheExactOpenedFile() {
        render()
        rule.onNodeWithText("示例配置.yaml").assertIsDisplayed()
        rule.onNodeWithContentDescription("保存").assertIsNotEnabled()
        typeText("# local change\n")
        rule.onNodeWithContentDescription("保存").assertIsEnabled().performClick()
        rule.waitForIdle()
        assertEquals(listOf("# local change\n" + initial), saved)
        assertEquals("示例配置.yaml", lastSnapshot?.name)
        rule.onNodeWithContentDescription("保存").assertIsNotEnabled()
        screenshot("configuration-editor")
    }
    @Test fun readFailureDoesNotCreateAnEmptyWritableEditorAndCanRetry() {
        loadFailure = true; render()
        assertNull(findEditor())
        rule.onNodeWithContentDescription("保存").assertIsNotEnabled()
        rule.onNodeWithText("配置暂时无法读取").assertIsDisplayed()
        screenshot("configuration-read-failure")
        loadFailure = false
        rule.onNodeWithText("重新读取").performClick()
        rule.waitForIdle()
        rule.runOnIdle { assertEquals(initial, requireNotNull(findEditor()).text.toString()) }
    }
    @Test fun saveIsSingleFlightAndCannotLoseEditsMadeWhileSaving() {
        gate = CompletableDeferred(); render(); typeText("# preserved\n")
        val activeConnection = rule.runOnIdle {
            requireNotNull(requireNotNull(findEditor()).onCreateInputConnection(EditorInfo()))
        }
        val saveAction = rule.onNodeWithContentDescription("保存").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        rule.runOnIdle { saveAction(); saveAction() }
        rule.waitForIdle()
        rule.runOnIdle {
            val native = requireNotNull(findEditor())
            assertFalse(native.isEditable)
            activeConnection.commitText("MUST NOT APPEAR", 1)
            rule.activity.onBackPressedDispatcher.onBackPressed()
        }
        rule.onNodeWithContentDescription("撤销").performClick()
        rule.runOnIdle { assertEquals("# preserved\n" + initial, requireNotNull(findEditor()).text.toString()) }
        rule.onNodeWithContentDescription("重做").performClick()
        rule.runOnIdle { assertEquals("# preserved\n" + initial, requireNotNull(findEditor()).text.toString()) }
        rule.onNodeWithContentDescription("返回").assertIsNotEnabled()
        rule.onNodeWithContentDescription("校验").assertIsNotEnabled()
        rule.runOnIdle {
            assertEquals("# preserved\n" + initial, requireNotNull(findEditor()).text.toString())
            assertEquals(1, saveAttempts)
            assertFalse(left)
            gate!!.complete(Unit)
        }
        rule.waitForIdle()
        assertEquals(1, saved.size)
        rule.runOnIdle { assertTrue(requireNotNull(findEditor()).isEditable) }
        assertFalse(left)
    }
    @Test fun failedSaveKeepsDraftAndAllowsRetry() {
        saveFailure = true; render(); typeText("# retained\n")
        rule.onNodeWithContentDescription("保存").performClick(); rule.waitForIdle()
        rule.onNodeWithText("测试写入失败").assertIsDisplayed()
        rule.onNodeWithContentDescription("保存").assertIsEnabled()
        rule.runOnIdle { assertEquals("# retained\n" + initial, requireNotNull(findEditor()).text.toString()) }
        saveFailure = false
        rule.onNodeWithContentDescription("保存").performClick(); rule.waitForIdle()
        assertEquals(1, saved.size)
    }
    @Test fun undoToTheOriginalClearsDirtyState() {
        render(); typeText("# undo\n")
        rule.onNodeWithContentDescription("撤销").performClick(); rule.waitForIdle()
        rule.onNodeWithContentDescription("保存").assertIsNotEnabled()
        rule.onNodeWithContentDescription("返回").performClick(); assertTrue(left)
    }
    @Test fun cancellingDiscardKeepsTheDraft() {
        render(); typeText("# keep\n")
        rule.onNodeWithContentDescription("返回").performClick()
        rule.onNodeWithText("放弃修改？").assertIsDisplayed()
        rule.onNodeWithText("取消").performClick()
        assertFalse(left)
        rule.runOnIdle { assertEquals("# keep\n" + initial, requireNotNull(findEditor()).text.toString()) }
    }
    @Test fun secondSaveUsesTheNewlySavedBaseline() {
        render(); typeText("# first\n")
        rule.onNodeWithContentDescription("保存").performClick(); rule.waitForIdle()
        typeText("# second\n")
        rule.onNodeWithContentDescription("保存").performClick(); rule.waitForIdle()
        assertEquals(2, saved.size)
        assertEquals("# first\n" + initial, lastSnapshot?.originalText)
        assertEquals("# second\n# first\n" + initial, liveSource)
    }
    @Test fun externalChangePreservesTheDraftAndDoesNotOverwriteTheSource() {
        render(); typeText("# draft\n")
        liveSource = "# external\n" + initial
        rule.onNodeWithContentDescription("保存").performClick(); rule.waitForIdle()
        assertTrue(saved.isEmpty())
        assertEquals("# external\n" + initial, liveSource)
        rule.onNodeWithText("配置已在其他页面更新", substring = true).assertIsDisplayed()
        rule.runOnIdle { assertEquals("# draft\n" + initial, requireNotNull(findEditor()).text.toString()) }
    }
    @Test fun selectedFileChangeCannotRedirectTheSave() {
        render(); typeText("# draft\n")
        liveName = "另一份配置.yaml"
        rule.onNodeWithContentDescription("保存").performClick(); rule.waitForIdle()
        assertTrue(saved.isEmpty())
        assertEquals(initial, liveSource)
        rule.onNodeWithText("当前配置已切换", substring = true).assertIsDisplayed()
    }

}
