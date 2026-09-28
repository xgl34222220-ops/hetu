package io.github.xgl34222220.hetu

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.*
import androidx.test.core.app.ApplicationProvider
import io.github.xgl34222220.hetu.ui.LocalHetuMotionEnabled
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w393dp-h852dp-mdpi", application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PanelPolish14Test {
    @get:Rule val rule=createAndroidComposeRule<ComponentActivity>()
    private val context get()=ApplicationProvider.getApplicationContext<Context>()
    private val prefs get()=context.getSharedPreferences("hetu",0)
    private val delays=mutableStateMapOf<String,Long>()
    private val calls=mutableListOf<String>()
    private val longName="[IPLC-移动优化-新加坡-VLESS] 2x [永久]"
    private fun group(name: String="节点选择")=ProxyGroupUi(name,"Selector","Alpha",listOf(
        ProxyNodeUi("Alpha","VLESS",true,80,"P1"),ProxyNodeUi("Beta","Trojan",true,160,"P1")))
    @Before fun reset() { prefs.edit().clear().commit();delays.clear();calls.clear() }
    private fun render(groups: List<ProxyGroupUi> = listOf(group(),group("YouTube")), running: Boolean=true,
        motion: Boolean=false, fontScale: Float=1f,
        select: suspend(String,String)->String={g,n->calls+="select:$g:$n";n},
        measure: suspend(String)->Long={n->calls+="delay:$n";123}) {
        rule.setContent { val density=LocalDensity.current
            CompositionLocalProvider(LocalHetuMotionEnabled provides motion,LocalHomeDockClearance provides 96.dp,
                LocalDensity provides Density(density.density,fontScale)) {
                MaterialTheme { ModalBackdropHost12 {
                    StrategyPanel11(ProxyComposeState(running=running,trafficMode="rule",groups=groups),delays,prefs,
                        onTab={calls+="tab:${it.label}"},refresh={calls+="refresh"},select=select,measure=measure)
                } }
            }
        }
    }
    private fun node(tag: String)=rule.onNodeWithTag(tag,useUnmergedTree=true)
    private fun tile(name: String)=node("panel11-node:节点选择:$name")
    private fun open()=node("panel11-group:节点选择").performTouchInput { click() }
    private fun screenshot(name: String) { rule.waitForIdle();rule.runOnIdle {
        val view=rule.activity.window.decorView
        val bitmap=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap));val file=File("build/outputs/ui14/$name.png");file.parentFile!!.mkdirs()
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle()
    } }
    @Test fun shortcutReservesSpaceRatherThanCoveringAnyGridRow() {
        render((1..10).map { group("策略 $it") })
        (1..10).forEach { node("panel11-group:策略 $it").assertIsDisplayed() }
        val viewport=node("panel11-viewport").getUnclippedBoundsInRoot()
        val slot=node("panel14-shortcut-slot").getUnclippedBoundsInRoot()
        val button=node("panel11-fab").getUnclippedBoundsInRoot()
        assertTrue(viewport.bottom<=slot.top);assertTrue(button.top>=slot.top)
        assertTrue(button.bottom<=slot.bottom);assertEquals(48.dp,button.height)
        screenshot("grid-and-reserved-shortcut")
    }
    @Test fun shortcutOpensTheRealPrimaryGroupAndBackPreservesGrid() {
        render();val before=node("panel11-group:YouTube").getUnclippedBoundsInRoot()
        node("panel11-fab").performTouchInput { click() }
        node("panel13-group-title").assertTextEquals("节点选择")
        node("panel13-back").performTouchInput { click() }
        assertEquals(before,node("panel11-group:YouTube").getUnclippedBoundsInRoot())
        assertTrue(calls.isEmpty())
    }
    @Test fun nodeSheetKeepsVisibleContextWithinSeventyToEightyFivePercent() {
        render();open()
        val bounds=node("panel13-group-sheet").getUnclippedBoundsInRoot()
        val height=with(rule.density) { rule.activity.window.decorView.height.toDp() }
        assertTrue("sheet occupies ${bounds.height}/$height",bounds.height>=height*.70f)
        assertTrue("sheet occupies ${bounds.height}/$height",bounds.height<=height*.85f)
        assertTrue(bounds.top>=height*.15f)
        node("panel13-detail-header").assertIsDisplayed()
        screenshot("contextual-node-sheet")
    }
    @Test fun batchKeepsPreviousMeasurementsAndOnlyTopProgressChanges() {
        val gate=CompletableDeferred<Long>()
        render(measure={n->calls+="delay:$n";gate.await()});open()
        node("panel11-test-all:节点选择").performTouchInput { click() }
        tile("Alpha").assert(hasAnyDescendant(hasText("80 ms")))
        tile("Beta").assert(hasAnyDescendant(hasText("160 ms")))
        val loading=SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,"测速中，保留上次结果")
        rule.onAllNodes(loading,useUnmergedTree=true).assertCountEquals(2)
        rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo),useUnmergedTree=true).assertCountEquals(0)
        node("panel11-test-all:节点选择").assertIsNotEnabled()
        node("panel11-test-all:节点选择").assert(hasAnyDescendant(hasText("0/2")))
        screenshot("quiet-batch-in-progress")
        rule.runOnIdle { gate.complete(214) }
        tile("Alpha").assert(hasAnyDescendant(hasText("214 ms")))
        tile("Beta").assert(hasAnyDescendant(hasText("214 ms")))
        assertEquals(setOf("delay:Alpha","delay:Beta"),calls.toSet())
    }
    @Test fun longNameIsProjectedButRawIdentityGoesToCoreAndLatencyRequests() {
        val g=group().copy(nodes=listOf(ProxyNodeUi("Alpha","VLESS",true,80),ProxyNodeUi(longName,"VLESS",true,220)))
        render(listOf(g));open()
        node("panel11-node-name:节点选择:$longName").assertTextEquals("移动优化 · 新加坡")
        node("panel14-node-tags:节点选择:$longName").assert(hasAnyDescendant(hasText("2x")))
        tile(longName).performTouchInput { click(Offset(15f,12f)) }
        tile(longName).assertIsSelected()
        node("panel11-delay:节点选择:$longName").performTouchInput { click() }
        assertEquals(listOf("select:节点选择:$longName","delay:$longName"),calls)
        screenshot("long-name-and-tags")
    }
    @Test fun compactSearchStillFiltersRealNodesWithoutPushingPrimaryTabs() {
        render();val tabs=node("panel11-subtabs").getUnclippedBoundsInRoot();open()
        node("panel13-node-search").performTextInput("Beta")
        tile("Alpha").assertDoesNotExist();tile("Beta").assertIsDisplayed()
        node("panel13-back").performTouchInput { click() }
        assertEquals(tabs,node("panel11-subtabs").getUnclippedBoundsInRoot())
    }
    @Test fun stoppedCoreDoesNotExposeAnUnusableFloatingShortcut() {
        render(running=false);node("panel11-fab").assertDoesNotExist()
        open();node("panel11-test-all:节点选择").assertIsNotEnabled()
        tile("Beta").performTouchInput { click(Offset(15f,12f)) }
        assertTrue(calls.isEmpty())
    }
    @Test fun delayedSelectionStillDoesNotPretendTheNodeIsActive() {
        val ack=CompletableDeferred<String>();render(select={_,_->ack.await()});open()
        tile("Beta").performTouchInput { click(Offset(15f,12f)) }
        tile("Alpha").assertIsSelected();tile("Beta").assertIsNotSelected()
        tile("Beta").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,"等待核心确认"))
        rule.runOnIdle { ack.complete("Beta") };tile("Beta").assertIsSelected()
    }
    @Test fun singleNodeTransportFailureKeepsItsLastValue() {
        render(measure={throw java.io.IOException("probe connection failed")});open()
        node("panel11-delay:节点选择:Beta").performTouchInput { click() }
        tile("Beta").assert(hasAnyDescendant(hasText("160 ms")))
        node("panel13-detail-notice").assertIsDisplayed();tile("Alpha").assertIsSelected()
    }
    @Test @Config(qualifiers="w320dp-h640dp-mdpi") fun largeFontRetainsAccessibleSearchListAndBack() {
        render(groups=listOf(group().copy(nodes=(1..30).map { ProxyNodeUi("香港移动优化节点 $it","VLESS",true,80) })),fontScale=1.7f)
        open();node("panel13-node-search").performTextInput("30")
        node("panel13-node-list").performScrollToNode(hasTestTag("panel11-node:节点选择:香港移动优化节点 30"))
        tile("香港移动优化节点 30").assertIsDisplayed();node("panel13-back").assertIsDisplayed()
        screenshot("large-font-node-sheet")
    }
}
