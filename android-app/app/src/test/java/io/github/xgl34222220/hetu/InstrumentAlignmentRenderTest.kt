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
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
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
@Config(sdk = [35], qualifiers = "zh-rCN-w480dp-h2400dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class InstrumentAlignmentRenderTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val app get() = ApplicationProvider.getApplicationContext<Context>()

    private fun bounds(tag: String) =
        compose.onNodeWithTag(tag, true).fetchSemanticsNode().boundsInRoot

    private fun capture(name: String) {
        compose.waitForIdle()
        val b = compose.onNodeWithTag("aligned-scene", true).fetchSemanticsNode().boundsInWindow
        val image = compose.runOnIdle {
            val decor = compose.activity.window.decorView
            val xy = IntArray(2).also { decor.getLocationInWindow(it) }
            Bitmap.createBitmap(
                kotlin.math.ceil(b.width).toInt(),
                kotlin.math.ceil(b.height).toInt(),
                Bitmap.Config.ARGB_8888,
            ).also {
                val canvas = Canvas(it)
                canvas.translate(xy[0] - b.left, xy[1] - b.top)
                decor.draw(canvas)
            }
        }
        File("build/reports/ui-audit/$name.png").apply { parentFile?.mkdirs() }
            .outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun runtime() = ProxyRuntimeSnapshot(
        running = true,
        wanAddress = "219.68.41.94",
        wanCountryCode = "TW",
        wanRegion = "Taiwan · 台北",
        wanState = "success",
        lanAddress = "192.168.2.96",
        lanInterface = "wlan0",
    )

    @Test
    fun referenceFourCardsReflowForNarrowScreensAndLargeFonts() {
        var width by mutableFloatStateOf(360f)
        var scale by mutableFloatStateOf(1f)
        var night by mutableStateOf(false)

        compose.setContent {
            key(night) {
                app.getSharedPreferences("hetu", 0).edit()
                    .putString("appearance", if (night) "dark" else "light")
                    .commit()
                HetuTheme {
                    CompositionLocalProvider(
                        LocalDensity provides Density(1f, scale),
                        LocalHetuMotionEnabled provides false,
                    ) {
                        Column(
                            Modifier.width(width.dp).crystalPageBackground().padding(12.dp)
                                .testTag("aligned-scene"),
                        ) {
                            WorkspaceBento(
                                runtime(), 20, 401000, 796000,
                                177006631321L, 736060939960L, 3,
                                81788928L, 6.3f, {},
                            )
                        }
                    }
                }
            }
        }

        for (dark in listOf(false, true)) {
            for ((w, f) in listOf(320f to 1f, 360f to 1f, 412f to 1f, 360f to 1.5f)) {
                compose.runOnIdle { width = w; scale = f; night = dark }
                compose.waitForIdle()

                val network = bounds("instrument-network")
                val speed = bounds("instrument-speed")
                val usage = bounds("instrument-usage")
                val resource = bounds("instrument-resource")

                if ((w - 24f) / f < 280f) {
                    assertTrue("Narrow/large-font cards must stack", speed.top > network.bottom)
                    assertTrue("Subscription must follow speed", usage.top > speed.bottom)
                    assertTrue("Resource must follow subscription", resource.top > usage.bottom)
                    assertEquals(network.left, speed.left, .6f)
                    assertEquals(network.width, speed.width, .6f)
                } else {
                    assertEquals("Top row must align", network.top, speed.top, .6f)
                    assertEquals("Bottom row must align", usage.top, resource.top, .6f)
                    assertTrue("Roomy cards should use two columns", speed.left > network.right)
                    assertEquals("Shared panel uses a hairline divider", 1f, speed.left - network.right, 1f)
                }
                assertEquals(5f, bounds("home-usage-progress").height, .6f)
                assertEquals(5f, bounds("home-cpu-progress").height, .6f)
                compose.onNodeWithTag("home-usage-progress", true).assert(
                    SemanticsMatcher.expectValue(
                        SemanticsProperties.ProgressBarRangeInfo,
                        ProgressBarRangeInfo(
                            (177006631321.0 / 736060939960.0).toFloat(),
                            0f..1f,
                        ),
                    ),
                )
                compose.onNodeWithTag("home-cpu-progress", true).assert(
                    SemanticsMatcher.expectValue(
                        SemanticsProperties.ProgressBarRangeInfo,
                        ProgressBarRangeInfo(.063f, 0f..1f),
                    ),
                )

                compose.onNodeWithText("WAN", true).assertExists()
                compose.onNodeWithText("网速", true).assertExists()
                compose.onNodeWithText("订阅", true).assertExists()
                compose.onNodeWithText("资源占用", true).assertExists()

                capture("aligned156785-$w-$f-${if (dark) "dark" else "light"}")
            }
        }
    }

    @Test
    fun unknownTelemetryKeepsTrackButDoesNotInventProgress() {
        var empty by mutableStateOf(false)
        compose.setContent {
            HetuTheme {
                CompositionLocalProvider(
                    LocalDensity provides Density(1f, 1f),
                    LocalHetuMotionEnabled provides false,
                ) {
                    Column(
                        Modifier.width(360.dp).crystalPageBackground().padding(12.dp)
                            .testTag("aligned-scene"),
                    ) {
                        WorkspaceBento(
                            runtime().copy(wanState = if (empty) "failed" else "success"),
                            20, 1500, 28000,
                            if (empty) 0L else 25_000_000_000L,
                            if (empty) 0L else 128_000_000_000L,
                            2, 81788928L,
                            if (empty) Float.NaN else 6.3f,
                            {},
                        )
                    }
                }
            }
        }

        compose.runOnIdle { empty = true }
        compose.waitForIdle()
        compose.onNodeWithTag("home-usage-progress", true)
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.ProgressBarRangeInfo))
        compose.onNodeWithTag("home-cpu-progress", true)
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.ProgressBarRangeInfo))
        compose.onNodeWithText("检测失败", true).assertExists()
        capture("aligned156785-unknown")
    }

    @Test
    fun networkAndSubscriptionInteractionsRemainRealAndSeparate() {
        var subscriptions = 0
        compose.setContent {
            HetuTheme {
                CompositionLocalProvider(
                    LocalDensity provides Density(1f, 1f),
                    LocalHetuMotionEnabled provides false,
                ) {
                    Column(
                        Modifier.width(360.dp).crystalPageBackground().padding(12.dp)
                            .testTag("aligned-scene"),
                    ) {
                        WorkspaceBento(
                            runtime(), 20, 1024, 3072,
                            25, 128, 2, 81788928L, 6.3f,
                            { subscriptions++ },
                        )
                    }
                }
            }
        }

        compose.onNodeWithTag("instrument-network", true).performClick()
        compose.onNodeWithText("192.168.2.96", true).assertExists()
        compose.onNodeWithTag("instrument-network", true).performTouchInput { longClick() }
        compose.onNodeWithText("网络详情", true).assertExists()
        compose.onNodeWithText("出口地址：219.68.41.94", true).assertExists()
        compose.onNodeWithText("关闭").performClick()

        compose.onNodeWithTag("instrument-usage", true).performClick()
        compose.runOnIdle { assertEquals(1, subscriptions) }
    }

    @Test
    fun fractionsAreActualAndBoundedWithoutOverflow() {
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
