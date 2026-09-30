package io.github.xgl34222220.hetu

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Sort
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Compact live dashboard based on the reference overview: one clear reading path instead of
 * several equally-heavy cards. Real-time ranking is application-first and uses the actual app
 * icon already resolved by ProxyComposeController whenever it is available.
 */
@Composable
internal fun PanelOverviewScreen(vm: HetuViewModel, bottomPadding: Dp) {
    val c = Hx.colors
    val scope = rememberCoroutineScope()
    val state = vm.state
    val connections = state.connections
    val tracked = vm.providers.filter { it.hasSubscriptionInfo && it.total > 0L }
    val total = tracked.sumOf { it.total }
    val used = tracked.sumOf { it.used }
    val remaining = (total - used).coerceAtLeast(0L)
    val ratio = if (total <= 0L) 0f else (used.toDouble() / total.toDouble()).toFloat().coerceIn(0f, 1f)
    val expire = tracked.mapNotNull { it.expire.takeIf { value -> value > 0L } }.minOrNull() ?: 0L
    val nodeCount = vm.providers.sumOf { it.nodes.size }
    var rankSort by remember { mutableStateOf(vm.prefs.getString("panelOverviewRankSort", "connections").orEmpty().ifBlank { "connections" }) }
    var rankCount by remember { mutableStateOf(vm.prefs.getInt("panelOverviewRankCount", 5).coerceIn(5, 30)) }
    var showRankMenu by remember { mutableStateOf(false) }
    var showOverviewMenu by remember { mutableStateOf(false) }
    val ranking = remember(connections, rankSort, rankCount) {
        val groups = overviewRankGroups(connections)
        val sorted = when (rankSort) {
            "traffic" -> groups.sortedByDescending { it.download + it.upload }
            else -> groups.sortedWith(compareByDescending<OverviewRankGroup> { it.connections.size }.thenByDescending { it.download + it.upload })
        }
        sorted.take(rankCount)
    }

    HxPage(
        title = "概览",
        bottomPadding = bottomPadding,
        refreshing = vm.refreshing,
        onRefresh = { scope.launch { vm.refreshNow() } },
        leadingActions = {
            Box(Modifier.hxAnchorSource()) {
                HxBarAction(Icons.Rounded.Sort, "排行方式", onClick = { showRankMenu = true })
            }
        },
        actions = {
            Box(Modifier.hxAnchorSource()) {
                HxBarAction(Icons.Rounded.Settings, "概览设置", onClick = { showOverviewMenu = true })
            }
        },
    ) {
        item(key = "overview-status") {
            HxSection {
                HxCard(padding = PaddingValues(horizontal = 15.dp, vertical = 13.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("运行概况", style = MaterialTheme.typography.titleSmall, color = c.text, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.weight(1f))
                        HxDot(if (state.running) c.good else c.textFaint, 7.dp)
                        Spacer(Modifier.width(5.dp))
                        Text(if (state.running) "运行中" else "未连接", style = MaterialTheme.typography.labelSmall, color = c.textMuted)
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(Modifier.fillMaxWidth()) {
                        OverviewCenteredMetric("策略", state.groups.size.toString(), Modifier.weight(1f))
                        OverviewCenteredMetric("规则", vm.rules.size.toString(), Modifier.weight(1f))
                        OverviewCenteredMetric("当前连接", connections.size.toString(), Modifier.weight(1f))
                    }
                }
            }
        }

        item(key = "overview-subscription") {
            HxSection {
                HxCard(
                    onClick = { vm.openPanel("providers") },
                    padding = PaddingValues(horizontal = 15.dp, vertical = 13.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("订阅", style = MaterialTheme.typography.titleSmall, color = c.text, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.weight(1f))
                        if (expire > 0L) {
                            Text("到期 ${overviewExpireDate(expire)}", style = MaterialTheme.typography.labelSmall, color = c.textMuted)
                        }
                    }
                    Spacer(Modifier.height(9.dp))
                    Row(Modifier.fillMaxWidth()) {
                        OverviewValueBlock("已用", if (total > 0L) HxFormat.bytes(used) else "—", Modifier.weight(1f))
                        OverviewValueBlock("剩余", if (total > 0L) HxFormat.bytes(remaining) else "—", Modifier.weight(1f))
                        OverviewValueBlock("总量", if (total > 0L) HxFormat.bytes(total) else "—", Modifier.weight(1f), end = true)
                    }
                    Spacer(Modifier.height(10.dp))
                    HxProgressBar(ratio, c.accent, height = 3.dp)
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth()) {
                        Text("${tracked.size} 个订阅", style = MaterialTheme.typography.labelSmall, color = c.textMuted)
                        Spacer(Modifier.weight(1f))
                        Text("$nodeCount 个节点", style = MaterialTheme.typography.labelSmall, color = c.textMuted)
                    }
                }
            }
        }

        item(key = "overview-speed") {
            HxSection {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OverviewSpeedCard(
                        title = "上行速度",
                        value = HxFormat.speed(vm.upRate),
                        iconUp = true,
                        tint = c.good,
                        soft = c.goodSoft,
                        modifier = Modifier.weight(1f),
                    )
                    OverviewSpeedCard(
                        title = "下行速度",
                        value = HxFormat.speed(vm.downRate),
                        iconUp = false,
                        tint = c.accent,
                        soft = c.accentSoft,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        item(key = "overview-total") {
            HxSection {
                HxCard(padding = PaddingValues(horizontal = 15.dp, vertical = 12.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("总流量", style = MaterialTheme.typography.titleSmall, color = c.text, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.weight(1f))
                        Text("上行 ${HxFormat.bytes(state.uploadTotal)}", style = MaterialTheme.typography.labelMedium.merge(HxNumberStyle), color = c.good)
                        Text("  /  ", style = MaterialTheme.typography.labelSmall, color = c.textFaint)
                        Text("下行 ${HxFormat.bytes(state.downloadTotal)}", style = MaterialTheme.typography.labelMedium.merge(HxNumberStyle), color = c.accent)
                    }
                }
            }
        }

        item(key = "overview-trend") {
            HxSection {
                HxCard(padding = PaddingValues(horizontal = 15.dp, vertical = 13.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                        Column {
                            Text("近期趋势", style = MaterialTheme.typography.titleSmall, color = c.text, fontWeight = FontWeight.SemiBold)
                            Text("最近 60 秒", style = MaterialTheme.typography.labelSmall, color = c.textMuted)
                        }
                        Spacer(Modifier.weight(1f))
                        OverviewLegend("上行", c.good, c.goodSoft)
                        Spacer(Modifier.width(7.dp))
                        OverviewLegend("下行", c.accent, c.accentSoft)
                    }
                    Spacer(Modifier.height(8.dp))
                    HxTrafficChart(
                        down = vm.rateHistory.toList(),
                        up = vm.upHistory.toList(),
                        downColor = c.accent,
                        upColor = c.good,
                        modifier = Modifier.fillMaxWidth().height(142.dp),
                    )
                }
            }
        }

        item(key = "overview-ranking") {
            HxSection {
                HxCard(
                    onClick = { vm.openPanel("rank") },
                    padding = PaddingValues(horizontal = 15.dp, vertical = 13.dp),
                ) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("实时排行", style = MaterialTheme.typography.titleSmall, color = c.text, fontWeight = FontWeight.SemiBold)
                            Text("按连接数", style = MaterialTheme.typography.labelSmall, color = c.textMuted)
                        }
                        Box(
                            Modifier.size(28.dp).clip(CircleShape).clickable { vm.openPanel("rank") },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(Icons.Rounded.MoreHoriz, "查看排行", tint = c.textMuted, modifier = Modifier.size(18.dp))
                        }
                    }
                    Spacer(Modifier.height(if (ranking.isEmpty()) 8.dp else 10.dp))
                    if (ranking.isEmpty()) {
                        Text(
                            if (state.running) "产生连接后会显示应用排行" else "代理未运行",
                            style = MaterialTheme.typography.bodySmall,
                            color = c.textMuted,
                        )
                    } else {
                        ranking.forEachIndexed { index, group ->
                            OverviewRankRow(group)
                            if (index != ranking.lastIndex) Spacer(Modifier.height(10.dp))
                        }
                    }
                }
            }
        }
    }

    if (showRankMenu) {
        HxChoiceSheet(
            title = "实时排行",
            choices = listOf(
                HxChoice("connections", "按连接数"),
                HxChoice("traffic", "按总流量"),
            ),
            selected = rankSort,
            onPick = {
                rankSort = it
                vm.prefs.edit().putString("panelOverviewRankSort", it).apply()
                showRankMenu = false
            },
            onDismiss = { showRankMenu = false },
            footer = "排行只改变显示顺序，不影响代理行为",
        )
    }
    if (showOverviewMenu) {
        HxChoiceSheet(
            title = "显示数量",
            choices = listOf(5, 10, 15, 20, 30).map { HxChoice(it.toString(), "$it") },
            selected = rankCount.toString(),
            onPick = { value ->
                rankCount = value.toIntOrNull()?.coerceIn(5, 30) ?: 5
                vm.prefs.edit().putInt("panelOverviewRankCount", rankCount).apply()
                showOverviewMenu = false
            },
            onDismiss = { showOverviewMenu = false },
        )
    }
}

@Composable
private fun OverviewCenteredMetric(label: String, value: String, modifier: Modifier = Modifier) {
    val c = Hx.colors
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleMedium.merge(HxNumberStyle), color = c.text, fontWeight = FontWeight.SemiBold)
        Text(label, style = MaterialTheme.typography.labelSmall, color = c.textMuted)
    }
}

@Composable
private fun OverviewValueBlock(label: String, value: String, modifier: Modifier = Modifier, end: Boolean = false) {
    val c = Hx.colors
    Column(modifier, horizontalAlignment = if (end) Alignment.End else Alignment.Start) {
        Text(value, style = MaterialTheme.typography.bodyMedium.merge(HxNumberStyle), color = c.text, fontWeight = FontWeight.Medium, maxLines = 1)
        Text(label, style = MaterialTheme.typography.labelSmall, color = c.textMuted)
    }
}

@Composable
private fun OverviewSpeedCard(
    title: String,
    value: String,
    iconUp: Boolean,
    tint: Color,
    soft: Color,
    modifier: Modifier,
) {
    val c = Hx.colors
    HxCard(modifier = modifier, padding = PaddingValues(horizontal = 12.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(30.dp).clip(RoundedCornerShape(10.dp)).background(soft), contentAlignment = Alignment.Center) {
                Icon(if (iconUp) Icons.Rounded.ArrowUpward else Icons.Rounded.ArrowDownward, null, tint = tint, modifier = Modifier.size(17.dp))
            }
            Spacer(Modifier.width(9.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.labelSmall, color = c.textMuted)
                Text(value, style = MaterialTheme.typography.titleSmall.merge(HxNumberStyle), color = tint, fontWeight = FontWeight.SemiBold, maxLines = 1)
            }
        }
    }
}

@Composable
private fun OverviewLegend(label: String, color: Color, soft: Color) {
    Row(
        Modifier.clip(CircleShape).background(soft).padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HxDot(color, 6.dp)
        Spacer(Modifier.width(4.dp))
        Text(label, style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp), color = color)
    }
}

private data class OverviewRankGroup(
    val key: String,
    val title: String,
    val subtitle: String,
    val icon: android.graphics.Bitmap?,
    val chainOnly: Boolean,
    val connections: List<ProxyConnectionUi>,
) {
    val upload: Long get() = connections.sumOf { it.upload.coerceAtLeast(0L) }
    val download: Long get() = connections.sumOf { it.download.coerceAtLeast(0L) }
}

private fun overviewRankGroups(items: List<ProxyConnectionUi>): List<OverviewRankGroup> {
    if (items.isEmpty()) return emptyList()
    val buckets = LinkedHashMap<String, MutableList<ProxyConnectionUi>>()
    items.forEach { item ->
        val process = item.process.substringAfterLast('/').substringBefore(':')
        val key = when {
            item.packageName.isNotBlank() -> "pkg:${item.packageName}"
            item.appName.isNotBlank() -> "app:${item.appName}"
            item.uid > 0 -> "uid:${item.uid}"
            process.isNotBlank() -> "proc:$process"
            item.chain.isNotBlank() -> "chain:${item.chain}"
            else -> "unknown"
        }
        buckets.getOrPut(key) { ArrayList() }.add(item)
    }
    return buckets.map { (key, list) ->
        val first = list.first()
        val process = first.process.substringAfterLast('/').substringBefore(':')
        val title = when {
            first.appName.isNotBlank() -> first.appName
            first.packageName.isNotBlank() -> first.packageName
            process.isNotBlank() -> process
            first.chain.isNotBlank() -> first.chain
            first.uid == 0 -> "系统服务"
            first.uid > 0 -> "UID ${first.uid}"
            else -> "未知连接"
        }
        val subtitle = when {
            first.host.isNotBlank() -> first.host
            first.chain.isNotBlank() && first.chain != title -> first.chain
            first.packageName.isNotBlank() && first.packageName != title -> first.packageName
            else -> "实时连接"
        }
        OverviewRankGroup(
            key = key,
            title = title,
            subtitle = subtitle,
            icon = first.appIcon,
            chainOnly = key.startsWith("chain:") || key == "unknown",
            connections = list,
        )
    }.sortedWith(
        compareByDescending<OverviewRankGroup> { it.connections.size }
            .thenByDescending { it.download + it.upload }
            .thenBy { it.title.lowercase(Locale.ROOT) }
    )
}

@Composable
private fun OverviewRankRow(group: OverviewRankGroup) {
    val c = Hx.colors
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background(c.surfaceMuted),
            contentAlignment = Alignment.Center,
        ) {
            val bitmap = group.icon
            if (bitmap != null) {
                Image(bitmap.asImageBitmap(), group.title, Modifier.fillMaxSize().padding(4.dp))
            } else {
                Icon(
                    if (group.chainOnly) Icons.Rounded.Link else Icons.Rounded.Apps,
                    null,
                    tint = if (group.chainOnly) c.textMuted else c.accent,
                    modifier = Modifier.size(19.dp),
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(group.title, style = MaterialTheme.typography.bodyMedium, color = c.text, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "↓ ${HxFormat.bytes(group.download)} · ↑ ${HxFormat.bytes(group.upload)}",
                style = MaterialTheme.typography.labelSmall.merge(HxNumberStyle),
                color = c.textMuted,
                maxLines = 1,
            )
        }
        Spacer(Modifier.width(8.dp))
        Text("${group.connections.size} 条连接", style = MaterialTheme.typography.labelSmall.merge(HxNumberStyle), color = c.textMuted, maxLines = 1)
    }
}

private fun overviewExpireDate(value: Long): String {
    if (value <= 0L) return "—"
    val millis = if (value < 10_000_000_000L) value * 1000L else value
    return runCatching { SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(millis)) }.getOrDefault("—")
}
