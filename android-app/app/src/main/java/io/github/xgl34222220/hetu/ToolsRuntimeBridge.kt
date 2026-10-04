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
)

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
    suspend fun switchToRuleMode(context: Context) = withContext(Dispatchers.IO) {
        val client = MihomoControllerClient(context.applicationContext)
        client.setTrafficMode("rule")
        if (client.configs().optString("mode", "").lowercase() != "rule") throw IOException("核心尚未确认规则模式，请刷新后重试")
    }

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
        val rules = if (running) quietly(emptyList()) { controller.rules() } else emptyList()
        val adRule = rules.firstOrNull {
            it.payload.contains(ProxyAdblockRules.PROVIDER_NAME, true) || it.type.contains(ProxyAdblockRules.PROVIDER_NAME, true)
        }
        val stats = if (running) quietly(AdblockRuntimeStats()) { ProxyRuntimeInspector(app).adblockRuntimeStats() } else AdblockRuntimeStats()
        val mode = if (running) quietly("") { MihomoControllerClient(app).configs().optString("mode", "") } else ""
        val cachedRecent = prefs.getString("proxyAdblockRecentDomains", "").orEmpty().lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.take(8).toList()
        ToolsAdblockChain(
            running = running,
            mode = mode,
            ruleMode = mode.isNotBlank() && AdblockRuleInspection.isRuleMode(mode),
            startupInjected = AdblockRuleInspection.isInjected(startup),
            controllerLoaded = adRule != null,
            hits = maxOf(prefs.getLong("proxyAdblockSessionHits", 0L), adRule?.hitCount ?: 0L, stats.count),
            recent = stats.recentDomains.ifEmpty { cachedRecent },
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
