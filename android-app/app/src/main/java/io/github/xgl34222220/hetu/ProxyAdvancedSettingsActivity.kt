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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
    val live = remember(revision) { OtherLiveState.load(prefs) }

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
                    )
                    ResourceSetting(
                        title = "内存限制",
                        subtitle = tuningNote(ht("Go 运行时的软上限：接近时更积极回收，不会结束核心；不低于 32M"), live.tuning("memory").takeIf { prefs.getBoolean("proxyMemoryLimitEnabled", false) }),
                        icon = HxIcons.MemoryStick,
                        enabled = prefs.getBoolean("proxyMemoryLimitEnabled", false),
                        value = prefs.getString("proxyMemoryLimit", "100M").orEmpty(),
                        hint = "100M",
                        keyboardType = KeyboardType.Ascii,
                        onEnabled = { putBool("proxyMemoryLimitEnabled", it) },
                        onValue = { putString("proxyMemoryLimit", it) },
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
                    )
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
        )
    }
}

/** A limit: its switch, and under it the value the limit uses. The field rests while the switch is off. */
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
) {
    Column(Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
        SettingsSwitchRow(ht(title), enabled, onEnabled, subtitle = subtitle, compact = true, icon = icon)
        SettingsInput(
            value = value, onValueChange = onValue, enabled = enabled, placeholder = hint,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            modifier = Modifier.padding(start = if (icon != null) HomeRowDims.textStart else HomeRowDims.start, end = HomeRowDims.end + 2.dp),
        )
    }
}
