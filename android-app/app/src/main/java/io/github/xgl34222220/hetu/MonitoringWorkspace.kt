package io.github.xgl34222220.hetu

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.xgl34222220.hetu.ui.*

/** The count is controller rule rows, never a sum of overlapping rule-provider entries. */
internal fun overviewRouteCounts(items: List<ProxyConnectionUi>): List<Pair<String, Int>> = items
    .groupingBy { it.chain.substringAfterLast(" → ").trim().ifBlank { it.rule.ifBlank { "未标记" } } }
    .eachCount().entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
    .map { it.key to it.value }

@Composable
internal fun OverviewInstruments(state: ProxyComposeState, ruleCount: Int?) {
    val t = LocalHetuTokens.current
    val primary = MaterialTheme.colorScheme.primary
    val ready = state.running && state.panelReady
    Column(Modifier.fillMaxWidth().crystalMaterial(RoundedCornerShape(22.dp)).padding(16.dp).testTag("overview-instruments"),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("运行概况", color = t.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("策略" to if (ready) state.groups.size.toString() else "—",
                "规则" to if (ready) ruleCount?.toString().orEmpty().ifEmpty { "—" } else "—",
                "当前连接" to if (ready) state.connections.size.toString() else "—").forEach { (label, value) ->
                Column(Modifier.weight(1f).testTag("overview:$label"), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    HetuNumber(value, style = MaterialTheme.typography.titleLarge.copy(fontSize = 23.sp, lineHeight = 29.sp), color = primary)
                    Text(label, color = t.textSecondary, fontSize = 12.sp, lineHeight = 18.sp)
                }
            }
        }
        if (!ready || ruleCount == null) Text(if (!ready) "等待核心状态" else "规则数量暂未读取", color = t.textSecondary, fontSize = 12.sp, lineHeight = 18.sp)
    }
}

private data class OverviewRouteAggregate(
    val name: String,
    val count: Int,
    val upload: Long,
    val download: Long,
)

private fun overviewRouteAggregates(items: List<ProxyConnectionUi>): List<OverviewRouteAggregate> {
    val map = LinkedHashMap<String, LongArray>()
    items.forEach { item ->
        val name = item.chain.substringAfterLast(" → ").trim().ifBlank { item.rule.ifBlank { "未标记" } }
        val values = map.getOrPut(name) { longArrayOf(0L, 0L, 0L) }
        values[0] += 1L
        values[1] += item.upload.coerceAtLeast(0L)
        values[2] += item.download.coerceAtLeast(0L)
    }
    return map.map { (name, values) ->
        OverviewRouteAggregate(name, values[0].toInt(), values[1], values[2])
    }.sortedWith(compareByDescending<OverviewRouteAggregate> { it.count }.thenBy { it.name })
}

private fun overviewRouteIcon(name: String): ImageVector {
    val lower = name.lowercase()
    return when {
        name.equals("DIRECT", true) -> Icons.Rounded.Route
        name.startsWith("REJECT", true) -> Icons.Rounded.Block
        "fallback" in lower || "故障" in name -> Icons.Rounded.SyncAlt
        "google" in lower || "github" in lower || "youtube" in lower -> Icons.Rounded.Public
        "ai" in lower || "openai" in lower -> Icons.Rounded.SmartToy
        else -> Icons.Rounded.Hub
    }
}

private fun overviewRouteAccent(name: String, primary: Color): Color {
    val lower = name.lowercase()
    return when {
        name.equals("DIRECT", true) -> Color(0xFF10B981)
        name.startsWith("REJECT", true) -> Color(0xFFF43F5E)
        "fallback" in lower || "故障" in name -> Color(0xFFF59E0B)
        "google" in lower || "github" in lower || "youtube" in lower -> Color(0xFF2563EB)
        "ai" in lower || "openai" in lower -> Color(0xFF10A37F)
        else -> primary
    }
}

@Composable
internal fun OverviewRouteRanking(state: ProxyComposeState) {
    val t = LocalHetuTokens.current
    val primary = MaterialTheme.colorScheme.primary
    val aggregates = remember(state.connections) { overviewRouteAggregates(state.connections) }
    var previousTotals by remember { mutableStateOf<Map<String, Pair<Long, Long>>>(emptyMap()) }
    var rates by remember { mutableStateOf<Map<String, Pair<Long, Long>>>(emptyMap()) }
    var lastSampleAt by remember { mutableLongStateOf(0L) }

    LaunchedEffect(aggregates) {
        val now = SystemClock.elapsedRealtime()
        val elapsed = (now - lastSampleAt).coerceAtLeast(1L)
        if (lastSampleAt > 0L) {
            rates = aggregates.associate { row ->
                val previous = previousTotals[row.name]
                val uploadRate = if (previous != null && row.upload >= previous.first) {
                    (row.upload - previous.first) * 1000L / elapsed
                } else 0L
                val downloadRate = if (previous != null && row.download >= previous.second) {
                    (row.download - previous.second) * 1000L / elapsed
                } else 0L
                row.name to (uploadRate.coerceAtLeast(0L) to downloadRate.coerceAtLeast(0L))
            }
        }
        previousTotals = aggregates.associate { it.name to (it.upload to it.download) }
        lastSampleAt = now
    }

    val rows = aggregates.take(5)
    Column(
        Modifier.fillMaxWidth().crystalMaterial(RoundedCornerShape(22.dp)).padding(16.dp).testTag("overview-ranking"),
        verticalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("实时排行", color = t.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text("按当前连接数排序 · 吞吐为相邻采样的真实增量", color = t.textSecondary, fontSize = 11.sp, lineHeight = 16.sp)
            }
            Surface(shape = CircleShape, color = primary.copy(alpha = .08f)) {
                Text(
                    "${state.connections.size} 连接",
                    color = primary,
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }
        if (!state.running || !state.panelReady || rows.isEmpty()) {
            Text("暂无活动连接", color = t.textSecondary, fontSize = 12.sp)
        } else {
            rows.forEachIndexed { index, row ->
                val accent = overviewRouteAccent(row.name, primary)
                val rate = rates[row.name] ?: (0L to 0L)
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 50.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Box(
                        Modifier.size(30.dp).background(accent.copy(alpha = .10f), RoundedCornerShape(10.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(overviewRouteIcon(row.name), null, Modifier.size(17.dp), tint = accent)
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(row.name, color = t.textPrimary, fontSize = 12.5.sp, lineHeight = 17.sp,
                            fontWeight = FontWeight.SemiBold, maxLines = 1)
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("↓ ${refSpeed(rate.second)}", color = t.textSecondary, fontSize = 10.5.sp, lineHeight = 15.sp)
                            Text("↑ ${refSpeed(rate.first)}", color = t.textSecondary, fontSize = 10.5.sp, lineHeight = 15.sp)
                        }
                    }
                    Surface(shape = CircleShape, color = t.controlBackground.copy(alpha = .72f)) {
                        Text(
                            "${row.count}",
                            color = t.textPrimary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                    }
                }
                InstrumentProgress(
                    row.count.toFloat() / state.connections.size.coerceAtLeast(1),
                    Modifier.testTag("route-count:${row.name}"),
                    accent = accent,
                )
                if (index != rows.lastIndex) HorizontalDivider(color = t.textMuted.copy(alpha = .10f), thickness = .5.dp)
            }
        }
    }
}
/** One ticket layout shared by panel and subscription library; all values are reported data. */
@Composable
internal fun SubscriptionBoardingTicket(name: String, provider: DashboardProviderUi?, host: String = "",
    placeholder: Boolean = false, refreshing: Boolean = false, success: Boolean = false,
    onEdit: () -> Unit, onRefresh: (() -> Unit)? = null, error: String = "") {
    val t = LocalHetuTokens.current
    val primary = MaterialTheme.colorScheme.primary
    val known = provider?.let { it.hasSubscriptionInfo && it.total > 0L } == true
    var details by rememberSaveable(name) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().crystalMaterial(RoundedCornerShape(22.dp), tint = primary)
        .testTag("subscription-ticket:$name").padding(WorkspaceMetrics.gutter), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                name,
                Modifier.weight(1f).testTag("ticket-title:$name"),
                color = t.textPrimary,
                fontSize = 16.sp,
                lineHeight = 22.sp,
                fontWeight = FontWeight.ExtraBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Box(Modifier.background(Color(0xFFEFF6FF), RoundedCornerShape(6.dp))
                .padding(horizontal = 6.dp, vertical = 2.dp).testTag("ticket-nodes:$name")) {
                Text(
                    if (provider != null) "${provider.nodes.size} 节点" else if (placeholder) "待填写" else "订阅",
                    color = if (placeholder) t.warning else primary, fontSize = 10.5.sp,
                    lineHeight = 14.sp, fontWeight = FontWeight.Bold,
                )
            }
            if (known && provider != null) Box(Modifier.background(Color(0xFFEFF6FF), CircleShape)
                .padding(horizontal = 7.dp, vertical = 2.dp).testTag("ticket-badge:$name")) {
                Text("剩余 ${((1f - provider.ratio) * 100f).toInt()}%", color = primary, fontSize = 11.sp,
                    lineHeight = 15.sp, fontWeight = FontWeight.ExtraBold)
            }
            if (onRefresh != null) {
                WorkspaceRefreshAction(name, refreshing, success, error.isNotBlank(), onClick = onRefresh)
            }
        }
        Row(Modifier.fillMaxWidth().heightIn(min = 36.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                host.ifBlank { if (placeholder) "待填写订阅地址" else "订阅配置" },
                Modifier.weight(1f).clickable(role = Role.Button, onClick = onEdit),
                color = if (placeholder) t.warning else t.textMuted,
                fontSize = 11.sp,
                lineHeight = 16.sp,
                maxLines = 1,
            )
            IconButton(
                onClick = onEdit,
                modifier = Modifier.size(36.dp).semantics { contentDescription = "编辑 $name" },
            ) {
                Icon(Icons.Rounded.Edit, null, Modifier.size(16.dp), tint = t.textMuted)
            }
        }
        if (known && provider != null) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("剩余流量", Modifier.alignByBaseline(), color = t.textSecondary, fontSize = 12.sp, lineHeight = 18.sp)
                HetuNumber(refBytes(provider.remaining), Modifier.weight(1f).alignByBaseline().testTag("ticket-remaining:$name"),
                    style = MaterialTheme.typography.titleMedium.copy(fontSize = 18.sp, lineHeight = 24.sp,
                        fontWeight = FontWeight.ExtraBold), color = t.textPrimary, monospaced = true)
            }
            InstrumentProgress(provider.ratio, Modifier.testTag("ticket-progress:$name"))
            HetuNumber("已用 ${refBytes(provider.used)} / ${refBytes(provider.total)}",
                Modifier.fillMaxWidth().heightIn(min = with(LocalDensity.current) { 24.sp.toDp() }).testTag("ticket-usage:$name"),
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium))
        } else Text("订阅未上报流量信息", color = t.textSecondary, fontSize = 12.sp, lineHeight = 18.sp)
        if (error.isNotBlank() && !refreshing) HetuTaskFeedback("$name 更新失败：$error", error = true)
        HorizontalDivider(color = t.textMuted.copy(alpha = .14f), thickness = .5.dp)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(ticketExpireAt(provider?.expire ?: 0), color = t.textSecondary, fontSize = 12.sp, lineHeight = 18.sp)
                Text(ticketUpdatedAt(provider?.updatedAt.orEmpty()), color = t.textSecondary, fontSize = 12.sp, lineHeight = 18.sp)
            }
            if (known) TextButton(onClick = { details = !details }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(if (details) "收起详情" else "流量详情", fontSize = 12.sp)
                Icon(if (details) Icons.Rounded.ExpandLess else Icons.Rounded.ChevronRight, null, Modifier.size(15.dp))
            }
        }
        WorkspaceAccordion(details && known && provider != null) {
            if (provider != null) Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("剩余 ${refBytes(provider.remaining)}", color = t.textPrimary, fontSize = 13.sp, lineHeight = 20.sp)
                Text("上传 ${refBytes(provider.upload)} · 下载 ${refBytes(provider.download)}", color = t.textSecondary,
                    fontSize = 12.sp, lineHeight = 18.sp)
            }
        }
    }
}
