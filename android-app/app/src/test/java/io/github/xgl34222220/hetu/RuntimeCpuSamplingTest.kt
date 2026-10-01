package io.github.xgl34222220.hetu

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

/** Exercises the production cache. Only its Root boundary is replaced; WAN IO is disabled. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class,
    shadows = [ConceptRootBridgeShadow::class, ConceptMihomoClientShadow::class])
class RuntimeCpuSamplingTest {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val prefs get() = app.getSharedPreferences("hetu", 0)

    @Before fun prepare() {
        ConceptTestIo.reset()
        prefs.edit().clear().putBoolean("proxyRootWanted", true)
            .putBoolean("proxyRootRuntimeRunning", false).commit()
        // publicNetwork requires proxyRootRuntimeRunning before it can schedule any HTTP.
        assertFalse(prefs.getBoolean("proxyRootRuntimeRunning", true))
        val marker = RootBridge::class.java.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
        assertTrue(Shadow.extract<Any>(marker) is ConceptRootBridgeShadow)
        ShadowSystemClock.advanceBy(Duration.ofMillis(1))
    }

    private fun source(pid: Int = 42, ticks: Long = 100L, total: Long = 1_000L) {
        ConceptTestIo.processSampleJson = JSONObject().put("running", true).put("pid", pid)
            .put("elapsed", 10L).put("processTicks", ticks).put("systemTicks", total)
            .put("rssBytes", 1024L).toString()
    }

    @Test fun cacheRetainsItsTimestampAndFailedRefreshCannotClaimValidity() = runBlocking {
        val inspector = ProxyRuntimeInspector(app)
        source()
        val first = inspector.sample()
        assertTrue(first.processSampleValid)
        source(ticks = 120, total = 2_000)
        ShadowSystemClock.advanceBy(Duration.ofSeconds(4))
        val cached = inspector.sample()
        assertTrue(cached.processSampleValid)
        assertEquals(first.processSampleAtElapsed, cached.processSampleAtElapsed)
        assertEquals(100L, cached.processTicks)
        ConceptTestIo.processSampleJson = null
        ShadowSystemClock.advanceBy(Duration.ofSeconds(5))
        val failed = inspector.sample()
        assertFalse(failed.processSampleValid)
        assertEquals(first.processSampleAtElapsed, failed.processSampleAtElapsed)
        source(ticks = 130, total = 3_000)
        val recovered = inspector.sample()
        assertTrue(recovered.processSampleValid)
        assertTrue(recovered.processSampleAtElapsed > first.processSampleAtElapsed)
        assertEquals(130L, recovered.processTicks)
    }

    @Test fun restartingInvalidatesTheOldCacheAndUnreadableCountersStayUnknown() = runBlocking {
        val inspector = ProxyRuntimeInspector(app)
        source()
        assertTrue(inspector.sample().processSampleValid)
        prefs.edit().putLong("proxyRootLastStartupAt", 1L).commit()
        ConceptTestIo.processSampleJson = null
        assertFalse(inspector.sample().processSampleValid)
        source(pid = 99, ticks = -1)
        assertFalse(inspector.sample().processSampleValid)
        prefs.edit().putBoolean("proxyRootWanted", false).commit()
        assertFalse(inspector.sample().processSampleValid)
    }
}
