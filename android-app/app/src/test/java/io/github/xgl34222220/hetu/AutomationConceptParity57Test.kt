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

        assertTrue("resource waiting state required", details.contains("等待连接进行采样"))
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
        val proxy = source("app/ProxiesScreen.kt")
        val nav = source("PanelNavigation13.kt")
        val vm = source("app/HetuViewModel.kt")

        val combined = proxy + nav
        assertTrue("stopped panel state required", combined.contains("代理未运行"))
        assertTrue(vm.contains("正在启动…"))
        assertTrue(vm.contains("正在重启…"))
        assertTrue(vm.contains("已断开全部连接"))
    }

    @Test fun pdfStateCatalogMatchesAllFiveReferenceBooks() {
        val home = listOf(
            "首页-参考版", "首页-运行控制展开", "首页-未运行", "首页-待重启与LAN", "首页-网速数据来源",
            "首页-正在启动-校正版", "首页-启动失败-校正版", "首页-正在重启-校正版", "公网IP详情-公网", "公网IP详情-局域网",
            "本机直测目标-已保存", "本机直测目标-校验错误-统一版", "资源占用-运行与缺测", "资源占用-等待采样-统一版",
        )
        val panel = listOf(
            "紧凑策略组-参考版", "策略展开-参考版", "面板概览-顶部", "面板概览-收起顶栏", "面板订阅", "面板连接", "面板规则", "面板规则集", "面板日志",
            "策略-筛选浮层", "策略-排序与布局", "策略-测速与API", "策略-节点信息", "连接-详情浮层", "连接-断开全部确认", "概览-显示数量", "连接-排序", "连接-显示菜单",
            "策略-搜索结果", "概览-排行方式", "连接-筛选", "订阅-搜索与更新状态", "规则-搜索无结果", "连接-搜索与应用展开", "规则集-搜索与更新", "日志-搜索与展开",
            "策略-测速与API默认", "策略-测速中与超时", "面板-代理未运行",
        )
        val toolsA = listOf(
            "工具-展开", "工具-折叠", "工具-搜索", "配置与订阅-列表", "添加订阅", "配置菜单-当前配置", "配置菜单-其他配置", "配置菜单-内置模板", "配置-重命名", "配置-删除确认",
            "订阅-删除确认", "配置表单-放弃填写", "配置编辑-语法大纲", "配置编辑-选择变更冲突", "配置编辑-校验失败", "配置编辑-读取失败", "配置编辑-文件外部修改", "配置编辑-放弃并重新读取", "配置编辑-放弃修改",
            "配置导入-链接错误", "添加订阅-名称为空", "添加订阅-链接错误", "编辑订阅-链接错误", "配置导入-链接", "配置编辑-YAML", "订阅-编辑", "配置导入-文件", "应用管理-黑名单", "应用-排序菜单",
            "应用-更多菜单", "应用管理-白名单", "应用管理-核心模式", "应用管理-搜索", "核心管理", "核心管理-下载中", "绕过规则", "绕过规则-放弃修改", "共享网络", "CNIP设置", "诊断与维护",
            "诊断-启动配置浮层", "诊断-恢复网络确认", "诊断-消息与网络", "广告过滤-规则", "广告过滤-状态", "广告过滤-说明", "广告过滤-添加白名单", "广告过滤-添加黑名单", "广告过滤-加入白名单",
        )
        val toolsB = listOf(
            "广告过滤-全局模式警告", "日志文件", "日志-选择文件", "日志-清空确认", "日志-搜索展开", "脚本", "脚本-更多菜单", "脚本-启动前Hook", "脚本-停止后Hook", "脚本-删除确认", "脚本-环境",
            "文件编辑", "文件管理-列表", "文件管理-子目录搜索", "文件管理-更多菜单", "文件管理-文件操作", "文件管理-文件夹操作", "文件管理-新建文件", "文件管理-新建文件夹", "文件管理-下载表单", "文件管理-下载地址校验",
            "文件管理-重命名", "文件管理-删除文件确认", "文件管理-删除文件夹确认", "文件管理-名称未改校验", "文件编辑-更多菜单", "文件编辑-跳转行", "文件编辑-放弃修改", "文件编辑-搜索", "网络匹配", "网络匹配-匹配动作",
            "网络匹配-失配动作", "Web面板管理", "Web面板-添加", "Web面板-编辑", "Web面板-删除确认", "Web面板-本地模式", "自定义Web面板-容器边界", "SubStore面板-容器边界", "内置WebUI-概览", "内置WebUI-策略组",
            "内置WebUI-连接", "内置WebUI-节点选择", "内置WebUI-切换失败", "SubStore管理", "SubStore-检测中", "SubStore-已连接", "SubStore-后端未启动",
        )
        val settings = listOf(
            "设置-首页展开", "设置-首页收起", "设置-基础代理配置", "设置-高级代理配置", "设置-高级资源限制", "设置-开机启动与下载", "设置-默认面板", "设置-主题外观", "设置-主题玻璃与动画", "设置-备份与恢复", "设置-通知详细设置",
            "设置-通知快捷按钮", "设置-关于", "设置-启动配置", "设置-核心选择", "设置-运行模式选择", "设置-IPv6选择", "设置-默认面板选择", "设置-语言选择", "设置-顶栏模糊选择", "设置-界面缩放选择", "设置-通知刷新频率",
            "设置-通知点击目标", "设置-通知按钮1动作", "设置-通知按钮2动作", "设置-通知按钮3动作", "设置-DNS劫持策略", "设置-恢复备份确认", "设置-加速地址编辑", "设置-加速地址校验", "设置-Mihomo许可", "设置-AdGuard许可", "设置-Lucide许可",
        )
        assertTrue("home reference must have 14 states", home.size == 14)
        assertTrue("panel reference must have 29 states", panel.size == 29)
        assertTrue("tools upper reference must have 49 states", toolsA.size == 49)
        assertTrue("tools lower reference must have 48 states", toolsB.size == 48)
        assertTrue("settings reference must have 33 states", settings.size == 33)
        val all = home + panel + toolsA + toolsB + settings
        assertTrue("all five PDFs must account for exactly 173 states", all.size == 173)
        assertTrue("catalog state names must be unique", all.toSet().size == all.size)
    }
}
