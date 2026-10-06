package io.github.xgl34222220.hetu

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implements
import org.robolectric.annotation.Implementation
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.ExecutionException

@Implements(value = RootBridge::class, isInAndroidSdk = false)
class HealthUpgradeBridgeShadow {
    companion object {
        val commands = mutableListOf<String>()
        var integrity = "degraded"
        var fault = "listener-11053-udp"
        var deny = false
        var allowRepair = false
        var granted = true
        @JvmStatic @Implementation fun hasRoot(context: Context): Boolean = granted
        @JvmStatic @Implementation fun rootShell(context: Context, command: String, timeout: Long): RootBridge.Result {
            commands += command
            require(!command.contains("service.d") && !command.contains("iptables") && !command.contains("kill "))
            if (command.contains("hetu-health-checker.sh")) {
                require(!command.contains("/bin/core") && !command.contains("startup-config"))
                if (deny) return RootBridge.Result(126, "inspector unavailable")
                if (command.endsWith("'repair-session'")) return RootBridge.Result(0,
                    JSONObject().put("ok", allowRepair).put("message", if (allowRepair) "已核对运行记录" else "拒绝不匹配的运行记录").toString())
                require(command.endsWith("'network-health'"))
                return RootBridge.Result(0, JSONObject().put("ok", true).put("networkIntegrity", integrity)
                    .put("networkFault", fault).put("sessionManifestState", "current")
                    .put("baselineRepairAvailable", false).put("dataPlaneHealthy", integrity == "healthy").toString())
            }
            require(command.contains("'status'"))
            // Old deployed status and an egress success must not override the
            // current inspector's more complete rule/listener/daemon assessment.
            return RootBridge.Result(0, """{"ok":true,"running":true,"dataPlaneHealthy":true,"controllerPort":29094,"pid":3087}""")
        }
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, shadows = [HealthUpgradeBridgeShadow::class])
class RootHealthUpgradeTest {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val prefs get() = app.getSharedPreferences("hetu", 0)
    @Before fun prepare() {
        HealthUpgradeBridgeShadow.commands.clear(); HealthUpgradeBridgeShadow.deny = false
        HealthUpgradeBridgeShadow.allowRepair = false; HealthUpgradeBridgeShadow.integrity = "degraded"
        HealthUpgradeBridgeShadow.granted = true
        HealthUpgradeBridgeShadow.fault = "listener-11053-udp"
        prefs.edit().clear().putInt("proxyRootAppliedRuntimeRevision", 149).putBoolean("proxyRootAutoStart", true)
            .putString("proxyPolicyEgressState", "reachable").putString("proxyNetworkIntegrity", "upgrade-required").commit()
    }
    private fun <T> worker(action: (RootProxyManager) -> T): T {
        val executor = Executors.newSingleThreadExecutor()
        try { return executor.submit<T> { action(RootProxyManager(app)) }.get() }
        finally { executor.shutdownNow() }
    }

    @Test fun readOnlyInspectorDoesNotInvalidateObservationPublication() {
        val ticket = RootProxyManager.observationTicket()
        assertEquals("degraded", worker { it.networkHealth() }.getString("networkIntegrity"))
        var published = false
        assertTrue(RootProxyManager.publishObservation(ticket) { published = true })
        assertTrue(published)
        assertEquals(149, prefs.getInt("proxyRootAppliedRuntimeRevision", 0))
    }
    @Test fun reachableEgressAndOldRunningStatusCannotMaskABrokenDnsListener() {
        val state = worker { it.status() }
        assertTrue(state.getBoolean("running")); assertFalse(state.getBoolean("dataPlaneHealthy"))
        assertEquals("listener-11053-udp", state.getString("networkFault"))
        assertEquals("reachable", prefs.getString("proxyPolicyEgressState", ""))
    }
    @Test fun failedInspectorDoesNotFallBackToAnOldHealthyResult() {
        HealthUpgradeBridgeShadow.deny = true
        try { worker { it.status() }; fail("failed observation must not claim health") }
        catch (error: ExecutionException) { assertTrue(error.cause is IOException) }
        assertEquals("upgrade-required", prefs.getString("proxyNetworkIntegrity", ""))
    }
    @Test fun refusedRepairPreservesDeploymentAndAutostartState() {
        try { worker { it.repairSessionRecord() }; fail("refused repair must remain refused") }
        catch (error: ExecutionException) { assertTrue(error.cause is IOException) }
        assertEquals(149, prefs.getInt("proxyRootAppliedRuntimeRevision", 0)); assertTrue(prefs.getBoolean("proxyRootAutoStart", false))
        assertEquals("upgrade-required", prefs.getString("proxyNetworkIntegrity", ""))
        assertEquals(1, HealthUpgradeBridgeShadow.commands.size)
    }
    @Test fun metadataRepairDoesNotClaimRuntimeUpgradeOrHideSubsequentFaults() {
        HealthUpgradeBridgeShadow.allowRepair = true
        assertTrue(worker { it.repairSessionRecord() }.getString("message").contains("listener-11053-udp"))
        assertEquals(149, prefs.getInt("proxyRootAppliedRuntimeRevision", 0)); assertTrue(prefs.getBoolean("proxyRootAutoStart", false))
        assertEquals("degraded", prefs.getString("proxyNetworkIntegrity", ""))
        assertEquals("listener-11053-udp", prefs.getString("proxyNetworkFault", ""))
        assertEquals(2, HealthUpgradeBridgeShadow.commands.size)
    }
    @Test fun unauthorizedRepairCannotDeployAnInspectorOrChangeSessionPreferences() {
        HealthUpgradeBridgeShadow.granted = false
        try { worker { it.repairSessionRecord() }; fail("ungranted Root must be refused") }
        catch (error: ExecutionException) { assertTrue(error.cause!!.message!!.contains("无法取得 Root 权限")) }
        assertTrue(HealthUpgradeBridgeShadow.commands.isEmpty())
        assertEquals(149, prefs.getInt("proxyRootAppliedRuntimeRevision", 0))
        assertEquals("upgrade-required", prefs.getString("proxyNetworkIntegrity", ""))
    }
    @Test fun supersededRecoveryCannotRunRootCommandsOrChangeDeployment() {
        prefs.edit().putBoolean("proxyRootWanted", true).commit()
        val result = worker { it.startIfWanted(ProxyRuntimeProfile.load(prefs)) { false } }
        assertTrue(result.getBoolean("cancelled"))
        assertEquals("network-changed", result.getString("reason"))
        assertTrue(HealthUpgradeBridgeShadow.commands.isEmpty())
        assertTrue(prefs.getBoolean("proxyRootWanted", false))
        assertEquals(149, prefs.getInt("proxyRootAppliedRuntimeRevision", 0))
        assertTrue(prefs.getBoolean("proxyRootAutoStart", false))
    }
}
