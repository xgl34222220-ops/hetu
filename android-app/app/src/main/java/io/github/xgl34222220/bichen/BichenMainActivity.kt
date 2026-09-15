package io.github.xgl34222220.bichen

import android.app.Activity
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.draw.clip
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

class BichenMainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { BichenTheme { BichenMainApp(lifecycleScope) } }
    }
}

private enum class MainPage { Home, Apps, Rules, Activity }

@Composable
private fun BichenMainApp(actionScope: CoroutineScope) {
    val context = LocalContext.current
    val controller = remember { BichenComposeController(context) }
    val haze = rememberHazeState()
    val backdrop = rememberLayerBackdrop()
    val liquid = isRuntimeShaderSupported()
    var page by rememberSaveable { mutableStateOf(MainPage.Home) }
    val dockItems = remember {
        listOf(
            DockItem("首页", Icons.Rounded.Home),
            DockItem("应用", Icons.Rounded.Apps),
            DockItem("规则", Icons.Rounded.Rule),
            DockItem("活动", Icons.Rounded.QueryStats),
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
                MainPage.Home -> HomePage(controller, actionScope)
                MainPage.Apps -> AppsPage(controller)
                MainPage.Rules -> RulesPage(controller, actionScope)
                MainPage.Activity -> ActivityPage(controller)
            }
        }
        BichenGlassDock(
            items = dockItems,
            selected = page.ordinal,
            onSelect = { page = MainPage.entries[it] },
            hazeState = haze,
            backdrop = backdrop.takeIf { liquid },
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

@Composable
private fun HomePage(controller: BichenComposeController, actionScope: CoroutineScope) {
    val context = LocalContext.current
    val tokens = LocalBichenTokens.current
    val scheme = MaterialTheme.colorScheme
    var revision by remember { mutableIntStateOf(0) }
    var snapshot by remember { mutableStateOf(HomeSnapshot()) }
    var busy by remember { mutableStateOf(false) }
    var operationMessage by remember { mutableStateOf("") }

    val vpnPermission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            controller.startVpn()
            revision++
        }
    }

    LaunchedEffect(revision) {
        try {
            snapshot = controller.homeSnapshot()
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            operationMessage = error.message ?: "状态读取失败"
        }
    }
    LaunchedEffect(Unit) {
        while (true) {
            delay(5000)
            try { snapshot = controller.homeSnapshot() }
            catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { }
        }
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 22.dp, end = 22.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item("header") {
            MainHeader("辟尘", "Root 去广告与透明代理", action = { revision++ })
        }
        item("hero") {
            Surface(shape = RoundedCornerShape(30.dp), color = tokens.cardBackground, shadowElevation = 2.dp) {
                Column(Modifier.fillMaxWidth().padding(22.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusPill(
                            if (snapshot.moduleEnabled || snapshot.vpnRunning) "去广告已开启" else "去广告未开启",
                            if (snapshot.moduleEnabled || snapshot.vpnRunning) tokens.success else tokens.warning,
                        )
                        Spacer(Modifier.weight(1f))
                        Text(if (controller.preferredVpnMode()) "DNS VPN" else "模块保护", color = tokens.textSecondary, style = MaterialTheme.typography.labelMedium)
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(
                            if (snapshot.moduleEnabled || snapshot.vpnRunning) "正在保护" else "准备就绪",
                            color = tokens.textPrimary,
                            fontSize = 30.sp,
                            lineHeight = 36.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            if (controller.preferredVpnMode()) "可统计 DNS 命中 · 支持应用放行" else "系统 hosts 过滤 · 不占 VPN",
                            color = tokens.textSecondary,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Metric(snapshot.ruleCount.toString(), "有效规则", Modifier.weight(1f))
                        Metric(snapshot.blockedQueries.toString(), "历史拦截", Modifier.weight(1f))
                        Metric(if (snapshot.queries > 0) "${((snapshot.blockedQueries * 100) / snapshot.queries).coerceIn(0, 100)}%" else "0%", "拦截率", Modifier.weight(1f))
                    }
                    if (operationMessage.isNotBlank()) {
                        Text(operationMessage, color = if (operationMessage.contains("已")) tokens.success else scheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                    Button(
                        onClick = {
                            if (busy) return@Button
                            if (controller.preferredVpnMode()) {
                                if (snapshot.vpnRunning) {
                                    controller.stopVpn(); revision++
                                } else {
                                    val intent = controller.prepareVpn()
                                    if (intent == null) { controller.startVpn(); revision++ } else vpnPermission.launch(intent)
                                }
                            } else {
                                busy = true
                                operationMessage = if (snapshot.moduleEnabled) "正在暂停…" else "正在开启…"
                                actionScope.launch {
                                    try {
                                        operationMessage = controller.toggleModuleProtection(snapshot.moduleEnabled)
                                    } catch (error: Exception) {
                                        operationMessage = error.message ?: "去广告操作失败"
                                    } finally {
                                        revision++
                                        busy = false
                                    }
                                }
                            }
                        },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                        shape = RoundedCornerShape(20.dp),
                    ) {
                        if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        else Icon(Icons.Rounded.PowerSettingsNew, null, Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            when {
                                busy -> "处理中"
                                controller.preferredVpnMode() && snapshot.vpnRunning -> "关闭去广告"
                                controller.preferredVpnMode() -> "开启去广告"
                                snapshot.moduleEnabled -> "暂停去广告"
                                else -> "开启去广告"
                            },
                        )
                    }
                }
            }
        }
        item("quick-title") { SectionTitle("快捷入口", "代理、规则与应用放行") }
        item("quick") {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                QuickCard(
                    icon = Icons.Rounded.Public,
                    title = "代理控制台",
                    subtitle = if (snapshot.proxyRunning) "透明代理运行中" else "核心 · 节点 · 连接 · 测速",
                    status = if (snapshot.proxyRunning) "运行中" else "未运行",
                ) { context.startActivity(android.content.Intent(context, ComposeProxyActivity::class.java)) }
                QuickCard(
                    icon = Icons.Rounded.Security,
                    title = "设备与保护状态",
                    subtitle = "Root ${if (snapshot.rootGranted) "已授权" else "未授权"} · 模块 ${snapshot.version}",
                    status = "${snapshot.ruleCount} 条规则",
                ) { revision++ }
            }
        }
        if (snapshot.message.isNotBlank() && !snapshot.message.contains("尚未安装")) {
            item("status-message") {
                Surface(shape = RoundedCornerShape(22.dp), color = scheme.surfaceContainerHigh) {
                    Text(snapshot.message, Modifier.fillMaxWidth().padding(16.dp), color = tokens.textSecondary, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun AppsPage(controller: BichenComposeController) {
    val tokens = LocalBichenTokens.current
    var revision by remember { mutableIntStateOf(0) }
    var apps by remember { mutableStateOf(emptyList<AppItem>()) }
    var query by rememberSaveable { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    var bypass by remember { mutableStateOf(controller.bypassApps()) }

    LaunchedEffect(revision) {
        loading = true
        try { apps = controller.loadApps() }
        catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { apps = emptyList() }
        loading = false
        bypass = controller.bypassApps()
    }
    val filtered = remember(apps, query) { apps.filter { query.isBlank() || it.label.contains(query, true) || it.packageName.contains(query, true) } }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 22.dp, end = 22.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item("header") { MainHeader("应用放行", "读取本机真实应用与图标", action = { revision++ }) }
        item("search") {
            OutlinedTextField(
                query, { query = it }, Modifier.fillMaxWidth(), singleLine = true,
                placeholder = { Text("搜索应用或包名") }, leadingIcon = { Icon(Icons.Rounded.Search, null) }, shape = RoundedCornerShape(20.dp),
            )
        }
        item("hint") {
            Text("放行列表用于 DNS VPN 模式；应用图标直接读取 PackageManager，不再使用字母占位图。", color = tokens.textSecondary, style = MaterialTheme.typography.bodySmall)
        }
        if (loading) item("loading") { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        items(filtered, key = { it.packageName }) { app ->
            Surface(shape = RoundedCornerShape(22.dp), color = tokens.cardBackground, shadowElevation = 1.dp) {
                Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (app.icon != null) {
                        androidx.compose.foundation.Image(
                            bitmap = app.icon.asImageBitmap(), contentDescription = null,
                            modifier = Modifier.size(46.dp).clip(RoundedCornerShape(13.dp)), contentScale = ContentScale.Fit,
                        )
                    } else {
                        Surface(shape = RoundedCornerShape(13.dp), color = tokens.elevatedCardBackground, modifier = Modifier.size(46.dp)) {
                            Box(contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Android, null, tint = MaterialTheme.colorScheme.primary) }
                        }
                    }
                    Spacer(Modifier.width(13.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(app.label, color = tokens.textPrimary, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(app.packageName, color = tokens.textSecondary, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Switch(
                        checked = app.packageName in bypass,
                        onCheckedChange = { enabled ->
                            controller.setBypass(app.packageName, enabled)
                            bypass = controller.bypassApps()
                            controller.applyVpnBypass()
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun RulesPage(controller: BichenComposeController, actionScope: CoroutineScope) {
    val tokens = LocalBichenTokens.current
    var revision by remember { mutableIntStateOf(0) }
    var state by remember { mutableStateOf(RulesSnapshot()) }
    var message by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(revision) {
        try { state = controller.rulesSnapshot() }
        catch (cancel: CancellationException) { throw cancel }
        catch (error: Exception) { message = error.message ?: "规则读取失败" }
    }

    LazyColumn(
        Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 22.dp, end = 22.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item("header") { MainHeader("过滤规则", "AdAway 通用规则 · 秋风纯广告 · 可扩展订阅", action = { revision++ }) }
        item("summary") {
            Surface(shape = RoundedCornerShape(26.dp), color = tokens.cardBackground, shadowElevation = 1.dp) {
                Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Rule, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("${state.count} 条有效规则", color = tokens.textPrimary, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(state.profile, color = tokens.textSecondary, style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(onClick = {
                        if (busy) return@TextButton
                        busy = true
                        actionScope.launch {
                            try { message = controller.updateRules() }
                            catch (e: Exception) { message = e.message ?: "更新失败" }
                            finally { busy = false; revision++ }
                        }
                    }) { Text(if (busy) "更新中" else "更新") }
                }
            }
        }
        if (message.isNotBlank()) item("message") { Text(message, color = tokens.textSecondary, style = MaterialTheme.typography.bodySmall) }
        item("sources-title") { SectionTitle("规则来源", "开关后立即重建模块规则") }
        items(state.sources, key = { it.id }) { source ->
            Surface(shape = RoundedCornerShape(22.dp), color = tokens.cardBackground, shadowElevation = 1.dp) {
                Row(Modifier.fillMaxWidth().padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
                    Surface(shape = RoundedCornerShape(13.dp), color = tokens.elevatedCardBackground, modifier = Modifier.size(44.dp)) {
                        Box(contentAlignment = Alignment.Center) { Icon(Icons.Rounded.FilterAlt, null, tint = MaterialTheme.colorScheme.primary) }
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(source.name, color = tokens.textPrimary, style = MaterialTheme.typography.titleSmall)
                        Text("${source.count} 条", color = tokens.textSecondary, style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(source.enabled, onCheckedChange = { enabled ->
                        actionScope.launch {
                            try { controller.setRuleSource(source.id, enabled) }
                            catch (e: Exception) { message = e.message ?: "规则切换失败" }
                            finally { revision++ }
                        }
                    })
                }
            }
        }
        if (state.sources.isEmpty()) {
            item("fallback-source-names") {
                Text("AdAway 通用规则\n秋风纯广告", color = tokens.textSecondary, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun ActivityPage(controller: BichenComposeController) {
    val tokens = LocalBichenTokens.current
    var revision by remember { mutableIntStateOf(0) }
    val requests = remember(revision) { controller.requestItems() }
    LazyColumn(
        Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 22.dp, end = 22.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item("header") { MainHeader("请求活动", "DNS 统计模式下的本机请求记录", action = { revision++ }) }
        item("clear") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { controller.clearRequests(); revision++ }) { Text("清空记录") }
            }
        }
        if (requests.isEmpty()) item("empty") {
            Surface(shape = RoundedCornerShape(24.dp), color = tokens.cardBackground) {
                Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text("暂无请求记录", color = tokens.textPrimary, style = MaterialTheme.typography.titleSmall)
                    Text("模块 hosts 模式不会伪造逐条 DNS 命中；切换到 DNS VPN 后可查看实时统计。", color = tokens.textSecondary, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        items(requests) { item ->
            Surface(shape = RoundedCornerShape(20.dp), color = tokens.cardBackground, shadowElevation = 1.dp) {
                Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (item.blocked) Icons.Rounded.Block else Icons.Rounded.Language, null, tint = if (item.blocked) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(item.domain, color = tokens.textPrimary, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(item.reason, color = tokens.textSecondary, style = MaterialTheme.typography.bodySmall)
                    }
                    if (item.time.isNotBlank()) Text(item.time, color = tokens.textSecondary, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
private fun MainHeader(title: String, subtitle: String, action: (() -> Unit)? = null) {
    val tokens = LocalBichenTokens.current
    Row(
        Modifier.fillMaxWidth().statusBarsPadding().padding(top = 16.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, color = tokens.textPrimary, fontSize = 30.sp, lineHeight = 36.sp, fontWeight = FontWeight.Bold)
            Text(subtitle, color = tokens.textSecondary, style = MaterialTheme.typography.bodySmall)
        }
        if (action != null) {
            Surface(shape = CircleShape, color = tokens.cardBackground, shadowElevation = 1.dp, modifier = Modifier.size(48.dp)) {
                IconButton(onClick = action) { Icon(Icons.Rounded.Refresh, "刷新", tint = MaterialTheme.colorScheme.primary) }
            }
        }
    }
}

@Composable
private fun StatusPill(text: String, color: androidx.compose.ui.graphics.Color) {
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Row(Modifier.padding(horizontal = 11.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(7.dp).background(color, CircleShape))
            Spacer(Modifier.width(7.dp))
            Text(text, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun Metric(value: String, label: String, modifier: Modifier = Modifier) {
    val tokens = LocalBichenTokens.current
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(value, color = tokens.textPrimary, fontSize = 21.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        Text(label, color = tokens.textSecondary, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun SectionTitle(title: String, subtitle: String) {
    val tokens = LocalBichenTokens.current
    Column(Modifier.padding(horizontal = 2.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, color = tokens.textPrimary, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(subtitle, color = tokens.textSecondary, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun QuickCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    status: String,
    onClick: () -> Unit,
) {
    val tokens = LocalBichenTokens.current
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick), shape = RoundedCornerShape(24.dp),
        color = tokens.cardBackground, shadowElevation = 1.dp,
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = RoundedCornerShape(15.dp), color = tokens.elevatedCardBackground, modifier = Modifier.size(46.dp)) {
                Box(contentAlignment = Alignment.Center) { Icon(icon, null, tint = MaterialTheme.colorScheme.primary) }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, color = tokens.textPrimary, style = MaterialTheme.typography.titleSmall)
                Text(subtitle, color = tokens.textSecondary, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(status, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.width(4.dp))
            Icon(Icons.Rounded.ChevronRight, null, tint = tokens.textSecondary, modifier = Modifier.size(20.dp))
        }
    }
}
