package io.github.xgl34222220.hetu

import android.content.Context

/** Small package bridge used by the Compose home controller without exposing RootProxyManager publicly. */
internal object ProxyStatusBridge {
    fun rootProxyRunning(context: Context): Boolean = try {
        RootProxyManager(context.applicationContext).status().optBoolean("running", false)
    } catch (_: Exception) {
        false
    }
}
