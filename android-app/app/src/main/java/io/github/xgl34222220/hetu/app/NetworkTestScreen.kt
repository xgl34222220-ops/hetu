package io.github.xgl34222220.hetu

import android.content.Context
import android.os.SystemClock
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.xgl34222220.hetu.home.HomeIcons
import io.github.xgl34222220.hetu.home.HomePill
import io.github.xgl34222220.hetu.home.HomeTone
import io.github.xgl34222220.hetu.home.HomeType
import io.github.xgl34222220.hetu.home.LocalHomeColors
import io.github.xgl34222220.hetu.panel.PanelIcons
import io.github.xgl34222220.hetu.ui.ht
import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch

/** One platform of 网络测试. [domestic] targets are expected to go DIRECT under usual rules. */
internal data class NetTestTarget(val name: String, val url: String, val domestic: Boolean = false)

/** [millis] is time to the first response line (DNS + connect + TLS + first byte); -1 when it failed. */
internal data class NetTestResult(val name: String, val ok: Boolean, val millis: Long, val code: Int, val error: String = "")

/**
 * Multi-platform connectivity test. Every target runs at once on IO and each result is emitted the
 * moment it finishes, so the page fills in row by row and the slowest platform never holds the others.
 * While the Root proxy runs, requests enter the core through its loopback policy listener (Hetu's own
 * UID is exempt from transparent interception), so the result reflects the real rules and nodes.
 */
internal object NetworkTest {
    const val TIMEOUT_MS = 6_000

    val targets = listOf(
        NetTestTarget("Google", "https://www.google.com/generate_204"),
        NetTestTarget("YouTube", "https://www.youtube.com/generate_204"),
        NetTestTarget("GitHub", "https://github.com/"),
        NetTestTarget("Telegram", "https://web.telegram.org/"),
        NetTestTarget("ChatGPT", "https://chatgpt.com/cdn-cgi/trace"),
        NetTestTarget("Netflix", "https://www.netflix.com/"),
        NetTestTarget("Cloudflare", "https://www.cloudflare.com/cdn-cgi/trace"),
        NetTestTarget("百度", "https://www.baidu.com/", domestic = true),
    )

    /** Any HTTP answer below 500 proves the path works (a 403 from a CDN is still the site); 5xx is the proxy or site failing. */
    fun reachable(code: Int): Boolean = code in 200..499

    fun failureText(error: Throwable): String = when (error) {
        is SocketTimeoutException -> "超时"
        is UnknownHostException -> "域名解析失败"
        is ConnectException -> "连接被拒绝"
        is SSLException -> "TLS 握手失败"
        is IOException -> error.message?.takeIf { it.isNotBlank() }?.take(60) ?: "连接失败"
        else -> error.javaClass.simpleName
    }

    /** “6/8 可达 · 平均 230 ms”; blank before the first result. */
    fun summary(results: Collection<NetTestResult>, total: Int = targets.size): String {
        if (results.isEmpty()) return ""
        val ok = results.filter { it.ok }
        val average = if (ok.isEmpty()) "" else " · 平均 ${ok.sumOf { it.millis } / ok.size} ms"
        return "${ok.size}/$total 可达$average"
    }

    fun tone(result: NetTestResult): HomeTone = when {
        !result.ok -> HomeTone.Bad
        result.millis < 400 -> HomeTone.Good
        result.millis < 1_000 -> HomeTone.Warn
        else -> HomeTone.Bad
    }

    /** Blocking; call on IO. A fresh connection per target, so every row measures a cold path. */
    fun measure(target: NetTestTarget, proxyPort: Int?): NetTestResult {
        val started = SystemClock.elapsedRealtime()
        return try {
            val url = URL(target.url)
            val opened = if (proxyPort == null) url.openConnection()
                else url.openConnection(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", proxyPort)))
            val connection = (opened as HttpURLConnection).apply {
                instanceFollowRedirects = false
                useCaches = false
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                setRequestProperty("Connection", "close")
                setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) Hetu-NetTest")
            }
            try {
                val code = connection.responseCode
                val elapsed = (SystemClock.elapsedRealtime() - started).coerceAtLeast(1L)
                if (reachable(code)) NetTestResult(target.name, true, elapsed, code)
                else NetTestResult(target.name, false, -1L, code, "HTTP $code")
            } finally {
                connection.disconnect()
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            NetTestResult(target.name, false, -1L, -1, failureText(error))
        }
    }

    /** Emits each result as soon as its request finishes. */
    fun run(proxyPort: Int?, list: List<NetTestTarget> = targets): Flow<NetTestResult> = channelFlow {
        list.forEach { target -> launch { send(measure(target, proxyPort)) } }
    }.flowOn(Dispatchers.IO)

    /** The core's loopback listener while the Root proxy runs; null means a direct test. */
    fun proxyPort(context: Context): Int? {
        if (!ProxyStatusBridge.rootProxyRunning(context)) return null
        val prefs = context.applicationContext.getSharedPreferences("hetu", Context.MODE_PRIVATE)
        return MihomoStartupConfig.egressProbePort(prefs.getInt("proxyControllerPort", MihomoStartupConfig.CONTROLLER_PORT))
    }
}

@Composable
internal fun NetworkTestScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val c = LocalHomeColors.current
    // Starts once on open; 重新测试 bumps it. Leaving the page cancels the collection.
    var runId by remember { mutableIntStateOf(1) }
    var testing by remember { mutableStateOf(false) }
    var viaProxy by remember { mutableStateOf<Boolean?>(null) }
    val results = remember { mutableStateMapOf<String, NetTestResult>() }

    LaunchedEffect(runId) {
        results.clear()
        testing = true
        val port = NetworkTest.proxyPort(context)
        viaProxy = port != null
        try {
            NetworkTest.run(port).collect { results[it.name] = it }
        } finally {
            testing = false
        }
    }

    HxPage(
        title = ht("网络测试"),
        subtitle = ht("并发测试常用平台的连通性与首包耗时"),
        onBack = onBack,
        largeTitle = false,
        actions = { HxBarAction(HomeIcons.RefreshCw, "重新测试", onClick = { if (!testing) runId++ }, busy = testing) },
    ) {
        item(key = "summary") {
            HxSection {
                HxCard {
                    Text(
                        when (viaProxy) {
                            true -> ht("经由河图代理（按当前规则与节点）")
                            false -> ht("直连（代理未运行）")
                            null -> ""
                        },
                        color = c.t2, style = HomeType.note,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        NetworkTest.summary(results.values).ifBlank { ht("正在测试…") },
                        color = c.t1, style = HomeType.cardLabel,
                    )
                    Spacer(Modifier.height(14.dp))
                    HxButton(if (testing) "测试中" else "重新测试", { if (!testing) runId++ }, Modifier.fillMaxWidth(),
                        icon = HomeIcons.RefreshCw, enabled = !testing, busy = testing)
                }
            }
        }
        item(key = "results") {
            HxSection {
                HxGroup {
                    NetworkTest.targets.forEachIndexed { index, target ->
                        if (index > 0) HxDivider()
                        val result = results[target.name]
                        HxRow(
                            target.name,
                            subtitle = when {
                                result == null -> if (testing) ht("测试中…") else ht("未测试")
                                result.ok -> "HTTP ${result.code}" + if (target.domestic) " · ${ht("国内直连参考")}" else ""
                                else -> result.error
                            },
                            icon = PanelIcons.Globe,
                        ) {
                            when {
                                result == null -> if (testing) HxSpinner(16.dp) else Unit
                                result.ok -> HomePill("${result.millis} ms", tone = NetworkTest.tone(result))
                                else -> HomePill(ht("失败"), tone = HomeTone.Bad)
                            }
                        }
                    }
                }
                Text(
                    ht("耗时为首包时间，含 DNS、握手与 TLS；同一时刻全部平台并发测试。"),
                    Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp),
                    color = c.t3, style = HomeType.caption,
                )
            }
        }
    }
}
