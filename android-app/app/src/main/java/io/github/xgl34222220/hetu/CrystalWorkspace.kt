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
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInWindow
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
import dev.chrisbanes.haze.HazeState
import io.github.xgl34222220.hetu.ui.*

/** Activity-hosted glass menu. Background capture and menu share the same window. */
@Composable
internal fun CrystalHomeMenu(onLog: () -> Unit, onConnections: () -> Unit, onDiagnostics: () -> Unit,
    onAdblock: () -> Unit, diagnosticLoading: Boolean, hazeState: HazeState? = null, glassEnabled: Boolean = false) {
    val host = LocalCrystalPopover.current
    val owner = remember { Any() }
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    var anchor by remember { mutableStateOf(androidx.compose.ui.geometry.Rect.Zero) }
    val logAction by rememberUpdatedState(onLog)
    val connectionsAction by rememberUpdatedState(onConnections)
    val diagnosticsAction by rememberUpdatedState(onDiagnostics)
    val adblockAction by rememberUpdatedState(onAdblock)
    val loading by rememberUpdatedState(diagnosticLoading)
    DisposableEffect(host, owner) { onDispose { host?.dismiss(owner, restoreFocus = false) } }
    Box {
        IconButton(onClick = {
            host?.show(owner, anchor, { runCatching { focus.requestFocus() } }) { dismiss ->
                var appeared by remember { mutableStateOf(false) }
                LaunchedEffect(Unit) { appeared = true }
                val progress by animateFloatAsState(if (appeared) 1f else 0f,
                    tween(if (LocalHetuMotionEnabled.current) 180 else 0), label = "popoverReveal")
                CrystalMenuContent(
                    onLog = { dismiss(); logAction() }, onConnections = { dismiss(); connectionsAction() },
                    onDiagnostics = { dismiss(); diagnosticsAction() }, onAdblock = { dismiss(); adblockAction() },
                    diagnosticLoading = loading, backdrop = hazeState, blurEnabled = glassEnabled,
                    modifier = Modifier.graphicsLayer {
                        alpha = progress; scaleX = .97f + .03f * progress; scaleY = scaleX
                        transformOrigin = androidx.compose.ui.graphics.TransformOrigin(1f, 0f)
                    })
            }
        }, modifier = Modifier.size(48.dp).focusRequester(focus).onGloballyPositioned {
            anchor = it.boundsInWindow(); host?.move(owner, anchor)
        }) {
            Icon(Icons.Rounded.MoreHoriz, "更多工具", tint = LocalHetuTokens.current.textSecondary)
        }
    }
}

@Composable
internal fun CrystalMenuContent(onLog: () -> Unit, onConnections: () -> Unit, onDiagnostics: () -> Unit,
    onAdblock: () -> Unit, diagnosticLoading: Boolean, modifier: Modifier = Modifier,
    backdrop: HazeState? = null, blurEnabled: Boolean = false) {
    val maxWidth = (LocalConfiguration.current.screenWidthDp.dp - 40.dp).coerceAtLeast(160.dp)
    Column(modifier.widthIn(max = maxWidth).width(150.dp)
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
        .padding(horizontal=14.dp,vertical=10.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(icon,null,Modifier.size(16.dp),tint=HetuMicroCrystal.TextMuted.copy(alpha=if(enabled) 1f else .35f))
        Text(label,color=HetuMicroCrystal.TextMain.copy(alpha=if(enabled) 1f else .4f),fontSize=13.sp,lineHeight=18.sp,fontWeight=FontWeight.Bold)
    }
}
