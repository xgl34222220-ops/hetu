package io.github.xgl34222220.hetu

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.xgl34222220.hetu.ui.*
import org.json.JSONObject
import org.json.JSONTokener

internal data class StructuredLogEntry(val time: String, val level: String, val message: String)

private val logFields = Regex("""(?:^|\s)(time|level|msg)=("(?:\\.|[^"\\])*"|[^\s"]+)""")

internal fun parseStructuredLog(line: String): StructuredLogEntry? {
    if (line.trimStart().startsWith("{")) {
        val json = runCatching { JSONObject(line) }.getOrNull()
        if (json != null && json.has("msg")) return StructuredLogEntry(
            json.optString("time"), json.optString("level"), json.optString("msg"))
    }
    val fields = logFields.findAll(line).associate { match ->
        val raw = match.groupValues[2]
        match.groupValues[1] to if (raw.startsWith('"')) {
            runCatching { JSONTokener(raw).nextValue().toString() }.getOrDefault(raw)
        } else raw
    }
    val message = fields["msg"] ?: return null
    return StructuredLogEntry(fields["time"].orEmpty(), fields["level"].orEmpty(), message)
}

@Composable
internal fun StructuredLogCard(line: String) {
    val t = LocalHetuTokens.current
    val parsed = remember(line) { parseStructuredLog(line) }
    var rawExpanded by remember(line) { mutableStateOf(false) }
    val level = parsed?.level.orEmpty().uppercase()
    val accent = when (level) { "ERROR", "FATAL" -> t.danger; "WARN", "WARNING" -> t.warning; else -> MaterialTheme.colorScheme.primary }
    Column(Modifier.fillMaxWidth().crystalMaterial(RoundedCornerShape(18.dp)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (parsed != null) {
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (level.isNotBlank()) Text(level, color = accent, fontSize = 12.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold)
                if (parsed.time.isNotBlank()) Text(parsed.time, color = t.textSecondary, fontSize = 12.sp, lineHeight = 18.sp)
            }
        }
        SelectionContainer {
            Text(parsed?.message ?: line, Modifier.fillMaxWidth().testTag("log-summary"),
                color = t.textPrimary, fontSize = 13.sp, lineHeight = 20.sp)
        }
        if (parsed != null) {
            TextButton(onClick = { rawExpanded = !rawExpanded }, contentPadding = PaddingValues(horizontal = 4.dp),
                modifier = Modifier.heightIn(min = 48.dp)) { Text(if (rawExpanded) "收起原始记录" else "查看原始记录") }
            if (rawExpanded) SelectionContainer {
                Text(line, Modifier.fillMaxWidth().testTag("log-original"), color = t.textSecondary,
                    fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 19.sp)
            }
        }
    }
}
