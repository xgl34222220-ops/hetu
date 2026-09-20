package io.github.xgl34222220.hetu

import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import dev.chrisbanes.haze.HazeState
import io.github.xgl34222220.hetu.ui.*

/** Anchored popup semantics (outside/back dismissal, focus), not a white Material menu. */
@Composable
internal fun CrystalHomeMenu(onLog: () -> Unit, onConnections: () -> Unit, onDiagnostics: () -> Unit,
    onAdblock: () -> Unit, diagnosticLoading: Boolean, hazeState: HazeState? = null, glassEnabled: Boolean = false) {
    var open by remember { mutableStateOf(false) }
    val density = LocalDensity.current
    val gap = with(density) { 4.dp.roundToPx() }
    val margin = with(density) { 8.dp.roundToPx() }
    val position = remember(gap, margin) {
        object : PopupPositionProvider {
            override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize,
                layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
                val preferred = if (layoutDirection == LayoutDirection.Ltr) anchorBounds.right - popupContentSize.width + margin else anchorBounds.left - margin
                val x = preferred.coerceIn(margin, (windowSize.width-popupContentSize.width-margin).coerceAtLeast(margin))
                val below = anchorBounds.bottom + gap
                val y = if (below + popupContentSize.height <= windowSize.height-margin) below
                    else (anchorBounds.top-popupContentSize.height-gap).coerceAtLeast(margin)
                return IntOffset(x,y)
            }
        }
    }
    Box {
        IconButton(onClick = { open = true }, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Rounded.MoreHoriz, "更多工具", tint = LocalHetuTokens.current.textSecondary)
        }
        if (open) Popup(popupPositionProvider = position, onDismissRequest = { open = false },
            properties = PopupProperties(focusable = true, dismissOnBackPress = true, dismissOnClickOutside = true)) {
            var appeared by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) { appeared = true }
            val progress by animateFloatAsState(if(appeared) 1f else 0f,
                tween(if(LocalHetuMotionEnabled.current) 160 else 0), label = "popoverReveal")
            Box(Modifier.padding(12.dp).graphicsLayer {
                alpha = progress; scaleX = .96f + .04f*progress; scaleY = scaleX
                transformOrigin = androidx.compose.ui.graphics.TransformOrigin(1f,0f)
            }) {
                CrystalMenuContent(
                    onLog = { open = false; onLog() }, onConnections = { open = false; onConnections() },
                    onDiagnostics = { open = false; onDiagnostics() }, onAdblock = { open = false; onAdblock() },
                    diagnosticLoading = diagnosticLoading, backdrop = hazeState, blurEnabled = glassEnabled)
            }
        }
    }
}

@Composable
internal fun CrystalMenuContent(onLog: () -> Unit, onConnections: () -> Unit, onDiagnostics: () -> Unit,
    onAdblock: () -> Unit, diagnosticLoading: Boolean, modifier: Modifier = Modifier,
    backdrop: HazeState? = null, blurEnabled: Boolean = false) {
    val maxWidth = (LocalConfiguration.current.screenWidthDp.dp - 40.dp).coerceAtLeast(160.dp)
    Column(modifier.widthIn(max = maxWidth).width(208.dp)
        .heightIn(max = (LocalConfiguration.current.screenHeightDp.dp-100.dp).coerceAtLeast(144.dp))
        .crystalMaterial(RoundedCornerShape(20.dp), depth = CrystalDepth.Popover, backdrop = backdrop, blurEnabled = blurEnabled)
        .semantics { paneTitle = "更多工具" }.testTag("crystal-popover")
        .verticalScroll(rememberScrollState()).padding(6.dp)) {
        CrystalMenuItem("运行日志", Icons.Rounded.Article, true, onLog)
        CrystalMenuItem("应用连接", Icons.Rounded.Apps, true, onConnections)
        CrystalMenuItem("广告过滤", Icons.Rounded.Shield, true, onAdblock)
        CrystalMenuItem("网络诊断", Icons.Rounded.Troubleshoot, !diagnosticLoading, onDiagnostics)
    }
}

@Composable
private fun CrystalMenuItem(label: String, icon: ImageVector, enabled: Boolean, onClick: () -> Unit) {
    val t = LocalHetuTokens.current
    val interactions = remember { MutableInteractionSource() }
    val pressed by interactions.collectIsPressedAsState()
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(13.dp))
        .background(if(pressed) MaterialTheme.colorScheme.primary.copy(alpha = .07f) else Color.Transparent)
        .clickable(enabled=enabled,interactionSource=interactions,indication=null,role=Role.Button,onClick=onClick)
        .padding(horizontal=12.dp,vertical=10.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(icon,null,Modifier.size(19.dp),tint=MaterialTheme.colorScheme.primary.copy(alpha=if(enabled) .9f else .35f))
        Text(label,color=t.textPrimary.copy(alpha=if(enabled) 1f else .4f),fontSize=13.sp,lineHeight=18.sp,fontWeight=FontWeight.Medium)
    }
}
