package io.github.xgl34222220.hetu

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import io.github.xgl34222220.hetu.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import dev.chrisbanes.haze.HazeState
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import top.yukonga.miuix.kmp.squircle.LocalSquircleEnabled

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "zh-rCN-w480dp-h1800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StrategyNodePanelRenderTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val longName = "Hong Kong 05 [两年订阅 · 高速专线 · 完整节点名称测试]"
    @Test fun productionStrategyUsesCompactGroupsAndReportsControllerFailureWithoutFalseSelection() {
        val app = ApplicationProvider.getApplicationContext<Context>()
        MockWebServer().use { server ->
            server.start()
            app.getSharedPreferences("hetu", 0).edit().clear().putString("appearance", "light")
                .putBoolean("enableBlur", false).putBoolean("liquidGlass", false)
                .putBoolean("proxyCustomApiEnabled", true).putString("proxyCustomApiHost", "127.0.0.1")
                .putInt("proxyCustomApiPort", server.port).commit()
            val repo = ProxyDashboardRepository(app)
            val nodes = listOf(ProxyNodeUi("香港 01", "VLESS", true, 69), ProxyNodeUi("日本 01", "Trojan", true, 87))
            val groups = listOf("节点选择", "自动选择", "AI 平台", "YouTube", "Google", "Telegram").map {
                ProxyGroupUi(it, if (it == "自动选择") "URLTest" else "Selector", "香港 01", nodes)
            }
            compose.setContent {
                HetuTheme { CompositionLocalProvider(LocalDensity provides Density(1f, 1f), LocalHetuMotionEnabled provides false,
                    LocalSquircleEnabled provides false) {
                    Box(Modifier.width(360.dp).height(800.dp).crystalPageBackground().testTag("panel-scene")) {
                        RefPanel(ProxyComposeState(running = true, panelReady = true, groups = groups, trafficMode = "rule"),
                            repo, remember { mutableStateMapOf() }, RefPanelTab.Groups, {}, 0,
                            remember { HazeState() }, null, false, true, {}, {}, {}, {})
                        HetuGlassDock(listOf(DockItem("首页", Icons.Rounded.Home), DockItem("面板", Icons.Rounded.Dashboard),
                            DockItem("策略", Icons.Rounded.Tune), DockItem("工具", Icons.Rounded.Apps), DockItem("设置", Icons.Rounded.Settings)),
                            2, {}, remember { HazeState() }, null, Modifier.align(Alignment.BottomCenter))
                    }
                } }
            }
            val first = compose.onNodeWithTag("strategy:节点选择", true).fetchSemanticsNode().boundsInRoot
            val second = compose.onNodeWithTag("strategy:自动选择", true).fetchSemanticsNode().boundsInRoot
            assertEquals(first.top, second.top, 1f); assertTrue(second.left > first.right)
            assertTrue("Default strategy cards must stay compact", first.height <= 150f)
            capture("test132-strategy-overview-light")
            compose.onNodeWithTag("strategy:AI 平台", true).performClick()
            compose.onNodeWithTag("strategy-node-panel", true).assertExists()
            server.enqueue(MockResponse().setResponseCode(500).setBody("switch failed"))
            compose.onNodeWithTag("panel-node:日本 01", true).performClick()
            compose.waitUntil(5000) { compose.onAllNodesWithTag("panel-error", true).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("panel-current-node", true).assertTextEquals("当前 · 香港 01")
            server.enqueue(MockResponse().setResponseCode(204))
            compose.onNodeWithTag("panel-node:日本 01", true).performClick()
            compose.waitUntil(5000) { compose.onAllNodesWithText("当前 · 日本 01", true).fetchSemanticsNodes().isNotEmpty() }
            assertEquals(2, server.requestCount)
            compose.onNodeWithContentDescription("关闭节点面板", true).performClick()
        }
    }
    @Test fun realPanelSeparatesSelectionProbeAndNestedNavigationAndExpandsLongNames() {
        var selected by mutableStateOf("日本 01")
        var pending by mutableStateOf<String?>(null)
        var width by mutableIntStateOf(360); var font by mutableFloatStateOf(1f); var dark by mutableStateOf(false)
        var selections = 0; var probes = 0; var nested = 0; var all = 0
        val nodes = listOf(ProxyNodeUi(longName, "VLESS", true), ProxyNodeUi("日本 01", "Trojan"), ProxyNodeUi("自动选择", "URLTest")) +
            (4..80).map { ProxyNodeUi("节点 $it", "VLESS") }
        val group = ProxyGroupUi("AI 平台", "Selector", selected, nodes)
        val app = ApplicationProvider.getApplicationContext<Context>()
        compose.setContent {
            key(dark) {
                app.getSharedPreferences("hetu", 0).edit().putString("appearance", if (dark) "dark" else "light").putBoolean("enableBlur", false).commit()
                HetuTheme { CompositionLocalProvider(LocalDensity provides Density(1f, font), LocalHetuMotionEnabled provides false) {
                    StrategyNodePanel(group, selected, mapOf(longName to 69L, "日本 01" to 332L), emptyMap(), pending, "",
                        setOf("自动选择"), false, onSelect = { selections++ }, onDelay = { probes++ }, onTestAll = { all++ },
                        onOpenGroup = { nested++ }, onBack = {}, onClose = {},
                        modifier = Modifier.width(width.dp).height(1050.dp).crystalPageBackground().testTag("panel-scene"))
                } }
            }
        }
        for (night in listOf(false, true)) for (w in listOf(320, 360, 412)) for (f in listOf(1f, 1.5f, 2f)) {
            compose.runOnIdle { dark = night; width = w; font = f }; compose.waitForIdle()
            compose.onNodeWithTag("panel-node:$longName", true).assertExists()
            val delay = compose.onNodeWithTag("panel-node-delay:$longName", true).fetchSemanticsNode().boundsInRoot
            val row = compose.onNodeWithTag("panel-node:$longName", true).fetchSemanticsNode().boundsInRoot
            assertTrue("Separate latency must stay inside node", delay.left >= row.left && delay.right <= row.right + 1f)
            assertTrue("Latency target must be reachable", delay.height >= 48f)
            compose.onNodeWithTag("panel-node:节点 80", true).assertDoesNotExist()
            if (w == 360 && f == 1f) capture("test132-node-panel-${if (night) "dark" else "light"}")
        }
        compose.runOnIdle { width = 360; font = 1f; dark = false }; compose.waitForIdle()
        compose.onNodeWithTag("panel-node-delay:$longName", true).performClick()
        compose.runOnIdle { assertEquals(1, probes); assertEquals(0, selections) }
        compose.onNodeWithContentDescription("查看自动选择子策略", true).performClick()
        compose.runOnIdle { assertEquals(1, nested); assertEquals(0, selections) }
        compose.onNodeWithTag("panel-node:$longName", true).performClick()
        compose.runOnIdle { assertEquals(1, selections); pending = longName }
        compose.onNodeWithTag("panel-node:日本 01", true).performClick()
        compose.runOnIdle { assertEquals(1, selections); pending = null }
        compose.onNodeWithTag("panel-node:$longName", true).performTouchInput { longClick() }
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag("panel-node-name:$longName", true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertTrue(layouts.isNotEmpty()); layouts.forEach { layout ->
            assertFalse(layout.didOverflowHeight)
            (0 until layout.lineCount).forEach { assertFalse(layout.isLineEllipsized(it)) }
        }
        compose.onNodeWithTag("panel-node-search", true).performTextInput("日本")
        compose.onNodeWithTag("panel-node:$longName", true).assertDoesNotExist()
        compose.onNodeWithTag("panel-node:日本 01", true).assertExists()
        compose.onNodeWithTag("panel-node-search", true).performTextClearance()
        compose.onNodeWithText("测速通过", useUnmergedTree = true).performClick()
        compose.onNodeWithTag("panel-node:自动选择", true).assertDoesNotExist()
        compose.onNodeWithText("全部测速", true).performClick()
        compose.runOnIdle { assertEquals(1, all) }
    }

    private fun capture(name: String) {
        val bounds = compose.onNodeWithTag("panel-scene", true).fetchSemanticsNode().boundsInWindow
        val bitmap = compose.runOnIdle {
            val decor = compose.activity.window.decorView
            val origin = IntArray(2).also { decor.getLocationInWindow(it) }
            Bitmap.createBitmap(bounds.width.toInt(), bounds.height.toInt(), Bitmap.Config.ARGB_8888).also {
                val canvas = Canvas(it); canvas.translate(origin[0] - bounds.left, origin[1] - bounds.top); decor.draw(canvas)
            }
        }
        File("build/reports/ui-audit/$name.png").apply { parentFile.mkdirs() }.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
