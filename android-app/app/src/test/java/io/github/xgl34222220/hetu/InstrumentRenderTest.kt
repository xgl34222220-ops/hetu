package io.github.xgl34222220.hetu

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import java.time.Instant
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],qualifiers = "zh-rCN-w480dp-h2400dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class InstrumentRenderTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private val app get()=ApplicationProvider.getApplicationContext<Context>()
    private fun capture(tag:String,name:String) {
        compose.waitForIdle()
        val b=compose.onNodeWithTag(tag,true).fetchSemanticsNode().boundsInWindow
        val bmp=compose.runOnIdle {
            val decor=compose.activity.window.decorView
            val xy=IntArray(2).also{decor.getLocationInWindow(it)}
            Bitmap.createBitmap(kotlin.math.ceil(b.width).toInt(),kotlin.math.ceil(b.height).toInt(),Bitmap.Config.ARGB_8888).also {
                val c=Canvas(it);c.translate(xy[0]-b.left,xy[1]-b.top);decor.draw(c)
            }
        }
        File("build/reports/ui-audit/$name.png").apply{parentFile?.mkdirs()}.outputStream().use{bmp.compress(Bitmap.CompressFormat.PNG,100,it)}
    }
    private fun provider()=DashboardProviderUi("两年套餐 · 长名称测试","HTTP","","","2026-09-20T07:48:00Z",
        5_000_000_000L,20_000_000_000L,128_000_000_000L,1_820_000_000L,setOf("日本","美国"),true)

    @Test fun sharedInstrumentPanelKeepsRealProgressAndHairlineDividers() {
        var night by mutableStateOf(false)
        compose.setContent { key(night) {
            app.getSharedPreferences("hetu",0).edit().putString("appearance",if(night)"dark" else "light").commit()
            HetuTheme { CompositionLocalProvider(LocalDensity provides Density(1f,1f),LocalHetuMotionEnabled provides false) {
                Column(Modifier.width(360.dp).crystalPageBackground().padding(16.dp).testTag("instrument-scene")) {
                    WorkspaceBento(ProxyRuntimeSnapshot(running=true,wanAddress="203.0.113.7",wanRegion="示例出口",wanState="success"),20,
                        1500,28000,25_000_000_000L,128_000_000_000L,2,136500000,6.3f,{})
                }
            } }
        } }
        for(dark in listOf(false,true)) {
            compose.runOnIdle{night=dark};compose.waitForIdle()
            val net=compose.onNodeWithTag("instrument-network",true).fetchSemanticsNode().boundsInRoot
            val speed=compose.onNodeWithTag("instrument-speed",true).fetchSemanticsNode().boundsInRoot
            assertEquals("Shared panel must keep a 1dp divider",1f,speed.left-net.right,1f)
            compose.onNodeWithTag("home-usage-progress",true).assert(SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo,ProgressBarRangeInfo(25f/128f,0f..1f)))
            compose.onNodeWithTag("home-cpu-progress",true).assert(SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo,ProgressBarRangeInfo(.063f,0f..1f)))
            compose.onNodeWithTag("instrument-network-badge",true).assertDoesNotExist()
            compose.onNodeWithTag("instrument-resource-badge",true).assertDoesNotExist()
            capture("instrument-scene","instruments-360-${if(dark)"dark" else "light"}")
        }
    }
    @Test fun subscriptionTicketKeepsUsageDetailsCallbacksAndUnknownState() {
        var known by mutableStateOf(true)
        var scale by mutableFloatStateOf(1f)
        var edited=0;var refreshed=0
        val item=provider()
        compose.setContent { HetuTheme { CompositionLocalProvider(LocalDensity provides Density(1f,scale),LocalHetuMotionEnabled provides false) {
            Column(Modifier.width(360.dp).crystalPageBackground().padding(16.dp).verticalScroll(rememberScrollState()).testTag("ticket-scene")) {
                InstrumentSubscriptionTicket(item.name,if(known)item else null,"example.invalid",onEdit={edited++},onRefresh={refreshed++})
            }
        } } }
        for(f in listOf(1f,1.5f)) {
            compose.runOnIdle{scale=f};compose.waitForIdle()
            val layouts=mutableListOf<TextLayoutResult>()
            compose.onNodeWithTag("ticket-usage:${item.name}",true).performSemanticsAction(SemanticsActions.GetTextLayoutResult){it(layouts)}
            assertTrue("Ticket number clipping",layouts.isNotEmpty() && layouts.none{it.hasVisualOverflow})
            val rail=compose.onNodeWithTag("ticket-progress:${item.name}",true).fetchSemanticsNode().boundsInRoot
            assertEquals("Ticket progress must remain 5dp",5f,rail.height,.6f)
            capture("ticket-scene","ticket-360-$f")
        }
        compose.onNodeWithContentDescription("编辑 ${item.name}").performClick()
        compose.onNodeWithContentDescription("${item.name} 更新订阅").performClick()
        compose.runOnIdle{assertEquals(1,edited);assertEquals(1,refreshed)}
        compose.onNodeWithText("流量详情").performClick()
        compose.onNodeWithText("上传 ",substring=true).assertExists()
        compose.runOnIdle{known=false}
        compose.onNodeWithText("订阅未上报流量信息").assertExists()
        compose.onNodeWithTag("ticket-progress:${item.name}").assertDoesNotExist()
        compose.onNodeWithText("到期日未上报").assertExists()
        capture("ticket-scene","ticket-unknown")
    }
    @Test fun timestampsRespectLocalDayAndNeverInventExpiry() {
        val now=Instant.parse("2026-09-20T08:00:00Z")
        val zone=ZoneId.of("Asia/Shanghai")
        assertEquals("今天 15:48 更新",ticketUpdatedAt("2026-09-20T07:48:00Z",now,zone))
        assertEquals("昨天 15:48 更新",ticketUpdatedAt("2026-09-19T07:48:00Z",now,zone))
        assertEquals("2026-09-18 15:48 更新",ticketUpdatedAt("2026-09-18T07:48:00Z",now,zone))
        assertEquals("尚未更新",ticketUpdatedAt("",now,zone))
        assertEquals("更新时间未知",ticketUpdatedAt("not-a-date",now,zone))
        assertEquals("到期日未上报",ticketExpireAt(0,zone))
    }
    @Test fun ruleBatchesKeepAllRulesInOriginalOrderAndBoundedGroups() {
        val rows=(0 until 31).map{ProxyRuleUi(it,"DOMAIN","host-$it.example","DIRECT")}
        val batches=instrumentRuleBatches(rows)
        assertEquals(listOf(15,15,1),batches.map{it.size})
        assertEquals(rows,batches.flatten())
        assertTrue(instrumentRuleBatches(emptyList()).isEmpty())
        compose.setContent { HetuTheme { CompositionLocalProvider(LocalDensity provides Density(1f,1f),LocalHetuMotionEnabled provides false) {
            Column(Modifier.width(360.dp).crystalPageBackground().padding(16.dp).testTag("rule-scene")) { RefRuleGroupCard(batches.first()) }
        } } }
        compose.onNodeWithText("host-0.example",true).assertExists()
        compose.onNodeWithText("host-14.example",true).assertExists()
        capture("rule-scene","rules-15-inset")
    }
}
