package io.github.xgl34222220.hetu

import android.content.Context
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
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.platform.testTag
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Download
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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

private sealed interface ConfigWorkflowPage {
    data object Library : ConfigWorkflowPage
    data object Import : ConfigWorkflowPage
    data class Subscription(val snapshot: ProxyConfigLibrary.SubscriptionEditSnapshot, val subscription: ProxySubscriptionUi?) : ConfigWorkflowPage
}

@Composable
internal fun ConfigsScreen(vm: HetuViewModel) {
    val nav = LocalNav.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val c = Hx.colors
    val motion = LocalHxMotionEnabled.current
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    var revision by remember { mutableIntStateOf(0) }
    var configs by remember { mutableStateOf<List<ProxyConfigUi>>(emptyList()) }
    var subscriptions by remember { mutableStateOf<List<ProxySubscriptionUi>>(emptyList()) }
    var subscriptionSnapshot by remember { mutableStateOf<ProxyConfigLibrary.SubscriptionEditSnapshot?>(null) }
    var loading by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var page by remember { mutableStateOf<ConfigWorkflowPage>(ConfigWorkflowPage.Library) }
    var workflowError by remember { mutableStateOf<String?>(null) }
    var menuFor by remember { mutableStateOf<ProxyConfigUi?>(null) }
    var confirmDelete by remember { mutableStateOf<ProxyConfigUi?>(null) }
    var renameConfig by remember { mutableStateOf<ProxyConfigUi?>(null) }
    var exportBytes by remember { mutableStateOf<ByteArray?>(null) }
    var deleteSub by remember { mutableStateOf<ConfigWorkflowPage.Subscription?>(null) }
    val haptics = io.github.xgl34222220.hetu.ui.rememberHetuHaptics()

    LaunchedEffect(revision) {
        loading = true
        subscriptionSnapshot = null
        subscriptions = emptyList()
        try {
            configs = vm.controller.configLibrary()
            val opened = vm.controller.subscriptionEditSnapshot()
            subscriptions = opened.subscriptions.map { ProxySubscriptionUi(it.name, it.url, it.placeholder) }
            subscriptionSnapshot = opened
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            vm.toast(error.message ?: "配置读取失败")
        } finally {
            loading = false
        }
    }

    fun perform(
        action: String,
        applyToRuntime: Boolean,
        closeWorkflow: Boolean = false,
        block: suspend () -> Any?,
    ) {
        if (busy) return
        busy = true
        workflowError = null
        scope.launch {
            try {
                val committed = block()
                revision++
                if (closeWorkflow) page = ConfigWorkflowPage.Library
                vm.refreshNow()
                // Bound subscription mutations return only source identity/revision,
                // never their URLs or YAML. Other actions keep their existing path.
                if (applyToRuntime) vm.applyConfigChange(action, committed as? ProxyConfigLibrary.SourceVersion) else vm.toast(action)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                if (closeWorkflow) workflowError = error.message ?: "操作失败，请重试"
                else vm.toast(error.message ?: "操作失败")
            } finally {
                busy = false
            }
        }
    }

    fun open(next: ConfigWorkflowPage) {
        if (busy) return
        workflowError = null
        page = next
    }

    fun openSubscription(subscription: ProxySubscriptionUi?) {
        val opened = subscriptionSnapshot ?: return
        open(ConfigWorkflowPage.Subscription(opened, subscription))
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

    androidx.compose.animation.AnimatedContent(
        targetState = page,
        transitionSpec = {
            if (!motion) androidx.compose.animation.EnterTransition.None togetherWith androidx.compose.animation.ExitTransition.None
            else (fadeIn(tween(HxMotion.Medium)) + androidx.compose.animation.slideInHorizontally(tween(HxMotion.Long, easing = HxMotion.Emphasized)) {
                if (targetState == ConfigWorkflowPage.Library) -it / 7 else it / 7
            })
                .togetherWith(fadeOut(tween(HxMotion.Short)))
                .using(androidx.compose.animation.SizeTransform(clip = false))
        },
        label = "configWorkflow",
    ) { shown ->
        when (shown) {
            ConfigWorkflowPage.Import -> ConfigImportPage(
                busy = busy,
                active = page == shown,
                error = workflowError,
                onBack = { open(ConfigWorkflowPage.Library) },
                onFileImport = { uri, name ->
                    perform("配置已导入并设为当前", applyToRuntime = true, closeWorkflow = true) {
                        vm.controller.importConfig(uri, name)
                    }
                },
                onLinkImport = { url, name ->
                    perform("配置已下载并设为当前", applyToRuntime = true, closeWorkflow = true) {
                        downloadConfig(context, url, name)
                    }
                },
            )
            is ConfigWorkflowPage.Subscription -> ConfigSubscriptionPage(
                subscription = shown.subscription,
                busy = busy,
                active = page == shown,
                error = workflowError,
                onBack = { open(ConfigWorkflowPage.Library) },
                onSave = { name, url ->
                    perform(if (shown.subscription == null) "订阅已添加" else "订阅已保存", applyToRuntime = true, closeWorkflow = true) {
                        if (shown.subscription == null) vm.controller.addSubscription(shown.snapshot, name, url)
                        else vm.controller.updateSubscription(shown.snapshot, shown.subscription.name, url)
                    }
                },
            )
            ConfigWorkflowPage.Library -> HxPage(
                title = "配置与订阅",
                largeTitle = false,
                subtitle = "管理源配置与当前配置中的订阅链接",
                listState = listState,
                onBack = { if (!busy) nav.pop() },
                actions = {
                    HxBarAction(Icons.Rounded.Add, "导入配置", onClick = { open(ConfigWorkflowPage.Import) }, busy = busy, enabled = !busy)
                },
            ) {
                item(key = "configs") {
                    HxSection {
                        HxCard(Modifier.fillMaxWidth().testTag("config-library-card")) {
                            Text("配置管理", style = MaterialTheme.typography.titleMedium, color = c.text)
                            Spacer(Modifier.height(12.dp))
                            if (loading && configs.isEmpty()) {
                                Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { HxSpinner() }
                            }
                            configs.forEachIndexed { index, config ->
                                if (index > 0) Spacer(Modifier.height(8.dp))
                                Row(
                                    Modifier.fillMaxWidth().testTag("config-entry-${config.name}")
                                        .clip(Hx.rowShape)
                                        .background(if (config.selected) c.accentSoft else c.surfaceMuted.copy(alpha = .55f))
                                        .border(1.dp, if (config.selected) c.accent.copy(alpha = .3f) else androidx.compose.ui.graphics.Color.Transparent, Hx.rowShape)
                                        .hxAnchorSource()
                                        .hxCombinedClick(
                                            enabled = !busy,
                                            onLongClick = { haptics.perform(io.github.xgl34222220.hetu.ui.HetuHaptic.LongPress); menuFor = config },
                                            onClick = {
                                                if (!config.selected) perform("已切换到 ${config.name}", applyToRuntime = true) { vm.controller.selectConfig(config.name) }
                                            },
                                        )
                                        .padding(start = 12.dp, top = 10.dp, bottom = 10.dp, end = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(Icons.Rounded.Description, null, tint = if (config.selected) c.accent else c.textMuted, modifier = Modifier.size(24.dp))
                                    Spacer(Modifier.width(12.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(config.name, style = MaterialTheme.typography.bodyLarge, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                                            color = c.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                        Text(if (config.selected) "当前配置" else "本地配置", style = MaterialTheme.typography.bodySmall, color = if (config.selected) c.accent else c.textMuted)
                                        if (config.bundled) Text("内置模板 · 需要填写订阅", style = MaterialTheme.typography.bodySmall, color = c.textMuted)
                                    }
                                    IconButton(onClick = { menuFor = config }, enabled = !busy, modifier = Modifier.size(48.dp).hxAnchorSource()) {
                                        Icon(Icons.Rounded.MoreHoriz, "更多 ${config.name}", tint = c.textMuted)
                                    }
                                }
                            }
                            Spacer(Modifier.height(12.dp))
                            HxButton("编辑当前 YAML", onClick = { nav.push(HxRoute.ConfigEditor) }, icon = Icons.Rounded.Edit,
                                enabled = !busy && configs.any { it.selected }, filled = false, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp))
                        }
                    }
                }
                item(key = "subs") {
                    HxSection {
                        HxCard(Modifier.fillMaxWidth().testTag("config-subscriptions-card")) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("订阅管理", style = MaterialTheme.typography.titleMedium, color = c.text, modifier = Modifier.weight(1f))
                                HxBarAction(Icons.Rounded.Add, "添加订阅", onClick = { openSubscription(null) }, enabled = !busy && !loading && subscriptionSnapshot != null)
                            }
                            Text("当前配置的 proxy-providers", style = MaterialTheme.typography.bodySmall, color = c.textMuted)
                            Spacer(Modifier.height(12.dp))
                            if (subscriptions.isEmpty() && !loading) {
                                Text("当前配置没有订阅段。完整 YAML 可直接使用；添加订阅前，请在 YAML 中保留 proxy-providers 段。", style = MaterialTheme.typography.bodyMedium, color = c.textMuted)
                            }
                            subscriptions.forEachIndexed { index, sub ->
                                if (index > 0) Spacer(Modifier.height(8.dp))
                                Row(
                                    Modifier.fillMaxWidth().testTag("config-subscription-${sub.name}").clip(Hx.rowShape)
                                        .background(c.surfaceMuted.copy(alpha = .55f))
                                        .clickable(enabled = !busy && !loading) { openSubscription(sub) }
                                        .padding(start = 12.dp, top = 10.dp, bottom = 10.dp, end = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(Icons.Rounded.Link, null, tint = if (sub.placeholder) c.warn else c.accent, modifier = Modifier.size(24.dp))
                                    Spacer(Modifier.width(12.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(sub.name, style = MaterialTheme.typography.bodyLarge, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, color = c.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                        Text(if (sub.placeholder) "尚未填写订阅链接" else maskUrl(sub.url), style = MaterialTheme.typography.bodySmall, color = c.textMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    }
                                    IconButton(onClick = { subscriptionSnapshot?.let { deleteSub = ConfigWorkflowPage.Subscription(it, sub) } }, enabled = !busy && !loading, modifier = Modifier.size(48.dp)) {
                                        Icon(Icons.Rounded.DeleteOutline, "删除订阅 ${sub.name}", tint = c.textFaint)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // A library mutation must finish before the user leaves and cancels its coroutine.
    BackHandler(enabled = busy && page == ConfigWorkflowPage.Library) { }

    menuFor?.let { config ->
        HxActionMenu(
            title = config.name,
            actions = buildList {
                if (!config.selected) add(HxMenuAction("设为当前配置", Icons.Rounded.CheckCircle) {
                    menuFor = null
                    perform("已切换到 ${config.name}", applyToRuntime = true) { vm.controller.selectConfig(config.name) }
                })
                add(HxMenuAction("导出配置", Icons.Rounded.FileOpen) {
                    menuFor = null
                    perform("请选择导出位置", applyToRuntime = false) {
                        exportBytes = vm.controller.exportConfig(config.name)
                        exportFile.launch(config.name)
                    }
                })
                if (!config.bundled) add(HxMenuAction("重命名", Icons.Rounded.Edit) { menuFor = null; renameConfig = config })
                if (!config.bundled) add(HxMenuAction("删除配置", Icons.Rounded.DeleteOutline, danger = true) { menuFor = null; confirmDelete = config })
            },
            onDismiss = { menuFor = null },
        )
    }
    renameConfig?.let { config ->
        HxFormDialog(title = "重命名配置", fields = listOf(HxField("名称", config.name)),
            validate = { values -> if (values[0].isBlank()) "名称不能为空" else null },
            onConfirm = { values ->
                renameConfig = null
                perform("已重命名", applyToRuntime = false) { vm.controller.renameConfig(config.name, values[0].trim()) }
            }, onDismiss = { renameConfig = null })
    }
    confirmDelete?.let { config ->
        HxConfirmDialog(title = "删除配置？", message = "「${config.name}」将被永久删除。" + if (config.selected) "删除后会切换回内置模板。" else "",
            confirmLabel = "删除", danger = true,
            onConfirm = { confirmDelete = null; perform("已删除", applyToRuntime = false) { vm.controller.deleteConfig(config.name) } },
            onDismiss = { confirmDelete = null })
    }
    deleteSub?.let { opened ->
        val sub = requireNotNull(opened.subscription)
        HxConfirmDialog(title = "删除订阅？", message = "从「${opened.snapshot.source.name}」中移除「${sub.name}」及其在策略组中的引用。", confirmLabel = "删除", danger = true,
            onConfirm = { deleteSub = null; perform("订阅已删除", applyToRuntime = true) { vm.controller.deleteSubscription(opened.snapshot, sub.name) } },
            onDismiss = { deleteSub = null })
    }
}

/** Form destinations are ordinary pages; cancelling the document picker never imports. */
@Composable
internal fun ConfigImportPage(
    busy: Boolean,
    error: String?,
    onBack: () -> Unit,
    onFileImport: (Uri, String) -> Unit,
    onLinkImport: (String, String) -> Unit,
    active: Boolean = true,
) {
    val context = LocalContext.current
    val c = Hx.colors
    var fromLink by remember { mutableStateOf(false) }
    var url by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var selectedFile by remember { mutableStateOf<Uri?>(null) }
    var selectedName by remember { mutableStateOf("") }
    var formError by remember { mutableStateOf<String?>(null) }
    var confirmLeave by remember { mutableStateOf(false) }
    val dirty = url.isNotBlank() || name.isNotBlank() || selectedFile != null
    fun leave() { if (!busy) { if (dirty) confirmLeave = true else onBack() } }
    BackHandler(enabled = active) { leave() }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) { selectedFile = uri; selectedName = displayName(context, uri); formError = null }
    }
    Box(Modifier.fillMaxSize().imePadding().testTag("config-import-page")) {
        HxPage(title = "导入配置", largeTitle = false, onBack = ::leave, showScrollTop = false) {
            item(key = "import-kind") {
                HxSection {
                    val wide = androidx.compose.ui.platform.LocalDensity.current.fontScale < 1.5f
                    if (wide) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ConfigFormTab("从文件导入", !fromLink, Icons.Rounded.FileOpen, Modifier.weight(1f), !busy) { fromLink = false; formError = null }
                        ConfigFormTab("从链接导入", fromLink, Icons.Rounded.Link, Modifier.weight(1f), !busy) { fromLink = true; formError = null }
                    } else Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        ConfigFormTab("从文件导入", !fromLink, Icons.Rounded.FileOpen, Modifier.fillMaxWidth(), !busy) { fromLink = false; formError = null }
                        ConfigFormTab("从链接导入", fromLink, Icons.Rounded.Link, Modifier.fillMaxWidth(), !busy) { fromLink = true; formError = null }
                    }
                }
            }
            item(key = "import-form") {
                HxSection {
                    HxCard(Modifier.fillMaxWidth()) {
                        if (fromLink) {
                            ConfigFormField("配置链接", url, "https://", "config-import-url", busy, urlKeyboard = true) { url = it; formError = null }
                            Spacer(Modifier.height(16.dp))
                            ConfigFormField("配置名称（可选）", name, "例如 旅行.yaml", "config-import-name", busy) { name = it; formError = null }
                        } else {
                            Text("配置文件", style = MaterialTheme.typography.titleSmall, color = c.text)
                            Spacer(Modifier.height(8.dp))
                            Text(selectedName.ifBlank { "选择设备上的 YAML 配置文件" }, style = MaterialTheme.typography.bodyMedium, color = c.textMuted)
                            Spacer(Modifier.height(12.dp))
                            HxButton(if (selectedFile == null) "选择文件" else "重新选择文件", onClick = { picker.launch(arrayOf("*/*")) },
                                icon = Icons.Rounded.FileOpen, enabled = !busy, filled = false, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp))
                        }
                        Spacer(Modifier.height(18.dp))
                        Text("导入后设为当前配置。支持 UTF-8，最大 4 MiB。" +
                            if (fromLink) "链接需直接返回 YAML 文件。" else "",
                            style = MaterialTheme.typography.bodySmall, color = c.textMuted)
                        (formError ?: error)?.let {
                            Spacer(Modifier.height(12.dp))
                            Text(it, style = MaterialTheme.typography.bodyMedium, color = c.bad, modifier = Modifier.testTag("config-form-error"))
                        }
                    }
                }
            }
            item(key = "import-action") {
                HxSection {
                    HxButton("导入配置", onClick = {
                        if (!busy) {
                            if (fromLink) {
                                if (!configHttpUrl(url)) formError = "请输入有效的 http/https 链接"
                                else onLinkImport(url.trim(), name.trim())
                            } else selectedFile?.let { onFileImport(it, selectedName) }
                        }
                    }, icon = Icons.Rounded.Download, busy = busy, enabled = !busy && (fromLink || selectedFile != null),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("config-import-submit"))
                    Spacer(Modifier.height(8.dp))
                    HxButton("取消", onClick = ::leave, enabled = !busy, filled = false, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp))
                }
            }
        }
    }
    if (confirmLeave) ConfigDiscardFormDialog(onDiscard = { confirmLeave = false; onBack() }, onDismiss = { confirmLeave = false })
}

@Composable
internal fun ConfigSubscriptionPage(
    subscription: ProxySubscriptionUi?,
    busy: Boolean,
    error: String?,
    onBack: () -> Unit,
    onSave: (String, String) -> Unit,
    active: Boolean = true,
) {
    val c = Hx.colors
    val initialUrl = subscription?.takeUnless { it.placeholder }?.url.orEmpty()
    var name by remember(subscription) { mutableStateOf(subscription?.name.orEmpty()) }
    var url by remember(subscription) { mutableStateOf(initialUrl) }
    var formError by remember { mutableStateOf<String?>(null) }
    var confirmLeave by remember { mutableStateOf(false) }
    val dirty = name != subscription?.name.orEmpty() || url != initialUrl
    fun leave() { if (!busy) { if (dirty) confirmLeave = true else onBack() } }
    BackHandler(enabled = active) { leave() }
    Box(Modifier.fillMaxSize().imePadding().testTag("config-subscription-page")) {
        HxPage(title = if (subscription == null) "添加订阅" else "编辑订阅", largeTitle = false, onBack = ::leave, showScrollTop = false) {
            item(key = "subscription-form") {
                HxSection {
                    HxCard(Modifier.fillMaxWidth()) {
                        if (subscription == null) ConfigFormField("订阅名称", name, "例如 主订阅", "config-subscription-name", busy) { name = it; formError = null }
                        else {
                            Text("订阅名称", style = MaterialTheme.typography.titleSmall, color = c.text)
                            Spacer(Modifier.height(8.dp))
                            Text(name, style = MaterialTheme.typography.bodyLarge, color = c.text)
                            Spacer(Modifier.height(4.dp))
                            Text("名称用于策略组引用，可在 YAML 编辑器中统一修改。", style = MaterialTheme.typography.bodySmall, color = c.textMuted)
                        }
                        Spacer(Modifier.height(18.dp))
                        ConfigFormField("订阅链接", url, "https://", "config-subscription-url", busy, urlKeyboard = true) { url = it; formError = null }
                        Spacer(Modifier.height(18.dp))
                        Text("保存到当前配置，运行时会尝试应用。", style = MaterialTheme.typography.bodySmall, color = c.textMuted)
                        (formError ?: error)?.let {
                            Spacer(Modifier.height(12.dp))
                            Text(it, style = MaterialTheme.typography.bodyMedium, color = c.bad, modifier = Modifier.testTag("config-form-error"))
                        }
                    }
                }
            }
            item(key = "subscription-actions") {
                HxSection {
                    HxButton("保存", onClick = {
                        if (!busy) {
                            formError = when {
                                name.isBlank() -> "名称不能为空"
                                !configHttpUrl(url) -> "请输入有效的 http/https 链接"
                                else -> null
                            }
                            if (formError == null) onSave(name.trim(), url.trim())
                        }
                    }, icon = Icons.Rounded.Save, busy = busy, enabled = !busy && dirty,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("config-subscription-save"))
                    Spacer(Modifier.height(8.dp))
                    HxButton("取消", onClick = ::leave, enabled = !busy, filled = false, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp))
                }
            }
        }
    }
    if (confirmLeave) ConfigDiscardFormDialog(onDiscard = { confirmLeave = false; onBack() }, onDismiss = { confirmLeave = false })
}

@Composable
private fun ConfigFormField(label: String, value: String, placeholder: String, tag: String, busy: Boolean, urlKeyboard: Boolean = false, onValueChange: (String) -> Unit) {
    Text(label, style = MaterialTheme.typography.titleSmall, color = Hx.colors.text)
    Spacer(Modifier.height(8.dp))
    HxTextField(value, { if (!busy) onValueChange(it) }, modifier = Modifier.fillMaxWidth().testTag(tag),
        placeholder = { Text(placeholder) }, singleLine = true,
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = if (urlKeyboard) androidx.compose.ui.text.input.KeyboardType.Uri else androidx.compose.ui.text.input.KeyboardType.Text))
}

@Composable
private fun ConfigFormTab(label: String, selected: Boolean, icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier, enabled: Boolean, onClick: () -> Unit) {
    val c = Hx.colors
    Row(modifier.heightIn(min = 52.dp).clip(Hx.rowShape).background(if (selected) c.surface else c.surfaceMuted)
        .selectable(selected = selected, enabled = enabled, role = androidx.compose.ui.semantics.Role.Tab, onClick = onClick)
        .padding(12.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = if (selected) c.accent else c.textMuted, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, color = if (selected) c.accent else c.textMuted)
    }
}

@Composable
private fun ConfigDiscardFormDialog(onDiscard: () -> Unit, onDismiss: () -> Unit) {
    ConfigDraftConfirmDialog(title = "放弃填写？", message = "已填写的内容还没有保存，返回会放弃这些修改。", confirmLabel = "放弃", onConfirm = onDiscard, onDismiss = onDismiss)
}

/** Stacked, scrollable confirmation actions remain usable at 320 dp / 200% text. */
@Composable
private fun ConfigDraftConfirmDialog(title: String, message: String, confirmLabel: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val c = Hx.colors
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.surface,
        shape = Hx.cardShape,
        title = { Text(title, style = MaterialTheme.typography.titleLarge, color = c.text) },
        text = { Text(message, style = MaterialTheme.typography.bodyMedium, color = c.textMuted,
            modifier = Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState())) },
        confirmButton = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                HxButton("取消", onClick = onDismiss, filled = false, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp))
                HxButton(confirmLabel, onClick = onConfirm, tone = HxTone.Bad, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp))
            }
        },
    )
}

private fun configHttpUrl(raw: String): Boolean = runCatching {
    val parsed = Uri.parse(raw.trim())
    (parsed.scheme.equals("http", ignoreCase = true) || parsed.scheme.equals("https", ignoreCase = true)) &&
        !parsed.host.isNullOrBlank() && raw.none { it == '\n' || it == '\r' || it == '\t' }
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
    val motion = LocalHxMotionEnabled.current
    val editorTextSizePx = with(androidx.compose.ui.platform.LocalDensity.current) { 13.sp.toPx() }
    var snapshot by remember { mutableStateOf<ConfigEditSnapshot?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var loadRevision by remember { mutableIntStateOf(0) }
    var editor by remember { mutableStateOf<CodeEditor?>(null) }
    var dirty by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var reloading by remember { mutableStateOf(false) }
    var conflict by remember { mutableStateOf<ConfigEditorConflict?>(null) }
    var showConflict by remember { mutableStateOf(false) }
    var confirmReload by remember { mutableStateOf(false) }
    var validating by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    var confirmLeave by remember { mutableStateOf(false) }
    var showOutline by remember { mutableStateOf(false) }
    val haptics = io.github.xgl34222220.hetu.ui.rememberHetuHaptics()
    val editorColors = remember(c.dark) { HetuYamlLanguage.colors(if (c.dark) SchemeDarcula() else SchemeGitHub(), c.dark) }

    LaunchedEffect(editorTextSizePx) { editor?.setTextSizePx(editorTextSizePx) }

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
        if (saving || reloading) return
        if (dirty) confirmLeave = true
        else if (onBackOverride != null) onBackOverride() else nav?.pop()
    }
    BackHandler(enabled = dirty || saving || reloading) { if (!saving && !reloading) confirmLeave = true }

    fun validate() {
        if (validating || saving || reloading || snapshot == null) return
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
        if (saving || reloading || validating || !dirty) return
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
                conflict = null
                vm.applyConfigChange("配置已保存")
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                problem = error.message ?: "保存失败"
                conflict = try {
                    repository.conflictAfterFailure(opened)
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (_: Exception) {
                    null // A failed read cannot prove that another writer changed the source.
                }
                showConflict = conflict != null
            } finally {
                saving = false
                editor?.isEditable = true
            }
        }
    }

    fun reload() {
        if (saving || reloading || validating) return
        reloading = true
        editor?.isEditable = false
        scope.launch {
            try {
                val latest = repository.load()
                // Keep the editor and its draft intact until a fresh read succeeds.
                snapshot = latest
                editor?.setText(latest.originalText)
                dirty = false
                problem = null
                conflict = null
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                problem = "重新读取失败，草稿仍保留：${error.message ?: "请重试"}"
            } finally {
                reloading = false
                editor?.isEditable = true
            }
        }
    }

    Column(Modifier.fillMaxSize().background(c.canvas).imePadding().testTag("config-editor-page")) {
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().heightIn(min = HxTopBarHeight).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HxBarAction(Icons.AutoMirrored.Rounded.ArrowBack, "返回", onClick = ::leave, enabled = !saving && !reloading)
            Text("编辑配置", style = MaterialTheme.typography.titleMedium, color = c.text,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(horizontal = 10.dp))
            HxBarAction(Icons.Rounded.Save, "保存", onClick = ::save, busy = saving, enabled = dirty && !validating && !reloading)
        }
        HxCard(Modifier.fillMaxWidth().padding(horizontal = Hx.gutter).padding(top = 8.dp, bottom = 10.dp), padding = androidx.compose.foundation.layout.PaddingValues(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Description, null, tint = c.accent, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(10.dp))
                Text(snapshot?.name ?: "读取配置", style = MaterialTheme.typography.titleSmall, color = c.text,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            }
            Spacer(Modifier.height(5.dp))
            Text(if (reloading) "正在重新读取" else if (dirty) "未保存 · 草稿仅保留在本页" else "源配置 · 保存前自动校验",
                style = MaterialTheme.typography.bodySmall, color = if (dirty) c.warn else c.textMuted)
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                HxEditorKey(Icons.AutoMirrored.Rounded.Undo, "撤销", enabled = !saving && !reloading) { editor?.let { if (it.canUndo()) it.undo() } }
                HxEditorKey(Icons.AutoMirrored.Rounded.Redo, "重做", enabled = !saving && !reloading) { editor?.let { if (it.canRedo()) it.redo() } }
                HxBarAction(Icons.AutoMirrored.Rounded.FormatListBulleted, "语法大纲", onClick = { showOutline = true }, enabled = snapshot != null && !saving && !reloading)
                HxBarAction(Icons.Rounded.FactCheck, "校验", onClick = ::validate, busy = validating, enabled = snapshot != null && !saving && !reloading)
            }
        }
        var lastProblem by remember { mutableStateOf("") }
        LaunchedEffect(problem) { problem?.let { lastProblem = it } }
        AnimatedVisibility(
            visible = problem != null && !showConflict,
            enter = if (motion) fadeIn(tween(HxMotion.Medium)) + expandVertically(tween(HxMotion.Medium, easing = HxMotion.Emphasized)) else androidx.compose.animation.EnterTransition.None,
            exit = if (motion) fadeOut(tween(HxMotion.Short)) + shrinkVertically(tween(HxMotion.Medium, easing = HxMotion.Emphasized)) else androidx.compose.animation.ExitTransition.None,
        ) {
            Column(Modifier.fillMaxWidth().padding(horizontal = Hx.gutter).padding(bottom = 10.dp)) {
                Text(problem ?: lastProblem, style = MaterialTheme.typography.bodySmall, color = c.bad)
                HxButton(if (conflict != null) "处理冲突" else "关闭提示", onClick = { if (conflict != null) showConflict = true else problem = null },
                    filled = false, enabled = !reloading, modifier = Modifier.heightIn(min = 48.dp))
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = Hx.gutter)
            .clip(Hx.cardShape).background(c.surface).border(1.dp, c.line, Hx.cardShape).padding(8.dp)) {
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
                            setTextSizePx(editorTextSizePx)
                            setLineNumberEnabled(true)
                            setWordwrap(false)
                            setTabWidth(2)
                            setHighlightCurrentLine(true)
                            setCursorAnimationEnabled(motion)
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
                        native.isEditable = !saving && !reloading
                        native.setCursorAnimationEnabled(motion)
                    },
                    onRelease = { native ->
                        if (editor === native) editor = null
                        native.release()
                    },
                )
            }
        }
        Row(
            Modifier
                .fillMaxWidth()
                .background(c.canvas)
                .navigationBarsPadding()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            HxYamlSymbols.forEach { symbol ->
                val source = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                Box(
                    Modifier
                        .hxPressScale(source, .9f)
                        .heightIn(min = 48.dp)
                        .widthIn(min = 48.dp)
                        .clip(Hx.chipShape)
                        .background(c.surface)
                        .clickable(enabled = !saving && !reloading && snapshot != null, interactionSource = source, indication = androidx.compose.foundation.LocalIndication.current) {
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

    if (showConflict) {
        conflict?.let { detected ->
            HxSheet(onDismiss = { showConflict = false }, title = if (detected.changedSelection) "保存遇到冲突" else "文件已在其他位置修改") {
                val close = LocalHxSheetClose.current
                Column(Modifier.fillMaxWidth().heightIn(max = 440.dp).verticalScroll(rememberScrollState()).padding(horizontal = 22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(problem.orEmpty(), style = MaterialTheme.typography.bodyMedium, color = c.textMuted)
                    Text(if (detected.changedSelection) "当前选择为「${detected.currentName}」。你的草稿仍保留，重新读取会打开当前选择。"
                        else "你的草稿仍保留。可以继续编辑，或明确放弃草稿后读取最新内容。", style = MaterialTheme.typography.bodyMedium, color = c.textMuted)
                    HxButton("保留草稿", onClick = { close { showConflict = false } }, filled = false, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp))
                    HxButton("重新读取", onClick = { close { showConflict = false; confirmReload = true } }, icon = Icons.Rounded.Refresh,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp))
                }
            }
        }
    }
    if (confirmReload) {
        ConfigDraftConfirmDialog(title = "放弃草稿并重新读取？", message = "重新读取成功后，本页未保存的修改和撤销记录会被替换为当前配置的最新内容。读取失败会继续保留草稿。",
            confirmLabel = "放弃并读取",
            onConfirm = { confirmReload = false; reload() }, onDismiss = { confirmReload = false })
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
                            .heightIn(min = 48.dp)
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
        ConfigDraftConfirmDialog(
            title = "放弃修改？",
            message = "当前修改还没有保存。",
            confirmLabel = "放弃",
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
private fun HxEditorKey(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, enabled: Boolean = true, onClick: () -> Unit) {
    val c = Hx.colors
    val haptics = io.github.xgl34222220.hetu.ui.rememberHetuHaptics()
    val source = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    Box(
        Modifier
            .hxPressScale(source, .9f)
            .size(48.dp)
            .clip(Hx.chipShape)
            .background(c.surface)
            .clickable(enabled = enabled, interactionSource = source, indication = androidx.compose.foundation.LocalIndication.current) {
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
