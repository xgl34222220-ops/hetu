package io.github.xgl34222220.bichen

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
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

class CompactMainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            var themeRevision by remember { mutableIntStateOf(0) }
            key(themeRevision) {
                BichenTheme {
                    CompactBichenApp(onThemeChanged = { themeRevision++ })
                }
            }
        }
    }
}

private enum class CompactPage { Home, Apps, Rules, Activity }

@Composable
private fun CompactBichenApp(onThemeChanged: () -> Unit) {
    val context = LocalContext.current
    val controller = remember { BichenComposeController(context) }
    var page by rememberSaveable { mutableStateOf(CompactPage.Home) }
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

    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier.fillMaxSize()
                .then(if (!liquid) Modifier.hazeSource(hazeState) else Modifier)
                .then(if (liquid) Modifier.layerBackdrop(liquidBackdrop) else Modifier),
        ) {
            CompactBackdrop(dark)
            key(page) {
                val enter = remember { Animatable(0f) }
                LaunchedEffect(Unit) { enter.animateTo(1f, spring(dampingRatio = .86f, stiffness = 430f)) }
                Box(
                    Modifier.fillMaxSize().graphicsLayer {
                        alpha = 1f
                        translationY = (1f - enter.value) * 10.dp.toPx()
                    },
                ) {
                    when (page) {
                        CompactPage.Home -> CompactHomePage(
                            controller = controller,
                            onSettings = { showSettings = true },
                            onApps = { page = CompactPage.Apps },
                            onRules = { page = CompactPage.Rules },
                        )
                        CompactPage.Apps -> CompactAppsPage(controller)
                        CompactPage.Rules -> CompactRulesPage(controller)
                        CompactPage.Activity -> CompactActivityPage(controller)
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
                selected = CompactPage.entries.indexOf(page),
                onSelect = { page = CompactPage.entries[it] },
                hazeState = hazeState,
                backdrop = liquidBackdrop.takeIf { liquid },
            )
        }
    }

    if (showSettings) {
        CompactSettingsSheet(controller, { showSettings = false }, onThemeChanged)
    }
}

@Composable
private fun CompactBackdrop(dark: Boolean) {
    val tokens = LocalBichenTokens.current
    val scheme = MaterialTheme.colorScheme
    Box(
        Modifier.fillMaxSize().background(tokens.pageBackground).drawBehind {
            drawRect(
                Brush.radialGradient(
                    listOf(scheme.primary.copy(alpha = if (dark) .09f else .10f), Color.Transparent),
                    center = androidx.compose.ui.geometry.Offset(size.width * .92f, size.height * .02f),
                    radius = size.width * .85f,
                ),
            )
            drawRect(
                Brush.radialGradient(
                    listOf(scheme.secondary.copy(alpha = if (dark) .06f else .07f), Color.Transparent),
                    center = androidx.compose.ui.geometry.Offset(size.width * .04f, size.height * .82f),
                    radius = size.width,
                ),
            )
        },
    )
}

@Composable
private fun CompactTopBar(title: String, actions: @Composable RowScope.() -> Unit = {}) {
    val tokens = LocalBichenTokens.current
    Row(
        Modifier.fillMaxWidth().statusBarsPadding().heightIn(min = 64.dp).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            title,
            Modifier.weight(1f),
            color = tokens.textPrimary,
            fontSize = 26.sp,
            lineHeight = 34.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp), content = actions)
    }
}

@Composable
private fun CompactHeaderAction(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    loading: Boolean = false,
) {
    val tokens = LocalBichenTokens.current
    Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
        Surface(
            modifier = Modifier.size(44.dp),
            shape = CircleShape,
            color = tokens.cardBackground,
            contentColor = MaterialTheme.colorScheme.primary,
            shadowElevation = 1.dp,
        ) {
            IconButton(onClick = onClick, enabled = !loading, modifier = Modifier.fillMaxSize()) {
                if (loading) CircularProgressIndicator(Modifier.size(21.dp), strokeWidth = 2.dp)
                else Icon(icon, description, Modifier.size(21.dp))
            }
        }
    }
}

@Composable
private fun CompactSectionHeading(title: String, subtitle: String? = null) {
    val tokens = LocalBichenTokens.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 2.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(title, color = tokens.textPrimary, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        subtitle?.takeIf { it.isNotBlank() }?.let { Text(it, color = tokens.textSecondary, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun CompactHomePage(
    controller: BichenComposeController,
    onSettings: () -> Unit,
    onApps: () -> Unit,
    onRules: () -> Unit,
) {
    var refresh by remember { mutableIntStateOf(0) }
    val snapshot by produceState(initialValue = HomeSnapshot(), refresh) {
        value = runCatching { controller.homeSnapshot() }.getOrElse { HomeSnapshot(message = it.message ?: "状态读取失败") }
    }
    val counters by produceState(initialValue = controller.dnsCounters()) {
        while (true) {
            value = controller.dnsCounters()
            delay(1000)
        }
    }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == Activity.RESULT_OK) controller.startVpn()
        refresh++
    }
    val vpnMode = controller.preferredVpnMode()
    val active = if (vpnMode) snapshot.vpnRunning else snapshot.moduleEnabled
    val tokens = LocalBichenTokens.current
    val scheme = MaterialTheme.colorScheme
    var detailsExpanded by rememberSaveable { mutableStateOf(false) }
    var showModePicker by rememberSaveable { mutableStateOf(false) }

    fun toggleProtection() {
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
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 124.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item("header") {
            CompactTopBar("辟尘") {
                CompactHeaderAction(Icons.Rounded.Settings, "设置", onSettings)
                CompactHeaderAction(Icons.Rounded.Refresh, "刷新", onClick = { refresh++ })
            }
        }
        item("hero") {
            Surface(shape = RoundedCornerShape(28.dp), color = tokens.cardBackground, shadowElevation = 2.dp) {
                Column(
                    Modifier.fillMaxWidth()
                        .background(Brush.linearGradient(listOf(scheme.primaryContainer.copy(alpha = .46f), tokens.cardBackground)))
                        .padding(22.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(shape = CircleShape, color = tokens.cardBackground.copy(alpha = .72f)) {
                            Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(6.dp).background(if (active) tokens.success else tokens.warning, CircleShape))
                                Spacer(Modifier.width(6.dp))
                                Text(if (active) "去广告已开启" else "去广告未开启", color = tokens.textPrimary, style = MaterialTheme.typography.labelMedium)
                            }
                        }
                        Spacer(Modifier.weight(1f))
                        Text(snapshot.version, color = tokens.textSecondary, style = MaterialTheme.typography.labelMedium)
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            if (vpnMode) "应用保护 · 可统计" else "模块保护 · 不占 VPN",
                            color = tokens.textSecondary,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            if (active) "正在过滤广告" else "准备就绪",
                            color = tokens.textPrimary,
                            fontSize = 24.sp,
                            lineHeight = 32.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    Surface(
                        onClick = { showModePicker = true },
                        shape = RoundedCornerShape(16.dp),
                        color = tokens.elevatedCardBackground.copy(alpha = .84f),
                    ) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(if (vpnMode) Icons.Rounded.QueryStats else Icons.Rounded.Extension, null, tint = scheme.primary, modifier = Modifier.size(19.dp))
                            Spacer(Modifier.width(9.dp))
                            Column(Modifier.weight(1f)) {
                                Text(if (vpnMode) "实时拦截统计" else "Root 模块过滤", color = tokens.textPrimary, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    if (vpnMode) "逐条记录 DNS 请求、拦截和拦截率" else "系统 hosts 过滤；点击可切换到可统计模式",
                                    color = tokens.textSecondary,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            Icon(Icons.Rounded.ChevronRight, null, tint = tokens.textSecondary, modifier = Modifier.size(20.dp))
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CompactMetric("${snapshot.ruleCount}", "有效规则", Modifier.weight(1f))
                        CompactMetric("${counters.blocked}", if (vpnMode) "累计拦截" else "历史拦截", Modifier.weight(1f))
                        CompactMetric("${counters.blockRate}%", if (vpnMode) "拦截率" else "历史拦截率", Modifier.weight(1f))
                    }
                    Text(
                        if (vpnMode) {
                            if (snapshot.vpnRunning) "DNS 请求 ${counters.queries} · 正在实时累计，停止后历史不会清零"
                            else "历史 DNS 请求 ${counters.queries} · 开启去广告后继续实时累计"
                        } else {
                            "模块保护没有逐条 DNS 命中事件，因此不会伪造实时次数；当前显示的是应用保护历史统计。"
                        },
                        color = tokens.textSecondary,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Button(
                        onClick = ::toggleProtection,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp),
                        shape = RoundedCornerShape(18.dp),
                    ) {
                        Icon(if (active) Icons.Rounded.Pause else Icons.Rounded.PowerSettingsNew, null, Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(if (active) "停止去广告" else "开启去广告", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
        if (snapshot.message.isNotBlank() && !snapshot.message.contains("尚未安装")) {
            item("message") {
                Surface(shape = RoundedCornerShape(24.dp), color = scheme.errorContainer) {
                    Text(snapshot.message, Modifier.padding(18.dp), color = scheme.onErrorContainer, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        item("quick") {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                CompactSectionHeading("快捷入口", "代理、规则与应用放行")
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CompactShortcut("代理控制台", if (snapshot.proxyRunning) "运行中 · 节点 · 连接" else "核心 · 节点 · 连接", Icons.Rounded.Public, {
                        context.startActivity(Intent(context, ComposeProxyActivity::class.java))
                    }, Modifier.weight(1f))
                    CompactShortcut("过滤规则", "订阅 · 白名单 · 黑名单", Icons.Rounded.Rule, onRules, Modifier.weight(1f))
                }
                TextButton(onClick = onApps, modifier = Modifier.align(Alignment.End)) { Text("管理应用放行") }
            }
        }
        item("device") {
            Surface(shape = RoundedCornerShape(24.dp), color = tokens.cardBackground) {
                Column(Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.fillMaxWidth().clickable { detailsExpanded = !detailsExpanded }.padding(18.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(Icons.Rounded.Security, null, tint = scheme.primary, modifier = Modifier.size(22.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text("设备与保护状态", style = MaterialTheme.typography.titleSmall, color = tokens.textPrimary)
                            Text(
                                if (snapshot.rootGranted && snapshot.installed) "Root 已授权 · 模块已安装" else "查看 Root、模块与规则状态",
                                style = MaterialTheme.typography.bodySmall,
                                color = tokens.textSecondary,
                            )
                        }
                        Icon(if (detailsExpanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null, tint = tokens.textSecondary)
                    }
                    AnimatedVisibility(detailsExpanded) {
                        Column(Modifier.padding(start = 14.dp, end = 14.dp, bottom = 18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CompactStatusCell(Modifier.weight(1f), "Root", if (snapshot.rootGranted) "已授权" else "未授权", snapshot.rootGranted)
                                CompactStatusCell(Modifier.weight(1f), "模块", if (snapshot.installed) snapshot.version else "未安装", snapshot.installed)
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CompactStatusCell(Modifier.weight(1f), "规则", "${snapshot.ruleCount} 条", snapshot.ruleCount > 0)
                                CompactStatusCell(Modifier.weight(1f), "代理", if (snapshot.proxyRunning) "运行中" else "未运行", snapshot.proxyRunning)
                            }
                        }
                    }
                }
            }
        }
    }

    if (showModePicker) {
        AlertDialog(
            onDismissRequest = { showModePicker = false },
            title = { Text("去广告保护方式") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    ProtectionModeOption(
                        selected = !vpnMode,
                        icon = Icons.Rounded.Extension,
                        title = "模块保护",
                        description = "不占 VPN · 全局 hosts 去广告 · 无法获得逐条命中次数",
                    ) {
                        controller.setProtectionMode("module")
                        showModePicker = false
                        refresh++
                    }
                    ProtectionModeOption(
                        selected = vpnMode,
                        icon = Icons.Rounded.QueryStats,
                        title = "应用保护",
                        description = "本地 DNS VPN · 精确累计请求/拦截 · 支持按应用放行",
                    ) {
                        controller.setProtectionMode("vpn")
                        showModePicker = false
                        refresh++
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showModePicker = false }) { Text("关闭") } },
        )
    }
}

@Composable
private fun ProtectionModeOption(
    selected: Boolean,
    icon: ImageVector,
    title: String,
    description: String,
    onClick: () -> Unit,
) {
    val tokens = LocalBichenTokens.current
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = .55f) else tokens.elevatedCardBackground,
    ) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = RoundedCornerShape(14.dp), color = tokens.cardBackground, modifier = Modifier.size(42.dp)) {
                Box(contentAlignment = Alignment.Center) { Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(21.dp)) }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, color = tokens.textPrimary, style = MaterialTheme.typography.titleSmall)
                Text(description, color = tokens.textSecondary, style = MaterialTheme.typography.bodySmall)
            }
            if (selected) Icon(Icons.Rounded.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun CompactMetric(value: String, label: String, modifier: Modifier = Modifier) {
    val tokens = LocalBichenTokens.current
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(value, color = tokens.textPrimary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(label, color = tokens.textSecondary, fontSize = 11.sp)
    }
}

@Composable
private fun CompactShortcut(title: String, subtitle: String, icon: ImageVector, onClick: () -> Unit, modifier: Modifier) {
    val tokens = LocalBichenTokens.current
    Surface(onClick = onClick, modifier = modifier, shape = RoundedCornerShape(24.dp), color = tokens.cardBackground, shadowElevation = 1.dp) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Surface(shape = RoundedCornerShape(15.dp), color = tokens.elevatedCardBackground) {
                Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) { Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp)) }
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, color = tokens.textPrimary, style = MaterialTheme.typography.titleMedium)
                Text(subtitle, color = tokens.textSecondary, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun CompactStatusCell(modifier: Modifier, title: String, value: String, healthy: Boolean) {
    val tokens = LocalBichenTokens.current
    val accent = if (healthy) tokens.success else MaterialTheme.colorScheme.error
    Row(modifier.padding(horizontal = 7.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).background(accent, CircleShape))
        Spacer(Modifier.width(9.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = tokens.textSecondary, style = MaterialTheme.typography.bodySmall)
            Text(value, color = tokens.textPrimary, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun CompactAppsPage(controller: BichenComposeController) {
    var reload by remember { mutableIntStateOf(0) }
    val apps by produceState(initialValue = emptyList<AppItem>(), reload) { value = runCatching { controller.loadApps() }.getOrElse { emptyList() } }
    var query by rememberSaveable { mutableStateOf("") }
    var showSystem by rememberSaveable { mutableStateOf(false) }
    var selectedOnly by rememberSaveable { mutableStateOf(false) }
    var selected by remember(reload) { mutableStateOf(controller.bypassApps()) }
    val tokens = LocalBichenTokens.current
    val visible = remember(apps, query, showSystem, selectedOnly, selected) {
        apps.filter {
            (!selectedOnly || it.packageName in selected) && (showSystem || !it.system || it.packageName in selected) &&
                (query.isBlank() || it.label.contains(query, true) || it.packageName.contains(query, true))
        }
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 124.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item("header") {
            CompactTopBar("应用放行") {
                CompactHeaderAction(Icons.Rounded.Refresh, "刷新", onClick = { reload++; selected = controller.bypassApps() })
            }
        }
        item("search") {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("搜索名称或包名") },
                leadingIcon = { Icon(Icons.Rounded.Search, null) },
                singleLine = true,
                shape = RoundedCornerShape(18.dp),
            )
        }
        item("filters") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                FilterChip(selected = showSystem, onClick = { showSystem = !showSystem }, label = { Text("系统应用") })
                FilterChip(selected = selectedOnly, onClick = { selectedOnly = !selectedOnly }, label = { Text("已选择") })
                Spacer(Modifier.weight(1f))
                if (DnsVpnService.running) TextButton(onClick = controller::applyVpnBypass) { Text("应用") }
            }
        }
        items(visible, key = { it.packageName }) { app ->
            val checked = app.packageName in selected
            Surface(
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).clickable {
                    controller.setBypass(app.packageName, !checked)
                    selected = controller.bypassApps()
                },
                shape = RoundedCornerShape(24.dp),
                color = tokens.cardBackground,
                shadowElevation = 1.dp,
            ) {
                Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (app.icon != null) {
              androidx.compose.foundation.Image(
                  bitmap = app.icon.asImageBitmap(),
                  contentDescription = null,
                  modifier = Modifier.size(44.dp).clip(RoundedCornerShape(13.dp)),
                  contentScale = ContentScale.Fit,
              )
          } else {
              Surface(shape = RoundedCornerShape(13.dp), color = tokens.elevatedCardBackground, modifier = Modifier.size(44.dp)) {
                  Box(contentAlignment = Alignment.Center) {
                      Icon(Icons.Rounded.Android, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                  }
              }
          }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(app.label, color = tokens.textPrimary, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(app.packageName, color = tokens.textSecondary, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                    }
                    Switch(checked, onCheckedChange = {
                        controller.setBypass(app.packageName, it)
                        selected = controller.bypassApps()
                    })
                }
            }
        }
    }
}

@Composable
private fun CompactRulesPage(controller: BichenComposeController) {
    var reload by remember { mutableIntStateOf(0) }
    val state by produceState(initialValue = RulesSnapshot(), reload) { value = runCatching { controller.rulesSnapshot() }.getOrElse { RulesSnapshot() } }
    val scope = rememberCoroutineScope()
    var addMode by remember { mutableStateOf<Boolean?>(null) }
    var input by remember { mutableStateOf("") }
    val tokens = LocalBichenTokens.current

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 124.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item("header") {
            CompactTopBar("过滤规则") { CompactHeaderAction(Icons.Rounded.Refresh, "刷新", onClick = { reload++ }) }
        }
        item("summary") {
            Surface(shape = RoundedCornerShape(28.dp), color = tokens.cardBackground, shadowElevation = 2.dp) {
                Column(Modifier.fillMaxWidth().padding(22.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text("有效规则", color = tokens.textSecondary, style = MaterialTheme.typography.bodySmall)
                    Text("${state.count}", color = tokens.textPrimary, fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.SemiBold)
                    Text(state.profile, color = tokens.textSecondary, style = MaterialTheme.typography.bodySmall)
                    Button(
                        onClick = { scope.launch { runCatching { controller.updateRules() }; reload++ } },
                        Modifier.fillMaxWidth().heightIn(min = 50.dp),
                        shape = RoundedCornerShape(18.dp),
                    ) {
                        Icon(Icons.Rounded.Sync, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text("更新订阅")
                    }
                }
            }
        }
        item("source-heading") { CompactSectionHeading("订阅来源", "关闭来源不会删除手动规则") }
        items(state.sources, key = { it.id }) { source ->
            Surface(shape = RoundedCornerShape(24.dp), color = tokens.cardBackground, shadowElevation = 1.dp) {
                Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(source.name, color = tokens.textPrimary, style = MaterialTheme.typography.titleSmall)
                        Text(if (source.count >= 0) "${source.count} 条规则" else "模块快照", color = tokens.textSecondary, style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(source.enabled, onCheckedChange = { on -> scope.launch { runCatching { controller.setRuleSource(source.id, on) }; reload++ } })
                }
            }
        }
        item("exceptions") {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                CompactSectionHeading("手动例外", "白名单优先，黑名单强制拦截")
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton({ addMode = true }, Modifier.weight(1f).heightIn(min = 48.dp), shape = RoundedCornerShape(18.dp)) { Icon(Icons.Rounded.Add, null); Spacer(Modifier.width(6.dp)); Text("放行") }
                    OutlinedButton({ addMode = false }, Modifier.weight(1f).heightIn(min = 48.dp), shape = RoundedCornerShape(18.dp)) { Icon(Icons.Rounded.Block, null); Spacer(Modifier.width(6.dp)); Text("拦截") }
                }
            }
        }
        if (state.allow.isNotEmpty()) item("allow") { CompactRuleList("白名单", state.allow, tokens.success) { domain -> scope.launch { runCatching { controller.changeDomain(domain, true, false) }; reload++ } } }
        if (state.block.isNotEmpty()) item("block") { CompactRuleList("黑名单", state.block, tokens.danger) { domain -> scope.launch { runCatching { controller.changeDomain(domain, false, false) }; reload++ } } }
    }

    if (addMode != null) {
        AlertDialog(
            onDismissRequest = { addMode = null; input = "" },
            title = { Text(if (addMode == true) "添加放行域名" else "添加拦截域名") },
            text = { OutlinedTextField(input, { input = it }, placeholder = { Text("example.com") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)) },
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
private fun CompactRuleList(title: String, domains: List<String>, color: Color, onDelete: (String) -> Unit) {
    val tokens = LocalBichenTokens.current
    Surface(shape = RoundedCornerShape(24.dp), color = tokens.cardBackground) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, color = color, style = MaterialTheme.typography.titleSmall)
            domains.take(40).forEach { domain ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(domain, Modifier.weight(1f), color = tokens.textPrimary, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    IconButton(onClick = { onDelete(domain) }, modifier = Modifier.size(36.dp)) { Icon(Icons.Rounded.Close, null, tint = tokens.textSecondary, modifier = Modifier.size(18.dp)) }
                }
            }
            if (domains.size > 40) Text("还有 ${domains.size - 40} 条", color = tokens.textSecondary, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun CompactActivityPage(controller: BichenComposeController) {
    var refresh by remember { mutableIntStateOf(0) }
    val requests = remember(refresh) { controller.requestItems() }
    val counters by produceState(initialValue = controller.dnsCounters(), refresh) {
        while (true) {
            value = controller.dnsCounters()
            delay(1000)
        }
    }
    val tokens = LocalBichenTokens.current
    val vpnMode = controller.preferredVpnMode()

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 124.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item("header") {
            CompactTopBar("请求活动") { CompactHeaderAction(Icons.Rounded.Refresh, "刷新", onClick = { refresh++ }) }
        }
        item("summary") {
            Surface(shape = RoundedCornerShape(24.dp), color = tokens.cardBackground, shadowElevation = 1.dp) {
                Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CompactMetric(counters.queries.toString(), "DNS 请求", Modifier.weight(1f))
                        CompactMetric(counters.blocked.toString(), "累计拦截", Modifier.weight(1f))
                        CompactMetric("${counters.blockRate}%", "拦截率", Modifier.weight(1f))
                    }
                    Text(
                        if (vpnMode) "应用保护统计会每秒刷新；错误 ${counters.errors} 次。"
                        else "当前是模块保护：这里只展示应用保护的历史精确统计，模块 hosts 命中不会伪造计数。",
                        color = tokens.textSecondary,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
        item("recent") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { CompactSectionHeading("最近记录", if (requests.isEmpty()) "尚无可统计 DNS 请求" else "${requests.size} 条本地记录") }
                if (requests.isNotEmpty()) TextButton({ controller.clearRequests(); refresh++ }) { Text("清空记录") }
            }
        }
        items(requests, key = { it.domain + it.time + it.reason }) { item ->
            Surface(shape = RoundedCornerShape(24.dp), color = tokens.cardBackground, shadowElevation = 1.dp) {
                Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).background(if (item.blocked) tokens.danger else tokens.success, CircleShape))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(item.domain, color = tokens.textPrimary, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(item.reason, color = tokens.textSecondary, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    if (item.time.isNotBlank()) Text(item.time.takeLast(8), color = tokens.textSecondary, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CompactSettingsSheet(controller: BichenComposeController, onDismiss: () -> Unit, onThemeChanged: () -> Unit) {
    val tokens = LocalBichenTokens.current
    val current = controller.appearance()
    var mode by remember { mutableStateOf(controller.protectionMode()) }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = tokens.cardBackground) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("设置与诊断", fontSize = 22.sp, lineHeight = 30.sp, fontWeight = FontWeight.SemiBold, color = tokens.textPrimary)
            Text("辟尘 · 洛书 Compact UI", color = tokens.textSecondary, style = MaterialTheme.typography.bodySmall)
            CompactSectionHeading("去广告保护方式", "模块保护不占 VPN；应用保护提供精确累计统计和应用放行")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = mode == "module",
                    onClick = { controller.setProtectionMode("module"); mode = "module" },
                    label = { Text("模块保护") },
                    leadingIcon = { Icon(Icons.Rounded.Extension, null, Modifier.size(18.dp)) },
                )
                FilterChip(
                    selected = mode == "vpn",
                    onClick = { controller.setProtectionMode("vpn"); mode = "vpn" },
                    label = { Text("应用保护") },
                    leadingIcon = { Icon(Icons.Rounded.QueryStats, null, Modifier.size(18.dp)) },
                )
            }
            Text(
                if (mode == "vpn") "开启去广告后会真实累计 DNS 请求、拦截次数和拦截率，停止后历史仍保留。"
                else "模块保护直接通过系统 hosts 拦截，不会收到逐条 DNS 命中事件，因此不显示伪造的实时次数。",
                color = tokens.textSecondary,
                style = MaterialTheme.typography.bodySmall,
            )
            CompactSectionHeading("外观")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("system" to "跟随系统", "light" to "浅色", "dark" to "深色").forEach { (value, label) ->
                    FilterChip(selected = current == value, onClick = { controller.setAppearance(value); onThemeChanged(); onDismiss() }, label = { Text(label) })
                }
            }
            Text("代理、规则、应用放行和活动记录均使用同一套洛书页面层级。", color = tokens.textSecondary, style = MaterialTheme.typography.bodySmall)
        }
    }
}
