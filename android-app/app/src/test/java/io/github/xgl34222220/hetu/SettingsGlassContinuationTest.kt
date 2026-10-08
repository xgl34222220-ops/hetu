package io.github.xgl34222220.hetu

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.xgl34222220.hetu.home.*
import io.github.xgl34222220.hetu.ui.LocalHetuMotionEnabled
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Settings' real shared rows and card, rendered on the existing diffuse canvas. No Root work. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "zh-rCN-w393dp-h852dp-mdpi", application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsGlassContinuationTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private fun settle() {
        rule.mainClock.advanceTimeBy(1000)
        rule.waitForIdle()
    }

    private fun capture(name: String): Bitmap {
        rule.waitForIdle()
        lateinit var bitmap: Bitmap
        rule.runOnIdle {
            val view = rule.activity.window.decorView
            bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            val path = File("build/outputs/glass91/$name.png")
            path.parentFile!!.mkdirs()
            path.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        return bitmap
    }

    private fun readableMaterial(appearance: String) {
        var openCalls = 0
        rule.setContent {
            CompositionLocalProvider(LocalHetuMotionEnabled provides false) {
                HetuAppTheme(appearance, false) {
                    Box(Modifier.fillMaxSize().homeDiffuseCanvas().padding(14.dp)) {
                        Box(Modifier.fillMaxWidth().testTag("settings-card")) {
                            SettingsGroup(title = "界面与交互") {
                                SettingsNavRow("主题设置", subtitle = "主题、玻璃与动画", icon = HxIcons.Palette) { openCalls++ }
                                SettingsDivider()
                                SettingsSwitchRow("预测性返回动画", false, {}, subtitle = "页面跟随返回手势", icon = HomeIcons.ChevronRight)
                            }
                        }
                    }
                }
            }
        }
        settle()
        // Bitmap capture paints decorView, so sample window coordinates rather than the
        // Compose root's coordinates (which can start below a system bar).
        val bounds = rule.onNodeWithTag("settings-card", useUnmergedTree = true).fetchSemanticsNode().boundsInWindow
        val caption = rule.onNodeWithText("主题、玻璃与动画", useUnmergedTree = true).fetchSemanticsNode().boundsInWindow
        val bitmap = capture("$appearance-settings-group")
        try {
            // The left gutter is outside all text, glyphs and inset dividers. The old opaque
            // settings card paints these two interior points identically in each appearance.
            val x = (bounds.left + 8f).toInt().coerceIn(0, bitmap.width - 1)
            val topY = (bounds.top + bounds.height * .25f).toInt().coerceIn(0, bitmap.height - 1)
            val bottomY = (bounds.top + bounds.height * .75f).toInt().coerceIn(0, bitmap.height - 1)
            val top = bitmap.getPixel(x, topY)
            val bottom = bitmap.getPixel(x, bottomY)
            assertNotEquals("Settings must carry the existing glass gradient", top, bottom)
            val captionY = caption.center.y.toInt().coerceIn(0, bitmap.height - 1)
            val background = Color(bitmap.getPixel(x, captionY)).luminance()
            val dark = appearance == "dark"
            assertTrue("Card must keep the requested appearance", if (dark) background < .5f else background > .5f)
            var strongest = if (dark) 0f else 1f
            val captionLeft = caption.left.toInt().coerceAtLeast(0)
            val captionRight = caption.right.toInt().coerceAtMost(bitmap.width)
            val captionTop = caption.top.toInt().coerceAtLeast(0)
            val captionBottom = caption.bottom.toInt().coerceAtMost(bitmap.height)
            assertTrue("Caption ink region must intersect the actual window", captionLeft < captionRight && captionTop < captionBottom)
            for (y in captionTop until captionBottom) {
                for (textX in captionLeft until captionRight) {
                    val luminance = Color(bitmap.getPixel(textX, y)).luminance()
                    strongest = if (dark) maxOf(strongest, luminance) else minOf(strongest, luminance)
                }
            }
            val contrast = (maxOf(background, strongest) + .05f) / (minOf(background, strongest) + .05f)
            assertTrue("Painted subtitle must remain readable: $appearance / $contrast", contrast >= 4.5f)
        } finally { bitmap.recycle() }
        rule.onNodeWithText("主题设置").assertIsDisplayed().performClick()
        assertEquals("Material must retain the original navigation callback", 1, openCalls)
    }

    @Test fun lightSettingsCardKeepsReadableMaterialAndNavigation() = readableMaterial("light")

    @Test fun darkSettingsCardKeepsReadableMaterialAndNavigation() = readableMaterial("dark")

    @Test fun pureBlackSettingsKeepCanvasAndStaticMaterialWithMotionOff() {
        rule.setContent {
            CompositionLocalProvider(LocalHetuMotionEnabled provides false) {
                HetuAppTheme("dark", false, pureBlack = true) {
                    Box(Modifier.fillMaxSize().homeDiffuseCanvas().padding(14.dp)) {
                        SettingsGroup {
                            SettingsSwitchRow("深色纯黑背景", true, {}, subtitle = "OLED 模式使用纯黑画布", icon = HxIcons.Contrast)
                            SettingsDivider()
                            SettingsNavRow("主题设置", subtitle = "主题、玻璃与动画", icon = HxIcons.Palette) {}
                        }
                    }
                }
            }
        }
        settle()
        val first = capture("pure-black-settings-group")
        assertEquals(android.graphics.Color.BLACK, first.getPixel(4, first.height / 2))
        rule.mainClock.advanceTimeBy(5000)
        val second = capture("pure-black-settings-after-5s")
        try {
            assertTrue("Motion-off setting must not leave a moving material", first.sameAs(second))
        } finally { first.recycle(); second.recycle() }
    }

    @Test fun largeTextSettingsReachLastActionAndRetainDisabledSwitchState() {
        var changedCalls = 0
        var disabledCalls = 0
        var openCalls = 0
        var checked by mutableStateOf(false)
        val switches = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch)
        rule.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, 1.6f), LocalHetuMotionEnabled provides false) {
                HetuAppTheme("light", false) {
                    Column(
                        Modifier.width(320.dp).height(480.dp).homeDiffuseCanvas()
                            .testTag("settings-scroll").verticalScroll(rememberScrollState()).padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(HomeDims.gap),
                    ) {
                        SettingsGroup(title = "玻璃与动画") {
                            SettingsSwitchRow("模糊效果", checked, { checked = it; changedCalls++ },
                                subtitle = "控制顶栏、底栏与浮层的实时模糊", icon = HxIcons.Droplet)
                            SettingsDivider()
                            SettingsSwitchRow("底栏液态玻璃", true, { disabledCalls++ }, enabled = false,
                                subtitle = "为底栏加入通透的折射与高光", icon = HxIcons.Sparkles)
                        }
                        repeat(3) {
                            SettingsGroup {
                                SettingsRow("预测性返回动画", subtitle = "返回手势让页面跟手缩放、位移并露出上一层", icon = HomeIcons.ChevronRight)
                            }
                        }
                        SettingsGroup {
                            SettingsNavRow("备份与恢复", subtitle = "管理配置与偏好数据", icon = HxIcons.CloudUpload) { openCalls++ }
                        }
                    }
                }
            }
        }
        settle()
        rule.onNode(switches and hasText("模糊效果")).assertIsOff().performClick().assertIsOn()
        assertEquals("Whole switch row toggles once", 1, changedCalls)
        rule.onNode(switches and hasText("底栏液态玻璃")).performScrollTo().assertIsOn().assertIsNotEnabled().performClick()
        assertEquals("Disabled switch keeps its state and cannot change preferences", 0, disabledCalls)
        capture("large-text-settings-top").recycle()
        rule.onNodeWithText("备份与恢复").performScrollTo().assertIsDisplayed().performClick()
        rule.onNodeWithText("管理配置与偏好数据").assertIsDisplayed()
        assertEquals("Last action remains reachable at 320dp and 1.6x text", 1, openCalls)
        val last = rule.onNodeWithText("备份与恢复").fetchSemanticsNode().boundsInRoot
        val viewport = rule.onNodeWithTag("settings-scroll", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue("Final row must sit within the scroll viewport", last.top >= viewport.top && last.bottom <= viewport.bottom)
        capture("large-text-settings-bottom").recycle()
    }
}
