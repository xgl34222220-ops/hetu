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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

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

    /** A tools page another tab asked to open (e.g. 首页 › 打开配置管理); the tools tab consumes it once. */
    var toolsRequest by mutableStateOf<io.github.xgl34222220.hetu.tools.ToolsEntry?>(null)

    /** Opens [entry] inside 工具, so every feature has one page wherever it is reached from. */
    fun openTools(entry: io.github.xgl34222220.hetu.tools.ToolsEntry) {
        toolsRequest = entry
        tab = HxTab.Tools
    }

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
    /** Groups restored from the last complete snapshot; display only until a fresh read replaces them. */
    private var restoredGroups: List<ProxyGroupUi>? = null
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
    var operation by mutableStateOf<HxRunOp?>(null)
        private set
    var operationText by mutableStateOf("")
        private set
    var startupError by mutableStateOf<String?>(null)
    var refreshing by mutableStateOf(false)
        private set
    var loadedOnce by mutableStateOf(false)
        private set
    var coreVersion by mutableStateOf("")
        private set
    var settingsRevision by mutableIntStateOf(0)
        private set
    /** Configuration/API identity for content reloads; physical handovers retain these lists. */
    var contentRevision by mutableLongStateOf(0L)
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
    val resourceSamples = androidx.compose.runtime.mutableStateListOf<HomeResourceSample>()
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
    private var lastProcessTicks = 0L
    private var lastSystemTicks = 0L
    private var lastLocalTxBytes = -1L
    private var lastLocalRxBytes = -1L
    private var lastLocalAt = 0L
    private var lastProviderRefreshAt = 0L
    private val coldStartAt = SystemClock.elapsedRealtime()
    private val measuredAt = HashMap<String, Long>()

    private data class RuntimeRequest(val generation: Long)
    private var runtimeRequestGeneration = 0L
    private var requestSequence = 0L
    private var runtimeRequestSettings = requestSettings()
    private var runtimeContentSettings = contentSettings(runtimeRequestSettings)
    private val nodeProbeOwners = HashMap<String, MutableSet<Long>>()
    private val latestNodeProbe = HashMap<String, Long>()
    private val groupProbeOwners = HashMap<String, Long>()
    private val selectionOwners = HashMap<String, Long>()
    private var allProbeOwner: Long? = null
    private var siteProbeOwner: Long? = null
    private var latestProviderRead = 0L
    private var latestSiteRead = 0L
    private var latestRulesRead = 0L
    private var latestRuleSetsRead = 0L
    private val providerTaskOwners = HashMap<String, Long>()
    private var providersUpdateAllOwner: Long? = null
    private val ruleSetTaskOwners = HashMap<String, Long>()
    private var ruleSetsUpdateAllOwner: Long? = null

    /** Ordinary polling must not invalidate a probe; a different core/API/config must. */
    private fun requestSettings(): Map<String, Any?> {
        val snapshot = prefs.all
        val core = ProxyRuntimeProfile.Core.from(snapshot["proxyBaseCore"] as? String ?: "mihomo")
        val configKey = "proxySelectedConfig.${core.id}"
        val settings = snapshot.filterKeys { key ->
            key in setOf("proxyBaseCore", "proxyBaseMode", "proxyCustomApiEnabled", "proxyCustomApiHost",
            "proxyCustomApiPort", "proxyCustomApiSecret", "proxyControllerPort", "proxyControllerSecret",
            "proxyCustomDelayUrlEnabled", "proxyCustomDelayUrl", "proxyNetworkSessionId", "proxyNetworkEpoch") ||
                key == configKey || key.startsWith("proxyLatencyTarget")
        }.toMutableMap()
        // ConfigLibrary lazily persists this same default during its first read.
        // That bookkeeping must not invalidate the first ordinary controller snapshot.
        settings["proxyBaseCore"] = core.id
        settings[configKey] = (snapshot[configKey] as? String).orEmpty().ifBlank {
            if (core == ProxyRuntimeProfile.Core.MIHOMO || core == ProxyRuntimeProfile.Core.MIHOMO_SMART)
                ProxyConfigLibrary.BUNDLED_NAME else ""
        }
        return settings
    }

    /** Network handovers invalidate observations without discarding the current config's lists. */
    private fun contentSettings(settings: Map<String, Any?>): Map<String, Any?> = settings.filterKeys { key ->
        key in setOf("proxyBaseCore", "proxyBaseMode", "proxyCustomApiEnabled", "proxyCustomApiHost",
            "proxyCustomApiPort", "proxyCustomApiSecret", "proxyControllerPort", "proxyControllerSecret") ||
            key.startsWith("proxySelectedConfig.")
    }

    private fun invalidateRuntimeRequests(clearRuleContent: Boolean = true) {
        runtimeRequestGeneration++
        nodeProbeOwners.clear()
        latestNodeProbe.clear()
        groupProbeOwners.clear()
        testingNodes.clear()
        testingGroups.clear()
        selectionOwners.clear()
        pendingSelection.clear()
        allProbeOwner = null
        testingAll = false
        siteProbeOwner = null
        siteTesting = false
        providerTaskOwners.keys.forEach { name -> if (providerTasks[name]?.running == true) providerTasks.remove(name) }
        providerTaskOwners.clear()
        providersUpdateAllOwner = null
        providersUpdatingAll = false
        // Allow the replacement runtime to read immediately. Old finally blocks
        // must not clear busy/task state belonging to those replacement reads.
        latestRulesRead = ++requestSequence
        latestRuleSetsRead = ++requestSequence
        rulesLoading = false
        ruleSetsLoading = false
        ruleSetTaskOwners.keys.forEach { name -> if (ruleSetTasks[name]?.running == true) ruleSetTasks.remove(name) }
        ruleSetTaskOwners.clear()
        ruleSetsUpdateAllOwner = null
        ruleSetsUpdatingAll = false
        if (clearRuleContent) {
            contentRevision++
            rules = emptyList()
            ruleSets = emptyList()
        }
    }

    private fun syncRequestSettings() {
        val current = requestSettings()
        if (current != runtimeRequestSettings) {
            val content = contentSettings(current)
            val clearRuleContent = content != runtimeContentSettings
            runtimeContentSettings = content
            runtimeRequestSettings = current
            invalidateRuntimeRequests(clearRuleContent)
        }
    }

    private fun captureRuntimeRequest(): RuntimeRequest {
        syncRequestSettings()
        return RuntimeRequest(runtimeRequestGeneration)
    }

    private fun activeRuntimeRequest(): RuntimeRequest? {
        val request = captureRuntimeRequest()
        return request.takeIf { state.running && operation == null }
    }

    private fun currentRuntimeRequest(request: RuntimeRequest, requireRunning: Boolean = true): Boolean {
        syncRequestSettings()
        return request.generation == runtimeRequestGeneration &&
            (!requireRunning || (state.running && operation == null))
    }

    private fun holdNodeProbes(owner: Long, nodes: Collection<String>) {
        nodes.forEach { node ->
            nodeProbeOwners.getOrPut(node) { HashSet() }.add(owner)
            latestNodeProbe[node] = owner
            testingNodes[node] = true
        }
    }

    private fun releaseNodeProbes(owner: Long, nodes: Collection<String>) {
        nodes.forEach { node ->
            val owners = nodeProbeOwners[node] ?: return@forEach
            owners.remove(owner)
            if (owners.isEmpty()) {
                nodeProbeOwners.remove(node)
                testingNodes.remove(node)
            }
        }
    }

    private fun applyNodeProbe(request: RuntimeRequest, owner: Long, node: String, value: Long, stamp: Long) {
        if (currentRuntimeRequest(request) && (latestNodeProbe[node] ?: owner) <= owner) {
            latestNodeProbe[node] = owner
            delays[node] = value
            measuredAt[node] = stamp
        }
    }

    private fun beginProviderRead(): Long = (++requestSequence).also { latestProviderRead = it }
    private fun beginSiteRead(): Long = (++requestSequence).also { latestSiteRead = it }

    private var foregroundActive = false
    private var foregroundGeneration = 0L
    private var continuityAttachedForeground = -1L

    fun onForeground() {
        reloadAppearance()
        if (pollJob?.isActive == true) return
        foregroundActive = true
        foregroundGeneration++
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
                if (operation == null) {
                    refreshNow()
                    maybeVerifyEgress()
                }
                delay(if (tab == HxTab.Home || tab == HxTab.Panel) 2_000L else 4_000L)
            }
        }
        ProxyStatusNotificationService.refresh(app)
    }

    fun onBackground() {
        foregroundActive = false
        foregroundGeneration++
        pollJob?.cancel()
        pollJob = null
    }

    /* ---------------- exit (Internet) verification ---------------- */
    /** True once a generate_204 answered through this runtime's policy listener. */
    var egressVerified by mutableStateOf(io.github.xgl34222220.hetu.home.HomeEgress.verified(prefs))
        private set
    private var egressCheck: Job? = null
    private var lastEgressAttemptAt = 0L

    /**
     * Runs the cheap exit check automatically: right after a start settles and
     * whenever the app is open with a running core that is not yet verified for
     * this runtime (or the last success is over ten minutes old). Never blocks a
     * poll; a failed attempt waits 30 s before the next try.
     */
    private fun maybeVerifyEgress() {
        egressVerified = io.github.xgl34222220.hetu.home.HomeEgress.verified(prefs)
        if (!state.running || operation != null || egressCheck?.isActive == true) return
        if (!io.github.xgl34222220.hetu.home.HomeEgress.due(prefs, System.currentTimeMillis())) return
        val now = SystemClock.elapsedRealtime()
        if (lastEgressAttemptAt > 0L && now - lastEgressAttemptAt in 0L until 30_000L) return
        val request = activeRuntimeRequest() ?: return
        lastEgressAttemptAt = now
        egressCheck = viewModelScope.launch {
            try { repo.verifyEgress() }
            catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { }
            if (currentRuntimeRequest(request, requireRunning = false))
                egressVerified = io.github.xgl34222220.hetu.home.HomeEgress.verified(prefs)
        }
    }

    private fun initialState(): ProxyComposeState {
        val profile = ProxyRuntimeProfile.load(prefs)
        val cachedConfig = prefs.getString("proxySelectedConfig.${profile.core.id}", "").orEmpty().ifBlank { "尚未选择配置" }
        val running = prefs.getBoolean("proxyUiLastRunning", prefs.getBoolean("proxyRootWanted", false))
        val config = prefs.getString("proxyUiLastConfig", cachedConfig) ?: cachedConfig
        // Draw the last complete strategy cards at once instead of ~2 s of placeholders.
        // panelReady stays false, so nothing here can confirm a selection or count as data.
        val restored = if (running && prefs.getString(StrategySnapshotCache.PREF_KEY, null) == config)
            StrategySnapshotCache.read(app, config) else emptyList()
        if (restored.isNotEmpty()) restoredGroups = restored
        return ProxyComposeState(
            running = running,
            core = profile.core.label,
            mode = profile.mode.label,
            ipv6 = profile.ipv6.id,
            autoOverwrite = profile.autoOverwrite,
            config = config,
            groups = restored,
        )
    }

    /** True while the cards still show the restored list rather than a fresh controller read. */
    private fun showingRestoredGroups(): Boolean {
        val restored = restoredGroups ?: return false
        if (state.groups === restored) return true
        restoredGroups = null
        return false
    }

    private fun refuseRestoredGroups(): Boolean {
        if (!showingRestoredGroups()) return false
        toast("正在读取核心最新状态，请稍候")
        return true
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
        wanOrganization = prefs.getString("proxyUiLastWanOrganization", "—") ?: "—",
        wanIpType = prefs.getString("proxyUiLastWanIpType", "—") ?: "—",
        wanTimezone = prefs.getString("proxyUiLastWanTimezone", "—") ?: "—",
        wanCoordinates = prefs.getString("proxyUiLastWanCoordinates", "—") ?: "—",
        cpuAffinity = prefs.getString("proxyUiLastCpuAffinity", "—") ?: "—",
        currentCpu = prefs.getInt("proxyUiLastCurrentCpu", -1),
        wanState = "stale",
    )

    /** Returns the fresh, unmerged readback; cached groups cannot acknowledge a selection. */
    suspend fun refreshNow(): ProxyComposeState? {
        val refreshContext = kotlin.coroutines.coroutineContext
        val observedForeground = foregroundGeneration
        var expectedState = state
        var expectedRequest = captureRuntimeRequest()
        val operationBefore = operation
        fun superseded(): Boolean {
            refreshContext.ensureActive()
            return state !== expectedState || operation != operationBefore ||
                !currentRuntimeRequest(expectedRequest, requireRunning = false)
        }
        try {
            val startedAt = SystemClock.elapsedRealtime()
            // Cold open: the restored cards are already on the first frame. Overlap
            // the Root status script with a display-only controller read so the
            // cards show the live list as soon as the controller answers. They stay
            // non-authoritative (panelReady false, actions refused) until the
            // confirmed read below replaces them.
            val preview = if (showingRestoredGroups()) viewModelScope.launch {
                val live = try { repo.previewGroups() } catch (cancel: CancellationException) { throw cancel } catch (_: Exception) { emptyList() }
                if (live.isNotEmpty() && showingRestoredGroups() && state === expectedState && operation == operationBefore) {
                    restoredGroups = live
                    state = state.copy(groups = live)
                    expectedState = state
                }
            } else null
            val next = try { repo.state() } finally { preview?.cancel() }
            // A later stop/config/state update owns the UI. The next fresh poll can still apply.
            if (superseded()) return null
            // The existing worker status read supplies authority. A cached
            // wanted/running hint alone must never start an Android guardian.
            if (operationBefore == null && foregroundActive && foregroundGeneration == observedForeground &&
                continuityAttachedForeground != observedForeground &&
                controller.resumeContinuityFromForeground(next)) {
                continuityAttachedForeground = observedForeground
            }
            if (next.running != state.running ||
                (next.corePid > 0 && state.corePid > 0 && next.corePid != state.corePid)) {
                invalidateRuntimeRequests()
                expectedRequest = captureRuntimeRequest()
            }
            val now = SystemClock.elapsedRealtime()
            val controllerSampleValid = next.panelReady && !next.controllerReadFailed
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
            if (state.running && !next.running && prefs.getBoolean("proxyRootWanted", false) && now - coldStartAt < 2_500L) return null

            if (controllerSampleValid && lastAt > 0L && now > lastAt && next.uploadTotal >= lastUp && next.downloadTotal >= lastDown) {
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
            if (controllerSampleValid) syncCoreLatencyResults(next.groups, delays, measuredAt, startedAt, testingNodes.keys.toSet())

            // Publish the current controller observation before optional /proc,
            // provider and version reads. A slow auxiliary response must not keep
            // the first screen on a cached state or hide its current controls.
            // This publication becomes this request's expected state: a stop,
            // configuration change or newer refresh still revokes every later
            // auxiliary write through the existing superseded checks below.
            if (superseded()) return null
            val displayed = next.copy(continuityRootObserved = false, continuityAutomationOnly = false,
                continuityObservationTicket = -1L, continuityObservationSession = "",
                continuityObservationNetworkEpoch = 0L)
            state = if (next.running && !next.panelReady) {
                displayed.copy(groups = state.groups, connections = state.connections,
                    downloadTotal = state.downloadTotal, uploadTotal = state.uploadTotal,
                    memoryBytes = state.memoryBytes, trafficMode = state.trafficMode)
            } else displayed
            expectedState = state
            loadedOnce = true
            if (next.running) startupError = null

            var sampleValid = next.running
            val sampled = if (next.running) {
                try { inspector.sample() } catch (cancel: CancellationException) { throw cancel } catch (_: Exception) { sampleValid = false; runtime }
            } else ProxyRuntimeSnapshot()
            if (superseded()) return null
            val sameProcess = sampled.pid > 0 && sampled.pid == runtime.pid
            val cpuAvailable = sampleValid && sameProcess && lastSystemTicks > 0L && sampled.systemTicks > lastSystemTicks && sampled.processTicks >= lastProcessTicks
            if (cpuAvailable) {
                val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
                cpuPercent = ((sampled.processTicks - lastProcessTicks).toDouble() /
                    (sampled.systemTicks - lastSystemTicks).toDouble() * 100.0 * cores).toFloat().coerceIn(0f, 100f)
            } else if (!next.running) cpuPercent = 0f
            if (sampled.pid != runtime.pid && sampled.pid > 0) resourceSamples.clear()
            // The inspector caches /proc for 8 seconds. Reused snapshots are not
            // new measurements and must not manufacture dropouts or flat samples.
            if (resourceSamples.isEmpty() || !sampleValid || sampled.pid != runtime.pid || sampled.systemTicks != lastSystemTicks) {
                resourceSamples.add(HomeResourceSample(
                    cpuPercent = if (cpuAvailable) cpuPercent else null,
                    memoryBytes = sampled.rssBytes.takeIf { sampleValid && it > 0L },
                ))
                while (resourceSamples.size > 60) resourceSamples.removeAt(0)
            }
            lastProcessTicks = sampled.processTicks
            lastSystemTicks = sampled.systemTicks
            runtime = sampled

            if (!next.running) {
                providers = emptyList()
                lastProviderRefreshAt = 0L
                coreVersion = ""
            } else if (!next.controllerReadFailed) {
                if (providers.isEmpty() || now - lastProviderRefreshAt > 30_000L) {
                    val request = captureRuntimeRequest()
                    val owner = beginProviderRead()
                    val fresh = try { repo.providers() } catch (cancel: CancellationException) { throw cancel } catch (_: Exception) { providers }
                    if (superseded()) return null
                    if (owner == latestProviderRead && currentRuntimeRequest(request, requireRunning = false)) {
                        lastProviderRefreshAt = now
                        if (fresh.isNotEmpty() || providers.isEmpty()) providers = fresh
                    }
                }
                if (coreVersion.isBlank()) {
                    val freshVersion = repo.coreVersion()
                    if (superseded()) return null
                    coreVersion = freshVersion
                }
            }

            if (superseded()) return null
            if (controllerSampleValid) {
                lastAt = now
                lastUp = next.uploadTotal
                lastDown = next.downloadTotal
                // Opt-in local traffic/connection history (「测速与 API」→ 历史采集).
                try { ProxyApiHistoryStore.record(app, upRate, downRate, next.connections) } catch (cancel: CancellationException) { throw cancel } catch (_: Exception) { }
                if (superseded()) return null
            }
            persistSnapshot(next, sampled)
            return next
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            // Polling errors are shown through state.message on the next good read.
            return null
        }
    }

    private fun persistSnapshot(next: ProxyComposeState, sampled: ProxyRuntimeSnapshot) {
        val completeGroups = next.running && next.panelReady && !next.controllerReadFailed && next.groups.isNotEmpty()
        if (completeGroups) StrategySnapshotCache.write(app, next.config, next.groups)
        prefs.edit()
            .also { if (completeGroups) it.putString(StrategySnapshotCache.PREF_KEY, next.config) }
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
            .putString("proxyUiLastWanOrganization", sampled.wanOrganization)
            .putString("proxyUiLastWanIpType", sampled.wanIpType)
            .putString("proxyUiLastWanTimezone", sampled.wanTimezone)
            .putString("proxyUiLastWanCoordinates", sampled.wanCoordinates)
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
                val request = activeRuntimeRequest()
                if (request != null) {
                    val providerOwner = beginProviderRead()
                    val siteOwner = beginSiteRead()
                    coroutineScope {
                        val p = async { try { repo.providers() } catch (cancel: CancellationException) { throw cancel } catch (_: Exception) { null } }
                        val s = async { try { repo.siteLatencies() } catch (cancel: CancellationException) { throw cancel } catch (_: Exception) { null } }
                        p.await()?.let {
                            if (providerOwner == latestProviderRead && currentRuntimeRequest(request)) {
                                providers = it
                                lastProviderRefreshAt = SystemClock.elapsedRealtime()
                            }
                        }
                        s.await()?.let {
                            if (currentRuntimeRequest(request) && siteOwner == latestSiteRead && it.isNotEmpty()) {
                                siteDelays = it
                                ProxyLatencyTargets.persistLast(prefs, it)
                            }
                        }
                    }
                }
            } finally {
                refreshing = false
            }
        }
    }

    /* ---------------- lifecycle actions ---------------- */

    var autoStartBusy by mutableStateOf(false)
        private set

    fun setAutoStart(enabled: Boolean) {
        if (autoStartBusy || operation != null) return
        autoStartBusy = true
        viewModelScope.launch {
            try { toast(controller.setAutoStart(enabled)) }
            catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) { toast(errorText(error, "开机自启设置失败")) }
            finally { autoStartBusy = false; bumpSettings() }
        }
    }

    fun toggle() {
        if (operation != null) return
        val stopping = state.running
        invalidateRuntimeRequests()
        operation = if (stopping) HxRunOp.Stop else HxRunOp.Start
        viewModelScope.launch {
            operationText = if (stopping) "正在停止…" else "正在启动…"
            try {
                if (stopping) controller.stop { operationText = it } else controller.start { operationText = it }
                state = controller.state()
                if (!stopping) toast("代理已启动")
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                val reason = errorText(error, "操作失败")
                // Configuration problems are known before Root is touched; report them at once.
                val configProblem = !stopping && (reason.contains("占位") || reason.contains("尚未选择配置") || reason.contains("仅所选应用代理"))
                operationText = "确认最终运行状态…"
                val recovered = if (configProblem || stopping) null else settleStart()
                if (recovered != null) {
                    state = recovered
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
                operation = null
                operationText = ""
                refreshNow()
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
        invalidateRuntimeRequests()
        operation = HxRunOp.Restart
        viewModelScope.launch {
            operationText = "正在重启…"
            ProxyRuntimeSettings.beginApply(prefs)
            try {
                controller.restart { operationText = it }
                prefs.edit().remove("proxyRootRuntimeRefreshPending").remove("proxyRootUpgradeError").apply()
                toast("已重启，设置已生效")
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                ProxyRuntimeSettings.recordFailure(prefs, error)
                toast(errorText(error, "重启失败"))
            } finally {
                operation = null
                operationText = ""
                settingsRevision++
                refreshNow()
            }
        }
    }

    fun reload() {
        if (operation != null || !state.running) return
        invalidateRuntimeRequests()
        operation = HxRunOp.Reload
        viewModelScope.launch {
            operationText = "正在重载配置…"
            try {
                toast(controller.reload())
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                toast(errorText(error, "重载失败"))
            } finally {
                operation = null
                operationText = ""
                refreshNow()
            }
        }
    }

    /**
     * Hot-reloads the running core for a config transaction and hands the result (or failure) back to
     * the caller, which decides whether to roll its source back. Same operation slot as [reload].
     */
    suspend fun reloadNow(): String {
        if (!state.running) throw java.io.IOException("代理未运行，无法热重载")
        if (operation != null) throw java.io.IOException("代理正在执行其他操作，请稍后重启代理应用配置")
        invalidateRuntimeRequests()
        operation = HxRunOp.Reload
        operationText = "正在重载配置…"
        try {
            return controller.reload()
        } finally {
            operation = null
            operationText = ""
            try { refreshNow() } catch (_: Exception) { }
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

    fun bumpSettings() { syncRequestSettings(); settingsRevision++ }

    /* ---------------- proxies ---------------- */

    fun select(group: String, node: String) {
        if (refuseRestoredGroups()) return
        val request = activeRuntimeRequest() ?: return
        if (pendingSelection.containsKey(group)) return
        val current = state.groups.firstOrNull { it.name == group } ?: return
        if (current.now == node || current.nodes.none { it.name == node }) return
        val ticket = repo.captureSelection()
        val owner = ++requestSequence
        selectionOwners[group] = owner
        pendingSelection[group] = node
        viewModelScope.launch {
            try {
                // A stale callback can outlive its screen or race with a stop/config refresh.
                if (!currentRuntimeRequest(request) || selectionOwners[group] != owner ||
                    state.groups.none { it.name == group && it.nodes.any { candidate -> candidate.name == node } }) return@launch
                repo.select(group, node, prefs.getBoolean("proxySelectorDisconnectOnSelect", false), ticket)
                // The already-sent PUT cannot be revoked, but must not revive a stopped UI.
                if (!currentRuntimeRequest(request) || selectionOwners[group] != owner) return@launch
                val fresh = refreshNow()
                if (!currentRuntimeRequest(request) || selectionOwners[group] != owner) return@launch
                val actual = fresh?.takeIf { it.running && it.panelReady }
                    ?.groups?.firstOrNull { it.name == group }?.now
                check(actual == node) { "核心尚未确认所选节点，当前为 ${actual.orEmpty().ifBlank { "未知" }}" }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                if (currentRuntimeRequest(request) && selectionOwners[group] == owner)
                    toast("切换失败：" + errorText(error, "未知错误"))
            } finally {
                if (selectionOwners[group] == owner) {
                    selectionOwners.remove(group)
                    pendingSelection.remove(group)
                }
            }
        }
    }

    private suspend fun probe(node: String, request: RuntimeRequest, owner: Long) {
        try {
            applyNodeProbe(request, owner, node, repo.delay(node), SystemClock.elapsedRealtime())
        } catch (failure: MihomoControllerClient.DelayFailure) {
            applyNodeProbe(request, owner, node, if (failure.timedOut) -1L else -2L, SystemClock.elapsedRealtime())
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            // A failed controller request is not a node timeout. Keep the last
            // measurement, but explain why the real spinner ended without a result.
            if (currentRuntimeRequest(request) && latestNodeProbe[node] == owner)
                toast(errorText(error, "节点测速请求失败"))
        }
    }

    fun testNode(node: String) {
        if (refuseRestoredGroups()) return
        val request = activeRuntimeRequest() ?: return
        if (testingNodes[node] == true) return
        val owner = ++requestSequence
        holdNodeProbes(owner, listOf(node))
        viewModelScope.launch {
            try { probe(node, request, owner) } finally { releaseNodeProbes(owner, listOf(node)) }
        }
    }

    fun testGroup(group: ProxyGroupUi) {
        if (refuseRestoredGroups()) return
        val request = activeRuntimeRequest() ?: return
        if (testingGroups[group.name] == true) return
        val targets = group.nodes.map { it.name }.filter { it.uppercase() !in setOf("DIRECT", "REJECT", "REJECT-DROP", "PASS", "COMPATIBLE") }.distinct()
        if (targets.isEmpty()) return
        val owner = ++requestSequence
        groupProbeOwners[group.name] = owner
        testingGroups[group.name] = true
        holdNodeProbes(owner, targets)
        viewModelScope.launch {
            // Leaf probes of automatic groups land one by one: show each reading and stop
            // that node's spinner at once instead of holding all of them for the slowest.
            val landed = Channel<Pair<String, Long>>(Channel.UNLIMITED)
            val progress = launch {
                for ((node, value) in landed) {
                    applyNodeProbe(request, owner, node, value, SystemClock.elapsedRealtime())
                    releaseNodeProbes(owner, listOf(node))
                }
            }
            try {
                // Selector groups retain the parallel core endpoint. Unpinned automatic groups
                // use it too and stream each member as the core records it; a pinned one keeps
                // bounded leaf probes because that endpoint silently clears its fixed choice.
                val result = repo.groupDelay(group, targets) { node, value -> landed.trySend(node to value) }
                if (targets.none { it in result } && currentRuntimeRequest(request) && groupProbeOwners[group.name] == owner)
                    toast("测速未取得有效结果，已保留上次读数，请检查控制接口后重试")
                val stamp = SystemClock.elapsedRealtime()
                targets.forEach { node ->
                    // Missing/invalid entries are not authoritative timeouts. Keep old readings.
                    result[node]?.let { delay ->
                        applyNodeProbe(request, owner, node, delay, stamp)
                    }
                }
            } catch (partial: IncompleteLatencyProbe) {
                val stamp = SystemClock.elapsedRealtime()
                partial.results.forEach { (node, value) -> applyNodeProbe(request, owner, node, value, stamp) }
                if (currentRuntimeRequest(request)) toast(partial.message.orEmpty())
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                if (currentRuntimeRequest(request)) toast(errorText(error, "策略组测速失败"))
            } finally {
                landed.close()
                progress.cancel()
                releaseNodeProbes(owner, targets)
                if (groupProbeOwners[group.name] == owner) {
                    groupProbeOwners.remove(group.name)
                    testingGroups.remove(group.name)
                }
            }
        }
    }

    fun testAll() {
        if (refuseRestoredGroups()) return
        val request = activeRuntimeRequest() ?: return
        if (testingAll) return
        val owner = ++requestSequence
        val targets = (state.groups.flatMap { it.nodes.map { node -> node.name } } +
            providers.flatMap { it.nodes } + delays.keys).distinct()
            .filter { it.uppercase() !in setOf("DIRECT", "REJECT", "REJECT-DROP", "PASS", "COMPATIBLE") }
        allProbeOwner = owner
        holdNodeProbes(owner, targets)
        testingAll = true
        viewModelScope.launch {
            // Same as a group probe: every reading appears as soon as its node answers.
            val landed = Channel<Pair<String, Long>>(Channel.UNLIMITED)
            val progress = launch {
                for ((node, value) in landed) {
                    applyNodeProbe(request, owner, node, value, SystemClock.elapsedRealtime())
                    releaseNodeProbes(owner, listOf(node))
                }
            }
            try {
                val result = repo.globalDelay { node, value -> landed.trySend(node to value) }
                val stamp = SystemClock.elapsedRealtime()
                result.forEach { (node, value) -> applyNodeProbe(request, owner, node, value, stamp) }
                val ok = result.values.count { it > 0L }
                if (currentRuntimeRequest(request)) {
                    if (result.isEmpty()) toast("测速未取得有效结果，已保留上次读数，请检查控制接口后重试")
                    else toast("测速完成：$ok / ${result.size} 个节点可用")
                }
            } catch (partial: IncompleteLatencyProbe) {
                val stamp = SystemClock.elapsedRealtime()
                partial.results.forEach { (node, value) -> applyNodeProbe(request, owner, node, value, stamp) }
                if (currentRuntimeRequest(request)) toast(partial.message.orEmpty())
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                if (currentRuntimeRequest(request)) toast(errorText(error, "测速失败"))
            } finally {
                landed.close()
                progress.cancel()
                releaseNodeProbes(owner, targets)
                if (allProbeOwner == owner) { allProbeOwner = null; testingAll = false }
            }
        }
    }

    private suspend fun measureSitesQuietly() {
        val request = activeRuntimeRequest() ?: return
        if (siteTesting) return
        val owner = beginSiteRead()
        siteProbeOwner = owner
        siteTesting = true
        try {
            val measured = repo.siteLatencies()
            if (currentRuntimeRequest(request) && owner == latestSiteRead && measured.isNotEmpty()) {
                siteDelays = measured
                ProxyLatencyTargets.persistLast(prefs, measured)
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
        } finally {
            if (siteProbeOwner == owner) { siteProbeOwner = null; siteTesting = false }
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
        val request = activeRuntimeRequest() ?: return
        if (siteTesting) return
        val owner = beginSiteRead()
        siteProbeOwner = owner
        siteTesting = true
        viewModelScope.launch {
            try {
                val measured = repo.siteLatencies()
                if (currentRuntimeRequest(request) && owner == latestSiteRead && measured.isNotEmpty()) {
                    siteDelays = measured
                    ProxyLatencyTargets.persistLast(prefs, measured)
                }
                if (currentRuntimeRequest(request) && owner == latestSiteRead && measured.values.none { it > 0L }) toast("站点测速失败，请检查网络")
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                if (currentRuntimeRequest(request) && owner == latestSiteRead) toast(errorText(error, "测速失败"))
            } finally {
                if (siteProbeOwner == owner) { siteProbeOwner = null; siteTesting = false }
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
        val request = activeRuntimeRequest() ?: return
        if (rulesLoading) return
        val owner = (++requestSequence).also { latestRulesRead = it }
        rulesLoading = true
        viewModelScope.launch {
            try {
                val fresh = controller.rules()
                if (currentRuntimeRequest(request) && latestRulesRead == owner) rules = fresh
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                if (currentRuntimeRequest(request) && latestRulesRead == owner) toast(errorText(error, "规则读取失败"))
            } finally {
                if (latestRulesRead == owner) rulesLoading = false
            }
        }
    }

    fun loadRuleSets() {
        val request = activeRuntimeRequest() ?: return
        if (ruleSetsLoading) return
        val owner = (++requestSequence).also { latestRuleSetsRead = it }
        ruleSetsLoading = true
        viewModelScope.launch {
            try {
                val fresh = repo.ruleSets()
                if (currentRuntimeRequest(request) && latestRuleSetsRead == owner) ruleSets = fresh
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                if (currentRuntimeRequest(request) && latestRuleSetsRead == owner) toast(errorText(error, "规则集读取失败"))
            } finally {
                if (latestRuleSetsRead == owner) ruleSetsLoading = false
            }
        }
    }

    fun updateRuleSet(name: String) {
        val request = activeRuntimeRequest() ?: return
        if (ruleSetTasks[name]?.running == true) return
        val owner = ++requestSequence
        ruleSetTaskOwners[name] = owner
        ruleSetTasks[name] = HxTask(running = true)
        viewModelScope.launch {
            try {
                val fresh = repo.refreshRuleSet(name)
                if (!currentRuntimeRequest(request) || ruleSetTaskOwners[name] != owner) return@launch
                // Earlier whole-list reads cannot overwrite this acknowledged mutation.
                latestRuleSetsRead = ++requestSequence
                ruleSetsLoading = false
                if (fresh != null) ruleSets = if (ruleSets.none { it.name == name }) ruleSets + fresh
                    else ruleSets.map { if (it.name == name) fresh else it }
                ruleSetTasks[name] = HxTask(ok = true, message = "已更新")
            } catch (cancel: CancellationException) {
                if (ruleSetTaskOwners[name] == owner) ruleSetTasks.remove(name)
                throw cancel
            } catch (error: Exception) {
                if (currentRuntimeRequest(request) && ruleSetTaskOwners[name] == owner)
                    ruleSetTasks[name] = HxTask(ok = false, message = errorText(error, "更新失败"))
            } finally {
                if (ruleSetTaskOwners[name] == owner) {
                    ruleSetTaskOwners.remove(name)
                    if (ruleSetTasks[name]?.running == true) ruleSetTasks.remove(name)
                }
            }
        }
    }

    /** Updates every remote (HTTP) rule-set, three at a time, with per-item status. */
    fun updateAllRuleSets() {
        val request = activeRuntimeRequest() ?: return
        if (ruleSetsUpdatingAll) return
        val owner = ++requestSequence
        ruleSetsUpdateAllOwner = owner
        ruleSetsUpdatingAll = true
        viewModelScope.launch {
            var ok = 0
            var failed = 0
            try {
                // Cached UI rows can belong to an earlier config on the same API.
                // Mutations always take their targets from the current controller.
                val initialReadOwner = (++requestSequence).also { latestRuleSetsRead = it }
                ruleSetsLoading = false
                val list = repo.ruleSets()
                if (!currentRuntimeRequest(request) || ruleSetsUpdateAllOwner != owner) return@launch
                if (initialReadOwner == latestRuleSetsRead) ruleSets = list
                val targets = list.filter { it.vehicleType.equals("HTTP", ignoreCase = true) }
                if (targets.isEmpty()) {
                    toast("没有可在线更新的规则集（本地/内联规则集无需更新）")
                    return@launch
                }
                targets.forEach { ruleSetTaskOwners[it.name] = owner; ruleSetTasks[it.name] = HxTask(running = true) }
                for (chunk in targets.chunked(3)) {
                    if (!currentRuntimeRequest(request) || ruleSetsUpdateAllOwner != owner) return@launch
                    coroutineScope {
                        chunk.map { item ->
                            async {
                                if (!currentRuntimeRequest(request) || ruleSetTaskOwners[item.name] != owner) return@async
                                try {
                                    repo.refreshRuleSet(item.name)
                                    if (currentRuntimeRequest(request) && ruleSetTaskOwners[item.name] == owner) {
                                        latestRuleSetsRead = ++requestSequence
                                        ruleSetsLoading = false
                                        ruleSetTasks[item.name] = HxTask(ok = true, message = "已更新")
                                        ok++
                                    }
                                } catch (cancel: CancellationException) {
                                    throw cancel
                                } catch (error: Exception) {
                                    if (currentRuntimeRequest(request) && ruleSetTaskOwners[item.name] == owner) {
                                        ruleSetTasks[item.name] = HxTask(ok = false, message = errorText(error, "更新失败"))
                                        failed++
                                    }
                                }
                            }
                        }.awaitAll()
                    }
                }
                if (!currentRuntimeRequest(request) || ruleSetsUpdateAllOwner != owner) return@launch
                val readOwner = (++requestSequence).also { latestRuleSetsRead = it }
                // This read supersedes earlier ordinary reads. Retire their busy
                // flag now; their finally blocks no longer own it. A later ordinary
                // read can take ownership and keep its own busy flag until done.
                ruleSetsLoading = false
                val fresh = try { repo.ruleSets() } catch (cancel: CancellationException) { throw cancel } catch (_: Exception) { ruleSets }
                if (!currentRuntimeRequest(request) || ruleSetsUpdateAllOwner != owner) return@launch
                if (readOwner == latestRuleSetsRead) ruleSets = fresh
                toast(if (failed == 0) "全部 $ok 个规则集已更新" else "已更新 $ok 个，$failed 个失败")
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                if (currentRuntimeRequest(request) && ruleSetsUpdateAllOwner == owner) toast(errorText(error, "规则集更新失败"))
            } finally {
                ruleSetTaskOwners.filterValues { it == owner }.keys.toList().forEach { name ->
                    ruleSetTaskOwners.remove(name)
                    if (ruleSetTasks[name]?.running == true) ruleSetTasks.remove(name)
                }
                if (ruleSetsUpdateAllOwner == owner) { ruleSetsUpdateAllOwner = null; ruleSetsUpdatingAll = false }
            }
        }
    }

    fun loadProviders() {
        val request = activeRuntimeRequest() ?: return
        val owner = beginProviderRead()
        viewModelScope.launch {
            try {
                val fresh = repo.providers()
                if (currentRuntimeRequest(request) && owner == latestProviderRead) {
                    providers = fresh
                    lastProviderRefreshAt = SystemClock.elapsedRealtime()
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                if (currentRuntimeRequest(request) && owner == latestProviderRead) toast(errorText(error, "订阅读取失败"))
            }
        }
    }

    fun updateProvider(name: String) {
        val request = activeRuntimeRequest() ?: return
        if (providerTasks[name]?.running == true) return
        val owner = ++requestSequence
        providerTaskOwners[name] = owner
        providerTasks[name] = HxTask(running = true)
        viewModelScope.launch {
            try {
                val fresh = repo.refreshProvider(name)
                if (!currentRuntimeRequest(request) || providerTaskOwners[name] != owner) return@launch
                beginProviderRead()
                if (fresh != null) providers = providers.map { if (it.name == name) fresh else it }
                providerTasks[name] = HxTask(ok = true, message = "已更新")
                refreshNow()
            } catch (cancel: CancellationException) {
                if (providerTaskOwners[name] == owner) providerTasks.remove(name)
                throw cancel
            } catch (error: Exception) {
                if (currentRuntimeRequest(request) && providerTaskOwners[name] == owner)
                    providerTasks[name] = HxTask(ok = false, message = errorText(error, "更新失败"))
            } finally {
                if (providerTaskOwners[name] == owner) {
                    providerTaskOwners.remove(name)
                    if (providerTasks[name]?.running == true) providerTasks.remove(name)
                }
            }
        }
    }

    fun updateAllProviders() {
        val request = activeRuntimeRequest() ?: return
        if (providersUpdatingAll) return
        val owner = ++requestSequence
        providersUpdateAllOwner = owner
        providersUpdatingAll = true
        viewModelScope.launch {
            var ok = 0
            var failed = 0
            try {
                val targets = providers.ifEmpty { repo.providers() }
                if (!currentRuntimeRequest(request) || providersUpdateAllOwner != owner) return@launch
                if (targets.isEmpty()) {
                    toast("当前配置没有在线订阅（proxy-providers）")
                    return@launch
                }
                targets.forEach { providerTaskOwners[it.name] = owner; providerTasks[it.name] = HxTask(running = true) }
                for (chunk in targets.chunked(3)) {
                    if (!currentRuntimeRequest(request) || providersUpdateAllOwner != owner) return@launch
                    coroutineScope {
                        chunk.map { item ->
                            async {
                                if (!currentRuntimeRequest(request) || providerTaskOwners[item.name] != owner) return@async
                                try {
                                    repo.refreshProvider(item.name)
                                    if (currentRuntimeRequest(request) && providerTaskOwners[item.name] == owner) {
                                        providerTasks[item.name] = HxTask(ok = true, message = "已更新")
                                        ok++
                                    }
                                } catch (cancel: CancellationException) {
                                    throw cancel
                                } catch (error: Exception) {
                                    if (currentRuntimeRequest(request) && providerTaskOwners[item.name] == owner) {
                                        providerTasks[item.name] = HxTask(ok = false, message = errorText(error, "更新失败"))
                                        failed++
                                    }
                                }
                            }
                        }.awaitAll()
                    }
                }
                if (!currentRuntimeRequest(request) || providersUpdateAllOwner != owner) return@launch
                val readOwner = beginProviderRead()
                val fresh = try { repo.providers() } catch (cancel: CancellationException) { throw cancel } catch (_: Exception) { providers }
                if (!currentRuntimeRequest(request) || providersUpdateAllOwner != owner) return@launch
                if (readOwner == latestProviderRead) {
                    providers = fresh
                    lastProviderRefreshAt = SystemClock.elapsedRealtime()
                }
                toast(if (failed == 0) "全部 $ok 个订阅已更新" else "已更新 $ok 个，$failed 个失败")
                refreshNow()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                if (currentRuntimeRequest(request) && providersUpdateAllOwner == owner) toast(errorText(error, "订阅更新失败"))
            } finally {
                providerTaskOwners.filterValues { it == owner }.keys.toList().forEach { name ->
                    providerTaskOwners.remove(name)
                    if (providerTasks[name]?.running == true) providerTasks.remove(name)
                }
                if (providersUpdateAllOwner == owner) { providersUpdateAllOwner = null; providersUpdatingAll = false }
            }
        }
    }

    /** Hot-reload the running core after a config change, or just report it's saved. */
    fun applyConfigChange(savedMessage: String) {
        if (!state.running) {
            toast("$savedMessage，下次启动生效")
            return
        }
        reload()
    }
}
