package io.github.xgl34222220.hetu

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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import io.github.rosemoe.sora.event.ContentChangeEvent
import io.github.rosemoe.sora.event.PublishSearchResultEvent
import io.github.rosemoe.sora.event.SelectionChangeEvent
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.schemes.SchemeDarcula
import io.github.rosemoe.sora.widget.schemes.SchemeGitHub
import io.github.rosemoe.sora.widget.subscribeAlways
import io.github.xgl34222220.hetu.home.HomeButton
import io.github.xgl34222220.hetu.home.HomeButtonKind
import io.github.xgl34222220.hetu.home.HomeDims
import io.github.xgl34222220.hetu.home.HomeEmptyState
import io.github.xgl34222220.hetu.home.HomeFormField
import io.github.xgl34222220.hetu.home.HomeHaptic
import io.github.xgl34222220.hetu.home.HomeIconButton
import io.github.xgl34222220.hetu.home.HomeIcons
import io.github.xgl34222220.hetu.home.HomeMenuDivider
import io.github.xgl34222220.hetu.home.HomeReveal
import io.github.xgl34222220.hetu.home.HomeRowDivider
import io.github.xgl34222220.hetu.home.HomeType
import io.github.xgl34222220.hetu.home.LocalHomeColors
import io.github.xgl34222220.hetu.home.LocalHomeHaptics
import io.github.xgl34222220.hetu.home.homeRowPressTint
import io.github.xgl34222220.hetu.home.homeTap
import io.github.xgl34222220.hetu.tools.ToolsFeatureIcons
import io.github.xgl34222220.hetu.tools.ToolsIcons
import io.github.xgl34222220.hetu.ui.HetuTheme
import io.github.xgl34222220.hetu.ui.ht
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
            HetuTheme { HetuAppTheme(
                prefs.getString("appearance", "system").orEmpty(), prefs.getBoolean("hetuDynamicColor", prefs.getBoolean("enableMonet", false)),
                accentHex = hxStoredAccent(prefs), pureBlack = prefs.getBoolean("pureBlackDark", false),
            ) {
                RuntimeFileEditorScreen(path, model) { finish() }
            } }
        }
    }
}

@Composable
internal fun RuntimeFileEditorScreen(path: String, model: RuntimeEditorModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val c = LocalHomeColors.current
    val haptics = LocalHomeHaptics.current
    val dark = c.dark
    val editorSurface = c.surface
    val editorColors = remember(dark, editorSurface) {
        HetuYamlLanguage.colors(if (dark) SchemeDarcula() else SchemeGitHub(), dark).apply {
            val background = editorSurface.toArgb()
            setColor(io.github.rosemoe.sora.widget.schemes.EditorColorScheme.WHOLE_BACKGROUND, background)
            setColor(io.github.rosemoe.sora.widget.schemes.EditorColorScheme.LINE_NUMBER_BACKGROUND, background)
            if (!dark) setColor(HetuYamlLanguage.YAML_KEY, 0xFF005CFF.toInt())
        }
    }
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
    // A finished action says so for a moment and then steps aside; a failure stays until the next action.
    LaunchedEffect(message, failure, working) {
        if (!failure && !working && message.isNotBlank()) { kotlinx.coroutines.delay(2200); message = "" }
    }
    val fileName = File(path).name.ifBlank { ht("文件编辑") }
    val canSave = editable && !working
    Column(Modifier.fillMaxSize().background(c.bg).statusBarsPadding().navigationBarsPadding().imePadding().testTag("runtime-editor")) {
        Row(Modifier.fillMaxWidth().height(HomeDims.barHeight).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            HomeIconButton(HomeIcons.ChevronLeft, "返回", ::back, enabled = !working, glyph = 26.dp)
            Text(
                fileName, Modifier.weight(1f).padding(horizontal = 8.dp).semantics { heading() },
                color = c.t1, style = HomeType.barTitle, maxLines = 1, overflow = TextOverflow.MiddleEllipsis, textAlign = TextAlign.Center,
            )
            // Save is the page's one main action: a filled disc while there is something to save.
            val saveLabel = ht("保存")
            Box(
                Modifier.size(48.dp).alpha(if (canSave) 1f else .4f)
                    .homeTap(enabled = canSave) { haptics(HomeHaptic.Confirm); save() }
                    .semantics { contentDescription = saveLabel },
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.size(34.dp).background(if (dirty && canSave) c.accent else c.accentSoft, CircleShape), contentAlignment = Alignment.Center) {
                    Icon(HomeIcons.Check, null, Modifier.size(20.dp), tint = if (dirty && canSave) c.onAccent else c.accent)
                }
            }
            Box {
                HxBarAction(ToolsIcons.Ellipsis, "更多", onClick = { moreMenu = true }, anchorMenu = true)
                RuntimeEditorReferenceMenu(expanded = moreMenu, onDismiss = { moreMenu = false }) {
                    HxMenuItem(label = ht("搜索"), icon = ToolsIcons.Search, onClick = {
                        moreMenu = false; search = !search
                    })
                    HxMenuItem(label = ht("跳转到行"), icon = ToolsFeatureIcons.ArrowUpDown, onClick = {
                        moreMenu = false; jumpLine = cursorLine.toString(); jump = true
                    })
                    HxMenuItem(label = ht(if (wrap) "关闭自动换行" else "自动换行"), icon = HxIcons.TextWrap, selected = wrap, onClick = {
                        moreMenu = false; wrap = !wrap; editor?.setWordwrap(wrap)
                    })
                    HxMenuItem(label = ht("语法校验"), icon = ToolsFeatureIcons.ShieldCheck, enabled = editable && !working, onClick = {
                        moreMenu = false; validate()
                    })
                    HxMenuItem(label = ht("复制全部"), icon = HomeIcons.Copy, onClick = {
                        moreMenu = false
                        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(File(path).name, current()))
                        message = "已复制"; failure = false
                    })
                    if (path.endsWith(".json", true)) {
                        HomeMenuDivider()
                        HxMenuItem(label = ht("sing-box Schema 校验"), icon = HxIcons.ListChecks, onClick = {
                            moreMenu = false
                            val text = current(); working = true; failure = false
                            scope.launch {
                                try { message = RuntimeSchemaRepository.validate(context, text) }
                                catch (cancel: CancellationException) { throw cancel }
                                catch (error: Exception) { failure = true; message = error.message ?: "Schema 校验失败" }
                                finally { working = false }
                            }
                        })
                        HxMenuItem(label = ht("更新官方 Schema"), icon = HxIcons.CloudDownload, onClick = {
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
        HomeReveal(failure || working || message.isNotBlank()) {
            HetuTaskFeedback(message, failure, working, Modifier.padding(horizontal = HomeDims.gutter).padding(bottom = 10.dp))
        }
        revision
        HomeReveal(search) {
            Box(Modifier.padding(bottom = 10.dp)) { YamlWorkbenchSearch(editor, matches) { search = false } }
        }
        if (loading) Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { HxSpinner(26.dp) }
        else if (editable) {
            // The text and its tools are one card: the editor on top, the tool row closing it.
            Column(Modifier.fillMaxWidth().weight(1f).padding(horizontal = HomeDims.gutter).clip(HomeDims.cardShape).background(c.surface)) {
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
                }, modifier = Modifier.fillMaxWidth().weight(1f), update = { native ->
                    if (native.colorScheme !== editorColors) native.colorScheme = editorColors
                }, onRelease = { native ->
                    model.draft = native.text.toString(); if (editor === native) editor = null; native.release()
                })
                HomeRowDivider(start = 0.dp, end = 0.dp)
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 62.dp).horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RuntimeEditorTool("撤销", "撤销", ToolsIcons.Undo2, editor?.canUndo() == true && !working) { editor?.undo() }
                    RuntimeEditorTool("重做", "重做", ToolsIcons.Redo2, editor?.canRedo() == true && !working) { editor?.redo() }
                    RuntimeEditorTool("搜索", "搜索", ToolsIcons.Search, !working, active = search) { search = !search }
                    RuntimeEditorTool("跳转到行", "跳行", ToolsFeatureIcons.ArrowUpDown, !working) { jumpLine = cursorLine.toString(); jump = true }
                    RuntimeEditorTool("自动换行", "换行", HxIcons.TextWrap, !working, active = wrap) { wrap = !wrap; editor?.setWordwrap(wrap) }
                    RuntimeEditorTool("语法校验", "校验", ToolsFeatureIcons.ShieldCheck, editable && !working) { validate() }
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.padding(end = 12.dp), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("Ln $cursorLine · Col $cursorColumn", color = c.t2, style = HomeType.mono.copy(fontSize = 11.sp, lineHeight = 15.sp), maxLines = 1)
                        Text("${editor?.text?.lineCount ?: 1} 行", color = c.t3, style = HomeType.badge.copy(fontWeight = FontWeight.Medium), maxLines = 1)
                    }
                }
            }
            if (!search) YamlWorkbenchAccessory(enabled = !working) { symbol -> editor?.let { applyYamlAccessory(it, symbol) } }
            else Spacer(Modifier.height(10.dp))
        } else {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                HomeEmptyState(ToolsIcons.FileWarning, "当前文件类型暂不支持编辑", message, topPadding = 0.dp, verbatimSubtitle = true)
            }
        }
    }
    if (discard || jump) HxSheet(onDismiss = { discard = false; jump = false }) {
        Column(Modifier.fillMaxWidth().imePadding().padding(horizontal = 20.dp).padding(bottom = 4.dp)) {
            Text(
                ht(if (discard) "放弃未保存修改？" else "跳转到行"), Modifier.fillMaxWidth().semantics { heading() },
                color = c.t1, style = HomeType.sheetTitle, textAlign = if (discard) TextAlign.Center else TextAlign.Start,
            )
            if (discard) Text(ht("当前修改尚未保存。"), Modifier.fillMaxWidth().padding(top = 8.dp), color = c.t2, style = HomeType.body, textAlign = TextAlign.Center)
            else HomeFormField(
                "行号", jumpLine, { jumpLine = it.filter(Char::isDigit).take(8) }, Modifier.padding(top = 16.dp),
                hint = "共 ${editor?.text?.lineCount ?: 1} 行", keyboardType = KeyboardType.Number,
            )
            Spacer(Modifier.height(22.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                HomeButton("继续编辑", { discard = false; jump = false }, Modifier.weight(1f), kind = HomeButtonKind.Soft)
                HomeButton(if (discard) "放弃修改" else "前往", {
                    if (discard) { model.draft = null; discard = false; onBack() }
                    else { editor?.let { it.setSelection((jumpLine.toIntOrNull() ?: 1).coerceIn(1, it.text.lineCount) - 1, 0); it.ensureSelectionVisible() }; jump = false }
                }, Modifier.weight(1f), kind = HomeButtonKind.Primary, danger = discard)
            }
        }
    }
}

/** One tool under the editor: a glyph over a two-character name. [label] is what it is called aloud. */
@Composable
private fun RuntimeEditorTool(label: String, short: String, icon: ImageVector, available: Boolean, active: Boolean = false, onClick: () -> Unit) {
    val c = LocalHomeColors.current
    val haptics = LocalHomeHaptics.current
    val source = remember { MutableInteractionSource() }
    val spoken = ht(label)
    Column(
        Modifier.widthIn(min = 50.dp).heightIn(min = 52.dp).clip(RoundedCornerShape(14.dp))
            .background(if (active) c.accentSoft else androidx.compose.ui.graphics.Color.Transparent)
            .homeRowPressTint(source)
            .clickable(interactionSource = source, indication = null, enabled = available, role = Role.Button) { haptics(HomeHaptic.Tap); onClick() }
            .alpha(if (available) 1f else .38f)
            .padding(horizontal = 6.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterVertically), horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, spoken, Modifier.size(22.dp), tint = if (active) c.accent else c.t1)
        Text(ht(short), color = if (active) c.accent else c.t2, style = HomeType.badge.copy(fontWeight = FontWeight.Medium), maxLines = 1)
    }
}

@Composable
private fun RuntimeEditorReferenceMenu(expanded: Boolean, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    if (!expanded) return
    val anchor = remember { HxAnchor.take() }
    if (anchor != null) HxAnchoredMenu(anchor, onDismiss, anchorEndInset = 0.dp) { _ -> content() }
    else HxSheet(onDismiss, title = ht("更多")) { Column(Modifier.padding(horizontal = 8.dp), content = content) }
}
