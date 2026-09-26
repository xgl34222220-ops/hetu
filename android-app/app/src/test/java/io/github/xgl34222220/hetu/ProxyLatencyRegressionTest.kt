package io.github.xgl34222220.hetu

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Real controller contracts: provider nodes are absent from /proxies but present in group.all. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ProxyLatencyRegressionTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val testUrl = "https://www.gstatic.com/generate_204"
    private val time = "2026-09-26T19:19:16Z"

    @Before fun resetPreferences() {
        context.getSharedPreferences("hetu", 0).edit().clear().commit()
    }

    private fun node(name: String, delay: Long? = null): JSONObject = JSONObject().put("name", name)
        .put("type", "Shadowsocks").put("udp", true).put("history", JSONArray().also {
            if (delay != null) it.put(JSONObject().put("time", time).put("delay", delay))
        })

    private fun group(vararg nodes: String, selected: String = nodes.first()): JSONObject = JSONObject()
        .put("name", "自动选择").put("type", "URLTest").put("all", JSONArray(nodes.toList()))
        .put("now", selected).put("testUrl", testUrl)

    private fun providers(name: String, vararg nodes: JSONObject, vehicle: String = "HTTP"): JSONObject =
        JSONObject().put("providers", JSONObject().put(name, JSONObject().put("name", name)
            .put("vehicleType", vehicle).put("testUrl", testUrl).put("expectedStatus", "204")
            .put("proxies", JSONArray(nodes.toList()))))

    private fun connect(server: MockWebServer): MihomoControllerClient {
        server.start()
        context.getSharedPreferences("hetu", 0).edit().putBoolean("proxyCustomApiEnabled", true)
            .putString("proxyCustomApiHost", "127.0.0.1").putInt("proxyCustomApiPort", server.port).commit()
        return MihomoControllerClient(context)
    }

    @Test fun providerHistoriesRestoreFortyOfEightyOneHealthyNodesAndSelectedLatency() {
        val names = (1..81).map { "日本节点 $it" }
        val raw = JSONObject().put("节点选择", group(*names.toTypedArray()))
        val provider = providers("订阅", *names.mapIndexed { i, name -> node(name, if (i < 40) 65L + i else 0L) }.toTypedArray())
        val combined = mergeProxySnapshots(raw, provider)
        val parsed = ProxyComposeController(context).parseGroups(combined, emptyMap()).single()
        assertEquals(81, parsed.nodes.size)
        assertEquals(40, parsed.nodes.count { (it.lastDelay ?: 0L) > 0L })
        assertEquals(65L, parsed.nodes.first().lastDelay)
        assertEquals("订阅", parsed.nodes.first().provider)
        assertNull(raw.optJSONObject(names.first())) // Do not mutate the original server snapshot.
    }

    @Test fun staticGroupWinsOverCollidingProviderName() {
        val static = JSONObject().put("自动选择", group("Japan"))
        val merged = mergeProxySnapshots(static, providers("sub", node("自动选择", 99)))
        assertEquals("URLTest", merged.getJSONObject("自动选择").getString("type"))
    }

    @Test fun urlSpecificHistoryIsReadWhenOrdinaryHistoryIsEmpty() {
        val data = node("Japan").put("extra", JSONObject().put(testUrl,
            JSONObject().put("alive", true).put("history", JSONArray().put(JSONObject().put("time", time).put("delay", 74)))))
        assertEquals(74L, coreLatencySample(data)?.delay)
        assertEquals(testUrl, coreLatencySample(data)?.testUrl)
    }

    @Test fun newestFailureDoesNotReviveEarlierHealthyRecord() {
        val data = node("Japan", 74)
        data.getJSONArray("history").put(JSONObject().put("time", "2026-09-26T19:20:00Z").put("delay", 0))
        assertEquals(-2L, coreLatencySample(data)?.delay)
        assertNull(coreLatencySample(node("Untested")))
        assertNull(coreLatencySample(JSONObject().put("alive", false)))
    }

    @Test fun perUrlHistoryHonorsExplicitTargetAndTimestampsAcrossTimezones() {
        val targetHistory = JSONArray().put(JSONObject().put("time", "2026-09-27T03:20:00+08:00").put("delay", 77))
        val data = node("Japan", 91).put("extra", JSONObject()
            .put(testUrl, JSONObject().put("history", targetHistory)))
        assertEquals(77L, coreLatencySample(data)?.delay)
        assertEquals(77L, coreLatencySample(data, testUrl)?.delay)
    }

    @Test fun ipv6CapabilityFailureCannotReplaceWorkingOrdinaryLatency() {
        val ordinary = JSONObject().put("time", time).put("delay", 88)
        val ipv6Failure = JSONObject().put("time", "2026-09-26T19:20:00Z").put("delay", 0)
        val data = node("Japan").put("history", JSONArray().put(ordinary).put(ipv6Failure))
            .put("extra", JSONObject()
                .put(testUrl, JSONObject().put("history", JSONArray().put(ordinary)))
                .put(MihomoControllerClient.IPV6_DELAY_URL, JSONObject().put("history", JSONArray().put(ipv6Failure))))
        assertEquals(88L, coreLatencySample(data)?.delay)
    }

    @Test fun selectedNestedGroupUsesItsSelectedProviderLeafWithoutLooping() {
        val raw = JSONObject().put("outer", group("inner"))
            .put("inner", group("Japan")).put("Japan", node("Japan", 65))
        assertEquals(65L, resolvedCoreLatency(raw, "outer")?.delay)
        raw.getJSONObject("inner").put("now", "outer")
        assertNull(resolvedCoreLatency(raw, "outer"))
    }

    @Test fun coreRefreshReplacesStaleLocalTimeoutAndOldSuccessfulReading() {
        val old = mutableMapOf("Japan" to -1L, "Korea" to 90L)
        val groups = listOf(ProxyGroupUi("group", "URLTest", "Japan", listOf(
            ProxyNodeUi("Japan", lastDelay = 65, lastDelayAt = 5), ProxyNodeUi("Korea", lastDelay = -2, lastDelayAt = 5))))
        syncCoreLatencyResults(groups, old)
        assertEquals(mapOf("Japan" to 65L, "Korea" to -2L), old)
    }

    @Test fun stalePollCannotOverwriteNewerLocalProbeButNextPollCanRefreshIt() {
        val readings = mutableMapOf("Japan" to -1L)
        val measuredAt = mapOf("Japan" to 200L)
        val groups = listOf(ProxyGroupUi("Auto", "URLTest", "Japan", listOf(ProxyNodeUi("Japan", lastDelay = 65))))
        syncCoreLatencyResults(groups, readings, measuredAt, snapshotStartedAt = 100L)
        assertEquals(-1L, readings["Japan"])
        syncCoreLatencyResults(groups, readings, measuredAt, snapshotStartedAt = 300L)
        assertEquals(65L, readings["Japan"])
    }

    @Test fun oldCustomUrlHistoryDoesNotPinNewerProviderFallbackReading() {
        val data = node("Japan").put("history", JSONArray().put(JSONObject().put("time", "2026-09-26T19:20:00Z").put("delay", 74)))
            .put("extra", JSONObject().put(testUrl, JSONObject().put("history", JSONArray().put(JSONObject().put("time", time).put("delay", 0)))))
        assertEquals(74L, coreLatencySample(data, testUrl)?.delay)
    }

    @Test fun providerDelayUsesProviderEndpointAndEncodesEachSegment() {
        MockWebServer().use { server ->
            val api = connect(server)
            server.enqueue(MockResponse().setBody("{\"delay\":65}"))
            assertEquals(65L, api.providerDelay("机场 / A", "日本 02 [两年]", testUrl, "204"))
            val request = server.takeRequest(2, TimeUnit.SECONDS)!!
            assertEquals(listOf("providers", "proxies", "机场 / A", "日本 02 [两年]", "healthcheck"), request.requestUrl!!.pathSegments)
            assertEquals(testUrl, request.requestUrl!!.queryParameter("url"))
            assertEquals("204", request.requestUrl!!.queryParameter("expected"))
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun nativeRepositoryMeasuresHttpAndFileProviderNodesWithoutWrongProxiesEndpoint() = runBlocking {
        for (vehicle in listOf("HTTP", "File")) {
            MockWebServer().use { server ->
                connect(server)
                val dynamic = providers("my-sub", node("Japan", 65), vehicle = vehicle)
                server.dispatcher = object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse = when(request.requestUrl!!.encodedPath) {
                        "/proxies" -> MockResponse().setBody(JSONObject().put("proxies", JSONObject().put("Auto", group("Japan"))).toString())
                        "/providers/proxies" -> MockResponse().setBody(dynamic.toString())
                        "/providers/proxies/my-sub/Japan/healthcheck" -> MockResponse().setBody("{\"delay\":74}")
                        else -> MockResponse().setResponseCode(404).setBody("{\"message\":\"Not Found\"}")
                    }
                }
                assertEquals(74L, ProxyDashboardRepository(context).delay("Japan"))
                assertEquals(3, server.requestCount)
            }
        }
    }

    @Test fun wholeWaveIncludesProviderNodesAndReadsMetadataOnce() = runBlocking {
        MockWebServer().use { server ->
            connect(server)
            val dynamic = providers("my-sub", node("Japan", 65), node("Korea", 99))
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when(request.requestUrl!!.encodedPath) {
                    "/proxies" -> MockResponse().setBody(JSONObject().put("proxies", JSONObject().put("Auto", group("Japan", "Korea"))).toString())
                    "/providers/proxies" -> MockResponse().setBody(dynamic.toString())
                    "/providers/proxies/my-sub/Japan/healthcheck" -> MockResponse().setBody("{\"delay\":65}")
                    "/providers/proxies/my-sub/Korea/healthcheck" -> MockResponse().setBody("{\"delay\":99}")
                    else -> MockResponse().setResponseCode(404)
                }
            }
            assertEquals(mapOf("Japan" to 65L, "Korea" to 99L), ProxyDashboardRepository(context).globalDelay())
            assertEquals(4, server.requestCount)
        }
    }

    @Test fun authenticationAndMissingNodeErrorsDoNotBecomeTimeoutOrRetryOtherUrls() {
        for (code in listOf(400, 401, 404)) MockWebServer().use { server ->
            val api = connect(server)
            server.enqueue(MockResponse().setResponseCode(code).setBody("{\"message\":\"bad request\"}"))
            val error = assertThrows(MihomoControllerClient.ControllerHttpException::class.java) { api.delay("Japan") }
            assertEquals(code, error.statusCode)
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun onlyCoreConfirmedTimeoutIsTimeoutAndUnreachableIsFailure() {
        for (code in listOf(504, 503)) MockWebServer().use { server ->
            val api = connect(server)
            server.enqueue(MockResponse().setResponseCode(code).setBody("{\"message\":\"probe failed\"}"))
            val error = assertThrows(MihomoControllerClient.DelayFailure::class.java) { api.providerDelay("sub", "Japan", testUrl, "204") }
            assertEquals(code == 504, error.timedOut)
        }
    }

    @Test fun requestFailureRetainsKnownReadingAndReportsErrorWithoutFalseTimeout() = runBlocking {
        val readings = mutableMapOf("Japan" to 65L)
        val errors = mutableListOf<String>()
        measureStrategyNodes(listOf("Japan", "Untested"), probe = { throw IOException("API authentication failed") },
            onTesting = { _, _ -> }, onMeasured = { name, value -> readings[name] = value },
            onError = { name, _ -> errors += name })
        assertEquals(mapOf("Japan" to 65L), readings)
        assertEquals(listOf("Japan", "Untested"), errors)
    }
}
