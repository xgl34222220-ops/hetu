package io.github.xgl34222220.hetu

import android.content.Context
import android.net.Uri
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** What 广告过滤 needs to know about the running chain; assembled by [ToolsRuntimeBridge.adblockChain]. */
internal class ToolsAdblockChain(
    val running: Boolean,
    /** Raw Mihomo mode (`rule`, `global`, `direct`); blank when the core is not running or did not answer. */
    val mode: String,
    val ruleMode: Boolean,
    val startupInjected: Boolean,
    val controllerLoaded: Boolean,
    val hits: Long,
    val recent: List<String>,
    /** Connections the REJECT rule was checked against without matching (controller missCount). */
    val missCount: Long = 0L,
    /** True when [hits] is the controller's in-memory hitCount (no log level, no su). */
    val hitsFromController: Boolean = false,
    /** Strict controller reading of the chain; null when the core is not running or silent. */
    val report: AdblockChainReport? = null,
    /** The running copy answers the DNS block set empty from nameserver-policy. */
    val dnsLayer: Boolean = false,
    /** dns.enhanced-mode of the running copy (fake-ip / redir-host; blank without a dns block). */
    val enhancedMode: String = "",
    /** none / enabled / disabled / pure-ip-off / inline: the running copy's sniffer. */
    val snifferState: String = "none",
    /** System Private DNS: off / opportunistic / hostname, and the hostname when set. */
    val privateDnsMode: String = "",
    val privateDnsHost: String = "",
    /** What the running session reports (status JSON, cached by the controller read). */
    val systemDns: String = "",
    val dnsHijackMode: String = "",
    val dotGuard: Boolean = false,
    val dnsIpv6Rule: Boolean = false,
    val ipv6DnsPolicy: String = "",
    val cnip: String = "",
    val cnipTcpOnly: Boolean = false,
    /** 6.0.0: the session bypasses only UDP to CN addresses (the default 国内 UDP 内核直连). */
    val cnipUdpOnly: Boolean = false,
    /** The selected subscription has its own dns: block. */
    val sourceHasDns: Boolean = true,
    val recommendedDns: Boolean = false,
    /** blacklist / whitelist / core and the selected package count of that scope. */
    val appScope: String = "core",
    val scopedApps: Int = 0,
) {
    /** Apps whose traffic never reaches the core (blacklist entries); -1: all but the whitelist. */
    val appsOutside: Int get() = when (appScope) { "blacklist" -> scopedApps; "whitelist" -> -1; else -> 0 }
}

/**
 * Root-package access for pages 28–49 of the 工具 module. Like `ToolsConfigBridge`, it exists
 * because `ProxyRuntimeProfile`, `ProxyRuntimeSettings`, `RootProxyManager`, `MihomoControllerClient`,
 * `AdblockRuleInspection` and `ProxyAdblockRules` are package-private Java classes that
 * `hetu.tools` cannot name. It only forwards to them; no existing file is modified.
 */
internal object ToolsRuntimeBridge {
    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences("hetu", Context.MODE_PRIVATE)

    private fun core(id: String): ProxyRuntimeProfile.Core =
        ProxyRuntimeProfile.Core.values().firstOrNull { it.id == id } ?: throw IOException("未知核心：$id")

    /** Flags a runtime setting as changed so the home screen offers “重启后生效”. */
    fun markDirty(context: Context, key: String) = ProxyRuntimeSettings.markDirty(prefs(context), key)

    /** Label of the core selected in 基础代理配置. */
    fun selectedCoreLabel(context: Context): String = ProxyRuntimeProfile.load(prefs(context)).core.label

    /** Scope switching preserves the list last edited through either the new or existing UI. */
    fun setAppScope(context: Context, next: String) {
        require(next in setOf("blacklist", "whitelist", "core"))
        val prefs = prefs(context)
        val previous = prefs.getString("proxyAppScope", "blacklist").orEmpty()
        fun listKey(scope: String) = when (scope) {
            "blacklist" -> "proxyAppBlacklist"
            "whitelist" -> "proxyAppWhitelist"
            else -> null
        }
        val active = prefs.getStringSet("proxyAppPackages", emptySet()).orEmpty().toSet()
        val editor = prefs.edit()
        listKey(previous)?.let { editor.putStringSet(it, active) }
        editor.putString("proxyAppScope", next)
        listKey(next)?.let { key ->
            editor.putStringSet("proxyAppPackages", if (next == previous) active else prefs.getStringSet(key, emptySet()).orEmpty().toSet())
        }
        editor.apply()
    }

    /** Traffic rule/global/direct is the controller setting, separate from TPROXY/TUN mode. */
    suspend fun switchToRuleMode(context: Context) =
        ProxyDashboardRepository(context.applicationContext).setRuleModeAndConfirm()

    /* ------------------------------ 核心管理 ------------------------------ */

    suspend fun downloadCore(context: Context, id: String, onProgress: (String) -> Unit) {
        ProxyCoreDownloadManager(context.applicationContext).downloadOrUpdate(core(id), onProgress)
    }

    suspend fun removeCore(context: Context, id: String) {
        ProxyCoreDownloadManager(context.applicationContext).removeDownloaded(core(id))
    }

    suspend fun importCore(context: Context, id: String, uri: Uri, displayName: String) {
        ProxyCoreDownloadManager(context.applicationContext).importFromUri(core(id), uri, displayName)
    }

    /* ------------------------------ 诊断与维护 ------------------------------ */

    /** Same two calls as 高级代理配置 › 运行预检. Returns pass / fail and the message to show on failure. */
    suspend fun preflight(context: Context): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val root = RootProxyManager(context.applicationContext)
        val json = root.preflight(root.prepare(ProxyRuntimeProfile.load(prefs(context))))
        json.optBoolean("ok", false) to json.optString("message", "预检未通过")
    }

    /* ------------------------------ 广告过滤 ------------------------------ */

    /** The hostname of a Private DNS set to "hostname" mode; null otherwise. Never changes the setting. */
    fun privateDnsHostname(context: Context): String? = try {
        val resolver = context.applicationContext.contentResolver
        if (android.provider.Settings.Global.getString(resolver, "private_dns_mode") == "hostname")
            android.provider.Settings.Global.getString(resolver, "private_dns_specifier")?.trim()?.ifBlank { null } ?: "未知主机名"
        else null
    } catch (_: Exception) { null }

    private fun privateDnsMode(context: Context): String = try {
        android.provider.Settings.Global.getString(context.applicationContext.contentResolver, "private_dns_mode").orEmpty()
    } catch (_: Exception) { "" }

    /**
     * The checks `ProxyAdblockChainActivity` runs for its 运行链验证 card, in the same order and
     * from the same sources: startup copy, live rules, runtime log, session counters.
     */
    suspend fun adblockChain(context: Context): ToolsAdblockChain = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val prefs = prefs(app)
        val controller = ProxyComposeController(app)
        val running = quietly(false) { controller.state().running }
        val startup = quietly("") { RootProxyManager(app).startupConfig() }
        val rules = if (running) quietly<List<ProxyRuleUi>?>(null) { controller.rules() } else null
        val adRule = rules?.firstOrNull {
            it.payload.contains(ProxyAdblockRules.PROVIDER_NAME, true) || it.type.contains(ProxyAdblockRules.PROVIDER_NAME, true)
        }
        val mode = if (running) quietly("") { MihomoControllerClient(app).configs().optString("mode", "") } else ""
        val providers = if (running && rules != null) quietly<List<ProxyRuleProviderUi>?>(null) { controller.ruleProviders() } else null
        val report = if (rules != null && providers != null) AdblockVerification.inspect(mode, rules, providers) else null
        val counted = report?.takeIf { it.loaded }
        // Recent domains only: the counter itself comes from the controller below.
        val stats = if (running) quietly(AdblockRuntimeStats()) { ProxyRuntimeInspector(app).adblockRuntimeStats() } else AdblockRuntimeStats()
        val cachedRecent = prefs.getString("proxyAdblockRecentDomains", "").orEmpty().lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.take(8).toList()
        val profile = ProxyRuntimeProfile.load(prefs)
        val source = quietly<String?>(null) { ProxyConfigLibrary(app).let { lib -> lib.source(profile.core)?.let { lib.read(it) } } }
        ToolsAdblockChain(
            running = running,
            mode = mode,
            ruleMode = mode.isNotBlank() && AdblockRuleInspection.isRuleMode(mode),
            startupInjected = AdblockRuleInspection.isInjected(startup),
            controllerLoaded = adRule != null,
            // Controller hitCount is authoritative while the core answers (in-memory RuleWrapper
            // counter, independent of the log level); the log-derived session count is the fallback.
            hits = counted?.hitCount ?: maxOf(prefs.getLong("proxyAdblockSessionHits", 0L), adRule?.hitCount ?: 0L, stats.count),
            recent = stats.recentDomains.ifEmpty { cachedRecent },
            missCount = counted?.missCount ?: 0L,
            hitsFromController = counted != null,
            report = report,
            dnsLayer = AdblockDnsPolicy.isInjected(startup),
            enhancedMode = AdblockDnsPolicy.enhancedMode(startup),
            snifferState = AdblockDnsPolicy.snifferState(startup),
            privateDnsMode = privateDnsMode(app),
            privateDnsHost = privateDnsHostname(app).orEmpty(),
            systemDns = prefs.getString("proxyRootSystemDns", "").orEmpty(),
            dnsHijackMode = prefs.getString("proxyRootDnsMode", "").orEmpty(),
            dotGuard = prefs.getBoolean("proxyRootDotGuard", false),
            dnsIpv6Rule = prefs.getBoolean("proxyRootDnsIpv6Rule", false),
            ipv6DnsPolicy = prefs.getString("proxyRootIpv6DnsPolicy", "").orEmpty(),
            cnip = if (running) prefs.getString("proxyCnIpKernelStatus", "").orEmpty() else "",
            cnipTcpOnly = prefs.getBoolean("proxyCnIpKernelTcpOnlyActive", false),
            cnipUdpOnly = prefs.getBoolean("proxyCnIpKernelUdpOnlyActive", false),
            sourceHasDns = source == null || MihomoStartupConfig.sourceHasDns(source),
            recommendedDns = prefs.getBoolean("proxyRecommendedDns", false),
            appScope = profile.appScope.id,
            scopedApps = prefs.getStringSet("proxyAppPackages", emptySet()).orEmpty().size,
        )
    }

    /** [block], or [fallback] when it throws; cancellation always propagates. */
    private inline fun <T> quietly(fallback: T, block: () -> T): T = try {
        block()
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (error: Exception) {
        fallback
    }
}
