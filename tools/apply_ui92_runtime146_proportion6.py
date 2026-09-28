#!/usr/bin/env python3
"""One-time, exact-base UI5 -> UI6 migration. Never run over later application edits."""
from pathlib import Path
import json, subprocess
BASE = '60e5d1a18a288d1df1fc14a61918f6771c941eb2'
ROOT = 'android-app/app/src/main/java/io/github/xgl34222220/hetu/'
TEST = 'android-app/app/src/test/java/io/github/xgl34222220/hetu/'
changes = {}
def original(path):
    text = subprocess.check_output(['git','show', BASE + ':' + path]).decode()
    assert Path(path).read_text() == text, 'Refuse to overwrite newer work: ' + path
    return text
def replace(text, old, new, count=1):
    assert text.count(old) == count, (old[:150], text.count(old), count)
    return text.replace(old, new)
def section(text, start, end, new):
    a = text.index(start); b = text.index(end,a)
    return text[:a] + new + text[b:]

# Native continuous corners: a vector outline, not a RuntimeShader-dependent mask.
changes[ROOT + 'HomeProportions.kt'] = '''package io.github.xgl34222220.hetu

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.CornerRounding
import androidx.graphics.shapes.RoundedPolygon
import androidx.graphics.shapes.toPath

/** Actual dock occupancy, including its gesture inset and external bottom margin. */
internal val LocalHomeDockClearance = staticCompositionLocalOf<Dp?> { null }
internal val LocalHomeCompactSpacing = staticCompositionLocalOf { false }
internal data class HomeGridRows(val heading: Dp, val gap: Dp, val first: Dp, val second: Dp) {
    val height: Dp get() = 28.dp + heading + gap + first + 4.dp + second + 14.dp
}
internal val LocalHomeGridRows = staticCompositionLocalOf { HomeGridRows(32.dp, 8.dp, 22.dp, 22.dp) }

/** Smooth circular arcs with continuous cubic flank transitions; works on software Canvas too. */
internal data class HomeContinuousShape(val radius: Dp = 28.dp) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        if (size.width <= 0f || size.height <= 0f) return Outline.Rectangle(androidx.compose.ui.geometry.Rect(0f,0f,size.width,size.height))
        val r = with(density) { radius.toPx() }.coerceIn(0f, size.minDimension / 2f)
        return Outline.Generic(RoundedPolygon(
            vertices = floatArrayOf(0f,0f,size.width,0f,size.width,size.height,0f,size.height),
            rounding = CornerRounding(r, smoothing = .6f),
        ).toPath().asComposePath())
    }
}
'''

p=ROOT+'CompactHomeDashboard.kt'; s=original(p)
s=s.replace('Color(0xFFF6F8FC)', 'Color(0xFFEFEBF8)')
s=replace(s,'val shape = RoundedCornerShape(22.dp)','val shape = HomeContinuousShape(28.dp)')
s=replace(s,'lineHeight = (size + 5).sp','lineHeight = (size + 6).sp')
s=replace(s, 'Column(Modifier.fillMaxSize().windowInsetsPadding(\n            contentInsets.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))) {', '''val clearance = LocalHomeDockClearance.current ?: (86.dp + contentInsets.asPaddingValues().calculateBottomPadding())
        Column(Modifier.fillMaxSize().padding(bottom = clearance).windowInsetsPadding(
            contentInsets.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))) {''')
s=replace(s,'.heightIn(min = 56.dp).padding(horizontal = 16.dp)', '.heightIn(min = 48.dp).padding(horizontal = 16.dp)')
s=replace(s, '        LazyColumn(Modifier.fillMaxWidth().weight(1f).clipToBounds().testTag("compact-home"),', '''        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
        // Short windows reduce spacing, never the user's font size or address content.
        val compact = maxHeight < 710.dp
        CompositionLocalProvider(LocalHomeCompactSpacing provides compact) {
        LazyColumn(Modifier.fillMaxSize().clipToBounds().testTag("compact-home"),''')
s=replace(s, '''contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp,
                bottom = contentInsets.asPaddingValues().calculateBottomPadding() + 94.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp))''', '''contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 0.dp),
            verticalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 12.dp))''')
s=replace(s, '''            item("network") { HomePair(
                left = { HomeNetwork(data, it) },
                right = { HomeSpeed(data, it, onConnections) }) }
            item("resources") { HomePair(
                left = { HomeSubscription(data, it, onSubscription) },
                right = { HomeResources(data, it) }) }
        }
        }
        SnackbarHost''', '''            item("telemetry") { HomeTelemetryGrid(data, onConnections, onSubscription) }
        }
        }
        }
        }
        SnackbarHost''')
s=replace(s, '''Column(Modifier.fillMaxWidth().heightIn(min = 72.dp).padding(16.dp), verticalArrangement = Arrangement.Center)''', '''Column(Modifier.fillMaxWidth().heightIn(min = if (LocalHomeCompactSpacing.current) 60.dp else 72.dp)
            .padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.Center)''')
s=replace(s,'if (index > 0) Box(Modifier.width(1.dp).height(34.dp).background(p.line))', '''if (index > 0) Box(Modifier.width(1.dp).height(24.dp)
                        .testTag("latency-divider-$index")
                        .background(if (p.page.luminance() < .5f) Color.White.copy(alpha = .10f) else Color.Black.copy(alpha = .06f)))''')
s=section(s, '/** Equal-height pairs without fixed text heights.', '@Composable\nprivate fun HomeDataHeading', '''/** One shared grid: every tile uses the same measured row geometry, not independent heights. */
@Composable
private fun HomeTelemetryGrid(data: CompactHomeData, connections: () -> Unit, subscription: () -> Unit) {
    val density = LocalDensity.current
    val measurer = androidx.compose.ui.text.rememberTextMeasurer()
    val textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Default, fontWeight = FontWeight.Bold,
        fontSize = 16.sp, lineHeight = 22.sp, letterSpacing = (-.5).sp, fontFeatureSettings = "tnum")
    BoxWithConstraints(Modifier.fillMaxWidth().testTag("home-telemetry-grid")) {
        val single = maxWidth / density.fontScale < 290.dp
        val cellWidth = if (single) maxWidth else (maxWidth - 12.dp) / 2
        val addressWidth = with(density) { (cellWidth - 28.dp - 22.dp).roundToPx() }.coerceAtLeast(1)
        val regionWidth = with(density) { (cellWidth - 28.dp - 54.dp).roundToPx() }.coerceAtLeast(1)
        fun measuredHeight(value: String, width: Int, style: androidx.compose.ui.text.TextStyle, maxLines: Int = Int.MAX_VALUE): androidx.compose.ui.unit.Dp {
            val result = measurer.measure(androidx.compose.ui.text.AnnotatedString(value.ifBlank { "—" }),
                style = style, constraints = androidx.compose.ui.unit.Constraints(maxWidth = width), maxLines = maxLines)
            return with(density) { result.size.height.toDp() }
        }
        val line = with(density) { 22.sp.toDp() }
        val heading = maxOf(32.dp, with(density) { 21.sp.toDp() })
        val first = maxOf(line, measuredHeight(data.wan,addressWidth,textStyle), measuredHeight(data.lan,addressWidth,textStyle))
        val second = maxOf(line,
            measuredHeight(data.region,regionWidth,textStyle.copy(fontSize=14.sp,lineHeight=20.sp,letterSpacing=0.sp),2),
            measuredHeight(data.lanInterface,regionWidth,textStyle.copy(fontSize=14.sp,lineHeight=20.sp,letterSpacing=0.sp),2))
        val rows = HomeGridRows(heading, maxOf(0.dp,(48.dp-heading)/2), first, second)
        CompositionLocalProvider(LocalHomeGridRows provides rows) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (single) {
                    HomeNetwork(data,Modifier.fillMaxWidth().height(rows.height))
                    HomeSpeed(data,Modifier.fillMaxWidth().height(rows.height),connections)
                    HomeSubscription(data,Modifier.fillMaxWidth().height(rows.height),subscription)
                    HomeResources(data,Modifier.fillMaxWidth().height(rows.height))
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        HomeNetwork(data,Modifier.weight(1f).height(rows.height))
                        HomeSpeed(data,Modifier.weight(1f).height(rows.height),connections)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        HomeSubscription(data,Modifier.weight(1f).height(rows.height),subscription)
                        HomeResources(data,Modifier.weight(1f).height(rows.height))
                    }
                }
            }
        }
    }
}

''')
s=replace(s, 'Row(modifier.fillMaxWidth().heightIn(min = 32.dp), verticalAlignment = Alignment.CenterVertically)', 'Row(modifier.fillMaxWidth().height(LocalHomeGridRows.current.heading), verticalAlignment = Alignment.CenterVertically)')
s=replace(s, 'Box(Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)', 'Box(Modifier.requiredSize(48.dp)')
s=replace(s, 'Column(Modifier.fillMaxSize().padding(14.dp), verticalArrangement = Arrangement.SpaceBetween)', 'Column(Modifier.fillMaxSize().padding(14.dp))',4)
s=replace(s, '''            // The same body exits, swaps at zero opacity, then enters. No dual IP layers.''', '''            Spacer(Modifier.height(LocalHomeGridRows.current.gap))
            // The same body exits, swaps at zero opacity, then enters. No dual IP layers.''')
s=section(s, '''                    // Put the address on its own full-width line.''', '''                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {''', '''                    Row(Modifier.fillMaxWidth().height(LocalHomeGridRows.current.first)) {
                        HomeLabel("IP", Modifier.width(18.dp).alignByBaseline().testTag("home-label-IP"))
                        Spacer(Modifier.width(4.dp))
                        Text((if (shownLan) data.lan else data.wan).ifBlank { "—" },
                            Modifier.weight(1f).alignByBaseline().testTag("home-network-ip"), color = p.text,
                            fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Default, letterSpacing = (-.5).sp,
                            softWrap = true, overflow = TextOverflow.Clip,
                            style = LocalTextStyle.current.copy(fontFeatureSettings = "tnum"))
                    }
''')
s=replace(s, '''                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        HomeLabel(if (shownLan)''', '''                    Row(Modifier.fillMaxWidth().height(LocalHomeGridRows.current.second), verticalAlignment = Alignment.Top) {
                        HomeLabel(if (shownLan)''')
s=replace(s, '''                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
    if (details)''', '''                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
        }
    }
    if (details)''')
s=replace(s, '''private fun MetricLine(label: String, value: String, color: Color = LocalHomePalette.current.text) {
    Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        HomeLabel(label, Modifier.alignByBaseline())''', '''private fun MetricLine(label: String, value: String, color: Color = LocalHomePalette.current.text) {
    val rows = LocalHomeGridRows.current
    val first = label in listOf("上行", "已用", "内存")
    Row(Modifier.fillMaxWidth().padding(top = if (first) 0.dp else 4.dp)
        .height(if (first) rows.first else rows.second)) {
        HomeLabel(label, Modifier.alignByBaseline().testTag("home-label-$label"))''')
s=replace(s, '''            HomeDataHeading("网速", Modifier.heightIn(min = 48.dp))
            Column {''', '''            HomeDataHeading("网速")
            Spacer(Modifier.height(LocalHomeGridRows.current.gap))
            Column {''')
s=replace(s, '''                MetricLine("下行", if (data.running) CompactHomeFormat.bytes(down) + "/s" else "—")
            }
''', '''                MetricLine("下行", if (data.running) CompactHomeFormat.bytes(down) + "/s" else "—")
                Spacer(Modifier.height(14.dp))
            }
''')
s=replace(s, '''                    fontWeight = FontWeight.Bold, maxLines = 1)
            }
            Column {''', '''                    fontWeight = FontWeight.Bold, maxLines = 1)
            }
            Spacer(Modifier.height(LocalHomeGridRows.current.gap))
            Column {''')
s=replace(s, '''            HomeDataHeading("资源占用")
            Column {''', '''            HomeDataHeading("资源占用")
            Spacer(Modifier.height(LocalHomeGridRows.current.gap))
            Column {''')
changes[p]=s

p=ROOT+'NativeHomePolish.kt'; s=original(p)
s=s.replace('Color(0xFF0F172A).copy(alpha = .02f)', 'Color(0xFF505AA0).copy(alpha = .02f)')
s=s.replace('Color(0xFF1E293B).copy(alpha = .045f)', 'Color(0xFF505AA0).copy(alpha = .05f)')
s=replace(s,'listOf(Color(0xFFEEF2FF), Color(0xFFE0E7FE), Color(0xFF2563EB))','listOf(Color(0xFFE6E1FF), Color(0xFFECE7FE), Color(0xFF2563EB))')
s=replace(s,'val shape = RoundedCornerShape(26.dp)','val shape = HomeContinuousShape(28.dp)\n    val compact = LocalHomeCompactSpacing.current')
s=replace(s,'Column(Modifier.fillMaxWidth().padding(20.dp)) {','Column(Modifier.fillMaxWidth().padding(if (compact) 16.dp else 20.dp)) {')
s=replace(s,'Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {','Row(Modifier.fillMaxWidth().testTag("hero-information"), verticalAlignment = Alignment.CenterVertically) {')
s=replace(s,'Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp))','Column(Modifier.weight(1f).testTag("hero-information-text"), verticalArrangement = Arrangement.spacedBy(3.dp))')
s=replace(s,'Spacer(Modifier.height(18.dp))','Spacer(Modifier.height(if (compact) 12.dp else 16.dp))')
s=replace(s,'Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))','Row(Modifier.fillMaxWidth().testTag("hero-capsule-surface").clip(HomeContinuousShape(20.dp))')
s=replace(s,'Color.White.copy(alpha = .88f)','Color.White.copy(alpha = .95f)')
s=replace(s,'56.dp else 80.dp','56.dp else 84.dp')
changes[p]=s

# Measure the entire Dock wrapper (includes gesture inset and floating margin), then reserve it.
p=ROOT+'ReferenceProxyActivity.kt'; s=original(p)
s=replace(s,'import androidx.compose.ui.layout.ContentScale','import androidx.compose.ui.layout.ContentScale\nimport androidx.compose.ui.layout.onSizeChanged\nimport androidx.compose.ui.platform.LocalDensity')
s=s.replace('Color(0xFFF6F8FC)','Color(0xFFEFEBF8)')
s=replace(s, '''    Box(Modifier.fillMaxSize().background(shellBackground)) {''', '''    val dockDensity = LocalDensity.current
    val initialDockHeight = 76.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    var measuredDockHeight by remember { mutableStateOf(initialDockHeight) }
    CompositionLocalProvider(LocalHomeDockClearance provides (measuredDockHeight + 10.dp)) {
    Box(Modifier.fillMaxSize().background(shellBackground)) {''')
s=replace(s, '''                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }

    logText?.let''', '''                modifier = Modifier.align(Alignment.BottomCenter).onSizeChanged { size ->
                    measuredDockHeight = with(dockDensity) { size.height.toDp() }
                },
            )
        }
    }
    }

    logText?.let''')
changes[p]=s

p=ROOT+'ui/HetuGlassDock.kt';s=original(p)
s=replace(s,'bottomInset + 16.dp','bottomInset + 12.dp')
s=replace(s, '''indicatorColor = if (dark) Color(0xFF2563EB).copy(alpha = .18f) else Color(0xFF2563EB).copy(alpha = .08f),''', '''indicatorColor = if (dark) Color(0xFF233D64) else Color(0xFFE7F1FF),''')
s=replace(s,'selectedColor = scheme.primary,','selectedColor = if (dark) Color(0xFF8AB4FF) else Color(0xFF2563EB),')
s=replace(s,'.clip(RoundedCornerShape(26.dp)).background(fill)', '''.clip(RoundedCornerShape(26.dp))
                    // Fill is on the foreground sibling, outside every backdrop/shader layer.
                    .drawBehind {
                        val inset = 6.dp.toPx()
                        val h = (size.height - 2 * inset).coerceAtLeast(0f)
                        drawRoundRect(fill, topLeft = Offset(0f,inset),
                            size = androidx.compose.ui.geometry.Size(size.width,h), cornerRadius = CornerRadius(h/2f))
                    }''')
changes[p]=s

for p in [ROOT+'ui/HetuTheme.kt','android-app/app/src/main/res/values/styles.xml']:
    s=original(p)
    assert 'F6F8FC' in s
    changes[p]=s.replace('F6F8FC','EFEBF8')
p='android-app/app/build.gradle.kts'; s=original(p)
s=replace(s,'versionCode = 1006','versionCode = 1007')
s=replace(s,'versionName = "0.4.0-ui92-r146.5"','versionName = "0.4.0-ui92-r146.6"')
s=replace(s,'implementation("androidx.compose.foundation:foundation")','implementation("androidx.compose.foundation:foundation")\n    implementation("androidx.graphics:graphics-shapes:1.0.1")')
changes[p]=s
# Only this older size assertion changes because the new specification explicitly asks for 84dp.
p=TEST+'CompactHomeDashboardTest.kt';s=original(p)
s=replace(s,'.assertWidthIsEqualTo(80.dp).assertHeightIsEqualTo(80.dp)', '.assertWidthIsEqualTo(84.dp).assertHeightIsEqualTo(84.dp)')
changes[p]=s

changes[TEST+'HomeUi6RegressionTest.kt']='''package io.github.xgl34222220.hetu

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.*
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
@Config(sdk=[35],qualifiers="w393dp-h900dp-mdpi",application=Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HomeUi6RegressionTest {
    @get:Rule val rule=createAndroidComposeRule<ComponentActivity>()
    private val calls=mutableListOf<String>()
    private val tags=listOf("home-network","home-speed","home-subscription","home-resources")
    private fun fixture()=CompactHomeData(running=true,core="Mihomo",mode="TPROXY",config="fixture.yaml",
        uptimeSeconds=31860,wan="203.0.113.42",lan="192.168.2.85",region="测试地区",countryCode="JP",lanInterface="wlan0",
        up=1200,down=864000,used=196200000000,total=692400000000,memory=84000000,cpu=2.6f,
        delays=mapOf("Baidu" to 44L,"Cloudflare" to 321L,"Google" to 114L))
    private fun render(data:CompactHomeData=fixture(),scale:Float=1f,dark:Boolean=false) {
        // Software snapshots exercise layout, path corners and foreground pill, not GPU glass.
        val prefs=ApplicationProvider.getApplicationContext<Application>().getSharedPreferences("hetu",0)
        prefs.edit().putBoolean("floatingBottomBar",true).putBoolean("enableBlur",false).putBoolean("liquidGlass",false).commit()
        rule.setContent {
            val d=LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(d.density,scale),LocalHetuMotionEnabled provides false) {
                MaterialTheme(colorScheme=if(dark) darkColorScheme() else lightColorScheme()) {
                    val density=LocalDensity.current
                    var dockHeight by remember { mutableStateOf(76.dp) }
                    var selected by remember { mutableIntStateOf(0) }
                    Box(Modifier.fillMaxSize().background(if(dark) Color(0xFF121212) else Color(0xFFEFEBF8))) {
                        CompositionLocalProvider(LocalHomeDockClearance provides (dockHeight+10.dp)) {
                            CompactHomeDashboard(data,{calls+="refresh"},{calls+="toggle"},{calls+="reload"},{calls+="restart"},
                                {calls+="delay"},{calls+="webui"},{calls+="log"},{calls+="subscription"},{calls+="connections"},
                                {calls+="settings"},{calls+="diagnostics"},{calls+="adblock"},motionEnabled=false,
                                contentInsets=WindowInsets(0.dp,24.dp,0.dp,24.dp))
                        }
                        HetuGlassDock(listOf(DockItem("首页",Icons.Rounded.Home),DockItem("面板",Icons.Rounded.Link),
                            DockItem("工具",Icons.Rounded.GridView),DockItem("设置",Icons.Rounded.Settings)),selected,
                            {selected=it;calls+="tab-$it"},remember {HazeState()},null,
                            Modifier.align(Alignment.BottomCenter).onSizeChanged {dockHeight=with(density){it.height.toDp()}})
                    }
                }
            }
        }
    }
    private fun bounds(tag:String)=rule.onNodeWithTag(tag,useUnmergedTree=true).fetchSemanticsNode().boundsInRoot
    private fun text(tag:String):TextLayoutResult {
        val out=mutableListOf<TextLayoutResult>()
        rule.onNodeWithTag(tag,useUnmergedTree=true).performSemanticsAction(SemanticsActions.GetTextLayoutResult){it(out)}
        return out.single()
    }
    private fun baseline(tag:String):Float=bounds(tag).top+text(tag).firstBaseline
    private fun bitmap():Bitmap {
        rule.waitForIdle()
        lateinit var b:Bitmap
        rule.runOnIdle {
            val view=rule.activity.window.decorView
            b=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888)
            view.draw(Canvas(b))
        }
        return b
    }
    private fun snapshot(name:String) {
        val b=bitmap();val out=File("build/outputs/compact-home/$name.png");out.parentFile!!.mkdirs()
        out.outputStream().use {assertTrue(b.compress(Bitmap.CompressFormat.PNG,100,it))};b.recycle()
    }
    @Test fun normalFirstScreenShowsAllFourTilesAboveMeasuredDock() {
        render()
        tags.forEach {rule.onNodeWithTag(it).assertIsDisplayed()}
        val dock=bounds("hetu-dock")
        assertTrue("Entire bottom card, not just its top, must be above Dock: ${bounds("home-resources")} / $dock",
            bounds("home-resources").bottom<=dock.top-9f)
        assertEquals(10f,dock.top-bounds("compact-home").bottom,1f)
        snapshot("ui6-full-screen-with-dock")
    }
    @Test @Config(qualifiers="w393dp-h852dp-mdpi") fun mediumFirstScreenAlsoFitsWithoutScroll() {
        render()
        tags.forEach {rule.onNodeWithTag(it).assertIsDisplayed()}
        assertTrue(bounds("home-resources").bottom<=bounds("hetu-dock").top-9f)
        snapshot("ui6-393x852-with-dock")
    }
    @Test fun allFourCardsHaveIdenticalDimensionsAndTwelveDpGaps() {
        render()
        val b=tags.map(::bounds)
        b.forEach {assertEquals(b.first().height,it.height,1f);assertEquals(b.first().width,it.width,1f)}
        assertEquals(b[0].top,b[1].top,1f);assertEquals(b[2].top,b[3].top,1f)
        assertEquals(12f,b[1].left-b[0].right,1f);assertEquals(12f,b[2].top-b[0].bottom,1f)
    }
    @Test fun ipAndUploadShareTheSameActualTextBaseline() {
        render()
        assertEquals(baseline("home-network-ip"),baseline("home-value-上行"),1f)
        assertEquals(baseline("home-label-IP"),baseline("home-label-上行"),1f)
        assertEquals(baseline("home-value-已用"),baseline("home-value-内存"),1f)
        assertEquals(baseline("home-value-总量"),baseline("home-value-CPU"),1f)
    }
    @Test fun longestIpv4RemainsCompleteAndInline() {
        render(fixture().copy(wan="255.255.255.255"))
        val l=text("home-network-ip")
        assertEquals("255.255.255.255",l.layoutInput.text.text);assertFalse(l.hasVisualOverflow)
        assertEquals(1,l.lineCount);assertEquals(16.sp,l.layoutInput.style.fontSize)
        assertEquals(baseline("home-network-ip"),baseline("home-value-上行"),1f)
        snapshot("ui6-long-ipv4")
    }
    @Test fun balancedHeroUses84DpGlyphAndSubstantialCapsule() {
        render()
        rule.onNodeWithTag("hero-status-glyph").assertWidthIsEqualTo(84.dp).assertHeightIsEqualTo(84.dp)
        assertEquals(bounds("hero-information-text").center.y,bounds("hero-status-glyph").center.y,1f)
        assertTrue(bounds("hero-capsule-surface").height>=60f)
        for(tag in listOf("home-reload","home-toggle","home-restart")) rule.onNodeWithTag(tag).assertHeightIsAtLeast(48.dp)
    }
    @Test fun dividersAreVisible24DpLinesAndDoNotShiftColumns() {
        render()
        for(i in 1..2) rule.onNodeWithTag("latency-divider-$i").assertHeightIsEqualTo(24.dp).assertWidthIsEqualTo(1.dp)
        val a=bounds("latency-Baidu");val b=bounds("latency-Cloudflare");val c=bounds("latency-Google")
        assertTrue(a.right<=b.left && b.right<=c.left)
        val image=bitmap();val pixels=IntArray(image.width*image.height)
        image.getPixels(pixels,0,image.width,0,0,image.width,image.height)
        assertTrue("Separators must render actual gray pixels",pixels.count {it==0xFFF0F0F0.toInt() || it==0xFFEFEFEF.toInt()}>20)
        image.recycle()
    }
    @Test fun lavenderAndCrispForegroundPillAreActuallyPainted() {
        render()
        val b=bitmap();val pixels=IntArray(b.width*b.height)
        b.getPixels(pixels,0,b.width,0,0,b.width,b.height)
        assertTrue(pixels.count {it==0xFFEFEBF8.toInt()}>3000)
        assertTrue("Solid blue pill must not be a radial halo",pixels.count {it==0xFFE7F1FF.toInt()}>1000)
        b.recycle()
        rule.onNodeWithTag("dock-tab-2").performTouchInput{click()}
        rule.onNodeWithTag("dock-active-label").assertTextEquals("工具")
        assertEquals(listOf("tab-2"),calls)
    }
    @Test @Config(qualifiers="w320dp-h640dp-mdpi") fun smallLargeFontWindowScrollsWithoutDockOcclusionOrAddressLoss() {
        val ip="2001:db8:1234:5678:90ab:cdef:1234:5678"
        render(fixture().copy(wan=ip),scale=1.6f)
        rule.onNodeWithTag("compact-home").performScrollToNode(hasTestTag("home-network"))
        assertFalse(text("home-network-ip").hasVisualOverflow)
        assertEquals(ip,text("home-network-ip").layoutInput.text.text)
        rule.onNodeWithTag("compact-home").performScrollToNode(hasTestTag("home-cpu-bar"))
        assertTrue(bounds("home-cpu-bar").bottom<=bounds("hetu-dock").top-9f)
        assertEquals(24f,bounds("home-header").top,1f)
        snapshot("ui6-small-large-font-scrolled")
    }
    @Test fun cpuTwoPointSixUsesTwoPointSixPercentNotTemplateTwentySix() {
        render()
        val p=rule.onNodeWithTag("home-cpu-bar",useUnmergedTree=true).fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo]
        assertEquals(.026f,p.current,.00001f)
        rule.onNodeWithTag("home-reload").performClick();rule.onNodeWithTag("home-webui").performClick()
        assertEquals(listOf("reload","webui"),calls)
    }
    @Test fun darkFullScreenKeepsGeometryAndActualData() {
        render(dark=true)
        assertEquals(bounds("home-network").height,bounds("home-resources").height,1f)
        assertTrue(bounds("home-resources").bottom<=bounds("hetu-dock").top-9f)
        snapshot("ui6-dark-with-dock")
    }
}
'''

for name,text in changes.items():
    p=Path(name);p.parent.mkdir(parents=True,exist_ok=True);p.write_text(text)
Path('integration-evidence').mkdir(exist_ok=True)
Path('integration-evidence/ui6-changed-paths.json').write_text(json.dumps(list(changes),indent=2))
print('UI6 changes prepared:',len(changes))
