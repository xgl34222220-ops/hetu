package io.github.xgl34222220.hetu

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.*
import androidx.test.core.app.ApplicationProvider
import dev.chrisbanes.haze.HazeState
import io.github.xgl34222220.hetu.ui.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import top.yukonga.miuix.kmp.squircle.LocalSquircleEnabled
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35], qualifiers="zh-rCN-w480dp-h1600dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RecordingMiuixInteractionTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val app get() = ApplicationProvider.getApplicationContext<Context>()
    private fun capture(name: String) {
        compose.waitForIdle()
        val b = compose.onNodeWithTag("recording-panel", true).fetchSemanticsNode().boundsInWindow
        val bitmap = compose.runOnIdle {
            val decor = compose.activity.window.decorView
            val origin = IntArray(2).also { decor.getLocationInWindow(it) }
            Bitmap.createBitmap(b.width.toInt(), b.height.toInt(), Bitmap.Config.ARGB_8888).also {
                val canvas = Canvas(it); canvas.translate(origin[0]-b.left, origin[1]-b.top); decor.draw(canvas)
            }
        }
        File("build/reports/ui-audit/$name.png").apply { parentFile.mkdirs() }.outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test fun defaultGroupExpansionRemainsInlineAndFailedSelectionDoesNotMoveHighlight() {
        MockWebServer().use { server ->
            server.start()
            app.getSharedPreferences("hetu",0).edit().clear().putString("appearance","light")
                .putBoolean("enableBlur",false).putBoolean("liquidGlass",false)
                .putBoolean("proxyCustomApiEnabled",true).putString("proxyCustomApiHost","127.0.0.1")
                .putInt("proxyCustomApiPort",server.port).commit()
            val nodes = listOf(ProxyNodeUi("香港 01", "VLESS", true, 69), ProxyNodeUi("日本 01", "Trojan", true, 87))
            val groups = listOf("节点选择", "自动选择", "AI 平台", "YouTube", "Google", "Telegram", "GitHub", "流媒体", "国内服务", "兜底策略")
                .map { ProxyGroupUi(it, "Selector", "香港 01", nodes) }
            compose.setContent {
                RasterHetuTheme { CompositionLocalProvider(LocalDensity provides Density(1f,1f),
                    LocalHetuMotionEnabled provides false, LocalSquircleEnabled provides false) {
                    Box(Modifier.width(360.dp).height(780.dp).testTag("recording-panel")) {
                        RefPanel(ProxyComposeState(running=true,panelReady=true,groups=groups,trafficMode="rule"),
                            ProxyDashboardRepository(app), remember { mutableStateMapOf() }, RefPanelTab.Groups, {}, 0,
                            remember { HazeState() }, null, false, false, {}, {}, {}, {})
                    }
                } }
            }
            val first = compose.onNodeWithTag("strategy:节点选择",true).fetchSemanticsNode().boundsInRoot
            val second = compose.onNodeWithTag("strategy:自动选择",true).fetchSemanticsNode().boundsInRoot
            assertTrue(first.height in 74f..96f)
            assertEquals(first.top,second.top,1f)
            compose.onNodeWithText("模式").assertDoesNotExist()
            capture("test133-reference-strategy")
            compose.onNodeWithTag("strategy:节点选择",true).performClick()
            compose.onAllNodes(isDialog()).assertCountEquals(0)
            compose.onNodeWithTag("sunken-well:节点选择",true).assertExists()
            compose.onNodeWithTag("node:香港 01",true).assertIsSelected()
            capture("test133-reference-expanded")
            server.enqueue(MockResponse().setResponseCode(500).setBody("switch failed"))
            compose.onNodeWithTag("node:日本 01",true).performClick()
            compose.waitUntil(5000) { compose.onAllNodesWithText("切换失败",substring=true).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("node:香港 01",true).assertIsSelected()
            compose.onNodeWithTag("node:日本 01",true).assertIsNotSelected()
            server.enqueue(MockResponse().setResponseCode(204))
            compose.onNodeWithTag("node:日本 01",true).performClick()
            compose.waitUntil(5000) { compose.onAllNodesWithTag("strategy-selection:节点选择",true).fetchSemanticsNodes().any { it.config[androidx.compose.ui.semantics.SemanticsProperties.Text].first().text == "日本 01" } }
            compose.onNodeWithTag("node:日本 01",true).assertIsSelected()
            assertEquals(2, server.requestCount)
            compose.onNodeWithTag("strategy:节点选择",true).performClick()
            compose.onNodeWithTag("sunken-well:节点选择",true).assertDoesNotExist()
        }
    }

    @Test fun nativeCascadingMenuPersistsLayoutAndNativeSwitchDispatchesOnce() {
        val prefs = app.getSharedPreferences("hetu",0)
        prefs.edit().clear().commit()
        var checked by mutableStateOf(false)
        var changes = 0
        compose.setContent {
            RasterHetuTheme { CompositionLocalProvider(LocalSquircleEnabled provides false) {
                Column(Modifier.width(360.dp).height(780.dp).testTag("recording-panel")) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { ReferenceStrategyMenu() }
                    LiquidSwitch(checked, { checked=it; changes++ }, Modifier.testTag("native-switch"))
                }
            } }
        }
        compose.onNodeWithTag("strategy-layout-menu",true).performClick()
        compose.onNodeWithText("节点列数").performClick()
        compose.onAllNodesWithText("1 列").filter(androidx.compose.ui.test.SemanticsMatcher("Visible submenu") { it.boundsInWindow.top > 80f })[0].performClick()
        compose.waitForIdle()
        assertEquals(1,prefs.getInt("proxySelectorNodeColumns",0))
        compose.onNodeWithTag("native-switch",true).performClick()
        compose.onNodeWithTag("native-switch",true).assertIsOn()
        compose.runOnIdle { assertEquals(1,changes) }
    }
}
