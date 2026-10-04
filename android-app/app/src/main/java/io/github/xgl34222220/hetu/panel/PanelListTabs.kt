package io.github.xgl34222220.hetu.panel

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.xgl34222220.hetu.home.HomeBadge
import io.github.xgl34222220.hetu.home.HomeCard
import io.github.xgl34222220.hetu.home.HomeDims
import io.github.xgl34222220.hetu.home.HomeFormat
import io.github.xgl34222220.hetu.home.HomeHaptic
import io.github.xgl34222220.hetu.home.HomeIconButton
import io.github.xgl34222220.hetu.home.HomeIcons
import io.github.xgl34222220.hetu.home.HomeProgressBar
import io.github.xgl34222220.hetu.home.HomeSegmented
import io.github.xgl34222220.hetu.home.HomeSpinner
import io.github.xgl34222220.hetu.home.HomeTone
import io.github.xgl34222220.hetu.home.HomeType
import io.github.xgl34222220.hetu.home.LocalHomeColors
import io.github.xgl34222220.hetu.home.LocalHomeHaptics

/* ------------------------------------------------------------------ */
/*  订阅                                                                */
/* ------------------------------------------------------------------ */

/** One card per provider. Header right side: quota badge + refresh, or 更新中, or the failure + 重试. */
internal fun LazyListScope.panelSubscriptionsTab(items: List<PanelSubscription>, query: String, onUpdate: (String) -> Unit) {
    if (items.isEmpty()) {
        item(key = "subs-empty") {
            if (query.isEmpty()) PanelEmptyState(HomeIcons.Info, "没有订阅", "当前配置没有带流量信息的远程订阅")
            else PanelEmptyState(PanelIcons.Search, "没有匹配的订阅", "尝试其他关键词")
        }
        return
    }
    items(count = items.size, key = { "sub:" + items[it].name }) { index ->
        val sub = items[index]
        val c = LocalHomeColors.current
        HomeCard(Modifier.panelGutter().padding(bottom = 8.dp).fillMaxWidth()) {
            Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp)) {
                Row(Modifier.fillMaxWidth().heightIn(min = HomeDims.touch), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(panelHighlight(sub.name, query), Modifier.weight(1f), color = c.t1, style = PanelType.cardTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    when (val state = sub.update) {
                        PanelUpdate.Updating -> Row(
                            Modifier.height(20.dp).clip(HomeDims.badgeShape).background(c.accentSoft).padding(horizontal = 6.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            HomeSpinner(size = 11.dp, strokeWidth = 1.5.dp)
                            Text("更新中", color = c.accent, style = HomeType.badge)
                        }
                        is PanelUpdate.Failed -> {
                            HomeBadge("更新失败：${state.reason}", tone = HomeTone.Bad, icon = HomeIcons.CircleAlert)
                            HomeBadge("重试", tone = HomeTone.Accent, onClick = { onUpdate(sub.name) })
                        }
                        PanelUpdate.Idle -> {
                            sub.remainingPercent?.let { HomeBadge("$it%", tone = HomeTone.Accent) }
                            HomeIconButton(HomeIcons.RefreshCw, "更新订阅", { onUpdate(sub.name) }, Modifier.padding(end = 0.dp), tint = c.t2)
                        }
                    }
                }
                Row(Modifier.fillMaxWidth()) {
                    Text("到期 ${sub.expire ?: HomeFormat.Dash}", Modifier.weight(1f), color = c.t2, style = HomeType.rowSub, maxLines = 1)
                    Text("更新于 ${sub.updatedAt ?: HomeFormat.Dash}", color = c.t2, style = HomeType.rowSub, maxLines = 1)
                }
                Row(Modifier.fillMaxWidth().padding(vertical = 14.dp)) {
                    PanelStat(HomeFormat.bytes(sub.uploadBytes), null, "上传", style = PanelType.statSmall)
                    PanelStat(HomeFormat.bytes(sub.downloadBytes), null, "下载", style = PanelType.statSmall)
                    PanelStat(if (sub.totalBytes > 0L) HomeFormat.bytes(sub.remainingBytes) else HomeFormat.Dash, null, "剩余", valueColor = c.accent, style = PanelType.statSmall)
                }
                HomeProgressBar(sub.usedFraction)
                Row(Modifier.fillMaxWidth().padding(top = 10.dp)) {
                    Text("已用 ${HomeFormat.bytes(sub.usedBytes)}", Modifier.weight(1f), color = c.t2, style = HomeType.rowSub, maxLines = 1)
                    Text("总计 ${if (sub.totalBytes > 0L) HomeFormat.bytes(sub.totalBytes) else HomeFormat.Dash}", color = c.t2, style = HomeType.rowSub, maxLines = 1)
                }
            }
        }
    }
}

/* ------------------------------------------------------------------ */
/*  连接                                                                */
/* ------------------------------------------------------------------ */

/**
 * 连接 tab. While searching, an inline filter strip (全部 / 代理 / 直连 | 按应用) appears above the list.
 * With “按应用分组” on, each app is a collapsible header followed by its connections.
 */
internal fun LazyListScope.panelConnectionsTab(
    data: PanelData,
    view: PanelViewState,
    onView: (PanelViewState) -> Unit,
    onOpen: (String) -> Unit,
    onCloseAll: () -> Unit,
) {
    val list = PanelLogic.connections(data, view)
    if (view.searching) item(key = "conn-tools") {
        val c = LocalHomeColors.current
        Row(
            Modifier.fillMaxWidth().padding(bottom = 8.dp).horizontalScroll(rememberScrollState()).panelGutter(),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            HomeSegmented(PanelConnFilter.entries.map { it to it.label }, view.connFilter, { onView(view.copy(connFilter = it)) }, Modifier.width(192.dp))
            Box(Modifier.size(1.dp, 16.dp).background(c.line2))
            PanelChip("按应用", { onView(view.copy(groupByApp = !view.groupByApp)) }, selected = view.groupByApp)
        }
    }
    if (list.isEmpty()) {
        item(key = "conn-empty") {
            val narrowed = view.needle.isNotEmpty() || view.connFilter != PanelConnFilter.All
            if (narrowed) PanelEmptyState(PanelIcons.Search, "没有匹配的连接", if (view.needle.isNotEmpty()) "尝试其他关键词" else "当前筛选下没有连接")
            else PanelEmptyState(PanelIcons.Unlink, "暂无连接", "新的连接建立后会显示在这里")
        }
        return
    }
    item(key = "conn-header") {
        val c = LocalHomeColors.current
        val haptics = LocalHomeHaptics.current
        PanelSectionHeader("连接详情", Modifier.panelGutter().padding(bottom = 8.dp), count = PanelLogic.connectionCount(data, view, list.size).toString()) {
            Row(
                Modifier.heightIn(min = 28.dp).clip(HomeDims.badgeShape).clickable(role = Role.Button) { haptics(HomeHaptic.Tap); onCloseAll() }.padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(HomeIcons.X, null, Modifier.size(16.dp), tint = c.bad)
                Text("全部断开", color = c.bad, style = HomeType.section)
            }
        }
    }
    if (view.groupByApp) {
        PanelLogic.byApp(list).forEach { group ->
            val open = group.app in view.openApps
            item(key = "app:" + group.app) {
                val c = LocalHomeColors.current
                HomeCard(
                    Modifier.panelGutter().padding(bottom = 8.dp).fillMaxWidth(),
                    onClick = { onView(view.copy(openApps = if (open) view.openApps - group.app else view.openApps + group.app)) },
                    clickLabel = if (open) "收起" else "展开",
                ) {
                    Row(Modifier.fillMaxWidth().heightIn(min = HomeDims.rowMinHeight).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        PanelAvatar(group.app, group.packageName)
                        Column(Modifier.weight(1f)) {
                            Text(group.app, color = c.t1, style = HomeType.rowTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${group.connections.size} 个连接", color = c.t2, style = HomeType.rowSub)
                        }
                        Icon(if (open) PanelIcons.ChevronUp else PanelIcons.ChevronDown, null, Modifier.size(16.dp), tint = c.t3)
                    }
                }
            }
            if (open) items(count = group.connections.size, key = { "conn:" + group.app + ":" + group.connections[it].id }) { ConnectionCard(group.connections[it], onOpen) }
        }
    } else {
        items(count = list.size, key = { "conn:" + list[it].id }) { ConnectionCard(list[it], onOpen) }
    }
}

@Composable
private fun ConnectionCard(conn: PanelConnection, onOpen: (String) -> Unit) {
    val c = LocalHomeColors.current
    HomeCard(Modifier.panelGutter().padding(bottom = 8.dp).fillMaxWidth(), onClick = { onOpen(conn.id) }, clickLabel = "连接详情") {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(conn.host, Modifier.weight(1f), color = c.t1, style = PanelType.host, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (conn.time != null) Text(conn.time, color = c.t3, style = PanelType.tiny, maxLines = 1)
            }
            if (conn.meta.isNotEmpty()) Text(conn.meta, Modifier.padding(top = 2.dp), color = c.t3, style = PanelType.tiny, maxLines = 1)
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (conn.app.isNotBlank()) {
                    PanelAvatar(conn.app, conn.packageName, size = 20.dp)
                    Text(conn.app, color = c.t2, style = HomeType.rowSub, maxLines = 1)
                    Text("/", color = c.t3, style = HomeType.rowSub)
                }
                Text(conn.chainText.ifEmpty { "DIRECT" }, Modifier.weight(1f), color = if (conn.isDirect) c.t3 else c.t2, style = HomeType.rowSub, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                FlowStat(PanelIcons.ArrowUp, c.good, HomeFormat.speed(conn.uploadBytesPerSecond), HomeFormat.bytes(conn.uploadTotalBytes), Modifier.weight(1f))
                FlowStat(PanelIcons.ArrowDown, c.accent, HomeFormat.speed(conn.downloadBytesPerSecond), HomeFormat.bytes(conn.downloadTotalBytes), Modifier)
            }
        }
    }
}

@Composable
private fun FlowStat(icon: ImageVector, tint: Color, rate: String, total: String, modifier: Modifier) {
    val c = LocalHomeColors.current
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Icon(icon, null, Modifier.size(12.dp), tint = tint)
        Text(rate, color = c.t2, style = PanelType.tiny.copy(fontWeight = PanelType.tab.fontWeight), maxLines = 1)
        Text("总计 $total", color = c.t3, style = PanelType.tiny, maxLines = 1)
    }
}

/* ------------------------------------------------------------------ */
/*  规则                                                                */
/* ------------------------------------------------------------------ */

@Composable
internal fun policyColor(policy: String): Color {
    val c = LocalHomeColors.current
    return when {
        policy.equals("REJECT", true) || policy.startsWith("REJECT-", true) -> c.bad
        policy.equals("DIRECT", true) -> c.t2
        else -> c.accent
    }
}

/** 规则 tab: one long card, a row per rule in config order. Policy colour: REJECT red, DIRECT grey, proxy accent. */
internal fun LazyListScope.panelRulesTab(items: List<PanelRule>, query: String, total: Int) {
    if (items.isEmpty()) {
        item(key = "rules-empty") {
            if (query.isEmpty()) PanelEmptyState(HomeIcons.Info, "没有规则", "当前配置没有分流规则") else PanelEmptyState(PanelIcons.Search, "没有匹配的规则", "尝试其他关键词")
        }
        return
    }
    items(count = items.size, contentType = { "rule" }) { index ->
        val rule = items[index]
        val c = LocalHomeColors.current
        Row(
            Modifier.panelGutter().fillMaxWidth().cardPiece(PiecePosition.of(index, items.size)).heightIn(min = HomeDims.rowMinHeight).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(rule.type, color = c.t1, style = HomeType.rowTitle, maxLines = 1)
                Text(panelHighlight(rule.payload, query), color = c.t2, style = PanelType.monoSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(panelHighlight(rule.policy, query), color = policyColor(rule.policy), style = PanelType.policy, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
    item(key = "rules-note") {
        val c = LocalHomeColors.current
        val shown = if (items.size < total) "，此处显示 ${items.size} 条" else ""
        Text("按配置顺序自上而下匹配，共 $total 条$shown。", Modifier.panelGutter().padding(start = 4.dp, end = 4.dp, top = 8.dp, bottom = 8.dp), color = c.t3, style = HomeType.caption)
    }
}

/* ------------------------------------------------------------------ */
/*  规则集                                                              */
/* ------------------------------------------------------------------ */

/** 规则集 tab: name + rule count, format line, update line; trailing download / spinner / 重试. */
internal fun LazyListScope.panelRuleSetsTab(items: List<PanelRuleSet>, query: String, onUpdate: (String) -> Unit) {
    if (items.isEmpty()) {
        item(key = "rs-empty") {
            if (query.isEmpty()) PanelEmptyState(HomeIcons.Info, "没有规则集", "当前配置没有 rule-providers") else PanelEmptyState(PanelIcons.Search, "没有匹配的规则集", "尝试其他关键词")
        }
        return
    }
    items(count = items.size, key = { "rs:" + items[it].name }) { index ->
        val set = items[index]
        val c = LocalHomeColors.current
        val haptics = LocalHomeHaptics.current
        Row(
            Modifier.panelGutter().fillMaxWidth().cardPiece(PiecePosition.of(index, items.size)).heightIn(min = 72.dp).padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(panelHighlight(set.name, query), color = c.t1, style = HomeType.rowTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${set.ruleCount} 条规则", color = c.accent, style = HomeType.note.copy(fontWeight = PanelType.tab.fontWeight, fontFeatureSettings = "tnum"), maxLines = 1)
                }
                Text(set.meta, color = c.t2, style = HomeType.rowSub, maxLines = 1)
                when (val state = set.update) {
                    PanelUpdate.Updating -> Text("更新中", color = c.t2, style = HomeType.rowSub)
                    is PanelUpdate.Failed -> Text("更新失败：${state.reason}", color = c.bad, style = HomeType.rowSub, maxLines = 1)
                    PanelUpdate.Idle -> Text("更新于 ${set.updatedAt ?: HomeFormat.Dash}", color = c.t2, style = HomeType.rowSub, maxLines = 1)
                }
            }
            when (set.update) {
                PanelUpdate.Updating -> Box(Modifier.size(40.dp, HomeDims.touch), contentAlignment = Alignment.Center) { HomeSpinner() }
                is PanelUpdate.Failed -> Box(
                    Modifier.heightIn(min = 36.dp).clip(HomeDims.controlShape).clickable(role = Role.Button) { haptics(HomeHaptic.Tap); onUpdate(set.name) }.padding(horizontal = 12.dp),
                    contentAlignment = Alignment.Center,
                ) { Text("重试", color = c.accent, style = HomeType.buttonSmall) }
                PanelUpdate.Idle -> HomeIconButton(PanelIcons.Download, "更新规则集", { onUpdate(set.name) }, tint = c.accent)
            }
        }
    }
    item(key = "rs-gap") { Spacer(Modifier.height(8.dp)) }
}

/* ------------------------------------------------------------------ */
/*  日志                                                                */
/* ------------------------------------------------------------------ */

internal fun PanelLogLevel.tone(): HomeTone = when (this) {
    PanelLogLevel.Debug -> HomeTone.Neutral
    PanelLogLevel.Info -> HomeTone.Accent
    PanelLogLevel.Warn -> HomeTone.Warn
    PanelLogLevel.Error -> HomeTone.Bad
}

/**
 * 日志 tab: level chips + order chip (anchored menu), then one card per entry.
 * Entries longer than two lines are clamped and toggle open on tap.
 */
internal fun LazyListScope.panelLogsTab(
    items: List<PanelLogEntry>,
    view: PanelViewState,
    onView: (PanelViewState) -> Unit,
    onOrderMenu: () -> Unit,
    orderMenu: @Composable () -> Unit,
) {
    item(key = "log-filters") {
        Row(Modifier.panelGutter().padding(bottom = 8.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            PanelLogFilter.entries.forEach { filter ->
                PanelChip(filter.label, { onView(view.copy(logFilter = filter)) }, selected = view.logFilter == filter)
            }
            Spacer(Modifier.weight(1f))
            Box {
                PanelChip(view.logOrder.label, onOrderMenu, trailingIcon = PanelIcons.ChevronDown)
                orderMenu()
            }
        }
    }
    if (items.isEmpty()) {
        item(key = "log-empty") { PanelEmptyState(PanelIcons.Search, "没有匹配的日志", if (view.needle.isNotEmpty()) "尝试其他关键词" else "当前等级下暂无日志") }
        return
    }
    items(count = items.size, key = { "log:" + items[it].id }) { index ->
        val entry = items[index]
        val open = entry.id in view.openLogs
        val c = LocalHomeColors.current
        HomeCard(
            Modifier.panelGutter().padding(bottom = 8.dp).fillMaxWidth(),
            onClick = if (entry.foldable) { { onView(view.copy(openLogs = if (open) view.openLogs - entry.id else view.openLogs + entry.id)) } } else null,
            clickLabel = if (open) "收起" else "展开",
        ) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    HomeBadge(entry.level.label, tone = entry.level.tone())
                    Spacer(Modifier.weight(1f))
                    Text(entry.time, color = c.t3, style = PanelType.tiny, maxLines = 1)
                }
                Text(panelHighlight(entry.message, view.needle), color = c.t1, style = PanelType.log, maxLines = if (open || !entry.foldable) Int.MAX_VALUE else 2, overflow = TextOverflow.Ellipsis)
                if (entry.foldable) Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End)) {
                    Icon(if (open) PanelIcons.ChevronUp else PanelIcons.ChevronDown, null, Modifier.size(14.dp), tint = c.t2)
                    Text(if (open) "收起" else "展开", color = c.t2, style = HomeType.caption)
                }
            }
        }
    }
}
