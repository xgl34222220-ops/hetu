package io.github.xgl34222220.hetu

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * 测速历史: every finished (or stopped) test run is kept so it can be looked at again later.
 *
 * Two kinds of run are recorded:
 *  * [SpeedRunKind.Latency] — a node delay test (one strategy group or 全部). Per node: delay in ms, or
 *    timeout / no response. The core's controller does not report bytes, so the traffic is an estimate
 *    from the number of probes and is always marked 估算.
 *  * [SpeedRunKind.Bandwidth] — the 网速 test. Bytes are the real counters; the run is marked 估算 when it
 *    was stopped early or the sample is too small to be a reliable rate.
 *
 * Separate preference file (not part of settings backup), newest first, at most [MAX_RUNS] runs and
 * [MAX_NODES] nodes per run. Additive: it only observes results; the probes themselves are unchanged.
 */
internal enum class SpeedRunKind { Latency, Bandwidth }

/** delayMs: > 0 measured, [TIMEOUT] the node timed out, [NO_RESPONSE] no result came back for it. */
internal data class SpeedNodeSample(val name: String, val delayMs: Long) {
    companion object {
        const val TIMEOUT = -1L
        const val NO_RESPONSE = -2L
    }
}

internal data class SpeedRunRecord(
    val id: Long,
    val at: Long,
    val kind: SpeedRunKind,
    val title: String,
    val nodes: List<SpeedNodeSample> = emptyList(),
    val latencyMs: Long = -1L,
    val downMbps: Double? = null,
    val upMbps: Double? = null,
    val bytesDown: Long = 0L,
    val bytesUp: Long = 0L,
    val estimated: Boolean = false,
    val hint: String = "",
) {
    val ok: Int get() = nodes.count { it.delayMs > 0 }
    val timeouts: Int get() = nodes.count { it.delayMs == SpeedNodeSample.TIMEOUT }
    val noResponse: Int get() = nodes.count { it.delayMs <= SpeedNodeSample.NO_RESPONSE }
    val best: SpeedNodeSample? get() = nodes.filter { it.delayMs > 0 }.minByOrNull { it.delayMs }
}

internal object SpeedTestHistory {
    const val PREFS = "hetu_speed_history"
    private const val KEY = "runsV1"
    const val MAX_RUNS = 30
    const val MAX_NODES = 300

    /** Estimated wire bytes of one delay probe through a proxy (proxy + TLS handshake and one small HTTP exchange). */
    const val PROBE_UP_BYTES = 1_536L
    const val PROBE_DOWN_BYTES = 4_096L
    /** A timed-out probe still sent its handshake; count it as about one third of a full exchange. */
    const val TIMEOUT_UP_BYTES = 768L
    const val TIMEOUT_DOWN_BYTES = 1_024L
    /** Below this a bandwidth phase is too short to be a trustworthy rate. */
    const val RELIABLE_SAMPLE_BYTES = 2L * 1024 * 1024

    /** One delay-test run. [results] holds only the readings this run produced; absent targets got no response. */
    fun latencyRecord(title: String, targets: List<String>, results: Map<String, Long>, now: Long): SpeedRunRecord {
        val nodes = targets.distinct().take(MAX_NODES).map { name ->
            val v = results[name]
            SpeedNodeSample(name, when {
                v == null -> SpeedNodeSample.NO_RESPONSE
                v > 0 -> v
                v == SpeedNodeSample.TIMEOUT -> SpeedNodeSample.TIMEOUT
                else -> SpeedNodeSample.NO_RESPONSE
            })
        }
        var up = 0L
        var down = 0L
        nodes.forEach {
            when {
                it.delayMs > 0 -> { up += PROBE_UP_BYTES; down += PROBE_DOWN_BYTES }
                it.delayMs == SpeedNodeSample.TIMEOUT -> { up += TIMEOUT_UP_BYTES; down += TIMEOUT_DOWN_BYTES }
            }
        }
        val ok = nodes.count { it.delayMs > 0 }
        val silent = nodes.count { it.delayMs <= SpeedNodeSample.NO_RESPONSE }
        val hint = when {
            nodes.isNotEmpty() && ok == 0 && silent == nodes.size -> "所有节点均无响应：请确认代理正在运行、控制接口可用，然后重试"
            nodes.isNotEmpty() && ok == 0 -> "所有节点均超时：请检查订阅节点是否有效，或测速地址能否经代理访问"
            silent > 0 -> "$silent 个节点没有返回结果（未计入超时）"
            else -> ""
        }
        return SpeedRunRecord(now, now, SpeedRunKind.Latency, title, nodes, bytesDown = down, bytesUp = up, estimated = true, hint = hint)
    }

    /** One 网速 run, complete or stopped. */
    fun bandwidthRecord(
        title: String, latencyMs: Long, down: Pair<Double, Long>?, up: Pair<Double, Long>?,
        liveBytesDown: Long, liveBytesUp: Long, error: String?, now: Long,
    ): SpeedRunRecord {
        val bytesDown = down?.second ?: liveBytesDown
        val bytesUp = up?.second ?: liveBytesUp
        val incomplete = down == null || up == null
        val small = (down != null && down.second < RELIABLE_SAMPLE_BYTES) || (up != null && up.second < RELIABLE_SAMPLE_BYTES / 4)
        val hint = when {
            !error.isNullOrBlank() -> error
            down == null && latencyMs < 0 -> "测速服务器没有响应：请检查网络或当前节点"
            incomplete -> "测速未完成，结果仅为已完成阶段"
            small -> "样本过少，速率为估算值"
            else -> ""
        }
        return SpeedRunRecord(now, now, SpeedRunKind.Bandwidth, title, latencyMs = latencyMs, downMbps = down?.first, upMbps = up?.first,
            bytesDown = bytesDown.coerceAtLeast(0), bytesUp = bytesUp.coerceAtLeast(0), estimated = incomplete || small || !error.isNullOrBlank(), hint = hint)
    }

    fun load(context: Context): List<SpeedRunRecord> =
        decode(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "").orEmpty())

    @Synchronized
    fun add(context: Context, record: SpeedRunRecord) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val runs = decode(prefs.getString(KEY, "").orEmpty())
        val id = if (runs.any { it.id == record.id }) (runs.maxOf { it.id } + 1) else record.id
        prefs.edit().putString(KEY, encode(listOf(record.copy(id = id)) + runs)).apply()
    }

    @Synchronized
    fun delete(context: Context, id: Long) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY, encode(decode(prefs.getString(KEY, "").orEmpty()).filter { it.id != id })).apply()
    }

    @Synchronized
    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY).apply()
    }

    fun encode(runs: List<SpeedRunRecord>): String {
        val array = JSONArray()
        runs.sortedByDescending { it.at }.take(MAX_RUNS).forEach { r ->
            val o = JSONObject()
                .put("id", r.id).put("at", r.at).put("kind", r.kind.name).put("title", r.title)
                .put("latency", r.latencyMs).put("bytesDown", r.bytesDown).put("bytesUp", r.bytesUp)
                .put("estimated", r.estimated).put("hint", r.hint)
            r.downMbps?.let { o.put("down", it) }
            r.upMbps?.let { o.put("up", it) }
            if (r.nodes.isNotEmpty()) {
                val nodes = JSONArray()
                r.nodes.take(MAX_NODES).forEach { nodes.put(JSONArray().put(it.name).put(it.delayMs)) }
                o.put("nodes", nodes)
            }
            array.put(o)
        }
        return array.toString()
    }

    fun decode(text: String): List<SpeedRunRecord> {
        if (text.isBlank()) return emptyList()
        return try {
            val array = JSONArray(text)
            (0 until array.length()).mapNotNull { i ->
                val o = array.optJSONObject(i) ?: return@mapNotNull null
                val kind = runCatching { SpeedRunKind.valueOf(o.optString("kind")) }.getOrNull() ?: return@mapNotNull null
                val nodesJson = o.optJSONArray("nodes")
                val nodes = if (nodesJson == null) emptyList() else (0 until nodesJson.length()).mapNotNull { j ->
                    val pair = nodesJson.optJSONArray(j) ?: return@mapNotNull null
                    SpeedNodeSample(pair.optString(0), pair.optLong(1, SpeedNodeSample.NO_RESPONSE))
                }
                SpeedRunRecord(
                    id = o.optLong("id"), at = o.optLong("at"), kind = kind, title = o.optString("title"), nodes = nodes,
                    latencyMs = o.optLong("latency", -1L),
                    downMbps = if (o.has("down")) o.optDouble("down") else null,
                    upMbps = if (o.has("up")) o.optDouble("up") else null,
                    bytesDown = o.optLong("bytesDown"), bytesUp = o.optLong("bytesUp"),
                    estimated = o.optBoolean("estimated"), hint = o.optString("hint"),
                )
            }.sortedByDescending { it.at }.take(MAX_RUNS)
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** "↓ 1.2 MB ↑ 300 KB" plus 估算 when the numbers are not measured counters. */
    fun trafficText(r: SpeedRunRecord): String =
        "本次流量 ↓ ${HxFormat.bytes(r.bytesDown)} ↑ ${HxFormat.bytes(r.bytesUp)}" + if (r.estimated) "（估算）" else ""

    fun summary(r: SpeedRunRecord): String = when (r.kind) {
        SpeedRunKind.Latency -> buildString {
            append("${r.ok}/${r.nodes.size} 可用")
            if (r.timeouts > 0) append(" · ${r.timeouts} 超时")
            if (r.noResponse > 0) append(" · ${r.noResponse} 无响应")
            r.best?.let { append(" · 最快 ${it.name} ${it.delayMs} ms") }
        }
        SpeedRunKind.Bandwidth -> buildString {
            append("↓ ").append(r.downMbps?.let { "${formatMbps(it)} Mbps" } ?: "—")
            append(" · ↑ ").append(r.upMbps?.let { "${formatMbps(it)} Mbps" } ?: "—")
            if (r.latencyMs >= 0) append(" · ${r.latencyMs} ms")
        }
    }

    fun delayText(sample: SpeedNodeSample): String = when {
        sample.delayMs > 0 -> "${sample.delayMs} ms"
        sample.delayMs == SpeedNodeSample.TIMEOUT -> "超时"
        else -> "无响应"
    }

    fun timeText(at: Long): String =
        java.text.SimpleDateFormat("MM-dd HH:mm", Locale.ROOT).format(java.util.Date(at))
}
