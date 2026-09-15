package io.github.xgl34222220.bichen

import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
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

/**
 * Dense, LuoShu-first proxy workspace.
 *
 * UI UX Pro Max audit goals used here:
 * - section surfaces instead of card-per-row/card-inside-card
 * - minimum 48dp interaction targets while keeping information visually compact
 * - state is always expressed by text/icon as well as color
 * - six proxy tabs remain predictable and fit without horizontal page scrolling
 * - compact list rows for subscriptions, connections, rules and rule sets
 * - no runtime shader/haze source in the page; the shared dock uses its stable renderer
 */
@OptIn(ExperimentalMaterial3Api::class)
class ProMaxProxyActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { BichenTheme { ProMaxProxyShell { finish() } } }
    }
}

private enum class ProPage { Home, Panel, Tools, Settings }
private enum class ProTab(val title: String) {
    Overview("概览"), Nodes("节点"), Subscriptions("订阅"), Connections("连接"), Rules("规则"), RuleSets("规则集")
}
private enum class ProSort { Config, Delay }
private data class RatePoint(val up: Long, val down: Long)

@Composable
private fun ProMaxProxyShell(onBack: () -> Unit) {
    val context = LocalContext.current
    val controller = remember { ProxyComposeController(context) }
    val repo = remember { ProxyDashboardRepository(context) }
    val scope = rememberCoroutineScope()
    val haze = rememberHazeState()
    var page by rememberSaveable { mutableStateOf(ProPage.Home) }
    var state by remember { mutableStateOf(ProxyComposeState()) }
    var operation by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    val delays = remember { mutableStateMapOf<String, Long>() }
    val rates = remember { mutableStateListOf<RatePoint>() }
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
                rates += RatePoint(upRate, downRate)
                while (rates.size > 36) rates.removeAt(0)
            }
            next.groups.flatMap { it.nodes }.forEach { node ->
                node.lastDelay?.takeIf { it > 0 }?.let { if (node.name !in delays) delays[node.name] = it }
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

    fun startStop() {
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
        if (operation.isNotBlank()) return
        scope.launch {
            operation = "正在重启…"
            try {
                controller.stop { operation = it }
                delay(200)
                controller.start { operation = it }
                delay(300)
                refresh()
            } catch (e: Exception) {
                message = e.message ?: "重启失败"
            } finally { operation = "" }
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
                ProPage.Home -> ProHome(
                    state = state,
                    delays = delays,
                    operation = operation,
                    message = message,
                    onBack = onBack,
                    onRefresh = { scope.launch { refresh() } },
                    onStartStop = ::startStop,
                    onRestart = ::restart,
                    onPanel = { page = ProPage.Panel },
                )
                ProPage.Panel -> ProPanel(
                    state = state,
                    repo = repo,
                    delays = delays,
                    upRate = upRate,
                    downRate = downRate,
                    rates = rates,
                    onRefreshState = { scope.launch { refresh() } },
                )
                ProPage.Tools -> ProTools(state)
                ProPage.Settings -> ProSettings(state, controller) { scope.launch { refresh() } }
            }
        }
        BichenGlassDock(
            items = dock,
            selected = page.ordinal,
            onSelect = { page = ProPage.entries[it] },
            hazeState = haze,
            backdrop = null,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

@Composable
private fun ProHome(
    state: ProxyComposeState,
    delays: Map<String, Long>,
    operation: String,
    message: String,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onStartStop: () -> Unit,
    onRestart: () -> Unit,
    onPanel: () -> Unit,
) {
    val t = LocalBichenTokens.current
    val scheme = MaterialTheme.colorScheme
    val values = delays.values.filter { it > 0 }
    val avg = values.takeIf { it.isNotEmpty() }?.average()?.toInt()?.let { "$it ms" } ?: "--"
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 0.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            ProTopBar("代理", "${state.core} · ${state.mode}", onBack = onBack, onRefresh = onRefresh)
        }
        item {
            Surface(shape = RoundedCornerShape(28.dp), color = t.cardBackground, shadowElevation = 2.dp) {
                Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(17.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StateBadge(state.running, if (state.running) "运行中" else "已停止")
                        Spacer(Modifier.weight(1f))
                        Text(if (state.panelReady) "控制接口在线" else "控制接口等待", color = t.textSecondary, style = MaterialTheme.typography.labelMedium)
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("当前配置", color = t.textSecondary, style = MaterialTheme.typography.bodySmall)
                        Text(state.config, color = t.textPrimary, fontSize = 23.sp, lineHeight = 30.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ProMetric(bytes(state.memoryBytes), "内存", Modifier.weight(1f))
                        ProMetric(avg, "延迟", Modifier.weight(1f))
                        ProMetric(state.connections.size.toString(), "连接", Modifier.weight(1f))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ProMetric(bytes(state.downloadTotal), "下载", Modifier.weight(1f))
                        ProMetric(bytes(state.uploadTotal), "上传", Modifier.weight(1f))
                    }
                    Button(onClick = onStartStop, enabled = operation.isBlank(), modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp), shape = RoundedCornerShape(18.dp)) {
                        Icon(if (state.running) Icons.Rounded.Stop else Icons.Rounded.PlayArrow, null, Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(if (state.running) "停止代理" else "启动代理")
                    }
                    if (state.running) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ProTextAction("重载状态", Icons.Rounded.Refresh, onRefresh, Modifier.weight(1f))
                            ProTextAction("重启核心", Icons.Rounded.RestartAlt, onRestart, Modifier.weight(1f))
                        }
                    }
                    val note = operation.ifBlank { message }
                    if (note.isNotBlank()) Text(note, color = t.textSecondary, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionHeading("代理工作台", "节点、订阅、连接、规则都在一个面板里")
                FlatActionRow(Icons.Rounded.Public, "打开面板", "高密度视图 · 实时状态", onPanel)
            }
        }
        item { Spacer(Modifier.height(8.dp)) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProPanel(
    state: ProxyComposeState,
    repo: ProxyDashboardRepository,
    delays: MutableMap<String, Long>,
    upRate: Long,
    downRate: Long,
    rates: List<RatePoint>,
    onRefreshState: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val t = LocalBichenTokens.current
    var tab by rememberSaveable { mutableStateOf(ProTab.Nodes) }
    var sort by rememberSaveable { mutableStateOf(ProSort.Config) }
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
                ProTab.Subscriptions -> providers = repo.providers()
                ProTab.Rules -> rules = repo.rules()
                ProTab.RuleSets -> ruleSets = repo.ruleSets()
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
                    ProTab.Nodes -> delays.putAll(repo.globalDelay())
                    ProTab.Subscriptions -> providers = repo.refreshSubscriptions()
                    ProTab.RuleSets -> ruleSets = repo.refreshRuleSets()
                    ProTab.Rules -> rules = repo.rules()
                    ProTab.Connections, ProTab.Overview -> onRefreshState()
                }
            } catch (e: Exception) { error = e.message ?: "刷新失败" }
            finally { refreshing = false }
        }
    }

    LaunchedEffect(tab, state.running) {
        error = ""
        loadTab()
    }

    PullToRefreshBox(isRefreshing = refreshing, onRefresh = ::pullRefresh, modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Column(Modifier.statusBarsPadding().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("面板", color = t.textPrimary, fontSize = 26.sp, lineHeight = 34.sp, fontWeight = FontWeight.Bold)
                            Text(if (state.running) "${state.groups.size} 个策略组 · ${state.connections.size} 条连接" else "代理未运行", color = t.textSecondary, style = MaterialTheme.typography.bodySmall)
                        }
                        HeaderIcon(Icons.Rounded.Refresh, "刷新", ::pullRefresh, loading = refreshing)
                    }
                    ProTabStrip(tab) { tab = it }
                }
            }
            if (error.isNotBlank()) item { InlineNotice(error, true) }
            if (!state.running) {
                item { EmptySection(Icons.Rounded.PowerSettingsNew, "代理未运行", "启动代理后显示实时数据") }
            } else when (tab) {
                ProTab.Overview -> {
                    item { OverviewSection(state, upRate, downRate, rates) }
                }
                ProTab.Nodes -> {
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            SectionHeading("策略组", "点延迟值测速 · 点策略组展开节点", Modifier.weight(1f))
                            TextButton(onClick = { sort = if (sort == ProSort.Config) ProSort.Delay else ProSort.Config }, modifier = Modifier.heightIn(min = 48.dp)) {
                                Icon(Icons.Rounded.Sort, null, Modifier.size(18.dp)); Spacer(Modifier.width(5.dp)); Text(if (sort == ProSort.Config) "配置顺序" else "延迟排序")
                            }
                        }
                    }
                    val groups = if (sort == ProSort.Config) state.groups else state.groups.sortedBy { g ->
                        g.nodes.firstOrNull { it.name == current(g) }?.let(::nodeDelay)?.takeIf { it > 0 } ?: Long.MAX_VALUE
                    }
                    groups.chunked(2).forEachIndexed { rowIndex, pair ->
                        item(key = "g$rowIndex-${pair.joinToString { it.name }}") {
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                pair.forEach { group ->
                                    GroupTile(
                                        group = group,
                                        selected = current(group),
                                        expanded = expanded == group.name,
                                        delay = group.nodes.firstOrNull { it.name == current(group) }?.let(::nodeDelay),
                                        measured = group.nodes.count { (nodeDelay(it) ?: 0) > 0 },
                                        testing = testing[current(group)] == true,
                                        onExpand = { expanded = if (expanded == group.name) null else group.name },
                                        onDelay = {
                                            val node = current(group)
                                            if (node.isNotBlank() && testing[node] != true) scope.launch {
                                                testing[node] = true
                                                try { delays[node] = repo.delay(node) } catch (_: Exception) { delays[node] = -1 }
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
                            item(key = "expanded-${group.name}") {
                                NodeSection(
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
                                            try { delays[node] = repo.delay(node) } catch (_: Exception) { delays[node] = -1 }
                                            finally { testing.remove(node) }
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
                ProTab.Subscriptions -> {
                    item { SectionHeading("订阅", "下拉刷新全部远程订阅") }
                    item { ProviderSection(providers) }
                }
                ProTab.Connections -> {
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            SectionHeading("连接", "${state.connections.size} 条实时连接", Modifier.weight(1f))
                            if (state.connections.isNotEmpty()) TextButton(onClick = { scope.launch { repo.closeAll(); onRefreshState() } }, modifier = Modifier.heightIn(min = 48.dp)) { Text("全部关闭") }
                        }
                    }
                    item { ConnectionSection(state.connections) { id -> scope.launch { repo.closeConnection(id); onRefreshState() } } }
                }
                ProTab.Rules -> {
                    item { SectionHeading("规则", "${rules.size} 条 · 来自 Mihomo /rules") }
                    item { RuleSection(rules) }
                }
                ProTab.RuleSets -> {
                    item { SectionHeading("规则集", "下拉刷新全部远程规则集") }
                    item { RuleSetSection(ruleSets) }
                }
            }
        }
    }
}

@Composable
private fun ProTabStrip(selected: ProTab, onSelect: (ProTab) -> Unit) {
    val t = LocalBichenTokens.current
    val scheme = MaterialTheme.colorScheme
    Surface(shape = RoundedCornerShape(18.dp), color = t.elevatedCardBackground.copy(alpha = .72f)) {
        Row(Modifier.fillMaxWidth().padding(4.dp)) {
            ProTab.entries.forEach { tab ->
                val on = selected == tab
                val color by animateColorAsState(if (on) scheme.primaryContainer else Color.Transparent, label = "tab")
                Box(
                    Modifier.weight(1f).heightIn(min = 44.dp).clip(RoundedCornerShape(14.dp)).background(color).clickable { onSelect(tab) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(tab.title, color = if (on) scheme.primary else t.textSecondary, fontSize = 12.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Medium, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun OverviewSection(state: ProxyComposeState, up: Long, down: Long, rates: List<RatePoint>) {
    val t = LocalBichenTokens.current
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            BigMetric("↓ ${speed(down)}", "实时下载", Modifier.weight(1f))
            BigMetric("↑ ${speed(up)}", "实时上传", Modifier.weight(1f))
        }
        Surface(shape = RoundedCornerShape(24.dp), color = t.cardBackground) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ProMetric(bytes(state.downloadTotal), "累计下载", Modifier.weight(1f))
                    ProMetric(bytes(state.uploadTotal), "累计上传", Modifier.weight(1f))
                    ProMetric(state.connections.size.toString(), "连接", Modifier.weight(1f))
                }
                Text("近实时流量", color = t.textPrimary, style = MaterialTheme.typography.titleSmall)
                SpeedTrend(rates, Modifier.fillMaxWidth().height(100.dp))
            }
        }
    }
}

@Composable
private fun SpeedTrend(points: List<RatePoint>, modifier: Modifier) {
    val scheme = MaterialTheme.colorScheme
    Canvas(modifier) {
        if (points.size < 2) return@Canvas
        val max = points.maxOf { maxOf(it.up, it.down) }.coerceAtLeast(1L).toFloat()
        fun pathFor(selector: (RatePoint) -> Long): Path {
            val path = Path()
            points.forEachIndexed { i, p ->
                val x = size.width * i / (points.size - 1).coerceAtLeast(1)
                val y = size.height - (selector(p) / max) * size.height
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            return path
        }
        drawPath(pathFor { it.down }, scheme.primary, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3.dp.toPx()))
        drawPath(pathFor { it.up }, scheme.tertiary, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx()))
    }
}

@Composable
private fun GroupTile(
    group: ProxyGroupUi,
    selected: String,
    expanded: Boolean,
    delay: Long?,
    measured: Int,
    testing: Boolean,
    onExpand: () -> Unit,
    onDelay: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val t = LocalBichenTokens.current
    val scheme = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) .975f else 1f, spring(dampingRatio = .7f, stiffness = 500f), label = "groupScale")
    val fill by animateColorAsState(if (expanded) scheme.primaryContainer.copy(alpha = .50f) else t.cardBackground, label = "groupFill")
    Surface(
        modifier = modifier.graphicsLayer { scaleX = scale; scaleY = scale },
        shape = RoundedCornerShape(22.dp), color = fill, shadowElevation = if (expanded) 1.dp else 0.dp,
    ) {
        Column(
            Modifier.fillMaxWidth().clickable(interactionSource = interaction, indication = null, onClick = onExpand).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                GroupIcon(group, 30.dp)
                Spacer(Modifier.width(9.dp))
                Column(Modifier.weight(1f)) {
                    Text(group.name, color = t.textPrimary, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${group.type} · $measured/${group.nodes.size}", color = t.textSecondary, style = MaterialTheme.typography.labelSmall)
                }
                Icon(if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null, tint = t.textSecondary, modifier = Modifier.size(18.dp))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(selected.ifBlank { "未选择" }, color = t.textSecondary, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                DelayValue(delay, testing, onDelay)
            }
        }
    }
}

@Composable
private fun NodeSection(
    group: ProxyGroupUi,
    selected: String,
    delays: Map<String, Long>,
    testing: Map<String, Boolean>,
    onSelect: (String) -> Unit,
    onDelay: (String) -> Unit,
) {
    val t = LocalBichenTokens.current
    Surface(shape = RoundedCornerShape(24.dp), color = t.elevatedCardBackground.copy(alpha = .52f)) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(group.name, Modifier.padding(horizontal = 4.dp, vertical = 2.dp), color = t.textSecondary, style = MaterialTheme.typography.labelMedium)
            group.nodes.chunked(2).forEach { pair ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    pair.forEach { node -> NodeTile(node, selected == node.name, delays[node.name] ?: node.lastDelay, testing[node.name] == true, { onSelect(node.name) }, { onDelay(node.name) }, Modifier.weight(1f)) }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun NodeTile(node: ProxyNodeUi, selected: Boolean, delay: Long?, testing: Boolean, onSelect: () -> Unit, onDelay: () -> Unit, modifier: Modifier) {
    val t = LocalBichenTokens.current
    val scheme = MaterialTheme.colorScheme
    Surface(
        modifier = modifier.heightIn(min = 66.dp), shape = RoundedCornerShape(17.dp),
        color = if (selected) scheme.primaryContainer.copy(alpha = .52f) else Color.Transparent,
    ) {
        Row(Modifier.fillMaxWidth().clickable(onClick = onSelect).padding(start = 12.dp, end = 7.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (selected) { Icon(Icons.Rounded.CheckCircle, "已选择", tint = scheme.primary, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(7.dp)) }
            Column(Modifier.weight(1f)) {
                Text(node.name, color = t.textPrimary, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(listOf(node.type, if (node.udp) "UDP" else "").filter { it.isNotBlank() }.joinToString(" · "), color = t.textSecondary, style = MaterialTheme.typography.labelSmall)
            }
            DelayValue(delay, testing, onDelay)
        }
    }
}

@Composable
private fun DelayValue(value: Long?, testing: Boolean, onClick: () -> Unit) {
    val t = LocalBichenTokens.current
    val scheme = MaterialTheme.colorScheme
    val text = when { testing -> "…"; value == null -> "--"; value <= 0 -> "超时"; else -> "$value ms" }
    val color = when { testing || value == null -> t.textSecondary; value <= 0 -> scheme.error; value <= 350 -> scheme.primary; value <= 600 -> t.success; value <= 900 -> t.warning; else -> scheme.error }
    Box(Modifier.heightIn(min = 44.dp).widthIn(min = 48.dp).clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Text(text, color = color, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
private fun ProviderSection(providers: List<DashboardProviderUi>) {
    val t = LocalBichenTokens.current
    if (providers.isEmpty()) { EmptySection(Icons.Rounded.CloudOff, "没有远程订阅", "仅显示实际 HTTP 订阅 Provider"); return }
    FlatListSurface {
        providers.forEachIndexed { index, p ->
            if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .45f))
            Column(Modifier.fillMaxWidth().padding(vertical = 13.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.CloudDownload, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(p.name, color = t.textPrimary, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${p.nodes.size} 节点${p.updatedAt.takeIf { it.isNotBlank() }?.let { " · ${shortTime(it)}" } ?: ""}", color = t.textSecondary, style = MaterialTheme.typography.bodySmall)
                    }
                    Text("HTTP", color = t.textSecondary, style = MaterialTheme.typography.labelSmall)
                }
                if (p.hasSubscriptionInfo && p.total > 0) {
                    LinearProgressIndicator(progress = { p.ratio }, modifier = Modifier.fillMaxWidth().height(4.dp).clip(CircleShape))
                    Row {
                        Text("已用 ${bytes(p.used)} / ${bytes(p.total)}", color = t.textSecondary, style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
                        Text("剩余 ${bytes(p.remaining)}", color = t.textSecondary, style = MaterialTheme.typography.labelSmall)
                    }
                    if (p.expire > 0) Text("到期 ${dateFromEpoch(p.expire)}", color = t.textSecondary, style = MaterialTheme.typography.labelSmall)
                } else Text("服务端未提供流量信息", color = t.textSecondary, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun ConnectionSection(connections: List<ProxyConnectionUi>, onClose: (String) -> Unit) {
    val t = LocalBichenTokens.current
    if (connections.isEmpty()) { EmptySection(Icons.Rounded.LinkOff, "暂无活动连接", "新连接会实时出现在这里"); return }
    FlatListSurface {
        connections.forEachIndexed { index, c ->
            if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .45f))
            Row(Modifier.fillMaxWidth().padding(vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                if (c.appIcon != null) Image(c.appIcon.asImageBitmap(), c.appName, Modifier.size(38.dp).clip(RoundedCornerShape(10.dp)), contentScale = ContentScale.Crop)
                else Box(Modifier.size(38.dp).clip(RoundedCornerShape(10.dp)).background(t.elevatedCardBackground), contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Android, null, tint = t.textSecondary, modifier = Modifier.size(21.dp)) }
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
private fun RuleSection(rules: List<ProxyRuleUi>) {
    val t = LocalBichenTokens.current
    if (rules.isEmpty()) { EmptySection(Icons.Rounded.Rule, "没有规则数据", "确认核心控制接口已就绪"); return }
    FlatListSurface {
        rules.forEachIndexed { index, r ->
            if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .45f))
            Row(Modifier.fillMaxWidth().padding(vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.width(88.dp)) {
                    Text(r.type, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("#${r.index}", color = t.textSecondary, style = MaterialTheme.typography.labelSmall)
                }
                Column(Modifier.weight(1f)) {
                    Text(r.payload.ifBlank { "—" }, color = t.textPrimary, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (r.hitCount > 0) Text("命中 ${r.hitCount}", color = t.textSecondary, style = MaterialTheme.typography.labelSmall)
                }
                Spacer(Modifier.width(8.dp))
                Text(r.proxy, color = t.textSecondary, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 92.dp))
            }
        }
    }
}

@Composable
private fun RuleSetSection(items: List<DashboardRuleSetUi>) {
    val t = LocalBichenTokens.current
    if (items.isEmpty()) { EmptySection(Icons.Rounded.Inventory2, "没有规则集", "下拉可重新读取和更新远程规则集"); return }
    FlatListSurface {
        items.forEachIndexed { index, r ->
            if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .45f))
            Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Inventory2, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
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
private fun ProTools(state: ProxyComposeState) {
    val context = LocalContext.current
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { ProSimpleTopBar("工具", "代理配置与内核") }
        item {
            FlatListSurface {
                FlatActionRow(Icons.Rounded.Memory, "内核管理", "${state.core} · 在线更新", { context.startActivity(Intent(context, ProxyCoreActivity::class.java)) }, insideList = true)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .45f))
                FlatActionRow(Icons.Rounded.CloudDownload, "订阅与配置", state.config, { context.startActivity(Intent(context, ProxySubscriptionActivity::class.java)) }, insideList = true)
            }
        }
    }
}

@Composable
private fun ProSettings(state: ProxyComposeState, controller: ProxyComposeController, onChanged: () -> Unit) {
    val context = LocalContext.current
    val t = LocalBichenTokens.current
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { ProSimpleTopBar("设置", "运行参数") }
        item {
            FlatListSurface {
                SettingLine("核心", state.core)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .45f))
                SettingLine("模式", state.mode)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .45f))
                SettingLine("IPv6", state.ipv6)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .45f))
                SettingLine("配置", state.config)
            }
        }
        item {
            FlatActionRow(Icons.Rounded.Tune, "高级代理设置", "切换核心、模式、IPv6 与配置", { context.startActivity(Intent(context, ProxyCoreActivity::class.java)); onChanged() })
        }
        item { Text("运行参数修改后建议重启代理核心生效。", color = t.textSecondary, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun ProTopBar(title: String, subtitle: String, onBack: () -> Unit, onRefresh: () -> Unit) {
    val t = LocalBichenTokens.current
    Row(Modifier.fillMaxWidth().statusBarsPadding().heightIn(min = 72.dp).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        HeaderIcon(Icons.AutoMirrored.Rounded.ArrowBack, "返回", onBack, tint = t.textPrimary)
        Spacer(Modifier.width(5.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = t.textPrimary, fontSize = 24.sp, lineHeight = 31.sp, fontWeight = FontWeight.Bold)
            Text(subtitle, color = t.textSecondary, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        HeaderIcon(Icons.Rounded.Refresh, "刷新", onRefresh)
    }
}

@Composable
private fun ProSimpleTopBar(title: String, subtitle: String) {
    val t = LocalBichenTokens.current
    Column(Modifier.fillMaxWidth().statusBarsPadding().heightIn(min = 72.dp).padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, color = t.textPrimary, fontSize = 26.sp, lineHeight = 34.sp, fontWeight = FontWeight.Bold)
        Text(subtitle, color = t.textSecondary, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun HeaderIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, onClick: () -> Unit, loading: Boolean = false, tint: Color = MaterialTheme.colorScheme.primary) {
    val t = LocalBichenTokens.current
    Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
        Surface(shape = CircleShape, color = t.cardBackground, shadowElevation = 1.dp, modifier = Modifier.size(44.dp)) {
            IconButton(onClick = onClick, enabled = !loading, modifier = Modifier.fillMaxSize()) {
                if (loading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = tint)
                else Icon(icon, description, tint = tint, modifier = Modifier.size(21.dp))
            }
        }
    }
}

@Composable
private fun StateBadge(ok: Boolean, text: String) {
    val t = LocalBichenTokens.current
    Surface(shape = CircleShape, color = t.elevatedCardBackground.copy(alpha = .74f)) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (ok) Icons.Rounded.CheckCircle else Icons.Rounded.PauseCircle, null, tint = if (ok) t.success else t.warning, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(5.dp)); Text(text, color = t.textPrimary, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun SectionHeading(title: String, subtitle: String? = null, modifier: Modifier = Modifier) {
    val t = LocalBichenTokens.current
    Column(modifier.padding(horizontal = 2.dp, vertical = 3.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, color = t.textPrimary, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        subtitle?.let { Text(it, color = t.textSecondary, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun FlatListSurface(content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = RoundedCornerShape(24.dp), color = LocalBichenTokens.current.cardBackground, shadowElevation = 0.dp) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), content = content)
    }
}

@Composable
private fun FlatActionRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String, onClick: () -> Unit, modifier: Modifier = Modifier, insideList: Boolean = false) {
    val t = LocalBichenTokens.current
    val scheme = MaterialTheme.colorScheme
    val body = modifier.fillMaxWidth().heightIn(min = 68.dp).clickable(onClick = onClick).padding(if (insideList) 0.dp else 16.dp)
    val row: @Composable () -> Unit = {
        Row(body, verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clip(RoundedCornerShape(13.dp)).background(t.elevatedCardBackground), contentAlignment = Alignment.Center) { Icon(icon, null, tint = scheme.primary, modifier = Modifier.size(21.dp)) }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) { Text(title, color = t.textPrimary, style = MaterialTheme.typography.titleSmall); Text(subtitle, color = t.textSecondary, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            Icon(Icons.Rounded.ChevronRight, null, tint = t.textSecondary, modifier = Modifier.size(19.dp))
        }
    }
    if (insideList) row() else Surface(shape = RoundedCornerShape(24.dp), color = t.cardBackground) { row() }
}

@Composable
private fun ProTextAction(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit, modifier: Modifier) {
    val t = LocalBichenTokens.current
    Surface(onClick = onClick, modifier = modifier.heightIn(min = 48.dp), shape = RoundedCornerShape(16.dp), color = t.elevatedCardBackground) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 9.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) { Icon(icon, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(text, style = MaterialTheme.typography.labelMedium) }
    }
}

@Composable
private fun ProMetric(value: String, label: String, modifier: Modifier) {
    val t = LocalBichenTokens.current
    Column(modifier.padding(vertical = 2.dp), horizontalAlignment = Alignment.Start) { Text(value, color = t.textPrimary, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis); Text(label, color = t.textSecondary, style = MaterialTheme.typography.labelSmall) }
}

@Composable
private fun BigMetric(value: String, label: String, modifier: Modifier) {
    val t = LocalBichenTokens.current
    Surface(modifier = modifier, shape = RoundedCornerShape(22.dp), color = t.cardBackground) {
        Column(Modifier.padding(16.dp)) { Text(value, color = MaterialTheme.colorScheme.primary, fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold); Text(label, color = t.textSecondary, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun EmptySection(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String) {
    val t = LocalBichenTokens.current
    Surface(shape = RoundedCornerShape(24.dp), color = t.cardBackground) {
        Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) { Icon(icon, null, tint = t.textSecondary, modifier = Modifier.size(24.dp)); Spacer(Modifier.width(12.dp)); Column { Text(title, color = t.textPrimary, style = MaterialTheme.typography.titleSmall); Text(subtitle, color = t.textSecondary, style = MaterialTheme.typography.bodySmall) } }
    }
}

@Composable
private fun InlineNotice(text: String, error: Boolean) {
    val scheme = MaterialTheme.colorScheme
    Surface(shape = RoundedCornerShape(18.dp), color = if (error) scheme.errorContainer else LocalBichenTokens.current.elevatedCardBackground) {
        Row(Modifier.fillMaxWidth().padding(13.dp), verticalAlignment = Alignment.CenterVertically) { Icon(if (error) Icons.Rounded.ErrorOutline else Icons.Rounded.Info, null, tint = if (error) scheme.error else scheme.primary, modifier = Modifier.size(19.dp)); Spacer(Modifier.width(8.dp)); Text(text, color = if (error) scheme.onErrorContainer else LocalBichenTokens.current.textPrimary, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun SettingLine(label: String, value: String) {
    val t = LocalBichenTokens.current
    Row(Modifier.fillMaxWidth().heightIn(min = 54.dp), verticalAlignment = Alignment.CenterVertically) { Text(label, color = t.textPrimary, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f)); Text(value, color = t.textSecondary, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 190.dp)) }
}

@Composable
private fun GroupIcon(group: ProxyGroupUi, size: androidx.compose.ui.unit.Dp) {
    val t = LocalBichenTokens.current
    val bitmap = remember(group.iconPath) { group.iconPath.takeIf { it.isNotBlank() && File(it).isFile }?.let { runCatching { BitmapFactory.decodeFile(it) }.getOrNull() } }
    if (bitmap != null) Image(bitmap.asImageBitmap(), group.name, Modifier.size(size).clip(RoundedCornerShape(9.dp)), contentScale = ContentScale.Fit)
    else Box(Modifier.size(size).clip(RoundedCornerShape(9.dp)).background(t.elevatedCardBackground), contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Hub, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(size * .62f)) }
}

private fun bytes(value: Long): String {
    if (value <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var v = value.toDouble(); var i = 0
    while (v >= 1024 && i < units.lastIndex) { v /= 1024; i++ }
    return if (i == 0) "${v.toLong()} ${units[i]}" else String.format(Locale.US, "%.1f %s", v, units[i])
}

private fun speed(value: Long): String = "${bytes(value)}/s"
private fun dateFromEpoch(epoch: Long): String = runCatching { SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(epoch * 1000L)) }.getOrDefault("—")
private fun shortTime(raw: String): String = raw.replace('T', ' ').substringBefore('.').removeSuffix("Z").take(16)
