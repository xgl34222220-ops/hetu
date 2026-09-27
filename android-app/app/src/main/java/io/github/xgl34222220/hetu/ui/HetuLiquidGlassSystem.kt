package io.github.xgl34222220.hetu.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.CheckCircleOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Shared geometry for the Liquid Glass system. Keep page code free of ad-hoc radii. */
object HetuGlassRadius {
    val Hero = 20.dp
    val Card = 16.dp
    val Tile = 12.dp
    val Input = 12.dp
    val Sheet = 22.dp
    val Pill = 999.dp
}

object HetuBottomBarMetrics {
    val FloatingHorizontal = HetuPageMetrics.Gutter
    val FloatingBottom = 12.dp
    val ContentGap = 16.dp
}

object HetuMotionSpec {
    const val PressedScale = .972f
    const val PressDurationMs = 95
    const val SelectionDamping = .68f
    const val SelectionStiffness = 360f
}

/**
 * Compact top-level runtime island. It replaces the old 120dp status block while
 * preserving the existing runtime/config information and a full-size toggle target.
 */
@Composable
fun LiquidStatusCapsule(
    running: Boolean,
    busy: Boolean,
    status: String,
    core: String,
    mode: String,
    config: String,
    uptime: String,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    embedded: Boolean = false,
) {
    val t = LocalHetuTokens.current
    val accent = MaterialTheme.colorScheme.primary
    top.yukonga.miuix.kmp.basic.Card(
        modifier = modifier.fillMaxWidth(), cornerRadius = HetuPageMetrics.CardRadius,
        insideMargin = PaddingValues(16.dp),
        colors = top.yukonga.miuix.kmp.basic.CardDefaults.defaultColors(t.cardBackground, t.textPrimary),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    Box(Modifier.size(7.dp).background(if (running) accent else t.textMuted, CircleShape))
                    Text(status, Modifier.testTag("home-run-state"), color = t.textPrimary,
                        fontSize = 20.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold)
                }
                Text("$core · $mode · $uptime", color = t.textSecondary, fontSize = 12.sp, lineHeight = 18.sp,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            LiquidConnectionToggle(running, busy, onToggle)
        }
        Spacer(Modifier.height(14.dp))
        Text(config.ifBlank { "未选择配置" }, color = t.textPrimary, fontSize = 14.sp, lineHeight = 21.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun LiquidConnectionToggle(checked: Boolean, busy: Boolean, onClick: () -> Unit) {
    val t = LocalHetuTokens.current
    Row(Modifier.heightIn(min = 48.dp).crystalMaterial(CircleShape, depth = CrystalDepth.Sunken, selection = checked)
        .clickable(enabled = !busy, role = Role.Button, onClick = onClick)
        .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (busy) HetuBusyIndicator(Modifier.size(18.dp))
        else Icon(Icons.Rounded.PowerSettingsNew, if (checked) "断开代理" else "连接代理", Modifier.size(18.dp),
            tint = if (checked) MaterialTheme.colorScheme.primary else t.textSecondary)
        Text(if (busy) "处理中" else if (checked) "断开" else "连接", fontSize = 12.sp, lineHeight = 18.sp,
            color = if (checked) MaterialTheme.colorScheme.primary else t.textSecondary)
    }
}

@Composable
fun SegmentedLiquidActionPill(running: Boolean, busy: Boolean, onReload: () -> Unit,
    onToggle: () -> Unit, onRestart: () -> Unit, modifier: Modifier = Modifier, embedded: Boolean = false) {
    val t = LocalHetuTokens.current
    val labels = listOf("重载", if (busy) "请稍候" else if (running) "停止" else "启动", "重启")
    val enabled = listOf(running && !busy, !busy, running && !busy)
    val callbacks = listOf(onReload, onToggle, onRestart)
    Row(modifier.fillMaxWidth().clip(CircleShape).background(t.cardBackground)
        .height(IntrinsicSize.Min), verticalAlignment = Alignment.CenterVertically) {
        labels.forEachIndexed { index, label ->
            if (index > 0) Box(Modifier.width(.5.dp).height(22.dp).background(t.outline))
            Box(Modifier.weight(1f).heightIn(min = 52.dp)
                .miuixTap(enabled = enabled[index], onClick = callbacks[index])
                .padding(horizontal = 4.dp, vertical = 12.dp), contentAlignment = Alignment.Center) {
                Text(label, color = when {
                    !enabled[index] -> t.textMuted
                    index == 1 && running -> t.danger
                    index == 2 -> t.warning
                    else -> MaterialTheme.colorScheme.primary
                }, fontSize = 14.sp, lineHeight = 21.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

/**
 * Shared moving selection lens. Parent layouts animate its offset so the same
 * highlight appears to flow between destinations instead of blinking per-card.
 */
@Composable
fun LiquidSelectionIndicator(
    modifier: Modifier = Modifier,
) {
    Box(modifier.crystalMaterial(RoundedCornerShape(12.dp), depth = CrystalDepth.InsetItem, selection = true))
}

/** Shared micro-crystal tile used by WAN, speed, subscription and resource metrics. */
@Composable
fun GlassMetricTile(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val motion = LocalHetuMotionEnabled.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) HetuMotionSpec.PressedScale else 1f,
        animationSpec = tween(if (motion) HetuMotionSpec.PressDurationMs else 0),
        label = "metricPress",
    )
    val shape = RoundedCornerShape(HetuGlassRadius.Tile)
    Column(
        modifier
            .heightIn(min = 102.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .crystalMaterial(shape, depth = CrystalDepth.Card)
            .then(
                if (onClick != null) Modifier.clickable(
                    interactionSource = interaction,
                    indication = null,
                    role = Role.Button,
                    onClick = onClick,
                ) else Modifier
            )
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        content = content,
    )
}

/** Input-well material shared by API/config editors introduced in Phase 3. */
@Composable
fun Modifier.glassInputWell(): Modifier =
    crystalMaterial(
        RoundedCornerShape(HetuGlassRadius.Input),
        depth = CrystalDepth.Sunken,
    )


/** Material text input with the outline/container stripped back to an inset glass well. */
@Composable
fun LiquidGlassTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    supportingText: String = "",
    singleLine: Boolean = true,
    enabled: Boolean = true,
    leadingIcon: ImageVector? = null,
    textStyle: TextStyle = LocalTextStyle.current,
) {
    @Composable
    fun Input(fieldModifier: Modifier) {
        top.yukonga.miuix.kmp.basic.TextField(
            value = value, onValueChange = onValueChange,
            modifier = fieldModifier, label = label.ifBlank { placeholder },
            singleLine = singleLine, enabled = enabled, textStyle = textStyle,
            cornerRadius = HetuGlassRadius.Input,
            insideMargin = androidx.compose.ui.unit.DpSize(12.dp, 10.dp),
            leadingIcon = leadingIcon?.let { icon -> { Icon(icon, null, Modifier.size(18.dp)) } },
        )
    }
    if (supportingText.isBlank()) Input(modifier)
    else Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Input(Modifier.fillMaxWidth())
        Text(supportingText, color = LocalHetuTokens.current.textSecondary,
            fontSize = 11.sp, lineHeight = 16.sp, modifier = Modifier.padding(horizontal = 8.dp))
    }
}


/** Shared iOS-style grouped inset shell for all settings surfaces. */
@Composable
fun GroupedInsetSection(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    androidx.compose.material3.Surface(
        modifier = modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
        color = LocalHetuTokens.current.cardBackground, shadowElevation = 2.dp,
    ) { Column(content = content) }
}

/** Compact liquid switch used instead of the default Material tonal switch. */
@Composable
fun LiquidSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    top.yukonga.miuix.kmp.basic.Switch(
        checked = checked, onCheckedChange = onCheckedChange,
        modifier = modifier, enabled = enabled,
    )
}


/** Shared material wrapper for top-rounded modal/bottom-sheet content. */
@Composable
fun Modifier.liquidSheetMaterial(): Modifier =
    crystalMaterial(
        RoundedCornerShape(
            topStart = HetuGlassRadius.Sheet,
            topEnd = HetuGlassRadius.Sheet,
        ),
        depth = CrystalDepth.Popover,
    )


/** Small liquid choice used for filters, modes and compact segmented decisions. */
@Composable
fun LiquidChoicePill(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val t = LocalHetuTokens.current
    val motion = LocalHetuMotionEnabled.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) HetuMotionSpec.PressedScale else 1f,
        animationSpec = tween(if (motion) HetuMotionSpec.PressDurationMs else 0),
        label = "choicePress",
    )
    val shape = RoundedCornerShape(HetuGlassRadius.Pill)
    Box(
        modifier
            .heightIn(min = 44.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .crystalMaterial(
                shape = shape,
                depth = CrystalDepth.InsetItem,
                selection = selected,
            )
            .clickable(
                enabled = enabled,
                interactionSource = interaction,
                indication = null,
                role = Role.RadioButton,
                onClick = onClick,
            )
            .padding(horizontal = 13.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = when {
                !enabled -> t.textMuted.copy(alpha = .48f)
                selected -> MaterialTheme.colorScheme.primary
                else -> t.textPrimary
            },
            fontSize = 12.sp,
            lineHeight = 16.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}
