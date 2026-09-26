package io.github.xgl34222220.hetu

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import io.github.xgl34222220.hetu.ui.HetuTheme
import io.github.xgl34222220.hetu.ui.LocalHetuTokens
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35], qualifiers = "zh-rCN-w480dp-h1800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReferenceVideoScreenRenderTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()

    private fun capture(name:String) {
        compose.waitForIdle()
        val bounds=compose.onNodeWithTag("reference-screen",true).fetchSemanticsNode().boundsInWindow
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
        File("build/reports/ui-audit/$name.png").apply { parentFile?.mkdirs() }
            .outputStream().use { image.compress(Bitmap.CompressFormat.PNG,100,it) }
    }

    @Test fun toolsAndSettingsTopHierarchyMatch156785Reference() {
        var page by mutableStateOf("tools")
        compose.setContent {
            key(page) {
                HetuTheme {
                    Box(
                        Modifier
                            .width(360.dp)
                            .height(900.dp)
                            .background(LocalHetuTokens.current.pageBackground)
                            .testTag("reference-screen"),
                    ) {
                        if(page=="tools") {
                            RefTools(ProxyComposeState(core="Mihomo")) {}
                        } else {
                            RefSettings(
                                ProxyComposeState(
                                    running=false,
                                    core="Mihomo",
                                    mode="TPROXY",
                                    ipv6="disable",
                                    config="自用_tproxy.yaml",
                                ),
                                operation="",
                                onApplySettings={},
                                onChanged={},
                            )
                        }
                    }
                }
            }
        }

        compose.onNodeWithText("工具",true).assertExists()
        compose.onNodeWithText("应用管理",true).assertExists()
        compose.onNodeWithText("网络匹配",true).assertExists()
        compose.onNodeWithText("共享网络",true).assertExists()
        compose.onNodeWithText("绕过规则",true).assertExists()
        capture("reference156785-tools-top")

        compose.runOnIdle { page="settings" }
        compose.waitForIdle()
        compose.onNodeWithText("设置",substring=false).assertExists()
        compose.onNodeWithText("基础代理配置",true).assertExists()
        compose.onNodeWithText("其他代理配置",true).assertExists()
        compose.onNodeWithText("语言",true).assertExists()
        compose.onNodeWithText("主题设置",true).assertExists()
        compose.onNodeWithText("开机自启",true).assertExists()
        capture("reference156785-settings-top")
    }
}
