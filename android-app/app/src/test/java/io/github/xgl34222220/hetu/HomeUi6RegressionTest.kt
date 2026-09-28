package io.github.xgl34222220.hetu

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
    private fun bounds(tag:String):androidx.compose.ui.geometry.Rect {
        // assertIsDisplayed/boundsInRoot alone can accept an only-partially-visible card.
        // All test windows use mdpi, so these unclipped Dp coordinates equal physical pixels.
        val b=rule.onNodeWithTag(tag,useUnmergedTree=true).getUnclippedBoundsInRoot()
        return androidx.compose.ui.geometry.Rect(b.left.value,b.top.value,b.right.value,b.bottom.value)
    }
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
        snapshot("ui6-first-screen-before-assertions")
        println("UNCLIPPED normal viewport=${bounds("compact-home")} cards=${tags.map(::bounds)} dock=${bounds("hetu-dock")}")
        val dock=bounds("hetu-dock")
        assertTrue("Entire bottom card, not just its top, must be above Dock: ${bounds("home-resources")} / $dock",
            bounds("home-resources").bottom<=dock.top-9f)
        assertEquals(10f,dock.top-bounds("compact-home").bottom,1f)
        snapshot("ui6-full-screen-with-dock")
    }
    @Test @Config(qualifiers="w393dp-h852dp-mdpi") fun mediumFirstScreenAlsoFitsWithoutScroll() {
        render()
        tags.forEach {rule.onNodeWithTag(it).assertIsDisplayed()}
        snapshot("ui6-medium-before-assertions")
        println("UNCLIPPED medium viewport=${bounds("compact-home")} cards=${tags.map(::bounds)} dock=${bounds("hetu-dock")}")
        assertTrue("Full card ${bounds("home-resources")} must clear dock ${bounds("hetu-dock")}",bounds("home-resources").bottom<=bounds("hetu-dock").top-9f)
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
        assertEquals(baseline("home-value-network-region"),baseline("home-value-下行"),1f)
        assertEquals(baseline("home-label-network-region"),baseline("home-label-下行"),1f)
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
        rule.onNodeWithTag("dock-active-label",useUnmergedTree=true).assertTextEquals("工具")
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
