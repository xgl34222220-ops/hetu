package io.github.xgl34222220.hetu.panel

/** One prototype page state: data + view + floating layer. */
internal data class PanelScene(
    val id: String,
    val title: String,
    val data: PanelData,
    val view: PanelViewState,
    val overlay: PanelOverlay? = null,
    /** Lazy-list item to start at, for the “收起顶栏” states that are scrolled down. */
    val scrollToItem: Int = 0,
)

/** Sample data mirroring the prototype's sample data, and the 29 states of «02 面板» (B01–B29). */
internal object PanelSamples {
    private const val KB = 1024L
    private const val MB = 1024L * KB
    private const val GB = 1024L * MB

    private fun region(zh: String, count: Int): List<PanelNode> =
        (1..count).map { PanelNode("$zh ${it.toString().padStart(2, '0')}", "VLESS", udp = true, provider = "日常订阅") }

    private val hk = region("香港", 8)
    private val tw = region("台湾", 6)
    private val jp = region("日本", 6)
    private val sg = region("新加坡", 6)
    private val kr = region("韩国", 4)
    private val us = region("美国", 8)
    private val extra = listOf("英国", "德国", "法国", "澳大利亚").map { PanelNode("$it 01", "VLESS", udp = true, provider = "日常订阅") }
    private val all = (hk + tw + jp + sg + kr + us + extra).associateBy { it.name }
    private fun pick(vararg names: String): List<PanelNode> = names.map { all.getValue(it) }
    private fun policy(name: String) = PanelNode(name, "策略组", udp = true, provider = "配置", kind = PanelNodeKind.Group)
    private val direct = PanelNode("DIRECT", "直连", udp = true, provider = "内置", kind = PanelNodeKind.Direct)

    private val main = pick("香港 01", "香港 02", "日本 01", "日本 02", "新加坡 01", "台湾 01", "韩国 01", "美国 01", "英国 01", "德国 01", "法国 01", "澳大利亚 01")
    private val policies = listOf("节点选择", "手动选择", "故障转移", "香港节点", "日本节点", "美国节点").map(::policy) + direct

    val groups = listOf(
        PanelGroup("节点选择", "URLTest", main, "香港 01"),
        PanelGroup("手动选择", "Selector", main, "日本 01"),
        PanelGroup("故障转移", "Fallback", pick("香港 02", "香港 01", "日本 01", "新加坡 01", "台湾 01", "美国 01"), "香港 02"),
        PanelGroup("香港节点", "URLTest", hk, "香港 01"),
        PanelGroup("台湾节点", "URLTest", tw, "台湾 01"),
        PanelGroup("日本节点", "URLTest", jp, "日本 01"),
        PanelGroup("新加坡节点", "URLTest", sg, "新加坡 01"),
        PanelGroup("韩国节点", "URLTest", kr, "韩国 01"),
        PanelGroup("美国节点", "URLTest", us, "美国 01"),
        PanelGroup("AI 稳定", "Fallback", pick("新加坡 01", "日本 01", "美国 01", "台湾 01", "韩国 01", "日本 02", "英国 01"), "新加坡 01"),
        PanelGroup("AI 平台", "Selector", listOf(policy("AI 稳定"), policy("节点选择"), policy("美国节点"), direct), "AI 稳定"),
        PanelGroup("YouTube", "Selector", policies, "节点选择"),
        PanelGroup("Google", "Selector", policies, "节点选择"),
        PanelGroup("TikTok", "Selector", policies, "日本节点"),
        PanelGroup("GLOBAL", "Selector", listOf(policy("节点选择"), policy("手动选择"), direct), "节点选择"),
        PanelGroup("兜底隐藏", "Selector", listOf(policy("节点选择"), direct), "节点选择", hidden = true),
    )

    val delays: Map<String, PanelDelay> = buildMap {
        fun fill(nodes: List<PanelNode>, base: Long) = nodes.forEachIndexed { i, n -> put(n.name, PanelDelay.Ms(base + i * 3L)) }
        fill(hk, 28); fill(tw, 46); fill(jp, 42); fill(sg, 36); fill(kr, 55); fill(us, 128)
        put("英国 01", PanelDelay.Ms(150)); put("德国 01", PanelDelay.Ms(162)); put("法国 01", PanelDelay.Ms(171)); put("澳大利亚 01", PanelDelay.Ms(188))
    }

    val connections = listOf(
        PanelConnection("1", "api.example.com:443", "09:40:20", "TCP", "TProxy", "FQDN", "Chrome", "com.android.chrome", listOf("节点选择", "香港 01"), "RuleSet · AI", 128 * KB, (2.8 * MB).toLong(), 2 * MB, 30 * MB),
        PanelConnection("2", "203.0.113.20:443", "09:40:35", "TCP", "Inner", "IPv4", "", "", listOf("DIRECT"), "IPCIDR · 203.0.113.0/24", 0, 0, 11 * KB, 28 * KB),
        PanelConnection("3", "cdn.example.net:443", "09:40:28", "TCP", "TProxy", "FQDN", "哔哩哔哩", "tv.danmaku.bili", listOf("节点选择", "日本 01"), "RuleSet · BiliBili", 12 * KB, 0, 310 * KB, 9 * KB),
        PanelConnection("4", "192.0.2.53:53", "09:40:16", "UDP", "Inner", "IPv4", "", "", listOf("DIRECT"), "RuleSet · Private", 0, 0, 4 * KB, 12 * KB),
        PanelConnection("5", "updates.example.org:443", "09:40:12", "TCP", "TProxy", "FQDN", "系统服务", "android", listOf("故障转移", "香港 02"), "DomainSuffix · example.org", 6 * KB, 48 * KB, 220 * KB, (1.2 * MB).toLong()),
        PanelConnection("6", "cdn.example.net:443", "09:40:28", "TCP", "TProxy", "FQDN", "Chrome", "com.android.chrome", listOf("节点选择", "香港 01"), "Match", 12 * KB, 0, 310 * KB, 9 * KB),
        PanelConnection("7", "updates.example.org:443", "09:40:12", "TCP", "TProxy", "FQDN", "Chrome", "com.android.chrome", listOf("节点选择", "香港 01"), "DomainSuffix · example.org", 6 * KB, 48 * KB, 220 * KB, (1.2 * MB).toLong()),
        PanelConnection("8", "mtalk.example.com:5228", "09:39:51", "TCP", "TProxy", "FQDN", "Telegram", "org.telegram.messenger", listOf("手动选择", "日本 01"), "RuleSet · Telegram", 86 * KB, 512 * KB, (1.1 * MB).toLong(), 14 * MB),
    )

    private val uploadTrend = listOf(20, 22, 30, 60, 96, 120, 128, 110, 80, 52, 40, 34, 30, 46, 58, 50, 36, 30, 28, 26, 30, 44, 52, 40, 30, 26, 24, 26, 30, 28).map { it * KB / 5f }
    private val downloadTrend = listOf(8, 10, 18, 42, 70, 92, 100, 90, 66, 44, 32, 26, 24, 36, 46, 40, 30, 26, 24, 22, 26, 36, 44, 34, 26, 22, 20, 22, 24, 22).map { it * 28f * KB }

    val overview = PanelOverview(
        strategyCount = 12, ruleCount = 120, connectionCount = 18,
        subscription = PanelOverviewSubscription(20 * GB, 100 * GB, "2027-12-31", 2, 24),
        uploadBytesPerSecond = 128 * KB, downloadBytesPerSecond = (2.8 * MB).toLong(),
        uploadTotalBytes = 624 * KB, downloadTotalBytes = 165 * MB,
        uploadTrend = uploadTrend, downloadTrend = downloadTrend,
        ranks = listOf(
            PanelRank("Chrome", "com.android.chrome", (2.8 * MB).toLong(), 128 * KB, 8, 32 * MB),
            PanelRank("Telegram", "org.telegram.messenger", 512 * KB, 86 * KB, 4, 15 * MB),
            PanelRank("哔哩哔哩", "tv.danmaku.bili", 310 * KB, 52 * KB, 3, 96 * MB),
            PanelRank("系统服务", "android", 96 * KB, 24 * KB, 2, (1.4 * MB).toLong()),
            PanelRank("其他应用", "", 48 * KB, 12 * KB, 1, 620 * KB),
            PanelRank("微信", "com.tencent.mm", 22 * KB, 8 * KB, 1, 410 * KB),
            PanelRank("YouTube", "com.google.android.youtube", 0, 0, 1, 88 * MB),
        ),
    )

    val subscriptions = listOf(
        PanelSubscription("日常订阅", "2027-12-31", "10-01 20:30", 2 * GB, 18 * GB, 100 * GB),
        PanelSubscription("备用订阅", "2027-06-30", "10-01 20:30", 1 * GB, 4 * GB, 50 * GB),
        PanelSubscription("测试订阅", "2027-03-31", "10-01 20:30", 1 * GB, 4 * GB, 20 * GB),
    )

    val rules = listOf(
        PanelRule("DomainSuffix", "ads.example.com", "REJECT"), PanelRule("DomainSuffix", "tracker.example.net", "REJECT"),
        PanelRule("DomainSuffix", "example.cn", "DIRECT"), PanelRule("DomainSuffix", "example.org", "节点选择"),
        PanelRule("IPCIDR", "192.0.2.0/24", "DIRECT"), PanelRule("RuleSet", "Private", "DIRECT"), PanelRule("RuleSet", "CN_IP", "DIRECT"),
        PanelRule("RuleSet", "AI", "AI 稳定"), PanelRule("RuleSet", "BiliBili", "DIRECT"), PanelRule("RuleSet", "Apple", "节点选择"),
        PanelRule("Match", "所有其他流量", "节点选择"),
    )

    val ruleSets = listOf(
        PanelRuleSet("AI", 187, "Domain", "MRS", updatedAt = "1 小时前"), PanelRuleSet("Apple", 1792, "Domain", "MRS", updatedAt = "1 小时前"),
        PanelRuleSet("Bank_CN", 55, "Domain", "MRS", updatedAt = "1 小时前"), PanelRuleSet("BiliBili", 53, "Domain", "MRS", updatedAt = "1 小时前"),
        PanelRuleSet("CN_IP", 9612, "IP-CIDR", "MRS", updatedAt = "1 小时前"), PanelRuleSet("CN_域", 111224, "Domain", "MRS", updatedAt = "1 小时前"),
        PanelRuleSet("Private", 36, "Classical", "YAML", updatedAt = "1 小时前"),
    )

    val logs = listOf(
        PanelLogEntry(0, PanelLogLevel.Info, "2026/10/01 09:41:03", "[TCP] 192.0.2.10:52300 --> api.example.com:443\nmatch RuleSet(AI)\nusing 节点选择 -> 香港 01\noutbound VLESS · TLS\nelapsed 28 ms"),
        PanelLogEntry(1, PanelLogLevel.Info, "2026/10/01 09:41:01", "[UDP] 192.0.2.10:53012 --> 192.0.2.53:53\nmatch RuleSet(Private) using DIRECT"),
        PanelLogEntry(2, PanelLogLevel.Warn, "2026/10/01 09:40:58", "Health check timeout: 日本 02\nRetrying in 30 seconds."),
        PanelLogEntry(3, PanelLogLevel.Error, "2026/10/01 09:40:41", "dial 美国 03 failed: i/o timeout\nfallback to 美国 01"),
        PanelLogEntry(4, PanelLogLevel.Info, "2026/10/01 09:40:20", "[TCP] 192.0.2.10:52288 --> cdn.example.net:443\nmatch RuleSet(BiliBili) using 节点选择 -> 日本 01"),
    )

    val running = PanelData(
        status = PanelStatus.Running, groups = groups, delays = delays, overview = overview, subscriptions = subscriptions,
        connections = connections, connectionTotal = 18, rules = rules, ruleTotal = 120, ruleSets = ruleSets, logs = logs,
    )
    val notRunning = PanelData(status = PanelStatus.NotRunning)

    private fun view(tab: PanelTab) = PanelViewState(tab = tab)
    private fun search(tab: PanelTab, query: String) = PanelViewState(tab = tab, searching = true, query = query)
    private val expanded = view(PanelTab.Groups).copy(expandedGroups = listOf("节点选择"))
    private val apiConfigured = PanelApiSettings(customTestUrl = true, history = true, externalApi = true)

    val scenes: List<PanelScene> = listOf(
        PanelScene("B01", "策略 · 紧凑策略组", running, view(PanelTab.Groups)),
        PanelScene("B02", "策略 · 展开节点", running, expanded),
        PanelScene("B03", "概览 · 顶部", running, view(PanelTab.Overview)),
        PanelScene("B04", "概览 · 趋势与排行（收起顶栏）", running, view(PanelTab.Overview), scrollToItem = 6),
        PanelScene("B05", "订阅", running, view(PanelTab.Subscriptions)),
        PanelScene("B06", "连接", running, view(PanelTab.Connections)),
        PanelScene("B07", "规则", running, view(PanelTab.Rules)),
        PanelScene("B08", "规则集", running, view(PanelTab.RuleSets)),
        PanelScene("B09", "日志", running, view(PanelTab.Logs)),
        PanelScene("B10", "策略 · 筛选浮层", running, expanded, PanelOverlay.GroupFilterMenu),
        PanelScene("B11", "策略 · 排序与布局", running, expanded, PanelOverlay.LayoutSheet),
        PanelScene("B12", "策略 · 测速与 API（已配置）", running, view(PanelTab.Groups).copy(api = apiConfigured), PanelOverlay.ApiSheet),
        PanelScene("B13", "策略 · 节点信息", running, expanded, PanelOverlay.NodeInfo("香港 01")),
        PanelScene("B14", "连接 · 详情浮层", running, view(PanelTab.Connections), PanelOverlay.ConnectionDetail("1")),
        PanelScene("B15", "连接 · 断开全部确认", running, view(PanelTab.Connections), PanelOverlay.CloseAllDialog),
        PanelScene("B16", "概览 · 显示数量", running, view(PanelTab.Overview), PanelOverlay.RankCountMenu, scrollToItem = 6),
        PanelScene("B17", "连接 · 排序", running, view(PanelTab.Connections), PanelOverlay.ConnSortMenu),
        PanelScene("B18", "连接 · 显示菜单", running, view(PanelTab.Connections).copy(groupByApp = true, openApps = setOf("Chrome")), PanelOverlay.ConnMoreMenu),
        PanelScene("B19", "策略 · 搜索结果", running, search(PanelTab.Groups, "香港")),
        PanelScene("B20", "概览 · 排行方式", running, view(PanelTab.Overview), PanelOverlay.RankModeMenu, scrollToItem = 6),
        PanelScene("B21", "连接 · 筛选", running, view(PanelTab.Connections), PanelOverlay.ConnFilterMenu),
        PanelScene(
            "B22", "订阅 · 搜索与更新状态",
            running.copy(subscriptions = listOf(
                subscriptions[0].copy(update = PanelUpdate.Updating),
                subscriptions[1].copy(name = "日常备用", update = PanelUpdate.Failed("连接超时")),
                subscriptions[2],
            )),
            search(PanelTab.Subscriptions, "日常"),
        ),
        PanelScene("B23", "规则 · 搜索无结果", running, search(PanelTab.Rules, "nonexistent.example")),
        PanelScene("B24", "连接 · 搜索与应用展开", running, search(PanelTab.Connections, "Chrome").copy(groupByApp = true, openApps = setOf("Chrome"))),
        PanelScene(
            "B25", "规则集 · 搜索与更新",
            running.copy(ruleSets = ruleSets.map {
                when (it.name) {
                    "CN_IP" -> it.copy(update = PanelUpdate.Updating)
                    "CN_域" -> it.copy(update = PanelUpdate.Failed("下载超时"))
                    else -> it
                }
            }),
            search(PanelTab.RuleSets, "CN"),
        ),
        PanelScene("B26", "日志 · 搜索与展开", running, search(PanelTab.Logs, "api.example").copy(logFilter = PanelLogFilter.Info, openLogs = setOf(0))),
        PanelScene("B27", "策略 · 测速与 API（默认）", running, view(PanelTab.Groups), PanelOverlay.ApiSheet),
        PanelScene(
            "B28", "策略 · 测速中与超时",
            running.copy(delays = delays + mapOf(
                "香港 02" to PanelDelay.Testing, "台湾 01" to PanelDelay.Testing,
                "日本 02" to PanelDelay.Timeout, "韩国 01" to PanelDelay.Unknown,
            )),
            expanded,
        ),
        PanelScene("B29", "面板 · 代理未运行", notRunning, view(PanelTab.Groups)),
    )

    fun scene(id: String): PanelScene = scenes.first { it.id == id }
}
