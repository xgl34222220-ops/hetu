package io.github.xgl34222220.hetu

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import io.github.xgl34222220.hetu.ui.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Presentation only: three baseline slots and a shared rail, never space-between. */
private data class InstrumentReading(
    val id: String, val title: String, val badge: String,
    val value: String, val supporting: String, val accent: Color,
    val rail: String, val fraction: Float? = null, val monospaced: Boolean = false,
)

internal fun instrumentFraction(value: Long, total: Long): Float? =
    if (total > 0L && value >= 0L) (value.toDouble() / total.toDouble()).toFloat().coerceIn(0f, 1f) else null

internal fun instrumentUploadShare(up: Long, down: Long): Float? {
    if (up < 0L || down < 0L || (up == 0L && down == 0L)) return null
    return (up.toDouble() / (up.toDouble() + down.toDouble())).toFloat().coerceIn(0f, 1f)
}

/** One geometry source for both cell contents and their enclosing row. */
private object InstrumentSlots {
    val header = 22.sp
    val value = 26.sp
    val support = 20.sp
    val gap = 6.dp
    val padding = 12.dp
    val rail = 2.dp
    @Composable fun fontHeight(size: TextUnit): Dp {
        val density = LocalDensity.current
        // Keep room for the full line even when platform text scaling and the
        // sp-to-dp adapter differ. Never compress a 1.5x line into a smaller slot.
        return maxOf(with(density) { size.toDp() }, (size.value * density.fontScale).dp)
    }
    @Composable fun height(): Dp = fontHeight(header) + fontHeight(value) + fontHeight(support) + gap * 3 + padding * 2 + rail
}

/** The four quadrants share this exact layout, even for loading/unknown/long data. */
@Composable
private fun AlignedInstrumentCell(reading: InstrumentReading, modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null, onLongClick: (() -> Unit)? = null) {
    val t = LocalHetuTokens.current
    val headerHeight = InstrumentSlots.fontHeight(InstrumentSlots.header)
    val valueHeight = InstrumentSlots.fontHeight(InstrumentSlots.value)
    val supportingHeight = InstrumentSlots.fontHeight(InstrumentSlots.support)
    @OptIn(ExperimentalFoundationApi::class)
    val action = if (onClick != null) Modifier.combinedClickable(
        role = Role.Button, onClick = onClick, onLongClick = onLongClick,
        onClickLabel = if (reading.id == "network") "切换局域网与网络出口" else "查看订阅",
        onLongClickLabel = if (onLongClick != null) "查看完整网络详情" else null,
    ) else Modifier
    Column(modifier.then(action).testTag("instrument-${reading.id}").padding(InstrumentSlots.padding),
        verticalArrangement = Arrangement.spacedBy(InstrumentSlots.gap)) {
        Layout(modifier = Modifier.fillMaxWidth().height(headerHeight).testTag("instrument-${reading.id}-header"),
            content = {
                Text(reading.title, Modifier.fillMaxWidth().testTag("instrument-${reading.id}-title"), color = t.textSecondary,
                    fontSize = 11.sp, lineHeight = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                Box(Modifier.clip(CircleShape).background(reading.accent.copy(alpha = .085f))
                    .padding(horizontal = 6.dp, vertical = 2.dp).testTag("instrument-${reading.id}-badge")) {
                    Text(reading.badge, color = reading.accent, fontSize = 9.5.sp, lineHeight = 13.sp,
                        fontWeight = FontWeight.SemiBold, maxLines = 1)
                }
                Icon(when (reading.id) {
                    "network" -> Icons.Rounded.Public
                    "speed" -> Icons.Rounded.Speed
                    "usage" -> Icons.Rounded.DataUsage
                    else -> Icons.Rounded.Memory
                }, null, Modifier.size(14.dp).testTag("instrument-${reading.id}-icon"), tint = reading.accent.copy(alpha = .7f))
            }) { measurable, constraints ->
            val badge = measurable[1].measure(constraints.copy(minWidth = 0, minHeight = 0))
            val icon = measurable[2].measure(constraints.copy(minWidth = 0, minHeight = 0))
            val titleStart = icon.width + 5.dp.roundToPx()
            val title = measurable[0].measure(constraints.copy(minWidth = 0, minHeight = 0,
                maxWidth = (constraints.maxWidth - titleStart - badge.width - 4.dp.roundToPx()).coerceAtLeast(0)))
            val baseline = 16.sp.roundToPx()
            layout(constraints.maxWidth, constraints.maxHeight) {
                icon.placeRelative(0, (baseline - 11.sp.roundToPx()).coerceAtLeast(0))
                title.placeRelative(titleStart, (baseline - title[FirstBaseline]).coerceAtLeast(0))
                badge.placeRelative(constraints.maxWidth - badge.width,
                    (baseline - badge[FirstBaseline]).coerceAtLeast(0))
            }
        }
        InstrumentBaselineLine(reading.value, "instrument-${reading.id}-value", valueHeight, 20.sp,
            TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Bold,
                fontFamily = if (reading.monospaced) FontFamily.Monospace else FontFamily.SansSerif,
                fontFeatureSettings = "tnum", color = t.textPrimary), autoSize = true)
        InstrumentBaselineLine(reading.supporting, "instrument-${reading.id}-support", supportingHeight, 15.sp,
            TextStyle(fontSize = 11.sp, lineHeight = 16.sp, fontFamily = FontFamily.SansSerif,
                fontFeatureSettings = "tnum", color = t.textSecondary))
        InstrumentAlignedRail(reading)
    }
}

/** Align actual glyph baselines, not just Text boxes with different font metrics. */
@Composable
private fun InstrumentBaselineLine(text: String, tag: String, height: Dp, baseline: TextUnit,
    style: TextStyle, autoSize: Boolean = false) {
    Layout(modifier = Modifier.fillMaxWidth().height(height).testTag("$tag-slot"), content = {
        BasicText(text, Modifier.fillMaxWidth().testTag(tag), style = style, maxLines = 1, softWrap = false,
            overflow = TextOverflow.Ellipsis,
            autoSize = if (autoSize) TextAutoSize.StepBased(minFontSize = 12.sp, maxFontSize = 16.sp, stepSize = .5.sp) else null)
    }) { measurable, constraints ->
        val child = measurable.single().measure(constraints.copy(minWidth = 0, minHeight = 0))
        val y = (baseline.roundToPx() - child[FirstBaseline]).coerceAtLeast(0)
        layout(constraints.maxWidth, constraints.maxHeight) { child.placeRelative(0, y) }
    }
}

@Composable
private fun InstrumentAlignedRail(reading: InstrumentReading) {
    val t = LocalHetuTokens.current
    val primary = MaterialTheme.colorScheme.primary
    val testId = when (reading.id) {
        "usage" -> "home-usage-progress"
        "resource" -> "home-cpu-progress"
        else -> "instrument-${reading.id}-rail"
    }
    val progress = reading.fraction
    Canvas(Modifier.fillMaxWidth().height(InstrumentSlots.rail).clip(CircleShape).testTag(testId).semantics {
        stateDescription = when (reading.rail) {
            "quota" -> if (progress == null) "订阅额度未上报" else "已用流量占总额度的比例"
            "cpu" -> if (progress == null) "暂无 CPU 采样" else "CPU 占比"
            "share" -> if (progress == null) "暂无流量；不是带宽利用率" else "上行与下行的当前流量占比；不是带宽利用率"
            else -> "网络状态：${reading.supporting}"
        }
        if (progress != null) progressBarRangeInfo = ProgressBarRangeInfo(progress, 0f..1f)
    }) {
        drawRect(t.textMuted.copy(alpha = .12f))
        if (progress != null) {
            when (reading.rail) {
                "share" -> {
                    if (progress > 0f) drawRect(t.success.copy(alpha = .8f), size = size.copy(width = size.width * progress))
                    if (progress < 1f) drawRect(primary.copy(alpha = .75f),
                        topLeft = Offset(size.width * progress, 0f), size = size.copy(width = size.width * (1f - progress)))
                }
                else -> if (progress > 0f) drawRect(Brush.horizontalGradient(listOf(reading.accent, Color(0xFF42C9CE))),
                    size = size.copy(width = size.width * progress))
            }
        } else if (reading.rail == "status") {
            drawCircle(reading.accent.copy(alpha = .8f), radius = size.height / 2f,
                center = Offset(size.height / 2f, size.height / 2f))
        }
    }
}

@Composable
internal fun AlignedInstrumentPanel(runtime: ProxyRuntimeSnapshot, connections: Int, up: Long, down: Long,
    used: Long, total: Long, count: Int, memory: Long, cpu: Float, onSubscription: () -> Unit) {
    val t = LocalHetuTokens.current
    val primary = MaterialTheme.colorScheme.primary
    val view = LocalView.current
    var lan by rememberSaveable { mutableStateOf(false) }
    var details by rememberSaveable { mutableStateOf(false) }
    val ratio = instrumentFraction(used, total)
    val cpuRatio = if (runtime.running && cpu.isFinite() && cpu >= 0f) (cpu / 100f).coerceIn(0f, 1f) else null
    val checked = if (runtime.wanCheckedAt > 0L) DateTimeFormatter.ofPattern("HH:mm")
        .withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(runtime.wanCheckedAt)) else ""
    val networkState = when {
        lan -> "局域网"
        runtime.wanState == "success" -> "已检测"
        runtime.wanState == "stale" -> "上次结果"
        runtime.wanState == "loading" -> "检测中"
        runtime.wanState == "failed" -> "失败"
        else -> "未检测"
    }
    val address = if (lan) runtime.lanAddress else when (runtime.wanState) {
        "success", "stale" -> runtime.wanAddress
        "loading" -> "检测中…"
        "failed" -> "检测失败"
        else -> "未检测"
    }
    val networkSupporting = if (lan) "${runtime.lanInterface} · $connections 连接" else when (runtime.wanState) {
        "success" -> "${countryEmoji(runtime.wanCountryCode)} ${runtime.wanRegion}"
        "stale" -> "上次 $checked · 未重新确认"
        "loading" -> "按当前规则检测出口"
        "failed" -> "长按查看失败详情"
        else -> if (runtime.running) "等待出口检测" else "代理已停止"
    }
    val readings = listOf(
        InstrumentReading("network", if (lan) "局域网络" else "网络出口", "切换", address,
            networkSupporting, if (runtime.wanState == "failed" && !lan) t.danger else primary, "status",
            monospaced = lan || runtime.wanState in listOf("success", "stale")),
        InstrumentReading("speed", "实时速率", if (runtime.running) "实时" else "停止",
            if (runtime.running) "↓ ${refSpeed(down)}" else "—",
            if (runtime.running) "↑ 上行 ${refSpeed(up)}" else "代理已停止", t.success, "share",
            if (runtime.running) instrumentUploadShare(up, down) else null),
        InstrumentReading("usage", "已用流量", ratio?.let { "${((1f - it) * 100f).toInt()}%" } ?: "未上报",
            if (ratio != null) refBytes(used) else "—",
            if (ratio != null) "总量 ${refBytes(total)}" else "$count 个订阅 · 未上报额度", primary, "quota", ratio),
        InstrumentReading("resource", "资源占用", if (runtime.running) "运行中" else "已停止",
            if (runtime.running && memory >= 0L) refBytes(memory) else "—",
            if (runtime.running && cpu.isFinite() && cpu >= 0f) String.format(Locale.US, "CPU %.1f %%", cpu) else "CPU 未采样",
            primary, "cpu", cpuRatio),
    )
    BoxWithConstraints(Modifier.fillMaxWidth().crystalMaterial(RoundedCornerShape(24.dp)).testTag("workspace-bento")) {
        val single = maxWidth < 280.dp || (maxWidth / 2f - 24.dp).value < 116f * LocalDensity.current.fontScale
        val onNetwork = {
            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            lan = !lan
        }
        @Composable fun Cell(reading: InstrumentReading, modifier: Modifier) {
            AlignedInstrumentCell(reading, modifier,
                onClick = when (reading.id) { "network" -> onNetwork; "usage" -> onSubscription; else -> null },
                onLongClick = if (reading.id == "network") ({ details = true }) else null)
        }
        Column(Modifier.fillMaxWidth()) {
            if (single) {
                readings.forEachIndexed { index, reading ->
                    if (index > 0) PanelDivider()
                    Cell(reading, Modifier.fillMaxWidth())
                }
            } else {
                readings.chunked(2).forEachIndexed { index, pair ->
                    if (index > 0) PanelDivider()
                    Row(Modifier.fillMaxWidth().height(InstrumentSlots.height())) {
                        Cell(pair[0], Modifier.weight(1f))
                        Box(Modifier.width(.5.dp).fillMaxHeight().background(t.textMuted.copy(alpha = .14f)))
                        Cell(pair[1], Modifier.weight(1f))
                    }
                }
            }
        }
    }
    if (details) InstrumentNetworkDetailSheet(
        networkState = networkState,
        runtime = runtime,
        connections = connections,
        checked = checked,
        onDismiss = { details = false },
    )

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InstrumentNetworkDetailSheet(
    networkState: String,
    runtime: ProxyRuntimeSnapshot,
    connections: Int,
    checked: String,
    onDismiss: () -> Unit,
) {
    val t = LocalHetuTokens.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp),
        containerColor = t.elevatedCardBackground,
        contentColor = t.textPrimary,
        tonalElevation = 0.dp,
        scrimColor = Color.Black.copy(alpha = .35f),
        dragHandle = {
            Box(
                Modifier.padding(top = 10.dp, bottom = 6.dp).size(width = 36.dp, height = 4.dp)
                    .background(t.textMuted.copy(alpha = .24f), CircleShape),
            )
        },
    ) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 18.dp, end = 18.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("网络详情", color = t.textPrimary, fontSize = 20.sp, lineHeight = 25.sp, fontWeight = FontWeight.Bold)
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))
                    .background(t.controlBackground.copy(alpha = .42f))
                    .padding(14.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                Text("出口状态：$networkState")
                Text("出口地址：${runtime.wanAddress}")
                Text("地区：${runtime.wanRegion}")
                Text("局域地址：${runtime.lanAddress}")
                Text("接口：${runtime.lanInterface} · $connections 连接")
                if (checked.isNotBlank()) Text("检测时间：$checked")
                if (runtime.wanError.isNotBlank()) Text(runtime.wanError, color = t.danger)
                Text("点击仪表中的网络区域切换局域网与出口显示。", color = t.textSecondary)
            }
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.align(androidx.compose.ui.Alignment.End).heightIn(min = 48.dp),
            ) { Text("关闭") }
        }
    }
}

@Composable
private fun PanelDivider() {
    Box(Modifier.fillMaxWidth().height(.5.dp).background(LocalHetuTokens.current.textMuted.copy(alpha = .14f)))
}
