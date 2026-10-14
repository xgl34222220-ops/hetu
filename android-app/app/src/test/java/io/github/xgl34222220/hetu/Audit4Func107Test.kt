package io.github.xgl34222220.hetu

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Looper
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import io.github.xgl34222220.hetu.home.HetuHomeTheme
import io.github.xgl34222220.hetu.home.HomeActions
import io.github.xgl34222220.hetu.home.HomeProbe
import io.github.xgl34222220.hetu.home.HomeProbeSource
import io.github.xgl34222220.hetu.home.HomeScreen
import io.github.xgl34222220.hetu.home.HomeStatus
import io.github.xgl34222220.hetu.home.HomeUiState
import io.github.xgl34222220.hetu.panel.PanelActions
import io.github.xgl34222220.hetu.panel.PanelConnection
import io.github.xgl34222220.hetu.panel.PanelData
import io.github.xgl34222220.hetu.panel.PanelLogic
import io.github.xgl34222220.hetu.panel.PanelOverlay
import io.github.xgl34222220.hetu.panel.PanelRoute
import io.github.xgl34222220.hetu.panel.PanelRule
import io.github.xgl34222220.hetu.panel.PanelSheetBody
import io.github.xgl34222220.hetu.panel.PanelStatus
import io.github.xgl34222220.hetu.panel.PanelTab
import io.github.xgl34222220.hetu.panel.PanelViewState
import io.github.xgl34222220.hetu.ui.LocalHetuMotionEnabled
import io.github.xgl34222220.hetu.ui.translateHetuText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 2026-10-14 audit4 functions: stopped-state 本机直测 (G5), connection-details actions (G6),
 * subscription alerts (G7), launcher shortcuts (G8), 检查更新 (G9), WeChat hint refresh (G10)
 * and 撤销上次恢复 (G12).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "zh-rCN-w393dp-h852dp-mdpi", application = Application::class)
class Audit4Func107Test {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val prefs get() = app.getSharedPreferences("hetu", Context.MODE_PRIVATE)
    private fun source(path: String) = File("src/main/java/io/github/xgl34222220/hetu/$path").readText()
    private var vm: HetuViewModel? = null
    private val servers = mutableListOf<MockWebServer>()

    @Before fun clean() {
        prefs.edit().clear().putString("appearance", "light").putBoolean("enableBlur", false).commit()
    }

    @After fun finish() {
        vm?.viewModelScope?.cancel()
        servers.forEach { runCatching { it.shutdown() } }
        HxRestoreUndo.file(app).delete()
    }

    private fun eventually(reason: String, predicate: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
        while (System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (predicate()) return
            Thread.sleep(10)
        }
        assertTrue(reason, predicate())
    }

    /* ---------------- G5 ---------------- */

    @Test fun stoppedProbeRunsADirectRequestAndKeepsTheSavedProxyResults() {
        val server = MockWebServer().apply { repeat(3) { enqueue(MockResponse().setResponseCode(204)) }; start() }
        servers += server
        val url = server.url("/generate_204").toString()
        ProxyLatencyTargets.save(prefs, listOf(ProxyLatencyTarget("A", url), ProxyLatencyTarget("B", url), ProxyLatencyTarget("C", url)))
        val model = HetuViewModel(app).also { vm = it }
        assertFalse(model.state.running)
        model.probeSites()
        eventually("direct probe finished") { !model.siteTesting && model.directSiteDelays.size == 3 }
        assertTrue(model.directSiteDelays.values.all { it > 0L })
        assertEquals(3, server.requestCount)
        assertTrue("Direct results are not saved as the proxy-path last results", ProxyLatencyTargets.lastResults(prefs).isEmpty())
        val vmSource = source("app/HetuViewModel.kt")
        assertTrue(vmSource.contains("val measured = hxDirectSiteLatencies(app)"))
        assertTrue("The runtime-bound probe stays as it was", source("ProxyDashboardRepository.kt").contains("val identity = probeIdentity(routeBound = true)"))
        assertTrue(source("app/NewUiIntegration.kt").contains("onDelay = vm::probeSites"))
    }

    private fun home(state: HomeUiState, onProbe: () -> Unit = {}) {
        rule.setContent {
            CompositionLocalProvider(LocalHetuMotionEnabled provides false) {
                HetuHomeTheme(dark = false) {
                    HomeScreen(state, HomeActions(onProbe = onProbe), {}, {}, {}, {}, contentPadding = PaddingValues(bottom = 16.dp), motion = false)
                }
            }
        }
        rule.waitForIdle()
    }

    @Test fun stoppedProbeCardShowsDirectOrLastResultsAndCanTest() {
        var probes = 0
        val state = mutableStateOf(HomeUiState(status = HomeStatus.NotRunning, probes = listOf(HomeProbe("Baidu", 18L), HomeProbe("Cloudflare", 40L), HomeProbe("Google", 60L)), probeSource = HomeProbeSource.Last))
        rule.setContent {
            CompositionLocalProvider(LocalHetuMotionEnabled provides false) {
                HetuHomeTheme(dark = false) {
                    HomeScreen(state.value, HomeActions(onProbe = { probes++ }), {}, {}, {}, {}, contentPadding = PaddingValues(bottom = 16.dp), motion = false)
                }
            }
        }
        rule.waitForIdle()
        rule.onNodeWithText("上次结果（代理未运行）").assertExists()
        rule.onNodeWithText("由河图进程直接请求，未指定代理节点；结果不代表其他应用的代理路径。").assertExists()
        rule.onNodeWithContentDescription("直连测速").performClick()
        assertEquals(1, probes)
        state.value = state.value.copy(probeSource = HomeProbeSource.Direct)
        rule.waitForIdle()
        rule.onNodeWithText("直连（代理未运行）").assertExists()
        // Nothing measured yet: dashes, and no extra line.
        state.value = HomeUiState(status = HomeStatus.NotRunning, probes = listOf(HomeProbe("Baidu"), HomeProbe("Cloudflare"), HomeProbe("Google")))
        rule.waitForIdle()
        rule.onNodeWithText("直连（代理未运行）").assertDoesNotExist()
        rule.onNodeWithText("上次结果（代理未运行）").assertDoesNotExist()
    }

    /* ---------------- G6 ---------------- */

    private val conn = PanelConnection(
        id = "c1", host = "api.example.com", network = "tcp", app = "微信", packageName = "com.tencent.mm",
        chain = listOf("节点选择", "香港 01"), rule = "DomainSuffix · example.com",
    )
    private val other = PanelConnection(id = "c2", host = "other.test", app = "浏览器", chain = listOf("DIRECT"), rule = "Match")
    private val rules = listOf(
        PanelRule("DomainSuffix", "example.com", "节点选择"),
        PanelRule("DomainSuffix", "example.org", "DIRECT"),
        PanelRule("Match", "", "节点选择"),
    )

    @Test fun connectionFiltersNarrowByAppAndMatchedRule() {
        val data = PanelData(status = PanelStatus.Running, connections = listOf(conn, other), rules = rules)
        assertEquals(listOf("c1"), PanelLogic.connections(data, PanelViewState(connApp = "微信")).map { it.id })
        assertEquals(1, PanelLogic.connectionCount(data, PanelViewState(connApp = "微信"), 1))
        assertEquals(listOf(rules[0]), PanelLogic.rules(data, PanelViewState(ruleFocus = conn.rule)))
        assertEquals(listOf(rules[2]), PanelLogic.rules(data, PanelViewState(ruleFocus = "Match")))
        assertTrue(PanelLogic.ruleMatches(PanelRule("DOMAIN-SUFFIX", "x.com", "P"), "domain-suffix · x.com"))
        assertFalse(PanelLogic.ruleMatches(PanelRule("DomainSuffix", "x.com", "P"), "DomainSuffix · y.com"))
        assertEquals(3, PanelLogic.rules(data, PanelViewState()).size)
    }

    @Test fun connectionDetailsCopyNarrowAndJumpToTheRule() {
        val data = PanelData(status = PanelStatus.Running, connections = listOf(conn, other), rules = rules)
        val copied = mutableListOf<Pair<String, String>>()
        val views = mutableListOf<PanelViewState>()
        val overlays = mutableListOf<PanelOverlay?>()
        val start = PanelViewState(tab = PanelTab.Connections)
        rule.setContent {
            CompositionLocalProvider(LocalHetuMotionEnabled provides false) {
                HetuHomeTheme(dark = false) {
                    androidx.compose.foundation.layout.Column {
                        PanelSheetBody(
                            PanelOverlay.ConnectionDetail("c1"), data, start,
                            PanelActions(onCopy = { label, text -> copied += label to text }),
                            onView = { views += it }, onOverlay = { overlays += it },
                        )
                    }
                }
            }
        }
        rule.waitForIdle()
        rule.onNodeWithText("复制主机").performClick()
        assertEquals(listOf("主机" to "api.example.com"), copied)
        rule.onNodeWithText("只看此应用").performClick()
        assertEquals("微信", views.last().connApp)
        assertEquals(PanelTab.Connections, views.last().tab)
        rule.onNodeWithText("查看匹配规则").performClick()
        assertEquals(PanelTab.Rules, views.last().tab)
        assertEquals("DomainSuffix · example.com", views.last().ruleFocus)
        assertTrue("Each action closes the sheet", overlays.size >= 2 && overlays.all { it == null })
    }

    @Test fun rulesTabShowsTheFocusChipAndClearsIt() {
        val data = PanelData(status = PanelStatus.Running, connections = listOf(conn), rules = rules)
        rule.setContent {
            CompositionLocalProvider(LocalHetuMotionEnabled provides false) {
                HetuHomeTheme(dark = false) {
                    PanelRoute(data, PanelTab.Rules, {}, PanelActions(),
                        initialView = PanelViewState(tab = PanelTab.Rules, ruleFocus = conn.rule),
                        contentPadding = PaddingValues(bottom = 16.dp))
                }
            }
        }
        rule.waitForIdle()
        rule.onNodeWithText("匹配规则：DomainSuffix · example.com").assertExists()
        rule.onNodeWithText("example.org").assertDoesNotExist()
        rule.onNodeWithText("匹配规则：DomainSuffix · example.com").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("example.org").assertExists()
    }

    /* ---------------- G7 ---------------- */

    private fun provider(name: String, used: Long, total: Long, expire: Long, info: Boolean = true) = DashboardProviderUi(
        name = name, vehicleType = "HTTP", testUrl = "", expectedStatus = "200-399", updatedAt = "",
        upload = 0L, download = used, total = total, expire = expire, nodes = emptySet(), hasSubscriptionInfo = info,
    )

    @Test fun subscriptionAlertRulesAreThreeDaysOrTenPercent() {
        val now = 1_800_000_000_000L
        val day = 86_400L
        assertTrue(HxSubscriptionAlerts.alertFor("a", 0, 0, now / 1000 + 2 * day, true, now) is HxSubscriptionAlerts.Alert.Expiring)
        assertEquals(-1L, (HxSubscriptionAlerts.alertFor("a", 0, 0, now / 1000 - 10, true, now) as HxSubscriptionAlerts.Alert.Expiring).days)
        assertNull(HxSubscriptionAlerts.alertFor("a", 100, 10, now / 1000 + 4 * day, true, now))
        assertTrue(HxSubscriptionAlerts.alertFor("a", 100, 90, 0, true, now) is HxSubscriptionAlerts.Alert.LowTraffic)
        assertNull(HxSubscriptionAlerts.alertFor("a", 100, 89, 0, true, now))
        assertNull(HxSubscriptionAlerts.alertFor("a", 100, 99, 0, false, now))
        assertNull("Unknown total and no expiry: nothing to say", HxSubscriptionAlerts.alertFor("a", 0, 0, 0, true, now))
    }

    @Test fun subscriptionAlertIsOptInOncePerDayOnTheExistingChannel() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val manager = app.getSystemService(NotificationManager::class.java)
        val shadow = shadowOf(manager)
        val now = System.currentTimeMillis()
        val low = listOf(provider("机场", used = 95, total = 100, expire = 0))
        HxSubscriptionAlerts.check(app, low, now)
        assertEquals("Off by default", 0, shadow.allNotifications.size)
        prefs.edit().putBoolean(HxSubscriptionAlerts.PREF_ENABLED, true).commit()
        HxSubscriptionAlerts.check(app, low, now)
        assertEquals(1, shadow.allNotifications.size)
        assertEquals("proxy_status", shadow.allNotifications.single().channelId)
        assertEquals(1, manager.notificationChannels.size)
        manager.cancelAll()
        HxSubscriptionAlerts.check(app, low, now)
        assertEquals("At most once per subscription per day", 0, shadow.allNotifications.size)
        HxSubscriptionAlerts.check(app, low, now + 86_400_000L)
        assertEquals(1, shadow.allNotifications.size)
        assertTrue("Backed up with the notification options", HxSubscriptionAlerts.PREF_ENABLED.startsWith("notification"))
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertEquals("No permission added", 11, Regex("<uses-permission ").findAll(manifest).count())
    }

    /* ---------------- G8 ---------------- */

    @Test fun launcherShortcutsAreDynamicAndRouteThroughTheActivity() {
        HxLauncherShortcuts.publish(app)
        val published = androidx.core.content.pm.ShortcutManagerCompat.getDynamicShortcuts(app)
        assertEquals(listOf("hetu-start", "hetu-stop", "hetu-net-test"), published.sortedBy { it.rank }.map { it.id })
        assertEquals("启动代理", published.first { it.id == "hetu-start" }.shortLabel.toString())
        val intent = HxLauncherShortcuts.intent(app, HxLauncherShortcuts.NET_TEST)
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals(HetuActivity::class.java.name, intent.component?.className)
        assertEquals(HxLauncherShortcuts.NET_TEST, intent.getStringExtra(HxLauncherShortcuts.EXTRA))
        val activity = source("app/HetuActivity.kt")
        assertTrue(activity.contains("if (savedInstanceState == null) applyShortcut(intent)"))
        assertTrue(activity.contains("intent.removeExtra(HxLauncherShortcuts.EXTRA)"))
        assertFalse("No manifest shortcut resource", File("src/main/AndroidManifest.xml").readText().contains("android.app.shortcuts"))
    }

    @Test fun shortcutStopOnAStoppedProxyOnlyExplains() = runBlocking {
        val model = HetuViewModel(app).also { vm = it }
        val seen = mutableListOf<String>()
        val collector = launch(Dispatchers.Unconfined) { model.messages.collect { seen += it } }
        try {
            model.shortcutRun(start = false)
            eventually("toast posted") { seen.contains("代理未运行") }
            assertNull("No stop was started", model.operation)
        } finally {
            collector.cancel()
        }
    }

    /* ---------------- G9 ---------------- */

    @Test fun updateCheckParsesTheLatestReleaseAndComparesVersions() {
        val release = HetuUpdateCheck.parse("""{"tag_name":"v2.0.1","name":"河图 2.0.1","html_url":"https://github.com/xgl34222220-ops/hetu/releases/tag/v2.0.1"}""")
        assertEquals("2.0.1", release.version)
        assertEquals("https://github.com/xgl34222220-ops/hetu/releases/tag/v2.0.1", release.url)
        assertEquals(HetuUpdateCheck.RELEASES_PAGE, HetuUpdateCheck.parse("""{"tag_name":"v3","html_url":"https://evil.example/x"}""").url)
        assertTrue(HetuUpdateCheck.isNewer("2.0.1", "2.0.0"))
        assertTrue(HetuUpdateCheck.isNewer("v2.1", "2.0.9"))
        assertTrue(HetuUpdateCheck.isNewer("10.0.0", "9.9.9"))
        assertFalse(HetuUpdateCheck.isNewer("2.0.0", "2.0.0"))
        assertFalse(HetuUpdateCheck.isNewer("v2.0", "2.0.0"))
        assertFalse(HetuUpdateCheck.isNewer("1.9.9", "2.0.0"))
        assertFalse(HetuUpdateCheck.isNewer("nightly", "2.0.0"))
        assertEquals(listOf(2, 0, 1), HetuUpdateCheck.versionParts("v2.0.1-beta+7"))
        assertEquals("https://api.github.com/repos/xgl34222220-ops/hetu/releases/latest", HetuUpdateCheck.LATEST_URL)
        val about = source("app/MiscScreens.kt")
        assertTrue("Only on tap", about.contains(") { checkUpdate() }"))
        assertFalse("No install permission", File("src/main/AndroidManifest.xml").readText().contains("REQUEST_INSTALL_PACKAGES"))
    }

    /* ---------------- G10 ---------------- */

    @Test fun wechatBatteryHintIsReadAgainOnResume() {
        val tools = source("app/ToolScreens.kt")
        assertTrue(tools.contains("LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) {\n        wechatBatteryHint = wechatBatteryHintNow()"))
        assertFalse(tools.contains("val wechatBatteryHint = remember {"))
    }

    /* ---------------- G12 ---------------- */

    private val core = ProxyRuntimeProfile.Core.MIHOMO

    private fun archive(name: String, text: String, settings: JSONObject): Uri {
        settings.put("proxySelectedConfig.${core.id}", JSONObject().put("t", "s").put("v", name))
        val data = JSONObject().put("schema", 1).put("settings", settings).put("configs", JSONArray().put(
            JSONObject().put("core", core.id).put("name", name).put("data", Base64.encodeToString(text.toByteArray(), Base64.NO_WRAP))))
        return Uri.fromFile(File(app.cacheDir, "backup-${System.nanoTime()}.json").apply { writeText(data.toString()) })
    }

    @Test fun restoreTakesASnapshotAndUndoPutsSettingsAndConfigsBack() = runBlocking {
        val lib = ProxyConfigLibrary(app)
        val original = lib.importConfig(core, "undo-${System.nanoTime()}.yaml", ByteArrayInputStream("mode: rule\n".toByteArray()))
        prefs.edit().putString("appearance", "light").putBoolean("enableBlur", false).commit()
        val before = lib.list(core).map { it.name }.toSet()
        assertNull(HxRestoreUndo.info(app))
        val uri = archive(original.name, "mode: direct\n", JSONObject()
            .put("appearance", JSONObject().put("t", "s").put("v", "dark"))
            .put("liquidGlass", JSONObject().put("t", "b").put("v", true))
            .put("proxyControllerSecret", JSONObject().put("t", "s").put("v", "not-restored")))
        HxRestoreUndo.restoreWithSnapshot(app, uri)
        assertEquals("dark", prefs.getString("appearance", ""))
        assertTrue(prefs.getBoolean("liquidGlass", false))
        val copy = lib.selected(core)
        assertTrue(copy.name != original.name && copy.name !in before)
        val info = HxRestoreUndo.info(app)
        assertNotNull(info)
        assertEquals(1, info!!.configs)
        // A later edit wins over the undo.
        prefs.edit().putBoolean("liquidGlass", false).commit()
        HxRestoreUndo.undo(app)
        assertEquals("light", prefs.getString("appearance", ""))
        assertFalse("Edited after the restore: kept", prefs.getBoolean("liquidGlass", true))
        assertFalse(prefs.contains("proxyControllerSecret"))
        assertEquals(original.name, lib.selected(core).name)
        assertEquals(before, lib.list(core).map { it.name }.toSet())
        assertEquals("mode: rule\n", lib.read(original))
        assertNull("One snapshot, used once", HxRestoreUndo.info(app))
        assertTrue(source("app/SettingsScreen.kt").contains("HxRestoreUndo.restoreWithSnapshot(context, uri)"))
    }

    /* ---------------- word table ---------------- */

    @Test fun audit4StringsTranslateToAllThreeLanguages() {
        listOf("直连（代理未运行）", "上次结果（代理未运行）", "只看此应用", "查看匹配规则", "订阅到期/流量提醒", "%s 将在 %d 天后到期",
            "启动河图代理", "检查更新", "发现新版本 %s", "打开发布页", "撤销上次恢复", "已撤销上次恢复").forEach { key ->
            listOf("en", "zh-TW", "ru").forEach { lang -> assertTrue("$key/$lang", translateHetuText(key, lang) != key) }
        }
        val table = source("ui/HetuLanguage.kt")
        assertTrue(table.contains("private val vocabularyAudit4Func = \"\"\""))
        assertTrue(table.contains("vocabularyAudit4Ui, vocabularyAudit4Func).joinToString"))
    }
}
