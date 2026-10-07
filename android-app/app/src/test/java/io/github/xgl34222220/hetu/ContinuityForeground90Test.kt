package io.github.xgl34222220.hetu

import android.app.Application
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.util.ReflectionHelpers
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

@Implements(value = RootBridge::class, isInAndroidSdk = false)
class ContinuityForegroundBridge90Shadow {
    companion object {
        var rootCalls = 0
        @JvmStatic @Implementation fun hasRoot(context: Context): Boolean { rootCalls++; throw AssertionError("foreground observer attachment must not probe Root") }
        @JvmStatic @Implementation fun rootShell(context: Context, command: String, timeout: Long): RootBridge.Result {
            rootCalls++; throw AssertionError("foreground observer attachment must not mutate Root")
        }
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, shadows = [ContinuityForegroundBridge90Shadow::class])
class ContinuityForeground90Test {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val prefs get() = app.getSharedPreferences("hetu", Context.MODE_PRIVATE)
    @Before fun reset() {
        prefs.edit().clear().putString("proxySelectedConfig.mihomo", "preserved.yaml")
            .putString("proxyRootSessionOwner", "manual").putBoolean("proxyRootAutoStart", false).commit()
        while (shadowOf(app).nextStartedService != null) { }
        ContinuityForegroundBridge90Shadow.rootCalls = 0
    }
    private fun nextGuard(): Intent {
        val intent = shadowOf(app).nextStartedService
        assertNotNull(intent)
        assertEquals(ProxyNetworkMatchService::class.java.name, intent!!.component!!.className)
        assertNull(intent.action) // no boot/adopt/start/restart command is injected
        return intent
    }
    private fun protectedIntent() = prefs.all.filterKeys {
        it in setOf("proxyRootWanted", "networkMatchEnabled", "proxyRootSessionOwner", "proxyRootAutoStart", "proxySelectedConfig.mihomo")
    }

    @Test fun explicitStopWithNoAutomationNeverRequestsAService() {
        prefs.edit().putBoolean("proxyRootWanted", false).putBoolean("networkMatchEnabled", false).commit()
        val before = prefs.all
        assertFalse(RootProxyManager(app).resumeContinuityFromForeground())
        assertNull(shadowOf(app).nextStartedService)
        assertEquals(before, prefs.all)
        assertEquals(0, ContinuityForegroundBridge90Shadow.rootCalls)
    }
    @Test fun wantedRuntimeReattachesExistingGuardWithoutChangingCoreOrConfigurationIntent() {
        prefs.edit().putBoolean("proxyRootWanted", true).putBoolean("proxyRootRuntimeRunning", true).commit()
        val before = protectedIntent()
        assertTrue(RootProxyManager(app).resumeContinuityFromForeground())
        nextGuard()
        assertNull(shadowOf(app).nextStartedService)
        assertEquals(before, protectedIntent())
        assertTrue(prefs.getLong("proxyContinuityResumeRequestedAt", 0) > 0)
        assertEquals(0, ContinuityForegroundBridge90Shadow.rootCalls)
    }
    @Test fun automationOnlyReattachesButDoesNotInventWantedOrManualOwnership() {
        prefs.edit().putBoolean("proxyRootWanted", false).putBoolean("networkMatchEnabled", true).commit()
        val before = protectedIntent()
        assertTrue(RootProxyManager(app).resumeContinuityFromForeground())
        nextGuard()
        assertEquals(before, protectedIntent())
        assertFalse(prefs.getBoolean("proxyRootWanted", true))
        assertEquals(0, ContinuityForegroundBridge90Shadow.rootCalls)
    }
    @Test fun repeatedForegroundRequestsUseTheSameExistingServiceComponentAndPreserveIntent() {
        prefs.edit().putBoolean("proxyRootWanted", true).commit()
        val root = RootProxyManager(app)
        val before = protectedIntent()
        repeat(2) { assertTrue(root.resumeContinuityFromForeground()); nextGuard() }
        assertEquals(before, protectedIntent())
        assertEquals(0, ContinuityForegroundBridge90Shadow.rootCalls)
    }
    @Test @Config(sdk = [26]) fun minimumSupportedApiUsesTheExistingForegroundServiceIntent() {
        prefs.edit().putBoolean("proxyRootWanted", true).commit()
        assertTrue(RootProxyManager(app).resumeContinuityFromForeground())
        nextGuard()
        assertEquals(0, ContinuityForegroundBridge90Shadow.rootCalls)
    }
    @Test fun failedAttachmentIsRecordedWithoutAFalseAcknowledgementOrSecretDetails() {
        prefs.edit().putBoolean("proxyRootWanted", true).commit()
        val failing = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun startForegroundService(intent: Intent): ComponentName? = throw IllegalStateException("fixture-secret-must-not-be-recorded")
        }
        val before = protectedIntent()
        assertFalse(RootProxyManager(failing).resumeContinuityFromForeground())
        assertEquals("IllegalStateException", prefs.getString("proxyContinuityResumeError", ""))
        assertTrue(prefs.getLong("proxyContinuityResumeFailedAt", 0) > 0)
        assertFalse(prefs.contains("proxyContinuityResumeRequestedAt"))
        assertEquals(before, protectedIntent())
        assertNull(shadowOf(app).nextStartedService)
    }
    @Test fun retryClearsTheActiveErrorOnlyAfterTheRequestIsAccepted() {
        prefs.edit().putBoolean("proxyRootWanted", true).putString("proxyContinuityResumeError", "old-failure").commit()
        assertTrue(RootProxyManager(app).resumeContinuityFromForeground())
        nextGuard()
        assertFalse(prefs.contains("proxyContinuityResumeError"))
        assertTrue(prefs.getBoolean("proxyRootWanted", false))
    }
    @Test fun stopBetweenRequestAndDeliveryIsRecheckedByTheRealServiceWithoutRootWork() {
        prefs.edit().putBoolean("proxyRootWanted", true).commit()
        assertTrue(RootProxyManager(app).resumeContinuityFromForeground())
        val request = nextGuard()
        prefs.edit().putBoolean("proxyRootWanted", false).putBoolean("networkMatchEnabled", false).commit()
        val controller = Robolectric.buildService(ProxyNetworkMatchService::class.java).create()
        val service = controller.get()
        try {
            ReflectionHelpers.getField<ExecutorService>(service, "metrics").shutdownNow()
            ReflectionHelpers.getField<ExecutorService>(service, "worker").shutdownNow()
            assertEquals(Service.START_NOT_STICKY, service.onStartCommand(request, 0, 1))
            assertFalse(prefs.getBoolean("proxyRootWanted", true))
            assertEquals(0, ContinuityForegroundBridge90Shadow.rootCalls)
        } finally {
            controller.destroy()
            assertTrue(ReflectionHelpers.getField<ExecutorService>(service, "journalWorker").awaitTermination(5, TimeUnit.SECONDS))
        }
    }
}
