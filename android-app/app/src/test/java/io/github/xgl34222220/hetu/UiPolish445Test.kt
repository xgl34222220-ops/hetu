package io.github.xgl34222220.hetu

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import io.github.xgl34222220.hetu.home.HomeAccent
import io.github.xgl34222220.hetu.home.HomeCard
import io.github.xgl34222220.hetu.home.HomeColors
import io.github.xgl34222220.hetu.home.HomeSpeedHistory
import io.github.xgl34222220.hetu.home.HomeType
import io.github.xgl34222220.hetu.home.HomeUiState
import io.github.xgl34222220.hetu.home.LocalHomeColors
import io.github.xgl34222220.hetu.home.LocalHomeMotionEnabled
import io.github.xgl34222220.hetu.home.badText
import io.github.xgl34222220.hetu.home.delayColor
import io.github.xgl34222220.hetu.home.drawHomeSpeedBackdrop
import io.github.xgl34222220.hetu.home.homeColors
import io.github.xgl34222220.hetu.home.homeTouchExpand
import io.github.xgl34222220.hetu.home.rememberHomeFlaggedName
import io.github.xgl34222220.hetu.home.warnText
import io.github.xgl34222220.hetu.home.withoutLiveSamples
import java.io.File
import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 4.4.4 beautification pass, layout untouched: latency colours that never borrow the warning
 * hues, accent text that reads on every card, an accent-washed canvas (default blue and OLED
 * black unchanged), pressed feedback that survives reduced motion, a 48 dp WAN/LAN touch box,
 * the 网速 backdrop curve, one flag style on node tiles and the sliding dock.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "zh-rCN-w393dp-h852dp-mdpi", application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class UiPolish445Test {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private fun source(path: String) = File("src/main/java/io/github/xgl34222220/hetu/$path").readText()

    private fun ratio(a: Color, b: Color): Float =
        (maxOf(a.luminance(), b.luminance()) + .05f) / (minOf(a.luminance(), b.luminance()) + .05f)

    private fun palettes(): List<Pair<String, HomeColors>> = listOf(false, true).flatMap { dark ->
        HomeAccent.entries.map { accent -> "$accent/${if (dark) "dark" else "light"}" to homeColors(dark, accent.color(dark)) }
    }

    @Test fun healthyLatencyNeverWearsTheWarningHues() {
        val warm = setOf(HomeAccent.Red, HomeAccent.Orange, HomeAccent.Pink)
        for (dark in listOf(false, true)) for (accent in HomeAccent.entries) {
            val c = homeColors(dark, accent.color(dark))
            if (accent in warm) assertEquals("$accent fast latency turns green", c.good, c.delayFast)
            else assertEquals("$accent fast latency keeps the accent", c.accent, c.delayFast)
            assertTrue("$accent/$dark healthy figure readable", ratio(c.delayColor(28), c.sunken) >= 4.5f)
            assertEquals(c.warnText, c.delayColor(500))
            assertEquals(c.badText, c.delayColor(1200))
            assertNotEquals("$accent/$dark healthy and very slow must differ", c.delayColor(28), c.delayColor(1200))
        }
        val panel = source("panel/PanelComponents.kt")
        assertTrue(panel.contains("is PanelDelay.Ms -> c.delayColor(delay.value) to c.delayBase(delay.value)"))
        assertFalse("one set of latency bands for home and panel", panel.contains("delay.value < 300L"))
    }

    @Test fun accentTextReadsOnEveryCardSurfaceAndTheDefaultBlueIsUnchanged() {
        for ((name, c) in palettes()) {
            assertTrue("$name on sunken", ratio(c.accentText, c.sunken) >= 4.5f)
            assertTrue("$name on surface", ratio(c.accentText, c.surface) >= 4.5f)
        }
        val blue = homeColors(false)
        assertEquals(blue.accent, blue.accentText)
        val orange = homeColors(false, HomeAccent.Orange.color(false))
        assertNotEquals("orange is deepened for small text", orange.accent, orange.accentText)
        for (path in listOf("panel/PanelGroupsTab.kt", "panel/PanelListTabs.kt", "tools/ToolsFeatureComponents.kt", "home/HomeScreen.kt")) {
            assertFalse("$path: no Text drawn in the raw accent", Regex("""Text\([^\n]*color = c\.accent[,)\s]""").containsMatchIn(source(path)))
        }
    }

    @Test fun nonDefaultAccentWashesTheCanvasWhileDefaultAndOledStayExact() {
        val light = homeColors(false)
        val dark = homeColors(true)
        assertEquals(Color(0xFFECEEFB), light.bg)
        assertEquals(Color(0xFFE9EBF7), light.sunken)
        assertEquals(Color(0xFF0E0F13), dark.bg)
        assertFalse(light.canvasTint.isSpecified)
        assertFalse(dark.canvasTint.isSpecified)
        for (accent in HomeAccent.entries) {
            assertEquals("$accent OLED stays black", Color.Black, homeColors(true, accent.color(true), pureBlack = true).bg)
            if (accent == HomeAccent.Default) continue
            val tinted = homeColors(false, accent.color(false))
            assertEquals(accent.color(false), tinted.canvasTint)
            assertNotEquals("$accent washes the canvas", light.bg, tinted.bg)
            assertTrue("$accent metadata still readable", ratio(tinted.t3, tinted.surface) >= 4.5f)
            assertTrue("$accent body text readable on the canvas", ratio(tinted.t2, tinted.bg) >= 4.5f)
        }
        assertTrue(source("home/HomeMaterial.kt").contains("if (c.canvasTint.isSpecified) lerp(neutralMist, c.canvasTint"))
    }

    @Test fun pressedCardShowsATintWithReducedMotionAndClearsAfterRelease() {
        rule.setContent {
            CompositionLocalProvider(LocalHomeColors provides homeColors(), LocalHomeMotionEnabled provides false) {
                Box(Modifier.size(240.dp)) {
                    HomeCard(Modifier.padding(20.dp).size(180.dp).testTag("card"), onClick = {}) {}
                }
            }
        }
        fun centre(): Int {
            val bounds = rule.onNodeWithTag("card").fetchSemanticsNode().boundsInWindow
            val x = bounds.center.x.toInt()
            val y = bounds.center.y.toInt()
            return capture().let { val p = it.getPixel(x, y); it.recycle(); p }
        }
        val resting = centre()
        rule.onNodeWithTag("card").performTouchInput { down(center) }
        advance(50)
        val pressed = centre()
        assertNotEquals("a press must be visible without motion", resting, pressed)
        assertTrue("the wash darkens the light card", Color(pressed).luminance() < Color(resting).luminance())
        rule.onNodeWithTag("card").performTouchInput { up() }
        advance(400)
        assertEquals("released card returns to rest", resting, centre())
    }

    @Test fun touchExpandGrowsTheTargetWithoutMovingAnything() {
        val clicks = mutableIntStateOf(0)
        rule.setContent {
            Column {
                Spacer(Modifier.height(40.dp))
                Row(Modifier.testTag("expanded-row")) {
                    // The slot is what the parent lays out. (A tag on the Text itself would report the bounds of its
                    // outermost semantics modifier, the clickable, i.e. the enlarged touch box.)
                    Box(Modifier.testTag("lan")) {
                        Text("LAN", Modifier.homeTouchExpand(6.dp, 9.dp).clickable { clicks.intValue++ }.padding(horizontal = 6.dp, vertical = 9.dp))
                    }
                    Text("next", Modifier.testTag("next"))
                }
                Row(Modifier.testTag("plain-row")) {
                    Text("LAN", Modifier.testTag("lan-plain"))
                    Text("next", Modifier.testTag("next-plain"))
                }
            }
        }
        val lan = rule.onNodeWithTag("lan").getUnclippedBoundsInRoot()
        val plain = rule.onNodeWithTag("lan-plain").getUnclippedBoundsInRoot()
        assertEquals("drawn text keeps its size", (plain.right - plain.left).value, (lan.right - lan.left).value, .5f)
        assertEquals("the neighbour does not move", rule.onNodeWithTag("next-plain").getUnclippedBoundsInRoot().left.value,
            rule.onNodeWithTag("next").getUnclippedBoundsInRoot().left.value, .5f)
        val rowHeight = rule.onNodeWithTag("expanded-row").getUnclippedBoundsInRoot().let { (it.bottom - it.top).value }
        val plainHeight = rule.onNodeWithTag("plain-row").getUnclippedBoundsInRoot().let { (it.bottom - it.top).value }
        assertEquals("the row keeps its height", plainHeight, rowHeight, .5f)
        // 7 dp above the drawn text is outside its own bounds but inside the expanded box.
        rule.onRoot().performTouchInput { click(Offset(((lan.left + lan.right) / 2).toPx(), (lan.top - 7.dp).toPx())) }
        rule.waitForIdle()
        assertEquals(1, clicks.intValue)
    }

    @Test fun networkTitleSwitchUsesTheExpandedTouchBox() {
        val home = source("home/HomeScreen.kt")
        assertTrue(home.contains(".homeTouchExpand(NetSideTouchX, NetSideTouchY)"))
        assertTrue(home.contains(".padding(horizontal = NetSideTouchX, vertical = NetSideTouchY))"))
    }

    @Test fun speedBackdropDrawsOnlyInTheLowerHalfAndNeedsTwoPoints() {
        fun draw(history: HomeSpeedHistory): ImageBitmap {
            val image = ImageBitmap(300, 120)
            CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, androidx.compose.ui.graphics.Canvas(image), Size(300f, 120f)) {
                drawHomeSpeedBackdrop(history, Color.Blue, Color.Gray)
            }
            return image
        }
        val window = (1..30).map { if (it == 12) null else it * 40_000L }
        val pixels = draw(HomeSpeedHistory(upload = window.map { it?.div(4) }, download = window)).toPixelMap()
        for (y in 0 until 56) for (x in 0 until 300) assertEquals("upper half stays clear at $x,$y", 0f, pixels[x, y].alpha)
        var inked = 0
        for (y in 60 until 120) for (x in 200 until 300) if (pixels[x, y].alpha > 0f) inked++
        assertTrue("recent traffic is drawn at the right", inked > 200)
        val single = draw(HomeSpeedHistory(upload = listOf(1L), download = listOf(1L))).toPixelMap()
        for (y in 0 until 120 step 4) for (x in 0 until 300 step 4) assertEquals(0f, single[x, y].alpha)
    }

    @Test fun calmViewDropsTheSpeedWindowLikeTheOtherLiveSamples() {
        val live = HomeUiState(speedHistory = HomeSpeedHistory(listOf(1L, 2L), listOf(3L, null)))
        assertEquals(HomeSpeedHistory(), live.withoutLiveSamples().speedHistory)
        val adapter = source("home/HomeAdapter.kt")
        assertTrue(adapter.contains("rememberSpeedHistory(sampling, speedSource, mapped.uploadBytesPerSecond, mapped.downloadBytesPerSecond)"))
        assertTrue("source switch starts a fresh window", adapter.contains("LaunchedEffect(source) { upPoints.clear(); downPoints.clear() }"))
    }

    @Test fun leadingFlagEmojiIsDrawnAsTheVectorFlagButTheNameStaysTheSame() {
        val flagged = "🇭🇰 香港 01"
        var flaggedInline = -1
        var plainInline = -1
        rule.setContent {
            CompositionLocalProvider(LocalHomeColors provides homeColors()) {
                Column {
                    val (text, inline) = rememberHomeFlaggedName(flagged, HomeType.rowTitle)
                    flaggedInline = inline.size
                    Text(text, Modifier.testTag("flagged"), inlineContent = inline)
                    val (plain, none) = rememberHomeFlaggedName("香港 02", HomeType.rowTitle)
                    plainInline = none.size
                    Text(plain, Modifier.testTag("plain"), inlineContent = none)
                }
            }
        }
        rule.waitForIdle()
        assertEquals(1, flaggedInline)
        assertEquals(0, plainInline)
        rule.onNodeWithTag("flagged").assertTextEquals(flagged)
        rule.onNodeWithTag("plain").assertTextEquals("香港 02")
        assertTrue(source("panel/PanelGroupsTab.kt").contains("val (label, flag) = rememberHomeFlaggedName(node.name, PanelType.nodeName)"))
    }

    @Test fun dockSlidesAwayForDetailPagesAndSimplyHidesWithReducedMotion() {
        val shell = source("app/HetuActivity.kt")
        assertTrue(shell.contains("if (motion) AnimatedVisibility(\n            visible = !detailVisible,"))
        assertTrue(shell.contains("else if (!detailVisible) dock(Modifier.align(Alignment.BottomCenter))"))
        assertFalse(shell.contains("if (!detailVisible) HetuGlassDock("))
    }

    /** Moves both the frame clock and the main looper, whichever one the effect's delay runs on. */
    private fun advance(ms: Long) {
        rule.mainClock.advanceTimeBy(ms)
        rule.runOnUiThread { Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms)) }
        rule.mainClock.advanceTimeBy(16)
    }

    private fun capture(): Bitmap {
        rule.waitForIdle()
        lateinit var bitmap: Bitmap
        rule.runOnIdle {
            val view = rule.activity.window.decorView
            bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
        }
        return bitmap
    }
}
