package io.github.xgl34222220.hetu

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
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
class CompactHomeDashboardTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val calls = mutableListOf<String>()

    // Explicit test fixtures, never referenced by production code.
    private fun fixture() = CompactHomeData(
        running = true, uptimeSeconds = 45, core = "Mihomo", mode = "TPROXY",
        config = "自用_tproxy.yaml", delays = mapOf("Baidu" to 44L, "Cloudflare" to 146L, "Google" to 146L),
        wan = "203.0.113.42", lan = "192.168.1.8", countryCode = "JP", region = "测试地区",
        lanInterface = "wlan0", up = 654029, down = 3355443,
        used = 201863462666, total = 743491825336, memory = 97517568, cpu = 100f, connections = 12,
    )

    private fun render(data: CompactHomeData = fixture(), dark: Boolean = false, scale: Float = 1f, provider: (() -> CompactHomeData)? = null, motion: Boolean = false) {
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, scale)) {
                MaterialTheme(colorScheme = if (dark) darkColorScheme(background = Color(0xFF121212)) else lightColorScheme()) {
                    CompactHomeDashboard(provider?.invoke() ?: data, { calls += "refresh" }, { calls += "toggle" },
                        { calls += "reload" }, { calls += "restart" }, { calls += "delay" },
                        { calls += "webui" }, { calls += "log" }, { calls += "subscription" },
                        { calls += "connections" }, { calls += "settings" }, { calls += "diagnostics" },
                        { calls += "adblock" }, motionEnabled = motion)
                }
            }
        }
    }

    private fun snapshot(name: String) {
        rule.waitForIdle()
        val file = File("build/outputs/compact-home/$name.png")
        file.parentFile?.mkdirs()
        // Robolectric has no live Window compositor for captureToImage's forceRedraw.
        // Draw the actual measured activity view using native Skia instead of PixelCopy.
        // This still renders the production composables, not a mock drawing of the layout.
        rule.runOnIdle {
            val view = rule.activity.window.decorView
            assertTrue("Rendered view must have a real measured size", view.width > 0 && view.height > 0)
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            val pixels = IntArray(view.width * view.height)
            bitmap.getPixels(pixels, 0, view.width, 0, 0, view.width, view.height)
            assertTrue("Snapshot must contain rendered detail, not a blank canvas", pixels.toSet().size > 32)
            file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            bitmap.recycle()
        }
    }

    @Test fun actionsAreEqualWidthAndDispatchRealCallbacks() {
        render()
        val tags = listOf("home-reload", "home-toggle", "home-restart")
        val widths = tags.map { rule.onNodeWithTag(it).fetchSemanticsNode().boundsInRoot.width }
        // A one-pixel remainder cannot be split among three physical pixel widths.
        // Keep equal weights; reject any discrepancy larger than normal raster rounding.
        println("Segment physical pixel widths: $widths")
        assertTrue("Equal-weight segments may differ by at most one physical pixel: $widths",
            widths.max() - widths.min() <= 1f)
        tags.forEach { rule.onNodeWithTag(it).assertIsEnabled().performClick() }
        assertEquals(listOf("reload", "toggle", "restart"), calls)
        snapshot("light-running")
    }

    @Test fun busyDisablesAllProxyActions() {
        render(fixture().copy(busy = true, operation = HomeOperation.Reload, message = "正在重载配置"))
        listOf("home-reload", "home-toggle", "home-restart").forEach { rule.onNodeWithTag(it).assertDoesNotExist() }
        rule.onNodeWithTag("home-processing").assertIsDisplayed()
        rule.onNodeWithTag("home-processing-shimmer").assertExists()
        rule.onAllNodesWithText("正在重载").assertCountEquals(2)
        rule.onNodeWithText("正在重载配置").assertDoesNotExist()
        snapshot("processing-reload")
    }

    @Test fun stoppedOnlyEnablesStartAndDoesNotInventMetrics() {
        render(CompactHomeData(config = "未选择配置"))
        rule.onNodeWithTag("home-toggle").assertIsEnabled().performClick()
        rule.onNodeWithTag("home-reload").assertDoesNotExist()
        rule.onNodeWithTag("home-restart").assertDoesNotExist()
        rule.onNodeWithTag("home-latency-refresh").assertIsNotEnabled()
        rule.onNodeWithText("启动服务").assertIsDisplayed()
        assertEquals(listOf("toggle"), calls)
        snapshot("stopped-unknown")
    }

    @Test fun refreshStateShowsSkeletonAndDisablesDuplicateRequests() {
        render(fixture().copy(testing = true))
        rule.onAllNodesWithText("···").assertCountEquals(3)
        rule.onNodeWithTag("home-latency-refresh").assertIsNotEnabled()
        rule.onNodeWithTag("home-latency-sort").assertIsNotEnabled()
        snapshot("latency-loading")
    }

    @Test fun latencySortIsStableAndUnknownValuesStayLast() {
        val delays = mapOf("Baidu" to -1L, "Google" to 120L, "Cloudflare" to 20L)
        assertEquals(listOf("Cloudflare", "Google", "Baidu"), CompactHomeFormat.ordered(delays, true))
        assertEquals(CompactHomeFormat.sites, CompactHomeFormat.ordered(delays, false))
        render(fixture().copy(delays = delays))
        rule.onNodeWithTag("home-latency-sort").performClick()
        val cf = rule.onNodeWithTag("latency-Cloudflare").fetchSemanticsNode().boundsInRoot.left
        val google = rule.onNodeWithTag("latency-Google").fetchSemanticsNode().boundsInRoot.left
        val baidu = rule.onNodeWithTag("latency-Baidu").fetchSemanticsNode().boundsInRoot.left
        assertTrue(cf < google && google < baidu)
    }

    @Test fun wanIsDefaultAndWholeCardShowsLan() {
        render()
        rule.onNodeWithText("WAN").assertIsDisplayed()
        rule.onNodeWithTag("home-network").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("LAN").assertIsDisplayed()
        rule.onNodeWithText("192.168.1.8").assertIsDisplayed()
        rule.onNodeWithTag("home-network-details").performClick()
        rule.onNodeWithText("网络详情").assertIsDisplayed()
        rule.onNodeWithTag("native-details-sheet").assertExists()
        rule.onNodeWithTag("sheet-confirm").assertIsDisplayed()
        rule.onNodeWithTag("sheet-confirm").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("native-details-sheet").assertDoesNotExist()
    }

    @Test fun shortcutsAndOverflowKeepExistingFunctionsReachable() {
        render()
        rule.onNodeWithTag("home-webui").performClick()
        rule.onNodeWithTag("home-log").performClick()
        rule.onNodeWithContentDescription("更多首页功能").performClick()
        rule.onNodeWithText("网络诊断").performClick()
        assertEquals(listOf("webui", "log", "diagnostics"), calls)
    }

    @Test fun cpuHasBoundedProgressAndUnknownSubscriptionHasNoFakeRemainder() {
        assertNull(CompactHomeFormat.remaining(0, 0))
        assertEquals(0, CompactHomeFormat.remaining(200, 100))
        assertEquals(100, CompactHomeFormat.remaining(-1, 100))
        assertEquals("", CompactHomeFormat.flag("?"))
        assertEquals("—", CompactHomeFormat.bytes(-1))
        render(fixture().copy(cpu = 180f))
        rule.onNodeWithTag("compact-home").performScrollToNode(hasTestTag("home-cpu-bar"))
        val progress = rule.onNodeWithTag("home-cpu-bar").fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo]
        assertEquals(1f, progress.current, 0f)
        rule.onNodeWithText("180.0%").assertIsDisplayed()
        snapshot("resources-warning")
    }

    @Test fun darkThemeRendersWithoutChangingData() {
        render(dark = true)
        rule.onNodeWithText("运行中").assertIsDisplayed()
        snapshot("dark-running")
    }

    @Test fun largeTextLongConfigAndIpv6RemainScrollable() {
        render(fixture().copy(config = "这是用于验证长配置名称以及系统大字体的测试配置.yaml",
            wan = "2001:db8:1234:5678:90ab:cdef:1234:5678"), scale = 1.6f)
        rule.onNodeWithTag("home-toggle").assertIsDisplayed()
        snapshot("large-text-top")
        rule.onNodeWithTag("compact-home").performScrollToNode(hasTestTag("home-cpu-bar"))
        rule.onNodeWithTag("home-cpu-bar").assertIsDisplayed()
        snapshot("large-text-bottom")
    }

    @Test fun shellProgressDoesNotChangeOperationOrLeakIntoHero() {
        val raw = "停止守护\nKill Switch: iptables -D OUTPUT\nkill -9 1234"
        val data = fixture().copy(busy = true, operation = HomeOperation.Restart, message = raw)
        assertEquals(HomePhase.Processing, HomeLifecyclePresentation.phase(data))
        assertEquals("正在重启", HomeLifecyclePresentation.operationTitle(data))
        render(data)
        rule.onNodeWithTag("home-processing").assertIsDisplayed()
        rule.onNodeWithText(raw).assertDoesNotExist()
        rule.onNodeWithText("Kill Switch", substring = true).assertDoesNotExist()
        assertTrue("Busy hero must not expand for shell logs", rule.onNodeWithTag("home-hero").fetchSemanticsNode().boundsInRoot.height < 300f)
        snapshot("processing-restart")
    }

    @Test fun lifecycleAndFeedbackAreTruthfulWithoutInventingMetrics() {
        assertEquals(HomePhase.Stopped, HomeLifecyclePresentation.phase(CompactHomeData()))
        assertEquals(HomePhase.Running, HomeLifecyclePresentation.phase(fixture()))
        assertEquals(HomePhase.Processing, HomeLifecyclePresentation.phase(fixture().copy(operation = HomeOperation.Stop)))
        assertEquals("操作未完成，请查看详情", HomeLifecyclePresentation.feedback("Permission denied: /data/adb/hetu"))
        assertEquals("操作反馈已更新", HomeLifecyclePresentation.feedback("停止守护\niptables -D OUTPUT"))
        assertNull(HomeLifecyclePresentation.feedback(" "))
        render(fixture().copy(busy = true, operation = HomeOperation.Stop))
        rule.onNodeWithTag("home-toggle").assertDoesNotExist()
        rule.onNodeWithTag("home-processing").assertIsDisplayed()
        snapshot("processing-stop")
    }

    @Test fun fullWidthStartReplacesStoppedSegments() {
        render(CompactHomeData())
        val start = rule.onNodeWithTag("home-toggle").fetchSemanticsNode().boundsInRoot.width
        val pill = rule.onNodeWithTag("home-control-pill").fetchSemanticsNode().boundsInRoot.width
        assertTrue("Stopped start must fill its capsule", start >= pill - 13f)
        rule.onNodeWithTag("home-reload").assertDoesNotExist()
        rule.onNodeWithTag("home-restart").assertDoesNotExist()
    }

    private fun assertNetworkMode(mode: String) {
        rule.onNodeWithTag("home-network", useUnmergedTree = true)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, mode))
    }
    private fun revealGrid() {
        rule.onNodeWithTag("compact-home").performScrollToNode(hasTestTag("home-resources"))
    }
    private fun progress(tag: String): Float = rule.onNodeWithTag(tag, useUnmergedTree = true)
        .fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].current

    @Test fun wholeCardBlankSpaceTogglesExactlyOnce() {
        render()
        assertNetworkMode("WAN")
        rule.onNodeWithTag("home-network").performTouchInput { click(Offset(centerX, height - 7f)) }
        assertNetworkMode("LAN")
        rule.onNodeWithTag("home-network").performTouchInput { click(Offset(centerX, height - 7f)) }
        assertNetworkMode("WAN")
        assertTrue("Network display changes must not call proxy operations", calls.isEmpty())
    }

    @Test fun detailsPhysicalTouchDoesNotBubbleToNetworkCard() {
        render()
        repeat(2) { index ->
            val before = if (index == 0) "WAN" else "LAN"
            assertNetworkMode(before)
            rule.onNodeWithTag("home-network-details").performTouchInput { click() }
            rule.onNodeWithTag("native-details-sheet").assertIsDisplayed()
            rule.onNodeWithTag("sheet-confirm").performClick()
            rule.waitForIdle()
            assertNetworkMode(before)
            rule.onNodeWithTag("home-network").performClick()
        }
        assertTrue(calls.isEmpty())
    }

    @Test fun titleAndIpTouchesUseTheSingleParentToggle() {
        render()
        rule.onNodeWithTag("home-network-switch", useUnmergedTree = true).performTouchInput { click(Offset(12f, centerY)) }
        assertNetworkMode("LAN")
        rule.onNodeWithTag("home-network-ip", useUnmergedTree = true).performTouchInput { click() }
        assertNetworkMode("WAN")
        rule.onNodeWithTag("native-details-sheet").assertDoesNotExist()
        assertTrue(calls.isEmpty())
    }

    @Test fun horizontalSwipesKeepDirectionAndDoNotOpenDetails() {
        render()
        rule.onNodeWithTag("home-network").performTouchInput { swipeLeft() }
        assertNetworkMode("LAN")
        rule.onNodeWithTag("home-network").performTouchInput { swipeRight() }
        assertNetworkMode("WAN")
        rule.onNodeWithTag("native-details-sheet").assertDoesNotExist()
        assertTrue(calls.isEmpty())
    }

    @Test fun verticalScrollAcrossNetworkDoesNotToggleOrOpenDetails() {
        render()
        rule.onNodeWithTag("home-network").performTouchInput { swipeUp() }
        assertNetworkMode("WAN")
        rule.onNodeWithTag("native-details-sheet").assertDoesNotExist()
        assertTrue(calls.isEmpty())
    }

    @Test fun subscriptionBarUsesActualUsageAndUpdatesWithItsInput() {
        val live = mutableStateOf(fixture().copy(used = 25L, total = 100L))
        render(provider = { live.value })
        revealGrid()
        assertEquals(.25f, progress("home-subscription-bar"), .0001f)
        rule.onNodeWithText("剩余 75%", useUnmergedTree = true).assertIsDisplayed()
        rule.runOnIdle { live.value = live.value.copy(used = 60L) }
        assertEquals(.60f, progress("home-subscription-bar"), .0001f)
        rule.onNodeWithText("剩余 40%", useUnmergedTree = true).assertIsDisplayed()
        snapshot("grid-usage-rebound")
    }

    @Test fun unknownAndOverQuotaSubscriptionsHaveTruthfulBoundedTracks() {
        assertNull(CompactHomeFormat.usedFraction(12L, 0L))
        assertNull(CompactHomeFormat.usedFraction(12L, -1L))
        assertEquals(0f, CompactHomeFormat.usedFraction(-1L, 100L)!!, 0f)
        assertEquals(1f, CompactHomeFormat.usedFraction(Long.MAX_VALUE, 1L)!!, 0f)
        val live = mutableStateOf(fixture().copy(total = 0L, cpu = Float.NaN))
        render(provider = { live.value })
        revealGrid()
        for (tag in listOf("home-subscription-bar", "home-cpu-bar")) {
            val config = rule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().config
            assertFalse("Unknown data is not a real zero-percent measurement", config.contains(SemanticsProperties.ProgressBarRangeInfo))
        }
        rule.onNodeWithText("剩余", substring = true).assertDoesNotExist()
        snapshot("grid-unknown")
        rule.runOnIdle { live.value = live.value.copy(used = 200L, total = 100L) }
        assertEquals(1f, progress("home-subscription-bar"), 0f)
        rule.onNodeWithText("剩余 0%", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test fun compactGridPairsAndBottomBarsAlignAtFourteenDpInsets() {
        render()
        revealGrid()
        val sub = rule.onNodeWithTag("home-subscription").fetchSemanticsNode().boundsInRoot
        val resource = rule.onNodeWithTag("home-resources").fetchSemanticsNode().boundsInRoot
        val subBar = rule.onNodeWithTag("home-subscription-bar", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val cpuBar = rule.onNodeWithTag("home-cpu-bar", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertEquals(sub.height, resource.height, 1f)
        assertEquals(sub.top, resource.top, 1f)
        assertEquals(1f, resource.left - sub.right, 1f)
        assertEquals(14f, subBar.left - sub.left, 1f)
        assertEquals(14f, sub.right - subBar.right, 1f)
        assertEquals(subBar.bottom, cpuBar.bottom, 1f)
        assertEquals(subBar.width, cpuBar.width, 1f)
        rule.onNodeWithTag("home-subscription-bar", useUnmergedTree = true).assertHeightIsEqualTo(6.dp)
        rule.onNodeWithTag("home-cpu-bar", useUnmergedTree = true).assertHeightIsEqualTo(6.dp)
        snapshot("grid-aligned")
    }

    @Test fun metricTextIsSixteenSpWithSmallerSeparateUnits() {
        render()
        revealGrid()
        for (tag in listOf("home-value-上行", "home-value-下行", "home-value-已用", "home-value-总量", "home-value-内存")) {
            val layouts = mutableListOf<TextLayoutResult>()
            rule.onNodeWithTag(tag, useUnmergedTree = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertTrue(layouts.isNotEmpty())
            assertEquals(16.sp, layouts.first().layoutInput.style.fontSize)
            assertTrue("Units must remain smaller than data", layouts.first().layoutInput.text.spanStyles.any { it.item.fontSize == 11.sp })
        }
    }

    @Test fun largeFontRetainsFullIpv6InDetailsAndUsesOneColumn() {
        val ip = "2001:db8:1234:5678:90ab:cdef:1234:5678"
        render(fixture().copy(wan = ip, region = "测试用的很长的地区名称"), scale = 1.6f)
        rule.onNodeWithTag("compact-home").performScrollToNode(hasTestTag("home-network"))
        rule.onNodeWithTag("home-network-details").performTouchInput { click() }
        rule.onNodeWithText("IPv6").assertIsDisplayed()
        rule.onAllNodesWithText(ip, useUnmergedTree = true).onLast().assertIsDisplayed()
        snapshot("network-details-large-font")
        rule.onNodeWithTag("sheet-confirm").performClick()
        rule.waitForIdle()
        revealGrid()
        rule.onNodeWithTag("home-cpu-bar", useUnmergedTree = true).assertIsDisplayed()
        snapshot("grid-large-font")
    }


    private fun textLayout(tag: String): TextLayoutResult {
        val result = mutableListOf<TextLayoutResult>()
        rule.onNodeWithTag(tag, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(result) }
        return result.single()
    }

    @Test fun ui7BrandStartsLeadingAndRefreshStillWorks() {
        render()
        val header = rule.onNodeWithTag("home-header").fetchSemanticsNode().boundsInRoot
        val brand = rule.onNodeWithTag("home-brand").fetchSemanticsNode().boundsInRoot
        assertEquals(header.left + 16f, brand.left, 1f)
        assertEquals(28.sp, textLayout("home-brand").layoutInput.style.fontSize)
        rule.onNodeWithText("BoxProxy").assertDoesNotExist()
        rule.onNodeWithContentDescription("刷新状态").performClick()
        assertEquals(listOf("refresh"), calls)
        snapshot("ui4-centered-header")
    }

    @Test fun ui4HeroTypographyAndGlyphAreLargerWithoutFakeUptime() {
        render()
        assertEquals(22.sp, textLayout("hero-status-title").layoutInput.style.fontSize)
        assertEquals(15.sp, textLayout("hero-uptime").layoutInput.style.fontSize)
        assertEquals(13.sp, textLayout("hero-core").layoutInput.style.fontSize)
        assertEquals(15.sp, textLayout("hero-config").layoutInput.style.fontSize)
        rule.onNodeWithTag("hero-status-glyph").assertWidthIsEqualTo(84.dp).assertHeightIsEqualTo(84.dp)
        rule.onNodeWithText("少于 1 分钟").assertIsDisplayed()
        rule.onNodeWithText("7 小时 37 分钟").assertDoesNotExist()
    }

    @Test fun ui4LongestIpv4IsCompleteInTheCard() {
        val ip = "255.255.255.255"
        render(fixture().copy(wan = ip))
        rule.onNodeWithTag("compact-home").performScrollToNode(hasTestTag("home-network"))
        val result = textLayout("home-network-ip")
        assertEquals(ip, result.layoutInput.text.text)
        assertFalse(result.hasVisualOverflow)
        assertEquals(ip.length, result.getLineEnd(result.lineCount - 1))
        assertTrue((0 until result.lineCount).none { result.isLineEllipsized(it) })
        snapshot("ui4-ipv4-complete")
    }

    @Test @Config(qualifiers = "w320dp-h900dp-mdpi")
    fun ui4Ipv6AndLargeFontsNeverClipCardAddress() {
        val ip = "2001:db8:1234:5678:90ab:cdef:1234:5678"
        render(fixture().copy(wan = ip), scale = 1.6f)
        rule.onNodeWithTag("compact-home").performScrollToNode(hasTestTag("home-network"))
        val result = textLayout("home-network-ip")
        assertEquals(16.sp, result.layoutInput.style.fontSize)
        assertEquals(ip, result.layoutInput.text.text)
        assertFalse(result.hasVisualOverflow)
        assertEquals(ip.length, result.getLineEnd(result.lineCount - 1))
        snapshot("ui4-narrow-ipv6")
    }

    @Test fun ui4TimeoutIsABadgeForAnyConfiguredTarget() {
        render(fixture().copy(delays = mapOf("Baidu" to -1L, "Cloudflare" to 0L, "Google" to 81L)))
        rule.onNodeWithTag("latency-badge-Baidu").assertTextEquals("超时")
        rule.onNodeWithTag("latency-badge-Cloudflare").assertTextEquals("失败")
        rule.onNodeWithText("81 ms").assertIsDisplayed()
        snapshot("ui4-latency-badges")
    }

    @Test fun ui4RapidModeChangesHaveOnlyOneIpLayerDuringAnimation() {
        render(motion = true)
        rule.mainClock.autoAdvance = false
        repeat(5) { index ->
            rule.onNodeWithTag("home-network").performClick()
            rule.mainClock.advanceTimeBy(32)
            rule.onAllNodesWithTag("home-network-body", useUnmergedTree = true).assertCountEquals(1)
            rule.onAllNodesWithTag("home-network-ip", useUnmergedTree = true).assertCountEquals(1)
            assertNetworkMode(if (index % 2 == 0) "LAN" else "WAN")
        }
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()
        assertTrue(calls.isEmpty())
    }

}
