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
@Config(sdk=[35], qualifiers = "zh-rCN-w480dp-h1600dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WellHeaderRenderTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun referenceNodeGridKeepsTwoColumnsUntilAccessibilityNeedsOne() {
        var width by mutableIntStateOf(360)
        var scale by mutableFloatStateOf(1f)
        val group=ProxyGroupUi("AI 平台","Selector","日本节点",listOf(
            ProxyNodeUi("美国节点", "Vless", true),
            ProxyNodeUi("日本节点超级长名称用于自动缩放", "Trojan", false),
            ProxyNodeUi("新加坡节点", "Vless", true),
            ProxyNodeUi("香港节点", "Trojan", false),
        ))
        compose.setContent { HetuTheme {
            CompositionLocalProvider(
                LocalDensity provides Density(1f,scale),
                LocalHetuMotionEnabled provides false,
            ) {
                Column(
                    Modifier.width(width.dp).background(LocalHetuTokens.current.pageBackground)
                        .padding(16.dp).testTag("well-render-root"),
                ) {
                    LiquidGroupWell(
                        group,"日本节点",
                        mapOf("美国节点" to 245L,"日本节点超级长名称用于自动缩放" to 87L),
                        emptyMap(),{},{},{},
                    )
                }
            }
        } }

        for(w in listOf(320,360,412)) {
            compose.runOnIdle { width=w; scale=1f }
            compose.waitForIdle()
            val left=compose.onNodeWithTag("node:美国节点",true).fetchSemanticsNode().boundsInRoot
            val right=compose.onNodeWithTag("node:日本节点超级长名称用于自动缩放",true).fetchSemanticsNode().boundsInRoot
            assertEquals("Default first row must align even at 320dp",left.top,right.top,.5f)
            assertTrue("Compact node grid lost its right column",right.left>left.right)
            compose.onNodeWithText("日本节点超级长名称用于自动缩放",true).assertExists()
        }

        compose.runOnIdle { width=360; scale=1.5f }
        compose.waitForIdle()
        val first=compose.onNodeWithTag("node:美国节点",true).fetchSemanticsNode().boundsInRoot
        val second=compose.onNodeWithTag("node:日本节点超级长名称用于自动缩放",true).fetchSemanticsNode().boundsInRoot
        assertTrue("Accessibility font scale should stack node cards",second.top>first.bottom)

        compose.runOnIdle { width=360; scale=1f }
        compose.waitForIdle()
        val bounds=compose.onNodeWithTag("well-render-root",true).fetchSemanticsNode().boundsInWindow
        val image=compose.runOnIdle {
            val decor=compose.activity.window.decorView
            val xy=IntArray(2).also { decor.getLocationInWindow(it) }
            Bitmap.createBitmap(
                kotlin.math.ceil(bounds.width).toInt(),
                kotlin.math.ceil(bounds.height).toInt(),
                Bitmap.Config.ARGB_8888,
            ).also {
                val canvas=Canvas(it)
                canvas.translate(xy[0]-bounds.left,xy[1]-bounds.top)
                decor.draw(canvas)
            }
        }
        val file=File("build/reports/ui-audit/liquid-grid-156785.png").apply { parentFile?.mkdirs() }
        file.outputStream().use { image.compress(Bitmap.CompressFormat.PNG,100,it) }
    }
}
