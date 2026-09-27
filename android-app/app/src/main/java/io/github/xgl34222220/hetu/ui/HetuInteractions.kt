package io.github.xgl34222220.hetu.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** No global gesture detector: clicks do not compete with the enclosing vertical list. */
@Composable
internal fun Modifier.hetuTap(enabled: Boolean = true, role: Role = Role.Button,
    onClickLabel: String? = null, onClick: () -> Unit): Modifier {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val motion = LocalHetuMotionEnabled.current
    val scale by animateFloatAsState(if (pressed && enabled && motion) .975f else 1f,
        if (!motion) snap() else if (pressed) tween(150, easing = androidx.compose.animation.core.CubicBezierEasing(.2f, 0f, 0f, 1f)) else spring(dampingRatio = .82f, stiffness = 650f), label = "hetuPress")
    return graphicsLayer { scaleX = scale; scaleY = scale }
        .clickable(source, indication = null, enabled = enabled, onClickLabel = onClickLabel,
            role = role, onClick = onClick)
}

/** Content-sized tabs: scrolling labels instead of fixed-width ellipsized capsules. */
@Composable
internal fun HetuFilterTabs(labels: List<String>, selected: Int, onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier) {
    val state = rememberLazyListState()
    val motion = LocalHetuMotionEnabled.current
    LaunchedEffect(selected, labels.size) {
        if (selected in labels.indices) {
            if (motion) state.animateScrollToItem(selected) else state.scrollToItem(selected)
        }
    }
    LazyRow(modifier.fillMaxWidth().selectableGroup().testTag("panel-filter-tabs"), state = state,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(vertical = 4.dp)) {
        itemsIndexed(labels, key = { index, label -> "$index:$label" }) { index, label ->
            val active = index == selected
            val source = remember { MutableInteractionSource() }
            val pressed by source.collectIsPressedAsState()
            val scale by animateFloatAsState(if (pressed) .96f else 1f,
                if (motion) spring(dampingRatio = .84f, stiffness = 650f) else snap(), label = "tabPress")
            val fill by animateColorAsState(
                if (active) LocalHetuTokens.current.selectionBackground else LocalHetuTokens.current.controlBackground.copy(alpha = .55f),
                tween(if (motion) 160 else 0), label = "tabFill")
            Box(Modifier.graphicsLayer { scaleX = scale; scaleY = scale }.heightIn(min = 48.dp)
                .clip(RoundedCornerShape(16.dp)).background(fill)
                .selectable(active, interactionSource = source, indication = null, role = Role.Tab,
                    onClick = { if (!active) onSelect(index) })
                .padding(horizontal = 18.dp, vertical = 10.dp), contentAlignment = Alignment.Center) {
                Text(ht(label), fontFamily = HetuSystemFontFamily, fontSize = 14.sp, lineHeight = 20.sp,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                    color = if (active) MaterialTheme.colorScheme.primary else LocalHetuTokens.current.textSecondary,
                    maxLines = 1)
            }
        }
    }
}
