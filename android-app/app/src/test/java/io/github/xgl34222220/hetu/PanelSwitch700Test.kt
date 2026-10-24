package io.github.xgl34222220.hetu

import io.github.xgl34222220.hetu.panel.SelectionOutcome700
import io.github.xgl34222220.hetu.panel.mergeRetry700
import io.github.xgl34222220.hetu.panel.retryCandidates700
import io.github.xgl34222220.hetu.panel.selectionOutcome700
import io.github.xgl34222220.hetu.panel.tapUnpins700
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 7.0.0 panel switching confirmation and first-test retry selection (pure logic). */
class PanelSwitch700Test {
    @Test fun selectorConfirmsOnlyWhenNowFollows() {
        assertEquals(SelectionOutcome700.Confirmed, selectionOutcome700("Selector", "B", "", "B"))
        assertEquals(SelectionOutcome700.Unconfirmed, selectionOutcome700("Selector", "A", "", "B"))
        assertEquals(SelectionOutcome700.Unconfirmed, selectionOutcome700("Selector", null, null, "B"))
    }

    @Test fun urlTestWithFixedButOtherNowIsAPinNotAFailure() {
        assertEquals(SelectionOutcome700.Pinned, selectionOutcome700("URLTest", "A", "B", "B"))
        assertEquals(SelectionOutcome700.Pinned, selectionOutcome700("Fallback", "A", "B", "B"))
    }

    @Test fun urlTestConfirmsWhenNowAndFixedAgree() {
        assertEquals(SelectionOutcome700.Confirmed, selectionOutcome700("URLTest", "B", "B", "B"))
        assertEquals(SelectionOutcome700.Confirmed, selectionOutcome700("URLTest", "B", "", "B"))
        assertEquals(SelectionOutcome700.Confirmed, selectionOutcome700("urltest", "B", null, "B"))
    }

    @Test fun urlTestPinnedElsewhereIsUnconfirmed() {
        assertEquals(SelectionOutcome700.Unconfirmed, selectionOutcome700("URLTest", "A", "A", "B"))
        assertEquals(SelectionOutcome700.Unconfirmed, selectionOutcome700("Fallback", "A", "", "B"))
    }

    @Test fun tappingThePinnedNodeOfAnAutomaticGroupUnpins() {
        assertTrue(tapUnpins700("URLTest", "B", "B"))
        assertTrue(tapUnpins700("Fallback", "B", "B"))
        assertFalse(tapUnpins700("URLTest", "B", "A"))
        assertFalse(tapUnpins700("URLTest", "", "B"))
        assertFalse(tapUnpins700("Selector", "B", "B"))
    }

    @Test fun retryPicksOnlyExplicitFailuresInTargetOrder() {
        val targets = listOf("a", "b", "c", "d", "e", "a")
        val first = mapOf("a" to 120L, "b" to 0L, "c" to -1L, "d" to -2L)
        assertEquals(listOf("b", "c", "d"), retryCandidates700(targets, first))
        assertEquals(emptyList<String>(), retryCandidates700(targets, mapOf("a" to 1L)))
    }

    @Test fun retryOnlyReplacesFailuresWithRealMeasurements() {
        val first = mapOf("a" to 120L, "b" to 0L, "c" to -1L, "d" to -2L)
        val retry = mapOf("b" to 340L, "c" to -1L, "d" to -2L, "z" to 50L)
        assertEquals(mapOf("a" to 120L, "b" to 340L, "c" to -1L, "d" to -2L), mergeRetry700(first, retry))
    }
}
