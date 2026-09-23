package io.github.xgl34222220.hetu

import android.view.HapticFeedbackConstants
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
internal fun liquidColumns(width: Dp): Int =
    if (width < 292.dp || LocalDensity.current.fontScale > 1.35f) 1 else 2

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
    group: ProxyGroupUi,
    selected: String,
    expanded: Boolean,
    value: Long?,
    testing: Boolean,
    modifier: Modifier = Modifier,
    onExpand: () -> Unit,
    onDelay: () -> Unit,
    hazeState: HazeState? = null,
    glassEnabled: Boolean = false,
) {
    val t = LocalHetuTokens.current
    val motion = LocalHetuMotionEnabled.current
    val context = LocalContext.current
    val selectorPrefs = remember(context) { context.getSharedPreferences("hetu", 0) }
    val compactSelector = selectorPrefs.getString("proxySelectorDensity", "standard") == "compact"
    val nameOverflow = selectorPrefs.getString("proxySelectorNameOverflow", "clip").orEmpty()
    val strategyHeight = if (compactSelector) 68.dp else 80.dp
    val titleModifier = Modifier.fillMaxWidth().testTag("strategy-title:" + group.name)
        .then(if (nameOverflow == "scroll") Modifier.basicMarquee() else Modifier)
    val interactions = remember(group.name) { MutableInteractionSource() }
    val pressed by interactions.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) HetuMotionSpec.PressedScale else 1f,
        animationSpec = if (motion) spring(
            dampingRatio = HetuMotionSpec.SelectionDamping,
            stiffness = HetuMotionSpec.SelectionStiffness,
        ) else tween(0),
        label = "groupPress",
    )
    val measured = group.nodes.count { (it.lastDelay ?: 0L) > 0L }
    val typeText = buildString {
        append(group.type.ifBlank { "Group" })
        append(' ')
        append(measured)
        append('/')
        append(group.nodes.size)
    }

    Box(
        modifier
            .height(strategyHeight)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .crystalMaterial(
                RoundedCornerShape(HetuGlassRadius.Tile),
                depth = CrystalDepth.Card,
                selection = expanded,
            )
            .testTag("strategy:${group.name}")
            .clickable(
                interactionSource = interactions,
                indication = null,
                role = Role.Button,
                onClick = onExpand,
            ),
    ) {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    Text(
                        group.name,
                        titleModifier,
                        color = t.textPrimary,
                        fontSize = if (compactSelector) 14.sp else 15.5.sp,
                        lineHeight = if (compactSelector) 17.sp else 19.sp,
                        fontWeight = FontWeight.ExtraBold,
                        maxLines = if (nameOverflow == "wrap") 2 else 1,
                        softWrap = nameOverflow == "wrap",
                        overflow = if (nameOverflow == "clip" || nameOverflow == "scroll" || nameOverflow == "wrap") TextOverflow.Clip else TextOverflow.Ellipsis,
                    )
                    Text(
                        typeText,
                        Modifier.fillMaxWidth().testTag("strategy-type:${group.name}"),
                        color = t.textSecondary,
                        fontSize = 12.5.sp,
                        lineHeight = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                    )
                }
                Spacer(Modifier.width(6.dp))
                LiquidBrandTray(group)
            }

            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                val flag = refNodeFlag(selected)
                if (flag.isNotBlank()) {
                    Text(flag, fontSize = 11.5.sp)
                    Spacer(Modifier.width(3.dp))
                }
                Text(
                    selected.ifBlank { "未选择" },
                    Modifier.weight(1f).testTag("strategy-selection:" + group.name)
                        .then(if (nameOverflow == "scroll") Modifier.basicMarquee() else Modifier),
                    color = t.textPrimary,
                    fontSize = if (compactSelector) 10.5.sp else 11.5.sp,
                    lineHeight = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = if (nameOverflow == "wrap") 2 else 1,
                    softWrap = nameOverflow == "wrap",
                    overflow = if (nameOverflow == "clip" || nameOverflow == "scroll" || nameOverflow == "wrap") TextOverflow.Clip else TextOverflow.Ellipsis,
                )
                ReferenceDelayPill(value, testing)
            }
        }

        Box(
            Modifier
                .align(Alignment.BottomEnd)
                .size(48.dp)
                .testTag("strategy-delay:${group.name}")
                .clickable(
                    enabled = !testing,
                    role = Role.Button,
                    onClickLabel = "测速",
                    onClick = onDelay,
                ),
        )
    }
}

@Composable
internal fun LiquidNodeCard(
    node: ProxyNodeUi,
    active: Boolean,
    value: Long?,
    testing: Boolean,
    modifier: Modifier = Modifier,
    onSelect: () -> Unit,
    onDelay: () -> Unit,
    index: Int = 0,
) {
    val t = LocalHetuTokens.current
    val motion = LocalHetuMotionEnabled.current
    val context = LocalContext.current
    val selectorPrefs = remember(context) { context.getSharedPreferences("hetu", 0) }
    val compactSelector = selectorPrefs.getString("proxySelectorDensity", "standard") == "compact"
    val nameOverflow = selectorPrefs.getString("proxySelectorNameOverflow", "clip").orEmpty()
    val nodeHeight = if (compactSelector) 66.dp else 78.dp
    val nameModifier = Modifier.fillMaxWidth().testTag("node-label:" + node.name)
        .then(if (nameOverflow == "scroll") Modifier.basicMarquee() else Modifier)
    val interaction = remember(node.name) { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    var reveal by remember(node.name, motion) { mutableStateOf(!motion) }
    LaunchedEffect(node.name, motion) {
        if (motion) delay((index * 15L).coerceAtMost(90L))
        reveal = true
    }
    val alpha by animateFloatAsState(if (reveal) 1f else .78f, tween(if (motion) 150 else 0), label = "nodeReveal")
    val targetScale = if (pressed) HetuMotionSpec.PressedScale else if (active) 1.012f else 1f
    val scale by animateFloatAsState(
        targetValue = targetScale,
        animationSpec = if (motion) spring(
            dampingRatio = HetuMotionSpec.SelectionDamping,
            stiffness = HetuMotionSpec.SelectionStiffness,
        ) else tween(0),
        label = "nodeLiquidSelection",
    )
    val protocol = node.type.ifBlank { if (node.udp) "UDP" else "Node" }

    Box(
        modifier
            .height(nodeHeight)
            .testTag("node:" + node.name)
            .graphicsLayer { this.alpha = alpha; scaleX = scale; scaleY = scale }
            .crystalMaterial(
                RoundedCornerShape(HetuGlassRadius.Tile),
                depth = CrystalDepth.InsetItem,
                selection = false,
            )
            .clickable(
                interactionSource = interaction,
                indication = null,
                role = Role.RadioButton,
                onClick = onSelect,
            )
            .semantics { selected = active },
    ) {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            BasicText(
                text = node.name,
                modifier = nameModifier,
                style = TextStyle(
                    color = t.textPrimary,
                    fontSize = if (compactSelector) 12.5.sp else 14.sp,
                    lineHeight = if (compactSelector) 16.sp else 18.sp,
                    fontWeight = FontWeight.Bold,
                ),
                maxLines = if (nameOverflow == "wrap") 2 else 1,
                softWrap = nameOverflow == "wrap",
                overflow = TextOverflow.Clip,
                autoSize = if (nameOverflow == "wrap") null else TextAutoSize.StepBased(
                    minFontSize = 10.sp,
                    maxFontSize = if (compactSelector) 12.5.sp else 14.sp,
                    stepSize = .5.sp,
                ),
            )
            Text(
                if (node.udp) "UDP" else "TCP",
                color = t.textSecondary,
                fontSize = 10.5.sp,
                lineHeight = 14.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    protocol,
                    Modifier.weight(1f).testTag("node-protocol:${node.name}"),
                    color = t.textSecondary,
                    fontSize = 10.5.sp,
                    lineHeight = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Clip,
                )
                ReferenceDelayPill(value, testing)
            }
        }

        Box(
            Modifier
                .align(Alignment.BottomEnd)
                .size(48.dp)
                .testTag("node-delay:${node.name}")
                .clickable(
                    enabled = !testing,
                    role = Role.Button,
                    onClickLabel = "测速",
                    onClick = onDelay,
                ),
        )
    }
}

@Composable
internal fun LiquidGroupWell(
    group: ProxyGroupUi,
    selected: String,
    delays: Map<String, Long>,
    testing: Map<String, Boolean>,
    onSelect: (String) -> Unit,
    onDelay: (String) -> Unit,
    onTestAll: () -> Unit,
) {
    val motion = LocalHetuMotionEnabled.current
    val context = LocalContext.current
    val selectorPrefs = remember(context) { context.getSharedPreferences("hetu", 0) }
    val configuredNodeColumns = selectorPrefs.getInt("proxySelectorNodeColumns", 0).coerceIn(0, 3)
    val compactSelector = selectorPrefs.getString("proxySelectorDensity", "standard") == "compact"
    val nodeHeight = if (compactSelector) 66.dp else 78.dp
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .padding(top = 4.dp, bottom = 8.dp)
            .testTag("sunken-well:${group.name}")
            .then(
                if (motion) {
                    Modifier.animateContentSize(
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioLowBouncy,
                            stiffness = Spring.StiffnessLow,
                        ),
                    )
                } else Modifier
            ),
    ) {
        val columns = if (configuredNodeColumns == 0) liquidColumns(maxWidth) else configuredNodeColumns
        val horizontalGap = 10.dp
        val verticalGap = 8.dp
        val cellWidth = (maxWidth - horizontalGap * (columns - 1).toFloat()) / columns.toFloat()
        val selectedIndex = group.nodes.indexOfFirst { it.name == selected }
        if (selectedIndex >= 0) {
            val column = selectedIndex % columns
            val row = selectedIndex / columns
            val targetX = (cellWidth + horizontalGap) * column.toFloat()
            val targetY = (nodeHeight + verticalGap) * row.toFloat()
            val indicatorX by animateDpAsState(
                targetValue = targetX,
                animationSpec = if (motion) spring(
                    dampingRatio = HetuMotionSpec.SelectionDamping,
                    stiffness = HetuMotionSpec.SelectionStiffness,
                ) else tween(0),
                label = "nodeSelectionX",
            )
            val indicatorY by animateDpAsState(
                targetValue = targetY,
                animationSpec = if (motion) spring(
                    dampingRatio = HetuMotionSpec.SelectionDamping,
                    stiffness = HetuMotionSpec.SelectionStiffness,
                ) else tween(0),
                label = "nodeSelectionY",
            )
            LiquidSelectionIndicator(
                Modifier
                    .offset(x = indicatorX, y = indicatorY)
                    .width(cellWidth)
                    .height(nodeHeight)
                    .testTag("node-selection-indicator:" + group.name),
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(verticalGap)) {
            group.nodes.withIndex().toList().chunked(columns).forEach { row ->
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    row.forEach { (index, node) ->
                        LiquidNodeCard(
                            node = node,
                            active = node.name == selected,
                            value = delays[node.name] ?: node.lastDelay,
                            testing = testing[node.name] == true,
                            modifier = Modifier.weight(1f),
                            onSelect = { onSelect(node.name) },
                            onDelay = { onDelay(node.name) },
                            index = index,
                        )
                    }
                    if (row.size < columns) Spacer(Modifier.weight(1f))
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
        else if (primary) Color(0xFFEFF6FF) else Color(0xFFF8FAFC)
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
                if (danger) Color(0xFFFFE4E6) else if (dark) Color.White.copy(alpha = .08f) else Color(0xFFF1F5F9),
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
                if (running && !busy) Color.Transparent else Color(0xFFE2E8F0),
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
