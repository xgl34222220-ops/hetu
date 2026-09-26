package io.github.xgl34222220.hetu

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.xgl34222220.hetu.ui.*

internal fun rankConnections(values: List<ProxyConnectionUi>, dimension: String, sort: String): List<TrafficRank> {
    val rows = values.groupBy { when(dimension) { "app" -> it.appName.ifBlank { it.packageName.ifBlank { "未知应用" } }; "route" -> it.chain.ifBlank { "—" }; else -> it.host.ifBlank { "—" } } }
        .map { (name, connections) -> TrafficRank(name, connections.size, connections.sumOf { it.upload.coerceAtLeast(0) }, connections.sumOf { it.download.coerceAtLeast(0) }) }
    return (if (sort == "traffic") rows.sortedByDescending { it.upload + it.download } else rows.sortedByDescending { it.connections }).take(20)
}

@Composable
internal fun TrafficRankings(connections: List<ProxyConnectionUi>) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("hetu", 0) }
    val t = LocalHetuTokens.current
    var dimension by rememberSaveable { mutableStateOf(prefs.getString("overviewRankDimension", "host").orEmpty()) }
    var sort by rememberSaveable { mutableStateOf(prefs.getString("overviewRankSort", "count").orEmpty()) }
    var historical by rememberSaveable { mutableStateOf(false) }
    var history by remember { mutableStateOf(emptyList<TrafficRank>()) }
    LaunchedEffect(dimension, sort, historical, connections) {
        if (historical) history = ProxyApiHistoryStore.ranking(context, dimension, sort, System.currentTimeMillis() - 86_400_000)
    }
    val rows = remember(connections, dimension, sort, historical, history) { if (historical) history else rankConnections(connections, dimension, sort) }
    var expandedName by rememberSaveable(dimension) { mutableStateOf<String?>(null) }
    GroupedInsetSection {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if(historical) "24 小时排行" else "实时排行", color = t.textPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                TextButton(onClick = { sort = if (sort == "count") "traffic" else "count"; prefs.edit().putString("overviewRankSort", sort).apply() }) { Text(if (sort == "count") "按连接数" else "按流量", fontSize = 12.sp) }
            }
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("host" to "域名 / IP", "app" to "应用", "route" to "代理链").forEach { (key,label) -> LiquidChoicePill(label, dimension == key, { dimension = key; prefs.edit().putString("overviewRankDimension", key).apply() }) }
            }
            if(prefs.getBoolean("proxyApiHistoryEnabled", false)) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                LiquidChoicePill("实时", !historical, { historical = false }); LiquidChoicePill("24 小时", historical, { historical = true })
            }
            if(rows.isEmpty()) Text(if(historical) "暂无已采集历史" else "暂无实时连接数据", color = t.textSecondary, fontSize = 12.sp)
            rows.forEachIndexed { index, row ->
                Column(Modifier.fillMaxWidth().clickable { expandedName = if (expandedName == row.name) null else row.name }
                    .padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Box(Modifier.widthIn(min = 32.dp).heightIn(min = 32.dp)
                            .background(t.controlBackground, RoundedCornerShape(10.dp)).padding(6.dp),
                            contentAlignment = Alignment.Center) {
                            HetuNumber("${index + 1}", Modifier.testTag("rank-number:$index"), color = t.textSecondary,
                                style = MaterialTheme.typography.labelLarge.copy(fontSize = 13.sp, lineHeight = 19.sp))
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(row.name, color = t.textPrimary, maxLines = if (expandedName == row.name) Int.MAX_VALUE else 2,
                                overflow = TextOverflow.Ellipsis, fontSize = 13.sp, lineHeight = 20.sp)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text("↑ ${refBytes(row.upload)}", color = t.textSecondary, fontSize = 12.sp, lineHeight = 18.sp)
                                Text("↓ ${refBytes(row.download)}", color = t.textSecondary, fontSize = 12.sp, lineHeight = 18.sp)
                            }
                        }
                        HetuNumber("${row.connections} 条", color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.labelLarge.copy(fontSize = 12.sp, lineHeight = 18.sp))
                    }
                    val maximum = rows.maxOfOrNull { if (sort == "traffic") it.upload + it.download else it.connections.toLong() } ?: 0L
                    if (maximum > 0L) HetuReadOnlyProgress(
                        (if (sort == "traffic") row.upload + row.download else row.connections.toLong()).toFloat() / maximum,
                        Modifier.padding(start = 42.dp))
                }
            }
        }
    }
}

@Composable
internal fun OverviewSubscriptions(providers: List<DashboardProviderUi>) {
    val t = LocalHetuTokens.current
    GroupedInsetSection {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("订阅 · ${providers.size}", color = t.textPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            if (providers.isEmpty()) Text("当前核心没有远程订阅", color = t.textSecondary, fontSize = 12.sp)
            providers.forEach { provider ->
                Text(provider.name, color = t.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                if (provider.hasSubscriptionInfo && provider.total > 0) {
                    HetuReadOnlyProgress(provider.ratio)
                    Text("剩余 ${refBytes(provider.remaining)} / ${refBytes(provider.total)}", color = t.textSecondary, fontSize = 12.sp)
                    if(provider.expire > 0) Text("到期 " + java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).format(java.util.Date(provider.expire * 1000)), color = t.textSecondary, fontSize = 11.sp)
                } else Text("订阅源未提供用量", color = t.textSecondary, fontSize = 12.sp)
            }
        }
    }
}
