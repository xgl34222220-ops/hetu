package io.github.xgl34222220.hetu.panel

import io.github.xgl34222220.hetu.DashboardProviderUi
import io.github.xgl34222220.hetu.DashboardRuleSetUi
import io.github.xgl34222220.hetu.ProxyComposeState
import io.github.xgl34222220.hetu.ProxyConnectionUi
import io.github.xgl34222220.hetu.ProxyGroupUi
import io.github.xgl34222220.hetu.ProxyRuleUi
import io.github.xgl34222220.hetu.RefLogEntry
import io.github.xgl34222220.hetu.RefLogLevel
import java.text.SimpleDateFormat
import java.time.ZoneId
import java.util.Date
import java.util.Locale

/** Immutable values read from the sampler once for this presentation pass. */
internal data class PanelTrafficSnapshot(
    val upload: Long = 0L,
    val download: Long = 0L,
    val connectionRates: Map<String, Pair<Long, Long>> = emptyMap(),
    val uploadTrend: List<Float> = emptyList(),
    val downloadTrend: List<Float> = emptyList(),
)

/** One last-value cache per independent presentation slice; no runtime/controller state is cached here. */
private class PanelProjectionSlice<T> {
    private var keys: List<Any?>? = null
    private var value: T? = null

    fun get(vararg inputs: Any?, build: () -> T): T {
        val next = inputs.toList()
        if (keys != next) {
            val fresh = build()
            value = fresh
            keys = next
        }
        @Suppress("UNCHECKED_CAST")
        return value as T
    }

    fun clear() { keys = null; value = null }
}

/**
 * Traffic updates must not remap every node, rule and log on the main thread.
 * Source lists are immutable ViewModel snapshots. Mutable map/set inputs are copied before
 * comparison so an in-place SnapshotStateMap edit still invalidates the appropriate slice.
 * Keep one instance with remember in each panel host; it owns no IO or coroutine work.
 */
internal class PanelDataProjector {
    private data class GroupBase(val groups: List<PanelGroup>, val delays: Map<String, PanelDelay>)
    private val groupBase = PanelProjectionSlice<GroupBase>()
    private val groups = PanelProjectionSlice<List<PanelGroup>>()
    private val nodeDelays = PanelProjectionSlice<Map<String, PanelDelay>>()
    private data class ConnectionMetadataKey(
        val host: String, val startedAt: String, val network: String, val inbound: String,
        val app: String, val packageName: String, val chain: String, val rule: String,
        val rulePayload: String, val zone: ZoneId,
    )
    private data class ConnectionMetadata(val key: ConnectionMetadataKey, val base: PanelConnection)
    // Replaced after each changed connection snapshot: never retain closed connection IDs.
    private var connectionMetadata: Map<String, ConnectionMetadata> = emptyMap()
    private val connections = PanelProjectionSlice<List<PanelConnection>>()
    private val ranks = PanelProjectionSlice<List<PanelRank>>()
    private val subscriptionOverview = PanelProjectionSlice<PanelOverviewSubscription?>()
    private val subscriptions = PanelProjectionSlice<List<PanelSubscription>>()
    private val rules = PanelProjectionSlice<List<PanelRule>>()
    private val ruleSets = PanelProjectionSlice<List<PanelRuleSet>>()
    private val logs = PanelProjectionSlice<List<PanelLogEntry>>()

    private fun clear() {
        groupBase.clear(); groups.clear(); nodeDelays.clear(); connectionMetadata = emptyMap()
        connections.clear(); ranks.clear(); subscriptionOverview.clear(); subscriptions.clear()
        rules.clear(); ruleSets.clear(); logs.clear()
    }

    fun project(
        state: ProxyComposeState,
        starting: Boolean,
        providers: List<DashboardProviderUi>,
        rules: List<ProxyRuleUi>,
        ruleSets: List<DashboardRuleSetUi>,
        logs: List<RefLogEntry>,
        delays: Map<String, Long>,
        testing: Map<String, Boolean>,
        selectedLocal: Map<String, String>,
        subscriptionUpdates: Map<String, PanelUpdate>,
        ruleSetUpdates: Map<String, PanelUpdate>,
        traffic: PanelTrafficSnapshot,
        testingGroups: Set<String> = emptySet(),
        testingAll: Boolean = false,
        switching: Map<String, String> = emptyMap(),
    ): PanelData {
        if (!state.running) {
            clear()
            return PanelData(status = if (starting) PanelStatus.Starting else PanelStatus.NotRunning)
        }
        if (state.controllerReadFailed) {
            clear()
            return PanelData(status = PanelStatus.Running,
                readError = state.controllerError.ifBlank { "控制接口读取失败，请重试或检查 API 设置" })
        }
        val delayValues = delays.toMap()
        val testingNames = testing.keys.toSet()
        val selections = selectedLocal.toMap()
        val sourceGroups = state.groups
        val base = groupBase.get(sourceGroups) { buildGroupBase(sourceGroups) }
        val shownGroups = groups.get(sourceGroups, delayValues, selections) {
            base.groups.mapIndexed { index, group ->
                val now = selections[group.name] ?: group.now
                val available = sourceGroups[index].nodes.count { (delayValues[it.name] ?: it.lastDelay ?: -1L) > 0L }
                if (now == group.now && available == group.availableCount) group
                else group.copy(now = now, availableCount = available)
            }
        }
        val shownDelays = nodeDelays.get(base.delays, delayValues, testingNames) {
            HashMap(base.delays).apply {
                delayValues.forEach { (name, ms) -> this[name] = panelDelayOf(ms) }
                testingNames.forEach { this[it] = PanelDelay.Testing }
            }.toMap()
        }
        val zone = ZoneId.systemDefault()
        val rates = traffic.connectionRates.toMap()
        val shownConnections = connections.get(state.connections, rates, zone) {
            val currentMetadata = HashMap<String, ConnectionMetadata>(state.connections.size)
            val projected = state.connections.map { source ->
                val key = ConnectionMetadataKey(source.host, source.startedAt, source.network, source.inbound,
                    source.appName, source.packageName, source.chain, source.rule, source.rulePayload, zone)
                val metadata = connectionMetadata[source.id]?.takeIf { it.key == key }
                    ?: ConnectionMetadata(key, buildConnectionBase(source, zone))
                currentMetadata[source.id] = metadata
                val baseConnection = metadata.base
                val rate = rates[source.id]
                val up = rate?.first ?: 0L
                val down = rate?.second ?: 0L
                // Totals and rates always belong to this poll, including a reset to zero.
                // The expensive date/chain/network projection belongs only to the static key.
                if (source.upload == 0L && source.download == 0L && up == 0L && down == 0L) baseConnection
                else baseConnection.copy(uploadTotalBytes = source.upload, downloadTotalBytes = source.download,
                    uploadBytesPerSecond = up, downloadBytesPerSecond = down)
            }
            connectionMetadata = currentMetadata
            projected
        }
        val shownRanks = ranks.get(shownConnections) {
            shownConnections.groupBy { it.app.ifBlank { "其他应用" } }.map { (app, items) ->
                PanelRank(app = app, packageName = items.first().packageName,
                    downloadBytesPerSecond = items.sumOf { it.downloadBytesPerSecond },
                    uploadBytesPerSecond = items.sumOf { it.uploadBytesPerSecond },
                    connections = items.size, totalBytes = items.sumOf { it.uploadTotalBytes + it.downloadTotalBytes })
            }
        }
        val shownSubscriptionOverview = subscriptionOverview.get(providers, zone) {
            val tracked = providers.filter { it.hasSubscriptionInfo && it.total > 0L }
            if (tracked.isEmpty()) null else PanelOverviewSubscription(
                usedBytes = tracked.sumOf { it.used }, totalBytes = tracked.sumOf { it.total },
                expire = tracked.map { it.expire }.filter { it > 0L }.minOrNull()?.let(::formatExpire),
                subscriptionCount = providers.size, nodeCount = providers.sumOf { it.nodes.size },
            )
        }
        val shownSubscriptions = subscriptions.get(providers, subscriptionUpdates.toMap(), zone) {
            providers.map { p ->
                PanelSubscription(name = p.name, expire = p.expire.takeIf { it > 0L }?.let(::formatExpire),
                    updatedAt = formatUpdated(p.updatedAt), uploadBytes = p.upload, downloadBytes = p.download,
                    totalBytes = if (p.hasSubscriptionInfo) p.total else 0L,
                    update = subscriptionUpdates[p.name] ?: PanelUpdate.Idle)
            }
        }
        val shownRules = this.rules.get(rules) {
            rules.map { PanelRule(it.type, it.payload.ifBlank { if (it.type.equals("Match", true)) "所有其他流量" else "" }, it.proxy) }
        }
        val shownRuleSets = this.ruleSets.get(ruleSets, ruleSetUpdates.toMap()) {
            ruleSets.map { PanelRuleSet(it.name, it.ruleCount, it.behavior, it.format, it.vehicleType,
                formatUpdated(it.updatedAt), ruleSetUpdates[it.name] ?: PanelUpdate.Idle) }
        }
        val shownLogs = this.logs.get(logs) {
            logs.asReversed().map {
                PanelLogEntry(it.index, when (it.level) {
                    RefLogLevel.Debug -> PanelLogLevel.Debug
                    RefLogLevel.Info -> PanelLogLevel.Info
                    RefLogLevel.Warn -> PanelLogLevel.Warn
                    RefLogLevel.Error -> PanelLogLevel.Error
                }, it.time, it.message)
            }
        }
        return PanelData(
            status = PanelStatus.Running, globalMode = state.trafficMode.equals("global", ignoreCase = true),
            groups = shownGroups, delays = shownDelays,
            loading = !state.panelReady && state.groups.isEmpty() && state.connections.isEmpty(),
            testingGroups = testingGroups.toSet(), testingAll = testingAll,
            switching = switching.filter { (group, node) -> sourceGroups.firstOrNull { it.name == group }?.now != node },
            overview = PanelOverview(
                strategyCount = shownGroups.count { !it.hidden && !it.isGlobal }, ruleCount = rules.size,
                connectionCount = shownConnections.size, subscription = shownSubscriptionOverview,
                uploadBytesPerSecond = traffic.upload, downloadBytesPerSecond = traffic.download,
                uploadTotalBytes = state.uploadTotal, downloadTotalBytes = state.downloadTotal,
                uploadTrend = traffic.uploadTrend.toList(), downloadTrend = traffic.downloadTrend.toList(), ranks = shownRanks,
            ),
            subscriptions = shownSubscriptions, connections = shownConnections, rules = shownRules,
            ruleSets = shownRuleSets, logs = shownLogs,
        )
    }

    private fun buildConnectionBase(source: ProxyConnectionUi, zone: ZoneId): PanelConnection {
        val bare = source.host.substringBeforeLast(':')
        return PanelConnection(
            id = source.id, host = source.host, time = source.startedAt.takeIf { it.isNotBlank() },
            timeLabel = formatStarted(source.startedAt, zone),
            network = source.network.substringBefore(" · ").uppercase(Locale.ROOT), inbound = source.inbound,
            kind = when {
                bare.count { it == ':' } >= 2 -> "IPv6"
                bare.all { it.isDigit() || it == '.' } -> "IPv4"
                else -> "FQDN"
            },
            app = source.appName, packageName = source.packageName,
            chain = source.chain.split(" → ").filter { it.isNotBlank() }.asReversed(),
            rule = listOf(source.rule, source.rulePayload).filter { it.isNotBlank() }.joinToString(" · "),
        )
    }

    private fun buildGroupBase(source: List<ProxyGroupUi>): GroupBase {
        val names = source.mapTo(HashSet()) { it.name }
        val delays = HashMap<String, PanelDelay>()
        val groups = source.map { g ->
            PanelGroup(name = g.name, type = groupType(g.type), now = g.now, hidden = g.hidden,
                availableCount = g.nodes.count { (it.lastDelay ?: -1L) > 0L },
                nodes = g.nodes.map { n ->
                    if (n.name !in names && n.lastDelay != null) delays[n.name] = panelDelayOf(n.lastDelay)
                    val kind = when {
                        n.name in names -> PanelNodeKind.Group
                        n.name.equals("DIRECT", true) || n.name.equals("REJECT", true) -> PanelNodeKind.Direct
                        else -> PanelNodeKind.Proxy
                    }
                    PanelNode(n.name, if (kind == PanelNodeKind.Group) "策略组" else n.type, n.udp, n.provider, kind)
                })
        }
        return GroupBase(groups, delays)
    }
}

private fun groupType(raw: String): String = when (raw.lowercase(Locale.ROOT)) {
    "selector", "select" -> "Selector"
    "urltest", "url-test" -> "URLTest"
    "fallback" -> "Fallback"
    "loadbalance", "load-balance" -> "LoadBalance"
    else -> raw.ifBlank { "Group" }
}

private fun formatStarted(raw: String, zone: ZoneId): String? {
    val value = raw.trim()
    if (value.isEmpty()) return null
    return try {
        java.time.OffsetDateTime.parse(value).atZoneSameInstant(zone).toLocalTime()
            .withNano(0).format(java.time.format.DateTimeFormatter.ISO_LOCAL_TIME)
    } catch (_: Exception) {
        if (value.length >= 19 && value[10] == 'T') value.substring(11, 19) else value
    }
}

private fun formatExpire(expire: Long): String {
    val millis = if (expire < 10_000_000_000L) expire * 1000L else expire
    return SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(millis))
}

private fun formatUpdated(raw: String): String? {
    val value = raw.trim()
    if (value.isEmpty()) return null
    return if (value.length >= 16 && value[4] == '-' && value[7] == '-') value.substring(5, 16).replace('T', ' ') else value.replace('T', ' ')
}
