package io.github.xgl34222220.hetu

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.delay
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
internal fun ConnectionsScreen(vm: HetuViewModel, bottomPadding: Dp) {
    val state = vm.state
    var section by rememberSaveable { mutableStateOf("conn") }
    var filter by rememberSaveable { mutableStateOf("all") }
    var byApp by rememberSaveable { mutableStateOf(false) }
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var detailId by remember { mutableStateOf<String?>(null) }
    var confirmCloseAll by remember { mutableStateOf(false) }
    val logState = rememberLogFilterState()
    val rankState = rememberRankState(vm)

    LaunchedEffect(section) {
        if (section == "logs") {
            vm.loadLogs()
            while (true) {
                delay(3_000)
                vm.loadLogs(quiet = true)
            }
        }
    }

    val all = state.connections
    val filtered = all.filter { item ->
        val direct = item.chain.split(" → ").any { it.trim().equals("DIRECT", true) }
        (filter == "all" || (filter == "direct" && direct) || (filter == "proxy" && !direct)) &&
            (query.isBlank() || item.host.contains(query, true) || item.appName.contains(query, true) ||
                item.chain.contains(query, true) || item.rule.contains(query, true))
    }.sortedByDescending { it.download + it.upload }

    HxPage(
        title = "活动", scrollToTopSignal = vm.reselect,
        subtitle = if (state.running) "${all.size} 个连接 · ↓ ${HxFormat.speed(vm.downRate)}  ↑ ${HxFormat.speed(vm.upRate)}" else "代理未运行",
        bottomPadding = bottomPadding,
        refreshing = section == "logs" && vm.logsLoading,
        onRefresh = { if (section == "logs") vm.loadLogs() else vm.pullRefresh() },
        actions = {
            if (section != "rank") {
                HxBarAction(if (searching) Icons.Rounded.SearchOff else Icons.Rounded.Search, "搜索", onClick = {
                    searching = !searching
                    if (!searching) { query = ""; logState.query = "" }
                })
            }
            if (section == "conn" && state.running) {
                HxBarAction(Icons.Rounded.LinkOff, "断开全部", onClick = { confirmCloseAll = true }, enabled = all.isNotEmpty())
            }
        },
    ) {
        item(key = "sections") {
            HxSegmented(
                options = listOf("conn" to "连接", "rank" to "排行", "logs" to "日志"),
                selected = section,
                onSelect = { section = it },
                modifier = Modifier.padding(horizontal = Hx.gutter).padding(bottom = 12.dp),
            )
        }
        when (section) {
            "logs" -> logItems(vm, logState, searching)
            "rank" -> if (!state.running && !rankState.historical) {
                item(key = "rank-stopped") { HxEmpty(Icons.Rounded.SwapVert, "代理未运行", "实时排行来自运行中的连接；开启历史采集后可查看 24 小时排行") }
            } else rankItems(vm, rankState)
            else -> {
                if (!state.running) {
                    item(key = "stopped") { HxEmpty(Icons.Rounded.SwapVert, "代理未运行", "启动代理后这里会实时显示每个应用的连接") }
                } else {
                    item(key = "filters") {
                        Column(Modifier.padding(horizontal = Hx.gutter).padding(bottom = 12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                HxSegmented(
                                    options = listOf("all" to "全部", "proxy" to "代理", "direct" to "直连"),
                                    selected = filter,
                                    onSelect = { filter = it },
                                    modifier = Modifier.weight(1f),
                                )
                                Spacer(Modifier.width(8.dp))
                                HxToggleChip("按应用", byApp) { byApp = it }
                            }
                            if (searching) {
                                Spacer(Modifier.height(10.dp))
                                HxSearchField(query, { query = it }, "搜索域名、应用、规则")
                            }
                        }
                    }
                    if (filtered.isEmpty()) {
                        item(key = "empty") { HxEmpty(Icons.Rounded.SwapVert, if (all.isEmpty()) "暂无连接" else "没有匹配的连接") }
                    }
                    if (byApp) {
                        val groups = filtered.groupBy { it.appName.ifBlank { it.packageName.ifBlank { "未知应用" } } }
                            .toList().sortedByDescending { (_, list) -> list.sumOf { it.download + it.upload } }
                        groups.forEach { (app, list) ->
                            item(key = "app:$app") {
                                AppConnectionGroup(app, list, Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null)) { detailId = it }
                            }
                        }
                    } else {
                        items(filtered, key = { it.id }) { item ->
                            ConnectionRow(item, Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null)) { detailId = item.id }
                        }
                    }
                }
            }
        }
    }

    val detail = detailId?.let { id -> all.firstOrNull { it.id == id } }
    if (detailId != null && detail == null) {
        LaunchedEffect(detailId) { detailId = null }
    }
    if (detail != null) {
        run {
            HxSheet(onDismiss = { detailId = null }, title = detail.host) {
                Column(Modifier.padding(horizontal = 22.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    DetailLine("应用", detail.appName.ifBlank { detail.process.ifBlank { "未知" } })
                    if (detail.packageName.isNotBlank()) DetailLine("包名", detail.packageName)
                    DetailLine("规则", listOf(detail.rule, detail.rulePayload).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { "—" })
                    DetailLine("链路", detail.chain.ifBlank { "—" })
                    DetailLine("网络", detail.network.ifBlank { "—" })
                    DetailLine("入站", detail.inbound.ifBlank { "—" })
                    DetailLine("流量", "↓ ${HxFormat.bytes(detail.download)}   ↑ ${HxFormat.bytes(detail.upload)}")
                    Spacer(Modifier.height(4.dp))
                    HxButton("断开此连接", onClick = {
                        vm.closeConnection(detail.id)
                        detailId = null
                    }, icon = Icons.Rounded.Close, tone = HxTone.Bad, filled = false, modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }

    if (confirmCloseAll) {
        HxConfirmDialog(
            title = "断开全部连接？",
            message = "所有应用会立即重新建立连接，正在进行的下载或通话可能中断。",
            confirmLabel = "断开全部",
            danger = true,
            onConfirm = { confirmCloseAll = false; vm.closeAll() },
            onDismiss = { confirmCloseAll = false },
        )
    }
}

@Composable
private fun DetailLine(label: String, value: String) {
    val c = Hx.colors
    Row {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = c.textMuted, modifier = Modifier.width(56.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium, color = c.text, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun ConnectionRow(item: ProxyConnectionUi, modifier: Modifier, onClick: () -> Unit) {
    val c = Hx.colors
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = Hx.gutter)
            .padding(bottom = 8.dp)
            .clip(Hx.rowShape)
            .background(c.surface)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val icon = item.appIcon
        if (icon != null) {
            Image(icon.asImageBitmap(), null, Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)))
        } else {
            Box(Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background(c.surfaceMuted), contentAlignment = Alignment.Center) {
                Text(item.appName.take(1).ifBlank { "?" }, style = MaterialTheme.typography.labelLarge, color = c.textMuted)
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(item.host, style = MaterialTheme.typography.bodyMedium, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOf(item.appName, item.chain).filter { it.isNotBlank() }.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = c.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text("↓ " + HxFormat.bytes(item.download), style = MaterialTheme.typography.labelMedium.merge(HxNumberStyle), color = c.text)
            Text("↑ " + HxFormat.bytes(item.upload), style = MaterialTheme.typography.labelSmall.merge(HxNumberStyle), color = c.textFaint)
        }
    }
}

@Composable
private fun AppConnectionGroup(app: String, list: List<ProxyConnectionUi>, modifier: Modifier, onOpen: (String) -> Unit) {
    val c = Hx.colors
    var open by rememberSaveable(app) { mutableStateOf(false) }
    val first = list.first()
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = Hx.gutter)
            .padding(bottom = 8.dp)
            .clip(Hx.rowShape)
            .background(c.surface)
            .animateContentSize(tween(HxMotion.Medium, easing = HxMotion.Emphasized)),
    ) {
        Row(
            Modifier.fillMaxWidth().clickable { open = !open }.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val icon = first.appIcon
            if (icon != null) {
                Image(icon.asImageBitmap(), null, Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)))
            } else {
                Box(Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background(c.surfaceMuted), contentAlignment = Alignment.Center) {
                    Text(app.take(1), style = MaterialTheme.typography.labelLarge, color = c.textMuted)
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(app, style = MaterialTheme.typography.bodyMedium, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${list.size} 个连接", style = MaterialTheme.typography.bodySmall, color = c.textMuted)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("↓ " + HxFormat.bytes(list.sumOf { it.download }), style = MaterialTheme.typography.labelMedium.merge(HxNumberStyle), color = c.text)
                Text("↑ " + HxFormat.bytes(list.sumOf { it.upload }), style = MaterialTheme.typography.labelSmall.merge(HxNumberStyle), color = c.textFaint)
            }
        }
        if (open) {
            list.forEach { item ->
                HxDivider(58.dp)
                Row(
                    Modifier.fillMaxWidth().clickable { onOpen(item.id) }.padding(start = 58.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(item.host, style = MaterialTheme.typography.bodySmall, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(item.chain, style = MaterialTheme.typography.labelSmall, color = c.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Text(HxFormat.bytes(item.download + item.upload), style = MaterialTheme.typography.labelSmall.merge(HxNumberStyle), color = c.textMuted)
                }
            }
        }
    }
}
