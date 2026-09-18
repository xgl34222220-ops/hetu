package io.github.xgl34222220.hetu

import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.URL

internal data class AdblockRuntimeStats(
    val count: Long = 0L,
    val recentDomains: List<String> = emptyList(),
    val logAvailable: Boolean = false,
)

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
    private val prefs = app.getSharedPreferences("hetu", Context.MODE_PRIVATE)
    @Volatile private var wanCacheAt = 0L
    @Volatile private var wanCache = Triple("—", "", "—")

    suspend fun sample(): ProxyRuntimeSnapshot = withContext(Dispatchers.IO) {
        val command = """
            P=${'$'}(cat /data/adb/hetu/run/core.pid 2>/dev/null || echo 0)
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
        api.reloadConfig("/data/adb/hetu/run/state/startup-config")
    }

    suspend fun runtimeLog(): String = withContext(Dispatchers.IO) {
        val port = prefs.getInt("proxyControllerPort", MihomoStartupConfig.CONTROLLER_PORT)
        val command = "echo '--- controller-port ---'; echo $port; (ss -lntp 2>/dev/null || netstat -lntp 2>/dev/null || true) | grep -E ':$port([[:space:]]|$)' || true; echo '--- start-state ---'; cat /data/adb/hetu/run/start-state 2>/dev/null || true; echo '--- last-start-error ---'; cat /data/adb/hetu/run/last-start-error 2>/dev/null || true; echo '--- core.log ---'; tail -n 140 /data/adb/hetu/run/core.log 2>&1 || true; echo '--- watchdog.log ---'; tail -n 30 /data/adb/hetu/run/watchdog.log 2>/dev/null || true; echo '--- last-crash ---'; cat /data/adb/hetu/run/last-crash 2>/dev/null || true"
        val result = RootBridge.rootShell(app, command, 10_000L)
        val text = result.output.trim()
        val appEvents = buildString {
            prefs.getString("proxyRootUpgradeError", "")?.takeIf { it.isNotBlank() }?.let {
                append("\n--- upgrade-error ---\n").append(it)
            }
            prefs.getString("proxyLastAutoStopReason", "")?.takeIf { it.isNotBlank() }?.let {
                append("\n--- last-auto-stop ---\n").append(it)
            }
            if (prefs.getBoolean("proxyRootRuntimeRefreshPending", false)) {
                append("\n--- runtime-refresh ---\nAPK 已更新；当前 Root 运行环境保持不动，下一次主动重启代理时应用新规则")
            }
        }
        val combined = (text + appEvents).trim()
        if (combined.isBlank()) "暂无运行日志" else combined.takeLast(24_000)
    }

    suspend fun adblockRuntimeStats(): AdblockRuntimeStats = withContext(Dispatchers.IO) {
        val command = "grep -Ei 'hetu-adblock|RuleSet/hetu-adblock' /data/adb/hetu/run/core.log 2>/dev/null | tail -n 2000 || true"
        val result = RootBridge.rootShell(app, command, 6_000L)
        if (!result.ok()) return@withContext AdblockRuntimeStats()
        val matched = result.output.lineSequence()
            .filter { it.contains("hetu-adblock", ignoreCase = true) }
            .toList()
        val target = Regex("-->\\s+([^\\s\\\"]+)")
        val recent = LinkedHashSet<String>()
        for (line in matched.asReversed()) {
            val raw = target.find(line)?.groupValues?.getOrNull(1)?.trim().orEmpty()
            if (raw.isBlank()) continue
            val domain = raw.substringBeforeLast(':', raw).trim('[', ']')
            if (domain.isNotBlank()) recent += domain
            if (recent.size >= 8) break
        }
        AdblockRuntimeStats(
            count = matched.size.toLong(),
            recentDomains = recent.toList(),
            logAvailable = true,
        )
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
                setRequestProperty("User-Agent", "Hetu-Android")
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
