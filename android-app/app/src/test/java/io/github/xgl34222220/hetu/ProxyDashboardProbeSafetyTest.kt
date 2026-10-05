package io.github.xgl34222220.hetu

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Real repository HTTP requests: Root probes enter the local core, and waves retain partial success. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ProxyDashboardProbeSafetyTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val prefs get() = context.getSharedPreferences("hetu", 0)
    private val names = listOf("Baidu", "Cloudflare", "Google")
    private val testUrl = "https://www.gstatic.com/generate_204"

    @Before fun resetPreferences() {
        prefs.edit().clear().commit()
    }

    private fun targets(vararg urls: String) {
        ProxyLatencyTargets.save(prefs, names.mapIndexed { index, name ->
            ProxyLatencyTarget(name, urls.getOrElse(index) { urls.first() })
        })
    }

    private fun rootProxy(port: Int) {
        prefs.edit().putBoolean("proxyRootRuntimeRunning", true).putBoolean("proxyRootWanted", true)
            .putInt("proxyControllerPort", port - MihomoStartupConfig.EGRESS_PROBE_PORT_OFFSET).commit()
    }

    @Test fun runningRootUsesLocalPolicyListenerEvenWithAnUnrelatedCustomController() = runBlocking {
        MockWebServer().use { proxy -> MockWebServer().use { customApi ->
            proxy.start(); customApi.start()
            rootProxy(proxy.port)
            prefs.edit().putBoolean("proxyCustomApiEnabled", true).putString("proxyCustomApiHost", "127.0.0.1")
                .putInt("proxyCustomApiPort", customApi.port).commit()
            // The application must not resolve this name itself or send a controller request.
            targets("http://latency.invalid/baidu", "http://latency.invalid/cloudflare", "http://latency.invalid/google")
            repeat(3) { proxy.enqueue(MockResponse().setResponseCode(204)) }

            val measured = ProxyDashboardRepository(context).siteLatencies()
            assertEquals(names.toSet(), measured.keys)
            assertTrue(measured.values.all { it > 0L })
            assertEquals(3, proxy.requestCount)
            assertEquals(0, customApi.requestCount)
            val paths = (1..3).map { proxy.takeRequest(2, TimeUnit.SECONDS)!!.requestLine }
            assertEquals(setOf(
                "GET http://latency.invalid/baidu HTTP/1.1",
                "GET http://latency.invalid/cloudflare HTTP/1.1",
                "GET http://latency.invalid/google HTTP/1.1",
            ), paths.toSet())
        } }
    }

    @Test fun stoppedRootKeepsPhysicalDirectMeasurementDespiteAnOldRunningFlag() = runBlocking {
        MockWebServer().use { origin -> MockWebServer().use { proxy ->
            origin.start(); proxy.start()
            rootProxy(proxy.port)
            prefs.edit().putBoolean("proxyRootWanted", false).commit()
            targets(origin.url("/physical").toString())
            repeat(3) { origin.enqueue(MockResponse().setResponseCode(204)) }

            assertTrue(ProxyDashboardRepository(context).siteLatencies().values.all { it > 0L })
            assertEquals(3, origin.requestCount)
            assertEquals(0, proxy.requestCount)
            assertEquals("GET /physical HTTP/1.1", origin.takeRequest(2, TimeUnit.SECONDS)!!.requestLine)
        } }
    }

    @Test fun anUnavailableRunningListenerNeverFallsBackToPhysicalDirect() = runBlocking {
        MockWebServer().use { origin ->
            origin.start()
            val unavailablePort = ServerSocket(0).use { it.localPort }
            rootProxy(unavailablePort)
            targets(origin.url("/must-not-connect-direct").toString())
            origin.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = MockResponse().setResponseCode(204)
            }

            assertEquals(names.associateWith { -1L }, ProxyDashboardRepository(context).siteLatencies())
            assertEquals(0, origin.requestCount)
        }
    }

    @Test fun authenticationAccessAndProxyErrorsAreNotSuccessfulWebsiteLatency() = runBlocking {
        MockWebServer().use { proxy ->
            proxy.start(); rootProxy(proxy.port)
            targets("http://latency.invalid/401", "http://latency.invalid/403", "http://latency.invalid/407")
            proxy.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val code = request.requestLine.substringBefore(" HTTP/").substringAfterLast('/').toInt()
                    return MockResponse().setResponseCode(code)
                }
            }

            assertEquals(names.associateWith { -1L }, ProxyDashboardRepository(context).siteLatencies())
            assertEquals(3, proxy.requestCount)
        }
    }

    @Test fun ordinaryRedirectsRemainOnTheExplicitPolicyProxy() = runBlocking {
        MockWebServer().use { proxy ->
            proxy.start(); rootProxy(proxy.port)
            targets("http://latency.invalid/redirect")
            proxy.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse =
                    if (request.requestLine.contains("/redirect ")) MockResponse().setResponseCode(302)
                        .setHeader("Location", "http://redirect.invalid/final")
                    else MockResponse().setResponseCode(204)
            }

            assertTrue(ProxyDashboardRepository(context).siteLatencies().values.all { it > 0L })
            assertEquals(6, proxy.requestCount)
            val lines = (1..6).map { proxy.takeRequest(2, TimeUnit.SECONDS)!!.requestLine }
            assertEquals(3, lines.count { it == "GET http://redirect.invalid/final HTTP/1.1" })
            assertEquals(3, lines.count { it == "GET http://latency.invalid/redirect HTTP/1.1" })
        }
    }

    @Test fun cancellingEnteredWebsiteRequestsCannotPublishCompletedLatencies() = runBlocking {
        MockWebServer().use { proxy ->
            proxy.start(); rootProxy(proxy.port)
            targets("http://latency.invalid/held")
            val entered = CountDownLatch(3)
            val release = CountDownLatch(1)
            proxy.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    entered.countDown()
                    check(release.await(6, TimeUnit.SECONDS)) { "Website fixture was not released" }
                    return MockResponse().setResponseCode(204)
                }
            }
            val published = AtomicBoolean(false)
            val completion = AtomicReference<Throwable?>()
            val job = launch(Dispatchers.Default) {
                ProxyDashboardRepository(context).siteLatencies()
                published.set(true)
            }
            job.invokeOnCompletion { completion.set(it) }
            try {
                assertTrue(entered.await(3, TimeUnit.SECONDS))
                job.cancel()
            } finally {
                release.countDown()
            }
            job.join()
            assertFalse(published.get())
            assertTrue(job.isCancelled)
            assertTrue(completion.get() is CancellationException)
        }
    }

    private fun controller(server: MockWebServer, nodes: List<String>, selected: List<String> = nodes) {
        server.start()
        prefs.edit().putBoolean("proxyCustomApiEnabled", true).putString("proxyCustomApiHost", "127.0.0.1")
            .putInt("proxyCustomApiPort", server.port).commit()
        val proxies = JSONObject()
        nodes.forEach { name -> proxies.put(name, JSONObject().put("name", name).put("type", "Shadowsocks")) }
        selected.forEachIndexed { index, name -> proxies.put("Group $index", JSONObject()
            .put("name", "Group $index").put("type", "Selector").put("all", JSONArray(nodes))
            .put("now", name).put("testUrl", testUrl)) }
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.requestUrl!!.encodedPath) {
                "/proxies" -> MockResponse().setBody(JSONObject().put("proxies", proxies).toString())
                "/providers/proxies" -> MockResponse().setBody("{\"providers\":{}}")
                "/proxies/bad-auth/delay" -> MockResponse().setResponseCode(401)
                "/proxies/core-timeout/delay" -> MockResponse().setResponseCode(504)
                "/proxies/core-failure/delay" -> MockResponse().setResponseCode(503)
                else -> MockResponse().setBody("{\"delay\":74}")
            }
        }
    }

    @Test fun globalWaveKeepsSuccessfulSiblingsLaterChunksAndOnlyConfirmedFailures() = runBlocking {
        MockWebServer().use { server ->
            val nodes = listOf("bad-auth", "healthy-a", "core-timeout", "core-failure", "healthy-b", "healthy-c", "healthy-d", "healthy-e")
            controller(server, nodes)
            val result = ProxyDashboardRepository(context).globalDelay()
            val expected = nodes.filterNot { it == "bad-auth" }.associateWith {
                when (it) { "core-timeout" -> -1L; "core-failure" -> -2L; else -> 74L }
            }
            assertEquals(expected, result)
            // Applying returned measurements retains the old value for an API error.
            val old = mutableMapOf("bad-auth" to 99L)
            old.putAll(result)
            assertEquals(99L, old["bad-auth"])
            assertEquals(10, server.requestCount) // Two snapshots, every distinct leaf once.
        }
    }

    @Test fun quickWaveRetainsSuccessWhenAnotherSelectedNodeHasAnApiError() = runBlocking {
        MockWebServer().use { server ->
            controller(server, listOf("bad-auth", "healthy"), listOf("bad-auth", "healthy", "healthy"))
            assertEquals(mapOf("healthy" to 74L), ProxyDashboardRepository(context).quickDelay())
            assertEquals(4, server.requestCount) // Repeated selections are probed once.
        }
    }

    private fun selectApi(server: MockWebServer) {
        prefs.edit().putBoolean("proxyCustomApiEnabled", true).putString("proxyCustomApiHost", "127.0.0.1")
            .putInt("proxyCustomApiPort", server.port).commit()
    }

    private fun switchingApi(server: MockWebServer, current: () -> Pair<String, Long>,
        entered: CountDownLatch? = null, release: CountDownLatch? = null) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val (leaf, measured) = current()
                return when (request.requestUrl!!.encodedPath) {
                    "/proxies" -> {
                        entered?.countDown()
                        if (release != null) check(release.await(6, TimeUnit.SECONDS)) { "Snapshot fixture was not released" }
                        val group = JSONObject().put("name", "Route").put("type", "Selector")
                            .put("all", JSONArray(listOf(leaf))).put("now", leaf).put("testUrl", testUrl)
                        val proxies = JSONObject().put("Route", group).put(leaf,
                            JSONObject().put("name", leaf).put("type", "Shadowsocks"))
                        MockResponse().setBody(JSONObject().put("proxies", proxies).toString())
                    }
                    "/providers/proxies" -> MockResponse().setBody("{\"providers\":{}}")
                    "/proxies/$leaf/delay" -> MockResponse().setBody("{\"delay\":$measured}")
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
    }

    @Test fun changingApiCoreConfigurationAndRuntimeCannotReusePreviousProbeMetadata() = runBlocking {
        MockWebServer().use { first -> MockWebServer().use { second ->
            val current = AtomicReference("B" to 74L)
            switchingApi(first, { "A" to 65L })
            switchingApi(second, { current.get() })
            val repository = ProxyDashboardRepository(context)

            selectApi(first)
            assertEquals(65L, repository.delay("Route"))
            selectApi(second)
            assertEquals(74L, repository.delay("Route"))
            // The same API can serve a new config/core/runtime with different leaves.
            current.set("C" to 75L)
            prefs.edit().putString("proxySelectedConfig.mihomo", "changed.yaml").commit()
            assertEquals(75L, repository.delay("Route"))
            current.set("D" to 76L)
            prefs.edit().putString("proxyBaseCore", "mihomo-smart").commit()
            assertEquals(76L, repository.delay("Route"))
            current.set("E" to 77L)
            prefs.edit().putString("proxyNetworkSessionId", "replacement-runtime").commit()
            assertEquals(77L, repository.delay("Route"))
            assertEquals(77L, repository.delay("Route")) // An unchanged identity still reuses metadata.

            assertEquals(3, first.requestCount)
            assertEquals(13, second.requestCount) // Four new snapshots and five measurements.
            val secondPaths = (1..13).map { second.takeRequest(2, TimeUnit.SECONDS)!!.requestUrl!!.encodedPath }
            assertFalse(secondPaths.contains("/proxies/A/delay"))
            assertEquals(4, secondPaths.count { it == "/proxies" })
            assertEquals(4, secondPaths.count { it == "/providers/proxies" })
        } }
    }

    @Test fun anOldSnapshotLoadIsRejectedBeforeItCanPolluteTheNewApiCache() = runBlocking {
        MockWebServer().use { first -> MockWebServer().use { second ->
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            switchingApi(first, { "A" to 65L }, entered, release)
            switchingApi(second, { "B" to 74L })
            selectApi(first)
            val repository = ProxyDashboardRepository(context)
            val published = AtomicBoolean(false)
            val failure = AtomicReference<Throwable?>()
            val old = launch(Dispatchers.Default) {
                try {
                    repository.delay("Route")
                    published.set(true)
                } catch (error: IOException) { failure.set(error) }
            }
            try {
                assertTrue(entered.await(3, TimeUnit.SECONDS))
                selectApi(second)
            } finally {
                release.countDown()
            }
            old.join()
            assertFalse(published.get())
            assertTrue(failure.get() is IOException)
            assertEquals("代理或控制接口已变化，请重新测速", failure.get()!!.message)

            assertEquals(74L, repository.delay("Route"))
            assertEquals(74L, repository.delay("Route"))
            assertEquals(1, first.requestCount) // Do not fetch another segment of an obsolete snapshot.
            assertEquals(4, second.requestCount) // One B snapshot, then two B measurements.
            val secondPaths = (1..4).map { second.takeRequest(2, TimeUnit.SECONDS)!!.requestUrl!!.encodedPath }
            assertEquals(listOf("/proxies", "/providers/proxies", "/proxies/B/delay", "/proxies/B/delay"), secondPaths)
        } }
    }
}
