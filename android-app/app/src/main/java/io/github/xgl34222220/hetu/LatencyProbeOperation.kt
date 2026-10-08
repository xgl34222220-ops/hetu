package io.github.xgl34222220.hetu

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.IOException

internal class IncompleteLatencyProbe(val results: Map<String, Long> = emptyMap()) :
    IOException("本轮测速已到时限，部分结果未返回，已保留已有读数")

/** Cancellation closes real sockets, rather than merely timing out a blocked IO coroutine. */
internal suspend fun <T> latencyProbeOperation(
    timeoutMs: Long = LatencyProbeBudget.DEFAULT_TIMEOUT_MS,
    block: suspend () -> T,
): T {
    // Nested group helpers share the enclosing operation, including its metadata reads.
    if (LatencyProbeBudget.CURRENT.get() != null) return block()
    val budget = LatencyProbeBudget(timeoutMs)
    return try {
        withContext(Dispatchers.IO + LatencyProbeBudget.CURRENT.asContextElement(budget)) {
            coroutineScope {
                val cancellation = launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                    try { awaitCancellation() } finally { budget.close() }
                }
                try {
                    // A short cleanup margin lets closed sockets return partial results first.
                    // This also bounds suspension in metadata mutexes, not just network IO.
                    withTimeout(timeoutMs + 250) { block() }
                } catch (timeout: TimeoutCancellationException) {
                    currentCoroutineContext().ensureActive()
                    throw IncompleteLatencyProbe()
                } finally { cancellation.cancel() }
            }
        }
    } catch (error: Exception) {
        // Closing a cancelled request wakes blocking socket IO with IOException.
        // Preserve coroutine cancellation before any ViewModel error reporting.
        currentCoroutineContext().ensureActive()
        throw error
    } finally { budget.close() }
}
