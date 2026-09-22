package io.github.xgl34222220.hetu

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import dev.chrisbanes.haze.HazeState
import io.github.xgl34222220.hetu.ui.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35], qualifiers="w480dp-h900dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReferenceDockRenderTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()

    @Test fun floatingDockMatches156785Geometry() {
        compose.setContent {
            HetuTheme {
                val haze=remember { HazeState() }
                Box(
                    Modifier
                        .width(360.dp)
                        .height(120.dp)
                        .background(LocalHetuTokens.current.pageBackground)
                        .testTag("reference-dock-scene"),
                ) {
                    HetuGlassDock(
                        items=listOf(
                            DockItem("首页",Icons.Rounded.Home),
                            DockItem("面板",Icons.Rounded.Link),
                            DockItem("工具",Icons.Rounded.GridView),
                            DockItem("设置",Icons.Rounded.Settings),
                        ),
                        selected=0,
                        onSelect={},
                        hazeState=haze,
                        backdrop=null,
                        modifier=Modifier.align(Alignment.BottomCenter),
                    )
                }
            }
        }
        compose.onNodeWithContentDescription("首页").assertExists()
        compose.onNodeWithContentDescription("面板").assertExists()
        compose.onNodeWithContentDescription("工具").assertExists()
        compose.onNodeWithContentDescription("设置").assertExists()

        val bounds=compose.onNodeWithTag("reference-dock-scene",true).fetchSemanticsNode().boundsInWindow
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
        File("build/reports/ui-audit/reference156785-dock.png").apply { parentFile?.mkdirs() }
            .outputStream().use { image.compress(Bitmap.CompressFormat.PNG,100,it) }
    }
}
