package io.github.xgl34222220.hetu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure presentation checks: the reference labels must describe actual core counters. */
class PanelConceptModelTest {
    private fun connection(id: String, up: Long, down: Long) = ProxyConnectionUi(
        id = id, host = "example.test:443", rule = "Match", rulePayload = "", chain = "DIRECT",
        upload = up, download = down,
    )

    @Test fun subscriptionBadgeShowsRemainingNotUsed() {
        assertEquals(80, panelRemainingPercent(.2f))
        assertEquals(90, panelRemainingPercent(.1f))
        assertEquals(75, panelRemainingPercent(.25f))
        assertEquals(5, panelRemainingPercent(.95f))
    }

    @Test fun remainingPercentageStaysWithinBounds() {
        assertEquals(0, panelRemainingPercent(2f))
        assertEquals(100, panelRemainingPercent(-1f))
    }

    @Test fun firstConnectionObservationHasNoInventedRate() {
        assertTrue(panelConnectionRates(emptyMap(), listOf(connection("a", 100, 100)), 1000).isEmpty())
    }

    @Test fun ratesUseActualElapsedTimeAndCounterDeltas() {
        val old = connection("a", 100, 1000)
        val result = panelConnectionRates(mapOf("a" to old), listOf(connection("a", 1100, 5000)), 2000)
        assertEquals(PanelConnectionRate(500, 2000), result["a"])
    }

    @Test fun resetCountersNeverShowNegativeThroughput() {
        val old = connection("a", 900, 1000)
        val result = panelConnectionRates(mapOf("a" to old), listOf(connection("a", 5, 7)), 1000)
        assertEquals(PanelConnectionRate(0, 0), result["a"])
    }

    @Test fun removedAndNewConnectionsDoNotReuseAnotherRate() {
        val old = connection("old", 20, 20)
        val result = panelConnectionRates(mapOf("old" to old), listOf(connection("new", 100, 100)), 1000)
        assertFalse(result.containsKey("old"))
        assertFalse(result.containsKey("new"))
    }
}
