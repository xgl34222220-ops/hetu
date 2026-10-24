package io.github.xgl34222220.hetu

import java.util.Locale

/**
 * CNIP kernel bypass x fake-IP DNS (pure Kotlin, no Android API).
 *
 * The kernel bypass (ipset or the iptables tree in hetu-root.sh) matches the real destination
 * address. In fake-ip mode an app asks the core for a domestic name and gets 198.18.x.y back, so its
 * connection never matches a CN network and always enters the core, even with the bypass on. While
 * the bypass is on, domestic names therefore get real addresses in the private runtime copy:
 *
 *  - `geosite:cn` (fake-ip-filter, blacklist mode) or `GEOSITE,cn,real-ip` (rule mode), but only when
 *    the core's home directory already holds GeoSite.dat ([geositeAvailable], checked by Root before
 *    the start). Mihomo would otherwise try to download it during the start and fail without network.
 *  - otherwise an offline list: every `.cn` name plus the big domestic services and phone vendors.
 *
 * Entries are appended; nothing the user wrote is removed or reordered (rule mode is first-match:
 * ours go after the user's entries and before a final `MATCH`, so user rules keep precedence).
 * Whitelist mode and a list that already names geosite:cn are left alone. Apps that resolve with their own DoH/DoT or hard-coded IPs are not affected.
 */
object CnipFakeIp {
    const val PREF_V4 = "proxyCnIpKernelV4"
    const val PREF_V6 = "proxyCnIpKernelV6"
    /**
     * 6.0.0 (domestic video calls lagging), default on: while the full kernel bypass above is off, UDP to CN
     * addresses (voice/video calls, games, P2P) skips the core's userspace relay. TCP keeps going through the
     * core's rules, and no DNS answer changes (no fake-ip-filter entries are added for it).
     */
    const val PREF_UDP_DIRECT = "proxyCnIpUdpDirect"

    /** policy/cnip for [PREF_UDP_DIRECT]: both families, UDP only, AUTO = runs without it where the kernel cannot. */
    @JvmStatic
    fun udpPolicyBody(forceUids: String): String = "V4=1\nV6=1\nFORCE=$forceUids\nTCPONLY=0\nUDPONLY=1\nAUTO=1\n"
    const val GEOSITE_ENTRY = "geosite:cn"
    const val GEOSITE_RULE = "GEOSITE,cn,real-ip"

    /** Offline fallback (no GeoSite.dat): `.cn` plus the domestic services seen most on phones. */
    @JvmField val OFFLINE_SUFFIXES: List<String> = listOf(
        "cn", "baidu.com", "bdstatic.com", "bcebos.com", "qq.com", "gtimg.com", "qpic.cn", "tencent.com",
        "myqcloud.com", "tencent-cloud.net", "wechat.com", "taobao.com", "tmall.com", "alicdn.com", "alipay.com",
        "alipayobjects.com", "aliyun.com", "aliyuncs.com", "alibaba.com", "jd.com", "360buyimg.com",
        "bilibili.com", "hdslb.com", "biliapi.net", "douyin.com", "douyinpic.com", "douyinvod.com", "amemv.com",
        "snssdk.com", "pstatp.com", "bytedance.com", "byteimg.com", "zijieapi.com", "toutiao.com", "ixigua.com",
        "kuaishou.com", "weibo.com", "sinaimg.cn", "163.com", "126.net", "netease.com", "meituan.com",
        "meituan.net", "dianping.com", "pinduoduo.com", "yangkeduo.com", "iqiyi.com", "youku.com",
        "xiaohongshu.com", "xhscdn.com", "zhihu.com", "zhimg.com", "ctrip.com", "ele.me", "amap.com",
        "autonavi.com", "heytapmobi.com", "heytap.com", "oppomobile.com", "coloros.com", "allawntech.com",
        "mi.com", "xiaomi.com", "miui.com", "xiaomi.net", "vivo.com.cn", "huawei.com", "hicloud.com",
        "dbankcloud.com", "dbankcdn.com",
    )

    class Result(@JvmField val yaml: String, @JvmField val state: String)

    /** True when the CNIP kernel bypass is switched on for either family. */
    @JvmStatic
    fun kernelBypassWanted(v4: Boolean, v6: Boolean): Boolean = v4 || v6

    /** A case-insensitive `GeoSite.dat` in a `ls -l`-style listing of the core home, larger than 64 KiB. */
    @JvmStatic
    fun geositeAvailable(listing: String?): Boolean = (listing ?: "").lineSequence().any { line ->
        val words = line.trim().split(Regex("\\s+"))
        words.size >= 2 && words.last().lowercase(Locale.ROOT) == "geosite.dat" &&
            (words.firstOrNull { w -> w.all { it.isDigit() } && w.length >= 5 }?.toLongOrNull() ?: 0L) > 65_536L
    }

    /**
     * States: no-dns, inline-dns, not-fake-ip, whitelist-kept, inline-skipped, user-entry (the list already
     * has geosite:cn), geosite / offline (blacklist entries added), rule-geosite / rule-offline (rule entries).
     */
    @JvmStatic
    fun ensureFakeIpFilter(source: String?, geosite: Boolean): Result {
        val yaml = (source ?: "").replace("\r\n", "\n").replace('\r', '\n')
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
        val items = when {
            rule && geosite -> listOf(GEOSITE_RULE)
            rule -> OFFLINE_SUFFIXES.map { "DOMAIN-SUFFIX,$it,real-ip" }
            geosite -> listOf(GEOSITE_ENTRY)
            else -> OFFLINE_SUFFIXES.map { "+.$it" }
        }
        val state = (if (rule) "rule-" else "") + (if (geosite) "geosite" else "offline")
        val filter = findChild(lines, start, end, child, "fake-ip-filter")
        if (filter >= 0) {
            val rest = value(lines[filter])
            when {
                rest.isEmpty() -> {
                    var g = -1
                    var last = filter
                    var match = -1
                    val existing = HashSet<String>()
                    for (i in filter + 1 until end) {
                        val t = lines[i].trim()
                        if (t.isEmpty() || t.startsWith("#")) continue
                        val ind = indent(lines[i])
                        if (ind > child || (ind == child && t.startsWith("-"))) {
                            last = i
                            if (t.startsWith("-")) {
                                val entry = unquote(stripComment(t.substring(1).trim()))
                                if (namesGeositeCn(entry)) return Result(yaml, "user-entry")
                                existing.add(entry.lowercase(Locale.ROOT))
                                if (match < 0 && rule && entry.substringBefore(',').trim().equals("MATCH", ignoreCase = true)) match = i
                                if (g < 0) g = ind
                            }
                        } else break
                    }
                    if (g < 0) g = child + 2
                    val entries = items.filter { it.lowercase(Locale.ROOT) !in existing }.map { spaces(g) + "- '" + it + "'" }
                    // User entries keep precedence: blacklist order does not matter, rule mode is first-match,
                    // so ours go after the user's entries but before a final MATCH.
                    lines.addAll(if (match >= 0) match else last + 1, entries)
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
        return Result(lines.joinToString("\n"), state)
    }

    private fun namesGeositeCn(entry: String): Boolean {
        val e = entry.lowercase(Locale.ROOT).replace(" ", "")
        return e == GEOSITE_ENTRY || e.startsWith("geosite,cn,") || e == "geosite:cn" || e.startsWith("geosite:cn,")
    }

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
