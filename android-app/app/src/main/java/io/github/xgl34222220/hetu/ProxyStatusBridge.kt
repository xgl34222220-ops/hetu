package io.github.xgl34222220.hetu

import android.content.Context

/** Fast runtime truth: a slow full status probe must not be interpreted as "proxy stopped". */
internal object ProxyStatusBridge {
    fun rootProxyRunning(context: Context): Boolean {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences("hetu", Context.MODE_PRIVATE)
        return try {
            val command = "P=\$(cat /data/adb/hetu/run/core.pid 2>/dev/null || echo 0); " +
                "case \"\$P\" in ''|*[!0-9]*) P=0;; esac; " +
                "if [ \"\$P\" -gt 0 ] && kill -0 \"\$P\" >/dev/null 2>&1; then " +
                "EXE=\$(readlink \"/proc/\$P/exe\" 2>/dev/null || true); " +
                "case \"\$EXE\" in /data/adb/hetu/bin/core) printf 1;; *) printf 0;; esac; " +
                "else printf 0; fi"
            val result = RootBridge.rootShell(app, command, 3_500L)
            if (result.ok()) {
                val running = result.output.trim() == "1"
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
