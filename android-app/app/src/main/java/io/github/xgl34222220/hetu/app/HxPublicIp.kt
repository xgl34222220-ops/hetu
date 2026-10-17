package io.github.xgl34222220.hetu

import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Public address as other apps see it, for the WAN card and 公网 IP 详情.
 *
 * - Proxy running (Root): through the core's local policy listener, asking overseas sources only,
 *   so the answer is the proxy's exit rather than a mainland site matched DIRECT by the rules.
 * - Proxy stopped: the device's ordinary route, the address any app sees with Hetu off.
 *
 * Several independent sources are asked in parallel (mainland-reachable ones included); the first
 * answer is shown at once and a later one with location details fills them in. A failed refresh
 * keeps the last good answer of the same path, marked stale with its time.
 */
internal enum class HxIpPath { Proxy, Direct }

internal data class HxPublicIpView(
    /** Null until the first request: nothing to overlay. */
    val path: HxIpPath? = null,
    val info: WanRuntimeInfo? = null,
    /** idle | loading | success | stale | failed (the ProxyRuntimeSnapshot.wanState words). */
    val state: String = "idle",
    /** Wall-clock ms when [info] was obtained; 0 when unknown. */
    val checkedAt: Long = 0L,
    val error: String = "",
    val source: String = "",
)

internal class HxIpSource(
    val id: String,
    val url: String,
    /** Carries location / operator details, not just the address. */
    val rich: Boolean,
    val userAgent: String = "Mozilla/5.0 (Linux; Android) Hetu-Android",
    val parse: (String) -> WanRuntimeInfo,
    /**
     * A mainland site. Rule configs send it DIRECT (GEOIP CN), so through the proxy listener it
     * would report the device's own address: only asked while the proxy is stopped.
     */
    val mainland: Boolean = false,
)

internal object HxIpSources {
    private const val CURL = "curl/8.5.0"
    val all: List<HxIpSource> = listOf(
        HxIpSource("myip.ipip.net", "https://myip.ipip.net/json", true, parse = ::hxParseIpipNet, mainland = true),
        HxIpSource("ip.sb", "https://api.ip.sb/geoip", true, parse = ::hxParseIpSbGeo),
        HxIpSource("ipwho.is", "https://ipwho.is/?fields=success,ip,type,country_code,country,region,city,connection,timezone,latitude,longitude", true, parse = ::parseWanRuntimeInfo),
        HxIpSource("cip.cc", "https://cip.cc/", true, CURL, ::hxParseCipCc, mainland = true),
        HxIpSource("ipinfo.io", "https://ipinfo.io/json", true, parse = ::hxParseIpInfo),
        HxIpSource("ip.3322.net", "https://ip.3322.net/", false, CURL, ::hxParsePlainIp, mainland = true),
        HxIpSource("ipw.cn", "https://4.ipw.cn/", false, CURL, ::hxParsePlainIp, mainland = true),
        HxIpSource("ipify", "https://api.ipify.org/", false, parse = ::hxParsePlainIp),
    )
}

private val ipv4Literal = Regex("""^(25[0-5]|2[0-4]\d|1?\d?\d)(\.(25[0-5]|2[0-4]\d|1?\d?\d)){3}$""")
private val ipv6Literal = Regex("""^[0-9A-Fa-f:]{2,39}$""")

/** A bare address literal, never a host name (nothing is resolved). */
internal fun hxIpLiteral(text: String?): String? {
    val value = text?.trim().orEmpty()
    return value.takeIf { ipv4Literal.matches(it) || (it.contains(':') && ipv6Literal.matches(it)) }
}

private fun ipTypeOf(address: String) = if (address.contains(':')) "IPv6" else "IPv4"

private fun field(obj: JSONObject?, key: String): String =
    obj?.optString(key, "")?.trim()?.takeUnless { it.isEmpty() || it == "null" || it == "—" } ?: "—"

private fun coordinates(latitude: Double, longitude: Double): String =
    if (latitude.isFinite() && longitude.isFinite() && latitude in -90.0..90.0 && longitude in -180.0..180.0)
        String.format(Locale.US, "%.4f, %.4f", latitude, longitude) else "—"

private val chineseCountryCodes = mapOf(
    "中国" to "CN", "香港" to "HK", "中国香港" to "HK", "澳门" to "MO", "中国澳门" to "MO", "台湾" to "TW", "中国台湾" to "TW",
    "日本" to "JP", "韩国" to "KR", "新加坡" to "SG", "美国" to "US", "英国" to "GB", "德国" to "DE", "法国" to "FR",
    "加拿大" to "CA", "澳大利亚" to "AU", "俄罗斯" to "RU", "荷兰" to "NL", "马来西亚" to "MY", "泰国" to "TH", "越南" to "VN",
)

internal fun hxParsePlainIp(body: String): WanRuntimeInfo {
    val address = requireNotNull(hxIpLiteral(body.lineSequence().firstOrNull())) { "No address" }
    return WanRuntimeInfo(address = address, ipType = ipTypeOf(address))
}

/** myip.ipip.net/json: {"ret":"ok","data":{"ip":"…","location":["中国","江西","赣州","","电信"]}} */
internal fun hxParseIpipNet(body: String): WanRuntimeInfo {
    val json = JSONObject(body)
    require(json.optString("ret") == "ok") { "Lookup failed" }
    val data = json.optJSONObject("data")
    val address = requireNotNull(hxIpLiteral(data?.optString("ip"))) { "No address" }
    val location = data?.optJSONArray("location")
    fun part(index: Int) = location?.optString(index, "")?.trim().orEmpty().takeUnless { it.isEmpty() || it == "null" } ?: "—"
    val country = part(0)
    return WanRuntimeInfo(address = address, countryCode = chineseCountryCodes[country].orEmpty(), country = country,
        region = part(1), city = part(2), isp = part(4), ipType = ipTypeOf(address))
}

/** api.ip.sb/geoip */
internal fun hxParseIpSbGeo(body: String): WanRuntimeInfo {
    val json = JSONObject(body)
    val address = requireNotNull(hxIpLiteral(json.optString("ip"))) { "No address" }
    val asn = json.optLong("asn", 0L).takeIf { it > 0L }?.let { "AS$it" } ?: "—"
    return WanRuntimeInfo(address = address, countryCode = field(json, "country_code").takeUnless { it == "—" }.orEmpty(),
        country = field(json, "country"), region = field(json, "region"), city = field(json, "city"),
        isp = field(json, "isp"), asn = asn, organization = field(json, "organization").takeUnless { it == "—" } ?: field(json, "asn_organization"),
        ipType = ipTypeOf(address), timezone = field(json, "timezone"),
        coordinates = coordinates(json.optDouble("latitude", Double.NaN), json.optDouble("longitude", Double.NaN)))
}

/** ipinfo.io/json: org is "AS4134 CHINANET…", loc is "lat,lon". */
internal fun hxParseIpInfo(body: String): WanRuntimeInfo {
    val json = JSONObject(body)
    val address = requireNotNull(hxIpLiteral(json.optString("ip"))) { "No address" }
    val org = field(json, "org")
    val asn = org.substringBefore(' ').takeIf { org != "—" && it.startsWith("AS") && it.drop(2).all(Char::isDigit) } ?: "—"
    val operator = if (asn != "—") org.substringAfter(' ', "").trim().ifEmpty { "—" } else org
    val loc = field(json, "loc").split(',')
    return WanRuntimeInfo(address = address, countryCode = field(json, "country").takeIf { it.length == 2 }.orEmpty(),
        region = field(json, "region"), city = field(json, "city"), isp = operator, asn = asn, organization = operator,
        ipType = ipTypeOf(address), timezone = field(json, "timezone"),
        coordinates = coordinates(loc.getOrNull(0)?.trim()?.toDoubleOrNull() ?: Double.NaN, loc.getOrNull(1)?.trim()?.toDoubleOrNull() ?: Double.NaN))
}

/** cip.cc (curl text): "IP\t: 1.2.3.4", "地址\t: 中国 江西 赣州", "运营商\t: 电信". */
internal fun hxParseCipCc(body: String): WanRuntimeInfo {
    val lines = body.lineSequence().mapNotNull { line ->
        val key = line.substringBefore(':', "").trim()
        if (key.isEmpty()) null else key to line.substringAfter(':').trim()
    }.toMap()
    val address = requireNotNull(hxIpLiteral(lines["IP"])) { "No address" }
    val place = lines["地址"].orEmpty().split(' ', '\t').map { it.trim() }.filter { it.isNotEmpty() }
    val country = place.getOrNull(0) ?: "—"
    return WanRuntimeInfo(address = address, countryCode = chineseCountryCodes[country].orEmpty(), country = country,
        region = place.getOrNull(1) ?: "—", city = place.getOrNull(2) ?: "—",
        isp = lines["运营商"]?.takeIf { it.isNotBlank() } ?: "—", ipType = ipTypeOf(address))
}

/** Location/operator fields a plain answer lacks are taken from [details] when it reports the same address. */
internal fun hxMergeWan(base: WanRuntimeInfo, details: WanRuntimeInfo): WanRuntimeInfo {
    if (base.address != details.address) return details
    fun pick(a: String, b: String) = if (a.isBlank() || a == "—") b else a
    return base.copy(countryCode = base.countryCode.ifBlank { details.countryCode }, country = pick(base.country, details.country),
        region = pick(base.region, details.region), city = pick(base.city, details.city), isp = pick(base.isp, details.isp),
        asn = pick(base.asn, details.asn), organization = pick(base.organization, details.organization),
        ipType = pick(base.ipType, details.ipType), timezone = pick(base.timezone, details.timezone),
        coordinates = pick(base.coordinates, details.coordinates))
}

private fun WanRuntimeInfo.detailed(): Boolean = listOf(country, region, city, isp).any { it.isNotBlank() && it != "—" }

/**
 * One lookup per view model. [request] is called from the main thread (the foreground poll and
 * the refresh actions); every result is applied on the main thread and reported to [onChange].
 * Nothing runs until the first [request], so a view model that is never shown makes no request.
 */
internal class HxPublicIpLookup(
    context: Context,
    private val sources: List<HxIpSource> = HxIpSources.all,
    private val onChange: (HxPublicIpView) -> Unit,
) {
    private val app = context.applicationContext
    private val prefs: SharedPreferences = app.getSharedPreferences("hetu", Context.MODE_PRIVATE)
    private val main = Handler(Looper.getMainLooper())
    private var pool: ExecutorService? = null
    private var key = ""
    private var generation = 0L
    private var inFlight = false
    private var lastAttemptAt = 0L
    private var lastSuccessAt = 0L
    private var failures = 0
    private var bestRich = false

    var view = HxPublicIpView()
        private set

    fun request(running: Boolean, force: Boolean) {
        val viaRoot = running && ProxyStatusBridge.rootProxyRunning(app)
        val path = if (running) HxIpPath.Proxy else HxIpPath.Direct
        val controllerPort = prefs.getInt("proxyControllerPort", MihomoStartupConfig.CONTROLLER_PORT)
        val port = if (viaRoot) MihomoStartupConfig.egressProbePort(controllerPort) else 0
        val network = runCatching {
            (app.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager)?.activeNetwork?.networkHandle
        }.getOrNull()
        val nextKey = listOf(path.name, port.toString(), if (running) prefs.getLong("proxyRootLastStartupAt", 0L).toString() else "",
            if (running) prefs.getString("proxyRootAppliedSettings", "").orEmpty() else "", network?.toString() ?: "offline").joinToString("|")
        val now = SystemClock.elapsedRealtime()
        if (nextKey != key) {
            key = nextKey
            generation++
            inFlight = false
            lastSuccessAt = 0L
            lastAttemptAt = 0L
            failures = 0
            bestRich = false
            // The last good answer of this path, marked with its time, until the new one lands.
            val saved = loadSaved(path)
            publish(HxPublicIpView(path, saved?.first, if (saved != null) "stale" else "loading", saved?.second ?: 0L))
        } else {
            if (inFlight) return
            val due = force || (lastSuccessAt == 0L && now - lastAttemptAt >= retryDelay()) ||
                (lastSuccessAt > 0L && now - lastSuccessAt >= REFRESH_MS)
            if (!due) return
        }
        if (network == null) {
            lastAttemptAt = now
            failures++
            publish(view.copy(path = path, state = if (view.info != null) "stale" else "failed", error = "当前没有可用网络"))
            return
        }
        start(path, port.takeIf { viaRoot })
    }

    private fun retryDelay(): Long = (RETRY_MS shl failures.coerceAtMost(2)).coerceAtMost(60_000L)

    private fun start(path: HxIpPath, port: Int?) {
        inFlight = true
        lastAttemptAt = SystemClock.elapsedRealtime()
        bestRich = false
        if (view.info == null) publish(view.copy(path = path, state = "loading", error = ""))
        val ticket = generation
        val executor = pool ?: Executors.newFixedThreadPool(4) { task ->
            Thread(task, "hetu-public-ip").apply { isDaemon = true }
        }.also { pool = it }
        // Proxy on: only overseas sources, so the answer is the proxy's exit, never a DIRECT rule's.
        val chosen = if (path == HxIpPath.Proxy) sources.filterNot { it.mainland }.ifEmpty { sources } else sources
        var pending = chosen.size
        var answered = false
        chosen.forEach { source ->
            val task = Runnable {
                val info = runCatching { fetch(source, port) }.getOrNull()
                main.post {
                    if (ticket != generation) return@post
                    pending--
                    if (info != null) {
                        val first = !answered
                        answered = true
                        val current = view.info
                        val better = first || (source.rich && !bestRich)
                        if (better) {
                            val merged = if (!first && current != null && current.address == info.address) hxMergeWan(info, current) else info
                            bestRich = bestRich || (source.rich && info.detailed())
                            val at = System.currentTimeMillis()
                            lastSuccessAt = SystemClock.elapsedRealtime()
                            failures = 0
                            publish(HxPublicIpView(path, merged, "success", at, "", source.id))
                            save(path, merged, at)
                        }
                    }
                    if (pending == 0) {
                        inFlight = false
                        if (!answered) {
                            failures++
                            publish(view.copy(path = path, state = if (view.info != null) "stale" else "failed", error = "所有查询源均无响应，稍后自动重试"))
                        }
                    }
                }
            }
            try { executor.execute(task) } catch (_: Exception) { main.post { if (ticket == generation) { pending--; if (pending == 0) inFlight = false } } }
        }
    }

    private fun fetch(source: HxIpSource, port: Int?): WanRuntimeInfo {
        val target = URL(source.url)
        val opened = if (port != null) target.openConnection(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", port))) else target.openConnection()
        val connection = (opened as HttpURLConnection).apply {
            instanceFollowRedirects = false
            useCaches = false
            connectTimeout = 4_000
            readTimeout = 4_000
            requestMethod = "GET"
            setRequestProperty("User-Agent", source.userAgent)
            setRequestProperty("Accept", "application/json, text/plain, */*")
            setRequestProperty("Connection", "close")
        }
        try {
            if (connection.responseCode !in 200..299) error("HTTP ${connection.responseCode}")
            val body = connection.inputStream.bufferedReader().use { reader ->
                val text = StringBuilder()
                val buffer = CharArray(2_048)
                while (true) {
                    val count = reader.read(buffer)
                    if (count < 0) break
                    if (text.length + count > 16_384) error("Response exceeds limit")
                    text.append(buffer, 0, count)
                }
                text.toString()
            }
            return source.parse(body)
        } finally {
            connection.disconnect()
        }
    }

    private fun publish(next: HxPublicIpView) {
        view = next
        onChange(next)
    }

    private fun prefKey(path: HxIpPath) = "hxPublicIpLast." + path.name.lowercase(Locale.ROOT)

    private fun save(path: HxIpPath, info: WanRuntimeInfo, at: Long) {
        val json = JSONObject().put("at", at).put("ip", info.address).put("cc", info.countryCode).put("country", info.country)
            .put("region", info.region).put("city", info.city).put("isp", info.isp).put("asn", info.asn)
            .put("org", info.organization).put("type", info.ipType).put("tz", info.timezone).put("coords", info.coordinates)
        prefs.edit().putString(prefKey(path), json.toString()).apply()
    }

    private fun loadSaved(path: HxIpPath): Pair<WanRuntimeInfo, Long>? = runCatching {
        val json = JSONObject(prefs.getString(prefKey(path), null) ?: return null)
        val address = hxIpLiteral(json.optString("ip")) ?: return null
        fun s(name: String) = json.optString(name, "—").ifBlank { "—" }
        WanRuntimeInfo(address = address, countryCode = json.optString("cc", ""), country = s("country"), region = s("region"),
            city = s("city"), isp = s("isp"), asn = s("asn"), organization = s("org"), ipType = s("type"),
            timezone = s("tz"), coordinates = s("coords")) to json.optLong("at", 0L)
    }.getOrNull()

    fun close() {
        generation++
        pool?.shutdownNow()
        pool = null
    }

    private companion object {
        const val REFRESH_MS = 10 * 60_000L
        const val RETRY_MS = 15_000L
    }
}

/** The public-address fields of [snapshot] replaced by [view] when it belongs to the current path. */
internal fun hxWithPublicIp(snapshot: ProxyRuntimeSnapshot, view: HxPublicIpView, running: Boolean): ProxyRuntimeSnapshot {
    val path = view.path ?: return snapshot
    if ((path == HxIpPath.Proxy) != running) return snapshot
    val info = view.info
    // Nothing of our own yet: keep what the runtime inspector found through the core.
    if (info == null && running && snapshot.wanAddress != "—" && snapshot.wanState in setOf("success", "stale")) return snapshot
    val wan = info ?: WanRuntimeInfo()
    return snapshot.copy(
        wanAddress = wan.address, wanCountryCode = wan.countryCode, wanCountry = wan.country, wanRegion = wan.region,
        wanCity = wan.city, wanIsp = wan.isp, wanAsn = wan.asn, wanOrganization = wan.organization, wanIpType = wan.ipType,
        wanTimezone = wan.timezone, wanCoordinates = wan.coordinates,
        wanState = view.state, wanCheckedAt = view.checkedAt, wanError = view.error,
    )
}
