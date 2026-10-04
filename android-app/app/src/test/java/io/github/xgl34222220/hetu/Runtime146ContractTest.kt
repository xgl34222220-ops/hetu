package io.github.xgl34222220.hetu

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class Runtime146ContractTest {
    private fun prefs() = ApplicationProvider.getApplicationContext<Application>().getSharedPreferences("contract", 0).also { it.edit().clear().commit() }
    @Test fun matchingAppliedSnapshotDoesNotAskForRepeatedRestart() {
        val p = prefs()
        p.edit().putString(ProxyRuntimeSettings.APPLIED_SETTINGS_KEY, ProxyRuntimeSettings.signature(p)).putBoolean(ProxyRuntimeSettings.DIRTY_KEY, true).commit()
        assertFalse(ProxyRuntimeSettings.pending(true, p))
    }
    @Test fun preferenceChangedDuringApplyRemainsPending() {
        val p = prefs()
        val captured = ProxyRuntimeSettings.signature(p)
        p.edit().putString(ProxyRuntimeSettings.APPLIED_SETTINGS_KEY, captured).putBoolean("proxySharedNetwork", true).commit()
        assertTrue(ProxyRuntimeSettings.acknowledgeApplied(p, true))
        assertTrue(ProxyRuntimeSettings.pending(true, p))
    }
    @Test fun failedApplyNeverAcknowledgesTheNewConfiguration() {
        val p = prefs()
        val captured = ProxyRuntimeSettings.signature(p)
        p.edit().putString(ProxyRuntimeSettings.APPLIED_SETTINGS_KEY, captured).putBoolean("proxySharedNetwork", true).commit()
        ProxyRuntimeSettings.recordFailure(p, IllegalStateException("fixture error"))
        assertFalse(ProxyRuntimeSettings.acknowledgeApplied(p, false))
        assertEquals(captured, p.getString(ProxyRuntimeSettings.APPLIED_SETTINGS_KEY, ""))
        assertTrue(ProxyRuntimeSettings.pending(true, p))
    }
    @Test fun uiVersionBumpDoesNotRequestRuntimeRedeployment() {
        val p = prefs()
        // V20.74 deploys the corrected snapshot on the next explicit Start/Restart.
        assertEquals(151, ProxyRuntimeSettings.RUNTIME_REVISION)
        assertFalse(ProxyRuntimeSettings.runtimeUpgradePending(true, 149, true))
        assertFalse(ProxyRuntimeSettings.runtimeUpgradePending(true, 150, true))
        assertFalse(ProxyRuntimeSettings.runtimeUpgradePending(true, 492, true))
        assertFalse(ProxyRuntimeSettings.runtimeUpgradePending(true, p))
    }
    @Test fun renamedLatencyTargetsKeepTheirSlotsAndActualMeasurements() {
        val p = prefs()
        val targets = listOf(ProxyLatencyTarget("服务一", "https://one.invalid/"), ProxyLatencyTarget("服务二", "https://two.invalid/"), ProxyLatencyTarget("服务三", "https://three.invalid/"))
        ProxyLatencyTargets.save(p, targets)
        ProxyLatencyTargets.persistLast(p, linkedMapOf("服务一" to 44L, "服务二" to -1L, "服务三" to 90L))
        assertEquals(targets, ProxyLatencyTargets.load(p))
        assertEquals(listOf("服务一", "服务三", "服务二"), CompactHomeFormat.ordered(ProxyLatencyTargets.lastResults(p), true, targets.map { it.name }))
    }
    @Test fun officialCoreAssetsMatchTheUser146Apk() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        fun hash(name: String): String = context.assets.open(name).use { stream ->
            val md = java.security.MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(65536)
            while (true) { val n = stream.read(buffer); if (n < 0) break; md.update(buffer, 0, n) }
            md.digest().joinToString("") { "%02x".format(it) }
        }
        assertEquals("2eb2ffd88f8822dd7d9a3e68f37661660f46073ded2d1faf93698f47084e68e1", hash("mihomo-root/arm64-v8a/mihomo"))
        assertEquals("e96fb9a38ebd38207c99b01f019e1fd0b2a81b308f8f47280fbc9c42f2911b61", hash("mihomo-root/x86_64/mihomo"))
    }
}
