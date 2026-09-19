package io.github.xgl34222220.hetu

import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.platform.LocalDensity
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

    var revision by remember { mutableIntStateOf(0) }
    var subscriptions by remember { mutableStateOf(emptyList<ProxySubscriptionUi>()) }
    var configLibrary by remember { mutableStateOf(emptyList<ProxyConfigUi>()) }
    var configName by remember { mutableStateOf("加载中…") }
    var liveProviders by remember { mutableStateOf<Map<String, DashboardProviderUi>>(emptyMap()) }
    var loading by remember { mutableStateOf(false) }
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
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 92.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item("header") {
                Row(
                    Modifier.fillMaxWidth().statusBarsPadding().heightIn(min = 64.dp).padding(vertical = 8.dp),
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
                        IconButton(onClick = { revision++ }, enabled = !loading, modifier = Modifier.fillMaxSize()) {
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
                                Text("$configured 个已配置 · ${subscriptions.size - configured} 个待填写 · ${configLibrary.size} 份配置", color = tokens.textSecondary, fontSize = 11.sp)
                            }
                            if (liveTotal > 0L) {
                                Text(
                                    "剩余 ${((1f - liveRatio) * 100f).toInt()}%",
                                    color = scheme.primary,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                        }
                        if (liveTotal > 0L) {
                            Box(Modifier.fillMaxWidth().height(6.dp).background(Color(0xFFF1F5F9), CircleShape)) {
                                Box(
                                    Modifier.fillMaxWidth(liveRatio.coerceIn(.001f, 1f)).fillMaxHeight()
                                        .background(Brush.horizontalGradient(listOf(Color(0xFF2563EB), Color(0xFF22D3EE))), CircleShape),
                                )
                            }
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text("${subscriptionBytes(liveUsed)} / ${subscriptionBytes(liveTotal)}", color = tokens.textPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                                Spacer(Modifier.weight(1f))
                                Text("${live.sumOf { it.nodes.size }} 个节点", color = tokens.textSecondary, fontSize = 11.sp)
                            }
                        } else {
                            Text("运行中的 Mihomo 暂未上报订阅流量；配置管理功能仍可正常使用。", color = tokens.textSecondary, fontSize = 11.sp, lineHeight = 16.sp)
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            FilledTonalButton(
                                onClick = { importLauncher.launch(arrayOf("*/*")) },
                                modifier = Modifier.weight(1f).height(46.dp),
                                shape = CircleShape,
                            ) {
                                Icon(Icons.Rounded.FileOpen, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("导入配置", fontWeight = FontWeight.Bold)
                            }
                            Button(
                                onClick = {
                                    addingSubscription = true
                                    editSubscription = null
                                    editorName = ""
                                    editorUrl = ""
                                    editorError = ""
                                },
                                modifier = Modifier.weight(1f).height(46.dp),
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
                val selectedBrush = if (dark) {
                    Brush.horizontalGradient(listOf(Color(0xFF172554).copy(alpha = .52f), tokens.cardBackground))
                } else {
                    Brush.horizontalGradient(listOf(Color(0xFFEFF6FF).copy(alpha = .72f), Color.White.copy(alpha = .86f)))
                }
                Box(
                    Modifier.fillMaxWidth()
                        .shadow(
                            1.dp,
                            cardShape,
                            clip = false,
                            ambientColor = Color(0xFF0F172A).copy(alpha = .025f),
                            spotColor = Color(0xFF0F172A).copy(alpha = .035f),
                        )
                        .background(
                            if (config.selected) selectedBrush
                            else Brush.verticalGradient(listOf(tokens.cardBackground.copy(alpha = .82f), tokens.cardBackground.copy(alpha = .68f))),
                            cardShape,
                        )
                        .border(
                            if (config.selected) 1.5.dp else .7.dp,
                            if (config.selected) scheme.primary.copy(alpha = .88f)
                            else if (dark) tokens.outline.copy(alpha = .34f) else Color(0xFFCBD5E1).copy(alpha = .50f),
                            cardShape,
                        )
                        .clip(cardShape)
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
                        Box(
                            Modifier.size(23.dp)
                                .border(
                                    if (config.selected) 0.dp else 1.3.dp,
                                    if (config.selected) Color.Transparent else tokens.textMuted.copy(alpha = .60f),
                                    CircleShape,
                                )
                                .background(if (config.selected) scheme.primary else Color.Transparent, CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (config.selected) {
                                Icon(Icons.Rounded.Check, "当前使用", tint = Color.White, modifier = Modifier.size(14.dp))
                            }
                        }
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

            items(
                subscriptions,
                key = { item ->
                    val provider = liveProviders[item.name] ?: liveProviders.entries.firstOrNull { it.key.equals(item.name, true) }?.value
                    "sub-${item.name}-${provider?.updatedAt.orEmpty()}-${provider?.upload ?: 0L}-${provider?.download ?: 0L}-${provider?.total ?: 0L}"
                },
            ) { item ->
                val provider = liveProviders[item.name] ?: liveProviders.entries.firstOrNull { it.key.equals(item.name, true) }?.value
                val host = subscriptionHost(item)
                val ratio = provider?.takeIf { it.hasSubscriptionInfo && it.total > 0L }?.ratio?.coerceIn(0f, 1f)
                val remainingPercent = ratio?.let { ((1f - it) * 100f).toInt() }
                Surface(
                    onClick = {
                        addingSubscription = false
                        editSubscription = item
                        editorName = item.name
                        editorUrl = if (item.placeholder) "" else item.url
                        editorError = ""
                    },
                    shape = RoundedCornerShape(20.dp),
                    color = tokens.cardBackground,
                    border = BorderStroke(.7.dp, if (dark) tokens.outline.copy(alpha = .30f) else Color(0xFFE2E8F0).copy(alpha = .70f)),
                    shadowElevation = 1.dp,
                ) {
                    Column(Modifier.fillMaxWidth().padding(15.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier.size(36.dp).background(scheme.primary.copy(alpha = .08f), CircleShape),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    if (item.placeholder) Icons.Rounded.LinkOff else Icons.Rounded.Link,
                                    null,
                                    tint = if (item.placeholder) tokens.warning else scheme.primary,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(item.name, color = tokens.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(host, color = tokens.textSecondary, fontSize = 10.sp, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            if (provider != null) {
                                Surface(shape = CircleShape, color = scheme.primary.copy(alpha = .08f)) {
                                    Text("${provider.nodes.size} 节点", Modifier.padding(horizontal = 7.dp, vertical = 3.dp), color = scheme.primary, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                            Spacer(Modifier.width(5.dp))
                            Icon(Icons.Rounded.Edit, "编辑", tint = tokens.textMuted, modifier = Modifier.size(17.dp))
                        }

                        Box(
                            Modifier.fillMaxWidth().height(6.dp)
                                .background(if (dark) Color.White.copy(alpha = .06f) else Color(0xFFF1F5F9), CircleShape),
                        ) {
                            if (ratio != null && ratio > 0f) {
                                Box(
                                    Modifier.fillMaxWidth(ratio.coerceIn(.001f, 1f)).fillMaxHeight()
                                        .background(Brush.horizontalGradient(listOf(Color(0xFF2563EB), Color(0xFF22D3EE))), CircleShape),
                                )
                            }
                        }

                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            val usage = if (provider != null && provider.hasSubscriptionInfo && provider.total > 0L) {
                                "${subscriptionBytes(provider.used)} / ${subscriptionBytes(provider.total)}"
                            } else "流量 —"
                            Text(
                                usage,
                                color = tokens.textPrimary,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                maxLines = 1,
                            )
                            if (remainingPercent != null) {
                                Spacer(Modifier.width(7.dp))
                                Surface(shape = CircleShape, color = if (dark) Color(0xFF064E3B).copy(alpha = .34f) else Color(0xFFECFDF5)) {
                                    Text(
                                        "剩余 $remainingPercent%",
                                        Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                        color = if (dark) Color(0xFF6EE7B7) else Color(0xFF059669),
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold,
                                    )
                                }
                            }
                            Spacer(Modifier.weight(1f))
                            Text(
                                provider?.expire?.takeIf { it > 0L }?.let { "${subscriptionExpireDate(it)} 到期" } ?: "到期 —",
                                color = tokens.textSecondary,
                                fontSize = 9.sp,
                                maxLines = 1,
                            )
                        }
                        if (provider?.updatedAt?.isNotBlank() == true) {
                            Text(subscriptionUpdatedLabel(provider.updatedAt), color = tokens.textMuted, fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }

            item("yaml-heading") {
                Column(Modifier.padding(horizontal = 2.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("高级编辑", color = tokens.textPrimary, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text("需要修改策略组、DNS、规则或其他 Mihomo 字段时直接编辑 YAML", color = tokens.textSecondary, style = MaterialTheme.typography.bodySmall)
                }
            }
            item("yaml") {
                Surface(shape = RoundedCornerShape(24.dp), color = tokens.cardBackground, shadowElevation = 1.dp) {
                    Row(
                        Modifier.fillMaxWidth().padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Surface(shape = RoundedCornerShape(15.dp), color = tokens.elevatedCardBackground, modifier = Modifier.size(44.dp)) {
                            Box(contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Code, null, tint = scheme.primary, modifier = Modifier.size(22.dp)) }
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
                        modifier = Modifier.weight(1f).height(46.dp),
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
                        modifier = Modifier.weight(1.35f).height(46.dp),
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
                Column(Modifier.fillMaxSize()) {
                    Row(
                        Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 10.dp, vertical = 7.dp).height(48.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = { if (!yamlSaving) yamlOpen = false }, enabled = !yamlSaving) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回")
                        }
                        Column(Modifier.weight(1f)) {
                            Text(configName, color = tokens.textPrimary, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                            Text("$yamlLineCount 行 · 高性能 YAML 编辑", color = tokens.textSecondary, fontSize = 10.sp)
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
                        AndroidView(
                            factory = { viewContext ->
                                CodeEditor(viewContext).apply {
                                    setText(yamlText)
                                    typefaceText = Typeface.MONOSPACE
                                    setTextSize(13f)
                                    setLineNumberEnabled(true)
                                    setWordwrap(false)
                                    setTabWidth(2)
                                    setHighlightCurrentLine(true)
                                    nonPrintablePaintingFlags =
                                        CodeEditor.FLAG_DRAW_WHITESPACE_LEADING or
                                            CodeEditor.FLAG_DRAW_LINE_SEPARATOR or
                                            CodeEditor.FLAG_DRAW_WHITESPACE_IN_SELECTION
                                    colorScheme = if (dark) SchemeDarcula() else SchemeGitHub()
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

                    if (yamlError.isNotBlank()) {
                        Surface(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                            shape = RoundedCornerShape(14.dp),
                            color = scheme.errorContainer,
                            tonalElevation = 0.dp,
                        ) {
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(Icons.Rounded.ErrorOutline, null, tint = scheme.error, modifier = Modifier.size(17.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(yamlError, color = scheme.onErrorContainer, fontSize = 11.sp, lineHeight = 15.sp, modifier = Modifier.weight(1f))
                            }
                        }
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

                    androidx.compose.animation.AnimatedVisibility(
                        visible = !imeVisible,
                        enter = androidx.compose.animation.slideInVertically(androidx.compose.animation.core.tween(180)) { it / 2 } +
                            androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(160)),
                        exit = androidx.compose.animation.slideOutVertically(androidx.compose.animation.core.tween(140)) { it / 2 } +
                            androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(120)),
                    ) {
                        Row(
                            Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            FilledTonalButton(
                                onClick = { yamlOpen = false },
                                enabled = !yamlSaving,
                                modifier = Modifier.weight(1f).height(44.dp),
                                shape = CircleShape,
                            ) { Text("取消", fontWeight = FontWeight.Bold) }
                            Button(
                                onClick = ::saveYaml,
                                enabled = !yamlSaving,
                                modifier = Modifier.weight(1f).height(44.dp),
                                shape = CircleShape,
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF002FA7), contentColor = Color.White),
                            ) {
                                if (yamlSaving) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White)
                                else Text("保存", fontWeight = FontWeight.Bold)
                            }
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

private data class YamlLintIssue(val line: Int, val message: String)

private fun yamlLocalLint(text: String): YamlLintIssue? {
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
    }
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
    return if (time.toLocalDate() == today) {
        "今天 " + time.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm")) + " 更新"
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

private fun subscriptionSummary(item: ProxySubscriptionUi): String {
    if (item.placeholder) return "未配置 · 点击填写订阅链接"
    val host = runCatching { Uri.parse(item.url).host }.getOrNull()
    return if (host.isNullOrBlank()) "已配置 · 链接已隐藏" else "$host · Token 已隐藏"
}
