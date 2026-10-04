package io.github.xgl34222220.hetu

import android.app.Application
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Current launcher routes, safe offline storage, and dock ownership; no Root/network mutation. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "zh-rCN-w393dp-h852dp-mdpi", application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NewUiShell83Test {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private lateinit var vm: HetuViewModel

    @Before fun prepare() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        app.getSharedPreferences("hetu", 0).edit().clear()
            .putString("appearance", "light").putBoolean("enableBlur", false).commit()
        vm = HetuViewModel(app)
    }

    private fun root(tab: HxTab) {
        vm.tab = tab
        rule.setContent { HetuAppTheme(appearance = "light", dynamic = false) { HetuRoot(vm) } }
        settle()
    }

    private fun settle() {
        rule.mainClock.advanceTimeBy(1000)
        rule.waitForIdle()
    }

    private fun capture(name: String) {
        val file = File("build/outputs/ui83/$name.png")
        file.parentFile.mkdirs()
        file.outputStream().use {
            rule.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test fun toolsConfigurationOwnsDockAndBackReturnsToHub() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val core = ProxyRuntimeProfile.load(vm.prefs).core
        val library = ProxyConfigLibrary(app)
        val fixture = library.importConfig(core, "ui83-navigation.yaml", "mode: rule\nproxies: []\nproxy-groups: []\nrules: []\n".byteInputStream())
        library.select(core, fixture.name)
        root(HxTab.Tools)
        capture("tools-hub")
        rule.onNodeWithText("配置管理").performScrollTo().performClick()
        settle()
        rule.onNodeWithText("首页").assertDoesNotExist()
        // The library loads on Dispatchers.IO; virtual animation time cannot complete that read.
        rule.waitUntil(10_000) {
            rule.onAllNodesWithText("编辑当前 YAML").fetchSemanticsNodes().isNotEmpty()
        }
        capture("tools-configurations")
        rule.onNodeWithText("编辑当前 YAML").performScrollTo().performClick()
        settle()
        rule.onNodeWithText("编辑配置").assertExists()
        rule.onNodeWithContentDescription("返回").performClick()
        settle()
        rule.onNodeWithText("配置与订阅").assertExists()
        rule.onNodeWithContentDescription("返回").performClick()
        settle()
        rule.onNodeWithText("首页").assertExists()
        rule.onNodeWithText("文件管理").assertExists()
    }

    @Test fun diagnosticDetailKeepsNetworkJournalAccessible() {
        root(HxTab.Tools)
        rule.onNodeWithText("诊断工具").performScrollTo().performClick()
        settle()
        rule.onNodeWithText("首页").assertDoesNotExist()
        rule.onNodeWithText("网络事件记录").assertExists()
        capture("tools-diagnostics")
    }

    @Test fun settingsHubRetainsAllConceptGroups() {
        root(HxTab.Settings)
        capture("settings-hub")
        listOf("基础代理配置", "高级代理配置", "语言与主题", "默认面板", "备份与恢复", "开机启动与下载", "通知设置", "关于").forEach {
            rule.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(it))
            rule.onNodeWithText(it).assertExists()
        }
    }

    @Test fun requestedConfigurationRouteOpensOnceAndClearsDock() {
        vm.tab = HxTab.Tools
        var consumed = false
        rule.setContent {
            HetuAppTheme(appearance = "light", dynamic = false) {
                HetuRoot(vm, HxRoute.Configs) { consumed = true }
            }
        }
        settle()
        org.junit.Assert.assertTrue(consumed)
        rule.onNodeWithText("配置管理").assertExists()
        rule.onNodeWithText("首页").assertDoesNotExist()
        rule.onNodeWithContentDescription("返回").performClick()
        settle()
        rule.onNodeWithText("首页").assertExists()
    }
}
