package io.github.xgl34222220.hetu

import android.content.Context

internal object ProxyApiHistoryStore {
    private const val KEY = "proxyApiTrafficHistory"
    private const val MAX_POINTS = 180
    private const val MAX_AGE_MS = 10 * 60_000L

    fun record(context: Context, upload: Long, download: Long, now: Long = System.currentTimeMillis()) {
        val prefs = context.applicationContext.getSharedPreferences("hetu", 0)
        if (!prefs.getBoolean("proxyApiHistoryEnabled", false)) return
        val points = parse(prefs.getString(KEY, "").orEmpty()).toMutableList()
        val last = points.lastOrNull()
        if (last != null && now - last.first < 900L) return
        points += Triple(now, upload.coerceAtLeast(0L), download.coerceAtLeast(0L))
        val cutoff = now - MAX_AGE_MS
        val kept = points
            .filter { it.first >= cutoff }
            .takeLast(MAX_POINTS)
        prefs.edit().putString(KEY, encode(kept)).apply()
    }

    fun recent(context: Context, windowMs: Long = 60_000L, now: Long = System.currentTimeMillis()): List<Triple<Long, Long, Long>> {
        val prefs = context.applicationContext.getSharedPreferences("hetu", 0)
        if (!prefs.getBoolean("proxyApiHistoryEnabled", false)) return emptyList()
        val cutoff = now - windowMs
        return parse(prefs.getString(KEY, "").orEmpty()).filter { it.first in cutoff..(now + 5_000L) }
    }

    fun clear(context: Context) {
        context.applicationContext.getSharedPreferences("hetu", 0).edit().remove(KEY).apply()
    }

    private fun encode(points: List<Triple<Long, Long, Long>>): String =
        points.joinToString(";") { (time, up, down) -> "$time,$up,$down" }

    private fun parse(raw: String): List<Triple<Long, Long, Long>> =
        raw.split(';').mapNotNull { item ->
            val values = item.split(',')
            if (values.size != 3) return@mapNotNull null
            val time = values[0].toLongOrNull() ?: return@mapNotNull null
            val up = values[1].toLongOrNull() ?: return@mapNotNull null
            val down = values[2].toLongOrNull() ?: return@mapNotNull null
            if (time <= 0L || up < 0L || down < 0L) null else Triple(time, up, down)
        }
}
