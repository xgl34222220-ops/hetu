package io.github.xgl34222220.hetu

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Structural gates for the 97 tool concept states. */
class AutomationToolsConceptParityTest {
    private fun source(path: String) =
        File("src/main/java/io/github/xgl34222220/hetu/$path").readText()

    @Test fun toolLandingAndConfigStatesAreReachable() {
        val landing = source("app/PanelToolsScreens.kt")
        val config = source("app/ConfigScreens.kt") + source("ProxySubscriptionActivity.kt")

        listOf("工具", "搜索工具", "应用管理", "共享网络", "网络匹配", "绕过规则", "诊断").forEach {
            assertTrue("missing tool landing item $it", landing.contains(it))
        }
        listOf(
            "添加订阅", "重命名", "删除", "导入", "编辑订阅",
            "语法大纲", "YAML", "链接", "配置",
        ).forEach { assertTrue("missing config concept state $it", config.contains(it)) }
        listOf("放弃", "重新读取", "校验", "读取失败", "其他位置修改").forEach {
            assertTrue("missing config editor recovery state $it", config.contains(it, ignoreCase = true))
        }
    }

    @Test fun appCoreAndNetworkToolsCoverConceptStates() {
        val apps = source("ProxyAppSelectionActivity.kt") + source("app/MiscScreens.kt")
        val cores = source("ProxyCoreActivity.kt") + source("app/CoreConceptComponents.kt") + source("ProxyReferenceExtras.kt")
        val tools = source("app/ToolScreens.kt")

        listOf("黑名单", "白名单", "核心模式", "搜索", "排序").forEach {
            assertTrue("missing app management state $it", apps.contains(it))
        }
        listOf("Mihomo", "下载", "导入").forEach {
            assertTrue("missing core management state $it", cores.contains(it))
        }
        listOf("绕过规则", "共享网络", "CNIP", "诊断", "恢复网络").forEach {
            assertTrue("missing network tool state $it", tools.contains(it))
        }
    }

    @Test fun adblockConceptStatesHaveProductionControls() {
        val a = source("app/AdblockScreen.kt") + source("ProxyAdblockChainActivity.kt")
        listOf("广告", "白名单", "黑名单", "规则", "实际拦截", "全局").forEach {
            assertTrue("missing adblock concept state $it", a.contains(it))
        }
        listOf("添加", "更新", "拦截").forEach {
            assertTrue("missing adblock action $it", a.contains(it))
        }
    }

    @Test fun logAndScriptConceptStatesHaveProductionControls() {
        val logs = source("ProxyLogViewerActivity.kt")
        val scripts = source("ProxyScriptsActivity.kt")
        listOf("日志", "清空", "搜索", "选择").forEach {
            assertTrue("missing log state $it", logs.contains(it))
        }
        listOf("脚本", "启动前", "停止后", "环境", "删除", "导入").forEach {
            assertTrue("missing script state $it", scripts.contains(it))
        }
    }

    @Test fun fileWorkbenchConceptStatesHaveProductionControls() {
        val files = source("app/ToolScreens.kt") + source("RuntimeEditorScreen.kt")
        listOf(
            "文件管理", "新建文件", "新建文件夹", "下载", "重命名",
            "删除", "搜索", "跳转", "放弃",
        ).forEach { assertTrue("missing file workbench state $it", files.contains(it)) }
    }

    @Test fun networkWebPanelAndSubStoreStatesHaveProductionControls() {
        val network = source("app/ToolScreens.kt") + source("ProxyNetworkAutomationActivity.kt")
        val panels = source("ProxyReferenceExtras.kt")
        val sub = source("ProxySubStoreActivity.kt")

        listOf("网络匹配", "匹配", "失配").forEach {
            assertTrue("missing network match state $it", network.contains(it))
        }
        listOf("Web", "添加", "编辑", "删除", "本地").forEach {
            assertTrue("missing web panel state $it", panels.contains(it))
        }
        listOf("Sub-Store", "检测中", "已连接", "后端未启动").forEach {
            assertTrue("missing Sub-Store state $it", sub.contains(it))
        }
    }
}
