package io.github.xgl34222220.hetu.ui

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.net.VpnService
import io.github.xgl34222220.hetu.DnsVpnService
import io.github.xgl34222220.hetu.MihomoVpnService
import io.github.xgl34222220.hetu.ProxyStatusBridge
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
    val icon: Bitmap? = null,
)

internal data class RuleSourceItem(
    val id: String,
    val name: String,
    val url: String,
    val enabled: Boolean,
    val count: Int,
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
        val status = RootBridge.status(app)
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
            rootGranted = status.optBoolean("rootGranted"),
            installed = status.optBoolean("installed"),
            moduleEnabled = status.optBoolean("enabled") && !status.optBoolean("moduleDisabled") && !status.optBoolean("moduleRemovalPending"),
            pendingReboot = status.optBoolean("pendingReboot"),
            version = status.optString("version", "—"),
            ruleCount = if (status.optInt("ruleCount") > 0) status.optInt("ruleCount") else ruleCount,
            allowCount = allowCount,
            blockCount = blockCount,
            blockedQueries = DnsVpnService.currentBlocked(app),
            queries = DnsVpnService.currentQueries(app),
            errors = DnsVpnService.currentErrors(app),
            vpnRunning = DnsVpnService.running,
            vpnWanted = prefs.getBoolean("vpnWanted", false),
            proxyRunning = rootProxyRunning || MihomoVpnService.engaged || prefs.getBoolean("proxyWanted", false),
            message = prefs.getString("vpnError", "")?.takeIf {
                preferredVpnMode() && !DnsVpnService.running && it.isNotBlank()
            } ?: status.optString("error", status.optString("message", "")),
        )
    }

    fun protectionMode(): String = prefs.getString("preferredMode", "module") ?: "module"
    fun preferredVpnMode(): Boolean = protectionMode() == "vpn"

    fun setProtectionMode(value: String) {
        if (value != "module" && value != "vpn") return
        prefs.edit().putString("preferredMode", value).apply()
        if (value == "module" && DnsVpnService.running) stopVpn()
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

    private suspend fun moduleInstalled(): Boolean = withContext(Dispatchers.IO) {
        val s = RootBridge.status(app)
        s.optBoolean("installed") && !s.optBoolean("pendingReboot")
    }

    suspend fun setRuleSource(id: String, enabled: Boolean) = withContext(Dispatchers.IO) {
        val rules = RuleStore(app)
        rules.reload()
        rules.setSource(id, enabled, moduleInstalled())
    }

    suspend fun setRuleProfile(id: String) = withContext(Dispatchers.IO) {
        val rules = RuleStore(app)
        rules.reload()
        rules.setProfile(id, moduleInstalled())
    }

    suspend fun changeDomain(domain: String, allow: Boolean, add: Boolean) = withContext(Dispatchers.IO) {
        val rules = RuleStore(app)
        rules.reload()
        rules.changeDomain(domain, allow, add, moduleInstalled())
    }

    suspend fun updateRules(): String = withContext(Dispatchers.IO) {
        val rules = RuleStore(app)
        rules.reload()
        if (rules.updateRules(moduleInstalled())) "规则已更新" else "规则已校验，没有变化"
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
