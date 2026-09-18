package io.github.xgl34222220.hetu

import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
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
import io.github.xgl34222220.hetu.ui.HetuTheme
import io.github.xgl34222220.hetu.ui.LocalHetuTokens
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

@Composable
private fun ProxySubscriptionScreen(onBack: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val controller = remember { ProxyComposeController(context) }
    val scope = rememberCoroutineScope()
    val tokens = LocalHetuTokens.current
    val scheme = MaterialTheme.colorScheme
    val dark = scheme.background.luminance() < .5f

    var revision by remember { mutableIntStateOf(0) }
    var subscriptions by remember { mutableStateOf(emptyList<ProxySubscriptionUi>()) }
    var configName by remember { mutableStateOf("加载中…") }
    var loading by remember { mutableStateOf(true) }
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
                .onSuccess {
                    message = "已导入并选中 $displayName；启动或重启代理后生效"
                    revision++
                }
                .onFailure { message = it.message ?: "导入配置失败" }
            loading = false
        }
    }

    LaunchedEffect(revision) {
        loading = true
        runCatching {
            val state = controller.state()
            configName = state.config
            controller.subscriptions()
        }.onSuccess {
            subscriptions = it
            message = ""
        }.onFailure {
            subscriptions = emptyList()
            message = it.message ?: "读取订阅失败"
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
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 36.dp),
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
                Surface(shape = RoundedCornerShape(28.dp), color = tokens.cardBackground, shadowElevation = 2.dp) {
                    Column(
                        Modifier.fillMaxWidth().background(
                            Brush.linearGradient(listOf(scheme.primaryContainer.copy(alpha = .42f), tokens.cardBackground))
                        ).padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(shape = RoundedCornerShape(15.dp), color = tokens.elevatedCardBackground, modifier = Modifier.size(46.dp)) {
                                Box(contentAlignment = Alignment.Center) { Icon(Icons.Rounded.CloudSync, null, tint = scheme.primary, modifier = Modifier.size(23.dp)) }
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text("代理订阅", color = tokens.textPrimary, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                Text("$configured 个已配置 · ${subscriptions.size - configured} 个待填写", color = tokens.textSecondary, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        Text(
                            "支持直接导入完整 YAML/YML 配置，也可以只填写订阅链接。配置文件最大 4 MiB，导入后会自动切换为当前配置。",
                            color = tokens.textSecondary,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            OutlinedButton(
                                onClick = { importLauncher.launch(arrayOf("*/*")) },
                                enabled = !loading,
                                modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                                shape = RoundedCornerShape(18.dp),
                            ) {
                                Icon(Icons.Rounded.FileOpen, null, Modifier.size(19.dp))
                                Spacer(Modifier.width(7.dp))
                                Text("导入配置")
                            }
                            Button(
                                onClick = {
                                    addingSubscription = true
                                    editSubscription = null
                                    editorName = ""
                                    editorUrl = ""
                                    editorError = ""
                                },
                                enabled = !loading,
                                modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                                shape = RoundedCornerShape(18.dp),
                            ) {
                                Icon(Icons.Rounded.Add, null, Modifier.size(20.dp))
                                Spacer(Modifier.width(7.dp))
                                Text("添加订阅")
                            }
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

            items(subscriptions, key = { it.name }) { item ->
                Surface(
                    onClick = {
                        addingSubscription = false
                        editSubscription = item
                        editorName = item.name
                        editorUrl = if (item.placeholder) "" else item.url
                        editorError = ""
                    },
                    shape = RoundedCornerShape(24.dp),
                    color = tokens.cardBackground,
                    shadowElevation = 1.dp,
                ) {
                    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Surface(shape = RoundedCornerShape(15.dp), color = tokens.elevatedCardBackground, modifier = Modifier.size(44.dp)) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(if (item.placeholder) Icons.Rounded.LinkOff else Icons.Rounded.Link, null, tint = if (item.placeholder) tokens.warning else scheme.primary, modifier = Modifier.size(21.dp))
                            }
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(item.name, color = tokens.textPrimary, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(subscriptionSummary(item), color = tokens.textSecondary, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Icon(Icons.Rounded.Edit, "编辑", tint = tokens.textSecondary, modifier = Modifier.size(20.dp))
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
        AlertDialog(
            onDismissRequest = { if (!savingSubscription) { addingSubscription = false; editSubscription = null } },
            title = { Text(if (existing == null) "添加订阅" else "编辑订阅") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = editorName,
                        onValueChange = { if (existing == null) editorName = it },
                        enabled = existing == null,
                        label = { Text("订阅名称") },
                        placeholder = { Text("例如：机场一") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                    )
                    OutlinedTextField(
                        value = editorUrl,
                        onValueChange = { editorUrl = it; editorError = "" },
                        label = { Text("订阅链接") },
                        placeholder = { Text("https://…") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                    )
                    Text("链接仅保存在本机私有配置中；订阅列表不会显示 Token。", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    if (editorError.isNotBlank()) Text(editorError, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    if (existing != null && subscriptions.size > 1) {
                        TextButton(
                            onClick = {
                                savingSubscription = true
                                scope.launch {
                                    runCatching { controller.deleteSubscription(existing.name) }
                                        .onSuccess {
                                            addingSubscription = false; editSubscription = null; revision++; message = "已删除 ${existing.name}；重启代理后生效"
                                        }
                                        .onFailure { editorError = it.message ?: "删除失败" }
                                    savingSubscription = false
                                }
                            },
                            enabled = !savingSubscription,
                        ) {
                            Icon(Icons.Rounded.DeleteOutline, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("删除这个订阅")
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
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
                ) {
                    if (savingSubscription) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text("保存")
                }
            },
            dismissButton = { TextButton(onClick = { addingSubscription = false; editSubscription = null }, enabled = !savingSubscription) { Text("取消") } },
        )
    }

    if (yamlOpen) {
        Dialog(
            onDismissRequest = { if (!yamlSaving) yamlOpen = false },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            val editorShape = RoundedCornerShape(18.dp)
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = tokens.pageBackground,
            ) {
                Column(Modifier.fillMaxSize()) {
                    Row(
                        Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp).height(48.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = { if (!yamlSaving) yamlOpen = false }, enabled = !yamlSaving) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回")
                        }
                        Column(Modifier.weight(1f)) {
                            Text("编辑当前 YAML", color = tokens.textPrimary, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
                            Text("保存后重启代理生效", color = tokens.textSecondary, fontSize = 10.sp)
                        }
                    }
                    val editorScroll = rememberScrollState()
                    val editorHorizontalScroll = rememberScrollState()
                    val editorState = androidx.compose.foundation.text.input.rememberTextFieldState(yamlText)
                    val editorText = editorState.text.toString()
                    val lineCount = remember(editorText) { maxOf(1, editorText.count { it == '\n' } + 1) }
                    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
                    LaunchedEffect(editorState) {
                        snapshotFlow { editorState.text.toString() }.collect {
                            if (yamlError.isNotBlank()) yamlError = ""
                        }
                    }
                    Surface(
                        modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 16.dp, vertical = 8.dp),
                        shape = editorShape,
                        color = if (dark) tokens.cardBackground else Color(0xFFF8FAFC),
                        border = BorderStroke(.7.dp, if (dark) tokens.outline.copy(alpha = .42f) else Color(0xFFE2E8F0)),
                        tonalElevation = 0.dp,
                    ) {
                        Row(Modifier.fillMaxSize()) {
                            Box(
                                Modifier.width(46.dp).fillMaxHeight().clipToBounds()
                                    .background(if (dark) Color.White.copy(alpha = .035f) else Color(0xFFF1F5F9)),
                            ) {
                                Text(
                                    (1..lineCount).joinToString("\n"),
                                    modifier = Modifier.fillMaxWidth()
                                        .graphicsLayer { translationY = -editorScroll.value.toFloat() }
                                        .padding(top = 12.dp, end = 9.dp, bottom = 12.dp),
                                    color = if (dark) tokens.textMuted else Color(0xFFB0BAC8),
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    lineHeight = 21.sp,
                                    textAlign = TextAlign.End,
                                )
                            }
                            Box(Modifier.width(1.dp).fillMaxHeight().background(if (dark) Color.White.copy(alpha = .06f) else Color(0xFFE2E8F0)))
                            BasicTextField(
                                state = editorState,
                                modifier = Modifier.weight(1f).fillMaxHeight()
                                    .horizontalScroll(editorHorizontalScroll)
                                    .drawBehind {
                                        val guideColor = if (dark) Color.White.copy(alpha = .04f) else Color(0xFFE2E8F0).copy(alpha = .86f)
                                        val step = 16.dp.toPx()
                                        for (index in 1..5) {
                                            val x = index * step
                                            drawLine(guideColor, Offset(x, 0f), Offset(x, size.height), strokeWidth = .5.dp.toPx())
                                        }
                                    }
                                    .padding(horizontal = 12.dp, vertical = 12.dp),
                                textStyle = MaterialTheme.typography.bodySmall.copy(
                                    color = tokens.textPrimary,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 13.sp,
                                    lineHeight = 21.sp,
                                ),
                                cursorBrush = SolidColor(scheme.primary),
                                outputTransformation = YamlSyntaxHighlightOutputTransformation,
                                scrollState = editorScroll,
                            )
                        }
                    }

                    if (yamlError.isNotBlank()) {
                        Text(
                            yamlError,
                            color = scheme.error,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp),
                        )
                    }
                    androidx.compose.animation.AnimatedVisibility(
                        visible = !imeVisible,
                        enter = androidx.compose.animation.slideInVertically(androidx.compose.animation.core.tween(180)) { it / 2 } +
                            androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(160)),
                        exit = androidx.compose.animation.slideOutVertically(androidx.compose.animation.core.tween(140)) { it / 2 } +
                            androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(120)),
                    ) {
                        Surface(
                        modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp)
                            .shadow(18.dp, RoundedCornerShape(28.dp), clip = false, ambientColor = Color(0xFF0F172A).copy(alpha = .10f), spotColor = Color(0xFF0F172A).copy(alpha = .14f)),
                        shape = RoundedCornerShape(28.dp),
                        color = if (dark) tokens.elevatedCardBackground.copy(alpha = .88f) else Color.White.copy(alpha = .84f),
                        border = BorderStroke(.8.dp, if (dark) Color.White.copy(alpha = .10f) else Color.White.copy(alpha = .96f)),
                        tonalElevation = 0.dp,
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(6.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            FilledTonalButton(
                                onClick = { yamlOpen = false },
                                enabled = !yamlSaving,
                                modifier = Modifier.weight(1f).height(44.dp),
                                shape = CircleShape,
                                colors = ButtonDefaults.filledTonalButtonColors(
                                    containerColor = if (dark) Color.White.copy(alpha = .08f) else Color(0xFFF1F5F9),
                                    contentColor = if (dark) Color(0xFFE2E8F0) else Color(0xFF64748B),
                                ),
                            ) { Text("取消", fontWeight = FontWeight.Bold) }
                            Button(
                                onClick = {
                                    yamlSaving = true
                                    scope.launch {
                                        runCatching { controller.saveConfigText(editorState.text.toString()) }
                                            .onSuccess { yamlOpen = false; revision++; message = "YAML 已保存；重启代理后生效" }
                                            .onFailure { yamlError = it.message ?: "保存失败" }
                                        yamlSaving = false
                                    }
                                },
                                enabled = !yamlSaving,
                                modifier = Modifier.weight(1f).height(44.dp)
                                    .shadow(9.dp, CircleShape, clip = false, ambientColor = Color(0xFF002FA7).copy(alpha = .22f), spotColor = Color(0xFF002FA7).copy(alpha = .30f)),
                                shape = CircleShape,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFF002FA7),
                                    contentColor = Color.White,
                                    disabledContainerColor = Color(0xFF002FA7).copy(alpha = .46f),
                                ),
                            ) {
                                if (yamlSaving) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White)
                                else Text("保存", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                    }
                }
            }
        }
    }
}

private fun subscriptionSummary(item: ProxySubscriptionUi): String {
    if (item.placeholder) return "未配置 · 点击填写订阅链接"
    val host = runCatching { Uri.parse(item.url).host }.getOrNull()
    return if (host.isNullOrBlank()) "已配置 · 链接已隐藏" else "$host · Token 已隐藏"
}
