package io.github.xgl34222220.hetu

import android.content.Context

/** Narrow bridge for rule UI to refresh the already-running private Mihomo ruleset. */
internal object ProxyAdblockRuntimeBridge {
    fun hotReload(context: Context): String =
        RootProxyManager(context.applicationContext).refreshAdblockRuntime()
}
