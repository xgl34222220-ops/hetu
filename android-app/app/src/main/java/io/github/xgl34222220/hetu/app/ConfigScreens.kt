package io.github.xgl34222220.hetu

import io.github.xgl34222220.hetu.home.homeDiffuseCanvas
import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.ViewModelProvider
import io.github.rosemoe.sora.event.ContentChangeEvent
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.schemes.SchemeDarcula
import io.github.rosemoe.sora.widget.schemes.SchemeGitHub
import io.github.rosemoe.sora.widget.subscribeAlways
import io.github.xgl34222220.hetu.home.HomeButton
import io.github.xgl34222220.hetu.home.HomeButtonKind
import io.github.xgl34222220.hetu.home.HomeCardTitle
import io.github.xgl34222220.hetu.home.HomeDims
import io.github.xgl34222220.hetu.home.HomeEmptyState
import io.github.xgl34222220.hetu.home.HomeFormField
import io.github.xgl34222220.hetu.home.HomeHaptic
import io.github.xgl34222220.hetu.home.HomeIconButton
import io.github.xgl34222220.hetu.home.HomeIcons
import io.github.xgl34222220.hetu.home.HomeMotion
import io.github.xgl34222220.hetu.home.HomePill
import io.github.xgl34222220.hetu.home.HomePop
import io.github.xgl34222220.hetu.home.HomeReveal
import io.github.xgl34222220.hetu.home.HomeRowLayout
import io.github.xgl34222220.hetu.home.HomeRowSubStyle
import io.github.xgl34222220.hetu.home.HomeSegmentStyle
import io.github.xgl34222220.hetu.home.HomeSegmented
import io.github.xgl34222220.hetu.home.HomeTone
import io.github.xgl34222220.hetu.home.HomeType
import io.github.xgl34222220.hetu.home.LocalHomeColors
import io.github.xgl34222220.hetu.home.LocalHomeHaptics
import io.github.xgl34222220.hetu.home.LocalHomeMotionEnabled
import io.github.xgl34222220.hetu.home.badText
import io.github.xgl34222220.hetu.home.homeRowPressTint
import io.github.xgl34222220.hetu.home.warnText
import io.github.xgl34222220.hetu.panel.PanelIcons
import io.github.xgl34222220.hetu.tools.ToolsIcons
import io.github.xgl34222220.hetu.ui.ht
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
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
    val c = LocalHomeColors.current
    var revision by remember { mutableIntStateOf(0) }
    var configs by remember { mutableStateOf<List<ProxyConfigUi>>(emptyList()) }
    var subscriptions by remember { mutableStateOf<List<ProxySubscriptionUi>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var menuFor by remember { mutableStateOf<ProxyConfigUi?>(null) }
    var confirmDelete by remember { mutableStateOf<ProxyConfigUi?>(null) }
    var renameFor by remember { mutableStateOf<ProxyConfigUi?>(null) }
    var exportFor by remember { mutableStateOf<ProxyConfigUi?>(null) }
    var form by remember { mutableStateOf<String?>(null) }
    var editSub by remember { mutableStateOf<ProxySubscriptionUi?>(null) }
    var deleteSub by remember { mutableStateOf<ProxySubscriptionUi?>(null) }
    val haptics = io.github.xgl34222220.hetu.ui.rememberHetuHaptics()

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

    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/yaml")) { uri ->
        val config = exportFor
        exportFor = null
        if (uri != null && config != null) perform("已导出 ${config.name}", false) {
            withContext(Dispatchers.IO) {
                val library = ProxyConfigLibrary(context)
                val entry = library.list(ProxyRuntimeProfile.load(vm.prefs).core).firstOrNull { it.name == config.name }
                    ?: error("配置不存在")
                val content = library.read(entry)
                val output = context.contentResolver.openOutputStream(uri) ?: error("无法写入导出文件")
                output.use { it.write(content.toByteArray(Charsets.UTF_8)) }
            }
        }
    }

    if (form == "import") {
        ConfigImportPage(vm, onBack = { form = null }) {
            revision++
            scope.launch { vm.refreshNow() }
            vm.applyConfigChange("配置已导入并设为当前")
            form = null
        }
        return
    }
    if (form == "subscription") {
        ConfigSubscriptionPage(vm, editSub, onBack = { form = null; editSub = null }) {
            revision++
            scope.launch { vm.refreshNow() }
            vm.applyConfigChange(if (editSub == null) "订阅已添加" else "订阅已保存")
            form = null
            editSub = null
        }
        return
    }

    HxPage(
        title = ht("配置与订阅"),
        largeTitle = false,
        subtitle = ht("管理源配置与当前配置中的订阅链接。"),
        onBack = { nav.pop() },
        actions = { HxBarAction(ToolsIcons.Plus, "导入配置", onClick = { form = "import" }, busy = busy) },
    ) {
        item(key = "configs") {
            HxSection {
                HxGroup(title = ht("配置管理")) {
                    if (loading && configs.isEmpty()) {
                        Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { HxSpinner(22.dp) }
                    }
                    configs.forEach { config ->
                        // Tap to make it the current config; hold, or use ···, for everything else.
                        HxRow(
                            config.name,
                            subtitle = ht(when {
                                config.selected -> "当前配置"
                                config.bundled -> "本地配置 / 内置模板 · 需要填写订阅"
                                else -> "本地配置"
                            }),
                            icon = if (config.bundled) ToolsIcons.FileCog else ToolsIcons.FileText,
                            selected = config.selected,
                            onClick = { if (!config.selected) perform("已切换到 ${config.name}", true) { vm.controller.selectConfig(config.name) } },
                            onLongClick = { if (!busy) menuFor = config },
                        ) {
                            HomePop(config.selected) { Icon(HomeIcons.CircleCheck, ht("当前配置"), Modifier.size(22.dp), tint = c.accent) }
                            HxBarAction(ToolsIcons.Ellipsis, "更多", onClick = { menuFor = config }, enabled = !busy, anchorMenu = true)
                        }
                    }
                    HxDivider()
                    HxNavRow(ht("编辑当前 YAML"), icon = HxIcons.SquarePen, onClick = { nav.push(HxRoute.ConfigEditor) })
                }
            }
        }
        item(key = "subs") {
            HxSection {
                HxGroup {
                    HomeCardTitle(ht("订阅管理")) {
                        HomeIconButton(ToolsIcons.Plus, "添加订阅", { editSub = null; form = "subscription" }, enabled = !busy)
                    }
                    Text(ht("当前配置的 proxy-providers。"), Modifier.padding(start = 18.dp, end = 18.dp, bottom = 6.dp), color = c.t2, style = HomeRowSubStyle)
                    if (subscriptions.isEmpty() && !loading) {
                        HxRow(ht("当前配置没有订阅段"), subtitle = ht("完整的 YAML 配置无需订阅；如需添加，请在编辑器中加入 proxy-providers"), icon = PanelIcons.Unlink)
                    }
                    subscriptions.forEachIndexed { index, sub ->
                        if (index > 0) HxDivider()
                        HxRow(
                            sub.name,
                            subtitle = if (sub.placeholder) ht("尚未填写订阅链接") else maskUrl(sub.url),
                            icon = ToolsIcons.Link,
                            onClick = { editSub = sub; form = "subscription" },
                        ) {
                            HxChevron()
                            HomeIconButton(ToolsIcons.Trash2, "删除订阅", { deleteSub = sub }, enabled = !busy, tint = c.bad, glyph = 22.dp)
                        }
                    }
                    Text(ht("配置切换后将应用到当前运行状态。"), Modifier.padding(start = 18.dp, end = 18.dp, top = 8.dp, bottom = 12.dp), color = c.t3, style = HomeType.caption)
                }
            }
        }
    }

    menuFor?.let { config ->
        HxActionMenu(
            title = config.name,
            referenceFileMenu = true,
            headerIcon = if (config.bundled) ToolsIcons.FileCog else ToolsIcons.FileText,
            actions = buildList {
                if (!config.selected) add(HxMenuAction(ht("设为当前配置"), HomeIcons.CircleCheck) {
                    menuFor = null
                    perform("已切换到 ${config.name}", true) { vm.controller.selectConfig(config.name) }
                })
                add(HxMenuAction(ht("导出配置"), ToolsIcons.Share) { menuFor = null; exportFor = config; launchDocumentPicker(vm::toast) { exporter.launch(config.name) } })
                if (!config.bundled) {
                    add(HxMenuAction(ht("重命名"), HxIcons.TextCursorInput) { menuFor = null; renameFor = config })
                    add(HxMenuAction(ht("删除配置"), ToolsIcons.Trash2, danger = true) { menuFor = null; confirmDelete = config })
                }
            },
            onDismiss = { menuFor = null },
        )
    }
    renameFor?.let { config ->
        HxFormDialog(
            title = "重命名配置", fields = listOf(HxField("名称", config.name)), confirmLabel = "重命名",
            validate = { values ->
                val name = values[0]
                when {
                    name == config.name -> "名称没有变化"
                    else -> runCatching {
                        ProxyConfigLibrary.safeName(name)
                        require(ProxyConfigLibrary.coreAccepts(ProxyRuntimeProfile.load(vm.prefs).core, name)) { "当前核心不支持该配置格式" }
                    }.exceptionOrNull()?.message
                }
            },
            onConfirm = { values ->
                renameFor = null
                perform("配置已重命名", false) {
                    withContext(Dispatchers.IO) {
                        val library = ProxyConfigLibrary(context)
                        val entry = library.list(ProxyRuntimeProfile.load(vm.prefs).core).firstOrNull { it.name == config.name }
                            ?: error("配置不存在")
                        library.rename(entry, values[0])
                    }
                }
            },
            onDismiss = { renameFor = null },
        )
    }
    confirmDelete?.let { config ->
        HxConfirmDialog(
            title = "删除配置？",
            message = "「${config.name}」将被永久删除。" + if (config.selected) "删除后会切换回内置模板。" else "",
            confirmLabel = "删除", danger = true,
            onConfirm = {
                confirmDelete = null
                perform("已删除", applyToRuntime = config.selected) { vm.controller.deleteConfig(config.name) }
            },
            onDismiss = { confirmDelete = null },
        )
    }
    deleteSub?.let { sub ->
        HxConfirmDialog(
            title = "删除订阅？", message = "从当前配置中移除「${sub.name}」及其在策略组中的引用。",
            confirmLabel = "删除", danger = true,
            onConfirm = { deleteSub = null; perform("订阅已删除", true) { vm.controller.deleteSubscription(sub.name) } },
            onDismiss = { deleteSub = null },
        )
    }
}

/** Forms are their own pages in the reference, so drafts can be reviewed before applying. */
@Composable
private fun ConfigSubscriptionPage(vm: HetuViewModel, subscription: ProxySubscriptionUi?, onBack: () -> Unit, onSaved: () -> Unit) {
    val scope = rememberCoroutineScope()
    val initialUrl = subscription?.takeUnless { it.placeholder }?.url.orEmpty()
    var name by remember(subscription?.name) { mutableStateOf(subscription?.name.orEmpty()) }
    var url by remember(subscription?.name) { mutableStateOf(initialUrl) }
    var attempted by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    var discard by remember { mutableStateOf(false) }
    val dirty = name != subscription?.name.orEmpty() || url != initialUrl
    fun leave() { if (!busy) { if (dirty) discard = true else onBack() } }
    fun save() {
        attempted = true
        if (name.isBlank() || !hxConfigHttpUrl(url) || busy) return
        busy = true
        problem = null
        scope.launch {
            try {
                if (subscription == null) vm.controller.addSubscription(name.trim(), url.trim())
                else vm.controller.updateSubscription(subscription.name, url.trim())
                onSaved()
            } catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) { problem = failure.message ?: "保存失败" }
            finally { busy = false }
        }
    }
    BackHandler { leave() }
    HxPage(title = if (subscription == null) ht("添加订阅") else ht("编辑订阅"), largeTitle = false, onBack = ::leave) {
        item("form") {
            HxSection {
                HxCard {
                    HomeFormField(
                        "订阅名称", name, { name = it; problem = null }, placeholder = "例如 主订阅",
                        error = if (attempted && name.isBlank()) ht("名称不能为空") else null,
                        hint = if (subscription == null) null else "名称用于引用订阅，可在 YAML 编辑器中统一修改。",
                        readOnly = subscription != null, enabled = !busy,
                    )
                    Spacer(Modifier.height(22.dp))
                    HomeFormField(
                        "订阅链接", url, { url = it; problem = null }, placeholder = "https://example.com/subscription",
                        error = if (attempted && !hxConfigHttpUrl(url)) ht("请输入有效的 http/https 链接") else null,
                        hint = "保存到当前配置，运行时会尝试应用。", enabled = !busy, clearable = true, keyboardType = KeyboardType.Uri,
                    )
                    HomeReveal(problem != null) { HxBanner(problem.orEmpty(), HxTone.Bad, Modifier.padding(top = 14.dp)) }
                    Spacer(Modifier.height(26.dp))
                    HxButton("保存", ::save, Modifier.fillMaxWidth(), icon = HomeIcons.Save, busy = busy)
                    ConfigCancelButton(::leave, !busy)
                }
            }
        }
    }
    if (discard) HxConfirmDialog("放弃填写？", "已填写的内容尚未保存，返回后将丢失这些修改。", "放弃", danger = true,
        onConfirm = { discard = false; onBack() }, onDismiss = { discard = false })
}

@Composable
private fun ConfigImportPage(vm: HetuViewModel, onBack: () -> Unit, onImported: () -> Unit) {
    val context = LocalContext.current
    val c = LocalHomeColors.current
    val scope = rememberCoroutineScope()
    var mode by remember { mutableStateOf("file") }
    var uri by remember { mutableStateOf<Uri?>(null) }
    var fileName by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var attempted by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    var discard by remember { mutableStateOf(false) }
    val dirty = uri != null || url.isNotBlank() || name.isNotBlank()
    val chooser = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { selected ->
        if (selected != null) { uri = selected; fileName = displayName(context, selected); problem = null }
    }
    fun leave() { if (!busy) { if (dirty) discard = true else onBack() } }
    fun import() {
        attempted = true
        if (busy || (mode == "link" && !hxConfigHttpUrl(url)) || (mode == "file" && uri == null)) return
        busy = true
        problem = null
        scope.launch {
            try {
                if (mode == "file") vm.controller.importConfig(requireNotNull(uri), fileName)
                else downloadConfig(context, url.trim(), name.trim())
                onImported()
            } catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) { problem = failure.message ?: "配置导入失败" }
            finally { busy = false }
        }
    }
    BackHandler { leave() }
    HxPage(title = ht("导入配置"), largeTitle = false, onBack = ::leave) {
        item("mode") {
            HxSection {
                HomeSegmented(
                    listOf("file" to ht("从文件导入"), "link" to ht("从链接导入")), mode,
                    { mode = it; attempted = false; problem = null },
                    Modifier.fillMaxWidth(), enabled = !busy, style = HomeSegmentStyle.Soft, track = c.surface, height = 56.dp, corner = 20.dp,
                    textStyle = HomeType.button, icons = mapOf("file" to ToolsIcons.FileText, "link" to ToolsIcons.Link), inset = 5.dp,
                )
            }
        }
        item("form") {
            HxSection {
                HxCard {
                    if (mode == "link") {
                        HomeFormField(
                            "配置链接", url, { url = it; problem = null }, placeholder = "https://example.com/config.yaml",
                            error = if (attempted && !hxConfigHttpUrl(url)) ht("请输入有效的 http/https 链接") else null,
                            enabled = !busy, clearable = true, keyboardType = KeyboardType.Uri,
                        )
                        Spacer(Modifier.height(22.dp))
                        HomeFormField("配置名称（可选）", name, { name = it; problem = null }, placeholder = "例如 旅行.yaml", enabled = !busy)
                    } else {
                        Text(ht("配置文件"), Modifier.padding(horizontal = 2.dp), color = c.t1, style = HomeType.cardLabel)
                        Spacer(Modifier.height(8.dp))
                        // The chosen file, or an invitation to choose one: the whole well is the button.
                        val source = remember { MutableInteractionSource() }
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 64.dp).clip(HomeDims.controlShape).background(if (uri == null) c.sunken else c.accentSoft)
                                .homeRowPressTint(source)
                                .clickable(interactionSource = source, indication = null, enabled = !busy, role = Role.Button) {
                                    launchDocumentPicker(vm::toast) { chooser.launch(arrayOf("*/*")) }
                                }
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(if (uri == null) HxIcons.FolderOpen else ToolsIcons.FileText, null, Modifier.size(24.dp), tint = if (uri == null) c.t2 else c.accent)
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                if (uri != null) Text(fileName, color = c.t1, style = HomeType.cardLabel, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(ht(if (uri == null) "选择配置文件" else "重新选择文件"), color = if (uri == null) c.t1 else c.accent,
                                    style = if (uri == null) HomeType.cardLabel else HomeType.caption.copy(fontWeight = FontWeight.SemiBold))
                            }
                            HxChevron()
                        }
                        HomeReveal(attempted && uri == null) {
                            Row(Modifier.padding(start = 2.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Icon(HomeIcons.CircleAlert, null, Modifier.size(16.dp), tint = c.bad)
                                Text(ht("请选择配置文件"), color = c.badText, style = HomeType.note.copy(fontWeight = FontWeight.Medium))
                            }
                        }
                    }
                    Row(Modifier.padding(top = 18.dp), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(HomeIcons.Info, null, Modifier.padding(top = 1.dp).size(18.dp), tint = c.t3)
                        Text(ht("导入后设为当前配置。支持 UTF-8，最大 4 MiB。") + if (mode == "link") ht("链接需直接返回 YAML 文件。") else "", color = c.t2, style = HomeType.note)
                    }
                    HomeReveal(problem != null) { HxBanner(problem.orEmpty(), HxTone.Bad, Modifier.padding(top = 14.dp)) }
                }
            }
        }
        item("submit") {
            HxSection {
                HxButton("导入配置", ::import, Modifier.fillMaxWidth(), busy = busy, icon = if (mode == "file") ToolsIcons.Download else null)
                ConfigCancelButton(::leave, !busy)
            }
        }
    }
    if (discard) HxConfirmDialog("放弃填写？", "已填写的内容尚未保存，返回后将丢失这些修改。", "放弃", danger = true,
        onConfirm = { discard = false; onBack() }, onDismiss = { discard = false })
}

@Composable
private fun ConfigCancelButton(onClick: () -> Unit, enabled: Boolean) {
    HomeButton("取消", onClick, Modifier.fillMaxWidth().padding(top = 6.dp), kind = HomeButtonKind.Ghost, enabled = enabled)
}

internal fun hxConfigHttpUrl(raw: String): Boolean = runCatching {
    val value = raw.trim()
    val uri = java.net.URI(value)
    (uri.scheme == "http" || uri.scheme == "https") && !uri.host.isNullOrBlank() &&
        value.length <= 4096 && value.none { it.isWhitespace() || it.isISOControl() }
}.getOrDefault(false)

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
        val bytes = connection.inputStream.use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (output.size() + read > 4 * 1024 * 1024) throw IOException("配置超过 4 MiB")
                output.write(buffer, 0, read)
            }
            output.toByteArray()
        }
        val text = String(bytes, Charsets.UTF_8)
        if (!text.contains("proxies") && !text.contains("proxy-providers")) {
            throw IOException("下载内容不是 Clash/Mihomo YAML 配置（可能是 Base64 节点列表，请改用「添加订阅」）")
        }
        val profile = ProxyRuntimeProfile.load(context.getSharedPreferences("hetu", Context.MODE_PRIVATE))
        ProxyConfigLibrary(context).importConfig(profile.core, name, bytes.inputStream())
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
        title = ht("订阅流量"),
        largeTitle = false,
        subtitle = if (vm.state.running) ht("来自运行中 Mihomo 的 proxy-providers") else ht("代理未运行"),
        onBack = { nav.pop() },
    ) {
        if (!vm.state.running) {
            item(key = "stopped") { HxEmpty(HxIcons.CloudDownload, "代理未运行", "启动后可查看订阅流量并更新节点") }
        } else {
            providerListItems(vm)
        }
    }
}

/* ------------------------------------------------------------------ */
/*  YAML editor                                                         */
/* ------------------------------------------------------------------ */

private val HxYamlSymbols = listOf("-", "[", "]", "{", "}", "#", "'", ":", "Tab", "\"", "true", "false", "|")

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

private data class HxConfigSource(val core: ProxyRuntimeProfile.Core, val name: String, val text: String)

private suspend fun hxReadConfigSource(context: Context): HxConfigSource = withContext(Dispatchers.IO) {
    val prefs = context.getSharedPreferences("hetu", Context.MODE_PRIVATE)
    val core = ProxyRuntimeProfile.load(prefs).core
    val library = ProxyConfigLibrary(context)
    val entry = library.selected(core) ?: error("尚未选择配置")
    HxConfigSource(core, entry.name, library.read(entry))
}

private class HxConfigSourceConflict(val selectionChanged: Boolean, val selectedName: String) : IOException()

@Composable
internal fun ConfigEditorScreen(vm: HetuViewModel, onBackOverride: (() -> Unit)? = null) {
    val nav = if (onBackOverride == null) LocalNav.current else null
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val c = LocalHomeColors.current
    var source by remember { mutableStateOf<HxConfigSource?>(null) }
    var editor by remember { mutableStateOf<CodeEditor?>(null) }
    var loadRevision by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var loadFailure by remember { mutableStateOf<String?>(null) }
    var dirty by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var validating by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    var conflict by remember { mutableStateOf<HxConfigSourceConflict?>(null) }
    var confirmLeave by remember { mutableStateOf(false) }
    var confirmReload by remember { mutableStateOf(false) }
    var showOutline by remember { mutableStateOf(false) }
    val haptics = io.github.xgl34222220.hetu.ui.rememberHetuHaptics()
    val editorColors = remember(c.dark, c.surface) {
        HetuYamlLanguage.colors(if (c.dark) SchemeDarcula() else SchemeGitHub(), c.dark).apply {
            val background = c.surface.toArgb()
            setColor(io.github.rosemoe.sora.widget.schemes.EditorColorScheme.WHOLE_BACKGROUND, background)
            setColor(io.github.rosemoe.sora.widget.schemes.EditorColorScheme.LINE_NUMBER_BACKGROUND, background)
            if (!c.dark) setColor(HetuYamlLanguage.YAML_KEY, 0xFF005CFF.toInt())
        }
    }

    LaunchedEffect(loadRevision) {
        loading = true
        loadFailure = null
        source = null
        try {
            source = hxReadConfigSource(context)
            dirty = false
            problem = null
        } catch (cancel: CancellationException) { throw cancel }
        catch (failure: Exception) { loadFailure = failure.message ?: "配置读取失败" }
        finally { loading = false }
    }

    fun leave() {
        if (saving) return
        if (dirty) confirmLeave = true
        else if (onBackOverride != null) onBackOverride() else nav?.pop()
    }
    fun reload() {
        conflict = null
        if (dirty) confirmReload = true else loadRevision++
    }
    BackHandler { leave() }

    // Returning from another editor must never silently replace this page's draft.
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycle) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                val expected = source
                if (expected != null && !loading && !saving) scope.launch {
                    try {
                        val latest = hxReadConfigSource(context)
                        if (latest.core != expected.core || latest.name != expected.name)
                            conflict = HxConfigSourceConflict(true, latest.name)
                        else if (latest.text != expected.text) conflict = HxConfigSourceConflict(false, latest.name)
                    } catch (cancel: CancellationException) { throw cancel }
                    catch (_: Exception) { /* Explicit reload exposes read errors; retain the draft. */ }
                }
            }
        }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer) }
    }

    fun validate() {
        val current = editor?.text?.toString() ?: return
        if (validating || saving) return
        validating = true
        scope.launch {
            try { vm.controller.validateConfigText(current); problem = null; vm.toast("校验通过") }
            catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) { problem = "配置无效：${error.message ?: "校验失败"}" }
            finally { validating = false }
        }
    }

    // 保存前预览：本次改动与源文件的差异；确认后经事务写入（先备份，运行中热重载，核心拒绝时回滚源文件）。
    var savePreview by remember { mutableStateOf<Pair<String, ConfigDiff>?>(null) }

    fun save() {
        val expected = source ?: return
        val current = editor?.text?.toString() ?: return
        if (saving || validating) return
        saving = true
        scope.launch {
            try {
                val latest = hxReadConfigSource(context)
                if (latest.core != expected.core || latest.name != expected.name) throw HxConfigSourceConflict(true, latest.name)
                if (latest.text != expected.text) throw HxConfigSourceConflict(false, latest.name)
                ConfigText.inspect(current).error?.let { throw IOException(it) }
                vm.controller.validateConfigText(current)
                val diff = withContext(Dispatchers.Default) { ConfigDiffer.diff(expected.text, current) }
                problem = null
                savePreview = current to diff
            } catch (cancel: CancellationException) { throw cancel }
            catch (changed: HxConfigSourceConflict) { conflict = changed }
            catch (error: Exception) { problem = error.message ?: "保存失败" }
            finally { saving = false }
        }
    }

    fun commit(current: String) {
        val expected = source ?: return
        if (saving) return
        saving = true
        savePreview = null
        scope.launch {
            try {
                val core = ProxyRuntimeProfile.load(vm.prefs).core
                if (core != expected.core) throw HxConfigSourceConflict(true, expected.name)
                // The core already validated this exact text in save(); the transaction re-checks the file under it.
                val transaction = ConfigTransaction(
                    store = LibraryConfigStore(context), backups = LibraryConfigStore.backups(context),
                    validate = { },
                    runningIdle = { if (!vm.state.running) false else if (vm.operation != null) null else true },
                    reload = { vm.controller.reload() },
                )
                val result = try {
                    transaction.save(expected.name, current, expected = expected.text)
                } catch (conflicted: ConfigConflictException) {
                    throw HxConfigSourceConflict(false, expected.name)
                }
                if (result is ConfigApplyResult.RolledBack) {
                    problem = result.message
                } else {
                    source = expected.copy(text = current)
                    dirty = editor?.text?.toString() != current
                    problem = null
                    vm.toast(result.message)
                }
                vm.refreshNow()
            } catch (cancel: CancellationException) { throw cancel }
            catch (changed: HxConfigSourceConflict) { conflict = changed }
            catch (error: Exception) { problem = error.message ?: "保存失败" }
            finally { saving = false }
        }
    }

    val ready = source != null && !loading && !saving
    Column(Modifier.fillMaxSize().homeDiffuseCanvas().statusBarsPadding().navigationBarsPadding().imePadding()) {
        Box(Modifier.fillMaxWidth().height(HxTopBarHeight).padding(horizontal = 6.dp)) {
            HomeIconButton(HomeIcons.ChevronLeft, "返回", ::leave, Modifier.align(Alignment.CenterStart), glyph = 26.dp)
            Text(ht("编辑配置"), Modifier.align(Alignment.Center).semantics { heading() }, color = c.t1, style = HomeType.barTitle)
            HomeIconButton(
                HomeIcons.Save, "保存", ::save, Modifier.align(Alignment.CenterEnd),
                enabled = dirty && !saving && !validating, loading = saving, tint = if (dirty) c.accent else c.t3,
            )
        }
        // Which file this is, and whether what is on screen has been written to it.
        Box(Modifier.fillMaxWidth().padding(horizontal = HomeDims.gutter).clip(HomeDims.cardShape).background(c.surface)) {
            HomeRowLayout(
                AnnotatedString(source?.name ?: ht("读取配置")),
                subtitle = ht(when { loading -> "正在读取当前配置"; loadFailure != null -> "读取配置失败，保存操作已禁用"; dirty -> "未保存 · 草稿仅保留在本页"; else -> "已保存 · 当前配置" }),
                icon = ToolsIcons.FileCog,
                iconTint = if (source == null) c.t3 else c.t1,
                subtitleColor = if (dirty) c.warnText else c.t2,
                trailing = { HomePop(dirty) { HomePill(ht("未保存"), tone = HomeTone.Warn) } },
            )
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = HomeDims.gutter, vertical = 10.dp).clip(HomeDims.cardShape).background(c.surface).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ConfigEditorAction(ToolsIcons.Undo2, "撤销", Modifier.weight(1f), ready) { editor?.let { if (it.canUndo()) it.undo() } }
            ConfigEditorAction(ToolsIcons.Redo2, "重做", Modifier.weight(1f), ready) { editor?.let { if (it.canRedo()) it.redo() } }
            ConfigEditorAction(PanelIcons.ListTree, "语法大纲", Modifier.weight(1f), ready) { showOutline = true }
            ConfigEditorAction(HomeIcons.CircleCheck, "校验", Modifier.weight(1f), ready && !validating, validating, ::validate)
        }
        HomeReveal(problem != null) {
            HxBanner(problem.orEmpty(), tone = HxTone.Bad,
                modifier = Modifier.padding(horizontal = HomeDims.gutter).padding(bottom = 10.dp),
                actionLabel = "关闭提示", onAction = { problem = null })
        }
        Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = HomeDims.gutter).clip(HomeDims.cardShape).background(c.surface)) {
            val initial = source
            when {
                loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { HxSpinner(26.dp) }
                loadFailure != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    HomeEmptyState(HomeIcons.CircleAlert, "配置读取失败", loadFailure.orEmpty(), topPadding = 0.dp, verbatimSubtitle = true) {
                        HxButton("重新读取", { loadRevision++ }, icon = HomeIcons.RefreshCw)
                    }
                }
                initial != null -> androidx.compose.runtime.key(loadRevision) {
                    AndroidView(
                        factory = { viewContext ->
                            CodeEditor(viewContext).apply {
                                setEditorLanguage(HetuYamlLanguage())
                                setText(initial.text)
                                setEditable(true)
                                setSoftKeyboardEnabled(true)
                                typefaceText = Typeface.MONOSPACE
                                setTextSize(14f)
                                setLineSpacingMultiplier(1.4f)
                                setLineNumberMarginLeft(18f * resources.displayMetrics.density)
                                setDividerMargin(8f * resources.displayMetrics.density, 14f * resources.displayMetrics.density)
                                setDividerWidth(0f)
                                setLineNumberEnabled(true)
                                setWordwrap(false)
                                setTabWidth(2)
                                setHighlightCurrentLine(true)
                                colorScheme = editorColors
                                subscribeAlways<ContentChangeEvent> { dirty = this.text.toString() != source?.text }
                                editor = this
                            }
                        },
                        modifier = Modifier.fillMaxSize().padding(vertical = 10.dp),
                        update = { native -> if (native.colorScheme !== editorColors) native.colorScheme = editorColors },
                        onRelease = { native -> if (editor === native) editor = null; native.release() },
                    )
                }
            }
        }
        // The characters YAML needs and a phone keyboard hides, one tap each.
        Row(Modifier.fillMaxWidth().padding(horizontal = HomeDims.gutter, vertical = 10.dp)
            .clip(HomeDims.cardShape).background(c.surface)
            .horizontalScroll(rememberScrollState()).padding(8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            HxYamlSymbols.forEach { symbol ->
                val keySource = remember { MutableInteractionSource() }
                Box(Modifier.heightIn(min = 44.dp).widthIn(min = 44.dp).clip(RoundedCornerShape(13.dp)).background(c.sunken)
                    .homeRowPressTint(keySource)
                    .clickable(interactionSource = keySource, indication = null, enabled = ready, role = Role.Button) {
                        haptics.perform(io.github.xgl34222220.hetu.ui.HetuHaptic.Tick)
                        editor?.let { hxApplyYamlSymbol(it, symbol) }
                    }.padding(horizontal = 10.dp), contentAlignment = Alignment.Center) {
                    Text(symbol, color = if (source == null) c.t3 else c.t1, style = HomeType.mono.copy(fontSize = 15.sp, fontWeight = FontWeight.SemiBold))
                }
            }
        }
    }

    if (showOutline) {
        val current = editor?.text?.toString().orEmpty()
        val outline = remember(current) { hxYamlOutline(current) }
        HxSheet(onDismiss = { showOutline = false }, title = ht("语法大纲")) {
            val close = LocalHxSheetClose.current
            if (outline.isEmpty()) Text(ht("没有识别到顶层字段"), Modifier.padding(horizontal = 20.dp, vertical = 12.dp), color = c.t2, style = HomeType.body)
            androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxWidth().heightIn(max = 520.dp).padding(horizontal = 8.dp)) {
                items(outline.size) { index ->
                    val entry = outline[index]
                    val rowSource = remember { MutableInteractionSource() }
                    // Top-level keys read as headings; the entries under them are indented.
                    Row(Modifier.fillMaxWidth().heightIn(min = if (entry.level == 0) 48.dp else 42.dp).clip(RoundedCornerShape(14.dp)).homeRowPressTint(rowSource)
                        .clickable(interactionSource = rowSource, indication = null, role = Role.Button) {
                            close {
                                showOutline = false
                                editor?.let { native -> runCatching { native.setSelection(entry.line, 0); native.ensureSelectionVisible(); native.requestFocus() } }
                            }
                        }.padding(start = if (entry.level == 0) 12.dp else 30.dp, end = 10.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(entry.label, Modifier.weight(1f), color = if (entry.level == 0) c.t1 else c.t2, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            style = if (entry.level == 0) HomeType.cardLabel else HomeType.note.copy(fontSize = 15.sp))
                        Text("${entry.line + 1}", color = c.t3, style = HomeType.mono.copy(fontSize = 12.sp))
                        Spacer(Modifier.width(8.dp)); HxChevron()
                    }
                }
            }
        }
    }
    conflict?.let { changed ->
        HxSheet(onDismiss = { conflict = null }, title = if (changed.selectionChanged) ht("保存遇到冲突") else ht("文件已在其他位置修改")) {
            Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 6.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(ht(if (changed.selectionChanged) "当前配置已发生变化，无法保存。" else "源文件已发生变化，无法保存。"), color = c.t1, style = HomeType.body)
                Text(if (changed.selectionChanged) "当前选择为「${changed.selectedName}」。你仍可保留草稿，重新读取会打开当前配置。"
                    else ht("你的草稿仍保留。可以继续编辑，或确认放弃草稿后读取最新内容。"), color = c.t2, style = HomeType.note)
                Spacer(Modifier.height(6.dp))
                HxButton("保留草稿", { conflict = null }, Modifier.fillMaxWidth(), filled = false)
                HxButton("重新读取", ::reload, Modifier.fillMaxWidth(), icon = HomeIcons.RefreshCw)
            }
        }
    }
    savePreview?.let { (pending, diff) ->
        HxSheet(onDismiss = { savePreview = null }, title = ht("保存前确认")) {
            Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 6.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    if (diff.identical) ht("内容没有变化。") else "+${diff.added} / −${diff.removed} " + ht("行") + " · " + ht("原配置会先备份，可在「导入配置 › 备份与恢复」找回"),
                    color = c.t2, style = HomeType.note,
                )
                ConfigDiffPreview(diff)
                Text(ht(if (vm.state.running) "代理运行中：保存后立即热重载；核心拒绝时自动恢复原配置。" else "代理未运行：保存后下次启动生效。"), color = c.t2, style = HomeType.note)
                HxButton("保存并应用", { commit(pending) }, Modifier.fillMaxWidth(), icon = HomeIcons.Save, enabled = !diff.identical)
                HxButton("继续编辑", { savePreview = null }, Modifier.fillMaxWidth(), filled = false)
            }
        }
    }
    if (confirmReload) HxConfirmDialog("放弃草稿并重新读取？",
        "重新读取后，本页未保存的修改将被替换。该操作读取当前配置的最新内容，不会覆盖源文件。", "放弃并读取", danger = true,
        onConfirm = { confirmReload = false; dirty = false; loadRevision++ }, onDismiss = { confirmReload = false })
    if (confirmLeave) HxConfirmDialog("放弃修改？", "编辑的修改没有保存。", "放弃", danger = true,
        onConfirm = { confirmLeave = false; dirty = false; if (onBackOverride != null) onBackOverride() else nav?.pop() },
        onDismiss = { confirmLeave = false })
}

/** One tool of the editor: a glyph over its name; a spinner takes the glyph's place while it works. */
@Composable
private fun ConfigEditorAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, modifier: Modifier,
    enabled: Boolean, busy: Boolean = false, onClick: () -> Unit) {
    val c = LocalHomeColors.current
    val haptics = LocalHomeHaptics.current
    val source = remember { MutableInteractionSource() }
    Column(
        modifier.heightIn(min = 62.dp).padding(vertical = 4.dp).clip(RoundedCornerShape(16.dp)).homeRowPressTint(source)
            .clickable(interactionSource = source, indication = null, enabled = enabled && !busy, role = Role.Button) { haptics(HomeHaptic.Tap); onClick() }
            .alpha(if (enabled || busy) 1f else .4f)
            .padding(vertical = 7.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterVertically),
    ) {
        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
            if (busy) HxSpinner(20.dp) else Icon(icon, null, Modifier.size(22.dp), tint = c.t1)
        }
        Text(ht(label), color = c.t2, style = HomeType.badge.copy(fontWeight = FontWeight.Medium), maxLines = 1)
    }
}

/** Selection mark for single-choice lists: a ring that fills with a check. */
@Composable
internal fun HxSelectMark(selected: Boolean, modifier: Modifier = Modifier) {
    val c = LocalHomeColors.current
    val motion = LocalHomeMotionEnabled.current
    val fill by androidx.compose.animation.core.animateFloatAsState(if (selected) 1f else 0f, HomeMotion.pop(motion), label = "selectMark")
    val ring by androidx.compose.animation.animateColorAsState(if (selected) c.accent else c.t3, HomeMotion.fade(motion), label = "selectRing")
    Box(
        modifier.size(24.dp).clip(CircleShape).border(1.8.dp, ring, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(24.dp)
                .graphicsLayer { scaleX = fill; scaleY = fill; alpha = fill.coerceIn(0f, 1f) }
                .clip(CircleShape)
                .background(c.accent),
            contentAlignment = Alignment.Center,
        ) {
            Icon(HomeIcons.Check, null, Modifier.size(15.dp), tint = c.onAccent)
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
