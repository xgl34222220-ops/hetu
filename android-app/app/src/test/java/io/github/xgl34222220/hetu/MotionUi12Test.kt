@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package io.github.xgl34222220.hetu

import android.app.Application
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.animation.core.SpringSpec
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import io.github.xgl34222220.hetu.ui.LocalHetuMotionEnabled
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w393dp-h852dp-mdpi", application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MotionUi12Test {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val prefs get() = context.getSharedPreferences("hetu",0)
    private val open = mutableStateOf(true)
    private var closes = 0
    private var appliedSpring: SpringSpec<Float>? = null
    @Before fun setup() { prefs.edit().clear().commit(); open.value = true; closes = 0 }
    private fun node(tag: String) = rule.onNodeWithTag(tag, useUnmergedTree = true)
    private fun renderSheet(motion: Boolean = true, log: Boolean = false, long: Boolean = false) {
        rule.setContent {
            CompositionLocalProvider(LocalHetuMotionEnabled provides motion) {
                MaterialTheme {
                    ImmersiveUiHost {
                        Box(Modifier.fillMaxSize()) {
                            Button(onClick = { open.value = true }, modifier = Modifier.testTag("reopen12")) { Text("重新打开") }
                            if (open.value) {
                                val close: () -> Unit = { closes++; open.value = false }
                                if (log) RefInfoBottomSheet("运行日志", (1..100).joinToString("\n") { "[TCP] record $it" }, "关闭", close)
                                else NativeDetailsSheet("网络详情", close) {
                                    val spec = MaterialTheme.motionScheme.defaultSpatialSpec<Float>()
                                    SideEffect { appliedSpring = spec as? SpringSpec<Float> }
                                    repeat(if (long) 80 else 5) { index -> Text("网络信息 $index", Modifier.fillMaxWidth().height(36.dp).testTag("body12-$index")) }
                                }
                            }
                        }
                    }
                }
            }
        }
        rule.waitForIdle()
    }
    private fun progress() = node("motion12-background").fetchSemanticsNode().config[ModalProgress12]
    @Test fun customSpringReachesActualNativeSheetSubtree() {
        renderSheet()
        assertNotNull(appliedSpring)
        assertEquals(.84f, appliedSpring!!.dampingRatio, .0001f)
        assertEquals(280f, appliedSpring!!.stiffness, .0001f)
        assertEquals(1f,progress(),.02f)
    }
    @Test fun fingerDragContinuouslyRestoresBackgroundRatherThanTogglingIt() {
        renderSheet()
        val fullyOpen = progress()
        node("sheet-drag-handle").performTouchInput { down(center); moveBy(Offset(0f,70f),140) }
        val during = progress()
        assertTrue("actual measured coverage must decrease during a live drag",during > 0f && during < fullyOpen)
        val scale = node("motion12-background").fetchSemanticsNode().config[ModalScale12]
        assertTrue(scale > .96f && scale < 1f)
        node("sheet-drag-handle").performTouchInput { advanceEventTime(180); up() }
        node("native-details-sheet").assertIsDisplayed()
        assertEquals(1f,progress(),.02f); assertEquals(0,closes)
    }
    @Test fun shortSlowDragReturnsToSameAnchor() {
        renderSheet()
        val top = node("native-details-sheet").fetchSemanticsNode().boundsInRoot.top
        // 90px / 1.2s = 75dp/s on this mdpi fixture: below native 125dp/s.
        node("sheet-drag-handle").performTouchInput { swipe(center,center+Offset(0f,90f),1200) }
        node("native-details-sheet").assertIsDisplayed()
        assertEquals(top,node("native-details-sheet").fetchSemanticsNode().boundsInRoot.top,1f)
        assertEquals(0,closes)
    }
    @Test fun unpaused150DpPerSecondGestureFollowsTheNativeFlingContract() {
        renderSheet()
        // Preserve the exact failed-attempt gesture, now correctly classified.
        node("sheet-drag-handle").performTouchInput { swipe(center,center+Offset(0f,90f),600) }
        node("native-details-sheet").assertDoesNotExist()
        assertEquals(1,closes); assertEquals(0f,progress(),0f)
    }
    @Test fun shortFastFlingClosesWithoutRequiring120dpTravel() {
        renderSheet()
        node("sheet-drag-handle").performTouchInput { swipe(center,center+Offset(0f,90f),65) }
        node("native-details-sheet").assertDoesNotExist()
        assertEquals(1,closes); assertEquals(0f,progress(),0f)
    }
    @Test fun logCloseButtonKeepsTheSheetAliveWhileItSlidesOut() {
        renderSheet(log = true)
        rule.mainClock.autoAdvance = false
        rule.onNodeWithContentDescription("关闭").performTouchInput { click() }
        rule.mainClock.advanceTimeByFrame()
        node("native-log-sheet").assertExists()
        assertEquals(0,closes)
        rule.mainClock.advanceTimeBy(120)
        assertTrue(progress() < 1f)
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()
        node("native-log-sheet").assertDoesNotExist(); assertEquals(1,closes)
    }
    @Test fun scrollingLongSheetBodyDoesNotDismissOrDragTheWholePage() {
        renderSheet(long = true)
        node("body12-4").performTouchInput { swipeUp() }
        node("native-details-sheet").assertIsDisplayed()
        assertEquals(0,closes); assertEquals(1f,progress(),.02f)
    }
    @Test fun reducedMotionDoesNotShrinkBackgroundAndCanCloseNormally() {
        renderSheet(motion = false)
        assertEquals(1f,node("motion12-background").fetchSemanticsNode().config[ModalScale12],0f)
        node("sheet-confirm").performTouchInput { click() }
        node("native-details-sheet").assertDoesNotExist()
        assertEquals(0f,progress(),0f)
    }
    @Test fun disabledBlurHasZeroRenderBlurWithoutDisablingSheet() {
        prefs.edit().putBoolean("enableBlur",false).commit()
        renderSheet()
        assertEquals(0f,node("motion12-background").fetchSemanticsNode().config[ModalBlur12],0f)
        node("sheet-confirm").performTouchInput { click() }
        node("native-details-sheet").assertDoesNotExist()
    }
    @Test fun reopenAfterDismissDoesNotKeepAStaleBackdropLease() {
        renderSheet()
        repeat(3) {
            node("sheet-confirm").performTouchInput { click() }
            assertEquals(0f,progress(),0f)
            node("reopen12").performTouchInput { click() }
            assertEquals(1f,progress(),.02f)
        }
        assertEquals(3,closes)
    }
    @Test fun anchoredMenuKeepsItsContentDuringExitAndDisposesAfterward() {
        val menu = mutableStateOf(false)
        rule.setContent {
            CompositionLocalProvider(LocalHetuMotionEnabled provides true) {
                MaterialTheme {
                    Box(Modifier.fillMaxSize().padding(16.dp)) {
                        Box(Modifier.align(Alignment.TopEnd)) {
                            Button({menu.value = true},modifier=Modifier.testTag("open-menu12")) { Text("菜单") }
                            MotionPopover12(menu.value,{menu.value=false},Modifier.testTag("menu12")) {
                                DropdownMenuItem(text={Text("完成")},onClick={menu.value=false},modifier=Modifier.testTag("close-menu12"))
                            }
                        }
                    }
                }
            }
        }
        node("open-menu12").performTouchInput { click() }
        node("close-menu12").assertIsDisplayed()
        rule.mainClock.autoAdvance=false
        node("close-menu12").performTouchInput { click() }
        rule.mainClock.advanceTimeByFrame()
        node("menu12").assertExists()
        rule.mainClock.autoAdvance=true;rule.waitForIdle()
        node("menu12").assertDoesNotExist()
    }
    @Test fun bottomEdgeDragReboundsWithoutSelectingNodesOrRequestingRefresh() {
        var requests = 0
        val groups = (1..30).map { ProxyGroupUi("策略 $it","Selector","Alpha",listOf(ProxyNodeUi("Alpha","VLESS",true,100))) }
        rule.setContent {
            CompositionLocalProvider(LocalHetuMotionEnabled provides true,LocalHomeDockClearance provides 96.dp) {
                MaterialTheme {
                    StrategyPanel11(ProxyComposeState(running=true,groups=groups,trafficMode="rule"),remember { mutableStateMapOf() },prefs,
                        onTab={},refresh={requests++},select={_,name->requests++;name},measure={requests++;100L})
                }
            }
        }
        node("panel11-list").performScrollToNode(hasTestTag("panel11-group:策略 30"))
        node("panel11-list").performTouchInput { swipeUp() }
        node("panel11-list").performTouchInput { down(center);moveBy(Offset(0f,-80f),160) }
        assertTrue(node("panel11-list").fetchSemanticsNode().config[EdgeOffset12] < -1f)
        node("panel11-list").performTouchInput { advanceEventTime(180);up() }
        rule.waitForIdle()
        assertEquals(0f,node("panel11-list").fetchSemanticsNode().config[EdgeOffset12],.1f)
        assertEquals(0,requests)
    }
}
