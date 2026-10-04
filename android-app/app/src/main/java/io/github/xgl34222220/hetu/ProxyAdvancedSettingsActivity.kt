package io.github.xgl34222220.hetu

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.xgl34222220.hetu.ui.HetuTheme
import io.github.xgl34222220.hetu.ui.LocalHetuTokens

class ProxyAdvancedSettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        hxHost { OtherProxySettingsPage { finish() } }
    }
}

private data class OtherChoice(val label: String, val value: String)

@Composable
private fun OtherProxySettingsPage(onBack: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { context.getSharedPreferences("hetu", 0) }
    var revision by remember { mutableIntStateOf(0) }
    var choice by remember { mutableStateOf<String?>(null) }
    val profile = remember(revision) { ProxyRuntimeProfile.load(prefs) }
    val card = Hx.colors.surface

    fun changed(key: String) { ProxyRuntimeSettings.markDirty(prefs, key); revision++ }
    fun putBool(key: String, value: Boolean) { prefs.edit().putBoolean(key, value).apply(); changed(key) }
    fun putString(key: String, value: String) { prefs.edit().putString(key, value).apply(); changed(key) }

    HxPage(title = "高级代理配置", largeTitle = false, compactTitleFontSizeSp = 20f, onBack = onBack) {
        item {
            OtherCard(card) {
                OtherLabel("代理能力")
                OtherSwitch("性能模式", prefs.getBoolean("proxyPerformanceMode", false)) { putBool("proxyPerformanceMode", it) }
                OtherSwitch("QUIC", !profile.quicBlocked) { putBool("proxyQuicBlocked", !it) }
                OtherSwitch("Mihomo DNS 转发", prefs.getBoolean("proxyMihomoDnsForward", true)) { putBool("proxyMihomoDnsForward", it) }
                OtherSwitch("代理 TCP", profile.tcp) { putBool("proxyTcp", it) }
                OtherSwitch("代理 UDP", profile.udp) { putBool("proxyUdp", it) }
            }
        }

        item {
            OtherCard(card) {
                OtherLabel("DNS 劫持")
                val dnsEnabled = prefs.getBoolean("proxyMihomoDnsForward", true) && profile.dnsHijack != ProxyRuntimeProfile.DnsHijack.OFF
                val protocolControl = profile.mode != ProxyRuntimeProfile.Mode.TUN && profile.mode != ProxyRuntimeProfile.Mode.EBPF
                OtherSwitch("DNS 劫持 TCP", prefs.getBoolean("proxyDnsHijackTcp", true), enabled = dnsEnabled && protocolControl) { putBool("proxyDnsHijackTcp", it) }
                OtherSwitch("DNS 劫持 UDP", prefs.getBoolean("proxyDnsHijackUdp", true), enabled = dnsEnabled && protocolControl) { putBool("proxyDnsHijackUdp", it) }
                OtherChoiceRow("DNS 劫持策略", when (profile.dnsHijack) {
                    ProxyRuntimeProfile.DnsHijack.REDIRECT -> "REDIRECT"
                    ProxyRuntimeProfile.DnsHijack.OFF -> "关闭"
                    else -> "TPROXY"
                }) { choice = "dns" }
            }
        }

        item {
            OtherCard(card) {
                OtherLabel("资源限制")
                ResourceSetting(
                    title = "CPU 核心分配",
                    enabled = prefs.getBoolean("proxyCpuAffinityEnabled", false),
                    value = prefs.getString("proxyCpuAffinity", "0-7").orEmpty(),
                    hint = "0-7",
                    keyboardType = KeyboardType.Ascii,
                    onEnabled = { putBool("proxyCpuAffinityEnabled", it) },
                    onValue = { putString("proxyCpuAffinity", it) },
                )
                ResourceSetting(
                    title = "内存限制",
                    enabled = prefs.getBoolean("proxyMemoryLimitEnabled", false),
                    value = prefs.getString("proxyMemoryLimit", "100M").orEmpty(),
                    hint = "100M",
                    keyboardType = KeyboardType.Ascii,
                    onEnabled = { putBool("proxyMemoryLimitEnabled", it) },
                    onValue = { putString("proxyMemoryLimit", it) },
                )
                ResourceSetting(
                    title = "磁盘 I/O 权重",
                    enabled = prefs.getBoolean("proxyIoWeightEnabled", false),
                    value = prefs.getString("proxyIoWeight", "4").orEmpty(),
                    hint = "0-7",
                    keyboardType = KeyboardType.Number,
                    onEnabled = { putBool("proxyIoWeightEnabled", it) },
                    onValue = { putString("proxyIoWeight", it) },
                )
            }
        }

        item {
            OtherCard(card) {
                OtherLabel("厂商防火墙")
                OtherSwitch("启动时清理", prefs.getBoolean("proxyVendorFirewallCleanup", false)) { putBool("proxyVendorFirewallCleanup", it) }
            }
        }
    }

    if (choice == "dns") {
        HxReferenceDialog(onDismiss = { choice = null }, widthFraction = .65f, contentPadding = 24.dp) {
            Text("DNS 劫持策略", modifier = Modifier.fillMaxWidth(),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Column(Modifier.selectableGroup()) {
            listOf(OtherChoice("TPROXY", "tproxy"), OtherChoice("REDIRECT", "redirect"), OtherChoice("关闭", "off")).forEach { item ->
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(selected = profile.dnsHijack.id == item.value, role = Role.RadioButton) {
                    putString("proxyDnsHijack", item.value)
                    prefs.edit().putBoolean("proxyMihomoDnsForward", item.value != "off").apply()
                    revision++
                    choice = null
                }, verticalAlignment = Alignment.CenterVertically) {
                    Text(item.label, modifier = Modifier.weight(1f), color = Hx.colors.text, fontSize = 19.sp)
                    RadioButton(selected = profile.dnsHijack.id == item.value, onClick = null,
                        modifier = Modifier.size(24.dp))
                }
            }
            }
            Spacer(Modifier.height(6.dp))
            Text("取消", color = Hx.colors.accent, fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.align(Alignment.End).heightIn(min = 48.dp).clickable { choice = null }.padding(vertical = 12.dp))
        }
    }
}

@Composable
private fun OtherCard(color: Color, content: @Composable ColumnScope.() -> Unit) {
    SettingsSection { SettingsGroup(content = content) }
}

@Composable
private fun OtherLabel(text: String) {
    Text(text, color = Hx.colors.text, fontSize = 21.sp, lineHeight = 28.sp,
        fontWeight = FontWeight.Bold, modifier = Modifier.semantics { heading() }.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 6.dp))
}

@Composable
private fun OtherSwitch(title: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    SettingsSwitchRow(title, checked, onChange, enabled = enabled, compact = true)
}

@Composable
private fun OtherChoiceRow(title: String, value: String, onClick: () -> Unit) {
    SettingsNavRow(title, value = value, dropdown = true, compact = true, onClick = onClick)
}

@Composable
private fun ResourceSetting(
    title: String,
    enabled: Boolean,
    value: String,
    hint: String,
    keyboardType: KeyboardType,
    onEnabled: (Boolean) -> Unit,
    onValue: (String) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
        SettingsSwitchRow(title, enabled, onEnabled, compact = true)
        SettingsInput(
            value = value, onValueChange = onValue, enabled = enabled, placeholder = hint,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            modifier = Modifier.padding(horizontal = 16.dp),
        )
    }
}
