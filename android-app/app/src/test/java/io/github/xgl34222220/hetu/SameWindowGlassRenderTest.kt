package io.github.xgl34222220.hetu

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import dev.chrisbanes.haze.*
import io.github.xgl34222220.hetu.ui.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35], qualifiers = "zh-rCN-w480dp-h1200dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SameWindowGlassRenderTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private val app get()=ApplicationProvider.getApplicationContext<Context>()
    private fun raster(tag:String):Bitmap {
        compose.waitForIdle()
        val rect=compose.onNodeWithTag(tag,true).fetchSemanticsNode().boundsInWindow
        return compose.runOnIdle {
            val view=compose.activity.window.decorView
            val origin=IntArray(2).also{view.getLocationInWindow(it)}
            Bitmap.createBitmap(kotlin.math.ceil(rect.width).toInt(),kotlin.math.ceil(rect.height).toInt(),Bitmap.Config.ARGB_8888).also{
                val canvas=Canvas(it);canvas.translate(origin[0]-rect.left,origin[1]-rect.top);view.draw(canvas)
            }
        }
    }
    private fun save(tag:String,name:String) {
        val bitmap=raster(tag)
        File("build/reports/ui-audit/$name.png").apply{parentFile.mkdirs()}.outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
    }
    @Test fun actualMenuIsInActivityWindowAndSupportsOutsideBackAndSingleDispatch() {
        var clicks=0
        app.getSharedPreferences("hetu",0).edit().putString("appearance","light").commit()
        compose.setContent { HetuTheme {
            CompositionLocalProvider(LocalDensity provides Density(1f,1f),LocalHetuMotionEnabled provides false) {
                val haze=remember{HazeState()}
                Column(Modifier.fillMaxSize().hazeSource(haze).crystalPageBackground().padding(16.dp)) {
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                        Text("河图",style=MaterialTheme.typography.headlineLarge)
                        LiquidHomeMenu({clicks++},{clicks++},{clicks++},{clicks++},false,haze,true)
                    }
                    CrystalSurface(Modifier.fillMaxWidth().height(160.dp),shape=RoundedCornerShape(22.dp)) {
                        Column(Modifier.padding(16.dp)) { Text("代理运行中");Text("原配置 · 当前连接保留");LiquidHomeActions(true,false,{},{},{}) }
                    }
                    WorkspaceBento(ProxyRuntimeSnapshot(running=true,wanAddress="203.0.113.7",wanRegion="示例出口",wanState="success"),
                        20,12000,84000,164400000000,685500000000,3,136500000,6.3f,{})
                }
            }
        } }
        val rootsBefore=compose.onAllNodes(isRoot()).fetchSemanticsNodes().size
        compose.onNodeWithContentDescription("更多工具").performClick()
        compose.onNodeWithTag("crystal-menu-overlay",true).assertExists()
        compose.onNodeWithText("网络诊断").assertIsDisplayed()
        assertEquals("Menu created a second window/root", rootsBefore, compose.onAllNodes(isRoot()).fetchSemanticsNodes().size)
        save("crystal-overlay-host","glass101-open-menu-same-window")
        compose.onNodeWithContentDescription("关闭更多工具菜单").performTouchInput { click(bottomLeft + androidx.compose.ui.geometry.Offset(12f,-12f)) }
        compose.onNodeWithTag("crystal-menu-overlay",true).assertDoesNotExist()
        compose.onNodeWithContentDescription("更多工具").performClick()
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithTag("crystal-menu-overlay",true).assertDoesNotExist()
        compose.onNodeWithContentDescription("更多工具").performClick()
        compose.onNodeWithText("运行日志").performClick()
        compose.runOnIdle { assertEquals(1,clicks) }
        compose.onNodeWithTag("crystal-menu-overlay",true).assertDoesNotExist()
    }
    @Test fun placementRespectsWindowEdgesAndRtl() {
        for(direction in LayoutDirection.entries) {
            for(anchor in listOf(IntRect(4,10,52,58),IntRect(332,610,380,658))) {
                val pos=crystalPopoverPosition(anchor,IntSize(390,700),IntSize(208,210),direction,16,4)
                assertTrue(pos.x>=16 && pos.x+208<=374)
                assertTrue(pos.y>=16 && pos.y+210<=684)
            }
        }
    }
    @Test fun cachedConfiguredImagesStayDistinctAndRemovingUrlCannotShowPreviousImage() {
        val repository=ProxyGroupIconRepository.get(app)
        val urls=listOf("https://offline.invalid/glass101-green.png","https://offline.invalid/glass101-blue.png")
        for((i,url) in urls.withIndex()) {
            val file=File(app.cacheDir,"glass101-$i.png")
            val bitmap=Bitmap.createBitmap(16,16,Bitmap.Config.ARGB_8888).apply { eraseColor(if(i==0)0xff169b76.toInt() else 0xff2463df.toInt()) }
            file.outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
            runBlocking { assertTrue(repository.load(url,file.absolutePath) is GroupIconLoad.Ready) }
            file.delete()
        }
        var url by mutableStateOf(urls[0])
        compose.setContent { HetuTheme { CompositionLocalProvider(LocalHetuMotionEnabled provides false) {
            Row(Modifier.padding(16.dp).testTag("cached-brand-images")) {
                LiquidBrandTray(ProxyGroupUi("Google","Selector","",emptyList(),url))
                LiquidBrandTray(ProxyGroupUi("Microsoft","Selector","",emptyList(),urls[1]))
            }
        } } }
        compose.onNodeWithTag("configured-icon:Google",true).assertExists()
        compose.onNodeWithTag("configured-icon:Microsoft",true).assertExists()
        save("cached-brand-images","glass101-distinct-cached-images")
        compose.runOnIdle {url=""}
        compose.onNodeWithTag("configured-icon:Google",true).assertDoesNotExist()
        compose.onNodeWithTag("brand-icon:Google", true).assertExists()
        compose.onNodeWithContentDescription("Google 彩色品牌图标").assertExists()
    }
}
