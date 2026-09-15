package io.github.xgl34222220.bichen

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import io.github.xgl34222220.bichen.ui.*
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.blur.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop

class ComposeMainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            var themeRevision by remember { mutableIntStateOf(0) }
            key(themeRevision) {
                BichenTheme {
                    BichenComposeApp(onThemeChanged = { themeRevision++ })
                }
            }
        }
    }
}

private enum class MainPage(val label: String) { Home("首页"), Apps("应用"), Rules("规则"), Activity("活动") }

@Composable
private fun BichenComposeApp(onThemeChanged: () -> Unit) {
    val context = LocalContext.current
    val activity = context as Activity
    val controller = remember { BichenComposeController(context) }
    var page by rememberSaveable { mutableStateOf(MainPage.Home) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    val dark = MaterialTheme.colorScheme.background.luminance() < .5f
    val hazeState = rememberHazeState()
    val liquidBackdrop = rememberLayerBackdrop()
    val liquid = isRuntimeShaderSupported()
    val dockItems = remember {
        listOf(
            DockItem("首页", Icons.Rounded.Home, .94f),
            DockItem("应用", Icons.Rounded.Apps, 1f),
            DockItem("规则", Icons.Rounded.Rule, .96f),
            DockItem("活动", Icons.Rounded.QueryStats, .96f),
        )
    }
    val pageIndex = MainPage.entries.indexOf(page)

    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier.fillMaxSize()
                .then(if (!liquid) Modifier.hazeSource(hazeState) else Modifier)
                .then(if (liquid) Modifier.layerBackdrop(liquidBackdrop) else Modifier),
        ) {
            AppBackdrop(dark)
            key(page) {
                val enter = remember { Animatable(0f) }
                LaunchedEffect(Unit) { enter.animateTo(1f, spring(dampingRatio = .86f, stiffness = 430f)) }
                Box(
                    Modifier.fillMaxSize().padding(bottom = 108.dp)
                        .graphicsLayerCompat(enter.value),
                ) {
                    when (page) {
                        MainPage.Home -> HomePage(controller, onSettings = { showSettings = true })
                        MainPage.Apps -> AppsPage(controller)
                        MainPage.Rules -> RulesPage(controller)
                        MainPage.Activity -> ActivityPage(controller)
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = true,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = fadeIn() + slideInVertically { it / 2 },
            exit = fadeOut() + slideOutVertically { it / 2 },
        ) {
            BichenGlassDock(
                items = dockItems,
                selected = pageIndex,
                onSelect = { page = MainPage.entries[it] },
                hazeState = hazeState,
                backdrop = liquidBackdrop.takeIf { liquid },
            )
        }
    }

    if (showSettings) {
        SettingsSheet(
            controller = controller,
            onDismiss = { showSettings = false },
            onThemeChanged = onThemeChanged,
        )
    }
}

private fun Modifier.graphicsLayerCompat(progress: Float): Modifier = this.then(
    Modifier.graphicsLayer {
        alpha = 1f
        translationY = (1f - progress) * 10.dp.toPx()
    },
)

@Composable
private fun AppBackdrop(dark: Boolean) {
    val tokens = LocalBichenTokens.current
    val scheme = MaterialTheme.colorScheme
    Box(
        Modifier.fillMaxSize().background(tokens.pageBackground).drawBehind {
            drawRect(
                Brush.radialGradient(
                    listOf(scheme.primary.copy(alpha = if (dark) .09f else .10f), Color.Transparent),
                    center = Offset(size.width * .92f, size.height * .02f), radius = size.width * .85f,
                ),
            )
            drawRect(
                Brush.radialGradient(
                    listOf(scheme.secondary.copy(alpha = if (dark) .06f else .07f), Color.Transparent),
                    center = Offset(size.width * .04f, size.height * .82f), radius = size.width,
                ),
            )
        },
    )
}

@Composable
private fun PageHeader(kicker: String, title: String, subtitle: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onAction: () -> Unit) {
    val tokens = LocalBichenTokens.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(kicker, color = MaterialTheme.colorScheme.primary, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.4.sp)
            Spacer(Modifier.height(4.dp))
            Text(title, color = tokens.textPrimary, fontSize = 39.sp, lineHeight = 44.sp, fontWeight = FontWeight.Black)
            Text(subtitle, color = tokens.textSecondary, fontSize = 12.sp)
        }
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = tokens.elevatedCardBackground),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        ) {
            IconButton(onClick = onAction, modifier = Modifier.size(50.dp)) { Icon(icon, null) }
        }
    }
}

@Composable
private fun SectionTitle(kicker: String, title: String, subtitle: String) {
    val tokens = LocalBichenTokens.current
    Column(Modifier.fillMaxWidth().padding(start = 2.dp, top = 4.dp, bottom = 2.dp)) {
        Text(kicker, color = MaterialTheme.colorScheme.primary, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
        Text(title, color = tokens.textPrimary, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        Text(subtitle, color = tokens.textSecondary, fontSize = 11.sp)
    }
}

@Composable
private fun HomePage(controller: BichenComposeController, onSettings: () -> Unit) {
    var refresh by remember { mutableIntStateOf(0) }
    val snapshot by produceState(initialValue = HomeSnapshot(), refresh) {
        value = runCatching { controller.homeSnapshot() }.getOrElse { HomeSnapshot(message = it.message ?: "状态读取失败") }
    }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == Activity.RESULT_OK) controller.startVpn()
        refresh++
    }
    val active = if (controller.preferredVpnMode()) snapshot.vpnRunning else snapshot.moduleEnabled
    val tokens = LocalBichenTokens.current

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, top = 10.dp, end = 16.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { PageHeader("PRIVACY ENGINE", "辟尘", "去广告 · 代理 · Root", Icons.Rounded.Settings, onSettings) }
        item {
            val shape = RoundedCornerShape(30.dp)
            Card(
                Modifier.fillMaxWidth().shadow(8.dp, shape, clip = false),
                shape = shape,
                colors = CardDefaults.cardColors(containerColor = tokens.cardBackground),
            ) {
                Box(
                    Modifier.fillMaxWidth().drawBehind {
                        drawCircle(
                            Brush.radialGradient(listOf(MaterialTheme.colorScheme.primary.copy(.18f), Color.Transparent), center = Offset(size.width, 0f), radius = size.width * .78f),
                            radius = size.width * .78f, center = Offset(size.width, 0f),
                        )
                    }.padding(20.dp),
                ) {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(10.dp).background(if (active) tokens.success else tokens.warning, CircleShape))
                            Spacer(Modifier.width(8.dp))
                            Text(if (active) "保护正在运行" else "保护当前未运行", color = tokens.textSecondary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                        Spacer(Modifier.height(18.dp))
                        Text(if (controller.preferredVpnMode()) "应用保护" else "模块保护", color = tokens.textSecondary, fontSize = 12.sp)
                        Text(if (active) "正在守护" else "准备就绪", color = tokens.textPrimary, fontSize = 36.sp, lineHeight = 42.sp, fontWeight = FontWeight.Black)
                        Spacer(Modifier.height(20.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Metric(snapshot.ruleCount.toString(), "有效规则", Modifier.weight(1f))
                            Metric(snapshot.blockedQueries.toString(), "累计拦截", Modifier.weight(1f))
                            Metric(snapshot.queries.toString(), "DNS 请求", Modifier.weight(1f))
                        }
                        Spacer(Modifier.height(18.dp))
                        Button(
                            onClick = {
                                if (controller.preferredVpnMode()) {
                                    if (snapshot.vpnRunning) controller.stopVpn()
                                    else {
                                        val permission = controller.prepareVpn()
                                        if (permission == null) controller.startVpn() else launcher.launch(permission)
                                    }
                                    refresh++
                                } else {
                                    scope.launch {
                                        runCatching { controller.toggleModuleProtection(snapshot.moduleEnabled) }
                                        refresh++
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth().height(54.dp),
                            shape = RoundedCornerShape(20.dp),
                        ) {
                            Icon(if (active) Icons.Rounded.Pause else Icons.Rounded.PowerSettingsNew, null)
                            Spacer(Modifier.width(8.dp))
                            Text(if (active) "停止保护" else "开启保护", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
        if (snapshot.message.isNotBlank() && !snapshot.message.contains("尚未安装")) {
            item {
                Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.errorContainer) {
                    Text(snapshot.message, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onErrorContainer, fontSize = 12.sp)
                }
            }
        }
        item { SectionTitle("UNIFIED PROXY", "代理与去广告", "Mihomo · TPROXY · 策略组 · 测速") }
        item {
            ActionGroup(
                listOf(
                    ActionItem(Icons.Rounded.Public, "代理控制台", if (snapshot.proxyRunning) "当前代理正在运行" else "核心、模式、节点与连接") {
                        context.startActivity(Intent(context, ComposeProxyActivity::class.java))
                    },
                    ActionItem(Icons.Rounded.Apps, "应用放行", "按应用决定是否绕过过滤") { },
                    ActionItem(Icons.Rounded.Rule, "规则与订阅", "来源、例外、更新与回滚") { },
                ),
            )
        }
        item { SectionTitle("SYSTEM STATUS", "运行状态", "Root、模块与规则") }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatusMetric(Icons.Rounded.Security, "Root", if (snapshot.rootGranted) "已授权" else "未授权", snapshot.rootGranted, Modifier.weight(1f))
                StatusMetric(Icons.Rounded.Extension, "模块", if (snapshot.installed) snapshot.version else "未安装", snapshot.installed, Modifier.weight(1f))
            }
        }
        item {
            OutlinedButton(onClick = { refresh++ }, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(18.dp)) {
                Icon(Icons.Rounded.Refresh, null); Spacer(Modifier.width(7.dp)); Text("重新检查状态")
            }
        }
    }
}

private data class ActionItem(val icon: androidx.compose.ui.graphics.vector.ImageVector, val title: String, val desc: String, val action: () -> Unit)

@Composable
private fun ActionGroup(items: List<ActionItem>) {
    val tokens = LocalBichenTokens.current
    Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = tokens.cardBackground)) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 5.dp)) {
            items.forEachIndexed { index, item ->
                Row(
                    Modifier.fillMaxWidth().clickable(onClick = item.action).padding(vertical = 13.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Surface(shape = RoundedCornerShape(15.dp), color = tokens.elevatedCardBackground, modifier = Modifier.size(42.dp)) {
                        Box(contentAlignment = Alignment.Center) { Icon(item.icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp)) }
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(item.title, color = tokens.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        Text(item.desc, color = tokens.textSecondary, fontSize = 12.sp, maxLines = 2)
                    }
                    Icon(Icons.Rounded.ChevronRight, null, tint = tokens.textSecondary)
                }
                if (index != items.lastIndex) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .34f))
            }
        }
    }
}

@Composable
private fun Metric(value: String, label: String, modifier: Modifier = Modifier) {
    val tokens = LocalBichenTokens.current
    Column(modifier) {
        Text(value, color = tokens.textPrimary, fontSize = 22.sp, fontWeight = FontWeight.Black, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(label, color = tokens.textSecondary, fontSize = 11.sp)
    }
}

@Composable
private fun StatusMetric(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, value: String, positive: Boolean, modifier: Modifier = Modifier) {
    val tokens = LocalBichenTokens.current
    Card(modifier, shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = tokens.cardBackground)) {
        Column(Modifier.padding(16.dp)) {
            Icon(icon, null, tint = if (positive) tokens.success else tokens.warning)
            Spacer(Modifier.height(12.dp))
            Text(label, color = tokens.textSecondary, fontSize = 11.sp)
            Text(value, color = tokens.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun AppsPage(controller: BichenComposeController) {
    var reload by remember { mutableIntStateOf(0) }
    val apps by produceState(initialValue = emptyList<AppItem>(), reload) { value = runCatching { controller.loadApps() }.getOrElse { emptyList() } }
    var query by rememberSaveable { mutableStateOf("") }
    var showSystem by rememberSaveable { mutableStateOf(false) }
    var selectedOnly by rememberSaveable { mutableStateOf(false) }
    var selected by remember(reload) { mutableStateOf(controller.bypassApps()) }
    val tokens = LocalBichenTokens.current
    val visible = remember(apps, query, showSystem, selectedOnly, selected) {
        apps.filter {
            (!selectedOnly || it.packageName in selected) &&
                (showSystem || !it.system || it.packageName in selected) &&
                (query.isBlank() || it.label.contains(query, true) || it.packageName.contains(query, true))
        }
    }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp, 10.dp, 16.dp, 28.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { PageHeader("APPLICATIONS", "应用放行", "已选择 ${selected.size} 个", Icons.Rounded.Refresh) { reload++; selected = controller.bypassApps() } }
        item {
            OutlinedTextField(value = query, onValueChange = { query = it }, modifier = Modifier.fillMaxWidth(), placeholder = { Text("搜索名称或包名") }, leadingIcon = { Icon(Icons.Rounded.Search, null) }, singleLine = true, shape = RoundedCornerShape(18.dp))
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = showSystem, onClick = { showSystem = !showSystem }, label = { Text("系统应用") })
                FilterChip(selected = selectedOnly, onClick = { selectedOnly = !selectedOnly }, label = { Text("只看已选择") })
                Spacer(Modifier.weight(1f))
                if (DnsVpnService.running) TextButton(onClick = { controller.applyVpnBypass() }) { Text("应用更改") }
            }
        }
        items(visible, key = { it.packageName }) { app ->
            val checked = app.packageName in selected
            Card(
                Modifier.fillMaxWidth().clickable {
                    controller.setBypass(app.packageName, !checked)
                    selected = controller.bypassApps()
                },
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(containerColor = tokens.cardBackground),
            ) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Surface(shape = CircleShape, color = tokens.elevatedCardBackground, modifier = Modifier.size(44.dp)) {
                        Box(contentAlignment = Alignment.Center) { Text(app.label.take(1), fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.primary) }
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(app.label, color = tokens.textPrimary, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(app.packageName, color = tokens.textSecondary, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                    }
                    Switch(checked = checked, onCheckedChange = {
                        controller.setBypass(app.packageName, it)
                        selected = controller.bypassApps()
                    })
                }
            }
        }
    }
}

@Composable
private fun RulesPage(controller: BichenComposeController) {
    var reload by remember { mutableIntStateOf(0) }
    val state by produceState(initialValue = RulesSnapshot(), reload) { value = runCatching { controller.rulesSnapshot() }.getOrElse { RulesSnapshot() } }
    val scope = rememberCoroutineScope()
    var addMode by remember { mutableStateOf<Boolean?>(null) }
    var input by remember { mutableStateOf("") }
    val tokens = LocalBichenTokens.current
    LazyColumn(
        Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp, 10.dp, 16.dp, 28.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { PageHeader("FILTER RULES", "过滤规则", "${state.count} 个有效域名 · ${state.profile}", Icons.Rounded.Refresh) { reload++ } }
        item {
            Button(
                onClick = { scope.launch { runCatching { controller.updateRules() }; reload++ } },
                Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(18.dp),
            ) { Icon(Icons.Rounded.Sync, null); Spacer(Modifier.width(8.dp)); Text("更新已启用订阅", fontWeight = FontWeight.Bold) }
        }
        item { SectionTitle("SOURCES", "订阅来源", "关闭来源不会删除你的手动规则") }
        items(state.sources, key = { it.id }) { source ->
            Card(shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = tokens.cardBackground)) {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(source.name, color = tokens.textPrimary, fontWeight = FontWeight.SemiBold)
                        Text(if (source.count >= 0) "${source.count} 条规则" else "模块快照", color = tokens.textSecondary, fontSize = 11.sp)
                    }
                    Switch(checked = source.enabled, onCheckedChange = { on -> scope.launch { runCatching { controller.setRuleSource(source.id, on) }; reload++ } })
                }
            }
        }
        item { SectionTitle("EXCEPTIONS", "手动例外", "白名单优先，黑名单强制拦截") }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = { addMode = true }, Modifier.weight(1f).height(50.dp), shape = RoundedCornerShape(18.dp)) { Icon(Icons.Rounded.Add, null); Spacer(Modifier.width(5.dp)); Text("添加放行") }
                OutlinedButton(onClick = { addMode = false }, Modifier.weight(1f).height(50.dp), shape = RoundedCornerShape(18.dp)) { Icon(Icons.Rounded.Block, null); Spacer(Modifier.width(5.dp)); Text("添加拦截") }
            }
        }
        if (state.allow.isNotEmpty()) item { RuleListCard("白名单", state.allow, tokens.success) { domain -> scope.launch { runCatching { controller.changeDomain(domain, true, false) }; reload++ } } }
        if (state.block.isNotEmpty()) item { RuleListCard("黑名单", state.block, tokens.danger) { domain -> scope.launch { runCatching { controller.changeDomain(domain, false, false) }; reload++ } } }
    }
    if (addMode != null) {
        AlertDialog(
            onDismissRequest = { addMode = null; input = "" },
            title = { Text(if (addMode == true) "添加放行域名" else "添加拦截域名") },
            text = { OutlinedTextField(value = input, onValueChange = { input = it }, placeholder = { Text("example.com") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)) },
            confirmButton = {
                TextButton(onClick = {
                    val allow = addMode == true
                    scope.launch { runCatching { controller.changeDomain(input, allow, true) }; input = ""; addMode = null; reload++ }
                }) { Text("添加") }
            },
            dismissButton = { TextButton(onClick = { addMode = null; input = "" }) { Text("取消") } },
        )
    }
}

@Composable
private fun RuleListCard(title: String, domains: List<String>, color: Color, onDelete: (String) -> Unit) {
    val tokens = LocalBichenTokens.current
    Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = tokens.cardBackground)) {
        Column(Modifier.padding(16.dp)) {
            Text(title, color = color, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            domains.take(40).forEachIndexed { index, domain ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(domain, Modifier.weight(1f), color = tokens.textPrimary, fontSize = 13.sp)
                    IconButton(onClick = { onDelete(domain) }, modifier = Modifier.size(36.dp)) { Icon(Icons.Rounded.Close, null, tint = tokens.textSecondary, modifier = Modifier.size(18.dp)) }
                }
                if (index != domains.take(40).lastIndex) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(.25f))
            }
            if (domains.size > 40) Text("还有 ${domains.size - 40} 条，后续加入搜索与分页", color = tokens.textSecondary, fontSize = 11.sp)
        }
    }
}

@Composable
private fun ActivityPage(controller: BichenComposeController) {
    var refresh by remember { mutableIntStateOf(0) }
    val requests = remember(refresh) { controller.requestItems() }
    val snapshot by produceState(initialValue = HomeSnapshot(), refresh) { value = runCatching { controller.homeSnapshot() }.getOrElse { HomeSnapshot() } }
    val tokens = LocalBichenTokens.current
    LazyColumn(
        Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp, 10.dp, 16.dp, 28.dp), verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { PageHeader("REQUEST ACTIVITY", "请求活动", "最近 DNS 请求只保存在本机", Icons.Rounded.Refresh) { refresh++ } }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatusMetric(Icons.Rounded.QueryStats, "请求", snapshot.queries.toString(), true, Modifier.weight(1f))
                StatusMetric(Icons.Rounded.Block, "拦截", snapshot.blockedQueries.toString(), true, Modifier.weight(1f))
                StatusMetric(Icons.Rounded.ErrorOutline, "错误", snapshot.errors.toString(), snapshot.errors == 0L, Modifier.weight(1f))
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                SectionTitle("RECENT", "最近记录", if (requests.isEmpty()) "尚无请求记录" else "${requests.size} 条本地记录")
                Spacer(Modifier.weight(1f))
                if (requests.isNotEmpty()) TextButton(onClick = { controller.clearRequests(); refresh++ }) { Text("清空") }
            }
        }
        items(requests, key = { it.domain + it.time + it.reason }) { item ->
            Card(shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = tokens.cardBackground)) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(9.dp).background(if (item.blocked) tokens.danger else tokens.success, CircleShape))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(item.domain, color = tokens.textPrimary, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(item.reason, color = tokens.textSecondary, fontSize = 11.sp, maxLines = 2)
                    }
                    if (item.time.isNotBlank()) Text(item.time.takeLast(8), color = tokens.textSecondary, fontSize = 10.sp)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsSheet(controller: BichenComposeController, onDismiss: () -> Unit, onThemeChanged: () -> Unit) {
    val tokens = LocalBichenTokens.current
    val current = controller.appearance()
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = tokens.cardBackground) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
            Text("设置与诊断", fontSize = 24.sp, fontWeight = FontWeight.Black, color = tokens.textPrimary)
            Text("辟尘 Compose · MIUIX", color = tokens.textSecondary, fontSize = 12.sp)
            Spacer(Modifier.height(18.dp))
            Text("外观", fontWeight = FontWeight.Bold, color = tokens.textPrimary)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("system" to "跟随系统", "light" to "浅色", "dark" to "深色").forEach { (value, label) ->
                    FilterChip(selected = current == value, onClick = { controller.setAppearance(value); onThemeChanged(); onDismiss() }, label = { Text(label) })
                }
            }
            Spacer(Modifier.height(18.dp))
            Text("代理设置、DNS、日志与模块管理将在 Compose 页面中继续迁移；旧 Java UI 不再作为最终界面。", color = tokens.textSecondary, fontSize = 12.sp)
        }
    }
}
