package io.github.xgl34222220.hetu

import android.animation.ValueAnimator
import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import io.github.xgl34222220.hetu.home.*
import io.github.xgl34222220.hetu.panel.*
import io.github.xgl34222220.hetu.ui.LocalHetuMotionEnabled
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Actual V2 presentation and launcher adapter; local original icons and no live core or Root. */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "zh-rCN-w393dp-h852dp-mdpi", application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NewHomePanelInteractionTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val prefs get() = app.getSharedPreferences("hetu", 0)
    private var vm: HetuViewModel? = null
    private val nodeA = PanelNode("香港 Alpha", protocol = "VLESS", udp = true)
    private val nodeB = PanelNode("日本 Beta", protocol = "Trojan")
    private val group = PanelGroup("节点选择", "Selector", listOf(nodeA, nodeB), nodeA.name)
    private val data = PanelData(status = PanelStatus.Running, groups = listOf(group),
        delays = mapOf(nodeA.name to PanelDelay.Ms(44), nodeB.name to PanelDelay.Ms(72)))

    @Before fun prepare() {
        prefs.edit().clear().putString("appearance", "light").putBoolean("enableBlur", false).commit()
    }
    @After fun finish() {
        vm?.viewModelScope?.cancel()
        ValueAnimator::class.java.getDeclaredMethod("setDurationScale", Float::class.javaPrimitiveType)
            .invoke(null, 1f)
    }

    private fun home(refreshing: MutableState<Boolean>, refresh: () -> Unit) {
        rule.setContent {
            CompositionLocalProvider(LocalHetuMotionEnabled provides false) {
                HetuHomeTheme(dark = false) {
                    HomeScreen(HomeUiState(ipRefreshing = refreshing.value), HomeActions(onRefreshIp = refresh),
                        {}, {}, {}, {}, contentPadding = PaddingValues(bottom = 16.dp), motion = false)
                }
            }
        }
    }

    @Test fun physicalHomePullRunsTheActualRefreshCallback() {
        val refreshing = mutableStateOf(false)
        var calls = 0
        home(refreshing) { calls++; refreshing.value = true }
        rule.onNodeWithTag("refresh:刷新首页状态").performTouchInput {
            swipe(Offset(center.x, 100f), Offset(center.x, 560f), 700)
        }
        rule.waitForIdle()
        assertEquals(1, calls)
        assertTrue(refreshing.value)
        rule.runOnIdle { refreshing.value = false }
    }

    @Test fun accessibleHomeRefreshCannotDuplicateAnInflightRead() {
        val refreshing = mutableStateOf(false)
        var calls = 0
        home(refreshing) { calls++; refreshing.value = true }
        val page = rule.onNodeWithTag("refresh:刷新首页状态")
        page.performCustomAccessibilityActionWithLabel("刷新首页状态")
        page.performCustomAccessibilityActionWithLabel("刷新首页状态")
        assertEquals(1, calls)
        rule.runOnIdle { refreshing.value = false }
        page.performCustomAccessibilityActionWithLabel("刷新首页状态")
        assertEquals(2, calls)
        rule.runOnIdle { refreshing.value = false }
    }

    @Test fun everyPanelTabHasAnAccessibleRefreshAndBusyGuard() {
        val tab = mutableStateOf(PanelTab.Groups)
        val refreshing = mutableStateOf(false)
        val calls = mutableListOf<PanelTab>()
        rule.setContent {
            CompositionLocalProvider(LocalHetuMotionEnabled provides false) {
                HetuHomeTheme(dark = false) {
                    PanelRoute(data.copy(refreshing = refreshing.value), tab.value, { tab.value = it },
                        PanelActions(onRefresh = { calls += tab.value; refreshing.value = true }),
                        contentPadding = PaddingValues(bottom = 16.dp))
                }
            }
        }
        PanelTab.entries.forEach { next ->
            rule.runOnIdle { tab.value = next }
            val label = "刷新${next.label}"
            rule.onNodeWithTag("refresh:$label").performCustomAccessibilityActionWithLabel(label)
            rule.onNodeWithTag("refresh:$label").performCustomAccessibilityActionWithLabel(label)
            rule.runOnIdle { refreshing.value = false }
        }
        assertEquals(PanelTab.entries.toList(), calls)
    }

    private fun iconFile(): File {
        val file = File(app.cacheDir, "original-v2-policy-icon.png")
        val bitmap = Bitmap.createBitmap(24, 24, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.BLUE)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        return file
    }

    @Suppress("UNCHECKED_CAST")
    private fun setState(target: HetuViewModel, state: ProxyComposeState) {
        val field = HetuViewModel::class.java.getDeclaredField("state\$delegate").apply { isAccessible = true }
        (field.get(target) as MutableState<ProxyComposeState>).value = state
    }

    private fun configuredPanel(columns: Int): HetuViewModel {
        prefs.edit().putInt("proxySelectorGroupColumns", columns).commit()
        val target = HetuViewModel(app)
        vm = target
        target.openPanel("proxies")
        setState(target, ProxyComposeState(running = true, panelReady = true,
            groups = listOf(ProxyGroupUi("配置策略", "Selector", "Alpha", listOf(ProxyNodeUi("Alpha")),
                iconPath = iconFile().absolutePath))))
        rule.setContent {
            CompositionLocalProvider(LocalHetuMotionEnabled provides false) {
                HetuAppTheme(appearance = "light", dynamic = false) { NewUiPanel(target, 16.dp) }
            }
        }
        rule.waitUntil(5_000) {
            rule.onAllNodesWithTag("configured-icon:配置策略", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        return target
    }

    private fun snapshot(name: String) {
        rule.waitForIdle()
        rule.runOnIdle {
            val view = rule.activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            val path = File("build/outputs/ui83/$name.png")
            path.parentFile!!.mkdirs()
            path.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }

    @Test fun launcherTwoColumnCardsShowTheConfiguredArtwork() {
        configuredPanel(2)
        rule.onNodeWithTag("configured-icon:配置策略", useUnmergedTree = true).assertIsDisplayed().assertWidthIsEqualTo(28.dp)
        rule.onNodeWithContentDescription("配置策略 配置图标", useUnmergedTree = true).assertExists()
        snapshot("panel-configured-icon-two-columns")
    }

    @Test fun launcherOneColumnCardsKeepTheConfiguredArtwork() {
        configuredPanel(1)
        rule.onNodeWithTag("configured-icon:配置策略", useUnmergedTree = true).assertIsDisplayed().assertWidthIsEqualTo(28.dp)
        snapshot("panel-configured-icon-one-column")
    }

    @Test fun changingTheCoreGroupRemovesStaleConfiguredArtwork() {
        val target = configuredPanel(2)
        rule.runOnIdle {
            setState(target, target.state.copy(groups = target.state.groups.map { it.copy(iconPath = "", iconUrl = "") }))
        }
        rule.waitUntil(5_000) {
            rule.onAllNodesWithTag("configured-icon:配置策略", useUnmergedTree = true).fetchSemanticsNodes().isEmpty()
        }
        rule.onNodeWithTag("group-icon-slot:配置策略", useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithContentDescription("配置策略 未配置图标", useUnmergedTree = true).assertExists()
    }

    private fun expandedPanel(scale: Float = 1f, calls: MutableList<String> = mutableListOf()) {
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalHetuMotionEnabled provides false,
                LocalDensity provides Density(density.density, scale)) {
                HetuHomeTheme(dark = false) {
                    PanelRoute(data, PanelTab.Groups, {}, PanelActions(
                        onSelectNode = { _, node -> calls += "select:$node" },
                        onTestNode = { node -> calls += "test:$node" }),
                        initialView = PanelViewState(expandedGroups = listOf(group.name)),
                        contentPadding = PaddingValues(bottom = 16.dp))
                }
            }
        }
    }

    @Test fun expandedNodesAreReadableAndLatencyTapDoesNotSelect() {
        val calls = mutableListOf<String>()
        expandedPanel(calls = calls)
        val card = rule.onNodeWithTag("panel-node:${nodeA.name}")
        card.assertIsSelected().assertHeightIsAtLeast(92.dp)
        val delay = rule.onNode(hasText("44 ms") and hasClickAction() and hasAnyAncestor(hasTestTag("panel-node:${nodeA.name}")))
        delay.performClick()
        assertEquals(listOf("test:${nodeA.name}"), calls)
        card.performTouchInput { click(Offset(18f, 18f)) }
        assertEquals(listOf("test:${nodeA.name}", "select:${nodeA.name}"), calls)
        snapshot("panel-expanded-nodes")
    }

    @Test fun largeFontsUseFullWidthRowsWithoutChangingSavedColumns() {
        expandedPanel(scale = 1.6f)
        val first = rule.onNodeWithTag("panel-node:${nodeA.name}").getUnclippedBoundsInRoot()
        val second = rule.onNodeWithTag("panel-node:${nodeB.name}").getUnclippedBoundsInRoot()
        assertEquals(first.left, second.left)
        assertEquals(first.right, second.right)
        assertTrue(second.top >= first.bottom)
        assertTrue(first.width >= 300.dp)
        val saved = PanelGroupLayout(groupColumns = 2, nodeColumns = 2)
        val adaptive = PanelLogic.layoutForViewport(saved, 393, 1.6f)
        assertEquals(1, adaptive.groupColumns)
        assertEquals(1, adaptive.nodeColumns)
        assertEquals(2, saved.groupColumns)
        assertEquals(saved, PanelLogic.layoutForViewport(saved, 393, 1f))
        val single = saved.copy(groupColumns = 1, nodeColumns = 1)
        assertEquals(single, PanelLogic.layoutForViewport(single, 700, 1f))
        snapshot("panel-large-font-nodes")
    }

    @Test fun zeroSystemAnimatorScaleDisablesTheActualHomeMotionProvider() {
        ValueAnimator::class.java.getDeclaredMethod("setDurationScale", Float::class.javaPrimitiveType)
            .invoke(null, 0f)
        rule.setContent {
            CompositionLocalProvider(LocalHetuMotionEnabled provides true) {
                HetuHomeTheme(dark = false) { Text(if (LocalHomeMotionEnabled.current) "动效开启" else "动效关闭") }
            }
        }
        rule.onNodeWithText("动效关闭").assertExists()
    }
}
