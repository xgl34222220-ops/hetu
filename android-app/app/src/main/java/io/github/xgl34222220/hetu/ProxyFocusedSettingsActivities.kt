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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SharedNetworkSettingsPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("hetu", 0) }
    var enabled by remember { mutableStateOf(prefs.getBoolean("proxySharedNetwork", false)) }
    var revision by remember { mutableIntStateOf(0) }
    var snapshot by remember { mutableStateOf(SharedNetworkSnapshot()) }
    var loading by remember { mutableStateOf(true) }
    var interfaceSheet by remember { mutableStateOf(false) }
    var macSheet by remember { mutableStateOf(false) }
    var interfaceBypass by remember {
        mutableStateOf(TreeSet(prefs.getStringSet("proxyBypassInterfaces", emptySet()).orEmpty()))
    }
    var macBypass by remember {
        mutableStateOf(TreeSet(prefs.getStringSet("proxySharedBypassMacs", emptySet()).orEmpty()))
    }
    var macEditor by remember { mutableStateOf("") }
    var macError by remember { mutableStateOf("") }

    LaunchedEffect(revision) {
        loading = true
        snapshot = ProxySharedNetworkInspector.inspect(context)
        loading = false
    }

    fun saveInterfaces(next: Set<String>) {
        val sorted = TreeSet(next)
        interfaceBypass = sorted
        prefs.edit().putStringSet("proxyBypassInterfaces", sorted).apply()
        ProxyRuntimeSettings.markDirty(prefs, "proxyBypassInterfaces")
    }

    fun saveMacs(next: Set<String>) {
        val sorted = TreeSet(next.map { it.lowercase() })
        macBypass = sorted
        prefs.edit().putStringSet("proxySharedBypassMacs", sorted).apply()
        ProxyRuntimeSettings.markDirty(prefs, "proxySharedBypassMacs")
    }

    FocusedSettingsScaffold("共享网络", "热点、USB 与局域网转发流量", onBack) {
        item {
            FocusedNotice(
                "开启后，河图接管共享/转发流量。接口绕过和 MAC 直连规则都会在 Root PREROUTING/FORWARD 层生效；修改后重启代理应用。"
            )
        }
        item {
            FocusedGroup {
                FocusedSwitchRow(
                    Icons.Rounded.WifiTethering,
                    Color(0xFF10B981),
                    "接管共享网络",
                    "将进入 PREROUTING 的共享流量纳入 Root 透明代理",
                    enabled,
                ) {
                    enabled = it
                    prefs.edit().putBoolean("proxySharedNetwork", it).apply()
                    ProxyRuntimeSettings.markDirty(prefs, "proxySharedNetwork")
                }
                FocusedDivider()
                FocusedValueRow(
                    Icons.Rounded.Cable,
                    Color(0xFF0EA5E9),
                    "接口管理",
                    if (loading) "正在读取接口" else "选择不由河图接管的共享/转发接口",
                    if (interfaceBypass.isEmpty()) "全部接管" else "${interfaceBypass.size} 个直连接口",
                ) {
                    interfaceSheet = true
                }
                FocusedDivider()
                FocusedValueRow(
                    Icons.Rounded.Devices,
                    Color(0xFF8B5CF6),
                    "下游设备 / MAC",
                    if (loading) "正在读取邻居表" else "指定设备绕过透明代理直接联网",
                    if (macBypass.isEmpty()) "未设置" else "${macBypass.size} 个直连设备",
                ) {
                    macEditor = macBypass.joinToString("\n")
                    macError = ""
                    macSheet = true
                }
                FocusedDivider()
                FocusedActionRow(
                    Icons.Rounded.Refresh,
                    Color(0xFF64748B),
                    "刷新共享网络状态",
                    when {
                        loading -> "读取中…"
                        snapshot.error.isNotBlank() -> snapshot.error
                        else -> "${snapshot.interfaces.size} 个接口 · ${snapshot.clients.size} 个可识别下游设备"
                    },
                ) {
                    revision++
                }
            }
        }

        item {
            FocusedGroup {
                FocusedInfoRow(
                    Icons.Rounded.Router,
                    Color(0xFF0EA5E9),
                    "当前接管方式",
                    "本机应用仍按应用范围与 YAML 分流；共享设备先经过接口/MAC/CIDR 绕过，再进入透明代理。",
                )
            }
        }
    }

    if (interfaceSheet) {
        ModalBottomSheet(
            onDismissRequest = { interfaceSheet = false },
            containerColor = Color.Transparent,
            shape = RoundedCornerShape(topStart = HetuGlassRadius.Sheet, topEnd = HetuGlassRadius.Sheet),
            dragHandle = { BottomSheetDefaults.DragHandle() },
        ) {
            Column(
                Modifier.fillMaxWidth().fillMaxHeight(.72f).liquidSheetMaterial().navigationBarsPadding()
                    .padding(horizontal = 18.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("接口管理", fontSize = 21.sp, fontWeight = FontWeight.ExtraBold)
                Text(
                    "打开“直连”后，该接口进入河图 Root 链时会立即 RETURN，不再送入 Mihomo。不要绕过当前实际需要代理的下游接口。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                )
                if (snapshot.interfaces.isEmpty()) {
                    Text(
                        if (loading) "正在读取接口…" else snapshot.error.ifBlank { "没有读取到活动接口" },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 13.sp,
                    )
                } else {
                    snapshot.interfaces.forEach { item ->
                        val checked = item.name in interfaceBypass
                        WorkspaceSettingRow(
                            item.name,
                            "状态：${item.state}",
                            Icons.Rounded.Cable,
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    if (checked) "直连" else "接管",
                                    color = if (checked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                )
                                Spacer(Modifier.width(6.dp))
                                LiquidSwitch(
                                    checked = checked,
                                    onCheckedChange = { value ->
                                        val next = TreeSet(interfaceBypass)
                                        if (value) next.add(item.name) else next.remove(item.name)
                                        saveInterfaces(next)
                                    },
                                )
                            }
                        }
                        WorkspaceInsetDivider()
                    }
                }
                TextButton(
                    onClick = {
                        interfaceSheet = false
                        context.startActivity(Intent(context, ProxyBypassRulesActivity::class.java))
                    },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) {
                    Text("打开完整 CIDR / 接口绕过规则")
                }
            }
        }
    }

    if (macSheet) {
        ModalBottomSheet(
            onDismissRequest = { macSheet = false },
            containerColor = Color.Transparent,
            shape = RoundedCornerShape(topStart = HetuGlassRadius.Sheet, topEnd = HetuGlassRadius.Sheet),
            dragHandle = { BottomSheetDefaults.DragHandle() },
        ) {
            Column(
                Modifier.fillMaxWidth().fillMaxHeight(.84f).liquidSheetMaterial().navigationBarsPadding().imePadding()
                    .padding(horizontal = 18.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("下游设备 / MAC", fontSize = 21.sp, fontWeight = FontWeight.ExtraBold)
                Text(
                    "勾选设备后，它在共享网络的 TPROXY、Redirect、DNS、UDP 防泄漏、QUIC 与 Kill Switch 链中都会优先 RETURN 直连。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                )

                if (snapshot.clients.isNotEmpty()) {
                    Text("已检测设备", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    snapshot.clients.forEach { client ->
                        val checked = client.mac in macBypass
                        WorkspaceSettingRow(
                            client.ip,
                            "${client.mac} · ${client.iface} · ${client.state}",
                            Icons.Rounded.Devices,
                        ) {
                            LiquidSwitch(
                                checked = checked,
                                onCheckedChange = { value ->
                                    val next = TreeSet(macBypass)
                                    if (value) next.add(client.mac) else next.remove(client.mac)
                                    saveMacs(next)
                                    macEditor = next.joinToString("\n")
                                },
                            )
                        }
                        WorkspaceInsetDivider()
                    }
                } else {
                    Text(
                        if (loading) "正在读取下游设备…" else snapshot.error.ifBlank { "当前邻居表没有可识别的下游 MAC，可在下面手动填写。" },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                    )
                }

                Text("手动 MAC 列表", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                LiquidGlassTextField(
                    value = macEditor,
                    onValueChange = {
                        macEditor = it.take(4096)
                        macError = ""
                    },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 130.dp, max = 240.dp),
                    label = "MAC 列表",
                    placeholder = "每行一个，例如 aa:bb:cc:dd:ee:ff",
                    singleLine = false,
                    textStyle = LocalTextStyle.current.copy(
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        fontSize = 13.sp,
                        lineHeight = 19.sp,
                    ),
                )
                if (macError.isNotBlank()) {
                    Text(macError, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                }
                Button(
                    onClick = {
                        val values = macEditor.lineSequence()
                            .map { it.trim().lowercase() }
                            .filter { it.isNotBlank() }
                            .toSortedSet()
                        val bad = values.firstOrNull {
                            !it.matches(Regex("(?i)^[0-9a-f]{2}(?::[0-9a-f]{2}){5}$")) ||
                                it == "00:00:00:00:00:00" || it == "ff:ff:ff:ff:ff:ff"
                        }
                        if (values.size > 64) {
                            macError = "最多 64 个 MAC"
                        } else if (bad != null) {
                            macError = "MAC 格式无效：$bad"
                        } else {
                            saveMacs(values)
                            macSheet = false
                        }
                    },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) {
                    Text("保存 MAC 直连规则")
                }
                Spacer(Modifier.height(4.dp))
            }
        }
    }
}

@Composable
private fun CnIpSettingsPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("hetu", 0) }
    var enabled by remember { mutableStateOf(prefs.getBoolean("proxyCnIpDirect", false)) }
    val runtimeMode = ProxyRuntimeProfile.load(prefs).mode
    FocusedSettingsScaffold("CNIP 设置", "中国大陆 IPv4 / IPv6 自动直连", onBack) {
        item {
            FocusedNotice(
                "CNIP 只补充 IP 级直连，不替代 YAML 中已有的域名规则。河图会把内置 IPv4 / IPv6 网段写入当前 Mihomo / Root 运行配置；修改后重启代理生效。"
            )
        }
        item {
            FocusedGroup {
                FocusedSwitchRow(
                    Icons.Rounded.Public,
                    Color(0xFFF59E0B),
                    "中国 IP 自动直连",
                    "命中国大陆 IPv4 / IPv6 网段时直接连接",
                    enabled,
                ) {
                    enabled = it
                    prefs.edit().putBoolean("proxyCnIpDirect", it).apply()
                    ProxyRuntimeSettings.markDirty(prefs, "proxyCnIpDirect")
                }
                FocusedDivider()
                FocusedInfoRow(
                    Icons.Rounded.Inventory2,
                    Color(0xFF0EA5E9),
                    "数据源",
                    "内置离线 IPv4 / IPv6 快照 · 随河图版本更新",
                )
                FocusedDivider()
                FocusedActionRow(
                    Icons.Rounded.Memory,
                    Color(0xFF8B5CF6),
                    "CNIP 运行模式",
                    if (runtimeMode == ProxyRuntimeProfile.Mode.EBPF) {
                        "当前 eBPF · CNIP 与 hetu0 数据面协同"
                    } else {
                        "当前 ${runtimeMode.label} · 可切换 eBPF / TUN / TPROXY 等模式"
                    },
                ) {
                    context.startActivity(Intent(context, ProxyRuntimeCoreSettingsActivity::class.java))
                }
                FocusedDivider()
                FocusedActionRow(
                    Icons.Rounded.Apps,
                    Color(0xFF10B981),
                    "例外与强制代理应用",
                    "使用应用名单控制哪些 UID 直连、代理或交给核心配置",
                ) {
                    context.startActivity(Intent(context, ProxyAppSelectionActivity::class.java))
                }
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
        ModalBottomSheet(
            onDismissRequest = { editor = null },
            containerColor = Color.Transparent,
            shape = RoundedCornerShape(topStart = HetuGlassRadius.Sheet, topEnd = HetuGlassRadius.Sheet),
            dragHandle = { BottomSheetDefaults.DragHandle() },
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .crystalMaterial(
                        RoundedCornerShape(topStart = HetuGlassRadius.Sheet, topEnd = HetuGlassRadius.Sheet),
                        depth = CrystalDepth.Popover,
                    )
                    .navigationBarsPadding()
                    .padding(horizontal = 18.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(current.title, fontSize = 21.sp, fontWeight = FontWeight.ExtraBold)
                Text(current.hint, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, lineHeight = 17.sp)
                LiquidGlassTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 180.dp, max = 360.dp),
                    label = "规则",
                    placeholder = "每行一项",
                    singleLine = false,
                )
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
    androidx.compose.foundation.lazy.LazyColumn(modifier = Modifier.fillMaxSize().crystalPageBackground(), contentPadding = PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = hetuContentBottomPadding()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Column(Modifier.statusBarsPadding()) { HetuPageHeader(title, onBack, subtitle) }
        }

        content()
    }
}

@Composable
private fun FocusedNotice(text: String) {
    val t = LocalHetuTokens.current
    Box(
        Modifier
            .fillMaxWidth()
            .crystalMaterial(
                RoundedCornerShape(HetuGlassRadius.Tile),
                depth = CrystalDepth.InsetItem,
            )
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Text(
            text,
            color = t.textSecondary,
            fontSize = 12.sp,
            lineHeight = 18.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}
@Composable private fun FocusedGroup(content: @Composable ColumnScope.() -> Unit) {
    GroupedInsetSection(content = content)
}
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
    WorkspaceSettingRow(title, subtitle, icon) {
        Box(Modifier.heightIn(min = 48.dp), contentAlignment = Alignment.CenterEnd) {
            LiquidSwitch(checked = checked, onCheckedChange = onChecked)
        }
    }
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
