package io.github.xgl34222220.hetu

import io.github.xgl34222220.hetu.ui.CrystalSurface as Surface
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
    val target = if (showBusy) "测速中" else when { value == null -> "— ms"; value <= 0L -> "超时"; else -> "$value ms" }
    val motion = LocalHetuMotionEnabled.current
    Box(modifier.then(if (onClick != null) Modifier.sizeIn(minWidth = 72.dp, minHeight = 48.dp)
        .clickable(enabled = !testing, role = Role.Button, onClickLabel = "测速", onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center) {
        Surface(shape = CircleShape, color = if (showBusy || value == null) Color.Transparent else background,
            modifier = Modifier.semantics { stateDescription = if (showBusy) "正在测速" else "$band $target" }) {
            Row(Modifier.padding(horizontal = 9.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (showBusy) HetuBusyIndicator(Modifier.size(12.dp), t.textSecondary)
                AnimatedContent(target, transitionSpec = { fadeIn(tween(if (motion) 150 else 0)).togetherWith(fadeOut(tween(if (motion) 90 else 0))) }, label = "latency") {
                    HetuNumber(it, monospaced = true, color = if (showBusy || value == null) t.textSecondary.copy(alpha = .65f) else foreground,
                        style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold))
                }
            }
        }
    }
}

@Composable
internal fun StrategyGroupCard(group: ProxyGroupUi, selected: String, expanded: Boolean, value: Long?, testing: Boolean,
    modifier: Modifier = Modifier, onExpand: () -> Unit, onDelay: () -> Unit,
    hazeState: dev.chrisbanes.haze.HazeState? = null, glassEnabled: Boolean = false) {
    LiquidStrategyCard(group, selected, expanded, value, testing, modifier, onExpand, onDelay, hazeState, glassEnabled)
}

@Composable
internal fun NodeChoiceCard(node: ProxyNodeUi, active: Boolean, value: Long?, testing: Boolean,
    modifier: Modifier = Modifier, onSelect: () -> Unit, onDelay: () -> Unit) {
    LiquidNodeCard(node, active, value, testing, modifier, onSelect, onDelay)
}

@Composable
internal fun WorkspaceBento(runtime: ProxyRuntimeSnapshot, connections: Int, up: Long, down: Long,
    used: Long, total: Long, count: Int, memory: Long, cpu: Float, onSubscription: () -> Unit) {
    InstrumentBento(runtime, connections, up, down, used, total, count, memory, cpu, onSubscription)
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
