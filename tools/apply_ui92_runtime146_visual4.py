#!/usr/bin/env python3
"""One-time, exact-base UI3 -> UI4 presentation migration. Never reset later source."""
from pathlib import Path
import hashlib
import json
import subprocess

BASE = '39d6e4e6b997dc4564273c95ab27c11b5320d370'
ROOT = 'android-app/app/src/main/java/io/github/xgl34222220/hetu/'
TEST = 'android-app/app/src/test/java/io/github/xgl34222220/hetu/'
changes = {}

def read(path):
    data = Path(path).read_bytes()
    expected = subprocess.check_output(['git', 'show', BASE + ':' + path])
    if data != expected:
        raise RuntimeError('Source changed since reviewed UI3; reconcile instead of overwriting: ' + path)
    return data.decode('utf-8')

def replace(text, old, new, count=1):
    actual = text.count(old)
    if actual != count:
        raise RuntimeError(f'Expected {count} matches, got {actual}: {old[:100]!r}')
    return text.replace(old, new)

def put(path, text):
    changes[path] = text

# Homepage: native and bound to the existing runtime inputs, not the example Vue timers.
p = ROOT + 'CompactHomeDashboard.kt'
s = read(p)
s = replace(s, 'Color(0xFFF4F6FB)', 'Color(0xFFEFF2F8)')
s = replace(s, 'val shape = RoundedCornerShape(20.dp)', 'val shape = RoundedCornerShape(22.dp)')
s = s.replace('Arrangement.spacedBy(10.dp)', 'Arrangement.spacedBy(12.dp)')
a = s.index('            item("title") {')
b = s.index('            item("hero")', a)
s = s[:a] + '''            item("title") {
                Row(Modifier.fillMaxWidth().testTag("home-header"), verticalAlignment = Alignment.CenterVertically) {
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
            }
''' + s[b:]
s = replace(s, 'Column(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(14.dp)',
               'Column(Modifier.fillMaxWidth().heightIn(min = 72.dp).padding(16.dp)')
s = replace(s, 'Text(title, color = LocalHomePalette.current.text, fontSize = 15.sp, fontWeight = FontWeight.Bold)',
               'Text(title, color = LocalHomePalette.current.text, fontSize = 16.sp, fontWeight = FontWeight.Bold)')
s = replace(s, 'Text(name, color = p.muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)',
               'Text(name, color = p.muted, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)')
s = replace(s, '''                                if (shown == "···") LoadingWaveDots(motion, p.blue, Modifier.padding(top = 4.dp))
                                else HomeNumber(shown, Modifier.padding(top = 4.dp), tint, 18, TextAlign.Center)''',
'''                                when (shown) {
                                    "···" -> LoadingWaveDots(motion, p.blue, Modifier.padding(top = 4.dp))
                                    "超时", "失败" -> Text(shown,
                                        Modifier.padding(top = 4.dp).testTag("latency-badge-$name")
                                            .clip(RoundedCornerShape(7.dp)).background(p.red.copy(alpha = .08f))
                                            .padding(horizontal = 10.dp, vertical = 5.dp),
                                        color = p.red, fontSize = 12.sp, lineHeight = 17.sp, fontWeight = FontWeight.Bold)
                                    else -> HomeNumber(shown, Modifier.padding(top = 4.dp), tint, 18, TextAlign.Center)
                                }''')
s = replace(s, '@Composable\nprivate fun HomeNetwork(data:', '@OptIn(ExperimentalLayoutApi::class)\n@Composable\nprivate fun HomeNetwork(data:')
a = s.index('            Crossfade(targetState = isLan')
b = s.index('\n        }\n    }\n    if (details) NativeNetworkDetails', a)
s = s[:a] + '''            // Only one mode exists in the tree. No outgoing IP is painted under the new one.
            key(isLan) {
                val reveal = remember { Animatable(if (motion) 0f else 1f) }
                LaunchedEffect(motion) {
                    if (motion) reveal.animateTo(1f, tween(200, easing = FastOutSlowInEasing))
                    else reveal.snapTo(1f)
                }
                Column(Modifier.fillMaxWidth().testTag("home-network-body").graphicsLayer {
                    alpha = reveal.value
                    translationY = (1f - reveal.value) * 4.dp.toPx()
                }, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    // A FlowRow keeps short addresses inline, moves a longer address below its
                    // label, and lets IPv6 wrap. It supports the pair's intrinsic height query.
                    // No reduced font, clipping, fixed line limit, or ellipsis hides IP digits.
                    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp), maxItemsInEachRow = 2) {
                        HomeLabel("IP", Modifier.width(26.dp))
                        Text((if (isLan) data.lan else data.wan).ifBlank { "—" },
                            Modifier.testTag("home-network-ip"), color = p.text,
                            fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace, letterSpacing = (-.5).sp,
                            softWrap = true, overflow = TextOverflow.Clip,
                            style = LocalTextStyle.current.copy(fontFeatureSettings = "tnum"))
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        HomeLabel(if (isLan) "接口" else "地区", Modifier.width(26.dp))
                        Spacer(Modifier.width(4.dp))
                        val flag = if (isLan) "" else CompactHomeFormat.flag(data.countryCode)
                        if (flag.isNotBlank()) {
                            Box(Modifier.clip(RoundedCornerShape(4.dp)).background(p.blue.copy(alpha = .06f))
                                .padding(horizontal = 2.dp), contentAlignment = Alignment.Center) {
                                Text(flag, fontSize = 13.sp, lineHeight = 18.sp)
                            }
                            Spacer(Modifier.width(4.dp))
                        }
                        Text((if (isLan) data.lanInterface else data.region)
                            .takeUnless { it.isBlank() || it == "—" } ?: if (isLan) "接口未知" else "地区未知",
                            Modifier.weight(1f), color = p.text.copy(alpha = .82f),
                            fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }''' + s[b:]
put(p, s)

# Larger lifecycle typography and an 80dp vector glyph; operating state is still real.
p = ROOT + 'NativeHomePolish.kt'
s = read(p)
s = replace(s, 'val shape = RoundedCornerShape(24.dp)', 'val shape = RoundedCornerShape(26.dp)')
a = s.index('            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {', s.index('internal fun NativeStatusHero'))
b = s.index('            if (data.pendingSettings', a)
s = s[:a] + '''            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Box(Modifier.size(12.dp).background(accent, CircleShape))
                        Text(title, color = accent, fontSize = 22.sp, lineHeight = 29.sp,
                            fontWeight = FontWeight.Black, modifier = Modifier.testTag("hero-status-title")
                                .semantics { liveRegion = LiveRegionMode.Polite })
                    }
                    Text(when {
                        processing -> "正在与内核通信…"
                        data.running -> CompactHomeFormat.uptime(data.uptimeSeconds)
                        else -> "服务未启动"
                    }, Modifier.padding(start = 22.dp).testTag("hero-uptime"),
                        color = if (dark) muted else Color(0xFF334155), fontSize = 15.sp,
                        lineHeight = 21.sp, fontWeight = FontWeight.Bold)
                    Text(listOf(data.core, data.mode).filter(String::isNotBlank).joinToString(" · "),
                        Modifier.padding(start = 22.dp, top = 3.dp).testTag("hero-core"),
                        color = muted, fontSize = 13.sp, lineHeight = 19.sp, fontWeight = FontWeight.Bold)
                    Text(data.config.ifBlank { "尚未选择配置" },
                        Modifier.padding(start = 22.dp).testTag("hero-config"),
                        color = text, fontSize = 15.sp, lineHeight = 21.sp, fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.width(10.dp))
                HeroStatusGlyph(phase, accent, motion)
            }
''' + s[b:]
s = replace(s, 'Row(Modifier.fillMaxWidth().clip(CircleShape)\n                    .background',
               'Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))\n                    .background')
s = replace(s, '.padding(4.dp).heightIn(min = 48.dp).testTag("home-control-pill")',
               '.padding(6.dp).heightIn(min = 48.dp).testTag("home-control-pill")')
s = replace(s, 'HomePhase.Stopped -> PillAction("启动",', 'HomePhase.Stopped -> PillAction("启动服务",')
s = replace(s, 'Text(label, color = tint, fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold)',
               'Text(label, color = tint, fontSize = 15.sp, lineHeight = 21.sp, fontWeight = FontWeight.Bold)')
a = s.index('@Composable\nprivate fun HeroStatusGlyph')
b = s.index('@Composable\ninternal fun NativeSpinner', a)
s = s[:a] + '''@Composable
private fun HeroStatusGlyph(phase: HomePhase, color: Color, motion: Boolean) {
    // Normal density follows the reference; accessibility text gets space before decoration.
    val sizeDp = if (androidx.compose.ui.platform.LocalDensity.current.fontScale > 1.3f) 56.dp else 80.dp
    Box(Modifier.size(sizeDp).testTag("hero-status-glyph"), contentAlignment = Alignment.Center) {
        if (phase == HomePhase.Processing) NativeSpinner(color, motion, Modifier.fillMaxSize().padding(8.dp))
        else Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            drawCircle(color.copy(alpha = .18f), radius = w * .4f, style = Stroke(w * .1f))
            if (phase == HomePhase.Running) {
                drawArc(color, -90f, 270f, false, Offset(w * .1f, w * .1f), Size(w * .8f, w * .8f),
                    style = Stroke(w * .1f, cap = StrokeCap.Round))
                drawPath(Path().apply { moveTo(w * .32f, w * .5f); lineTo(w * .45f, w * .63f); lineTo(w * .70f, w * .36f) },
                    color, style = Stroke(w * .11f, cap = StrokeCap.Round, join = StrokeJoin.Round))
            } else drawLine(color, Offset(w * .35f, w * .5f), Offset(w * .65f, w * .5f), w * .08f, StrokeCap.Round)
        }
    }
}

''' + s[b:]
s = replace(s, 'RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp)', 'RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp)')
s = replace(s, 'Text(title, color = t.textPrimary, fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold)',
               'Text(title, Modifier.fillMaxWidth(), color = t.textPrimary, fontSize = 20.sp, lineHeight = 27.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)')
s = replace(s, '.testTag("sheet-confirm"), shape = CircleShape,', '.testTag("sheet-confirm"), shape = RoundedCornerShape(16.dp),')
put(p, s)

# Existing native glass stays; the content becomes an active-label capsule dock.
p = ROOT + 'ui/HetuGlassDock.kt'
s = read(p)
s = replace(s, 'import androidx.compose.ui.platform.LocalContext', '''import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.animation.core.snap''')
s = replace(s, 'Modifier.padding(horizontal = 20.dp).padding(bottom = bottomInset + 12.dp)',
               'Modifier.padding(horizontal = 24.dp).padding(bottom = bottomInset + 16.dp)')
s = replace(s, '.height(72.dp + if (floating) 0.dp else bottomInset)', '.height(64.dp + if (floating) 0.dp else bottomInset).testTag("hetu-dock")')
a = s.index('                .border(\n                    if (renderGlass)')
b = s.index('\n        )\n\n        DockItems', a)
s = s[:a].rstrip() + ',' + s[b:]
s = replace(s, '.then(liquidShellModifier),', '.then(liquidShellModifier),')
s = replace(s, 'itemHeight = 60.dp,', 'itemHeight = 52.dp,')
a = s.index('@Composable\nprivate fun DockItems(')
s = s[:a] + '''@Suppress("UNUSED_PARAMETER")
@Composable
private fun DockItems(
    items: List<DockItem>, selected: Int, onSelect: (Int) -> Unit,
    itemHeight: androidx.compose.ui.unit.Dp, modifier: Modifier,
    indicatorColor: Color, indicatorBorderColor: Color, indicatorShadow: androidx.compose.ui.unit.Dp,
    selectedColor: Color, unselectedColor: Color, liquidGlass: Boolean,
    indicatorBackdrop: LayerBackdrop?, dark: Boolean,
) {
    val motion = LocalHetuMotionEnabled.current && android.animation.ValueAnimator.areAnimatorsEnabled()
    val target = selected.coerceIn(0, items.lastIndex)
    Row(modifier.selectableGroup(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        items.forEachIndexed { index, item ->
            key(item.label) {
                val active = index == target
                val interaction = remember { MutableInteractionSource() }
                val pressed by interaction.collectIsPressedAsState()
                val weight by animateFloatAsState(if (active) 1.65f else 1f,
                    if (motion) spring(dampingRatio = .88f, stiffness = 420f) else snap(), label = "dock-weight-$index")
                val scale by animateFloatAsState(if (pressed && motion) .975f else 1f,
                    if (motion) spring(dampingRatio = .82f, stiffness = 550f) else snap(), label = "dock-press-$index")
                val fill by animateColorAsState(if (active) indicatorColor else Color.Transparent,
                    if (motion) tween(220) else snap(), label = "dock-fill-$index")
                val color by animateColorAsState(if (active) selectedColor else unselectedColor,
                    if (motion) tween(180) else snap(), label = "dock-tint-$index")
                Row(Modifier.weight(weight).height(itemHeight).testTag("dock-tab-$index")
                    .graphicsLayer { scaleX = scale; scaleY = scale }
                    .clip(RoundedCornerShape(26.dp)).background(fill)
                    .selectable(active, role = Role.Tab, interactionSource = interaction, indication = null,
                        onClick = { if (!active) onSelect(index) })
                    .semantics { contentDescription = item.label }
                    .padding(horizontal = 6.dp), horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically) {
                    Icon(item.icon, null, Modifier.size(21.dp).graphicsLayer {
                        scaleX = item.opticalScale; scaleY = item.opticalScale
                    }, tint = color)
                    if (active) {
                        Spacer(Modifier.width(6.dp))
                        Text(item.label, Modifier.testTag("dock-active-label"), color = color,
                            fontSize = 12.sp, lineHeight = 17.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                    }
                }
            }
        }
    }
}
'''
put(p, s)

# Full viewport and all theme-token consumers use one light canvas, retaining dark themes.
for path in [ROOT + 'ReferenceProxyActivity.kt', ROOT + 'ui/HetuTheme.kt']:
    s = read(path)
    if '0xFFF4F6FB' not in s:
        raise RuntimeError('Expected UI2/3 light canvas in ' + path)
    put(path, s.replace('0xFFF4F6FB', '0xFFEFF2F8'))
p = 'android-app/app/src/main/res/values/styles.xml'
s = read(p)
if '#F4F6FB' not in s:
    raise RuntimeError('Expected UI2/3 window canvas')
put(p, s.replace('#F4F6FB', '#EFF2F8'))
p = 'android-app/app/build.gradle.kts'
s = replace(read(p), 'versionCode = 1004', 'versionCode = 1005')
s = replace(s, 'versionName = "0.4.0-ui92-r146.3"', 'versionName = "0.4.0-ui92-r146.4"')
put(p, s)

# Preserve every prior test; update exact expectations for explicitly requested UI changes.
p = TEST + 'CompactHomeDashboardTest.kt'
s = read(p)
s = replace(s, 'provider: (() -> CompactHomeData)? = null)', 'provider: (() -> CompactHomeData)? = null, motion: Boolean = false)')
s = replace(s, '{ calls += "adblock" }, motionEnabled = false)', '{ calls += "adblock" }, motionEnabled = motion)')
s = replace(s, 'rule.onNodeWithText("启动").assertIsDisplayed()', 'rule.onNodeWithText("启动服务").assertIsDisplayed()')
s = replace(s, 'start >= pill - 9f', 'start >= pill - 13f')
s = replace(s, 'assertEquals(10f, resource.left - sub.right, 1f)', 'assertEquals(12f, resource.left - sub.right, 1f)')
extra = r'''
    private fun textLayout(tag: String): TextLayoutResult {
        val result = mutableListOf<TextLayoutResult>()
        rule.onNodeWithTag(tag, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(result) }
        return result.single()
    }

    @Test fun ui4BrandIsCenteredAndRefreshStillWorks() {
        render()
        val header = rule.onNodeWithTag("home-header").fetchSemanticsNode().boundsInRoot
        val brand = rule.onNodeWithTag("home-brand").fetchSemanticsNode().boundsInRoot
        assertEquals(header.center.x, brand.center.x, 1f)
        assertEquals(22.sp, textLayout("home-brand").layoutInput.style.fontSize)
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
        rule.onNodeWithTag("hero-status-glyph").assertWidthIsEqualTo(80.dp).assertHeightIsEqualTo(80.dp)
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
'''
s = s.rstrip()
assert s.endswith('}')
put(p, s[:-1] + extra + '\n}\n')

# Render and exercise the actual shared dock, including hidden-panel mode and accessibility.
p = TEST + 'NativeDockUi4Test.kt'
if Path(p).exists():
    raise RuntimeError('New test path already exists; do not overwrite')
put(p, r'''package io.github.xgl34222220.hetu

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import dev.chrisbanes.haze.HazeState
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
@Config(sdk = [35], qualifiers = "w393dp-h900dp-mdpi", application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NativeDockUi4Test {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val calls = mutableListOf<Int>()
    private val all = listOf(DockItem("首页", Icons.Rounded.Home), DockItem("面板", Icons.Rounded.Link),
        DockItem("工具", Icons.Rounded.GridView), DockItem("设置", Icons.Rounded.Settings))

    private fun render(items: List<DockItem> = all, dark: Boolean = false, scale: Float = 1f) {
        ApplicationProvider.getApplicationContext<Application>().getSharedPreferences("hetu", 0).edit()
            .putBoolean("enableBlur", false).putBoolean("floatingBottomBar", true).commit()
        rule.setContent {
            var selected by remember { mutableIntStateOf(0) }
            val density = LocalDensity.current
            CompositionLocalProvider(LocalHetuMotionEnabled provides false,
                LocalDensity provides Density(density.density, scale)) {
                MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                    Box(Modifier.fillMaxSize()) {
                        HetuGlassDock(items, selected, { calls += it; selected = it }, remember { HazeState() }, null,
                            Modifier.align(Alignment.BottomCenter))
                    }
                }
            }
        }
    }

    @Test fun selectedLabelOnlyAndAllDestinationsRemainAccessible() {
        render()
        rule.onAllNodesWithTag("dock-active-label", useUnmergedTree = true).assertCountEquals(1)
        rule.onNodeWithText("首页", useUnmergedTree = true).assertIsDisplayed()
        all.forEachIndexed { index, item ->
            rule.onNodeWithContentDescription(item.label).assertHasClickAction()
            rule.onNodeWithTag("dock-tab-$index").performTouchInput { click() }
            rule.onNodeWithTag("dock-tab-$index").assertIsSelected()
            rule.onNodeWithText(item.label, useUnmergedTree = true).assertIsDisplayed()
            rule.onAllNodesWithTag("dock-active-label", useUnmergedTree = true).assertCountEquals(1)
        }
        assertEquals(listOf(1, 2, 3), calls)
    }

    @Test fun dockTouchTargetsNeverOverlapOrCollapse() {
        render()
        val bounds = all.indices.map { rule.onNodeWithTag("dock-tab-$it").fetchSemanticsNode().boundsInRoot }
        bounds.forEach { assertTrue(it.width >= 48f); assertTrue(it.height >= 48f) }
        bounds.zipWithNext().forEach { (a,b) -> assertTrue(a.right <= b.left) }
        assertTrue(bounds.first().width > bounds.last().width)
        rule.onNodeWithTag("dock-tab-0").assertHeightIsEqualTo(52.dp)
    }

    @Test fun hiddenPanelPreferenceKeepsThreeUsableDestinations() {
        val items = listOf(all[0], all[2], all[3])
        render(items)
        rule.onNodeWithContentDescription("面板").assertDoesNotExist()
        rule.onNodeWithContentDescription("设置").performClick()
        rule.onNodeWithTag("dock-tab-2").assertIsSelected()
        assertEquals(listOf(2), calls)
    }

    @Test @Config(qualifiers = "w320dp-h900dp-mdpi")
    fun darkNarrowDockAndLargeTextRemainWithinWindow() {
        render(dark = true, scale = 1.6f)
        rule.onNodeWithContentDescription("设置").performTouchInput { click() }
        rule.onNodeWithText("设置", useUnmergedTree = true).assertIsDisplayed()
        for (index in all.indices) rule.onNodeWithTag("dock-tab-$index").assertIsDisplayed()
        rule.runOnIdle {
            val view = rule.activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            val file = File("build/outputs/compact-home/ui4-dock-dark-narrow.png")
            file.parentFile?.mkdirs()
            file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            bitmap.recycle()
        }
    }
}
''')

# Validate the complete allowlist before writing any source.
Path('integration-evidence').mkdir(exist_ok=True)
for path, text in changes.items():
    if not path.startswith('android-app/'):
        raise RuntimeError('Unexpected output path: ' + path)
    Path(path).parent.mkdir(parents=True, exist_ok=True)
    Path(path).write_text(text, encoding='utf-8')
Path('integration-evidence/ui4-changed-paths.json').write_text(json.dumps(list(changes), indent=2))
Path('integration-evidence/ui4-source-hashes.json').write_text(json.dumps({p: hashlib.sha256(Path(p).read_bytes()).hexdigest() for p in changes}, indent=2))
print('Applied presentation-only UI4 changes:', len(changes))
