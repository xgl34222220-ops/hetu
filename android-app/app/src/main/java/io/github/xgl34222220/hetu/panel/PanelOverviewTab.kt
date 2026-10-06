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
import androidx.compose.foundation.layout.width
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
import io.github.xgl34222220.hetu.home.HomeRollingText
import io.github.xgl34222220.hetu.home.HomeStatusDot
import io.github.xgl34222220.hetu.home.HomeType
import io.github.xgl34222220.hetu.home.LocalHomeColors
import io.github.xgl34222220.hetu.home.fill
import io.github.xgl34222220.hetu.home.goodText
import io.github.xgl34222220.hetu.home.homeEnter
import io.github.xgl34222220.hetu.home.rememberHomeReveal
import io.github.xgl34222220.hetu.ui.HetuStaggerState
import io.github.xgl34222220.hetu.ui.ht

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
    stagger: HetuStaggerState,
    onView: (PanelViewState) -> Unit,
    onOpenSubscriptions: () -> Unit,
    onRankCountMenu: () -> Unit,
    rankMenu: @Composable () -> Unit,
) {
    val card = Modifier.panelGutter().padding(bottom = PanelDims.gap).fillMaxWidth()
    item(key = PanelOverviewItems.Summary) {
        val c = LocalHomeColors.current
        HomeCard(card.homeEnter(stagger, 0)) {
            Column(Modifier.padding(start = HomeDims.cardPadding, end = HomeDims.cardPadding, top = 16.dp, bottom = 18.dp)) {
                Text(ht("运行概况"), Modifier.padding(bottom = 14.dp), color = c.t1, style = HomeType.rowTitle)
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
        HomeCard(card.homeEnter(stagger, 1), onClick = onOpenSubscriptions, clickLabel = "查看订阅") {
            Column(Modifier.padding(start = HomeDims.cardPadding, end = HomeDims.cardPadding, top = 16.dp, bottom = 16.dp)) {
                Row(Modifier.fillMaxWidth().padding(bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(ht("订阅"), Modifier.weight(1f), color = c.t1, style = HomeType.rowTitle)
                    if (sub.expire != null) Text(ht("到期 %s").fill(sub.expire), color = c.t2, style = HomeType.rowSub, maxLines = 1)
                }
                Row(Modifier.fillMaxWidth()) {
                    val (used, usedUnit) = split(HomeFormat.bytes(sub.usedBytes))
                    val (left, leftUnit) = split(HomeFormat.bytes(sub.remainingBytes))
                    val (total, totalUnit) = split(HomeFormat.bytes(sub.totalBytes))
                    PanelStat(used, usedUnit, "已用")
                    PanelStat(left, leftUnit, "剩余")
                    PanelStat(total, totalUnit, "总量")
                }
                HomeProgressBar(sub.usedFraction, Modifier.padding(top = 16.dp, bottom = 12.dp))
                Row(Modifier.fillMaxWidth()) {
                    Text(ht("%d 个订阅").fill(sub.subscriptionCount), Modifier.weight(1f), color = c.t1, style = HomeType.rowSub, maxLines = 1)
                    Text(ht("%d 个节点").fill(sub.nodeCount), color = c.t2, style = HomeType.rowSub, maxLines = 1)
                }
            }
        }
    }
    item(key = PanelOverviewItems.Speed) {
        val c = LocalHomeColors.current
        Row(card.homeEnter(stagger, 2), horizontalArrangement = Arrangement.spacedBy(PanelDims.gap)) {
            SpeedCard("上行速度", HomeFormat.speed(overview.uploadBytesPerSecond), PanelIcons.ArrowUp, c.goodText, c.goodSoft, Modifier.weight(1f))
            SpeedCard("下行速度", HomeFormat.speed(overview.downloadBytesPerSecond), PanelIcons.ArrowDown, c.accent, c.accentSoft, Modifier.weight(1f))
        }
    }
    item(key = PanelOverviewItems.Total) {
        val c = LocalHomeColors.current
        HomeCard(card.homeEnter(stagger, 3)) {
            Row(Modifier.fillMaxWidth().heightIn(min = HomeDims.rowMinHeightSmall).padding(horizontal = HomeDims.cardPadding), verticalAlignment = Alignment.CenterVertically) {
                Text(ht("总流量"), Modifier.weight(1f), color = c.t1, style = HomeType.rowTitle, maxLines = 1)
                Text(ht("上行") + " " + HomeFormat.bytes(overview.uploadTotalBytes), color = c.goodText, style = HomeType.value, maxLines = 1)
                Text("/", Modifier.padding(horizontal = 9.dp), color = c.t2, style = HomeType.value)
                Text(ht("下行") + " " + HomeFormat.bytes(overview.downloadTotalBytes), color = c.accent, style = HomeType.value, maxLines = 1)
            }
        }
    }
    item(key = PanelOverviewItems.Trend) {
        val c = LocalHomeColors.current
        val reveal = rememberHomeReveal(PanelOverviewItems.Trend, 800)
        HomeCard(card.homeEnter(stagger, 4)) {
            Column(Modifier.padding(start = HomeDims.cardPadding, end = HomeDims.cardPadding, top = 14.dp, bottom = 16.dp)) {
                Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), verticalAlignment = Alignment.Top) {
                    Column(Modifier.weight(1f)) {
                        Text(ht("近期趋势"), color = c.t1, style = HomeType.rowTitle)
                        Text(ht("最近 60 秒"), color = c.t2, style = HomeType.rowSub)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PanelChip("上行", { onView(view.copy(showUploadTrend = !view.showUploadTrend)) }, selected = view.showUploadTrend,
                            selectedColor = c.goodText, selectedFill = c.goodSoft, height = 32.dp, leading = { HomeStatusDot(if (view.showUploadTrend) c.good else c.t3, size = 7.dp) })
                        PanelChip("下行", { onView(view.copy(showDownloadTrend = !view.showDownloadTrend)) }, selected = view.showDownloadTrend,
                            height = 32.dp, leading = { HomeStatusDot(if (view.showDownloadTrend) c.accent else c.t3, size = 7.dp) })
                    }
                }
                PanelTrendChart(
                    buildList {
                        if (view.showDownloadTrend) add(PanelTrendSeries(overview.downloadTrend, c.accent, fill = true))
                        if (view.showUploadTrend) add(PanelTrendSeries(overview.uploadTrend, c.good, fill = false))
                    },
                    reveal = reveal,
                )
            }
        }
    }
    item(key = PanelOverviewItems.Rank) {
        val c = LocalHomeColors.current
        val ranks = PanelLogic.ranks(overview, view.rankMode, view.rankCount)
        HomeCard(card.homeEnter(stagger, 5)) {
            Row(Modifier.fillMaxWidth().heightIn(min = HomeDims.rowMinHeight).padding(start = HomeDims.cardPadding, end = 6.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(ht("实时排行"), color = c.t1, style = HomeType.rowTitle)
                    Text(ht(view.rankMode.label), color = c.t2, style = HomeType.rowSub)
                }
                Box {
                    HomeIconButton(PanelIcons.CircleEllipsis, "显示数量", onRankCountMenu, glyph = 26.dp)
                    rankMenu()
                }
            }
            if (ranks.isEmpty()) {
                Text(ht("暂无应用流量"), Modifier.padding(start = HomeDims.cardPadding, end = HomeDims.cardPadding, top = 4.dp, bottom = 18.dp), color = c.t2, style = HomeType.note)
            } else Spacer(Modifier.size(4.dp))
            ranks.forEachIndexed { index, rank ->
                if (index > 0) HomeDivider(inset = HomeDims.cardPadding)
                Row(
                    Modifier.fillMaxWidth().heightIn(min = HomeDims.rowMinHeight).padding(horizontal = HomeDims.cardPadding, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    PanelAvatar(rank.app, rank.packageName, size = 38.dp)
                    Column(Modifier.weight(1f)) {
                        Text(rank.app, color = c.t1, style = HomeType.rowTitle.copy(fontSize = HomeType.value.fontSize), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            ht("下行") + " " + HomeFormat.speed(rank.downloadBytesPerSecond) + " · " + ht("上行") + " " + HomeFormat.speed(rank.uploadBytesPerSecond),
                            color = c.t2, style = HomeType.rowSub, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Text(
                        if (view.rankMode == PanelRankMode.Connections) ht("%d 条连接").fill(rank.connections) else HomeFormat.bytes(rank.totalBytes),
                        color = c.t2, style = HomeType.rowSub, maxLines = 1,
                    )
                }
            }
            if (ranks.isNotEmpty()) Spacer(Modifier.size(6.dp))
        }
    }
}

/** “20 GB” → ("20", "GB"). */
private fun split(text: String): Pair<String, String?> {
    val at = text.lastIndexOf(' ')
    return if (at <= 0) text to null else text.substring(0, at) to text.substring(at + 1)
}

@Composable
private fun SpeedCard(label: String, value: String, icon: ImageVector, tint: Color, soft: Color, modifier: Modifier) {
    val c = LocalHomeColors.current
    HomeCard(modifier) {
        Row(Modifier.padding(start = 14.dp, end = 10.dp, top = 14.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(38.dp).background(soft, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                Icon(icon, null, Modifier.size(22.dp), tint = tint)
            }
            Spacer(Modifier.width(12.dp))
            Column {
                Text(ht(label), color = c.t2, style = HomeType.note, maxLines = 1)
                HomeRollingText(value, tint, HomeType.metric, alignment = Alignment.CenterStart)
            }
        }
    }
}
