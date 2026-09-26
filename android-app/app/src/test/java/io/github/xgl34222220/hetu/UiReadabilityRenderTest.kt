package io.github.xgl34222220.hetu

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
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
@Config(sdk = [35], qualifiers = "zh-rCN-w480dp-h2800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class UiReadabilityRenderTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val app get() = ApplicationProvider.getApplicationContext<Context>()

    private fun readable(tag: String, container: String) {
        val text = compose.onNodeWithTag(tag, true)
        val layouts = mutableListOf<TextLayoutResult>()
        text.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertTrue("Missing text layout for $tag", layouts.isNotEmpty())
        layouts.forEach { layout ->
            assertFalse("Text height clipped: $tag ${layout.size}", layout.didOverflowHeight)
            for (line in 0 until layout.lineCount) {
                assertFalse("Critical text must not be ellipsized: $tag", layout.isLineEllipsized(line))
                assertTrue("Line bottom outside text: $tag", layout.getLineBottom(line) <= layout.size.height + .6f)
            }
        }
        val child = text.fetchSemanticsNode().boundsInRoot
        val parent = compose.onNodeWithTag(container, true).fetchSemanticsNode().boundsInRoot
        assertTrue("Text beyond parent: $tag $child / $parent", child.top >= parent.top - .6f && child.bottom <= parent.bottom + .6f && child.left >= parent.left - .6f && child.right <= parent.right + .6f)
    }

    private fun raster(tag: String): Bitmap {
        compose.waitForIdle()
        val b = compose.onNodeWithTag(tag, true).fetchSemanticsNode().boundsInWindow
        return compose.runOnIdle {
            val decor = compose.activity.window.decorView
            val origin = IntArray(2).also { decor.getLocationInWindow(it) }
            Bitmap.createBitmap(kotlin.math.ceil(b.width).toInt(), kotlin.math.ceil(b.height).toInt(), Bitmap.Config.ARGB_8888).also {
                val canvas = Canvas(it); canvas.translate(origin[0] - b.left, origin[1] - b.top); decor.draw(canvas)
            }
        }
    }

    private fun capture(name: String) {
        val bitmap = raster("readability-scene")
        File("build/reports/ui-audit/$name.png").apply { parentFile.mkdirs() }.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun visibleInk(tag: String) {
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag(tag, true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val bitmap = raster(tag)
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val background = Color(pixels.toList().groupingBy { it }.eachCount().maxBy { it.value }.key).luminance()
        val foreground = layouts.single().layoutInput.style.color.luminance()
        val contrast = (maxOf(background, foreground) + .05f) / (minOf(background, foreground) + .05f)
        assertTrue("Latency text blends into its rendered background: $tag ($contrast)", contrast >= 4.5f)
    }

    @Test fun strategyNamesLatencyAndSelectionLensUseActualMeasuredCardSizes() {
        val longName = "Hong Kong 05 [两年订阅 · 高速专线]"
        val nodes = listOf(ProxyNodeUi(longName, "VLESS", true), ProxyNodeUi("Japan 04", "Trojan"), ProxyNodeUi("台湾节点 · 长名称", "VMESS", true))
        val group = ProxyGroupUi("AI 平台", "Selector", nodes.last().name, nodes)
        var width by mutableIntStateOf(360)
        var font by mutableFloatStateOf(1f)
        var night by mutableStateOf(false)
        var probes = 0; var expansions = 0; var selections = 0
        compose.setContent {
            key(night) {
                app.getSharedPreferences("hetu", 0).edit().putString("appearance", if (night) "dark" else "light")
                    .putInt("proxySelectorNodeColumns", 3).putString("proxySelectorNameOverflow", "clip").commit()
                HetuTheme { CompositionLocalProvider(LocalDensity provides Density(1f, font), LocalHetuMotionEnabled provides false) {
                    Column(Modifier.width(width.dp).crystalPageBackground().padding(16.dp).testTag("readability-scene"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        BoxWithConstraints(Modifier.fillMaxWidth()) {
                            val cardWidth = if (liquidColumns(maxWidth) == 2) (maxWidth - 10.dp) / 2 else maxWidth
                            LiquidStrategyCard(group, longName, true, 99999, false, Modifier.width(cardWidth), { expansions++ }, { probes++ })
                        }
                        LiquidGroupWell(group, nodes.last().name, mapOf(longName to 69L, "Japan 04" to 332L, nodes.last().name to 146L), emptyMap(), { selections++ }, { probes++ }, {})
                    }
                } }
            }
        }
        for (dark in listOf(false, true)) for (w in listOf(320, 360, 412)) for (f in listOf(1f, 1.5f, 2f)) {
            compose.runOnIdle { night = dark; width = w; font = f }; compose.waitForIdle()
            readable("strategy-title:AI 平台", "strategy:AI 平台")
            readable("strategy-selection:AI 平台", "strategy:AI 平台")
            readable("latency-text:99999 ms", "strategy-delay:AI 平台")
            nodes.forEach { readable("node-label:${it.name}", "node:${it.name}") }
            for ((n, v) in listOf(longName to 69, "Japan 04" to 332, nodes.last().name to 146)) readable("latency-text:$v ms", "node-delay:$n")
            val selected = compose.onNodeWithTag("node:${nodes.last().name}", true).fetchSemanticsNode().boundsInRoot
            val lens = compose.onNodeWithTag("node-selection-indicator:AI 平台", true).fetchSemanticsNode().boundsInRoot
            assertEquals(selected.left, lens.left, 1f); assertEquals(selected.top, lens.top, 1f)
            assertEquals(selected.width, lens.width, 1f); assertEquals(selected.height, lens.height, 1f)
            if (w == 360 && f <= 1.5f) capture("test131-policy-$w-$f-${if (dark) "dark" else "light"}")
            if (w == 360 && f == 1f) for (v in listOf(69, 146, 332, 99999)) visibleInk("latency-text:$v ms")
        }
        compose.onNodeWithTag("strategy-delay:AI 平台", true).performClick()
        compose.onNodeWithTag("node-delay:${nodes.last().name}", true).performClick()
        compose.runOnIdle { assertEquals(2, probes); assertEquals(0, expansions); assertEquals(0, selections) }
    }

    @Test fun subscriptionPercentRankAndOriginalLogStayReadable() {
        val provider = DashboardProviderUi("两年订阅 · 家庭常用", "HTTP", "", "", "2026-09-26T18:55:00+08:00", 7100000000, 28800000000, 128000000000, 1829000000, emptySet(), true)
        var font by mutableFloatStateOf(1f)
        var night by mutableStateOf(false)
        var refreshes = 0; var opens = 0
        val raw = "time=\"2026-09-26T19:10:02+08:00\" level=warning msg=\"dial tcp: i/o timeout\""
        compose.setContent { key(night) {
            app.getSharedPreferences("hetu", 0).edit().putString("appearance", if (night) "dark" else "light").commit()
            HetuTheme { CompositionLocalProvider(LocalDensity provides Density(1f, font), LocalHetuMotionEnabled provides false) {
                Column(Modifier.width(320.dp).crystalPageBackground().padding(16.dp).testTag("readability-scene"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    RefProviderRow(provider, false, false, { refreshes++ }, { opens++ })
                    TrafficRankings(listOf(ProxyConnectionUi("1", "long-host-name.example:443", "", "", "DIRECT", 1250, 27000)))
                    StructuredLogCard(raw)
                }
            } }
        } }
        for (dark in listOf(false, true)) for (f in listOf(1f, 1.5f, 2f)) {
            compose.runOnIdle { night = dark; font = f }; compose.waitForIdle()
            readable("subscription-percent:${provider.name}", "subscription-provider:${provider.name}")
            readable("rank-number:0", "readability-scene")
            readable("log-summary", "readability-scene")
            if (f <= 1.5f) capture("test131-panel-320-$f-${if (dark) "dark" else "light"}")
        }
        compose.onNodeWithTag("refresh:${provider.name}", true).performClick()
        compose.runOnIdle { assertEquals(1, refreshes); assertEquals(0, opens) }
        compose.onNodeWithText("查看原始记录").performClick()
        compose.onNodeWithTag("log-original", true).assertTextEquals(raw)
    }

    @Test fun logParsingPreservesEscapesAndLeavesUnstructuredTextUntouched() {
        val parsed = parseStructuredLog("""time="19:10:02" level=warning msg="path \"A B\": timeout"""")!!
        assertEquals("path \"A B\": timeout", parsed.message)
        assertEquals("warning", parsed.level)
        assertEquals("hello\nworld", parseStructuredLog("""{"time":"19:10","level":"info","msg":"hello\nworld"}""")!!.message)
        assertNull(parseStructuredLog("raw line with no structured fields"))
        assertNull(parseStructuredLog("{broken json"))
    }
}
