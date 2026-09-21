package io.github.xgl34222220.hetu

import io.github.xgl34222220.hetu.ui.CrystalSurface as Surface
import io.github.xgl34222220.hetu.ui.*

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.animation.core.animateFloat
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.xgl34222220.hetu.ui.HetuTheme
import io.github.xgl34222220.hetu.ui.LocalHetuTokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.TreeSet
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import org.json.JSONObject

class ProxyAdvancedSettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val focus = intent.getStringExtra("focus").orEmpty()
        setContent { HetuTheme { ProxyAdvancedSettingsPage(focus = focus, onBack = { finish() }) } }
    }
}

private data class AdvancedChoice(val label: String, val value: String)
private data class SetEditorState(val key: String, val title: String, val hint: String, val value: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProxyAdvancedSettingsPage(focus: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("hetu", 0) }
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
    var preflightText by remember { mutableStateOf<String?>(null) }
    var preflightPassed by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var operationText by remember { mutableStateOf("") }
    var runtimeStatus by remember { mutableStateOf<JSONObject?>(null) }
    var statusError by remember { mutableStateOf("") }
    val lifecycleOwner = LocalLifecycleOwner.current
    val profile = remember(revision) { ProxyRuntimeProfile.load(prefs) }
    val t = LocalHetuTokens.current
    val dark = MaterialTheme.colorScheme.background.luminance() < .5f
    val pageBg = if (dark) t.pageBackground else MaterialTheme.colorScheme.background
    val running = runtimeStatus?.optBoolean("running", false) ?: prefs.getBoolean("proxyRootRuntimeRunning", false)
    val effectiveIpv6 = runtimeStatus?.optString("ipv6Mode", "").orEmpty()
    val settingsPending = ProxyRuntimeSettings.pending(running, ProxyRuntimeSettings.signature(prefs), prefs.getString("proxyRootAppliedSettings", ""))

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
            operationText = "正在执行$title…"
            try {
                val text = withContext(Dispatchers.IO) { block() }
                infoTitle = title
                infoText = text.ifBlank { "完成" }
                refresh()
            } catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) { infoTitle = title; infoText = error.message ?: "操作失败" }
            finally { busy = false; operationText = "" }
        }
    }

    fun applySettings() {
        if (busy) return
        scope.launch {
            busy = true
            operationText = "正在应用设置…"
            try {
                ProxyComposeController(context).restart { stage -> scope.launch { operationText = stage } }
                runtimeStatus = withContext(Dispatchers.IO) { root.status() }
                statusError = ""
                refresh()
            } catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) { infoTitle = "设置应用失败"; infoText = error.message ?: "请查看消息与网络诊断" }
            finally { busy = false; operationText = "" }
        }
    }

    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                if (!busy) try {
                    runtimeStatus = withContext(Dispatchers.IO) { root.status() }
                    statusError = ""
                    refresh()
                } catch (cancel: CancellationException) { throw cancel }
                catch (error: Exception) { statusError = "运行状态暂时无法确认" }
                delay(10_000L)
            }
        }
    }

    fun runPreflight() {
        if (busy) return
        scope.launch {
            busy = true
            try {
                val json = withContext(Dispatchers.IO) {
                    val prepared = root.prepare(ProxyRuntimeProfile.load(prefs))
                    root.preflight(prepared)
                }
                preflightPassed = json.optBoolean("ok", false)
                preflightText = if (preflightPassed) {
                    "预检通过：当前设备支持这组 Root 代理设置"
                } else {
                    json.optString("message", "预检未通过")
                }
            } catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) {
                preflightPassed = false
                preflightText = error.message ?: "预检执行失败"
            } finally { busy = false }
        }
    }

    LaunchedEffect(focus) {
        val index = when (focus) {
            "adblock" -> 5
            "sharing" -> 11
            "cnip" -> 13
            "bypass" -> 15
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
            bottom = hetuContentBottomPadding(),
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Column(Modifier.statusBarsPadding()) { HetuPageHeader("高级代理配置", onBack) } }

        item {
            AdvancedGroup {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(if (running) "当前网络保护" else "网络保护设置", color = t.textPrimary, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    Text("已选择：${ProxyRuntimeSettings.ipv6Label(profile.ipv6.id)}", color = t.textPrimary, fontSize = 14.sp, lineHeight = 20.sp)
                    val actual = when {
                        statusError.isNotBlank() -> statusError
                        !running -> "代理未运行，设置将在下次启动时应用"
                        effectiveIpv6.isBlank() -> "正在核对当前生效策略…"
                        effectiveIpv6 == "disable" && runtimeStatus?.optBoolean("ipv6DisableGuard", false) == true ->
                            if (runtimeStatus?.optBoolean("ipv6DisabledByHetu", false) == true) "IPv6 保护规则已加载；系统禁用状态已确认"
                            else "IPv6 保护规则已加载；系统接口可能保留地址"
                        effectiveIpv6 == "disable" -> "IPv6 防泄漏保护尚未生效，请查看诊断"
                        else -> "当前生效：${ProxyRuntimeSettings.ipv6Label(effectiveIpv6)}"
                    }
                    Text(actual, color = t.textSecondary, fontSize = 13.sp, lineHeight = 19.sp)
                    if (profile.ipv6 == ProxyRuntimeProfile.Ipv6.DISABLE) {
                        Text("这里显示规则和系统开关的核对结果，不代替端到端网络检测。蜂窝/IMS 可能保留地址；代理节点自身的 IPv6 出口需在节点端管理。", color = t.textSecondary, fontSize = 12.sp, lineHeight = 18.sp)
                    }
                    if (settingsPending) {
                        Text("部分设置尚未应用，重启会重新建立现有连接。", color = MaterialTheme.colorScheme.primary, fontSize = 13.sp, lineHeight = 19.sp)
                        Button(onClick = ::applySettings, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), shape = RoundedCornerShape(16.dp)) {
                            Text("应用设置并重启", fontWeight = FontWeight.SemiBold)
                        }
                    }
                    if (busy) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text(operationText.ifBlank { "正在检查运行环境…" }, color = t.textSecondary, fontSize = 13.sp, lineHeight = 19.sp)
                    }
                }
            }
        }

        item { AdvancedSectionLabel("流量接管") }
        item {
            AdvancedGroup {
                Text(
                    "修改后点击「应用设置并重启」使设置生效",
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                    color = t.textSecondary,
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
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
                    ProxyRuntimeProfile.DnsHijack.REDIRECT -> "本地 DNS · ${MihomoStartupConfig.DNS_PORT}"
                    else -> "自动接管本地 DNS"
                }) {
                    showChoices("DNS 劫持", "proxyDnsHijack", listOf(
                        AdvancedChoice("自动接管 DNS", "tproxy"),
                        AdvancedChoice("转发到本地 DNS · ${MihomoStartupConfig.DNS_PORT}", "redirect"),
                        AdvancedChoice("关闭 DNS 劫持", "off"),
                    ))
                }
                AdvancedDivider()
                AdvancedSwitchRow(Icons.Rounded.Speed, Color(0xFFF59E0B), "拦截 QUIC", "阻断 UDP 443，促使客户端回落 TCP", prefs.getBoolean("proxyQuicBlocked", false)) { putBool("proxyQuicBlocked", it) }
                AdvancedDivider()
                AdvancedValueRow(Icons.Rounded.Public, Color(0xFF10B981), "IPv6", when (profile.ipv6) {
                    ProxyRuntimeProfile.Ipv6.BYPASS -> "IPv6 不进核心"
                    ProxyRuntimeProfile.Ipv6.STRICT -> "严格 IPv4 防泄漏"
                    ProxyRuntimeProfile.Ipv6.DISABLE -> "禁用本机 IPv6"
                    else -> "启用 IPv6"
                }) {
                    showChoices("IPv6", "proxyBaseIpv6", listOf(
                        AdvancedChoice("启用 IPv6", "enable"),
                        AdvancedChoice("IPv6 不进核心", "bypass"),
                        AdvancedChoice("严格 IPv4 防泄漏", "strict"),
                        AdvancedChoice("禁用本机 IPv6", "disable"),
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
                AdvancedInfoRow(Icons.Rounded.AutoFixHigh, Color(0xFF64748B), "运行配置副本", "代理设置应用到运行副本，保留原始订阅配置")
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
                    runPreflight()
                }
                AdvancedDivider()
                AdvancedActionRow(Icons.Rounded.Description, Color(0xFF3B82F6), "查看启动配置", "查看最终运行副本，不修改源配置") {
                    runRoot("启动配置") { root.prepare(ProxyRuntimeProfile.load(prefs)).startup }
                }
                AdvancedDivider()
                AdvancedActionRow(Icons.Rounded.Terminal, Color(0xFF8B5CF6), "消息与网络诊断", "微信连接、保活、分流与最近运行事件") {
                    runRoot("消息与网络诊断") { root.diagnostics() }
                }
                AdvancedDivider()
                AdvancedActionRow(Icons.Rounded.Restore, Color(0xFFEF4444), "恢复网络", "停止代理并清理河图自己的透明代理规则") {
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

    preflightText?.let { text ->
        AdvancedPreflightSheet(
            passed = preflightPassed,
            text = text,
            onDismiss = { preflightText = null },
        )
    }

    if (infoTitle != null && infoText != null) {
        AdvancedInfoSheet(infoTitle!!, infoText!!, onDismiss = { infoTitle = null; infoText = null })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AdvancedPreflightSheet(passed: Boolean, text: String, onDismiss: () -> Unit) {
    val t = LocalHetuTokens.current
    val pulse = 1f // completed preflight is a static result, not an ongoing operation
    val accent = if (passed) Color(0xFF10B981) else Color(0xFFF59E0B)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = t.elevatedCardBackground,
        tonalElevation = 0.dp,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        dragHandle = {
            Box(
                Modifier.padding(top = 10.dp, bottom = 5.dp).size(width = 36.dp, height = 4.dp)
                    .background(Color(0xFFCBD5E1), CircleShape),
            )
        },
    ) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 20.dp, end = 20.dp, bottom = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                Modifier.size(62.dp).graphicsLayer { scaleX = pulse; scaleY = pulse }
                    .background(accent.copy(alpha = .11f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (passed) Icons.Rounded.VerifiedUser else Icons.Rounded.HealthAndSafety,
                    null,
                    tint = accent,
                    modifier = Modifier.size(31.dp),
                )
            }
            Text(
                if (passed) "预检通过" else "预检结果",
                color = t.textPrimary,
                fontSize = 20.sp,
                lineHeight = 25.sp,
                fontWeight = FontWeight.ExtraBold,
            )
            Surface(shape = RoundedCornerShape(18.dp), color = t.controlBackground.copy(alpha = .52f)) {
                Text(
                    text,
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                    color = t.textSecondary,
                    fontSize = 12.sp,
                    lineHeight = 19.sp,
                )
            }
            Text("下滑即可关闭", color = Color(0xFF94A3B8), fontSize = 12.sp, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun AdvancedSectionLabel(text: String) {
    Text(text, color = LocalHetuTokens.current.textOnPage, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = .6.sp, modifier = Modifier.padding(start = 12.dp, top = 2.dp))
}

@Composable
private fun AdvancedGroup(content: @Composable ColumnScope.() -> Unit) {
    val t = LocalHetuTokens.current
    val dark = MaterialTheme.colorScheme.background.luminance() < .5f
    Surface(shape = RoundedCornerShape(18.dp), color = if (dark) t.elevatedCardBackground else Color.White, shadowElevation = if (dark) 0.dp else 1.dp) {
        Column(Modifier.fillMaxWidth(), content = content)
    }
}

@Composable
private fun AdvancedDivider() { WorkspaceInsetDivider() }

@Composable
private fun AdvancedIcon(icon: ImageVector, accent: Color) { HetuListIcon(icon) }

@Composable
private fun AdvancedValueRow(icon: ImageVector, accent: Color, title: String, value: String, onClick: () -> Unit) {
    WorkspaceSettingRow(title, value, icon, onClick = onClick) {
        Box(Modifier.heightIn(min = 48.dp), contentAlignment = Alignment.CenterEnd) {
            Icon(Icons.Rounded.ChevronRight, null, Modifier.size(16.dp), tint = LocalHetuTokens.current.textMuted)
        }
    }
}

@Composable
private fun AdvancedSwitchRow(icon: ImageVector, accent: Color, title: String, subtitle: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    WorkspaceSettingRow(title, subtitle, icon) { Switch(checked, onCheckedChange = onChecked, modifier = Modifier.heightIn(min = 48.dp)) }
}

@Composable
private fun AdvancedInfoRow(icon: ImageVector, accent: Color, title: String, subtitle: String) {
    WorkspaceSettingRow(title, subtitle, icon)
}

@Composable
private fun AdvancedActionRow(icon: ImageVector, accent: Color, title: String, subtitle: String, onClick: () -> Unit) = AdvancedValueRow(icon, accent, title, subtitle, onClick)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AdvancedChoiceSheet(title: String, values: List<AdvancedChoice>, current: String, onDismiss: () -> Unit, onSelect: (String) -> Unit) {
    val t = LocalHetuTokens.current
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SetEditorDialog(state: SetEditorState, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember(state) { mutableStateOf(state.value) }
    val t = LocalHetuTokens.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = t.elevatedCardBackground,
        tonalElevation = 0.dp,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        dragHandle = {
            Box(
                Modifier.padding(top = 10.dp, bottom = 6.dp).size(width = 36.dp, height = 4.dp)
                    .background(Color(0xFFCBD5E1), CircleShape),
            )
        },
    ) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 18.dp, end = 18.dp, bottom = 18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(state.title, color = t.textPrimary, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
            Text(state.hint, color = t.textSecondary, fontSize = 12.sp, lineHeight = 18.sp)
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth().heightIn(min = 190.dp, max = 360.dp),
                minLines = 7,
                shape = RoundedCornerShape(18.dp),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FilledTonalButton(onClick = onDismiss, modifier = Modifier.weight(1f).heightIn(min = 48.dp), shape = CircleShape) {
                    Text("取消", fontWeight = FontWeight.Bold)
                }
                Button(onClick = { onSave(text) }, modifier = Modifier.weight(1f).heightIn(min = 48.dp), shape = CircleShape) {
                    Text("保存", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

private fun advancedYamlPreview(text: String): androidx.compose.ui.text.AnnotatedString =
    androidx.compose.ui.text.buildAnnotatedString {
        val cyan = Color(0xFF38BDF8)
        val lime = Color(0xFFA3E635)
        val comment = Color(0xFF64748B)
        val normal = Color(0xFFE2E8F0)
        val lines = text.lines()
        lines.forEachIndexed { index, line ->
            val trimmed = line.trimStart()
            val indent = line.take(line.length - trimmed.length)
            append(indent)
            when {
                trimmed.startsWith("#") -> {
                    pushStyle(androidx.compose.ui.text.SpanStyle(color = comment))
                    append(trimmed)
                    pop()
                }
                trimmed.startsWith("- ") -> {
                    pushStyle(androidx.compose.ui.text.SpanStyle(color = normal))
                    append("- ")
                    pop()
                    val hash = trimmed.indexOf('#', 2)
                    val value = if (hash >= 0) trimmed.substring(2, hash) else trimmed.substring(2)
                    pushStyle(androidx.compose.ui.text.SpanStyle(color = lime))
                    append(value)
                    pop()
                    if (hash >= 0) {
                        pushStyle(androidx.compose.ui.text.SpanStyle(color = comment))
                        append(trimmed.substring(hash))
                        pop()
                    }
                }
                ':' in trimmed -> {
                    val colon = trimmed.indexOf(':')
                    pushStyle(androidx.compose.ui.text.SpanStyle(color = cyan))
                    append(trimmed.substring(0, colon + 1))
                    pop()
                    val rest = trimmed.substring(colon + 1)
                    val hash = rest.indexOf('#')
                    val value = if (hash >= 0) rest.substring(0, hash) else rest
                    pushStyle(androidx.compose.ui.text.SpanStyle(color = lime))
                    append(value)
                    pop()
                    if (hash >= 0) {
                        pushStyle(androidx.compose.ui.text.SpanStyle(color = comment))
                        append(rest.substring(hash))
                        pop()
                    }
                }
                else -> {
                    pushStyle(androidx.compose.ui.text.SpanStyle(color = normal))
                    append(trimmed)
                    pop()
                }
            }
            if (index != lines.lastIndex) append('\n')
        }
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AdvancedInfoSheet(title: String, text: String, onDismiss: () -> Unit) {
    val t = LocalHetuTokens.current
    val context = LocalContext.current
    val codePreview = title == "启动配置"
    val highlighted = remember(text, codePreview) { if (codePreview) advancedYamlPreview(text) else null }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = if (codePreview) Color(0xFF0B1220) else t.elevatedCardBackground,
        contentColor = if (codePreview) Color(0xFFE2E8F0) else t.textPrimary,
        tonalElevation = 0.dp,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        dragHandle = {
            Box(
                Modifier.padding(top = 10.dp, bottom = 6.dp).size(width = 36.dp, height = 4.dp)
                    .background(if (codePreview) Color(0xFF334155) else Color(0xFFCBD5E1), CircleShape),
            )
        },
    ) {
        Column(
            Modifier.fillMaxWidth().fillMaxHeight(if (codePreview) .82f else .62f)
                .navigationBarsPadding().padding(start = 18.dp, end = 18.dp, bottom = 18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(title, Modifier.weight(1f), color = if (codePreview) Color(0xFFF8FAFC) else t.textPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                TextButton(onClick = {
                    val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText(title, text))
                    android.widget.Toast.makeText(context, "已复制", android.widget.Toast.LENGTH_SHORT).show()
                }) { Text("复制") }
            }
            if (codePreview) {
                val lines = remember(text) { maxOf(1, text.count { it == '\n' } + 1) }
                val vScroll = androidx.compose.foundation.rememberScrollState()
                Surface(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    shape = RoundedCornerShape(18.dp),
                    color = Color(0xFF0F172A),
                    border = BorderStroke(.7.dp, Color(0xFF334155)),
                ) {
                    Row(Modifier.fillMaxSize().verticalScroll(vScroll)) {
                        Text(
                            (1..lines).joinToString("\n"),
                            modifier = Modifier.width(44.dp).background(Color(0xFF111827))
                                .padding(top = 12.dp, end = 8.dp, bottom = 12.dp),
                            color = Color(0xFF475569),
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            fontSize = 12.sp,
                            lineHeight = 18.sp,
                            textAlign = androidx.compose.ui.text.style.TextAlign.End,
                        )
                        Box(Modifier.width(1.dp).fillMaxHeight().background(Color(0xFF334155)))
                        Text(
                            highlighted ?: androidx.compose.ui.text.AnnotatedString(text),
                            modifier = Modifier.weight(1f)
                                .horizontalScroll(androidx.compose.foundation.rememberScrollState())
                                .padding(horizontal = 12.dp, vertical = 12.dp),
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            fontSize = 12.sp,
                            lineHeight = 18.sp,
                            softWrap = false,
                        )
                    }
                }
            } else {
                Surface(Modifier.weight(1f), shape = RoundedCornerShape(16.dp), color = t.controlBackground.copy(alpha = .55f)) {
                    Text(
                        text,
                        Modifier.fillMaxWidth().padding(14.dp)
                            .verticalScroll(androidx.compose.foundation.rememberScrollState()),
                        color = t.textSecondary,
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                    )
                }
            }
            Text(
                "下滑即可关闭",
                color = Color(0xFF94A3B8),
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
        }
    }
}
