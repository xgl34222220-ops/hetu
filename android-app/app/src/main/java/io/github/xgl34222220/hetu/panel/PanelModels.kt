package io.github.xgl34222220.hetu.panel

import io.github.xgl34222220.hetu.home.HomeRegions
import java.util.Locale

/* ------------------------------------------------------------------ */
/*  Data shown by the panel. Pure Kotlin: no Compose or Android types.  */
/* ------------------------------------------------------------------ */

/** The seven sub-tabs, in the order of the prototype's tab strip. */
internal enum class PanelTab(val id: String, val label: String, val searchHint: String?) {
    Overview("overview", "概览", null),
    Groups("groups", "策略", "搜索策略或当前节点"),
    Subscriptions("subs", "订阅", "搜索订阅"),
    Connections("conns", "连接", "搜索主机或应用"),
    Rules("rules", "规则", "搜索规则"),
    RuleSets("rulesets", "规则集", "搜索规则集"),
    Logs("logs", "日志", "搜索日志");

    val searchable: Boolean get() = searchHint != null

    companion object {
        fun fromId(id: String?): PanelTab = entries.firstOrNull { it.id == id } ?: Groups
    }
}

/** Whether the panel has live data at all. Every tab shows the same empty state when it does not. */
internal sealed interface PanelStatus {
    data object Running : PanelStatus
    data object NotRunning : PanelStatus

    /** A start is in flight: the empty state's button shows a spinner and is disabled. */
    data object Starting : PanelStatus
}

/** Latency of one node. */
internal sealed interface PanelDelay {
    data object Unknown : PanelDelay
    data object Testing : PanelDelay
    data object Timeout : PanelDelay
    data object Failed : PanelDelay
    data class Ms(val value: Long) : PanelDelay
}

internal fun panelDelayOf(ms: Long?): PanelDelay = when {
    ms == null -> PanelDelay.Unknown
    ms > 0L -> PanelDelay.Ms(ms)
    ms == -2L -> PanelDelay.Failed
    else -> PanelDelay.Timeout
}

internal enum class PanelNodeKind { Proxy, Group, Direct }

internal data class PanelNode(
    val name: String,
    val protocol: String = "",
    val udp: Boolean = false,
    val provider: String = "",
    val kind: PanelNodeKind = PanelNodeKind.Proxy,
) {
    val regionCode: String get() = when (kind) {
        PanelNodeKind.Group -> "组"
        PanelNodeKind.Direct -> "直"
        PanelNodeKind.Proxy -> PanelRegions.codeOf(name)
    }
    val meta: String get() = listOf(protocol, if (udp) "UDP" else "").filter { it.isNotBlank() }.joinToString(" · ")
}

internal data class PanelGroup(
    val name: String,
    val type: String,
    val nodes: List<PanelNode>,
    val now: String,
    val hidden: Boolean = false,
    val availableCount: Int = nodes.size,
) {
    val isGlobal: Boolean get() = name == "GLOBAL"
    val summary: String get() = "$type · $availableCount/${nodes.size}"
}

internal data class PanelOverviewSubscription(
    val usedBytes: Long,
    val totalBytes: Long,
    val expire: String?,
    val subscriptionCount: Int,
    val nodeCount: Int,
) {
    val remainingBytes: Long get() = (totalBytes - usedBytes).coerceAtLeast(0L)
    val usedFraction: Float? get() = if (totalBytes <= 0L) null else (usedBytes.coerceAtLeast(0L).toDouble() / totalBytes).coerceIn(0.0, 1.0).toFloat()
}

/** One application in 实时排行. */
internal data class PanelRank(
    val app: String,
    val packageName: String = "",
    val downloadBytesPerSecond: Long = 0L,
    val uploadBytesPerSecond: Long = 0L,
    val connections: Int = 0,
    val totalBytes: Long = 0L,
)

internal data class PanelOverview(
    val strategyCount: Int = 0,
    val ruleCount: Int = 0,
    val connectionCount: Int = 0,
    val subscription: PanelOverviewSubscription? = null,
    val uploadBytesPerSecond: Long = 0L,
    val downloadBytesPerSecond: Long = 0L,
    val uploadTotalBytes: Long = 0L,
    val downloadTotalBytes: Long = 0L,
    /** Last 60 s, oldest first, bytes per second. */
    val uploadTrend: List<Float> = emptyList(),
    val downloadTrend: List<Float> = emptyList(),
    val ranks: List<PanelRank> = emptyList(),
)

/** Refresh state of a subscription or a rule set. */
internal sealed interface PanelUpdate {
    data object Idle : PanelUpdate
    data object Updating : PanelUpdate
    data class Failed(val reason: String) : PanelUpdate
}

internal data class PanelSubscription(
    val name: String,
    val expire: String? = null,
    val updatedAt: String? = null,
    val uploadBytes: Long = 0L,
    val downloadBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val update: PanelUpdate = PanelUpdate.Idle,
) {
    val usedBytes: Long get() = (uploadBytes + downloadBytes).coerceAtLeast(0L)
    val remainingBytes: Long get() = (totalBytes - usedBytes).coerceAtLeast(0L)
    val usedFraction: Float? get() = if (totalBytes <= 0L) null else (usedBytes.toDouble() / totalBytes).coerceIn(0.0, 1.0).toFloat()
    val remainingPercent: Int? get() = usedFraction?.let { Math.round((1f - it) * 100f).coerceIn(0, 100) }
}

internal data class PanelConnection(
    val id: String,
    val host: String,
    val time: String? = null,
    val network: String = "",
    val inbound: String = "",
    val kind: String = "",
    val app: String = "",
    val packageName: String = "",
    val chain: List<String> = emptyList(),
    val rule: String = "",
    val uploadBytesPerSecond: Long = 0L,
    val downloadBytesPerSecond: Long = 0L,
    val uploadTotalBytes: Long = 0L,
    val downloadTotalBytes: Long = 0L,
    /** [time] as shown on the card (clock time); [time] itself stays sortable. */
    val timeLabel: String? = time,
) {
    /** [chain] runs from the top group to the node that carries the traffic, e.g. 节点选择 → 香港 01. */
    val isDirect: Boolean get() = chain.isEmpty() || chain.last().equals("DIRECT", ignoreCase = true)
    val meta: String get() = listOf(network, inbound, kind).filter { it.isNotBlank() }.joinToString(" · ")
    val chainText: String get() = chain.joinToString(" → ")
    /** Bucket used by “按应用分组”. */
    val appKey: String get() = app.ifBlank { PanelLogic.UnattributedApp }
}

internal data class PanelRule(val type: String, val payload: String, val policy: String)

internal data class PanelRuleSet(
    val name: String,
    val ruleCount: Int,
    val behavior: String,
    val format: String,
    val vehicle: String = "HTTP",
    val updatedAt: String? = null,
    val update: PanelUpdate = PanelUpdate.Idle,
) {
    val meta: String get() = listOf(behavior, format, vehicle).filter { it.isNotBlank() }.joinToString(" / ")
}

internal enum class PanelLogLevel(val label: String) { Debug("DEBUG"), Info("INFO"), Warn("WARN"), Error("ERROR") }

internal data class PanelLogEntry(val id: Int, val level: PanelLogLevel, val time: String, val message: String) {
    /** More than two lines: the card is clamped and gets a 展开 / 收起 footer. */
    val foldable: Boolean get() = message.length > 60 || message.count { it == '\n' } >= 2
}

/** Everything the panel renders that comes from the core. */
internal data class PanelData(
    val status: PanelStatus = PanelStatus.NotRunning,
    /** True while the traffic mode is “全局”; decides whether GLOBAL is listed. */
    val globalMode: Boolean = false,
    val groups: List<PanelGroup> = emptyList(),
    /** Latency by leaf node name. Missing = unknown. */
    val delays: Map<String, PanelDelay> = emptyMap(),
    val overview: PanelOverview = PanelOverview(),
    val subscriptions: List<PanelSubscription> = emptyList(),
    val connections: List<PanelConnection> = emptyList(),
    /** Count reported by the core; may exceed [connections] when the list is truncated. */
    val connectionTotal: Int = connections.size,
    val rules: List<PanelRule> = emptyList(),
    val ruleTotal: Int = rules.size,
    val ruleSets: List<PanelRuleSet> = emptyList(),
    val logs: List<PanelLogEntry> = emptyList(),
    /** Owns the pull indicator; false only after the real read finishes or is cancelled. */
    val refreshing: Boolean = false,
    /** A failed controller read is unavailable data, never an empty live snapshot. */
    val readError: String = "",
    /** Running, but the first controller snapshot has not arrived yet: tabs show placeholders. */
    val loading: Boolean = false,
    /** Groups whose 测速 is in flight. */
    val testingGroups: Set<String> = emptySet(),
    /** A whole-profile latency test is in flight. */
    val testingAll: Boolean = false,
    /** group → node the user picked that the core has not confirmed yet. */
    val switching: Map<String, String> = emptyMap(),
    /** “全部更新” is running for subscriptions / rule sets, including while single items have already finished. */
    val updatingAllSubscriptions: Boolean = false,
    val updatingAllRuleSets: Boolean = false,
) {
    val running: Boolean get() = status == PanelStatus.Running

    /** Follows `now` through nested groups (at most five hops) to the node that actually carries traffic. */
    fun leafOf(name: String): String {
        var current = name
        var hops = 0
        while (hops++ < 5) current = groups.firstOrNull { it.name == current }?.now?.takeIf { it.isNotBlank() } ?: break
        return current
    }

    /** Latency to show next to [node]; null when a figure makes no sense (DIRECT). */
    fun delayOf(node: String): PanelDelay? {
        if (node.equals("DIRECT", ignoreCase = true) || node.equals("REJECT", ignoreCase = true)) return null
        // An alias/group can own its own request while displaying a selected leaf.
        // Preserve that busy state instead of hiding it behind the leaf's old result.
        if (delays[node] == PanelDelay.Testing) return PanelDelay.Testing
        return delays[leafOf(node)] ?: PanelDelay.Unknown
    }
}

/* ------------------------------------------------------------------ */
/*  View state: what the user has chosen on screen                      */
/* ------------------------------------------------------------------ */

/** 策略 › 筛选浮层. All five are independent check boxes. */
internal data class PanelGroupDisplay(
    val showHidden: Boolean = false,
    val globalByMode: Boolean = true,
    val groupByProvider: Boolean = false,
    val collapsePrevious: Boolean = true,
    val disconnectOnSelect: Boolean = false,
)

internal enum class PanelNodeSort(val label: String) { Config("按配置"), Name("名称"), Delay("延迟") }

/** 策略 › 排序与布局. */
internal data class PanelGroupLayout(
    val sort: PanelNodeSort = PanelNodeSort.Config,
    val descending: Boolean = false,
    val groupColumns: Int = 2,
    val compactGroups: Boolean = true,
    val nodeColumns: Int = 2,
    val compactNodes: Boolean = false,
    val wrapNames: Boolean = false,
)

/** 策略 › 测速与 API. */
internal data class PanelApiSettings(
    val customTestUrl: Boolean = false,
    val testUrl: String = "https://example.com/generate_204",
    val history: Boolean = false,
    val externalApi: Boolean = false,
    val host: String = "127.0.0.1",
    val port: String = "9090",
    val secret: String = "",
)

internal enum class PanelConnFilter(val label: String) { All("全部"), Proxy("代理"), Direct("直连") }
internal enum class PanelConnSort(val label: String) { Host("主机"), Rule("规则"), Type("类型"), Time("连接时间"), Down("下行"), Up("上行") }
internal enum class PanelRankMode(val label: String) { Connections("按连接数"), Total("按总流量") }
internal enum class PanelLogFilter(val label: String, val level: PanelLogLevel?) { All("全部", null), Info("INFO", PanelLogLevel.Info), Warn("WARN", PanelLogLevel.Warn), Error("ERROR", PanelLogLevel.Error) }
internal enum class PanelLogOrder(val label: String) { Newest("最新在前"), Oldest("最早在前") }

internal data class PanelViewState(
    val tab: PanelTab = PanelTab.Groups,
    val searching: Boolean = false,
    val query: String = "",
    /** Expanded strategy groups, most recent last. */
    val expandedGroups: List<String> = emptyList(),
    val display: PanelGroupDisplay = PanelGroupDisplay(),
    val layout: PanelGroupLayout = PanelGroupLayout(),
    val api: PanelApiSettings = PanelApiSettings(),
    val rankMode: PanelRankMode = PanelRankMode.Connections,
    val rankCount: Int = 5,
    val showUploadTrend: Boolean = true,
    val showDownloadTrend: Boolean = true,
    val connFilter: PanelConnFilter = PanelConnFilter.All,
    val connSort: PanelConnSort = PanelConnSort.Down,
    val groupByApp: Boolean = false,
    val openApps: Set<String> = emptySet(),
    val logFilter: PanelLogFilter = PanelLogFilter.All,
    val logOrder: PanelLogOrder = PanelLogOrder.Newest,
    val openLogs: Set<Int> = emptySet(),
) {
    /** Effective search text: empty unless the search field is open. */
    val needle: String get() = if (searching) query.trim() else ""

    fun withTab(next: PanelTab): PanelViewState = copy(tab = next, searching = false, query = "")
    fun toggleSearch(): PanelViewState = copy(searching = !searching, query = "")

    fun toggleGroup(name: String): PanelViewState = copy(
        expandedGroups = when {
            name in expandedGroups -> expandedGroups - name
            display.collapsePrevious -> listOf(name)
            else -> expandedGroups + name
        },
    )
}

/** Exactly one floating layer at a time. */
internal sealed interface PanelOverlay {
    /* anchored menus */
    data object GroupFilterMenu : PanelOverlay
    data object RankModeMenu : PanelOverlay
    data object RankCountMenu : PanelOverlay
    data object ConnFilterMenu : PanelOverlay
    data object ConnSortMenu : PanelOverlay
    data object ConnMoreMenu : PanelOverlay
    data object LogOrderMenu : PanelOverlay

    /* bottom sheets */
    data object LayoutSheet : PanelOverlay
    data object ApiSheet : PanelOverlay
    /** Direct entry from a failed read; cancel returns to the panel, not the layout sheet. */
    data object ApiReadErrorSheet : PanelOverlay
    data class NodeInfo(val node: String) : PanelOverlay
    data class ConnectionDetail(val id: String) : PanelOverlay

    /* dialogs */
    data object CloseAllDialog : PanelOverlay

    val isMenu: Boolean get() = this is GroupFilterMenu || this is RankModeMenu || this is RankCountMenu ||
        this is ConnFilterMenu || this is ConnSortMenu || this is ConnMoreMenu || this is LogOrderMenu
    val isSheet: Boolean get() = this is LayoutSheet || this is ApiSheet || this is ApiReadErrorSheet || this is NodeInfo || this is ConnectionDetail
}

/** Everything the panel asks its host to do. Defaults are no-ops so previews need nothing. */
internal class PanelActions(
    val onStart: () -> Unit = {},
    val onSelectNode: (group: String, node: String) -> Unit = { _, _ -> },
    val onTestNode: (node: String) -> Unit = {},
    val onTestGroup: (group: String) -> Unit = {},
    /** 排序与布局 › 测试全部节点. */
    val onTestAll: () -> Unit = {},
    val onUpdateSubscription: (name: String) -> Unit = {},
    val onUpdateAllSubscriptions: () -> Unit = {},
    val onCloseConnection: (id: String) -> Unit = {},
    val onCloseAllConnections: () -> Unit = {},
    val onRefreshRules: () -> Unit = {},
    val onUpdateRuleSet: (name: String) -> Unit = {},
    val onUpdateAllRuleSets: () -> Unit = {},
    val onRefreshLogs: () -> Unit = {},
    val onSaveApi: (PanelApiSettings) -> Unit = {},
    /** 排序与布局 › 策略图标. */
    val onOpenPolicyIcons: () -> Unit = {},
    val onCopy: (label: String, text: String) -> Unit = { _, _ -> },
    val onRefresh: () -> Unit = {},
)

/* ------------------------------------------------------------------ */
/*  Derivations                                                         */
/* ------------------------------------------------------------------ */

/** One lazy-list row of the 策略 tab. */
internal sealed interface PanelGroupRow {
    /** A row of group cards; shorter than the column count only on the last row. */
    data class Cards(val groups: List<PanelGroup>) : PanelGroupRow

    /** Header of an expanded node panel: “节点选择 · 12 个节点” + 测速. */
    data class NodesHeader(val group: PanelGroup) : PanelGroupRow

    /** Provider caption inside a node panel when “按订阅分组节点” is on. */
    data class Provider(val group: PanelGroup, val provider: String, val count: Int) : PanelGroupRow

    /** One row of node cards. [last] closes the sunken panel. */
    data class Nodes(val group: PanelGroup, val nodes: List<PanelNode>, val last: Boolean) : PanelGroupRow
}

internal data class PanelAppGroup(val app: String, val packageName: String, val connections: List<PanelConnection>)

internal object PanelLogic {
    const val UnattributedApp = "未归属应用"
    val rankCounts = listOf(5, 10, 15, 20, 30)

    /** Keep user column choices; use one column where font scaling would squeeze the cards. */
    fun layoutForViewport(layout: PanelGroupLayout, widthDp: Int, fontScale: Float): PanelGroupLayout {
        val scale = fontScale.takeIf { it.isFinite() && it > 0f } ?: 1f
        val groupWidth = (widthDp - 32f - 8f) / 2f
        val nodeWidth = (widthDp - 32f - 16f - 8f) / 2f
        return layout.copy(
            groupColumns = if (groupWidth < 148f * scale) 1 else layout.groupColumns,
            nodeColumns = if (nodeWidth < 148f * scale) 1 else layout.nodeColumns,
        )
    }

    fun visibleGroups(data: PanelData, view: PanelViewState): List<PanelGroup> {
        val terms = view.needle.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        return data.groups.filter { g ->
            (!g.hidden || view.display.showHidden) &&
                (!g.isGlobal || !view.display.globalByMode || data.globalMode) &&
                (terms.isEmpty() || terms.all { g.name.contains(it, true) || g.now.contains(it, true) } ||
                    g.nodes.any { n -> terms.all { n.name.contains(it, true) || n.protocol.contains(it, true) || n.provider.contains(it, true) } })
        }
    }

    fun sortedNodes(group: PanelGroup, layout: PanelGroupLayout, data: PanelData): List<PanelNode> {
        val sorted = when (layout.sort) {
            PanelNodeSort.Config -> group.nodes
            PanelNodeSort.Name -> group.nodes.sortedBy { it.name }
            PanelNodeSort.Delay -> group.nodes.sortedBy { (data.delayOf(it.name) as? PanelDelay.Ms)?.value ?: Long.MAX_VALUE }
        }
        return if (layout.descending) sorted.asReversed() else sorted
    }

    /** Flattens groups and expanded node panels into lazy-list rows; a node panel follows the card row of its group. */
    fun groupRows(data: PanelData, view: PanelViewState): List<PanelGroupRow> {
        val columns = view.layout.groupColumns.coerceIn(1, 2)
        val nodeColumns = view.layout.nodeColumns.coerceIn(1, 2)
        val rows = mutableListOf<PanelGroupRow>()
        visibleGroups(data, view).chunked(columns).forEach { chunk ->
            rows += PanelGroupRow.Cards(chunk)
            chunk.filter { it.name in view.expandedGroups || view.needle.isNotBlank() }.forEach { group ->
                rows += PanelGroupRow.NodesHeader(group)
                val terms = view.needle.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
                val headerMatches = terms.all { group.name.contains(it, true) || group.now.contains(it, true) }
                val nodes = sortedNodes(group, view.layout, data).filter { n ->
                    headerMatches || terms.all { n.name.contains(it, true) || n.protocol.contains(it, true) || n.provider.contains(it, true) }
                }
                val sections: List<Pair<String?, List<PanelNode>>> =
                    if (view.display.groupByProvider) nodes.groupBy { it.provider.ifBlank { "其他" } }.map { it.key to it.value }
                    else listOf(null to nodes)
                val pending = mutableListOf<PanelGroupRow>()
                sections.forEach { (provider, items) ->
                    if (provider != null) pending += PanelGroupRow.Provider(group, provider, items.size)
                    items.chunked(nodeColumns).forEach { pending += PanelGroupRow.Nodes(group, it, last = false) }
                }
                val lastIndex = pending.indexOfLast { it is PanelGroupRow.Nodes }
                pending.forEachIndexed { index, row ->
                    rows += if (index == lastIndex && row is PanelGroupRow.Nodes) row.copy(last = true) else row
                }
            }
        }
        return rows
    }

    fun subscriptions(data: PanelData, view: PanelViewState): List<PanelSubscription> =
        data.subscriptions.filter { view.needle.isEmpty() || it.name.contains(view.needle, ignoreCase = true) }

    fun connections(data: PanelData, view: PanelViewState): List<PanelConnection> {
        val q = view.needle
        val filtered = data.connections.filter { c ->
            (view.connFilter == PanelConnFilter.All || (view.connFilter == PanelConnFilter.Direct) == c.isDirect) &&
                (q.isEmpty() || c.host.contains(q, ignoreCase = true) || c.app.contains(q, ignoreCase = true))
        }
        return when (view.connSort) {
            PanelConnSort.Host -> filtered.sortedBy { it.host }
            PanelConnSort.Rule -> filtered.sortedBy { it.rule }
            PanelConnSort.Type -> filtered.sortedBy { it.network }
            PanelConnSort.Time -> filtered.sortedByDescending { it.time.orEmpty() }
            PanelConnSort.Down -> filtered.sortedByDescending { it.downloadBytesPerSecond }
            PanelConnSort.Up -> filtered.sortedByDescending { it.uploadBytesPerSecond }
        }
    }

    fun byApp(connections: List<PanelConnection>): List<PanelAppGroup> =
        connections.groupBy { it.appKey }.map { (app, items) -> PanelAppGroup(app, items.first().packageName, items) }

    /** Number next to “连接详情”: the core's total, or the visible count once a filter or search narrows the list. */
    fun connectionCount(data: PanelData, view: PanelViewState, visible: Int): Int =
        if (view.needle.isNotEmpty() || view.connFilter != PanelConnFilter.All) visible else maxOf(data.connectionTotal, visible)

    fun rules(data: PanelData, view: PanelViewState): List<PanelRule> {
        val q = view.needle
        return data.rules.filter { q.isEmpty() || it.type.contains(q, true) || it.payload.contains(q, true) || it.policy.contains(q, true) }
    }

    fun ruleSets(data: PanelData, view: PanelViewState): List<PanelRuleSet> =
        data.ruleSets.filter { view.needle.isEmpty() || it.name.contains(view.needle, ignoreCase = true) }

    fun logs(data: PanelData, view: PanelViewState): List<PanelLogEntry> {
        val level = view.logFilter.level
        val q = view.needle
        val filtered = data.logs.filter { (level == null || it.level == level) && (q.isEmpty() || it.message.contains(q, ignoreCase = true)) }
        return if (view.logOrder == PanelLogOrder.Oldest) filtered.asReversed() else filtered
    }

    fun ranks(overview: PanelOverview, mode: PanelRankMode, count: Int): List<PanelRank> = when (mode) {
        PanelRankMode.Connections -> overview.ranks.sortedByDescending { it.connections }
        PanelRankMode.Total -> overview.ranks.sortedByDescending { it.totalBytes }
    }.take(count)

    /** Field → message for the 测速与 API sheet; empty when it can be saved. */
    fun validateApi(settings: PanelApiSettings): Map<String, String> = buildMap {
        if (settings.customTestUrl) {
            val u = runCatching { java.net.URI(settings.testUrl.trim()) }.getOrNull()
            if (u == null || u.scheme?.lowercase(Locale.ROOT) !in setOf("http", "https") || u.host.isNullOrBlank() ||
                u.rawUserInfo != null || settings.testUrl.length > 2048) put("testUrl", "测速 URL 必须是完整的 HTTP/HTTPS 地址，且不能包含账号密码")
        }
        if (settings.externalApi) {
            if (!settings.host.trim().matches(Regex("[A-Za-z0-9.-]{1,253}")) || settings.host.trim().startsWith('.') ||
                settings.host.trim().endsWith('.')) put("host", "后端主机只填写 IPv4 或域名，不含协议和端口")
            val port = settings.port.trim().toIntOrNull()
            if (port == null || port !in 1024..65535) put("port", "端口范围为 1024–65535")
        }
        if ('\r' in settings.secret || '\n' in settings.secret) put("secret", "Secret 不能包含换行")
    }

    private fun isHttpUrl(raw: String): Boolean {
        val url = raw.trim()
        val rest = when {
            url.startsWith("https://", ignoreCase = true) -> url.substring(8)
            url.startsWith("http://", ignoreCase = true) -> url.substring(7)
            else -> return false
        }
        return rest.substringBefore('/').isNotEmpty() && url.none { it.isWhitespace() }
    }
}

/** Two-letter region code from a node name; the table and the emoji detection live in [HomeRegions]. */
internal object PanelRegions {
    fun codeOf(name: String): String = HomeRegions.codeOf(name)
}
