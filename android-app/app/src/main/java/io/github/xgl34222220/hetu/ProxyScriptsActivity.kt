package io.github.xgl34222220.hetu

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.xgl34222220.hetu.home.HomeButton
import io.github.xgl34222220.hetu.home.HomeButtonKind
import io.github.xgl34222220.hetu.home.HomeDims
import io.github.xgl34222220.hetu.home.HomeIconButton
import io.github.xgl34222220.hetu.home.HomeMenuDivider
import io.github.xgl34222220.hetu.home.HomeRowDims
import io.github.xgl34222220.hetu.home.HomeRowSubStyle
import io.github.xgl34222220.hetu.home.HomeType
import io.github.xgl34222220.hetu.home.LocalHomeColors
import io.github.xgl34222220.hetu.tools.ToolsIcons
import io.github.xgl34222220.hetu.ui.HetuTheme
import io.github.xgl34222220.hetu.ui.ht
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

class ProxyScriptsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { HetuTheme { ProxyScriptsScreen { finish() } } }
    }
}

private data class ScriptEntry(
    val title: String,
    val subtitle: String,
    val path: String,
)

private fun scriptSize(bytes: Long): String = when {
    bytes >= 1024L -> String.format(java.util.Locale.US, "%.1f KiB", bytes / 1024.0)
    else -> "$bytes B"
}

@Composable
private fun ProxyScriptsScreen(onBack: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val c = LocalHomeColors.current
    val scope = rememberCoroutineScope()
    val entries = remember {
        listOf(
            ScriptEntry(
                "服务启动前",
                "代理核心启动前执行；非 0 退出码会阻止手动启动",
                ProxyScriptHooks.PRE_START,
            ),
            ScriptEntry(
                "服务停止后",
                "代理网络恢复完成后执行；非 0 退出码会报告失败",
                ProxyScriptHooks.POST_STOP,
            ),
        )
    }
    var editor by remember { mutableStateOf<ScriptEntry?>(null) }
    var text by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var messageError by remember { mutableStateOf(false) }
    var environment by remember { mutableStateOf(false) }
    var managedRevision by remember { mutableIntStateOf(0) }
    var refreshing by remember { mutableStateOf(false) }
    var hookRevision by remember { mutableIntStateOf(0) }
    var menuOpen by remember { mutableStateOf(false) }
    var menuAnchor by remember { mutableStateOf<Rect?>(null) }
    var deleteTarget by remember { mutableStateOf<ManagedScript?>(null) }
    val fixedStatus by produceState(initialValue = mapOf<String, Boolean>(), hookRevision) {
        value = try { entries.associate { it.path to ProxyScriptHooks.read(context, it.path).isNotBlank() } }
        catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { emptyMap() }
    }
    val managedScripts by produceState(initialValue = emptyList<ManagedScript>(), managedRevision) {
        value = try {
            ProxyScriptHooks.listManaged(context)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (failure: Exception) {
            message = failure.message ?: "脚本列表读取失败"; messageError = true
            emptyList()
        } finally { refreshing = false }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null || busy) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            messageError = false; message = ""
            try {
                val input = ProxyScriptImport.read(context, uri)
                val imported = ProxyScriptHooks.importManaged(context, input.name, input.bytes)
                message = "已导入 " + imported.name
                managedRevision++
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (failure: Exception) {
                message = failure.message ?: "脚本导入失败"; messageError = true
            } finally {
                busy = false
            }
        }
    }

    fun open(entry: ScriptEntry) {
        if (busy) return
        scope.launch {
            busy = true
            messageError = false; message = ""
            try {
                text = ProxyScriptHooks.read(context, entry.path)
                editor = entry
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (failure: Exception) {
                message = failure.message ?: "脚本读取失败"; messageError = true
            } finally {
                busy = false
            }
        }
    }

    fun pickScript() = launchDocumentPicker({ messageError = true; message = it }) { importer.launch(arrayOf("text/*", "application/octet-stream")) }

    HxPage(
        title = ht("脚本"),
        largeTitle = false,
        onBack = onBack,
        refreshing = refreshing,
        onRefresh = { if (!busy && !refreshing) { refreshing = true; managedRevision++; hookRevision++ } },
        actions = {
            Box(Modifier.onGloballyPositioned { menuAnchor = it.boundsInWindow() }) {
                HxBarAction(ToolsIcons.Ellipsis, "更多", onClick = { menuOpen = true })
            }
        },
    ) {
        // The outcome of the last action sits right under the bar, where it cannot be missed.
        if (message.isNotBlank()) {
            item("feedback") {
                HetuTaskFeedback(message, error = messageError, busy = busy, modifier = Modifier.padding(horizontal = HomeDims.gutter).padding(bottom = HomeDims.gap))
            }
        }
        item("hooks") {
            SettingsSection {
                SettingsGroup {
                    entries.forEach { entry ->
                        SettingsNavRow(
                            ht(entry.title),
                            icon = if (entry.path == ProxyScriptHooks.PRE_START) HxIcons.CirclePlay else HxIcons.CircleStop,
                            value = ht(if (fixedStatus[entry.path] == true) "已设置" else "不设置"),
                            dropdown = true,
                        ) { open(entry) }
                        SettingsDivider()
                    }
                    SettingsNavRow(ht("脚本环境"), icon = ToolsIcons.SquareTerminal) { environment = true }
                }
            }
        }
        item("custom-title") {
            Row(
                Modifier.fillMaxWidth().hxPageEnter().padding(start = HomeDims.gutter + 16.dp, end = HomeDims.gutter).padding(top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(ht("自定义脚本"), Modifier.weight(1f).semantics { heading() }, color = c.t1, style = HomeType.section)
                HomeButton("导入", { pickScript() }, kind = HomeButtonKind.Ghost, icon = ToolsIcons.Plus, enabled = !busy, height = 44.dp)
            }
        }
        if (managedScripts.isEmpty()) {
            item("empty") {
                SettingsSection {
                    SettingsGroup {
                        SettingsRow(ht("暂无脚本"), subtitle = ht("导入的脚本会列在这里，可以随时手动执行。"), icon = HxIcons.FileTerminal, enabled = false)
                    }
                }
            }
        }
        items(managedScripts, key = { it.name }) { script ->
            Row(
                Modifier.fillMaxWidth().padding(horizontal = HomeDims.gutter).padding(bottom = 10.dp)
                    .clip(HomeDims.cardShape).background(c.surface).heightIn(min = 80.dp)
                    .padding(start = HomeRowDims.start, end = 8.dp, top = 12.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(HxIcons.FileTerminal, null, Modifier.size(HomeRowDims.icon), tint = c.t1)
                Spacer(Modifier.size(HomeRowDims.iconGap, 1.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(script.name, color = c.t1, style = HomeType.rowTitle, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(scriptSize(script.size), color = c.t2, style = HomeRowSubStyle)
                }
                HomeIconButton(HxIcons.Play, "执行", {
                    if (!busy) scope.launch {
                        busy = true; messageError = false; message = ""
                        try { message = ProxyScriptHooks.runManaged(context, script.name) }
                        catch (cancel: CancellationException) { throw cancel }
                        catch (failure: Exception) { message = failure.message ?: "脚本执行失败"; messageError = true }
                        finally { busy = false }
                    }
                }, enabled = !busy, tint = c.accent)
                HomeIconButton(ToolsIcons.Trash2, "删除", { if (!busy) deleteTarget = script }, enabled = !busy, tint = c.t2, glyph = 22.dp)
            }
        }
    }

    if (menuOpen) {
        menuAnchor?.let { anchor ->
            HxAnchoredMenu(anchor, onDismiss = { menuOpen = false }, minWidth = 200.dp, anchorEndInset = 0.dp) { close ->
                HxMenuItem(ht("导入自定义脚本"), icon = ToolsIcons.Plus, onClick = { close { menuOpen = false; pickScript() } })
                HomeMenuDivider()
                HxMenuItem(ht("脚本环境"), icon = ToolsIcons.SquareTerminal, onClick = { close { menuOpen = false; environment = true } })
            }
        }
    }

    editor?.let { entry ->
        HxSheet(onDismiss = { if (!busy) editor = null }, title = ht(entry.title)) {
            Column(
                Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text(ht(entry.subtitle), color = c.t2, style = HomeType.note)
                ScriptCodeField(
                    value = text,
                    onValueChange = { if (it.length <= 64 * 1024) text = it },
                    enabled = !busy,
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    HomeButton("清空", {
                        scope.launch {
                            busy = true
                            try {
                                ProxyScriptHooks.clear(context, entry.path)
                                text = ""
                                messageError = false; message = "已清空${entry.title}脚本"; hookRevision++
                            } catch (failure: Exception) {
                                message = failure.message ?: "清空失败"; messageError = true
                            } finally {
                                busy = false
                            }
                        }
                    }, Modifier.weight(1f), kind = HomeButtonKind.Soft, enabled = !busy)
                    HomeButton(if (busy) "处理中" else "保存", {
                        scope.launch {
                            busy = true
                            try {
                                ProxyScriptHooks.write(context, entry.path, text)
                                messageError = false; message = "${entry.title}脚本已保存"
                                hookRevision++
                                editor = null
                            } catch (failure: Exception) {
                                message = failure.message ?: "保存失败"; messageError = true
                            } finally {
                                busy = false
                            }
                        }
                    }, Modifier.weight(1f), kind = HomeButtonKind.Primary, enabled = !busy)
                }
            }
        }
    }

    deleteTarget?.let { target ->
        HxConfirmDialog(
            title = "删除脚本",
            message = "确定删除 “" + target.name + "” 吗？\n此操作只删除自定义脚本，不会影响固定启动/停止 Hook。",
            confirmLabel = "删除",
            danger = true,
            onConfirm = {
                scope.launch {
                    busy = true
                    try {
                        ProxyScriptHooks.deleteManaged(context, target.name)
                        messageError = false; message = "已删除 " + target.name
                        managedRevision++
                        deleteTarget = null
                    } catch (cancel: CancellationException) {
                        throw cancel
                    } catch (failure: Exception) {
                        message = failure.message ?: "脚本删除失败"; messageError = true
                    } finally {
                        busy = false
                    }
                }
            },
            onDismiss = { if (!busy) deleteTarget = null },
        )
    }

    if (environment) {
        HxSheet(onDismiss = { environment = false }, title = ht("脚本环境")) {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                val environmentLines = remember { ProxyScriptHooks.environmentText().lines() }
                // The variables a script can read, each with what it holds.
                Column(
                    Modifier.fillMaxWidth().clip(HomeDims.innerShape).background(if (c.dark) c.sunken else c.bg).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    environmentLines.takeWhile { it.isNotBlank() }.forEach { line ->
                        val parts = line.trim().split(Regex("\\s+"), limit = 2)
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(parts.first(), color = c.t1, style = HomeType.mono.copy(fontSize = 14.sp, fontWeight = FontWeight.SemiBold))
                            Text(parts.getOrElse(1) { "" }, color = c.t2, style = HomeType.note)
                        }
                    }
                }
                Text(
                    environmentLines.dropWhile { it.isNotBlank() }.dropWhile { it.isBlank() }.joinToString("\n"),
                    Modifier.padding(horizontal = 4.dp), color = c.t2, style = HomeType.note,
                )
            }
        }
    }
}

/** A bounded, top-aligned editor; long hook content scrolls inside its own input well. */
@Composable
private fun ScriptCodeField(value: String, onValueChange: (String) -> Unit, enabled: Boolean) {
    val c = LocalHomeColors.current
    val label = ht("脚本内容")
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, Modifier.padding(horizontal = 2.dp), color = c.t1, style = HomeType.cardLabel)
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth().height(280.dp).clip(HomeDims.controlShape)
                .background(if (c.dark) c.sunken else c.bg)
                .semantics { contentDescription = label }
                .padding(14.dp),
            textStyle = HomeType.mono.copy(color = c.t1, fontSize = 14.sp, lineHeight = 21.sp),
            cursorBrush = SolidColor(c.accent),
            decorationBox = { inner ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopStart) {
                    if (value.isEmpty()) Text("#!/system/bin/sh\n# " + ht("在这里输入脚本"), color = c.t3, style = HomeType.mono.copy(fontSize = 14.sp, lineHeight = 21.sp))
                    inner()
                }
            },
        )
    }
}
