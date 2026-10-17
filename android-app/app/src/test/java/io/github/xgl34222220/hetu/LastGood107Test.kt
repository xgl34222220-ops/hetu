package io.github.xgl34222220.hetu

import android.app.Application
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import io.github.xgl34222220.hetu.home.HetuHomeTheme
import io.github.xgl34222220.hetu.home.HomeActions
import io.github.xgl34222220.hetu.home.HomeLastGood
import io.github.xgl34222220.hetu.home.HomeScreen
import io.github.xgl34222220.hetu.home.HomeStartFailedSheetContent
import io.github.xgl34222220.hetu.home.HomeStatus
import io.github.xgl34222220.hetu.home.HomeUiState
import io.github.xgl34222220.hetu.ui.LocalHetuMotionEnabled
import io.github.xgl34222220.hetu.ui.translateHetuText
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 2026-10-13 audit G1 「上次可启动」: a successful manual start keeps its effective source in private
 * storage; the start-failure sheet can start from that copy without touching the user's file, and
 * home says so while that session runs. The privileged transport is the existing full-path fixture
 * of RootStartTransaction93Test (reused, not changed); preparation and startInternal are real.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "zh-rCN-w393dp-h852dp-mdpi", application = Application::class,
    shadows = [RootStartTransaction93BridgeShadow::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LastGood107Test {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val prefs get() = app.getSharedPreferences("hetu", Context.MODE_PRIVATE)
    private val workers = Executors.newSingleThreadExecutor()
    private fun source(path: String) = File("src/main/java/io/github/xgl34222220/hetu/$path").readText()
    private fun yaml(node: String) = """
        mixed-port: 7890
        mode: rule
        proxies:
          - name: $node
            type: socks5
            server: 127.0.0.1
            port: 1080
        proxy-groups:
          - name: fixture-group
            type: select
            proxies: [$node]
        rules:
          - MATCH,fixture-group
    """.trimIndent() + "\n"

    @Before fun clean() {
        RootStartTransaction93BridgeShadow.commands.clear()
        RootStartTransaction93BridgeShadow.nativeStarts.set(0)
        RootStartTransaction93BridgeShadow.nativeStops.set(0)
        RootStartTransaction93BridgeShadow.readTokens.clear()
        RootStartTransaction93BridgeShadow.cancelToken = ""
        RootStartTransaction93BridgeShadow.tokenReadEntered = null
        RootStartTransaction93BridgeShadow.tokenReadRelease = null
        RootStartTransaction93BridgeShadow.bootInstallEntered = null
        RootStartTransaction93BridgeShadow.bootInstallRelease = null
        RootStartTransaction93BridgeShadow.deploymentAdvanceMillis = 0L
        RootStartTransaction93BridgeShadow.cleanupOk = true
        RootStartTransaction93BridgeShadow.preludeRefusal = null
        DnsVpnService.running = false
        prefs.edit().clear().putString("appearance", "light").putBoolean("enableBlur", false)
            .putBoolean("proxyRootWanted", false).putBoolean("proxyRootRuntimeRunning", false)
            .putBoolean("proxyRootAutoStart", false).putBoolean("proxyAdblockChain", false)
            .putBoolean("proxyAdblockFallbackEnabled", false).putBoolean("vpnWanted", false)
            .putBoolean("hetuLegacyRetired", true).putBoolean("proxyCnIpDirect", false)
            .putString("proxyAppScope", "core").commit()
        LastGoodConfig.file(app, "mihomo").delete()
        android.provider.Settings.Global.putInt(app.contentResolver, android.provider.Settings.Global.BOOT_COUNT, 93)
    }

    @After fun finish() {
        workers.shutdownNow()
        assertTrue(workers.awaitTermination(8, TimeUnit.SECONDS))
        DnsVpnService.running = false
    }

    @Test fun copyRoundTripsAndRejectsBlankOversizedAndTamperedText() {
        assertFalse(LastGoodConfig.save(app, "mihomo", "tproxy", "a.yaml", "  \n"))
        assertFalse(LastGoodConfig.save(app, "mihomo", "tproxy", "a.yaml", "x".repeat(LastGoodConfig.MAX_BYTES + 1)))
        assertNull(LastGoodConfig.info(prefs, "mihomo"))
        assertTrue(LastGoodConfig.save(app, "mihomo", "tproxy", "日常.yaml", "proxies: []\n", 1_000L))
        val info = LastGoodConfig.info(prefs, "mihomo")!!
        assertEquals("日常.yaml", info.label)
        assertEquals("tproxy", info.mode)
        assertEquals(1_000L, info.savedAt)
        assertEquals("proxies: []\n", LastGoodConfig.read(app, "mihomo"))
        assertNull("Another core has its own copy", LastGoodConfig.info(prefs, "sing-box"))
        LastGoodConfig.file(app, "mihomo").writeText("proxies: [changed]\n")
        assertNull("A copy that no longer matches its SHA-256 is never started", LastGoodConfig.read(app, "mihomo"))
        assertTrue(LastGoodConfig.save(app, "mihomo", "tproxy", "", "proxies: []\n"))
        assertEquals(LastGoodConfig.SCRIPTS_ONLY, LastGoodConfig.info(prefs, "mihomo")!!.label)
    }

    @Test fun savingTheCopyNeverMarksRuntimeSettingsPending() {
        val before = ProxyRuntimeSettings.signature(prefs)
        assertTrue(LastGoodConfig.save(app, "mihomo", "tproxy", "a.yaml", "proxies: []\n"))
        LastGoodConfig.markActive(prefs, "mihomo", "fp")
        assertEquals(before, ProxyRuntimeSettings.signature(prefs))
    }

    @Test fun activeOnlyForTheSessionThatWasStartedFromTheCopy() {
        assertTrue(LastGoodConfig.save(app, "mihomo", "tproxy", "a.yaml", "proxies: []\n"))
        prefs.edit().putString("proxyRootValidatedFingerprint", "session-1").commit()
        LastGoodConfig.markActive(prefs, "mihomo", "session-1")
        assertEquals("a.yaml", LastGoodConfig.active(prefs, running = true)?.label)
        assertNull("Not running: nothing is shown", LastGoodConfig.active(prefs, running = false))
        prefs.edit().putString("proxyRootValidatedFingerprint", "session-2").commit()
        assertNull("A later start publishes another fingerprint", LastGoodConfig.active(prefs, running = true))
        prefs.edit().putString("proxyRootValidatedFingerprint", "session-1").commit()
        assertTrue(LastGoodConfig.save(app, "mihomo", "tproxy", "a.yaml", "proxies: []\n"))
        assertNull("A normal successful start clears the record", LastGoodConfig.active(prefs, running = true))
    }

    @Test fun manualStartKeepsItsSourceAndLastGoodStartLeavesTheUserFileUntouched() {
        val configs = ProxyConfigLibrary(app)
        val entry = configs.importConfig(ProxyRuntimeProfile.Core.MIHOMO, "last-good-107.yaml",
            ByteArrayInputStream(yaml("good-node").toByteArray(Charsets.UTF_8)))
        ProxyCoreStore(app).importCore(ProxyRuntimeProfile.Core.MIHOMO, ByteArrayInputStream(ByteArray(1024) { 1 }))
        val manager = RootProxyManager(app)
        fun profile() = ProxyRuntimeProfile.load(prefs)
        fun stopped() = prefs.edit().putBoolean("proxyRootWanted", false).putBoolean("proxyRootRuntimeRunning", false).commit()

        // The unchanged entry point keeps nothing by itself.
        val plain = workers.submit<JSONObject> { manager.startManual(profile(), null, true) }.get(10, TimeUnit.SECONDS)
        assertTrue(plain.getBoolean("ok"))
        assertNull(LastGoodConfig.info(prefs, "mihomo"))

        // What ProxyComposeController.start does around the same call.
        stopped(); RootStartTransaction93BridgeShadow.nativeStarts.set(0)
        val armed = workers.submit<JSONObject> {
            manager.armLastGood()
            try { manager.startManual(profile(), null, true).also { manager.rememberLastGood(it) } } finally { manager.disarmLastGood() }
        }.get(10, TimeUnit.SECONDS)
        assertTrue(armed.getBoolean("ok"))
        val info = LastGoodConfig.info(prefs, "mihomo")
        assertNotNull(info)
        assertEquals("last-good-107.yaml", info!!.config)
        val copy = LastGoodConfig.read(app, "mihomo")!!
        assertTrue(copy.contains("good-node"))

        // The source is edited into something else; the fallback start must run the copy.
        val edited = yaml("edited-node")
        configs.write(entry, edited)
        stopped(); RootStartTransaction93BridgeShadow.nativeStarts.set(0)
        val fallback = workers.submit<JSONObject> { manager.startManualLastGood(profile(), null, copy) }.get(10, TimeUnit.SECONDS)
        assertTrue(fallback.getBoolean("ok"))
        assertTrue(fallback.optBoolean("lastGood", false))
        assertEquals(1, RootStartTransaction93BridgeShadow.nativeStarts.get())
        val startup = manager.startupConfig()
        assertTrue(startup.contains("good-node"))
        assertFalse(startup.contains("edited-node"))
        assertEquals("The user's file is never written", edited, configs.read(configs.selected(ProxyRuntimeProfile.Core.MIHOMO)))
        assertEquals("last-good-107.yaml", LastGoodConfig.active(prefs, running = true)?.config)
        assertEquals("The copy itself is not replaced by the fallback start", copy, LastGoodConfig.read(app, "mihomo"))

        // The next normal start reads the user's file again and ends the fallback indication.
        stopped(); RootStartTransaction93BridgeShadow.nativeStarts.set(0)
        workers.submit<JSONObject> {
            manager.armLastGood()
            try { manager.startManual(profile(), null, true).also { manager.rememberLastGood(it) } } finally { manager.disarmLastGood() }
        }.get(10, TimeUnit.SECONDS)
        assertTrue(manager.startupConfig().contains("edited-node"))
        assertNull(LastGoodConfig.active(prefs, running = true))
        assertTrue(LastGoodConfig.read(app, "mihomo")!!.contains("edited-node"))
    }

    @Test fun lastGoodStartWithoutACopyRefusesBeforeAnyRootCall() {
        val manager = RootProxyManager(app)
        val error = workers.submit<Throwable?> {
            runCatching { manager.startManualLastGood(ProxyRuntimeProfile.load(prefs), null, " ") }.exceptionOrNull()
        }.get(5, TimeUnit.SECONDS)
        assertTrue(error is IOException)
        assertEquals("没有上次可启动的配置", error!!.message)
        assertTrue(RootStartTransaction93BridgeShadow.commands.isEmpty())
    }

    @Test fun failureSheetOffersTheCopyOnlyWhenOneExists() {
        var lastGoodStarts = 0
        var retries = 0
        rule.setContent {
            CompositionLocalProvider(LocalHetuMotionEnabled provides false) {
                HetuHomeTheme(dark = false) {
                    androidx.compose.foundation.layout.Column {
                        HomeStartFailedSheetContent("配置加载失败", {}, {}, { retries++ })
                        HomeStartFailedSheetContent("配置加载失败", HomeLastGood("日常.yaml", 0L), {}, {}, {}, { lastGoodStarts++ })
                    }
                }
            }
        }
        rule.onAllNodesWithText("用上次可启动的版本启动").assertCountEquals(1)
        rule.onNodeWithText("上次可启动：日常.yaml").assertExists()
        rule.onNodeWithText("只用于这次启动，不会修改当前配置文件").assertExists()
        rule.onNodeWithText("用上次可启动的版本启动").performClick()
        rule.waitForIdle()
        assertEquals(1, lastGoodStarts)
        assertEquals(0, retries)
        rule.onAllNodesWithText("重新启动").assertCountEquals(2)
    }

    @Test fun homeSaysWhenTheSessionRunsFromTheCopy() {
        rule.setContent {
            CompositionLocalProvider(LocalHetuMotionEnabled provides false) {
                HetuHomeTheme(dark = false) {
                    HomeScreen(HomeUiState(status = HomeStatus.Running(120), lastGoodRunning = "日常.yaml"), HomeActions(),
                        {}, {}, {}, {}, contentPadding = PaddingValues(bottom = 16.dp), motion = false)
                }
            }
        }
        rule.onNodeWithText("正在运行上次可启动的版本（日常.yaml）· 源配置未修改").assertExists()
    }

    @Test fun homeWithoutTheCopyShowsNoFallbackNotice() {
        rule.setContent {
            CompositionLocalProvider(LocalHetuMotionEnabled provides false) {
                HetuHomeTheme(dark = false) {
                    HomeScreen(HomeUiState(status = HomeStatus.Running(120)), HomeActions(),
                        {}, {}, {}, {}, contentPadding = PaddingValues(bottom = 16.dp), motion = false)
                }
            }
        }
        rule.onAllNodesWithText("正在运行上次可启动的版本", substring = true).assertCountEquals(0)
    }

    @Test fun wordTableCoversTheNewTextAndTheStartPathKeepsItsShape() {
        for (key in listOf("用上次可启动的版本启动", "上次可启动：%s", "只用于这次启动，不会修改当前配置文件",
                "正在运行上次可启动的版本（%s）· 源配置未修改", "正在用上次可启动的版本启动…", "已用上次可启动的版本启动，源配置未修改")) {
            listOf("en", "zh-TW", "ru").forEach { lang ->
                val translated = translateHetuText(key, lang)
                assertTrue("$key has no $lang entry", translated != key)
                if (key.contains("%s")) assertTrue("$key → $lang lost %s", translated.contains("%s"))
            }
        }
        val manager = source("RootProxyManager.java")
        assertEquals(2, Regex("""overrideScripts\(profile,selected,source,""").findAll(manager).count())
        assertEquals(2, Regex("""String source=lastGood!=null\?lastGood:selected==null\?"":configs\.read\(selected\);""").findAll(manager).count())
        assertTrue(manager.contains("RootProxyPolicy policy=loadPolicy(profile);"))
        val fallback = manager.substring(manager.indexOf("JSONObject startManualLastGood("), manager.indexOf("void armLastGood()"))
        assertFalse("The fallback never writes or selects a config", Regex("""configs\.(write|select|importConfig)\(""").containsMatchIn(fallback))
        val controller = source("ProxyComposeController.kt")
        assertTrue(controller.contains("root.startManual(profile, RootProxyManager.Progress { onProgress(it) }, true).also { root.rememberLastGood(it) }"))
        assertTrue(controller.contains("root.replaceRunningManually(profile, onProgress).also { root.rememberLastGood(it) }"))
        val vm = source("app/HetuViewModel.kt")
        assertTrue("toggle() is unchanged", vm.contains("val started = controller.start { operationText = it }"))
        assertTrue(vm.contains("val started = controller.startLastGood { operationText = it }"))
    }
}
