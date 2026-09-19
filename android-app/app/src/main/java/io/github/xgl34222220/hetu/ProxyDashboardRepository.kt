package io.github.xgl34222220.hetu

import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
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

    suspend fun state(): ProxyComposeState = controller.state()
    suspend fun rules(): List<ProxyRuleUi> = controller.rules()
    suspend fun select(group: String, node: String) = controller.select(group, node)
    suspend fun closeConnection(id: String) = controller.closeConnection(id)
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

        /** Fast homepage probe: test only the currently selected nodes from strategy groups. */
    suspend fun quickDelay(): Map<String, Long> = withContext(Dispatchers.IO) {
        val targets = controller.state().groups.map { it.now }.filter { it.isNotBlank() }.distinct()
        if (targets.isEmpty()) return@withContext emptyMap()
        coroutineScope {
            targets.map { node ->
                async {
                    val value = try {
                        delay(node)
                    } catch (cancel: CancellationException) {
                        throw cancel
                    } catch (_: Exception) {
                        -1L
                    }
                    node to value
                }
            }.awaitAll().toMap()
        }
    }

/**
 * Test every real leaf node using the same provider/group health-check URL as manual testing.
 * A failed fresh probe keeps Mihomo's most recent positive result instead of falsely turning
 * a known-good node into "超时" just because a generic probe endpoint is blocked.
 */
suspend fun globalDelay(): Map<String, Long> = withContext(Dispatchers.IO) {
    val initial = api.proxies()
    val providers = remoteProviders(api.proxyProviders())
    val providerByNode = HashMap<String, DashboardProviderUi>()
    providers.forEach { provider ->
        provider.nodes.forEach { node -> providerByNode.putIfAbsent(node, provider) }
    }

    val leaves = LinkedHashSet<String>()
    val previous = HashMap<String, Long>()
    val groupProbe = HashMap<String, Pair<String, String>>()

    val names = initial.keys()
    while (names.hasNext()) {
        val name = names.next()
        val item = initial.optJSONObject(name) ?: continue
        val all = item.optJSONArray("all")
        if (all != null) {
            val url = item.optString("testUrl", "")
            val expected = item.optString("expectedStatus", "200-399").ifBlank { "200-399" }
            if (url.isNotBlank()) {
                for (i in 0 until all.length()) {
                    all.optString(i).takeIf { it.isNotBlank() }?.let { node ->
                        groupProbe.putIfAbsent(node, url to expected)
                    }
                }
            }
            continue
        }
        if (name == "GLOBAL") continue
        val type = item.optString("type", "").lowercase()
        if (type in setOf("direct", "reject", "rejectdrop", "pass", "compatible")) continue
        leaves += name
        latestDelay(item)?.takeIf { it > 0L }?.let { previous[name] = it }
    }

    // Use Mihomo's provider healthcheck first. This is the same provider-backed test path
    // used by manual per-node testing, but one request can refresh many node histories.
    coroutineScope {
        providers.filter { it.vehicleType.equals("HTTP", true) }.map { provider ->
            async {
                try { api.healthCheckProxyProvider(provider.name) }
                catch (cancel: CancellationException) { throw cancel }
                catch (_: Exception) { }
            }
        }.awaitAll()
    }

    val result = LinkedHashMap<String, Long>()
    repeat(5) { poll ->
        if (poll > 0) delay(180L)
        val fresh = try { api.proxies() } catch (_: Exception) { initial }
        leaves.forEach { node ->
            latestDelay(fresh.optJSONObject(node))?.takeIf { it > 0L }?.let { result[node] = it }
        }
        if (result.size >= leaves.size) return@repeat
    }

    // Only nodes whose provider healthcheck did not yield a fresh result fall back to the
    // exact same per-node delay endpoint used by the working "全部测速" button.
    val remaining = leaves.filter { (result[it] ?: -1L) <= 0L }
    for (chunk in remaining.chunked(12)) {
        coroutineScope {
            chunk.map { node ->
                async {
                    val provider = providerByNode[node]
                    val group = groupProbe[node]
                    val value = try {
                        when {
                            provider != null -> api.delay(node, provider.testUrl, provider.expectedStatus)
                            group != null -> api.delay(node, group.first, group.second)
                            else -> api.delay(node)
                        }
                    } catch (cancel: CancellationException) {
                        throw cancel
                    } catch (_: Exception) {
                        -1L
                    }
                    node to value
                }
            }.awaitAll().forEach { (node, value) ->
                if (value > 0L) result[node] = value
            }
        }
    }

    // Never manufacture a fresh timeout just because a batch path failed. Keep the most
    // recent positive Mihomo history; the UI leaves truly missing values unchanged.
    leaves.forEach { node ->
        if ((result[node] ?: -1L) <= 0L) previous[node]?.takeIf { it > 0L }?.let { result[node] = it }
    }
    result
}

suspend fun delay(node: String): Long = withContext(Dispatchers.IO) {
        val provider = parseProviders(api.proxyProviders()).firstOrNull {
            node in it.nodes && it.vehicleType.equals("HTTP", true)
        }
        if (provider != null) {
            try {
                return@withContext api.delay(node, provider.testUrl, provider.expectedStatus)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                try { api.healthCheckProxyProvider(provider.name) } catch (_: Exception) { }
                repeat(8) {
                    delay(350)
                    latestDelay(api.proxies().optJSONObject(node))?.let { return@withContext it }
                }
            }
        }

        val raw = api.proxies()
        val groupName = raw.keys().asSequence().firstOrNull { name ->
            val all = raw.optJSONObject(name)?.optJSONArray("all") ?: return@firstOrNull false
            (0 until all.length()).any { all.optString(it) == node }
        }
        val group = groupName?.let { raw.optJSONObject(it) }
        api.delay(node, group?.optString("testUrl", "") ?: "", group?.optString("expectedStatus", "200-399") ?: "200-399")
    }

    suspend fun siteLatencies(): Map<String, Long> = withContext(Dispatchers.IO) {
        val sites = listOf(
            "Baidu" to "https://www.baidu.com/",
            "Cloudflare" to "https://cp.cloudflare.com/generate_204",
            "Google" to "https://www.gstatic.com/generate_204",
        )
        coroutineScope {
            sites.map { (name, url) -> async { name to measureSiteLatency(url) } }.awaitAll().toMap()
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

    private fun latestDelay(node: JSONObject?): Long? {
        val history = node?.optJSONArray("history") ?: return null
        for (i in history.length() - 1 downTo 0) {
            val value = history.optJSONObject(i)?.optLong("delay", -1L) ?: -1L
            if (value > 0L) return value
        }
        return null
    }
}
