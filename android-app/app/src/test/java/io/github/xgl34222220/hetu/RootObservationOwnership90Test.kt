package io.github.xgl34222220.hetu

import android.app.Application
import android.content.Context
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
import org.robolectric.util.ReflectionHelpers
import java.io.IOException
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Only the read-only Root command reply is isolated. Real status, current
 * control ownership and endpoint adoption run unchanged; no real su is used. */
@Implements(value = RootBridge::class, isInAndroidSdk = false)
class RootObservationBridge90Shadow {
    companion object {
        @Volatile var reply = "{}"
        @Volatile var entered: CountDownLatch? = null
        @Volatile var gate: CountDownLatch? = null
        val commands = Collections.synchronizedList(mutableListOf<String>())
        @JvmStatic @Implementation fun rootShell(context: Context, command: String, timeout: Long): RootBridge.Result {
            check(command.contains("hetu-root.sh") && command.contains("'status'")) {
                "This fixture permits the existing read-only status command only"
            }
            val captured = reply
            val capturedEntered = entered; val capturedGate = gate
            commands += command
            capturedEntered?.countDown()
            check(capturedGate == null || capturedGate.await(8, TimeUnit.SECONDS))
            return RootBridge.Result(0, captured)
        }
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, shadows = [RootObservationBridge90Shadow::class])
class RootObservationOwnership90Test {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val prefs get() = app.getSharedPreferences("hetu", Context.MODE_PRIVATE)
    private val control get() = ReflectionHelpers.getStaticField<ProxyControlEpoch>(RootProxyManager::class.java, "CONTROL_LOCK")
    private val worker = Executors.newSingleThreadExecutor()
    @Before fun prepare() {
        prefs.edit().clear().putInt("proxyControllerPort", 29107)
            .putString("proxyNetworkSessionId", "existing").putLong("proxyNetworkEpoch", 1L).commit()
        RootObservationBridge90Shadow.reply = "{\"ok\":true,\"running\":false,\"runtimeSchema\":4,\"networkIntegrity\":\"stopped\",\"controllerPort\":29091}"
        RootObservationBridge90Shadow.entered = null; RootObservationBridge90Shadow.gate = null
        RootObservationBridge90Shadow.commands.clear()
    }
    @After fun finish() {
        RootObservationBridge90Shadow.gate?.countDown()
        worker.shutdownNow()
        assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS))
        assertEquals("Only the existing status read may execute", 1, RootObservationBridge90Shadow.commands.size)
    }

    @Test fun unchangedActualManagerStatusAdoptsItsNormalizedLivePort() {
        val reply = worker.submit<JSONObject> { RootProxyManager(app).status() }.get(5, TimeUnit.SECONDS)
        assertEquals(29091, reply.getInt("controllerPort"))
        assertEquals(29091, prefs.getInt("proxyControllerPort", 0))
    }

    @Test fun lateActualManagerStatusCannotReplaceANewerControlEndpoint() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        RootObservationBridge90Shadow.entered = entered; RootObservationBridge90Shadow.gate = release
        val pending = worker.submit<Result<JSONObject>> { runCatching { RootProxyManager(app).status() } }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            control.lock()
            try {
                prefs.edit().putInt("proxyControllerPort", 29108).putString("proxyNetworkSessionId", "replacement")
                    .putLong("proxyNetworkEpoch", 9L).commit()
            } finally { control.unlock() }
            val before = prefs.all
            release.countDown()
            assertTrue(pending.get(5, TimeUnit.SECONDS).exceptionOrNull() is IOException)
            assertEquals(before, prefs.all)
        } finally { release.countDown() }
    }

    @Test fun actualManagerStatusPreservesTheExistingSameThreadControlOwnerContract() {
        val reply = worker.submit<JSONObject> {
            control.lock()
            try {
                assertEquals("observe retains its original busy sentinel", -1L, RootProxyManager.observationTicket())
                RootProxyManager(app).status()
            } finally { control.unlock() }
        }.get(5, TimeUnit.SECONDS)
        assertEquals(29091, reply.getInt("controllerPort"))
        assertEquals(29091, prefs.getInt("proxyControllerPort", 0))
    }

    @Test fun anotherThreadsControlLockCannotLendStatusPublicationOwnership() {
        val before = prefs.all
        control.lock()
        try {
            val result = worker.submit<Result<JSONObject>> { runCatching { RootProxyManager(app).status() } }
                .get(5, TimeUnit.SECONDS)
            assertTrue(result.exceptionOrNull() is IOException)
            assertEquals(before, prefs.all)
        } finally { control.unlock() }
    }
}
