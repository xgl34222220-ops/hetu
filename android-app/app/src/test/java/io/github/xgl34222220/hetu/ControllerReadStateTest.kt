package io.github.xgl34222220.hetu

import android.app.Application
import android.os.Looper
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadow.api.Shadow
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Actual controller HTTP reads with isolated healthy Root and /proc boundaries. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, shadows = [
    PanelActionRuntimeShadows.RootStatus::class,
    PanelActionRuntimeShadows.RuntimeSample::class,
    PanelActionRuntimeShadows.HistoryRecord::class,
    PanelActionRuntimeShadows.NoRoot::class,
])
@LooperMode(LooperMode.Mode.PAUSED)
class ControllerReadStateTest {
    private lateinit var app: Application
    private lateinit var server: MockWebServer
    private lateinit var controller: ProxyComposeController
    private var vm: HetuViewModel? = null
    private val release = CountDownLatch(1)
    private val controllerReadEntered = CountDownLatch(1)
    @Volatile private var rejectedPath: String? = null
    @Volatile private var responseCode = 401
    @Volatile private var generation = 1
    @Volatile private var malformed = false
    @Volatile private var holdConfigs = false
    private val siblingReads = listOf("/configs", "/proxies", "/providers/proxies").associateWith { CountDownLatch(1) }

    @Before fun prepare() {
        // Share the original suspend-capable history shadow and isolate this method's audit.
        PanelActionRuntimeShadows.HistoryRecord.reset()
        PanelActionRuntimeShadows.RuntimeSample.reset()
        PanelActionRuntimeShadows.NoRoot.reset()
        app = ApplicationProvider.getApplicationContext()
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    siblingReads[request.path]?.countDown()
                    if (rejectedPath == "/connections" && request.path == "/connections") {
                        // A failing sibling read now cancels and closes the snapshot at once; let the
                        // parallel reads arrive first so the rejection cannot race them off the wire.
                        siblingReads.values.forEach { it.await(6, TimeUnit.SECONDS) }
                    }
                    if (holdConfigs && request.path == "/configs") {
                        controllerReadEntered.countDown()
                        check(release.await(6, TimeUnit.SECONDS))
                    }
                    if (request.path == rejectedPath || rejectedPath == "*") return MockResponse()
                        .setResponseCode(responseCode).setBody("fixture-custom-secret fixture-local-secret private response")
                    if (malformed && request.path == "/connections") return MockResponse().setBody("broken json")
                    return MockResponse().setBody(when (request.path) {
                        "/configs" -> """{"mode":"rule"}"""
                        "/proxies" -> """{"proxies":{"SELECT":{"type":"Selector","now":"node-$generation","all":["node-$generation"]},"node-$generation":{"type":"Shadowsocks"}}}"""
                        "/providers/proxies" -> """{"providers":{}}"""
                        "/connections" -> """{"downloadTotal":${generation * 2000},"uploadTotal":${generation * 1000},"memory":${generation * 4096},"connections":[{"id":"conn-$generation","metadata":{"host":"example.test","network":"tcp"},"chains":["SELECT"],"upload":5,"download":10}]}"""
                        "/version" -> """{"version":"fixture"}"""
                        else -> "{}"
                    })
                }
            }
            start()
        }
        app.getSharedPreferences("hetu", 0).edit().clear()
            .putBoolean("proxyCustomApiEnabled", true).putString("proxyCustomApiHost", "127.0.0.1")
            .putInt("proxyCustomApiPort", server.port).putString("proxyCustomApiSecret", "fixture-custom-secret")
            .putString("proxyControllerSecret", "fixture-local-secret").putBoolean("proxyApiHistoryEnabled", true).commit()
        PanelActionRuntimeShadows.RuntimeSample.value = ProxyRuntimeSnapshot(running = true, pid = 77,
            rssBytes = 8192L, elapsedSeconds = 123L)
        assertTrue("History singleton must use the original suspend-capable shadow",
            Shadow.extract<Any>(ProxyApiHistoryStore) is PanelActionRuntimeShadows.HistoryRecord)
        controller = ProxyComposeController(app)
    }

    @After fun finish() {
        release.countDown()
        vm?.viewModelScope?.cancel()
        server.shutdown()
        assertEquals("Controller reads must not mutate Root", 0, PanelActionRuntimeShadows.NoRoot.calls)
    }

    @Test fun unauthorizedFirstReadIsExplicitWithoutChangingHealthyRoot() = runBlocking {
        rejectedPath = "*"
        val state = controller.state()
        assertTrue(state.running)
        assertTrue(state.dataPlaneHealthy)
        assertEquals("", state.message)
        assertFalse(state.panelReady)
        assertTrue(state.controllerReadFailed)
        assertTrue(state.controllerError.contains("自定义控制接口"))
        assertTrue(state.controllerError.contains("401"))
        assertFalse(state.controllerError.contains("fixture-custom-secret"))
        assertFalse(state.controllerError.contains("fixture-local-secret"))
        assertTrue(state.groups.isEmpty())
        assertTrue(PanelActionRuntimeShadows.HistoryRecord.samples.isEmpty())
    }

    @Test fun successfulGroupsThenUnauthorizedConnectionsCannotPublishPartialSnapshot() = runBlocking {
        rejectedPath = "/connections"
        val state = controller.state()
        assertTrue(server.requestCount >= 4)
        assertFalse(state.panelReady)
        assertTrue(state.controllerReadFailed)
        assertTrue(state.controllerError.contains("401"))
        assertTrue(state.groups.isEmpty())
        assertTrue(state.connections.isEmpty())
        assertEquals("", state.trafficMode)
        assertTrue(state.running && state.dataPlaneHealthy)
    }

    @Test fun otherHttpFailuresAlsoReportMissingControllerData() = runBlocking {
        rejectedPath = "/configs"
        for (code in listOf(400, 503)) {
            responseCode = code
            val state = controller.state()
            assertFalse(state.panelReady)
            assertTrue(state.controllerReadFailed)
            assertTrue(state.controllerError.contains(code.toString()))
            assertTrue(state.running && state.dataPlaneHealthy)
        }
    }

    @Test fun malformedSuccessfulHttpResponseIsAReadFailure() = runBlocking {
        malformed = true
        val state = controller.state()
        assertTrue(state.controllerReadFailed)
        assertFalse(state.panelReady)
        assertTrue(state.controllerError.contains("读取失败"))
        assertTrue(state.running && state.dataPlaneHealthy)
    }

    @Test fun failedPollKeepsConfirmedDataRatesTrendsAndHistoryUntilCompleteRecovery() = runBlocking {
        val model = HetuViewModel(app).also { vm = it }
        assertNotNull(model.refreshNow())
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        generation = 2
        assertNotNull(model.refreshNow())
        val confirmed = model.state
        assertTrue(confirmed.panelReady)
        assertEquals("node-2", confirmed.groups.single().now)
        val oldDownRate = model.downRate
        val oldUpRate = model.upRate
        val downTrend = model.rateHistory.toList()
        val upTrend = model.upHistory.toList()
        val history = PanelActionRuntimeShadows.HistoryRecord.samples.toList()
        assertTrue(downTrend.isNotEmpty())
        assertEquals(2, history.size)

        rejectedPath = "/connections"
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        val failedRead = model.refreshNow()!!
        assertTrue(failedRead.controllerReadFailed)
        assertFalse(failedRead.panelReady)
        assertTrue(model.state.controllerReadFailed)
        assertEquals(confirmed.groups, model.state.groups)
        assertEquals(confirmed.connections, model.state.connections)
        assertEquals(confirmed.downloadTotal, model.state.downloadTotal)
        assertEquals(confirmed.uploadTotal, model.state.uploadTotal)
        assertEquals(confirmed.memoryBytes, model.state.memoryBytes)
        assertEquals(confirmed.trafficMode, model.state.trafficMode)
        assertEquals(oldDownRate, model.downRate)
        assertEquals(oldUpRate, model.upRate)
        assertEquals(downTrend, model.rateHistory.toList())
        assertEquals(upTrend, model.upHistory.toList())
        assertEquals(history, PanelActionRuntimeShadows.HistoryRecord.samples)
        assertTrue(model.state.running && model.state.dataPlaneHealthy)

        rejectedPath = null
        generation = 3
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        val recovered = model.refreshNow()!!
        assertTrue(recovered.panelReady)
        assertFalse(recovered.controllerReadFailed)
        assertEquals("", recovered.controllerError)
        assertEquals("node-3", model.state.groups.single().now)
        assertEquals(6000L, model.state.downloadTotal)
        assertEquals(downTrend.size + 1, model.rateHistory.size)
        assertEquals(history.size + 1, PanelActionRuntimeShadows.HistoryRecord.samples.size)
    }

    @Test fun cancellationDuringControllerReadPropagatesWithoutPublishingState() = runBlocking {
        holdConfigs = true
        val published = AtomicBoolean(false)
        val completion = AtomicReference<Throwable?>()
        val job = launch(Dispatchers.Default) {
            controller.state()
            published.set(true)
        }
        job.invokeOnCompletion { completion.set(it) }
        assertNotNull(server.takeRequest(3, TimeUnit.SECONDS))
        assertTrue("Cancellation must interrupt an entered controller read", controllerReadEntered.await(3, TimeUnit.SECONDS))
        job.cancel()
        release.countDown()
        job.join()
        assertFalse("Cancelled controller read must not publish a state", published.get())
        assertTrue("Controller read must complete as cancelled", job.isCancelled)
        assertTrue("Controller cancellation must propagate", completion.get() is CancellationException)
        assertTrue(PanelActionRuntimeShadows.HistoryRecord.samples.isEmpty())
    }
}
