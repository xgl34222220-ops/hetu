package io.github.xgl34222220.hetu

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.xgl34222220.hetu.home.HomeIcons
import io.github.xgl34222220.hetu.home.HomePop
import io.github.xgl34222220.hetu.home.LocalHomeColors
import io.github.xgl34222220.hetu.tools.ToolsIcons
import io.github.xgl34222220.hetu.ui.ht
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Which config the in-place editor of 基础代理配置 shows, and whether it opens read-only. */
internal data class SettingsConfigEditTarget(val name: String, val readOnly: Boolean)

/** The long-press menu of one row in 配置选择; the bundled template cannot be renamed or deleted. */
internal fun settingsConfigMenu(config: ProxyConfigUi): List<String> = buildList {
    add("查看"); add("编辑"); add("导出")
    if (!config.bundled) { add("重命名"); add("删除") }
}

/**
 * 配置选择 in 设置 › 基础代理配置: the configs of the selected core. Tap makes one current (applied to
 * the running core); hold, or ···, opens 查看 / 编辑 / 导出 / 重命名 / 删除. 查看 and 编辑 open the
 * shared config editor in place through [onOpen]; nothing here switches to the 工具 tab.
 */
@Composable
internal fun SettingsConfigPicker(
    vm: HetuViewModel,
    configs: List<ProxyConfigUi>,
    loading: Boolean,
    onChanged: () -> Unit,
    onOpen: (SettingsConfigEditTarget) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val c = LocalHomeColors.current
    var busy by remember { mutableStateOf(false) }
    var menuFor by remember { mutableStateOf<ProxyConfigUi?>(null) }
    var renameFor by remember { mutableStateOf<ProxyConfigUi?>(null) }
    var deleteFor by remember { mutableStateOf<ProxyConfigUi?>(null) }
    var exportFor by remember { mutableStateOf<ProxyConfigUi?>(null) }

    fun perform(action: String, applyToRuntime: Boolean, block: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                block()
                onChanged()
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

    fun entryOf(config: ProxyConfigUi): ProxyConfigLibrary.Entry {
        val library = ProxyConfigLibrary(context)
        return library.list(ProxyRuntimeProfile.load(vm.prefs).core).firstOrNull { it.name == config.name } ?: error("配置不存在")
    }

    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/yaml")) { uri ->
        val config = exportFor
        exportFor = null
        if (uri != null && config != null) perform("已导出 ${config.name}", false) {
            withContext(Dispatchers.IO) {
                val content = ProxyConfigLibrary(context).read(entryOf(config))
                val output = context.contentResolver.openOutputStream(uri) ?: error("无法写入导出文件")
                output.use { it.write(content.toByteArray(Charsets.UTF_8)) }
            }
        }
    }

    SettingsGroup(ht("配置选择")) {
        when {
            loading && configs.isEmpty() -> Box(Modifier.fillMaxWidth().padding(vertical = 22.dp), contentAlignment = Alignment.Center) { HxSpinner(22.dp) }
            configs.isEmpty() -> SettingsRow(ht("尚无配置"), subtitle = ht("在 工具 › 配置管理 导入配置后会显示在这里"), icon = ToolsIcons.FileText)
            else -> configs.forEachIndexed { index, config ->
                if (index > 0) SettingsDivider()
                HxRow(
                    config.name,
                    subtitle = ht(when {
                        config.selected -> "当前配置 · 长按查看或编辑"
                        config.bundled -> "内置模板 · 需要填写订阅"
                        else -> "本地配置"
                    }),
                    icon = if (config.bundled) ToolsIcons.FileCog else ToolsIcons.FileText,
                    selected = config.selected,
                    enabled = !busy,
                    onClick = { if (!config.selected) perform("已切换到 ${config.name}", true) { vm.controller.selectConfig(config.name) } },
                    onLongClick = { menuFor = config },
                ) {
                    HomePop(config.selected) { Icon(HomeIcons.CircleCheck, ht("当前配置"), Modifier.size(22.dp), tint = c.accent) }
                    HxBarAction(ToolsIcons.Ellipsis, "更多", onClick = { menuFor = config }, enabled = !busy, anchorMenu = true)
                }
            }
        }
    }

    menuFor?.let { config ->
        HxActionMenu(
            title = config.name,
            referenceFileMenu = true,
            headerIcon = if (config.bundled) ToolsIcons.FileCog else ToolsIcons.FileText,
            actions = settingsConfigMenu(config).map { label ->
                when (label) {
                    "查看" -> HxMenuAction(ht("查看"), ToolsIcons.FileText) { menuFor = null; onOpen(SettingsConfigEditTarget(config.name, readOnly = true)) }
                    "编辑" -> HxMenuAction(ht("编辑"), ToolsIcons.Pencil) { menuFor = null; onOpen(SettingsConfigEditTarget(config.name, readOnly = false)) }
                    "导出" -> HxMenuAction(ht("导出"), ToolsIcons.Share) {
                        menuFor = null; exportFor = config
                        launchDocumentPicker(vm::toast) { exporter.launch(config.name) }
                    }
                    "重命名" -> HxMenuAction(ht("重命名"), HxIcons.TextCursorInput) { menuFor = null; renameFor = config }
                    else -> HxMenuAction(ht("删除"), ToolsIcons.Trash2, danger = true) { menuFor = null; deleteFor = config }
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
                    withContext(Dispatchers.IO) { ProxyConfigLibrary(context).rename(entryOf(config), values[0]) }
                }
            },
            onDismiss = { renameFor = null },
        )
    }
    deleteFor?.let { config ->
        HxConfirmDialog(
            title = "删除配置？",
            message = "「${config.name}」将被永久删除。" + if (config.selected) "删除后会切换回内置模板。" else "",
            confirmLabel = "删除", danger = true,
            onConfirm = {
                deleteFor = null
                perform("已删除", applyToRuntime = config.selected) { vm.controller.deleteConfig(config.name) }
            },
            onDismiss = { deleteFor = null },
        )
    }
}
