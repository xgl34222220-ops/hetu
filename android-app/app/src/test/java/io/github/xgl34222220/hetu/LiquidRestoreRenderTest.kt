package io.github.xgl34222220.hetu

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import io.github.xgl34222220.hetu.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35], qualifiers="w480dp-h1600dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LiquidRestoreRenderTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private fun raster(tag: String): Bitmap {
        compose.waitForIdle()
        val b=compose.onNodeWithTag(tag,true).fetchSemanticsNode().boundsInWindow
        return compose.runOnIdle {
            val decor=compose.activity.window.decorView
            val xy=IntArray(2).also { decor.getLocationInWindow(it) }
            Bitmap.createBitmap(kotlin.math.ceil(b.width).toInt(),kotlin.math.ceil(b.height).toInt(),Bitmap.Config.ARGB_8888).also {
                val canvas=Canvas(it);canvas.translate(xy[0]-b.left,xy[1]-b.top);decor.draw(canvas)
            }
        }
    }
    private fun save(tag: String,name: String) {
        val image=raster(tag)
        val file=File("build/reports/ui-audit/$name.png").apply { parentFile.mkdirs() }
        file.outputStream().use { image.compress(Bitmap.CompressFormat.PNG,100,it) }
    }
    @Test fun compactWellKeepsTwoColumnsAndSelectionDoesNotShiftLabels() {
        val nodes=listOf(ProxyNodeUi("美国节点", "URLTest"),ProxyNodeUi("日本节点", "URLTest"),
            ProxyNodeUi("香港节点", "URLTest"),ProxyNodeUi("台湾节点", "URLTest"))
        val group=ProxyGroupUi("AI 平台", "Selector", "日本节点", nodes)
        var selected="日本节点";var probes=0
        compose.setContent { HetuTheme { CompositionLocalProvider(LocalDensity provides Density(1f,1f),LocalHetuMotionEnabled provides false) {
            Column(Modifier.width(360.dp).background(LocalHetuTokens.current.pageBackground).padding(16.dp).testTag("liquid-grid")) {
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    StrategyGroupCard(group,selected,true,87,false,Modifier.weight(1f),{}, {probes++})
                    StrategyGroupCard(group.copy(name="节点选择"),"手动选择",false,245,false,Modifier.weight(1f),{}, {probes++})
                }
                Spacer(Modifier.height(8.dp))
                LiquidGroupWell(group,selected,mapOf("美国节点" to 245L,"日本节点" to 87L),emptyMap(),{selected=it},{probes++},{probes++})
            }
        } } }
        val us=compose.onNodeWithTag("node-label:美国节点",true).fetchSemanticsNode().boundsInRoot
        val jp=compose.onNodeWithTag("node-label:日本节点",true).fetchSemanticsNode().boundsInRoot
        assertEquals("Selected label baseline changed",us.top,jp.top,.5f)
        assertTrue("Nodes collapsed to single column",jp.left>us.right)
        compose.onNodeWithText("AI 平台 · 可选节点",true).assertDoesNotExist()
        compose.onNodeWithTag("brand-tray:AI 平台",true).assertWidthIsEqualTo(32.dp)
        compose.onNodeWithTag("node-delay:日本节点",true).performClick()
        compose.runOnIdle { assertEquals(1,probes);assertEquals("日本节点",selected) }
        save("liquid-grid","liquid-grid-360-light")
    }
    @Test fun selectorHasNoWhiteRectangleAndHomeDestinationsRemainAvailable() {
        var logs=0;var connections=0;var diagnostics=0;var adblock=0
        compose.setContent { HetuTheme { CompositionLocalProvider(LocalDensity provides Density(1f,1f),LocalHetuMotionEnabled provides false) {
            Column(Modifier.width(360.dp)) {
                Box(Modifier.background(Color(0xFFEEF2F6)).testTag("selector-background")) { LiquidConfigIndicator(true) }
                LiquidHomeMenu({logs++},{connections++},{diagnostics++},{adblock++},false)
            }
        } } }
        val icon=raster("selector-background")
        for ((x,y) in listOf(1 to 1, (icon.width-2) to 1, 1 to (icon.height-2), (icon.width-2) to (icon.height-2))) {
            assertEquals("Opaque selector corner",0xffeef2f6.toInt(),icon.getPixel(x,y))
        }
        compose.onNodeWithContentDescription("更多工具").performClick()
        compose.onNodeWithText("应用连接").performClick()
        compose.runOnIdle { assertEquals(1,connections);assertEquals(0,logs);assertEquals(0,diagnostics);assertEquals(0,adblock) }
        save("selector-background","selector-transparent-corners")
    }
}
