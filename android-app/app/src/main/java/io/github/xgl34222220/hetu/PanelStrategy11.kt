package io.github.xgl34222220.hetu

import android.content.SharedPreferences
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.xgl34222220.hetu.ui.*
import kotlinx.coroutines.*
import java.util.Locale

/** Production adapter. All network mutations use the original146 repository, never Vue demo data. */
@Composable
internal fun PanelStrategyRoute11(state: ProxyComposeState, repo: ProxyDashboardRepository,
    delays: MutableMap<String, Long>, searchRequest: Int,
    onTab: (RefPanelTab) -> Unit, onRefresh: suspend () -> Unit,
    onDetailVisible: (Boolean) -> Unit) {
    val context = LocalContext.current
    val prefs = remember(context) { context.getSharedPreferences("hetu", 0) }
    StrategyPanel11(state, delays, prefs, searchRequest, onTab, onRefresh,
        select = { group, node ->
            repo.select(group, node, prefs.getBoolean("proxySelectorDisconnectOnSelect", false))
            val actual = repo.state().groups.firstOrNull { it.name == group }?.now.orEmpty()
            onRefresh()
            actual
        }, measure = { node -> repo.delay(node) }, onDetailVisible = onDetailVisible)
}

/** All UI and real callbacks are injectable so touch, failures and delayed acknowledgements are testable. */
@Composable
internal fun StrategyPanel11(
    state: ProxyComposeState, delays: MutableMap<String, Long>, prefs: SharedPreferences,
    searchRequest: Int = 0, onTab: (RefPanelTab) -> Unit,
    refresh: suspend () -> Unit, select: suspend (String, String) -> String,
    measure: suspend (String) -> Long, onDetailVisible: (Boolean) -> Unit = {},
) {
    BoxProxyExactStrategy17(state, delays, prefs, searchRequest, onTab, refresh, select, measure, onDetailVisible)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun PanelNode11(group: String, node: ProxyNodeUi, active: Boolean, delay: Long?, testing: Boolean,
    pending: Boolean, enabled: Boolean, compact: Boolean, modifier: Modifier,
    onSelect: () -> Unit, onDelay: () -> Unit, onName: () -> Unit, onNested: (() -> Unit)?) {
    val t = LocalHetuTokens.current
    val motion = LocalHetuMotionEnabled.current
    val label14 = remember(node.name) { nodeLabel14(node.name) }
    val fill by animateColorAsState(if (active) Color(0xFF2563EB).copy(alpha = .09f) else t.cardBackground,
        tween(if (motion) 200 else 0), label = "selected-node")
    // nativePress owns the main click; a separate info action exposes untruncated names.
    Column(modifier.diffuseCardShadow(HomeContinuousShape(18.dp)).clip(HomeContinuousShape(18.dp)).background(fill)
        .border(1.dp, if (active) Color(0xFF93C5FD).copy(alpha = .65f) else t.textMuted.copy(alpha = .07f), HomeContinuousShape(18.dp))
        .panelNodePress11(enabled = enabled, onClick = onSelect, onLongClick = onName)
        .semantics { selected = active; role = Role.RadioButton; stateDescription = if (pending) "等待核心确认" else if (active) "当前节点" else "未选择" }
        .testTag("panel11-node:$group:${node.name}").padding(start = 10.dp, end = 6.dp, top = if (compact) 7.dp else 11.dp, bottom = if (compact) 3.dp else 7.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            Text(label14.title, Modifier.weight(1f).testTag("panel11-node-name:$group:${node.name}"),
                color = t.textPrimary, fontSize = 14.sp, lineHeight = 20.sp,
                fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                maxLines = if (compact) 2 else 3, overflow = TextOverflow.Ellipsis)
            if (pending) NativeSpinner(Color(0xFF2563EB), motion, Modifier.size(16.dp))
            else if (active) Icon(Icons.Rounded.Check, "当前正在使用", Modifier.size(17.dp).testTag("panel11-check:$group:${node.name}"), tint = Color(0xFF2563EB))
        }
        NodeTags14(label14.tags, Modifier.testTag("panel14-node-tags:$group:${node.name}"))
        Text(listOf(node.type.uppercase(Locale.ROOT).ifBlank { "协议未知" }, if (node.udp) "UDP" else "")
            .filter(String::isNotBlank).joinToString(" · "), Modifier.padding(top = 4.dp)
                .testTag("panel11-protocol:$group:${node.name}"), color = t.textSecondary,
            fontSize = 10.sp, lineHeight = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.End) {
            if (onNested != null) Box(Modifier.size(48.dp).clip(CircleShape)
                .semantics { contentDescription = "展开子策略${node.name}" }
                .nativePress(label = "展开子策略${node.name}", onClick = onNested), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.ChevronRight, null, Modifier.size(17.dp), tint = t.textSecondary)
            }
            Box(Modifier.weight(1f).heightIn(min = 48.dp).clip(RoundedCornerShape(10.dp))
                .nativePress(enabled = enabled && !testing, label = "测试${node.name}延迟", onClick = onDelay)
                .testTag("panel11-delay:$group:${node.name}"), contentAlignment = Alignment.CenterEnd) {
                PanelDelayLabel11(delay, testing)
            }
        }
    }
}

@Composable
internal fun PanelDelayLabel11(value: Long?, loading: Boolean) {
    QuietDelay14(value, loading)
}
