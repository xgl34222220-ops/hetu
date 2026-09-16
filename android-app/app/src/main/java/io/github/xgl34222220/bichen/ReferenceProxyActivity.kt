package io.github.xgl34222220.bichen

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.chrisbanes.haze.rememberHazeState
import io.github.xgl34222220.bichen.ui.BichenGlassDock
import io.github.xgl34222220.bichen.ui.BichenTheme
import io.github.xgl34222220.bichen.ui.DockItem
import io.github.xgl34222220.bichen.ui.LocalBichenTokens
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Proxy workspace rebuilt around the compact reference-video language:
 * flat warm canvas, pale coral selected surfaces, grouped rows and a small floating dock.
 */
@OptIn(ExperimentalMaterial3Api::class)
class ReferenceProxyActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { BichenTheme { RefProxyShell { finish() } } }
    }
}

private enum class RefProxyPage { Home, Panel, Tools, Settings }
private enum class RefPanelTab(val label: String) {
    Overview("策略"), Nodes("节点"), Subscriptions("订阅"), Connections("连接"), Rules("规则"), RuleSets("规则集")
}

@Composable
private fun RefProxyShell(onBack: () -> Unit) {
    val context = LocalContext.current
    val controller = remember { ProxyComposeController(context) }
    val repo = remember { ProxyDashboardRepository(context) }
    val inspector = remember { ProxyRuntimeInspector(context) }
    val scope = rememberCoroutineScope()
    val haze = rememberHazeState()

    var page by rememberSaveable { mutableStateOf(RefProxyPage.Home) }
    var state by remember { mutableStateOf(ProxyComposeState()) }
    var runtime by remember { mutableStateOf(ProxyRuntimeSnapshot()) }
    var providers by remember { mutableStateOf<List<DashboardProviderUi>>(emptyList()) }
    val delays = remember { mutableStateMapOf<String, Long>() }
    var upRate by remember { mutableLongStateOf(0L) }
    var downRate by remember { mutableLongStateOf(0L) }
    var lastUp by remember { mutableLongStateOf(0L) }
    var lastDown by remember { mutableLongStateOf(0L) }
    var lastAt by remember { mutableLongStateOf(0L) }
    var operation by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var testing by remember { mutableStateOf(false) }
    var logText by remember { mutableStateOf<String?>(null) }

    suspend fun refresh() {
        try {
            val next = repo.state()
            val now = SystemClock.elapsedRealtime()
            if (lastAt > 0L && now > lastAt && next.uploadTotal >= lastUp && next.downloadTotal >= lastDown) {
                val elapsed = now - lastAt
                upRate = ((next.uploadTotal - lastUp) * 1000L / elapsed).coerceAtLeast(0L)
                downRate = ((next.downloadTotal - lastDown) * 1000L / elapsed).coerceAtLeast(0L)
            }
            next.groups.flatMap { it.nodes }.forEach { node ->
                node.lastDelay?.takeIf { it > 0L }?.let { delays.putIfAbsent(node.name, it) }
            }
            runtime = if (next.running) runCatching { inspector.sample() }.getOrDefault(runtime) else ProxyRuntimeSnapshot()
            providers = if (next.running) runCatching { repo.providers() }.getOrDefault(providers) else emptyList()
            state = next
            message = next.message
            lastAt = now
            lastUp = next.uploadTotal
            lastDown = next.downloadTotal
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            message = error.message ?: "状态读取失败"
        }
    }

    fun toggle() {
        if (operation.isNotBlank()) return
        scope.launch {
            operation = if (state.running) "正在停止…" else "正在启动…"
            try {
                if (state.running) controller.stop { operation = it } else controller.start { operation = it }
                delay(250)
                refresh()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                message = error.message ?: "操作失败"
            } finally {
                operation = ""
            }
        }
    }

    fun reload() {
        if (!state.running || operation.isNotBlank()) return
        scope.launch {
            operation = "正在重载…"
            try {
                inspector.reloadConfig()
                message = "运行配置已重载"
                delay(180)
                refresh()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                message = error.message ?: "重载失败"
            } finally {
                operation = ""
            }
        }
    }

    fun restart() {
        if (!state.running || operation.isNotBlank()) return
        scope.launch {
            operation = "正在重启…"
            try {
                controller.stop { operation = it }
                delay(160)
                controller.start { operation = it }
                delay(250)
                refresh()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                message = error.message ?: "重启失败"
            } finally {
                operation = ""
            }
        }
    }

    fun quickDelay() {
        if (!state.running || testing) return
        scope.launch {
            testing = true
            try {
                val result = repo.quickDelay()
                delays.putAll(result)
                if (result.isEmpty()) message = "没有可测速的当前节点"
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                message = error.message ?: "测速失败"
            } finally {
                testing = false
            }
        }
    }

    LaunchedEffect(Unit) {
        runCatching { repo.ensureIcons() }
        refresh()
        while (true) {
            delay(2200)
            refresh()
        }
    }

    val dock = remember {
        listOf(
            DockItem("首页", Icons.Rounded.Home, .94f),
            DockItem("面板", Icons.Rounded.Link, .96f),
            DockItem("工具", Icons.Rounded.GridView, .96f),
            DockItem("设置", Icons.Rounded.Settings, .94f),
        )
    }

    Box(Modifier.fillMaxSize().background(LocalBichenTokens.current.pageBackground)) {
        Box(Modifier.fillMaxSize().padding(bottom = 88.dp)) {
            when (page) {
                RefProxyPage.Home -> RefHome(
                    state = state,
                    runtime = runtime,
                    providers = providers,
                    delays = delays,
                    upRate = upRate,
                    downRate = downRate,
                    operation = operation,
                    message = message,
                    testing = testing,
                    onBack = onBack,
                    onRefresh = { scope.launch { refresh() } },
                    onToggle = ::toggle,
                    onReload = ::reload,
                    onRestart = ::restart,
                    onDelay = ::quickDelay,
                    onWebUi = { context.startActivity(Intent(context, ProxyWebUiActivity::class.java)) },
                    onLog = { scope.launch { logText = runCatching { inspector.runtimeLog() }.getOrElse { it.message ?: "日志读取失败" } } },
                )
                RefProxyPage.Panel -> RefPanel(state, repo, delays) { scope.launch { refresh() } }
                RefProxyPage.Tools -> RefTools(state) { logText = it }
                RefProxyPage.Settings -> RefSettings(state)
            }
        }
        BichenGlassDock(
            items = dock,
            selected = page.ordinal,
            onSelect = { page = RefProxyPage.entries[it] },
            hazeState = haze,
            backdrop = null,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }

    logText?.let { text ->
        AlertDialog(
            onDismissRequest = { logText = null },
            shape = RoundedCornerShape(20.dp),
            title = { Text("运行日志") },
            text = { Text(text, style = MaterialTheme.typography.bodySmall, maxLines = 24, overflow = TextOverflow.Ellipsis) },
            confirmButton = { TextButton(onClick = { logText = null }) { Text("关闭") } },
        )
    }
}

@Composable
private fun RefHome(
    state: ProxyComposeState,
    runtime: ProxyRuntimeSnapshot,
    providers: List<DashboardProviderUi>,
    delays: Map<String, Long>,
    upRate: Long,
    downRate: Long,
    operation: String,
    message: String,
    testing: Boolean,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onToggle: () -> Unit,
    onReload: () -> Unit,
    onRestart: () -> Unit,
    onDelay: () -> Unit,
    onWebUi: () -> Unit,
    onLog: () -> Unit,
) {
    val t = LocalBichenTokens.current
    val scheme = MaterialTheme.colorScheme
    val selectedDelays = state.groups.mapNotNull { group ->
        val name = group.now
        (delays[name] ?: group.nodes.firstOrNull { it.name == name }?.lastDelay)?.takeIf { it > 0L }
    }
    val measured = delays.values.filter { it > 0L }
    val current = selectedDelays.firstOrNull()
    val avg = selectedDelays.takeIf { it.isNotEmpty() }?.average()?.toInt()?.toLong()
        ?: measured.takeIf { it.isNotEmpty() }?.average()?.toInt()?.toLong()
    val fastest = measured.minOrNull()
    val memory = runtime.rssBytes.takeIf { it > 0L } ?: state.memoryBytes

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().padding(top = 12.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("代理", color = t.textPrimary, fontSize = 26.sp, lineHeight = 32.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                IconButton(onClick = onRefresh, modifier = Modifier.size(42.dp)) {
                    Icon(Icons.Rounded.Refresh, "刷新", tint = t.textSecondary, modifier = Modifier.size(20.dp))
                }
                IconButton(onClick = onBack, modifier = Modifier.size(42.dp)) {
                    Icon(Icons.Rounded.Close, "关闭", tint = t.textSecondary, modifier = Modifier.size(20.dp))
                }
            }
        }
        item {
            Box(
                Modifier.fillMaxWidth()
                    .background(if (state.running) t.selectionBackground else t.cardBackground, RoundedCornerShape(18.dp))
                    .heightIn(min = 118.dp),
            ) {
                Column(
                    Modifier.fillMaxWidth().padding(15.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(Modifier.size(8.dp).background(if (state.running) scheme.primary else t.warning, CircleShape))
                        Text(if (state.running) "运行中" else "已停止", color = if (state.running) scheme.primary else t.textPrimary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    }
                    Text(if (state.running) refDuration(runtime.elapsedSeconds) else "等待启动", color = t.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    Text("${state.core} · ${state.mode}", color = t.textPrimary, style = MaterialTheme.typography.bodyMedium)
                    Text(state.config, color = t.textPrimary, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        item {
            Surface(shape = RoundedCornerShape(15.dp), color = t.cardBackground, shadowElevation = 0.dp) {
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    RefActionText("重载", state.running && operation.isBlank(), onReload, Modifier.weight(1f), scheme.primary)
                    VerticalDivider(Modifier.height(30.dp).align(Alignment.CenterVertically), color = t.outline)
                    RefActionText(if (state.running) "停止" else "启动", operation.isBlank(), onToggle, Modifier.weight(1f), scheme.error)
                    VerticalDivider(Modifier.height(30.dp).align(Alignment.CenterVertically), color = t.outline)
                    RefActionText("重启", state.running && operation.isBlank(), onRestart, Modifier.weight(1f), t.warning)
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RefSmallTool("WebUI", "Web 界面", Icons.Rounded.Language, onWebUi, Modifier.weight(1f))
                RefSmallTool("日志", "查看", Icons.Rounded.Article, onLog, Modifier.weight(1f))
            }
        }
        item { RefLatencyPanel(current, avg, fastest, testing, onDelay) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RefNetworkIdentityCard(runtime.lanAddress, state.connections.size, Modifier.weight(1f))
                RefSpeedCard(upRate, downRate, Modifier.weight(1f))
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RefSubscriptionCompact(providers, Modifier.weight(1f))
                RefResourceCard(memory, runtime.pid, state.connections.size, Modifier.weight(1f))
            }
        }
        if (operation.isNotBlank() || message.isNotBlank()) {
            item { Text(operation.ifBlank { message }, color = t.textSecondary, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)) }
        }
    }
}

@Composable
private fun RefLatencyPanel(current: Long?, average: Long?, fastest: Long?, testing: Boolean, onClick: () -> Unit) {
    val t = LocalBichenTokens.current
    Surface(onClick = onClick, enabled = !testing, shape = RoundedCornerShape(15.dp), color = t.cardBackground, shadowElevation = 0.dp) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("延迟", color = t.textPrimary, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Icon(Icons.Rounded.Tune, null, tint = t.textSecondary, modifier = Modifier.size(17.dp))
                Spacer(Modifier.width(10.dp))
                if (testing) CircularProgressIndicator(Modifier.size(17.dp), strokeWidth = 2.dp) else Icon(Icons.Rounded.Refresh, "测速", tint = t.textSecondary, modifier = Modifier.size(18.dp))
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                RefLatencyColumn("当前", if (testing) "…" else refDelay(current), Modifier.weight(1f))
                VerticalDivider(Modifier.height(36.dp), color = t.outline)
                RefLatencyColumn("平均", if (testing) "…" else refDelay(average), Modifier.weight(1f))
                VerticalDivider(Modifier.height(36.dp), color = t.outline)
                RefLatencyColumn("最快", if (testing) "…" else refDelay(fastest), Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun RefLatencyColumn(label: String, value: String, modifier: Modifier) {
    val t = LocalBichenTokens.current
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, color = t.textSecondary, style = MaterialTheme.typography.labelSmall)
        Text(value, color = MaterialTheme.colorScheme.primary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
private fun RefNetworkIdentityCard(lan: String, connections: Int, modifier: Modifier) {
    val t = LocalBichenTokens.current
    Surface(modifier = modifier, shape = RoundedCornerShape(15.dp), color = t.cardBackground, shadowElevation = 0.dp) {
        Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("网络", color = t.textPrimary, style = MaterialTheme.typography.titleSmall)
            Text("LAN", color = t.textSecondary, style = MaterialTheme.typography.labelSmall)
            Text(lan.ifBlank { "—" }, color = t.textPrimary, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("连接  $connections", color = t.textSecondary, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun RefSpeedCard(up: Long, down: Long, modifier: Modifier) {
    val t = LocalBichenTokens.current
    Surface(modifier = modifier, shape = RoundedCornerShape(15.dp), color = t.cardBackground, shadowElevation = 0.dp) {
        Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("网速", color = t.textPrimary, style = MaterialTheme.typography.titleSmall)
            Text("上行   ${refSpeed(up)}", color = t.textPrimary, style = MaterialTheme.typography.bodyMedium)
            Text("下行   ${refSpeed(down)}", color = t.textPrimary, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun RefSubscriptionCompact(items: List<DashboardProviderUi>, modifier: Modifier) {
    val t = LocalBichenTokens.current
    val tracked = items.filter { it.hasSubscriptionInfo && it.total > 0L }
    val used = tracked.sumOf { it.used }
    val total = tracked.sumOf { it.total }
    val remain = (total - used).coerceAtLeast(0L)
    val ratio = if (total <= 0L) 0f else (used.toDouble() / total.toDouble()).toFloat().coerceIn(0f, 1f)
    Surface(modifier = modifier, shape = RoundedCornerShape(15.dp), color = t.cardBackground, shadowElevation = 0.dp) {
        Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("订阅", color = t.textPrimary, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                if (total > 0L) Text("剩余 ${((1f - ratio).coerceIn(0f, 1f) * 100).toInt()}%", color = t.textSecondary, style = MaterialTheme.typography.labelSmall)
            }
            Text(if (tracked.isEmpty()) "${items.size} 个远程订阅" else "剩余 ${refBytes(remain)}", color = t.textPrimary, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (total > 0L) LinearProgressIndicator(progress = { ratio }, modifier = Modifier.fillMaxWidth().height(3.dp).clip(CircleShape))
        }
    }
}

@Composable
private fun RefResourceCard(memory: Long, pid: Int, connections: Int, modifier: Modifier) {
    val t = LocalBichenTokens.current
    Surface(modifier = modifier, shape = RoundedCornerShape(15.dp), color = t.cardBackground, shadowElevation = 0.dp) {
        Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("资源占用", color = t.textPrimary, style = MaterialTheme.typography.titleSmall)
            Text("内存   ${refBytes(memory)}", color = t.textPrimary, style = MaterialTheme.typography.bodyMedium)
            Text("PID   ${if (pid > 0) pid else "—"}", color = t.textSecondary, style = MaterialTheme.typography.labelSmall)
            Text("连接   $connections", color = t.textSecondary, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun RefActionText(text: String, enabled: Boolean, onClick: () -> Unit, modifier: Modifier, color: Color = LocalBichenTokens.current.textPrimary) {
    Box(
        modifier.height(34.dp).clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = if (enabled) color else LocalBichenTokens.current.textSecondary.copy(alpha = .45f), style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun RefMetricCard(title: String, value: String, icon: ImageVector, modifier: Modifier, onClick: (() -> Unit)? = null) {
    val t = LocalBichenTokens.current
    val clickable = if (onClick == null) modifier else modifier.clickable(onClick = onClick)
    Surface(modifier = clickable, shape = RoundedCornerShape(14.dp), color = t.cardBackground, shadowElevation = 0.dp) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(17.dp))
                Spacer(Modifier.weight(1f))
                Text(title, color = t.textSecondary, style = MaterialTheme.typography.labelSmall)
            }
            Text(value, color = t.textPrimary, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun RefSmallTool(title: String, subtitle: String, icon: ImageVector, onClick: () -> Unit, modifier: Modifier) {
    val t = LocalBichenTokens.current
    Surface(onClick = onClick, modifier = modifier, shape = RoundedCornerShape(14.dp), color = t.cardBackground, shadowElevation = 0.dp) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(19.dp))
            Spacer(Modifier.width(9.dp))
            Column(Modifier.weight(1f)) {
                Text(title, color = t.textPrimary, style = MaterialTheme.typography.titleSmall)
                Text(subtitle, color = t.textSecondary, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun RefSubscriptionCard(items: List<DashboardProviderUi>) {
    val t = LocalBichenTokens.current
    val tracked = items.filter { it.hasSubscriptionInfo && it.total > 0L }
    val used = tracked.sumOf { it.used }
    val total = tracked.sumOf { it.total }
    val remain = (total - used).coerceAtLeast(0L)
    Surface(shape = RoundedCornerShape(14.dp), color = t.cardBackground, shadowElevation = 0.dp) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("订阅", color = t.textSecondary, style = MaterialTheme.typography.labelSmall)
                Text(if (tracked.isEmpty()) "${items.size} 个远程订阅" else "剩余 ${refBytes(remain)}", color = t.textPrimary, style = MaterialTheme.typography.titleSmall)
            }
            if (total > 0L) {
                val ratio = (used.toDouble() / total.toDouble()).toFloat().coerceIn(0f, 1f)
                CircularProgressIndicator(progress = { ratio }, modifier = Modifier.size(34.dp), strokeWidth = 4.dp)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RefPanel(
    state: ProxyComposeState,
    repo: ProxyDashboardRepository,
    delays: MutableMap<String, Long>,
    onRefreshState: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val t = LocalBichenTokens.current
    var tab by rememberSaveable { mutableStateOf(RefPanelTab.Overview) }
    var refreshing by remember { mutableStateOf(false) }
    var providers by remember { mutableStateOf<List<DashboardProviderUi>>(emptyList()) }
    var rules by remember { mutableStateOf<List<ProxyRuleUi>>(emptyList()) }
    var ruleSets by remember { mutableStateOf<List<DashboardRuleSetUi>>(emptyList()) }
    var selectedGroupName by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingNode by rememberSaveable { mutableStateOf("") }
    val selectedLocal = remember { mutableStateMapOf<String, String>() }
    val testing = remember { mutableStateMapOf<String, Boolean>() }
    var error by remember { mutableStateOf("") }
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }

    val filteredGroups = remember(state.groups, query) {
        state.groups.filter { group ->
            query.isBlank() || group.name.contains(query, true) || group.now.contains(query, true) ||
                group.nodes.any { it.name.contains(query, true) }
        }
    }
    val filteredNodes = remember(state.groups, query) {
        state.groups.flatMap { it.nodes }.distinctBy { it.name }.filter { node ->
            query.isBlank() || node.name.contains(query, true) || node.type.contains(query, true)
        }
    }

    suspend fun loadTab() {
        if (!state.running) return
        try {
            when (tab) {
                RefPanelTab.Subscriptions -> providers = repo.providers()
                RefPanelTab.Rules -> rules = repo.rules()
                RefPanelTab.RuleSets -> ruleSets = repo.ruleSets()
                else -> Unit
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (e: Exception) {
            error = e.message ?: "读取失败"
        }
    }

    fun refresh() {
        if (refreshing) return
        scope.launch {
            refreshing = true
            error = ""
            try {
                when (tab) {
                    RefPanelTab.Overview, RefPanelTab.Nodes -> delays.putAll(repo.globalDelay())
                    RefPanelTab.Subscriptions -> providers = repo.refreshSubscriptions()
                    RefPanelTab.RuleSets -> ruleSets = repo.refreshRuleSets()
                    RefPanelTab.Rules -> rules = repo.rules()
                    RefPanelTab.Connections -> onRefreshState()
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (e: Exception) {
                error = e.message ?: "刷新失败"
            } finally {
                refreshing = false
            }
        }
    }

    LaunchedEffect(tab, state.running) { loadTab() }

    PullToRefreshBox(isRefreshing = refreshing, onRefresh = ::refresh, modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Column(Modifier.statusBarsPadding(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                            Text("代理面板", color = t.textPrimary, fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.Bold)
                            Text("${state.groups.size} 个策略组", color = t.textSecondary, style = MaterialTheme.typography.labelSmall)
                        }
                        IconButton(onClick = { context.startActivity(Intent(context, ProxyWebUiActivity::class.java)) }, modifier = Modifier.size(40.dp)) {
                            Icon(Icons.Rounded.Language, "WebUI", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(19.dp))
                        }
                        IconButton(onClick = { searchOpen = !searchOpen; if (!searchOpen) query = "" }, modifier = Modifier.size(40.dp)) {
                            Icon(if (searchOpen) Icons.Rounded.Close else Icons.Rounded.Search, "搜索", tint = t.textSecondary, modifier = Modifier.size(19.dp))
                        }
                    }
                    RefPanelTabs(tab) { tab = it }
                    if (searchOpen) {
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            placeholder = { Text("搜索策略组或节点") },
                            leadingIcon = { Icon(Icons.Rounded.Search, null, Modifier.size(18.dp)) },
                            shape = RoundedCornerShape(13.dp),
                        )
                    }
                }
            }
            if (error.isNotBlank()) item { RefNotice(error) }
            if (!state.running) {
                item { RefNotice("代理未运行") }
            } else when (tab) {
                RefPanelTab.Overview -> {
                    item {
                        Surface(shape = RoundedCornerShape(15.dp), color = t.cardBackground, shadowElevation = 0.dp) {
                            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(34.dp).background(t.selectionBackground, RoundedCornerShape(11.dp)), contentAlignment = Alignment.Center) {
                                    Icon(Icons.Rounded.Speed, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(19.dp))
                                }
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text("策略组", color = t.textPrimary, style = MaterialTheme.typography.titleMedium)
                                    Text("${filteredGroups.size} 个策略组 · 点击卡片选择节点", color = t.textSecondary, style = MaterialTheme.typography.labelSmall)
                                }
                                TextButton(onClick = ::refresh, enabled = !refreshing) { Text(if (refreshing) "测速中" else "全部测速") }
                            }
                        }
                    }
                    itemsIndexed(filteredGroups.chunked(2), key = { index, _ -> "groups-$index" }) { _, pair ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            pair.forEach { group ->
                                val selected = selectedLocal[group.name] ?: group.now
                                RefGroupCard(
                                    group = group,
                                    selected = selected,
                                    expanded = selectedGroupName == group.name,
                                    delay = delays[selected] ?: group.nodes.firstOrNull { it.name == selected }?.lastDelay,
                                    modifier = Modifier.weight(1f),
                                    onClick = { selectedGroupName = group.name; pendingNode = selected },
                                )
                            }
                            if (pair.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }
                RefPanelTab.Nodes -> {
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("全部节点", color = t.textPrimary, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                            TextButton(onClick = ::refresh, enabled = !refreshing) { Text(if (refreshing) "测速中" else "全部测速") }
                        }
                    }
                    itemsIndexed(filteredNodes.chunked(2), key = { index, _ -> "nodes-$index" }) { _, pair ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            pair.forEach { node ->
                                RefLeafNodeCard(node, delays[node.name] ?: node.lastDelay, Modifier.weight(1f)) {
                                    if (testing[node.name] != true) scope.launch {
                                        testing[node.name] = true
                                        try { delays[node.name] = repo.delay(node.name) }
                                        catch (_: Exception) { delays[node.name] = -1L }
                                        finally { testing.remove(node.name) }
                                    }
                                }
                            }
                            if (pair.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }
                RefPanelTab.Subscriptions -> items(providers, key = { it.name }) { item ->
                    RefProviderRow(item) { context.startActivity(Intent(context, ProxySubscriptionActivity::class.java)) }
                }
                RefPanelTab.Connections -> items(state.connections, key = { it.id }) { c ->
                    RefConnectionRow(c) { scope.launch { repo.closeConnection(c.id); onRefreshState() } }
                }
                RefPanelTab.Rules -> items(rules, key = { it.index }) { RefRuleRow(it) }
                RefPanelTab.RuleSets -> items(ruleSets, key = { it.name }) { RefRuleSetRow(it) }
            }
        }
    }

    val selectedGroup = selectedGroupName?.let { name -> state.groups.firstOrNull { it.name == name } }
    if (selectedGroup != null) {
        ModalBottomSheet(
            onDismissRequest = { selectedGroupName = null },
            shape = RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp),
            containerColor = t.cardBackground,
            scrimColor = Color.Black.copy(alpha = .30f),
            dragHandle = {
                Box(
                    Modifier.padding(top = 10.dp, bottom = 6.dp)
                        .size(width = 38.dp, height = 4.dp)
                        .background(t.outline.copy(alpha = .75f), CircleShape),
                )
            },
        ) {
            RefNodeSheet(
                group = selectedGroup,
                selected = pendingNode.ifBlank { selectedLocal[selectedGroup.name] ?: selectedGroup.now },
                delays = delays,
                testing = testing,
                onSelect = { pendingNode = it },
                onDelay = { node ->
                    if (testing[node] != true) scope.launch {
                        testing[node] = true
                        try { delays[node] = repo.delay(node) }
                        catch (_: Exception) { delays[node] = -1L }
                        finally { testing.remove(node) }
                    }
                },
                onTestAll = {
                    selectedGroup.nodes.forEach { node ->
                        if (testing[node.name] != true) scope.launch {
                            testing[node.name] = true
                            try { delays[node.name] = repo.delay(node.name) }
                            catch (_: Exception) { delays[node.name] = -1L }
                            finally { testing.remove(node.name) }
                        }
                    }
                },
                onConfirm = {
                    val node = pendingNode.ifBlank { selectedLocal[selectedGroup.name] ?: selectedGroup.now }
                    if (node.isNotBlank()) {
                        selectedLocal[selectedGroup.name] = node
                        scope.launch {
                            try {
                                repo.select(selectedGroup.name, node)
                                onRefreshState()
                            } catch (_: Exception) {
                                selectedLocal.remove(selectedGroup.name)
                            }
                        }
                    }
                    selectedGroupName = null
                },
            )
        }
    }
}

@Composable
private fun RefNodeSheet(
    group: ProxyGroupUi,
    selected: String,
    delays: Map<String, Long>,
    testing: Map<String, Boolean>,
    onSelect: (String) -> Unit,
    onDelay: (String) -> Unit,
    onTestAll: () -> Unit,
    onConfirm: () -> Unit,
) {
    val t = LocalBichenTokens.current
    val scheme = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, bottom = 18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(38.dp).background(scheme.primary.copy(alpha = .09f), RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                Icon(refGroupIcon(group.type), null, tint = scheme.primary, modifier = Modifier.size(21.dp))
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(group.name, color = t.textPrimary, fontSize = 19.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${refGroupType(group.type)} · ${group.nodes.size} 个节点", color = t.textSecondary, style = MaterialTheme.typography.bodySmall)
            }
        }
        Row(
            Modifier.fillMaxWidth()
                .background(
                    Brush.verticalGradient(listOf(t.elevatedCardBackground.copy(alpha = .86f), t.cardBackground.copy(alpha = .70f))),
                    RoundedCornerShape(14.dp),
                )
                .border(.7.dp, t.outline.copy(alpha = .45f), RoundedCornerShape(14.dp))
                .clickable(onClick = onTestAll)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("测试全部节点", color = t.textPrimary, style = MaterialTheme.typography.titleSmall)
                Text("${group.nodes.size} 个节点", color = t.textSecondary, style = MaterialTheme.typography.labelSmall)
            }
            Icon(Icons.Rounded.Refresh, "测试全部节点", tint = scheme.primary, modifier = Modifier.size(21.dp))
        }
        LazyColumn(
            modifier = Modifier.fillMaxWidth().heightIn(max = 430.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(group.nodes, key = { it.name }) { node ->
                val active = node.name == selected
                val shape = RoundedCornerShape(14.dp)
                val fill = if (active) {
                    Brush.verticalGradient(listOf(t.selectionBackground.copy(alpha = .94f), scheme.primaryContainer.copy(alpha = .56f)))
                } else {
                    Brush.verticalGradient(listOf(t.elevatedCardBackground.copy(alpha = .82f), t.cardBackground.copy(alpha = .64f)))
                }
                Row(
                    Modifier.fillMaxWidth()
                        .background(fill, shape)
                        .border(.7.dp, if (active) scheme.primary.copy(alpha = .28f) else t.outline.copy(alpha = .34f), shape)
                        .clickable { onSelect(node.name) }
                        .padding(horizontal = 14.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(node.name, color = t.textPrimary, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(listOf(node.type, if (node.udp) "UDP" else "").filter { it.isNotBlank() }.joinToString(" · "), color = t.textSecondary, style = MaterialTheme.typography.labelSmall)
                    }
                    if (active) {
                        Icon(Icons.Rounded.Check, "已选择", tint = scheme.primary, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                    }
                    RefDelayBadge(
                        value = delays[node.name] ?: node.lastDelay,
                        testing = testing[node.name] == true,
                        onClick = { onDelay(node.name) },
                    )
                }
            }
        }
        Button(
            onClick = onConfirm,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(14.dp),
        ) {
            Text("确定", fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun RefPanelTabs(selected: RefPanelTab, onSelect: (RefPanelTab) -> Unit) {
    val t = LocalBichenTokens.current
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        RefPanelTab.entries.forEach { tab ->
            val active = tab == selected
            Surface(
                onClick = { onSelect(tab) },
                shape = RoundedCornerShape(10.dp),
                color = if (active) t.selectionBackground else t.cardBackground,
                shadowElevation = 0.dp,
            ) {
                Box(Modifier.height(34.dp).padding(horizontal = 13.dp), contentAlignment = Alignment.Center) {
                    Text(tab.label, color = if (active) MaterialTheme.colorScheme.primary else t.textPrimary, fontSize = 12.sp, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun RefPanelOverview(state: ProxyComposeState, delays: Map<String, Long>) {
    val t = LocalBichenTokens.current
    val values = delays.values.filter { it > 0L }
    val avg = values.takeIf { it.isNotEmpty() }?.average()?.toInt()?.toString() ?: "--"
    Surface(shape = RoundedCornerShape(16.dp), color = t.cardBackground, shadowElevation = 0.dp) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(state.config, color = t.textPrimary, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RefMetric("连接", state.connections.size.toString(), Modifier.weight(1f))
                RefMetric("延迟", "$avg ms", Modifier.weight(1f))
                RefMetric("规则组", state.groups.size.toString(), Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun RefGroupCard(group: ProxyGroupUi, selected: String, expanded: Boolean, delay: Long?, modifier: Modifier, onClick: () -> Unit) {
    val t = LocalBichenTokens.current
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(15.dp)
    val glass = if (expanded) {
        Brush.verticalGradient(listOf(t.selectionBackground.copy(alpha = .96f), scheme.primaryContainer.copy(alpha = .38f)))
    } else {
        Brush.verticalGradient(listOf(t.cardBackground.copy(alpha = .98f), t.elevatedCardBackground.copy(alpha = .72f)))
    }
    Column(
        modifier.background(glass, shape)
            .border(.7.dp, if (expanded) scheme.primary.copy(alpha = .24f) else t.outline.copy(alpha = .46f), shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(32.dp).background(scheme.primary.copy(alpha = .09f), RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
                Icon(refGroupIcon(group.type), null, tint = scheme.primary, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(group.name, color = t.textPrimary, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(refGroupType(group.type), color = t.textSecondary, style = MaterialTheme.typography.labelSmall, maxLines = 1)
            }
            RefDelayBadge(delay, false, null)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(selected.ifBlank { "未选择" }, color = t.textSecondary, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text("${group.nodes.size} 节点", color = t.textSecondary, style = MaterialTheme.typography.labelSmall)
            if (expanded) { Spacer(Modifier.width(5.dp)); Icon(Icons.Rounded.CheckCircle, "已打开", tint = scheme.primary, modifier = Modifier.size(15.dp)) }
        }
    }
}

@Composable
private fun RefLeafNodeCard(node: ProxyNodeUi, delay: Long?, modifier: Modifier, onClick: () -> Unit) {
    val t = LocalBichenTokens.current
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier.background(t.cardBackground, shape).border(.7.dp, t.outline.copy(alpha = .45f), shape).clickable(onClick = onClick).padding(11.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(28.dp).background(scheme.primary.copy(alpha = .08f), RoundedCornerShape(9.dp)), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Public, null, tint = scheme.primary, modifier = Modifier.size(16.dp))
            }
            Spacer(Modifier.width(7.dp))
            Text(node.name, color = t.textPrimary, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            RefDelayBadge(delay, false, null)
        }
        Text(listOf(node.type.ifBlank { "节点" }, if (node.udp) "UDP" else "").filter { it.isNotBlank() }.joinToString(" · "), color = t.textSecondary, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private fun refGroupIcon(type: String): ImageVector = when (type.lowercase()) {
    "urltest", "fallback" -> Icons.Rounded.Speed
    "selector" -> Icons.Rounded.Tune
    "loadbalance" -> Icons.Rounded.SwapHoriz
    else -> Icons.Rounded.Hub
}

private fun refGroupType(type: String): String = when (type.lowercase()) {
    "urltest" -> "自动测速"
    "selector" -> "手动选择"
    "fallback" -> "故障转移"
    "loadbalance" -> "负载均衡"
    else -> type.ifBlank { "策略组" }
}

@Composable
private fun RefDelayBadge(value: Long?, testing: Boolean, onClick: (() -> Unit)?) {
    val t = LocalBichenTokens.current
    val scheme = MaterialTheme.colorScheme
    val text = if (testing) "…" else refDelay(value)
    val textColor = when {
        testing -> scheme.primary
        value == null -> t.textSecondary
        value <= 0L -> scheme.error
        value <= 250L -> t.success
        value <= 650L -> scheme.primary
        else -> t.warning
    }
    val shape = RoundedCornerShape(9.dp)
    val modifier = Modifier
        .background(scheme.primary.copy(alpha = .08f), shape)
        .then(if (onClick != null) Modifier.clickable(enabled = !testing, onClick = onClick) else Modifier)
        .padding(horizontal = 9.dp, vertical = 5.dp)
    Text(text, modifier = modifier, color = textColor, style = MaterialTheme.typography.labelMedium, maxLines = 1)
}

@Composable
private fun RefProviderRow(item: DashboardProviderUi, onClick: () -> Unit) {
    val t = LocalBichenTokens.current
    Surface(onClick = onClick, shape = RoundedCornerShape(12.dp), color = t.cardBackground, shadowElevation = 0.dp) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(item.name, color = t.textPrimary, style = MaterialTheme.typography.titleSmall)
            Text("${item.nodes.size} 个节点 · ${item.vehicleType}", color = t.textSecondary, style = MaterialTheme.typography.labelSmall)
            if (item.hasSubscriptionInfo && item.total > 0L) {
                LinearProgressIndicator(progress = { item.ratio }, modifier = Modifier.fillMaxWidth().height(4.dp).clip(CircleShape))
                Text("剩余 ${refBytes(item.remaining)}", color = t.textSecondary, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun RefConnectionRow(item: ProxyConnectionUi, onClose: () -> Unit) {
    val t = LocalBichenTokens.current
    Surface(shape = RoundedCornerShape(12.dp), color = t.cardBackground, shadowElevation = 0.dp) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(item.appName.ifBlank { item.host }, color = t.textPrimary, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(item.host, color = t.textSecondary, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(listOf(item.network, item.rule, item.chain).filter { it.isNotBlank() }.joinToString(" · "), color = t.textSecondary, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, "关闭", tint = t.textSecondary) }
        }
    }
}

@Composable
private fun RefRuleRow(item: ProxyRuleUi) {
    val t = LocalBichenTokens.current
    Surface(shape = RoundedCornerShape(12.dp), color = t.cardBackground, shadowElevation = 0.dp) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(item.type, color = t.textPrimary, style = MaterialTheme.typography.titleSmall)
                Text(item.payload.ifBlank { "—" }, color = t.textSecondary, style = MaterialTheme.typography.labelSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Text(item.proxy, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 92.dp))
        }
    }
}

@Composable
private fun RefRuleSetRow(item: DashboardRuleSetUi) {
    val t = LocalBichenTokens.current
    Surface(shape = RoundedCornerShape(12.dp), color = t.cardBackground, shadowElevation = 0.dp) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Inventory2, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(9.dp))
            Column(Modifier.weight(1f)) {
                Text(item.name, color = t.textPrimary, style = MaterialTheme.typography.titleSmall)
                Text(listOf(item.behavior, item.format, item.vehicleType).filter { it.isNotBlank() }.joinToString(" · "), color = t.textSecondary, style = MaterialTheme.typography.labelSmall)
            }
            if (item.ruleCount > 0) Text(item.ruleCount.toString(), color = t.textSecondary, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun RefTools(state: ProxyComposeState, onLog: (String) -> Unit) {
    val context = LocalContext.current
    val inspector = remember { ProxyRuntimeInspector(context) }
    val scope = rememberCoroutineScope()
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { RefTitleBar("工具") }
        item {
            RefGroup {
                RefToolRow(Icons.Rounded.Folder, "文件管理", "代理目录 · 配置 · 运行文件") { context.startActivity(Intent(context, ReferenceFileManagerActivity::class.java)) }
                RefDivider()
                RefToolRow(Icons.Rounded.Apps, "应用管理", "去广告应用放行与规则") { context.startActivity(Intent(context, CompactMainActivity::class.java)) }
                RefDivider()
                RefToolRow(Icons.Rounded.Memory, "内核管理", "${state.core} · 在线更新") { context.startActivity(Intent(context, ProxyCoreActivity::class.java)) }
                RefDivider()
                RefToolRow(Icons.Rounded.CloudDownload, "订阅管理", state.config) { context.startActivity(Intent(context, ProxySubscriptionActivity::class.java)) }
                RefDivider()
                RefToolRow(Icons.Rounded.Tune, "基础代理配置", "核心、模式、IPv6 与配置") { context.startActivity(Intent(context, RootTproxyActivity::class.java)) }
                RefDivider()
                RefToolRow(Icons.Rounded.Language, "WebUI", "本地 · Zashboard · MetaCubeXD · 自定义") { context.startActivity(Intent(context, ProxyWebUiActivity::class.java)) }
                RefDivider()
                RefToolRow(Icons.Rounded.Article, "运行日志", "查看 Mihomo 核心输出") {
                    scope.launch { onLog(runCatching { inspector.runtimeLog() }.getOrElse { it.message ?: "日志读取失败" }) }
                }
            }
        }
    }
}

@Composable
private fun RefSettings(state: ProxyComposeState) {
    val context = LocalContext.current
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { RefTitleBar("设置") }
        item {
            RefGroup {
                RefValueRow("基础代理配置", "") { context.startActivity(Intent(context, RootTproxyActivity::class.java)) }
                RefDivider()
                RefValueRow("核心", state.core) { context.startActivity(Intent(context, RootTproxyActivity::class.java)) }
                RefDivider()
                RefValueRow("运行模式", state.mode) { context.startActivity(Intent(context, RootTproxyActivity::class.java)) }
                RefDivider()
                RefValueRow("IPv6", state.ipv6) { context.startActivity(Intent(context, RootTproxyActivity::class.java)) }
                RefDivider()
                RefValueRow("当前配置", state.config) { context.startActivity(Intent(context, ProxySubscriptionActivity::class.java)) }
            }
        }
        item {
            RefGroup {
                RefValueRow("文件管理", "代理运行目录") { context.startActivity(Intent(context, ReferenceFileManagerActivity::class.java)) }
                RefDivider()
                RefValueRow("WebUI", "多面板 · 可自定义") { context.startActivity(Intent(context, ProxyWebUiActivity::class.java)) }
                RefDivider()
                RefValueRow("订阅与配置", "管理远程订阅") { context.startActivity(Intent(context, ProxySubscriptionActivity::class.java)) }
            }
        }
    }
}

@Composable
private fun RefGroup(content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = RoundedCornerShape(16.dp), color = LocalBichenTokens.current.cardBackground, shadowElevation = 0.dp) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp), content = content)
    }
}

@Composable
private fun RefToolRow(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    val t = LocalBichenTokens.current
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(21.dp))
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = t.textPrimary, style = MaterialTheme.typography.titleSmall)
            Text(subtitle, color = t.textSecondary, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Icon(Icons.Rounded.ChevronRight, null, tint = t.textSecondary, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun RefValueRow(title: String, value: String, onClick: (() -> Unit)? = null) {
    val t = LocalBichenTokens.current
    val modifier = if (onClick == null) Modifier.fillMaxWidth() else Modifier.fillMaxWidth().clickable(onClick = onClick)
    Row(modifier.padding(vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = t.textPrimary, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        if (value.isNotBlank()) Text(value, color = t.textSecondary, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 190.dp))
        if (onClick != null) {
            Spacer(Modifier.width(6.dp))
            Icon(Icons.Rounded.ChevronRight, null, tint = t.textSecondary, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun RefDivider() { HorizontalDivider(color = LocalBichenTokens.current.outline) }

@Composable
private fun RefMetric(title: String, value: String, modifier: Modifier) {
    val t = LocalBichenTokens.current
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(value, color = t.textPrimary, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(title, color = t.textSecondary, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun RefNotice(text: String) {
    Surface(shape = RoundedCornerShape(12.dp), color = LocalBichenTokens.current.selectionBackground, shadowElevation = 0.dp) {
        Text(text, Modifier.fillMaxWidth().padding(12.dp), color = LocalBichenTokens.current.textPrimary, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun RefTopBar(title: String, onBack: () -> Unit, onRefresh: () -> Unit) {
    val t = LocalBichenTokens.current
    Row(
        Modifier.fillMaxWidth().statusBarsPadding().heightIn(min = 54.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回", tint = t.textPrimary) }
        Text(title, color = t.textPrimary, fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        IconButton(onClick = onRefresh) { Icon(Icons.Rounded.Refresh, "刷新", tint = t.textPrimary) }
    }
}

@Composable
private fun RefTitleBar(title: String) {
    Text(
        title,
        color = LocalBichenTokens.current.textPrimary,
        fontSize = 26.sp,
        lineHeight = 32.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(top = 12.dp, bottom = 8.dp),
    )
}

private fun refDelay(value: Long?): String = when {
    value == null -> "--"
    value <= 0L -> "超时"
    else -> "$value ms"
}

private fun refBytes(value: Long): String {
    if (value <= 0L) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var v = value.toDouble()
    var i = 0
    while (v >= 1024.0 && i < units.lastIndex) {
        v /= 1024.0
        i++
    }
    return if (i == 0) "${v.toLong()} ${units[i]}" else String.format(java.util.Locale.US, "%.1f %s", v, units[i])
}

private fun refSpeed(value: Long): String = "${refBytes(value)}/s"

private fun refDuration(seconds: Long): String {
    if (seconds <= 0L) return "0s"
    val days = seconds / 86_400
    val hours = (seconds % 86_400) / 3_600
    val minutes = (seconds % 3_600) / 60
    return when {
        days > 0 -> "${days}天${hours}小时"
        hours > 0 -> "${hours}小时${minutes}分"
        minutes > 0 -> "${minutes}分${seconds % 60}秒"
        else -> "${seconds}秒"
    }
}
