package io.github.xgl34222220.hetu

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** The old sandbox is an original cache fixture; no shell or privileged filesystem is executed. */
@Implements(value = RootBridge::class, isInAndroidSdk = false)
class LegacyControllerMigrationShadow {
    companion object {
        var calls = 0
        var originalPrefs = ""
        var oldConfig = false
        @JvmStatic @Implementation fun rootShell(context: Context, command: String, timeout: Long): RootBridge.Result {
            calls++
            check(command.contains("bichen-app-migration") && command.contains("bichen.xml"))
            check(!command.contains("iptables") && !command.contains("service.d") && !command.contains("kill "))
            val stage = File(context.cacheDir, "bichen-app-migration")
            File(stage, "bichen.xml").writeText(originalPrefs)
            if (oldConfig) {
                val config = File(stage, "configs/mihomo/original-legacy.yaml")
                config.parentFile!!.mkdirs()
                config.writeText("proxies: []\nproxy-groups: []\nrules:\n  - MATCH,DIRECT\n")
            }
            return RootBridge.Result(0, "present=1\n")
        }
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, shadows = [LegacyControllerMigrationShadow::class])
class LegacyControllerCredentialTest {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val prefs get() = app.getSharedPreferences("hetu", 0)

    @Before fun reset() {
        prefs.edit().clear().commit()
        LegacyAppMigrator::class.java.getDeclaredField("attempted").apply { isAccessible = true }.setBoolean(null, false)
        LegacyControllerMigrationShadow.calls = 0
        LegacyControllerMigrationShadow.oldConfig = false
        LegacyControllerMigrationShadow.originalPrefs = """<?xml version="1.0" encoding="utf-8"?>
            <map>
              <string name="proxyControllerSecret">original-old-local-secret</string>
              <int name="proxyControllerPort" value="29098" />
              <boolean name="proxyRootWanted" value="true" />
              <string name="proxyRootAppliedSettings">old-settings-fingerprint</string>
              <string name="proxyRootTopologyFingerprint">old-topology</string>
              <string name="proxyRootValidatedFingerprint">old-validation</string>
              <string name="proxyRootCapabilityFingerprint">old-capability</string>
              <long name="proxyRootHealthProbeElapsed" value="98765" />
              <boolean name="proxyCustomApiEnabled" value="true" />
              <string name="proxyCustomApiHost">original-controller.example</string>
              <int name="proxyCustomApiPort" value="23456" />
              <string name="proxyCustomApiSecret">original-custom-secret</string>
              <string name="proxyBaseMode">tproxy</string>
              <string name="proxySelectedConfig.mihomo">original-legacy.yaml</string>
            </map>""".trimIndent()
    }

    private fun migrate() {
        val executor = Executors.newSingleThreadExecutor()
        try { executor.submit { LegacyAppMigrator.migrateIfNeeded(app) }.get(5, TimeUnit.SECONDS) }
        finally { executor.shutdownNow() }
        assertTrue(prefs.getBoolean("hetuLegacyAppDataMigrated", false))
        assertFalse(prefs.contains("hetuLegacyMigrationError"))
        assertEquals(1, LegacyControllerMigrationShadow.calls)
    }

    @Test fun oldMigrationCannotReplaceAnExistingLocalControllerSecret() {
        prefs.edit().putString("proxyControllerSecret", "original-current-local-secret").commit()
        migrate()
        assertEquals("original-current-local-secret", prefs.getString("proxyControllerSecret", ""))
    }

    @Test fun missingLocalIdentityIsNotImportedFromAnUnrelatedInstallation() {
        migrate()
        listOf("proxyControllerSecret", "proxyControllerPort", "proxyRootWanted", "proxyRootAppliedSettings",
            "proxyRootTopologyFingerprint", "proxyRootValidatedFingerprint", "proxyRootCapabilityFingerprint",
            "proxyRootHealthProbeElapsed").forEach { key -> assertFalse("Local runtime key imported: $key", prefs.contains(key)) }
    }

    @Test fun customApiAndOriginalConfigImportKeepTheirExistingMigrationBehavior() {
        LegacyControllerMigrationShadow.oldConfig = true
        migrate()
        assertTrue(prefs.getBoolean("proxyCustomApiEnabled", false))
        assertEquals("original-controller.example", prefs.getString("proxyCustomApiHost", ""))
        assertEquals(23456, prefs.getInt("proxyCustomApiPort", 0))
        assertEquals("original-custom-secret", prefs.getString("proxyCustomApiSecret", ""))
        assertEquals("tproxy", prefs.getString("proxyBaseMode", ""))
        assertEquals(1, prefs.getInt("hetuLegacyImportedConfigCount", 0))
        val selected = ProxyConfigLibrary(app).selected(ProxyRuntimeProfile.Core.MIHOMO)
        assertNotNull(selected)
        assertEquals("original-legacy.yaml", selected!!.name)
        assertTrue(ProxyConfigLibrary(app).read(selected).contains("MATCH,DIRECT"))
    }
}
