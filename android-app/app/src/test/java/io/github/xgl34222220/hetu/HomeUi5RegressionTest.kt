package io.github.xgl34222220.hetu

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w393dp-h900dp-mdpi", application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HomeUi5RegressionTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val calls = mutableListOf<String>()
    private fun fixture() = CompactHomeData(running = true, core = "Mihomo", mode = "TPROXY",
        config = "fixture.yaml", uptimeSeconds = 45, wan = "255.255.255.255", lan = "192.168.1.8",
        lanInterface = "wlan0", used = 25, total = 100, memory = 90000000, cpu = 10f,
        up = 20000, down = 80000, delays = mapOf("Baidu" to 45L, "Cloudflare" to -1L, "Google" to 81L))

    private fun render(insets: () -> WindowInsets = { WindowInsets(0.dp, 0.dp, 0.dp, 0.dp) }, motion: Boolean = false) {
        rule.setContent {
            MaterialTheme(colorScheme = lightColorScheme()) {
                CompactHomeDashboard(fixture(), { calls += "refresh" }, { calls += "toggle" },
                    { calls += "reload" }, { calls += "restart" }, { calls += "delay" },
                    { calls += "webui" }, { calls += "log" }, { calls += "subscription" },
                    { calls += "connections" }, { calls += "settings" }, { calls += "diagnostics" },
                    { calls += "adblock" }, motionEnabled = motion, contentInsets = insets())
            }
        }
    }
    private fun layout(tag: String): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        rule.onNodeWithTag(tag, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        return results.single()
    }
    private fun revealGrid() = rule.onNodeWithTag("compact-home").performScrollToNode(hasTestTag("home-resources"))

    @Test fun headerStaysPinnedBelowCutoutThroughoutScrollAndOverscroll() {
        render(insets = { WindowInsets(12.dp, 44.dp, 12.dp, 24.dp) })
        val before = rule.onNodeWithTag("home-header").fetchSemanticsNode().boundsInRoot
        assertEquals(44f, before.top, 1f)
        val viewport = rule.onNodeWithTag("compact-home").fetchSemanticsNode().boundsInRoot
        assertEquals(before.bottom, viewport.top, 1f)
        assertTrue(viewport.left >= 12f)
        revealGrid()
        rule.onNodeWithTag("compact-home").performTouchInput { swipeUp() }
        val after = rule.onNodeWithTag("home-header").fetchSemanticsNode().boundsInRoot
        assertEquals(before, after)
        rule.onNodeWithTag("compact-home").performScrollToIndex(0)
        rule.onNodeWithTag("compact-home").performTouchInput { swipeDown() }
        assertEquals(before, rule.onNodeWithTag("home-header").fetchSemanticsNode().boundsInRoot)
        assertEquals("The completed top-edge pull now dispatches a real refresh", listOf("refresh"), calls)
        rule.onNodeWithContentDescription("刷新状态").performClick()
        assertEquals("Toolbar refresh remains independently reachable", listOf("refresh", "refresh"), calls)
    }

    @Test fun changedSafeInsetsDoNotLoseSelectedNetworkMode() {
        val top = mutableStateOf(24.dp)
        render(insets = { WindowInsets(0.dp, top.value, 0.dp, 20.dp) })
        rule.onNodeWithTag("compact-home").performScrollToNode(hasTestTag("home-network"))
        rule.onNodeWithTag("home-network").performClick()
        rule.runOnIdle { top.value = 48.dp }
        assertEquals(48f, rule.onNodeWithTag("home-header").fetchSemanticsNode().boundsInRoot.top, 1f)
        rule.onNodeWithTag("home-network", useUnmergedTree = true)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "LAN"))
    }

    @Test fun metricsAndAddressUseSystemFamilyAndTabularDigits() {
        render()
        revealGrid()
        for (tag in listOf("home-network-ip", "home-value-上行", "home-value-下行", "home-value-已用", "home-value-内存")) {
            val style = layout(tag).layoutInput.style
            assertEquals(FontFamily.Default, style.fontFamily)
            assertEquals("tnum", style.fontFeatureSettings)
        }
    }

    @Test fun widestIpv4FitsOneLineWithoutReducingFont() {
        render()
        rule.onNodeWithTag("compact-home").performScrollToNode(hasTestTag("home-network"))
        val result = layout("home-network-ip")
        assertEquals("255.255.255.255", result.layoutInput.text.text)
        assertEquals(1, result.lineCount)
        assertFalse(result.hasVisualOverflow)
    }

    private fun renderTrack(fraction: Float?, dark: Boolean = false) {
        rule.setContent { MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
            Box(Modifier.fillMaxSize().background(if (dark) Color(0xFF121212) else Color.White).padding(20.dp)) {
                HomeUsageBar(fraction, "track", if (fraction == null) "未知" else "已用25%", Color(0xFF2563EB))
            }
        } }
    }
    private fun countPixels(argb: Int): Int {
        rule.waitForIdle()
        var count = 0
        rule.runOnIdle {
            val view = rule.activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            val pixels = IntArray(view.width * view.height)
            bitmap.getPixels(pixels, 0, view.width, 0, 0, view.width, view.height)
            count = pixels.count { it == argb }
            bitmap.recycle()
        }
        return count
    }

    @Test fun knownUsageHasVisibleBlueFillAndFullWidthGrayRemainder() {
        renderTrack(.25f)
        rule.onNodeWithTag("track").assertHeightIsEqualTo(6.dp)
        val progress = rule.onNodeWithTag("track").fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo]
        assertEquals(.25f, progress.current, 0f)
        assertTrue("Gray track must actually be painted, not merely declared", countPixels(0xFFE2E8F0.toInt()) > 500)
        assertTrue("Known usage has a real blue fill", countPixels(0xFF2563EB.toInt()) > 100)
    }

    @Test fun unknownUsageKeepsGrayTrackWithoutInventingZeroPercent() {
        renderTrack(null)
        val config = rule.onNodeWithTag("track").fetchSemanticsNode().config
        assertFalse(config.contains(SemanticsProperties.ProgressBarRangeInfo))
        assertTrue(countPixels(0xFFE2E8F0.toInt()) > 1000)
        assertEquals(0, countPixels(0xFF2563EB.toInt()))
    }

    @Test fun darkTrackAlsoRemainsVisible() {
        renderTrack(.25f, dark = true)
        assertTrue(countPixels(0xFF334155.toInt()) > 500)
        assertTrue(countPixels(0xFF2563EB.toInt()) > 100)
    }

    @Test fun sequentialTransitionExitsBeforeSwappingItsSingleBody() {
        val target = mutableStateOf(false)
        lateinit var transition: NetworkModeTransition
        rule.setContent {
            transition = rememberNetworkModeTransition(target.value, true)
            Text(if (transition.displayedLan) "LAN" else "WAN", Modifier.testTag("single-body"))
        }
        rule.waitForIdle()
        rule.mainClock.autoAdvance = false
        // ui5-explicit-snapshot-clock
        rule.runOnIdle {
            target.value = true
            Snapshot.sendApplyNotifications()
        }
        rule.mainClock.advanceTimeUntil(1_000) { transition.leaving }
        val exitStartedAt = rule.mainClock.currentTime
        rule.mainClock.advanceTimeBy(32)
        rule.onNodeWithTag("single-body").assertTextEquals("WAN")
        assertTrue(transition.leaving)
        assertTrue(transition.progress.value > 0f)
        assertTrue(transition.progress.value < 1f)
        println("Measured exit at ${rule.mainClock.currentTime - exitStartedAt}ms: ${transition.progress.value}")
        rule.mainClock.advanceTimeBy(160)
        rule.onNodeWithTag("single-body").assertTextEquals("LAN")
        rule.onAllNodesWithTag("single-body").assertCountEquals(1)
        rule.mainClock.advanceTimeBy(320)
        assertEquals(1f, transition.progress.value, .001f)
        assertFalse(transition.leaving)
        rule.mainClock.autoAdvance = true
    }

    @Test fun rapidTargetsCancelOldAnimationAndSettleOnLatestMode() {
        val target = mutableStateOf(false)
        lateinit var transition: NetworkModeTransition
        rule.setContent {
            transition = rememberNetworkModeTransition(target.value, true)
            Text(if (transition.displayedLan) "LAN" else "WAN", Modifier.testTag("single-body"))
        }
        rule.waitForIdle()
        rule.mainClock.autoAdvance = false
        repeat(7) {
            rule.runOnIdle {
                target.value = !target.value
                Snapshot.sendApplyNotifications()
            }
            rule.mainClock.advanceTimeBy(32)
            rule.onAllNodesWithTag("single-body").assertCountEquals(1)
        }
        rule.mainClock.advanceTimeBy(500)
        rule.onNodeWithTag("single-body").assertTextEquals("LAN")
        assertEquals(1f, transition.progress.value, .001f)
        rule.mainClock.autoAdvance = true
    }

    @Test fun reducedMotionDuringExitSettlesImmediately() {
        val target = mutableStateOf(false)
        val motion = mutableStateOf(true)
        lateinit var transition: NetworkModeTransition
        rule.setContent {
            transition = rememberNetworkModeTransition(target.value, motion.value)
            Text(if (transition.displayedLan) "LAN" else "WAN", Modifier.testTag("single-body"))
        }
        rule.waitForIdle()
        rule.mainClock.autoAdvance = false
        rule.runOnIdle {
            target.value = true
            Snapshot.sendApplyNotifications()
        }
        rule.mainClock.advanceTimeUntil(1_000) { transition.leaving }
        rule.mainClock.advanceTimeBy(32)
        assertTrue("This test must disable motion during a real exit", transition.leaving)
        rule.runOnIdle {
            motion.value = false
            Snapshot.sendApplyNotifications()
        }
        // Two composition frames plus the Android draw/layout pass, not an animation wait.
        rule.mainClock.advanceTimeBy(32)
        rule.waitForIdle()
        rule.onNodeWithTag("single-body").assertTextEquals("LAN")
        assertEquals(1f, transition.progress.value, 0f)
        assertFalse(transition.leaving)
        rule.mainClock.autoAdvance = true
    }
}
