package io.github.xgl34222220.hetu

import android.content.SharedPreferences
import androidx.compose.runtime.*
import kotlinx.coroutines.CancellationException
import java.net.URI
import java.util.Locale

/** Presentation settings reuse the existing keys. No runtime configuration is rewritten here. */
internal data class PanelOptions11(
    val showHidden: Boolean = false,
    val globalByMode: Boolean = true,
    val providers: Boolean = false,
    val collapsePrevious: Boolean = true,
    val sort: String = "config",
    val descending: Boolean = false,
    val columns: Int = 2,
    val compact: Boolean = false,
    val groupColumns: Int = 2,
    val groupCompact: Boolean = false,
    val nameOverflow: String = "clip",
) {
    companion object {
        fun read(p: SharedPreferences) = PanelOptions11(
            p.getBoolean("proxySelectorShowHidden", false),
            p.getBoolean("proxySelectorShowGlobalByMode", true),
            p.getBoolean("proxySelectorGroupByProvider", false),
            p.getBoolean("proxySelectorCollapsePrevious", true),
            p.getString("proxySelectorNodeSort", "config").orEmpty(),
            p.getBoolean("proxySelectorSortDescending", false),
            p.getInt("proxySelectorNodeColumns", 2).coerceIn(1, 2),
            p.getString("proxySelectorDensity", "standard") == "compact",
            p.getInt("proxySelectorGroupColumns", 2).coerceIn(1, 2),
            p.getString("proxySelectorGroupDensity", "standard") == "compact",
            p.getString("proxySelectorNameOverflow", "clip").orEmpty().ifBlank { "clip" },
        )
    }
    fun save(p: SharedPreferences) {
        p.edit().putBoolean("proxySelectorShowHidden", showHidden)
            .putBoolean("proxySelectorShowGlobalByMode", globalByMode)
            .putBoolean("proxySelectorGroupByProvider", providers)
            .putBoolean("proxySelectorCollapsePrevious", collapsePrevious)
            .putString("proxySelectorNodeSort", sort)
            .putBoolean("proxySelectorSortDescending", descending)
            .putInt("proxySelectorNodeColumns", columns.coerceIn(1, 2))
            .putString("proxySelectorDensity", if (compact) "compact" else "standard")
            .putInt("proxySelectorGroupColumns", groupColumns.coerceIn(1, 2))
            .putString("proxySelectorGroupDensity", if (groupCompact) "compact" else "standard")
            .putString("proxySelectorNameOverflow", nameOverflow.ifBlank { "clip" })
            .apply()
    }
}

@Composable
internal fun rememberPanelOptions11(prefs: SharedPreferences): PanelOptions11 {
    var value by remember(prefs) { mutableStateOf(PanelOptions11.read(prefs)) }
    DisposableEffect(prefs) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { p, key ->
            if (key?.startsWith("proxySelector") == true) value = PanelOptions11.read(p)
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return value
}

internal fun panelGroups11(groups: List<ProxyGroupUi>, query: String, mode: String,
    options: PanelOptions11): List<ProxyGroupUi> {
    val terms = query.trim().split(Regex("\\s+")).filter(String::isNotEmpty)
    return groups.filter { (options.showHidden || !it.hidden) &&
        (!options.globalByMode || !it.name.equals("GLOBAL", true) || mode.equals("global", true))
    }.mapNotNull { group ->
        val headerMatches = terms.all { group.name.contains(it, true) || group.now.contains(it, true) }
        if (terms.isEmpty() || headerMatches) group else {
            val matches = group.nodes.filter { node -> terms.all {
                node.name.contains(it, true) || node.provider.contains(it, true) || node.type.contains(it, true)
            } }
            if (matches.isEmpty()) null else group.copy(nodes = matches)
        }
    }
}

internal fun panelExpanded11(old: Set<String>, name: String, exclusive: Boolean): Set<String> =
    if (name in old) old - name else if (exclusive) setOf(name) else old + name

internal sealed class PanelEntry11(val key: String, val group: ProxyGroupUi) {
    class Header(group: ProxyGroupUi, val expanded: Boolean) : PanelEntry11("h:${group.name}", group)
    class Tools(group: ProxyGroupUi) : PanelEntry11("t:${group.name}", group)
    class Provider(group: ProxyGroupUi, val provider: String) : PanelEntry11("p:${group.name.length}:${group.name}:$provider", group)
    class Nodes(group: ProxyGroupUi, val nodes: List<ProxyNodeUi>, val columns: Int) :
        PanelEntry11("n:${group.name.length}:${group.name}:" + nodes.joinToString("") { "${it.name.length}:${it.name}" }, group)
    class End(group: ProxyGroupUi) : PanelEntry11("e:${group.name}", group)
}

/** One vertical lazy list: a thousand-node group does not compose a thousand tiles at once. */
internal fun panelEntries11(groups: List<ProxyGroupUi>, expanded: Set<String>, query: String,
    options: PanelOptions11, delays: Map<String, Long>, effectiveColumns: Int): List<PanelEntry11> = buildList {
    groups.forEach { group ->
        val open = group.name in expanded || query.isNotBlank()
        add(PanelEntry11.Header(group, open))
        if (open) {
            add(PanelEntry11.Tools(group))
            val nodes = projectStrategyNodes(group.nodes, options.sort, options.descending, delays)
            val sections = if (options.providers) nodes.groupBy { it.provider.ifBlank { "配置内节点" } }
                else linkedMapOf("" to nodes)
            sections.forEach { (provider, members) ->
                if (provider.isNotEmpty()) add(PanelEntry11.Provider(group, provider))
                members.chunked(effectiveColumns.coerceIn(1, 2)).forEach {
                    add(PanelEntry11.Nodes(group, it, effectiveColumns.coerceIn(1, 2)))
                }
            }
            add(PanelEntry11.End(group))
        }
    }
}

/** A check mark follows acknowledged state, never a click or a simulated timeout. */
@Stable
internal class PanelSelection11 {
    val pending = mutableStateMapOf<String, String>()
    private val confirmed = mutableStateMapOf<String, Pair<String, String>>()
    fun current(group: ProxyGroupUi): String = confirmed[group.name]?.first ?: group.now
    fun reconcile(groups: List<ProxyGroupUi>) {
        val byName = groups.associateBy { it.name }
        confirmed.keys.toList().forEach { name ->
            val g = byName[name]
            val (selected, before) = confirmed[name] ?: return@forEach
            if (g == null || g.now == selected || g.now != before) confirmed.remove(name)
        }
    }
    suspend fun select(group: ProxyGroupUi, node: String, action: suspend (String, String) -> String): Boolean {
        if (pending.containsKey(group.name) || node == current(group)) return false
        require(group.nodes.any { it.name == node }) { "节点已不在此策略组中，请刷新" }
        pending[group.name] = node
        try {
            val actual = action(group.name, node)
            check(actual == node) { "核心尚未确认所选节点，当前为 ${actual.ifBlank { "未知" }}" }
            confirmed[group.name] = node to group.now
            return true
        } catch (cancel: CancellationException) {
            throw cancel
        } finally { pending.remove(group.name) }
    }
}

internal data class PanelApiDraft11(
    val customDelay: Boolean, val delayUrl: String, val history: Boolean,
    val customApi: Boolean, val host: String, val port: String, val secret: String,
) {
    companion object {
        fun read(p: SharedPreferences) = PanelApiDraft11(
            p.getBoolean("proxyCustomDelayUrlEnabled", false),
            p.getString("proxyCustomDelayUrl", "https://www.gstatic.com/generate_204").orEmpty(),
            p.getBoolean("proxyApiHistoryEnabled", false),
            p.getBoolean("proxyCustomApiEnabled", false),
            p.getString("proxyCustomApiHost", "127.0.0.1").orEmpty(),
            p.getInt("proxyCustomApiPort", 9090).toString(),
            p.getString("proxyCustomApiSecret", "").orEmpty(),
        )
    }
    fun validationError(): String? {
        if (customDelay) {
            val u = runCatching { URI(delayUrl.trim()) }.getOrNull()
            if (u == null || u.scheme?.lowercase(Locale.ROOT) !in setOf("https", "http") ||
                u.host.isNullOrBlank() || u.rawUserInfo != null || delayUrl.length > 2048)
                return "测速 URL 必须是完整的 HTTP/HTTPS 地址，且不能包含账号密码"
        }
        if (customApi && (!host.trim().matches(Regex("[A-Za-z0-9.-]{1,253}")) ||
            host.trim().startsWith('.') || host.trim().endsWith('.'))) return "后端主机只填写 IPv4 或域名，不含协议和端口"
        val parsedPort = port.toIntOrNull()
        if (customApi && (parsedPort == null || parsedPort !in 1024..65535)) return "端口范围为 1024–65535"
        if ('\r' in secret || '\n' in secret) return "Secret 不能包含换行"
        return null
    }
    fun save(p: SharedPreferences) {
        require(validationError() == null) { validationError().orEmpty() }
        p.edit().putBoolean("proxyCustomDelayUrlEnabled", customDelay)
            .putString("proxyCustomDelayUrl", delayUrl.trim())
            .putBoolean("proxyApiHistoryEnabled", history)
            .putBoolean("proxyCustomApiEnabled", customApi)
            .putString("proxyCustomApiHost", host.trim())
            .putInt("proxyCustomApiPort", port.toIntOrNull()?.takeIf { it in 1024..65535 } ?: 9090)
            .putString("proxyCustomApiSecret", secret).apply()
    }
}
