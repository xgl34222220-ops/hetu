package io.github.xgl34222220.bichen

import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import io.github.xgl34222220.bichen.ui.*
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

private enum class ProxyPage(val label: String) { Home("首页"), Panel("面板"), Tools("工具"), Settings("设置") }

@Composable
private fun ProxyComposeApp(onBack: () -> Unit) {
    val context = LocalContext.current
    val controller = remember { ProxyComposeController(context) }
    var page by rememberSaveable { mutableStateOf(ProxyPage.Home) }
    var refresh by remember { mutableIntStateOf(0) }
    var state by remember { mutableStateOf(ProxyComposeState()) }
    var progress by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val haze = rememberHazeState()
    val backdrop = rememberLayerBackdrop()
    val liquid = isRuntimeShaderSupported()
    val dark = MaterialTheme.colorScheme.background.luminance() < .5f
    val dockItems = remember {
        listOf(
            DockItem("首页", Icons.Rounded.Shield, .94f),
            DockItem("面板", Icons.Rounded.Public, 1f),
            DockItem("工具", Icons.Rounded.GridView, .96f),
            DockItem("设置", Icons.Rounded.Settings, .94f),
        )
    }

    LaunchedEffect(refresh) {
        state = runCatching { controller.state() }.getOrElse { ProxyComposeState(message = it.message ?: "状态读取失败") }
    }
    LaunchedEffect(Unit) {
        while (true) {
            delay(2500)
            state = runCatching { controller.state() }.getOrElse { state }
        }
    }

    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier.fillMaxSize()
                .then(if (!liquid) Modifier.hazeSource(haze) else Modifier)
                .then(if (liquid) Modifier.layerBackdrop(backdrop) else Modifier),
        ) {
            ProxyBackdrop(dark)
            key(page) {
                val enter = remember { Animatable(0f) }
                LaunchedEffect(Unit) { enter.animateTo(1f, spring(dampingRatio = .86f, stiffness = 430f)) }
                Box(Modifier.fillMaxSize().padding(bottom = 108.dp).graphicsLayer { translationY = (1f - enter.value) * 10.dp.toPx() }) {
                    when (page) {
                        ProxyPage.Home -> ProxyHome(state, progress, onBack, onToggle = {
                            scope.launch {
                                progress = if (state.running) "正在停止…" else "正在启动…"
                                runCatching {
                                    if (state.running) controller.stop { step -> uiProgress(scope, step) { progress = it } }
                                    else controller.start { step -> uiProgress(scope, step) { progress = it } }
                                }.onFailure { progress = it.message ?: "操作失败" }
                                refresh++
                                delay(250)
                                progress = ""
                            }
                        }, onPanel = { page = ProxyPage.Panel }, onSettings = { page = ProxyPage.Settings })
                        ProxyPage.Panel -> ProxyPanel(state, controller) { refresh++ }
                        ProxyPage.Tools -> ProxyTools(state, controller, onPanel = { page = ProxyPage.Panel })
                        ProxyPage.Settings -> ProxySettings(state, controller) { refresh++ }
                    }
                }
            }
        }
        BichenGlassDock(
            items = dockItems,
            selected = ProxyPage.entries.indexOf(page),
            onSelect = { page = ProxyPage.entries[it] },
            hazeState = haze,
            backdrop = backdrop.takeIf { liquid },
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

private fun uiProgress(scope: kotlinx.coroutines.CoroutineScope, value: String, update: (String) -> Unit) {
    scope.launch { update(value) }
}

@Composable
private fun ProxyBackdrop(dark: Boolean) {
    val tokens = LocalBichenTokens.current
    val scheme = MaterialTheme.colorScheme
    Box(Modifier.fillMaxSize().background(tokens.pageBackground).drawBehind {
        drawRect(Brush.radialGradient(listOf(scheme.primary.copy(alpha = if (dark) .09f else .11f), Color.Transparent), center = Offset(size.width * .88f, 0f), radius = size.width * .86f))
        drawRect(Brush.radialGradient(listOf(scheme.secondary.copy(alpha = if (dark) .05f else .07f), Color.Transparent), center = Offset(0f, size.height * .75f), radius = size.width))
    })
}

@Composable
private fun ProxyHeader(kicker: String, title: String, subtitle: String, onBack: (() -> Unit)? = null, action: (() -> Unit)? = null) {
    val tokens = LocalBichenTokens.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) {
            IconButton(onClick = onBack, modifier = Modifier.size(46.dp)) { Icon(Icons.Rounded.ArrowBack, null) }
            Spacer(Modifier.width(6.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(kicker, color = MaterialTheme.colorScheme.primary, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.4.sp)
            Text(title, color = tokens.textPrimary, fontSize = 36.sp, lineHeight = 42.sp, fontWeight = FontWeight.Black)
            Text(subtitle, color = tokens.textSecondary, fontSize = 12.sp)
        }
        if (action != null) {
            Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = tokens.elevatedCardBackground), elevation = CardDefaults.cardElevation(4.dp)) {
                IconButton(onClick = action, modifier = Modifier.size(50.dp)) { Icon(Icons.Rounded.Refresh, null) }
            }
        }
    }
}

@Composable
private fun ProxyHome(state: ProxyComposeState, progress: String, onBack: () -> Unit, onToggle: () -> Unit, onPanel: () -> Unit, onSettings: () -> Unit) {
    val tokens = LocalBichenTokens.current
    val primary = MaterialTheme.colorScheme.primary
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp, 10.dp, 16.dp, 28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { ProxyHeader("ROOT PROXY", "代理", "${state.core} · ${state.mode}", onBack = onBack) }
        item {
            val shape = RoundedCornerShape(30.dp)
            Card(Modifier.fillMaxWidth().shadow(8.dp, shape, clip = false), shape = shape, colors = CardDefaults.cardColors(containerColor = tokens.cardBackground)) {
                Box(Modifier.fillMaxWidth().drawBehind {
                    drawCircle(Brush.radialGradient(listOf(primary.copy(alpha = .18f), Color.Transparent), center = Offset(size.width, 0f), radius = size.width * .78f), radius = size.width * .78f, center = Offset(size.width, 0f))
                }.padding(20.dp)) {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(10.dp).background(if (state.running) tokens.success else tokens.warning, CircleShape))
                            Spacer(Modifier.width(8.dp))
                            Text(if (state.running) "Root 透明代理正在运行" else "Root 透明代理未运行", color = tokens.textSecondary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                        Spacer(Modifier.height(18.dp))
                        Text(if (state.running) "已连接" else "准备就绪", color = tokens.textPrimary, fontSize = 36.sp, lineHeight = 42.sp, fontWeight = FontWeight.Black)
                        Text(state.config, color = tokens.textSecondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                        Spacer(Modifier.height(20.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            ProxyMetric(formatBytes(state.downloadTotal), "下载", Modifier.weight(1f))
                            ProxyMetric(formatBytes(state.uploadTotal), "上传", Modifier.weight(1f))
                            ProxyMetric(state.connections.size.toString(), "连接", Modifier.weight(1f))
                        }
                        if (progress.isNotBlank()) {
                            Spacer(Modifier.height(14.dp)); Text(progress, color = primary, fontSize = 12.sp)
                        }
                        Spacer(Modifier.height(18.dp))
                        Button(onClick = onToggle, Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(20.dp)) {
                            Icon(if (state.running) Icons.Rounded.Stop else Icons.Rounded.PowerSettingsNew, null)
                            Spacer(Modifier.width(8.dp)); Text(if (state.running) "停止代理" else "启动代理", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
        item { ProxySection("QUICK ACCESS", "常用入口", "策略、节点与配置") }
        item {
            ProxyActionGroup(
                listOf(
                    Triple(Icons.Rounded.Public, "策略组与节点", if (state.panelReady) "真实 Clash API 已连接" else "启动后读取策略组") to onPanel,
                    Triple(Icons.Rounded.Speed, "测速", "单节点测速与整组测速") to onPanel,
                    Triple(Icons.Rounded.Settings, "基础代理配置", "核心 · 模式 · IPv6 · 配置选择") to onSettings,
                ),
            )
        }
        if (state.message.isNotBlank() && !state.message.contains("尚未启动")) item {
            Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.errorContainer) { Text(state.message, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onErrorContainer, fontSize = 12.sp) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProxyPanel(state: ProxyComposeState, controller: ProxyComposeController, onRefresh: () -> Unit) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var query by rememberSaveable { mutableStateOf("") }
    var selectedGroup by remember { mutableStateOf<ProxyGroupUi?>(null) }
    val scope = rememberCoroutineScope()
    val delays = remember { mutableStateMapOf<String, Long>() }
    val tokens = LocalBichenTokens.current
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp, 10.dp, 16.dp, 28.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { ProxyHeader("CLASH PANEL", "面板", if (state.panelReady) "已连接当前运行核心" else "策略接口等待核心启动", action = onRefresh) }
        item {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                SegmentedButton(selected = tab == 0, onClick = { tab = 0 }, shape = SegmentedButtonDefaults.itemShape(0, 2)) { Text("策略组") }
                SegmentedButton(selected = tab == 1, onClick = { tab = 1 }, shape = SegmentedButtonDefaults.itemShape(1, 2)) { Text("连接") }
            }
        }
        if (tab == 0) {
            item { OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), placeholder = { Text("搜索策略组或当前节点") }, leadingIcon = { Icon(Icons.Rounded.Search, null) }, singleLine = true, shape = RoundedCornerShape(18.dp)) }
            val groups = state.groups.filter { query.isBlank() || it.name.contains(query, true) || it.now.contains(query, true) }
            if (groups.isEmpty()) item { ProxyEmpty(if (state.running) "没有可显示的策略组" else "代理尚未运行", if (state.running) "当前配置没有 Selector / URLTest 组。" else "启动代理后可选择策略组与节点。") }
            items(groups, key = { it.name }) { group ->
                Card(Modifier.fillMaxWidth().clickable { selectedGroup = group }, shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = tokens.cardBackground)) {
                    Column(Modifier.padding(16.dp)) {
                        Row { Text(group.name, Modifier.weight(1f), color = tokens.textPrimary, fontWeight = FontWeight.Bold); Text(group.type, color = tokens.textSecondary, fontSize = 11.sp) }
                        Spacer(Modifier.height(7.dp)); Text(group.now, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                        Text("${group.nodes.size} 个节点 · 点击选择与测速", color = tokens.textSecondary, fontSize = 11.sp)
                    }
                }
            }
        } else {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ProxyStatusMetric("下载", formatBytes(state.downloadTotal), Modifier.weight(1f))
                    ProxyStatusMetric("上传", formatBytes(state.uploadTotal), Modifier.weight(1f))
                    ProxyStatusMetric("连接", state.connections.size.toString(), Modifier.weight(1f))
                }
            }
            item { Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Text("实时连接与流量", Modifier.weight(1f), color = tokens.textSecondary, fontSize = 11.sp); TextButton(onClick = { scope.launch { runCatching { controller.closeAll() }; onRefresh() } }) { Text("清空连接") } } }
            if (state.connections.isEmpty()) item { ProxyEmpty("暂无连接", "当前没有可显示的活动连接。") }
            items(state.connections, key = { it.id }) { c ->
                Card(shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = tokens.cardBackground)) {
                    Column(Modifier.padding(16.dp)) {
                        Text(c.host, color = tokens.textPrimary, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (c.rule.isNotBlank()) Text(c.rule, color = MaterialTheme.colorScheme.primary, fontSize = 11.sp)
                        if (c.chain.isNotBlank()) Text(c.chain, color = tokens.textSecondary, fontSize = 11.sp, maxLines = 2)
                        Spacer(Modifier.height(6.dp)); Text("↑ ${formatBytes(c.upload)}   ↓ ${formatBytes(c.download)}", color = tokens.textSecondary, fontSize = 10.sp)
                    }
                }
            }
        }
    }
    selectedGroup?.let { group ->
        NodePicker(group, delays, onDismiss = { selectedGroup = null }, onSelect = { node -> scope.launch { runCatching { controller.select(group.name, node) }; selectedGroup = null; onRefresh() } }, onDelay = { node -> scope.launch { delays[node] = runCatching { controller.delay(node) }.getOrDefault(-1L) } })
    }
}

@Composable
private fun NodePicker(group: ProxyGroupUi, delays: Map<String, Long>, onDismiss: () -> Unit, onSelect: (String) -> Unit, onDelay: (String) -> Unit) {
    var query by remember { mutableStateOf("") }
    val nodes = group.nodes.filter { query.isBlank() || it.contains(query, true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(group.name) },
        text = {
            Column {
                OutlinedTextField(query, { query = it }, placeholder = { Text("搜索节点") }, singleLine = true)
                Spacer(Modifier.height(8.dp))
                LazyColumn(Modifier.heightIn(max = 440.dp)) {
                    items(nodes) { node ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f).clickable { onSelect(node) }) {
                                Text(node, fontWeight = if (node == group.now) FontWeight.Bold else FontWeight.Normal, color = if (node == group.now) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                                val d = delays[node]
                                Text(if (d == null) "未测速" else if (d < 0) "超时" else "$d ms", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            TextButton(onClick = { onDelay(node) }) { Text("测速") }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

@Composable
private fun ProxyTools(state: ProxyComposeState, controller: ProxyComposeController, onPanel: () -> Unit) {
    val scope = rememberCoroutineScope()
    var dialogTitle by remember { mutableStateOf("") }
    var dialogText by remember { mutableStateOf("") }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp, 10.dp, 16.dp, 28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { ProxyHeader("RUNTIME TOOLS", "工具", "核心 · 日志 · 启动配置") }
        item { ProxySection("RUNTIME", "运行与诊断", "只展示真实核心状态") }
        item {
            ProxyActionGroup(
                listOf(
                    Triple(Icons.Rounded.Description, "运行日志", "核心启动 · Root · 透明代理规则") to { scope.launch { dialogTitle = "运行日志"; dialogText = runCatching { controller.diagnostics() }.getOrElse { it.message ?: "读取失败" } } },
                    Triple(Icons.Rounded.Code, "最终启动配置", "实际交给核心的 startup-config") to { scope.launch { dialogTitle = "最终启动配置"; dialogText = runCatching { controller.startupConfig() }.getOrElse { it.message ?: "读取失败" } } },
                    Triple(Icons.Rounded.Public, "策略组与节点", if (state.panelReady) "面板接口已连接" else "面板尚未就绪") to onPanel,
                ),
            )
        }
    }
    if (dialogTitle.isNotBlank()) AlertDialog(onDismissRequest = { dialogTitle = "" }, title = { Text(dialogTitle) }, text = { Text(dialogText.take(16000), fontSize = 12.sp) }, confirmButton = { TextButton(onClick = { dialogTitle = "" }) { Text("关闭") } })
}

@Composable
private fun ProxySettings(state: ProxyComposeState, controller: ProxyComposeController, onRefresh: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var coreMenu by remember { mutableStateOf(false) }
    var modeMenu by remember { mutableStateOf(false) }
    var ipv6Menu by remember { mutableStateOf(false) }
    var configMenu by remember { mutableStateOf(false) }
    val configs = remember(state.config, state.core) { controller.configs() }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val name = runCatching {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else "config.yaml" }
            }.getOrNull() ?: "config.yaml"
            scope.launch { runCatching { controller.importConfig(uri, name) }; onRefresh() }
        }
    }
    val tokens = LocalBichenTokens.current
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp, 10.dp, 16.dp, 28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { ProxyHeader("PROXY SETTINGS", "设置", "核心 · 运行模式 · 网络 · 配置", action = onRefresh) }
        item {
            Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = tokens.cardBackground)) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    SettingRow("核心选择", state.core) { coreMenu = true }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(.30f))
                    SettingRow("运行模式", state.mode) { modeMenu = true }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(.30f))
                    SettingRow("IPv6", state.ipv6) { ipv6Menu = true }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(.30f))
                    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) { Text("自动覆写", color = tokens.textPrimary, fontWeight = FontWeight.SemiBold); Text("源配置保留，运行时生成启动副本", color = tokens.textSecondary, fontSize = 11.sp) }
                        Switch(checked = state.autoOverwrite, onCheckedChange = { controller.setAutoOverwrite(it); onRefresh() })
                    }
                }
            }
        }
        item { ProxySection("CONFIG", "配置选择", "源文件不会被运行逻辑直接修改") }
        item {
            Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = tokens.cardBackground)) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) { Text(state.config, color = tokens.textPrimary, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.MiddleEllipsis); Text("当前配置", color = tokens.textSecondary, fontSize = 11.sp) }
                        IconButton(onClick = { importLauncher.launch(arrayOf("application/yaml", "text/yaml", "text/plain", "application/json")) }) { Icon(Icons.Rounded.Add, null) }
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = { configMenu = true }, Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) { Text("切换已保存配置") }
                }
            }
        }
    }
    if (coreMenu) SelectDialog("核心选择", controller.cores(), state.core, { coreMenu = false }) { id -> controller.setCore(id); coreMenu = false; onRefresh() }
    if (modeMenu) SelectDialog("运行模式", controller.modes(), state.mode, { modeMenu = false }) { id -> controller.setMode(id); modeMenu = false; onRefresh() }
    if (ipv6Menu) SelectDialog("IPv6", controller.ipv6Modes(), state.ipv6, { ipv6Menu = false }) { id -> controller.setIpv6(id); ipv6Menu = false; onRefresh() }
    if (configMenu) SelectDialog("配置选择", configs.map { it to it }, state.config, { configMenu = false }) { name -> scope.launch { runCatching { controller.selectConfig(name) }; configMenu = false; onRefresh() } }
}

@Composable
private fun SettingRow(title: String, value: String, click: () -> Unit) {
    val tokens = LocalBichenTokens.current
    Row(Modifier.fillMaxWidth().clickable(onClick = click).padding(vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), color = tokens.textPrimary, fontWeight = FontWeight.SemiBold)
        Text(value, color = tokens.textSecondary, fontSize = 13.sp)
        Spacer(Modifier.width(5.dp)); Icon(Icons.Rounded.ChevronRight, null, tint = tokens.textSecondary)
    }
}

@Composable
private fun SelectDialog(title: String, options: List<Pair<String, String>>, current: String, dismiss: () -> Unit, select: (String) -> Unit) {
    AlertDialog(onDismissRequest = dismiss, title = { Text(title) }, text = {
        Column { options.forEach { (id, label) -> Row(Modifier.fillMaxWidth().clickable { select(id) }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) { Text(label, Modifier.weight(1f)); if (label == current || id == current) Icon(Icons.Rounded.Check, null, tint = MaterialTheme.colorScheme.primary) } } }
    }, confirmButton = { TextButton(onClick = dismiss) { Text("关闭") } })
}

@Composable
private fun ProxyActionGroup(items: List<Pair<Triple<androidx.compose.ui.graphics.vector.ImageVector, String, String>, () -> Unit>>) {
    val tokens = LocalBichenTokens.current
    Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = tokens.cardBackground)) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
            items.forEachIndexed { index, item ->
                val (data, action) = item
                Row(Modifier.fillMaxWidth().clickable(onClick = action).padding(vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
                    Surface(shape = RoundedCornerShape(15.dp), color = tokens.elevatedCardBackground, modifier = Modifier.size(42.dp)) { Box(contentAlignment = Alignment.Center) { Icon(data.first, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp)) } }
                    Spacer(Modifier.width(12.dp)); Column(Modifier.weight(1f)) { Text(data.second, color = tokens.textPrimary, fontWeight = FontWeight.SemiBold); Text(data.third, color = tokens.textSecondary, fontSize = 11.sp) }
                    Icon(Icons.Rounded.ChevronRight, null, tint = tokens.textSecondary)
                }
                if (index != items.lastIndex) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(.30f))
            }
        }
    }
}

@Composable
private fun ProxySection(kicker: String, title: String, subtitle: String) {
    val tokens = LocalBichenTokens.current
    Column(Modifier.fillMaxWidth().padding(start = 2.dp)) { Text(kicker, color = MaterialTheme.colorScheme.primary, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp); Text(title, color = tokens.textPrimary, fontSize = 17.sp, fontWeight = FontWeight.Bold); Text(subtitle, color = tokens.textSecondary, fontSize = 11.sp) }
}

@Composable
private fun ProxyMetric(value: String, label: String, modifier: Modifier = Modifier) { val t = LocalBichenTokens.current; Column(modifier) { Text(value, color = t.textPrimary, fontSize = 21.sp, fontWeight = FontWeight.Black); Text(label, color = t.textSecondary, fontSize = 11.sp) } }
@Composable
private fun ProxyStatusMetric(label: String, value: String, modifier: Modifier = Modifier) { val t = LocalBichenTokens.current; Card(modifier, shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = t.cardBackground)) { Column(Modifier.padding(14.dp)) { Text(label, color = t.textSecondary, fontSize = 10.sp); Text(value, color = t.textPrimary, fontWeight = FontWeight.Bold) } } }
@Composable
private fun ProxyEmpty(title: String, desc: String) { val t = LocalBichenTokens.current; Card(shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = t.cardBackground)) { Column(Modifier.padding(18.dp)) { Text(title, color = t.textPrimary, fontWeight = FontWeight.Bold); Spacer(Modifier.height(6.dp)); Text(desc, color = t.textSecondary, fontSize = 12.sp) } } }
private fun formatBytes(v: Long): String = when { v >= 1_073_741_824L -> "%.1fG".format(v / 1_073_741_824.0); v >= 1_048_576L -> "%.1fM".format(v / 1_048_576.0); v >= 1024L -> "%.0fK".format(v / 1024.0); else -> v.toString() }
