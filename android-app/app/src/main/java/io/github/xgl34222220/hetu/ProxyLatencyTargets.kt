package io.github.xgl34222220.hetu

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri

internal data class ProxyLatencyTarget(val name: String, val url: String)

internal object ProxyLatencyTargets {
    private const val COUNT = 3
    private const val NAME_PREFIX = "proxyLatencyTargetName"
    private const val URL_PREFIX = "proxyLatencyTargetUrl"
    private const val LAST_PREFIX = "proxyUiLastDelayTarget"

    val defaults = listOf(
        ProxyLatencyTarget("Baidu", "https://www.baidu.com/"),
        ProxyLatencyTarget("Cloudflare", "https://cp.cloudflare.com/generate_204"),
        ProxyLatencyTarget("Google", "https://www.gstatic.com/generate_204"),
    )

    fun load(context: Context): List<ProxyLatencyTarget> =
        load(context.getSharedPreferences("hetu", Context.MODE_PRIVATE))

    fun load(prefs: SharedPreferences): List<ProxyLatencyTarget> =
        (0 until COUNT).map { index ->
            val fallback = defaults[index]
            ProxyLatencyTarget(
                prefs.getString("$NAME_PREFIX$index", fallback.name).orEmpty().trim().ifBlank { fallback.name },
                prefs.getString("$URL_PREFIX$index", fallback.url).orEmpty().trim().ifBlank { fallback.url },
            )
        }

    fun save(prefs: SharedPreferences, values: List<ProxyLatencyTarget>) {
        val normalized = (0 until COUNT).map { index -> values.getOrNull(index) ?: defaults[index] }
        prefs.edit().apply {
            normalized.forEachIndexed { index, target ->
                putString("$NAME_PREFIX$index", target.name.trim().take(40))
                putString("$URL_PREFIX$index", target.url.trim().take(400))
            }
        }.apply()
    }

    fun reset(prefs: SharedPreferences) {
        prefs.edit().apply {
            (0 until COUNT).forEach { index ->
                remove("$NAME_PREFIX$index")
                remove("$URL_PREFIX$index")
                remove("$LAST_PREFIX$index")
            }
        }.apply()
    }

    fun lastResults(prefs: SharedPreferences): Map<String, Long> {
        val targets = load(prefs)
        return buildMap {
            targets.forEachIndexed { index, target ->
                val value = prefs.getLong("$LAST_PREFIX$index", -2L)
                if (value != -2L) put(target.name, value)
            }
        }
    }

    fun persistLast(prefs: SharedPreferences, measured: Map<String, Long>) {
        val targets = load(prefs)
        prefs.edit().apply {
            targets.forEachIndexed { index, target ->
                putLong("$LAST_PREFIX$index", measured[target.name] ?: -2L)
            }
        }.apply()
    }

    fun validate(target: ProxyLatencyTarget): String? {
        if (target.name.trim().isBlank()) return "测速目标名称不能为空"
        if (target.url.trim().isBlank()) return "测速地址不能为空"
        return runCatching {
            val uri = Uri.parse(target.url.trim())
            if (uri.scheme !in setOf("http", "https") || uri.host.isNullOrBlank()) {
                "测速地址必须是有效的 HTTP(S) URL"
            } else null
        }.getOrElse { "测速地址格式无效" }
    }
}

