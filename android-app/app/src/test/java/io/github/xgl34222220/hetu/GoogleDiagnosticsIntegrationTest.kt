package io.github.xgl34222220.hetu

import android.app.Application
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import androidx.test.core.app.ApplicationProvider
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import java.io.IOException

/** Runs the actual diagnostic assembler; Root and Controller IO are intercepted. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class,
    shadows = [ConceptRootBridgeShadow::class, ConceptMihomoClientShadow::class])
class GoogleDiagnosticsIntegrationTest {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    @Before fun prepare() {
        ConceptTestIo.reset()
        app.getSharedPreferences("hetu", 0).edit().clear().putString("proxyControllerSecret", "TEST_CONTROLLER_SECRET").commit()
        val marker = RootBridge::class.java.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
        assertTrue(Shadow.extract<Any>(marker) is ConceptRootBridgeShadow)
        assertTrue(Shadow.extract<Any>(MihomoControllerClient(app)) is ConceptMihomoClientShadow)
        assertTrue(Shadow.extract<Any>(MihomoControllerClient.forLocalRuntime(app)) is ConceptMihomoClientShadow)
    }
    private fun install(name: String, uidValue: Int) {
        shadowOf(app.packageManager).installPackage(PackageInfo().apply {
            packageName = name
            applicationInfo = ApplicationInfo().apply { packageName = name; uid = uidValue; processName = name }
        })
        shadowOf(app.packageManager).setPackagesForUid(uidValue, name)
        assertEquals(uidValue, app.packageManager.getApplicationInfo(name, 0).uid)
    }
    private fun connection(uid: Int, host: String, chain: String) = JSONObject()
        .put("metadata", JSONObject().put("uid", uid).put("process", "").put("host", host)
            .put("destinationIP", "203.0.113.9").put("destinationPort", "443").put("network", "udp")
            .put("requestBody", "PRIVATE_BODY"))
        .put("rule", "RuleSet").put("rulePayload", "synthetic-google-rules")
        .put("chains", JSONArray().put(chain)).put("headers", JSONObject().put("Authorization", "PRIVATE_TOKEN"))

    @Test fun existingDiagnosticEntryReadsBoundCoreInventoryWithoutChangingPreferences() {
        install("com.example.otherproxy", 10125)
        val boot = "00000000-0000-4000-8000-000000000001"
        val prefs = app.getSharedPreferences("hetu", 0)
        prefs.edit().putString(RuntimeIdentity.RECORD_KEY, JSONObject().put("running", true)
            .put("pid", 101).put("processStartTicks", "100").put("bootId", boot).toString()).commit()
        ProxyConfigLibrary(app).selected(ProxyRuntimeProfile.Core.MIHOMO)
        val before = prefs.all.toMap()
        ConceptTestIo.diagnosticInventoryText = "inventory\t1\nboot\t$boot\n" +
            "process\t101\t100\t0\t1\tmihomo\tcore\thetu\tinit\n" +
            "process\t202\t200\t10125\t1\tmihomo\tmihomo\tapp-private\tinit\ncomplete\t2\t0\t0\n"
        val report = RootProxyManager(app).diagnostics()
        val section = report.substringAfter("--- 代理核心并存（只读） ---").substringBefore("\n--- ")
        assertTrue(section.contains("河图已绑定启动进程"))
        assertTrue(section.contains("2 个代理核心候选进程同时存在"))
        assertTrue(section.contains("com.example.otherproxy（系统UID映射）"))
        assertTrue(section.contains("不证明网站可达"))
        assertEquals(before, prefs.all)
        assertFalse(ConceptTestIo.calls.any { it.startsWith("PUT ") || it.startsWith("DELETE ") })
    }

    @Test fun verifiedPlayUidWithoutDnsNameUsesAnIndependentQuotaAndOneSnapshot() {
        install("com.android.vending", 10123)
        val entries = JSONArray()
        repeat(40) { entries.put(connection(20100 + it, "github.com", "Proxy")) }
        entries.put(connection(10123, "", "DIRECT"))
        ConceptTestIo.diagnosticConnectionsJson = JSONObject().put("connections", entries).toString()
        val report = RootProxyManager(app).diagnostics()
        val google = report.substringAfter("--- Google 相关连接（仅元数据） ---").substringBefore("\n--- ")
        assertTrue(google.contains("com.android.vending=uid:10123"))
        assertTrue(google.contains("uid=10123") && google.contains("host=未知"))
        assertTrue(google.contains("chains=DIRECT") && google.contains("rule=RuleSet/synthetic-google-rules"))
        assertTrue(google.contains("匹配 1 条，展示 1 条"))
        assertTrue(google.contains(GoogleConnectionDiagnostics.SNAPSHOT_LIMIT))
        assertFalse(google.contains("github.com"))
        assertFalse(report.contains("PRIVATE_BODY") || report.contains("PRIVATE_TOKEN") || report.contains("TEST_CONTROLLER_SECRET"))
        assertEquals(1, ConceptTestIo.calls.count { it == "GET /connections" })
    }

    @Test fun controllerFailureCannotBecomeAnEmptySuccessfulGoogleSnapshot() {
        install("com.google.android.gms", 10124)
        ConceptTestIo.diagnosticConnectionsFailure = IOException("synthetic read failure")
        val report = RootProxyManager(app).diagnostics()
        val google = report.substringAfter("--- Google 相关连接（仅元数据） ---").substringBefore("\n--- ")
        assertTrue(google.contains("读取失败") && google.contains("当前连接状态未知"))
        assertTrue(google.contains("com.google.android.gms=uid:10124"))
        assertFalse(google.contains("本次快照未匹配"))
        assertEquals(1, ConceptTestIo.calls.count { it == "GET /connections" })
    }

    @Test fun savedApplicationSelectionIsVisibleWithoutChangingSettingsOrClaimingLiveRouting() {
        install("com.android.vending", 10123)
        install("com.google.android.gms", 10124)
        shadowOf(app.packageManager).setPackagesForUid(10124, "com.google.android.gms", "private.shared.package")
        val prefs = app.getSharedPreferences("hetu", 0)
        prefs.edit().putString("proxyAppScope", "whitelist")
            .putStringSet("proxyAppPackages", setOf("0:com.android.vending", "private.shared.package"))
            .putBoolean("proxyRootSettingsDirty", true).commit()
        // Establish the normal configured-app baseline before checking that
        // diagnostics leave it unchanged; the library lazily seeds a bundled file.
        ProxyConfigLibrary(app).selected(ProxyRuntimeProfile.Core.MIHOMO)
        val before = prefs.all.toMap()
        ConceptTestIo.diagnosticConnectionsJson = JSONObject().put("connections", JSONArray()).toString()
        val report = RootProxyManager(app).diagnostics()
        val google = report.substringAfter("--- Google 相关连接（仅元数据） ---").substringBefore("\n--- ")
        assertTrue(google.contains("仅所选应用代理"))
        assertTrue(google.contains("com.android.vending=uid:10123；此包已列入名单"))
        assertTrue(google.contains("同 UID 的其他应用已列入名单"))
        assertTrue(google.contains("未证明设置已应用") && google.contains("故障原因仍未知"))
        assertFalse(google.contains("private.shared.package"))
        assertEquals(before, prefs.all)
        assertEquals(1, ConceptTestIo.calls.count { it == "GET /connections" })
    }
}
