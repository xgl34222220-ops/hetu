package io.github.xgl34222220.hetu

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
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
class WellHeaderRenderTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun compactMeasureActionNeverConsumesWeightedTitleWidth() {
        var width by mutableIntStateOf(360)
        var scale by mutableFloatStateOf(1f)
        val group=ProxyGroupUi("AI 平台", "Selector", "日本节点", listOf(
            ProxyNodeUi("美国节点", "URLTest"),ProxyNodeUi("日本节点", "URLTest")))
        compose.setContent { HetuTheme {
            CompositionLocalProvider(LocalDensity provides Density(1f,scale),LocalHetuMotionEnabled provides false) {
                Column(Modifier.width(width.dp).background(LocalHetuTokens.current.pageBackground).padding(16.dp).testTag("well-render-root")) {
                    LiquidGroupWell(group,"日本节点",mapOf("美国节点" to 245L,"日本节点" to 87L),emptyMap(),{},{},{})
                }
            }
        } }
        for(w in listOf(320,360,412)) for(f in listOf(1f,1.3f,1.5f)) {
            compose.runOnIdle { width=w; scale=f }
            compose.waitForIdle()
            val title=compose.onNodeWithTag("well-title:AI 平台",true)
            val layouts=mutableListOf<TextLayoutResult>()
            title.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertEquals("Title unexpectedly wrapped at $w/$f",1,layouts.single().lineCount)
            assertFalse("Title clipped at $w/$f",layouts.single().hasVisualOverflow)
            val titleBounds=title.fetchSemanticsNode().boundsInRoot
            val actionBounds=compose.onNodeWithTag("well-testall:AI 平台",true).fetchSemanticsNode().boundsInRoot
            assertTrue("Accessory consumed header width at $w/$f",actionBounds.width < w*.5f)
            assertTrue("Title/action overlap at $w/$f",titleBounds.right <= actionBounds.left+.5f)
            if(w==360 && f==1f) {
                val bounds=compose.onNodeWithTag("well-render-root",true).fetchSemanticsNode().boundsInWindow
                val image=compose.runOnIdle {
                    val decor=compose.activity.window.decorView
                    val xy=IntArray(2).also { decor.getLocationInWindow(it) }
                    Bitmap.createBitmap(kotlin.math.ceil(bounds.width).toInt(),kotlin.math.ceil(bounds.height).toInt(),Bitmap.Config.ARGB_8888).also {
                        val canvas=Canvas(it); canvas.translate(xy[0]-bounds.left,xy[1]-bounds.top); decor.draw(canvas)
                    }
                }
                val file=File("build/reports/ui-audit/liquid-header-360-verified.png").apply { parentFile.mkdirs() }
                file.outputStream().use { image.compress(Bitmap.CompressFormat.PNG,100,it) }
            }
        }
    }
}
