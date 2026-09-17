package io.github.xgl34222220.bichen

import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.URL
import java.util.zip.ZipInputStream

internal data class ProxyRuntimeSnapshot(
    val running: Boolean = false,
    val pid: Int = 0,
    val elapsedSeconds: Long = 0L,
    val processTicks: Long = 0L,
    val systemTicks: Long = 0L,
    val rssBytes: Long = 0L,
    val lanAddress: String = "—",
    val lanInterface: String = "—",
    val wanAddress: String = "—",
    val wanCountryCode: String = "",
    val wanRegion: String = "—",
)

/** Lightweight runtime inspector used by the proxy dashboard. */
internal class ProxyRuntimeInspector(context: Context) {
    private val app = context.applicationContext
    private val api = MihomoControllerClient(app)
    private val prefs = app.getSharedPreferences("bichen", Context.MODE_PRIVATE)
    @Volatile private var wanCacheAt = 0L
    @Volatile private var wanCache = Triple("—", "", "—")

    companion object {
        private const val ZASHBOARD_ZIP = "https://github.com/Zephyruso/zashboard/releases/latest/download/dist-no-fonts.zip"
    }

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
        val local = localNetwork()
        val wan = publicNetwork()
        ProxyRuntimeSnapshot(
            running = json?.optBoolean("running", false) == true,
            pid = json?.optInt("pid", 0) ?: 0,
            elapsedSeconds = json?.optLong("elapsed", 0L) ?: 0L,
            processTicks = json?.optLong("processTicks", 0L) ?: 0L,
            systemTicks = json?.optLong("systemTicks", 0L) ?: 0L,
            rssBytes = json?.optLong("rssBytes", 0L) ?: 0L,
            lanAddress = local.first,
            lanInterface = local.second,
            wanAddress = wan.first,
            wanCountryCode = wan.second,
            wanRegion = wan.third,
        )
    }

    suspend fun reloadConfig() = withContext(Dispatchers.IO) {
        api.reloadConfig("/data/adb/bichen/proxy/run/state/startup-config")
    }

    suspend fun runtimeLog(): String = withContext(Dispatchers.IO) {
        val command = "echo '--- start-state ---'; cat /data/adb/bichen/proxy/run/start-state 2>/dev/null || true; echo '--- last-start-error ---'; cat /data/adb/bichen/proxy/run/last-start-error 2>/dev/null || true; echo '--- core.log ---'; tail -n 140 /data/adb/bichen/proxy/run/core.log 2>&1 || true; echo '--- watchdog.log ---'; tail -n 30 /data/adb/bichen/proxy/run/watchdog.log 2>/dev/null || true; echo '--- last-crash ---'; cat /data/adb/bichen/proxy/run/last-crash 2>/dev/null || true"
        val result = RootBridge.rootShell(app, command, 10_000L)
        val text = result.output.trim()
        if (text.isBlank()) "暂无运行日志" else text.takeLast(24_000)
    }

    /**
     * Install Zashboard directly into Mihomo's local external-ui directory.
     * The UI is then served from the same 127.0.0.1 origin as the Clash API, so Android WebView
     * no longer depends on hosted-page CORS or Private Network Access behavior.
     */
    suspend fun ensureWebUi() = withContext(Dispatchers.IO) {
        if (waitWebUiReady(1_200L)) return@withContext
        var officialError = ""
        try {
            api.upgradeUi()
            if (waitWebUiReady(10_000L)) return@withContext
            officialError = "Mihomo /upgrade/ui 已返回，但 /ui/ 仍不可访问"
        } catch (error: Exception) {
            officialError = error.message ?: "Mihomo /upgrade/ui 调用失败"
        }
        try {
            installZashboard()
            if (waitWebUiReady(5_000L)) return@withContext
            error("本地文件已安装，但 Mihomo 没有从 /ui/ 提供页面")
        } catch (fallback: Exception) {
            val second = fallback.message ?: "本地安装失败"
            error("WebUI 安装失败：$officialError；备用安装：$second")
        }
    }

    suspend fun repairWebUi() = withContext(Dispatchers.IO) {
        removeWebUiFiles()
        ensureWebUi()
    }

    fun controllerSecret(): String = prefs.getString("proxyControllerSecret", "").orEmpty()

    private fun installZashboard() {
        val stage = File(app.cacheDir, "zashboard-stage")
        if (stage.exists()) stage.deleteRecursively()
        if (!stage.mkdirs()) error("无法创建 WebUI 临时目录")

        try {
            val connection = openDownload(ZASHBOARD_ZIP)
            connection.inputStream.buffered().use { input ->
                ZipInputStream(input).use { zip ->
                    val stageRoot = stage.canonicalFile
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        val out = File(stage, entry.name).canonicalFile
                        if (out != stageRoot && !out.path.startsWith(stageRoot.path + File.separator)) {
                            error("WebUI 压缩包包含非法路径")
                        }
                        if (entry.isDirectory) {
                            if (!out.isDirectory && !out.mkdirs()) error("无法创建 WebUI 目录")
                        } else {
                            out.parentFile?.let { parent ->
                                if (!parent.isDirectory && !parent.mkdirs()) error("无法创建 WebUI 目录")
                            }
                            out.outputStream().buffered().use { output -> zip.copyTo(output) }
                        }
                        zip.closeEntry()
                    }
                }
            }
            connection.disconnect()

            val index = stage.walkTopDown().firstOrNull {
                it.isFile && it.name.equals("index.html", ignoreCase = true) &&
                    runCatching { it.readText().contains("zashboard", ignoreCase = true) }.getOrDefault(false)
            } ?: error("Zashboard 压缩包里没有有效的 index.html")
            val root = index.parentFile ?: error("Zashboard 文件结构异常")
            val hasJs = root.walkTopDown().any { it.isFile && it.extension.equals("js", ignoreCase = true) }
            if (!hasJs) error("Zashboard 静态资源不完整")

            val uiPath = "/data/adb/bichen/proxy/run/${MihomoStartupConfig.EXTERNAL_UI_DIR}"
            val command = buildString {
                append("set -e; rm -rf ").append(RootBridge.quote(uiPath))
                append("; mkdir -p ").append(RootBridge.quote(uiPath))
                append("; cp -a ").append(RootBridge.quote(root.absolutePath + "/."))
                append(' ').append(RootBridge.quote(uiPath + "/"))
                append("; chmod -R a+rX ").append(RootBridge.quote(uiPath))
            }
            val copied = RootBridge.rootShell(app, command, 20_000L)
            if (!copied.ok()) error("安装 Zashboard 失败：${copied.output.trim().takeLast(300)}")
            if (!webUiReady()) error("Zashboard 已复制但本机静态资源校验失败")
        } finally {
            stage.deleteRecursively()
        }
    }

    private fun openDownload(initialUrl: String): HttpURLConnection {
        var current = initialUrl
        repeat(6) {
            val connection = (URL(current).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 15_000
                readTimeout = 90_000
                requestMethod = "GET"
                setRequestProperty("User-Agent", "Bichen-Android")
                setRequestProperty("Accept", "application/zip,application/octet-stream,*/*")
            }
            val code = connection.responseCode
            if (code in listOf(301, 302, 303, 307, 308)) {
                val location = connection.getHeaderField("Location") ?: error("WebUI 下载重定向缺少地址")
                current = URL(URL(current), location).toString()
                connection.disconnect()
            } else {
                if (code !in 200..299) {
                    connection.disconnect()
                    error("Zashboard 下载失败：HTTP $code")
                }
                return connection
            }
        }
        error("Zashboard 下载重定向次数过多")
    }

    private fun webUiReady(): Boolean {
        val uiPath = "/data/adb/bichen/proxy/run/${MihomoStartupConfig.EXTERNAL_UI_DIR}"
        val command = "UI=${RootBridge.quote(uiPath)}; test -s \"${'$'}UI/index.html\" && find \"${'$'}UI\" -type f -print 2>/dev/null | grep -Eq '\\.(m?js)$' && echo READY || echo MISSING"
        val result = RootBridge.rootShell(app, command, 4_000L)
        return result.ok() && result.output.contains("READY")
    }

    private fun webUiHttpReady(): Boolean {
        val connection = (URL("http://127.0.0.1:${MihomoStartupConfig.CONTROLLER_PORT}/ui/").openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 2_000
            readTimeout = 3_000
            requestMethod = "GET"
            setRequestProperty("Accept", "text/html,*/*")
            controllerSecret().takeIf { it.isNotBlank() }?.let { setRequestProperty("Authorization", "Bearer $it") }
        }
        return try {
            val code = connection.responseCode
            if (code !in 200..299) false else {
                val head = connection.inputStream.bufferedReader().use { reader ->
                    val chars = CharArray(4096)
                    val count = reader.read(chars)
                    if (count <= 0) "" else String(chars, 0, count)
                }
                head.contains("<html", ignoreCase = true) || head.contains("<!doctype", ignoreCase = true)
            }
        } catch (_: Exception) {
            false
        } finally {
            connection.disconnect()
        }
    }

    private fun waitWebUiReady(timeoutMs: Long): Boolean {
        val end = SystemClock.elapsedRealtime() + timeoutMs
        do {
            if (webUiReady() && webUiHttpReady()) return true
            if (SystemClock.elapsedRealtime() >= end) break
            Thread.sleep(180L)
        } while (true)
        return false
    }

    private fun removeWebUiFiles() {
        val uiPath = "/data/adb/bichen/proxy/run/${MihomoStartupConfig.EXTERNAL_UI_DIR}"
        RootBridge.rootShell(app, "rm -rf ${RootBridge.quote(uiPath)}; mkdir -p ${RootBridge.quote(uiPath)}", 8_000L)
    }

    private fun localNetwork(): Pair<String, String> {
        return runCatching {
            val preferred = ArrayList<Triple<Int, String, String>>()
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return@runCatching "—" to "—"
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
                        preferred += Triple(rank, address.hostAddress.orEmpty(), network.name)
                    }
                }
            }
            preferred.sortedBy { it.first }.firstOrNull()?.let { it.second to it.third } ?: ("—" to "—")
        }.getOrDefault("—" to "—")
    }

    private fun publicNetwork(): Triple<String, String, String> {
        val now = SystemClock.elapsedRealtime()
        if (now - wanCacheAt < 60_000L && wanCache.first != "—") return wanCache
        val fresh = runCatching {
            val connection = (URL("https://ipwho.is/?fields=success,ip,country_code,region").openConnection() as HttpURLConnection).apply {
                connectTimeout = 3_000
                readTimeout = 3_000
                requestMethod = "GET"
                setRequestProperty("User-Agent", "Bichen-Android")
            }
            try {
                if (connection.responseCode !in 200..299) error("HTTP ${connection.responseCode}")
                val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                if (!json.optBoolean("success", true)) error("WAN lookup failed")
                Triple(
                    json.optString("ip", "—").ifBlank { "—" },
                    json.optString("country_code", ""),
                    json.optString("region", "—").ifBlank { "—" },
                )
            } finally {
                connection.disconnect()
            }
        }.getOrNull()
        if (fresh != null) {
            wanCache = fresh
            wanCacheAt = now
        }
        return fresh ?: wanCache
    }

}
