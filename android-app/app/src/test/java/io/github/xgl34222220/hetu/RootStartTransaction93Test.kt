package io.github.xgl34222220.hetu

import android.app.Application
import android.content.Context
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowSystemClock
import java.io.ByteArrayInputStream
import java.io.IOException
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Only the privileged transport is isolated. Configuration, preparation,
 * startInternal, autostart, handoff and compensation are the real application code. */
@Implements(value = RootBridge::class, isInAndroidSdk = false)
class RootStartTransaction93BridgeShadow {
    companion object {
        val commands = CopyOnWriteArrayList<String>()
        val nativeStarts = AtomicInteger()
        val nativeStops = AtomicInteger()
        val readTokens = CopyOnWriteArrayList<String>()
        @Volatile var cancelToken = ""
        @Volatile var tokenReadEntered: CountDownLatch? = null
        @Volatile var tokenReadRelease: CountDownLatch? = null
        @Volatile var bootInstallEntered: CountDownLatch? = null
        @Volatile var bootInstallRelease: CountDownLatch? = null
        @Volatile var deploymentAdvanceMillis = 0L
        @Volatile var cleanupOk = true

        @JvmStatic @Implementation fun hasRoot(context: Context): Boolean = true
        @JvmStatic @Implementation
        fun rootShell(context: Context, command: String, timeout: Long): RootBridge.Result {
            // Enforce the real bridge contract instead of silently accepting a
            // helper's impossible sub-second timeout in this boundary fixture.
            require(timeout in 1_000L..300_000L)
            commands += command
            if (command == "if [ -e '/data/adb/hetu/run/start-cancel-generation' ]; then cat '/data/adb/hetu/run/start-cancel-generation'; else printf ''; fi") {
                // Capture the old receipt before holding this transport read. A
                // concurrent Stop changes the entity token while this old reply waits.
                val captured = cancelToken
                readTokens += captured
                tokenReadEntered?.countDown()
                check(tokenReadRelease?.await(12, TimeUnit.SECONDS) != false) { "Cancel-token read gate not released" }
                return RootBridge.Result(0, captured)
            }
            if (command.contains("cancel-boot")) {
                cancelToken = "193-9300"
                return RootBridge.Result(0, "")
            }
            if (command.contains("exec '/data/adb/hetu/hetu-root.sh' 'stop'")) {
                nativeStops.incrementAndGet()
                return RootBridge.Result(0, if (cleanupOk) """{"ok":true,"running":false}"""
                    else """{"ok":false,"running":true,"message":"fixture cleanup denied"}""")
            }
            if (command.contains("exec '/data/adb/hetu/hetu-root.sh' 'start'")) {
                check(command.contains("export HETU_START_CANCEL_TOKEN=" + RootBridge.quote(cancelToken) + "; ")) {
                    "Every native start must carry the exact captured cancellation receipt, including an explicit empty receipt"
                }
                nativeStarts.incrementAndGet()
                return RootBridge.Result(0, """{"ok":true,"running":true,"message":"fixture native transaction completed"}""")
            }
            if (command.contains("core.pid") && command.contains("readlink"))
                return RootBridge.Result(0, "0\n\n") // Actual start path, never the already-running shortcut.
            if (command.startsWith("set -e; test \"\$(id -u)\" = 0; test -x") && command.contains("/data/adb/service.d")) {
                check(nativeStarts.get() == 1) { "Autostart gate must be after native success" }
                bootInstallEntered?.countDown()
                check(bootInstallRelease?.await(12, TimeUnit.SECONDS) != false) { "Autostart install gate not released" }
                return RootBridge.Result(0, "")
            }
            if (command.startsWith("set -e; mkdir -p") && command.contains("/data/adb/hetu/bin") && command.contains("startup-config")) {
                if (deploymentAdvanceMillis > 0L) {
                    check(deploymentAdvanceMillis <= timeout) { "Fixture may not outlive the actual root timeout" }
                    ShadowSystemClock.advanceBy(Duration.ofMillis(deploymentAdvanceMillis))
                }
                return RootBridge.Result(0, "")
            }
            throw AssertionError("Unexpected privileged command in full start transaction: $command")
        }
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, shadows = [RootStartTransaction93BridgeShadow::class])
class RootStartTransaction93Test {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val prefs get() = app.getSharedPreferences("hetu", Context.MODE_PRIVATE)
    private val workers = Executors.newFixedThreadPool(3)
    private val gates = mutableListOf<CountDownLatch>()
    private lateinit var source: ProxyConfigLibrary.Entry
    private val sourceYaml = """
        mixed-port: 7890
        mode: rule
        proxies:
          - name: fixture-node
            type: socks5
            server: 127.0.0.1
            port: 1080
        proxy-groups:
          - name: fixture-group
            type: select
            proxies: [fixture-node]
        rules:
          - MATCH,fixture-group
    """.trimIndent() + "\n"

    @Before fun prepare() {
        RootStartTransaction93BridgeShadow.commands.clear()
        RootStartTransaction93BridgeShadow.nativeStarts.set(0)
        RootStartTransaction93BridgeShadow.nativeStops.set(0)
        RootStartTransaction93BridgeShadow.readTokens.clear()
        RootStartTransaction93BridgeShadow.cancelToken = ""
        RootStartTransaction93BridgeShadow.tokenReadEntered = null
        RootStartTransaction93BridgeShadow.tokenReadRelease = null
        RootStartTransaction93BridgeShadow.bootInstallEntered = null
        RootStartTransaction93BridgeShadow.bootInstallRelease = null
        RootStartTransaction93BridgeShadow.deploymentAdvanceMillis = 0L
        RootStartTransaction93BridgeShadow.cleanupOk = true
        DnsVpnService.running = false
        prefs.edit().clear().putBoolean("proxyRootWanted", false)
            .putBoolean("proxyRootRuntimeRunning", false).putBoolean("proxyRootAutoStart", false)
            .putBoolean("proxyAdblockChain", false).putBoolean("proxyAdblockFallbackEnabled", false)
            .putBoolean("vpnWanted", false).putBoolean("hetuLegacyRetired", true)
            .putBoolean("proxyCnIpDirect", false).putString("proxyAppScope", "core").commit()
        android.provider.Settings.Global.putInt(app.contentResolver, android.provider.Settings.Global.BOOT_COUNT, 93)
        val configs = ProxyConfigLibrary(app)
        source = configs.importConfig(ProxyRuntimeProfile.Core.MIHOMO, "root-transaction-93.yaml",
            ByteArrayInputStream(sourceYaml.toByteArray(Charsets.UTF_8)))
        // A local fixture payload is staged by the actual importer, never executed.
        ProxyCoreStore(app).importCore(ProxyRuntimeProfile.Core.MIHOMO, ByteArrayInputStream(ByteArray(1024) { 1 }))
    }

    @After fun finish() {
        gates.forEach { it.countDown() }
        RootStartTransaction93BridgeShadow.bootInstallRelease?.countDown()
        RootStartTransaction93BridgeShadow.tokenReadRelease?.countDown()
        workers.shutdownNow()
        assertTrue("All full-path fixture workers drained", workers.awaitTermination(8, TimeUnit.SECONDS))
        DnsVpnService.running = false
    }

    private fun gate() = CountDownLatch(1).also { gates += it }
    private fun profile() = ProxyRuntimeProfile.load(prefs)
    private fun assertActuallyPrepared(manager: RootProxyManager) {
        assertTrue("Real prepare wrote a startup configuration", manager.startupFile().isFile)
        assertTrue(manager.startupConfig().contains("fixture-node"))
        assertEquals("Runtime generation must preserve imported source", sourceYaml, ProxyConfigLibrary(app).read(source))
        assertTrue("Real deployment command was reached", RootStartTransaction93BridgeShadow.commands.any {
            it.startsWith("set -e; mkdir -p") && it.contains("/data/adb/hetu/bin") && it.contains("startup-config")
        })
    }
    private fun stopWhileControlHeld(): IOException {
        val stopped = workers.submit<Result<JSONObject>> { runCatching { RootProxyManager(app).stop() } }
            .get(5, TimeUnit.SECONDS)
        assertTrue("Stop must revoke, then report incomplete cleanup while start owns the control lock", stopped.exceptionOrNull() is IOException)
        assertFalse("Revocation must persist before the blocked transaction is released", prefs.getBoolean("proxyRootWanted", true))
        return stopped.exceptionOrNull() as IOException
    }

    @Test fun automaticMatchingFirstStartReachesNativeWithSharedRecoveryIdentity() {
        prefs.edit().putBoolean("networkMatchEnabled", true).commit()
        val manager = RootProxyManager(app)
        val result = workers.submit<JSONObject> { manager.startIfAutomationAllowed(profile()) { true } }
            .get(10, TimeUnit.SECONDS)
        assertActuallyPrepared(manager)
        assertTrue(result.getBoolean("ok"))
        assertEquals(1, RootStartTransaction93BridgeShadow.nativeStarts.get())
        val start = RootStartTransaction93BridgeShadow.commands.single { it.contains("exec '/data/adb/hetu/hetu-root.sh' 'start'") }
        assertTrue("Automatic first start participates in the native boot ledger", start.contains("export HETU_AUTOMATIC_RECOVERY_ID="))
        assertEquals(1, prefs.getInt("proxyRecoveryStarts", -1))
        assertTrue(prefs.getLong("proxyRecoveryDeadlineElapsed", 0L) > prefs.getLong("proxyRecoveryStartedElapsed", 0L))
        assertEquals("automation", prefs.getString("proxyRootSessionOwner", ""))
    }

    @Test fun exhaustedAppAdmissionRejectsMatchingBeforePreparationOrNativeDispatch() {
        prefs.edit().putBoolean("networkMatchEnabled", true).putInt("proxyRecoveryBootCount", 93)
            .putLong("proxyRecoveryManualGeneration", 0L).putLong("proxyRecoveryStartedElapsed", SystemClock.elapsedRealtime())
            .putLong("proxyRecoveryDeadlineElapsed", SystemClock.elapsedRealtime() + 300_000L)
            .putInt("proxyRecoveryStarts", 6).putBoolean("proxyRecoveryEpisodeComplete", false).commit()
        val result = workers.submit<Result<JSONObject>> {
            runCatching { RootProxyManager(app).startIfAutomationAllowed(profile()) { true } }
        }.get(5, TimeUnit.SECONDS)
        assertTrue(result.exceptionOrNull() is IOException)
        assertTrue("Admission refusal occurs before any privileged probe or deployment", RootStartTransaction93BridgeShadow.commands.isEmpty())
        assertEquals(0, RootStartTransaction93BridgeShadow.nativeStarts.get())
        assertEquals(6, prefs.getInt("proxyRecoveryStarts", -1))
    }

    @Test fun stopDuringAutostartInstallAfterNativeSuccessCannotReturnReady() {
        prefs.edit().putBoolean("proxyRootAutoStart", true).commit()
        val entered = gate(); val release = gate()
        RootStartTransaction93BridgeShadow.bootInstallEntered = entered
        RootStartTransaction93BridgeShadow.bootInstallRelease = release
        val manager = RootProxyManager(app)
        val starting = workers.submit<Result<JSONObject>> { runCatching { manager.startManual(profile(), null) } }
        assertTrue("Actual autostart install is held after native success", entered.await(8, TimeUnit.SECONDS))
        assertActuallyPrepared(manager)
        assertEquals(1, RootStartTransaction93BridgeShadow.nativeStarts.get())
        assertTrue(prefs.getBoolean("proxyRootRuntimeRunning", false))
        stopWhileControlHeld()
        release.countDown()
        val result = starting.get(8, TimeUnit.SECONDS)
        assertTrue("Autostart warning must not swallow a revoked start", result.exceptionOrNull() is IOException)
        assertEquals("Native success must be compensated exactly once", 1, RootStartTransaction93BridgeShadow.nativeStops.get())
        assertFalse(prefs.getBoolean("proxyRootWanted", true))
        assertFalse(prefs.getBoolean("proxyRootRuntimeRunning", true))
        assertFalse(prefs.getString("proxyRootLastStartupTiming", "")!!.contains("outcome=ready"))
    }

    @Test fun stopAfterActualHandoffBeforeNativeRestoresCoordinatorOwnership() {
        prefs.edit().putBoolean("proxyAdblockFallbackEnabled", true).commit()
        val entered = gate(); val release = gate(); val handoffs = AtomicInteger()
        val manager = RootProxyManager(app)
        val starting = workers.submit<Result<JSONObject>> { runCatching {
            manager.startManual(profile(), RootProxyManager.Progress { stage ->
                if (stage.startsWith("启动核心并等待")) {
                    assertTrue("Actual coordinator enter precedes this stage", prefs.getBoolean("proxyAdblockChainActive", false))
                    handoffs.incrementAndGet(); entered.countDown()
                    check(release.await(12, TimeUnit.SECONDS))
                }
            })
        } }
        assertTrue("Actual handoff stage reached", entered.await(8, TimeUnit.SECONDS))
        assertActuallyPrepared(manager)
        assertEquals(0, RootStartTransaction93BridgeShadow.nativeStarts.get())
        stopWhileControlHeld(); release.countDown()
        assertTrue(starting.get(8, TimeUnit.SECONDS).exceptionOrNull() is IOException)
        assertEquals(1, handoffs.get())
        assertEquals(0, RootStartTransaction93BridgeShadow.nativeStarts.get())
        assertFalse("Pre-native revocation must release real coordinator ownership", prefs.getBoolean("proxyAdblockChainActive", true))
        assertFalse(prefs.contains("proxyResumeDnsAfterChain"))
        assertFalse(prefs.getBoolean("proxyRootWanted", true))
    }

    @Test fun rejectedPostNativePublicationWithFailedCleanupRetainsUnknownHandoff() {
        prefs.edit().putBoolean("proxyAdblockFallbackEnabled", true).commit()
        RootStartTransaction93BridgeShadow.cleanupOk = false
        val reachedNative = AtomicBoolean(false)
        val manager = RootProxyManager(app)
        val result = workers.submit<Result<JSONObject>> { runCatching {
            manager.startManual(profile(), RootProxyManager.Progress { stage ->
                if (stage.startsWith("核心监听与网络接管已就绪")) {
                    assertEquals(1, RootStartTransaction93BridgeShadow.nativeStarts.get())
                    assertTrue(prefs.getBoolean("proxyAdblockChainActive", false))
                    reachedNative.set(true)
                    throw IOException("fixture post-native publication rejected")
                }
            })
        } }.get(10, TimeUnit.SECONDS)
        assertActuallyPrepared(manager)
        assertTrue("Fixture reaches post-native publication, not a preparation failure", reachedNative.get())
        assertTrue(result.exceptionOrNull() is IOException)
        assertEquals(1, RootStartTransaction93BridgeShadow.nativeStops.get())
        assertTrue("Cleanup failure must remain explicitly unknown/running", prefs.getBoolean("proxyRootRuntimeRunning", false))
        assertEquals("unknown", prefs.getString("proxyNetworkIntegrity", ""))
        assertTrue("Unconfirmed native cleanup must not resume a competing filter", prefs.getBoolean("proxyAdblockChainActive", false))
        assertTrue(result.exceptionOrNull()!!.suppressed.any { it.message!!.contains("fixture cleanup denied") })
    }

    @Test fun stopDuringEmptyCancelTokenReadRejectsTheLateManualStart() {
        val entered = gate(); val release = gate()
        RootStartTransaction93BridgeShadow.tokenReadEntered = entered
        RootStartTransaction93BridgeShadow.tokenReadRelease = release
        val manager = RootProxyManager(app)
        val starting = workers.submit<Result<JSONObject>> { runCatching { manager.startManual(profile(), null) } }
        assertTrue("Real start must reach its cancellation-token read after preparation", entered.await(8, TimeUnit.SECONDS))
        assertActuallyPrepared(manager)
        assertEquals("The first transaction captured an actual empty receipt", listOf(""), RootStartTransaction93BridgeShadow.readTokens)
        assertEquals(0, RootStartTransaction93BridgeShadow.nativeStarts.get())
        stopWhileControlHeld()
        assertEquals("Native cancellation changed while the old read was held", "193-9300", RootStartTransaction93BridgeShadow.cancelToken)
        release.countDown()
        val result = starting.get(8, TimeUnit.SECONDS)
        assertTrue("A late empty-token reply cannot authorize a stopped manual request", result.exceptionOrNull() is IOException)
        assertEquals("Java intent recheck rejects the old reply before native dispatch", 0, RootStartTransaction93BridgeShadow.nativeStarts.get())
        assertEquals(0, RootStartTransaction93BridgeShadow.nativeStops.get())
        assertFalse(prefs.getBoolean("proxyRootWanted", true))
        assertFalse(prefs.getBoolean("proxyRootRuntimeRunning", true))
        assertFalse(prefs.contains("proxyRootManualStartSucceededGeneration"))
        assertFalse(prefs.getString("proxyRootLastStartupTiming", "")!!.contains("outcome=ready"))
    }

    @Test fun nativeAdmissionDeadlineFailureAfterHandoffDoesNotPretendDispatchOccurred() {
        prefs.edit().putBoolean("proxyRootWanted", true).putBoolean("proxyAdblockFallbackEnabled", true).commit()
        RootStartTransaction93BridgeShadow.deploymentAdvanceMillis = 5_000L
        val manager = RootProxyManager(app)
        val reachedHandoff = AtomicBoolean(false)
        val result = workers.submit<Result<JSONObject>> {
            RootProxyManager.beginAutomaticRecoveryScope(SystemClock.elapsedRealtime() + 170_000L)
            try { runCatching { manager.startIfWanted(profile()) {
                if (prefs.getBoolean("proxyAdblockChainActive", false) && reachedHandoff.compareAndSet(false, true))
                    ShadowSystemClock.advanceBy(Duration.ofMillis(1L))
                true
            } } } finally { RootProxyManager.endAutomaticRecoveryScope() }
        }.get(10, TimeUnit.SECONDS)
        assertActuallyPrepared(manager)
        assertTrue("Input must reach real handoff before native admission fails", reachedHandoff.get())
        assertTrue(result.exceptionOrNull() is IOException)
        assertEquals("No privileged native start was dispatched", 0, RootStartTransaction93BridgeShadow.nativeStarts.get())
        assertFalse("An admission error must release the known pre-native handoff", prefs.getBoolean("proxyAdblockChainActive", true))
        assertFalse(prefs.contains("proxyResumeDnsAfterChain"))
    }
}
