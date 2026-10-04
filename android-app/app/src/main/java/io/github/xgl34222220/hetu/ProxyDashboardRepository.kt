package io.github.xgl34222220.hetu

import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

internal data class DashboardProviderUi(
    val name: String,
    val vehicleType: String,
    val testUrl: String,
    val expectedStatus: String,
    val updatedAt: String,
    val upload: Long,
    val download: Long,
    val total: Long,
    val expire: Long,
    val nodes: Set<String>,
    val hasSubscriptionInfo: Boolean,
) {
    val used: Long get() = (upload + download).coerceAtLeast(0L)
    val remaining: Long get() = (total - used).coerceAtLeast(0L)
    val ratio: Float get() = if (total <= 0L) 0f else (used.toDouble() / total.toDouble()).toFloat().coerceIn(0f, 1f)
}

internal data class DashboardRuleSetUi(
    val name: String,
    val behavior: String,
    val format: String,
    val vehicleType: String,
    val ruleCount: Int,
    val updatedAt: String,
)

internal class ProxyDashboardRepository(context: Context) {
    private val app = context.applicationContext
    private val api = MihomoControllerClient(app)
    private val controller = ProxyComposeController(app)
    private data class ProbeSnapshot(val proxies: JSONObject, val providers: List<DashboardProviderUi>)
    private val probeSnapshotMutex = Mutex()
    private var cachedProbeSnapshot: ProbeSnapshot? = null
    private var probeSnapshotAt = 0L

    private suspend fun probeSnapshot(force: Boolean = false): ProbeSnapshot = probeSnapshotMutex.withLock {
        val now = SystemClock.elapsedRealtime()
        val cached = cachedProbeSnapshot
        if (!force && cached != null && now - probeSnapshotAt in 0L..1500L) return@withLock cached
        val raw = api.proxies()
        val providerResponse = api.proxyProviders()
        ProbeSnapshot(mergeProxySnapshots(raw, providerResponse), parseProviders(providerResponse)).also {
            cachedProbeSnapshot = it
            probeSnapshotAt = now
        }
    }

    private fun probe(node: String, snapshot: ProbeSnapshot): Long {
        val leaf = selectedProxyName(snapshot.proxies, node)
        val entry = snapshot.proxies.optJSONObject(leaf) ?: throw IOException("节点已更新，请刷新策略组")
        val provider = snapshot.providers.firstOrNull { it.name == entry.optString("provider-name") }
        val group = snapshot.proxies.optJSONObject(node)?.takeIf { it.optJSONArray("all") != null }
            ?: snapshot.proxies.keys().asSequence().mapNotNull { snapshot.proxies.optJSONObject(it) }
                .firstOrNull { candidate ->
                    val all = candidate.optJSONArray("all")
                    candidate.optString("testUrl").isNotBlank() && all != null &&
                        (0 until all.length()).any { all.optString(it) == leaf }
                }
        val testUrl = provider?.testUrl?.takeIf { it.isNotBlank() } ?: group?.optString("testUrl").orEmpty()
        val expected = provider?.expectedStatus ?: group?.optString("expectedStatus", "200-399") ?: "200-399"
        return if (provider != null) api.providerDelay(provider.name, leaf, testUrl, expected)
        else api.delay(leaf, testUrl, expected)
    }

    private fun measuredProbe(node: String, snapshot: ProbeSnapshot): Long = try { probe(node, snapshot) }
        catch (failure: MihomoControllerClient.DelayFailure) { if (failure.timedOut) -1L else -2L }


    suspend fun state(): ProxyComposeState = controller.state()
    suspend fun rules(): List<ProxyRuleUi> = controller.rules()
    suspend fun select(group: String, node: String, disconnectPrevious: Boolean = false) = withContext(Dispatchers.IO) {
        // Take a live snapshot before switching. A cached UI list includes unrelated
        // DIRECT/message transports, and a post-switch list can include new sessions.
        val previous = if (disconnectPrevious) api.proxies().optJSONObject(group)
            ?.optString("now").orEmpty() else ""
        val oldIds = if (disconnectPrevious && previous.isNotBlank() && previous != node)
            selectionConnectionIds(api.connections(), group) else emptyList()
        api.select(group, node)
        // A failed switch never reaches cleanup. Missing/unknown chains are retained.
        var failed = 0
        for (id in oldIds) {
            try { api.closeConnection(id) }
            catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { failed++ }
        }
        app.getSharedPreferences("hetu", 0).edit()
            .putLong("proxyLastSelectionAt", System.currentTimeMillis())
            .putString("proxyLastSelectionGroup", group)
            .putInt("proxyLastSelectionClosed", oldIds.size - failed)
            .putInt("proxyLastSelectionCloseFailed", failed)
            .apply()
    }
    suspend fun closeConnection(id: String) = controller.closeConnection(id)
    /** V19: live Mihomo traffic mode (rule / global / direct); "rule" when unreadable. */
    suspend fun trafficMode(): String = withContext(Dispatchers.IO) {
        runCatching { api.configs().optString("mode", "rule").lowercase() }.getOrDefault("rule")
    }
    suspend fun setTrafficMode(mode: String) = withContext(Dispatchers.IO) { api.setTrafficMode(mode) }
    /** V19: "v1.19.x Meta" style label from GET /version; empty when the core is down. */
    suspend fun coreVersion(): String = withContext(Dispatchers.IO) {
        runCatching {
            val v = api.version()
            listOf(v.optString("version"), if (v.optBoolean("meta")) "Meta" else "").filter { it.isNotBlank() }.joinToString(" ")
        }.getOrDefault("")
    }
    suspend fun closeAll() = controller.closeAll()
    suspend fun ensureIcons(): Int = controller.ensureIcons()

    /** Only real remote HTTP providers belong on the subscription page. */
    suspend fun providers(): List<DashboardProviderUi> = withContext(Dispatchers.IO) {
        remoteProviders(api.proxyProviders())
    }

    suspend fun ruleSets(): List<DashboardRuleSetUi> = withContext(Dispatchers.IO) {
        parseRuleSets(api.ruleProviders())
    }

    suspend fun refreshSubscriptions(): List<DashboardProviderUi> = withContext(Dispatchers.IO) {
        val before = remoteProviders(api.proxyProviders())
        for (chunk in before.chunked(3)) {
            coroutineScope {
                chunk.map { provider ->
                    async {
                        try { api.updateProxyProvider(provider.name) }
                        catch (cancel: CancellationException) { throw cancel }
                        catch (_: Exception) { }
                    }
                }.awaitAll()
            }
        }
        remoteProviders(api.proxyProviders())
    }

    suspend fun refreshRuleSets(): List<DashboardRuleSetUi> = withContext(Dispatchers.IO) {
        val before = parseRuleSets(api.ruleProviders())
        for (chunk in before.filter { it.vehicleType.equals("HTTP", true) }.chunked(3)) {
            coroutineScope {
                chunk.map { provider ->
                    async {
                        try { api.updateRuleProvider(provider.name) }
                        catch (cancel: CancellationException) { throw cancel }
                        catch (_: Exception) { }
                    }
                }.awaitAll()
            }
        }
        parseRuleSets(api.ruleProviders())
    }

    /** Probe selected nodes only. Provider metadata is shared by the whole wave. */
    suspend fun quickDelay(): Map<String, Long> = withContext(Dispatchers.IO) {
        val snapshot = probeSnapshot(force = true)
        val targets = snapshot.proxies.keys().asSequence().mapNotNull { name ->
            if (name == "GLOBAL") null else snapshot.proxies.optJSONObject(name)
                ?.takeIf { it.optJSONArray("all") != null }?.optString("now")?.takeIf { it.isNotBlank() }
        }.distinct().toList()
        measureSnapshot(targets, snapshot)
    }

    /** Every provider leaf is included, with bounded requests to the correct core endpoint. */
    suspend fun globalDelay(): Map<String, Long> = withContext(Dispatchers.IO) {
        val snapshot = probeSnapshot(force = true)
        val targets = snapshot.proxies.keys().asSequence().filter { name ->
            val node = snapshot.proxies.optJSONObject(name)
            node != null && node.optJSONArray("all") == null &&
                node.optString("type").lowercase() !in setOf("direct", "reject", "rejectdrop", "pass", "compatible")
        }.toList()
        measureSnapshot(targets, snapshot)
    }

    private suspend fun measureSnapshot(targets: List<String>, snapshot: ProbeSnapshot): Map<String, Long> {
        val results = LinkedHashMap<String, Long>()
        for (chunk in targets.distinct().chunked(6)) {
            coroutineScope {
                chunk.map { node -> async { node to measuredProbe(node, snapshot) } }.awaitAll()
            }.forEach { (node, value) -> results[node] = value }
        }
        return results
    }

/** Match exact core-provided chain elements, never a rendered arrow-separated label. */
internal fun selectionConnectionIds(snapshot: JSONObject, group: String): List<String> {
    if (group.isBlank()) return emptyList()
    val connections = snapshot.optJSONArray("connections") ?: return emptyList()
    return buildSet {
        for (index in 0 until connections.length()) {
            val connection = connections.optJSONObject(index) ?: continue
            val chains = connection.optJSONArray("chains") ?: continue
            val id = connection.optString("id")
            if (id.isNotBlank() && (0 until chains.length()).any { chains.optString(it) == group }) add(id)
        }
    }.toList()
}

    suspend fun delay(node: String): Long = withContext(Dispatchers.IO) {
        probe(node, probeSnapshot())
    }

    /**
     * Mihomo's /group/{name}/delay clears fixed selection on non-Selector groups
     * (upstream ab405bad, hub/route/groups.go). Probe their leaves instead; never
     * clear and reapply a pin, which could briefly reroute live traffic.
     */
    suspend fun groupDelay(group: ProxyGroupUi, targets: List<String>): Map<String, Long> = withContext(Dispatchers.IO) {
        if (group.type.equals("Selector", ignoreCase = true)) return@withContext groupDelay(group.name)
        val snapshot = probeSnapshot(force = true)
        val results = LinkedHashMap<String, Long>()
        for (chunk in targets.distinct().chunked(6)) {
            val measured = coroutineScope {
                chunk.map { node -> async {
                    try { node to measuredProbe(node, snapshot) }
                    catch (cancel: CancellationException) { throw cancel }
                    catch (_: IOException) { node to null }
                } }.awaitAll()
            }
            measured.forEach { (node, delay) -> if (delay != null) results[node] = delay }
        }
        results
    }

    /** Core-parallel group latency probe used by strategy-card delay taps. */
    suspend fun groupDelay(group: String): Map<String, Long> = withContext(Dispatchers.IO) {
        val raw = api.groupDelay(group)
        buildMap {
            val keys = raw.keys()
            while (keys.hasNext()) {
                val name = keys.next()
                // JSONObject.optLong coerces malformed/missing values to a false timeout.
                // The core contract is an integer measurement, including explicit 0 / -1.
                when (val value = raw.opt(name)) {
                    is Int -> put(name, value.toLong())
                    is Long -> put(name, value)
                }
            }
        }
    }

    suspend fun ipv6Delay(node: String): Long = withContext(Dispatchers.IO) {
        val snapshot = probeSnapshot()
        val leaf = selectedProxyName(snapshot.proxies, node)
        api.delayIpv6(leaf, snapshot.proxies.optJSONObject(leaf)?.optString("provider-name").orEmpty())
    }

    suspend fun siteLatencies(): Map<String, Long> = withContext(Dispatchers.IO) {
        val sites = ProxyLatencyTargets.load(app)
        coroutineScope {
            sites.map { target ->
                async { target.name to measureSiteLatency(target.url) }
            }.awaitAll().toMap()
        }
    }

    suspend fun refreshProvider(name: String): DashboardProviderUi? = withContext(Dispatchers.IO) {
        api.updateProxyProvider(name)
        remoteProviders(api.proxyProviders()).firstOrNull { it.name == name }
    }

    suspend fun refreshRuleSet(name: String): DashboardRuleSetUi? = withContext(Dispatchers.IO) {
        api.updateRuleProvider(name)
        parseRuleSets(api.ruleProviders()).firstOrNull { it.name == name }
    }

    private fun measureSiteLatency(url: String): Long {
        return try {
            val started = SystemClock.elapsedRealtime()
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = 3_000
                readTimeout = 3_000
                requestMethod = "GET"
                setRequestProperty("User-Agent", "Hetu-Android")
                setRequestProperty("Cache-Control", "no-cache")
            }
            try {
                val code = connection.responseCode
                if (code in 200..499) (SystemClock.elapsedRealtime() - started).coerceAtLeast(1L) else -1L
            } finally {
                runCatching { connection.inputStream?.close() }
                connection.disconnect()
            }
        } catch (_: Exception) {
            -1L
        }
    }

    private fun remoteProviders(root: JSONObject): List<DashboardProviderUi> =
        parseProviders(root).filter { it.vehicleType.equals("HTTP", true) }

    private fun parseProviders(root: JSONObject): List<DashboardProviderUi> {
        val providers = root.optJSONObject("providers") ?: JSONObject()
        val out = ArrayList<DashboardProviderUi>()
        val names = providers.keys()
        while (names.hasNext()) {
            val name = names.next()
            val p = providers.optJSONObject(name) ?: continue
            val nodes = LinkedHashSet<String>()
            val proxies = p.optJSONArray("proxies") ?: JSONArray()
            for (i in 0 until proxies.length()) {
                when (val value = proxies.opt(i)) {
                    is JSONObject -> value.optString("name").takeIf { it.isNotBlank() }?.let(nodes::add)
                    is String -> if (value.isNotBlank()) nodes += value
                }
            }
            val info = p.optJSONObject("subscriptionInfo")
            fun infoLong(upper: String, lower: String): Long = when {
                info == null -> 0L
                info.has(upper) -> info.optLong(upper, 0L)
                else -> info.optLong(lower, 0L)
            }
            out += DashboardProviderUi(
                name = name,
                vehicleType = p.optString("vehicleType", p.optString("vehicle", "")),
                testUrl = p.optString("testUrl", ""),
                expectedStatus = p.optString("expectedStatus", "200-399").ifBlank { "200-399" },
                updatedAt = normalizeTimestamp(p.optString("updatedAt", "")),
                upload = infoLong("Upload", "upload"),
                download = infoLong("Download", "download"),
                total = infoLong("Total", "total"),
                expire = infoLong("Expire", "expire"),
                nodes = nodes,
                hasSubscriptionInfo = info != null,
            )
        }
        return out.sortedBy { it.name.lowercase() }
    }

    private fun parseRuleSets(root: JSONObject): List<DashboardRuleSetUi> {
        val providers = root.optJSONObject("providers") ?: JSONObject()
        val out = ArrayList<DashboardRuleSetUi>()
        val names = providers.keys()
        while (names.hasNext()) {
            val name = names.next()
            val p = providers.optJSONObject(name) ?: continue
            out += DashboardRuleSetUi(
                name = name,
                behavior = p.optString("behavior", ""),
                format = normalizeRuleFormat(p.optString("format", p.optString("ruleFormat", ""))),
                vehicleType = p.optString("vehicleType", p.optString("vehicle", "")),
                ruleCount = p.optInt("ruleCount", p.optInt("count", 0)),
                updatedAt = normalizeTimestamp(p.optString("updatedAt", "")),
            )
        }
        return out.sortedBy { it.name.lowercase() }
    }

    private fun normalizeTimestamp(raw: String): String {
        val value = raw.trim()
        return if (value.isBlank() || value.startsWith("0001-01-01") || value.startsWith("0000-")) "" else value
    }

    private fun normalizeRuleFormat(raw: String): String = when (raw.lowercase()) {
        "mrsrule", "mrs" -> "MRS"
        "textrule", "text" -> "TEXT"
        "yamlrule", "yaml" -> "YAML"
        else -> raw.uppercase().ifBlank { "RULE" }
    }

}
