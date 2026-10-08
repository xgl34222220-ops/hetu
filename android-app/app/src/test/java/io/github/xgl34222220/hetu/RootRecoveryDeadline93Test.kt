package io.github.xgl34222220.hetu

import android.app.Application
import android.os.SystemClock
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import java.io.IOException
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** New reconstruction tests; the thirteen recovered Android tests stay byte-identical. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class RootRecoveryDeadline93Test {
    @After fun clearScope() = RootProxyManager.endAutomaticRecoveryScope()

    @Test fun preparationNeverSpendsTheStartAndRollbackReserve() {
        RootProxyManager.beginAutomaticRecoveryScope(SystemClock.elapsedRealtime() + 170_000)
        assertEquals(5_000L, RootProxyManager.automaticRootTimeout(20_000))
        ShadowSystemClock.advanceBy(Duration.ofMillis(4_000))
        assertEquals(1_000L, RootProxyManager.automaticRootTimeout(20_000))
        ShadowSystemClock.advanceBy(Duration.ofMillis(1))
        try { RootProxyManager.automaticRootTimeout(1); fail("Start/rollback reserve must remain intact") }
        catch (_: IOException) { }
    }

    @Test fun endedAndIndependentThreadsCannotInheritAnOldDeadline() {
        RootProxyManager.beginAutomaticRecoveryScope(SystemClock.elapsedRealtime() + 166_000)
        assertEquals(1_000L, RootProxyManager.automaticRootTimeout(20_000))
        val executor = Executors.newSingleThreadExecutor()
        try { assertEquals(20_000L, executor.submit<Long> { RootProxyManager.automaticRootTimeout(20_000) }.get(5, TimeUnit.SECONDS)) }
        finally { executor.shutdownNow(); assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS)) }
        RootProxyManager.endAutomaticRecoveryScope()
        assertEquals(20_000L, RootProxyManager.automaticRootTimeout(20_000))
    }

    @Test fun nestedScopeCannotReplenishAnExistingRecoveryWindow() {
        RootProxyManager.beginAutomaticRecoveryScope(SystemClock.elapsedRealtime() + 166_020)
        try { RootProxyManager.beginAutomaticRecoveryScope(SystemClock.elapsedRealtime() + 300_000); fail("Nested deadline reset") }
        catch (_: IllegalStateException) { }
        assertEquals(1_020L, RootProxyManager.automaticRootTimeout(20_000))
    }
    @Test fun completedNativeStartRetainsOnlyTheRollbackReserve() {
        RootProxyManager.beginAutomaticRecoveryScope(SystemClock.elapsedRealtime() + 170_000)
        assertEquals(5_000L, RootProxyManager.automaticRootTimeout(20_000))
        ShadowSystemClock.advanceBy(Duration.ofMillis(145_000))
        RootProxyManager.automaticStartCompleted()
        assertEquals(5_000L, RootProxyManager.automaticRootTimeout(20_000))
        ShadowSystemClock.advanceBy(Duration.ofMillis(5_000))
        try { RootProxyManager.automaticRootTimeout(1); fail("Rollback reserve must remain intact after native success") }
        catch (_: IOException) { }
    }

}
