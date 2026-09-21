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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
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

/** Home dashboard measured from the 156785 reference video. */
internal fun instrumentFraction(value: Long, total: Long): Float? =
    if (total > 0L && value >= 0L) (value.toDouble() / total.toDouble()).toFloat().coerceIn(0f, 1f) else null

internal fun instrumentUploadShare(up: Long, down: Long): Float? {
    if (up < 0L || down < 0L || (up == 0L && down == 0L)) return null
    return (up.toDouble() / (up.toDouble() + down.toDouble())).toFloat().coerceIn(0f, 1f)
}

@Composable
private fun ReferenceDashboardCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val t = LocalHetuTokens.current
    val shape = RoundedCornerShape(20.dp)
    @OptIn(ExperimentalFoundationApi::class)
    val interaction = if (onClick != null || onLongClick != null) {
        Modifier.combinedClickable(
            onClick = onClick ?: {},
            onLongClick = onLongClick,
            role = if (onClick != null) Role.Button else null,
        )
    } else Modifier
    Column(
        modifier
            .heightIn(min = 102.dp)
            .clip(shape)
            .background(t.cardBackground)
            .then(interaction)
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        content = content,
    )
}

@Composable
private fun ReferenceProgress(
    progress: Float?,
    tag: String,
    accent: Color,
) {
    val t = LocalHetuTokens.current
    val p = progress?.takeIf { it.isFinite() }?.coerceIn(0f, 1f)
    Box(
        Modifier
            .fillMaxWidth()
            .height(3.dp)
            .clip(CircleShape)
            .background(Color(0xFFD7E0F5))
            .testTag(tag)
            .semantics {
                if (p != null) progressBarRangeInfo = ProgressBarRangeInfo(p, 0f..1f)
            },
    ) {
        if (p != null && p > 0f) {
            Box(
                Modifier
                    .fillMaxWidth(p.coerceAtLeast(.01f))
                    .fillMaxHeight()
                    .background(accent, CircleShape),
            )
        }
    }
}

@Composable
private fun ReferenceMetricLine(label: String, value: String) {
    val t = LocalHetuTokens.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            color = t.textSecondary,
            fontSize = 12.sp,
            lineHeight = 17.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.weight(1f))
        Text(
            value,
            color = t.textPrimary,
            fontSize = 13.5.sp,
            lineHeight = 18.sp,
            fontWeight = FontWeight.ExtraBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
internal fun AlignedInstrumentPanel(
    runtime: ProxyRuntimeSnapshot,
    connections: Int,
    up: Long,
    down: Long,
    used: Long,
    total: Long,
    count: Int,
    memory: Long,
    cpu: Float,
    onSubscription: () -> Unit,
) {
    val t = LocalHetuTokens.current
    val view = LocalView.current
    var lan by rememberSaveable { mutableStateOf(false) }
    var details by rememberSaveable { mutableStateOf(false) }
    val usageRatio = instrumentFraction(used, total)
    val cpuRatio = if (runtime.running && cpu.isFinite() && cpu >= 0f) {
        (cpu / 100f).coerceIn(0f, 1f)
    } else null

    val checked = if (runtime.wanCheckedAt > 0L) {
        DateTimeFormatter.ofPattern("HH:mm")
            .withZone(ZoneId.systemDefault())
            .format(Instant.ofEpochMilli(runtime.wanCheckedAt))
    } else ""

    val networkState = when {
        lan -> "局域网"
        runtime.wanState == "success" -> "已检测"
        runtime.wanState == "stale" -> "上次结果"
        runtime.wanState == "loading" -> "读取中"
        runtime.wanState == "failed" -> "失败"
        else -> "未检测"
    }

    val address = if (lan) runtime.lanAddress else when (runtime.wanState) {
        "success", "stale" -> runtime.wanAddress
        "loading" -> "读取中"
        "failed" -> "检测失败"
        else -> "未检测"
    }

    val region = if (lan) {
        "${runtime.lanInterface} · $connections 连接"
    } else when (runtime.wanState) {
        "success" -> "${countryEmoji(runtime.wanCountryCode)} ${runtime.wanRegion.substringBefore(" · ")}"
        "stale" -> "上次 $checked"
        "loading" -> "正在检测当前网络…"
        "failed" -> "长按查看失败详情"
        else -> if (runtime.running) "等待出口检测" else "代理已停止"
    }

    Column(
        Modifier.fillMaxWidth().testTag("workspace-bento"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ReferenceDashboardCard(
                Modifier.weight(1f).testTag("instrument-network"),
                onClick = {
                    view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    lan = !lan
                },
                onLongClick = { details = true },
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("WAN", color = t.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold)
                    Spacer(Modifier.weight(1f))
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0xFFE7ECFA))
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                    ) {
                        Text("详情", color = HetuMicroCrystal.KleinBlue, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                }
                Text(
                    address,
                    Modifier.fillMaxWidth().testTag("instrument-network-value"),
                    color = t.textPrimary,
                    fontSize = 15.sp,
                    lineHeight = 20.sp,
                    fontWeight = FontWeight.ExtraBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "地区  $region",
                    Modifier.fillMaxWidth().testTag("instrument-network-support"),
                    color = t.textSecondary,
                    fontSize = 11.5.sp,
                    lineHeight = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            ReferenceDashboardCard(
                Modifier.weight(1f).testTag("instrument-speed"),
            ) {
                Text("网速", color = t.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold)
                ReferenceMetricLine("上行", if (runtime.running) refSpeed(up) else "0 B/s")
                ReferenceMetricLine("下行", if (runtime.running) refSpeed(down) else "0 B/s")
            }
        }

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ReferenceDashboardCard(
                Modifier.weight(1f).testTag("instrument-usage"),
                onClick = onSubscription,
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("订阅", color = t.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold)
                    Spacer(Modifier.weight(1f))
                    Text(
                        usageRatio?.let { "剩余 ${((1f - it) * 100f).toInt()}%" } ?: "未上报",
                        color = t.textSecondary,
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
                ReferenceMetricLine("已用", usageRatio?.let { refBytes(used) } ?: "—")
                ReferenceMetricLine("总量", usageRatio?.let { refBytes(total) } ?: "—")
                Spacer(Modifier.weight(1f))
                ReferenceProgress(usageRatio, "home-usage-progress", HetuMicroCrystal.KleinBlue)
            }

            ReferenceDashboardCard(
                Modifier.weight(1f).testTag("instrument-resource"),
            ) {
                Text("资源占用", color = t.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold)
                ReferenceMetricLine("内存", if (runtime.running && memory >= 0L) refBytes(memory) else "—")
                ReferenceMetricLine(
                    "CPU",
                    if (runtime.running && cpu.isFinite() && cpu >= 0f) {
                        String.format(Locale.US, "%.1f%%", cpu)
                    } else "—",
                )
                Spacer(Modifier.weight(1f))
                ReferenceProgress(cpuRatio, "home-cpu-progress", HetuMicroCrystal.KleinBlue)
            }
        }
    }

    if (details) {
        InstrumentNetworkDetailSheet(
            networkState = networkState,
            runtime = runtime,
            connections = connections,
            checked = checked,
            onDismiss = { details = false },
        )
    }
}

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
    Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFFF1F5F9)))
}
