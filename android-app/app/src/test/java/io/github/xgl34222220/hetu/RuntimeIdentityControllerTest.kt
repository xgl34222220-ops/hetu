package io.github.xgl34222220.hetu

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.LooperMode
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowSystemClock
import java.io.IOException
import java.time.Duration

/** Real controller projection and VM initialization; privileged/API IO cannot call through. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, shadows = [
    RuntimeIdentityStatusShadow::class, ConceptRootBridgeShadow::class,
    ConceptMihomoClientShadow::class, ConceptRuntimeInspectorShadow::class,
])
@LooperMode(LooperMode.Mode.PAUSED)
class RuntimeIdentityControllerTest {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val prefs get() = app.getSharedPreferences("hetu", 0)
    private lateinit var library: ProxyConfigLibrary

    @Before fun prepare() {
        ConceptTestIo.reset()
        ShadowSystemClock.advanceBy(Duration.ofSeconds(1))
        RuntimeIdentityStatusShadow.reads = 0
        RuntimeIdentityStatusShadow.failure = null
        RuntimeIdentityStatusShadow.value = live()
        prefs.edit().clear().putBoolean("proxyRootWanted", true)
            .putBoolean("proxyRootRuntimeRunning", true).putBoolean("proxyUiLastRunning", true).commit()
        library = ProxyConfigLibrary(app)
        library.importConfig(ProxyRuntimeProfile.Core.MIHOMO, "A.yaml", "rules: [MATCH,DIRECT]\n".byteInputStream())
        prefs.edit().putString(RuntimeIdentity.RECORD_KEY,
            RuntimeIdentity.completed(live(), "mihomo", "tproxy", "A.yaml")).commit()
        assertTrue(Shadow.extract<Any>(RootProxyManager(app)) is RuntimeIdentityStatusShadow)
        assertTrue(Shadow.extract<Any>(MihomoControllerClient(app)) is ConceptMihomoClientShadow)
    }

    private fun live() = JSONObject().put("ok", true).put("running", true).put("pid", 142)
        .put("processStartTicks", "90112").put("bootId", "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")
        .put("mode", "tproxy").put("dataPlaneHealthy", true)

    @Test fun selectingAnotherCoreCannotRenameTheOldRunningProcess() = runBlocking {
        ProxyConfigLibrary.selectCore(prefs, "sing-box")
        prefs.edit().putString("proxyBaseMode", "tun").commit()
        library.importConfig(ProxyRuntimeProfile.Core.SING_BOX, "B.json", "{}".byteInputStream())
        val state = ProxyComposeController(app).state()
        assertTrue(state.running)
        assertTrue(state.runtimeIdentityConfirmed)
        assertEquals("Mihomo", state.core)
        assertEquals("TPROXY", state.mode)
        assertEquals("A.yaml", state.config)
        assertEquals("Sing-Box", state.selectedCore)
        assertEquals("TUN", state.selectedMode)
        assertEquals("B.json", state.selectedConfig)
    }

    @Test fun selectingAnotherSourceDoesNotClaimItWasApplied() = runBlocking {
        library.importConfig(ProxyRuntimeProfile.Core.MIHOMO, "B.yaml", "rules: [MATCH,DIRECT]\n".byteInputStream())
        val state = ProxyComposeController(app).state()
        assertEquals("A.yaml", state.config)
        assertEquals("B.yaml", state.selectedConfig)
    }

    @Test fun reusedPidCannotRestoreTheOldCoreAndSourceLabels() = runBlocking {
        RuntimeIdentityStatusShadow.value = live().put("processStartTicks", "90200")
        val state = ProxyComposeController(app).state()
        assertFalse(state.runtimeIdentityConfirmed)
        assertEquals(RuntimeIdentity.UNKNOWN, state.core)
        assertEquals(RuntimeIdentity.UNKNOWN, state.mode)
        assertEquals(RuntimeIdentity.UNKNOWN, state.config)
    }

    @Test fun verifiedIdentityRemainsStableDuringTheExistingHealthCacheInterval() = runBlocking {
        val controller = ProxyComposeController(app)
        assertTrue(controller.state().runtimeIdentityConfirmed)
        // Use a positive current monotonic stamp so the zero sentinel does not force a probe.
        val now = android.os.SystemClock.elapsedRealtime()
        prefs.edit().putLong("proxyRootHealthProbeElapsed", now).commit()
        RuntimeIdentityStatusShadow.failure = IOException("test probe must not run")
        val cached = controller.state()
        assertTrue(cached.runtimeIdentityConfirmed)
        assertEquals("A.yaml", cached.config)
        assertEquals(142, cached.corePid)
        assertEquals(1, RuntimeIdentityStatusShadow.reads)
    }

    @Test fun failedFreshProbeCannotSubstituteTheSelectedValues() = runBlocking {
        RuntimeIdentityStatusShadow.failure = IOException("synthetic unavailable")
        val state = ProxyComposeController(app).state()
        assertTrue(state.running)
        assertFalse(state.runtimeIdentityConfirmed)
        assertEquals(RuntimeIdentity.UNKNOWN, state.config)
    }

    @Test fun oldSessionsWithoutCompletedLaunchMetadataStayUnconfirmed() = runBlocking {
        prefs.edit().remove(RuntimeIdentity.RECORD_KEY).commit()
        val state = ProxyComposeController(app).state()
        assertFalse(state.runtimeIdentityConfirmed)
        assertEquals(RuntimeIdentity.UNKNOWN, state.core)
        assertEquals("Mihomo", state.selectedCore)
    }

    @Test fun vmDoesNotTurnSavedSelectionsIntoRuntimeFactsBeforeItsFirstProbe() {
        ProxyConfigLibrary.selectCore(prefs, "sing-box")
        prefs.edit().putString("proxyBaseMode", "tun").putString("proxyUiLastConfig", "old-cached.yaml").commit()
        val vm = HetuViewModel(app)
        assertTrue(vm.state.running)
        assertEquals(RuntimeIdentity.UNKNOWN, vm.state.core)
        assertEquals(RuntimeIdentity.UNKNOWN, vm.state.mode)
        assertEquals(RuntimeIdentity.UNKNOWN, vm.state.config)
        assertEquals("Sing-Box", vm.state.selectedCore)
        assertEquals("TUN", vm.state.selectedMode)
    }

    @Test fun failedCancelledOrRepeatedStartsNeverPublishARequestedIdentity() {
        for (result in listOf(live().put("ok", false), live().put("cancelled", true), live().put("alreadyRunning", true))) {
            assertEquals("", RuntimeIdentity.completed(result, "mihomo-smart", "tproxy", "new.yaml"))
        }
        val recorded = prefs.getString(RuntimeIdentity.RECORD_KEY, "")
        assertFalse(RuntimeIdentity.resolve(live().put("bootId", "11111111-2222-3333-4444-555555555555"), recorded).confirmed)
        assertFalse(RuntimeIdentity.resolve(live().put("mode", "tun"), recorded).confirmed)
    }

    @Test fun reloadCanUpdateTheSourceOnlyForTheConfirmedOriginalProcessAndCore() {
        val recorded = prefs.getString(RuntimeIdentity.RECORD_KEY, "")
        val updated = RuntimeIdentity.reloaded(live(), recorded, "mihomo", "tproxy", "B.yaml")
        assertEquals("B.yaml", RuntimeIdentity.resolve(live(), updated).source)
        assertEquals("", RuntimeIdentity.reloaded(live(), recorded, "mihomo-smart", "tproxy", "B.yaml"))
        assertEquals("", RuntimeIdentity.reloaded(live().put("processStartTicks", "90200"), recorded, "mihomo", "tproxy", "B.yaml"))
    }
}

@Implements(value = RootProxyManager::class, isInAndroidSdk = false, callThroughByDefault = false)
class RuntimeIdentityStatusShadow {
    companion object {
        var value = JSONObject()
        var failure: IOException? = null
        var reads = 0
    }
    @Implementation fun status(): JSONObject {
        reads++
        failure?.let { throw it }
        return JSONObject(value.toString())
    }
}
