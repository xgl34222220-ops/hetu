package io.github.xgl34222220.hetu

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RuntimeUpgradeTest {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val prefs get() = app.getSharedPreferences("hetu", Context.MODE_PRIVATE)

    @Before fun reset() { prefs.edit().clear().commit() }

    private fun replacePackage() = BootReceiver().onReceive(app, Intent(Intent.ACTION_MY_PACKAGE_REPLACED))

    @Test fun oldLiveRuntimeIsMarkedWithoutRestartingItsConnections() {
        prefs.edit().putBoolean("proxyRootRuntimeRunning", true).putBoolean("proxyRootWanted", true).commit()
        replacePackage()
        assertTrue(ProxyRuntimeSettings.pending(true, prefs))
        assertTrue(prefs.getBoolean("proxyRootRuntimeRefreshPending", false))
        assertTrue(prefs.getBoolean("proxyRootWanted", false))
        assertNull(shadowOf(app).nextStartedService)
        replacePackage()
        assertTrue(ProxyRuntimeSettings.pending(true, prefs))
        assertNull(shadowOf(app).nextStartedService)
    }

    @Test fun uiOnlyUpgradeAndStoppedCoreDoNotManufactureRestartRequirement() {
        prefs.edit().putBoolean("proxyRootRuntimeRunning", true)
            .putInt(ProxyRuntimeSettings.APPLIED_RUNTIME_REVISION_KEY, ProxyRuntimeSettings.RUNTIME_REVISION).commit()
        replacePackage()
        assertFalse(ProxyRuntimeSettings.pending(true, prefs))
        prefs.edit().putBoolean("proxyRootRuntimeRunning", false)
            .remove(ProxyRuntimeSettings.APPLIED_RUNTIME_REVISION_KEY).commit()
        replacePackage()
        assertFalse(ProxyRuntimeSettings.pending(false, prefs))
        assertFalse(prefs.getBoolean("proxyRootRuntimeRefreshPending", false))
        assertNull(shadowOf(app).nextStartedService)
    }

    @Test fun missedPackageBroadcastAndConfigReloadCannotHideUnappliedScriptFix() {
        // A live script still reports the old revision, even if the package-replaced
        // broadcast was missed or a config-only operation cleared an obsolete flag.
        prefs.edit().putBoolean("proxyRootRuntimeRunning", true)
            .putString("proxyRootAppliedSettings", ProxyRuntimeSettings.signature(prefs))
            .remove("proxyRootRuntimeRefreshPending").commit()
        assertTrue(ProxyRuntimeSettings.runtimeUpgradePending(true, prefs))
        assertTrue(ProxyRuntimeSettings.pending(true, prefs))
    }
}
