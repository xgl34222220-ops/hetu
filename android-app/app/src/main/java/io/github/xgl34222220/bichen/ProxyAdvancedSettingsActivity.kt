package io.github.xgl34222220.bichen

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.xgl34222220.bichen.ui.BichenTheme
import io.github.xgl34222220.bichen.ui.LocalBichenTokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.TreeSet

class ProxyAdvancedSettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val focus = intent.getStringExtra("focus").orEmpty()
        setContent { BichenTheme { ProxyAdvancedSettingsPage(focus = focus, onBack = { finish() }) } }
    }
}

private data class AdvancedChoice(val label: String, val value: String)
private data class SetEditorState(val key: String, val title: String, val hint: String, val value: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProxyAdvancedSettingsPage(focus: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("bichen", 0) }
    val root = remember { RootProxyManager(context) }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    var revision by remember { mutableIntStateOf(0) }
    var choiceTitle by remember { mutableStateOf<String?>(null) }
    var choiceKey by remember { mutableStateOf<String?>(null) }
    var choices by remember { mutableStateOf<List<AdvancedChoice>>(emptyList()) }
    var editor by remember { mutableStateOf<SetEditorState?>(null) }
    var infoTitle by remember { mutableStateOf<String?>(null) }
    var infoText by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val profile = remember(revision) { ProxyRuntimeProfile.load(prefs) }
    val t = LocalBichenTokens.current
    val dark = MaterialTheme.colorScheme.background.luminance() < .5f
    val pageBg = if (dark) t.pageBackground else Color(0xFFF1F5F9)

    fun refresh() { revision++ }
    fun putBool(key: String, value: Boolean) { prefs.edit().putBoolean(key, value).apply(); refresh() }
    fun putString(key: String, value: String) { prefs.edit().putString(key, value).apply(); refresh() }
    fun setSummary(key: String): String {
        val values = prefs.getStringSet(key, emptySet()).orEmpty()
        return if (values.isEmpty()) "未设置" else "${values.size} 条"
    }
    fun editSet(key: String, title: String, hint: String) {
        val values = TreeSet(prefs.getStringSet(key, emptySet()).orEmpty())
        editor = SetEditorState(key, title, hint, values.joinToString("\n"))
    }
    fun showChoices(title: String, key: String, values: List<AdvancedChoice>) {
        choiceTitle = title; choiceKey = key; choices = values
    }
    fun runRoot(title: String, block: suspend () -> String) {
        if (busy) return
        scope.launch {
            busy = true
            val text = runCatching { withContext(Dispatchers.IO) { block() } }
                .getOrElse { it.message ?: it.javaClass.simpleName }
            busy = false
            infoTitle = title
            infoText = text.ifBlank { "完成" }
        }
    }

    LaunchedEffect(focus) {
        val index = when (focus) {
            "adblock" -> 4
            "sharing" -> 10
            "cnip" -> 12
            "bypass" -> 14
            else -> 0
        }
        if (index > 0) listState.animateScrollToItem(index)
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().background(pageBg),
        contentPadding = PaddingValues(
            start = 16.dp,
            top = 8.dp,
            end = 16.dp,
            bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 28.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().padding(top = 8.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack, modifier = Modifier.size(42.dp)) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回", tint = t.textPrimary)
                }
                Spacer(Modifier.width(4.dp))
                Text(
                    "高级代理配置",
                    color = t.textPrimary,
                    fontSize = 22.sp,
                    lineHeight = 28.sp,
                    fontWeight = FontWeight.ExtraBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        item { AdvancedSectionLabel("流量接管") }
        item {
            AdvancedGroup {
                Text(
                    "Root 数据面 · 修改后重启代理生效",
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                    color = t.textSecondary,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    fontWeight = FontWeight.Medium,
                )
                AdvancedDivider()
                AdvancedValueRow(Icons.Rounded.Memory, Color(0xFF334155), "运行核心", profile.core.label) {
                    showChoices("运行核心", "proxyBaseCore", listOf(
                        AdvancedChoice("Mihomo", "mihomo"),
                        AdvancedChoice("Mihomo Smart", "mihomo-smart"),
                    ))
                }
                AdvancedDivider()
                AdvancedValueRow(Icons.Rounded.Apps, Color(0xFFF97316), "应用范围", when (profile.appScope) {
                    ProxyRuntimeProfile.AppScope.WHITELIST -> "仅所选应用代理"
                    ProxyRuntimeProfile.AppScope.BLACKLIST -> "所选应用直连"
                    else -> "核心配置"
                }) {
                    showChoices("应用范围", "proxyAppScope", listOf(
                        AdvancedChoice("核心配置（不做 UID 过滤）", "core"),
                        AdvancedChoice("所选应用直连，其余代理", "blacklist"),
                        AdvancedChoice("仅所选应用代理，其余直连", "whitelist"),
                    ))
                }
                AdvancedDivider()
                AdvancedValueRow(Icons.Rounded.Checklist, Color(0xFF8B5CF6), "应用名单", "选择需要直连/代理的应用") {
                    context.startActivity(Intent(context, ProxyAppSelectionActivity::class.java))
                }
                AdvancedDivider()
                AdvancedSwitchRow(Icons.Rounded.SwapHoriz, Color(0xFF2563EB), "TCP 接管", "透明代理 TCP 流量", prefs.getBoolean("proxyTcp", true)) { putBool("proxyTcp", it) }
                AdvancedDivider()
                AdvancedSwitchRow(Icons.Rounded.Bolt, Color(0xFF0EA5E9), "UDP 接管", "游戏、VoIP 与 QUIC 等 UDP", prefs.getBoolean("proxyUdp", true)) { putBool("proxyUdp", it) }
            }
        }

        item { AdvancedSectionLabel("广告过滤") }
        item {
            AdvancedGroup {
                AdvancedSwitchRow(Icons.Rounded.Shield, Color(0xFF2563EB), "随代理串联去广告", "广告规则先 REJECT，剩余流量再进入代理分流", profile.adblockChain) { putBool("proxyAdblockChain", it) }
                AdvancedDivider()
                AdvancedActionRow(Icons.Rounded.FilterAlt, Color(0xFF8B5CF6), "广告规则与命中", "规则源 · 有效规则 · Mihomo REJECT 命中") {
                    context.startActivity(Intent(context, ProxyAdblockChainActivity::class.java))
                }
            }
        }

        item { AdvancedSectionLabel("DNS 与协议") }
        item {
            AdvancedGroup {
                AdvancedValueRow(Icons.Rounded.Dns, Color(0xFF14B8A6), "DNS 劫持", when (profile.dnsHijack) {
                    ProxyRuntimeProfile.DnsHijack.OFF -> "关闭"
                    ProxyRuntimeProfile.DnsHijack.REDIRECT -> "Redirect · 1053"
                    else -> "TPROXY"
                }) {
                    showChoices("DNS 劫持", "proxyDnsHijack", listOf(
                        AdvancedChoice("TPROXY（跟随透明代理端口）", "tproxy"),
                        AdvancedChoice("Redirect 到本地 1053", "redirect"),
                        AdvancedChoice("关闭 DNS 劫持", "off"),
                    ))
                }
                AdvancedDivider()
                AdvancedSwitchRow(Icons.Rounded.Speed, Color(0xFFF59E0B), "拦截 QUIC", "阻断 UDP 443，促使客户端回落 TCP", prefs.getBoolean("proxyQuicBlocked", false)) { putBool("proxyQuicBlocked", it) }
                AdvancedDivider()
                AdvancedValueRow(Icons.Rounded.Public, Color(0xFF10B981), "IPv6", when (profile.ipv6) {
                    ProxyRuntimeProfile.Ipv6.BYPASS -> "IPv6 不进核心"
                    ProxyRuntimeProfile.Ipv6.STRICT -> "严格 IPv4 防泄漏"
                    ProxyRuntimeProfile.Ipv6.DISABLE -> "禁用系统 IPv6"
                    else -> "启用 IPv6"
                }) {
                    showChoices("IPv6", "proxyBaseIpv6", listOf(
                        AdvancedChoice("启用 IPv6", "enable"),
                        AdvancedChoice("IPv6 不进核心", "bypass"),
                        AdvancedChoice("严格 IPv4 防泄漏", "strict"),
                        AdvancedChoice("禁用系统 IPv6", "disable"),
                    ))
                }
            }
        }

        item { AdvancedSectionLabel("安全与恢复") }
        item {
            AdvancedGroup {
                AdvancedSwitchRow(Icons.Rounded.GppGood, Color(0xFFEF4444), "Kill Switch", "核心异常时阻止代理范围直接裸连", prefs.getBoolean("proxyKillSwitch", false)) { putBool("proxyKillSwitch", it) }
                AdvancedDivider()
                AdvancedSwitchRow(Icons.Rounded.RestartAlt, Color(0xFF6366F1), "Root 开机自启", "开机后恢复上次保持运行的 Root 代理", prefs.getBoolean("proxyRootAutoStart", false)) { putBool("proxyRootAutoStart", it) }
                AdvancedDivider()
                AdvancedSwitchRow(Icons.Rounded.AutoFixHigh, Color(0xFF64748B), "自动覆写", "仅修改运行副本，不直接改源订阅", prefs.getBoolean("proxyBaseAutoOverwrite", true)) { putBool("proxyBaseAutoOverwrite", it) }
            }
        }

        item { AdvancedSectionLabel("共享网络") }
        item {
            AdvancedGroup {
                AdvancedSwitchRow(Icons.Rounded.WifiTethering, Color(0xFF10B981), "接管共享网络", "将热点/USB 转发流量纳入 Root 透明代理", prefs.getBoolean("proxySharedNetwork", false)) { putBool("proxySharedNetwork", it) }
                AdvancedInfoRow(Icons.Rounded.Router, Color(0xFF0EA5E9), "当前接管方式", "自动处理进入 PREROUTING 的共享流量；接口/MAC 精细过滤尚未开放")
            }
        }

        item { AdvancedSectionLabel("CNIP") }
        item {
            AdvancedGroup {
                AdvancedSwitchRow(Icons.Rounded.Public, Color(0xFFF59E0B), "中国 IP 自动直连", "内置 IPv4/IPv6 快照 + Mihomo 远端更新", prefs.getBoolean("proxyCnIpDirect", false)) { putBool("proxyCnIpDirect", it) }
                AdvancedDivider()
                AdvancedInfoRow(Icons.Rounded.Inventory2, Color(0xFF0EA5E9), "数据源", "内置离线快照 · 运行时按 provider 更新")
            }
        }

        item { AdvancedSectionLabel("绕过规则") }
        item {
            AdvancedGroup {
                AdvancedValueRow(Icons.Rounded.Route, Color(0xFFEF4444), "CIDR 绕过", setSummary("proxyBypassCidrs")) {
                    editSet("proxyBypassCidrs", "CIDR 绕过", "每行一个 IPv4/IPv6 CIDR，例如 10.0.0.0/8 或 fd00::/8。")
                }
                AdvancedDivider()
                AdvancedValueRow(Icons.Rounded.Cable, Color(0xFF64748B), "接口绕过", setSummary("proxyBypassInterfaces")) {
                    editSet("proxyBypassInterfaces", "接口绕过", "每行一个接口名，例如 dummy0、tun+。不能填写 lo。")
                }
            }
        }

        item { AdvancedSectionLabel("诊断与维护") }
        item {
            AdvancedGroup {
                AdvancedActionRow(Icons.Rounded.HealthAndSafety, Color(0xFF10B981), "运行预检", "验证 Root / TPROXY / UID / IPv6 / 绕过规则") {
                    runRoot("运行预检") {
                        val prepared = root.prepare(ProxyRuntimeProfile.load(prefs))
                        val result = root.preflight(prepared)
                        if (result.optBoolean("ok", false)) "预检通过：当前设备支持这组 Root 代理设置" else result.optString("message", "预检失败")
                    }
                }
                AdvancedDivider()
                AdvancedActionRow(Icons.Rounded.Description, Color(0xFF3B82F6), "查看启动配置", "查看最终运行副本，不修改源配置") {
                    runRoot("启动配置") { root.prepare(ProxyRuntimeProfile.load(prefs)).startup }
                }
                AdvancedDivider()
                AdvancedActionRow(Icons.Rounded.Terminal, Color(0xFF8B5CF6), "Root 诊断", "规则、端口、会话、CNIP 与核心日志") {
                    runRoot("Root 诊断") { root.diagnostics() }
                }
                AdvancedDivider()
                AdvancedActionRow(Icons.Rounded.Restore, Color(0xFFEF4444), "恢复网络", "停止代理并清理辟尘自己的透明代理规则") {
                    runRoot("恢复网络") { root.stop(); "已停止 Root 代理并执行网络规则回滚" }
                }
            }
        }

        if (busy) item {
            Surface(shape = RoundedCornerShape(16.dp), color = t.selectionBackground) {
                Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text("正在执行 Root 操作…", color = t.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }

    val currentChoiceKey = choiceKey
    if (choiceTitle != null && currentChoiceKey != null) {
        val current = when (currentChoiceKey) {
            "proxyBaseCore" -> profile.core.id
            "proxyAppScope" -> profile.appScope.id
            "proxyDnsHijack" -> profile.dnsHijack.id
            "proxyBaseIpv6" -> profile.ipv6.id
            else -> prefs.getString(currentChoiceKey, "").orEmpty()
        }
        AdvancedChoiceSheet(
            title = choiceTitle!!,
            values = choices,
            current = current,
            onDismiss = { choiceTitle = null; choiceKey = null },
            onSelect = { value -> putString(currentChoiceKey, value); choiceTitle = null; choiceKey = null },
        )
    }

    editor?.let { state ->
        SetEditorDialog(
            state = state,
            onDismiss = { editor = null },
            onSave = { raw ->
                val set = raw.split('\n', ',', ';').map { it.trim() }.filter { it.isNotEmpty() }.toSortedSet()
                prefs.edit().putStringSet(state.key, set).apply()
                editor = null
                refresh()
            },
        )
    }

    if (infoTitle != null && infoText != null) {
        AdvancedInfoSheet(infoTitle!!, infoText!!, onDismiss = { infoTitle = null; infoText = null })
    }
}

@Composable
private fun AdvancedSectionLabel(text: String) {
    Text(text, color = Color(0xFF94A3B8), fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = .6.sp, modifier = Modifier.padding(start = 12.dp, top = 2.dp))
}

@Composable
private fun AdvancedGroup(content: @Composable ColumnScope.() -> Unit) {
    val t = LocalBichenTokens.current
    val dark = MaterialTheme.colorScheme.background.luminance() < .5f
    Surface(shape = RoundedCornerShape(20.dp), color = if (dark) t.elevatedCardBackground else Color.White, shadowElevation = if (dark) 0.dp else 1.dp) {
        Column(Modifier.fillMaxWidth(), content = content)
    }
}

@Composable
private fun AdvancedDivider() {
    HorizontalDivider(Modifier.padding(start = 62.dp, end = 14.dp), color = if (MaterialTheme.colorScheme.background.luminance() < .5f) LocalBichenTokens.current.outline else Color(0xFFF1F5F9))
}

@Composable
private fun AdvancedIcon(icon: ImageVector, accent: Color) {
    Box(Modifier.size(36.dp).background(accent.copy(alpha = .12f), RoundedCornerShape(11.dp)), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = accent, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun AdvancedValueRow(icon: ImageVector, accent: Color, title: String, value: String, onClick: () -> Unit) {
    val t = LocalBichenTokens.current
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        AdvancedIcon(icon, accent); Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = t.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(value, color = t.textSecondary, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Icon(Icons.Rounded.ChevronRight, null, tint = Color(0xFFCBD5E1), modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun AdvancedSwitchRow(icon: ImageVector, accent: Color, title: String, subtitle: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    val t = LocalBichenTokens.current
    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
        AdvancedIcon(icon, accent); Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = t.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = t.textSecondary, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Switch(checked = checked, onCheckedChange = onChecked)
    }
}

@Composable
private fun AdvancedInfoRow(icon: ImageVector, accent: Color, title: String, subtitle: String) {
    val t = LocalBichenTokens.current
    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        AdvancedIcon(icon, accent); Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = t.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = t.textSecondary, fontSize = 11.sp)
        }
    }
}

@Composable
private fun AdvancedActionRow(icon: ImageVector, accent: Color, title: String, subtitle: String, onClick: () -> Unit) = AdvancedValueRow(icon, accent, title, subtitle, onClick)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AdvancedChoiceSheet(title: String, values: List<AdvancedChoice>, current: String, onDismiss: () -> Unit, onSelect: (String) -> Unit) {
    val t = LocalBichenTokens.current
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = t.elevatedCardBackground, tonalElevation = 0.dp, shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, color = t.textPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            values.forEach { item ->
                val selected = item.value == current
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(15.dp)).background(if (selected) t.selectionBackground else t.controlBackground.copy(alpha = .35f)).clickable { onSelect(item.value) }.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(item.label, color = t.textPrimary, fontSize = 14.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium, modifier = Modifier.weight(1f))
                    if (selected) Icon(Icons.Rounded.Check, null, tint = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

@Composable
private fun SetEditorDialog(state: SetEditorState, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember(state) { mutableStateOf(state.value) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(state.title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(state.hint, color = LocalBichenTokens.current.textSecondary, fontSize = 12.sp)
                OutlinedTextField(value = text, onValueChange = { text = it }, modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp, max = 320.dp), minLines = 6)
            }
        },
        confirmButton = { TextButton(onClick = { onSave(text) }) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AdvancedInfoSheet(title: String, text: String, onDismiss: () -> Unit) {
    val t = LocalBichenTokens.current
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = t.elevatedCardBackground, tonalElevation = 0.dp, shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, color = t.textPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Surface(shape = RoundedCornerShape(16.dp), color = t.controlBackground.copy(alpha = .55f)) {
                Text(text, Modifier.fillMaxWidth().padding(14.dp), color = t.textSecondary, fontSize = 12.sp, lineHeight = 18.sp)
            }
            FilledTonalButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth().height(48.dp), shape = RoundedCornerShape(15.dp)) { Text("关闭") }
        }
    }
}
