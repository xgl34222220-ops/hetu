package io.github.xgl34222220.hetu

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import io.github.xgl34222220.hetu.ui.translateHetuText
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 2026-10-13 function polish (audit B1/B2/B3/B7/B8/B9/B12/B13/B14): idle poll cadence, feedback for
 * bulk updates that cannot run, the eBPF switch's pending notice / mode guard / translatable subtitle,
 * stable log keys, the history time distribution, unsaved-list guards and the network type fallback.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class FuncPolish106Test {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private fun source(path: String) = File("src/main/java/io/github/xgl34222220/hetu/$path").readText()

    @Before fun clean() { app.getSharedPreferences("hetu", 0).edit().clear().commit() }

    @Test fun stoppedIdleProxyIsProbedEveryEightSecondsAndActiveCadenceIsUnchanged() {
        assertEquals(8_000L, HxPollCadence.delayMs(homeOrPanel = true, running = false, operationActive = false, runningHint = false))
        assertEquals(8_000L, HxPollCadence.delayMs(homeOrPanel = false, running = false, operationActive = false, runningHint = false))
        assertEquals(2_000L, HxPollCadence.delayMs(homeOrPanel = true, running = true, operationActive = false, runningHint = false))
        assertEquals(4_000L, HxPollCadence.delayMs(homeOrPanel = false, running = true, operationActive = false, runningHint = false))
        // An operation or a start reported by tile / boot / network match keeps the fast cadence.
        assertEquals(2_000L, HxPollCadence.delayMs(homeOrPanel = true, running = false, operationActive = true, runningHint = false))
        assertEquals(2_000L, HxPollCadence.delayMs(homeOrPanel = true, running = false, operationActive = false, runningHint = true))
        assertTrue(HxPollCadence.WakeStepMs <= 1_000L)
        val vm = source("app/HetuViewModel.kt")
        assertTrue(vm.contains("HxPollCadence.delayMs(homeOrPanel, state.running, operation != null, ProxyStatusBridge.rootProxyRunning(app))"))
    }

    @Test fun bulkUpdatesThatCannotRunExplainWhyInsteadOfReturningSilently() = runBlocking {
        val vm = HetuViewModel(app)
        val seen = mutableListOf<String>()
        val collector = launch(Dispatchers.Unconfined) { vm.messages.collect { seen += it } }
        try {
            assertFalse(vm.state.running)
            vm.updateAllProviders()
            vm.updateAllRuleSets()
        } finally { collector.cancel() }
        assertEquals(listOf("订阅更新需要代理运行中", "规则集更新需要代理运行中"), seen.filter { it.endsWith("需要代理运行中") })
        assertFalse(vm.providersUpdatingAll)
        assertFalse(vm.ruleSetsUpdatingAll)
    }

    @Test fun ebpfSwitchChangeIsPendingUntilTheSessionCarriesIt() {
        val on = "core=mihomo|mode=tproxy|uids=1|ebpf=1"
        val off = "core=mihomo|mode=tproxy|uids=1"
        assertTrue(EbpfMatcher.switchPending(true, true, off))
        assertTrue(EbpfMatcher.switchPending(true, false, on))
        assertFalse(EbpfMatcher.switchPending(true, true, on))
        assertFalse(EbpfMatcher.switchPending(true, false, off))
        assertFalse("Nothing is pending without a running session", EbpfMatcher.switchPending(false, true, off))
        assertFalse(EbpfMatcher.switchPending(true, true, ""))
        assertTrue(source("app/HetuViewModel.kt").contains("EbpfMatcher.switchPending(state.running"))
    }

    @Test fun ebpfSwitchIsGuardedByRunModeAndItsSubtitleIsTranslatable() {
        listOf("tproxy", "redirect", "enhance").forEach { assertTrue(it, EbpfMatcher.modeSupported(it)) }
        listOf("tun", "ebpf", "mixed", "").forEach { assertFalse(it, EbpfMatcher.modeSupported(it)) }
        assertTrue(EbpfMatcher.describe(false, false, "", "", "tun").single().startsWith("当前运行模式不支持"))
        assertTrue(EbpfMatcher.describe(false, true, "active", "", "tproxy").single().contains("重启代理后停用"))
        assertEquals(listOf("未生效，已回退到 iptables 规则：", "SELinux 拒绝了 bpf 调用"),
            EbpfMatcher.describe(true, true, "fallback", "selinux-denied", "tproxy").toList())
        assertEquals(EbpfMatcher.describe(true, true, "active", "", "redirect").single(), EbpfMatcher.describe(true, true, "active", ""))
        // Every part the UI shows has an entry in all three translations.
        val parts = listOf(
            EbpfMatcher.describe(false, false, "", "", "tun"), EbpfMatcher.describe(false, true, "active", "", "tproxy"),
            EbpfMatcher.describe(true, true, "fallback", "selinux-denied", "tproxy"), EbpfMatcher.describe(true, true, "active", "pins-missing", "tproxy"),
            EbpfMatcher.describe(true, true, "active", "", "tproxy"), EbpfMatcher.describe(true, true, "off", "", "tproxy"),
            EbpfMatcher.describe(false, false, "", "", "tproxy"),
        ).flatMap { it.toList() } + listOf("bpffs-missing", "xt-bpf-unsupported", "mode-tun", "prog-load-permission", "map-create-e2big").map(EbpfMatcher::reasonText)
        parts.forEach { part ->
            listOf("en", "zh-TW", "ru").forEach { lang -> assertTrue("$lang: $part", translateHetuText(part, lang) != part) }
        }
        val page = source("ProxyAdvancedSettingsActivity.kt")
        assertTrue(page.contains("enabled = ebpf || modeSupported"))
        assertTrue(page.contains("putBool(EbpfMatcher.PREF_ENABLED, it)"))
    }

    @Test fun logLineKeysStayStableWhenLinesAreAppended() {
        val first = hxKeyedLogLines(listOf("a", "b", "a"))
        assertEquals(listOf("l:1:a", "l:1:b", "l:2:a"), first.map { it.first })
        assertEquals(first.size, first.map { it.first }.toSet().size)
        val appended = hxKeyedLogLines(listOf("a", "b", "a", "c"))
        assertEquals(first.map { it.first }, appended.take(3).map { it.first })
        val viewer = source("ProxyLogViewerActivity.kt")
        // Live refresh only advances the content tick; the Root file listing follows manual refreshes.
        assertTrue(viewer.contains("if (!loading) liveTick++"))
        assertTrue(viewer.contains("ActivityResultContracts.CreateDocument(\"text/plain\")"))
        assertTrue(viewer.contains("\"tail -n \" + lines.coerceIn(1, LOG_TAIL_MAX)"))
    }

    @Test fun historyDistributionHasContinuousZeroFilledSlots() {
        val hour = 3_600L
        val slots = ProxyApiHistoryStore.distributionSlots(mapOf(7_200L to (5L to 9L)), fromSec = 3_700L, toSec = 10_900L, bucket = hour)
        assertEquals(listOf(3_600_000L, 7_200_000L, 10_800_000L), slots.map { it.first })
        assertEquals(listOf(0L, 5L, 0L), slots.map { it.second })
        assertEquals(listOf(0L, 9L, 0L), slots.map { it.third })
        assertEquals(400, ProxyApiHistoryStore.distributionSlots(emptyMap(), 0L, 10_000_000L, 60L).size)
        assertTrue(ProxyApiHistoryStore.distributionSlots(emptyMap(), 10L, 5L, 60L).isEmpty())
        assertEquals(300L, historyBucketSeconds(3_600_000L))
        assertEquals(3_600L, historyBucketSeconds(86_400_000L))
        assertEquals(21_600L, historyBucketSeconds(7 * 86_400_000L))
        assertEquals(86_400L, historyBucketSeconds(30 * 86_400_000L))
    }

    @Test fun networkTypeFallbackAndUnsavedListGuards() {
        assertEquals(HxNetworkType.Wifi, HxNetworkType.fromLabel("Wi-Fi · Test Network"))
        assertEquals(HxNetworkType.Cellular, HxNetworkType.fromLabel("移动数据 · 卡1"))
        assertEquals(HxNetworkType.Other, HxNetworkType.fromLabel("监听失败：X"))
        assertEquals("无网络", hxNetworkTypeLabel(HxNetworkType.None))
        val type = hxNetworkType(app)
        assertTrue(type in HxNetworkType.values())
        val match = source("ProxyNetworkAutomationActivity.kt")
        assertTrue(match.contains("BackHandler(enabled = dirty) { confirmLeave = true }"))
        val shared = source("app/ToolScreens.kt")
        assertTrue(shared.contains("BackHandler(enabled = macDirty) { confirmLeave = true }"))
    }

    @Test fun homeSubscriptionLongPressAndTopBarStyleWiring() {
        assertTrue(source("home/HomeScreen.kt").contains("onLongClick = onUpdateAll, longClickLabel = \"更新全部订阅\""))
        assertTrue(source("app/NewUiIntegration.kt").contains("onUpdateProviders = vm::updateAllProviders"))
        val components = source("app/HxComponents.kt")
        assertTrue(components.contains("val progressive = rememberHxTopBarProgressive(context)"))
        assertFalse(components.contains("context.getSharedPreferences(\"hetu\", 0).getString(\"topBarBlurStyle\""))
    }
}
