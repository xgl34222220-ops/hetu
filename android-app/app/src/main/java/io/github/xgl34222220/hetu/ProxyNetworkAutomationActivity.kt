package io.github.xgl34222220.hetu

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.TreeSet

class ProxyNetworkAutomationActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { HetuTheme { ProxyNetworkAutomationPage(onBack = { finish() }) } }
    }
}

private data class NetworkChoice(val label: String, val value: String)
private data class NetworkEditor(val key: String, val title: String, val hint: String, val value: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProxyNetworkAutomationPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("hetu", 0) }
    val scope = rememberCoroutineScope()
    var revision by remember { mutableIntStateOf(0) }
    var editor by remember { mutableStateOf<NetworkEditor?>(null) }
    var actionKey by remember { mutableStateOf<String?>(null) }
    val enabled = remember(revision) { prefs.getBoolean("networkMatchEnabled", false) }
    val mobile = remember(revision) { prefs.getBoolean("networkMatchMobile", false) }
    val environment = remember(revision) { prefs.getString("networkMatchLastEnvironment", "尚未获取当前网络").orEmpty() }
    val ssids = remember(revision) { TreeSet(prefs.getStringSet("networkMatchSsids", emptySet()).orEmpty()) }
    val bssids = remember(revision) { TreeSet(prefs.getStringSet("networkMatchBssids", emptySet()).orEmpty()) }
    val t = LocalHetuTokens.current
    val dark = MaterialTheme.colorScheme.background.luminance() < .5f
    val pageBg = if (dark) t.pageBackground else Color(0xFFF1F5F9)

    fun refresh() { revision++ }
    fun service(action: String) {
        val intent = Intent(context, ProxyNetworkMatchService::class.java).setAction(action)
        if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
    }
    fun setEnabled(value: Boolean) {
        prefs.edit().putBoolean("networkMatchEnabled", value).apply()
        if (value) service("START") else context.stopService(Intent(context, ProxyNetworkMatchService::class.java))
        refresh()
    }
    fun evaluate() {
        prefs.edit().putBoolean("networkMatchForceEval", true).apply()
        if (prefs.getBoolean("networkMatchEnabled", false)) service("EVAL")
        scope.launch { delay(550); refresh() }
    }
    fun currentAction(key: String, def: String): String = when (prefs.getString(key, def)) {
        "start" -> "启动 Root 代理"
        "stop" -> "停止 Root 代理"
        else -> "不操作"
    }
    fun editSet(key: String, title: String, hint: String, values: Set<String>) {
        editor = NetworkEditor(key, title, hint, TreeSet(values).joinToString("\n"))
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (prefs.getBoolean("networkMatchEnabled", false)) service("EVAL")
        scope.launch { delay(450); refresh() }
    }
    fun requestWifiPermissions() {
        val needs = buildList {
            if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED) add(Manifest.permission.NEARBY_WIFI_DEVICES)
            if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (needs.isNotEmpty()) permissionLauncher.launch(needs.toTypedArray()) else evaluate()
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(pageBg),
        contentPadding = PaddingValues(
            start = 16.dp,
            top = 8.dp,
            end = 16.dp,
            bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 40.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(top = 8.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack, modifier = Modifier.size(42.dp)) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回", tint = t.textPrimary) }
                Spacer(Modifier.width(4.dp))
                Column(Modifier.weight(1f)) {
                    Text("网络匹配", color = t.textPrimary, fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.ExtraBold)
                    Text("按当前网络环境自动控制 Root 代理", color = t.textSecondary, fontSize = 11.sp)
                }
                IconButton(onClick = { requestWifiPermissions() }) { Icon(Icons.Rounded.Refresh, "刷新", tint = MaterialTheme.colorScheme.primary) }
            }
        }

        item {
            Surface(shape = RoundedCornerShape(24.dp), color = if (dark) t.elevatedCardBackground else Color.White, shadowElevation = if (dark) 0.dp else 1.dp) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(42.dp).background(MaterialTheme.colorScheme.primary.copy(alpha = .10f), RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
                            Icon(if (environment.startsWith("Wi")) Icons.Rounded.Wifi else if (environment.contains("移动")) Icons.Rounded.SignalCellularAlt else Icons.Rounded.Public, null, tint = MaterialTheme.colorScheme.primary)
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("当前环境", color = t.textSecondary, fontSize = 11.sp)
                            Text(environment, color = t.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    HorizontalDivider(color = if (dark) t.outline else Color(0xFFF1F5F9))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("自动网络匹配", color = t.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                            Text(if (enabled) "监听服务正在运行" else "关闭后不会自动启停代理", color = t.textSecondary, fontSize = 11.sp)
                        }
                        Switch(checked = enabled, onCheckedChange = { value ->
                            if (value) {
                                prefs.edit().putBoolean("networkMatchEnabled", true).apply(); refresh(); requestWifiPermissions()
                            } else setEnabled(false)
                        })
                    }
                }
            }
        }

        item { NetworkSectionLabel("匹配条件") }
        item {
            NetworkGroup {
                NetworkValueRow(Icons.Rounded.Wifi, Color(0xFF0EA5E9), "Wi‑Fi SSID", if (ssids.isEmpty()) "不限" else "${ssids.size} 个") { editSet("networkMatchSsids", "Wi‑Fi SSID", "每行一个 SSID。留空表示任何 Wi‑Fi 都满足 SSID 条件。", ssids) }
                NetworkDivider()
                NetworkValueRow(Icons.Rounded.Router, Color(0xFF8B5CF6), "Wi‑Fi BSSID", if (bssids.isEmpty()) "不限" else "${bssids.size} 个") { editSet("networkMatchBssids", "Wi‑Fi BSSID", "每行一个 BSSID，例如 AA:BB:CC:DD:EE:FF。留空表示不限。", bssids) }
                NetworkDivider()
                NetworkSwitchRow(Icons.Rounded.SignalCellularAlt, Color(0xFF10B981), "移动数据", "使用蜂窝网络时视为匹配", mobile) {
                    prefs.edit().putBoolean("networkMatchMobile", it).apply(); refresh(); evaluate()
                }
            }
        }

        item { NetworkSectionLabel("自动动作") }
        item {
            NetworkGroup {
                NetworkValueRow(Icons.Rounded.CheckCircle, Color(0xFF10B981), "匹配成功", currentAction("networkMatchAction", "start")) { actionKey = "networkMatchAction" }
                NetworkDivider()
                NetworkValueRow(Icons.Rounded.Cancel, Color(0xFFF59E0B), "条件失配", currentAction("networkUnmatchAction", "none")) { actionKey = "networkUnmatchAction" }
            }
        }

        item {
            Surface(shape = RoundedCornerShape(18.dp), color = t.selectionBackground) {
                Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.Top) {
                    Icon(Icons.Rounded.Info, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    Text("SSID/BSSID 在部分 Android 版本需要附近设备或定位权限。权限不足时河图不会猜测 Wi‑Fi 名称，而会在当前环境中显示无法取得的状态。", color = t.textSecondary, fontSize = 12.sp, lineHeight = 18.sp)
                }
            }
        }
    }

    actionKey?.let { key ->
        val values = listOf(NetworkChoice("不操作", "none"), NetworkChoice("启动 Root 代理", "start"), NetworkChoice("停止 Root 代理", "stop"))
        NetworkChoiceSheet(
            title = if (key == "networkMatchAction") "匹配成功动作" else "失配动作",
            values = values,
            current = prefs.getString(key, if (key == "networkMatchAction") "start" else "none").orEmpty(),
            onDismiss = { actionKey = null },
            onSelect = { value -> prefs.edit().putString(key, value).apply(); actionKey = null; refresh(); evaluate() },
        )
    }

    editor?.let { state ->
        NetworkSetEditor(
            state = state,
            onDismiss = { editor = null },
            onSave = { raw ->
                val set = raw.split('\n', ',', ';').map { it.trim() }.filter { it.isNotEmpty() }.toSortedSet()
                prefs.edit().putStringSet(state.key, set).apply(); editor = null; refresh(); evaluate()
            },
        )
    }
}

@Composable
private fun NetworkSectionLabel(text: String) = Text(text, color = Color(0xFF94A3B8), fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = .6.sp, modifier = Modifier.padding(start = 12.dp, top = 2.dp))

@Composable
private fun NetworkGroup(content: @Composable ColumnScope.() -> Unit) {
    val t = LocalHetuTokens.current
    val dark = MaterialTheme.colorScheme.background.luminance() < .5f
    Surface(shape = RoundedCornerShape(20.dp), color = if (dark) t.elevatedCardBackground else Color.White, shadowElevation = if (dark) 0.dp else 1.dp) { Column(Modifier.fillMaxWidth(), content = content) }
}

@Composable
private fun NetworkDivider() = HorizontalDivider(Modifier.padding(start = 62.dp, end = 14.dp), color = if (MaterialTheme.colorScheme.background.luminance() < .5f) LocalHetuTokens.current.outline else Color(0xFFF1F5F9))

@Composable
private fun NetworkIcon(icon: ImageVector, accent: Color) {
    Box(Modifier.size(36.dp).background(accent.copy(alpha = .12f), RoundedCornerShape(11.dp)), contentAlignment = Alignment.Center) { Icon(icon, null, tint = accent, modifier = Modifier.size(20.dp)) }
}

@Composable
private fun NetworkValueRow(icon: ImageVector, accent: Color, title: String, value: String, onClick: () -> Unit) {
    val t = LocalHetuTokens.current
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        NetworkIcon(icon, accent); Spacer(Modifier.width(12.dp))
        Text(title, color = t.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        Text(value, color = t.textSecondary, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.width(7.dp)); Icon(Icons.Rounded.ChevronRight, null, tint = Color(0xFFCBD5E1), modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun NetworkSwitchRow(icon: ImageVector, accent: Color, title: String, subtitle: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    val t = LocalHetuTokens.current
    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
        NetworkIcon(icon, accent); Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = t.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = t.textSecondary, fontSize = 11.sp)
        }
        Switch(checked = checked, onCheckedChange = onChecked)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NetworkChoiceSheet(title: String, values: List<NetworkChoice>, current: String, onDismiss: () -> Unit, onSelect: (String) -> Unit) {
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
private fun NetworkSetEditor(state: NetworkEditor, onDismiss: () -> Unit, onSave: (String) -> Unit) {
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
                    .background(Color(0xFFCBD5E1), RoundedCornerShape(999.dp)),
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
                FilledTonalButton(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f).height(44.dp),
                    shape = RoundedCornerShape(999.dp),
                ) { Text("取消", fontWeight = FontWeight.Bold) }
                Button(
                    onClick = { onSave(text) },
                    modifier = Modifier.weight(1f).height(44.dp),
                    shape = RoundedCornerShape(999.dp),
                ) { Text("保存", fontWeight = FontWeight.Bold) }
            }
        }
    }
}
