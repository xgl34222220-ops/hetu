package io.github.xgl34222220.hetu

import android.graphics.Typeface
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme
import io.github.rosemoe.sora.widget.schemes.SchemeDarcula
import io.github.rosemoe.sora.widget.schemes.SchemeGitHub
import io.github.xgl34222220.hetu.home.HomeButton
import io.github.xgl34222220.hetu.home.HomeButtonKind
import io.github.xgl34222220.hetu.home.HomeDims
import io.github.xgl34222220.hetu.home.HomeIconButton
import io.github.xgl34222220.hetu.home.HomePill
import io.github.xgl34222220.hetu.home.HomeRowDims
import io.github.xgl34222220.hetu.home.HomeRowSubStyle
import io.github.xgl34222220.hetu.home.HomeTone
import io.github.xgl34222220.hetu.home.HomeType
import io.github.xgl34222220.hetu.home.LocalHomeColors
import io.github.xgl34222220.hetu.home.badText
import io.github.xgl34222220.hetu.home.homeRowPressTint
import io.github.xgl34222220.hetu.panel.PanelIcons
import io.github.xgl34222220.hetu.tools.ToolsIcons
import io.github.xgl34222220.hetu.ui.ht
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private class OverrideDraft(val id: String?, val name: String, val source: String)

private val overrideJsSymbols = listOf("{", "}", "(", ")", "[", "]", ";", ":", ".", ",", "\"", "'", "=", "=>", "!", "&&", "||")

/**
 * The «覆写脚本» section of the scripts page: JS scripts applied in order before every start.
 * Self-contained (own IO, sheets and feedback) so the fixed hooks and managed shell scripts above
 * are untouched. App-private files only; nothing here needs Root.
 */
@Composable
internal fun OverrideScriptsSection() {
    val context = LocalContext.current
    val c = LocalHomeColors.current
    val scope = rememberCoroutineScope()
    val store = remember { WebViewOverrideEngine.store(context) }
    var revision by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var messageError by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf<OverrideDraft?>(null) }
    var deleteTarget by remember { mutableStateOf<OverrideScript?>(null) }
    var showLastRun by remember { mutableStateOf(false) }
    val scripts by produceState(initialValue = emptyList<OverrideScript>(), revision) {
        value = try { withContext(Dispatchers.IO) { store.list() } }
        catch (cancel: CancellationException) { throw cancel }
        catch (failure: Exception) { message = failure.message ?: "覆写脚本读取失败"; messageError = true; emptyList() }
    }
    val lastRun by produceState<OverrideScriptStore.LastRun?>(initialValue = null, revision) {
        value = runCatching { withContext(Dispatchers.IO) { store.lastRun() } }.getOrNull()
    }

    fun act(done: String? = null, block: suspend () -> Unit) {
        if (busy) return
        scope.launch {
            busy = true; messageError = false; message = ""
            try { block(); if (done != null) message = done; revision++ }
            catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) { message = failure.message ?: "操作失败"; messageError = true }
            finally { busy = false }
        }
    }

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        act {
            val input = ProxyScriptImport.read(context, uri)
            require(input.name.endsWith(".js", ignoreCase = true)) { "仅支持 .js 文件" }
            val text = String(input.bytes, Charsets.UTF_8)
            withContext(Dispatchers.IO) { store.add(input.name.removeSuffix(".js").removeSuffix(".JS"), text) }
            message = "已导入覆写脚本 " + input.name
        }
    }

    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(start = HomeDims.gutter + 16.dp, end = HomeDims.gutter).padding(top = 12.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(ht("覆写脚本"), Modifier.weight(1f).semantics { heading() }, color = c.t1, style = HomeType.section)
            HomeIconButton(ToolsIcons.Download, "导入覆写脚本", {
                launchDocumentPicker({ messageError = true; message = it }) { importer.launch(arrayOf("application/javascript", "text/javascript", "text/*", "application/octet-stream")) }
            }, enabled = !busy, tint = c.t1, glyph = 22.dp)
            HomeButton("新建", { if (!busy) draft = OverrideDraft(null, "", OverrideScriptExamples.BLANK.source) },
                kind = HomeButtonKind.Ghost, icon = ToolsIcons.Plus, enabled = !busy, height = 44.dp)
        }
        Text(
            ht("每次启动前按顺序执行 main(config, profile)，返回修改后的配置；河图必需的端口与接管参数随后写入。任一脚本出错或超时，本次使用未修改的配置启动。未选择配置文件时，配置完全由脚本生成。"),
            Modifier.padding(horizontal = HomeDims.gutter + 16.dp).padding(bottom = 10.dp), color = c.t2, style = HomeType.note,
        )
        if (message.isNotBlank()) {
            HetuTaskFeedback(message, error = messageError, busy = busy, modifier = Modifier.padding(horizontal = HomeDims.gutter).padding(bottom = HomeDims.gap))
        }
        lastRun?.takeIf { it.notice.isNotBlank() }?.let { run ->
            SettingsSection {
                SettingsGroup {
                    SettingsRow(
                        ht("上次启动"),
                        subtitle = formatRunTime(run.at) + " · " + run.notice,
                        icon = if (run.fallback) HxIcons.CircleStop else HxIcons.SquareCheckBig,
                        iconTint = if (run.fallback) c.badText else c.t1,
                        onClick = { showLastRun = true },
                    )
                }
            }
        }
        if (scripts.isEmpty()) {
            SettingsSection {
                SettingsGroup {
                    SettingsRow(ht("暂无覆写脚本"), subtitle = ht("新建脚本或导入 .js 文件，可添加规则、设置 DNS、合并节点。"), icon = HxIcons.Code, enabled = false)
                }
            }
        }
        scripts.forEachIndexed { index, script ->
            val step = lastRun?.steps?.firstOrNull { it.id == script.id }
            val rowSource = remember(script.id) { MutableInteractionSource() }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = HomeDims.gutter).padding(bottom = 10.dp)
                    .clip(HomeDims.cardShape).background(c.surface).heightIn(min = 80.dp)
                    .homeRowPressTint(rowSource)
                    .clickable(interactionSource = rowSource, indication = null, enabled = !busy, role = Role.Button, onClickLabel = "编辑") {
                        act { val text = withContext(Dispatchers.IO) { store.read(script.id) }; draft = OverrideDraft(script.id, script.name, text) }
                    }
                    .padding(start = HomeRowDims.start, end = 4.dp, top = 10.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(HxIcons.Code, null, Modifier.size(HomeRowDims.icon), tint = if (script.enabled) c.t1 else c.t3)
                Spacer(Modifier.size(HomeRowDims.iconGap, 1.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(script.name, color = if (script.enabled) c.t1 else c.t2, style = HomeType.rowTitle, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    val state = when {
                        !script.enabled -> "已停用"
                        step == null -> "第 ${index + 1} 个执行"
                        step.ok -> "第 ${index + 1} 个执行 · 上次 ${step.millis} ms"
                        else -> "上次失败：" + step.detail
                    }
                    Text(ht(state), color = if (step != null && !step.ok && script.enabled) c.badText else c.t2, style = HomeRowSubStyle, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                HomeIconButton(PanelIcons.ChevronUp, "上移", { act { withContext(Dispatchers.IO) { store.move(script.id, -1) } } },
                    Modifier.size(40.dp), enabled = !busy && index > 0, tint = c.t2, glyph = 20.dp)
                HomeIconButton(PanelIcons.ChevronDown, "下移", { act { withContext(Dispatchers.IO) { store.move(script.id, 1) } } },
                    Modifier.size(40.dp), enabled = !busy && index < scripts.lastIndex, tint = c.t2, glyph = 20.dp)
                HxSwitch(script.enabled, { on -> act { withContext(Dispatchers.IO) { store.setEnabled(script.id, on) } } }, enabled = !busy)
                HomeIconButton(ToolsIcons.Trash2, "删除覆写脚本", { if (!busy) deleteTarget = script }, Modifier.size(40.dp), enabled = !busy, tint = c.t2, glyph = 20.dp)
            }
        }
    }

    draft?.let { current ->
        OverrideScriptEditorSheet(current, busy, onDismiss = { if (!busy) draft = null }) { name, source ->
            act(if (current.id == null) "覆写脚本已添加，下次启动生效" else "覆写脚本已保存，下次启动生效") {
                withContext(Dispatchers.IO) {
                    if (current.id == null) store.add(name, source) else store.update(current.id, name, source)
                }
                draft = null
            }
        }
    }

    deleteTarget?.let { target ->
        HxConfirmDialog(
            title = "删除覆写脚本",
            message = "确定删除 “" + target.name + "” 吗？",
            confirmLabel = "删除",
            danger = true,
            onConfirm = { act("已删除 " + target.name) { withContext(Dispatchers.IO) { store.delete(target.id) }; deleteTarget = null } },
            onDismiss = { if (!busy) deleteTarget = null },
        )
    }

    if (showLastRun) {
        lastRun?.let { run ->
            HxSheet(onDismiss = { showLastRun = false }, title = ht("覆写脚本运行结果")) {
                Column(
                    Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(formatRunTime(run.at) + "\n" + run.notice, color = if (run.fallback) c.badText else c.t1, style = HomeType.note)
                    run.steps.forEach { step ->
                        Column(
                            Modifier.fillMaxWidth().clip(HomeDims.innerShape).background(if (c.dark) c.sunken else c.bg).padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text((if (step.ok) "✓ " else "✗ ") + step.name + " · " + step.millis + " ms", color = if (step.ok) c.t1 else c.badText, style = HomeType.rowTitle)
                            Text(step.detail, color = c.t2, style = HomeType.note)
                            if (step.logs.isNotEmpty()) Text(step.logs.joinToString("\n"), color = c.t2, style = HomeType.mono.copy(fontSize = 12.sp, lineHeight = 17.sp))
                        }
                    }
                }
            }
        }
    }
}

private fun formatRunTime(at: Long): String =
    if (at <= 0L) "" else SimpleDateFormat("MM-dd HH:mm:ss", Locale.ROOT).format(Date(at))

/** Name, examples and the native code editor (same sora editor as the YAML workbench, JS lexer). */
@Composable
private fun OverrideScriptEditorSheet(draft: OverrideDraft, busy: Boolean, onDismiss: () -> Unit, onSave: (String, String) -> Unit) {
    val c = LocalHomeColors.current
    var name by remember(draft) { mutableStateOf(draft.name) }
    var editor by remember { mutableStateOf<CodeEditor?>(null) }
    var problem by remember { mutableStateOf("") }
    val editorColors = remember(c.dark, c.sunken, c.bg) {
        HetuYamlLanguage.colors(if (c.dark) SchemeDarcula() else SchemeGitHub(), c.dark).apply {
            val background = (if (c.dark) c.sunken else c.bg).toArgb()
            setColor(EditorColorScheme.WHOLE_BACKGROUND, background)
            setColor(EditorColorScheme.LINE_NUMBER_BACKGROUND, background)
        }
    }
    HxSheet(onDismiss = onDismiss, title = ht(if (draft.id == null) "新建覆写脚本" else "编辑覆写脚本")) {
        Column(
            Modifier.fillMaxWidth().imePadding().padding(horizontal = 20.dp).padding(bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val nameLabel = ht("脚本名称")
            BasicTextField(
                value = name,
                onValueChange = { if (it.length <= 64) name = it.replace("\n", "") },
                singleLine = true,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(HomeDims.controlShape)
                    .background(if (c.dark) c.sunken else c.bg).semantics { contentDescription = nameLabel }.padding(horizontal = 14.dp, vertical = 13.dp),
                textStyle = HomeType.body.copy(color = c.t1),
                cursorBrush = SolidColor(c.accent),
                decorationBox = { inner -> Box { if (name.isEmpty()) Text(nameLabel, color = c.t3, style = HomeType.body); inner() } },
            )
            if (draft.id == null) {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OverrideScriptExamples.ALL.forEach { example ->
                        HomePill(ht(example.title), tone = HomeTone.Neutral, height = 32.dp, onClick = {
                            editor?.setText(example.source)
                            if (name.isBlank() || OverrideScriptExamples.ALL.any { it.title == name }) name = example.title
                        })
                    }
                }
            }
            Box(Modifier.fillMaxWidth().height(340.dp).clip(HomeDims.controlShape).background(if (c.dark) c.sunken else c.bg)) {
                AndroidView(
                    factory = { viewContext ->
                        CodeEditor(viewContext).apply {
                            setEditorLanguage(HetuJsLanguage())
                            setText(draft.source)
                            setSoftKeyboardEnabled(true)
                            typefaceText = Typeface.MONOSPACE
                            setTextSize(13f)
                            setLineSpacingMultiplier(1.35f)
                            setLineNumberEnabled(true)
                            setWordwrap(false)
                            setTabWidth(2)
                            setHighlightCurrentLine(true)
                            setDividerWidth(0f)
                            colorScheme = editorColors
                            editor = this
                        }
                    },
                    modifier = Modifier.fillMaxSize().padding(vertical = 8.dp),
                    update = { native ->
                        if (native.colorScheme !== editorColors) native.colorScheme = editorColors
                        if (native.isEditable == busy) native.setEditable(!busy)
                    },
                    onRelease = { native -> if (editor === native) editor = null; native.release() },
                )
            }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                overrideJsSymbols.forEach { symbol ->
                    val keySource = remember { MutableInteractionSource() }
                    Box(
                        Modifier.heightIn(min = 40.dp).widthIn(min = 40.dp).clip(RoundedCornerShape(12.dp)).background(if (c.dark) c.sunken else c.bg)
                            .homeRowPressTint(keySource)
                            .clickable(interactionSource = keySource, indication = null, enabled = !busy, role = Role.Button, onClickLabel = "输入 $symbol") {
                                editor?.let { if (it.isEditable) { it.insertText(symbol, symbol.length); it.requestFocus() } }
                            }.padding(horizontal = 9.dp),
                        contentAlignment = Alignment.Center,
                    ) { Text(symbol, color = c.t1, style = HomeType.mono.copy(fontSize = 15.sp, fontWeight = FontWeight.SemiBold)) }
                }
            }
            if (problem.isNotBlank()) Text(problem, color = c.badText, style = HomeType.note)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                HomeButton("取消", onDismiss, Modifier.weight(1f), kind = HomeButtonKind.Soft, enabled = !busy)
                HomeButton(if (busy) "处理中" else "保存", {
                    val text = editor?.text?.toString() ?: draft.source
                    problem = when {
                        text.length > OverrideScriptLimits.MAX_SCRIPT_CHARS -> "脚本最多 64 KiB"
                        !Regex("\\bmain\\b").containsMatchIn(text) -> "脚本需要定义 main(config, profile) 函数"
                        else -> ""
                    }
                    if (problem.isEmpty()) onSave(name, text)
                }, Modifier.weight(1f), kind = HomeButtonKind.Primary, enabled = !busy)
            }
        }
    }
}
