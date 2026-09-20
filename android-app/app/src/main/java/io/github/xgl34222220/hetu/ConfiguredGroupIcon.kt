package io.github.xgl34222220.hetu

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.github.xgl34222220.hetu.ui.*

/** The configured image always wins, including when the group name contains a flag. */
@Composable
internal fun ConfiguredGroupIcon(group: ProxyGroupUi, modifier: Modifier = Modifier.size(32.dp)) {
    val context = LocalContext.current
    val repository = remember(context) { ProxyGroupIconRepository.get(context) }
    val configured = group.iconUrl.isNotBlank() || group.iconPath.isNotBlank()
    val key = group.iconUrl.ifBlank { group.iconPath }
    val cached = remember(key) { repository.peek(key) }
    val loaded by produceState<GroupIconLoad>(cached?.let { GroupIconLoad.Ready(it, true) } ?: GroupIconLoad.Loading, key, group.iconPath) {
        value = cached?.let { GroupIconLoad.Ready(it, true) } ?: GroupIconLoad.Loading
        if (configured) value = repository.load(key, group.iconPath)
    }
    val ready = loaded as? GroupIconLoad.Ready
    var reveal by remember(key) { mutableStateOf(cached != null) }
    LaunchedEffect(ready) { if (ready != null) reveal = true }
    val alpha by animateFloatAsState(if (reveal) 1f else 0f,
        tween(if (LocalHetuMotionEnabled.current && ready?.cached != true) 150 else 0), label = "configuredIconFade")
    Box(modifier.testTag("group-icon-slot:${group.name}"), contentAlignment = Alignment.Center) {
        when {
            ready != null -> Image(ready.bitmap.asImageBitmap(), "${group.name} 配置图标",
                Modifier.fillMaxSize().graphicsLayer { this.alpha = alpha }.testTag("configured-icon:${group.name}"),
                contentScale = ContentScale.Fit)
            configured -> Box(Modifier.fillMaxSize().background(LocalHetuTokens.current.controlBackground, RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
                Icon(if (loaded is GroupIconLoad.Failed) Icons.Rounded.BrokenImage else Icons.Rounded.Image,
                    if (loaded is GroupIconLoad.Failed) "${group.name} 图标暂未加载" else "${group.name} 图标加载中",
                    Modifier.size(20.dp), tint = LocalHetuTokens.current.textSecondary)
            }
            else -> Icon(refScenarioIcon(group.name, group.type), "${group.name} 未配置图标", Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
        }
    }
}
