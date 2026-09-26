package io.github.xgl34222220.hetu.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/** Pull, release, in-flight and completion feedback share the same glass surface. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HetuRefreshBox(isRefreshing: Boolean, onRefresh: () -> Unit, modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit) {
    val state = rememberPullToRefreshState()
    val view = LocalView.current
    var hadRefresh by remember { mutableStateOf(false) }
    var complete by remember { mutableStateOf(false) }
    val ready = state.distanceFraction >= 1f
    LaunchedEffect(ready) {
        if (ready && !isRefreshing) view.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
    }
    LaunchedEffect(isRefreshing) {
        if (isRefreshing) { hadRefresh = true; complete = false }
        else if (hadRefresh) { complete = true; delay(700); complete = false; hadRefresh = false }
    }
    PullToRefreshBox(isRefreshing = isRefreshing, onRefresh = { if (!isRefreshing) onRefresh() },
        state = state, modifier = modifier, indicator = {
            AnimatedVisibility(isRefreshing || complete || state.distanceFraction > .05f,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 8.dp), enter = fadeIn(), exit = fadeOut()) {
                Row(Modifier.crystalMaterial(CircleShape).heightIn(min = 44.dp).padding(horizontal = 16.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite }.testTag("refresh-feedback"),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (isRefreshing) HetuBusyIndicator(Modifier.size(16.dp))
                    else Icon(if (complete) Icons.Rounded.Check else Icons.Rounded.ArrowDownward, null, Modifier.size(16.dp))
                    Text(when { isRefreshing -> "正在刷新"; complete -> "刷新完成"; ready -> "松开刷新"; else -> "下拉刷新" }, fontSize = 12.sp)
                }
            }
        }, content = content)
}
