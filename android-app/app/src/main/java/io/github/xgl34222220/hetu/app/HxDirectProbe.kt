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
import java.net.Proxy
import java.net.URL
import kotlin.coroutines.coroutineContext

/**
 * Audit4 G5: 本机直测 while the proxy is stopped. A plain direct request (Proxy.NO_PROXY) to the
 * same three targets, deliberately separate from ProxyDashboardRepository.siteLatencies, whose
 * runtime-bound probe identity stays untouched. Same timeouts and success range as that probe.
 * Results: name → ms (> 0), -1 = failed or timed out.
 */
internal suspend fun hxDirectSiteLatencies(context: Context): Map<String, Long> = withContext(Dispatchers.IO) {
    val sites = ProxyLatencyTargets.load(context.applicationContext)
    coroutineScope {
        sites.map { target -> async { target.name to hxDirectLatency(target.url) } }.awaitAll().toMap()
    }
}

private suspend fun hxDirectLatency(url: String): Long {
    coroutineContext.ensureActive()
    return try {
        val started = SystemClock.elapsedRealtime()
        val connection = (URL(url).openConnection(Proxy.NO_PROXY) as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 3_000
            readTimeout = 3_000
            requestMethod = "GET"
            setRequestProperty("User-Agent", "Hetu-Android")
            setRequestProperty("Cache-Control", "no-cache")
        }
        try {
            val code = connection.responseCode
            coroutineContext.ensureActive()
            if (code in 200..399) (SystemClock.elapsedRealtime() - started).coerceAtLeast(1L) else -1L
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
