package io.github.xgl34222220.hetu

import android.content.Context

/** Fast runtime truth: a slow full status probe must not be interpreted as "proxy stopped". */
internal object ProxyStatusBridge {
    fun rootProxyRunning(context: Context): Boolean {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences("hetu", Context.MODE_PRIVATE)
        return try {
            val command = ProxyContinuity.coreProbeCommand("/data/adb/hetu/run/core.pid", "/data/adb/hetu/bin/core")
            val result = RootBridge.rootShell(app, command, 3_500L)
            val process = ProxyContinuity.processState(result.ok(), result.output)
            if (process != ProxyContinuity.ProcessState.UNKNOWN) {
                val running = process == ProxyContinuity.ProcessState.ALIVE
                prefs.edit().putBoolean("proxyRootRuntimeRunning", running).apply()
                running
            } else {
                prefs.getBoolean("proxyRootRuntimeRunning", false) &&
                    prefs.getBoolean("proxyRootWanted", false)
            }
        } catch (_: Exception) {
            prefs.getBoolean("proxyRootRuntimeRunning", false) &&
                prefs.getBoolean("proxyRootWanted", false)
        }
    }
}
