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

/** Actual launcher/repository HTTP. New implementation tests, not recovered original bytes. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, shadows = [
    PanelRequestOwnershipShadows.RootStatus::class,
    PanelRequestOwnershipShadows.Sites::class,
    PanelActionRuntimeShadows.RuntimeSample::class,
    PanelActionRuntimeShadows.HistoryRecord::class,
    PanelActionRuntimeShadows.NoRoot::class,
])
@LooperMode(LooperMode.Mode.PAUSED)
class LatencyFeedbackRecovery93Test {
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

    @Test fun singleNodeApiFailureRetainsReadingAndExplainsWhySpinnerEnded() {
        response = { req -> if (req.requestUrl!!.encodedPath == "/proxies/node/delay")
            MockResponse().setResponseCode(401) else normalResponse(req) }
        vm.testNode("node")
        assertEquals(true, vm.testingNodes["node"])
        awaitActions()
        assertTrue(vm.testingNodes.isEmpty())
        assertEquals(44L, vm.delays["node"])
        assertEquals(1, messages.count { it.contains("401") })
        assertFalse(messages.any { it.contains("节点测速超时") })
    }

    @Test fun oldApiFailureDoesNotReportAgainstNewProbeOrReleaseItsBusyState() {
        val old = gate(); val fresh = gate(); val probes = AtomicInteger()
        response = { req -> if (req.requestUrl!!.encodedPath == "/proxies/node/delay") {
            if (probes.incrementAndGet() == 1) { old.hold(); MockResponse().setResponseCode(401) }
            else { fresh.hold(); body("""{"delay":22}""") }
        } else normalResponse(req) }
        vm.testNode("node")
        eventually("Old node probe reaches controller") { probes.get() == 1 }
        vm.prefs.edit().putString("proxyCustomApiSecret", "new-fixture-session").commit()
        vm.bumpSettings(); vm.testNode("node")
        eventually("Replacement node probe reaches controller") { probes.get() == 2 }
        old.countDown()
        eventually("Only replacement request remains") { vm.viewModelScope.coroutineContext[Job]!!.children.count { it.isActive } == 1 }
        assertTrue(messages.isEmpty())
        assertEquals(true, vm.testingNodes["node"])
        assertEquals(44L, vm.delays["node"])
        fresh.countDown(); awaitActions()
        assertEquals(22L, vm.delays["node"])
        assertTrue(vm.testingNodes.isEmpty())
    }

    @Test fun ordinaryPollCannotPublishOldHistoryWhileRealGroupProbeIsBusy() {
        val held = gate()
        response = { req -> if (req.requestUrl!!.encodedPath == "/group/Route/delay") {
            held.hold(); body("""{"node":22,"other":31}""")
        } else normalResponse(req) }
        vm.testGroup(group)
        eventually("Group probe reached controller") { paths().contains("/group/Route/delay") }
        var refreshed = false
        vm.viewModelScope.launch { vm.refreshNow(); refreshed = true }
        eventually("Ordinary poll completed") { refreshed }
        assertEquals(true, vm.testingGroups["Route"])
        assertEquals(44L, vm.delays["node"])
        held.countDown(); awaitActions()
        assertEquals(22L, vm.delays["node"])
        assertTrue(vm.testingGroups.isEmpty())
    }

    @Test fun cancelledGroupCannotPublishLateResultsAndReleasesEveryBusyOwner() {
        val held = gate()
        response = { req -> if (req.requestUrl!!.encodedPath == "/group/Route/delay") {
            held.hold(); body("""{"node":999,"other":999}""")
        } else normalResponse(req) }
        vm.testGroup(group)
        eventually("Group probe reached controller") { paths().contains("/group/Route/delay") }
        vm.viewModelScope.cancel(); held.countDown(); awaitActions()
        assertEquals(44L, vm.delays["node"])
        assertTrue(vm.testingGroups.isEmpty())
        assertTrue(vm.testingNodes.isEmpty())
        assertTrue(messages.isEmpty())
    }

    @Test fun emptyGroupResponseKeepsPriorResultsAndReportsMissingMeasurements() {
        response = { req -> if (req.requestUrl!!.encodedPath == "/group/Route/delay")
            body("""{"node":null,"other":"invalid"}""") else normalResponse(req) }
        vm.testGroup(group); awaitActions()
        assertEquals(44L, vm.delays["node"])
        assertTrue(vm.testingGroups.isEmpty())
        assertTrue(vm.testingNodes.isEmpty())
        assertEquals(1, messages.count { it.contains("未取得有效结果") })
    }

    @Test fun allControllerFailuresDoNotBecomeSuccessfulZeroNodeCompletion() {
        response = { req -> if (req.requestUrl!!.encodedPath.endsWith("/delay"))
            MockResponse().setResponseCode(401) else normalResponse(req) }
        vm.testAll(); awaitActions()
        assertEquals(44L, vm.delays["node"])
        assertFalse(vm.testingAll)
        assertTrue(vm.testingNodes.isEmpty())
        assertEquals(1, messages.count { it.contains("未取得有效结果") })
        assertFalse(messages.any { it.startsWith("测速完成") })
    }

    @Test fun changedLatencyTargetRejectsLateRepositoryResultAndAllowsNewTarget() {
        val held = gate(); val probes = AtomicInteger()
        response = { req -> if (req.requestUrl!!.encodedPath == "/proxies/node/delay") {
            if (probes.incrementAndGet() == 1) { held.hold(); body("""{"delay":999}""") }
            else body("""{"delay":22}""")
        } else normalResponse(req) }
        vm.prefs.edit().putBoolean("proxyCustomDelayUrlEnabled", true)
            .putString("proxyCustomDelayUrl", "https://first.invalid/204").commit()
        var value: Long? = null; var failure: Throwable? = null
        vm.viewModelScope.launch { try { value = vm.repo.delay("node") } catch (error: IOException) { failure = error } }
        eventually("Old URL probe reached controller") { probes.get() == 1 }
        vm.prefs.edit().putString("proxyCustomDelayUrl", "https://second.invalid/204").commit()
        held.countDown(); awaitActions()
        assertNull(value)
        assertTrue(failure is IOException)
        vm.viewModelScope.launch { value = vm.repo.delay("node") }
        awaitActions()
        assertEquals(22L, value)
        val urls = synchronized(requests) { requests.filter { it.requestUrl!!.encodedPath.endsWith("/delay") }
            .map { it.requestUrl!!.queryParameter("url") } }
        assertEquals(listOf("https://first.invalid/204", "https://second.invalid/204"), urls)
    }

    @Test fun changingOnlyLatencyTargetDoesNotRevokeSameControllerSelection() {
        val held = gate(); var done = false; var failure: Throwable? = null
        response = { req -> if (req.method == "PUT" && req.requestUrl!!.encodedPath == "/proxies/Route") {
            held.hold(); MockResponse().setResponseCode(204)
        } else normalResponse(req) }
        val ticket = vm.repo.captureSelection()
        vm.viewModelScope.launch {
            try { vm.repo.select("Route", "other", ticket = ticket); done = true }
            catch (error: IOException) { failure = error }
        }
        eventually("Selection PUT reached controller") { paths().contains("/proxies/Route") }
        vm.prefs.edit().putBoolean("proxyCustomDelayUrlEnabled", true)
            .putString("proxyCustomDelayUrl", "https://new.invalid/204").commit()
        held.countDown(); awaitActions()
        assertNull(failure)
        assertTrue(done)
    }
}
