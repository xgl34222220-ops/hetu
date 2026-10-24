package io.github.xgl34222220.hetu

import android.content.Context
import android.content.SharedPreferences
import java.util.concurrent.Executors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * App side of the Root monitor daemon (hetu-root.sh monitor): exports the policy it reads, asks
 * whether it owns network-match actions right now, and pulls its traffic batches into
 * [ProxyApiHistoryStore]. All Root work runs off the main thread.
 */
internal object DaemonMonitorBridge {
    private const val SCRIPT = "/data/adb/hetu/hetu-root.sh"
    private val exporter = Executors.newSingleThreadExecutor { r -> Thread(r, "hetu-monitor-policy").apply { isDaemon = true } }
    @Volatile private var lastPull = 0L

    @JvmStatic
    fun policyBody(prefs: SharedPreferences): String {
        val inputs = DaemonMonitorPolicy.Inputs()
        inputs.traffic = prefs.getBoolean("proxyApiHistoryEnabled", false)
        inputs.netmatch = prefs.getBoolean("networkMatchEnabled", false)
        inputs.mobile = prefs.getBoolean("networkMatchMobile", false)
        inputs.ssids = prefs.getStringSet("networkMatchSsids", emptySet()).orEmpty().toList()
        inputs.bssids = prefs.getStringSet("networkMatchBssids", emptySet()).orEmpty().toList()
        inputs.slots = prefs.getStringSet("networkMatchSimSlots", emptySet()).orEmpty().toList()
        inputs.mccMncs = prefs.getStringSet("networkMatchMccMnc", emptySet()).orEmpty().toList()
        inputs.matchAction = prefs.getString("networkMatchAction", "start").orEmpty()
        inputs.unmatchAction = prefs.getString("networkUnmatchAction", "none").orEmpty()
        inputs.apiPort = prefs.getInt("proxyControllerPort", MihomoStartupConfig.CONTROLLER_PORT)
        inputs.apiSecret = prefs.getString("proxyControllerSecret", "").orEmpty()
        inputs.mode = prefs.getString("proxyBaseMode", "").orEmpty()
        inputs.config = prefs.getString("proxyUiLastConfig", "").orEmpty()
        inputs.intervalSeconds = prefs.getInt("proxyApiHistoryDaemonInterval", DaemonMonitorPolicy.DEFAULT_INTERVAL_SECONDS)
        return DaemonMonitorPolicy.body(inputs)
    }

    /** Shell fragment for the deployment command: written with every start (boot plans keep it). */
    @JvmStatic
    fun exportCommand(prefs: SharedPreferences): String = DaemonMonitorPolicy.installCommand(policyBody(prefs))

    /**
     * Settings changed while a session may run: rewrite the policy (the monitor reloads it on its
     * next tick) and start the monitor when it became wanted. No Root call when nothing runs.
     */
    @JvmStatic
    fun exportAsync(context: Context) {
        val app = context.applicationContext
        try {
            exporter.execute {
                val prefs = app.getSharedPreferences("hetu", 0)
                if (!prefs.getBoolean("proxyRootRuntimeRunning", false)) return@execute
                val body = policyBody(prefs)
                val command = DaemonMonitorPolicy.installCommand(body) +
                    (if (DaemonMonitorPolicy.wanted(body)) "; sh $SCRIPT monitor-ensure >/dev/null 2>&1" else "") + "; exit 0"
                try {
                    if (RootBridge.rootShell(app, command, 8_000L).ok())
                        prefs.edit().putString("daemonMonitorPolicyGen", DaemonMonitorPolicy.generation(body)).apply()
                } catch (_: Throwable) { }
            }
        } catch (_: Exception) { }
    }

    /** Worker thread only. Unreadable or failing replies mean "not alive": the App keeps acting. */
    @JvmStatic
    fun status(context: Context): DaemonMonitorPolicy.Status = try {
        val result = RootBridge.rootShell(context.applicationContext, "sh $SCRIPT monitor-status", 5_000L)
        if (result.ok()) DaemonMonitorPolicy.parseStatus(result.output) else DaemonMonitorPolicy.DOWN
    } catch (_: Throwable) { DaemonMonitorPolicy.DOWN }

    /**
     * Pulls the daemon's closed traffic minutes into the history database: one batch, committed
     * with its id (a batch re-delivered after a crash is skipped), then acknowledged. At most once
     * every 15 seconds unless [force].
     */
    suspend fun pullTraffic(context: Context, force: Boolean = false): Int = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences("hetu", 0)
        if (!prefs.getBoolean("proxyApiHistoryEnabled", false)) return@withContext 0
        val now = System.currentTimeMillis()
        if (!force && now >= lastPull && now - lastPull < 15_000L) return@withContext 0
        lastPull = now
        var total = 0
        repeat(4) {
            val reply = try { RootBridge.rootShell(app, "sh $SCRIPT traffic-take", 15_000L) } catch (_: Throwable) { return@withContext total }
            if (!reply.ok()) return@withContext total
            val batch = DaemonMonitorPolicy.parseBatch(reply.output) ?: return@withContext total
            total += ProxyApiHistoryStore.ingestDaemonBatch(app, batch)
            try { RootBridge.rootShell(app, "sh $SCRIPT traffic-ack " + RootBridge.quote(batch.id), 8_000L) } catch (_: Throwable) { return@withContext total }
        }
        total
    }
}
