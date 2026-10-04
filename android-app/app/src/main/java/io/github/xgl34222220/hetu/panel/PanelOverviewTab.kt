package io.github.xgl34222220.hetu.panel

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.xgl34222220.hetu.home.HomeCard
import io.github.xgl34222220.hetu.home.HomeDims
import io.github.xgl34222220.hetu.home.HomeDivider
import io.github.xgl34222220.hetu.home.HomeFormat
import io.github.xgl34222220.hetu.home.HomeIconButton
import io.github.xgl34222220.hetu.home.HomeProgressBar
import io.github.xgl34222220.hetu.home.HomeStatusDot
import io.github.xgl34222220.hetu.home.HomeType
import io.github.xgl34222220.hetu.home.LocalHomeColors

/** Lazy-list keys of the overview items; [PanelOverviewItems.Trend] is where B04 / B16 / B20 are scrolled to. */
internal object PanelOverviewItems {
    const val Summary = "ov-summary"
    const val Subscription = "ov-subscription"
    const val Speed = "ov-speed"
    const val Total = "ov-total"
    const val Trend = "ov-trend"
    const val Rank = "ov-rank"
}

/**
 * 概览 tab: 运行概况 → 订阅 → 上 / 下行速度 → 总流量 → 近期趋势 → 实时排行.
 * @param rankMenu anchored menu for “显示数量”, drawn next to the ⋯ button of the rank card.
 */
internal fun LazyListScope.panelOverviewTab(
    overview: PanelOverview,
    view: PanelViewState,
    onView: (PanelViewState) -> Unit,
    onOpenSubscriptions: () -> Unit,
    onRankCountMenu: () -> Unit,
    rankMenu: @Composable () -> Unit,
) {
    item(key = PanelOverviewItems.Summary) {
        val c = LocalHomeColors.current
        HomeCard(Modifier.panelGutter().padding(bottom = 8.dp).fillMaxWidth()) {
            Column(Modifier.padding(HomeDims.cardPadding)) {
                Text("运行概况", Modifier.padding(start = 4.dp, bottom = 12.dp), color = c.t2, style = HomeType.section)
                Row(Modifier.fillMaxWidth()) {
                    PanelStat(overview.strategyCount.toString(), null, "策略")
                    PanelStat(overview.ruleCount.toString(), null, "规则")
                    PanelStat(overview.connectionCount.toString(), null, "当前连接")
                }
            }
        }
    }
    val sub = overview.subscription
    if (sub != null) item(key = PanelOverviewItems.Subscription) {
        val c = LocalHomeColors.current
        HomeCard(Modifier.panelGutter().padding(bottom = 8.dp).fillMaxWidth(), onClick = onOpenSubscriptions, clickLabel = "查看订阅") {
            Column(Modifier.padding(HomeDims.cardPadding)) {
                Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("订阅", Modifier.weight(1f), color = c.t2, style = HomeType.section)
                    if (sub.expire != null) Text("到期 ${sub.expire}", color = c.t3, style = PanelType.tiny)
                }
                Row(Modifier.fillMaxWidth()) {
                    val (used, usedUnit) = split(HomeFormat.bytes(sub.usedBytes))
                    val (left, leftUnit) = split(HomeFormat.bytes(sub.remainingBytes))
                    val (total, totalUnit) = split(HomeFormat.bytes(sub.totalBytes))
                    PanelStat(used, usedUnit, "已用")
                    PanelStat(left, leftUnit, "剩余", valueColor = c.accent)
                    PanelStat(total, totalUnit, "总量")
                }
                HomeProgressBar(sub.usedFraction, Modifier.padding(top = 14.dp, bottom = 10.dp))
                Row(Modifier.fillMaxWidth()) {
                    Text("${sub.subscriptionCount} 个订阅", Modifier.weight(1f), color = c.t3, style = PanelType.tiny)
                    Text("${sub.nodeCount} 个节点", color = c.t3, style = PanelType.tiny)
                }
            }
        }
    }
    item(key = PanelOverviewItems.Speed) {
        val c = LocalHomeColors.current
        Row(Modifier.panelGutter().padding(bottom = 8.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SpeedCard("上行速度", HomeFormat.speed(overview.uploadBytesPerSecond), PanelIcons.ArrowUp, c.good, Modifier.weight(1f))
            SpeedCard("下行速度", HomeFormat.speed(overview.downloadBytesPerSecond), PanelIcons.ArrowDown, c.accent, Modifier.weight(1f))
        }
    }
    item(key = PanelOverviewItems.Total) {
        val c = LocalHomeColors.current
        HomeCard(Modifier.panelGutter().padding(bottom = 8.dp).fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().heightIn(min = HomeDims.rowMinHeightSmall).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("总流量", Modifier.weight(1f), color = c.t1, style = HomeType.rowTitle)
                TotalValue("↑", HomeFormat.bytes(overview.uploadTotalBytes), c.good)
                TotalValue("↓", HomeFormat.bytes(overview.downloadTotalBytes), c.accent)
            }
        }
    }
    item(key = PanelOverviewItems.Trend) {
        val c = LocalHomeColors.current
        HomeCard(Modifier.panelGutter().padding(bottom = 8.dp).fillMaxWidth()) {
            Column(Modifier.padding(HomeDims.cardPadding)) {
                Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = Alignment.Top) {
                    Column(Modifier.weight(1f)) {
                        Text("近期趋势", color = c.t1, style = HomeType.rowTitle.copy(fontWeight = HomeType.value.fontWeight))
                        Text("最近 60 秒", color = c.t3, style = HomeType.caption)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        PanelChip("上行", { onView(view.copy(showUploadTrend = !view.showUploadTrend)) }, selected = view.showUploadTrend,
                            selectedColor = c.good, selectedFill = c.goodSoft, height = 26.dp, leading = { HomeStatusDot(if (view.showUploadTrend) c.good else c.t3, size = 6.dp) })
                        PanelChip("下行", { onView(view.copy(showDownloadTrend = !view.showDownloadTrend)) }, selected = view.showDownloadTrend,
                            height = 26.dp, leading = { HomeStatusDot(if (view.showDownloadTrend) c.accent else c.t3, size = 6.dp) })
                    }
                }
                PanelTrendChart(
                    buildList {
                        if (view.showDownloadTrend) add(PanelTrendSeries(overview.downloadTrend, c.accent, fill = true))
                        if (view.showUploadTrend) add(PanelTrendSeries(overview.uploadTrend, c.good, fill = false))
                    },
                )
            }
        }
    }
    item(key = PanelOverviewItems.Rank) {
        val c = LocalHomeColors.current
        val ranks = PanelLogic.ranks(overview, view.rankMode, view.rankCount)
        HomeCard(Modifier.panelGutter().padding(bottom = 8.dp).fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().heightIn(min = HomeDims.rowMinHeight).padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("实时排行", color = c.t1, style = HomeType.rowTitle.copy(fontWeight = HomeType.value.fontWeight))
                    Text(view.rankMode.label, color = c.t2, style = HomeType.rowSub)
                }
                Box {
                    HomeIconButton(PanelIcons.Ellipsis, "显示数量", onRankCountMenu, tint = c.t2)
                    rankMenu()
                }
            }
            if (ranks.isEmpty()) {
                HomeDivider()
                Text("暂无应用流量", Modifier.padding(16.dp), color = c.t3, style = HomeType.note)
            }
            ranks.forEach { rank ->
                HomeDivider()
                Row(
                    Modifier.fillMaxWidth().heightIn(min = HomeDims.rowMinHeight).padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    PanelAvatar(rank.app, rank.packageName)
                    Column(Modifier.weight(1f)) {
                        Text(rank.app, color = c.t1, style = HomeType.rowTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("下行 ${HomeFormat.speed(rank.downloadBytesPerSecond)} · 上行 ${HomeFormat.speed(rank.uploadBytesPerSecond)}", color = c.t2, style = HomeType.rowSub, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Text(
                        if (view.rankMode == PanelRankMode.Connections) "${rank.connections} 条连接" else HomeFormat.bytes(rank.totalBytes),
                        color = c.t2, style = HomeType.rowSub, maxLines = 1,
                    )
                }
            }
        }
    }
}

/** “20 GB” → ("20", "GB"). */
private fun split(text: String): Pair<String, String?> {
    val at = text.lastIndexOf(' ')
    return if (at <= 0) text to null else text.substring(0, at) to text.substring(at + 1)
}

@Composable
private fun SpeedCard(label: String, value: String, icon: ImageVector, tint: Color, modifier: Modifier) {
    val c = LocalHomeColors.current
    HomeCard(modifier) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(32.dp).background(c.sunken, RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
                Icon(icon, null, Modifier.size(20.dp), tint = tint)
            }
            Column {
                Text(label, color = c.t3, style = HomeType.caption, maxLines = 1)
                Text(value, color = c.t1, style = HomeType.metric.copy(lineHeight = HomeType.sheetTitle.lineHeight), maxLines = 1)
            }
        }
    }
}

@Composable
private fun TotalValue(arrow: String, value: String, tint: Color) {
    val c = LocalHomeColors.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(arrow, color = tint, style = HomeType.label)
        Spacer(Modifier.size(4.dp))
        Text(value, color = c.t2, style = HomeType.label.copy(fontFeatureSettings = "tnum"), maxLines = 1)
    }
}
