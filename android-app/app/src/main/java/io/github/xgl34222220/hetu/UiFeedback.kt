package io.github.xgl34222220.hetu

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import io.github.xgl34222220.hetu.home.HomeButton
import io.github.xgl34222220.hetu.home.HomeButtonKind
import io.github.xgl34222220.hetu.home.HomeDims
import io.github.xgl34222220.hetu.home.HomeIcons
import io.github.xgl34222220.hetu.home.HomeMotion
import io.github.xgl34222220.hetu.home.HomeSpinner
import io.github.xgl34222220.hetu.home.HomeTone
import io.github.xgl34222220.hetu.home.HomeType
import io.github.xgl34222220.hetu.home.LocalHomeColors
import io.github.xgl34222220.hetu.home.LocalHomeMotionEnabled
import io.github.xgl34222220.hetu.home.foreground
import io.github.xgl34222220.hetu.home.soft
import io.github.xgl34222220.hetu.ui.*

internal object UiFeedback {
    fun summary(text: String, error: Boolean): String {
        val normalized = if (text.contains("/data/adb/hetu/") || text.contains("hetu-root.sh[")) {
            if (error) "Root 运行状态暂时无法确认" else "运行状态正在同步"
        } else text
        val code = Regex("(?:返回|HTTP|status[ =:]*)\\s*(\\d{3})", RegexOption.IGNORE_CASE)
            .find(normalized)?.groupValues?.get(1)
        val prefix = normalized.substringBefore("：").substringBefore(":").substringBefore('{')
        val safe = prefix.replace(Regex("https?://\\S+"), "远端地址")
            .replace(Regex("""/data/adb/hetu/\S+"""), "Root 运行组件")
            .replace(Regex("[\\r\\n]+"), " ").trim()
        if (error) {
            val title = safe.takeIf { it.length in 1..32 && !it.contains("Mihomo 控制接口") }
                ?: "请求未完成"
            return title + if (code != null) "（$code）" else ""
        }
        return safe.take(72)
    }
}

/**
 * What a task is doing or how it ended, in the page's normal flow: a spinner while it runs, a
 * green line when it worked, a red one when it did not. A failure keeps its full text behind
 * 详情, already stripped of secrets, with a button to copy it.
 */
@Composable
internal fun HetuTaskFeedback(text: String, error: Boolean = false, busy: Boolean = false, modifier: Modifier = Modifier) {
    if (text.isBlank() && !busy) return
    val c = LocalHomeColors.current
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var detailsOpen by remember { mutableStateOf(false) }
    val safeDetails = remember(text) {
        DiagnosticReport.redact(text, context.getSharedPreferences("hetu", 0).getString("proxyControllerSecret", ""))
    }
    val summary = remember(safeDetails, error) { UiFeedback.summary(safeDetails, error) }
    val tone = when { error -> HomeTone.Bad; busy -> HomeTone.Accent; else -> HomeTone.Good }
    val fill by animateColorAsState(tone.soft(), HomeMotion.fade(LocalHomeMotionEnabled.current), label = "task-feedback-fill")
    Column(
        modifier.fillMaxWidth().testTag("task-feedback").clip(HomeDims.controlShape).background(fill)
            .animateContentSize(HomeMotion.glide(LocalHomeMotionEnabled.current)),
    ) {
        Row(Modifier.fillMaxWidth().heightIn(min = 50.dp).padding(start = 14.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (busy && !error) HomeSpinner(size = 18.dp, color = tone.foreground())
            else Icon(if (error) HomeIcons.CircleAlert else HomeIcons.CircleCheck, null, Modifier.size(20.dp), tint = tone.foreground())
            Text(summary.ifBlank { ht("正在处理…") }, Modifier.weight(1f).padding(vertical = 12.dp),
                color = c.t1, style = HomeType.note.copy(fontWeight = FontWeight.Medium))
            if (error) HomeButton(if (detailsOpen) "收起" else "详情", { detailsOpen = !detailsOpen }, kind = HomeButtonKind.Ghost, danger = true, height = 48.dp)
            else Spacer(Modifier.width(8.dp))
        }
        if (detailsOpen && error) {
            Text(safeDetails.take(24_000), Modifier.fillMaxWidth().heightIn(max = 220.dp)
                .verticalScroll(rememberScrollState()).padding(horizontal = 14.dp).testTag("task-inline-details"),
                color = c.t2, style = HomeType.mono.copy(fontSize = 12.sp, lineHeight = 18.sp))
            HomeButton("复制诊断", { clipboard.setText(AnnotatedString(safeDetails)) }, Modifier.padding(start = 2.dp, bottom = 4.dp),
                kind = HomeButtonKind.Ghost, icon = HomeIcons.Copy, height = 48.dp)
        }
    }
}
