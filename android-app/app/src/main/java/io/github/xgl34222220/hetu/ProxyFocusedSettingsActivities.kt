package io.github.xgl34222220.hetu

import io.github.xgl34222220.hetu.ui.CrystalSurface as Surface
import io.github.xgl34222220.hetu.ui.*

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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
import io.github.xgl34222220.hetu.ui.HetuTheme
import io.github.xgl34222220.hetu.ui.LocalHetuTokens
import java.util.TreeSet

class ProxyRuntimeCoreSettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); enableEdgeToEdge()
        setContent { HetuTheme { RuntimeCoreSettingsPage { finish() } } }
    }
}

class ProxySharedNetworkSettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); enableEdgeToEdge()
        setContent { HetuTheme { SharedNetworkSettingsPage { finish() } } }
    }
}

class ProxyCnIpSettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); enableEdgeToEdge()
        setContent { HetuTheme { CnIpSettingsPage { finish() } } }
    }
}

class ProxyBypassRulesActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); enableEdgeToEdge()
        setContent { HetuTheme { BypassRulesPage { finish() } } }
    }
}

@Composable
private fun RuntimeCoreSettingsPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("hetu", 0) }
    var revision by remember { mutableIntStateOf(0) }
    val profile = remember(revision) { ProxyRuntimeProfile.load(prefs) }
    FocusedSettingsScaffold("运行核心", "选择真正负责 Root 代理运行的核心", onBack) {
        item { FocusedNotice("修改运行核心后，下次启动或重启代理生效。这里仅负责核心选择；下载、更新和维护在“内核管理”中完成。") }
        item {
            FocusedGroup {
                FocusedChoiceRow(Icons.Rounded.Memory, Color(0xFF2563EB), "Mihomo", "标准 Mihomo 运行核心", profile.core == ProxyRuntimeProfile.Core.MIHOMO) {
                    prefs.edit().putString("proxyBaseCore", "mihomo").apply(); ProxyRuntimeSettings.markDirty(prefs, "proxyBaseCore"); revision++
                }
                FocusedDivider()
                FocusedChoiceRow(Icons.Rounded.AutoAwesome, Color(0xFF8B5CF6), "Mihomo Smart", "Smart 运行配置入口", profile.core == ProxyRuntimeProfile.Core.MIHOMO_SMART) {
                    prefs.edit().putString("proxyBaseCore", "mihomo-smart").apply(); ProxyRuntimeSettings.markDirty(prefs, "proxyBaseCore"); revision++
                }
            }
        }
        item {
            FocusedGroup {
                FocusedActionRow(Icons.Rounded.SystemUpdateAlt, Color(0xFF334155), "内核管理", "下载、更新与维护核心文件") {
                    context.startActivity(Intent(context, ProxyCoreActivity::class.java))
                }
            }
        }
    }
}

@Composable
private fun SharedNetworkSettingsPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("hetu", 0) }
    var enabled by remember { mutableStateOf(prefs.getBoolean("proxySharedNetwork", false)) }
    FocusedSettingsScaffold("共享网络", "热点、USB 与局域网转发流量", onBack) {
        item { FocusedNotice("仅在确实需要让热点/USB 下游设备经过河图时开启。修改后重启代理生效。") }
        item {
            FocusedGroup {
                FocusedSwitchRow(Icons.Rounded.WifiTethering, Color(0xFF10B981), "接管共享网络", "将进入 PREROUTING 的共享流量纳入 Root 透明代理", enabled) {
                    enabled = it; prefs.edit().putBoolean("proxySharedNetwork", it).apply(); ProxyRuntimeSettings.markDirty(prefs, "proxySharedNetwork")
                }
                FocusedDivider()
                FocusedInfoRow(Icons.Rounded.Router, Color(0xFF0EA5E9), "当前接管方式", "只处理共享/转发流量；本机应用仍按应用范围和原 YAML 分流。")
            }
        }
    }
}

@Composable
private fun CnIpSettingsPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("hetu", 0) }
    var enabled by remember { mutableStateOf(prefs.getBoolean("proxyCnIpDirect", false)) }
    FocusedSettingsScaffold("CNIP 设置", "中国大陆 IPv4 / IPv6 自动直连", onBack) {
        item { FocusedNotice("CNIP 只补充 IP 级直连，不替代 YAML 中已有的域名规则。修改后重启代理生效。") }
        item {
            FocusedGroup {
                FocusedSwitchRow(Icons.Rounded.Public, Color(0xFFF59E0B), "中国 IP 自动直连", "命中国内 IPv4 / IPv6 网段时直接连接", enabled) {
                    enabled = it; prefs.edit().putBoolean("proxyCnIpDirect", it).apply(); ProxyRuntimeSettings.markDirty(prefs, "proxyCnIpDirect")
                }
                FocusedDivider()
                FocusedInfoRow(Icons.Rounded.Inventory2, Color(0xFF0EA5E9), "数据源", "内置离线快照 + Mihomo provider 运行时更新")
            }
        }
    }
}

private data class BypassEditor(val key: String, val title: String, val hint: String, val value: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BypassRulesPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("hetu", 0) }
    var revision by remember { mutableIntStateOf(0) }
    var editor by remember { mutableStateOf<BypassEditor?>(null) }
    fun summary(key: String): String { revision; val values = prefs.getStringSet(key, emptySet()).orEmpty(); return if (values.isEmpty()) "未设置" else "${values.size} 条" }
    fun open(key: String, title: String, hint: String) { val values = TreeSet(prefs.getStringSet(key, emptySet()).orEmpty()); editor = BypassEditor(key, title, hint, values.joinToString("\n")) }

    FocusedSettingsScaffold("绕过规则", "CIDR 与网络接口直连绕过", onBack) {
        item { FocusedNotice("这里的规则在 Root 接管层直接绕过，不会再进入 Mihomo。修改后重启代理生效。") }
        item {
            FocusedGroup {
                FocusedValueRow(Icons.Rounded.Route, Color(0xFFEF4444), "CIDR 绕过", "IPv4 / IPv6 网段", summary("proxyBypassCidrs")) {
                    open("proxyBypassCidrs", "CIDR 绕过", "每行一个 CIDR，例如 10.0.0.0/8 或 fd00::/8")
                }
                FocusedDivider()
                FocusedValueRow(Icons.Rounded.Cable, Color(0xFF64748B), "接口绕过", "按接口名直接绕过", summary("proxyBypassInterfaces")) {
                    open("proxyBypassInterfaces", "接口绕过", "每行一个接口名，例如 dummy0、tun+；不能填写 lo")
                }
            }
        }
    }

    editor?.let { current ->
        var text by remember(current) { mutableStateOf(current.value) }
        ModalBottomSheet(onDismissRequest = { editor = null }, containerColor = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp), dragHandle = { BottomSheetDefaults.DragHandle() }) {
            Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 18.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(current.title, fontSize = 21.sp, fontWeight = FontWeight.ExtraBold)
                Text(current.hint, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, lineHeight = 17.sp)
                OutlinedTextField(value = text, onValueChange = { text = it }, modifier = Modifier.fillMaxWidth().heightIn(min = 180.dp, max = 360.dp), textStyle = LocalTextStyle.current.copy(fontSize = 13.sp, lineHeight = 20.sp), placeholder = { Text("每行一项") })
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TextButton(onClick = { editor = null }, modifier = Modifier.weight(1f)) { Text("取消") }
                    Button(onClick = {
                        val values = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toSortedSet()
                        prefs.edit().putStringSet(current.key, values).apply(); ProxyRuntimeSettings.markDirty(prefs, current.key); revision++; editor = null
                    }, modifier = Modifier.weight(1f)) { Text("保存") }
                }
                Spacer(Modifier.height(4.dp))
            }
        }
    }
}

@Composable
private fun FocusedSettingsScaffold(title: String, subtitle: String, onBack: () -> Unit, content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    val t = LocalHetuTokens.current
    val dark = MaterialTheme.colorScheme.background.luminance() < .5f
    val bg = if (dark) t.pageBackground else MaterialTheme.colorScheme.background
    androidx.compose.foundation.lazy.LazyColumn(modifier = Modifier.fillMaxSize().background(bg), contentPadding = PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = hetuContentBottomPadding()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Column(Modifier.statusBarsPadding()) { HetuPageHeader(title, onBack, subtitle) }
        }

        content()
    }
}

@Composable private fun FocusedNotice(text: String) { Surface(color = if (MaterialTheme.colorScheme.background.luminance() < .5f) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .46f) else Color(0xFFEFF4FA), shape = RoundedCornerShape(18.dp), tonalElevation = 0.dp, shadowElevation = 0.dp) { Text(text, modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium) } }
@Composable private fun FocusedGroup(content: @Composable ColumnScope.() -> Unit) { Surface(modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(18.dp), tonalElevation = 0.dp, shadowElevation = 1.dp) { Column(content = content) } }
@Composable private fun FocusedDivider() { WorkspaceInsetDivider() }
@Composable private fun FocusedIcon(icon: ImageVector, accent: Color) { HetuListIcon(icon) }

@Composable
private fun FocusedChoiceRow(icon: ImageVector, accent: Color, title: String, subtitle: String, selected: Boolean, onClick: () -> Unit) {
    WorkspaceSettingRow(title, subtitle, icon, onClick = onClick) {
        Box(Modifier.heightIn(min = 48.dp), contentAlignment = Alignment.CenterEnd) {
            Icon(if (selected) Icons.Rounded.Check else Icons.Rounded.ChevronRight, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun FocusedSwitchRow(icon: ImageVector, accent: Color, title: String, subtitle: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    WorkspaceSettingRow(title, subtitle, icon) { Switch(checked, onCheckedChange = onChecked, modifier = Modifier.heightIn(min = 48.dp)) }
}

@Composable
private fun FocusedActionRow(icon: ImageVector, accent: Color, title: String, subtitle: String, onClick: () -> Unit) {
    WorkspaceSettingRow(title, subtitle, icon, onClick = onClick) {
        Box(Modifier.heightIn(min = 48.dp), contentAlignment = Alignment.CenterEnd) { Icon(Icons.Rounded.ChevronRight, null, Modifier.size(16.dp)) }
    }
}

@Composable
private fun FocusedInfoRow(icon: ImageVector, accent: Color, title: String, subtitle: String) {
    WorkspaceSettingRow(title, subtitle, icon)
}

@Composable
private fun FocusedValueRow(icon: ImageVector, accent: Color, title: String, subtitle: String, value: String, onClick: () -> Unit) {
    WorkspaceSettingRow(title, listOf(subtitle, value).filter { it.isNotBlank() }.joinToString(" · "), icon, onClick = onClick) {
        Box(Modifier.heightIn(min = 48.dp), contentAlignment = Alignment.CenterEnd) { Icon(Icons.Rounded.ChevronRight, null, Modifier.size(16.dp)) }
    }
}
