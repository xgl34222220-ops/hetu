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
import androidx.compose.ui.graphics.luminance
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

/** LuoShu visual language + Mihomo-native data model, optimized for phone use. */
class ProxyWorkspaceActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { BichenTheme { ProxyWorkspaceApp { finish() } } }
    }
}

private enum class WorkspacePage { Home, Panel, Tools, Settings }
private enum class WorkspaceTab(val label: String) {
    Overview("概览"), Groups("节点"), Subscription("订阅"), Connections("连接"), Rules("规则"), RuleSets("规则集")
}
private enum class WorkspaceSort { Config, Delay }
private data class TrafficSample(val up: Long, val down: Long)

@Composable
private fun ProxyWorkspaceApp(onBack: () -> Unit) {
    val context = LocalContext.current
    val controller = remember { ProxyComposeController(context) }
    val scope = rememberCoroutineScope()
    val haze = rememberHazeState()
    val backdrop = rememberLayerBackdrop()
    val liquid = isRuntimeShaderSupported()
    var page by rememberSaveable { mutableStateOf(WorkspacePage.Home) }
    var state by remember { mutableStateOf(ProxyComposeState()) }
    var revision by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf("") }
    var notice by remember { mutableStateOf("") }
    var testing by remember { mutableStateOf(false) }
    var upRate by remember { mutableLongStateOf(0L) }
    var downRate by remember { mutableLongStateOf(0L) }
    var lastUp by remember { mutableLongStateOf(0L) }
    var lastDown by remember { mutableLongStateOf(0L) }
    var lastSampleAt by remember { mutableLongStateOf(0L) }
    val traffic = remember { mutableStateListOf<TrafficSample>() }
    val delays = remember { mutableStateMapOf<String, Long>() }

    suspend fun refreshState() {
        try {
            val next = controller.state()
            val now = SystemClock.elapsedRealtime()
            if (lastSampleAt > 0L && now > lastSampleAt && next.uploadTotal >= lastUp && next.downloadTotal >= lastDown) {
                val elapsed = now - lastSampleAt
                upRate = ((next.uploadTotal - lastUp) * 1000L / elapsed).coerceAtLeast(0L)
                downRate = ((next.downloadTotal - lastDown) * 1000L / elapsed).coerceAtLeast(0L)
                traffic += TrafficSample(upRate, downRate)
                while (traffic.size > 30) traffic.removeAt(0)
            }
            lastUp = next.uploadTotal
            lastDown = next.downloadTotal
            lastSampleAt = now
            next.groups.flatMap { it.nodes }.forEach { node ->
                if (!delays.containsKey(node.name) && node.lastDelay != null) delays[node.name] = node.lastDelay
            }
            state = next
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (e: Exception) {
            notice = e.message ?: "状态读取失败"
        }
    }

    fun globalTest() {
        if (!state.running || testing) return
        testing = true
        notice = "正在测速…"
        scope.launch {
            try {
                val result = controller.globalDelay()
                delays.putAll(result)
                val ok = result.values.count { it > 0 }
                notice = if (ok == 0) "测速完成 · 暂无可用结果" else "测速完成 · $ok/${result.size} 可用"
            } catch (e: Exception) {
                notice = e.message ?: "测速失败"
            } finally {
                testing = false
            }
        }
    }

    fun toggle() {
        if (busy.isNotBlank()) return
        scope.launch {
            busy = if (state.running) "正在停止…" else "正在启动…"
            try {
                if (state.running) controller.stop { busy = it } else controller.start { busy = it }
            } catch (e: Exception) {
                notice = e.message ?: "代理操作失败"
            } finally {
                busy = ""
                revision++
            }
        }
    }

    LaunchedEffect(revision) { refreshState() }
    LaunchedEffect(Unit) {
        runCatching { controller.ensureIcons() }
        refreshState()
        while (true) {
            delay(3000)
            refreshState()
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
                .padding(bottom = 106.dp),
        ) {
            when (page) {
                WorkspacePage.Home -> WorkspaceHome(
                    state, delays, busy, notice, testing, onBack,
                    onRefresh = { revision++ }, onToggle = ::toggle,
                    onPanel = { page = WorkspacePage.Panel }, onSpeed = ::globalTest,
                )
                WorkspacePage.Panel -> WorkspacePanel(
                    state = state,
                    controller = controller,
                    delays = delays,
                    notice = notice,
                    testing = testing,
                    upRate = upRate,
                    downRate = downRate,
                    traffic = traffic,
                    onGlobalDelay = ::globalTest,
                )
                WorkspacePage.Tools -> WorkspaceTools(state, testing, ::globalTest)
                WorkspacePage.Settings -> WorkspaceSettings(state, controller) { revision++ }
            }
        }
        BichenGlassDock(
            items = dock,
            selected = page.ordinal,
            onSelect = { page = WorkspacePage.entries[it] },
            hazeState = haze,
            backdrop = backdrop.takeIf { liquid },
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

@Composable
private fun WorkspaceHome(
    state: ProxyComposeState,
    delays: Map<String, Long>,
    busy: String,
    notice: String,
    testing: Boolean,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onToggle: () -> Unit,
    onPanel: () -> Unit,
    onSpeed: () -> Unit,
) {
    val tokens = LocalBichenTokens.current
    val scheme = MaterialTheme.colorScheme
    val good = delays.values.filter { it > 0 }
    val avg = if (good.isEmpty()) "—" else "${good.average().toInt()} ms"
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item { WorkspaceDetailBar("代理", "${state.core} · ${state.mode}", onBack, onRefresh) }
        item {
            Surface(shape = RoundedCornerShape(28.dp), color = tokens.cardBackground, shadowElevation = 2.dp) {
                Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(15.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusChip(if (state.running) "运行中" else "已停止", if (state.running) tokens.success else tokens.warning)
                        Spacer(Modifier.weight(1f))
                        Icon(if (state.running) Icons.Rounded.CheckCircle else Icons.Rounded.PauseCircle, null, tint = scheme.primary, modifier = Modifier.size(42.dp))
                    }
                    Text(state.config, color = tokens.textPrimary, fontSize = 22.sp, lineHeight = 29.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StatCell(if (state.memoryBytes > 0) fmtBytes(state.memoryBytes) else "—", "内存", Modifier.weight(1f))
                        StatCell(avg, "全局延迟", Modifier.weight(1f))
                        StatCell(state.connections.size.toString(), "连接", Modifier.weight(1f))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StatCell(fmtBytes(state.downloadTotal), "下载", Modifier.weight(1f))
                        StatCell(fmtBytes(state.uploadTotal), "上传", Modifier.weight(1f))
                        StatCell(if (state.panelReady) "在线" else "等待", "控制接口", Modifier.weight(1f))
                    }
                    if (busy.isNotBlank() || notice.isNotBlank()) Text(busy.ifBlank { notice }, color = tokens.textSecondary, style = MaterialTheme.typography.bodySmall)
                    Button(onClick = onToggle, enabled = busy.isBlank(), modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp), shape = RoundedCornerShape(18.dp)) {
                        Icon(if (state.running) Icons.Rounded.Stop else Icons.Rounded.PlayArrow, null)
                        Spacer(Modifier.width(8.dp)); Text(if (state.running) "停止代理" else "启动代理")
                    }
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ShortcutCard(Icons.Rounded.Public, "策略面板", "节点 · 连接 · 规则", Modifier.weight(1f), onPanel)
                ShortcutCard(Icons.Rounded.Speed, if (testing) "测速中" else "全局测速", "原生批量测速", Modifier.weight(1f), onSpeed)
            }
        }
    }
}

@Composable
private fun WorkspacePanel(
    state: ProxyComposeState,
    controller: ProxyComposeController,
    delays: MutableMap<String, Long>,
    notice: String,
    testing: Boolean,
    upRate: Long,
    downRate: Long,
    traffic: List<TrafficSample>,
    onGlobalDelay: () -> Unit,
) {
    val context = LocalContext.current
    val tokens = LocalBichenTokens.current
    val scope = rememberCoroutineScope()
    var tab by rememberSaveable { mutableStateOf(WorkspaceTab.Overview) }
    var query by rememberSaveable { mutableStateOf("") }
    var expanded by rememberSaveable { mutableStateOf<String?>(null) }
    var sort by rememberSaveable { mutableStateOf(WorkspaceSort.Config) }
    var searchVisible by rememberSaveable { mutableStateOf(false) }
    var rules by remember { mutableStateOf<List<ProxyRuleUi>>(emptyList()) }
    var ruleProviders by remember { mutableStateOf<List<ProxyRuleProviderUi>>(emptyList()) }
    var subscriptions by remember { mutableStateOf<List<ProxySubscriptionUi>>(emptyList()) }
    var panelError by remember { mutableStateOf("") }
    var loadingTab by remember { mutableStateOf(false) }
    val providerBusy = remember { mutableStateMapOf<String, Boolean>() }
    val selectedOverrides = remember { mutableStateMapOf<String, String>() }

    fun effectiveDelay(node: ProxyNodeUi): Long? = delays[node.name] ?: node.lastDelay
    fun selectedNow(group: ProxyGroupUi): String = selectedOverrides[group.name] ?: group.now

    val groups = remember(state.groups, delays, query, sort, selectedOverrides.toMap()) {
        val filtered = state.groups.filter { g ->
            query.isBlank() || g.name.contains(query, true) || selectedNow(g).contains(query, true) || g.nodes.any { it.name.contains(query, true) }
        }
        if (sort == WorkspaceSort.Config) filtered.sortedWith(compareBy({ groupPriority(it.name) }, { it.name }))
        else filtered.sortedBy { g ->
            g.nodes.firstOrNull { it.name == selectedNow(g) }?.let(::effectiveDelay)?.takeIf { it > 0 } ?: Long.MAX_VALUE
        }
    }

    val filteredRules = remember(rules, query) {
        rules.filter { query.isBlank() || it.type.contains(query, true) || it.payload.contains(query, true) || it.proxy.contains(query, true) }
    }
    val filteredProviders = remember(ruleProviders, query) {
        ruleProviders.filter { query.isBlank() || it.name.contains(query, true) || it.behavior.contains(query, true) }
    }

    LaunchedEffect(tab, state.running) {
        if (!state.running) return@LaunchedEffect
        loadingTab = true
        panelError = ""
        try {
            when (tab) {
                WorkspaceTab.Rules -> rules = controller.rules()
                WorkspaceTab.RuleSets -> ruleProviders = controller.ruleProviders()
                WorkspaceTab.Subscription -> subscriptions = controller.subscriptions()
                else -> Unit
            }
        } catch (e: Exception) {
            panelError = e.message ?: "面板数据读取失败"
        } finally {
            loadingTab = false
        }
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item("top") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth().statusBarsPadding().heightIn(min = 58.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("面板", Modifier.weight(1f), color = tokens.textPrimary, fontSize = 30.sp, lineHeight = 38.sp, fontWeight = FontWeight.Bold)
                    SmallRound(Icons.Rounded.Search, "搜索") { searchVisible = !searchVisible }
                    if (tab == WorkspaceTab.Groups) {
                        Spacer(Modifier.width(6.dp))
                        SmallRound(Icons.Rounded.Sort, "排序") { sort = if (sort == WorkspaceSort.Config) WorkspaceSort.Delay else WorkspaceSort.Config }
                        Spacer(Modifier.width(6.dp))
                        SmallRound(Icons.Rounded.Speed, "全部测速", enabled = state.running && !testing, onClick = onGlobalDelay)
                    }
                }
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    WorkspaceTab.entries.forEach { item ->
                        FilterChip(selected = tab == item, onClick = { tab = item }, label = { Text(item.label) })
                    }
                }
                AnimatedVisibility(searchVisible, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                    OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), singleLine = true, placeholder = { Text("搜索当前面板") }, leadingIcon = { Icon(Icons.Rounded.Search, null) }, shape = RoundedCornerShape(16.dp))
                }
                if (testing) LinearProgressIndicator(Modifier.fillMaxWidth().height(3.dp).clip(CircleShape))
                if (panelError.isNotBlank()) Text(panelError, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                else if (notice.isNotBlank() && tab == WorkspaceTab.Groups) Text(notice, color = tokens.textSecondary, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }

        when (tab) {
            WorkspaceTab.Overview -> {
                item("rates") {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        RateCard(Icons.Rounded.ArrowUpward, "上行速度", fmtRate(upRate), true, Modifier.weight(1f))
                        RateCard(Icons.Rounded.ArrowDownward, "下行速度", fmtRate(downRate), false, Modifier.weight(1f))
                    }
                }
                item("totals") {
                    Surface(shape = RoundedCornerShape(20.dp), color = tokens.cardBackground) {
                        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("总流量", color = tokens.textPrimary, style = MaterialTheme.typography.titleSmall)
                            Spacer(Modifier.weight(1f))
                            Text("上行 ${fmtBytes(state.uploadTotal)}", color = tokens.success, style = MaterialTheme.typography.bodyMedium)
                            Spacer(Modifier.width(12.dp))
                            Text("下行 ${fmtBytes(state.downloadTotal)}", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                item("trend") { TrafficTrendCard(traffic) }
            }

            WorkspaceTab.Groups -> {
                if (groups.isEmpty()) item { EmptyCompact("没有可显示的策略组") }
                groups.chunked(2).forEachIndexed { rowIndex, row ->
                    item("g-$rowIndex") {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            row.forEach { group ->
                                val current = selectedNow(group)
                                val currentDelay = group.nodes.firstOrNull { it.name == current }?.let(::effectiveDelay)
                                DenseGroupCard(
                                    group = group,
                                    current = current,
                                    delay = currentDelay,
                                    measured = group.nodes.count { (effectiveDelay(it) ?: -1) > 0 },
                                    expanded = expanded == group.name,
                                    modifier = Modifier.weight(1f),
                                ) { expanded = if (expanded == group.name) null else group.name }
                            }
                            if (row.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                    item("expanded-row-$rowIndex") {
                        val target = row.firstOrNull { it.name == expanded }?.name
                        AnimatedContent(
                            targetState = target,
                            transitionSpec = { (fadeIn() + expandVertically()) togetherWith (fadeOut() + shrinkVertically()) },
                            label = "strategy-expand-$rowIndex",
                        ) { groupName ->
                            if (groupName == null) Spacer(Modifier.height(0.dp))
                            else row.firstOrNull { it.name == groupName }?.let { group ->
                                DenseNodeSection(
                                    group = group,
                                    delays = delays,
                                    controller = controller,
                                    selected = selectedNow(group),
                                    onSelectLocal = { node -> selectedOverrides[group.name] = node },
                                    onSelectFailed = { selectedOverrides.remove(group.name) },
                                )
                            }
                        }
                    }
                }
            }

            WorkspaceTab.Subscription -> {
                if (loadingTab) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                if (!loadingTab && subscriptions.isEmpty()) item { EmptyCompact("当前配置没有可显示的订阅") }
                items(subscriptions, key = { it.name }) { subscription ->
                    Surface(
                        onClick = { context.startActivity(Intent(context, ProxySubscriptionActivity::class.java)) },
                        shape = RoundedCornerShape(20.dp),
                        color = tokens.cardBackground,
                    ) {
                        Row(Modifier.fillMaxWidth().padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
                            Surface(shape = RoundedCornerShape(12.dp), color = tokens.elevatedCardBackground, modifier = Modifier.size(42.dp)) {
                                Box(contentAlignment = Alignment.Center) { Icon(Icons.Rounded.CloudSync, null, tint = MaterialTheme.colorScheme.primary) }
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(subscription.name, color = tokens.textPrimary, style = MaterialTheme.typography.titleSmall)
                                Text(if (subscription.placeholder) "等待填写订阅地址" else "已配置 · 点击管理", color = tokens.textSecondary, style = MaterialTheme.typography.bodySmall)
                            }
                            Icon(Icons.Rounded.ChevronRight, null, tint = tokens.textSecondary)
                        }
                    }
                }
            }

            WorkspaceTab.Connections -> {
                item("conn-stats") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StatCell(fmtBytes(state.downloadTotal), "下载", Modifier.weight(1f))
                        StatCell(fmtBytes(state.uploadTotal), "上传", Modifier.weight(1f))
                        StatCell(state.connections.size.toString(), "连接", Modifier.weight(1f))
                    }
                }
                items(state.connections, key = { it.id }) { c ->
                    ConnectionCard(c) {
                        scope.launch { runCatching { controller.closeConnection(c.id) } }
                    }
                }
            }

            WorkspaceTab.Rules -> {
                if (loadingTab) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                if (!loadingTab && filteredRules.isEmpty()) item { EmptyCompact(if (state.running) "Mihomo 没有返回规则" else "代理未运行") }
                items(filteredRules, key = { it.index }) { rule -> RuleCard(rule) }
            }

            WorkspaceTab.RuleSets -> {
                if (loadingTab) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                if (!loadingTab && filteredProviders.isEmpty()) item { EmptyCompact(if (state.running) "没有规则集" else "代理未运行") }
                items(filteredProviders, key = { it.name }) { provider ->
                    RuleProviderCard(provider, providerBusy[provider.name] == true) {
                        if (providerBusy[provider.name] == true) return@RuleProviderCard
                        providerBusy[provider.name] = true
                        scope.launch {
                            try {
                                controller.updateRuleProvider(provider.name)
                                ruleProviders = controller.ruleProviders()
                            } catch (e: Exception) {
                                panelError = e.message ?: "规则集更新失败"
                            } finally {
                                providerBusy.remove(provider.name)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DenseGroupCard(group: ProxyGroupUi, current: String, delay: Long?, measured: Int, expanded: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val tokens = LocalBichenTokens.current
    val scheme = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) .965f else 1f, spring(stiffness = 650f), label = "group-press")
    val container by animateColorAsState(if (expanded) scheme.primaryContainer.copy(alpha = .58f) else tokens.cardBackground, label = "group-color")
    val arrow by animateFloatAsState(if (expanded) 180f else 0f, label = "group-arrow")
    Surface(
        modifier = modifier.height(108.dp).graphicsLayer { scaleX = scale; scaleY = scale }
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        color = container,
        shadowElevation = if (expanded) 2.dp else 1.dp,
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                GroupIcon(group, 30)
                Spacer(Modifier.width(8.dp))
                Text(group.name, Modifier.weight(1f), color = tokens.textPrimary, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Icon(Icons.Rounded.KeyboardArrowDown, null, tint = tokens.textSecondary, modifier = Modifier.size(18.dp).graphicsLayer { rotationZ = arrow })
            }
            Text("${groupType(group.type)} · $measured/${group.nodes.size}", color = tokens.textSecondary, fontSize = 11.sp, maxLines = 1)
            Spacer(Modifier.weight(1f))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(current, Modifier.weight(1f), color = tokens.textSecondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                DelayBadge(delay)
            }
        }
    }
}

@Composable
private fun DenseNodeSection(
    group: ProxyGroupUi,
    delays: MutableMap<String, Long>,
    controller: ProxyComposeController,
    selected: String,
    onSelectLocal: (String) -> Unit,
    onSelectFailed: () -> Unit,
) {
    val tokens = LocalBichenTokens.current
    val scope = rememberCoroutineScope()
    Surface(shape = RoundedCornerShape(22.dp), color = tokens.elevatedCardBackground.copy(alpha = .58f)) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                GroupIcon(group, 28); Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(group.name, color = tokens.textPrimary, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text("${group.nodes.size} 节点 · 当前 $selected", color = tokens.textSecondary, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            group.nodes.chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    row.forEach { node ->
                        DenseNodeCard(node, delays[node.name] ?: node.lastDelay, selected == node.name, Modifier.weight(1f), onSelect = {
                            val previous = selected
                            onSelectLocal(node.name)
                            scope.launch {
                                try { controller.select(group.name, node.name) }
                                catch (_: Exception) {
                                    if (previous != node.name) onSelectFailed()
                                }
                            }
                        }, onDelay = {
                            scope.launch {
                                delays[node.name] = -2
                                delays[node.name] = runCatching { controller.delay(node.name) }.getOrDefault(-1)
                            }
                        })
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun DenseNodeCard(node: ProxyNodeUi, delay: Long?, selected: Boolean, modifier: Modifier, onSelect: () -> Unit, onDelay: () -> Unit) {
    val tokens = LocalBichenTokens.current
    val scheme = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) .96f else 1f, spring(stiffness = 700f), label = "node-press")
    val container by animateColorAsState(if (selected) scheme.primaryContainer.copy(alpha = .72f) else tokens.cardBackground, label = "node-color")
    Surface(
        modifier = modifier.height(88.dp).graphicsLayer { scaleX = scale; scaleY = scale }
            .clickable(interactionSource = interaction, indication = null, onClick = onSelect),
        shape = RoundedCornerShape(18.dp),
        color = container,
        shadowElevation = if (selected) 2.dp else 0.dp,
    ) {
        Column(Modifier.padding(11.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(node.name, Modifier.weight(1f), color = tokens.textPrimary, fontSize = 13.sp, lineHeight = 17.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                AnimatedVisibility(selected, enter = fadeIn(), exit = fadeOut()) {
                    Icon(Icons.Rounded.CheckCircle, "已选择", tint = scheme.primary, modifier = Modifier.size(16.dp))
                }
            }
            Spacer(Modifier.weight(1f))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(buildString {
                    if (node.udp) append("UDP")
                    if (node.udp && node.type.isNotBlank()) append(" · ")
                    append(node.type.ifBlank { "节点" })
                }, Modifier.weight(1f), color = tokens.textSecondary, fontSize = 10.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Box(Modifier.clip(CircleShape).clickable(onClick = onDelay)) { DelayBadge(delay) }
            }
        }
    }
}

@Composable
private fun RuleCard(rule: ProxyRuleUi) {
    val tokens = LocalBichenTokens.current
    Surface(shape = RoundedCornerShape(20.dp), color = tokens.cardBackground) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 15.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(rule.type, color = tokens.textPrimary, style = MaterialTheme.typography.titleSmall)
                Text(rule.payload.ifBlank { "—" }, color = tokens.textSecondary, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (rule.hitCount > 0) Text("命中 ${rule.hitCount}", color = tokens.textSecondary, style = MaterialTheme.typography.labelSmall)
            }
            Spacer(Modifier.width(12.dp))
            Text(rule.proxy.ifBlank { "DIRECT" }, color = if (rule.disabled) tokens.textSecondary else MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun RuleProviderCard(provider: ProxyRuleProviderUi, busy: Boolean, onRefresh: () -> Unit) {
    val tokens = LocalBichenTokens.current
    Surface(shape = RoundedCornerShape(20.dp), color = tokens.cardBackground) {
        Row(Modifier.fillMaxWidth().padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(provider.name, color = tokens.textPrimary, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    if (provider.ruleCount > 0) {
                        Spacer(Modifier.width(8.dp))
                        Text("${provider.ruleCount} 条规则", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    }
                }
                Text(listOf(provider.behavior, provider.type, provider.vehicleType).filter { it.isNotBlank() }.joinToString(" / "), color = tokens.textSecondary, style = MaterialTheme.typography.bodySmall)
                if (provider.updatedAt.isNotBlank()) Text("更新于 ${shortTime(provider.updatedAt)}", color = tokens.textSecondary, style = MaterialTheme.typography.labelSmall)
            }
            IconButton(onClick = onRefresh, enabled = !busy) {
                if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                else Icon(Icons.Rounded.Download, "更新规则集", tint = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
private fun ConnectionCard(connection: ProxyConnectionUi, onClose: () -> Unit) {
    val tokens = LocalBichenTokens.current
    Surface(shape = RoundedCornerShape(20.dp), color = tokens.cardBackground) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(connection.host, color = tokens.textPrimary, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (connection.network.isNotBlank()) Text(connection.network, color = tokens.textSecondary, style = MaterialTheme.typography.labelSmall)
                }
                if (connection.appName.isNotBlank()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (connection.appIcon != null) {
                            Image(connection.appIcon.asImageBitmap(), null, Modifier.size(24.dp).clip(RoundedCornerShape(6.dp)))
                            Spacer(Modifier.width(6.dp))
                        }
                        Text(connection.appName, color = tokens.textSecondary, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                    }
                }
                IconButton(onClick = onClose, modifier = Modifier.size(34.dp)) { Icon(Icons.Rounded.Close, "断开连接", Modifier.size(17.dp), tint = tokens.textSecondary) }
            }
            val route = listOf(connection.rulePayload.ifBlank { connection.rule }, connection.chain).filter { it.isNotBlank() }.joinToString(" / ")
            if (route.isNotBlank()) Text(route, color = tokens.textSecondary, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row {
                Text("↑ ${fmtBytes(connection.upload)}", color = tokens.success, style = MaterialTheme.typography.labelSmall)
                Spacer(Modifier.weight(1f))
                Text("↓ ${fmtBytes(connection.download)}", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun RateCard(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, value: String, up: Boolean, modifier: Modifier) {
    val tokens = LocalBichenTokens.current
    val color = if (up) tokens.success else MaterialTheme.colorScheme.primary
    Surface(modifier = modifier, shape = RoundedCornerShape(20.dp), color = tokens.cardBackground) {
        Row(Modifier.padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = RoundedCornerShape(12.dp), color = color.copy(alpha = .12f), modifier = Modifier.size(42.dp)) {
                Box(contentAlignment = Alignment.Center) { Icon(icon, null, tint = color) }
            }
            Spacer(Modifier.width(10.dp))
            Column {
                Text(title, color = tokens.textSecondary, style = MaterialTheme.typography.bodySmall)
                Text(value, color = color, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun TrafficTrendCard(samples: List<TrafficSample>) {
    val tokens = LocalBichenTokens.current
    val scheme = MaterialTheme.colorScheme
    Surface(shape = RoundedCornerShape(24.dp), color = tokens.cardBackground) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("近期趋势", color = tokens.textPrimary, style = MaterialTheme.typography.titleMedium)
                    Text("最近 ${samples.size.coerceAtMost(30) * 3} 秒", color = tokens.textSecondary, style = MaterialTheme.typography.bodySmall)
                }
                StatusChip("上行", tokens.success)
                Spacer(Modifier.width(6.dp))
                StatusChip("下行", scheme.primary)
            }
            Canvas(Modifier.fillMaxWidth().height(150.dp)) {
                if (samples.size < 2) return@Canvas
                val max = samples.maxOf { maxOf(it.up, it.down) }.coerceAtLeast(1L).toFloat()
                fun pathFor(up: Boolean): Path {
                    val path = Path()
                    samples.forEachIndexed { index, sample ->
                        val x = if (samples.size == 1) 0f else size.width * index / (samples.size - 1).toFloat()
                        val value = if (up) sample.up else sample.down
                        val y = size.height - (value / max) * size.height
                        if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    }
                    return path
                }
                drawPath(pathFor(false), scheme.primary, style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round))
                drawPath(pathFor(true), tokens.success, style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round))
            }
        }
    }
}

@Composable
private fun WorkspaceTools(state: ProxyComposeState, testing: Boolean, onSpeed: () -> Unit) {
    val context = LocalContext.current
    val tokens = LocalBichenTokens.current
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { WorkspaceTopBar("工具") }
        item { ToolRow(Icons.Rounded.Speed, if (testing) "正在全局测速" else "全局测速", "Mihomo 策略组批量测速", if (state.running) "开始" else "未运行", state.running && !testing, onSpeed) }
        item { ToolRow(Icons.Rounded.Storage, "内核管理", "Mihomo 可更新，其他核心按需下载", "管理") { context.startActivity(Intent(context, ProxyCoreActivity::class.java)) } }
        item { ToolRow(Icons.Rounded.CloudSync, "订阅与配置", "订阅、YAML、配置切换", "打开") { context.startActivity(Intent(context, ProxySubscriptionActivity::class.java)) } }
        item { ToolRow(Icons.Rounded.Tune, "基础代理配置", "TPROXY 与 Root 参数", "打开") { context.startActivity(Intent(context, RootTproxyActivity::class.java)) } }
        item { Text("${state.core} · ${state.mode}", color = tokens.textSecondary, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun WorkspaceSettings(state: ProxyComposeState, controller: ProxyComposeController, onChanged: () -> Unit) {
    val tokens = LocalBichenTokens.current
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { WorkspaceTopBar("设置") }
        item {
            Surface(shape = RoundedCornerShape(22.dp), color = tokens.cardBackground) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("内核", color = tokens.textPrimary, style = MaterialTheme.typography.titleMedium)
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        controller.cores().forEach { (id, label) ->
                            FilterChip(selected = state.core == label, onClick = { controller.setCore(id); onChanged() }, label = { Text(label) })
                        }
                    }
                }
            }
        }
        item {
            Surface(shape = RoundedCornerShape(22.dp), color = tokens.cardBackground) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("当前运行", color = tokens.textPrimary, style = MaterialTheme.typography.titleMedium)
                    Text("${state.core} · ${state.mode} · IPv6 ${state.ipv6}", color = tokens.textSecondary, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun WorkspaceTopBar(title: String) {
    val tokens = LocalBichenTokens.current
    Row(Modifier.fillMaxWidth().statusBarsPadding().heightIn(min = 64.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = tokens.textPrimary, fontSize = 26.sp, lineHeight = 34.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun WorkspaceDetailBar(title: String, subtitle: String, onBack: () -> Unit, onRefresh: () -> Unit) {
    val tokens = LocalBichenTokens.current
    Row(Modifier.fillMaxWidth().statusBarsPadding().heightIn(min = 68.dp), verticalAlignment = Alignment.CenterVertically) {
        SmallRound(Icons.AutoMirrored.Rounded.ArrowBack, "返回", onClick = onBack)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = tokens.textPrimary, fontSize = 26.sp, lineHeight = 32.sp, fontWeight = FontWeight.Bold)
            Text(subtitle, color = tokens.textSecondary, style = MaterialTheme.typography.bodySmall)
        }
        SmallRound(Icons.Rounded.Refresh, "刷新", onClick = onRefresh)
    }
}

@Composable
private fun SmallRound(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, enabled: Boolean = true, onClick: () -> Unit) {
    Surface(shape = CircleShape, color = LocalBichenTokens.current.cardBackground, shadowElevation = 1.dp, modifier = Modifier.size(44.dp)) {
        IconButton(onClick = onClick, enabled = enabled) { Icon(icon, description, Modifier.size(20.dp)) }
    }
}

@Composable
private fun GroupIcon(group: ProxyGroupUi, size: Int) {
    val bitmap = remember(group.iconPath) { group.iconPath.takeIf { it.isNotBlank() }?.let(BitmapFactory::decodeFile) }
    Surface(shape = RoundedCornerShape(10.dp), color = LocalBichenTokens.current.elevatedCardBackground, modifier = Modifier.size(size.dp)) {
        Box(contentAlignment = Alignment.Center) {
            if (bitmap != null) Image(bitmap.asImageBitmap(), null, Modifier.size((size - 6).dp), contentScale = ContentScale.Fit)
            else Icon(Icons.Rounded.Public, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size((size - 12).dp))
        }
    }
}

@Composable
private fun DelayBadge(delay: Long?) {
    val tokens = LocalBichenTokens.current
    val scheme = MaterialTheme.colorScheme
    val text = when {
        delay == -2L -> "…"
        delay == null -> "测速"
        delay < 0 -> "超时"
        else -> "$delay ms"
    }
    val color = when {
        delay == null -> tokens.textSecondary
        delay == -2L -> scheme.primary
        delay < 0 -> scheme.error
        delay <= 350 -> scheme.primary
        delay <= 550 -> tokens.success
        delay <= 800 -> tokens.warning
        else -> scheme.error
    }
    Surface(shape = CircleShape, color = color.copy(alpha = .13f)) {
        Text(text, Modifier.padding(horizontal = 7.dp, vertical = 3.dp), color = color, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
private fun StatusChip(text: String, color: Color) {
    Surface(shape = CircleShape, color = color.copy(alpha = .11f)) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(6.dp).background(color, CircleShape)); Spacer(Modifier.width(6.dp)); Text(text, color = LocalBichenTokens.current.textPrimary, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun StatCell(value: String, label: String, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(value, color = LocalBichenTokens.current.textPrimary, fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(label, color = LocalBichenTokens.current.textSecondary, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun ShortcutCard(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String, modifier: Modifier, onClick: () -> Unit) {
    val tokens = LocalBichenTokens.current
    Surface(onClick = onClick, modifier = modifier, shape = RoundedCornerShape(22.dp), color = tokens.cardBackground, shadowElevation = 1.dp) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(25.dp))
            Text(title, color = tokens.textPrimary, style = MaterialTheme.typography.titleSmall)
            Text(subtitle, color = tokens.textSecondary, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun ToolRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String, trailing: String, enabled: Boolean = true, onClick: () -> Unit) {
    val tokens = LocalBichenTokens.current
    Surface(onClick = onClick, enabled = enabled, shape = RoundedCornerShape(20.dp), color = tokens.cardBackground) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = RoundedCornerShape(12.dp), color = tokens.elevatedCardBackground, modifier = Modifier.size(42.dp)) { Box(contentAlignment = Alignment.Center) { Icon(icon, null, tint = MaterialTheme.colorScheme.primary) } }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) { Text(title, color = tokens.textPrimary, style = MaterialTheme.typography.titleSmall); Text(subtitle, color = tokens.textSecondary, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            Text(trailing, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun EmptyCompact(text: String) {
    Surface(shape = RoundedCornerShape(20.dp), color = LocalBichenTokens.current.cardBackground) {
        Text(text, Modifier.fillMaxWidth().padding(20.dp), color = LocalBichenTokens.current.textSecondary, style = MaterialTheme.typography.bodyMedium)
    }
}

private fun groupType(raw: String): String = when (raw.lowercase()) {
    "urltest", "url-test" -> "URLTest"
    "fallback" -> "Fallback"
    "loadbalance", "load-balance" -> "LoadBalance"
    "selector" -> "Selector"
    else -> raw.ifBlank { "Group" }
}

private fun groupPriority(name: String): Int {
    val order = listOf("节点选择", "手动选择", "故障转移", "香港节点", "台湾节点", "日本节点", "新加坡节点", "韩国节点", "美国节点", "AI", "YouTube", "Google", "TikTok", "Telegram", "GitHub", "Emby", "Netflix", "Spotify", "Windows", "Apple", "Game", "Download", "bilibili")
    val index = order.indexOfFirst { name.contains(it, true) }
    return if (index >= 0) index else 1000
}

private fun fmtRate(bytes: Long): String = fmtBytes(bytes) + "/s"
private fun fmtBytes(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.1f GB".format(bytes.toDouble() / (1L shl 30))
    bytes >= 1L shl 20 -> "%.1f MB".format(bytes.toDouble() / (1L shl 20))
    bytes >= 1L shl 10 -> "%.1f KB".format(bytes.toDouble() / (1L shl 10))
    else -> "$bytes B"
}

private fun shortTime(raw: String): String {
    if (raw.length < 16) return raw
    return raw.replace('T', ' ').substring(0, 16)
}
