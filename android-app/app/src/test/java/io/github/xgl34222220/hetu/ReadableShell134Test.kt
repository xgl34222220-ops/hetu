package io.github.xgl34222220.hetu

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
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
import top.yukonga.miuix.kmp.squircle.LocalSquircleEnabled
import java.io.File

/** Actual production screens: sample state, no fabricated device/performance claims. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35], qualifiers="zh-rCN-w480dp-h1800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReadableShell134Test {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val app get() = ApplicationProvider.getApplicationContext<Context>()
    private fun capture(name: String) {
        compose.waitForIdle()
        val bounds = compose.onNodeWithTag("shell134", true).fetchSemanticsNode().boundsInWindow
        val bitmap = compose.runOnIdle {
            val decor = compose.activity.window.decorView
            val origin = IntArray(2).also { decor.getLocationInWindow(it) }
            Bitmap.createBitmap(bounds.width.toInt(), bounds.height.toInt(), Bitmap.Config.ARGB_8888).also {
                Canvas(it).also { c -> c.translate(origin[0]-bounds.left, origin[1]-bounds.top); decor.draw(c) }
            }
        }
        File("build/reports/ui-audit/$name.png").apply { parentFile.mkdirs() }.outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
    private fun layout(tag: String): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag(tag, true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        return results.single()
    }

    @Test fun completeScreensKeepReadableTypeStableNamesAndLastRowsClearOfDock() {
        app.getSharedPreferences("hetu",0).edit().clear().putString("appearance","light")
            .putBoolean("enableBlur",false).putBoolean("liquidGlass",false)
            .putString("proxySelectorNameOverflow","scroll").commit()
        var page by mutableIntStateOf(0)
        var font by mutableFloatStateOf(1f)
        var dockHeight by mutableStateOf(70.dp)
        val nodes = listOf(ProxyNodeUi("🇯🇵 Japan 02 [两年订阅 · 高速专线]", "VLESS", true, 65),
            ProxyNodeUi("🇭🇰 Hong Kong 01", "Trojan", true, 134))
        val groups = listOf("节点选择", "手动选择", "故障转移", "香港节点", "台湾节点", "日本节点", "新加坡节点", "韩国节点", "美国节点", "AI 平台")
            .mapIndexed { index, name -> ProxyGroupUi(name, if(index%2==0) "URLTest" else "Selector", nodes[index%2].name, nodes) }
        val state = ProxyComposeState(running=true,panelReady=true,groups=groups,trafficMode="rule",core="Mihomo",mode="TPROXY",config="自用.yaml")
        compose.setContent {
            RasterHetuTheme { CompositionLocalProvider(LocalDensity provides Density(1f,font),
                LocalHetuMotionEnabled provides false, LocalSquircleEnabled provides false,
                LocalHetuDockHeight provides dockHeight) {
                Box(Modifier.width(360.dp).height(800.dp).crystalPageBackground().testTag("shell134")) {
                    key(page,font) {
                        when(page) {
                            0 -> RefHome(state, ProxyRuntimeSnapshot(running=true,elapsedSeconds=120,rssBytes=112_721_920,
                                wanAddress="61.222.202.153",wanRegion="Taipei",wanCountryCode="TW",wanState="success"),
                                emptyList(), RefSubscriptionCache(185_600_000_000,691_100_000_000,3),20,
                                mapOf("Baidu" to 54L,"Cloudflare" to 278L,"Google" to 77L),1_100_000,55_000,1.9f,
                                "","",false,remember { HazeState() },false,{},{},{},{},{},{},{},false,{},{},{})
                            1 -> RefPanel(state,ProxyDashboardRepository(app),remember { mutableStateMapOf() },
                                RefPanelTab.Groups,{},0,remember { HazeState() },null,false,false,{},{},{},{})
                            2 -> RefTools(state) {}
                            3 -> RefSettings(state.copy(running=false),"",{},{})
                        }
                    }
                    HetuGlassDock(listOf(DockItem("首页",Icons.Rounded.Home),DockItem("面板",Icons.Rounded.Dashboard),
                        DockItem("工具",Icons.Rounded.Apps),DockItem("设置",Icons.Rounded.Settings)),page,{page=it},
                        remember { HazeState() },null,Modifier.align(Alignment.BottomCenter).onSizeChanged { dockHeight=it.height.dp })
                }
            } }
        }
        for(scale in listOf(1f,1.5f)) for(screen in 0..3) {
            compose.runOnIdle { font=scale;page=screen }
            compose.waitForIdle()
            capture("test134-${listOf("home","strategy","tools","settings")[screen]}-360-$scale")
            if(screen>=2) {
                val title=if(screen==2) "文件管理" else "基础代理配置"
                val heading=layout("setting-title:$title")
                val supporting=layout("setting-support:$title")
                assertTrue(heading.layoutInput.style.fontSize.value >=18f)
                assertTrue(supporting.layoutInput.style.fontSize.value >=14f)
                assertFalse(heading.didOverflowHeight)
                assertFalse(supporting.didOverflowHeight)
                val list=if(screen==2) "tools-list" else "settings-list"
                val last=if(screen==2) "启动配置" else "关于"
                compose.onNodeWithTag(list).performScrollToNode(hasText(last))
                compose.onNodeWithTag(list).performTouchInput { swipeUp() }
                val end=compose.onNodeWithTag("setting-title:$last",true).fetchSemanticsNode().boundsInRoot
                val dock=compose.onNodeWithTag("navigation-dock",true).fetchSemanticsNode().boundsInRoot
                assertTrue("Last action hidden under navigation",end.bottom <= dock.top)
            }
            if(screen==1) {
                val tabLayouts = mutableListOf<TextLayoutResult>()
                compose.onNodeWithText("策略", useUnmergedTree=true)
                    .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(tabLayouts) }
                tabLayouts.forEach { text ->
                    assertFalse("Strategy tab clipped at font=$scale", text.didOverflowHeight)
                    assertFalse("Strategy tab became only an ellipsis at font=$scale", text.isLineEllipsized(0))
                }
                val name=compose.onNodeWithTag("strategy-selection:节点选择",true)
                name.assertTextEquals(nodes.first().name)
                val before=name.fetchSemanticsNode().boundsInRoot
                compose.mainClock.advanceTimeBy(5_000)
                assertEquals(before,name.fetchSemanticsNode().boundsInRoot)
            }
        }
    }

    @Test fun realGroupExpansionProducesIntermediateFramesAndCompletes() {
        app.getSharedPreferences("hetu",0).edit().clear().putBoolean("enableBlur",false).putBoolean("liquidGlass",false).commit()
        val nodes=listOf(ProxyNodeUi("香港 01", "VLESS",true,65),ProxyNodeUi("日本 02", "Trojan",true,77))
        val group=ProxyGroupUi("节点选择","Selector",nodes.first().name,nodes)
        var expanded by mutableStateOf(false)
        compose.setContent {
            RasterHetuTheme { CompositionLocalProvider(LocalDensity provides Density(1f,1f),LocalHetuMotionEnabled provides true) {
                Column(Modifier.width(360.dp).height(500.dp).crystalPageBackground().padding(16.dp).testTag("shell134"),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                    LiquidStrategyCard(group,nodes.first().name,expanded,65,false,Modifier.width(158.dp),{expanded=!expanded},{})
                    WorkspaceAccordion(expanded) { LiquidGroupWell(group,nodes.first().name,emptyMap(),emptyMap(),{},{},{}) }
                }
            } }
        }
        compose.mainClock.autoAdvance=false
        capture("test134-motion-00")
        compose.onNodeWithTag("strategy:节点选择",true).performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(80)
        capture("test134-motion-01")
        compose.onNodeWithTag("strategy:节点选择",true).performTouchInput { up() }
        for(i in 2..8) {
            compose.mainClock.advanceTimeBy(48)
            capture("test134-motion-0$i")
        }
        compose.mainClock.autoAdvance=true
        compose.waitForIdle()
        compose.onNodeWithTag("sunken-well:节点选择",true).assertIsDisplayed()
        capture("test134-motion-09")
    }
}
