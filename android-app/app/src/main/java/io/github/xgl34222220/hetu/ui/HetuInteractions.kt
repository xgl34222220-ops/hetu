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
    // V18.3: shared interaction-driven dip, so even a very quick tap is visible.
    return hetuPressScale(source, enabled, pressedScale = .975f)
        .clickable(source, indication = null, enabled = enabled, onClickLabel = onClickLabel,
            role = role, onClick = onClick)
}

/** Content-sized tabs: scrolling labels instead of fixed-width ellipsized capsules. */
@Composable
internal fun HetuFilterTabs(labels: List<String>, selected: Int, onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier) {
    val state = rememberLazyListState()
    val motion = LocalHetuMotionEnabled.current
    val haptics = rememberHetuHaptics()
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
            val fill by animateColorAsState(
                if (active) LocalHetuTokens.current.selectionBackground else LocalHetuTokens.current.controlBackground.copy(alpha = .55f),
                HetuMotion.fade(motion), label = "tabFill")
            Box(Modifier.hetuPressScale(source, pressedScale = .96f).heightIn(min = 48.dp)
                .clip(RoundedCornerShape(16.dp)).background(fill)
                .selectable(active, interactionSource = source, indication = null, role = Role.Tab,
                    onClick = { if (!active) { haptics.perform(HetuHaptic.Tick); onSelect(index) } })
                .padding(horizontal = 18.dp, vertical = 10.dp), contentAlignment = Alignment.Center) {
                Text(ht(label), fontFamily = HetuSystemFontFamily, fontSize = 14.sp, lineHeight = 20.sp,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                    color = if (active) MaterialTheme.colorScheme.primary else LocalHetuTokens.current.textSecondary,
                    maxLines = 1)
            }
        }
    }
}
