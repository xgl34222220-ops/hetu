package io.github.xgl34222220.hetu

import java.io.EOFException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * func-400 S1/S2: which download / subscription failures are worth one more attempt.
 *
 * Transient = the network or the server hiccupped (timeout, reset, refused, DNS during a network
 * switch, HTTP 408 / 425 / 429 / 5xx). A 4xx answer (bad link, expired token), a TLS / certificate
 * failure, an oversize or non-config body are final: retrying them only delays the real message.
 */
internal object HxNetRetry {
    const val PROVIDER_RETRY_DELAY_MS = 1_500L
    /** Waits before the 2nd and 3rd download attempt. */
    val DOWNLOAD_BACKOFF_MS = longArrayOf(1_000L, 3_000L)

    private val httpStatus = Regex("""HTTP(?:/\d(?:\.\d)?)?\s*[:：]?\s*([1-5]\d{2})(?!\d)""")

    fun transientStatus(code: Int): Boolean = code == 408 || code == 425 || code == 429 || code in 500..599

    fun transient(error: Throwable): Boolean {
        var cause: Throwable? = error
        var depth = 0
        while (cause != null && depth < 6) {
            when (cause) {
                is SSLException -> return false
                // Mihomo answers a failed provider fetch with 503 (body = the fetch error).
                is MihomoControllerClient.ControllerHttpException -> return transientStatus(cause.statusCode)
                is InterruptedIOException, is ConnectException, is NoRouteToHostException,
                is UnknownHostException, is EOFException, is SocketException -> return true
            }
            val status = httpStatus.find(cause.message.orEmpty())?.groupValues?.get(1)?.toIntOrNull()
            if (status != null) return transientStatus(status)
            cause = cause.cause?.takeIf { it !== cause }
            depth++
        }
        return false
    }
}
