package io.github.xgl34222220.hetu

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class StrategyPanelBehaviorTest {
    @Test fun searchSortAndTestedFilterUseFreshResultsIncludingFailures() {
        val nodes = listOf(ProxyNodeUi("Tokyo 2", lastDelay = 18, provider = "机场A"),
            ProxyNodeUi("Hong Kong", lastDelay = 300), ProxyNodeUi("Tokyo 1", provider = "机场B"),
            ProxyNodeUi("Taipei", lastDelay = 20))
        val fresh = mapOf("Tokyo 2" to -1L, "Tokyo 1" to 9L)
        assertEquals(listOf("Tokyo 1", "Taipei", "Hong Kong", "Tokyo 2"),
            strategyPanelNodes(nodes, fresh, "", "delay", false).map { it.name })
        assertEquals(listOf("Tokyo 1"), strategyPanelNodes(nodes, fresh, "tokyo 机场B", "config", true).map { it.name })
        assertEquals(listOf("Hong Kong", "Tokyo 1", "Taipei"), strategyPanelNodes(nodes, fresh, "", "config", true).map { it.name })
        assertEquals(nodes, strategyPanelNodes(nodes, emptyMap(), "", "config", false))
        assertEquals(listOf("Hong Kong", "Taipei", "Tokyo 1", "Tokyo 2"),
            strategyPanelNodes(nodes, fresh, "", "delay", false, descending = true).map { it.name })
    }
    @Test fun batchMeasurementsAreBoundedDeduplicatedAndReplaceFailedReadings() = runBlocking {
        var concurrent = 0; var peak = 0
        val active = mutableSetOf<String>(); val measured = mutableMapOf<String, Long>()
        measureStrategyNodes((1..30).map { "$it" } + "1", probe = { name ->
            concurrent++; peak = maxOf(peak, concurrent)
            try { delay(5); if (name == "2") error("timeout"); name.toLong() }
            finally { concurrent-- }
        }, onTesting = { name, busy -> if (busy) active.add(name) else active.remove(name); Unit },
            onMeasured = { name, value -> measured[name] = value })
        assertEquals(6, peak); assertEquals(30, measured.size)
        assertEquals(-1L, measured["2"]); assertEquals(1L, measured["1"]); assertTrue(active.isEmpty())
    }
    @Test fun cancellingMeasurementClearsQueuedAndRunningNodesWithoutFalseTimeouts() = runBlocking {
        val active = mutableSetOf<String>(); val measured = mutableMapOf<String, Long>()
        val started = CompletableDeferred<Unit>()
        val job = launch {
            measureStrategyNodes((1..20).map { "$it" }, probe = { started.complete(Unit); awaitCancellation() },
                onTesting = { name, busy -> if (busy) active.add(name) else active.remove(name); Unit },
                onMeasured = { name, value -> measured[name] = value })
        }
        started.await(); job.cancelAndJoin()
        assertTrue(active.isEmpty()); assertTrue(measured.isEmpty()); assertTrue(job.isCancelled)
    }
}
