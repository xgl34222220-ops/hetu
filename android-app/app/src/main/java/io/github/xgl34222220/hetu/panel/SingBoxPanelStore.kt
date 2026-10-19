package io.github.xgl34222220.hetu.panel

import io.github.xgl34222220.hetu.DashboardProviderUi
import io.github.xgl34222220.hetu.DashboardRuleSetUi
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.math.BigInteger
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.zip.InflaterInputStream
import org.json.JSONArray
import org.json.JSONObject

/**
 * 订阅 / 规则集 for sing-box. Its clash_api has neither /providers endpoint (1.14.3: `{}` and `[]`,
 * every update 404), so the panel shows what Hetu itself fetched and deployed for the running
 * config. RootProxyManager records it when it prepares a sing-box start ([FILE] in noBackup) and
 * updates an entry when a subscription is downloaded again or a rule-set file is replaced
 * (sing-box reloads a changed local rule-set file by itself, checked with 1.14.3).
 *
 * providers[]: name, type (http / inline / file), url, nodes[], updatedAt (ms, 0 = never), info (the
 * subscription-userinfo header), fetchedNodes (nodes in the last download, -1 = not downloaded since start).
 * ruleSets[]: tag, type (local / inline / remote), format (binary / source), url, path,
 * rules (-1 = unknown), updatedAt (ms, 0 = unknown).
 */
object SingBoxPanelStore {
    const val FILE = "singbox-panel.json"
    private const val MAX_INFLATED = 32 * 1024 * 1024

    @JvmStatic fun load(dir: File): JSONObject? = runCatching { JSONObject(File(dir, FILE).readText()) }.getOrNull()

    @JvmStatic @Throws(IOException::class)
    fun save(dir: File, meta: JSONObject) {
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("无法创建面板数据目录")
        val tmp = File(dir, "$FILE.tmp")
        tmp.writeText(meta.toString())
        if (!tmp.renameTo(File(dir, FILE))) { tmp.delete(); throw IOException("无法保存面板数据") }
    }

    /** The entry of [key] = [value] in the array [list] of [meta], or null. */
    @JvmStatic fun entry(meta: JSONObject?, list: String, key: String, value: String): JSONObject? {
        val items = meta?.optJSONArray(list) ?: return null
        for (i in 0 until items.length()) items.optJSONObject(i)?.takeIf { it.optString(key) == value }?.let { return it }
        return null
    }

    /** Rule entries of a rule-set file: binary srs or source JSON; -1 when it cannot be read. */
    @JvmStatic fun ruleCount(body: ByteArray?, format: String): Int = runCatching {
        if (body == null) -1 else if (format == "source") sourceRuleCount(String(body, Charsets.UTF_8)) else srsRuleCount(body)
    }.getOrDefault(-1)

    @JvmStatic fun sourceRuleCount(json: String): Int = rulesCount(JSONObject(json).optJSONArray("rules"))

    /** Entries of headless rules (an inline rule-set's `rules`), the way `sing-box rule-set decompile` lists them. */
    @JvmStatic fun rulesCount(rules: JSONArray?): Int {
        if (rules == null) return 0
        var total = 0
        for (i in 0 until rules.length()) {
            val rule = rules.optJSONObject(i) ?: continue
            if (rule.optString("type") == "logical") { total += rulesCount(rule.optJSONArray("rules")); continue }
            for (key in rule.keys()) {
                if (key == "type" || key == "invert") continue
                total += (rule.opt(key) as? JSONArray)?.length() ?: 1
            }
        }
        return total
    }

    /**
     * Entries of a binary rule-set ("SRS", version, zlib body), counted like `sing-box rule-set decompile`:
     * each domain / suffix once (a version 1 file stores a suffix twice), each IP range as its CIDRs.
     */
    @JvmStatic fun srsRuleCount(file: ByteArray): Int {
        if (file.size < 4 || file[0] != 'S'.code.toByte() || file[1] != 'R'.code.toByte() || file[2] != 'S'.code.toByte()) throw IOException("not srs")
        val body = ByteArrayOutputStream()
        InflaterInputStream(file.inputStream(4, file.size - 4)).use { input ->
            val buffer = ByteArray(32 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                body.write(buffer, 0, n)
                if (body.size() > MAX_INFLATED) throw IOException("srs too large")
            }
        }
        val r = Srs(body.toByteArray())
        var total = 0
        repeat(r.uvarint().toInt()) { total += r.rule() }
        if (!r.done) throw IOException("srs trailing data")
        return total
    }

    private class Srs(private val b: ByteArray) {
        private var i = 0
        val done: Boolean get() = i == b.size
        fun u8(): Int { if (i >= b.size) throw IOException("srs truncated"); return b[i++].toInt() and 0xff }
        fun uvarint(): Long {
            var x = 0L; var shift = 0
            while (true) {
                val c = u8()
                x = x or ((c and 0x7f).toLong() shl shift)
                if (c < 0x80) return x
                shift += 7
                if (shift > 63) throw IOException("srs varint")
            }
        }
        fun take(n: Long): ByteArray {
            if (n < 0 || n > b.size - i) throw IOException("srs truncated")
            return b.copyOfRange(i, i + n.toInt()).also { i += n.toInt() }
        }
        fun strings(): Int { val n = uvarint(); repeat(n.toInt()) { take(uvarint()) }; return n.toInt() }
        fun words(): LongArray {
            val n = uvarint().toInt()
            val raw = take(8L * n)
            return LongArray(n) { k -> var v = 0L; for (j in 0 until 8) v = (v shl 8) or (raw[k * 8 + j].toLong() and 0xff); v }
        }

        fun rule(): Int = when (val type = u8()) {
            0 -> {
                var count = 0
                while (true) {
                    val item = u8()
                    if (item == 0xff) { u8(); break }
                    count += when (item) {
                        0, 7, 9 -> { val n = uvarint(); take(2 * n); n.toInt() }
                        1, 3, 4, 8, 10, 11, 12, 13, 14, 15, 17 -> strings()
                        2 -> domains()
                        5, 6 -> ipSet()
                        18 -> { val n = uvarint(); take(n); n.toInt() }
                        19, 20 -> 1
                        else -> throw IOException("srs item $item")
                    }
                }
                count
            }
            1 -> { u8(); var count = 0; repeat(uvarint().toInt()) { count += rule() }; u8(); count }
            else -> throw IOException("srs rule $type")
        }

        /** The succinct trie of reversed domains: walks it and applies sing-box's own Dump merge. */
        private fun domains(): Int {
            if (u8() != 0) throw IOException("srs domain set")
            val leaves = words(); val bitmap = words(); val labels = take(uvarint())
            val parent = IntArray(labels.size + 1); val label = ByteArray(labels.size + 1)
            fun bit(words: LongArray, n: Int) = n ushr 6 < words.size && (words[n ushr 6] ushr (n and 63)) and 1L == 1L
            var nodes = 1; var pos = 0; var edge = 0; var node = 0
            val exact = HashSet<String>(); val prefixed = HashSet<String>(); var rooted = 0
            while (node < nodes) {
                if (bit(leaves, node)) {
                    // Parent links walk the reversed key backwards: the domain in reading order.
                    val out = StringBuilder()
                    var at = node
                    while (at != 0) { out.append((label[at].toInt() and 0xff).toChar()); at = parent[at] }
                    val key = out.toString()
                    when {
                        key.startsWith("\r") -> prefixed += key.substring(1)
                        key.startsWith("\n") -> rooted++
                        else -> exact += key
                    }
                }
                while (!bit(bitmap, pos)) {
                    if (edge >= labels.size || nodes > labels.size) throw IOException("srs domain trie")
                    parent[nodes] = node; label[nodes] = labels[edge]
                    nodes++; edge++; pos++
                }
                pos++; node++
            }
            for (p in prefixed) if (p.startsWith(".")) exact.remove(p.substring(1))
            return exact.size + rooted + prefixed.size
        }

        private fun ipSet(): Int {
            if (u8() != 1) throw IOException("srs ip set")
            var n = 0L
            repeat(8) { n = (n shl 8) or u8().toLong() }
            if (n < 0 || n > b.size) throw IOException("srs ip set")
            var count = 0
            repeat(n.toInt()) {
                val from = take(uvarint()); val to = take(uvarint())
                count += cidrs(BigInteger(1, from), BigInteger(1, to), from.size * 8)
            }
            return count
        }

        /** CIDR blocks that cover [lo, hi] exactly. */
        private fun cidrs(start: BigInteger, hi: BigInteger, bits: Int): Int {
            var lo = start; var count = 0
            while (lo <= hi) {
                var size = if (lo.signum() == 0) bits else lo.lowestSetBit
                while (size > 0 && lo.add(BigInteger.ONE.shiftLeft(size)).subtract(BigInteger.ONE) > hi) size--
                count++
                lo = lo.add(BigInteger.ONE.shiftLeft(size))
            }
            return count
        }
    }

    /** Remote (http) subscriptions of the recorded start, like the Mihomo page lists them. */
    internal fun providers(meta: JSONObject?): List<DashboardProviderUi> {
        val items = meta?.optJSONArray("providers") ?: return emptyList()
        val out = ArrayList<DashboardProviderUi>()
        for (i in 0 until items.length()) {
            val p = items.optJSONObject(i) ?: continue
            if (p.optString("type") != "http") continue
            out += provider(p)
        }
        return out.sortedBy { it.name.lowercase() }
    }

    internal fun provider(p: JSONObject): DashboardProviderUi {
        val nodes = LinkedHashSet<String>()
        val list = p.optJSONArray("nodes") ?: JSONArray()
        for (i in 0 until list.length()) list.optString(i).takeIf { it.isNotBlank() }?.let(nodes::add)
        val info = userInfo(p.optString("info"))
        return DashboardProviderUi(
            name = p.optString("name"), vehicleType = if (p.optString("type") == "http") "HTTP" else p.optString("type").replaceFirstChar { it.uppercase() },
            testUrl = "", expectedStatus = "200-399", updatedAt = iso(p.optLong("updatedAt")),
            upload = info["upload"] ?: 0L, download = info["download"] ?: 0L, total = info["total"] ?: 0L, expire = info["expire"] ?: 0L,
            nodes = nodes, hasSubscriptionInfo = info.isNotEmpty(),
        )
    }

    internal fun ruleSets(meta: JSONObject?): List<DashboardRuleSetUi> {
        val items = meta?.optJSONArray("ruleSets") ?: return emptyList()
        val out = ArrayList<DashboardRuleSetUi>()
        for (i in 0 until items.length()) items.optJSONObject(i)?.let { out += ruleSet(it) }
        return out.sortedBy { it.name.lowercase() }
    }

    /** HTTP = a file Hetu downloaded and can download again; Inline / Local / Remote are not updated from here (PanelRuleSet.updatable). */
    internal fun ruleSet(s: JSONObject): DashboardRuleSetUi {
        val type = s.optString("type")
        val vehicle = when {
            type == "local" && s.optString("url").isNotBlank() -> "HTTP"
            type == "local" -> "Local"
            type == "inline" -> "Inline"
            else -> "Remote"
        }
        val tag = s.optString("tag")
        return DashboardRuleSetUi(
            name = tag,
            behavior = when { tag.startsWith("geosite-") -> "domain"; tag.startsWith("geoip-") -> "ipcidr"; else -> "" },
            format = when { type == "inline" -> "INLINE"; s.optString("format") == "source" -> "JSON"; else -> "SRS" },
            vehicleType = vehicle,
            ruleCount = s.optInt("rules", -1),
            updatedAt = iso(s.optLong("updatedAt")),
        )
    }

    /** `upload=1; download=2; total=3; expire=4` (any order, any spacing); keys without a number are left out. */
    @JvmStatic fun userInfo(header: String?): Map<String, Long> {
        val out = LinkedHashMap<String, Long>()
        for (part in header.orEmpty().split(';')) {
            val key = part.substringBefore('=').trim().lowercase()
            val value = part.substringAfter('=', "").trim().toDoubleOrNull() ?: continue
            if (key in setOf("upload", "download", "total", "expire") && value >= 0) out[key] = value.toLong()
        }
        return out
    }

    private val isoFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.ROOT)

    /** Local-time ISO 8601 with seconds and offset, like Mihomo's updatedAt; empty for 0. */
    @JvmStatic fun iso(ms: Long): String =
        if (ms <= 0L) "" else isoFormat.format(OffsetDateTime.ofInstant(Instant.ofEpochMilli(ms), ZoneId.systemDefault()))
}
