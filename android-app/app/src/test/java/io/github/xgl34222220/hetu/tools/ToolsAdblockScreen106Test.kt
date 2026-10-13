package io.github.xgl34222220.hetu.tools

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import io.github.xgl34222220.hetu.HetuAppTheme
import io.github.xgl34222220.hetu.ui.LocalHetuLanguage
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * UI evidence for 广告过滤 renders the page users actually reach: tools/ToolsAdblockScreen (工具 ›
 * 广告过滤 → ToolsFeatureRoute AdblockHost), not the legacy app/AdblockScreen kept only for frozen
 * tests. Offline fixture states; PNGs go to build/outputs/adblock-tools (or -Dhetu.qa.output).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "zh-rCN-w393dp-h852dp-mdpi", application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ToolsAdblockScreen106Test {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val ready = ToolsFeatureSamples.adblock.copy(modeKnown = true, ruleMode = true, modeLabel = "规则", coreLabel = "Mihomo")

    private fun render(state: ToolsAdblockState, language: String = "zh-CN", scrolled: Int = 0) {
        rule.setContent {
            HetuAppTheme(appearance = "light", dynamic = false) {
                CompositionLocalProvider(LocalHetuLanguage provides language) {
                    Box(Modifier.fillMaxSize()) {
                        ToolsAdblockScreen(
                            state = state, onBack = {}, onHelp = {}, onRefresh = {}, onEnabledChange = {}, onSwitchToRuleMode = {},
                            onPickRecent = {}, onLevelChange = {}, onUpdate = {}, onSourceChange = { _, _ -> }, onAddDomain = {},
                            onRemoveDomain = { _, _ -> }, onStandaloneDnsChange = {}, onCnameChange = {}, onRetry = {},
                            scroll = rememberScrollState(initial = scrolled), onProbe = {},
                        )
                    }
                }
            }
        }
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(1_000)
        rule.waitForIdle()
    }

    private fun shows(text: String) =
        assertTrue("Missing 「$text」", rule.onAllNodesWithText(text, substring = true, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())

    private fun absent(text: String) =
        assertTrue("Unexpected 「$text」", rule.onAllNodesWithText(text, substring = true, useUnmergedTree = true).fetchSemanticsNodes().isEmpty())

    private fun capture(id: String, state: ToolsAdblockState) {
        rule.runOnIdle {
            val decor = rule.activity.window.decorView
            assertTrue("Window must be laid out", decor.width > 0 && decor.height > 0)
            val bitmap = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
            decor.draw(Canvas(bitmap))
            val folder = File(System.getProperty("hetu.qa.output", "build/outputs/adblock-tools"))
            folder.mkdirs()
            val png = File(folder, "$id.png")
            png.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            bitmap.recycle()
            assertTrue(png.length() > 0)
            File(folder, "$id.json").writeText(JSONObject()
                .put("id", id).put("screen", "tools/ToolsAdblockScreen").put("status", state.status.name)
                .put("coreManaged", state.coreManaged).put("testOnlySyntheticData", true)
                .put("capturedAtUtc", java.time.Instant.now().toString())
                .put("sampleText", JSONArray(listOf(state.coreLabel, state.modeLabel))).toString(2))
        }
    }

    @Test fun protectingWithControllerCounterShowsOneConsistentState() {
        val state = ready.copy(hits = 1_204, missCount = 38, hitsFromController = true)
        render(state)
        shows("保护中")
        shows("1,204 次")
        shows("已检查 1,242 个连接")
        // The legacy page could show 保护中 next to failed checks; the user-facing page cannot.
        absent("运行链未确认")
        absent("核心未应答")
        capture("106-adblock-tools-protecting", state)
    }

    @Test fun globalModeWarnsAndOffersRuleMode() {
        val state = ready.copy(ruleMode = false, modeLabel = "全局")
        render(state)
        shows("当前为全局模式")
        absent("保护中")
        capture("106-adblock-tools-global-warning", state)
    }

    @Test fun coreWithoutLiveVerificationSaysItIsApplied() {
        val state = ready.copy(coreLiveVerify = false, coreLabel = "sing-box")
        render(state)
        shows("已随核心启动")
        shows("广告域名在 sing-box 中按规则拦截")
        capture("106-adblock-tools-core-managed", state)
    }

    @Test fun stoppedProxyWaitsInsteadOfClaimingProtection() {
        val state = ready.copy(proxyRunning = false)
        render(state)
        assertEquals(ToolsAdStatus.Waiting, state.status)
        shows("启动代理后自动验证运行链")
        absent("保护中")
        capture("106-adblock-tools-waiting", state)
    }

    @Test fun englishUsesTheTemplatesInsteadOfMixedConcatenation() {
        val state = ready.copy(ruleMode = false, modeLabel = "全局")
        render(state, language = "en")
        shows("Current mode: Global")
        absent("当前为")
        capture("106-adblock-tools-global-warning-en", state)
    }
}
