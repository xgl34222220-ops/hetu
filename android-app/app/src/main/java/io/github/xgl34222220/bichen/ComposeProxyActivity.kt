package io.github.xgl34222220.bichen

import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
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
                Box(
                    Modifier.fillMaxSize().padding(bottom = 108.dp).graphicsLayer {
                        alpha = 1f
                        translationY = (1f - enter.value) * 10.dp.toPx()
                    },
                ) {
                    when (page) {
                        ProxyPage.Home -> ProxyHome(
                            state,
                            progress,
                            onBack,
                            onToggle = {
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
                            },
                            onPanel = { page = ProxyPage.Panel },
                            onSettings = { page = ProxyPage.Settings },
                        )
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
    Box(
        Modifier.fillMaxSize().background(tokens.pageBackground).drawBehind {
            drawRect(
                Brush.radialGradient(
                    listOf(scheme.primary.copy(alpha = if (dark) .09f else .10f), Color.Transparent),
                    center = Offset(size.width * .92f, size.height * .02f),
                    radius = size.width * .85f,
                ),
            )
            drawRect(
                Brush.radialGradient(
                    listOf(scheme.secondary.copy(alpha = if (dark) .06f else .07f), Color.Transparent),
                    center = Offset(size.width * .04f, size.height * .82f),
                    radius = size.width,
                ),
            )
        },
    )
}

@Composable
private fun ProxyHeader(
    kicker: String,
    title: String,
    subtitle: String,
    onBack: (() -> Unit)? = null,
    action: (() -> Unit)? = null,
) {
    val tokens = LocalBichenTokens.current
    Row(
        Modifier.fillMaxWidth().statusBarsPadding().heightIn(min = 64.dp).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (onBack != null) {
            Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                Surface(
                    modifier = Modifier.size(44.dp),
                    shape = CircleShape,
                    color = tokens.cardBackground,
                    contentColor = MaterialTheme.colorScheme.primary,
                    shadowElevation = 1.dp,
                ) {
                    IconButton(onClick = onBack, modifier = Modifier.fillMaxSize()) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回", Modifier.size(21.dp))
                    }
                }
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                title,
                color = tokens.textPrimary,
                fontSize = 26.sp,
                lineHeight = 34.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle.isNotBlank()) {
                Text(subtitle, color = tokens.textSecondary, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (action != null) {
            Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                Surface(
                    modifier = Modifier.size(44.dp),
                    shape = CircleShape,
                    color = tokens.cardBackground,
                    contentColor = MaterialTheme.colorScheme.primary,
                    shadowElevation = 1.dp,
                ) {
                    IconButton(onClick = action, modifier = Modifier.fillMaxSize()) {
                        Icon(Icons.Rounded.Refresh, "刷新", Modifier.size(21.dp))
                    }
                }
            }
        }
    }
    @Suppress("UNUSED_VARIABLE") val compactKicker = kicker
}

@Composable
private fun ProxyHome(
    state: ProxyComposeState,
    progress: String,
    onBack: () -> Unit,
    onToggle: () -> Unit,
    onPanel: () -> Unit,
    onSettings: () -> Unit,
) {
    val tokens = LocalBichenTokens.current
    val scheme = MaterialTheme.colorScheme
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item("header") { ProxyHeader("ROOT PROXY", "代理", "${state.core} · ${state.mode}", onBack = onBack) }
        item("hero") {
            Surface(shape = RoundedCornerShape(28.dp), color = tokens.cardBackground, shadowElevation = 2.dp) {
                Column(
                    Modifier.fillMaxWidth()
                        .background(Brush.linearGradient(listOf(scheme.primaryContainer.copy(alpha = .46f), tokens.cardBackground)))
                        .padding(22.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(shape = CircleShape, color = tokens.cardBackground.copy(alpha = .72f)) {
                            Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(6.dp).background(if (state.running) tokens.success else tokens.warning, CircleShape))
                                Spacer(Modifier.width(6.dp))
                                Text(if (state.running) "透明代理已开启" else "透明代理未开启", color = tokens.textPrimary, style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(state.config, color = tokens.textSecondary, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                        Text(
                            if (state.running) "已连接" else "准备就绪",
                            color = tokens.textPrimary,
                            fontSize = 24.sp,
                            lineHeight = 32.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        ProxyMetric(formatBytes(state.downloadTotal), "下载", Modifier.weight(1f))
                        ProxyMetric(formatBytes(state.uploadTotal), "上传", Modifier.weight(1f))
                        ProxyMetric(state.connections.size.toString(), "连接", Modifier.weight(1f))
                    }
                    if (progress.isNotBlank()) {
                        Text(progress, color = scheme.primary, style = MaterialTheme.typography.bodySmall)
                    }
                    Button(
                        onClick = onToggle,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp),
                        shape = RoundedCornerShape(18.dp),
                    ) {
                        Icon(if (state.running) Icons.Rounded.Stop else Icons.Rounded.PowerSettingsNew, null, Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(if (state.running) "停止代理" else "启动代理", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
        item("quick-heading") { ProxySection("QUICK ACCESS", "常用入口", "策略、节点与配置") }
        item("quick") {
            ProxyActionGroup(
                listOf(
                    Triple(Icons.Rounded.Public, "策略组与节点", if (state.panelReady) "真实 Clash API 已连接" else "启动后读取策略组") to onPanel,
                    Triple(Icons.Rounded.Speed, "测速", "单节点测速与整组测速") to onPanel,
                    Triple(Icons.Rounded.Settings, "基础代理配置", "核心 · 模式 · IPv6 · 配置选择") to onSettings,
                ),
            )
        }
        if (state.message.isNotBlank() && !state.message.contains("尚未启动")) {
            item("message") {
                Surface(shape = RoundedCornerShape(24.dp), color = scheme.errorContainer) {
                    Text(state.message, Modifier.padding(18.dp), color = scheme.onErrorContainer, style = MaterialTheme.typography.bodySmall)
                }
            }
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
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item("header") { ProxyHeader("CLASH PANEL", "面板", if (state.panelReady) "已连接当前运行核心" else "策略接口等待核心启动", action = onRefresh) }
        item("tabs") {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                SegmentedButton(selected = tab == 0, onClick = { tab = 0 }, shape = SegmentedButtonDefaults.itemShape(0, 2)) { Text("策略组") }
                SegmentedButton(selected = tab == 1, onClick = { tab = 1 }, shape = SegmentedButtonDefaults.itemShape(1, 2)) { Text("连接") }
            }
        }
        if (tab == 0) {
            item("search") {
                TextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("搜索策略组或当前节点") },
                    leadingIcon = { Icon(Icons.Rounded.Search, null) },
                    singleLine = true,
                    shape = RoundedCornerShape(20.dp),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = tokens.elevatedCardBackground,
                        unfocusedContainerColor = tokens.elevatedCardBackground,
                        disabledContainerColor = tokens.elevatedCardBackground,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        disabledIndicatorColor = Color.Transparent,
                    ),
                )
            }
            val groups = state.groups.filter { query.isBlank() || it.name.contains(query, true) || it.now.contains(query, true) }
            if (groups.isEmpty()) {
                item("empty-groups") {
                    ProxyEmpty(
                        if (state.running) "没有可显示的策略组" else "代理尚未运行",
                        if (state.running) "当前配置没有 Selector / URLTest 组。" else "启动代理后可选择策略组与节点。",
                    )
                }
            }
            items(groups, key = { it.name }) { group ->
                val groupVisual = proxyVisualParts(group.name)
                val nowVisual = proxyVisualParts(group.now)
                Surface(
                    modifier = Modifier.fillMaxWidth().clickable { selectedGroup = group },
                    shape = RoundedCornerShape(24.dp),
                    color = tokens.cardBackground,
                    shadowElevation = 1.dp,
                ) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        ProxyVisualBadge(groupVisual.first)
                        Spacer(Modifier.width(13.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(groupVisual.second, Modifier.weight(1f), color = tokens.textPrimary, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Surface(shape = CircleShape, color = tokens.elevatedCardBackground) {
                                    Text(group.type, Modifier.padding(horizontal = 8.dp, vertical = 4.dp), color = tokens.textSecondary, style = MaterialTheme.typography.labelSmall)
                                }
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(nowVisual.first, fontSize = 16.sp)
                                Spacer(Modifier.width(6.dp))
                                Text(nowVisual.second, Modifier.weight(1f), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Text("${group.nodes.size} 个节点 · 点击选择与测速", color = tokens.textSecondary, style = MaterialTheme.typography.bodySmall)
                        }
                        Spacer(Modifier.width(7.dp))
                        Icon(Icons.Rounded.ChevronRight, null, tint = tokens.textSecondary, modifier = Modifier.size(20.dp))
                    }
                }
            }
        } else {
            item("traffic") {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ProxyStatusMetric("下载", formatBytes(state.downloadTotal), Modifier.weight(1f))
                    ProxyStatusMetric("上传", formatBytes(state.uploadTotal), Modifier.weight(1f))
                    ProxyStatusMetric("连接", state.connections.size.toString(), Modifier.weight(1f))
                }
            }
            item("connections-heading") {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { ProxySection("LIVE", "实时连接", "当前核心连接与流量") }
                    TextButton(onClick = { scope.launch { runCatching { controller.closeAll() }; onRefresh() } }) { Text("清空") }
                }
            }
            if (state.connections.isEmpty()) item("empty-connections") { ProxyEmpty("暂无连接", "当前没有可显示的活动连接。") }
            items(state.connections, key = { it.id }) { c ->
                Surface(shape = RoundedCornerShape(24.dp), color = tokens.cardBackground, shadowElevation = 1.dp) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Surface(shape = RoundedCornerShape(15.dp), color = tokens.elevatedCardBackground, modifier = Modifier.size(44.dp)) {
                            Box(contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Language, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(21.dp)) }
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(c.host, color = tokens.textPrimary, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (c.rule.isNotBlank()) Text(c.rule, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall)
                            if (c.chain.isNotBlank()) Text(c.chain, color = tokens.textSecondary, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                            Text("↑ ${formatBytes(c.upload)}   ↓ ${formatBytes(c.download)}", color = tokens.textSecondary, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
    }
    selectedGroup?.let { group ->
        NodePicker(
            group,
            delays,
            onDismiss = { selectedGroup = null },
            onSelect = { node ->
                scope.launch {
                    runCatching { controller.select(group.name, node) }
                    selectedGroup = null
                    onRefresh()
                }
            },
            onDelay = { node -> scope.launch { delays[node] = runCatching { controller.delay(node) }.getOrDefault(-1L) } },
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
    val groupVisual = proxyVisualParts(group.name)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ProxyVisualBadge(groupVisual.first)
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(groupVisual.second, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${group.nodes.size} 个节点", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        text = {
            Column {
                OutlinedTextField(query, { query = it }, placeholder = { Text("搜索节点") }, leadingIcon = { Icon(Icons.Rounded.Search, null) }, singleLine = true, shape = RoundedCornerShape(18.dp))
                Spacer(Modifier.height(8.dp))
                LazyColumn(Modifier.heightIn(max = 440.dp)) {
                    items(nodes) { node ->
                        val visual = proxyVisualParts(node)
                        val selected = node == group.now
                        Surface(
                            onClick = { onSelect(node) },
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            shape = RoundedCornerShape(18.dp),
                            color = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = .48f) else Color.Transparent,
                        ) {
                            Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                                ProxyVisualBadge(visual.first)
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    Text(
                                        visual.second,
                                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    val d = delays[node]
                                    Text(
                                        if (d == null) "未测速" else if (d < 0) "超时" else "$d ms",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = when {
                                            d == null -> MaterialTheme.colorScheme.onSurfaceVariant
                                            d < 0 -> MaterialTheme.colorScheme.error
                                            d <= 150 -> MaterialTheme.colorScheme.primary
                                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                                        },
                                    )
                                }
                                if (selected) Icon(Icons.Rounded.CheckCircle, "当前节点", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
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
private fun ProxyVisualBadge(symbol: String) {
    val tokens = LocalBichenTokens.current
    Surface(
        shape = RoundedCornerShape(15.dp),
        color = tokens.elevatedCardBackground,
        modifier = Modifier.size(44.dp),
        shadowElevation = 1.dp,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(symbol.ifBlank { "☁️" }, fontSize = 21.sp)
        }
    }
}

private fun proxyVisualParts(value: String): Pair<String, String> {
    val separator = "\u2009"
    val cut = value.indexOf(separator)
    if (cut in 1..8) {
        val symbol = value.substring(0, cut)
        if (symbol in PROXY_VISUAL_SYMBOLS) return symbol to value.substring(cut + separator.length).trimStart()
    }
    val leading = PROXY_VISUAL_SYMBOLS.firstOrNull { value.startsWith(it) }
    return if (leading != null) leading to value.removePrefix(leading).trimStart() else "☁️" to value
}

private val PROXY_VISUAL_SYMBOLS = setOf(
    "🇭🇰", "🇹🇼", "🇯🇵", "🇸🇬", "🇺🇸", "🇰🇷", "🇬🇧", "🇩🇪", "🇫🇷", "🇨🇦", "🇦🇺", "🇷🇺", "🇮🇳", "🇳🇱", "🇹🇷",
    "🎯", "⚡", "🛟", "⚖️", "🔗", "🧭", "🌐", "⛔", "🔒", "🌍", "☁️",
)

@Composable
private fun ProxyTools(state: ProxyComposeState, controller: ProxyComposeController, onPanel: () -> Unit) {
    val scope = rememberCoroutineScope()
    var dialogTitle by remember { mutableStateOf("") }
    var dialogText by remember { mutableStateOf("") }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item("header") { ProxyHeader("RUNTIME TOOLS", "工具", "核心 · 日志 · 启动配置") }
        item("section") { ProxySection("RUNTIME", "运行与诊断", "只展示真实核心状态") }
        item("actions") {
            ProxyActionGroup(
                listOf(
                    Triple(Icons.Rounded.Description, "运行日志", "核心启动 · Root · 透明代理规则") to {
                        scope.launch {
                            dialogTitle = "运行日志"
                            dialogText = runCatching { controller.diagnostics() }.getOrElse { it.message ?: "读取失败" }
                        }
                    },
                    Triple(Icons.Rounded.Code, "最终启动配置", "实际交给核心的 startup-config") to {
                        scope.launch {
                            dialogTitle = "最终启动配置"
                            dialogText = runCatching { controller.startupConfig() }.getOrElse { it.message ?: "读取失败" }
                        }
                    },
                    Triple(Icons.Rounded.Public, "策略组与节点", if (state.panelReady) "面板接口已连接" else "面板尚未就绪") to onPanel,
                ),
            )
        }
    }
    if (dialogTitle.isNotBlank()) {
        AlertDialog(
            onDismissRequest = { dialogTitle = "" },
            title = { Text(dialogTitle) },
            text = { Text(dialogText.take(16000), style = MaterialTheme.typography.bodySmall) },
            confirmButton = { TextButton(onClick = { dialogTitle = "" }) { Text("关闭") } },
        )
    }
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
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) c.getString(0) else "config.yaml"
                }
            }.getOrNull() ?: "config.yaml"
            scope.launch { runCatching { controller.importConfig(uri, name) }; onRefresh() }
        }
    }
    val tokens = LocalBichenTokens.current
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item("header") { ProxyHeader("PROXY SETTINGS", "设置", "核心 · 运行模式 · 网络 · 配置", action = onRefresh) }
        item("base") {
            Surface(shape = RoundedCornerShape(24.dp), color = tokens.cardBackground, shadowElevation = 1.dp) {
                Column(Modifier.padding(horizontal = 18.dp, vertical = 5.dp)) {
                    SettingRow("核心选择", state.core) { coreMenu = true }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(.30f))
                    SettingRow("运行模式", state.mode) { modeMenu = true }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(.30f))
                    SettingRow("IPv6", state.ipv6) { ipv6Menu = true }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(.30f))
                    Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text("自动覆写", color = tokens.textPrimary, style = MaterialTheme.typography.titleSmall)
                            Text("源配置保留，运行时生成启动副本", color = tokens.textSecondary, style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(checked = state.autoOverwrite, onCheckedChange = { controller.setAutoOverwrite(it); onRefresh() })
                    }
                }
            }
        }
        item("config-heading") { ProxySection("CONFIG", "配置选择", "源文件不会被运行逻辑直接修改") }
        item("config") {
            Surface(shape = RoundedCornerShape(24.dp), color = tokens.cardBackground, shadowElevation = 1.dp) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(state.config, color = tokens.textPrimary, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                            Text("当前配置", color = tokens.textSecondary, style = MaterialTheme.typography.bodySmall)
                        }
                        Surface(shape = CircleShape, color = tokens.elevatedCardBackground, modifier = Modifier.size(44.dp)) {
                            IconButton(onClick = { importLauncher.launch(arrayOf("application/yaml", "text/yaml", "text/plain", "application/json")) }) {
                                Icon(Icons.Rounded.Add, "导入配置", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(21.dp))
                            }
                        }
                    }
                    OutlinedButton(onClick = { configMenu = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), shape = RoundedCornerShape(18.dp)) {
                        Text("切换已保存配置")
                    }
                }
            }
        }
    }
    if (coreMenu) SelectDialog("核心选择", controller.cores(), state.core, { coreMenu = false }) { id -> controller.setCore(id); coreMenu = false; onRefresh() }
    if (modeMenu) SelectDialog("运行模式", controller.modes(), state.mode, { modeMenu = false }) { id -> controller.setMode(id); modeMenu = false; onRefresh() }
    if (ipv6Menu) SelectDialog("IPv6", controller.ipv6Modes(), state.ipv6, { ipv6Menu = false }) { id -> controller.setIpv6(id); ipv6Menu = false; onRefresh() }
    if (configMenu) SelectDialog("配置选择", configs.map { it to it }, state.config, { configMenu = false }) { name ->
        scope.launch { runCatching { controller.selectConfig(name) }; configMenu = false; onRefresh() }
    }
}

@Composable
private fun SettingRow(title: String, value: String, click: () -> Unit) {
    val tokens = LocalBichenTokens.current
    Row(
        Modifier.fillMaxWidth().clickable(onClick = click).padding(vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, Modifier.weight(1f), color = tokens.textPrimary, style = MaterialTheme.typography.titleSmall)
        Text(value, color = tokens.textSecondary, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.width(5.dp))
        Icon(Icons.Rounded.ChevronRight, null, tint = tokens.textSecondary, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun SelectDialog(
    title: String,
    options: List<Pair<String, String>>,
    current: String,
    dismiss: () -> Unit,
    select: (String) -> Unit,
) {
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(title) },
        text = {
            Column {
                options.forEach { (id, label) ->
                    Row(
                        Modifier.fillMaxWidth().clickable { select(id) }.padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(label, Modifier.weight(1f))
                        if (label == current || id == current) Icon(Icons.Rounded.Check, null, tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = dismiss) { Text("关闭") } },
    )
}

@Composable
private fun ProxyActionGroup(items: List<Pair<Triple<androidx.compose.ui.graphics.vector.ImageVector, String, String>, () -> Unit>>) {
    val tokens = LocalBichenTokens.current
    Surface(shape = RoundedCornerShape(24.dp), color = tokens.cardBackground, shadowElevation = 1.dp) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 5.dp)) {
            items.forEachIndexed { index, item ->
                val (data, action) = item
                Row(
                    Modifier.fillMaxWidth().clickable(onClick = action).padding(vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Surface(shape = RoundedCornerShape(15.dp), color = tokens.elevatedCardBackground, modifier = Modifier.size(44.dp)) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(data.first, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(data.second, color = tokens.textPrimary, style = MaterialTheme.typography.titleSmall)
                        Text(data.third, color = tokens.textSecondary, style = MaterialTheme.typography.bodySmall)
                    }
                    Icon(Icons.Rounded.ChevronRight, null, tint = tokens.textSecondary, modifier = Modifier.size(20.dp))
                }
                if (index != items.lastIndex) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(.30f))
            }
        }
    }
}

@Composable
private fun ProxySection(kicker: String, title: String, subtitle: String) {
    val tokens = LocalBichenTokens.current
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 2.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text(title, color = tokens.textPrimary, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(subtitle, color = tokens.textSecondary, style = MaterialTheme.typography.bodySmall)
    }
    @Suppress("UNUSED_VARIABLE") val compactKicker = kicker
}

@Composable
private fun ProxyMetric(value: String, label: String, modifier: Modifier = Modifier) {
    val tokens = LocalBichenTokens.current
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(value, color = tokens.textPrimary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(label, color = tokens.textSecondary, fontSize = 11.sp)
    }
}

@Composable
private fun ProxyStatusMetric(label: String, value: String, modifier: Modifier = Modifier) {
    val tokens = LocalBichenTokens.current
    Surface(modifier, shape = RoundedCornerShape(20.dp), color = tokens.cardBackground, shadowElevation = 1.dp) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(label, color = tokens.textSecondary, style = MaterialTheme.typography.labelSmall)
            Text(value, color = tokens.textPrimary, style = MaterialTheme.typography.titleSmall)
        }
    }
}

@Composable
private fun ProxyEmpty(title: String, desc: String) {
    val tokens = LocalBichenTokens.current
    Surface(shape = RoundedCornerShape(24.dp), color = tokens.cardBackground, shadowElevation = 1.dp) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, color = tokens.textPrimary, style = MaterialTheme.typography.titleSmall)
            Text(desc, color = tokens.textSecondary, style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun formatBytes(v: Long): String = when {
    v >= 1_073_741_824L -> "%.1fG".format(v / 1_073_741_824.0)
    v >= 1_048_576L -> "%.1fM".format(v / 1_048_576.0)
    v >= 1024L -> "%.0fK".format(v / 1024.0)
    else -> v.toString()
}
