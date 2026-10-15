package io.github.xgl34222220.hetu

import android.app.Application
import android.app.job.JobScheduler
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsOwner
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import io.github.xgl34222220.hetu.tools.ToolsCatalog
import io.github.xgl34222220.hetu.tools.ToolsEntry
import io.github.xgl34222220.hetu.tools.ToolsRootState
import io.github.xgl34222220.hetu.ui.translateHetuText
import java.io.File
import java.time.Duration
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 2026-10-15 3.3.3 polish (work/audit5-2026-10-15.md): reduced-motion gates (R1), network test
 * feedback (N2/N3), tools groups (U14), ABI fallback (G13), background subscription alerts (G7)
 * and the screenshot coverage of pages that had none (G11). The captures are layout evidence only
 * (test-only data, no Root, no controller, no network).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "zh-rCN-w393dp-h852dp-mdpi", application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class Audit5Polish108Test {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val prefs get() = app.getSharedPreferences("hetu", Context.MODE_PRIVATE)
    private fun source(path: String) = File("src/main/java/io/github/xgl34222220/hetu/$path").readText()
    private var vm: HetuViewModel? = null
    private val output = File("build/outputs/concept-remaining-coverage/audit5-coverage")

    @Before fun clean() {
        prefs.edit().clear().putString("appearance", "light").putBoolean("hetuDynamicColor", false)
            .putBoolean("enableBlur", false).putBoolean("predictiveBackAnimation", false).commit()
    }

    @After fun finish() {
        runCatching { rule.runOnUiThread { rule.activity.setContent {} } }
        runCatching { frame() }
        vm?.viewModelScope?.cancel()
        UpdateJobService.scheduleSubscriptionAlerts(app, false)
    }

    /* ---------------- R1 / N2 / N3 / L1 / E1 / T2 (source shape) ---------------- */

    @Test fun motionOffMakesJumpsAndTheSplashInstant() {
        val page = source("app/HxComponents.kt")
        assertTrue(page.contains("if (pageMotion) listState.animateScrollToItem(0) else listState.scrollToItem(0)"))
        assertFalse("every HxPage jump reads the motion switch", page.contains("{ listState.animateScrollToItem(0) }"))
        val tabs = source("app/HxTabbedPage.kt")
        assertTrue(tabs.contains("if (tabMotion) it.animateScrollToItem(0) else it.scrollToItem(0)"))
        assertTrue(tabs.contains("if (chipMotion) scroll.animateScrollTo("))
        assertTrue(source("tools/ToolsYamlEditor.kt").contains("if (jumpMotion) vertical.animateScrollTo(target) else vertical.scrollTo(target)"))
        assertTrue(source("app/HetuActivity.kt").contains("if (artMotion) alpha.animateTo(0f"))
    }

    @Test fun networkTestSettlesColoursAndConfirmsAFinishedRun() {
        val screen = source("app/NetworkTestScreen.kt")
        assertTrue(screen.contains("label = \"net-site-status\""))
        assertTrue(screen.contains("if (finished) haptics.perform(io.github.xgl34222220.hetu.ui.HetuHaptic.Confirm)"))
        assertTrue(screen.contains("else if (runFailed) haptics.perform(io.github.xgl34222220.hetu.ui.HetuHaptic.Reject)"))
        assertTrue(screen.contains("style = HomeType.noteStrong, maxLines = 1, overflow = TextOverflow.Ellipsis)"))
    }

    @Test fun logLinesAnimateAndTheEditorShowsPlaceholders() {
        assertTrue(source("ProxyLogViewerActivity.kt").contains("Box(hxAnimateItem()) { HxLogLine(line) }"))
        val editor = source("tools/ToolsEditorScreen.kt")
        assertTrue(editor.contains("ToolsLoad.Loading -> Box(Modifier.fillMaxSize()) { io.github.xgl34222220.hetu.HxSkeletonLines("))
        assertFalse(editor.contains("HomeSpinner(size = 22.dp"))
    }

    @Test fun plainCopyToastFollowsTheAppLanguage() {
        prefs.edit().putString("appLanguage", "en").commit()
        assertEquals("Copied", hxPlainText(app, "已复制"))
        prefs.edit().putString("appLanguage", "zh-CN").commit()
        assertEquals("已复制", hxPlainText(app, "已复制"))
        listOf("常用工具", "分流与网络", "配置与规则", "核心与维护", "已复制").forEach { key ->
            assertTrue(key, translateHetuText(key, "en") != key)
            assertTrue(key, translateHetuText(key, "ru") != key)
        }
    }

    /* ---------------- U14 ---------------- */

    @Test fun toolsAreFourTitledCardsInTheConceptOrder() {
        val grouped = ToolsCatalog.grouped
        assertEquals(grouped, ToolsRootState().groups)
        assertEquals(listOf("常用工具", "分流与网络", "配置与规则", "核心与维护"), grouped.map { it.title })
        assertEquals(ToolsCatalog.concept.flatMap { it.entries }, grouped.flatMap { it.entries })
        assertEquals(ToolsEntry.entries.toSet(), grouped.flatMap { it.entries }.toSet())
        val entryTexts = ToolsEntry.entries.flatMap { listOf(it.title, it.summary, it.brief) }.toSet()
        grouped.forEach { assertFalse("title ${it.title} repeats an entry", it.title in entryTexts) }
    }

    /* ---------------- G13 ---------------- */

    @Test fun abiFallbackSkipsPayloadsThisPackageDoesNotCarry() {
        val armOnly = EbpfMatcher.AssetProbe { path -> !path.contains("x86_64") }
        assertEquals("mihomo-root/arm64-v8a/mihomo", EbpfMatcher.firstAsset(arrayOf("x86_64", "arm64-v8a"), "mihomo-root/", "/mihomo", armOnly))
        assertEquals("mihomo-root/x86_64/mihomo", EbpfMatcher.firstAsset(arrayOf("x86_64", "arm64-v8a"), "mihomo-root/", "/mihomo", null))
        assertNull(EbpfMatcher.firstAsset(arrayOf("x86_64", "x86"), "mihomo-root/", "/mihomo", armOnly))
        assertEquals("hetu-bpf/arm64-v8a/hetu-bpf", EbpfMatcher.loaderAsset(arrayOf("x86_64", "arm64-v8a"), armOnly))
        assertEquals("hetu-bpf/x86_64/hetu-bpf", EbpfMatcher.loaderAsset(arrayOf("x86", "x86_64")))
        val manager = source("RootProxyManager.java")
        assertTrue(manager.contains("EbpfMatcher.loaderAsset(Build.SUPPORTED_ABIS,this::packagedAsset)"))
        assertTrue(manager.contains("EbpfMatcher.firstAsset(Build.SUPPORTED_ABIS,\"mihomo-root/\",\"/mihomo\",this::packagedAsset)"))
        assertTrue(manager.contains("本安装包不含适配当前 CPU 的内核，请下载通用版"))
    }

    /* ---------------- G7 ---------------- */

    @Test fun backgroundSubscriptionCheckFollowsTheSwitch() {
        val scheduler = app.getSystemService(JobScheduler::class.java)
        assertNull(scheduler.getPendingJob(UpdateJobService.SUBSCRIPTION_ALERT_JOB_ID))
        UpdateJobService.scheduleSubscriptionAlerts(app, true)
        val job = scheduler.getPendingJob(UpdateJobService.SUBSCRIPTION_ALERT_JOB_ID)
        assertNotNull(job)
        assertTrue(job!!.isPeriodic)
        assertEquals(TimeUnit.HOURS.toMillis(12), job.intervalMillis)
        assertTrue(job.isPersisted)
        assertTrue("never replaces the daily rule update", UpdateJobService.SUBSCRIPTION_ALERT_JOB_ID != UpdateJobService.JOB_ID)
        UpdateJobService.scheduleSubscriptionAlerts(app, false)
        assertNull(scheduler.getPendingJob(UpdateJobService.SUBSCRIPTION_ALERT_JOB_ID))
        // Off by default: the background pass returns without reading anything.
        HxSubscriptionAlerts.checkInBackground(app)
        val service = source("UpdateJobService.java")
        assertTrue(service.contains("if(parameters.getJobId()==SUBSCRIPTION_ALERT_JOB_ID) return startSubscriptionAlertJob(parameters);"))
        assertTrue(source("app/ToolScreens.kt").contains("UpdateJobService.scheduleSubscriptionAlerts(context, on)"))
        assertTrue(source("app/HetuActivity.kt").contains("HxSubscriptionAlerts.syncSchedule(applicationContext)"))
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertEquals("No permission added", 11, Regex("<uses-permission ").findAll(manifest).count())
    }

    /* ---------------- G11: screenshot coverage ---------------- */

    private fun frame() {
        repeat(4) {
            rule.mainClock.advanceTimeBy(200)
            rule.runOnUiThread { Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100)) }
        }
    }

    private fun nativeTexts(): List<String> {
        val result = mutableListOf<String>()
        fun visitNode(n: SemanticsNode) {
            result += n.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }
            n.children.forEach(::visitNode)
        }
        fun visitView(v: View) {
            v.javaClass.methods.firstOrNull { it.name == "getSemanticsOwner" }?.let { visitNode((it.invoke(v) as SemanticsOwner).unmergedRootSemanticsNode) }
            if (v is ViewGroup) (0 until v.childCount).forEach { visitView(v.getChildAt(it)) }
        }
        WindowInspector.getGlobalWindowViews().forEach(::visitView)
        return result
    }

    private fun capture(root: View, id: String) {
        rule.runOnUiThread {
            assertTrue("$id must be laid out", root.width > 0 && root.height > 0)
            val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            root.draw(Canvas(bitmap))
            output.mkdirs()
            val file = File(output, "$id.png")
            file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            bitmap.recycle()
            assertTrue("$id has bytes", file.length() > 0)
        }
    }

    private fun show(appearance: String, fontScale: Float, content: @Composable () -> Unit) {
        rule.runOnUiThread {
            rule.activity.setContent {
                HetuAppTheme(appearance, false) {
                    val density = LocalDensity.current
                    CompositionLocalProvider(
                        LocalNav provides HxNav(), LocalHxBlur provides false,
                        LocalDensity provides Density(density.density, fontScale),
                    ) { Box(Modifier.fillMaxSize()) { content() } }
                }
            }
        }
        frame()
    }

    @Test fun pagesWithoutEvidenceAreCapturedInLightDarkAndLargeText() {
        rule.mainClock.autoAdvance = false
        val now = System.currentTimeMillis()
        SpeedTestHistory.add(app, SpeedTestHistory.bandwidthRecord("网速测试", 38L, 182.4 to 46_000_000L, 41.7 to 12_000_000L, 46_000_000L, 12_000_000L, null, now))
        prefs.edit().putBoolean("networkMatchEnabled", true).putString("networkMatchLastEnvironment", "Wi-Fi · Test Network").commit()
        val model = HetuViewModel(app).also { vm = it }
        val pages = listOf("speed-history", "override-scripts", "traffic-ranking", "network-match-daemon")
        val variants = listOf(Triple("light", "light", 1f), Triple("dark", "dark", 1f), Triple("light-2x", "light", 2f))
        pages.forEach { name ->
            variants.forEach { (suffix, appearance, scale) ->
                show(appearance, scale) { CoveragePage(name, model) }
                capture(rule.activity.window.decorView, "$name-$suffix")
            }
        }
        show("light", 1f) { Column { SpeedTestHistorySection(0) } }
        var texts = emptyList<String>()
        rule.runOnUiThread { texts = nativeTexts() }
        assertTrue("speed history renders its run: $texts", texts.contains("测速历史"))
    }

    @Test fun advancedSettingsWithEbpfAndWechatSwitchesIsCaptured() {
        rule.mainClock.autoAdvance = false
        show("light", 1f) {}
        listOf("light", "dark").forEach { appearance ->
            prefs.edit().putString("appearance", appearance).commit()
            val controller = Robolectric.buildActivity(ProxyAdvancedSettingsActivity::class.java).setup()
            try {
                frame()
                capture(controller.get().window.decorView, "advanced-settings-ebpf-wechat-$appearance")
            } finally {
                controller.pause().stop().destroy()
                frame()
            }
        }
    }
}

@Composable
private fun CoveragePage(name: String, model: HetuViewModel) {
    when (name) {
        "speed-history" -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) { SpeedTestHistorySection(0) }
        "override-scripts" -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) { OverrideScriptsSection() }
        "traffic-ranking" -> {
            val rank = rememberRankState(model)
            LazyColumn(Modifier.fillMaxSize()) { rankItems(model, rank) }
        }
        else -> HxNetworkMatchScreen(model) {}
    }
}
