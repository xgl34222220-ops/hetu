package io.github.xgl34222220.hetu

import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.coroutineContext

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
    private companion object {
        // Activities have independent repository/cache instances but mutate the
        // same core. A confirmed mutation must supersede observations in all of them.
        val probeMutationEpoch = AtomicLong()
    }
    private val app = context.applicationContext
    private val api = MihomoControllerClient(app)
    private val controller = ProxyComposeController(app)
    // Keep credentials private and out of generated data-class toString output.
    internal class ProbeIdentity(private val settings: Map<String, Any?>, private val runtimeEpoch: Long, private val mutationEpoch: Long) {
        val stable: Boolean get() = runtimeEpoch >= 0L
        override fun equals(other: Any?): Boolean = other is ProbeIdentity &&
            settings == other.settings && runtimeEpoch == other.runtimeEpoch && mutationEpoch == other.mutationEpoch
        override fun hashCode(): Int = 31 * (31 * settings.hashCode() + runtimeEpoch.hashCode()) + mutationEpoch.hashCode()
        fun sameOrigin(other: ProbeIdentity): Boolean = settings == other.settings && runtimeEpoch == other.runtimeEpoch
    }
    internal class SelectionTicket internal constructor(private val settings: Map<String, Any?>, internal val identity: ProbeIdentity) {
        internal fun client(context: Context) = MihomoControllerClient(context, settings)
    }
    private class ProbeSnapshot(val proxies: JSONObject, val providers: List<DashboardProviderUi>, val identity: ProbeIdentity)
    private val probeSnapshotMutex = Mutex()
    private var cachedProbeSnapshot: ProbeSnapshot? = null
    private var probeSnapshotAt = 0L
    private val probeIdentityKeys = setOf(
        "proxyBaseCore", "proxyBaseMode", "proxyCustomApiEnabled", "proxyCustomApiHost",
        "proxyCustomApiPort", "proxyCustomApiSecret", "proxyControllerPort", "proxyControllerSecret",
        "proxyRootWanted", "proxyRootRuntimeRunning", "proxyRootLastStartupAt",
        "proxyRootAppliedSettings", "proxyRootAppliedRuntimeRevision", "proxyRootTopologyFingerprint",
        "proxyAdblockSessionGeneration", "proxyNetworkSessionId", "proxyNetworkEpoch",
    )

    private fun probeIdentity(values: Map<String, Any?> = app.getSharedPreferences("hetu", 0).all): ProbeIdentity {
        val core = ProxyRuntimeProfile.Core.from(values["proxyBaseCore"] as? String ?: "mihomo")
        val configKey = "proxySelectedConfig.${core.id}"
        val settings = values.filterKeys { key -> key == configKey || key in probeIdentityKeys }.toMutableMap()
        settings["proxyBaseCore"] = core.id
        // The configuration library may lazily persist this same default during a poll.
        settings[configKey] = (values[configKey] as? String).orEmpty().ifBlank {
            if (core == ProxyRuntimeProfile.Core.MIHOMO || core == ProxyRuntimeProfile.Core.MIHOMO_SMART)
                ProxyConfigLibrary.BUNDLED_NAME else ""
        }
        // Ordinary status/health reads do not change this nonblocking control epoch.
        return ProbeIdentity(settings, RootProxyManager.observationTicket(), probeMutationEpoch.get())
    }

    private fun requireCurrentProbe(identity: ProbeIdentity) {
        if (!identity.stable || probeIdentity() != identity)
            throw IOException("代理或控制接口已变化，请重新测速")
    }

    /** Capture before IO dispatch; the same atomic preference copy binds identity and HTTP. */
    internal fun captureSelection(): SelectionTicket {
        val settings = app.getSharedPreferences("hetu", 0).all
        return SelectionTicket(settings, probeIdentity(settings))
    }

    private fun requireCurrentOrigin(identity: ProbeIdentity) {
        if (!identity.stable || !probeIdentity().sameOrigin(identity))
            throw IOException("代理或控制接口已变化，请刷新后重试")
    }

    private fun acknowledgeMutation(ticket: SelectionTicket) {
        // A concurrent successful operation on another group must not invalidate
        // this operation's origin. Every confirmed current-core mutation advances
        // observations, while a response from a replaced endpoint cannot do so.
        requireCurrentOrigin(ticket.identity)
        probeMutationEpoch.incrementAndGet()
    }

    private inline fun <T> readCurrentProbe(snapshot: ProbeSnapshot, read: () -> T): T =
        readCurrentProbe(snapshot.identity, read)

    private inline fun <T> readCurrentProbe(identity: ProbeIdentity, read: () -> T): T {
        requireCurrentProbe(identity)
        return try {
            val measured = read()
            requireCurrentProbe(identity)
            measured
        } catch (cancel: CancellationException) { throw cancel }
        catch (error: Exception) {
            // A response from a superseded runtime is not a measurement for the new one,
            // including a core-confirmed failure received after an endpoint switch.
            requireCurrentProbe(identity)
            throw error
        }
    }

    private suspend fun probeSnapshot(force: Boolean = false): ProbeSnapshot = probeSnapshotMutex.withLock {
        val identity = probeIdentity()
        requireCurrentProbe(identity)
        val now = SystemClock.elapsedRealtime()
        val cached = cachedProbeSnapshot
        if (!force && cached != null && cached.identity == identity && now - probeSnapshotAt in 0L..1500L) return@withLock cached
        val raw = api.proxies()
        coroutineContext.ensureActive()
        requireCurrentProbe(identity)
        val providerResponse = api.proxyProviders()
        coroutineContext.ensureActive()
        requireCurrentProbe(identity)
        ProbeSnapshot(mergeProxySnapshots(raw, providerResponse), parseProviders(providerResponse), identity).also {
            requireCurrentProbe(identity)
            cachedProbeSnapshot = it
            probeSnapshotAt = SystemClock.elapsedRealtime()
        }
    }

    private fun probe(node: String, snapshot: ProbeSnapshot): Long {
        requireCurrentProbe(snapshot.identity)
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
        return readCurrentProbe(snapshot) {
            if (provider != null) api.providerDelay(provider.name, leaf, testUrl, expected)
            else api.delay(leaf, testUrl, expected)
        }
    }

    private fun measuredProbe(node: String, snapshot: ProbeSnapshot): Long = try { probe(node, snapshot) }
        catch (failure: MihomoControllerClient.DelayFailure) { if (failure.timedOut) -1L else -2L }


    suspend fun state(): ProxyComposeState = controller.state()
    suspend fun rules(): List<ProxyRuleUi> = controller.rules()
    suspend fun select(group: String, node: String, disconnectPrevious: Boolean = false,
        ticket: SelectionTicket = captureSelection()) = withContext(Dispatchers.IO) {
        requireCurrentOrigin(ticket.identity)
        val operationApi = ticket.client(app)
        // Take a live snapshot before switching. A cached UI list includes unrelated
        // DIRECT/message transports, and a post-switch list can include new sessions.
        val previous = if (disconnectPrevious) operationApi.proxies().optJSONObject(group)
            ?.optString("now").orEmpty() else ""
        requireCurrentOrigin(ticket.identity)
        val oldIds = if (disconnectPrevious && previous.isNotBlank() && previous != node)
            selectionConnectionIds(operationApi.connections(), group) else emptyList()
        requireCurrentOrigin(ticket.identity)
        operationApi.select(group, node)
        // Successful core mutations invalidate both cached metadata and in-flight
        // observations immediately, without waiting behind a blocking snapshot read.
        acknowledgeMutation(ticket)
        // A failed switch never reaches cleanup. Missing/unknown chains are retained.
        var failed = 0
        for (id in oldIds) {
            requireCurrentOrigin(ticket.identity)
            try { operationApi.closeConnection(id) }
            catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { requireCurrentOrigin(ticket.identity); failed++ }
            requireCurrentOrigin(ticket.identity)
        }
        requireCurrentOrigin(ticket.identity)
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
    suspend fun setTrafficMode(mode: String, ticket: SelectionTicket = captureSelection()) = withContext(Dispatchers.IO) {
        requireCurrentOrigin(ticket.identity)
        ticket.client(app).setTrafficMode(mode)
        acknowledgeMutation(ticket)
    }
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

    suspend fun refreshSubscriptions(ticket: SelectionTicket = captureSelection()): List<DashboardProviderUi> = withContext(Dispatchers.IO) {
        requireCurrentOrigin(ticket.identity)
        val operationApi = ticket.client(app)
        val before = remoteProviders(operationApi.proxyProviders())
        requireCurrentOrigin(ticket.identity)
        for (chunk in before.chunked(3)) {
            coroutineScope {
                chunk.map { provider ->
                    async {
                        try {
                            requireCurrentOrigin(ticket.identity)
                            operationApi.updateProxyProvider(provider.name)
                            acknowledgeMutation(ticket)
                        }
                        catch (cancel: CancellationException) { throw cancel }
                        catch (_: Exception) { }
                    }
                }.awaitAll()
            }
        }
        requireCurrentOrigin(ticket.identity)
        remoteProviders(operationApi.proxyProviders()).also { requireCurrentOrigin(ticket.identity) }
    }

    suspend fun refreshRuleSets(ticket: SelectionTicket = captureSelection()): List<DashboardRuleSetUi> = withContext(Dispatchers.IO) {
        requireCurrentOrigin(ticket.identity)
        val operationApi = ticket.client(app)
        val before = parseRuleSets(operationApi.ruleProviders())
        requireCurrentOrigin(ticket.identity)
        for (chunk in before.filter { it.vehicleType.equals("HTTP", true) }.chunked(3)) {
            coroutineScope {
                chunk.map { provider ->
                    async {
                        try {
                            requireCurrentOrigin(ticket.identity)
                            operationApi.updateRuleProvider(provider.name)
                            acknowledgeMutation(ticket)
                        }
                        catch (cancel: CancellationException) { throw cancel }
                        catch (_: Exception) { }
                    }
                }.awaitAll()
            }
        }
        requireCurrentOrigin(ticket.identity)
        parseRuleSets(operationApi.ruleProviders()).also { requireCurrentOrigin(ticket.identity) }
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
                chunk.map { node -> async {
                    try { node to measuredProbe(node, snapshot) }
                    catch (cancel: CancellationException) { throw cancel }
                    // A controller/transport error has no new node measurement. Keep
                    // successful siblings and let callers retain this node's old value.
                    catch (_: IOException) { node to null }
                } }.awaitAll()
            }.forEach { (node, value) -> if (value != null) results[node] = value }
        }
        requireCurrentProbe(snapshot.identity)
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
        requireCurrentProbe(snapshot.identity)
        results
    }

    /** Core-parallel group latency probe used by strategy-card delay taps. */
    suspend fun groupDelay(group: String): Map<String, Long> = withContext(Dispatchers.IO) {
        // The Selector endpoint returns one complete wave. A successful selection
        // or provider update can supersede it without changing the API/settings.
        readCurrentProbe(probeIdentity()) {
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
    }

    suspend fun ipv6Delay(node: String): Long = withContext(Dispatchers.IO) {
        val snapshot = probeSnapshot()
        val leaf = selectedProxyName(snapshot.proxies, node)
        readCurrentProbe(snapshot) {
            api.delayIpv6(leaf, snapshot.proxies.optJSONObject(leaf)?.optString("provider-name").orEmpty())
        }
    }

    suspend fun siteLatencies(): Map<String, Long> = withContext(Dispatchers.IO) {
        val identity = probeIdentity()
        requireCurrentProbe(identity)
        val sites = ProxyLatencyTargets.load(app)
        // Hetu's UID is exempt from transparent Root interception. Explicitly enter
        // the running local core so fake DNS and policy routing match the active
        // runtime; the optional remote controller API is unrelated to this listener.
        val egressPort = if (ProxyStatusBridge.rootProxyRunning(app))
            MihomoStartupConfig.egressProbePort(app.getSharedPreferences("hetu", 0)
                .getInt("proxyControllerPort", MihomoStartupConfig.CONTROLLER_PORT)) else null
        val measured = coroutineScope {
            sites.map { target ->
                async { target.name to measureSiteLatency(target.url, egressPort) }
            }.awaitAll().toMap()
        }
        coroutineContext.ensureActive()
        // A valid response on the previous physical route is not a measurement
        // for the replacement route, even when the core and API port stay put.
        requireCurrentProbe(identity)
        measured
    }

    suspend fun refreshProvider(name: String, ticket: SelectionTicket = captureSelection()): DashboardProviderUi? = withContext(Dispatchers.IO) {
        requireCurrentOrigin(ticket.identity)
        val operationApi = ticket.client(app)
        operationApi.updateProxyProvider(name)
        acknowledgeMutation(ticket)
        remoteProviders(operationApi.proxyProviders()).firstOrNull { it.name == name }.also { requireCurrentOrigin(ticket.identity) }
    }

    suspend fun refreshRuleSet(name: String, ticket: SelectionTicket = captureSelection()): DashboardRuleSetUi? = withContext(Dispatchers.IO) {
        requireCurrentOrigin(ticket.identity)
        val operationApi = ticket.client(app)
        operationApi.updateRuleProvider(name)
        acknowledgeMutation(ticket)
        parseRuleSets(operationApi.ruleProviders()).firstOrNull { it.name == name }.also { requireCurrentOrigin(ticket.identity) }
    }

    private suspend fun measureSiteLatency(url: String, egressPort: Int?): Long {
        coroutineContext.ensureActive()
        return try {
            val started = SystemClock.elapsedRealtime()
            val target = URL(url)
            val opened = if (egressPort == null) target.openConnection()
                else target.openConnection(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", egressPort)))
            val connection = (opened as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = 3_000
                readTimeout = 3_000
                requestMethod = "GET"
                setRequestProperty("User-Agent", "Hetu-Android")
                setRequestProperty("Cache-Control", "no-cache")
            }
            try {
                val code = connection.responseCode
                coroutineContext.ensureActive()
                if (code in 200..399) (SystemClock.elapsedRealtime() - started).coerceAtLeast(1L) else -1L
            } finally {
                runCatching { connection.inputStream?.close() }
                connection.disconnect()
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            coroutineContext.ensureActive()
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
