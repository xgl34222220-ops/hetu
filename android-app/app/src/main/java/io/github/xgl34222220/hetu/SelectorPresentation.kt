package io.github.xgl34222220.hetu

import android.content.Context
import androidx.compose.runtime.mutableStateMapOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** UI projections never rewrite the user's configuration or select nodes implicitly. */
internal object SelectorPresentation {
    fun visibleGroups(groups: List<ProxyGroupUi>, showHidden: Boolean, globalByMode: Boolean, mode: String): List<ProxyGroupUi> =
        groups.filter { (!it.hidden || showHidden) && (it.name != "GLOBAL" || !globalByMode || mode.equals("global", true)) }

    fun nodes(group: ProxyGroupUi, sort: String, descending: Boolean, delays: Map<String, Long>, providers: Map<String, String> = emptyMap()): List<ProxyNodeUi> {
        return projectStrategyNodes(group.nodes, sort, descending, delays, providers)
    }

    fun toggleExpanded(current: List<String>, name: String, collapsePrevious: Boolean): List<String> =
        if (name in current) current - name else if (collapsePrevious) listOf(name) else current + name
}

internal object SelectorIpv6Probe {
    val results = mutableStateMapOf<String, Boolean>()
    suspend fun measure(context: Context, node: String, repository: ProxyDashboardRepository = ProxyDashboardRepository(context)) {
        if (!context.getSharedPreferences("hetu", 0).getBoolean("proxySelectorDetectIpv6", true)) return
        val connected = withContext(Dispatchers.IO) {
            try { repository.ipv6Delay(node) > 0 }
            catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { false }
        }
        withContext(Dispatchers.Main) {
            if (results.size >= 512) results.remove(results.keys.first())
            results[node] = connected
        }
    }
}

internal suspend fun selectorProviderNames(context: Context): Map<String, String> = withContext(Dispatchers.IO) {
    val providers = MihomoControllerClient(context).proxyProviders().optJSONObject("providers") ?: return@withContext emptyMap()
    buildMap {
        providers.keys().forEach { name ->
            val nodes = providers.optJSONObject(name)?.optJSONArray("proxies") ?: return@forEach
            for (index in 0 until nodes.length()) {
                val node = nodes.optJSONObject(index)?.optString("name") ?: nodes.optString(index)
                if (node.isNotBlank() && !containsKey(node)) put(node, name)
            }
        }
    }
}
