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
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.WifiTethering
import androidx.compose.material.icons.rounded.FilterList
import androidx.compose.material.icons.rounded.Sort
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
internal fun ConnectionsScreen(vm: HetuViewModel, bottomPadding: Dp, forcedSection: String? = null) {
    val state = vm.state
    var section by rememberSaveable { mutableStateOf(forcedSection ?: "conn") }
    val active = LocalHxEmbed.current?.active ?: true
    var filter by rememberSaveable { mutableStateOf("all") }
    var byApp by rememberSaveable { mutableStateOf(false) }
    var sortBy by rememberSaveable { mutableStateOf("traffic") }
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
        "time" -> filteredBase
        "down" -> filteredBase.sortedByDescending { it.download }
        "up" -> filteredBase.sortedByDescending { it.upload }
        else -> filteredBase.sortedByDescending { it.download + it.upload }
    }

    val leadingBarActions: @Composable RowScope.() -> Unit = {
        if (section != "rank") {
            HxBarAction(if (searching) Icons.Rounded.SearchOff else Icons.Rounded.Search, "搜索", onClick = {
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
                Box(Modifier.hxAnchorSource()) { HxBarAction(Icons.Rounded.Settings, "连接设置", onClick = { showConnMenu = true }) }
            }
        }
    }
    val body: LazyListScope.(String) -> Unit = { tab ->
        when (tab) {
            "logs" -> {
                item(key = "logs-summary") {
                    HxPanelSectionIntro(
                        title = "运行日志",
                        subtitle = "连接、规则匹配与系统事件",
                        icon = Icons.Rounded.Description,
                        count = if (vm.logsLoading && vm.logEntries.isEmpty()) "读取中" else "${vm.logEntries.size} 条",
                        tag = "panel-logs-summary",
                    )
                }
                logItems(vm, logState, searching && section == "logs")
            }
            "rank" -> if (!state.running && !rankState.historical) {
                item(key = "rank-stopped") { HxEmpty(Icons.Rounded.SwapVert, "代理未运行", "实时排行来自运行中的连接；开启历史采集后可查看 24 小时排行") }
            } else rankItems(vm, rankState)
            else -> {
                if (!state.running) {
                    item(key = "stopped") { HxEmpty(Icons.Rounded.SwapVert, "代理未运行", "启动代理后这里会实时显示每个应用的连接") }
                } else {
                    item(key = "connection-summary") {
                        HxPanelSectionIntro(
                            title = "连接监测",
                            subtitle = "实时查看设备的网络连接",
                            icon = Icons.Rounded.WifiTethering,
                            count = "${all.size} 个",
                            tag = "panel-connections-summary",
                        )
                    }
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
                            if (searching && section == "conn") {
                                Spacer(Modifier.height(10.dp))
                                HxSearchField(query, { query = it }, "搜索域名、应用、规则", autoFocus = true)
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
                        itemsIndexed(filtered, key = { _, item -> item.id }) { index, item ->
                            ConnectionRow(
                                item = item,
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
            title = "活动",
            subtitle = pageSubtitle,
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
        HxChoiceSheet(
            title = "连接筛选",
            choices = listOf(HxChoice("all", "全部"), HxChoice("proxy", "代理"), HxChoice("direct", "直连")),
            selected = filter,
            onPick = { filter = it; showFilterMenu = false },
            onDismiss = { showFilterMenu = false },
        )
    }
    if (showSortMenu) {
        HxChoiceSheet(
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
            HxSheet(onDismiss = { detailId = null }, title = detail.host) {
                val close = LocalHxSheetClose.current
                Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    HxGroup {
                        DetailLine("应用", detail.appName.ifBlank { detail.process.ifBlank { "未知" } })
                        if (detail.packageName.isNotBlank()) { HxDivider(16.dp); DetailLine("包名", detail.packageName) }
                        HxDivider(16.dp)
                        DetailLine("规则", listOf(detail.rule, detail.rulePayload).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { "—" })
                        HxDivider(16.dp)
                        DetailLine("链路", detail.chain.ifBlank { "—" })
                        HxDivider(16.dp)
                        DetailLine("网络", listOf(detail.network, detail.inbound).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { "—" })
                        HxDivider(16.dp)
                        DetailLine("流量", "↓ ${HxFormat.bytes(detail.download)}   ↑ ${HxFormat.bytes(detail.upload)}")
                    }
                    HxButton("断开此连接", onClick = {
                        val id = detail.id
                        close {
                            vm.closeConnection(id)
                            detailId = null
                        }
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
    val context = LocalContext.current
    val haptics = rememberHetuHaptics()
    Row(
        Modifier
            .fillMaxWidth()
            .hxCombinedClick(
                onLongClick = { haptics.perform(HetuHaptic.LongPress); hxCopy(context, label, value) },
                onClick = {},
            )
            .padding(horizontal = 16.dp, vertical = 11.dp),
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = c.textMuted, modifier = Modifier.width(56.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium.merge(HxNumberStyle), color = c.text, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun ConnectionRow(
    item: ProxyConnectionUi,
    first: Boolean,
    last: Boolean,
    modifier: Modifier,
    onClose: () -> Unit,
    onClick: () -> Unit,
) {
    val c = Hx.colors
    val shape = Hx.cardShape
    val source = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = Hx.gutter)
            .padding(bottom = 9.dp)
            .testTag("panel-connection-${item.id}")
            .hxPressScale(source, .985f)
            .clip(shape)
            .background(c.surface)
            .border(0.8.dp, c.line.copy(alpha = .55f), shape),
    ) {
        HxSwipeAction(label = "断开", icon = Icons.Rounded.LinkOff, onAction = onClose) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(c.surface)
                    .clickable(interactionSource = source, indication = androidx.compose.foundation.LocalIndication.current, onClick = onClick)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val icon = item.appIcon
                    if (icon != null) {
                        Image(icon.asImageBitmap(), null, Modifier.size(36.dp).clip(RoundedCornerShape(11.dp)))
                    } else {
                        HxIconBadge(Icons.Rounded.Public, size = 36.dp)
                    }
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            item.host,
                            style = MaterialTheme.typography.titleMedium.merge(HxNumberStyle),
                            fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                            color = c.text,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            listOf(item.network, item.inbound).filter { it.isNotBlank() }.distinct().joinToString(" · ").ifBlank { "连接" },
                            style = MaterialTheme.typography.bodySmall,
                            color = c.textMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (item.appName.isNotBlank()) {
                        Text(
                            item.appName,
                            style = MaterialTheme.typography.labelSmall,
                            color = c.textMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .widthIn(max = 88.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(c.accent.copy(alpha = .06f))
                                .padding(horizontal = 7.dp, vertical = 3.dp),
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                val chain = item.chain.ifBlank { item.rule.ifBlank { "链路未知" } }
                val direct = item.chain.split(" → ").any { it.trim().equals("DIRECT", true) }
                val chainTint = if (direct) c.good else c.accent
                Text(
                    chain,
                    style = MaterialTheme.typography.labelMedium,
                    color = chainTint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.clip(Hx.chipShape).background(chainTint.copy(alpha = .08f))
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                )
                if (item.rule.isNotBlank() || item.rulePayload.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        listOf(item.rule, item.rulePayload).filter { it.isNotBlank() }.joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        color = c.textFaint,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier.fillMaxWidth().clip(Hx.chipShape).background(c.accent.copy(alpha = .035f))
                        .padding(horizontal = 10.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("↑ ${HxFormat.bytes(item.upload)}", style = MaterialTheme.typography.labelMedium.merge(HxNumberStyle), color = c.good)
                    Spacer(Modifier.weight(1f))
                    Text("累计流量", style = MaterialTheme.typography.labelSmall, color = c.textFaint)
                    Spacer(Modifier.weight(1f))
                    Text("↓ ${HxFormat.bytes(item.download)}", style = MaterialTheme.typography.labelMedium.merge(HxNumberStyle), color = c.accent)
                }
            }
        }
    }
}

@Composable
private fun AppConnectionGroup(app: String, list: List<ProxyConnectionUi>, modifier: Modifier, onOpen: (String) -> Unit) {
    val c = Hx.colors
    var open by rememberSaveable(app) { mutableStateOf(false) }
    val haptics = rememberHetuHaptics()
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
            Modifier.fillMaxWidth().clickable { haptics.perform(HetuHaptic.Tick); open = !open }.padding(horizontal = 12.dp, vertical = 10.dp),
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
            Spacer(Modifier.width(4.dp))
            val rotation by animateFloatAsState(if (open) 180f else 0f, HxMotion.glide(), label = "appChevron")
            Icon(
                Icons.Rounded.ExpandMore,
                null,
                tint = c.textFaint,
                modifier = Modifier.size(20.dp).graphicsLayer { rotationZ = rotation },
            )
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
