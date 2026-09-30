package io.github.xgl34222220.hetu

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
        setContent { HetuTheme { OtherProxySettingsPage { finish() } } }
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
    val t = LocalHetuTokens.current
    val dark = MaterialTheme.colorScheme.background.luminance() < .5f
    val page = if (dark) t.pageBackground else Color(0xFFEBEDFA)
    val card = if (dark) t.cardBackground else Color(0xFFF9F8FE)

    fun changed(key: String) { ProxyRuntimeSettings.markDirty(prefs, key); revision++ }
    fun putBool(key: String, value: Boolean) { prefs.edit().putBoolean(key, value).apply(); changed(key) }
    fun putString(key: String, value: String) { prefs.edit().putString(key, value).apply(); changed(key) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(page),
        contentPadding = PaddingValues(bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().padding(start = 8.dp, end = 18.dp, top = 8.dp, bottom = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回", tint = t.textPrimary)
                }
                Text("其他代理配置", fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.Bold, color = t.textPrimary, modifier = Modifier.padding(start = 8.dp))
            }
        }

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
        AlertDialog(
            onDismissRequest = { choice = null },
            title = { Text("DNS 劫持策略") },
            text = {
                Column {
                    listOf(
                        OtherChoice("TPROXY", "tproxy"),
                        OtherChoice("REDIRECT", "redirect"),
                        OtherChoice("关闭", "off"),
                    ).forEach { item ->
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                putString("proxyDnsHijack", item.value)
                                if (item.value == "off") prefs.edit().putBoolean("proxyMihomoDnsForward", false).apply()
                                else prefs.edit().putBoolean("proxyMihomoDnsForward", true).apply()
                                revision++
                                choice = null
                            }.padding(vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = profile.dnsHijack.id == item.value, onClick = null)
                            Spacer(Modifier.width(10.dp))
                            Text(item.label)
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { choice = null }) { Text("取消") } },
        )
    }
}

@Composable
private fun OtherCard(color: Color, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp).background(color, RoundedCornerShape(22.dp)).padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        content = content,
    )
}

@Composable
private fun OtherLabel(text: String) {
    val t = LocalHetuTokens.current
    Text(text, color = t.textPrimary, fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 2.dp, bottom = 8.dp))
}

@Composable
private fun OtherSwitch(title: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    val t = LocalHetuTokens.current
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = if (enabled) t.textPrimary else t.textSecondary, fontSize = 16.5.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
        Switch(checked = checked, enabled = enabled, onCheckedChange = onChange)
    }
}

@Composable
private fun OtherChoiceRow(title: String, value: String, onClick: () -> Unit) {
    val t = LocalHetuTokens.current
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable(onClick = onClick), verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = t.textPrimary, fontSize = 16.5.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
        Text(value, color = t.textSecondary, fontSize = 15.sp)
        Spacer(Modifier.width(6.dp))
        Text("⌃\n⌄", color = t.textSecondary, fontSize = 14.sp, lineHeight = 10.sp)
    }
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
    val t = LocalHetuTokens.current
    Column(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 58.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = t.textPrimary, fontSize = 16.5.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            Switch(checked = enabled, onCheckedChange = onEnabled)
        }
        OutlinedTextField(
            value = value,
            onValueChange = onValue,
            enabled = enabled,
            placeholder = { Text(hint) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = Color(0xFFF0F0FC),
                unfocusedContainerColor = Color(0xFFF0F0FC),
                disabledContainerColor = Color(0xFFEDEDF7),
                focusedBorderColor = Color.Transparent,
                unfocusedBorderColor = Color.Transparent,
                disabledBorderColor = Color.Transparent,
            ),
        )
    }
}
