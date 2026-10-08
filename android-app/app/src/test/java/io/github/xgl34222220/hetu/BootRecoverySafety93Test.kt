package io.github.xgl34222220.hetu

import android.app.Application
import android.content.Context
import android.net.Network
import android.provider.Settings
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
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowNetwork
import org.robolectric.shadows.ShadowSystemClock
import org.robolectric.util.ReflectionHelpers
import java.io.IOException
import java.time.Duration
import java.util.ArrayDeque
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.function.BooleanSupplier

/** Fakes Root replies, not the Service's recovery decisions or Root stop. */
@Implements(value = RootBridge::class, isInAndroidSdk = false)
class BootRecoverySafety93BridgeShadow {
    companion object {
        var process = "0"
        var nativeState = "IDLE"
        var nativeRunningThisBoot = false
        var missingStopScript = false
        val commands = CopyOnWriteArrayList<String>()

        @JvmStatic @Implementation
        fun rootShell(context: Context, command: String, timeout: Long): RootBridge.Result {
            commands += command
            if (command.contains("core.pid") && command.contains("readlink"))
                return RootBridge.Result(if (process == "?") 126 else 0,
                    if (command.contains("core.token")) "$process\nasset:fixture\n" else process)
            if (command.contains("cancel-boot")) {
                nativeState = "STOPPED"
                return RootBridge.Result(0, "")
            }
            if (command.contains("exec '/data/adb/hetu/hetu-root.sh' 'stop'")) {
                if (!missingStopScript) return RootBridge.Result(0,
                    JSONObject().put("ok", false).put("message", "fixture-cleanup-failed").toString())
                // Execute the actual missing-script response encoded by the
                // production command, so an old ok:true branch cannot be hidden.
                val prefix = "else printf '%s\\n' "
                require(command.contains(prefix) && command.endsWith("; fi"))
                val quoted = command.substringAfter(prefix).removeSuffix("; fi")
                require(quoted.startsWith("'") && quoted.endsWith("'"))
                return RootBridge.Result(0, quoted.substring(1, quoted.length - 1).replace("'\\''", "'"))
            }
            require(command.contains("/data/adb/hetu/boot/"))
            if (command.contains("= \"\$b running\"") && command.contains("&& printf 1"))
                return RootBridge.Result(if (nativeRunningThisBoot) 0 else 1, if (nativeRunningThisBoot) "1" else "")
            // New owner/status API and cff's original restoreInProgress API are
            // both Root boundaries; the before Service compiles without edits.
            return if (command.contains("printf ACTIVE")) RootBridge.Result(0, nativeState)
                else RootBridge.Result(if (nativeState == "ACTIVE") 0 else 1,
                    if (nativeState == "ACTIVE") "1" else "")
        }
    }
}

@Implements(value = RootProxyManager::class, isInAndroidSdk = false)
class BootRecoverySafety93ManagerShadow {
    @Implementation(methodName = "startIfWanted")
    private fun startIfWanted(profile: ProxyRuntimeProfile, currentNetwork: BooleanSupplier): JSONObject {
        check(currentNetwork.asBoolean)
        starts++
        val held = duringStart; duringStart = null; held?.invoke()
        return JSONObject().put("ok", startSucceeds).put("running", startSucceeds).put("message", "fixture-permanently-dead")
    }

    @Implementation(methodName = "networkHealth")
    private fun networkHealth(): JSONObject = JSONObject().put("ok", true)
        .put("networkIntegrity", integrity).put("dataPlaneHealthy", dataPlaneHealthy)
        .put("networkFault", if (integrity == "healthy" && dataPlaneHealthy) "" else "fixture-incomplete-data-plane")

    companion object {
        var starts = 0
        var integrity = "degraded"
        var dataPlaneHealthy = false
        var startSucceeds = false
        var duringStart: (() -> Unit)? = null
    }
}

/** The original private Service entries and scheduler run against controlled
 * monotonic time; no helper algorithm, real Root, network or device is invoked. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class,
    shadows = [BootRecoverySafety93BridgeShadow::class, BootRecoverySafety93ManagerShadow::class])
class BootRecoverySafety93Test {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val prefs get() = app.getSharedPreferences("hetu", Context.MODE_PRIVATE)
    private lateinit var controller: ServiceController<ProxyNetworkMatchService>
    private lateinit var service: ProxyNetworkMatchService
    private lateinit var epochs: NetworkEpoch<Network>
    private val pending = ArrayDeque<Pair<Runnable, Long>>()
    private var attached = false

    @Before fun prepare() {
        prefs.edit().clear().commit()
        Settings.Global.putInt(app.contentResolver, Settings.Global.BOOT_COUNT, 93)
        BootRecoverySafety93BridgeShadow.process = "0"
        BootRecoverySafety93BridgeShadow.nativeState = "IDLE"
        BootRecoverySafety93BridgeShadow.nativeRunningThisBoot = false
        BootRecoverySafety93BridgeShadow.missingStopScript = false
        BootRecoverySafety93BridgeShadow.commands.clear()
        BootRecoverySafety93ManagerShadow.starts = 0
        BootRecoverySafety93ManagerShadow.integrity = "degraded"
        BootRecoverySafety93ManagerShadow.dataPlaneHealthy = false
        BootRecoverySafety93ManagerShadow.startSucceeds = false
        BootRecoverySafety93ManagerShadow.duringStart = null
        attach()
        prefs.edit().putBoolean("proxyRootWanted", true).commit()
        ready()
    }

    private fun attach() {
        controller = Robolectric.buildService(ProxyNetworkMatchService::class.java).create()
        service = controller.get(); attached = true
        ReflectionHelpers.getField<ExecutorService>(service, "metrics").shutdownNow()
        ReflectionHelpers.getField<ExecutorService>(service, "worker").shutdownNow()
        ReflectionHelpers.setField(service, "egressRequest", ProxyNetworkMatchService.EgressRequest { _, _ -> 204 })
        ReflectionHelpers.setField(service, "bootRestores", ProxyRestoreScheduler({ task, delay ->
            pending.add(task to delay)
        }, { restore() }))
        epochs = ReflectionHelpers.getField(service, "networkEvents")
    }

    private fun ready() {
        val network = ShadowNetwork.newInstance(1093)
        epochs.available(network); epochs.capabilities(network, true); epochs.links(network, "fixture-wlan0")
    }

    private fun destroy() {
        if (!attached) return
        controller.destroy(); attached = false
        assertTrue(ReflectionHelpers.getField<ExecutorService>(service, "journalWorker").awaitTermination(5, TimeUnit.SECONDS))
    }

    @After fun finish() = destroy()

    private fun <T> offMain(task: () -> T): T {
        val executor = Executors.newSingleThreadExecutor()
        try { return executor.submit<T> { task() }.get(30, TimeUnit.SECONDS) }
        finally { executor.shutdownNow() }
    }

    private fun restore(): Long = offMain {
        ReflectionHelpers.callInstanceMethod<Long>(service, "restoreWantedProxyAfterBoot")
    }

    private fun maintain() = offMain {
        ReflectionHelpers.callInstanceMethod<Void>(service, "maintainProxyRuntime")
    }

    private fun requestRestore() = ReflectionHelpers.callInstanceMethod<Void>(service, "scheduleBootRestore",
        ReflectionHelpers.ClassParameter.from(Long::class.javaPrimitiveType!!, 350L))

    private fun fireRestore() {
        val (task, delay) = pending.remove()
        ShadowSystemClock.advanceBy(Duration.ofMillis(delay)); task.run()
    }

    private fun foregroundReattach(times: Int = 1) {
        val executor = Executors.newSingleThreadExecutor()
        ReflectionHelpers.setField(service, "worker", executor)
        ReflectionHelpers.setField(service, "evaluations", ProxyTaskCoalescer(executor) {
            ReflectionHelpers.callInstanceMethod<Void>(service, "evaluateNow")
        })
        try {
            repeat(times) { service.onStartCommand(null, 0, 1093) }
            executor.submit {}.get(30, TimeUnit.SECONDS)
        } finally { executor.shutdownNow() }
    }

    // The archived assertion used "start.sh", which also matched the read-only
    // cmdline identity literal "hetu-autostart.sh --worker". Match the actual
    // launch templates and complete executable tokens without treating that
    // owner-inspection literal as a launch.
    private fun containsRecoveryStartCommand(command: String): Boolean {
        val entry = RootAutostart.ENTRY
        val padded = "$command "
        return command.contains("'start'") || command.contains(RootAutostart.BASE + "/start.sh") ||
            command.contains(RootBridge.quote(entry)) || command.contains("\"$entry\"") ||
            padded.startsWith("$entry ") || padded.contains("exec $entry ") || padded.contains("sh $entry ")
    }

    @Test fun readOnlyOwnerInspectionIsDistinctFromEveryKnownRecoveryLaunchTemplate() {
        offMain { RootAutostart.restoreState(app) }
        val ownerRead = BootRecoverySafety93BridgeShadow.commands.last { it.contains("printf ACTIVE") }
        assertTrue("The real owner-inspection command reproduces the old substring false positive", ownerRead.contains("start.sh"))
        assertFalse("Reading the worker cmdline must not be classified as starting it", containsRecoveryStartCommand(ownerRead))
        val launches = listOf(
            "exec '/data/adb/hetu/hetu-root.sh' 'start' '/data/adb/hetu/bin/core'",
            "/system/bin/sh '${RootAutostart.BASE}/start.sh'",
            "exec '${RootAutostart.ENTRY}' --worker",
            "/system/bin/sh '${RootAutostart.ENTRY}' --worker",
            "exec \"${RootAutostart.ENTRY}\" --worker",
            "/system/bin/sh ${RootAutostart.ENTRY} --worker",
            "exec ${RootAutostart.ENTRY} --worker"
        )
        launches.forEach { assertTrue("Actual recovery launch remains forbidden: $it", containsRecoveryStartCommand(it)) }
        assertEquals(0, BootRecoverySafety93ManagerShadow.starts)
    }

    @Test fun confirmedDeadRecoveryBudgetSurvivesObserverRecreation() {
        maintain(); assertEquals(1, BootRecoverySafety93ManagerShadow.starts)
        val originalDeadline = prefs.getLong("proxyRecoveryDeadlineElapsed", 0L)
        repeat(10) {
            destroy(); attach(); ready(); maintain()
        }
        assertEquals("observer recreation must not replenish automatic starts", 6, BootRecoverySafety93ManagerShadow.starts)
        assertEquals(originalDeadline, prefs.getLong("proxyRecoveryDeadlineElapsed", -1L))
        assertTrue(prefs.getString("proxyAutoRecoveryError", "")!!.contains("6"))
        epochs.links(epochs.snapshot().network, "fixture-network-handover")
        ShadowSystemClock.advanceBy(Duration.ofMinutes(6)); repeat(20) { maintain() }
        assertEquals(6, BootRecoverySafety93ManagerShadow.starts)
        assertFalse(prefs.contains("proxyAutoRecoverySuccess"))
        // Only a fresh explicit manual intent, emitted by startOwned, begins a
        // new episode after an exhausted attempt/deadline; a callback cannot.
        prefs.edit().putLong("proxyRootManualStartGeneration", 1L).commit()
        maintain(); assertEquals(7, BootRecoverySafety93ManagerShadow.starts)
        assertFalse(prefs.contains("proxyAutoRecoverySuccess"))
        // A deliberately held Root boundary returns complete health after the
        // original window; the actual Service must retain that consumed ledger.
        prefs.edit().putLong("proxyRootManualStartGeneration", 2L).commit()
        BootRecoverySafety93ManagerShadow.startSucceeds = true
        BootRecoverySafety93ManagerShadow.duringStart = {
            ShadowSystemClock.advanceBy(Duration.ofMinutes(6))
            BootRecoverySafety93BridgeShadow.process = "1"
            BootRecoverySafety93ManagerShadow.integrity = "healthy"
            BootRecoverySafety93ManagerShadow.dataPlaneHealthy = true
        }
        maintain(); assertEquals(8, BootRecoverySafety93ManagerShadow.starts)
        val lateDeadline = prefs.getLong("proxyRecoveryDeadlineElapsed", 0L)
        assertTrue(prefs.getBoolean("proxyRecoveryCompletedAfterBudget", false))
        assertEquals("a late Root reply cannot clear consumed starts", 1, prefs.getInt("proxyRecoveryStarts", -1))
        repeat(20) { maintain() }
        assertEquals(lateDeadline, prefs.getLong("proxyRecoveryDeadlineElapsed", -1L))
        assertEquals(8, BootRecoverySafety93ManagerShadow.starts)
        BootRecoverySafety93BridgeShadow.process = "0"
        BootRecoverySafety93ManagerShadow.startSucceeds = false
        prefs.edit().putLong("proxyRootManualStartGeneration", 3L).commit()
        maintain(); assertEquals(9, BootRecoverySafety93ManagerShadow.starts)
        val nearDeadline = prefs.getLong("proxyRecoveryDeadlineElapsed", 0L)
        ShadowSystemClock.advanceBy(Duration.ofMillis(299_000L)); maintain()
        assertEquals("299s cannot admit a new Root transaction without its cleanup budget", 9, BootRecoverySafety93ManagerShadow.starts)
        assertEquals(nearDeadline, prefs.getLong("proxyRecoveryDeadlineElapsed", -1L))
        assertEquals(1, prefs.getInt("proxyRecoveryStarts", -1))
    }

    @Test fun livingCoreRequiresBothNetworkIntegrityAndDataPlaneBeforeBootSuccess() {
        prefs.edit().putBoolean("proxyRootAutoStart", true).commit()
        BootRecoverySafety93BridgeShadow.process = "1"
        val firstDelay = restore()
        assertFalse("a live PID cannot prove boot restore success", prefs.contains("proxyRootBootRestoreSuccessAt"))
        assertTrue(prefs.getString("proxyRootBootError", "")!!.isNotEmpty())
        assertEquals(0, BootRecoverySafety93ManagerShadow.starts)
        assertTrue(firstDelay >= 30_000L)
        ShadowSystemClock.advanceBy(Duration.ofMillis(firstDelay))
        BootRecoverySafety93ManagerShadow.integrity = "healthy"
        val secondDelay = restore()
        assertFalse(prefs.contains("proxyRootBootRestoreSuccessAt"))
        assertFalse(prefs.contains("proxyAutoRecoverySuccess"))
        ShadowSystemClock.advanceBy(Duration.ofMillis(secondDelay))
        BootRecoverySafety93ManagerShadow.integrity = "degraded"
        BootRecoverySafety93ManagerShadow.dataPlaneHealthy = true
        val thirdDelay = restore()
        assertFalse(prefs.contains("proxyRootBootRestoreSuccessAt"))
        ShadowSystemClock.advanceBy(Duration.ofMillis(thirdDelay))
        BootRecoverySafety93ManagerShadow.integrity = "healthy"
        assertEquals(0L, restore())
        assertTrue(prefs.getLong("proxyRootBootRestoreSuccessAt", 0L) > 0L)
        assertFalse(prefs.contains("proxyRootBootError"))
        assertEquals(0, BootRecoverySafety93ManagerShadow.starts)
        assertTrue(prefs.getBoolean("proxyRootWanted", false))
        assertTrue(prefs.getBoolean("proxyRootRuntimeRunning", false))
    }

    @Test fun queuedBootRetryCannotResurrectAnExplicitlyStoppedBoot() {
        prefs.edit().putBoolean("proxyRootAutoStart", true).commit()
        BootRecoverySafety93BridgeShadow.process = "?"
        repeat(64) { requestRestore() }; assertEquals(1, pending.size)
        fireRestore(); assertEquals(1, pending.size)
        val commandsBeforeStop = BootRecoverySafety93BridgeShadow.commands.size
        prefs.edit().putBoolean("proxyRootWanted", false).commit()
        fireRestore(); assertTrue(pending.isEmpty())
        assertEquals(commandsBeforeStop, BootRecoverySafety93BridgeShadow.commands.size)
        assertEquals(0, BootRecoverySafety93ManagerShadow.starts)
        assertFalse(prefs.contains("proxyRootBootRestoreSuccessAt"))
        // A stale wanted bit/callback cannot override native stop-this-boot.
        prefs.edit().putBoolean("proxyRootWanted", true).commit()
        BootRecoverySafety93BridgeShadow.process = "0"
        BootRecoverySafety93BridgeShadow.nativeState = "STOPPED"
        requestRestore(); fireRestore()
        assertEquals("native stop marker must outlive queued App callbacks", 0, BootRecoverySafety93ManagerShadow.starts)
        assertTrue(pending.isEmpty())
        repeat(20) { maintain() }
        assertEquals(0, BootRecoverySafety93ManagerShadow.starts)
        assertFalse(prefs.contains("proxyRootBootRestoreSuccessAt"))
        val stoppedReason = prefs.getString("proxyRecoveryTerminalReason", "")
        BootRecoverySafety93BridgeShadow.process = "1"
        BootRecoverySafety93ManagerShadow.integrity = "healthy"
        BootRecoverySafety93ManagerShadow.dataPlaneHealthy = true
        repeat(20) { maintain() }
        assertEquals(stoppedReason, prefs.getString("proxyRecoveryTerminalReason", ""))
        assertFalse(prefs.contains("proxyAutoRecoverySuccess"))
        assertFalse(prefs.contains("proxyRootBootRestoreSuccessAt"))
        assertEquals(0, BootRecoverySafety93ManagerShadow.starts)
        // A new generation by itself is no proof that an explicit start
        // succeeded; an older successful generation stopped later also fails.
        prefs.edit().putLong("proxyRootManualStartGeneration", 1L)
            .putString("proxyRootSessionOwner", "manual").commit()
        maintain(); assertFalse(prefs.contains("proxyAutoRecoverySuccess"))
        prefs.edit().putLong("proxyRootManualStartSucceededGeneration", 1L)
            .putInt("proxyRootManualStartSucceededBootCount", 92).commit()
        maintain()
        assertFalse("a previous-boot success receipt cannot authorize this stopped boot", prefs.contains("proxyAutoRecoverySuccess"))
        Settings.Global.putInt(app.contentResolver, Settings.Global.BOOT_COUNT, -1)
        prefs.edit().putInt("proxyRootManualStartSucceededBootCount", -1).commit()
        maintain()
        assertFalse("an unknown boot identity cannot authorize a manual exception", prefs.contains("proxyAutoRecoverySuccess"))
        assertEquals(0, BootRecoverySafety93ManagerShadow.starts)
        Settings.Global.putInt(app.contentResolver, Settings.Global.BOOT_COUNT, 93)
        prefs.edit().putInt("proxyRootManualStartSucceededBootCount", 93)
            .putLong("proxyRootStoppedManualGeneration", 1L).commit()
        maintain(); assertFalse(prefs.contains("proxyAutoRecoverySuccess"))
        // Only Root's success receipt for a later explicit manual intent may
        // maintain its fully healthy runtime while leaving native STOPPED.
        prefs.edit().putLong("proxyRootManualStartGeneration", 2L)
            .putLong("proxyRootManualStartSucceededGeneration", 2L).commit()
        maintain()
        assertTrue(prefs.getLong("proxyAutoRecoverySuccess", 0L) > 0L)
        assertFalse("a manual recovery cannot relabel the old boot task as successful", prefs.contains("proxyRootBootRestoreSuccessAt"))
        assertEquals("STOPPED", BootRecoverySafety93BridgeShadow.nativeState)
        assertEquals(0, BootRecoverySafety93ManagerShadow.starts)
        val commandsAfterManual = BootRecoverySafety93BridgeShadow.commands.size
        requestRestore(); fireRestore()
        assertTrue(pending.isEmpty())
        assertEquals(commandsAfterManual, BootRecoverySafety93BridgeShadow.commands.size)
    }

    @Test fun nativeRestoreOwnsTheOnlyTaskAndAppWaitingHasATotalDeadline() {
        prefs.edit().putBoolean("proxyRootAutoStart", true).commit()
        BootRecoverySafety93BridgeShadow.nativeState = "ACTIVE"
        repeat(64) { requestRestore() }; assertEquals(1, pending.size)
        fireRestore(); maintain()
        assertEquals("periodic guardian must not start beside native restoration", 0, BootRecoverySafety93ManagerShadow.starts)
        repeat(16) {
            if (pending.isNotEmpty()) {
                repeat(64) { requestRestore() }; assertEquals(1, pending.size)
                fireRestore(); maintain()
            }
        }
        assertTrue("native-active waits must stop at the persisted deadline", pending.isEmpty())
        assertEquals(0, BootRecoverySafety93ManagerShadow.starts)
        assertTrue(prefs.getString("proxyRootBootError", "")!!.contains("5 分钟"))
        BootRecoverySafety93BridgeShadow.nativeState = "IDLE"
        requestRestore(); fireRestore(); maintain()
        assertTrue(pending.isEmpty()); assertEquals(0, BootRecoverySafety93ManagerShadow.starts)
        val originalDeadline = prefs.getLong("proxyRecoveryDeadlineElapsed", 0L)
        val originalStarts = prefs.getInt("proxyRecoveryStarts", -1)
        val originalTerminal = prefs.getString("proxyRecoveryTerminalReason", "")
        // The native owner can finish after App's bounded admission window.
        // A lost ACTION_RUNNING is recovered only by one read-only foreground
        // reattach check; the consumed budget and stop boundary are retained.
        BootRecoverySafety93BridgeShadow.nativeState = "IDLE"
        BootRecoverySafety93BridgeShadow.process = "1"
        BootRecoverySafety93ManagerShadow.integrity = "healthy"
        BootRecoverySafety93ManagerShadow.dataPlaneHealthy = true
        destroy(); attach(); ready(); foregroundReattach()
        assertFalse("an IDLE native state alone cannot prove a same-boot start", prefs.contains("proxyRootBootRestoreSuccessAt"))
        assertEquals(originalDeadline, prefs.getLong("proxyRecoveryDeadlineElapsed", -1L))
        assertEquals(originalStarts, prefs.getInt("proxyRecoveryStarts", -1))
        BootRecoverySafety93BridgeShadow.nativeRunningThisBoot = true
        destroy(); attach(); ready(); foregroundReattach(64)
        assertTrue(prefs.getLong("proxyRootBootRestoreSuccessAt", 0L) > 0L)
        assertTrue(prefs.getBoolean("proxyRecoveryCompletedAfterBudget", false))
        assertEquals(originalDeadline, prefs.getLong("proxyRecoveryDeadlineElapsed", -1L))
        assertEquals(originalStarts, prefs.getInt("proxyRecoveryStarts", -1))
        assertEquals(originalTerminal, prefs.getString("proxyRecoveryTerminalReason", ""))
        val commandsAfterReattach = BootRecoverySafety93BridgeShadow.commands.size
        foregroundReattach(64); maintain(); requestRestore(); fireRestore()
        assertEquals("reattach and queued callbacks cannot reopen the exhausted native episode", commandsAfterReattach,
            BootRecoverySafety93BridgeShadow.commands.size)
        assertTrue(pending.isEmpty()); assertEquals(0, BootRecoverySafety93ManagerShadow.starts)
        assertTrue(BootRecoverySafety93BridgeShadow.commands.none { containsRecoveryStartCommand(it) })
        prefs.edit().putBoolean("proxyRootWanted", false).commit()
        val commandsAfterStop = BootRecoverySafety93BridgeShadow.commands.size
        destroy(); attach(); ready(); foregroundReattach(); maintain()
        assertFalse(prefs.getBoolean("proxyRootWanted", true))
        assertEquals(commandsAfterStop, BootRecoverySafety93BridgeShadow.commands.size)
        // Native's failed final budget is never extended by a fresh App episode.
        prefs.edit().putBoolean("proxyRootWanted", true).putLong("proxyRootManualStartGeneration", 1L)
            .putLong("proxyRootManualStartSucceededGeneration", 1L)
            .putInt("proxyRootManualStartSucceededBootCount", 92).putString("proxyRootSessionOwner", "manual").commit()
        BootRecoverySafety93BridgeShadow.nativeState = "TERMINAL"
        maintain()
        assertEquals(0, BootRecoverySafety93ManagerShadow.starts)
        assertFalse("a previous-boot manual success cannot replenish a terminal native budget", prefs.getBoolean("proxyRecoveryEpisodeComplete", false))
        assertTrue(prefs.getString("proxyAutoRecoveryError", "")!!.contains("不追加重试"))
        // Enabled native work with no published owner/status is still its
        // episode. App cannot spend six starts before that owner appears.
        prefs.edit().putLong("proxyRootManualStartGeneration", 2L).commit()
        BootRecoverySafety93BridgeShadow.process = "0"
        BootRecoverySafety93BridgeShadow.nativeState = "PENDING"
        repeat(64) { requestRestore() }; assertEquals(1, pending.size)
        fireRestore(); maintain()
        assertEquals("unpublished native owner must retain the only start budget", 0, BootRecoverySafety93ManagerShadow.starts)
        val pendingDeadline = prefs.getLong("proxyRecoveryDeadlineElapsed", 0L)
        val commandsBeforeRecreation = BootRecoverySafety93BridgeShadow.commands.size
        destroy()
        while (pending.isNotEmpty()) pending.remove().first.run()
        assertEquals(commandsBeforeRecreation, BootRecoverySafety93BridgeShadow.commands.size)
        attach(); ready(); requestRestore()
        repeat(16) {
            if (pending.isNotEmpty()) {
                repeat(64) { requestRestore() }; assertEquals(1, pending.size)
                fireRestore(); maintain()
            }
        }
        assertTrue("missing native owner must produce a bounded waiting error", pending.isEmpty())
        assertTrue(prefs.getString("proxyRootBootError", "")!!.contains("5 分钟"))
        assertEquals(pendingDeadline, prefs.getLong("proxyRecoveryDeadlineElapsed", -1L))
        assertEquals(0, BootRecoverySafety93ManagerShadow.starts)
        BootRecoverySafety93BridgeShadow.nativeState = "IDLE"
        requestRestore(); fireRestore(); maintain()
        assertTrue(pending.isEmpty()); assertEquals(0, BootRecoverySafety93ManagerShadow.starts)
        // A failed native-disable operation preserves its confirmed setting,
        // while its separate revocation intent blocks observer re-enablement.
        prefs.edit().putLong("proxyRootManualStartGeneration", 3L)
            .putBoolean("proxyRootAutoStartRevoked", true).commit()
        val commandsBeforeRevoked = BootRecoverySafety93BridgeShadow.commands.size
        requestRestore(); fireRestore(); maintain(); foregroundReattach(64)
        assertTrue(prefs.getBoolean("proxyRootAutoStart", false))
        assertTrue(pending.isEmpty()); assertEquals(0, BootRecoverySafety93ManagerShadow.starts)
        assertEquals(commandsBeforeRevoked, BootRecoverySafety93BridgeShadow.commands.size)
    }

    @Test fun actualRootStopDoesNotClaimCleanupWhenScriptIsMissingOrCleanupFails() {
        // RootProxyManager.stop itself executes, including its real command
        // parser and preference transaction; no stop implementation is shadowed.
        for (missing in listOf(true, false)) {
            prefs.edit().putBoolean("proxyRootWanted", true).putBoolean("proxyRootRuntimeRunning", true)
                .putBoolean("proxyRootAutoStart", true).putString("proxyRootSessionOwner", "manual").commit()
            BootRecoverySafety93BridgeShadow.missingStopScript = missing
            val stages = CopyOnWriteArrayList<String>()
            val failure = try {
                offMain { RootProxyManager(app).stop(RootProxyManager.Progress { stages += it }) }
                null
            } catch (error: ExecutionException) { error.cause }
            assertNotNull(if (missing) "missing Root script cannot prove cleanup" else "failed cleanup must remain visible", failure)
            assertTrue(failure is IOException)
            assertFalse(prefs.getBoolean("proxyRootWanted", true))
            assertTrue("running remains unverified until cleanup succeeds", prefs.getBoolean("proxyRootRuntimeRunning", false))
            assertEquals("manual", prefs.getString("proxyRootSessionOwner", ""))
            assertFalse(stages.any { it == "网络规则、广告过滤接管与临时 IPv6 状态已恢复" })
        }
        val commandsBefore = BootRecoverySafety93BridgeShadow.commands.size
        requestRestore(); fireRestore(); maintain()
        assertEquals(commandsBefore, BootRecoverySafety93BridgeShadow.commands.size)
        assertEquals(0, BootRecoverySafety93ManagerShadow.starts)
        assertFalse(prefs.contains("proxyRootBootRestoreSuccessAt"))
    }
}
