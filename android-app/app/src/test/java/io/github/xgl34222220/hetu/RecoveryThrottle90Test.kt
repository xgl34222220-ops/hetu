package io.github.xgl34222220.hetu

import android.app.Application
import android.content.Context
import android.net.Network
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
import java.time.Duration
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.function.BooleanSupplier

/** Only the Root deployment boundary is fake; the service's process decision,
 * current-intent/network gates and monotonic recovery backoff execute verbatim. */
@Implements(value = RootBridge::class, isInAndroidSdk = false)
class RecoveryThrottleBridge90Shadow {
    companion object {
        var processAnswer = "0"
        @JvmStatic @Implementation fun rootShell(context: Context, command: String, timeout: Long): RootBridge.Result {
            require(command.contains("core.pid") && command.contains("readlink"))
            return RootBridge.Result(0, processAnswer)
        }
    }
}

@Implements(value = RootProxyManager::class, isInAndroidSdk = false)
class RecoveryThrottleManager90Shadow {
    @Implementation fun startIfWanted(profile: ProxyRuntimeProfile, currentNetwork: BooleanSupplier): JSONObject {
        check(currentNetwork.asBoolean)
        attempts++
        // Remain confirmed DEAD so repeated maintenance actually tests backoff.
        return JSONObject().put("ok", false).put("running", false).put("message", "fixture-dead")
    }
    companion object { var attempts = 0 }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class,
    shadows = [RecoveryThrottleBridge90Shadow::class, RecoveryThrottleManager90Shadow::class])
class RecoveryThrottle90Test {
    private lateinit var controller: ServiceController<ProxyNetworkMatchService>
    private lateinit var service: ProxyNetworkMatchService
    private lateinit var epochs: NetworkEpoch<Network>
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val prefs get() = app.getSharedPreferences("hetu", Context.MODE_PRIVATE)

    @Before fun prepare() {
        prefs.edit().clear().commit()
        RecoveryThrottleBridge90Shadow.processAnswer = "0"
        RecoveryThrottleManager90Shadow.attempts = 0
        attach()
        prefs.edit().putBoolean("proxyRootWanted", true).commit()
        ready()
    }
    private fun attach() {
        controller = Robolectric.buildService(ProxyNetworkMatchService::class.java).create()
        service = controller.get()
        ReflectionHelpers.getField<ExecutorService>(service, "metrics").shutdownNow()
        ReflectionHelpers.getField<ExecutorService>(service, "worker").shutdownNow()
        epochs = ReflectionHelpers.getField(service, "networkEvents")
    }
    private fun ready() {
        val network = ShadowNetwork.newInstance(1090)
        epochs.available(network); epochs.capabilities(network, true); epochs.links(network, "fixture-wlan0")
    }
    private fun maintain() = ReflectionHelpers.callInstanceMethod<Void>(service, "maintainProxyRuntime")
    private fun destroy() {
        controller.destroy()
        assertTrue(ReflectionHelpers.getField<ExecutorService>(service, "journalWorker").awaitTermination(5, TimeUnit.SECONDS))
    }
    @After fun finish() = destroy()

    @Test fun futureWallHistoryCannotDelayTheFirstConfirmedDeadRecovery() {
        // Equivalent persisted state after the wall clock has moved backwards.
        prefs.edit().putLong("proxyAutoRecoveryAttempt", Long.MAX_VALUE / 2).commit()
        maintain()
        assertEquals(1, RecoveryThrottleManager90Shadow.attempts)
        assertTrue(prefs.getLong("proxyAutoRecoveryAttempt", 0) < Long.MAX_VALUE / 2)
    }
    @Test fun pastWallHistoryCannotBypassTheSameSessionThirtySecondBackoff() {
        maintain(); assertEquals(1, RecoveryThrottleManager90Shadow.attempts)
        // Equivalent persisted state after a large wall-clock jump forwards.
        prefs.edit().putLong("proxyAutoRecoveryAttempt", 1).commit()
        repeat(20) { maintain() }
        ShadowSystemClock.advanceBy(Duration.ofMillis(29_999)); maintain()
        assertEquals(1, RecoveryThrottleManager90Shadow.attempts)
        prefs.edit().putLong("proxyAutoRecoveryAttempt", Long.MAX_VALUE / 2).commit()
        ShadowSystemClock.advanceBy(Duration.ofMillis(1)); maintain()
        assertEquals(2, RecoveryThrottleManager90Shadow.attempts)
    }
    @Test fun aRecreatedObserverDoesNotInheritAStaleWallOrElapsedGate() {
        maintain(); assertEquals(1, RecoveryThrottleManager90Shadow.attempts)
        destroy()
        prefs.edit().putLong("proxyAutoRecoveryAttempt", Long.MAX_VALUE / 2).commit()
        attach(); ready(); maintain()
        assertEquals(2, RecoveryThrottleManager90Shadow.attempts)
    }
    @Test fun aStoppedIntentCannotRecoverEvenAfterTheBackoffExpires() {
        maintain(); prefs.edit().putBoolean("proxyRootWanted", false).commit()
        ShadowSystemClock.advanceBy(Duration.ofSeconds(31)); maintain()
        assertEquals(1, RecoveryThrottleManager90Shadow.attempts)
        assertFalse(prefs.getBoolean("proxyRootWanted", true))
    }
    @Test fun noCurrentNetworkDoesNotConsumeTheFirstRecoveryOpportunity() {
        epochs.reset(); maintain()
        assertEquals(0, RecoveryThrottleManager90Shadow.attempts)
        assertFalse(prefs.contains("proxyAutoRecoveryAttempt"))
        ready(); maintain(); assertEquals(1, RecoveryThrottleManager90Shadow.attempts)
    }
    @Test fun anUnknownProcessObservationCannotTriggerRecovery() {
        RecoveryThrottleBridge90Shadow.processAnswer = "?"; maintain()
        assertEquals(0, RecoveryThrottleManager90Shadow.attempts)
        assertFalse(prefs.contains("proxyAutoRecoveryAttempt"))
        RecoveryThrottleBridge90Shadow.processAnswer = "0"; maintain()
        assertEquals(1, RecoveryThrottleManager90Shadow.attempts)
    }
}
