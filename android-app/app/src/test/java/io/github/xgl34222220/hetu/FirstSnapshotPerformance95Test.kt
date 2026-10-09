package io.github.xgl34222220.hetu

import android.app.Application
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.io.File
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Real HTTP concurrency is the gate; wall-clock measurements are diagnostic evidence only. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, shadows = [
    ControllerRunState90Shadows.RootStatus::class,
    PanelActionRuntimeShadows.RuntimeSample::class,
    PanelActionRuntimeShadows.HistoryRecord::class,
    PanelActionRuntimeShadows.NoRoot::class,
])
@LooperMode(LooperMode.Mode.PAUSED)
class FirstSnapshotPerformance95Test {
    private lateinit var app: Application
    private lateinit var vm: HetuViewModel
    private lateinit var server: MockWebServer
    private val snapshotPaths = setOf("/configs", "/proxies", "/providers/proxies", "/connections")
    private val requests = Collections.synchronizedList(mutableListOf<String>())
    private val gates = mutableListOf<CountDownLatch>()
    @Volatile private var response: (RecordedRequest) -> MockResponse = ::normal

    @Before fun prepare() {
        ControllerRunState90Shadows.RootStatus.reset()
        PanelActionRuntimeShadows.RuntimeSample.reset()
        PanelActionRuntimeShadows.HistoryRecord.reset()
        PanelActionRuntimeShadows.NoRoot.reset()
        app = ApplicationProvider.getApplicationContext()
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    requests += request.path.orEmpty()
                    return response(request)
                }
            }
            start()
        }
        app.getSharedPreferences("hetu", 0).edit().clear()
            .putBoolean("proxyCustomApiEnabled", true)
            .putString("proxyCustomApiHost", "127.0.0.1")
            .putInt("proxyCustomApiPort", server.port)
            .putBoolean("proxyRootWanted", true)
            .putBoolean("proxyRootRuntimeRunning", true)
            .putBoolean("proxyApiHistoryEnabled", false).commit()
        ControllerRunState90Shadows.RootStatus.reply =
            """{"ok":true,"running":true,"pid":77,"dataPlaneHealthy":true}"""
        PanelActionRuntimeShadows.RuntimeSample.value = ProxyRuntimeSnapshot(running = true, pid = 77)
        vm = HetuViewModel(app)
    }

    @After fun finish() {
        gates.forEach { it.countDown() }
        vm.viewModelScope.cancel()
        PanelActionRuntimeShadows.RuntimeSample.release()
        PanelActionRuntimeShadows.HistoryRecord.release()
        eventually("Canceled refreshes finish") {
            vm.viewModelScope.coroutineContext[Job]!!.children.none { it.isActive }
        }
        server.shutdown()
        assertEquals("Fixture must never issue a Root command", 0, PanelActionRuntimeShadows.NoRoot.calls)
    }

    private fun normal(request: RecordedRequest): MockResponse = MockResponse().setBody(when (request.path) {
        "/configs" -> """{"mode":"rule"}"""
        "/proxies" -> """{"proxies":{"SELECT":{"type":"Selector","now":"node","all":["node"]},"node":{"type":"Shadowsocks"}}}"""
        "/providers/proxies" -> """{"providers":{}}"""
        "/connections" -> """{"connections":[],"downloadTotal":2048,"uploadTotal":1024,"memory":4096}"""
        "/version" -> """{"version":"fixture"}"""
        else -> "{}"
    })

    private fun gate() = CountDownLatch(1).also { gates += it }
    private fun CountDownLatch.hold() {
        check(await(8, TimeUnit.SECONDS)) { "Controller response gate was not released" }
    }
    private fun eventually(message: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(10)
        }
        assertTrue("$message; requests=$requests", condition())
    }
    private fun refresh() = vm.viewModelScope.launch { vm.refreshNow() }

    @Test fun fourControllerReadsOverlapBeforeAnyFirstSnapshotIsPublished() {
        val held = gate()
        val entered = Collections.synchronizedSet(mutableSetOf<String>())
        response = { request ->
            if (request.path in snapshotPaths) {
                entered += request.path!!
                held.hold()
            }
            normal(request)
        }
        val initial = vm.state
        val job = refresh()
        // Serial implementations cannot enter all four handlers while every response is held.
        eventually("All four independent controller reads arrive before any response is released") {
            entered.size == snapshotPaths.size
        }
        assertFalse(job.isCompleted)
        assertFalse(vm.loadedOnce)
        assertSame("A partial controller snapshot must remain invisible", initial, vm.state)
        var mainCallbackRan = false
        Handler(Looper.getMainLooper()).post { mainCallbackRan = true }
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue("Waiting for HTTP must leave the main looper free", mainCallbackRan)
        held.countDown()
        eventually("Complete first snapshot is published") { job.isCompleted && vm.loadedOnce }
        assertTrue(vm.state.panelReady)
        assertFalse(vm.state.controllerReadFailed)
        assertEquals("node", vm.state.groups.single().now)
        assertEquals(2048L, vm.state.downloadTotal)
        assertEquals("rule", vm.state.trafficMode)
    }

    @Test fun oneFailedConcurrentReadCannotPublishSuccessfulPartialControllerData() {
        val held = gate()
        val entered = Collections.synchronizedSet(mutableSetOf<String>())
        response = { request ->
            if (request.path in snapshotPaths) {
                entered += request.path!!
                held.hold()
            }
            if (request.path == "/connections") MockResponse().setResponseCode(503)
            else normal(request)
        }
        val job = refresh()
        eventually("All four reads are pending together") { entered.size == snapshotPaths.size }
        assertFalse(vm.loadedOnce)
        held.countDown()
        eventually("Failed complete read is reported") { job.isCompleted && vm.loadedOnce }
        assertTrue(vm.state.running)
        assertTrue(vm.state.dataPlaneHealthy)
        assertFalse(vm.state.panelReady)
        assertTrue(vm.state.controllerReadFailed)
        assertTrue(vm.state.controllerError.contains("503"))
        assertTrue(vm.state.groups.isEmpty())
        assertTrue(vm.state.connections.isEmpty())
        assertEquals("", vm.state.trafficMode)
        assertEquals(0L, vm.state.downloadTotal)
        assertEquals(0L, vm.state.uploadTotal)
        assertTrue("Failed counters must not enter history", PanelActionRuntimeShadows.HistoryRecord.samples.isEmpty())
    }

    @Test fun changedNetworkGenerationRejectsTheLateConcurrentFirstSnapshot() {
        val held = gate()
        val entered = Collections.synchronizedSet(mutableSetOf<String>())
        response = { request ->
            if (request.path in snapshotPaths) {
                entered += request.path!!
                held.hold()
            }
            normal(request)
        }
        val initial = vm.state
        val job = refresh()
        eventually("Original generation has four reads in flight") { entered.size == snapshotPaths.size }
        vm.prefs.edit().putLong("proxyNetworkEpoch", 1L).commit()
        vm.bumpSettings()
        held.countDown()
        eventually("Superseded refresh finishes") { job.isCompleted }
        assertSame(initial, vm.state)
        assertFalse("Old HTTP responses cannot acknowledge a new network generation", vm.loadedOnce)
        assertTrue(vm.providers.isEmpty())
        assertTrue(PanelActionRuntimeShadows.HistoryRecord.samples.isEmpty())
    }

    @Test fun reportSerialReferenceAndConcurrentSnapshotWithEqualResponseDelays() = runBlocking {
        val fixtureDelayMs = 150L
        val controller = ProxyComposeController(app)
        val api = MihomoControllerClient(app)
        // Warm class loading/config projection before either measured path.
        assertTrue(controller.state().panelReady)
        response = { request -> normal(request).apply {
            if (request.path in snapshotPaths) setBodyDelay(fixtureDelayMs, TimeUnit.MILLISECONDS)
        } }
        val serialMs = mutableListOf<Double>()
        val concurrentMs = mutableListOf<Double>()
        fun serialReference(): Double {
            val start = System.nanoTime()
            api.configs(); api.proxies(); api.proxyProviders(); api.connections()
            return (System.nanoTime() - start) / 1_000_000.0
        }
        suspend fun concurrentSnapshot(): Double {
            val start = System.nanoTime()
            val fresh = controller.state()
            val elapsed = (System.nanoTime() - start) / 1_000_000.0
            assertTrue(fresh.panelReady)
            assertEquals("node", fresh.groups.single().now)
            assertEquals(2048L, fresh.downloadTotal)
            return elapsed
        }
        repeat(3) { index ->
            // Alternate order so the reference is not always penalized by the first sample.
            if (index % 2 == 0) {
                serialMs += serialReference()
                concurrentMs += concurrentSnapshot()
            } else {
                concurrentMs += concurrentSnapshot()
                serialMs += serialReference()
            }
        }
        val serialMedian = serialMs.sorted()[1]
        val concurrentMedian = concurrentMs.sorted()[1]
        val report = JSONObject()
            .put("scenario", "Four real loopback HTTP responses, equal body delay, three alternating warmed samples")
            .put("scope", "Serial reference is four raw reads; concurrent path includes full controller snapshot parsing. Root is stubbed. This is not device first-frame or jank evidence.")
            .put("fixtureDelayMs", fixtureDelayMs)
            .put("serialMs", JSONArray(serialMs)).put("concurrentMs", JSONArray(concurrentMs))
            .put("serialMedianMs", serialMedian).put("concurrentMedianMs", concurrentMedian)
            .put("observedSpeedup", serialMedian / concurrentMedian)
            .put("hardGate", "fourControllerReadsOverlapBeforeAnyFirstSnapshotIsPublished")
            .put("timingThresholdEnforced", false)
        val file = File("build/outputs/ui93/first-snapshot-performance95.json")
        check(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs())
        file.writeText(report.toString(2) + "\n")
        println("HETU_FIRST_SNAPSHOT_PERFORMANCE95 " + report.toString())
        println("HETU_FIRST_SNAPSHOT_PERFORMANCE95_REPORT " + file.absolutePath)
    }
    @Test fun fastAuthenticationFailureClosesUnresponsiveSiblingSockets() {
        val siblings = CountDownLatch(3)
        response = { request ->
            when (request.path) {
                "/configs" -> {
                    check(siblings.await(4, TimeUnit.SECONDS))
                    MockResponse().setResponseCode(401)
                }
                "/proxies", "/providers/proxies", "/connections" -> {
                    siblings.countDown()
                    MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.NO_RESPONSE)
                }
                else -> normal(request)
            }
        }
        val start = System.nanoTime()
        val job = refresh()
        eventually("A fast 401 must close silent siblings rather than wait their 6.5/12-second HTTP timeouts") {
            job.isCompleted && vm.loadedOnce
        }
        assertEquals(0L, siblings.count)
        assertTrue(vm.state.controllerReadFailed)
        assertTrue(vm.state.controllerError.contains("401"))
        assertFalse(vm.state.panelReady)
        assertTrue(vm.state.groups.isEmpty())
        println("PERFORMANCE95_AUTH_FAILURE_MS " + (System.nanoTime() - start) / 1_000_000.0)
    }

    @Test fun cancellingConcurrentSnapshotClosesAllSilentSocketsWithoutPublishing() {
        val entered = Collections.synchronizedSet(mutableSetOf<String>())
        response = { request ->
            if (request.path in snapshotPaths) {
                entered += request.path!!
                MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.NO_RESPONSE)
            } else normal(request)
        }
        val initial = vm.state
        val job = refresh()
        eventually("All four silent reads are in flight") { entered.size == snapshotPaths.size }
        val start = System.nanoTime()
        job.cancel()
        eventually("Cancellation must finish cleanup without waiting for server replies") { job.isCompleted }
        assertSame(initial, vm.state)
        assertFalse(vm.loadedOnce)
        assertTrue(PanelActionRuntimeShadows.HistoryRecord.samples.isEmpty())
        println("PERFORMANCE95_SNAPSHOT_CANCEL_MS " + (System.nanoTime() - start) / 1_000_000.0)
    }

    @Test fun successfulSnapshotWithinAnExistingBudgetKeepsThatBudgetUsable() = runBlocking {
        latencyProbeOperation(3_000) {
            val inherited = LatencyProbeBudget.CURRENT.get()
            val api = MihomoControllerClient(app)
            val version = controllerSnapshotOperation { async { api.version() }.await() }
            assertEquals("fixture", version.optString("version"))
            assertSame(inherited, LatencyProbeBudget.CURRENT.get())
            assertFalse("Successful cleanup must not close the enclosing operation", inherited.expired())
            assertEquals("fixture", api.version().optString("version"))
        }
        assertNull(LatencyProbeBudget.CURRENT.get())
    }

    @Test fun concurrentSnapshotCannotRestartAnInheritedShortDeadline() = runBlocking {
        response = { MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.NO_RESPONSE) }
        val started = System.nanoTime()
        val error = try {
            latencyProbeOperation(250) {
                controllerSnapshotOperation { async { MihomoControllerClient(app).version() }.await() }
            }
            null
        } catch (failure: java.io.IOException) { failure }
        assertNotNull("The original short deadline must close the silent socket", error)
        assertTrue("A nested snapshot must not replace the250ms budget with the6.5s HTTP timeout",
            TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 2_000)
        assertNull(LatencyProbeBudget.CURRENT.get())
    }

}
