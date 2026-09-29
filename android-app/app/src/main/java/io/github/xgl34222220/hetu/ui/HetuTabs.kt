package io.github.xgl34222220.hetu.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/**
 * V19 panel tab strip. Replaces the fixed-width Miuix TabRow whose 54–86dp cells
 * clipped every label to "…" under Hetu's density scaling.
 *
 * - Content-sized capsules in a horizontal scroller (labels are never truncated).
 * - One selection capsule slides between tabs. Its leading edge runs ahead of the
 *   trailing edge, so it stretches while travelling and settles back: a liquid move.
 * - The strip auto-scrolls to keep the selection centred.
 */
@Composable
internal fun HetuScrollTabs(
    labels: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(horizontal = 12.dp),
) {
    val motion = LocalHetuMotionEnabled.current
    val haptics = rememberHetuHaptics()
    val tokens = LocalHetuTokens.current
    val dark = MaterialTheme.colorScheme.background.luminance() < .5f
    val scroll = rememberScrollState()
    // x / width of every capsule in Row coordinates.
    val bounds = remember(labels) { mutableStateListOf<Pair<Float, Float>>().apply { repeat(labels.size) { add(0f to 0f) } } }
    val left = remember { Animatable(0f) }
    val right = remember { Animatable(0f) }
    var placed by remember { mutableStateOf(false) }
    val target = bounds.getOrNull(selected.coerceIn(0, (labels.size - 1).coerceAtLeast(0))) ?: (0f to 0f)
    var viewport by remember { mutableStateOf(0) }

    LaunchedEffect(target, motion) {
        if (target.second <= 0f) return@LaunchedEffect
        val targetLeft = target.first
        val targetRight = target.first + target.second
        if (!placed || !motion) {
            left.snapTo(targetLeft); right.snapTo(targetRight); placed = true
        } else {
            val forward = targetLeft > left.value
            // Leading edge: stiff. Trailing edge: soft. Produces the stretch-then-settle.
            launch { left.animateTo(targetLeft, spring(dampingRatio = .82f, stiffness = if (forward) 240f else 640f)) }
            launch { right.animateTo(targetRight, spring(dampingRatio = .82f, stiffness = if (forward) 640f else 240f)) }
        }
        if (viewport > 0) {
            val centre = (targetLeft + target.second / 2f - viewport / 2f).toInt().coerceIn(0, scroll.maxValue)
            if (motion) scroll.animateScrollTo(centre) else scroll.scrollTo(centre)
        }
    }

    val capsuleFill = if (dark) Color.White.copy(alpha = .12f) else Color.White
    val capsuleShadow = Color(0xFF3B2E7E).copy(alpha = if (dark) 0f else .07f)
    val outline = if (dark) Color.White.copy(alpha = .10f) else Color(0xFFD9D6E8)
    val capsuleHeight = 38.dp

    Box(
        modifier.fillMaxWidth()
            .onPlaced { viewport = it.size.width }
            .horizontalScroll(scroll)
            .testTag("boxproxy17-tabs"),
    ) {
        Row(
            Modifier.padding(contentPadding).height(48.dp).selectableGroup()
                .drawBehind {
                    if (placed && right.value > left.value) {
                        val h = capsuleHeight.toPx()
                        val w = right.value - left.value
                        val settledW = target.second.coerceAtLeast(1f)
                        // While stretched the capsule thins a little, like a drop under tension.
                        val stretch = ((w - settledW) / settledW).coerceIn(0f, 1f)
                        val drawH = h * (1f - .10f * stretch)
                        val top = (size.height - drawH) / 2f
                        val r = CornerRadius(drawH / 2f)
                        drawRoundRect(capsuleShadow, Offset(left.value, top + 2.dp.toPx()), Size(w, drawH), r)
                        drawRoundRect(capsuleFill, Offset(left.value, top), Size(w, drawH), r)
                    }
                },
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            labels.forEachIndexed { index, label ->
                val active = index == selected
                val source = remember { MutableInteractionSource() }
                val text by animateColorAsState(
                    if (active) tokens.textPrimary else tokens.textSecondary,
                    HetuMotion.fade(motion), label = "hetu-tab-text",
                )
                val border by animateColorAsState(
                    if (active) Color.Transparent else outline,
                    HetuMotion.fade(motion), label = "hetu-tab-border",
                )
                Box(
                    Modifier
                        .onPlaced { c -> if (index < bounds.size) bounds[index] = c.positionInParent().x to c.size.width.toFloat() }
                        .height(48.dp)
                        .hetuPressScale(source, pressedScale = .94f)
                        .selectable(active, interactionSource = source, indication = null, role = Role.Tab) {
                            if (!active) { haptics.perform(HetuHaptic.Tick); onSelect(index) }
                        }
                        .testTag("hetu-tab-$index"),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        Modifier.height(capsuleHeight)
                            .border(1.dp, border, RoundedCornerShape(50))
                            .padding(horizontal = 16.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            label, color = text, fontSize = 15.sp, lineHeight = 20.sp, maxLines = 1,
                            fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                        )
                    }
                }
            }
        }
    }
}
