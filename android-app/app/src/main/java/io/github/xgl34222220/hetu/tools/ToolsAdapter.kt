package io.github.xgl34222220.hetu.tools

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import io.github.xgl34222220.hetu.ProxyComposeController
import io.github.xgl34222220.hetu.ProxyLogViewerActivity
import io.github.xgl34222220.hetu.ProxyNetworkAutomationActivity
import io.github.xgl34222220.hetu.ProxyScriptsActivity
import io.github.xgl34222220.hetu.ProxySubStoreActivity
import io.github.xgl34222220.hetu.ProxyWebPanelsActivity
import io.github.xgl34222220.hetu.ReferenceFileManagerActivity
import io.github.xgl34222220.hetu.ToolsConfigBridge
import io.github.xgl34222220.hetu.HxConfigTransaction
import io.github.xgl34222220.hetu.home.HetuHomeThemeFromPrefs
import io.github.xgl34222220.hetu.tools.ToolsDesignDims as HomeDims
import io.github.xgl34222220.hetu.home.HomeHaptic
import io.github.xgl34222220.hetu.home.LocalHomeHaptics
import io.github.xgl34222220.hetu.home.asHomeHaptics
import io.github.xgl34222220.hetu.ui.HetuHaptic
import io.github.xgl34222220.hetu.ui.rememberHetuHaptics
import java.io.IOException
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/* ------------------------------------------------------------------ */
/*  The only file of the module that touches the existing sources.      */
/* ------------------------------------------------------------------ */

/**
 * Drop-in replacement for `RefTools(state, onLog)` in `ReferenceProxyActivity.kt`.
 *
 * 配置管理 (pages 4–27) and the seven pages of Part 2 (应用管理, 核心管理, 绕过规则, 共享网络,
 * CNIP, 诊断工具, 广告过滤: pages 28–49) open inside the module. The other six entries start
 * the activities they start today; they move into the module with 03B.
 *
 * @param onLog receives the diagnostics text for 诊断工具, as `RefTools` does today.
 * @param onSubPageVisibleChanged true while a pushed page covers the tab: hide the dock.
 * @param onConfigChanged the config library or the current config changed: refresh the shell state.
 * @param onConfigSelected the user made another config current: the host applies it to a running proxy and says so.
 */
@Composable
internal fun HetuToolsV2(
    onLog: (String) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(bottom = HomeDims.dockClearance),
    onSubPageVisibleChanged: (Boolean) -> Unit = {},
    onConfigChanged: () -> Unit = {},
    onConfigSelected: ((String) -> Unit)? = null,
    onOpenDiagnosticsDetails: (() -> Unit)? = null,
    onOpenExternalEntry: (ToolsEntry) -> Boolean = { false },
    onOpenConfigEditor: (() -> Unit)? = null,
    requestedEntry: ToolsEntry? = null,
    onRequestConsumed: () -> Unit = {},
    requestedImport: Boolean = false,
) {
    val context = LocalContext.current
    val app = context.applicationContext
    val prefs = remember(app) { app.getSharedPreferences("hetu", Context.MODE_PRIVATE) }
    val controller = remember(app) { ProxyComposeController(app) }
    val scope = rememberCoroutineScope()
    val haptics = rememberHetuHaptics()
    val log by rememberUpdatedState(onLog)
    val changed by rememberUpdatedState(onConfigChanged)
    val selected by rememberUpdatedState(onConfigSelected)
    val openExternal by rememberUpdatedState(onOpenExternalEntry)
    val openConfigEditor by rememberUpdatedState(onOpenConfigEditor)
    val features = rememberToolsFeatureHost(onChanged = onConfigChanged, onOpenDiagnosticsDetails = onOpenDiagnosticsDetails)
    var destination by remember { mutableStateOf(ToolsDestination.Root) }
    ToolsAdblockVisibilityFlag(visible = destination == ToolsDestination.Adblock)

    fun toast(text: String) = Toast.makeText(app, text, Toast.LENGTH_SHORT).show()

    var pickedFile by remember { mutableStateOf<ToolsPickedFile?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) pickedFile = pickedFileOf(app, uri)
    }

    var exporting by remember { mutableStateOf<String?>(null) }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/yaml")) { uri ->
        val name = exporting
        exporting = null
        if (uri != null && name != null) {
            scope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        val text = ToolsConfigBridge.read(app, name)
                        val output = app.contentResolver.openOutputStream(uri) ?: throw IOException("无法写入目标文件")
                        output.use { it.write(text.toByteArray(Charsets.UTF_8)) }
                    }
                    toast("已导出 $name")
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (error: Exception) {
                    toast(io.github.xgl34222220.hetu.hxErrorMessage(error, "导出失败"))
                }
            }
        }
    }

    val actions = remember(app, controller) {
        ToolsActions(
            onOpenEntry = openEntry@{ entry ->
                if (openExternal(entry)) return@openEntry
                // Apps / Cores / Bypass / Share / CnIp / Diag / Adblock always open their in-module page
                // (rememberToolsFeatureHost supplies every loader), so only entries without one leave here.
                val target = when (entry) {
                    ToolsEntry.Files -> ReferenceFileManagerActivity::class.java
                    ToolsEntry.Scripts -> ProxyScriptsActivity::class.java
                    ToolsEntry.Logs -> ProxyLogViewerActivity::class.java
                    ToolsEntry.NetMatch -> ProxyNetworkAutomationActivity::class.java
                    ToolsEntry.SubStore -> ProxySubStoreActivity::class.java
                    ToolsEntry.WebUi -> ProxyWebPanelsActivity::class.java
                    else -> null
                }
                if (target != null) context.startActivity(Intent(context, target))
            },
            onOpenEditor = if (onOpenConfigEditor == null) null else { { openConfigEditor?.invoke() } },
            loadConfigs = {
                val library = controller.configLibrary()
                val subscriptions = controller.configOverview().second
                ToolsConfigSnapshot(
                    configs = library
                        .map { ToolsConfig(it.name, if (it.bundled) ToolsConfigKind.Bundled else ToolsConfigKind.Local, it.selected) }
                        .sortedBy { it.kind == ToolsConfigKind.Bundled },
                    subscriptions = subscriptions.map { ToolsSubscription(it.name, it.url, it.placeholder) },
                )
            },
            selectConfig = { name -> controller.selectConfig(name); changed(); selected?.invoke(name) },
            selectionAnnounced = onConfigSelected != null,
            exportConfig = { name ->
                exporting = name
                // A disabled DocumentsUI is a message, not a crash (DocumentPickerSafety).
                if (!io.github.xgl34222220.hetu.launchDocumentPicker(::toast) { exporter.launch(name) }) exporting = null
            },
            renameConfig = { from, to -> withContext(Dispatchers.IO) { ToolsConfigBridge.rename(app, from, to) }; changed() },
            deleteConfig = { name -> controller.deleteConfig(name); changed() },
            addSubscription = { name, url -> controller.addSubscription(name, url); changed() },
            updateSubscription = { name, url -> controller.updateSubscription(name, url); changed() },
            deleteSubscription = { name -> controller.deleteSubscription(name); changed() },
            importFromUrl = { url, name ->
                // Validated by Mihomo before it is stored, like 设置 › 导入配置.
                val text = HxConfigTransaction.decode(HxConfigTransaction.download(url).first)
                toolsImportCheck { controller.validateConfigText(text) }
                withContext(Dispatchers.IO) { ToolsConfigBridge.importStream(app, name, text.toByteArray(Charsets.UTF_8).inputStream()) }
                    .also { changed(); selected?.invoke(it) }
            },
            onPickImportFile = { io.github.xgl34222220.hetu.launchDocumentPicker(::toast) { picker.launch(arrayOf("*/*")) } },
            onClearPickedFile = { pickedFile = null },
            importFile = { file ->
                val uri = file.token as? Uri ?: throw IOException("无法读取配置文件")
                val bytes = try {
                    HxConfigTransaction.readUri(app, uri)
                } catch (denied: SecurityException) {
                    throw IOException("无法读取所选文件，请重新选择文件", denied)
                }
                val text = HxConfigTransaction.decode(bytes)
                toolsImportCheck { controller.validateConfigText(text) }
                // A picker name without .yaml/.yml (some providers drop it) would be refused by the library.
                withContext(Dispatchers.IO) { ToolsConfigBridge.importStream(app, toolsImportFileName(file.name), text.toByteArray(Charsets.UTF_8).inputStream()) }
                    .also { changed(); selected?.invoke(it) }
            },
            readConfig = { withContext(Dispatchers.IO) { ToolsConfigBridge.document(app) } },
            validateConfig = { text -> controller.validateConfigText(text) },
            saveConfig = { source, text ->
                ToolsConfigBridge.saveDocument(app, source, text, controller::validateConfigText).also {
                    if (it is ToolsSaveResult.Saved) changed()
                }
            },
            onMessage = ::toast,
        )
    }

    val homeHaptics = remember(haptics) { haptics.asHomeHaptics() }
    HetuHomeThemeFromPrefs(prefs) {
      ToolsConceptTheme {
        CompositionLocalProvider(
            LocalHomeHaptics provides homeHaptics,
        ) {
            ToolsRoute(
                actions = actions,
                modifier = modifier,
                pickedFile = pickedFile,
                contentPadding = contentPadding,
                onSubPageVisibleChanged = onSubPageVisibleChanged,
                features = features.actions,
                appIcon = features.appIcon,
                onDestinationChanged = { destination = it },
                requestedEntry = requestedEntry,
                onRequestConsumed = onRequestConsumed,
                requestedImport = requestedImport,
            )
        }
      }
    }
}

/** Display name and size of a picked document; falls back to the last path segment. */
private fun pickedFileOf(context: Context, uri: Uri): ToolsPickedFile {
    var name = ""
    var size = -1L
    try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameAt = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeAt = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (nameAt >= 0) name = cursor.getString(nameAt).orEmpty()
                if (sizeAt >= 0 && !cursor.isNull(sizeAt)) size = cursor.getLong(sizeAt)
            }
        }
    } catch (ignored: Exception) {
        // Some providers reject the query; the fallbacks below still give a usable name.
    }
    if (name.isBlank()) name = uri.lastPathSegment?.substringAfterLast('/').orEmpty().ifBlank { "config.yaml" }
    val detail = when {
        size < 0 -> ""
        size < 1024 -> "$size B"
        size < 1024 * 1024 -> String.format(Locale.ROOT, "%.0f KB", size / 1024.0)
        else -> String.format(Locale.ROOT, "%.1f MB", size / 1024.0 / 1024.0)
    }
    return ToolsPickedFile(name, detail, uri)
}

/**
 * 7.0.0: the core's `-t` answer is many log lines; a toast shows two, so the reason was cut off and an
 * import seemed to do nothing. Keeps the core's own error line, in a Chinese sentence.
 */
private suspend fun toolsImportCheck(check: suspend () -> Unit) {
    try {
        check()
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (error: Exception) {
        throw IOException("配置校验未通过：" + toolsCoreCheckReason(error.message.orEmpty()), error)
    }
}

/** The `level=error msg="…"` (or `level=fatal`) text of a core check, else its last meaningful line. */
internal fun toolsCoreCheckReason(message: String): String {
    val body = message.substringAfter("YAML 校验失败：", message).trim()
    val logged = Regex("""level=(?:error|fatal)\s+msg="((?:[^"\\]|\\.)*)"""").findAll(body).lastOrNull()?.groupValues?.get(1)
        ?.replace("\\\"", "\"")?.trim()
    val reason = logged?.takeIf { it.isNotEmpty() } ?: body.lineSequence().map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("configuration file") && !it.contains("level=info") && !it.contains("level=warning") }
        .lastOrNull().orEmpty()
    return reason.ifEmpty { body.ifEmpty { "未知原因" } }.take(300)
}

/** A picked document's name as a library file name: keeps .yaml/.yml, otherwise appends .yaml; at most 120 characters. */
internal fun toolsImportFileName(raw: String): String {
    val base = raw.trim().replace('/', '_').replace('\\', '_').ifEmpty { "config.yaml" }
    val named = if (base.endsWith(".yaml", ignoreCase = true) || base.endsWith(".yml", ignoreCase = true)) base else "$base.yaml"
    if (named.substringBeforeLast('.').isBlank()) return "config.yaml"
    if (named.length <= 120) return named
    val ext = named.substring(named.lastIndexOf('.'))
    return named.substring(0, 120 - ext.length) + ext
}
