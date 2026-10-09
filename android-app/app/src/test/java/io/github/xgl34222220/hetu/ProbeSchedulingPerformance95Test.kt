package io.github.xgl34222220.hetu

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Loopback transport timings plus deterministic scheduling guards; no device/network mutation. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ProbeSchedulingPerformance95Test {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val names = (0 until 18).map { "node$it" }
    private val group = ProxyGroupUi("Route", "URLTest", "node0", names.map { ProxyNodeUi(it) })
    private val proxies = JSONObject().put("proxies", JSONObject().apply {
        names.forEach { put(it, JSONObject().put("type", "Shadowsocks")) }
    }).toString()
    @Before fun reset() { app.getSharedPreferences("hetu", 0).edit().clear().commit() }
    private fun configure(server: MockWebServer) {
        app.getSharedPreferences("hetu", 0).edit()
            .putBoolean("proxyCustomApiEnabled", true).putString("proxyCustomApiHost", "127.0.0.1")
            .putInt("proxyCustomApiPort", server.port).putBoolean("proxyRootRuntimeRunning", true)
            .putBoolean("proxyRootWanted", true).commit()
    }
    private fun response(req: RecordedRequest) = MockResponse().setBody(when(req.requestUrl!!.encodedPath) {
        "/proxies" -> proxies
        "/providers/proxies" -> """{"providers":{}}"""
        else -> """{"delay":21}"""
    })

    @Test fun healthyTailStartsBeforeSlowFirstWorkerFinishesWithoutIncreasingConcurrency() = runBlocking {
        val release = CountDownLatch(1)
        val tail = CountDownLatch(1)
        val active = AtomicInteger()
        val peak = AtomicInteger()
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(req: RecordedRequest): MockResponse {
                    if (req.requestUrl!!.encodedPath.endsWith("/delay")) {
                        val count = active.incrementAndGet(); peak.updateAndGet { maxOf(it, count) }
                        try {
                            when (req.requestUrl!!.pathSegments[1]) {
                                "node0" -> check(release.await(6, TimeUnit.SECONDS))
                                "node17" -> tail.countDown()
                            }
                            Thread.sleep(20)
                        } finally { active.decrementAndGet() }
                    }
                    return response(req)
                }
            }
            server.start(); configure(server)
            val result = async(Dispatchers.IO) { ProxyDashboardRepository(app).groupDelay(group, names) }
            try {
                assertTrue("Free workers must reach the last healthy node before node0 returns", tail.await(4, TimeUnit.SECONDS))
                assertFalse(result.isCompleted)
                assertTrue("Per-wave concurrency must stay at six", peak.get() <= 6)
            } finally { release.countDown() }
            assertEquals(names.toSet(), result.await().keys)
        }
    }

    @Test fun mixedLatencyWaveRecordsRealElapsedComparisonAgainstThePreviousBatchScheduler() = runBlocking {
        val active = AtomicInteger(); val peak = AtomicInteger()
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(req: RecordedRequest): MockResponse {
                    if (req.requestUrl!!.encodedPath.endsWith("/delay")) {
                        val count = active.incrementAndGet(); peak.updateAndGet { maxOf(it, count) }
                        try {
                            val index = req.requestUrl!!.pathSegments[1].removePrefix("node").toInt()
                            Thread.sleep(if (index % 6 == 0) 400L else 25L)
                        } finally { active.decrementAndGet() }
                    }
                    return response(req)
                }
            }
            server.start(); configure(server)
            // Same real client and inputs. This is the exact previous batch barrier,
            // retained only as a measurement reference, never as production fallback.
            val baselineStarted = System.nanoTime()
            val previous = latencyProbeOperation {
                val api = MihomoControllerClient(app)
                api.proxies(); api.proxyProviders()
                val values = linkedMapOf<String, Long>()
                for (chunk in names.chunked(6)) coroutineScope {
                    chunk.map { node -> async { node to api.delay(node) } }.awaitAll().forEach { values[it.first] = it.second }
                }
                values
            }
            val previousMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - baselineStarted)
            val started = System.nanoTime()
            val current = ProxyDashboardRepository(app).groupDelay(group, names)
            val currentMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
            assertEquals(previous, current)
            assertTrue(peak.get() <= 6)
            val report = JSONObject().put("fixture", "18 loopback leaves; three 400ms, fifteen25ms")
                .put("previousBatchMs", previousMs).put("currentWorkerMs", currentMs)
                .put("maxConcurrent", peak.get()).put("results", current.size)
                .put("deadlineMs", LatencyProbeBudget.DEFAULT_TIMEOUT_MS)
                .put("deviceFpsMeasured", false)
            File("build/outputs/ui93/probe-scheduling-performance95.json").apply { parentFile.mkdirs(); writeText(report.toString(2)) }
            println("PERFORMANCE95 $report")
            // Structural gate tests above are the hard performance contract; these
            // actual times are reported without a flaky host-speed percentage gate.
        }
    }
}
