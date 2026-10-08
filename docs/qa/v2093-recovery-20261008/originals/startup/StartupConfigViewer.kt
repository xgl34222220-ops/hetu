package io.github.xgl34222220.hetu

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import io.github.xgl34222220.hetu.home.HomeButton
import io.github.xgl34222220.hetu.home.HomeButtonKind
import io.github.xgl34222220.hetu.home.HomeIcons
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Read-only display; callers retain the untouched original for copy and export. */
@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun StartupConfigViewer(
    text: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    annotated: ((String) -> AnnotatedString)? = null,
) {
    val chunks = remember(text) { startupConfigChunks(text) }
    key(text) {
        val state = rememberLazyListState()
        val headScroll = rememberScrollState()
        val tailScroll = rememberScrollState()
        var tailEndX by remember { mutableFloatStateOf(0f) }
        var tailTextWidth by remember { mutableIntStateOf(0) }
        val scope = rememberCoroutineScope()
        Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (chunks.size > 1) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                HomeButton("回到开头", { scope.launch { state.scrollToItem(0); headScroll.scrollTo(0) } },
                    kind = HomeButtonKind.Soft, icon = HomeIcons.ArrowUp, height = 44.dp)
                HomeButton("查看末尾", { scope.launch {
                    // The final paragraph can exceed the viewport height. An end
                    // anchor exposes its bottom; then reveal the end of a long line.
                    state.scrollToItem(chunks.size)
                    val viewportWidth = tailTextWidth - tailScroll.maxValue
                    val endOffset = (tailEndX - viewportWidth).roundToInt().coerceIn(0, tailScroll.maxValue)
                    tailScroll.scrollTo(endOffset)
                } },
                    kind = HomeButtonKind.Soft, icon = HomeIcons.ArrowDown, height = 44.dp)
            }
            // A bounded paragraph, including very long single lines, is measured only
            // when visible. Neither the full document nor every individual line is a Text.
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp), state = state) {
                itemsIndexed(chunks, key = { index, _ -> index }) { index, range ->
                    val part = remember(text, range) { text.substring(range.start, range.end).ifEmpty { " " } }
                    SelectionContainer {
                        val scroll = Modifier.horizontalScroll(when (index) {
                            chunks.lastIndex -> tailScroll
                            0 -> headScroll
                            else -> rememberScrollState()
                        })
                        val onLayout: (TextLayoutResult) -> Unit = { layout ->
                            if (index == chunks.lastIndex) {
                                val end = part.indexOfLast { it != '\n' && it != '\r' }.coerceAtLeast(0)
                                tailEndX = layout.getBoundingBox(end).right
                                tailTextWidth = layout.size.width
                            }
                        }
                        if (annotated == null) Text(part, scroll, color = color, style = style, softWrap = false, onTextLayout = onLayout)
                        else Text(remember(part, annotated) { annotated(part) }, scroll, color = color, style = style, softWrap = false, onTextLayout = onLayout)
                    }
                }
                if (chunks.size > 1) item(key = "end") { Spacer(Modifier.height(1.dp)) }
            }
        }
    }
}

private data class StartupConfigChunk(val start: Int, val end: Int)

/** Keep allocations proportional to document blocks, including newline-only input. */
private fun startupConfigChunks(text: String): List<StartupConfigChunk> {
    if (text.isEmpty()) return emptyList()
    val result = ArrayList<StartupConfigChunk>()
    var start = 0
    while (start < text.length) {
        val limit = minOf(start + 1024, text.length)
        var end = start
        var lines = 0
        var lastBreak = -1
        while (end < limit) {
            if (text[end++] == '\n') {
                lastBreak = end
                if (++lines == 24) break
            }
        }
        if (end < text.length && lines < 24 && lastBreak > start) end = lastBreak
        // Consume a delimiter touching a char-bound split so that it does not
        // become an extra blank paragraph. Displayed content remains bounded.
        if (end < text.length && text[end - 1] != '\n') {
            if (text[end - 1] == '\r' && text[end] == '\n') end++
            else if (text[end] == '\r' && end + 1 < text.length && text[end + 1] == '\n') end += 2
            else if (text[end] == '\n') end++
        }
        if (end < text.length && text[end - 1].isHighSurrogate() && text[end].isLowSurrogate()) end--
        var displayEnd = end
        if ((end < text.length || end - start > 1024) && displayEnd > start && text[displayEnd - 1] == '\n') {
            displayEnd--
            if (displayEnd > start && text[displayEnd - 1] == '\r') displayEnd--
        }
        result += StartupConfigChunk(start, displayEnd)
        start = end
    }
    return result
}
