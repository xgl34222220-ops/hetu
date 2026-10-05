package io.github.xgl34222220.hetu.tools

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.xgl34222220.hetu.tools.ToolsTypography as HomeType
import io.github.xgl34222220.hetu.home.LocalHomeColors

/* ------------------------------------------------------------------ */
/*  State holder                                                        */
/* ------------------------------------------------------------------ */

/**
 * Text, selection and undo history of the YAML editor.
 *
 * Undo steps are coalesced the way people expect on a phone: a run of typed characters is one
 * step, a run of backspaces is one step, and anything else (paste, accessory key, newline,
 * moving the caret in between) starts a new one. Undo and redo together retain at most
 * [HistoryLimit] snapshots and [HistoryTextByteLimit] estimated UTF-16 text bytes. The
 * current text is never evicted; the oldest retained snapshot is removed when needed.
 */
@Stable
internal class ToolsYamlEditorState(initial: String = "") {
    var value by mutableStateOf(TextFieldValue(initial))
        private set
    var canUndo by mutableStateOf(false)
        private set
    var canRedo by mutableStateOf(false)
        private set

    /** 1-based line tinted after a jump; cleared by the next edit. */
    var focusLine by mutableStateOf<Int?>(null)
        private set

    /** Bumped by [jumpToLine] so the widget scrolls even when the same line is requested twice. */
    var jumpTick by mutableIntStateOf(0)
        private set

    private data class HistorySnapshot(val value: TextFieldValue, val order: Long) {
        val textBytes: Long get() = value.text.length.toLong() * 2L
    }

    private val undoStack = ArrayDeque<HistorySnapshot>()
    private val redoStack = ArrayDeque<HistorySnapshot>()
    private var nextHistoryOrder = 0L
    private var retainedTextBytes = 0L
    private var lastKind = EditOther

    val text: String get() = value.text
    /** Conservative text estimate; excludes the current buffer and object overhead. */
    internal val historyTextBytes: Long get() = retainedTextBytes

    /** Replaces the content and forgets the history (first load, or reload from disk). */
    fun reset(text: String) {
        value = TextFieldValue(text)
        undoStack.clear()
        redoStack.clear()
        retainedTextBytes = 0L
        nextHistoryOrder = 0L
        lastKind = EditOther
        focusLine = null
        sync()
    }

    fun onValueChange(next: TextFieldValue) {
        val previous = value
        if (next.text != previous.text) {
            val kind = editKind(previous, next)
            // A new edit abandons redo before reserving room for its undo snapshot.
            clearRedo()
            if (kind == EditOther || kind != lastKind) retain(undoStack, previous)
            lastKind = kind
            focusLine = null
        } else if (next.selection != previous.selection) {
            lastKind = EditOther
        }
        value = next
        sync()
    }

    fun undo() {
        val target = takeLast(undoStack) ?: return
        retain(redoStack, value)
        value = target
        lastKind = EditOther
        focusLine = null
        sync()
    }

    fun redo() {
        val target = takeLast(redoStack) ?: return
        retain(undoStack, value)
        value = target
        lastKind = EditOther
        focusLine = null
        sync()
    }

    /** Accessory key: replaces the selection with what [ToolsYaml.symbolInsertion] returns. */
    fun insert(symbol: String) {
        val insertion = ToolsYaml.symbolInsertion(symbol)
        val current = value
        val start = current.selection.min.coerceIn(0, current.text.length)
        val end = current.selection.max.coerceIn(start, current.text.length)
        clearRedo()
        retain(undoStack, current)
        lastKind = EditOther
        focusLine = null
        value = TextFieldValue(current.text.substring(0, start) + insertion + current.text.substring(end), TextRange(start + insertion.length))
        sync()
    }

    /** Moves the caret to the first column of the 1-based [line], tints it and scrolls to it. */
    fun jumpToLine(line: Int) {
        val target = line.coerceIn(1, ToolsYaml.lineCount(value.text))
        value = TextFieldValue(value.text, TextRange(ToolsYaml.lineStart(value.text, target)))
        lastKind = EditOther
        focusLine = target
        jumpTick++
    }

    private fun retain(stack: ArrayDeque<HistorySnapshot>, value: TextFieldValue) {
        val snapshot = HistorySnapshot(plain(value), nextHistoryOrder++)
        // Oversized text may still be edited or restored, but cannot consume history memory.
        if (snapshot.textBytes > HistoryTextByteLimit) return
        stack.addLast(snapshot)
        retainedTextBytes += snapshot.textBytes
        while (undoStack.size + redoStack.size > HistoryLimit || retainedTextBytes > HistoryTextByteLimit) {
            val undo = undoStack.firstOrNull()
            val redo = redoStack.firstOrNull()
            val oldest = if (redo == null || (undo != null && undo.order < redo.order)) undoStack else redoStack
            retainedTextBytes -= oldest.removeFirst().textBytes
        }
    }

    private fun takeLast(stack: ArrayDeque<HistorySnapshot>): TextFieldValue? {
        val snapshot = stack.removeLastOrNull() ?: return null
        retainedTextBytes -= snapshot.textBytes
        return snapshot.value
    }

    private fun clearRedo() {
        retainedTextBytes -= redoStack.sumOf { it.textBytes }
        redoStack.clear()
    }

    /** Snapshots drop the IME composition range; restoring one must not resurrect it. */
    private fun plain(source: TextFieldValue): TextFieldValue = TextFieldValue(source.text, source.selection)

    private fun sync() {
        canUndo = undoStack.isNotEmpty()
        canRedo = redoStack.isNotEmpty()
    }

    private fun editKind(old: TextFieldValue, new: TextFieldValue): Int {
        val delta = new.text.length - old.text.length
        val caret = new.selection.start
        return when {
            delta == 1 && new.selection.collapsed && caret in 1..new.text.length && !new.text[caret - 1].isWhitespace() -> EditTyping
            delta == -1 -> EditDeleting
            else -> EditOther
        }
    }

    private companion object {
        const val HistoryLimit = 200
        const val HistoryTextByteLimit = 8L * 1024L * 1024L
        const val EditOther = 0
        const val EditTyping = 1
        const val EditDeleting = 2
    }
}

/* ------------------------------------------------------------------ */
/*  Colouring                                                           */
/* ------------------------------------------------------------------ */

@Immutable
internal data class ToolsYamlPalette(val key: Color, val bool: Color, val number: Color, val comment: Color, val error: Color)

/** Keys accent, booleans warn, numbers good, comments tertiary: the palette of the prototype. */
@Composable
internal fun toolsYamlPalette(): ToolsYamlPalette {
    val c = LocalHomeColors.current
    return remember(c) { ToolsYamlPalette(key = c.accent, bool = c.warn, number = c.good, comment = c.t3, error = c.bad) }
}

/** [text] with YAML colouring; the 1-based [errorLine] is painted in the error colour first. */
internal fun toolsYamlAnnotated(text: String, palette: ToolsYamlPalette, errorLine: Int? = null): AnnotatedString {
    val builder = AnnotatedString.Builder(text)
    if (errorLine != null && errorLine in 1..ToolsYaml.lineCount(text)) {
        val start = ToolsYaml.lineStart(text, errorLine)
        val end = text.indexOf('\n', start).let { if (it < 0) text.length else it }
        if (end > start) builder.addStyle(SpanStyle(color = palette.error), start, end)
    }
    for (token in ToolsYaml.tokens(text)) {
        val color = when (token.kind) {
            ToolsYamlTokenKind.Key -> palette.key
            ToolsYamlTokenKind.Bool -> palette.bool
            ToolsYamlTokenKind.Number -> palette.number
            ToolsYamlTokenKind.Comment -> palette.comment
        }
        builder.addStyle(SpanStyle(color = color), token.start, token.end.coerceAtMost(text.length))
    }
    return builder.toAnnotatedString()
}

private class ToolsYamlHighlight(private val palette: ToolsYamlPalette, private val errorLine: Int?) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText =
        TransformedText(toolsYamlAnnotated(text.text, palette, errorLine), OffsetMapping.Identity)
}

/* ------------------------------------------------------------------ */
/*  Widget                                                              */
/* ------------------------------------------------------------------ */

internal object ToolsEditorType {
    /** Every line is exactly 18 sp tall (no first/last line trimming), so the gutter and line tints line up. */
    val code: TextStyle = HomeType.mono.copy(
        fontSize = 13.5.sp,
        lineHeight = 18.sp,
        lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
    )
}

private val GutterWidth = 44.dp
private val EditorPadding = 12.dp

/**
 * Code area of the config editor: line-number gutter, no soft wrap (long lines scroll sideways),
 * YAML colouring, accent caret. The error line gets a red tint, the line of the last jump an
 * accent tint.
 *
 * It is a plain `BasicTextField`, which is comfortable up to a few thousand lines. For very
 * large configs the host can pass a different widget to `ToolsEditorScreen(editor = …)`.
 */
@Composable
internal fun ToolsYamlEditor(
    state: ToolsYamlEditorState,
    modifier: Modifier = Modifier,
    errorLine: Int? = null,
    enabled: Boolean = true,
) {
    val c = LocalHomeColors.current
    val density = LocalDensity.current
    val vertical = rememberScrollState()
    val horizontal = rememberScrollState()
    val palette = toolsYamlPalette()
    val highlight = remember(palette, errorLine) { ToolsYamlHighlight(palette, errorLine) }
    val textStyle = remember(c.t1) { ToolsEditorType.code.copy(color = c.t1) }
    val lineHeightPx = with(density) { ToolsEditorType.code.lineHeight.toPx() }
    val lines = ToolsYaml.lineCount(state.text)
    val gutter = remember(lines) { ToolsYaml.gutter(lines) }
    val focusLine = state.focusLine

    LaunchedEffect(state.jumpTick) {
        val line = state.focusLine ?: return@LaunchedEffect
        vertical.animateScrollTo(((line - 4).coerceAtLeast(0) * lineHeightPx).toInt())
    }

    BoxWithConstraints(modifier) {
        val minTextWidth = (maxWidth - GutterWidth).coerceAtLeast(0.dp)
        val minTextHeight = (maxHeight - EditorPadding * 2).coerceAtLeast(0.dp)
        Row(
            Modifier
                .fillMaxSize()
                .verticalScroll(vertical)
                .drawBehind {
                    val top = EditorPadding.toPx()
                    fun tint(line: Int?, color: Color) {
                        if (line == null || line < 1 || line > lines) return
                        drawRect(color, Offset(0f, top + (line - 1) * lineHeightPx), Size(size.width, lineHeightPx))
                    }
                    tint(focusLine, c.accentSoft)
                    tint(errorLine, c.badSoft)
                }
                .padding(vertical = EditorPadding),
        ) {
            Text(gutter, Modifier.width(GutterWidth).padding(end = 10.dp), color = c.t3, style = ToolsEditorType.code, textAlign = TextAlign.End)
            Box(Modifier.weight(1f).horizontalScroll(horizontal)) {
                BasicTextField(
                    value = state.value,
                    onValueChange = state::onValueChange,
                    modifier = Modifier.widthIn(min = minTextWidth).heightIn(min = minTextHeight).padding(end = 16.dp),
                    enabled = enabled,
                    textStyle = textStyle,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                        autoCorrectEnabled = false,
                        keyboardType = KeyboardType.Text,
                    ),
                    visualTransformation = highlight,
                    cursorBrush = SolidColor(c.accent),
                )
            }
        }
    }
}
