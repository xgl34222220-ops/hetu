package io.github.xgl34222220.hetu

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.xgl34222220.hetu.ui.*

/** Native presentation inspired by zashboard's mobile groups and separate latency target. */
internal fun strategyPanelNodes(nodes: List<ProxyNodeUi>, delays: Map<String, Long>, query: String,
    sort: String, testedOnly: Boolean, descending: Boolean = false): List<ProxyNodeUi> {
    val terms = query.trim().split(Regex("\\s+")).filter(String::isNotEmpty)
    val filtered = nodes.filter { node ->
        terms.all { node.name.contains(it, true) || node.provider.contains(it, true) } &&
            (!testedOnly || (delays[node.name] ?: node.lastDelay ?: -1L) > 0L)
    }
    return when (sort) {
        "name" -> filtered.sortedBy { it.name.lowercase(java.util.Locale.ROOT) }.let { if (descending) it.reversed() else it }
        "delay" -> filtered.sortedWith(compareBy<ProxyNodeUi> {
            if ((delays[it.name] ?: it.lastDelay ?: -1L) > 0L) 0 else 1
        }.thenBy {
            val value = (delays[it.name] ?: it.lastDelay ?: -1L).coerceAtLeast(0L)
            if (descending) -value else value
        }.thenBy { it.name })
        else -> filtered
    }
}

@Composable
internal fun StrategyNodePanel(
    group: ProxyGroupUi, selected: String, delays: Map<String, Long>, testing: Map<String, Boolean>,
    pending: String?, error: String, groupNames: Set<String>, canGoBack: Boolean,
    onSelect: (String) -> Unit, onDelay: (String) -> Unit, onTestAll: () -> Unit,
    onOpenGroup: (String) -> Unit, onBack: () -> Unit, onClose: () -> Unit,
    modifier: Modifier = Modifier,
    initialSort: String = "config", initialDescending: Boolean = false,
    initialGrid: Boolean = false, groupByProvider: Boolean = false,
) {
    val t = LocalHetuTokens.current
    var query by rememberSaveable(group.name) { mutableStateOf("") }
    var sort by rememberSaveable(group.name) { mutableStateOf(initialSort) }
    var descending by rememberSaveable(group.name) { mutableStateOf(initialDescending) }
    var testedOnly by rememberSaveable(group.name) { mutableStateOf(false) }
    var grid by rememberSaveable(group.name) { mutableStateOf(initialGrid) }
    val nodes = strategyPanelNodes(group.nodes, delays, query, sort, testedOnly, descending)
    val passed = group.nodes.count { (delays[it.name] ?: it.lastDelay ?: -1L) > 0L }
    val busy = group.nodes.any { testing[it.name] == true }
    val listState = rememberLazyListState()
    LaunchedEffect(group.name, query, sort, descending, testedOnly, grid) { listState.scrollToItem(0) }

    Column(modifier.testTag("strategy-node-panel").padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (canGoBack) IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回上级策略", Modifier.size(20.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(group.name, Modifier.testTag("panel-group-name"), color = t.textPrimary,
                    fontSize = 21.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold)
                Text("${liquidGroupType(group.type)} · $passed/${group.nodes.size} 测速通过",
                    color = t.textSecondary, fontSize = 11.sp, lineHeight = 17.sp)
            }
            IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, "关闭节点面板", Modifier.size(20.dp)) }
        }
        Text("当前 · ${selected.ifBlank { "未选择" }}", Modifier.testTag("panel-current-node"),
            color = MaterialTheme.colorScheme.primary, fontSize = 12.sp, lineHeight = 18.sp)
        LiquidGlassTextField(value = query, onValueChange = { query = it },
            label = if (group.nodes.any { it.provider.isNotBlank() }) "搜索节点或订阅" else "搜索节点",
            modifier = Modifier.fillMaxWidth().testTag("panel-node-search"), leadingIcon = Icons.Rounded.Search)
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)) {
            LiquidChoicePill(when (sort) { "delay" -> "按延迟"; "name" -> "按名称"; else -> "配置顺序" }, sort != "config", {
                sort = when (sort) { "config" -> "delay"; "delay" -> "name"; else -> "config" }
            })
            if (sort != "config") LiquidChoicePill(if (descending) "降序 ↓" else "升序 ↑", descending, { descending = !descending })
            LiquidChoicePill("测速通过", testedOnly, { testedOnly = !testedOnly })
            LiquidChoicePill(if (grid) "双列" else "列表", grid, { grid = !grid })
            TextButton(onClick = onTestAll, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) {
                if (busy) HetuBusyIndicator(Modifier.size(14.dp))
                else Icon(Icons.Rounded.Speed, null, Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(if (busy) "测速中" else "全部测速", fontSize = 12.sp)
            }
        }
        if (error.isNotBlank()) Text(error, Modifier.testTag("panel-error"), color = t.danger,
            fontSize = 12.sp, lineHeight = 18.sp)
        Text("${nodes.size} 个节点 · 长按名称查看全文", color = t.textMuted, fontSize = 11.sp, lineHeight = 16.sp)
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val columns = if (grid && maxWidth / LocalDensity.current.fontScale >= 280.dp) 2 else 1
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize().testTag("panel-node-list"),
                contentPadding = PaddingValues(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (nodes.isEmpty()) item {
                    Text(if (testedOnly) "没有符合条件的已测速节点" else "没有匹配的节点",
                        Modifier.fillMaxWidth().padding(vertical = 28.dp), color = t.textSecondary)
                }
                val sections = if (groupByProvider) nodes.groupBy { it.provider.ifBlank { "未分组" } } else mapOf("" to nodes)
                sections.forEach { (provider, members) ->
                if (provider.isNotBlank()) item(key = "provider:$provider") {
                    Text("$provider · ${members.size}", Modifier.padding(top = 8.dp, bottom = 4.dp),
                        color = t.textSecondary, fontSize = 12.sp, lineHeight = 18.sp)
                }
                items(members.chunked(columns), key = { row -> "node:${row.first().name}" }) { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { node ->
                            StrategyNodeRow(node, node.name == selected, delays[node.name] ?: node.lastDelay,
                                testing[node.name] == true, pending == node.name, pending == null,
                                node.name in groupNames && node.name != group.name, columns == 2,
                                onSelect = { onSelect(node.name) }, onDelay = { onDelay(node.name) },
                                onOpenGroup = { onOpenGroup(node.name) }, modifier = Modifier.weight(1f))
                        }
                        if (row.size < columns) Spacer(Modifier.weight(1f))
                    }
                }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun StrategyNodeRow(node: ProxyNodeUi, active: Boolean, value: Long?, testing: Boolean,
    pending: Boolean, enabled: Boolean, nested: Boolean, compact: Boolean,
    onSelect: () -> Unit, onDelay: () -> Unit, onOpenGroup: () -> Unit, modifier: Modifier) {
    val t = LocalHetuTokens.current
    var fullName by rememberSaveable(node.name) { mutableStateOf(false) }
    val motion = LocalHetuMotionEnabled.current
    val fill by animateColorAsState(if (active) MaterialTheme.colorScheme.primary.copy(alpha = .09f) else t.controlBackground.copy(alpha = .35f),
        tween(if (motion) 220 else 0), label = "nodeFill")
    Column(modifier.clip(RoundedCornerShape(18.dp)).background(fill)
        .combinedClickable(enabled = enabled, role = Role.RadioButton, onClick = onSelect,
            onLongClickLabel = if (fullName) "收起节点名称" else "显示完整节点名称", onLongClick = { fullName = !fullName })
        .semantics { selected = active }.testTag("panel-node:${node.name}")
        .padding(start = 12.dp, end = 6.dp, top = 6.dp, bottom = 6.dp)) {
        @Composable fun name(modifier: Modifier) {
            Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(node.name, Modifier.testTag("panel-node-name:${node.name}"), color = t.textPrimary,
                    fontSize = 13.sp, lineHeight = 20.sp, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                    maxLines = if (fullName) Int.MAX_VALUE else 2, overflow = TextOverflow.Ellipsis)
                Text(listOf(liquidNodeProtocolLabel(node), if (node.udp) "UDP" else "TCP", node.provider)
                    .filter(String::isNotBlank).distinct().joinToString(" · "), color = t.textSecondary,
                    fontSize = 10.sp, lineHeight = 16.sp, maxLines = if (fullName) Int.MAX_VALUE else 1, overflow = TextOverflow.Ellipsis)
            }
        }
        @Composable fun accessories() {
            if (pending) HetuBusyIndicator(Modifier.size(16.dp))
            else if (active) Icon(Icons.Rounded.CheckCircle, "已选择", Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
            if (nested) IconButton(onClick = onOpenGroup, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Rounded.ChevronRight, "查看${node.name}子策略", Modifier.size(18.dp), tint = t.textSecondary)
            }
            LatencyChip(value, testing, onClick = onDelay, compact = true,
                modifier = Modifier.testTag("panel-node-delay:${node.name}"))
        }
        if (compact) {
            name(Modifier.fillMaxWidth())
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) { accessories() }
        } else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            name(Modifier.weight(1f)); accessories()
        }
    }
}
