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
import io.github.xgl34222220.hetu.ProxyAdblockChainActivity
import io.github.xgl34222220.hetu.ProxyAppSelectionActivity
import io.github.xgl34222220.hetu.ProxyBypassRulesActivity
import io.github.xgl34222220.hetu.ProxyCnIpSettingsActivity
import io.github.xgl34222220.hetu.ProxyComposeController
import io.github.xgl34222220.hetu.ProxyCoreActivity
import io.github.xgl34222220.hetu.ProxyLogViewerActivity
import io.github.xgl34222220.hetu.ProxyNetworkAutomationActivity
import io.github.xgl34222220.hetu.ProxyScriptsActivity
import io.github.xgl34222220.hetu.ProxySharedNetworkSettingsActivity
import io.github.xgl34222220.hetu.ProxySubStoreActivity
import io.github.xgl34222220.hetu.ProxyWebPanelsActivity
import io.github.xgl34222220.hetu.ReferenceFileManagerActivity
import io.github.xgl34222220.hetu.ToolsConfigBridge
import io.github.xgl34222220.hetu.home.HetuHomeThemeFromPrefs
import io.github.xgl34222220.hetu.tools.ToolsDesignDims as HomeDims
import io.github.xgl34222220.hetu.home.HomeHaptic
import io.github.xgl34222220.hetu.home.LocalHomeHaptics
import io.github.xgl34222220.hetu.ui.HetuHaptic
import io.github.xgl34222220.hetu.ui.rememberHetuHaptics
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
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
 */
@Composable
internal fun HetuToolsV2(
    onLog: (String) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(bottom = HomeDims.dockClearance),
    onSubPageVisibleChanged: (Boolean) -> Unit = {},
    onConfigChanged: () -> Unit = {},
    onOpenDiagnosticsDetails: (() -> Unit)? = null,
    onOpenExternalEntry: (ToolsEntry) -> Boolean = { false },
    onOpenConfigEditor: (() -> Unit)? = null,
    onOpenConnectivity: (() -> Unit)? = null,
    onOpenAdblockVerify: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val app = context.applicationContext
    val prefs = remember(app) { app.getSharedPreferences("hetu", Context.MODE_PRIVATE) }
    val controller = remember(app) { ProxyComposeController(app) }
    val scope = rememberCoroutineScope()
    val haptics = rememberHetuHaptics()
    val log by rememberUpdatedState(onLog)
    val changed by rememberUpdatedState(onConfigChanged)
    val openExternal by rememberUpdatedState(onOpenExternalEntry)
    val openConfigEditor by rememberUpdatedState(onOpenConfigEditor)
    val features = rememberToolsFeatureHost(
        onChanged = onConfigChanged,
        onOpenDiagnosticsDetails = onOpenDiagnosticsDetails,
        onOpenConnectivity = onOpenConnectivity,
        onOpenAdblockVerify = onOpenAdblockVerify,
    )
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
                    toast(error.message ?: "导出失败")
                }
            }
        }
    }

    val actions = remember(app, controller) {
        ToolsActions(
            onOpenEntry = openEntry@{ entry ->
                if (openExternal(entry)) return@openEntry
                val target = when (entry) {
                    ToolsEntry.Files -> ReferenceFileManagerActivity::class.java
                    ToolsEntry.Scripts -> ProxyScriptsActivity::class.java
                    ToolsEntry.Logs -> ProxyLogViewerActivity::class.java
                    ToolsEntry.Apps -> ProxyAppSelectionActivity::class.java
                    ToolsEntry.NetMatch -> ProxyNetworkAutomationActivity::class.java
                    ToolsEntry.Share -> ProxySharedNetworkSettingsActivity::class.java
                    ToolsEntry.Bypass -> ProxyBypassRulesActivity::class.java
                    ToolsEntry.SubStore -> ProxySubStoreActivity::class.java
                    ToolsEntry.CnIp -> ProxyCnIpSettingsActivity::class.java
                    ToolsEntry.Cores -> ProxyCoreActivity::class.java
                    ToolsEntry.Adblock -> ProxyAdblockChainActivity::class.java
                    ToolsEntry.WebUi -> ProxyWebPanelsActivity::class.java
                    ToolsEntry.Diag, ToolsEntry.Configs -> null
                }
                if (target != null) {
                    context.startActivity(Intent(context, target))
                } else if (entry == ToolsEntry.Diag) {
                    scope.launch {
                        try {
                            log(controller.diagnostics())
                        } catch (cancel: CancellationException) {
                            throw cancel
                        } catch (error: Exception) {
                            log(error.message ?: "诊断读取失败")
                        }
                    }
                }
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
            selectConfig = { name -> controller.selectConfig(name); changed() },
            exportConfig = { name -> exporting = name; exporter.launch(name) },
            renameConfig = { from, to -> withContext(Dispatchers.IO) { ToolsConfigBridge.rename(app, from, to) }; changed() },
            deleteConfig = { name -> controller.deleteConfig(name); changed() },
            addSubscription = { name, url -> controller.addSubscription(name, url); changed() },
            updateSubscription = { name, url -> controller.updateSubscription(name, url); changed() },
            deleteSubscription = { name -> controller.deleteSubscription(name); changed() },
            importFromUrl = { url, name -> download(app, url, name).also { changed() } },
            onPickImportFile = { picker.launch(arrayOf("*/*")) },
            onClearPickedFile = { pickedFile = null },
            importFile = { file ->
                val uri = file.token as? Uri ?: throw IOException("无法读取配置文件")
                controller.importConfig(uri, file.name).also { changed() }
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

    HetuHomeThemeFromPrefs(prefs) {
      ToolsConceptTheme {
        CompositionLocalProvider(
            LocalHomeHaptics provides { kind ->
                haptics.perform(
                    when (kind) {
                        HomeHaptic.Tap -> HetuHaptic.Tap
                        HomeHaptic.Tick -> HetuHaptic.Tick
                        HomeHaptic.Confirm -> HetuHaptic.Confirm
                        HomeHaptic.Reject -> HetuHaptic.Reject
                    },
                )
            },
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

/** Fetches [url] and hands the body to the config library under [name]; returns the stored name. */
private suspend fun download(context: Context, url: String, name: String): String = withContext(Dispatchers.IO) {
    val connection = URL(url).openConnection() as? HttpURLConnection ?: throw IOException("请输入有效的 http/https 链接")
    try {
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("User-Agent", "clash.meta")
        val code = connection.responseCode
        if (code !in 200..299) throw IOException("下载失败：HTTP $code")
        ToolsConfigBridge.importStream(context, name, connection.inputStream)
    } finally {
        connection.disconnect()
    }
}
