package io.github.xgl34222220.hetu

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source-level gates for the Home and Panel concept states that CI reconstructs.
 * These tests do not perform root/network operations.
 */
class AutomationConceptParity57Test {
    private fun source(path: String) =
        File("src/main/java/io/github/xgl34222220/hetu/$path").readText()

    @Test fun homeConceptStatesHaveProductionControls() {
        val home = source("app/HomeScreen.kt")
        val root = source("app/HetuActivity.kt")
        val latency = source("ProxyLatencyTargetsActivity.kt")
        val details = source("app/HomeDetailsScreen.kt")

        listOf(
            "网速数据来源",
            "正在启动",
            "正在重启",
            "本机直测",
            "\"LAN\"",
            "\"WAN\"",
            "资源占用",
        ).forEach { assertTrue("missing home concept control $it", home.contains(it)) }

        assertTrue("resource waiting state required", details.contains("等待采样"))
        assertTrue("startup error sheet required", root.contains("启动失败"))
        assertTrue("direct target screen required", latency.contains("本机直测目标"))
        assertTrue("direct target input required", latency.contains("目标"))
    }

    @Test fun panelConceptMenusHaveProductionControls() {
        val overview = source("app/PanelOverviewScreen.kt")
        val proxy = source("app/ProxiesScreen.kt")
        val conn = source("app/ConnectionsScreen.kt")
        val rules = source("app/RulesScreen.kt")
        val filter = source("app/PanelStrategyFilter51.kt")
        val layout = source("app/PanelStrategyLayout51.kt")
        val info = source("app/PanelStrategyNodeInfo51.kt")

        listOf("排行方式", "显示数量").forEach { assertTrue(overview.contains(it)) }
        listOf("搜索", "筛选").forEach { assertTrue(proxy.contains(it)) }
        listOf("连接筛选", "断开全部连接？", "断开全部").forEach { assertTrue(conn.contains(it)) }
        listOf("搜索规则集", "搜索订阅", "搜索规则内容或策略", "更新失败").forEach {
            assertTrue("missing rules state $it", rules.contains(it))
        }
        assertTrue(filter.contains("策略筛选"))
        assertTrue(layout.contains("排序与布局"))
        assertTrue(layout.contains("测速与 API"))
        assertTrue(info.contains("节点信息"))
    }

    @Test fun stoppedPanelAndRuntimeStatesAreExplicit() {
        val panel = source("app/PanelScreen.kt")
        val proxy = source("app/ProxiesScreen.kt")
        val nav = source("PanelNavigation13.kt")
        val vm = source("app/HetuViewModel.kt")

        val combined = panel + proxy + nav
        assertTrue("stopped panel state required", combined.contains("代理未运行"))
        assertTrue(vm.contains("正在启动…"))
        assertTrue(vm.contains("正在重启…"))
        assertTrue(vm.contains("已断开全部连接"))
    }
}
