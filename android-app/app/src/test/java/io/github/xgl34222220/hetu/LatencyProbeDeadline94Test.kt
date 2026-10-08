package io.github.xgl34222220.hetu

import android.app.Application
import android.os.Looper
import androidx.compose.runtime.MutableState
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.async
import kotlinx.coroutines.asContextElement
import okhttp3.mockwebserver.SocketPolicy
import java.util.concurrent.Semaphore
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

/** Controlled loopback HTTP only: total deadline, cancellation and ownership regressions. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, shadows = [
    PanelRequestOwnershipShadows.RootStatus::class,
    PanelRequestOwnershipShadows.Sites::class,
    PanelActionRuntimeShadows.RuntimeSample::class,
    PanelActionRuntimeShadows.HistoryRecord::class,
    PanelActionRuntimeShadows.NoRoot::class,
])
@LooperMode(LooperMode.Mode.PAUSED)
class LatencyProbeDeadline94Test {
    private lateinit var vm: HetuViewModel
    private lateinit var server: MockWebServer
    private lateinit var collector: CoroutineScope
    private val gates = mutableListOf<CountDownLatch>()
    private val messages = mutableListOf<String>()
    private val requests = Collections.synchronizedList(mutableListOf<RecordedRequest>())
    private val group = ProxyGroupUi("Route", "Selector", "node", listOf(ProxyNodeUi("node"), ProxyNodeUi("other")))
    @Volatile private var response: (RecordedRequest) -> MockResponse = { MockResponse().setResponseCode(404) }

    @Before fun prepare() {
        PanelRequestOwnershipShadows.RootStatus.reset()
        PanelRequestOwnershipShadows.Sites.reset()
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse { requests += request; return response(request) }
            }
            start()
        }
        val app = ApplicationProvider.getApplicationContext<Application>()
        app.getSharedPreferences("hetu", 0).edit().clear()
            .putBoolean("proxyCustomApiEnabled", true).putString("proxyCustomApiHost", "127.0.0.1")
            .putInt("proxyCustomApiPort", server.port)
            .putBoolean("proxyRootWanted", true).putBoolean("proxyRootRuntimeRunning", true)
            .putBoolean("proxyApiHistoryEnabled", false).commit()
        PanelActionRuntimeShadows.RuntimeSample.value = ProxyRuntimeSnapshot(running = true, pid = 77)
        vm = HetuViewModel(app)
        @Suppress("UNCHECKED_CAST")
        val state = HetuViewModel::class.java.getDeclaredField("state\$delegate").apply { isAccessible = true }
            .get(vm) as MutableState<ProxyComposeState>
        state.value = ProxyComposeState(running = true, panelReady = true, corePid = 77, groups = listOf(group))
        vm.delays["node"] = 44L
        response = ::normalResponse
        collector = CoroutineScope(Dispatchers.Main.immediate)
        collector.launch { vm.messages.collect { messages += it } }
        shadowOf(Looper.getMainLooper()).idle()
    }

    @After fun finish() {
        vm.viewModelScope.cancel()
        gates.forEach { it.countDown() }
        PanelRequestOwnershipShadows.Sites.releaseAll()
        PanelActionRuntimeShadows.RuntimeSample.release()
        PanelActionRuntimeShadows.HistoryRecord.release()
        awaitActions()
        collector.cancel()
        server.shutdown()
        assertEquals("Fixtures must not execute Root actions", 0, PanelActionRuntimeShadows.NoRoot.calls)
    }

    private fun gate() = CountDownLatch(1).also { gates += it }
    private fun CountDownLatch.hold() { check(await(8, TimeUnit.SECONDS)) { "HTTP fixture gate not released" } }
    private fun body(value: String) = MockResponse().setBody(value)
    private fun paths() = synchronized(requests) { requests.map { it.requestUrl!!.encodedPath } }
    private fun eventually(reason: String, predicate: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(6)
        while (System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (predicate()) return
            Thread.sleep(10)
        }
        assertTrue("$reason; requests=${paths()}, busy=${vm.testingNodes}", predicate())
    }
    private fun awaitActions() = eventually("ViewModel requests completed") {
        // Cancellation makes isActive false immediately; held HTTP and finally
        // cleanup can still be running. Wait for completion before checking owners.
        vm.viewModelScope.coroutineContext[Job]!!.children.all { it.isCompleted }
    }
    private fun normalResponse(req: RecordedRequest): MockResponse = when (req.requestUrl!!.encodedPath) {
        "/proxies" -> body("""{"proxies":{"Route":{"type":"Selector","now":"node","all":["node","other"]},"node":{"type":"Shadowsocks","history":[{"delay":999}]},"other":{"type":"Shadowsocks"}}}""")
        "/providers/proxies" -> body("""{"providers":{}}""")
        "/connections" -> body("""{"connections":[]}""")
        "/configs" -> body("""{"mode":"rule"}""")
        "/version" -> body("""{"version":"fixture"}""")
        "/proxies/node/delay", "/proxies/other/delay" -> body("""{"delay":22}""")
        else -> MockResponse().setResponseCode(404)
    }

    private fun boundedFailure(timeoutMs: Long = 300, action: () -> Unit): IOException {
        val started = System.nanoTime()
        val budget = LatencyProbeBudget(timeoutMs)
        LatencyProbeBudget.CURRENT.set(budget)
        try {
            try { action(); fail("Expected a real transport deadline") }
            catch (error: IOException) {
                assertTrue("Deadline should be explicit", error.message.orEmpty().contains("时限"))
                assertTrue("Deadline must close IO, even while bytes arrive", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 2000)
                return error
            }
            throw AssertionError("No failure")
        } finally { budget.close(); LatencyProbeBudget.CURRENT.remove() }
    }

    @Test fun slowBodyCannotResetTheWholeRequestDeadline() {
        response = { body("""{"delay":22}""").throttleBody(1, 70, TimeUnit.MILLISECONDS) }
        boundedFailure { MihomoControllerClient(vm.getApplication()).delay("node") }
        assertEquals(1, server.requestCount)
    }

    @Test fun chunkedDripCannotResetTheWholeRequestDeadline() {
        response = { MockResponse().setChunkedBody("""{"delay":22}""", 1).throttleBody(1, 70, TimeUnit.MILLISECONDS) }
        boundedFailure { MihomoControllerClient(vm.getApplication()).delay("node") }
        assertEquals(1, server.requestCount)
    }

    @Test fun blockedHeadersAreClosedAtTheOperationDeadline() {
        response = { MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE) }
        boundedFailure { MihomoControllerClient(vm.getApplication()).groupDelay("Route") }
        assertEquals(1, server.requestCount)
    }

    @Test fun fallbackUrlsShareOneBudgetInsteadOfRestartingTheClock() {
        vm.prefs.edit().putBoolean("proxyCustomDelayUrlEnabled", true)
            .putString("proxyCustomDelayUrl", "https://fixture.invalid/204").commit()
        response = { MockResponse().setResponseCode(503).setBody("failed").setBodyDelay(210, TimeUnit.MILLISECONDS) }
        boundedFailure(350) { MihomoControllerClient(vm.getApplication()).delay("node") }
        assertEquals("Third fallback must not start after the budget", 2, server.requestCount)
    }

    @Test fun waitingForDelaySlotsIsBoundedAndDoesNotLeakPermits() {
        val field = MihomoControllerClient::class.java.getDeclaredField("DELAY_SLOTS").apply { isAccessible = true }
        val slots = field.get(null) as Semaphore
        assertTrue(slots.tryAcquire(12, 2, TimeUnit.SECONDS))
        try {
            boundedFailure(150) { MihomoControllerClient(vm.getApplication()).delay("node") }
            assertEquals(0, server.requestCount)
        } finally { slots.release(12) }
        assertEquals(12, slots.availablePermits())
    }

    @Test fun cancellingBlockedGroupImmediatelyReleasesBusyWithoutServerCooperation() {
        response = { req -> if (req.requestUrl!!.encodedPath == "/group/Route/delay")
            MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE) else normalResponse(req) }
        vm.testGroup(group)
        eventually("Blocked group reached HTTP") { paths().contains("/group/Route/delay") }
        val start = System.nanoTime()
        vm.viewModelScope.coroutineContext[Job]!!.children.toList().forEach { it.cancel() }
        awaitActions()
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 2000)
        assertTrue(vm.testingGroups.isEmpty())
        assertTrue(vm.testingNodes.isEmpty())
        assertEquals(44L, vm.delays["node"])
        assertTrue(messages.isEmpty())
    }

    @Test fun cancelledLateReplyCannotOverwriteFreshProbeOrKeepItsBusyFlag() {
        val probes = AtomicInteger()
        response = { req -> if (req.requestUrl!!.encodedPath == "/proxies/node/delay" && probes.incrementAndGet() == 1)
            body("""{"delay":999}""").setBodyDelay(800, TimeUnit.MILLISECONDS) else normalResponse(req) }
        vm.testNode("node")
        eventually("Old node reached HTTP") { probes.get() == 1 }
        vm.viewModelScope.coroutineContext[Job]!!.children.toList().forEach { it.cancel() }
        awaitActions()
        vm.testNode("node"); awaitActions()
        assertEquals(22L, vm.delays["node"])
        Thread.sleep(900); shadowOf(Looper.getMainLooper()).idle()
        assertEquals(22L, vm.delays["node"])
        assertTrue(vm.testingNodes.isEmpty())
    }

    @Test fun automaticWaveReturnsCompletedSiblingsAsExplicitPartialOutcome() = runBlocking {
        response = { req -> if (req.requestUrl!!.encodedPath == "/proxies/other/delay")
            MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE) else normalResponse(req) }
        val failure = try {
            latencyProbeOperation(400) {
                vm.repo.groupDelay(group.copy(type = "URLTest"), listOf("node", "other"))
            }
            throw AssertionError("Partial wave must not report full success")
        } catch (partial: IncompleteLatencyProbe) { partial }
        assertEquals(mapOf("node" to 22L), failure.results)
        assertTrue(failure.message.orEmpty().contains("部分结果未返回"))
    }

    @Test fun ordinaryControllerReadHasNoInheritedExpiredProbeBudget() = runBlocking {
        response = { req -> if (req.requestUrl!!.encodedPath == "/group/Route/delay")
            MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
            else body("""{"version":"fixture"}""").setBodyDelay(450, TimeUnit.MILLISECONDS) }
        try { latencyProbeOperation(150) { MihomoControllerClient(vm.getApplication()).groupDelay("Route") } }
        catch (_: IOException) { }
        assertNull(LatencyProbeBudget.CURRENT.get())
        assertEquals("fixture", MihomoControllerClient(vm.getApplication()).version().getString("version"))
    }

    @Test fun realDefaultDeadlineEndsBusyAndTheNextGroupProbeCanRun() {
        response = { req -> if (req.requestUrl!!.encodedPath == "/group/Route/delay")
            MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE) else normalResponse(req) }
        val started = System.nanoTime()
        vm.testGroup(group)
        eventually("Real-budget group reached HTTP") { paths().contains("/group/Route/delay") }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(23)
        while (vm.testingGroups.isNotEmpty() && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        assertTrue("The actual 20-second operation budget must end the visible busy state", vm.testingGroups.isEmpty())
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 23_000)
        assertTrue(vm.testingNodes.isEmpty())
        assertEquals(44L, vm.delays["node"])
        assertTrue(messages.any { it.contains("时限") })
        response = { req -> if (req.requestUrl!!.encodedPath == "/group/Route/delay")
            body("""{"node":22,"other":31}""") else normalResponse(req) }
        vm.testGroup(group); awaitActions()
        assertEquals(22L, vm.delays["node"])
        assertEquals(31L, vm.delays["other"])
        assertTrue(vm.testingGroups.isEmpty())
    }

    @Test fun successfulGroupStillPublishesActualMeasurementsAndReleasesBusy() {
        response = { req -> if (req.requestUrl!!.encodedPath == "/group/Route/delay")
            body("""{"node":22,"other":31}""") else normalResponse(req) }
        vm.testGroup(group); awaitActions()
        assertEquals(22L, vm.delays["node"])
        assertEquals(31L, vm.delays["other"])
        assertTrue(vm.testingGroups.isEmpty())
        assertTrue(vm.testingNodes.isEmpty())
    }

    @Test fun failedGroupReleasesBusyAndRetainsItsPreviousMeasurement() {
        response = { req -> if (req.requestUrl!!.encodedPath == "/group/Route/delay")
            MockResponse().setResponseCode(401) else normalResponse(req) }
        vm.testGroup(group); awaitActions()
        assertEquals(44L, vm.delays["node"])
        assertTrue(vm.testingGroups.isEmpty())
        assertTrue(vm.testingNodes.isEmpty())
        assertEquals(1, messages.count { it.contains("401") })
    }
}
