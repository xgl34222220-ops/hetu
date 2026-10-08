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
import java.io.IOException
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.function.BooleanSupplier

/** Only the privileged command replies are isolated. The real manager's wanted
 * start, adoption, stop, locks and publication code run without a Manager shadow. */
@Implements(value = RootBridge::class, isInAndroidSdk = false)
class RootStartIntent93BridgeShadow {
    companion object {
        val commands = CopyOnWriteArrayList<String>()
        @Volatile var cancelFails = false
        @Volatile var integrity = "healthy"
        @Volatile var dataPlaneHealthy = true
        @Volatile var nativeState = "IDLE"
        @Volatile var runningConfirmed = true
        @Volatile var probeHook: (() -> Unit)? = null
        @Volatile var healthEntered: CountDownLatch? = null
        @Volatile var healthRelease: CountDownLatch? = null
        @Volatile var stopEntered: CountDownLatch? = null

        @JvmStatic @Implementation
        fun rootShell(context: Context, command: String, timeout: Long): RootBridge.Result {
            commands += command
            if (command.contains("cancel-boot")) {
                stopEntered?.countDown()
                nativeState = "STOPPED"; runningConfirmed = false
                return RootBridge.Result(if (cancelFails) 126 else 0, "fixture-cancel-failed")
            }
            if (command.contains("'stop'")) return RootBridge.Result(0, "{\"ok\":true,\"running\":false}")
            if (command.contains("core.pid") && command.contains("readlink")) {
                probeHook?.invoke()
                return RootBridge.Result(0, "1\nasset:fixture\n")
            }
            if (command.contains("network-health")) {
                healthEntered?.countDown()
                check(healthRelease?.await(8, TimeUnit.SECONDS) != false)
                return RootBridge.Result(0, JSONObject().put("ok", true)
                    .put("networkIntegrity", integrity).put("dataPlaneHealthy", dataPlaneHealthy).toString())
            }
            if (command.contains("printf ACTIVE")) return RootBridge.Result(0, nativeState)
            if (command.contains("/data/adb/hetu/boot/status"))
                return RootBridge.Result(if (runningConfirmed) 0 else 1, if (runningConfirmed) "1" else "")
            if (command.contains("'status'")) return RootBridge.Result(0,
                "{\"ok\":true,\"running\":false,\"runtimeSchema\":4,\"controllerPort\":29091}")
            throw AssertionError("Unexpected privileged command: $command")
        }
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, shadows = [RootStartIntent93BridgeShadow::class])
class RootStartIntentSafety93Test {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val prefs get() = app.getSharedPreferences("hetu", Context.MODE_PRIVATE)
    private val workers = Executors.newFixedThreadPool(3)
    private val releases = mutableListOf<CountDownLatch>()

    @Before fun prepare() {
        prefs.edit().clear().putBoolean("proxyRootWanted", true).putBoolean("proxyRootRuntimeRunning", true)
            .putBoolean("proxyRootAutoStart", false).commit()
        android.provider.Settings.Global.putInt(app.contentResolver, android.provider.Settings.Global.BOOT_COUNT, 93)
        RootStartIntent93BridgeShadow.commands.clear()
        RootStartIntent93BridgeShadow.cancelFails = false
        RootStartIntent93BridgeShadow.integrity = "healthy"
        RootStartIntent93BridgeShadow.dataPlaneHealthy = true
        RootStartIntent93BridgeShadow.nativeState = "IDLE"
        RootStartIntent93BridgeShadow.runningConfirmed = true
        RootStartIntent93BridgeShadow.probeHook = null
        RootStartIntent93BridgeShadow.healthEntered = null
        RootStartIntent93BridgeShadow.healthRelease = null
        RootStartIntent93BridgeShadow.stopEntered = null
    }

    @After fun finish() {
        releases.forEach { it.countDown() }
        RootStartIntent93BridgeShadow.healthRelease?.countDown()
        workers.shutdownNow()
        assertTrue("All fixture work must drain", workers.awaitTermination(5, TimeUnit.SECONDS))
    }

    private fun profile() = ProxyRuntimeProfile.load(prefs)
    private fun latch() = CountDownLatch(1).also { releases += it }
    private fun probeCount() = RootStartIntent93BridgeShadow.commands.count { it.contains("core.pid") && it.contains("readlink") }

    @Test fun stopInsideActualWantedNetworkDecisionCannotRepublishWanted() {
        val calls = AtomicInteger()
        val result = workers.submit<Result<JSONObject>> {
            val manager = RootProxyManager(app)
            runCatching { manager.startIfWanted(profile(), BooleanSupplier {
                calls.incrementAndGet()
                assertTrue(manager.stop().getBoolean("ok"))
                true
            }) }
        }.get(8, TimeUnit.SECONDS)
        assertFalse("A Stop inside the real wanted network decision must remain revoked", prefs.getBoolean("proxyRootWanted", true))
        assertEquals("A revoked entry must not probe or deploy a new start", 0, probeCount())
        assertTrue(result.exceptionOrNull() is IOException || result.getOrNull()?.optBoolean("cancelled") == true)
        assertEquals(1, calls.get())
    }

    @Test fun changedNetworkAfterActualProbeCannotPublishAlreadyRunningSuccess() {
        prefs.edit().putBoolean("proxyRootRuntimeRunning", false).commit()
        val current = AtomicBoolean(true)
        RootStartIntent93BridgeShadow.probeHook = { current.set(false) }
        val result = workers.submit<Result<JSONObject>> {
            runCatching { RootProxyManager(app).startIfWanted(profile(), BooleanSupplier { current.get() }) }
        }.get(8, TimeUnit.SECONDS)
        assertEquals(1, probeCount())
        assertFalse("An obsolete network decision cannot publish runtime success", prefs.getBoolean("proxyRootRuntimeRunning", false))
        assertTrue("The actual already-running publication must recheck its network decision", result.exceptionOrNull() is IOException)
    }

    @Test fun stopDuringActualAdoptionHealthCannotReviveWanted() {
        prefs.edit().putBoolean("proxyRootAutoStart", true).commit()
        val entered = latch(); val release = latch()
        RootStartIntent93BridgeShadow.healthEntered = entered
        RootStartIntent93BridgeShadow.healthRelease = release
        val adoption = workers.submit<Boolean> { RootProxyManager(app).adoptBootRuntime() }
        assertTrue("Real adoption must query the complete data plane", entered.await(5, TimeUnit.SECONDS))
        val started = SystemClock.elapsedRealtime()
        val stopping = workers.submit<Result<JSONObject>> { runCatching { RootProxyManager(app).stop() } }
        val limit = System.nanoTime() + TimeUnit.SECONDS.toNanos(4)
        while (!stopping.isDone && System.nanoTime() < limit) {
            ShadowSystemClock.advanceBy(Duration.ofMillis(100)); Thread.sleep(10)
        }
        assertTrue("Stop must return while the unrelated health reply remains held", stopping.isDone)
        assertTrue(stopping.get(1, TimeUnit.SECONDS).exceptionOrNull() is IOException)
        assertTrue("Original total Stop budget stays twenty seconds", SystemClock.elapsedRealtime() - started < 20_000)
        assertFalse(prefs.getBoolean("proxyRootWanted", true))
        release.countDown()
        assertFalse(adoption.get(5, TimeUnit.SECONDS))
        assertFalse(prefs.getBoolean("proxyRootWanted", true))
        assertFalse(prefs.contains("proxyRootBootRestoreSuccessAt"))
    }

    @Test fun actualAdoptionRejectsIncompleteHealthEvenWhenNativeReportsRunning() {
        prefs.edit().putBoolean("proxyRootAutoStart", true).commit()
        RootStartIntent93BridgeShadow.integrity = "unknown"
        assertFalse(workers.submit<Boolean> { RootProxyManager(app).adoptBootRuntime() }.get(5, TimeUnit.SECONDS))
        RootStartIntent93BridgeShadow.integrity = "healthy"
        RootStartIntent93BridgeShadow.dataPlaneHealthy = false
        assertFalse(workers.submit<Boolean> { RootProxyManager(app).adoptBootRuntime() }.get(5, TimeUnit.SECONDS))
        assertFalse(prefs.contains("proxyRootBootRestoreSuccessAt"))
    }

    @Test fun cancellationFailureRejectsOldObservationAndPermitsFreshActualStatus() {
        val old = RootProxyManager.observationTicket()
        RootStartIntent93BridgeShadow.cancelFails = true
        val stopping = workers.submit<Result<JSONObject>> { runCatching { RootProxyManager(app).stop() } }.get(5, TimeUnit.SECONDS)
        assertTrue(stopping.exceptionOrNull() is IOException)
        val oldPublished = AtomicBoolean()
        assertFalse(RootProxyManager.publishObservation(old) { oldPublished.set(true) })
        assertFalse(oldPublished.get())
        val fresh = workers.submit<JSONObject> { RootProxyManager(app).status() }.get(5, TimeUnit.SECONDS)
        assertEquals(29091, fresh.getInt("controllerPort"))
        assertEquals(29091, prefs.getInt("proxyControllerPort", 0))
        assertFalse(prefs.getBoolean("proxyRootWanted", true))
    }

    @Test fun actualSupplierObservationAndStopDoNotInvertPublicationLocks() {
        val networkLock = Any()
        val probed = latch(); val allowProbe = latch(); val observationHeld = latch()
        val allowObservation = latch(); val supplierWaiting = latch()
        val ticket = RootProxyManager.observationTicket()
        val calls = AtomicInteger()
        RootStartIntent93BridgeShadow.probeHook = { probed.countDown(); check(allowProbe.await(5, TimeUnit.SECONDS)) }
        val starting = workers.submit<Result<JSONObject>> {
            runCatching { RootProxyManager(app).startIfWanted(profile(), BooleanSupplier {
                if (calls.incrementAndGet() >= 3) {
                    supplierWaiting.countDown()
                    synchronized(networkLock) { true }
                } else true
            }) }
        }
        assertTrue(probed.await(5, TimeUnit.SECONDS))
        val observing = workers.submit<Boolean> {
            synchronized(networkLock) {
                observationHeld.countDown(); check(allowObservation.await(5, TimeUnit.SECONDS))
                RootProxyManager.publishObservation(ticket) { prefs.edit().putBoolean("unexpectedOldObservation", true).apply() }
            }
        }
        assertTrue(observationHeld.await(5, TimeUnit.SECONDS))
        allowProbe.countDown(); assertTrue(supplierWaiting.await(5, TimeUnit.SECONDS))
        val stopEntered = latch()
        RootStartIntent93BridgeShadow.stopEntered = stopEntered
        val stopping = workers.submit<Result<JSONObject>> { runCatching { RootProxyManager(app).stop() } }
        assertTrue("Stop must revoke its generation before releasing the blocked observation", stopEntered.await(5, TimeUnit.SECONDS))
        assertFalse(prefs.getBoolean("proxyRootWanted", true))
        allowObservation.countDown()
        assertFalse(observing.get(5, TimeUnit.SECONDS))
        assertTrue("The obsolete actual start must be rejected", starting.get(5, TimeUnit.SECONDS).exceptionOrNull() is IOException)
        assertTrue(stopping.get(5, TimeUnit.SECONDS).getOrThrow().getBoolean("ok"))
        assertFalse(prefs.getBoolean("proxyRootWanted", true))
        assertFalse(prefs.contains("unexpectedOldObservation"))
    }

    @Test fun queuedActualManualStartIsRevokedAndNewManualStartGetsSameBootReceipt() {
        val field = RootProxyManager::class.java.getDeclaredField("CONTROL_LOCK").apply { isAccessible = true }
        val control = field.get(null) as ProxyControlEpoch
        val gate = ProxyControlEpoch::class.java.getDeclaredField("gate").apply { isAccessible = true }.get(control) as java.util.concurrent.locks.ReentrantLock
        val thread = AtomicReference<Thread>()
        val entered = latch(); val stopEntered = latch()
        RootStartIntent93BridgeShadow.stopEntered = stopEntered
        control.lock()
        try {
            val queued = workers.submit<Result<JSONObject>> {
                thread.set(Thread.currentThread()); entered.countDown()
                runCatching { RootProxyManager(app).startManual(profile(), null) }
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val queuedLimit = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (!gate.hasQueuedThread(thread.get()) && System.nanoTime() < queuedLimit) Thread.sleep(5)
            assertTrue("The old manual request must already be queued at the real control lock", gate.hasQueuedThread(thread.get()))
            val stopping = workers.submit<Result<JSONObject>> { runCatching { RootProxyManager(app).stop() } }
            assertTrue(stopEntered.await(5, TimeUnit.SECONDS))
            val stopLimit = System.nanoTime() + TimeUnit.SECONDS.toNanos(4)
            while (!stopping.isDone && System.nanoTime() < stopLimit) {
                ShadowSystemClock.advanceBy(Duration.ofMillis(100)); Thread.sleep(10)
            }
            assertTrue("Stop retains its real two-second control acquisition limit", stopping.isDone)
            assertTrue(stopping.get(1, TimeUnit.SECONDS).exceptionOrNull() is IOException)
            assertFalse(prefs.getBoolean("proxyRootWanted", true))
            control.unlock()
            assertTrue("A manual request queued before Stop must retain its old generation", queued.get(5, TimeUnit.SECONDS).exceptionOrNull() is IOException)
            assertFalse(prefs.getBoolean("proxyRootWanted", true))
            assertEquals(0, probeCount())
            assertFalse(prefs.contains("proxyRootManualStartSucceededGeneration"))
        } finally {
            // Unlock only if the assertions left the holder owning the gate.
            if (gate.isHeldByCurrentThread) control.unlock()
        }
        val fresh = workers.submit<JSONObject> { RootProxyManager(app).startManual(profile(), null) }.get(5, TimeUnit.SECONDS)
        assertTrue(fresh.getBoolean("ok"))
        assertTrue(prefs.getBoolean("proxyRootWanted", false))
        val manual = prefs.getLong("proxyRootManualStartGeneration", -1)
        assertEquals(manual, prefs.getLong("proxyRootManualStartSucceededGeneration", -2))
        assertTrue(manual > prefs.getLong("proxyRootStoppedManualGeneration", 0))
        assertEquals(93, prefs.getInt("proxyRootManualStartSucceededBootCount", -1))
    }

    @Test fun actualUnchangedWantedDecisionKeepsHealthyOwnedCoreWithoutNewDeployment() {
        val result = workers.submit<JSONObject> { RootProxyManager(app).startIfWanted(profile(), BooleanSupplier { true }) }.get(5, TimeUnit.SECONDS)
        assertTrue(result.getBoolean("ok"))
        assertTrue(result.getBoolean("alreadyRunning"))
        assertEquals(1, probeCount())
        assertTrue(prefs.getBoolean("proxyRootWanted", false))
    }

}
