package io.github.xgl34222220.hetu.ui

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.net.VpnService
import io.github.xgl34222220.hetu.BuildConfig
import io.github.xgl34222220.hetu.DnsVpnService
import io.github.xgl34222220.hetu.MihomoVpnService
import io.github.xgl34222220.hetu.ProxyStatusBridge
import io.github.xgl34222220.hetu.ProxyAdblockRuntimeBridge
import io.github.xgl34222220.hetu.RootBridge
import io.github.xgl34222220.hetu.RuleStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.Collator
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal data class HomeSnapshot(
    val rootGranted: Boolean = false,
    val installed: Boolean = false,
    val moduleEnabled: Boolean = false,
    val pendingReboot: Boolean = false,
    val version: String = "—",
    val ruleCount: Int = 0,
    val allowCount: Int = 0,
    val blockCount: Int = 0,
    val blockedQueries: Long = 0,
    val queries: Long = 0,
    val errors: Long = 0,
    val vpnRunning: Boolean = false,
    val vpnWanted: Boolean = false,
    val proxyRunning: Boolean = false,
    val message: String = "",
)

internal data class AppItem(
    val label: String,
    val packageName: String,
    val system: Boolean,
    val uid: Int = -1,
    val icon: Bitmap? = null,
)

internal data class RuleSourceItem(
    val id: String,
    val name: String,
    val url: String,
    val enabled: Boolean,
    val count: Int,
    val lastSuccess: Long = 0L,
    val lastError: String = "",
    val durationMs: Long = 0L,
    val mirror: Int = -1,
)

internal data class RulesSnapshot(
    val count: Int = 0,
    val allow: List<String> = emptyList(),
    val block: List<String> = emptyList(),
    val sources: List<RuleSourceItem> = emptyList(),
    val profile: String = "加载中",
)

internal data class RequestItem(
    val domain: String,
    val reason: String,
    val blocked: Boolean,
    val time: String,
)

internal data class DnsCounters(
    val queries: Long = 0,
    val blocked: Long = 0,
    val errors: Long = 0,
    val sessionQueries: Long = 0,
    val sessionBlocked: Long = 0,
    val lastBlockedDomain: String = "",
    val lastBlockedAt: Long = 0,
) {
    val blockRate: Int get() = if (queries <= 0L) 0 else ((blocked * 100L) / queries).coerceIn(0L, 100L).toInt()
}

internal class HetuComposeController(private val context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("hetu", Context.MODE_PRIVATE)
    @Volatile private var appCache: List<AppItem>? = null
    private val iconCache = android.util.LruCache<String, Bitmap>(128)

    suspend fun homeSnapshot(): HomeSnapshot = withContext(Dispatchers.IO) {
        val rootGranted = RootBridge.hasRoot(app)
        val rules = RuleStore(app)
        var ruleCount = 0
        var allowCount = 0
        var blockCount = 0
        try {
            rules.reload()
            val summary = rules.summary()
            ruleCount = summary.optInt("effectiveCount")
            allowCount = summary.optInt("allowCount")
            blockCount = summary.optInt("blockCount")
        } catch (_: Exception) { }

        val rootProxyRunning = ProxyStatusBridge.rootProxyRunning(app)
        HomeSnapshot(
            rootGranted = rootGranted,
            installed = rootGranted,
            moduleEnabled = false,
            pendingReboot = false,
            version = BuildConfig.VERSION_NAME,
            ruleCount = ruleCount,
            allowCount = allowCount,
            blockCount = blockCount,
            blockedQueries = DnsVpnService.currentBlocked(app),
            queries = DnsVpnService.currentQueries(app),
            errors = DnsVpnService.currentErrors(app),
            vpnRunning = DnsVpnService.running,
            vpnWanted = prefs.getBoolean("vpnWanted", false),
            proxyRunning = rootProxyRunning || MihomoVpnService.engaged || prefs.getBoolean("proxyWanted", false),
            message = prefs.getString("vpnError", "")?.takeIf {
                !DnsVpnService.running && it.isNotBlank()
            } ?: "",
        )
    }

    fun protectionMode(): String = "vpn"
    fun preferredVpnMode(): Boolean = true

    fun setProtectionMode(value: String) {
        prefs.edit().putString("preferredMode", "vpn").apply()
    }

    fun dnsCounters(): DnsCounters = DnsCounters(
        queries = DnsVpnService.currentQueries(app),
        blocked = DnsVpnService.currentBlocked(app),
        errors = DnsVpnService.currentErrors(app),
        sessionQueries = DnsVpnService.sessionQueries(),
        sessionBlocked = DnsVpnService.sessionBlocked(),
        lastBlockedDomain = DnsVpnService.lastBlockedDomain(),
        lastBlockedAt = DnsVpnService.lastBlockedAt(),
    )

    fun prepareVpn(): Intent? = VpnService.prepare(app)

    fun startVpn() {
        prefs.edit().putBoolean("vpnWanted", true).putBoolean("requestLogs", true).remove("vpnError").apply()
        val intent = Intent(app, DnsVpnService::class.java).setAction(DnsVpnService.ACTION_START)
        if (android.os.Build.VERSION.SDK_INT >= 26) app.startForegroundService(intent) else app.startService(intent)
    }

    fun stopVpn() {
        prefs.edit().putBoolean("vpnWanted", false).apply()
        val intent = Intent(app, DnsVpnService::class.java).setAction(DnsVpnService.ACTION_STOP)
        app.startService(intent)
    }

    suspend fun toggleModuleProtection(currentlyEnabled: Boolean): String = withContext(Dispatchers.IO) {
        val command = if (currentlyEnabled) "pause" else "enable"
        val result = RootBridge.run(app, command)
        if (!result.ok()) throw IllegalStateException(result.output.ifBlank { "模块操作失败" })
        val raw = result.output.trim()
        try { JSONObject(raw).optString("message", raw) } catch (_: Exception) { raw.ifBlank { if (currentlyEnabled) "模块保护已暂停" else "广告拦截已开启" } }
    }

    fun cachedApps(): List<AppItem> = appCache ?: emptyList()

    suspend fun preloadApps() {
        loadApps(forceRefresh = false)
    }

    suspend fun loadApps(forceRefresh: Boolean = false): List<AppItem> = withContext(Dispatchers.IO) {
        if (!forceRefresh) appCache?.let { return@withContext it }
        val pm = app.packageManager
        val items = pm.getInstalledApplications(0).asSequence()
            .filter { it.packageName != app.packageName }
            .map { info ->
                // Do not decode every installed app icon before showing the list. Icons are loaded
                // lazily for visible rows by appIcon(), so the page can appear immediately.
                AppItem(
                    label = pm.getApplicationLabel(info).toString(),
                    packageName = info.packageName,
                    system = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                    uid = info.uid,
                    icon = null,
                )
            }.toMutableList()
        val collator = Collator.getInstance(Locale.CHINA)
        items.sortWith { a, b -> collator.compare(a.label, b.label) }
        items.toList().also { appCache = it }
    }

    suspend fun appIcon(packageName: String): Bitmap? = withContext(Dispatchers.IO) {
        iconCache.get(packageName)?.let { return@withContext it }
        try {
            val info = app.packageManager.getApplicationInfo(packageName, 0)
            loadIcon(info)?.also { iconCache.put(packageName, it) }
        } catch (_: Exception) {
            null
        }
    }

    private fun loadIcon(info: ApplicationInfo): Bitmap? = try {
        val drawable = app.packageManager.getApplicationIcon(info)
        val size = 72
        if (drawable is BitmapDrawable && drawable.bitmap != null) {
            Bitmap.createScaledBitmap(drawable.bitmap, size, size, true)
        } else {
            Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also { bitmap ->
                val canvas = Canvas(bitmap)
                drawable.setBounds(0, 0, size, size)
                drawable.draw(canvas)
            }
        }
    } catch (_: Exception) { null }

    fun bypassApps(): Set<String> = prefs.getStringSet("bypassApps", emptySet())?.toSet() ?: emptySet()

    fun setBypass(packageName: String, enabled: Boolean) {
        val next = bypassApps().toMutableSet()
        if (enabled) next.add(packageName) else next.remove(packageName)
        prefs.edit().putStringSet("bypassApps", next).apply()
    }

    fun applyVpnBypass() {
        if (DnsVpnService.running) {
            val intent = Intent(app, DnsVpnService::class.java).setAction(DnsVpnService.ACTION_RESTART)
            app.startService(intent)
        }
    }

    suspend fun rulesSnapshot(): RulesSnapshot = withContext(Dispatchers.IO) {
        val rules = RuleStore(app)
        rules.reload()
        val sources = rules.sources()
        val list = ArrayList<RuleSourceItem>()
        for (i in 0 until sources.length()) {
            val o = sources.optJSONObject(i) ?: continue
            list += RuleSourceItem(
                id = o.optString("id"),
                name = o.optString("name"),
                url = o.optString("url"),
                enabled = o.optBoolean("enabled"),
                count = o.optInt("count"),
                lastSuccess = o.optLong("lastSuccess", 0L),
                lastError = o.optString("lastError", ""),
                durationMs = o.optLong("durationMs", 0L),
                mirror = o.optInt("mirror", -1),
            )
        }
        RulesSnapshot(
            count = rules.count(),
            allow = rules.userList(true),
            block = rules.userList(false),
            sources = list,
            profile = rules.profileTitle(),
        )
    }

    private fun hotApplyRulesIfNeeded(): String {
        if (!prefs.getBoolean("proxyAdblockChain", true)) return ""
        if (!ProxyStatusBridge.rootProxyRunning(app)) return ""
        return runCatching { ProxyAdblockRuntimeBridge.hotReload(app) }.getOrElse { error ->
            "规则已保存；热更新失败：" + (error.message ?: error::class.java.simpleName) + "，下次重启自动生效"
        }
    }

    suspend fun setRuleSource(id: String, enabled: Boolean): String = withContext(Dispatchers.IO) {
        val rules = RuleStore(app)
        rules.reload()
        rules.setSource(id, enabled, false)
        hotApplyRulesIfNeeded()
    }

    suspend fun setRuleProfile(id: String): String = withContext(Dispatchers.IO) {
        val rules = RuleStore(app)
        rules.reload()
        rules.setProfile(id, false)
        hotApplyRulesIfNeeded()
    }

    suspend fun changeDomain(domain: String, allow: Boolean, add: Boolean): String = withContext(Dispatchers.IO) {
        val rules = RuleStore(app)
        rules.reload()
        rules.changeDomain(domain, allow, add, false)
        hotApplyRulesIfNeeded()
    }

    suspend fun updateRules(): String = withContext(Dispatchers.IO) {
        val rules = RuleStore(app)
        rules.reload()
        val changed = rules.updateRules(false)
        val warning = rules.summary().optString("lastRuleUpdateWarning", "")
        val hot = if (changed) hotApplyRulesIfNeeded() else ""
        val base = when {
            changed && warning.isNotBlank() -> "规则已更新；$warning"
            changed -> "规则已更新"
            warning.isNotBlank() -> "规则已校验；$warning"
            else -> "规则已校验，没有变化"
        }
        if (hot.isBlank()) base else "$base；$hot"
    }

    private fun requestTime(o: JSONObject): String {
        val millis = o.optLong("time", 0L)
        return if (millis > 0L) SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(millis))
        else o.optString("timestamp", "")
    }

    fun requestItems(): List<RequestItem> {
        val raw = prefs.getString("dnsLogs", "[]") ?: "[]"
        val array = try { JSONArray(raw) } catch (_: Exception) { JSONArray() }
        val out = ArrayList<RequestItem>()
        for (i in array.length() - 1 downTo 0) {
            val o = array.optJSONObject(i) ?: continue
            out += RequestItem(
                domain = o.optString("domain", o.optString("name", "未知域名")),
                reason = o.optString("reason", o.optString("result", "DNS 请求")),
                blocked = o.optBoolean("blocked", o.optString("result", "").startsWith("blocked")),
                time = requestTime(o),
            )
        }
        return out
    }

    fun clearRequests() {
        prefs.edit().putString("dnsLogs", "[]").remove("dnsLogError").remove("dnsLogNotice").apply()
    }

    fun requestLoggingEnabled(): Boolean = prefs.getBoolean("requestLogs", false)
    fun setRequestLogging(enabled: Boolean) = DnsVpnService.setRequestLogging(app, enabled)

    fun appearance(): String = prefs.getString("appearance", "system") ?: "system"
    fun setAppearance(value: String) { prefs.edit().putString("appearance", value).apply() }
}
