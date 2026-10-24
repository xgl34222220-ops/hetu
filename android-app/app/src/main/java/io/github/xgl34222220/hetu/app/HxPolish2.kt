package io.github.xgl34222220.hetu

/*
 * Second polish round (2026-10-13): small pure decisions shared by the panel, log and network-test
 * pages, kept here so they can be unit-tested without composing a screen.
 */

/**
 * A quiet (polling) re-read of the runtime log whose text did not change since the last parse:
 * the caller keeps the current entries instead of re-parsing and recomposing the whole list.
 * A manual (non-quiet) read always re-parses.
 */
internal fun hxLogUnchanged(quiet: Boolean, previous: String?, raw: String): Boolean =
    quiet && previous != null && previous == raw

/**
 * The log page's first read, before anything is shown. Live refresh flips `loading` every 2 s;
 * only this first read disables the file actions (清空 / 加载更早), so they do not blink.
 */
internal fun hxLogFirstRead(loading: Boolean, content: String): Boolean = loading && content.isEmpty()

/**
 * What an empty traffic-trend chart says instead of a blank 148 dp area: nothing when there is a
 * curve to draw, otherwise why there is none (both series switched off, or no traffic sampled yet).
 */
internal fun hxTrendEmptyLabel(seriesShown: Int, peak: Float): String? = when {
    seriesShown == 0 -> "已隐藏上行与下行"
    peak <= 0f -> "等待流量数据"
    else -> null
}

/**
 * The 连通性 summary: "x/y 可用" from whatever finished, prefixed with 测试中断 when the run
 * itself failed (no network, port probe error), so a row of dashes is never left unexplained.
 */
internal fun hxNetTestSummary(ok: Int, total: Int, finished: Int, failed: Boolean, available: String, interrupted: String): String = when {
    failed && finished == 0 -> interrupted
    failed -> "$interrupted · $ok/$total $available"
    finished == 0 -> ""
    else -> "$ok/$total $available"
}
