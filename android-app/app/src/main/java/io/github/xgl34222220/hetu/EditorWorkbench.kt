package io.github.xgl34222220.hetu

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.EditorSearcher
import io.github.xgl34222220.hetu.ui.*
import kotlinx.coroutines.delay

/** Only explicit input mutates the native editor. Tab never inserts a YAML tab character. */
internal fun applyYamlAccessory(editor: CodeEditor, symbol: String) {
    if (!editor.isEditable) return
    if (symbol == "Tab") {
        if (editor.cursor.isSelected) editor.indentSelection()
        else editor.insertText("  ", 2)
    } else {
        require(symbol in yamlWorkbenchSymbols) { "Unknown YAML accessory" }
        editor.insertText(symbol, symbol.length)
    }
    editor.requestFocus()
    editor.ensureSelectionVisible()
}

internal val yamlWorkbenchSymbols = listOf("Tab", "<", ">", "{", "}", "[", "]", ":", "-", "'", "\"", "#", "$")

@Composable
internal fun YamlWorkbenchActions(canUndo: Boolean, canRedo: Boolean, saving: Boolean,
    undo: () -> Unit, redo: () -> Unit, search: () -> Unit, outline: () -> Unit, save: () -> Unit) {
    val t = LocalHetuTokens.current
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag("yaml-workbench-actions")
        .crystalMaterial(RoundedCornerShape(14.dp), depth = CrystalDepth.InsetItem),
        horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        @Composable fun Action(label: String, icon: ImageVector, enabled: Boolean = true, click: () -> Unit) {
            IconButton(onClick = click, enabled = enabled, modifier = Modifier.size(48.dp).testTag("yaml-action:$label")) {
                if (label == "保存" && saving) HetuBusyIndicator(Modifier.size(18.dp))
                else Icon(icon, label, Modifier.size(19.dp), tint = if (enabled) MaterialTheme.colorScheme.primary else t.textMuted.copy(alpha = .45f))
            }
        }
        Action("撤销", Icons.Rounded.Undo, canUndo && !saving, undo)
        Action("重做", Icons.Rounded.Redo, canRedo && !saving, redo)
        Action("搜索", Icons.Rounded.Search, !saving, search)
        Action("语法大纲", Icons.Rounded.FormatListBulleted, !saving, outline)
        Action("保存", Icons.Rounded.Save, !saving, save)
    }
}

/** Fixed above the IME by the containing Column's imePadding; keys never steal editor focus. */
@Composable
internal fun YamlWorkbenchAccessory(enabled: Boolean, onSymbol: (String) -> Unit) {
    val t = LocalHetuTokens.current
    Row(Modifier.fillMaxWidth().testTag("yaml-accessory")
        .horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
        yamlWorkbenchSymbols.forEach { symbol ->
            Box(Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                .testTag("yaml-symbol:$symbol").clip(RoundedCornerShape(12.dp))
                .clickable(enabled = enabled, role = Role.Button,
                    onClickLabel = if (symbol == "Tab") "缩进两个空格" else "输入 $symbol") { onSymbol(symbol) },
                contentAlignment = Alignment.Center) {
                Box(Modifier.background(MaterialTheme.colorScheme.primary.copy(alpha = .045f), RoundedCornerShape(9.dp))
                    .padding(horizontal = 10.dp, vertical = 7.dp), contentAlignment = Alignment.Center) {
                    Text(symbol, color = if (enabled) t.textPrimary else t.textMuted, fontSize = 13.sp,
                        fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
internal fun YamlCursorStatus(line: Int, column: Int, lines: Int, modifier: Modifier = Modifier) {
    val t = LocalHetuTokens.current
    Row(modifier.fillMaxWidth().testTag("yaml-cursor-status").padding(horizontal = 16.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text("Ln ${line.coerceAtLeast(1)}, Col ${column.coerceAtLeast(1)}", Modifier.testTag("yaml-cursor"),
            color = t.textSecondary, fontSize = 11.sp, lineHeight = 16.sp, fontFamily = FontFamily.Monospace)
        Text("${lines.coerceAtLeast(1)} 行 · 2 空格", color = t.textSecondary, fontSize = 11.sp, lineHeight = 16.sp)
    }
}

@Composable
internal fun YamlWorkbenchSearch(editor: CodeEditor?, matches: Int, onClose: () -> Unit) {
    var query by remember { mutableStateOf("") }
    var pending by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    val t = LocalHetuTokens.current
    LaunchedEffect(Unit) { focus.requestFocus() }
    LaunchedEffect(query, editor) {
        if (query.isEmpty()) { editor?.searcher?.stopSearch(); pending = false }
        else {
            pending = true
            delay(180)
            editor?.searcher?.let { it.isEnsureOccurrenceVisible = true; it.search(query, EditorSearcher.SearchOptions(true, false)) }
            pending = false
        }
    }
    DisposableEffect(editor) { onDispose { editor?.searcher?.stopSearch() } }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag("yaml-search")) {
        OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().focusRequester(focus).testTag("yaml-search-query"),
            singleLine = true, placeholder = { Text("搜索配置文本", fontSize = 13.sp) },
            textStyle = LocalTextStyle.current.copy(fontSize = 13.sp), shape = RoundedCornerShape(12.dp),
            trailingIcon = { IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, "关闭搜索") } })
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(if (query.isEmpty()) "输入关键字" else if (pending) "正在搜索…" else "$matches 个匹配",
                Modifier.weight(1f), color = t.textSecondary, fontSize = 11.sp)
            val canNavigate = !pending && query.isNotEmpty() && matches > 0
            IconButton(onClick = { if (editor?.searcher?.hasQuery() == true) editor.searcher.gotoPrevious() }, enabled = canNavigate) {
                Icon(Icons.Rounded.KeyboardArrowUp, "上一个匹配", Modifier.size(18.dp))
            }
            IconButton(onClick = { if (editor?.searcher?.hasQuery() == true) editor.searcher.gotoNext() }, enabled = canNavigate) {
                Icon(Icons.Rounded.KeyboardArrowDown, "下一个匹配", Modifier.size(18.dp))
            }
        }
    }
}
