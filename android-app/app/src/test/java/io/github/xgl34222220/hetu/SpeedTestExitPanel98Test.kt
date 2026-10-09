package io.github.xgl34222220.hetu

import android.app.Application
import android.net.LinkProperties
import android.os.Looper
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import io.github.xgl34222220.hetu.home.HomeConnectionHealth
import io.github.xgl34222220.hetu.home.HomeConnectionObservation
import io.github.xgl34222220.hetu.home.HomeEgress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
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
import org.robolectric.util.ReflectionHelpers
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 真机反馈三项：测速被身份守卫误丢、首页「出口未验证」从不变为已验证、策略页冷启动等待 Root 状态脚本。
 * 测速只因控制接口/核心实例/配置/真实控制事务而作废；观测发布、观测性偏好改写与物理网络代次不再作废控制器测速。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, shadows = [
    ControllerRunState90Shadows.RootStatus::class,
    PanelActionRuntimeShadows.RuntimeSample::class,
    PanelActionRuntimeShadows.HistoryRecord::class,
    PanelActionRuntimeShadows.NoRoot::class,
])
@LooperMode(LooperMode.Mode.PAUSED)
class SpeedTestExitPanel98Test {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val prefs get() = app.getSharedPreferences("hetu", 0)
    private val control get() = ReflectionHelpers.getStaticField<ProxyControlEpoch>(RootProxyManager::class.java, "CONTROL_LOCK")
    private val servers = mutableListOf<MockWebServer>()
    private val gates = mutableListOf<CountDownLatch>()
    private val vms = mutableListOf<HetuViewModel>()

    @Before fun prepare() {
        ControllerRunState90Shadows.RootStatus.reset()
        PanelActionRuntimeShadows.RuntimeSample.reset()
        PanelActionRuntimeShadows.HistoryRecord.reset()
        PanelActionRuntimeShadows.NoRoot.reset()
        prefs.edit().clear().putBoolean("proxyRootWanted", true).putBoolean("proxyRootRuntimeRunning", true)
            .putBoolean("proxyApiHistoryEnabled", false).commit()
    }

    @After fun finish() {
        gates.forEach { it.countDown() }
        vms.forEach { it.viewModelScope.cancel() }
        ControllerRunState90Shadows.RootStatus.gate?.countDown()
        PanelActionRuntimeShadows.RuntimeSample.release()
        PanelActionRuntimeShadows.HistoryRecord.release()
        vms.forEach { vm ->
            eventually("ViewModel work finished") { vm.viewModelScope.coroutineContext[Job]!!.children.none { it.isActive } }
        }
        servers.forEach { it.shutdown() }
        assertEquals("Fixtures never run Root commands", 0, PanelActionRuntimeShadows.NoRoot.calls)
    }

    private fun gate() = CountDownLatch(1).also { gates += it }
    private fun CountDownLatch.hold() { check(await(8, TimeUnit.SECONDS)) { "Fixture gate not released" } }
    private fun server(handler: (RecordedRequest) -> MockResponse) = MockWebServer().also { server ->
        server.dispatcher = object : Dispatcher() { override fun dispatch(request: RecordedRequest) = handler(request) }
        server.start(); servers += server
    }
    private fun useApi(server: MockWebServer) {
        prefs.edit().putBoolean("proxyCustomApiEnabled", true).putString("proxyCustomApiHost", "127.0.0.1")
            .putInt("proxyCustomApiPort", server.port).commit()
    }
    private fun eventually(reason: String, predicate: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(6)
        while (System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (predicate()) return
            Thread.sleep(10)
        }
        assertTrue(reason, predicate())
    }

    /** Holds the observation gate the way a status poll or the network service publishes. */
    private fun publishWhile(entered: CountDownLatch, release: CountDownLatch): Thread = Thread {
        RootProxyManager.publishObservation(RootProxyManager.observationTicket()) {
            entered.countDown(); release.await(8, TimeUnit.SECONDS)
        }
    }.also { it.start() }

    /* ------------------------------ 1. speed test identity ------------------------------ */

    @Test fun observationPublicationDuringASelectorWaveKeepsItsResult() = runBlocking {
        val held = gate()
        val api = server { request ->
            if (request.requestUrl!!.encodedPath == "/group/Route/delay") {
                held.hold(); MockResponse().setBody("""{"A":31,"B":42}""")
            } else MockResponse().setResponseCode(404)
        }
        useApi(api)
        val repository = ProxyDashboardRepository(app)
        val publishing = CountDownLatch(1); val endPublish = gate()
        supervisorScope {
            val wave = async(Dispatchers.Default) { repository.groupDelay("Route") }
            eventually("Selector wave reached the controller") { api.requestCount == 1 }
            val publisher = publishWhile(publishing, endPublish)
            assertTrue(publishing.await(3, TimeUnit.SECONDS))
            assertEquals("A publication still reads as busy to observers", -1L, RootProxyManager.observationTicket())
            assertTrue("…but it is not a control transaction", RootProxyManager.probeTicket() >= 0L)
            // Pollers also rewrite observational flags and the physical-network epoch.
            prefs.edit().putBoolean("proxyRootRuntimeRunning", true).putLong("proxyNetworkEpoch", 41L)
                .putLong("proxyRootHealthProbeElapsed", 99L).commit()
            held.countDown()
            assertEquals(mapOf("A" to 31L, "B" to 42L), wave.await())
            endPublish.countDown(); publisher.join(3000)
        }
        assertEquals("One core request for a Selector group", 1, api.requestCount)
        assertTrue(api.takeRequest().path!!.contains("url=https%3A%2F%2Fwww.gstatic.com%2Fgenerate_204"))
    }

    @Test fun leafResultsLandPerNodeAcrossObservationalRewrites() = runBlocking {
        val names = (1..8).map { "n$it" }
        val slow = gate()
        val api = server { request ->
            val path = request.requestUrl!!.encodedPath
            when {
                path == "/proxies" -> {
                    val proxies = org.json.JSONObject()
                    names.forEach { proxies.put(it, org.json.JSONObject().put("name", it).put("type", "Shadowsocks")) }
                    proxies.put("Auto", org.json.JSONObject().put("name", "Auto").put("type", "URLTest")
                        .put("all", org.json.JSONArray(names)).put("now", "n1"))
                    MockResponse().setBody(org.json.JSONObject().put("proxies", proxies).toString())
                }
                path == "/providers/proxies" -> MockResponse().setBody("""{"providers":{}}""")
                path == "/proxies/n8/delay" -> { slow.hold(); MockResponse().setBody("""{"delay":80}""") }
                path.endsWith("/delay") -> MockResponse().setBody("""{"delay":20}""")
                else -> MockResponse().setResponseCode(404)
            }
        }
        useApi(api)
        val repository = ProxyDashboardRepository(app)
        val landed = Collections.synchronizedMap(LinkedHashMap<String, Long>())
        val group = ProxyGroupUi("Auto", "URLTest", "n1", names.map { ProxyNodeUi(it) })
        supervisorScope {
            val wave = async(Dispatchers.Default) { repository.groupDelay(group, names) { node, value -> landed[node] = value } }
            eventually("Seven fast leaves are shown before the slow one answers") { landed.size == 7 }
            prefs.edit().putBoolean("proxyRootRuntimeRunning", false).putLong("proxyNetworkEpoch", 7L).commit()
            prefs.edit().putBoolean("proxyRootRuntimeRunning", true).commit()
            slow.countDown()
            val result = wave.await()
            assertEquals(8, result.size)
            assertEquals(80L, result["n8"])
            assertEquals(80L, landed["n8"])
        }
    }

    @Test fun aRealControlTransactionOrEndpointChangeStillVoidsTheWave() = runBlocking {
        for (endpoint in listOf(false, true)) {
            val held = gate()
            val api = server { request ->
                if (request.requestUrl!!.encodedPath == "/group/Route/delay") { held.hold(); MockResponse().setBody("""{"A":31}""") }
                else MockResponse().setResponseCode(404)
            }
            useApi(api)
            val repository = ProxyDashboardRepository(app)
            supervisorScope {
                val wave = async(Dispatchers.Default) { runCatching { repository.groupDelay("Route") } }
                eventually("Wave entered") { api.requestCount == 1 }
                if (endpoint) prefs.edit().putString("proxyCustomApiSecret", "rotated").commit()
                else { control.lock(); assertEquals(-1L, RootProxyManager.probeTicket()) }
                try { held.countDown() } finally { if (!endpoint) control.unlock() }
                val failure = wave.await().exceptionOrNull()
                assertTrue(failure is IOException)
                assertEquals("代理或控制接口已变化，请重新测速", failure!!.message)
            }
            prefs.edit().remove("proxyCustomApiSecret").commit()
        }
    }

    @Test fun probeTicketTracksTransactionsOnly() {
        val epoch = ProxyControlEpoch()
        val first = epoch.transactionEpoch()
        assertTrue(first >= 0L)
        assertTrue(epoch.publish(epoch.observe()) {
            assertEquals(-1L, epoch.observe())
            assertEquals(first, epoch.transactionEpoch())
        })
        epoch.lock()
        try {
            assertEquals(-1L, epoch.transactionEpoch())
            epoch.lock(); epoch.unlock() // reentrant transaction
            assertEquals(-1L, epoch.transactionEpoch())
        } finally { epoch.unlock() }
        val after = epoch.transactionEpoch()
        assertTrue(after >= 0L && after != first)
        assertTrue(epoch.tryLock()); epoch.unlock()
        assertNotEquals(after, epoch.transactionEpoch())
    }

    @Test fun linkSignatureIgnoresNonRoutingFieldsButSeesRouteChanges() {
        fun links(dns: String, mtu: Int, name: String? = "wlan0") = LinkProperties().apply {
            interfaceName = name
            this.mtu = mtu
            ReflectionHelpers.callInstanceMethod<Boolean>(this, "addDnsServer",
                ReflectionHelpers.ClassParameter.from(InetAddress::class.java, InetAddress.getByName(dns)))
        }
        val base = links("192.0.2.53", 1500)
        val remtu = links("192.0.2.53", 1280)
        assertNotEquals("Precondition: the raw string changes", base.toString(), remtu.toString())
        assertEquals(ProxyNetworkMatchService.linkSignature(base), ProxyNetworkMatchService.linkSignature(remtu))
        assertNotEquals(ProxyNetworkMatchService.linkSignature(base),
            ProxyNetworkMatchService.linkSignature(links("192.0.2.54", 1500)))
        assertNotEquals(ProxyNetworkMatchService.linkSignature(base),
            ProxyNetworkMatchService.linkSignature(links("192.0.2.53", 1500, "rmnet0")))
        assertNull(ProxyNetworkMatchService.linkSignature(links("192.0.2.53", 1500, null)))
    }

    /* ------------------------------ 2. exit verification ------------------------------ */

    private fun egressProxy(code: () -> Int): MockWebServer = server { request ->
        if (request.requestLine.startsWith("GET http://egress.invalid/generate_204")) MockResponse().setResponseCode(code())
        else MockResponse().setResponseCode(400)
    }.also { proxy ->
        prefs.edit().putInt("proxyControllerPort", proxy.port - MihomoStartupConfig.EGRESS_PROBE_PORT_OFFSET)
            .putLong("proxyRootLastStartupAt", System.currentTimeMillis() - 60_000L).commit()
    }

    @Test fun exitCheckThroughThePolicyListenerVerifiesThisRuntime() = runBlocking {
        var code = 204
        val proxy = egressProxy { code }
        val repository = ProxyDashboardRepository(app).apply { egressTargets = listOf("http://egress.invalid/generate_204") }
        assertFalse(HomeEgress.verified(prefs))
        assertTrue(HomeEgress.due(prefs, System.currentTimeMillis()))
        assertEquals(true, repository.verifyEgress())
        assertEquals(1, proxy.requestCount)
        assertEquals("reachable", prefs.getString("proxyPolicyEgressState", ""))
        assertTrue(HomeEgress.verified(prefs))
        assertFalse("A fresh success is cached", HomeEgress.due(prefs, System.currentTimeMillis()))
        assertTrue("…and rechecked after ten minutes", HomeEgress.due(prefs, System.currentTimeMillis() + HomeEgress.RECHECK_MS + 1))

        code = 503
        assertEquals("A dead exit withdraws the earlier success", false, repository.verifyEgress())
        assertEquals("unverified", prefs.getString("proxyPolicyEgressState", ""))
        assertFalse(HomeEgress.verified(prefs))
        val before = prefs.all
        assertEquals(false, repository.verifyEgress())
        assertEquals("Nothing to withdraw: no write", before, prefs.all)
    }

    @Test fun anOlderRuntimesVerificationOrAStoppedCoreIsNotVerified() = runBlocking {
        prefs.edit().putString("proxyPolicyEgressState", "reachable").putLong("proxyRootEgressVerifiedAt", 1_000L)
            .putLong("proxyRootLastStartupAt", 2_000L).commit()
        assertFalse("Verified before this core started", HomeEgress.verified(prefs))
        prefs.edit().putBoolean("proxyRootRuntimeRunning", false).commit()
        val repository = ProxyDashboardRepository(app).apply { egressTargets = listOf("http://egress.invalid/generate_204") }
        assertNull(repository.verifyEgress())
    }

    @Test fun homeCardShowsVerifiedOnlyAfterLocalAndExitChecks() {
        val local = HomeConnectionObservation(controllerReady = true, takeoverHealthy = true)
        assertEquals(HomeConnectionHealth.LocalReady, local.health)
        assertEquals("接管检查通过 · 出口未验证", local.health.caption)
        val verified = local.copy(egressVerified = true)
        assertEquals(HomeConnectionHealth.Verified, verified.health)
        assertEquals("接管检查通过 · 出口已验证", verified.health.caption)
        assertEquals("Exit success never hides a degraded takeover",
            HomeConnectionHealth.TakeoverDegraded, verified.copy(takeoverHealthy = false).health)
        assertEquals(HomeConnectionHealth.ControllerUnavailable, verified.copy(controllerReadFailed = true).health)
    }

    /* ------------------------------ 3. cold-open strategy cards ------------------------------ */

    @Test fun coldOpenShowsLiveGroupsBeforeRootStatusButStaysReadOnly() {
        val requests = Collections.synchronizedList(mutableListOf<String>())
        val api = server { request ->
            requests += request.requestUrl!!.encodedPath
            MockResponse().setBody(when (request.requestUrl!!.encodedPath) {
                "/configs" -> """{"mode":"rule"}"""
                "/proxies" -> """{"proxies":{"Route":{"type":"Selector","now":"other","all":["node","other"]},"node":{"type":"Shadowsocks"},"other":{"type":"Shadowsocks"}}}"""
                "/providers/proxies" -> """{"providers":{}}"""
                "/connections" -> """{"connections":[]}"""
                "/version" -> """{"version":"fixture"}"""
                else -> "{}"
            })
        }
        useApi(api)
        val config = "cold-open-98"
        StrategySnapshotCache.write(app, config, listOf(ProxyGroupUi("Route", "Selector", "node",
            listOf(ProxyNodeUi("node"), ProxyNodeUi("other")))))
        eventually("Cache written") { File(app.noBackupFilesDir, "strategy-groups-v1.json").isFile }
        prefs.edit().putBoolean("proxyUiLastRunning", true).putString("proxyUiLastConfig", config)
            .putString(StrategySnapshotCache.PREF_KEY, config).commit()
        val entered = CountDownLatch(1); val release = gate()
        ControllerRunState90Shadows.RootStatus.reply = """{"ok":true,"running":true,"pid":77,"dataPlaneHealthy":true}"""
        ControllerRunState90Shadows.RootStatus.entered = entered
        ControllerRunState90Shadows.RootStatus.gate = release
        PanelActionRuntimeShadows.RuntimeSample.value = ProxyRuntimeSnapshot(running = true, pid = 77)
        val vm = HetuViewModel(app).also { vms += it }
        assertEquals("First frame: cached cards", "node", vm.state.groups.single().now)
        assertFalse(vm.state.panelReady)
        val job = vm.viewModelScope.launch { vm.refreshNow() }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        eventually("Live controller list replaces the cache while Root status is still running") {
            vm.state.groups.singleOrNull()?.now == "other"
        }
        assertFalse("Still not an authoritative snapshot", vm.state.panelReady)
        assertFalse("Only display reads before status", requests.any { it == "/connections" || it == "/configs" })
        val before = requests.size
        vm.select("Route", "node"); vm.testGroup(vm.state.groups.single())
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(vm.pendingSelection.isEmpty()); assertTrue(vm.testingGroups.isEmpty())
        assertEquals("Read-only until confirmed", before, requests.size)
        release.countDown()
        eventually("Confirmed snapshot follows") { job.isCompleted && vm.state.panelReady }
        assertEquals("other", vm.state.groups.single().now)
    }
}
