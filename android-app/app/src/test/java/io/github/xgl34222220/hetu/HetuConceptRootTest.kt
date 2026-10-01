package io.github.xgl34222220.hetu

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Runs the launcher composition itself. IO is blocked by test-only client/Root shadows. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w393dp-h852dp-mdpi", application = Application::class,
    shadows = [ConceptRootBridgeShadow::class, ConceptMihomoClientShadow::class, ConceptRuntimeInspectorShadow::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HetuConceptRootTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var vm: HetuViewModel

    @Before fun clean() {
        app.getSharedPreferences("hetu", Context.MODE_PRIVATE).edit().clear()
            .putBoolean("enableBlur", false).putBoolean("liquidGlass", false)
            .putBoolean("proxySelectorShowHidden", true).commit()
        vm = newConceptTestVm(app)
    }
    @After fun close() { if (::vm.isInitialized) rule.runOnIdle { closeConceptTestVm(vm) } }
    private fun render(tab: HxTab = HxTab.Panel, section: String = "proxies", fontScale: Float = 1f, dark: Boolean = false, running: Boolean = true, motion: Boolean = false, followAppearance: Boolean = false) {
        if (running) setConceptState(vm, conceptRunningState())
        setConceptValue(vm, "rules", conceptRules())
        setConceptValue(vm, "ruleSets", conceptRuleSets())
        vm.openPanel(section)
        vm.tab = tab
        rule.setContent {
            HetuAppTheme(if (followAppearance) vm.appearance else if (dark) "dark" else "light", dynamic = false,
                accentHex = if (followAppearance) vm.accentHex else "", pureBlack = followAppearance && vm.pureBlack) {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale), LocalHxMotionEnabled provides motion) {
                    HetuRoot(vm)
                }
            }
        }
        rule.waitForIdle()
    }
    private fun node(tag: String) = rule.onNodeWithTag(tag, useUnmergedTree = true)
    private fun screenshot(name: String) {
        rule.waitForIdle()
        rule.runOnIdle {
            val view = rule.activity.window.decorView
            assertTrue("actual root must be measured", view.width > 0 && view.height > 0)
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            val softwareCanvas = Canvas(bitmap)
            assertFalse("capture must exercise software Canvas fallback", softwareCanvas.isHardwareAccelerated)
            view.draw(softwareCanvas)
            val file = File("build/outputs/hetu-concept-root/$name.png")
            file.parentFile!!.mkdirs()
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
    private fun back() { rule.runOnIdle { rule.activity.onBackPressedDispatcher.onBackPressed() }; rule.waitForIdle() }

    @Test fun homeUsesActualRootAndKeepsTheDockVisibleWithSoftwareGlassFallback() {
        rule.runOnIdle {
            vm.prefs.edit().putBoolean("enableBlur", true).putBoolean("liquidGlass", true).commit()
            vm.reloadAppearance()
            setConceptValue(vm, "runtime", ProxyRuntimeSnapshot(running = true, pid = 456, elapsedSeconds = 3661, rssBytes = 132120576L, wanAddress = "203.0.113.24", wanCountryCode = "SG", wanCountry = "测试地区"))
        }
        render(tab = HxTab.Home)
        node("hetu-dock").assertIsDisplayed()
        rule.onNodeWithText("停止").assertIsDisplayed()
        rule.onNodeWithText("203.0.113.24").assertIsDisplayed()
        screenshot("00-actual-root-home-software-glass-fallback")
    }

    @Test fun actualMotionClockCapturesInlineExpansionCollapseAndPageTransitionFrames() {
        render(motion = true)
        val group = vm.state.groups.first()
        val following = "strategy-group-${vm.state.groups[2].name}"
        val before = node(following).getUnclippedBoundsInRoot().top
        rule.mainClock.autoAdvance = false
        node("strategy-group-${group.name}").performClick()
        rule.mainClock.advanceTimeBy(80)
        screenshot("motion-01-expansion-middle")
        rule.mainClock.advanceTimeBy(700)
        val expandedTop = node(following).getUnclippedBoundsInRoot().top
        assertTrue("expansion must move following groups", expandedTop > before)
        screenshot("motion-02-expansion-settled")
        rule.onNodeWithContentDescription("收起策略 ${group.name}").performClick()
        rule.mainClock.advanceTimeBy(80)
        screenshot("motion-03-collapse-middle")
        rule.mainClock.advanceTimeBy(700)
        assertEquals(before, node(following).getUnclippedBoundsInRoot().top)
        screenshot("motion-04-collapse-settled")
        node("panel-tab-rules").performClick()
        rule.mainClock.advanceTimeBy(120)
        screenshot("motion-05-panel-transition-middle")
        rule.mainClock.advanceTimeBy(900)
        node("panel-rules-summary").assertIsDisplayed()
        screenshot("motion-06-panel-transition-settled")
        rule.mainClock.autoAdvance = true
    }

    @Test fun realRootShowsSevenPanelsAndTwoColumnStrategyCards() {
        render()
        node("hetu-route-main").assertExists()
        node("hetu-dock").assertIsDisplayed()
        HxPanelSections.forEach { (key, _) -> node("panel-tab-$key").assertExists() }
        val first = vm.state.groups[0].name
        val second = vm.state.groups[1].name
        val a = node("strategy-group-$first").getUnclippedBoundsInRoot()
        val b = node("strategy-group-$second").getUnclippedBoundsInRoot()
        assertEquals(a.top, b.top)
        assertTrue(a.right < b.left)
        assertTrue("group must have readable height", (a.bottom - a.top) >= 110.dp)
        screenshot("01-root-strategy-groups")
    }

    @Test fun expansionIsInlineWithRealHeaderAndTwoNodeColumnsAndCanBeRepeated() {
        render()
        val group = vm.state.groups.first()
        repeat(2) {
            node("strategy-group-${group.name}").performClick()
            node("strategy-expanded-${group.name}").assertIsDisplayed()
            node("hetu-dock").assertIsDisplayed()
            val a = node("strategy-node-${group.name}-${group.nodes[0].name}").getUnclippedBoundsInRoot()
            val b = node("strategy-node-${group.name}-${group.nodes[1].name}").getUnclippedBoundsInRoot()
            assertEquals(a.top, b.top)
            assertTrue(a.right < b.left)
            node("strategy-node-${group.name}-${group.now}").assertIsSelected()
            rule.onAllNodes(isDialog()).assertCountEquals(0)
            screenshot("02-root-inline-nodes-$it")
            rule.onNodeWithContentDescription("收起策略 ${group.name}").performClick()
            node("strategy-expanded-${group.name}").assertDoesNotExist()
        }
    }

    @Test fun largeFontsUseOneColumnWithoutCompressingNodeHeight() {
        render(fontScale = 1.6f)
        val group = vm.state.groups.first()
        node("strategy-group-${group.name}").performClick()
        val first = node("strategy-node-${group.name}-${group.nodes[0].name}")
        first.performScrollTo().assertIsDisplayed()
        val a = first.getUnclippedBoundsInRoot()
        val b = node("strategy-node-${group.name}-${group.nodes[1].name}").getUnclippedBoundsInRoot()
        assertEquals(a.left, b.left)
        assertTrue(b.top >= a.bottom)
        assertTrue((a.bottom - a.top) >= 82.dp)
        screenshot("03-root-strategy-large-font")
    }

    @Test @Config(qualifiers = "w320dp-h820dp-mdpi")
    fun twoHundredPercentFontStacksPanelActionsBelowTheTitle() {
        render(fontScale = 2f)
        val search = rule.onNodeWithContentDescription("搜索").fetchSemanticsNode().boundsInRoot
        val title = rule.onAllNodesWithText("面板").fetchSemanticsNodes().map { it.boundsInRoot }.minBy { it.top }
        assertTrue("actions must be below large title", search.top >= title.bottom)
        node("panel-tab-proxies").assertIsDisplayed()
        screenshot("03b-root-narrow-200-percent-font")
    }

    @Test fun pendingSelectionKeepsOldCheckAndRepeatedPressSendsOnlyOneRequest() {
        render()
        rule.runOnIdle { enableConceptActionMode(vm) }
        val group = vm.state.groups.first()
        node("strategy-group-${group.name}").performClick()
        val gate = blockNextConceptSelection()
        val next = node("strategy-node-${group.name}-${group.nodes[1].name}")
        next.performClick()
        assertTrue("selection must reach the actual API boundary", gate.awaitStarted())
        next.performTouchInput { click() }
        node("strategy-node-${group.name}-${group.now}").assertIsSelected()
        next.assertIsNotSelected()
        assertEquals(1, conceptSelectionRequestCount())
        screenshot("11-root-selection-pending")
        gate.succeed()
        rule.waitUntil(10_000) { org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle(); vm.pendingSelection.isEmpty() && vm.state.groups.any { it.name == group.name && it.now == group.nodes[1].name } }
        next.assertIsSelected()
        screenshot("12-root-selection-confirmed")
    }

    @Test fun failedSelectionKeepsOldNodeAndAllowsAConfirmedRetry() {
        render()
        rule.runOnIdle { enableConceptActionMode(vm) }
        val group = vm.state.groups.first()
        node("strategy-group-${group.name}").performClick()
        val next = node("strategy-node-${group.name}-${group.nodes[1].name}")
        val failed = blockNextConceptSelection()
        next.performClick()
        assertTrue(failed.awaitStarted())
        failed.fail("测试网络暂时不可用")
        rule.waitUntil(10_000) { org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle(); vm.pendingSelection.isEmpty() }
        node("strategy-node-${group.name}-${group.now}").assertIsSelected()
        next.assertIsNotSelected()
        screenshot("13-root-selection-failed")
        val retry = blockNextConceptSelection()
        next.performClick()
        assertTrue(retry.awaitStarted())
        retry.succeed()
        rule.waitUntil(10_000) { org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle(); vm.pendingSelection.isEmpty() && vm.state.groups.any { it.name == group.name && it.now == group.nodes[1].name } }
        next.assertIsSelected()
        assertEquals(2, conceptSelectionRequestCount())
    }

    @Test fun cancelledSelectionReleasesPendingAndKeepsThePreviousCheck() {
        render()
        rule.runOnIdle { enableConceptActionMode(vm) }
        val group = vm.state.groups.first()
        node("strategy-group-${group.name}").performClick()
        val gate = blockNextConceptSelection()
        node("strategy-node-${group.name}-${group.nodes[1].name}").performClick()
        assertTrue(gate.awaitStarted())
        gate.cancel()
        rule.waitUntil(10_000) { org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle(); vm.pendingSelection.isEmpty() }
        node("strategy-node-${group.name}-${group.now}").assertIsSelected()
        node("strategy-node-${group.name}-${group.nodes[1].name}").assertIsNotSelected()
    }

    @Test fun longPressShowsCompleteNodeMetadataWithoutMeasuringOrSelecting() {
        val longName = "香港测试专线 · 完整名称不可省略 · 原始节点标识 0123456789"
        val target = ProxyNodeUi(longName, "VLESS", true, 88, "测试订阅提供商")
        val original = conceptRunningState()
        val group = original.groups.first().copy(nodes = original.groups.first().nodes + target)
        rule.runOnIdle { setConceptState(vm, original.copy(groups = listOf(group))) }
        render(running = false)
        node("strategy-group-${group.name}").performClick()
        val next = node("strategy-node-${group.name}-$longName")
        next.performScrollTo().performTouchInput { longClick() }
        node("strategy-node-details").assertIsDisplayed()
        rule.onAllNodesWithText(longName).filter(hasAnyAncestor(hasTestTag("strategy-node-details"))).assertCountEquals(1)
        rule.onNodeWithText("协议：VLESS").assertIsDisplayed()
        rule.onNodeWithText("提供商：测试订阅提供商").assertIsDisplayed()
        assertEquals(0, conceptSelectionRequestCount())
        assertTrue(ConceptTestIo.calls.none { it.startsWith("DELAY:") })
        assertTrue(vm.delays.isEmpty())
        // ModalBottomSheet owns a separate window. Draw that actual dialog's decorView,
        // not the activity beneath it; this remains software-rendered layout evidence.
        rule.runOnIdle {
            val dialog = requireNotNull(org.robolectric.shadows.ShadowDialog.getLatestDialog())
            assertTrue(dialog.isShowing)
            val view = requireNotNull(dialog.window).decorView
            assertTrue(view.width > 0 && view.height > 0)
            val detailsBitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(detailsBitmap))
            val detailsFile = File("build/outputs/hetu-concept-root/14-root-complete-node-details.png")
            detailsFile.parentFile!!.mkdirs()
            detailsFile.outputStream().use { detailsBitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            detailsBitmap.recycle()
        }
        rule.onNodeWithContentDescription("关闭节点信息").performClick()
        node("strategy-node-details").assertDoesNotExist()
        node("strategy-node-${group.name}-${group.now}").assertIsSelected()
        next.assertIsNotSelected()
    }

    @Test fun independentDelayTargetMeasuresTheRawNodeWithoutSelectingIt() {
        render()
        rule.runOnIdle { enableConceptActionMode(vm) }
        val group = vm.state.groups.first()
        val name = group.nodes[1].name
        node("strategy-group-${group.name}").performClick()
        node("strategy-delay-${group.name}-$name").performTouchInput { click() }
        rule.waitUntil(10_000) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            vm.delays[name] == 125L && vm.testingNodes.isEmpty()
        }
        assertEquals(listOf("DELAY:$name"), ConceptTestIo.calls.filter { it.startsWith("DELAY:") })
        assertEquals(0, conceptSelectionRequestCount())
        node("strategy-node-${group.name}-${group.now}").assertIsSelected()
        node("strategy-node-${group.name}-$name").assertIsNotSelected()
    }

    @Test fun automaticGroupMeasurementUsesIndividualProbesAndKeepsThePinnedNode() {
        render()
        rule.runOnIdle { enableConceptActionMode(vm) }
        val group = vm.state.groups.first { it.type == "URLTest" }
        node("strategy-group-${group.name}").performClick()
        rule.onNodeWithContentDescription("全部测速 ${group.name}").performClick()
        rule.waitUntil(10_000) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            group.nodes.all { vm.delays[it.name] == 125L } && vm.testingGroups.isEmpty() && vm.testingNodes.isEmpty()
        }
        assertEquals(group.nodes.map { "DELAY:${it.name}" }.toSet(),
            ConceptTestIo.calls.filter { it.startsWith("DELAY:") }.toSet())
        assertEquals(group.nodes.size, ConceptTestIo.calls.count { it.startsWith("DELAY:") })
        assertFalse(ConceptTestIo.calls.any { it.startsWith("GROUP_DELAY:") })
        assertEquals(0, conceptSelectionRequestCount())
        assertEquals(group.now, ConceptTestIo.state.groups.first { it.name == group.name }.now)
        node("strategy-node-${group.name}-${group.now}").assertIsSelected()
    }

    @Test fun groupMeasurementPreservesTransportFailureAndOnlyRecordsConfirmedProbeFailures() {
        render()
        rule.runOnIdle { enableConceptActionMode(vm) }
        val group = vm.state.groups.first()
        val names = group.nodes.map { it.name }
        // Inspect timestamp retention without widening the production VM API.
        @Suppress("UNCHECKED_CAST")
        val stamps = HetuViewModel::class.java.getDeclaredField("measuredAt").run {
            isAccessible = true
            get(vm) as MutableMap<String, Long>
        }
        rule.runOnIdle {
            names.forEach { vm.delays[it] = 88L; stamps[it] = 9_999L }
            ConceptTestIo.delayFailures[names[0]] = java.io.IOException("Synthetic controller transport failure")
            ConceptTestIo.delayFailures[names[1]] = MihomoControllerClient.DelayFailure(true)
            ConceptTestIo.delayFailures[names[2]] = MihomoControllerClient.DelayFailure(false)
        }
        node("strategy-group-${group.name}").performClick()
        rule.onNodeWithContentDescription("全部测速 ${group.name}").performClick()
        rule.waitUntil(10_000) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            ConceptTestIo.calls.count { it.startsWith("DELAY:") } == names.size && vm.testingGroups.isEmpty() && vm.testingNodes.isEmpty()
        }
        assertEquals(88L, vm.delays[names[0]])
        assertEquals(9_999L, stamps[names[0]])
        assertEquals(-1L, vm.delays[names[1]])
        assertEquals(-2L, vm.delays[names[2]])
        assertNotEquals(9_999L, stamps[names[1]])
        assertNotEquals(9_999L, stamps[names[2]])
        assertFalse(ConceptTestIo.calls.any { it.startsWith("GROUP_DELAY:") })
        node("strategy-node-${group.name}-${group.now}").assertIsSelected()
    }

    @Test fun actualHomeCpuIsUnknownUntilTwoSamplesAndAgainAfterFailureOrRestart() {
        render(tab = HxTab.Home)
        rule.runOnIdle { enableConceptActionMode(vm) }
        fun sample(at: Long, pid: Int = 42) = rule.runOnIdle {
            ConceptTestIo.runtimeFailure = null
            ConceptTestIo.runtimeSample = ProxyRuntimeSnapshot(running = true, pid = pid,
                elapsedSeconds = at / 1000, processTicks = 100L, systemTicks = at,
                processSampleAtElapsed = at, processSampleValid = true)
            kotlinx.coroutines.runBlocking { vm.refreshNow() }
        }
        rule.onNodeWithText("等待有效采样").assertExists()
        rule.onNodeWithText("0.0%").assertDoesNotExist()
        sample(1_000)
        assertFalse(vm.cpuSampleAvailable)
        sample(2_000)
        assertTrue(vm.cpuSampleAvailable)
        rule.onNodeWithText("0.0%").assertExists() // A measured idle interval is a real zero.
        rule.runOnIdle {
            ConceptTestIo.runtimeFailure = java.io.IOException("CPU sample unavailable")
            kotlinx.coroutines.runBlocking { vm.refreshNow() }
        }
        assertFalse(vm.cpuSampleAvailable)
        rule.onNodeWithText("0.0%").assertDoesNotExist()
        rule.onNodeWithText("等待有效采样").assertExists()
        sample(3_000)
        assertFalse(vm.cpuSampleAvailable)
        sample(4_000)
        assertTrue(vm.cpuSampleAvailable)
        sample(5_000, pid = 43)
        assertFalse(vm.cpuSampleAvailable)
        sample(6_000, pid = 43)
        assertTrue(vm.cpuSampleAvailable)
        rule.runOnIdle {
            ConceptTestIo.state = ConceptTestIo.state.copy(running = false)
            kotlinx.coroutines.runBlocking { vm.refreshNow() }
        }
        assertFalse(vm.cpuSampleAvailable)
        rule.onNodeWithText("0.0%").assertDoesNotExist()
    }

    @Test fun settingsHasOneOwnerPerFeatureAndBasicConfigurationHasNoSecondImporter() {
        render(tab = HxTab.Settings, running = false)
        node("settings-基础代理配置").performTouchInput { click() }
        node("hetu-route-network").assertExists()
        rule.onNodeWithText("代理核心").assertIsDisplayed()
        rule.onNodeWithText("配置选择").assertDoesNotExist()
        rule.onNodeWithContentDescription("导入配置").assertDoesNotExist()
        screenshot("04-root-basic-settings")
        back()
        node("settings-高级代理配置").performTouchInput { click() }
        node("hetu-route-advanced-network").assertExists()
        rule.onNodeWithText("代理 TCP").assertIsDisplayed()
        rule.onNodeWithText("Mihomo DNS 转发").assertIsDisplayed()
        screenshot("05-root-advanced-settings")
        back()
        node("settings-通知设置").performScrollTo().performTouchInput { click() }
        node("hetu-route-notifications").assertExists()
        back()
        node("hetu-route-main").assertExists()
    }

    @Test fun themeModeCardsSelectPersistAndReplaceTheOldChooser() {
        rule.runOnIdle { vm.setDynamic(false); vm.setAppearanceMode("light") }
        render(tab = HxTab.Settings, running = false, followAppearance = true)
        node("settings-语言与主题").performScrollTo().performClick()
        node("hetu-route-theme").assertExists()
        node("theme-mode-light").assertIsSelected()
        node("theme-mode-system").assertIsNotSelected()
        node("theme-mode-dark").assertIsNotSelected()
        rule.onAllNodesWithText("主题模式").assertCountEquals(1)
        screenshot("16-root-theme-modes-light")
        node("theme-mode-dark").performTouchInput { click() }
        node("theme-mode-dark").assertIsSelected()
        assertEquals("dark", vm.prefs.getString("appearance", null))
        assertEquals("dark", vm.appearance)
        screenshot("17-root-theme-modes-dark")
        node("theme-mode-system").performTouchInput { click() }
        assertEquals("system", vm.prefs.getString("appearance", null))
        node("theme-mode-system").assertIsSelected()
    }

    @Test fun largeFontThemeCardsStackAndEveryAccentHasAnIndependentTouchTarget() {
        rule.runOnIdle { vm.setDynamic(false); vm.setAppearanceMode("light") }
        render(tab = HxTab.Settings, running = false, fontScale = 1.8f, followAppearance = true)
        node("settings-语言与主题").performScrollTo().performClick()
        listOf("system", "light", "dark").forEach { mode ->
            val item = node("theme-mode-$mode").performScrollTo().assertIsDisplayed()
            val bounds = item.getUnclippedBoundsInRoot()
            assertTrue("large text mode card must use the whole row", bounds.right - bounds.left >= 300.dp)
        }
        screenshot("18-root-theme-modes-large-font")
        val colors = listOf("#2A62E8", "#12806F", "#0EA5E9", "#4F46E5", "#8B5CF6", "#EC4899", "#EF4444", "#F59E0B")
        colors.forEach { hex ->
            val item = node("theme-color-$hex").performScrollTo().assertIsDisplayed()
            val bounds = item.getUnclippedBoundsInRoot()
            assertTrue(bounds.right - bounds.left >= 48.dp)
            assertTrue(bounds.bottom - bounds.top >= 48.dp)
            item.performTouchInput { click() }.assertIsSelected()
            assertEquals(hex, vm.accentChoice)
        }
        val first = node("theme-color-#2A62E8").getUnclippedBoundsInRoot()
        val next = node("theme-color-#12806F").getUnclippedBoundsInRoot()
        assertTrue("accent targets must not overlap", first.right <= next.left)
        screenshot("19-root-theme-accent-targets-large-font")
    }

    @Test fun groupedToolsOpenTheCanonicalConfigLibraryAndBackReturnsToTools() {
        render(tab = HxTab.Tools, running = false)
        node("tools-文件与脚本").assertIsDisplayed()
        screenshot("06-root-tools")
        node("tool-配置管理").performScrollTo().performClick()
        node("hetu-route-configs").assertExists()
        rule.onNodeWithText("配置与订阅").assertExists()
        back()
        node("tool-配置管理").assertExists()
        assertEquals(HxTab.Tools, vm.tab)
    }

    @Test fun panelNavigationUsesActualRulesSetsAndConnectionsAndSupportsDarkMode() {
        render(section = "rules", dark = true)
        node("panel-rules-summary").assertIsDisplayed()
        node("panel-rule-0").assertExists()
        screenshot("07-root-rules-dark")
        node("panel-tab-sets").performClick()
        node("panel-rule-sets-summary").assertIsDisplayed()
        screenshot("08-root-rule-sets-dark")
        node("panel-tab-conn").performClick()
        node("panel-connections-summary").assertIsDisplayed()
        screenshot("09-root-connections-dark")
    }

    @Test fun disabledMotionLeavesFirstFrameContentVisibleAndNavigationFunctional() {
        render(tab = HxTab.Settings, running = false)
        node("settings-备份与恢复").performTouchInput { click() }
        node("hetu-route-backup").assertIsDisplayed()
        rule.onNodeWithText("创建备份").assertIsDisplayed()
        screenshot("10-root-backup-reduced-motion")
        back()
        node("settings-默认面板").performScrollTo().performTouchInput { click() }
        node("hetu-route-panel-preferences").assertExists()
        rule.onNodeWithText("默认面板页面").performClick()
        rule.onAllNodesWithText("概览", useUnmergedTree = true).assertCountEquals(2)
        rule.onNodeWithText("日志", useUnmergedTree = true).assertExists()
    }
}
