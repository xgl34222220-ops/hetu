package io.github.xgl34222220.bichen

import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import io.github.xgl34222220.bichen.ui.BichenGlassDock
import io.github.xgl34222220.bichen.ui.BichenTheme
import io.github.xgl34222220.bichen.ui.DockItem
import io.github.xgl34222220.bichen.ui.LocalBichenTokens
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.blur.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Proxy UI v3: LuoShu visual hierarchy with provider-native Mihomo behaviour.
 * The panel intentionally avoids large empty white tiles: compact tonal cards carry real data.
 */
@OptIn(ExperimentalMaterial3Api::class)
class ProxyDashboardActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { BichenTheme { DashboardApp { finish() } } }
    }
}

private enum class DashPage { Home, Panel, Tools, Settings }
private enum class DashTab(val title: String) {
    Overview("概览"), Groups("节点"), Subscription("订阅"), Connections("连接"), Rules("规则"), RuleSets("规则集")
}
private enum class DashSort { Config, Delay }
private data class RateSample(val up: Long, val down: Long)

@Composable
private fun DashboardApp(onBack: () -> Unit) {
    val context = LocalContext.current
    val controller = remember { ProxyComposeController(context) }
    val repo = remember { ProxyDashboardRepository(context) }
    val scope = rememberCoroutineScope()
    val haze = rememberHazeState()
    val backdrop = rememberLayerBackdrop()
    val liquid = isRuntimeShaderSupported()

    var page by rememberSaveable { mutableStateOf(DashPage.Home) }
    var state by remember { mutableStateOf(ProxyComposeState()) }
    var busy by remember { mutableStateOf("") }
    var notice by remember { mutableStateOf("") }
    var upRate by remember { mutableLongStateOf(0L) }
    var downRate by remember { mutableLongStateOf(0L) }
    var lastUp by remember { mutableLongStateOf(0L) }
    var lastDown by remember { mutableLongStateOf(0L) }
    var lastAt by remember { mutableLongStateOf(0L) }
    val rateHistory = remember { mutableStateListOf<RateSample>() }
    val delays = remember { mutableStateMapOf<String, Long>() }

    suspend fun loadState() {
        try {
            val next = repo.state()
            val now = SystemClock.elapsedRealtime()
            if (lastAt > 0L && now > lastAt && next.uploadTotal >= lastUp && next.downloadTotal >= lastDown) {
                val elapsed = now - lastAt
                upRate = ((next.uploadTotal - lastUp) * 1000 / elapsed).coerceAtLeast(0L)
                downRate = ((next.downloadTotal - lastDown) * 1000 / elapsed).coerceAtLeast(0L)
                rateHistory += RateSample(upRate, downRate)
                while (rateHistory.size > 30) rateHistory.removeAt(0)
            }
            next.groups.flatMap { it.nodes }.forEach { node ->
                if (!delays.containsKey(node.name) && node.lastDelay != null) delays[node.name] = node.lastDelay
            }
            lastUp = next.uploadTotal
            lastDown = next.downloadTotal
            lastAt = now
            state = next
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (e: Exception) {
            notice = e.message ?: "状态读取失败"
        }
    }

    fun toggleProxy() {
        if (busy.isNotBlank()) return
        scope.launch {
            busy = if (state.running) "正在停止…" else "正在启动…"
            try {
                if (state.running) controller.stop { busy = it } else controller.start { busy = it }
                delay(400)
                loadState()
            } catch (e: Exception) {
                notice = e.message ?: "代理操作失败"
            } finally {
                busy = ""
            }
        }
    }

    fun restartProxy() {
        if (!state.running || busy.isNotBlank()) return
        scope.launch {
            busy = "正在重启…"
            try {
                controller.stop { busy = it }
                controller.start { busy = it }
                delay(400)
                loadState()
            } catch (e: Exception) {
                notice = e.message ?: "重启失败"
            } finally {
                busy = ""
            }
        }
    }

    LaunchedEffect(Unit) {
        runCatching { repo.ensureIcons() }
        loadState()
        while (true) {
            delay(2500)
            loadState()
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
        Box(
            Modifier.fillMaxSize()
                .then(if (!liquid) Modifier.hazeSource(haze) else Modifier)
                .then(if (liquid) Modifier.layerBackdrop(backdrop) else Modifier)
                .padding(bottom = 104.dp),
        ) {
            when (page) {
                DashPage.Home -> DashboardHome(
                    state = state,
                    delays = delays,
                    busy = busy,
                    notice = notice,
                    onBack = onBack,
                    onRefresh = { scope.launch { loadState() } },
                    onToggle = ::toggleProxy,
                    onRestart = ::restartProxy,
                    onPanel = { page = DashPage.Panel },
                )
                DashPage.Panel -> DashboardPanel(
                    state = state,
                    repo = repo,
                    delays = delays,
                    upRate = upRate,
                    downRate = downRate,
                    history = rateHistory,
                    onStateRefresh = { scope.launch { loadState() } },
                )
                DashPage.Tools -> DashboardTools(state)
                DashPage.Settings -> DashboardSettings(state, controller) { scope.launch { loadState() } }
            }
        }
        BichenGlassDock(
            items = dock,
            selected = page.ordinal,
            onSelect = { page = DashPage.entries[it] },
            hazeState = haze,
            backdrop = backdrop.takeIf { liquid },
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

@Composable
private fun DashboardHome(
    state: ProxyComposeState,
    delays: Map<String, Long>,
    busy: String,
    notice: String,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onToggle: () -> Unit,
    onRestart: () -> Unit,
    onPanel: () -> Unit,
) {
    val tokens = LocalBichenTokens.current
    val scheme = MaterialTheme.colorScheme
    val valid = delays.values.filter { it > 0 }
    val avg = if (valid.isEmpty()) "—" else "${valid.average().toInt()} ms"
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth().statusBarsPadding().heightIn(min = 68.dp), verticalAlignment = Alignment.CenterVertically) {
                HeaderCircle(Icons.AutoMirrored.Rounded.ArrowBack, "返回", onBack)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("代理", color = tokens.textPrimary, fontSize = 26.sp, lineHeight = 32.sp, fontWeight = FontWeight.Bold)
                    Text("${state.core} · ${state.mode}", color = tokens.textSecondary, style = MaterialTheme.typography.bodySmall)
                }
                HeaderCircle(Icons.Rounded.Refresh, "刷新", onRefresh)
            }
        }
        item {
            Surface(shape = RoundedCornerShape(28.dp), color = scheme.surfaceContainer.copy(alpha = .78f), shadowElevation = 0.dp) {
                Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(15.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusPill(if (state.running) "运行中" else "已停止", if (state.running) tokens.success else tokens.warning)
                        Spacer(Modifier.weight(1f))
                        Icon(if (state.running) Icons.Rounded.CheckCircle else Icons.Rounded.PauseCircle, null, tint = scheme.primary, modifier = Modifier.size(42.dp))
                    }
                    Text(state.config, color = tokens.textPrimary, fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MetricCell(if (state.memoryBytes > 0) bytes(state.memoryBytes) else "—", "内存", Modifier.weight(1f))
                        MetricCell(avg, "延迟", Modifier.weight(1f))
                        MetricCell(state.connections.size.toString(), "连接", Modifier.weight(1f))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MetricCell(bytes(state.downloadTotal), "下载", Modifier.weight(1f))
                        MetricCell(bytes(state.uploadTotal), "上传", Modifier.weight(1f))
                        MetricCell(if (state.panelReady) "在线" else "等待", "控制接口", Modifier.weight(1f))
                    }
                    if (busy.isNotBlank() || notice.isNotBlank()) {
                        Text(busy.ifBlank { notice }, color = tokens.textSecondary, style = MaterialTheme.typography.bodySmall)
                    }
                    Button(onClick = onToggle, enabled = busy.isBlank(), modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), shape = RoundedCornerShape(17.dp)) {
                        Icon(if (state.running) Icons.Rounded.Stop else Icons.Rounded.PlayArrow, null, Modifier.size(19.dp))
                        Spacer(Modifier.width(7.dp))
                        Text(if (state.running) "停止代理" else "启动代理")
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(onClick = onRefresh, Modifier.weight(1f), shape = RoundedCornerShape(16.dp)) { Text("重载") }
                        OutlinedButton(onClick = onRestart, enabled = state.running && busy.isBlank(), modifier = Modifier.weight(1f), shape = RoundedCornerShape(16.dp)) { Text("重启") }
                    }
                }
            }
        }
        item {
            Surface(onClick = onPanel, shape = RoundedCornerShape(22.dp), color = scheme.surfaceContainerLow.copy(alpha = .76f)) {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Surface(shape = RoundedCornerShape(13.dp), color = scheme.primary.copy(alpha = .10f), modifier = Modifier.size(44.dp)) {
                        Box(contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Public, null, tint = scheme.primary) }
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("代理工作台", color = tokens.textPrimary, style = MaterialTheme.typography.titleSmall)
                        Text("节点 · 订阅 · 连接 · 规则", color = tokens.textSecondary, style = MaterialTheme.typography.bodySmall)
                    }
                    Icon(Icons.Rounded.ChevronRight, null, tint = tokens.textSecondary)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DashboardPanel(
    state: ProxyComposeState,
    repo: ProxyDashboardRepository,
    delays: MutableMap<String, Long>,
    upRate: Long,
    downRate: Long,
    history: List<RateSample>,
    onStateRefresh: () -> Unit,
) {
    val tokens = LocalBichenTokens.current
    val scheme = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    var tab by rememberSaveable { mutableStateOf(DashTab.Groups) }
    var query by rememberSaveable { mutableStateOf("") }
    var showSearch by rememberSaveable { mutableStateOf(false) }
    var sort by rememberSaveable { mutableStateOf(DashSort.Config) }
    var expanded by rememberSaveable { mutableStateOf<String?>(null) }
    var refreshing by remember { mutableStateOf(false) }
    var providers by remember { mutableStateOf<List<DashboardProviderUi>>(emptyList()) }
    var rules by remember { mutableStateOf<List<ProxyRuleUi>>(emptyList()) }
    var ruleSets by remember { mutableStateOf<List<DashboardRuleSetUi>>(emptyList()) }
    var error by remember { mutableStateOf("") }
    var initialLatencyLoaded by rememberSaveable { mutableStateOf(false) }
    val selectedOverrides = remember { mutableStateMapOf<String, String>() }

    fun selected(group: ProxyGroupUi): String = selectedOverrides[group.name] ?: group.now
    fun nodeDelay(node: ProxyNodeUi): Long? = delays[node.name] ?: node.lastDelay

    suspend fun loadTabData() {
        if (!state.running) return
        try {
            when (tab) {
                DashTab.Subscription -> providers = repo.providers()
                DashTab.Rules -> rules = repo.rules()
                DashTab.RuleSets -> ruleSets = repo.ruleSets()
                else -> Unit
            }
        } catch (e: Exception) {
            error = e.message ?: "面板数据读取失败"
        }
    }

    fun refreshCurrent() {
        if (refreshing) return
        refreshing = true
        error = ""
        scope.launch {
            try {
                when (tab) {
                    DashTab.Groups -> delays.putAll(repo.globalDelay())
                    DashTab.Subscription -> providers = repo.refreshSubscriptions()
                    DashTab.RuleSets -> ruleSets = repo.refreshRuleSets()
                    DashTab.Rules -> rules = repo.rules()
                    DashTab.Connections, DashTab.Overview -> onStateRefresh()
                }
            } catch (e: Exception) {
                error = e.message ?: "刷新失败"
            } finally {
                refreshing = false
            }
        }
    }

    LaunchedEffect(tab, state.running) {
        error = ""
        loadTabData()
    }

    // If the core has no cached history yet, do one provider-native healthcheck automatically.
    LaunchedEffect(state.running, state.groups.size) {
        if (!state.running || initialLatencyLoaded || state.groups.isEmpty()) return@LaunchedEffect
        val allNodes = state.groups.flatMap { it.nodes }.distinctBy { it.name }
        val known = allNodes.count { nodeDelay(it) != null }
        if (known * 5 < allNodes.size.coerceAtLeast(1)) {
            initialLatencyLoaded = true
            runCatching { repo.globalDelay() }.getOrNull()?.let { delays.putAll(it) }
        } else initialLatencyLoaded = true
    }

    val groups = remember(state.groups, delays, query, sort, selectedOverrides.toMap()) {
        val filtered = state.groups.filter { g ->
            query.isBlank() || g.name.contains(query, true) || selected(g).contains(query, true) || g.nodes.any { it.name.contains(query, true) }
        }
        if (sort == DashSort.Config) filtered.sortedWith(compareBy({ groupPriority2(it.name) }, { it.name }))
        else filtered.sortedBy { group -> group.nodes.firstOrNull { it.name == selected(group) }?.let(::nodeDelay)?.takeIf { it > 0 } ?: Long.MAX_VALUE }
    }
    val filteredRules = remember(rules, query) { rules.filter { query.isBlank() || it.type.contains(query, true) || it.payload.contains(query, true) || it.proxy.contains(query, true) } }
    val filteredRuleSets = remember(ruleSets, query) { ruleSets.filter { query.isBlank() || it.name.contains(query, true) || it.behavior.contains(query, true) } }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth().statusBarsPadding().heightIn(min = 72.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("面板", Modifier.weight(1f), color = tokens.textPrimary, fontSize = 30.sp, lineHeight = 38.sp, fontWeight = FontWeight.Bold)
            HeaderCircle(Icons.Rounded.Search, "搜索") { showSearch = !showSearch }
            Spacer(Modifier.width(7.dp))
            if (tab == DashTab.Groups) {
                HeaderCircle(Icons.Rounded.Sort, "排序") { sort = if (sort == DashSort.Config) DashSort.Delay else DashSort.Config }
                Spacer(Modifier.width(7.dp))
            }
            HeaderCircle(Icons.Rounded.Refresh, "刷新", ::refreshCurrent)
        }

        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DashTab.entries.forEach { item -> DashTabChip(item.title, tab == item) { tab = item } }
        }
        Spacer(Modifier.height(8.dp))
        AnimatedVisibility(showSearch, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            OutlinedTextField(
                query,
                { query = it },
                Modifier.fillMaxWidth().padding(bottom = 8.dp),
                singleLine = true,
                placeholder = { Text("搜索当前面板") },
                leadingIcon = { Icon(Icons.Rounded.Search, null) },
                shape = RoundedCornerShape(16.dp),
            )
        }
        if (error.isNotBlank()) Text(error, color = scheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 6.dp))

        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = ::refreshCurrent,
            modifier = Modifier.fillMaxSize(),
        ) {
            AnimatedContent(
                targetState = tab,
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "panel-tab",
            ) { active ->
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(top = 6.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    when (active) {
                        DashTab.Overview -> {
                            item {
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    RatePanel("上行速度", rate(upRate), true, Modifier.weight(1f))
                                    RatePanel("下行速度", rate(downRate), false, Modifier.weight(1f))
                                }
                            }
                            item { TrafficPanel(state, history) }
                        }
                        DashTab.Groups -> {
                            if (groups.isEmpty()) item { EmptyPanel(if (state.running) "没有可显示的策略组" else "代理尚未运行") }
                            groups.chunked(2).forEachIndexed { rowIndex, row ->
                                item("group-$rowIndex") {
                                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                        row.forEach { group ->
                                            val current = selected(group)
                                            val currentNode = group.nodes.firstOrNull { it.name == current }
                                            StrategyTile(
                                                group = group,
                                                current = current,
                                                delay = currentNode?.let(::nodeDelay),
                                                measured = group.nodes.count { nodeDelay(it) != null },
                                                expanded = expanded == group.name,
                                                modifier = Modifier.weight(1f),
                                                onOpen = { expanded = if (expanded == group.name) null else group.name },
                                                onDelay = {
                                                    if (currentNode != null) scope.launch {
                                                        delays[currentNode.name] = -2L
                                                        delays[currentNode.name] = runCatching { repo.delay(currentNode.name) }.getOrDefault(-1L)
                                                    }
                                                },
                                            )
                                        }
                                        if (row.size == 1) Spacer(Modifier.weight(1f))
                                    }
                                }
                                item("expand-$rowIndex") {
                                    val target = row.firstOrNull { it.name == expanded }
                                    AnimatedVisibility(target != null, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                                        if (target != null) NodeGrid(
                                            group = target,
                                            selected = selected(target),
                                            delayOf = ::nodeDelay,
                                            onSelect = { node ->
                                                val old = selected(target)
                                                selectedOverrides[target.name] = node.name
                                                scope.launch {
                                                    try { repo.select(target.name, node.name) }
                                                    catch (_: Exception) { selectedOverrides[target.name] = old }
                                                }
                                            },
                                            onDelay = { node ->
                                                scope.launch {
                                                    delays[node.name] = -2L
                                                    delays[node.name] = runCatching { repo.delay(node.name) }.getOrDefault(-1L)
                                                }
                                            },
                                        )
                                    }
                                }
                            }
                        }
                        DashTab.Subscription -> {
                            item { PullHint("下拉即可更新全部订阅；流量来自 subscription-userinfo") }
                            if (providers.isEmpty()) item { EmptyPanel(if (state.running) "当前订阅未提供流量信息" else "代理尚未运行") }
                            items(providers, key = { it.name }) { provider -> SubscriptionTile(provider) }
                        }
                        DashTab.Connections -> {
                            item {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    MetricCell(bytes(state.downloadTotal), "下载", Modifier.weight(1f))
                                    MetricCell(bytes(state.uploadTotal), "上传", Modifier.weight(1f))
                                    MetricCell(state.connections.size.toString(), "连接", Modifier.weight(1f))
                                }
                            }
                            items(state.connections, key = { it.id }) { connection ->
                                ConnectionTile(connection) { scope.launch { runCatching { repo.closeConnection(connection.id) } } }
                            }
                        }
                        DashTab.Rules -> {
                            if (filteredRules.isEmpty()) item { EmptyPanel(if (state.running) "Mihomo 没有返回规则" else "代理尚未运行") }
                            items(filteredRules, key = { it.index }) { rule -> RuleTile(rule) }
                        }
                        DashTab.RuleSets -> {
                            item { PullHint("下拉刷新全部远程规则集，不再逐个点下载") }
                            if (filteredRuleSets.isEmpty()) item { EmptyPanel(if (state.running) "没有规则集" else "代理尚未运行") }
                            items(filteredRuleSets, key = { it.name }) { provider -> RuleSetTile(provider) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StrategyTile(
    group: ProxyGroupUi,
    current: String,
    delay: Long?,
    measured: Int,
    expanded: Boolean,
    modifier: Modifier,
    onOpen: () -> Unit,
    onDelay: () -> Unit,
) {
    val tokens = LocalBichenTokens.current
    val scheme = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) .968f else 1f, spring(stiffness = Spring.StiffnessMedium), label = "strategy-press")
    val color by animateColorAsState(if (expanded) scheme.primaryContainer.copy(alpha = .60f) else scheme.surfaceContainerLow.copy(alpha = .82f), label = "strategy-color")
    val arrow by animateFloatAsState(if (expanded) 180f else 0f, label = "strategy-arrow")
    Surface(
        modifier = modifier.height(96.dp).graphicsLayer { scaleX = scale; scaleY = scale }
            .clickable(interactionSource = interaction, indication = null, onClick = onOpen),
        shape = RoundedCornerShape(19.dp),
        color = color,
        shadowElevation = 0.dp,
    ) {
        Column(Modifier.padding(11.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                GroupIcon2(group, 28)
                Spacer(Modifier.width(7.dp))
                Text(group.name, Modifier.weight(1f), color = tokens.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Icon(Icons.Rounded.KeyboardArrowDown, null, tint = tokens.textSecondary, modifier = Modifier.size(17.dp).graphicsLayer { rotationZ = arrow })
            }
            Text("${groupType2(group.type)} · $measured/${group.nodes.size}", color = tokens.textSecondary, fontSize = 10.5.sp, maxLines = 1)
            Spacer(Modifier.weight(1f))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(current, Modifier.weight(1f), color = tokens.textSecondary, fontSize = 11.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Box(Modifier.clip(CircleShape).clickable(onClick = onDelay)) { DelayPill(delay) }
            }
        }
    }
}

@Composable
private fun NodeGrid(
    group: ProxyGroupUi,
    selected: String,
    delayOf: (ProxyNodeUi) -> Long?,
    onSelect: (ProxyNodeUi) -> Unit,
    onDelay: (ProxyNodeUi) -> Unit,
) {
    val tokens = LocalBichenTokens.current
    Column(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 3.dp)) {
            GroupIcon2(group, 28)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(group.name, color = tokens.textPrimary, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text("${group.nodes.size} 节点 · 当前 $selected", color = tokens.textSecondary, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        group.nodes.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                row.forEach { node -> NodeTile(node, delayOf(node), node.name == selected, Modifier.weight(1f), { onSelect(node) }, { onDelay(node) }) }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun NodeTile(node: ProxyNodeUi, delay: Long?, selected: Boolean, modifier: Modifier, onSelect: () -> Unit, onDelay: () -> Unit) {
    val tokens = LocalBichenTokens.current
    val scheme = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) .97f else 1f, spring(stiffness = 600f), label = "node-press")
    Surface(
        modifier = modifier.height(74.dp).graphicsLayer { scaleX = scale; scaleY = scale }
            .clickable(interactionSource = interaction, indication = null, onClick = onSelect),
        shape = RoundedCornerShape(17.dp),
        color = if (selected) scheme.primaryContainer.copy(alpha = .66f) else scheme.surfaceContainerLow.copy(alpha = .74f),
        shadowElevation = 0.dp,
    ) {
        Column(Modifier.padding(horizontal = 11.dp, vertical = 9.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(node.name, Modifier.weight(1f), color = tokens.textPrimary, fontSize = 13.sp, lineHeight = 17.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (selected) Icon(Icons.Rounded.CheckCircle, "已选择", tint = scheme.primary, modifier = Modifier.size(16.dp))
            }
            Spacer(Modifier.weight(1f))
            Row(verticalAlignment = Alignment.CenterVertically) {
                val meta = listOf(if (node.udp) "UDP" else "", node.type).filter { it.isNotBlank() }.joinToString(" · ")
                Text(meta.ifBlank { "节点" }, Modifier.weight(1f), color = tokens.textSecondary, fontSize = 10.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Box(Modifier.clip(CircleShape).clickable(onClick = onDelay)) { DelayPill(delay) }
            }
        }
    }
}

@Composable
private fun DelayPill(delay: Long?) {
    val scheme = MaterialTheme.colorScheme
    val tokens = LocalBichenTokens.current
    val text = when {
        delay == -2L -> "…"
        delay == null -> "--"
        delay < 0L -> "超时"
        else -> "$delay ms"
    }
    val color = when {
        delay == -2L -> scheme.primary
        delay == null -> tokens.textSecondary
        delay < 0L -> scheme.error
        delay <= 350L -> scheme.primary
        delay <= 600L -> tokens.success
        delay <= 900L -> tokens.warning
        else -> scheme.error
    }
    Surface(shape = CircleShape, color = color.copy(alpha = .13f)) {
        Text(text, Modifier.padding(horizontal = 8.dp, vertical = 3.dp), color = color, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
private fun SubscriptionTile(provider: DashboardProviderUi) {
    val tokens = LocalBichenTokens.current
    val scheme = MaterialTheme.colorScheme
    val context = LocalContext.current
    val percent = if (provider.total > 0L) ((1f - provider.ratio) * 100).toInt().coerceIn(0, 100) else null
    Surface(
        onClick = { context.startActivity(Intent(context, ProxySubscriptionActivity::class.java)) },
        shape = RoundedCornerShape(22.dp),
        color = scheme.surfaceContainerLow.copy(alpha = .78f),
        shadowElevation = 0.dp,
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(11.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(provider.name, color = tokens.textPrimary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${provider.nodes.size} 节点${provider.updatedAt.takeIf { it.isNotBlank() }?.let { " · 更新于 ${shortTime2(it)}" } ?: ""}", color = tokens.textSecondary, style = MaterialTheme.typography.bodySmall)
                }
                if (percent != null) Surface(shape = RoundedCornerShape(12.dp), color = scheme.primary.copy(alpha = .11f)) {
                    Text("$percent%", Modifier.padding(horizontal = 10.dp, vertical = 6.dp), color = scheme.primary, fontWeight = FontWeight.SemiBold)
                }
            }
            if (provider.total > 0L) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MetricCell(bytes(provider.upload), "上传", Modifier.weight(1f))
                    MetricCell(bytes(provider.download), "下载", Modifier.weight(1f))
                    MetricCell(bytes(provider.remaining), "剩余", Modifier.weight(1f), scheme.primary)
                }
                LinearProgressIndicator(progress = { provider.ratio }, modifier = Modifier.fillMaxWidth().height(5.dp).clip(CircleShape))
                Row {
                    Text("已用 ${bytes(provider.used)}", color = tokens.textSecondary, style = MaterialTheme.typography.labelSmall)
                    Spacer(Modifier.weight(1f))
                    Text("总计 ${bytes(provider.total)}", color = tokens.textSecondary, style = MaterialTheme.typography.labelSmall)
                }
            } else {
                Text("订阅服务器未返回 Subscription-Userinfo 流量信息", color = tokens.textSecondary, style = MaterialTheme.typography.bodySmall)
            }
            if (provider.expire > 0L) Text("到期 ${dateFromEpoch(provider.expire)}", color = tokens.textSecondary, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun RuleSetTile(provider: DashboardRuleSetUi) {
    val tokens = LocalBichenTokens.current
    val scheme = MaterialTheme.colorScheme
    Surface(shape = RoundedCornerShape(20.dp), color = scheme.surfaceContainerLow.copy(alpha = .76f), shadowElevation = 0.dp) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 15.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(provider.name, color = tokens.textPrimary, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (provider.ruleCount > 0) {
                        Spacer(Modifier.width(8.dp))
                        Text("${provider.ruleCount} 条规则", color = scheme.primary, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                    }
                }
                Text(listOf(provider.behavior, provider.format, provider.vehicleType).filter { it.isNotBlank() }.joinToString(" / "), color = tokens.textSecondary, style = MaterialTheme.typography.bodySmall)
                if (provider.updatedAt.isNotBlank()) Text("更新于 ${shortTime2(provider.updatedAt)}", color = tokens.textSecondary, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun RuleTile(rule: ProxyRuleUi) {
    val tokens = LocalBichenTokens.current
    val scheme = MaterialTheme.colorScheme
    Surface(shape = RoundedCornerShape(19.dp), color = scheme.surfaceContainerLow.copy(alpha = .74f), shadowElevation = 0.dp) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(rule.type, color = tokens.textPrimary, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
                Text(rule.payload.ifBlank { "—" }, color = tokens.textSecondary, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(10.dp))
            Text(rule.proxy.ifBlank { "DIRECT" }, color = if (rule.disabled) tokens.textSecondary else scheme.primary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun ConnectionTile(connection: ProxyConnectionUi, onClose: () -> Unit) {
    val tokens = LocalBichenTokens.current
    val scheme = MaterialTheme.colorScheme
    Surface(shape = RoundedCornerShape(20.dp), color = scheme.surfaceContainerLow.copy(alpha = .76f), shadowElevation = 0.dp) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(connection.host, color = tokens.textPrimary, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(connection.network.ifBlank { connection.inbound }, color = tokens.textSecondary, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                }
                if (connection.appName.isNotBlank()) {
                    if (connection.appIcon != null) {
                        Image(connection.appIcon.asImageBitmap(), null, Modifier.size(26.dp).clip(RoundedCornerShape(7.dp)))
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(connection.appName, color = tokens.textSecondary, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconButton(onClick = onClose, modifier = Modifier.size(34.dp)) { Icon(Icons.Rounded.Close, "断开", Modifier.size(17.dp), tint = tokens.textSecondary) }
            }
            val path = listOf(connection.rulePayload.ifBlank { connection.rule }, connection.chain).filter { it.isNotBlank() }.joinToString(" / ")
            if (path.isNotBlank()) Text(path, color = tokens.textSecondary, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row {
                Text("↑ ${bytes(connection.upload)}", color = tokens.success, style = MaterialTheme.typography.labelSmall)
                Spacer(Modifier.weight(1f))
                Text("↓ ${bytes(connection.download)}", color = scheme.primary, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun RatePanel(title: String, value: String, up: Boolean, modifier: Modifier) {
    val tokens = LocalBichenTokens.current
    val scheme = MaterialTheme.colorScheme
    val color = if (up) tokens.success else scheme.primary
    Surface(modifier = modifier, shape = RoundedCornerShape(20.dp), color = scheme.surfaceContainerLow.copy(alpha = .76f), shadowElevation = 0.dp) {
        Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, color = tokens.textSecondary, style = MaterialTheme.typography.bodySmall)
            Text(value, color = color, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun TrafficPanel(state: ProxyComposeState, samples: List<RateSample>) {
    val tokens = LocalBichenTokens.current
    val scheme = MaterialTheme.colorScheme
    Surface(shape = RoundedCornerShape(22.dp), color = scheme.surfaceContainerLow.copy(alpha = .76f), shadowElevation = 0.dp) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row {
                Text("总流量", color = tokens.textPrimary, style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.weight(1f))
                Text("↑ ${bytes(state.uploadTotal)}", color = tokens.success, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.width(12.dp))
                Text("↓ ${bytes(state.downloadTotal)}", color = scheme.primary, style = MaterialTheme.typography.bodySmall)
            }
            Canvas(Modifier.fillMaxWidth().height(150.dp)) {
                if (samples.size < 2) return@Canvas
                val max = samples.maxOf { maxOf(it.up, it.down) }.coerceAtLeast(1L).toFloat()
                fun make(up: Boolean): Path {
                    val p = Path()
                    samples.forEachIndexed { index, sample ->
                        val x = size.width * index / (samples.size - 1).toFloat()
                        val raw = if (up) sample.up else sample.down
                        val y = size.height - (raw / max) * size.height
                        if (index == 0) p.moveTo(x, y) else p.lineTo(x, y)
                    }
                    return p
                }
                drawPath(make(false), scheme.primary, style = Stroke(4.dp.toPx(), cap = StrokeCap.Round))
                drawPath(make(true), tokens.success, style = Stroke(3.dp.toPx(), cap = StrokeCap.Round))
            }
        }
    }
}

@Composable
private fun DashboardTools(state: ProxyComposeState) {
    val context = LocalContext.current
    val tokens = LocalBichenTokens.current
    val scheme = MaterialTheme.colorScheme
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { SimpleTitle("工具") }
        item { ToolTile(Icons.Rounded.Storage, "内核管理", "Mihomo 可更新；其他核心按需联网下载") { context.startActivity(Intent(context, ProxyCoreActivity::class.java)) } }
        item { ToolTile(Icons.Rounded.CloudSync, "订阅与配置", "订阅、YAML、配置切换") { context.startActivity(Intent(context, ProxySubscriptionActivity::class.java)) } }
        item { ToolTile(Icons.Rounded.Tune, "基础代理配置", "TPROXY 与 Root 参数") { context.startActivity(Intent(context, RootTproxyActivity::class.java)) } }
        item { Text("${state.core} · ${state.mode}", color = tokens.textSecondary, style = MaterialTheme.typography.bodySmall) }
        item { Spacer(Modifier.height(1.dp).background(scheme.outlineVariant.copy(alpha = .2f))) }
    }
}

@Composable
private fun DashboardSettings(state: ProxyComposeState, controller: ProxyComposeController, onChanged: () -> Unit) {
    val tokens = LocalBichenTokens.current
    val scheme = MaterialTheme.colorScheme
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { SimpleTitle("设置") }
        item {
            Surface(shape = RoundedCornerShape(20.dp), color = scheme.surfaceContainerLow.copy(alpha = .76f)) {
                Column(Modifier.fillMaxWidth().padding(15.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Text("内核", color = tokens.textPrimary, style = MaterialTheme.typography.titleSmall)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        controller.cores().forEach { (id, label) -> FilterChip(selected = state.core == label, onClick = { controller.setCore(id); onChanged() }, label = { Text(label) }) }
                    }
                }
            }
        }
        item {
            Surface(shape = RoundedCornerShape(20.dp), color = scheme.surfaceContainerLow.copy(alpha = .76f)) {
                Column(Modifier.fillMaxWidth().padding(15.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("当前运行", color = tokens.textPrimary, style = MaterialTheme.typography.titleSmall)
                    Text("${state.core} · ${state.mode} · IPv6 ${state.ipv6}", color = tokens.textSecondary, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun DashTabChip(text: String, selected: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val tokens = LocalBichenTokens.current
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(15.dp),
        color = if (selected) scheme.primaryContainer.copy(alpha = .58f) else Color.Transparent,
        border = if (selected) null else androidx.compose.foundation.BorderStroke(1.dp, scheme.outlineVariant.copy(alpha = .72f)),
    ) {
        Text(text, Modifier.padding(horizontal = 16.dp, vertical = 9.dp), color = if (selected) tokens.textPrimary else tokens.textSecondary, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium)
    }
}

@Composable
private fun GroupIcon2(group: ProxyGroupUi, size: Int) {
    val bitmap = remember(group.iconPath) { group.iconPath.takeIf { it.isNotBlank() }?.let(BitmapFactory::decodeFile) }
    Box(Modifier.size(size.dp), contentAlignment = Alignment.Center) {
        if (bitmap != null) Image(bitmap.asImageBitmap(), null, Modifier.size(size.dp), contentScale = ContentScale.Fit)
        else Icon(Icons.Rounded.Public, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size((size - 7).dp))
    }
}

@Composable
private fun HeaderCircle(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, onClick: () -> Unit) {
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = .82f), shadowElevation = 1.dp, modifier = Modifier.size(44.dp)) {
        IconButton(onClick = onClick) { Icon(icon, description, Modifier.size(21.dp)) }
    }
}

@Composable
private fun StatusPill(text: String, color: Color) {
    Surface(shape = CircleShape, color = color.copy(alpha = .11f)) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(6.dp).background(color, CircleShape))
            Spacer(Modifier.width(6.dp))
            Text(text, color = LocalBichenTokens.current.textPrimary, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun MetricCell(value: String, label: String, modifier: Modifier, valueColor: Color = LocalBichenTokens.current.textPrimary) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(value, color = valueColor, fontSize = 16.sp, lineHeight = 21.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(label, color = LocalBichenTokens.current.textSecondary, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun PullHint(text: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 3.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Rounded.South, null, tint = LocalBichenTokens.current.textSecondary, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(6.dp))
        Text(text, color = LocalBichenTokens.current.textSecondary, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun EmptyPanel(text: String) {
    Box(Modifier.fillMaxWidth().padding(vertical = 34.dp), contentAlignment = Alignment.Center) {
        Text(text, color = LocalBichenTokens.current.textSecondary, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun SimpleTitle(text: String) {
    Text(text, Modifier.fillMaxWidth().statusBarsPadding().padding(vertical = 14.dp), color = LocalBichenTokens.current.textPrimary, fontSize = 26.sp, lineHeight = 34.sp, fontWeight = FontWeight.Bold)
}

@Composable
private fun ToolTile(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    val tokens = LocalBichenTokens.current
    val scheme = MaterialTheme.colorScheme
    Surface(onClick = onClick, shape = RoundedCornerShape(19.dp), color = scheme.surfaceContainerLow.copy(alpha = .76f)) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = RoundedCornerShape(12.dp), color = scheme.primary.copy(alpha = .10f), modifier = Modifier.size(42.dp)) { Box(contentAlignment = Alignment.Center) { Icon(icon, null, tint = scheme.primary) } }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, color = tokens.textPrimary, style = MaterialTheme.typography.titleSmall)
                Text(subtitle, color = tokens.textSecondary, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Icon(Icons.Rounded.ChevronRight, null, tint = tokens.textSecondary)
        }
    }
}

private fun groupType2(raw: String): String = when (raw.lowercase()) {
    "urltest", "url-test" -> "URLTest"
    "fallback" -> "Fallback"
    "loadbalance", "load-balance" -> "LoadBalance"
    "selector" -> "Selector"
    else -> raw.ifBlank { "Group" }
}

private fun groupPriority2(name: String): Int {
    val order = listOf("节点选择", "手动选择", "故障转移", "香港节点", "台湾节点", "日本节点", "新加坡节点", "韩国节点", "美国节点", "AI", "YouTube", "Google", "TikTok", "Telegram", "GitHub", "Emby", "Netflix", "Spotify", "Windows", "Apple", "Game", "Download", "bilibili")
    val index = order.indexOfFirst { name.contains(it, true) }
    return if (index >= 0) index else 1000
}

private fun bytes(value: Long): String = when {
    value >= 1L shl 40 -> "%.2f TB".format(Locale.US, value.toDouble() / (1L shl 40))
    value >= 1L shl 30 -> "%.2f GB".format(Locale.US, value.toDouble() / (1L shl 30))
    value >= 1L shl 20 -> "%.1f MB".format(Locale.US, value.toDouble() / (1L shl 20))
    value >= 1L shl 10 -> "%.1f KB".format(Locale.US, value.toDouble() / (1L shl 10))
    else -> "$value B"
}

private fun rate(value: Long): String = "${bytes(value)}/s"
private fun shortTime2(raw: String): String = raw.replace('T', ' ').substringBefore('.').removeSuffix("Z").take(16)
private fun dateFromEpoch(epoch: Long): String {
    val millis = if (epoch > 10_000_000_000L) epoch else epoch * 1000L
    return runCatching { SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(millis)) }.getOrDefault("—")
}
