package io.github.xgl34222220.hetu

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material.icons.rounded.Sort
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.util.Locale

private val NodeSorts = listOf("config" to "配置顺序", "delay" to "延迟优先", "name" to "名称")

@Composable
internal fun ProxiesScreen(vm: HetuViewModel, bottomPadding: Dp) {
    val c = Hx.colors
    val state = vm.state
    var expanded by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var sort by rememberSaveable { mutableStateOf(vm.prefs.getString("hetuNodeSort", "config") ?: "config") }
    var showSort by remember { mutableStateOf(false) }

    val groups = state.groups.filter { !it.hidden }
    val q = query.trim()
    val visibleGroups = if (q.isBlank()) groups else groups.mapNotNull { group ->
        val nameHit = group.name.contains(q, ignoreCase = true)
        val nodes = group.nodes.filter { it.name.contains(q, ignoreCase = true) || it.type.contains(q, ignoreCase = true) }
        when {
            nameHit -> group
            nodes.isNotEmpty() -> group.copy(nodes = nodes)
            else -> null
        }
    }

    HxPage(
        title = "代理",
        subtitle = if (state.running) "${groups.size} 个策略组 · ${groups.sumOf { it.nodes.size }} 个节点" else null,
        bottomPadding = bottomPadding,
        actions = {
            if (state.running) {
                HxBarAction(if (searching) Icons.Rounded.SearchOff else Icons.Rounded.Search, "搜索", onClick = {
                    searching = !searching
                    if (!searching) query = ""
                })
                HxBarAction(Icons.Rounded.Sort, "排序", onClick = { showSort = true })
                HxBarAction(Icons.Rounded.Speed, "全部测速", onClick = vm::testAll, busy = vm.testingAll)
            }
        },
    ) {
        if (!state.running) {
            item(key = "stopped") {
                HxEmpty(Icons.Rounded.Hub, "代理未运行", "启动后即可查看策略组、切换节点和测速") {
                    HxButton("启动代理", onClick = vm::toggle, icon = Icons.Rounded.PowerSettingsNew, busy = vm.operation != null)
                }
            }
            return@HxPage
        }
        if (searching) {
            item(key = "search") {
                HxSearchField(query, { query = it }, "搜索策略组或节点", Modifier.padding(horizontal = Hx.gutter).padding(bottom = 12.dp))
            }
        }
        if (visibleGroups.isEmpty()) {
            item(key = "empty") {
                if (!state.panelReady && groups.isEmpty()) {
                    Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) { HxSpinner(28.dp) }
                } else {
                    HxEmpty(Icons.Rounded.SearchOff, if (q.isBlank()) "没有策略组" else "没有匹配的节点")
                }
            }
        }
        visibleGroups.forEach { group ->
            val open = q.isNotBlank() || group.name in expanded
            item(key = "g:${group.name}") {
                GroupHeader(
                    vm = vm,
                    group = group,
                    open = open,
                    modifier = Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null),
                    onToggle = {
                        expanded = if (group.name in expanded) expanded - group.name else expanded + group.name
                    },
                )
            }
            if (open) {
                val nodes = sortNodes(group.nodes, sort, vm)
                val rows = nodes.chunked(2)
                items(rows.size, key = { index -> "n:${group.name}:$index:${rows[index].first().name}" }) { index ->
                    val row = rows[index]
                    Row(
                        Modifier
                            .animateItem(fadeInSpec = tween(HxMotion.Medium), placementSpec = null, fadeOutSpec = null)
                            .fillMaxWidth()
                            .padding(horizontal = Hx.gutter)
                            .padding(bottom = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        row.forEach { node ->
                            NodeTile(vm, group, node, Modifier.weight(1f))
                        }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
                item(key = "gap:${group.name}") { Spacer(Modifier.height(10.dp)) }
            }
        }
    }

    if (showSort) {
        HxChoiceSheet(
            title = "节点排序",
            choices = NodeSorts.map { HxChoice(it.first, it.second) },
            selected = sort,
            onPick = {
                sort = it
                vm.prefs.edit().putString("hetuNodeSort", it).apply()
                showSort = false
            },
            onDismiss = { showSort = false },
        )
    }
}

private fun sortNodes(nodes: List<ProxyNodeUi>, sort: String, vm: HetuViewModel): List<ProxyNodeUi> = when (sort) {
    "name" -> nodes.sortedBy { it.name.lowercase(Locale.ROOT) }
    "delay" -> nodes.sortedBy { node ->
        val d = vm.delays[node.name] ?: node.lastDelay
        if (d == null || d <= 0L) Long.MAX_VALUE else d
    }
    else -> nodes
}

@Composable
private fun GroupHeader(vm: HetuViewModel, group: ProxyGroupUi, open: Boolean, modifier: Modifier, onToggle: () -> Unit) {
    val c = Hx.colors
    val rotation by animateFloatAsState(if (open) 180f else 0f, tween(HxMotion.Medium, easing = HxMotion.Emphasized), label = "chevron")
    val testing = vm.testingGroups[group.name] == true
    val pending = vm.pendingSelection[group.name]
    val nowDelay = vm.delays[group.now]
    val source = remember { MutableInteractionSource() }
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Hx.gutter)
            .padding(bottom = if (open) 10.dp else 10.dp)
            .hxPressScale(source, .985f),
        shape = Hx.cardShape,
        color = c.surface,
        border = if (c.dark) BorderStroke(0.5.dp, c.line) else null,
    ) {
        Row(
            Modifier
                .clickable(interactionSource = source, indication = LocalIndication.current, onClick = onToggle)
                .padding(start = 14.dp, end = 6.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(38.dp).clip(RoundedCornerShape(12.dp)).background(c.surfaceMuted), contentAlignment = Alignment.Center) {
                HxGroupIcon(group, Modifier.size(24.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(group.name, style = MaterialTheme.typography.titleMedium, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    Spacer(Modifier.width(6.dp))
                    HxPill(HxFormat.groupType(group.type))
                }
                Spacer(Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (pending != null) {
                        HxSpinner(11.dp)
                        Spacer(Modifier.width(6.dp))
                        Text("切换到 $pending…", style = MaterialTheme.typography.bodySmall, color = c.accent, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    } else {
                        Text(
                            group.now.ifBlank { "未选择" },
                            style = MaterialTheme.typography.bodySmall,
                            color = c.textMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        if (nowDelay != null) {
                            Spacer(Modifier.width(6.dp))
                            Text(
                                HxFormat.delay(nowDelay) + if (nowDelay > 0) "ms" else "",
                                style = MaterialTheme.typography.labelSmall.merge(HxNumberStyle),
                                color = HxFormat.delayColor(nowDelay),
                            )
                        }
                    }
                }
            }
            Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).clickable(enabled = !testing) { vm.testGroup(group) }, contentAlignment = Alignment.Center) {
                if (testing) HxSpinner(16.dp) else Icon(Icons.Rounded.Bolt, "测速 ${group.name}", tint = c.textMuted, modifier = Modifier.size(20.dp))
            }
            Icon(
                Icons.Rounded.ExpandMore,
                contentDescription = if (open) "收起" else "展开",
                tint = c.textFaint,
                modifier = Modifier.size(24.dp).graphicsLayer { rotationZ = rotation },
            )
            Spacer(Modifier.width(6.dp))
        }
    }
}

@Composable
private fun NodeTile(vm: HetuViewModel, group: ProxyGroupUi, node: ProxyNodeUi, modifier: Modifier) {
    val c = Hx.colors
    val selectable = HxFormat.isSelectable(group.type)
    val pending = vm.pendingSelection[group.name]
    val selected = if (pending != null) pending == node.name else group.now == node.name
    val delay = vm.delays[node.name] ?: node.lastDelay
    val testing = vm.testingNodes[node.name] == true
    val bg by animateColorAsState(if (selected) c.accentSoft else c.surface, tween(HxMotion.Medium), label = "nodeBg")
    val border by animateColorAsState(if (selected) c.accent else if (c.dark) c.line else Color.Transparent, tween(HxMotion.Medium), label = "nodeBorder")
    val source = remember { MutableInteractionSource() }
    Column(
        modifier
            .hxPressScale(source, .96f)
            .clip(Hx.rowShape)
            .background(bg)
            .border(if (selected) 1.dp else 0.5.dp, border, Hx.rowShape)
            .clickable(interactionSource = source, indication = LocalIndication.current, enabled = selectable && pending == null && !selected) {
                vm.select(group.name, node.name)
            }
            .heightIn(min = 64.dp)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Text(
                node.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = c.text,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            AnimatedVisibility(selected, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                Icon(Icons.Rounded.Check, null, tint = c.accent, modifier = Modifier.padding(start = 4.dp).size(16.dp))
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                listOf(node.type, if (node.udp) "UDP" else "").filter { it.isNotBlank() }.joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = c.textFaint,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            Box(
                Modifier
                    .clip(Hx.chipShape)
                    .clickable(enabled = !testing) { vm.testNode(node.name) }
                    .padding(horizontal = 6.dp, vertical = 2.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (testing) HxSpinner(12.dp)
                else Text(
                    if (delay != null && delay > 0) "${delay}ms" else HxFormat.delay(delay).let { if (it == "—") "测速" else it },
                    style = MaterialTheme.typography.labelMedium.merge(HxNumberStyle),
                    color = if (delay == null) c.textFaint else HxFormat.delayColor(delay),
                )
            }
        }
    }
}
