package io.github.xgl34222220.bichen

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.graphics.luminance
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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop

class ComposeProxyActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { BichenTheme { ProxyApp(onBack = ::finish) } }
    }
}

private enum class ProxyPage { Home, Panel, Tools, Settings }
private enum class PanelTab(val label: String) {
    Overview("策略"), Nodes("节点"), Connections("连接"), Subscription("订阅"), Rules("规则"), RuleSets("规则集")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProxyApp(onBack: () -> Unit) {
    val context = LocalContext.current
    val controller = remember { ProxyComposeController(context) }
    val scope = rememberCoroutineScope()
    val tokens = LocalBichenTokens.current
    val backdrop = rememberLayerBackdrop()
    val liquid = isRuntimeShaderSupported()
    val haze = rememberHazeState()

    var page by rememberSaveable { mutableStateOf(ProxyPage.Home) }
    var revision by remember { mutableIntStateOf(0) }
    var state by remember { mutableStateOf(ProxyComposeState()) }
    var busy by remember { mutableStateOf("") }
    var notice by remember { mutableStateOf("") }
    var testingAll by remember { mutableStateOf(false) }
    val delays = remember { mutableStateMapOf<String, Long>() }

    suspend fun reloadState() {
        try {
            state = controller.state()
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (e: Exception) {
            notice = e.message ?: "状态读取失败"
        }
    }

    fun startStop() {
        if (busy.isNotBlank()) return
        scope.launch {
            val running = state.running
            busy = if (running) "正在停止…" else "正在启动…"
            try {
                val callback: (String) -> Unit = { busy = it }
                if (running) controller.stop(callback) else controller.start(callback)
                notice = if (running) "代理已停止" else "代理已启动"
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (e: Exception) {
                notice = e.message ?: "操作失败"
            } finally {
                busy = ""
                revision++
            }
        }
    }

    fun restart() {
        if (!state.running || busy.isNotBlank()) return
        scope.launch {
            busy = "正在重启…"
            try {
                controller.stop { busy = it }
                controller.start { busy = it }
                notice = "代理已重启"
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (e: Exception) {
                notice = e.message ?: "重启失败"
            } finally {
                busy = ""
                revision++
            }
        }
    }

    fun globalDelay() {
        if (!state.running || testingAll) return
        testingAll = true
        notice = "正在测试全部节点…"
        scope.launch {
            try {
                val result = controller.globalDelay()
                delays.clear()
                delays.putAll(result)
                val valid = result.values.filter { it > 0 }
                notice = if (valid.isEmpty()) "测速完成，没有节点返回有效延迟"
                else "测速完成 · ${valid.size}/${result.size} 可用 · 平均 ${valid.average().toInt()} ms"
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (e: Exception) {
                notice = e.message ?: "测速失败"
            } finally {
                testingAll = false
            }
        }
    }

    LaunchedEffect(revision) { reloadState() }
    LaunchedEffect(Unit) {
        reloadState()
        while (true) {
            delay(2_500)
            reloadState()
        }
    }

    val dockItems = remember {
        listOf(
            DockItem("首页", Icons.Rounded.Home),
            DockItem("面板", Icons.Rounded.Public),
            DockItem("工具", Icons.Rounded.GridView),
            DockItem("设置", Icons.Rounded.Settings),
        )
    }
    val dark = MaterialTheme.colorScheme.background.luminance() < .5f

    Box(Modifier.fillMaxSize().background(tokens.pageBackground)) {
        Box(
            Modifier
                .fillMaxSize()
                .background(tokens.pageBackground)
                .layerBackdrop(backdrop),
        ) {
            ProxyBackground(dark)
            Box(Modifier.fillMaxSize().padding(bottom = 86.dp)) {
                when (page) {
                    ProxyPage.Home -> HomePage(
                        state = state,
                        busy = busy,
                        notice = notice,
                        delays = delays,
                        testingAll = testingAll,
                        backdrop = backdrop.takeIf { liquid },
                        onBack = onBack,
                        onOpenPanel = { page = ProxyPage.Panel },
                        onOpenWebUi = { context.startActivity(Intent(context, ProxyWebUiActivity::class.java)) },
                        onStartStop = ::startStop,
                        onRestart = ::restart,
                        onGlobalDelay = ::globalDelay,
                    )
                    ProxyPage.Panel -> PanelPage(
                        state = state,
                        controller = controller,
                        delays = delays,
                        testingAll = testingAll,
                        backdrop = backdrop.takeIf { liquid },
                        onGlobalDelay = ::globalDelay,
                        onOpenWebUi = { context.startActivity(Intent(context, ProxyWebUiActivity::class.java)) },
                        onStateChanged = { revision++ },
                    )
                    ProxyPage.Tools -> ToolsPage(
                        state = state,
                        controller = controller,
                        notice = notice,
                        testingAll = testingAll,
                        backdrop = backdrop.takeIf { liquid },
                        onGlobalDelay = ::globalDelay,
                    )
                    ProxyPage.Settings -> SettingsPage(
                        state = state,
                        controller = controller,
                        backdrop = backdrop.takeIf { liquid },
                        onStateChanged = { revision++ },
                    )
                }
            }
        }

        BichenGlassDock(
            items = dockItems,
            selected = page.ordinal,
            onSelect = { page = ProxyPage.entries[it] },
            hazeState = haze,
            backdrop = backdrop.takeIf { liquid },
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

@Composable
private fun ProxyBackground(dark: Boolean) {
    val tokens = LocalBichenTokens.current
    val primary = MaterialTheme.colorScheme.primary
    Box(
        Modifier.fillMaxSize().background(tokens.pageBackground).drawBehind {
            drawRect(
                Brush.radialGradient(
                    listOf(primary.copy(alpha = if (dark) .11f else .08f), Color.Transparent),
                    center = Offset(size.width * .86f, size.height * .05f),
                    radius = size.width * .95f,
                ),
            )
            drawRect(
                Brush.radialGradient(
                    listOf(primary.copy(alpha = if (dark) .05f else .035f), Color.Transparent),
                    center = Offset(size.width * .08f, size.height * .72f),
                    radius = size.width * 1.15f,
                ),
            )
        },
    )
}

@Composable
private fun HomePage(
    state: ProxyComposeState,
    busy: String,
    notice: String,
    delays: Map<String, Long>,
    testingAll: Boolean,
    backdrop: LayerBackdrop?,
    onBack: () -> Unit,
    onOpenPanel: () -> Unit,
    onOpenWebUi: () -> Unit,
    onStartStop: () -> Unit,
    onRestart: () -> Unit,
    onGlobalDelay: () -> Unit,
) {
    val tokens = LocalBichenTokens.current
    val scheme = MaterialTheme.colorScheme
    val good = delays.values.filter { it > 0 }
    val avg = if (good.isEmpty()) "—" else "${good.average().toInt()} ms"

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            CompactTopBar("代理", "${state.core} · ${state.mode}", onBack) {
                SmallAction(Icons.Rounded.Language, "WebUI", onOpenWebUi)
            }
        }
        item {
            GlassBlock(backdrop = backdrop, radius = 22, tintAlpha = .80f) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.size(10.dp).background(if (state.running) tokens.success else tokens.warning, CircleShape),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (state.running) "运行中" else "已停止",
                            color = tokens.textPrimary,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.weight(1f))
                        Text(
                            if (state.panelReady) "控制接口在线" else "控制接口未就绪",
                            color = if (state.panelReady) tokens.success else tokens.textSecondary,
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(
                            state.config,
                            color = tokens.textPrimary,
                            fontSize = 20.sp,
                            lineHeight = 25.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            "${state.groups.size} 个策略组 · ${state.connections.size} 个连接",
                            color = tokens.textSecondary,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Metric(avg, "平均延迟", Modifier.weight(1f))
                        Metric(compactBytes(state.memoryBytes), "内存", Modifier.weight(1f))
                        Metric(state.connections.size.toString(), "连接", Modifier.weight(1f))
                    }
                    if (busy.isNotBlank() || notice.isNotBlank()) {
                        Text(
                            busy.ifBlank { notice },
                            color = if (busy.isNotBlank()) scheme.primary else tokens.textSecondary,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = onStartStop,
                            enabled = busy.isBlank(),
                            modifier = Modifier.weight(1f).height(44.dp),
                            shape = RoundedCornerShape(13.dp),
                        ) {
                            if (busy.isNotBlank()) CircularProgressIndicator(Modifier.size(17.dp), strokeWidth = 2.dp)
                            else Icon(if (state.running) Icons.Rounded.Stop else Icons.Rounded.PlayArrow, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(if (state.running) "停止" else "启动")
                        }
                        OutlinedButton(
                            onClick = onRestart,
                            enabled = state.running && busy.isBlank(),
                            modifier = Modifier.weight(1f).height(44.dp),
                            shape = RoundedCornerShape(13.dp),
                        ) {
                            Icon(Icons.Rounded.RestartAlt, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("重启")
                        }
                    }
                }
            }
        }
        item {
            Text("快捷操作", color = tokens.textSecondary, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(start = 2.dp))
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                QuickTile(Icons.Rounded.Public, "策略面板", "节点与策略", Modifier.weight(1f), onOpenPanel, backdrop)
                QuickTile(Icons.Rounded.Language, "WebUI", "本机 Zashboard", Modifier.weight(1f), onOpenWebUi, backdrop)
                QuickTile(
                    Icons.Rounded.Speed,
                    if (testingAll) "测速中" else "全部测速",
                    if (testingAll) "正在检测" else "所有真实节点",
                    Modifier.weight(1f),
                    onGlobalDelay,
                    backdrop,
                    enabled = state.running && !testingAll,
                )
            }
        }
        item {
            GlassBlock(backdrop = backdrop, radius = 18, tintAlpha = .72f) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp)) {
                    Metric(compactBytes(state.downloadTotal), "下载", Modifier.weight(1f))
                    Metric(compactBytes(state.uploadTotal), "上传", Modifier.weight(1f))
                    Metric(if (state.panelReady) "在线" else "等待", "API", Modifier.weight(1f))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PanelPage(
    state: ProxyComposeState,
    controller: ProxyComposeController,
    delays: MutableMap<String, Long>,
    testingAll: Boolean,
    backdrop: LayerBackdrop?,
    onGlobalDelay: () -> Unit,
    onOpenWebUi: () -> Unit,
    onStateChanged: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val tokens = LocalBichenTokens.current
    val scheme = MaterialTheme.colorScheme

    var tab by rememberSaveable { mutableStateOf(PanelTab.Overview) }
    var query by rememberSaveable { mutableStateOf("") }
    var search by rememberSaveable { mutableStateOf(false) }
    var selectedGroup by remember { mutableStateOf<ProxyGroupUi?>(null) }
    var groupTesting by remember { mutableStateOf<String?>(null) }
    var rules by remember { mutableStateOf<List<ProxyRuleUi>?>(null) }
    var providers by remember { mutableStateOf<List<ProxyRuleProviderUi>?>(null) }

    val groups = remember(state.groups, query) {
        state.groups.filter { group ->
            query.isBlank() || group.name.contains(query, true) || group.now.contains(query, true) || group.nodes.any { it.name.contains(query, true) }
        }
    }
    val nodes = remember(state.groups, query) {
        state.groups.flatMap { it.nodes }.distinctBy { it.name }.filter { query.isBlank() || it.name.contains(query, true) || it.type.contains(query, true) }
    }

    LaunchedEffect(tab, state.running) {
        if (tab == PanelTab.Rules && state.running) rules = runCatching { controller.rules() }.getOrDefault(emptyList())
        if (tab == PanelTab.RuleSets && state.running) providers = runCatching { controller.ruleProviders() }.getOrDefault(emptyList())
    }

    fun testGroup(group: ProxyGroupUi) {
        if (groupTesting != null) return
        groupTesting = group.name
        scope.launch {
            try {
                for (chunk in group.nodes.chunked(4)) {
                    val result = coroutineScope {
                        chunk.map { node ->
                            async {
                                val value = try { controller.delay(node.name) } catch (_: Exception) { -1L }
                                node.name to value
                            }
                        }.awaitAll()
                    }
                    result.forEach { (name, value) -> delays[name] = value }
                }
            } finally {
                groupTesting = null
            }
        }
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            CompactTopBar("代理面板", "${state.groups.size} 个策略组", null) {
                SmallAction(Icons.Rounded.Language, "WebUI", onOpenWebUi)
                SmallAction(Icons.Rounded.Search, "搜索") { search = !search }
            }
        }
        item { PanelTabs(tab, onSelect = { tab = it }) }
        if (search) {
            item {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text("搜索策略组或节点") },
                    leadingIcon = { Icon(Icons.Rounded.Search, null) },
                    trailingIcon = { if (query.isNotBlank()) IconButton(onClick = { query = "" }) { Icon(Icons.Rounded.Close, "清空") } },
                    shape = RoundedCornerShape(14.dp),
                )
            }
        }

        when (tab) {
            PanelTab.Overview -> {
                item {
                    GlassBlock(backdrop = backdrop, radius = 17, tintAlpha = .70f) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 13.dp, vertical = 11.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("策略组", color = tokens.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                                Text("点击卡片从底部选择节点", color = tokens.textSecondary, style = MaterialTheme.typography.labelSmall)
                            }
                            TextButton(onClick = onGlobalDelay, enabled = state.running && !testingAll) {
                                if (testingAll) {
                                    CircularProgressIndicator(Modifier.size(15.dp), strokeWidth = 2.dp)
                                    Spacer(Modifier.width(6.dp))
                                } else Icon(Icons.Rounded.Speed, null, Modifier.size(17.dp))
                                Spacer(Modifier.width(5.dp))
                                Text(if (testingAll) "测速中" else "全部测速")
                            }
                        }
                    }
                }
                if (groups.isEmpty()) {
                    item { EmptyState(if (state.running) "没有匹配的策略组" else "代理未运行", if (state.running) "清空搜索条件再试" else "启动代理后显示策略组") }
                } else {
                    groups.chunked(2).forEachIndexed { index, row ->
                        item("group-$index") {
                            Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                                row.forEach { group ->
                                    StrategyCard(
                                        group = group,
                                        delay = delays[group.now] ?: group.nodes.firstOrNull { it.name == group.now }?.lastDelay,
                                        measured = group.nodes.count { (delays[it.name] ?: it.lastDelay ?: -1L) > 0 },
                                        modifier = Modifier.weight(1f),
                                        backdrop = backdrop,
                                    ) { selectedGroup = group }
                                }
                                if (row.size == 1) Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
            PanelTab.Nodes -> {
                item { SectionLabel("全部节点", "${nodes.size} 个真实节点") }
                nodes.chunked(2).forEachIndexed { index, row ->
                    item("node-$index") {
                        Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                            row.forEach { node ->
                                NodeGridCard(node, delays[node.name] ?: node.lastDelay, Modifier.weight(1f), backdrop) {
                                    scope.launch {
                                        delays[node.name] = -2
                                        delays[node.name] = try { controller.delay(node.name) } catch (_: Exception) { -1 }
                                    }
                                }
                            }
                            if (row.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
            PanelTab.Connections -> {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SectionLabel("活动连接", "${state.connections.size} 个连接", Modifier.weight(1f))
                        TextButton(onClick = { scope.launch { controller.closeAll(); onStateChanged() } }, enabled = state.connections.isNotEmpty()) { Text("全部断开") }
                    }
                }
                items(state.connections, key = { it.id }) { connection ->
                    ConnectionRow(connection, backdrop) {
                        scope.launch { controller.closeConnection(connection.id); onStateChanged() }
                    }
                }
            }
            PanelTab.Subscription -> {
                item {
                    ActionRow(Icons.Rounded.CloudSync, "订阅与配置", "添加、更新、切换订阅与 YAML", "打开", backdrop) {
                        context.startActivity(Intent(context, ProxySubscriptionActivity::class.java))
                    }
                }
            }
            PanelTab.Rules -> {
                item { SectionLabel("当前规则", rules?.let { "${it.size} 条" } ?: "正在读取…") }
                when (val list = rules) {
                    null -> item { CenterLoading() }
                    else -> items(list.take(600), key = { it.index }) { rule -> RuleRow(rule, backdrop) }
                }
            }
            PanelTab.RuleSets -> {
                item { SectionLabel("规则集", providers?.let { "${it.size} 个" } ?: "正在读取…") }
                when (val list = providers) {
                    null -> item { CenterLoading() }
                    else -> items(list, key = { it.name }) { provider ->
                        ProviderRow(provider, backdrop) {
                            scope.launch {
                                runCatching { controller.updateRuleProvider(provider.name) }
                                providers = runCatching { controller.ruleProviders() }.getOrDefault(emptyList())
                            }
                        }
                    }
                }
            }
        }
    }

    selectedGroup?.let { group ->
        ModalBottomSheet(
            onDismissRequest = { selectedGroup = null },
            shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp),
            containerColor = tokens.pageBackground,
            dragHandle = { BottomSheetDefaults.DragHandle() },
        ) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(group.name, color = tokens.textPrimary, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${group.nodes.size} 个节点 · 当前 ${group.now}", color = tokens.textSecondary, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    TextButton(onClick = { testGroup(group) }, enabled = groupTesting == null) {
                        if (groupTesting == group.name) CircularProgressIndicator(Modifier.size(15.dp), strokeWidth = 2.dp)
                        else Icon(Icons.Rounded.Speed, null, Modifier.size(17.dp))
                        Spacer(Modifier.width(5.dp))
                        Text(if (groupTesting == group.name) "测速中" else "测试本组")
                    }
                }
                LazyColumn(
                    Modifier.fillMaxWidth().heightIn(max = 560.dp),
                    verticalArrangement = Arrangement.spacedBy(7.dp),
                    contentPadding = PaddingValues(bottom = 22.dp),
                ) {
                    items(group.nodes, key = { it.name }) { node ->
                        NodeSheetRow(
                            node = node,
                            delay = delays[node.name] ?: node.lastDelay,
                            selected = group.now == node.name,
                            onSelect = {
                                scope.launch {
                                    try {
                                        controller.select(group.name, node.name)
                                        selectedGroup = null
                                        onStateChanged()
                                    } catch (_: Exception) { }
                                }
                            },
                            onDelay = {
                                scope.launch {
                                    delays[node.name] = -2
                                    delays[node.name] = try { controller.delay(node.name) } catch (_: Exception) { -1 }
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ToolsPage(
    state: ProxyComposeState,
    controller: ProxyComposeController,
    notice: String,
    testingAll: Boolean,
    backdrop: LayerBackdrop?,
    onGlobalDelay: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var dialogTitle by remember { mutableStateOf("") }
    var dialogBody by remember { mutableStateOf("") }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        item { CompactTopBar("工具", if (state.running) "代理运行中" else "代理已停止", null) }
        item {
            ActionRow(Icons.Rounded.Language, "WebUI", "本机 Zashboard · 与 Mihomo API 同源", if (state.running) "打开" else "未运行", backdrop, enabled = state.running) {
                context.startActivity(Intent(context, ProxyWebUiActivity::class.java))
            }
        }
        item {
            ActionRow(Icons.Rounded.Speed, if (testingAll) "正在测速" else "全部节点测速", "策略组批量测速 + 未覆盖节点补测", if (state.running) "开始" else "未运行", backdrop, enabled = state.running && !testingAll, onClick = onGlobalDelay)
        }
        item {
            ActionRow(Icons.Rounded.CloudSync, "订阅与配置", "订阅、配置切换、YAML 编辑与导入", "打开", backdrop) {
                context.startActivity(Intent(context, ProxySubscriptionActivity::class.java))
            }
        }
        item {
            ActionRow(Icons.Rounded.Storage, "内核管理", "Mihomo 更新与内核运行文件", "管理", backdrop) {
                context.startActivity(Intent(context, ProxyCoreActivity::class.java))
            }
        }
        item {
            ActionRow(Icons.Rounded.Build, "运行诊断", "Root、透明代理、核心与控制接口", "查看", backdrop) {
                scope.launch {
                    dialogTitle = "运行诊断"
                    dialogBody = runCatching { controller.diagnostics() }.getOrElse { it.message ?: "诊断失败" }
                }
            }
        }
        item {
            ActionRow(Icons.Rounded.Description, "最终启动配置", "查看实际交给 Mihomo 的运行配置", "查看", backdrop) {
                scope.launch {
                    dialogTitle = "最终启动配置"
                    dialogBody = runCatching { controller.startupConfig() }.getOrElse { it.message ?: "读取失败" }
                }
            }
        }
        if (notice.isNotBlank()) item { Text(notice, color = LocalBichenTokens.current.textSecondary, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(4.dp)) }
    }

    if (dialogBody.isNotBlank()) {
        AlertDialog(
            onDismissRequest = { dialogBody = "" },
            title = { Text(dialogTitle) },
            text = { LazyColumn(Modifier.heightIn(max = 520.dp)) { item { Text(dialogBody, fontSize = 12.sp, lineHeight = 17.sp) } } },
            confirmButton = { TextButton(onClick = { dialogBody = "" }) { Text("关闭") } },
        )
    }
}

@Composable
private fun SettingsPage(
    state: ProxyComposeState,
    controller: ProxyComposeController,
    backdrop: LayerBackdrop?,
    onStateChanged: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var choiceTitle by remember { mutableStateOf("") }
    var choices by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var onChoice by remember { mutableStateOf<((String, String) -> Unit)?>(null) }

    fun showChoices(title: String, items: List<Pair<String, String>>, action: (String, String) -> Unit) {
        choiceTitle = title
        choices = items
        onChoice = action
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        item { CompactTopBar("设置", "代理运行参数", null) }
        item { SectionLabel("运行配置", "修改后重启代理生效") }
        item {
            ActionRow(Icons.Rounded.Storage, "核心", state.core, "更改", backdrop) {
                showChoices("核心", controller.cores()) { id, _ -> controller.setCore(id); onStateChanged() }
            }
        }
        item {
            ActionRow(Icons.Rounded.Public, "运行模式", state.mode, "更改", backdrop) {
                showChoices("运行模式", controller.modes()) { id, _ -> controller.setMode(id); onStateChanged() }
            }
        }
        item {
            ActionRow(Icons.Rounded.Language, "IPv6", when (state.ipv6) { "disable" -> "禁用"; "bypass" -> "绕过核心"; else -> "启用" }, "更改", backdrop) {
                showChoices("IPv6", controller.ipv6Modes()) { id, _ -> controller.setIpv6(id); onStateChanged() }
            }
        }
        item {
            ActionRow(Icons.Rounded.Description, "当前配置", state.config, "切换", backdrop) {
                showChoices("当前配置", controller.configs().map { it to it }) { _, label ->
                    scope.launch { runCatching { controller.selectConfig(label) }; onStateChanged() }
                }
            }
        }
        item {
            ActionRow(Icons.Rounded.CloudSync, "订阅与 YAML", "添加订阅、导入配置、编辑完整 YAML", "打开", backdrop) {
                context.startActivity(Intent(context, ProxySubscriptionActivity::class.java))
            }
        }
        item {
            ActionRow(Icons.Rounded.Storage, "内核管理", "检查版本、在线更新与恢复内置核心", "打开", backdrop) {
                context.startActivity(Intent(context, ProxyCoreActivity::class.java))
            }
        }
    }

    if (choiceTitle.isNotBlank()) {
        AlertDialog(
            onDismissRequest = { choiceTitle = "" },
            title = { Text(choiceTitle) },
            text = {
                LazyColumn(Modifier.heightIn(max = 440.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    items(choices) { pair ->
                        Surface(
                            onClick = { onChoice?.invoke(pair.first, pair.second); choiceTitle = "" },
                            shape = RoundedCornerShape(12.dp),
                            color = LocalBichenTokens.current.controlBackground,
                        ) {
                            Text(pair.second, Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp))
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { choiceTitle = "" }) { Text("取消") } },
        )
    }
}

@Composable
private fun CompactTopBar(
    title: String,
    subtitle: String,
    onBack: (() -> Unit)?,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val tokens = LocalBichenTokens.current
    Row(
        Modifier.fillMaxWidth().statusBarsPadding().heightIn(min = 58.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            SmallAction(Icons.AutoMirrored.Rounded.ArrowBack, "返回", onBack)
            Spacer(Modifier.width(6.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(title, color = tokens.textPrimary, fontSize = 22.sp, lineHeight = 27.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            if (subtitle.isNotBlank()) Text(subtitle, color = tokens.textSecondary, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically, content = actions)
    }
}

@Composable
private fun SmallAction(icon: ImageVector, description: String, onClick: () -> Unit) {
    val tokens = LocalBichenTokens.current
    Surface(
        onClick = onClick,
        modifier = Modifier.size(40.dp),
        shape = RoundedCornerShape(13.dp),
        color = tokens.controlBackground.copy(alpha = .88f),
        shadowElevation = 0.dp,
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(icon, description, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun PanelTabs(selected: PanelTab, onSelect: (PanelTab) -> Unit) {
    val tokens = LocalBichenTokens.current
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        PanelTab.entries.forEach { tab ->
            val active = tab == selected
            Surface(
                onClick = { onSelect(tab) },
                shape = RoundedCornerShape(12.dp),
                color = if (active) tokens.selectionBackground else tokens.controlBackground.copy(alpha = .78f),
                border = BorderStroke(1.dp, if (active) MaterialTheme.colorScheme.primary.copy(alpha = .18f) else Color.Transparent),
                shadowElevation = 0.dp,
            ) {
                Text(
                    tab.label,
                    Modifier.padding(horizontal = 13.dp, vertical = 7.dp),
                    color = if (active) MaterialTheme.colorScheme.primary else tokens.textSecondary,
                    fontSize = 12.sp,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                )
            }
        }
    }
}

@Composable
private fun StrategyCard(
    group: ProxyGroupUi,
    delay: Long?,
    measured: Int,
    modifier: Modifier,
    backdrop: LayerBackdrop?,
    onClick: () -> Unit,
) {
    val tokens = LocalBichenTokens.current
    GlassBlock(modifier = modifier, backdrop = backdrop, radius = 17, tintAlpha = .72f, onClick = onClick) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 11.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(28.dp).background(MaterialTheme.colorScheme.primary.copy(alpha = .10f), RoundedCornerShape(9.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(groupIcon(group.type), null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(17.dp))
                }
                Spacer(Modifier.width(7.dp))
                Text(group.name, Modifier.weight(1f), color = tokens.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                DelayBadge(delay)
            }
            Text(group.now, color = tokens.textSecondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${groupType(group.type)} · $measured/${group.nodes.size}", color = tokens.textSecondary, fontSize = 10.5.sp, maxLines = 1)
        }
    }
}

@Composable
private fun NodeGridCard(node: ProxyNodeUi, delay: Long?, modifier: Modifier, backdrop: LayerBackdrop?, onDelay: () -> Unit) {
    val tokens = LocalBichenTokens.current
    GlassBlock(modifier = modifier, backdrop = backdrop, radius = 16, tintAlpha = .70f, onClick = onDelay) {
        Column(Modifier.padding(11.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(node.name, color = tokens.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(node.type.ifBlank { "节点" }, Modifier.weight(1f), color = tokens.textSecondary, fontSize = 10.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                DelayBadge(delay)
            }
        }
    }
}

@Composable
private fun NodeSheetRow(node: ProxyNodeUi, delay: Long?, selected: Boolean, onSelect: () -> Unit, onDelay: () -> Unit) {
    val tokens = LocalBichenTokens.current
    val scheme = MaterialTheme.colorScheme
    Surface(
        onClick = onSelect,
        shape = RoundedCornerShape(15.dp),
        color = if (selected) tokens.selectionBackground else tokens.cardBackground,
        border = BorderStroke(1.dp, if (selected) scheme.primary.copy(alpha = .20f) else tokens.outline.copy(alpha = .55f)),
        shadowElevation = 0.dp,
    ) {
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 6.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(node.name, color = tokens.textPrimary, fontSize = 13.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(node.type.ifBlank { "节点" }, color = tokens.textSecondary, fontSize = 10.5.sp, maxLines = 1)
            }
            if (selected) Icon(Icons.Rounded.CheckCircle, "已选择", tint = scheme.primary, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            DelayBadge(delay)
            IconButton(onClick = onDelay, modifier = Modifier.size(38.dp)) { Icon(Icons.Rounded.Speed, "测速", tint = scheme.primary, modifier = Modifier.size(18.dp)) }
        }
    }
}

@Composable
private fun DelayBadge(delay: Long?) {
    val tokens = LocalBichenTokens.current
    when {
        delay == -2L -> CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 1.8.dp)
        delay == null -> Text("—", color = tokens.textSecondary, fontSize = 11.sp)
        delay <= 0 -> Text("超时", color = tokens.danger, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        else -> Text("${delay}ms", color = if (delay < 500) tokens.success else tokens.warning, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun QuickTile(
    icon: ImageVector,
    title: String,
    subtitle: String,
    modifier: Modifier,
    onClick: () -> Unit,
    backdrop: LayerBackdrop?,
    enabled: Boolean = true,
) {
    val tokens = LocalBichenTokens.current
    GlassBlock(modifier, backdrop, radius = 16, tintAlpha = .68f, onClick = if (enabled) onClick else null) {
        Column(Modifier.padding(horizontal = 11.dp, vertical = 12.dp), horizontalAlignment = Alignment.Start, verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Icon(icon, null, tint = if (enabled) MaterialTheme.colorScheme.primary else tokens.textSecondary, modifier = Modifier.size(21.dp))
            Text(title, color = if (enabled) tokens.textPrimary else tokens.textSecondary, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Text(subtitle, color = tokens.textSecondary, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun ActionRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    trailing: String,
    backdrop: LayerBackdrop?,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val tokens = LocalBichenTokens.current
    GlassBlock(Modifier.fillMaxWidth(), backdrop, radius = 16, tintAlpha = .70f, onClick = if (enabled) onClick else null) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(34.dp).background(tokens.controlBackground, RoundedCornerShape(11.dp)), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = if (enabled) MaterialTheme.colorScheme.primary else tokens.textSecondary, modifier = Modifier.size(19.dp))
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, color = if (enabled) tokens.textPrimary else tokens.textSecondary, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                Text(subtitle, color = tokens.textSecondary, fontSize = 10.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(trailing, color = if (enabled) MaterialTheme.colorScheme.primary else tokens.textSecondary, fontSize = 11.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.width(2.dp))
            Icon(Icons.Rounded.ChevronRight, null, tint = tokens.textSecondary, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun ConnectionRow(connection: ProxyConnectionUi, backdrop: LayerBackdrop?, onClose: () -> Unit) {
    val tokens = LocalBichenTokens.current
    GlassBlock(Modifier.fillMaxWidth(), backdrop, radius = 15, tintAlpha = .68f) {
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 5.dp, top = 9.dp, bottom = 9.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(connection.host, color = tokens.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(listOf(connection.rule, connection.chain).filter { it.isNotBlank() }.joinToString(" · "), color = tokens.textSecondary, fontSize = 10.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("↑ ${compactBytes(connection.upload)}  ↓ ${compactBytes(connection.download)}", color = tokens.textSecondary, fontSize = 10.sp)
            }
            IconButton(onClick = onClose, modifier = Modifier.size(38.dp)) { Icon(Icons.Rounded.Close, "断开", tint = tokens.textSecondary, modifier = Modifier.size(18.dp)) }
        }
    }
}

@Composable
private fun RuleRow(rule: ProxyRuleUi, backdrop: LayerBackdrop?) {
    val tokens = LocalBichenTokens.current
    GlassBlock(Modifier.fillMaxWidth(), backdrop, radius = 14, tintAlpha = .64f) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 11.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(rule.payload.ifBlank { rule.type }, color = tokens.textPrimary, fontSize = 12.5.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${rule.type} · ${rule.proxy}", color = tokens.textSecondary, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (rule.hitCount > 0) Text(rule.hitCount.toString(), color = MaterialTheme.colorScheme.primary, fontSize = 10.5.sp)
        }
    }
}

@Composable
private fun ProviderRow(provider: ProxyRuleProviderUi, backdrop: LayerBackdrop?, onUpdate: () -> Unit) {
    val tokens = LocalBichenTokens.current
    GlassBlock(Modifier.fillMaxWidth(), backdrop, radius = 15, tintAlpha = .68f) {
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 5.dp, top = 9.dp, bottom = 9.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(provider.name, color = tokens.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${provider.behavior} · ${provider.ruleCount} 条 · ${provider.updatedAt}", color = tokens.textSecondary, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconButton(onClick = onUpdate, modifier = Modifier.size(38.dp)) { Icon(Icons.Rounded.Refresh, "更新", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp)) }
        }
    }
}

@Composable
private fun Metric(value: String, label: String, modifier: Modifier) {
    val tokens = LocalBichenTokens.current
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(value, color = tokens.textPrimary, fontSize = 15.sp, lineHeight = 19.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(label, color = tokens.textSecondary, fontSize = 10.sp, maxLines = 1)
    }
}

@Composable
private fun SectionLabel(title: String, subtitle: String, modifier: Modifier = Modifier) {
    val tokens = LocalBichenTokens.current
    Column(modifier.padding(horizontal = 2.dp, vertical = 3.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, color = tokens.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Text(subtitle, color = tokens.textSecondary, fontSize = 10.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun EmptyState(title: String, subtitle: String) {
    val tokens = LocalBichenTokens.current
    Column(Modifier.fillMaxWidth().padding(vertical = 56.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(Icons.Rounded.PublicOff, null, tint = tokens.textSecondary, modifier = Modifier.size(28.dp))
        Text(title, color = tokens.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Text(subtitle, color = tokens.textSecondary, fontSize = 11.sp)
    }
}

@Composable
private fun CenterLoading() {
    Box(Modifier.fillMaxWidth().padding(vertical = 42.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp) }
}

@Composable
private fun GlassBlock(
    modifier: Modifier = Modifier,
    backdrop: LayerBackdrop?,
    radius: Int,
    tintAlpha: Float,
    onClick: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val tokens = LocalBichenTokens.current
    val shape = RoundedCornerShape(radius.dp)
    // Do not attach a RuntimeShader blur to every scrolling card. Some OEM GPU/WebView
    // combinations crash the whole activity while Compose creates multiple blur RenderNodes.
    // Keep the glass hierarchy with a translucent one-layer surface; the dock can still use
    // the single, already-proven backdrop blur.
    var surface = modifier
        .background(tokens.cardBackground.copy(alpha = if (backdrop != null) tintAlpha else .98f), shape)
        .border(1.dp, tokens.outline.copy(alpha = if (backdrop != null) .42f else .60f), shape)
        .clip(shape)
    if (onClick != null) surface = surface.clickable(onClick = onClick)
    Box(surface, content = content)
}

private fun groupIcon(type: String): ImageVector = when (type.lowercase()) {
    "urltest", "fallback" -> Icons.Rounded.Speed
    "selector" -> Icons.Rounded.Tune
    "loadbalance" -> Icons.Rounded.SwapHoriz
    else -> Icons.Rounded.Hub
}

private fun groupType(type: String): String = when (type.lowercase()) {
    "urltest" -> "自动测速"
    "selector" -> "手动选择"
    "fallback" -> "故障切换"
    "loadbalance" -> "负载均衡"
    else -> type.ifBlank { "策略组" }
}

private fun compactBytes(value: Long): String {
    if (value <= 0L) return "0 B"
    val kb = 1024.0
    val mb = kb * 1024
    val gb = mb * 1024
    return when {
        value >= gb -> String.format("%.1f GB", value / gb)
        value >= mb -> String.format("%.1f MB", value / mb)
        value >= kb -> String.format("%.0f KB", value / kb)
        else -> "$value B"
    }
}
