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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import io.github.xgl34222220.bichen.ui.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.blur.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop

class ComposeProxyActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { BichenTheme { ProxyComposeApp(onBack = { finish() }) } }
    }
}

private enum class ProxyPage { Home, Panel, Tools, Settings }

@Composable
private fun ProxyComposeApp(onBack: () -> Unit) {
    val context = LocalContext.current
    val activity = context as? ComponentActivity
    val composeScope = rememberCoroutineScope()
    val actionScope: CoroutineScope = activity?.lifecycleScope ?: composeScope
    val controller = remember { ProxyComposeController(context) }
    val haze = rememberHazeState()
    val backdrop = rememberLayerBackdrop()
    val liquid = isRuntimeShaderSupported()

    var page by rememberSaveable { mutableStateOf(ProxyPage.Home) }
    var revision by remember { mutableIntStateOf(0) }
    var state by remember { mutableStateOf(ProxyComposeState()) }
    var progress by remember { mutableStateOf("") }
    var testing by remember { mutableStateOf(false) }
    var testMessage by remember { mutableStateOf("") }
    val delays = remember { mutableStateMapOf<String, Long>() }

    val dockItems = remember {
        listOf(
            DockItem("首页", Icons.Rounded.Shield),
            DockItem("面板", Icons.Rounded.Public),
            DockItem("工具", Icons.Rounded.GridView),
            DockItem("设置", Icons.Rounded.Settings),
        )
    }

    suspend fun readState() {
        try {
            state = controller.state()
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            state = state.copy(message = error.message ?: "状态读取失败")
        }
    }

    fun runGlobalDelay() {
        if (testing || !state.running) return
        testing = true
        testMessage = "正在全局测速…"
        actionScope.launch {
            try {
                val result = controller.globalDelay()
                delays.clear(); delays.putAll(result)
                val good = result.values.filter { it > 0 }
                testMessage = if (good.isEmpty()) "测速完成：全部超时" else "测速完成：${good.size}/${result.size} 可用 · 平均 ${good.average().toInt()} ms"
            } catch (error: Exception) {
                testMessage = error.message ?: "全局测速失败"
            } finally { testing = false }
        }
    }

    fun refresh(forceIcons: Boolean = false) {
        actionScope.launch {
            if (forceIcons) {
                val n = try { controller.refreshIcons(true) } catch (_: Exception) { 0 }
                if (n > 0) testMessage = "已刷新 $n 个策略图标"
            }
            revision++
        }
    }

    LaunchedEffect(revision) { readState() }
    LaunchedEffect(Unit) {
        try { controller.refreshIcons(false) } catch (cancel: CancellationException) { throw cancel } catch (_: Exception) { }
        readState()
        while (true) {
            delay(3500)
            readState()
        }
    }

    Box(Modifier.fillMaxSize().background(LocalBichenTokens.current.pageBackground)) {
        Box(
            Modifier.fillMaxSize()
                .then(if (!liquid) Modifier.hazeSource(haze) else Modifier)
                .then(if (liquid) Modifier.layerBackdrop(backdrop) else Modifier)
                .padding(bottom = 106.dp),
        ) {
            when (page) {
                ProxyPage.Home -> ProxyHome(
                    state = state,
                    progress = progress,
                    testMessage = testMessage,
                    delays = delays,
                    testing = testing,
                    onBack = onBack,
                    onToggle = {
                        actionScope.launch {
                            progress = if (state.running) "正在停止代理…" else "正在启动代理…"
                            try {
                                val callback: (String) -> Unit = { step -> activity?.runOnUiThread { progress = step } }
                                if (state.running) controller.stop(callback) else controller.start(callback)
                            } catch (error: Exception) {
                                progress = error.message ?: "代理操作失败"
                            } finally {
                                revision++
                                delay(500)
                                if (!progress.contains("失败") && !progress.contains("订阅")) progress = ""
                            }
                        }
                    },
                    onPanel = { page = ProxyPage.Panel },
                    onSubscriptions = { context.startActivity(Intent(context, ProxySubscriptionActivity::class.java)) },
                    onGlobalDelay = ::runGlobalDelay,
                    onRefresh = { refresh(true) },
                )
                ProxyPage.Panel -> ProxyPanel(
                    state = state,
                    controller = controller,
                    actionScope = actionScope,
                    delays = delays,
                    testing = testing,
                    testMessage = testMessage,
                    onGlobalDelay = ::runGlobalDelay,
                    onRefresh = { refresh(true) },
                )
                ProxyPage.Tools -> ProxyTools(
                    state = state,
                    controller = controller,
                    actionScope = actionScope,
                    testing = testing,
                    onGlobalDelay = ::runGlobalDelay,
                    onRefreshIcons = { refresh(true) },
                )
                ProxyPage.Settings -> ProxySettings(state, controller, actionScope) { revision++ }
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
private fun ProxyHome(
    state: ProxyComposeState,
    progress: String,
    testMessage: String,
    delays: Map<String, Long>,
    testing: Boolean,
    onBack: () -> Unit,
    onToggle: () -> Unit,
    onPanel: () -> Unit,
    onSubscriptions: () -> Unit,
    onGlobalDelay: () -> Unit,
    onRefresh: () -> Unit,
) {
    val tokens = LocalBichenTokens.current
    val good = delays.values.filter { it > 0 }
    val avgDelay = if (good.isEmpty()) "—" else "${good.average().toInt()} ms"
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 22.dp, end = 22.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item("header") { ProxyHeader("代理", "${state.core} · ${state.mode}", onBack, onRefresh) }
        item("hero") {
            Surface(shape = RoundedCornerShape(30.dp), color = tokens.cardBackground, shadowElevation = 2.dp) {
                Column(Modifier.fillMaxWidth().padding(22.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusPill(if (state.running) "透明代理运行中" else "透明代理未运行", if (state.running) tokens.success else tokens.warning)
                        Spacer(Modifier.weight(1f))
                        Text("ROOT", color = tokens.textSecondary, style = MaterialTheme.typography.labelSmall)
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(if (state.running) "已连接" else "准备就绪", color = tokens.textPrimary, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                        Text(state.config, color = tokens.textSecondary, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        ProxyMetric(formatBytes(state.memoryBytes), "内存", Modifier.weight(1f))
                        ProxyMetric(avgDelay, "全局延迟", Modifier.weight(1f))
                        ProxyMetric(state.connections.size.toString(), "连接", Modifier.weight(1f))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        ProxyMetric(formatBytes(state.downloadTotal), "下载", Modifier.weight(1f))
                        ProxyMetric(formatBytes(state.uploadTotal), "上传", Modifier.weight(1f))
                        ProxyMetric(if (state.panelReady) "在线" else "等待", "控制接口", Modifier.weight(1f))
                    }
                    if (progress.isNotBlank()) Text(progress, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
                    if (testMessage.isNotBlank()) Text(testMessage, color = tokens.textSecondary, style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(
                            onClick = onGlobalDelay, enabled = state.running && !testing,
                            modifier = Modifier.weight(1f).heightIn(min = 50.dp), shape = RoundedCornerShape(18.dp),
                        ) {
                            Icon(Icons.Rounded.Speed, null, Modifier.size(19.dp)); Spacer(Modifier.width(7.dp)); Text(if (testing) "测速中" else "全局测速")
                        }
                        Button(onClick = onToggle, modifier = Modifier.weight(1f).heightIn(min = 50.dp), shape = RoundedCornerShape(18.dp)) {
                            Icon(if (state.running) Icons.Rounded.Stop else Icons.Rounded.PowerSettingsNew, null, Modifier.size(19.dp)); Spacer(Modifier.width(7.dp)); Text(if (state.running) "停止代理" else "启动代理")
                        }
                    }
                }
            }
        }
        item("quick-title") { ProxySection("常用入口", "节点、连接、订阅和 YAML 配置") }
        item("quick") {
            ActionGroup(
                listOf(
                    ActionItem(Icons.Rounded.Public, "策略组与节点", "延迟、节点选择、实时连接", onPanel),
                    ActionItem(Icons.Rounded.CloudSync, "订阅与配置", "添加自己的订阅 · 可直接编辑 YAML", onSubscriptions),
                    ActionItem(Icons.Rounded.Speed, "全局测速", "并发测试当前配置全部真实节点", onGlobalDelay),
                ),
            )
        }
        if (state.message.isNotBlank() && !state.message.contains("尚未启动")) {
            item("message") {
                Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                    Text(state.message, Modifier.fillMaxWidth().padding(16.dp), color = tokens.textSecondary, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun ProxyPanel(
    state: ProxyComposeState,
    controller: ProxyComposeController,
    actionScope: CoroutineScope,
    delays: MutableMap<String, Long>,
    testing: Boolean,
    testMessage: String,
    onGlobalDelay: () -> Unit,
    onRefresh: () -> Unit,
) {
    val tokens = LocalBichenTokens.current
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var query by rememberSaveable { mutableStateOf("") }
    var selectedGroup by remember { mutableStateOf<ProxyGroupUi?>(null) }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 22.dp, end = 22.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item("header") { ProxyHeader("面板", if (state.panelReady) "已连接 Mihomo 控制接口" else "启动代理后读取实时数据", null, onRefresh) }
        item("tabs") {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                SegmentedButton(selected = tab == 0, onClick = { tab = 0 }, shape = SegmentedButtonDefaults.itemShape(0, 2)) { Text("策略组") }
                SegmentedButton(selected = tab == 1, onClick = { tab = 1 }, shape = SegmentedButtonDefaults.itemShape(1, 2)) { Text("连接") }
            }
        }
        if (tab == 0) {
            item("speed") {
                Surface(shape = RoundedCornerShape(22.dp), color = tokens.cardBackground) {
                    Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Speed, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(if (testing) "正在全局测速" else "全局测速延迟", color = tokens.textPrimary, style = MaterialTheme.typography.titleSmall)
                            Text(testMessage.ifBlank { "测试所有真实节点并在每组显示延迟" }, color = tokens.textSecondary, style = MaterialTheme.typography.bodySmall)
                        }
                        TextButton(onClick = onGlobalDelay, enabled = state.running && !testing) { Text(if (testing) "测速中" else "开始") }
                    }
                }
            }
            item("search") {
                OutlinedTextField(
                    query, { query = it }, Modifier.fillMaxWidth(), singleLine = true,
                    placeholder = { Text("搜索策略组或当前节点") }, leadingIcon = { Icon(Icons.Rounded.Search, null) }, shape = RoundedCornerShape(20.dp),
                )
            }
            val groups = state.groups.filter { query.isBlank() || it.name.contains(query, true) || it.now.contains(query, true) }
            if (groups.isEmpty()) item("empty") { EmptyCard(if (state.running) "没有策略组" else "代理尚未运行", "启动后这里显示配置中的策略组与真实节点。") }
            items(groups, key = { it.name }) { group ->
                Surface(
                    modifier = Modifier.fillMaxWidth().clickable { selectedGroup = group },
                    shape = RoundedCornerShape(24.dp), color = tokens.cardBackground, shadowElevation = 1.dp,
                ) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        ProxyGroupIcon(group)
                        Spacer(Modifier.width(13.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(group.name, Modifier.weight(1f), color = tokens.textPrimary, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(group.type, color = tokens.textSecondary, style = MaterialTheme.typography.labelSmall)
                            }
                            Text(group.now, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            val d = delays[group.now]
                            Text("${group.nodes.size} 个节点 · ${delayText(d)}", color = delayColor(d, tokens.textSecondary), style = MaterialTheme.typography.bodySmall)
                        }
                        Icon(Icons.Rounded.ChevronRight, null, tint = tokens.textSecondary)
                    }
                }
            }
        } else {
            item("stats") {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatusMetric("下载", formatBytes(state.downloadTotal), Modifier.weight(1f))
                    StatusMetric("上传", formatBytes(state.uploadTotal), Modifier.weight(1f))
                    StatusMetric("内存", formatBytes(state.memoryBytes), Modifier.weight(1f))
                }
            }
            item("connection-title") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { ProxySection("实时连接", "${state.connections.size} 个活动连接") }
                    TextButton(onClick = { actionScope.launch { try { controller.closeAll() } catch (_: Exception) { }; onRefresh() } }) { Text("清空") }
                }
            }
            if (state.connections.isEmpty()) item("empty-connections") { EmptyCard("暂无连接", "当前没有可显示的活动连接。") }
            items(state.connections, key = { it.id }) { connection ->
                Surface(shape = RoundedCornerShape(22.dp), color = tokens.cardBackground, shadowElevation = 1.dp) {
                    Row(Modifier.padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
                        Surface(shape = RoundedCornerShape(13.dp), color = tokens.elevatedCardBackground, modifier = Modifier.size(44.dp)) {
                            Box(contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Language, null, tint = MaterialTheme.colorScheme.primary) }
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(connection.host, color = tokens.textPrimary, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (connection.rule.isNotBlank()) Text(connection.rule, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall)
                            if (connection.chain.isNotBlank()) Text(connection.chain, color = tokens.textSecondary, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("↑ ${formatBytes(connection.upload)}   ↓ ${formatBytes(connection.download)}", color = tokens.textSecondary, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
    }

    selectedGroup?.let { group ->
        NodePicker(
            group = group,
            delays = delays,
            onDismiss = { selectedGroup = null },
            onSelect = { node ->
                actionScope.launch {
                    try { controller.select(group.name, node) } catch (_: Exception) { }
                    selectedGroup = null; onRefresh()
                }
            },
            onDelay = { node ->
                actionScope.launch { delays[node] = try { controller.delay(node) } catch (_: Exception) { -1L } }
            },
        )
    }
}

@Composable
private fun NodePicker(
    group: ProxyGroupUi,
    delays: Map<String, Long>,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
    onDelay: (String) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val nodes = group.nodes.filter { query.isBlank() || it.contains(query, true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ProxyGroupIcon(group)
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(group.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${group.nodes.size} 个节点", style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        text = {
            Column {
                OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), singleLine = true, placeholder = { Text("搜索节点") }, leadingIcon = { Icon(Icons.Rounded.Search, null) }, shape = RoundedCornerShape(18.dp))
                Spacer(Modifier.height(8.dp))
                LazyColumn(Modifier.heightIn(max = 460.dp)) {
                    items(nodes, key = { it }) { node ->
                        val selected = node == group.now
                        Surface(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                            onClick = { onSelect(node) }, shape = RoundedCornerShape(17.dp),
                            color = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = .5f) else Color.Transparent,
                        ) {
                            Row(Modifier.padding(horizontal = 10.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.size(34.dp)) {
                                    Box(contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Speed, null, modifier = Modifier.size(18.dp)) }
                                }
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(node, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
                                    Text(delayText(delays[node]), style = MaterialTheme.typography.bodySmall, color = delayColor(delays[node], MaterialTheme.colorScheme.onSurfaceVariant))
                                }
                                if (selected) Icon(Icons.Rounded.CheckCircle, "当前节点", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(19.dp))
                                TextButton(onClick = { onDelay(node) }) { Text("测速") }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

@Composable
private fun ProxyTools(
    state: ProxyComposeState,
    controller: ProxyComposeController,
    actionScope: CoroutineScope,
    testing: Boolean,
    onGlobalDelay: () -> Unit,
    onRefreshIcons: () -> Unit,
) {
    var dialogTitle by remember { mutableStateOf("") }
    var dialogText by remember { mutableStateOf("") }
    LazyColumn(
        Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 22.dp, end = 22.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item("header") { ProxyHeader("工具", "核心 · 日志 · 配置 · 图标", null, null) }
        item("runtime") { ProxySection("运行与诊断", "只展示真实核心状态") }
        item("metrics") {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatusMetric("内存占用", formatBytes(state.memoryBytes), Modifier.weight(1f))
                StatusMetric("下载", formatBytes(state.downloadTotal), Modifier.weight(1f))
                StatusMetric("连接数", state.connections.size.toString(), Modifier.weight(1f))
            }
        }
        item("actions") {
            ActionGroup(
                listOf(
                    ActionItem(Icons.Rounded.Description, "运行日志", "Root 与 Mihomo 最近诊断") {
                        actionScope.launch { dialogTitle = "运行日志"; dialogText = try { controller.diagnostics() } catch (e: Exception) { e.message ?: "读取失败" } }
                    },
                    ActionItem(Icons.Rounded.Code, "最终启动配置", "查看运行时隔离后的 Mihomo YAML") {
                        actionScope.launch { dialogTitle = "最终启动配置"; dialogText = try { controller.startupConfig() } catch (e: Exception) { e.message ?: "读取失败" } }
                    },
                    ActionItem(Icons.Rounded.Speed, "全局测速", if (testing) "测速进行中" else "并发测试全部真实节点", onGlobalDelay),
                    ActionItem(Icons.Rounded.Image, "刷新策略图标", "从 YAML 的 icon 链接重新拉取缓存", onRefreshIcons),
                ),
            )
        }
    }
    if (dialogTitle.isNotBlank()) {
        AlertDialog(
            onDismissRequest = { dialogTitle = "" }, title = { Text(dialogTitle) },
            text = { Text(dialogText.ifBlank { "暂无内容" }, style = MaterialTheme.typography.bodySmall) },
            confirmButton = { TextButton(onClick = { dialogTitle = "" }) { Text("关闭") } },
        )
    }
}

@Composable
private fun ProxySettings(
    state: ProxyComposeState,
    controller: ProxyComposeController,
    actionScope: CoroutineScope,
    onRefresh: () -> Unit,
) {
    val context = LocalContext.current
    val tokens = LocalBichenTokens.current
    var coreMenu by remember { mutableStateOf(false) }
    var modeMenu by remember { mutableStateOf(false) }
    var ipv6Menu by remember { mutableStateOf(false) }
    var configMenu by remember { mutableStateOf(false) }
    val configs = remember(state.config, state.core) { controller.configs() }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            var displayName = "导入配置.yaml"
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) displayName = cursor.getString(0) ?: displayName
            }
            actionScope.launch { try { controller.importConfig(uri, displayName) } catch (_: Exception) { }; onRefresh() }
        }
    }

    LazyColumn(
        Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 22.dp, end = 22.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item("header") { ProxyHeader("设置", "运行模式与可编辑源配置", null, null) }
        item("base-title") { ProxySection("基础代理配置", "运行时生成副本，源 YAML 保持可编辑") }
        item("base") {
            Surface(shape = RoundedCornerShape(24.dp), color = tokens.cardBackground, shadowElevation = 1.dp) {
                Column(Modifier.padding(horizontal = 18.dp)) {
                    SettingRow("核心选择", state.core) { coreMenu = true }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(.3f))
                    SettingRow("运行模式", state.mode) { modeMenu = true }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(.3f))
                    SettingRow("IPv6", state.ipv6) { ipv6Menu = true }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(.3f))
                    Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("自动覆写", color = tokens.textPrimary, style = MaterialTheme.typography.titleSmall)
                            Text("只覆写运行端口/监听器，不修改源配置", color = tokens.textSecondary, style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(state.autoOverwrite, onCheckedChange = { controller.setAutoOverwrite(it); onRefresh() })
                    }
                }
            }
        }
        item("config-title") { ProxySection("配置与订阅", "默认配置不带私人订阅，用户自行添加") }
        item("config") {
            ActionGroup(
                listOf(
                    ActionItem(Icons.Rounded.CloudSync, "订阅与 YAML 编辑", state.config) { context.startActivity(Intent(context, ProxySubscriptionActivity::class.java)) },
                    ActionItem(Icons.Rounded.SwapHoriz, "切换已保存配置", "当前：${state.config}") { configMenu = true },
                    ActionItem(Icons.Rounded.Add, "导入配置", "支持 YAML / YML / JSON") { importLauncher.launch(arrayOf("application/yaml", "text/yaml", "text/plain", "application/json")) },
                ),
            )
        }
    }

    if (coreMenu) SelectDialog("核心选择", controller.cores(), state.core, { coreMenu = false }) { id -> controller.setCore(id); coreMenu = false; onRefresh() }
    if (modeMenu) SelectDialog("运行模式", controller.modes(), state.mode, { modeMenu = false }) { id -> controller.setMode(id); modeMenu = false; onRefresh() }
    if (ipv6Menu) SelectDialog("IPv6", controller.ipv6Modes(), state.ipv6, { ipv6Menu = false }) { id -> controller.setIpv6(id); ipv6Menu = false; onRefresh() }
    if (configMenu) SelectDialog("配置选择", configs.map { it to it }, state.config, { configMenu = false }) { name ->
        actionScope.launch { try { controller.selectConfig(name) } catch (_: Exception) { }; configMenu = false; onRefresh() }
    }
}

@Composable
private fun ProxyGroupIcon(group: ProxyGroupUi) {
    val tokens = LocalBichenTokens.current
    val bitmap = remember(group.iconPath) { if (group.iconPath.isBlank()) null else BitmapFactory.decodeFile(group.iconPath) }
    Surface(shape = RoundedCornerShape(15.dp), color = tokens.elevatedCardBackground, modifier = Modifier.size(46.dp), shadowElevation = 1.dp) {
        Box(contentAlignment = Alignment.Center) {
            if (bitmap != null) {
                Image(bitmap.asImageBitmap(), contentDescription = null, modifier = Modifier.size(32.dp).clip(RoundedCornerShape(7.dp)), contentScale = ContentScale.Fit)
            } else {
                Icon(Icons.Rounded.Public, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
            }
        }
    }
}

@Composable
private fun ProxyHeader(title: String, subtitle: String, onBack: (() -> Unit)?, onRefresh: (() -> Unit)?) {
    val tokens = LocalBichenTokens.current
    Row(
        Modifier.fillMaxWidth().statusBarsPadding().padding(top = 14.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (onBack != null) {
            Surface(shape = CircleShape, color = tokens.cardBackground, modifier = Modifier.size(46.dp), shadowElevation = 1.dp) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回", tint = MaterialTheme.colorScheme.primary) }
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, color = tokens.textPrimary, fontSize = 30.sp, fontWeight = FontWeight.Bold)
            Text(subtitle, color = tokens.textSecondary, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (onRefresh != null) {
            Surface(shape = CircleShape, color = tokens.cardBackground, modifier = Modifier.size(46.dp), shadowElevation = 1.dp) {
                IconButton(onClick = onRefresh) { Icon(Icons.Rounded.Refresh, "刷新并拉取图标", tint = MaterialTheme.colorScheme.primary) }
            }
        }
    }
}

@Composable
private fun SettingRow(title: String, value: String, onClick: () -> Unit) {
    val tokens = LocalBichenTokens.current
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), color = tokens.textPrimary, style = MaterialTheme.typography.titleSmall)
        Text(value, color = tokens.textSecondary, style = MaterialTheme.typography.bodyMedium)
        Icon(Icons.Rounded.ChevronRight, null, tint = tokens.textSecondary)
    }
}

@Composable
private fun SelectDialog(title: String, options: List<Pair<String, String>>, current: String, dismiss: () -> Unit, select: (String) -> Unit) {
    AlertDialog(
        onDismissRequest = dismiss, title = { Text(title) },
        text = {
            Column {
                options.forEach { (id, label) ->
                    Row(Modifier.fillMaxWidth().clickable { select(id) }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(label, Modifier.weight(1f)); if (label == current || id == current) Icon(Icons.Rounded.Check, null, tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = dismiss) { Text("关闭") } },
    )
}

private data class ActionItem(val icon: androidx.compose.ui.graphics.vector.ImageVector, val title: String, val subtitle: String, val action: () -> Unit)

@Composable
private fun ActionGroup(items: List<ActionItem>) {
    val tokens = LocalBichenTokens.current
    Surface(shape = RoundedCornerShape(24.dp), color = tokens.cardBackground, shadowElevation = 1.dp) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 4.dp)) {
            items.forEachIndexed { index, item ->
                Row(Modifier.fillMaxWidth().clickable(onClick = item.action).padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Surface(shape = RoundedCornerShape(14.dp), color = tokens.elevatedCardBackground, modifier = Modifier.size(44.dp)) {
                        Box(contentAlignment = Alignment.Center) { Icon(item.icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(21.dp)) }
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(item.title, color = tokens.textPrimary, style = MaterialTheme.typography.titleSmall)
                        Text(item.subtitle, color = tokens.textSecondary, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Icon(Icons.Rounded.ChevronRight, null, tint = tokens.textSecondary, modifier = Modifier.size(20.dp))
                }
                if (index != items.lastIndex) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(.3f))
            }
        }
    }
}

@Composable
private fun ProxySection(title: String, subtitle: String) {
    val tokens = LocalBichenTokens.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 2.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, color = tokens.textPrimary, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(subtitle, color = tokens.textSecondary, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun ProxyMetric(value: String, label: String, modifier: Modifier = Modifier) {
    val tokens = LocalBichenTokens.current
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(value, color = tokens.textPrimary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(label, color = tokens.textSecondary, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun StatusMetric(label: String, value: String, modifier: Modifier = Modifier) {
    val tokens = LocalBichenTokens.current
    Surface(modifier, shape = RoundedCornerShape(19.dp), color = tokens.cardBackground, shadowElevation = 1.dp) {
        Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(label, color = tokens.textSecondary, style = MaterialTheme.typography.labelSmall)
            Text(value, color = tokens.textPrimary, style = MaterialTheme.typography.titleSmall, maxLines = 1)
        }
    }
}

@Composable
private fun EmptyCard(title: String, subtitle: String) {
    val tokens = LocalBichenTokens.current
    Surface(shape = RoundedCornerShape(22.dp), color = tokens.cardBackground) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(title, color = tokens.textPrimary, style = MaterialTheme.typography.titleSmall)
            Text(subtitle, color = tokens.textSecondary, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun StatusPill(text: String, color: Color) {
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Row(Modifier.padding(horizontal = 11.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(7.dp).background(color, CircleShape)); Spacer(Modifier.width(7.dp)); Text(text, style = MaterialTheme.typography.labelMedium)
        }
    }
}

private fun delayText(value: Long?): String = when {
    value == null -> "未测速"
    value < 0 -> "超时"
    else -> "$value ms"
}

@Composable
private fun delayColor(value: Long?, fallback: Color): Color = when {
    value == null -> fallback
    value < 0 -> MaterialTheme.colorScheme.error
    value <= 150 -> LocalBichenTokens.current.success
    value <= 300 -> MaterialTheme.colorScheme.primary
    else -> LocalBichenTokens.current.warning
}

private fun formatBytes(value: Long): String = when {
    value >= 1_073_741_824L -> "%.1f GB".format(value / 1_073_741_824.0)
    value >= 1_048_576L -> "%.1f MB".format(value / 1_048_576.0)
    value >= 1024L -> "%.0f KB".format(value / 1024.0)
    else -> if (value <= 0) "0 B" else "$value B"
}
