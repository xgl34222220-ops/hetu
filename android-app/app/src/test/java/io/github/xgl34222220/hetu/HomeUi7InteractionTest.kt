package io.github.xgl34222220.hetu

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.*
import dev.chrisbanes.haze.HazeState
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
@Config(sdk=[35], qualifiers="w393dp-h900dp-mdpi", application=Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HomeUi7InteractionTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val calls = mutableListOf<String>()
    private fun fixture() = CompactHomeData(running=true, core="Mihomo", mode="TPROXY", config="fixture.yaml",
        wan="203.0.113.42",lan="192.168.2.8",region="测试地区",countryCode="JP",lanInterface="wlan0",
        cpu=2.6f, memory=90000000, up=1300, down=864000, used=25,total=100,
        delays=mapOf("Baidu" to 44L,"Cloudflare" to 321L,"Google" to 114L))
    private fun render(data: CompactHomeData = fixture(), height: Dp? = null, scale: Float = 1f) {
        rule.setContent {
            val d=LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(d.density,scale),LocalHetuMotionEnabled provides false) {
                MaterialTheme {
                    Box(if(height==null) Modifier.fillMaxSize() else Modifier.fillMaxWidth().height(height)) {
                        CompactHomeDashboard(data,{calls+="refresh"},{calls+="toggle"},{calls+="reload"},{calls+="restart"},
                            {calls+="delay"},{calls+="webui"},{calls+="log"},{calls+="subscription"},{calls+="connections"},
                            {calls+="settings"},{calls+="diagnostics"},{calls+="adblock"},motionEnabled=false,
                            contentInsets=WindowInsets(0.dp,24.dp,0.dp,24.dp))
                    }
                }
            }
        }
    }
    private fun layout(tag: String): TextLayoutResult {
        val r=mutableListOf<TextLayoutResult>()
        rule.onNodeWithTag(tag,useUnmergedTree=true).performSemanticsAction(SemanticsActions.GetTextLayoutResult){it(r)}
        return r.single()
    }
    private fun snapshot(name:String) {
        rule.waitForIdle()
        rule.runOnIdle {
            val v=rule.activity.window.decorView
            val b=Bitmap.createBitmap(v.width,v.height,Bitmap.Config.ARGB_8888)
            v.draw(Canvas(b))
            val f=File("build/outputs/compact-home/$name.png");f.parentFile!!.mkdirs()
            f.outputStream().use {assertTrue(b.compress(Bitmap.CompressFormat.PNG,100,it))};b.recycle()
        }
    }
    @Test fun shortcutsFollowHeroInTheLatestCompactTileStrip() {
        render()
        rule.onNodeWithTag("home-webui").assert(hasAnyAncestor(hasTestTag("home-shortcut-strip")))
        val strip = rule.onNodeWithTag("home-shortcut-strip").fetchSemanticsNode().boundsInRoot
        val hero = rule.onNodeWithTag("home-hero").fetchSemanticsNode().boundsInRoot
        assertTrue(strip.top >= hero.bottom)
        rule.onNodeWithTag("home-log").assert(hasAnyAncestor(hasTestTag("home-shortcut-strip")))
        rule.onNodeWithTag("home-webui").assertHeightIsAtLeast(48.dp).performTouchInput{click()}
        rule.onNodeWithTag("home-log").performTouchInput{click()}
        assertEquals(listOf("webui","log"),calls)
        snapshot("ui7-grouped-home")
    }
    @Test fun sharedNetworkSurfacePreservesIndependentTapOwnership() {
        render()
        rule.onNodeWithTag("home-network").assert(hasAnyAncestor(hasTestTag("home-network-group")))
        rule.onNodeWithTag("home-speed").assert(hasAnyAncestor(hasTestTag("home-network-group")))
        rule.onNodeWithTag("home-speed").performTouchInput{click()}
        rule.onNodeWithTag("home-network",useUnmergedTree=true).assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,"WAN"))
        rule.onNodeWithTag("home-network").performTouchInput{click(bottomLeft+Offset(12f,-12f))}
        rule.onNodeWithTag("home-network",useUnmergedTree=true).assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,"LAN"))
        assertEquals(listOf("connections"),calls)
    }
    @Test fun singleHeaderMovesFromLeadingLargeTitleToCenteredCompactTitle() {
        val collapsed=mutableFloatStateOf(0f)
        rule.setContent { MaterialTheme {
            HomeCollapsingHeader(collapsed.floatValue,false,remember{HazeState()},Color.Black) {
                Box(Modifier.size(48.dp));Box(Modifier.size(48.dp))
            }
        } }
        val expanded=rule.onNodeWithTag("home-brand").fetchSemanticsNode().boundsInRoot
        assertEquals(16f,expanded.left,1f)
        assertEquals(28.sp,layout("home-brand").layoutInput.style.fontSize)
        rule.runOnIdle{collapsed.floatValue=1f}
        val header=rule.onNodeWithTag("home-header").fetchSemanticsNode().boundsInRoot
        val brand=rule.onNodeWithTag("home-brand").fetchSemanticsNode().boundsInRoot
        assertEquals(header.center.x,brand.center.x,1f)
        assertEquals(20.sp,layout("home-brand").layoutInput.style.fontSize)
        rule.onAllNodesWithTag("home-brand").assertCountEquals(1)
    }
    @Test fun realListScrollCompactsTitleWithoutMovingSafeHeaderOrActions() {
        render(height=480.dp)
        val before=rule.onNodeWithTag("home-header").fetchSemanticsNode().boundsInRoot
        rule.onNodeWithTag("compact-home").performTouchInput{swipeUp()}
        rule.onNodeWithTag("home-header").assert(SemanticsMatcher.expectValue(HomeHeaderCollapse,1f))
        assertEquals(before,rule.onNodeWithTag("home-header").fetchSemanticsNode().boundsInRoot)
        rule.onNodeWithContentDescription("刷新状态").assertDoesNotExist()
        rule.onNodeWithContentDescription("更多首页功能").assertDoesNotExist()
        rule.onNodeWithTag("compact-home").refreshHome11()
        assertEquals(listOf("refresh"),calls)
        snapshot("ui7-collapsed-header")
    }
    @Test fun interruptedNumberRollSettlesToLatestRealValue() {
        val target=mutableStateOf("44 ms")
        lateinit var state:MetricRollState
        rule.setContent {state=rememberMetricRoll(target.value,true);Text(state.displayed,Modifier.testTag("rolled-value"))}
        rule.mainClock.autoAdvance=false
        rule.runOnIdle{target.value="80 ms";Snapshot.sendApplyNotifications()}
        repeat(4){rule.mainClock.advanceTimeByFrame();rule.waitForIdle()}
        assertTrue("Interrupt the outgoing phase, not an already finished value",state.leaving && state.progress.value<1f)
        rule.runOnIdle{target.value="114 ms";Snapshot.sendApplyNotifications()}
        repeat(32){rule.mainClock.advanceTimeByFrame();rule.waitForIdle()}
        rule.onNodeWithTag("rolled-value").assertTextEquals("114 ms")
        rule.onAllNodesWithTag("rolled-value").assertCountEquals(1)
        assertEquals(1f,state.progress.value,0f)
        rule.mainClock.autoAdvance=true
    }
    @Test fun reducedMotionNumberDoesNotWaitForAnimation() {
        val target=mutableStateOf("1.1 KB/s")
        lateinit var state:MetricRollState
        rule.setContent {state=rememberMetricRoll(target.value,false);Text(state.displayed,Modifier.testTag("rolled-value"))}
        rule.runOnIdle{target.value="843 B/s"}
        rule.onNodeWithTag("rolled-value").assertTextEquals("843 B/s")
        assertEquals(1f,state.progress.value,0f)
    }
    @Test fun perspectiveHasOpposingOutInAnglesAndNoRestingTilt() {
        assertEquals(-12f,networkPerspective(0f,true),0f)
        assertEquals(12f,networkPerspective(0f,false),0f)
        assertEquals(0f,networkPerspective(1f,true),0f)
        assertEquals(0f,networkPerspective(1f,false),0f)
    }
    private fun renderNetworkSheet() {
        rule.setContent {MaterialTheme {
            var shown by remember{mutableStateOf(true)}
            if(shown) NativeNetworkDetails(fixture()){shown=false;calls+="dismiss"}
        }}
        rule.waitForIdle()
    }
    @Test fun networkHandleFollowsLongSlowDragAndDismisses() {
        renderNetworkSheet()
        rule.onNodeWithTag("sheet-drag-handle",useUnmergedTree=true).assertHeightIsAtLeast(48.dp)
            .performTouchInput{swipe(center,center+Offset(0f,210f),900)}
        rule.waitForIdle()
        rule.onNodeWithTag("native-details-sheet").assertDoesNotExist()
        assertEquals(listOf("dismiss"),calls)
    }
    @Test fun shortNetworkDragSpringsBackRatherThanDismissing() {
        renderNetworkSheet()
        val before=rule.onNodeWithTag("native-details-sheet").fetchSemanticsNode().boundsInRoot.top
        rule.onNodeWithTag("sheet-drag-handle",useUnmergedTree=true).performTouchInput{swipe(center,center+Offset(0f,32f),1000)}
        rule.waitForIdle()
        rule.onNodeWithTag("native-details-sheet").assertIsDisplayed()
        assertEquals(before,rule.onNodeWithTag("native-details-sheet").fetchSemanticsNode().boundsInRoot.top,1f)
        assertTrue(calls.isEmpty())
    }
    private fun renderLog() {
        rule.setContent{MaterialTheme{
            var shown by remember{mutableStateOf(true)}
            if(shown) RefInfoBottomSheet("运行日志",(1..120).joinToString("\n"){"fixture log line $it"},"关闭"){
                shown=false;calls+="dismiss"
            }
        }}
        rule.waitForIdle()
    }
    @Test fun actualLogDrawerUsesTheSameDraggableHandle() {
        renderLog()
        rule.onNodeWithTag("log-drag-handle",useUnmergedTree=true).assertHeightIsAtLeast(48.dp)
            .performTouchInput{swipe(center,center+Offset(0f,210f),900)}
        rule.waitForIdle()
        rule.onNodeWithTag("native-log-sheet").assertDoesNotExist()
        assertEquals(listOf("dismiss"),calls)
    }
    @Test fun scrollingLongLogsDoesNotDismissAndCopyStillWorks() {
        renderLog()
        rule.onNodeWithTag("native-log-sheet").performTouchInput{swipeUp()}
        rule.waitForIdle()
        rule.onNodeWithTag("native-log-sheet").assertIsDisplayed()
        rule.onNodeWithText("复制").performClick()
        rule.onNodeWithText("已复制").assertIsDisplayed()
        assertTrue(calls.isEmpty())
    }
    @Test @Config(qualifiers="w320dp-h640dp-mdpi") fun groupedNetworkKeepsFullIpv6AtLargeFont() {
        val ip="2001:db8:1234:5678:90ab:cdef:1234:5678"
        render(fixture().copy(wan=ip),scale=1.6f)
        rule.onNodeWithTag("compact-home").performScrollToNode(hasTestTag("home-network"))
        assertEquals(ip,layout("home-network-ip").layoutInput.text.text)
        assertFalse(layout("home-network-ip").hasVisualOverflow)
        rule.onNodeWithTag("compact-home").performScrollToNode(hasTestTag("home-cpu-bar"))
        rule.onNodeWithTag("home-cpu-bar").assertIsDisplayed()
    }
}
