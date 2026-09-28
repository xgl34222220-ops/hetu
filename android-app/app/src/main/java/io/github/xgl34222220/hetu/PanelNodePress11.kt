package io.github.xgl34222220.hetu

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import io.github.xgl34222220.hetu.ui.HetuHaptic
import io.github.xgl34222220.hetu.ui.hetuPressScale
import io.github.xgl34222220.hetu.ui.rememberHetuHaptics

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun Modifier.panelNodePress11(enabled: Boolean, onClick: () -> Unit, onLongClick: () -> Unit): Modifier {
    val interaction = remember { MutableInteractionSource() }
    val haptics = rememberHetuHaptics()
    // V18.3: shared press dip; long press confirms with a distinct haptic before the detail opens.
    return hetuPressScale(interaction, enabled, pressedScale = .97f).combinedClickable(
        interactionSource = interaction, indication = null, enabled = enabled,
        role = Role.RadioButton, onClickLabel = "选择节点", onClick = onClick,
        onLongClickLabel = "查看完整节点名称与协议", onLongClick = {
            haptics.perform(HetuHaptic.LongPress)
            onLongClick()
        })
}
