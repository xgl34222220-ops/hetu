package io.github.xgl34222220.hetu.panel

/**
 * 7.0.0 panel switching: pure decision logic, kept free of Android types so it is unit-testable.
 */
internal enum class SelectionOutcome700 {
    /** The core is routing through the requested node. */
    Confirmed,
    /** URLTest/Fallback stored the pin (`fixed`) but keeps another node until the pin tests alive. */
    Pinned,
    /** Nothing in the readback reflects the request yet. */
    Unconfirmed,
}

internal fun isSelectorGroup700(type: String): Boolean = type.equals("Selector", ignoreCase = true)

/**
 * Selector: success when `now` follows. Automatic groups (URLTest/Fallback): Mihomo stores the
 * PUT as `fixed` and only moves `now` once that node is alive for the group's test URL, so a
 * `fixed` match is a successful pin, not a failure.
 */
internal fun selectionOutcome700(type: String, now: String?, fixed: String?, node: String): SelectionOutcome700 = when {
    isSelectorGroup700(type) && now == node -> SelectionOutcome700.Confirmed
    !isSelectorGroup700(type) && now == node && (fixed.isNullOrEmpty() || fixed == node) -> SelectionOutcome700.Confirmed
    fixed == node -> SelectionOutcome700.Pinned
    now == node -> SelectionOutcome700.Confirmed
    else -> SelectionOutcome700.Unconfirmed
}

/** Tapping the node an automatic group is already pinned to releases the pin. */
internal fun tapUnpins700(type: String, fixed: String, node: String): Boolean =
    !isSelectorGroup700(type) && fixed.isNotEmpty() && fixed == node

internal const val SELECT_CONFIRM_POLLS_700 = 6
internal const val SELECT_CONFIRM_INTERVAL_MS_700 = 350L
internal const val RETRY_DELAY_TIMEOUT_MS_700 = 6000
internal const val RETRY_DELAY_CONCURRENCY_700 = 6

/**
 * Nodes whose first group-test reading was a failure (0, timeout -1, error -2, any non-positive)
 * or absent, in target order. Each is retested exactly once with a single per-proxy probe.
 */
internal fun retryCandidates700(targets: List<String>, results: Map<String, Long>): List<String> =
    targets.distinct().filter { (results[it] ?: 0L) <= 0L }

/** A retry only replaces the first reading when it measured a real delay. */
internal fun mergeRetry700(first: Map<String, Long>, retry: Map<String, Long>): Map<String, Long> {
    val out = LinkedHashMap(first)
    retry.forEach { (node, value) -> if (value > 0L || node !in out) out[node] = value }
    return out
}
