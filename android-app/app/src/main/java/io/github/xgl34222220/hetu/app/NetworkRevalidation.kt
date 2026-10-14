package io.github.xgl34222220.hetu

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/**
 * 3.0.0 system-side recovery after a Root start, restart or stop (real-device report: stop/restart during an
 * outage did not bring the network back because Wi-Fi stayed "无法连接到网络" in NetworkMonitor's backoff).
 *
 * hetu-root.sh already flushes every network's resolver cache and asks for re-evaluation as Root
 * (`ndc resolver flushnet`, `cmd connectivity reevaluate`). From the App, every network with INTERNET
 * (no VPN) is reported with the opposite of its VALIDATED state, which makes ConnectivityService force
 * a re-evaluation without changing anything else. Only when the user turned on [PREF_WIFI_TOGGLE] and a
 * Wi-Fi network is still not validated [WIFI_TOGGLE_DELAY_MS] later is Wi-Fi switched off and on as Root.
 *
 * After a start the probe URLs NetworkMonitor uses are also read as Root (`hetu-root.sh probe-urls`:
 * device_config, dumpsys network_stack / connectivity, logcat) at most once per [PROBE_URL_TTL_MS]; the next
 * start bypasses their hosts too (CaptivePortalBypass.validationEntries). Never on the start path itself.
 */
object NetworkRevalidation {
    const val PREF_WIFI_TOGGLE = "proxyWifiToggleRecovery"
    const val PREF_LAST = "proxyNetworkRevalidateLast"
    private const val PREF_PROBE_URLS = "proxyProbeUrlsCache"
    private const val PREF_PROBE_URLS_AT = "proxyProbeUrlsAt"
    private const val SCRIPT = "/data/adb/hetu/hetu-root.sh"
    const val WIFI_TOGGLE_DELAY_MS = 12_000L
    const val PROBE_URL_TTL_MS = 24L * 60 * 60 * 1000

    @JvmStatic
    fun kick(context: Context, reason: String) {
        val app = context.applicationContext ?: context
        Thread({ runCatching { recover(app, reason) } }, "hetu-revalidate").apply { isDaemon = true }.start()
    }

    /** Probe URLs last read as Root (one per line); empty until a start has read them. */
    @JvmStatic
    fun cachedProbeUrls(context: Context): List<String> =
        context.getSharedPreferences("hetu", 0).getString(PREF_PROBE_URLS, "").orEmpty()
            .lines().map { it.trim() }.filter { it.startsWith("http://") || it.startsWith("https://") }.take(32)

    /** Networks with INTERNET (VPN excluded) and whether each is validated. */
    private fun networks(cm: ConnectivityManager) = runCatching {
        @Suppress("DEPRECATION")
        cm.allNetworks.mapNotNull { n ->
            val caps = cm.getNetworkCapabilities(n) ?: return@mapNotNull null
            if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return@mapNotNull null
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return@mapNotNull null
            Triple(n, caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED), caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))
        }
    }.getOrDefault(emptyList())

    private fun recover(app: Context, reason: String) {
        val prefs = app.getSharedPreferences("hetu", 0)
        val cm = app.getSystemService(ConnectivityManager::class.java) ?: return
        val reported = StringBuilder()
        for ((network, validated, wifi) in networks(cm)) {
            // Reporting the opposite of the current state is what forces NetworkMonitor to re-evaluate.
            val ok = runCatching { cm.reportNetworkConnectivity(network, !validated) }.isSuccess
            reported.append(if (wifi) " wifi" else " net").append(if (validated) "=valid" else "=invalid").append(if (ok) "" else "(report-failed)")
        }
        var toggled = ""
        if (prefs.getBoolean(PREF_WIFI_TOGGLE, false)) {
            Thread.sleep(WIFI_TOGGLE_DELAY_MS)
            if (networks(cm).any { it.third && !it.second }) {
                val r = runCatching { RootBridge.rootShell(app, "svc wifi disable; sleep 2; svc wifi enable", 15_000L) }.getOrNull()
                toggled = if (r != null && r.ok()) " wifi-toggled" else " wifi-toggle-failed"
            }
        }
        prefs.edit().putString(PREF_LAST, System.currentTimeMillis().toString() + " " + reason + reported + toggled).apply()
        if (reason == "start" && System.currentTimeMillis() - prefs.getLong(PREF_PROBE_URLS_AT, 0L) > PROBE_URL_TTL_MS) refreshProbeUrls(app)
    }

    private fun refreshProbeUrls(app: Context) {
        val r = runCatching { RootBridge.rootShell(app, "sh " + RootBridge.quote(SCRIPT) + " probe-urls", 20_000L) }.getOrNull() ?: return
        if (!r.ok()) return
        val urls = r.output.lines().map { it.trim() }.filter { it.startsWith("http://") || it.startsWith("https://") }.distinct().take(32)
        app.getSharedPreferences("hetu", 0).edit()
            .putString(PREF_PROBE_URLS, urls.joinToString("\n")).putLong(PREF_PROBE_URLS_AT, System.currentTimeMillis()).apply()
    }
}
