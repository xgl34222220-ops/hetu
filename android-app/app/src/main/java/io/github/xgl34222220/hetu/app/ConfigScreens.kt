package io.github.xgl34222220.hetu

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.automirrored.rounded.Redo
import androidx.compose.material.icons.automirrored.rounded.FormatListBulleted
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.Check
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CloudSync
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.FactCheck
import androidx.compose.material.icons.rounded.FileOpen
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.ViewModelProvider
import io.github.rosemoe.sora.event.ContentChangeEvent
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.schemes.SchemeDarcula
import io.github.rosemoe.sora.widget.schemes.SchemeGitHub
import io.github.rosemoe.sora.widget.subscribeAlways
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/* ------------------------------------------------------------------ */
/*  Config library + subscriptions                                      */
/* ------------------------------------------------------------------ */

@Composable
internal fun ConfigsScreen(vm: HetuViewModel) {
    val nav = LocalNav.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val c = Hx.colors
    var revision by remember { mutableIntStateOf(0) }
    var configs by remember { mutableStateOf<List<ProxyConfigUi>>(emptyList()) }
    var subscriptions by remember { mutableStateOf<List<ProxySubscriptionUi>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var menuFor by remember { mutableStateOf<ProxyConfigUi?>(null) }
    var confirmDelete by remember { mutableStateOf<ProxyConfigUi?>(null) }
    var renameConfig by remember { mutableStateOf<ProxyConfigUi?>(null) }
    var exportBytes by remember { mutableStateOf<ByteArray?>(null) }
    var urlImport by remember { mutableStateOf(false) }
    var editSub by remember { mutableStateOf<ProxySubscriptionUi?>(null) }
    var addSub by remember { mutableStateOf(false) }
    val haptics = io.github.xgl34222220.hetu.ui.rememberHetuHaptics()
    var deleteSub by remember { mutableStateOf<ProxySubscriptionUi?>(null) }

    LaunchedEffect(revision) {
        loading = true
        try {
            configs = vm.controller.configLibrary()
            subscriptions = vm.controller.subscriptions()
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            vm.toast(error.message ?: "配置读取失败")
        } finally {
            loading = false
        }
    }

    fun perform(action: String, applyToRuntime: Boolean, block: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                block()
                revision++
                vm.refreshNow()
                if (applyToRuntime) vm.applyConfigChange(action) else vm.toast(action)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                vm.toast(error.message ?: "操作失败")
            } finally {
                busy = false
            }
        }
    }

    val importFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) perform("配置已导入并设为当前", applyToRuntime = true) {
            vm.controller.importConfig(uri, displayName(context, uri))
        }
    }

    val exportFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val bytes = exportBytes
        exportBytes = null
        if (uri != null && bytes != null) perform("配置已导出", applyToRuntime = false) {
            withContext(Dispatchers.IO) {
                context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                    ?: error("无法写入导出文件")
            }
        }
    }

    HxPage(
        title = "配置与订阅",
        largeTitle = false,
        subtitle = "当前：${vm.state.config}",
        onBack = { nav.pop() },
        actions = { if (busy) Box(Modifier.padding(12.dp)) { HxSpinner(18.dp) } },
    ) {
        item(key = "import") {
            HxSection("导入") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    HxButton("从文件", onClick = { importFile.launch(arrayOf("*/*")) }, icon = Icons.Rounded.FileOpen, filled = false, modifier = Modifier.weight(1f))
                    HxButton("从链接", onClick = { urlImport = true }, icon = Icons.Rounded.Link, filled = false, modifier = Modifier.weight(1f))
                }
            }
        }
        item(key = "configs") {
            HxSection() {
                HxGroup(title = "配置文件") {
                    if (loading && configs.isEmpty()) {
                        Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { HxSpinner() }
                    }
                    configs.forEachIndexed { index, config ->
                        if (index > 0) HxDivider(52.dp)
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .hxAnchorSource()
                                .hxCombinedClick(
                                    enabled = !busy,
                                    onLongClick = { haptics.perform(io.github.xgl34222220.hetu.ui.HetuHaptic.LongPress); menuFor = config },
                                    onClick = {
                                        if (!config.selected) {
                                            perform("已切换到 ${config.name}", applyToRuntime = true) { vm.controller.selectConfig(config.name) }
                                        }
                                    },
                                )
                                .padding(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            HxSelectMark(config.selected, Modifier.padding(horizontal = 14.dp))
                            Column(Modifier.weight(1f).padding(vertical = 10.dp)) {
                                Text(
                                    config.name,
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = if (config.selected) androidx.compose.ui.text.font.FontWeight.SemiBold else androidx.compose.ui.text.font.FontWeight.Normal,
                                    color = c.text,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (config.bundled) Text("内置模板 · 需要填写订阅", style = MaterialTheme.typography.bodySmall, color = c.textMuted)
                            }
                            IconButton(onClick = { menuFor = config }, modifier = Modifier.hxAnchorSource()) {
                                Icon(Icons.Rounded.MoreHoriz, "更多", tint = c.textMuted)
                            }
                        }
                    }
                }
            }
        }
        item(key = "subs") {
            HxSection(
                "订阅链接（proxy-providers）",
                trailing = {
                    Text(
                        "添加",
                        style = MaterialTheme.typography.labelLarge,
                        color = c.accent,
                        modifier = Modifier.clip(Hx.chipShape).clickable(enabled = !busy) { addSub = true }.padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                },
            ) {
                HxGroup {
                    if (subscriptions.isEmpty() && !loading) {
                        HxRow("当前配置没有订阅段", subtitle = "完整的 YAML 配置无需订阅；如需添加，请在编辑器中加入 proxy-providers", icon = Icons.Rounded.CloudSync, iconTint = c.textMuted)
                    }
                    subscriptions.forEachIndexed { index, sub ->
                        if (index > 0) HxDivider()
                        HxRow(
                            sub.name,
                            subtitle = if (sub.placeholder) "尚未填写订阅链接" else maskUrl(sub.url),
                            icon = Icons.Rounded.CloudSync,
                            iconTint = if (sub.placeholder) c.warn else c.accent,
                            onClick = { editSub = sub },
                        ) {
                            IconButton(onClick = { deleteSub = sub }) { Icon(Icons.Rounded.DeleteOutline, "删除订阅", tint = c.textFaint) }
                        }
                    }
                }
            }
        }
        item(key = "more") {
            HxSection() {
                HxGroup(title = "编辑") {
                    HxNavRow("编辑 YAML", subtitle = "语法大纲跳转、撤销重做，保存前自动校验", icon = Icons.Rounded.Edit) { nav.push(HxRoute.ConfigEditor) }
                }
            }
        }
    }

    menuFor?.let { config ->
        HxActionMenu(
            title = config.name,
            actions = buildList {
                if (!config.selected) {
                    add(HxMenuAction("设为当前配置", Icons.Rounded.CheckCircle) {
                        menuFor = null
                        perform("已切换到 ${config.name}", applyToRuntime = true) { vm.controller.selectConfig(config.name) }
                    })
                }
                add(HxMenuAction("导出配置", Icons.Rounded.FileOpen) {
                    menuFor = null
                    perform("请选择导出位置", applyToRuntime = false) {
                        exportBytes = vm.controller.exportConfig(config.name)
                        exportFile.launch(config.name)
                    }
                })
                if (!config.bundled) add(HxMenuAction("重命名", Icons.Rounded.Edit) {
                    menuFor = null
                    renameConfig = config
                })
                if (!config.bundled) add(HxMenuAction("删除配置", Icons.Rounded.DeleteOutline, danger = true) { menuFor = null; confirmDelete = config })
            },
            onDismiss = { menuFor = null },
        )
    }

    renameConfig?.let { config ->
        HxFormDialog(
            title = "重命名配置",
            fields = listOf(HxField("名称", config.name)),
            validate = { values -> if (values[0].isBlank()) "名称不能为空" else null },
            onConfirm = { values ->
                renameConfig = null
                perform("已重命名", applyToRuntime = false) {
                    vm.controller.renameConfig(config.name, values[0].trim())
                }
            },
            onDismiss = { renameConfig = null },
        )
    }

    confirmDelete?.let { config ->
        HxConfirmDialog(
            title = "删除配置？",
            message = "「${config.name}」将被永久删除。" + if (config.selected) "删除后会切换回内置模板。" else "",
            confirmLabel = "删除",
            danger = true,
            onConfirm = {
                confirmDelete = null
                perform("已删除", applyToRuntime = false) { vm.controller.deleteConfig(config.name) }
            },
            onDismiss = { confirmDelete = null },
        )
    }

    if (urlImport) {
        HxFormDialog(
            title = "从链接导入配置",
            message = "输入 Clash / Mihomo 配置订阅地址，会下载完整 YAML 保存为新配置。",
            fields = listOf(HxField("配置链接", placeholder = "https://"), HxField("名称（可选）", placeholder = "例如 机场A.yaml")),
            confirmLabel = "下载",
            validate = { v -> if (!v[0].startsWith("http://") && !v[0].startsWith("https://")) "请输入 http/https 链接" else null },
            onConfirm = { v ->
                urlImport = false
                perform("配置已下载并设为当前", applyToRuntime = true) { downloadConfig(context, v[0], v[1]) }
            },
            onDismiss = { urlImport = false },
        )
    }

    if (addSub) {
        HxFormDialog(
            title = "添加订阅",
            fields = listOf(HxField("名称", placeholder = "例如 机场A"), HxField("订阅链接", placeholder = "https://")),
            confirmLabel = "添加",
            validate = { v -> if (v[0].isBlank()) "名称不能为空" else if (!v[1].startsWith("http")) "请输入 http/https 链接" else null },
            onConfirm = { v ->
                addSub = false
                perform("订阅已添加", applyToRuntime = true) { vm.controller.addSubscription(v[0], v[1]) }
            },
            onDismiss = { addSub = false },
        )
    }

    editSub?.let { sub ->
        HxFormDialog(
            title = sub.name,
            fields = listOf(HxField("订阅链接", if (sub.placeholder) "" else sub.url, placeholder = "https://")),
            validate = { v -> if (!v[0].startsWith("http")) "请输入 http/https 链接" else null },
            onConfirm = { v ->
                editSub = null
                perform("订阅已保存", applyToRuntime = true) { vm.controller.updateSubscription(sub.name, v[0]) }
            },
            onDismiss = { editSub = null },
        )
    }

    deleteSub?.let { sub ->
        HxConfirmDialog(
            title = "删除订阅？",
            message = "从当前配置中移除「${sub.name}」及其在策略组中的引用。",
            confirmLabel = "删除",
            danger = true,
            onConfirm = {
                deleteSub = null
                perform("订阅已删除", applyToRuntime = true) { vm.controller.deleteSubscription(sub.name) }
            },
            onDismiss = { deleteSub = null },
        )
    }
}

private fun maskUrl(url: String): String = runCatching {
    val uri = Uri.parse(url)
    val host = uri.host.orEmpty()
    if (host.isBlank()) url.take(40) else "${uri.scheme}://$host/…"
}.getOrDefault(url.take(40))

private fun displayName(context: Context, uri: Uri): String {
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
    return normalizeConfigName(name)
}

private fun normalizeConfigName(raw: String): String {
    var name = raw.replace('/', '_').replace('\\', '_').trim().ifBlank { "imported-config" }
    if (!name.endsWith(".yaml", ignoreCase = true) && !name.endsWith(".yml", ignoreCase = true)) name += ".yaml"
    if (name.length > 120) name = name.takeLast(120)
    if (name.length < 3) name = "config.yaml"
    return name
}

/** Downloads a full Clash/Mihomo YAML subscription and stores it as a new config. */
private suspend fun downloadConfig(context: Context, url: String, requestedName: String) = withContext(Dispatchers.IO) {
    val downloadContext = currentCoroutineContext()
    downloadContext.ensureActive()
    val connection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 15_000
        readTimeout = 30_000
        instanceFollowRedirects = true
        setRequestProperty("User-Agent", "clash.meta")
        setRequestProperty("Accept", "*/*")
    }
    try {
        val code = connection.responseCode
        if (code !in 200..299) throw IOException("下载失败：HTTP $code")
        val disposition = connection.getHeaderField("Content-Disposition").orEmpty()
        val fromHeader = Regex("filename\\*?=(?:UTF-8'')?\"?([^\";]+)\"?", RegexOption.IGNORE_CASE)
            .find(disposition)?.groupValues?.getOrNull(1)?.let { Uri.decode(it) }.orEmpty()
        val name = normalizeConfigName(
            requestedName.ifBlank { fromHeader.ifBlank { Uri.parse(url).host.orEmpty().ifBlank { "subscription" } } },
        )
        val content = connection.inputStream.use {
            ConfigDownloadReader.read(it, connection.contentLengthLong) { downloadContext.ensureActive() }
        }
        if (!content.text.contains("proxies") && !content.text.contains("proxy-providers")) {
            throw IOException("下载内容不是 Clash/Mihomo YAML 配置（可能是 Base64 节点列表，请改用「添加订阅」）")
        }
        val profile = ProxyRuntimeProfile.load(context.getSharedPreferences("hetu", Context.MODE_PRIVATE))
        downloadContext.ensureActive()
        ProxyConfigLibrary(context).importConfig(profile.core, name, content.bytes.inputStream())
    } finally {
        connection.disconnect()
    }
}

/* ------------------------------------------------------------------ */
/*  Providers (live subscription usage)                                 */
/* ------------------------------------------------------------------ */

@Composable
internal fun ProvidersScreen(vm: HetuViewModel) {
    val nav = LocalNav.current
    LaunchedEffect(Unit) { vm.loadProviders() }
    HxPage(
        title = "订阅流量",
        largeTitle = false,
        subtitle = if (vm.state.running) "来自运行中 Mihomo 的 proxy-providers" else "代理未运行",
        onBack = { nav.pop() },
    ) {
        if (!vm.state.running) {
            item(key = "stopped") { HxEmpty(Icons.Rounded.CloudSync, "代理未运行", "启动后可查看订阅流量并更新节点") }
        } else {
            providerListItems(vm)
        }
    }
}

/* ------------------------------------------------------------------ */
/*  YAML editor                                                         */
/* ------------------------------------------------------------------ */

private val HxYamlSymbols = listOf("Tab", ":", "-", "#", "\"", "'", "[", "]", "{", "}", "true", "false", "|")

private fun hxApplyYamlSymbol(editor: CodeEditor, symbol: String) {
    if (!editor.isEditable) return
    if (symbol == "Tab") {
        if (editor.cursor.isSelected) editor.indentSelection() else editor.insertText("  ", 2)
    } else {
        val inserted = when (symbol) {
            ":" -> ": "
            "-" -> "- "
            "#" -> "# "
            else -> symbol
        }
        editor.insertText(inserted, inserted.length)
    }
    editor.requestFocus()
    editor.ensureSelectionVisible()
}

class ProxyConfigEditorActivity : ComponentActivity() {
    private lateinit var vm: HetuViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        vm = ViewModelProvider(this)[HetuViewModel::class.java]
        setContent {
            HetuAppTheme(appearance = vm.appearance, dynamic = vm.dynamicColor, accentHex = vm.accentHex, pureBlack = vm.pureBlack) {
                ConfigEditorScreen(vm, onBackOverride = { finish() })
            }
        }
    }
}

@Composable
internal fun ConfigEditorScreen(
    vm: HetuViewModel,
    onBackOverride: (() -> Unit)? = null,
    editorRepository: ConfigEditorRepository? = null,
) {
    val repository = editorRepository ?: remember(vm) { ControllerConfigEditorRepository(vm.controller) }
    val nav = if (onBackOverride == null) LocalNav.current else null
    val scope = rememberCoroutineScope()
    val c = Hx.colors
    var snapshot by remember { mutableStateOf<ConfigEditSnapshot?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var loadRevision by remember { mutableIntStateOf(0) }
    var editor by remember { mutableStateOf<CodeEditor?>(null) }
    var dirty by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var validating by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    var confirmLeave by remember { mutableStateOf(false) }
    var showOutline by remember { mutableStateOf(false) }
    val haptics = io.github.xgl34222220.hetu.ui.rememberHetuHaptics()
    val editorColors = remember(c.dark) { HetuYamlLanguage.colors(if (c.dark) SchemeDarcula() else SchemeGitHub(), c.dark) }

    LaunchedEffect(loadRevision) {
        loadError = null
        try {
            snapshot = repository.load()
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            loadError = error.message ?: "配置读取失败"
        }
    }

    fun leave() {
        if (saving) return
        if (dirty) confirmLeave = true
        else if (onBackOverride != null) onBackOverride() else nav?.pop()
    }
    BackHandler(enabled = dirty || saving) { if (!saving) confirmLeave = true }

    fun validate() {
        if (validating || saving || snapshot == null) return
        val current = editor?.text?.toString() ?: return
        validating = true
        scope.launch {
            try {
                repository.validate(current)
                problem = null
                vm.toast(if (editor?.text?.toString() == current) "校验通过" else "内容已修改，请重新校验")
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                if (editor?.text?.toString() == current) problem = error.message ?: "配置无效"
                else vm.toast("内容已修改，请重新校验")
            } finally {
                validating = false
            }
        }
    }

    fun save() {
        if (saving || validating || !dirty) return
        val opened = snapshot ?: return
        val current = editor?.text?.toString() ?: return
        saving = true
        editor?.isEditable = false
        scope.launch {
            try {
                repository.save(opened, current)
                snapshot = ConfigEditSnapshot(opened.coreId, opened.name, current)
                dirty = false
                problem = null
                vm.applyConfigChange("配置已保存")
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                problem = error.message ?: "保存失败"
            } finally {
                saving = false
                editor?.isEditable = true
            }
        }
    }

    Column(Modifier.fillMaxSize().background(c.canvas).imePadding()) {
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().height(HxTopBarHeight).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = ::leave, enabled = !saving) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回", tint = c.text) }
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("编辑配置", style = MaterialTheme.typography.titleMedium, color = c.text)
                    val dot by androidx.compose.animation.core.animateFloatAsState(if (dirty) 1f else 0f, HxMotion.pop(), label = "dirtyDot")
                    Spacer(Modifier.width(6.dp))
                    Box(
                        Modifier
                            .size(7.dp)
                            .graphicsLayer { scaleX = dot; scaleY = dot; alpha = dot.coerceIn(0f, 1f) }
                            .clip(CircleShape)
                            .background(c.warn),
                    )
                }
                Text(snapshot?.name ?: "读取配置", style = MaterialTheme.typography.bodySmall, color = c.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            HxBarAction(Icons.AutoMirrored.Rounded.FormatListBulleted, "语法大纲", onClick = { showOutline = true }, enabled = snapshot != null && !saving)
            HxBarAction(Icons.Rounded.FactCheck, "校验", onClick = ::validate, busy = validating, enabled = snapshot != null && !saving)
            HxBarAction(Icons.Rounded.Save, "保存", onClick = ::save, busy = saving, enabled = dirty && !validating)
        }
        HorizontalDivider(thickness = 0.5.dp, color = c.line)
        var lastProblem by remember { mutableStateOf("") }
        LaunchedEffect(problem) { problem?.let { lastProblem = it } }
        AnimatedVisibility(
            visible = problem != null,
            enter = fadeIn(tween(HxMotion.Medium)) + expandVertically(tween(HxMotion.Medium, easing = HxMotion.Emphasized)),
            exit = fadeOut(tween(HxMotion.Short)) + shrinkVertically(tween(HxMotion.Medium, easing = HxMotion.Emphasized)),
        ) {
            HxBanner(problem ?: lastProblem, tone = HxTone.Bad, modifier = Modifier.padding(12.dp), actionLabel = "关闭", onAction = { problem = null })
        }
        Box(Modifier.weight(1f).fillMaxWidth().background(c.surface)) {
            val initial = snapshot?.originalText
            if (loadError != null) {
                Column(
                    Modifier.align(Alignment.Center).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text(loadError.orEmpty(), style = MaterialTheme.typography.bodyMedium, color = c.textMuted)
                    HxButton("重新读取", onClick = { loadRevision++ }, icon = Icons.Rounded.Refresh)
                }
            } else if (initial == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { HxSpinner(26.dp) }
            } else {
                AndroidView(
                    factory = { viewContext ->
                        CodeEditor(viewContext).apply {
                            setEditorLanguage(HetuYamlLanguage())
                            setText(initial)
                            isEditable = true
                            isEnabled = true
                            setSoftKeyboardEnabled(true)
                            isFocusableInTouchMode = true
                            contentDescription = "配置 YAML 编辑器"
                            typefaceText = Typeface.MONOSPACE
                            setTextSize(13f)
                            setLineNumberEnabled(true)
                            setWordwrap(false)
                            setTabWidth(2)
                            setHighlightCurrentLine(true)
                            colorScheme = editorColors
                            subscribeAlways<ContentChangeEvent> {
                                dirty = this.text.toString() != snapshot?.originalText
                            }
                            editor = this
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                    update = { native ->
                        if (native.colorScheme !== editorColors) native.colorScheme = editorColors
                        native.isEditable = !saving
                    },
                    onRelease = { native ->
                        if (editor === native) editor = null
                        native.release()
                    },
                )
            }
        }
        HorizontalDivider(thickness = 0.5.dp, color = c.line)
        Row(
            Modifier
                .fillMaxWidth()
                .background(c.canvas)
                .navigationBarsPadding()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            HxEditorKey(Icons.AutoMirrored.Rounded.Undo, "撤销") { if (!saving) editor?.let { if (it.canUndo()) it.undo() } }
            HxEditorKey(Icons.AutoMirrored.Rounded.Redo, "重做") { if (!saving) editor?.let { if (it.canRedo()) it.redo() } }
            Box(Modifier.padding(vertical = 6.dp).width(0.5.dp).height(22.dp).background(c.line))
            HxYamlSymbols.forEach { symbol ->
                val source = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                Box(
                    Modifier
                        .hxPressScale(source, .9f)
                        .clip(Hx.chipShape)
                        .background(c.surface)
                        .clickable(enabled = !saving && snapshot != null, interactionSource = source, indication = androidx.compose.foundation.LocalIndication.current) {
                            haptics.perform(io.github.xgl34222220.hetu.ui.HetuHaptic.Tick)
                            editor?.let { hxApplyYamlSymbol(it, symbol) }
                        }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(symbol, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelLarge, color = c.text)
                }
            }
        }
    }

    if (showOutline) {
        val current = editor?.text?.toString().orEmpty()
        val outline = remember(current) { hxYamlOutline(current) }
        HxSheet(onDismiss = { showOutline = false }, title = "语法大纲") {
            val close = LocalHxSheetClose.current
            if (outline.isEmpty()) {
                Text("没有识别到顶层字段", style = MaterialTheme.typography.bodyMedium, color = c.textMuted, modifier = Modifier.padding(horizontal = 22.dp, vertical = 12.dp))
            }
            androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxWidth().heightIn(max = 520.dp)) {
                items(outline.size) { index ->
                    val entry = outline[index]
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                close {
                                    showOutline = false
                                    editor?.let { native ->
                                        runCatching {
                                            native.setSelection(entry.line, 0)
                                            native.ensureSelectionVisible()
                                            native.requestFocus()
                                        }
                                    }
                                }
                            }
                            .padding(start = if (entry.level == 0) 22.dp else 40.dp, end = 22.dp, top = 10.dp, bottom = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            entry.label,
                            style = if (entry.level == 0) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.bodyMedium,
                            fontWeight = if (entry.level == 0) androidx.compose.ui.text.font.FontWeight.SemiBold else androidx.compose.ui.text.font.FontWeight.Normal,
                            color = if (entry.level == 0) c.text else c.textMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Text("${entry.line + 1}", style = MaterialTheme.typography.labelSmall.merge(HxNumberStyle), color = c.textFaint)
                    }
                }
            }
        }
    }

    if (confirmLeave) {
        HxConfirmDialog(
            title = "放弃修改？",
            message = "当前修改还没有保存。",
            confirmLabel = "放弃",
            danger = true,
            onConfirm = {
                confirmLeave = false
                dirty = false
                if (onBackOverride != null) onBackOverride() else nav?.pop()
            },
            onDismiss = { confirmLeave = false },
        )
    }
}


@Composable
private fun HxEditorKey(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, onClick: () -> Unit) {
    val c = Hx.colors
    val haptics = io.github.xgl34222220.hetu.ui.rememberHetuHaptics()
    val source = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    Box(
        Modifier
            .hxPressScale(source, .9f)
            .clip(Hx.chipShape)
            .background(c.surface)
            .clickable(interactionSource = source, indication = androidx.compose.foundation.LocalIndication.current) {
                haptics.perform(io.github.xgl34222220.hetu.ui.HetuHaptic.Tick)
                onClick()
            }
            .padding(horizontal = 10.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, description, tint = c.text, modifier = Modifier.size(19.dp))
    }
}

/** Selection mark for single-choice lists: a ring that fills with a check. */
@Composable
internal fun HxSelectMark(selected: Boolean, modifier: Modifier = Modifier) {
    val c = Hx.colors
    val fill by androidx.compose.animation.core.animateFloatAsState(if (selected) 1f else 0f, HxMotion.pop(), label = "selectMark")
    val ring by androidx.compose.animation.animateColorAsState(if (selected) c.accent else c.textFaint.copy(alpha = .6f), tween(HxMotion.Medium), label = "selectRing")
    Box(
        modifier.size(22.dp).clip(CircleShape).border(1.6.dp, ring, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(22.dp)
                .graphicsLayer { scaleX = fill; scaleY = fill; alpha = fill.coerceIn(0f, 1f) }
                .clip(CircleShape)
                .background(c.accent),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.Check, null, tint = c.onAccent, modifier = Modifier.size(14.dp))
        }
    }
}


private data class HxOutlineEntry(val label: String, val line: Int, val level: Int)

/** Top-level keys plus named entries under the list/map sections that matter in Mihomo YAML. */
private fun hxYamlOutline(text: String): List<HxOutlineEntry> {
    val top = Regex("^([A-Za-z0-9_.-]+):")
    val named = Regex("^\\s*-\\s*(?:\\{\\s*)?name:\\s*[\"']?([^\"',}]+)")
    val mapChild = Regex("^  ([^\\s#][^:]*):\\s*$")
    val out = ArrayList<HxOutlineEntry>()
    var section = ""
    text.lineSequence().forEachIndexed { index, line ->
        if (out.size >= 600) return@forEachIndexed
        top.find(line)?.let { match ->
            section = match.groupValues[1]
            out.add(HxOutlineEntry(section, index, 0))
            return@forEachIndexed
        }
        when (section) {
            "proxies", "proxy-groups" -> named.find(line)?.let { out.add(HxOutlineEntry(it.groupValues[1].trim(), index, 1)) }
            "proxy-providers", "rule-providers" -> mapChild.find(line)?.let { out.add(HxOutlineEntry(it.groupValues[1].trim(), index, 1)) }
        }
    }
    return out
}
