package io.github.xgl34222220.hetu

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.LinkProperties
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implements
import org.robolectric.annotation.Implementation
import org.robolectric.shadows.ShadowNetwork
import org.robolectric.shadows.ShadowNetworkCapabilities
import org.robolectric.shadow.api.Shadow
import org.robolectric.util.ReflectionHelpers
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ThreadPoolExecutor
import java.io.File

@Implements(value = RootBridge::class, isInAndroidSdk = false)
class DiagnosticsBridgeShadow {
    companion object {
        var integrity = "degraded"
        var fault = "listener-11053-udp"
        var deny = false
        var repairExit = 91
        var duringHealth: (() -> Unit)? = null
        val commands = mutableListOf<String>()
        @JvmStatic @Implementation fun hasRoot(context: Context): Boolean = true
        @JvmStatic @Implementation fun rootShell(context: Context, command: String, timeout: Long): RootBridge.Result {
            commands += command
            require(!command.contains("service.d") && !command.contains("'start'") && !command.contains("'stop'"))
            if (command.contains("repair-network")) return RootBridge.Result(repairExit, "")
            require(command.endsWith("'network-health'"))
            val callback = duringHealth; duringHealth = null; callback?.invoke()
            if (deny) return RootBridge.Result(126, "private-error-message-with-TOPSECRET")
            return RootBridge.Result(0, JSONObject().put("ok", true).put("networkIntegrity", integrity)
                .put("networkFault", fault).put("dataPlaneHealthy", integrity == "healthy").toString())
        }
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, shadows = [DiagnosticsBridgeShadow::class])
class NetworkDiagnosticsTest {
    private lateinit var controller: ServiceController<ProxyNetworkMatchService>
    private lateinit var service: ProxyNetworkMatchService
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val prefs get() = app.getSharedPreferences("hetu", 0)
    @Before fun prepare() {
        prefs.edit().clear().commit()
        DiagnosticsBridgeShadow.commands.clear(); DiagnosticsBridgeShadow.deny = false
        DiagnosticsBridgeShadow.integrity = "degraded"; DiagnosticsBridgeShadow.fault = "listener-11053-udp"
        DiagnosticsBridgeShadow.repairExit = 91; DiagnosticsBridgeShadow.duringHealth = null
        controller = Robolectric.buildService(ProxyNetworkMatchService::class.java).create(); service = controller.get()
        ReflectionHelpers.getField<ExecutorService>(service, "metrics").shutdownNow()
        ReflectionHelpers.getField<ExecutorService>(service, "worker").shutdownNow()
        prefs.edit().putBoolean("proxyRootWanted", true).putString("proxyNetworkIntegrity", "degraded")
            .putString("proxyNetworkFault", "watchdog-missing").putString("proxyPolicyEgressState", "reachable").commit()
    }
    @After fun finish() {
        controller.destroy()
        ReflectionHelpers.getField<ExecutorService>(service, "journalWorker").awaitTermination(5, TimeUnit.SECONDS)
    }
    private fun call(name: String, vararg args: Any) {
        val method = ProxyNetworkMatchService::class.java.declaredMethods.single { it.name == name }
        method.isAccessible = true
        val worker = Executors.newSingleThreadExecutor()
        try { worker.submit { method.invoke(service, *args) }.get() } finally { worker.shutdownNow() }
        ReflectionHelpers.getField<ExecutorService>(service, "journalWorker").submit {}.get()
    }
    private fun recent() = ProxyNetworkJournal(app).recent()
    private fun readyNetwork() {
        val network = ShadowNetwork.newInstance(101)
        val callback = ReflectionHelpers.getField<ConnectivityManager.NetworkCallback>(service, "cb")
        callback.onAvailable(network)
        val caps = NetworkCapabilities(); val shadow = Shadow.extract<ShadowNetworkCapabilities>(caps)
        shadow.addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        shadow.addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
        callback.onCapabilitiesChanged(network, caps)
        callback.onLinkPropertiesChanged(network, LinkProperties().apply { interfaceName = "wlan0" })
    }

    @Test fun failedHealthReadHasFreshUnknownStateTimestampAndTraceWithoutClaimingHealth() {
        DiagnosticsBridgeShadow.deny = true; call("checkLiveNetworkIntegrity")
        assertEquals("unknown", prefs.getString("proxyNetworkIntegrity", ""))
        assertEquals("health-read-failed", prefs.getString("proxyNetworkFault", ""))
        assertTrue(prefs.getLong("proxyNetworkCheckedAt", 0) > 0)
        val id = prefs.getString("proxyNetworkHealthTraceId", "")!!
        assertEquals(36, id.length); assertTrue(recent().contains(id))
        assertTrue(recent().contains("java.io.IOException")); assertFalse(recent().contains("TOPSECRET"))
        assertEquals("reachable", prefs.getString("proxyPolicyEgressState", ""))
    }
    @Test fun laterHealthSuccessKeepsTheEarlierFailureRecord() {
        DiagnosticsBridgeShadow.deny = true; call("checkLiveNetworkIntegrity")
        val failed = prefs.getString("proxyNetworkHealthTraceId", "")!!
        DiagnosticsBridgeShadow.deny = false; DiagnosticsBridgeShadow.integrity = "healthy"
        DiagnosticsBridgeShadow.fault = ""; call("checkLiveNetworkIntegrity")
        assertEquals("healthy", prefs.getString("proxyNetworkIntegrity", ""))
        assertTrue(recent().contains(failed)); assertTrue(recent().contains("health-read-failed"))
        assertFalse(prefs.contains("proxyNetworkHealthReadError"))
    }
    @Test fun failedJournalWriteDoesNotChangeHealthOrWantedIntent() {
        val blocked = File(app.noBackupFilesDir, "blocked-journal-parent"); blocked.writeText("fixture")
        ReflectionHelpers.setField(service, "journal", ProxyNetworkJournal(blocked, 1024))
        DiagnosticsBridgeShadow.integrity = "healthy"; DiagnosticsBridgeShadow.fault = ""
        call("checkLiveNetworkIntegrity")
        assertEquals("healthy", prefs.getString("proxyNetworkIntegrity", ""))
        assertEquals("IOException", prefs.getString("proxyNetworkJournalError", ""))
        assertEquals(1L, prefs.getLong("proxyNetworkJournalDropped", 0))
        assertTrue(prefs.getBoolean("proxyRootWanted", false))
    }
    @Test fun oldHealthAfterNetworkChangeIsRecordedButCannotOverwriteCurrentObservation() {
        DiagnosticsBridgeShadow.integrity = "healthy"; DiagnosticsBridgeShadow.fault = ""
        DiagnosticsBridgeShadow.duringHealth = {
            ReflectionHelpers.getField<ConnectivityManager.NetworkCallback>(service, "cb")
                .onAvailable(ShadowNetwork.newInstance(202))
        }
        call("checkLiveNetworkIntegrity")
        assertEquals("degraded", prefs.getString("proxyNetworkIntegrity", ""))
        assertEquals("watchdog-missing", prefs.getString("proxyNetworkFault", ""))
        assertTrue(recent().contains("STALE_RESULT")); assertTrue(recent().contains("DISCARDED"))
    }
    @Test fun repairExitFailureIsVisibleAndDoesNotTurnIntoNetworkHealthSuccess() {
        val route = ReflectionHelpers.getField<NetworkEpoch<android.net.Network>>(service, "networkEvents").snapshot()
        call("repairLiveNetworkIntegrity", route)
        assertEquals(91, prefs.getInt("proxyNetworkRepairExit", 0))
        assertEquals("degraded", prefs.getString("proxyNetworkIntegrity", ""))
        assertTrue(recent().contains("REPAIR_RESULT")); assertTrue(recent().contains("\"code\":91"))
        val command = DiagnosticsBridgeShadow.commands.first { it.contains("repair-network") }
        assertTrue(command.endsWith(">/dev/null 2>&1")); assertFalse(command.endsWith("|| true"))
    }
    @Test fun saturatedJournalQueueDoesNotBlockCallbacksAndReportsDroppedEvidence() {
        val writer = ReflectionHelpers.getField<ThreadPoolExecutor>(service, "journalWorker")
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        writer.execute { entered.countDown(); release.await(5, TimeUnit.SECONDS) }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        try {
            val callback = ReflectionHelpers.getField<ConnectivityManager.NetworkCallback>(service, "cb")
            repeat(140) { callback.onAvailable(ShadowNetwork.newInstance(1000 + it)) }
            assertEquals(128, writer.queue.size)
            assertTrue(prefs.getLong("proxyNetworkJournalDropped", 0) > 0)
            assertTrue(prefs.getBoolean("proxyRootWanted", false))
            assertTrue(DiagnosticsBridgeShadow.commands.isEmpty())
        } finally { release.countDown(); writer.shutdown() }
        assertTrue(writer.awaitTermination(15, TimeUnit.SECONDS))
    }
    @Test fun alternateTargetSuccessKeepsFirstFailureAndCannotMaskStructuralFault() {
        readyNetwork(); var requests = 0
        ReflectionHelpers.setField(service, "egressRequest", ProxyNetworkMatchService.EgressRequest { _, _ ->
            requests++; if (requests == 1) 503 else 204
        })
        call("probeEgressIfPending")
        assertEquals(2, requests)
        assertEquals("reachable", prefs.getString("proxyPolicyEgressState", ""))
        assertEquals("degraded", prefs.getString("proxyNetworkIntegrity", ""))
        val results = recent().lineSequence().filter { it.startsWith("{") }.map { JSONObject(it) }.toList()
        val targets = results.filter { it.getString("stage") == "EGRESS_TARGET_RESULT" }
        assertEquals(listOf(503, 204), targets.map { it.getInt("code") })
        assertEquals(listOf("GOOGLE_204", "CLOUDFLARE_204"), targets.map { it.getString("target") })
        assertEquals(1, targets.map { it.getString("parent") }.toSet().size)
        assertTrue(DiagnosticsBridgeShadow.commands.isEmpty())
    }
    @Test fun handoverDuringFirstProbeDiscardsResultAndDoesNotRequestSecondTarget() {
        readyNetwork(); var requests = 0
        ReflectionHelpers.setField(service, "egressRequest", ProxyNetworkMatchService.EgressRequest { _, _ ->
            requests++
            ReflectionHelpers.getField<ConnectivityManager.NetworkCallback>(service, "cb")
                .onAvailable(ShadowNetwork.newInstance(102))
            503
        })
        call("probeEgressIfPending")
        assertEquals(1, requests)
        assertEquals("unverified", prefs.getString("proxyPolicyEgressState", ""))
        assertTrue(recent().contains("STALE_RESULT"))
        assertTrue(DiagnosticsBridgeShadow.commands.isEmpty())
    }
    @Test fun concurrentDroppedEventsCannotRegressTheSharedCounter() {
        val method = ProxyNetworkMatchService::class.java.getDeclaredMethod("recordJournalDrop", ProxyNetworkJournal.Event::class.java, String::class.java)
        method.isAccessible = true; val writers = Executors.newFixedThreadPool(8)
        try { (1..128).map { writers.submit { method.invoke(service, ProxyNetworkJournal.capture(
            ProxyNetworkJournal.Stage.NETWORK_CHANGE, 1, ProxyNetworkJournal.Outcome.WAITING, 0, null, null), "queue-full") } }.forEach { it.get() } }
        finally { writers.shutdownNow() }
        assertEquals(128L, prefs.getLong("proxyNetworkJournalDropped", 0)); assertEquals("degraded", prefs.getString("proxyNetworkIntegrity", ""))
        assertTrue(prefs.getBoolean("proxyRootWanted", false)); assertTrue(ProxyNetworkJournal.uuid(prefs.getString("proxyNetworkJournalLastDroppedId", "")))
    }
    @Test fun droppedCounterSaturatesAndDoesNotWrapOrOverwriteNewerPersistedCount() {
        val m = ProxyNetworkMatchService::class.java.getDeclaredMethod("recordJournalDrop", ProxyNetworkJournal.Event::class.java, String::class.java); m.isAccessible = true
        val e = ProxyNetworkJournal.capture(ProxyNetworkJournal.Stage.NETWORK_CHANGE, 1, ProxyNetworkJournal.Outcome.WAITING, 0, null, null)
        prefs.edit().putLong("proxyNetworkJournalDropped", 500L).commit(); m.invoke(service, e, "queue-full")
        assertEquals(501L, prefs.getLong("proxyNetworkJournalDropped", 0))
        prefs.edit().putLong("proxyNetworkJournalDropped", Long.MAX_VALUE).commit(); repeat(2) { m.invoke(service, e, "queue-full") }
        assertEquals(Long.MAX_VALUE, prefs.getLong("proxyNetworkJournalDropped", 0))
    }
    @Test fun quotaFailureMakesTraceGapVisibleWithoutChangingNetworkHealthOrWanted() {
        val dir = File(app.noBackupFilesDir, "quota-fixture"); val j = ProxyNetworkJournal(dir, 1024, 2048L)
        repeat(100) { try { j.append(ProxyNetworkJournal.capture(ProxyNetworkJournal.Stage.NETWORK_CHANGE, 1,
            ProxyNetworkJournal.Outcome.WAITING, 0, null, null)) } catch (_: ProxyNetworkJournal.JournalFullException) {} }
        ReflectionHelpers.setField(service, "journal", j)
        DiagnosticsBridgeShadow.integrity = "healthy"; DiagnosticsBridgeShadow.fault = ""; call("checkLiveNetworkIntegrity")
        val id = prefs.getString("proxyNetworkHealthTraceId", "")!!
        assertEquals("healthy", prefs.getString("proxyNetworkIntegrity", "")); assertTrue(prefs.getBoolean("proxyRootWanted", false))
        assertEquals("JournalFullException", prefs.getString("proxyNetworkJournalError", "")); assertEquals(1L, prefs.getLong("proxyNetworkJournalDropped", 0))
        assertEquals(id, prefs.getString("proxyNetworkJournalLastDroppedId", "")); assertFalse(j.recent().contains(id))
        assertTrue(j.report(prefs).contains("生成 ID 不等于已落盘")); assertTrue(j.report(prefs).contains("storagePaused=true"))
    }
}
