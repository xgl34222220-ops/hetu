package io.github.xgl34222220.bichen

import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
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
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Proxy workspace rebuilt around the real LuoShu compact visual hierarchy. */
@OptIn(ExperimentalMaterial3Api::class)
class LuoShuProxyV2Activity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { BichenTheme { ProxyV2Shell { finish() } } }
    }
}

private enum class V2Page { Home, Panel, Tools, Settings }
private enum class V2Tab(val title: String) {
    Overview("概览"), Nodes("节点"), Subscriptions("订阅"), Connections("连接"), Rules("规则"), RuleSets("规则集")
}
private enum class V2Sort { Config, Delay }
private data class V2Rate(val up: Long, val down: Long)

@Composable
private fun ProxyV2Shell(onBack: () -> Unit) {
    val context = LocalContext.current
    val controller = remember { ProxyComposeController(context) }
    val repo = remember { ProxyDashboardRepository(context) }
    val inspector = remember { ProxyRuntimeInspector(context) }
    val scope = rememberCoroutineScope()
    val haze = rememberHazeState()
    var page by rememberSaveable { mutableStateOf(V2Page.Home) }
    var state by remember { mutableStateOf(ProxyComposeState()) }
    var runtime by remember { mutableStateOf(ProxyRuntimeSnapshot()) }
    var cpuPercent by remember { mutableFloatStateOf(0f) }
    var lastProcessTicks by remember { mutableLongStateOf(0L) }
    var lastSystemTicks by remember { mutableLongStateOf(0L) }
    var operation by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var providers by remember { mutableStateOf<List<DashboardProviderUi>>(emptyList()) }
    var logText by remember { mutableStateOf<String?>(null) }
    var testingAll by remember { mutableStateOf(false) }
    val delays = remember { mutableStateMapOf<String, Long>() }
    val rates = remember { mutableStateListOf<V2Rate>() }
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
                rates += V2Rate(upRate, downRate)
                while (rates.size > 36) rates.removeAt(0)
            }
            next.groups.flatMap { it.nodes }.forEach { node ->
                node.lastDelay?.takeIf { it > 0L }?.let { if (node.name !in delays) delays[node.name] = it }
            }
            if (next.running) {
                runCatching { inspector.sample() }.getOrNull()?.let { sample ->
                    if (sample.processTicks >= lastProcessTicks && sample.systemTicks > lastSystemTicks && lastSystemTicks > 0L) {
                        val processDelta = sample.processTicks - lastProcessTicks
                        val systemDelta = sample.systemTicks - lastSystemTicks
                        val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
                        cpuPercent = ((processDelta.toDouble() / systemDelta.toDouble()) * cores * 100.0)
                            .toFloat().coerceIn(0f, 999f)
                    }
                    lastProcessTicks = sample.processTicks
                    lastSystemTicks = sample.systemTicks
                    runtime = sample
                }
            } else {
                runtime = ProxyRuntimeSnapshot()
                cpuPercent = 0f
                lastProcessTicks = 0L
                lastSystemTicks = 0L
                providers = emptyList()
            }
            lastAt = now
            lastUp = next.uploadTotal
            lastDown = next.downloadTotal
            state = next
            message = next.message
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (e: Exception) {
            message = e.message ?: "状态读取失败"
        }
    }

    fun toggleProxy() {
        if (operation.isNotBlank()) return
        scope.launch {
            operation = if (state.running) "正在停止…" else "正在启动…"
            try {
                if (state.running) controller.stop { operation = it } else controller.start { operation = it }
                delay(280)
                refresh()
            } catch (e: Exception) {
                message = e.message ?: "操作失败"
            } finally { operation = "" }
        }
    }

    fun restart() {
        if (operation.isNotBlank() || !state.running) return
        scope.launch {
            operation = "正在重启…"
            try {
                controller.stop { operation = it }
                delay(180)
                controller.start { operation = it }
                delay(280)
                refresh()
            } catch (e: Exception) {
                message = e.message ?: "重启失败"
            } finally { operation = "" }
        }
    }

    fun reload() {
        if (operation.isNotBlank() || !state.running) return
        scope.launch {
            operation = "正在重载配置…"
            try {
                inspector.reloadConfig()
                message = "运行配置已重载"
                delay(220)
                refresh()
            } catch (e: Exception) {
                message = e.message ?: "配置重载失败"
            } finally { operation = "" }
        }
    }

    fun testAll() {
        if (testingAll || !state.running) return
        scope.launch {
            testingAll = true
            try { delays.putAll(repo.globalDelay()) }
            catch (e: Exception) { message = e.message ?: "测速失败" }
            finally { testingAll = false }
        }
    }

    fun showLog() {
        scope.launch { logText = runCatching { inspector.runtimeLog() }.getOrElse { it.message ?: "日志读取失败" } }
    }

    LaunchedEffect(Unit) {
        runCatching { repo.ensureIcons() }
        refresh()
        while (true) {
            delay(2500)
            refresh()
        }
    }
    LaunchedEffect(state.running) {
        if (!state.running) return@LaunchedEffect
        while (true) {
            providers = runCatching { repo.providers() }.getOrDefault(providers)
            delay(30_000)
        }
    }

    val dockItems = remember {
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
                V2Page.Home -> V2Home(
                    state = state,
                    runtime = runtime,
                    cpuPercent = cpuPercent,
                    delays = delays,
                    providers = providers,
                    upRate = upRate,
                    downRate = downRate,
                    operation = operation,
                    message = message,
                    testingAll = testingAll,
                    onBack = onBack,
                    onRefresh = { scope.launch { refresh() } },
                    onToggle = ::toggleProxy,
                    onReload = ::reload,
                    onRestart = ::restart,
                    onTestAll = ::testAll,
                    onWebUi = { context.startActivity(Intent(context, ProxyWebUiActivity::class.java)) },
                    onLog = ::showLog,
                    onPanel = { page = V2Page.Panel },
                )
                V2Page.Panel -> V2Panel(state, repo, delays, upRate, downRate, rates) { scope.launch { refresh() } }
                V2Page.Tools -> V2Tools(state)
                V2Page.Settings -> V2Settings(state)
            }
        }
        BichenGlassDock(
            items = dockItems,
            selected = page.ordinal,
            onSelect = { page = V2Page.entries[it] },
            hazeState = haze,
            backdrop = null,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
    logText?.let { V2LogDialog(it) { logText = null } }
}

@Composable
private fun V2Home(
    state: ProxyComposeState,
    runtime: ProxyRuntimeSnapshot,
    cpuPercent: Float,
    delays: Map<String, Long>,
    providers: List<DashboardProviderUi>,
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
    onWebUi: () -> Unit,
    onLog: () -> Unit,
    onPanel: () -> Unit,
) {
    val t = LocalBichenTokens.current
    val scheme = MaterialTheme.colorScheme
    val measured = delays.values.filter { it > 0L }
    val avgDelay = measured.takeIf { it.isNotEmpty() }?.average()?.toInt()?.let { "$it ms" } ?: "--"
    val memory = runtime.rssBytes.takeIf { it > 0L } ?: state.memoryBytes
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item { V2DetailBar("代理", onBack, onRefresh) }
        item {
            Surface(shape = RoundedCornerShape(28.dp), color = t.cardBackground, shadowElevation = 2.dp) {
                Column(
                    Modifier.fillMaxWidth()
                        .background(Brush.linearGradient(listOf(scheme.primaryContainer.copy(alpha = .46f), t.cardBackground)))
                        .padding(22.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        V2StatusPill(state.running, if (state.running) "代理运行中" else "代理已停止")
                        Spacer(Modifier.weight(1f))
                        Text("${state.core} · ${state.mode}", color = t.textSecondary, style = MaterialTheme.typography.labelMedium)
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text("当前配置", color = t.textSecondary, style = MaterialTheme.typography.bodySmall)
                        Text(
                            state.config,
                            color = t.textPrimary,
                            fontSize = 24.sp,
                            lineHeight = 32.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Row(verticalAlignment = Alignment.Bottom) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text("下载", color = t.textSecondary, style = MaterialTheme.typography.labelSmall)
                            Text(if (state.running) speed(downRate) else "—", color = scheme.primary, fontSize = 27.sp, lineHeight = 34.sp, fontWeight = FontWeight.SemiBold)
                        }
                        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text("上传", color = t.textSecondary, style = MaterialTheme.typography.labelSmall)
                            Text(if (state.running) speed(upRate) else "—", color = t.textPrimary, fontSize = 18.sp, lineHeight = 25.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        V2Metric(if (state.running) duration(runtime.elapsedSeconds) else "—", "运行时间", Modifier.weight(1f))
                        V2Metric(if (state.running) String.format(Locale.US, "%.1f%%", cpuPercent) else "—", "CPU", Modifier.weight(1f))
                        V2Metric(if (state.running) bytes(memory) else "—", "内存", Modifier.weight(1f))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        V2Metric(state.connections.size.toString(), "连接", Modifier.weight(1f))
                        V2Metric(runtime.lanAddress, "LAN", Modifier.weight(1.4f))
                        Box(
                            Modifier.weight(1f).heightIn(min = 48.dp).clip(RoundedCornerShape(14.dp)).clickable(enabled = state.running, onClick = onTestAll),
                            contentAlignment = Alignment.CenterStart,
                        ) {
                            V2Metric(if (testingAll) "…" else avgDelay, "延迟 · 点按测速", Modifier.fillMaxWidth())
                        }
                    }
                    Button(
                        onClick = onToggle,
                        enabled = operation.isBlank(),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp),
                        shape = RoundedCornerShape(18.dp),
                    ) {
                        Icon(if (state.running) Icons.Rounded.Stop else Icons.Rounded.PlayArrow, null, Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(if (state.running) "停止代理" else "启动代理", style = MaterialTheme.typography.labelLarge)
                    }
                    val note = operation.ifBlank { message }
                    if (note.isNotBlank()) Text(note, color = t.textSecondary, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (state.running) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    V2SectionHeading("快捷控制", "配置、核心与本机控制台")
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        V2Shortcut("重载配置", "重新载入运行副本", Icons.Rounded.Refresh, onReload, Modifier.weight(1f))
                        V2Shortcut("重启核心", "重启 Mihomo", Icons.Rounded.RestartAlt, onRestart, Modifier.weight(1f))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        V2Shortcut("WebUI", "MetaCubeXD", Icons.Rounded.Language, onWebUi, Modifier.weight(1f))
                        V2Shortcut("运行日志", "查看核心输出", Icons.Rounded.Article, onLog, Modifier.weight(1f))
                    }
                }
            }
        }
        if (state.running && providers.isNotEmpty()) item { V2SubscriptionSummary(providers) }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                V2SectionHeading("流量统计", "本次核心累计流量")
                V2Card {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        V2Metric(bytes(state.downloadTotal), "累计下载", Modifier.weight(1f))
                        V2Metric(bytes(state.uploadTotal), "累计上传", Modifier.weight(1f))
                        V2Metric(if (state.panelReady) "在线" else "等待", "控制接口", Modifier.weight(1f))
                    }
                }
            }
        }
        item {
            Surface(onClick = onPanel, shape = RoundedCornerShape(24.dp), color = t.cardBackground, shadowElevation = 1.dp) {
                Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                    V2IconBox(Icons.Rounded.Public)
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
private fun V2SubscriptionSummary(providers: List<DashboardProviderUi>) {
    val t = LocalBichenTokens.current
    val tracked = providers.filter { it.hasSubscriptionInfo && it.total > 0L }
    val used = tracked.sumOf { it.used }
    val total = tracked.sumOf { it.total }
    val remaining = (total - used).coerceAtLeast(0L)
    val ratio = if (total <= 0L) 0f else (used.toDouble() / total.toDouble()).toFloat().coerceIn(0f, 1f)
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        V2SectionHeading("订阅流量", "${providers.size} 个远程订阅")
        V2Card {
            if (tracked.isNotEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(bytes(remaining), color = t.textPrimary, fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold)
                        Text("剩余流量", color = t.textSecondary, style = MaterialTheme.typography.bodySmall)
                    }
                    Text("已用 ${bytes(used)} / ${bytes(total)}", color = t.textSecondary, style = MaterialTheme.typography.labelSmall)
                }
                LinearProgressIndicator(progress = { ratio }, modifier = Modifier.fillMaxWidth().height(5.dp).clip(CircleShape))
                tracked.mapNotNull { it.expire.takeIf { value -> value > 0L } }.minOrNull()?.let {
                    Text("最近到期 ${dateFromEpoch(it)}", color = t.textSecondary, style = MaterialTheme.typography.labelSmall)
                }
            } else {
                Text("订阅服务器未返回可用流量信息", color = t.textSecondary, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun V2Panel(
    state: ProxyComposeState,
    repo: ProxyDashboardRepository,
    delays: MutableMap<String, Long>,
    upRate: Long,
    downRate: Long,
    rates: List<V2Rate>,
    onRefreshState: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val t = LocalBichenTokens.current
    var tab by rememberSaveable { mutableStateOf(V2Tab.Nodes) }
    var sort by rememberSaveable { mutableStateOf(V2Sort.Config) }
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
    fun nodeDelay(node: ProxyNodeUi): Long? = delays[node.name] ?: node.lastDelay

    suspend fun loadTab() {
        if (!state.running) return
        try {
            when (tab) {
                V2Tab.Subscriptions -> providers = repo.providers()
                V2Tab.Rules -> rules = repo.rules()
                V2Tab.RuleSets -> ruleSets = repo.ruleSets()
                else -> Unit
            }
        } catch (e: Exception) { error = e.message ?: "读取失败" }
    }

    fun pullRefresh() {
        if (refreshing) return
        refreshing = true
        error = ""
        scope.launch {
            try {
                when (tab) {
                    V2Tab.Nodes -> delays.putAll(repo.globalDelay())
                    V2Tab.Subscriptions -> providers = repo.refreshSubscriptions()
                    V2Tab.RuleSets -> ruleSets = repo.refreshRuleSets()
                    V2Tab.Rules -> rules = repo.rules()
                    V2Tab.Connections, V2Tab.Overview -> onRefreshState()
                }
            } catch (e: Exception) { error = e.message ?: "刷新失败" }
            finally { refreshing = false }
        }
    }

    LaunchedEffect(tab, state.running) { error = ""; loadTab() }

    PullToRefreshBox(isRefreshing = refreshing, onRefresh = ::pullRefresh, modifier = Modifier.fillMaxSize()) {
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
                        V2HeaderAction(Icons.Rounded.Refresh, "刷新", ::pullRefresh, refreshing)
                    }
                    V2Tabs(tab) { tab = it }
                }
            }
            if (error.isNotBlank()) item { V2Notice(error, true) }
            if (!state.running) {
                item { V2Empty(Icons.Rounded.PowerSettingsNew, "代理未运行", "启动代理后显示实时数据") }
            } else when (tab) {
                V2Tab.Overview -> item { V2Overview(state, upRate, downRate, rates) }
                V2Tab.Nodes -> {
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            V2SectionHeading("策略组", "点策略组展开节点", Modifier.weight(1f))
                            V2SmallAction(Icons.Rounded.Sort, if (sort == V2Sort.Config) "配置" else "延迟") {
                                sort = if (sort == V2Sort.Config) V2Sort.Delay else V2Sort.Config
                            }
                        }
                    }
                    item { V2SearchField(query, { query = it }, "搜索策略组或节点") }
                    val base = if (sort == V2Sort.Config) state.groups else state.groups.sortedBy { g ->
                        g.nodes.firstOrNull { it.name == current(g) }?.let(::nodeDelay)?.takeIf { it > 0L } ?: Long.MAX_VALUE
                    }
                    val needle = query.trim()
                    val groups = base.mapNotNull { group ->
                        if (needle.isBlank()) group else {
                            val headerMatch = group.name.contains(needle, true) || current(group).contains(needle, true)
                            val nodes = group.nodes.filter { it.name.contains(needle, true) || it.type.contains(needle, true) }
                            when { headerMatch -> group; nodes.isNotEmpty() -> group.copy(nodes = nodes); else -> null }
                        }
                    }
                    if (groups.isEmpty()) item { V2Empty(Icons.Rounded.SearchOff, "没有匹配节点", "换个关键词试试") }
                    groups.chunked(2).forEachIndexed { rowIndex, pair ->
                        item(key = "v2-g$rowIndex-${pair.joinToString { it.name }}") {
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                pair.forEach { group ->
                                    V2GroupCard(
                                        group = group,
                                        selected = current(group),
                                        expanded = expanded == group.name,
                                        delay = group.nodes.firstOrNull { it.name == current(group) }?.let(::nodeDelay),
                                        measured = group.nodes.count { (nodeDelay(it) ?: 0L) > 0L },
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
                            item(key = "v2-expand-${group.name}") {
                                V2NodeGrid(
                                    group,
                                    current(group),
                                    delays,
                                    testing,
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
                V2Tab.Subscriptions -> {
                    item { V2SectionHeading("订阅", "下拉刷新全部远程订阅") }
                    item { V2ProviderList(providers) }
                }
                V2Tab.Connections -> {
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            V2SectionHeading("连接", "${state.connections.size} 条实时连接", Modifier.weight(1f))
                            if (state.connections.isNotEmpty()) V2SmallAction(Icons.Rounded.Close, "全部关闭") {
                                scope.launch { repo.closeAll(); onRefreshState() }
                            }
                        }
                    }
                    item { V2ConnectionList(state.connections) { id -> scope.launch { repo.closeConnection(id); onRefreshState() } } }
                }
                V2Tab.Rules -> {
                    item { V2SectionHeading("规则", "${rules.size} 条 · Mihomo /rules") }
                    item { V2RuleList(rules) }
                }
                V2Tab.RuleSets -> {
                    item { V2SectionHeading("规则集", "下拉刷新全部远程规则集") }
                    item { V2RuleSetList(ruleSets) }
                }
            }
        }
    }
}

@Composable
private fun V2Tabs(selected: V2Tab, onSelect: (V2Tab) -> Unit) {
    val t = LocalBichenTokens.current
    val scheme = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        V2Tab.entries.forEach { tab ->
            val on = tab == selected
            val fill by animateColorAsState(if (on) scheme.primaryContainer.copy(alpha = .58f) else Color.Transparent, label = "v2Tab")
            Box(
                Modifier.weight(1f).height(44.dp).clip(RoundedCornerShape(14.dp)).background(fill).clickable { onSelect(tab) },
                contentAlignment = Alignment.Center,
            ) {
                Text(tab.title, color = if (on) scheme.primary else t.textSecondary, fontSize = 12.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Medium, maxLines = 1)
            }
        }
    }
}

@Composable
private fun V2Overview(state: ProxyComposeState, up: Long, down: Long, rates: List<V2Rate>) {
    val t = LocalBichenTokens.current
    val scheme = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Surface(shape = RoundedCornerShape(28.dp), color = t.cardBackground, shadowElevation = 1.dp) {
            Column(
                Modifier.fillMaxWidth().background(Brush.linearGradient(listOf(scheme.primaryContainer.copy(alpha = .34f), t.cardBackground))).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Column(Modifier.weight(1f)) {
                        Text("实时下载", color = t.textSecondary, style = MaterialTheme.typography.bodySmall)
                        Text(speed(down), color = scheme.primary, fontSize = 27.sp, lineHeight = 34.sp, fontWeight = FontWeight.SemiBold)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("实时上传", color = t.textSecondary, style = MaterialTheme.typography.bodySmall)
                        Text(speed(up), color = t.textPrimary, fontSize = 19.sp, lineHeight = 26.sp, fontWeight = FontWeight.Medium)
                    }
                }
                V2SpeedTrend(rates, Modifier.fillMaxWidth().height(92.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    V2Metric(bytes(state.downloadTotal), "累计下载", Modifier.weight(1f))
                    V2Metric(bytes(state.uploadTotal), "累计上传", Modifier.weight(1f))
                    V2Metric(state.connections.size.toString(), "连接", Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun V2SpeedTrend(points: List<V2Rate>, modifier: Modifier) {
    val scheme = MaterialTheme.colorScheme
    Canvas(modifier) {
        if (points.size < 2) return@Canvas
        val max = points.maxOf { maxOf(it.up, it.down) }.coerceAtLeast(1L).toFloat()
        fun make(selector: (V2Rate) -> Long): Path {
            val p = Path()
            points.forEachIndexed { i, point ->
                val x = size.width * i / (points.size - 1).coerceAtLeast(1)
                val y = size.height - (selector(point) / max) * size.height
                if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
            }
            return p
        }
        drawPath(make { it.down }, scheme.primary, style = androidx.compose.ui.graphics.drawscope.Stroke(3.dp.toPx()))
        drawPath(make { it.up }, scheme.tertiary, style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()))
    }
}

@Composable
private fun V2GroupCard(
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
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) .975f else 1f, spring(dampingRatio = .76f, stiffness = 500f), label = "v2GroupScale")
    val fill by animateColorAsState(if (expanded) scheme.primaryContainer.copy(alpha = .46f) else t.cardBackground, label = "v2GroupFill")
    Surface(modifier = modifier.graphicsLayer { scaleX = scale; scaleY = scale }, shape = RoundedCornerShape(20.dp), color = fill, shadowElevation = 1.dp) {
        Column(
            Modifier.fillMaxWidth().clickable(interactionSource = interaction, indication = null, onClick = onExpand).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                V2GroupIcon(group, 32.dp)
                Spacer(Modifier.width(9.dp))
                Column(Modifier.weight(1f)) {
                    Text(group.name, color = t.textPrimary, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${group.type} · $measured/${group.nodes.size}", color = t.textSecondary, style = MaterialTheme.typography.labelSmall)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(selected.ifBlank { "未选择" }, color = t.textSecondary, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                V2Delay(delay, testing, onDelay)
            }
        }
    }
}

@Composable
private fun V2NodeGrid(
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
                    V2NodeCell(node, selected == node.name, delays[node.name] ?: node.lastDelay, testing[node.name] == true, { onSelect(node.name) }, { onDelay(node.name) }, Modifier.weight(1f))
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun V2NodeCell(node: ProxyNodeUi, selected: Boolean, delay: Long?, testing: Boolean, onSelect: () -> Unit, onDelay: () -> Unit, modifier: Modifier) {
    val t = LocalBichenTokens.current
    val scheme = MaterialTheme.colorScheme
    val fill by animateColorAsState(if (selected) scheme.primaryContainer.copy(alpha = .55f) else t.cardBackground, label = "v2Node")
    Surface(modifier = modifier.heightIn(min = 64.dp), shape = RoundedCornerShape(17.dp), color = fill, shadowElevation = if (selected) 1.dp else 0.dp) {
        Row(Modifier.fillMaxWidth().clickable(onClick = onSelect).padding(start = 11.dp, end = 5.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (selected) {
                Icon(Icons.Rounded.CheckCircle, "已选择", tint = scheme.primary, modifier = Modifier.size(17.dp))
                Spacer(Modifier.width(6.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(node.name, color = t.textPrimary, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(listOf(node.type, if (node.udp) "UDP" else "").filter { it.isNotBlank() }.joinToString(" · "), color = t.textSecondary, style = MaterialTheme.typography.labelSmall)
            }
            V2Delay(delay, testing, onDelay)
        }
    }
}

@Composable
private fun V2Delay(value: Long?, testing: Boolean, onClick: () -> Unit) {
    val t = LocalBichenTokens.current
    val scheme = MaterialTheme.colorScheme
    val text = when { testing -> "…"; value == null -> "--"; value <= 0L -> "超时"; else -> "$value ms" }
    val color = when {
        testing || value == null -> t.textSecondary
        value <= 0L -> scheme.error
        value <= 250L -> t.success
        value <= 600L -> scheme.primary
        value <= 1000L -> t.warning
        else -> scheme.error
    }
    Box(Modifier.heightIn(min = 44.dp).widthIn(min = 48.dp).clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Text(text, color = color, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
private fun V2ProviderList(items: List<DashboardProviderUi>) {
    val t = LocalBichenTokens.current
    if (items.isEmpty()) { V2Empty(Icons.Rounded.CloudOff, "没有远程订阅", "仅显示实际 HTTP 订阅 Provider"); return }
    V2ListCard {
        items.forEachIndexed { index, p ->
            if (index > 0) V2Divider()
            Column(Modifier.fillMaxWidth().padding(vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    V2IconBox(Icons.Rounded.CloudDownload, 40.dp)
                    Spacer(Modifier.width(11.dp))
                    Column(Modifier.weight(1f)) {
                        Text(p.name, color = t.textPrimary, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${p.nodes.size} 节点${p.updatedAt.takeIf { it.isNotBlank() }?.let { " · ${shortTime(it)}" } ?: ""}", color = t.textSecondary, style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (p.hasSubscriptionInfo && p.total > 0L) {
                    LinearProgressIndicator(progress = { p.ratio }, modifier = Modifier.fillMaxWidth().height(4.dp).clip(CircleShape))
                    Row {
                        Text("已用 ${bytes(p.used)}", color = t.textSecondary, style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
                        Text("剩余 ${bytes(p.remaining)}", color = t.textSecondary, style = MaterialTheme.typography.labelSmall)
                    }
                    Row {
                        Text("↑ ${bytes(p.upload)}", color = t.textSecondary, style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
                        Text("↓ ${bytes(p.download)}", color = t.textSecondary, style = MaterialTheme.typography.labelSmall)
                    }
                    if (p.expire > 0L) Text("到期 ${dateFromEpoch(p.expire)}", color = t.textSecondary, style = MaterialTheme.typography.labelSmall)
                } else Text("服务端未提供流量信息", color = t.textSecondary, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun V2ConnectionList(items: List<ProxyConnectionUi>, onClose: (String) -> Unit) {
    val t = LocalBichenTokens.current
    if (items.isEmpty()) { V2Empty(Icons.Rounded.LinkOff, "暂无活动连接", "新连接会实时出现在这里"); return }
    V2ListCard {
        items.forEachIndexed { index, c ->
            if (index > 0) V2Divider()
            Row(Modifier.fillMaxWidth().padding(vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                if (c.appIcon != null) Image(c.appIcon.asImageBitmap(), c.appName, Modifier.size(40.dp).clip(RoundedCornerShape(11.dp)), contentScale = ContentScale.Crop)
                else Box(Modifier.size(40.dp).clip(RoundedCornerShape(11.dp)).background(t.elevatedCardBackground), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Android, null, tint = t.textSecondary, modifier = Modifier.size(21.dp))
                }
                Spacer(Modifier.width(11.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(c.appName.ifBlank { c.process.ifBlank { c.host } }, color = t.textPrimary, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(c.host, color = t.textSecondary, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(listOf(c.network, c.inbound, c.rule, c.chain).filter { it.isNotBlank() }.joinToString(" · "), color = t.textSecondary, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("↓ ${bytes(c.download)}   ↑ ${bytes(c.upload)}", color = t.textSecondary, style = MaterialTheme.typography.labelSmall)
                }
                IconButton(onClick = { onClose(c.id) }, modifier = Modifier.size(48.dp)) { Icon(Icons.Rounded.Close, "关闭连接", tint = t.textSecondary) }
            }
        }
    }
}

@Composable
private fun V2RuleList(items: List<ProxyRuleUi>) {
    val t = LocalBichenTokens.current
    if (items.isEmpty()) { V2Empty(Icons.Rounded.Rule, "没有规则数据", "确认核心控制接口已就绪"); return }
    V2ListCard {
        items.forEachIndexed { index, r ->
            if (index > 0) V2Divider()
            Row(Modifier.fillMaxWidth().padding(vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.width(82.dp)) {
                    Text(r.type, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("#${r.index}", color = t.textSecondary, style = MaterialTheme.typography.labelSmall)
                }
                Column(Modifier.weight(1f)) {
                    Text(r.payload.ifBlank { "—" }, color = t.textPrimary, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (r.hitCount > 0L) Text("命中 ${r.hitCount}", color = t.textSecondary, style = MaterialTheme.typography.labelSmall)
                }
                Spacer(Modifier.width(8.dp))
                Text(r.proxy, color = t.textSecondary, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 90.dp))
            }
        }
    }
}

@Composable
private fun V2RuleSetList(items: List<DashboardRuleSetUi>) {
    val t = LocalBichenTokens.current
    if (items.isEmpty()) { V2Empty(Icons.Rounded.Inventory2, "没有规则集", "下拉可重新读取和更新远程规则集"); return }
    V2ListCard {
        items.forEachIndexed { index, r ->
            if (index > 0) V2Divider()
            Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                V2IconBox(Icons.Rounded.Inventory2, 40.dp)
                Spacer(Modifier.width(11.dp))
                Column(Modifier.weight(1f)) {
                    Text(r.name, color = t.textPrimary, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(listOf(r.behavior, r.format, r.vehicleType, if (r.ruleCount > 0) "${r.ruleCount} 条" else "").filter { it.isNotBlank() }.joinToString(" · "), color = t.textSecondary, style = MaterialTheme.typography.bodySmall)
                    if (r.updatedAt.isNotBlank()) Text(shortTime(r.updatedAt), color = t.textSecondary, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
private fun V2Tools(state: ProxyComposeState) {
    val context = LocalContext.current
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item { V2TopBar("工具") }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                V2SectionHeading("代理工具", "内核、配置与控制台")
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    V2Shortcut("内核管理", "${state.core} · 在线更新", Icons.Rounded.Memory, { context.startActivity(Intent(context, ProxyCoreActivity::class.java)) }, Modifier.weight(1f))
                    V2Shortcut("订阅配置", state.config, Icons.Rounded.CloudDownload, { context.startActivity(Intent(context, ProxySubscriptionActivity::class.java)) }, Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    V2Shortcut("WebUI", "MetaCubeXD", Icons.Rounded.Language, { context.startActivity(Intent(context, ProxyWebUiActivity::class.java)) }, Modifier.weight(1f))
                    V2Shortcut("基础配置", "核心 · 模式 · IPv6", Icons.Rounded.Tune, { context.startActivity(Intent(context, RootTproxyActivity::class.java)) }, Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun V2Settings(state: ProxyComposeState) {
    val context = LocalContext.current
    val t = LocalBichenTokens.current
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item { V2TopBar("设置") }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                V2SectionHeading("运行参数", "当前代理运行环境")
                V2ListCard {
                    V2SettingLine("核心", state.core)
                    V2Divider()
                    V2SettingLine("模式", state.mode)
                    V2Divider()
                    V2SettingLine("IPv6", state.ipv6)
                    V2Divider()
                    V2SettingLine("配置", state.config)
                }
            }
        }
        item {
            Surface(onClick = { context.startActivity(Intent(context, RootTproxyActivity::class.java)) }, shape = RoundedCornerShape(24.dp), color = t.cardBackground, shadowElevation = 1.dp) {
                Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                    V2IconBox(Icons.Rounded.Tune)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("高级代理设置", color = t.textPrimary, style = MaterialTheme.typography.titleSmall)
                        Text("切换核心、模式、IPv6 与配置", color = t.textSecondary, style = MaterialTheme.typography.bodySmall)
                    }
                    Icon(Icons.Rounded.ChevronRight, null, tint = t.textSecondary, modifier = Modifier.size(18.dp))
                }
            }
        }
        item { Text("运行参数修改后重启代理核心生效。", color = t.textSecondary, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun V2DetailBar(title: String, onBack: () -> Unit, onRefresh: () -> Unit) {
    val t = LocalBichenTokens.current
    Row(
        Modifier.fillMaxWidth().statusBarsPadding().heightIn(min = 64.dp).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        V2HeaderAction(Icons.AutoMirrored.Rounded.ArrowBack, "返回", onBack, tint = t.textPrimary)
        Text(title, Modifier.weight(1f), color = t.textPrimary, fontSize = 22.sp, lineHeight = 30.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        V2HeaderAction(Icons.Rounded.Refresh, "刷新", onRefresh)
    }
}

@Composable
private fun V2TopBar(title: String) {
    val t = LocalBichenTokens.current
    Row(Modifier.fillMaxWidth().statusBarsPadding().heightIn(min = 64.dp).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), color = t.textPrimary, fontSize = 26.sp, lineHeight = 34.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

@Composable
private fun V2HeaderAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    onClick: () -> Unit,
    loading: Boolean = false,
    tint: Color = MaterialTheme.colorScheme.primary,
) {
    val t = LocalBichenTokens.current
    Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
        Surface(modifier = Modifier.size(44.dp), shape = CircleShape, color = t.cardBackground, shadowElevation = 1.dp) {
            IconButton(onClick = onClick, enabled = !loading, modifier = Modifier.fillMaxSize()) {
                if (loading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = tint)
                else Icon(icon, description, tint = tint, modifier = Modifier.size(21.dp))
            }
        }
    }
}

@Composable
private fun V2StatusPill(running: Boolean, text: String) {
    val t = LocalBichenTokens.current
    Surface(shape = CircleShape, color = t.cardBackground.copy(alpha = .72f)) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(6.dp).background(if (running) t.success else t.warning, CircleShape))
            Spacer(Modifier.width(6.dp))
            Text(text, color = t.textPrimary, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun V2SectionHeading(title: String, subtitle: String? = null, modifier: Modifier = Modifier) {
    val t = LocalBichenTokens.current
    Column(modifier.fillMaxWidth().padding(horizontal = 2.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(title, color = t.textPrimary, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        subtitle?.takeIf { it.isNotBlank() }?.let { Text(it, color = t.textSecondary, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun V2Card(content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = RoundedCornerShape(24.dp), color = LocalBichenTokens.current.cardBackground, shadowElevation = 1.dp) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

@Composable
private fun V2ListCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = RoundedCornerShape(24.dp), color = LocalBichenTokens.current.cardBackground, shadowElevation = 1.dp) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), content = content)
    }
}

@Composable
private fun V2Shortcut(title: String, subtitle: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit, modifier: Modifier) {
    val t = LocalBichenTokens.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) .975f else 1f, spring(dampingRatio = .76f, stiffness = 520f), label = "v2Shortcut")
    Surface(
        onClick = onClick,
        interactionSource = interaction,
        modifier = modifier.graphicsLayer { scaleX = scale; scaleY = scale },
        shape = RoundedCornerShape(24.dp),
        color = t.cardBackground,
        shadowElevation = 1.dp,
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            V2IconBox(icon, 44.dp)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, color = t.textPrimary, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(subtitle, color = t.textSecondary, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun V2IconBox(icon: androidx.compose.ui.graphics.vector.ImageVector, size: androidx.compose.ui.unit.Dp = 44.dp) {
    val t = LocalBichenTokens.current
    Surface(shape = RoundedCornerShape(15.dp), color = t.elevatedCardBackground, modifier = Modifier.size(size)) {
        Box(contentAlignment = Alignment.Center) { Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(21.dp)) }
    }
}

@Composable
private fun V2Metric(value: String, label: String, modifier: Modifier) {
    val t = LocalBichenTokens.current
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(value, color = t.textPrimary, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(label, color = t.textSecondary, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun V2SmallAction(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, onClick: () -> Unit) {
    val t = LocalBichenTokens.current
    Surface(onClick = onClick, shape = RoundedCornerShape(16.dp), color = t.cardBackground, shadowElevation = 1.dp) {
        Row(Modifier.heightIn(min = 44.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(5.dp))
            Text(text, color = t.textPrimary, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun V2SearchField(value: String, onValueChange: (String) -> Unit, placeholder: String) {
    val t = LocalBichenTokens.current
    val scheme = MaterialTheme.colorScheme
    Surface(shape = RoundedCornerShape(18.dp), color = t.cardBackground, shadowElevation = 1.dp) {
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
                decorationBox = { inner ->
                    Box {
                        if (value.isBlank()) Text(placeholder, color = t.textSecondary, style = MaterialTheme.typography.bodyMedium)
                        inner()
                    }
                },
            )
            if (value.isNotBlank()) IconButton(onClick = { onValueChange("") }, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Rounded.Close, "清除搜索", tint = t.textSecondary, modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun V2Empty(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String) {
    val t = LocalBichenTokens.current
    Surface(shape = RoundedCornerShape(24.dp), color = t.cardBackground, shadowElevation = 1.dp) {
        Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            V2IconBox(icon, 40.dp)
            Spacer(Modifier.width(12.dp))
            Column { Text(title, color = t.textPrimary, style = MaterialTheme.typography.titleSmall); Text(subtitle, color = t.textSecondary, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
private fun V2Notice(text: String, error: Boolean) {
    val scheme = MaterialTheme.colorScheme
    Surface(shape = RoundedCornerShape(18.dp), color = if (error) scheme.errorContainer else LocalBichenTokens.current.cardBackground) {
        Row(Modifier.fillMaxWidth().padding(13.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (error) Icons.Rounded.ErrorOutline else Icons.Rounded.Info, null, tint = if (error) scheme.error else scheme.primary, modifier = Modifier.size(19.dp))
            Spacer(Modifier.width(8.dp))
            Text(text, color = if (error) scheme.onErrorContainer else LocalBichenTokens.current.textPrimary, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun V2SettingLine(label: String, value: String) {
    val t = LocalBichenTokens.current
    Row(Modifier.fillMaxWidth().heightIn(min = 58.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = t.textPrimary, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(value, color = t.textSecondary, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 190.dp))
    }
}

@Composable
private fun V2Divider() {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .36f))
}

@Composable
private fun V2GroupIcon(group: ProxyGroupUi, size: androidx.compose.ui.unit.Dp) {
    val t = LocalBichenTokens.current
    val bitmap = remember(group.iconPath) {
        group.iconPath.takeIf { it.isNotBlank() && File(it).isFile }?.let { runCatching { BitmapFactory.decodeFile(it) }.getOrNull() }
    }
    if (bitmap != null) Image(bitmap.asImageBitmap(), group.name, Modifier.size(size).clip(RoundedCornerShape(10.dp)), contentScale = ContentScale.Fit)
    else Box(Modifier.size(size).clip(RoundedCornerShape(10.dp)).background(t.elevatedCardBackground), contentAlignment = Alignment.Center) {
        Icon(Icons.Rounded.Hub, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(size * .62f))
    }
}

@Composable
private fun V2LogDialog(text: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(28.dp),
        title = { Text("Mihomo 运行日志") },
        text = { Box(Modifier.fillMaxWidth().heightIn(max = 520.dp).verticalScroll(rememberScrollState())) { Text(text, style = MaterialTheme.typography.bodySmall) } },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

private fun bytes(value: Long): String {
    if (value <= 0L) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var v = value.toDouble(); var i = 0
    while (v >= 1024.0 && i < units.lastIndex) { v /= 1024.0; i++ }
    return if (i == 0) "${v.toLong()} ${units[i]}" else String.format(Locale.US, "%.1f %s", v, units[i])
}

private fun duration(seconds: Long): String {
    if (seconds <= 0L) return "0s"
    val days = seconds / 86_400
    val hours = (seconds % 86_400) / 3_600
    val minutes = (seconds % 3_600) / 60
    return when {
        days > 0 -> "${days}d ${hours}h"
        hours > 0 -> "${hours}h ${minutes}m"
        minutes > 0 -> "${minutes}m ${seconds % 60}s"
        else -> "${seconds}s"
    }
}

private fun speed(value: Long): String = "${bytes(value)}/s"
private fun dateFromEpoch(epoch: Long): String = runCatching { SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(epoch * 1000L)) }.getOrDefault("—")
private fun shortTime(raw: String): String = raw.replace('T', ' ').substringBefore('.').removeSuffix("Z").take(16)
