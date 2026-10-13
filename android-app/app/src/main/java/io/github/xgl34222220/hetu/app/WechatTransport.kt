package io.github.xgl34222220.hetu

import java.util.Locale

/**
 * WeChat message-latency policy for the private Mihomo runtime copy (pure Kotlin, no Android API).
 *
 * WeChat (com.tencent.mm, Tencent mars) keeps one long TCP link (port 80/443/8080) to
 * long/hklong.weixin.qq.com or sglong.wechat.com and is pushed over it; a link that dies silently
 * is only noticed at the next app heartbeat, which is when "late" messages arrive. Two defaults keep
 * that link off the proxy path without overriding anything the user wrote:
 *
 *  - [ensureFakeIpFilter]: in fake-ip mode WeChat/Tencent names get real addresses. A fake address
 *    (198.18.0.0/16) can never match the kernel CN ipset bypass, always pulls a bypassed UID back
 *    into the core (owner RETURNs exclude the fake range), and is cached by mars' own DNS cache, so
 *    it black-holes the link after a core restart or once the proxy is stopped.
 *  - [insertDirectRules]: WeChat transport domains go DIRECT right before the final MATCH, unless
 *    the source already routes WeChat/QQ itself (any rule naming weixin/wechat/qq.com/com.tencent.mm).
 *    Earlier source rules always win; `CN-IP ... no-resolve` rules cannot match a fake-ip domain
 *    flow and the HK/SG servers are not CN addresses, so without this they fall to MATCH (proxy).
 *
 * The kernel-level bypass of the whole app is opt-in ([PREF_KERNEL_DIRECT], default off).
 */
object WechatTransport {
    const val PACKAGE = "com.tencent.mm"
    /** Opt-in: WeChat's UID bypasses the core in the kernel (Root direct UID), off by default. */
    const val PREF_KERNEL_DIRECT = "proxyWechatKernelDirect"

    /** fake-ip-filter (blacklist mode) entries: real addresses for WeChat and Tencent QQ names. */
    @JvmField val FAKE_IP_FILTER: List<String> = listOf("+.weixin.qq.com", "+.qq.com", "+.wechat.com")
    /** fake-ip-filter-mode: rule entries, placed first so a trailing MATCH,fake-ip cannot shadow them. */
    @JvmField val FAKE_IP_RULES: List<String> = listOf("DOMAIN-SUFFIX,qq.com,real-ip", "DOMAIN-SUFFIX,wechat.com,real-ip")
    /** Routing rules inserted before the final MATCH when the source has no WeChat policy. */
    @JvmField val DIRECT_RULES: List<String> = listOf("DOMAIN-SUFFIX,weixin.qq.com,DIRECT", "DOMAIN-SUFFIX,wechat.com,DIRECT")

    private val RULE_MARKERS = listOf("weixin", "wechat", "com.tencent.mm", "tencent")

    class Result(@JvmField val yaml: String, @JvmField val state: String)

    /** Packages the opt-in kernel bypass adds; never in 仅所选应用 (whitelist) where the user's selection rules. */
    @JvmStatic
    fun kernelDirectPackages(enabled: Boolean, appScope: String): Set<String> =
        if (enabled && appScope != "whitelist") setOf(PACKAGE) else emptySet()

    /* ------------------------------ fake-ip-filter ------------------------------ */

    /**
     * States: no-dns, inline-dns, not-fake-ip, whitelist-kept, inline-skipped, user-entry (the list already
     * names qq.com / weixin / wechat), filter (blacklist entries added), rule (rule-mode entries added).
     */
    @JvmStatic
    fun ensureFakeIpFilter(source: String?): Result {
        val yaml = normalize(source)
        val lines = yaml.split("\n").toMutableList()
        val start = lines.indexOfFirst { indent(it) == 0 && key(it.trim()) == "dns" }
        if (start < 0) return Result(yaml, "no-dns")
        if (value(lines[start]).isNotEmpty()) return Result(yaml, "inline-dns")
        val end = blockEnd(lines, start)
        val child = childIndent(lines, start, end)
        val enhanced = findChild(lines, start, end, child, "enhanced-mode")
        if (enhanced < 0 || !unquote(value(lines[enhanced])).equals("fake-ip", ignoreCase = true)) return Result(yaml, "not-fake-ip")
        val modeLine = findChild(lines, start, end, child, "fake-ip-filter-mode")
        val mode = if (modeLine < 0) "blacklist" else unquote(value(lines[modeLine])).lowercase(Locale.ROOT)
        if (mode == "whitelist") return Result(yaml, "whitelist-kept")
        val rule = mode == "rule"
        val items = if (rule) FAKE_IP_RULES else FAKE_IP_FILTER
        val filter = findChild(lines, start, end, child, "fake-ip-filter")
        if (filter >= 0) {
            val rest = value(lines[filter])
            when {
                rest.isEmpty() -> {
                    var g = -1
                    for (i in filter + 1 until end) {
                        val t = lines[i].trim()
                        if (t.isEmpty() || t.startsWith("#")) continue
                        val ind = indent(lines[i])
                        if (ind > child || (ind == child && t.startsWith("-"))) {
                            if (filterNamesWechat(t)) return Result(yaml, "user-entry")
                            if (g < 0 && t.startsWith("-")) g = ind
                        } else break
                    }
                    if (g < 0) g = child + 2
                    val entries = items.map { spaces(g) + "- '" + it + "'" }
                    // Rule mode is first-match: our real-ip entries go first. Blacklist order is irrelevant.
                    lines.addAll(filter + 1, entries)
                }
                rest == "[]" -> {
                    lines[filter] = spaces(child) + "fake-ip-filter:"
                    lines.addAll(filter + 1, items.map { spaces(child + 2) + "- '" + it + "'" })
                }
                else -> return Result(yaml, "inline-skipped")
            }
        } else {
            var at = lastContentLine(lines, start, end) + 1
            lines.add(at++, spaces(child) + "fake-ip-filter:")
            for (item in items) lines.add(at++, spaces(child + 2) + "- '" + item + "'")
            if (!rule) for (d in AdblockDnsPolicy.MIHOMO_DEFAULT_FAKE_IP_FILTER) lines.add(at++, spaces(child + 2) + "- '" + d + "'")
        }
        return Result(lines.joinToString("\n"), if (rule) "rule" else "filter")
    }

    /* ------------------------------ routing rules ------------------------------ */

    /** States: no-rules, inline-skipped, user-policy, inserted. */
    @JvmStatic
    fun insertDirectRules(source: String?): Result {
        val yaml = normalize(source)
        val lines = yaml.split("\n")
        val index = lines.indexOfFirst { indent(it) == 0 && key(it.trim()) == "rules" }
        if (index < 0) return Result(yaml, "no-rules")
        val rest = value(lines[index])
        if (rest.isNotEmpty() && rest != "[]") return Result(yaml, "inline-skipped")
        var end = lines.size
        for (i in index + 1 until lines.size) {
            val t = lines[i].trim()
            if (t.isEmpty() || t.startsWith("#")) continue
            if (indent(lines[i]) == 0 && !t.startsWith("-")) { end = i; break }
        }
        var listIndent = "  "
        var insertion = end
        for (i in index + 1 until end) {
            val t = lines[i].trim()
            if (!t.startsWith("-")) continue
            listIndent = spaces(indent(lines[i]))
            val scalar = unquote(stripComment(t.substring(1).trim()))
            if (ruleNamesWechat(scalar)) return Result(yaml, "user-policy")
            val type = scalar.substringBefore(',').trim().uppercase(Locale.ROOT)
            if (type == "MATCH" || type == "FINAL") { insertion = i; break }
        }
        val out = StringBuilder()
        for (i in lines.indices) {
            if (i == insertion) for (r in DIRECT_RULES) out.append(listIndent).append("- ").append(r).append('\n')
            out.append(if (i == index && rest == "[]") "rules:" else lines[i])
            if (i < lines.size - 1) out.append('\n')
        }
        if (insertion >= lines.size) {
            if (!out.endsWith("\n")) out.append('\n')
            for (r in DIRECT_RULES) out.append(listIndent).append("- ").append(r).append('\n')
        }
        return Result(out.toString(), "inserted")
    }

    /* ------------------------------ diagnostics ------------------------------ */

    /** Battery hint for the 诊断与维护 page; null when WeChat is absent, exempt, or unknown. */
    @JvmStatic
    fun batteryHint(installed: Boolean, batteryExempt: Boolean?): String? =
        if (installed && batteryExempt == false)
            "微信未加入电池优化白名单：锁屏后系统可能冻结微信，消息会在下次唤醒时才到达。请在微信的应用设置里把电池改为「无限制」并允许自启动"
        else null

    /**
     * Ranked, plain hints for the 消息与网络诊断 report. [proxiedConnections] counts WeChat flows whose
     * chain is not DIRECT; [fakeIpState] is [ensureFakeIpFilter]'s state for the running copy.
     */
    @JvmStatic
    fun hints(installed: Boolean, batteryExempt: Boolean?, proxiedConnections: Int, directConnections: Int,
              kernelDirect: Boolean, cnipKernel: Boolean, appScope: String, disableKeepAlive: Boolean): List<String> {
        val out = ArrayList<String>()
        if (!installed) { out.add("未检测到微信（com.tencent.mm）"); return out }
        batteryHint(installed, batteryExempt)?.let { out.add(it) }
        if (proxiedConnections > 0)
            out.add("有 $proxiedConnections 条微信连接经代理节点转发：节点或运营商 NAT 空闲超时会静默断开长连接，消息要等微信下次心跳重连后才到。建议在配置里让微信直连，或开启「微信直连（绕过核心）」")
        if (!kernelDirect && appScope == "core" && !cnipKernel)
            out.add("微信流量仍全部经过核心；开启「微信直连（绕过核心）」或「中国 IP 内核直连」可让微信长连接不经核心")
        if (disableKeepAlive)
            out.add("运行配置禁用了 TCP 保活（disable-keep-alive: true），经核心转发的空闲长连接断开后不能及时发现")
        if (proxiedConnections == 0 && directConnections > 0)
            out.add("当前识别到的微信连接都是直连")
        return out
    }

    /* ------------------------------ helpers ------------------------------ */

    /** A source rule that already decides WeChat/QQ traffic (domain, process or a named list). */
    private fun ruleNamesWechat(rule: String): Boolean {
        val lower = rule.lowercase(Locale.ROOT).replace(" ", "")
        return RULE_MARKERS.any { lower.contains(it) } || lower.contains(",qq.com,") || lower.endsWith(",qq.com")
    }

    /** A source fake-ip-filter entry that already decides WeChat/QQ names. */
    private fun filterNamesWechat(entry: String): Boolean {
        val lower = entry.lowercase(Locale.ROOT)
        return lower.contains("qq.com") || lower.contains("weixin") || lower.contains("wechat")
    }

    private fun normalize(s: String?): String = (s ?: "").replace("\r\n", "\n").replace('\r', '\n')

    private fun blockEnd(lines: List<String>, start: Int): Int {
        for (i in start + 1 until lines.size) {
            val t = lines[i].trim()
            if (t.isEmpty() || t.startsWith("#")) continue
            if (indent(lines[i]) == 0) return i
        }
        return lines.size
    }

    private fun childIndent(lines: List<String>, start: Int, end: Int): Int {
        var child = Int.MAX_VALUE
        for (i in start + 1 until end) {
            val t = lines[i].trim()
            if (t.isEmpty() || t.startsWith("#")) continue
            child = minOf(child, indent(lines[i]))
        }
        return if (child == Int.MAX_VALUE || child == 0) 2 else child
    }

    private fun findChild(lines: List<String>, start: Int, end: Int, child: Int, name: String): Int {
        for (i in start + 1 until end) if (indent(lines[i]) == child && key(lines[i].trim()) == name) return i
        return -1
    }

    private fun lastContentLine(lines: List<String>, start: Int, end: Int): Int {
        var last = start
        for (i in start + 1 until end) if (lines[i].trim().isNotEmpty()) last = i
        return last
    }

    private fun key(trimmed: String): String {
        if (trimmed.startsWith("-") || trimmed.startsWith("#")) return ""
        val colon = trimmed.indexOf(':')
        if (colon <= 0) return ""
        return unquote(trimmed.substring(0, colon).trim())
    }

    private fun value(line: String): String {
        val t = line.trim()
        val colon = t.indexOf(':')
        if (colon < 0) return ""
        return stripComment(t.substring(colon + 1).trim())
    }

    private fun stripComment(rest: String): String {
        var single = false
        var dbl = false
        for (i in rest.indices) {
            val c = rest[i]
            if (c == '\'' && !dbl) single = !single
            else if (c == '"' && !single) dbl = !dbl
            else if (c == '#' && !single && !dbl && (i == 0 || rest[i - 1].isWhitespace())) return rest.substring(0, i).trim()
        }
        return rest.trim()
    }

    private fun unquote(v: String): String {
        val t = v.trim()
        if (t.length >= 2 && ((t.first() == '"' && t.last() == '"') || (t.first() == '\'' && t.last() == '\''))) return t.substring(1, t.length - 1).trim()
        return t
    }

    private fun indent(s: String): Int { var n = 0; while (n < s.length && (s[n] == ' ' || s[n] == '\t')) n++; return n }
    private fun spaces(n: Int): String = " ".repeat(n)
}
