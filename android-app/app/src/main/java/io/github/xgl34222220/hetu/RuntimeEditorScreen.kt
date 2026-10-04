package io.github.xgl34222220.hetu

import io.github.xgl34222220.hetu.ui.ReferenceButton as Button

import io.github.xgl34222220.hetu.ui.ReferenceModalBottomSheet as ModalBottomSheet

import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Typeface
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
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
        setContent {
            val prefs = remember { getSharedPreferences("hetu", 0) }
            HetuTheme { HetuAppTheme(prefs.getString("appearance", "system").orEmpty(), prefs.getBoolean("hetuDynamicColor", false)) {
                RuntimeFileEditorScreen(path, model) { finish() }
            } }
        }
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
    var moreMenu by remember { mutableStateOf(false) }
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
    Column(Modifier.fillMaxSize().background(Hx.colors.canvas).statusBarsPadding().navigationBarsPadding().imePadding().testTag("runtime-editor")) {
        Row(
            Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = ::back, enabled = !working) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回") }
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                Text(
                    File(path).name.ifBlank { "文件编辑" },
                    color = t.textPrimary,
                    fontSize = 16.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                )
            }
            IconButton(onClick = ::save, enabled = editable && !working) {
                Icon(Icons.Rounded.CheckCircle, "保存", tint = if (editable && !working) MaterialTheme.colorScheme.primary else t.textSecondary)
            }
            Box {
                IconButton(onClick = { moreMenu = true }, modifier = Modifier.hxAnchorSource()) { Icon(Icons.Rounded.MoreHoriz, "更多") }
                RuntimeEditorReferenceMenu(expanded = moreMenu, onDismiss = { moreMenu = false }) {
                    HxMenuItem(label = "搜索", icon = Icons.Rounded.Search, onClick = {
                        moreMenu = false; search = !search
                    })
                    HxMenuItem(label = "跳转到行", icon = Icons.Rounded.FormatListNumbered, onClick = {
                        moreMenu = false; jumpLine = cursorLine.toString(); jump = true
                    })
                    HxMenuItem(label = if (wrap) "关闭自动换行" else "自动换行", icon = Icons.Rounded.WrapText, onClick = {
                        moreMenu = false; wrap = !wrap; editor?.setWordwrap(wrap)
                    })
                    HxMenuItem(label = "语法校验", icon = Icons.Rounded.FactCheck, enabled = editable && !working, onClick = {
                        moreMenu = false; validate()
                    })
                    HxMenuItem(label = "复制全部", icon = Icons.Rounded.ContentCopy, onClick = {
                        moreMenu = false
                        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(File(path).name, current()))
                        message = "已复制"; failure = false
                    })
                    if (path.endsWith(".json", true)) {
                        androidx.compose.material3.HorizontalDivider()
                        HxMenuItem(label = "sing-box Schema 校验", icon = Icons.Rounded.Rule, onClick = {
                            moreMenu = false
                            val text = current(); working = true; failure = false
                            scope.launch {
                                try { message = RuntimeSchemaRepository.validate(context, text) }
                                catch (cancel: CancellationException) { throw cancel }
                                catch (error: Exception) { failure = true; message = error.message ?: "Schema 校验失败" }
                                finally { working = false }
                            }
                        })
                        HxMenuItem(label = "更新官方 Schema", icon = Icons.Rounded.CloudDownload, onClick = {
                            moreMenu = false; working = true; failure = false; message = "正在更新 sing-box 官方 Schema…"
                            scope.launch {
                                try { RuntimeSchemaRepository.update(context); message = "官方 Schema 已更新" }
                                catch (cancel: CancellationException) { throw cancel }
                                catch (error: Exception) { failure = true; message = error.message ?: "更新失败；保留原 Schema" }
                                finally { working = false }
                            }
                        })
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        if (failure || working) HetuTaskFeedback(message, failure, working, Modifier.padding(horizontal = 12.dp))
        revision
        if (search) YamlWorkbenchSearch(editor, matches) { search = false }
        if (loading) Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { HetuBusyIndicator() }
        else if (editable) {
            AndroidView(factory = { viewContext ->
                CodeEditor(viewContext).apply {
                    setEditorLanguage(if (path.endsWith(".yaml", true) || path.endsWith(".yml", true)) HetuYamlLanguage() else HetuCodeLanguage(File(path).extension))
                    setText(model.draft ?: model.content!!.text)
                    typefaceText = Typeface.MONOSPACE; setTextSize(14f); setLineNumberEnabled(true)
                    setLineSpacingMultiplier(1.4f)
                    setLineNumberMarginLeft(18f * resources.displayMetrics.density)
                    setDividerMargin(8f * resources.displayMetrics.density, 14f * resources.displayMetrics.density)
                    setDividerWidth(0f)
                    setPadding(0, (10f * resources.displayMetrics.density).toInt(), 0, 0)
                    setWordwrap(wrap); setTabWidth(2); setHighlightCurrentLine(true)
                    colorScheme = editorColors
                    subscribeAlways<ContentChangeEvent> {
                        dirty = true; revision++
                    }
                    subscribeAlways<SelectionChangeEvent> { cursorLine = cursor.rightLine + 1; cursorColumn = cursor.rightColumn + 1 }
                    subscribeAlways<PublishSearchResultEvent> { matches = if (searcher.hasQuery()) searcher.matchedPositionCount else 0 }
                    editor = this
                }
            }, modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 12.dp).clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)), update = { native ->
                if (native.colorScheme !== editorColors) native.colorScheme = editorColors
            }, onRelease = { native ->
                model.draft = native.text.toString(); if (editor === native) editor = null; native.release()
            })
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp).height(56.dp).clip(RoundedCornerShape(bottomStart = 20.dp, bottomEnd = 20.dp)).background(Hx.colors.surface).horizontalScroll(rememberScrollState()).padding(horizontal = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                @Composable fun CompactAction(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, available: Boolean = true, click: () -> Unit) {
                    Column(Modifier.width(46.dp).height(52.dp).clickable(enabled = available && !working, onClick = click),
                        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(icon, label, Modifier.size(19.dp), tint = if (available && !working) t.textPrimary else t.textSecondary.copy(alpha = .45f))
                        Spacer(Modifier.height(3.dp))
                        Text(when (label) { "跳转到行" -> "跳行"; "自动换行" -> "换行"; "语法校验" -> "校验"; else -> label },
                            fontSize = 9.sp, color = if (available && !working) t.textPrimary else t.textSecondary.copy(alpha = .45f))
                    }
                }
                CompactAction("撤销", Icons.Rounded.Undo, editor?.canUndo() == true) { editor?.undo() }
                CompactAction("重做", Icons.Rounded.Redo, editor?.canRedo() == true) { editor?.redo() }
                CompactAction("搜索", Icons.Rounded.Search) { search = !search }
                CompactAction("跳转到行", Icons.Rounded.FormatListNumbered) { jumpLine = cursorLine.toString(); jump = true }
                CompactAction("自动换行", Icons.Rounded.WrapText) { wrap = !wrap; editor?.setWordwrap(wrap) }
                CompactAction("语法校验", Icons.Rounded.FactCheck, editable) { validate() }
                Spacer(Modifier.width(8.dp))
                Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(end = 10.dp)) {
                    Text("Ln $cursorLine · Col $cursorColumn", color = t.textSecondary, fontSize = 9.sp)
                    Text("${editor?.text?.lineCount ?: 1} 行", color = t.textSecondary, fontSize = 9.sp)
                }
            }
            if (!search) YamlWorkbenchAccessory(enabled = !working) { symbol -> editor?.let { applyYamlAccessory(it, symbol) } }
        } else {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Text("当前文件类型暂不支持编辑", color = t.textSecondary, fontSize = 13.sp)
            }
        }
    }
    if (discard || jump) HxSheet(onDismiss = { discard = false; jump = false }) {
        Column(Modifier.fillMaxWidth().imePadding().padding(horizontal = 20.dp).padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(if (discard) "放弃未保存修改？" else "跳转到行", color = t.textPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            if (discard) Text("当前修改尚未保存。", color = t.textSecondary)
            else Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("行号", color = t.textSecondary, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 4.dp))
                LiquidGlassTextField(jumpLine, { jumpLine = it.filter(Char::isDigit).take(8) }, "", Modifier.fillMaxWidth(), supportingText = "共 ${editor?.text?.lineCount ?: 1} 行")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { discard = false; jump = false }, modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(16.dp), colors = ButtonDefaults.buttonColors(containerColor = Hx.colors.surfaceMuted, contentColor = Hx.colors.accent)) { Text("继续编辑") }
                Button(onClick = {
                    if (discard) { model.draft = null; discard = false; onBack() }
                    else { editor?.let { it.setSelection((jumpLine.toIntOrNull() ?: 1).coerceIn(1, it.text.lineCount) - 1, 0); it.ensureSelectionVisible() }; jump = false }
                }, modifier = Modifier.weight(1f)) { Text(if (discard) "放弃修改" else "前往") }
            }
        }
    }
}

@Composable
private fun RuntimeEditorReferenceMenu(expanded: Boolean, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    if (!expanded) return
    val anchor = remember { HxAnchor.take() }
    if (anchor != null) HxAnchoredMenu(anchor, onDismiss) { _ -> content() }
    else HxSheet(onDismiss, title = "更多") { content() }
}
