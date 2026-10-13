package io.github.xgl34222220.hetu

import android.app.Application
import io.github.xgl34222220.hetu.app.maskUrl
import io.github.xgl34222220.hetu.panel.PanelLogFilter
import io.github.xgl34222220.hetu.panel.PanelLogLevel
import io.github.xgl34222220.hetu.tools.ToolsYaml
import io.github.xgl34222220.hetu.ui.translateHetuText
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 2026-10-13 third audit, UI items (work/audit3-2026-10-13.md): G2 config-editor search / go-to-line,
 * U1 panel FAB clearance, U2 masked subscription links, U7 log-level names, U8 disconnect-all, U9
 * rolling-text ellipsis.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class Audit3Ui107Test {
    private fun source(path: String) = File("src/main/java/io/github/xgl34222220/hetu/$path").readText()

    /* G2 */
    @Test fun configEditorHasSearchGoToLineAndJumpsToTheFailingLine() {
        val editor = source("app/ConfigScreens.kt")
        assertTrue(editor.contains("YamlWorkbenchSearch(editor, matches)"))
        assertTrue(editor.contains("subscribeAlways<PublishSearchResultEvent>"))
        assertTrue(editor.contains("ConfigEditorAction(ToolsIcons.Search, \"搜索\""))
        assertTrue(editor.contains("ConfigEditorAction(ToolsFeatureIcons.ArrowUpDown, \"跳行\""))
        assertTrue(editor.contains("ht(\"跳转到行\")"))
        assertTrue(editor.contains("ToolsYaml.errorLine(it)"))
        assertTrue(editor.contains("ht(\"跳到第 %d 行\").fill(problemLine)"))
        // Back closes the search bar before it asks to leave.
        assertTrue(editor.contains("BackHandler { if (search) search = false else leave() }"))
        assertEquals(18, ToolsYaml.errorLine("yaml: line 18: did not find expected key"))
        assertEquals(7, ToolsYaml.errorLine("配置无效：第 7 行缩进错误"))
        assertEquals(null, ToolsYaml.errorLine("配置无效：proxy-groups 为空"))
        assertEquals("Go to line 18", translateHetuText("跳到第 %d 行", "en").format(18))
        assertEquals("К строке 18", translateHetuText("跳到第 %d 行", "ru").format(18))
    }

    /* U1 */
    @Test fun strategyListReservesRoomForTheFloatingButtons() {
        val panel = source("panel/PanelScreen.kt")
        assertTrue(panel.contains("bottom = listBottom"))
        assertTrue(panel.contains(".onSizeChanged { size -> fabStack = with(density) { size.height.toDp() } }"))
        assertTrue(panel.contains("bottomPad.coerceAtLeast(16.dp) + fabStack + 12.dp"))
    }

    /* U2 */
    @Test fun subscriptionLinksAreMaskedInTheConfigList() {
        assertEquals("https://sub.example.com/…", maskUrl("https://sub.example.com/api/v1/client/subscribe?token=0123456789abcdef"))
        assertFalse(maskUrl("https://sub.example.com/link?token=secret").contains("secret"))
        val list = source("tools/ToolsConfigScreen.kt")
        assertTrue(list.contains("maskUrl(item.url)"))
        assertFalse(list.contains("else item.url,"))
    }

    /* U7 */
    @Test fun panelLogFiltersUseTheLogViewerNamesAndOrder() {
        assertEquals(listOf("全部", "错误", "警告", "信息", "调试"), PanelLogFilter.entries.map { it.label })
        assertEquals(listOf(null, PanelLogLevel.Error, PanelLogLevel.Warn, PanelLogLevel.Info, PanelLogLevel.Debug), PanelLogFilter.entries.map { it.level })
        listOf("错误", "警告", "信息", "调试").forEach { assertTrue(it, translateHetuText(it, "en") != it) }
        // Row badges keep the core's level names.
        assertEquals("INFO", PanelLogLevel.Info.label)
    }

    /* U8 */
    @Test fun disconnectAllUsesTheUnlinkGlyphUnderAPlainHeading() {
        val lists = source("panel/PanelListTabs.kt")
        assertTrue(lists.contains("HomeIconButton(PanelIcons.Unlink, \"全部断开\", onCloseAll"))
        assertFalse(lists.contains("HomeIconButton(HomeIcons.X, \"全部断开\""))
        assertTrue(lists.contains("PanelSectionHeader(\"活动连接\""))
    }

    /* U9 */
    @Test fun rollingTextEndsWithAnEllipsis() {
        val kit = source("home/HomeMotionKit.kt")
        val body = kit.substringAfter("internal fun HomeRollingText(").substringBefore("private fun rollsUp")
        val texts = body.lines().filter { "Text(" in it && "maxLines = 1" in it }
        assertEquals(2, texts.size)
        assertTrue(texts.joinToString("\n"), texts.all { "overflow = TextOverflow.Ellipsis" in it })
    }
}
