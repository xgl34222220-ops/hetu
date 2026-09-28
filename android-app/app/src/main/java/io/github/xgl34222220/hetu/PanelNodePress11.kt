package io.github.xgl34222220.hetu

import androidx.compose.animation.core.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import io.github.xgl34222220.hetu.ui.LocalHetuMotionEnabled

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun Modifier.panelNodePress11(enabled: Boolean, onClick: () -> Unit, onLongClick: () -> Unit): Modifier {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val motion = LocalHetuMotionEnabled.current
    val scale by animateFloatAsState(if (pressed && enabled && motion) .97f else 1f,
        if (!motion) snap() else if (pressed) tween(100) else spring(dampingRatio = .78f, stiffness = 600f), label = "panel-node-press")
    return graphicsLayer { scaleX = scale; scaleY = scale }.combinedClickable(
        interactionSource = interaction, indication = null, enabled = enabled,
        role = Role.RadioButton, onClickLabel = "选择节点", onClick = onClick,
        onLongClickLabel = "查看完整节点名称与协议", onLongClick = onLongClick)
}
