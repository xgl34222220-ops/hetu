package io.github.xgl34222220.hetu

import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext

/**
 * 网络延迟 while the proxy is stopped: the same three targets over the device's ordinary route
 * (what any other app gets with Hetu off). Deliberately separate from
 * ProxyDashboardRepository.siteLatencies, whose runtime-bound probe identity stays untouched.
 * Same timeouts and success rule ([hxProbeAnswered]) as that probe.
 * Results: name → ms (> 0), -1 = failed or timed out.
 */
internal suspend fun hxDirectSiteLatencies(context: Context): Map<String, Long> = withContext(Dispatchers.IO) {
    val sites = ProxyLatencyTargets.load(context.applicationContext)
    coroutineScope {
        sites.map { target -> async { target.name to hxDirectLatency(target.url) } }.awaitAll().toMap()
    }
}

/**
 * Whether [code] is a real answer from [url]. A generate_204 endpoint must answer 204: a captive
 * portal, a DNS-hijack page or an interception box answers 200/302 for any host, which used to
 * count as "reachable". Other targets accept 2xx/3xx as before.
 */
internal fun hxProbeAnswered(url: String, code: Int): Boolean {
    val path = runCatching { URL(url).path.orEmpty() }.getOrDefault("")
    return if (path.trimEnd('/').endsWith("generate_204")) code == 204 else code in 200..399
}

private suspend fun hxDirectLatency(url: String): Long {
    coroutineContext.ensureActive()
    return try {
        val started = SystemClock.elapsedRealtime()
        // The default route, not Proxy.NO_PROXY: a stopped Hetu measures exactly what other apps see.
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            useCaches = false
            connectTimeout = 3_000
            readTimeout = 3_000
            requestMethod = "GET"
            setRequestProperty("User-Agent", "Hetu-Android")
            setRequestProperty("Cache-Control", "no-cache")
        }
        try {
            val code = connection.responseCode
            coroutineContext.ensureActive()
            if (hxProbeAnswered(url, code)) (SystemClock.elapsedRealtime() - started).coerceAtLeast(1L) else -1L
        } finally {
            runCatching { connection.inputStream?.close() }
            connection.disconnect()
        }
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (_: Exception) {
        coroutineContext.ensureActive()
        -1L
    }
}
