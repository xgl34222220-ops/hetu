package io.github.xgl34222220.hetu

import android.content.Context
import android.os.SystemClock
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * What the running core actually has for 广告过滤, read from the controller rather than from the
 * flags the start path writes. [blockingIndex] is the strict REJECT rule (both providers, not
 * disabled), not any rule that merely mentions hetu-adblock.
 */
internal data class AdblockChainReport(
    val mode: String,
    val blockingIndex: Int,
    /** First rule before which the ad rule must sit: anything routed to a proxy group / node. */
    val firstRoutingIndex: Int,
    val matchIndex: Int,
    val blockRules: Int,
    val allowRules: Int,
    /** Remote rule sets that loaded zero rules (unreachable / failed download). */
    val emptyRemoteProviders: List<String>,
) {
    val loaded: Boolean get() = blockingIndex >= 0
    val ordered: Boolean get() = loaded && (firstRoutingIndex < 0 || blockingIndex < firstRoutingIndex) && (matchIndex < 0 || blockingIndex < matchIndex)
    val providersLoaded: Boolean get() = blockRules > 0 && allowRules >= 0
    val ruleMode: Boolean get() = AdblockRuleInspection.isRuleMode(mode)
    val effective: Boolean get() = loaded && ordered && providersLoaded && ruleMode

    /** One line for the 运行链验证 row. */
    fun detail(): String = when {
        !loaded -> "核心未加载河图广告 REJECT 规则"
        !ordered -> "广告规则排在第 ${blockingIndex + 1} 条，位于代理分流之后，不会先行拦截"
        blockRules <= 0 -> "广告规则已加载，但 hetu-adblock 规则集为空"
        !ruleMode -> "规则链已加载；当前为${if (mode.equals("global", true)) "全局" else "直连"}模式，不经过规则"
        else -> "第 ${blockingIndex + 1} 条 REJECT，先于分流与兜底 · ${String.format(Locale.US, "%,d", blockRules)} 条"
    } + if (emptyRemoteProviders.isEmpty()) "" else "；${emptyRemoteProviders.size} 个远程规则集为空：${emptyRemoteProviders.take(3).joinToString("、")}"
}

/** Result of one 实测拦截 request. [ok] is null when nothing could be concluded. */
internal data class AdblockProbe(val ok: Boolean?, val domain: String, val detail: String)

internal object AdblockVerification {
    /** Well-known ad/tracker hosts; the first one the active block list covers is used for 实测. */
    val candidates = listOf(
        "doubleclick.net", "googleadservices.com", "googlesyndication.com", "pagead2.googlesyndication.com",
        "adservice.google.com", "app-measurement.com", "ads.tiktok.com", "pos.baidu.com", "cpro.baidu.com",
        "mi.gdt.qq.com", "adsmind.gdtimg.com", "ads.yahoo.com",
    )

    private val terminalPolicies = setOf("DIRECT", "REJECT", "REJECT-DROP", "PASS", "COMPATIBLE")
    private val hostPattern = Regex("^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)+$")

    /** Strict reading of the controller's /rules, /providers/rules and /configs answers. */
    fun inspect(mode: String, rules: List<ProxyRuleUi>, providers: List<ProxyRuleProviderUi>): AdblockChainReport {
        var blocking = -1
        var routing = -1
        var match = -1
        rules.forEachIndexed { position, rule ->
            if (blocking < 0 && AdblockRuleInspection.isBlockingRule(rule.type, rule.payload, rule.proxy, rule.disabled)) blocking = position
            val policy = rule.proxy.trim().uppercase(Locale.ROOT)
            if (routing < 0 && !rule.disabled && policy.isNotEmpty() && policy !in terminalPolicies && !policy.startsWith("REJECT")) routing = position
            if (match < 0 && rule.type.equals("Match", true)) match = position
        }
        val block = providers.firstOrNull { it.name == ProxyAdblockRules.PROVIDER_NAME }?.ruleCount ?: -1
        val allow = providers.firstOrNull { it.name == ProxyAdblockRules.ALLOW_PROVIDER_NAME }?.ruleCount ?: -1
        val empty = providers.filter {
            it.name != ProxyAdblockRules.PROVIDER_NAME && it.name != ProxyAdblockRules.ALLOW_PROVIDER_NAME &&
                it.vehicleType.equals("HTTP", true) && it.ruleCount <= 0
        }.map { it.name }
        return AdblockChainReport(mode.trim().lowercase(Locale.ROOT), blocking, routing, match, block, allow, empty)
    }

    /** Reads the live chain; null when the core is not running or did not answer. */
    suspend fun chain(context: Context): AdblockChainReport? = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        if (!ProxyStatusBridge.rootProxyRunning(app)) return@withContext null
        try {
            val controller = ProxyComposeController(app)
            val mode = MihomoControllerClient(app).configs().optString("mode", "")
            inspect(mode, controller.rules(), controller.ruleProviders())
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            null
        }
    }

    /** True when [host] or one of its parent domains is in [set] (Mihomo `+.` suffix semantics). */
    fun covered(host: String, set: Set<String>): Boolean {
        var current = host
        while (true) {
            if (current in set) return true
            val dot = current.indexOf('.')
            if (dot < 0 || dot == current.lastIndex) return false
            current = current.substring(dot + 1)
        }
    }

    /** A host the block list covers and the allow list does not; well-known ad hosts first. */
    fun pickProbeDomain(block: Set<String>, allow: Set<String>): String? {
        candidates.firstOrNull { covered(it, block) && !covered(it, allow) }?.let { return it }
        var scanned = 0
        for (domain in block) {
            if (++scanned > 5_000) break
            val host = domain.trim().lowercase(Locale.ROOT)
            if (hostPattern.matches(host) && !covered(host, allow)) return host
        }
        return null
    }

    /** Verdict of the newest core log line for [domain]; null when the line is not there yet. */
    fun parseProbeLog(output: String, domain: String): AdblockProbe? {
        val line = output.lineSequence().map { it.trim() }.lastOrNull { it.contains("--> $domain:") } ?: return null
        val match = Regex("match (.+?) using (.+?)\"?$").find(line)
        val rule = match?.groupValues?.get(1)?.trim().orEmpty()
        val policy = match?.groupValues?.get(2)?.trim().orEmpty()
        return when {
            line.contains(ProxyAdblockRules.PROVIDER_NAME) && policy.uppercase(Locale.ROOT).startsWith("REJECT") ->
                AdblockProbe(true, domain, "$domain → REJECT，命中河图广告规则")
            policy.uppercase(Locale.ROOT).startsWith("REJECT") ->
                AdblockProbe(true, domain, "$domain → REJECT，命中配置自带规则 $rule")
            match != null -> AdblockProbe(false, domain, "$domain 未被拦截：命中 $rule → $policy")
            else -> null
        }
    }

    /**
     * Sends one plain-HTTP request for a blocked host through the running core's loopback policy
     * listener (Hetu's own UID is exempt from transparent interception) and reads the core's own
     * decision for it from core.log. It counts once in 本次拦截, like any real blocked request.
     */
    suspend fun probe(context: Context): AdblockProbe = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences("hetu", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("proxyAdblockChain", true)) return@withContext AdblockProbe(null, "", "广告过滤未开启")
        if (!ProxyStatusBridge.rootProxyRunning(app)) return@withContext AdblockProbe(null, "", "代理启动后可实测")
        var rules = RuleStore.currentExportRules()
        if (rules.domains.isEmpty()) {
            RuleStore(app).reload()
            rules = RuleStore.currentExportRules()
        }
        val domain = pickProbeDomain(rules.domains, rules.allowDomains)
            ?: return@withContext AdblockProbe(false, "", "本地规则库为空，没有可实测的广告域名")
        val log = "/data/adb/hetu/run/core.log"
        val pattern = RootBridge.quote("--> $domain:")
        fun count(): Int? {
            val result = RootBridge.rootShell(app, "grep -cF -- $pattern $log 2>/dev/null || true", 5_000L)
            return result.output.trim().lineSequence().lastOrNull()?.trim()?.toIntOrNull()
        }
        val before = count() ?: return@withContext AdblockProbe(null, domain, "无法读取核心日志，不能确认拦截结果")
        val port = MihomoStartupConfig.egressProbePort(prefs.getInt("proxyControllerPort", MihomoStartupConfig.CONTROLLER_PORT))
        val started = SystemClock.elapsedRealtime()
        try {
            val connection = (URL("http://$domain/").openConnection(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", port))) as HttpURLConnection).apply {
                instanceFollowRedirects = false
                useCaches = false
                connectTimeout = 3_000
                readTimeout = 3_000
                setRequestProperty("Connection", "close")
                setRequestProperty("User-Agent", "Hetu-Android")
            }
            try { connection.responseCode } finally { connection.disconnect() }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            // A rejected request usually fails here; the core log below is the evidence either way.
        }
        val elapsed = SystemClock.elapsedRealtime() - started
        for (attempt in 0 until 5) {
            if (attempt > 0) delay(300L)
            val after = count() ?: break
            if (after > before) {
                val tail = RootBridge.rootShell(app, "grep -F -- $pattern $log 2>/dev/null | tail -n 1", 5_000L).output
                val verdict = parseProbeLog(tail, domain)
                if (verdict != null) return@withContext if (verdict.ok == true) verdict.copy(detail = verdict.detail + " · ${elapsed} ms") else verdict
            }
        }
        AdblockProbe(null, domain, "核心日志里没有这次请求（代理未接收或日志级别过低），无法确认")
    }
}
