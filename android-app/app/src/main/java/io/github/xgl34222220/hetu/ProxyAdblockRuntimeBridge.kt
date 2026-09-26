package io.github.xgl34222220.hetu

import android.content.Context

/** Narrow bridge for rule UI to refresh the already-running private Mihomo ruleset. */
internal object ProxyAdblockRuntimeBridge {
    /** A user toggle applies the rule chain now; it never restarts the Root transport. */
    fun setEnabled(context: Context, enabled: Boolean): String {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences("hetu", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("proxyAdblockChain", enabled).apply()
        val manager = RootProxyManager(app)
        if (!manager.status().optBoolean("running", false)) return "设置已保存，下次启动代理时生效"
        manager.reloadCurrentConfig()
        val mode = MihomoControllerClient(app).configs().optString("mode", "")
        return when {
            !enabled -> "广告过滤已关闭"
            AdblockRuleInspection.isRuleMode(mode) -> "广告过滤已开启，运行规则已确认加载"
            else -> "过滤规则已加载；当前为全局或直连模式，请切换到规则模式"
        }
    }

    fun hotReload(context: Context): String =
        RootProxyManager(context.applicationContext).refreshAdblockRuntime()
}
