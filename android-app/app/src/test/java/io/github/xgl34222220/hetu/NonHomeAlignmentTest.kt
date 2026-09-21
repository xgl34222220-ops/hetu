package io.github.xgl34222220.hetu

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
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
@Config(sdk=[35], qualifiers="w480dp-h2400dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NonHomeAlignmentTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val app get() = ApplicationProvider.getApplicationContext<Context>()
    private fun bounds(tag: String) = compose.onNodeWithTag(tag,true).fetchSemanticsNode().boundsInRoot
    private fun layout(tag: String): TextLayoutResult {
        val out = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag(tag,true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(out) }
        assertEquals(1,out.size)
        return out.single()
    }
    private fun capture(tag: String, name: String) {
        compose.waitForIdle()
        val b = compose.onNodeWithTag(tag,true).fetchSemanticsNode().boundsInWindow
        val bitmap = compose.runOnIdle {
            val decor = compose.activity.window.decorView
            val xy = IntArray(2).also { decor.getLocationInWindow(it) }
            Bitmap.createBitmap(kotlin.math.ceil(b.width).toInt().coerceAtLeast(1),kotlin.math.ceil(b.height).toInt().coerceAtLeast(1),Bitmap.Config.ARGB_8888).also {
                val c = Canvas(it);c.translate(xy[0]-b.left,xy[1]-b.top);decor.draw(c)
            }
        }
        File("build/reports/ui-audit/$name.png").apply { parentFile?.mkdirs() }.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
    }
    @Test fun toolRowsKeepTitleOriginAndChevronColumnAtNormalAndLargeFont() {
        var scale by mutableFloatStateOf(1f)
        var dark by mutableStateOf(false)
        compose.setContent { key(dark) {
            app.getSharedPreferences("hetu",0).edit().putString("appearance",if(dark)"dark" else "light").commit()
            HetuTheme { CompositionLocalProvider(LocalDensity provides Density(1f,scale),LocalHetuMotionEnabled provides false) {
                Column(Modifier.width(360.dp).crystalPageBackground().padding(16.dp).testTag("tools-scene")) {
                    Column(Modifier.fillMaxWidth().crystalMaterial(androidx.compose.foundation.shape.RoundedCornerShape(22.dp))) {
                        WorkspaceSettingRow("订阅管理","导入、更新与切换配置",Icons.Rounded.CloudDownload,Modifier.testTag("row:a")) {
                            Icon(Icons.Rounded.ChevronRight,null,Modifier.size(16.dp).testTag("chevron:a"))
                        }
                        WorkspaceInsetDivider()
                        WorkspaceSettingRow("广告过滤","较长说明应向下增长，不应将第一行文字重新垂直居中",Icons.Rounded.Shield,Modifier.testTag("row:b")) {
                            Icon(Icons.Rounded.ChevronRight,null,Modifier.size(16.dp).testTag("chevron:b"))
                        }
                    }
                }
            } }
        } }
        for(night in listOf(false,true)) for(f in listOf(1f,1.5f)) {
            compose.runOnIdle { dark=night;scale=f };compose.waitForIdle()
            val a=bounds("setting-title:订阅管理");val b=bounds("setting-title:广告过滤")
            assertEquals(a.left,b.left,.5f)
            assertEquals(a.top-bounds("row:a").top,b.top-bounds("row:b").top,.5f)
            assertEquals(bounds("chevron:a").right,bounds("chevron:b").right,.5f)
            assertFalse(layout("setting-title:订阅管理").hasVisualOverflow)
            assertFalse(layout("setting-support:广告过滤").hasVisualOverflow)
            capture("tools-scene","nonhome104-tools-$night-$f")
        }
    }
    @Test fun nodeFooterIsFixedForShortAndTwoLineNamesAndUdpIsReportedOnly() {
        val nodes=listOf(ProxyNodeUi("美国节点","Vless",true),ProxyNodeUi("日本节点长名称测试","Trojan",false))
        val g=ProxyGroupUi("AI 平台","Selector",nodes[1].name,nodes)
        compose.setContent { HetuTheme { CompositionLocalProvider(LocalDensity provides Density(1f,1f),LocalHetuMotionEnabled provides false) {
            Column(Modifier.width(380.dp).crystalPageBackground().padding(16.dp).testTag("groups-scene")) {
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    StrategyGroupCard(g,g.now,true,87,false,Modifier.weight(1f),{}, {})
                    StrategyGroupCard(g.copy(name="Google"),"美国节点",false,245,false,Modifier.weight(1f),{}, {})
                }
                LiquidGroupWell(g,g.now,emptyMap(),emptyMap(),{},{},{})
            }
        } } }
        compose.onNodeWithTag("strategy:AI 平台",true).assertHeightIsAtLeast(90.dp)
        compose.onNodeWithTag("brand-tray:AI 平台",true).assertWidthIsEqualTo(32.dp)
        assertTrue(bounds("brand-tray:AI 平台").left < bounds("strategy-title:AI 平台").left)
        assertEquals(bounds("node-protocol:${nodes[0].name}").top,bounds("node-protocol:${nodes[1].name}").top,.5f)
        compose.onNodeWithText("Vless · UDP",true).assertExists()
        compose.onNodeWithText("Trojan",true).assertExists()
        compose.onNodeWithTag("node:${nodes[0].name}",true).assertHeightIsAtLeast(64.dp)
        capture("groups-scene","nonhome104-groups")
    }
    @Test fun refreshStatesKeep48dpSlotAndDetailsRemainInlineWithoutDialogs() {
        var busy by mutableStateOf(false);var ok by mutableStateOf(false);var error by mutableStateOf("")
        var clicks=0
        val p=DashboardProviderUi("两年套餐","HTTP","","","2026-09-21T00:00:00Z",5_000_000_000L,20_000_000_000L,128_000_000_000L,1820000000,setOf("a","b"),true)
        compose.setContent { HetuTheme { CompositionLocalProvider(LocalDensity provides Density(1f,1f),LocalHetuMotionEnabled provides false) {
            Column(Modifier.width(360.dp).crystalPageBackground().padding(16.dp).verticalScroll(rememberScrollState()).testTag("ticket-scene")) {
                SubscriptionBoardingTicket(p.name,p,"example.invalid",refreshing=busy,success=ok,error=error,onEdit={},onRefresh={clicks++})
            }
        } } }
        val original=bounds("refresh:${p.name}")
        assertEquals(18.sp,layout("ticket-remaining:${p.name}").layoutInput.style.fontSize)
        for(phase in listOf("loading","success","failed","idle")) {
            compose.runOnIdle { busy=phase=="loading";ok=phase=="success";error=if(phase=="failed")"HTTP 503" else "" };compose.waitForIdle()
            assertEquals(original,bounds("refresh:${p.name}"))
            compose.onAllNodes(isDialog()).assertCountEquals(0)
            compose.onNodeWithTag("refresh:${p.name}",true).assertHeightIsAtLeast(48.dp)
            if(phase=="loading") compose.onNodeWithTag("refresh:${p.name}",true).assertIsNotEnabled()
            if(phase=="failed") {
                compose.onNodeWithText("详情").performClick()
                compose.onNodeWithTag("task-inline-details",true).assertExists()
                compose.onAllNodes(isDialog()).assertCountEquals(0)
                capture("ticket-scene","nonhome104-ticket-failure")
            }
        }
        compose.onNodeWithTag("refresh:${p.name}",true).performClick()
        compose.runOnIdle { assertEquals(1,clicks) }
        capture("ticket-scene","nonhome104-ticket")
    }
    @Test fun accordionHasIntermediateHeightAndHonoursReducedMotion() {
        var visible by mutableStateOf(false)
        var motion by mutableStateOf(true)
        compose.mainClock.autoAdvance=false
        compose.setContent { CompositionLocalProvider(LocalHetuMotionEnabled provides motion) {
            Column(Modifier.width(320.dp).heightIn(min=1.dp).testTag("accordion-host")) {
                WorkspaceAccordion(visible) { Box(Modifier.fillMaxWidth().height(120.dp)) }
            }
        } }
        // Android measure/layout is not driven by the Compose clock. Flush each
        // frame to observe the actual intermediate height, not the pre-layout 1dp.
        fun frames(count: Int): List<Float> = (0 until count).map {
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            bounds("accordion-host").height
        }
        compose.runOnIdle { visible=true }
        val opening=frames(26)
        val end=opening.last()
        assertTrue("Accordion never reached full height: $opening",end>=119f)
        assertTrue("Open hard-snapped: $opening",opening.any { it>1f && it<end })
        assertTrue("Opening moved backwards: $opening",opening.zipWithNext().all { (a,b)-> b>=a })
        compose.runOnIdle { visible=false }
        val closing=frames(26)
        assertTrue("Close hard-snapped: $closing",closing.any { it>1f && it<end })
        assertTrue("Accordion did not finish closing: $closing",closing.last()<=1f)
        compose.runOnIdle { motion=false;visible=true }
        assertEquals(end,frames(4).last(),1f)
    }
}
