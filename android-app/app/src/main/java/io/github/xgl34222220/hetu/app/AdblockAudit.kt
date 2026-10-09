package io.github.xgl34222220.hetu

import android.content.Context
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URL
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject

/*
 * 去广告运行链核查.
 *
 * Hetu's ad blocking is not DNS-level inside the Root proxy: the private Mihomo runtime gets two
 * local `type: file` domain rule-sets (`hetu-adblock`, `hetu-adblock-allow`) and one rule
 * `AND((RULE-SET,hetu-adblock),(NOT,((RULE-SET,hetu-adblock-allow)))),REJECT`. A connection is
 * rejected only when (1) the runtime copy carries the rule, (2) the core loaded it with REJECT and
 * before MATCH, (3) the block rule-set has entries, (4) the core is in rule mode and (5) the
 * connection reaches the core with a hostname (fake-ip / DNS mapping / sniffer). This file checks
 * each link from what the core itself reports, and can probe known ad domains through the core.
 */

internal enum class AdCheckState { Pass, Warn, Fail, Pending }

internal data class AdCheck(val key: String, val title: String, val state: AdCheckState, val detail: String)

internal data class AdRuleRow(val type: String, val payload: String, val proxy: String, val disabled: Boolean, val hitCount: Long?)

internal data class AdProviderInfo(val name: String, val ruleCount: Int, val vehicle: String)

internal enum class AdVerdict { Effective, NotEffective, Waiting, Off, Unknown }

internal data class AdblockAuditInput(
    val enabled: Boolean,
    val running: Boolean,
    /** Controller `mode`; null when the controller did not answer. */
    val mode: String?,
    /** Private startup copy; null when it could not be read. */
    val startupYaml: String?,
    /** Controller `/rules`; null when it did not answer. */
    val rules: List<AdRuleRow>?,
    /** Controller `/providers/rules`; null when it did not answer. */
    val providers: Map<String, AdProviderInfo>?,
    val localRuleCount: Int,
    val lastError: String = "",
)

internal data class AdblockAudit(
    val verdict: AdVerdict,
    val headline: String,
    val checks: List<AdCheck>,
    /** 0-based index of the blocking rule in the core's list, when found. */
    val ruleIndex: Int? = null,
    val ruleHits: Long? = null,
    val coreRuleCount: Int? = null,
    /** The controller answered both rules and providers. */
    val coreAnswered: Boolean = false,
    /** Rule present (REJECT, enabled, before MATCH) and the block rule-set has entries. */
    val coreChainOk: Boolean = false,
    /** Remote rule-sets (HTTP) the core reports with 0 entries, i.e. never downloaded. */
    val emptyRemoteProviders: List<String> = emptyList(),
)

internal enum class SnifferState { Enabled, Disabled, Absent }

internal object AdblockAuditor {
    const val BLOCK = "hetu-adblock"
    const val ALLOW = "hetu-adblock-allow"

    fun parseRules(root: JSONObject?): List<AdRuleRow>? {
        val array = root?.optJSONArray("rules") ?: return null
        return (0 until array.length()).mapNotNull { i ->
            val rule = array.optJSONObject(i) ?: return@mapNotNull null
            val extra = rule.optJSONObject("extra")
            AdRuleRow(
                type = rule.optString("type"),
                payload = rule.optString("payload"),
                proxy = rule.optString("proxy"),
                disabled = rule.optBoolean("disabled", false) || (extra?.optBoolean("disabled", false) ?: false),
                hitCount = extra?.takeIf { it.has("hitCount") }?.optLong("hitCount"),
            )
        }
    }

    fun parseProviders(root: JSONObject?): Map<String, AdProviderInfo>? {
        val providers = root?.optJSONObject("providers") ?: return null
        val out = LinkedHashMap<String, AdProviderInfo>()
        val names = providers.keys()
        while (names.hasNext()) {
            val name = names.next()
            val p = providers.optJSONObject(name) ?: continue
            out[name] = AdProviderInfo(name, p.optInt("ruleCount", p.optInt("count", -1)), p.optString("vehicleType", p.optString("vehicle", "")))
        }
        return out
    }

    /** Top-level `sniffer:` block of a YAML text: enabled, explicitly disabled, or absent. */
    fun sniffer(yaml: String?): SnifferState {
        val block = topLevelBlock(yaml, "sniffer") ?: return SnifferState.Absent
        val enable = block.firstNotNullOfOrNull { Regex("^\\s+enable\\s*:\\s*([A-Za-z]+)").find(it)?.groupValues?.get(1) }
        return if (enable != null && enable.lowercase(Locale.ROOT) == "false") SnifferState.Disabled else SnifferState.Enabled
    }

    /** `dns.enhanced-mode` of a YAML text, lower-case; blank when not set. */
    fun enhancedMode(yaml: String?): String {
        val block = topLevelBlock(yaml, "dns") ?: return ""
        return block.firstNotNullOfOrNull { Regex("^\\s+enhanced-mode\\s*:\\s*['\"]?([A-Za-z-]+)").find(it)?.groupValues?.get(1) }
            ?.lowercase(Locale.ROOT).orEmpty()
    }

    private fun topLevelBlock(yaml: String?, key: String): List<String>? {
        if (yaml.isNullOrEmpty()) return null
        val lines = yaml.split('\n')
        val start = lines.indexOfFirst { it.startsWith("$key:") || it.startsWith("$key :") }
        if (start < 0) return null
        val out = ArrayList<String>()
        for (i in start + 1 until lines.size) {
            val line = lines[i]
            if (line.isBlank() || line.trimStart().startsWith("#")) continue
            if (!line.first().isWhitespace()) break
            out += line
        }
        return out
    }

    private fun isProviderRef(rule: AdRuleRow): Boolean =
        rule.payload.contains(BLOCK, true) || rule.type.contains(BLOCK, true)

    private val terminalPolicies = setOf("DIRECT", "REJECT", "REJECT-DROP", "PASS", "COMPATIBLE")

    fun audit(input: AdblockAuditInput): AdblockAudit {
        val checks = ArrayList<AdCheck>()
        if (!input.enabled) {
            checks += AdCheck("switch", "广告过滤开关", AdCheckState.Fail, "已关闭：运行副本不注入广告规则")
            return AdblockAudit(AdVerdict.Off, "广告过滤已关闭", checks)
        }
        checks += AdCheck("switch", "广告过滤开关", AdCheckState.Pass, "已开启")
        checks += if (input.localRuleCount > 0) AdCheck("local", "本地规则库", AdCheckState.Pass, "%,d 条有效拦截域名（本地文件，无需下载即可生效）".format(input.localRuleCount))
            else AdCheck("local", "本地规则库", AdCheckState.Fail, "没有启用的拦截规则")

        val injected = input.startupYaml?.let { AdblockRuleInspection.isInjected(it) }
        checks += when {
            injected == true -> AdCheck("startup", "运行副本注入", AdCheckState.Pass, "已写入 hetu-adblock 规则集与 REJECT 规则")
            input.lastError.isNotBlank() -> AdCheck("startup", "运行副本注入", AdCheckState.Fail, "本次启动已降级为不带广告规则：${input.lastError}")
            injected == false -> AdCheck("startup", "运行副本注入", AdCheckState.Fail, "运行副本没有广告规则，重启代理后生效")
            else -> AdCheck("startup", "运行副本注入", AdCheckState.Pending, "尚未生成运行副本")
        }

        if (!input.running) {
            checks += AdCheck("mode", "规则模式", AdCheckState.Pending, "代理启动后检测")
            checks += AdCheck("core", "核心规则链", AdCheckState.Pending, "代理启动后检测")
            checks += AdCheck("provider", "核心规则集", AdCheckState.Pending, "代理启动后检测")
            return AdblockAudit(AdVerdict.Waiting, "代理未运行，启动后才会拦截", checks)
        }

        val mode = input.mode?.trim()?.lowercase(Locale.ROOT)
        checks += when {
            mode == null || mode.isEmpty() -> AdCheck("mode", "规则模式", AdCheckState.Warn, "控制接口未返回当前模式")
            AdblockRuleInspection.isRuleMode(mode) -> AdCheck("mode", "规则模式", AdCheckState.Pass, "rule：规则会逐条执行")
            else -> AdCheck("mode", "规则模式", AdCheckState.Fail, "当前为 ${if (mode == "global") "全局" else if (mode == "direct") "直连" else mode} 模式，规则（含广告规则）不会执行")
        }

        var ruleIndex: Int? = null
        var ruleHits: Long? = null
        var ruleOk = false
        val rules = input.rules
        if (rules == null) {
            checks += AdCheck("core", "核心规则链", AdCheckState.Warn, "控制接口未返回规则列表")
        } else {
            val index = rules.indexOfFirst { AdblockRuleInspection.isBlockingRule(it.type, it.payload, it.proxy, it.disabled) }
            val match = rules.indexOfFirst { it.type.equals("Match", true) }
            if (index < 0) {
                val loose = rules.firstOrNull { isProviderRef(it) }
                checks += AdCheck(
                    "core", "核心规则链", AdCheckState.Fail,
                    when {
                        loose == null -> "核心没有加载河图广告规则"
                        loose.disabled -> "核心中的广告规则已被禁用"
                        !loose.proxy.uppercase(Locale.ROOT).startsWith("REJECT") -> "广告规则的策略是 ${loose.proxy}，不是 REJECT"
                        else -> "核心中的广告规则与河图注入的形式不一致"
                    },
                )
            } else if (match in 0 until index) {
                ruleIndex = index
                checks += AdCheck("core", "核心规则链", AdCheckState.Fail, "广告规则排在第 ${index + 1} 条，位于兜底 MATCH（第 ${match + 1} 条）之后，永远不会命中")
            } else {
                ruleIndex = index
                ruleOk = true
                ruleHits = rules[index].hitCount
                val earlier = rules.subList(0, index).count { it.proxy.uppercase(Locale.ROOT) !in terminalPolicies && !it.disabled }
                checks += if (earlier > 0)
                    AdCheck("core", "核心规则链", AdCheckState.Warn, "第 ${index + 1} 条 REJECT；前面有 $earlier 条分流规则会先命中，命中它们的域名不经广告规则")
                else AdCheck("core", "核心规则链", AdCheckState.Pass, "第 ${index + 1} 条 REJECT，先于分流与兜底规则")
            }
        }

        var coreCount: Int? = null
        var providerOk = false
        val providers = input.providers
        var emptyRemote = emptyList<String>()
        if (providers == null) {
            checks += AdCheck("provider", "核心规则集", AdCheckState.Warn, "控制接口未返回规则集")
        } else {
            val block = providers[BLOCK]
            val allow = providers[ALLOW]
            coreCount = block?.ruleCount
            checks += when {
                block == null -> AdCheck("provider", "核心规则集", AdCheckState.Fail, "核心没有 hetu-adblock 规则集")
                block.ruleCount <= 0 -> AdCheck("provider", "核心规则集", AdCheckState.Fail, "hetu-adblock 为 0 条：规则文件未写入运行目录或格式无效")
                allow == null -> AdCheck("provider", "核心规则集", AdCheckState.Warn, "已载入 %,d 条；白名单规则集缺失".format(block.ruleCount))
                input.localRuleCount > 0 && block.ruleCount != input.localRuleCount ->
                    AdCheck("provider", "核心规则集", AdCheckState.Warn, "核心 %,d 条，本地 %,d 条：规则更新后尚未热重载".format(block.ruleCount, input.localRuleCount))
                else -> AdCheck("provider", "核心规则集", AdCheckState.Pass, "核心已载入 %,d 条（白名单 %,d 条）".format(block.ruleCount, allow.ruleCount.coerceAtLeast(0)))
            }
            providerOk = block != null && block.ruleCount > 0
            emptyRemote = providers.values.filter { it.name != BLOCK && it.name != ALLOW && it.vehicle.equals("HTTP", true) && it.ruleCount == 0 }.map { it.name }
            if (emptyRemote.isNotEmpty()) {
                checks += AdCheck("remote", "其他远程规则集", AdCheckState.Warn, "未下载成功（0 条）：" + emptyRemote.take(6).joinToString("、") + if (emptyRemote.size > 6) " 等 ${emptyRemote.size} 个" else "")
            }
        }

        val sniffer = sniffer(input.startupYaml)
        val enhanced = enhancedMode(input.startupYaml)
        val dnsNote = when (enhanced) {
            "fake-ip" -> "fake-ip：连接带域名进入规则"
            "redir-host" -> "redir-host：靠 DNS 映射还原域名"
            else -> "DNS 未指定增强模式"
        }
        checks += when (sniffer) {
            SnifferState.Disabled -> AdCheck("sniffer", "域名识别", AdCheckState.Warn, "源配置关闭了嗅探：自带 DoH 或直连 IP 的广告请求无法按域名拦截；$dnsNote")
            SnifferState.Enabled -> AdCheck("sniffer", "域名识别", AdCheckState.Pass, "TLS/HTTP/QUIC 嗅探已开启；$dnsNote")
            SnifferState.Absent -> AdCheck("sniffer", "域名识别", AdCheckState.Warn, "没有嗅探配置：只能识别经核心 DNS 解析的域名；$dnsNote")
        }

        val answered = rules != null && providers != null
        val chainOk = ruleOk && providerOk
        val anyFail = checks.any { it.state == AdCheckState.Fail }
        val verdict = when {
            anyFail -> AdVerdict.NotEffective
            !answered || mode.isNullOrEmpty() -> AdVerdict.Unknown
            chainOk -> AdVerdict.Effective
            else -> AdVerdict.Unknown
        }
        val headline = when (verdict) {
            AdVerdict.Effective -> "运行链完整：广告域名会被核心 REJECT"
            AdVerdict.NotEffective -> checks.first { it.state == AdCheckState.Fail }.let { "未生效：${it.title} · ${it.detail}" }
            AdVerdict.Unknown -> "无法确认：控制接口未完整应答"
            else -> ""
        }
        return AdblockAudit(verdict, headline, checks, ruleIndex, ruleHits, coreCount, answered, chainOk, emptyRemote)
    }
}

/* ------------------------------ 拦截实测 ------------------------------ */

internal enum class AdProbeVerdict { Blocked, BlockedByOther, Allowed, Unknown }

internal data class AdProbeResult(val domain: String, val expectBlocked: Boolean, val verdict: AdProbeVerdict, val detail: String)

internal data class AdLogDecision(val rule: String, val policy: String)

internal object AdblockProbe {
    /** Well-known ad / tracking hosts; only those the active block list covers are probed. */
    val candidates = listOf(
        "doubleclick.net", "googleadservices.com", "googlesyndication.com", "adservice.google.com",
        "app-measurement.com", "pos.baidu.com", "cpro.baidu.com", "mobads.baidu.com",
        "mi.gdt.qq.com", "pgdt.ugdtimg.com", "ads.tiktok.com", "ad.xiaomi.com",
    )
    /** A control that must pass: proves the probe path itself works. */
    const val CONTROL = "connectivitycheck.gstatic.com"
    const val PORT = 80
    private const val LOG = "/data/adb/hetu/run/core.log"

    /** `+.suffix` semantics: the host or any parent domain is listed. */
    fun covered(host: String, domains: Set<String>): Boolean {
        var current = host.trim().trimEnd('.').lowercase(Locale.ROOT)
        while (current.isNotEmpty()) {
            if (current in domains) return true
            val dot = current.indexOf('.')
            if (dot < 0) return false
            current = current.substring(dot + 1)
        }
        return false
    }

    fun pick(block: Set<String>, allow: Set<String>, limit: Int = 4): List<String> =
        candidates.filter { covered(it, block) && !covered(it, allow) }.take(limit)

    /** The core's own verdict for `host:port` in a slice of its info log, newest first. */
    fun findDecision(log: String, host: String, port: Int = PORT): AdLogDecision? {
        val needle = "--> $host:$port"
        for (line in log.lineSequence().toList().asReversed()) {
            val at = line.indexOf(needle)
            if (at < 0) continue
            val after = line.substring(at + needle.length)
            if (after.isNotEmpty() && !after.first().isWhitespace()) continue
            val usingAt = after.lastIndexOf(" using ")
            if (usingAt < 0) continue
            val policy = after.substring(usingAt + 7).trim().trimEnd('"', '\'').trim()
            if (policy.isEmpty()) continue
            val matchAt = after.indexOf(" match ")
            val rule = if (after.contains("doesn't match") || matchAt < 0 || matchAt > usingAt) "" else after.substring(matchAt + 7, usingAt).trim()
            return AdLogDecision(rule, policy)
        }
        return null
    }

    fun classify(domain: String, expectBlocked: Boolean, logged: AdLogDecision?, httpCode: Int?, error: String?): AdProbeResult {
        val verdict: AdProbeVerdict
        val detail: String
        when {
            logged != null && logged.policy.uppercase(Locale.ROOT).startsWith("REJECT") -> {
                if (logged.rule.contains(AdblockAuditor.BLOCK, true)) {
                    verdict = AdProbeVerdict.Blocked; detail = "核心按河图广告规则 REJECT"
                } else {
                    verdict = AdProbeVerdict.BlockedByOther; detail = "被其他规则拒绝：${logged.rule.ifBlank { "未命名规则" }}"
                }
            }
            logged != null -> {
                verdict = AdProbeVerdict.Allowed
                detail = if (logged.rule.isBlank()) "未拦截：未命中任何规则 → ${logged.policy}" else "未拦截：命中 ${logged.rule.take(80)} → ${logged.policy}"
            }
            httpCode != null -> { verdict = AdProbeVerdict.Allowed; detail = "未拦截：返回 HTTP $httpCode（核心日志无此请求记录）" }
            else -> { verdict = AdProbeVerdict.Unknown; detail = "无法确认：${error ?: "请求失败"}，核心日志没有此请求的判定" }
        }
        return AdProbeResult(domain, expectBlocked, verdict, detail)
    }

    /** Verdict matches the expectation (blocked ad, passed control). */
    fun asExpected(result: AdProbeResult): Boolean = if (result.expectBlocked)
        result.verdict == AdProbeVerdict.Blocked || result.verdict == AdProbeVerdict.BlockedByOther
    else result.verdict == AdProbeVerdict.Allowed

    private fun httpGet(host: String, proxy: Proxy): Pair<Int?, String?> {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL("http://$host/").openConnection(proxy) as HttpURLConnection).apply {
                instanceFollowRedirects = false
                useCaches = false
                connectTimeout = 4_000
                readTimeout = 4_000
                setRequestProperty("Connection", "close")
                setRequestProperty("User-Agent", "Hetu-AdblockProbe")
            }
            connection.responseCode to null
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            null to PlatformProbe.failureReason(error)
        } finally {
            try { connection?.disconnect() } catch (_: Exception) {}
        }
    }

    /**
     * Sends one plain HTTP request per domain through the core's loopback listener, then reads
     * the core's own log lines written since just before the requests. Root is needed for the log.
     * Requests count as real blocks in the session counter.
     */
    suspend fun run(context: Context, domains: List<String>): List<AdProbeResult> = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val proxy = PlatformRoute.proxy(app) ?: throw IllegalStateException("代理未运行，无法经核心实测")
        val before = RootBridge.rootShell(app, "wc -c < $LOG 2>/dev/null", 5_000L).takeIf { it.ok() }?.output?.trim()?.toLongOrNull()
        val targets = domains.map { it to true } + (CONTROL to false)
        val responses = coroutineScope {
            targets.map { (domain, _) -> async { domain to httpGet(domain, proxy) } }.awaitAll().toMap()
        }
        delay(300L) // let the core flush its log line for the last request
        val slice = if (before == null) "" else {
            val read = RootBridge.rootShell(app,
                "size=$(wc -c < $LOG 2>/dev/null); if [ \"${'$'}size\" -ge $before ]; then tail -c +${before + 1} $LOG | tail -c 524288; else tail -c 262144 $LOG; fi",
                6_000L)
            if (read.ok()) read.output else ""
        }
        targets.map { (domain, expect) ->
            val (code, error) = responses[domain] ?: (null to null)
            val logged = findDecision(slice, domain)
            classify(domain, expect, logged, code, if (before == null && logged == null) (error ?: "") + "（无法读取核心日志）" else error)
        }
    }
}

/** Gathers what the audit needs from the controller, the startup copy and the rule store. */
internal object AdblockAuditBridge {
    suspend fun audit(context: Context, running: Boolean? = null): AdblockAudit = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences("hetu", Context.MODE_PRIVATE)
        val live = running ?: ProxyStatusBridge.rootProxyRunning(app)
        val store = RuleStore(app)
        quietly(Unit) { store.reload() }
        val controller = MihomoControllerClient(app)
        val input = AdblockAuditInput(
            enabled = prefs.getBoolean("proxyAdblockChain", true),
            running = live,
            mode = if (live) quietly(null) { controller.configs().optString("mode", "") } else null,
            startupYaml = quietly(null) { RootProxyManager(app).startupConfig() },
            rules = if (live) quietly(null) { AdblockAuditor.parseRules(controller.rules()) } else null,
            providers = if (live) quietly(null) { AdblockAuditor.parseProviders(controller.ruleProviders()) } else null,
            localRuleCount = store.count(),
            lastError = prefs.getString("proxyAdblockLastError", "").orEmpty(),
        )
        AdblockAuditor.audit(input)
    }

    /** Ad hosts from the active list (block minus allow) to probe; empty when none is covered. */
    suspend fun probeDomains(context: Context): List<String> = withContext(Dispatchers.IO) {
        val store = RuleStore(context.applicationContext)
        store.reload()
        val rules = RuleStore.currentExportRules()
        AdblockProbe.pick(rules.domains, rules.allowDomains)
    }

    private inline fun <T> quietly(fallback: T, block: () -> T): T = try {
        block()
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (_: Exception) {
        fallback
    }
}
