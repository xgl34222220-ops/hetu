package io.github.xgl34222220.hetu

import android.app.Application
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.PowerManager
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import dev.chrisbanes.haze.HazeState
import io.github.xgl34222220.hetu.home.*
import io.github.xgl34222220.hetu.ui.*
import java.io.File
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Newly authored recovery coverage of the actual dock/theme, with native rendered captures. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "zh-rCN-w393dp-h852dp-mdpi", application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GlassDockFeedback93Test {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val prefs get() = app.getSharedPreferences("hetu", 0)
    private val items = listOf(DockItem("首页", Icons.Rounded.Home), DockItem("面板", Icons.Rounded.Link),
        DockItem("工具", Icons.Rounded.GridView), DockItem("设置", Icons.Rounded.Settings))
    private var selected by mutableIntStateOf(0)
    private var measured = 0.dp
    private var navigationInset = 0.dp
    private val picks = mutableListOf<Int>()

    @Before fun prepare() {
        prefs.edit().clear().putBoolean("enableBlur", false).putBoolean("enableAnimations", false)
            .putBoolean("floatingBottomBar", true).commit()
    }

    private fun dock(scale: Float = 1f) {
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, scale)) {
                HetuAppTheme("light", false) {
                    navigationInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
                    Box(Modifier.fillMaxSize().homeDiffuseCanvas()) {
                        HetuGlassDock(items, selected, { selected = it; picks += it }, remember { HazeState() }, null,
                            Modifier.align(Alignment.BottomCenter), onHeightChanged = { measured = it })
                    }
                }
            }
        }
        rule.waitForIdle()
    }

    private fun capture(name: String): Bitmap {
        lateinit var bitmap: Bitmap
        rule.runOnIdle {
            val view = rule.activity.window.decorView
            bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            val output = File("build/outputs/ui93/$name.png")
            output.parentFile!!.mkdirs()
            output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        return bitmap
    }

    @Test fun largeFontDockMeasuresItsWholeFootprintAndKeepsEveryLabelInsideItsSlot() {
        dock(scale = 2f)
        assertTrue("Scaled labels must increase dock clearance", measured > 64.dp + navigationInset + 8.dp)
        for (index in items.indices) {
            val slot = rule.onNodeWithTag("dock-tab-$index")
            slot.assertIsDisplayed().assertHeightIsAtLeast(60.dp)
            val label = rule.onNodeWithText(items[index].label, useUnmergedTree = true)
            val labelBounds = label.getUnclippedBoundsInRoot()
            val slotBounds = slot.getUnclippedBoundsInRoot()
            assertTrue("Label must stay inside its touch slot", labelBounds.top >= slotBounds.top && labelBounds.bottom <= slotBounds.bottom)
            slot.performTouchInput { click() }
            slot.assertIsSelected()
        }
        assertEquals(listOf(0, 1, 2, 3), picks)
        capture("dock-large-font-2x").recycle()
    }

    @Test fun changingFloatingModeRecomputesMeasuredClearanceWithoutCountingInsetTwice() {
        dock()
        val floating = measured
        assertEquals(72.dp + navigationInset, floating)
        rule.runOnIdle { prefs.edit().putBoolean("floatingBottomBar", false).commit() }
        rule.waitForIdle()
        assertEquals(64.dp + navigationInset, measured)
        rule.onNodeWithTag("dock-tab-3").performTouchInput { click() }
        assertEquals(3, selected)
        capture("dock-attached").recycle()
    }

    @Test fun themeHonorsLiveAnimationAndGlassPreferencesAndRetainsSelection() {
        prefs.edit().putBoolean("enableAnimations", true).putBoolean("enableBlur", true).commit()
        rule.setContent {
            HetuAppTheme("light", false) {
                Text("motion=${LocalHomeMotionEnabled.current};glass=${rememberHetuGlassEnabled()}")
            }
        }
        rule.onNodeWithText("motion=true;glass=true").assertExists()
        rule.runOnIdle { prefs.edit().putBoolean("enableAnimations", false).putBoolean("enableBlur", false).commit() }
        rule.onNodeWithText("motion=false;glass=false").assertExists()
        rule.runOnIdle { prefs.edit().putBoolean("enableBlur", true).putBoolean("liquidGlass", false).commit() }
        rule.onNodeWithText("motion=false;glass=false").assertExists()
        rule.runOnIdle { prefs.edit().putBoolean("enableAnimations", true).putBoolean("liquidGlass", true).commit() }
        rule.onNodeWithText("motion=true;glass=true").assertExists()
    }

    @Test fun batterySaverDropsOffscreenEffectsAndMotionThenRestoresUserPreferences() {
        prefs.edit().putBoolean("enableAnimations", true).putBoolean("enableBlur", true).commit()
        val power = app.getSystemService(Context.POWER_SERVICE) as PowerManager
        Shadows.shadowOf(power).setIsPowerSaveMode(false)
        rule.setContent {
            // Match the launcher -> home adapter bridge. LocalHomeBlur intentionally defaults
            // to false for isolated previews; HetuAppTheme alone does not enable a page bar.
            CompositionLocalProvider(LocalHxBlur provides prefs.getBoolean("enableBlur", true)) {
                HetuAppTheme("light", false) {
                    HetuHomeThemeFromPrefs(prefs) {
                        val bar = rememberHomeBarGlass(lifted = true)
                        Text("motion=${LocalHomeMotionEnabled.current};glass=${rememberHetuGlassEnabled()};bar=${bar.haze != null}")
                    }
                }
            }
        }
        rule.onNodeWithText("motion=true;glass=true;bar=true").assertExists()
        rule.runOnIdle {
            Shadows.shadowOf(power).setIsPowerSaveMode(true)
            app.sendBroadcast(Intent(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED))
        }
        rule.onNodeWithText("motion=false;glass=false;bar=false").assertExists()
        assertTrue(prefs.getBoolean("enableAnimations", false))
        assertTrue(prefs.getBoolean("enableBlur", false))
        rule.runOnIdle {
            Shadows.shadowOf(power).setIsPowerSaveMode(false)
            app.sendBroadcast(Intent(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED))
        }
        rule.onNodeWithText("motion=true;glass=true;bar=true").assertExists()
    }

    @Test fun motionDisabledDockSettlesToAnUnchangingFrameAfterRepeatedRealTaps() {
        dock()
        listOf(3, 1, 2, 0, 3).forEach { index -> rule.onNodeWithTag("dock-tab-$index").performTouchInput { click() } }
        rule.onNodeWithTag("dock-tab-3").assertIsSelected()
        val first = capture("dock-repeated-taps-settled")
        rule.mainClock.advanceTimeBy(2_000)
        val later = capture("dock-motion-off-after-2s")
        assertTrue("Reduced-motion dock must not leave a perpetual render animation", first.sameAs(later))
        assertEquals(listOf(3, 1, 2, 0, 3), picks)
        first.recycle(); later.recycle()
    }
}
