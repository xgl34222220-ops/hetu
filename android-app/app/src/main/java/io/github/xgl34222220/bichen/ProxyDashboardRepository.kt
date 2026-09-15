package io.github.xgl34222220.bichen

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

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

/**
 * Panel-specific data access.  It deliberately follows Mihomo's provider APIs rather than
 * inventing a dashboard-side test URL: provider health checks use the exact URL/expected-status
 * from the running configuration and therefore match MetaCubeXD/Zashboard behaviour.
 */
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

    suspend fun providers(): List<DashboardProviderUi> = withContext(Dispatchers.IO) {
        parseProviders(api.proxyProviders())
    }

    suspend fun ruleSets(): List<DashboardRuleSetUi> = withContext(Dispatchers.IO) {
        parseRuleSets(api.ruleProviders())
    }

    suspend fun refreshSubscriptions(): List<DashboardProviderUi> = withContext(Dispatchers.IO) {
        val before = parseProviders(api.proxyProviders())
        for (chunk in before.filter { it.vehicleType.equals("HTTP", true) }.chunked(3)) {
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
        parseProviders(api.proxyProviders())
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

    /**
     * Global latency refresh uses provider health-checks first.  This is important: the provider
     * already knows its configured test URL and expected status, so the UI no longer forces every
     * node through one hard-coded URL (which was the reason some real devices showed all timeout).
     */
    suspend fun globalDelay(): Map<String, Long> = withContext(Dispatchers.IO) {
        val providerList = parseProviders(api.proxyProviders())
        if (providerList.isNotEmpty()) {
            for (chunk in providerList.chunked(3)) {
                coroutineScope {
                    chunk.map { provider ->
                        async {
                            try { api.healthCheckProxyProvider(provider.name) }
                            catch (cancel: CancellationException) { throw cancel }
                            catch (_: Exception) { }
                        }
                    }.awaitAll()
                }
            }
            val wanted = providerList.flatMapTo(LinkedHashSet()) { it.nodes }
            var result = LinkedHashMap<String, Long>()
            repeat(6) { round ->
                if (round > 0) delay(550)
                result = delaysFromProxies(api.proxies(), wanted)
                if (wanted.isNotEmpty() && result.values.count { it > 0L } >= (wanted.size * 3) / 4) return@withContext result
            }
            for (name in wanted) if (name !in result) result[name] = -1L
            return@withContext result
        }

        // Configs without proxy-providers still get group-native tests.
        val raw = api.proxies()
        val result = LinkedHashMap<String, Long>()
        val groups = raw.keys().asSequence().mapNotNull { name ->
            val obj = raw.optJSONObject(name) ?: return@mapNotNull null
            val all = obj.optJSONArray("all") ?: return@mapNotNull null
            Triple(name, obj, all)
        }.sortedByDescending { it.third.length() }.toList()
        for ((name, obj, _) in groups.take(8)) {
            try {
                val measured = api.groupDelay(name, obj.optString("testUrl", ""), obj.optString("expectedStatus", "200-399"))
                val keys = measured.keys()
                while (keys.hasNext()) {
                    val node = keys.next()
                    val value = measured.optLong(node, -1L)
                    if (value > 0L) result[node] = value
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) { }
        }
        result
    }

    /** Test one visible node with its provider/group native URL. */
    suspend fun delay(node: String): Long = withContext(Dispatchers.IO) {
        val provider = parseProviders(api.proxyProviders()).firstOrNull { node in it.nodes }
        if (provider != null) {
            try {
                return@withContext api.delay(node, provider.testUrl, provider.expectedStatus)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                // Provider healthcheck is the authoritative fallback and updates node history.
                try { api.healthCheckProxyProvider(provider.name) } catch (_: Exception) { }
                repeat(5) {
                    delay(450)
                    val value = latestDelay(api.proxies().optJSONObject(node))
                    if (value != null) return@withContext value
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
                val value = proxies.opt(i)
                when (value) {
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
                updatedAt = p.optString("updatedAt", ""),
                upload = infoLong("Upload", "upload"),
                download = infoLong("Download", "download"),
                total = infoLong("Total", "total"),
                expire = infoLong("Expire", "expire"),
                nodes = nodes,
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
                updatedAt = p.optString("updatedAt", ""),
            )
        }
        return out.sortedBy { it.name.lowercase() }
    }

    private fun normalizeRuleFormat(raw: String): String = when (raw.lowercase()) {
        "mrsrule", "mrs" -> "MRS"
        "textrule", "text" -> "TEXT"
        "yamlrule", "yaml" -> "YAML"
        else -> raw.uppercase().ifBlank { "RULE" }
    }

    private fun delaysFromProxies(root: JSONObject, wanted: Set<String>): LinkedHashMap<String, Long> {
        val out = LinkedHashMap<String, Long>()
        for (name in wanted) latestDelay(root.optJSONObject(name))?.let { out[name] = it }
        return out
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
