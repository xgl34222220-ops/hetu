package io.github.xgl34222220.hetu

import java.util.Locale

/** Pure UI data: never selects a node, rewrites a configuration or touches the proxy. */
internal fun projectStrategyNodes(nodes: List<ProxyNodeUi>, sort: String, descending: Boolean,
    delays: Map<String, Long>, providers: Map<String, String> = emptyMap()): List<ProxyNodeUi> {
    val values = if (providers.isEmpty()) nodes else nodes.map { node ->
        providers[node.name]?.takeIf(String::isNotBlank)?.let { node.copy(provider = it) } ?: node
    }
    return when (sort) {
        "name" -> values.sortedBy { it.name.lowercase(Locale.ROOT) }.let { if (descending) it.reversed() else it }
        "latency", "delay" -> values.sortedWith(compareBy<ProxyNodeUi> {
            if ((delays[it.name] ?: it.lastDelay ?: -1L) > 0L) 0 else 1
        }.thenBy {
            val value = (delays[it.name] ?: it.lastDelay ?: 0L).coerceAtLeast(0L)
            if (descending) -value else value
        }.thenBy { it.name.lowercase(Locale.ROOT) })
        // Config order stays config order, even if an earlier sort used descending.
        else -> values
    }
}

private fun strategyKey(value: String): String = "${value.length}:$value"
internal sealed class StrategyListEntry(val key: String, val type: String) {
    class Groups(val groups: List<ProxyGroupUi>) : StrategyListEntry(
        "groups:" + groups.joinToString("") { strategyKey(it.name) }, "strategy-groups")
    class Heading(val group: ProxyGroupUi, val count: Int) : StrategyListEntry(
        "heading:" + strategyKey(group.name), "strategy-heading")
    class Provider(val group: ProxyGroupUi, val name: String, val count: Int) : StrategyListEntry(
        "provider:" + strategyKey(group.name) + strategyKey(name), "strategy-provider")
    class Nodes(val group: ProxyGroupUi, val nodes: List<ProxyNodeUi>, val columns: Int) : StrategyListEntry(
        "nodes:" + strategyKey(group.name) + nodes.joinToString("") { strategyKey(it.name) }, "strategy-nodes")
}

/** Expanded children share ONE vertical lazy list with group headers. No nested full grid. */
internal fun strategyListEntries(groups: List<ProxyGroupUi>, groupColumns: Int,
    nodeColumns: Int, expanded: Set<String>, nodeSort: String, descending: Boolean,
    delays: Map<String, Long>, providers: Map<String, String>, groupByProvider: Boolean): List<StrategyListEntry> = buildList {
    val columns = nodeColumns.coerceIn(1, 3)
    groups.chunked(groupColumns.coerceIn(1, 3)).forEach { pair ->
        add(StrategyListEntry.Groups(pair))
        pair.filter { it.name in expanded }.forEach { group ->
            val ordered = projectStrategyNodes(group.nodes, nodeSort, descending, delays, providers)
            add(StrategyListEntry.Heading(group, ordered.size))
            val sections = if (groupByProvider) ordered.groupBy { it.provider.ifBlank { "配置内节点" } }
                else linkedMapOf("" to ordered)
            sections.forEach { (provider, members) ->
                if (provider.isNotBlank()) add(StrategyListEntry.Provider(group, provider, members.size))
                members.chunked(columns).forEach { add(StrategyListEntry.Nodes(group, it, columns)) }
            }
        }
    }
}
