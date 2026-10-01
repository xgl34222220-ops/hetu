package io.github.xgl34222220.hetu

import android.app.Application
import android.os.Looper
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.LooperMode
import org.robolectric.shadow.api.Shadow
import java.io.IOException
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.startCoroutineUninterceptedOrReturn

/** Real VM + real on-disk CAS saves; only controller/repository/inspector IO is isolated. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, shadows = [
    ConfigApplyControllerShadow::class, ConfigApplyRepositoryShadow::class,
    ConfigApplyInspectorShadow::class,
])
@LooperMode(LooperMode.Mode.PAUSED)
class ConfigApplyQueueTest {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val prefs get() = app.getSharedPreferences("hetu", 0)
    private lateinit var library: ProxyConfigLibrary
    private lateinit var entry: ProxyConfigLibrary.Entry
    private val stores = mutableListOf<ViewModelStore>()
    private val messages = CopyOnWriteArrayList<String>()

    @Before fun prepare() {
        ConfigApplyIo.reset()
        prefs.edit().clear().putBoolean("proxyUiLastRunning", true)
            .putBoolean("proxyApiHistoryEnabled", false).commit()
        library = ProxyConfigLibrary(app)
        entry = library.importConfig(ProxyRuntimeProfile.Core.MIHOMO, "apply-queue.yaml", yaml("initial").byteInputStream())
        ConfigApplyIo.source = { library.read(requireNotNull(library.selected(ProxyRuntimeProfile.Core.MIHOMO))) }
        ConfigApplyIo.openSource = library::beginSourceApplication
        ConfigApplyIo.applied = yaml("initial")
    }

    @After fun clean() {
        stores.forEach { it.clear() }
        ConfigApplyIo.gates.forEach { it.release.countDown() }
        ConfigApplyIo.nextStateGate?.release?.countDown()
        pumpUntil { ConfigApplyIo.active == 0 }
        assertTrue("Unexpected IO: ${ConfigApplyIo.unexpected}", ConfigApplyIo.unexpected.isEmpty())
    }

    private fun yaml(version: String) = "# $version\nmixed-port: 7890\nrules: [MATCH,DIRECT]\n"

    private fun vm(): HetuViewModel {
        val vm = HetuViewModel(app)
        assertTrue(Shadow.extract<Any>(vm.controller) is ConfigApplyControllerShadow)
        assertTrue(Shadow.extract<Any>(vm.repo) is ConfigApplyRepositoryShadow)
        assertTrue(Shadow.extract<Any>(vm.inspector) is ConfigApplyInspectorShadow)
        stores += ViewModelStore().also { it.put("vm", vm) }
        vm.viewModelScope.launch { vm.messages.collect { messages += it } }
        shadowOf(Looper.getMainLooper()).idle()
        return vm
    }

    private fun save(vm: HetuViewModel, version: String) {
        val snapshot = ConfigEditSnapshot(entry.core.id, entry.name, library.read(entry))
        library.writeIfUnchanged(entry, snapshot, yaml(version))
        vm.applyConfigChange("配置已保存")
    }

    private fun saveBound(vm: HetuViewModel, version: String): ProxyConfigLibrary.SourceVersion {
        val source = yaml(version)
        library.writeIfUnchanged(entry, ConfigEditSnapshot(entry.core.id, entry.name, library.read(entry)), source)
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(source.toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        val receipt = ProxyConfigLibrary.SourceVersion.restored(entry.core.id, entry.name, digest)
        vm.applyConfigChange("配置已保存", receipt)
        return receipt
    }

    private fun pumpUntil(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
        do {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(5)
        } while (System.nanoTime() < deadline)
        fail("Timed out. Calls: ${ConfigApplyIo.calls}; active: ${ConfigApplyIo.active}")
    }

    private fun started(gate: ConfigApplyGate) = pumpUntil { gate.entered.count == 0L }
    private fun finished(vm: HetuViewModel) = pumpUntil { vm.operation == null && ConfigApplyIo.active == 0 }

    @Test fun newerSaveAfterReloadReadsSourceIsAppliedAfterItsAcknowledgement() {
        val vm = vm()
        val a = ConfigApplyIo.expect("reload")
        val b = ConfigApplyIo.expect("reload")
        save(vm, "A"); started(a)
        assertEquals(yaml("initial"), ConfigApplyIo.applied)
        save(vm, "B")
        assertEquals(yaml("B"), library.read(entry))
        assertEquals(listOf("reload:A"), ConfigApplyIo.calls)
        a.release.countDown(); started(b)
        assertEquals(yaml("A"), ConfigApplyIo.applied)
        assertFalse(messages.contains("运行配置已热重载"))
        b.release.countDown(); finished(vm)
        assertEquals(listOf("reload:A", "reload:B"), ConfigApplyIo.calls)
        assertEquals(yaml("B"), ConfigApplyIo.applied)
        assertTrue(messages.contains("运行配置已热重载"))
    }
    @Test fun switchingTheSelectedFileDuringReloadAppliesTheNewSelectedSource() {
        val vm = vm()
        val a = ConfigApplyIo.expect("reload")
        val b = ConfigApplyIo.expect("reload")
        save(vm, "A"); started(a)
        entry = library.importConfig(ProxyRuntimeProfile.Core.MIHOMO, "selected-B.yaml", yaml("B").byteInputStream())
        vm.applyConfigChange("配置已导入")
        assertEquals(entry.name, library.selected(ProxyRuntimeProfile.Core.MIHOMO)?.name)
        a.release.countDown(); started(b)
        assertEquals(yaml("A"), ConfigApplyIo.applied)
        b.release.countDown(); finished(vm)
        assertEquals(listOf("reload:A", "reload:B"), ConfigApplyIo.calls)
        assertEquals(yaml("B"), ConfigApplyIo.applied)
    }

    @Test fun aTransientStoppedSnapshotDuringReloadCannotDiscardTheNextSave() {
        val vm = vm()
        val a = ConfigApplyIo.expect("reload")
        val b = ConfigApplyIo.expect("reload")
        save(vm, "A"); started(a)
        ConfigApplyIo.running = false
        runBlocking { vm.refreshNow() }
        assertFalse(vm.state.running)
        ConfigApplyIo.running = true
        save(vm, "B")
        a.release.countDown(); started(b)
        b.release.countDown(); finished(vm)
        assertEquals(listOf("reload:A", "reload:B"), ConfigApplyIo.calls)
        assertEquals(yaml("B"), ConfigApplyIo.applied)
    }

    @Test fun rapidSavesCoalesceToTheLatestSourceWithoutCancellingTheFirstCall() {
        val vm = vm()
        val a = ConfigApplyIo.expect("reload")
        val c = ConfigApplyIo.expect("reload")
        save(vm, "A"); started(a)
        save(vm, "B"); save(vm, "C")
        assertEquals(yaml("initial"), ConfigApplyIo.applied)
        assertEquals(1, ConfigApplyIo.calls.size)
        a.release.countDown(); started(c)
        assertEquals(listOf("reload:A", "reload:C"), ConfigApplyIo.calls)
        assertEquals(yaml("A"), ConfigApplyIo.applied)
        c.release.countDown(); finished(vm)
        assertEquals(yaml("C"), ConfigApplyIo.applied)
        assertFalse(prefs.contains("proxyUiConfigApplyPending"))
        assertFalse(vm.state.message.contains("尚未确认应用"))
    }

    @Test fun failedRequestRemainsPendingWithoutAutomaticRetryAndUsesExistingReloadForRetry() {
        val vm = vm()
        val a = ConfigApplyIo.expect("reload", "测试核心拒绝配置")
        save(vm, "A"); started(a); a.release.countDown(); finished(vm)
        assertEquals(listOf("reload:A"), ConfigApplyIo.calls)
        assertEquals(yaml("initial"), ConfigApplyIo.applied)
        assertTrue(prefs.contains("proxyUiConfigApplyPending"))
        assertTrue(vm.state.message.contains("应用失败"))
        assertFalse(messages.contains("运行配置已热重载"))
        val retry = ConfigApplyIo.expect("reload")
        vm.reload(); started(retry); retry.release.countDown(); finished(vm)
        assertEquals(listOf("reload:A", "reload:A"), ConfigApplyIo.calls)
        assertEquals(yaml("A"), ConfigApplyIo.applied)
        assertFalse(prefs.contains("proxyUiConfigApplyPending"))
    }

    @Test fun newerSaveStillRunsOnceAfterThePreviousRequestFails() {
        val vm = vm()
        val a = ConfigApplyIo.expect("reload", "测试失败")
        val b = ConfigApplyIo.expect("reload")
        save(vm, "A"); started(a); save(vm, "B")
        a.release.countDown(); started(b)
        assertEquals(yaml("initial"), ConfigApplyIo.applied)
        assertFalse(messages.contains("运行配置已热重载"))
        b.release.countDown(); finished(vm)
        assertEquals(listOf("reload:A", "reload:B"), ConfigApplyIo.calls)
        assertEquals(yaml("B"), ConfigApplyIo.applied)
        assertFalse(prefs.contains("proxyUiConfigApplyPending"))
    }

    @Test fun saveDuringStartQueuesOnlyTheNewerSource() {
        ConfigApplyIo.running = false
        prefs.edit().putBoolean("proxyUiLastRunning", false).commit()
        val vm = vm()
        save(vm, "A")
        assertTrue(ConfigApplyIo.calls.isEmpty())
        val start = ConfigApplyIo.expect("start")
        val b = ConfigApplyIo.expect("reload")
        vm.toggle(); started(start); save(vm, "B")
        start.release.countDown(); started(b)
        assertEquals(yaml("A"), ConfigApplyIo.applied)
        b.release.countDown(); finished(vm)
        assertEquals(listOf("start:A", "reload:B"), ConfigApplyIo.calls)
        assertEquals(yaml("B"), ConfigApplyIo.applied)
    }

    @Test fun directStartReadBackWarningSurvivesFailedFinalRefreshWithoutPendingConfig() {
        ConfigApplyIo.running = false
        prefs.edit().putBoolean("proxyUiLastRunning", false).commit()
        val vm = vm()
        ConfigApplyIo.stateMessage = "核心已运行，但网络健康检查告警"
        val start = ConfigApplyIo.expect("start")
        vm.toggle(); started(start)
        ConfigApplyIo.nextStateFailure = IOException("收尾状态读取失败")
        start.release.countDown(); finished(vm)
        assertEquals(ConfigApplyIo.stateMessage, vm.state.message)
        assertTrue(vm.state.running)
        assertFalse(prefs.contains("proxyUiConfigApplyPending"))
        assertEquals(listOf("start:initial"), ConfigApplyIo.calls)
    }

    @Test fun settledStartRetainsWarningAndPendingRequestUntilReloadActuallyAcknowledgesIt() {
        ConfigApplyIo.running = false
        prefs.edit().putBoolean("proxyUiLastRunning", false).commit()
        val vm = vm()
        save(vm, "A")
        ConfigApplyIo.stateMessage = "启动回读已运行，但网络尚未就绪"
        val start = ConfigApplyIo.expect("start", "测试启动响应超时")
        val reload = ConfigApplyIo.expect("reload")
        vm.toggle(); started(start)
        ConfigApplyIo.running = true
        ConfigApplyIo.nextStateFailure = IOException("收尾状态读取失败")
        start.release.countDown()
        pumpUntil { vm.operationText == "确认最终运行状态…" }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(350))
        started(reload)
        assertTrue(vm.state.message.contains(ConfigApplyIo.stateMessage))
        assertTrue(prefs.contains("proxyUiConfigApplyPending"))
        assertEquals(yaml("initial"), ConfigApplyIo.applied)
        assertFalse(messages.contains("运行配置已热重载"))
        reload.release.countDown(); finished(vm)
        assertEquals(yaml("A"), ConfigApplyIo.applied)
        assertFalse(prefs.contains("proxyUiConfigApplyPending"))
        assertEquals(ConfigApplyIo.stateMessage, vm.state.message)
    }

    @Test fun successfulStartAcknowledgesItsUnchangedRequestWithoutExtraReload() {
        ConfigApplyIo.running = false
        prefs.edit().putBoolean("proxyUiLastRunning", false).commit()
        val vm = vm()
        save(vm, "A")
        val start = ConfigApplyIo.expect("start")
        vm.toggle(); started(start); start.release.countDown(); finished(vm)
        assertEquals(listOf("start:A"), ConfigApplyIo.calls)
        assertEquals(yaml("A"), ConfigApplyIo.applied)
        assertFalse(prefs.contains("proxyUiConfigApplyPending"))
    }

    @Test fun startThatFindsAnAlreadyRunningCoreCannotAcknowledgeTheNewConfig() {
        prefs.edit().putBoolean("proxyUiLastRunning", false).commit()
        val vm = vm()
        save(vm, "A")
        val start = ConfigApplyIo.expect("start", alreadyRunning = true)
        val reload = ConfigApplyIo.expect("reload")
        vm.toggle(); started(start); start.release.countDown(); started(reload)
        assertEquals(yaml("initial"), ConfigApplyIo.applied)
        assertTrue(prefs.contains("proxyUiConfigApplyPending"))
        reload.release.countDown(); finished(vm)
        assertEquals(listOf("start:A", "reload:A"), ConfigApplyIo.calls)
        assertEquals(yaml("A"), ConfigApplyIo.applied)
    }

    @Test fun cancelledStartCannotAcknowledgeOrRetryAgainstAStoppedCore() {
        ConfigApplyIo.running = false
        prefs.edit().putBoolean("proxyUiLastRunning", false).commit()
        val vm = vm()
        save(vm, "A")
        val start = ConfigApplyIo.expect("start", cancelled = true)
        vm.toggle(); started(start); start.release.countDown(); finished(vm)
        assertFalse(ConfigApplyIo.running)
        assertEquals(listOf("start:A"), ConfigApplyIo.calls)
        assertEquals(yaml("initial"), ConfigApplyIo.applied)
        assertTrue(prefs.contains("proxyUiConfigApplyPending"))
        assertFalse(messages.contains("代理已启动"))
    }

    @Test fun saveDuringRestartWaitsAndAppliesTheLatestSource() {
        val vm = vm()
        val restart = ConfigApplyIo.expect("restart")
        val c = ConfigApplyIo.expect("reload")
        vm.restart(); started(restart); save(vm, "B"); save(vm, "C")
        restart.release.countDown(); started(c)
        assertEquals(yaml("initial"), ConfigApplyIo.applied)
        c.release.countDown(); finished(vm)
        assertEquals(listOf("restart:initial", "reload:C"), ConfigApplyIo.calls)
        assertEquals(yaml("C"), ConfigApplyIo.applied)
    }

    @Test fun successfulRestartAcknowledgesAnEarlierFailedSaveWithoutExtraReload() {
        val vm = vm()
        val failed = ConfigApplyIo.expect("reload", "测试失败")
        save(vm, "A"); started(failed); failed.release.countDown(); finished(vm)
        val restart = ConfigApplyIo.expect("restart")
        vm.restart(); started(restart); restart.release.countDown(); finished(vm)
        assertEquals(listOf("reload:A", "restart:A"), ConfigApplyIo.calls)
        assertEquals(yaml("A"), ConfigApplyIo.applied)
        assertFalse(prefs.contains("proxyUiConfigApplyPending"))
    }

    @Test fun savingWhileStoppingCannotReloadOrRestartTheCore() {
        val vm = vm()
        val stop = ConfigApplyIo.expect("stop")
        vm.toggle(); started(stop); save(vm, "A"); vm.reload()
        stop.release.countDown(); finished(vm)
        assertFalse(ConfigApplyIo.running)
        assertEquals(listOf("stop:initial"), ConfigApplyIo.calls)
        assertTrue(prefs.contains("proxyUiConfigApplyPending"))
        assertTrue(vm.state.message.contains("下次启动"))
        val start = ConfigApplyIo.expect("start")
        vm.toggle(); started(start); start.release.countDown(); finished(vm)
        assertEquals(listOf("stop:initial", "start:A"), ConfigApplyIo.calls)
        assertEquals(yaml("A"), ConfigApplyIo.applied)
        assertFalse(prefs.contains("proxyUiConfigApplyPending"))
    }

    @Test fun failedStopAlsoSuppressesAutomaticApplyOfSavesMadeDuringStop() {
        val vm = vm()
        val stop = ConfigApplyIo.expect("stop", "测试停止失败")
        vm.toggle(); started(stop); save(vm, "B")
        stop.release.countDown(); finished(vm)
        assertTrue(ConfigApplyIo.running)
        assertEquals(listOf("stop:initial"), ConfigApplyIo.calls)
        assertEquals(yaml("initial"), ConfigApplyIo.applied)
        assertTrue(prefs.contains("proxyUiConfigApplyPending"))
        assertTrue(vm.state.message.contains("重载配置"))
    }

    @Test fun failedStartKeepsSavedConfigPendingAndNeverClaimsItApplied() {
        ConfigApplyIo.running = false
        prefs.edit().putBoolean("proxyUiLastRunning", false).commit()
        val vm = vm()
        save(vm, "A")
        val start = ConfigApplyIo.expect("start", "测试占位配置拒绝启动")
        vm.toggle(); started(start); start.release.countDown(); finished(vm)
        assertEquals(listOf("start:A"), ConfigApplyIo.calls)
        assertFalse(ConfigApplyIo.running)
        assertEquals(yaml("initial"), ConfigApplyIo.applied)
        assertTrue(prefs.contains("proxyUiConfigApplyPending"))
        assertFalse(messages.any { it.contains("已生效") || it == "代理已启动" })
    }

    @Test fun failedRestartRetainsTheNewestPendingSaveForExplicitRetry() {
        val vm = vm()
        val restart = ConfigApplyIo.expect("restart", "测试重启失败")
        vm.restart(); started(restart); save(vm, "B")
        restart.release.countDown(); finished(vm)
        assertEquals(listOf("restart:initial"), ConfigApplyIo.calls)
        assertEquals(yaml("initial"), ConfigApplyIo.applied)
        assertTrue(prefs.contains("proxyUiConfigApplyPending"))
        assertFalse(messages.any { it.contains("已生效") })
    }

    @Test fun unavailableCoreCannotUseAnOlderRunningProcessToAcknowledgeItsStart() {
        ConfigApplyIo.running = false
        prefs.edit().putBoolean("proxyUiLastRunning", false)
            .putString("proxyBaseCore", "sing-box")
            .putString("proxyBaseMode", "tun")
            .putString("proxyUiConfigApplyPending", "preserve-request").commit()
        val vm = vm()
        assertFalse(vm.state.running)
        // Another operation's existing process is observable during final read-back.
        // It must not turn the rejected sing-box request into a successful start.
        ConfigApplyIo.running = true
        val start = ConfigApplyIo.expect("start")
        start.preflightFailure = ProxyStartPreflightException("Sing-Box 的运行后端还未接入")
        vm.toggle(); started(start); start.release.countDown(); finished(vm)
        assertTrue(vm.startupError?.contains("Sing-Box") == true)
        assertTrue("Keep reporting the actual older process without claiming this start succeeded", vm.state.running)
        assertEquals("sing-box", prefs.getString("proxyBaseCore", ""))
        assertEquals("tun", prefs.getString("proxyBaseMode", ""))
        assertEquals("preserve-request", prefs.getString("proxyUiConfigApplyPending", ""))
        assertEquals(listOf("start:initial"), ConfigApplyIo.calls)
        assertEquals(yaml("initial"), ConfigApplyIo.applied)
        assertFalse(messages.any { it == "代理已启动" || it.contains("已生效") })
    }

    @Test fun sourceReceiptsKeepFrozenApplyBytesAndCoalesceASecondSave() {
        val vm = vm()
        val a = ConfigApplyIo.expect("reload")
        val b = ConfigApplyIo.expect("reload")
        val receiptA = saveBound(vm, "A"); started(a)
        val receiptB = saveBound(vm, "B")
        assertEquals(yaml("initial"), ConfigApplyIo.applied)
        assertEquals(listOf("reload:A"), ConfigApplyIo.calls)
        a.release.countDown(); started(b)
        assertEquals(yaml("A"), ConfigApplyIo.applied)
        assertEquals(listOf(receiptA.sha256, receiptB.sha256), ConfigApplyIo.boundRequests.map { it.sha256 })
        assertEquals(receiptB.sha256, prefs.getString("proxyUiConfigApplySourceHash", ""))
        b.release.countDown(); finished(vm)
        assertEquals(yaml("B"), ConfigApplyIo.applied)
        assertFalse(prefs.contains("proxyUiConfigApplyPending"))
        assertFalse(prefs.contains("proxyUiConfigApplySourceHash"))
    }

    @Test fun aQueuedReceiptCannotApplyAnotherConfigAfterStartCompletes() {
        ConfigApplyIo.running = false
        prefs.edit().putBoolean("proxyUiLastRunning", false).commit()
        val vm = vm()
        val start = ConfigApplyIo.expect("start")
        vm.toggle(); started(start)
        val saved = saveBound(vm, "A")
        val other = library.importConfig(ProxyRuntimeProfile.Core.MIHOMO, "other-B.yaml", yaml("B").byteInputStream())
        start.release.countDown(); finished(vm)
        assertEquals(listOf("start:initial"), ConfigApplyIo.calls)
        assertEquals(saved.name, ConfigApplyIo.boundRequests.single().name)
        assertEquals(yaml("initial"), ConfigApplyIo.applied)
        assertEquals(other.name, library.selected(ProxyRuntimeProfile.Core.MIHOMO)?.name)
        assertEquals(saved.sha256, prefs.getString("proxyUiConfigApplySourceHash", ""))
        assertTrue(prefs.contains("proxyUiConfigApplyPending"))
        assertTrue(vm.state.message.contains("当前配置已切换"))
        assertFalse(messages.contains("运行配置已热重载"))
    }

    @Test fun successfulStartDoesNotAcknowledgeABoundReceiptBeforeItsCheckedReload() {
        ConfigApplyIo.running = false
        prefs.edit().putBoolean("proxyUiLastRunning", false).commit()
        val vm = vm()
        val receipt = saveBound(vm, "A")
        val start = ConfigApplyIo.expect("start")
        val reload = ConfigApplyIo.expect("reload")
        vm.toggle(); started(start); start.release.countDown(); started(reload)
        assertEquals(receipt.sha256, prefs.getString("proxyUiConfigApplySourceHash", ""))
        assertTrue(prefs.contains("proxyUiConfigApplyPending"))
        reload.release.countDown(); finished(vm)
        assertEquals(listOf("start:A", "reload:A"), ConfigApplyIo.calls)
        assertFalse(prefs.contains("proxyUiConfigApplyPending"))
    }

    @Test fun rebuiltVmRetainsTheBoundSourceInsteadOfRetryingTheCurrentSelection() {
        ConfigApplyIo.running = false
        prefs.edit().putBoolean("proxyUiLastRunning", false).commit()
        val original = vm()
        val receipt = saveBound(original, "A")
        stores.single().clear()
        library.importConfig(ProxyRuntimeProfile.Core.MIHOMO, "new-selection.yaml", yaml("B").byteInputStream())
        ConfigApplyIo.running = true
        prefs.edit().putBoolean("proxyUiLastRunning", true).commit()
        val rebuilt = vm()
        rebuilt.reload(); finished(rebuilt)
        assertTrue(ConfigApplyIo.calls.isEmpty())
        assertEquals(receipt.name, ConfigApplyIo.boundRequests.single().name)
        assertEquals(yaml("initial"), ConfigApplyIo.applied)
        assertTrue(prefs.contains("proxyUiConfigApplyPending"))
        assertTrue(rebuilt.state.message.contains("当前配置已切换"))
    }

    @Test fun invalidReceiptCannotSilentlyBecomeAnUnboundReload() {
        val vm = vm()
        prefs.edit().putString("proxyUiConfigApplyPending", "bad-receipt")
            .putString("proxyUiConfigApplySourceCore", "mihomo")
            .putString("proxyUiConfigApplySourceName", entry.name)
            .putString("proxyUiConfigApplySourceHash", "not-a-sha256").commit()
        vm.reload(); finished(vm)
        assertTrue(ConfigApplyIo.calls.isEmpty())
        assertTrue(ConfigApplyIo.boundRequests.isEmpty())
        assertEquals("bad-receipt", prefs.getString("proxyUiConfigApplyPending", ""))
        assertFalse(messages.contains("运行配置已热重载"))
    }

    @Test fun saveDuringFinalStateReadBackCannotFallBetweenWorkers() {
        val vm = vm()
        val a = ConfigApplyIo.expect("reload")
        val b = ConfigApplyIo.expect("reload")
        val readBack = ConfigApplyGate("state")
        save(vm, "A"); started(a)
        ConfigApplyIo.nextStateGate = readBack
        a.release.countDown(); started(readBack)
        assertEquals(HxRunOp.Reload, vm.operation)
        save(vm, "B")
        assertEquals(listOf("reload:A"), ConfigApplyIo.calls)
        readBack.release.countDown(); started(b)
        b.release.countDown(); finished(vm)
        assertEquals(listOf("reload:A", "reload:B"), ConfigApplyIo.calls)
        assertEquals(yaml("B"), ConfigApplyIo.applied)
    }

    @Test fun leavingThePageDoesNotCancelAlreadySavedRequests() {
        val vm = vm()
        val a = ConfigApplyIo.expect("reload")
        val b = ConfigApplyIo.expect("reload")
        save(vm, "A"); started(a); save(vm, "B"); vm.onBackground()
        a.release.countDown(); started(b); b.release.countDown(); finished(vm)
        assertEquals(listOf("reload:A", "reload:B"), ConfigApplyIo.calls)
        assertEquals(yaml("B"), ConfigApplyIo.applied)
    }

    @Test fun clearedVmCannotAcknowledgeOrDrainItsOldJobAndRebuiltVmCanRetry() {
        val old = vm()
        val a = ConfigApplyIo.expect("reload")
        save(old, "A"); started(a); save(old, "B")
        stores.single().clear()
        val rebuilt = vm()
        assertTrue(rebuilt.state.message.contains("尚未确认应用"))
        assertEquals(listOf("reload:A"), ConfigApplyIo.calls)
        val b = ConfigApplyIo.expect("reload")
        rebuilt.reload()
        a.release.countDown(); started(b)
        assertEquals(yaml("A"), ConfigApplyIo.applied)
        assertTrue(prefs.contains("proxyUiConfigApplyPending"))
        assertFalse(messages.contains("运行配置已热重载"))
        b.release.countDown(); finished(rebuilt)
        assertEquals(listOf("reload:A", "reload:B"), ConfigApplyIo.calls)
        assertEquals(yaml("B"), ConfigApplyIo.applied)
        assertFalse(prefs.contains("proxyUiConfigApplyPending"))
    }
}

/** A blocking IO gate deliberately outlives cancellation, like Root's synchronous call. */
internal class ConfigApplyGate(
    val operation: String,
    val failure: String? = null,
    val alreadyRunning: Boolean = false,
    val cancelled: Boolean = false,
) {
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)
    var preflightFailure: ProxyStartPreflightException? = null
}

internal object ConfigApplyIo {
    val calls = CopyOnWriteArrayList<String>()
    val unexpected = CopyOnWriteArrayList<String>()
    val gates = CopyOnWriteArrayList<ConfigApplyGate>()
    val boundRequests = CopyOnWriteArrayList<ProxyConfigLibrary.SourceVersion>()
    private val lock = ReentrantLock()
    private var consumed = 0
    @Volatile var running = true
    @Volatile var active = 0
    @Volatile var applied = ""
    @Volatile var nextStateGate: ConfigApplyGate? = null
    @Volatile var nextStateFailure: IOException? = null
    @Volatile var stateMessage = ""
    var source: () -> String = { error("Source not installed") }
    var openSource: (ProxyConfigLibrary.SourceVersion) -> ProxyConfigLibrary.SourceApplication = { error("Source lease not installed") }

    fun reset() {
        calls.clear(); unexpected.clear(); gates.clear(); boundRequests.clear(); consumed = 0
        running = true; active = 0; applied = ""; nextStateGate = null
        nextStateFailure = null; stateMessage = ""
    }
    fun expect(operation: String, failure: String? = null, alreadyRunning: Boolean = false, cancelled: Boolean = false): ConfigApplyGate =
        ConfigApplyGate(operation, failure, alreadyRunning, cancelled).also { gates += it }

    fun state() = ProxyComposeState(running = running, config = "apply-queue.yaml", message = stateMessage)

    suspend fun readState(): ProxyComposeState = withContext(Dispatchers.IO) {
        nextStateFailure?.let { failure -> nextStateFailure = null; throw failure }
        nextStateGate?.let { gate ->
            gate.entered.countDown()
            if (!gate.release.await(8, TimeUnit.SECONDS)) throw IOException("Unreleased read-back gate")
            nextStateGate = null
        }
        state()
    }

    suspend fun action(operation: String): Any = withContext(Dispatchers.IO) { blockingAction(operation) }

    suspend fun boundAction(receipt: ProxyConfigLibrary.SourceVersion): Any = withContext(Dispatchers.IO) {
        boundRequests += receipt
        // Like Root's synchronous transaction, acquire/use/close on one IO thread.
        openSource(receipt).use { application -> blockingAction("reload", application.text) }
    }

    private fun blockingAction(operation: String, frozenSource: String? = null): Any =
        lock.withLock {
            active++
            try {
                val gate = gates.getOrNull(consumed++)
                if (gate == null || gate.operation != operation) {
                    unexpected += operation
                    throw IOException("Unplanned test IO: $operation")
                }
                val captured = frozenSource ?: source()
                calls += "$operation:${captured.lineSequence().first().removePrefix("# ")}"
                gate.entered.countDown()
                if (!gate.release.await(8, TimeUnit.SECONDS)) throw IOException("Unreleased test gate: $operation")
                gate.preflightFailure?.let { throw it }
                gate.failure?.let { throw IOException(it) }
                when (operation) {
                    "reload" -> { check(running); applied = captured }
                    "start", "restart" -> if (!gate.cancelled) {
                        running = true
                        if (!gate.alreadyRunning) applied = captured
                    }
                    "stop" -> running = false
                }
                if (operation == "reload") "运行配置已热重载" else JSONObject().put("ok", true).put("alreadyRunning", gate.alreadyRunning).put("cancelled", gate.cancelled)
            } finally { active-- }
        }
}

/** No unhandled call can fall through to Root, scripts or the public controller API. */
@Implements(value = ProxyComposeController::class, isInAndroidSdk = false, callThroughByDefault = false)
class ConfigApplyControllerShadow {
    @Implementation fun state(continuation: Continuation<Any?>): Any = ConfigApplyIo.state()
    @Implementation fun reload(continuation: Continuation<Any?>): Any? =
        (suspend { ConfigApplyIo.action("reload") }).startCoroutineUninterceptedOrReturn(continuation)
    @Implementation fun reload(source: ProxyConfigLibrary.SourceVersion, continuation: Continuation<Any?>): Any? =
        (suspend { ConfigApplyIo.boundAction(source) }).startCoroutineUninterceptedOrReturn(continuation)
    @Implementation fun start(progress: (String) -> Unit, continuation: Continuation<Any?>): Any? =
        (suspend { ConfigApplyIo.action("start") }).startCoroutineUninterceptedOrReturn(continuation)
    @Implementation fun restart(progress: (String) -> Unit, continuation: Continuation<Any?>): Any? =
        (suspend { ConfigApplyIo.action("restart") }).startCoroutineUninterceptedOrReturn(continuation)
    @Implementation fun stop(progress: (String) -> Unit, continuation: Continuation<Any?>): Any? =
        (suspend { ConfigApplyIo.action("stop") }).startCoroutineUninterceptedOrReturn(continuation)
    @Implementation fun diagnostics(continuation: Continuation<Any?>): Any = "test-only diagnostics"
}

@Implements(value = ProxyDashboardRepository::class, isInAndroidSdk = false, callThroughByDefault = false)
class ConfigApplyRepositoryShadow {
    @Implementation fun state(continuation: Continuation<Any?>): Any? =
        (suspend { ConfigApplyIo.readState() }).startCoroutineUninterceptedOrReturn(continuation)
    @Implementation fun providers(continuation: Continuation<Any?>): Any = emptyList<DashboardProviderUi>()
    @Implementation fun coreVersion(continuation: Continuation<Any?>): Any = "isolated-test"
    @Implementation fun ensureIcons(continuation: Continuation<Any?>): Any = Unit
}

@Implements(value = ProxyRuntimeInspector::class, isInAndroidSdk = false, callThroughByDefault = false)
class ConfigApplyInspectorShadow {
    @Implementation fun sample(continuation: Continuation<Any?>): Any = ProxyRuntimeSnapshot(running = ConfigApplyIo.running)
}
