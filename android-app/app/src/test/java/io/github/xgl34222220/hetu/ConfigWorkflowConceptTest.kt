package io.github.xgl34222220.hetu

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import io.github.rosemoe.sora.widget.CodeEditor
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowDialog
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.IOException

/** Actual configuration renderer, real local library/CAS, test-only Root/API boundaries. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w393dp-h852dp-mdpi", application = Application::class,
    shadows = [ConceptRootBridgeShadow::class, ConceptMihomoClientShadow::class, ConceptRuntimeInspectorShadow::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ConfigWorkflowConceptTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var vm: HetuViewModel
    private lateinit var library: ProxyConfigLibrary
    private lateinit var entry: ProxyConfigLibrary.Entry
    private val source = """
        mixed-port: 7890
        proxy-providers:
          main:
            type: http
            url: 'https://example.invalid/original'
            path: ./main.yaml
            interval: 86400
        proxy-groups:
          - name: select
            type: select
            use: [main]
        rules: [MATCH,DIRECT]
    """.trimIndent() + "\n"
    private val core get() = ProxyRuntimeProfile.load(vm.prefs).core
    private var readFailure = false
    private var writeFailure = false
    private var saveCount = 0
    private val repository = object : ConfigEditorRepository {
        override suspend fun load(): ConfigEditSnapshot {
            if (readFailure) throw IOException("测试读取失败")
            val selected = requireNotNull(library.selected(core))
            return ConfigEditSnapshot(core.id, selected.name, library.read(selected))
        }
        override suspend fun validate(text: String) = Unit
        override suspend fun save(snapshot: ConfigEditSnapshot, text: String) {
            saveCount++
            if (writeFailure) throw IOException("测试磁盘写入失败")
            val selected = requireNotNull(library.selected(core))
            snapshot.requireUnchanged(core.id, selected.name, library.read(selected))
            library.writeIfUnchanged(selected, snapshot, text)
        }
    }

    @Before fun prepare() {
        app.getSharedPreferences("hetu", Context.MODE_PRIVATE).edit().clear()
            .putBoolean("enableBlur", false).putBoolean("liquidGlass", false).commit()
        vm = newConceptTestVm(app)
        library = ProxyConfigLibrary(app)
        entry = library.importConfig(core, "工作流测试.yaml", source.byteInputStream())
    }
    @After fun close() {
        if (::vm.isInitialized) rule.runOnIdle { closeConceptTestVm(vm) }
    }
    private fun node(tag: String) = rule.onNodeWithTag(tag, useUnmergedTree = true)
    private fun awaitTag(tag: String) {
        rule.waitUntil(5_000) { rule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        rule.waitForIdle()
    }
    private fun back() { rule.runOnIdle { rule.activity.onBackPressedDispatcher.onBackPressed() }; rule.waitForIdle() }
    private fun cancelDialog() = rule.onNode(hasText("取消", substring = false) and hasAnyAncestor(isDialog()))
        .assertIsDisplayed().performClick()
    private fun renderLibrary(fontScale: Float = 1f, motion: Boolean = false) {
        vm.tab = HxTab.Tools
        rule.setContent {
            HetuAppTheme("light", dynamic = false) {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale), LocalHxMotionEnabled provides motion) {
                    HetuRoot(vm)
                }
            }
        }
        rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag("tool-配置管理"))
        node("tool-配置管理").performClick()
        awaitTag("config-entry-${entry.name}")
    }
    private fun renderEditor(fontScale: Float = 1f) {
        rule.setContent {
            HetuAppTheme("light", dynamic = false) {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale), LocalHxMotionEnabled provides false) {
                    ConfigEditorScreen(vm, onBackOverride = {}, editorRepository = repository)
                }
            }
        }
        rule.waitForIdle()
    }
    private fun findEditor(view: View = rule.activity.window.decorView): CodeEditor? {
        if (view is CodeEditor) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) findEditor(view.getChildAt(index))?.let { return it }
        return null
    }
    private fun typeEditor(text: String) {
        rule.runOnIdle {
            val native = requireNotNull(findEditor())
            native.requestFocus()
            native.setSelection(0, 0)
            assertTrue(requireNotNull(native.onCreateInputConnection(EditorInfo())).commitText(text, 1))
        }
        rule.waitForIdle()
    }
    private fun editorText() = rule.runOnIdle { requireNotNull(findEditor()).text.toString() }
    private fun externalChange() { library.write(entry, "# external change\n" + source) }
    private fun conflict() {
        typeEditor("# local draft\n")
        externalChange()
        rule.onNodeWithContentDescription("保存").performClick()
        rule.onNodeWithText("文件已在其他位置修改").assertIsDisplayed()
    }
    private fun screenshot(name: String) {
        rule.waitForIdle()
        rule.runOnIdle {
            val view = ShadowDialog.getLatestDialog()?.takeIf { it.isShowing }?.window?.decorView
                ?: rule.activity.window.decorView
            assertTrue(view.width > 0 && view.height > 0)
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            val file = File("build/outputs/hetu-concept-root/config-workflow-$name.png")
            file.parentFile!!.mkdirs()
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
    private fun openImport() {
        rule.onNodeWithContentDescription("导入配置").performClick()
        awaitTag("config-import-page")
    }
    private fun chooseFile(name: String, content: String?, cancelled: Boolean = false) {
        rule.onNodeWithText("选择文件").performScrollTo().performClick()
        val request = shadowOf(rule.activity).nextStartedActivityForResult
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, request.intent.action)
        val uri = Uri.parse("content://hetu-config-test/$name")
        if (content != null) shadowOf(app.contentResolver).registerInputStream(uri, content.byteInputStream())
        rule.runOnIdle {
            shadowOf(rule.activity).receiveResult(request.intent, if (cancelled) Activity.RESULT_CANCELED else Activity.RESULT_OK,
                if (cancelled) null else Intent().setData(uri))
        }
        rule.waitForIdle()
    }

    @Test fun libraryUsesTheRealSelectedSourceAndOneImportEntry() {
        renderLibrary()
        node("config-library-card").assertIsDisplayed()
        rule.onAllNodesWithContentDescription("导入配置").assertCountEquals(1)
        rule.onNodeWithText("当前配置").assertExists()
        screenshot("01-configuration-library")
        node("config-subscription-main").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("正常", substring = false).assertDoesNotExist()
        rule.onNodeWithText("未验证", substring = false).assertDoesNotExist()
        assertEquals(source, library.read(entry))
    }

    @Test fun linkImportIsAFullPageAndCancelledDraftNeverApplies() {
        renderLibrary(); openImport()
        rule.onAllNodes(isDialog()).assertCountEquals(0)
        rule.onNodeWithText("从链接导入").performClick()
        node("config-import-url").performTextInput("https://example.invalid/new.yaml")
        node("config-import-name").performTextInput("旅行")
        screenshot("03-import-link-page")
        back()
        rule.onNodeWithText("放弃填写？").assertIsDisplayed()
        cancelDialog()
        node("config-import-url").assertTextContains("https://example.invalid/new.yaml")
        back(); rule.onNodeWithText("放弃", substring = false).performClick()
        awaitTag("config-library-card")
        assertEquals(source, library.read(entry))
        assertEquals(entry.name, library.selected(core)?.name)
        openImport()
        rule.onNodeWithText("从链接导入").performClick()
        node("config-import-url").assertTextEquals("")
    }

    @Test fun invalidLinkStaysInThePageAndNeverStartsARequest() {
        renderLibrary(); openImport()
        rule.onNodeWithText("从链接导入").performClick()
        node("config-import-url").performTextInput("httpbad://example.invalid/config")
        node("config-import-submit").performScrollTo().performClick()
        node("config-form-error").performScrollTo().assertTextEquals("请输入有效的 http/https 链接")
        assertEquals(entry.name, library.selected(core)?.name)
        assertEquals(source, library.read(entry))
    }

    @Test fun documentPickerCancellationDoesNotImportOrLeaveThePage() {
        renderLibrary(); openImport()
        chooseFile("cancelled.yaml", null, cancelled = true)
        node("config-import-page").assertExists()
        node("config-import-submit").assertIsNotEnabled()
        assertEquals(entry.name, library.selected(core)?.name)
    }

    @Test fun chosenFileIsStagedThenImportedThroughTheRealControllerOnce() {
        renderLibrary(); openImport()
        val imported = "mixed-port: 9999\nproxies: []\nrules: [MATCH,DIRECT]\n"
        chooseFile("file-import.yaml", imported)
        assertEquals(entry.name, library.selected(core)?.name)
        val action = node("config-import-submit").performScrollTo().fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        rule.runOnIdle { action(); action() }
        awaitTag("config-entry-file-import.yaml")
        val selected = requireNotNull(library.selected(core))
        assertEquals("file-import.yaml", selected.name)
        assertEquals(imported, library.read(selected))
        assertEquals(1, library.list(core).count { it.name.startsWith("file-import") })
        assertEquals(source, library.read(entry))
    }

    @Test fun unreadableImportRetainsSelectionAndOffersRetry() {
        renderLibrary(); openImport()
        chooseFile("unreadable.yaml", null)
        node("config-import-submit").performScrollTo().performClick()
        awaitTag("config-form-error")
        node("config-import-page").assertExists()
        node("config-import-submit").performScrollTo().assertIsEnabled()
        assertEquals(entry.name, library.selected(core)?.name)
        assertEquals(source, library.read(entry))
    }

    @Test fun subscriptionEditUsesRealLibraryAndCancelledChangesAreRetainedOnlyUntilDiscarded() {
        renderLibrary()
        node("config-subscription-main").performScrollTo().performClick()
        awaitTag("config-subscription-page")
        screenshot("04-subscription-editor")
        node("config-subscription-url").performTextReplacement("https://example.invalid/updated")
        back(); cancelDialog()
        node("config-subscription-url").assertTextContains("https://example.invalid/updated")
        assertEquals(source, library.read(entry))
        node("config-subscription-save").performScrollTo().performClick()
        awaitTag("config-library-card")
        assertEquals("https://example.invalid/updated", library.subscriptions(entry).single().url)
        node("config-subscription-main").performScrollTo().performClick()
        node("config-subscription-url").assertTextContains("https://example.invalid/updated")
        node("config-subscription-url").performTextReplacement("https://example.invalid/discard")
        back(); rule.onNodeWithText("放弃", substring = false).performClick()
        awaitTag("config-library-card")
        assertEquals("https://example.invalid/updated", library.subscriptions(entry).single().url)
    }

    @Test fun duplicateSubscriptionFailureKeepsTheFormAndOriginalConfig() {
        renderLibrary()
        rule.onNodeWithContentDescription("添加订阅").performScrollTo().performClick()
        node("config-subscription-name").performTextInput("main")
        node("config-subscription-url").performTextInput("https://example.invalid/duplicate")
        node("config-subscription-save").performScrollTo().performClick()
        awaitTag("config-form-error")
        node("config-form-error").performScrollTo().assertTextEquals("订阅名称已存在")
        node("config-subscription-url").performScrollTo().assertTextContains("https://example.invalid/duplicate")
        assertEquals(source, library.read(entry))
    }

    @Test fun conflictKeepsDirtyDraftByDefaultAndCancelDoesNotReload() {
        renderEditor(); conflict()
        screenshot("05-conflict-recovery")
        rule.onNodeWithText("保留草稿").performClick()
        assertEquals("# local draft\n" + source, editorText())
        rule.onNodeWithText("处理冲突").performClick()
        rule.onNodeWithText("重新读取", substring = false).performClick()
        rule.onNodeWithText("放弃草稿并重新读取？").assertIsDisplayed()
        cancelDialog()
        assertEquals("# local draft\n" + source, editorText())
        assertEquals("# external change\n" + source, library.read(entry))
    }

    @Test fun confirmedReloadReplacesDraftAndResetsTheCasBaseline() {
        renderEditor(); conflict()
        rule.onNodeWithText("重新读取", substring = false).performClick()
        rule.onNodeWithText("放弃并读取").performClick()
        assertEquals("# external change\n" + source, editorText())
        rule.onNodeWithContentDescription("保存").assertIsNotEnabled()
        typeEditor("# after reload\n")
        rule.onNodeWithContentDescription("保存").performClick()
        rule.waitForIdle()
        assertEquals("# after reload\n# external change\n" + source, library.read(entry))
        assertEquals(2, saveCount)
        screenshot("02-configuration-editor")
    }

    @Test fun failedReloadLeavesTheNativeDraftAndUndoHistoryIntact() {
        renderEditor(); conflict()
        readFailure = true
        rule.onNodeWithText("重新读取", substring = false).performClick()
        rule.onNodeWithText("放弃并读取").performClick()
        rule.onNodeWithText("重新读取失败，草稿仍保留", substring = true).assertIsDisplayed()
        assertEquals("# local draft\n" + source, editorText())
        rule.onNodeWithContentDescription("撤销").performClick()
        assertEquals(source, editorText())
        assertEquals("# external change\n" + source, library.read(entry))
    }

    @Test fun unchangedSourceWriteFailureIsNotMislabelledAsAnExternalConflict() {
        renderEditor(); typeEditor("# retained\n")
        writeFailure = true
        rule.onNodeWithContentDescription("保存").performClick()
        rule.onNodeWithText("测试磁盘写入失败").assertIsDisplayed()
        rule.onNodeWithText("文件已在其他位置修改").assertDoesNotExist()
        rule.onNodeWithText("保留草稿").assertDoesNotExist()
        assertEquals("# retained\n" + source, editorText())
    }

    @Test fun changedSelectionRecoveryNamesTheNewFileAndCannotRedirectTheFailedSave() {
        renderEditor(); typeEditor("# old draft\n")
        val other = library.importConfig(core, "另一个配置.yaml", "rules: [MATCH,DIRECT]\n".byteInputStream())
        rule.onNodeWithContentDescription("保存").performClick()
        rule.onNodeWithText("保存遇到冲突").assertIsDisplayed()
        rule.onNodeWithText("当前选择为「${other.name}」", substring = true).assertIsDisplayed()
        assertEquals(source, library.read(entry))
        assertEquals("rules: [MATCH,DIRECT]\n", library.read(other))
        rule.onNodeWithText("保留草稿").performClick()
        assertEquals("# old draft\n" + source, editorText())
    }

    @Test @Config(qualifiers = "w320dp-h820dp-mdpi")
    fun largeFontImportUsesReachableActionsAndStillHonorsDirtyBack() {
        renderLibrary(fontScale = 2f); openImport()
        rule.onNodeWithText("从链接导入").performScrollTo().performClick()
        node("config-import-url").performScrollTo().performTextInput("https://example.invalid/large.yaml")
        rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag("config-import-submit"))
        node("config-import-submit").performScrollTo().assertIsDisplayed()
        val bounds = node("config-import-submit").getUnclippedBoundsInRoot()
        assertTrue((bounds.right - bounds.left) >= 48.dp && (bounds.bottom - bounds.top) >= 48.dp)
        screenshot("06-import-320dp-200pct")
        back()
        cancelDialog()
        node("config-import-page").assertExists()
    }

    @Test @Config(qualifiers = "w320dp-h820dp-mdpi")
    fun largeFontEditorKeepsNativeImeEditingAnd48DpTools() {
        renderEditor(fontScale = 2f)
        typeEditor("# keyboard input\n")
        for (description in listOf("撤销", "重做", "语法大纲", "校验")) {
            val bounds = rule.onNodeWithContentDescription(description).getUnclippedBoundsInRoot()
            assertTrue("$description target", (bounds.right - bounds.left) >= 48.dp && (bounds.bottom - bounds.top) >= 48.dp)
        }
        rule.runOnIdle { assertTrue(requireNotNull(findEditor()).height > 100) }
        rule.onNodeWithContentDescription("撤销").performClick()
        assertEquals(source, editorText())
        screenshot("07-editor-320dp-200pct")
    }

    @Test @Config(qualifiers = "w320dp-h820dp-mdpi")
    fun largeFontSubscriptionFieldsAndSaveRemainReachable() {
        renderLibrary(fontScale = 2f)
        node("config-subscription-main").performScrollTo().performClick()
        node("config-subscription-url").performScrollTo().performTextReplacement("https://example.invalid/large-font")
        node("config-subscription-save").performScrollTo().assertIsDisplayed()
        val bounds = node("config-subscription-save").getUnclippedBoundsInRoot()
        assertTrue((bounds.right - bounds.left) >= 48.dp && (bounds.bottom - bounds.top) >= 48.dp)
        screenshot("10-subscription-320dp-200pct")
        back()
        cancelDialog()
        node("config-subscription-url").performScrollTo().assertTextContains("https://example.invalid/large-font")
    }

    @Test fun workflowTransitionSettlesOnTheFullPageWithMotionEnabled() {
        renderLibrary(motion = true)
        rule.mainClock.autoAdvance = false
        rule.onNodeWithContentDescription("导入配置").performClick()
        rule.mainClock.advanceTimeBy(100)
        screenshot("08-import-motion-middle")
        rule.mainClock.advanceTimeBy(900)
        node("config-import-page").assertIsDisplayed()
        node("config-library-card").assertDoesNotExist()
        screenshot("09-import-motion-settled")
        rule.mainClock.autoAdvance = true
    }
}
