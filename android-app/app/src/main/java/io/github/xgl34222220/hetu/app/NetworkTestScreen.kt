package io.github.xgl34222220.hetu

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.xgl34222220.hetu.home.HomeColors
import io.github.xgl34222220.hetu.home.HomeIcons
import io.github.xgl34222220.hetu.home.HomeRowDims
import io.github.xgl34222220.hetu.home.HomeRowDivider
import io.github.xgl34222220.hetu.home.HomeSpinner
import io.github.xgl34222220.hetu.home.HomeType
import io.github.xgl34222220.hetu.home.LocalHomeColors
import io.github.xgl34222220.hetu.home.LocalHomeMotionEnabled
import io.github.xgl34222220.hetu.home.badText
import io.github.xgl34222220.hetu.home.goodText
import io.github.xgl34222220.hetu.home.warnText
import io.github.xgl34222220.hetu.panel.PanelIcons
import io.github.xgl34222220.hetu.ui.ht
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** What a 连通性 row shows; [result] is null until its requests finish. */
internal data class NetRowState(val testing: Boolean = false, val result: NetSiteResult? = null)

/** Live and final readings of 网速, kept by the page so switching tabs does not lose them. */
internal class SpeedUiState {
    var phase by mutableStateOf(SpeedPhase.Idle)
    var meta by mutableStateOf<SpeedMeta?>(null)
    var metaKnown by mutableStateOf(false)
    var latency by mutableStateOf(-1L)
    var live by mutableStateOf(0.0)
    var down by mutableStateOf<Pair<Double, Long>?>(null)
    var up by mutableStateOf<Pair<Double, Long>?>(null)
    var error by mutableStateOf<String?>(null)
    val downSamples: SnapshotStateList<Float> = mutableStateListOf()
    val upSamples: SnapshotStateList<Float> = mutableStateListOf()

    val running: Boolean get() = phase != SpeedPhase.Idle && phase != SpeedPhase.Done && phase != SpeedPhase.Failed

    fun reset() {
        phase = SpeedPhase.Meta; meta = null; metaKnown = false; latency = -1L; live = 0.0
        down = null; up = null; error = null; downSamples.clear(); upSamples.clear()
    }

    fun accept(event: SpeedEvent) {
        when (event) {
            is SpeedEvent.Phase -> { phase = event.phase; if (event.phase == SpeedPhase.Download || event.phase == SpeedPhase.Upload) live = 0.0 }
            is SpeedEvent.Meta -> { meta = event.meta; metaKnown = true }
            is SpeedEvent.Latency -> latency = event.millis
            is SpeedEvent.Live -> {
                live = event.mbps
                val list = if (event.upload) upSamples else downSamples
                list.add(event.mbps.toFloat())
                if (list.size > SAMPLE_CAP) list.removeAt(0)
            }
            is SpeedEvent.Finished -> if (event.upload) up = event.mbps to event.bytes else down = event.mbps to event.bytes
            is SpeedEvent.Failed -> { error = event.reason; phase = SpeedPhase.Failed }
        }
    }

    companion object { const val SAMPLE_CAP = 64 }
}

internal fun formatMbps(value: Double): String = when {
    value <= 0.0 -> "0"
    value < 10 -> String.format(Locale.ROOT, "%.2f", value)
    value < 100 -> String.format(Locale.ROOT, "%.1f", value)
    else -> String.format(Locale.ROOT, "%.0f", value)
}

/**
 * 网络测试: 连通性 (many services at once, each with the exit region it really left from) and
 * 网速 (Cloudflare speed test through the current node). Every request runs on IO; leaving the
 * page, or the app going to the background, cancels whatever is still running.
 */
@Composable
internal fun NetworkTestScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val c = LocalHomeColors.current
    var tab by rememberSaveable { mutableStateOf("conn") }

    /* ---------------- 连通性 ---------------- */
    val rows = remember { NetworkTest.sites.associate { it.id to mutableStateOf(NetRowState()) } }
    var runId by remember { mutableIntStateOf(1) }
    var testing by remember { mutableStateOf(false) }
    var viaProxy by remember { mutableStateOf<Boolean?>(null) }
    var summary by remember { mutableStateOf("") }
    val availableLabel = ht("可用")

    LaunchedEffect(runId) {
        if (runId == 0) return@LaunchedEffect
        testing = true
        summary = ""
        rows.values.forEach { it.value = NetRowState(testing = true) }
        val results = ArrayList<NetSiteResult>()
        try {
            val port = withContext(Dispatchers.IO) { NetworkTest.proxyPort(context) }
            viaProxy = port != null
            NetworkTest.run(port).collect { result ->
                results += result
                rows[result.id]?.value = NetRowState(testing = false, result = result)
            }
            NetworkTest.regions(context, results, port).forEach { result -> rows[result.id]?.value = NetRowState(false, result) }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            rows.values.forEach { if (it.value.testing) it.value = NetRowState() }
        } finally {
            testing = false
            val done = rows.values.mapNotNull { it.value.result }
            if (done.isNotEmpty()) {
                val ok = done.count { it.ok && !it.blocked }
                summary = "$ok/${NetworkTest.sites.size} $availableLabel"
            }
        }
    }

    /* ---------------- 网速 ---------------- */
    val speed = remember { SpeedUiState() }
    var speedRun by remember { mutableIntStateOf(0) }
    var speedActive by remember { mutableStateOf(false) }
    LaunchedEffect(speedRun, speedActive) {
        if (!speedActive) {
            if (speed.running) speed.phase = if (speed.down != null) SpeedPhase.Done else SpeedPhase.Idle
            return@LaunchedEffect
        }
        val generation = speedRun
        speed.reset()
        try {
            val port = withContext(Dispatchers.IO) { NetworkTest.proxyPort(context) }
            SpeedTest.run(port).collect { speed.accept(it) }
        } finally {
            // A late finish of an older run must not stop the one that replaced it.
            if (speedRun == generation) speedActive = false
        }
    }
    // A speed test is data the user pays for: it never keeps running behind another app.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) speedActive = false }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    fun toggleSpeed() {
        if (speedActive) speedActive = false else { speedRun++; speedActive = true }
    }

    // Static catalogue: grouped once, not on every row update.
    val byCategory = remember { NetCategory.entries.map { category -> category to NetworkTest.sites.filter { it.category == category } } }

    HxPage(
        title = ht("网络测试"),
        onBack = onBack,
        actions = {
            if (tab == "conn") HxBarAction(HomeIcons.RefreshCw, ht("重新测试"), onClick = { if (!testing) runId++ }, busy = testing)
            else HxBarAction(HomeIcons.RefreshCw, ht("重新测速"), onClick = { if (!speedActive) toggleSpeed() }, busy = speedActive)
        },
    ) {
        item(key = "tabs") {
            HxSection {
                HxSegmented(listOf("conn" to ht("连通性"), "speed" to ht("网速")), tab, { tab = it }, Modifier.fillMaxWidth())
            }
        }
        if (tab == "conn") {
            item(key = "conn-note") {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = HomeRowDims.start + 14.dp).padding(bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        when (viaProxy) {
                            true -> ht("经由河图代理 · 按当前规则与节点")
                            false -> ht("代理未运行 · 直连测试")
                            null -> ht("准备测试…")
                        },
                        Modifier.weight(1f), color = c.t2, style = HomeType.note, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    if (summary.isNotBlank()) Text(summary, color = c.t2, style = HomeType.noteStrong)
                }
            }
            byCategory.forEach { (category, sites) ->
                item(key = "cat-${category.name}") {
                    HxSection(ht(category.title)) {
                        HxGroup {
                            sites.forEachIndexed { index, site ->
                                if (index > 0) HomeRowDivider(start = HomeRowDims.start + BrandTileSize + 14.dp)
                                NetSiteRow(site, rows.getValue(site.id))
                            }
                        }
                    }
                }
            }
            item(key = "conn-foot") {
                Text(
                    ht("延迟为首包时间（DNS、握手、TLS 与首字节）。地区来自该服务所在 Cloudflare 的出口报告、同一节点上的其他服务，或直连出口；无法实测时不显示。"),
                    Modifier.padding(horizontal = HomeRowDims.start + 14.dp).padding(bottom = 8.dp),
                    color = c.t3, style = HomeType.caption,
                )
            }
        } else {
            item(key = "speed-hero") { SpeedHeroCard(speed, speedActive, ::toggleSpeed) }
            item(key = "speed-exit") { SpeedExitCard(speed) }
            item(key = "speed-result") { SpeedResultCard(speed) }
            item(key = "speed-chart") { SpeedChartCard(speed) }
        }
    }
}

private val BrandTileSize = 36.dp

/** Brand colour that stays readable on this theme (black marks turn to the text colour in dark mode). */
private fun brandColor(site: NetSite, c: HomeColors): Color {
    val color = Color(site.color)
    return if (c.dark && color.luminance() < .08f) c.t1 else color
}

@Composable
private fun BrandTile(site: NetSite) {
    val c = LocalHomeColors.current
    val tint = brandColor(site, c)
    Box(
        Modifier.size(BrandTileSize).clip(RoundedCornerShape(11.dp)).background(tint.copy(alpha = if (c.dark) .16f else .10f)),
        contentAlignment = Alignment.Center,
    ) {
        val mark = remember(site.brand) { site.brand?.let(NetBrandIcons::vector) }
        if (mark != null) Icon(mark, contentDescription = null, Modifier.size(20.dp), tint = tint)
        else Text(site.monogram, color = tint, style = HomeType.cardLabel.copy(fontWeight = FontWeight.Bold, fontSize = if (site.monogram.length > 1) 13.sp else 16.sp))
    }
}

@Composable
private fun NetSiteRow(site: NetSite, holder: MutableState<NetRowState>) {
    val c = LocalHomeColors.current
    val state = holder.value
    val result = state.result
    val region = result?.region
    val status: String
    val statusColor: Color
    when {
        state.testing -> { status = ht("测试中"); statusColor = c.t2 }
        result == null -> { status = "—"; statusColor = c.t3 }
        result.blocked -> { status = ht("未解锁"); statusColor = c.badText }
        !result.ok -> { status = ht(result.error.ifBlank { "失败" }); statusColor = c.badText }
        else -> {
            status = "${result.millis}ms"
            statusColor = when {
                result.millis < 300 -> c.goodText
                result.millis < 1_000 -> c.t1
                else -> c.warnText
            }
        }
    }
    val regionText = if (region == null) "" else (NetworkTest.flag(region) + " " + region).trim()
    Row(
        Modifier.fillMaxWidth().heightIn(min = 62.dp).padding(start = HomeRowDims.start, end = HomeRowDims.end, top = 9.dp, bottom = 9.dp)
            .semantics(mergeDescendants = true) { contentDescription = listOf(site.name, regionText, status).filter { it.isNotBlank() }.joinToString("，") },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BrandTile(site)
        Spacer(Modifier.width(14.dp))
        Text(site.name, Modifier.weight(1f), color = c.t1, style = HomeType.cardLabel, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Column(Modifier.widthIn(min = 64.dp), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.Center) {
            if (regionText.isNotEmpty()) Text(regionText, color = c.t2, style = HomeType.caption.copy(fontWeight = FontWeight.SemiBold), maxLines = 1)
            if (state.testing) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    HomeSpinner(size = 12.dp, color = c.t3, strokeWidth = 1.5.dp)
                    Spacer(Modifier.width(6.dp))
                    Text(status, color = statusColor, style = HomeType.delay, maxLines = 1)
                }
            } else Text(status, color = statusColor, style = HomeType.delay, maxLines = 1)
        }
    }
}

/* ---------------- 网速 cards ---------------- */

@Composable
private fun SpeedHeroCard(speed: SpeedUiState, active: Boolean, onToggle: () -> Unit) {
    val c = LocalHomeColors.current
    val motion = LocalHomeMotionEnabled.current
    val shown = when (speed.phase) {
        SpeedPhase.Download, SpeedPhase.Upload -> speed.live
        else -> speed.down?.first ?: 0.0
    }
    val animated by animateFloatAsState(shown.toFloat(), if (motion) tween(260) else tween(0), label = "speed-readout")
    HxSection {
        HxCard {
            Text(ht("经当前代理节点连接 Cloudflare 测速服务器（speed.cloudflare.com），依次测量延迟、下载与上传，约 20 秒，会消耗流量。"), color = c.t2, style = HomeType.note)
            Spacer(Modifier.height(18.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(formatMbps(animated.toDouble()), color = c.t1, style = HomeType.metricLarge.copy(fontSize = 54.sp, lineHeight = 58.sp))
                Spacer(Modifier.width(8.dp))
                Text("Mbps", Modifier.padding(bottom = 9.dp), color = c.t2, style = HomeType.cardLabel)
            }
            Text(
                when (speed.phase) {
                    SpeedPhase.Idle -> ht("尚未测速")
                    SpeedPhase.Meta -> ht("正在连接测速服务器…")
                    SpeedPhase.Latency -> ht("正在测量延迟…")
                    SpeedPhase.Download -> ht("正在测量下载…")
                    SpeedPhase.Upload -> ht("正在测量上传…")
                    SpeedPhase.Done -> ht("下载速率")
                    SpeedPhase.Failed -> speed.error ?: ht("测速失败")
                },
                color = if (speed.phase == SpeedPhase.Failed) c.badText else c.t3, style = HomeType.caption,
            )
            Spacer(Modifier.height(16.dp))
            HxButton(if (active) ht("停止测速") else ht("开始测速"), onToggle, Modifier.fillMaxWidth(),
                icon = if (active) null else PanelIcons.Gauge, tone = if (active) HxTone.Bad else HxTone.Accent, filled = !active)
        }
    }
}

@Composable
private fun SpeedLine(label: String, value: String, detail: String? = null) {
    val c = LocalHomeColors.current
    Row(
        Modifier.fillMaxWidth().heightIn(min = 54.dp).padding(horizontal = HomeRowDims.start, vertical = 8.dp)
            .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f), color = c.t2, style = HomeType.label)
        Column(horizontalAlignment = Alignment.End) {
            Text(value, color = c.t1, style = HomeType.value, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!detail.isNullOrBlank()) Text(detail, color = c.t3, style = HomeType.caption, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun SpeedExitCard(speed: SpeedUiState) {
    val meta = speed.meta
    HxSection(ht("出口")) {
        HxGroup {
            val region = when {
                meta != null -> listOf((NetworkTest.flag(meta.country) + " " + meta.country).trim(), meta.city, meta.colo.takeIf { it.isNotBlank() }?.let { "$it" })
                    .filterNotNull().filter { it.isNotBlank() }.joinToString(" · ")
                speed.metaKnown -> ht("未知")
                else -> "—"
            }
            SpeedLine(ht("地区"), region)
            HxDivider(0.dp)
            SpeedLine("IP", meta?.ip ?: if (speed.metaKnown) ht("未知") else "—", meta?.org)
        }
    }
}

@Composable
private fun SpeedResultCard(speed: SpeedUiState) {
    HxSection(ht("测速结果")) {
        HxGroup {
            SpeedLine(ht("延迟"), if (speed.latency >= 0) "${speed.latency} ms" else "—")
            HxDivider(0.dp)
            SpeedLine(ht("下载"), speed.down?.let { "${formatMbps(it.first)} Mbps" } ?: "—", speed.down?.let { HxFormat.bytes(it.second) })
            HxDivider(0.dp)
            SpeedLine(ht("上传"), speed.up?.let { "${formatMbps(it.first)} Mbps" } ?: "—", speed.up?.let { HxFormat.bytes(it.second) })
        }
    }
}

@Composable
private fun SpeedChartCard(speed: SpeedUiState) {
    val c = LocalHomeColors.current
    val downColor = c.accent
    val upColor = c.good
    HxSection(ht("实时速率")) {
        HxCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(downColor))
                Spacer(Modifier.width(6.dp))
                Text(ht("下载"), color = c.t2, style = HomeType.caption)
                Spacer(Modifier.width(16.dp))
                Box(Modifier.size(8.dp).clip(CircleShape).background(upColor))
                Spacer(Modifier.width(6.dp))
                Text(ht("上传"), color = c.t2, style = HomeType.caption)
            }
            Spacer(Modifier.height(12.dp))
            // Reads the sample lists only while drawing, so a new sample redraws the chart without recomposing the page.
            Canvas(Modifier.fillMaxWidth().height(150.dp)) {
                val grid = c.line
                for (i in 0..3) {
                    val y = size.height * i / 3f
                    drawLine(grid, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
                }
                val down = speed.downSamples.toList()
                val up = speed.upSamples.toList()
                val peak = maxOf(down.maxOrNull() ?: 0f, up.maxOrNull() ?: 0f, 1f) * 1.15f
                val slots = (SpeedTest.PHASE_MS / SpeedTest.SAMPLE_MS).toInt().coerceAtLeast(2)
                fun series(points: List<Float>, color: Color) {
                    if (points.size < 2) return
                    val path = Path()
                    points.forEachIndexed { index, value ->
                        val x = size.width * index / (slots - 1).toFloat()
                        val y = size.height - size.height * (value / peak)
                        if (index == 0) path.moveTo(x, y) else path.lineTo(x.coerceAtMost(size.width), y)
                    }
                    drawPath(path, color, style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                }
                series(down, downColor)
                series(up, upColor)
            }
        }
    }
}
