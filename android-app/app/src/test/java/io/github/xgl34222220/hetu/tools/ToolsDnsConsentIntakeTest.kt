package io.github.xgl34222220.hetu.tools

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.VpnService
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.app.ActivityOptionsCompat
import androidx.test.core.app.ApplicationProvider
import io.github.xgl34222220.hetu.DnsVpnService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

/** Real adapter, actual ActivityResult contract and intercepted service intents; no VPN/Root runs. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, shadows = [ToolsDnsConsentVpnShadow::class])
class ToolsDnsConsentIntakeTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private lateinit var app: Application
    private lateinit var prefs: SharedPreferences
    private lateinit var actions: ToolsFeatureActions
    private lateinit var pageScope: CoroutineScope
    private val pageVisible = mutableStateOf(true)
    private val registry = ConsentRegistry()
    private var returnedNote: String? = null

    @Before fun prepare() {
        app = ApplicationProvider.getApplicationContext()
        prefs = app.getSharedPreferences("hetu", Context.MODE_PRIVATE)
        prefs.edit().clear().putBoolean("proxyAdblockFallbackEnabled", false)
            .putString("proxyAdblockFallbackMode", "hosts").putBoolean("autoStartVpn", false)
            .putBoolean("vpnWanted", false).commit()
        ToolsDnsConsentVpnShadow.prepareCalls = 0
        ToolsDnsConsentVpnShadow.prepareResult = Intent("hetu.fixture.VPN_CONSENT")
    }

    private fun host() {
        val owner = object : ActivityResultRegistryOwner {
            override val activityResultRegistry: ActivityResultRegistry = registry
        }
        rule.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                actions = rememberToolsFeatureHost().actions
                if (pageVisible.value) pageScope = rememberCoroutineScope()
            }
        }
        rule.waitForIdle()
    }

    private fun toggle(on: Boolean): Job {
        lateinit var job: Job
        returnedNote = null
        rule.runOnIdle { job = pageScope.launch { returnedNote = actions.setStandaloneDns(on) } }
        rule.waitForIdle()
        return job
    }

    private fun result(requestCode: Int, code: Int) {
        rule.runOnIdle { registry.dispatchResult(requestCode, code, null) }
        rule.waitForIdle()
    }

    private fun leavePage() {
        rule.runOnIdle { pageVisible.value = false }
        rule.waitForIdle()
    }

    private fun reopenPage() {
        rule.runOnIdle { pageVisible.value = true }
        rule.waitForIdle()
    }

    private fun assertNoService() = assertNull(shadowOf(app).nextStartedService)

    @Test fun deniedConsentPreservesExistingFallbackModeAndAutostart() {
        prefs.edit().putBoolean("proxyAdblockFallbackEnabled", true).commit()
        host()
        val before = prefs.all.toMap()
        val job = toggle(true)
        assertTrue(job.isActive)
        assertEquals(before, prefs.all)
        assertNoService()
        result(registry.requests.single(), Activity.RESULT_CANCELED)
        assertTrue(job.isCompleted)
        assertFalse(job.isCancelled)
        assertEquals("未授予 VPN 权限，独立 DNS 过滤未开启", returnedNote)
        assertEquals(before, prefs.all)
        assertNoService()
    }

    @Test fun grantedConsentCommitsAfterReturnAndStartsExistingVpnExactlyOnce() {
        prefs.edit().putBoolean("requestLogs", false).putString("vpnError", "previous error").commit()
        host()
        val before = prefs.all.toMap()
        val job = toggle(true)
        assertTrue(job.isActive)
        assertNull(returnedNote)
        assertEquals(before, prefs.all)
        assertNoService()
        val request = registry.requests.single()
        result(request, Activity.RESULT_OK)
        assertTrue(job.isCompleted)
        assertTrue(prefs.getBoolean("proxyAdblockFallbackEnabled", false))
        assertEquals("vpn", prefs.getString("proxyAdblockFallbackMode", ""))
        assertTrue(prefs.getBoolean("autoStartVpn", false))
        assertTrue(prefs.getBoolean("vpnWanted", false))
        assertTrue(prefs.getBoolean("requestLogs", false))
        assertFalse(prefs.contains("vpnError"))
        val started = shadowOf(app).nextStartedService
        assertNotNull(started)
        assertEquals(DnsVpnService.ACTION_START, started!!.action)
        assertEquals(DnsVpnService::class.java.name, started.component!!.className)
        assertEquals("独立 DNS 过滤已启用；Root 代理启动时自动暂停", returnedNote)
        assertNoService()
        result(request, Activity.RESULT_OK)
        assertNoService()
    }

    @Test fun explicitOffCancelsPendingEnableAndLateConsentCannotUndoOff() {
        prefs.edit().putBoolean("proxyAdblockFallbackEnabled", true)
            .putBoolean("autoStartVpn", true).putBoolean("vpnWanted", true).commit()
        host()
        val before = prefs.all.toMap()
        val pending = toggle(true)
        val request = registry.requests.single()
        assertEquals(before, prefs.all)
        assertNoService()
        assertTrue(toggle(false).isCompleted)
        assertTrue(pending.isCancelled)
        assertFalse(prefs.getBoolean("proxyAdblockFallbackEnabled", true))
        assertFalse(prefs.getBoolean("autoStartVpn", true))
        assertFalse(prefs.getBoolean("vpnWanted", true))
        assertEquals(DnsVpnService.ACTION_STOP, shadowOf(app).nextStartedService!!.action)
        val disabled = prefs.all.toMap()
        result(request, Activity.RESULT_OK)
        assertEquals(disabled, prefs.all)
        assertEquals("独立 DNS 过滤已关闭", returnedNote)
        assertNoService()
    }

    @Test fun cancelledRequestCannotAuthorizeTheNextPageRequest() {
        host()
        val before = prefs.all.toMap()
        val first = toggle(true)
        val oldRequest = registry.requests.single()
        leavePage()
        assertTrue(first.isCancelled)
        assertEquals(before, prefs.all)
        assertNoService()
        reopenPage()
        val next = toggle(true)
        val newRequest = registry.requests.last()
        assertEquals(2, registry.requests.size)
        assertNotEquals(oldRequest, newRequest)
        result(oldRequest, Activity.RESULT_OK)
        assertTrue(next.isActive)
        assertNull(returnedNote)
        assertEquals(before, prefs.all)
        assertNoService()
        result(newRequest, Activity.RESULT_CANCELED)
        assertTrue(next.isCompleted)
        assertFalse(next.isCancelled)
        assertEquals(before, prefs.all)
        assertNoService()
    }

    @Test fun runningRootPreservesOnOffIntentAndNeverLaunchesOrStartsDnsVpn() {
        prefs.edit().putBoolean("proxyRootRuntimeRunning", true).putBoolean("proxyRootWanted", true)
            .putBoolean("vpnWanted", true).commit()
        host()
        assertTrue(toggle(true).isCompleted)
        assertEquals("已保存；Root 代理停止后自动恢复独立 DNS 过滤", returnedNote)
        assertTrue(prefs.getBoolean("proxyAdblockFallbackEnabled", false))
        assertTrue(prefs.getBoolean("autoStartVpn", false))
        assertEquals("vpn", prefs.getString("proxyAdblockFallbackMode", ""))
        assertTrue(toggle(false).isCompleted)
        assertEquals("已关闭代理停止后的独立 DNS 过滤", returnedNote)
        assertFalse(prefs.getBoolean("proxyAdblockFallbackEnabled", true))
        assertFalse(prefs.getBoolean("autoStartVpn", true))
        assertTrue(prefs.getBoolean("vpnWanted", false))
        assertTrue(prefs.getBoolean("proxyRootRuntimeRunning", false))
        assertTrue(prefs.getBoolean("proxyRootWanted", false))
        assertEquals(0, ToolsDnsConsentVpnShadow.prepareCalls)
        assertTrue(registry.requests.isEmpty())
        assertNoService()

        // Root starting while the consent screen is open must also retain service ownership.
        rule.runOnIdle { prefs.edit().putBoolean("proxyRootRuntimeRunning", false).commit() }
        val pending = toggle(true)
        assertTrue(pending.isActive)
        rule.runOnIdle { prefs.edit().putBoolean("proxyRootRuntimeRunning", true).commit() }
        result(registry.requests.single(), Activity.RESULT_OK)
        assertTrue(pending.isCompleted)
        assertTrue(prefs.getBoolean("proxyAdblockFallbackEnabled", false))
        assertTrue(prefs.getBoolean("autoStartVpn", false))
        assertEquals("已保存；Root 代理停止后自动恢复独立 DNS 过滤", returnedNote)
        assertNoService()
    }

    private class ConsentRegistry : ActivityResultRegistry() {
        val requests = mutableListOf<Int>()
        override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
            assertTrue(input is Intent)
            assertEquals("hetu.fixture.VPN_CONSENT", (input as Intent).action)
            requests += requestCode
        }
    }
}

/** Only the Android consent boundary is controlled; adapter and controller code stay real. */
@Implements(VpnService::class)
class ToolsDnsConsentVpnShadow {
    companion object {
        @JvmField var prepareCalls = 0
        @JvmField var prepareResult: Intent? = null
        @JvmStatic @Implementation fun prepare(context: Context): Intent? {
            prepareCalls++
            return prepareResult
        }
    }
}
