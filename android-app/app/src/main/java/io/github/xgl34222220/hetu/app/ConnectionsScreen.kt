package io.github.xgl34222220.hetu

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import io.github.xgl34222220.hetu.ui.HetuHaptic
import io.github.xgl34222220.hetu.ui.rememberHetuHaptics
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.FilterList
import androidx.compose.material.icons.rounded.Sort
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Refresh
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.layout.heightIn

@Composable
internal fun ConnectionsScreen(vm: HetuViewModel, bottomPadding: Dp, forcedSection: String? = null) {
    val state = vm.state
    val c = Hx.colors
    var section by rememberSaveable { mutableStateOf(forcedSection ?: "conn") }
    val active = LocalHxEmbed.current?.active ?: true
    var filter by rememberSaveable { mutableStateOf("all") }
    var byApp by rememberSaveable { mutableStateOf(false) }
    var sortBy by rememberSaveable { mutableStateOf("down") }
    var showFilterMenu by remember { mutableStateOf(false) }
    var showSortMenu by remember { mutableStateOf(false) }
    var showConnMenu by remember { mutableStateOf(false) }
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var detailId by remember { mutableStateOf<String?>(null) }
    var confirmCloseAll by remember { mutableStateOf(false) }
    val logState = rememberLogFilterState()
    val rankState = rememberRankState(vm)

    LaunchedEffect(section, active) {
        if (section == "logs" && active) {
            vm.loadLogs()
            while (true) {
                delay(3_000)
                vm.loadLogs(quiet = true)
            }
        }
    }

    val all = state.connections
    val rates = rememberPanelConnectionRates(all, active && state.running)
    val filteredBase = all.filter { item ->
        val direct = item.chain.split(" → ").any { it.trim().equals("DIRECT", true) }
        (filter == "all" || (filter == "direct" && direct) || (filter == "proxy" && !direct)) &&
            (query.isBlank() || item.host.contains(query, true) || item.appName.contains(query, true) ||
                item.chain.contains(query, true) || item.rule.contains(query, true))
    }
    val filtered = when (sortBy) {
        "host" -> filteredBase.sortedBy { it.host.lowercase() }
        "rule" -> filteredBase.sortedBy { it.rule.lowercase() }
        "type" -> filteredBase.sortedBy { it.network.lowercase() }
        "time" -> filteredBase.sortedByDescending { it.startedAt }
        "down" -> filteredBase.sortedByDescending { it.download }
        "up" -> filteredBase.sortedByDescending { it.upload }
        else -> filteredBase.sortedByDescending { it.download + it.upload }
    }

    val leadingBarActions: @Composable RowScope.() -> Unit = {
        if (section != "rank") {
            HxBarAction(if (searching) Icons.Rounded.Close else Icons.Rounded.Search, "搜索", onClick = {
                searching = !searching
                if (!searching) { query = ""; logState.query = "" }
            })
        }
        if (section == "conn" && state.running) {
            Box(Modifier.hxAnchorSource()) { HxBarAction(Icons.Rounded.FilterList, "连接筛选", onClick = { showFilterMenu = true }) }
        }
    }
    val barActions: @Composable RowScope.() -> Unit = {
        when (section) {
            "logs" -> HxBarAction(Icons.Rounded.Refresh, "刷新日志", onClick = { vm.loadLogs() }, busy = vm.logsLoading)
            "conn" -> if (state.running) {
                Box(Modifier.hxAnchorSource()) { HxBarAction(Icons.Rounded.Sort, "连接排序", onClick = { showSortMenu = true }) }
                Box(Modifier.hxAnchorSource()) { HxBarAction(Icons.Rounded.MoreVert, "连接显示", onClick = { showConnMenu = true }) }
            }
        }
    }
    val body: LazyListScope.(String) -> Unit = { tab ->
        when (tab) {
            "logs" -> logItems(vm, logState, searching && section == "logs")
            "rank" -> if (!state.running && !rankState.historical) {
                item(key = "rank-stopped") { HxEmpty(Icons.Rounded.SwapVert, "代理未运行", "实时排行来自运行中的连接；开启历史采集后可查看 24 小时排行") }
            } else rankItems(vm, rankState)
            else -> {
                if (!state.running) {
                    item(key = "stopped") { HxEmpty(Icons.Rounded.SwapVert, "代理未运行", "启动代理后这里会实时显示每个应用的连接") }
                } else {
                    if (!searching) item(key = "conn-summary") {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 18.dp).padding(bottom = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("连接详情", style = MaterialTheme.typography.labelLarge, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, color = c.accent)
                            Spacer(Modifier.weight(1f))
                            Text(filtered.size.toString(), style = MaterialTheme.typography.labelLarge.merge(HxNumberStyle), color = c.text)
                            Spacer(Modifier.width(10.dp))
                            Box(
                                Modifier.size(28.dp).clip(RoundedCornerShape(10.dp)).clickable { confirmCloseAll = true },
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(Icons.Rounded.Close, "断开全部", tint = c.textMuted, modifier = Modifier.size(19.dp))
                            }
                        }
                    }
                    if (searching && section == "conn") {
                        item(key = "filters") {
                            Column(Modifier.padding(horizontal = Hx.gutter).padding(bottom = 10.dp)) {
                                HxSearchField(query, { query = it }, "搜索域名、应用、规则", autoFocus = true)
                                Spacer(Modifier.height(8.dp))
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
                                AppConnectionGroup(app, list, rates, Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null), onClose = vm::closeConnection) { detailId = it }
                            }
                        }
                    } else {
                        itemsIndexed(filtered, key = { _, item -> item.id }) { index, item ->
                            ConnectionRow(
                                item = item,
                                rate = rates[item.id],
                                first = index == 0,
                                last = index == filtered.lastIndex,
                                modifier = Modifier.animateItem(
                                    fadeInSpec = tween(HxMotion.Medium),
                                    placementSpec = HxMotion.glide(),
                                    fadeOutSpec = tween(HxMotion.Short),
                                ),
                                onClose = { vm.closeConnection(item.id) },
                            ) { detailId = item.id }
                        }
                    }
                }
            }
        }
    }
    val pageSubtitle = if (state.running) "${all.size} 个连接 · ↓ ${HxFormat.speed(vm.downRate)}  ↑ ${HxFormat.speed(vm.upRate)}" else "代理未运行"
    if (forcedSection != null) {
        HxPage(
            title = "连接",
            subtitle = null,
            bottomPadding = bottomPadding,
            refreshing = forcedSection == "logs" && vm.logsLoading,
            onRefresh = { if (forcedSection == "logs") vm.loadLogs() else vm.pullRefresh() },
            leadingActions = leadingBarActions,
            actions = barActions,
        ) { body(forcedSection) }
    } else {
        HxTabbedPage(
            title = "活动",
            tabs = listOf(HxPageTab("conn", "连接"), HxPageTab("rank", "排行"), HxPageTab("logs", "日志")),
            selected = section,
            onSelect = { section = it },
            scrollToTopSignal = vm.reselect,
            subtitle = pageSubtitle,
            bottomPadding = bottomPadding,
            actions = barActions,
        ) { tab -> body(tab) }
    }

    if (showFilterMenu) {
        PanelChoiceMenu(
            title = null,
            choices = listOf(HxChoice("all", "全部"), HxChoice("proxy", "代理"), HxChoice("direct", "直连")),
            selected = filter,
            onPick = { filter = it; showFilterMenu = false },
            onDismiss = { showFilterMenu = false },
        )
    }
    if (showSortMenu) {
        PanelChoiceMenu(
            title = "排序方式",
            choices = listOf(
                HxChoice("host", "主机"), HxChoice("rule", "规则"), HxChoice("type", "类型"),
                HxChoice("time", "连接时间"), HxChoice("down", "下行"), HxChoice("up", "上行"),
            ),
            selected = sortBy,
            onPick = { sortBy = it; showSortMenu = false },
            onDismiss = { showSortMenu = false },
        )
    }
    if (showConnMenu) {
        HxActionMenu(
            title = "连接设置",
            actions = buildList {
                add(HxMenuAction((if (byApp) "✓ " else "") + "按应用分组") { byApp = !byApp; showConnMenu = false })
                if (state.running) add(HxMenuAction("断开全部连接", Icons.Rounded.LinkOff, danger = true) { showConnMenu = false; confirmCloseAll = true })
            },
            onDismiss = { showConnMenu = false },
        )
    }

    val detail = detailId?.let { id -> all.firstOrNull { it.id == id } }
    if (detailId != null && detail == null) {
        LaunchedEffect(detailId) { detailId = null }
    }
    if (detail != null) {
        run {
            HxSheet(onDismiss = { detailId = null }, title = detail.host, showClose = true, containerColor = androidx.compose.ui.graphics.lerp(Hx.colors.canvas, Hx.colors.surface, .45f)) {
                val close = LocalHxSheetClose.current
                Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    HxGroup {
                        DetailLine("应用", detail.appName.ifBlank { detail.process.ifBlank { "未知" } }, detail.appIcon)
                        if (detail.packageName.isNotBlank()) { HxDivider(16.dp); DetailLine("包名", detail.packageName) }
                        HxDivider(16.dp)
                        DetailLine("规则", listOf(detail.rule, detail.rulePayload).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { "—" })
                    }
                    HxGroup {
                        DetailLine("链路", detail.chain.ifBlank { "—" })
                        HxDivider(16.dp)
                        DetailLine("网络", listOf(detail.network, detail.inbound).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { "—" })
                    }
                    HxGroup {
                        DetailLine("流量", "↓ ${HxFormat.bytes(detail.download)}   ↑ ${HxFormat.bytes(detail.upload)}")
                    }
                    HxButton("断开此连接", onClick = {
                        val id = detail.id
                        close {
                            vm.closeConnection(id)
                            detailId = null
                        }
                    }, icon = Icons.Rounded.LinkOff, tone = HxTone.Bad, filled = false, outlined = true, modifier = Modifier.fillMaxWidth())
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
private fun DetailLine(label: String, value: String, appIcon: android.graphics.Bitmap? = null) {
    val c = Hx.colors
    val context = LocalContext.current
    val haptics = rememberHetuHaptics()
    Row(
        Modifier.fillMaxWidth()
            .hxCombinedClick(onLongClick = { haptics.perform(HetuHaptic.LongPress); hxCopy(context, label, value) }, onClick = {})
            .padding(horizontal = 13.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = c.textMuted, modifier = Modifier.width(56.dp))
        Spacer(Modifier.weight(1f))
        if (appIcon != null) {
            Image(appIcon.asImageBitmap(), null, Modifier.size(18.dp))
            Spacer(Modifier.width(7.dp))
        }
        Text(value, style = MaterialTheme.typography.bodyMedium.merge(HxNumberStyle), color = c.text,
            textAlign = TextAlign.End, modifier = Modifier.weight(4f, fill = false))
    }
}

/** Rates are measured from real cumulative-counter deltas. The first observation is unknown. */
internal data class PanelConnectionRate(val upload: Long, val download: Long)

internal fun panelConnectionRates(previous: Map<String, ProxyConnectionUi>, sample: List<ProxyConnectionUi>, elapsedMillis: Long): Map<String, PanelConnectionRate> {
    val elapsed = elapsedMillis.coerceAtLeast(1L)
    return sample.mapNotNull { item ->
        previous[item.id]?.let { old ->
            item.id to PanelConnectionRate(
                ((item.upload - old.upload).coerceAtLeast(0L) * 1000.0 / elapsed).toLong(),
                ((item.download - old.download).coerceAtLeast(0L) * 1000.0 / elapsed).toLong(),
            )
        }
    }.toMap()
}

@Composable
internal fun rememberPanelConnectionRates(connections: List<ProxyConnectionUi>, active: Boolean): Map<String, PanelConnectionRate> {
    val latest by rememberUpdatedState(connections)
    var rates by remember { mutableStateOf<Map<String, PanelConnectionRate>>(emptyMap()) }
    LaunchedEffect(active) {
        rates = emptyMap()
        if (!active) return@LaunchedEffect
        var previous = latest.associateBy { it.id }
        var sampledAt = android.os.SystemClock.elapsedRealtime()
        while (true) {
            delay(1_000)
            val now = android.os.SystemClock.elapsedRealtime()
            val elapsed = (now - sampledAt).coerceAtLeast(1L)
            val sample = latest
            rates = panelConnectionRates(previous, sample, elapsed)
            previous = sample.associateBy { it.id }
            sampledAt = now
        }
    }
    return rates
}

private fun panelConnectionTime(value: String): String = runCatching {
    java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")
        .withZone(java.time.ZoneId.systemDefault()).format(java.time.Instant.parse(value))
}.getOrDefault("")

@Composable
private fun ConnectionRow(
    item: ProxyConnectionUi,
    rate: PanelConnectionRate?,
    first: Boolean,
    last: Boolean,
    modifier: Modifier,
    onClose: () -> Unit,
    onClick: () -> Unit,
) {
    val c = Hx.colors
    Column(modifier.fillMaxWidth().padding(horizontal = Hx.gutter).padding(bottom = 10.dp)
        .clip(RoundedCornerShape(18.dp)).background(c.surface)) {
        HxSwipeAction(label = "断开", icon = Icons.Rounded.LinkOff, onAction = onClose) {
            ConnectionContent(item, rate, onClick)
        }
    }
}

@Composable
private fun ConnectionContent(item: ProxyConnectionUi, rate: PanelConnectionRate?, onClick: () -> Unit) {
    val c = Hx.colors
    Column(Modifier.fillMaxWidth().background(c.surface).clickable(onClick = onClick)
        .padding(horizontal = 12.dp, vertical = 11.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(item.host, fontSize = 18.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold,
                color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            val time = panelConnectionTime(item.startedAt)
            if (time.isNotBlank()) {
                Spacer(Modifier.width(8.dp))
                Text(time, fontSize = 10.5.sp, color = c.textMuted, maxLines = 1)
            }
        }
        val metadata = (item.network.split(" · ") + item.inbound + item.addressType)
            .map(String::trim).filter(String::isNotBlank).distinctBy { it.lowercase() }.joinToString(" · ")
        if (metadata.isNotBlank()) Text(metadata, fontSize = 11.sp, lineHeight = 14.sp,
            color = c.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(7.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (item.appIcon != null) {
                Image(item.appIcon.asImageBitmap(), null, Modifier.size(16.dp))
                Spacer(Modifier.width(5.dp))
            }
            if (item.appName.isNotBlank()) {
                Text(item.appName, fontSize = 10.5.sp, color = c.text, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 100.dp))
                Text("  /  ", fontSize = 10.5.sp, color = c.textMuted)
            }
            Text(item.chain.ifBlank { item.rule.ifBlank { "—" } }, fontSize = 10.5.sp, lineHeight = 15.sp,
                color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.ArrowUpward, null, tint = c.good, modifier = Modifier.size(13.dp))
            Spacer(Modifier.width(4.dp))
            Text("${rate?.let { HxFormat.speed(it.upload) } ?: "—"}  总计 ${HxFormat.bytes(item.upload)}",
                fontSize = 9.5.sp, lineHeight = 13.sp, color = c.text, maxLines = 1, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(5.dp))
            Icon(Icons.Rounded.ArrowDownward, null, tint = c.accent, modifier = Modifier.size(13.dp))
            Spacer(Modifier.width(4.dp))
            Text("${rate?.let { HxFormat.speed(it.download) } ?: "—"}  总计 ${HxFormat.bytes(item.download)}",
                fontSize = 9.5.sp, lineHeight = 13.sp, color = c.text, maxLines = 1)
        }
    }
}

@Composable
private fun AppConnectionGroup(
    app: String,
    list: List<ProxyConnectionUi>,
    rates: Map<String, PanelConnectionRate>,
    modifier: Modifier,
    onClose: (String) -> Unit,
    onOpen: (String) -> Unit,
) {
    val c = Hx.colors
    var open by rememberSaveable(app) { mutableStateOf(false) }
    val haptics = rememberHetuHaptics()
    val first = list.first()
    Column(modifier.fillMaxWidth().padding(horizontal = Hx.gutter).padding(bottom = 8.dp)
        .clip(RoundedCornerShape(18.dp)).background(c.surface)
        .animateContentSize(tween(HxMotion.Medium, easing = HxMotion.Emphasized))) {
        Row(Modifier.fillMaxWidth().clickable { haptics.perform(HetuHaptic.Tick); open = !open }
            .padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            val icon = first.appIcon
            if (icon != null) Image(icon.asImageBitmap(), null, Modifier.size(26.dp))
            else Icon(Icons.Rounded.Apps, null, tint = c.textMuted, modifier = Modifier.size(26.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(app, style = MaterialTheme.typography.bodyMedium, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${list.size} 个连接  ↓ ${HxFormat.bytes(list.sumOf { it.download })}  ↑ ${HxFormat.bytes(list.sumOf { it.upload })}",
                    style = MaterialTheme.typography.labelSmall, color = c.textMuted, maxLines = 1)
            }
            val rotation by animateFloatAsState(if (open) 180f else 0f, HxMotion.glide(), label = "appChevron")
            Icon(Icons.Rounded.ExpandMore, "展开应用连接", tint = c.textMuted,
                modifier = Modifier.size(20.dp).graphicsLayer { rotationZ = rotation })
        }
        if (open) list.forEach { item ->
            HxSwipeAction(label = "断开", icon = Icons.Rounded.LinkOff, onAction = { onClose(item.id) }) {
                ConnectionContent(item, rates[item.id]) { onOpen(item.id) }
            }
        }
    }
}
