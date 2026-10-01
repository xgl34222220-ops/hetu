package io.github.xgl34222220.hetu

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.net.TrafficStats
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.xgl34222220.hetu.ui.HetuComposeController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID

internal enum class HxTab(val label: String) { Home("首页"), Panel("面板"), Tools("工具"), Settings("设置") }

/** Compact panel sections. Strategy stays inside Panel so the dock remains focused. */
internal val HxPanelSections = listOf(
    "overview" to "概览",
    "proxies" to "策略",
    "providers" to "订阅",
    "conn" to "连接",
    "rules" to "规则",
    "sets" to "规则集",
    "logs" to "日志",
)

internal enum class HxRunOp { Start, Stop, Restart, Reload }

// This is an unacknowledged request, not a claim about the core's config version.
private const val CONFIG_APPLY_PENDING_KEY = "proxyUiConfigApplyPending"

/** Result of a long-running per-item task (rule-set update, provider update…). */
internal data class HxTask(val running: Boolean = false, val ok: Boolean? = null, val message: String = "")

/**
 * Single source of truth for the UI. Every action here calls the real Root / Mihomo
 * backend; nothing is simulated. Polling runs only while the activity is started.
 */
internal class HetuViewModel(application: Application) : AndroidViewModel(application) {
    private val app: Application = application
    val prefs: SharedPreferences = app.getSharedPreferences("hetu", Context.MODE_PRIVATE)
    val controller = ProxyComposeController(app)
    val repo = ProxyDashboardRepository(app)
    val inspector = ProxyRuntimeInspector(app)
    val filters = HetuComposeController(app)

    /* ---------------- navigation ---------------- */
    private var tabState by mutableStateOf(if (prefs.getBoolean("startOnPanel", false)) HxTab.Panel else HxTab.Home)

    /** Current 「面板」 section; startup uses the user's configured default page. */
    var panelSection by mutableStateOf(defaultPanelSection())
        private set

    private fun defaultPanelSection(): String {
        val requested = prefs.getString("defaultPanelSection", "overview").orEmpty()
        return requested.takeIf { key -> HxPanelSections.any { it.first == key } } ?: "overview"
    }

    /** Dock destination. Panel remembers whichever live sub-page the user last opened. */
    var tab: HxTab
        get() = tabState
        set(value) { tabState = value }

    /** Open a live panel section from home cards, tools or deep links. */
    fun openPanel(section: String) {
        val requested = section.ifBlank { "overview" }
        panelSection = requested.takeIf { key -> HxPanelSections.any { it.first == key } } ?: "overview"
        tabState = HxTab.Panel
    }

    fun setDefaultPanelSection(section: String) {
        val value = section.takeIf { key -> HxPanelSections.any { it.first == key } } ?: "overview"
        prefs.edit().putString("defaultPanelSection", value).apply()
        bumpSettings()
    }

    /** Incremented when the current dock tab is tapped again (scroll to top). */
    var reselect by mutableIntStateOf(0)

    /* ---------------- appearance ---------------- */
    var appearance by mutableStateOf(prefs.getString("appearance", "system") ?: "system")
        private set
    var dynamicColor by mutableStateOf(prefs.getBoolean("hetuDynamicColor", prefs.getBoolean("enableMonet", false)))
        private set
    var blurEnabled by mutableStateOf(prefs.getBoolean("enableBlur", true))
        private set
    /** Custom accent chosen in「更多主题选项」; blank = Hetu jade. */
    var accentHex by mutableStateOf(customAccent())
        private set

    /** Dark mode on true black (OLED). */
    var pureBlack by mutableStateOf(prefs.getBoolean("pureBlackDark", false))
        private set

    fun updatePureBlack(value: Boolean) {
        prefs.edit().putBoolean("pureBlackDark", value).apply()
        pureBlack = value
    }

    /** Accent swatch; blank or the default blue resets to the Hetu default. */
    fun setAccent(hex: String) {
        prefs.edit().putString("accentHex", hex).apply()
        accentHex = customAccent()
    }

    /** The accent as stored (default blue when none), for showing the current swatch. */
    val accentChoice: String get() = accentHex.ifBlank { "#2A62E8" }

    private fun customAccent(): String = (prefs.getString("accentHex", "") ?: "")
        .takeUnless { it.equals("#2563EB", true) || it.equals("#3B82F6", true) || it.equals("#2A62E8", true) || it.equals("#7EA6FF", true) }
        .orEmpty()

    /** Theme pages that still live in their own activities write prefs; pick them up on return. */
    fun reloadAppearance() {
        appearance = prefs.getString("appearance", "system") ?: "system"
        dynamicColor = prefs.getBoolean("hetuDynamicColor", prefs.getBoolean("enableMonet", false))
        accentHex = customAccent()
        blurEnabled = prefs.getBoolean("enableBlur", true)
        pureBlack = prefs.getBoolean("pureBlackDark", false)
    }

    fun setAppearanceMode(value: String) {
        prefs.edit().putString("appearance", value).apply()
        appearance = value
    }

    fun setDynamic(value: Boolean) {
        prefs.edit().putBoolean("hetuDynamicColor", value).putBoolean("enableMonet", value).apply()
        dynamicColor = value
    }

    /* ---------------- runtime state ---------------- */
    private var configApplyQueued = false
    private var configApplyError: String? = null
    private var runtimeMessage = ""
    var state by mutableStateOf(initialState())
        private set
    var runtime by mutableStateOf(initialRuntime())
        private set
    var upRate by mutableLongStateOf(0L)
        private set
    var downRate by mutableLongStateOf(0L)
        private set
    var localUpRate by mutableLongStateOf(0L)
        private set
    var localDownRate by mutableLongStateOf(0L)
        private set
    var homeSpeedSource by mutableStateOf(
        prefs.getString("homeSpeedSource", "api").orEmpty().takeIf { it == "api" || it == "local" } ?: "api"
    )
        private set
    var cpuPercent by mutableFloatStateOf(0f)
        private set
    var cpuSampleAvailable by mutableStateOf(false)
        private set
    var cpuSampledAtElapsed by mutableLongStateOf(0L)
        private set
    var operation by mutableStateOf<HxRunOp?>(null)
        private set
    var operationText by mutableStateOf("")
        private set
    var startupError by mutableStateOf<String?>(null)
    private var startupPreflightRejected = false
    var refreshing by mutableStateOf(false)
        private set
    var loadedOnce by mutableStateOf(false)
        private set
    var coreVersion by mutableStateOf("")
        private set
    var settingsRevision by mutableIntStateOf(0)
        private set

    var providers by mutableStateOf<List<DashboardProviderUi>>(emptyList())
        private set
    var siteDelays by mutableStateOf(ProxyLatencyTargets.lastResults(prefs))
        private set
    var siteTesting by mutableStateOf(false)
        private set

    /** node name -> delay (ms, -1 timeout, -2 failed). */
    val delays = mutableStateMapOf<String, Long>()
    /** Last ~40 samples of (download, upload) bytes/s for the home sparkline. */
    val rateHistory = androidx.compose.runtime.mutableStateListOf<Long>()
    val upHistory = androidx.compose.runtime.mutableStateListOf<Long>()
    val testingNodes = mutableStateMapOf<String, Boolean>()
    val testingGroups = mutableStateMapOf<String, Boolean>()
    val pendingSelection = mutableStateMapOf<String, String>()
    var testingAll by mutableStateOf(false)
        private set

    /* ---------------- rules ---------------- */
    var rules by mutableStateOf<List<ProxyRuleUi>>(emptyList())
        private set
    var rulesLoading by mutableStateOf(false)
        private set
    var ruleSets by mutableStateOf<List<DashboardRuleSetUi>>(emptyList())
        private set
    var ruleSetsLoading by mutableStateOf(false)
        private set
    val ruleSetTasks = mutableStateMapOf<String, HxTask>()
    var ruleSetsUpdatingAll by mutableStateOf(false)
        private set
    val providerTasks = mutableStateMapOf<String, HxTask>()
    var providersUpdatingAll by mutableStateOf(false)
        private set

    /* ---------------- messages ---------------- */
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val messages: SharedFlow<String> = _messages
    fun toast(text: String) {
        if (text.isNotBlank()) _messages.tryEmit(text)
    }

    private fun errorText(error: Throwable, fallback: String): String =
        error.message?.takeIf { it.isNotBlank() } ?: fallback

    /* ---------------- polling ---------------- */
    private var pollJob: Job? = null
    private var lastUp = 0L
    private var lastDown = 0L
    private var lastAt = 0L
    private val cpuSamples = CpuSampleTracker()
    private var lastLocalTxBytes = -1L
    private var lastLocalRxBytes = -1L
    private var lastLocalAt = 0L
    private var lastProviderRefreshAt = 0L
    private val coldStartAt = SystemClock.elapsedRealtime()
    private val measuredAt = HashMap<String, Long>()

    fun onForeground() {
        reloadAppearance()
        if (pollJob?.isActive == true) return
        pollJob = viewModelScope.launch {
            launch {
                delay(600)
                try { repo.ensureIcons() } catch (cancel: CancellationException) { throw cancel } catch (_: Exception) { }
            }
            launch {
                // 「延迟自动刷新」: opt-in periodic site probes (30 s / 60 s), same as before.
                while (isActive) {
                    val interval = prefs.getInt("latencyAutoRefreshSeconds", 0)
                    delay(if (interval == 30 || interval == 60) interval * 1_000L else 15_000L)
                    if ((interval == 30 || interval == 60) && state.running && operation == null) measureSitesQuietly()
                }
            }
            while (isActive) {
                if (operation == null) refreshNow()
                delay(if (tab == HxTab.Home || tab == HxTab.Panel) 2_000L else 4_000L)
            }
        }
        ProxyStatusNotificationService.refresh(app)
    }

    fun onBackground() {
        pollJob?.cancel()
        pollJob = null
    }

    private fun initialState(): ProxyComposeState {
        val profile = ProxyRuntimeProfile.load(prefs)
        val cachedConfig = prefs.getString("proxySelectedConfig.${profile.core.id}", "").orEmpty().ifBlank { "尚未选择配置" }
        val running = prefs.getBoolean("proxyUiLastRunning", prefs.getBoolean("proxyRootWanted", false))
        return ProxyComposeState(
            running = running,
            message = configApplyMessage(running),
            core = profile.core.label,
            mode = profile.mode.label,
            ipv6 = profile.ipv6.id,
            autoOverwrite = profile.autoOverwrite,
            config = prefs.getString("proxyUiLastConfig", cachedConfig) ?: cachedConfig,
        )
    }

    private fun initialRuntime(): ProxyRuntimeSnapshot = ProxyRuntimeSnapshot(
        running = prefs.getBoolean("proxyUiLastRunning", false),
        elapsedSeconds = prefs.getLong("proxyUiLastElapsed", 0L),
        rssBytes = prefs.getLong("proxyUiLastRss", 0L),
        lanAddress = prefs.getString("proxyUiLastLan", "—") ?: "—",
        lanInterface = prefs.getString("proxyUiLastLanIf", "—") ?: "—",
        wanAddress = prefs.getString("proxyUiLastWan", "—") ?: "—",
        wanCountryCode = prefs.getString("proxyUiLastWanCountry", "") ?: "",
        wanCountry = prefs.getString("proxyUiLastWanCountryName", "—") ?: "—",
        wanRegion = prefs.getString("proxyUiLastWanRegion", "—") ?: "—",
        wanCity = prefs.getString("proxyUiLastWanCity", "—") ?: "—",
        wanIsp = prefs.getString("proxyUiLastWanIsp", "—") ?: "—",
        wanAsn = prefs.getString("proxyUiLastWanAsn", "—") ?: "—",
        cpuAffinity = prefs.getString("proxyUiLastCpuAffinity", "—") ?: "—",
        currentCpu = prefs.getInt("proxyUiLastCurrentCpu", -1),
        wanState = "stale",
    )

    suspend fun refreshNow() {
        try {
            val startedAt = SystemClock.elapsedRealtime()
            val next = repo.state()
            val now = SystemClock.elapsedRealtime()
            val totalTx = TrafficStats.getTotalTxBytes()
            val totalRx = TrafficStats.getTotalRxBytes()
            if (next.running && lastLocalAt > 0L && now > lastLocalAt &&
                totalTx >= 0L && totalRx >= 0L && lastLocalTxBytes >= 0L && lastLocalRxBytes >= 0L &&
                totalTx >= lastLocalTxBytes && totalRx >= lastLocalRxBytes) {
                val elapsedLocal = now - lastLocalAt
                localUpRate = ((totalTx - lastLocalTxBytes) * 1000L / elapsedLocal).coerceAtLeast(0L)
                localDownRate = ((totalRx - lastLocalRxBytes) * 1000L / elapsedLocal).coerceAtLeast(0L)
            } else if (!next.running) {
                localUpRate = 0L
                localDownRate = 0L
            }
            if (totalTx >= 0L && totalRx >= 0L) {
                lastLocalTxBytes = totalTx
                lastLocalRxBytes = totalRx
                lastLocalAt = now
            }
            // Right after process start a recovering core may briefly look stopped.
            if (state.running && !next.running && prefs.getBoolean("proxyRootWanted", false) && now - coldStartAt < 2_500L) return

            if (next.panelReady && lastAt > 0L && now > lastAt && next.uploadTotal >= lastUp && next.downloadTotal >= lastDown) {
                val elapsed = now - lastAt
                upRate = ((next.uploadTotal - lastUp) * 1000L / elapsed).coerceAtLeast(0L)
                downRate = ((next.downloadTotal - lastDown) * 1000L / elapsed).coerceAtLeast(0L)
                rateHistory.add(downRate)
                upHistory.add(upRate)
                while (rateHistory.size > 40) rateHistory.removeAt(0)
                while (upHistory.size > 40) upHistory.removeAt(0)
            }
            if (!next.running) {
                upRate = 0L
                downRate = 0L
                rateHistory.clear()
                upHistory.clear()
            }
            syncCoreLatencyResults(next.groups, delays, measuredAt, startedAt)

            val sampled = if (next.running) {
                try { inspector.sample() } catch (cancel: CancellationException) { throw cancel } catch (_: Exception) { runtime.copy(processSampleValid = false) }
            } else ProxyRuntimeSnapshot()
            val cpu = cpuSamples.update(CpuCounterSample(sampled.pid, sampled.processTicks, sampled.systemTicks,
                sampled.elapsedSeconds, sampled.processSampleAtElapsed, sampled.processSampleValid), next.running,
                Runtime.getRuntime().availableProcessors())
            cpuSampleAvailable = cpu != null
            cpuPercent = cpu?.percent ?: 0f
            cpuSampledAtElapsed = cpu?.sampledAt ?: 0L
            runtime = sampled

            if (!next.running) {
                providers = emptyList()
                lastProviderRefreshAt = 0L
                coreVersion = ""
            } else {
                if (providers.isEmpty() || now - lastProviderRefreshAt > 30_000L) {
                    lastProviderRefreshAt = now
                    val fresh = try { repo.providers() } catch (cancel: CancellationException) { throw cancel } catch (_: Exception) { providers }
                    if (fresh.isNotEmpty() || providers.isEmpty()) providers = fresh
                }
                if (coreVersion.isBlank()) coreVersion = repo.coreVersion()
            }

            state = if (next.running && !next.panelReady) {
                next.copy(groups = state.groups, connections = state.connections)
            } else next
            runtimeMessage = next.message
            refreshConfigApplyMessage()
            if (next.running && !startupPreflightRejected) startupError = null
            if (next.panelReady) {
                lastAt = now
                lastUp = next.uploadTotal
                lastDown = next.downloadTotal
                // Opt-in local traffic/connection history (「测速与 API」→ 历史采集).
                try { ProxyApiHistoryStore.record(app, upRate, downRate, next.connections) } catch (cancel: CancellationException) { throw cancel } catch (_: Exception) { }
            }
            loadedOnce = true
            persistSnapshot(next, sampled)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            cpuSamples.clear()
            cpuSampleAvailable = false
            cpuSampledAtElapsed = 0L
            // Polling errors are shown through state.message on the next good read.
        }
    }

    private fun persistSnapshot(next: ProxyComposeState, sampled: ProxyRuntimeSnapshot) {
        prefs.edit()
            .putBoolean("proxyUiLastRunning", next.running)
            .putString("proxyUiLastConfig", next.config)
            .putLong("proxyUiLastElapsed", sampled.elapsedSeconds)
            .putLong("proxyUiLastRss", sampled.rssBytes)
            .putString("proxyUiLastLan", sampled.lanAddress)
            .putString("proxyUiLastLanIf", sampled.lanInterface)
            .putString("proxyUiLastWan", sampled.wanAddress)
            .putString("proxyUiLastWanCountry", sampled.wanCountryCode)
            .putString("proxyUiLastWanCountryName", sampled.wanCountry)
            .putString("proxyUiLastWanRegion", sampled.wanRegion)
            .putString("proxyUiLastWanCity", sampled.wanCity)
            .putString("proxyUiLastWanIsp", sampled.wanIsp)
            .putString("proxyUiLastWanAsn", sampled.wanAsn)
            .putString("proxyUiLastCpuAffinity", sampled.cpuAffinity)
            .putInt("proxyUiLastCurrentCpu", sampled.currentCpu)
            .apply()
    }

    fun updateHomeSpeedSource(source: String) {
        val value = source.takeIf { it == "api" || it == "local" } ?: "api"
        prefs.edit().putString("homeSpeedSource", value).apply()
        homeSpeedSource = value
    }

    fun pullRefresh() {
        if (refreshing) return
        viewModelScope.launch {
            refreshing = true
            try {
                refreshNow()
                if (state.running) {
                    coroutineScope {
                        val p = async { try { repo.providers() } catch (cancel: CancellationException) { throw cancel } catch (_: Exception) { null } }
                        val s = async { try { repo.siteLatencies() } catch (cancel: CancellationException) { throw cancel } catch (_: Exception) { null } }
                        p.await()?.let { providers = it; lastProviderRefreshAt = SystemClock.elapsedRealtime() }
                        s.await()?.let { if (it.isNotEmpty()) { siteDelays = it; ProxyLatencyTargets.persistLast(prefs, it) } }
                    }
                }
            } finally {
                refreshing = false
            }
        }
    }

    /* ---------------- lifecycle actions ---------------- */

    fun toggle() {
        if (operation != null) return
        startupPreflightRejected = false
        startupError = null
        val stopping = state.running
        // A stop consumes no config request, even if it fails and the core stays alive.
        configApplyQueued = !stopping && prefs.contains(CONFIG_APPLY_PENDING_KEY)
        operation = if (stopping) HxRunOp.Stop else HxRunOp.Start
        viewModelScope.launch {
            var canApplyConfig = false
            val configToken = prefs.getString(CONFIG_APPLY_PENDING_KEY, null)
            operationText = if (stopping) "正在停止…" else "正在启动…"
            try {
                val result = if (stopping) controller.stop { operationText = it } else controller.start { operationText = it }
                state = controller.state()
                runtimeMessage = state.message
                canApplyConfig = !stopping && state.running
                if (canApplyConfig) {
                    if (result.optBoolean("ok", false) && !result.optBoolean("cancelled", false) &&
                        !result.optBoolean("alreadyRunning", false)) acknowledgeConfigApply(configToken)
                    toast("代理已启动")
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                val reason = errorText(error, "操作失败")
                // Configuration problems are known before Root is touched; report them at once.
                val configProblem = !stopping && (error is ProxyStartPreflightException || reason.contains("占位") || reason.contains("尚未选择配置") || reason.contains("仅所选应用代理"))
                startupPreflightRejected = configProblem
                operationText = "确认最终运行状态…"
                val recovered = if (configProblem || stopping) null else settleStart()
                if (recovered != null) {
                    state = recovered
                    runtimeMessage = recovered.message
                    canApplyConfig = true
                } else if (!stopping) {
                    startupError = if (reason.contains("仅所选应用代理")) {
                        "当前应用范围是「仅所选应用代理」，但没有选中任何可用应用。\n\n请到「设置 → 应用名单」至少勾选一个应用，或把应用范围改为其它模式后再启动。"
                    } else if (configProblem) {
                        reason
                    } else {
                        val diagnostics = try { controller.diagnostics() } catch (cancel: CancellationException) { throw cancel } catch (_: Exception) { "" }
                        if (diagnostics.isBlank()) reason else "$reason\n\n—— Root / Mihomo 诊断 ——\n${diagnostics.trim()}"
                    }
                } else {
                    toast(reason)
                }
            } finally {
                finishOperation(canApplyConfig)
            }
        }
    }

    private suspend fun settleStart(): ProxyComposeState? {
        for (wait in longArrayOf(350L, 1_050L, 1_800L)) {
            delay(wait)
            val confirmed = try { controller.state() } catch (cancel: CancellationException) { throw cancel } catch (_: Exception) { null }
            if (confirmed?.running == true) return confirmed
        }
        return null
    }

    fun restart() {
        if (operation != null || !state.running) return
        startupPreflightRejected = false
        startupError = null
        configApplyQueued = prefs.contains(CONFIG_APPLY_PENDING_KEY)
        operation = HxRunOp.Restart
        viewModelScope.launch {
            var canApplyConfig = false
            val configToken = prefs.getString(CONFIG_APPLY_PENDING_KEY, null)
            operationText = "正在重启…"
            ProxyRuntimeSettings.beginApply(prefs)
            try {
                controller.restart { operationText = it }
                // restart() checks that replacement actually completed before returning.
                acknowledgeConfigApply(configToken)
                prefs.edit().remove("proxyRootRuntimeRefreshPending").remove("proxyRootUpgradeError").apply()
                canApplyConfig = true
                toast(if (configApplyQueued) "已重启，继续应用最新配置" else "已重启，设置已生效")
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                ProxyRuntimeSettings.recordFailure(prefs, error)
                toast(errorText(error, "重启失败"))
            } finally {
                settingsRevision++
                finishOperation(canApplyConfig)
            }
        }
    }

    /** The existing manual retry also coalesces with a running start/restart/reload. */
    fun reload() {
        if (operation == HxRunOp.Stop || (operation == null && !state.running)) return
        queueConfigApply()
    }

    private fun configApplyMessage(running: Boolean): String {
        if (!prefs.contains(CONFIG_APPLY_PENDING_KEY)) return ""
        if (!running) return "配置已保存，下次启动时应用"
        return configApplyError?.let { "配置已保存，但应用失败：$it；请点「重载配置」重试" }
            ?: "配置已保存，尚未确认应用；可点「重载配置」重试"
    }

    private fun refreshConfigApplyMessage() {
        state = state.copy(message = listOf(runtimeMessage, configApplyMessage(state.running))
            .filter { it.isNotBlank() }.joinToString("\n"))
    }

    private fun markConfigApplyPending() {
        // A token prevents an older completion (including an old VM) from clearing
        // a later save. Source persistence still belongs to ProxyConfigLibrary.
        prefs.edit().putString(CONFIG_APPLY_PENDING_KEY, UUID.randomUUID().toString()).apply()
        configApplyError = null
    }

    private fun acknowledgeConfigApply(token: String?): Boolean {
        if (token == null || prefs.getString(CONFIG_APPLY_PENDING_KEY, null) != token) return false
        prefs.edit().remove(CONFIG_APPLY_PENDING_KEY).apply()
        configApplyQueued = false
        configApplyError = null
        return true
    }

    private fun queueConfigApply() {
        markConfigApplyPending()
        configApplyQueued = true
        refreshConfigApplyMessage()
        launchConfigApply()
    }

    private fun launchConfigApply() {
        if (operation != null || !state.running || !configApplyQueued || !viewModelScope.isActive) return
        operation = HxRunOp.Reload
        viewModelScope.launch {
            operationText = "正在重载配置…"
            try {
                while (configApplyQueued && isActive) {
                    val token = prefs.getString(CONFIG_APPLY_PENDING_KEY, null)
                    configApplyQueued = false
                    if (token == null) break
                    configApplyError = null
                    try {
                        // Never cancel an already issued Root/API operation to replace it.
                        // Root serializes and refuses reload when no core is alive.
                        val result = controller.reload()
                        if (acknowledgeConfigApply(token)) toast(result)
                    } catch (cancel: CancellationException) {
                        throw cancel
                    } catch (error: Exception) {
                        configApplyError = errorText(error, "重载失败")
                        toast("配置已保存，但应用失败：$configApplyError；可点「重载配置」重试")
                    }
                    // A newer save gets one next attempt, including after failure.
                    // An unchanged failed request stays pending for explicit retry.
                    refreshConfigApplyMessage()
                }
            } finally {
                finishOperation(allowConfigApply = true)
            }
        }
    }

    private suspend fun finishOperation(allowConfigApply: Boolean) {
        try {
            // Keep ownership during read-back so a save here is not lost either.
            refreshNow()
        } finally {
            operation = null
            operationText = ""
            if (!allowConfigApply) configApplyQueued = false
            refreshConfigApplyMessage()
            // Clearing a VM leaves its token pending; it cannot start another IO.
            if (allowConfigApply && viewModelScope.isActive) launchConfigApply()
        }
    }

    fun setTrafficMode(mode: String) {
        if (!state.running) return
        viewModelScope.launch {
            try {
                repo.setTrafficMode(mode)
                refreshNow()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                toast(errorText(error, "切换模式失败"))
            }
        }
    }

    fun settingsPending(): Boolean = ProxyRuntimeSettings.pending(state.running, prefs)

    fun bumpSettings() { settingsRevision++ }

    /* ---------------- proxies ---------------- */

    fun select(group: String, node: String) {
        if (!state.running || operation != null || pendingSelection.containsKey(group)) return
        val current = state.groups.firstOrNull { it.name == group } ?: return
        if (!HxFormat.isSelectable(current.type) || current.now == node || current.nodes.none { it.name == node }) return
        pendingSelection[group] = node
        viewModelScope.launch {
            try {
                if (!state.running || operation != null) return@launch
                repo.select(group, node, prefs.getBoolean("proxySelectorDisconnectOnSelect", false))
                if (!state.running || operation != null) return@launch
                // Polling tolerates partial reads; an explicit switch needs an actual
                // core response. A failed read leaves the last observed check intact.
                val actual = try { repo.selectedNode(group) }
                catch (cancel: CancellationException) { throw cancel }
                catch (error: Exception) {
                    toast("切换结果尚未确认：" + errorText(error, "读取当前节点失败"))
                    return@launch
                }
                if (!state.running || operation != null) return@launch
                state = state.copy(groups = state.groups.map { if (it.name == group) it.copy(now = actual) else it })
                check(actual == node) { "核心尚未确认所选节点，当前为 $actual" }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                toast("切换失败：" + errorText(error, "未知错误"))
            } finally {
                pendingSelection.remove(group)
            }
        }
    }

    private suspend fun probe(node: String) {
        try {
            delays[node] = repo.delay(node)
            measuredAt[node] = SystemClock.elapsedRealtime()
        } catch (failure: MihomoControllerClient.DelayFailure) {
            delays[node] = if (failure.timedOut) -1L else -2L
            measuredAt[node] = SystemClock.elapsedRealtime()
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            // Transport error: keep the previous measurement.
        }
    }

    fun testNode(node: String) {
        if (testingNodes[node] == true || !state.running) return
        testingNodes[node] = true
        viewModelScope.launch {
            try { probe(node) } finally { testingNodes.remove(node) }
        }
    }

    fun testGroup(group: ProxyGroupUi) {
        if (testingGroups[group.name] == true || !state.running) return
        val targets = group.nodes.map { it.name }.filter { it.uppercase() !in setOf("DIRECT", "REJECT", "REJECT-DROP", "PASS", "COMPATIBLE") }.distinct()
        if (targets.isEmpty()) return
        testingGroups[group.name] = true
        viewModelScope.launch {
            try {
                // The pinned core's /group/.../delay clears forced selection on automatic
                // groups. Probe individual nodes so measuring cannot unpin the user's route.
                for (chunk in targets.chunked(4)) {
                    if (!state.running) break
                    coroutineScope {
                        chunk.map { node -> async {
                            // A single-node test or another group may already own this probe.
                            if (state.running && testingNodes[node] != true) {
                                testingNodes[node] = true
                                try { probe(node) } finally { testingNodes.remove(node) }
                            }
                        } }.awaitAll()
                    }
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                toast(errorText(error, "策略组测速失败"))
            } finally {
                testingGroups.remove(group.name)
            }
        }
    }

    fun testAll() {
        if (testingAll || !state.running) return
        testingAll = true
        viewModelScope.launch {
            try {
                val result = repo.globalDelay()
                delays.putAll(result)
                val stamp = SystemClock.elapsedRealtime()
                result.keys.forEach { measuredAt[it] = stamp }
                val ok = result.values.count { it > 0L }
                toast("测速完成：$ok / ${result.size} 个节点可用")
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                toast(errorText(error, "测速失败"))
            } finally {
                testingAll = false
            }
        }
    }

    private suspend fun measureSitesQuietly() {
        if (siteTesting) return
        siteTesting = true
        try {
            val measured = repo.siteLatencies()
            if (measured.isNotEmpty()) {
                siteDelays = measured
                ProxyLatencyTargets.persistLast(prefs, measured)
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
        } finally {
            siteTesting = false
        }
    }

    /* ---------------- logs ---------------- */
    var logEntries by mutableStateOf<List<RefLogEntry>>(emptyList())
        private set
    var logsLoading by mutableStateOf(false)
        private set

    fun loadLogs(quiet: Boolean = false) {
        if (logsLoading) return
        logsLoading = true
        viewModelScope.launch {
            try {
                logEntries = refParseLogs19(inspector.runtimeLog())
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                if (!quiet) toast(errorText(error, "日志读取失败"))
            } finally {
                logsLoading = false
            }
        }
    }

    fun measureSites() {
        if (siteTesting || !state.running) return
        siteTesting = true
        viewModelScope.launch {
            try {
                val measured = repo.siteLatencies()
                if (measured.isNotEmpty()) {
                    siteDelays = measured
                    ProxyLatencyTargets.persistLast(prefs, measured)
                }
                if (measured.values.none { it > 0L }) toast("本机直测失败，请检查当前网络")
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                toast(errorText(error, "本机直测失败"))
            } finally {
                siteTesting = false
            }
        }
    }

    /* ---------------- connections ---------------- */

    fun closeConnection(id: String) {
        viewModelScope.launch {
            try {
                repo.closeConnection(id)
                refreshNow()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                toast(errorText(error, "断开失败"))
            }
        }
    }

    fun closeAll() {
        viewModelScope.launch {
            try {
                repo.closeAll()
                toast("已断开全部连接")
                refreshNow()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                toast(errorText(error, "断开失败"))
            }
        }
    }

    /* ---------------- rules / rule sets / providers ---------------- */

    fun loadRules() {
        if (rulesLoading || !state.running) return
        rulesLoading = true
        viewModelScope.launch {
            try {
                rules = controller.rules()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                toast(errorText(error, "规则读取失败"))
            } finally {
                rulesLoading = false
            }
        }
    }

    fun loadRuleSets() {
        if (ruleSetsLoading || !state.running) return
        ruleSetsLoading = true
        viewModelScope.launch {
            try {
                ruleSets = repo.ruleSets()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                toast(errorText(error, "规则集读取失败"))
            } finally {
                ruleSetsLoading = false
            }
        }
    }

    fun updateRuleSet(name: String) {
        if (ruleSetTasks[name]?.running == true || !state.running) return
        ruleSetTasks[name] = HxTask(running = true)
        viewModelScope.launch {
            try {
                val fresh = repo.refreshRuleSet(name)
                if (fresh != null) ruleSets = ruleSets.map { if (it.name == name) fresh else it }
                ruleSetTasks[name] = HxTask(ok = true, message = "已更新")
            } catch (cancel: CancellationException) {
                ruleSetTasks.remove(name)
                throw cancel
            } catch (error: Exception) {
                ruleSetTasks[name] = HxTask(ok = false, message = errorText(error, "更新失败"))
            }
        }
    }

    /** Updates every remote (HTTP) rule-set, three at a time, with per-item status. */
    fun updateAllRuleSets() {
        if (ruleSetsUpdatingAll || !state.running) return
        ruleSetsUpdatingAll = true
        viewModelScope.launch {
            var ok = 0
            var failed = 0
            try {
                val list = ruleSets.ifEmpty { repo.ruleSets().also { ruleSets = it } }
                val targets = list.filter { it.vehicleType.equals("HTTP", ignoreCase = true) }
                if (targets.isEmpty()) {
                    toast("没有可在线更新的规则集（本地/内联规则集无需更新）")
                    return@launch
                }
                targets.forEach { ruleSetTasks[it.name] = HxTask(running = true) }
                for (chunk in targets.chunked(3)) {
                    coroutineScope {
                        chunk.map { item ->
                            async {
                                try {
                                    repo.refreshRuleSet(item.name)
                                    ruleSetTasks[item.name] = HxTask(ok = true, message = "已更新")
                                    ok++
                                } catch (cancel: CancellationException) {
                                    throw cancel
                                } catch (error: Exception) {
                                    ruleSetTasks[item.name] = HxTask(ok = false, message = errorText(error, "更新失败"))
                                    failed++
                                }
                            }
                        }.awaitAll()
                    }
                }
                ruleSets = try { repo.ruleSets() } catch (cancel: CancellationException) { throw cancel } catch (_: Exception) { ruleSets }
                toast(if (failed == 0) "全部 $ok 个规则集已更新" else "已更新 $ok 个，$failed 个失败")
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                toast(errorText(error, "规则集更新失败"))
            } finally {
                ruleSetsUpdatingAll = false
            }
        }
    }

    fun loadProviders() {
        if (!state.running) return
        viewModelScope.launch {
            try {
                providers = repo.providers()
                lastProviderRefreshAt = SystemClock.elapsedRealtime()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                toast(errorText(error, "订阅读取失败"))
            }
        }
    }

    fun updateProvider(name: String) {
        if (providerTasks[name]?.running == true || !state.running) return
        providerTasks[name] = HxTask(running = true)
        viewModelScope.launch {
            try {
                val fresh = repo.refreshProvider(name)
                if (fresh != null) providers = providers.map { if (it.name == name) fresh else it }
                providerTasks[name] = HxTask(ok = true, message = "已更新")
                refreshNow()
            } catch (cancel: CancellationException) {
                providerTasks.remove(name)
                throw cancel
            } catch (error: Exception) {
                providerTasks[name] = HxTask(ok = false, message = errorText(error, "更新失败"))
            }
        }
    }

    fun updateAllProviders() {
        if (providersUpdatingAll || !state.running) return
        providersUpdatingAll = true
        viewModelScope.launch {
            var ok = 0
            var failed = 0
            try {
                val targets = providers.ifEmpty { repo.providers() }
                if (targets.isEmpty()) {
                    toast("当前配置没有在线订阅（proxy-providers）")
                    return@launch
                }
                targets.forEach { providerTasks[it.name] = HxTask(running = true) }
                for (chunk in targets.chunked(3)) {
                    coroutineScope {
                        chunk.map { item ->
                            async {
                                try {
                                    repo.refreshProvider(item.name)
                                    providerTasks[item.name] = HxTask(ok = true, message = "已更新")
                                    ok++
                                } catch (cancel: CancellationException) {
                                    throw cancel
                                } catch (error: Exception) {
                                    providerTasks[item.name] = HxTask(ok = false, message = errorText(error, "更新失败"))
                                    failed++
                                }
                            }
                        }.awaitAll()
                    }
                }
                providers = try { repo.providers() } catch (cancel: CancellationException) { throw cancel } catch (_: Exception) { providers }
                lastProviderRefreshAt = SystemClock.elapsedRealtime()
                toast(if (failed == 0) "全部 $ok 个订阅已更新" else "已更新 $ok 个，$failed 个失败")
                refreshNow()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                toast(errorText(error, "订阅更新失败"))
            } finally {
                providersUpdatingAll = false
            }
        }
    }

    /** Called only after the real save succeeds. Safe to leave the config page immediately. */
    fun applyConfigChange(savedMessage: String) {
        if (operation == HxRunOp.Stop || (operation == null && !state.running)) {
            markConfigApplyPending()
            configApplyQueued = false
            refreshConfigApplyMessage()
            toast("$savedMessage，下次启动生效")
            return
        }
        queueConfigApply()
    }
}
