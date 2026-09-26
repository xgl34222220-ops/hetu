package io.github.xgl34222220.hetu

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.layout.onSizeChanged
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
import dev.chrisbanes.haze.HazeState
import io.github.xgl34222220.hetu.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import top.yukonga.miuix.kmp.squircle.LocalSquircleEnabled
import java.io.File

/** Full production home with inert sample-data callbacks. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35], qualifiers = "zh-rCN-w480dp-h2400dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HomeScreenRenderTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Test fun homeKeepsActionsAndLastMetricReachableWithoutImplicitRestart() {
        var night by mutableStateOf(false)
        var font by mutableFloatStateOf(1f)
        var width by mutableIntStateOf(360)
        var dockHeight by mutableStateOf(108.dp)
        var restarts=0;var toggles=0;var reloads=0;var diagnostics=0;var logs=0
        val app=ApplicationProvider.getApplicationContext<Context>()
        compose.setContent {
            key(night) {
                app.getSharedPreferences("hetu",0).edit().putString("appearance",if(night) "dark" else "light").putBoolean("enableBlur", false).putBoolean("liquidGlass", false).commit()
                HetuTheme {
                    // Software capture keeps the actual dock layout; GPU squircle/glass is device-only.
                    CompositionLocalProvider(LocalDensity provides Density(1f,font),LocalHetuMotionEnabled provides false,LocalHetuDockHeight provides dockHeight,LocalSquircleEnabled provides false) {
                        Box(Modifier.width(width.dp).height(800.dp).testTag("home-screen")) {
                            RefHome(
                                state=ProxyComposeState(running=true,panelReady=true,config="原始配置 · 不修改内容",mode="TPROXY"),
                                runtime=ProxyRuntimeSnapshot(running=true,elapsedSeconds=30,rssBytes=92_274_688,
                                    wanAddress="61.222.202.153",wanRegion="Taiwan",wanCountryCode="TW",wanState="success",wanCheckedAt=1789870000000),
                                providers=emptyList(),cachedSubscription=RefSubscriptionCache(177_414_635_520,735_965_020_160,3),cachedConnections=20,
                                siteDelays=mapOf("Baidu" to 44L,"Cloudflare" to 186L,"Google" to 162L),
                                upRate=1331,downRate=2150,cpuPercent=5.7f,operation="",message="",testing=false,
                                hazeState=remember { HazeState() },glassEnabled=false,onRefresh={},onToggle={toggles++},onReload={reloads++},
                                onRestart={restarts++},onDelay={},onLog={logs++},onSubscription={},diagnosticLoading=false,onConnections={},onSettings={},onDiagnostics={diagnostics++})
                            HetuGlassDock(
                                listOf(DockItem("首页", Icons.Rounded.Home), DockItem("面板", Icons.Rounded.Dashboard),
                                    DockItem("策略", Icons.Rounded.Tune), DockItem("工具", Icons.Rounded.Apps), DockItem("设置", Icons.Rounded.Settings)),
                                0, {}, remember { HazeState() }, null,
                                Modifier.align(Alignment.BottomCenter).testTag("home-real-dock").onSizeChanged { dockHeight = it.height.dp })
                        }
                    }
                }
            }
        }
        for(w in listOf(320,360,412)) for(scale in listOf(1f,1.5f,2f)) for(dark in listOf(false,true)) {
            compose.runOnIdle { width=w;font=scale;night=dark }
            compose.waitForIdle()
            compose.onNodeWithTag("home-run-state", true).assertTextEquals("运行中")
            compose.onNodeWithTag("home-liquid-actions", true).assertExists()
            compose.onNodeWithText("停止",true).assertExists()
            compose.onNodeWithText("44 ms", true).assertExists()
            for (value in listOf("44 ms", "186 ms", "162 ms", "WebUI", "Web 界面", "日志", "长按诊断")) {
                val layouts = mutableListOf<TextLayoutResult>()
                compose.onNodeWithText(value, true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
                assertTrue("Missing measured latency text: $value", layouts.isNotEmpty())
                layouts.forEach { layout ->
                    assertFalse("Latency text clipped vertically at width=$w font=$scale: $value (${layout.size})", layout.didOverflowHeight)
                    assertTrue("Latency line cut off: $value", layout.getLineBottom(0) <= layout.size.height + .5f)
                }
            }
            val heroBounds = compose.onNodeWithTag("home-hero", true).fetchSemanticsNode().boundsInRoot
            val actionBounds = compose.onNodeWithTag("home-liquid-actions", true).fetchSemanticsNode().boundsInRoot
            compose.runOnIdle {
                assertTrue("Status content must stay above actions", heroBounds.bottom <= actionBounds.top)
                assertTrue("Status card must fit the viewport width", heroBounds.width <= w)
                assertTrue("Liquid action rail lost its 48dp touch height: $actionBounds", actionBounds.height >= 48f)
            }
            compose.onNodeWithText("网络与广告过滤",true).assertDoesNotExist()
            compose.onNodeWithText("应用连接",true).assertDoesNotExist()
            compose.onNodeWithText("WebUI",true).assertExists()
            compose.onNodeWithText("日志",true).assertExists()
            compose.onNodeWithContentDescription("更多工具").assertDoesNotExist()
            compose.onNodeWithText("↑ 实时上行", true).assertDoesNotExist()
            compose.onNodeWithText("↓ 实时下行", true).assertDoesNotExist()
            compose.runOnIdle { assertEquals(0,restarts);assertEquals(0,toggles);assertEquals(0,reloads) }
            capture("home-${w}-${scale}-${if(dark) "dark" else "light"}-top")
            compose.onNode(hasScrollToIndexAction()).performScrollToIndex(4)
            compose.onNodeWithTag("instrument-usage",true).assertExists()
            compose.onNodeWithText("CPU",true).performScrollTo().assertIsDisplayed()
            val cpu = compose.onNodeWithText("CPU", true).fetchSemanticsNode().boundsInRoot
            val dock = compose.onNodeWithTag("home-real-dock", true).fetchSemanticsNode().boundsInRoot
            assertTrue("Last metric must scroll above actual dock", cpu.bottom < dock.top)
            for (label in listOf("首页", "面板", "策略", "工具", "设置")) {
                val layouts = mutableListOf<TextLayoutResult>()
                compose.onNodeWithText(label, true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
                assertTrue("Dock label has no text layout: $label", layouts.isNotEmpty())
                assertTrue("Dock label clips at width=$w font=$scale: $label: ${layouts.map { "${it.size} / ${it.multiParagraph.width} x ${it.multiParagraph.height}, width=${it.didOverflowWidth} height=${it.didOverflowHeight}" }}", layouts.none { it.didOverflowHeight || it.didOverflowWidth })
            }
            capture("home-${w}-${scale}-${if(dark) "dark" else "light"}-bottom")
            compose.onNode(hasScrollToIndexAction()).performScrollToIndex(0)
        }
        compose.onNodeWithText("重启",true).performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1,restarts);assertEquals(0,toggles);assertEquals(0,reloads) }
        compose.onNodeWithText("日志").performScrollTo().performTouchInput { longClick() }
        compose.runOnIdle { assertEquals(1,diagnostics);assertEquals(0,logs);assertEquals(1,restarts) }
    }
    private fun capture(name:String) {
        compose.waitForIdle()
        val bounds=compose.onNodeWithTag("home-screen",true).fetchSemanticsNode().boundsInWindow
        val image=compose.runOnIdle {
            val decor=compose.activity.window.decorView
            val location=IntArray(2).also { decor.getLocationInWindow(it) }
            Bitmap.createBitmap(kotlin.math.ceil(bounds.width).toInt(),kotlin.math.ceil(bounds.height).toInt(),Bitmap.Config.ARGB_8888).also {
                val canvas=Canvas(it);canvas.translate(location[0]-bounds.left,location[1]-bounds.top);decor.draw(canvas)
            }
        }
        val target=File("build/reports/ui-audit/$name.png").apply{parentFile?.mkdirs()}
        target.outputStream().use{image.compress(Bitmap.CompressFormat.PNG,100,it)}
    }
}
