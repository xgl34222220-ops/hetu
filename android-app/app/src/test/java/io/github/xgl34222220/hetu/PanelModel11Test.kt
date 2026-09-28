package io.github.xgl34222220.hetu

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PanelModel11Test {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val prefs get() = context.getSharedPreferences("hetu", 0)
    private val nodes = listOf(ProxyNodeUi("Alpha", "VLESS", true, 80, "P1"), ProxyNodeUi("Beta", "VMess", false, 40, "P2"), ProxyNodeUi("Gamma", "Trojan"))
    private fun group(name: String = "节点选择", hidden: Boolean = false) = ProxyGroupUi(name, "Selector", "Alpha", nodes, hidden = hidden)
    @Before fun clear() { prefs.edit().clear().commit() }

    @Test fun hiddenAndGlobalFiltersUseActualMetadataAndMode() {
        val source = listOf(group(), group("hidden", true), group("GLOBAL"))
        assertEquals(listOf("节点选择"), panelGroups11(source, "", "rule", PanelOptions11()).map { it.name })
        assertEquals(3, panelGroups11(source, "", "global", PanelOptions11(showHidden = true)).size)
        assertEquals(2, panelGroups11(source, "", "rule", PanelOptions11(globalByMode = false)).size)
    }
    @Test fun searchingNodeAndProviderFiltersChildrenInsteadOfReturningUnrelatedNodes() {
        val result = panelGroups11(listOf(group()), "beta p2", "rule", PanelOptions11())
        assertEquals(listOf("Beta"), result.single().nodes.map { it.name })
        assertTrue(panelGroups11(listOf(group()), "missing", "rule", PanelOptions11()).isEmpty())
        assertEquals(3, panelGroups11(listOf(group()), "节点选择", "rule", PanelOptions11()).single().nodes.size)
    }
    @Test fun expansionSupportsBothExclusiveAndMultipleGroups() {
        assertEquals(setOf("B"), panelExpanded11(setOf("A"), "B", true))
        assertEquals(setOf("A", "B"), panelExpanded11(setOf("A"), "B", false))
        assertEquals(emptySet<String>(), panelExpanded11(setOf("A"), "A", true))
    }
    @Test fun sortAndProviderGroupingReprojectRealData() {
        val e = panelEntries11(listOf(group()), setOf("节点选择"), "", PanelOptions11(sort = "latency", providers = true), emptyMap(), 2)
        assertEquals(listOf("P2", "P1", "配置内节点"), e.filterIsInstance<PanelEntry11.Provider>().map { it.provider })
        val reversed = panelEntries11(listOf(group()), setOf("节点选择"), "", PanelOptions11(sort = "name", descending = true), emptyMap(), 2)
        assertEquals(listOf("Gamma", "Beta", "Alpha"), reversed.filterIsInstance<PanelEntry11.Nodes>().flatMap { it.nodes }.map { it.name })
    }
    @Test fun thousandsOfNodesHaveBoundedRowSizeAndUniqueStableKeys() {
        val g = group().copy(nodes = (1..2000).map { ProxyNodeUi("Node $it", "VLESS") })
        val e = panelEntries11(listOf(g), setOf(g.name), "", PanelOptions11(), emptyMap(), 2)
        assertEquals(1000, e.filterIsInstance<PanelEntry11.Nodes>().size)
        assertEquals(e.size, e.map { it.key }.distinct().size)
        assertTrue(e.filterIsInstance<PanelEntry11.Nodes>().all { it.nodes.size <= 2 })
    }
    @Test fun allLayoutOptionsPersistAndReload() {
        val o = PanelOptions11(true, false, true, false, "name", true, 1, true)
        o.save(prefs)
        assertEquals(o, PanelOptions11.read(prefs))
    }
    @Test fun selectionDoesNotChangeUntilBackendAcknowledges() = runBlocking {
        val s = PanelSelection11(); val gate = CompletableDeferred<String>(); val g = group()
        val job = launch(start = CoroutineStart.UNDISPATCHED) { s.select(g, "Beta") { _, _ -> gate.await() } }
        assertEquals("Alpha", s.current(g)); assertEquals("Beta", s.pending[g.name])
        assertFalse(s.select(g, "Gamma") { _, _ -> fail("Duplicate request"); "Gamma" })
        gate.complete("Beta"); job.join()
        assertEquals("Beta", s.current(g)); assertTrue(s.pending.isEmpty())
        s.reconcile(listOf(g.copy(now = "Gamma"))); assertEquals("Gamma", s.current(g.copy(now = "Gamma")))
    }
    @Test fun failureAndCancellationPreserveSelectionAndClearBusyFlag() = runBlocking {
        val s = PanelSelection11(); val g = group()
        runCatching { s.select(g, "Beta") { _, _ -> error("backend rejected") } }
        assertEquals("Alpha", s.current(g)); assertTrue(s.pending.isEmpty())
        val job = launch(start = CoroutineStart.UNDISPATCHED) { s.select(g, "Beta") { _, _ -> awaitCancellation() } }
        job.cancelAndJoin(); assertTrue(s.pending.isEmpty()); assertEquals("Alpha", s.current(g))
    }
    @Test fun staleBackendAcknowledgementNeverCreatesSuccessCheck() = runBlocking {
        val s = PanelSelection11(); val g = group()
        assertTrue(runCatching { s.select(g, "Beta") { _, _ -> "Alpha" } }.isFailure)
        assertEquals("Alpha", s.current(g)); assertTrue(s.pending.isEmpty())
    }
    @Test fun invalidApiDraftCannotModifyExistingConfiguration() {
        val original = PanelApiDraft11(false, "", false, false, "127.0.0.1", "9090", "")
        original.save(prefs)
        val bad = original.copy(customApi = true, port = "0")
        assertNotNull(bad.validationError()); assertTrue(runCatching { bad.save(prefs) }.isFailure)
        assertFalse(prefs.getBoolean("proxyCustomApiEnabled", true))
        assertNotNull(original.copy(customDelay = true, delayUrl = "javascript:alert(1)").validationError())
        assertNotNull(original.copy(customDelay = true, delayUrl = "http://a:b@example.com/").validationError())
        assertNotNull(original.copy(secret = "bad\nsecret").validationError())
    }
    @Test fun savedApiHostPortSecretAndDelayUrlAreReadByOriginal146Client() {
        MockWebServer().use { server ->
            server.start()
            PanelApiDraft11(true, "https://example.com/probe", false, true, "127.0.0.1", server.port.toString(), "test-only-secret").save(prefs)
            server.enqueue(MockResponse().setResponseCode(204))
            MihomoControllerClient(context).select("节点选择", "Beta")
            val selection = server.takeRequest(2, TimeUnit.SECONDS)!!
            assertEquals("PUT", selection.method)
            assertTrue(selection.path!!.startsWith("/proxies/"))
            assertEquals("Bearer test-only-secret", selection.getHeader("Authorization"))
            assertTrue(selection.body.readUtf8().contains("Beta"))
            server.enqueue(MockResponse().setBody("{\"delay\":123}"))
            assertEquals(123L, MihomoControllerClient(context).delay("Beta"))
            val probe = server.takeRequest(2, TimeUnit.SECONDS)!!
            assertEquals("https://example.com/probe", probe.requestUrl!!.queryParameter("url"))
        }
    }
    @Test fun historyToggleControlsActualLocalCollection() = runBlocking {
        ProxyApiHistoryStore.clear(context)
        PanelApiDraft11(false, "", true, false, "127.0.0.1", "9090", "").save(prefs)
        val now = System.currentTimeMillis()
        ProxyApiHistoryStore.record(context, 100, 200, now = now)
        assertEquals(1, ProxyApiHistoryStore.recent(context, now = now).size)
        prefs.edit().putBoolean("proxyApiHistoryEnabled", false).commit()
        ProxyApiHistoryStore.record(context, 300, 400, now = now + 2000)
        prefs.edit().putBoolean("proxyApiHistoryEnabled", true).commit()
        assertEquals(1, ProxyApiHistoryStore.recent(context, now = now + 2000).size)
    }
}
