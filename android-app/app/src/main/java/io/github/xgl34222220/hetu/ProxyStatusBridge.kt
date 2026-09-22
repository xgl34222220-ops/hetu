package io.github.xgl34222220.hetu

import android.content.Context

/**
 * Non-blocking runtime hint for Compose.
 *
 * The foreground continuity service and the periodic full status probe are the
 * authorities that update proxyRootRuntimeRunning. A UI refresh must never spawn
 * a new su process just to paint the same cached state every few seconds.
 */
internal object ProxyStatusBridge {
    fun rootProxyRunning(context: Context): Boolean {
        val prefs = context.applicationContext.getSharedPreferences("hetu", Context.MODE_PRIVATE)
        return prefs.getBoolean("proxyRootRuntimeRunning", false) &&
            prefs.getBoolean("proxyRootWanted", false)
    }
}
