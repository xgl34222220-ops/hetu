package io.github.xgl34222220.hetu

import android.app.Application
import android.os.Looper
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
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** First-state publication uses real controller HTTP; optional work is held behind explicit gates. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, shadows = [
    ControllerRunState90Shadows.RootStatus::class, HomeReadiness93Shadows.Stop::class,
    PanelActionRuntimeShadows.RuntimeSample::class, PanelActionRuntimeShadows.HistoryRecord::class,
    PanelActionRuntimeShadows.NoRoot::class,
])
@LooperMode(LooperMode.Mode.PAUSED)
class HomeFirstSnapshot93Test {
    private lateinit var vm: HetuViewModel
    private lateinit var server: MockWebServer
    private val servers = mutableListOf<MockWebServer>()
    private val gates = mutableListOf<CountDownLatch>()
    private val requests = Collections.synchronizedList(mutableListOf<String>())
    @Volatile private var response: (RecordedRequest) -> MockResponse = ::normal

    @Before fun prepare() {
        ControllerRunState90Shadows.RootStatus.reset()
        PanelActionRuntimeShadows.RuntimeSample.reset()
        PanelActionRuntimeShadows.HistoryRecord.reset()
        PanelActionRuntimeShadows.NoRoot.reset()
        HomeReadiness93Shadows.Stop.reset()
        server = server { response(it) }
        val app = ApplicationProvider.getApplicationContext<Application>()
        app.getSharedPreferences("hetu", 0).edit().clear()
            .putBoolean("proxyCustomApiEnabled", true).putString("proxyCustomApiHost", "127.0.0.1")
            .putInt("proxyCustomApiPort", server.port).putBoolean("proxyRootWanted", true)
            .putBoolean("proxyRootRuntimeRunning", true).putBoolean("proxyApiHistoryEnabled", false).commit()
        ControllerRunState90Shadows.RootStatus.reply = """{"ok":true,"running":true,"pid":77,"dataPlaneHealthy":true}"""
        PanelActionRuntimeShadows.RuntimeSample.value = ProxyRuntimeSnapshot(running = true, pid = 77, rssBytes = 77_000L)
        vm = HetuViewModel(app)
    }

    @After fun finish() {
        vm.viewModelScope.cancel()
        gates.forEach { it.countDown() }
        PanelActionRuntimeShadows.RuntimeSample.release()
        PanelActionRuntimeShadows.HistoryRecord.release()
        eventually("All canceled work finishes") { vm.viewModelScope.coroutineContext[Job]!!.children.none { it.isActive } }
        servers.forEach { it.shutdown() }
        assertEquals(0, PanelActionRuntimeShadows.NoRoot.calls)
    }

    private fun server(handler: (RecordedRequest) -> MockResponse) = MockWebServer().apply {
        dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request.path.orEmpty()
                return handler(request)
            }
        }
        start(); servers += this
    }
    private fun body(value: String) = MockResponse().setBody(value)
    private fun normal(req: RecordedRequest): MockResponse = when {
        req.path == "/configs" -> body("""{"mode":"rule"}""")
        req.path == "/proxies" -> body("""{"proxies":{"A":{"type":"Selector","now":"shared","all":["shared"]},"shared":{"type":"Shadowsocks"}}}""")
        req.path == "/providers/proxies" -> body("""{"providers":{}}""")
        req.path == "/connections" -> body("""{"connections":[]}""")
        req.path == "/version" -> body("""{"version":"old"}""")
        req.requestUrl?.pathSegments?.lastOrNull() == "delay" -> body("""{"delay":31}""")
        else -> MockResponse().setResponseCode(404)
    }
    private fun gate() = CountDownLatch(1).also { gates += it }
    private fun CountDownLatch.hold() { check(await(8, TimeUnit.SECONDS)) { "Auxiliary response gate not released" } }
    private fun eventually(message: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(6)
        while (System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(10)
        }
        assertTrue("$message; requests=$requests", condition())
    }
    private fun refresh() = vm.viewModelScope.launch { vm.refreshNow() }
    private fun await(job: Job) = eventually("Refresh finishes") { job.isCompleted }

    @Test fun slowResourceSampleDoesNotHoldTheFirstSnapshotOrNodeActions() {
        PanelActionRuntimeShadows.RuntimeSample.pause = true
        val job = refresh()
        eventually("Resource sample reached") { PanelActionRuntimeShadows.RuntimeSample.pending != null }
        assertFalse(job.isCompleted)
        assertTrue("Resource wait must not hold first snapshot publication", vm.loadedOnce)
        assertTrue(vm.state.running)
        assertTrue(vm.state.panelReady)
        assertEquals("A", vm.state.groups.single().name)
        vm.testNode("shared")
        eventually("Current node measurement works while resource read is paused") { vm.delays["shared"] == 31L }
        PanelActionRuntimeShadows.RuntimeSample.release()
        await(job)
    }

    @Test fun lateProviderResponseCannotRestoreContentAfterTheActualStopAction() {
        val held = gate(); val entered = CountDownLatch(1); val providerReads = AtomicInteger()
        response = { req -> if (req.path == "/providers/proxies" && providerReads.incrementAndGet() == 2) {
            entered.countDown(); held.hold()
            body("""{"providers":{"old":{"vehicleType":"HTTP","proxies":["shared"]}}}""")
        } else normal(req) }
        val job = refresh()
        eventually("Supplemental provider read reached") { entered.count == 0L }
        assertTrue("Provider wait must not hold first snapshot publication", vm.loadedOnce)
        assertTrue(vm.state.running)
        vm.toggle()
        eventually("User stop is confirmed while the old provider is held") {
            HomeReadiness93Shadows.Stop.calls == 1 && vm.operation == null && !vm.state.running
        }
        held.countDown(); await(job)
        assertFalse(vm.state.running)
        assertTrue(vm.providers.isEmpty())
        assertEquals("", vm.coreVersion)
    }

    @Test fun oldVersionCannotOverwriteTheReplacementController() {
        val held = gate(); val entered = CountDownLatch(1)
        response = { req -> if (req.path == "/version") {
            entered.countDown(); held.hold(); body("""{"version":"obsolete"}""")
        } else normal(req) }
        val old = refresh()
        eventually("Version read held") { entered.count == 0L }
        assertTrue("Version wait must not hold first snapshot publication", vm.loadedOnce)
        val replacement = server { req -> if (req.path == "/version") body("""{"version":"current"}""") else normal(req) }
        vm.prefs.edit().putInt("proxyCustomApiPort", replacement.port).commit()
        vm.bumpSettings()
        val fresh = refresh(); await(fresh)
        assertEquals("current", vm.coreVersion)
        held.countDown(); await(old)
        assertEquals("current", vm.coreVersion)
    }

    @Test fun olderResourceSampleCannotOverwriteANewerConfirmedProcess() {
        PanelActionRuntimeShadows.RuntimeSample.pause = true
        val old = refresh()
        eventually("Old resource sample held") { PanelActionRuntimeShadows.RuntimeSample.pending != null }
        assertTrue("Old process sample wait must not hold first snapshot publication", vm.loadedOnce)
        PanelActionRuntimeShadows.RuntimeSample.pause = false
        PanelActionRuntimeShadows.RuntimeSample.value = ProxyRuntimeSnapshot(running = true, pid = 88, rssBytes = 88_000L)
        ControllerRunState90Shadows.RootStatus.reply = """{"ok":true,"running":true,"pid":88,"dataPlaneHealthy":true}"""
        vm.prefs.edit().putLong("proxyRootHealthProbeElapsed", 0L).commit()
        val fresh = refresh(); await(fresh)
        assertEquals(88, vm.state.corePid)
        assertEquals(88_000L, vm.runtime.rssBytes)
        PanelActionRuntimeShadows.RuntimeSample.value = ProxyRuntimeSnapshot(running = true, pid = 77, rssBytes = 999_000L)
        PanelActionRuntimeShadows.RuntimeSample.release(); await(old)
        assertEquals(88, vm.state.corePid)
        assertEquals(88_000L, vm.runtime.rssBytes)
    }

    @Test fun canceledAuxiliaryReadCannotPublishAfterTheVisibleSnapshot() {
        PanelActionRuntimeShadows.RuntimeSample.pause = true
        val job = refresh()
        eventually("Resource sample held") { PanelActionRuntimeShadows.RuntimeSample.pending != null }
        assertTrue("Cancelable auxiliary wait must not hold first snapshot publication", vm.loadedOnce)
        val visible = vm.state
        val runtime = vm.runtime
        job.cancel()
        PanelActionRuntimeShadows.RuntimeSample.release(); await(job)
        assertSame(visible, vm.state)
        assertEquals(runtime, vm.runtime)
        assertEquals("", vm.coreVersion)
        assertTrue(vm.providers.isEmpty())
    }
}
