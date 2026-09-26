package io.github.xgl34222220.hetu

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** Bound a UI-requested measurement wave; preserve cancellation and clear pending markers. */
internal suspend fun measureStrategyNodes(names: List<String>, probe: suspend (String) -> Long,
    onTesting: (String, Boolean) -> Unit, onMeasured: (String, Long) -> Unit,
    onError: (String, Exception) -> Unit = { _, _ -> }) {
    val targets = names.distinct()
    val permits = Semaphore(6)
    targets.forEach { onTesting(it, true) }
    try {
        coroutineScope {
            targets.map { name -> async {
                permits.withPermit {
                    try {
                        val result = try { probe(name).takeIf { it > 0L } ?: -2L }
                        catch (cancel: CancellationException) { throw cancel }
                        catch (failure: MihomoControllerClient.DelayFailure) { if (failure.timedOut) -1L else -2L }
                        catch (error: Exception) { onError(name, error); null }
                        if (result != null) onMeasured(name, result)
                    } finally { onTesting(name, false) }
                }
            } }.awaitAll()
        }
    } finally { targets.forEach { onTesting(it, false) } }
}
