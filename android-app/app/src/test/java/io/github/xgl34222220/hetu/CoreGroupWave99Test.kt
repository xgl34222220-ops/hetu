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
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * BoxProxy 对标：未固定的自动组（URLTest/Fallback）与 LoadBalance 走核心并发的
 * GET /group/{name}/delay，一次请求由核心同时测完全部成员，并按核心写入的历史逐个落结果；
 * 已固定或核心不报告 fixed 的组仍只测叶子，绝不清除用户固定的选择。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class CoreGroupWave99Test {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val prefs get() = app.getSharedPreferences("hetu", 0)
    private val servers = mutableListOf<MockWebServer>()
    private val gates = mutableListOf<CountDownLatch>()
    private val url = "https://www.gstatic.com/generate_204"
    private val names = (1..6).map { "n$it" }

    @Before fun reset() { prefs.edit().clear().commit() }
    @After fun finish() { gates.forEach { it.countDown() }; servers.forEach { it.shutdown() } }

    private fun gate() = CountDownLatch(1).also { gates += it }
    private fun server(handler: (RecordedRequest) -> MockResponse) = MockWebServer().also { server ->
        server.dispatcher = object : Dispatcher() { override fun dispatch(request: RecordedRequest) = handler(request) }
        server.start(); servers += server
        prefs.edit().putBoolean("proxyCustomApiEnabled", true).putString("proxyCustomApiHost", "127.0.0.1")
            .putInt("proxyCustomApiPort", server.port).putBoolean("proxyRootRuntimeRunning", true)
            .putBoolean("proxyRootWanted", true).commit()
    }

    private fun record(time: String, delay: Long) = JSONObject().put("time", time).put("delay", delay)
    private fun leaf(name: String, history: List<JSONObject>) = JSONObject().put("name", name).put("type", "Shadowsocks")
        .put("history", JSONArray(history)).put("extra", JSONObject().put(url, JSONObject().put("history", JSONArray(history))))

    /** /proxies body: group of [type] pinned to [fixed] (null = core does not report it), leaves with [records]. */
    private fun proxies(type: String, fixed: String?, records: Map<String, List<JSONObject>>): String {
        val all = JSONObject()
        names.forEach { all.put(it, leaf(it, records[it].orEmpty())) }
        all.put("Auto", JSONObject().put("name", "Auto").put("type", type).put("all", JSONArray(names))
            .put("now", "n1").put("testUrl", url).apply { if (fixed != null) put("fixed", fixed) })
        return JSONObject().put("proxies", all).toString()
    }
    private val old = names.associateWith { listOf(record("2026-10-10T01:00:00.000000000+08:00", 900)) }
    private fun group(type: String) = ProxyGroupUi("Auto", type, "n1", names.map { ProxyNodeUi(it) })

    /* ------------------------------ pure decisions ------------------------------ */

    @Test fun onlyUnpinnedAutomaticGroupsAndLoadBalanceMayUseTheCoreGroupEndpoint() {
        fun g(type: String, fixed: Any? = Unit) = JSONObject().put("type", type).put("all", JSONArray(names))
            .apply { if (fixed != Unit) put("fixed", fixed ?: JSONObject.NULL) }
        assertTrue(coreGroupWaveKeepsSelection(g("URLTest", "")))
        assertTrue(coreGroupWaveKeepsSelection(g("Fallback", "")))
        assertTrue(coreGroupWaveKeepsSelection(g("LoadBalance")))
        assertFalse("A pin would be cleared by ForceSet(\"\")", coreGroupWaveKeepsSelection(g("URLTest", "n2")))
        assertFalse(coreGroupWaveKeepsSelection(g("Fallback", "n2")))
        assertFalse("Unknown pin state is treated as pinned", coreGroupWaveKeepsSelection(g("URLTest")))
        assertFalse(coreGroupWaveKeepsSelection(g("URLTest", null)))
        assertFalse("Selector keeps its own existing path", coreGroupWaveKeepsSelection(g("Selector", "")))
        assertFalse(coreGroupWaveKeepsSelection(JSONObject().put("type", "URLTest").put("fixed", "")))
        assertFalse(coreGroupWaveKeepsSelection(null))
    }

    @Test fun onlyRecordsNewerThanTheBaselineOfTheSameUrlLand() {
        val ipv6 = MihomoControllerClient.IPV6_DELAY_URL
        val live = JSONObject()
            .put("fresh", leaf("fresh", listOf(record("t0", 300), record("t1", 41))))
            .put("dead", leaf("dead", listOf(record("t0", 300), record("t1", 0))))
            .put("same", leaf("same", listOf(record("t0", 300))))
            .put("otherUrl", JSONObject().put("history", JSONArray(listOf(record("t9", 12))))
                .put("extra", JSONObject().put(ipv6, JSONObject().put("history", JSONArray(listOf(record("t9", 12)))))))
            .put("legacy", JSONObject().put("history", JSONArray(listOf(record("t2", 77)))))
        val baseline = mapOf("fresh" to "t0", "dead" to "t0", "same" to "t0", "otherUrl" to null, "legacy" to null, "missing" to null)
        assertEquals(mapOf("fresh" to 41L, "dead" to -2L, "legacy" to 77L), freshCoreWaveResults(live, url, baseline))
        assertEquals(-1L, freshCoreWaveResults(live, url, mapOf("dead" to "t0"), failure = -1L)["dead"])
        val nullDelay = JSONObject().put("history", JSONArray().put(JSONObject().put("time", "t").put("delay", JSONObject.NULL)))
        assertNull("A null delay is not a measurement", lastCoreWaveRecord(nullDelay, url))
    }

    /* ------------------------------ repository ------------------------------ */

    @Test fun unpinnedUrlTestGroupIsOneCoreRequestAndStreamsMembersBeforeTheSlowestFinishes() = runBlocking {
        val release = gate()
        val fast = names.take(4).associateWith { listOf(old.getValue(it).single(), record("2026-10-10T02:00:00.1+08:00", 40L + it.drop(1).toLong())) }
        val waveStarted = AtomicReference(false)
        val api = server { req ->
            when (req.requestUrl!!.encodedPath) {
                "/proxies" -> MockResponse().setBody(proxies("URLTest", "", if (waveStarted.get()) old + fast else old))
                "/providers/proxies" -> MockResponse().setBody("{\"providers\":{}}")
                "/group/Auto/delay" -> {
                    waveStarted.set(true)
                    check(release.await(8, TimeUnit.SECONDS))
                    MockResponse().setBody("""{"n1":41,"n2":42,"n3":43,"n4":44,"n5":45}""")
                }
                else -> MockResponse().setResponseCode(404)
            }
        }
        val streamed = Collections.synchronizedList(mutableListOf<Pair<String, Long>>())
        val repository = ProxyDashboardRepository(app)
        val wave = async(Dispatchers.IO) { repository.groupDelay(group("URLTest"), names) { n, v -> streamed += n to v } }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4)
        while (streamed.size < 4 && System.nanoTime() < deadline) Thread.sleep(20)
        assertEquals("Four members land from core history while the group request is still open",
            names.take(4).map { it to 40L + it.drop(1).toLong() }.toSet(), streamed.toSet())
        assertFalse(wave.isCompleted)
        release.countDown()
        val result = wave.await()
        assertEquals(mapOf("n1" to 41L, "n2" to 42L, "n3" to 43L, "n4" to 44L, "n5" to 45L), result)
        val paths = generateSequence { api.takeRequest(0, TimeUnit.SECONDS) }.map { it.requestUrl!! }.toList()
        assertEquals(1, paths.count { it.encodedPath == "/group/Auto/delay" })
        assertFalse("No leaf probes when the core runs the wave", paths.any { it.encodedPath.startsWith("/proxies/") })
        val groupRequest = paths.single { it.encodedPath == "/group/Auto/delay" }
        assertEquals(url, groupRequest.queryParameter("url"))
        assertEquals("5000", groupRequest.queryParameter("timeout"))
    }

    @Test fun pinnedOrUnreportedAutomaticGroupsNeverCallTheUnfixingEndpoint() = runBlocking {
        for (fixed in listOf("n2", null)) {
            val api = server { req ->
                when (req.requestUrl!!.encodedPath) {
                    "/proxies" -> MockResponse().setBody(proxies("Fallback", fixed, old))
                    "/providers/proxies" -> MockResponse().setBody("{\"providers\":{}}")
                    else -> if (req.requestUrl!!.encodedPath.endsWith("/delay") && req.requestUrl!!.encodedPath.startsWith("/proxies/"))
                        MockResponse().setBody("{\"delay\":33}") else MockResponse().setResponseCode(404)
                }
            }
            val result = ProxyDashboardRepository(app).groupDelay(group("Fallback"), names)
            assertEquals(names.associateWith { 33L }, result)
            val paths = generateSequence { api.takeRequest(0, TimeUnit.SECONDS) }.map { it.requestUrl!!.encodedPath }.toList()
            assertFalse(paths.any { it.startsWith("/group/") })
        }
    }

    @Test fun unavailableGroupEndpointFallsBackToLeafProbes() = runBlocking {
        val api = server { req ->
            val path = req.requestUrl!!.encodedPath
            when {
                path == "/proxies" -> MockResponse().setBody(proxies("URLTest", "", old))
                path == "/providers/proxies" -> MockResponse().setBody("{\"providers\":{}}")
                path == "/group/Auto/delay" -> MockResponse().setResponseCode(404).setBody("{\"message\":\"not found\"}")
                path.startsWith("/proxies/") && path.endsWith("/delay") -> MockResponse().setBody("{\"delay\":52}")
                else -> MockResponse().setResponseCode(404)
            }
        }
        assertEquals(names.associateWith { 52L }, ProxyDashboardRepository(app).groupDelay(group("URLTest"), names))
        val paths = generateSequence { api.takeRequest(0, TimeUnit.SECONDS) }.map { it.requestUrl!!.encodedPath }.toList()
        assertEquals(1, paths.count { it == "/group/Auto/delay" })
        assertEquals(names.size, paths.count { it.startsWith("/proxies/") && it.endsWith("/delay") })
    }

    @Test fun allMembersTimingOutIsARealWaveSettledFromCoreFailureRecords() = runBlocking {
        val failed = names.take(5).associateWith { listOf(old.getValue(it).single(), record("2026-10-10T02:00:05+08:00", 0)) }
        val after = AtomicReference(false)
        server { req ->
            when (req.requestUrl!!.encodedPath) {
                "/proxies" -> MockResponse().setBody(proxies("LoadBalance", null, if (after.get()) old + failed else old))
                "/providers/proxies" -> MockResponse().setBody("{\"providers\":{}}")
                "/group/Auto/delay" -> { after.set(true); MockResponse().setResponseCode(504).setBody("{\"message\":\"all proxies timeout\"}") }
                else -> MockResponse().setResponseCode(404)
            }
        }
        val result = ProxyDashboardRepository(app).groupDelay(group("LoadBalance"), names)
        assertEquals("Five confirmed timeouts; n6 has no new record and keeps its earlier reading",
            names.take(5).associateWith { -1L }, result)
    }

    @Test fun successfulSelectionDuringAWaveSupersedesItAndStopsStreaming() = runBlocking {
        val entered = gate(); val release = gate()
        server { req ->
            when (req.requestUrl!!.encodedPath) {
                "/proxies" -> MockResponse().setBody(proxies("URLTest", "", old))
                "/providers/proxies" -> MockResponse().setBody("{\"providers\":{}}")
                "/group/Auto/delay" -> { entered.countDown(); check(release.await(8, TimeUnit.SECONDS)); MockResponse().setBody("""{"n1":999}""") }
                "/proxies/Other" -> MockResponse().setResponseCode(204)
                else -> MockResponse().setResponseCode(404)
            }
        }
        val repository = ProxyDashboardRepository(app)
        val streamed = Collections.synchronizedList(mutableListOf<String>())
        supervisorScope {
            val wave = async(Dispatchers.IO) { repository.groupDelay(group("URLTest"), names) { n, _ -> streamed += n } }
            assertTrue(entered.await(4, TimeUnit.SECONDS))
            repository.select("Other", "n2")
            release.countDown()
            val failure = try { wave.await(); null } catch (error: IOException) { error }
            assertNotNull(failure)
            assertEquals("代理或控制接口已变化，请重新测速", failure!!.message)
            assertTrue("A superseded wave publishes nothing", streamed.isEmpty())
        }
    }

    @Test fun aCoreConfirmedLeafTimeoutIsNotRetriedOnTheNextDefaultUrl() {
        val api = server { MockResponse().setResponseCode(504).setBody("{\"message\":\"timeout\"}") }
        val error = assertThrows(MihomoControllerClient.DelayFailure::class.java) { MihomoControllerClient(app).delay("Japan") }
        assertTrue(error.timedOut)
        assertEquals("One 5 s window per dead node, not two", 1, api.requestCount)
    }
}
