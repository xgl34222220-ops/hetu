package io.github.xgl34222220.hetu

import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.xgl34222220.hetu.home.HomeDims
import io.github.xgl34222220.hetu.home.HomeIcons
import io.github.xgl34222220.hetu.home.HomeType
import io.github.xgl34222220.hetu.home.LocalHomeColors
import io.github.xgl34222220.hetu.tools.ToolsIcons
import io.github.xgl34222220.hetu.ui.ht
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A config text waiting for the user's decision: where it came from and what the checks said. */
private data class ImportCandidate(
    val origin: String,
    val name: String,
    val text: String,
    val check: ConfigCheck,
    /** Against the current config; null when there is none. */
    val diff: ConfigDiff?,
)

private enum class ImportMode { New, Replace }

/**
 * 基础代理配置 › 导入配置: file / link / clipboard → strict decode, YAML pre-check and a diff
 * against the current config → the core's validation → backup, atomic write, hot reload, and
 * rollback when the running core refuses it. Also lists backups for one-tap restore.
 */
@Composable
internal fun ConfigImportScreen(vm: HetuViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val c = LocalHomeColors.current
    var busy by remember { mutableStateOf<String?>(null) }
    var url by remember { mutableStateOf("") }
    var candidate by remember { mutableStateOf<ImportCandidate?>(null) }
    var name by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf(ImportMode.New) }
    var current by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<ConfigApplyResult?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    var backups by remember { mutableStateOf<List<ConfigBackup>>(emptyList()) }
    var restoreTarget by remember { mutableStateOf<ConfigBackup?>(null) }
    var revision by remember { mutableIntStateOf(0) }

    LaunchedEffect(revision) {
        try {
            val (selected, list) = withContext(Dispatchers.IO) {
                LibraryConfigStore(context).selectedName() to LibraryConfigStore.backups(context).list().take(12)
            }
            current = selected
            backups = list
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            backups = emptyList()
        }
    }

    fun task(key: String, block: suspend () -> Unit) {
        if (busy != null) return
        busy = key
        failure = null
        scope.launch {
            try {
                block()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                failure = error.message ?: "操作失败"
            } finally {
                busy = null
            }
        }
    }

    /** Decode, check and diff off the main thread, then show the preview. */
    suspend fun stage(origin: String, requestedName: String, text: String) {
        val prepared = withContext(Dispatchers.Default) {
            val check = ConfigText.inspect(text)
            val store = LibraryConfigStore(context)
            val selected = withContext(Dispatchers.IO) { store.selectedName() }
            val old = selected?.let { withContext(Dispatchers.IO) { store.read(it) } }
            ImportCandidate(origin, requestedName, text, check, old?.let { ConfigDiffer.diff(it, text) })
        }
        candidate = prepared
        name = prepared.name
        result = null
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) task("file") {
            val (display, text) = withContext(Dispatchers.IO) {
                val stream = context.contentResolver.openInputStream(uri) ?: throw java.io.IOException("无法读取所选文件")
                importDisplayName(context, uri) to ConfigText.decode(ConfigText.readLimited(stream))
            }
            stage(ht0("文件"), ConfigText.importName(display), text)
        }
    }

    fun apply() {
        val pending = candidate ?: return
        pending.check.error?.let { failure = it; return }
        task("apply") {
            val transaction = LibraryConfigStore.transaction(context, vm)
            val target = current
            val outcome = if (mode == ImportMode.Replace && target != null) {
                transaction.save(target, pending.text)
            } else {
                transaction.import(ConfigText.importName(name.ifBlank { pending.name }), pending.text).second
            }
            result = outcome
            if (outcome !is ConfigApplyResult.RolledBack) candidate = null
            vm.toast(outcome.message)
            vm.bumpSettings()
            vm.refreshNow()
            revision++
        }
    }

    HxPage(title = ht("导入配置"), subtitle = ht("文件、链接或剪贴板中的 Mihomo YAML"), onBack = onBack, largeTitle = false) {
        item(key = "sources") {
            SettingsSection {
                SettingsGroup(title = ht("来源")) {
                    SettingsRow(ht("选择文件"), subtitle = ht("从本机或网盘选择 .yaml / .yml"), icon = ToolsIcons.FileText, enabled = busy == null,
                        onClick = { launchDocumentPicker(vm::toast) { picker.launch(arrayOf("*/*")) } }) {
                        if (busy == "file") HxSpinner(18.dp) else HxChevron()
                    }
                    SettingsDivider()
                    SettingsRow(ht("从剪贴板读取"), subtitle = ht("读取已复制的完整 YAML 文本"), icon = HomeIcons.Copy, enabled = busy == null,
                        onClick = {
                            val clip = try {
                                val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                manager?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
                            } catch (_: Exception) { null }
                            if (clip.isNullOrBlank()) failure = "剪贴板里没有文本"
                            else task("clipboard") {
                                if (clip.toByteArray(Charsets.UTF_8).size > ConfigText.LIMIT) throw java.io.IOException("配置超过 4 MiB")
                                stage("剪贴板", ConfigText.importName(null, "剪贴板配置"), clip.removePrefix("\uFEFF"))
                            }
                        }) {
                        if (busy == "clipboard") HxSpinner(18.dp) else HxChevron()
                    }
                    SettingsDivider()
                    Column(Modifier.padding(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        HxTextField(
                            url, { url = it }, Modifier.fillMaxWidth(),
                            label = { Text(ht("从链接下载")) },
                            placeholder = { Text("https://…/config.yaml") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        )
                        HxButton(ht("下载并预览"), {
                            task("url") {
                                val (fileName, text) = ConfigText.download(url)
                                stage("链接", fileName, text)
                            }
                        }, Modifier.fillMaxWidth(), icon = HxIcons.CloudDownload, enabled = busy == null && url.isNotBlank(), busy = busy == "url")
                    }
                }
            }
        }
        failure?.let { text ->
            item(key = "failure") {
                SettingsSection { HxBanner(text, tone = HxTone.Bad, actionLabel = "关闭", onAction = { failure = null }) }
            }
        }
        result?.let { outcome ->
            item(key = "result") {
                SettingsSection {
                    HxBanner(
                        outcome.message,
                        tone = when (outcome) {
                            is ConfigApplyResult.Applied -> HxTone.Good
                            is ConfigApplyResult.NeedsRestart -> HxTone.Warn
                            is ConfigApplyResult.RolledBack -> HxTone.Bad
                        },
                        actionLabel = if (outcome is ConfigApplyResult.NeedsRestart && vm.operation == null && vm.state.running) "立即重启" else null,
                        onAction = { vm.restart() },
                    )
                }
            }
        }
        candidate?.let { pending ->
            item(key = "preview") {
                SettingsSection {
                    SettingsGroup(title = ht("预检") + " · " + pending.origin) {
                        val check = pending.check
                        if (check.error != null) {
                            SettingsRow(ht("无法导入"), subtitle = check.error, icon = HomeIcons.CircleAlert, iconTint = c.bad)
                        } else {
                            SettingsRow(
                                ht("YAML 结构"),
                                subtitle = "${check.lines} " + ht("行") + " · ${HxFormat.bytes(check.bytes.toLong())} · " +
                                    ht("节点") + " ${check.proxies} · " + ht("订阅") + " ${check.providers} · " + ht("策略组") + " ${check.groups} · " + ht("规则") + " ${check.rules}",
                                icon = HomeIcons.CircleCheck, iconTint = c.good,
                            )
                            check.warnings.forEach { warning ->
                                SettingsDivider()
                                SettingsRow(warning, icon = HomeIcons.TriangleAlert, iconTint = c.warn, compact = true)
                            }
                        }
                    }
                }
            }
            if (pending.check.ok) {
                item(key = "target") {
                    SettingsSection {
                        SettingsGroup(title = ht("导入方式")) {
                            Column(Modifier.padding(start = 14.dp, end = 14.dp, top = 4.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                HxSegmented(
                                    options = listOf(ImportMode.New.name to ht("新配置并启用"), ImportMode.Replace.name to ht("覆盖当前配置")),
                                    selected = mode.name,
                                    onSelect = { mode = ImportMode.valueOf(it) },
                                    enabled = busy == null && current != null,
                                )
                                if (mode == ImportMode.New || current == null) {
                                    HxTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text(ht("配置名称")) }, singleLine = true)
                                } else {
                                    Text(ht("将覆盖「") + current.orEmpty() + ht("」，原内容会先备份。"), color = c.t2, style = HomeType.note)
                                }
                            }
                        }
                    }
                }
                pending.diff?.let { diff ->
                    item(key = "diff") {
                        SettingsSection {
                            SettingsGroup(title = ht("与当前配置的差异") + " · +${diff.added} / −${diff.removed}") {
                                Box(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp)) { ConfigDiffPreview(diff) }
                            }
                        }
                    }
                }
                item(key = "apply") {
                    SettingsSection {
                        HxButton(
                            ht("校验并应用"), ::apply, Modifier.fillMaxWidth(), icon = HomeIcons.CircleCheck,
                            enabled = busy == null, busy = busy == "apply",
                        )
                        Text(
                            ht(if (vm.state.running) "先由核心校验；代理运行中会立即热重载，核心拒绝时自动恢复原配置。" else "先由核心校验；代理未运行，下次启动时生效。"),
                            Modifier.padding(top = 8.dp, start = 4.dp, end = 4.dp), color = c.t2, style = HomeType.note,
                        )
                    }
                }
            }
        }
        item(key = "backups") {
            SettingsSection {
                SettingsGroup(title = ht("备份与恢复")) {
                    if (backups.isEmpty()) {
                        SettingsRow(ht("暂无备份"), subtitle = ht("编辑或导入覆盖前会自动备份原配置（每个配置保留 10 份）"), icon = HomeIcons.Clock, iconTint = c.t3)
                    }
                    backups.forEachIndexed { index, backup ->
                        if (index > 0) SettingsDivider()
                        SettingsRow(
                            backup.configName,
                            subtitle = HxFormat.ago(backup.createdAt) + " · " + HxFormat.bytes(backup.size) + " · " + ht(if (backup.reason == "import") "导入前" else "编辑前"),
                            icon = HomeIcons.RotateCcw, enabled = busy == null, compact = true,
                            onClick = { restoreTarget = backup },
                        ) { if (busy == "restore-${backup.file.name}") HxSpinner(18.dp) else HxChevron() }
                    }
                }
            }
        }
    }

    restoreTarget?.let { backup ->
        HxConfirmDialog(
            title = "恢复这份备份？",
            message = "将用 ${HxFormat.ago(backup.createdAt)} 的备份覆盖「${backup.configName}」，当前内容会先备份。",
            confirmLabel = "恢复",
            onConfirm = {
                restoreTarget = null
                task("restore-${backup.file.name}") {
                    val outcome = LibraryConfigStore.transaction(context, vm).restore(backup)
                    result = outcome
                    vm.toast(outcome.message)
                    vm.refreshNow()
                    revision++
                }
            },
            onDismiss = { restoreTarget = null },
        )
    }
}

/** Non-composable label helper for text built inside coroutines. */
private fun ht0(text: String): String = text

private fun importDisplayName(context: Context, uri: Uri): String? {
    val queried = try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    } catch (_: Exception) {
        null
    }
    return queried ?: uri.lastPathSegment
}

/** Unified diff preview: monospace, coloured, scrollable both ways, height-bounded. */
@Composable
internal fun ConfigDiffPreview(diff: ConfigDiff, modifier: Modifier = Modifier) {
    val c = LocalHomeColors.current
    val text: AnnotatedString = remember(diff, c) {
        buildAnnotatedString {
            if (diff.lines.isEmpty()) append("（无差异）")
            diff.lines.forEachIndexed { index, line ->
                if (index > 0) append('\n')
                when (line.kind) {
                    '+' -> withStyle(SpanStyle(color = c.good, background = c.goodSoft)) { append("+ ").append(line.text) }
                    '-' -> withStyle(SpanStyle(color = c.bad, background = c.badSoft)) { append("- ").append(line.text) }
                    '…' -> withStyle(SpanStyle(color = c.t3)) { append("⋯ ${line.text} 行未改动") }
                    else -> withStyle(SpanStyle(color = c.t2)) { append("  ").append(line.text) }
                }
            }
            if (diff.truncated) withStyle(SpanStyle(color = c.t3)) { append("\n⋯ 仅显示前 ${diff.lines.size} 行差异") }
        }
    }
    Box(
        modifier.fillMaxWidth().heightIn(max = 320.dp).clip(HomeDims.innerShape).background(if (c.dark) c.sunken else c.bg)
            .verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(text, style = HomeType.mono.copy(fontSize = 12.sp, lineHeight = 17.sp), softWrap = false)
    }
}
