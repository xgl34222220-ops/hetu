package io.github.xgl34222220.hetu

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 4.0.0 UI pass (source shape): scrolling chip rows fade at the clipped edge, single-line labels
 * end in an ellipsis instead of a hard cut, and 配置与订阅 keeps a failed read on the page.
 */
class UiPolish400Test {
    private fun source(path: String) = File("src/main/java/io/github/xgl34222220/hetu/$path").readText()

    private fun textCalls(src: String): List<String> {
        val calls = mutableListOf<String>()
        Regex("""\bText\(""").findAll(src).forEach { match ->
            var i = match.range.last + 1
            var depth = 1
            while (depth > 0 && i < src.length) {
                when (src[i]) {
                    '(' -> depth++
                    ')' -> depth--
                    '"' -> { i++; while (i < src.length && src[i] != '"') { if (src[i] == '\\') i++; i++ } }
                }
                i++
            }
            calls += src.substring(match.range.first, i)
        }
        return calls
    }

    @Test fun scrollingChipRowsFadeAtTheClippedEdge() {
        val rows = mapOf(
            "panel/PanelListTabs.kt" to 2,
            "panel/PanelOverlays.kt" to 1,
            "app/ToolScreens.kt" to 2,
            "app/OverrideScriptsSection.kt" to 2,
            "app/ConfigScreens.kt" to 1,
        )
        rows.forEach { (path, count) ->
            val src = source(path)
            assertEquals(path, count, Regex("""\.panelHorizontalEdgeFade\(\w+\)\.horizontalScroll\(\w+\)""").findAll(src).count())
            assertFalse("$path: no unfaded chip row left", src.contains(".horizontalScroll(rememberScrollState())"))
        }
    }

    @Test fun liveSingleLineLabelsEndInAnEllipsis() {
        val files = listOf(
            "home/HomeScreen.kt", "panel/PanelScreen.kt", "panel/PanelOverviewTab.kt", "tools/ToolsEditorScreen.kt",
            "ui/HetuGlassDock.kt", "ui/HetuTabs.kt", "ui/HetuInteractions.kt", "ui/HetuLiquidGlassSystem.kt", "app/ToolScreens.kt",
            "app/MiscScreens.kt", "app/CoreConceptComponents.kt",
        )
        files.forEach { path ->
            val clipped = textCalls(source(path)).filter { Regex("""maxLines\s*=\s*1\b""").containsMatchIn(it) && "overflow" !in it }
            assertTrue("$path: ${clipped.joinToString("\n")}", clipped.isEmpty())
        }
        val log = source("ProxyLogViewerActivity.kt")
        assertTrue(log.contains("style = HomeType.buttonSmall.copy(fontSize = 15.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)"))
    }

    @Test fun configLibraryKeepsAFailedReadOnThePageWithRetry() {
        val configs = source("app/ConfigScreens.kt")
        assertTrue(configs.contains("loadError = hxErrorMessage(error, \"配置读取失败\")"))
        assertTrue(configs.contains("tone = HomeTone.Bad, actionLabel = \"重试\", onAction = { revision++ },"))
        assertTrue("no misleading empty-subscription row after a failed read",
            configs.contains("if (subscriptions.isEmpty() && !loading && loadError == null) {"))
        assertTrue("subscriptions show placeholders on the first read",
            configs.contains("if (subscriptions.isEmpty() && loading) {"))
    }
}
