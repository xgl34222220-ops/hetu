package io.github.xgl34222220.hetu

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
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
@Config(sdk = [35], qualifiers = "w393dp-h900dp-mdpi", application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NativeDockUi4Test {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val calls = mutableListOf<Int>()
    private val all = listOf(DockItem("首页", Icons.Rounded.Home), DockItem("面板", Icons.Rounded.Link),
        DockItem("工具", Icons.Rounded.GridView), DockItem("设置", Icons.Rounded.Settings))

    private fun render(items: List<DockItem> = all, dark: Boolean = false, scale: Float = 1f) {
        ApplicationProvider.getApplicationContext<Application>().getSharedPreferences("hetu", 0).edit()
            .putBoolean("enableBlur", false).putBoolean("floatingBottomBar", true).commit()
        rule.setContent {
            var selected by remember { mutableIntStateOf(0) }
            val density = LocalDensity.current
            CompositionLocalProvider(LocalHetuMotionEnabled provides false,
                LocalDensity provides Density(density.density, scale)) {
                MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                    Box(Modifier.fillMaxSize()) {
                        HetuGlassDock(items, selected, { calls += it; selected = it }, remember { HazeState() }, null,
                            Modifier.align(Alignment.BottomCenter))
                    }
                }
            }
        }
    }

    @Test fun selectedLabelOnlyAndAllDestinationsRemainAccessible() {
        render()
        rule.onAllNodesWithTag("dock-active-label", useUnmergedTree = true).assertCountEquals(1)
        rule.onNodeWithText("首页", useUnmergedTree = true).assertIsDisplayed()
        all.forEachIndexed { index, item ->
            rule.onNodeWithContentDescription(item.label).assertHasClickAction()
            rule.onNodeWithTag("dock-tab-$index").performTouchInput { click() }
            rule.onNodeWithTag("dock-tab-$index").assertIsSelected()
            rule.onNodeWithText(item.label, useUnmergedTree = true).assertIsDisplayed()
            rule.onAllNodesWithTag("dock-active-label", useUnmergedTree = true).assertCountEquals(1)
        }
        assertEquals(listOf(1, 2, 3), calls)
    }

    @Test fun dockTouchTargetsNeverOverlapOrCollapse() {
        render()
        val bounds = all.indices.map { rule.onNodeWithTag("dock-tab-$it").fetchSemanticsNode().boundsInRoot }
        bounds.forEach { assertTrue(it.width >= 48f); assertTrue(it.height >= 48f) }
        bounds.zipWithNext().forEach { (a,b) -> assertTrue(a.right <= b.left) }
        // V19: equal slots (icon + label everywhere); the glass lens marks selection.
        assertEquals(bounds.first().width, bounds.last().width, 1f)
        rule.onNodeWithTag("dock-tab-0").assertHeightIsEqualTo(52.dp)
    }

    @Test fun hiddenPanelPreferenceKeepsThreeUsableDestinations() {
        val items = listOf(all[0], all[2], all[3])
        render(items)
        rule.onNodeWithContentDescription("面板").assertDoesNotExist()
        rule.onNodeWithContentDescription("设置").performClick()
        rule.onNodeWithTag("dock-tab-2").assertIsSelected()
        assertEquals(listOf(2), calls)
    }

    @Test @Config(qualifiers = "w320dp-h900dp-mdpi")
    fun darkNarrowDockAndLargeTextRemainWithinWindow() {
        render(dark = true, scale = 1.6f)
        rule.onNodeWithContentDescription("设置").performTouchInput { click() }
        rule.onNodeWithText("设置", useUnmergedTree = true).assertIsDisplayed()
        for (index in all.indices) rule.onNodeWithTag("dock-tab-$index").assertIsDisplayed()
        rule.runOnIdle {
            val view = rule.activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            val file = File("build/outputs/compact-home/ui4-dock-dark-narrow.png")
            file.parentFile?.mkdirs()
            file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            bitmap.recycle()
        }
    }
}
