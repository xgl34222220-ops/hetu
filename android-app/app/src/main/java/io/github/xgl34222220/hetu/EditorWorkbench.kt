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
        val inserted = when (symbol) {
            ":" -> ": "
            "-" -> "- "
            "#" -> "# "
            else -> symbol
        }
        editor.insertText(inserted, inserted.length)
    }
    editor.requestFocus()
    editor.ensureSelectionVisible()
}

internal val yamlWorkbenchSymbols = listOf("Tab", ":", "-", "#", "\"", "'", "[", "]", "{", "}", "=", "true", "false", "|")

@Composable
internal fun YamlWorkbenchActions(
    canUndo: Boolean,
    canRedo: Boolean,
    saving: Boolean,
    validating: Boolean,
    undo: () -> Unit,
    redo: () -> Unit,
    format: () -> Unit,
    search: () -> Unit,
    outline: () -> Unit,
    validate: () -> Unit,
    save: () -> Unit,
) {
    val t = LocalHetuTokens.current
    val working = saving || validating
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag("yaml-workbench-actions")
            .crystalMaterial(RoundedCornerShape(14.dp), depth = CrystalDepth.InsetItem)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(1.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        @Composable fun Action(label: String, icon: ImageVector, enabled: Boolean = true, busy: Boolean = false, click: () -> Unit) {
            IconButton(onClick = click, enabled = enabled, modifier = Modifier.size(48.dp).testTag("yaml-action:$label")) {
                if (busy) HetuBusyIndicator(Modifier.size(18.dp))
                else Icon(icon, label, Modifier.size(19.dp), tint = if (enabled) MaterialTheme.colorScheme.primary else t.textMuted.copy(alpha = .45f))
            }
        }
        Action("撤销", Icons.Rounded.Undo, canUndo && !working, click = undo)
        Action("重做", Icons.Rounded.Redo, canRedo && !working, click = redo)
        Action("格式化", Icons.Rounded.AutoFixHigh, !working, click = format)
        Action("搜索", Icons.Rounded.Search, !working, click = search)
        Action("语法大纲", Icons.Rounded.FormatListBulleted, !working, click = outline)
        Action("校验", Icons.Rounded.FactCheck, !saving, busy = validating, click = validate)
        Action("保存", Icons.Rounded.Save, !validating, busy = saving, click = save)
    }
}
/** Fixed above the IME by the containing Column's imePadding; keys never steal editor focus. */
@Composable
internal fun YamlWorkbenchAccessory(
    enabled: Boolean,
    canUndo: Boolean = false,
    canRedo: Boolean = false,
    onUndo: () -> Unit = {},
    onRedo: () -> Unit = {},
    onSymbol: (String) -> Unit,
) {
    val primary = MaterialTheme.colorScheme.primary
    val keys = yamlWorkbenchSymbols
    Row(
        Modifier.fillMaxWidth().height(42.dp).testTag("yaml-accessory")
            .background(Color(0xFF0F172A))
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 6.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        keys.forEach { symbol ->
            val highlighted = symbol == "Tab"
            Box(
                Modifier.height(32.dp).widthIn(min = 34.dp)
                    .testTag("yaml-symbol:$symbol")
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (highlighted) primary else Color(0xFF1E293B))
                    .clickable(
                        enabled = enabled,
                        role = Role.Button,
                        onClickLabel = if (symbol == "Tab") "缩进两个空格" else "输入 $symbol",
                    ) { onSymbol(symbol) }
                    .padding(horizontal = if (symbol.length > 4) 7.dp else 9.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    symbol,
                    color = if (enabled) Color.White else Color(0xFF64748B),
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
        Box(Modifier.width(1.dp).height(20.dp).background(Color(0xFF334155)))
        listOf(
            Triple("撤销", Icons.Rounded.Undo, canUndo),
            Triple("重做", Icons.Rounded.Redo, canRedo),
        ).forEach { (label, icon, available) ->
            Box(
                Modifier.size(32.dp).clip(RoundedCornerShape(6.dp))
                    .clickable(enabled = enabled && available, role = Role.Button) {
                        if (label == "撤销") onUndo() else onRedo()
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    icon,
                    label,
                    Modifier.size(16.dp),
                    tint = if (enabled && available) Color(0xFF94A3B8) else Color(0xFF475569),
                )
            }
        }
    }
}

@Composable
internal fun YamlCursorStatus(line: Int, column: Int, lines: Int, modifier: Modifier = Modifier) {
    val t = LocalHetuTokens.current
    Row(modifier.fillMaxWidth().testTag("yaml-cursor-status").padding(horizontal = 16.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(
            "Ln ${line.coerceAtLeast(1)} · Col ${column.coerceAtLeast(1)} · UTF-8 · YAML",
            Modifier.testTag("yaml-cursor"),
            color = t.textSecondary,
            fontSize = 10.5.sp,
            lineHeight = 15.sp,
            fontFamily = FontFamily.Monospace,
        )
        Text("${lines.coerceAtLeast(1)} 行", color = t.textMuted, fontSize = 10.5.sp, lineHeight = 15.sp)
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
