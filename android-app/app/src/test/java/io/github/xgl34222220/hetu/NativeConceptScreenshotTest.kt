package io.github.xgl34222220.hetu

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.Gravity
import android.view.WindowManager
import android.view.inspector.WindowInspector
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasAnySibling
import androidx.compose.ui.test.hasText
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Opt-in empty/offline-state visual smoke tests. No root, start/stop or live service action. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "zh-rCN-w393dp-h852dp-mdpi", application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NativeConceptScreenshotTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private lateinit var vm: HetuViewModel

    @Before fun prepare() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        app.getSharedPreferences("hetu", 0).edit().clear()
            .putString("appearance", "light")
            .putBoolean("hetuDynamicColor", false)
            .putBoolean("enableBlur", false).commit()
        vm = HetuViewModel(app)
        // Deliberately do not invoke onForeground(): these are offline render fixtures.
    }

    private fun render(name: String, tab: HxTab) {
        vm.tab = tab
        snapshot(name) { HetuRoot(vm) }
    }
    private fun snapshot(name: String, content: @Composable () -> Unit) = snapshotAfter(name, content) {}

    private fun snapshotAfter(name: String, content: @Composable () -> Unit, afterSet: () -> Unit) {
        rule.setContent { HetuAppTheme(appearance = "light", dynamic = false) {
            CompositionLocalProvider(LocalNav provides HxNav(), LocalHxBlur provides vm.blurEnabled) {
                Box(Modifier.fillMaxSize()) { content() }
            }
        } }
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(1000)
        rule.waitForIdle()
        afterSet()
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(350)
        rule.waitForIdle()
        rule.runOnIdle {
            val decor = rule.activity.window.decorView
            assertTrue("Window must be laid out", decor.width > 0 && decor.height > 0)
            val bitmap = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val roots = WindowInspector.getGlobalWindowViews().filter { it.width > 0 && it.height > 0 && it.isShown }
            val ordered = buildList {
                if (roots.none { it === decor }) add(decor)
                addAll(roots)
            }
            val drawn = HashSet<View>()
            ordered.forEach { root ->
                if (!drawn.add(root)) return@forEach
                val location = IntArray(2)
                root.getLocationOnScreen(location)
                val params = root.layoutParams as? WindowManager.LayoutParams
                // Match the real WM placement/dimming, as the full state capture does.
                // Robolectric otherwise reports a separate dialog decor at (0, 0).
                if (root !== decor && params != null) {
                    val placed = android.graphics.Rect()
                    Gravity.apply(params.gravity, root.width, root.height,
                        android.graphics.Rect(0, 0, decor.width, decor.height), params.x, params.y, placed)
                    location[0] = placed.left
                    location[1] = placed.top
                    if ((params.flags and WindowManager.LayoutParams.FLAG_DIM_BEHIND) != 0) {
                        canvas.drawColor(android.graphics.Color.argb((params.dimAmount * 255).toInt(), 0, 0, 0))
                    }
                }
                canvas.save()
                canvas.translate(location[0].toFloat(), location[1].toFloat())
                root.draw(canvas)
                canvas.restore()
            }
            val file = File(System.getProperty("hetu.qa.output", "build/outputs/concept59"), "$name.png")
            file.parentFile!!.mkdirs()
            file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            bitmap.recycle()
            assertTrue("Screenshot must contain bytes", file.length() > 0)
        }
    }
    @Test fun homeOffline() = render("home-offline", HxTab.Home)
    @Test fun toolsOffline() = render("tools-offline", HxTab.Tools)
    @Test fun settingsOffline() = render("settings-offline", HxTab.Settings)
    @Test fun publicIpOffline() = snapshot("ip-public") { HomePublicIpScreen(vm) {} }
    @Test fun diagnosticsOffline() = snapshot("diagnostics") { DiagnosticsScreen(vm) {} }
    @Test fun sharedNetworkOffline() = snapshot("shared-network") { SharedNetworkScreen(vm) {} }
    @Test fun cnipOffline() = snapshot("cnip") { CnIpScreen(vm) {} }
    @Test fun networkMatchOffline() = snapshot("network-match") { HxNetworkMatchScreen(vm) {} }
    @Test fun coresOffline() = snapshot("cores") { CoresScreen(vm) {} }
    @Test fun configsOffline() = snapshot("configs") { ConfigsScreen(vm) }
    @Test fun themeOffline() = snapshot("theme") { HxThemeLabScreen(vm) {} }
    @Test fun notificationsOffline() = snapshot("notifications") { NotificationSettingsScreen(vm) {} }
    @Test fun aboutOffline() = snapshot("about") { AboutScreen(vm) }
    @Test fun panelOverviewOffline() = render("panel-overview-offline", HxTab.Panel)
    @Test fun panelStopped() { vm.openPanel("proxies"); render("panel-stopped", HxTab.Panel) }
    @Test fun webPanelsOffline() = snapshot("web-panels") { ProxyWebPanelsScreen {} }
    @Test fun subStoreOffline() = snapshot("sub-store") { ProxySubStoreScreen {} }
    @Test fun defaultPanelChoice() = snapshotAfter("settings-default-panel-choice", { SettingsScreen(vm, 0.dp, initialSubPage = "defaultPanel") }) {
        rule.onNodeWithText("默认面板页面").performClick()
    }
    @Test fun themeLanguageChoice() = snapshotAfter("settings-language-choice", { HxThemeLabScreen(vm) {} }) {
        rule.onNodeWithText("语言").performClick()
    }
    @Test fun themeTopBlurChoice() = snapshotAfter("settings-top-blur-choice", { HxThemeLabScreen(vm) {} }) {
        rule.onNodeWithText("顶栏模糊样式").performScrollTo().performClick()
    }
    @Test fun themeScaleChoice() = snapshotAfter("settings-scale-choice", { HxThemeLabScreen(vm) {} }) {
        rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("界面缩放"))
        rule.onNodeWithText("界面缩放").performClick()
        rule.onNodeWithText("80%").assertExists()
    }
    @Test fun notificationRefreshChoice() = snapshotAfter("settings-notification-refresh-choice", { NotificationSettingsScreen(vm) {} }) {
        rule.onNodeWithText("刷新频率").performClick()
    }
    @Test fun notificationTargetChoice() = snapshotAfter("settings-notification-target-choice", { NotificationSettingsScreen(vm) {} }) {
        rule.onNodeWithText("点击通知打开").performClick()
    }
    @Test fun notificationActionChoice() = snapshotAfter("settings-notification-action1-choice", { NotificationSettingsScreen(vm) {} }) {
        rule.onAllNodesWithText("动作")[0].performScrollTo().performClick()
    }

    @Test fun notificationAction2Choice() = snapshotAfter("settings-notification-action2-choice", { NotificationSettingsScreen(vm) {} }) {
        rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("快捷按钮 2"))
        rule.onNode(hasClickAction() and hasText("动作") and hasAnySibling(hasText("快捷按钮 2"))).performScrollTo().performClick()
    }
    @Test fun notificationAction3Choice() = snapshotAfter("settings-notification-action3-choice", { NotificationSettingsScreen(vm) {} }) {
        rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("快捷按钮 3"))
        rule.onNode(hasClickAction() and hasText("动作") and hasAnySibling(hasText("快捷按钮 3"))).performScrollTo().performClick()
    }
    @Test fun mirrorAddressDialog() = snapshotAfter("settings-mirror-dialog", { SettingsScreen(vm, 0.dp, initialSubPage = "startupDownload") }) {
        rule.onNode(hasText("加速地址") and hasClickAction()).performScrollTo().performClick()
        rule.onNode(hasText("加速下载") and !hasClickAction()).assertExists()
    }
    @Test fun mihomoLicenseSheet() = snapshotAfter("settings-license-mihomo", { AboutScreen(vm) }) {
        rule.onNode(hasText("Mihomo") and hasClickAction()).performScrollTo().performClick()
        awaitLicenseSheet()
    }
    @Test fun adguardLicenseSheet() = snapshotAfter("settings-license-adguard", { AboutScreen(vm) }) {
        rule.onNodeWithText("AdGuard DNS Filter").performScrollTo().performClick()
        awaitLicenseSheet()
    }
    @Test fun lucideLicenseSheet() = snapshotAfter("settings-license-lucide", { AboutScreen(vm) }) {
        rule.onNodeWithText("Lucide Icons").performScrollTo().performClick()
        awaitLicenseSheet()
    }

    private fun awaitLicenseSheet() {
        rule.waitUntil(10_000) {
            rule.onAllNodesWithText("复制").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText("复制").assertExists()
    }

}
