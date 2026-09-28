package io.github.xgl34222220.hetu

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w393dp-h900dp-mdpi", application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HomeUi8ExperienceTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val calls = mutableListOf<String>()
    private fun fixture() = CompactHomeData(running = true, core = "Mihomo", mode = "TPROXY",
        config = "fixture.yaml", wan = "123.193.18.254", lan = "192.168.2.85", region = "测试地区",
        countryCode = "JP", lanInterface = "wlan0", up = 1200, down = 864000, used = 25, total = 100,
        memory = 90000000, cpu = 3.5f, delays = mapOf("Baidu" to 44L, "Cloudflare" to 312L, "Google" to 114L))
    private fun render(data: MutableState<CompactHomeData> = mutableStateOf(fixture()),
        scale: Float = 1f, height: Dp? = null) {
        rule.setContent {
            val d = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(d.density, scale), LocalHetuMotionEnabled provides false) {
                MaterialTheme {
                    Box(if (height == null) Modifier.fillMaxSize() else Modifier.fillMaxWidth().height(height)) {
                        CompactHomeDashboard(data.value, { calls += "refresh" }, { calls += "toggle" },
                            { calls += "reload" }, { calls += "restart" }, { calls += "delay" },
                            { calls += "webui" }, { calls += "log" }, { calls += "subscription" },
                            { calls += "connections" }, { calls += "settings" }, { calls += "diagnostics" },
                            { calls += "adblock" }, motionEnabled = false,
                            contentInsets = WindowInsets(0.dp, 24.dp, 0.dp, 24.dp),
                            onPullRefresh = { calls += "pull"; data.value = data.value.copy(refreshing = true) })
                    }
                }
            }
        }
    }
    private fun node(tag: String) = rule.onNodeWithTag(tag, useUnmergedTree = true)
    private fun text(tag: String): TextLayoutResult {
        val out = mutableListOf<TextLayoutResult>()
        node(tag).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(out) }
        val result = out.single()
        if (tag == "home-network-ip") println("ADDRESS_LAYOUT size=${result.size} paragraph=${result.multiParagraph.width}x${result.multiParagraph.height} constraints=${result.layoutInput.constraints} widthOverflow=${result.didOverflowWidth} heightOverflow=${result.didOverflowHeight} exceeded=${result.multiParagraph.didExceedMaxLines} baseline=${result.firstBaseline} lineEnd=${result.getLineEnd(0)}")
        return result
    }
    private fun pullOffset(): Float = node("home-pull-indicator").fetchSemanticsNode().config[HomePullOffset]
    private fun longPull() = node("compact-home").performTouchInput {
        swipe(Offset(center.x, 25f), Offset(center.x, 350f), 700)
    }
    private fun showNetwork() = node("compact-home").performScrollToNode(hasTestTag("home-network"))

    @Test fun reportedIpv4HasAllDigitsOnOneLineWithoutClipping() {
        render(); showNetwork()
        val l = text("home-network-ip")
        assertEquals("123.193.18.254", l.layoutInput.text.text)
        assertEquals(1, l.lineCount); assertFalse(l.hasVisualOverflow)
        assertFalse(l.layoutInput.softWrap); assertEquals(1, l.layoutInput.maxLines)
        assertEquals(14.sp, l.layoutInput.style.fontSize)
        val ip = node("home-network-ip").getUnclippedBoundsInRoot()
        val card = node("home-network").getUnclippedBoundsInRoot()
        assertTrue(ip.right <= card.right - 12.dp) // UI10 explicitly uses 12dp inner padding.
    }
    @Test @Config(qualifiers = "w320dp-h640dp-mdpi") fun narrowLargeTextKeepsBentoHalvesAndScrollableAddress() {
        render(scale = 1.3f); showNetwork()
        val l = text("home-network-ip")
        assertEquals("123.193.18.254", l.layoutInput.text.text)
        assertEquals(1, l.lineCount); assertFalse(l.hasVisualOverflow)
        assertTrue(node("home-network").getUnclippedBoundsInRoot().width <
            node("home-telemetry-grid").getUnclippedBoundsInRoot().width * .6f)
        node("home-network-ip").performTouchInput { swipeLeft() }
        node("home-network").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "WAN"))
    }
    @Test @Config(qualifiers = "w393dp-h852dp-xxhdpi") fun highDensityDoesNotChangeAddressWrapping() {
        render(); showNetwork()
        val l = text("home-network-ip")
        assertEquals(1, l.lineCount); assertFalse(l.hasVisualOverflow)
        assertEquals("123.193.18.254", l.layoutInput.text.text)
    }
    @Test fun horizontalIpv6ScrollDoesNotToggleWanLan() {
        val ip = "2001:db8:1234:5678:90ab:cdef:1234:5678"
        render(mutableStateOf(fixture().copy(wan = ip))); showNetwork()
        assertEquals(1, text("home-network-ip").lineCount)
        node("home-network-ip").performTouchInput { swipeLeft() }
        node("home-network").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "WAN"))
        val scroll = node("home-network-ip").fetchSemanticsNode().config[SemanticsProperties.HorizontalScrollAxisRange]
        assertTrue("Long address can actually be scrolled", scroll.value() > 0f)
        assertEquals(ip, text("home-network-ip").layoutInput.text.text)
    }
    @Test fun detailsKeepTheFullAddressAndDoNotToggleNetwork() {
        val ip = "2001:db8:1234:5678:90ab:cdef:1234:5678"
        render(mutableStateOf(fixture().copy(wan = ip))); showNetwork()
        node("home-network-details").performTouchInput { click() }
        rule.onNode(hasText(ip) and hasAnyAncestor(hasTestTag("native-details-sheet")), useUnmergedTree = true).assertIsDisplayed()
        node("sheet-confirm").performClick()
        node("home-network").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "WAN"))
    }
    @Test fun rubberBandIsNonlinearMonotonicAndBounded() {
        val a = homeRubberBand(50f); val b = homeRubberBand(100f); val c = homeRubberBand(150f)
        assertEquals(0f, homeRubberBand(0f), 0f)
        assertEquals(0f, homeRubberBand(-100f), 0f)
        assertTrue(a < b && b < c)
        assertTrue(a > b - a && b - a > c - b)
        assertTrue(homeRubberBand(100000f) < 110f)
    }
    @Test fun longPullDispatchesOneRealRequestAndDoesNotDuplicateWhileRefreshing() {
        val data = mutableStateOf(fixture()); render(data)
        longPull(); rule.waitForIdle()
        assertEquals(listOf("pull"), calls)
        assertTrue(data.value.refreshing)
        node("home-pull-indicator").assert(SemanticsMatcher.expectValue(HomePullActive, true))
        longPull(); rule.waitForIdle()
        assertEquals(listOf("pull"), calls)
    }
    @Test fun shortPullReturnsWithoutDispatchingRefresh() {
        render()
        node("compact-home").performTouchInput { swipe(Offset(center.x, 25f), Offset(center.x, 65f), 800) }
        assertTrue(calls.isEmpty())
        assertEquals(0f, pullOffset(), .01f)
    }
    @Test fun reversingBeforeReleaseCancelsAnArmedPull() {
        render()
        node("compact-home").performTouchInput {
            down(Offset(center.x, 30f))
            moveTo(Offset(center.x, 340f), 300)
            moveTo(Offset(center.x, 45f), 600)
            up()
        }
        assertTrue(calls.isEmpty()); assertEquals(0f, pullOffset(), .01f)
    }
    @Test fun indicatorWaitsForActualCompletionNotASimulatedTimer() {
        val data = mutableStateOf(fixture().copy(refreshing = true)); render(data)
        assertEquals(46f, pullOffset(), .01f)
        rule.mainClock.advanceTimeBy(3000)
        node("home-pull-indicator").assert(SemanticsMatcher.expectValue(HomePullActive, true))
        rule.runOnIdle { data.value = data.value.copy(refreshing = false) }
        assertEquals(0f, pullOffset(), .01f)
        assertTrue(calls.isEmpty())
    }
    @Test fun normalDownwardScrollAwayFromTopIsNotRefresh() {
        render(height = 480.dp)
        node("compact-home").performScrollToIndex(3)
        node("compact-home").performTouchInput { swipe(Offset(center.x, 30f), Offset(center.x, 95f), 900) }
        assertTrue(calls.isEmpty()); assertEquals(0f, pullOffset(), .01f)
    }
    @Test fun thirtyOnePixelsCollapseOnePinnedTitleWithoutLosingActions() {
        render(height = 480.dp)
        val before = node("home-header").getUnclippedBoundsInRoot()
        node("compact-home").performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 31f) }
        rule.waitForIdle()
        node("home-header").assert(SemanticsMatcher.expectValue(HomeHeaderCollapse, 1f))
        assertEquals(before, node("home-header").getUnclippedBoundsInRoot())
        assertEquals(20.sp, text("home-brand").layoutInput.style.fontSize)
        rule.onAllNodesWithTag("home-brand").assertCountEquals(1)
        rule.onNodeWithContentDescription("刷新状态").assertDoesNotExist()
        rule.onNodeWithContentDescription("更多首页功能").assertDoesNotExist()
        node("compact-home").performScrollToIndex(0)
        assertEquals(20.sp, text("home-brand").layoutInput.style.fontSize)
    }
    @Test fun successFeedbackIsAboveHeaderAndOutsideTheContent() {
        render(mutableStateOf(fixture().copy(message = "全部刷新完成")))
        node("home-feedback-pill").assertIsDisplayed()
        val p = node("home-feedback-pill").getUnclippedBoundsInRoot()
        val header = node("home-header").getUnclippedBoundsInRoot()
        assertTrue(p.top >= 24.dp)
        assertTrue(p.bottom <= header.top - 7.dp)
        assertTrue(p.bottom < 180.dp)
        assertTrue(p.width <= 321.dp)
        rule.onNodeWithText("全部刷新完成").assertIsDisplayed()
    }
    @Test fun failedFeedbackNeverClaimsSuccessAndPreservesRawDetails() {
        val raw = "授权失败\nERROR: full diagnostic fixture"
        assertEquals(HomeNoticeKind.Error, homeNotice(raw, 1)?.kind)
        render(mutableStateOf(fixture().copy(message = raw)))
        rule.onNodeWithText("操作未完成，请查看详情").assertIsDisplayed()
        node("home-feedback-pill").performClick()
        node("native-details-sheet").assertIsDisplayed()
        rule.onNodeWithText(raw).assertIsDisplayed()
        assertEquals(HomeNoticeKind.Information, homeNotice("状态读取完成（无结果）", 2)?.kind)
    }
    @Test fun feedbackWaitsUntilTheRealRefreshFlagClears() {
        val data = mutableStateOf(fixture().copy(refreshing = true, message = "全部刷新完成")); render(data)
        node("home-feedback-pill").assertDoesNotExist()
        rule.runOnIdle { data.value = data.value.copy(refreshing = false) }
        node("home-feedback-pill").assertIsDisplayed()
        rule.runOnIdle { data.value = data.value.copy(busy = true, operation = HomeOperation.Stop) }
        node("home-feedback-pill").assertDoesNotExist()
    }
    @Test fun realPressScalesToPoint97ThenSpringsBack() {
        rule.setContent { MaterialTheme {
            // Locate inside the graphics layer so its transformed bounds are measured.
            Box(Modifier.size(100.dp).nativePress(motion = true) { calls += "tap" }.testTag("press-target"))
        } }
        val initial = node("press-target").fetchSemanticsNode().boundsInRoot.width
        rule.mainClock.autoAdvance = false
        node("press-target").performTouchInput { down(center) }
        repeat(18) { rule.mainClock.advanceTimeByFrame(); rule.waitForIdle() }
        val pressed = node("press-target").fetchSemanticsNode().boundsInRoot.width
        assertEquals(initial * .97f, pressed, 1f)
        node("press-target").performTouchInput { up() }
        repeat(60) { rule.mainClock.advanceTimeByFrame(); rule.waitForIdle() }
        assertEquals(initial, node("press-target").fetchSemanticsNode().boundsInRoot.width, 1f)
        assertEquals(listOf("tap"), calls)
        rule.mainClock.autoAdvance = true
    }
    @Test fun busyServiceCannotDispatchPullRefresh() {
        render(mutableStateOf(fixture().copy(busy = true, operation = HomeOperation.Restart)))
        longPull(); assertTrue(calls.isEmpty()); assertEquals(0f, pullOffset(), .01f)
    }
    @Test fun pullMovesContentDownButNeverTheSafeHeaderUp() {
        render()
        val before = node("home-header").getUnclippedBoundsInRoot()
        node("compact-home").performTouchInput {
            down(Offset(center.x, 25f)); moveTo(Offset(center.x, 340f), 600)
        }
        assertEquals(before, node("home-header").getUnclippedBoundsInRoot())
        assertTrue(pullOffset() > 65f)
        assertTrue(node("home-hero").getUnclippedBoundsInRoot().top >= before.bottom)
        node("compact-home").performTouchInput { up() }
        assertEquals(listOf("pull"), calls)
    }
}
