package io.github.xgl34222220.hetu

import android.app.Application
import android.os.Looper
import androidx.compose.runtime.MutableState
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
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
import java.io.IOException
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, shadows = [
    ControllerRunState90Shadows.RootStatus::class,
    PanelActionRuntimeShadows.RuntimeSample::class,
    PanelActionRuntimeShadows.HistoryRecord::class,
    PanelActionRuntimeShadows.NoRoot::class,
])
@LooperMode(LooperMode.Mode.PAUSED)
class SelectionMutation90Test {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val prefs get() = app.getSharedPreferences("hetu", 0)
    private val servers = mutableListOf<MockWebServer>()
    private val gates = mutableListOf<CountDownLatch>()
    private var vm: HetuViewModel? = null
    private var observer: CoroutineScope? = null

    @Before fun reset() {
        prefs.edit().clear().putBoolean("proxyRootWanted", true).putBoolean("proxyRootRuntimeRunning", true).commit()
        ControllerRunState90Shadows.RootStatus.reset()
        ControllerRunState90Shadows.RootStatus.reply = "{\"running\":true,\"dataPlaneHealthy\":true,\"pid\":77}"
        PanelActionRuntimeShadows.RuntimeSample.reset()
        PanelActionRuntimeShadows.RuntimeSample.value = ProxyRuntimeSnapshot(running = true, pid = 77)
        PanelActionRuntimeShadows.HistoryRecord.reset()
        PanelActionRuntimeShadows.NoRoot.reset()
    }
    @After fun finish() {
        observer?.cancel(); vm?.viewModelScope?.cancel()
        gates.forEach { it.countDown() }
        PanelActionRuntimeShadows.RuntimeSample.release(); PanelActionRuntimeShadows.HistoryRecord.release()
        vm?.let { model -> eventually("Operations finish") { model.viewModelScope.coroutineContext[Job]!!.children.none { it.isActive } } }
        servers.forEach { it.shutdown() }
        assertEquals(0, PanelActionRuntimeShadows.NoRoot.calls)
    }
    private fun server(handler: (RecordedRequest) -> MockResponse) = MockWebServer().apply {
        dispatcher = object : Dispatcher() { override fun dispatch(request: RecordedRequest) = handler(request) }
        start(); servers += this
    }
    private fun gate() = CountDownLatch(1).also { gates += it }
    private fun CountDownLatch.hold() { check(await(8, TimeUnit.SECONDS)) { "Selection fixture was not released" } }
    private fun api(server: MockWebServer, secret: String = "fixture") {
        prefs.edit().putBoolean("proxyCustomApiEnabled", true).putString("proxyCustomApiHost", "127.0.0.1")
            .putInt("proxyCustomApiPort", server.port).putString("proxyCustomApiSecret", secret).commit()
    }
    private fun body(value: String) = MockResponse().setBody(value)
    private fun proxies(now: String, target: String = "X") = "{\"proxies\":{\"R\":{\"type\":\"Selector\",\"now\":\"$now\",\"all\":[\"A\",\"$target\"]},\"A\":{\"type\":\"Shadowsocks\"},\"$target\":{\"type\":\"Shadowsocks\"}}}"
    private fun connections() = "{\"connections\":[{\"id\":\"same-id\",\"chains\":[\"R\"]}]}"
    private fun rules() = "{\"providers\":{\"R\":{\"vehicleType\":\"HTTP\",\"ruleCount\":1}}}"
    private fun websites(proxy: MockWebServer) {
        prefs.edit().putInt("proxyControllerPort", proxy.port - MihomoStartupConfig.EGRESS_PROBE_PORT_OFFSET).commit()
        ProxyLatencyTargets.save(prefs, ProxyLatencyTargets.defaults.map { it.copy(url = "http://latency.invalid/held") })
    }
    private fun eventually(reason: String, predicate: () -> Boolean) {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(6)
        while (System.nanoTime() < end) { shadowOf(Looper.getMainLooper()).idle(); if (predicate()) return; Thread.sleep(10) }
        assertTrue(reason, predicate())
    }
    @Suppress("UNCHECKED_CAST")
    private fun state(model: HetuViewModel, target: String) {
        val field = HetuViewModel::class.java.getDeclaredField("state\$delegate").apply { isAccessible = true }
        (field.get(model) as MutableState<ProxyComposeState>).value = ProxyComposeState(running = true, panelReady = true, corePid = 77,
            groups = listOf(ProxyGroupUi("R", "Selector", "A", listOf(ProxyNodeUi("A"), ProxyNodeUi(target)))))
    }

    @Test fun replacedApiReceivesNoOldSelectionCleanupAndQueuedTicketCannotRebind() = runBlocking {
        val entered = CountDownLatch(1); val release = gate()
        val old = server { req -> when {
            req.method == "PUT" -> { entered.countDown(); release.hold(); MockResponse().setResponseCode(204) }
            req.requestUrl!!.encodedPath == "/proxies" -> body(proxies("A"))
            req.requestUrl!!.encodedPath == "/connections" -> body(connections())
            else -> MockResponse().setResponseCode(204)
        } }
        val fresh = server { MockResponse().setResponseCode(204) }
        api(old)
        val repository = ProxyDashboardRepository(app)
        val ticket = repository.captureSelection()
        supervisorScope {
            val selecting = async(Dispatchers.Default) { repository.select("R", "X", true, ticket) }
            try { assertTrue(entered.await(3, TimeUnit.SECONDS)); api(fresh) } finally { release.countDown() }
            val failed = try { selecting.await(); null } catch (error: IOException) { error }
            assertNotNull(failed)
            assertEquals(3, old.requestCount)
            assertEquals(0, fresh.requestCount)
            assertFalse(prefs.contains("proxyLastSelectionAt"))
            val queued = try { repository.select("R", "X", true, ticket); null } catch (error: IOException) { error }
            assertNotNull("A ticket captured before dispatch must never bind to B", queued)
            assertEquals(3, old.requestCount); assertEquals(0, fresh.requestCount)
        }
    }

    @Test fun oldSelectionCannotBlockClearReadBackOrToastOverTheReplacementOwner() {
        val aEntered = CountDownLatch(1); val bEntered = CountDownLatch(1)
        val aRelease = gate(); val bRelease = gate()
        val bRequests = Collections.synchronizedList(mutableListOf<RecordedRequest>())
        val selected = AtomicReference("A")
        val old = server { req -> when {
            req.method == "PUT" -> { aEntered.countDown(); aRelease.hold(); MockResponse().setResponseCode(204) }
            req.requestUrl!!.encodedPath == "/proxies" -> body(proxies("A"))
            req.requestUrl!!.encodedPath == "/connections" -> body(connections())
            else -> MockResponse().setResponseCode(204)
        } }
        val fresh = server { req ->
            bRequests += req
            when {
                req.method == "PUT" -> { bEntered.countDown(); bRelease.hold(); selected.set("Y"); MockResponse().setResponseCode(204) }
                req.method == "DELETE" -> MockResponse().setResponseCode(204)
                req.requestUrl!!.encodedPath == "/proxies" -> body(proxies(selected.get(), "Y"))
                req.requestUrl!!.encodedPath == "/configs" -> body("{\"mode\":\"rule\"}")
                req.requestUrl!!.encodedPath == "/providers/proxies" -> body("{\"providers\":{}}")
                req.requestUrl!!.encodedPath == "/connections" -> body(if (selected.get() == "A") connections() else "{\"connections\":[]}")
                req.requestUrl!!.encodedPath == "/version" -> body("{\"version\":\"fixture\"}")
                else -> MockResponse().setResponseCode(404)
            }
        }
        api(old); prefs.edit().putBoolean("proxySelectorDisconnectOnSelect", true).commit()
        val model = HetuViewModel(app).also { vm = it }; state(model, "X")
        val messages = mutableListOf<String>()
        observer = CoroutineScope(Dispatchers.Main.immediate + Job()).also { scope -> scope.launch { model.messages.collect { messages += it } } }
        model.select("R", "X")
        eventually("A selection enters") { aEntered.count == 0L }
        api(fresh); model.bumpSettings(); state(model, "Y")
        assertTrue(model.pendingSelection.isEmpty())
        model.select("R", "Y")
        eventually("B selection enters without waiting for A") { bEntered.count == 0L }
        aRelease.countDown()
        eventually("Only B owner remains") { model.viewModelScope.coroutineContext[Job]!!.children.count { it.isActive } == 1 }
        assertEquals("Y", model.pendingSelection["R"])
        assertTrue(messages.isEmpty())
        assertEquals(3, fresh.requestCount)
        assertTrue(synchronized(bRequests) { bRequests.none { it.method == "DELETE" || it.requestUrl!!.encodedPath == "/configs" } })
        bRelease.countDown()
        eventually("B selection is confirmed") { model.viewModelScope.coroutineContext[Job]!!.children.none { it.isActive } }
        assertTrue(model.pendingSelection.isEmpty())
        assertEquals("Y", model.state.groups.single().now)
        assertTrue(messages.isEmpty())
        assertEquals(1, synchronized(bRequests) { bRequests.count { it.method == "DELETE" } })
    }

    @Test fun trafficModeSingleAndBulkRuleMutationsInvalidateWebsiteWavesOnlyAfterSuccessfulHttp() = runBlocking {
        for (kind in listOf("mode", "rule", "bulk")) for (success in listOf(false, true)) {
            val entered = CountDownLatch(3); val release = gate(); val writes = AtomicInteger()
            val proxy = server { entered.countDown(); release.hold(); MockResponse().setResponseCode(204) }
            val controller = server { req -> if (req.method == "PATCH" || req.method == "PUT") {
                writes.incrementAndGet(); MockResponse().setResponseCode(if (success) 204 else 401)
            } else body(rules()) }
            api(controller); websites(proxy)
            val repository = ProxyDashboardRepository(app)
            supervisorScope {
                val wave = async(Dispatchers.Default) { repository.siteLatencies() }
                try {
                    assertTrue(entered.await(3, TimeUnit.SECONDS))
                    val rejected = try {
                        when (kind) { "mode" -> repository.setTrafficMode("direct"); "rule" -> repository.refreshRuleSet("R"); else -> repository.refreshRuleSets() }
                        null
                    } catch (error: MihomoControllerClient.ControllerHttpException) { error }
                    if (success || kind == "bulk") assertNull(rejected) else assertEquals(401, rejected!!.statusCode)
                    assertEquals(1, writes.get())
                } finally { release.countDown() }
                val failure = try { assertTrue(wave.await().values.all { it > 0L }); null } catch (error: IOException) { error }
                if (success) assertNotNull("Confirmed $kind mutation rejects the old route wave", failure)
                else assertNull("Rejected $kind mutation preserves valid measurements", failure)
                assertEquals(3, proxy.requestCount)
            }
        }
    }

    @Test fun concurrentGroupSelectionsEachAdvanceObservationsWithoutRejectingTheOtherOrigin() = runBlocking {
        val xEntered = CountDownLatch(1); val yEntered = CountDownLatch(1)
        val xRelease = gate(); val yRelease = gate(); val websitesEntered = CountDownLatch(3); val websitesRelease = gate()
        val proxy = server { websitesEntered.countDown(); websitesRelease.hold(); MockResponse().setResponseCode(204) }
        val controller = server { req -> when (req.requestUrl!!.encodedPath) {
            "/proxies/X" -> { xEntered.countDown(); xRelease.hold(); MockResponse().setResponseCode(204) }
            "/proxies/Y" -> { yEntered.countDown(); yRelease.hold(); MockResponse().setResponseCode(204) }
            else -> MockResponse().setResponseCode(404)
        } }
        api(controller); websites(proxy)
        val repository = ProxyDashboardRepository(app)
        supervisorScope {
            val x = async(Dispatchers.Default) { repository.select("X", "B") }
            val y = async(Dispatchers.Default) { repository.select("Y", "B") }
            assertTrue(xEntered.await(3, TimeUnit.SECONDS)); assertTrue(yEntered.await(3, TimeUnit.SECONDS))
            xRelease.countDown(); x.await()
            val wave = async(Dispatchers.Default) { repository.siteLatencies() }
            try {
                assertTrue(websitesEntered.await(3, TimeUnit.SECONDS))
                yRelease.countDown(); y.await()
            } finally { websitesRelease.countDown(); xRelease.countDown(); yRelease.countDown() }
            val failure = try { wave.await(); null } catch (error: IOException) { error }
            assertNotNull("The second confirmed PUT supersedes waves started after the first", failure)
            assertEquals(2, controller.requestCount)
        }
    }

    @Test fun frozenControllerKeepsCapturedEndpointAndCredentialWhileOrdinaryRequestsRemainDynamic() {
        val old = server { MockResponse().setResponseCode(204) }
        val fresh = server { MockResponse().setResponseCode(204) }
        api(old, "old-fixture")
        val frozen = MihomoControllerClient(app, prefs.all)
        api(fresh, "fresh-fixture")
        frozen.closeConnection("same-id")
        assertEquals(1, old.requestCount); assertEquals(0, fresh.requestCount)
        assertEquals("Bearer old-fixture", old.takeRequest(2, TimeUnit.SECONDS)!!.getHeader("Authorization"))
        MihomoControllerClient(app).closeConnection("fresh-id")
        assertEquals(1, fresh.requestCount)
        assertEquals("Bearer fresh-fixture", fresh.takeRequest(2, TimeUnit.SECONDS)!!.getHeader("Authorization"))
    }
}
