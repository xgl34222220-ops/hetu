package io.github.xgl34222220.hetu

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.EditorSearcher
import io.github.xgl34222220.hetu.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.Shadows.shadowOf
import android.os.Looper
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w480dp-h2400dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WorkbenchRegressionTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val app get() = ApplicationProvider.getApplicationContext<Context>()
    private fun capture(tag: String, name: String) {
        compose.waitForIdle()
        val b = compose.onNodeWithTag(tag, true).fetchSemanticsNode().boundsInWindow
        val bitmap = compose.runOnIdle {
            val decor = compose.activity.window.decorView
            val xy = IntArray(2).also { decor.getLocationInWindow(it) }
            Bitmap.createBitmap(kotlin.math.ceil(b.width).toInt(), kotlin.math.ceil(b.height).toInt(), Bitmap.Config.ARGB_8888).also {
                val c = Canvas(it); c.translate(xy[0] - b.left, xy[1] - b.top); decor.draw(c)
            }
        }
        File("build/reports/ui-audit/$name.png").apply { parentFile?.mkdirs() }.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun attachEditor(editor: CodeEditor) {
        compose.activity.setContentView(editor, ViewGroup.LayoutParams(360, 480))
        editor.measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(480, View.MeasureSpec.EXACTLY))
        editor.layout(0, 0, 360, 480)
        awaitEditable(editor)
    }

    private fun awaitEditable(editor: CodeEditor) {
        // Sora intentionally refuses editing until its asynchronous layout is ready.
        // Do not force layoutBusy=false or bypass the production isEditable guard.
        repeat(200) {
            shadowOf(Looper.getMainLooper()).idle()
            if (editor.isEditable) return
            Thread.sleep(10)
        }
        fail("Native editor did not finish layout: editable=${editor.editable}")
    }

    @Test fun nativeSymbolInsertionPreservesTextAndBatchIndentUndo() {
        val editor = CodeEditor(compose.activity).apply {
            setEditorLanguage(HetuYamlLanguage()); setWordwrap(false); setTabWidth(2); setText("")
        }
        try {
            attachEditor(editor)
            var expected = ""
            yamlWorkbenchSymbols.forEach { symbol ->
                awaitEditable(editor)
                applyYamlAccessory(editor, symbol)
                expected += when (symbol) {
                    "Tab" -> "  "
                    ":" -> ": "
                    "-" -> "- "
                    "#" -> "# "
                    else -> symbol
                }
                assertEquals(expected, editor.text.toString())
            }
            assertFalse(editor.text.toString().contains('\t'))
            val source = "name: 中文\npath: '/a'"
            editor.setText(source)
            awaitEditable(editor)
            editor.setSelectionRegion(0, 0, 1, 10)
            applyYamlAccessory(editor, "Tab")
            assertEquals("  name: 中文\n  path: '/a'", editor.text.toString())
            assertTrue(editor.canUndo())
            editor.undo()
            assertEquals(source, editor.text.toString())
            editor.redo()
            assertEquals("  name: 中文\n  path: '/a'", editor.text.toString())
        } finally { editor.release() }
    }

    @Test fun nativeLiteralSearchDoesNotModifyConfiguration() {
        val source = "proxy: DIRECT\n# proxy test\nserver: example.org"
        val editor = CodeEditor(compose.activity).apply { setWordwrap(false); setText(source) }
        try {
            attachEditor(editor)
            editor.searcher.search("proxy", EditorSearcher.SearchOptions(true, false))
            repeat(100) { Thread.sleep(10); shadowOf(Looper.getMainLooper()).idle() }
            assertEquals(2, editor.searcher.matchedPositionCount)
            assertTrue(editor.searcher.gotoNext())
            assertEquals(source, editor.text.toString())
            editor.searcher.stopSearch()
            assertFalse(editor.searcher.hasQuery())
        } finally { editor.release() }
    }

    @Test fun workbenchControlsHaveCallbacksAndReadableCursorInBothThemes() {
        var night by mutableStateOf(false)
        var font by mutableFloatStateOf(1f)
        var saves = 0; var searches = 0; var symbol = ""
        compose.setContent { key(night) {
            app.getSharedPreferences("hetu", 0).edit().putString("appearance", if (night) "dark" else "light").commit()
            HetuTheme { CompositionLocalProvider(LocalDensity provides Density(1f, font), LocalHetuMotionEnabled provides false) {
                Column(Modifier.width(360.dp).crystalPageBackground().testTag("workbench-scene")) {
                    YamlWorkbenchActions(true, false, false, false, {}, {}, {}, { searches++ }, {}, {}, { saves++ })
                    YamlWorkbenchAccessory(true) { symbol = it }
                    YamlCursorStatus(21, 9, 1200)
                }
            } }
        } }
        for (dark in listOf(false, true)) for (scale in listOf(1f, 1.5f)) {
            compose.runOnIdle { night = dark; font = scale }; compose.waitForIdle()
            compose.onNodeWithText("Ln 21 · Col 9 · UTF-8 · YAML").assertExists()
            compose.onNodeWithTag("yaml-action:保存").assertWidthIsEqualTo(48.dp)
            compose.onNodeWithTag("yaml-action:格式化").assertExists()
            compose.onNodeWithTag("yaml-action:校验").assertExists()
            capture("workbench-scene", "workbench105-$dark-$scale")
        }
        compose.onNodeWithTag("yaml-action:保存").performClick()
        compose.onNodeWithTag("yaml-action:搜索").performClick()
        compose.onNodeWithTag("yaml-symbol:Tab").performClick()
        compose.runOnIdle { assertEquals(1, saves); assertEquals(1, searches); assertEquals("Tab", symbol) }
    }

    @Test fun overviewUsesReportedCountsAndCurrentConnectionsOnly() {
        fun connection(id: String, chain: String) = ProxyConnectionUi(id, "example.org", "DOMAIN", "example.org", chain, 10, 20)
        val items = listOf(connection("1", "DIRECT"), connection("2", "DIRECT"), connection("3", "node → Google"))
        assertEquals(listOf("DIRECT" to 2, "Google" to 1), overviewRouteCounts(items))
        val state = ProxyComposeState(running = true, panelReady = true, connections = items,
            groups = listOf(ProxyGroupUi("Google", "Selector", "node", emptyList())))
        compose.setContent { HetuTheme { CompositionLocalProvider(LocalDensity provides Density(1f, 1f), LocalHetuMotionEnabled provides false) {
            Column(Modifier.width(360.dp).crystalPageBackground().padding(16.dp).testTag("overview-scene"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OverviewInstruments(state, 201)
                OverviewRouteRanking(state)
            }
        } } }
        compose.onNodeWithText("201").assertExists()
        compose.onNodeWithText("当前连接").assertExists()
        compose.onNodeWithTag("route-count:DIRECT").assertExists()
        capture("overview-scene", "overview103-real-counts")
    }

    @Test fun configuredBrandAddressesRemainExactIncludingFlagsAndAnchors() {
        val source = "template: &b {type: select, icon: 'https://icons.example/google.svg'}\nproxy-groups:\n" +
            "  - {<<: *b, name: '🇺🇸 Google', proxies: [DIRECT]}\n" +
            "  - {name: GitHub, type: select, icon: 'https://icons.example/github.png', proxies: [DIRECT]}"
        val icons = ProxyGroupIcons.parse(source)
        assertEquals("https://icons.example/google.svg", icons["🇺🇸 Google"])
        assertEquals("https://icons.example/github.png", icons["GitHub"])
        assertNotEquals(icons["🇺🇸 Google"], icons["GitHub"])
    }
}
