package io.github.xgl34222220.hetu

import android.content.Context
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.NoRouteToHostException
import java.net.Proxy
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/*
 * 网络测试 › 多平台连通性.
 *
 * Every platform is requested at the same time (bounded by [PlatformProbe.DEFAULT_CONCURRENCY]),
 * each on its own short-lived connection, and every result is pushed as soon as it is known, so the
 * slowest platform never holds the others back. While the Root proxy runs, requests enter the
 * running core through its loopback policy listener (Hetu's own UID is exempt from transparent
 * interception), so the result reflects the active rules and selected nodes; otherwise they go
 * direct and the page says so.
 */

internal enum class PlatformGroup(val title: String) { Global("国际平台"), China("国内平台") }

internal data class PlatformTarget(val id: String, val name: String, val url: String, val group: PlatformGroup) {
    val host: String get() = try { URL(url).host } catch (_: Exception) { url }
}

internal sealed interface PlatformOutcome {
    /** Not started yet in this round. */
    data object Waiting : PlatformOutcome
    /** Request in flight. */
    data object Running : PlatformOutcome
    /** 2xx/3xx, or a 4xx that proves the service answered (401 from an API, 404, 405 …). */
    data class Reachable(val latencyMs: Long, val code: Int) : PlatformOutcome
    /** The service answered but refused: 403 / 451 (usually region) or 429 (rate limit). */
    data class Restricted(val latencyMs: Long, val code: Int) : PlatformOutcome
    data class Failed(val reason: String) : PlatformOutcome
}

internal data class PlatformResult(val target: PlatformTarget, val outcome: PlatformOutcome)

internal data class PlatformSummary(val reachable: Int, val restricted: Int, val failed: Int, val pending: Int) {
    val done: Boolean get() = pending == 0
}

internal object PlatformCatalog {
    val targets: List<PlatformTarget> = listOf(
        PlatformTarget("google", "Google", "https://www.google.com/generate_204", PlatformGroup.Global),
        PlatformTarget("youtube", "YouTube", "https://www.youtube.com/generate_204", PlatformGroup.Global),
        PlatformTarget("netflix", "Netflix", "https://www.netflix.com/", PlatformGroup.Global),
        PlatformTarget("chatgpt", "ChatGPT", "https://chatgpt.com/", PlatformGroup.Global),
        PlatformTarget("openai-api", "OpenAI API", "https://api.openai.com/v1/models", PlatformGroup.Global),
        PlatformTarget("claude", "Claude", "https://claude.ai/", PlatformGroup.Global),
        PlatformTarget("gemini", "Gemini", "https://gemini.google.com/", PlatformGroup.Global),
        PlatformTarget("github", "GitHub", "https://github.com/", PlatformGroup.Global),
        PlatformTarget("telegram", "Telegram", "https://web.telegram.org/", PlatformGroup.Global),
        PlatformTarget("x", "X / Twitter", "https://x.com/", PlatformGroup.Global),
        PlatformTarget("instagram", "Instagram", "https://www.instagram.com/", PlatformGroup.Global),
        PlatformTarget("tiktok", "TikTok", "https://www.tiktok.com/", PlatformGroup.Global),
        PlatformTarget("spotify", "Spotify", "https://open.spotify.com/", PlatformGroup.Global),
        PlatformTarget("disney", "Disney+", "https://www.disneyplus.com/", PlatformGroup.Global),
        PlatformTarget("steam", "Steam", "https://store.steampowered.com/", PlatformGroup.Global),
        PlatformTarget("bilibili", "哔哩哔哩", "https://www.bilibili.com/", PlatformGroup.China),
        PlatformTarget("baidu", "百度", "https://www.baidu.com/", PlatformGroup.China),
        PlatformTarget("wechat", "微信", "https://weixin.qq.com/", PlatformGroup.China),
    )
}

internal object PlatformProbe {
    const val DEFAULT_TIMEOUT_MS = 6_000
    const val DEFAULT_CONCURRENCY = 6
    private const val USER_AGENT = "Mozilla/5.0 (Linux; Android) Hetu-NetworkTest"

    /** HTTP status → outcome. Only a refusal or a server error is not a plain “reachable”. */
    fun outcomeFor(code: Int, latencyMs: Long): PlatformOutcome = when {
        code in 200..399 -> PlatformOutcome.Reachable(latencyMs, code)
        code == 403 || code == 451 || code == 429 -> PlatformOutcome.Restricted(latencyMs, code)
        code in 400..499 -> PlatformOutcome.Reachable(latencyMs, code)
        code >= 500 -> PlatformOutcome.Failed("服务器错误 HTTP $code")
        else -> PlatformOutcome.Failed("无效响应")
    }

    fun failureReason(error: Throwable): String = when (error) {
        is SocketTimeoutException -> "超时"
        is UnknownHostException -> "域名解析失败"
        is SSLException -> "TLS 握手失败"
        is ConnectException -> "连接被拒绝"
        is NoRouteToHostException -> "无法路由到目标"
        is InterruptedIOException -> "超时"
        is IOException -> {
            val text = error.message.orEmpty()
            when {
                text.contains("unexpected end of stream", true) || text.contains("EOF", false) ||
                    text.contains("reset", true) || text.contains("closed", true) -> "连接被中断"
                text.isNotBlank() -> text.take(60)
                else -> error.javaClass.simpleName
            }
        }
        else -> error.javaClass.simpleName
    }

    /** One request on its own connection. Time is connect + TLS + first response line. */
    suspend fun probe(target: PlatformTarget, proxy: Proxy?, timeoutMs: Int = DEFAULT_TIMEOUT_MS): PlatformOutcome =
        withContext(Dispatchers.IO) {
            val holder = AtomicReference<HttpURLConnection?>()
            coroutineScope {
                // Connect/read timeouts do not cover every blocking step (for example a slow
                // direct DNS lookup); the watchdog closes the socket at a hard deadline.
                val watchdog = launch {
                    delay(timeoutMs * 2L + 500L)
                    holder.get()?.let { try { it.disconnect() } catch (_: Exception) {} }
                }
                try {
                    blockingProbe(target, proxy, timeoutMs, holder)
                } finally {
                    watchdog.cancel()
                }
            }
        }

    private fun blockingProbe(target: PlatformTarget, proxy: Proxy?, timeoutMs: Int, holder: AtomicReference<HttpURLConnection?>): PlatformOutcome {
        val started = System.nanoTime()
        var connection: HttpURLConnection? = null
        return try {
            val url = URL(target.url)
            connection = ((if (proxy == null) url.openConnection() else url.openConnection(proxy)) as HttpURLConnection).apply {
                instanceFollowRedirects = false
                useCaches = false
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                requestMethod = "GET"
                setRequestProperty("Connection", "close")
                setRequestProperty("Cache-Control", "no-cache")
                setRequestProperty("Accept", "*/*")
                setRequestProperty("User-Agent", USER_AGENT)
            }
            holder.set(connection)
            val code = connection.responseCode
            val elapsed = ((System.nanoTime() - started) / 1_000_000L).coerceAtLeast(1L)
            outcomeFor(code, elapsed)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            PlatformOutcome.Failed(failureReason(error))
        } finally {
            try { connection?.disconnect() } catch (_: Exception) {}
        }
    }

    /**
     * Probes [targets] concurrently and emits `Running` then the outcome of each one, in the
     * order they finish. [prober] is injectable for tests.
     */
    fun run(
        targets: List<PlatformTarget>,
        proxy: Proxy?,
        timeoutMs: Int = DEFAULT_TIMEOUT_MS,
        concurrency: Int = DEFAULT_CONCURRENCY,
        prober: suspend (PlatformTarget) -> PlatformOutcome = { target -> probe(target, proxy, timeoutMs) },
    ): Flow<PlatformResult> = channelFlow {
        val gate = Semaphore(concurrency.coerceAtLeast(1))
        for (target in targets) {
            launch {
                gate.withPermit {
                    send(PlatformResult(target, PlatformOutcome.Running))
                    val outcome = try {
                        prober(target)
                    } catch (cancel: CancellationException) {
                        throw cancel
                    } catch (error: Exception) {
                        PlatformOutcome.Failed(failureReason(error))
                    }
                    send(PlatformResult(target, outcome))
                }
            }
        }
    }

    fun summarize(outcomes: Collection<PlatformOutcome>): PlatformSummary = PlatformSummary(
        reachable = outcomes.count { it is PlatformOutcome.Reachable },
        restricted = outcomes.count { it is PlatformOutcome.Restricted },
        failed = outcomes.count { it is PlatformOutcome.Failed },
        pending = outcomes.count { it == PlatformOutcome.Waiting || it == PlatformOutcome.Running },
    )
}

/** Which path the test takes: the running core's loopback listener, or direct. */
internal object PlatformRoute {
    fun proxy(context: Context): Proxy? {
        val app = context.applicationContext
        if (!ProxyStatusBridge.rootProxyRunning(app)) return null
        val prefs = app.getSharedPreferences("hetu", Context.MODE_PRIVATE)
        val port = MihomoStartupConfig.egressProbePort(prefs.getInt("proxyControllerPort", MihomoStartupConfig.CONTROLLER_PORT))
        return Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", port))
    }
}
