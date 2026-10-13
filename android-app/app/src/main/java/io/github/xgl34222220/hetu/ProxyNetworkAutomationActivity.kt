package io.github.xgl34222220.hetu

import androidx.compose.ui.text.AnnotatedString
import io.github.xgl34222220.hetu.home.HomeDims
import io.github.xgl34222220.hetu.home.HomeIcons
import io.github.xgl34222220.hetu.home.HomeRowLayout
import io.github.xgl34222220.hetu.home.HomeStatusDot
import io.github.xgl34222220.hetu.home.LocalHomeColors
import io.github.xgl34222220.hetu.panel.PanelIcons
import io.github.xgl34222220.hetu.tools.ToolsIcons
import io.github.xgl34222220.hetu.ui.ht
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
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
import io.github.xgl34222220.hetu.ui.LocalHetuTokens
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.TreeSet

class ProxyNetworkAutomationActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        hxHost { vm -> HxNetworkMatchScreen(vm) { finish() } }
    }
}

@Composable
internal fun HxNetworkMatchScreen(vm: HetuViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = vm.prefs
    val scope = rememberCoroutineScope()
    val home = LocalHomeColors.current
    var revision by remember { mutableIntStateOf(0) }
    var actionKey by remember { mutableStateOf<String?>(null) }
    val enabled = remember(revision) { prefs.getBoolean("networkMatchEnabled", false) }
    val mobile = remember(revision) { prefs.getBoolean("networkMatchMobile", false) }
    val environment = remember(revision) { prefs.getString("networkMatchLastEnvironment", "尚未获取当前网络").orEmpty() }
    val ssidOriginal = remember(revision) { prefs.getStringSet("networkMatchSsids", emptySet()).orEmpty().sorted() }
    val bssidOriginal = remember(revision) { prefs.getStringSet("networkMatchBssids", emptySet()).orEmpty().sorted() }
    val ssids = remember(ssidOriginal) { mutableStateListOf<HxEditEntry>().apply { ssidOriginal.forEach { add(HxEditEntry(it)) } } }
    val bssids = remember(bssidOriginal) { mutableStateListOf<HxEditEntry>().apply { bssidOriginal.forEach { add(HxEditEntry(it)) } } }
    // SIM conditions (no READ_PHONE_STATE: default data SIM slot + operator codes, see NetworkMatchRules).
    val simSlots = remember(revision) { prefs.getStringSet("networkMatchSimSlots", emptySet()).orEmpty().toSet() }
    val mccOriginal = remember(revision) { prefs.getStringSet("networkMatchMccMnc", emptySet()).orEmpty().sorted() }
    val mccs = remember(mccOriginal) { mutableStateListOf<HxEditEntry>().apply { mccOriginal.forEach { add(HxEditEntry(it)) } } }
    val lastActionResult = remember(revision) { prefs.getString("networkMatchLastActionResult", "").orEmpty() }
    // Root monitor daemon: while a session runs it evaluates these rules itself (also when the App is killed).
    var daemon by remember { mutableStateOf<DaemonMonitorPolicy.Status?>(null) }
    LaunchedEffect(revision) {
        daemon = if (!prefs.getBoolean("proxyRootRuntimeRunning", false)) null
            else kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { DaemonMonitorBridge.status(context) }
    }
    var targetPick by remember { mutableStateOf<Pair<String, String>?>(null) }
    var targetChoices by remember { mutableStateOf<List<String>>(emptyList()) }
    fun values(list: List<HxEditEntry>) = list.map { it.text.trim() }.filter { it.isNotEmpty() }.sorted()
    val dirty = values(ssids) != ssidOriginal || values(bssids) != bssidOriginal || values(mccs) != mccOriginal

    fun refresh() { revision++ }
    fun service(action: String) {
        val intent = Intent(context, ProxyNetworkMatchService::class.java).setAction(action)
        if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
    }
    fun evaluate() {
        prefs.edit().putBoolean("networkMatchForceEval", true).apply()
        if (prefs.getBoolean("networkMatchEnabled", false)) service("EVAL")
        // A running session's Root monitor reloads the exported rules.
        DaemonMonitorBridge.exportAsync(context)
        scope.launch { delay(550); refresh() }
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (prefs.getBoolean("networkMatchEnabled", false)) service("START")
        scope.launch { delay(450); refresh() }
    }
    fun requestWifiPermissions() {
        val needs = buildList {
            if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED) add(Manifest.permission.NEARBY_WIFI_DEVICES)
            if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                add(Manifest.permission.ACCESS_COARSE_LOCATION)
                add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        }
        if (needs.isNotEmpty()) permissionLauncher.launch(needs.toTypedArray()) else evaluate()
    }
    fun actionLabel(key: String, def: String): String {
        val action = NetworkMatchRules.parseAction(prefs.getString(key, def))
        return when (action.kind) {
            "start" -> "启动代理"
            "stop" -> "停止代理"
            "switch" -> "切换配置 · " + action.target
            "script" -> "运行脚本 · " + action.target
            else -> "不操作"
        }
    }
    fun toggleSlot(slot: String, on: Boolean) {
        val next = (if (on) simSlots + slot else simSlots - slot).toSortedSet()
        prefs.edit().putStringSet("networkMatchSimSlots", next).apply()
        refresh()
        evaluate()
    }
    fun openTargets(key: String, kind: String) {
        scope.launch {
            val names = runCatching {
                if (kind == "switch") {
                    val core = ProxyRuntimeProfile.load(prefs).core
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { ProxyConfigLibrary(context).list(core).map { it.name } }
                } else ProxyScriptHooks.listManaged(context).map { it.name }
            }.getOrElse { emptyList() }.filter { if (kind == "switch") NetworkMatchRules.validConfigName(it) else NetworkMatchRules.validScriptName(it) }
            if (names.isEmpty()) {
                vm.toast(if (kind == "switch") "当前核心没有可切换的配置" else "没有可运行的脚本，请先在「脚本」中导入 .sh")
            } else {
                targetChoices = names
                targetPick = key to kind
            }
        }
    }
    fun save() {
        val bad = values(bssids).firstOrNull { !it.matches(Regex("(?i)^[0-9a-f]{2}(?::[0-9a-f]{2}){5}$")) }
        if (bad != null) {
            vm.toast("BSSID 格式无效：$bad")
            return
        }
        val badMcc = values(mccs).firstOrNull { NetworkMatchRules.normalizeMccMnc(it).isEmpty() }
        if (badMcc != null) {
            vm.toast("MCC+MNC 应为 5–6 位数字，例如 46000：$badMcc")
            return
        }
        prefs.edit()
            .putStringSet("networkMatchSsids", values(ssids).toSet())
            .putStringSet("networkMatchBssids", values(bssids).map { it.uppercase() }.toSet())
            .putStringSet("networkMatchMccMnc", NetworkMatchRules.sanitizeMccMncs(values(mccs)))
            .apply()
        vm.toast("匹配条件已保存")
        refresh()
        evaluate()
    }

    HxPage(
        title = ht("网络匹配"),
        largeTitle = false,
        subtitle = ht("按当前网络环境自动启停代理"),
        onBack = onBack,
        onRefresh = { requestWifiPermissions() },
        actions = {
            HxBarAction(HomeIcons.RefreshCw, "刷新当前网络", onClick = { requestWifiPermissions() })
            HxBarAction(HomeIcons.Check, "保存", onClick = ::save, enabled = dirty)
        },
    ) {
        item(key = "env") {
            SettingsSection {
                // What the phone is on right now: the thing every rule below is matched against.
                SettingsGroup {
                    HomeRowLayout(
                        AnnotatedString(ht("当前环境")), subtitle = environment,
                        icon = if (environment.startsWith("Wi")) ToolsIcons.Wifi else if (environment.contains("移动")) HxIcons.Signal else PanelIcons.Globe,
                        iconTint = home.accent,
                        trailing = { HomeStatusDot(if (enabled) home.good else home.t3, pulse = enabled) },
                    )
                    SettingsDivider()
                    val owns = daemon?.ownsNetworkMatch() == true
                    HomeRowLayout(
                        AnnotatedString(ht("Root 守护")),
                        subtitle = when {
                            daemon == null -> "代理运行时由 Root 守护执行匹配，App 被杀也不中断"
                            owns -> "正在执行网络匹配" + (daemon?.lastAction?.takeIf { it.isNotBlank() }?.let { "：$it" } ?: "")
                            daemon?.alive == true -> "运行中（未启用网络匹配）"
                            else -> "未运行：由 App 前台服务执行匹配"
                        },
                        icon = HxIcons.Radio,
                        iconTint = home.accent,
                        trailing = { HomeStatusDot(if (owns) home.good else home.t3, pulse = owns) },
                    )
                }
            }
        }
        item(key = "master") {
            SettingsSection {
                SettingsGroup {
                    SettingsSwitchRow(ht("自动网络匹配"), enabled, { value ->
                        if (value) {
                            prefs.edit().putBoolean("networkMatchEnabled", true).apply()
                            service("START")
                            refresh()
                            requestWifiPermissions()
                        } else {
                            prefs.edit().putBoolean("networkMatchEnabled", false).apply()
                            context.stopService(Intent(context, ProxyNetworkMatchService::class.java))
                            DaemonMonitorBridge.exportAsync(context)
                            refresh()
                        }
                    }, subtitle = ht(if (enabled) "监听服务正在运行" else "关闭后不会自动启停代理"), icon = HxIcons.Radio)
                    SettingsDivider()
                    SettingsSwitchRow(ht("移动数据"), mobile, {
                        prefs.edit().putBoolean("networkMatchMobile", it).apply()
                        refresh()
                        evaluate()
                    }, subtitle = ht("使用蜂窝网络时视为匹配"), icon = ToolsIcons.RadioTower)
                    SettingsDivider()
                    SettingsSwitchRow(ht("卡1 · 蜂窝数据"), "1" in simSlots, { toggleSlot("1", it) },
                        subtitle = ht("默认数据卡在卡槽 1 且使用蜂窝网络时匹配"), icon = HxIcons.Signal)
                    SettingsDivider()
                    SettingsSwitchRow(ht("卡2 · 蜂窝数据"), "2" in simSlots, { toggleSlot("2", it) },
                        subtitle = ht("默认数据卡在卡槽 2 且使用蜂窝网络时匹配"), icon = HxIcons.Signal)
                }
            }
        }
        item(key = "actions") {
            SettingsSection {
                SettingsGroup(title = ht("自动动作")) {
                    SettingsNavRow(ht("匹配成功"), compact = true, icon = HxIcons.CirclePlay, value = ht(actionLabel("networkMatchAction", "start")), dropdown = true) {
                        actionKey = "networkMatchAction"
                    }
                    SettingsDivider()
                    SettingsNavRow(ht("条件失配"), compact = true, icon = HxIcons.CircleStop, value = ht(actionLabel("networkUnmatchAction", "none")), dropdown = true) {
                        actionKey = "networkUnmatchAction"
                    }
                    if (lastActionResult.isNotBlank()) {
                        SettingsDivider()
                        SettingsNavRow(ht("最近动作"), subtitle = lastActionResult, compact = true, icon = HxIcons.Radio) { refresh() }
                    }
                }
            }
        }
        item(key = "ssid") {
            SettingsSection { HxEditableList("Wi‑Fi SSID（留空不限）", "SSID", "例如 Home-5G", ssids) }
        }
        item(key = "bssid") {
            SettingsSection { HxEditableList("Wi‑Fi BSSID（留空不限）", "BSSID", "AA:BB:CC:DD:EE:FF", bssids) }
        }
        item(key = "mccmnc") {
            SettingsSection { HxEditableList("MCC+MNC（蜂窝网络时，默认数据卡或注册网络匹配即满足）", "MCC+MNC", "例如 46000", mccs) }
        }
        item(key = "note") {
            HxBanner(
                ht("部分 Android 版本读取 SSID / BSSID 需要附近设备或定位权限；权限不足时不会猜测 Wi‑Fi 名称。"),
                tone = HxTone.Neutral,
                modifier = Modifier.padding(horizontal = HomeDims.gutter),
            )
        }
    }

    actionKey?.let { key ->
        HxChoiceSheet(
            title = if (key == "networkMatchAction") ht("匹配成功动作") else ht("失配动作"),
            referenceRadios = true,
            trailingReferenceRadios = key == "networkUnmatchAction",
            choices = listOf(HxChoice("none", ht("不操作")), HxChoice("start", ht("启动 Root 代理")), HxChoice("stop", ht("停止 Root 代理")),
                HxChoice("switch", ht("切换配置…")), HxChoice("script", ht("运行脚本…"))),
            selected = NetworkMatchRules.parseAction(prefs.getString(key, if (key == "networkMatchAction") "start" else "none")).kind,
            onPick = { value ->
                actionKey = null
                if (value == "switch" || value == "script") {
                    openTargets(key, value)
                } else {
                    prefs.edit().putString(key, value).apply()
                    refresh()
                    evaluate()
                }
            },
            onDismiss = { actionKey = null },
        )
    }

    targetPick?.let { (key, kind) ->
        val current = NetworkMatchRules.parseAction(prefs.getString(key, ""))
        HxChoiceSheet(
            title = if (kind == "switch") ht("请选择匹配时使用的配置") else ht("请选择匹配时运行的脚本"),
            referenceRadios = true,
            choices = targetChoices.map { HxChoice(it, it) },
            selected = if (current.kind == kind) current.target else null,
            maxVisibleChoices = 8,
            footer = if (kind == "script") ht("脚本以 Root 运行，最长 12 秒，输出与退出码写入 scripts.log；环境变量 HETU_EVENT / HETU_NETWORK。") else ht("运行中会校验新配置后重启核心；失败时恢复原配置。"),
            onPick = { name ->
                val encoded = NetworkMatchRules.Action(kind, name).encode()
                prefs.edit().putString(key, encoded).apply()
                targetPick = null
                refresh()
                evaluate()
            },
            onDismiss = { targetPick = null },
        )
    }
}
