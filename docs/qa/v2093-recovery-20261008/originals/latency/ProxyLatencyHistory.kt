package io.github.xgl34222220.hetu

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/** Mihomo exposes dynamic provider nodes outside /proxies. Keep static/group precedence. */
internal fun mergeProxySnapshots(proxies: JSONObject, providerResponse: JSONObject): JSONObject {
    val merged = JSONObject()
    val providers = providerResponse.optJSONObject("providers") ?: JSONObject()
    providers.keys().forEach { providerName ->
        val entries = providers.optJSONObject(providerName)?.optJSONArray("proxies") ?: return@forEach
        for (index in 0 until entries.length()) {
            val entry = entries.optJSONObject(index) ?: continue
            val name = entry.optString("name")
            if (name.isBlank()) continue
            val copy = JSONObject(entry.toString())
            if (copy.optString("provider-name").isBlank()) copy.put("provider-name", providerName)
            merged.put(name, copy)
        }
    }
    proxies.keys().forEach { name -> merged.put(name, proxies.opt(name)) }
    return merged
}

internal data class CoreLatencySample(val delay: Long, val timestamp: Long, val testUrl: String = "")

/** Last actual sample, including failures. Never revive an old success after a new failure. */
private fun lastHistorySample(history: JSONArray?, url: String = ""): CoreLatencySample? {
    if (history == null) return null
    for (index in history.length() - 1 downTo 0) {
        val item = history.optJSONObject(index) ?: continue
        if (!item.has("delay") || item.isNull("delay")) continue
        val value = item.optLong("delay", Long.MIN_VALUE)
        if (value < 0L) continue
        val at = runCatching { Instant.parse(item.optString("time")).toEpochMilli() }.getOrDefault(0L)
        // A history entry records probe failure, not whether it specifically timed out.
        return CoreLatencySample(if (value > 0L) value else -2L, at, url)
    }
    return null
}

internal fun coreLatencySample(node: JSONObject?, preferredUrl: String = ""): CoreLatencySample? {
    if (node == null) return null
    val extra = node.optJSONObject("extra")
    val preferred = if (preferredUrl.isNotBlank())
        lastHistorySample(extra?.optJSONObject(preferredUrl)?.optJSONArray("history"), preferredUrl) else null
    val ordinary = lastHistorySample(node.optJSONArray("history"))
    val ipv6 = lastHistorySample(extra?.optJSONObject(MihomoControllerClient.IPV6_DELAY_URL)?.optJSONArray("history"))
    // Capability probes share Mihomo's root history; an IPv6-only failure is not an ordinary latency failure.
    val rootIsIpv6 = ordinary != null && ordinary.timestamp > 0L && ipv6?.timestamp == ordinary.timestamp
    var newest = if (rootIsIpv6) null else ordinary
    extra?.keys()?.forEach { url ->
        if (url == MihomoControllerClient.IPV6_DELAY_URL) return@forEach
        val sample = lastHistorySample(extra.optJSONObject(url)?.optJSONArray("history"), url) ?: return@forEach
        // Root history is authoritative when timestamps tie; extra is needed for URL-specific data.
        if (newest == null || sample.timestamp > newest!!.timestamp) newest = sample
    }
    // A custom URL can fall back to the provider URL. Its old history cannot pin a newer fallback result.
    if (preferred != null && (newest == null || preferred.timestamp >= newest!!.timestamp)) return preferred
    return newest
}

/** Follow nested selected groups exactly like WebUI; cycle/missing selections remain untested. */
internal fun selectedProxyName(proxies: JSONObject, name: String): String {
    val visited = HashSet<String>()
    var current = name
    while (visited.add(current)) {
        val node = proxies.optJSONObject(current) ?: return current
        if (node.optJSONArray("all") == null) return current
        val selected = node.optString("now")
        if (selected.isBlank() || selected == current) return current
        current = selected
    }
    return name
}

internal fun resolvedCoreLatency(proxies: JSONObject, name: String, preferredUrl: String = ""): CoreLatencySample? {
    val leaf = selectedProxyName(proxies, name)
    return coreLatencySample(proxies.optJSONObject(leaf), preferredUrl)
        ?: coreLatencySample(proxies.optJSONObject(name), preferredUrl)
}

/** A new successful core/WebUI reading must replace an old locally cached failure. */
internal fun syncCoreLatencyResults(groups: List<ProxyGroupUi>, delays: MutableMap<String, Long>,
    measuredAt: Map<String, Long> = emptyMap(), snapshotStartedAt: Long = Long.MAX_VALUE,
    protectedNodes: Set<String> = emptySet()) {
    val nodes = groups.flatMap { it.nodes }.groupBy { it.name }
    nodes.forEach { (name, copies) ->
        if (name in protectedNodes || (measuredAt[name] ?: Long.MIN_VALUE) > snapshotStartedAt) return@forEach
        copies.filter { it.lastDelay != null }.maxByOrNull { it.lastDelayAt }?.lastDelay?.let { delays[name] = it }
    }
    if (groups.isNotEmpty()) delays.keys.toList().filter { it !in nodes }.forEach(delays::remove)
}
