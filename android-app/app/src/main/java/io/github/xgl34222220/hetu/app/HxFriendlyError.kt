package io.github.xgl34222220.hetu

import java.io.FileNotFoundException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.nio.file.AccessDeniedException
import java.nio.file.NoSuchFileException
import java.util.Locale
import javax.net.ssl.SSLException

/**
 * A failure as the user should read it: a short Chinese phrase (a word-table key, so ht() can
 * translate it), a stable reason code, and the original JVM text for the log / details.
 *
 * [code] is null when the message is already the App's own wording (any non-JVM failure): then
 * [text] is that message unchanged and nothing is appended.
 */
internal data class HxFriendlyError(val text: String, val code: String?, val detail: String) {
    /** What a toast or an inline error shows: the phrase, then the reason code. */
    val display: String get() = if (code == null) text else "$text · $code"
}

private val hxHttpStatus = Regex("""(?:^|\s|：|:)HTTP(?:/\d(?:\.\d)?)?\s*[:：]?\s*([1-5]\d{2})(?!\d)""")

/**
 * Maps JVM network and I/O exceptions (UnknownHost, SocketTimeout, Connect, NoRouteToHost, SSL,
 * FileNotFound/NoSuchFile, AccessDenied) and plain "HTTP 4xx/5xx" failures to a short phrase plus
 * a reason code. Everything else keeps its own message (or [fallback] when it has none), so the
 * App's existing Chinese errors are never rewritten.
 */
internal fun hxFriendlyError(error: Throwable, fallback: String): HxFriendlyError {
    val raw = error.message?.trim().orEmpty()
    val detail = (error::class.java.simpleName + if (raw.isEmpty()) "" else ": $raw")
    fun mapped(text: String, code: String) = HxFriendlyError(text, code, detail)
    // Wrapped exceptions (ExecutionException, IOException(cause)) report the innermost known type.
    var cause: Throwable? = error
    var depth = 0
    while (cause != null && depth < 6) {
        when (cause) {
            is UnknownHostException -> return mapped("无法解析服务器地址，请检查网络或 DNS", "UNKNOWN_HOST")
            is SocketTimeoutException -> return mapped("连接超时，请检查网络后重试", "TIMEOUT")
            is NoRouteToHostException -> return mapped("网络不可达，请检查网络连接", "NO_ROUTE")
            is ConnectException -> return mapped("无法连接服务器，请确认地址和端口", "CONNECT_FAILED")
            is SSLException -> return mapped("安全连接失败，请检查系统时间或证书", "SSL")
            is FileNotFoundException, is NoSuchFileException -> return mapped("文件不存在或无法访问", "FILE_NOT_FOUND")
            is AccessDeniedException -> return mapped("没有访问权限", "ACCESS_DENIED")
        }
        cause = cause.cause?.takeIf { it !== cause }
        depth++
    }
    // Only a bare status ("HTTP 404", "HTTP/1.1 503", "下载失败：HTTP 500") is mapped; App wording
    // around it is kept as the detail.
    hxHttpStatus.find(raw)?.groupValues?.get(1)?.toIntOrNull()?.let { status ->
        val code = "HTTP_$status"
        when {
            status == 404 -> return mapped("地址不存在，请检查链接（HTTP 404）", code)
            status == 429 -> return mapped("请求过于频繁，请稍后再试（HTTP 429）", code)
            status in 400..499 -> return mapped("请求被拒绝，请检查链接或访问令牌（HTTP %s）".hxFill(status), code)
            status in 500..599 -> return mapped("服务器出错，请稍后再试（HTTP %s）".hxFill(status), code)
        }
    }
    return HxFriendlyError(raw.ifEmpty { fallback }, null, detail)
}

/** Short message for a toast or inline error; the original JVM text goes to the log. */
internal fun hxErrorMessage(error: Throwable, fallback: String): String {
    val friendly = hxFriendlyError(error, fallback)
    if (friendly.code != null) {
        // android.util.Log is a stub in plain JVM tests; logging must never mask the error itself.
        runCatching { android.util.Log.w("Hetu", "${friendly.code}: ${friendly.detail}") }
    }
    return friendly.display
}

private fun String.hxFill(vararg args: Any): String = String.format(Locale.ROOT, this, *args)
