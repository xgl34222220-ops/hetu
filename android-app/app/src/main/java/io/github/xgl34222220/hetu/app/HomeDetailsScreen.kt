package io.github.xgl34222220.hetu

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

/** Missing telemetry remains a gap, never a fabricated zero or interpolated line. */
internal data class HomeResourceSample(val cpuPercent: Float?, val memoryBytes: Long?)

@Composable
internal fun HomePublicIpScreen(vm: HetuViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    var tab by rememberSaveable { mutableStateOf("wan") }
    val rt = vm.runtime
    fun present(value: String) = value.takeUnless { it.isBlank() || it == "—" } ?: "未知"
    val rows = if (tab == "lan") listOf("IP 地址" to present(rt.lanAddress), "网络接口" to present(rt.lanInterface)) else listOf(
        "IP 地址" to present(rt.wanAddress),
        "地理位置" to listOf(rt.wanCountry, rt.wanRegion).filter { it.isNotBlank() && it != "—" }.distinct().joinToString(" · ").ifBlank { "未知" },
        "网络运营商" to present(rt.wanIsp), "ASN" to present(rt.wanAsn), "城市" to present(rt.wanCity),
        "组织" to present(rt.wanOrganization), "IP 类型" to present(rt.wanIpType), "时区" to present(rt.wanTimezone), "经纬度" to present(rt.wanCoordinates),
    )
    HxPage(title = "公网 IP 详情", onBack = onBack, largeTitle = false, compactTitleFontSizeSp = 20f,
        actions = { HxBarAction(Icons.Rounded.Sync, "刷新 IP 信息", vm::pullRefresh, busy = vm.refreshing) }) {
        item { HxSection { HxSegmented(listOf("wan" to "公网 IP", "lan" to "局域网 IP"), tab, { tab = it }, selectionColor = Hx.colors.accent, selectedTextColor = Hx.colors.onAccent) } }
        item {
            HxSection {
                HxGroup {
                    rows.forEachIndexed { index, (label, value) ->
                        if (index > 0) HxDivider(16.dp)
                        Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(start = 16.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(label, color = Hx.colors.textMuted, fontSize = 14.sp, modifier = Modifier.width(105.dp))
                            Text(value, color = Hx.colors.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                            if (tab == "lan" || index < 5) HxBarAction(Icons.Rounded.ContentCopy, "复制$label", { hxCopy(context, label, value) })
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun HomeResourcesScreen(vm: HetuViewModel, onBack: () -> Unit) {
    val samples = vm.resourceSamples.toList()
    val latest = samples.lastOrNull()
    val running = vm.state.running
    val rt = vm.runtime
    val rows = listOf(
        "运行时长" to if (running && rt.elapsedSeconds > 0) HxFormat.duration(rt.elapsedSeconds) else "—",
        "进程 PID" to if (running && rt.pid > 0) rt.pid.toString() else "—",
        "核心版本" to vm.coreVersion.ifBlank { vm.state.core.ifBlank { "—" } },
        "CPU 核心分配" to rt.cpuAffinity.ifBlank { "—" },
        "当前 CPU" to if (running && rt.currentCpu >= 0) "CPU ${rt.currentCpu}" else "—",
        "模式" to "${vm.state.mode} · ${vm.state.trafficMode.ifBlank { "rule" }}",
        "启动配置" to vm.state.config,
        "活动连接" to if (running && vm.state.panelReady) vm.state.connections.size.toString() else "—",
    )
    HxPage(title = "资源占用", onBack = onBack, largeTitle = false, compactTitleFontSizeSp = 20f) {
        item { ResourceChartCard("CPU", latest?.cpuPercent?.let { String.format(Locale.US, "%.1f%%", it) } ?: "—", samples.map { it.cpuPercent }, Hx.colors.accent) }
        item { ResourceChartCard("内存", latest?.memoryBytes?.let(HxFormat::bytes) ?: "—", samples.map { it.memoryBytes?.toFloat() }, Hx.colors.good) }
        item {
            HxSection {
                HxGroup {
                    rows.forEach { (label, value) ->
                        Row(Modifier.fillMaxWidth().heightIn(min = 42.dp).padding(horizontal = 16.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(label, color = Hx.colors.textMuted, fontSize = 16.sp, modifier = Modifier.weight(1f))
                            Text(value, color = Hx.colors.text, fontSize = 16.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.End)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ResourceChartCard(label: String, value: String, samples: List<Float?>, color: Color) {
    val muted = Hx.colors.textMuted
    HxSection {
        HxCard(padding = PaddingValues(16.dp)) {
            Row(Modifier.fillMaxWidth()) {
                Text(label, color = Hx.colors.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text("本次查看", color = muted, fontSize = 11.sp)
            }
            Text(value, color = if (value == "—") Hx.colors.text else Hx.colors.accent, fontSize = 32.sp, fontWeight = FontWeight.SemiBold)
            Box(Modifier.fillMaxWidth().height(76.dp), contentAlignment = Alignment.Center) {
                if (samples.none { it != null }) Text("等待连接进行采样", color = muted, fontSize = 12.sp)
                else Canvas(Modifier.fillMaxSize().padding(vertical = 10.dp)) {
                    val max = samples.filterNotNull().maxOrNull()?.coerceAtLeast(1f) ?: 1f
                    val denominator = (samples.size - 1).coerceAtLeast(1)
                    val segment = mutableListOf<Offset>()
                    fun drawSegment() {
                        if (segment.isEmpty()) return
                        val fill = Path().apply {
                            moveTo(segment.first().x, size.height)
                            segment.forEach { lineTo(it.x, it.y) }
                            lineTo(segment.last().x, size.height)
                            close()
                        }
                        drawPath(fill, Brush.verticalGradient(listOf(color.copy(alpha = .20f), Color.Transparent)))
                        segment.zipWithNext().forEach { (a, b) -> drawLine(color, a, b, 1.5.dp.toPx(), StrokeCap.Round) }
                        if (segment.size == 1) drawCircle(color, 1.5.dp.toPx(), segment.first())
                        segment.clear()
                    }
                    samples.forEachIndexed { index, value ->
                        if (value == null) drawSegment()
                        else segment.add(Offset(size.width * index / denominator, size.height - (value / max).coerceIn(0f, 1f) * size.height * .85f))
                    }
                    drawSegment()
                }
            }
            if (samples.any { it == null } && samples.any { it != null }) Text("断开处为缺测", color = muted, fontSize = 10.sp, modifier = Modifier.align(Alignment.CenterHorizontally))
        }
    }
}
