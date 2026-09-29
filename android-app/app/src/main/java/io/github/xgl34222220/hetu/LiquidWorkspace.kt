package io.github.xgl34222220.hetu

import android.view.HapticFeedbackConstants
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import io.github.xgl34222220.hetu.ui.*
import dev.chrisbanes.haze.*
import dev.chrisbanes.haze.materials.*
import kotlinx.coroutines.delay

// Presentation only. All callbacks are provided by the existing controller.
@Composable
internal fun liquidColumns(width: Dp, requested: Int = 0): Int {
    val scale = LocalDensity.current.fontScale.coerceAtLeast(1f)
    val capacity = ((width.value + 10f) / (136f * scale + 10f)).toInt().coerceIn(1, 3)
    return (if (requested == 0) 2 else requested.coerceIn(1, 3)).coerceAtMost(capacity)
}

internal fun liquidGroupType(type: String): String = when (type.lowercase()) {
    "selector", "select" -> "手动选择"
    "urltest", "url-test" -> "自动测速"
    "fallback" -> "故障转移"
    "loadbalance", "load-balance" -> "负载均衡"
    else -> type
}

internal fun liquidNodeProtocolLabel(node: ProxyNodeUi): String {
    val raw = node.type.trim()
    val container = raw.lowercase(java.util.Locale.ROOT) in setOf(
        "urltest", "url-test", "selector", "select", "fallback",
        "loadbalance", "load-balance", "relay", "compatible",
    )
    return when {
        raw.isNotBlank() && !container -> raw.uppercase(java.util.Locale.ROOT)
        node.udp -> "UDP"
        else -> "策略组"
    }
}

@Composable
internal fun LiquidBrandTray(group: ProxyGroupUi) {
    Box(
        Modifier
            .size(34.dp)
            .testTag("brand-tray:${group.name}"),
        contentAlignment = Alignment.Center,
    ) {
        ConfiguredGroupIcon(group, Modifier.size(32.dp))
    }
}

@Composable
private fun ReferenceDelayPill(value: Long?, testing: Boolean, modifier: Modifier = Modifier) {
    LatencyChip(
        value = value,
        testing = testing,
        modifier = modifier,
        compact = true,
    )
}

@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
internal fun LiquidStrategyCard(
    group: ProxyGroupUi, selected: String, expanded: Boolean, value: Long?, testing: Boolean,
    modifier: Modifier = Modifier, onExpand: () -> Unit, onDelay: () -> Unit,
    hazeState: HazeState? = null, glassEnabled: Boolean = false,
) {
    val t = LocalHetuTokens.current
    val scale = LocalDensity.current.fontScale.coerceAtLeast(1f)
    top.yukonga.miuix.kmp.basic.Card(
        modifier = modifier.testTag("strategy:${group.name}")
            .miuixTap(onClickLabel = "查看${group.name}节点", onClick = onExpand),
        cornerRadius = 16.dp,
        insideMargin = PaddingValues(12.dp, 10.dp),
        colors = top.yukonga.miuix.kmp.basic.CardDefaults.defaultColors(
            if (expanded) t.selectionBackground else t.cardBackground, t.textPrimary),
    ) {
        Column(Modifier.fillMaxWidth().heightIn(min = (52f * scale).dp),
            verticalArrangement = Arrangement.SpaceBetween) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ConfiguredGroupIcon(group, Modifier.size(20.dp))
                Text(group.name, Modifier.weight(1f).testTag("strategy-title:${group.name}"),
                    color = t.textPrimary, fontSize = 14.sp, lineHeight = 20.sp,
                    fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(liquidGroupType(group.type), Modifier.widthIn(max = 52.dp).testTag("strategy-type:${group.name}"),
                    color = t.textSecondary, fontSize = 10.sp, lineHeight = 15.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(selected.ifBlank { "未选择节点" },
                    Modifier.weight(1f).testTag("strategy-selection:${group.name}"),
                    color = t.textSecondary, fontSize = 12.sp, lineHeight = 18.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                LatencyChip(value, testing, onClick = onDelay, compact = true,
                    modifier = Modifier.testTag("strategy-delay:${group.name}"))
            }
        }
    }
}

@Composable
internal fun LiquidNodeCard(
    node: ProxyNodeUi, active: Boolean, value: Long?, testing: Boolean,
    modifier: Modifier = Modifier, onSelect: () -> Unit, onDelay: () -> Unit, index: Int = 0,
) {
    val t = LocalHetuTokens.current
    val prefs = LocalContext.current.getSharedPreferences("hetu", 0)
    val scale = LocalDensity.current.fontScale.coerceAtLeast(1f)
    val accent = MaterialTheme.colorScheme.primary
    val selectedFill by animateColorAsState(if (active) t.selectionBackground else t.cardBackground,
        tween(if (LocalHetuMotionEnabled.current) 160 else 0), label = "nodeSelectionFill")
    Row(modifier.testTag("node:${node.name}")
        .heightIn(min = (56f * scale).dp)
        .clip(RoundedCornerShape(14.dp)).background(selectedFill)
        .miuixTap(role = Role.RadioButton, onClick = onSelect)
        .semantics { selected = active; contentDescription = node.name }
        .padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(node.name, Modifier.fillMaxWidth().testTag("node-label:${node.name}"),
                color = if (active) accent else t.textPrimary, fontSize = 13.sp, lineHeight = 18.sp,
                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = if (prefs.getString("proxySelectorNameOverflow", "ellipsis") == "wrap") Int.MAX_VALUE else 1,
                overflow = TextOverflow.Ellipsis)
            Text(buildString {
                append(liquidNodeProtocolLabel(node))
                if (node.udp && !liquidNodeProtocolLabel(node).contains("UDP")) append(" · UDP")
                if (prefs.getBoolean("proxySelectorDetectIpv6", true)) SelectorIpv6Probe.results[node.name]?.let {
                    append(if (it) " · IPv6 ✓" else " · IPv6 未通")
                }
            }, Modifier.testTag("node-protocol:${node.name}"), color = t.textSecondary,
                fontSize = 10.sp, lineHeight = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        LatencyChip(value, testing, onClick = onDelay, compact = true,
            modifier = Modifier.testTag("node-delay:${node.name}"))
    }
}

@Composable
internal fun LiquidGroupWell(
    group: ProxyGroupUi, selected: String, delays: Map<String, Long>, testing: Map<String, Boolean>,
    onSelect: (String) -> Unit, onDelay: (String) -> Unit, onTestAll: () -> Unit,
) {
    val prefs = LocalContext.current.getSharedPreferences("hetu", 0)
    val requested = prefs.getInt("proxySelectorNodeColumns", 0).coerceIn(0, 3)
    BoxWithConstraints(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp)
        .testTag("sunken-well:${group.name}")) {
        val columns = liquidColumns(maxWidth, requested)
        val rows = remember(group.nodes, columns) { group.nodes.chunked(columns) }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            rows.forEach { row ->
                key(row.firstOrNull()?.name) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        row.forEach { node ->
                            key(node.name) {
                                LiquidNodeCard(node, node.name == selected, delays[node.name] ?: node.lastDelay,
                                    testing[node.name] == true, modifier = Modifier.weight(1f),
                                    onSelect = { onSelect(node.name) }, onDelay = { onDelay(node.name) })
                            }
                        }
                        repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }
}

@Composable
internal fun LiquidPill(text: String, icon: ImageVector, onClick: () -> Unit, modifier: Modifier = Modifier,
    enabled: Boolean = true, danger: Boolean = false, primary: Boolean = false, compact: Boolean = false) {
    val t = LocalHetuTokens.current
    val scheme = MaterialTheme.colorScheme
    val dark = scheme.background.luminance() < .5f
    val view = LocalView.current
    val motion = LocalHetuMotionEnabled.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) HetuMotionSpec.PressedScale else 1f,
        animationSpec = if (motion) spring(
            dampingRatio = HetuMotionSpec.SelectionDamping,
            stiffness = HetuMotionSpec.SelectionStiffness,
        ) else tween(0),
        label = "pillPress",
    )
    val color = if (danger) Color(0xFFE11D48) else if (primary || compact) scheme.primary else Color(0xFF475569)
    val fill = if (danger) Color(0xFFFFF1F2) else if (dark) Color.White.copy(alpha = .06f)
        else if (primary) Color(0xFFDDF1EC) else Color(0xFFF8FAFC)
    Box(modifier.heightIn(min = if (compact) 36.dp else 48.dp).graphicsLayer { scaleX = scale; scaleY = scale; alpha = if (enabled) 1f else .45f }
        .clip(CircleShape).clickable(enabled = enabled, interactionSource = interaction, indication = null, role = Role.Button) {
            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK); onClick()
        }, contentAlignment = Alignment.Center) {
        // A compact accessory wraps its label. fillMaxWidth here would consume the
        // header's whole width before the weighted title is measured, forcing it
        // into a vertical column. Full-width home actions still fill their slot.
        Row(Modifier.then(if (compact) Modifier else Modifier.fillMaxWidth())
            .heightIn(min = if (compact) 28.dp else 38.dp)
            .background(Brush.verticalGradient(listOf(fill, fill.copy(alpha = fill.alpha * .92f))), CircleShape)
            .border(
                1.dp,
                if (danger) Color(0xFFFFE4E6) else if (dark) Color.White.copy(alpha = .08f) else Color(0xFFF4F5F7),
                CircleShape,
            )
            .padding(horizontal = if (compact) 9.dp else 10.dp, vertical = if (compact) 5.dp else 6.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            if (danger && text == "停止") {
                Box(Modifier.size(6.dp).background(Color(0xFFE11D48), CircleShape))
            } else {
                Icon(icon, null, Modifier.size(if (compact) 13.dp else 16.dp), tint = color)
            }
            Spacer(Modifier.width(5.dp))
            Text(text, color = color, fontSize = if (compact) 11.sp else 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
    }
}

@Composable
internal fun LiquidHomeActions(running: Boolean, busy: Boolean, onToggle: () -> Unit, onReload: () -> Unit, onRestart: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        LiquidPill(
            "重载",
            Icons.Rounded.Refresh,
            onReload,
            Modifier.weight(1f),
            enabled = running && !busy,
        )
        LiquidPill(
            if (busy) "请稍候" else if (running) "停止" else "启动",
            if (running) Icons.Rounded.Stop else Icons.Rounded.PlayArrow,
            onToggle,
            Modifier.weight(1.1f),
            enabled = !busy,
            danger = running,
            primary = !running,
        )
        LiquidPill(
            "重启",
            Icons.Rounded.RestartAlt,
            onRestart,
            Modifier.weight(1f),
            enabled = running && !busy,
        )
    }
}
@Composable
internal fun LiquidStatusGlyph(running: Boolean, busy: Boolean) {
    val primary = MaterialTheme.colorScheme.primary
    val kleinBlue = HetuMicroCrystal.KleinBlue
    val dark = MaterialTheme.colorScheme.background.luminance() < .5f
    Box(
        Modifier
            .size(46.dp)
            .shadow(
                elevation = if (running && !busy) 10.dp else 2.dp,
                shape = CircleShape,
                ambientColor = kleinBlue.copy(alpha = .12f),
                spotColor = kleinBlue.copy(alpha = .35f),
            )
            .background(
                if (running && !busy) kleinBlue
                else if (dark) Color.White.copy(alpha = .08f) else Color(0xFFF8FAFC),
                CircleShape,
            )
            .border(
                1.dp,
                if (running && !busy) Color.Transparent else Color(0xFFE4E7EB),
                CircleShape,
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (busy) {
            HetuBusyIndicator(Modifier.size(22.dp), if (running) Color.White else primary)
        } else {
            Icon(
                if (running) Icons.Rounded.Check else Icons.Rounded.PowerSettingsNew,
                null,
                Modifier.size(if (running) 26.dp else 23.dp),
                tint = if (running) Color.White else primary,
            )
        }
    }
}

@Composable
internal fun LiquidHomeMenu(onLog: () -> Unit, onConnections: () -> Unit, onDiagnostics: () -> Unit,
    onAdblock: () -> Unit, diagnosticLoading: Boolean, hazeState: HazeState? = null, glassEnabled: Boolean = false) {
    CrystalHomeMenu(onLog, onConnections, onDiagnostics, onAdblock, diagnosticLoading, hazeState, glassEnabled)
}

@Composable
internal fun LiquidConfigIndicator(selected: Boolean, modifier: Modifier = Modifier) {
    val primary = MaterialTheme.colorScheme.primary
    val outline = LocalHetuTokens.current.textMuted
    // Draw only a circle and strokes; no elevation layer or rectangular fill.
    Canvas(modifier.size(24.dp).testTag("config-selector").semantics { contentDescription = if (selected) "当前使用" else "未选择" }) {
        val radius = 9.dp.toPx()
        if (selected) {
            drawCircle(primary, radius)
            val path = Path().apply { moveTo(size.width*.30f,size.height*.50f); lineTo(size.width*.44f,size.height*.64f); lineTo(size.width*.71f,size.height*.35f) }
            drawPath(path, Color.White, style = Stroke(width = 1.8.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        } else drawCircle(outline.copy(alpha = .52f), radius, style = Stroke(1.2.dp.toPx()))
    }
}
