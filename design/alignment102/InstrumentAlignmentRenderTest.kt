package io.github.xgl34222220.hetu

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextLayoutResult
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w480dp-h2400dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class InstrumentAlignmentRenderTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val app get() = ApplicationProvider.getApplicationContext<Context>()
    private val ids = listOf("network", "speed", "usage", "resource")
    private fun bounds(tag: String) = compose.onNodeWithTag(tag, true).fetchSemanticsNode().boundsInRoot
    private fun baseline(tag: String): Float {
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag(tag, true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals("Text missing for $tag", 1, layouts.size)
        assertFalse("Text clipped for $tag", layouts.single().hasVisualOverflow)
        return bounds(tag).top + layouts.single().firstBaseline
    }
    private fun capture(name: String) {
        compose.waitForIdle()
        val b = compose.onNodeWithTag("aligned-scene", true).fetchSemanticsNode().boundsInWindow
        val image = compose.runOnIdle {
            val decor = compose.activity.window.decorView
            val xy = IntArray(2).also { decor.getLocationInWindow(it) }
            Bitmap.createBitmap(kotlin.math.ceil(b.width).toInt(), kotlin.math.ceil(b.height).toInt(), Bitmap.Config.ARGB_8888).also {
                val c = Canvas(it); c.translate(xy[0] - b.left, xy[1] - b.top); decor.draw(c)
            }
        }
        File("build/reports/ui-audit/$name.png").apply { parentFile?.mkdirs() }.outputStream()
            .use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    private fun runtime() = ProxyRuntimeSnapshot(running = true, wanAddress = "219.68.41.94", wanCountryCode = "TW",
        wanRegion = "Taiwan · 台北", wanState = "success", lanAddress = "192.168.2.96", lanInterface = "wlan0")

    @Test fun sharedHeaderValueSupportAndRailsAlignInBothThemesAndLargeFont() {
        var width by mutableFloatStateOf(360f)
        var scale by mutableFloatStateOf(1f)
        var night by mutableStateOf(false)
        compose.setContent { key(night) {
            app.getSharedPreferences("hetu", 0).edit().putString("appearance", if (night) "dark" else "light").commit()
            HetuTheme { CompositionLocalProvider(LocalDensity provides Density(1f, scale), LocalHetuMotionEnabled provides false) {
                Column(Modifier.width(width.dp).crystalPageBackground().padding(16.dp).testTag("aligned-scene")) {
                    WorkspaceBento(runtime(), 20, 401000, 796000, 177006631321L, 736060939960L, 3, 81788928L, 0f, {})
                }
            } }
        } }
        for (dark in listOf(false, true)) for ((w, f) in listOf(320f to 1f, 360f to 1f, 412f to 1f, 360f to 1.5f)) {
            compose.runOnIdle { width = w; scale = f; night = dark }; compose.waitForIdle()
            val top = bounds("instrument-network")
            for (id in ids) {
                val b = bounds("instrument-$id")
                assertEquals("Unequal cell heights at $w/$f", top.height, b.height, .6f)
                for (slot in listOf("title", "value", "support")) {
                    assertEquals("Misaligned $slot baseline for $id at $w/$f",
                        baseline("instrument-network-$slot") - top.top,
                        baseline("instrument-$id-$slot") - b.top, .6f)
                }
                val railTag = when(id) { "usage" -> "home-usage-progress"; "resource" -> "home-cpu-progress"; else -> "instrument-$id-rail" }
                assertEquals("Rail bottom does not match", bounds("instrument-network-rail").top - top.top,
                    bounds(railTag).top - b.top, .6f)
                assertEquals(2f, bounds(railTag).height, .6f)
            }
            if (f == 1f) assertEquals("Central gutter still exists", .5f, bounds("instrument-speed").left - top.right, 1f)
            else assertTrue("Large font must not squeeze a two-column panel", bounds("instrument-speed").top > top.bottom)
            capture("aligned102-$w-$f-${if(dark) "dark" else "light"}")
        }
    }

    @Test fun updatesDoNotMoveSlotsAndNoFakeHealthOrUnknownProgress() {
        var empty by mutableStateOf(false)
        var stopped by mutableStateOf(false)
        var high by mutableStateOf(false)
        compose.setContent { HetuTheme { CompositionLocalProvider(LocalDensity provides Density(1f, 1f), LocalHetuMotionEnabled provides false) {
            Column(Modifier.width(360.dp).crystalPageBackground().padding(16.dp).testTag("aligned-scene")) {
                WorkspaceBento(runtime().copy(running = !stopped, wanState = if(empty) "failed" else "success", wanError = "示例检测失败"),
                    20, if(empty) 0 else if(high) 1024L * 1024 * 900 else 1500,
                    if(empty) 0 else if(high) 1024L * 1024 * 1023 else 28000,
                    if(empty) 0 else 25_000_000_000L, if(empty) 0 else 128_000_000_000L, 2,
                    if(high) 1024L * 1024 * 1023 else 81788928L, if(empty) Float.NaN else 6.3f, {})
            }
        } } }
        val initial = ids.map { bounds("instrument-$it-value-slot").top }
        compose.runOnIdle { high = true }; compose.waitForIdle()
        assertEquals(initial, ids.map { bounds("instrument-$it-value-slot").top })
        ids.forEach { baseline("instrument-$it-value") }
        compose.runOnIdle { empty = true }; compose.waitForIdle()
        assertEquals(initial, ids.map { bounds("instrument-$it-value-slot").top })
        compose.onNodeWithText("正常", true).assertDoesNotExist()
        compose.onNodeWithText("健康", true).assertDoesNotExist()
        compose.onNodeWithTag("home-usage-progress", true).assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.ProgressBarRangeInfo))
        compose.onNodeWithTag("home-cpu-progress", true).assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.ProgressBarRangeInfo))
        compose.onNodeWithTag("instrument-speed-rail", true).assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.ProgressBarRangeInfo))
        capture("aligned102-unknown")
        compose.runOnIdle { stopped = true }; compose.waitForIdle()
        compose.onNodeWithText("实时", true).assertDoesNotExist()
        capture("aligned102-stopped")
    }

    @Test fun networkSwitchDetailsAndSubscriptionActionRemainSeparate() {
        var subscriptions = 0
        compose.setContent { HetuTheme { CompositionLocalProvider(LocalDensity provides Density(1f, 1f), LocalHetuMotionEnabled provides false) {
            Column(Modifier.width(360.dp).crystalPageBackground().padding(16.dp).testTag("aligned-scene")) {
                WorkspaceBento(runtime(), 20, 1024, 3072, 25, 128, 2, 81788928L, 6.3f, { subscriptions++ })
            }
        } } }
        compose.onNodeWithTag("instrument-network", true).performClick()
        compose.onNodeWithText("192.168.2.96", true).assertExists()
        compose.onNodeWithTag("instrument-network", true).performTouchInput { longClick() }
        compose.onNodeWithText("网络详情", true).assertExists()
        compose.onNodeWithText("出口地址：219.68.41.94", true).assertExists()
        compose.onNodeWithText("关闭").performClick()
        compose.onNodeWithTag("instrument-usage", true).performClick()
        compose.runOnIdle { assertEquals(1, subscriptions) }
        compose.onNodeWithTag("instrument-speed-rail", true).assert(SemanticsMatcher.expectValue(
            SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo(.25f, 0f..1f)))
    }

    @Test fun fractionsAreActualAndBoundedWithoutOverflow() {
        assertNull(instrumentFraction(0, 0))
        assertNull(instrumentFraction(-1, 100))
        assertEquals(.25f, instrumentFraction(25, 100)!!, 0f)
        assertEquals(1f, instrumentFraction(120, 100)!!, 0f)
        assertNull(instrumentUploadShare(0, 0))
        assertNull(instrumentUploadShare(-1, 100))
        assertEquals(.5f, instrumentUploadShare(Long.MAX_VALUE, Long.MAX_VALUE)!!, 0f)
        assertEquals(.25f, instrumentUploadShare(1, 3)!!, 0f)
    }
}
