package io.github.xgl34222220.hetu

import io.github.xgl34222220.hetu.panel.PanelData
import io.github.xgl34222220.hetu.panel.PanelDelay
import io.github.xgl34222220.hetu.panel.PanelGroup
import io.github.xgl34222220.hetu.panel.PanelNode
import io.github.xgl34222220.hetu.panel.panelDelayOf
import org.junit.Assert.*
import org.junit.Test

/** New recovery implementation checks; no claim that missing original tests were recovered. */
class LatencyPresentationRecovery93Test {
    @Test fun failedProbeIsDistinctFromTimeoutAndUntested() {
        assertEquals(PanelDelay.Failed, panelDelayOf(-2L))
        assertEquals(PanelDelay.Timeout, panelDelayOf(-1L))
        assertEquals(PanelDelay.Unknown, panelDelayOf(null))
        assertEquals(PanelDelay.Ms(52L), panelDelayOf(52L))
    }

    @Test fun aliasProbeShowsItsBusyStateBeforeTheSelectedLeafReading() {
        val data = PanelData(groups = listOf(PanelGroup("Auto", "URLTest", listOf(PanelNode("leaf")), "leaf")),
            delays = mapOf("Auto" to PanelDelay.Testing, "leaf" to PanelDelay.Ms(52L)))
        assertEquals(PanelDelay.Testing, data.delayOf("Auto"))
        assertEquals(PanelDelay.Ms(52L), data.delayOf("leaf"))
        assertEquals(PanelDelay.Ms(52L), data.copy(delays = data.delays - "Auto").delayOf("Auto"))
    }

    @Test fun activeProbeRejectsOldCoreHistoryButUnrelatedNodeCanRefresh() {
        val groups = listOf(ProxyGroupUi("Auto", "URLTest", "busy", listOf(
            ProxyNodeUi("busy", lastDelay = 900, lastDelayAt = 5), ProxyNodeUi("idle", lastDelay = 72, lastDelayAt = 5))))
        val readings = mutableMapOf("busy" to 44L, "idle" to 50L)
        syncCoreLatencyResults(groups, readings, snapshotStartedAt = 100L, protectedNodes = setOf("busy"))
        assertEquals(mapOf("busy" to 44L, "idle" to 72L), readings)
    }

    @Test fun completedProbeRemainsProtectedFromEarlierPollThenFreshPollCanUpdate() {
        val groups = listOf(ProxyGroupUi("Auto", "URLTest", "node", listOf(ProxyNodeUi("node", lastDelay = 72))))
        val readings = mutableMapOf("node" to 44L)
        syncCoreLatencyResults(groups, readings, mapOf("node" to 200L), 100L, emptySet())
        assertEquals(44L, readings["node"])
        syncCoreLatencyResults(groups, readings, mapOf("node" to 200L), 300L, emptySet())
        assertEquals(72L, readings["node"])
    }
}
