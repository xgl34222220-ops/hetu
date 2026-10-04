package io.github.xgl34222220.hetu.tools

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import io.github.xgl34222220.hetu.ProxyAdblockRuntimeBridge
import io.github.xgl34222220.hetu.ProxyComposeController
import io.github.xgl34222220.hetu.ProxyCoreDownloadManager
import io.github.xgl34222220.hetu.ProxyCoreRemoteStatus
import io.github.xgl34222220.hetu.ProxySharedNetworkInspector
import io.github.xgl34222220.hetu.ProxyStatusBridge
import io.github.xgl34222220.hetu.ToolsRuntimeBridge
import io.github.xgl34222220.hetu.ui.HetuComposeController
import java.util.TreeSet
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/* ------------------------------------------------------------------ */
/*  Host wiring of pages 28–49. With ToolsAdapter.kt and the two root-  */
/*  package bridges, the only code that touches the existing sources.   */
/* ------------------------------------------------------------------ */

/**
 * Preference keys of the existing runtime, plus the two this module adds for the per-scope app
 * lists. Those two start with `proxyApp` on purpose: `HetuSettingsBackup` saves keys by that prefix.
 */
private object Keys {
    const val AppScope = "proxyAppScope"
    const val AppPackages = "proxyAppPackages"
    const val AppBlacklist = "proxyAppBlacklist"
    const val AppWhitelist = "proxyAppWhitelist"
    const val BypassCidrs = "proxyBypassCidrs"
    const val BypassInterfaces = "proxyBypassInterfaces"
    const val SharedNetwork = "proxySharedNetwork"
    const val SharedMacs = "proxySharedBypassMacs"
    const val CnIp = "proxyCnIpDirect"
    const val AdblockChain = "proxyAdblockChain"
    const val AdblockFallback = "proxyAdblockFallbackEnabled"
    const val Cname = "cnameProtection"
}

private fun SharedPreferences.strings(key: String): Set<String> = getStringSet(key, emptySet()).orEmpty().toSet()

private fun ToolsAppScope.prefId(): String = when (this) {
    ToolsAppScope.Blacklist -> "blacklist"
    ToolsAppScope.Whitelist -> "whitelist"
    ToolsAppScope.Core -> "core"
}

private fun ToolsAppScope.listKey(): String? = when (this) {
    ToolsAppScope.Blacklist -> Keys.AppBlacklist
    ToolsAppScope.Whitelist -> Keys.AppWhitelist
    ToolsAppScope.Core -> null
}

/** What `ToolsRoute` needs for pages 28–49: the callbacks and the app-icon slot of 应用管理. */
internal class ToolsFeatureHost(val actions: ToolsFeatureActions, val appIcon: @Composable (ToolsApp, Modifier) -> Unit)

/**
 * Builds the [ToolsFeatureHost] of pages 28–49 on top of the existing controllers and
 * preferences. Call it inside `HetuToolsV2` and hand both parts to `ToolsRoute`.
 *
 * @param onChanged a runtime setting changed: refresh the shell state (pending-restart banner).
 */
@Composable
internal fun rememberToolsFeatureHost(onChanged: () -> Unit = {}): ToolsFeatureHost {
    val context = LocalContext.current
    val app = context.applicationContext
    val prefs = remember(app) { app.getSharedPreferences("hetu", Context.MODE_PRIVATE) }
    val proxy = remember(app) { ProxyComposeController(app) }
    val rules = remember(app) { HetuComposeController(app) }
    val scope = rememberCoroutineScope()
    val changed by rememberUpdatedState(onChanged)

    fun toast(text: String) = Toast.makeText(app, text, Toast.LENGTH_SHORT).show()
    fun dirty(key: String) { ToolsRuntimeBridge.markDirty(app, key); changed() }

    // 核心导入：the picker result needs the core id and the page's callback from the request.
    var importRequest by remember { mutableStateOf<Pair<String, (String?) -> Unit>?>(null) }
    val corePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val request = importRequest
        importRequest = null
        if (request != null) {
            if (uri == null) {
                request.second(null)
            } else {
                scope.launch {
                    val message = try {
                        ToolsRuntimeBridge.importCore(app, request.first, uri, uri.lastPathSegment ?: "外部核心")
                        "核心已导入"
                    } catch (cancel: CancellationException) {
                        throw cancel
                    } catch (error: Exception) {
                        error.message ?: "导入失败"
                    }
                    request.second(message)
                }
            }
        }
    }

    // 独立 DNS 过滤：starting the local VPN may need the system consent screen first.
    val vpnConsent = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK && prefs.getBoolean(Keys.AdblockFallback, false)) {
            rules.startVpn()
            toast("独立 DNS 过滤已启用；Root 代理启动时自动暂停")
        } else {
            prefs.edit().putBoolean(Keys.AdblockFallback, false).apply()
            toast("未授予 VPN 权限，独立 DNS 过滤未开启")
        }
    }

    return remember(app) {
        ToolsFeatureHost(appIcon = { item, modifier -> ToolsAppIcon(rules, item, modifier) }, actions = ToolsFeatureActions(
            /* ------------------------------ 应用管理 ------------------------------ */
            loadApps = { refresh ->
                val apps = rules.loadApps(forceRefresh = refresh).map { ToolsApp(it.packageName, it.label, it.packageName, it.uid, it.system) }
                val scopeNow = ToolsAppScope.entries.firstOrNull { it.prefId() == prefs.getString(Keys.AppScope, "blacklist") } ?: ToolsAppScope.Blacklist
                val active = prefs.strings(Keys.AppPackages)
                // The runtime reads one list (proxyAppPackages) for whichever scope is active; the
                // other scope's list waits in its own key until that scope is selected.
                val storedBlack = if (prefs.contains(Keys.AppBlacklist)) prefs.strings(Keys.AppBlacklist) else null
                val storedWhite = if (prefs.contains(Keys.AppWhitelist)) prefs.strings(Keys.AppWhitelist) else null
                ToolsAppsSnapshot(
                    apps = apps,
                    scope = scopeNow,
                    blacklist = if (scopeNow == ToolsAppScope.Blacklist || (scopeNow == ToolsAppScope.Core && storedBlack == null && storedWhite == null)) active else storedBlack.orEmpty(),
                    whitelist = if (scopeNow == ToolsAppScope.Whitelist) active else storedWhite.orEmpty(),
                )
            },
            setAppList = { target, keys ->
                val key = target.listKey()
                if (key != null) {
                    val sorted = TreeSet(keys)
                    val editor = prefs.edit().putStringSet(key, sorted)
                    val live = prefs.getString(Keys.AppScope, "blacklist") == target.prefId()
                    if (live) editor.putStringSet(Keys.AppPackages, sorted)
                    editor.apply()
                    if (live) dirty(Keys.AppPackages)
                }
            },
            setAppScope = { next ->
                val previous = ToolsAppScope.entries.firstOrNull { it.prefId() == prefs.getString(Keys.AppScope, "blacklist") } ?: ToolsAppScope.Blacklist
                val editor = prefs.edit()
                // Park the list that was live under the scope being left, unless it is already parked.
                val parkKey = previous.listKey()
                if (parkKey != null && !prefs.contains(parkKey)) editor.putStringSet(parkKey, TreeSet(prefs.strings(Keys.AppPackages)))
                editor.putString(Keys.AppScope, next.prefId())
                val nextKey = next.listKey()
                if (nextKey != null) editor.putStringSet(Keys.AppPackages, TreeSet(if (prefs.contains(nextKey)) prefs.strings(nextKey) else emptySet()))
                editor.apply()
                dirty(Keys.AppScope)
                if (nextKey != null) dirty(Keys.AppPackages)
            },

            /* ------------------------------ 核心管理 ------------------------------ */
            loadCores = { network ->
                val statuses = ProxyCoreDownloadManager(app).statuses(forceNetwork = network)
                ToolsCoresSnapshot(ToolsRuntimeBridge.selectedCoreLabel(app), statuses.map { it.toToolsCore() })
            },
            downloadCore = { id, onProgress -> ToolsRuntimeBridge.downloadCore(app, id, onProgress) },
            removeCore = { id -> ToolsRuntimeBridge.removeCore(app, id) },
            importCore = { id, onDone -> importRequest = id to onDone; corePicker.launch(arrayOf("*/*")) },

            /* ------------------------------ 绕过规则 ------------------------------ */
            loadBypass = { ToolsBypassRules(prefs.strings(Keys.BypassCidrs).sorted(), prefs.strings(Keys.BypassInterfaces).sorted()) },
            saveBypass = { value ->
                prefs.edit().putStringSet(Keys.BypassCidrs, TreeSet(value.cidrs)).putStringSet(Keys.BypassInterfaces, TreeSet(value.interfaces)).apply()
                dirty(Keys.BypassCidrs)
                dirty(Keys.BypassInterfaces)
            },

            /* ------------------------------ 共享网络 ------------------------------ */
            loadShare = {
                val live = ProxySharedNetworkInspector.inspect(app)
                val names = live.interfaces.map { it.name }.toSet()
                ToolsShareSnapshot(
                    settings = ToolsShareSettings(
                        enabled = prefs.getBoolean(Keys.SharedNetwork, false),
                        // The interface set is shared with 绕过规则; only names that are being shared show up here.
                        directInterfaces = prefs.strings(Keys.BypassInterfaces).intersect(names),
                        macs = prefs.strings(Keys.SharedMacs).sorted(),
                    ),
                    interfaces = live.interfaces.map { ToolsShareInterface(it.name, it.state) },
                    clients = live.clients.map { ToolsShareClient(it.ip, it.mac, it.iface, it.state) },
                    note = live.error,
                )
            },
            saveShare = { value ->
                val live = ProxySharedNetworkInspector.inspect(app).interfaces.map { it.name }.toSet()
                // Keep the bypass interfaces that are not shared interfaces: they belong to 绕过规则.
                val interfaces = TreeSet((prefs.strings(Keys.BypassInterfaces) - live) + value.directInterfaces)
                prefs.edit()
                    .putBoolean(Keys.SharedNetwork, value.enabled)
                    .putStringSet(Keys.BypassInterfaces, interfaces)
                    .putStringSet(Keys.SharedMacs, TreeSet(value.macs))
                    .apply()
                dirty(Keys.SharedNetwork)
                dirty(Keys.BypassInterfaces)
                dirty(Keys.SharedMacs)
            },

            /* ------------------------------ CNIP ------------------------------ */
            loadCnIp = { prefs.getBoolean(Keys.CnIp, false) },
            setCnIp = { on -> prefs.edit().putBoolean(Keys.CnIp, on).apply(); dirty(Keys.CnIp) },

            /* ------------------------------ 诊断与维护 ------------------------------ */
            runPreflight = {
                val (passed, message) = ToolsRuntimeBridge.preflight(app)
                if (passed) ToolsPreflight.Passed() else ToolsPreflight.Failed(message.ifBlank { "预检未通过" })
            },
            startupConfig = { proxy.startupConfig() },
            diagnostics = { proxy.diagnostics() },
            restoreNetwork = { proxy.stop(); changed(); "已停止 Root 代理并执行网络规则回滚" },
            onCopy = { label, text ->
                val clipboard = app.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                clipboard?.setPrimaryClip(ClipData.newPlainText(label, text))
                toast("已复制$label")
            },

            /* ------------------------------ 广告过滤 ------------------------------ */
            loadAdblock = {
                val snapshot = rules.rulesSnapshot()
                val chain = ToolsRuntimeBridge.adblockChain(app)
                val now = System.currentTimeMillis()
                ToolsAdblockSnapshot(
                    ToolsAdblockState(
                        enabled = prefs.getBoolean(Keys.AdblockChain, true),
                        proxyRunning = chain.running,
                        ruleMode = chain.ruleMode,
                        modeLabel = when (chain.mode.lowercase()) { "global" -> "全局"; "direct" -> "直连"; else -> "" },
                        ruleCount = snapshot.count,
                        hits = chain.hits,
                        allow = snapshot.allow,
                        block = snapshot.block,
                        sources = snapshot.sources.map { source ->
                            ToolsAdSource(
                                source.id, source.name, source.count,
                                meta = when {
                                    source.lastError.isNotBlank() -> "更新失败：${source.lastError}"
                                    source.lastSuccess > 0L -> "更新于 ${elapsed(now - source.lastSuccess)}"
                                    else -> "使用内置快照"
                                },
                                enabled = source.enabled,
                            )
                        },
                        // RuleProfiles labels are “轻量”, “均衡 · AdGuard DNS”, “加强 · AdGuard + HaGeZi”; anything else is a custom mix.
                        level = ToolsAdLevel.entries.firstOrNull { snapshot.profile.startsWith(it.label) },
                        // The preset contents are defined by RuleProfiles; describe them by what is switched on.
                        levelNote = snapshot.sources.filter { it.enabled }.joinToString(" + ") { it.name }.ifBlank { "没有启用的规则源" } + "。",
                        recent = chain.recent,
                        startupInjected = chain.startupInjected,
                        controllerLoaded = chain.controllerLoaded,
                        standaloneDns = prefs.getBoolean(Keys.AdblockFallback, prefs.getBoolean("vpnWanted", false)),
                        cnameProtection = prefs.getBoolean(Keys.Cname, true),
                    ),
                )
            },
            setAdblockEnabled = { on ->
                val note = withContext(Dispatchers.IO) { ProxyAdblockRuntimeBridge.setEnabled(app, on) }
                dirty(Keys.AdblockChain)
                note
            },
            setAdLevel = { level -> rules.setRuleProfile(level.id) },
            setAdSource = { id, on -> rules.setRuleSource(id, on) },
            updateAdRules = { rules.updateRules() },
            changeAdDomain = { domain, allow, add -> rules.changeDomain(domain, allow, add) },
            setStandaloneDns = { on ->
                prefs.edit().putBoolean(Keys.AdblockFallback, on).putString("proxyAdblockFallbackMode", "vpn").putBoolean("autoStartVpn", on).apply()
                when {
                    ProxyStatusBridge.rootProxyRunning(app) -> if (on) "已保存；Root 代理停止后自动恢复独立 DNS 过滤" else "已关闭代理停止后的独立 DNS 过滤"
                    !on -> { rules.stopVpn(); "独立 DNS 过滤已关闭" }
                    else -> {
                        val consent = rules.prepareVpn()
                        if (consent != null) { vpnConsent.launch(consent); "" } else { rules.startVpn(); "独立 DNS 过滤已启用；Root 代理启动时自动暂停" }
                    }
                }
            },
            setCnameProtection = { on -> prefs.edit().putBoolean(Keys.Cname, on).apply() },
            switchToRuleMode = null,

            onMessage = ::toast,
        ))
    }
}

/** “3 分钟前”, “2 小时前”, “5 天前”. */
private fun elapsed(millis: Long): String {
    val minutes = (millis / 60_000L).coerceAtLeast(0L)
    return when {
        minutes < 1 -> "刚刚"
        minutes < 60 -> "$minutes 分钟前"
        minutes < 60 * 24 -> "${minutes / 60} 小时前"
        else -> "${minutes / (60 * 24)} 天前"
    }
}

private fun ProxyCoreRemoteStatus.toToolsCore(): ToolsCore {
    // The manager only reports an update for a downloaded copy; a bundled core that was never
    // downloaded can still be brought to the latest release, which the card offers as “更新”.
    val upgradable = updateAvailable || (bundled && !downloaded && latestVersion.isNotBlank() && canDownload)
    return ToolsCore(
        id = id,
        name = label,
        installed = when {
            downloaded -> installedVersion.ifBlank { "已下载" } + "（下载版）"
            bundled -> "内置版本" + if (installedVersion.isBlank()) "" else " $installedVersion"
            else -> "未安装"
        },
        latest = latestVersion,
        updateAvailable = upgradable,
        bundled = bundled,
        downloaded = downloaded,
        runnable = runtimeReady,
        canDownload = canDownload,
    )
}

/** Launcher icon of [app], loaded lazily through the controller's icon cache; letter tile meanwhile. */
@Composable
private fun ToolsAppIcon(controller: HetuComposeController, app: ToolsApp, modifier: Modifier) {
    var bitmap by remember(app.packageName) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(app.packageName) { bitmap = controller.appIcon(app.packageName) }
    val loaded = bitmap
    if (loaded != null) Image(loaded.asImageBitmap(), null, modifier) else ToolsAvatar(app.label, modifier)
}

/**
 * While 广告过滤 is on screen the existing network service refreshes its counters every 3 s
 * instead of every 15 s. `ProxyAdblockChainActivity` sets this flag; the module page must too.
 */
@Composable
internal fun ToolsAdblockVisibilityFlag(visible: Boolean) {
    val app = LocalContext.current.applicationContext
    DisposableEffect(visible) {
        val prefs = app.getSharedPreferences("hetu", Context.MODE_PRIVATE)
        if (visible) prefs.edit().putBoolean("proxyAdblockUiVisible", true).apply()
        onDispose { if (visible) prefs.edit().putBoolean("proxyAdblockUiVisible", false).apply() }
    }
}
