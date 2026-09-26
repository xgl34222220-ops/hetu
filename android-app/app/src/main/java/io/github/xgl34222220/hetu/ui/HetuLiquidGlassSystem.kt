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
    val Hero = 28.dp
    val Card = 24.dp
    val Tile = 20.dp
    val Input = 20.dp
    val Sheet = 30.dp
    val Pill = 999.dp
}

object HetuBottomBarMetrics {
    val FloatingHorizontal = 20.dp
    val FloatingBottom = 12.dp
    val ContentGap = 20.dp
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
    val motion = LocalHetuMotionEnabled.current
    val shape = RoundedCornerShape(HetuGlassRadius.Hero)
    val pulse = if (motion && running && !busy) {
        val transition = rememberInfiniteTransition(label = "runtimePulse")
        transition.animateFloat(
            initialValue = .62f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(1350), RepeatMode.Reverse),
            label = "runtimePulseAlpha",
        ).value
    } else 1f

    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 78.dp)
            .then(if (embedded) Modifier else Modifier.crystalMaterial(shape, depth = CrystalDepth.Card))
            .padding(start = 16.dp, top = 12.dp, end = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(16.dp)
                .graphicsLayer {
                    alpha = pulse
                    scaleX = if (running) 1.08f else 1f
                    scaleY = if (running) 1.08f else 1f
                }
                .background(
                    if (running) t.success.copy(alpha = .16f) else Color.Transparent,
                    CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .size(8.dp)
                    .background(if (running) t.success else t.textMuted, CircleShape),
            )
        }

        Spacer(Modifier.width(10.dp))

        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(status, Modifier.testTag("home-run-state"), color = if (running) t.success else t.textPrimary,
                fontSize = 17.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold)
            Text("$core · $mode", color = t.textSecondary, fontSize = 12.sp, lineHeight = 18.sp)
            Text("$uptime · $config", color = t.textSecondary, fontSize = 12.sp, lineHeight = 18.sp)
        }

        Spacer(Modifier.width(10.dp))
        LiquidConnectionToggle(
            checked = running,
            busy = busy,
            onClick = onToggle,
        )
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
    Row(modifier.fillMaxWidth().then(if (embedded) Modifier else Modifier.crystalMaterial(RoundedCornerShape(HetuGlassRadius.Pill), depth = CrystalDepth.Sunken))
        .padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        labels.forEachIndexed { index, label ->
            val source = remember(index) { MutableInteractionSource() }
            val pressed by source.collectIsPressedAsState()
            Box(Modifier.weight(1f).heightIn(min = 48.dp)
                .background(if (pressed) t.controlBackground else Color.Transparent, CircleShape)
                .clickable(enabled = enabled[index], interactionSource = source, indication = null, role = Role.Button, onClick = callbacks[index])
                .padding(horizontal = 4.dp, vertical = 10.dp), contentAlignment = Alignment.Center) {
                Text(label, color = when {
                    !enabled[index] -> t.textMuted
                    index == 1 && running -> t.danger
                    index == 1 -> MaterialTheme.colorScheme.primary
                    else -> t.textPrimary
                }, fontSize = 13.sp, lineHeight = 19.sp, fontWeight = FontWeight.SemiBold)
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
    val primary = MaterialTheme.colorScheme.primary
    val shape = RoundedCornerShape(HetuGlassRadius.Tile)
    Box(
        modifier
            .graphicsLayer { alpha = .95f }
            .crystalMaterial(
                shape = shape,
                depth = CrystalDepth.Card,
                selection = true,
            )
            .background(primary.copy(alpha = .14f), shape)
            .border(.7.dp, primary.copy(alpha = .32f), shape),
    )
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
    val t = LocalHetuTokens.current
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.glassInputWell(),
        singleLine = singleLine,
        enabled = enabled,
        textStyle = textStyle,
        label = { Text(label) },
        placeholder = { if (placeholder.isNotBlank()) Text(placeholder) },
        supportingText = if (supportingText.isNotBlank()) { { Text(supportingText) } } else null,
        leadingIcon = if (leadingIcon == null) null else {
            { Icon(leadingIcon, null, modifier = Modifier.size(18.dp)) }
        },
        shape = RoundedCornerShape(HetuGlassRadius.Input),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = Color.Transparent,
            unfocusedContainerColor = Color.Transparent,
            disabledContainerColor = Color.Transparent,
            errorContainerColor = Color.Transparent,
            focusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = .34f),
            unfocusedBorderColor = Color.Transparent,
            disabledBorderColor = Color.Transparent,
            errorBorderColor = t.danger.copy(alpha = .55f),
        ),
    )
}


/** Shared iOS-style grouped inset shell for all settings surfaces. */
@Composable
fun GroupedInsetSection(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .fillMaxWidth()
            .crystalMaterial(
                RoundedCornerShape(HetuGlassRadius.Card),
                depth = CrystalDepth.Card,
            )
            .padding(horizontal = 14.dp),
        content = content,
    )
}

/** Compact liquid switch used instead of the default Material tonal switch. */
@Composable
fun LiquidSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val t = LocalHetuTokens.current
    val motion = LocalHetuMotionEnabled.current
    val shape = RoundedCornerShape(HetuGlassRadius.Pill)
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) HetuMotionSpec.PressedScale else 1f,
        animationSpec = tween(if (motion) HetuMotionSpec.PressDurationMs else 0),
        label = "liquidSwitchPress",
    )

    BoxWithConstraints(
        modifier
            .width(52.dp)
            .height(32.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .crystalMaterial(shape, depth = CrystalDepth.Sunken, selection = checked)
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                interactionSource = interaction,
                indication = null,
                onValueChange = onCheckedChange,
            ),
    ) {
        val thumb = 26.dp
        val targetX = if (checked) maxWidth - thumb - 3.dp else 3.dp
        val x by animateDpAsState(
            targetValue = targetX,
            animationSpec = spring(
                dampingRatio = if (motion) .72f else 1f,
                stiffness = if (motion) 480f else 10_000f,
            ),
            label = "liquidSwitchThumb",
        )
        Box(
            Modifier
                .offset(x = x)
                .align(Alignment.CenterStart)
                .size(thumb)
                .crystalMaterial(
                    RoundedCornerShape(13.dp),
                    depth = CrystalDepth.Popover,
                    selection = checked,
                ),
        )
        if (checked) {
            Box(
                Modifier
                    .matchParentSize()
                    .padding(3.dp)
                    .clip(shape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = .08f)),
            )
        }
    }
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
