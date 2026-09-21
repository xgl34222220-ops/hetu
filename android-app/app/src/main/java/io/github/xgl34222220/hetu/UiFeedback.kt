package io.github.xgl34222220.hetu

import io.github.xgl34222220.hetu.ui.CrystalSurface as Surface
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.xgl34222220.hetu.ui.*

internal object UiFeedback {
    fun summary(text: String, error: Boolean): String {
        val code = Regex("(?:返回|HTTP|status[ =:]*)\\s*(\\d{3})", RegexOption.IGNORE_CASE)
            .find(text)?.groupValues?.get(1)
        val prefix = text.substringBefore("：").substringBefore(":").substringBefore('{')
        val safe = prefix.replace(Regex("https?://\\S+"), "远端地址")
            .replace(Regex("[\\r\\n]+"), " ").trim()
        if (error) {
            val title = safe.takeIf { it.length in 1..32 && !it.contains("Mihomo 控制接口") }
                ?: "请求未完成"
            return title + if (code != null) "（$code）" else ""
        }
        return safe.take(72)
    }
}

/** Lives in normal header flow, never at an absolute offset above tabs. */
@Composable
internal fun HetuTaskFeedback(text: String, error: Boolean = false, busy: Boolean = false, modifier: Modifier = Modifier) {
    if (text.isBlank() && !busy) return
    val t = LocalHetuTokens.current
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var detailsOpen by remember { mutableStateOf(false) }
    val safeDetails = remember(text) {
        DiagnosticReport.redact(text, context.getSharedPreferences("hetu", 0).getString("proxyControllerSecret", ""))
    }
    val summary = remember(safeDetails, error) { UiFeedback.summary(safeDetails, error) }
    val accent = when { error -> t.danger; busy -> MaterialTheme.colorScheme.primary; else -> t.success }
    Surface(modifier.fillMaxWidth().testTag("task-feedback"), shape = RoundedCornerShape(14.dp),
        color = if (error) t.dangerContainer else t.controlBackground, shadowElevation = 0.dp) {
        Column(Modifier.animateContentSize(tween(if (LocalHetuMotionEnabled.current) 180 else 0))) {
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(start = 12.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (busy && !error) HetuBusyIndicator(Modifier.size(16.dp), accent)
                else Icon(if (error) Icons.Rounded.ErrorOutline else Icons.Rounded.CheckCircle, null, Modifier.size(18.dp), tint = accent)
                Text(summary.ifBlank { "正在处理…" }, Modifier.weight(1f).padding(vertical = 8.dp),
                    color = t.textPrimary, fontSize = 12.sp, lineHeight = 18.sp)
                if (error) TextButton(onClick = { detailsOpen = !detailsOpen }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(if (detailsOpen) "收起" else "详情", fontSize = 12.sp, color = accent)
                }
            }
            if (detailsOpen && error) {
                Text(safeDetails.take(24_000), Modifier.fillMaxWidth().heightIn(max = 220.dp)
                    .verticalScroll(rememberScrollState()).padding(horizontal = 12.dp).testTag("task-inline-details"),
                    color = t.textSecondary, fontSize = 12.sp, lineHeight = 18.sp)
                TextButton(onClick = { clipboard.setText(AnnotatedString(safeDetails)) }, modifier = Modifier.padding(horizontal = 4.dp)) {
                    Text("复制诊断")
                }
            }
        }
    }
}
