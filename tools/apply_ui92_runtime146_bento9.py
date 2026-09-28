#!/usr/bin/env python3
"""Apply the user's latest Bento layout to the exact verified UI8 input, once."""
from pathlib import Path
import json, subprocess
BASE = 'c7dc243376586481aae39b3e665e7ab92271d95f'
R = 'android-app/app/src/main/java/io/github/xgl34222220/hetu/'
T = 'android-app/app/src/test/java/io/github/xgl34222220/hetu/'
changed = []
def original(path):
    expected = subprocess.check_output(['git', 'show', BASE + ':' + path])
    assert Path(path).read_bytes() == expected, 'Input has diverged: ' + path
    return expected.decode()
def one(s, a, b):
    assert s.count(a) == 1, (a[:100], s.count(a))
    return s.replace(a, b)
def save(path, s):
    if Path(path).read_text() != s:
        Path(path).write_text(s)
        changed.append(path)

s = original(R + 'CompactHomeDashboard.kt')
if 'import androidx.compose.animation.animateContentSize\n' not in s:
    s = s.replace('package io.github.xgl34222220.hetu\n', 'package io.github.xgl34222220.hetu\n\nimport androidx.compose.animation.animateContentSize\n', 1)
s = s.replace('0xFFF6F8FD', '0xFFF4F6FC')
s = one(s, '    val shape = HomeContinuousShape(28.dp)', '    val shape = HomeContinuousShape(24.dp)')
s = one(s, '''        val density = LocalDensity.current
        var headerPx by remember(density.density) { mutableIntStateOf(with(density) { 56.dp.roundToPx() }) }
''', '')
s = one(s, '''            HomeCollapsingHeader(collapse, motion, headerHaze, palette.text,
                modifier = Modifier.zIndex(2f).onSizeChanged { headerPx = it.height }) {''', '''            // A measured lane above the header, NOT an overlay over the first list item.
            // Its animated bounds clip the entering/exiting pill, including at large font scales.
            Box(Modifier.fillMaxWidth().animateContentSize(
                animationSpec = if (motion) spring(dampingRatio = 1f, stiffness = 700f) else snap()
            ).clipToBounds().testTag("home-notice-lane")) {
                HomeFeedbackPill(notice.value, motion, headerHaze,
                    Modifier.align(Alignment.TopCenter).padding(top = 10.dp, bottom = 8.dp,
                        start = 16.dp, end = 16.dp)) { raw ->
                    feedbackDetails = raw
                    notice.value = null
                }
            }
            HomeCollapsingHeader(collapse, motion, headerHaze, palette.text,
                modifier = Modifier.zIndex(2f)) {''')
s = one(s, '''        HomeFeedbackPill(notice.value, motion, headerHaze,
            Modifier.align(Alignment.TopCenter)
                .windowInsetsPadding(contentInsets.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                .padding(top = with(density) { headerPx.toDp() } + 8.dp, start = 16.dp, end = 16.dp)) { raw ->
            feedbackDetails = raw
            notice.value = null
        }
''', '')
s = one(s, '''            item("hero") { HomeHero(data, onToggle, onReload, onRestart, onSettings, onWebUi, onLog) }
''', '''            item("hero") { HomeHero(data, onToggle, onReload, onRestart, onSettings) }
            item("shortcuts") { HomeShortcutStrip(onWebUi, onLog) }
''')
a = s.index('@Composable\nprivate fun HomeHero(')
b = s.index('@Composable\nprivate fun HomeLatency(', a)
s = s[:a] + '''@Composable
private fun HomeHero(data: CompactHomeData, toggle: () -> Unit, reload: () -> Unit,
    restart: () -> Unit, settings: () -> Unit) {
    NativeStatusHero(data, toggle, reload, restart, settings, LocalHomeMotion.current)
}

@Composable
private fun HomeShortcutStrip(webUi: () -> Unit, log: () -> Unit) {
    Row(Modifier.fillMaxWidth().testTag("home-shortcut-strip"), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        HomeHeroShortcut("WebUI", "Web 界面", Modifier.weight(1f).testTag("home-webui"), webUi)
        HomeHeroShortcut("日志", "查看记录", Modifier.weight(1f).testTag("home-log"), log)
    }
}

@Composable
private fun HomeHeroShortcut(title: String, subtitle: String, modifier: Modifier, click: () -> Unit) {
    val p = LocalHomePalette.current
    HomeCard(modifier.heightIn(min = 58.dp), onClick = click, clickLabel = title) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, color = p.text, fontSize = 15.sp, lineHeight = 21.sp, fontWeight = FontWeight.Bold)
                Text(subtitle, color = p.muted, fontSize = 11.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium)
            }
            Icon(Icons.Rounded.ChevronRight, null, Modifier.size(18.dp), tint = p.muted)
        }
    }
}

''' + s[b:]
a = s.index('@Composable\nprivate fun HomeTelemetryGrid(')
b = s.index('@Composable\nprivate fun HomeDataHeading(', a)
s = s[:a] + '''private val LocalBentoStackedMetrics = staticCompositionLocalOf { false }

/** Two material surfaces with independent halves. Address content NEVER changes column count. */
@Composable
private fun HomeTelemetryGrid(data: CompactHomeData, connections: () -> Unit, subscription: () -> Unit) {
    val density = LocalDensity.current
    val measurer = androidx.compose.ui.text.rememberTextMeasurer()
    val textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Default, fontWeight = FontWeight.Bold,
        fontSize = 16.sp, lineHeight = 22.sp, letterSpacing = (-.5).sp, fontFeatureSettings = "tnum")
    BoxWithConstraints(Modifier.fillMaxWidth().testTag("home-telemetry-grid")) {
        val cellWidth = (maxWidth - 1.dp) / 2
        // Accessibility reflows labels within each half, never four empty full-width cards.
        val stacked = (cellWidth - 28.dp) / density.fontScale < 110.dp
        val regionWidth = with(density) { (cellWidth - 28.dp - 54.dp).roundToPx() }.coerceAtLeast(1)
        fun measuredHeight(value: String, width: Int, style: androidx.compose.ui.text.TextStyle, maxLines: Int): androidx.compose.ui.unit.Dp {
            val result = measurer.measure(androidx.compose.ui.text.AnnotatedString(value.ifBlank { "—" }),
                style = style, constraints = androidx.compose.ui.unit.Constraints(maxWidth = width), maxLines = maxLines)
            return with(density) { kotlin.math.ceil(result.multiParagraph.height).toInt().toDp() }
        }
        val line = with(density) { 22.sp.toDp() }
        val metricHeight = if (stacked) with(density) { 39.sp.toDp() } else line
        val heading = maxOf(32.dp, with(density) { 21.sp.toDp() })
        val first = maxOf(metricHeight, with(density) {
            listOf(data.wan, data.lan).maxOf { address ->
                val result = measurer.measure(androidx.compose.ui.text.AnnotatedString(address.ifBlank { "—" }),
                    style = textStyle, softWrap = false, maxLines = 1)
                kotlin.math.ceil(result.multiParagraph.height).toInt()
            }.toDp()
        })
        val regionStyle = textStyle.copy(fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.sp)
        val anchor = measurer.measure(androidx.compose.ui.text.AnnotatedString("0"), style = textStyle).firstBaseline
        val smallAnchor = measurer.measure(androidx.compose.ui.text.AnnotatedString("0"), style = regionStyle).firstBaseline
        val baseline = with(density) { anchor.toDp() }
        val baselineExtra = with(density) { (anchor - smallAnchor).coerceAtLeast(0f).toDp() }
        val second = maxOf(metricHeight,
            measuredHeight(data.region, regionWidth, regionStyle, 2) + baselineExtra,
            measuredHeight(data.lanInterface, regionWidth, regionStyle, 2) + baselineExtra)
        val rows = HomeGridRows(heading, maxOf(0.dp, (48.dp - heading) / 2), first, second, baseline)
        CompositionLocalProvider(LocalHomeGridRows provides rows, LocalBentoStackedMetrics provides stacked) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                HomeBentoPair(rows, "home-network-group",
                    left = { HomeNetwork(data, it) }, right = { HomeSpeed(data, it, connections) })
                HomeBentoPair(rows, "home-health-group",
                    left = { HomeSubscription(data, it, subscription) }, right = { HomeResources(data, it) })
            }
        }
    }
}

@Composable
private fun HomeBentoPair(rows: HomeGridRows, tag: String,
    left: @Composable (Modifier) -> Unit, right: @Composable (Modifier) -> Unit) {
    val p = LocalHomePalette.current
    HomeCard(Modifier.fillMaxWidth().testTag(tag)) {
        CompositionLocalProvider(LocalHomeGroupedSurface provides true) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                left(Modifier.weight(1f).height(rows.height))
                Box(Modifier.width(1.dp).height(rows.height * .62f).background(p.line).testTag("$tag-divider"))
                right(Modifier.weight(1f).height(rows.height))
            }
        }
    }
}

''' + s[b:]
a = s.index('@Composable\nprivate fun MetricLine(')
b = s.index('@Composable\nprivate fun HomeSpeed(', a)
s = s[:a] + '''@Composable
private fun MetricLine(label: String, value: String, color: Color = LocalHomePalette.current.text,
    transmitting: Boolean = false) {
    val rows = LocalHomeGridRows.current
    val first = label in listOf("上行", "已用", "内存")
    val modifier = Modifier.fillMaxWidth().padding(top = if (first) 0.dp else 4.dp)
        .height(if (first) rows.first else rows.second)
    @Composable fun Label(modifier: Modifier = Modifier) {
        Row(modifier, verticalAlignment = Alignment.CenterVertically) {
            if (label == "上行" || label == "下行") {
                val tint = if (label == "上行") LocalHomePalette.current.blue else Color(0xFF10B981)
                val motion = LocalHomeMotion.current
                val alpha = if (transmitting && motion) {
                    val pulse = rememberInfiniteTransition(label = "traffic-$label")
                    pulse.animateFloat(.45f, 1f, infiniteRepeatable(tween(850), RepeatMode.Reverse), label = "traffic-dot").value
                } else if (transmitting) 1f else .3f
                Box(Modifier.size(5.dp).graphicsLayer { this.alpha = alpha }.background(tint, CircleShape)
                    .testTag("home-traffic-dot-$label").semantics { stateDescription = if (transmitting) "有传输" else "无传输" })
                Spacer(Modifier.width(4.dp))
            }
            HomeLabel(label, Modifier.alignByBaseline().testTag("home-label-$label"))
        }
    }
    if (LocalBentoStackedMetrics.current) {
        Column(modifier) {
            Label()
            // Keep even large units available inside their own scroll viewport.
            HomeNumber(value, Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).width(IntrinsicSize.Max)
                .testTag("home-value-$label"), color)
        }
    } else {
        Row(modifier) {
            Label(Modifier.alignByBaseline())
            Spacer(Modifier.width(6.dp))
            HomeNumber(value, Modifier.weight(1f).alignByBaseline().testTag("home-value-$label"), color)
        }
    }
}

''' + s[b:]
s = one(s, 'MetricLine("上行", if (data.running) CompactHomeFormat.bytes(up) + "/s" else "—")',
    'MetricLine("上行", if (data.running) CompactHomeFormat.bytes(up) + "/s" else "—", transmitting = data.running && up > 0L)')
s = one(s, 'MetricLine("下行", if (data.running) CompactHomeFormat.bytes(down) + "/s" else "—")',
    'MetricLine("下行", if (data.running) CompactHomeFormat.bytes(down) + "/s" else "—", transmitting = data.running && down > 0L)')
s = one(s, 'HomeDataHeading("网速")', 'HomeDataHeading("实时速率")')
s = one(s, 'HomeDataHeading("订阅") {', 'HomeDataHeading(if (LocalBentoStackedMetrics.current) "订阅" else "套餐订阅") {')
s = one(s, 'HomeDataHeading("资源占用")', 'HomeDataHeading("系统负载")')
save(R + 'CompactHomeDashboard.kt', s)

# Palette only: do not change runtime functions, non-home page structures, or the core.
for path in [R + 'ReferenceProxyActivity.kt', R + 'ui/HetuTheme.kt', 'android-app/app/src/main/res/values/styles.xml']:
    s = original(path)
    assert 'F6F8FD' in s
    save(path, s.replace('F6F8FD', 'F4F6FC'))
s = original('android-app/app/build.gradle.kts')
s = one(s, 'versionCode = 1009', 'versionCode = 1010')
s = one(s, '0.4.0-ui92-r146.8', '0.4.0-ui92-r146.9')
save('android-app/app/build.gradle.kts', s)

# Update only expectations explicitly superseded by the latest attachment.
s = original(T + 'HomeUi8ExperienceTest.kt')
s = one(s, 'narrowLargeTextUsesWideLayoutInsteadOfAnOrphanDigit', 'narrowLargeTextKeepsBentoHalvesAndScrollableAddress')
s = one(s, '''        assertEquals(node("home-telemetry-grid").getUnclippedBoundsInRoot().width,
            node("home-network").getUnclippedBoundsInRoot().width)''', '''        assertTrue(node("home-network").getUnclippedBoundsInRoot().width <
            node("home-telemetry-grid").getUnclippedBoundsInRoot().width * .6f)
        node("home-network-ip").performTouchInput { swipeLeft() }
        node("home-network").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "WAN"))''')
s = one(s, 'successFeedbackIsBelowSafeHeaderAndNowhereNearDock', 'successFeedbackIsAboveHeaderAndOutsideTheContent')
s = one(s, '        assertTrue(p.top >= header.bottom + 7.dp)',
    '        assertTrue(p.top >= 24.dp)\n        assertTrue(p.bottom <= header.top - 7.dp)')
# The new shortcut row moves telemetry from lazy item 2 to item 3.
s = one(s, 'performScrollToIndex(2)', 'performScrollToIndex(3)')
save(T + 'HomeUi8ExperienceTest.kt', s)
s = original(T + 'HomeUi7InteractionTest.kt')
s = one(s, 'shortcutsAreInsideHeroNotSeparateMaterialCards', 'shortcutsFollowHeroInTheLatestCompactTileStrip')
s = one(s, 'rule.onNodeWithTag("home-webui").assert(hasAnyAncestor(hasTestTag("home-hero")))',
    '''rule.onNodeWithTag("home-webui").assert(hasAnyAncestor(hasTestTag("home-shortcut-strip")))
        val strip = rule.onNodeWithTag("home-shortcut-strip").fetchSemanticsNode().boundsInRoot
        val hero = rule.onNodeWithTag("home-hero").fetchSemanticsNode().boundsInRoot
        assertTrue(strip.top >= hero.bottom)''')
save(T + 'HomeUi7InteractionTest.kt', s)
s = original(T + 'HomeUi6RegressionTest.kt')
assert 'F6F8FD' in s
save(T + 'HomeUi6RegressionTest.kt', s.replace('F6F8FD', 'F4F6FC'))
Path('integration-evidence').mkdir(exist_ok=True)
Path('integration-evidence/ui9-changed-paths.json').write_text(json.dumps(changed, indent=2))
print('UI9 reviewed application/test changes:', *changed, sep='\n')
