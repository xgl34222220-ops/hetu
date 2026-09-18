package io.github.xgl34222220.bichen

import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
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
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Bichen V3 proxy workspace.
 *
 * Strategy / node selection surfaces intentionally use only opaque, non-animated fills.
 * This avoids OEM RenderNode partial-invalidation artifacts that appeared as white rectangles
 * after expanding a strategy group or selecting a node.
 */
@OptIn(ExperimentalMaterial3Api::class)
class LuoShuProxyV3Activity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { BichenTheme { V3Shell { finish() } } }
    }
}

private enum class V3Page { Home, Panel, Tools, Settings }
private enum class V3Tab(val label: String) {
    Overview("概览"), Nodes("节点"), Subscriptions("订阅"), Connections("连接"), Rules("规则"), RuleSets("规则集")
}
private enum class V3Sort { Config, Delay }
private data class V3Rate(val up: Long, val down: Long)

@Composable
private fun V3Shell(onBack: () -> Unit) {
    val context = LocalContext.current
    val controller = remember { ProxyComposeController(context) }
    val repo = remember { ProxyDashboardRepository(context) }
    val inspector = remember { ProxyRuntimeInspector(context) }
    val scope = rememberCoroutineScope()
    val haze = rememberHazeState()

    var page by rememberSaveable { mutableStateOf(V3Page.Home) }
    var state by remember { mutableStateOf(ProxyComposeState()) }
    var runtime by remember { mutableStateOf(ProxyRuntimeSnapshot()) }
    var providers by remember { mutableStateOf<List<DashboardProviderUi>>(emptyList()) }
    var operation by remember { mutableStateOf("") }
    var logText by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf("") }
    var testingAll by remember { mutableStateOf(false) }
    val delays = remember { mutableStateMapOf<String, Long>() }
    val rates = remember { mutableStateListOf<V3Rate>() }
    var upRate by remember { mutableLongStateOf(0L) }
    var downRate by remember { mutableLongStateOf(0L) }
    var lastUp by remember { mutableLongStateOf(0L) }
    var lastDown by remember { mutableLongStateOf(0L) }
    var lastAt by remember { mutableLongStateOf(0L) }

    suspend fun refresh() {
        try {
            val next = repo.state()
            val now = SystemClock.elapsedRealtime()
            if (lastAt > 0L && now > lastAt && next.uploadTotal >= lastUp && next.downloadTotal >= lastDown) {
                val elapsed = now - lastAt
                upRate = ((next.uploadTotal - lastUp) * 1000L / elapsed).coerceAtLeast(0L)
                downRate = ((next.downloadTotal - lastDown) * 1000L / elapsed).coerceAtLeast(0L)
                rates += V3Rate(upRate, downRate)
                while (rates.size > 36) rates.removeAt(0)
            }
            next.groups.flatMap { it.nodes }.forEach { node ->
                node.lastDelay?.takeIf { it > 0L }?.let { delays.putIfAbsent(node.name, it) }
            }
            runtime = if (next.running) runCatching { inspector.sample() }.getOrDefault(runtime) else ProxyRuntimeSnapshot()
            if (next.running) providers = runCatching { repo.providers() }.getOrDefault(providers) else providers = emptyList()
            state = next
            message = next.message
            lastAt = now
            lastUp = next.uploadTotal
            lastDown = next.downloadTotal
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (e: Exception) {
            message = e.message ?: "状态读取失败"
        }
    }

    fun toggle() {
        if (operation.isNotBlank()) return
        scope.launch {
            operation = if (state.running) "正在停止…" else "正在启动…"
            try {
                if (state.running) controller.stop { operation = it } else controller.start { operation = it }
                delay(300)
                refresh()
            } catch (e: Exception) {
                message = e.message ?: "操作失败"
            } finally { operation = "" }
        }
    }

    fun restart() {
        if (!state.running || operation.isNotBlank()) return
        scope.launch {
            operation = "正在重启…"
            try {
                controller.stop { operation = it }
                delay(180)
                controller.start { operation = it }
                delay(300)
                refresh()
            } catch (e: Exception) {
                message = e.message ?: "重启失败"
            } finally { operation = "" }
        }
    }

    fun reload() {
        if (!state.running || operation.isNotBlank()) return
        scope.launch {
            operation = "正在重载…"
            try {
                inspector.reloadConfig()
                message = "运行配置已重载"
                delay(220)
                refresh()
            } catch (e: Exception) {
                message = e.message ?: "重载失败"
            } finally { operation = "" }
        }
    }

    fun testAll() {
        if (!state.running || testingAll) return
        scope.launch {
            testingAll = true
            try {
                val result = repo.quickDelay()
                delays.putAll(result)
                if (result.isEmpty()) message = "没有可测速的当前节点"
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (e: Exception) {
                message = e.message ?: "测速失败"
            } finally {
                testingAll = false
            }
        }
    }

    LaunchedEffect(Unit) {
        runCatching { repo.ensureIcons() }
        refresh()
        while (true) {
            delay(2500)
            refresh()
        }
    }

    val dock = remember {
        listOf(
            DockItem("首页", Icons.Rounded.Home, .94f),
            DockItem("面板", Icons.Rounded.Public, .96f),
            DockItem("工具", Icons.Rounded.GridView, .96f),
            DockItem("设置", Icons.Rounded.Settings, .94f),
        )
    }

    Box(Modifier.fillMaxSize().background(LocalBichenTokens.current.pageBackground)) {
        Box(Modifier.fillMaxSize().padding(bottom = 104.dp)) {
            when (page) {
                V3Page.Home -> V3Home(
                    state = state,
                    runtime = runtime,
                    providers = providers,
                    delays = delays,
                    upRate = upRate,
                    downRate = downRate,
                    operation = operation,
                    message = message,
                    testingAll = testingAll,
                    onBack = onBack,
                    onRefresh = { scope.launch { refresh() } },
                    onToggle = ::toggle,
                    onReload = ::reload,
                    onRestart = ::restart,
                    onTestAll = ::testAll,
                    onPanel = { page = V3Page.Panel },
                    onLog = { scope.launch { logText = runCatching { inspector.runtimeLog() }.getOrElse { it.message ?: "日志读取失败" } } },
                )
                V3Page.Panel -> V3Panel(state, repo, delays, upRate, downRate, rates) { scope.launch { refresh() } }
                V3Page.Tools -> V3Tools(state)
                V3Page.Settings -> V3Settings(state)
            }
        }
        BichenGlassDock(
            items = dock,
            selected = page.ordinal,
            onSelect = { page = V3Page.entries[it] },
            hazeState = haze,
            backdrop = null,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }

    logText?.let { text ->
        AlertDialog(
            onDismissRequest = { logText = null },
            shape = RoundedCornerShape(28.dp),
            title = { Text("Mihomo 运行日志") },
            text = { Box(Modifier.fillMaxWidth().heightIn(max = 520.dp).verticalScroll(rememberScrollState())) { Text(text, style = MaterialTheme.typography.bodySmall) } },
            confirmButton = { TextButton(onClick = { logText = null }) { Text("关闭") } },
        )
    }
}

@Composable
private fun V3Home(
    state: ProxyComposeState,
    runtime: ProxyRuntimeSnapshot,
    providers: List<DashboardProviderUi>,
    delays: Map<String, Long>,
    upRate: Long,
    downRate: Long,
    operation: String,
    message: String,
    testingAll: Boolean,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onToggle: () -> Unit,
    onReload: () -> Unit,
    onRestart: () -> Unit,
    onTestAll: () -> Unit,
    onPanel: () -> Unit,
    onLog: () -> Unit,
) {
    val t = LocalBichenTokens.current
    val scheme = MaterialTheme.colorScheme
    val measured = delays.values.filter { it > 0L }
    val avg = measured.takeIf { it.isNotEmpty() }?.average()?.toInt()?.let { "$it ms" } ?: "--"
    val memory = runtime.rssBytes.takeIf { it > 0L } ?: state.memoryBytes

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item { V3DetailBar("代理", onBack, onRefresh) }
        item {
            Surface(shape = RoundedCornerShape(24.dp), color = t.cardBackground, shadowElevation = 0.dp) {
                Column(
                    Modifier.fillMaxWidth()
                        .background(Brush.linearGradient(listOf(lerp(t.cardBackground, scheme.primaryContainer, .38f), t.cardBackground)))
                        .padding(22.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        V3Status(state.running)
                        Spacer(Modifier.weight(1f))
                        Text("${state.core} · ${state.mode}", color = t.textSecondary, style = MaterialTheme.typography.labelMedium)
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text("当前配置", color = t.textSecondary, style = MaterialTheme.typography.bodySmall)
                        Text(state.config, color = t.textPrimary, fontSize = 24.sp, lineHeight = 32.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    Row(verticalAlignment = Alignment.Bottom) {
                        Column(Modifier.weight(1f)) {
                            Text("下载", color = t.textSecondary, style = MaterialTheme.typography.labelSmall)
                            Text(if (state.running) v3Speed(downRate) else "—", color = scheme.primary, fontSize = 27.sp, lineHeight = 34.sp, fontWeight = FontWeight.SemiBold)
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text("上传", color = t.textSecondary, style = MaterialTheme.typography.labelSmall)
                            Text(if (state.running) v3Speed(upRate) else "—", color = t.textPrimary, fontSize = 18.sp, lineHeight = 25.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        V3Metric(if (state.running) v3Duration(runtime.elapsedSeconds) else "—", "运行时间", Modifier.weight(1f))
                        V3Metric(if (state.running) v3Bytes(memory) else "—", "内存", Modifier.weight(1f))
                        V3Metric(state.connections.size.toString(), "连接", Modifier.weight(1f))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        V3Metric(runtime.lanAddress, "LAN", Modifier.weight(1.5f))
                        Surface(
                            onClick = onTestAll,
                            enabled = state.running && !testingAll,
                            modifier = Modifier.weight(1f).heightIn(min = 50.dp),
                            shape = RoundedCornerShape(14.dp),
                            color = t.elevatedCardBackground,
                        ) {
                            V3LatencyMetric(
                                avg,
                                testingAll,
                                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 7.dp),
                            )
                        }
                    }
                    Button(onClick = onToggle, enabled = operation.isBlank(), modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp), shape = RoundedCornerShape(18.dp)) {
                        Icon(if (state.running) Icons.Rounded.Stop else Icons.Rounded.PlayArrow, null, Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(if (state.running) "停止代理" else "启动代理")
                    }
                    operation.ifBlank { message }.takeIf { it.isNotBlank() }?.let { Text(it, color = t.textSecondary, style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
        if (state.running) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    V3Heading("快捷控制", "配置、核心与控制台")
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        V3Shortcut("重载配置", "重新载入运行副本", Icons.Rounded.Refresh, onReload, Modifier.weight(1f))
                        V3Shortcut("重启核心", "重启 Mihomo", Icons.Rounded.RestartAlt, onRestart, Modifier.weight(1f))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        V3Shortcut("运行日志", "查看核心输出", Icons.Rounded.Article, onLog, Modifier.weight(1f))
                    }
                }
            }
        }
        if (state.running && providers.isNotEmpty()) item { V3SubscriptionSummary(providers) }
        item {
            Surface(onClick = onPanel, shape = RoundedCornerShape(20.dp), color = t.cardBackground, shadowElevation = 0.dp) {
                Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                    V3IconBox(Icons.Rounded.Public)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("完整代理面板", color = t.textPrimary, style = MaterialTheme.typography.titleSmall)
                        Text("节点 · 订阅 · 连接 · 规则", color = t.textSecondary, style = MaterialTheme.typography.bodySmall)
                    }
                    Icon(Icons.Rounded.ChevronRight, null, tint = t.textSecondary, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

@Composable
private fun V3SubscriptionSummary(items: List<DashboardProviderUi>) {
    val t = LocalBichenTokens.current
    val tracked = items.filter { it.hasSubscriptionInfo && it.total > 0L }
    val used = tracked.sumOf { it.used }
    val total = tracked.sumOf { it.total }
    val remaining = (total - used).coerceAtLeast(0L)
    val ratio = if (total <= 0L) 0f else (used.toDouble() / total).toFloat().coerceIn(0f, 1f)
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        V3Heading("订阅流量", "${items.size} 个远程订阅")
        V3Card {
            if (tracked.isEmpty()) Text("订阅服务器未返回可用流量信息", color = t.textSecondary, style = MaterialTheme.typography.bodySmall)
            else {
                Row {
                    Column(Modifier.weight(1f)) {
                        Text(v3Bytes(remaining), color = t.textPrimary, fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold)
                        Text("剩余流量", color = t.textSecondary, style = MaterialTheme.typography.bodySmall)
                    }
                    Text("已用 ${v3Bytes(used)}", color = t.textSecondary, style = MaterialTheme.typography.labelSmall)
                }
                LinearProgressIndicator(progress = { ratio }, modifier = Modifier.fillMaxWidth().height(5.dp).clip(CircleShape))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun V3Panel(
    state: ProxyComposeState,
    repo: ProxyDashboardRepository,
    delays: MutableMap<String, Long>,
    upRate: Long,
    downRate: Long,
    rates: List<V3Rate>,
    onRefreshState: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val t = LocalBichenTokens.current
    var tab by rememberSaveable { mutableStateOf(V3Tab.Nodes) }
    var sort by rememberSaveable { mutableStateOf(V3Sort.Config) }
    var query by rememberSaveable { mutableStateOf("") }
    var expanded by rememberSaveable { mutableStateOf<String?>(null) }
    var refreshing by remember { mutableStateOf(false) }
    var providers by remember { mutableStateOf<List<DashboardProviderUi>>(emptyList()) }
    var rules by remember { mutableStateOf<List<ProxyRuleUi>>(emptyList()) }
    var ruleSets by remember { mutableStateOf<List<DashboardRuleSetUi>>(emptyList()) }
    var error by remember { mutableStateOf("") }
    val selectedLocal = remember { mutableStateMapOf<String, String>() }
    val testing = remember { mutableStateMapOf<String, Boolean>() }

    fun current(group: ProxyGroupUi) = selectedLocal[group.name] ?: group.now
    fun delayOf(node: ProxyNodeUi) = delays[node.name] ?: node.lastDelay

    suspend fun loadForTab() {
        if (!state.running) return
        try {
            when (tab) {
                V3Tab.Subscriptions -> providers = repo.providers()
                V3Tab.Rules -> rules = repo.rules()
                V3Tab.RuleSets -> ruleSets = repo.ruleSets()
                else -> Unit
            }
        } catch (e: Exception) { error = e.message ?: "读取失败" }
    }

    fun refreshTab() {
        if (refreshing) return
        refreshing = true
        scope.launch {
            try {
                when (tab) {
                    V3Tab.Nodes -> {
                val nodeNames = state.groups.flatMap { it.nodes }
                    .map { it.name }
                    .filter { it.isNotBlank() }
                    .distinct()
                nodeNames.forEach { testing[it] = true }
                try {
                    delays.putAll(repo.globalDelay())
                } finally {
                    nodeNames.forEach { testing.remove(it) }
                }
            }
                    V3Tab.Subscriptions -> providers = repo.refreshSubscriptions()
                    V3Tab.RuleSets -> ruleSets = repo.refreshRuleSets()
                    V3Tab.Rules -> rules = repo.rules()
                    V3Tab.Connections, V3Tab.Overview -> onRefreshState()
                }
            } catch (e: Exception) { error = e.message ?: "刷新失败" }
            finally { refreshing = false }
        }
    }

    LaunchedEffect(tab, state.running) { error = ""; loadForTab() }

    PullToRefreshBox(isRefreshing = refreshing, onRefresh = ::refreshTab, modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                Column(Modifier.statusBarsPadding().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("面板", color = t.textPrimary, fontSize = 26.sp, lineHeight = 34.sp, fontWeight = FontWeight.Bold)
                            Text(if (state.running) "${state.groups.size} 个策略组 · ${state.connections.size} 条连接" else "代理未运行", color = t.textSecondary, style = MaterialTheme.typography.bodySmall)
                        }
                        V3HeaderButton(Icons.Rounded.Refresh, "刷新", ::refreshTab, refreshing)
                    }
                    V3Tabs(tab) { tab = it }
                }
            }
            if (error.isNotBlank()) item { V3Notice(error) }
            if (!state.running) item { V3Empty(Icons.Rounded.PowerSettingsNew, "代理未运行", "启动代理后显示实时数据") }
            else when (tab) {
                V3Tab.Overview -> item { V3Overview(state, upRate, downRate, rates) }
                V3Tab.Nodes -> {
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            V3Heading("策略组", if (refreshing) "正在测试全部节点…" else "点策略组展开节点", Modifier.weight(1f))
                            V3SmallAction(Icons.Rounded.Speed, if (refreshing) "测速中" else "全部测速") { if (!refreshing) refreshTab() }
                            Spacer(Modifier.width(6.dp))
                            V3SmallAction(Icons.Rounded.Sort, if (sort == V3Sort.Config) "配置" else "延迟") { sort = if (sort == V3Sort.Config) V3Sort.Delay else V3Sort.Config }
                        }
                    }
                    item { V3Search(query, { query = it }, "搜索策略组或节点") }
                    val base = if (sort == V3Sort.Config) state.groups else state.groups.sortedBy { group ->
                        group.nodes.firstOrNull { it.name == current(group) }?.let(::delayOf)?.takeIf { it > 0L } ?: Long.MAX_VALUE
                    }
                    val needle = query.trim()
                    val groups = base.mapNotNull { group ->
                        if (needle.isBlank()) group else {
                            val hit = group.name.contains(needle, true) || current(group).contains(needle, true)
                            val nodes = group.nodes.filter { it.name.contains(needle, true) || it.type.contains(needle, true) }
                            when { hit -> group; nodes.isNotEmpty() -> group.copy(nodes = nodes); else -> null }
                        }
                    }
                    groups.chunked(2).forEachIndexed { index, pair ->
                        item(key = "v3-group-$index-${pair.joinToString { it.name }}") {
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                pair.forEach { group ->
                                    V3GroupCard(
                                        group = group,
                                        selected = current(group),
                                        expanded = expanded == group.name,
                                        delay = group.nodes.firstOrNull { it.name == current(group) }?.let(::delayOf),
                                        measured = group.nodes.count { (delayOf(it) ?: 0L) > 0L },
                                        testing = testing[current(group)] == true,
                                        onExpand = { expanded = if (expanded == group.name) null else group.name },
                                        onDelay = {
                                            val node = current(group)
                                            if (node.isNotBlank() && testing[node] != true) scope.launch {
                                                testing[node] = true
                                                try { delays[node] = repo.delay(node) } catch (_: Exception) { delays[node] = -1L }
                                                finally { testing.remove(node) }
                                            }
                                        },
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                                if (pair.size == 1) Spacer(Modifier.weight(1f))
                            }
                        }
                        pair.firstOrNull { expanded == it.name }?.let { group ->
                            item(key = "v3-expanded-${group.name}") {
                                V3NodeGrid(
                                    group = group,
                                    selected = current(group),
                                    delays = delays,
                                    testing = testing,
                                    onSelect = { node ->
                                        selectedLocal[group.name] = node
                                        scope.launch {
                                            try { repo.select(group.name, node); onRefreshState() }
                                            catch (_: Exception) { selectedLocal.remove(group.name) }
                                        }
                                    },
                                    onDelay = { node ->
                                        if (testing[node] != true) scope.launch {
                                            testing[node] = true
                                            try { delays[node] = repo.delay(node) } catch (_: Exception) { delays[node] = -1L }
                                            finally { testing.remove(node) }
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
                V3Tab.Subscriptions -> {
                    item { V3Heading("订阅", "下拉刷新全部远程订阅") }
                    item { V3ProviderList(providers) }
                }
                V3Tab.Connections -> {
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            V3Heading("连接", "${state.connections.size} 条实时连接", Modifier.weight(1f))
                            if (state.connections.isNotEmpty()) V3SmallAction(Icons.Rounded.Close, "全部关闭") { scope.launch { repo.closeAll(); onRefreshState() } }
                        }
                    }
                    item { V3ConnectionList(state.connections) { id -> scope.launch { repo.closeConnection(id); onRefreshState() } } }
                }
                V3Tab.Rules -> {
                    item { V3Heading("规则", "${rules.size} 条 · Mihomo /rules") }
                    item { V3RuleList(rules) }
                }
                V3Tab.RuleSets -> {
                    item { V3Heading("规则集", "下拉刷新全部远程规则集") }
                    item { V3RuleSetList(ruleSets) }
                }
            }
        }
    }
}

@Composable
private fun V3Tabs(selected: V3Tab, onSelect: (V3Tab) -> Unit) {
    val t = LocalBichenTokens.current
    val scheme = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        V3Tab.entries.forEach { tab ->
            val active = tab == selected
            val fill = if (active) lerp(t.pageBackground, scheme.primaryContainer, .58f) else Color.Transparent
            Box(
                Modifier.weight(1f).height(44.dp).clip(RoundedCornerShape(14.dp)).background(fill).clickable { onSelect(tab) },
                contentAlignment = Alignment.Center,
            ) {
                Text(tab.label, color = if (active) scheme.primary else t.textSecondary, fontSize = 12.sp, fontWeight = if (active) FontWeight.Bold else FontWeight.Medium, maxLines = 1)
            }
        }
    }
}

@Composable
private fun V3GroupCard(
    group: ProxyGroupUi,
    selected: String,
    expanded: Boolean,
    delay: Long?,
    measured: Int,
    testing: Boolean,
    onExpand: () -> Unit,
    onDelay: () -> Unit,
    modifier: Modifier,
) {
    val t = LocalBichenTokens.current
    val scheme = MaterialTheme.colorScheme
    // Opaque + direct state change: never animate/translucently composite this surface.
    val fill = if (expanded) t.selectionBackground else t.cardBackground
    Surface(modifier = modifier, shape = RoundedCornerShape(18.dp), color = fill, shadowElevation = 0.dp) {
        Column(
            Modifier.fillMaxWidth().clickable(onClick = onExpand).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                V3GroupIcon(group, 32.dp)
                Spacer(Modifier.width(9.dp))
                Column(Modifier.weight(1f)) {
                    Text(group.name, color = t.textPrimary, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${group.type} · $measured/${group.nodes.size}", color = t.textSecondary, style = MaterialTheme.typography.labelSmall)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(selected.ifBlank { "未选择" }, color = t.textSecondary, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                V3Delay(delay, testing, onDelay)
            }
        }
    }
}

@Composable
private fun V3NodeGrid(
    group: ProxyGroupUi,
    selected: String,
    delays: Map<String, Long>,
    testing: Map<String, Boolean>,
    onSelect: (String) -> Unit,
    onDelay: (String) -> Unit,
) {
    val t = LocalBichenTokens.current
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Text(group.name, Modifier.padding(horizontal = 4.dp, vertical = 2.dp), color = t.textSecondary, style = MaterialTheme.typography.labelMedium)
        group.nodes.chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                pair.forEach { node ->
                    V3NodeCell(node, selected == node.name, delays[node.name] ?: node.lastDelay, testing[node.name] == true, { onSelect(node.name) }, { onDelay(node.name) }, Modifier.weight(1f))
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun V3NodeCell(node: ProxyNodeUi, selected: Boolean, delay: Long?, testing: Boolean, onSelect: () -> Unit, onDelay: () -> Unit, modifier: Modifier) {
    val t = LocalBichenTokens.current
    val scheme = MaterialTheme.colorScheme
    // Same rule here: selected color is an opaque lerp and changes immediately.
    val fill = if (selected) t.selectionBackground else t.cardBackground
    Surface(modifier = modifier.heightIn(min = 64.dp), shape = RoundedCornerShape(16.dp), color = fill, shadowElevation = 0.dp) {
        Row(Modifier.fillMaxWidth().clickable(onClick = onSelect).padding(start = 11.dp, end = 5.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (selected) {
                Icon(Icons.Rounded.CheckCircle, "已选择", tint = scheme.primary, modifier = Modifier.size(17.dp))
                Spacer(Modifier.width(6.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(node.name, color = t.textPrimary, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(listOf(node.type, if (node.udp) "UDP" else "").filter { it.isNotBlank() }.joinToString(" · "), color = t.textSecondary, style = MaterialTheme.typography.labelSmall)
            }
            V3Delay(delay, testing, onDelay)
        }
    }
}

@Composable
private fun V3Delay(value: Long?, testing: Boolean, onClick: () -> Unit) {
    val t = LocalBichenTokens.current
    val scheme = MaterialTheme.colorScheme
    val text = when { value == null -> "--"; value <= 0L -> "超时"; else -> "$value ms" }
    val color = when {
        value == null -> t.textSecondary
        value <= 0L -> scheme.error
        value <= 250L -> t.success
        value <= 600L -> scheme.primary
        value <= 1000L -> t.warning
        else -> scheme.error
    }
    val motion = rememberInfiniteTransition(label = "latency-motion")
    val rotation by motion.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(animation = tween(820, easing = LinearEasing)),
        label = "latency-spin",
    )
    val pulse by motion.animateFloat(
        initialValue = .35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(animation = tween(520), repeatMode = RepeatMode.Reverse),
        label = "latency-pulse",
    )
    Box(
        Modifier.heightIn(min = 48.dp).widthIn(min = 58.dp).clip(RoundedCornerShape(12.dp)).clickable(enabled = !testing, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Crossfade(targetState = testing, animationSpec = tween(140), label = "latency-state") { active ->
            if (active) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Icon(Icons.Rounded.Refresh, "测速中", tint = scheme.primary, modifier = Modifier.size(13.dp).rotate(rotation))
                    Text("测速中", color = scheme.primary, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    Box(Modifier.size(4.dp).alpha(pulse).background(scheme.primary, CircleShape))
                }
            } else {
                Crossfade(targetState = text, animationSpec = tween(120), label = "latency-result") { display ->
                    Text(display, color = color, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun V3Overview(state: ProxyComposeState, up: Long, down: Long, rates: List<V3Rate>) {
    val t = LocalBichenTokens.current
    val scheme = MaterialTheme.colorScheme
    Surface(shape = RoundedCornerShape(24.dp), color = t.cardBackground, shadowElevation = 0.dp) {
        Column(
            Modifier.fillMaxWidth().background(Brush.linearGradient(listOf(lerp(t.cardBackground, scheme.primaryContainer, .32f), t.cardBackground))).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.Bottom) {
                Column(Modifier.weight(1f)) {
                    Text("实时下载", color = t.textSecondary, style = MaterialTheme.typography.bodySmall)
                    Text(v3Speed(down), color = scheme.primary, fontSize = 27.sp, lineHeight = 34.sp, fontWeight = FontWeight.SemiBold)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("实时上传", color = t.textSecondary, style = MaterialTheme.typography.bodySmall)
                    Text(v3Speed(up), color = t.textPrimary, fontSize = 19.sp, lineHeight = 26.sp, fontWeight = FontWeight.Medium)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                V3Metric(v3Bytes(state.downloadTotal), "累计下载", Modifier.weight(1f))
                V3Metric(v3Bytes(state.uploadTotal), "累计上传", Modifier.weight(1f))
                V3Metric(state.connections.size.toString(), "连接", Modifier.weight(1f))
            }
            if (rates.isNotEmpty()) Text("近实时流量持续采样中", color = t.textSecondary, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun V3ProviderList(items: List<DashboardProviderUi>) {
    val t = LocalBichenTokens.current
    if (items.isEmpty()) { V3Empty(Icons.Rounded.CloudOff, "没有远程订阅", "仅显示实际 HTTP 订阅 Provider"); return }
    V3ListCard {
        items.forEachIndexed { index, p ->
            if (index > 0) V3Divider()
            Column(Modifier.fillMaxWidth().padding(vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    V3IconBox(Icons.Rounded.CloudDownload, 40.dp)
                    Spacer(Modifier.width(11.dp))
                    Column(Modifier.weight(1f)) {
                        Text(p.name, color = t.textPrimary, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${p.nodes.size} 节点${p.updatedAt.takeIf { it.isNotBlank() }?.let { " · ${v3ShortTime(it)}" } ?: ""}", color = t.textSecondary, style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (p.hasSubscriptionInfo && p.total > 0L) {
                    LinearProgressIndicator(progress = { p.ratio }, modifier = Modifier.fillMaxWidth().height(4.dp).clip(CircleShape))
                    Row {
                        Text("已用 ${v3Bytes(p.used)}", color = t.textSecondary, style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
                        Text("剩余 ${v3Bytes(p.remaining)}", color = t.textSecondary, style = MaterialTheme.typography.labelSmall)
                    }
                    if (p.expire > 0L) Text("到期 ${v3Date(p.expire)}", color = t.textSecondary, style = MaterialTheme.typography.labelSmall)
                } else Text("服务端未提供流量信息", color = t.textSecondary, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun V3ConnectionList(items: List<ProxyConnectionUi>, onClose: (String) -> Unit) {
    val t = LocalBichenTokens.current
    if (items.isEmpty()) { V3Empty(Icons.Rounded.LinkOff, "暂无活动连接", "新连接会实时出现在这里"); return }
    V3ListCard {
        items.forEachIndexed { index, c ->
            if (index > 0) V3Divider()
            Row(Modifier.fillMaxWidth().padding(vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                if (c.appIcon != null) Image(c.appIcon.asImageBitmap(), c.appName, Modifier.size(40.dp).clip(RoundedCornerShape(11.dp)), contentScale = ContentScale.Crop)
                else Box(Modifier.size(40.dp).clip(RoundedCornerShape(11.dp)).background(t.elevatedCardBackground), contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Android, null, tint = t.textSecondary, modifier = Modifier.size(21.dp)) }
                Spacer(Modifier.width(11.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(c.appName.ifBlank { c.process.ifBlank { c.host } }, color = t.textPrimary, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(c.host, color = t.textSecondary, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(listOf(c.network, c.inbound, c.rule, c.chain).filter { it.isNotBlank() }.joinToString(" · "), color = t.textSecondary, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("↓ ${v3Bytes(c.download)}   ↑ ${v3Bytes(c.upload)}", color = t.textSecondary, style = MaterialTheme.typography.labelSmall)
                }
                IconButton(onClick = { onClose(c.id) }, modifier = Modifier.size(48.dp)) { Icon(Icons.Rounded.Close, "关闭连接", tint = t.textSecondary) }
            }
        }
    }
}

@Composable
private fun V3RuleList(items: List<ProxyRuleUi>) {
    val t = LocalBichenTokens.current
    if (items.isEmpty()) { V3Empty(Icons.Rounded.Rule, "没有规则数据", "确认核心控制接口已就绪"); return }
    V3ListCard {
        items.forEachIndexed { index, r ->
            if (index > 0) V3Divider()
            Row(Modifier.fillMaxWidth().padding(vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.width(82.dp)) {
                    Text(r.type, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("#${r.index}", color = t.textSecondary, style = MaterialTheme.typography.labelSmall)
                }
                Text(r.payload.ifBlank { "—" }, color = t.textPrimary, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                Text(r.proxy, color = t.textSecondary, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 90.dp))
            }
        }
    }
}

@Composable
private fun V3RuleSetList(items: List<DashboardRuleSetUi>) {
    val t = LocalBichenTokens.current
    if (items.isEmpty()) { V3Empty(Icons.Rounded.Inventory2, "没有规则集", "下拉可重新读取和更新远程规则集"); return }
    V3ListCard {
        items.forEachIndexed { index, r ->
            if (index > 0) V3Divider()
            Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                V3IconBox(Icons.Rounded.Inventory2, 40.dp)
                Spacer(Modifier.width(11.dp))
                Column(Modifier.weight(1f)) {
                    Text(r.name, color = t.textPrimary, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(listOf(r.behavior, r.format, r.vehicleType, if (r.ruleCount > 0) "${r.ruleCount} 条" else "").filter { it.isNotBlank() }.joinToString(" · "), color = t.textSecondary, style = MaterialTheme.typography.bodySmall)
                    if (r.updatedAt.isNotBlank()) Text(v3ShortTime(r.updatedAt), color = t.textSecondary, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
private fun V3Tools(state: ProxyComposeState) {
    val context = LocalContext.current
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { V3TopBar("工具") }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                V3Heading("代理工具", "内核、配置与控制台")
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    V3Shortcut("内核管理", "${state.core} · 在线更新", Icons.Rounded.Memory, { context.startActivity(Intent(context, ProxyCoreActivity::class.java)) }, Modifier.weight(1f))
                    V3Shortcut("订阅与配置", state.config, Icons.Rounded.CloudDownload, { context.startActivity(Intent(context, ProxySubscriptionActivity::class.java)) }, Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    V3Shortcut("基础配置", "核心 · 模式 · IPv6", Icons.Rounded.Tune, { context.startActivity(Intent(context, RootTproxyActivity::class.java)) }, Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun V3Settings(state: ProxyComposeState) {
    val context = LocalContext.current
    val t = LocalBichenTokens.current
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { V3TopBar("设置") }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                V3Heading("运行参数", "当前代理运行环境")
                V3ListCard {
                    V3Setting("核心", state.core); V3Divider()
                    V3Setting("模式", state.mode); V3Divider()
                    V3Setting("IPv6", state.ipv6); V3Divider()
                    V3Setting("配置", state.config)
                }
            }
        }
        item {
            Surface(onClick = { context.startActivity(Intent(context, RootTproxyActivity::class.java)) }, shape = RoundedCornerShape(20.dp), color = t.cardBackground, shadowElevation = 0.dp) {
                Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                    V3IconBox(Icons.Rounded.Tune)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("高级代理设置", color = t.textPrimary, style = MaterialTheme.typography.titleSmall)
                        Text("切换核心、模式、IPv6 与配置", color = t.textSecondary, style = MaterialTheme.typography.bodySmall)
                    }
                    Icon(Icons.Rounded.ChevronRight, null, tint = t.textSecondary, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

@Composable
private fun V3DetailBar(title: String, onBack: () -> Unit, onRefresh: () -> Unit) {
    val t = LocalBichenTokens.current
    Row(Modifier.fillMaxWidth().statusBarsPadding().heightIn(min = 64.dp).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        V3HeaderButton(Icons.AutoMirrored.Rounded.ArrowBack, "返回", onBack, tint = t.textPrimary)
        Text(title, Modifier.weight(1f), color = t.textPrimary, fontSize = 22.sp, lineHeight = 30.sp, fontWeight = FontWeight.SemiBold)
        V3HeaderButton(Icons.Rounded.Refresh, "刷新", onRefresh)
    }
}

@Composable
private fun V3TopBar(title: String) {
    val t = LocalBichenTokens.current
    Row(Modifier.fillMaxWidth().statusBarsPadding().heightIn(min = 64.dp).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = t.textPrimary, fontSize = 26.sp, lineHeight = 34.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun V3HeaderButton(icon: ImageVector, description: String, onClick: () -> Unit, loading: Boolean = false, tint: Color = MaterialTheme.colorScheme.primary) {
    val t = LocalBichenTokens.current
    Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
        Surface(modifier = Modifier.size(44.dp), shape = CircleShape, color = t.cardBackground, shadowElevation = 0.dp) {
            IconButton(onClick = onClick, enabled = !loading, modifier = Modifier.fillMaxSize()) {
                if (loading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = tint)
                else Icon(icon, description, tint = tint, modifier = Modifier.size(21.dp))
            }
        }
    }
}

@Composable
private fun V3Status(running: Boolean) {
    val t = LocalBichenTokens.current
    Surface(shape = CircleShape, color = t.cardBackground) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(6.dp).background(if (running) t.success else t.warning, CircleShape))
            Spacer(Modifier.width(6.dp))
            Text(if (running) "代理运行中" else "代理已停止", color = t.textPrimary, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun V3Heading(title: String, subtitle: String? = null, modifier: Modifier = Modifier) {
    val t = LocalBichenTokens.current
    Column(modifier.fillMaxWidth().padding(horizontal = 2.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(title, color = t.textPrimary, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        subtitle?.let { Text(it, color = t.textSecondary, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun V3Card(content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = RoundedCornerShape(20.dp), color = LocalBichenTokens.current.cardBackground, shadowElevation = 0.dp) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

@Composable
private fun V3ListCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = RoundedCornerShape(20.dp), color = LocalBichenTokens.current.cardBackground, shadowElevation = 0.dp) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), content = content)
    }
}

@Composable
private fun V3Shortcut(title: String, subtitle: String, icon: ImageVector, onClick: () -> Unit, modifier: Modifier) {
    val t = LocalBichenTokens.current
    Surface(onClick = onClick, modifier = modifier, shape = RoundedCornerShape(20.dp), color = t.cardBackground, shadowElevation = 0.dp) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            V3IconBox(icon)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, color = t.textPrimary, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(subtitle, color = t.textSecondary, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun V3IconBox(icon: ImageVector, size: Dp = 44.dp) {
    val t = LocalBichenTokens.current
    Surface(shape = RoundedCornerShape(15.dp), color = t.elevatedCardBackground, modifier = Modifier.size(size)) {
        Box(contentAlignment = Alignment.Center) { Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(21.dp)) }
    }
}

@Composable
private fun V3Metric(value: String, label: String, modifier: Modifier) {
    val t = LocalBichenTokens.current
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(value, color = t.textPrimary, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(label, color = t.textSecondary, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun V3LatencyMetric(value: String, testing: Boolean, modifier: Modifier) {
    val t = LocalBichenTokens.current
    val scheme = MaterialTheme.colorScheme
    val motion = rememberInfiniteTransition(label = "global-latency-motion")
    val rotation by motion.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(animation = tween(900, easing = LinearEasing)),
        label = "global-latency-spin",
    )
    val pulse by motion.animateFloat(
        initialValue = .35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(animation = tween(560), repeatMode = RepeatMode.Reverse),
        label = "global-latency-pulse",
    )
    Column(modifier, verticalArrangement = Arrangement.Center) {
        Crossfade(targetState = testing, animationSpec = tween(150), label = "global-latency-state") { active ->
            if (active) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    Icon(Icons.Rounded.Refresh, "正在测试当前节点", tint = scheme.primary, modifier = Modifier.size(15.dp).rotate(rotation))
                    Text("测速中", color = scheme.primary, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                    Box(Modifier.size(5.dp).alpha(pulse).background(scheme.primary, CircleShape))
                }
            } else {
                Crossfade(targetState = value, animationSpec = tween(140), label = "global-latency-result") { display ->
                    Text(display, color = t.textPrimary, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        Text(if (testing) "正在测试当前节点" else "延迟 · 点按测速", color = t.textSecondary, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun V3SmallAction(icon: ImageVector, text: String, onClick: () -> Unit) {
    val t = LocalBichenTokens.current
    Surface(onClick = onClick, shape = RoundedCornerShape(16.dp), color = t.cardBackground, shadowElevation = 0.dp) {
        Row(Modifier.heightIn(min = 44.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(5.dp))
            Text(text, color = t.textPrimary, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun V3Search(value: String, onValueChange: (String) -> Unit, placeholder: String) {
    val t = LocalBichenTokens.current
    val scheme = MaterialTheme.colorScheme
    Surface(shape = RoundedCornerShape(18.dp), color = t.cardBackground, shadowElevation = 0.dp) {
        Row(Modifier.fillMaxWidth().heightIn(min = 50.dp).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Search, null, tint = t.textSecondary, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.weight(1f),
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = t.textPrimary),
                cursorBrush = SolidColor(scheme.primary),
                decorationBox = { inner -> Box { if (value.isBlank()) Text(placeholder, color = t.textSecondary, style = MaterialTheme.typography.bodyMedium); inner() } },
            )
            if (value.isNotBlank()) IconButton(onClick = { onValueChange("") }, modifier = Modifier.size(40.dp)) { Icon(Icons.Rounded.Close, "清除搜索", tint = t.textSecondary, modifier = Modifier.size(18.dp)) }
        }
    }
}

@Composable
private fun V3Empty(icon: ImageVector, title: String, subtitle: String) {
    val t = LocalBichenTokens.current
    Surface(shape = RoundedCornerShape(20.dp), color = t.cardBackground, shadowElevation = 0.dp) {
        Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            V3IconBox(icon, 40.dp); Spacer(Modifier.width(12.dp))
            Column { Text(title, color = t.textPrimary, style = MaterialTheme.typography.titleSmall); Text(subtitle, color = t.textSecondary, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
private fun V3Notice(text: String) {
    val scheme = MaterialTheme.colorScheme
    Surface(shape = RoundedCornerShape(18.dp), color = scheme.errorContainer) {
        Row(Modifier.fillMaxWidth().padding(13.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.ErrorOutline, null, tint = scheme.error, modifier = Modifier.size(19.dp)); Spacer(Modifier.width(8.dp))
            Text(text, color = scheme.onErrorContainer, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun V3Setting(label: String, value: String) {
    val t = LocalBichenTokens.current
    Row(Modifier.fillMaxWidth().heightIn(min = 58.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = t.textPrimary, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(value, color = t.textSecondary, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 190.dp))
    }
}

@Composable
private fun V3Divider() { HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .36f)) }

@Composable
private fun V3GroupIcon(group: ProxyGroupUi, size: Dp) {
    val t = LocalBichenTokens.current
    val bitmap = remember(group.iconPath) { group.iconPath.takeIf { it.isNotBlank() && File(it).isFile }?.let { runCatching { BitmapFactory.decodeFile(it) }.getOrNull() } }
    if (bitmap != null) Image(bitmap.asImageBitmap(), group.name, Modifier.size(size).clip(RoundedCornerShape(10.dp)), contentScale = ContentScale.Fit)
    else Box(Modifier.size(size).clip(RoundedCornerShape(10.dp)).background(t.elevatedCardBackground), contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Hub, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(size * .62f)) }
}

private fun v3Bytes(value: Long): String {
    if (value <= 0L) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var v = value.toDouble(); var i = 0
    while (v >= 1024.0 && i < units.lastIndex) { v /= 1024.0; i++ }
    return if (i == 0) "${v.toLong()} ${units[i]}" else String.format(Locale.US, "%.1f %s", v, units[i])
}
private fun v3Speed(value: Long) = "${v3Bytes(value)}/s"
private fun v3Duration(seconds: Long): String {
    if (seconds <= 0L) return "0s"
    val days = seconds / 86_400; val hours = (seconds % 86_400) / 3_600; val minutes = (seconds % 3_600) / 60
    return when { days > 0 -> "${days}d ${hours}h"; hours > 0 -> "${hours}h ${minutes}m"; minutes > 0 -> "${minutes}m ${seconds % 60}s"; else -> "${seconds}s" }
}
private fun v3Date(epoch: Long): String = runCatching { SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(epoch * 1000L)) }.getOrDefault("—")
private fun v3ShortTime(raw: String): String = raw.replace('T', ' ').substringBefore('.').removeSuffix("Z").take(16)
