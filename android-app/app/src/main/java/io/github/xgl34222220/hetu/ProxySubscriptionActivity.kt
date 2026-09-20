package io.github.xgl34222220.hetu

import io.github.xgl34222220.hetu.ui.CrystalSurface as Surface
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.OutputTransformation
import androidx.compose.foundation.text.input.TextFieldBuffer
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.viewinterop.AndroidView
import io.github.rosemoe.sora.event.ContentChangeEvent
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.subscribeAlways
import io.github.rosemoe.sora.widget.schemes.SchemeDarcula
import io.github.rosemoe.sora.widget.schemes.SchemeGitHub
import io.github.xgl34222220.hetu.ui.*
import io.github.xgl34222220.hetu.ui.HetuTheme
import io.github.xgl34222220.hetu.ui.LocalHetuTokens
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class ProxySubscriptionActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            HetuTheme {
                ProxySubscriptionScreen(onBack = { finish() })
            }
        }
    }
}

private fun importedConfigName(context: android.content.Context, uri: Uri): String {
    var name = ""
    runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) name = cursor.getString(index).orEmpty()
            }
        }
    }
    if (name.isBlank()) name = uri.lastPathSegment?.substringAfterLast('/') ?: "imported-config"
    name = name.replace('/', '_').replace('\\', '_').trim().ifBlank { "imported-config" }
    if (!name.endsWith(".yaml", ignoreCase = true) && !name.endsWith(".yml", ignoreCase = true)) {
        name += ".yaml"
    }
    return name.take(120)
}

private object YamlSyntaxHighlightOutputTransformation : OutputTransformation {
    private val keyRegex = Regex("(?m)^[\\t -]*([A-Za-z0-9_.-]+)(?=\\s*:)")
    private val scalarRegex = Regex("(?<![A-Za-z0-9_.-])(?:true|false|null|-?\\d+(?:\\.\\d+)?)(?![A-Za-z0-9_.-])", RegexOption.IGNORE_CASE)
    private val commentRegex = Regex("(?m)#.*$")

    override fun TextFieldBuffer.transformOutput() {
        val source = asCharSequence().toString()
        keyRegex.findAll(source).forEach { match ->
            val group = match.groups[1] ?: return@forEach
            addStyle(
                SpanStyle(color = Color(0xFF0E7490), fontWeight = FontWeight.SemiBold),
                group.range.first,
                group.range.last + 1,
            )
        }
        scalarRegex.findAll(source).forEach { match ->
            addStyle(
                SpanStyle(color = Color(0xFF6366F1), fontWeight = FontWeight.Medium),
                match.range.first,
                match.range.last + 1,
            )
        }
        commentRegex.findAll(source).forEach { match ->
            addStyle(
                SpanStyle(color = Color(0xFF64748B)),
                match.range.first,
                match.range.last + 1,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProxySubscriptionScreen(onBack: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val controller = remember { ProxyComposeController(context) }
    val dashboardRepo = remember { ProxyDashboardRepository(context) }
    val scope = rememberCoroutineScope()
    val tokens = LocalHetuTokens.current
    val scheme = MaterialTheme.colorScheme
    val dark = scheme.background.luminance() < .5f
    val haptic = LocalHapticFeedback.current

    var revision by remember { mutableIntStateOf(0) }
    var subscriptions by remember { mutableStateOf(emptyList<ProxySubscriptionUi>()) }
    var configLibrary by remember { mutableStateOf(emptyList<ProxyConfigUi>()) }
    var configName by remember { mutableStateOf("加载中…") }
    var liveProviders by remember { mutableStateOf<Map<String, DashboardProviderUi>>(emptyMap()) }
    var loading by remember { mutableStateOf(false) }
    var pullRefreshing by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }

    var editSubscription by remember { mutableStateOf<ProxySubscriptionUi?>(null) }
    var addingSubscription by remember { mutableStateOf(false) }
    var editorName by remember { mutableStateOf("") }
    var editorUrl by remember { mutableStateOf("") }
    var editorError by remember { mutableStateOf("") }
    var savingSubscription by remember { mutableStateOf(false) }

    var yamlOpen by remember { mutableStateOf(false) }
    var yamlText by remember { mutableStateOf("") }
    var yamlError by remember { mutableStateOf("") }
    var yamlLoading by remember { mutableStateOf(false) }
    var yamlSaving by remember { mutableStateOf(false) }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val displayName = importedConfigName(context, uri)
        loading = true
        scope.launch {
            runCatching { controller.importConfig(uri, displayName) }
                .onSuccess { actualName ->
                    message = "已导入并选中 $actualName；原配置未覆盖"
                    revision++
                }
                .onFailure { message = it.message ?: "导入配置失败" }
            loading = false
        }
    }

    fun pullRefreshAll() {
        if (pullRefreshing) return
        scope.launch {
            pullRefreshing = true
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            try {
                val freshProviders = runCatching { dashboardRepo.refreshSubscriptions() }.getOrDefault(emptyList())
                val overview = controller.configOverview()
                val library = controller.configLibrary()
                configName = overview.first
                subscriptions = overview.second
                configLibrary = library
                liveProviders = if (freshProviders.isNotEmpty()) freshProviders.associateBy { it.name }
                    else runCatching { dashboardRepo.providers() }.getOrDefault(emptyList()).associateBy { it.name }
                message = "订阅、用量与配置状态已刷新"
            } catch (error: Exception) {
                message = "刷新失败：" + (error.message ?: "未知错误")
            } finally {
                pullRefreshing = false
            }
        }
    }

    LaunchedEffect(revision) {
        loading = true
        runCatching {
            val overview = controller.configOverview()
            val library = controller.configLibrary()
            val providerMap = runCatching { dashboardRepo.providers() }
                .getOrDefault(emptyList())
                .associateBy { it.name }
            Triple(overview, library, providerMap)
        }
            .onSuccess { result ->
                val overview = result.first
                configName = overview.first
                subscriptions = overview.second
                configLibrary = result.second
                liveProviders = result.third
                if (message.startsWith("读取订阅失败") || message.startsWith("读取配置失败")) message = ""
            }
            .onFailure {
                configName = "配置读取失败"
                subscriptions = emptyList()
                configLibrary = emptyList()
                message = "读取配置失败：" + (it.message ?: "未知错误")
            }
        loading = false
    }

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
    ) {
        PullToRefreshBox(
            isRefreshing = pullRefreshing,
            onRefresh = ::pullRefreshAll,
            modifier = Modifier.fillMaxSize(),
        ) {
        LazyColumn(
            Modifier.fillMaxSize().statusBarsPadding(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = hetuContentBottomPadding()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item("header") {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Surface(
                        modifier = Modifier.size(44.dp),
                        shape = CircleShape,
                        color = tokens.cardBackground,
                        contentColor = scheme.primary,
                        shadowElevation = 1.dp,
                    ) {
                        IconButton(onClick = onBack, modifier = Modifier.fillMaxSize()) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回", Modifier.size(21.dp))
                        }
                    }
                    Column(Modifier.weight(1f)) {
                        Text("订阅与配置", color = tokens.textPrimary, fontSize = 26.sp, lineHeight = 34.sp, fontWeight = FontWeight.Bold)
                        Text(configName, color = tokens.textSecondary, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                    }
                    Surface(
                        modifier = Modifier.size(44.dp),
                        shape = CircleShape,
                        color = tokens.cardBackground,
                        contentColor = scheme.primary,
                        shadowElevation = 1.dp,
                    ) {
                        IconButton(onClick = ::pullRefreshAll, enabled = !loading && !pullRefreshing, modifier = Modifier.fillMaxSize()) {
                            if (loading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            else Icon(Icons.Rounded.Refresh, "刷新", Modifier.size(21.dp))
                        }
                    }
                }
            }

            item("summary") {
                val configured = subscriptions.count { !it.placeholder }
                val live = liveProviders.values.filter { it.hasSubscriptionInfo && it.total > 0L }
                val liveUsed = live.sumOf { it.used }
                val liveTotal = live.sumOf { it.total }
                val liveRatio = if (liveTotal > 0L) (liveUsed.toDouble() / liveTotal.toDouble()).toFloat().coerceIn(0f, 1f) else 0f
                val liveNodeCount = live.sumOf { it.nodes.size }
                Surface(
                    shape = RoundedCornerShape(24.dp),
                    color = tokens.cardBackground,
                    border = BorderStroke(.7.dp, if (dark) tokens.outline.copy(alpha = .34f) else Color(0xFFE2E8F0).copy(alpha = .72f)),
                    shadowElevation = 1.dp,
                ) {
                    Column(Modifier.fillMaxWidth().padding(17.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier.size(42.dp).background(scheme.primary.copy(alpha = .09f), RoundedCornerShape(14.dp)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(Icons.Rounded.CloudSync, null, tint = scheme.primary, modifier = Modifier.size(21.dp))
                            }
                            Spacer(Modifier.width(11.dp))
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text("订阅与配置", color = tokens.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold)
                                Text("${subscriptions.size - configured} 个待填写 · ${configLibrary.size} 份本地配置", color = tokens.textSecondary, fontSize = 11.sp)
                            }
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            SubscriptionMetric("已配置", configured.toString(), Modifier.weight(1f))
                            SubscriptionMetric("节点", liveNodeCount.toString(), Modifier.weight(1f))
                            SubscriptionMetric(
                                "剩余",
                                if (liveTotal > 0L) "${((1f - liveRatio) * 100f).toInt()}%" else "—",
                                Modifier.weight(1f),
                                accent = liveTotal > 0L,
                            )
                        }
                        if (liveTotal > 0L) {
                            Box(Modifier.fillMaxWidth().height(6.dp).background(Color(0xFFF1F5F9), CircleShape)) {
                                Box(
                                    Modifier.fillMaxWidth(liveRatio.coerceIn(.001f, 1f)).fillMaxHeight()
                                        .background(Brush.horizontalGradient(listOf(Color(0xFF2563EB), Color(0xFF22D3EE))), CircleShape),
                                )
                            }
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text("${subscriptionBytes(liveUsed)} / ${subscriptionBytes(liveTotal)}", color = tokens.textPrimary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.SansSerif)
                                Spacer(Modifier.weight(1f))
                                Text("$liveNodeCount 个节点", color = tokens.textSecondary, fontSize = 11.sp)
                            }
                        } else {
                            Text("运行中的 Mihomo 暂未上报订阅流量；配置管理功能仍可正常使用。", color = tokens.textSecondary, fontSize = 11.sp, lineHeight = 16.sp)
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            LiquidPill("导入配置", Icons.Rounded.FileOpen,
                                { importLauncher.launch(arrayOf("*/*")) }, Modifier.weight(1f))
                            Button(
                                onClick = {
                                    addingSubscription = true
                                    editSubscription = null
                                    editorName = ""
                                    editorUrl = ""
                                    editorError = ""
                                },
                                modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                                shape = CircleShape,
                            ) {
                                Icon(Icons.Rounded.Add, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("添加订阅", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }

            item("config-library-heading") {
                Column(Modifier.padding(horizontal = 2.dp, vertical = 2.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("配置库", color = tokens.textPrimary, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text("可保存多份 YAML，点一下立即切换；同名导入会自动保留为副本", color = tokens.textSecondary, style = MaterialTheme.typography.bodySmall)
                }
            }

            items(configLibrary, key = { "config-" + it.name }) { config ->
                val cardShape = RoundedCornerShape(20.dp)
                Box(
                    Modifier.fillMaxWidth()
                        .crystalMaterial(cardShape, selection = config.selected,
                            depth = if (config.selected) CrystalDepth.Card else CrystalDepth.Sunken)
                        .clickable(enabled = !loading) {
                            if (!config.selected) {
                                loading = true
                                scope.launch {
                                    runCatching { controller.selectConfig(config.name) }
                                        .onSuccess { message = "已切换到 ${config.name}"; revision++ }
                                        .onFailure { message = it.message ?: "切换配置失败" }
                                    loading = false
                                }
                            }
                        }
                        .padding(horizontal = 15.dp, vertical = 13.dp),
                ) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        LiquidConfigIndicator(config.selected)
                        Spacer(Modifier.width(11.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(config.name, color = tokens.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                when {
                                    config.selected -> "当前使用"
                                    config.bundled -> "河图内置模板 · 点击切换"
                                    else -> "自定义配置 · 点击切换生效"
                                },
                                color = if (config.selected) scheme.primary else tokens.textSecondary,
                                fontSize = 11.sp,
                                maxLines = 1,
                            )
                        }
                        if (!config.bundled) {
                            IconButton(
                                onClick = {
                                    if (!loading) {
                                        loading = true
                                        scope.launch {
                                            runCatching { controller.deleteConfig(config.name) }
                                                .onSuccess { message = "已删除 ${config.name}"; revision++ }
                                                .onFailure { message = it.message ?: "删除配置失败" }
                                            loading = false
                                        }
                                    }
                                },
                                enabled = !loading,
                                modifier = Modifier.size(34.dp),
                            ) {
                                Icon(Icons.Rounded.DeleteOutline, "删除配置", tint = Color(0xFFE11D48), modifier = Modifier.size(18.dp))
                            }
                        } else if (!config.selected) {
                            Text("切换生效", color = tokens.textMuted, fontSize = 10.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                }
            }

            if (message.isNotBlank()) {
                item("message") {
                    Surface(shape = RoundedCornerShape(20.dp), color = scheme.errorContainer) {
                        Text(message, Modifier.fillMaxWidth().padding(16.dp), color = scheme.onErrorContainer, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            item("subscription-heading") {
                Column(Modifier.padding(horizontal = 2.dp, vertical = 2.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("订阅列表", color = tokens.textPrimary, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text("点卡片可填写或修改订阅地址", color = tokens.textSecondary, style = MaterialTheme.typography.bodySmall)
                }
            }

            if (!loading && subscriptions.isEmpty()) {
                item("empty") {
                    Surface(shape = RoundedCornerShape(24.dp), color = tokens.cardBackground) {
                        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("当前配置没有 proxy-providers", color = tokens.textPrimary, style = MaterialTheme.typography.titleSmall)
                            Text("可以直接使用下方 YAML 编辑器修改整份配置。", color = tokens.textSecondary, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }

            items(subscriptions, key = { "sub-${it.name}" }) { item ->
                val provider = liveProviders[item.name] ?: liveProviders.entries.firstOrNull { it.key.equals(item.name, true) }?.value
                InstrumentSubscriptionTicket(item.name, provider, subscriptionHost(item), item.placeholder, onEdit = {
                    addingSubscription = false
                    editSubscription = item
                    editorName = item.name
                    editorUrl = if (item.placeholder) "" else item.url
                    editorError = ""
                })
            }

            item("yaml-heading") {
                Column(Modifier.padding(horizontal = 2.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("高级编辑", color = tokens.textPrimary, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text("需要修改策略组、DNS、规则或其他 Mihomo 字段时直接编辑 YAML", color = tokens.textSecondary, style = MaterialTheme.typography.bodySmall)
                }
            }
            item("yaml") {
                val yamlCardShape = RoundedCornerShape(24.dp)
                val yamlCardBrush = if (dark) {
                    Brush.verticalGradient(listOf(Color(0xFF1B2431).copy(alpha = .88f), Color(0xFF151D29).copy(alpha = .82f)))
                } else {
                    Brush.verticalGradient(listOf(Color.White.copy(alpha = .94f), Color(0xFFF8FAFE).copy(alpha = .85f)))
                }
                Box(
                    Modifier.fillMaxWidth()
                        .shadow(7.dp, yamlCardShape, clip = false, ambientColor = Color(0xFF0F172A).copy(alpha = .025f), spotColor = Color(0xFF0F172A).copy(alpha = .045f))
                        .background(yamlCardBrush, yamlCardShape)
                        .border(.8.dp, if (dark) Color.White.copy(alpha = .10f) else Color.White.copy(alpha = .78f), yamlCardShape)
                        .clip(yamlCardShape),
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier.size(44.dp)
                                .background(scheme.primary.copy(alpha = .08f), RoundedCornerShape(15.dp))
                                .border(.6.dp, scheme.primary.copy(alpha = .10f), RoundedCornerShape(15.dp)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(Icons.Rounded.Code, null, tint = scheme.primary, modifier = Modifier.size(22.dp))
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("编辑当前 YAML", color = tokens.textPrimary, style = MaterialTheme.typography.titleSmall)
                            Text("保存后在下次启动/重启代理时生效", color = tokens.textSecondary, style = MaterialTheme.typography.bodySmall)
                        }
                        TextButton(onClick = {
                            yamlError = ""
                            yamlLoading = true
                            scope.launch {
                                runCatching { controller.configText() }
                                    .onSuccess { yamlText = it; yamlOpen = true }
                                    .onFailure { message = it.message ?: "读取配置失败" }
                                yamlLoading = false
                            }
                        }, enabled = !yamlLoading) {
                            if (yamlLoading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text("打开")
                        }
                    }
                }
            }
        }
        }
    }

    if (addingSubscription || editSubscription != null) {
        val existing = editSubscription
        ModalBottomSheet(
            onDismissRequest = { if (!savingSubscription) { addingSubscription = false; editSubscription = null } },
            containerColor = tokens.elevatedCardBackground,
            contentColor = tokens.textPrimary,
            tonalElevation = 0.dp,
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            dragHandle = {
                Box(
                    Modifier.padding(top = 10.dp, bottom = 7.dp)
                        .size(width = 36.dp, height = 4.dp)
                        .background(tokens.textMuted.copy(alpha = .40f), CircleShape),
                )
            },
        ) {
            Column(
                Modifier.fillMaxWidth().navigationBarsPadding().imePadding()
                    .padding(start = 18.dp, end = 18.dp, bottom = 18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    if (existing == null) "添加订阅" else "编辑订阅",
                    color = tokens.textPrimary,
                    fontSize = 21.sp,
                    lineHeight = 27.sp,
                    fontWeight = FontWeight.ExtraBold,
                )
                Text(
                    "订阅链接仅保存在河图本机私有配置中",
                    color = tokens.textSecondary,
                    fontSize = 11.sp,
                )
                OutlinedTextField(
                    value = editorName,
                    onValueChange = { if (existing == null) editorName = it },
                    enabled = existing == null && !savingSubscription,
                    label = { Text("订阅名称") },
                    placeholder = { Text("例如：机场一") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                    shape = RoundedCornerShape(16.dp),
                )
                OutlinedTextField(
                    value = editorUrl,
                    onValueChange = { editorUrl = it; editorError = "" },
                    enabled = !savingSubscription,
                    label = { Text("订阅链接") },
                    placeholder = { Text("https://…") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                    shape = RoundedCornerShape(16.dp),
                )
                if (editorError.isNotBlank()) {
                    Text(editorError, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                if (existing != null && subscriptions.size > 1) {
                    TextButton(
                        onClick = {
                            savingSubscription = true
                            scope.launch {
                                runCatching { controller.deleteSubscription(existing.name) }
                                    .onSuccess {
                                        addingSubscription = false
                                        editSubscription = null
                                        revision++
                                        message = "已删除 ${existing.name}；重启代理后生效"
                                    }
                                    .onFailure { editorError = it.message ?: "删除失败" }
                                savingSubscription = false
                            }
                        },
                        enabled = !savingSubscription,
                    ) {
                        Icon(Icons.Rounded.DeleteOutline, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("删除这个订阅")
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    FilledTonalButton(
                        onClick = { addingSubscription = false; editSubscription = null },
                        enabled = !savingSubscription,
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                        shape = CircleShape,
                    ) {
                        Text("取消", fontWeight = FontWeight.Bold)
                    }
                    Button(
                        onClick = {
                            savingSubscription = true
                            scope.launch {
                                val result = if (existing == null) runCatching { controller.addSubscription(editorName, editorUrl) }
                                else runCatching { controller.updateSubscription(existing.name, editorUrl) }
                                result.onSuccess {
                                    addingSubscription = false
                                    editSubscription = null
                                    revision++
                                    message = "订阅已保存；重启代理后生效"
                                }.onFailure { editorError = it.message ?: "保存失败" }
                                savingSubscription = false
                            }
                        },
                        enabled = !savingSubscription,
                        modifier = Modifier.weight(1.35f).heightIn(min = 48.dp),
                        shape = CircleShape,
                    ) {
                        if (savingSubscription) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White)
                        } else {
                            Text("保存", fontWeight = FontWeight.Bold)
                        }
                    }
                }
                Spacer(Modifier.height(2.dp))
            }
        }
    }

    if (yamlOpen) {
        Dialog(
            onDismissRequest = { if (!yamlSaving) yamlOpen = false },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            var yamlEditor by remember { mutableStateOf<CodeEditor?>(null) }
            var yamlLineCount by remember { mutableIntStateOf(maxOf(1, yamlText.count { it == '\n' } + 1)) }
            var yamlCanUndo by remember { mutableStateOf(false) }
            var yamlCanRedo by remember { mutableStateOf(false) }
            var outlineOpen by remember { mutableStateOf(false) }
            var outlineItems by remember { mutableStateOf(yamlOutlineItems(yamlText)) }
            val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0

            fun currentYamlText(): String = yamlEditor?.text?.toString() ?: yamlText

            fun insertYamlText(snippet: String) {
                yamlEditor?.let { editor ->
                    editor.insertText(snippet, snippet.length)
                    editor.requestFocus()
                    editor.ensureSelectionVisible()
                }
            }

            fun jumpToYamlLine(line: Int) {
                yamlEditor?.let { editor ->
                    val target = line.coerceIn(0, (editor.text.lineCount - 1).coerceAtLeast(0))
                    editor.setSelection(target, 0)
                    editor.ensurePositionVisible(target, 0)
                    editor.requestFocus()
                }
            }

            fun saveYaml() {
                if (yamlSaving) return
                val currentText = currentYamlText()
                yamlLocalLint(currentText)?.let { issue ->
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    yamlError = "第 ${issue.line} 行：${issue.message}"
                    jumpToYamlLine(issue.line - 1)
                    return
                }
                yamlSaving = true
                scope.launch {
                    val result = runCatching { controller.saveConfigText(currentText) }
                    result.onSuccess {
                        yamlOpen = false
                        revision++
                        message = "YAML 已保存；重启代理后生效"
                    }
                    result.exceptionOrNull()?.let { error ->
                        yamlError = error.message ?: "保存失败"
                        yamlErrorLine(yamlError)?.let { line -> jumpToYamlLine(line - 1) }
                    }
                    yamlSaving = false
                }
            }

            Surface(modifier = Modifier.fillMaxSize(), color = tokens.pageBackground) {
                Column(Modifier.fillMaxSize().imePadding()) {
                    Row(
                        Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 10.dp, vertical = 7.dp).height(48.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = { if (!yamlSaving) yamlOpen = false }, enabled = !yamlSaving) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回")
                        }
                        Column(Modifier.weight(1f)) {
                            Text(configName, color = tokens.textPrimary, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                            Text("$yamlLineCount 行 · 长行可横向滚动", color = tokens.textSecondary, fontSize = 12.sp)
                        }
                        IconButton(
                            onClick = {
                                outlineItems = yamlOutlineItems(currentYamlText())
                                outlineOpen = true
                            },
                            enabled = !yamlSaving,
                        ) {
                            Icon(Icons.Rounded.FormatListBulleted, "语法大纲", tint = scheme.primary)
                        }
                        TextButton(onClick = ::saveYaml, enabled = !yamlSaving) {
                            if (yamlSaving) CircularProgressIndicator(Modifier.size(17.dp), strokeWidth = 2.dp)
                            else Text("保存", color = scheme.primary, fontWeight = FontWeight.Bold)
                        }
                    }

                    Surface(
                        modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 12.dp, vertical = 7.dp),
                        shape = RoundedCornerShape(18.dp),
                        color = if (dark) Color(0xFF101722) else Color(0xFFF8FAFC),
                        border = BorderStroke(.8.dp, if (dark) tokens.outline.copy(alpha = .44f) else Color(0xFFE2E8F0)),
                        tonalElevation = 0.dp,
                    ) {
                        Box(Modifier.fillMaxSize()) {
                            AndroidView(
                                factory = { viewContext ->
                                    CodeEditor(viewContext).apply {
                                        setEditorLanguage(HetuYamlLanguage())
                                        setText(yamlText)
                                        typefaceText = Typeface.MONOSPACE
                                        setTextSize(13f)
                                        setLineNumberEnabled(true)
                                        setWordwrap(false)
                                        setTabWidth(2)
                                        setBlockLineEnabled(true)
                                        setBlockLineWidth(.5f)
                                        setHighlightCurrentLine(true)
                                        nonPrintablePaintingFlags =
                                            CodeEditor.FLAG_DRAW_WHITESPACE_LEADING or
                                                CodeEditor.FLAG_DRAW_LINE_SEPARATOR or
                                                CodeEditor.FLAG_DRAW_WHITESPACE_IN_SELECTION
                                        colorScheme = HetuYamlLanguage.colors(if (dark) SchemeDarcula() else SchemeGitHub(), dark)
                                        subscribeAlways<ContentChangeEvent> {
                                            yamlLineCount = text.lineCount
                                            yamlCanUndo = canUndo()
                                            yamlCanRedo = canRedo()
                                            if (yamlError.isNotBlank()) yamlError = ""
                                        }
                                        yamlLineCount = text.lineCount
                                        yamlCanUndo = canUndo()
                                        yamlCanRedo = canRedo()
                                        yamlEditor = this
                                    }
                                },
                                modifier = Modifier.fillMaxSize(),
                                update = { editor ->
                                    yamlEditor = editor
                                },
                                onRelease = { editor ->
                                    if (yamlEditor === editor) yamlEditor = null
                                    editor.release()
                                },
                            )

                        }
                    }

                    androidx.compose.animation.AnimatedVisibility(
                        visible = yamlError.isNotBlank(),
                        enter = androidx.compose.animation.slideInVertically(
                            animationSpec = androidx.compose.animation.core.tween(180),
                        ) { it / 2 } + androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(140)),
                        exit = androidx.compose.animation.slideOutVertically(
                            animationSpec = androidx.compose.animation.core.tween(140),
                        ) { it / 2 } + androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(110)),
                    ) {
                        HetuTaskFeedback(yamlError, error = true, busy = false,
                            modifier = Modifier.padding(horizontal = 12.dp))

                    }

                    androidx.compose.animation.AnimatedVisibility(
                        visible = imeVisible,
                        enter = androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(130)) +
                            androidx.compose.animation.slideInVertically(androidx.compose.animation.core.tween(170)) { it / 2 },
                        exit = androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(100)),
                    ) {
                        Surface(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 5.dp),
                            shape = RoundedCornerShape(18.dp),
                            color = if (dark) tokens.elevatedCardBackground else Color(0xFFF1F5F9),
                            border = BorderStroke(.7.dp, if (dark) tokens.outline.copy(alpha = .42f) else Color(0xFFE2E8F0)),
                            tonalElevation = 0.dp,
                        ) {
                            Row(
                                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 7.dp, vertical = 6.dp),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                YamlAccessoryKey("2空格") { insertYamlText("  ") }
                                YamlAccessoryKey(":") { insertYamlText(": ") }
                                YamlAccessoryKey("-") { insertYamlText("- ") }
                                YamlAccessoryKey("#") { insertYamlText("# ") }
                                YamlAccessoryKey("\"") { insertYamlText("\"") }
                                YamlAccessoryKey("撤销", enabled = yamlCanUndo) { yamlEditor?.undo() }
                                YamlAccessoryKey("重做", enabled = yamlCanRedo) { yamlEditor?.redo() }
                            }
                        }
                    }

                    if (!imeVisible) {
                        Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text("语法着色仅影响显示，不改写配置", color = tokens.textSecondary,
                                fontSize = 12.sp, modifier = Modifier.weight(1f))
                            TextButton(onClick = { yamlOpen = false }, enabled = !yamlSaving,
                                modifier = Modifier.heightIn(min = 48.dp)) { Text("取消") }
                        }
                    }

                }
            }

            if (outlineOpen) {
                ModalBottomSheet(
                    onDismissRequest = { outlineOpen = false },
                    containerColor = tokens.elevatedCardBackground,
                    contentColor = tokens.textPrimary,
                    tonalElevation = 0.dp,
                    shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                    dragHandle = {
                        Box(
                            Modifier.padding(top = 10.dp, bottom = 6.dp)
                                .size(width = 36.dp, height = 4.dp)
                                .background(tokens.textMuted.copy(alpha = .40f), CircleShape),
                        )
                    },
                ) {
                    Column(
                        Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 18.dp, end = 18.dp, bottom = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("YAML 语法大纲", fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
                        Text("点击直接跳转到对应区段", color = tokens.textSecondary, fontSize = 11.sp)
                        Column(
                            Modifier.fillMaxWidth().heightIn(max = 430.dp).verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            outlineItems.forEach { item ->
                                Surface(
                                    onClick = {
                                        jumpToYamlLine(item.line)
                                        outlineOpen = false
                                    },
                                    shape = RoundedCornerShape(15.dp),
                                    color = tokens.controlBackground.copy(alpha = .58f),
                                ) {
                                    Row(
                                        Modifier.fillMaxWidth().padding(horizontal = 13.dp, vertical = 11.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Icon(Icons.Rounded.SubdirectoryArrowRight, null, tint = scheme.primary, modifier = Modifier.size(17.dp))
                                        Spacer(Modifier.width(9.dp))
                                        Text(item.title, color = tokens.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                                        Text("L${item.line + 1}", color = tokens.textMuted, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private data class YamlOutlineItem(val title: String, val line: Int)

private fun yamlOutlineItems(text: String): List<YamlOutlineItem> {
    val lines = text.lines()
    if (lines.isEmpty()) return emptyList()
    val out = ArrayList<YamlOutlineItem>()
    out += YamlOutlineItem("文件顶部", 0)

    val portLine = lines.indexOfFirst {
        val t = it.trim()
        t.startsWith("mixed-port:") || t.startsWith("port:") || t.startsWith("socks-port:") || t.startsWith("tproxy-port:")
    }
    if (portLine >= 0) out += YamlOutlineItem("基础端口", portLine)

    fun sectionEnd(start: Int): Int {
        for (index in start + 1 until lines.size) {
            val raw = lines[index]
            val trimmed = raw.trim()
            if (trimmed.isNotEmpty() && !trimmed.startsWith("#") && raw.takeWhile { it == ' ' || it == '\t' }.isEmpty()) return index
        }
        return lines.size
    }

    val labels = linkedMapOf(
        "dns" to "DNS 模块",
        "tun" to "TUN 设置",
        "sniffer" to "嗅探设置",
        "proxy-providers" to "代理订阅",
        "proxies" to "节点定义",
        "proxy-groups" to "策略组",
        "rule-providers" to "规则集",
        "rules" to "规则分流",
    )
    lines.forEachIndexed { index, raw ->
        if (raw.isBlank() || raw.firstOrNull()?.isWhitespace() == true) return@forEachIndexed
        val trimmed = raw.trim()
        if (!trimmed.endsWith(":")) return@forEachIndexed
        val key = trimmed.removeSuffix(":").trim()
        val base = labels[key] ?: return@forEachIndexed
        val end = sectionEnd(index)
        val count = when (key) {
            "proxy-groups", "proxies" -> lines.subList(index + 1, end).count { it.trim().startsWith("- name:") }
            "rules" -> lines.subList(index + 1, end).count { it.trim().startsWith("-") }
            "proxy-providers", "rule-providers" -> lines.subList(index + 1, end).count {
                val indent = it.length - it.trimStart().length
                indent == 2 && it.trim().endsWith(":")
            }
            else -> 0
        }
        out += YamlOutlineItem(if (count > 0) "$base ($count)" else base, index)
    }
    return out.distinctBy { it.line }
}

@Composable
private fun YamlAccessoryKey(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    val tokens = LocalHetuTokens.current
    val shape = RoundedCornerShape(11.dp)
    Box(
        Modifier.height(34.dp).widthIn(min = 42.dp)
            .clip(shape)
            .background(if (enabled) tokens.cardBackground else tokens.cardBackground.copy(alpha = .42f), shape)
            .border(.6.dp, tokens.outline.copy(alpha = if (enabled) .55f else .24f), shape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (enabled) tokens.textPrimary else tokens.textMuted,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
        )
    }
}

@Composable
private fun SubscriptionMetric(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    accent: Boolean = false,
) {
    val tokens = LocalHetuTokens.current
    val scheme = MaterialTheme.colorScheme
    val dark = scheme.background.luminance() < .5f
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(15.dp),
        color = if (accent) scheme.primary.copy(alpha = if (dark) .13f else .08f)
        else if (dark) Color.White.copy(alpha = .055f) else Color(0xFFF8FAFC).copy(alpha = .86f),
        border = BorderStroke(
            .7.dp,
            if (accent) scheme.primary.copy(alpha = .20f)
            else if (dark) Color.White.copy(alpha = .07f) else Color.White.copy(alpha = .92f),
        ),
        tonalElevation = 0.dp,
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            Text(label, color = tokens.textMuted, fontSize = 9.5.sp, lineHeight = 12.sp, fontWeight = FontWeight.SemiBold)
            Text(
                value,
                color = if (accent) scheme.primary else tokens.textPrimary,
                fontSize = 13.sp,
                lineHeight = 17.sp,
                fontWeight = FontWeight.ExtraBold,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
            )
        }
    }
}

private data class YamlLintIssue(val line: Int, val message: String)

private fun yamlMihomoStrictDomainIssue(text: String): YamlLintIssue? {
    var top = ""
    var child = ""
    var grandchild = ""
    var listIndent = -1
    var domainList = false
    text.lines().forEachIndexed { index, raw ->
        val trimmed = raw.trim()
        if (trimmed.isBlank() || trimmed.startsWith("#")) return@forEachIndexed
        val indent = raw.length - raw.trimStart().length
        fun mappingKey(value: String): String {
            if (value.startsWith("-")) return ""
            val colon = value.indexOf(':')
            if (colon <= 0) return ""
            return value.substring(0, colon).trim().trim('"', '\'')
        }
        when (indent) {
            0 -> {
                top = mappingKey(trimmed); child = ""; grandchild = ""; domainList = false; listIndent = -1
            }
            2 -> {
                child = mappingKey(trimmed); grandchild = ""
                domainList = (top == "sniffer" && (child == "skip-domain" || child == "force-domain")) ||
                    (top == "dns" && child == "fake-ip-filter")
                listIndent = if (domainList) indent else -1
            }
            4 -> if (top == "dns" && child == "fallback-filter") {
                grandchild = mappingKey(trimmed)
                domainList = grandchild == "domain"
                listIndent = if (domainList) indent else -1
            }
        }
        if (domainList && listIndent >= 0 && indent > listIndent && trimmed.startsWith("- ")) {
            var scalar = trimmed.removePrefix("- ").trim()
            var single = false
            var double = false
            var cut = -1
            scalar.forEachIndexed { i, c ->
                if (c == '\'' && !double) single = !single
                else if (c == '"' && !single) double = !double
                else if (c == '#' && !single && !double && (i == 0 || scalar[i - 1].isWhitespace())) {
                    cut = i
                    return@forEachIndexed
                }
            }
            if (cut >= 0) scalar = scalar.substring(0, cut).trim()
            val value = scalar.trim().trim('"', '\'')
            if (!yamlStrictDomainPatternValid(value)) {
                return YamlLintIssue(index + 1, "Mihomo 1.19.30 不接受此域名表达式：$value")
            }
        }
    }
    return null
}

private fun yamlStrictDomainPatternValid(value: String): Boolean {
    if (value.isBlank() || value != value.trim() || value.endsWith('.') || value.startsWith('.') || value.any { it.isWhitespace() }) return false
    val lower = value.lowercase(java.util.Locale.ROOT)
    if (lower.startsWith("rule-set:") || lower.startsWith("geosite:") || lower.startsWith("regexp:")) return true
    val parts = value.split('.')
    if (parts.any { it.isEmpty() }) return false
    parts.forEachIndexed { index, part ->
        if (part == "+") {
            if (index != 0 || parts.size < 2) return false
        } else if (part == "*") {
            // complete wildcard label is supported
        } else if ('+' in part || '*' in part) {
            return false
        }
    }
    return true
}

private fun yamlLocalLint(text: String): YamlLintIssue? {
    val compactMapping = Regex("""^(?:-\s+)?(?:["'][^"']+["']|[A-Za-z0-9_.-]+):\S""")
    text.lines().forEachIndexed { index, raw ->
        if (raw.isBlank()) return@forEachIndexed
        val leading = raw.takeWhile { it == ' ' || it == '\t' }
        if ('\t' in leading) {
            return YamlLintIssue(index + 1, "缩进包含 Tab，请改用 2 个空格")
        }
        val spaces = leading.length
        val trimmed = raw.trimStart()
        if (!trimmed.startsWith("#") && spaces % 2 != 0) {
            return YamlLintIssue(index + 1, "缩进为 ${spaces} 个空格，河图要求按 2 空格层级缩进")
        }
        val urlOnly = trimmed.startsWith("http://") || trimmed.startsWith("https://") ||
            trimmed.startsWith("- http://") || trimmed.startsWith("- https://")
        if (!urlOnly && !trimmed.startsWith("#") && compactMapping.containsMatchIn(trimmed)) {
            return YamlLintIssue(index + 1, "冒号后缺少空格，建议写成 key: value")
        }
    }
    yamlMihomoStrictDomainIssue(text)?.let { return it }
    return null
}

private fun yamlErrorLine(message: String): Int? {
    val patterns = listOf(
        Regex("(?i)line\\s+(\\d+)"),
        Regex("(?i)line[:=]\\s*(\\d+)"),
        Regex("(?i)第\\s*(\\d+)\\s*行"),
    )
    return patterns.firstNotNullOfOrNull { regex ->
        regex.find(message)?.groupValues?.getOrNull(1)?.toIntOrNull()
    }
}

private fun subscriptionUpdatedLabel(raw: String): String {
    if (raw.isBlank()) return "更新 —"
    val millis = runCatching {
        java.time.OffsetDateTime.parse(raw).toInstant().toEpochMilli()
    }.recoverCatching {
        java.time.Instant.parse(raw).toEpochMilli()
    }.getOrNull() ?: return "更新 —"
    val now = System.currentTimeMillis()
    val age = (now - millis).coerceAtLeast(0L)
    if (age < 60L * 60L * 1000L) return "刚刚更新"
    val zone = java.time.ZoneId.systemDefault()
    val time = java.time.Instant.ofEpochMilli(millis).atZone(zone)
    val today = java.time.Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    return if (time.toLocalDate() == today && age < 6L * 60L * 60L * 1000L) {
        time.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm")) + " 更新"
    } else {
        time.format(java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm")) + " 更新"
    }
}

private fun subscriptionHost(item: ProxySubscriptionUi): String {
    if (item.placeholder) return "未配置"
    return runCatching { Uri.parse(item.url).host }.getOrNull().orEmpty().ifBlank { "链接已隐藏" }
}

private fun subscriptionBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var index = 0
    while (value >= 1024.0 && index < units.lastIndex) {
        value /= 1024.0
        index++
    }
    return if (index <= 1) "${value.toInt()} ${units[index]}" else String.format(java.util.Locale.US, "%.1f %s", value, units[index])
}

private fun subscriptionExpireDate(expire: Long): String {
    if (expire <= 0L) return "—"
    val millis = if (expire < 10_000_000_000L) expire * 1000L else expire
    return runCatching {
        java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).format(java.util.Date(millis))
    }.getOrDefault("—")
}

private fun subscriptionExpireLabel(expire: Long): String {
    if (expire <= 0L) return "到期 —"
    val millis = if (expire < 10_000_000_000L) expire * 1000L else expire
    val remaining = millis - System.currentTimeMillis()
    if (remaining <= 0L) return "已到期"
    val days = kotlin.math.ceil(remaining / 86_400_000.0).toInt()
    return when {
        days <= 1 -> "明天到期"
        days <= 30 -> "${days} 天后到期"
        else -> "${subscriptionExpireDate(expire)} 到期"
    }
}

private fun subscriptionSummary(item: ProxySubscriptionUi): String {
    if (item.placeholder) return "未配置 · 点击填写订阅链接"
    val host = runCatching { Uri.parse(item.url).host }.getOrNull()
    return if (host.isNullOrBlank()) "已配置 · 链接已隐藏" else "$host · Token 已隐藏"
}
