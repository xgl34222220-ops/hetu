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
@Config(sdk=[35], qualifiers="w393dp-h852dp-mdpi", application=Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HomeUi10GridTest {
    @get:Rule val rule=createAndroidComposeRule<ComponentActivity>()
    private val calls=mutableListOf<String>()
    private val cards=listOf("home-network","home-speed","home-subscription","home-resources")
    private fun fixture()=CompactHomeData(running=true,core="Mihomo",mode="TPROXY",config="fixture.yaml",
        uptimeSeconds=41640,wan="61.222.202.153",lan="192.168.2.85",region="测试地区",countryCode="JP",lanInterface="wlan0",
        up=0,down=15,used=196200000000,total=692400000000,memory=80400000,cpu=3.3f,
        delays=mapOf("Baidu" to 36L,"Cloudflare" to 194L,"Google" to 95L))
    private fun render(data:MutableState<CompactHomeData> = mutableStateOf(fixture()),scale:Float=1f,dark:Boolean=false) {
        ApplicationProvider.getApplicationContext<Application>().getSharedPreferences("hetu",0).edit()
            .putBoolean("floatingBottomBar",true).putBoolean("enableBlur",false).putBoolean("liquidGlass",false).commit()
        rule.setContent {
            val d=LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(d.density,scale),LocalHetuMotionEnabled provides false) {
                MaterialTheme(colorScheme=if(dark) darkColorScheme() else lightColorScheme()) {
                    val density=LocalDensity.current
                    var dockHeight by remember { mutableStateOf(72.dp) }
                    var selected by remember { mutableIntStateOf(0) }
                    Box(Modifier.fillMaxSize().background(if(dark) Color(0xFF121212) else Color(0xFFF4F6FB))) {
                        CompositionLocalProvider(LocalHomeDockClearance provides (dockHeight+10.dp)) {
                            CompactHomeDashboard(data.value,{calls+="refresh"},{calls+="toggle"},{calls+="reload"},
                                {calls+="restart"},{calls+="delay"},{calls+="webui"},{calls+="log"},{calls+="subscription"},
                                {calls+="connections"},{calls+="settings"},{calls+="diagnostics"},{calls+="adblock"},
                                motionEnabled=false,contentInsets=WindowInsets(0.dp,44.dp,0.dp,24.dp))
                        }
                        HetuGlassDock(listOf(DockItem("首页",Icons.Rounded.Home),DockItem("面板",Icons.Rounded.Link),
                            DockItem("工具",Icons.Rounded.GridView),DockItem("设置",Icons.Rounded.Settings)),selected,
                            {selected=it;calls+="tab-$it"},remember{HazeState()},null,
                            Modifier.align(Alignment.BottomCenter).onSizeChanged{dockHeight=with(density){it.height.toDp()}})
                    }
                }
            }
        }
    }
    private fun node(tag:String)=rule.onNodeWithTag(tag,useUnmergedTree=true)
    private fun bounds(tag:String)=node(tag).getUnclippedBoundsInRoot()
    private fun text(tag:String):TextLayoutResult {
        val out=mutableListOf<TextLayoutResult>()
        node(tag).performSemanticsAction(SemanticsActions.GetTextLayoutResult){it(out)}
        return out.single()
    }
    private fun visibleIpv4(ip:String) {
        val l=text("home-network-ip")
        val n=node("home-network-ip").fetchSemanticsNode()
        val range=n.config[SemanticsProperties.HorizontalScrollAxisRange]
        assertEquals(ip,l.layoutInput.text.text)
        assertEquals(1,l.lineCount);assertFalse(l.hasVisualOverflow)
        assertEquals("An intact hidden paragraph is not a visible IP",0f,range.maxValue(),.5f)
        assertEquals(0f,range.value(),.01f)
        val last=l.getBoundingBox(ip.lastIndex)
        assertTrue("Last glyph $last exceeds viewport ${n.boundsInRoot}",last.right<=n.boundsInRoot.width+.5f)
        assertTrue(l.getBoundingBox(0).left>=-.5f)
        assertEquals(14.sp,l.layoutInput.style.fontSize)
    }
    private fun firstScreen() {
        snapshot("ui10-pre-assert-${bounds("home-viewport").width.value.toInt()}")
        cards.forEach{node(it).assertIsDisplayed()}
        val dock=bounds("hetu-dock");val view=bounds("compact-home")
        cards.forEach{assertTrue("$it ${bounds(it)} / $dock / $view",bounds(it).bottom<=view.bottom)}
        assertEquals(10f,(dock.top-view.bottom).value,1f)
        assertTrue(bounds("home-cpu-bar").bottom<dock.top-9.dp)
        assertTrue(bounds("home-subscription-bar").bottom<dock.top-9.dp)
    }
    private fun snapshot(name:String) {
        rule.runOnIdle {
            val v=rule.activity.window.decorView
            val b=Bitmap.createBitmap(v.width,v.height,Bitmap.Config.ARGB_8888)
            v.draw(Canvas(b));val f=File("build/outputs/compact-home/$name.png");f.parentFile!!.mkdirs()
            f.outputStream().use{assertTrue(b.compress(Bitmap.CompressFormat.PNG,100,it))};b.recycle()
        }
    }
    @Test fun reportedAddressIsFullyVisibleWithoutAnyHorizontalScroll() {
        render();visibleIpv4("61.222.202.153");firstScreen();snapshot("ui10-full-home-with-real-dock")
    }
    @Test fun priorOrphanDigitCaseIsAlsoFullyVisible() {
        render(mutableStateOf(fixture().copy(wan="123.193.18.254")));visibleIpv4("123.193.18.254")
    }
    @Test fun longestIpv4UsesVisibleWidthNotHiddenTail() {
        render(mutableStateOf(fixture().copy(wan="255.255.255.255")));visibleIpv4("255.255.255.255");firstScreen()
    }
    @Test @Config(qualifiers="w393dp-h852dp-xxhdpi") fun physicalHighDensityStillShowsTheLastGlyph() {
        render();visibleIpv4("61.222.202.153");firstScreen();snapshot("ui10-xxhdpi")
    }
    @Test @Config(qualifiers="w360dp-h800dp-mdpi") fun smallerNormalPhoneFitsAboveActualDock() {
        render();visibleIpv4("61.222.202.153");firstScreen();snapshot("ui10-360x800")
    }
    @Test fun fourCardsHaveIndependentPaintAndTenDpGutters() {
        render()
        val boxes=cards.map(::bounds)
        boxes.forEach{assertEquals(boxes[0].height.value,it.height.value,1f);assertEquals(boxes[0].width.value,it.width.value,1f)}
        assertEquals(10f,(boxes[1].left-boxes[0].right).value,1f)
        assertEquals(10f,(boxes[2].top-boxes[0].bottom).value,1f)
        node("home-network-group-divider").assertDoesNotExist();node("home-health-group-divider").assertDoesNotExist()
        rule.runOnIdle {
            val v=rule.activity.window.decorView;val b=Bitmap.createBitmap(v.width,v.height,Bitmap.Config.ARGB_8888);v.draw(Canvas(b))
            val x=((boxes[0].right+boxes[1].left)/2).value.toInt()
            val y=((boxes[0].top+boxes[0].bottom)/2).value.toInt()
            assertEquals("Independent cards expose the page between them",0xFFF4F6FB.toInt(),b.getPixel(x,y))
            b.recycle()
        }
    }
    @Test fun simpleShortcutTilesKeepBothRealCallbacks() {
        render();node("home-webui").performTouchInput{click()};node("home-log").performTouchInput{click()}
        assertEquals(listOf("webui","log"),calls)
        assertEquals(10f,(bounds("home-log").left-bounds("home-webui").right).value,1f)
        assertTrue(bounds("home-webui").height>=48.dp)
    }
    @Test fun centeredTitleAndRealToolbarStayAboveContent() {
        render()
        val h=bounds("home-header");val brand=bounds("home-brand")
        // V19: expanded state is the reference's large leading title (collapses to centre on scroll).
        assertEquals(32.sp,text("home-brand").layoutInput.style.fontSize)
        assertEquals((h.left + 16.dp).value, brand.left.value, 1f)
        assertEquals(44.dp,h.top)
        rule.onNodeWithTag("compact-home").refreshHome11()
        assertEquals(listOf("refresh"),calls)
    }
    @Test fun noticeStartsEightDpBelowSafeAreaAndDoesNotBlockStop() {
        render(mutableStateOf(fixture().copy(message="全部刷新完成")))
        assertEquals(52f,bounds("home-feedback-pill").top.value,1f)
        assertTrue(bounds("home-feedback-pill").bottom<=bounds("home-header").top)
        node("home-toggle").performTouchInput{click()};assertEquals(listOf("toggle"),calls)
        node("native-details-sheet").assertDoesNotExist();snapshot("ui10-notice-and-stop")
    }
    @Test fun noticeRemovalReclaimsAllOfItsLane() {
        val data=mutableStateOf(fixture());render(data)
        val before=bounds("home-header")
        rule.runOnIdle{data.value=data.value.copy(message="全部刷新完成")}
        assertTrue(bounds("home-header").top>before.top)
        rule.runOnIdle{data.value=data.value.copy(message="")}
        assertEquals(before,bounds("home-header"));firstScreen()
    }
    @Test fun detailTapIsolatedAndCardTapStillSwitches() {
        render();node("home-network-details").performTouchInput{click()};node("sheet-confirm").performClick()
        node("home-network").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,"WAN"))
        node("home-network").performTouchInput{click()}
        node("home-network").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,"LAN"))
        visibleIpv4("192.168.2.85")
    }
    @Test @Config(qualifiers="w320dp-h640dp-mdpi") fun accessibilityKeepsDataAndScrollWithoutChangingToFullWidthCards() {
        render(scale=1.6f)
        node("compact-home").performScrollToNode(hasTestTag("home-network"))
        assertEquals(1,text("home-network-ip").lineCount);assertFalse(text("home-network-ip").hasVisualOverflow)
        assertEquals(14.sp,text("home-network-ip").layoutInput.style.fontSize)
        assertTrue(bounds("home-network").width<160.dp)
        node("compact-home").performScrollToNode(hasTestTag("home-cpu-bar"))
        assertTrue(bounds("home-cpu-bar").bottom<bounds("hetu-dock").top)
        snapshot("ui10-large-text")
    }
    @Test fun trueUsageAndCpuRemainIndependentOfTemplateFixtures() {
        render()
        val cpu=node("home-cpu-bar").fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo]
        val sub=node("home-subscription-bar").fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo]
        assertEquals(.033f,cpu.current,.00001f);assertEquals(1962f/6924f,sub.current,.00001f)
        assertEquals(bounds("home-cpu-bar").bottom,bounds("home-subscription-bar").bottom)
    }
    @Test fun darkModePreservesFourCardGeometryAndTouchTargets() {
        render(dark=true);firstScreen();visibleIpv4("61.222.202.153")
        node("dock-tab-0").assertHeightIsAtLeast(48.dp);node("home-toggle").assertHeightIsAtLeast(48.dp)
        snapshot("ui10-dark-with-dock")
    }
}
