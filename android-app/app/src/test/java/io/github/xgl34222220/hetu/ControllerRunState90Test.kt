package io.github.xgl34222220.hetu

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
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
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import org.robolectric.util.ReflectionHelpers
import java.io.IOException
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, shadows = [
    ControllerRunState90Shadows.RootStatus::class,
    PanelActionRuntimeShadows.NoRoot::class,
])
class ControllerRunState90Test {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val prefs get() = app.getSharedPreferences("hetu", 0)
    private lateinit var server: MockWebServer

    @Before fun prepare() {
        ControllerRunState90Shadows.RootStatus.reset()
        ShadowSystemClock.advanceBy(Duration.ofSeconds(1))
        PanelActionRuntimeShadows.NoRoot.reset()
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                    "/configs" -> MockResponse().setBody("{\"mode\":\"rule\"}")
                    "/proxies" -> MockResponse().setBody("{\"proxies\":{}}")
                    "/providers/proxies" -> MockResponse().setBody("{\"providers\":{}}")
                    "/connections" -> MockResponse().setBody("{\"connections\":[]}")
                    else -> MockResponse().setResponseCode(404)
                }
            }
            start()
        }
        prefs.edit().clear().putBoolean("proxyRootWanted", true)
            .putBoolean("proxyRootRuntimeRunning", true)
            .putBoolean("proxyCustomApiEnabled", true)
            .putString("proxyCustomApiHost", "127.0.0.1")
            .putInt("proxyCustomApiPort", server.port).commit()
    }

    @After fun finish() {
        ControllerRunState90Shadows.RootStatus.gate?.countDown()
        server.shutdown()
        assertEquals("No fixture may mutate Root", 0, PanelActionRuntimeShadows.NoRoot.calls)
    }

    @Test fun confirmedStoppedSupersedesTheCachedRunningHintAndSkipsControllerReads() = runBlocking {
        ControllerRunState90Shadows.RootStatus.reply = "{\"running\":false,\"message\":\"已停止\"}"
        val observed = ProxyComposeController(app).state()
        assertFalse(observed.running)
        assertFalse(prefs.getBoolean("proxyRootRuntimeRunning", true))
        assertFalse(observed.panelReady)
        assertFalse(observed.controllerReadFailed)
        assertFalse(observed.dataPlaneHealthy)
        assertFalse(observed.healthObserved)
        assertEquals("已停止", observed.message)
        assertEquals(0, server.requestCount)
        assertEquals(1, ControllerRunState90Shadows.RootStatus.reads)
    }

    @Test fun confirmedRunningSupersedesAStoppedHintAndReadsTheController() = runBlocking {
        prefs.edit().putBoolean("proxyRootRuntimeRunning", false).commit()
        ControllerRunState90Shadows.RootStatus.reply = "{\"running\":true,\"dataPlaneHealthy\":true,\"pid\":77}"
        val controller = ProxyComposeController(app)
        val observed = controller.state()
        assertTrue(observed.running)
        assertTrue(observed.panelReady)
        assertTrue(observed.dataPlaneHealthy)
        assertTrue(observed.healthObserved)
        assertEquals(77, observed.corePid)
        assertTrue(prefs.getBoolean("proxyRootRuntimeRunning", false))
        assertEquals(4, server.requestCount)
        ShadowSystemClock.advanceBy(Duration.ofSeconds(2))
        ControllerRunState90Shadows.RootStatus.reply = "{\"running\":true,\"dataPlaneHealthy\":true,\"pid\":88}"
        val cached = controller.state()
        assertEquals("Throttled reads preserve the last confirmed process", 77, cached.corePid)
        assertTrue(cached.healthObserved)
        assertEquals(1, ControllerRunState90Shadows.RootStatus.reads)
        prefs.edit().putLong("proxyRootHealthProbeElapsed", 0L).commit()
        assertEquals(88, controller.state().corePid)
        assertEquals(2, ControllerRunState90Shadows.RootStatus.reads)
    }

    @Test fun missingRunningFieldRetainsOnlyTheHintAndDoesNotClaimDataPlaneHealth() = runBlocking {
        val observed = ProxyComposeController(app).state()
        assertTrue(observed.running)
        assertTrue(observed.panelReady)
        assertFalse(observed.dataPlaneHealthy)
        assertFalse(observed.healthObserved)
        assertTrue(observed.message.isNotBlank())
        assertEquals(4, server.requestCount)
    }

    @Test fun failedRootObservationRetainsTheHintButRejectsOldHealthyCache() = runBlocking {
        prefs.edit().putBoolean("proxyRootDataPlaneHealthy", true).putBoolean("proxyRootHealthObserved", true)
            .putInt("proxyRootRuntimeSchema", 42).commit()
        ControllerRunState90Shadows.RootStatus.fail = true
        val observed = ProxyComposeController(app).state()
        assertTrue(observed.running)
        assertTrue(observed.panelReady)
        assertFalse(observed.dataPlaneHealthy)
        assertFalse(observed.healthObserved)
        assertEquals(42, observed.runtimeSchema)
        assertEquals("运行状态暂时无法确认", observed.message)
        assertEquals(4, server.requestCount)
    }

    @Test fun missingRunningWithoutARunningHintRemainsStopped() = runBlocking {
        prefs.edit().putBoolean("proxyRootRuntimeRunning", false).commit()
        val observed = ProxyComposeController(app).state()
        assertFalse(observed.running)
        assertFalse(observed.panelReady)
        assertFalse(observed.dataPlaneHealthy)
        assertFalse(observed.healthObserved)
        assertFalse(prefs.getBoolean("proxyRootRuntimeRunning", true))
        assertEquals(0, server.requestCount)
    }

    private val control get() = ReflectionHelpers.getStaticField<ProxyControlEpoch>(RootProxyManager::class.java, "CONTROL_LOCK")
    private val replyA = "{\"ok\":true,\"running\":true,\"pid\":77,\"runtimeSchema\":4,\"ipv4Rules\":true,\"dnsMode\":\"a\",\"dataPlaneHealthy\":true,\"controllerPort\":29091}"
    private val replyB = "{\"ok\":true,\"running\":true,\"pid\":88,\"runtimeSchema\":4,\"ipv4Rules\":false,\"dnsMode\":\"b\",\"dataPlaneHealthy\":false,\"controllerPort\":29107}"
    private fun heldReply(failure: Boolean = false): Pair<CountDownLatch, CountDownLatch> {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        ControllerRunState90Shadows.RootStatus.reply = replyA
        ControllerRunState90Shadows.RootStatus.fail = failure
        ControllerRunState90Shadows.RootStatus.entered = entered
        ControllerRunState90Shadows.RootStatus.gate = release
        return entered to release
    }
    private fun writeReplacement(stopped: Boolean = false, changeSession: Boolean = true) {
        control.lock()
        try {
            val edit = prefs.edit().putBoolean("proxyRootWanted", !stopped)
                .putBoolean("proxyRootRuntimeRunning", !stopped).putInt("proxyRootObservedPid", if (stopped) 0 else 88)
                .putLong("proxyRootHealthProbeElapsed", 777L).putBoolean("proxyRootHealthObserved", false)
                .putBoolean("proxyRootDataPlaneHealthy", false).putBoolean("proxyRootStatusRunning", !stopped)
                .putInt("proxyRootRuntimeSchema", 4).putBoolean("proxyRootIpv4Rules", false)
                .putString("proxyRootDnsMode", "b").putInt("proxyControllerPort", 29107)
            if (changeSession) edit.putString("proxyNetworkSessionId", "replacement").putLong("proxyNetworkEpoch", 9L)
            assertTrue(edit.commit())
        } finally { control.unlock() }
    }
    private fun assertSuperseded(result: Result<ProxyComposeState>) {
        assertTrue("Late observation must be rejected, not published as fallback", result.exceptionOrNull() is IOException)
    }
    private suspend fun nextReplacementRead(controller: ProxyComposeController, stopped: Boolean = false) {
        ControllerRunState90Shadows.RootStatus.entered = null
        ControllerRunState90Shadows.RootStatus.gate = null
        ControllerRunState90Shadows.RootStatus.fail = false
        ControllerRunState90Shadows.RootStatus.reply = if (stopped) "{\"ok\":true,\"running\":false,\"runtimeSchema\":4,\"networkIntegrity\":\"stopped\"}" else replyB
        prefs.edit().putLong("proxyRootHealthProbeElapsed", 0L).commit()
        val next = controller.state()
        assertEquals(!stopped, next.running)
        assertEquals(if (stopped) 0 else 88, next.corePid)
        assertEquals(!stopped, prefs.getBoolean("proxyRootRuntimeRunning", stopped))
    }

    @Test fun lateRootReadCannotOverwriteAReplacementControlSnapshot() = runBlocking {
        val controller = ProxyComposeController(app); val (entered, release) = heldReply()
        val pending = async(Dispatchers.IO) { runCatching { controller.state() } }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            writeReplacement(); val before = prefs.all
            release.countDown(); assertSuperseded(pending.await())
            assertEquals(before, prefs.all)
            assertEquals(0, server.requestCount)
            nextReplacementRead(controller)
        } finally { release.countDown() }
    }

    @Test fun lateRootReadCannotRewriteRuntimeAfterAnExplicitStop() = runBlocking {
        val controller = ProxyComposeController(app); val (entered, release) = heldReply()
        val pending = async(Dispatchers.IO) { runCatching { controller.state() } }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            writeReplacement(stopped = true); val before = prefs.all
            release.countDown(); assertSuperseded(pending.await())
            assertEquals(before, prefs.all)
            assertFalse(ProxyStatusBridge.rootProxyRunning(app))
            assertEquals(0, server.requestCount)
            nextReplacementRead(controller, stopped = true)
        } finally { release.countDown() }
    }

    @Test fun lateFailedRootReadCannotRewriteReplacementThroughItsCachedFallback() = runBlocking {
        val controller = ProxyComposeController(app); val (entered, release) = heldReply(failure = true)
        val pending = async(Dispatchers.IO) { runCatching { controller.state() } }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            writeReplacement(); val before = prefs.all
            release.countDown(); assertSuperseded(pending.await())
            assertEquals(before, prefs.all)
            assertEquals(0, server.requestCount)
            nextReplacementRead(controller)
        } finally { release.countDown() }
    }

    @Test fun unchangedRootObservationPublishesItsCompleteSnapshotAndLivePort() = runBlocking {
        val controller = ProxyComposeController(app); val (entered, release) = heldReply()
        val pending = async(Dispatchers.IO) { controller.state() }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS)); release.countDown()
            val next = pending.await()
            assertTrue(next.running); assertTrue(next.panelReady); assertTrue(next.dataPlaneHealthy)
            assertEquals(77, prefs.getInt("proxyRootObservedPid", 0))
            assertEquals(29091, prefs.getInt("proxyControllerPort", 0))
            assertEquals("a", prefs.getString("proxyRootDnsMode", ""))
            assertTrue(prefs.getLong("proxyRootHealthProbeElapsed", 0L) > 0L)
            assertEquals(4, server.requestCount)
        } finally { release.countDown() }
    }

    @Test fun changedSessionRejectsRootObservationWithoutAControlTransaction() = runBlocking {
        val controller = ProxyComposeController(app); val (entered, release) = heldReply()
        val pending = async(Dispatchers.IO) { runCatching { controller.state() } }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            prefs.edit().putString("proxyNetworkSessionId", "replacement").commit(); val before = prefs.all
            release.countDown(); assertSuperseded(pending.await())
            assertEquals(before, prefs.all); assertEquals(0, server.requestCount)
            nextReplacementRead(controller)
        } finally { release.countDown() }
    }

    @Test fun changedPhysicalEpochRejectsRootObservationWithoutAControlTransaction() = runBlocking {
        val controller = ProxyComposeController(app); val (entered, release) = heldReply()
        val pending = async(Dispatchers.IO) { runCatching { controller.state() } }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            prefs.edit().putLong("proxyNetworkEpoch", 9L).commit(); val before = prefs.all
            release.countDown(); assertSuperseded(pending.await())
            assertEquals(before, prefs.all); assertEquals(0, server.requestCount)
            nextReplacementRead(controller)
        } finally { release.countDown() }
    }

    @Test fun aCachedReadDuringAnotherThreadsControlTransactionCannotPublishRunning() = runBlocking {
        ProxyConfigLibrary(app).selected(ProxyRuntimeProfile.Core.MIHOMO)
        prefs.edit().putLong("proxyRootHealthProbeElapsed", android.os.SystemClock.elapsedRealtime())
            .putInt("proxyRootObservedPid", 88).commit()
        val before = prefs.all; val controller = ProxyComposeController(app)
        control.lock()
        try {
            assertSuperseded(async(Dispatchers.IO) { runCatching { controller.state() } }.await())
            assertEquals(before, prefs.all); assertEquals(0, ControllerRunState90Shadows.RootStatus.reads)
            assertEquals(0, server.requestCount)
        } finally { control.unlock() }
        assertTrue(controller.state().panelReady)
        assertEquals(0, ControllerRunState90Shadows.RootStatus.reads)
    }

    @Test fun controllerHttpFinishingAfterSameConfigurationControlCannotReturnAnOldSnapshot() = runBlocking {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val original = server.dispatcher
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.path == "/configs") {
                    entered.countDown(); check(release.await(8, TimeUnit.SECONDS))
                }
                return original.dispatch(request)
            }
        }
        ControllerRunState90Shadows.RootStatus.reply = replyA
        val controller = ProxyComposeController(app)
        val pending = async(Dispatchers.IO) { runCatching { controller.state() } }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertEquals(77, prefs.getInt("proxyRootObservedPid", 0))
            writeReplacement(changeSession = false); val before = prefs.all
            release.countDown(); assertSuperseded(pending.await())
            assertEquals(before, prefs.all)
            assertEquals(4, server.requestCount)
            nextReplacementRead(controller)
        } finally { release.countDown() }
    }
}
