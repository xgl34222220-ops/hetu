package io.github.xgl34222220.hetu

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Tests the existing tool entry, so the exact earlier production source is a runnable control. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, shadows = [PanelActionRuntimeShadows.NoRoot::class])
class RuleModeMutation92Test {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val prefs get() = app.getSharedPreferences("hetu", 0)
    private val servers = mutableListOf<MockWebServer>()
    private val gates = mutableListOf<CountDownLatch>()

    @Before fun resetPreferences() {
        prefs.edit().clear().putString("proxyBaseMode", "tproxy").commit()
        PanelActionRuntimeShadows.NoRoot.reset()
    }

    @After fun finish() {
        gates.forEach { it.countDown() }
        servers.forEach { it.shutdown() }
        assertEquals("Tool traffic mode changes must not mutate Root", 0, PanelActionRuntimeShadows.NoRoot.calls)
        assertEquals("Traffic rule mode is separate from TPROXY interception", "tproxy", prefs.getString("proxyBaseMode", ""))
    }

    private fun gate() = CountDownLatch(1).also { gates += it }
    private fun CountDownLatch.hold() { check(await(6, TimeUnit.SECONDS)) { "Rule mode fixture was not released" } }
    private fun server(handler: (RecordedRequest) -> MockResponse) = MockWebServer().apply {
        dispatcher = object : Dispatcher() { override fun dispatch(request: RecordedRequest) = handler(request) }
        start(); servers += this
    }

    private fun api(server: MockWebServer, secret: String = "rule-fixture") {
        prefs.edit().putBoolean("proxyCustomApiEnabled", true).putString("proxyCustomApiHost", "127.0.0.1")
            .putInt("proxyCustomApiPort", server.port).putString("proxyCustomApiSecret", secret).commit()
    }

    private class Requests {
        val writes = AtomicInteger()
        val reads = AtomicInteger()
        val unexpected = AtomicInteger()
        fun assertCounts(writes: Int, reads: Int) {
            assertEquals(writes, this.writes.get())
            assertEquals(reads, this.reads.get())
            assertEquals("Unexpected controller method, endpoint, credential or payload", 0, unexpected.get())
        }
    }

    private fun controller(requests: Requests, secret: String = "rule-fixture",
        write: () -> MockResponse = { MockResponse().setResponseCode(204) },
        read: () -> MockResponse = { MockResponse().setBody("{\"mode\":\"rule\"}") }) = server { request ->
        val endpointMatches = request.requestUrl!!.encodedPath == "/configs" && request.getHeader("Authorization") == "Bearer $secret"
        when {
            endpointMatches && request.method == "PATCH" &&
                runCatching { JSONObject(request.body.readUtf8()).getString("mode") == "rule" }.getOrDefault(false) -> {
                requests.writes.incrementAndGet(); write()
            }
            endpointMatches && request.method == "GET" && request.body.size == 0L -> {
                requests.reads.incrementAndGet(); read()
            }
            else -> { requests.unexpected.incrementAndGet(); MockResponse().setResponseCode(400) }
        }
    }

    private fun websites(entered: CountDownLatch, release: CountDownLatch): MockWebServer {
        val proxy = server { request ->
            if (request.requestLine != "GET http://latency.invalid/held HTTP/1.1") MockResponse().setResponseCode(400)
            else { entered.countDown(); release.hold(); MockResponse().setResponseCode(204) }
        }
        prefs.edit().putBoolean("proxyRootWanted", true).putBoolean("proxyRootRuntimeRunning", true)
            .putInt("proxyControllerPort", proxy.port - MihomoStartupConfig.EGRESS_PROBE_PORT_OFFSET).commit()
        ProxyLatencyTargets.save(prefs, ProxyLatencyTargets.defaults.map { it.copy(url = "http://latency.invalid/held") })
        return proxy
    }

    private fun assertSuperseded(result: Result<*>) {
        assertTrue("A successful tool PATCH must supersede the old route wave", result.exceptionOrNull() is IOException)
        assertEquals("代理或控制接口已变化，请重新测速", result.exceptionOrNull()!!.message)
    }

    private fun assertReplacedOrigin(result: Result<*>) {
        assertTrue("The tool must not confirm an operation from a replaced API", result.exceptionOrNull() is IOException)
        assertEquals("代理或控制接口已变化，请刷新后重试", result.exceptionOrNull()!!.message)
    }

    @Test fun toolRuleModeSwitchRejectsOldWebsiteWaveOnlyAfterSuccessfulPatch() = runBlocking {
        for (success in listOf(false, true)) {
            val requests = Requests()
            val controller = controller(requests, write = { MockResponse().setResponseCode(if (success) 204 else 401) })
            api(controller)
            val entered = CountDownLatch(3); val release = gate()
            val proxy = websites(entered, release)
            val observer = ProxyDashboardRepository(app)
            val before = prefs.all
            supervisorScope {
                val pending = async(Dispatchers.Default) { runCatching { observer.siteLatencies() } }
                try {
                    assertTrue(entered.await(3, TimeUnit.SECONDS))
                    val action = runCatching { ToolsRuntimeBridge.switchToRuleMode(app) }
                    if (success) assertNull(action.exceptionOrNull())
                    else assertEquals(401, (action.exceptionOrNull() as MihomoControllerClient.ControllerHttpException).statusCode)
                    requests.assertCounts(1, if (success) 1 else 0)
                    assertEquals(before, prefs.all)
                } finally { release.countDown() }
                val measured = pending.await()
                if (success) assertSuperseded(measured)
                else assertTrue("A denied PATCH must retain the valid website results", measured.getOrThrow().values.all { it > 0L })
                assertEquals(3, proxy.requestCount)
                assertTrue(observer.siteLatencies().values.all { it > 0L })
                assertEquals(6, proxy.requestCount)
            }
        }
    }

    @Test fun successfulPatchInvalidatesOldWaveBeforeHeldReadBackFinishesEvenWhenModeIsUnconfirmed() = runBlocking {
        val readEntered = CountDownLatch(1); val readRelease = gate()
        val requests = Requests()
        val controller = controller(requests, read = {
            readEntered.countDown(); readRelease.hold(); MockResponse().setBody("{\"mode\":\"direct\"}")
        })
        api(controller)
        val websitesEntered = CountDownLatch(3); val websitesRelease = gate()
        val proxy = websites(websitesEntered, websitesRelease)
        val observer = ProxyDashboardRepository(app)
        val before = prefs.all
        supervisorScope {
            val pending = async(Dispatchers.Default) { runCatching { observer.siteLatencies() } }
            try {
                assertTrue(websitesEntered.await(3, TimeUnit.SECONDS))
                val switching = async(Dispatchers.Default) { runCatching { ToolsRuntimeBridge.switchToRuleMode(app) } }
                try {
                    assertTrue(readEntered.await(3, TimeUnit.SECONDS))
                    requests.assertCounts(1, 1)
                    websitesRelease.countDown()
                    assertSuperseded(pending.await())
                    assertFalse("Invalidation must precede completion of the confirmation read", switching.isCompleted)
                } finally { readRelease.countDown() }
                val error = switching.await().exceptionOrNull()
                assertTrue(error is IOException)
                assertEquals("核心尚未确认规则模式，请刷新后重试", error!!.message)
            } finally { websitesRelease.countDown(); readRelease.countDown() }
            assertEquals(before, prefs.all)
            assertEquals(3, proxy.requestCount)
            assertTrue(observer.siteLatencies().values.all { it > 0L })
        }
    }

    @Test fun successfulPatchWithFailedConfirmationStillInvalidatesOldWebsiteWave() = runBlocking {
        for (readCode in listOf(401, 500)) {
            val requests = Requests()
            val controller = controller(requests, read = { MockResponse().setResponseCode(readCode) })
            api(controller)
            val entered = CountDownLatch(3); val release = gate()
            val proxy = websites(entered, release)
            val observer = ProxyDashboardRepository(app)
            val before = prefs.all
            supervisorScope {
                val pending = async(Dispatchers.Default) { runCatching { observer.siteLatencies() } }
                try {
                    assertTrue(entered.await(3, TimeUnit.SECONDS))
                    val action = runCatching { ToolsRuntimeBridge.switchToRuleMode(app) }
                    assertEquals(readCode, (action.exceptionOrNull() as MihomoControllerClient.ControllerHttpException).statusCode)
                    requests.assertCounts(1, 1)
                } finally { release.countDown() }
                assertSuperseded(pending.await())
                assertEquals(before, prefs.all)
                assertEquals(3, proxy.requestCount)
            }
        }
    }

    @Test fun latePatchFromReplacedApiCannotReadBackOrConfirmThroughReplacement() = runBlocking {
        for (patchCode in listOf(204, 401, 500)) {
            val entered = CountDownLatch(1); val release = gate()
            val oldRequests = Requests(); val newRequests = Requests()
            val old = controller(oldRequests, "old-fixture", write = {
                entered.countDown(); release.hold(); MockResponse().setResponseCode(patchCode)
            })
            val replacement = controller(newRequests, "new-fixture")
            api(old, "old-fixture")
            val replacementObserver = ProxyDashboardRepository(app)
            supervisorScope {
                val pending = async(Dispatchers.Default) { runCatching { ToolsRuntimeBridge.switchToRuleMode(app) } }
                val afterSave: Map<String, *>
                val replacementIdentity: ProxyDashboardRepository.ProbeIdentity
                try {
                    assertTrue(entered.await(3, TimeUnit.SECONDS))
                    api(replacement, "new-fixture")
                    afterSave = prefs.all
                    replacementIdentity = replacementObserver.captureSelection().identity
                } finally { release.countDown() }
                assertReplacedOrigin(pending.await())
                oldRequests.assertCounts(1, 0)
                newRequests.assertCounts(0, 0)
                assertEquals(1, old.requestCount)
                assertEquals("Old PATCH must never read back using the replacement API", 0, replacement.requestCount)
                assertEquals("Obsolete PATCH responses cannot advance the replacement's observations", replacementIdentity,
                    replacementObserver.captureSelection().identity)
                assertEquals(afterSave, prefs.all)
                ToolsRuntimeBridge.switchToRuleMode(app)
                newRequests.assertCounts(1, 1)
                assertEquals(2, replacement.requestCount)
            }
        }
    }

    @Test fun lateReadBackCannotConfirmAfterApiReplacementEvenWhenOldApiReturnsRule() = runBlocking {
        val entered = CountDownLatch(1); val release = gate()
        val oldRequests = Requests(); val newRequests = Requests()
        val old = controller(oldRequests, "old-fixture", read = {
            entered.countDown(); release.hold(); MockResponse().setBody("{\"mode\":\"rule\"}")
        })
        val replacement = controller(newRequests, "new-fixture")
        api(old, "old-fixture")
        supervisorScope {
            val pending = async(Dispatchers.Default) { runCatching { ToolsRuntimeBridge.switchToRuleMode(app) } }
            val afterSave: Map<String, *>
            try {
                assertTrue(entered.await(3, TimeUnit.SECONDS))
                api(replacement, "new-fixture")
                afterSave = prefs.all
            } finally { release.countDown() }
            assertReplacedOrigin(pending.await())
            oldRequests.assertCounts(1, 1)
            assertEquals(2, old.requestCount)
            assertEquals(0, replacement.requestCount)
            assertEquals(afterSave, prefs.all)
            ToolsRuntimeBridge.switchToRuleMode(app)
            newRequests.assertCounts(1, 1)
        }
    }
}
