package io.github.xgl34222220.hetu

import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Typeface
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import io.github.rosemoe.sora.event.ContentChangeEvent
import io.github.rosemoe.sora.event.SelectionChangeEvent
import io.github.rosemoe.sora.event.PublishSearchResultEvent
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.subscribeAlways
import io.github.rosemoe.sora.widget.schemes.SchemeDarcula
import io.github.rosemoe.sora.widget.schemes.SchemeGitHub
import io.github.xgl34222220.hetu.ui.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class RuntimeEditorModel : ViewModel() {
    internal var content by mutableStateOf<RuntimeFileContent?>(null)
    var draft: String? = null
}

class RuntimeFileEditorActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); enableEdgeToEdge()
        val model = ViewModelProvider(this)[RuntimeEditorModel::class.java]
        val path = intent.getStringExtra(EXTRA_RUNTIME_PATH).orEmpty()
        setContent { HetuTheme { RuntimeFileEditorScreen(path, model) { finish() } } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RuntimeFileEditorScreen(path: String, model: RuntimeEditorModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val t = LocalHetuTokens.current
    val dark = MaterialTheme.colorScheme.background.luminance() < .5f
    val editorColors = remember(dark) { HetuYamlLanguage.colors(if (dark) SchemeDarcula() else SchemeGitHub(), dark) }
    val scope = rememberCoroutineScope()
    val repo = remember { RuntimeFilesRepository(context) }
    var editor by remember { mutableStateOf<CodeEditor?>(null) }
    var loading by remember { mutableStateOf(model.content == null) }
    var working by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var failure by remember { mutableStateOf(false) }
    var dirty by remember { mutableStateOf(model.draft != null && model.draft != model.content?.text) }
    var revision by remember { mutableIntStateOf(0) }
    var discard by remember { mutableStateOf(false) }
    var search by rememberSaveable { mutableStateOf(false) }
    var jump by rememberSaveable { mutableStateOf(false) }
    var jumpLine by rememberSaveable { mutableStateOf("") }
    var wrap by rememberSaveable { mutableStateOf(false) }
    var cursorLine by remember { mutableIntStateOf(1) }
    var cursorColumn by remember { mutableIntStateOf(1) }
    var matches by remember { mutableIntStateOf(0) }
    val editable = model.content?.editable == true && !loading
    fun current(): String = editor?.text?.toString() ?: model.draft ?: model.content?.text.orEmpty()
    LaunchedEffect(revision, editor) {
        if (editor != null) {
            kotlinx.coroutines.delay(180)
            model.draft = current()
            dirty = model.draft != model.content?.text
        }
    }
    fun back() { if (working) return; if (editable && current() != model.content?.text) discard = true else onBack() }
    BackHandler { if (search) search = false else back() }
    LaunchedEffect(path) {
        if (model.content != null) { loading = false; return@LaunchedEffect }
        try { model.content = repo.read(path); if (model.content?.editable != true) { failure = true; message = "二进制或压缩文件请使用预览和导出" } }
        catch (cancel: CancellationException) { throw cancel }
        catch (error: Exception) { failure = true; message = error.message ?: "读取失败" }
        finally { loading = false }
    }
    fun save() {
        if (!editable || working) return
        val text = current(); val expected = model.content!!.digest
        working = true; failure = false; message = "正在保存…"
        scope.launch {
            try {
                repo.save(path, text, expected)
                model.content = RuntimeFileContent(text, RuntimeFilesRepository.digest(text.toByteArray()), true, "UTF-8")
                model.draft = text; dirty = false; message = "已保存"
            } catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) { failure = true; message = error.message ?: "保存失败" }
            finally { working = false }
        }
    }
    fun validate() {
        if (!editable || working) return
        val text = current(); working = true; failure = false; message = "正在校验语法…"
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    when (File(path).extension.lowercase()) {
                        "yaml", "yml" -> org.yaml.snakeyaml.Yaml(org.yaml.snakeyaml.constructor.SafeConstructor(org.yaml.snakeyaml.LoaderOptions().apply { isAllowDuplicateKeys = false })).loadAll(text).forEach { }
                        "json" -> RuntimeSchemaRepository.parse(text)
                        "sh", "bash" -> {
                            val temp = File.createTempFile("hetu-syntax-", ".sh", context.cacheDir)
                            try { temp.writeText(text); val result = RootBridge.rootShell(context, "sh -n " + RootBridge.quote(temp.absolutePath), 8000); check(result.ok()) { result.output } }
                            finally { temp.delete() }
                        }
                        else -> require(!text.contains('\u0000')) { "内容包含二进制字符" }
                    }
                }
                message = "语法校验通过"
            } catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) { failure = true; message = error.message ?: "语法校验失败" }
            finally { working = false }
        }
    }
    Column(Modifier.fillMaxSize().crystalPageBackground().statusBarsPadding().navigationBarsPadding().imePadding().testTag("runtime-editor")) {
        HetuPageHeader(File(path).name.ifBlank { "运行文件编辑" }, ::back, if (dirty) "有未保存修改" else path.removePrefix(RuntimeFilesRepository.ROOT + "/")) {
            IconButton(onClick = ::save, enabled = editable && !working) { Icon(Icons.Rounded.Save, "保存") }
        }
        if (message.isNotBlank()) HetuTaskFeedback(message, failure, working, Modifier.padding(horizontal = 12.dp))
        revision
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            @Composable fun Action(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, available: Boolean = editable, click: () -> Unit) {
                IconButton(onClick = click, enabled = available && !working, modifier = Modifier.size(48.dp)) { Icon(icon, label, Modifier.size(20.dp)) }
            }
            Action("撤销", Icons.Rounded.Undo, editable && editor?.canUndo() == true) { editor?.undo() }
            Action("重做", Icons.Rounded.Redo, editable && editor?.canRedo() == true) { editor?.redo() }
            Action("搜索", Icons.Rounded.Search) { search = !search }
            Action("跳转到行", Icons.Rounded.FormatListNumbered) { jumpLine = cursorLine.toString(); jump = true }
            Action("自动换行", Icons.Rounded.WrapText) { wrap = !wrap; editor?.setWordwrap(wrap) }
            Action("语法校验", Icons.Rounded.FactCheck, click = ::validate)
            if (path.endsWith(".json", true)) {
                Action("sing-box Schema 校验", Icons.Rounded.Rule) {
                    val text = current(); working = true; failure = false
                    scope.launch {
                        try { message = RuntimeSchemaRepository.validate(context, text) }
                        catch (cancel: CancellationException) { throw cancel }
                        catch (error: Exception) { failure = true; message = error.message ?: "Schema 校验失败" }
                        finally { working = false }
                    }
                }
                Action("更新官方 Schema", Icons.Rounded.CloudDownload) {
                    working = true; failure = false; message = "正在更新 sing-box 官方 Schema…"
                    scope.launch {
                        try { RuntimeSchemaRepository.update(context); message = "官方 Schema 已更新" }
                        catch (cancel: CancellationException) { throw cancel }
                        catch (error: Exception) { failure = true; message = error.message ?: "更新失败；保留原 Schema" }
                        finally { working = false }
                    }
                }
            }
            Action("复制", Icons.Rounded.ContentCopy) { context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(File(path).name, current())); message = "已复制"; failure = false }
        }
        if (search) YamlWorkbenchSearch(editor, matches) { search = false }
        if (loading) Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { HetuBusyIndicator() }
        else if (editable) {
            AndroidView(factory = { viewContext ->
                CodeEditor(viewContext).apply {
                    setEditorLanguage(if (path.endsWith(".yaml", true) || path.endsWith(".yml", true)) HetuYamlLanguage() else HetuCodeLanguage(File(path).extension))
                    setText(model.draft ?: model.content!!.text)
                    typefaceText = Typeface.MONOSPACE; setTextSize(13f); setLineNumberEnabled(true)
                    setWordwrap(wrap); setTabWidth(2); setHighlightCurrentLine(true)
                    colorScheme = editorColors
                    subscribeAlways<ContentChangeEvent> {
                        dirty = true; revision++
                    }
                    subscribeAlways<SelectionChangeEvent> { cursorLine = cursor.rightLine + 1; cursorColumn = cursor.rightColumn + 1 }
                    subscribeAlways<PublishSearchResultEvent> { matches = if (searcher.hasQuery()) searcher.matchedPositionCount else 0 }
                    editor = this
                }
            }, modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 8.dp), update = { native ->
                if (native.colorScheme !== editorColors) native.colorScheme = editorColors
            }, onRelease = { native ->
                model.draft = native.text.toString(); if (editor === native) editor = null; native.release()
            })
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Ln $cursorLine · Col $cursorColumn · UTF-8", color = t.textSecondary, fontSize = 11.sp)
                Text("${editor?.text?.lineCount ?: 1} 行${if (wrap) " · 换行" else ""}", color = t.textSecondary, fontSize = 11.sp)
            }
            YamlWorkbenchAccessory(enabled = !working) { symbol -> editor?.let { applyYamlAccessory(it, symbol) } }
        } else Spacer(Modifier.weight(1f))
    }
    if (discard || jump) ModalBottomSheet(onDismissRequest = { discard = false; jump = false }, containerColor = Color.Transparent) {
        Column(Modifier.fillMaxWidth().liquidSheetMaterial().navigationBarsPadding().imePadding().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(if (discard) "放弃未保存修改？" else "跳转到行", color = t.textPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            if (discard) Text("当前修改尚未保存。", color = t.textSecondary)
            else LiquidGlassTextField(jumpLine, { jumpLine = it.filter(Char::isDigit).take(8) }, "行号", Modifier.fillMaxWidth(), supportingText = "共 ${editor?.text?.lineCount ?: 1} 行")
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(onClick = { discard = false; jump = false }, modifier = Modifier.weight(1f)) { Text("继续编辑") }
                Button(onClick = {
                    if (discard) { model.draft = null; discard = false; onBack() }
                    else { editor?.let { it.setSelection((jumpLine.toIntOrNull() ?: 1).coerceIn(1, it.text.lineCount) - 1, 0); it.ensureSelectionVisible() }; jump = false }
                }, modifier = Modifier.weight(1f)) { Text(if (discard) "放弃修改" else "前往") }
            }
        }
    }
}
