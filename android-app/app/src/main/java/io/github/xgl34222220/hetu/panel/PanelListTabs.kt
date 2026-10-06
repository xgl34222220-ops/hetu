package io.github.xgl34222220.hetu.panel

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.xgl34222220.hetu.home.HomeCard
import io.github.xgl34222220.hetu.home.HomeDims
import io.github.xgl34222220.hetu.home.HomeFormat
import io.github.xgl34222220.hetu.home.HomeHaptic
import io.github.xgl34222220.hetu.home.HomeIconButton
import io.github.xgl34222220.hetu.home.HomeIcons
import io.github.xgl34222220.hetu.home.HomeMotion
import io.github.xgl34222220.hetu.home.HomePill
import io.github.xgl34222220.hetu.home.HomeProgressBar
import io.github.xgl34222220.hetu.home.HomeSegmentStyle
import io.github.xgl34222220.hetu.home.HomeSegmented
import io.github.xgl34222220.hetu.home.HomeSpinner
import io.github.xgl34222220.hetu.home.HomeTone
import io.github.xgl34222220.hetu.home.HomeType
import io.github.xgl34222220.hetu.home.LocalHomeColors
import io.github.xgl34222220.hetu.home.LocalHomeHaptics
import io.github.xgl34222220.hetu.home.LocalHomeMotionEnabled
import io.github.xgl34222220.hetu.home.badText
import io.github.xgl34222220.hetu.home.fill
import io.github.xgl34222220.hetu.home.goodText
import io.github.xgl34222220.hetu.home.homeEnter
import io.github.xgl34222220.hetu.home.homeTap
import io.github.xgl34222220.hetu.ui.HetuStaggerState
import io.github.xgl34222220.hetu.ui.hetuAnimateItem
import io.github.xgl34222220.hetu.ui.ht

private val ListCard = Modifier.panelGutter().padding(bottom = PanelDims.gap).fillMaxWidth()

/* ------------------------------------------------------------------ */
/*  订阅                                                                */
/* ------------------------------------------------------------------ */

/** One card per provider. Header right side: quota capsule + refresh, or 更新中, or the failure + 重试. */
internal fun LazyListScope.panelSubscriptionsTab(items: List<PanelSubscription>, query: String, stagger: HetuStaggerState, onUpdate: (String) -> Unit) {
    if (items.isEmpty()) {
        item(key = "subs-empty") {
            if (query.isEmpty()) PanelEmptyState(PanelIcons.Inbox, "没有订阅", "当前配置没有带流量信息的远程订阅")
            else PanelEmptyState(PanelIcons.SearchX, "没有匹配的订阅", "尝试其他关键词")
        }
        return
    }
    items(count = items.size, key = { "sub:" + items[it].name }) { index ->
        val sub = items[index]
        val c = LocalHomeColors.current
        val motion = LocalHomeMotionEnabled.current
        HomeCard(ListCard.then(hetuAnimateItem(motion)).homeEnter(stagger, index)) {
            Column(Modifier.padding(start = HomeDims.cardPadding, end = HomeDims.cardPadding, top = 10.dp, bottom = 16.dp)) {
                Row(Modifier.fillMaxWidth().heightIn(min = HomeDims.touch), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(panelHighlight(sub.name, query), Modifier.weight(1f), color = c.t1, style = PanelType.cardTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    when (val state = sub.update) {
                        PanelUpdate.Updating -> HomePill(ht("更新中"), height = 30.dp, style = HomeType.buttonSmall, leading = { HomeSpinner(size = 15.dp, strokeWidth = 2.dp) })
                        is PanelUpdate.Failed -> {
                            HomePill(ht("更新失败：%s").fill(state.reason), Modifier.weight(1f, fill = false), tone = HomeTone.Bad, icon = HomeIcons.CircleAlert, height = 30.dp)
                            HomePill(ht("重试"), height = 30.dp, style = HomeType.buttonSmall, onClick = { onUpdate(sub.name) })
                        }
                        PanelUpdate.Idle -> {
                            sub.remainingPercent?.let { HomePill("$it%", height = 28.dp, style = HomeType.delay.copy(fontWeight = FontWeight.Bold)) }
                            HomeIconButton(HomeIcons.RefreshCw, "更新订阅", { onUpdate(sub.name) }, Modifier.padding(start = 2.dp).size(40.dp))
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top = 2.dp)) {
                    Text(ht("到期 %s").fill(sub.expire ?: HomeFormat.Dash), Modifier.weight(1f), color = c.t1, style = HomeType.rowSub.copy(fontSize = PanelType.groupSummary.fontSize), maxLines = 1)
                    Text(ht("更新于 %s").fill(sub.updatedAt ?: HomeFormat.Dash), color = c.t1, style = HomeType.rowSub.copy(fontSize = PanelType.groupSummary.fontSize), maxLines = 1)
                }
                Row(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 14.dp)) {
                    PanelStat(HomeFormat.bytes(sub.uploadBytes), null, "上传", style = PanelType.statSmall)
                    PanelStat(HomeFormat.bytes(sub.downloadBytes), null, "下载", style = PanelType.statSmall)
                    PanelStat(if (sub.totalBytes > 0L) HomeFormat.bytes(sub.remainingBytes) else HomeFormat.Dash, null, "剩余", valueColor = c.accent, style = PanelType.statSmall)
                }
                HomeProgressBar(sub.usedFraction)
                Row(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                    Text(ht("已用 %s").fill(HomeFormat.bytes(sub.usedBytes)), Modifier.weight(1f), color = c.t1, style = HomeType.rowSub.copy(fontSize = PanelType.groupSummary.fontSize), maxLines = 1)
                    Text(ht("总计 %s").fill(if (sub.totalBytes > 0L) HomeFormat.bytes(sub.totalBytes) else HomeFormat.Dash), color = c.t2, style = HomeType.rowSub.copy(fontSize = PanelType.groupSummary.fontSize), maxLines = 1)
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
    stagger: HetuStaggerState,
    onView: (PanelViewState) -> Unit,
    onOpen: (String) -> Unit,
    onCloseAll: () -> Unit,
) {
    val list = PanelLogic.connections(data, view)
    if (view.searching) item(key = "conn-tools") {
        val c = LocalHomeColors.current
        Row(
            Modifier.fillMaxWidth().padding(bottom = PanelDims.gap).horizontalScroll(rememberScrollState()).panelGutter(),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            HomeSegmented(
                PanelConnFilter.entries.map { it to it.label }, view.connFilter, { onView(view.copy(connFilter = it)) },
                Modifier.width(204.dp), style = HomeSegmentStyle.Raised, height = 36.dp,
            )
            Box(Modifier.size(1.dp, 20.dp).background(c.line2))
            PanelChip("按应用", { onView(view.copy(groupByApp = !view.groupByApp)) }, selected = view.groupByApp, selectedColor = c.t1, selectedFill = c.surface)
        }
    }
    if (list.isEmpty()) {
        item(key = "conn-empty") {
            val narrowed = view.needle.isNotEmpty() || view.connFilter != PanelConnFilter.All
            if (narrowed) PanelEmptyState(PanelIcons.SearchX, "没有匹配的连接", if (view.needle.isNotEmpty()) "尝试其他关键词" else "当前筛选下没有连接")
            else PanelEmptyState(PanelIcons.Unlink, "暂无连接", "新的连接建立后会显示在这里")
        }
        return
    }
    item(key = "conn-header") {
        val c = LocalHomeColors.current
        PanelSectionHeader("连接详情", Modifier.panelGutter().padding(bottom = 4.dp), count = PanelLogic.connectionCount(data, view, list.size).toString()) {
            HomeIconButton(HomeIcons.X, "全部断开", onCloseAll, tint = c.t1)
        }
    }
    if (view.groupByApp) {
        PanelLogic.byApp(list).forEach { group ->
            val open = group.app in view.openApps
            item(key = "app:" + group.app) {
                val c = LocalHomeColors.current
                val motion = LocalHomeMotionEnabled.current
                val turn by animateFloatAsState(if (open) 180f else 0f, HomeMotion.glide(motion), label = "panel-app-chevron")
                HomeCard(
                    ListCard.then(hetuAnimateItem(motion)),
                    onClick = { onView(view.copy(openApps = if (open) view.openApps - group.app else view.openApps + group.app)) },
                    clickLabel = if (open) "收起" else "展开",
                ) {
                    Row(Modifier.fillMaxWidth().heightIn(min = HomeDims.rowMinHeight).padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        PanelAvatar(group.app, group.packageName, size = 38.dp)
                        Column(Modifier.weight(1f)) {
                            Text(if (group.app == PanelLogic.UnattributedApp) ht(group.app) else group.app, color = c.t1, style = HomeType.rowTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(ht("%d 个连接").fill(group.connections.size), color = c.t2, style = HomeType.rowSub, maxLines = 1)
                                FlowTotal(PanelIcons.ArrowDown, HomeFormat.bytes(group.connections.sumOf { it.downloadTotalBytes }))
                                FlowTotal(PanelIcons.ArrowUp, HomeFormat.bytes(group.connections.sumOf { it.uploadTotalBytes }))
                            }
                        }
                        Icon(PanelIcons.ChevronDown, null, Modifier.size(22.dp).graphicsLayer { rotationZ = turn }, tint = c.t1)
                    }
                }
            }
            if (open) items(count = group.connections.size, key = { "conn:" + group.app + ":" + group.connections[it].id }) {
                ConnectionCard(group.connections[it], data, ListCard.then(hetuAnimateItem(LocalHomeMotionEnabled.current)), onOpen)
            }
        }
    } else {
        items(count = list.size, key = { "conn:" + list[it].id }) {
            ConnectionCard(list[it], data, ListCard.then(hetuAnimateItem(LocalHomeMotionEnabled.current)).homeEnter(stagger, it), onOpen)
        }
    }
}

@Composable
private fun FlowTotal(icon: ImageVector, text: String) {
    val c = LocalHomeColors.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        Icon(icon, null, Modifier.size(13.dp), tint = c.t2)
        Text(text, color = c.t2, style = HomeType.rowSub, maxLines = 1)
    }
}

@Composable
private fun ConnectionCard(conn: PanelConnection, data: PanelData, modifier: Modifier, onOpen: (String) -> Unit) {
    val c = LocalHomeColors.current
    HomeCard(modifier, onClick = { onOpen(conn.id) }, clickLabel = "连接详情", shape = PanelDims.groupShape) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 11.dp, bottom = 12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(conn.host, Modifier.weight(1f), color = c.t1, style = PanelType.host, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (conn.timeLabel != null) Text(conn.timeLabel, color = c.t2, style = PanelType.tiny, maxLines = 1)
            }
            if (conn.meta.isNotEmpty()) Text(conn.meta, Modifier.padding(top = 2.dp), color = c.t2, style = PanelType.tiny, maxLines = 1)
            Row(Modifier.fillMaxWidth().padding(top = 7.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (conn.app.isNotBlank()) {
                    PanelAvatar(conn.app, conn.packageName, size = 18.dp)
                    Text(conn.app, Modifier.weight(1f, fill = false), color = c.t2, style = HomeType.rowSub, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("/", color = c.t3, style = HomeType.rowSub)
                }
                ChainMark(conn, data)
                Text(
                    conn.chain.joinToString(" / ").ifEmpty { "DIRECT" }, Modifier.weight(1f),
                    color = c.t2, style = HomeType.rowSub, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                FlowStat(PanelIcons.ArrowUp, c.goodText, HomeFormat.speed(conn.uploadBytesPerSecond), HomeFormat.bytes(conn.uploadTotalBytes), Modifier.weight(1f))
                FlowStat(PanelIcons.ArrowDown, c.accent, HomeFormat.speed(conn.downloadBytesPerSecond), HomeFormat.bytes(conn.downloadTotalBytes), Modifier)
            }
        }
    }
}

/** The icon of the strategy group that carries the connection, when the host supplies group artwork. */
@Composable
private fun ChainMark(conn: PanelConnection, data: PanelData) {
    val slot = LocalPanelGroupIcon.current ?: return
    val head = conn.chain.firstOrNull() ?: return
    val group = data.groups.firstOrNull { it.name == head } ?: return
    slot(group, Modifier.size(16.dp))
}

@Composable
private fun FlowStat(icon: ImageVector, tint: Color, rate: String, total: String, modifier: Modifier) {
    val c = LocalHomeColors.current
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Icon(icon, null, Modifier.size(15.dp), tint = tint)
        Text(rate, color = c.t1, style = PanelType.tiny.copy(fontWeight = FontWeight.Medium), maxLines = 1)
        Text(ht("总计 %s").fill(total), color = c.t2, style = PanelType.tiny, maxLines = 1)
    }
}

/* ------------------------------------------------------------------ */
/*  规则                                                                */
/* ------------------------------------------------------------------ */

/** REJECT reads as a warning; every other policy (DIRECT included) uses the accent, as in the concept. */
@Composable
internal fun policyColor(policy: String): Color {
    val c = LocalHomeColors.current
    return if (policy.equals("REJECT", true) || policy.startsWith("REJECT-", true)) c.badText else c.accent
}

/** 规则 tab: one card per rule, in config order. */
internal fun LazyListScope.panelRulesTab(items: List<PanelRule>, query: String, total: Int, stagger: HetuStaggerState) {
    if (items.isEmpty()) {
        item(key = "rules-empty") {
            if (query.isEmpty()) PanelEmptyState(PanelIcons.Route, "没有规则", "当前配置没有分流规则") else PanelEmptyState(PanelIcons.SearchX, "没有匹配的规则", "尝试其他关键词")
        }
        return
    }
    items(count = items.size, contentType = { "rule" }) { index ->
        val rule = items[index]
        val c = LocalHomeColors.current
        HomeCard(ListCard.homeEnter(stagger, index), shape = PanelDims.groupShape) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(horizontal = HomeDims.cardPadding, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(rule.type, color = c.t1, style = HomeType.rowTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (rule.payload.isNotBlank()) Text(panelHighlight(rule.payload, query), color = c.t2, style = PanelType.groupSummary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(panelHighlight(rule.policy, query), Modifier.weight(1f, fill = false), color = policyColor(rule.policy), style = PanelType.policy, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
    item(key = "rules-note") {
        val c = LocalHomeColors.current
        val shown = if (items.size < total) ht("，此处显示 %d 条").fill(items.size) else ""
        Text(ht("按配置顺序自上而下匹配，共 %d 条").fill(total) + shown + "。", Modifier.panelGutter().padding(start = 6.dp, end = 6.dp, top = 2.dp, bottom = 10.dp), color = c.t3, style = HomeType.caption)
    }
}

/* ------------------------------------------------------------------ */
/*  规则集                                                              */
/* ------------------------------------------------------------------ */

/** 规则集 tab: name + rule count, format line, update line; trailing download / spinner / 重试. */
internal fun LazyListScope.panelRuleSetsTab(items: List<PanelRuleSet>, query: String, stagger: HetuStaggerState, onUpdate: (String) -> Unit) {
    if (items.isEmpty()) {
        item(key = "rs-empty") {
            if (query.isEmpty()) PanelEmptyState(PanelIcons.Layers, "没有规则集", "当前配置没有 rule-providers") else PanelEmptyState(PanelIcons.SearchX, "没有匹配的规则集", "尝试其他关键词")
        }
        return
    }
    items(count = items.size, key = { "rs:" + items[it].name }) { index ->
        val set = items[index]
        val c = LocalHomeColors.current
        val haptics = LocalHomeHaptics.current
        val motion = LocalHomeMotionEnabled.current
        HomeCard(ListCard.then(hetuAnimateItem(motion)).homeEnter(stagger, index), shape = PanelDims.groupShape) {
            Row(Modifier.fillMaxWidth().heightIn(min = 82.dp).padding(start = HomeDims.cardPadding, end = 8.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(panelHighlight(set.name, query), Modifier.weight(1f, fill = false), color = c.t1, style = HomeType.rowTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(ht("%d 条规则").fill(set.ruleCount), color = c.accent, style = PanelType.groupSummary.copy(fontWeight = FontWeight.Medium), maxLines = 1)
                    }
                    Text(set.meta, color = c.t2, style = PanelType.groupSummary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    when (val state = set.update) {
                        PanelUpdate.Updating -> Text(ht("更新中"), color = c.t1, style = PanelType.groupSummary)
                        is PanelUpdate.Failed -> Text(ht("更新失败：%s").fill(state.reason), color = c.badText, style = PanelType.groupSummary.copy(fontWeight = FontWeight.Medium), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        PanelUpdate.Idle -> Text(ht("更新于 %s").fill(set.updatedAt ?: HomeFormat.Dash), color = c.t1, style = PanelType.groupSummary, maxLines = 1)
                    }
                }
                when (set.update) {
                    PanelUpdate.Updating -> Box(Modifier.size(HomeDims.touch), contentAlignment = Alignment.Center) { HomeSpinner(size = 24.dp, strokeWidth = 2.5.dp) }
                    is PanelUpdate.Failed -> Box(
                        Modifier.heightIn(min = HomeDims.touch).clip(HomeDims.controlShape).homeTap(role = Role.Button) { haptics(HomeHaptic.Tap); onUpdate(set.name) }.padding(horizontal = 12.dp),
                        contentAlignment = Alignment.Center,
                    ) { Text(ht("重试"), color = c.accent, style = HomeType.button) }
                    PanelUpdate.Idle -> HomeIconButton(PanelIcons.Download, "更新规则集", { onUpdate(set.name) }, tint = c.accent)
                }
            }
        }
    }
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
 * Entries longer than two lines are clamped and open with a short height animation on tap.
 */
internal fun LazyListScope.panelLogsTab(
    items: List<PanelLogEntry>,
    view: PanelViewState,
    stagger: HetuStaggerState,
    onView: (PanelViewState) -> Unit,
    onOrderMenu: () -> Unit,
    orderMenu: @Composable () -> Unit,
) {
    item(key = "log-filters") {
        val c = LocalHomeColors.current
        Row(
            Modifier.fillMaxWidth().padding(bottom = PanelDims.gap).horizontalScroll(rememberScrollState()).panelGutter(),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PanelLogFilter.entries.forEach { filter ->
                PanelChip(filter.label, { onView(view.copy(logFilter = filter)) }, selected = view.logFilter == filter, height = 33.dp)
            }
            Spacer(Modifier.width(4.dp))
            Box {
                PanelChip(view.logOrder.label, onOrderMenu, selected = true, selectedColor = c.t1, selectedFill = c.surface, height = 33.dp, trailingIcon = PanelIcons.ChevronDown)
                orderMenu()
            }
        }
    }
    if (items.isEmpty()) {
        item(key = "log-empty") {
            val filtered = view.needle.isNotEmpty() || view.logFilter != PanelLogFilter.All
            if (filtered) PanelEmptyState(PanelIcons.SearchX, "没有匹配的日志", if (view.needle.isNotEmpty()) "尝试其他关键词" else "当前等级下暂无日志")
            else PanelEmptyState(PanelIcons.ScrollText, "暂无日志", "核心输出日志后会显示在这里")
        }
        return
    }
    items(count = items.size, key = { "log:" + items[it].id }) { index ->
        val entry = items[index]
        val open = entry.id in view.openLogs
        val c = LocalHomeColors.current
        val motion = LocalHomeMotionEnabled.current
        HomeCard(
            ListCard.then(hetuAnimateItem(motion)).homeEnter(stagger, index),
            onClick = if (entry.foldable) { { onView(view.copy(openLogs = if (open) view.openLogs - entry.id else view.openLogs + entry.id)) } } else null,
            clickLabel = if (open) "收起" else "展开",
            shape = PanelDims.groupShape,
        ) {
            Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 13.dp, bottom = 13.dp).let { if (motion) it.animateContentSize(HomeMotion.glide(true)) else it }) {
                Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    HomePill(entry.level.label, tone = entry.level.tone())
                    Spacer(Modifier.weight(1f))
                    Text(entry.time, color = c.t2, style = PanelType.groupSummary, maxLines = 1)
                }
                Text(panelHighlight(entry.message, view.needle), color = c.t1, style = PanelType.log, maxLines = if (open || !entry.foldable) Int.MAX_VALUE else 2, overflow = TextOverflow.Ellipsis)
                if (entry.foldable) Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
                    Row(
                        Modifier.height(30.dp).clip(HomeDims.pillShape).background(if (c.dark) c.sunken else c.bg).padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Icon(if (open) PanelIcons.ChevronUp else PanelIcons.ChevronDown, null, Modifier.size(16.dp), tint = c.t1)
                        Text(ht(if (open) "收起" else "展开"), color = c.t1, style = HomeType.note.copy(fontWeight = FontWeight.Medium))
                    }
                }
            }
        }
    }
}
