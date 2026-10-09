package io.github.xgl34222220.hetu

import android.app.Application
import android.os.Looper
import androidx.compose.runtime.MutableState
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import io.github.xgl34222220.hetu.panel.PanelData
import io.github.xgl34222220.hetu.panel.PanelDataProjector
import io.github.xgl34222220.hetu.panel.PanelDelay
import io.github.xgl34222220.hetu.panel.PanelGroup
import io.github.xgl34222220.hetu.panel.PanelNode
import io.github.xgl34222220.hetu.panel.PanelTrafficSnapshot
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
import java.io.File
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** 策略页加载与测速：只标记被测组、自动组逐个落结果、冷启动先画上次完整策略组（只读）。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, shadows = [
    PanelRequestOwnershipShadows.RootStatus::class,
    PanelRequestOwnershipShadows.Sites::class,
    PanelActionRuntimeShadows.RuntimeSample::class,
    PanelActionRuntimeShadows.HistoryRecord::class,
    PanelActionRuntimeShadows.NoRoot::class,
])
@LooperMode(LooperMode.Mode.PAUSED)
class StrategyLoadSpeed96Test {
    private lateinit var app: Application
    private lateinit var vm: HetuViewModel
    private lateinit var server: MockWebServer
    private lateinit var collector: CoroutineScope
    private val extraVms = mutableListOf<HetuViewModel>()
    private val gates = mutableListOf<CountDownLatch>()
    private val messages = Collections.synchronizedList(mutableListOf<String>())
    private val requests = Collections.synchronizedList(mutableListOf<RecordedRequest>())
    private val route = ProxyGroupUi("Route", "Selector", "node", listOf(ProxyNodeUi("node"), ProxyNodeUi("other")))
    private val region = ProxyGroupUi("Region", "Selector", "node", listOf(ProxyNodeUi("node")))
    @Volatile private var response: (RecordedRequest) -> MockResponse = ::normalResponse

    @Before fun prepare() {
        PanelRequestOwnershipShadows.RootStatus.reset()
        PanelRequestOwnershipShadows.Sites.reset()
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse { requests += request; return response(request) }
            }
            start()
        }
        app = ApplicationProvider.getApplicationContext()
        app.getSharedPreferences("hetu", 0).edit().clear()
            .putBoolean("proxyCustomApiEnabled", true).putString("proxyCustomApiHost", "127.0.0.1")
            .putInt("proxyCustomApiPort", server.port)
            .putBoolean("proxyRootWanted", true).putBoolean("proxyRootRuntimeRunning", true)
            .putBoolean("proxyApiHistoryEnabled", false).commit()
        PanelActionRuntimeShadows.RuntimeSample.value = ProxyRuntimeSnapshot(running = true, pid = 77)
        vm = HetuViewModel(app)
        setState(vm, ProxyComposeState(running = true, panelReady = true, corePid = 77, groups = listOf(route, region)))
        vm.delays["node"] = 44L
        collector = CoroutineScope(Dispatchers.Main.immediate)
        collector.launch { vm.messages.collect { messages += it } }
        shadowOf(Looper.getMainLooper()).idle()
    }

    @After fun finish() {
        (extraVms + vm).forEach { it.viewModelScope.cancel() }
        gates.forEach { it.countDown() }
        PanelRequestOwnershipShadows.Sites.releaseAll()
        PanelActionRuntimeShadows.RuntimeSample.release()
        PanelActionRuntimeShadows.HistoryRecord.release()
        eventually("ViewModel requests completed") {
            (extraVms + vm).all { model -> model.viewModelScope.coroutineContext[Job]!!.children.all { it.isCompleted } }
        }
        collector.cancel()
        server.shutdown()
        assertEquals("Fixtures must not execute Root actions", 0, PanelActionRuntimeShadows.NoRoot.calls)
    }

    @Suppress("UNCHECKED_CAST")
    private fun setState(model: HetuViewModel, value: ProxyComposeState) {
        (HetuViewModel::class.java.getDeclaredField("state\$delegate").apply { isAccessible = true }
            .get(model) as MutableState<ProxyComposeState>).value = value
    }
    private fun gate() = CountDownLatch(1).also { gates += it }
    private fun CountDownLatch.hold() { check(await(8, TimeUnit.SECONDS)) { "HTTP fixture gate not released" } }
    private fun body(value: String) = MockResponse().setBody(value)
    private fun paths() = synchronized(requests) { requests.map { "${it.method} ${it.requestUrl!!.encodedPath}" } }
    private fun eventually(reason: String, predicate: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(6)
        while (System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (predicate()) return
            Thread.sleep(10)
        }
        assertTrue("$reason; requests=${paths()}, busy=${vm.testingNodes}", predicate())
    }
    private fun normalResponse(req: RecordedRequest): MockResponse = when (req.requestUrl!!.encodedPath) {
        "/proxies" -> body("""{"proxies":{"Route":{"type":"Selector","now":"node","all":["node","other"]},"Region":{"type":"Selector","now":"node","all":["node"]},"node":{"type":"Shadowsocks"},"other":{"type":"Shadowsocks"}}}""")
        "/providers/proxies" -> body("""{"providers":{}}""")
        "/connections" -> body("""{"connections":[]}""")
        "/configs" -> body("""{"mode":"rule"}""")
        "/version" -> body("""{"version":"fixture"}""")
        "/group/Route/delay" -> body("""{"node":31,"other":42}""")
        "/proxies/node/delay" -> body("""{"delay":22}""")
        "/proxies/other/delay" -> body("""{"delay":66}""")
        else -> MockResponse().setResponseCode(404)
    }

    @Test fun groupCardKeepsItsLastReadingWhileAnotherGroupProbesItsCurrentNode() {
        val data = PanelData(groups = listOf(PanelGroup("Region", "Selector", listOf(PanelNode("node")), "node")),
            delays = mapOf("node" to PanelDelay.Testing), settledDelays = mapOf("node" to PanelDelay.Ms(52L)))
        assertEquals("Node rows still show the in-flight probe", PanelDelay.Testing, data.delayOf("node"))
        assertEquals(PanelDelay.Ms(52L), data.cardDelayOf("node"))
        assertEquals(PanelDelay.Unknown, data.copy(settledDelays = emptyMap()).cardDelayOf("node"))
        assertNull(data.cardDelayOf("DIRECT"))
    }

    @Test fun projectorKeepsTheSettledReadingBesideTheTestingMarker() {
        val data = PanelDataProjector().project(
            ProxyComposeState(running = true, panelReady = true, groups = listOf(route, region)), false,
            emptyList(), emptyList(), emptyList(), emptyList(), mapOf("node" to 52L), mapOf("node" to true),
            emptyMap(), emptyMap(), emptyMap(), PanelTrafficSnapshot(), testingGroups = setOf("Route"))
        assertEquals(PanelDelay.Testing, data.delays["node"])
        assertEquals(PanelDelay.Ms(52L), data.settledDelays["node"])
        assertEquals(setOf("Route"), data.testingGroups)
        assertEquals("Region card is not being measured", PanelDelay.Ms(52L), data.cardDelayOf(data.groups.first { it.name == "Region" }.now))
    }

    @Test fun selectorGroupProbeMarksOnlyItsOwnGroupAndUsesOneCoreRequest() {
        val held = gate()
        response = { req -> if (req.requestUrl!!.encodedPath == "/group/Route/delay") { held.hold(); normalResponse(req) } else normalResponse(req) }
        vm.testGroup(route)
        eventually("Group probe reached the controller") { paths().contains("GET /group/Route/delay") }
        assertEquals(true, vm.testingGroups["Route"])
        assertNull("A different card must not become 测速中", vm.testingGroups["Region"])
        assertFalse(vm.testingAll)
        held.countDown()
        eventually("Group probe finished") { vm.testingGroups.isEmpty() && vm.testingNodes.isEmpty() }
        assertEquals(31L, vm.delays["node"])
        assertEquals(42L, vm.delays["other"])
        assertEquals(1, paths().count { it.startsWith("GET /group/") })
        assertFalse(paths().any { it.startsWith("GET /proxies/") && it.endsWith("/delay") })
    }

    @Test fun automaticGroupLeafResultLandsBeforeItsSlowestSibling() {
        val held = gate()
        response = { req -> if (req.requestUrl!!.encodedPath == "/proxies/other/delay") { held.hold(); normalResponse(req) } else normalResponse(req) }
        vm.testGroup(route.copy(type = "URLTest"))
        eventually("Fast leaf is shown while its sibling is still measuring") {
            vm.delays["node"] == 22L && vm.testingNodes["node"] == null
        }
        assertEquals(true, vm.testingNodes["other"])
        assertEquals(true, vm.testingGroups["Route"])
        held.countDown()
        eventually("Automatic group probe finished") { vm.testingGroups.isEmpty() && vm.testingNodes.isEmpty() }
        assertEquals(22L, vm.delays["node"])
        assertEquals(66L, vm.delays["other"])
        assertFalse("Automatic groups never call the unfixing group endpoint", paths().any { it.contains("/group/") })
    }

    @Test fun testAllShowsEachReadingAsItsNodeAnswers() {
        val held = gate()
        response = { req -> if (req.requestUrl!!.encodedPath == "/proxies/other/delay") { held.hold(); normalResponse(req) } else normalResponse(req) }
        vm.testAll()
        eventually("Fast node is shown while the whole-profile probe continues") {
            vm.delays["node"] == 22L && vm.testingNodes["node"] == null
        }
        assertTrue(vm.testingAll)
        assertEquals(true, vm.testingNodes["other"])
        held.countDown()
        eventually("Whole-profile probe finished") { !vm.testingAll && vm.testingNodes.isEmpty() }
        assertEquals(66L, vm.delays["other"])
        assertTrue(messages.any { it.startsWith("测速完成") })
    }

    @Test fun restoredStrategyCardsDrawAtOnceButCannotActUntilAFreshRead() {
        val config = "restored-config-96"
        StrategySnapshotCache.write(app, config, listOf(route.copy(nodes = route.nodes.map { it.copy(lastDelay = 80L) }), region))
        val file = File(app.noBackupFilesDir, "strategy-groups-v1.json")
        eventually("Snapshot cache was written off the main thread") { file.isFile }
        app.getSharedPreferences("hetu", 0).edit().putBoolean("proxyUiLastRunning", true)
            .putString("proxyUiLastConfig", config).putString(StrategySnapshotCache.PREF_KEY, config).commit()
        val restored = HetuViewModel(app).also { extraVms += it }
        collector.launch { restored.messages.collect { messages += it } }
        assertTrue(restored.state.running)
        assertFalse("Restored cards are never a confirmed controller snapshot", restored.state.panelReady)
        assertEquals(listOf("Route", "Region"), restored.state.groups.map { it.name })
        assertEquals(listOf("node", "other"), restored.state.groups.first().nodes.map { it.name })
        assertNull("Latency is not restored from disk", restored.state.groups.first().nodes.first().lastDelay)
        val before = paths().size
        restored.testGroup(restored.state.groups.first())
        restored.testNode("node")
        restored.testAll()
        restored.select("Route", "other")
        eventually("Refusal is reported") { messages.any { it.contains("正在读取核心最新状态") } }
        assertTrue(restored.testingGroups.isEmpty())
        assertTrue(restored.testingNodes.isEmpty())
        assertTrue(restored.pendingSelection.isEmpty())
        assertEquals("No probe or selection reaches the controller", before, paths().size)

        val mismatched = File(app.noBackupFilesDir, "strategy-groups-v1.json").readText()
        assertTrue(mismatched.contains(config))
        app.getSharedPreferences("hetu", 0).edit().putString("proxyUiLastConfig", "another-config").commit()
        val other = HetuViewModel(app).also { extraVms += it }
        assertTrue("A different configuration never shows the cached cards", other.state.groups.isEmpty())
    }
}
