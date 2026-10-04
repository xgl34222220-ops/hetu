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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.automirrored.rounded.Redo
import androidx.compose.material.icons.automirrored.rounded.FormatListBulleted
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.Check
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
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
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.CheckCircleOutline
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.IosShare
import androidx.compose.material.icons.rounded.DriveFileRenameOutline
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CloudSync
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.FactCheck
import androidx.compose.material.icons.rounded.FileOpen
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.MoreHoriz
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
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
    val c = Hx.colors
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
        title = "配置与订阅",
        largeTitle = false,
        compactTitleFontSizeSp = 20f,
        subtitle = "管理源配置与当前配置中的订阅链接。",
        onBack = { nav.pop() },
        actions = {
            if (busy) Box(Modifier.padding(12.dp)) { HxSpinner(18.dp) }
            else HxBarAction(Icons.Rounded.Add, "导入配置", onClick = { form = "import" })
        },
    ) {
        item(key = "configs") {
            HxSection {
                HxGroup {
                    HxRow("配置管理", icon = Icons.Rounded.FolderOpen, referenceRow = true)
                    if (loading && configs.isEmpty()) {
                        Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { HxSpinner() }
                    }
                    configs.forEach { config ->
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 2.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (config.selected) c.accentSoft else Color.Transparent)
                                .hxAnchorSource()
                                .hxCombinedClick(
                                    enabled = !busy,
                                    onLongClick = { haptics.perform(io.github.xgl34222220.hetu.ui.HetuHaptic.LongPress); menuFor = config },
                                    onClick = {
                                        if (!config.selected) perform("已切换到 ${config.name}", true) { vm.controller.selectConfig(config.name) }
                                    },
                                ).padding(start = 14.dp, end = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(settingsLineIcon(Icons.Rounded.Description), null, tint = c.text, modifier = Modifier.size(24.dp))
                            Spacer(Modifier.width(16.dp))
                            Column(Modifier.weight(1f).padding(vertical = 9.dp)) {
                                Text(config.name, style = MaterialTheme.typography.bodyLarge.copy(fontSize = 16.sp, lineHeight = 21.sp),
                                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, color = c.text,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(when {
                                    config.selected -> "当前配置"
                                    config.bundled -> "本地配置 / 内置模板 · 需要填写订阅"
                                    else -> "本地配置"
                                }, style = MaterialTheme.typography.bodySmall, color = c.textMuted)
                            }
                            if (config.selected) Icon(Icons.Rounded.CheckCircle, "当前配置", tint = c.accent, modifier = Modifier.size(22.dp))
                            IconButton(onClick = { menuFor = config }, enabled = !busy, modifier = Modifier.hxAnchorSource()) {
                                Icon(Icons.Rounded.MoreHoriz, "更多", tint = c.text)
                            }
                        }
                    }
                    HorizontalDivider(Modifier.padding(horizontal = 14.dp, vertical = 4.dp), thickness = .5.dp, color = c.line.copy(alpha = .4f))
                    Row(Modifier.fillMaxWidth().clickable { nav.push(HxRoute.ConfigEditor) }
                        .padding(horizontal = 20.dp, vertical = 15.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Edit, null, tint = c.accent, modifier = Modifier.size(24.dp))
                        Spacer(Modifier.width(20.dp))
                        Text("编辑当前 YAML", style = MaterialTheme.typography.bodyLarge.copy(fontSize = 16.sp, lineHeight = 21.sp),
                            fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, color = c.accent)
                    }
                }
            }
        }
        item(key = "subs") {
            HxSection {
                HxGroup {
                    HxRow("订阅管理", subtitle = "当前配置的 proxy-providers。", icon = Icons.Rounded.Link, referenceRow = true) {
                        IconButton(onClick = { editSub = null; form = "subscription" }, enabled = !busy) {
                            Icon(Icons.Rounded.Add, "添加订阅", tint = c.text)
                        }
                    }
                    if (subscriptions.isEmpty() && !loading) {
                        HxRow("当前配置没有订阅段", subtitle = "完整的 YAML 配置无需订阅；如需添加，请在编辑器中加入 proxy-providers", icon = Icons.Rounded.CloudSync)
                    }
                    subscriptions.forEach { sub ->
                        HxRow(sub.name,
                            referenceRow = true,                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp).clip(RoundedCornerShape(12.dp)).background(c.surface),
                            subtitle = if (sub.placeholder) "尚未填写订阅链接" else maskUrl(sub.url),
                            icon = Icons.Rounded.Link,
                            onClick = { editSub = sub; form = "subscription" },
                        ) {
                            HxChevron()
                            IconButton(onClick = { deleteSub = sub }, enabled = !busy) {
                                Icon(Icons.Rounded.DeleteOutline, "删除订阅", tint = c.bad, modifier = Modifier.size(22.dp))
                            }
                        }
                    }
                    Text("配置切换后将应用到当前运行状态。", style = MaterialTheme.typography.bodySmall,
                        color = c.textMuted, modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp))
                }
            }
        }
    }

    menuFor?.let { config ->
        HxActionMenu(
            title = config.name,
            referenceFileMenu = true,
            anchorEndInset = 2.dp,
            actions = buildList {
                if (!config.selected) add(HxMenuAction("设为当前配置", Icons.Rounded.CheckCircle) {
                    menuFor = null
                    perform("已切换到 ${config.name}", true) { vm.controller.selectConfig(config.name) }
                })
                add(HxMenuAction("导出配置", Icons.Rounded.IosShare) { menuFor = null; exportFor = config; launchDocumentPicker(vm::toast) { exporter.launch(config.name) } })
                if (!config.bundled) {
                    add(HxMenuAction("重命名", Icons.Rounded.DriveFileRenameOutline) { menuFor = null; renameFor = config })
                    add(HxMenuAction("删除配置", Icons.Rounded.DeleteOutline, danger = true) { menuFor = null; confirmDelete = config })
                }
            },
            onDismiss = { menuFor = null },
        )
    }
    renameFor?.let { config ->
        HxFormDialog(
            title = "重命名配置", configFooter = true, fields = listOf(HxField("名称", config.name)),
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
            confirmLabel = "删除", danger = true, presentation = HxConfirmStyle.Text,
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
            confirmLabel = "删除", danger = true, presentation = HxConfirmStyle.SoftRow,
            onConfirm = { deleteSub = null; perform("订阅已删除", true) { vm.controller.deleteSubscription(sub.name) } },
            onDismiss = { deleteSub = null },
        )
    }
}

/** Forms are their own pages in the reference, so drafts can be reviewed before applying. */
@Composable
private fun ConfigSubscriptionPage(vm: HetuViewModel, subscription: ProxySubscriptionUi?, onBack: () -> Unit, onSaved: () -> Unit) {
    val c = Hx.colors
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
    HxPage(title = if (subscription == null) "添加订阅" else "编辑订阅", largeTitle = false, onBack = ::leave) {
        item("form") {
            HxSection {
                HxCard(padding = androidx.compose.foundation.layout.PaddingValues(15.dp)) {
                    if (subscription == null) ConfigFormField("订阅名称", name, { name = it; problem = null }, "例如 主订阅",
                        if (attempted && name.isBlank()) "名称不能为空" else null)
                    else {
                        Text("订阅名称", style = MaterialTheme.typography.bodySmall, color = c.textMuted)
                        Text(subscription.name, style = MaterialTheme.typography.titleMedium, color = c.text, modifier = Modifier.padding(top = 4.dp))
                        Text("名称用于引用订阅，可在 YAML 编辑器中统一修改。", style = MaterialTheme.typography.bodySmall,
                            color = c.textMuted, modifier = Modifier.padding(top = 6.dp))
                    }
                    Spacer(Modifier.height(28.dp))
                    ConfigFormField("订阅链接", url, { url = it; problem = null }, "https://example.com/subscription",
                        if (attempted && !hxConfigHttpUrl(url)) "请输入有效的 http/https 链接" else null)
                    Text("保存到当前配置，运行时会尝试应用。", style = MaterialTheme.typography.bodySmall,
                        color = c.textMuted, modifier = Modifier.padding(top = 10.dp))
                    if (problem != null) HxBanner(problem.orEmpty(), HxTone.Bad, Modifier.padding(top = 10.dp))
                    Spacer(Modifier.height(48.dp))
                    HxButton("保存", ::save, Modifier.fillMaxWidth(), icon = Icons.Rounded.Save, busy = busy)
                    ConfigCancelButton(::leave, !busy)
                }
            }
        }
    }
    if (discard) HxConfirmDialog("放弃填写？", "已填写的内容尚未保存，返回后将丢失这些修改。", "放弃", danger = true, presentation = HxConfirmStyle.DestructiveStack,
        onConfirm = { discard = false; onBack() }, onDismiss = { discard = false })
}

@Composable
private fun ConfigImportPage(vm: HetuViewModel, onBack: () -> Unit, onImported: () -> Unit) {
    val context = LocalContext.current
    val c = Hx.colors
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
    HxPage(title = "导入配置", largeTitle = false, onBack = ::leave) {
        item("mode") {
            HxSection {
                HxCard(padding = androidx.compose.foundation.layout.PaddingValues(6.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOf("file" to "从文件导入", "link" to "从链接导入").forEach { (id, label) ->
                            Row(Modifier.weight(1f).height(44.dp).clip(RoundedCornerShape(18.dp))
                                .background(if (id == mode) c.accentSoft else Color.Transparent)
                                .clickable(enabled = !busy) { mode = id; attempted = false; problem = null },
                                horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                                Icon(settingsLineIcon(if (id == "file") Icons.Rounded.Description else Icons.Rounded.Link), null,
                                    tint = if (id == mode) c.accent else c.text, modifier = Modifier.size(22.dp))
                                Spacer(Modifier.width(10.dp))
                                Text(label, color = if (id == mode) c.accent else c.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
            }
        }
        item("form") {
            HxSection {
                HxCard(padding = androidx.compose.foundation.layout.PaddingValues(18.dp)) {
                    if (mode == "link") {
                        ConfigFormField("配置链接", url, { url = it; problem = null }, "https://example.com/config.yaml",
                            if (attempted && !hxConfigHttpUrl(url)) "请输入有效的 http/https 链接" else null)
                        Spacer(Modifier.height(28.dp))
                        ConfigFormField("配置名称（可选）", name, { name = it; problem = null }, "例如 旅行.yaml")
                    } else {
                        Text("配置文件", style = MaterialTheme.typography.titleSmall, color = c.text)
                        if (uri != null) HxRow(fileName, icon = Icons.Rounded.Description)
                        HxNavRow(if (uri == null) "选择配置文件" else "重新选择文件", icon = Icons.Rounded.FolderOpen) {
                            if (!busy) launchDocumentPicker(vm::toast) { chooser.launch(arrayOf("*/*")) }
                        }
                        if (attempted && uri == null) Text("请选择配置文件", style = MaterialTheme.typography.bodySmall, color = c.bad)
                    }
                    Row(Modifier.padding(top = 16.dp), verticalAlignment = Alignment.Top) {
                        Icon(Icons.Rounded.Info, null, tint = c.textMuted, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(10.dp))
                        Text("导入后设为当前配置。支持 UTF-8，最大 4 MiB。" + if (mode == "link") "链接需直接返回 YAML 文件。" else "",
                            style = MaterialTheme.typography.bodySmall, color = c.textMuted)
                    }
                    if (problem != null) HxBanner(problem.orEmpty(), HxTone.Bad, Modifier.padding(top = 10.dp))
                }
            }
        }
        item("submit") {
            HxSection {
                HxButton("导入配置", ::import, Modifier.fillMaxWidth(), busy = busy,
                    icon = if (mode == "file") Icons.Rounded.FileDownload else null)
                ConfigCancelButton(::leave, !busy)
            }
        }
    }
    if (discard) HxConfirmDialog("放弃填写？", "已填写的内容尚未保存，返回后将丢失这些修改。", "放弃", danger = true, presentation = HxConfirmStyle.DestructiveStack,
        onConfirm = { discard = false; onBack() }, onDismiss = { discard = false })
}

@Composable
private fun ConfigFormField(label: String, value: String, onValueChange: (String) -> Unit, placeholder: String, error: String? = null) {
    val c = Hx.colors
    Text(label, style = MaterialTheme.typography.titleSmall, color = c.text)
    Spacer(Modifier.height(8.dp))
    HxTextField(value, onValueChange, Modifier.fillMaxWidth(), placeholder = { Text(placeholder, color = c.textFaint) }, singleLine = true)
    if (error != null) {
        Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.ErrorOutline, null, tint = c.bad, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(4.dp))
            Text(error, style = MaterialTheme.typography.bodySmall, color = c.bad)
        }
    }
}

@Composable
private fun ConfigCancelButton(onClick: () -> Unit, enabled: Boolean) {
    Box(Modifier.fillMaxWidth().height(52.dp).clickable(enabled = enabled, onClick = onClick), contentAlignment = Alignment.Center) {
        Text("取消", style = MaterialTheme.typography.labelLarge, color = Hx.colors.accent)
    }
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
    val c = Hx.colors
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
                vm.controller.validateConfigText(current)
                withContext(Dispatchers.IO) {
                    // Validation may take time. Recheck identity and contents immediately before writing.
                    val library = ProxyConfigLibrary(context)
                    val core = ProxyRuntimeProfile.load(vm.prefs).core
                    val entry = library.selected(core) ?: error("尚未选择配置")
                    if (core != expected.core || entry.name != expected.name) throw HxConfigSourceConflict(true, entry.name)
                    if (library.read(entry) != expected.text) throw HxConfigSourceConflict(false, entry.name)
                    library.write(entry, current)
                }
                source = expected.copy(text = current)
                dirty = editor?.text?.toString() != current
                problem = null
                vm.applyConfigChange("配置已保存")
            } catch (cancel: CancellationException) { throw cancel }
            catch (changed: HxConfigSourceConflict) { conflict = changed }
            catch (error: Exception) { problem = error.message ?: "保存失败" }
            finally { saving = false }
        }
    }

    Column(Modifier.fillMaxSize().background(c.canvas).statusBarsPadding().navigationBarsPadding().imePadding()) {
        Box(Modifier.fillMaxWidth().height(HxTopBarHeight)) {
            IconButton(onClick = ::leave, modifier = Modifier.align(Alignment.CenterStart)) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回", tint = c.text)
            }
            Text("编辑配置", style = MaterialTheme.typography.titleMedium, color = c.text, modifier = Modifier.align(Alignment.Center))
            IconButton(onClick = ::save, enabled = dirty && !saving && !validating,
                modifier = Modifier.align(Alignment.CenterEnd)) {
                if (saving) HxSpinner() else Icon(Icons.Rounded.Save, "保存", tint = if (dirty) c.accent else c.textFaint)
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = Hx.gutter).padding(top = 4.dp)
            .clip(RoundedCornerShape(18.dp)).background(c.surface).padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Description, null, tint = if (source == null) c.textFaint else c.text, modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(source?.name ?: "读取配置", style = MaterialTheme.typography.titleMedium, color = c.text,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(when { loading -> "正在读取当前配置"; loadFailure != null -> "读取配置失败，保存操作已禁用"; dirty -> "未保存 · 草稿仅保留在本页"; else -> "已保存 · 当前配置" },
                    style = MaterialTheme.typography.bodySmall, color = if (dirty) c.warn else c.textMuted)
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = Hx.gutter, vertical = 10.dp)
            .clip(RoundedCornerShape(18.dp)).background(c.surface), verticalAlignment = Alignment.CenterVertically) {
            val enabled = source != null && !loading && !saving
            ConfigEditorAction(Icons.AutoMirrored.Rounded.Undo, "撤销", Modifier.weight(1f), enabled) { editor?.let { if (it.canUndo()) it.undo() } }
            ConfigEditorAction(Icons.AutoMirrored.Rounded.Redo, "重做", Modifier.weight(1f), enabled) { editor?.let { if (it.canRedo()) it.redo() } }
            Box(Modifier.width(.5.dp).height(26.dp).background(c.line))
            ConfigEditorAction(Icons.AutoMirrored.Rounded.FormatListBulleted, "语法大纲", Modifier.weight(1.3f), enabled) { showOutline = true }
            Box(Modifier.width(.5.dp).height(26.dp).background(c.line))
            ConfigEditorAction(Icons.Rounded.CheckCircleOutline, "校验", Modifier.weight(1.2f), enabled && !validating, validating, ::validate)
        }
        AnimatedVisibility(visible = problem != null) {
            HxBanner(problem.orEmpty(), tone = HxTone.Bad,
                modifier = Modifier.padding(horizontal = Hx.gutter).padding(bottom = 8.dp),
                actionLabel = "关闭提示", onAction = { problem = null })
        }
        Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = Hx.gutter)
            .clip(RoundedCornerShape(18.dp)).background(c.surface)) {
            val initial = source
            when {
                loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { HxSpinner(26.dp) }
                loadFailure != null -> Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Rounded.ErrorOutline, null, tint = c.textFaint, modifier = Modifier.size(48.dp))
                    Spacer(Modifier.height(14.dp))
                    Text("配置读取失败", style = MaterialTheme.typography.bodyMedium, color = c.textMuted)
                    Text(loadFailure.orEmpty(), style = MaterialTheme.typography.bodySmall, color = c.textFaint,
                        modifier = Modifier.padding(top = 6.dp, bottom = 16.dp))
                    HxButton("重新读取", { loadRevision++ }, icon = Icons.Rounded.Refresh)
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
        Row(Modifier.fillMaxWidth().padding(horizontal = Hx.gutter, vertical = 8.dp)
            .clip(RoundedCornerShape(18.dp)).background(c.surface)
            .horizontalScroll(rememberScrollState()).padding(7.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            HxYamlSymbols.forEach { symbol ->
                Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(c.surfaceMuted)
                    .clickable(enabled = source != null && !loading && !saving) {
                        haptics.perform(io.github.xgl34222220.hetu.ui.HetuHaptic.Tick)
                        editor?.let { hxApplyYamlSymbol(it, symbol) }
                    }, contentAlignment = Alignment.Center) {
                    Text(symbol, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelLarge,
                        color = if (source == null) c.textFaint else c.text)
                }
            }
        }
    }

    if (showOutline) {
        val current = editor?.text?.toString().orEmpty()
        val outline = remember(current) { hxYamlOutline(current) }
        HxSheet(onDismiss = { showOutline = false }, title = "语法大纲") {
            val close = LocalHxSheetClose.current
            if (outline.isEmpty()) Text("没有识别到顶层字段", style = MaterialTheme.typography.bodyMedium, color = c.textMuted,
                modifier = Modifier.padding(horizontal = 22.dp, vertical = 12.dp))
            androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxWidth().heightIn(max = 520.dp)) {
                items(outline.size) { index ->
                    val entry = outline[index]
                    Row(Modifier.fillMaxWidth().clickable {
                        close {
                            showOutline = false
                            editor?.let { native -> runCatching { native.setSelection(entry.line, 0); native.ensureSelectionVisible(); native.requestFocus() } }
                        }
                    }.padding(start = if (entry.level == 0) 22.dp else 40.dp, end = 22.dp, top = 9.dp, bottom = 9.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(entry.label, style = MaterialTheme.typography.bodyMedium, color = if (entry.level == 0) c.accent else c.textMuted,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Text("${entry.line + 1}", style = MaterialTheme.typography.labelSmall.merge(HxNumberStyle), color = c.textFaint)
                        Spacer(Modifier.width(10.dp)); HxChevron()
                    }
                }
            }
        }
    }
    conflict?.let { changed ->
        HxSheet(onDismiss = { conflict = null }, title = if (changed.selectionChanged) "保存遇到冲突" else "文件已在其他位置修改") {
            Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(if (changed.selectionChanged) "当前配置已发生变化，无法保存。" else "源文件已发生变化，无法保存。",
                    style = MaterialTheme.typography.bodyMedium, color = c.textMuted)
                Text(if (changed.selectionChanged) "当前选择为「${changed.selectedName}」。你仍可保留草稿，重新读取会打开当前配置。"
                    else "你的草稿仍保留。可以继续编辑，或确认放弃草稿后读取最新内容。", style = MaterialTheme.typography.bodySmall, color = c.textMuted)
                HxButton("保留草稿", { conflict = null }, Modifier.fillMaxWidth(), filled = false)
                HxButton("重新读取", ::reload, Modifier.fillMaxWidth(), icon = Icons.Rounded.Refresh)
            }
        }
    }
    if (confirmReload) HxConfirmDialog("放弃草稿并重新读取？",
        "重新读取后，本页未保存的修改将被替换。该操作读取当前配置的最新内容，不会覆盖源文件。", "放弃并读取", danger = true, presentation = HxConfirmStyle.SoftStack,
        onConfirm = { confirmReload = false; dirty = false; loadRevision++ }, onDismiss = { confirmReload = false })
    if (confirmLeave) HxConfirmDialog("放弃修改？", "编辑的修改没有保存。", "放弃", danger = true, presentation = HxConfirmStyle.SoftStack,
        onConfirm = { confirmLeave = false; dirty = false; if (onBackOverride != null) onBackOverride() else nav?.pop() },
        onDismiss = { confirmLeave = false })
}

@Composable
private fun ConfigEditorAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, modifier: Modifier,
    enabled: Boolean, busy: Boolean = false, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled && !busy, modifier = modifier.height(52.dp)) {
        if (busy) HxSpinner() else Icon(icon, label, tint = if (enabled) Hx.colors.text else Hx.colors.textFaint, modifier = Modifier.size(24.dp))
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
