package io.github.xgl34222220.hetu

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
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
import java.io.File

/** Full production home with inert sample-data callbacks. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35], qualifiers="w480dp-h2400dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HomeScreenRenderTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Test fun homeKeepsActionsAndLastMetricReachableWithoutImplicitRestart() {
        var night by mutableStateOf(false)
        var font by mutableFloatStateOf(1f)
        var width by mutableIntStateOf(360)
        var restarts=0;var toggles=0;var reloads=0
        val app=ApplicationProvider.getApplicationContext<Context>()
        compose.setContent {
            key(night) {
                app.getSharedPreferences("hetu",0).edit().putString("appearance",if(night) "dark" else "light").commit()
                HetuTheme {
                    CompositionLocalProvider(LocalDensity provides Density(1f,font),LocalHetuMotionEnabled provides false,LocalHetuDockHeight provides 108.dp) {
                        Box(Modifier.width(width.dp).height(1100.dp).testTag("home-screen")) {
                            RefHome(
                                state=ProxyComposeState(running=true,panelReady=true,config="原始配置 · 不修改内容",mode="TPROXY"),
                                runtime=ProxyRuntimeSnapshot(running=true,elapsedSeconds=3600,rssBytes=78100000,
                                    wanAddress="203.0.113.7",wanRegion="示例出口",wanCountryCode="JP",wanState="success",wanCheckedAt=1789870000000),
                                providers=emptyList(),cachedSubscription=RefSubscriptionCache(163100000000,685500000000,3),cachedConnections=20,
                                siteDelays=mapOf("Baidu" to 96L,"Cloudflare" to 193L,"Google" to 882L),
                                upRate=123456,downRate=345678,cpuPercent=6.3f,operation="",message="",testing=false,
                                hazeState=remember { HazeState() },glassEnabled=false,onRefresh={},onToggle={toggles++},onReload={reloads++},
                                onRestart={restarts++},onDelay={},onLog={},onSubscription={},diagnosticLoading=false,onConnections={},onSettings={},onDiagnostics={})
                        }
                    }
                }
            }
        }
        for(w in listOf(360,412)) for(scale in listOf(1f,1.5f)) for(dark in listOf(false,true)) {
            compose.runOnIdle { width=w;font=scale;night=dark }
            compose.waitForIdle()
            compose.onNodeWithText("代理运行中",true).assertExists()
            compose.onNodeWithText("停止",true).assertExists()
            compose.runOnIdle { assertEquals(0,restarts);assertEquals(0,toggles);assertEquals(0,reloads) }
            capture("home-${w}-${scale}-${if(dark) "dark" else "light"}-top")
            compose.onNode(hasScrollToIndexAction()).performScrollToIndex(4)
            compose.onNodeWithText("已用流量",true).assertExists()
            compose.onNodeWithText("CPU",true).performScrollTo().assertIsDisplayed()
            capture("home-${w}-${scale}-${if(dark) "dark" else "light"}-bottom")
            compose.onNode(hasScrollToIndexAction()).performScrollToIndex(0)
        }
        compose.onNodeWithText("重启",true).performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1,restarts);assertEquals(0,toggles);assertEquals(0,reloads) }
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
