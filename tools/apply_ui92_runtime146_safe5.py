#!/usr/bin/env python3
"""One-time UI4 -> UI5 presentation migration. Refuse to overwrite later work."""
from pathlib import Path
import json
import subprocess

BASE = '1c04d12a0d77437b4611537c7dd3e5e18f897719'
ROOT = 'android-app/app/src/main/java/io/github/xgl34222220/hetu/'
TEST = 'android-app/app/src/test/java/io/github/xgl34222220/hetu/'
changes = {}

def original(path):
    current = Path(path).read_bytes()
    baseline = subprocess.check_output(['git', 'show', BASE + ':' + path])
    assert current == baseline, 'Refusing to replace modified source: ' + path
    return current.decode()

def one(text, old, new):
    assert text.count(old) == 1, ('Expected one replacement', old[:140], text.count(old))
    return text.replace(old, new, 1)

s = original(ROOT + 'CompactHomeDashboard.kt')
s = one(s, 'import androidx.compose.ui.draw.clip\n', 'import androidx.compose.ui.draw.clip\nimport androidx.compose.ui.draw.clipToBounds\n')
s = one(s, '    motionEnabled: Boolean = true,\n) {', '    motionEnabled: Boolean = true,\n    contentInsets: WindowInsets = WindowInsets.safeDrawing,\n) {')
s = s.replace('0xFFEFF2F8', '0xFFF6F8FC')
a = s.index('        Box(Modifier.fillMaxSize()) {\n        LazyColumn(')
b = s.index('            item("hero")', a)
s = s[:a] + '''        // The header is a sibling of the scroll viewport, never a lazy item.
        // Insets are applied outside the clipped viewport and consumed exactly once.
        Box(modifier.fillMaxSize().background(palette.page).testTag("home-viewport")) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(
            contentInsets.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))) {
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 16.dp)
                .testTag("home-header"), verticalAlignment = Alignment.CenterVertically) {
                HomeIcon(Icons.Rounded.Refresh, "刷新状态", !data.refreshing && !data.busy, data.refreshing,
                    onClick = onRefresh)
                Text("河图", Modifier.weight(1f).testTag("home-brand"), color = palette.text,
                    fontSize = 22.sp, lineHeight = 29.sp, fontWeight = FontWeight.Black,
                    letterSpacing = 1.sp, textAlign = TextAlign.Center)
                Box {
                    HomeIcon(Icons.Rounded.MoreVert, "更多首页功能", onClick = { more = true })
                    DropdownMenu(expanded = more, onDismissRequest = { more = false }) {
                        listOf("应用连接" to onConnections, "广告过滤" to onAdblock,
                            "网络诊断" to onDiagnostics, "代理设置" to onSettings).forEach { (title, callback) ->
                            DropdownMenuItem(text = { Text(title) }, enabled = title != "网络诊断" || !data.diagnosticLoading,
                                onClick = { more = false; callback() })
                        }
                    }
                }
            }
        LazyColumn(Modifier.fillMaxWidth().weight(1f).clipToBounds().testTag("compact-home"),
            overscrollEffect = null,
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp,
                bottom = contentInsets.asPaddingValues().calculateBottomPadding() + 94.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
''' + s[b:]
s = one(s, '        }\n        SnackbarHost(snackbar,', '        }\n        }\n        SnackbarHost(snackbar,')
# Numeric glyphs follow the system family; only digits are tabular, not every character.
a = s.index('private fun HomeNumber(')
b = s.index('@Composable\nprivate fun HomeIcon', a)
part = s[a:b]
assert 'fontFamily = FontFamily.Monospace' in part
part = part.replace('fontFamily = FontFamily.Monospace', 'fontFamily = FontFamily.Default')
s = s[:a] + part + s[b:]
s = one(s, '.clip(RoundedCornerShape(7.dp)).background(p.red.copy(alpha = .08f))', '.clip(CircleShape).background(p.red.copy(alpha = .08f))')
s = one(s, '.padding(horizontal = 10.dp, vertical = 5.dp),', '.padding(horizontal = 10.dp, vertical = 3.dp),')
s = one(s, 'else -> HomeNumber(shown, Modifier.padding(top = 4.dp), tint, 18, TextAlign.Center)', 'else -> HomeNumber(shown, Modifier.padding(top = 4.dp), tint, 19, TextAlign.Center)')
a = s.index('private fun HomeNetwork(')
b = s.index('@Composable\nprivate fun animatedMetric', a)
part = s[a:b]
part = one(part, '    var details by remember { mutableStateOf(false) }', '    var details by remember { mutableStateOf(false) }\n    val transition = rememberNetworkModeTransition(isLan, motion)\n    val shownLan = if (motion) transition.displayedLan else isLan')
part = one(part, 'HomeDataHeading(if (isLan) "LAN" else "WAN",', 'HomeDataHeading(if (shownLan) "LAN" else "WAN",')
x = part.index('            // Only one mode exists in the tree.')
y = part.index('                    // Put the address on its own full-width line.', x)
part = part[:x] + '''            // The same body exits, swaps at zero opacity, then enters. No dual IP layers.
            key(Unit) {
                Column(Modifier.fillMaxWidth().testTag("home-network-body").graphicsLayer {
                    val progress = if (motion) transition.progress.value else 1f
                    alpha = progress.coerceIn(0f, 1f)
                    scaleX = .97f + .03f * progress
                    scaleY = scaleX
                    translationY = (1f - progress) * (if (transition.leaving) -6f else 6f) * density
                }, verticalArrangement = Arrangement.spacedBy(4.dp)) {
''' + part[y:]
x = part.index('            key(Unit)')
part = part[:x] + part[x:].replace('if (isLan)', 'if (shownLan)').replace('fontFamily = FontFamily.Monospace', 'fontFamily = FontFamily.Default')
s = s[:a] + part + s[b:]
# Give the track an independent full-width background rather than an almost-white paint.
a = s.index('@Composable\nprivate fun HomeUsageBar(')
b = s.index('@Composable\nprivate fun HomeSubscription', a)
s = s[:a] + '''@Composable
internal fun HomeUsageBar(fraction: Float?, tag: String, description: String,
    tint: Color = LocalHomePalette.current.blue) {
    val motion = LocalHomeMotion.current
    val target = fraction?.takeIf { it.isFinite() }?.coerceIn(0f, 1f)
    val dark = MaterialTheme.colorScheme.background.luminance() < .5f
    val track = if (dark) Color(0xFF334155) else Color(0xFFE2E8F0)
    val progress by animateFloatAsState(target ?: 0f,
        if (motion) tween(500, easing = FastOutSlowInEasing) else snap(), label = "$tag-progress")
    val color by animateColorAsState(tint, if (motion) tween(300) else snap(), label = "$tag-color")
    Box(Modifier.fillMaxWidth().padding(top = 8.dp).height(6.dp).clip(CircleShape)
        .background(track).testTag(tag).semantics {
            if (target != null) progressBarRangeInfo = ProgressBarRangeInfo(target, 0f..1f)
            stateDescription = description
        }) {
        Canvas(Modifier.matchParentSize()) {
            if (target != null && progress > 0f) drawRoundRect(color,
                size = androidx.compose.ui.geometry.Size(size.width * progress, size.height),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height / 2f))
        }
    }
}

''' + s[b:]
changes[ROOT + 'CompactHomeDashboard.kt'] = s

s = original(ROOT + 'NativeHomePolish.kt')
s = s.replace('fontFamily = FontFamily.Monospace', 'fontFamily = FontFamily.Default')
s = one(s, 'fontFamily = FontFamily.Default, fontWeight = FontWeight.Bold, fontSize = 14.sp, lineHeight = 21.sp)', 'fontFamily = FontFamily.Default, fontWeight = FontWeight.Bold, fontSize = 14.sp, lineHeight = 21.sp,\n                style = LocalTextStyle.current.copy(fontFeatureSettings = "tnum"))')
s += '''

/** Presentation state only: never invokes proxy, routing or network operations. */
@Stable
internal class NetworkModeTransition(initialLan: Boolean) {
    var displayedLan by mutableStateOf(initialLan)
        internal set
    var leaving by mutableStateOf(false)
        internal set
    val progress = Animatable(1f)
}

/**
 * Sequential out-in, not Crossfade/AnimatedContent. A new target cancels the old coroutine;
 * the latest target wins, and a stopped lifecycle/reduced-motion request settles immediately.
 * Exactly one subtree is composed throughout both halves of the transition.
 */
@Composable
internal fun rememberNetworkModeTransition(targetLan: Boolean, motion: Boolean): NetworkModeTransition {
    val state = remember { NetworkModeTransition(targetLan) }
    LaunchedEffect(targetLan, motion) {
        if (!motion) {
            state.displayedLan = targetLan
            state.leaving = false
            state.progress.snapTo(1f)
        } else {
            if (state.displayedLan != targetLan) {
                state.leaving = true
                state.progress.animateTo(0f, tween(110, easing = FastOutSlowInEasing))
                state.displayedLan = targetLan
                state.leaving = false
                state.progress.snapTo(0f)
            } else state.leaving = false
            state.progress.animateTo(1f, tween(220, easing = CubicBezierEasing(.34f, 1.56f, .64f, 1f)))
        }
    }
    return state
}
'''
changes[ROOT + 'NativeHomePolish.kt'] = s

s = original(ROOT + 'ReferenceProxyActivity.kt')
s = s.replace('0xFFEFF2F8', '0xFFF6F8FC')
s = one(s, 'import androidx.compose.ui.draw.clip\n', 'import androidx.compose.ui.draw.clip\nimport androidx.compose.ui.draw.clipToBounds\n')
a = s.index('                RefProxyPage.Home -> PullToRefreshBox(')
b = s.index('                    RefHome(', a)
part = s[a:b]
part = one(part, 'modifier = Modifier.fillMaxSize(),', 'modifier = Modifier.fillMaxSize()\n                        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))\n                        .clipToBounds(),')
s = s[:a] + part + s[b:]
changes[ROOT + 'ReferenceProxyActivity.kt'] = s
for path in [ROOT + 'ui/HetuTheme.kt', 'android-app/app/src/main/res/values/styles.xml']:
    s = original(path)
    assert 'EFF2F8' in s, path
    changes[path] = s.replace('EFF2F8', 'F6F8FC')
path = 'android-app/app/build.gradle.kts'
s = original(path)
s = one(s, 'versionCode = 1005', 'versionCode = 1006')
s = one(s, 'versionName = "0.4.0-ui92-r146.4"', 'versionName = "0.4.0-ui92-r146.5"')
changes[path] = s

new_test = TEST + 'HomeUi5RegressionTest.kt'
assert not Path(new_test).exists(), new_test
changes[new_test] = r'''package io.github.xgl34222220.hetu

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
        rule.onNodeWithContentDescription("刷新状态").performClick()
        assertEquals(listOf("refresh"), calls)
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
        rule.runOnUiThread { target.value = true }
        rule.mainClock.advanceTimeBy(48)
        rule.onNodeWithTag("single-body").assertTextEquals("WAN")
        assertTrue(transition.leaving)
        assertTrue(transition.progress.value in 0f..1f)
        assertTrue(transition.progress.value < 1f)
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
            rule.runOnUiThread { target.value = !target.value }
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
        rule.runOnUiThread { target.value = true }
        rule.mainClock.advanceTimeBy(32)
        rule.runOnUiThread { motion.value = false }
        rule.mainClock.advanceTimeBy(32)
        rule.onNodeWithTag("single-body").assertTextEquals("LAN")
        assertEquals(1f, transition.progress.value, 0f)
        assertFalse(transition.leaving)
        rule.mainClock.autoAdvance = true
    }
}
'''
# Validate all inputs before writing anything; no service/core files are touched.
for path, content in changes.items():
    Path(path).parent.mkdir(parents=True, exist_ok=True)
    Path(path).write_text(content)
Path('integration-evidence').mkdir(exist_ok=True)
Path('integration-evidence/ui5-changed-paths.json').write_text(json.dumps(list(changes), indent=2))
print('Applied UI5 presentation changes:', list(changes))
