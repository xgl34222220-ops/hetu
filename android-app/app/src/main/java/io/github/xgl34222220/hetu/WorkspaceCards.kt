package io.github.xgl34222220.hetu

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.view.HapticFeedbackConstants
import io.github.xgl34222220.hetu.ui.*
import kotlinx.coroutines.delay

internal fun delayBand(value: Long?): String = when {
    value == null -> "unknown"
    value <= 0 -> "failed"
    value < 100 -> "good"
    value <= 300 -> "fair"
    else -> "slow"
}

/** Selection and latency are independent: selected 96ms remains green. */
@Composable
internal fun LatencyChip(value: Long?, testing: Boolean, onClick: (() -> Unit)? = null, modifier: Modifier = Modifier) {
    val t = LocalHetuTokens.current
    val band = delayBand(value)
    val foreground = when (band) { "good" -> t.success; "fair" -> t.warning; "failed", "slow" -> t.danger; else -> t.textSecondary }
    val background = when (band) { "good" -> t.successContainer; "fair" -> t.warningContainer; "failed", "slow" -> t.dangerContainer; else -> t.controlBackground }
    var showBusy by remember { mutableStateOf(false) }
    LaunchedEffect(testing) { if (testing) { delay(150); showBusy = true } else showBusy = false }
    val target = if (showBusy) "测速中" else when { value == null -> "未测速"; value <= 0L -> "超时"; else -> "$value ms" }
    val motion = LocalHetuMotionEnabled.current
    Box(modifier.then(if (onClick != null) Modifier.sizeIn(minWidth = 72.dp, minHeight = 48.dp)
        .clickable(enabled = !testing, role = Role.Button, onClickLabel = "测速", onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center) {
        Surface(shape = CircleShape, color = if (showBusy) t.controlBackground else background,
            modifier = Modifier.semantics { stateDescription = if (showBusy) "正在测速" else "$band $target" }) {
            Row(Modifier.padding(horizontal = 9.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (showBusy) HetuBusyIndicator(Modifier.size(12.dp), t.textSecondary)
                AnimatedContent(target, transitionSpec = { fadeIn(tween(if (motion) 150 else 0)).togetherWith(fadeOut(tween(if (motion) 90 else 0))) }, label = "latency") {
                    HetuNumber(it, color = if (showBusy) t.textSecondary else foreground,
                        style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold))
                }
            }
        }
    }
}

@Composable
internal fun StrategyGroupCard(group: ProxyGroupUi, selected: String, expanded: Boolean, value: Long?, testing: Boolean,
    modifier: Modifier = Modifier, onExpand: () -> Unit, onDelay: () -> Unit) {
    val t = LocalHetuTokens.current
    val primary = MaterialTheme.colorScheme.primary
    val shape = RoundedCornerShape(18.dp)
    Column(modifier.background(t.cardBackground, shape)
        .border(1.dp, if (expanded) primary.copy(alpha = .28f) else t.outline.copy(alpha = .35f), shape)
        .testTag("strategy:${group.name}")) {
        // Independent sibling hit regions, not a tiny child inside a clickable parent.
        Column(Modifier.fillMaxWidth().clickable(role = Role.Button, onClickLabel = if (expanded) "收起策略组" else "展开策略组", onClick = onExpand)
            .padding(start = 14.dp, top = 14.dp, end = 14.dp, bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ConfiguredGroupIcon(group, Modifier.size(32.dp))
                Text(group.name, Modifier.weight(1f), color = t.textPrimary, fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold)
                Icon(if (expanded) Icons.Rounded.KeyboardArrowUp else Icons.Rounded.KeyboardArrowDown, null,
                    Modifier.size(18.dp), tint = if (expanded) primary else t.textSecondary)
            }
            Text(selected.ifBlank { "未选择" }, color = t.textSecondary, fontSize = 13.sp, lineHeight = 19.sp,
                modifier = Modifier.fillMaxWidth().heightIn(min = 38.dp))
        }
        Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            val flag = refNodeFlag(selected)
            if (flag.isNotBlank() && !selected.contains(flag)) Text(flag, fontSize = 16.sp)
            Spacer(Modifier.weight(1f))
            LatencyChip(value, testing, onDelay, Modifier.testTag("strategy-delay:${group.name}"))
        }
    }
}

@Composable
internal fun NodeChoiceCard(node: ProxyNodeUi, active: Boolean, value: Long?, testing: Boolean,
    modifier: Modifier = Modifier, onSelect: () -> Unit, onDelay: () -> Unit) {
    val t = LocalHetuTokens.current
    val primary = MaterialTheme.colorScheme.primary
    Row(modifier.fillMaxWidth().heightIn(min = 72.dp)
        .background(if (active) t.selectionBackground.copy(alpha = .45f) else t.cardBackground, RoundedCornerShape(14.dp))
        .border(1.dp, if (active) primary.copy(alpha = .4f) else t.outline.copy(alpha = .28f), RoundedCornerShape(14.dp))
        .testTag("node:${node.name}"), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f).heightIn(min = 72.dp).clickable(role = Role.RadioButton, onClick = onSelect)
            .semantics { selected = active }.padding(12.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (active) Icon(Icons.Rounded.CheckCircle, "已选择", Modifier.size(18.dp), tint = primary)
            Text(node.name, color = t.textPrimary, fontSize = 14.sp, lineHeight = 20.sp)
        }
        LatencyChip(value, testing, onDelay, Modifier.padding(end = 6.dp).testTag("node-delay:${node.name}"))
    }
}

@Composable
internal fun WorkspaceBento(runtime: ProxyRuntimeSnapshot, connections: Int, up: Long, down: Long,
    used: Long, total: Long, count: Int, memory: Long, cpu: Float, onSubscription: () -> Unit) {
    val t = LocalHetuTokens.current
    val view = LocalView.current
    var lan by rememberSaveable { mutableStateOf(false) }
    val shape = RoundedCornerShape(18.dp)
    val ratio = if (total > 0) (used.toDouble() / total).toFloat().coerceIn(0f, 1f) else 0f
    @Composable fun Cell(title: String, modifier: Modifier, onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
        Column(modifier.heightIn(min = 132.dp).then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, color = t.textSecondary, fontSize = 12.sp, lineHeight = 17.sp)
            content()
        }
    }
    @Composable fun Network(modifier: Modifier) {
        Cell(if (lan) "局域网 · 点击切换" else "出口 · 点击切换", modifier, {
            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK); lan = !lan
        }) {
            val display = if (lan) runtime.lanAddress else when (runtime.wanState) {
                "success", "stale" -> runtime.wanAddress
                "loading" -> "检测中…"
                "failed" -> "检测失败"
                else -> "未检测"
            }
            HetuNumber(display, style = MaterialTheme.typography.titleMedium.copy(fontSize = 15.sp, lineHeight = 21.sp))
            val checked = if (runtime.wanCheckedAt > 0) java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(runtime.wanCheckedAt)) else ""
            Text(if (lan) "${runtime.lanInterface} · $connections 连接" else when (runtime.wanState) {
                "success" -> "${countryEmoji(runtime.wanCountryCode)} ${runtime.wanRegion} · $checked"
                "stale" -> "上次检测 $checked · 尚未重新确认"
                "loading" -> "通过当前 Mihomo 规则检测"
                "failed" -> runtime.wanError.ifBlank { "稍后自动重试" }
                else -> if (runtime.running) "等待出口检测" else "代理已停止"
            }, color = t.textSecondary, fontSize = 12.sp, lineHeight = 17.sp)
        }
    }
    @Composable fun Speed(modifier: Modifier) {
        Cell("实时网速", modifier) {
            MetricLine("↑ 上行", refSpeed(up), t.success)
            MetricLine("↓ 下行", refSpeed(down), MaterialTheme.colorScheme.primary)
        }
    }
    @Composable fun Subscription(modifier: Modifier) {
        Cell("已用流量", modifier, onSubscription) {
            HetuNumber(if (total > 0) refBytes(used) else "—", style = MaterialTheme.typography.titleMedium.copy(fontSize = 18.sp))
            HetuReadOnlyProgress(ratio)
            Text(if (total > 0) "总 ${refBytes(total)} · 剩余 ${((1f-ratio)*100).toInt()}%" else "$count 个订阅 · 未上报额度",
                color = t.textSecondary, fontSize = 12.sp, lineHeight = 17.sp)
        }
    }
    @Composable fun Resource(modifier: Modifier) {
        Cell("资源占用", modifier) {
            MetricLine("内存", refBytes(memory))
            MetricLine("CPU", String.format(java.util.Locale.US, "%.1f%%", cpu))
            HetuReadOnlyProgress(cpu / 100f)
        }
    }
    Surface(shape = shape, color = t.cardBackground, modifier = Modifier.fillMaxWidth().testTag("workspace-bento")) {
        BoxWithConstraints {
            val columns = hetuCompactColumns(maxWidth, 152.dp)
            Column {
                if (columns == 1) {
                    Network(Modifier.fillMaxWidth()); DividerLine()
                    Speed(Modifier.fillMaxWidth()); DividerLine()
                    Subscription(Modifier.fillMaxWidth()); DividerLine()
                    Resource(Modifier.fillMaxWidth())
                } else {
                    Row(Modifier.height(IntrinsicSize.Min)) {
                        Network(Modifier.weight(1f)); VerticalDivider(Modifier.fillMaxHeight().padding(vertical = 16.dp), color = t.outline.copy(alpha = .45f))
                        Speed(Modifier.weight(1f))
                    }
                    DividerLine()
                    Row(Modifier.height(IntrinsicSize.Min)) {
                        Subscription(Modifier.weight(1f)); VerticalDivider(Modifier.fillMaxHeight().padding(vertical = 16.dp), color = t.outline.copy(alpha = .45f))
                        Resource(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun DividerLine() { HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = LocalHetuTokens.current.outline.copy(alpha = .45f)) }

@Composable
private fun MetricLine(label: String, value: String, color: Color = LocalHetuTokens.current.textSecondary) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, color = color, fontSize = 12.sp, lineHeight = 17.sp)
        HetuNumber(value, style = MaterialTheme.typography.titleSmall.copy(fontSize = 16.sp, lineHeight = 21.sp))
    }
}

@Composable
internal fun RuleMetricSummary(rules: Int, sources: Int, hits: Long?, modifier: Modifier = Modifier) {
    val t = LocalHetuTokens.current
    val nf = remember { java.text.NumberFormat.getIntegerInstance(java.util.Locale.US) }
    Column(modifier.fillMaxWidth().testTag("rule-metrics"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("有效规则", color = t.textSecondary, fontSize = 12.sp)
        HetuNumber(nf.format(rules), Modifier.fillMaxWidth().heightIn(min = with(LocalDensity.current) { 28.sp.toDp() }).testTag("rule-count"), style = MaterialTheme.typography.titleLarge.copy(fontSize = 20.sp, lineHeight = 26.sp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(Modifier.weight(1f)) { Text("已启用规则源", color = t.textSecondary, fontSize = 12.sp); HetuNumber(nf.format(sources)) }
            Column(Modifier.weight(1f)) { Text("域名命中", color = t.textSecondary, fontSize = 12.sp); HetuNumber(hits?.let { nf.format(it) } ?: "—") }
        }
    }
}
