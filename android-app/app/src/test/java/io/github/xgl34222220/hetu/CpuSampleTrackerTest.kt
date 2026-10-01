package io.github.xgl34222220.hetu

import org.junit.Assert.*
import org.junit.Test

class CpuSampleTrackerTest {
    private fun sample(at: Long, ticks: Long = 100, system: Long = at, pid: Int = 42, elapsed: Long = at) =
        CpuCounterSample(pid, ticks, system, elapsed, at, true)

    @Test fun repeatedCacheIsNotASecondObservationAndAnActualIdleIntervalIsZero() {
        val tracker = CpuSampleTracker()
        val first = sample(1_000)
        assertNull(tracker.update(first, true, 4))
        assertNull(tracker.update(first, true, 4))
        val idle = tracker.update(sample(2_000), true, 4)!!
        assertEquals(0f, idle.percent, 0f)
        assertEquals(idle, tracker.update(sample(2_000), true, 4))
    }

    @Test fun failureStopAndProcessRestartAllRequireTwoFreshSamples() {
        val tracker = CpuSampleTracker()
        assertNull(tracker.update(sample(1_000), true, 4))
        assertNotNull(tracker.update(sample(2_000, ticks = 150), true, 4))
        assertNull(tracker.update(sample(2_000).copy(valid = false), true, 4))
        assertNull(tracker.update(sample(3_000, ticks = 200), true, 4))
        assertNotNull(tracker.update(sample(4_000, ticks = 220), true, 4))
        assertNull(tracker.update(sample(5_000, ticks = 300, pid = 43), true, 4))
        assertNotNull(tracker.update(sample(6_000, ticks = 310, pid = 43), true, 4))
        assertNull(tracker.update(sample(7_000, ticks = 350, pid = 43, elapsed = 1), true, 4))
        assertNotNull(tracker.update(sample(8_000, ticks = 360, pid = 43, elapsed = 2), true, 4))
        assertNull(tracker.update(sample(9_000), false, 4))
        assertNull(tracker.update(sample(10_000), true, 4))
    }

    @Test fun invalidCountersAndHistoryGapsNeverBecomeArtificialZeroReadings() {
        val tracker = CpuSampleTracker()
        assertNull(tracker.update(sample(1_000, ticks = -1), true, 4))
        assertNull(tracker.update(sample(2_000, system = 0), true, 4))
        assertNull(tracker.update(sample(3_000), true, 4))
        assertNull(tracker.update(sample(4_000, system = 3_000), true, 4))
        assertNull(tracker.update(sample(5_000), true, 4))
        val segments = resourceSampleSegments(listOf(null, 10L, 20L, null, null, 40L, 0L, null))
        assertEquals(listOf(listOf(1, 2), listOf(5, 6)), segments.map { it.map { point -> point.index } })
        assertEquals(listOf(listOf(10L, 20L), listOf(40L, 0L)), segments.map { it.map { point -> point.value } })
    }
}
