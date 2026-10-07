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

    fun changed(key: String) { ProxyRuntimeSettings.markDirty(prefs, key); revision++ }
    fun putBool(key: String, value: Boolean) { prefs.edit().putBoolean(key, value).apply(); changed(key) }
    fun putString(key: String, value: String) { prefs.edit().putString(key, value).apply(); changed(key) }

    HxPage(title = ht("高级代理配置"), largeTitle = false, onBack = onBack) {
        item(key = "abilities") {
            SettingsSection {
                SettingsGroup(title = ht("代理能力")) {
                    OtherSwitch("性能模式", prefs.getBoolean("proxyPerformanceMode", false), icon = PanelIcons.Gauge) { putBool("proxyPerformanceMode", it) }
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
                }
            }
        }

        item(key = "limits") {
            SettingsSection {
                SettingsGroup(title = ht("资源限制")) {
                    ResourceSetting(
                        title = "CPU 核心分配",
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
                    OtherSwitch("启动时清理", prefs.getBoolean("proxyVendorFirewallCleanup", false), icon = HxIcons.BrushCleaning) { putBool("proxyVendorFirewallCleanup", it) }
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
private fun OtherSwitch(title: String, checked: Boolean, enabled: Boolean = true, icon: ImageVector? = null, onChange: (Boolean) -> Unit) {
    SettingsSwitchRow(ht(title), checked, onChange, enabled = enabled, compact = true, icon = icon)
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
    onEnabled: (Boolean) -> Unit,
    onValue: (String) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
        SettingsSwitchRow(ht(title), enabled, onEnabled, compact = true, icon = icon)
        SettingsInput(
            value = value, onValueChange = onValue, enabled = enabled, placeholder = hint,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            modifier = Modifier.padding(start = if (icon != null) HomeRowDims.textStart else HomeRowDims.start, end = HomeRowDims.end + 2.dp),
        )
    }
}
