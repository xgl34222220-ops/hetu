package io.github.xgl34222220.hetu

import android.app.Application
import android.os.Looper
import androidx.compose.runtime.MutableState
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Real launcher ViewModel/repository/controller, with deterministic loopback responses. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, shadows = [
    PanelRequestOwnershipShadows.RootStatus::class,
    PanelRequestOwnershipShadows.Sites::class,
    PanelActionRuntimeShadows.RuntimeSample::class,
    PanelActionRuntimeShadows.HistoryRecord::class,
    PanelActionRuntimeShadows.NoRoot::class,
])
@LooperMode(LooperMode.Mode.PAUSED)
class PanelRequestOwnershipTest {
    private lateinit var vm: HetuViewModel
    private lateinit var server: MockWebServer
    private val servers = mutableListOf<MockWebServer>()
    private val gates = mutableListOf<CountDownLatch>()
    private val requests = Collections.synchronizedList(mutableListOf<RecordedRequest>())
    @Volatile private var response: (RecordedRequest) -> MockResponse = { MockResponse().setResponseCode(404) }
    private val groupA = ProxyGroupUi("A", "Selector", "shared", listOf(ProxyNodeUi("shared"), ProxyNodeUi("a")))
    private val groupB = ProxyGroupUi("B", "Selector", "shared", listOf(ProxyNodeUi("shared"), ProxyNodeUi("b")))

    @Before fun prepare() {
        server = newServer { response(it) }
        val app = ApplicationProvider.getApplicationContext<Application>()
        app.getSharedPreferences("hetu", 0).edit().clear()
            .putBoolean("proxyCustomApiEnabled", true)
            .putString("proxyCustomApiHost", "127.0.0.1")
            .putInt("proxyCustomApiPort", server.port)
            .putBoolean("proxyApiHistoryEnabled", false).commit()
        PanelActionRuntimeShadows.RuntimeSample.value = ProxyRuntimeSnapshot(running = true, pid = 77)
        vm = HetuViewModel(app)
        fixture("state", ProxyComposeState(running = true, panelReady = true, corePid = 77, groups = listOf(groupA, groupB)))
        vm.delays["shared"] = 44L
        response = ::normalResponse
    }

    @After fun finish() {
        vm.viewModelScope.cancel()
        gates.forEach { it.countDown() }
        PanelRequestOwnershipShadows.Sites.releaseAll()
        PanelActionRuntimeShadows.RuntimeSample.release()
        PanelActionRuntimeShadows.HistoryRecord.release()
        awaitActions()
        servers.forEach { it.shutdown() }
        assertEquals("Fixtures must not perform Root operations", 0, PanelActionRuntimeShadows.NoRoot.calls)
    }

    private fun newServer(handler: (RecordedRequest) -> MockResponse): MockWebServer = MockWebServer().apply {
        dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                return handler(request)
            }
        }
        start()
        servers += this
    }
    private fun gate() = CountDownLatch(1).also { gates += it }
    private fun CountDownLatch.hold() { check(await(8, TimeUnit.SECONDS)) { "Response gate not released" } }
    private fun body(json: String) = MockResponse().setBody(json)
    private fun paths() = synchronized(requests) { requests.map { it.path.orEmpty() } }
    private fun eventually(reason: String, predicate: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(6)
        while (System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (predicate()) return
            Thread.sleep(10)
        }
        assertTrue("$reason; requests=${paths()}, busy=${vm.testingNodes}, groups=${vm.testingGroups}", predicate())
    }
    private fun awaitActions() = eventually("ViewModel requests complete") {
        vm.viewModelScope.coroutineContext[Job]!!.children.none { it.isActive }
    }
    @Suppress("UNCHECKED_CAST")
    private fun fixture(name: String, value: Any?) {
        val field = HetuViewModel::class.java.getDeclaredField(name + "\$delegate").apply { isAccessible = true }
        (field.get(vm) as MutableState<Any?>).value = value
    }
    private fun stopFixture() {
        fixture("state", vm.state.copy(running = false))
        fixture("providers", emptyList<DashboardProviderUi>())
    }
    private fun changeApi(port: Int = server.port) {
        vm.prefs.edit().putInt("proxyCustomApiPort", port).putString("proxyCustomApiSecret", "new-session").commit()
        vm.bumpSettings()
    }
    private fun snapshot(sharedDelay: Long? = null): String {
        val proxies = JSONObject()
        for (group in listOf(groupA, groupB)) proxies.put(group.name, JSONObject()
            .put("type", "Selector").put("now", group.now).put("all", JSONArray(group.nodes.map { it.name })))
        for (node in listOf("shared", "a", "b")) proxies.put(node, JSONObject().put("type", "Shadowsocks").apply {
            if (node == "shared" && sharedDelay != null) put("history", JSONArray().put(JSONObject().put("delay", sharedDelay)))
        })
        return JSONObject().put("proxies", proxies).toString()
    }
    private fun provider(name: String, updatedAt: String = "") = """{"providers":{"$name":{"vehicleType":"HTTP","updatedAt":"$updatedAt","proxies":["shared"]}}}"""
    private fun fixtureProvider() = DashboardProviderUi("P", "HTTP", "", "", "before", 0L, 0L, 0L, 0L, setOf("shared"), false)
    private fun normalResponse(req: RecordedRequest): MockResponse = when {
        req.path == "/proxies" -> body(snapshot())
        req.path == "/providers/proxies" -> body("""{"providers":{}}""")
        req.path == "/connections" -> body("""{"connections":[]}""")
        req.path == "/configs" -> body("""{"mode":"rule"}""")
        req.path == "/version" -> body("""{"version":"fixture"}""")
        req.requestUrl?.pathSegments?.lastOrNull() == "delay" -> body("""{"delay":12}""")
        else -> MockResponse().setResponseCode(404)
    }

    @Test fun sharedGroupsKeepNodeBusyUntilEveryOwnerCompletes() {
        val first = gate(); val second = gate()
        response = { req -> when (req.requestUrl!!.encodedPath) {
            "/group/A/delay" -> { first.hold(); body("""{"shared":81,"a":82}""") }
            "/group/B/delay" -> { second.hold(); body("""{"shared":91,"b":92}""") }
            else -> normalResponse(req)
        } }
        vm.testGroup(groupA); vm.testGroup(groupB)
        eventually("Both groups reach controller") { paths().count { it.startsWith("/group/") } == 2 }
        first.countDown()
        eventually("First group completes") { vm.testingGroups["A"] != true }
        assertEquals(true, vm.testingNodes["shared"])
        assertEquals(true, vm.testingGroups["B"])
        assertEquals(82L, vm.delays["a"])
        second.countDown(); awaitActions()
        assertTrue(vm.testingNodes.isEmpty())
        assertEquals(91L, vm.delays["shared"])
    }

    @Test fun lateOlderGroupCannotOverwriteTheNewerSharedNodeResult() {
        val old = gate()
        response = { req -> when (req.requestUrl!!.encodedPath) {
            "/group/A/delay" -> { old.hold(); body("""{"shared":999,"a":81}""") }
            "/group/B/delay" -> body("""{"shared":90,"b":91}""")
            else -> normalResponse(req)
        } }
        vm.testGroup(groupA)
        eventually("Old group is in flight") { paths().any { it.startsWith("/group/A/") } }
        vm.testGroup(groupB)
        eventually("New group result applies") { vm.delays["shared"] == 90L }
        assertEquals(true, vm.testingNodes["shared"])
        old.countDown(); awaitActions()
        assertEquals(90L, vm.delays["shared"])
        assertEquals(81L, vm.delays["a"])
    }

    @Test fun apiChangeOldFinallyCannotClearTheNewSameGroupBusyFlag() {
        val old = gate(); val fresh = gate()
        response = { old.hold(); body("""{"shared":999}""") }
        vm.testGroup(groupA)
        eventually("Old group reaches controller") { paths().isNotEmpty() }
        val next = newServer { fresh.hold(); body("""{"shared":22}""") }
        changeApi(next.port)
        vm.testGroup(groupA)
        eventually("New group reaches new controller") { paths().size == 2 }
        old.countDown()
        eventually("Old request completes while new stays pending") {
            vm.viewModelScope.coroutineContext[Job]!!.children.count { it.isActive } == 1
        }
        assertEquals(true, vm.testingGroups["A"])
        assertEquals(true, vm.testingNodes["shared"])
        assertEquals(44L, vm.delays["shared"])
        fresh.countDown(); awaitActions()
        assertEquals(22L, vm.delays["shared"])
        assertTrue(vm.testingNodes.isEmpty())
    }

    @Test fun stoppingDropsALateGroupResultAndReleasesBusy() {
        val old = gate()
        response = { old.hold(); body("""{"shared":999}""") }
        vm.testGroup(groupA)
        eventually("Group reaches controller") { paths().isNotEmpty() }
        stopFixture(); old.countDown(); awaitActions()
        assertEquals(44L, vm.delays["shared"])
        assertTrue(vm.testingNodes.isEmpty())
        assertTrue(vm.testingGroups.isEmpty())
    }

    @Test fun olderIndividualProbeCannotOverwriteALaterGroupResult() {
        val old = gate()
        response = { req -> when {
            req.requestUrl!!.encodedPath == "/proxies/shared/delay" -> { old.hold(); body("""{"delay":999}""") }
            req.requestUrl!!.encodedPath == "/group/B/delay" -> body("""{"shared":90}""")
            else -> normalResponse(req)
        } }
        vm.testNode("shared")
        eventually("Individual probe reaches controller") { paths().any { it.startsWith("/proxies/shared/delay") } }
        vm.testGroup(groupB)
        eventually("New group result applies") { vm.delays["shared"] == 90L }
        old.countDown(); awaitActions()
        assertEquals(90L, vm.delays["shared"])
        assertTrue(vm.testingNodes.isEmpty())
    }

    @Test fun olderGlobalProbeHoldsBusyButCannotOverwriteALaterGroupResult() {
        val old = gate()
        response = { req -> when {
            req.requestUrl!!.encodedPath == "/proxies/shared/delay" -> { old.hold(); body("""{"delay":999}""") }
            req.requestUrl!!.encodedPath == "/group/B/delay" -> body("""{"shared":90}""")
            else -> normalResponse(req)
        } }
        vm.testAll()
        assertEquals(true, vm.testingNodes["shared"])
        eventually("Global shared probe reaches controller") { paths().any { it.startsWith("/proxies/shared/delay") } }
        vm.testGroup(groupB)
        eventually("New group result applies") { vm.delays["shared"] == 90L }
        assertEquals(true, vm.testingNodes["shared"])
        old.countDown(); awaitActions()
        assertEquals(90L, vm.delays["shared"])
        assertFalse(vm.testingAll)
        assertTrue(vm.testingNodes.isEmpty())
    }

    @Test fun olderProviderReadCannotReplaceTheLatestProviderRead() {
        val old = gate(); val reads = AtomicInteger()
        response = { if (reads.incrementAndGet() == 1) { old.hold(); body(provider("old")) } else body(provider("fresh")) }
        vm.loadProviders()
        eventually("Old provider read reaches controller") { reads.get() == 1 }
        vm.loadProviders()
        eventually("Fresh providers apply") { vm.providers.singleOrNull()?.name == "fresh" }
        old.countDown(); awaitActions()
        assertEquals("fresh", vm.providers.single().name)
    }

    @Test fun stoppingDropsALateProviderRead() {
        val old = gate()
        response = { old.hold(); body(provider("old")) }
        vm.loadProviders()
        eventually("Provider read reaches controller") { paths().isNotEmpty() }
        stopFixture(); old.countDown(); awaitActions()
        assertTrue(vm.providers.isEmpty())
        assertFalse(vm.state.running)
    }

    @Test fun apiChangeDropsOldProvidersAndAcceptsTheNewControllerProviders() {
        val old = gate()
        response = { old.hold(); body(provider("old")) }
        vm.loadProviders()
        eventually("Old provider read reaches controller") { paths().isNotEmpty() }
        val next = newServer { body(provider("fresh")) }
        changeApi(next.port); vm.loadProviders()
        eventually("New controller providers apply") { vm.providers.singleOrNull()?.name == "fresh" }
        old.countDown(); awaitActions()
        assertEquals("fresh", vm.providers.single().name)
    }

    @Test fun stoppingDropsPullRefreshProvidersAndPersistedSiteResults() {
        val old = gate(); val reads = AtomicInteger()
        val before = vm.siteDelays.toMap()
        PanelRequestOwnershipShadows.Sites.pause = true
        response = { req -> if (req.path == "/providers/proxies" && reads.incrementAndGet() == 3) {
            old.hold(); body(provider("late"))
        } else normalResponse(req) }
        vm.pullRefresh()
        eventually("Pull refresh parallel reads are pending") { reads.get() == 3 && PanelRequestOwnershipShadows.Sites.pendingCount() == 1 }
        stopFixture(); old.countDown()
        PanelRequestOwnershipShadows.Sites.releaseFirst(mapOf("Baidu" to 999L))
        awaitActions()
        assertTrue(vm.providers.isEmpty())
        assertEquals(before, vm.siteDelays)
        assertEquals(before, ProxyLatencyTargets.lastResults(vm.prefs))
        assertFalse(vm.refreshing)
    }

    @Test fun apiChangeDropsAnInFlightRefreshStateAndItsCoreHistory() {
        val old = gate()
        val before = vm.state
        var completed = false
        var result: ProxyComposeState? = null
        response = { req -> when (req.path) {
            "/configs" -> { old.hold(); normalResponse(req) }
            "/proxies" -> body(snapshot(sharedDelay = 999L))
            else -> normalResponse(req)
        } }
        vm.viewModelScope.launch { result = vm.refreshNow(); completed = true }
        eventually("State read reaches old controller") { paths().contains("/configs") }
        changeApi(); old.countDown()
        eventually("Old state read completes") { completed }
        assertNull(result)
        assertSame(before, vm.state)
        assertEquals(44L, vm.delays["shared"])
    }

    @Test fun externalPidChangeInvalidatesOldGroupAndProtectsANewOwner() {
        val old = gate(); val fresh = gate(); val groupReads = AtomicInteger()
        response = { req -> if (req.requestUrl!!.encodedPath == "/group/A/delay") {
            if (groupReads.incrementAndGet() == 1) { old.hold(); body("""{"shared":999}""") }
            else { fresh.hold(); body("""{"shared":22}""") }
        } else normalResponse(req) }
        vm.testGroup(groupA)
        eventually("Old group reaches controller") { groupReads.get() == 1 }
        PanelRequestOwnershipShadows.RootStatus.pid = 88
        PanelActionRuntimeShadows.RuntimeSample.value = ProxyRuntimeSnapshot(running = true, pid = 88)
        vm.prefs.edit().putLong("proxyRootHealthProbeElapsed", 0L).commit()
        vm.viewModelScope.launch { vm.refreshNow() }
        eventually("New PID is confirmed") { vm.state.corePid == 88 }
        vm.testGroup(groupA)
        eventually("New group starts in new PID session") { groupReads.get() == 2 }
        old.countDown()
        eventually("Only new group remains") { vm.viewModelScope.coroutineContext[Job]!!.children.count { it.isActive } == 1 }
        assertEquals(true, vm.testingGroups["A"])
        assertEquals(44L, vm.delays["shared"])
        fresh.countDown(); awaitActions()
        assertEquals(22L, vm.delays["shared"])
    }

    @Test fun ordinaryPollKeepsAnActiveGroupInTheSamePidSession() {
        val old = gate()
        response = { req -> if (req.requestUrl!!.encodedPath == "/group/A/delay") {
            old.hold(); body("""{"shared":22}""")
        } else normalResponse(req) }
        vm.testGroup(groupA)
        eventually("Group reaches controller") { paths().any { it.startsWith("/group/A/") } }
        var refreshed = false
        vm.viewModelScope.launch { vm.refreshNow(); refreshed = true }
        eventually("Ordinary poll completes") { refreshed }
        assertEquals(true, vm.testingGroups["A"])
        assertEquals(true, vm.testingNodes["shared"])
        old.countDown(); awaitActions()
        assertEquals(22L, vm.delays["shared"])
    }

    @Test fun oldSiteCompletionCannotClearOrOverwriteTheNewSessionSiteProbe() {
        PanelRequestOwnershipShadows.Sites.pause = true
        val before = vm.siteDelays.toMap()
        vm.measureSites()
        eventually("Old site probe is suspended") { PanelRequestOwnershipShadows.Sites.pendingCount() == 1 }
        changeApi(); vm.measureSites()
        eventually("New site probe is suspended") { PanelRequestOwnershipShadows.Sites.pendingCount() == 2 }
        PanelRequestOwnershipShadows.Sites.releaseFirst(mapOf("Baidu" to 999L))
        eventually("Old site probe completes") { vm.viewModelScope.coroutineContext[Job]!!.children.count { it.isActive } == 1 }
        assertTrue(vm.siteTesting)
        assertEquals(before, vm.siteDelays)
        PanelRequestOwnershipShadows.Sites.releaseFirst(mapOf("Baidu" to 22L))
        awaitActions()
        assertFalse(vm.siteTesting)
        assertEquals(22L, vm.siteDelays["Baidu"])
        assertEquals(22L, ProxyLatencyTargets.lastResults(vm.prefs)["Baidu"])
    }

    @Test fun staleSingleProviderUpdateCannotOverwriteOrReleaseTheNewSessionTask() {
        val old = gate(); val fresh = gate(); val reads = AtomicInteger()
        fixture("providers", listOf(fixtureProvider()))
        response = { req -> when {
            req.method == "PUT" && req.path == "/providers/proxies/P" -> MockResponse().setResponseCode(204)
            req.path == "/providers/proxies" -> when (reads.incrementAndGet()) {
                1 -> { old.hold(); body(provider("P", "old")) }
                2 -> { fresh.hold(); body(provider("P", "fresh")) }
                else -> body(provider("P", "fresh"))
            }
            else -> normalResponse(req)
        } }
        vm.updateProvider("P")
        eventually("Old update readback is pending") { reads.get() == 1 }
        changeApi(); vm.updateProvider("P")
        eventually("New update readback is pending") { reads.get() == 2 }
        old.countDown()
        eventually("Old update completes") { vm.viewModelScope.coroutineContext[Job]!!.children.count { it.isActive } == 1 }
        assertEquals("before", vm.providers.single().updatedAt)
        assertEquals(true, vm.providerTasks["P"]?.running)
        fresh.countDown(); awaitActions()
        assertEquals("fresh", vm.providers.single().updatedAt)
        assertEquals(true, vm.providerTasks["P"]?.ok)
        assertEquals(false, vm.providerTasks["P"]?.running)
    }

    @Test fun staleBulkProviderUpdateCannotOverwriteOrReleaseTheNewSessionTask() {
        val old = gate(); val fresh = gate(); val reads = AtomicInteger()
        fixture("providers", listOf(fixtureProvider()))
        response = { req -> when {
            req.method == "PUT" && req.path == "/providers/proxies/P" -> MockResponse().setResponseCode(204)
            req.path == "/providers/proxies" -> when (reads.incrementAndGet()) {
                1 -> { old.hold(); body(provider("P", "old")) }
                2 -> { fresh.hold(); body(provider("P", "fresh")) }
                else -> body(provider("P", "fresh"))
            }
            else -> normalResponse(req)
        } }
        vm.updateAllProviders()
        eventually("Old bulk update readback is pending") { reads.get() == 1 }
        changeApi(); vm.updateAllProviders()
        eventually("New bulk update readback is pending") { reads.get() == 2 }
        old.countDown()
        eventually("Old bulk update completes") { vm.viewModelScope.coroutineContext[Job]!!.children.count { it.isActive } == 1 }
        assertTrue(vm.providersUpdatingAll)
        assertEquals(true, vm.providerTasks["P"]?.running)
        assertEquals("before", vm.providers.single().updatedAt)
        fresh.countDown(); awaitActions()
        assertFalse(vm.providersUpdatingAll)
        assertEquals("fresh", vm.providers.single().updatedAt)
        assertEquals(true, vm.providerTasks["P"]?.ok)
        assertEquals(false, vm.providerTasks["P"]?.running)
    }
}
