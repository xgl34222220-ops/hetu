package io.github.xgl34222220.hetu.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
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
            .crystalMaterial(shape, depth = CrystalDepth.Card)
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

        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    status,
                    Modifier.testTag("home-run-state"),
                    color = if (running) t.success else t.textPrimary,
                    fontSize = 16.sp,
                    lineHeight = 20.sp,
                    fontWeight = FontWeight.ExtraBold,
                    maxLines = 1,
                )
                Spacer(Modifier.width(7.dp))
                Text(
                    "$core · $mode",
                    color = t.textSecondary,
                    fontSize = 11.5.sp,
                    lineHeight = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                "$uptime · $config",
                color = t.textSecondary,
                fontSize = 11.5.sp,
                lineHeight = 16.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
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
private fun LiquidConnectionToggle(
    checked: Boolean,
    busy: Boolean,
    onClick: () -> Unit,
) {
    val t = LocalHetuTokens.current
    val motion = LocalHetuMotionEnabled.current
    val shape = RoundedCornerShape(HetuGlassRadius.Pill)
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) HetuMotionSpec.PressedScale else 1f,
        animationSpec = tween(if (motion) HetuMotionSpec.PressDurationMs else 0),
        label = "connectPress",
    )

    BoxWithConstraints(
        Modifier
            .width(88.dp)
            .height(40.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .crystalMaterial(shape, depth = CrystalDepth.Sunken, selection = checked)
            .clickable(
                enabled = !busy,
                interactionSource = interaction,
                indication = null,
                role = Role.Button,
                onClick = onClick,
            ),
    ) {
        val thumbWidth = 34.dp
        val target = if (checked) maxWidth - thumbWidth - 4.dp else 4.dp
        val x by animateDpAsState(
            targetValue = target,
            animationSpec = spring(
                dampingRatio = if (motion) .70f else 1f,
                stiffness = if (motion) 420f else 10_000f,
            ),
            label = "connectThumb",
        )
        Text(
            if (busy) "…" else if (checked) "断开" else "连接",
            modifier = Modifier.align(Alignment.Center),
            color = if (checked) MaterialTheme.colorScheme.primary else t.textSecondary,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
        )
        Box(
            Modifier
                .offset(x = x)
                .align(Alignment.CenterStart)
                .size(34.dp)
                .crystalMaterial(RoundedCornerShape(17.dp), depth = CrystalDepth.Popover, selection = checked),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Rounded.PowerSettingsNew,
                contentDescription = if (checked) "断开代理" else "连接代理",
                tint = if (checked) MaterialTheme.colorScheme.primary else t.textSecondary,
                modifier = Modifier.size(17.dp),
            )
        }
    }
}

/**
 * Three-command liquid rail. The highlight remains inside one physical shell and
 * springs to the last invoked command instead of rendering three unrelated cards.
 */
@Composable
fun SegmentedLiquidActionPill(
    running: Boolean,
    busy: Boolean,
    onReload: () -> Unit,
    onToggle: () -> Unit,
    onRestart: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val t = LocalHetuTokens.current
    val motion = LocalHetuMotionEnabled.current
    var selected by remember { mutableIntStateOf(1) }
    val labels = listOf("重载", if (busy) "请稍候" else if (running) "停止" else "启动", "重启")
    val enabled = listOf(running && !busy, !busy, running && !busy)
    val callbacks = listOf(onReload, onToggle, onRestart)
    val shape = RoundedCornerShape(HetuGlassRadius.Pill)

    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(54.dp)
            .crystalMaterial(shape, depth = CrystalDepth.Sunken),
    ) {
        val segmentWidth = maxWidth / 3
        val targetX = segmentWidth * selected.toFloat()
        val indicatorX by animateDpAsState(
            targetValue = targetX,
            animationSpec = spring(
                dampingRatio = if (motion) HetuMotionSpec.SelectionDamping else 1f,
                stiffness = if (motion) HetuMotionSpec.SelectionStiffness else 10_000f,
            ),
            label = "actionIndicator",
        )
        Box(
            Modifier
                .offset(x = indicatorX)
                .padding(5.dp)
                .width(segmentWidth - 10.dp)
                .fillMaxHeight()
                .crystalMaterial(
                    RoundedCornerShape(22.dp),
                    depth = CrystalDepth.Card,
                    selection = true,
                ),
        )

        Row(Modifier.fillMaxSize()) {
            labels.forEachIndexed { index, label ->
                val source = remember(index) { MutableInteractionSource() }
                val pressed by source.collectIsPressedAsState()
                val scale by animateFloatAsState(
                    targetValue = if (pressed) HetuMotionSpec.PressedScale else 1f,
                    animationSpec = tween(if (motion) HetuMotionSpec.PressDurationMs else 0),
                    label = "actionPress$index",
                )
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .graphicsLayer { scaleX = scale; scaleY = scale }
                        .semantics { role = Role.Button }
                        .clickable(
                            enabled = enabled[index],
                            interactionSource = source,
                            indication = null,
                        ) {
                            selected = index
                            callbacks[index]()
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label,
                        color = when {
                            !enabled[index] -> t.textMuted.copy(alpha = .48f)
                            index == 1 && running -> t.danger
                            selected == index -> MaterialTheme.colorScheme.primary
                            else -> t.textPrimary
                        },
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                    )
                }
            }
        }
    }
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
