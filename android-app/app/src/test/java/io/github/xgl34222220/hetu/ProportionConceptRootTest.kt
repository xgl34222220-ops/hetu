package io.github.xgl34222220.hetu

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
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

/** Anonymous UI fixtures exercise actual layout; user screenshots/addresses never enter tests. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w393dp-h852dp-mdpi", application = Application::class,
    shadows = [ConceptRootBridgeShadow::class, ConceptMihomoClientShadow::class, ConceptRuntimeInspectorShadow::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ProportionConceptRootTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var vm: HetuViewModel
    @Before fun prepare() {
        app.getSharedPreferences("hetu", 0).edit().clear().putBoolean("enableBlur", false)
            .putBoolean("liquidGlass", false).putBoolean("proxySelectorShowHidden", true).commit()
        vm = newConceptTestVm(app)
        setConceptState(vm, conceptRunningState().copy(memoryBytes = 96L * 1024 * 1024))
        setConceptValue(vm, "runtime", ProxyRuntimeSnapshot(running = true, pid = 456, elapsedSeconds = 720,
            rssBytes = 96L * 1024 * 1024, wanAddress = "203.0.113.24", wanCountry = "测试地区", wanCountryCode = "SG"))
        setConceptValue(vm, "cpuSampleAvailable", true)
        setConceptValue(vm, "cpuPercent", 6.2f)
        setConceptValue(vm, "upRate", 312345L)
        setConceptValue(vm, "downRate", 1456123L)
        setConceptValue(vm, "localUpRate", 312345L)
        setConceptValue(vm, "localDownRate", 1456123L)
    }
    @After fun close() { if (::vm.isInitialized) rule.runOnIdle { closeConceptTestVm(vm) } }
    private fun tag(value: String) = rule.onNodeWithTag(value, useUnmergedTree = true)
    private fun render(tab: HxTab, section: String = "proxies", scale: Float = 1f) {
        vm.openPanel(section); vm.tab = tab
        rule.setContent {
            HetuAppTheme("light", dynamic = false) {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, scale), LocalHxMotionEnabled provides false) { HetuRoot(vm) }
            }
        }
        rule.waitForIdle()
    }
    private fun sameHeight(first: String, second: String) {
        val a = tag(first).getUnclippedBoundsInRoot()
        val b = tag(second).getUnclippedBoundsInRoot()
        assertEquals("paired cards align at their top", a.top, b.top)
        assertEquals("paired cards align at their bottom", a.bottom, b.bottom)
    }
    private fun capture(name: String) = rule.runOnIdle {
        val view = rule.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        File("build/outputs/hetu-concept-root/proportion-$name.png").also { file ->
            file.parentFile!!.mkdirs()
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        bitmap.recycle()
    }
    @Test fun homeMetricsUseAlignedCardsWithValidCpuAndResidentMemory() {
        render(HxTab.Home)
        sameHeight("home-address-card", "home-speed-card")
        sameHeight("home-subscription-card", "home-resources-card")
        rule.onNodeWithText("6.2%").assertExists()
        capture("01-home")
        tag("home-resources-card").performScrollTo()
        sameHeight("home-memory-row", "home-cpu-row")
        capture("02-home-scrolled")
    }
    @Test fun overviewPairsAlignAndCompositeTransportMetadataCountsCorrectly() {
        val connections = vm.state.connections.mapIndexed { i, entry -> entry.copy(network = if (i < 2) "tcp · Tun" else "udp · TProxy") }
        setConceptState(vm, vm.state.copy(connections = connections))
        render(HxTab.Panel, "overview")
        tag("overview-connections-card").performScrollTo()
        sameHeight("overview-connections-card", "overview-memory-card")
        rule.onNodeWithText("TCP  2").assertIsDisplayed()
        rule.onNodeWithText("UDP  1").assertIsDisplayed()
        capture("03-overview")
    }
    @Test fun currentStrategyNameUsesAvailableWidthAndToolbarKeeps48DpTargets() {
        val longName = "测试线路 Alpha 02 长名称"
        val first = vm.state.groups.first()
        val changed = first.copy(now = longName, nodes = first.nodes.mapIndexed { i, node -> if (i == 0) node.copy(name = longName) else node })
        setConceptState(vm, vm.state.copy(groups = listOf(changed) + vm.state.groups.drop(1)))
        render(HxTab.Panel)
        val layouts = mutableListOf<TextLayoutResult>()
        tag("strategy-current-${changed.name}").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertTrue(layouts.isNotEmpty())
        assertFalse("representative current name must not be ellipsized", layouts.single().hasVisualOverflow)
        val a = tag("strategy-group-${changed.name}").getUnclippedBoundsInRoot()
        val b = tag("strategy-group-${vm.state.groups[1].name}").getUnclippedBoundsInRoot()
        assertTrue("normal scale retains two columns", b.left >= a.right)
        assertEquals("wrapped and short names retain aligned card bottoms", a.bottom, b.bottom)
        for (label in listOf("搜索", "筛选", "排序与布局", "测速与 API")) {
            val bounds = rule.onNodeWithContentDescription(label).getUnclippedBoundsInRoot()
            assertTrue((bounds.bottom - bounds.top) >= 48.dp && (bounds.right - bounds.left) >= 48.dp)
        }
        capture("04-strategies")
    }
    @Test @Config(qualifiers = "w320dp-h820dp-mdpi")
    fun twoHundredPercentFontStacksResourceCardsWithoutSplittingUnitsIntoNarrowColumns() {
        render(HxTab.Home, scale = 2f)
        tag("home-resources-card").performScrollTo()
        tag("home-resources-card").performTouchInput { swipeUp() }
        val cpu = tag("home-cpu-row").getUnclippedBoundsInRoot()
        val memory = tag("home-memory-row").getUnclippedBoundsInRoot()
        val resources = tag("home-resources-card").getUnclippedBoundsInRoot()
        assertTrue(cpu.top >= memory.bottom)
        assertTrue((resources.right - resources.left) >= 288.dp)
        rule.onNodeWithText("96.0 MB").assertIsDisplayed()
        capture("05-home-320dp-200pct")
    }

    @Test fun settingsUsesFourSharedContainersForItsEightExistingRoutes() {
        render(HxTab.Settings)
        val first = tag("settings-group-0").getUnclippedBoundsInRoot()
        val basic = tag("settings-基础代理配置").getUnclippedBoundsInRoot()
        val advanced = tag("settings-高级代理配置").getUnclippedBoundsInRoot()
        assertTrue(basic.top >= first.top && advanced.bottom <= first.bottom)
        assertTrue(advanced.top >= basic.bottom)
        assertEquals(first.left, basic.left)
        tag("settings-group-3").performScrollTo().assertExists()
        tag("settings-关于").assertIsDisplayed()
        capture("06-settings-groups")
    }
}
