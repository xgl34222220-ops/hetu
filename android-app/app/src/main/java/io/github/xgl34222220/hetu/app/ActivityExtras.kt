package io.github.xgl34222220.hetu

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Article
import androidx.compose.material.icons.rounded.QueryStats
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.xgl34222220.hetu.ui.HetuHaptic
import io.github.xgl34222220.hetu.ui.rememberHetuHaptics

/* ------------------------------------------------------------------ */
/*  Small toggle chip                                                   */
/* ------------------------------------------------------------------ */

@Composable
internal fun HxToggleChip(label: String, selected: Boolean, onChange: (Boolean) -> Unit) {
    val c = Hx.colors
    val haptics = rememberHetuHaptics()
    val bg by animateColorAsState(if (selected) c.accentSoft else c.surfaceMuted, tween(HxMotion.Short), label = "chipBg")
    val fg by animateColorAsState(if (selected) c.accent else c.textMuted, tween(HxMotion.Short), label = "chipFg")
    val source = remember { MutableInteractionSource() }
    Box(
        Modifier
            .hxPressScale(source, .94f)
            .heightIn(min = 40.dp)
            .clip(Hx.pillShape)
            .background(bg)
            .clickable(interactionSource = source, indication = LocalIndication.current) { haptics.perform(HetuHaptic.Tick); onChange(!selected) }
            .animateContentSize(tween(HxMotion.Short, easing = HxMotion.Emphasized))
            .padding(horizontal = 14.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = fg, maxLines = 1)
    }
}

/* ------------------------------------------------------------------ */
/*  Logs                                                                */
/* ------------------------------------------------------------------ */

@Stable
internal class LogFilterState(minLevel: String, newestFirst: Boolean) {
    var minLevel by mutableStateOf(minLevel)
    var newestFirst by mutableStateOf(newestFirst)
    var query by mutableStateOf("")
}

@Composable
internal fun rememberLogFilterState(): LogFilterState {
    val minLevel = rememberSaveable { mutableStateOf("Debug") }
    val newest = rememberSaveable { mutableStateOf(true) }
    val state = remember { LogFilterState(minLevel.value, newest.value) }
    LaunchedEffect(state.minLevel, state.newestFirst) {
        minLevel.value = state.minLevel
        newest.value = state.newestFirst
    }
    return state
}

@Composable
private fun levelColor(level: RefLogLevel): Color = when (level) {
    RefLogLevel.Debug -> Hx.colors.textFaint
    RefLogLevel.Info -> Hx.colors.accent
    RefLogLevel.Warn -> Hx.colors.warn
    RefLogLevel.Error -> Hx.colors.bad
}

internal fun LazyListScope.logItems(vm: HetuViewModel, filter: LogFilterState, searching: Boolean) {
    if (searching) item(key = "log-controls") {
        var orderMenu by remember { mutableStateOf(false) }
        Column(Modifier.padding(horizontal = Hx.gutter).padding(bottom = 10.dp)) {
            HxSearchField(filter.query, { filter.query = it }, "搜索日志内容", autoFocus = true)
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                listOf("Debug" to "全部", "Info" to "INFO", "Warn" to "WARN", "Error" to "ERROR").forEach { (id,label) ->
                    HxToggleChip(label, filter.minLevel == id) { filter.minLevel = id }
                }
                Box(Modifier.hxAnchorSource()) {
                    HxToggleChip(if (filter.newestFirst) "最新在前 ⌄" else "最早在前 ⌄", false) { orderMenu = true }
                }
            }
            if (orderMenu) HxChoiceSheet("日志顺序", listOf(HxChoice("new", "最新在前"), HxChoice("old", "最早在前")),
                if (filter.newestFirst) "new" else "old", { filter.newestFirst = it == "new"; orderMenu = false }, { orderMenu = false }, dimBehind = true)
        }
    }
    val min = runCatching { RefLogLevel.valueOf(filter.minLevel) }.getOrDefault(RefLogLevel.Debug)
    val q = filter.query.trim()
    val list = vm.logEntries.filter { it.level.rank >= min.rank && (q.isBlank() || it.message.contains(q, true)) }
        .let { if (filter.newestFirst) it.asReversed() else it }
    if (list.isEmpty()) {
        item(key = "log-empty") {
            if (vm.logsLoading && vm.logEntries.isEmpty()) {
                Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) { HxSpinner(26.dp) }
            } else HxEmpty(Icons.Rounded.Article, if (vm.logEntries.isEmpty()) "暂无日志" else "没有匹配的日志")
        }
    }
    items(list, key = { "log:" + it.index + ":" + it.message.hashCode() }) { entry ->
        val color = levelColor(entry.level)
        val context = LocalContext.current
        var expanded by rememberSaveable(entry.index, entry.message.hashCode()) { mutableStateOf(false) }
        val shape = RoundedCornerShape(22.dp)
        Column(
            Modifier
                .animateItem(fadeInSpec = tween(HxMotion.Medium), placementSpec = HxMotion.glide(), fadeOutSpec = null)
                .fillMaxWidth()
                .padding(horizontal = Hx.gutter)
                .padding(bottom = 9.dp)
                .hxSoftShadow(shape, 2.dp)
                .clip(shape)
                .background(Hx.colors.surface)
                // Tap expands long lines; long-press copies the whole entry.
                .hxCombinedClick(
                    onLongClick = { hxCopy(context, "日志", listOf(entry.time, entry.level.label, entry.message).filter { it.isNotBlank() }.joinToString(" ")) },
                    onClick = { expanded = !expanded },
                )
                .animateContentSize(tween(HxMotion.Medium, easing = HxMotion.Emphasized))
                .padding(horizontal = 15.dp, vertical = 12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    entry.level.label.uppercase(),
                    fontFamily = FontFamily.Default,
                    fontSize = 11.sp,
                    lineHeight = 14.sp,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                    color = color,
                    modifier = Modifier
                        .clip(RoundedCornerShape(9.dp))
                        .background(color.copy(alpha = if (Hx.colors.dark) .16f else .10f))
                        .padding(horizontal = 9.dp, vertical = 4.dp),
                )
                Spacer(Modifier.weight(1f))
                if (entry.time.isNotBlank()) {
                    Text(
                        entry.time,
                        fontFamily = FontFamily.Default,
                        fontSize = 13.sp,
                        lineHeight = 14.sp,
                        color = Hx.colors.textMuted,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                entry.message,
                fontFamily = FontFamily.Default,
                fontSize = 13.sp,
                lineHeight = 18.sp,
                color = Hx.colors.text,
                maxLines = if (expanded) Int.MAX_VALUE else 3,
                overflow = TextOverflow.Ellipsis,
            )
            if (expanded) androidx.compose.material3.TextButton(onClick = { expanded = false }, modifier = Modifier.align(Alignment.End)) {
                Text("⌃ 收起", fontSize = 11.sp, color = Hx.colors.text)
            }
        }
    }
}

/* ------------------------------------------------------------------ */
/*  Traffic ranking (realtime + optional 24h history)                   */
/* ------------------------------------------------------------------ */

@Stable
internal class RankState(dimension: String, sort: String) {
    var dimension by mutableStateOf(dimension)
    var sort by mutableStateOf(sort)
    var historical by mutableStateOf(false)
    var history by mutableStateOf<List<TrafficRank>>(emptyList())
}

@Composable
internal fun rememberRankState(vm: HetuViewModel): RankState {
    val context = LocalContext.current
    val state = remember {
        RankState(
            vm.prefs.getString("overviewRankDimension", "host").orEmpty().ifBlank { "host" },
            vm.prefs.getString("overviewRankSort", "count").orEmpty().ifBlank { "count" },
        )
    }
    LaunchedEffect(state.dimension, state.sort) {
        vm.prefs.edit().putString("overviewRankDimension", state.dimension).putString("overviewRankSort", state.sort).apply()
    }
    LaunchedEffect(state.historical, state.dimension, state.sort) {
        if (state.historical) {
            state.history = try {
                ProxyApiHistoryStore.ranking(context, state.dimension, state.sort, System.currentTimeMillis() - 86_400_000L)
            } catch (cancel: kotlinx.coroutines.CancellationException) {
                throw cancel
            } catch (_: Exception) {
                emptyList()
            }
        }
    }
    return state
}

internal fun LazyListScope.rankItems(vm: HetuViewModel, rank: RankState) {
    val historyEnabled = vm.prefs.getBoolean("proxyApiHistoryEnabled", false)
    item(key = "rank-controls") {
        Column(Modifier.padding(horizontal = Hx.gutter).padding(bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            HxSegmented(
                options = listOf("host" to "域名 / IP", "app" to "应用", "route" to "代理链"),
                selected = rank.dimension,
                onSelect = { rank.dimension = it },
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HxToggleChip(if (rank.sort == "traffic") "按流量" else "按连接数", rank.sort == "traffic") {
                    rank.sort = if (it) "traffic" else "count"
                }
                if (historyEnabled) {
                    HxToggleChip(if (rank.historical) "24 小时" else "实时", rank.historical) { rank.historical = it }
                } else {
                    Text("在「测速与 API」开启历史采集可查看 24 小时排行", style = MaterialTheme.typography.bodySmall, color = Hx.colors.textMuted)
                }
            }
        }
    }
    val rows = if (rank.historical && historyEnabled) rank.history else rankConnections(vm.state.connections, rank.dimension, rank.sort)
    if (rows.isEmpty()) {
        item(key = "rank-empty") { HxEmpty(Icons.Rounded.QueryStats, if (rank.historical) "暂无已采集的历史" else "暂无实时连接") }
    }
    val max = rows.maxOfOrNull { if (rank.sort == "traffic") it.upload + it.download else it.connections.toLong() }?.coerceAtLeast(1L) ?: 1L
    items(rows.size, key = { "rank:" + rows[it].name }) { index ->
        val row = rows[index]
        val value = if (rank.sort == "traffic") row.upload + row.download else row.connections.toLong()
        Column(
            Modifier
                .animateItem(fadeInSpec = tween(HxMotion.Medium), placementSpec = HxMotion.glide(), fadeOutSpec = null)
                .fillMaxWidth()
                .padding(horizontal = Hx.gutter)
                .padding(bottom = 6.dp)
                .clip(Hx.rowShape)
                .background(Hx.colors.surface)
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(26.dp), contentAlignment = Alignment.CenterStart) {
                    Box(
                        Modifier
                            .size(20.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (index < 3) Hx.colors.accentSoft else Color.Transparent),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "${index + 1}",
                            style = MaterialTheme.typography.labelMedium.merge(HxNumberStyle),
                            color = if (index < 3) Hx.colors.accent else Hx.colors.textFaint,
                        )
                    }
                }
                Text(row.name, style = MaterialTheme.typography.bodyMedium, color = Hx.colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                Text(
                    if (rank.sort == "traffic") HxFormat.bytes(value) else "${row.connections} 个",
                    style = MaterialTheme.typography.labelMedium.merge(HxNumberStyle),
                    color = Hx.colors.textMuted,
                )
            }
            HxProgressBar((value.toDouble() / max.toDouble()).toFloat(), Hx.colors.accent, Modifier.padding(start = 26.dp, top = 6.dp), height = 4.dp)
            Text(
                "↓ ${HxFormat.bytes(row.download)}  ↑ ${HxFormat.bytes(row.upload)} · ${row.connections} 个连接",
                style = MaterialTheme.typography.labelSmall,
                color = Hx.colors.textFaint,
                modifier = Modifier.padding(start = 26.dp, top = 4.dp),
            )
        }
    }
}
