package io.github.xgl34222220.hetu

import android.content.Context
import android.os.SystemClock
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.UnknownHostException
import java.nio.charset.StandardCharsets
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
    /** Controller RuleWrapper counters of the REJECT rule: kept in memory, independent of log level. */
    val hitCount: Long = 0L,
    val missCount: Long = 0L,
    /** Rules evaluated before the REJECT rule (leading exemptions / guards). */
    val rulesBefore: Int = 0,
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
        var hit = 0L
        var miss = 0L
        rules.forEachIndexed { position, rule ->
            if (blocking < 0 && AdblockRuleInspection.isBlockingRule(rule.type, rule.payload, rule.proxy, rule.disabled)) {
                blocking = position; hit = rule.hitCount; miss = rule.missCount
            }
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
        return AdblockChainReport(mode.trim().lowercase(Locale.ROOT), blocking, routing, match, block, allow, empty,
            hitCount = hit, missCount = miss, rulesBefore = blocking.coerceAtLeast(0))
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

    /** Index, hit and miss counters of the strict REJECT rule in a /rules reading; null without it. */
    fun blockingCounters(rules: List<ProxyRuleUi>): Triple<Int, Long, Long>? {
        val position = rules.indexOfFirst { AdblockRuleInspection.isBlockingRule(it.type, it.payload, it.proxy, it.disabled) }
        if (position < 0) return null
        return Triple(position, rules[position].hitCount, rules[position].missCount)
    }

    /**
     * Verdict from two /rules readings around one request: the REJECT rule's hitCount went up, or
     * the rule whose hitCount moved instead. Null when no counter moved (nothing concluded).
     */
    fun verdictFromCounters(before: List<ProxyRuleUi>, after: List<ProxyRuleUi>, domain: String): AdblockProbe? {
        val b = blockingCounters(before)
        val a = blockingCounters(after)
        if (b != null && a != null && a.second > b.second) return AdblockProbe(true, domain, "$domain → REJECT，河图广告规则 hitCount +${a.second - b.second}")
        val old = before.associateBy { it.index }
        val moved = after.firstOrNull { rule -> rule.hitCount > (old[rule.index]?.hitCount ?: Long.MAX_VALUE) } ?: return null
        val policy = moved.proxy.uppercase(Locale.ROOT)
        val label = "第 ${moved.index + 1} 条 ${moved.type}${if (moved.payload.isNotBlank()) "," + moved.payload.take(60) else ""}"
        return if (policy.startsWith("REJECT")) AdblockProbe(true, domain, "$domain → REJECT，命中配置自带规则 $label")
        else AdblockProbe(false, domain, "$domain 未被拦截：命中 $label → ${moved.proxy}")
    }

    /** CONNECT request bytes for [domain]:443 (HTTPS tunnel: not subject to the cleartext policy). */
    fun connectRequest(domain: String): ByteArray =
        "CONNECT $domain:443 HTTP/1.1\r\nHost: $domain:443\r\nUser-Agent: Hetu-Android\r\nProxy-Connection: close\r\n\r\n".toByteArray(StandardCharsets.US_ASCII)

    /**
     * One raw-socket CONNECT for a blocked host through the running core's loopback HTTP listener
     * (Hetu's own UID is exempt from transparent interception). A plain-HTTP request via
     * HttpURLConnection never left the App: the network security config forbids cleartext to the
     * target host, so the core saw nothing. The verdict comes from the controller's in-memory rule
     * counters (no log level, no su); core.log is only a fallback when the controller is silent.
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
        val controller = ProxyComposeController(app)
        suspend fun readRules(): List<ProxyRuleUi>? = try { controller.rules() } catch (cancel: CancellationException) { throw cancel } catch (_: Exception) { null }
        val before = readRules()
        val port = MihomoStartupConfig.egressProbePort(prefs.getInt("proxyControllerPort", MihomoStartupConfig.CONTROLLER_PORT))
        val started = SystemClock.elapsedRealtime()
        val status = try {
            Socket().use { socket ->
                socket.soTimeout = 3_000
                socket.connect(InetSocketAddress("127.0.0.1", port), 3_000)
                socket.getOutputStream().apply { write(connectRequest(domain)); flush() }
                val line = StringBuilder()
                val input = socket.getInputStream()
                while (line.length < 200) {
                    val b = input.read()
                    if (b < 0 || b == '\n'.code) break
                    line.append(b.toChar())
                }
                line.toString().trim()
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            // A rejected tunnel is closed by the core; the counters below are the evidence either way.
            ""
        }
        val elapsed = SystemClock.elapsedRealtime() - started
        if (before != null) {
            for (attempt in 0 until 6) {
                if (attempt > 0) delay(250L)
                val after = readRules() ?: break
                val verdict = verdictFromCounters(before, after, domain)
                if (verdict != null) return@withContext if (verdict.ok == true) verdict.copy(detail = verdict.detail + " · CONNECT 443 · ${elapsed} ms") else verdict
            }
        }
        // Controller unreadable or silent: fall back to the core's own decision line.
        val log = "/data/adb/hetu/run/core.log"
        val pattern = RootBridge.quote("--> $domain:")
        val tail = try { RootBridge.rootShell(app, "tail -c 262144 $log 2>/dev/null | grep -F -- $pattern | tail -n 1", 5_000L).output } catch (cancel: CancellationException) { throw cancel } catch (_: Exception) { "" }
        parseProbeLog(tail, domain)?.let { return@withContext it }
        AdblockProbe(null, domain, if (status.isEmpty()) "无法连接核心本地监听端口 $port，不能确认拦截结果" else "核心未记录这次 CONNECT 的规则判定（控制器计数未变化），无法确认")
    }

    /** Fake-IP pools Mihomo answers from (198.18.0.0/15 and its default IPv6 pool). */
    fun isFakeIp(address: InetAddress): Boolean {
        val b = address.address
        return when (address) {
            is Inet4Address -> (b[0].toInt() and 0xff) == 198 && ((b[1].toInt() and 0xff) == 18 || (b[1].toInt() and 0xff) == 19)
            is Inet6Address -> (b[0].toInt() and 0xff) == 0xfd && (b[1].toInt() and 0xff) == 0xfe
            else -> false
        }
    }

    /**
     * What the system resolver (the way ordinary apps resolve) answered for a blocked domain.
     * [dnsLayer]: the running copy answers the DNS block set with an empty reply.
     */
    fun dnsVerdict(domain: String, answers: List<InetAddress>?, dnsLayer: Boolean, privateDnsHost: String?): AdblockProbe {
        val bypass = privateDnsHost?.takeIf { it.isNotBlank() }?.let { "；系统私人 DNS（$it）绕过代理 DNS" }.orEmpty()
        return when {
            answers.isNullOrEmpty() && dnsLayer -> AdblockProbe(true, domain, "$domain 无解析结果：系统 DNS 已进入 Mihomo，DNS 层空应答生效")
            answers.isNullOrEmpty() -> AdblockProbe(null, domain, "$domain 无解析结果；当前运行副本没有 DNS 层拦截，无法判断接管")
            answers.all { isFakeIp(it) } -> AdblockProbe(true, domain, "$domain → fake-ip ${answers.first().hostAddress}：系统 DNS 已进入 Mihomo，连接层 REJECT 兜底")
            dnsLayer -> AdblockProbe(false, domain, "$domain 返回真实地址 ${answers.first().hostAddress}：系统 DNS 未进入 Mihomo（私人 DNS / DoT / IPv6 DNS 绕过或系统缓存）$bypass")
            else -> AdblockProbe(null, domain, "$domain 返回 ${answers.first().hostAddress}；redir-host 下无法区分是否经过 Mihomo$bypass")
        }
    }

    /**
     * DNS 接管实测: resolve a blocked host through the system resolver. Hetu's UID is not exempt
     * from the DNS redirect chain, so its queries take the same path as any app's.
     */
    suspend fun dnsProbe(context: Context): AdblockProbe = withContext(Dispatchers.IO) {
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
        val startup = try { RootProxyManager(app).startupConfig() } catch (cancel: CancellationException) { throw cancel } catch (_: Exception) { "" }
        val answers = try { InetAddress.getAllByName(domain).toList() } catch (_: UnknownHostException) { emptyList() }
            catch (cancel: CancellationException) { throw cancel } catch (_: Exception) { null }
        if (answers == null) return@withContext AdblockProbe(null, domain, "系统解析器未应答，无法确认 DNS 接管")
        dnsVerdict(domain, answers, AdblockDnsPolicy.isInjected(startup), ToolsRuntimeBridge.privateDnsHostname(app))
    }

    /** 运行链验证 › DNS 路径: ok when DNS reaches the core and the DNS layer answers ad hosts. */
    fun dnsPath(chain: ToolsAdblockChain): Pair<Boolean, String> {
        val parts = ArrayList<String>()
        parts += when (chain.enhancedMode) { "fake-ip" -> "fake-ip"; "redir-host" -> "redir-host"; "" -> "核心 DNS 未启用"; else -> chain.enhancedMode }
        parts += when {
            chain.dnsHijackMode.equals("off", true) -> "系统 DNS 未接管"
            chain.systemDns == "captured" -> "系统 DNS 已接管"
            chain.systemDns == "exempt" -> "系统 DNS 已接管（Root 进程豁免）"
            else -> "接管状态未知"
        }
        parts += when (chain.privateDnsMode) {
            "hostname" -> "私人 DNS ${chain.privateDnsHost.ifBlank { "主机名" }}（绕过代理 DNS）"
            "opportunistic" -> if (chain.dotGuard) "私人 DNS 自动（DoT 已拦截，回落 53）" else "私人 DNS 自动（DoT 可能绕过）"
            "off" -> "私人 DNS 关闭"
            else -> "私人 DNS 未知"
        }
        parts += when {
            chain.dnsIpv6Rule -> "IPv6 DNS 已劫持"
            chain.ipv6DnsPolicy.startsWith("blocked") -> "IPv6 DNS 已阻断"
            else -> "IPv6 DNS 未劫持"
        }
        parts += when (chain.cnip) {
            "ipset", "iptables", "ebpf" -> when {
                chain.cnipTcpOnly -> "CNIP 内核旁路（仅 TCP）"
                chain.cnipUdpOnly -> "CNIP 内核旁路（仅 UDP）"
                else -> "CNIP 内核旁路"
            }
            "degraded" -> "CNIP 由核心规则处理"
            else -> "CNIP 关闭"
        }
        parts += if (chain.dnsLayer) "DNS 层拦截已启用" else "无 DNS 层拦截"
        val ok = chain.running && chain.dnsLayer && !chain.dnsHijackMode.equals("off", true) && chain.privateDnsMode != "hostname"
        return ok to parts.joinToString(" · ")
    }

    /** Plain-language reasons the chain may miss ads; empty when nothing needs attention. */
    fun hints(chain: ToolsAdblockChain): List<String> {
        val out = ArrayList<String>()
        if (chain.privateDnsMode == "hostname")
            out += "系统私人 DNS 设为「${chain.privateDnsHost.ifBlank { "指定主机名" }}」：系统 DNS 直接加密发往该服务器，不经过 Mihomo，DNS 层拦截不生效，域名规则只能靠 SNI 嗅探。可在系统设置 › 网络 › 私人 DNS 改为「自动」或「关闭」（河图不会自动修改）。"
        when (chain.snifferState) {
            "disabled" -> out += "订阅关闭了域名嗅探（sniffer 未启用）：直连 IP 的广告连接没有域名，连接层规则无法识别，只能靠 DNS 层拦截。"
            "pure-ip-off" -> out += "订阅设置了 parse-pure-ip: false：直连 IP 的连接不做嗅探，这部分广告无法按域名识别。"
        }
        val cnipActive = chain.cnip == "ipset" || chain.cnip == "iptables" || chain.cnip == "ebpf"
        if (cnipActive && chain.cnipUdpOnly) {
            out += if (chain.dnsLayer) "国内 UDP 内核直连已开启：发往中国 IP 的 UDP（含 QUIC）不经过核心，这部分广告只靠 DNS 层拦截。"
            else "国内 UDP 内核直连已开启且当前没有 DNS 层拦截：发往中国 IP 的 UDP（含 QUIC）广告连接不会被拦截。"
        } else if (cnipActive) {
            out += if (chain.dnsLayer)
                "CNIP 内核旁路已开启：解析为中国 IP 的连接${if (chain.cnipTcpOnly) "（TCP）" else ""}不经过核心，这些广告只靠 DNS 层拦截${if (chain.cnipTcpOnly) "" else "；可在 CNIP 卡片开启「仅 TCP 旁路」，让 UDP/QUIC 仍经核心判定"}。"
            else "CNIP 内核旁路已开启且当前没有 DNS 层拦截：解析为中国 IP 的广告连接不经过核心，不会被拦截。"
        }
        if (!chain.sourceHasDns && !chain.recommendedDns)
            out += "订阅没有 dns: 配置：核心只用系统 DNS（redir-host）。可开启下方「河图推荐 DNS」（fake-ip + 国内 DoH，默认关闭，重启代理后生效）。"
        when {
            chain.appsOutside > 0 -> out += "${chain.appsOutside} 个应用在黑名单中，不经过代理，也不经过去广告。"
            chain.appsOutside < 0 -> out += "仅白名单中的 ${chain.scopedApps} 个应用经过代理和去广告，其他应用不经过。"
        }
        if (chain.running && chain.hitsFromController && chain.hits == 0L) {
            val before = chain.report?.rulesBefore ?: 0
            if (chain.missCount == 0L) out += "REJECT 规则还没有检查过任何连接：流量没有到达这条规则（未被代理捕获，或被前 $before 条规则先行放行）。"
            else out += "REJECT 规则已检查 ${String.format(Locale.US, "%,d", chain.missCount)} 个连接但没有命中：这些连接可能没有域名（DNS 未经核心或嗅探未生效）。"
        }
        return out
    }
}
