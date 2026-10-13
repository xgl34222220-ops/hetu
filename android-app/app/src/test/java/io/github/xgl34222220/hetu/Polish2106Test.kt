package io.github.xgl34222220.hetu

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import io.github.xgl34222220.hetu.ui.translateHetuText
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 2026-10-13 second polish round (work/polish2-audit.md): feedback for per-item actions that cannot
 * run, the unchanged-log short cut, log-page action state, the empty trend chart, the network-test
 * interruption summary, friendly errors on the log/tools/panel paths and the new word-table rows.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class Polish2106Test {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private fun source(path: String) = File("src/main/java/io/github/xgl34222220/hetu/$path").readText()

    @Before fun clean() { app.getSharedPreferences("hetu", 0).edit().clear().commit() }

    /* F1 */
    @Test fun perItemActionsThatCannotRunExplainWhyInsteadOfReturningSilently() = runBlocking {
        val vm = HetuViewModel(app)
        val seen = mutableListOf<String>()
        val collector = launch(Dispatchers.Unconfined) { vm.messages.collect { seen += it } }
        try {
            assertFalse(vm.state.running)
            vm.updateProvider("机场订阅")
            vm.updateRuleSet("reject")
            vm.testNode("香港 01")
            vm.testAll()
        } finally { collector.cancel() }
        assertTrue(seen.toString(), seen.contains("订阅更新需要代理运行中"))
        assertTrue(seen.toString(), seen.contains("规则集更新需要代理运行中"))
        // testNode/testAll may first stop at the core-capability notice (no Clash controller); either way nothing starts.
        assertTrue(vm.providerTasks.isEmpty())
        assertTrue(vm.ruleSetTasks.isEmpty())
        assertTrue(vm.testingNodes.isEmpty())
        assertFalse(vm.testingAll)
        val model = source("app/HetuViewModel.kt")
        for (what in listOf("切换节点", "测速", "订阅更新", "规则集更新")) {
            assertTrue(what, model.contains("activeRuntimeRequest() ?: return toast(runtimeUnavailableText(\"$what\"))"))
        }
        assertFalse("No per-item entry returns silently any more",
            Regex("""fun (select|testNode|testGroup|testAll|updateProvider|updateRuleSet)\([^)]*\) \{[^}]*?activeRuntimeRequest\(\) \?: return\n""").containsMatchIn(model))
    }

    /* F2 */
    @Test fun unchangedQuietLogReadSkipsParsingButManualReadsAlwaysParse() {
        assertTrue(hxLogUnchanged(quiet = true, previous = "a\nb", raw = "a\nb"))
        assertFalse(hxLogUnchanged(quiet = true, previous = "a\nb", raw = "a\nb\nc"))
        assertFalse("First read always parses", hxLogUnchanged(quiet = true, previous = null, raw = ""))
        assertFalse("A manual refresh always parses", hxLogUnchanged(quiet = false, previous = "a", raw = "a"))
        val model = source("app/HetuViewModel.kt")
        assertTrue(model.contains("if (!hxLogUnchanged(quiet, lastRuntimeLog, raw)) {"))
    }

    /* V5 / V6 / F3 (log page) */
    @Test fun logPageActionsDoNotBlinkDuringLiveRefreshAndReadFailureOffersRetry() {
        assertTrue(hxLogFirstRead(loading = true, content = ""))
        assertFalse("A live re-tail keeps actions enabled", hxLogFirstRead(loading = true, content = "line"))
        assertFalse(hxLogFirstRead(loading = false, content = ""))
        val viewer = source("ProxyLogViewerActivity.kt")
        assertTrue(viewer.contains("enabled = selected != null && !hxLogFirstRead(loading, content)"))
        assertTrue(viewer.contains("enabled = canLoadEarlier && !hxLogFirstRead(loading, content)"))
        assertTrue(viewer.contains("files.isEmpty() && error.isNotBlank() -> item(key = \"read-error\")"))
        assertTrue(viewer.contains("hxErrorMessage(failure, \"日志导出失败\")"))
        assertTrue(viewer.contains("hxErrorMessage(failure, \"日志清空失败\")"))
        assertTrue(viewer.contains("error = hxErrorMessage(failure, \"日志读取失败\")"))
    }

    /* V2 */
    @Test fun emptyTrendChartSaysWhyInsteadOfStayingBlank() {
        assertEquals("已隐藏上行与下行", hxTrendEmptyLabel(seriesShown = 0, peak = 0f))
        assertEquals("已隐藏上行与下行", hxTrendEmptyLabel(seriesShown = 0, peak = 9f))
        assertEquals("等待流量数据", hxTrendEmptyLabel(seriesShown = 2, peak = 0f))
        assertNull(hxTrendEmptyLabel(seriesShown = 1, peak = 1f))
        assertTrue(source("panel/PanelOverviewTab.kt").contains("emptyLabel = hxTrendEmptyLabel(trend.size"))
    }

    /* F4 */
    @Test fun failedNetworkTestRunIsReportedWithWhateverFinished() {
        assertEquals("", hxNetTestSummary(0, 30, 0, failed = false, available = "可用", interrupted = "测试中断"))
        assertEquals("12/30 可用", hxNetTestSummary(12, 30, 20, failed = false, available = "可用", interrupted = "测试中断"))
        assertEquals("测试中断", hxNetTestSummary(0, 30, 0, failed = true, available = "可用", interrupted = "测试中断"))
        assertEquals("测试中断 · 3/30 可用", hxNetTestSummary(3, 30, 5, failed = true, available = "可用", interrupted = "测试中断"))
        val screen = source("app/NetworkTestScreen.kt")
        assertTrue(screen.contains("runFailed = true"))
        assertTrue(screen.contains("speed.error?.let { ht(it) }"))
    }

    /* V1 / V3 / V4 */
    @Test fun panelTabsFadeAtTheEdgesAndFailuresCanBeReadInFull() {
        val components = source("panel/PanelComponents.kt")
        val fade = components.indexOf(".panelHorizontalEdgeFade(scroll)")
        assertTrue("The fade masks the viewport, so it sits before horizontalScroll", fade in 0 until components.indexOf(".horizontalScroll(scroll)"))
        val lists = source("panel/PanelListTabs.kt")
        assertTrue(lists.contains("onClick = { showReason = !showReason })"))
        assertTrue(lists.contains("maxLines = if (showReason) Int.MAX_VALUE else 1"))
        // User-facing one-line texts on the panel tabs end with an ellipsis instead of a hard clip.
        for (path in listOf("panel/PanelListTabs.kt", "panel/PanelOverviewTab.kt")) {
            val clipped = source(path).lines().filter { "Text(" in it && "maxLines = 1" in it && "overflow" !in it }
            assertTrue("$path: $clipped", clipped.isEmpty())
        }
    }

    /* F3 (tools / panel defaults) */
    @Test fun rawJvmErrorsNoLongerReachToolsAndPanelMessages() {
        val tools = source("tools/ToolsFeatureAdapter.kt")
        assertFalse(tools.contains("error.message ?: \"导入失败\""))
        assertFalse(tools.contains("error.message ?: \"运行记录未修复"))
        val panel = source("panel/PanelAdapter.kt")
        assertFalse(Regex("""error\.message \?:""").containsMatchIn(panel))
        assertEquals("导入失败", hxErrorMessage(java.io.IOException(), "导入失败"))
    }

    /* word table */
    @Test fun polish2StringsTranslateToAllThreeLanguages() {
        val keys = listOf("测试中断", "等待流量数据", "已隐藏上行与下行", "查看完整原因", "切换节点需要代理运行中", "测速需要代理运行中",
            "订阅更新需要代理运行中", "规则集更新需要代理运行中", "请等待当前操作完成", "流量分布", "每 5 分钟", "每小时", "每 6 小时",
            "每天", "峰值 %s", "↓ 下行", "↑ 上行", "日志导出失败", "日志清空失败", "导入失败")
        for (key in keys) {
            assertTrue("$key en", translateHetuText(key, "en") != key)
            assertTrue("$key ru", translateHetuText(key, "ru") != key)
            assertTrue("$key zh-TW", translateHetuText(key, "zh-TW").isNotBlank())
            assertEquals(key, translateHetuText(key, "zh-CN"))
        }
        assertTrue(translateHetuText("峰值 %s", "en").contains("%s"))
        assertTrue(source("app/ActivityExtras.kt").contains("ht(\"流量分布\") + \" · \" + ht(historyBucketLabel(rank.range))"))
    }
}
