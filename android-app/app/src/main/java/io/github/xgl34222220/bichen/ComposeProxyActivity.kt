package io.github.xgl34222220.bichen

import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
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
        setContent { BichenTheme { ProxyApp { finish() } } }
    }
}

private enum class ProxyPage { Home, Panel, Tools, Settings }
private enum class PanelTab(val title: String) { Overview("概览"), Nodes("节点"), Subscription("订阅"), Connections("连接"), Rules("规则"), RuleSets("规则集") }
private enum class GroupSort { Config, Delay }

@Composable
private fun ProxyApp(onBack: () -> Unit) {
    val context = LocalContext.current
    val activity = context as ComponentActivity
    val controller = remember { ProxyComposeController(context) }
    val haze = rememberHazeState()
    val backdrop = rememberLayerBackdrop()
    val liquid = isRuntimeShaderSupported()
    var page by rememberSaveable { mutableStateOf(ProxyPage.Home) }
    var revision by remember { mutableIntStateOf(0) }
    var state by remember { mutableStateOf(ProxyComposeState()) }
    var busyText by remember { mutableStateOf("") }
    var notice by remember { mutableStateOf("") }
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
            val added = try { controller.ensureIcons() } catch (_: Exception) { 0 }
            if (added > 0) notice = "已补齐 $added 个缺失策略图标"
            revision++
        }
    }

    fun globalDelay() {
        if (!state.running || testing) return
        testing = true
        notice = "正在全局测速…"
        activity.lifecycleScope.launch {
            try {
                val result = controller.globalDelay()
                delays.clear()
                delays.putAll(result)
                val good = result.values.filter { it > 0 }
                notice = if (good.isEmpty()) "测速完成：全部超时" else "测速完成：${good.size}/${result.size} 可用 · 平均 ${good.average().toInt()} ms"
            } catch (error: Exception) {
                notice = error.message ?: "全局测速失败"
            } finally {
                testing = false
            }
        }
    }

    fun toggle() {
        if (busyText.isNotBlank()) return
        activity.lifecycleScope.launch {
            busyText = if (state.running) "正在停止…" else "正在启动…"
            try {
                val callback: (String) -> Unit = { step -> activity.runOnUiThread { busyText = step } }
                if (state.running) controller.stop(callback) else controller.start(callback)
                notice = if (state.running) "代理已停止" else "代理已启动"
            } catch (error: Exception) {
                notice = error.message ?: "代理操作失败"
            } finally {
                busyText = ""
                revision++
            }
        }
    }

    fun restart() {
        if (busyText.isNotBlank()) return
        activity.lifecycleScope.launch {
            busyText = "正在重启…"
            try {
                controller.stop { step -> activity.runOnUiThread { busyText = step } }
                controller.start { step -> activity.runOnUiThread { busyText = step } }
                notice = "代理已重启"
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
        try { controller.ensureIcons() } catch (cancel: CancellationException) { throw cancel } catch (_: Exception) { }
        loadState()
        while (true) {
            delay(3000)
            loadState()
        }
    }

    val dock = remember {
        listOf(
            DockItem("首页", Icons.Rounded.Home),
            DockItem("面板", Icons.Rounded.Public),
            DockItem("工具", Icons.Rounded.GridView),
            DockItem("设置", Icons.Rounded.Settings),
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
                ProxyPage.Home -> ProxyHome(state, delays, notice, busyText, testing, onBack, ::refresh, ::toggle, ::restart, ::globalDelay) { page = ProxyPage.Panel }
                ProxyPage.Panel -> ProxyPanel(state, controller, delays, notice, testing, ::refresh, ::globalDelay) { revision++ }
                ProxyPage.Tools -> ProxyTools(state, controller, notice, testing, ::globalDelay, ::refresh)
                ProxyPage.Settings -> ProxySettings(state, controller) { revision++ }
            }
        }
        BichenGlassDock(
            items = dock,
            selected = page.ordinal,
            onSelect = { page = ProxyPage.entries[it] },
            hazeState = haze,
            backdrop = backdrop.takeIf { liquid },
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
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
    onGlobalDelay: () -> Unit,
    onPanel: () -> Unit,
) {
    val tokens = LocalBichenTokens.current
    val good = delays.values.filter { it > 0 }
    val average = if (good.isEmpty()) "—" else "${good.average().toInt()} ms"
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item { PageHeader("代理", "${state.core} · ${state.mode}", onBack, onRefresh) }
        item {
            Surface(shape = RoundedCornerShape(30.dp), color = if (state.running) MaterialTheme.colorScheme.primaryContainer.copy(alpha = .48f) else tokens.cardBackground, shadowElevation = 2.dp) {
                Column(Modifier.fillMaxWidth().padding(22.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusPill(if (state.running) "运行中" else "已停止", if (state.running) tokens.success else tokens.warning)
                        Spacer(Modifier.weight(1f))
                        Icon(if (state.running) Icons.Rounded.CheckCircle else Icons.Rounded.PauseCircle, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(50.dp))
                    }
                    Text(state.config, color = tokens.textPrimary, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        ProxyMetric(formatBytes(state.memoryBytes), "内存", Modifier.weight(1f))
                        ProxyMetric(average, "全局延迟", Modifier.weight(1f))
                        ProxyMetric(state.connections.size.toString(), "连接", Modifier.weight(1f))
                    }
                    if (notice.isNotBlank()) Text(notice, color = tokens.textSecondary, style = MaterialTheme.typography.bodySmall)
                    if (busyText.isNotBlank()) Text(busyText, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onRefresh, Modifier.weight(1f), shape = RoundedCornerShape(18.dp)) { Icon(Icons.Rounded.Refresh, null); Spacer(Modifier.width(5.dp)); Text("重载") }
                        Button(onClick = onToggle, Modifier.weight(1f), shape = RoundedCornerShape(18.dp)) { Icon(if (state.running) Icons.Rounded.Stop else Icons.Rounded.PlayArrow, null); Spacer(Modifier.width(5.dp)); Text(if (state.running) "停止" else "启动") }
                        OutlinedButton(onClick = onRestart, enabled = state.running, modifier = Modifier.weight(1f), shape = RoundedCornerShape(18.dp)) { Icon(Icons.Rounded.RestartAlt, null); Spacer(Modifier.width(5.dp)); Text("重启") }
                    }
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                HomeActionCard(Icons.Rounded.Public, "面板", "策略组与连接", Modifier.weight(1f), onPanel)
                HomeActionCard(Icons.Rounded.Speed, if (testing) "测速中" else "测速", "全部节点", Modifier.weight(1f), onGlobalDelay)
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatusMetric("下载", formatBytes(state.downloadTotal), Modifier.weight(1f))
                StatusMetric("上传", formatBytes(state.uploadTotal), Modifier.weight(1f))
                StatusMetric("控制接口", if (state.panelReady) "在线" else "等待", Modifier.weight(1f))
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
    var search by rememberSaveable { mutableStateOf("") }
    var expanded by rememberSaveable { mutableStateOf<String?>(null) }
    var sort by rememberSaveable { mutableStateOf(GroupSort.Config) }
    var hideUnavailable by rememberSaveable { mutableStateOf(false) }
    var showSearch by rememberSaveable { mutableStateOf(false) }
    var showSettings by rememberSaveable { mutableStateOf(false) }

    val ordered = remember(state.groups, delays, sort, hideUnavailable, search) {
        var list = state.groups.filter { group ->
            search.isBlank() || group.name.contains(search, true) || group.now.contains(search, true)
        }
        if (hideUnavailable) list = list.filter { group -> group.nodes.any { (delays[it.name] ?: 0L) >= 0L } }
        when (sort) {
            GroupSort.Config -> list.sortedWith(compareBy<ProxyGroupUi>({ videoGroupPriority(it.name) }, { it.name }))
            GroupSort.Delay -> list.sortedBy { group -> delaySortKey(delays[group.now]) }
        }
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item("toolbar") {
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                RoundIcon(Icons.Rounded.Search, "搜索") { showSearch = !showSearch }
                Spacer(Modifier.width(8.dp))
                RoundIcon(Icons.Rounded.FilterList, "筛选") { hideUnavailable = !hideUnavailable }
                Spacer(Modifier.weight(1f))
                RoundIcon(Icons.Rounded.Sort, "排序") { sort = if (sort == GroupSort.Config) GroupSort.Delay else GroupSort.Config }
                Spacer(Modifier.width(8.dp))
                RoundIcon(Icons.Rounded.Tune, "代理设置") { showSettings = true }
            }
        }
        item("title") { Text("面板", color = tokens.textPrimary, fontSize = 32.sp, fontWeight = FontWeight.Bold) }
        item("tabs") {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PanelTab.entries.forEach { item ->
                    FilterChip(selected = tab == item, onClick = { tab = item }, label = { Text(item.title) })
                }
            }
        }
        if (showSearch) item("search") {
            OutlinedTextField(search, { search = it }, Modifier.fillMaxWidth(), singleLine = true, placeholder = { Text("搜索策略组或节点") }, leadingIcon = { Icon(Icons.Rounded.Search, null) }, shape = RoundedCornerShape(18.dp))
        }

        when (tab) {
            PanelTab.Overview -> {
                item("speed") {
                    Surface(shape = RoundedCornerShape(20.dp), color = tokens.cardBackground, shadowElevation = 1.dp) {
                        Row(Modifier.fillMaxWidth().padding(13.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Speed, null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(if (testing) "正在全局测速" else "全局测速延迟", color = tokens.textPrimary, style = MaterialTheme.typography.titleSmall)
                                Text(notice.ifBlank { "一次测试全部真实节点；策略卡片实时显示进度" }, color = tokens.textSecondary, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            TextButton(onClick = onGlobalDelay, enabled = state.running && !testing) { Text(if (testing) "测速中" else "开始") }
                        }
                    }
                }
                if (ordered.isEmpty()) item("empty") { EmptyCard(if (state.running) "没有可显示的策略组" else "代理尚未运行", "启动代理后读取 Mihomo 实时策略组。") }
                ordered.chunked(2).forEachIndexed { rowIndex, row ->
                    item("group-row-$rowIndex") {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                row.forEach { group ->
                                    StrategyGroupCard(group, delays, expanded == group.name, Modifier.weight(1f)) {
                                        expanded = if (expanded == group.name) null else group.name
                                    }
                                }
                                if (row.size == 1) Spacer(Modifier.weight(1f))
                            }
                            row.firstOrNull { it.name == expanded }?.let { group ->
                                ExpandedNodes(group, delays, controller, activity, onStateChanged)
                            }
                        }
                    }
                }
            }
            PanelTab.Nodes -> {
                val nodes = remember(state.groups, search) {
                    state.groups.flatMap { it.nodes }.distinctBy { it.name }.filter { search.isBlank() || it.name.contains(search, true) }
                }
                nodes.chunked(2).forEachIndexed { index, row ->
                    item("node-row-$index") {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            row.forEach { node -> NodeCard(node, selected = false, delay = delays[node.name], Modifier.weight(1f), onSelect = {}, onDelay = { activity.lifecycleScope.launch { delays[node.name] = try { controller.delay(node.name) } catch (_: Exception) { -1L } } }) }
                            if (row.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
            PanelTab.Subscription -> item("subscription") {
                ActionGroup(listOf(ProxyActionItem(Icons.Rounded.CloudSync, "订阅管理", "添加、编辑、删除自己的订阅；私人订阅不内置") { context.startActivity(Intent(context, ProxySubscriptionActivity::class.java)) }))
            }
            PanelTab.Connections -> {
                item("connection-stats") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StatusMetric("下载", formatBytes(state.downloadTotal), Modifier.weight(1f))
                        StatusMetric("上传", formatBytes(state.uploadTotal), Modifier.weight(1f))
                        StatusMetric("连接", state.connections.size.toString(), Modifier.weight(1f))
                    }
                }
                items(state.connections, key = { it.id }) { c ->
                    Surface(shape = RoundedCornerShape(20.dp), color = tokens.cardBackground) {
                        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(c.host, color = tokens.textPrimary, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (c.rule.isNotBlank()) Text(c.rule, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall)
                            if (c.chain.isNotBlank()) Text(c.chain, color = tokens.textSecondary, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("↑ ${formatBytes(c.upload)}   ↓ ${formatBytes(c.download)}", color = tokens.textSecondary, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
            PanelTab.Rules, PanelTab.RuleSets -> item("rules-link") {
                ActionGroup(listOf(ProxyActionItem(Icons.Rounded.Rule, if (tab == PanelTab.Rules) "规则" else "规则集", "当前先读取配置中的真实规则；点此编辑当前 YAML") { context.startActivity(Intent(context, ProxySubscriptionActivity::class.java)) }))
            }
        }
    }

    if (showSettings) {
        AlertDialog(
            onDismissRequest = { showSettings = false },
            title = { Text("代理设置") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("排序方式", style = MaterialTheme.typography.labelMedium, color = tokens.textSecondary)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = sort == GroupSort.Config, onClick = { sort = GroupSort.Config }, label = { Text("按配置排序") })
                        FilterChip(selected = sort == GroupSort.Delay, onClick = { sort = GroupSort.Delay }, label = { Text("按延迟排序") })
                    }
                    SettingSwitch("隐藏不可用节点", hideUnavailable) { hideUnavailable = it }
                    Text("策略卡片采用双列布局；点策略组后在原位置下方展开双列节点卡片。图标只在本地没有缓存时下载。", color = tokens.textSecondary, style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = { TextButton(onClick = { showSettings = false }) { Text("完成") } },
        )
    }
}

@Composable
private fun ExpandedNodes(
    group: ProxyGroupUi,
    delays: MutableMap<String, Long>,
    controller: ProxyComposeController,
    activity: ComponentActivity,
    onStateChanged: () -> Unit,
) {
    val tokens = LocalBichenTokens.current
    Surface(shape = RoundedCornerShape(24.dp), color = tokens.elevatedCardBackground.copy(alpha = .65f)) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(group.name, color = tokens.textPrimary, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            group.nodes.chunked(2).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { node ->
                        NodeCard(
                            node = node,
                            selected = node.name == group.now,
                            delay = delays[node.name],
                            modifier = Modifier.weight(1f),
                            onSelect = {
                                activity.lifecycleScope.launch {
                                    try { controller.select(group.name, node.name) } catch (_: Exception) { }
                                    onStateChanged()
                                }
                            },
                            onDelay = {
                                activity.lifecycleScope.launch { delays[node.name] = try { controller.delay(node.name) } catch (_: Exception) { -1L } }
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
private fun StrategyGroupCard(group: ProxyGroupUi, delays: Map<String, Long>, expanded: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val tokens = LocalBichenTokens.current
    val measured = group.nodes.count { delays.containsKey(it.name) }
    Surface(modifier = modifier.clickable(onClick = onClick), shape = RoundedCornerShape(22.dp), color = if (expanded) MaterialTheme.colorScheme.primaryContainer.copy(alpha = .45f) else tokens.cardBackground, shadowElevation = 1.dp) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                MiniGroupIcon(group)
                Spacer(Modifier.width(8.dp))
                Text(group.name, Modifier.weight(1f), color = tokens.textPrimary, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text("${prettyGroupType(group.type)}  $measured/${group.nodes.size}", color = tokens.textSecondary, style = MaterialTheme.typography.labelSmall)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(group.now, Modifier.weight(1f), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                DelayPill(delays[group.now])
            }
        }
    }
}

@Composable
private fun NodeCard(node: ProxyNodeUi, selected: Boolean, delay: Long?, modifier: Modifier, onSelect: () -> Unit, onDelay: () -> Unit) {
    val tokens = LocalBichenTokens.current
    Surface(modifier = modifier.clickable(onClick = onSelect), shape = RoundedCornerShape(20.dp), color = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = .62f) else tokens.cardBackground, shadowElevation = 1.dp) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(node.name, Modifier.weight(1f), color = tokens.textPrimary, style = MaterialTheme.typography.bodyMedium, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (selected) Icon(Icons.Rounded.CheckCircle, "已选择", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (node.udp) Text("UDP", color = tokens.textSecondary, style = MaterialTheme.typography.labelSmall)
                if (node.type.isNotBlank()) {
                    if (node.udp) Text(" · ", color = tokens.textSecondary, style = MaterialTheme.typography.labelSmall)
                    Text(node.type, color = tokens.textSecondary, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.weight(1f))
                DelayPill(delay)
            }
            TextButton(onClick = onDelay, modifier = Modifier.align(Alignment.End).heightIn(min = 30.dp), contentPadding = PaddingValues(horizontal = 6.dp)) { Text("测速", fontSize = 11.sp) }
        }
    }
}

@Composable
private fun MiniGroupIcon(group: ProxyGroupUi) {
    val bitmap = remember(group.iconPath) { if (group.iconPath.isBlank()) null else BitmapFactory.decodeFile(group.iconPath) }
    Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.size(34.dp)) {
        Box(contentAlignment = Alignment.Center) {
            if (bitmap != null) Image(bitmap.asImageBitmap(), null, Modifier.size(26.dp).clip(RoundedCornerShape(5.dp)), contentScale = ContentScale.Fit)
            else Icon(Icons.Rounded.Public, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun DelayPill(delay: Long?) {
    val tokens = LocalBichenTokens.current
    val color = when {
        delay == null -> tokens.textSecondary
        delay < 0 -> MaterialTheme.colorScheme.error
        delay <= 150 -> tokens.success
        delay <= 300 -> MaterialTheme.colorScheme.primary
        else -> tokens.warning
    }
    Surface(shape = CircleShape, color = color.copy(alpha = .12f)) {
        Text(delayText(delay), Modifier.padding(horizontal = 7.dp, vertical = 3.dp), color = color, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun ProxyTools(state: ProxyComposeState, controller: ProxyComposeController, notice: String, testing: Boolean, onGlobalDelay: () -> Unit, onEnsureIcons: () -> Unit) {
    val context = LocalContext.current
    val activity = context as ComponentActivity
    var dialogTitle by remember { mutableStateOf("") }
    var dialogText by remember { mutableStateOf("") }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { PageHeader("工具", "运行、测速、日志与配置", null, null) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusMetric("内存", formatBytes(state.memoryBytes), Modifier.weight(1f))
                StatusMetric("下载", formatBytes(state.downloadTotal), Modifier.weight(1f))
                StatusMetric("连接", state.connections.size.toString(), Modifier.weight(1f))
            }
        }
        if (notice.isNotBlank()) item { Text(notice, color = LocalBichenTokens.current.textSecondary, style = MaterialTheme.typography.bodySmall) }
        item {
            ActionGroup(
                listOf(
                    ProxyActionItem(Icons.Rounded.Speed, if (testing) "全局测速中" else "全局测速", "测试全部真实节点", onGlobalDelay),
                    ProxyActionItem(Icons.Rounded.Image, "补齐策略图标", "只下载本地缺失图标，已缓存图标不重复下载", onEnsureIcons),
                    ProxyActionItem(Icons.Rounded.Description, "运行日志", "Root / Mihomo / TPROXY 诊断") {
                        activity.lifecycleScope.launch { dialogTitle = "运行日志"; dialogText = try { controller.diagnostics() } catch (e: Exception) { e.message ?: "读取失败" } }
                    },
                    ProxyActionItem(Icons.Rounded.Code, "最终启动配置", "查看运行时实际 Mihomo YAML") {
                        activity.lifecycleScope.launch { dialogTitle = "最终启动配置"; dialogText = try { controller.startupConfig() } catch (e: Exception) { e.message ?: "读取失败" } }
                    },
                    ProxyActionItem(Icons.Rounded.CloudSync, "订阅与配置", "添加订阅 / 编辑 YAML") { context.startActivity(Intent(context, ProxySubscriptionActivity::class.java)) },
                ),
            )
        }
    }
    if (dialogTitle.isNotBlank()) AlertDialog(onDismissRequest = { dialogTitle = "" }, title = { Text(dialogTitle) }, text = { Text(dialogText.ifBlank { "暂无内容" }, style = MaterialTheme.typography.bodySmall) }, confirmButton = { TextButton(onClick = { dialogTitle = "" }) { Text("关闭") } })
}

@Composable
private fun ProxySettings(state: ProxyComposeState, controller: ProxyComposeController, onChanged: () -> Unit) {
    val context = LocalContext.current
    val activity = context as ComponentActivity
    val tokens = LocalBichenTokens.current
    var coreMenu by remember { mutableStateOf(false) }
    var modeMenu by remember { mutableStateOf(false) }
    var ipv6Menu by remember { mutableStateOf(false) }
    var configMenu by remember { mutableStateOf(false) }
    val configs = remember(state.core, state.config) { controller.configs() }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            var name = "导入配置.yaml"
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor -> if (cursor.moveToFirst()) name = cursor.getString(0) ?: name }
            activity.lifecycleScope.launch { try { controller.importConfig(uri, name) } catch (_: Exception) { }; onChanged() }
        }
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { PageHeader("设置", "基础代理配置", null, null) }
        item {
            Surface(shape = RoundedCornerShape(24.dp), color = tokens.cardBackground) {
                Column(Modifier.padding(horizontal = 17.dp)) {
                    SettingRow("核心选择", state.core) { coreMenu = true }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(.3f))
                    SettingRow("运行模式", state.mode) { modeMenu = true }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(.3f))
                    SettingRow("IPv6", state.ipv6) { ipv6Menu = true }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(.3f))
                    SettingSwitch("自动覆写运行参数", state.autoOverwrite) { controller.setAutoOverwrite(it); onChanged() }
                }
            }
        }
        item {
            ActionGroup(
                listOf(
                    ProxyActionItem(Icons.Rounded.CloudSync, "订阅与 YAML 编辑", state.config) { context.startActivity(Intent(context, ProxySubscriptionActivity::class.java)) },
                    ProxyActionItem(Icons.Rounded.SwapHoriz, "切换已保存配置", "当前：${state.config}") { configMenu = true },
                    ProxyActionItem(Icons.Rounded.Add, "导入配置", "支持 YAML / YML / JSON") { importLauncher.launch(arrayOf("application/yaml", "text/yaml", "text/plain", "application/json")) },
                ),
            )
        }
    }
    if (coreMenu) SelectDialog("核心选择", controller.cores(), state.core, { coreMenu = false }) { controller.setCore(it); coreMenu = false; onChanged() }
    if (modeMenu) SelectDialog("运行模式", controller.modes(), state.mode, { modeMenu = false }) { controller.setMode(it); modeMenu = false; onChanged() }
    if (ipv6Menu) SelectDialog("IPv6", controller.ipv6Modes(), state.ipv6, { ipv6Menu = false }) { controller.setIpv6(it); ipv6Menu = false; onChanged() }
    if (configMenu) SelectDialog("配置选择", configs.map { it to it }, state.config, { configMenu = false }) { name -> activity.lifecycleScope.launch { try { controller.selectConfig(name) } catch (_: Exception) { }; configMenu = false; onChanged() } }
}

private data class ProxyActionItem(val icon: androidx.compose.ui.graphics.vector.ImageVector, val title: String, val subtitle: String, val action: () -> Unit)

@Composable
private fun ActionGroup(items: List<ProxyActionItem>) {
    val tokens = LocalBichenTokens.current
    Surface(shape = RoundedCornerShape(24.dp), color = tokens.cardBackground, shadowElevation = 1.dp) {
        Column(Modifier.padding(horizontal = 17.dp, vertical = 4.dp)) {
            items.forEachIndexed { index, item ->
                Row(Modifier.fillMaxWidth().clickable(onClick = item.action).padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Surface(shape = RoundedCornerShape(14.dp), color = tokens.elevatedCardBackground, modifier = Modifier.size(44.dp)) { Box(contentAlignment = Alignment.Center) { Icon(item.icon, null, tint = MaterialTheme.colorScheme.primary) } }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(item.title, color = tokens.textPrimary, style = MaterialTheme.typography.titleSmall)
                        Text(item.subtitle, color = tokens.textSecondary, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Icon(Icons.Rounded.ChevronRight, null, tint = tokens.textSecondary)
                }
                if (index != items.lastIndex) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(.3f))
            }
        }
    }
}

@Composable
private fun PageHeader(title: String, subtitle: String, onBack: (() -> Unit)?, onRefresh: (() -> Unit)?) {
    val tokens = LocalBichenTokens.current
    Row(Modifier.fillMaxWidth().statusBarsPadding().padding(top = 12.dp, bottom = 7.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (onBack != null) RoundIcon(Icons.AutoMirrored.Rounded.ArrowBack, "返回", onBack)
        Column(Modifier.weight(1f)) {
            Text(title, color = tokens.textPrimary, fontSize = 30.sp, fontWeight = FontWeight.Bold)
            Text(subtitle, color = tokens.textSecondary, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (onRefresh != null) RoundIcon(Icons.Rounded.Refresh, "刷新", onRefresh)
    }
}

@Composable
private fun RoundIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, desc: String, onClick: () -> Unit) {
    Surface(shape = CircleShape, color = LocalBichenTokens.current.cardBackground, shadowElevation = 1.dp, modifier = Modifier.size(44.dp)) { IconButton(onClick = onClick) { Icon(icon, desc, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp)) } }
}

@Composable
private fun HomeActionCard(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String, modifier: Modifier, onClick: () -> Unit) {
    val tokens = LocalBichenTokens.current
    Surface(modifier = modifier.clickable(onClick = onClick), shape = RoundedCornerShape(22.dp), color = tokens.cardBackground) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
            Text(title, color = tokens.textPrimary, style = MaterialTheme.typography.titleSmall)
            Text(subtitle, color = tokens.textSecondary, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun StatusMetric(label: String, value: String, modifier: Modifier) {
    val tokens = LocalBichenTokens.current
    Surface(modifier, shape = RoundedCornerShape(18.dp), color = tokens.cardBackground) {
        Column(Modifier.padding(12.dp)) {
            Text(label, color = tokens.textSecondary, style = MaterialTheme.typography.labelSmall)
            Text(value, color = tokens.textPrimary, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun ProxyMetric(value: String, label: String, modifier: Modifier) {
    val tokens = LocalBichenTokens.current
    Column(modifier) {
        Text(value, color = tokens.textPrimary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(label, color = tokens.textSecondary, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun StatusPill(text: String, color: Color) {
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(7.dp).background(color, CircleShape)); Spacer(Modifier.width(6.dp)); Text(text, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun SettingRow(title: String, value: String, onClick: () -> Unit) {
    val tokens = LocalBichenTokens.current
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 15.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), color = tokens.textPrimary, style = MaterialTheme.typography.titleSmall)
        Text(value, color = tokens.textSecondary, style = MaterialTheme.typography.bodySmall)
        Icon(Icons.Rounded.ChevronRight, null, tint = tokens.textSecondary)
    }
}

@Composable
private fun SettingSwitch(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun SelectDialog(title: String, options: List<Pair<String, String>>, current: String, dismiss: () -> Unit, select: (String) -> Unit) {
    AlertDialog(onDismissRequest = dismiss, title = { Text(title) }, text = { Column { options.forEach { (id, label) -> Row(Modifier.fillMaxWidth().clickable { select(id) }.padding(vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) { Text(label, Modifier.weight(1f)); if (id == current || label == current) Icon(Icons.Rounded.Check, null, tint = MaterialTheme.colorScheme.primary) } } } }, confirmButton = { TextButton(onClick = dismiss) { Text("关闭") } })
}

@Composable
private fun EmptyCard(title: String, subtitle: String) {
    Surface(shape = RoundedCornerShape(22.dp), color = LocalBichenTokens.current.cardBackground) {
        Column(Modifier.fillMaxWidth().padding(17.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(subtitle, color = LocalBichenTokens.current.textSecondary, style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun prettyGroupType(type: String): String = when (type.lowercase()) {
    "urltest", "url-test" -> "URLTest"
    "selector" -> "Selector"
    "fallback" -> "Fallback"
    "loadbalance", "load-balance" -> "LoadBalance"
    else -> type.ifBlank { "Group" }
}

private fun videoGroupPriority(name: String): Int {
    val order = listOf("节点选择", "手动选择", "故障转移", "香港节点", "台湾节点", "日本节点", "新加坡节点", "韩国节点", "美国节点", "AI 稳定", "AI 平台", "YouTube", "Google", "TikTok", "Telegram", "GitHub", "Emby", "Netflix", "Spotify", "Windows", "Apple", "Game", "Download", "bilibili")
    val i = order.indexOfFirst { key -> name.contains(key, true) }
    return if (i < 0) 9999 else i
}

private fun delaySortKey(value: Long?): Long = when {
    value == null -> Long.MAX_VALUE - 1
    value < 0 -> Long.MAX_VALUE
    else -> value
}

private fun delayText(value: Long?): String = when {
    value == null -> "—"
    value < 0 -> "超时"
    else -> "$value ms"
}

private fun formatBytes(value: Long): String = when {
    value >= 1_073_741_824L -> "%.1f GB".format(value / 1_073_741_824.0)
    value >= 1_048_576L -> "%.1f MB".format(value / 1_048_576.0)
    value >= 1024L -> "%.0f KB".format(value / 1024.0)
    else -> if (value <= 0) "0 B" else "$value B"
}
