package io.github.xgl34222220.hetu

import android.content.Context
import android.os.SystemClock
import android.net.ConnectivityManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Proxy
import java.net.URL
import java.util.concurrent.Executors

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
    val wanState: String = "idle",
    val wanCheckedAt: Long = 0L,
    val wanError: String = "",
)

/** Lightweight runtime inspector used by the proxy dashboard. */
internal class ProxyRuntimeInspector(context: Context) {
    private val app = context.applicationContext
    private val api = MihomoControllerClient(app)
    private val prefs = app.getSharedPreferences("hetu", Context.MODE_PRIVATE)

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
        val lookup = wanLookup.view()
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
            wanState = lookup.state,
            wanCheckedAt = if (lookup.succeededAt > 0L) System.currentTimeMillis() - (SystemClock.elapsedRealtime() - lookup.succeededAt) else 0L,
            wanError = if (lookup.failure.isNotBlank() && prefs.getBoolean("proxyRootRuntimeRefreshPending", false))
                "运行组件待应用，可手动重启代理" else if (lookup.failure.isNotBlank()) "出口检测失败，稍后自动重试" else "",
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
            prefs.getString("proxyAutoRecoveryError", "")?.takeIf { it.isNotBlank() }?.let {
                append("\n--- auto-recovery ---\n").append(it)
            }
            prefs.getString("proxyRootEgressProbeLastError", "")?.takeIf { it.isNotBlank() }?.let {
                append("\n--- egress-probe ---\n").append(it)
            }
            if (prefs.getBoolean("proxyRootRuntimeRefreshPending", false)) {
                append("\n--- runtime-refresh ---\nAPK 已更新；当前 Root 运行环境保持不动，下一次主动重启代理时应用新规则")
            }
        }
        // Redact before truncation so a URL cut at the display boundary cannot
        // leave a subscription token or controller credential in copied UI logs.
        val combined = DiagnosticReport.redact((text + appEvents).trim(), prefs.getString("proxyControllerSecret", ""))
        if (combined.isBlank()) "暂无运行日志" else combined.takeLast(24_000)
    }

    suspend fun adblockRuntimeStats(): AdblockRuntimeStats = withContext(Dispatchers.IO) {
        // The service owns cumulative counters. Read only a bounded tail here so a
        // dashboard refresh never scans an ever-growing log or races its counter.
        val command = "if [ -r /data/adb/hetu/run/core.log ]; then tail -c 1048576 /data/adb/hetu/run/core.log 2>/dev/null | grep -Ei 'hetu-adblock|RuleSet/hetu-adblock' | tail -n 4000; else exit 2; fi"
        val result = RootBridge.rootShell(app, command, 6_000L)
        val persisted = prefs.getLong("proxyAdblockSessionHits", 0L)
        if (!result.ok()) {
            return@withContext AdblockRuntimeStats(
                count = persisted,
                recentDomains = prefs.getString("proxyAdblockRecentDomains", "").orEmpty()
                    .lineSequence().map { it.trim() }.filter { it.isNotBlank() }.take(8).toList(),
                logAvailable = false,
            )
        }
        val matched = result.output.lineSequence()
            .filter {
                it.contains("hetu-adblock", ignoreCase = true) &&
                    it.contains("REJECT", ignoreCase = true) &&
                    it.contains("match", ignoreCase = true)
            }
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
        val count = maxOf(persisted, matched.size.toLong())
        AdblockRuntimeStats(
            count = count,
            recentDomains = if (recent.isNotEmpty()) recent.toList() else
                prefs.getString("proxyAdblockRecentDomains", "").orEmpty()
                    .lineSequence().map { it.trim() }.filter { it.isNotBlank() }.take(8).toList(),
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
        val active = runCatching {
            (app.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager)?.activeNetwork
        }.getOrNull()
        // Selected settings are not yet the running policy. Invalidate only
        // after application/restart, a real network change, or runtime stop.
        val controllerPort = prefs.getInt("proxyControllerPort", MihomoStartupConfig.CONTROLLER_PORT)
        val egressPort = MihomoStartupConfig.egressProbePort(controllerPort)
        probeEgressPort = egressPort
        val runtimeRunning = prefs.getBoolean("proxyRootRuntimeRunning", false)
        val key = listOf(
            "mihomo-egress-v2",
            active?.networkHandle?.toString() ?: "offline",
            controllerPort.toString(),
            egressPort.toString(),
            prefs.getString("proxyRootAppliedSettings", "").orEmpty(),
            runtimeRunning.toString(),
            prefs.getLong("proxyRootLastStartupAt", 0L).toString(),
        ).joinToString("|")
        return wanLookup.get(key, active != null && runtimeRunning)
    }

    private companion object {
        // One process-wide worker/cache: recomposition or reopening the page
        // must not create threads, parallel requests, or a new failure loop.
        val wanWorker = Executors.newSingleThreadExecutor { task ->
            Thread(task, "hetu-public-network").apply { isDaemon = true }
        }
        @Volatile var probeEgressPort: Int = 0
        val wanLookup = ProxyAsyncValue<Triple<String, String, String>>(
            wanWorker,
            { SystemClock.elapsedRealtime() },
            { fetchPublicNetwork() },
            Triple("—", "", "—"),
            900_000L,
            60_000L,
        )

        fun fetchPublicNetwork(): Triple<String, String, String> {
            val deadline = SystemClock.elapsedRealtime() + 6_000L
            val port = probeEgressPort
            if (port !in 1024..65535) error("Mihomo egress probe is not ready")
            val proxy = Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", port))
            val connection = (URL("https://ipwho.is/?fields=success,ip,country_code,region").openConnection(proxy) as HttpURLConnection).apply {
                connectTimeout = 3_000
                readTimeout = 3_000
                requestMethod = "GET"
                setRequestProperty("User-Agent", "Hetu-Android")
            }
            try {
                if (connection.responseCode !in 200..299) error("HTTP ${connection.responseCode}")
                val body = connection.inputStream.bufferedReader().use { reader ->
                    val text = StringBuilder()
                    val buffer = CharArray(2_048)
                    while (true) {
                        val remaining = deadline - SystemClock.elapsedRealtime()
                        if (remaining <= 0L) error("WAN lookup timed out")
                        connection.readTimeout = minOf(3_000L, remaining).toInt()
                        val count = reader.read(buffer)
                        if (count < 0) break
                        if (text.length + count > 16_384) error("WAN response exceeds limit")
                        text.append(buffer, 0, count)
                    }
                    text.toString()
                }
                val json = JSONObject(body)
                if (!json.optBoolean("success", false)) error("WAN lookup failed")
                val address = json.optString("ip", "").trim()
                if (address.isBlank()) error("WAN response has no address")
                return Triple(
                    address,
                    json.optString("country_code", ""),
                    json.optString("region", "—").ifBlank { "—" },
                )
            } finally {
                connection.disconnect()
            }
        }
    }

}
