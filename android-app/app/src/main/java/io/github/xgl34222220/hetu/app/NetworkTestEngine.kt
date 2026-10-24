package io.github.xgl34222220.hetu

import android.content.Context
import java.io.EOFException
import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URL
import java.net.UnknownHostException
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.SSLException
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONObject

/* ------------------------------------------------------------------ */
/*  网络测试 · 连通性                                                    */
/* ------------------------------------------------------------------ */

internal enum class NetCategory(val title: String) { Ai("AI 服务"), Social("社交媒体"), Media("影音娱乐"), Tools("工具与服务") }

/**
 * One service of 网络测试. [brand] names a bundled full-colour mark (null draws [monogram] on a [color] tile);
 * [trace] asks the service's own host for Cloudflare's `/cdn-cgi/trace`, which reports the country
 * of the exit IP the request actually left from. [aiRegion] marks services that refuse some regions.
 */
internal data class NetSite(
    val id: String,
    val name: String,
    val category: NetCategory,
    val url: String,
    val brand: String? = null,
    val color: Long = 0xFF6B7280L,
    val monogram: String = name.take(1),
    val trace: Boolean = true,
    val aiRegion: Boolean = false,
) {
    val host: String get() = URI(url).host.orEmpty().lowercase(Locale.ROOT)
    val traceUrl: String? get() = when {
        url.endsWith("/cdn-cgi/trace") -> null
        trace -> "https://$host/cdn-cgi/trace"
        else -> null
    }
}

/** How the exit region of a row was established; every source is a real observation. */
internal enum class NetRegionSource { Trace, SameNode, Direct }

/**
 * [millis] is time to the response line (DNS + connect + TLS + first byte), -1 when it failed.
 * [blocked] is a reachable service that refused this exit (HTTP 403/451, or an AI service in a
 * region it does not serve). [route] is the core's policy chain for the host, when its log has it.
 */
internal data class NetSiteResult(
    val id: String,
    val ok: Boolean,
    val millis: Long,
    val code: Int,
    val error: String = "",
    val region: String? = null,
    val regionSource: NetRegionSource? = null,
    val route: String? = null,
    val blocked: Boolean = false,
)

internal object NetworkTest {
    const val TIMEOUT_MS = 8_000
    /** Requests in flight at once: every row still starts within the first wave, the core is not flooded. */
    const val PARALLEL = 14

    val sites: List<NetSite> = listOf(
        NetSite("chatgpt", "ChatGPT", NetCategory.Ai, "https://chatgpt.com/cdn-cgi/trace", "openai", NetBrandIcons.colors.getValue("openai"), aiRegion = true),
        NetSite("claude", "Claude", NetCategory.Ai, "https://claude.ai/cdn-cgi/trace", "claude", NetBrandIcons.colors.getValue("claude"), aiRegion = true),
        NetSite("doubao", "Doubao", NetCategory.Ai, "https://www.doubao.com/", "doubao", NetBrandIcons.colors.getValue("doubao"), trace = false),
        NetSite("gemini", "Gemini", NetCategory.Ai, "https://gemini.google.com/", "googlegemini", NetBrandIcons.colors.getValue("googlegemini"), aiRegion = true),
        NetSite("grok", "Grok", NetCategory.Ai, "https://grok.com/", "grok", NetBrandIcons.colors.getValue("grok")),

        NetSite("discord", "Discord", NetCategory.Social, "https://discord.com/cdn-cgi/trace", "discord", NetBrandIcons.colors.getValue("discord")),
        NetSite("douyin", "Douyin", NetCategory.Social, "https://www.douyin.com/", "tiktok", 0xFF111111L, trace = false),
        NetSite("reddit", "Reddit", NetCategory.Social, "https://www.reddit.com/", "reddit", NetBrandIcons.colors.getValue("reddit")),
        NetSite("tiktok", "TikTok", NetCategory.Social, "https://www.tiktok.com/", "tiktok", NetBrandIcons.colors.getValue("tiktok")),
        NetSite("x", "X", NetCategory.Social, "https://x.com/", "x", NetBrandIcons.colors.getValue("x")),
        NetSite("telegram", "Telegram", NetCategory.Social, "https://web.telegram.org/", "telegram", NetBrandIcons.colors.getValue("telegram")),

        NetSite("appletv", "Apple TV+", NetCategory.Media, "https://tv.apple.com/", "appletv", NetBrandIcons.colors.getValue("appletv")),
        NetSite("bilibili", "Bilibili", NetCategory.Media, "https://www.bilibili.com/", "bilibili", NetBrandIcons.colors.getValue("bilibili"), trace = false),
        NetSite("disney", "Disney+", NetCategory.Media, "https://www.disneyplus.com/", "disneyplus", NetBrandIcons.colors.getValue("disneyplus")),
        NetSite("hulu", "Hulu", NetCategory.Media, "https://www.hulu.com/", "hulu", NetBrandIcons.colors.getValue("hulu")),
        NetSite("netflix", "Netflix", NetCategory.Media, "https://www.netflix.com/", "netflix", NetBrandIcons.colors.getValue("netflix")),
        NetSite("spotify", "Spotify", NetCategory.Media, "https://open.spotify.com/", "spotify", NetBrandIcons.colors.getValue("spotify")),
        NetSite("twitch", "Twitch", NetCategory.Media, "https://www.twitch.tv/", "twitch", NetBrandIcons.colors.getValue("twitch")),
        NetSite("youtube", "YouTube", NetCategory.Media, "https://www.youtube.com/generate_204", "youtube", NetBrandIcons.colors.getValue("youtube")),

        NetSite("alibaba", "Alibaba", NetCategory.Tools, "https://www.alibaba.com/", "alibabadotcom", NetBrandIcons.colors.getValue("alibabadotcom"), trace = false),
        NetSite("apple", "Apple", NetCategory.Tools, "https://www.apple.com/library/test/success.html", "apple", NetBrandIcons.colors.getValue("apple")),
        NetSite("cloudflare", "Cloudflare", NetCategory.Tools, "https://www.cloudflare.com/cdn-cgi/trace", "cloudflare", NetBrandIcons.colors.getValue("cloudflare")),
        NetSite("github", "GitHub", NetCategory.Tools, "https://github.com/", "github", NetBrandIcons.colors.getValue("github")),
        NetSite("netease", "NetEase", NetCategory.Tools, "https://www.163.com/", null, 0xFFE60012L, "网易", trace = false),
        NetSite("paypal", "PayPal", NetCategory.Tools, "https://www.paypal.com/", "paypal", NetBrandIcons.colors.getValue("paypal")),
        NetSite("steam", "Steam", NetCategory.Tools, "https://store.steampowered.com/", "steam", NetBrandIcons.colors.getValue("steam")),
        NetSite("tencent", "Tencent", NetCategory.Tools, "https://www.qq.com/", "qq", NetBrandIcons.colors.getValue("qq"), trace = false),
        NetSite("wikipedia", "Wikipedia", NetCategory.Tools, "https://www.wikipedia.org/", "wikipedia", NetBrandIcons.colors.getValue("wikipedia")),
        NetSite("google", "Google", NetCategory.Tools, "https://www.google.com/generate_204", "google", NetBrandIcons.colors.getValue("google")),
        NetSite("baidu", "Baidu", NetCategory.Tools, "https://www.baidu.com/", "baidu", NetBrandIcons.colors.getValue("baidu"), trace = false),
    )

    /** Regions the AI services above do not serve; a known exit in one of them reads 未解锁. */
    val aiUnsupported = setOf("CN", "HK", "MO", "RU", "BY", "IR", "KP", "SY", "CU")

    /** Any HTTP answer below 500 proves the path works (a CDN 403 is still the site); 5xx is the proxy or site failing. */
    fun reachable(code: Int): Boolean = code in 200..499

    /** 403/451 from the service itself: reachable, but this exit is refused. */
    fun refused(code: Int): Boolean = code == 403 || code == 451

    fun failureText(error: Throwable): String = when (error) {
        is SocketTimeoutException -> "超时"
        is UnknownHostException -> "解析失败"
        is ConnectException -> "连接被拒绝"
        is SSLException -> "TLS 失败"
        is IOException -> "连接失败"
        else -> "失败"
    }

    /** Country of a Cloudflare trace body; null unless it is a real trace (loc plus colo/fl lines). */
    fun parseTrace(body: String): String? {
        val fields = body.lineSequence().mapNotNull { line ->
            val at = line.indexOf('=')
            if (at <= 0) null else line.substring(0, at).trim() to line.substring(at + 1).trim()
        }.toMap()
        if (!fields.containsKey("colo") && !fields.containsKey("fl")) return null
        return fields["loc"]?.uppercase(Locale.ROOT)?.takeIf { it.length == 2 && it.all(Char::isLetter) && it != "XX" }
    }

    /** Newest core-log policy chain per host, e.g. `节点选择[🇯🇵 日本 01]` or `DIRECT`. */
    fun parseRoutes(log: String, hosts: Collection<String>): Map<String, String> {
        val routes = HashMap<String, String>()
        log.lineSequence().forEach { raw ->
            val line = raw.trim().trimEnd('"')
            val arrow = line.indexOf("--> ")
            if (arrow < 0) return@forEach
            val target = line.substring(arrow + 4).substringBefore(' ')
            val host = target.substringBeforeLast(':').lowercase(Locale.ROOT)
            if (host !in hosts) return@forEach
            val using = line.lastIndexOf(" using ")
            if (using < 0) return@forEach
            routes[host] = line.substring(using + 7).trim()
        }
        return routes
    }

    /** The proxy that actually carried the request: Mihomo writes `outer[leaf]`, or the leaf alone. */
    fun leafOf(chain: String): String {
        val open = chain.indexOf('[')
        return if (open >= 0 && chain.endsWith("]")) chain.substring(open + 1, chain.length - 1).trim() else chain.trim()
    }

    fun isDirect(chain: String): Boolean = leafOf(chain).uppercase(Locale.ROOT) == "DIRECT"

    /**
     * Fills regions from real observations only: a row's own Cloudflare trace; otherwise the trace of
     * another row whose request left through the same leaf node; otherwise, for DIRECT (or any row
     * when the proxy is off), the device's own exit. Everything else stays unknown.
     */
    fun resolve(results: List<NetSiteResult>, routes: Map<String, String>, hosts: Map<String, String>, directRegion: String?, viaProxy: Boolean): List<NetSiteResult> {
        val nodeRegion = HashMap<String, String>()
        results.forEach { result ->
            val chain = hosts[result.id]?.let { routes[it] } ?: return@forEach
            if (result.regionSource == NetRegionSource.Trace && result.region != null) nodeRegion.putIfAbsent(leafOf(chain), result.region)
        }
        return results.map { result ->
            val chain = hosts[result.id]?.let { routes[it] }
            val resolved = when {
                result.regionSource == NetRegionSource.Trace && result.region != null -> result
                !viaProxy -> if (directRegion != null) result.copy(region = directRegion, regionSource = NetRegionSource.Direct) else result
                chain == null -> result
                isDirect(chain) -> if (directRegion != null) result.copy(region = directRegion, regionSource = NetRegionSource.Direct) else result
                else -> nodeRegion[leafOf(chain)]?.let { result.copy(region = it, regionSource = NetRegionSource.SameNode) } ?: result
            }
            resolved.copy(route = chain)
        }
    }

    /** 未解锁: the service answered but refused, or it is an AI service at an exit it does not serve. */
    fun blocked(site: NetSite, result: NetSiteResult): Boolean =
        (result.ok && refused(result.code)) || (site.aiRegion && result.ok && result.region != null && result.region in aiUnsupported)

    fun withVerdict(site: NetSite, result: NetSiteResult): NetSiteResult = result.copy(blocked = blocked(site, result))

    /** 🇯🇵 for JP; blank for anything that is not two letters. */
    fun flag(region: String?): String {
        val code = region?.uppercase(Locale.ROOT)?.takeIf { it.length == 2 && it.all { c -> c in 'A'..'Z' } } ?: return ""
        return String(Character.toChars(0x1F1E6 + (code[0] - 'A'))) + String(Character.toChars(0x1F1E6 + (code[1] - 'A')))
    }

    private fun open(url: String, proxyPort: Int?, direct: Boolean = false): HttpURLConnection {
        val target = URL(url)
        val opened = when {
            proxyPort != null -> target.openConnection(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", proxyPort)))
            direct -> target.openConnection(Proxy.NO_PROXY)
            else -> target.openConnection()
        }
        return (opened as HttpURLConnection).apply {
            instanceFollowRedirects = false
            useCaches = false
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            setRequestProperty("Connection", "close")
            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140 Mobile Safari/537.36")
        }
    }

    private fun readSmall(connection: HttpURLConnection, limit: Int = 4_096): String {
        val stream = (if (connection.responseCode < 400) connection.inputStream else connection.errorStream) ?: return ""
        return stream.use { input ->
            val buffer = ByteArray(limit)
            var total = 0
            while (total < limit) {
                val n = input.read(buffer, total, limit - total)
                if (n < 0) break
                total += n
            }
            String(buffer, 0, total, Charsets.UTF_8)
        }
    }

    /** Blocking; call on IO. A fresh connection per request, so every row measures a cold path. */
    fun measure(site: NetSite, proxyPort: Int?): NetSiteResult {
        val started = System.nanoTime()
        return try {
            val connection = open(site.url, proxyPort)
            try {
                val code = connection.responseCode
                val elapsed = ((System.nanoTime() - started) / 1_000_000).coerceAtLeast(1L)
                val region = if (site.url.endsWith("/cdn-cgi/trace") && code == 200) parseTrace(readSmall(connection)) else null
                if (reachable(code)) NetSiteResult(site.id, true, elapsed, code, region = region, regionSource = region?.let { NetRegionSource.Trace })
                else NetSiteResult(site.id, false, -1L, code, "HTTP $code")
            } finally {
                connection.disconnect()
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            NetSiteResult(site.id, false, -1L, -1, failureText(error))
        }
    }

    /** Country reported by Cloudflare for [url]; null when the host is not behind Cloudflare or the request failed. */
    fun trace(url: String, proxyPort: Int?, direct: Boolean = false): String? = try {
        val connection = open(url, proxyPort, direct)
        try { if (connection.responseCode == 200) parseTrace(readSmall(connection)) else null } finally { connection.disconnect() }
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (_: Exception) {
        null
    }

    /** Emits each row the moment its requests finish; the row's own trace runs beside its timed request. */
    fun run(proxyPort: Int?, list: List<NetSite> = sites): Flow<NetSiteResult> = channelFlow {
        val gate = Semaphore(PARALLEL)
        list.forEach { site ->
            launch {
                val result = coroutineScope {
                    val traced = site.traceUrl?.let { url -> async { gate.withPermit { trace(url, proxyPort) } } }
                    val main = gate.withPermit { measure(site, proxyPort) }
                    val region = traced?.await()
                    if (main.ok && main.region == null && region != null) main.copy(region = region, regionSource = NetRegionSource.Trace) else main
                }
                send(withVerdict(site, result))
            }
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Second pass, once every row is in: the core's routing decision per host (from core.log, read
     * once) and the device's own exit for DIRECT rows. Rows whose region cannot be observed stay blank.
     */
    suspend fun regions(context: Context, results: List<NetSiteResult>, proxyPort: Int?, list: List<NetSite> = sites): List<NetSiteResult> = withContext(Dispatchers.IO) {
        val hosts = list.associate { it.id to it.host }
        val directRegion = trace("https://www.cloudflare.com/cdn-cgi/trace", null, direct = true)
        val routes = if (proxyPort == null) emptyMap() else readRoutes(context, hosts.values.toSet())
        val bySite = list.associateBy { it.id }
        resolve(results, routes, hosts, directRegion, proxyPort != null).map { result ->
            bySite[result.id]?.let { withVerdict(it, result) } ?: result
        }
    }

    private fun readRoutes(context: Context, hosts: Set<String>): Map<String, String> = try {
        val patterns = hosts.joinToString(" ") { "-e " + RootBridge.quote("--> $it:") }
        val output = RootBridge.rootShell(context.applicationContext,
            "tail -n 6000 /data/adb/hetu/run/core.log 2>/dev/null | grep -F $patterns | tail -n 600", 5_000L).output.orEmpty()
        parseRoutes(output, hosts)
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (_: Exception) {
        emptyMap()
    }

    /** The core's loopback listener while the Root proxy runs; null means a direct test. */
    fun proxyPort(context: Context): Int? {
        if (!ProxyStatusBridge.rootProxyRunning(context)) return null
        val prefs = context.applicationContext.getSharedPreferences("hetu", Context.MODE_PRIVATE)
        return MihomoStartupConfig.egressProbePort(prefs.getInt("proxyControllerPort", MihomoStartupConfig.CONTROLLER_PORT))
    }
}

/* ------------------------------------------------------------------ */
/*  网络测试 · 网速 (speed.cloudflare.com)                               */
/* ------------------------------------------------------------------ */

internal data class SpeedMeta(val ip: String, val country: String, val city: String, val colo: String, val org: String)

internal enum class SpeedPhase { Idle, Meta, Latency, Download, Upload, Done, Failed }

internal sealed interface SpeedEvent {
    data class Phase(val phase: SpeedPhase) : SpeedEvent
    data class Meta(val meta: SpeedMeta?) : SpeedEvent
    data class Latency(val millis: Long) : SpeedEvent
    /** Live rate of the running phase, sampled every [SpeedTest.SAMPLE_MS]. */
    data class Live(val upload: Boolean, val mbps: Double, val bytes: Long) : SpeedEvent
    data class Finished(val upload: Boolean, val mbps: Double, val bytes: Long) : SpeedEvent
    /** [reason] is a word-table key (Chinese); [args] fill its %s placeholders. */
    data class Failed(val reason: String, val args: List<String> = emptyList()) : SpeedEvent
}

/** A non-2xx answer of the speed server itself (the request reached it). */
internal class SpeedHttpStatus(val code: Int) : IOException("HTTP $code")

/**
 * Cloudflare speed test through the current proxy node: metadata of the exit (`/meta`), idle
 * latency (`__down?bytes=0`), then download (`__down`) and upload (`__up`) for a fixed time with a
 * few parallel streams. Everything runs on IO; cancelling the collection closes the sockets.
 */
internal object SpeedTest {
    const val SAMPLE_MS = 250L
    const val PHASE_MS = 8_000L
    const val STREAMS = 3
    /** Per-request connect/read budget; matches the 8 s budget of every 连通性 check. */
    const val REQUEST_TIMEOUT_MS = 8_000
    /** The latency phase stops sampling once this much time has passed (at least one sample kept). */
    const val LATENCY_BUDGET_MS = 8_000L
    private const val DOWN_CHUNK = 25_000_000L
    internal const val UP_CHUNK = 4_000_000

    fun mbps(bytes: Long, millis: Long): Double = if (millis <= 0) 0.0 else bytes * 8.0 / (millis / 1000.0) / 1_000_000.0

    /** Fallback for the exit card: Cloudflare's own trace over the same proxy path (works when /meta is refused). */
    const val TRACE_FALLBACK = "https://www.cloudflare.com/cdn-cgi/trace"

    const val FAIL_CLOSED_EXIT_KNOWN = "出口正常，但测速站 speed.cloudflare.com 的连接被立即关闭：通常是当前节点（机场）禁止测速，或分流规则把它拒绝（REJECT）。可换个节点再试"
    const val FAIL_CLOSED = "连接被立即关闭，出口也查询不到：当前节点可能不可用，或分流规则拒绝了这些连接。请换个节点再试"
    const val FAIL_LOCAL_PORT = "无法连接河图核心的本地测速入口（127.0.0.1:%s）：代理可能刚停止或正在重启，请稍后再试"
    const val FAIL_REFUSED = "测速服务器拒绝连接"
    const val FAIL_TIMEOUT = "测速请求超时：当前节点响应太慢或不可用"
    const val FAIL_DNS = "无法解析测速服务器域名"
    const val FAIL_TLS = "与测速服务器的 TLS 握手失败（%s）"
    const val FAIL_HTTP = "测速服务器返回 HTTP %s，当前出口可能被 Cloudflare 限制"
    const val FAIL_IO = "测速连接中断（%s）"
    const val FAIL_OTHER = "测速失败"

    /**
     * Friendly reason for a failed run. Through Mihomo's HTTP listener a CONNECT is answered "200" before
     * the core dials, so a REJECT rule, a node that refuses the target or a dead node all surface as the
     * TLS handshake hitting end-of-stream ("connection closed") right away.
     */
    fun failure(error: Throwable, exitKnown: Boolean, port: Int?): SpeedEvent.Failed {
        val message = error.message.orEmpty()
        val closed = error is EOFException || error.cause is EOFException ||
            message.contains("connection closed", ignoreCase = true) || message.contains("closed by peer", ignoreCase = true) ||
            message.contains("unexpected end of stream", ignoreCase = true) || message.contains("end of file", ignoreCase = true) ||
            message.contains("Connection reset", ignoreCase = true)
        return when {
            error is SpeedHttpStatus -> SpeedEvent.Failed(FAIL_HTTP, listOf(error.code.toString()))
            closed -> SpeedEvent.Failed(if (exitKnown) FAIL_CLOSED_EXIT_KNOWN else FAIL_CLOSED)
            error is ConnectException && port != null -> SpeedEvent.Failed(FAIL_LOCAL_PORT, listOf(port.toString()))
            error is ConnectException -> SpeedEvent.Failed(FAIL_REFUSED)
            error is SocketTimeoutException -> SpeedEvent.Failed(FAIL_TIMEOUT)
            error is UnknownHostException -> SpeedEvent.Failed(FAIL_DNS)
            error is SSLException -> SpeedEvent.Failed(FAIL_TLS, listOf(message.ifBlank { error.javaClass.simpleName }.take(60)))
            error is IOException -> SpeedEvent.Failed(FAIL_IO, listOf(message.ifBlank { error.javaClass.simpleName }.take(60)))
            else -> SpeedEvent.Failed(FAIL_OTHER)
        }
    }

    /** Exit of a Cloudflare `/cdn-cgi/trace` body (ip, loc, colo); null unless it carries ip and loc. */
    fun parseTraceMeta(body: String): SpeedMeta? {
        val fields = body.lineSequence().mapNotNull { line ->
            val at = line.indexOf('='); if (at <= 0) null else line.substring(0, at).trim() to line.substring(at + 1).trim()
        }.toMap()
        val ip = fields["ip"].orEmpty(); val loc = fields["loc"].orEmpty()
        if (ip.isBlank() || loc.length != 2) return null
        return SpeedMeta(ip = ip, country = loc.uppercase(Locale.ROOT), city = "", colo = fields["colo"].orEmpty(), org = "")
    }

    fun parseMeta(body: String): SpeedMeta? = try {
        val json = JSONObject(body)
        SpeedMeta(
            ip = json.optString("clientIp"), country = json.optString("country").uppercase(Locale.ROOT),
            city = json.optString("city"), colo = json.optString("colo"), org = json.optString("asOrganization"),
        ).takeIf { it.ip.isNotBlank() }
    } catch (_: Exception) {
        null
    }

    /** Median of the samples after the first (which pays for the handshake). */
    fun latency(samples: List<Long>): Long {
        val usable = (if (samples.size > 2) samples.drop(1) else samples).sorted()
        return if (usable.isEmpty()) -1L else usable[usable.size / 2]
    }

    private fun open(url: String, port: Int?): HttpURLConnection {
        val target = URL(url)
        val opened = if (port == null) target.openConnection() else target.openConnection(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", port)))
        return (opened as HttpURLConnection).apply {
            useCaches = false
            connectTimeout = REQUEST_TIMEOUT_MS
            readTimeout = REQUEST_TIMEOUT_MS
            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) Hetu-SpeedTest")
        }
    }

    /**
     * Runs [block] while a sibling watcher waits; cancelling the collection cancels the watcher at
     * once (even while [block] is stuck in a socket read) and it closes [connection], so the read returns.
     */
    private suspend fun <T> guarded(connection: HttpURLConnection, block: suspend () -> T): T = coroutineScope {
        val watcher = launch { try { awaitCancellation() } finally { connection.disconnect() } }
        try {
            block()
        } finally {
            watcher.cancel()
            connection.disconnect()
        }
    }

    private suspend fun download(base: String, port: Int?, deadline: Long, counter: AtomicLong) {
        val buffer = ByteArray(64 * 1024)
        while (System.nanoTime() < deadline) {
            currentCoroutineContext().ensureActive()
            val connection = open("$base/__down?bytes=$DOWN_CHUNK", port)
            guarded(connection) {
                if (connection.responseCode !in 200..299) throw SpeedHttpStatus(connection.responseCode)
                connection.inputStream.use { input ->
                    while (System.nanoTime() < deadline) {
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buffer)
                        if (n < 0) break
                        counter.addAndGet(n.toLong())
                    }
                }
            }
        }
    }

    private suspend fun upload(base: String, port: Int?, deadline: Long, counter: AtomicLong, chunk: Int) {
        val payload = Random.nextBytes(64 * 1024)
        while (System.nanoTime() < deadline) {
            currentCoroutineContext().ensureActive()
            val connection = open("$base/__up", port).apply {
                requestMethod = "POST"
                doOutput = true
                setFixedLengthStreamingMode(chunk)
                setRequestProperty("Content-Type", "application/octet-stream")
            }
            guarded(connection) {
                var sent = 0
                connection.outputStream.use { output ->
                    while (sent < chunk) {
                        currentCoroutineContext().ensureActive()
                        val n = minOf(payload.size, chunk - sent)
                        output.write(payload, 0, n)
                        sent += n
                        counter.addAndGet(n.toLong())
                    }
                }
                connection.responseCode
            }
        }
    }

    /** Runs the whole test; the flow completes after [SpeedEvent.Finished] for upload, or [SpeedEvent.Failed]. */
    fun run(port: Int?, base: String = "https://speed.cloudflare.com", phaseMs: Long = PHASE_MS, streams: Int = STREAMS, upChunk: Int = UP_CHUNK,
            traceUrls: List<String> = listOf("$base/cdn-cgi/trace", TRACE_FALLBACK)): Flow<SpeedEvent> = channelFlow {
        var meta: SpeedMeta? = null
        try {
            send(SpeedEvent.Phase(SpeedPhase.Meta))
            meta = try {
                val connection = open("$base/meta", port)
                guarded(connection) { if (connection.responseCode == 200) parseMeta(connection.inputStream.bufferedReader().use { it.readText() }) else null }
            } catch (cancel: CancellationException) { throw cancel } catch (_: Exception) { null }
            // /meta refused (node or rule blocks the speed host): the exit still comes from Cloudflare's trace
            // over the same proxy listener, so the card shows the real exit and the failure can say so.
            if (meta == null) for (url in traceUrls) {
                meta = try {
                    val connection = open(url, port)
                    guarded(connection) { if (connection.responseCode == 200) parseTraceMeta(connection.inputStream.bufferedReader().use { it.readText().take(4_096) }) else null }
                } catch (cancel: CancellationException) { throw cancel } catch (_: Exception) { null }
                if (meta != null) break
            }
            send(SpeedEvent.Meta(meta))

            send(SpeedEvent.Phase(SpeedPhase.Latency))
            val samples = ArrayList<Long>()
            val latencyStarted = System.nanoTime()
            while (samples.size < 6) {
                // A slow path must not stretch the phase: keep what was measured once the budget is spent.
                if (samples.isNotEmpty() && (System.nanoTime() - latencyStarted) / 1_000_000 > LATENCY_BUDGET_MS) break
                val started = System.nanoTime()
                val connection = open("$base/__down?bytes=0", port)
                guarded(connection) {
                    val code = connection.responseCode
                    if (code !in 200..299) throw SpeedHttpStatus(code)
                    connection.inputStream.use { it.readBytes() }
                }
                samples += (System.nanoTime() - started) / 1_000_000
            }
            send(SpeedEvent.Latency(latency(samples)))

            for (uploading in listOf(false, true)) {
                send(SpeedEvent.Phase(if (uploading) SpeedPhase.Upload else SpeedPhase.Download))
                val counter = AtomicLong()
                val started = System.nanoTime()
                val deadline = started + phaseMs * 1_000_000
                coroutineScope {
                    val workers = (0 until streams).map {
                        async { if (uploading) upload(base, port, deadline, counter, upChunk) else download(base, port, deadline, counter) }
                    }
                    launch {
                        while (isActive && workers.any { !it.isCompleted }) {
                            delay(SAMPLE_MS)
                            val elapsed = (System.nanoTime() - started) / 1_000_000
                            send(SpeedEvent.Live(uploading, mbps(counter.get(), elapsed), counter.get()))
                        }
                    }
                    workers.forEach { it.await() }
                }
                val elapsed = ((System.nanoTime() - started) / 1_000_000).coerceAtLeast(1L)
                send(SpeedEvent.Finished(uploading, mbps(counter.get(), elapsed), counter.get()))
            }
            send(SpeedEvent.Phase(SpeedPhase.Done))
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            send(failure(error, meta != null, port))
        }
    }.flowOn(Dispatchers.IO)
}
