package io.github.xgl34222220.hetu

import java.util.Locale

/**
 * Network validation must not depend on the core (2026-10 network-loss fix; pure Kotlin, no Android API).
 *
 * Android's NetworkMonitor marks Wi-Fi "无法连接到网络" when its HTTP(S) probe (generate_204) fails;
 * the default network can then move away from Wi-Fi. With Root capture the probe host got a fake IP
 * and its flow went through the core, so a frozen or stuck core looked like broken Wi-Fi. For the
 * private Mihomo runtime copy the probe hosts therefore get real addresses (fake-ip-filter) and go
 * DIRECT before every other rule, ad-block included. hetu-root.sh additionally leaves the network
 * stack UID's DNS out of the takeover.
 *
 * Hosts: the probe hosts the AOSP/Xiaomi/Qualcomm ROMs use that answer from mainland China, plus the
 * hosts of Settings.Global captive_portal_*_url. Google search hosts (google.com, android.com) are
 * never sent DIRECT: that would cut off Google for users who need the proxy to reach it.
 */
object CaptivePortalBypass {
    /** Same list as AdblockDnsPolicy.VALIDATION_PROBE_HOSTS (that pure-Java class is also compiled alone by host tests). */
    @JvmField val DEFAULT_HOSTS: List<String> = AdblockDnsPolicy.VALIDATION_PROBE_HOSTS.toList()

    /** Settings.Global keys whose URLs name the probe hosts. */
    @JvmField val SETTINGS_KEYS: List<String> = listOf(
        "captive_portal_http_url", "captive_portal_https_url", "captive_portal_fallback_url", "captive_portal_other_fallback_urls")

    private val NEVER_DIRECT = listOf("google.com", "android.com", "googleapis.com", "youtube.com")
    private val HOST = Regex("^[a-z0-9]([a-z0-9-]{0,62}[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]{0,62}[a-z0-9])?)+$")

    class Result(@JvmField val yaml: String, @JvmField val state: String)

    /** Default hosts plus the hosts of the given setting values (URLs, comma-separated lists allowed). */
    @JvmStatic
    fun hosts(settingValues: Collection<String?>?): List<String> {
        val out = LinkedHashSet(DEFAULT_HOSTS)
        settingValues?.forEach { value ->
            value?.split(',')?.forEach { url -> host(url)?.let { out.add(it) } }
        }
        return out.toList()
    }

    /** Host of an http(s) URL when it is a plain DNS name that may go DIRECT; null otherwise. */
    @JvmStatic
    fun host(url: String?): String? {
        var t = url?.trim()?.lowercase(Locale.ROOT) ?: return null
        t = when {
            t.startsWith("http://") -> t.substring(7)
            t.startsWith("https://") -> t.substring(8)
            else -> return null
        }
        t = t.substringBefore('/').substringBefore('?').substringBefore('#')
        if (t.contains('@') || t.startsWith('[')) return null
        t = t.substringBefore(':').trimEnd('.')
        if (t.length > 253 || !HOST.matches(t) || t.all { it.isDigit() || it == '.' }) return null
        if (NEVER_DIRECT.any { t == it || t.endsWith(".$it") }) return null
        return t
    }

    /** Applies both parts to a runtime copy; [hosts] empty leaves it untouched. */
    @JvmStatic
    fun apply(yaml: String, hosts: List<String>): Result {
        if (hosts.isEmpty()) return Result(yaml, "off")
        val fake = ensureFakeIpFilter(yaml, hosts)
        val rules = insertDirectRules(fake.yaml, hosts)
        return Result(rules.yaml, "fake-ip=" + fake.state + " rules=" + rules.state)
    }

    /**
     * fake-ip mode only. States: no-dns, inline-dns, not-fake-ip, whitelist-kept, inline-skipped, present
     * (every host already listed), filter (blacklist entries added), rule (rule-mode entries added first).
     */
    @JvmStatic
    fun ensureFakeIpFilter(source: String, hosts: List<String>): Result {
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
        val filter = findChild(lines, start, end, child, "fake-ip-filter")
        if (filter >= 0) {
            val rest = value(lines[filter])
            when {
                rest.isEmpty() -> {
                    var g = -1
                    val existing = HashSet<String>()
                    for (i in filter + 1 until end) {
                        val t = lines[i].trim()
                        if (t.isEmpty() || t.startsWith("#")) continue
                        val ind = indent(lines[i])
                        if (ind > child || (ind == child && t.startsWith("-"))) {
                            if (t.startsWith("-")) {
                                existing.add(unquote(t.substring(1).trim()).lowercase(Locale.ROOT))
                                if (g < 0) g = ind
                            }
                        } else break
                    }
                    if (g < 0) g = child + 2
                    val items = entries(hosts, rule).filter { it.lowercase(Locale.ROOT) !in existing }
                    if (items.isEmpty()) return Result(yaml, "present")
                    lines.addAll(filter + 1, items.map { spaces(g) + "- '" + it + "'" })
                }
                rest == "[]" -> {
                    lines[filter] = spaces(child) + "fake-ip-filter:"
                    lines.addAll(filter + 1, entries(hosts, rule).map { spaces(child + 2) + "- '" + it + "'" })
                }
                else -> return Result(yaml, "inline-skipped")
            }
        } else {
            var at = lastContentLine(lines, start, end) + 1
            lines.add(at++, spaces(child) + "fake-ip-filter:")
            for (item in entries(hosts, rule)) lines.add(at++, spaces(child + 2) + "- '" + item + "'")
            if (!rule) for (d in AdblockDnsPolicy.MIHOMO_DEFAULT_FAKE_IP_FILTER) lines.add(at++, spaces(child + 2) + "- '" + d + "'")
        }
        return Result(lines.joinToString("\n"), if (rule) "rule" else "filter")
    }

    /** DIRECT rules for the hosts at the very top of rules (before ad-block and the source's rules). States: no-rules, inline-skipped, inserted. */
    @JvmStatic
    fun insertDirectRules(source: String, hosts: List<String>): Result {
        val yaml = normalize(source)
        val lines = yaml.split("\n")
        val index = lines.indexOfFirst { indent(it) == 0 && key(it.trim()) == "rules" }
        if (index < 0) return Result(yaml, "no-rules")
        val rest = value(lines[index])
        if (rest.isNotEmpty() && rest != "[]") return Result(yaml, "inline-skipped")
        var listIndent = "  "
        for (i in index + 1 until lines.size) {
            val t = lines[i].trim()
            if (t.isEmpty() || t.startsWith("#")) continue
            if (t.startsWith("-")) listIndent = spaces(indent(lines[i]))
            break
        }
        val out = StringBuilder()
        for (i in lines.indices) {
            out.append(if (i == index && rest == "[]") "rules:" else lines[i])
            if (i == index) for (h in hosts) out.append('\n').append(listIndent).append("- DOMAIN,").append(h).append(",DIRECT")
            if (i < lines.size - 1) out.append('\n')
        }
        return Result(out.toString(), "inserted")
    }

    private fun entries(hosts: List<String>, rule: Boolean): List<String> =
        if (rule) hosts.map { "DOMAIN,$it,real-ip" } else hosts

    /* ------------------------------ YAML helpers (same conventions as WechatTransport) ------------------------------ */

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

    private fun indent(line: String): Int {
        var n = 0
        while (n < line.length && line[n] == ' ') n++
        return n
    }

    private fun spaces(n: Int): String = " ".repeat(maxOf(0, n))
}
