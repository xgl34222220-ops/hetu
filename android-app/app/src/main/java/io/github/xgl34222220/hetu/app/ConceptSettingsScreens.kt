package io.github.xgl34222220.hetu

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun HxBackupScreen(vm: HetuViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var restoreUri by remember { mutableStateOf<Uri?>(null) }
    var busy by remember { mutableStateOf(false) }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) scope.launch {
            busy = true
            try { vm.toast("已备份 ${HetuSettingsBackup.export(context, uri)} 项设置与配置") }
            catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) { vm.toast(error.message ?: "备份失败") }
            finally { busy = false }
        }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { restoreUri = it }
    HxPage("备份与恢复", subtitle = "管理配置与偏好数据", onBack = onBack) {
        item("export") { HxSection { HxCard {
            HxRow("创建备份", subtitle = "导出当前配置与偏好设置", icon = Icons.Rounded.CloudUpload)
            HxButton("导出备份", { exporter.launch("Hetu-backup.json") }, icon = Icons.Rounded.UploadFile, busy = busy)
        } } }
        item("restore") { HxSection { HxCard {
            HxRow("从文件恢复", subtitle = "选择河图备份文件，恢复前会再次确认", icon = Icons.Rounded.CloudDownload)
            HxButton("选择文件", { importer.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }, icon = Icons.Rounded.Folder, enabled = !busy)
        } } }
        item("contents") { HxSection("备份内容") { HxGroup {
            HxRow("应用配置", subtitle = "配置库与运行偏好", icon = Icons.Rounded.Description)
            HxRow("界面偏好", subtitle = "主题、导航与显示设置", icon = Icons.Rounded.Palette)
            HxRow("订阅与连接", subtitle = "订阅链接与配置来源", icon = Icons.Rounded.Link)
        } } }
        item("privacy") { HxSection { HxBanner("备份文件可能包含配置中的订阅链接与认证信息，请保存在可信位置。", HxTone.Warn) } }
    }
    restoreUri?.let { uri -> HxConfirmDialog(
        title = "恢复备份？", message = "将写入备份中的设置与配置。同名但内容不同的配置会保留为恢复副本。正在运行的代理不会自动重启。", confirmLabel = "恢复",
        onDismiss = { restoreUri = null }, onConfirm = {
            restoreUri = null
            scope.launch {
                busy = true
                try { val count = HetuSettingsBackup.restore(context, uri); vm.reloadAppearance(); vm.bumpSettings(); vm.toast("已恢复 $count 项，请检查后手动应用") }
                catch (cancel: CancellationException) { throw cancel }
                catch (error: Exception) { vm.toast(error.message ?: "恢复失败") }
                finally { busy = false }
            }
        },
    ) }
}

@Composable
internal fun HxStartupSettingsScreen(vm: HetuViewModel, onBack: () -> Unit) {
    val prefs = vm.prefs
    var revision by remember { mutableIntStateOf(0) }
    var mirrorDialog by remember { mutableStateOf(false) }
    val autoStart = remember(revision) { prefs.getBoolean("proxyRootAutoStart", false) }
    val mirror = remember(revision) { prefs.getBoolean("downloadMirrorEnabled", false) }
    fun put(key: String, value: Boolean) { prefs.edit().putBoolean(key, value).apply(); revision++; vm.bumpSettings() }
    HxPage("开机启动与下载", onBack = onBack, largeTitle = false) {
        item("startup") { HxSection { HxGroup { HxSwitchRow("开机自启", autoStart, { put("proxyRootAutoStart", it) }, subtitle = "安装 Root 开机脚本，开机后自动启动服务", icon = Icons.Rounded.RestartAlt) } } }
        item("download") { HxSection { HxGroup {
            HxSwitchRow("加速下载", mirror, { put("downloadMirrorEnabled", it) }, subtitle = "通过已配置的镜像下载资源", icon = Icons.Rounded.Download)
            if (mirror) HxNavRow("加速地址", subtitle = prefs.getString("downloadMirrorPrefix", "").orEmpty().ifBlank { "尚未设置" }, icon = Icons.Rounded.Link) { mirrorDialog = true }
        } } }
    }
    if (mirrorDialog) HxFormDialog("加速下载", fields = listOf(HxField("镜像前缀", prefs.getString("downloadMirrorPrefix", "").orEmpty(), placeholder = "https://")),
        validate = { values -> val value = values.firstOrNull().orEmpty().trim(); if (value.isNotBlank() && !value.startsWith("https://") && !value.startsWith("http://")) "请填写 http/https 地址" else null },
        onDismiss = { mirrorDialog = false }, onConfirm = { values -> prefs.edit().putString("downloadMirrorPrefix", values.firstOrNull().orEmpty().trim()).apply(); revision++; vm.bumpSettings(); mirrorDialog = false })
}

@Composable
internal fun HxPanelPreferencesScreen(vm: HetuViewModel, onBack: () -> Unit) {
    val prefs = vm.prefs
    var revision by remember { mutableIntStateOf(0) }
    var picking by remember { mutableStateOf(false) }
    val show = remember(revision) { prefs.getBoolean("showPanelDock", true) }
    val start = remember(revision) { prefs.getBoolean("startOnPanel", false) }
    val default = remember(revision) { prefs.getString("defaultPanelSection", "overview").orEmpty() }
    fun put(key: String, value: Boolean) { prefs.edit().putBoolean(key, value).apply(); revision++; vm.bumpSettings() }
    HxPage("默认面板", onBack = onBack, largeTitle = false) {
        item("navigation") { HxSection { HxGroup {
            HxNavRow("默认面板页面", icon = Icons.Rounded.GridView, value = HxPanelSections.firstOrNull { it.first == default }?.second ?: "概览") { picking = true }
            HxSwitchRow("启动时打开面板", start, { put("startOnPanel", it) }, icon = Icons.Rounded.Dashboard)
            HxSwitchRow("显示底栏面板入口", show, { put("showPanelDock", it) }, icon = Icons.Rounded.ViewModule)
        } } }
    }
    if (picking) HxChoiceSheet("默认面板页面", HxPanelSections.map { HxChoice(it.first, it.second) }, default,
        onPick = { vm.setDefaultPanelSection(it); revision++; picking = false }, onDismiss = { picking = false })
}
