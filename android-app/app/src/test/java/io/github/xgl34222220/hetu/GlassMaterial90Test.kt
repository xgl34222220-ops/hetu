package io.github.xgl34222220.hetu

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import io.github.xgl34222220.hetu.home.*
import io.github.xgl34222220.hetu.ui.LocalHetuMotionEnabled
import java.io.File
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Current launcher screenshots and material/accessibility/state regressions; no live Root mutation. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "zh-rCN-w393dp-h852dp-mdpi", application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GlassMaterial90Test {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private lateinit var vm: HetuViewModel
    private lateinit var app: Application

    @Before fun prepare() {
        app = ApplicationProvider.getApplicationContext()
        app.getSharedPreferences("hetu", 0).edit().clear().putString("appearance", "light")
            .putBoolean("enableBlur", false).putBoolean("enableAnimations", false).commit()
        vm = HetuViewModel(app)
    }

    private fun settle() {
        rule.mainClock.advanceTimeBy(1000)
        rule.waitForIdle()
    }

    /** The real production composables are painted by the native graphics implementation. */
    private fun capture(name: String): Bitmap {
        rule.waitForIdle()
        lateinit var bitmap: Bitmap
        rule.runOnIdle {
            val view = rule.activity.window.decorView
            bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            val path = File("build/outputs/glass90/$name.png")
            path.parentFile!!.mkdirs()
            path.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        return bitmap
    }

    private fun launcherTabs(dark: Boolean) {
        app.getSharedPreferences("hetu", 0).edit().putString("appearance", if (dark) "dark" else "light").commit()
        rule.setContent {
            CompositionLocalProvider(LocalHetuMotionEnabled provides false) {
                HetuAppTheme(appearance = if (dark) "dark" else "light", dynamic = false) { HetuRoot(vm) }
            }
        }
        for (tab in HxTab.entries) {
            rule.runOnIdle { vm.tab = tab }
            settle()
            rule.onNodeWithTag("hetu-dock", useUnmergedTree = true).assertIsDisplayed()
            val bitmap = capture("${if (dark) "dark" else "light"}-${tab.name.lowercase()}")
            assertTrue("Rendered page must have actual content", bitmap.width > 300 && bitmap.height > 600)
            bitmap.recycle()
        }
    }

    @Test fun actualFourLauncherTabsKeepTheirDockInLightAppearance() = launcherTabs(false)
    @Test fun actualFourLauncherTabsKeepTheirDockInDarkAppearance() = launcherTabs(true)

    @Test fun pureBlackCanvasStaysBlackAndMaterialDoesNotAnimateWithMotionOff() {
        rule.setContent {
            CompositionLocalProvider(LocalHetuMotionEnabled provides false) {
                HetuAppTheme(appearance = "dark", dynamic = false, pureBlack = true) {
                    Box(Modifier.fillMaxSize().homeDiffuseCanvas()) {
                        HomeCard(Modifier.padding(24.dp).fillMaxWidth()) {
                            Text("纯黑画布上的可读内容", Modifier.padding(20.dp), color = LocalHomeColors.current.t1)
                        }
                    }
                }
            }
        }
        settle()
        val first = capture("pure-black-motion-off")
        assertEquals(android.graphics.Color.BLACK, first.getPixel(4, first.height / 2))
        rule.mainClock.advanceTimeBy(5_000)
        val second = capture("pure-black-motion-off-after-5s")
        assertTrue("Static material must not trigger a moving gradient", first.sameAs(second))
        first.recycle(); second.recycle()
    }

    @Test fun raisedDialogKeepsCancelAndBusyRepeatedClickGuards() {
        var shown by mutableStateOf(true)
        var busy by mutableStateOf(false)
        var confirms = 0
        var cancels = 0
        rule.setContent {
            CompositionLocalProvider(LocalHetuMotionEnabled provides false) {
                HetuAppTheme("light", false) {
                    Box(Modifier.fillMaxSize().homeDiffuseCanvas().padding(24.dp)) {
                        if (shown) HomeDialogCard("保留配置", "继续", { confirms++; busy = true }, { cancels++; shown = false },
                            text = "操作期间保留当前配置和已知连接。", confirmLoading = busy)
                        else HomeButton("重新打开", { shown = true; busy = false })
                    }
                }
            }
        }
        settle()
        capture("dialog-ready").recycle()
        rule.onNodeWithText("取消").performClick()
        assertEquals(1, cancels)
        rule.onNodeWithText("重新打开").performClick()
        rule.onNodeWithText("继续").performClick()
        settle()
        rule.onNodeWithText("继续").assertIsNotEnabled()
        rule.onNodeWithText("取消").assertIsNotEnabled()
        rule.onNodeWithText("继续").performClick()
        assertEquals("Busy confirmation must run only once", 1, confirms)
        capture("dialog-busy").recycle()
    }

    @Test fun compactLargeTextKeepsTheMaterialAndItsActionsReachable() {
        var callbacks = 0
        rule.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, 1.6f), LocalHetuMotionEnabled provides false) {
                HetuAppTheme("light", false) {
                    Box(Modifier.width(320.dp).fillMaxHeight().homeDiffuseCanvas()) {
                        HomeBarScaffold("连接详情", {}, false) { top, _ ->
                            Column(Modifier.fillMaxSize().padding(horizontal = 14.dp).padding(top = top + 8.dp)) {
                                HomeCard(Modifier.fillMaxWidth()) {
                                    Text("这是需要完整阅读的连接状态与恢复说明", Modifier.padding(18.dp), color = LocalHomeColors.current.t1)
                                    HomeButton("返回面板", { callbacks++ }, Modifier.padding(18.dp).fillMaxWidth())
                                }
                            }
                        }
                    }
                }
            }
        }
        settle()
        rule.onNodeWithText("返回面板").assertIsDisplayed().performClick()
        assertEquals(1, callbacks)
        capture("compact-large-text").recycle()
    }

    @Test fun faintMetadataRemainsReadableInBothAppearancesAndEveryAccent() {
        fun ratio(a: Color, b: Color): Float = (maxOf(a.luminance(), b.luminance()) + .05f) / (minOf(a.luminance(), b.luminance()) + .05f)
        for (dark in listOf(false, true)) for (accent in HomeAccent.entries) {
            val c = homeColors(dark, accent.color(dark))
            assertTrue("Metadata contrast for $dark / $accent", ratio(c.t3, c.surface) >= 4.5f)
            assertTrue("Body contrast for $dark / $accent", ratio(c.t2, c.surface) >= 4.5f)
            assertTrue("Hero caption and title contrast for $dark / $accent", ratio(c.t1, c.hero) >= 4.5f)
            assertTrue("Custom/accent button contrast for $dark / $accent", ratio(c.onAccent, c.accent) >= 4.5f)
        }
    }

    @Test fun runningCoreWithController401ShowsUnconfirmedConnectionsAndKeepsStopAction() {
        var stops = 0
        val state = HomeUiState(status = HomeStatus.Running(120), config = "保留配置.yaml",
            connection = HomeConnectionObservation(controllerReadFailed = true, controllerError = "控制接口鉴权失败 (401)", takeoverHealthy = true))
        assertEquals(HomeGlyphMode.Attention, state.homeHeroGlyphMode())
        rule.setContent {
            CompositionLocalProvider(LocalHetuMotionEnabled provides false) {
                HetuAppTheme("light", false) { HomeRoute(state, HomeActions(onStop = { stops++ }), motion = false) }
            }
        }
        settle()
        rule.onNodeWithText("控制接口异常 · 连接状态未确认").assertIsDisplayed()
        rule.onNodeWithText("控制接口鉴权失败 (401)").assertIsDisplayed()
        rule.onNodeWithText("停止").performClick()
        assertEquals(1, stops)
        val bitmap = capture("home-controller-401")
        assertCaptionContrast(bitmap, "light")
        bitmap.recycle()
    }

    /** Sample the actual rendered caption ink against a text-free part of its actual material. */
    private fun assertCaptionContrast(bitmap: Bitmap, appearance: String) {
        val hero = rule.onNodeWithTag("home-status-material").getUnclippedBoundsInRoot()
        val caption = rule.onNodeWithTag("home-connection-caption").getUnclippedBoundsInRoot()
        val background = Color(bitmap.getPixel((hero.left.value + 36f).toInt(), (hero.top.value + 5f).toInt()))
        val dark = appearance == "dark"
        var strongest = if (dark) 0f else 1f
        val left = caption.left.value.toInt().coerceAtLeast(0)
        val right = caption.right.value.toInt().coerceAtMost(bitmap.width)
        val top = caption.top.value.toInt().coerceAtLeast(0)
        val bottom = caption.bottom.value.toInt().coerceAtMost(bitmap.height)
        for (y in top until bottom) for (x in left until right) {
            val lum = Color(bitmap.getPixel(x, y)).luminance()
            strongest = if (dark) maxOf(strongest, lum) else minOf(strongest, lum)
        }
        val bg = background.luminance()
        val contrast = (maxOf(bg, strongest) + .05f) / (minOf(bg, strongest) + .05f)
        assertTrue("Rendered hero caption must remain readable: $appearance / $contrast", contrast >= 4.5f)
    }

    @Test fun localTakeoverChecksNeverBecomeAnInternetHealthClaim() {
        val original = HomeUiState(status = HomeStatus.Running(600))
        assertEquals(HomeGlyphMode.Unconfirmed, original.homeHeroGlyphMode())
        val degraded = original.copy(connection = HomeConnectionObservation(controllerReady = true, takeoverHealthy = false))
        assertEquals(HomeGlyphMode.Attention, degraded.homeHeroGlyphMode())
        val localReady = original.copy(connection = HomeConnectionObservation(controllerReady = true, takeoverHealthy = true))
        assertEquals(HomeGlyphMode.On, localReady.homeHeroGlyphMode())
        assertTrue(localReady.connection.health.caption.contains("出口未验证"))
        @Suppress("UNCHECKED_CAST")
        fun actualState(state: ProxyComposeState) {
            val field = HetuViewModel::class.java.getDeclaredField("state\$delegate").apply { isAccessible = true }
            (field.get(vm) as MutableState<ProxyComposeState>).value = state
        }
        // A failed current probe can retain the previous schema; that cached schema is not
        // evidence that the current local takeover was observed unhealthy.
        val unobserved = ProxyComposeState(running = true, panelReady = true, runtimeSchema = 3,
            dataPlaneHealthy = false, healthObserved = false)
        actualState(unobserved)
        rule.setContent {
            CompositionLocalProvider(LocalHetuMotionEnabled provides false) {
                HetuAppTheme("dark", false) { NewUiHome(vm, 16.dp) {} }
            }
        }
        settle()
        rule.onNodeWithText("接管状态未确认 · 出口未验证").assertIsDisplayed()
        capture("home-takeover-unobserved").recycle()
        rule.runOnIdle { actualState(unobserved.copy(healthObserved = true)) }
        settle()
        rule.onNodeWithText("接管检查异常 · 出口未验证").assertIsDisplayed()
        val bitmap = capture("home-takeover-degraded")
        assertCaptionContrast(bitmap, "dark")
        bitmap.recycle()
        rule.runOnIdle { actualState(unobserved.copy(healthObserved = true, dataPlaneHealthy = true)) }
        settle()
        rule.onNodeWithText("接管检查通过 · 出口未验证").assertIsDisplayed()
        capture("home-local-checks-ready").recycle()
    }
}
