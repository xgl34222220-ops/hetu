package io.github.xgl34222220.hetu

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URL
import java.net.UnknownHostException
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull

/* ------------------------------------------------------------------ */
/*  网络测试 · 解锁与地区检测                                             */
/*                                                                      */
/*  Every request goes through the system network: no explicit proxy is */
/*  set, so whatever the Root proxy does to this app's traffic applies   */
/*  transparently, exactly as it would for the service's own app.        */
/* ------------------------------------------------------------------ */

/** Outcome of one service's unlock check. */
internal enum class UnlockState(val label: String) {
    Unlocked("解锁"),
    /** Reachable, but only part of the catalogue (Netflix originals only, Bilibili one area). */
    Restricted("受限"),
    Unavailable("不可用"),
    /** The check itself could not decide; [UnlockResult.detail] carries the concrete reason. */
    Failed("失败"),
}

/**
 * [region] is an ISO 3166 alpha-2 code when the service reported one, otherwise a plain place name
 * (Bilibili reports Chinese names). [detail] qualifies the state: 仅自制剧, 港澳台, a currency, or the
 * reason a check failed.
 */
internal data class UnlockResult(val id: String, val state: UnlockState, val region: String? = null, val detail: String = "") {
    /** Chip text: 解锁, 受限 · 仅自制剧, 失败 · 超时. */
    val chip: String get() = if (detail.isBlank()) state.label else state.label + " · " + detail
}

/** One HTTP answer, headers keyed in lower case. [url] is the last URL after any followed redirects. */
internal data class UnlockReply(val code: Int, val headers: Map<String, List<String>>, val body: String, val url: String) {
    fun header(name: String): String? = headers[name.lowercase(Locale.ROOT)]?.firstOrNull()
    fun headerValues(name: String): List<String> = headers[name.lowercase(Locale.ROOT)].orEmpty()
    val location: String? get() = header("location")
}

/**
 * A single request. [bodyLimit] bounds what is read (0 reads no body); reading also stops as soon
 * as the body contains one of [stopAt], so a region marker early in a large page costs only that far.
 */
internal data class UnlockRequest(
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val bodyLimit: Int = 64 * 1024,
    val stopAt: List<String> = emptyList(),
)

/** Raw transport; [timeoutMs] is the remaining budget of the check this request belongs to. */
internal fun interface UnlockTransport {
    @Throws(IOException::class)
    fun fetch(request: UnlockRequest, timeoutMs: Int): UnlockReply
}

/** Thrown when a check has used its whole budget before the next request could start. */
internal class UnlockDeadline : SocketTimeoutException("deadline")

/** Service reported a human-verification page (Cloudflare challenge, Google "sorry", HTTP 429). */
internal class UnlockChallenge : IOException("challenge")

/**
 * The view a detector gets: same transport, one shared deadline for all of the check's requests,
 * and redirects followed by hand (so a region in a redirect path is never lost).
 */
internal class UnlockHttp(private val transport: UnlockTransport, private val deadlineNanos: Long) {
    fun remainingMs(): Int = ((deadlineNanos - System.nanoTime()) / 1_000_000).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

    fun get(
        url: String,
        headers: Map<String, String> = emptyMap(),
        bodyLimit: Int = 64 * 1024,
        stopAt: List<String> = emptyList(),
        follow: Boolean = false,
    ): UnlockReply {
        var current = url
        var hops = 0
        while (true) {
            val left = remainingMs()
            if (left < 200) throw UnlockDeadline()
            val reply = transport.fetch(UnlockRequest(current, NetworkUnlock.baseHeaders + headers, bodyLimit, stopAt), left)
            val next = reply.location
            if (!follow || reply.code !in 300..399 || next.isNullOrBlank() || hops >= MAX_REDIRECTS) return reply
            current = try { URI(current).resolve(next.trim()).toString() } catch (_: Exception) { return reply }
            hops++
        }
    }

    companion object { const val MAX_REDIRECTS = 5 }
}

/** The production transport: [HttpURLConnection] on the system network, no explicit proxy. */
internal object SystemUnlockTransport : UnlockTransport {
    /** Closes a connection still open at its deadline, so a stalled socket cannot outlive the budget. */
    private val reaper = Executors.newSingleThreadScheduledExecutor { task -> Thread(task, "hetu-unlock-reaper").apply { isDaemon = true } }

    override fun fetch(request: UnlockRequest, timeoutMs: Int): UnlockReply {
        val connection = URL(request.url).openConnection() as HttpURLConnection
        val reap = reaper.schedule({ connection.disconnect() }, timeoutMs.toLong().coerceAtLeast(1L), TimeUnit.MILLISECONDS)
        try {
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.connectTimeout = timeoutMs.coerceAtLeast(1)
            connection.readTimeout = timeoutMs.coerceAtLeast(1)
            request.headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
            val started = System.nanoTime()
            val code = connection.responseCode
            val headers = HashMap<String, List<String>>()
            connection.headerFields.forEach { (name, values) -> if (name != null) headers[name.lowercase(Locale.ROOT)] = values.orEmpty() }
            val body = if (request.bodyLimit <= 0) "" else {
                val stream = try { if (code < 400) connection.inputStream else connection.errorStream } catch (_: IOException) { connection.errorStream }
                stream?.use { readBounded(it, request.bodyLimit, request.stopAt, started + timeoutMs * 1_000_000L) }.orEmpty()
            }
            return UnlockReply(code, headers, body, request.url)
        } finally {
            reap.cancel(false)
            connection.disconnect()
        }
    }

    /** Reads at most [limit] bytes, stopping at the first [stopAt] marker or when the budget is spent. */
    internal fun readBounded(input: java.io.InputStream, limit: Int, stopAt: List<String>, deadlineNanos: Long): String {
        val out = ByteArrayOutputStream(minOf(limit, 64 * 1024))
        val buffer = ByteArray(16 * 1024)
        var scanned = 0
        val longest = stopAt.maxOfOrNull { it.length } ?: 0
        while (out.size() < limit) {
            if (System.nanoTime() > deadlineNanos) throw UnlockDeadline()
            val n = input.read(buffer, 0, minOf(buffer.size, limit - out.size()))
            if (n < 0) break
            out.write(buffer, 0, n)
            if (stopAt.isNotEmpty()) {
                // Re-scan only the new bytes plus an overlap long enough for a marker split across reads.
                val text = out.toString(Charsets.UTF_8.name())
                val from = (scanned - longest).coerceAtLeast(0)
                if (stopAt.any { text.indexOf(it, from) >= 0 }) return text
                scanned = text.length
            }
        }
        return out.toString(Charsets.UTF_8.name())
    }
}

/** One service's check; [run] may make several requests, all inside one budget. */
internal class UnlockCheck(val id: String, val run: (UnlockHttp) -> UnlockResult)

/** Overseas or domestic exit as reported by an IP service. */
internal data class ExitIp(val ip: String, val region: String?, val place: String = "", val org: String = "")

internal data class ExitIps(val overseas: ExitIp?, val domestic: ExitIp?, val overseasError: String = "", val domesticError: String = "")

internal object NetworkUnlock {
    const val TIMEOUT_MS = 8_000L
    /** Checks in flight at once; each makes one to three requests. */
    const val PARALLEL = 6

    val baseHeaders: Map<String, String> = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140 Mobile Safari/537.36",
        "Accept-Language" to "en-US,en;q=0.9",
        "Cache-Control" to "no-cache",
        "Connection" to "close",
    )

    /** Regions OpenAI, Anthropic and Google Gemini do not serve (same list the latency rows use). */
    val aiUnsupported: Set<String> = setOf("CN", "HK", "MO", "RU", "BY", "IR", "KP", "SY", "CU")

    /* ---------------- shared parsing ---------------- */

    fun failure(error: Throwable): String = when (error) {
        is UnlockDeadline -> "超时"
        is UnlockChallenge -> "需人机验证"
        is SocketTimeoutException -> "超时"
        is UnknownHostException -> "解析失败"
        is ConnectException -> "连接被拒绝"
        is SSLException -> "TLS 失败"
        is IOException -> "连接失败"
        else -> "失败"
    }

    fun code2(value: String?): String? = value?.trim()?.uppercase(Locale.ROOT)?.takeIf { it.length == 2 && it.all { c -> c in 'A'..'Z' } && it != "XX" }

    private val iso3: Map<String, String> by lazy {
        Locale.getISOCountries().mapNotNull { code ->
            try { Locale("", code).isO3Country.uppercase(Locale.ROOT) to code } catch (_: Exception) { null }
        }.toMap()
    }

    /** USA → US; null for anything that is not a known alpha-3 code. */
    fun fromIso3(value: String?): String? = value?.trim()?.uppercase(Locale.ROOT)?.let { iso3[it] }

    /** First JSON string value of [key] anywhere in [body] (no full parse: pages embed JSON in HTML). */
    fun jsonString(body: String, key: String): String? {
        val match = Regex("\"" + Regex.escape(key) + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").find(body) ?: return null
        return unescape(match.groupValues[1])
    }

    fun jsonNumber(body: String, key: String): Long? =
        Regex("\"" + Regex.escape(key) + "\"\\s*:\\s*(-?\\d+)").find(body)?.groupValues?.get(1)?.toLongOrNull()

    private fun unescape(raw: String): String {
        if ('\\' !in raw) return raw
        val out = StringBuilder(raw.length)
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            if (c != '\\' || i + 1 >= raw.length) { out.append(c); i++; continue }
            when (val n = raw[i + 1]) {
                'u' -> if (i + 5 < raw.length) { raw.substring(i + 2, i + 6).toIntOrNull(16)?.let { out.append(it.toChar()) }; i += 6; continue } else out.append(n)
                'x' -> if (i + 3 < raw.length) { raw.substring(i + 2, i + 4).toIntOrNull(16)?.let { out.append(it.toChar()) }; i += 4; continue } else out.append(n)
                'n' -> out.append('\n')
                't' -> out.append('\t')
                else -> out.append(n)
            }
            i += 2
        }
        return out.toString()
    }

    /** Country of a Cloudflare trace body: `loc=` when the body really is a trace (has colo= or fl=). */
    fun traceField(body: String, field: String): String? {
        val lines = body.lineSequence().mapNotNull { line ->
            val at = line.indexOf('=')
            if (at <= 0) null else line.substring(0, at).trim() to line.substring(at + 1).trim()
        }.toMap()
        if (!lines.containsKey("colo") && !lines.containsKey("fl")) return null
        return lines[field]?.takeIf { it.isNotBlank() }
    }

    /** Country headers some CDNs add for the requesting client. */
    private val countryHeaders = listOf("x-aka-user-geo", "region", "physical-location", "cf-ipcountry", "x-country-code", "x-geo-country", "x-client-country", "x-vercel-ip-country")

    fun headerRegion(reply: UnlockReply): String? = countryHeaders.firstNotNullOfOrNull { name -> code2(reply.header(name)?.substringBefore(',')) }

    /** `name=VALUE` from any Set-Cookie header. */
    fun cookie(reply: UnlockReply, name: String): String? = reply.headerValues("set-cookie").firstNotNullOfOrNull { header ->
        header.split(';').firstNotNullOfOrNull { part ->
            val at = part.indexOf('=')
            if (at > 0 && part.substring(0, at).trim() == name) part.substring(at + 1).trim() else null
        }
    }

    /** Region segment of a localized path: /us/…, /hk-en/…, /en-gb/ is ignored unless it names a country first. */
    fun pathRegion(url: String?): String? {
        val path = try { URI(url ?: return null).path } catch (_: Exception) { return null } ?: return null
        val first = path.trim('/').substringBefore('/')
        val match = Regex("^([a-zA-Z]{2})(?:-[a-zA-Z]{2,4})?$").find(first) ?: return null
        return code2(match.groupValues[1])
    }

    /** Azure-style edge regions (GitHub's x-github-edge-region) → country. */
    private val azureRegions: Map<String, String> = mapOf(
        "eastus" to "US", "eastus2" to "US", "westus" to "US", "westus2" to "US", "westus3" to "US", "centralus" to "US",
        "northcentralus" to "US", "southcentralus" to "US", "westcentralus" to "US",
        "canadacentral" to "CA", "canadaeast" to "CA", "brazilsouth" to "BR",
        "northeurope" to "IE", "westeurope" to "NL", "uksouth" to "GB", "ukwest" to "GB", "francecentral" to "FR",
        "germanywestcentral" to "DE", "swedencentral" to "SE", "switzerlandnorth" to "CH", "norwayeast" to "NO", "polandcentral" to "PL", "italynorth" to "IT",
        "eastasia" to "HK", "southeastasia" to "SG", "japaneast" to "JP", "japanwest" to "JP", "koreacentral" to "KR", "koreasouth" to "KR",
        "centralindia" to "IN", "southindia" to "IN", "westindia" to "IN", "australiaeast" to "AU", "australiasoutheast" to "AU",
        "uaenorth" to "AE", "southafricanorth" to "ZA",
    )

    fun azureRegion(value: String?): String? = value?.trim()?.lowercase(Locale.ROOT)?.let { azureRegions[it] }

    /** Chinese country names domestic IP services return → alpha-2; unknown names are kept as text by callers. */
    private val chineseCountries: Map<String, String> = mapOf(
        "中国" to "CN", "香港" to "HK", "中国香港" to "HK", "澳门" to "MO", "中国澳门" to "MO", "台湾" to "TW", "中国台湾" to "TW",
        "日本" to "JP", "韩国" to "KR", "新加坡" to "SG", "美国" to "US", "英国" to "GB", "德国" to "DE", "法国" to "FR",
        "加拿大" to "CA", "澳大利亚" to "AU", "俄罗斯" to "RU", "印度" to "IN", "马来西亚" to "MY", "泰国" to "TH",
        "越南" to "VN", "菲律宾" to "PH", "印度尼西亚" to "ID", "荷兰" to "NL", "土耳其" to "TR", "阿联酋" to "AE",
    )

    fun fromChinese(name: String?): String? = name?.trim()?.let { chineseCountries[it] }

    private fun challenged(reply: UnlockReply): Boolean =
        reply.code == 429 || reply.header("cf-mitigated").equals("challenge", ignoreCase = true) ||
            (reply.url.contains("sorry.google.com") || reply.url.contains("/sorry/"))

    private fun httpFailure(reply: UnlockReply) = "HTTP ${reply.code}"

    /* ---------------- detectors ---------------- */

    /** Non-original title: available only where the catalogue is licensed. Original: everywhere Netflix runs. */
    const val NETFLIX_LICENSED_TITLE = "70143836"
    const val NETFLIX_ORIGINAL_TITLE = "81280792"
    private val netflixCountry = Regex("\"requestCountry\"\\s*:\\s*\\{[^}]*?\"id\"\\s*:\\s*\"([A-Za-z]{2})\"")

    fun netflix(http: UnlockHttp): UnlockResult {
        val licensed = http.get("https://www.netflix.com/title/$NETFLIX_LICENSED_TITLE", bodyLimit = 2_500_000, stopAt = listOf("\"requestCountry\""), follow = true)
        val region = (netflixCountry.find(licensed.body)?.groupValues?.get(1)?.let(::code2)) ?: pathRegion(licensed.url)
        if (licensed.code == 200) return UnlockResult("netflix", UnlockState.Unlocked, region)
        if (licensed.code == 403) return UnlockResult("netflix", UnlockState.Unavailable, region, "拒绝访问")
        if (licensed.code != 404) return UnlockResult("netflix", UnlockState.Failed, region, httpFailure(licensed))
        val original = http.get("https://www.netflix.com/title/$NETFLIX_ORIGINAL_TITLE", bodyLimit = 0, follow = true)
        val where = region ?: pathRegion(original.url)
        return when (original.code) {
            200 -> UnlockResult("netflix", UnlockState.Restricted, where, "仅自制剧")
            403, 404 -> UnlockResult("netflix", UnlockState.Unavailable, where)
            else -> UnlockResult("netflix", UnlockState.Failed, where, httpFailure(original))
        }
    }

    private val youtubeGl = listOf("\"INNERTUBE_CONTEXT_GL\":\"", "\"countryCode\":\"")

    fun youtube(http: UnlockHttp): UnlockResult {
        val reply = http.get("https://www.youtube.com/premium", bodyLimit = 1_200_000, stopAt = listOf("\"INNERTUBE_CONTEXT_GL\""), follow = true)
        if (challenged(reply)) return UnlockResult("youtube", UnlockState.Failed, null, "需人机验证")
        if (reply.code != 200) return UnlockResult("youtube", UnlockState.Failed, null, httpFailure(reply))
        val region = code2(jsonString(reply.body, "INNERTUBE_CONTEXT_GL")) ?: code2(jsonString(reply.body, "countryCode"))
        if (reply.body.contains("Premium is not available in your country", ignoreCase = true)) return UnlockResult("youtube", UnlockState.Unavailable, region)
        return if (region != null) UnlockResult("youtube", UnlockState.Unlocked, region, "Premium")
        else UnlockResult("youtube", UnlockState.Failed, null, "未返回地区")
    }

    fun disney(http: UnlockHttp): UnlockResult {
        val reply = http.get("https://www.disneyplus.com/", bodyLimit = 0, follow = true)
        val region = headerRegion(reply) ?: pathRegion(reply.url)
        val lost = reply.url.lowercase(Locale.ROOT).let { "unavailable" in it || "unsupported" in it || "not-available" in it }
        return when {
            lost || reply.code == 403 || reply.code == 451 -> UnlockResult("disney", UnlockState.Unavailable, region)
            reply.code in 200..299 && region != null -> UnlockResult("disney", UnlockState.Unlocked, region)
            reply.code in 200..299 -> UnlockResult("disney", UnlockState.Failed, null, "未返回地区")
            else -> UnlockResult("disney", UnlockState.Failed, region, httpFailure(reply))
        }
    }

    private fun traceRegion(http: UnlockHttp, host: String): Pair<String?, Throwable?> = try {
        val reply = http.get("https://$host/cdn-cgi/trace", bodyLimit = 4_096)
        (if (reply.code == 200) code2(traceField(reply.body, "loc")) else null) to null
    } catch (error: IOException) {
        null to error
    }

    fun chatgpt(http: UnlockHttp): UnlockResult {
        val (region, traceError) = traceRegion(http, "chatgpt.com")
        val compliance = try {
            http.get("https://api.openai.com/compliance/cookie_requirements", mapOf("Authorization" to "Bearer null"), bodyLimit = 8_192)
        } catch (error: IOException) {
            if (region == null) return UnlockResult("chatgpt", UnlockState.Failed, null, failure(traceError ?: error))
            null
        }
        return when {
            compliance != null && compliance.body.contains("unsupported_country") -> UnlockResult("chatgpt", UnlockState.Unavailable, region)
            region != null && region in aiUnsupported -> UnlockResult("chatgpt", UnlockState.Unavailable, region)
            region != null -> UnlockResult("chatgpt", UnlockState.Unlocked, region)
            compliance != null && compliance.code == 200 -> UnlockResult("chatgpt", UnlockState.Unlocked, null)
            compliance != null -> UnlockResult("chatgpt", UnlockState.Failed, null, httpFailure(compliance))
            else -> UnlockResult("chatgpt", UnlockState.Failed, null, failure(traceError ?: IOException()))
        }
    }

    fun claude(http: UnlockHttp): UnlockResult {
        val (region, traceError) = traceRegion(http, "claude.ai")
        val home = try { http.get("https://claude.ai/", bodyLimit = 0) } catch (error: IOException) {
            if (region == null) return UnlockResult("claude", UnlockState.Failed, null, failure(traceError ?: error))
            null
        }
        val refused = home?.location?.contains("unavailable-in-region") == true
        return when {
            refused -> UnlockResult("claude", UnlockState.Unavailable, region)
            region != null && region in aiUnsupported -> UnlockResult("claude", UnlockState.Unavailable, region)
            region != null -> UnlockResult("claude", UnlockState.Unlocked, region)
            home != null && challenged(home) -> UnlockResult("claude", UnlockState.Failed, null, "需人机验证")
            else -> UnlockResult("claude", UnlockState.Failed, null, home?.let(::httpFailure) ?: "未返回地区")
        }
    }

    private val geminiRegion = Regex(",2,1,200,\"([A-Z]{3})\"")

    fun gemini(http: UnlockHttp): UnlockResult {
        val reply = http.get("https://gemini.google.com/", bodyLimit = 1_500_000, stopAt = listOf(",2,1,200,\""), follow = true)
        if (challenged(reply)) return UnlockResult("gemini", UnlockState.Failed, null, "需人机验证")
        if (reply.code != 200) return UnlockResult("gemini", UnlockState.Failed, null, httpFailure(reply))
        val region = fromIso3(geminiRegion.find(reply.body)?.groupValues?.get(1))
        val text = reply.body
        val refused = text.contains("not supported in your country", true) || text.contains("isn't currently supported in your country", true) ||
            text.contains("not available in your country", true)
        return when {
            refused -> UnlockResult("gemini", UnlockState.Unavailable, region)
            region != null && region in aiUnsupported -> UnlockResult("gemini", UnlockState.Unavailable, region)
            region != null -> UnlockResult("gemini", UnlockState.Unlocked, region)
            else -> UnlockResult("gemini", UnlockState.Failed, null, "未返回地区")
        }
    }

    fun tiktok(http: UnlockHttp): UnlockResult {
        val reply = http.get("https://www.tiktok.com/", bodyLimit = 600_000, stopAt = listOf("\"region\":\""), follow = true)
        val region = code2(jsonString(reply.body, "region")) ?: headerRegion(reply)
        return when {
            reply.code == 403 || reply.code == 451 -> UnlockResult("tiktok", UnlockState.Unavailable, region)
            challenged(reply) -> UnlockResult("tiktok", UnlockState.Failed, region, "需人机验证")
            reply.code in 200..299 && region != null -> UnlockResult("tiktok", UnlockState.Unlocked, region)
            reply.code in 200..299 -> UnlockResult("tiktok", UnlockState.Failed, null, "未返回地区")
            else -> UnlockResult("tiktok", UnlockState.Failed, region, httpFailure(reply))
        }
    }

    /** Bangumi episodes licensed for 港澳台 together and for 台湾 alone; the play-url API answers -10403 elsewhere. */
    const val BILIBILI_HKMOTW_EP = "183799"
    const val BILIBILI_TW_EP = "307247"

    private fun bilibiliPlayable(http: UnlockHttp, ep: String): Boolean? {
        val reply = http.get("https://api.bilibili.com/pgc/player/web/playurl?ep_id=$ep&fnval=16", mapOf("Referer" to "https://www.bilibili.com/"), bodyLimit = 32_768)
        return when (jsonNumber(reply.body, "code")) {
            0L -> true
            -10403L -> false
            else -> null
        }
    }

    fun bilibili(http: UnlockHttp): UnlockResult {
        val zone = try { http.get("https://api.bilibili.com/x/web-interface/zone", bodyLimit = 8_192) } catch (_: IOException) { null }
        val country = zone?.body?.let { jsonString(it, "country") }?.takeIf { it.isNotBlank() }
        val region = fromChinese(country) ?: country
        val area = bilibiliPlayable(http, BILIBILI_HKMOTW_EP)
        val taiwan = bilibiliPlayable(http, BILIBILI_TW_EP)
        return when {
            area == true && taiwan == true -> UnlockResult("bilibili", UnlockState.Unlocked, region, "港澳台")
            area == true -> UnlockResult("bilibili", UnlockState.Restricted, region, "仅港澳")
            taiwan == true -> UnlockResult("bilibili", UnlockState.Restricted, region, "仅台湾")
            area == false && taiwan == false -> UnlockResult("bilibili", UnlockState.Unavailable, region, "港澳台")
            else -> UnlockResult("bilibili", UnlockState.Failed, region, "接口异常")
        }
    }

    fun apple(http: UnlockHttp): UnlockResult {
        val reply = http.get("https://gspe1-ssl.ls.apple.com/pep/gcc", bodyLimit = 256)
        val region = code2(reply.body.trim())
        return when {
            reply.code == 200 && region != null -> UnlockResult("apple", UnlockState.Unlocked, region)
            reply.code == 200 -> UnlockResult("apple", UnlockState.Failed, null, "未返回地区")
            else -> UnlockResult("apple", UnlockState.Failed, null, httpFailure(reply))
        }
    }

    fun spotify(http: UnlockHttp): UnlockResult {
        val reply = http.get("https://www.spotify.com/signup", bodyLimit = 200_000, stopAt = listOf("in your country"))
        val target = reply.location?.let { location -> try { URI("https://www.spotify.com/signup").resolve(location.trim()).toString() } catch (_: Exception) { null } }
        val region = pathRegion(target)
        val refused = reply.body.contains("not available in your country", true) || reply.body.contains("isn't available in your country", true) ||
            target?.contains("select-your-country", true) == true || reply.code == 403 || reply.code == 451
        return when {
            refused -> UnlockResult("spotify", UnlockState.Unavailable, region)
            reply.code in 300..399 && region != null -> UnlockResult("spotify", UnlockState.Unlocked, region)
            reply.code == 200 -> UnlockResult("spotify", UnlockState.Unlocked, null)
            else -> UnlockResult("spotify", UnlockState.Failed, region, httpFailure(reply))
        }
    }

    /** A paid game whose price Steam quotes in the store currency of the requesting region. */
    const val STEAM_PRICED_APP = "1086940"

    fun steam(http: UnlockHttp): UnlockResult {
        val store = http.get("https://store.steampowered.com/", bodyLimit = 0)
        val region = code2(cookie(store, "steamCountry")?.substringBefore('%')?.substringBefore('|'))
        val currency = try {
            val price = http.get("https://store.steampowered.com/api/appdetails?appids=$STEAM_PRICED_APP&filters=price_overview", bodyLimit = 8_192)
            jsonString(price.body, "currency")?.takeIf { it.length == 3 }
        } catch (_: IOException) { null }
        return when {
            store.code == 403 || store.code == 451 -> UnlockResult("steam", UnlockState.Unavailable, region)
            region != null -> UnlockResult("steam", UnlockState.Unlocked, region, currency.orEmpty())
            store.code in 200..399 -> UnlockResult("steam", UnlockState.Unlocked, null, currency.orEmpty())
            else -> UnlockResult("steam", UnlockState.Failed, null, httpFailure(store))
        }
    }

    /** Reachable services that only tell where they think you are, through a header or cookie. */
    private fun headerOnly(id: String, url: String, http: UnlockHttp, region: (UnlockReply) -> String?): UnlockResult {
        val reply = http.get(url, bodyLimit = 0)
        val where = region(reply) ?: headerRegion(reply)
        return when {
            reply.code == 403 || reply.code == 451 -> UnlockResult(id, UnlockState.Unavailable, where)
            challenged(reply) -> UnlockResult(id, UnlockState.Failed, where, "需人机验证")
            reply.code in 200..399 -> UnlockResult(id, UnlockState.Unlocked, where)
            else -> UnlockResult(id, UnlockState.Failed, where, httpFailure(reply))
        }
    }

    fun github(http: UnlockHttp): UnlockResult =
        headerOnly("github", "https://github.com/", http) { azureRegion(it.header("x-github-edge-region")) }

    fun twitch(http: UnlockHttp): UnlockResult =
        headerOnly("twitch", "https://www.twitch.tv/", http) { code2(cookie(it, "twitch.lohp.countryCode")) }

    /** Hulu streams in the United States only. */
    fun hulu(http: UnlockHttp): UnlockResult {
        val reply = http.get("https://www.hulu.com/", bodyLimit = 0)
        val region = headerRegion(reply)
        val lost = reply.location?.lowercase(Locale.ROOT)?.let { "unavailable" in it || "not-available" in it || "unsupported" in it } == true
        return when {
            lost || reply.code == 403 || reply.code == 451 -> UnlockResult("hulu", UnlockState.Unavailable, region)
            reply.code !in 200..399 -> UnlockResult("hulu", UnlockState.Failed, region, httpFailure(reply))
            region == "US" -> UnlockResult("hulu", UnlockState.Unlocked, region)
            region != null -> UnlockResult("hulu", UnlockState.Unavailable, region, "仅限美国")
            else -> UnlockResult("hulu", UnlockState.Failed, null, "未返回地区")
        }
    }

    val checks: List<UnlockCheck> = listOf(
        UnlockCheck("chatgpt", ::chatgpt), UnlockCheck("claude", ::claude), UnlockCheck("gemini", ::gemini),
        UnlockCheck("tiktok", ::tiktok),
        UnlockCheck("bilibili", ::bilibili), UnlockCheck("disney", ::disney), UnlockCheck("hulu", ::hulu),
        UnlockCheck("netflix", ::netflix), UnlockCheck("spotify", ::spotify), UnlockCheck("twitch", ::twitch), UnlockCheck("youtube", ::youtube),
        UnlockCheck("apple", ::apple), UnlockCheck("github", ::github), UnlockCheck("steam", ::steam),
    )

    val checkIds: Set<String> get() = checks.mapTo(HashSet()) { it.id }

    /**
     * Runs one check inside its own [timeoutMs] budget: every request shares the deadline, and the
     * coroutine returns at the deadline even if a socket is still stuck. Never throws except cancellation.
     */
    suspend fun runOne(check: UnlockCheck, transport: UnlockTransport, timeoutMs: Long = TIMEOUT_MS): UnlockResult {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        val result = withTimeoutOrNull(timeoutMs) {
            runInterruptible(Dispatchers.IO) {
                try {
                    check.run(UnlockHttp(transport, deadline))
                } catch (error: Exception) {
                    UnlockResult(check.id, UnlockState.Failed, null, failure(error))
                }
            }
        }
        return result ?: UnlockResult(check.id, UnlockState.Failed, null, "超时")
    }

    /** Emits each check the moment it finishes; at most [parallel] run at once. */
    fun run(transport: UnlockTransport = SystemUnlockTransport, list: List<UnlockCheck> = checks, parallel: Int = PARALLEL, timeoutMs: Long = TIMEOUT_MS): Flow<UnlockResult> = channelFlow {
        val gate = Semaphore(parallel)
        list.forEach { check -> launch { send(gate.withPermit { runOne(check, transport, timeoutMs) }) } }
    }.flowOn(Dispatchers.IO)

    /* ---------------- exit IPs ---------------- */

    /** Overseas exit: Cloudflare trace for IP and country, ip.sb for place and network (optional). */
    fun overseasExit(http: UnlockHttp, detail: UnlockHttp = http): ExitIp {
        val trace = http.get("https://www.cloudflare.com/cdn-cgi/trace", bodyLimit = 4_096)
        val ip = traceField(trace.body, "ip") ?: throw IOException("trace")
        val region = code2(traceField(trace.body, "loc"))
        val geo = try { detail.get("https://api.ip.sb/geoip", bodyLimit = 8_192).body } catch (_: IOException) { "" }
        val sameIp = jsonString(geo, "ip") == ip
        val place = if (sameIp) listOfNotNull(jsonString(geo, "city"), jsonString(geo, "region")).filter { it.isNotBlank() }.distinct().joinToString(" ") else ""
        val org = if (sameIp) (jsonString(geo, "asn_organization") ?: jsonString(geo, "organization")).orEmpty() else ""
        return ExitIp(ip, region, place, org)
    }

    /** Domestic exit: NetEase locate, else Tencent ip2city. Both report in Chinese. */
    fun domesticExit(http: UnlockHttp): ExitIp {
        val netease = try { http.get("https://ipservice.ws.126.net/locate/api/getLocByIp", bodyLimit = 8_192).body } catch (_: IOException) { "" }
        jsonString(netease, "ip")?.takeIf { it.isNotBlank() }?.let { ip ->
            val country = jsonString(netease, "country")
            val region = code2(jsonString(netease, "countrySymbol")) ?: fromChinese(country)
            val place = listOfNotNull(jsonString(netease, "province"), jsonString(netease, "city")).filter { it.isNotBlank() }.distinct().joinToString(" ")
            return ExitIp(ip, region, place, jsonString(netease, "operator").orEmpty())
        }
        val qq = http.get("https://r.inews.qq.com/api/ip2city", bodyLimit = 8_192).body
        val ip = jsonString(qq, "ip")?.takeIf { it.isNotBlank() } ?: throw IOException("ip2city")
        val country = jsonString(qq, "country")
        val place = listOfNotNull(jsonString(qq, "province"), jsonString(qq, "city")).filter { it.isNotBlank() }.distinct().joinToString(" ")
        return ExitIp(ip, fromChinese(country) ?: country?.takeIf { it.isNotBlank() }, place, jsonString(qq, "isp").orEmpty())
    }

    /** Both exits at once, each in its own budget; a failure leaves its side null with the reason. */
    suspend fun exits(transport: UnlockTransport = SystemUnlockTransport, timeoutMs: Long = TIMEOUT_MS): ExitIps = coroutineScope {
        suspend fun side(block: (UnlockHttp) -> ExitIp): Pair<ExitIp?, String> {
            val deadline = System.nanoTime() + timeoutMs * 1_000_000
            return withTimeoutOrNull(timeoutMs) {
                runInterruptible(Dispatchers.IO) {
                    try { block(UnlockHttp(transport, deadline)) to "" } catch (error: Exception) { null to failure(error) }
                }
            } ?: (null to "超时")
        }
        val overseas = async { side { overseasExit(it) } }
        val domestic = async { side { domesticExit(it) } }
        val o = overseas.await()
        val d = domestic.await()
        ExitIps(o.first, d.first, o.second, d.second)
    }
}

/** Joins row state: the latency row and the unlock check of the same service finish independently. */
internal fun mergeUnlockRegion(traceRegion: String?, unlock: UnlockResult?): String? = unlock?.region ?: traceRegion
