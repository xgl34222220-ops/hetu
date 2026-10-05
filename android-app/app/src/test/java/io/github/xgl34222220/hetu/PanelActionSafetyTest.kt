package io.github.xgl34222220.hetu

import android.app.Application
import android.os.Looper
import androidx.compose.runtime.MutableState
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
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

/** Current ViewModel, repository and HTTP client; no Root, live core, or external network. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, shadows = [
    PanelActionRuntimeShadows.RootStatus::class,
    PanelActionRuntimeShadows.RuntimeSample::class,
    PanelActionRuntimeShadows.HistoryRecord::class,
    PanelActionRuntimeShadows.NoRoot::class,
])
@LooperMode(LooperMode.Mode.PAUSED)
class PanelActionSafetyTest {
    private lateinit var vm: HetuViewModel
    private lateinit var server: MockWebServer
    private val requests = Collections.synchronizedList(mutableListOf<RecordedRequest>())
    private val gates = mutableListOf<CountDownLatch>()
    private val messages = mutableListOf<String>()
    private lateinit var collector: CoroutineScope
    private val group = ProxyGroupUi("SELECT", "Selector", "old", listOf(
        ProxyNodeUi("old"), ProxyNodeUi("new"), ProxyNodeUi("untested"), ProxyNodeUi("DIRECT"),
    ))
    @Volatile private var response: (RecordedRequest) -> MockResponse = { MockResponse().setResponseCode(404) }

    @Before fun prepare() {
        requests.clear()
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    requests += request
                    return response(request)
                }
            }
            start()
        }
        val app = ApplicationProvider.getApplicationContext<Application>()
        app.getSharedPreferences("hetu", 0).edit().clear()
            .putBoolean("proxyCustomApiEnabled", true)
            .putString("proxyCustomApiHost", "127.0.0.1")
            .putInt("proxyCustomApiPort", server.port)
            .putBoolean("proxyApiHistoryEnabled", false)
            .commit()
        PanelActionRuntimeShadows.RuntimeSample.value = ProxyRuntimeSnapshot(running = true, pid = 77, rssBytes = 2048L, elapsedSeconds = 123L)
        vm = HetuViewModel(app)
        fixtureState(ProxyComposeState(running = true, panelReady = true, groups = listOf(group)))
        vm.delays["old"] = 44L
        vm.delays["new"] = 65L
        collector = CoroutineScope(Dispatchers.Main.immediate)
        collector.launch { vm.messages.collect { messages += it } }
        shadowOf(Looper.getMainLooper()).idle()
    }

    @After fun finish() {
        vm.viewModelScope.cancel()
        gates.forEach { it.countDown() }
        PanelActionRuntimeShadows.RuntimeSample.release()
        PanelActionRuntimeShadows.HistoryRecord.release()
        eventually("All action flags clear after scope cancellation") {
            vm.pendingSelection.isEmpty() && vm.testingGroups.isEmpty() && vm.testingNodes.isEmpty()
        }
        collector.cancel()
        server.shutdown()
        assertEquals("Fixture must never call Root", 0, PanelActionRuntimeShadows.NoRoot.calls)
    }

    @Suppress("UNCHECKED_CAST")
    private fun fixture(name: String, value: Any?) {
        val field = HetuViewModel::class.java.getDeclaredField(name + "\$delegate").apply { isAccessible = true }
        (field.get(vm) as MutableState<Any?>).value = value
    }
    private fun fixtureState(state: ProxyComposeState) = fixture("state", state)
    private fun stoppedFixture() {
        fixtureState(vm.state.copy(running = false))
        fixture("runtime", ProxyRuntimeSnapshot())
        fixture("providers", emptyList<DashboardProviderUi>())
        fixture("coreVersion", "")
        vm.prefs.edit().putBoolean("proxyUiLastRunning", false)
            .putLong("proxyUiLastRss", 0L).putLong("proxyUiLastElapsed", 0L).commit()
    }
    private fun assertStoppedSnapshot() {
        assertFalse(vm.state.running)
        assertFalse(vm.runtime.running)
        assertEquals(0L, vm.runtime.rssBytes)
        assertEquals(0L, vm.runtime.elapsedSeconds)
        assertTrue(vm.providers.isEmpty())
        assertEquals("", vm.coreVersion)
        assertFalse(vm.prefs.getBoolean("proxyUiLastRunning", true))
        assertEquals(0L, vm.prefs.getLong("proxyUiLastRss", -1L))
        assertEquals(0L, vm.prefs.getLong("proxyUiLastElapsed", -1L))
    }

    private fun eventually(reason: String, predicate: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(6)
        while (System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (predicate()) return
            Thread.sleep(10)
        }
        assertTrue("$reason [panelReady=${vm.state.panelReady}, readFailed=${vm.state.controllerReadFailed}, pendingSelection=${vm.pendingSelection.keys}, historyPaused=${PanelActionRuntimeShadows.HistoryRecord.pause}, requestPaths=${requestSnapshot().map { it.path }}]", predicate())
    }

    private fun gate() = CountDownLatch(1).also { gates += it }
    private fun CountDownLatch.hold() { check(await(6, TimeUnit.SECONDS)) { "Fixture gate not released" } }
    private fun body(value: String) = MockResponse().setBody(value)
    private fun requestSnapshot() = synchronized(requests) { requests.toList() }
    private fun selectionSnapshot(now: String = "new", includeGroup: Boolean = true): String {
        val proxies = JSONObject()
        if (includeGroup) proxies.put("SELECT", JSONObject()
            .put("type", "Selector").put("now", now).put("all", org.json.JSONArray(listOf("old", "new", "untested"))))
        for (name in listOf("old", "new", "untested")) proxies.put(name, JSONObject().put("type", "Shadowsocks"))
        return JSONObject().put("proxies", proxies).toString()
    }
    private fun selectionResponses(
        now: String = "new", includeGroup: Boolean = true, putCode: Int = 204,
        putGate: CountDownLatch? = null, readGate: CountDownLatch? = null, readCode: Int = 200, connectionCode: Int = 200,
        providerGate: CountDownLatch? = null, versionGate: CountDownLatch? = null,
    ) {
        val providerReads = java.util.concurrent.atomic.AtomicInteger()
        response = { req -> when {
            req.method == "PUT" && req.path == "/proxies/SELECT" -> {
                putGate?.hold()
                MockResponse().setResponseCode(putCode).apply { if (putCode != 204) setBody("rejected by fixture") }
            }
            req.path == "/proxies" -> {
                readGate?.hold()
                body(selectionSnapshot(now, includeGroup)).setResponseCode(readCode)
            }
            req.path == "/providers/proxies" -> {
                if (providerGate != null && providerReads.incrementAndGet() > 1) {
                    providerGate.hold()
                    body("""{"providers":{"late":{"vehicleType":"HTTP","proxies":["old","new"]}}}""")
                } else body("{\"providers\":{}}")
            }
            req.path == "/connections" -> body("{\"connections\":[]}").setResponseCode(connectionCode)
            req.path == "/configs" -> body("{\"mode\":\"rule\"}")
            req.path == "/version" -> { versionGate?.hold(); body("{\"version\":\"fixture\"}") }
            else -> MockResponse().setResponseCode(404)
        } }
    }
    private fun awaitSelection() = eventually("Selection pending flag cleared") { vm.pendingSelection.isEmpty() }
    private fun awaitGroup() = eventually("Probe busy flags cleared") { vm.testingGroups.isEmpty() && vm.testingNodes.isEmpty() }
    private fun assertNonconfirmation() {
        eventually("Missing acknowledgement must report failure") { messages.any { it.contains("核心尚未确认") } }
        assertFalse(vm.state.groups.any { it.name == "SELECT" && it.now == "new" })
    }

    @Test fun completeGroupResultUsesCoreEndpointWithoutChangingSelection() {
        response = { body("""{"old":81,"new":0,"untested":-1,"outside":12}""") }
        vm.testGroup(group)
        assertTrue(vm.testingGroups["SELECT"] == true)
        awaitGroup()
        assertEquals(mapOf("old" to 81L, "new" to 0L, "untested" to -1L), vm.delays.toMap())
        assertEquals("old", vm.state.groups.single().now)
        assertTrue(vm.pendingSelection.isEmpty())
        val request = requestSnapshot().single()
        assertEquals("GET", request.method)
        assertEquals(listOf("group", "SELECT", "delay"), request.requestUrl!!.pathSegments)
        assertEquals("5000", request.requestUrl!!.queryParameter("timeout"))
        assertFalse(vm.delays.containsKey("DIRECT"))
    }

    @Test fun partialGroupResultPreservesMissingMeasurementsAndUnknownNodes() {
        response = { body("""{"old":81}""") }
        vm.testGroup(group)
        awaitGroup()
        assertEquals(81L, vm.delays["old"])
        assertEquals(65L, vm.delays["new"])
        assertFalse("Missing entry must remain unknown", vm.delays.containsKey("untested"))
    }

    @Test fun malformedGroupEntriesCannotBecomeTimeouts() {
        response = { body("""{"old":null,"new":"timeout","untested":{"delay":12}}""") }
        vm.testGroup(group)
        awaitGroup()
        assertEquals(mapOf("old" to 44L, "new" to 65L), vm.delays.toMap())
    }

    @Test fun repositoryRejectsMalformedValuesButKeepsExactIntegersAndExplicitTimeout() {
        response = { body("""{"positive":123,"zero":0,"timeout":-1,"large":9007199254740993,"text":"53","fraction":1.5,"null":null,"bool":true,"array":[],"object":{}}""") }
        val values = kotlinx.coroutines.runBlocking { vm.repo.groupDelay("SELECT") }
        assertEquals(mapOf("positive" to 123L, "zero" to 0L, "timeout" to -1L, "large" to 9007199254740993L), values)
    }

    @Test fun malformedWholeResponsePreservesMeasurementsAndClearsBusyFlags() {
        response = { body("not JSON") }
        vm.testGroup(group)
        awaitGroup()
        assertEquals(mapOf("old" to 44L, "new" to 65L), vm.delays.toMap())
        assertTrue(messages.isNotEmpty())
    }

    @Test fun failedGroupResponsePreservesMeasurementsAndClearsBusyFlags() {
        response = { body("controller unavailable").setResponseCode(503) }
        vm.testGroup(group)
        awaitGroup()
        assertEquals(mapOf("old" to 44L, "new" to 65L), vm.delays.toMap())
        assertTrue(messages.isNotEmpty())
    }

    @Test fun disconnectedGroupResponsePreservesMeasurementsAndClearsBusyFlags() {
        response = { MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST) }
        vm.testGroup(group)
        awaitGroup()
        assertEquals(mapOf("old" to 44L, "new" to 65L), vm.delays.toMap())
    }

    @Test fun cancelledGroupResponseDoesNotApplyLateMeasurementsAndClearsBusyFlags() {
        val responseGate = gate()
        response = { responseGate.hold(); body("""{"old":999,"new":888}""") }
        vm.testGroup(group)
        eventually("Group HTTP reached fixture") { requests.isNotEmpty() }
        vm.viewModelScope.cancel()
        responseGate.countDown()
        awaitGroup()
        assertEquals(mapOf("old" to 44L, "new" to 65L), vm.delays.toMap())
        assertTrue(messages.isEmpty())
    }

    @Test fun duplicateGroupProbeIsSuppressed() {
        val responseGate = gate()
        response = { responseGate.hold(); body("""{"old":81}""") }
        vm.testGroup(group)
        vm.testGroup(group)
        eventually("First group HTTP reached fixture") { requests.isNotEmpty() }
        responseGate.countDown()
        awaitGroup()
        assertEquals(1, requests.size)
    }

    @Test fun delayedSelectionWaitsForReadbackAndSurvivesNavigationWithNoDuplicatePut() {
        val readGate = gate()
        selectionResponses(readGate = readGate)
        vm.select("SELECT", "new")
        vm.select("SELECT", "untested")
        eventually("Readback started") { requestSnapshot().any { it.path == "/proxies" } }
        assertEquals("old", vm.state.groups.single().now)
        assertEquals("new", vm.pendingSelection["SELECT"])
        vm.tab = HxTab.Home
        vm.onBackground()
        vm.openPanel("proxies")
        assertEquals("new", vm.pendingSelection["SELECT"])
        readGate.countDown()
        awaitSelection()
        assertEquals("new", vm.state.groups.single().now)
        assertEquals(HxTab.Panel, vm.tab)
        assertEquals("proxies", vm.panelSection)
        assertEquals(1, requestSnapshot().count { it.method == "PUT" })
        assertEquals("new", JSONObject(requestSnapshot().single { it.method == "PUT" }.body.readUtf8()).getString("name"))
        assertTrue(messages.isEmpty())
    }

    @Test fun rejectedSelectionRetainsActualSelectionAndClearsPending() {
        selectionResponses(putCode = 400)
        vm.select("SELECT", "new")
        awaitSelection()
        assertEquals("old", vm.state.groups.single().now)
        assertEquals(1, requests.size)
        assertTrue(messages.any { it.contains("切换失败") })
    }

    @Test fun unchangedReadbackReportsNonconfirmation() {
        selectionResponses(now = "old")
        vm.select("SELECT", "new")
        awaitSelection()
        assertNonconfirmation()
        assertEquals("old", vm.state.groups.single().now)
    }

    @Test fun mismatchedReadbackUsesActualStateAndReportsNonconfirmation() {
        selectionResponses(now = "untested")
        vm.select("SELECT", "new")
        awaitSelection()
        assertNonconfirmation()
        assertEquals("untested", vm.state.groups.single().now)
    }

    @Test fun removedGroupReadbackDoesNotConfirmSelection() {
        selectionResponses(includeGroup = false)
        vm.select("SELECT", "new")
        awaitSelection()
        assertNonconfirmation()
        assertTrue(vm.state.groups.isEmpty())
    }

    @Test fun failedReadbackDoesNotConfirmSelectionDespiteSuccessfulConnectionRead() {
        selectionResponses(readCode = 500)
        vm.select("SELECT", "new")
        awaitSelection()
        assertNonconfirmation()
    }

    @Test fun cancelledSelectionKeepsActualSelectionAndClearsPending() {
        val putGate = gate()
        selectionResponses(putGate = putGate)
        vm.select("SELECT", "new")
        eventually("Selection PUT reached fixture") { requests.isNotEmpty() }
        vm.viewModelScope.cancel()
        putGate.countDown()
        awaitSelection()
        assertEquals("old", vm.state.groups.single().now)
        assertEquals(1, requests.size)
        assertTrue(messages.isEmpty())
    }

    @Test fun stoppedStateRejectsStaleSelectionCallback() {
        selectionResponses()
        fixtureState(vm.state.copy(running = false))
        vm.select("SELECT", "new")
        assertTrue("Stopped callback must not become pending", vm.pendingSelection.isEmpty())
        assertEquals(0, requests.size)
    }

    @Test fun selectionCompletionAfterStopDoesNotRestartReadbackOrRestoreRunningState() {
        val putGate = gate()
        selectionResponses(putGate = putGate)
        vm.select("SELECT", "new")
        eventually("Selection PUT reached fixture") { requests.isNotEmpty() }
        fixtureState(vm.state.copy(running = false))
        putGate.countDown()
        awaitSelection()
        assertFalse(vm.state.running)
        assertEquals("old", vm.state.groups.single().now)
        assertEquals(1, requests.size)
    }

    @Test fun removedGroupAndAlreadySelectedNodeDoNotSendNewRequests() {
        selectionResponses()
        vm.select("SELECT", "old")
        assertTrue("Already selected callback must not become pending", vm.pendingSelection.isEmpty())
        fixtureState(vm.state.copy(groups = emptyList()))
        vm.select("SELECT", "new")
        assertTrue("Removed group callback must not become pending", vm.pendingSelection.isEmpty())
        assertEquals(0, requests.size)
    }
    @Test fun stopDuringReadbackDoesNotRestoreStaleRunningSnapshot() {
        val readGate = gate()
        selectionResponses(readGate = readGate)
        vm.select("SELECT", "new")
        eventually("Selection readback reached fixture") { requestSnapshot().any { it.path == "/proxies" } }
        stoppedFixture()
        readGate.countDown()
        awaitSelection()
        assertStoppedSnapshot()
        assertFalse("Stopped state must survive a late running snapshot", vm.state.running)
        assertEquals("old", vm.state.groups.single().now)
        assertTrue(messages.isEmpty())
        val fresh = kotlinx.coroutines.runBlocking { vm.refreshNow() }
        assertTrue("A subsequent fresh read can observe a real running core", fresh?.running == true)
        assertTrue(vm.state.running)
        assertEquals("new", vm.state.groups.single().now)
    }

    @Suppress("UNCHECKED_CAST")
    @Test fun stopOperationDuringReadbackCannotApplyOrConfirmSelection() {
        val readGate = gate()
        selectionResponses(readGate = readGate)
        vm.select("SELECT", "new")
        eventually("Selection readback reached fixture") { requestSnapshot().any { it.path == "/proxies" } }
        val field = HetuViewModel::class.java.getDeclaredField("operation\$delegate").apply { isAccessible = true }
        (field.get(vm) as MutableState<HxRunOp?>).value = HxRunOp.Stop
        readGate.countDown()
        awaitSelection()
        assertEquals("old", vm.state.groups.single().now)
        assertTrue(messages.isEmpty())
    }

    @Test fun unreadableControllerCannotConfirmUsingCachedSelection() {
        val readGate = gate()
        selectionResponses(readGate = readGate, readCode = 500, connectionCode = 500)
        vm.select("SELECT", "new")
        eventually("Selection readback reached fixture") { requestSnapshot().any { it.path == "/proxies" } }
        fixtureState(vm.state.copy(groups = listOf(group.copy(now = "new"))))
        readGate.countDown()
        awaitSelection()
        assertEquals("Cached display remains available", "new", vm.state.groups.single().now)
        assertTrue("A superseded response must not replace newer visible state", vm.state.panelReady)
        assertTrue("Cached group is not a fresh acknowledgement", messages.any { it.contains("核心尚未确认") })
    }

    @Test fun removedNodeDoesNotSendNewSelectionRequest() {
        selectionResponses()
        fixtureState(vm.state.copy(groups = listOf(group.copy(nodes = listOf(ProxyNodeUi("old"))))))
        vm.select("SELECT", "new")
        assertTrue(vm.pendingSelection.isEmpty())
        assertEquals(0, requests.size)
    }

    @Test fun stopDuringRuntimeSampleCannotWriteLateRuntimeOrPersistedRunning() {
        PanelActionRuntimeShadows.RuntimeSample.pause = true
        selectionResponses()
        vm.select("SELECT", "new")
        eventually("Runtime sample suspended") { PanelActionRuntimeShadows.RuntimeSample.pending != null }
        stoppedFixture()
        PanelActionRuntimeShadows.RuntimeSample.release()
        awaitSelection()
        assertStoppedSnapshot()
        assertTrue(messages.isEmpty())
    }

    @Test fun stopDuringProviderReadCannotWriteLateProvidersOrPersistedRunning() {
        val providerGate = gate()
        selectionResponses(providerGate = providerGate)
        vm.select("SELECT", "new")
        eventually("Fresh provider read started") { requestSnapshot().count { it.path == "/providers/proxies" } == 2 }
        stoppedFixture()
        providerGate.countDown()
        awaitSelection()
        assertStoppedSnapshot()
        assertTrue(messages.isEmpty())
    }

    @Test fun stopDuringVersionReadCannotWriteLateVersionOrPersistedRunning() {
        val versionGate = gate()
        selectionResponses(versionGate = versionGate)
        vm.select("SELECT", "new")
        eventually("Version read started") { requestSnapshot().any { it.path == "/version" } }
        stoppedFixture()
        versionGate.countDown()
        awaitSelection()
        assertStoppedSnapshot()
        assertTrue(messages.isEmpty())
    }

    @Test fun stopDuringHistorySuspensionCannotPersistOldRunningSnapshot() {
        PanelActionRuntimeShadows.HistoryRecord.pause = true
        selectionResponses()
        vm.select("SELECT", "new")
        eventually("History record suspended") { PanelActionRuntimeShadows.HistoryRecord.pending != null }
        stoppedFixture()
        PanelActionRuntimeShadows.HistoryRecord.release()
        awaitSelection()
        assertStoppedSnapshot()
        assertTrue(messages.isEmpty())
    }


    private fun automaticProbe(type: String, malformedNew: Boolean = false): String {
        val automatic = group.copy(type = type)
        fixtureState(vm.state.copy(groups = listOf(automatic)))
        val fixed = java.util.concurrent.atomic.AtomicReference("old")
        response = { request ->
            val path = request.requestUrl!!.encodedPath
            when {
                path == "/proxies" -> {
                    val snapshot = JSONObject(selectionSnapshot("old"))
                    snapshot.getJSONObject("proxies").getJSONObject("SELECT")
                        .put("type", type).put("fixed", fixed.get())
                    body(snapshot.toString())
                }
                path == "/providers/proxies" -> body("""{"providers":{}}""")
                path == "/group/SELECT/delay" -> {
                    // Upstream ab405bad groups.go explicitly clears non-Selector fixed selection.
                    fixed.set("")
                    body("""{"old":31,"new":42,"untested":53}""")
                }
                path == "/proxies/old/delay" -> body("""{"delay":31}""")
                path == "/proxies/new/delay" -> body(if (malformedNew) "{}" else """{"delay":42}""")
                path == "/proxies/untested/delay" -> body("""{"delay":53}""")
                else -> MockResponse().setResponseCode(404)
            }
        }
        vm.testGroup(automatic)
        eventually("Automatic group probe completes") { vm.testingGroups.isEmpty() }
        return fixed.get()
    }
    @Test fun urlTestGroupProbeNeverCallsTheUnfixingGroupEndpoint() {
        assertEquals("old", automaticProbe("URLTest"))
        assertFalse(requestSnapshot().any { it.requestUrl!!.encodedPath.startsWith("/group/") })
        assertEquals(31L, vm.delays["old"])
        assertEquals(42L, vm.delays["new"])
    }
    @Test fun fallbackGroupProbeNeverCallsTheUnfixingGroupEndpoint() {
        assertEquals("old", automaticProbe("Fallback"))
        assertFalse(requestSnapshot().any { it.requestUrl!!.encodedPath.startsWith("/group/") })
        assertEquals("old", vm.state.groups.single().now)
    }
    @Test fun automaticGroupMalformedNodeResultRetainsItsEarlierMeasurement() {
        automaticProbe("URLTest", malformedNew = true)
        assertEquals(65L, vm.delays["new"])
        assertEquals(31L, vm.delays["old"])
        assertEquals(53L, vm.delays["untested"])
        assertTrue(vm.testingNodes.isEmpty())
    }
}
