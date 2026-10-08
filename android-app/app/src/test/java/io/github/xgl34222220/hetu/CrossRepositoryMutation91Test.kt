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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Separate activities own separate repositories, but mutate the same running core. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, shadows = [PanelActionRuntimeShadows.NoRoot::class])
class CrossRepositoryMutation91Test {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val prefs get() = app.getSharedPreferences("hetu", 0)
    private val testUrl = "https://www.gstatic.com/generate_204"

    @Before fun resetPreferences() {
        prefs.edit().clear().commit()
        PanelActionRuntimeShadows.NoRoot.reset()
        // No test assumes an epoch value or resets the shared production generation.
        // Every held request is released and awaited before the next fixture begins.
    }

    @After fun noRootMutation() {
        assertEquals(0, PanelActionRuntimeShadows.NoRoot.calls)
    }

    private fun api(server: MockWebServer) {
        prefs.edit().putBoolean("proxyCustomApiEnabled", true)
            .putString("proxyCustomApiHost", "127.0.0.1")
            .putInt("proxyCustomApiPort", server.port).commit()
    }

    private fun providers(leaf: String): String = JSONObject().put("providers", JSONObject().put("sub", JSONObject()
        .put("vehicleType", "HTTP").put("testUrl", testUrl).put("expectedStatus", "204")
        .put("proxies", JSONArray().put(JSONObject().put("name", leaf).put("type", "Shadowsocks"))))).toString()

    private suspend fun mutate(repository: ProxyDashboardRepository, kind: String): Throwable? = try {
        when (kind) {
            "select" -> repository.select("Route", "B")
            "provider" -> repository.refreshProvider("sub")
            "subscriptions" -> repository.refreshSubscriptions()
            else -> error("Unknown fixture operation")
        }
        null
    } catch (error: MihomoControllerClient.ControllerHttpException) { error }

    private fun assertMutationResult(kind: String, success: Boolean, error: Throwable?) {
        if (success || kind == "subscriptions") assertNull(error)
        else assertEquals(401, (error as MihomoControllerClient.ControllerHttpException).statusCode)
    }

    private fun matchesMutation(request: RecordedRequest, kind: String): Boolean = when (kind) {
        "select" -> request.requestUrl!!.encodedPath == "/proxies/Route" &&
            runCatching { JSONObject(request.body.readUtf8()).getString("name") == "B" }.getOrDefault(false)
        "provider", "subscriptions" -> request.requestUrl!!.encodedPath == "/providers/proxies/sub" && request.body.size == 0L
        else -> false
    }

    @Test fun otherRepositorySelectionsAndProviderUpdatesRejectLateWebsiteWavesOnlyAfterSuccess() = runBlocking {
        for (kind in listOf("select", "provider", "subscriptions")) for (success in listOf(false, true)) {
            MockWebServer().use { proxy -> MockWebServer().use { controller ->
                val entered = CountDownLatch(3)
                val release = CountDownLatch(1)
                val writes = AtomicInteger()
                val unexpectedWrites = AtomicInteger()
                proxy.dispatcher = object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse {
                        entered.countDown()
                        check(release.await(6, TimeUnit.SECONDS)) { "Website fixture was not released" }
                        return MockResponse().setResponseCode(204)
                    }
                }
                controller.dispatcher = object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse = if (request.method == "PUT") {
                        writes.incrementAndGet()
                        if (matchesMutation(request, kind)) MockResponse().setResponseCode(if (success) 204 else 401)
                        else { unexpectedWrites.incrementAndGet(); MockResponse().setResponseCode(500) }
                    } else MockResponse().setBody(providers("A"))
                }
                proxy.start(); controller.start(); api(controller)
                prefs.edit().putBoolean("proxyRootWanted", true).putBoolean("proxyRootRuntimeRunning", true)
                    .putInt("proxyControllerPort", proxy.port - MihomoStartupConfig.EGRESS_PROBE_PORT_OFFSET).commit()
                ProxyLatencyTargets.save(prefs, ProxyLatencyTargets.defaults.map { it.copy(url = "http://latency.invalid/held") })
                val observer = ProxyDashboardRepository(app)
                val actor = ProxyDashboardRepository(app)
                supervisorScope {
                    val pending = async(Dispatchers.Default) { runCatching { observer.siteLatencies() } }
                    try {
                        assertTrue(entered.await(3, TimeUnit.SECONDS))
                        assertMutationResult(kind, success, mutate(actor, kind))
                        assertEquals(1, writes.get())
                        assertEquals("Mutation endpoint/body must match the requested operation", 0, unexpectedWrites.get())
                    } finally { release.countDown() }
                    val result = pending.await()
                    if (success) assertTrue("Other repository's confirmed $kind rejects the old website wave", result.exceptionOrNull() is IOException)
                    else assertTrue("Rejected $kind preserves valid website measurements", result.getOrThrow().values.all { it > 0L })
                    assertEquals(3, proxy.requestCount)
                    assertTrue(observer.siteLatencies().values.all { it > 0L })
                    assertEquals(6, proxy.requestCount)
                }
            } }
        }
    }

    @Test fun otherRepositorySelectionsAndProviderUpdatesRejectLateSelectorMapsOnlyAfterSuccess() = runBlocking {
        for (kind in listOf("select", "provider", "subscriptions")) for (success in listOf(false, true)) {
            MockWebServer().use { server ->
                val entered = CountDownLatch(1)
                val release = CountDownLatch(1)
                val writes = AtomicInteger()
                val unexpectedWrites = AtomicInteger()
                server.dispatcher = object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse = when {
                        request.method == "PUT" -> {
                            writes.incrementAndGet()
                            if (matchesMutation(request, kind)) MockResponse().setResponseCode(if (success) 204 else 401)
                            else { unexpectedWrites.incrementAndGet(); MockResponse().setResponseCode(500) }
                        }
                        request.requestUrl!!.encodedPath == "/group/Route/delay" -> {
                            entered.countDown()
                            check(release.await(6, TimeUnit.SECONDS)) { "Selector fixture was not released" }
                            MockResponse().setBody("{\"A\":65,\"B\":74}")
                        }
                        else -> MockResponse().setBody(providers("A"))
                    }
                }
                server.start(); api(server)
                val observer = ProxyDashboardRepository(app)
                val actor = ProxyDashboardRepository(app)
                supervisorScope {
                    val pending = async(Dispatchers.Default) { runCatching { observer.groupDelay("Route") } }
                    try {
                        assertTrue(entered.await(3, TimeUnit.SECONDS))
                        assertMutationResult(kind, success, mutate(actor, kind))
                        assertEquals(1, writes.get())
                        assertEquals("Mutation endpoint/body must match the requested operation", 0, unexpectedWrites.get())
                    } finally { release.countDown() }
                    val result = pending.await()
                    if (success) assertTrue("Other repository's confirmed $kind rejects the old Selector map", result.exceptionOrNull() is IOException)
                    else assertEquals(mapOf("A" to 65L, "B" to 74L), result.getOrThrow())
                    assertEquals(mapOf("A" to 65L, "B" to 74L), observer.groupDelay("Route"))
                }
            }
        }
    }

    @Test fun otherRepositorySuccessfulSelectionRefreshesNestedShortCacheAndFailedSelectionPreservesIt() = runBlocking {
        MockWebServer().use { server ->
            val selected = AtomicReference("A")
            val reject = AtomicBoolean(false)
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.requestUrl!!.encodedPath
                    if (request.method == "PUT" && path == "/proxies/Nested") {
                        if (reject.get()) return MockResponse().setResponseCode(401)
                        selected.set(JSONObject(request.body.readUtf8()).getString("name"))
                        return MockResponse().setResponseCode(204)
                    }
                    return when (path) {
                        "/proxies" -> {
                            val proxies = JSONObject().put("Outer", JSONObject().put("type", "Selector")
                                .put("all", JSONArray(listOf("Nested"))).put("now", "Nested").put("testUrl", testUrl))
                                .put("Nested", JSONObject().put("type", "Selector").put("all", JSONArray(listOf("A", "B")))
                                    .put("now", selected.get()).put("testUrl", testUrl))
                            for (leaf in listOf("A", "B")) proxies.put(leaf, JSONObject().put("name", leaf).put("type", "Shadowsocks"))
                            MockResponse().setBody(JSONObject().put("proxies", proxies).toString())
                        }
                        "/providers/proxies" -> MockResponse().setBody("{\"providers\":{}}")
                        "/proxies/A/delay" -> MockResponse().setBody("{\"delay\":65}")
                        "/proxies/B/delay" -> MockResponse().setBody("{\"delay\":74}")
                        else -> MockResponse().setResponseCode(404)
                    }
                }
            }
            server.start(); api(server)
            val observer = ProxyDashboardRepository(app)
            val actor = ProxyDashboardRepository(app)
            assertEquals(65L, observer.delay("Outer"))
            actor.select("Nested", "B")
            assertEquals("The other page's successful PUT must refresh Outer -> Nested", 74L, observer.delay("Outer"))
            reject.set(true)
            val rejected = try { actor.select("Nested", "A"); null }
                catch (error: MihomoControllerClient.ControllerHttpException) { error }
            assertEquals(401, rejected!!.statusCode)
            assertEquals(74L, observer.delay("Outer"))
            assertEquals(9, server.requestCount)
            val paths = (1..9).map { server.takeRequest(2, TimeUnit.SECONDS)!!.requestUrl!!.encodedPath }
            assertEquals(listOf("/proxies", "/providers/proxies", "/proxies/A/delay", "/proxies/Nested",
                "/proxies", "/providers/proxies", "/proxies/B/delay", "/proxies/Nested", "/proxies/B/delay"), paths)
        }
    }

    @Test fun otherRepositorySingleAndBulkSubscriptionUpdatesRefreshShortCacheButRejectedUpdatesRetainIt() = runBlocking {
        MockWebServer().use { server ->
            val current = AtomicReference("A")
            val reject = AtomicBoolean(false)
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.requestUrl!!.encodedPath
                    if (request.method == "PUT" && path == "/providers/proxies/sub") {
                        if (reject.get()) return MockResponse().setResponseCode(401)
                        current.set(if (current.get() == "A") "B" else "C")
                        return MockResponse().setResponseCode(204)
                    }
                    val leaf = current.get()
                    return when (path) {
                        "/proxies" -> MockResponse().setBody(JSONObject().put("proxies", JSONObject().put("Route", JSONObject()
                            .put("type", "Selector").put("all", JSONArray(listOf(leaf))).put("now", leaf).put("testUrl", testUrl))).toString())
                        "/providers/proxies" -> MockResponse().setBody(providers(leaf))
                        "/providers/proxies/sub/$leaf/healthcheck" -> MockResponse()
                            .setBody("{\"delay\":${mapOf("A" to 65L, "B" to 74L, "C" to 83L).getValue(leaf)}}")
                        else -> MockResponse().setResponseCode(404)
                    }
                }
            }
            server.start(); api(server)
            val observer = ProxyDashboardRepository(app)
            val actor = ProxyDashboardRepository(app)
            assertEquals(65L, observer.delay("A"))
            assertEquals(setOf("B"), actor.refreshProvider("sub")!!.nodes)
            assertEquals("A subscription updated in another page must refresh the cached leaf list", 74L, observer.delay("B"))
            assertEquals(setOf("C"), actor.refreshSubscriptions().single().nodes)
            assertEquals(83L, observer.delay("C"))
            reject.set(true)
            val rejected = try { actor.refreshProvider("sub"); null }
                catch (error: MihomoControllerClient.ControllerHttpException) { error }
            assertEquals(401, rejected!!.statusCode)
            assertEquals(83L, observer.delay("C"))
            assertEquals(setOf("C"), actor.refreshSubscriptions().single().nodes)
            assertEquals(83L, observer.delay("C"))
            assertEquals("C", current.get())
            assertEquals(20, server.requestCount)
            val requests = (1..20).map { server.takeRequest(2, TimeUnit.SECONDS)!! }
            assertEquals(3, requests.count { it.requestUrl!!.encodedPath == "/proxies" })
            assertEquals(4, requests.count { it.method == "PUT" })
            assertEquals(3, requests.count { it.requestUrl!!.encodedPath == "/providers/proxies/sub/C/healthcheck" })
        }
    }
}
