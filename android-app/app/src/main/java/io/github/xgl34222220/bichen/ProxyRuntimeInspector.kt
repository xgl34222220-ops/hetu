package io.github.xgl34222220.bichen

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.nio.charset.StandardCharsets

internal data class ProxyRuntimeSnapshot(
    val running: Boolean = false,
    val pid: Int = 0,
    val elapsedSeconds: Long = 0L,
    val processTicks: Long = 0L,
    val systemTicks: Long = 0L,
    val rssBytes: Long = 0L,
    val lanAddress: String = "—",
)

/** Lightweight runtime inspector used by the Pro Max dashboard. */
internal class ProxyRuntimeInspector(context: Context) {
    private val app = context.applicationContext
    private val api = MihomoControllerClient(app)
    private val prefs = app.getSharedPreferences("bichen", Context.MODE_PRIVATE)

    suspend fun sample(): ProxyRuntimeSnapshot = withContext(Dispatchers.IO) {
        val command = """
            P=${'$'}(cat /data/adb/bichen/proxy/run/core.pid 2>/dev/null || echo 0)
            case "${'$'}P" in ''|*[!0-9]*) P=0;; esac
            if [ "${'$'}P" -gt 0 ] && kill -0 "${'$'}P" >/dev/null 2>&1; then
              UP=${'$'}(cut -d' ' -f1 /proc/uptime 2>/dev/null | cut -d. -f1)
              START=${'$'}(awk '{print int(${'$'}22/100)}' /proc/${'$'}P/stat 2>/dev/null || echo 0)
              case "${'$'}UP" in ''|*[!0-9]*) UP=0;; esac
              case "${'$'}START" in ''|*[!0-9]*) START=0;; esac
              ELAPSED=0
              if [ "${'$'}UP" -ge "${'$'}START" ]; then ELAPSED=${'$'}((UP-START)); fi
              PT=${'$'}(awk '{print ${'$'}14+${'$'}15}' /proc/${'$'}P/stat 2>/dev/null || echo 0)
              ST=${'$'}(awk '/^cpu /{s=0; for(i=2;i<=NF;i++)s+=${'$'}i; print s; exit}' /proc/stat 2>/dev/null || echo 0)
              RKB=${'$'}(awk '/^VmRSS:/{print ${'$'}2; exit}' /proc/${'$'}P/status 2>/dev/null || echo 0)
              case "${'$'}PT" in ''|*[!0-9]*) PT=0;; esac
              case "${'$'}ST" in ''|*[!0-9]*) ST=0;; esac
              case "${'$'}RKB" in ''|*[!0-9]*) RKB=0;; esac
              printf '{"running":true,"pid":%s,"elapsed":%s,"processTicks":%s,"systemTicks":%s,"rssBytes":%s}\n' "${'$'}P" "${'$'}ELAPSED" "${'$'}PT" "${'$'}ST" "${'$'}((RKB*1024))"
            else
              printf '%s\n' '{"running":false,"pid":0,"elapsed":0,"processTicks":0,"systemTicks":0,"rssBytes":0}'
            fi
        """.trimIndent()
        val result = RootBridge.rootShell(app, command, 8_000L)
        val json = if (result.ok()) runCatching { JSONObject(result.output.trim()) }.getOrNull() else null
        ProxyRuntimeSnapshot(
            running = json?.optBoolean("running", false) == true,
            pid = json?.optInt("pid", 0) ?: 0,
            elapsedSeconds = json?.optLong("elapsed", 0L) ?: 0L,
            processTicks = json?.optLong("processTicks", 0L) ?: 0L,
            systemTicks = json?.optLong("systemTicks", 0L) ?: 0L,
            rssBytes = json?.optLong("rssBytes", 0L) ?: 0L,
            lanAddress = localIpv4Address(),
        )
    }

    suspend fun reloadConfig() = withContext(Dispatchers.IO) {
        api.reloadConfig("/data/adb/bichen/proxy/run/state/startup-config")
    }

    suspend fun runtimeLog(): String = withContext(Dispatchers.IO) {
        val command = "tail -n 160 /data/adb/bichen/proxy/run/core.log 2>&1 || true"
        val result = RootBridge.rootShell(app, command, 10_000L)
        val text = result.output.trim()
        if (text.isBlank()) "暂无运行日志" else text.takeLast(24_000)
    }

    /**
     * Opening WebUI is the explicit user action that allows us to install MetaCubeXD if needed.
     * Merely finding index.html is not enough: interrupted upgrades can leave that file behind
     * while _nuxt chunks are missing, which produces a completely blank WebView. Validate both the
     * entry point and at least one JavaScript chunk, delete an incomplete dashboard, then let
     * Mihomo's /upgrade/ui endpoint install a clean copy.
     */
    suspend fun ensureWebUi() = withContext(Dispatchers.IO) {
        if (webUiReady()) return@withContext

        val secret = controllerSecret()
        if (secret.isBlank()) error("本机控制接口尚未初始化，请先启动代理核心")

        val uiPath = "/data/adb/bichen/proxy/run/${MihomoStartupConfig.EXTERNAL_UI_DIR}"
        val cleanup = RootBridge.rootShell(app, "rm -rf '$uiPath'", 5_000L)
        if (!cleanup.ok()) error("无法清理损坏的 MetaCubeXD 文件")

        val socket = Socket()
        try {
            socket.connect(InetSocketAddress("127.0.0.1", MihomoStartupConfig.CONTROLLER_PORT), 3_000)
            socket.soTimeout = 90_000
            val request = buildString {
                append("POST /upgrade/ui HTTP/1.1\r\n")
                append("Host: 127.0.0.1:").append(MihomoStartupConfig.CONTROLLER_PORT).append("\r\n")
                append("Authorization: Bearer ").append(secret).append("\r\n")
                append("Accept: application/json\r\n")
                append("Content-Length: 0\r\n")
                append("Connection: close\r\n\r\n")
            }
            val output = socket.getOutputStream()
            output.write(request.toByteArray(StandardCharsets.US_ASCII))
            output.flush()
            val input = socket.getInputStream().bufferedReader(StandardCharsets.ISO_8859_1)
            val status = input.readLine() ?: error("WebUI 更新没有返回 HTTP 状态")
            val code = status.split(' ').getOrNull(1)?.toIntOrNull() ?: 0
            if (code !in 200..299) error("WebUI 更新失败：HTTP $code")
        } finally {
            runCatching { socket.close() }
        }

        repeat(12) { attempt ->
            if (webUiReady()) return@withContext
            if (attempt < 11) delay(250)
        }
        error("MetaCubeXD 下载完成但静态资源不完整，请检查网络后重试")
    }

    fun controllerSecret(): String = prefs.getString("proxyControllerSecret", "").orEmpty()

    private fun webUiReady(): Boolean {
        val uiPath = "/data/adb/bichen/proxy/run/${MihomoStartupConfig.EXTERNAL_UI_DIR}"
        val command = "UI='$uiPath'; test -s \"${'$'}UI/index.html\" && find \"${'$'}UI/_nuxt\" -type f -name '*.js' -print -quit 2>/dev/null | grep -q . && echo READY || echo MISSING"
        val result = RootBridge.rootShell(app, command, 4_000L)
        return result.ok() && result.output.contains("READY")
    }

    private fun localIpv4Address(): String {
        return runCatching {
            val preferred = ArrayList<Pair<Int, String>>()
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return@runCatching "—"
            while (interfaces.hasMoreElements()) {
                val network = interfaces.nextElement()
                if (!network.isUp || network.isLoopback) continue
                val rank = when {
                    network.name.startsWith("wlan", true) -> 0
                    network.name.startsWith("eth", true) -> 1
                    network.name.startsWith("rmnet", true) -> 2
                    else -> 3
                }
                val addresses = network.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (address is Inet4Address && !address.isLoopbackAddress && !address.isLinkLocalAddress) {
                        preferred += rank to address.hostAddress.orEmpty()
                    }
                }
            }
            preferred.sortedBy { it.first }.firstOrNull()?.second?.takeIf { it.isNotBlank() } ?: "—"
        }.getOrDefault("—")
    }
}
