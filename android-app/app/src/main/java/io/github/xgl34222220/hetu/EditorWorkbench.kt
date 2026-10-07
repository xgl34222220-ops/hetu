package io.github.xgl34222220.hetu

import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.EditorSearcher
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.graphics.SolidColor
import io.github.xgl34222220.hetu.home.HomeDims
import io.github.xgl34222220.hetu.home.HomeHaptic
import io.github.xgl34222220.hetu.home.HomeIconButton
import io.github.xgl34222220.hetu.home.HomeIcons
import io.github.xgl34222220.hetu.home.HomeType
import io.github.xgl34222220.hetu.home.LocalHomeColors
import io.github.xgl34222220.hetu.home.LocalHomeHaptics
import io.github.xgl34222220.hetu.home.homeRowPressTint
import io.github.xgl34222220.hetu.panel.PanelIcons
import io.github.xgl34222220.hetu.tools.ToolsIcons
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
    val c = LocalHomeColors.current
    val haptics = LocalHomeHaptics.current
    val keys = listOf(":", "-", "[", "]", "{", "}", "#") + yamlWorkbenchSymbols.filter { it !in setOf(":", "-", "[", "]", "{", "}", "#") }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 60.dp).testTag("yaml-accessory")
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = HomeDims.gutter, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        keys.forEach { symbol ->
            // Tab is the one key that does more than type itself, so it is the one in the accent.
            val highlighted = symbol == "Tab"
            val source = remember { MutableInteractionSource() }
            Box(
                Modifier.heightIn(min = 44.dp).widthIn(min = 46.dp)
                    .testTag("yaml-symbol:$symbol")
                    .clip(RoundedCornerShape(13.dp))
                    .background(if (highlighted) c.accent else c.surface)
                    .homeRowPressTint(source)
                    .clickable(
                        interactionSource = source, indication = null,
                        enabled = enabled,
                        role = Role.Button,
                        onClickLabel = if (symbol == "Tab") "缩进两个空格" else "输入 $symbol",
                    ) { haptics(HomeHaptic.Tick); onSymbol(symbol) }
                    .padding(horizontal = if (symbol.length > 4) 9.dp else 11.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    symbol,
                    color = if (!enabled) c.t3 else if (highlighted) c.onAccent else c.t1,
                    style = HomeType.mono.copy(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
                )
            }
        }
        if (canUndo || canRedo) {
            Box(Modifier.width(1.dp).height(22.dp).background(c.line2))
            HomeIconButton(ToolsIcons.Undo2, "撤销", onUndo, enabled = enabled && canUndo, glyph = 20.dp)
            HomeIconButton(ToolsIcons.Redo2, "重做", onRedo, enabled = enabled && canRedo, glyph = 20.dp)
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
            fontFamily = io.github.xgl34222220.hetu.ui.HetuSystemFontFamily,
        )
        Text("${lines.coerceAtLeast(1)} 行", color = t.textMuted, fontSize = 10.5.sp, lineHeight = 15.sp)
    }
}

/** Find in the open file: the query, how many places match, and the way from one to the next. */
@Composable
internal fun YamlWorkbenchSearch(editor: CodeEditor?, matches: Int, onClose: () -> Unit) {
    var query by remember { mutableStateOf("") }
    var pending by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    val c = LocalHomeColors.current
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
    Row(Modifier.fillMaxWidth().padding(horizontal = HomeDims.gutter).clip(HomeDims.cardShape)
        .background(c.surface).padding(start = 8.dp, end = 2.dp, top = 6.dp, bottom = 6.dp).testTag("yaml-search"),
        verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f).clip(HomeDims.controlShape).background(c.sunken)
            .heightIn(min = 44.dp).padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(ToolsIcons.Search, null, Modifier.size(20.dp), tint = c.t2)
            Spacer(Modifier.width(8.dp))
            BasicTextField(query, { query = it }, Modifier.weight(1f).focusRequester(focus).testTag("yaml-search-query"),
                singleLine = true, textStyle = HomeType.body.copy(color = c.t1, fontWeight = FontWeight.Medium),
                cursorBrush = SolidColor(c.accent),
                decorationBox = { field -> Box { if (query.isEmpty()) Text(ht("搜索配置文本"), color = c.t3, style = HomeType.body); field() } })
            if (query.isNotEmpty()) HomeIconButton(HomeIcons.X, "清空搜索", { query = "" }, Modifier.size(40.dp), tint = c.t2, glyph = 18.dp)
        }
        Text(if (query.isEmpty()) "0 个匹配" else if (pending) ht("搜索中…") else "$matches 个匹配",
            Modifier.padding(start = 10.dp, end = 4.dp), color = c.t2, style = HomeType.caption.copy(fontWeight = FontWeight.Medium), maxLines = 1)
        val canNavigate = !pending && query.isNotEmpty() && matches > 0
        HomeIconButton(PanelIcons.ChevronUp, "上一个匹配", { if (editor?.searcher?.hasQuery() == true) editor.searcher.gotoPrevious() },
            Modifier.size(40.dp), enabled = canNavigate, glyph = 22.dp)
        HomeIconButton(PanelIcons.ChevronDown, "下一个匹配", { if (editor?.searcher?.hasQuery() == true) editor.searcher.gotoNext() },
            Modifier.size(40.dp), enabled = canNavigate, glyph = 22.dp)
        Box(Modifier.padding(horizontal = 2.dp).width(1.dp).height(22.dp).background(c.line2))
        HomeIconButton(HomeIcons.X, "关闭搜索", onClose, Modifier.size(40.dp), glyph = 20.dp)
    }
}
