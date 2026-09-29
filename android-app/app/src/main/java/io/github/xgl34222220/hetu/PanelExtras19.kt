package io.github.xgl34222220.hetu

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.FilterList
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.xgl34222220.hetu.ui.HetuHaptic
import io.github.xgl34222220.hetu.ui.LocalHetuTokens
import io.github.xgl34222220.hetu.ui.hetuPressHighlight
import io.github.xgl34222220.hetu.ui.hetuPressScale
import io.github.xgl34222220.hetu.ui.rememberHetuHaptics
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

// ---------------------------------------------------------------------------------
// Logs tab
// ---------------------------------------------------------------------------------

internal enum class RefLogLevel(val label: String, val rank: Int) {
    Debug("DEBUG", 0), Info("INFO", 1), Warn("WARN", 2), Error("ERROR", 3)
}

internal data class RefLogEntry(val index: Int, val level: RefLogLevel, val time: String, val message: String)

private val LEVEL_BRACKET = Regex("""\[(debug|info|warn|warning|error|fatal)\]""", RegexOption.IGNORE_CASE)
private val LEVEL_KEY = Regex("""level=(debug|info|warn|warning|error|fatal)""", RegexOption.IGNORE_CASE)
private val TIME_PREFIX = Regex("""^(\d{4}-\d{2}-\d{2}[ T]\d{2}:\d{2}:\d{2})""")
private val TIME_KEY = Regex("""time="([^"]+)"""")
private val MSG_KEY = Regex("""msg="(.*)"$""")

private fun levelOf(raw: String): RefLogLevel? = when (raw.lowercase()) {
    "debug" -> RefLogLevel.Debug
    "info" -> RefLogLevel.Info
    "warn", "warning" -> RefLogLevel.Warn
    "error", "fatal" -> RefLogLevel.Error
    else -> null
}

/**
 * Parses both Hetu's "yyyy-MM-dd HH:mm:ss [Info] message" lines and Mihomo's
 * logfmt `time="…" level=info msg="…"`. Continuation lines inherit the previous level
 * and are folded into the previous entry, so a multi-line stack stays one row.
 * Only the newest [limit] entries are kept.
 */
internal fun refParseLogs19(text: String, limit: Int = 1500): List<RefLogEntry> {
    val out = ArrayList<RefLogEntry>()
    var last = RefLogLevel.Info
    for (line in text.lineSequence()) {
        val trimmed = line.trimEnd()
        if (trimmed.isBlank()) continue
        val bracket = LEVEL_BRACKET.find(trimmed)
        val key = LEVEL_KEY.find(trimmed)
        val level = (bracket ?: key)?.groupValues?.getOrNull(1)?.let(::levelOf)
        if (level == null && out.isNotEmpty()) {
            val prev = out.removeAt(out.lastIndex)
            out.add(prev.copy(message = prev.message + "\n" + trimmed.trim()))
            continue
        }
        val lvl = level ?: last
        last = lvl
        val time = TIME_PREFIX.find(trimmed)?.groupValues?.get(1)
            ?: TIME_KEY.find(trimmed)?.groupValues?.get(1)?.replace('T', ' ')?.take(19).orEmpty()
        val message = when {
            key != null -> MSG_KEY.find(trimmed)?.groupValues?.get(1) ?: trimmed
            bracket != null -> trimmed.substring(bracket.range.last + 1).trim()
            else -> trimmed
        }
        out.add(RefLogEntry(out.size, lvl, time, message))
    }
    val kept = if (out.size > limit) out.subList(out.size - limit, out.size) else out
    return kept.mapIndexed { i, e -> e.copy(index = i) }
}

@Composable
internal fun refLogLevelColor19(level: RefLogLevel): Color {
    val dark = MaterialTheme.colorScheme.background.luminance() < .5f
    return when (level) {
        RefLogLevel.Debug -> if (dark) Color(0xFF98A1AA) else Color(0xFF5D6670)
        RefLogLevel.Info -> if (dark) Color(0xFF8AB4FF) else Color(0xFF12806F)
        RefLogLevel.Warn -> if (dark) Color(0xFFFBBF24) else Color(0xFFD97706)
        RefLogLevel.Error -> if (dark) Color(0xFFFB7185) else Color(0xFFE11D48)
    }
}

/** Control row: level popover (minimum level), newest-first toggle, entry count. */
@Composable
internal fun RefLogControls19(
    minLevel: RefLogLevel,
    newestFirst: Boolean,
    shown: Int,
    total: Int,
    loading: Boolean,
    onLevel: (RefLogLevel) -> Unit,
    onToggleOrder: () -> Unit,
) {
    val t = LocalHetuTokens.current
    val haptics = rememberHetuHaptics()
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(horizontal = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Box {
            val source = remember { MutableInteractionSource() }
            Row(
                Modifier.heightIn(min = 40.dp).hetuPressScale(source, pressedScale = .95f)
                    .clip(CircleShape).background(t.cardBackground)
                    .clickable(interactionSource = source, indication = null, role = Role.Button) {
                        haptics.perform(HetuHaptic.Tap); menu = true
                    }
                    .padding(horizontal = 14.dp)
                    .testTag("log-level-button"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(Icons.Rounded.FilterList, null, Modifier.size(17.dp), tint = refLogLevelColor19(minLevel))
                Text("等级 · ${minLevel.label}", color = t.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
            MotionPopover12(expanded = menu, onDismissRequest = { menu = false }) {
                RefLogLevel.values().forEach { level ->
                    val selected = level == minLevel
                    Row(
                        Modifier.widthIn(min = 180.dp).heightIn(min = 48.dp)
                            .clickable {
                                haptics.perform(HetuHaptic.Tick)
                                onLevel(level); menu = false
                            }
                            .padding(horizontal = 18.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(8.dp).background(refLogLevelColor19(level), CircleShape))
                        Spacer(Modifier.width(12.dp))
                        Text(level.label, Modifier.weight(1f), color = t.textPrimary, fontSize = 15.sp,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium)
                        if (selected) Icon(Icons.Rounded.Check, "已选择", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                    }
                }
                HorizontalDivider(Modifier.padding(vertical = 4.dp), color = t.outline)
                Row(
                    Modifier.widthIn(min = 180.dp).heightIn(min = 48.dp)
                        .clickable { haptics.perform(HetuHaptic.Tick); onToggleOrder(); menu = false }
                        .padding(horizontal = 18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.SwapVert, null, Modifier.size(18.dp), tint = t.textSecondary)
                    Spacer(Modifier.width(10.dp))
                    Text("倒序", Modifier.weight(1f), color = t.textPrimary, fontSize = 15.sp)
                    if (newestFirst) Icon(Icons.Rounded.Check, "已开启", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                }
            }
        }
        Spacer(Modifier.weight(1f))
        Text(
            if (loading && total == 0) "正在读取…" else "$shown / $total 条",
            color = t.textSecondary, fontSize = 12.sp,
        )
    }
}

@Composable
internal fun RefLogRow19(entry: RefLogEntry) {
    val t = LocalHetuTokens.current
    val color = refLogLevelColor19(entry.level)
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(t.cardBackground)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Box(Modifier.padding(top = 5.dp).size(width = 3.dp, height = 14.dp).background(color, RoundedCornerShape(2.dp)))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(entry.level.label, color = color, fontSize = 10.5.sp, fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace)
                if (entry.time.isNotBlank()) {
                    Spacer(Modifier.width(8.dp))
                    Text(entry.time.takeLast(8), color = t.textSecondary, fontSize = 10.5.sp, fontFamily = FontFamily.Monospace)
                }
            }
            Text(entry.message, color = t.textPrimary, fontSize = 12.5.sp, lineHeight = 18.sp,
                fontFamily = FontFamily.Monospace, maxLines = 8, overflow = TextOverflow.Ellipsis)
        }
    }
}

// ---------------------------------------------------------------------------------
// API configuration sheet (panel gear)
// ---------------------------------------------------------------------------------

/**
 * Every control is backed by real behaviour:
 * - 模式: GET/PATCH /configs mode on the running core (rule / global / direct);
 * - 自定义延迟 URL: prefs proxyCustomDelayUrlEnabled / proxyCustomDelayUrl, already read by
 *   MihomoControllerClient and ProxyComposeController for every latency probe;
 * - Clash API 历史采集: prefs proxyApiHistoryEnabled, read by ProxyApiHistoryStore;
 * - 地址: loopback controller; the secret is never shown here.
 */
@Composable
internal fun RefApiConfigSheet19(
    repo: ProxyDashboardRepository,
    controllerPort: Int,
    running: Boolean,
    onMessage: (String, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { context.getSharedPreferences("hetu", 0) }
    val t = LocalHetuTokens.current
    val scope = rememberCoroutineScope()
    val haptics = rememberHetuHaptics()
    val focus = LocalFocusManager.current
    var mode by remember { mutableStateOf<String?>(null) }
    var modeBusy by remember { mutableStateOf(false) }
    var delayEnabled by remember { mutableStateOf(prefs.getBoolean("proxyCustomDelayUrlEnabled", false)) }
    var delayUrl by remember { mutableStateOf(prefs.getString("proxyCustomDelayUrl", "").orEmpty()) }
    var history by remember { mutableStateOf(prefs.getBoolean("proxyApiHistoryEnabled", false)) }
    LaunchedEffect(running) { mode = if (running) repo.trafficMode() else null }
    DisposableEffect(Unit) {
        onDispose {
            // Persist the URL on close; invalid values are not saved.
            val trimmed = delayUrl.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
                prefs.edit().putString("proxyCustomDelayUrl", trimmed).apply()
            }
        }
    }
    NativeDetailsSheet("API 配置", onDismiss) {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(t.controlBackground.copy(alpha = .6f))) {
            // Mode
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text("模式", Modifier.weight(1f), color = t.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                if (!running) Text("代理未运行", color = t.textSecondary, fontSize = 13.sp)
                else Row(Modifier.clip(CircleShape).background(t.cardBackground).padding(3.dp)) {
                    listOf("rule" to "规则", "global" to "全局", "direct" to "直连").forEach { (id, label) ->
                        val active = mode == id
                        val fill by animateColorAsState(if (active) MaterialTheme.colorScheme.primary else Color.Transparent,
                            label = "api-mode-fill")
                        Box(
                            Modifier.clip(CircleShape).background(fill)
                                .clickable(enabled = !modeBusy && !active, role = Role.RadioButton) {
                                    haptics.perform(HetuHaptic.Tick)
                                    val previous = mode
                                    mode = id; modeBusy = true
                                    scope.launch {
                                        try {
                                            repo.setTrafficMode(id)
                                            onMessage("流量模式：$label", false)
                                        } catch (cancel: CancellationException) {
                                            throw cancel
                                        } catch (error: Exception) {
                                            mode = previous
                                            haptics.perform(HetuHaptic.Reject)
                                            onMessage(error.message ?: "模式切换失败", true)
                                        } finally { modeBusy = false }
                                    }
                                }
                                .padding(horizontal = 12.dp, vertical = 7.dp),
                        ) {
                            Text(label, color = if (active) Color.White else t.textSecondary, fontSize = 13.sp,
                                fontWeight = if (active) FontWeight.Bold else FontWeight.Medium)
                        }
                    }
                }
            }
            ApiSwitchRow19("自定义延迟 URL", delayEnabled) { value ->
                delayEnabled = value
                prefs.edit().putBoolean("proxyCustomDelayUrlEnabled", value).apply()
            }
            androidx.compose.animation.AnimatedVisibility(delayEnabled) {
                Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 12.dp)
                    .clip(RoundedCornerShape(14.dp)).background(t.cardBackground).padding(horizontal = 14.dp, vertical = 10.dp)) {
                    Text("延迟 URL", color = t.textSecondary, fontSize = 11.sp)
                    BasicTextField(
                        value = delayUrl,
                        onValueChange = { delayUrl = it },
                        singleLine = true,
                        textStyle = TextStyle(color = t.textPrimary, fontSize = 15.sp),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp).testTag("api-delay-url"),
                        decorationBox = { inner ->
                            if (delayUrl.isEmpty()) Text("https://cp.cloudflare.com/generate_204", color = t.textSecondary.copy(alpha = .6f), fontSize = 15.sp)
                            inner()
                        },
                    )
                }
            }
            ApiSwitchRow19("Clash API 历史采集", history) { value ->
                history = value
                prefs.edit().putBoolean("proxyApiHistoryEnabled", value).apply()
            }
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("地址", color = t.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Text("127.0.0.1:$controllerPort", color = t.textSecondary, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                }
                Text("仅本机", color = t.textSecondary, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun ApiSwitchRow19(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val t = LocalHetuTokens.current
    val haptics = rememberHetuHaptics()
    val source = remember { MutableInteractionSource() }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp)
            .hetuPressHighlight(source, t.textPrimary.copy(alpha = .05f))
            .clickable(interactionSource = source, indication = null, role = Role.Switch) {
                haptics.perform(if (!checked) HetuHaptic.ToggleOn else HetuHaptic.ToggleOff)
                onChange(!checked)
            }
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, Modifier.weight(1f), color = t.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Switch(checked = checked, onCheckedChange = null)
    }
}

// ---------------------------------------------------------------------------------
// Core details (tap the home status card)
// ---------------------------------------------------------------------------------

internal data class CoreDetails19(
    val pid: Int,
    val core: String,
    val version: String,
    val uptime: String,
    val mode: String,
    val config: String,
    val cpu: String,
    val memory: String,
    val connections: String,
    val controller: String,
)

@Composable
internal fun CoreDetailsSheet19(details: CoreDetails19, onDismiss: () -> Unit) {
    val t = LocalHetuTokens.current
    NativeDetailsSheet("核心运行详情", onDismiss) {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(t.controlBackground.copy(alpha = .6f))
            .padding(horizontal = 16.dp, vertical = 6.dp).testTag("core-details")) {
            listOf(
                "PID" to (if (details.pid > 0) details.pid.toString() else "—"),
                "核心" to details.core.ifBlank { "—" },
                "版本" to details.version.ifBlank { "读取中…" },
                "运行" to details.uptime,
                "模式" to details.mode.ifBlank { "—" },
                "配置" to details.config.ifBlank { "—" },
            ).forEach { (k, v) -> CoreKv19(k, v) }
            HorizontalDivider(Modifier.padding(vertical = 6.dp), color = t.outline)
            listOf(
                "CPU 占用" to details.cpu,
                "内存" to details.memory,
                "活动连接" to details.connections,
                "控制器" to details.controller,
            ).forEach { (k, v) -> CoreKv19(k, v) }
        }
    }
}

@Composable
private fun CoreKv19(label: String, value: String) {
    val t = LocalHetuTokens.current
    Row(Modifier.fillMaxWidth().heightIn(min = 40.dp).semantics { contentDescription = "$label $value" },
        verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = t.textSecondary, fontSize = 14.sp, modifier = Modifier.width(84.dp))
        Text(value, Modifier.weight(1f), color = t.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
            maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = androidx.compose.ui.text.style.TextAlign.End)
    }
}
