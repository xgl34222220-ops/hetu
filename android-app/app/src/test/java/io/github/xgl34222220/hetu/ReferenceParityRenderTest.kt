package io.github.xgl34222220.hetu

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "zh-rCN-w360dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReferenceParityRenderTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val app get() = ApplicationProvider.getApplicationContext<Context>()
    private fun capture(tag: String, name: String) {
        compose.waitForIdle()
        val bounds = compose.onNodeWithTag(tag, true).fetchSemanticsNode().boundsInWindow
        val bitmap = compose.runOnIdle {
            val decor = org.robolectric.shadows.ShadowDialog.getLatestDialog()?.takeIf { it.isShowing }?.window?.decorView
                ?: compose.activity.window.decorView
            val xy = IntArray(2).also { decor.getLocationInWindow(it) }
            Bitmap.createBitmap(bounds.width.toInt(), bounds.height.toInt(), Bitmap.Config.ARGB_8888).also {
                val canvas = Canvas(it); canvas.translate(xy[0] - bounds.left, xy[1] - bounds.top); decor.draw(canvas)
            }
        }
        File("build/reports/ui-audit/$name.png").apply { parentFile?.mkdirs() }.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    @Test fun dockDragCommitsOnceAndCanReturnToInitialTab() {
        var selected by mutableIntStateOf(0); val selections = mutableListOf<Int>()
        app.getSharedPreferences("hetu", 0).edit().putBoolean("enableBlur", false).putBoolean("floatingBottomBar", false).commit()
        compose.setContent { HetuTheme {
            val haze = remember { HazeState() }
            Column(Modifier.width(360.dp).testTag("scene")) {
                HetuGlassDock(listOf(DockItem("首页", Icons.Rounded.Home), DockItem("面板", Icons.Rounded.Web), DockItem("策略", Icons.Rounded.Dns), DockItem("工具", Icons.Rounded.Build), DockItem("设置", Icons.Rounded.Settings)), selected,
                    { selected = it; selections += it }, haze, null, Modifier.testTag("dock"))
            }
        } }
        compose.onNodeWithTag("dock", true).performTouchInput { swipe(Offset(30f, 30f), Offset(width - 20f, 30f), 400) }
        compose.runOnIdle { assertEquals(4, selected); assertEquals(listOf(4), selections) }
        compose.onNodeWithTag("dock", true).performTouchInput { swipe(Offset(width - 30f, 30f), Offset(20f, 30f), 400) }
        compose.runOnIdle { assertEquals(0, selected); assertEquals(listOf(4, 0), selections) }
        // MIUIX squircle uses a hardware RuntimeShader; this test verifies real
        // pointer dispatch and callbacks, not a software bitmap of that shader.
    }
    @Test fun editorDirtyBackRequiresDiscardAndKeepsDraftAcrossComposition() {
        val original = "dns:\n  enable: true\nproxies: []\n"
        val model = RuntimeEditorModel().apply {
            content = RuntimeFileContent(original, RuntimeFilesRepository.digest(original.toByteArray()), true, "UTF-8")
            draft = original + "# unsaved\n"
        }
        var exits = 0
        compose.setContent { HetuTheme { RuntimeFileEditorScreen("/data/adb/hetu/config.yaml", model) { exits++ } } }
        compose.onNodeWithText("有未保存修改").assertExists()
        capture("runtime-editor", "reference129-editor-light")
        compose.runOnIdle { app.getSharedPreferences("hetu", 0).edit().putString("appearance", "dark").commit() }
        capture("runtime-editor", "reference129-editor-dark")
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithText("放弃未保存修改？").assertExists()
        compose.runOnIdle { assertEquals(0, exits) }
        compose.onNodeWithText("继续编辑").performClick()
        compose.runOnIdle { assertTrue(model.draft!!.contains("# unsaved")); assertEquals(0, exits) }
    }
    @Test fun healthFormFitsNarrowScreen() {
        compose.setContent { HetuTheme { Box(Modifier.fillMaxSize().testTag("health-scene")) { SubscriptionHealthSheet({}, {}) } } }
        compose.onNodeWithText("健康检查").assertExists()
        capture("health-scene", "reference129-health")
    }
}
