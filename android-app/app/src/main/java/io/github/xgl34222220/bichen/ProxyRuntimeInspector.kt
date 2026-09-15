package io.github.xgl34222220.bichen

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.Inet4Address
import java.net.NetworkInterface

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
