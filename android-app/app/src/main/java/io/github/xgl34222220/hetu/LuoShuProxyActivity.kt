package io.github.xgl34222220.hetu

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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import io.github.xgl34222220.hetu.ui.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.blur.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
class LuoShuProxyActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { HetuTheme { LuoProxyShell { finish() } } }
    }
}

private enum class LuoProxyPage { Home, Panel, Tools, Settings }
private enum class LuoProxyTab(val label: String) {
    Overview("概览"), Groups("节点"), Subscriptions("订阅"), Connections("连接"), Rules("规则"), RuleSets("规则集")
}
private enum class LuoProxySort { Config, Delay }
private data class ProxyRateSample(val up: Long, val down: Long)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LuoProxyShell(onBack: () -> Unit) {
    val context = LocalContext.current
    val controller = remember { ProxyComposeController(context) }
    val repo = remember { ProxyDashboardRepository(context) }
    val scope = rememberCoroutineScope()
    val haze = rememberHazeState()
    val backdrop = rememberLayerBackdrop()
    val liquid = isRuntimeShaderSupported()

    var page by rememberSaveable { mutableStateOf(LuoProxyPage.Home) }
    var state by remember { mutableStateOf(ProxyComposeState()) }
    var busy by remember { mutableStateOf("") }
    var notice by remember { mutableStateOf("") }
    val delays = remember { mutableStateMapOf<String, Long>() }
    val rates = remember { mutableStateListOf<ProxyRateSample>() }
    var upRate by remember { mutableLongStateOf(0L) }
    var downRate by remember { mutableLongStateOf(0L) }
    var lastUp by remember { mutableLongStateOf(0L) }
    var lastDown by remember { mutableLongStateOf(0L) }
    var lastAt by remember { mutableLongStateOf(0L) }

    suspend fun refreshState() {
        try {
            val next = repo.state()
            val now = SystemClock.elapsedRealtime()
            if (lastAt > 0L && now > lastAt && next.uploadTotal >= lastUp && next.downloadTotal >= lastDown) {
                val elapsed = now - lastAt
                upRate = ((next.uploadTotal - lastUp) * 1000L / elapsed).coerceAtLeast(0L)
                downRate = ((next.downloadTotal - lastDown) * 1000L / elapsed).coerceAtLeast(0L)
                rates += ProxyRateSample(upRate, downRate)
                while (rates.size > 30) rates.removeAt(0)
            }
            next.groups.flatMap { it.nodes }.forEach { node ->
                node.lastDelay?.let { if (!delays.containsKey(node.name)) delays[node.name] = it }
            }
            lastAt = now
            lastUp = next.uploadTotal
            lastDown = next.downloadTotal
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
                delay(350)
                refreshState()
            } catch (e: Exception) {
                notice = e.message ?: "代理操作失败"
            } finally { busy = "" }
        }
    }

    LaunchedEffect(Unit) {
        runCatching { repo.ensureIcons() }
        refreshState()
        while (true) {
            delay(3000)
            refreshState()
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

    Box(Modifier.fillMaxSize().background(LocalHetuTokens.current.pageBackground)) {
        LuoProxyBackdrop()
        Box(
            Modifier.fillMaxSize()
                .then(if (!liquid) Modifier.hazeSource(haze) else Modifier)
                .then(if (liquid) Modifier.layerBackdrop(backdrop) else Modifier)
                .padding(bottom = 104.dp),
        ) {
            when (page) {
                LuoProxyPage.Home -> LuoProxyHome(
                    state = state,
                    delays = delays,
                    busy = busy,
                    notice = notice,
                    onBack = onBack,
                    onRefresh = { scope.launch { refreshState() } },
                    onToggle = ::toggleProxy,
                    onPanel = { page = LuoProxyPage.Panel },
                )
                LuoProxyPage.Panel -> LuoProxyPanel(
                    state = state,
                    repo = repo,
                    delays = delays,
                    upRate = upRate,
                    downRate = downRate,
                    rates = rates,
                    onRefreshState = { scope.launch { refreshState() } },
                )
                LuoProxyPage.Tools -> LuoProxyTools(state)
                LuoProxyPage.Settings -> LuoProxySettings(state, controller) { scope.launch { refreshState() } }
            }
        }
        HetuGlassDock(
            items = dockItems,
            selected = page.ordinal,
            onSelect = { page = LuoProxyPage.entries[it] },
            hazeState = haze,
            backdrop = backdrop.takeIf { liquid },
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

@Composable
private fun LuoProxyBackdrop() {
    val tokens = LocalHetuTokens.current
    val scheme = MaterialTheme.colorScheme
    Box(
        Modifier.fillMaxSize()
            .background(Brush.verticalGradient(listOf(tokens.pageBackground, tokens.pageBackground)))
            .drawBehind {
                drawRect(
                    Brush.radialGradient(
                        listOf(scheme.primary.copy(alpha = .09f), Color.Transparent),
                        center = Offset(size.width * .92f, size.height * .02f),
                        radius = size.width * .85f,
                    ),
                )
                drawRect(
                    Brush.radialGradient(
                        listOf(scheme.secondary.copy(alpha = .055f), Color.Transparent),
                        center = Offset(size.width * .04f, size.height * .82f),
                        radius = size.width,
                    ),
                )
            },
    )
}

@Composable
private fun LuoProxyHome(
    state: ProxyComposeState,
    delays: Map<String, Long>,
    busy: String,
    notice: String,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onToggle: () -> Unit,
    onPanel: () -> Unit,
) {
    val tokens = LocalHetuTokens.current
    val scheme = MaterialTheme.colorScheme
    val valid = delays.values.filter { it > 0L }
    val avg = if (valid.isEmpty()) "—" else "${valid.average().toInt()} ms"
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().heightIn(min = 64.dp).padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                HetuLuoShuHeaderAction(Icons.AutoMirrored.Rounded.ArrowBack, "返回", onBack, contentColor = tokens.textPrimary)
                Column(Modifier.weight(1f)) {
                    Text("代理", color = tokens.textPrimary, fontSize = 22.sp, lineHeight = 30.sp, fontWeight = FontWeight.SemiBold)
                    Text("${state.core} · ${state.mode}", color = tokens.textSecondary, style = MaterialTheme.typography.bodySmall)
                }
                HetuLuoShuHeaderAction(Icons.Rounded.Refresh, "刷新", onRefresh)
            }
        }
        item {
            Surface(shape = RoundedCornerShape(28.dp), color = tokens.cardBackground, shadowElevation = 2.dp) {
                Column(
                    Modifier.fillMaxWidth()
                        .background(Brush.linearGradient(listOf(scheme.primaryContainer.copy(alpha = .46f), tokens.cardBackground)))
                        .padding(22.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusPill(if (state.running) "运行中" else "已停止", if (state.running) tokens.success else tokens.warning)
                        Spacer(Modifier.weight(1f))
                        HetuLuoShuGlyph(
                            if (state.running) Icons.Rounded.CheckCircle else Icons.Rounded.PauseCircle,
                            null,
                            42.dp,
                            tint = scheme.primary,
                        )
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text("当前配置", color = tokens.textSecondary, style = MaterialTheme.typography.bodySmall)
                        Text(state.config, color = tokens.textPrimary, fontSize = 24.sp, lineHeight = 32.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        MetricColumn(if (state.memoryBytes > 0) bytes(state.memoryBytes) else "—", "内存", Modifier.weight(1f))
                        MetricColumn(avg, "延迟", Modifier.weight(1f))
                        MetricColumn(state.connections.size.toString(), "连接", Modifier.weight(1f))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        MetricColumn(bytes(state.downloadTotal), "下载", Modifier.weight(1f))
                        MetricColumn(bytes(state.uploadTotal), "上传", Modifier.weight(1f))
                        MetricColumn(if (state.panelReady) "在线" else "等待", "控制接口", Modifier.weight(1f))
                    }
                    Button(onClick = onToggle, enabled = busy.isBlank(), modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp), shape = RoundedCornerShape(18.dp)) {
                        Icon(if (state.running) Icons.Rounded.Stop else Icons.Rounded.PlayArrow, null)
                        Spacer(Modifier.width(8.dp))
                        Text(if (state.running) "停止代理" else "启动代理")
                    }
                    if (busy.isNotBlank() || notice.isNotBlank()) {
                        Text(busy.ifBlank { notice }, color = tokens.textSecondary, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                HetuLuoShuSectionHeading("代理工作台", "节点、订阅、连接与规则集中管理")
                LuoShortcut(Icons.Rounded.Public, "打开面板", "查看策略组与连接", onPanel)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LuoProxyPanel(
    state: ProxyComposeState,
    repo: ProxyDashboardRepository,
    delays: MutableMap<String, Long>,
    upRate: Long,
    downRate: Long,
    rates: List<ProxyRateSample>,
    onRefreshState: () -> Unit,
) {
    val tokens = LocalHetuTokens.current
    val scope = rememberCoroutineScope()
    var tab by rememberSaveable { mutableStateOf(LuoProxyTab.Groups) }
    var query by rememberSaveable { mutableStateOf("") }
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var sort by rememberSaveable { mutableStateOf(LuoProxySort.Config) }
    var expanded by rememberSaveable { mutableStateOf<String?>(null) }
    var refreshing by remember { mutableStateOf(false) }
    var providers by remember { mutableStateOf<List<DashboardProviderUi>>(emptyList()) }
    var rules by remember { mutableStateOf<List<ProxyRuleUi>>(emptyList()) }
    var ruleSets by remember { mutableStateOf<List<DashboardRuleSetUi>>(emptyList()) }
    var error by remember { mutableStateOf("") }
    val selectedOverrides = remember { mutableStateMapOf<String, String>() }

    fun selected(group: ProxyGroupUi): String = selectedOverrides[group.name] ?: group.now
    fun delayOf(node: ProxyNodeUi): Long? = delays[node.name] ?: node.lastDelay

    suspend fun loadTab() {
        if (!state.running) return
        try {
            when (tab) {
                LuoProxyTab.Subscriptions -> providers = repo.providers()
                LuoProxyTab.Rules -> rules = repo.rules()
                LuoProxyTab.RuleSets -> ruleSets = repo.ruleSets()
                else -> Unit
            }
        } catch (e: Exception) {
            error = e.message ?: "数据读取失败"
        }
    }

    fun refreshCurrent() {
        if (refreshing) return
        refreshing = true
        error = ""
        scope.launch {
            try {
                when (tab) {
                    LuoProxyTab.Groups -> delays.putAll(repo.globalDelay())
                    LuoProxyTab.Subscriptions -> providers = repo.refreshSubscriptions()
                    LuoProxyTab.RuleSets -> ruleSets = repo.refreshRuleSets()
                    LuoProxyTab.Rules -> rules = repo.rules()
                    LuoProxyTab.Connections, LuoProxyTab.Overview -> onRefreshState()
                }
            } catch (e: Exception) {
                error = e.message ?: "刷新失败"
            } finally { refreshing = false }
        }
    }

    LaunchedEffect(tab, state.running) {
        error = ""
        loadTab()
    }

    val groups = remember(state.groups, query, sort, delays.toMap(), selectedOverrides.toMap()) {
        val filtered = state.groups.filter { group ->
            query.isBlank() || group.name.contains(query, true) || selected(group).contains(query, true) || group.nodes.any { it.name.contains(query, true) }
        }
        if (sort == LuoProxySort.Config) filtered.sortedWith(compareBy({ groupPriority2(it.name) }, { it.name }))
        else filtered.sortedBy { group ->
            group.nodes.firstOrNull { it.name == selected(group) }?.let(::delayOf)?.takeIf { it > 0L } ?: Long.MAX_VALUE
        }
    }
    val visibleRules = remember(rules, query) {
        rules.filter { query.isBlank() || it.type.contains(query, true) || it.payload.contains(query, true) || it.proxy.contains(query, true) }
    }

    PullToRefreshBox(isRefreshing = refreshing, onRefresh = ::refreshCurrent, modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item("panel-header") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    HetuLuoShuTopBar("面板") {
                        HetuLuoShuHeaderAction(Icons.Rounded.Search, "搜索", { searchOpen = !searchOpen })
                        if (tab == LuoProxyTab.Groups) {
                            HetuLuoShuHeaderAction(Icons.Rounded.Sort, "排序", { sort = if (sort == LuoProxySort.Config) LuoProxySort.Delay else LuoProxySort.Config })
                        }
                        HetuLuoShuHeaderAction(Icons.Rounded.Refresh, "刷新", ::refreshCurrent, loading = refreshing)
                    }
                    LuoTabs(tab) { tab = it }
                    AnimatedVisibility(searchOpen, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            placeholder = { Text("搜索当前页面") },
                            leadingIcon = { Icon(Icons.Rounded.Search, null) },
                            shape = RoundedCornerShape(18.dp),
                        )
                    }
                    if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }

            when (tab) {
                LuoProxyTab.Overview -> {
                    item { HetuLuoShuSectionHeading("实时流量", "最近 ${rates.size.coerceAtMost(30) * 3} 秒") }
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            RateSurface("上行", rate(upRate), true, Modifier.weight(1f))
                            RateSurface("下行", rate(downRate), false, Modifier.weight(1f))
                        }
                    }
                    item {
                        HetuLuoShuSurfaceCard {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("累计流量", color = tokens.textPrimary, style = MaterialTheme.typography.titleMedium)
                                Spacer(Modifier.weight(1f))
                                Text("↑ ${bytes(state.uploadTotal)}", color = tokens.success, style = MaterialTheme.typography.bodyMedium)
                                Spacer(Modifier.width(12.dp))
                                Text("↓ ${bytes(state.downloadTotal)}", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }

                LuoProxyTab.Groups -> {
                    item { HetuLuoShuSectionHeading("策略组", "点击卡片展开节点；点击延迟重新测试") }
                    if (groups.isEmpty()) item { EmptyLine("没有可显示的策略组") }
                    groups.chunked(2).forEachIndexed { rowIndex, row ->
                        item("groups-$rowIndex") {
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                row.forEach { group ->
                                    val current = selected(group)
                                    val currentNode = group.nodes.firstOrNull { it.name == current }
                                    StrategyCard(
                                        group = group,
                                        current = current,
                                        delay = currentNode?.let(::delayOf),
                                        measured = group.nodes.count { (delayOf(it) ?: -1L) > 0L },
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
                        val target = row.firstOrNull { it.name == expanded }
                        if (target != null) {
                            item("expanded-$rowIndex-${target.name}") {
                                AnimatedVisibility(visible = true, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                                    NodeSection(
                                        group = target,
                                        selected = selected(target),
                                        delayOf = ::delayOf,
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
                }

                LuoProxyTab.Subscriptions -> {
                    item { HetuLuoShuSectionHeading("订阅", "下拉一次更新全部远程订阅") }
                    if (!state.running) item { EmptyLine("代理未运行") }
                    else if (providers.isEmpty()) item { EmptyLine("当前配置没有远程 HTTP 订阅") }
                    items(providers, key = { it.name }) { provider -> SubscriptionSurface(provider) }
                }

                LuoProxyTab.Connections -> {
                    item { HetuLuoShuSectionHeading("连接", "${state.connections.size} 个活动连接") }
                    if (state.connections.isEmpty()) item { EmptyLine("暂无活动连接") }
                    items(state.connections, key = { it.id }) { connection ->
                        ConnectionRow(connection) { scope.launch { runCatching { repo.closeConnection(connection.id) } } }
                    }
                }

                LuoProxyTab.Rules -> {
                    item { HetuLuoShuSectionHeading("规则", "当前 Mihomo 实际生效规则") }
                    if (!state.running) item { EmptyLine("代理未运行") }
                    else if (visibleRules.isEmpty()) item { EmptyLine("没有匹配的规则") }
                    items(visibleRules.take(1200), key = { it.index }) { rule -> RuleRow(rule) }
                }

                LuoProxyTab.RuleSets -> {
                    item { HetuLuoShuSectionHeading("规则集", "下拉一次更新全部远程规则集") }
                    if (!state.running) item { EmptyLine("代理未运行") }
                    else if (ruleSets.isEmpty()) item { EmptyLine("没有规则集") }
                    items(ruleSets, key = { it.name }) { provider -> RuleSetSurface(provider) }
                }
            }
        }
    }
}

@Composable
private fun LuoTabs(selected: LuoProxyTab, onSelect: (LuoProxyTab) -> Unit) {
    val tokens = LocalHetuTokens.current
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        LuoProxyTab.entries.forEach { tab ->
            val active = tab == selected
            Surface(
                onClick = { onSelect(tab) },
                shape = RoundedCornerShape(16.dp),
                color = if (active) MaterialTheme.colorScheme.primaryContainer.copy(alpha = .72f) else Color.Transparent,
                tonalElevation = 0.dp,
            ) {
                Text(
                    tab.label,
                    modifier = Modifier.padding(horizontal = 15.dp, vertical = 10.dp),
                    color = if (active) MaterialTheme.colorScheme.primary else tokens.textSecondary,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = if (active) FontWeight.Bold else FontWeight.SemiBold,
                )
            }
        }
    }
}

@Composable
private fun StrategyCard(
    group: ProxyGroupUi,
    current: String,
    delay: Long?,
    measured: Int,
    expanded: Boolean,
    modifier: Modifier,
    onOpen: () -> Unit,
    onDelay: () -> Unit,
) {
    val tokens = LocalHetuTokens.current
    val scheme = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) .97f else 1f, spring(dampingRatio = .72f, stiffness = 560f), label = "strategyPress")
    val arrow by animateFloatAsState(if (expanded) 180f else 0f, label = "strategyArrow")
    Surface(
        modifier = modifier.height(102.dp).graphicsLayer { scaleX = scale; scaleY = scale }
            .clickable(interactionSource = interaction, indication = null, onClick = onOpen),
        shape = RoundedCornerShape(24.dp),
        color = tokens.cardBackground,
        shadowElevation = 1.dp,
    ) {
        Column(
            Modifier.fillMaxSize()
                .background(
                    Brush.linearGradient(
                        if (expanded) listOf(scheme.primaryContainer.copy(alpha = .62f), tokens.cardBackground)
                        else listOf(tokens.elevatedCardBackground.copy(alpha = .72f), tokens.cardBackground),
                    ),
                )
                .padding(13.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StrategyIcon(group, 30)
                Spacer(Modifier.width(8.dp))
                Text(group.name, Modifier.weight(1f), color = tokens.textPrimary, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Icon(Icons.Rounded.KeyboardArrowDown, null, tint = tokens.textSecondary, modifier = Modifier.size(18.dp).graphicsLayer { rotationZ = arrow })
            }
            Text("${strategyType(group.type)} · $measured/${group.nodes.size}", color = tokens.textSecondary, style = MaterialTheme.typography.labelSmall)
            Spacer(Modifier.weight(1f))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(current, Modifier.weight(1f), color = tokens.textSecondary, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                LatencyPill(delay, onDelay)
            }
        }
    }
}

@Composable
private fun NodeSection(
    group: ProxyGroupUi,
    selected: String,
    delayOf: (ProxyNodeUi) -> Long?,
    onSelect: (ProxyNodeUi) -> Unit,
    onDelay: (ProxyNodeUi) -> Unit,
) {
    val tokens = LocalHetuTokens.current
    HetuLuoShuSurfaceCard(emphasized = true) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StrategyIcon(group, 34)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(group.name, color = tokens.textPrimary, style = MaterialTheme.typography.titleMedium)
                Text("${group.nodes.size} 节点 · 当前 $selected", color = tokens.textSecondary, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        group.nodes.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { node ->
                    NodeTile(node, delayOf(node), selected == node.name, Modifier.weight(1f), { onSelect(node) }, { onDelay(node) })
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun NodeTile(
    node: ProxyNodeUi,
    delay: Long?,
    selected: Boolean,
    modifier: Modifier,
    onSelect: () -> Unit,
    onDelay: () -> Unit,
) {
    val tokens = LocalHetuTokens.current
    val scheme = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) .965f else 1f, spring(dampingRatio = .7f, stiffness = 600f), label = "nodePress")
    val bg by animateColorAsState(
        if (selected) scheme.primaryContainer.copy(alpha = .72f) else tokens.elevatedCardBackground,
        label = "nodeColor",
    )
    Surface(
        modifier = modifier.height(78.dp).graphicsLayer { scaleX = scale; scaleY = scale }
            .clickable(interactionSource = interaction, indication = null, onClick = onSelect),
        shape = RoundedCornerShape(18.dp),
        color = bg,
        tonalElevation = 0.dp,
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(node.name, Modifier.weight(1f), color = tokens.textPrimary, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (selected) Icon(Icons.Rounded.CheckCircle, null, tint = scheme.primary, modifier = Modifier.size(17.dp))
            }
            Spacer(Modifier.weight(1f))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(listOfNotNull(node.type.takeIf { it.isNotBlank() }, "UDP".takeIf { node.udp }).joinToString(" · ").ifBlank { "节点" }, Modifier.weight(1f), color = tokens.textSecondary, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                LatencyPill(delay, onDelay)
            }
        }
    }
}

@Composable
private fun LatencyPill(delay: Long?, onClick: () -> Unit) {
    val tokens = LocalHetuTokens.current
    val scheme = MaterialTheme.colorScheme
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
        delay <= 180L -> Color(0xFF16825D)
        delay <= 350L -> scheme.primary
        delay <= 650L -> tokens.warning
        else -> scheme.error
    }
    Surface(
        modifier = Modifier.clip(CircleShape).clickable(onClick = onClick),
        shape = CircleShape,
        color = color.copy(alpha = .13f),
        tonalElevation = 0.dp,
    ) {
        Text(text, Modifier.padding(horizontal = 8.dp, vertical = 4.dp), color = color, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
private fun SubscriptionSurface(provider: DashboardProviderUi) {
    val tokens = LocalHetuTokens.current
    val scheme = MaterialTheme.colorScheme
    val percent = ((1f - provider.ratio) * 100).toInt().coerceIn(0, 100)
    HetuLuoShuSurfaceCard(emphasized = provider.hasSubscriptionInfo && provider.total > 0L) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(provider.name, color = tokens.textPrimary, style = MaterialTheme.typography.titleLarge)
                val meta = buildList {
                    add("${provider.nodes.size} 节点")
                    provider.updatedAt.takeIf { it.isNotBlank() }?.let { add("更新于 ${shortTime2(it)}") }
                }.joinToString(" · ")
                Text(meta, color = tokens.textSecondary, style = MaterialTheme.typography.bodySmall)
            }
            if (provider.hasSubscriptionInfo && provider.total > 0L) {
                Surface(shape = RoundedCornerShape(12.dp), color = scheme.primaryContainer.copy(alpha = .70f)) {
                    Text("$percent%", Modifier.padding(horizontal = 10.dp, vertical = 6.dp), color = scheme.primary, style = MaterialTheme.typography.titleSmall)
                }
            }
        }
        if (provider.hasSubscriptionInfo && provider.total > 0L) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MetricColumn(bytes(provider.upload), "上传", Modifier.weight(1f))
                MetricColumn(bytes(provider.download), "下载", Modifier.weight(1f))
                MetricColumn(bytes(provider.remaining), "剩余", Modifier.weight(1f), scheme.primary)
            }
            LinearProgressIndicator(progress = { provider.ratio }, modifier = Modifier.fillMaxWidth().height(5.dp).clip(CircleShape))
            Row {
                Text("已用 ${bytes(provider.used)}", color = tokens.textSecondary, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.weight(1f))
                Text("总计 ${bytes(provider.total)}", color = tokens.textSecondary, style = MaterialTheme.typography.bodySmall)
            }
            if (provider.expire > 0L) Text("到期 ${formatEpoch(provider.expire)}", color = tokens.textSecondary, style = MaterialTheme.typography.bodySmall)
        } else {
            Text("服务端未提供流量信息", color = tokens.textSecondary, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun RuleSetSurface(provider: DashboardRuleSetUi) {
    val tokens = LocalHetuTokens.current
    HetuLuoShuSurfaceCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(provider.name, Modifier.weight(1f), color = tokens.textPrimary, style = MaterialTheme.typography.titleMedium)
            if (provider.ruleCount > 0) Text("${provider.ruleCount} 条", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleSmall)
        }
        Text(listOf(provider.behavior, provider.format, provider.vehicleType).filter { it.isNotBlank() }.joinToString(" / "), color = tokens.textSecondary, style = MaterialTheme.typography.bodySmall)
        provider.updatedAt.takeIf { it.isNotBlank() }?.let { Text("更新于 ${shortTime2(it)}", color = tokens.textSecondary, style = MaterialTheme.typography.labelSmall) }
    }
}

@Composable
private fun ConnectionRow(connection: ProxyConnectionUi, onClose: () -> Unit) {
    val tokens = LocalHetuTokens.current
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (connection.appIcon != null) {
                Image(connection.appIcon.asImageBitmap(), null, Modifier.size(34.dp).clip(RoundedCornerShape(9.dp)))
                Spacer(Modifier.width(10.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(connection.host, color = tokens.textPrimary, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val app = connection.appName.ifBlank { connection.packageName.ifBlank { connection.process } }
                Text(listOf(app, connection.network, connection.inbound).filter { it.isNotBlank() }.joinToString(" · "), color = tokens.textSecondary, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconButton(onClick = onClose, modifier = Modifier.size(36.dp)) { Icon(Icons.Rounded.Close, "断开", tint = tokens.textSecondary, modifier = Modifier.size(18.dp)) }
        }
        val route = listOf(connection.rulePayload.ifBlank { connection.rule }, connection.chain).filter { it.isNotBlank() }.joinToString(" / ")
        if (route.isNotBlank()) Text(route, color = tokens.textSecondary, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Row {
            Text("↑ ${bytes(connection.upload)}", color = tokens.success, style = MaterialTheme.typography.labelSmall)
            Spacer(Modifier.weight(1f))
            Text("↓ ${bytes(connection.download)}", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall)
        }
        HorizontalDivider(color = tokens.textSecondary.copy(alpha = .10f))
    }
}

@Composable
private fun RuleRow(rule: ProxyRuleUi) {
    val tokens = LocalHetuTokens.current
    Row(Modifier.fillMaxWidth().padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(rule.type, color = tokens.textPrimary, style = MaterialTheme.typography.titleSmall)
            Text(rule.payload.ifBlank { "—" }, color = tokens.textSecondary, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(12.dp))
        Text(rule.proxy.ifBlank { "DIRECT" }, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleSmall)
    }
    HorizontalDivider(color = tokens.textSecondary.copy(alpha = .10f))
}

@Composable
private fun RateSurface(title: String, value: String, up: Boolean, modifier: Modifier) {
    val tokens = LocalHetuTokens.current
    val color = if (up) tokens.success else MaterialTheme.colorScheme.primary
    HetuLuoShuSurfaceCard(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = RoundedCornerShape(14.dp), color = color.copy(alpha = .12f), modifier = Modifier.size(44.dp)) {
                Box(contentAlignment = Alignment.Center) { Icon(if (up) Icons.Rounded.ArrowUpward else Icons.Rounded.ArrowDownward, null, tint = color) }
            }
            Spacer(Modifier.width(10.dp))
            Column {
                Text(title, color = tokens.textSecondary, style = MaterialTheme.typography.bodySmall)
                Text(value, color = color, style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

@Composable
private fun LuoProxyTools(state: ProxyComposeState) {
    val context = LocalContext.current
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item { HetuLuoShuTopBar("工具") }
        item { HetuLuoShuSectionHeading("代理工具", "核心、订阅和基础运行参数") }
        item { LuoToolRow(Icons.Rounded.Storage, "内核管理", "Mihomo 更新与其他核心按需下载") { context.startActivity(Intent(context, ProxyCoreActivity::class.java)) } }
        item { LuoToolRow(Icons.Rounded.CloudSync, "订阅与配置", "订阅、YAML 与配置切换") { context.startActivity(Intent(context, ProxySubscriptionActivity::class.java)) } }
        item { LuoToolRow(Icons.Rounded.Tune, "基础代理配置", "Root TPROXY 与运行参数") { context.startActivity(Intent(context, RootTproxyActivity::class.java)) } }
        item { EmptyLine("${state.core} · ${state.mode}") }
    }
}

@Composable
private fun LuoProxySettings(state: ProxyComposeState, controller: ProxyComposeController, onChanged: () -> Unit) {
    val tokens = LocalHetuTokens.current
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item { HetuLuoShuTopBar("设置") }
        item {
            HetuLuoShuSurfaceCard {
                Text("内核", color = tokens.textPrimary, style = MaterialTheme.typography.titleMedium)
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    controller.cores().forEach { (id, label) ->
                        val active = state.core == label
                        Surface(onClick = { controller.setCore(id); onChanged() }, shape = RoundedCornerShape(15.dp), color = if (active) MaterialTheme.colorScheme.primaryContainer else tokens.elevatedCardBackground) {
                            Text(label, Modifier.padding(horizontal = 12.dp, vertical = 8.dp), color = if (active) MaterialTheme.colorScheme.primary else tokens.textSecondary, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
        item { HetuLuoShuSurfaceCard { Text("当前运行", color = tokens.textPrimary, style = MaterialTheme.typography.titleMedium); Text("${state.core} · ${state.mode} · IPv6 ${state.ipv6}", color = tokens.textSecondary, style = MaterialTheme.typography.bodySmall) } }
    }
}

@Composable
private fun LuoShortcut(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    val tokens = LocalHetuTokens.current
    Surface(onClick = onClick, shape = RoundedCornerShape(24.dp), color = tokens.cardBackground, shadowElevation = 1.dp) {
        Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = RoundedCornerShape(15.dp), color = tokens.elevatedCardBackground) {
                Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) { HetuLuoShuGlyph(icon, null, HetuLuoShuIconTokens.StatusGlyph, tint = MaterialTheme.colorScheme.primary) }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, color = tokens.textPrimary, style = MaterialTheme.typography.titleMedium)
                Text(subtitle, color = tokens.textSecondary, style = MaterialTheme.typography.bodySmall)
            }
            HetuLuoShuGlyph(Icons.Rounded.ChevronRight, null, HetuLuoShuIconTokens.TrailingGlyph, tint = tokens.textSecondary)
        }
    }
}

@Composable
private fun LuoToolRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String, onClick: () -> Unit) = LuoShortcut(icon, title, subtitle, onClick)

@Composable
private fun StrategyIcon(group: ProxyGroupUi, size: Int) {
    val bitmap = remember(group.iconPath) { group.iconPath.takeIf { it.isNotBlank() }?.let(BitmapFactory::decodeFile) }
    if (bitmap != null) {
        Image(bitmap.asImageBitmap(), null, Modifier.size(size.dp).clip(RoundedCornerShape(9.dp)), contentScale = ContentScale.Fit)
    } else {
        Surface(shape = RoundedCornerShape(9.dp), color = LocalHetuTokens.current.elevatedCardBackground, modifier = Modifier.size(size.dp)) {
            Box(contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Public, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size((size - 11).dp)) }
        }
    }
}

@Composable
private fun StatusPill(text: String, color: Color) {
    Surface(shape = CircleShape, color = LocalHetuTokens.current.cardBackground.copy(alpha = .72f)) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(6.dp).background(color, CircleShape))
            Spacer(Modifier.width(6.dp))
            Text(text, color = LocalHetuTokens.current.textPrimary, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun MetricColumn(value: String, label: String, modifier: Modifier, color: Color = LocalHetuTokens.current.textPrimary) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(value, color = color, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(label, color = LocalHetuTokens.current.textSecondary, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun EmptyLine(text: String) {
    Text(text, Modifier.fillMaxWidth().padding(horizontal = 2.dp, vertical = 10.dp), color = LocalHetuTokens.current.textSecondary, style = MaterialTheme.typography.bodyMedium)
}

private fun strategyType(raw: String): String = when (raw.lowercase(Locale.ROOT)) {
    "urltest", "url-test" -> "URLTest"
    "fallback" -> "Fallback"
    "loadbalance", "load-balance" -> "LoadBalance"
    "selector" -> "Selector"
    else -> raw.ifBlank { "Group" }
}

private fun groupPriority2(name: String): Int {
    val order = listOf("节点选择", "手动选择", "故障转移", "香港节点", "台湾节点", "日本节点", "新加坡节点", "韩国节点", "美国节点", "AI", "YouTube", "Google", "TikTok", "Telegram", "GitHub", "Emby", "Netflix", "Spotify", "Windows", "Apple", "Game", "Download", "bilibili")
    return order.indexOfFirst { name.contains(it, true) }.let { if (it >= 0) it else 1000 }
}

private fun bytes(value: Long): String = when {
    value >= 1L shl 30 -> "%.2f GB".format(Locale.US, value.toDouble() / (1L shl 30))
    value >= 1L shl 20 -> "%.1f MB".format(Locale.US, value.toDouble() / (1L shl 20))
    value >= 1L shl 10 -> "%.0f KB".format(Locale.US, value.toDouble() / (1L shl 10))
    else -> "$value B"
}

private fun rate(value: Long): String = "${bytes(value)}/s"

private fun shortTime2(raw: String): String {
    if (raw.isBlank() || raw.startsWith("0001-")) return "—"
    return raw.replace('T', ' ').substringBefore('.').replace("Z", "").take(16)
}

private fun formatEpoch(epoch: Long): String = try {
    SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(epoch * 1000L))
} catch (_: Exception) { "—" }
