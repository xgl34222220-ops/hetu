package io.github.xgl34222220.hetu

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import io.github.xgl34222220.hetu.tools.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** New tool routes preserve runtime truth and the established configuration/list safeguards. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class UI83ToolsTest {
    private fun verified() = ToolsAdblockState(enabled = true, proxyRunning = true,
        modeKnown = true, ruleMode = true, startupInjected = true, controllerLoaded = true, hits = 17)

    @Test fun unknownTrafficModeNeverClaimsProtectionOrOldHits() {
        val unknown = verified().copy(modeKnown = false, ruleMode = false)
        assertEquals(ToolsAdStatus.Unverified, unknown.status)
        assertFalse(unknown.effective)
        assertEquals(0L, unknown.shownHits)
    }

    @Test fun missingStartupOrLiveProviderKeepsProtectionUnverified() {
        listOf(verified().copy(startupInjected = false), verified().copy(controllerLoaded = false)).forEach {
            assertEquals(ToolsAdStatus.Unverified, it.status)
            assertFalse(it.effective)
            assertEquals(0L, it.shownHits)
        }
    }

    @Test fun confirmedRuleChainShowsItsActualSessionHits() {
        assertEquals(ToolsAdStatus.Protecting, verified().status)
        assertEquals(17L, verified().shownHits)
        assertEquals(ToolsAdStatus.Waiting, verified().copy(proxyRunning = false).status)
        assertEquals(ToolsAdStatus.Off, verified().copy(enabled = false).status)
    }

    @Test fun confirmedGlobalModeHasAWarningEvenWhenProviderExists() {
        val global = verified().copy(ruleMode = false, modeLabel = "全局")
        assertEquals(ToolsAdStatus.WrongMode, global.status)
        assertEquals(0L, global.shownHits)
    }

    @Test fun malformedHttpHostsStayInlineErrors() {
        listOf("https://", "https://:", "https:///config.yaml", "https://exa mple.com/config.yaml",
            "example.com/config.yaml", "ftp://example.com/config.yaml", "https://example.com/\nfile",
            "https://example.com/" + "a".repeat(4096)).forEach { assertNotNull(it, ToolsRules.urlError(it)) }
        listOf("https://example.com/config.yaml", "http://127.0.0.1:3000/sub", "http://[::1]:3000/sub")
            .forEach { assertNull(it, ToolsRules.urlError(it)) }
    }

    @Test fun cidrValidationRejectsOutOfRangeAddressesAndMasks() {
        listOf("999.1.1.1/24", "192.168.1/24", "192.168.1.0/33", ":::/64", "2001:db8::/129")
            .forEach { assertFalse(it, ToolsFeatureRules.isCidr(it)) }
        listOf("192.168.1.0/24", "0.0.0.0/0", "2001:db8::/32", "::1/128")
            .forEach { assertTrue(it, ToolsFeatureRules.isCidr(it)) }
    }

    @Test fun switchingScopesParksLatestListFromTheExistingSettingsScreen() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val prefs = app.getSharedPreferences("hetu", 0)
        prefs.edit().clear().putString("proxyAppScope", "blacklist")
            .putStringSet("proxyAppBlacklist", setOf("stale.blacklist"))
            .putStringSet("proxyAppWhitelist", setOf("saved.whitelist"))
            .putStringSet("proxyAppPackages", setOf("legacy.latest")) .commit()
        ToolsRuntimeBridge.setAppScope(app, "whitelist")
        assertEquals(setOf("legacy.latest"), prefs.getStringSet("proxyAppBlacklist", emptySet()))
        assertEquals(setOf("saved.whitelist"), prefs.getStringSet("proxyAppPackages", emptySet()))
        ToolsRuntimeBridge.setAppScope(app, "blacklist")
        assertEquals(setOf("legacy.latest"), prefs.getStringSet("proxyAppPackages", emptySet()))
    }

    @Test fun validationCannotOverwriteAChangedSelectionOrExternalSource() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val prefs = app.getSharedPreferences("hetu", 0)
        prefs.edit().clear().commit()
        val core = ProxyRuntimeProfile.load(prefs).core
        val library = ProxyConfigLibrary(app)
        val first = library.importConfig(core, "ui83-first.yaml", "mode: rule\n".byteInputStream())
        val second = library.importConfig(core, "ui83-second.yaml", "mode: direct\n".byteInputStream())
        library.select(core, first.name)
        val original = ToolsConfigBridge.document(app)
        val selection = ToolsConfigBridge.saveDocument(app, original, "mode: global\n") { library.select(core, second.name) }
        assertTrue(selection is ToolsSaveResult.SelectionChanged)
        assertEquals("mode: rule\n", library.read(first))
        assertEquals("mode: direct\n", library.read(second))
        library.select(core, first.name)
        val sourceChanged = ToolsConfigBridge.saveDocument(app, original, "mode: global\n") { library.write(first, "mode: direct\n") }
        assertEquals(ToolsSaveResult.SourceChanged, sourceChanged)
        assertEquals("mode: direct\n", library.read(first))
    }
}
