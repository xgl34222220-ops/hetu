package io.github.xgl34222220.hetu

import androidx.compose.material3.minimumInteractiveComponentSize
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
import io.github.xgl34222220.hetu.ui.ht
import io.github.xgl34222220.hetu.home.fill

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
            // Drawn 40 dp tall; the slot around it is the 48 dp touch target.
            .minimumInteractiveComponentSize()
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
    /** Selected history window (ms) and what the daemon counted in it (controller totals). */
    var range by mutableStateOf(86_400_000L)
    var totals by mutableStateOf(0L to 0L)
    var loading by mutableStateOf(false)
    var revision by mutableStateOf(0)
    /** 历史采集 switch, re-read on every history read so the page follows a change made elsewhere. */
    var historyEnabled by mutableStateOf(false)
    /** Why the history read failed; null after a successful read. */
    var error by mutableStateOf<String?>(null)
    /** Rows a ranking shows: the top 20, or 100 after 「显示更多」. */
    var limit by mutableStateOf(RankLimit)
    /** Controller totals per slot over the window: (slot start ms, upload, download). */
    var distribution by mutableStateOf<List<Triple<Long, Long, Long>>>(emptyList())
}

internal const val RankLimit = 20
internal const val RankLimitMore = 100

/** Slot of the time-distribution chart for each history window: 5 minutes, 1 hour, 6 hours, 1 day. */
internal fun historyBucketSeconds(range: Long): Long = when {
    range <= 3_600_000L -> 300L
    range <= 86_400_000L -> 3_600L
    range <= 7 * 86_400_000L -> 21_600L
    else -> 86_400L
}
internal fun historyBucketLabel(range: Long): String = when (historyBucketSeconds(range)) {
    300L -> "每 5 分钟"
    3_600L -> "每小时"
    21_600L -> "每 6 小时"
    else -> "每天"
}

/** History windows offered on the ranking page; the retention setting bounds what exists. */
internal val historyRanges = listOf(3_600_000L to "1 小时", 86_400_000L to "24 小时", 7 * 86_400_000L to "7 天", 30 * 86_400_000L to "30 天")
internal fun historyRangeLabel(range: Long) = historyRanges.firstOrNull { it.first == range }?.second ?: "24 小时"
/** History dimensions: the five the Root daemon aggregates, plus the App-recorded proxy chain. */
internal val historyDimensions = listOf("app" to "应用", "host" to "域名", "ip" to "目标 IP", "outbound" to "出站", "rule" to "规则", "route" to "代理链")

/** Daemon keys are package names, process names or "uid:N"; show the app label when one resolves. */
private fun historyLabel(context: android.content.Context, key: String, cache: MutableMap<String, String>): String = cache.getOrPut(key) {
    val pm = context.packageManager
    try {
        val pkg = when {
            key.startsWith("uid:") -> key.removePrefix("uid:").toIntOrNull()?.let { pm.getPackagesForUid(it)?.firstOrNull() }
            key.contains('.') && !key.contains(' ') -> key
            else -> null
        } ?: return@getOrPut key
        val label = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        if (label.isBlank() || label == pkg) pkg else "$label · $pkg"
    } catch (_: Exception) { key }
}

@Composable
internal fun rememberRankState(vm: HetuViewModel): RankState {
    val context = LocalContext.current
    val state = remember {
        RankState(
            vm.prefs.getString("overviewRankDimension", "host").orEmpty().ifBlank { "host" },
            vm.prefs.getString("overviewRankSort", "count").orEmpty().ifBlank { "count" },
        ).apply {
            range = vm.prefs.getLong("historyRankRange", 86_400_000L).takeIf { r -> historyRanges.any { it.first == r } } ?: 86_400_000L
            historyEnabled = vm.prefs.getBoolean("proxyApiHistoryEnabled", false)
        }
    }
    val labels = remember { HashMap<String, String>() }
    LaunchedEffect(state.dimension, state.sort) {
        vm.prefs.edit().putString("overviewRankDimension", state.dimension).putString("overviewRankSort", state.sort).apply()
    }
    LaunchedEffect(state.range) { vm.prefs.edit().putLong("historyRankRange", state.range).apply() }
    LaunchedEffect(state.historical, state.dimension, state.sort, state.range, state.revision, state.limit) {
        state.historyEnabled = vm.prefs.getBoolean("proxyApiHistoryEnabled", false)
        if (state.historical && state.historyEnabled) {
            state.loading = true
            try {
                // Closed minutes the Root daemon counted (also while the App was killed) first.
                try { DaemonMonitorBridge.pullTraffic(context, force = state.revision > 0) } catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel } catch (_: Exception) { }
                val since = System.currentTimeMillis() - state.range
                var rows = if (state.dimension in DaemonMonitorPolicy.DIMENSIONS)
                    ProxyApiHistoryStore.dimensionRanking(context, state.dimension, state.sort, since, state.limit) else emptyList()
                // Without daemon data (older session, core without Clash API) the App-recorded history remains.
                if (rows.isEmpty() && state.dimension in setOf("host", "app", "route"))
                    rows = ProxyApiHistoryStore.ranking(context, state.dimension, state.sort, since, state.limit)
                if (state.dimension == "app") rows = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    // A package seen both by name and by uid becomes one row (list keys stay unique).
                    rows.map { it.copy(name = historyLabel(context, it.name, labels)) }.groupBy { it.name }
                        .map { (name, same) -> TrafficRank(name, same.sumOf { it.connections }, same.sumOf { it.upload }, same.sumOf { it.download }) }
                        .sortedByDescending { if (state.sort == "traffic") it.upload + it.download else it.connections.toLong() }
                }
                state.history = rows
                state.totals = ProxyApiHistoryStore.rangeTotals(context, since)
                state.distribution = ProxyApiHistoryStore.timeDistribution(context, since, historyBucketSeconds(state.range))
                state.error = null
            } catch (cancel: kotlinx.coroutines.CancellationException) {
                throw cancel
            } catch (failure: Exception) {
                // A failed read is not the same as nothing collected: say so and offer a retry.
                state.history = emptyList()
                state.distribution = emptyList()
                state.error = failure.message?.takeIf { it.isNotBlank() } ?: failure.javaClass.simpleName
            } finally { state.loading = false }
        }
    }
    return state
}

internal fun LazyListScope.rankItems(vm: HetuViewModel, rank: RankState) {
    val historyEnabled = rank.historyEnabled
    item(key = "rank-controls") {
        Column(Modifier.padding(horizontal = Hx.gutter).padding(bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (rank.historical && historyEnabled) {
                // History: the five dimensions the Root daemon aggregates (plus the proxy chain) over a chosen window.
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    historyDimensions.forEach { (key, label) -> HxToggleChip(label, rank.dimension == key) { if (it) rank.dimension = key } }
                }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    historyRanges.forEach { (range, label) -> HxToggleChip(label, rank.range == range) { if (it) rank.range = range } }
                }
            } else {
                HxSegmented(
                    options = listOf("host" to "域名 / IP", "app" to "应用", "route" to "代理链"),
                    selected = if (rank.dimension in setOf("host", "app", "route")) rank.dimension else "host",
                    onSelect = { rank.dimension = it },
                )
            }
            // Two explicit choices instead of chips whose label flips with the state.
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HxSegmented(
                    options = listOf("count" to "按连接数", "traffic" to "按流量"),
                    selected = if (rank.sort == "traffic") "traffic" else "count",
                    onSelect = { rank.sort = it },
                    modifier = Modifier.weight(1f),
                )
                if (historyEnabled) HxSegmented(
                    options = listOf("live" to "实时", "history" to "历史"),
                    selected = if (rank.historical) "history" else "live",
                    onSelect = { rank.historical = it == "history" },
                    modifier = Modifier.weight(1f),
                )
            }
            if (!historyEnabled) {
                HxBanner(
                    "历史采集未开启：开启后由 Root 守护记录流量与连接，可查看 1 小时到 30 天的排行与时间分布",
                    tone = HxTone.Neutral, actionLabel = "开启历史采集",
                    onAction = {
                        // Same preference the 测速与 API sheet saves.
                        vm.prefs.edit().putBoolean("proxyApiHistoryEnabled", true).apply()
                        rank.historyEnabled = true
                        rank.historical = true
                        rank.revision++
                    },
                )
            } else if (rank.historical) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "前 ${rank.limit} · " + historyRangeLabel(rank.range),
                        style = MaterialTheme.typography.labelMedium, color = Hx.colors.textMuted, modifier = Modifier.weight(1f),
                    )
                    HxToggleChip(if (rank.loading) "读取中…" else "刷新", false) { rank.revision++ }
                }
            }
            if (rank.historical && historyEnabled && (rank.totals.first > 0 || rank.totals.second > 0)) {
                Text(
                    historyRangeLabel(rank.range) + "合计 ↓ ${HxFormat.bytes(rank.totals.second)}  ↑ ${HxFormat.bytes(rank.totals.first)} · Root 守护统计（含已关闭连接）",
                    style = MaterialTheme.typography.labelSmall,
                    color = Hx.colors.textMuted,
                )
            }
        }
    }
    val historyShown = rank.historical && historyEnabled
    val rows = if (historyShown) rank.history
        else rankConnections(vm.state.connections, if (rank.dimension in setOf("host", "app", "route")) rank.dimension else "host", rank.sort)
    val failure = rank.error
    if (historyShown && failure != null) {
        item(key = "rank-error") {
            HxBanner(
                "历史读取失败：$failure", tone = HxTone.Bad, actionLabel = "重试", onAction = { rank.revision++ },
                modifier = Modifier.padding(horizontal = Hx.gutter).padding(bottom = 10.dp),
            )
        }
    } else if (historyShown && rows.isEmpty() && !rank.loading && !vm.state.running) {
        item(key = "rank-daemon") {
            HxBanner(
                "代理未运行：Root 守护只在代理运行时采集，这段时间没有新数据", tone = HxTone.Warn,
                modifier = Modifier.padding(horizontal = Hx.gutter).padding(bottom = 10.dp),
            )
        }
    }
    if (historyShown && failure == null && rank.distribution.any { it.second > 0L || it.third > 0L }) {
        item(key = "rank-distribution") { HxTrafficDistribution(rank) }
    }
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
    if (historyShown && failure == null && rank.limit < RankLimitMore && rows.size >= rank.limit) {
        item(key = "rank-more") {
            HxButton(
                "显示更多（前 $RankLimitMore）", { rank.limit = RankLimitMore },
                Modifier.fillMaxWidth().padding(horizontal = Hx.gutter).padding(top = 4.dp, bottom = 10.dp),
                filled = false, busy = rank.loading,
            )
        }
    }
}

/** History mode: how the counted traffic spread over the window, on the existing traffic chart. */
@Composable
private fun HxTrafficDistribution(rank: RankState) {
    val c = Hx.colors
    val slots = rank.distribution
    val format = remember(rank.range) {
        java.text.SimpleDateFormat(if (rank.range > 86_400_000L) "M/d HH:mm" else "HH:mm", java.util.Locale.getDefault())
    }
    HxGroup(Modifier.padding(horizontal = Hx.gutter).padding(bottom = 10.dp), title = ht("流量分布") + " · " + ht(historyBucketLabel(rank.range))) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(ht("↓ 下行"), style = MaterialTheme.typography.labelSmall, color = c.accent)
                Spacer(Modifier.width(10.dp))
                Text(ht("↑ 上行"), style = MaterialTheme.typography.labelSmall, color = c.warn)
                Spacer(Modifier.weight(1f))
                Text(
                    ht("峰值 %s").fill(HxFormat.bytes(slots.maxOf { it.second + it.third })),
                    style = MaterialTheme.typography.labelSmall.merge(HxNumberStyle), color = c.textMuted,
                )
            }
            Spacer(Modifier.height(8.dp))
            HxTrafficChart(slots.map { it.third }, slots.map { it.second }, c.accent, c.warn, Modifier.fillMaxWidth().height(120.dp))
            Row(Modifier.padding(top = 6.dp)) {
                Text(format.format(java.util.Date(slots.first().first)), style = MaterialTheme.typography.labelSmall.merge(HxNumberStyle), color = c.textFaint)
                Spacer(Modifier.weight(1f))
                Text("现在", style = MaterialTheme.typography.labelSmall, color = c.textFaint)
            }
        }
    }
}
