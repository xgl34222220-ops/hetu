package io.github.xgl34222220.hetu

import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.xgl34222220.hetu.ui.*

/** Display projection only. The original node.name remains the routing identity. */
internal data class NodeLabel14(val title: String, val tags: List<String>)
internal fun nodeLabel14(raw: String): NodeLabel14 {
    val tags = linkedSetOf<String>()
    val token = Regex("(?i)(?<![A-Za-z0-9])(?:IPLC|IEPL|VLESS|VMESS|TROJAN|HYSTERIA2|HY2|TUIC|\\d+(?:\\.\\d+)?[xX倍])(?![A-Za-z0-9])")
    var title = token.replace(raw) { match ->
        val text = match.value
        tags += if (text.first().isDigit()) text.lowercase() else text.uppercase()
        " "
    }
    title = Regex("[\\[【](永久|无限)[\\]】]").replace(title) { tags += it.groupValues[1]; " " }
    title = title.replace(Regex("[\\[\\]【】]"), " ")
        .replace(Regex("(?:\\s*[-|·]\\s*)+"), " · ")
        .replace(Regex("\\s+"), " ").trim(' ', '·', '-')
    // Do not invent a geographic name, index or unique ID when nothing remains.
    return if (title.isBlank()) NodeLabel14(raw, emptyList()) else NodeLabel14(title, tags.toList())
}

internal enum class DelayTone14 { Unknown, Good, Medium, Slow, Failed }
internal fun delayTone14(value: Long?): DelayTone14 = when {
    value == -1L || value == -2L -> DelayTone14.Failed
    value == null || value <= 0L -> DelayTone14.Unknown
    value < 100L -> DelayTone14.Good
    value < 300L -> DelayTone14.Medium
    else -> DelayTone14.Slow
}
internal fun delayText14(value: Long?): String = when {
    value == -1L -> "超时"
    value == -2L -> "失败"
    value != null && value > 0L -> "$value ms"
    else -> "未测"
}

/** No per-node infinite animation or spinner: stale values stay visible while probing. */
@Composable
internal fun QuietDelay14(value: Long?, loading: Boolean) {
    val t = LocalHetuTokens.current
    val motion = homeMotionAvailable(LocalHetuMotionEnabled.current)
    val accent = when (delayTone14(value)) {
        DelayTone14.Good -> Color(0xFF2A6984)
        DelayTone14.Medium -> Color(0xFF97651F)
        DelayTone14.Slow -> Color(0xFFAD682D)
        DelayTone14.Failed -> Color(0xFFA65D69)
        DelayTone14.Unknown -> t.textSecondary
    }
    val opacity by animateFloatAsState(if (loading) .48f else 1f,
        if (motion) tween(if (loading) 80 else 200) else snap(), label = "quiet-delay")
    val reveal = remember { Animatable(1f) }
    LaunchedEffect(value, motion) {
        if (motion) { reveal.snapTo(.45f); reveal.animateTo(1f, tween(200)) }
        else reveal.snapTo(1f)
    }
    Box(Modifier.clip(CircleShape).background(accent.copy(alpha = .09f))
        .semantics { stateDescription = if (loading) "测速中，保留上次结果" else delayText14(value) }
        .padding(horizontal = 8.dp, vertical = 3.dp)) {
        Text(delayText14(value), Modifier.graphicsLayer { alpha = opacity * reveal.value },
            color = accent, fontSize = 11.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold,
            style = LocalTextStyle.current.copy(fontFeatureSettings = "tnum"), maxLines = 1)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun NodeTags14(tags: List<String>, modifier: Modifier = Modifier) {
    val t = LocalHetuTokens.current
    if (tags.isNotEmpty()) FlowRow(modifier.padding(top = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        tags.forEach { text -> Text(text, Modifier.clip(CircleShape).background(t.textMuted.copy(alpha = .07f))
            .padding(horizontal = 6.dp, vertical = 2.dp), color = t.textSecondary, fontSize = 10.sp,
            lineHeight = 13.sp, fontWeight = FontWeight.Medium) }
    }
}

@Composable
internal fun CompactNodeSearch14(query: String, onQuery: (String) -> Unit) {
    val t = LocalHetuTokens.current
    Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp).fillMaxWidth().heightIn(min = 48.dp)
        .clip(CircleShape).background(t.cardBackground).padding(start = 13.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Rounded.Search, null, Modifier.size(17.dp), tint = t.textSecondary)
        Spacer(Modifier.width(8.dp))
        BasicTextField(query, onQuery, Modifier.weight(1f).padding(vertical = 12.dp)
            .testTag("panel13-node-search"), singleLine = true,
            textStyle = LocalTextStyle.current.copy(color = t.textPrimary, fontSize = 13.sp, lineHeight = 20.sp),
            decorationBox = { inner -> Box { if (query.isEmpty()) Text("搜索本组节点", color = t.textSecondary, fontSize = 13.sp); inner() } })
        if (query.isNotEmpty()) IconButton({ onQuery("") }, Modifier.size(40.dp)) {
            Icon(Icons.Rounded.Close, "清除本组搜索", Modifier.size(16.dp), tint = t.textSecondary)
        }
    }
}

/** Occupies its own footer above the measured dock. Never steals taps from the grid. */
@Composable
internal fun PanelShortcut14(enabled: Boolean, onClick: () -> Unit) {
    if (!enabled) return
    Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 16.dp)
        .testTag("panel14-shortcut-slot"), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.height(48.dp).diffuseCardShadow(HomeContinuousShape(24.dp)).clip(CircleShape)
            .background(Color(0xFF12806F).copy(alpha = .94f))
            .nativePress(label = "打开主策略节点选择", onClick = onClick).testTag("panel11-fab")
            .padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(Icons.Rounded.Explore, null, Modifier.size(18.dp), tint = Color.White)
            Text("节点选择", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}
