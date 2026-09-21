package io.github.xgl34222220.hetu

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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
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

@Composable
internal fun OverviewRouteRanking(state: ProxyComposeState) {
    val t = LocalHetuTokens.current
    val rows = remember(state.connections) { overviewRouteCounts(state.connections).take(5) }
    Column(Modifier.fillMaxWidth().crystalMaterial(RoundedCornerShape(22.dp)).padding(16.dp).testTag("overview-ranking"),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("活动连接排行", color = t.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Text("按策略统计当前连接，非累计流量", color = t.textSecondary, fontSize = 12.sp, lineHeight = 18.sp)
        if (!state.running || !state.panelReady || rows.isEmpty()) Text("暂无活动连接", color = t.textSecondary, fontSize = 12.sp)
        else rows.forEach { (name, count) ->
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(name, Modifier.weight(1f), color = t.textPrimary, fontSize = 12.sp, lineHeight = 17.sp)
                    HetuNumber("$count", style = MaterialTheme.typography.labelLarge)
                }
                InstrumentProgress(count.toFloat() / state.connections.size.coerceAtLeast(1), Modifier.testTag("route-count:$name"))
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
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(name, Modifier.weight(1f).testTag("ticket-title:$name"), color = t.textPrimary,
                fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Bold)
            if (known && provider != null) Box(Modifier.background(primary.copy(alpha = .09f), CircleShape)
                .padding(horizontal = 8.dp, vertical = 4.dp).testTag("ticket-badge:$name")) {
                Text("剩余 ${((1f - provider.ratio) * 100f).toInt()}%", color = primary, fontSize = 11.sp,
                    lineHeight = 16.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).heightIn(min = 48.dp).clickable(role = Role.Button, onClick = onEdit),
                verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(if (provider != null) "${provider.nodes.size} 节点" else if (placeholder) "待填写订阅地址" else "订阅信息",
                    color = if (placeholder) t.warning else t.textSecondary, fontSize = 12.sp, lineHeight = 18.sp,
                    modifier = Modifier.testTag("ticket-nodes:$name"))
                if (host.isNotBlank()) Text(host, color = t.textSecondary, fontSize = 12.sp, lineHeight = 18.sp)
            }
            IconButton(onClick = onEdit, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Rounded.Edit, "编辑 $name", Modifier.size(18.dp), tint = t.textSecondary)
            }
            if (onRefresh != null) WorkspaceRefreshAction(name, refreshing, success, error.isNotBlank(), onClick = onRefresh)
        }
        if (known && provider != null) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("剩余流量", Modifier.alignByBaseline(), color = t.textSecondary, fontSize = 12.sp, lineHeight = 18.sp)
                HetuNumber(refBytes(provider.remaining), Modifier.weight(1f).alignByBaseline().testTag("ticket-remaining:$name"),
                    style = MaterialTheme.typography.titleMedium.copy(fontSize = 18.sp, lineHeight = 24.sp,
                        fontWeight = FontWeight.Bold), color = primary)
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
