package io.github.xgl34222220.hetu

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.xgl34222220.hetu.home.HomeButton
import io.github.xgl34222220.hetu.home.HomeButtonKind
import io.github.xgl34222220.hetu.home.HomeHaptic
import io.github.xgl34222220.hetu.home.HomeIcons
import io.github.xgl34222220.hetu.home.HomeRadio
import io.github.xgl34222220.hetu.home.HomeRowDims
import io.github.xgl34222220.hetu.home.HomeType
import io.github.xgl34222220.hetu.home.LocalHomeColors
import io.github.xgl34222220.hetu.home.LocalHomeHaptics
import io.github.xgl34222220.hetu.home.badText
import io.github.xgl34222220.hetu.home.fill
import io.github.xgl34222220.hetu.home.homeRowPressTint
import io.github.xgl34222220.hetu.panel.PanelIcons
import io.github.xgl34222220.hetu.tools.ToolsIcons
import io.github.xgl34222220.hetu.ui.ht

class ProxyAdvancedSettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        hxHost { OtherProxySettingsPage { finish() } }
    }
}

private data class OtherChoice(val label: String, val value: String)

/** 高级代理配置: what the proxy carries, how DNS is captured, and the limits the core runs under. */
@Composable
private fun OtherProxySettingsPage(onBack: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { context.getSharedPreferences("hetu", 0) }
    val c = LocalHomeColors.current
    val haptics = LocalHomeHaptics.current
    var revision by remember { mutableIntStateOf(0) }
    var choice by remember { mutableStateOf<String?>(null) }
    val profile = remember(revision) { ProxyRuntimeProfile.load(prefs) }
    // What the running core last reported. Cached by the status probe; blank on a runtime that predates these fields.
    // The status probe keeps refreshing these while the page is open; re-read them (prefs only, no Root call).
    var liveTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(4_000L); liveTick++ } }
    val live = remember(revision, liveTick) { OtherLiveState.load(prefs) }

    fun changed(key: String) { ProxyRuntimeSettings.markDirty(prefs, key); revision++ }
    fun putBool(key: String, value: Boolean) { prefs.edit().putBoolean(key, value).apply(); changed(key) }
    fun putString(key: String, value: String) { prefs.edit().putString(key, value).apply(); changed(key) }

    HxPage(title = ht("高级代理配置"), largeTitle = false, onBack = onBack) {
        item(key = "abilities") {
            SettingsSection {
                SettingsGroup(title = ht("代理能力")) {
                    val performance = prefs.getBoolean("proxyPerformanceMode", false)
                    OtherSwitch(
                        "性能模式", performance, icon = PanelIcons.Gauge,
                        subtitle = tuningNote(ht("提高核心进程的调度优先级"), live.tuning("priority").takeIf { performance }),
                    ) { putBool("proxyPerformanceMode", it) }
                    SettingsDivider()
                    OtherSwitch("QUIC", !profile.quicBlocked, icon = PanelIcons.Zap) { putBool("proxyQuicBlocked", !it) }
                    SettingsDivider()
                    OtherSwitch("Mihomo DNS 转发", prefs.getBoolean("proxyMihomoDnsForward", true), icon = HomeIcons.Server) { putBool("proxyMihomoDnsForward", it) }
                    SettingsDivider()
                    OtherSwitch("代理 TCP", profile.tcp, icon = HxIcons.ArrowLeftRight) { putBool("proxyTcp", it) }
                    SettingsDivider()
                    OtherSwitch("代理 UDP", profile.udp, icon = HxIcons.Radio) { putBool("proxyUdp", it) }
                }
            }
        }

        item(key = "dns") {
            SettingsSection {
                SettingsGroup(title = ht("DNS 劫持")) {
                    val dnsEnabled = prefs.getBoolean("proxyMihomoDnsForward", true) && profile.dnsHijack != ProxyRuntimeProfile.DnsHijack.OFF
                    val protocolControl = profile.mode != ProxyRuntimeProfile.Mode.TUN && profile.mode != ProxyRuntimeProfile.Mode.EBPF
                    OtherSwitch("DNS 劫持 TCP", prefs.getBoolean("proxyDnsHijackTcp", true), enabled = dnsEnabled && protocolControl, icon = HxIcons.ArrowLeftRight) { putBool("proxyDnsHijackTcp", it) }
                    SettingsDivider()
                    OtherSwitch("DNS 劫持 UDP", prefs.getBoolean("proxyDnsHijackUdp", true), enabled = dnsEnabled && protocolControl, icon = HxIcons.Radio) { putBool("proxyDnsHijackUdp", it) }
                    SettingsDivider()
                    SettingsNavRow(ht("DNS 劫持策略"), value = when (profile.dnsHijack) {
                        ProxyRuntimeProfile.DnsHijack.REDIRECT -> "REDIRECT"
                        ProxyRuntimeProfile.DnsHijack.OFF -> ht("关闭")
                        else -> "TPROXY"
                    }, dropdown = true, compact = true, icon = PanelIcons.Globe) { choice = "dns" }
                    SettingsDivider()
                    // The system resolver runs as root, the identity the rules used to exempt wholesale.
                    val resolver = prefs.getBoolean("proxyDnsSystemResolver", true)
                    OtherSwitch(
                        "接管系统解析", resolver, enabled = dnsEnabled && protocolControl, icon = PanelIcons.ShieldCheck,
                        subtitle = when {
                            !resolver -> ht("已关闭：系统解析器直接向网络的 DNS 查询")
                            !live.running || live.systemDns.isBlank() || live.systemDns == "unknown" -> ht("让系统解析器的查询也进入核心，应用不再拿到被污染的地址")
                            live.systemDns == "captured" && live.privateDns == "hostname" -> ht("系统设置了指定的私人 DNS：解析走它的加密通道，不经过核心的 DNS")
                            live.systemDns == "captured" -> ht("已生效：系统解析器的查询进入核心")
                            else -> ht("未生效：Root 管理器的 BusyBox 无法切换核心的用户组，仍按旧方式运行")
                        },
                    ) { putBool("proxyDnsSystemResolver", it) }
                }
            }
        }

        item(key = "limits") {
            SettingsSection {
                SettingsGroup(title = ht("资源限制")) {
                    ResourceSetting(
                        title = "CPU 核心分配",
                        subtitle = tuningNote(ht("只让核心在这些 CPU 上运行，如 0-3 或 0,2,4-6"), live.tuning("cpu").takeIf { prefs.getBoolean("proxyCpuAffinityEnabled", false) }),
                        icon = ToolsIcons.Cpu,
                        enabled = prefs.getBoolean("proxyCpuAffinityEnabled", false),
                        value = prefs.getString("proxyCpuAffinity", "0-7").orEmpty(),
                        hint = "0-7",
                        keyboardType = KeyboardType.Ascii,
                        onEnabled = { putBool("proxyCpuAffinityEnabled", it) },
                        onValue = { putString("proxyCpuAffinity", it) },
                        validate = HxResourceLimits::cpuError,
                        advice = { value ->
                            HxResourceLimits.cpuBeyondDevice(value)?.let {
                                val cores = Runtime.getRuntime().availableProcessors()
                                ht(HxResourceLimits.CPU_BEYOND_DEVICE).fill(cores, cores - 1)
                            }
                        },
                    )
                    ResourceSetting(
                        title = "内存限制",
                        subtitle = tuningNote(ht("Go 运行时的软上限：接近时更积极回收，不会结束核心；不低于 32M"), live.tuning("memory").takeIf { prefs.getBoolean("proxyMemoryLimitEnabled", false) }),
                        icon = HxIcons.MemoryStick,
                        enabled = prefs.getBoolean("proxyMemoryLimitEnabled", false),
                        value = prefs.getString("proxyMemoryLimit", RootProxyManager.DEFAULT_MEMORY_LIMIT).orEmpty(),
                        hint = RootProxyManager.DEFAULT_MEMORY_LIMIT,
                        keyboardType = KeyboardType.Ascii,
                        onEnabled = { putBool("proxyMemoryLimitEnabled", it) },
                        onValue = { putString("proxyMemoryLimit", it) },
                        validate = HxResourceLimits::memoryError,
                    )
                    ResourceSetting(
                        title = "磁盘 I/O 权重",
                        subtitle = tuningNote(ht("0 最优先，7 最靠后"), live.tuning("io").takeIf { prefs.getBoolean("proxyIoWeightEnabled", false) }),
                        icon = HxIcons.HardDrive,
                        enabled = prefs.getBoolean("proxyIoWeightEnabled", false),
                        value = prefs.getString("proxyIoWeight", "4").orEmpty(),
                        hint = "0-7",
                        keyboardType = KeyboardType.Number,
                        onEnabled = { putBool("proxyIoWeightEnabled", it) },
                        onValue = { putString("proxyIoWeight", it) },
                        validate = HxResourceLimits::ioError,
                    )
                    // Opt-in: the two values above also become kernel limits (memcg memory.max, io weight).
                    val cgroup = prefs.getBoolean(CoreResourceLimits.PREF_ENABLED, false)
                    val cgroupLive = if (cgroup && live.running) CoreResourceLimits.describe(prefs.getString("proxyRootTuning", "").orEmpty()) else ""
                    val cgroupAbout = ht("同时把上面的内存限制和 I/O 权重交给内核执行（memcg memory.max 与 io 权重，优先 cgroup v2，不支持时用 v1）；核心超出内存上限时可能被系统结束。默认关闭，重启代理后生效")
                    OtherSwitch(
                        "cgroup 硬限制", cgroup, icon = HxIcons.MemoryStick,
                        subtitle = if (cgroupLive.isEmpty()) cgroupAbout else "$cgroupAbout。$cgroupLive",
                    ) { putBool(CoreResourceLimits.PREF_ENABLED, it) }
                }
            }
        }

        item(key = "vendor") {
            SettingsSection {
                SettingsGroup(title = ht("厂商防火墙")) {
                    val cleanup = prefs.getBoolean("proxyVendorFirewallCleanup", false)
                    val about = ht("删除厂商防火墙里拦截 Google 服务的规则，只在带这类规则链的系统上有用")
                    OtherSwitch(
                        "启动时清理", cleanup, icon = HxIcons.BrushCleaning,
                        subtitle = when {
                            !cleanup || !live.running || live.firewallDetail.isBlank() -> about
                            live.firewallField("uids") == "0" -> ht("没有找到已安装的 Google 服务，本次没有可清理的对象")
                            live.firewallField("chains").isNullOrBlank() -> ht("本机没有这类厂商规则链，这个开关在这台设备上不起作用")
                            else -> ht("本机有这类规则链。本次检查 %s 条，删除 %s 条").fill(live.firewallCount("checked"), live.firewallCount("removed"))
                        },
                    ) { putBool("proxyVendorFirewallCleanup", it) }
                }
            }
        }

        item(key = "recovery") {
            SettingsSection {
                SettingsGroup(title = ht("网络恢复")) {
                    // 3.3.3: Wi-Fi reconnect after a failed real probe, on by default (hetu-root.sh wifi-heal);
                    // DNS flush and re-validation always run. The switch writes the Root policy marker at once.
                    val toggle = prefs.getBoolean(NetworkRevalidation.PREF_WIFI_TOGGLE, NetworkRevalidation.WIFI_RECONNECT_DEFAULT)
                    OtherSwitch(
                        "Wi‑Fi 无法联网时自动重连", toggle, icon = HomeIcons.Server,
                        subtitle = ht("启动、停止或重启代理后，以及守护进程发现整机断网时，会绕过河图直接访问国内的 204 检测地址并读取系统的 Wi‑Fi 验证状态；若 Wi‑Fi 仍无法联网，先刷新 DNS 并让系统重新检测，再用 Root 重连 Wi‑Fi（必要时关闭再打开）并发出通知。最多每 10 分钟一次、每小时 3 次。默认开启，立即生效"),
                    ) {
                        prefs.edit().putBoolean(NetworkRevalidation.PREF_WIFI_TOGGLE, it).apply(); revision++
                        NetworkRevalidation.applyWifiReconnectPolicy(context, it)
                    }
                }
            }
        }

        item(key = "matcher") {
            SettingsSection {
                SettingsGroup(title = ht("规则匹配")) {
                    // Opt-in kernel matcher for the app list and bypass networks; a policy file read at start.
                    val ebpf = prefs.getBoolean(EbpfMatcher.PREF_ENABLED, false)
                    // TUN / Mihomo eBPF / Mixed always fall back; an already enabled switch can still be turned off.
                    val modeSupported = EbpfMatcher.modeSupported(profile.mode.id)
                    OtherSwitch(
                        "eBPF 规则匹配", ebpf, enabled = ebpf || modeSupported, icon = ToolsIcons.Cpu,
                        subtitle = EbpfMatcher.describe(ebpf, live.running, live.ebpf, live.ebpfReason, profile.mode.id)
                            .map { ht(it) }.joinToString(""),
                    ) { putBool(EbpfMatcher.PREF_ENABLED, it) }
                }
            }
        }

        item(key = "wechat") {
            SettingsSection {
                SettingsGroup(title = ht("消息")) {
                    // Opt-in kernel bypass for WeChat's UID; 仅所选应用 keeps the user's own selection.
                    val wechatDirect = prefs.getBoolean(WechatTransport.PREF_KERNEL_DIRECT, false)
                    OtherSwitch(
                        "微信直连（绕过核心）", wechatDirect, enabled = wechatDirect || profile.appScope.id != "whitelist", icon = HomeIcons.Server,
                        subtitle = if (profile.appScope.id == "whitelist") ht("仅所选应用模式下由应用名单决定微信是否代理")
                            else ht("微信流量在内核直接放行，不经过核心与代理节点，避免长连接被节点或 NAT 静默断开导致消息延迟。默认关闭，重启代理后生效"),
                    ) { putBool(WechatTransport.PREF_KERNEL_DIRECT, it) }
                }
            }
        }
    }

    if (choice == "dns") {
        // Three ways to capture DNS, answered in a dialog: the choice changes two settings at once.
        HxReferenceDialog(onDismiss = { choice = null }) {
            Text(ht("DNS 劫持策略"), Modifier.fillMaxWidth().semantics { heading() }, color = c.t1, style = HomeType.sheetTitle, textAlign = TextAlign.Center)
            Spacer(Modifier.height(12.dp))
            Column(Modifier.selectableGroup()) {
                listOf(OtherChoice("TPROXY", "tproxy"), OtherChoice("REDIRECT", "redirect"), OtherChoice("关闭", "off")).forEach { item ->
                    val on = profile.dnsHijack.id == item.value
                    val source = remember { MutableInteractionSource() }
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 54.dp).clip(RoundedCornerShape(16.dp)).homeRowPressTint(source)
                            .selectable(selected = on, interactionSource = source, indication = null, role = Role.RadioButton) {
                                haptics(HomeHaptic.Tick)
                                putString("proxyDnsHijack", item.value)
                                prefs.edit().putBoolean("proxyMihomoDnsForward", item.value != "off").apply()
                                revision++
                                choice = null
                            }
                            .padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(ht(item.label), Modifier.weight(1f), color = if (on) c.accent else c.t1, style = HomeType.label.copy(fontWeight = if (on) FontWeight.Bold else FontWeight.Medium))
                        HomeRadio(on)
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            HomeButton("取消", { choice = null }, Modifier.fillMaxWidth(), kind = HomeButtonKind.Soft)
        }
    }
}

@Composable
private fun OtherSwitch(title: String, checked: Boolean, enabled: Boolean = true, icon: ImageVector? = null, subtitle: String? = null, onChange: (Boolean) -> Unit) {
    SettingsSwitchRow(ht(title), checked, onChange, subtitle = subtitle, enabled = enabled, compact = true, icon = icon)
}

/** [about], followed by whether the running core took the setting. [state] is null while there is nothing to report. */
@Composable
private fun tuningNote(about: String, state: String?): String = when (state) {
    "applied" -> about + ht("。本次已生效")
    "failed" -> about + ht("。本次未生效：系统不允许调整")
    else -> about
}

/** The last status the Root script reported for the running core, as the status probe cached it. */
private class OtherLiveState(
    val running: Boolean,
    val systemDns: String,
    val privateDns: String,
    private val tuningReport: String,
    private val firewall: String,
    val firewallDetail: String,
    val ebpf: String = "",
    val ebpfReason: String = "",
) {
    /** `applied` or `failed` for one of `priority`, `cpu`, `memory`, `io`; null when the core is stopped or the control was off. */
    fun tuning(key: String): String? = if (!running) null else field(tuningReport, key)?.substringBefore(':')
    fun firewallField(key: String): String? = field(firewallDetail, key)
    fun firewallCount(key: String): String = field(firewall, key)?.takeIf { it.isNotEmpty() && it.all(Char::isDigit) } ?: "0"

    private fun field(line: String, key: String): String? =
        line.split(' ').firstOrNull { it.startsWith("$key=") }?.substringAfter('=')

    companion object {
        fun load(prefs: android.content.SharedPreferences) = OtherLiveState(
            running = prefs.getBoolean("proxyRootStatusRunning", false),
            systemDns = prefs.getString("proxyRootSystemDns", "").orEmpty(),
            privateDns = prefs.getString("proxyRootPrivateDns", "").orEmpty(),
            tuningReport = prefs.getString("proxyRootTuning", "").orEmpty(),
            firewall = prefs.getString("proxyRootVendorFirewall", "").orEmpty(),
            firewallDetail = prefs.getString("proxyRootVendorFirewallDetail", "").orEmpty(),
            ebpf = prefs.getString(EbpfMatcher.PREF_STATE, "").orEmpty(),
            ebpfReason = prefs.getString(EbpfMatcher.PREF_REASON, "").orEmpty(),
        )
    }
}

/**
 * A limit: its switch, and under it the value the limit uses. The field rests while the switch is off.
 * The field edits a draft checked by the start script's own rules ([validate]); only a value the
 * script accepts reaches prefs (and marks a restart pending), once typing pauses, the field loses
 * focus or the page closes. An invalid draft shows its error and is never written.
 */
@Composable
private fun ResourceSetting(
    title: String,
    enabled: Boolean,
    value: String,
    hint: String,
    keyboardType: KeyboardType,
    icon: ImageVector? = null,
    subtitle: String? = null,
    onEnabled: (Boolean) -> Unit,
    onValue: (String) -> Unit,
    validate: (String) -> String? = { null },
    advice: @Composable (String) -> String? = { null },
) {
    val c = LocalHomeColors.current
    var draft by remember(value) { mutableStateOf(value) }
    val stored by rememberUpdatedState(value)
    val latest by rememberUpdatedState(draft)
    val write by rememberUpdatedState(onValue)
    val check by rememberUpdatedState(validate)
    fun commit() { if (latest != stored && check(latest) == null) write(latest) }
    LaunchedEffect(draft, value) {
        if (draft != value && validate(draft) == null) { kotlinx.coroutines.delay(RESOURCE_COMMIT_DELAY_MS); commit() }
    }
    DisposableEffect(Unit) { onDispose { commit() } }
    var focused by remember { mutableStateOf(false) }
    val error = if (enabled) validate(draft) else null
    val note = if (enabled && error == null) advice(draft) else null
    val start = if (icon != null) HomeRowDims.textStart else HomeRowDims.start
    Column(Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
        SettingsSwitchRow(ht(title), enabled, onEnabled, subtitle = subtitle, compact = true, icon = icon)
        SettingsInput(
            value = draft, onValueChange = { draft = it }, enabled = enabled, placeholder = hint, error = error != null,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            // Audit4 U6: a limit that is off reads as off (the value is kept for when it is turned on).
            modifier = Modifier.padding(start = start, end = HomeRowDims.end + 2.dp).alpha(if (enabled) 1f else .38f).onFocusChanged {
                if (focused && !it.hasFocus) commit()
                focused = it.hasFocus
            },
        )
        (error ?: note)?.let { message ->
            Text(
                if (error != null) ht(message) else message,
                Modifier.padding(start = start + 2.dp, end = HomeRowDims.end + 2.dp, top = 6.dp),
                color = if (error != null) c.badText else c.t2, style = HomeType.note.copy(fontWeight = FontWeight.Medium),
            )
        }
    }
}

private const val RESOURCE_COMMIT_DELAY_MS = 1_200L
