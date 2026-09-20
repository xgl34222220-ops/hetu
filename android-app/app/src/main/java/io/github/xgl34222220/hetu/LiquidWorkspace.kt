package io.github.xgl34222220.hetu

import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import io.github.xgl34222220.hetu.ui.*
import dev.chrisbanes.haze.*
import dev.chrisbanes.haze.materials.*
import kotlinx.coroutines.delay

// Presentation only. All callbacks are provided by the existing controller.
@Composable
internal fun liquidColumns(width: Dp): Int =
    if (width < 292.dp || LocalDensity.current.fontScale > 1.35f) 1 else 2

internal fun liquidGroupType(type: String): String = when (type.lowercase()) {
    "selector", "select" -> "手动选择"
    "urltest", "url-test" -> "自动测速"
    "fallback" -> "故障转移"
    "loadbalance", "load-balance" -> "负载均衡"
    else -> type
}

@Composable
internal fun LiquidBrandTray(group: ProxyGroupUi) {
    val primary = MaterialTheme.colorScheme.primary
    val dark = MaterialTheme.colorScheme.background.luminance() < .5f
    val name = group.name.lowercase()
    val accent = when {
        "ai" in name || "chatgpt" in name || "emby" in name -> Color(0xFF10A37F)
        "youtube" in name || "netflix" in name -> Color(0xFFE5484D)
        "telegram" in name || "twitter" in name -> Color(0xFF38A4D8)
        else -> primary
    }
    val shape = RoundedCornerShape(11.dp)
    Box(Modifier.size(32.dp).testTag("brand-tray:${group.name}")
        .shadow(2.dp, shape, clip = false, ambientColor = accent.copy(alpha = .05f), spotColor = accent.copy(alpha = .09f))
        .background(Brush.verticalGradient(listOf(
            if (dark) accent.copy(alpha = .19f) else androidx.compose.ui.graphics.lerp(Color.White, accent, .045f),
            if (dark) accent.copy(alpha = .09f) else androidx.compose.ui.graphics.lerp(Color.White, accent, .095f))), shape)
        .border(.6.dp, accent.copy(alpha = .13f), shape).clip(shape), contentAlignment = Alignment.Center) {
        // Never tint or substitute the configured bitmap with a guessed flag.
        ConfiguredGroupIcon(group, Modifier.size(28.dp))
    }
}

@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
internal fun LiquidStrategyCard(group: ProxyGroupUi, selected: String, expanded: Boolean, value: Long?, testing: Boolean,
    modifier: Modifier = Modifier, onExpand: () -> Unit, onDelay: () -> Unit,
    hazeState: HazeState? = null, glassEnabled: Boolean = false) {
    val t = LocalHetuTokens.current
    val primary = MaterialTheme.colorScheme.primary
    val dark = MaterialTheme.colorScheme.background.luminance() < .5f
    val motion = LocalHetuMotionEnabled.current
    val interactions = remember(group.name) { MutableInteractionSource() }
    val pressed by interactions.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) .98f else 1f, tween(if (motion) 110 else 0), label = "groupPress")
    val angle by animateFloatAsState(if (expanded) 180f else 0f, tween(if (motion) 250 else 0), label = "groupArrow")
    val sink by animateDpAsState(if (expanded) 2.dp else 0.dp, tween(if (motion) 180 else 0), label = "groupSink")
    val shape = RoundedCornerShape(22.dp)
    Column(modifier.offset(y = sink).heightIn(min = 88.dp)
        .graphicsLayer { scaleX = scale; scaleY = scale }
        .crystalMaterial(shape, selection = expanded)
        .testTag("strategy:${group.name}")) {
        Row(Modifier.fillMaxWidth().heightIn(min = 40.dp)
            .clickable(interactionSource = interactions, indication = null, role = Role.Button,
                onClickLabel = if (expanded) "收起策略组" else "展开策略组", onClick = onExpand)
            .padding(start = 13.dp, end = 10.dp, top = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = 3.dp)) {
                Text(group.name, color = t.textPrimary, fontSize = 14.5.sp, lineHeight = 18.sp, fontWeight = FontWeight.ExtraBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${liquidGroupType(group.type)} · ${group.nodes.size}", color = t.textSecondary, fontSize = 10.5.sp,
                    lineHeight = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            LiquidBrandTray(group)
            Icon(Icons.Rounded.KeyboardArrowDown, null, Modifier.padding(start = 3.dp).size(14.dp)
                .graphicsLayer { rotationZ = angle }, tint = primary)
        }
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(start = 13.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).heightIn(min = 48.dp).clickable(interactionSource = interactions, indication = null,
                role = Role.Button, onClick = onExpand), verticalAlignment = Alignment.CenterVertically) {
                val flag = refNodeFlag(selected)
                if (flag.isNotBlank()) { Text(flag, fontSize = 12.sp); Spacer(Modifier.width(4.dp)) }
                Text(selected.ifBlank { "未选择" }, color = t.textSecondary, fontSize = 11.5.sp, lineHeight = 15.sp,
                    fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            LatencyChip(value, testing, onDelay, Modifier.testTag("strategy-delay:${group.name}"))
        }
    }
}

@Composable
internal fun LiquidNodeCard(node: ProxyNodeUi, active: Boolean, value: Long?, testing: Boolean,
    modifier: Modifier = Modifier, onSelect: () -> Unit, onDelay: () -> Unit, index: Int = 0) {
    val t = LocalHetuTokens.current
    val primary = MaterialTheme.colorScheme.primary
    val dark = MaterialTheme.colorScheme.background.luminance() < .5f
    val motion = LocalHetuMotionEnabled.current
    val interaction = remember(node.name) { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    var reveal by remember(node.name, motion) { mutableStateOf(!motion) }
    LaunchedEffect(node.name, motion) { if (motion) delay((index * 15L).coerceAtMost(90L)); reveal = true }
    val alpha by animateFloatAsState(if (reveal) 1f else .78f, tween(if (motion) 160 else 0), label = "nodeReveal")
    val scale by animateFloatAsState(if (pressed) .97f else 1f, tween(if (motion) 110 else 0), label = "nodePress")
    val shape = RoundedCornerShape(14.dp)
    Box(modifier.heightIn(min = 76.dp).testTag("node:${node.name}")
        .graphicsLayer { this.alpha = alpha; scaleX = scale; scaleY = scale }
        .crystalMaterial(shape, depth = CrystalDepth.InsetItem, selection = active)
        .clip(shape)) {
        Column {
            Row(Modifier.fillMaxWidth().heightIn(min = 28.dp).clickable(interactionSource = interaction, indication = null,
                role = Role.RadioButton, onClick = onSelect).semantics { selected = active }
                .padding(start = 10.dp, end = 24.dp, top = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                val flag = refNodeFlag(node.name)
                if (flag.isNotBlank()) { Text(flag, fontSize = 12.sp); Spacer(Modifier.width(4.dp)) }
                Text(node.name, color = if (active) primary else t.textPrimary, fontSize = 12.sp, lineHeight = 16.sp,
                    fontWeight = if (active) FontWeight.ExtraBold else FontWeight.SemiBold,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("node-label:${node.name}"))
            }
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(start = 10.dp, end = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f).heightIn(min = 48.dp).clickable(interactionSource = interaction, indication = null,
                    role = Role.RadioButton, onClick = onSelect).semantics { selected = active }, contentAlignment = Alignment.CenterStart) {
                    Text(node.type.lowercase().ifBlank { "节点" }, color = if (active) primary.copy(alpha = .66f) else t.textMuted,
                        fontSize = 9.5.sp, lineHeight = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                LatencyChip(value, testing, onDelay, Modifier.testTag("node-delay:${node.name}"))
            }
        }
        if (active) Icon(Icons.Rounded.Check, "已选择", Modifier.align(Alignment.TopEnd).padding(top = 8.dp, end = 6.dp).size(13.dp), tint = primary)
    }
}

@Composable
internal fun LiquidGroupWell(group: ProxyGroupUi, selected: String, delays: Map<String, Long>, testing: Map<String, Boolean>,
    onSelect: (String) -> Unit, onDelay: (String) -> Unit, onTestAll: () -> Unit) {
    val t = LocalHetuTokens.current
    val dark = MaterialTheme.colorScheme.background.luminance() < .5f
    val shape = RoundedCornerShape(22.dp)
    val well = if (dark) Color(0xFF18212E) else Color(0xFFEEF2F6)
    Column(Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 16.dp).testTag("sunken-well:${group.name}")
        .clip(shape).background(Brush.verticalGradient(listOf(if (dark) Color(0xFF141C27) else Color(0xFFE8EEF4), well,
            if (dark) Color(0xFF202A37) else Color(0xFFF2F5F8))), shape)
        .border(.7.dp, if (dark) Color.White.copy(alpha = .07f) else Color(0xFFCBD5E1).copy(alpha = .6f), shape)
        .drawWithContent { drawContent(); drawRect(Brush.verticalGradient(listOf(Color(0xFF0F172A).copy(alpha = .045f), Color.Transparent), endY = 5.dp.toPx())) }
        .padding(horizontal = 12.dp, vertical = 9.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("切换落地节点", color = t.textSecondary, fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.fillMaxWidth().heightIn(min = with(LocalDensity.current) { 24.sp.toDp() })
                        .testTag("well-title:${group.name}"))
                Text("点击即生效", color = t.textMuted, fontSize = 10.sp, lineHeight = 13.sp)
            }
            LiquidPill("全测速", Icons.Rounded.Bolt, onTestAll,
                Modifier.widthIn(min = 76.dp).testTag("well-testall:${group.name}"), compact = true)
        }
        BoxWithConstraints {
            val columns = liquidColumns(maxWidth)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                group.nodes.withIndex().toList().chunked(columns).forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { (index, node) ->
                            LiquidNodeCard(node, node.name == selected, delays[node.name] ?: node.lastDelay,
                                testing[node.name] == true, Modifier.weight(1f), { onSelect(node.name) }, { onDelay(node.name) }, index)
                        }
                        if (row.size < columns) Spacer(Modifier.weight(1f))
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
    val scale by animateFloatAsState(if (pressed) .96f else 1f, tween(if (motion) 100 else 0), label = "pillPress")
    val color = if (danger) t.danger else if (primary || compact) scheme.primary else t.textSecondary
    val fill = if (danger) t.dangerContainer.copy(alpha = .62f) else if (dark) Color.White.copy(alpha = .06f)
        else if (primary) Color(0xFFEFF6FF) else Color(0xFFF1F5F9).copy(alpha = .80f)
    Box(modifier.heightIn(min = 48.dp).graphicsLayer { scaleX = scale; scaleY = scale; alpha = if (enabled) 1f else .45f }
        .clip(CircleShape).clickable(enabled = enabled, interactionSource = interaction, indication = null, role = Role.Button) {
            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK); onClick()
        }, contentAlignment = Alignment.Center) {
        // A compact accessory wraps its label. fillMaxWidth here would consume the
        // header's whole width before the weighted title is measured, forcing it
        // into a vertical column. Full-width home actions still fill their slot.
        Row(Modifier.then(if (compact) Modifier else Modifier.fillMaxWidth())
            .heightIn(min = if (compact) 30.dp else 38.dp)
            .background(Brush.verticalGradient(listOf(fill, fill.copy(alpha = fill.alpha * .75f))), CircleShape)
            .border(.6.dp, if (danger) t.danger.copy(alpha = .10f) else Color.White.copy(alpha = if (dark) .08f else .68f), CircleShape)
            .padding(horizontal = if (compact) 9.dp else 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            Icon(icon, null, Modifier.size(if (compact) 13.dp else 16.dp), tint = color)
            Spacer(Modifier.width(5.dp))
            Text(text, color = color, fontSize = if (compact) 11.sp else 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
    }
}

@Composable
internal fun LiquidHomeActions(running: Boolean, busy: Boolean, onToggle: () -> Unit, onReload: () -> Unit, onRestart: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        LiquidPill(if (busy) "请稍候" else if (running) "停止" else "启动", if (running) Icons.Rounded.Stop else Icons.Rounded.PlayArrow,
            onToggle, Modifier.weight(1f), enabled = !busy, danger = running, primary = !running)
        LiquidPill("重载", Icons.Rounded.Refresh, onReload, Modifier.weight(1f), enabled = running && !busy)
        LiquidPill("重启", Icons.Rounded.RestartAlt, onRestart, Modifier.weight(1f), enabled = running && !busy)
    }
}

@Composable
internal fun LiquidStatusGlyph(running: Boolean, busy: Boolean) {
    val t = LocalHetuTokens.current
    val primary = MaterialTheme.colorScheme.primary
    Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) {
        if (busy) HetuBusyIndicator(Modifier.size(22.dp))
        else Icon(if (running) Icons.Rounded.CheckCircle else Icons.Rounded.PowerSettingsNew, null, Modifier.size(28.dp), tint = primary)
        if (running && !busy) Canvas(Modifier.align(Alignment.BottomEnd).size(9.dp)) {
            drawCircle(t.cardBackground); drawCircle(t.success, radius = size.minDimension * .32f)
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
