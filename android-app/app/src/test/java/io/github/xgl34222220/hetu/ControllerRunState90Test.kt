package io.github.xgl34222220.hetu

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, shadows = [
    ControllerRunState90Shadows.RootStatus::class,
    PanelActionRuntimeShadows.NoRoot::class,
])
class ControllerRunState90Test {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val prefs get() = app.getSharedPreferences("hetu", 0)
    private lateinit var server: MockWebServer

    @Before fun prepare() {
        ControllerRunState90Shadows.RootStatus.reset()
        ShadowSystemClock.advanceBy(Duration.ofSeconds(1))
        PanelActionRuntimeShadows.NoRoot.reset()
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                    "/configs" -> MockResponse().setBody("{\"mode\":\"rule\"}")
                    "/proxies" -> MockResponse().setBody("{\"proxies\":{}}")
                    "/providers/proxies" -> MockResponse().setBody("{\"providers\":{}}")
                    "/connections" -> MockResponse().setBody("{\"connections\":[]}")
                    else -> MockResponse().setResponseCode(404)
                }
            }
            start()
        }
        prefs.edit().clear().putBoolean("proxyRootWanted", true)
            .putBoolean("proxyRootRuntimeRunning", true)
            .putBoolean("proxyCustomApiEnabled", true)
            .putString("proxyCustomApiHost", "127.0.0.1")
            .putInt("proxyCustomApiPort", server.port).commit()
    }

    @After fun finish() {
        server.shutdown()
        assertEquals("No fixture may mutate Root", 0, PanelActionRuntimeShadows.NoRoot.calls)
    }

    @Test fun confirmedStoppedSupersedesTheCachedRunningHintAndSkipsControllerReads() = runBlocking {
        ControllerRunState90Shadows.RootStatus.reply = "{\"running\":false,\"message\":\"已停止\"}"
        val observed = ProxyComposeController(app).state()
        assertFalse(observed.running)
        assertFalse(prefs.getBoolean("proxyRootRuntimeRunning", true))
        assertFalse(observed.panelReady)
        assertFalse(observed.controllerReadFailed)
        assertFalse(observed.dataPlaneHealthy)
        assertFalse(observed.healthObserved)
        assertEquals("已停止", observed.message)
        assertEquals(0, server.requestCount)
        assertEquals(1, ControllerRunState90Shadows.RootStatus.reads)
    }

    @Test fun confirmedRunningSupersedesAStoppedHintAndReadsTheController() = runBlocking {
        prefs.edit().putBoolean("proxyRootRuntimeRunning", false).commit()
        ControllerRunState90Shadows.RootStatus.reply = "{\"running\":true,\"dataPlaneHealthy\":true,\"pid\":77}"
        val controller = ProxyComposeController(app)
        val observed = controller.state()
        assertTrue(observed.running)
        assertTrue(observed.panelReady)
        assertTrue(observed.dataPlaneHealthy)
        assertTrue(observed.healthObserved)
        assertEquals(77, observed.corePid)
        assertTrue(prefs.getBoolean("proxyRootRuntimeRunning", false))
        assertEquals(4, server.requestCount)
        ShadowSystemClock.advanceBy(Duration.ofSeconds(2))
        ControllerRunState90Shadows.RootStatus.reply = "{\"running\":true,\"dataPlaneHealthy\":true,\"pid\":88}"
        val cached = controller.state()
        assertEquals("Throttled reads preserve the last confirmed process", 77, cached.corePid)
        assertTrue(cached.healthObserved)
        assertEquals(1, ControllerRunState90Shadows.RootStatus.reads)
        prefs.edit().putLong("proxyRootHealthProbeElapsed", 0L).commit()
        assertEquals(88, controller.state().corePid)
        assertEquals(2, ControllerRunState90Shadows.RootStatus.reads)
    }

    @Test fun missingRunningFieldRetainsOnlyTheHintAndDoesNotClaimDataPlaneHealth() = runBlocking {
        val observed = ProxyComposeController(app).state()
        assertTrue(observed.running)
        assertTrue(observed.panelReady)
        assertFalse(observed.dataPlaneHealthy)
        assertFalse(observed.healthObserved)
        assertTrue(observed.message.isNotBlank())
        assertEquals(4, server.requestCount)
    }

    @Test fun failedRootObservationRetainsTheHintButRejectsOldHealthyCache() = runBlocking {
        prefs.edit().putBoolean("proxyRootDataPlaneHealthy", true).putBoolean("proxyRootHealthObserved", true)
            .putInt("proxyRootRuntimeSchema", 42).commit()
        ControllerRunState90Shadows.RootStatus.fail = true
        val observed = ProxyComposeController(app).state()
        assertTrue(observed.running)
        assertTrue(observed.panelReady)
        assertFalse(observed.dataPlaneHealthy)
        assertFalse(observed.healthObserved)
        assertEquals(42, observed.runtimeSchema)
        assertEquals("运行状态暂时无法确认", observed.message)
        assertEquals(4, server.requestCount)
    }

    @Test fun missingRunningWithoutARunningHintRemainsStopped() = runBlocking {
        prefs.edit().putBoolean("proxyRootRuntimeRunning", false).commit()
        val observed = ProxyComposeController(app).state()
        assertFalse(observed.running)
        assertFalse(observed.panelReady)
        assertFalse(observed.dataPlaneHealthy)
        assertFalse(observed.healthObserved)
        assertFalse(prefs.getBoolean("proxyRootRuntimeRunning", true))
        assertEquals(0, server.requestCount)
    }
}
