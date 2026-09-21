package io.github.xgl34222220.hetu

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.*
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
import java.net.Proxy
import java.net.InetSocketAddress

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35], qualifiers="w480dp-h1600dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CrystalMaterialRenderTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private val app get()=ApplicationProvider.getApplicationContext<Context>()
    private fun raster(tag:String):Bitmap {
        compose.waitForIdle()
        val b=compose.onNodeWithTag(tag,true).fetchSemanticsNode().boundsInWindow
        return compose.runOnIdle {
            val decor=compose.activity.window.decorView
            val xy=IntArray(2).also{decor.getLocationInWindow(it)}
            Bitmap.createBitmap(kotlin.math.ceil(b.width).toInt(),kotlin.math.ceil(b.height).toInt(),Bitmap.Config.ARGB_8888).also{
                val c=Canvas(it);c.translate(xy[0]-b.left,xy[1]-b.top);decor.draw(c)
            }
        }
    }
    private fun save(tag:String,name:String) {
        val image=raster(tag)
        File("build/reports/ui-audit/$name.png").apply{parentFile?.mkdirs()}.outputStream().use{image.compress(Bitmap.CompressFormat.PNG,100,it)}
    }
    @Test fun materialsMatchCalmReferenceCardsWithoutPureWhiteSlabs() {
        var dark by mutableStateOf(false)
        compose.setContent { key(dark) {
            app.getSharedPreferences("hetu",0).edit().putString("appearance",if(dark)"dark" else "light").commit()
            HetuTheme { CompositionLocalProvider(LocalDensity provides Density(1f,1f),LocalCrystalBlurEnabled provides false) {
                Column(Modifier.width(360.dp).crystalPageBackground().padding(16.dp).testTag("crystal-showcase"),verticalArrangement=Arrangement.spacedBy(16.dp)) {
                    Text("河图",style=MaterialTheme.typography.headlineLarge)
                    CrystalSurface(modifier=Modifier.fillMaxWidth().height(100.dp).testTag("lit-card"),shape=androidx.compose.foundation.shape.RoundedCornerShape(22.dp)) {
                        Column(Modifier.padding(16.dp)) { Text("网络延迟"); Row { LatencyChip(null,false);Spacer(Modifier.width(20.dp));LatencyChip(87,false) } }
                    }
                    WorkspaceBento(ProxyRuntimeSnapshot(running=true,wanAddress="203.0.113.7",wanRegion="示例区域",wanState="success"),20,12000,84000,164400000000,685500000000,3,136500000,6.3f,{})
                    CrystalMenuContent({},{},{},{},false)
                }
            } }
        } }
        for(night in listOf(false,true)) {
            compose.runOnIdle { dark=night };compose.waitForIdle()
            val card=raster("lit-card")
            val upper=card.getPixel(card.width/2,5)
            val lower=card.getPixel(card.width/2,card.height-6)
            if(!night) {
                assertNotEquals("Reference surface must not become pure white",0xffffffff.toInt(),upper)
                assertNotEquals("Reference surface must not become pure white",0xffffffff.toInt(),lower)
            } else {
                assertNotEquals("Dark card lost depth",upper,lower)
            }
            compose.onNodeWithText("— ms",true).assertExists()
            compose.onNodeWithText("未测速",true).assertDoesNotExist()
            save("crystal-showcase","crystal-materials-${if(night)"dark" else "light"}")
        }
    }
    @Test fun numericalTypographyIsSansExceptExplicitAddressOrLatency() {
        compose.setContent { HetuTheme { Column {
            HetuNumber("164.4 GB",Modifier.testTag("sans-number"))
            HetuNumber("203.0.113.7",Modifier.testTag("mono-number"),monospaced=true)
        } } }
        for((tag,family) in listOf("sans-number" to FontFamily.SansSerif,"mono-number" to FontFamily.Monospace)) {
            val layouts=mutableListOf<TextLayoutResult>()
            compose.onNodeWithTag(tag,true).performSemanticsAction(SemanticsActions.GetTextLayoutResult){it(layouts)}
            assertEquals(family,layouts.single().layoutInput.style.fontFamily)
        }
    }
    @Test fun frostedMenuKeepsActionsEnabledStatesAndNativeDismissal() {
        var log=0;var connection=0;var diagnostic=0;var adblock=0
        compose.setContent { HetuTheme { Box(Modifier.width(360.dp)) {
            LiquidHomeMenu({log++},{connection++},{diagnostic++},{adblock++},true)
        } } }
        compose.onNodeWithContentDescription("更多工具").performClick()
        compose.onNodeWithText("网络诊断").assertIsNotEnabled()
        compose.onNodeWithText("应用连接").performClick()
        compose.onNodeWithText("运行日志").assertDoesNotExist()
        compose.runOnIdle{assertEquals(1,connection);assertEquals(0,log);assertEquals(0,diagnostic);assertEquals(0,adblock)}
    }
    @Test fun imageRequestsUseExistingCoreWithoutDirectFallbackWhenRunning() {
        val prefs=app.getSharedPreferences("hetu",0)
        val repository=ProxyGroupIconRepository.get(app)
        try {
            prefs.edit().putBoolean("proxyRootRuntimeRunning",true).putInt("proxyControllerPort",29134).commit()
            val proxy=repository.imageProxy()
            assertEquals(Proxy.Type.HTTP,proxy.type())
            val address=proxy.address() as InetSocketAddress
            assertEquals("127.0.0.1",address.hostString);assertEquals(29194,address.port)
            prefs.edit().putBoolean("proxyRootRuntimeRunning",false).commit()
            assertEquals(Proxy.NO_PROXY,repository.imageProxy())
        } finally {prefs.edit().remove("proxyRootRuntimeRunning").remove("proxyControllerPort").commit()}
    }
}
