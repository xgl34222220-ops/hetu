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
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Actual launcher operations and HTTP responses; no user configuration or Root mutation. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, shadows = [
    ControllerRunState90Shadows.RootStatus::class,
    PanelActionRuntimeShadows.RuntimeSample::class,
    PanelActionRuntimeShadows.HistoryRecord::class,
    PanelActionRuntimeShadows.NoRoot::class,
    PanelRequestOwnershipShadows.Sites::class,
])
@LooperMode(LooperMode.Mode.PAUSED)
class RuleRequestOwnership90Test {
    private lateinit var vm: HetuViewModel
    private lateinit var server: MockWebServer
    private val servers = mutableListOf<MockWebServer>()
    private val gates = mutableListOf<CountDownLatch>()
    private val requests = Collections.synchronizedList(mutableListOf<RecordedRequest>())
    @Volatile private var response: (RecordedRequest) -> MockResponse = { normal(it) }

    @Before fun prepare() {
        PanelActionRuntimeShadows.NoRoot.reset()
        ControllerRunState90Shadows.RootStatus.reset()
        ControllerRunState90Shadows.RootStatus.reply = "{\"running\":true,\"dataPlaneHealthy\":true,\"pid\":77}"
        PanelActionRuntimeShadows.RuntimeSample.reset()
        PanelActionRuntimeShadows.HistoryRecord.reset()
        PanelRequestOwnershipShadows.Sites.reset()
        server = newServer { response(it) }
        val app: Application = ApplicationProvider.getApplicationContext()
        app.getSharedPreferences("hetu", 0).edit().clear()
            .putBoolean("proxyCustomApiEnabled", true).putString("proxyCustomApiHost", "127.0.0.1")
            .putInt("proxyCustomApiPort", server.port).putBoolean("proxyApiHistoryEnabled", false)
            .putBoolean("proxyRootWanted", true).putBoolean("proxyRootRuntimeRunning", true).commit()
        vm = HetuViewModel(app)
        fixture("state", ProxyComposeState(running = true, panelReady = true, corePid = 77))
    }

    @After fun finish() {
        vm.viewModelScope.cancel()
        gates.forEach { it.countDown() }
        PanelActionRuntimeShadows.RuntimeSample.release()
        PanelActionRuntimeShadows.HistoryRecord.release()
        PanelRequestOwnershipShadows.Sites.releaseAll()
        awaitActions()
        servers.forEach { it.shutdown() }
        assertEquals(0, PanelActionRuntimeShadows.NoRoot.calls)
    }

    private fun newServer(handler: (RecordedRequest) -> MockResponse) = MockWebServer().apply {
        dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                return handler(request)
            }
        }
        start(); servers += this
    }
    private fun gate() = CountDownLatch(1).also { gates += it }
    private fun CountDownLatch.hold() { check(await(8, TimeUnit.SECONDS)) { "Rule response was not released" } }
    private fun body(value: String) = MockResponse().setBody(value)
    private fun rules(value: String) = "{\"rules\":[{\"type\":\"Domain\",\"payload\":\"$value\",\"proxy\":\"DIRECT\"}]}"
    private fun sets(value: String, names: List<String> = listOf("R")) = "{\"providers\":{" +
        names.joinToString(",") { "\"$it\":{\"vehicleType\":\"HTTP\",\"updatedAt\":\"$value\",\"ruleCount\":1}" } + "}}"
    private fun providers(value: String) = "{\"providers\":{\"P\":{\"vehicleType\":\"HTTP\",\"updatedAt\":\"$value\",\"proxies\":[\"node\"]}}}"
    private fun ruleSet(value: String, name: String = "R") = DashboardRuleSetUi(name, "classical", "yaml", "HTTP", 1, value)
    private fun provider(value: String) = DashboardProviderUi("P", "HTTP", "", "", value, 0L, 0L, 0L, 0L, setOf("node"), false)
    private fun normal(request: RecordedRequest): MockResponse = when (request.requestUrl!!.encodedPath) {
        "/rules" -> body(rules("fresh"))
        "/providers/rules" -> body(sets("fresh"))
        "/providers/proxies" -> body(providers("fresh"))
        "/configs" -> body("{\"mode\":\"rule\"}")
        "/proxies" -> body("{\"proxies\":{}}")
        "/connections" -> body("{\"connections\":[]}")
        "/version" -> body("{\"version\":\"fixture\"}")
        else -> if (request.method == "PUT") MockResponse().setResponseCode(204) else MockResponse().setResponseCode(404)
    }
    private fun paths() = synchronized(requests) { requests.map { "${it.method} ${it.requestUrl!!.encodedPath}" } }
    private fun eventually(reason: String, predicate: () -> Boolean) {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(6)
        while (System.nanoTime() < end) {
            shadowOf(Looper.getMainLooper()).idle()
            if (predicate()) return
            Thread.sleep(10)
        }
        assertTrue("$reason; requests=${paths()}", predicate())
    }
    private fun awaitActions() = eventually("All operations complete") {
        vm.viewModelScope.coroutineContext[Job]!!.children.none { it.isActive }
    }
    @Suppress("UNCHECKED_CAST")
    private fun fixture(name: String, value: Any?) {
        val field = HetuViewModel::class.java.getDeclaredField(name + "\$delegate").apply { isAccessible = true }
        (field.get(vm) as MutableState<Any?>).value = value
    }
    private fun changeApi(port: Int) {
        vm.prefs.edit().putInt("proxyCustomApiPort", port)
            .putString("proxyCustomApiSecret", "fixture-${System.nanoTime()}").commit()
        vm.bumpSettings()
    }
    private fun load(ruleSets: Boolean) { if (ruleSets) vm.loadRuleSets() else vm.loadRules() }
    private fun loading(ruleSets: Boolean) = if (ruleSets) vm.ruleSetsLoading else vm.rulesLoading
    private fun value(ruleSets: Boolean) = if (ruleSets) vm.ruleSets.singleOrNull()?.updatedAt else vm.rules.singleOrNull()?.payload

    @Test fun stoppingDropsLateRuleAndRuleSetReads() {
        for (ruleSets in listOf(false, true)) {
            val entered = CountDownLatch(1); val old = gate()
            val path = if (ruleSets) "/providers/rules" else "/rules"
            response = { req -> if (req.requestUrl!!.encodedPath == path) {
                entered.countDown(); old.hold(); body(if (ruleSets) sets("late") else rules("late"))
            } else normal(req) }
            fixture("state", ProxyComposeState(running = true, panelReady = true))
            load(ruleSets)
            eventually("Read enters old runtime") { entered.count == 0L }
            fixture("state", vm.state.copy(running = false))
            old.countDown(); awaitActions()
            assertNull(value(ruleSets))
            assertFalse(loading(ruleSets))
        }
    }

    @Test fun replacementApiCanReadImmediatelyAndOldFinallyKeepsTheNewReadBusy() {
        for (ruleSets in listOf(false, true)) {
            val entered = CountDownLatch(1); val old = gate(); val fresh = gate()
            val newEntered = CountDownLatch(1)
            val path = if (ruleSets) "/providers/rules" else "/rules"
            changeApi(server.port)
            response = { req -> if (req.requestUrl!!.encodedPath == path) {
                entered.countDown(); old.hold(); body(if (ruleSets) sets("old") else rules("old"))
            } else normal(req) }
            load(ruleSets)
            eventually("Old read enters") { entered.count == 0L }
            val next = newServer { req -> if (req.requestUrl!!.encodedPath == path) {
                newEntered.countDown(); fresh.hold(); body(if (ruleSets) sets("new") else rules("new"))
            } else normal(req) }
            changeApi(next.port); load(ruleSets)
            eventually("Replacement read enters before the old read finishes") { newEntered.count == 0L }
            old.countDown()
            eventually("Only replacement read remains") { vm.viewModelScope.coroutineContext[Job]!!.children.count { it.isActive } == 1 }
            assertTrue(loading(ruleSets))
            assertNotEquals("old", value(ruleSets))
            fresh.countDown(); awaitActions()
            assertEquals("new", value(ruleSets))
            assertFalse(loading(ruleSets))
        }
    }

    @Test fun singleRuleSetUpdateCannotPublishAfterStop() {
        val entered = CountDownLatch(1); val old = gate()
        fixture("ruleSets", listOf(ruleSet("before")))
        response = { req -> if (req.method == "PUT") { entered.countDown(); old.hold(); MockResponse().setResponseCode(204) } else normal(req) }
        vm.updateRuleSet("R")
        eventually("Update enters") { entered.count == 0L }
        fixture("state", vm.state.copy(running = false))
        old.countDown(); awaitActions()
        assertEquals("before", vm.ruleSets.single().updatedAt)
        assertFalse(vm.ruleSetTasks["R"]?.running == true)
        assertNotEquals(true, vm.ruleSetTasks["R"]?.ok)
    }

    @Test fun oldRuleSetUpdateCannotClearTheReplacementTaskBusyState() {
        val entered = CountDownLatch(1); val old = gate(); val fresh = gate(); val newEntered = CountDownLatch(1)
        fixture("ruleSets", listOf(ruleSet("before")))
        response = { req -> if (req.method == "PUT") { entered.countDown(); old.hold(); MockResponse().setResponseCode(204) } else normal(req) }
        vm.updateRuleSet("R")
        eventually("Old update enters") { entered.count == 0L }
        val next = newServer { req -> if (req.method == "PUT") {
            newEntered.countDown(); fresh.hold(); MockResponse().setResponseCode(204)
        } else normal(req) }
        changeApi(next.port); vm.updateRuleSet("R")
        eventually("Replacement update enters") { newEntered.count == 0L }
        old.countDown()
        eventually("Old task finishes") { vm.viewModelScope.coroutineContext[Job]!!.children.count { it.isActive } == 1 }
        assertEquals(true, vm.ruleSetTasks["R"]?.running)
        assertTrue("Replacement API cannot display the old API's list", vm.ruleSets.isEmpty())
        fresh.countDown(); awaitActions()
        assertEquals("fresh", vm.ruleSets.single().updatedAt)
        assertEquals(true, vm.ruleSetTasks["R"]?.ok)
    }

    @Test fun bulkRuleSetUpdateStopsBeforeTheNextChunkAndDropsLateReadbacks() {
        val old = gate(); val writes = AtomicInteger()
        val names = listOf("R1", "R2", "R3", "R4")
        fixture("ruleSets", names.map { ruleSet("before", it) })
        response = { req -> when {
            req.method == "PUT" -> { writes.incrementAndGet(); old.hold(); MockResponse().setResponseCode(204) }
            req.requestUrl!!.encodedPath == "/providers/rules" -> body(sets("late", names))
            else -> normal(req)
        } }
        vm.updateAllRuleSets()
        eventually("First bounded chunk enters") { writes.get() == 3 }
        fixture("state", vm.state.copy(running = false))
        fixture("ruleSets", listOf(ruleSet("replacement")))
        old.countDown(); awaitActions()
        assertEquals(3, writes.get())
        assertEquals("replacement", vm.ruleSets.single().updatedAt)
        assertFalse(vm.ruleSetsUpdatingAll)
        assertTrue(vm.ruleSetTasks.values.none { it.running || it.ok == true })
    }

    @Test fun acknowledgedRuleAndProviderUpdatesSupersedeOlderWholeListReads() {
        for (isProvider in listOf(false, true)) {
            changeApi(server.port)
            fixture("state", ProxyComposeState(running = true, panelReady = true))
            if (isProvider) fixture("providers", listOf(provider("before")))
            else fixture("ruleSets", listOf(ruleSet("before")))
            val old = gate(); val reads = AtomicInteger()
            val path = if (isProvider) "/providers/proxies" else "/providers/rules"
            response = { req -> if (req.method == "GET" && req.requestUrl!!.encodedPath == path && reads.incrementAndGet() == 1) {
                old.hold(); body(if (isProvider) providers("old") else sets("old"))
            } else normal(req) }
            if (isProvider) vm.loadProviders() else vm.loadRuleSets()
            eventually("Old list enters") { reads.get() == 1 }
            if (isProvider) vm.updateProvider("P") else vm.updateRuleSet("R")
            eventually("Acknowledged update applies") {
                if (isProvider) vm.providerTasks["P"]?.ok == true else vm.ruleSetTasks["R"]?.ok == true
            }
            old.countDown(); awaitActions()
            assertEquals("fresh", if (isProvider) vm.providers.single().updatedAt else vm.ruleSets.single().updatedAt)
        }
    }

    @Test fun bulkFinalReadRetiresTheSupersededReadBusyFlagWithoutClearingANewerOwner() {
        val old = gate(); val fresh = gate(); val reads = AtomicInteger()
        fixture("ruleSets", listOf(ruleSet("before")))
        response = { req -> when {
            req.method == "PUT" -> MockResponse().setResponseCode(401)
            req.requestUrl!!.encodedPath == "/providers/rules" -> when (reads.incrementAndGet()) {
                1 -> { old.hold(); body(sets("old")) }
                4 -> { fresh.hold(); body(sets("new")) }
                else -> body(sets("after-bulk"))
            }
            else -> normal(req)
        } }
        vm.loadRuleSets()
        eventually("Ordinary read enters") { reads.get() == 1 }
        vm.updateAllRuleSets()
        eventually("Bulk failed PUT and final read finish") { !vm.ruleSetsUpdatingAll && reads.get() == 3 }
        assertEquals(false, vm.ruleSetTasks["R"]?.ok)
        assertFalse("Superseded ordinary read must not leave loading stuck", vm.ruleSetsLoading)
        vm.loadRuleSets()
        eventually("New ordinary read starts immediately") { reads.get() == 4 }
        old.countDown()
        eventually("Old ordinary read finishes") { vm.viewModelScope.coroutineContext[Job]!!.children.count { it.isActive } == 1 }
        assertTrue("Old finally cannot clear the new ordinary owner", vm.ruleSetsLoading)
        assertEquals("after-bulk", vm.ruleSets.single().updatedAt)
        fresh.countDown(); awaitActions()
        assertFalse(vm.ruleSetsLoading)
        assertEquals("new", vm.ruleSets.single().updatedAt)
    }

    @Test fun physicalEpochAndReplacementSessionRejectOldSelectorResultsAndPreserveTheNewOwner() {
        for (replaceSession in listOf(false, true)) {
            vm.prefs.edit().putString("proxyNetworkSessionId", "session-a").putLong("proxyNetworkEpoch", 17L).commit()
            vm.bumpSettings()
            val old = gate(); val fresh = gate(); val reads = AtomicInteger()
            val group = ProxyGroupUi("R", "Selector", "shared", listOf(ProxyNodeUi("shared")))
            fixture("rules", listOf(ProxyRuleUi(0, "Domain", "retained", "DIRECT")))
            fixture("ruleSets", listOf(ruleSet("retained")))
            vm.delays["shared"] = 44L
            response = { req -> if (req.requestUrl!!.encodedPath == "/group/R/delay") {
                if (reads.incrementAndGet() == 1) { old.hold(); body("{\"shared\":999}") }
                else { fresh.hold(); body("{\"shared\":21}") }
            } else normal(req) }
            vm.testGroup(group)
            eventually("Old physical route probe enters") { reads.get() == 1 }
            val contentRevision = vm.contentRevision
            if (replaceSession) vm.prefs.edit().putString("proxyNetworkSessionId", "session-b")
                .putLong("proxyNetworkEpoch", 0L).commit()
            else vm.prefs.edit().putLong("proxyNetworkEpoch", 18L).commit()
            // No UI callback is required for the service's persisted route change.
            vm.testGroup(group)
            eventually("New route can probe immediately") { reads.get() == 2 }
            assertEquals("retained", vm.rules.single().payload)
            assertEquals("retained", vm.ruleSets.single().updatedAt)
            assertEquals("Physical handovers retain the current config's content identity", contentRevision, vm.contentRevision)
            old.countDown()
            eventually("Old physical-route probe finishes") { vm.viewModelScope.coroutineContext[Job]!!.children.count { it.isActive } == 1 }
            assertEquals(44L, vm.delays["shared"])
            assertEquals(true, vm.testingGroups["R"])
            assertEquals(true, vm.testingNodes["shared"])
            fresh.countDown(); awaitActions()
            assertEquals(21L, vm.delays["shared"])
            assertTrue(vm.testingGroups.isEmpty())
            assertTrue(vm.testingNodes.isEmpty())
        }
    }

    @Test fun physicalEpochAndReplacementSessionRejectOldWebsitePersistenceAndKeepTheNewProbeBusy() {
        PanelRequestOwnershipShadows.Sites.pause = true
        for (replaceSession in listOf(false, true)) {
            vm.prefs.edit().putString("proxyNetworkSessionId", "session-a").putLong("proxyNetworkEpoch", 17L).commit()
            vm.bumpSettings()
            val before = vm.siteDelays.toMap()
            vm.measureSites()
            eventually("Old route website probe enters") { PanelRequestOwnershipShadows.Sites.pendingCount() == 1 }
            if (replaceSession) vm.prefs.edit().putString("proxyNetworkSessionId", "session-b")
                .putLong("proxyNetworkEpoch", 0L).commit()
            else vm.prefs.edit().putLong("proxyNetworkEpoch", 18L).commit()
            vm.measureSites()
            eventually("Replacement route website probe enters") { PanelRequestOwnershipShadows.Sites.pendingCount() == 2 }
            PanelRequestOwnershipShadows.Sites.releaseFirst(mapOf("Baidu" to 999L))
            eventually("Old website probe finishes") { vm.viewModelScope.coroutineContext[Job]!!.children.count { it.isActive } == 1 }
            assertTrue(vm.siteTesting)
            assertEquals(before, vm.siteDelays)
            assertEquals(before, ProxyLatencyTargets.lastResults(vm.prefs))
            PanelRequestOwnershipShadows.Sites.releaseFirst(mapOf("Baidu" to 21L))
            awaitActions()
            assertFalse(vm.siteTesting)
            assertEquals(21L, vm.siteDelays["Baidu"])
            assertEquals(21L, ProxyLatencyTargets.lastResults(vm.prefs)["Baidu"])
        }
    }

    @Test fun replacementApiClearsOldContentAndBulkMutationUsesOnlyTheCurrentControllerTargets() {
        fixture("rules", listOf(ProxyRuleUi(0, "Domain", "A-only", "DIRECT")))
        fixture("ruleSets", listOf(ruleSet("A-content", "A-only")))
        val wrongWrites = AtomicInteger()
        val validWrites = AtomicInteger()
        val next = newServer { req -> when {
            req.method == "PUT" && req.requestUrl!!.encodedPath == "/providers/rules/A-only" -> {
                wrongWrites.incrementAndGet(); MockResponse().setResponseCode(404)
            }
            req.method == "PUT" && req.requestUrl!!.encodedPath == "/providers/rules/B-only" -> {
                validWrites.incrementAndGet(); MockResponse().setResponseCode(204)
            }
            req.requestUrl!!.encodedPath == "/providers/rules" -> body(sets("B-content", listOf("B-only")))
            else -> normal(req)
        } }
        val contentRevision = vm.contentRevision
        changeApi(next.port)
        assertTrue(vm.contentRevision > contentRevision)
        assertTrue(vm.rules.isEmpty())
        assertTrue(vm.ruleSets.isEmpty())
        vm.updateAllRuleSets(); awaitActions()
        assertEquals("Old controller targets must never be sent to the replacement API", 0, wrongWrites.get())
        assertEquals(1, validWrites.get())
        assertEquals("B-only", vm.ruleSets.single().name)
        assertEquals(true, vm.ruleSetTasks["B-only"]?.ok)
        assertFalse(vm.ruleSetsUpdatingAll)
    }

    @Test fun confirmedPidChangeAfterAThrottledPollRejectsThePreviousRuntimeWave() {
        ShadowSystemClock.advanceBy(Duration.ofSeconds(1))
        var refreshed = false
        vm.viewModelScope.launch { vm.refreshNow(); refreshed = true }
        eventually("PID 77 is confirmed") { refreshed && vm.state.corePid == 77 }
        assertEquals(1, ControllerRunState90Shadows.RootStatus.reads)
        val old = gate(); val entered = CountDownLatch(1)
        response = { req -> if (req.requestUrl!!.encodedPath == "/group/R/delay") {
            entered.countDown(); old.hold(); body("{\"shared\":999}")
        } else normal(req) }
        vm.delays["shared"] = 44L
        vm.testGroup(ProxyGroupUi("R", "Selector", "shared", listOf(ProxyNodeUi("shared"))))
        eventually("Old PID wave enters") { entered.count == 0L }
        ControllerRunState90Shadows.RootStatus.reply = "{\"running\":true,\"dataPlaneHealthy\":true,\"pid\":88}"
        ShadowSystemClock.advanceBy(Duration.ofSeconds(2))
        refreshed = false
        vm.viewModelScope.launch { vm.refreshNow(); refreshed = true }
        eventually("Throttled poll finishes") { refreshed }
        assertEquals("Cached poll must not erase the last confirmed process", 77, vm.state.corePid)
        assertEquals(1, ControllerRunState90Shadows.RootStatus.reads)
        prefsForceFreshHealth()
        PanelActionRuntimeShadows.RuntimeSample.value = ProxyRuntimeSnapshot(running = true, pid = 88)
        refreshed = false
        vm.viewModelScope.launch { vm.refreshNow(); refreshed = true }
        eventually("Replacement PID is confirmed") { refreshed && vm.state.corePid == 88 }
        assertEquals(2, ControllerRunState90Shadows.RootStatus.reads)
        old.countDown(); awaitActions()
        assertEquals(44L, vm.delays["shared"])
        assertTrue(vm.testingGroups.isEmpty())
        assertTrue(vm.testingNodes.isEmpty())
    }

    private fun prefsForceFreshHealth() { vm.prefs.edit().putLong("proxyRootHealthProbeElapsed", 0L).commit() }

    @Test fun foregroundReattachesOncePerForegroundAndReattachesAfterReturningFromBackground() {
        val app: Application = ApplicationProvider.getApplicationContext()
        while (shadowOf(app).nextStartedService != null) { }
        vm.onForeground()
        val first = shadowOf(app).nextStartedService
        assertNotNull(first)
        assertEquals(ProxyNetworkMatchService::class.java.name, first!!.component!!.className)
        assertNull(first.action)
        vm.onForeground()
        assertNull("Duplicate callback in the same foreground must not request another observer", shadowOf(app).nextStartedService)
        vm.onBackground()
        vm.onForeground()
        val next = shadowOf(app).nextStartedService
        assertNotNull(next)
        assertEquals(ProxyNetworkMatchService::class.java.name, next!!.component!!.className)
        assertNull(next.action)
        assertTrue(vm.prefs.getBoolean("proxyRootWanted", false))
        vm.onBackground(); awaitActions()
        assertEquals(0, PanelActionRuntimeShadows.NoRoot.calls)
    }
}
