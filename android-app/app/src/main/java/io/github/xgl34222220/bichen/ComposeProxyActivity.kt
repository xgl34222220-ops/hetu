package io.github.xgl34222220.bichen

import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
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

class ComposeProxyActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            BichenTheme {
                ProxyWorkspace(onBack = ::finish)
            }
        }
    }
}

private enum class ProxyPage { Home, Panel, Tools, Settings }

private enum class PanelTab(val title: String) {
    Overview("概览"),
    Nodes("节点"),
    Subscription("订阅"),
    Connections("连接"),
    Rules("规则"),
    RuleSets("规则集"),
}

private enum class GroupSort { Config, Delay }

@Composable
private fun ProxyWorkspace(onBack: () -> Unit) {
    val context = LocalContext.current
    val activity = context as ComponentActivity
    val controller = remember { ProxyComposeController(context) }
    val hazeState = rememberHazeState()
    val liquidBackdrop = rememberLayerBackdrop()
    val liquid = isRuntimeShaderSupported()

    var page by rememberSaveable { mutableStateOf(ProxyPage.Home) }
    var revision by remember { mutableIntStateOf(0) }
    var state by remember { mutableStateOf(ProxyComposeState()) }
    var notice by remember { mutableStateOf("") }
    var busyText by remember { mutableStateOf("") }
    var testing by remember { mutableStateOf(false) }
    val delays = remember { mutableStateMapOf<String, Long>() }

    suspend fun loadState() {
        try {
            state = controller.state()
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            notice = error.message ?: "状态读取失败"
        }
    }

    fun refresh() {
        activity.lifecycleScope.launch {
            val added = try {
                controller.ensureIcons()
            } catch (_: Exception) {
                0
            }
            notice = if (added > 0) "已补齐 $added 个缺失策略图标" else "状态已刷新"
            revision++
        }
    }

    fun runGlobalDelay() {
        if (!state.running || testing) {
            if (!state.running) notice = "代理未运行，无法测速"
            return
        }
        testing = true
        notice = "正在调用 Mihomo 批量测速…"
        activity.lifecycleScope.launch {
            try {
                val result = controller.globalDelay()
                delays.clear()
                delays.putAll(result)
                val good = result.values.filter { it > 0L }
                notice = if (good.isEmpty()) {
                    "测速完成：没有节点返回有效延迟"
                } else {
                    "测速完成 · ${good.size}/${result.size} 可用 · 平均 ${good.average().toInt()} ms"
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                notice = error.message ?: "全局测速失败"
            } finally {
                testing = false
            }
        }
    }

    fun toggleProxy() {
        if (busyText.isNotBlank()) return
        activity.lifecycleScope.launch {
            val wasRunning = state.running
            busyText = if (wasRunning) "正在停止…" else "正在启动…"
            try {
                val callback: (String) -> Unit = { step ->
                    activity.runOnUiThread { busyText = step }
                }
                if (wasRunning) controller.stop(callback) else controller.start(callback)
                notice = if (wasRunning) "代理已停止" else "代理已启动"
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                notice = error.message ?: "代理操作失败"
            } finally {
                busyText = ""
                revision++
            }
        }
    }

    fun restartProxy() {
        if (!state.running || busyText.isNotBlank()) return
        activity.lifecycleScope.launch {
            busyText = "正在重启…"
            try {
                controller.stop { step -> activity.runOnUiThread { busyText = step } }
                controller.start { step -> activity.runOnUiThread { busyText = step } }
                notice = "代理已重启"
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                notice = error.message ?: "重启失败"
            } finally {
                busyText = ""
                revision++
            }
        }
    }

    LaunchedEffect(revision) { loadState() }
    LaunchedEffect(Unit) {
        try {
            controller.ensureIcons()
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
        }
        loadState()
        while (true) {
            delay(3_000L)
            loadState()
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

    val dark = MaterialTheme.colorScheme.background.luminance() < .5f

    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .then(if (!liquid) Modifier.hazeSource(hazeState) else Modifier)
                .then(if (liquid) Modifier.layerBackdrop(liquidBackdrop) else Modifier),
        ) {
            ProxyBackdrop(dark)
            Box(Modifier.fillMaxSize().padding(bottom = 106.dp)) {
                when (page) {
                    ProxyPage.Home -> ProxyHome(
                        state = state,
                        delays = delays,
                        notice = notice,
                        busyText = busyText,
                        testing = testing,
                        onBack = onBack,
                        onRefresh = ::refresh,
                        onToggle = ::toggleProxy,
                        onRestart = ::restartProxy,
                        onPanel = { page = ProxyPage.Panel },
                        onSpeedTest = ::runGlobalDelay,
                    )

                    ProxyPage.Panel -> ProxyPanel(
                        state = state,
                        controller = controller,
                        delays = delays,
                        notice = notice,
                        testing = testing,
                        onRefresh = ::refresh,
                        onGlobalDelay = ::runGlobalDelay,
                        onStateChanged = { revision++ },
                    )

                    ProxyPage.Tools -> ProxyTools(
                        state = state,
                        controller = controller,
                        notice = notice,
                        testing = testing,
                        onGlobalDelay = ::runGlobalDelay,
                        onRefresh = ::refresh,
                    )

                    ProxyPage.Settings -> ProxySettings(
                        state = state,
                        controller = controller,
                        onStateChanged = { revision++ },
                    )
                }
            }
        }

        BichenGlassDock(
            items = dockItems,
            selected = page.ordinal,
            onSelect = { page = ProxyPage.entries[it] },
            hazeState = hazeState,
            backdrop = liquidBackdrop.takeIf { liquid },
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

@Composable
private fun ProxyBackdrop(dark: Boolean) {
    val tokens = LocalBichenTokens.current
    val scheme = MaterialTheme.colorScheme
    Box(
        Modifier
            .fillMaxSize()
            .background(tokens.pageBackground)
            .drawBehind {
                drawRect(
                    Brush.radialGradient(
                        colors = listOf(
                            scheme.primary.copy(alpha = if (dark) .09f else .10f),
                            Color.Transparent,
                        ),
                        center = Offset(size.width * .92f, size.height * .03f),
                        radius = size.width * .86f,
                    ),
                )
                drawRect(
                    Brush.radialGradient(
                        colors = listOf(
                            scheme.secondary.copy(alpha = if (dark) .05f else .07f),
                            Color.Transparent,
                        ),
                        center = Offset(size.width * .06f, size.height * .80f),
                        radius = size.width,
                    ),
                )
            },
    )
}

@Composable
private fun ProxyHome(
    state: ProxyComposeState,
    delays: Map<String, Long>,
    notice: String,
    busyText: String,
    testing: Boolean,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onToggle: () -> Unit,
    onRestart: () -> Unit,
    onPanel: () -> Unit,
    onSpeedTest: () -> Unit,
) {
    val tokens = LocalBichenTokens.current
    val scheme = MaterialTheme.colorScheme
    val good = delays.values.filter { it > 0L }
    val averageDelay = if (good.isEmpty()) "—" else "${good.average().toInt()} ms"

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item("header") {
            ProxyDetailTopBar(
                title = "代理",
                subtitle = "${state.core} · ${state.mode}",
                onBack = onBack,
                actions = {
                    ProxyHeaderAction(
                        icon = Icons.Rounded.Refresh,
                        description = "刷新",
                        onClick = onRefresh,
                    )
                },
            )
        }

        item("hero") {
            Surface(
                shape = RoundedCornerShape(28.dp),
                color = tokens.cardBackground,
                shadowElevation = 2.dp,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            Brush.linearGradient(
                                listOf(
                                    scheme.primaryContainer.copy(alpha = .46f),
                                    tokens.cardBackground,
                                ),
                            ),
                        )
                        .padding(22.dp),
                    verticalArrangement = Arrangement.spacedBy(17.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusPill(
                            text = if (state.running) "运行中" else "已停止",
                            color = if (state.running) tokens.success else tokens.warning,
                        )
                        Spacer(Modifier.weight(1f))
                        Surface(
                            shape = CircleShape,
                            color = scheme.primary.copy(alpha = .12f),
                        ) {
                            Box(Modifier.size(54.dp), contentAlignment = Alignment.Center) {
                                Icon(
                                    if (state.running) Icons.Rounded.Check else Icons.Rounded.Pause,
                                    contentDescription = null,
                                    tint = scheme.primary,
                                    modifier = Modifier.size(31.dp),
                                )
                            }
                        }
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(
                            state.config,
                            color = tokens.textPrimary,
                            fontSize = 23.sp,
                            lineHeight = 30.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            "${state.core} · ${state.mode} · ${if (state.panelReady) "控制接口在线" else "等待控制接口"}",
                            color = tokens.textSecondary,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        ProxyMetric(
                            value = if (state.memoryBytes > 0) formatBytes(state.memoryBytes) else "—",
                            label = "内存",
                            modifier = Modifier.weight(1f),
                        )
                        ProxyMetric(
                            value = averageDelay,
                            label = "全局延迟",
                            modifier = Modifier.weight(1f),
                        )
                        ProxyMetric(
                            value = state.connections.size.toString(),
                            label = "连接",
                            modifier = Modifier.weight(1f),
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        ProxyMetric(formatBytes(state.downloadTotal), "下载", Modifier.weight(1f))
                        ProxyMetric(formatBytes(state.uploadTotal), "上传", Modifier.weight(1f))
                        ProxyMetric(if (state.panelReady) "在线" else "等待", "面板", Modifier.weight(1f))
                    }

                    if (notice.isNotBlank() || busyText.isNotBlank()) {
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = tokens.elevatedCardBackground.copy(alpha = .76f),
                        ) {
                            Text(
                                busyText.ifBlank { notice },
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                                color = if (busyText.isNotBlank()) scheme.primary else tokens.textSecondary,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }

                    Button(
                        onClick = onToggle,
                        enabled = busyText.isBlank(),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp),
                        shape = RoundedCornerShape(18.dp),
                    ) {
                        if (busyText.isNotBlank()) {
                            CircularProgressIndicator(Modifier.size(19.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(
                                if (state.running) Icons.Rounded.Stop else Icons.Rounded.PlayArrow,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(
                            when {
                                busyText.isNotBlank() -> "处理中"
                                state.running -> "停止代理"
                                else -> "启动代理"
                            },
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(
                            onClick = onRefresh,
                            modifier = Modifier.weight(1f).heightIn(min = 46.dp),
                            shape = RoundedCornerShape(17.dp),
                        ) {
                            Icon(Icons.Rounded.Refresh, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("重载")
                        }
                        OutlinedButton(
                            onClick = onRestart,
                            enabled = state.running && busyText.isBlank(),
                            modifier = Modifier.weight(1f).heightIn(min = 46.dp),
                            shape = RoundedCornerShape(17.dp),
                        ) {
                            Icon(Icons.Rounded.RestartAlt, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("重启")
                        }
                    }
                }
            }
        }

        item("workspace-title") {
            ProxySectionHeading("代理工作台", "节点、连接、订阅与测速")
        }

        item("workspace-shortcuts") {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ProxyShortcut(
                    icon = Icons.Rounded.Public,
                    title = "策略面板",
                    subtitle = "策略组 · 节点 · 连接",
                    modifier = Modifier.weight(1f),
                    onClick = onPanel,
                )
                ProxyShortcut(
                    icon = Icons.Rounded.Speed,
                    title = if (testing) "测速中" else "全局测速",
                    subtitle = "Mihomo 原生批量测速",
                    modifier = Modifier.weight(1f),
                    enabled = state.running && !testing,
                    onClick = onSpeedTest,
                )
            }
        }
    }
}

@Composable
private fun ProxyPanel(
    state: ProxyComposeState,
    controller: ProxyComposeController,
    delays: MutableMap<String, Long>,
    notice: String,
    testing: Boolean,
    onRefresh: () -> Unit,
    onGlobalDelay: () -> Unit,
    onStateChanged: () -> Unit,
) {
    val context = LocalContext.current
    val activity = context as ComponentActivity
    val tokens = LocalBichenTokens.current

    var tab by rememberSaveable { mutableStateOf(PanelTab.Overview) }
    var query by rememberSaveable { mutableStateOf("") }
    var searchVisible by rememberSaveable { mutableStateOf(false) }
    var expandedGroup by rememberSaveable { mutableStateOf<String?>(null) }
    var sort by rememberSaveable { mutableStateOf(GroupSort.Config) }
    var hideUnavailable by rememberSaveable { mutableStateOf(false) }
    var showPanelSettings by rememberSaveable { mutableStateOf(false) }

    val orderedGroups = remember(state.groups, delays, query, sort, hideUnavailable) {
        var list = state.groups.filter { group ->
            query.isBlank() ||
                group.name.contains(query, ignoreCase = true) ||
                group.now.contains(query, ignoreCase = true) ||
                group.nodes.any { it.name.contains(query, ignoreCase = true) }
        }
        if (hideUnavailable) {
            list = list.filter { group ->
                group.nodes.any { node -> (delays[node.name] ?: 0L) > 0L }
            }
        }
        when (sort) {
            GroupSort.Config -> list.sortedWith(
                compareBy<ProxyGroupUi>({ videoGroupPriority(it.name) }, { it.name }),
            )
            GroupSort.Delay -> list.sortedBy { delaySortKey(delays[it.now]) }
        }
    }

    val allNodes = remember(state.groups, query) {
        state.groups
            .flatMap { it.nodes }
            .distinctBy { it.name }
            .filter { node ->
                query.isBlank() ||
                    node.name.contains(query, ignoreCase = true) ||
                    node.type.contains(query, ignoreCase = true)
            }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item("header") {
            ProxyTopBar(
                title = "代理面板",
                actions = {
                    ProxyHeaderAction(
                        icon = Icons.Rounded.Search,
                        description = "搜索",
                        onClick = { searchVisible = !searchVisible },
                    )
                    ProxyHeaderAction(
                        icon = Icons.Rounded.Sort,
                        description = "排序",
                        onClick = {
                            sort = if (sort == GroupSort.Config) GroupSort.Delay else GroupSort.Config
                        },
                    )
                    ProxyHeaderAction(
                        icon = Icons.Rounded.Tune,
                        description = "面板设置",
                        onClick = { showPanelSettings = true },
                    )
                },
            )
        }

        item("tabs") {
            PanelTabs(tab = tab, onSelect = { tab = it })
        }

        if (searchVisible) {
            item("search") {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text("搜索策略组、节点或协议") },
                    leadingIcon = { Icon(Icons.Rounded.Search, null) },
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { query = "" }) {
                                Icon(Icons.Rounded.Close, "清空")
                            }
                        }
                    },
                    shape = RoundedCornerShape(18.dp),
                )
            }
        }

        when (tab) {
            PanelTab.Overview -> {
                item("overview-summary") {
                    Surface(
                        shape = RoundedCornerShape(24.dp),
                        color = tokens.cardBackground,
                        shadowElevation = 1.dp,
                    ) {
                        Column(
                            Modifier.fillMaxWidth().padding(17.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Surface(
                                    shape = RoundedCornerShape(14.dp),
                                    color = tokens.elevatedCardBackground,
                                ) {
                                    Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                                        Icon(
                                            Icons.Rounded.Speed,
                                            null,
                                            tint = MaterialTheme.colorScheme.primary,
                                        )
                                    }
                                }
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    Text(
                                        if (testing) "正在全局测速" else "策略组",
                                        color = tokens.textPrimary,
                                        style = MaterialTheme.typography.titleMedium,
                                    )
                                    Text(
                                        notice.ifBlank { "${state.groups.size} 个策略组 · 点击卡片展开节点" },
                                        color = tokens.textSecondary,
                                        style = MaterialTheme.typography.bodySmall,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                TextButton(
                                    onClick = onGlobalDelay,
                                    enabled = state.running && !testing,
                                ) {
                                    Text(if (testing) "测速中" else "全部测速")
                                }
                            }
                            if (testing) LinearProgressIndicator(Modifier.fillMaxWidth())
                        }
                    }
                }

                if (orderedGroups.isEmpty()) {
                    item("empty-groups") {
                        EmptyState(
                            icon = Icons.Rounded.Public,
                            title = if (state.running) "没有可显示的策略组" else "代理尚未运行",
                            subtitle = if (state.running) "检查搜索、筛选或当前配置" else "启动代理后会显示真实策略组",
                        )
                    }
                } else {
                    orderedGroups.chunked(2).forEachIndexed { rowIndex, row ->
                        item("group-row-$rowIndex-${row.joinToString { it.name }}") {
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                row.forEach { group ->
                                    StrategyCard(
                                        group = group,
                                        delay = delays[group.now],
                                        measured = group.nodes.count { delays.containsKey(it.name) },
                                        expanded = expandedGroup == group.name,
                                        modifier = Modifier.weight(1f),
                                        onClick = {
                                            expandedGroup = if (expandedGroup == group.name) null else group.name
                                        },
                                    )
                                }
                                if (row.size == 1) Spacer(Modifier.weight(1f))
                            }
                        }

                        row.firstOrNull { it.name == expandedGroup }?.let { group ->
                            item("expanded-${group.name}") {
                                ExpandedGroupNodes(
                                    group = group,
                                    delays = delays,
                                    controller = controller,
                                    activity = activity,
                                    onStateChanged = onStateChanged,
                                )
                            }
                        }
                    }
                }
            }

            PanelTab.Nodes -> {
                item("nodes-title") {
                    ProxySectionHeading(
                        "全部节点",
                        "${allNodes.size} 个真实节点 · 点击切换，测速按钮只测当前节点",
                    )
                }
                if (allNodes.isEmpty()) {
                    item("empty-nodes") {
                        EmptyState(Icons.Rounded.Public, "暂无节点", "启动代理并加载订阅后再查看")
                    }
                } else {
                    allNodes.chunked(2).forEachIndexed { index, row ->
                        item("node-row-$index") {
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                row.forEach { node ->
                                    val selectedIn = state.groups.firstOrNull { it.now == node.name }
                                    NodeCard(
                                        node = node,
                                        delay = delays[node.name],
                                        selected = selectedIn != null,
                                        modifier = Modifier.weight(1f),
                                        onSelect = {
                                            val group = selectedIn ?: state.groups.firstOrNull { g ->
                                                g.nodes.any { it.name == node.name } &&
                                                    normalizedGroupType(g.type) == "Selector"
                                            }
                                            if (group != null) {
                                                activity.lifecycleScope.launch {
                                                    try {
                                                        controller.select(group.name, node.name)
                                                        onStateChanged()
                                                    } catch (_: Exception) {
                                                    }
                                                }
                                            }
                                        },
                                        onDelay = {
                                            activity.lifecycleScope.launch {
                                                delays[node.name] = -2L
                                                val value = try {
                                                    controller.delay(node.name)
                                                } catch (_: Exception) {
                                                    -1L
                                                }
                                                delays[node.name] = value
                                            }
                                        },
                                    )
                                }
                                if (row.size == 1) Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                }
            }

            PanelTab.Subscription -> {
                item("subscription") {
                    WorkspaceActionCard(
                        icon = Icons.Rounded.CloudSync,
                        title = "订阅与配置",
                        subtitle = "添加订阅、切换配置、编辑 YAML；私人订阅不会内置到 App",
                        trailing = "打开",
                    ) {
                        context.startActivity(Intent(context, ProxySubscriptionActivity::class.java))
                    }
                }
                item("subscription-tip") {
                    InfoCard(
                        title = "图标缓存规则",
                        body = "普通刷新只检查缓存，策略图标仅在本地缺失时下载；已经缓存的图标不会重复联网拉取。",
                    )
                }
            }

            PanelTab.Connections -> {
                item("connections-summary") {
                    Surface(
                        shape = RoundedCornerShape(24.dp),
                        color = tokens.cardBackground,
                        shadowElevation = 1.dp,
                    ) {
                        Column(
                            Modifier.fillMaxWidth().padding(17.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                StatusMetric("下载", formatBytes(state.downloadTotal), Modifier.weight(1f))
                                StatusMetric("上传", formatBytes(state.uploadTotal), Modifier.weight(1f))
                                StatusMetric("连接", state.connections.size.toString(), Modifier.weight(1f))
                            }
                            OutlinedButton(
                                onClick = {
                                    activity.lifecycleScope.launch {
                                        try {
                                            controller.closeAll()
                                            onStateChanged()
                                        } catch (_: Exception) {
                                        }
                                    }
                                },
                                enabled = state.connections.isNotEmpty(),
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(17.dp),
                            ) {
                                Icon(Icons.Rounded.DeleteSweep, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(7.dp))
                                Text("断开全部连接")
                            }
                        }
                    }
                }
                items(state.connections, key = { it.id }) { connection ->
                    ConnectionCard(connection)
                }
            }

            PanelTab.Rules -> {
                item("rules") {
                    WorkspaceActionCard(
                        icon = Icons.Rounded.Rule,
                        title = "规则",
                        subtitle = "规则来源于当前代理 YAML；在配置编辑器中查看和调整完整规则。",
                        trailing = "编辑 YAML",
                    ) {
                        context.startActivity(Intent(context, ProxySubscriptionActivity::class.java))
                    }
                }
            }

            PanelTab.RuleSets -> {
                item("rule-sets") {
                    WorkspaceActionCard(
                        icon = Icons.Rounded.Folder,
                        title = "规则集",
                        subtitle = "规则提供者、rule-providers 与更新地址由当前配置管理。",
                        trailing = "打开配置",
                    ) {
                        context.startActivity(Intent(context, ProxySubscriptionActivity::class.java))
                    }
                }
            }
        }
    }

    if (showPanelSettings) {
        PanelSettingsDialog(
            sort = sort,
            hideUnavailable = hideUnavailable,
            onSortChange = { sort = it },
            onHideUnavailableChange = { hideUnavailable = it },
            onDismiss = { showPanelSettings = false },
            onRefresh = {
                showPanelSettings = false
                onRefresh()
            },
        )
    }
}

@Composable
private fun ExpandedGroupNodes(
    group: ProxyGroupUi,
    delays: MutableMap<String, Long>,
    controller: ProxyComposeController,
    activity: ComponentActivity,
    onStateChanged: () -> Unit,
) {
    val tokens = LocalBichenTokens.current
    Surface(
        shape = RoundedCornerShape(26.dp),
        color = tokens.elevatedCardBackground.copy(alpha = .62f),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                MiniGroupIcon(group)
                Spacer(Modifier.width(9.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        group.name,
                        color = tokens.textPrimary,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        "${group.nodes.size} 个节点 · 当前 ${group.now}",
                        color = tokens.textSecondary,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            group.nodes.chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { node ->
                        NodeCard(
                            node = node,
                            delay = delays[node.name],
                            selected = group.now == node.name,
                            modifier = Modifier.weight(1f),
                            onSelect = {
                                activity.lifecycleScope.launch {
                                    try {
                                        controller.select(group.name, node.name)
                                        onStateChanged()
                                    } catch (_: Exception) {
                                    }
                                }
                            },
                            onDelay = {
                                activity.lifecycleScope.launch {
                                    delays[node.name] = -2L
                                    val value = try {
                                        controller.delay(node.name)
                                    } catch (_: Exception) {
                                        -1L
                                    }
                                    delays[node.name] = value
                                }
                            },
                        )
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun StrategyCard(
    group: ProxyGroupUi,
    delay: Long?,
    measured: Int,
    expanded: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val tokens = LocalBichenTokens.current
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(24.dp),
        color = if (expanded) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = .48f)
        } else {
            tokens.cardBackground
        },
        shadowElevation = 1.dp,
    ) {
        Column(
            Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                MiniGroupIcon(group)
                Spacer(Modifier.width(8.dp))
                Text(
                    group.name,
                    modifier = Modifier.weight(1f),
                    color = tokens.textPrimary,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Text(
                "${normalizedGroupType(group.type)}  $measured/${group.nodes.size}",
                color = tokens.textSecondary,
                style = MaterialTheme.typography.labelSmall,
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    group.now,
                    modifier = Modifier.weight(1f),
                    color = tokens.textSecondary,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                DelayPill(delay)
            }
        }
    }
}

@Composable
private fun NodeCard(
    node: ProxyNodeUi,
    delay: Long?,
    selected: Boolean,
    modifier: Modifier,
    onSelect: () -> Unit,
    onDelay: () -> Unit,
) {
    val tokens = LocalBichenTokens.current
    Surface(
        onClick = onSelect,
        modifier = modifier,
        shape = RoundedCornerShape(21.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = .62f)
        } else {
            tokens.cardBackground
        },
        shadowElevation = 1.dp,
    ) {
        Column(
            Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Row(verticalAlignment = Alignment.Top) {
                Text(
                    node.name,
                    modifier = Modifier.weight(1f),
                    color = tokens.textPrimary,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (selected) {
                    Icon(
                        Icons.Rounded.CheckCircle,
                        "已选择",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    buildString {
                        if (node.udp) append("UDP")
                        if (node.udp && node.type.isNotBlank()) append(" · ")
                        if (node.type.isNotBlank()) append(node.type)
                        if (isEmpty()) append("节点")
                    },
                    modifier = Modifier.weight(1f),
                    color = tokens.textSecondary,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                DelayPill(delay)
            }

            TextButton(
                onClick = onDelay,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                modifier = Modifier.heightIn(min = 32.dp),
            ) {
                Icon(Icons.Rounded.Speed, null, Modifier.size(15.dp))
                Spacer(Modifier.width(4.dp))
                Text("测速", fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun ConnectionCard(connection: ProxyConnectionUi) {
    val tokens = LocalBichenTokens.current
    Surface(
        shape = RoundedCornerShape(22.dp),
        color = tokens.cardBackground,
    ) {
        Column(
            Modifier.fillMaxWidth().padding(15.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                connection.host,
                color = tokens.textPrimary,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (connection.rule.isNotBlank()) {
                Text(
                    connection.rule,
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (connection.chain.isNotBlank()) {
                Text(
                    connection.chain,
                    color = tokens.textSecondary,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                "↑ ${formatBytes(connection.upload)}   ↓ ${formatBytes(connection.download)}",
                color = tokens.textSecondary,
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

@Composable
private fun ProxyTools(
    state: ProxyComposeState,
    controller: ProxyComposeController,
    notice: String,
    testing: Boolean,
    onGlobalDelay: () -> Unit,
    onRefresh: () -> Unit,
) {
    val context = LocalContext.current
    var textDialogTitle by remember { mutableStateOf("") }
    var textDialogBody by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item("header") {
            ProxyTopBar(
                title = "工具",
                actions = {
                    ProxyHeaderAction(
                        icon = Icons.Rounded.Refresh,
                        description = "刷新",
                        onClick = onRefresh,
                    )
                },
            )
        }

        item("status") {
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = LocalBichenTokens.current.cardBackground,
                shadowElevation = 1.dp,
            ) {
                Column(
                    Modifier.fillMaxWidth().padding(17.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusPill(
                            if (state.running) "代理运行中" else "代理已停止",
                            if (state.running) LocalBichenTokens.current.success else LocalBichenTokens.current.warning,
                        )
                        Spacer(Modifier.weight(1f))
                        Text(
                            state.core,
                            color = LocalBichenTokens.current.textSecondary,
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                    if (notice.isNotBlank()) {
                        Text(
                            notice,
                            color = LocalBichenTokens.current.textSecondary,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }

        item("maintain-title") {
            ProxySectionHeading("代理维护", "测速、内核、日志与诊断")
        }

        item("speed") {
            WorkspaceActionCard(
                icon = Icons.Rounded.Speed,
                title = if (testing) "正在全局测速" else "全局测速",
                subtitle = "优先调用 Mihomo 策略组批量测速，失败节点再单独补测",
                trailing = if (state.running) "开始" else "未运行",
                enabled = state.running && !testing,
                onClick = onGlobalDelay,
            )
        }

        item("core") {
            WorkspaceActionCard(
                icon = Icons.Rounded.Storage,
                title = "内核管理",
                subtitle = "Mihomo 内置但可更新；其他内核按需联网下载",
                trailing = "管理",
            ) {
                context.startActivity(Intent(context, ProxyCoreActivity::class.java))
            }
        }

        item("subscription") {
            WorkspaceActionCard(
                icon = Icons.Rounded.CloudSync,
                title = "订阅与配置",
                subtitle = "订阅、配置切换、YAML 编辑与导入",
                trailing = "打开",
            ) {
                context.startActivity(Intent(context, ProxySubscriptionActivity::class.java))
            }
        }

        item("diagnostics") {
            WorkspaceActionCard(
                icon = Icons.Rounded.Build,
                title = "运行与诊断",
                subtitle = "读取 Root TPROXY、核心和控制接口诊断信息",
                trailing = "查看",
            ) {
                scope.launch {
                    textDialogTitle = "运行与诊断"
                    textDialogBody = try {
                        controller.diagnostics()
                    } catch (error: Exception) {
                        error.message ?: "诊断失败"
                    }
                }
            }
        }

        item("startup") {
            WorkspaceActionCard(
                icon = Icons.Rounded.Description,
                title = "最终启动配置",
                subtitle = "查看运行前生成的最终 Mihomo 配置",
                trailing = "查看",
            ) {
                scope.launch {
                    textDialogTitle = "最终启动配置"
                    textDialogBody = try {
                        controller.startupConfig()
                    } catch (error: Exception) {
                        error.message ?: "读取失败"
                    }
                }
            }
        }

        item("logs") {
            WorkspaceActionCard(
                icon = Icons.Rounded.QueryStats,
                title = "运行日志",
                subtitle = "查看当前代理诊断与最近运行状态",
                trailing = "查看",
            ) {
                scope.launch {
                    textDialogTitle = "运行日志"
                    textDialogBody = try {
                        controller.diagnostics()
                    } catch (error: Exception) {
                        error.message ?: "读取失败"
                    }
                }
            }
        }

        item("icons") {
            WorkspaceActionCard(
                icon = Icons.Rounded.Image,
                title = "补齐策略图标",
                subtitle = "只下载本地缺失图标；已缓存图标不会重复拉取",
                trailing = "检查",
                onClick = onRefresh,
            )
        }
    }

    if (textDialogBody.isNotBlank()) {
        TextViewerDialog(
            title = textDialogTitle,
            body = textDialogBody,
            onDismiss = {
                textDialogTitle = ""
                textDialogBody = ""
            },
        )
    }
}

@Composable
private fun ProxySettings(
    state: ProxyComposeState,
    controller: ProxyComposeController,
    onStateChanged: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var coreDialog by rememberSaveable { mutableStateOf(false) }
    var modeDialog by rememberSaveable { mutableStateOf(false) }
    var ipv6Dialog by rememberSaveable { mutableStateOf(false) }
    var configDialog by rememberSaveable { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item("header") {
            ProxyTopBar(title = "设置")
        }

        item("runtime-title") {
            ProxySectionHeading("运行配置", "修改后重新启动代理生效")
        }

        item("core") {
            SettingRow(
                icon = Icons.Rounded.Storage,
                title = "核心选择",
                subtitle = state.core,
                onClick = { coreDialog = true },
            )
        }

        item("mode") {
            SettingRow(
                icon = Icons.Rounded.Public,
                title = "运行模式",
                subtitle = state.mode,
                onClick = { modeDialog = true },
            )
        }

        item("ipv6") {
            SettingRow(
                icon = Icons.Rounded.Language,
                title = "IPv6",
                subtitle = when (state.ipv6) {
                    "disable" -> "禁用系统 IPv6"
                    "bypass" -> "IPv6 不进核心"
                    else -> "启用 IPv6"
                },
                onClick = { ipv6Dialog = true },
            )
        }

        item("config") {
            SettingRow(
                icon = Icons.Rounded.Description,
                title = "当前配置",
                subtitle = state.config,
                onClick = { configDialog = true },
            )
        }

        item("overwrite") {
            val tokens = LocalBichenTokens.current
            Surface(
                shape = RoundedCornerShape(22.dp),
                color = tokens.cardBackground,
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SettingIcon(Icons.Rounded.Sync)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(
                            "自动覆写必要配置",
                            color = tokens.textPrimary,
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(
                            "启动时自动补齐外部控制接口与必要运行参数",
                            color = tokens.textSecondary,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Switch(
                        checked = state.autoOverwrite,
                        onCheckedChange = {
                            controller.setAutoOverwrite(it)
                            onStateChanged()
                        },
                    )
                }
            }
        }

        item("manage-title") {
            ProxySectionHeading("管理", "内核、订阅与配置文件")
        }

        item("core-manage") {
            WorkspaceActionCard(
                icon = Icons.Rounded.Storage,
                title = "内核管理",
                subtitle = "检查版本、在线更新、下载或恢复内置 Mihomo",
                trailing = "打开",
            ) {
                context.startActivity(Intent(context, ProxyCoreActivity::class.java))
            }
        }

        item("subscription-manage") {
            WorkspaceActionCard(
                icon = Icons.Rounded.CloudSync,
                title = "订阅与 YAML",
                subtitle = "添加订阅、导入配置、编辑完整 YAML",
                trailing = "打开",
            ) {
                context.startActivity(Intent(context, ProxySubscriptionActivity::class.java))
            }
        }
    }

    if (coreDialog) {
        ChoiceDialog(
            title = "核心选择",
            entries = controller.cores(),
            selectedLabel = state.core,
            onSelect = { id, _ ->
                controller.setCore(id)
                coreDialog = false
                onStateChanged()
            },
            onDismiss = { coreDialog = false },
        )
    }

    if (modeDialog) {
        ChoiceDialog(
            title = "运行模式",
            entries = controller.modes(),
            selectedLabel = state.mode,
            onSelect = { id, _ ->
                controller.setMode(id)
                modeDialog = false
                onStateChanged()
            },
            onDismiss = { modeDialog = false },
        )
    }

    if (ipv6Dialog) {
        ChoiceDialog(
            title = "IPv6",
            entries = controller.ipv6Modes(),
            selectedLabel = when (state.ipv6) {
                "disable" -> "禁用系统 IPv6"
                "bypass" -> "IPv6 不进核心"
                else -> "启用 IPv6"
            },
            onSelect = { id, _ ->
                controller.setIpv6(id)
                ipv6Dialog = false
                onStateChanged()
            },
            onDismiss = { ipv6Dialog = false },
        )
    }

    if (configDialog) {
        val entries = controller.configs().map { it to it }
        ChoiceDialog(
            title = "当前配置",
            entries = entries,
            selectedLabel = state.config,
            onSelect = { _, label ->
                scope.launch {
                    try {
                        controller.selectConfig(label)
                    } finally {
                        configDialog = false
                        onStateChanged()
                    }
                }
            },
            onDismiss = { configDialog = false },
        )
    }
}

@Composable
private fun ProxyTopBar(
    title: String,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val tokens = LocalBichenTokens.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .heightIn(min = 64.dp)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            title,
            modifier = Modifier.weight(1f),
            color = tokens.textPrimary,
            fontSize = 26.sp,
            lineHeight = 34.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            content = actions,
        )
    }
}

@Composable
private fun ProxyDetailTopBar(
    title: String,
    subtitle: String,
    onBack: () -> Unit,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val tokens = LocalBichenTokens.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .heightIn(min = 70.dp)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ProxyHeaderAction(
            icon = Icons.AutoMirrored.Rounded.ArrowBack,
            description = "返回",
            onClick = onBack,
            contentColor = tokens.textPrimary,
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(
                title,
                color = tokens.textPrimary,
                fontSize = 26.sp,
                lineHeight = 32.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
            )
            Text(
                subtitle,
                color = tokens.textSecondary,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            content = actions,
        )
    }
}

@Composable
private fun ProxyHeaderAction(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    contentColor: Color = MaterialTheme.colorScheme.primary,
) {
    val tokens = LocalBichenTokens.current
    Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
        Surface(
            modifier = Modifier.size(44.dp),
            shape = CircleShape,
            color = tokens.cardBackground,
            contentColor = contentColor,
            shadowElevation = 1.dp,
        ) {
            IconButton(onClick = onClick, modifier = Modifier.fillMaxSize()) {
                Icon(icon, description, modifier = Modifier.size(21.dp))
            }
        }
    }
}

@Composable
private fun ProxySectionHeading(title: String, subtitle: String? = null) {
    val tokens = LocalBichenTokens.current
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 2.dp, vertical = 3.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text(
            title,
            color = tokens.textPrimary,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        subtitle?.takeIf { it.isNotBlank() }?.let {
            Text(
                it,
                color = tokens.textSecondary,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun ProxyShortcut(
    icon: ImageVector,
    title: String,
    subtitle: String,
    modifier: Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val tokens = LocalBichenTokens.current
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        shape = RoundedCornerShape(24.dp),
        color = tokens.cardBackground,
        shadowElevation = 1.dp,
    ) {
        Column(
            Modifier.padding(17.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Surface(
                shape = RoundedCornerShape(15.dp),
                color = tokens.elevatedCardBackground,
            ) {
                Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                    Icon(
                        icon,
                        null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(23.dp),
                    )
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, color = tokens.textPrimary, style = MaterialTheme.typography.titleMedium)
                Text(
                    subtitle,
                    color = tokens.textSecondary,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun WorkspaceActionCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    trailing: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val tokens = LocalBichenTokens.current
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(23.dp),
        color = tokens.cardBackground,
        shadowElevation = 1.dp,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(15.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = tokens.elevatedCardBackground,
            ) {
                Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                    Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, color = tokens.textPrimary, style = MaterialTheme.typography.titleSmall)
                Text(
                    subtitle,
                    color = tokens.textSecondary,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                trailing,
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelMedium,
            )
            Spacer(Modifier.width(3.dp))
            Icon(
                Icons.Rounded.ChevronRight,
                null,
                tint = tokens.textSecondary,
                modifier = Modifier.size(19.dp),
            )
        }
    }
}

@Composable
private fun SettingRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    val tokens = LocalBichenTokens.current
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(22.dp),
        color = tokens.cardBackground,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SettingIcon(icon)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, color = tokens.textPrimary, style = MaterialTheme.typography.titleSmall)
                Text(
                    subtitle,
                    color = tokens.textSecondary,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(
                Icons.Rounded.ChevronRight,
                null,
                tint = tokens.textSecondary,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun SettingIcon(icon: ImageVector) {
    val tokens = LocalBichenTokens.current
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = tokens.elevatedCardBackground,
    ) {
        Box(Modifier.size(42.dp), contentAlignment = Alignment.Center) {
            Icon(
                icon,
                null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(21.dp),
            )
        }
    }
}

@Composable
private fun PanelTabs(tab: PanelTab, onSelect: (PanelTab) -> Unit) {
    val tokens = LocalBichenTokens.current
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PanelTab.entries.forEach { item ->
            Surface(
                onClick = { onSelect(item) },
                shape = CircleShape,
                color = if (tab == item) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    tokens.cardBackground
                },
                shadowElevation = if (tab == item) 1.dp else 0.dp,
            ) {
                Text(
                    item.title,
                    modifier = Modifier.padding(horizontal = 15.dp, vertical = 9.dp),
                    color = if (tab == item) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        tokens.textSecondary
                    },
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (tab == item) FontWeight.Bold else FontWeight.Medium,
                )
            }
        }
    }
}

@Composable
private fun StatusPill(text: String, color: Color) {
    val tokens = LocalBichenTokens.current
    Surface(
        shape = CircleShape,
        color = tokens.cardBackground.copy(alpha = .78f),
    ) {
        Row(
            Modifier.padding(horizontal = 11.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(7.dp).background(color, CircleShape))
            Spacer(Modifier.width(7.dp))
            Text(
                text,
                color = tokens.textPrimary,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun ProxyMetric(value: String, label: String, modifier: Modifier) {
    val tokens = LocalBichenTokens.current
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            value,
            color = tokens.textPrimary,
            fontSize = 18.sp,
            lineHeight = 23.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            label,
            color = tokens.textSecondary,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun StatusMetric(label: String, value: String, modifier: Modifier) {
    val tokens = LocalBichenTokens.current
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = tokens.elevatedCardBackground,
    ) {
        Column(
            Modifier.padding(horizontal = 11.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                value,
                color = tokens.textPrimary,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(label, color = tokens.textSecondary, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun MiniGroupIcon(group: ProxyGroupUi) {
    val bitmap = remember(group.iconPath) {
        if (group.iconPath.isBlank()) null else BitmapFactory.decodeFile(group.iconPath)
    }
    val tokens = LocalBichenTokens.current
    Surface(
        shape = RoundedCornerShape(11.dp),
        color = tokens.elevatedCardBackground,
        modifier = Modifier.size(36.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.size(27.dp).clip(RoundedCornerShape(6.dp)),
                    contentScale = ContentScale.Fit,
                )
            } else {
                Icon(
                    Icons.Rounded.Public,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(19.dp),
                )
            }
        }
    }
}

@Composable
private fun DelayPill(delay: Long?) {
    val tokens = LocalBichenTokens.current
    val color = when {
        delay == null -> tokens.textSecondary
        delay == -2L -> MaterialTheme.colorScheme.primary
        delay < 0L -> MaterialTheme.colorScheme.error
        delay <= 150L -> tokens.success
        delay <= 300L -> MaterialTheme.colorScheme.primary
        else -> tokens.warning
    }
    Surface(
        shape = CircleShape,
        color = color.copy(alpha = .12f),
    ) {
        Text(
            delayText(delay),
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
            color = color,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun EmptyState(icon: ImageVector, title: String, subtitle: String) {
    val tokens = LocalBichenTokens.current
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = tokens.cardBackground,
    ) {
        Column(
            Modifier.fillMaxWidth().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                icon,
                null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(34.dp),
            )
            Text(title, color = tokens.textPrimary, style = MaterialTheme.typography.titleMedium)
            Text(
                subtitle,
                color = tokens.textSecondary,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun InfoCard(title: String, body: String) {
    val tokens = LocalBichenTokens.current
    Surface(
        shape = RoundedCornerShape(22.dp),
        color = tokens.elevatedCardBackground.copy(alpha = .68f),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Text(title, color = tokens.textPrimary, style = MaterialTheme.typography.titleSmall)
            Text(body, color = tokens.textSecondary, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun PanelSettingsDialog(
    sort: GroupSort,
    hideUnavailable: Boolean,
    onSortChange: (GroupSort) -> Unit,
    onHideUnavailableChange: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    onRefresh: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("面板设置") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("排序方式", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AssistChip(
                        onClick = { onSortChange(GroupSort.Config) },
                        label = { Text("配置顺序") },
                        leadingIcon = {
                            if (sort == GroupSort.Config) Icon(Icons.Rounded.Check, null, Modifier.size(16.dp))
                        },
                    )
                    AssistChip(
                        onClick = { onSortChange(GroupSort.Delay) },
                        label = { Text("延迟排序") },
                        leadingIcon = {
                            if (sort == GroupSort.Delay) Icon(Icons.Rounded.Check, null, Modifier.size(16.dp))
                        },
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("隐藏不可用节点", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "仅在已有测速结果时过滤不可用节点",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Switch(
                        checked = hideUnavailable,
                        onCheckedChange = onHideUnavailableChange,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onRefresh) { Text("刷新") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("完成") }
        },
    )
}

@Composable
private fun ChoiceDialog(
    title: String,
    entries: List<Pair<String, String>>,
    selectedLabel: String,
    onSelect: (String, String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                entries.forEach { (id, label) ->
                    Surface(
                        onClick = { onSelect(id, label) },
                        shape = RoundedCornerShape(15.dp),
                        color = if (label == selectedLabel) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerLow
                        },
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 13.dp, vertical = 11.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(label, Modifier.weight(1f))
                            if (label == selectedLabel) {
                                Icon(
                                    Icons.Rounded.CheckCircle,
                                    null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(19.dp),
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

@Composable
private fun TextViewerDialog(title: String, body: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .horizontalScroll(rememberScrollState()),
            ) {
                Text(
                    body,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
    )
}

private fun delayText(delay: Long?): String = when {
    delay == null -> "—"
    delay == -2L -> "…"
    delay < 0L -> "超时"
    else -> "$delay ms"
}

private fun delaySortKey(delay: Long?): Long = when {
    delay == null -> Long.MAX_VALUE - 1
    delay < 0L -> Long.MAX_VALUE
    else -> delay
}

private fun normalizedGroupType(type: String): String = when (type.lowercase()) {
    "urltest", "url-test" -> "URLTest"
    "selector" -> "Selector"
    "fallback" -> "Fallback"
    "loadbalance", "load-balance" -> "LoadBalance"
    else -> type.ifBlank { "Group" }
}

private fun videoGroupPriority(name: String): Int {
    val priority = listOf(
        "节点选择", "手动选择", "故障转移", "香港节点", "台湾节点", "日本节点", "新加坡节点",
        "韩国节点", "美国节点", "AI 稳定", "AI 平台", "YouTube", "Google", "TikTok",
        "Telegram", "GitHub", "Emby", "Netflix", "Spotify", "Windows", "Apple", "Game",
        "Download", "bilibili",
    )
    val exact = priority.indexOf(name)
    if (exact >= 0) return exact
    val contains = priority.indexOfFirst { key -> name.contains(key, ignoreCase = true) }
    return if (contains >= 0) contains else 10_000
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024L) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var index = -1
    while (value >= 1024.0 && index < units.lastIndex) {
        value /= 1024.0
        index++
    }
    return if (value >= 100.0) {
        "${value.toInt()} ${units[index]}"
    } else {
        "%.1f %s".format(value, units[index])
    }
}
