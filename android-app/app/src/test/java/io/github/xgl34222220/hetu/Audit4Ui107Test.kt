package io.github.xgl34222220.hetu

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import io.github.xgl34222220.hetu.home.HomeActions
import io.github.xgl34222220.hetu.home.HomeConnectionObservation
import io.github.xgl34222220.hetu.home.HomeControllerFix
import io.github.xgl34222220.hetu.home.HomeProbe
import io.github.xgl34222220.hetu.home.HomeRoute
import io.github.xgl34222220.hetu.home.HomeScreen
import io.github.xgl34222220.hetu.home.HomeStatus
import io.github.xgl34222220.hetu.home.HomeUiState
import io.github.xgl34222220.hetu.home.homeControllerFix
import io.github.xgl34222220.hetu.ui.LocalHetuMotionEnabled
import io.github.xgl34222220.hetu.ui.translateHetuText
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 2026-10-14 audit4 UI items (work/audit3-2026-10-13.md U3, U4, U5, U6, U10, U11, U12, U13): controller
 * banner fix button, start failure kept on 首页, stopped-state placeholders, switched-off settings,
 * Root 守护 row, subscription percentage meaning, bundled core version on 关于, short theme notes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "zh-rCN-w393dp-h852dp-mdpi", application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class Audit4Ui107Test {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private lateinit var app: Application

    @Before fun prepare() {
        app = ApplicationProvider.getApplicationContext()
        app.getSharedPreferences("hetu", 0).edit().clear().putString("appearance", "light")
            .putBoolean("enableBlur", false).putBoolean("enableAnimations", false).commit()
    }

    private fun source(path: String) = File("src/main/java/io/github/xgl34222220/hetu/$path").readText()

    private fun settle() {
        rule.mainClock.advanceTimeBy(1000)
        rule.waitForIdle()
    }

    private fun home(state: HomeUiState, actions: HomeActions, onShowStartFailure: () -> Unit = {}) {
        rule.setContent {
            CompositionLocalProvider(LocalHetuMotionEnabled provides false) {
                HetuAppTheme("light", false) {
                    HomeScreen(state, actions, {}, {}, {}, {}, motion = false, onShowStartFailure = onShowStartFailure)
                }
            }
        }
        settle()
    }

    /* U3 */
    @Test fun controllerFixFollowsTheComposedErrorWording() {
        assertEquals(HomeControllerFix.ApiSettings, homeControllerFix("自定义控制接口 认证失败（HTTP 401），请检查 API 地址和 Secret 后刷新"))
        assertEquals(HomeControllerFix.ApiSettings, homeControllerFix("自定义 Mihomo 控制接口鉴权失败（401），请核对所填 API 地址、端口与 Secret"))
        assertEquals(HomeControllerFix.ApiSettings, homeControllerFix("本机控制接口 拒绝访问（HTTP 403），请检查 API 设置后刷新"))
        assertEquals(HomeControllerFix.Diagnostics, homeControllerFix("本机控制接口 认证失败（HTTP 401），应用密钥与运行核心不一致；请在工具→诊断工具核对运行状态"))
        assertEquals(HomeControllerFix.Retry, homeControllerFix("本机控制接口 读取失败，请检查 API 设置后刷新"))
        assertEquals(HomeControllerFix.Retry, homeControllerFix("本机控制接口 读取失败（HTTP 503），请稍后刷新"))
        assertEquals(HomeControllerFix.Retry, homeControllerFix(""))
    }

    @Test fun controllerBannerButtonOpensTheMatchingFix() {
        var keys = 0; var diagnostics = 0; var retries = 0; var nodes = 0
        val actions = HomeActions(onOpenNode = { nodes++ }, onOpenControllerKey = { keys++ },
            onOpenDiagnostics = { diagnostics++ }, onRetryController = { retries++ })
        val error = androidx.compose.runtime.mutableStateOf("自定义控制接口 认证失败（HTTP 401），请检查 API 地址和 Secret 后刷新")
        rule.setContent {
            CompositionLocalProvider(LocalHetuMotionEnabled provides false) {
                HetuAppTheme("light", false) {
                    HomeRoute(HomeUiState(status = HomeStatus.Running(120), connection = HomeConnectionObservation(
                        controllerReadFailed = true, controllerError = error.value, takeoverHealthy = true)), actions, motion = false)
                }
            }
        }
        settle()
        rule.onNodeWithText("设置密钥").assertIsDisplayed().performClick()
        rule.runOnIdle { error.value = "本机控制接口 认证失败（HTTP 401），应用密钥与运行核心不一致；请在工具→诊断工具核对运行状态" }
        settle()
        rule.onNodeWithText("诊断工具").assertIsDisplayed().performClick()
        rule.runOnIdle { error.value = "本机控制接口 读取失败（HTTP 503），请稍后刷新" }
        settle()
        rule.onNodeWithText("重试").assertIsDisplayed().performClick()
        assertEquals(listOf(1, 1, 1, 0), listOf(keys, diagnostics, retries, nodes))
        rule.onNodeWithText("面板").assertDoesNotExist()
    }

    @Test fun controllerKeyRequestOpensThePanelApiSheet() {
        val vm = HetuViewModel(app)
        vm.openPanelApi()
        assertEquals(HxTab.Panel, vm.tab)
        assertEquals("proxies", vm.panelSection)
        assertTrue(vm.panelApiRequest)
        assertTrue(source("app/NewUiIntegration.kt").contains("onOpenControllerKey = vm::openPanelApi"))
        assertTrue(source("panel/PanelAdapter.kt").contains("apiRequest = vm.panelApiRequest, onApiRequestConsumed = { vm.panelApiRequest = false }"))
        assertTrue(source("panel/PanelRoute.kt").contains("if (running) overlay = PanelOverlay.ApiReadErrorSheet"))
    }

    /* U4 */
    @Test fun startFailureStaysOnHomeUntilDismissedOrRetried() {
        var shown = 0; var dismissed = 0
        home(HomeUiState(status = HomeStatus.StartFailed("配置加载失败")), HomeActions(onDismissStartFailure = { dismissed++ }), onShowStartFailure = { shown++ })
        rule.onNodeWithText("上次启动失败").assertIsDisplayed()
        rule.onNodeWithText("尚未启动代理").assertDoesNotExist()
        rule.onNodeWithTag("home-start-failed-notice").assertIsDisplayed()
        rule.onNodeWithText("修改配置后可重新启动").assertIsDisplayed()
        rule.onNodeWithText("查看原因").performClick()
        rule.onNodeWithContentDescription("忽略启动失败").performClick()
        assertEquals(1, shown)
        assertEquals(1, dismissed)
        val route = source("home/HomeRoute.kt")
        assertTrue(route.contains("HomeModalSheet(onDismiss = { failureSheetHidden = true })"))
        assertTrue(route.contains("onShowStartFailure = { failureSheetHidden = false }"))
        assertTrue(route.contains("rememberSaveable(failure?.detail)"))
    }

    @Test fun plainStoppedHomeHasNoFailureNotice() {
        home(HomeUiState(status = HomeStatus.NotRunning), HomeActions())
        rule.onNodeWithText("尚未启动代理").assertIsDisplayed()
        rule.onNodeWithTag("home-start-failed-notice").assertDoesNotExist()
    }

    /* U5 */
    @Test fun stoppedHomeShowsPlaceholdersAndTheLastProbeResults() {
        home(HomeUiState(status = HomeStatus.NotRunning, probes = listOf(HomeProbe("Baidu", 23L), HomeProbe("Google"))), HomeActions())
        rule.onNodeWithText("Baidu · 上次").assertExists()
        rule.onNodeWithText("Google").assertExists()
        rule.onNodeWithText("代理未运行，启动后查询公网 IP").performScrollTo().assertExists()
        rule.onNodeWithText("等待连接").assertDoesNotExist()
        rule.onNodeWithText("上行").assertDoesNotExist()
        val screen = source("home/HomeScreen.kt")
        assertTrue(screen.contains("MetricEmpty(\"代理未运行\", MetricEmptyShort)"))
        assertTrue(screen.contains("private val MetricEmptyShort = 60.dp"))
    }

    /* U6 */
    @Test fun switchedOffSettingsReadAsOff() {
        assertTrue(source("ProxyAdvancedSettingsActivity.kt").contains(".alpha(if (enabled) 1f else .38f)"))
        val notifications = source("app/ToolScreens.kt")
        assertTrue(notifications.contains("val offDim = Modifier.alpha(if (enabled) 1f else .5f)"))
        assertTrue(notifications.contains("已关闭：下面的设置仍可修改，开启后生效"))
        assertTrue(source("app/SettingsScreen.kt").contains("ht(if (blur) \"为底栏加入通透的折射与高光\" else \"需先开启模糊效果\")"))
    }

    /* U10 */
    @Test fun rootDaemonRowUsesPillsAndTranslatedLines() {
        val match = source("ProxyNetworkAutomationActivity.kt")
        listOf("执行中", "待命").forEach { assertTrue(match.contains("HomePill(ht(\"$it\")")) }
        listOf("代理运行时由 Root 守护执行匹配，App 被杀也不中断", "正在执行网络匹配", "运行中（未启用网络匹配）", "未运行：由 App 前台服务执行匹配")
            .forEach { assertTrue(it, match.contains("ht(\"$it\")")) }
        assertFalse(match.contains("HomeStatusDot("))
    }

    /* U11 */
    @Test fun subscriptionPercentageSaysItIsTheRemainingShare() {
        val lists = source("panel/PanelListTabs.kt")
        assertTrue(lists.contains("HomePill(ht(\"剩余 %d%%\").fill(it)"))
        assertTrue(lists.contains("if (it <= 10) HomeTone.Bad else HomeTone.Accent"))
        assertFalse(lists.contains("HomePill(\"\$it%\""))
    }

    /* U12 */
    @Test fun aboutShowsTheBundledCoreVersionBeforeItsRevision() {
        val prefs = app.getSharedPreferences("hetu", 0)
        assertEquals("v1.19.31", HxBundledCoreVersion.display("v1.19.31 Meta"))
        assertEquals("", HxBundledCoreVersion.display(" "))
        val idle = HxBundledCoreInfo(revision = "ab405bad5bee", inUse = true)
        assertEquals("v1.19.31", HxBundledCoreVersion.versionFor(idle, "v1.19.31 Meta"))
        HxBundledCoreVersion.remember(prefs, idle, "v1.19.31 Meta")
        val again = HxBundledCoreVersion.read(app, prefs)
        if (again.revision == idle.revision) assertEquals("v1.19.31", again.savedVersion)
        assertEquals("v1.19.31", HxBundledCoreVersion.versionFor(idle.copy(savedVersion = "v1.19.31"), ""))
        // A downloaded core's version is never remembered as the bundled one.
        val downloaded = HxBundledCoreInfo(revision = "ffff", inUse = false)
        HxBundledCoreVersion.remember(prefs, downloaded, "v9.9.9")
        assertEquals("", prefs.getString("aboutBundledCoreVersion.ffff", ""))
        assertEquals("", HxBundledCoreVersion.versionFor(downloaded, "v9.9.9"))
        val about = source("app/MiscScreens.kt")
        assertTrue(about.contains("ht(\"修订 %s\").fill(revision.take(7))"))
        assertTrue(about.contains("coreVersion.ifBlank { ht(\"启动后显示版本\") }"))
        assertFalse(about.contains("revision.take(12)"))
    }

    /* U13 */
    @Test fun themeTilesUseOneShortLine() {
        val settings = source("app/SettingsScreen.kt")
        listOf("随系统切换", "始终浅色", "始终深色").forEach { assertTrue(settings.contains("ht(\"$it\")")) }
        listOf("与系统设置保持一致", "始终使用浅色主题", "始终使用深色主题").forEach { assertFalse(settings.contains(it)) }
        assertTrue(settings.contains("maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center"))
    }

    @Test fun everyNewWordIsTranslated() {
        val words = listOf(
            "设置密钥", "可改用上次可启动的版本启动", "修改配置后可重新启动", "查看原因", "忽略启动失败", "上次启动失败",
            "代理未运行，启动后查询公网 IP", "%s · 上次", "已关闭：下面的设置仍可修改，开启后生效", "需先开启模糊效果",
            "随系统切换", "始终浅色", "始终深色", "代理运行时由 Root 守护执行匹配，App 被杀也不中断", "正在执行网络匹配",
            "运行中（未启用网络匹配）", "未运行：由 App 前台服务执行匹配", "执行中", "待命", "修订 %s", "启动后显示版本",
        )
        for (word in words) {
            assertNotEquals(word, word, translateHetuText(word, "en"))
            assertNotEquals(word, word, translateHetuText(word, "ru"))
        }
        assertEquals("Set secret", translateHetuText("设置密钥", "en"))
        assertEquals("上次啟動失敗", translateHetuText("上次启动失败", "zh-TW"))
        val table = source("ui/HetuLanguage.kt")
        assertTrue(table.contains("vocabularyLastGood, vocabularyAudit4Ui).joinToString"))
    }
}
