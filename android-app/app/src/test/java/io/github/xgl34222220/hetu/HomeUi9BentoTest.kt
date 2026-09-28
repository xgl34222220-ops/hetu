package io.github.xgl34222220.hetu

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.*
import io.github.xgl34222220.hetu.ui.LocalHetuMotionEnabled
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w393dp-h852dp-mdpi", application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HomeUi9BentoTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val calls = mutableListOf<String>()
    // Deliberate fixture data, never included in the production adapter.
    private fun fixture() = CompactHomeData(running = true, uptimeSeconds = 39480, core = "Mihomo", mode = "TPROXY",
        config = "fixture.yaml", wan = "123.193.18.254", lan = "192.168.2.85", countryCode = "JP",
        region = "测试地区", lanInterface = "wlan0", up = 2200, down = 14,
        used = 1962, total = 6924, memory = 74100000, cpu = 4.7f,
        delays = mapOf("Baidu" to 48L, "Cloudflare" to 207L, "Google" to 107L))
    private fun render(data: MutableState<CompactHomeData> = mutableStateOf(fixture()),
        scale: Float = 1f, height: Dp? = null, motion: Boolean = false) {
        rule.setContent {
            val d = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(d.density, scale), LocalHetuMotionEnabled provides motion) {
                MaterialTheme {
                    Box(if (height == null) Modifier.fillMaxSize() else Modifier.fillMaxWidth().height(height)) {
                        CompactHomeDashboard(data.value, { calls += "refresh" }, { calls += "toggle" },
                            { calls += "reload" }, { calls += "restart" }, { calls += "delay" },
                            { calls += "webui" }, { calls += "log" }, { calls += "subscription" },
                            { calls += "connections" }, { calls += "settings" }, { calls += "diagnostics" },
                            { calls += "adblock" }, motionEnabled = motion,
                            contentInsets = WindowInsets(0.dp, 44.dp, 0.dp, 24.dp))
                    }
                }
            }
        }
    }
    private fun node(tag: String) = rule.onNodeWithTag(tag, useUnmergedTree = true)
    private fun bounds(tag: String) = node(tag).getUnclippedBoundsInRoot()
    private fun show(tag: String) = node("compact-home").performScrollToNode(hasTestTag(tag))
    private fun text(tag: String): TextLayoutResult {
        val result = mutableListOf<TextLayoutResult>()
        node(tag).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(result) }
        return result.single()
    }
    private fun assertPair(group: String, left: String, right: String) {
        node(left).assert(hasAnyAncestor(hasTestTag(group)))
        node(right).assert(hasAnyAncestor(hasTestTag(group)))
        val a = bounds(left); val b = bounds(right); val whole = bounds(group)
        assertEquals(a.top.value, b.top.value, 1f)
        assertEquals(a.bottom.value, b.bottom.value, 1f)
        assertEquals(a.width.value, b.width.value, 1f)
        assertTrue(a.right <= b.left)
        assertTrue(a.width < whole.width * .51f)
        node("$group-divider").assertDoesNotExist()
        assertEquals(10f, (b.left - a.right).value, 1f)
    }
    private fun assertNoticeSeparated() {
        val p = bounds("home-feedback-pill")
        val lane = bounds("home-notice-lane")
        val header = bounds("home-header")
        assertTrue(p.top >= 44.dp)
        assertTrue(p.bottom <= lane.bottom)
        assertTrue(lane.bottom <= header.top)
        assertTrue(bounds("compact-home").top >= header.bottom)
    }
    private fun snapshot(name: String) {
        rule.runOnIdle {
            val v = rule.activity.window.decorView
            val bitmap = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
            v.draw(Canvas(bitmap))
            val file = File("build/outputs/compact-home/$name.png")
            file.parentFile!!.mkdirs()
            file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            bitmap.recycle()
        }
    }
    @Test fun fourMetricsUseExactlyTwoSharedSideBySideSurfaces() {
        render()
        assertPair("home-network-group", "home-network", "home-speed")
        assertPair("home-health-group", "home-subscription", "home-resources")
        assertEquals(10f, (bounds("home-health-group").top - bounds("home-network-group").bottom).value, 1f)
    }
    @Test fun widestIpv4CannotTurnAllMetricsIntoAFullWidthStack() {
        render(mutableStateOf(fixture().copy(wan = "255.255.255.255")))
        assertPair("home-network-group", "home-network", "home-speed")
        assertPair("home-health-group", "home-subscription", "home-resources")
        assertEquals("255.255.255.255", text("home-network-ip").layoutInput.text.text)
        assertEquals(1, text("home-network-ip").lineCount)
        assertFalse(text("home-network-ip").hasVisualOverflow)
    }
    @Test fun liveAddressChangesDoNotReflowPairsOrResetSelectedLan() {
        val data = mutableStateOf(fixture()); render(data)
        val before = bounds("home-network")
        node("home-network").performClick()
        rule.runOnIdle { data.value = data.value.copy(wan = "255.255.255.255") }
        assertEquals(before, bounds("home-network"))
        node("home-network").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "LAN"))
        assertEquals("192.168.2.85", text("home-network-ip").layoutInput.text.text)
    }
    @Test fun physicalBlankAreaTapChangesOnlyNetworkMode() {
        render()
        node("home-network").performTouchInput { click(Offset(12f, height - 8f)) }
        node("home-network").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "LAN"))
        assertTrue(calls.isEmpty())
    }
    @Test fun detailTapStillDoesNotBubbleToTheLeftHalf() {
        render()
        node("home-network-details").performTouchInput { click() }
        node("native-details-sheet").assertIsDisplayed()
        node("sheet-confirm").performClick()
        node("home-network").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "WAN"))
        assertTrue(calls.isEmpty())
    }
    @Test fun pairedQuotaAndSpeedKeepTheirRealIndependentCallbacks() {
        render()
        node("home-speed").performTouchInput { click() }
        node("home-subscription").performTouchInput { click() }
        assertEquals(listOf("connections", "subscription"), calls)
        node("home-network").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "WAN"))
    }
    @Test fun normalPhoneShowsBothProgressTracksBeforeAnyScrolling() {
        render()
        node("home-cpu-bar").assertIsDisplayed()
        node("home-subscription-bar").assertIsDisplayed()
        assertTrue("Full health surface ${bounds("home-health-group")} / viewport ${bounds("compact-home")}", bounds("home-health-group").bottom <= bounds("compact-home").bottom)
        assertTrue(bounds("home-toggle").top >= bounds("home-header").bottom)
        snapshot("ui9-bento-first-screen")
    }
    @Test @Config(qualifiers = "w393dp-h852dp-xxhdpi") fun screenDensityDoesNotChangeTheTwoColumnDecision() {
        render()
        assertPair("home-network-group", "home-network", "home-speed")
        assertPair("home-health-group", "home-subscription", "home-resources")
        assertFalse(text("home-network-ip").hasVisualOverflow)
    }
    @Test @Config(qualifiers = "w320dp-h640dp-mdpi") fun accessibilityStacksLabelsInsideHalvesNotFourWholeCards() {
        render(scale = 1.6f)
        show("home-network")
        assertPair("home-network-group", "home-network", "home-speed")
        assertEquals(1, text("home-network-ip").lineCount)
        node("home-network-ip").performTouchInput { swipeLeft() }
        node("home-network").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "WAN"))
        show("home-health-group")
        assertPair("home-health-group", "home-subscription", "home-resources")
        node("home-cpu-bar").assertIsDisplayed()
        for (tag in listOf("home-value-已用", "home-value-总量", "home-value-内存", "home-value-CPU")) {
            val layout = text(tag)
            assertFalse("$tag size=${layout.size} paragraph=${layout.multiParagraph.width}x${layout.multiParagraph.height} constraints=${layout.layoutInput.constraints}", layout.hasVisualOverflow)
        }
    }
    @Test fun notificationIsAboveHeaderAndCannotInterceptStop() {
        render(mutableStateOf(fixture().copy(message = "全部刷新完成")))
        assertNoticeSeparated()
        node("home-toggle").performTouchInput { click() }
        assertEquals(listOf("toggle"), calls)
        node("native-details-sheet").assertDoesNotExist()
        snapshot("ui9-notification-safe-stop")
    }
    @Test @Config(qualifiers = "w320dp-h640dp-mdpi") fun longLargeFontNotificationHasItsOwnMeasuredLane() {
        render(mutableStateOf(fixture().copy(message = "网络状态同步完成，当前连接信息和应用流量已经更新")), scale = 1.6f)
        assertNoticeSeparated()
        node("home-toggle").assertIsDisplayed().performTouchInput { click() }
        assertEquals(listOf("toggle"), calls)
    }
    @Test fun arrivingNotificationDoesNotOverlayScrolledListOrHeaderActions() {
        val data = mutableStateOf(fixture()); render(data, height = 540.dp)
        node("compact-home").performTouchInput { swipeUp() }
        rule.runOnIdle { data.value = data.value.copy(message = "全部刷新完成") }
        assertNoticeSeparated()
        rule.onNodeWithContentDescription("刷新状态").performTouchInput { click() }
        assertEquals(listOf("refresh"), calls)
    }
    @Test fun errorCapsuleStillOpensTheUnmodifiedDiagnosticText() {
        val raw = "授权失败\nERROR: fixture original details"
        render(mutableStateOf(fixture().copy(message = raw)))
        assertNoticeSeparated()
        node("home-feedback-pill").performTouchInput { click() }
        node("native-details-sheet").assertIsDisplayed()
        rule.onNodeWithText(raw).assertIsDisplayed()
        assertTrue(calls.isEmpty())
    }
    @Test fun transferDotsReflectActualTrafficAndStoppedState() {
        val data = mutableStateOf(fixture().copy(up = 0)); render(data)
        node("home-traffic-dot-上行").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "无传输"))
        node("home-traffic-dot-下行").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "有传输"))
        rule.runOnIdle { data.value = data.value.copy(running = false) }
        node("home-traffic-dot-下行").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "无传输"))
    }
    @Test fun pairedTracksRepresentRealQuotaAndFourPointSevenPercentCpu() {
        render()
        val cpu = node("home-cpu-bar").fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo]
        val quota = node("home-subscription-bar").fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo]
        assertEquals(.047f, cpu.current, .0001f)
        assertEquals(1962f / 6924f, quota.current, .0001f)
        assertEquals(bounds("home-cpu-bar").top.value, bounds("home-subscription-bar").top.value, 1f)
    }
    @Test fun unknownQuotaDoesNotBecomeTheMockSeventyOnePercent() {
        render(mutableStateOf(fixture().copy(used = 0, total = 0, cpu = Float.NaN)))
        val q = node("home-subscription-bar").fetchSemanticsNode().config
        val c = node("home-cpu-bar").fetchSemanticsNode().config
        assertFalse(q.contains(SemanticsProperties.ProgressBarRangeInfo))
        assertFalse(c.contains(SemanticsProperties.ProgressBarRangeInfo))
        rule.onNodeWithText("余 71%").assertDoesNotExist()
    }
    @Test fun processingClearsNoticeAndKeepsRealLifecycleControls() {
        val data = mutableStateOf(fixture().copy(message = "全部刷新完成")); render(data)
        node("home-feedback-pill").assertIsDisplayed()
        rule.runOnIdle { data.value = data.value.copy(busy = true, operation = HomeOperation.Stop) }
        node("home-feedback-pill").assertDoesNotExist()
        node("home-toggle").assertDoesNotExist()
        node("home-processing").assertIsDisplayed()
    }
    @Test fun animatedLaneNeverPlacesThePillBelowTheHeader() {
        val data = mutableStateOf(fixture().copy(up = 0, down = 0)); render(data, motion = true)
        rule.mainClock.autoAdvance = false
        rule.runOnIdle { data.value = data.value.copy(message = "全部刷新完成") }
        repeat(35) {
            rule.mainClock.advanceTimeByFrame(); rule.waitForIdle()
            val lane = node("home-notice-lane").fetchSemanticsNode().boundsInRoot
            val header = node("home-header").fetchSemanticsNode().boundsInRoot
            assertTrue(lane.bottom <= header.top + 1f)
            val pills = rule.onAllNodesWithTag("home-feedback-pill", useUnmergedTree = true).fetchSemanticsNodes()
            pills.forEach { p -> assertTrue(p.boundsInRoot.bottom <= header.top + 1f) }
        }
        rule.mainClock.autoAdvance = true
        assertNoticeSeparated()
    }
}
