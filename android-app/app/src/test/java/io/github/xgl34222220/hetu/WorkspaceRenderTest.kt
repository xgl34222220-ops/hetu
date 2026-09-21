package io.github.xgl34222220.hetu

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import io.github.xgl34222220.hetu.ui.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Real Compose rasterization and interactions, no Root/backend/network mocking as UI success. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w480dp-h2400dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WorkspaceRenderTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private fun configuredGroup(): ProxyGroupUi {
        val url = "https://icons.example/audit-brand.png"
        val path = File(context.cacheDir, "ui-audit-brand.png")
        val bmp = Bitmap.createBitmap(48, 24, Bitmap.Config.ARGB_8888).apply { eraseColor(0xffd32f89.toInt()) }
        path.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        runBlocking { assertTrue(ProxyGroupIconRepository.get(context).load(url, path.absolutePath) is GroupIconLoad.Ready) }
        return ProxyGroupUi("🇯🇵 AI 平台", "Selector", "A long original strategy selection", emptyList(), url, path.absolutePath)
    }
    private fun raster(tag: String): Bitmap {
        compose.waitForIdle()
        val bounds = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInWindow
        assertTrue("Empty render bounds: $tag $bounds", bounds.width > 0 && bounds.height > 0)
        // Native Skia rasterizes the real Android view tree synchronously on the UI thread.
        // PixelCopy's device redraw callback does not run on a paused host test looper.
        return compose.runOnIdle {
            val decor = compose.activity.window.decorView
            val location = IntArray(2).also { decor.getLocationInWindow(it) }
            Bitmap.createBitmap(kotlin.math.ceil(bounds.width).toInt(), kotlin.math.ceil(bounds.height).toInt(), Bitmap.Config.ARGB_8888).also {
                val canvas = Canvas(it)
                canvas.translate(location[0] - bounds.left, location[1] - bounds.top)
                decor.draw(canvas)
            }
        }
    }
    private fun capture(tag: String, filename: String) {
        val image = raster(tag)
        val target = File("build/reports/ui-audit/$filename.png").apply { parentFile?.mkdirs() }
        target.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    private fun assertUnclipped(tag: String) {
        val node = compose.onNodeWithTag(tag, useUnmergedTree = true)
        val results = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        assertTrue("No text layout for $tag", results.isNotEmpty())
        assertTrue("Text visually clipped for $tag: " + results.joinToString { "size=${it.size} constraints=${it.layoutInput.constraints} lines=${it.lineCount} text=${it.layoutInput.text}" }, results.none { it.hasVisualOverflow })
    }
    @Test fun configuredIconsAndLongNumbersRenderAcrossWidthFontAndTheme() {
        val group = configuredGroup()
        var width by mutableIntStateOf(320)
        var font by mutableFloatStateOf(1f)
        var dark by mutableStateOf(false)
        var rules by mutableIntStateOf(180547)
        compose.setContent {
            key(dark) {
                context.getSharedPreferences("hetu",0).edit().putString("appearance",if (dark) "dark" else "light").commit()
                HetuTheme {
                    CompositionLocalProvider(LocalDensity provides Density(1f,font), LocalHetuMotionEnabled provides false) {
                        Column(Modifier.width(width.dp).background(LocalHetuTokens.current.pageBackground).verticalScroll(rememberScrollState()).padding(16.dp).testTag("audit-root"),
                            verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("河图 · 界面回归", style = MaterialTheme.typography.headlineLarge)
                            StrategyGroupCard(group, "[1.0x] A long original strategy selection — 保留完整名称", false, 96, false, onExpand={}, onDelay={})
                            RuleMetricSummary(rules, 23, 1000000)
                            NodeChoiceCard(ProxyNodeUi("Japan - Original node name - 东京节点"), true, 96, false, onSelect={}, onDelay={})
                            HetuTaskFeedback("Google 更新失败：Mihomo 控制接口返回 503 : {\"message\":\"Get https://x.example/?token=SECRET\"}", error=true)
                        }
                    }
                }
            }
        }
        for (w in listOf(320,360,412)) for (scale in listOf(1f,1.15f,1.3f,1.5f)) for (night in listOf(false,true)) {
            compose.runOnIdle { width=w; font=scale; dark=night; rules=if(scale==1.5f) 1000000 else 180547 }
            compose.waitForIdle()
            compose.onNodeWithTag("configured-icon:${group.name}", useUnmergedTree=true).assertExists()
            compose.onNodeWithTag("rule-count", useUnmergedTree=true).assertTextEquals(if(scale==1.5f) "1,000,000" else "180,547")
            assertUnclipped("rule-count")
            val icon = raster("configured-icon:${group.name}")
            assertEquals("Original brand color changed",0xffd32f89.toInt(),icon.getPixel(icon.width/2,icon.height/2))
            val root = compose.onNodeWithTag("audit-root",true).fetchSemanticsNode().boundsInRoot
            val number = compose.onNodeWithTag("rule-count",true).fetchSemanticsNode().boundsInRoot
            assertTrue(number.left >= root.left && number.right <= root.right + .5f)
            if(scale==1f || scale==1.5f) capture("audit-root", "cards-${w}-${scale}-${if(night) "dark" else "light"}")
        }
    }
    @Test fun selectionAndDelayAreIndependentAndAtLeast48dp() {
        val group=configuredGroup()
        var selected=0; var measured=0; var expanded=0
        compose.setContent { HetuTheme { CompositionLocalProvider(LocalDensity provides Density(1f,1.3f),LocalHetuMotionEnabled provides false) {
            Column(Modifier.width(360.dp).padding(16.dp)) {
                StrategyGroupCard(group,"Original",false,96,false,onExpand={expanded++},onDelay={measured++})
                NodeChoiceCard(ProxyNodeUi("Tokyo original name"),true,96,false,onSelect={selected++},onDelay={measured++})
            }
        } } }
        compose.onNodeWithTag("node-delay:Tokyo original name",true).assertHeightIsAtLeast(48.dp).performClick()
        compose.runOnIdle { assertEquals(1,measured); assertEquals(0,selected); assertEquals(0,expanded) }
        compose.onNodeWithText("Tokyo original name",true).performClick()
        compose.runOnIdle { assertEquals(1,selected); assertEquals(1,measured) }
        compose.onNodeWithTag("strategy-delay:${group.name}",true).assertHeightIsAtLeast(48.dp).performClick()
        compose.runOnIdle { assertEquals(2,measured); assertEquals(0,expanded) }
        compose.onNodeWithText(group.name,true).performClick()
        compose.runOnIdle { assertEquals(1,expanded) }
    }
    @Test fun actualBentoDisplaysAllRuntimeStatesAndNeverCallsBackend() {
        var state by mutableStateOf("idle")
        var width by mutableIntStateOf(360)
        var scale by mutableFloatStateOf(1f)
        compose.setContent { HetuTheme { CompositionLocalProvider(LocalDensity provides Density(1f,scale),LocalHetuMotionEnabled provides false) {
            Column(Modifier.width(width.dp).verticalScroll(rememberScrollState()).testTag("bento-root")) {
                WorkspaceBento(ProxyRuntimeSnapshot(running=true,wanAddress="2001:db8:1234:5678:90ab:cdef:1234:5678",
                    wanRegion="示例区域",wanState=state,wanCheckedAt=1000,wanError="检测未成功"),20,999,1000000,163100000000,685500000000,3,78100000,6.3f,{})
            }
        } } }
        for (s in listOf("idle","loading","failed","success","stale")) {
            compose.runOnIdle { state=s }
            compose.waitForIdle()
            val expected=when(s) { "idle"->"未检测";"loading"->"读取中";"failed"->"检测失败";else->"2001:db8:1234:5678:90ab:cdef:1234:5678" }
            compose.onNodeWithText(expected,true).assertExists()
            compose.onNodeWithTag("instrument-usage",true).assertExists()
        }
        for(w in listOf(320,360,412)) for(f in listOf(1f,1.5f)) {
            compose.runOnIdle { width=w;scale=f }
            capture("bento-root","bento-${w}-${f}")
        }
    }
    @Test fun feedbackDoesNotExposeSecretsAndFailureIsNotASpinner() {
        context.getSharedPreferences("hetu",0).edit().putString("proxyControllerSecret","SUPER_SECRET").commit()
        compose.setContent { HetuTheme {
            HetuTaskFeedback("Google 更新失败：Mihomo 控制接口返回 503 : {\"secret\":\"SUPER_SECRET\",\"url\":\"https://x.example/?token=PRIVATE\"}",error=true)
        } }
        compose.onNodeWithText("Google 更新失败（503）").assertExists()
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)).assertCountEquals(0)
        compose.onNodeWithText("SUPER_SECRET",substring=true).assertDoesNotExist()
        compose.onNodeWithText("详情").performClick()
        compose.onNodeWithText("SUPER_SECRET",substring=true).assertDoesNotExist()
        compose.onNodeWithText("PRIVATE",substring=true).assertDoesNotExist()
        compose.onNodeWithText("复制诊断").assertExists()
    }
    @Test fun measuredDockIsNotCountedTwiceAndLastRowIsReachable() {
        var padding=0f;var click=0
        compose.setContent { HetuTheme { CompositionLocalProvider(LocalDensity provides Density(1f,1f),LocalHetuDockHeight provides 108.dp) {
            val bottom=hetuContentBottomPadding()
            SideEffect { padding=bottom.value }
            LazyColumn(Modifier.width(360.dp).height(600.dp).testTag("dock-list"),contentPadding=PaddingValues(bottom=bottom)) {
                items(20) { i -> Button(onClick={click++},modifier=Modifier.fillMaxWidth().height(56.dp).testTag("row-$i")) { Text("条目 $i") } }
            }
        } } }
        compose.runOnIdle { assertEquals(138f,padding,.01f) }
        // Lazy items outside the viewport do not yet have semantics nodes.
        compose.onNodeWithTag("dock-list").performScrollToIndex(19)
        compose.onNodeWithTag("row-19").assertIsDisplayed().performClick()
        val listBounds = compose.onNodeWithTag("dock-list").fetchSemanticsNode().boundsInRoot
        val rowBounds = compose.onNodeWithTag("row-19").fetchSemanticsNode().boundsInRoot
        assertTrue("Last row behind measured dock", rowBounds.bottom <= listBounds.bottom - 108f)
        capture("dock-list", "last-row-safe-area")
        compose.runOnIdle { assertEquals(1,click) }
    }
}
