package io.github.xgl34222220.hetu

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * 用户决定：选择非 Mihomo 核心（sing-box / reF1nd / Xray / V2Fly / Hysteria）且该核心尚未选中自己的配置时，
 * 自动选中它的内置模板；已有自己配置的核心与 Mihomo / Smart 用户不受影响。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class CoreTemplateAutoSelect104Test {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val prefs get() = context.getSharedPreferences("hetu", 0)
    private val nonMihomo = listOf(ProxyRuntimeProfile.Core.SING_BOX, ProxyRuntimeProfile.Core.SING_BOX_REF1ND,
        ProxyRuntimeProfile.Core.XRAY, ProxyRuntimeProfile.Core.V2FLY, ProxyRuntimeProfile.Core.HYSTERIA)

    @Before fun reset() {
        prefs.edit().clear().commit()
        File(context.filesDir, "hetu/configs").deleteRecursively()
    }

    @Test fun pickingANonMihomoCoreWithoutItsOwnConfigSelectsItsBuiltInTemplate() {
        val library = ProxyConfigLibrary(context)
        for (core in nonMihomo) {
            assertNull(library.selected(core))
            val name = BundledCoreTemplates.name(core)!!
            assertEquals(name, library.adoptTemplateOnCoreSelect(core))
            assertEquals(name, library.selected(core)!!.name)
            assertEquals(name, prefs.getString("proxySelectedConfig.${core.id}", ""))
            // Picking the same core again keeps the selection and reports no change.
            assertNull(library.adoptTemplateOnCoreSelect(core))
            assertEquals(name, library.selected(core)!!.name)
        }
    }

    @Test fun aCoreThatAlreadyHasItsOwnConfigKeepsIt() {
        val library = ProxyConfigLibrary(context)
        val own = mapOf(
            ProxyRuntimeProfile.Core.SING_BOX to ("mine.json" to "{\"outbounds\":[{\"type\":\"direct\",\"tag\":\"d\"}]}"),
            ProxyRuntimeProfile.Core.XRAY to ("mine.json" to "{\"outbounds\":[{\"protocol\":\"freedom\"}]}"),
            ProxyRuntimeProfile.Core.HYSTERIA to ("mine.yaml" to "server: node.example.com:443\nauth: x\n"),
        )
        for ((core, file) in own) {
            val entry = library.importConfig(core, file.first, file.second.byteInputStream())
            library.select(core, entry.name)
            assertNull(library.adoptTemplateOnCoreSelect(core))
            assertEquals(entry.name, library.selected(core)!!.name)
        }
    }

    @Test fun aSelectionWhoseFileIsGoneCountsAsNoConfig() {
        val library = ProxyConfigLibrary(context)
        val core = ProxyRuntimeProfile.Core.V2FLY
        prefs.edit().putString("proxySelectedConfig.${core.id}", "deleted.json").commit()
        assertNull(library.selected(core))
        assertEquals(BundledCoreTemplates.V2FLY_NAME, library.adoptTemplateOnCoreSelect(core))
        assertEquals(BundledCoreTemplates.V2FLY_NAME, library.selected(core)!!.name)
    }

    @Test fun mihomoAndSmartUsersAreUntouched() {
        val library = ProxyConfigLibrary(context)
        val mihomo = ProxyRuntimeProfile.Core.MIHOMO
        val smart = ProxyRuntimeProfile.Core.MIHOMO_SMART
        val before = library.selected(mihomo)?.name
        assertNull(library.adoptTemplateOnCoreSelect(mihomo))
        assertNull(library.adoptTemplateOnCoreSelect(smart))
        assertEquals(before, library.selected(mihomo)?.name)
        val smartKey = prefs.getString("proxySelectedConfig.${smart.id}", null)
        assertNull(library.adoptTemplateOnCoreSelect(smart))
        assertEquals(smartKey, prefs.getString("proxySelectedConfig.${smart.id}", null))
        // Choosing Mihomo never selects a non-Mihomo template behind the user's back.
        for (core in nonMihomo) assertNull(library.selected(core))
    }
}
