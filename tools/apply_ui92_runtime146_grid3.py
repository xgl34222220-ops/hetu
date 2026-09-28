#!/usr/bin/env python3
"""One-time, checked UI2 -> UI3 migration. Never import or alter runtime146 code."""
from pathlib import Path
import hashlib
import json
import subprocess

BASE = 'd24383422897cfa29f267811e33e7e51ad80bae5'
ROOT = Path(__file__).resolve().parents[1]
MAIN = ROOT / 'android-app/app/src/main/java/io/github/xgl34222220/hetu'
TEST = ROOT / 'android-app/app/src/test/java/io/github/xgl34222220/hetu/CompactHomeDashboardTest.kt'
BUILD = ROOT / 'android-app/app/build.gradle.kts'
HOME = MAIN / 'CompactHomeDashboard.kt'

def blob(data):
    return hashlib.sha1(b'blob ' + str(len(data)).encode() + b'\0' + data).hexdigest()

def replace_once(text, old, new):
    if text.count(old) != 1:
        raise RuntimeError('Expected one exact migration anchor: ' + old[:100])
    return text.replace(old, new, 1)

subprocess.run(['git', 'merge-base', '--is-ancestor', BASE, 'HEAD'], cwd=ROOT, check=True)
assert blob(HOME.read_bytes()) == '7aa2b9a70aea67b13e071bac251db39aca1415c1', 'UI2 home changed; review instead of overwriting'
assert blob(TEST.read_bytes()) == '68f9179cb9700df4236dd5f7cdc46774a86b48eb', 'UI2 tests changed; review instead of overwriting'
text = HOME.read_text()
text = text.replace('import androidx.compose.foundation.pager.HorizontalPager\n', '')
text = text.replace('import androidx.compose.foundation.pager.rememberPagerState\n', '')
text = replace_once(text, 'import androidx.compose.foundation.*\n', 'import androidx.compose.foundation.*\nimport androidx.compose.foundation.gestures.Orientation\nimport androidx.compose.foundation.gestures.draggable\nimport androidx.compose.foundation.gestures.rememberDraggableState\n')
text = replace_once(text, '    fun bytes(value: Long): String {', '''    /** Unknown totals have no determinate progress; ratios never overflow Long arithmetic. */
    fun usedFraction(used: Long, total: Long): Float? = if (total <= 0L) null else
        (used.coerceAtLeast(0L).toDouble() / total.toDouble()).coerceIn(0.0, 1.0).toFloat()
    fun bytes(value: Long): String {''')
text = replace_once(text, '''    enabled: Boolean = true, content: @Composable BoxScope.() -> Unit) {
    val p = LocalHomePalette.current
    val shape = RoundedCornerShape(24.dp)
    val interactive = if (onClick == null) modifier else modifier.homeClick(enabled, onClick = onClick)
    Box(interactive.diffuseCardShadow(shape).clip(shape).background(p.card), content = content)''', '''    enabled: Boolean = true, clickLabel: String? = null, content: @Composable BoxScope.() -> Unit) {
    val p = LocalHomePalette.current
    val shape = RoundedCornerShape(20.dp)
    val interactive = if (onClick == null) modifier else modifier.homeClick(enabled, clickLabel, onClick)
    Box(interactive.diffuseCardShadow(shape).clip(shape).background(p.card),
        propagateMinConstraints = true, content = content)''')
text = replace_once(text, '    size: Int = 14, align: TextAlign = TextAlign.End)', '    size: Int = 16, align: TextAlign = TextAlign.End)')
text = text.replace('Arrangement.spacedBy(12.dp)', 'Arrangement.spacedBy(10.dp)')
text = replace_once(text, '.heightIn(min = 72.dp).padding(16.dp)', '.heightIn(min = 64.dp).padding(14.dp)')
text = replace_once(text, 'Text(title, color = LocalHomePalette.current.text, fontSize = 14.sp', 'Text(title, color = LocalHomePalette.current.text, fontSize = 15.sp')
text = replace_once(text, '.padding(start = 16.dp, end = 16.dp, bottom = 16.dp)', '.padding(start = 14.dp, end = 14.dp, bottom = 14.dp)')
text = replace_once(text, 'Text("延迟", Modifier.weight(1f), color = p.text, fontSize = 14.sp', 'Text("延迟", Modifier.weight(1f), color = p.text, fontSize = 15.sp')
text = replace_once(text, 'Text(name, color = p.muted, fontSize = 11.sp', 'Text(name, color = p.muted, fontSize = 12.sp')
text = replace_once(text, 'else HomeNumber(shown, Modifier.padding(top = 4.dp), tint, 21, TextAlign.Center)', 'else HomeNumber(shown, Modifier.padding(top = 4.dp), tint, 18, TextAlign.Center)')
pulse_start = text.index('            val pulse = if (data.testing && motion) {')
pulse_end = text.index('            Row(Modifier.fillMaxWidth(), verticalAlignment', pulse_start)
text = text[:pulse_start] + text[pulse_end:]
start = text.index('@Composable\nprivate fun HomePair(')
end = text.index('/** test.92 removed the bundled WebUI.', start)
text = text[:start] + r'''/** Equal-height pairs without fixed text heights. Large system fonts use one column. */
@Composable
private fun HomePair(left: @Composable (Modifier) -> Unit, right: @Composable (Modifier) -> Unit) {
    val scale = LocalDensity.current.fontScale
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth / scale < 290.dp) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                left(Modifier.fillMaxWidth()); right(Modifier.fillMaxWidth())
            }
        } else Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min),
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            left(Modifier.weight(1f).fillMaxHeight())
            right(Modifier.weight(1f).fillMaxHeight())
        }
    }
}

@Composable
private fun HomeDataHeading(title: String, modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null) {
    val p = LocalHomePalette.current
    Row(modifier.fillMaxWidth().heightIn(min = 32.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), color = p.text, fontSize = 15.sp, lineHeight = 21.sp,
            fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        trailing?.invoke()
    }
}

@Composable
private fun HomeNetwork(data: CompactHomeData, modifier: Modifier) {
    val p = LocalHomePalette.current
    val motion = LocalHomeMotion.current
    var isLan by rememberSaveable { mutableStateOf(false) }
    var details by remember { mutableStateOf(false) }
    var dragDistance by remember { mutableFloatStateOf(0f) }
    val threshold = with(LocalDensity.current) { 36.dp.toPx() }
    // Native clickable consumes the child Details click; no parent Initial-pass interception.
    // A horizontal drag cancels the press, while vertical drags remain owned by LazyColumn.
    HomeCard(modifier.testTag("home-network").semantics { stateDescription = if (isLan) "LAN" else "WAN" }
        .draggable(state = rememberDraggableState { dragDistance += it }, orientation = Orientation.Horizontal,
            onDragStarted = { dragDistance = 0f }, onDragStopped = {
                if (dragDistance < -threshold) isLan = true
                else if (dragDistance > threshold) isLan = false
                dragDistance = 0f
            }), onClick = { isLan = !isLan }, clickLabel = "切换 WAN 和 LAN") {
        Column(Modifier.fillMaxSize().padding(14.dp), verticalArrangement = Arrangement.SpaceBetween) {
            HomeDataHeading(if (isLan) "LAN" else "WAN", Modifier.testTag("home-network-switch")) {
                Box(Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                    .testTag("home-network-details").homeClick(label = "网络详情", onClick = { details = true }),
                    contentAlignment = Alignment.Center) {
                    Text("详情", Modifier.clip(CircleShape).background(p.blue.copy(alpha = .07f))
                        .padding(horizontal = 9.dp, vertical = 3.dp), color = p.blue,
                        fontSize = 11.sp, lineHeight = 16.sp, fontWeight = FontWeight.Bold)
                }
            }
            Crossfade(targetState = isLan, animationSpec = tween(if (motion) 140 else 0),
                modifier = Modifier.fillMaxWidth().testTag("home-network-body"), label = "network-mode") { lan ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        HomeLabel("IP", Modifier.width(22.dp))
                        Spacer(Modifier.width(6.dp))
                        Text((if (lan) data.lan else data.wan).ifBlank { "—" },
                            Modifier.weight(1f).testTag("home-network-ip"), color = p.text,
                            fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace, letterSpacing = (-.5).sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            style = LocalTextStyle.current.copy(fontFeatureSettings = "tnum"))
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        HomeLabel(if (lan) "接口" else "地区", Modifier.width(26.dp))
                        Spacer(Modifier.width(4.dp))
                        val flag = if (lan) "" else CompactHomeFormat.flag(data.countryCode)
                        if (flag.isNotBlank()) {
                            Box(Modifier.clip(RoundedCornerShape(4.dp)).background(p.blue.copy(alpha = .06f))
                                .padding(horizontal = 2.dp), contentAlignment = Alignment.Center) {
                                Text(flag, fontSize = 13.sp, lineHeight = 18.sp)
                            }
                            Spacer(Modifier.width(4.dp))
                        }
                        Text((if (lan) data.lanInterface else data.region)
                            .takeUnless { it.isBlank() || it == "—" } ?: if (lan) "接口未知" else "地区未知",
                            Modifier.weight(1f), color = p.text.copy(alpha = .82f),
                            fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
    if (details) NativeNetworkDetails(data) { details = false }
}

@Composable
private fun animatedMetric(value: Long): Long {
    val motion = LocalHomeMotion.current
    val animated by animateFloatAsState(value.coerceAtLeast(0).toFloat(),
        if (motion) tween(350, easing = FastOutSlowInEasing) else snap(), label = "live-metric")
    return if (motion) animated.toLong() else value
}

@Composable
private fun MetricLine(label: String, value: String, color: Color = LocalHomePalette.current.text) {
    Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        HomeLabel(label, Modifier.alignByBaseline())
        Spacer(Modifier.width(6.dp))
        HomeNumber(value, Modifier.weight(1f).alignByBaseline().testTag("home-value-$label"), color)
    }
}

@Composable
private fun HomeSpeed(data: CompactHomeData, modifier: Modifier, click: () -> Unit) {
    val up = animatedMetric(data.up)
    val down = animatedMetric(data.down)
    HomeCard(modifier.testTag("home-speed"), click) {
        Column(Modifier.fillMaxSize().padding(14.dp), verticalArrangement = Arrangement.SpaceBetween) {
            HomeDataHeading("网速", Modifier.heightIn(min = 48.dp))
            Column {
                MetricLine("上行", if (data.running) CompactHomeFormat.bytes(up) + "/s" else "—")
                MetricLine("下行", if (data.running) CompactHomeFormat.bytes(down) + "/s" else "—")
            }
        }
    }
}

/** Common six-dp tracks align the subscription and resource cards, including unknown data. */
@Composable
private fun HomeUsageBar(fraction: Float?, tag: String, description: String,
    tint: Color = LocalHomePalette.current.blue) {
    val p = LocalHomePalette.current
    val motion = LocalHomeMotion.current
    val target = fraction?.coerceIn(0f, 1f)
    val progress by animateFloatAsState(target ?: 0f,
        if (motion) tween(500, easing = FastOutSlowInEasing) else snap(), label = "$tag-progress")
    val color by animateColorAsState(tint, if (motion) tween(300) else snap(), label = "$tag-color")
    Canvas(Modifier.fillMaxWidth().padding(top = 8.dp).height(6.dp).clip(CircleShape)
        .testTag(tag).semantics {
            if (target != null) progressBarRangeInfo = ProgressBarRangeInfo(target, 0f..1f)
            stateDescription = description
        }) {
        drawRoundRect(p.line, cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height))
        if (target != null && progress > 0f) drawRoundRect(color,
            size = androidx.compose.ui.geometry.Size(size.width * progress, size.height),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height))
    }
}

@Composable
private fun HomeSubscription(data: CompactHomeData, modifier: Modifier, click: () -> Unit) {
    val p = LocalHomePalette.current
    val remaining = CompactHomeFormat.remaining(data.used, data.total)
    val fraction = CompactHomeFormat.usedFraction(data.used, data.total)
    HomeCard(modifier.testTag("home-subscription"), click) {
        Column(Modifier.fillMaxSize().padding(14.dp), verticalArrangement = Arrangement.SpaceBetween) {
            HomeDataHeading("订阅") {
                if (remaining != null) Text("剩余 $remaining%",
                    Modifier.clip(CircleShape).background(p.blue.copy(alpha = .07f))
                        .padding(horizontal = 6.dp, vertical = 3.dp),
                    color = p.blue, fontSize = 11.sp, lineHeight = 16.sp,
                    fontWeight = FontWeight.Bold, maxLines = 1)
            }
            Column {
                MetricLine("已用", if (data.total > 0) CompactHomeFormat.bytes(data.used.coerceAtLeast(0)) else "—")
                MetricLine("总量", if (data.total > 0) CompactHomeFormat.bytes(data.total) else "—")
                HomeUsageBar(fraction, "home-subscription-bar",
                    if (fraction == null) "订阅总量未知" else "流量已用 ${String.format(Locale.US, "%.1f", fraction * 100)}%")
            }
        }
    }
}

@Composable
private fun HomeResources(data: CompactHomeData, modifier: Modifier) {
    val p = LocalHomePalette.current
    val motion = LocalHomeMotion.current
    val known = data.running && data.cpu.isFinite() && data.cpu >= 0
    val cpu by animateFloatAsState(if (known) data.cpu else 0f,
        if (motion) tween(400) else snap(), label = "cpu-number")
    val tint = when { !known -> p.muted; data.cpu >= 100 -> p.red
        data.cpu >= 80 -> Color(0xFFD97706); else -> p.blue }
    HomeCard(modifier.testTag("home-resources")) {
        Column(Modifier.fillMaxSize().padding(14.dp), verticalArrangement = Arrangement.SpaceBetween) {
            HomeDataHeading("资源占用")
            Column {
                MetricLine("内存", if (data.running && data.memory > 0) CompactHomeFormat.bytes(data.memory) else "—")
                MetricLine("CPU", if (known) String.format(Locale.US, "%.1f%%", cpu) else "—",
                    if (known && data.cpu >= 80) tint else p.text)
                HomeUsageBar(if (known) (data.cpu / 100).coerceIn(0f, 1f) else null, "home-cpu-bar",
                    if (known) "CPU ${data.cpu}%" else "CPU 未知", tint)
            }
        }
    }
}

''' + text[end:]
assert 'Icons.Rounded.UnfoldMore' not in text
HOME.write_text(text)

tests = TEST.read_text()
tests = replace_once(tests, 'import androidx.compose.runtime.CompositionLocalProvider', 'import androidx.compose.runtime.mutableStateOf\nimport androidx.compose.ui.geometry.Offset\nimport androidx.compose.ui.semantics.SemanticsActions\nimport androidx.compose.ui.text.TextLayoutResult\nimport androidx.compose.ui.unit.dp\nimport androidx.compose.ui.unit.sp\nimport androidx.compose.runtime.CompositionLocalProvider')
tests = replace_once(tests, 'private fun render(data: CompactHomeData = fixture(), dark: Boolean = false, scale: Float = 1f) {', 'private fun render(data: CompactHomeData = fixture(), dark: Boolean = false, scale: Float = 1f, provider: (() -> CompactHomeData)? = null) {')
tests = replace_once(tests, 'CompactHomeDashboard(data, { calls += "refresh" }', 'CompactHomeDashboard(provider?.invoke() ?: data, { calls += "refresh" }')
tests = replace_once(tests, '@Test fun wanIsDefaultAndInlineSwitchShowsLan()', '@Test fun wanIsDefaultAndWholeCardShowsLan()')
tests = replace_once(tests, 'rule.onNodeWithTag("home-network-switch").performClick()', 'rule.onNodeWithTag("home-network").performClick()')
extra_tests = r'''
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
        assertEquals(10f, resource.left - sub.right, 1f)
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
        rule.onAllNodesWithText(ip, useUnmergedTree = true).assertAny(isDisplayed())
        snapshot("network-details-large-font")
        rule.onNodeWithTag("sheet-confirm").performClick()
        rule.waitForIdle()
        revealGrid()
        rule.onNodeWithTag("home-cpu-bar", useUnmergedTree = true).assertIsDisplayed()
        snapshot("grid-large-font")
    }
'''
tests = tests.rstrip()
assert tests.endswith('}')
tests = tests[:-1] + extra_tests + '\n}\n'
TEST.write_text(tests)
build = BUILD.read_text()
build = replace_once(build, 'versionCode = 1003', 'versionCode = 1004')
build = replace_once(build, 'versionName = "0.4.0-ui92-r146.2"', 'versionName = "0.4.0-ui92-r146.3"')
BUILD.write_text(build)
changed = [str(p.relative_to(ROOT)) for p in (HOME, TEST, BUILD)]
actual = subprocess.check_output(['git', 'diff', '--name-only'], cwd=ROOT).decode().splitlines()
assert sorted(actual) == sorted(changed), ('Unexpected source changes', actual)
evidence = ROOT / 'integration-evidence'
evidence.mkdir(exist_ok=True)
(evidence / 'ui3-changed-paths.json').write_text(json.dumps(changed, indent=2))
print('Applied UI3 only:', changed)
