package io.github.xgl34222220.hetu

import io.github.xgl34222220.hetu.ui.CrystalSurface as Surface
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import androidx.activity.BackEventCompat
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import dev.chrisbanes.haze.materials.HazeMaterials
import dev.chrisbanes.haze.rememberHazeState
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.blur
import top.yukonga.miuix.kmp.blur.colorControls
import top.yukonga.miuix.kmp.blur.drawBackdrop
import top.yukonga.miuix.kmp.blur.highlight.Highlight
import top.yukonga.miuix.kmp.blur.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.squircle.squircleClip
import io.github.xgl34222220.hetu.ui.HetuGlassDock
import io.github.xgl34222220.hetu.ui.HetuTheme
import io.github.xgl34222220.hetu.ui.crystalMaterial
import io.github.xgl34222220.hetu.ui.crystalPageBackground
import io.github.xgl34222220.hetu.ui.DockItem
import io.github.xgl34222220.hetu.ui.LocalHetuTokens
import io.github.xgl34222220.hetu.ui.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onSizeChanged
import io.github.xgl34222220.hetu.ui.glass.liquidGlassLens
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Proxy workspace rebuilt around the compact reference-video language:
 * flat warm canvas, pale coral selected surfaces, grouped rows and a small floating dock.
 */
@OptIn(ExperimentalMaterial3Api::class)
class ReferenceProxyActivity : ComponentActivity() {
    private var resumeRevision by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            // Re-read theme preferences on resume without destroying the Compose tree.
            // The previous forced wrapper recreated RefProxyShell and briefly exposed
            // default/empty runtime state before the async refresh completed.
            val revision = resumeRevision
            HetuTheme { RefProxyShell(resumeRevision = revision) { finish() } }
        }
    }

    override fun onResume() {
        super.onResume()
        resumeRevision++
    }
}

private enum class RefProxyPage { Home, Panel, Tools, Settings }
internal data class RefSubscriptionCache(val used: Long = 0L, val total: Long = 0L, val count: Int = 0)
private enum class RefPanelTab(val label: String) {
    Groups("节点"), Overview("概览"), Subscriptions("订阅"), Connections("连接"), Rules("规则"), RuleSets("规则集")
}

@Composable
private fun RefProxyShell(resumeRevision: Int, onBack: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val controller = remember { ProxyComposeController(context) }
    val repo = remember { ProxyDashboardRepository(context) }
    val inspector = remember { ProxyRuntimeInspector(context) }
    val scope = rememberCoroutineScope()
    val haze = rememberHazeState()
    val liquidBackdrop = rememberLayerBackdrop()
    val prefs = remember { context.getSharedPreferences("hetu", 0) }
    var uiPrefsRevision by remember { mutableIntStateOf(0) }
    DisposableEffect(prefs) {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "enableBlur" || key == "liquidGlass" || key == "showPanelTab" || key == "latencyAutoRefreshSeconds") uiPrefsRevision++
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    uiPrefsRevision
    val blurEnabled = prefs.getBoolean("enableBlur", true)
    val liquidGlassEnabled = prefs.getBoolean("liquidGlass", true)
    val siteProbeInterval = prefs.getInt("latencyAutoRefreshSeconds", 0).takeIf { it == 30 || it == 60 } ?: 0
    val liquid = blurEnabled && liquidGlassEnabled && isRuntimeShaderSupported()
    val showPanelTab = prefs.getBoolean("showPanelTab", true)
    val startupProfile = remember { ProxyRuntimeProfile.load(prefs) }
    val startupConfig = remember(startupProfile.core) {
        prefs.getString("proxySelectedConfig.${startupProfile.core.id}", "").orEmpty().ifBlank { "尚未选择配置" }
    }

    var page by rememberSaveable { mutableStateOf(RefProxyPage.Home) }
    var panelTab by rememberSaveable { mutableStateOf(RefPanelTab.Groups) }
    var panelSearchRequest by rememberSaveable { mutableIntStateOf(0) }
    var panelDetailVisible by rememberSaveable { mutableStateOf(false) }
    val pageStateHolder = rememberSaveableStateHolder()
    BackHandler(enabled = page != RefProxyPage.Home && !panelDetailVisible) { page = RefProxyPage.Home }
    var state by remember {
        mutableStateOf(
            ProxyComposeState(
                running = prefs.getBoolean("proxyUiLastRunning", prefs.getBoolean("proxyRootWanted", false)),
                core = prefs.getString("proxyUiLastCore", startupProfile.core.label) ?: startupProfile.core.label,
                mode = prefs.getString("proxyUiLastMode", startupProfile.mode.label) ?: startupProfile.mode.label,
                ipv6 = startupProfile.ipv6.id,
                autoOverwrite = startupProfile.autoOverwrite,
                config = prefs.getString("proxyUiLastConfig", startupConfig) ?: startupConfig,
            ),
        )
    }
    val cachedWanViaCore = prefs.getBoolean("proxyUiLastWanViaCore", false)
    var runtime by remember { mutableStateOf(ProxyRuntimeSnapshot(
        running = prefs.getBoolean("proxyUiLastRunning", prefs.getBoolean("proxyRootWanted", false)),
        elapsedSeconds = prefs.getLong("proxyUiLastElapsed", 0L),
        rssBytes = prefs.getLong("proxyUiLastRss", 0L),
        lanAddress = prefs.getString("proxyUiLastLan", "—") ?: "—",
        lanInterface = prefs.getString("proxyUiLastLanIf", "—") ?: "—",
        wanAddress = if (cachedWanViaCore) prefs.getString("proxyUiLastWan", "—") ?: "—" else "—",
        wanCountryCode = if (cachedWanViaCore) prefs.getString("proxyUiLastWanCountry", "") ?: "" else "",
        wanRegion = if (cachedWanViaCore) prefs.getString("proxyUiLastWanRegion", "—") ?: "—" else "—",
        wanState = if (cachedWanViaCore) "stale" else "idle",
        wanCheckedAt = if (cachedWanViaCore) prefs.getLong("proxyUiLastWanCheckedAt", 0L) else 0L,
    )) }
    var providers by remember { mutableStateOf<List<DashboardProviderUi>>(emptyList()) }
    var cachedSubscription by remember { mutableStateOf(RefSubscriptionCache(
        used = prefs.getLong("proxyUiLastSubUsed", 0L),
        total = prefs.getLong("proxyUiLastSubTotal", 0L),
        count = prefs.getInt("proxyUiLastSubCount", 0),
    )) }
    var cachedConnectionCount by remember { mutableIntStateOf(prefs.getInt("proxyUiLastConnectionCount", 0)) }
    var lastProviderRefreshAt by remember { mutableLongStateOf(0L) }
    val coldStartAt = remember { SystemClock.elapsedRealtime() }
    var siteDelays by remember { mutableStateOf(mapOf(
        "Baidu" to prefs.getLong("proxyUiLastDelayBaidu", -2L),
        "Cloudflare" to prefs.getLong("proxyUiLastDelayCloudflare", -2L),
        "Google" to prefs.getLong("proxyUiLastDelayGoogle", -2L),
    ).filterValues { it != -2L }) }
    val delays = remember { mutableStateMapOf<String, Long>() }
    var cpuPercent by remember { mutableFloatStateOf(prefs.getFloat("proxyUiLastCpu", 0f)) }
    var lastProcessTicks by remember { mutableLongStateOf(0L) }
    var lastSystemTicks by remember { mutableLongStateOf(0L) }
    var upRate by remember { mutableLongStateOf(prefs.getLong("proxyUiLastUpRate", 0L)) }
    var downRate by remember { mutableLongStateOf(prefs.getLong("proxyUiLastDownRate", 0L)) }
    var lastUp by remember { mutableLongStateOf(0L) }
    var lastDown by remember { mutableLongStateOf(0L) }
    var lastAt by remember { mutableLongStateOf(0L) }
    var operation by remember { mutableStateOf("") }
    var homeRefreshing by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var testing by remember { mutableStateOf(false) }
    var logText by remember { mutableStateOf<String?>(null) }
    var logTitle by remember { mutableStateOf("运行日志") }
    var diagnosticLoading by remember { mutableStateOf(false) }
    var startupError by remember { mutableStateOf<String?>(null) }

    suspend fun refresh() {
        try {
            val next = repo.state()
            val now = SystemClock.elapsedRealtime()
            val transientColdGap = state.running && !next.running &&
                prefs.getBoolean("proxyRootWanted", false) && now - coldStartAt < 2500L
            if (transientColdGap) {
                message = next.message
                return
            }
            if (lastAt > 0L && now > lastAt && next.uploadTotal >= lastUp && next.downloadTotal >= lastDown) {
                val elapsed = now - lastAt
                upRate = ((next.uploadTotal - lastUp) * 1000L / elapsed).coerceAtLeast(0L)
                downRate = ((next.downloadTotal - lastDown) * 1000L / elapsed).coerceAtLeast(0L)
            }
            next.groups.flatMap { it.nodes }.forEach { node ->
                node.lastDelay?.takeIf { it > 0L }?.let { delays.putIfAbsent(node.name, it) }
            }
            val sampled = if (next.running) runCatching { inspector.sample() }.getOrDefault(runtime) else ProxyRuntimeSnapshot()
            if (next.running && lastSystemTicks > 0L && sampled.systemTicks > lastSystemTicks && sampled.processTicks >= lastProcessTicks) {
                val deltaProcess = sampled.processTicks - lastProcessTicks
                val deltaSystem = sampled.systemTicks - lastSystemTicks
                val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
                cpuPercent = ((deltaProcess.toDouble() / deltaSystem.toDouble()) * 100.0 * cores).toFloat().coerceIn(0f, 100f)
            } else if (!next.running) {
                cpuPercent = 0f
            }
            lastProcessTicks = sampled.processTicks
            lastSystemTicks = sampled.systemTicks
            runtime = sampled
            if (!next.running) {
                providers = emptyList()
                lastProviderRefreshAt = 0L
            } else if (providers.isEmpty() || now - lastProviderRefreshAt >= 30_000L) {
                val freshProviders = runCatching { repo.providers() }.getOrDefault(providers)
                if (freshProviders.isNotEmpty()) providers = freshProviders
                lastProviderRefreshAt = now
            }
            if (providers.isNotEmpty()) {
                val tracked = providers.filter { it.hasSubscriptionInfo && it.total > 0L }
                cachedSubscription = RefSubscriptionCache(tracked.sumOf { it.used }, tracked.sumOf { it.total }, providers.size)
            }
            if (next.panelReady) cachedConnectionCount = next.connections.size
            state = if (next.running && !next.panelReady) {
                next.copy(
                    groups = state.groups,
                    connections = state.connections,
                    downloadTotal = if (state.downloadTotal > 0L) state.downloadTotal else next.downloadTotal,
                    uploadTotal = if (state.uploadTotal > 0L) state.uploadTotal else next.uploadTotal,
                    memoryBytes = if (state.memoryBytes > 0L) state.memoryBytes else next.memoryBytes,
                )
            } else next
            prefs.edit()
                .putBoolean("proxyUiLastRunning", next.running)
                .putString("proxyUiLastCore", next.core)
                .putString("proxyUiLastMode", next.mode)
                .putString("proxyUiLastConfig", next.config)
                .putLong("proxyUiLastElapsed", sampled.elapsedSeconds)
                .putLong("proxyUiLastRss", sampled.rssBytes)
                .putString("proxyUiLastLan", sampled.lanAddress)
                .putString("proxyUiLastLanIf", sampled.lanInterface)
                .putString("proxyUiLastWan", sampled.wanAddress)
                .putString("proxyUiLastWanCountry", sampled.wanCountryCode)
                .putString("proxyUiLastWanRegion", sampled.wanRegion)
                .putBoolean("proxyUiLastWanViaCore", sampled.wanState == "success" || sampled.wanState == "stale")
                .putLong("proxyUiLastWanCheckedAt", sampled.wanCheckedAt)
                .putFloat("proxyUiLastCpu", cpuPercent)
                .putLong("proxyUiLastUpRate", upRate)
                .putLong("proxyUiLastDownRate", downRate)
                .putLong("proxyUiLastSubUsed", cachedSubscription.used)
                .putLong("proxyUiLastSubTotal", cachedSubscription.total)
                .putInt("proxyUiLastSubCount", cachedSubscription.count)
                .putInt("proxyUiLastConnectionCount", cachedConnectionCount)
                .putBoolean("proxyUiSnapshotValid", true)
                .apply()
            message = next.message
            if (next.panelReady) {
                lastAt = now
                lastUp = next.uploadTotal
                lastDown = next.downloadTotal
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            message = error.message ?: "状态读取失败"
        }
    }

    fun toggle() {
        if (operation.isNotBlank()) return
        scope.launch {
            operation = if (state.running) "正在停止…" else "正在启动…"
            try {
                if (state.running) controller.stop { operation = it } else controller.start { operation = it }
                operation = ""
                launch { delay(120); refresh() }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                val reason = error.message ?: "操作失败"
                message = reason
                if (!state.running) {
                    val diagnostics = runCatching { controller.diagnostics() }.getOrDefault("").trim()
                    startupError = buildString {
                        append(reason)
                        if (diagnostics.isNotBlank()) {
                            append("\n\n--- Root / Mihomo 诊断 ---\n")
                            append(diagnostics)
                        }
                    }
                }
            } finally {
                operation = ""
            }
        }
    }

    fun reload() {
        if (!state.running || operation.isNotBlank()) return
        scope.launch {
            operation = "正在重载…"
            try {
                message = controller.reload()
                operation = ""
                launch { delay(100); refresh() }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                message = error.message ?: "重载失败"
            } finally {
                operation = ""
            }
        }
    }

    fun restart() {
        if (!state.running || operation.isNotBlank()) return
        scope.launch {
            operation = "正在重启…"
            try {
                controller.restart { operation = it }
                prefs.edit().remove("proxyRootRuntimeRefreshPending").remove("proxyRootUpgradeError").apply()
                state = state.copy(runtimeSettingsPending = false, message = "")
                message = ""
                operation = ""
                launch {
                    delay(350)
                    refresh()
                    delay(1_100)
                    refresh()
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                message = UiFeedback.summary(error.message ?: "重启失败", true)
            } finally {
                operation = ""
            }
        }
    }

    suspend fun measureSitesInternal(reportError: Boolean) {
        if (!state.running || testing) return
        testing = true
        try {
            val measured = repo.siteLatencies()
            if (measured.isNotEmpty()) {
                siteDelays = measured
                prefs.edit()
                    .putLong("proxyUiLastDelayBaidu", measured["Baidu"] ?: -2L)
                    .putLong("proxyUiLastDelayCloudflare", measured["Cloudflare"] ?: -2L)
                    .putLong("proxyUiLastDelayGoogle", measured["Google"] ?: -2L)
                    .apply()
            }
            if (reportError && measured.values.none { it > 0L }) {
                message = "关键站点测速失败，请检查当前网络"
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            if (reportError) message = error.message ?: "测速失败"
        } finally {
            testing = false
        }
    }

    fun measureSites() {
        if (!state.running || testing) return
        scope.launch { measureSitesInternal(reportError = true) }
    }

    suspend fun refreshHomeAll() {
        refresh()
        if (!state.running) return
        coroutineScope {
            val quickTask = async {
                try { repo.quickDelay() }
                catch (cancel: CancellationException) { throw cancel }
                catch (_: Exception) { emptyMap() }
            }
            val siteTask = async {
                try { repo.siteLatencies() }
                catch (cancel: CancellationException) { throw cancel }
                catch (_: Exception) { emptyMap() }
            }
            val providerTask = async {
                try { repo.providers() }
                catch (cancel: CancellationException) { throw cancel }
                catch (_: Exception) { emptyList() }
            }
            val quick = quickTask.await()
            if (quick.isNotEmpty()) delays.putAll(quick)
            val sites = siteTask.await()
            if (sites.isNotEmpty()) {
                siteDelays = sites
                prefs.edit()
                    .putLong("proxyUiLastDelayBaidu", sites["Baidu"] ?: -2L)
                    .putLong("proxyUiLastDelayCloudflare", sites["Cloudflare"] ?: -2L)
                    .putLong("proxyUiLastDelayGoogle", sites["Google"] ?: -2L)
                    .apply()
            }
            val freshProviders = providerTask.await()
            if (freshProviders.isNotEmpty()) {
                providers = freshProviders
                lastProviderRefreshAt = SystemClock.elapsedRealtime()
                val tracked = freshProviders.filter { it.hasSubscriptionInfo && it.total > 0L }
                cachedSubscription = RefSubscriptionCache(
                    tracked.sumOf { it.used },
                    tracked.sumOf { it.total },
                    freshProviders.size,
                )
            }
        }
    }

    LaunchedEffect(lifecycleOwner, resumeRevision) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            // Resume polling only while this screen is visible. The foreground service
            // independently maintains the running proxy while Hetu is in the background.
            launch {
                delay(420)
                try { repo.ensureIcons() }
                catch (cancel: CancellationException) { throw cancel }
                catch (_: Exception) { }
            }
            // Paint the persisted snapshot before issuing Root/controller queries.
            delay(320)
            while (true) {
                if (operation.isBlank()) refresh()
                delay(3000)
            }
        }
    }

    // Rendering/returning to a page must never restart or redeploy a running core.
    // Pending runtime upgrades are surfaced by the existing manual restart action.

    // Auto site probes are opt-in. The default is off; manual refresh remains available.
    // This prevents Hetu itself from constantly adding probe traffic to Mihomo.
    LaunchedEffect(state.running, lifecycleOwner, siteProbeInterval) {
        if (!state.running) {
            siteDelays = emptyMap()
            return@LaunchedEffect
        }
        if (siteProbeInterval <= 0) return@LaunchedEffect
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                delay(siteProbeInterval * 1_000L)
                measureSitesInternal(reportError = false)
            }
        }
    }

    val dockPages = remember(showPanelTab) {
        if (showPanelTab) listOf(RefProxyPage.Home, RefProxyPage.Panel, RefProxyPage.Tools, RefProxyPage.Settings)
        else listOf(RefProxyPage.Home, RefProxyPage.Tools, RefProxyPage.Settings)
    }
    LaunchedEffect(showPanelTab) { if (!showPanelTab && page == RefProxyPage.Panel) page = RefProxyPage.Home }
    val dock = remember(showPanelTab) {
        dockPages.map { destination ->
            when (destination) {
                RefProxyPage.Home -> DockItem("首页", Icons.Rounded.Home, .94f)
                RefProxyPage.Panel -> DockItem("面板", Icons.Rounded.Link, .96f)
                RefProxyPage.Tools -> DockItem("工具", Icons.Rounded.GridView, .96f)
                RefProxyPage.Settings -> DockItem("设置", Icons.Rounded.Settings, .94f)
            }
        }
    }

    val shellBackground = LocalHetuTokens.current.pageBackground
    val layoutDensity = LocalDensity.current
    var measuredDockHeight by remember { mutableStateOf(0.dp) }
    val fallbackDockHeight = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 84.dp
    CompositionLocalProvider(LocalHetuDockHeight provides if (panelDetailVisible) 0.dp else measuredDockHeight.takeIf { it > 0.dp } ?: fallbackDockHeight) {
    Box(Modifier.fillMaxSize().background(shellBackground)) {
        Box(
            Modifier.fillMaxSize()
                .then(if (blurEnabled) Modifier.hazeSource(haze) else Modifier)
                .then(if (liquid) Modifier.layerBackdrop(liquidBackdrop) else Modifier),
        ) {
            // Match LuoShu's backdrop architecture: the full-screen page backdrop must live
            // INSIDE the layerBackdrop source. Keeping it only on the parent leaves transparent
            // pixels near the Home tail, which the RuntimeShader can stretch into a white strip.
            Box(Modifier.matchParentSize().crystalPageBackground())
            key(page) {
                pageStateHolder.SaveableStateProvider(page.name) {
                val pageEnter = remember { Animatable(0f) }
                LaunchedEffect(Unit) {
                    pageEnter.animateTo(
                        targetValue = 1f,
                        animationSpec = spring(dampingRatio = .86f, stiffness = 430f),
                    )
                }
                Box(
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            alpha = 1f
                            translationY = (1f - pageEnter.value) * 10.dp.toPx()
                        },
                ) {
            when (page) {
                RefProxyPage.Home -> PullToRefreshBox(
                    isRefreshing = homeRefreshing,
                    onRefresh = {
                        if (!homeRefreshing) scope.launch {
                            homeRefreshing = true
                            try {
                                refreshHomeAll()
                                if (message.isBlank()) message = "全部刷新完成"
                            } finally {
                                homeRefreshing = false
                            }
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                ) {
                    RefHome(
                    state = state,
                    runtime = runtime,
                    providers = providers,
                    cachedSubscription = cachedSubscription,
                    cachedConnections = cachedConnectionCount,
                    siteDelays = siteDelays,
                    upRate = upRate,
                    downRate = downRate,
                    cpuPercent = cpuPercent,
                    operation = operation,
                    message = message,
                    testing = testing,
                    hazeState = haze,
                    glassEnabled = blurEnabled && liquidGlassEnabled,
                    onRefresh = {
                        if (!homeRefreshing) scope.launch {
                            homeRefreshing = true
                            try { refresh() } finally { homeRefreshing = false }
                        }
                    },
                    onToggle = ::toggle,
                    onReload = ::reload,
                    onRestart = ::restart,
                    onDelay = ::measureSites,
                    diagnosticLoading = diagnosticLoading,
                    onLog = { scope.launch {
                        logTitle = "运行日志"
                        logText = runCatching { inspector.runtimeLog() }.getOrElse { it.message ?: "日志读取失败" }
                    } },
                    onConnections = {
                        panelTab = RefPanelTab.Connections
                        page = RefProxyPage.Panel
                    },
                    onSettings = { page = RefProxyPage.Settings },
                    onDiagnostics = {
                        if (!diagnosticLoading) scope.launch {
                            diagnosticLoading = true
                            try {
                                logTitle = "消息与网络诊断"
                                logText = controller.diagnostics()
                            } catch (cancel: CancellationException) {
                                throw cancel
                            } catch (error: Exception) {
                                message = error.message ?: "诊断读取失败"
                            } finally {
                                diagnosticLoading = false
                            }
                        }
                    },
                    onSubscription = {
                        panelTab = RefPanelTab.Subscriptions
                        page = RefProxyPage.Panel
                    },
                    )
                }
                RefProxyPage.Panel -> RefPanel(
                    state = state,
                    repo = repo,
                    delays = delays,
                    selectedTab = panelTab,
                    onSelectedTabChange = { panelTab = it },
                    searchRequest = panelSearchRequest,
                    hazeState = haze,
                    backdrop = liquidBackdrop.takeIf { liquid },
                    glassEnabled = blurEnabled && liquidGlassEnabled,
                    onRefreshState = { refresh() },
                    onBack = { page = RefProxyPage.Home },
                    onOpenSettings = { page = RefProxyPage.Settings },
                    onDetailVisibleChanged = { panelDetailVisible = it },
                )
                RefProxyPage.Tools -> RefTools(state) { logTitle = "运行日志"; logText = it }
                RefProxyPage.Settings -> RefSettings(state, operation, ::restart) { scope.launch { refresh() } }
            }
                }
                }
            }
        }
        if (!panelDetailVisible) {
            HetuGlassDock(
                items = dock,
                selected = dockPages.indexOf(page).coerceAtLeast(0),
                onSelect = { page = dockPages[it] },
                hazeState = haze,
                backdrop = liquidBackdrop.takeIf { liquid },
                modifier = Modifier.align(Alignment.BottomCenter).onSizeChanged {
                    measuredDockHeight = with(layoutDensity) { it.height.toDp() }
                },
            )
        }
    }

    } // measured dock composition local

    logText?.let { text ->
        RefInfoBottomSheet(
            title = logTitle,
            text = text,
            actionLabel = "关闭",
            onDismiss = { logText = null },
        )
    }
    startupError?.let { text ->
        RefInfoBottomSheet(
            title = "启动失败",
            text = text,
            actionLabel = "关闭",
            onDismiss = { startupError = null },
        )
    }
}

@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
private fun refHomeLiquidModifier(base: Modifier, hazeState: HazeState, glassEnabled: Boolean, shape: RoundedCornerShape): Modifier {
    return base.crystalMaterial(shape)
}

@Composable
internal fun RefHome(
    state: ProxyComposeState,
    runtime: ProxyRuntimeSnapshot,
    providers: List<DashboardProviderUi>,
    cachedSubscription: RefSubscriptionCache,
    cachedConnections: Int,
    siteDelays: Map<String, Long>,
    upRate: Long,
    downRate: Long,
    cpuPercent: Float,
    operation: String,
    message: String,
    testing: Boolean,
    hazeState: HazeState,
    glassEnabled: Boolean,
    onRefresh: () -> Unit,
    onToggle: () -> Unit,
    onReload: () -> Unit,
    onRestart: () -> Unit,
    onDelay: () -> Unit,
    onLog: () -> Unit,
    onSubscription: () -> Unit,
    diagnosticLoading: Boolean,
    onConnections: () -> Unit,
    onSettings: () -> Unit,
    onDiagnostics: () -> Unit,
) {
    val context = LocalContext.current
    val t = LocalHetuTokens.current
    val memory = runtime.rssBytes.takeIf { it > 0L } ?: state.memoryBytes
    val busy = operation.isNotBlank()
    val connections = if (state.panelReady) state.connections.size else cachedConnections

    LazyColumn(
        Modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = PaddingValues(
            start = 12.dp,
            top = 6.dp,
            end = 12.dp,
            bottom = hetuContentBottomPadding(),
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "home-title") {
            Box(
                Modifier.fillMaxWidth().height(48.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "河图",
                    color = t.textPrimary,
                    fontSize = 22.sp,
                    lineHeight = 28.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = (-.6).sp,
                )
            }
        }

        item(key = "home-status") {
            RefReferenceHero(
                state = state,
                runtime = runtime,
                busy = busy,
            )
        }

        item(key = "home-actions") {
            RefReferenceActionStrip(
                running = state.running,
                busy = busy,
                onToggle = onToggle,
                onReload = onReload,
                onRestart = onRestart,
            )
        }

        item(key = "home-shortcuts") {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                RefReferenceShortcut(
                    title = "WebUI",
                    subtitle = "Web 界面",
                    modifier = Modifier.weight(1f),
                    enabled = state.running,
                ) {
                    context.startActivity(Intent(context, ProxyLocalWebUiActivity::class.java))
                }
                RefReferenceShortcut(
                    title = "日志",
                    subtitle = "查看",
                    modifier = Modifier.weight(1f),
                    enabled = true,
                    onClick = onLog,
                )
            }
        }

        item(key = "home-latency") {
            RefLatencyPanel(
                baidu = siteDelays["Baidu"],
                cloudflare = siteDelays["Cloudflare"],
                google = siteDelays["Google"],
                testing = testing,
                onTune = onSettings,
                onClick = onDelay,
            )
        }

        item(key = "home-bento") {
            RefHomeBentoMatrix(
                runtime = runtime,
                connections = connections,
                up = upRate,
                down = downRate,
                providers = providers,
                cached = cachedSubscription,
                memory = memory,
                cpuPercent = cpuPercent,
                hazeState = hazeState,
                glassEnabled = glassEnabled,
                onSubscription = onSubscription,
            )
        }

        if (busy || message.isNotBlank()) {
            item(key = "home-feedback") {
                HetuTaskFeedback(
                    operation.ifBlank { message },
                    error = !busy && (
                        message.contains("失败") ||
                            message.contains("异常") ||
                            message.contains("error", true)
                        ),
                    busy = busy,
                )
            }
        }
    }
}

@Composable
private fun RefReferenceHero(
    state: ProxyComposeState,
    runtime: ProxyRuntimeSnapshot,
    busy: Boolean,
) {
    val t = LocalHetuTokens.current
    val shape = RoundedCornerShape(22.dp)
    val status = if (busy) "正在处理" else if (state.running) "运行中" else "已停止"
    val uptime = when {
        !state.running -> "等待启动"
        runtime.elapsedSeconds < 60L -> "少于 1 分钟"
        else -> refDuration(runtime.elapsedSeconds)
    }

    Box(
        Modifier
            .fillMaxWidth()
            .height(120.dp)
            .clip(shape)
            .background(t.heroBackground)
            .testTag("home-hero"),
    ) {
        Column(
            Modifier
                .align(Alignment.TopStart)
                .padding(start = 18.dp, top = 16.dp, end = 118.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(
                    Modifier
                        .size(9.dp)
                        .background(
                            if (state.running) HetuMicroCrystal.KleinBlue else t.textMuted,
                            CircleShape,
                        ),
                )
                Text(
                    status,
                    Modifier.testTag("home-run-state"),
                    color = if (state.running) HetuMicroCrystal.KleinBlue else t.textPrimary,
                    fontSize = 20.sp,
                    lineHeight = 25.sp,
                    fontWeight = FontWeight.ExtraBold,
                )
            }
            Text(
                uptime,
                color = t.textPrimary,
                fontSize = 13.sp,
                lineHeight = 18.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                if (state.running) "${state.core}  ·  ${state.mode}" else "Root  ·  Mihomo",
                color = t.textPrimary,
                fontSize = 12.5.sp,
                lineHeight = 17.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                state.config,
                color = t.textPrimary,
                fontSize = 12.sp,
                lineHeight = 17.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Canvas(
            Modifier
                .align(Alignment.BottomEnd)
                .offset(x = 34.dp, y = 28.dp)
                .size(116.dp),
        ) {
            val stroke = 10.dp.toPx()
            drawCircle(
                color = HetuMicroCrystal.KleinBlue,
                radius = size.minDimension / 2f - stroke / 2f,
                style = Stroke(width = stroke),
            )
            if (state.running && !busy) {
                val path = Path().apply {
                    moveTo(size.width * .28f, size.height * .52f)
                    lineTo(size.width * .43f, size.height * .67f)
                    lineTo(size.width * .73f, size.height * .34f)
                }
                drawPath(
                    path,
                    HetuMicroCrystal.KleinBlue,
                    style = Stroke(
                        width = 9.dp.toPx(),
                        cap = androidx.compose.ui.graphics.StrokeCap.Round,
                        join = androidx.compose.ui.graphics.StrokeJoin.Round,
                    ),
                )
            }
        }
    }
}

@Composable
private fun RefReferenceActionStrip(
    running: Boolean,
    busy: Boolean,
    onToggle: () -> Unit,
    onReload: () -> Unit,
    onRestart: () -> Unit,
) {
    val t = LocalHetuTokens.current
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = t.cardBackground,
        shadowElevation = 0.dp,
    ) {
        Row(
            Modifier.fillMaxWidth().height(52.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RefReferenceAction(
                label = "重载",
                color = HetuMicroCrystal.KleinBlue,
                enabled = running && !busy,
                modifier = Modifier.weight(1f),
                onClick = onReload,
            )
            Box(Modifier.width(1.dp).height(24.dp).background(t.outline.copy(alpha = .72f)))
            RefReferenceAction(
                label = if (busy) "请稍候" else if (running) "停止" else "启动",
                color = if (running) Color(0xFFB51F32) else HetuMicroCrystal.KleinBlue,
                enabled = !busy,
                modifier = Modifier.weight(1f),
                onClick = onToggle,
            )
            Box(Modifier.width(1.dp).height(24.dp).background(t.outline.copy(alpha = .72f)))
            RefReferenceAction(
                label = "重启",
                color = Color(0xFF8A651B),
                enabled = running && !busy,
                modifier = Modifier.weight(1f),
                onClick = onRestart,
            )
        }
    }
}

@Composable
private fun RefReferenceAction(
    label: String,
    color: Color,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier
            .fillMaxHeight()
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = color.copy(alpha = if (enabled) 1f else .35f),
            fontSize = 14.sp,
            lineHeight = 18.sp,
            fontWeight = FontWeight.ExtraBold,
        )
    }
}

@Composable
private fun RefReferenceShortcut(
    title: String,
    subtitle: String,
    modifier: Modifier,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val t = LocalHetuTokens.current
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(64.dp),
        shape = RoundedCornerShape(20.dp),
        color = t.cardBackground,
        shadowElevation = 0.dp,
    ) {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                title,
                color = t.textPrimary.copy(alpha = if (enabled) 1f else .45f),
                fontSize = 16.sp,
                lineHeight = 20.sp,
                fontWeight = FontWeight.ExtraBold,
            )
            Text(
                subtitle,
                color = t.textSecondary.copy(alpha = if (enabled) 1f else .45f),
                fontSize = 11.5.sp,
                lineHeight = 16.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun RefLatencyPanel(
    baidu: Long?,
    cloudflare: Long?,
    google: Long?,
    testing: Boolean,
    onTune: () -> Unit,
    onClick: () -> Unit,
) {
    val t = LocalHetuTokens.current
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = t.cardBackground,
        shadowElevation = 0.dp,
    ) {
        Column(
            Modifier.fillMaxWidth().height(88.dp).padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "延迟",
                    Modifier.weight(1f),
                    color = t.textPrimary,
                    fontSize = 16.sp,
                    lineHeight = 20.sp,
                    fontWeight = FontWeight.ExtraBold,
                )
                IconButton(onClick = onTune, modifier = Modifier.size(34.dp)) {
                    Icon(Icons.Rounded.Tune, "延迟设置", Modifier.size(18.dp), tint = t.textSecondary)
                }
                IconButton(onClick = onClick, enabled = !testing, modifier = Modifier.size(34.dp)) {
                    if (testing) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 1.8.dp)
                    } else {
                        Icon(Icons.Rounded.Refresh, "测速", Modifier.size(18.dp), tint = t.textSecondary)
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth().height(42.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RefLatencyColumn("Baidu", baidu, testing, Modifier.weight(1f))
                Box(Modifier.width(1.dp).height(34.dp).background(t.outline.copy(alpha = .70f)))
                RefLatencyColumn("Cloudflare", cloudflare, testing, Modifier.weight(1f))
                Box(Modifier.width(1.dp).height(34.dp).background(t.outline.copy(alpha = .70f)))
                RefLatencyColumn("Google", google, testing, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun RefLatencyColumn(
    label: String,
    value: Long?,
    testing: Boolean,
    modifier: Modifier,
) {
    val t = LocalHetuTokens.current
    Column(
        modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        Text(
            label,
            color = t.textSecondary,
            fontSize = 10.5.sp,
            lineHeight = 14.sp,
            fontWeight = FontWeight.Medium,
        )
        Text(
            if (testing) "..." else refDelay(value),
            color = HetuMicroCrystal.KleinBlue,
            fontSize = 16.sp,
            lineHeight = 20.sp,
            fontWeight = FontWeight.ExtraBold,
            maxLines = 1,
        )
    }
}

@Composable
private fun RefHomeBentoMatrix(
    runtime: ProxyRuntimeSnapshot, connections: Int, up: Long, down: Long,
    providers: List<DashboardProviderUi>, cached: RefSubscriptionCache,
    memory: Long, cpuPercent: Float, hazeState: HazeState, glassEnabled: Boolean,
    onSubscription: () -> Unit,
) {
    val tracked = providers.filter { it.hasSubscriptionInfo && it.total > 0L }
    val total = tracked.sumOf { it.total }
    WorkspaceBento(runtime, connections, up, down,
        if (total > 0L) tracked.sumOf { it.used } else cached.used,
        if (total > 0L) total else cached.total,
        if (providers.isNotEmpty()) providers.size else cached.count,
        memory, cpuPercent, onSubscription)
}

internal fun countryEmoji(code: String): String {
    val upper = code.trim().uppercase(java.util.Locale.ROOT)
    if (upper.length != 2 || upper.any { it !in 'A'..'Z' }) return "🌐"
    val first = Character.toChars(0x1F1E6 + (upper[0] - 'A'))
    val second = Character.toChars(0x1F1E6 + (upper[1] - 'A'))
    return String(first) + String(second)
}

@Composable
private fun RefActionText(text: String, enabled: Boolean, onClick: () -> Unit, modifier: Modifier,
    color: Color = LocalHetuTokens.current.textPrimary, icon: ImageVector? = null, danger: Boolean = false) {
    val t = LocalHetuTokens.current
    Surface(onClick = onClick, enabled = enabled, modifier = modifier.heightIn(min = 48.dp),
        color = if (danger) t.dangerContainer else t.controlBackground,
        shape = RoundedCornerShape(15.dp), shadowElevation = 0.dp) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            if (icon != null) { Icon(icon, null, Modifier.size(18.dp), tint = color); Spacer(Modifier.width(6.dp)) }
            Text(text, color = color, fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun RefMetricCard(title: String, value: String, icon: ImageVector, modifier: Modifier, onClick: (() -> Unit)? = null) {
    val t = LocalHetuTokens.current
    val clickable = if (onClick == null) modifier else modifier.clickable(onClick = onClick)
    Surface(modifier = clickable, shape = RoundedCornerShape(14.dp), color = t.cardBackground, shadowElevation = 0.dp) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(17.dp))
                Spacer(Modifier.weight(1f))
                Text(title, color = t.textSecondary, style = MaterialTheme.typography.labelSmall)
            }
            Text(value, color = t.textPrimary, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun RefSmallTool(title: String, subtitle: String, icon: ImageVector, onClick: () -> Unit, modifier: Modifier) {
    val t = LocalHetuTokens.current
    val source = remember(title) { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) .96f else 1f, spring(dampingRatio = .74f, stiffness = 560f), label = "smallTool$title")
    val shape = RoundedCornerShape(18.dp)
    Surface(
        modifier = modifier
            .height(58.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale; alpha = if (pressed) .92f else 1f }
            .clip(shape)
            .clickable(interactionSource = source, indication = null, onClick = onClick),
        shape = shape,
        color = t.cardBackground,
        shadowElevation = 1.dp,
    ) {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 9.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(title, color = t.textPrimary, fontSize = 15.sp, lineHeight = 18.sp, fontWeight = FontWeight.Bold)
            Text(subtitle, color = Color(0xFF64748B), fontSize = 11.sp, lineHeight = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun RefSubscriptionCard(items: List<DashboardProviderUi>) {
    val t = LocalHetuTokens.current
    val tracked = items.filter { it.hasSubscriptionInfo && it.total > 0L }
    val used = tracked.sumOf { it.used }
    val total = tracked.sumOf { it.total }
    val remain = (total - used).coerceAtLeast(0L)
    Surface(shape = RoundedCornerShape(14.dp), color = t.cardBackground, shadowElevation = 0.dp) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("订阅", color = t.textSecondary, style = MaterialTheme.typography.labelSmall)
                Text(if (tracked.isEmpty()) "${items.size} 个远程订阅" else "剩余 ${refBytes(remain)}", color = t.textPrimary, style = MaterialTheme.typography.titleSmall)
            }
            if (total > 0L) {
                val ratio = (used.toDouble() / total.toDouble()).toFloat().coerceIn(0f, 1f)
                CircularProgressIndicator(progress = { ratio }, modifier = Modifier.size(34.dp), strokeWidth = 4.dp)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalHazeMaterialsApi::class)
@Composable
private fun RefPanel(
    state: ProxyComposeState,
    repo: ProxyDashboardRepository,
    delays: MutableMap<String, Long>,
    selectedTab: RefPanelTab,
    onSelectedTabChange: (RefPanelTab) -> Unit,
    searchRequest: Int,
    hazeState: HazeState,
    backdrop: LayerBackdrop?,
    glassEnabled: Boolean,
    onRefreshState: suspend () -> Unit,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    onDetailVisibleChanged: (Boolean) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val t = LocalHetuTokens.current
    val haptic = LocalHapticFeedback.current
    val view = LocalView.current
    val tab = selectedTab
    var refreshing by remember { mutableStateOf(false) }
    var providers by remember { mutableStateOf<List<DashboardProviderUi>>(emptyList()) }
    var rules by remember { mutableStateOf<List<ProxyRuleUi>>(emptyList()) }
    var overviewRuleCount by remember { mutableStateOf<Int?>(null) }
    var ruleSets by remember { mutableStateOf<List<DashboardRuleSetUi>>(emptyList()) }
    var selectedGroupName by rememberSaveable { mutableStateOf<String?>(null) }
    val selectedLocal = remember { mutableStateMapOf<String, String>() }
    val testing = remember { mutableStateMapOf<String, Boolean>() }
    val providerRefreshing = remember { mutableStateMapOf<String, Boolean>() }
    val providerSucceeded = remember { mutableStateMapOf<String, Boolean>() }
    val providerErrors = remember { mutableStateMapOf<String, String>() }
    val ruleSetRefreshing = remember { mutableStateMapOf<String, Boolean>() }
    val ruleSetSucceeded = remember { mutableStateMapOf<String, Boolean>() }
    val ruleSetErrors = remember { mutableStateMapOf<String, String>() }
    var capsuleText by remember { mutableStateOf("") }
    var capsuleError by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var groupLayout by rememberSaveable { mutableIntStateOf(0) } // 0 auto, 1 single, 2 double
    var groupSortMode by rememberSaveable { mutableStateOf("config") }
    var groupSortPicker by rememberSaveable { mutableStateOf(false) }
    var connectionView by rememberSaveable { mutableStateOf("active") }
    var connectionProtocol by rememberSaveable { mutableStateOf("all") }
    var connectionSort by rememberSaveable { mutableStateOf("count") }
    var confirmCloseAll by remember { mutableStateOf(false) }
    var closingConnections by remember { mutableStateOf(false) }
    val expandedConnectionApps = remember { mutableStateMapOf<String, Boolean>() }
    val closedConnections = remember { mutableStateListOf<ProxyConnectionUi>() }
    var previousConnections by remember { mutableStateOf<List<ProxyConnectionUi>>(emptyList()) }
    val activeConnectionGroups = remember(state.connections) { refConnectionGroups(state.connections) }
    val closedConnectionSnapshot = closedConnections.toList()
    val closedConnectionGroups = remember(closedConnectionSnapshot) { refConnectionGroups(closedConnectionSnapshot) }
    val connectionGroups = if (connectionView == "active") activeConnectionGroups else closedConnectionGroups
    val filteredConnectionGroups = remember(connectionGroups, query, connectionProtocol, connectionSort) {
        val needle = query.trim()
        val protocolFiltered = connectionGroups.mapNotNull { group ->
            val children = group.connections.filter { item ->
                connectionProtocol == "all" || item.network.contains(connectionProtocol, true)
            }
            if (children.isEmpty()) null else group.copy(connections = children)
        }
        val searched = if (needle.isEmpty()) {
            protocolFiltered
        } else {
            protocolFiltered.mapNotNull { group ->
                if (group.title.contains(needle, true) || group.subtitle.contains(needle, true)) group
                else group.connections.filter { item ->
                    item.host.contains(needle, true) || item.chain.contains(needle, true) ||
                        item.rule.contains(needle, true) || item.network.contains(needle, true) ||
                        item.inbound.contains(needle, true)
                }.takeIf { it.isNotEmpty() }?.let { group.copy(connections = it) }
            }
        }
        when (connectionSort) {
            "traffic" -> searched.sortedWith(compareByDescending<RefConnectionAppGroup> { it.upload + it.download }.thenBy { it.title.lowercase() })
            "name" -> searched.sortedBy { it.title.lowercase() }
            else -> searched.sortedWith(compareByDescending<RefConnectionAppGroup> { it.connections.size }.thenBy { it.title.lowercase() })
        }
    }

    suspend fun closeSelectedConnections(items: List<ProxyConnectionUi>) {
        if (closingConnections) return
        closingConnections = true
        try {
            var failures = 0
            items.forEach { item ->
                try { repo.closeConnection(item.id) }
                catch (cancel: CancellationException) { throw cancel }
                catch (_: Exception) { failures++ }
            }
            onRefreshState()
            capsuleError = failures > 0
            capsuleText = if (failures > 0) "$failures 条连接未能关闭，请刷新后重试" else "连接已关闭"
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            capsuleError = true
            capsuleText = error.message ?: "连接更新失败"
        } finally {
            closingConnections = false
        }
    }

    LaunchedEffect(capsuleText) {
        if (capsuleText.isNotBlank() && !capsuleError) {
            delay(2500)
            capsuleText = ""
            capsuleError = false
        }
    }

    DisposableEffect(Unit) {
        onDispose { onDetailVisibleChanged(false) }
    }

    LaunchedEffect(state.connections) {
        if (previousConnections.isNotEmpty()) {
            val currentIds = state.connections.mapTo(HashSet()) { it.id }
            previousConnections.filter { it.id !in currentIds }.forEach { item ->
                if (closedConnections.none { it.id == item.id }) closedConnections.add(0, item)
            }
            while (closedConnections.size > 80) closedConnections.removeAt(closedConnections.lastIndex)
        }
        previousConnections = state.connections
    }

    val filteredGroups = remember(state.groups, query, groupSortMode, delays.toMap(), selectedLocal.toMap()) {
        val matched = state.groups.filter { group ->
            query.isBlank() || group.name.contains(query, true) || group.now.contains(query, true) ||
                group.nodes.any { it.name.contains(query, true) }
        }
        when (groupSortMode) {
            "name" -> matched.sortedBy { it.name.lowercase() }
            "delay" -> matched.sortedWith(compareBy<ProxyGroupUi> { group ->
                val selected = selectedLocal[group.name] ?: group.now
                (delays[selected] ?: group.nodes.firstOrNull { it.name == selected }?.lastDelay)
                    ?.takeIf { it > 0L } ?: Long.MAX_VALUE
            }.thenBy { it.name.lowercase() })
            else -> matched
        }
    }
    val filteredNodes = remember(state.groups, query) {
        state.groups.flatMap { it.nodes }.distinctBy { it.name }.filter { node ->
            query.isBlank() || node.name.contains(query, true) || node.type.contains(query, true)
        }
    }
    val filteredProviders = remember(providers, query) { providers.filter { query.isBlank() || it.name.contains(query.trim(), true) } }
    val filteredRules = remember(rules, query) { rules.filter {
        query.isBlank() || it.type.contains(query.trim(), true) || it.payload.contains(query.trim(), true) || it.proxy.contains(query.trim(), true)
    } }
    val filteredRuleSets = remember(ruleSets, query) { ruleSets.filter {
        query.isBlank() || it.name.contains(query.trim(), true) || it.behavior.contains(query.trim(), true)
    } }

    suspend fun loadTab() {
        if (!state.running) return
        try {
            when (tab) {
                RefPanelTab.Subscriptions -> providers = repo.providers()
                RefPanelTab.Rules -> rules = repo.rules()
                RefPanelTab.RuleSets -> ruleSets = repo.ruleSets()
                RefPanelTab.Overview -> { overviewRuleCount = null; rules = repo.rules(); overviewRuleCount = rules.size }
                else -> Unit
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (e: Exception) {
            error = e.message ?: "读取失败"
        }
    }

    suspend fun refreshGroupDelaysAnimated() {
        // Pull-to-refresh on the strategy page means the same thing as tapping the
        // delay chip on every visible strategy card: test each group's CURRENT node.
        // It must not silently expand into a probe of every leaf node in every provider.
        val targets = state.groups.mapNotNull { group ->
            (selectedLocal[group.name] ?: group.now)
                .trim()
                .takeIf { it.isNotBlank() && it != "未选择" }
        }.distinct()
        if (targets.isEmpty()) {
            capsuleText = "没有可测速的当前节点"
            capsuleError = false
            return
        }

        // Remove legacy false-timeout values left by older test builds. A failed refresh
        // means "no new value", not "this node is definitely timed out".
        targets.forEach { node ->
            if ((delays[node] ?: 1L) <= 0L) delays.remove(node)
            testing[node] = true
        }

        var completed = 0
        var failed = 0
        capsuleText = "当前节点测速 0/${targets.size}"
        capsuleError = false
        try {
            coroutineScope {
                targets.map { node ->
                    async {
                        val value = try {
                            repo.delay(node)
                        } catch (cancel: CancellationException) {
                            throw cancel
                        } catch (_: Exception) {
                            -1L
                        }
                        if (value > 0L) {
                            delays[node] = value
                        } else {
                            failed++
                        }
                        testing.remove(node)
                        completed++
                        capsuleText = "当前节点测速 $completed/${targets.size}"
                    }
                }.awaitAll()
            }
        } finally {
            targets.forEach { testing.remove(it) }
        }
        capsuleError = failed > 0
        capsuleText = if (failed == 0) {
            "当前节点测速完成 · ${targets.size}/${targets.size}"
        } else {
            "当前节点测速完成 · ${targets.size - failed} 成功 / $failed 未更新"
        }
        view.performHapticFeedback(if (failed == 0) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.CLOCK_TICK)
    }

    suspend fun refreshProvidersAnimated() {
        if (providers.isEmpty()) providers = repo.providers()
        val targets = providers
        if (targets.isEmpty()) {
            capsuleText = "没有远程订阅"
            capsuleError = false
            return
        }
        providerSucceeded.clear()
        providerErrors.clear()
        var completed = 0
        var failed = 0
        capsuleText = "订阅更新 0/${targets.size}"
        capsuleError = false
        targets.forEach { providerRefreshing[it.name] = true }
        coroutineScope {
            targets.map { item ->
                async {
                    try {
                        val updated = repo.refreshProvider(item.name)
                        if (updated != null) providers = providers.map { if (it.name == updated.name) updated else it }
                        providerSucceeded[item.name] = true
                        scope.launch {
                            delay(1400)
                            providerSucceeded.remove(item.name)
                        }
                    } catch (cancel: CancellationException) {
                        throw cancel
                    } catch (failure: Exception) {
                        providerErrors[item.name] = failure.message ?: "更新失败"
                        failed++
                    } finally {
                        providerRefreshing.remove(item.name)
                        completed++
                        capsuleText = "订阅更新 $completed/${targets.size}"
                    }
                }
            }.awaitAll()
        }
        capsuleError = failed > 0
        capsuleText = if (failed == 0) "订阅更新完成 · ${targets.size}/${targets.size}" else "订阅更新完成 · ${targets.size - failed} 成功 / $failed 失败"
        view.performHapticFeedback(if (failed == 0) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.CLOCK_TICK)
    }

    suspend fun refreshRuleSetsAnimated() {
        if (ruleSets.isEmpty()) ruleSets = repo.ruleSets()
        val targets = ruleSets.filter { it.vehicleType.equals("HTTP", true) }
        if (targets.isEmpty()) {
            capsuleText = "没有可更新的远程规则集"
            capsuleError = false
            return
        }
        ruleSetSucceeded.clear()
        ruleSetErrors.clear()
        var completed = 0
        var failed = 0
        capsuleText = "规则集更新 0/${targets.size}"
        capsuleError = false
        ruleSetRefreshing.clear()
        targets.forEach { ruleSetRefreshing[it.name] = true }
        for (chunk in targets.chunked(16)) {
            coroutineScope {
                chunk.map { item ->
                    async {
                        try {
                            val updated = repo.refreshRuleSet(item.name)
                            if (updated != null) ruleSets = ruleSets.map { if (it.name == updated.name) updated else it }
                            ruleSetSucceeded[item.name] = true
                            scope.launch {
                                delay(1400)
                                ruleSetSucceeded.remove(item.name)
                            }
                        } catch (cancel: CancellationException) {
                            throw cancel
                        } catch (failure: Exception) {
                            ruleSetErrors[item.name] = failure.message ?: "更新失败"
                            failed++
                        } finally {
                            ruleSetRefreshing.remove(item.name)
                            completed++
                            capsuleText = "规则集更新 $completed/${targets.size}"
                        }
                    }
                }.awaitAll()
            }
        }
        capsuleError = failed > 0
        capsuleText = if (failed == 0) "规则集更新完成 · ${targets.size}/${targets.size}" else "规则集更新完成 · ${targets.size - failed} 成功 / $failed 失败"
        view.performHapticFeedback(if (failed == 0) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.CLOCK_TICK)
    }

    fun refresh() {
        if (refreshing) return
        scope.launch {
            refreshing = true
            error = ""
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            try {
                if (!state.running) {
                    onRefreshState()
                    return@launch
                }
                when (tab) {
                    RefPanelTab.Groups -> refreshGroupDelaysAnimated()
                    RefPanelTab.Subscriptions -> refreshProvidersAnimated()
                    RefPanelTab.RuleSets -> refreshRuleSetsAnimated()
                    RefPanelTab.Rules -> {
                        rules = repo.rules()
                        capsuleError = false
                        capsuleText = "规则列表已刷新"
                    }
                    RefPanelTab.Overview -> {
                        onRefreshState()
                        overviewRuleCount = null
                        rules = repo.rules()
                        overviewRuleCount = rules.size
                        capsuleError = false
                        capsuleText = "流量状态已刷新"
                    }
                    RefPanelTab.Connections -> {
                        onRefreshState()
                        capsuleError = false
                        capsuleText = "连接状态已刷新"
                    }
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (e: Exception) {
                error = e.message ?: "刷新失败"
            } finally {
                refreshing = false
            }
        }
    }

    LaunchedEffect(tab, state.running) { loadTab() }
    LaunchedEffect(searchRequest) { if (searchRequest > 0) searchOpen = true }

    LaunchedEffect(tab) {
        if (tab != RefPanelTab.Groups) selectedGroupName = null
        onDetailVisibleChanged(false)
    }

    BoxWithConstraints(Modifier.fillMaxSize().statusBarsPadding()) {
        val autoGroupColumns = liquidColumns(maxWidth - 32.dp)
        val groupColumns = if (maxWidth < 292.dp) 1 else when (groupLayout) {
            1 -> 1
            2 -> 2
            else -> autoGroupColumns
        }
        Column(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    RefPanelGlassHeader(
                        selected = tab,
                        onSelect = onSelectedTabChange,
                        searchOpen = searchOpen,
                        query = query,
                        onQueryChange = { query = it },
                        onSearchToggle = {
                            searchOpen = !searchOpen
                            if (!searchOpen) query = ""
                        },
                        onRefreshGroups = ::refresh,
                        groupSortMode = groupSortMode,
                        onSortGroups = { groupSortPicker = true },
                        groupColumns = groupColumns,
                        onToggleGroupLayout = {
                            groupLayout = when (groupLayout) {
                                0 -> if (autoGroupColumns == 1) 2 else 1
                                1 -> 2
                                else -> 1
                            }
                            selectedGroupName = null
                        },
                        onBack = onBack,
                        onOpenSettings = onOpenSettings,
                        hazeState = hazeState,
                        backdrop = backdrop,
                    )

                if (capsuleText.isNotBlank() || refreshing || error.isNotBlank()) {
                    HetuTaskFeedback(capsuleText.ifBlank { error.ifBlank { "正在更新${tab.label}…" } },
                        error = capsuleError || (error.isNotBlank() && capsuleText.isBlank()),
                        busy = refreshing || providerRefreshing.values.any { it } || ruleSetRefreshing.values.any { it } || testing.values.any { it })
                }
            }
            PullToRefreshBox(
                isRefreshing = refreshing,
                onRefresh = ::refresh,
                modifier = Modifier.fillMaxWidth().weight(1f),
                indicator = {},
            ) {
            LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                top = 8.dp,
                end = 16.dp,
                bottom = hetuContentBottomPadding(),
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (!state.running) {
                item { RefEmptyState("代理未运行", "启动代理后，在这里查看节点、应用连接和分流规则。", Icons.Rounded.PowerSettingsNew) }
            } else when (tab) {
                RefPanelTab.Groups -> {
                    if (filteredGroups.isEmpty()) item { RefEmptyState("没有匹配的节点", if (query.isBlank()) "当前配置未提供策略组。" else "试试其他节点或策略组名称。", Icons.Rounded.Search) }
                    itemsIndexed(filteredGroups.chunked(groupColumns), key = { index, _ -> "${tab.name}-groups-$index" }, contentType = { _, _ -> "group-row" }) { _, pair ->
                        val expandedGroup = pair.firstOrNull { it.name == selectedGroupName }
                        // Keep the last content through the exit animation; a nullable let
                        // otherwise removes the entire well before shrinkVertically runs.
                        var closingGroup by remember(pair.map { it.name }) { mutableStateOf<ProxyGroupUi?>(null) }
                        SideEffect { if (expandedGroup != null) closingGroup = expandedGroup }
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                pair.forEach { group ->
                                    val selected = selectedLocal[group.name] ?: group.now
                                    RefGroupCard(
                                        group = group,
                                        selected = selected,
                                        expanded = selectedGroupName == group.name,
                                        delay = delays[selected] ?: group.nodes.firstOrNull { it.name == selected }?.lastDelay,
                                        testing = selected.isNotBlank() && testing[selected] == true,
                                        hazeState = hazeState,
                                        glassEnabled = glassEnabled,
                                        modifier = Modifier.weight(1f),
                                        onClick = {
                                            view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                                            selectedGroupName = if (selectedGroupName == group.name) null else group.name
                                        },
                                        onDelay = {
                                            if (selected.isNotBlank() && testing[selected] != true) scope.launch {
                                                testing[selected] = true
                                                try {
                                                    val measured = repo.delay(selected)
                                                    if (measured > 0L) delays[selected] = measured
                                                    view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                                } catch (_: Exception) {
                                                    if ((delays[selected] ?: 1L) <= 0L) delays.remove(selected)
                                                } finally {
                                                    testing.remove(selected)
                                                }
                                            }
                                        },
                                    )
                                }
                                if (pair.size < groupColumns) Spacer(Modifier.weight(1f))
                            }
                            WorkspaceAccordion(visible = expandedGroup != null) {
                                (expandedGroup ?: closingGroup)?.let { group ->
                                    val selected = selectedLocal[group.name] ?: group.now
                                    RefInlineGroupExpansion(
                                        group = group,
                                        selected = selected,
                                        delays = delays,
                                        testing = testing,
                                        onSelect = { node ->
                                            val previous = selectedLocal[group.name] ?: group.now
                                            selectedLocal[group.name] = node
                                            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                            scope.launch {
                                                try {
                                                    repo.select(group.name, node)
                                                    onRefreshState()
                                                } catch (_: Exception) {
                                                    if (previous.isBlank()) selectedLocal.remove(group.name) else selectedLocal[group.name] = previous
                                                }
                                            }
                                        },
                                        onDelay = { node ->
                                            if (testing[node] != true) scope.launch {
                                                testing[node] = true
                                                try {
                                                    val measured = repo.delay(node)
                                                    if (measured > 0L) delays[node] = measured
                                                } catch (_: Exception) {
                                                    if ((delays[node] ?: 1L) <= 0L) delays.remove(node)
                                                } finally { testing.remove(node) }
                                            }
                                        },
                                        onTestAll = {
                                            val pending = group.nodes.filter { testing[it.name] != true }
                                            if (pending.isNotEmpty()) scope.launch {
                                                try {
                                                    val wave = pending.mapIndexed { index, node ->
                                                        async {
                                                            delay(index * 30L)
                                                            testing[node.name] = true
                                                            try { repo.delay(node.name) }
                                                            catch (_: Exception) { -1L }
                                                        }
                                                    }
                                                    pending.forEachIndexed { index, node ->
                                                        val measured = wave[index].await()
                                                        if (measured > 0L) delays[node.name] = measured
                                                        else if ((delays[node.name] ?: 1L) <= 0L) delays.remove(node.name)
                                                        delay(32L)
                                                        testing.remove(node.name)
                                                    }
                                                    view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                                                } finally {
                                                    pending.forEach { testing.remove(it.name) }
                                                }
                                            }
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
                RefPanelTab.Overview -> item(key = "${tab.name}-traffic-overview", contentType = "traffic-overview") { RefTrafficOverview(state, overviewRuleCount) }
                RefPanelTab.Subscriptions -> items(filteredProviders, key = { "${tab.name}-provider-${it.name}" }, contentType = { "subscription-provider" }) { item ->
                    RefProviderRow(
                        item = item,
                        refreshing = providerRefreshing[item.name] == true,
                        success = providerSucceeded[item.name] == true,
                        error = providerErrors[item.name].orEmpty(),
                        onRefresh = {
                            if (providerRefreshing[item.name] != true) scope.launch {
                                providerRefreshing[item.name] = true
                                providerSucceeded.remove(item.name)
                                providerErrors.remove(item.name)
                                try {
                                    val updated = repo.refreshProvider(item.name)
                                    if (updated != null) providers = providers.map { if (it.name == updated.name) updated else it }
                                    providerSucceeded[item.name] = true
                                    scope.launch {
                                        delay(1400)
                                        providerSucceeded.remove(item.name)
                                    }
                                    capsuleError = false
                                    capsuleText = "订阅更新成功"
                                    view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                                    delay(70)
                                    view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                } catch (cancel: CancellationException) {
                                    throw cancel
                                } catch (e: Exception) {
                                    providerErrors[item.name] = e.message ?: "更新失败"
                                    capsuleError = true
                                    capsuleText = "${item.name} 更新失败：${e.message.orEmpty()}"
                                } finally {
                                    providerRefreshing.remove(item.name)
                                }
                            }
                        },
                        onClick = { context.startActivity(Intent(context, ProxySubscriptionActivity::class.java)) },
                    )
                }
                RefPanelTab.Connections -> {
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(
                                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                FilterChip(
                                    selected = connectionView == "active",
                                    onClick = { connectionView = "active" },
                                    label = { Text("活动 ${state.connections.size}") },
                                )
                                FilterChip(
                                    selected = connectionView == "closed",
                                    onClick = { connectionView = "closed" },
                                    label = { Text("已关闭 ${closedConnections.size}") },
                                )
                                FilterChip(
                                    selected = connectionProtocol == "all",
                                    onClick = { connectionProtocol = "all" },
                                    label = { Text("全部协议") },
                                )
                                FilterChip(
                                    selected = connectionProtocol == "tcp",
                                    onClick = { connectionProtocol = "tcp" },
                                    label = { Text("TCP") },
                                )
                                FilterChip(
                                    selected = connectionProtocol == "udp",
                                    onClick = { connectionProtocol = "udp" },
                                    label = { Text("UDP") },
                                )
                            }
                            Row(
                                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(7.dp),
                            ) {
                                Text("排序", color = t.textMuted, fontSize = 11.sp)
                                listOf("count" to "连接数", "traffic" to "流量", "name" to "名称").forEach { (value, label) ->
                                    FilterChip(
                                        selected = connectionSort == value,
                                        onClick = { connectionSort = value },
                                        label = { Text(label, fontSize = 11.sp) },
                                    )
                                }
                            }
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    if (query.isBlank()) "${connectionGroups.size} 个应用 · 点按可查看详情" else "找到 ${filteredConnectionGroups.size} 个应用",
                                    modifier = Modifier.weight(1f), color = t.textSecondary, style = MaterialTheme.typography.bodySmall,
                                )
                                if (connectionView == "active" && state.connections.isNotEmpty()) {
                                    TextButton(onClick = { confirmCloseAll = true }, enabled = !closingConnections) {
                                        Text(if (closingConnections) "正在关闭…" else "终止全部", color = t.danger)
                                    }
                                }
                            }
                        }
                    }
                    if (filteredConnectionGroups.isEmpty()) item {
                        RefEmptyState(
                            if (query.isNotBlank()) "没有匹配的连接" else if (connectionView == "closed") "暂无已关闭连接" else "暂无活动连接",
                            if (query.isNotBlank()) "可以搜索应用名称、域名或分流规则。" else if (connectionView == "closed") "在本次查看期间结束的连接会显示在这里。" else "应用产生网络请求后，连接会自动显示。",
                            Icons.Rounded.Link,
                        )
                    }
                    items(filteredConnectionGroups, key = { "${tab.name}-app-" + it.key }, contentType = { "connection-app-group" }) { group ->
                        RefConnectionAppCard(
                            group = group,
                            expanded = expandedConnectionApps[group.key] == true,
                            onToggle = {
                                expandedConnectionApps[group.key] = expandedConnectionApps[group.key] != true
                            },
                            onCloseAll = if (connectionView == "active" && !closingConnections) ({
                                scope.launch { closeSelectedConnections(group.connections) }
                            }) else null,
                            onCloseConnection = if (connectionView == "active" && !closingConnections) ({ item ->
                                scope.launch { closeSelectedConnections(listOf(item)) }
                            }) else null,
                        )
                    }
                }
                RefPanelTab.Rules -> itemsIndexed(instrumentRuleBatches(filteredRules), key = { index, _ -> "${tab.name}-rule-group-$index" }, contentType = { _, _ -> "rule-group" }) { _, batch ->
                    RefRuleGroupCard(batch)
                }
                RefPanelTab.RuleSets -> items(filteredRuleSets, key = { "${tab.name}-ruleset-${it.name}" }, contentType = { "ruleset-row" }) { item ->
                    RefRuleSetRow(item, refreshing = ruleSetRefreshing[item.name] == true, success = ruleSetSucceeded[item.name] == true, error = ruleSetErrors[item.name].orEmpty()) {
                        if (ruleSetRefreshing[item.name] != true) scope.launch {
                            ruleSetRefreshing[item.name] = true
                            ruleSetSucceeded.remove(item.name)
                            ruleSetErrors.remove(item.name)
                            try {
                                val updated = repo.refreshRuleSet(item.name)
                                if (updated != null) ruleSets = ruleSets.map { if (it.name == updated.name) updated else it }
                                capsuleError = false
                                capsuleText = "${item.name} 更新完成"
                                ruleSetSucceeded[item.name] = true
                                view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                                delay(70)
                                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                scope.launch {
                                    delay(1400)
                                    ruleSetSucceeded.remove(item.name)
                                }
                            } catch (cancel: CancellationException) {
                                throw cancel
                            } catch (e: Exception) {
                                ruleSetErrors[item.name] = e.message ?: "更新失败"
                                capsuleError = true
                                capsuleText = "${item.name} 更新失败：${e.message.orEmpty()}"
                            } finally {
                                ruleSetRefreshing.remove(item.name)
                            }
                        }
                    }
                }
            }
            }
        }
        } // column: header remains above list, never overlaps tabs
    } // measured content width



    if (groupSortPicker) {
        val sortValues = listOf(
            "config" to "配置顺序",
            "delay" to "当前节点延迟",
            "name" to "名称 A–Z",
        )
        RefChoiceBottomSheet(
            title = "策略组排序",
            options = sortValues.map { (value, label) -> label to (groupSortMode == value) },
            onDismiss = { groupSortPicker = false },
            onSelect = { index ->
                groupSortMode = sortValues[index].first
                selectedGroupName = null
                groupSortPicker = false
            },
        )
    }

    if (confirmCloseAll) {
        RefConfirmBottomSheet(
            title = "终止所有连接？",
            description = "所有应用的现有连接都会断开。消息、通话和下载可能需要重新连接。",
            confirmLabel = "终止全部",
            onDismiss = { confirmCloseAll = false },
            onConfirm = {
                confirmCloseAll = false
                if (!closingConnections) scope.launch {
                    closingConnections = true
                    try {
                        repo.closeAll()
                        onRefreshState()
                        capsuleError = false
                        capsuleText = "现有连接已关闭"
                    } catch (cancel: CancellationException) {
                        throw cancel
                    } catch (error: Exception) {
                        capsuleError = true
                        capsuleText = error.message ?: "关闭失败，请稍后重试"
                    } finally {
                        closingConnections = false
                    }
                }
            },
        )
    }
}


@Composable
private fun RefGroupDetailPage(
    state: ProxyComposeState,
    group: ProxyGroupUi,
    selected: String,
    delays: Map<String, Long>,
    testing: Map<String, Boolean>,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onSelect: (String) -> Unit,
    onDelay: (String) -> Unit,
    onTestAll: () -> Unit,
) {
    var predictiveBackProgress by remember(group.name) { mutableFloatStateOf(0f) }
    var predictiveBackDirection by remember(group.name) { mutableFloatStateOf(1f) }
    PredictiveBackHandler(enabled = true) { events ->
        try {
            events.collect { event ->
                predictiveBackProgress = event.progress.coerceIn(0f, 1f)
                predictiveBackDirection = if (event.swipeEdge == BackEventCompat.EDGE_LEFT) 1f else -1f
            }
            onBack()
        } catch (cancel: CancellationException) {
            predictiveBackProgress = 0f
            throw cancel
        }
    }
    val t = LocalHetuTokens.current
    val scheme = MaterialTheme.colorScheme
    val dark = scheme.background.luminance() < .5f
    val haptic = LocalHapticFeedback.current
    var searchOpen by rememberSaveable(group.name) { mutableStateOf(false) }
    var query by rememberSaveable(group.name) { mutableStateOf("") }
    val tags = remember(group.nodes) { refNodeFilterTags(group.nodes) }
    var activeTag by rememberSaveable(group.name) { mutableStateOf("全部") }
    var lastBytes by remember(group.name) { mutableLongStateOf(state.uploadTotal + state.downloadTotal) }
    var lastRateAt by remember(group.name) { mutableLongStateOf(0L) }
    var liveRate by remember(group.name) { mutableLongStateOf(0L) }
    var entered by remember(group.name) { mutableStateOf(false) }
    LaunchedEffect(group.name) { entered = true }
    val enterX by animateDpAsState(
        targetValue = if (entered) 0.dp else 28.dp,
        animationSpec = spring(dampingRatio = .86f, stiffness = 430f),
        label = "groupDetailEnter",
    )
    LaunchedEffect(state.uploadTotal, state.downloadTotal) {
        val now = SystemClock.elapsedRealtime()
        val total = state.uploadTotal + state.downloadTotal
        if (lastRateAt > 0L && now > lastRateAt && total >= lastBytes) {
            liveRate = ((total - lastBytes) * 1000L / (now - lastRateAt)).coerceAtLeast(0L)
        }
        lastBytes = total
        lastRateAt = now
    }
    val filteredNodes = remember(group.nodes, query, activeTag) {
        group.nodes.filter { node ->
            val queryMatch = query.isBlank() || node.name.contains(query, true) || node.type.contains(query, true)
            val tagMatch = activeTag == "全部" || node.name.contains(activeTag, true) || node.type.contains(activeTag, true)
            queryMatch && tagMatch
        }
    }
    val testedCount = group.nodes.count { node ->
        val delay = delays[node.name] ?: node.lastDelay
        delay != null && delay > 0L
    }
    val anyTesting = group.nodes.any { testing[it.name] == true }
    val pageBackground = t.pageBackground
    val nodeColumns = hetuCompactColumns(androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp.dp - 32.dp, 220.dp)

    val predictiveDistancePx = with(LocalDensity.current) { 52.dp.toPx() }
    Column(
        Modifier.fillMaxSize()
            .offset(x = enterX)
            .graphicsLayer {
                translationX = predictiveBackDirection * predictiveBackProgress * predictiveDistancePx
                alpha = 1f - predictiveBackProgress * .10f
            }
            .background(pageBackground)
            .navigationBarsPadding(),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = if (dark) t.elevatedCardBackground.copy(alpha = .94f) else Color.White.copy(alpha = .94f),
            shadowElevation = 1.dp,
        ) {
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().height(56.dp).padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回", tint = t.textPrimary, modifier = Modifier.size(21.dp))
                }
                Text(
                    "127.0.0.1:${state.controllerPort}",
                    color = t.textSecondary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onRefresh, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Rounded.Refresh, "刷新", tint = t.textSecondary, modifier = Modifier.size(18.dp))
                }
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, top = 14.dp, end = 16.dp, bottom = 22.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                group.name + " · 可选节点",
                                color = t.textPrimary,
                                fontSize = 22.sp,
                                lineHeight = 29.sp,
                                fontWeight = FontWeight.ExtraBold,
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "${refGroupTypeCompact(group.type).uppercase()} · $testedCount/${group.nodes.size}",
                                    color = Color(0xFF94A3B8),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                )
                                IconButton(
                                    onClick = { searchOpen = !searchOpen; if (!searchOpen) query = "" },
                                    modifier = Modifier.size(48.dp),
                                ) {
                                    Icon(
                                        if (searchOpen) Icons.Rounded.Close else Icons.Rounded.Search,
                                        "搜索节点",
                                        tint = Color(0xFF94A3B8),
                                        modifier = Modifier.size(16.dp),
                                    )
                                }
                            }
                        }
                        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text("⚡ ${refSpeed(liveRate)}", color = Color(0xFF94A3B8), fontSize = 12.sp, fontWeight = FontWeight.Medium)
                            if (anyTesting) {
                                Text("$testedCount/${group.nodes.size}", color = Color(0xFFF59E0B), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                        Spacer(Modifier.width(10.dp))
                        val rocketSource = remember { MutableInteractionSource() }
                        val rocketPressed by rocketSource.collectIsPressedAsState()
                        val rocketScale by animateFloatAsState(if (rocketPressed) .94f else 1f, label = "rocketPress")
                        Box(
                            Modifier.size(48.dp)
                                .graphicsLayer { scaleX = rocketScale; scaleY = rocketScale }
                                .clip(RoundedCornerShape(14.dp))
                                .background(Color(0xFFFFF7ED))
                                .clickable(interactionSource = rocketSource, indication = null) {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    onTestAll()
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(Icons.Rounded.RocketLaunch, "测试全部节点", tint = Color(0xFFF59E0B), modifier = Modifier.size(24.dp))
                        }
                    }

                    if (searchOpen) {
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            placeholder = { Text("搜索节点或协议") },
                            leadingIcon = { Icon(Icons.Rounded.Search, null, modifier = Modifier.size(17.dp)) },
                            shape = RoundedCornerShape(14.dp),
                        )
                    }

                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(7.dp),
                    ) {
                        tags.forEach { tag ->
                            val active = tag == activeTag
                            Surface(
                                shape = CircleShape,
                                color = if (active) Color(0xFFEBF3FF) else t.cardBackground,
                                shadowElevation = if (active) 1.dp else 0.dp,
                                modifier = Modifier.heightIn(min = 48.dp).clickable { activeTag = tag },
                            ) {
                                Text(
                                    tag,
                                    Modifier.padding(horizontal = 11.dp, vertical = 6.dp),
                                    color = if (active) scheme.primary else t.textSecondary,
                                    fontSize = 12.sp,
                                    fontWeight = if (active) FontWeight.Bold else FontWeight.SemiBold,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                }
            }

            if (filteredNodes.isEmpty()) {
                item {
                    Surface(shape = RoundedCornerShape(18.dp), color = t.cardBackground, shadowElevation = 0.dp) {
                        Column(
                            Modifier.fillMaxWidth().padding(vertical = 28.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Icon(Icons.Rounded.SearchOff, null, tint = t.textMuted, modifier = Modifier.size(24.dp))
                            Text("没有匹配的节点", color = t.textPrimary, fontWeight = FontWeight.SemiBold)
                            Text("换个关键词或筛选标签", color = t.textSecondary, fontSize = 12.sp)
                        }
                    }
                }
            } else {
                itemsIndexed(filteredNodes.chunked(nodeColumns), key = { index, _ -> "detail-row-$index" }) { _, pair ->
                    Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                        pair.forEach { node ->
                            RefDetailNodeCard(
                                node = node,
                                active = node.name == selected,
                                delay = delays[node.name] ?: node.lastDelay,
                                testing = testing[node.name] == true,
                                modifier = Modifier.weight(1f),
                                onSelect = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    onSelect(node.name)
                                },
                                onDelay = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    onDelay(node.name)
                                },
                            )
                        }
                        if (pair.size < nodeColumns) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun RefDetailNodeCard(node: ProxyNodeUi, active: Boolean, delay: Long?, testing: Boolean,
    modifier: Modifier, onSelect: () -> Unit, onDelay: () -> Unit) {
    NodeChoiceCard(node, active, delay, testing, modifier, onSelect, onDelay)
}

private fun refNodeProtocol(node: ProxyNodeUi): String {
    val base = node.type.ifBlank { "node" }.lowercase()
    return if (node.udp) "$base / udp" else base
}

private fun refNodeFilterTags(nodes: List<ProxyNodeUi>): List<String> {
    val candidates = listOf("无限", "移动", "联通", "电信", "香港", "日本", "新加坡", "美国", "台湾", "韩国", "自动", "直连")
    return buildList {
        add("全部")
        candidates.filterTo(this) { tag -> nodes.any { node -> node.name.contains(tag, true) || node.type.contains(tag, true) } }
    }
}

@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
private fun RefPanelGlassHeader(
    selected: RefPanelTab,
    onSelect: (RefPanelTab) -> Unit,
    searchOpen: Boolean,
    query: String,
    onQueryChange: (String) -> Unit,
    onSearchToggle: () -> Unit,
    onRefreshGroups: () -> Unit,
    groupSortMode: String,
    onSortGroups: () -> Unit,
    groupColumns: Int,
    onToggleGroupLayout: () -> Unit,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    hazeState: HazeState,
    backdrop: LayerBackdrop?,
) {
    val t = LocalHetuTokens.current
    val scheme = MaterialTheme.colorScheme
    val dark = scheme.background.luminance() < .5f

    // Keep the header structurally transparent. Previous full-width glass shells created
    // an oversized white slab on some OEM renderers. Only the compact controls carry glass.
    Column(
        Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().height(48.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            when (selected) {
                RefPanelTab.Groups -> {
                    RefPanelHeaderAction(
                        icon = if (searchOpen) Icons.Rounded.Close else Icons.Rounded.Search,
                        contentDescription = if (searchOpen) "关闭搜索" else "搜索",
                        active = searchOpen,
                        onClick = onSearchToggle,
                    )
                    RefPanelHeaderAction(
                        icon = Icons.Rounded.FilterList,
                        contentDescription = if (groupColumns == 2) "切换单列" else "切换双列",
                        active = groupColumns == 1,
                        onClick = onToggleGroupLayout,
                    )
                    Spacer(Modifier.weight(1f))
                    RefPanelHeaderAction(
                        icon = Icons.Rounded.Sort,
                        contentDescription = when (groupSortMode) {
                            "name" -> "策略组排序：名称"
                            "delay" -> "策略组排序：延迟"
                            else -> "策略组排序：配置顺序"
                        },
                        active = groupSortMode != "config",
                        onClick = onSortGroups,
                    )
                    RefPanelHeaderAction(
                        icon = Icons.Rounded.Settings,
                        contentDescription = "面板设置",
                        onClick = onOpenSettings,
                    )
                }
                RefPanelTab.Overview -> {
                    Spacer(Modifier.weight(1f))
                    RefPanelHeaderAction(
                        icon = Icons.Rounded.Settings,
                        contentDescription = "面板设置",
                        onClick = onOpenSettings,
                    )
                }
                RefPanelTab.Subscriptions -> {
                    RefPanelHeaderAction(
                        icon = Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = "返回首页",
                        onClick = onBack,
                    )
                    Spacer(Modifier.weight(1f))
                    RefPanelHeaderAction(
                        icon = Icons.Rounded.Link,
                        contentDescription = "刷新订阅",
                        active = true,
                        onClick = onRefreshGroups,
                    )
                    RefPanelHeaderAction(
                        icon = Icons.Rounded.Settings,
                        contentDescription = "面板设置",
                        onClick = onOpenSettings,
                    )
                }
                else -> {
                    RefPanelHeaderAction(
                        icon = Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = "返回首页",
                        onClick = onBack,
                    )
                    Spacer(Modifier.weight(1f))
                    if (selected != RefPanelTab.Overview) {
                        RefPanelHeaderAction(
                            icon = if (searchOpen) Icons.Rounded.Close else Icons.Rounded.Search,
                            contentDescription = if (searchOpen) "关闭搜索" else "搜索",
                            active = searchOpen,
                            onClick = onSearchToggle,
                        )
                    }
                    RefPanelHeaderAction(
                        icon = Icons.Rounded.Settings,
                        contentDescription = "面板设置",
                        onClick = onOpenSettings,
                    )
                }
            }
        }

        Text(
            "面板",
            color = t.textPrimary,
            fontSize = 32.sp,
            lineHeight = 40.sp,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = (-.8).sp,
            modifier = Modifier.fillMaxWidth(),
        )
        RefPanelTabs(
            selected = selected,
            liquidGlass = true,
            onSelect = onSelect,
        )
        androidx.compose.animation.AnimatedVisibility(
            visible = searchOpen && selected != RefPanelTab.Overview,
            enter = androidx.compose.animation.expandVertically(
                expandFrom = Alignment.Top,
                animationSpec = androidx.compose.animation.core.tween(240),
            ) + androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(180)) +
                androidx.compose.animation.slideInVertically(androidx.compose.animation.core.tween(220)) { -it / 4 },
            exit = androidx.compose.animation.shrinkVertically(
                shrinkTowards = Alignment.Top,
                animationSpec = androidx.compose.animation.core.tween(190),
            ) + androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(140)),
        ) {
            TextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text(when (selected) {
                    RefPanelTab.Connections -> "搜索应用、域名或分流规则"
                    RefPanelTab.Subscriptions -> "搜索订阅名称"
                    RefPanelTab.Rules -> "搜索规则、目标或策略"
                    RefPanelTab.RuleSets -> "搜索规则集"
                    else -> "搜索策略组或节点"
                }) },
                leadingIcon = { Icon(Icons.Rounded.Search, null, Modifier.size(18.dp)) },
                shape = RoundedCornerShape(18.dp),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = if (dark) Color.White.copy(alpha = .07f) else Color.White.copy(alpha = .82f),
                    unfocusedContainerColor = if (dark) Color.White.copy(alpha = .05f) else Color.White.copy(alpha = .72f),
                    disabledContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
            )
        }
    }
}

@Composable
private fun RefPanelHeaderAction(
    icon: ImageVector,
    contentDescription: String,
    active: Boolean = false,
    onClick: () -> Unit,
) {
    val t = LocalHetuTokens.current
    val scheme = MaterialTheme.colorScheme
    val dark = scheme.background.luminance() < .5f
    val view = LocalView.current
    val source = remember(contentDescription) { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(
        if (pressed) .90f else 1f,
        spring(dampingRatio = .68f, stiffness = 620f),
        label = "test46GhostHeader",
    )
    val pressFill = if (dark) Color.White.copy(alpha = .08f) else Color(0xFFE2E8F0).copy(alpha = .60f)
    Box(
        Modifier.size(48.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale; alpha = if (pressed) .84f else 1f }
            .background(if (pressed) pressFill else Color.Transparent, CircleShape)
            .clip(CircleShape)
            .clickable(interactionSource = source, indication = null) {
                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                onClick()
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription,
            tint = if (active) scheme.primary else if (dark) t.textSecondary else Color(0xFF64748B),
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun RefPanelTabs(
    selected: RefPanelTab,
    liquidGlass: Boolean = false,
    onSelect: (RefPanelTab) -> Unit,
) {
    val t = LocalHetuTokens.current
    val dark = MaterialTheme.colorScheme.background.luminance() < .5f
    val view = LocalView.current
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RefPanelTab.entries.forEach { tab ->
            val active = tab == selected
            val shape = RoundedCornerShape(14.dp)
            val source = remember(tab) { MutableInteractionSource() }
            val pressed by source.collectIsPressedAsState()
            val scale by animateFloatAsState(
                if (pressed) .96f else 1f,
                spring(dampingRatio = .78f, stiffness = 520f),
                label = "reference-tab-${tab.name}",
            )
            Box(
                Modifier
                    .height(38.dp)
                    .graphicsLayer { scaleX = scale; scaleY = scale }
                    .clip(shape)
                    .background(
                        if (active) {
                            if (dark) Color.White.copy(alpha = .10f) else Color(0xFFF9F8FE)
                        } else Color.Transparent,
                        shape,
                    )
                    .border(
                        if (active) .7.dp else 1.dp,
                        if (active) {
                            if (dark) Color.White.copy(alpha = .08f) else Color.White.copy(alpha = .24f)
                        } else {
                            if (dark) Color.White.copy(alpha = .42f) else Color(0xFF6D6975)
                        },
                        shape,
                    )
                    .clickable(interactionSource = source, indication = null) {
                        if (!active) {
                            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                            onSelect(tab)
                        }
                    }
                    .padding(horizontal = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    tab.label,
                    color = if (dark) t.textPrimary else Color(0xFF37333F),
                    fontSize = 15.5.sp,
                    lineHeight = 20.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun RefPanelOverview(state: ProxyComposeState, delays: Map<String, Long>) {
    val t = LocalHetuTokens.current
    val values = delays.values.filter { it > 0L }
    val avg = values.takeIf { it.isNotEmpty() }?.average()?.toInt()?.toString() ?: "--"
    Surface(shape = RoundedCornerShape(16.dp), color = t.cardBackground, shadowElevation = 0.dp) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(state.config, color = t.textPrimary, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RefMetric("连接", state.connections.size.toString(), Modifier.weight(1f))
                RefMetric("延迟", "$avg ms", Modifier.weight(1f))
                RefMetric("规则组", state.groups.size.toString(), Modifier.weight(1f))
            }
        }
    }
}

@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
private fun RefGroupCard(group: ProxyGroupUi, selected: String, expanded: Boolean, delay: Long?, testing: Boolean,
    hazeState: HazeState, glassEnabled: Boolean, modifier: Modifier, onClick: () -> Unit, onDelay: () -> Unit) {
    StrategyGroupCard(group, selected, expanded, delay, testing, modifier, onClick, onDelay, hazeState, glassEnabled)
}

@Composable
private fun RefInlineGroupExpansion(group: ProxyGroupUi, selected: String, delays: Map<String, Long>,
    testing: Map<String, Boolean>, onSelect: (String) -> Unit, onDelay: (String) -> Unit, onTestAll: () -> Unit) {
    LiquidGroupWell(group, selected, delays, testing, onSelect, onDelay, onTestAll)
}

@Composable
private fun RefGroupCornerVisual(group: ProxyGroupUi, modifier: Modifier) { ConfiguredGroupIcon(group, modifier) }

private fun refGroupBadgePalette(name: String, dark: Boolean): Triple<Color, Color, Color> {
    val value = name.lowercase(java.util.Locale.ROOT)
    val (base, border, tint) = when {
        value.contains("openai") || value.contains("chatgpt") || value.contains("ai") ->
            Triple(Color(0xFFECFDF5), Color(0xFFA7F3D0), Color(0xFF059669))
        value.contains("telegram") || value.contains("twitter") || value.contains("x ") ->
            Triple(Color(0xFFF0F9FF), Color(0xFFBAE6FD), Color(0xFF0EA5E9))
        value.contains("youtube") || value.contains("netflix") ->
            Triple(Color(0xFFFFF1F2), Color(0xFFFFCDD3), Color(0xFFF43F5E))
        value.contains("google") || value.contains("chrome") ->
            Triple(Color(0xFFF8FAFC), Color(0xFFE2E8F0), Color(0xFF475569))
        value.contains("github") ->
            Triple(Color(0xFFF8FAFC), Color(0xFFE2E8F0), Color(0xFF334155))
        else ->
            Triple(Color(0xFFEFF6FF), Color(0xFFDBEAFE), Color(0xFF2563EB))
    }
    return if (!dark) Triple(base, border.copy(alpha = .70f), tint)
    else Triple(tint.copy(alpha = .14f), tint.copy(alpha = .22f), tint.copy(alpha = .92f))
}

@Composable
private fun RefLeafNodeCard(node: ProxyNodeUi, delay: Long?, modifier: Modifier, onClick: () -> Unit) {
    val t = LocalHetuTokens.current
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier.crystalMaterial(shape, depth = CrystalDepth.InsetItem).clickable(onClick = onClick).padding(11.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(28.dp).background(scheme.primary.copy(alpha = .08f), RoundedCornerShape(9.dp)), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Public, null, tint = scheme.primary, modifier = Modifier.size(16.dp))
            }
            Spacer(Modifier.width(7.dp))
            Text(node.name, color = t.textPrimary, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            RefDelayBadge(delay, false, null)
        }
        Text(listOf(node.type.ifBlank { "节点" }, if (node.udp) "UDP" else "").filter { it.isNotBlank() }.joinToString(" · "), color = t.textSecondary, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

internal fun refScenarioIcon(name: String, type: String): ImageVector = when {
    name.contains("chatgpt", true) || name.contains("openai", true) || name.contains("ai", true) -> Icons.Rounded.SmartToy
    name.contains("youtube", true) -> Icons.Rounded.PlayCircle
    name.contains("tiktok", true) -> Icons.Rounded.MusicNote
    name.contains("netflix", true) -> Icons.Rounded.Movie
    name.contains("apple", true) -> Icons.Rounded.PhoneIphone
    else -> refGroupIcon(type)
}

private fun refGroupIcon(type: String): ImageVector = when (type.lowercase()) {
    "urltest", "fallback" -> Icons.Rounded.Speed
    "selector" -> Icons.Rounded.Tune
    "loadbalance" -> Icons.Rounded.SwapHoriz
    else -> Icons.Rounded.Hub
}

private fun refGroupType(type: String): String = when (type.lowercase()) {
    "urltest" -> "自动测速"
    "selector" -> "手动选择"
    "fallback" -> "故障转移"
    "loadbalance" -> "负载均衡"
    else -> type.ifBlank { "策略组" }
}

private fun refGroupTypeCompact(type: String): String = when (type.lowercase()) {
    "urltest", "url-test" -> "URLTest"
    "selector" -> "手动选择"
    "fallback" -> "Fallback"
    "loadbalance", "load-balance" -> "LoadBalance"
    else -> type.ifBlank { "Group" }
}

internal fun refNodeFlag(name: String): String {
    val value = name.trim()
    if (listOf("🇭🇰", "🇹🇼", "🇯🇵", "🇸🇬", "🇰🇷", "🇺🇸", "🇬🇧", "🇩🇪", "🇫🇷").any(value::contains)) return ""
    val upper = value.uppercase(java.util.Locale.ROOT)
    return when {
        value.contains("香港") || upper.contains("HONG KONG") || upper.startsWith("HK") -> "🇭🇰"
        value.contains("台湾") || value.contains("台灣") || upper.contains("TAIWAN") || upper.startsWith("TW") -> "🇹🇼"
        value.contains("日本") || upper.contains("JAPAN") || upper.startsWith("JP") -> "🇯🇵"
        value.contains("新加坡") || upper.contains("SINGAPORE") || upper.startsWith("SG") -> "🇸🇬"
        value.contains("韩国") || value.contains("韓國") || upper.contains("KOREA") || upper.startsWith("KR") -> "🇰🇷"
        value.contains("美国") || value.contains("美國") || upper.contains("UNITED STATES") || upper.startsWith("US") -> "🇺🇸"
        value.contains("英国") || value.contains("英國") || upper.contains("UNITED KINGDOM") || upper.startsWith("UK") -> "🇬🇧"
        value.contains("德国") || value.contains("德國") || upper.contains("GERMANY") || upper.startsWith("DE") -> "🇩🇪"
        value.contains("法国") || value.contains("法國") || upper.contains("FRANCE") || upper.startsWith("FR") -> "🇫🇷"
        else -> ""
    }
}

@Composable
private fun RefDelayBadge(value: Long?, testing: Boolean, onClick: (() -> Unit)?, selected: Boolean = false) {
    LatencyChip(value, testing, onClick)
}

@Composable
private fun RefTrafficOverview(state: ProxyComposeState, ruleCount: Int?) {
    val t = LocalHetuTokens.current
    val scheme = MaterialTheme.colorScheme
    val history = remember { mutableStateListOf<Triple<Long, Long, Long>>() }
    var lastUpload by remember { mutableLongStateOf(state.uploadTotal) }
    var lastDownload by remember { mutableLongStateOf(state.downloadTotal) }
    var lastAt by remember { mutableLongStateOf(0L) }
    var upRate by remember { mutableLongStateOf(0L) }
    var downRate by remember { mutableLongStateOf(0L) }

    LaunchedEffect(state.uploadTotal, state.downloadTotal) {
        val now = SystemClock.elapsedRealtime()
        if (lastAt > 0L && now > lastAt && state.uploadTotal >= lastUpload && state.downloadTotal >= lastDownload) {
            val elapsed = now - lastAt
            upRate = ((state.uploadTotal - lastUpload) * 1000L / elapsed).coerceAtLeast(0L)
            downRate = ((state.downloadTotal - lastDownload) * 1000L / elapsed).coerceAtLeast(0L)
        }
        lastAt = now
        lastUpload = state.uploadTotal
        lastDownload = state.downloadTotal
    }

    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            val now = SystemClock.elapsedRealtime()
            history += Triple(now, upRate, downRate)
            while (history.isNotEmpty() && history.first().first < now - 60_000L) {
                history.removeAt(0)
            }
        }
    }

    val peak = history.maxOfOrNull { maxOf(it.second, it.third) } ?: 0L
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OverviewInstruments(state, ruleCount)

        Surface(
            shape = RoundedCornerShape(20.dp),
            color = t.cardBackground,
            shadowElevation = 0.dp,
        ) {
            Column(
                Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "订阅",
                    color = t.textPrimary,
                    fontSize = 18.sp,
                    lineHeight = 23.sp,
                    fontWeight = FontWeight.ExtraBold,
                )
                Text(
                    "刷新后显示数据",
                    color = t.textSecondary,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            RefRateCard(
                "上行速度",
                upRate,
                Icons.Rounded.ArrowUpward,
                Color(0xFF20AF67),
                Modifier.weight(1f),
            )
            RefRateCard(
                "下行速度",
                downRate,
                Icons.Rounded.ArrowDownward,
                HetuMicroCrystal.KleinBlue,
                Modifier.weight(1f),
            )
        }

        Surface(
            shape = RoundedCornerShape(20.dp),
            color = t.cardBackground,
            shadowElevation = 0.dp,
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 15.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "总流量",
                    color = t.textPrimary,
                    fontSize = 17.sp,
                    lineHeight = 22.sp,
                    fontWeight = FontWeight.ExtraBold,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    "上行 ${refBytes(state.uploadTotal)}",
                    color = Color(0xFF20AF67),
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.ExtraBold,
                )
                Text(
                    "  /  ",
                    color = t.textSecondary,
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "下行 ${refBytes(state.downloadTotal)}",
                    color = HetuMicroCrystal.KleinBlue,
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.ExtraBold,
                )
            }
        }

        Surface(
            shape = RoundedCornerShape(20.dp),
            color = t.cardBackground,
            shadowElevation = 0.dp,
        ) {
            Column(
                Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "近期趋势",
                            color = t.textPrimary,
                            fontSize = 18.sp,
                            lineHeight = 23.sp,
                            fontWeight = FontWeight.ExtraBold,
                        )
                        Text(
                            "最近 60 秒",
                            color = t.textSecondary,
                            fontSize = 12.5.sp,
                            lineHeight = 17.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    RefTrendLegend("上行", Color(0xFF20AF67))
                    Spacer(Modifier.width(8.dp))
                    RefTrendLegend("下行", HetuMicroCrystal.KleinBlue)
                }

                val points = history.toList()
                val chartGrid = if (scheme.background.luminance() < .5f) {
                    Color.White.copy(alpha = .08f)
                } else {
                    Color(0xFFDCD9E8)
                }

                Canvas(Modifier.fillMaxWidth().height(190.dp).testTag("overview-trend-chart")) {
                    val inset = 6.dp.toPx()
                    val chartHeight = size.height - inset * 2f
                    for (line in 0..2) {
                        val y = inset + chartHeight * line / 2f
                        drawLine(
                            chartGrid,
                            Offset(0f, y),
                            Offset(size.width, y),
                            1.dp.toPx(),
                        )
                    }

                    if (points.size >= 2) {
                        val maxRate = peak.coerceAtLeast(1L).toFloat()
                        val end = points.last().first

                        fun drawSeries(upload: Boolean, color: Color) {
                            val rates = points.indices.map { index ->
                                val from = (index - 1).coerceAtLeast(0)
                                val to = (index + 1).coerceAtMost(points.lastIndex)
                                var total = 0.0
                                var count = 0
                                for (sample in from..to) {
                                    total += if (upload) points[sample].second.toDouble() else points[sample].third.toDouble()
                                    count++
                                }
                                (total / count.coerceAtLeast(1)).toFloat()
                            }

                            val offsets = points.mapIndexed { index, point ->
                                val x = (
                                    (point.first - (end - 60_000L)).coerceIn(0L, 60_000L) /
                                        60_000f
                                    ) * size.width
                                val y = size.height - inset - rates[index] / maxRate * chartHeight
                                Offset(x, y)
                            }

                            val path = Path().apply {
                                moveTo(offsets.first().x, offsets.first().y)
                                for (index in 1 until offsets.size) {
                                    val previous = offsets[index - 1]
                                    val current = offsets[index]
                                    val controlX = (previous.x + current.x) / 2f
                                    cubicTo(
                                        controlX, previous.y,
                                        controlX, current.y,
                                        current.x, current.y,
                                    )
                                }
                            }
                            val area = Path().apply {
                                addPath(path)
                                lineTo(offsets.last().x, size.height - inset)
                                lineTo(offsets.first().x, size.height - inset)
                                close()
                            }

                            drawPath(
                                area,
                                Brush.verticalGradient(
                                    colors = listOf(
                                        color.copy(alpha = .22f),
                                        color.copy(alpha = .07f),
                                        color.copy(alpha = 0f),
                                    ),
                                    startY = inset,
                                    endY = size.height - inset,
                                ),
                            )
                            drawPath(
                                path,
                                color,
                                style = Stroke(
                                    2.5.dp.toPx(),
                                    cap = androidx.compose.ui.graphics.StrokeCap.Round,
                                ),
                            )
                        }

                        drawSeries(true, Color(0xFF20AF67))
                        drawSeries(false, HetuMicroCrystal.KleinBlue)
                    }
                }

                Text(
                    if (points.size < 2) "正在收集真实采样" else "峰值 ${refSpeed(peak)}",
                    color = t.textSecondary,
                    fontSize = 11.5.sp,
                    lineHeight = 16.sp,
                )
            }
        }

        OverviewRouteRanking(state)
    }
}

@Composable
private fun RefTrendLegend(label: String, color: Color) {
    Box(
        Modifier
            .clip(CircleShape)
            .background(color.copy(alpha = .10f))
            .padding(horizontal = 11.dp, vertical = 5.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(7.dp).background(color, CircleShape))
            Spacer(Modifier.width(6.dp))
            Text(
                label,
                color = color,
                fontSize = 12.sp,
                fontWeight = FontWeight.ExtraBold,
            )
        }
    }
}

@Composable
private fun RefRateCard(
    title: String,
    value: Long,
    icon: ImageVector,
    color: Color,
    modifier: Modifier,
) {
    val t = LocalHetuTokens.current
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        color = t.cardBackground,
        shadowElevation = 0.dp,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(40.dp)
                    .background(color.copy(alpha = .10f), RoundedCornerShape(13.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, null, tint = color, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(
                    title,
                    color = t.textPrimary,
                    fontSize = 12.5.sp,
                    lineHeight = 17.sp,
                    fontWeight = FontWeight.ExtraBold,
                )
                HetuNumber(
                    refSpeed(value),
                    color = color,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontSize = 18.sp,
                        lineHeight = 23.sp,
                        fontWeight = FontWeight.ExtraBold,
                    ),
                )
            }
        }
    }
}

@Composable
internal fun RefProviderRow(
    item: DashboardProviderUi,
    refreshing: Boolean,
    success: Boolean,
    onRefresh: () -> Unit,
    onClick: () -> Unit,
    error: String = "",
) {
    val t = LocalHetuTokens.current
    val primary = HetuMicroCrystal.KleinBlue
    val known = item.hasSubscriptionInfo && item.total > 0L
    val shape = RoundedCornerShape(20.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(t.cardBackground)
            .clickable(onClick = onClick)
            .padding(16.dp)
            .testTag("subscription-provider:${item.name}"),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                item.name,
                Modifier.weight(1f),
                color = t.textPrimary,
                fontSize = 18.sp,
                lineHeight = 23.sp,
                fontWeight = FontWeight.ExtraBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (known) {
                Box(
                    Modifier
                        .clip(RoundedCornerShape(11.dp))
                        .background(Color(0xFFE2E8FA))
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                ) {
                    Text(
                        "${((1f - item.ratio) * 100f).toInt()}%",
                        color = primary,
                        fontSize = 14.sp,
                        lineHeight = 18.sp,
                        fontWeight = FontWeight.ExtraBold,
                    )
                }
            }
            Spacer(Modifier.width(4.dp))
            WorkspaceRefreshAction(
                item.name,
                refreshing,
                success,
                error.isNotBlank(),
                actionLabel = "更新订阅",
                onClick = onRefresh,
            )
        }

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                ticketExpireAt(item.expire),
                Modifier.weight(1f),
                color = t.textSecondary,
                fontSize = 12.5.sp,
                lineHeight = 17.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                refUpdatedAt(item.updatedAt),
                color = t.textSecondary,
                fontSize = 12.5.sp,
                lineHeight = 17.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
        }

        if (known) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RefProviderMetric("上传", refBytes(item.upload), Modifier.weight(1f))
                RefProviderMetric("下载", refBytes(item.download), Modifier.weight(1f))
                RefProviderMetric("剩余", refBytes(item.remaining), Modifier.weight(1f), primary)
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(CircleShape)
                    .background(Color(0xFFD7E0F5)),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(item.ratio.coerceIn(.01f, 1f))
                        .fillMaxHeight()
                        .background(primary, CircleShape),
                )
            }
            Row(Modifier.fillMaxWidth()) {
                Text(
                    "已用 ${refBytes(item.used)}",
                    Modifier.weight(1f),
                    color = t.textSecondary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "总计 ${refBytes(item.total)}",
                    color = t.textSecondary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        } else {
            Text(
                "订阅未上报流量信息",
                color = t.textSecondary,
                fontSize = 12.sp,
                lineHeight = 18.sp,
            )
        }

        if (error.isNotBlank() && !refreshing) {
            HetuTaskFeedback("${item.name} 更新失败：$error", error = true)
        }
    }
}

@Composable
private fun RefProviderMetric(
    label: String,
    value: String,
    modifier: Modifier,
    valueColor: Color = LocalHetuTokens.current.textPrimary,
) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            value,
            color = valueColor,
            fontSize = 18.sp,
            lineHeight = 22.sp,
            fontWeight = FontWeight.ExtraBold,
            maxLines = 1,
        )
        Text(
            label,
            color = LocalHetuTokens.current.textSecondary,
            fontSize = 11.5.sp,
            lineHeight = 16.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

private fun refExpireDays(expire: Long): String {
    if (expire <= 0L) return "—"
    val millis = if (expire < 10_000_000_000L) expire * 1000L else expire
    val days = ((millis - System.currentTimeMillis()) / 86_400_000L)
    return if (days < 0L) "已到期" else "${days} 天"
}

private fun refExpireDate(expire: Long): String {
    if (expire <= 0L) return "—"
    val millis = if (expire < 10_000_000_000L) expire * 1000L else expire
    return runCatching { java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).format(java.util.Date(millis)) }.getOrDefault("—")
}

private data class RefConnectionAppGroup(
    val key: String,
    val title: String,
    val subtitle: String,
    val icon: android.graphics.Bitmap?,
    val connections: List<ProxyConnectionUi>,
) {
    val upload: Long get() = connections.sumOf { it.upload }
    val download: Long get() = connections.sumOf { it.download }
}

private fun refConnectionGroups(items: List<ProxyConnectionUi>): List<RefConnectionAppGroup> {
    if (items.isEmpty()) return emptyList()
    val buckets = LinkedHashMap<String, MutableList<ProxyConnectionUi>>()
    items.forEach { item ->
        val key = when {
            item.packageName.isNotBlank() -> "pkg:" + item.packageName
            item.uid > 0 -> "uid:" + item.uid
            item.process.isNotBlank() -> "proc:" + item.process.substringAfterLast('/').substringBefore(':')
            else -> "unknown"
        }
        buckets.getOrPut(key) { ArrayList() } += item
    }
    return buckets.map { (key, connections) ->
        val first = connections.first()
        val process = first.process.substringAfterLast('/').substringBefore(':')
        val title = when {
            first.appName.isNotBlank() -> first.appName
            first.packageName.isNotBlank() -> first.packageName
            process.isNotBlank() -> process
            first.uid == 0 -> "系统服务"
            first.uid > 0 -> "UID ${first.uid}"
            else -> "未知应用"
        }
        val subtitle = when {
            first.packageName.isNotBlank() -> first.packageName
            first.uid > 0 -> "UID ${first.uid}" + if (process.isNotBlank()) " · $process" else ""
            process.isNotBlank() -> process
            else -> "未识别到应用信息"
        }
        RefConnectionAppGroup(key, title, subtitle, first.appIcon, connections)
    // Keep an application's card in place while its live connection count changes.
    }.sortedWith(compareBy<RefConnectionAppGroup> { it.title.lowercase() }.thenBy { it.key })
}

@Composable
private fun RefConnectionAppCard(
    group: RefConnectionAppGroup,
    expanded: Boolean,
    onToggle: () -> Unit,
    onCloseAll: (() -> Unit)?,
    onCloseConnection: ((ProxyConnectionUi) -> Unit)?,
) {
    val t = LocalHetuTokens.current
    val dark = MaterialTheme.colorScheme.background.luminance() < .5f
    var connectionPage by rememberSaveable(group.key) { mutableIntStateOf(0) }
    val lastPage = ((group.connections.size - 1).coerceAtLeast(0)) / 30
    val currentPage = connectionPage.coerceAtMost(lastPage)
    val firstConnection = currentPage * 30
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = t.cardBackground,
        shadowElevation = 0.dp,
    ) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(13.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(46.dp)
                        .background(if (dark) Color.White.copy(alpha = .06f) else t.pageBackground, RoundedCornerShape(13.dp))
                        .clip(RoundedCornerShape(13.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    val bitmap = group.icon
                    if (bitmap != null) {
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = group.title,
                            modifier = Modifier.fillMaxSize().padding(5.dp),
                            contentScale = ContentScale.Fit,
                        )
                    } else {
                        Icon(
                            if (group.key.startsWith("uid:0") || group.title == "系统服务") Icons.Rounded.Memory else Icons.Rounded.Apps,
                            null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }
                Spacer(Modifier.width(11.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            group.title,
                            color = t.textPrimary,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            maxLines = if (expanded) Int.MAX_VALUE else 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Surface(
                            shape = RoundedCornerShape(999.dp),
                            color = MaterialTheme.colorScheme.primary.copy(alpha = .10f),
                        ) {
                            Text(
                                "${group.connections.size} 连接",
                                color = MaterialTheme.colorScheme.primary,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            )
                        }
                    }
                    if (group.subtitle != group.title) Text(group.subtitle, color = t.textSecondary, fontSize = 12.sp, maxLines = if (expanded) Int.MAX_VALUE else 1, overflow = TextOverflow.Ellipsis)
                    val totalUpload = group.upload
                    val totalDownload = group.download
                    var previousUpload by remember(group.key) { mutableLongStateOf(totalUpload) }
                    var previousDownload by remember(group.key) { mutableLongStateOf(totalDownload) }
                    var previousAt by remember(group.key) { mutableLongStateOf(0L) }
                    var uploadRate by remember(group.key) { mutableLongStateOf(0L) }
                    var downloadRate by remember(group.key) { mutableLongStateOf(0L) }
                    LaunchedEffect(totalUpload, totalDownload) {
                        val now = SystemClock.elapsedRealtime()
                        if (previousAt > 0L && now > previousAt && totalUpload >= previousUpload && totalDownload >= previousDownload) {
                            val elapsed = now - previousAt
                            uploadRate = ((totalUpload - previousUpload) * 1000L / elapsed).coerceAtLeast(0L)
                            downloadRate = ((totalDownload - previousDownload) * 1000L / elapsed).coerceAtLeast(0L)
                        }
                        previousUpload = totalUpload
                        previousDownload = totalDownload
                        previousAt = now
                    }
                    Text(
                        "↑ ${refSpeed(uploadRate)} · ${refBytes(totalUpload)}    ↓ ${refSpeed(downloadRate)} · ${refBytes(totalDownload)}",
                        color = t.textMuted,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                Spacer(Modifier.width(8.dp))
                Icon(
                    if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                    if (expanded) "收起连接" else "展开连接",
                    tint = t.textMuted,
                    modifier = Modifier.size(21.dp),
                )
            }
            androidx.compose.animation.AnimatedVisibility(
                visible = expanded,
                enter = androidx.compose.animation.expandVertically(
                    expandFrom = Alignment.Top,
                    animationSpec = spring(dampingRatio = .72f, stiffness = 360f),
                    clip = false,
                ) + androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(150)),
                exit = androidx.compose.animation.shrinkVertically(
                    shrinkTowards = Alignment.Top,
                    animationSpec = spring(dampingRatio = .84f, stiffness = 460f),
                    clip = false,
                ) + androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(110)),
            ) {
                Column(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    HorizontalDivider(color = t.outline.copy(alpha = .35f))
                    if (onCloseAll != null && group.connections.size > 1) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            TextButton(onClick = onCloseAll) {
                                Text("终止这 ${group.connections.size} 条连接", color = t.danger, fontSize = 12.sp)
                            }
                        }
                    }
                    group.connections.drop(firstConnection).take(30).forEachIndexed { index, item ->
                        androidx.compose.animation.AnimatedVisibility(
                            visible = expanded,
                            enter = androidx.compose.animation.fadeIn(
                                androidx.compose.animation.core.tween(150, delayMillis = 0),
                            ) + androidx.compose.animation.slideInVertically(
                                androidx.compose.animation.core.tween(180, delayMillis = 0),
                            ) { it / 5 },
                        ) {
                            RefConnectionRow(item, onCloseConnection?.let { action -> { action(item) } })
                        }
                    }
                    if (group.connections.size > 30) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = { connectionPage = currentPage - 1 }, enabled = currentPage > 0) { Text("上一页") }
                            Text("${firstConnection + 1}–${(firstConnection + 30).coerceAtMost(group.connections.size)} / ${group.connections.size}",
                                Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                color = t.textSecondary, style = MaterialTheme.typography.labelMedium)
                            TextButton(onClick = { connectionPage = currentPage + 1 }, enabled = currentPage < lastPage) { Text("下一页") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RefConnectionRow(item: ProxyConnectionUi, onClose: (() -> Unit)?) {
    val t = LocalHetuTokens.current
    val dark = MaterialTheme.colorScheme.background.luminance() < .5f
    val closeSource = remember(item.id) { MutableInteractionSource() }
    val closePressed by closeSource.collectIsPressedAsState()
    val closeScale by animateFloatAsState(
        targetValue = if (closePressed) .88f else 1f,
        animationSpec = spring(dampingRatio = .70f, stiffness = 650f),
        label = "closeConnectionPress${item.id}",
    )
    Surface(shape = RoundedCornerShape(16.dp), color = t.cardBackground, shadowElevation = 0.dp) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(item.host, color = t.textPrimary, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(listOf(item.network, item.inbound).filter { it.isNotBlank() }.joinToString(" · "), color = t.textSecondary, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                Text(item.chain.ifBlank { item.rule.ifBlank { "DIRECT" } }, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                var previousUpload by remember(item.id) { mutableLongStateOf(item.upload) }
                var previousDownload by remember(item.id) { mutableLongStateOf(item.download) }
                var previousAt by remember(item.id) { mutableLongStateOf(0L) }
                var uploadRate by remember(item.id) { mutableLongStateOf(0L) }
                var downloadRate by remember(item.id) { mutableLongStateOf(0L) }
                LaunchedEffect(item.upload, item.download) {
                    val now = SystemClock.elapsedRealtime()
                    if (previousAt > 0L && now > previousAt && item.upload >= previousUpload && item.download >= previousDownload) {
                        val elapsed = now - previousAt
                        uploadRate = ((item.upload - previousUpload) * 1000L / elapsed).coerceAtLeast(0L)
                        downloadRate = ((item.download - previousDownload) * 1000L / elapsed).coerceAtLeast(0L)
                    }
                    previousUpload = item.upload
                    previousDownload = item.download
                    previousAt = now
                }
                Text(
                    "↑ ${refSpeed(uploadRate)} · ${refBytes(item.upload)}    ↓ ${refSpeed(downloadRate)} · ${refBytes(item.download)}",
                    color = t.textMuted,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            if (onClose != null) {
                Spacer(Modifier.width(8.dp))
                Box(
                    Modifier.size(48.dp)
                        .graphicsLayer { scaleX = closeScale; scaleY = closeScale }
                        .background(if (dark) Color.White.copy(alpha = .07f) else t.pageBackground.copy(alpha = .82f), CircleShape)
                        .clip(CircleShape)
                        .clickable(interactionSource = closeSource, indication = null, onClick = onClose),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Rounded.Close,
                        "终止连接",
                        tint = if (closePressed) t.danger else Color(0xFF64748B),
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    }
}

@Composable
internal fun RefRuleGroupCard(items: List<ProxyRuleUi>) {
    val t = LocalHetuTokens.current
    val dark = MaterialTheme.colorScheme.background.luminance() < .5f
    val shape = RoundedCornerShape(22.dp)
    Surface(
        modifier = Modifier.fillMaxWidth().crystalMaterial(shape).testTag("rules-inset-group"),
        shape = shape, color = Color.Transparent, shadowElevation = 0.dp,
    ) {
        Column(Modifier.fillMaxWidth()) {
            items.forEachIndexed { index, item ->
                val expression = item.payload.ifBlank { item.type }
                val composite = expression.contains("&&") || expression.contains("||") ||
                    expression.count { it == '(' } >= 2
                var expanded by remember(item.type, item.payload, item.proxy) { mutableStateOf(false) }
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Surface(
                            modifier = Modifier.fillMaxWidth()
                                .clip(RoundedCornerShape(7.dp))
                                .clickable(
                                    enabled = composite,
                                    indication = null,
                                    interactionSource = remember(item.type, item.payload) { MutableInteractionSource() },
                                ) { expanded = !expanded },
                            shape = RoundedCornerShape(7.dp),
                            color = Color.Transparent,
                            tonalElevation = 0.dp,
                        ) {
                            androidx.compose.animation.AnimatedContent(
                                targetState = expanded,
                                transitionSpec = {
                                    androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(150))
                                        .togetherWith(androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(90)))
                                },
                                label = "ruleExpressionExpand${index}",
                            ) { open ->
                                Text(
                                    expression,
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 7.dp, vertical = 4.dp),
                                    color = if (dark) Color(0xFFCBD5E1) else Color(0xFF475569),
                                    fontSize = 11.sp,
                                    lineHeight = 15.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                    maxLines = if (open) Int.MAX_VALUE else 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        Text(
                            if (composite) "${item.type} · 点击${if (expanded) "收起" else "展开"}" else item.type,
                            color = Color(0xFF94A3B8),
                            fontSize = 10.sp,
                            lineHeight = 13.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    val reject = item.proxy.equals("REJECT", true) || item.proxy.startsWith("REJECT-", true)
                    val direct = item.proxy.equals("DIRECT", true)
                    Text(
                        item.proxy,
                        color = when {
                            reject -> Color(0xFFF43F5E)
                            direct -> Color(0xFF2563EB)
                            else -> Color(0xFF64748B)
                        },
                        fontSize = 12.sp,
                        lineHeight = 15.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        letterSpacing = .45.sp,
                        maxLines = 1,
                    )
                }
                if (index != items.lastIndex) {
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        thickness = .5.dp,
                        color = if (dark) t.outline.copy(alpha = .28f) else t.pageBackground.copy(alpha = .84f),
                    )
                }
            }
        }
    }
}

@Composable
internal fun RefRuleSetRow(item: DashboardRuleSetUi, refreshing: Boolean, success: Boolean, error: String = "", onRefresh: () -> Unit) {
    val t = LocalHetuTokens.current
    Column(Modifier.fillMaxWidth().crystalMaterial(RoundedCornerShape(22.dp)).padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(item.name, color = t.textPrimary, fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold)
                HetuNumber("${java.text.NumberFormat.getIntegerInstance(java.util.Locale.US).format(item.ruleCount)} 条规则",
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, lineHeight = 20.sp))
                Text(listOf(item.behavior, item.format, item.vehicleType).filter { it.isNotBlank() }.joinToString(" / "),
                    color = t.textSecondary, fontSize = 12.sp, lineHeight = 18.sp)
                Text(refUpdatedAt(item.updatedAt), color = t.textSecondary, fontSize = 12.sp, lineHeight = 18.sp)
            }
            WorkspaceRefreshAction(item.name, refreshing, success, error.isNotBlank(), actionLabel = "更新规则集", onClick = onRefresh)
        }
        if (error.isNotBlank() && !refreshing) HetuTaskFeedback("${item.name} 更新失败：$error", error = true)
    }
}

@Composable
private fun RefTools(state: ProxyComposeState, onLog: (String) -> Unit) {
    val context = LocalContext.current
    val inspector = remember { ProxyRuntimeInspector(context) }
    val scope = rememberCoroutineScope()

    LazyColumn(
        Modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = PaddingValues(
            start = 12.dp,
            top = 8.dp,
            end = 12.dp,
            bottom = hetuContentBottomPadding(),
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { RefTitleBar("工具") }

        item {
            RefGroup {
                RefToolRow(
                    Icons.Rounded.FolderOpen,
                    Color.Unspecified,
                    "文件管理",
                    "查看并管理运行文件",
                ) {
                    context.startActivity(Intent(context, ReferenceFileManagerActivity::class.java))
                }
                RefDivider()
                RefToolRow(
                    Icons.Rounded.Article,
                    Color.Unspecified,
                    "日志查看",
                    "查看运行日志与排查问题",
                ) {
                    scope.launch {
                        onLog(runCatching { inspector.runtimeLog() }.getOrElse { it.message ?: "日志读取失败" })
                    }
                }
                RefDivider()
                RefToolRow(
                    Icons.Rounded.Apps,
                    Color.Unspecified,
                    "应用管理",
                    "查看并管理应用相关规则",
                ) {
                    context.startActivity(Intent(context, ProxyAppSelectionActivity::class.java))
                }
            }
        }

        item {
            RefGroup {
                RefToolRow(
                    Icons.Rounded.Wifi,
                    Color.Unspecified,
                    "网络匹配",
                    "设置网络匹配后要执行的操作",
                ) {
                    context.startActivity(Intent(context, ProxyNetworkAutomationActivity::class.java))
                }
                RefDivider()
                RefToolRow(
                    Icons.Rounded.WifiTethering,
                    Color.Unspecified,
                    "共享网络",
                    "管理共享网络转发相关设置",
                ) {
                    context.startActivity(Intent(context, ProxySharedNetworkSettingsActivity::class.java))
                }
                RefDivider()
                RefToolRow(
                    Icons.Rounded.AltRoute,
                    Color.Unspecified,
                    "绕过规则",
                    "管理本地 CIDR 与接口规则",
                ) {
                    context.startActivity(Intent(context, ProxyBypassRulesActivity::class.java))
                }
            }
        }

        item {
            RefGroup {
                RefToolRow(
                    Icons.Rounded.Link,
                    Color.Unspecified,
                    "订阅管理",
                    "配置订阅源并更新规则数据",
                ) {
                    context.startActivity(Intent(context, ProxySubscriptionActivity::class.java))
                }
                RefDivider()
                RefToolRow(
                    Icons.Rounded.Place,
                    Color.Unspecified,
                    "CNIP 设置",
                    "配置 CNIP 数据源并更新地理数据",
                ) {
                    context.startActivity(Intent(context, ProxyCnIpSettingsActivity::class.java))
                }
            }
        }

        item {
            RefGroup {
                RefToolRow(
                    Icons.Rounded.Web,
                    Color.Unspecified,
                    "更新 WebUI",
                    "检查并更新 WebUI 资源",
                    trailingText = "更新",
                    trailingColor = HetuMicroCrystal.KleinBlue,
                ) {
                    context.startActivity(Intent(context, ProxyLocalWebUiActivity::class.java))
                }
                RefDivider()
                RefToolRow(
                    Icons.Rounded.Download,
                    Color.Unspecified,
                    "更新核心",
                    "下载并安装核心",
                    trailingText = state.core.ifBlank { "Mihomo" },
                    trailingColor = HetuMicroCrystal.KleinBlue,
                ) {
                    context.startActivity(Intent(context, ProxyCoreActivity::class.java))
                }
                RefDivider()
                RefToolRow(
                    Icons.Rounded.Shield,
                    Color.Unspecified,
                    "广告过滤",
                    "订阅规则、放行与拦截记录",
                ) {
                    context.startActivity(Intent(context, ProxyAdblockChainActivity::class.java))
                }
            }
        }
    }
}

@Composable
private fun RefSettings(state: ProxyComposeState, operation: String, onApplySettings: () -> Unit, onChanged: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("hetu", 0) }
    var modePicker by remember { mutableStateOf(false) }
    var ipv6Picker by remember { mutableStateOf(false) }
    var latencyPicker by remember { mutableStateOf(false) }
    var portsInfo by remember { mutableStateOf(false) }
    var notificationSettings by remember { mutableStateOf(false) }
    var baseSettings by remember { mutableStateOf(false) }
    var autoStart by remember { mutableStateOf(prefs.getBoolean("proxyRootAutoStart", false)) }
    var statusNotificationEnabled by remember { mutableStateOf(prefs.getBoolean(ProxyStatusNotificationService.PREF_ENABLED, false)) }
    var blurEnabled by remember { mutableStateOf(prefs.getBoolean("enableBlur", true)) }
    var latencyInterval by remember { mutableIntStateOf(prefs.getInt("latencyAutoRefreshSeconds", 0).takeIf { it == 0 || it == 30 || it == 60 } ?: 0) }

    val pending = state.runtimeSettingsPending || prefs.getBoolean("proxyRootRuntimeRefreshPending", false)
    val t = LocalHetuTokens.current

    LazyColumn(
        Modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = PaddingValues(
            start = 12.dp,
            top = 8.dp,
            end = 12.dp,
            bottom = hetuContentBottomPadding(),
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { RefTitleBar("设置") }

        item {
            RefGroup {
                RefToolRow(
                    Icons.Rounded.Tune,
                    Color.Unspecified,
                    "基础代理配置",
                    "配置核心 模式 IPv6 和当前配置",
                ) {
                    baseSettings = true
                }
                RefDivider()
                RefToolRow(
                    Icons.Rounded.Dns,
                    Color.Unspecified,
                    "其他代理配置",
                    "调整端口 DNS 劫持与资源限制",
                ) {
                    context.startActivity(Intent(context, ProxyAdvancedSettingsActivity::class.java))
                }
            }
        }

        item {
            RefGroup {
                RefValueRow(
                    "语言",
                    "跟随系统",
                    Icons.Rounded.Translate,
                    Color.Unspecified,
                    highlightValue = false,
                )
                RefDivider()
                RefToolRow(
                    Icons.Rounded.FormatPaint,
                    Color.Unspecified,
                    "主题设置",
                    "调整主题 模糊 底栏和缩放",
                ) {
                    context.startActivity(Intent(context, ThemeSettingsActivity::class.java))
                }
            }
        }

        item {
            RefGroup {
                RefSwitchRow(
                    icon = Icons.Rounded.PowerSettingsNew,
                    accent = Color.Unspecified,
                    title = "开机自启",
                    subtitle = "重启后自动恢复上次启用的代理服务",
                    checked = autoStart,
                ) { enabled ->
                    autoStart = enabled
                    prefs.edit().putBoolean("proxyRootAutoStart", enabled).apply()
                }
                RefDivider()
                RefSwitchRow(
                    icon = Icons.Rounded.Notifications,
                    accent = Color.Unspecified,
                    title = "通知",
                    subtitle = "启用代理状态通知与快捷控制",
                    checked = statusNotificationEnabled,
                ) { enabled ->
                    statusNotificationEnabled = enabled
                    ProxyStatusNotificationService.setEnabled(context, enabled)
                }
                RefDivider()
                RefToolRow(
                    Icons.Rounded.Tune,
                    Color.Unspecified,
                    "通知内容与按钮",
                    "模板变量与快捷操作",
                ) {
                    notificationSettings = true
                }
            }
        }

        if (pending) {
            item {
                RefGroup {
                    Column(
                        Modifier.fillMaxWidth().padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(9.dp),
                    ) {
                        Text(
                            "有设置等待应用",
                            color = t.textPrimary,
                            fontSize = 15.sp,
                            lineHeight = 20.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            "应用后会短暂重连代理。",
                            color = t.textSecondary,
                            fontSize = 12.sp,
                            lineHeight = 17.sp,
                        )
                        Button(
                            onClick = onApplySettings,
                            enabled = operation.isBlank(),
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                            shape = CircleShape,
                        ) {
                            if (operation.isNotBlank()) {
                                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(8.dp))
                            }
                            Text(if (operation.isNotBlank()) "正在应用…" else "应用设置并重启")
                        }
                    }
                }
            }
        }
    }

    if (baseSettings) {
        ModalBottomSheet(
            onDismissRequest = { baseSettings = false },
            containerColor = t.cardBackground,
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        ) {
            Column(
                Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    "基础代理配置",
                    color = t.textPrimary,
                    fontSize = 22.sp,
                    lineHeight = 28.sp,
                    fontWeight = FontWeight.ExtraBold,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
                )
                WorkspaceSettingRow(
                    "运行核心",
                    state.core.ifBlank { "Mihomo" },
                    Icons.Rounded.Memory,
                    onClick = {
                        baseSettings = false
                        context.startActivity(Intent(context, ProxyRuntimeCoreSettingsActivity::class.java))
                    },
                ) {
                    Icon(Icons.Rounded.ChevronRight, null, tint = t.textMuted)
                }
                WorkspaceInsetDivider()
                WorkspaceSettingRow(
                    "运行模式",
                    state.mode,
                    Icons.Rounded.Tune,
                    onClick = {
                        baseSettings = false
                        modePicker = true
                    },
                ) {
                    Icon(Icons.Rounded.ChevronRight, null, tint = t.textMuted)
                }
                WorkspaceInsetDivider()
                WorkspaceSettingRow(
                    "IPv6",
                    ProxyRuntimeSettings.ipv6Label(state.ipv6),
                    Icons.Rounded.Public,
                    onClick = {
                        baseSettings = false
                        ipv6Picker = true
                    },
                ) {
                    Icon(Icons.Rounded.ChevronRight, null, tint = t.textMuted)
                }
                WorkspaceInsetDivider()
                WorkspaceSettingRow(
                    "当前配置",
                    state.config,
                    Icons.Rounded.Description,
                )
                Spacer(Modifier.height(8.dp))
            }
        }
    }

    if (notificationSettings) {
        RefNotificationSettingsBottomSheet(
            prefs = prefs,
            onDismiss = { notificationSettings = false },
            onSaved = {
                notificationSettings = false
                ProxyStatusNotificationService.refresh(context)
            },
        )
    }

    if (modePicker) {
        val profile = ProxyRuntimeProfile.load(prefs)
        val choices = ProxyRuntimeProfile.Mode.values().filter { ProxyRuntimeProfile.capability(profile.core, it).available }
        RefChoiceBottomSheet(
            title = "运行模式",
            options = choices.map { it.label to (profile.mode == it) },
            onDismiss = { modePicker = false },
            onSelect = { index ->
                val mode = choices[index]
                prefs.edit().putString("proxyBaseMode", mode.id).apply()
                ProxyRuntimeSettings.markDirty(prefs, "proxyBaseMode")
                modePicker = false
                android.widget.Toast.makeText(
                    context,
                    if (state.running) "运行模式已保存，重启代理后生效" else "运行模式已保存",
                    android.widget.Toast.LENGTH_SHORT,
                ).show()
                onChanged()
            },
        )
    }

    if (ipv6Picker) {
        val profile = ProxyRuntimeProfile.load(prefs)
        val values = listOf(
            ProxyRuntimeProfile.Ipv6.ENABLE to "启用 IPv6",
            ProxyRuntimeProfile.Ipv6.BYPASS to "IPv6 不进核心",
            ProxyRuntimeProfile.Ipv6.STRICT to "严格 IPv4 防泄漏",
            ProxyRuntimeProfile.Ipv6.DISABLE to "禁用系统 IPv6",
        )
        RefChoiceBottomSheet(
            title = "IPv6",
            options = values.map { (value, label) -> label to (profile.ipv6 == value) },
            onDismiss = { ipv6Picker = false },
            onSelect = { index ->
                val value = values[index].first
                prefs.edit().putString("proxyBaseIpv6", value.id).apply()
                ProxyRuntimeSettings.markDirty(prefs, "proxyBaseIpv6")
                ipv6Picker = false
                android.widget.Toast.makeText(
                    context,
                    if (state.running) "IPv6 设置已保存，重启代理后生效" else "IPv6 设置已保存",
                    android.widget.Toast.LENGTH_SHORT,
                ).show()
                onChanged()
            },
        )
    }

    if (latencyPicker) {
        val intervals = listOf(0 to "关闭", 30 to "30 秒", 60 to "60 秒")
        RefChoiceBottomSheet(
            title = "延迟自动刷新间隔",
            options = intervals.map { (seconds, label) -> label to (latencyInterval == seconds) },
            onDismiss = { latencyPicker = false },
            onSelect = { index ->
                latencyInterval = intervals[index].first
                prefs.edit().putInt("latencyAutoRefreshSeconds", latencyInterval).apply()
                latencyPicker = false
                onChanged()
            },
        )
    }

    if (portsInfo) {
        RefPortsBottomSheet(controllerPort = state.controllerPort, onDismiss = { portsInfo = false })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RefNotificationSettingsBottomSheet(
    prefs: android.content.SharedPreferences,
    onDismiss: () -> Unit,
    onSaved: () -> Unit,
) {
    val t = LocalHetuTokens.current
    var template by remember {
        mutableStateOf(
            prefs.getString(
                ProxyStatusNotificationService.PREF_TEMPLATE,
                ProxyStatusNotificationService.DEFAULT_TEMPLATE,
            ).orEmpty().ifBlank { ProxyStatusNotificationService.DEFAULT_TEMPLATE },
        )
    }
    var action1 by remember { mutableStateOf(prefs.getString(ProxyStatusNotificationService.PREF_ACTION_1, "reload").orEmpty()) }
    var action2 by remember { mutableStateOf(prefs.getString(ProxyStatusNotificationService.PREF_ACTION_2, "restart").orEmpty()) }
    val actions = listOf(
        "reload" to "重载",
        "restart" to "重启",
        "stop" to "停止",
        "hide" to "隐藏通知",
        "none" to "无按钮",
    )
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp),
        containerColor = t.elevatedCardBackground,
        contentColor = t.textPrimary,
        tonalElevation = 0.dp,
        scrimColor = Color.Black.copy(alpha = .35f),
        dragHandle = { RefSheetDragHandle() },
    ) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().imePadding()
                .padding(start = 18.dp, end = 18.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("状态通知", color = t.textPrimary, fontSize = 20.sp, lineHeight = 25.sp, fontWeight = FontWeight.Bold)
            Text(
                "可用变量：{status} {uptime} {upload} {download} {cpu} {memory} {connections} {config} {core} {mode}",
                color = t.textSecondary,
                fontSize = 11.sp,
                lineHeight = 17.sp,
            )
            OutlinedTextField(
                value = template,
                onValueChange = { template = it.take(320) },
                modifier = Modifier.fillMaxWidth().heightIn(min = 104.dp),
                label = { Text("通知正文模板") },
                minLines = 3,
                maxLines = 5,
                shape = RoundedCornerShape(16.dp),
            )
            Text("第一个快捷按钮", color = t.textSecondary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                actions.forEach { (value, label) ->
                    FilterChip(selected = action1 == value, onClick = { action1 = value }, label = { Text(label) })
                }
            }
            Text("第二个快捷按钮", color = t.textSecondary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                actions.forEach { (value, label) ->
                    FilterChip(selected = action2 == value, onClick = { action2 = value }, label = { Text(label) })
                }
            }
            Surface(shape = RoundedCornerShape(16.dp), color = t.controlBackground.copy(alpha = .48f)) {
                Column(Modifier.fillMaxWidth().padding(13.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("示例", color = t.textMuted, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    Text(
                        template
                            .replace("{status}", "运行中")
                            .replace("{uptime}", "11小时49分")
                            .replace("{upload}", "1.3 KB/s")
                            .replace("{download}", "2.1 KB/s")
                            .replace("{cpu}", "5.7%")
                            .replace("{memory}", "88.0 MB")
                            .replace("{connections}", "16")
                            .replace("{config}", "自用_tproxy.yaml")
                            .replace("{core}", "Mihomo")
                            .replace("{mode}", "TPROXY"),
                        color = t.textPrimary,
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                    )
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TextButton(onClick = {
                    template = ProxyStatusNotificationService.DEFAULT_TEMPLATE
                    action1 = "reload"
                    action2 = "restart"
                }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                    Text("恢复默认")
                }
                Button(
                    onClick = {
                        prefs.edit()
                            .putString(ProxyStatusNotificationService.PREF_TEMPLATE, template.ifBlank { ProxyStatusNotificationService.DEFAULT_TEMPLATE })
                            .putString(ProxyStatusNotificationService.PREF_ACTION_1, action1)
                            .putString(ProxyStatusNotificationService.PREF_ACTION_2, action2)
                            .apply()
                        onSaved()
                    },
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                ) { Text("保存") }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RefPortsBottomSheet(controllerPort: Int, onDismiss: () -> Unit) {
    val t = LocalHetuTokens.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp),
        containerColor = t.elevatedCardBackground,
        contentColor = t.textPrimary,
        tonalElevation = 0.dp,
        scrimColor = Color.Black.copy(alpha = .32f),
        dragHandle = { RefSheetDragHandle() },
    ) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 18.dp, end = 18.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("端口与控制器细则", color = t.textPrimary, fontSize = 20.sp, lineHeight = 25.sp, fontWeight = FontWeight.Black)
            Text("当前代理运行时副本使用的网络端口与外部控制接口", color = t.textSecondary, fontSize = 12.sp, lineHeight = 18.sp)
            Surface(shape = RoundedCornerShape(18.dp), color = t.controlBackground.copy(alpha = .58f), tonalElevation = 0.dp) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp)) {
                    RefPortDetailRow("TProxy 端口", MihomoStartupConfig.TPROXY_PORT.toString())
                    HorizontalDivider(color = t.outline.copy(alpha = .32f))
                    RefPortDetailRow("Redirect 端口", MihomoStartupConfig.REDIRECT_PORT.toString())
                    HorizontalDivider(color = t.outline.copy(alpha = .32f))
                    RefPortDetailRow("外部控制器", "127.0.0.1:$controllerPort", Color(0xFF2563EB))
                }
            }
            FilledTonalButton(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = CircleShape,
                colors = ButtonDefaults.filledTonalButtonColors(containerColor = t.controlBackground, contentColor = t.textPrimary),
            ) {
                Text("完成", fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun RefPortDetailRow(label: String, value: String, valueColor: Color = LocalHetuTokens.current.textPrimary) {
    val t = LocalHetuTokens.current
    Row(Modifier.fillMaxWidth().padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = t.textSecondary, fontSize = 12.sp, modifier = Modifier.weight(1f))
        Text(
            value,
            color = valueColor,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RefChoiceBottomSheet(
    title: String,
    options: List<Pair<String, Boolean>>,
    onDismiss: () -> Unit,
    onSelect: (Int) -> Unit,
) {
    val t = LocalHetuTokens.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp),
        containerColor = t.elevatedCardBackground,
        contentColor = t.textPrimary,
        tonalElevation = 0.dp,
        scrimColor = Color.Black.copy(alpha = .35f),
        dragHandle = { RefSheetDragHandle() },
    ) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 18.dp, end = 18.dp, bottom = 18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(title, color = t.textPrimary, fontSize = 20.sp, lineHeight = 25.sp, fontWeight = FontWeight.Bold)
            options.forEachIndexed { index, option ->
                val selected = option.second
                val shape = RoundedCornerShape(15.dp)
                Row(
                    Modifier.fillMaxWidth()
                        .clip(shape)
                        .background(if (selected) t.selectionBackground else t.controlBackground.copy(alpha = .42f), shape)
                        .clickable { onSelect(index) }
                        .padding(horizontal = 14.dp, vertical = 13.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(option.first, color = t.textPrimary, fontSize = 15.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium, modifier = Modifier.weight(1f))
                    if (selected) Icon(Icons.Rounded.Check, "已选择", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RefConfirmBottomSheet(
    title: String,
    description: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val t = LocalHetuTokens.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp),
        containerColor = t.elevatedCardBackground,
        contentColor = t.textPrimary,
        tonalElevation = 0.dp,
        scrimColor = Color.Black.copy(alpha = .35f),
        dragHandle = { RefSheetDragHandle() },
    ) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 18.dp, end = 18.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    Modifier.size(38.dp).background(t.danger.copy(alpha = .10f), RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Rounded.LinkOff, null, Modifier.size(20.dp), tint = t.danger)
                }
                Text(title, color = t.textPrimary, fontSize = 20.sp, lineHeight = 25.sp, fontWeight = FontWeight.Bold)
            }
            Text(description, color = t.textSecondary, fontSize = 13.sp, lineHeight = 20.sp)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                ) { Text("取消") }
                Button(
                    onClick = onConfirm,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = t.danger),
                ) { Text(confirmLabel) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RefInfoBottomSheet(
    title: String,
    text: String,
    actionLabel: String,
    onDismiss: () -> Unit,
) {
    val t = LocalHetuTokens.current
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    var copied by remember(text) { mutableStateOf(false) }
    val terminal = title.contains("日志")
    val renderedText = remember(text, terminal) {
        if (!terminal) {
            androidx.compose.ui.text.AnnotatedString(text)
        } else {
            androidx.compose.ui.text.buildAnnotatedString {
                val lines = text.lines()
                lines.forEachIndexed { index, line ->
                    val lower = line.lowercase(java.util.Locale.ROOT)
                    val color = when {
                        "warning" in lower || "warn" in lower -> Color(0xFFFBBF24)
                        "error" in lower || "fatal" in lower -> Color(0xFFFB7185)
                        "direct" in lower -> Color(0xFF34D399)
                        "[tcp]" in lower || " tcp " in lower -> Color(0xFF22D3EE)
                        "[udp]" in lower || " udp " in lower -> Color(0xFFA78BFA)
                        else -> Color(0xFFCBD5E1)
                    }
                    pushStyle(androidx.compose.ui.text.SpanStyle(color = color))
                    append(line)
                    pop()
                    if (index != lines.lastIndex) append('\n')
                }
            }
        }
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp),
        containerColor = if (terminal) Color(0xFF0B1220) else t.elevatedCardBackground,
        contentColor = if (terminal) Color(0xFFE2E8F0) else t.textPrimary,
        tonalElevation = 0.dp,
        scrimColor = Color.Black.copy(alpha = if (terminal) .48f else .35f),
        dragHandle = {
            Box(
                Modifier.padding(top = 10.dp, bottom = 6.dp).size(width = 36.dp, height = 4.dp)
                    .background(if (terminal) Color(0xFF334155) else Color(0xFFCBD5E1), CircleShape),
            )
        },
    ) {
        Column(
            Modifier.fillMaxWidth().fillMaxHeight(.80f).navigationBarsPadding().padding(start = 18.dp, end = 18.dp, bottom = 18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(title, color = if (terminal) Color(0xFFF8FAFC) else t.textPrimary, fontSize = 20.sp, lineHeight = 25.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                TextButton(onClick = {
                    clipboard.setText(androidx.compose.ui.text.AnnotatedString(text))
                    copied = true
                }) { Text(if (copied) "已复制" else "复制", color = if (terminal) Color(0xFF93C5FD) else MaterialTheme.colorScheme.primary) }
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.size(48.dp).background(if (terminal) Color.White.copy(alpha = .07f) else t.controlBackground.copy(alpha = .72f), CircleShape),
                ) {
                    Icon(Icons.Rounded.Close, "关闭", tint = if (terminal) Color(0xFFCBD5E1) else t.textSecondary, modifier = Modifier.size(17.dp))
                }
            }
            Box(
                Modifier.fillMaxWidth()
                    .weight(1f)
                    .background(if (terminal) Color.Transparent else t.controlBackground.copy(alpha = .54f), if (terminal) RoundedCornerShape(0.dp) else RoundedCornerShape(16.dp))
                    .padding(if (terminal) 4.dp else 14.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                if (terminal) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Box(Modifier.size(7.dp).background(Color(0xFFFB7185), CircleShape))
                            Box(Modifier.size(7.dp).background(Color(0xFFFBBF24), CircleShape))
                            Box(Modifier.size(7.dp).background(Color(0xFF34D399), CircleShape))
                            Spacer(Modifier.width(3.dp))
                            Text("runtime.log", color = Color(0xFF64748B), fontSize = 10.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                        }
                        Text(
                            renderedText,
                            fontSize = 12.sp,
                            lineHeight = 18.sp,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        )
                    }
                } else {
                    Text(renderedText, color = t.textSecondary, fontSize = 13.sp, lineHeight = 20.sp)
                }
            }
        }
    }
}

@Composable
private fun RefSheetDragHandle() {
    Box(
        Modifier.padding(top = 10.dp, bottom = 6.dp)
            .size(width = 36.dp, height = 4.dp)
            .background(Color(0xFFCBD5E1), CircleShape),
    )
}

@Composable
private fun RefGroup(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().crystalMaterial(RoundedCornerShape(20.dp)), content = content)
}

@Composable
private fun RefSectionLabel(text: String) {
    // The 156785 reference groups related rows by card spacing, not visible section captions.
    Spacer(Modifier.height(2.dp))
}

@Composable
private fun RefGradientIcon(icon: ImageVector, accent: Color) { HetuListIcon(icon) }

@Composable
private fun RefToolRow(icon: ImageVector, accent: Color, title: String, subtitle: String,
    trailingText: String = "", trailingBadge: Boolean = false, trailingColor: Color = Color(0xFF2563EB), onClick: () -> Unit) {
    val t = LocalHetuTokens.current
    val color = if (trailingColor == Color(0xFF2563EB)) MaterialTheme.colorScheme.primary else trailingColor
    WorkspaceSettingRow(title, subtitle, icon, onClick = onClick) {
        Row(Modifier.heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (trailingText.isNotBlank()) Text(trailingText, Modifier.widthIn(max = 72.dp), color = if (trailingBadge) color else t.textSecondary,
                fontSize = 12.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Icon(Icons.Rounded.ChevronRight, null, Modifier.size(16.dp), tint = t.textMuted)
        }
    }
}

@Composable
private fun RefValueRow(title: String, value: String, icon: ImageVector? = null,
    accent: Color = Color(0xFF64748B), highlightValue: Boolean = false, onClick: (() -> Unit)? = null) {
    val t = LocalHetuTokens.current
    val stacked = value.length > 12
    WorkspaceSettingRow(title, if (stacked) value else "", icon, onClick = onClick) {
        Row(Modifier.heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (!stacked) Text(value, Modifier.widthIn(max = 72.dp), color = if (highlightValue) MaterialTheme.colorScheme.primary else t.textSecondary,
                fontSize = 13.sp, lineHeight = 18.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (onClick != null) Icon(Icons.Rounded.ChevronRight, null, Modifier.size(16.dp), tint = t.textMuted)
        }
    }
}

@Composable
private fun RefSwitchRow(icon: ImageVector, accent: Color, title: String, subtitle: String,
    checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    val view = LocalView.current
    WorkspaceSettingRow(title, subtitle, icon,
        modifier = Modifier.toggleable(checked, role = Role.Switch) {
            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK); onCheckedChange(it)
        }) {
        Switch(checked, onCheckedChange = null, modifier = Modifier.heightIn(min = 48.dp))
    }
}

@Composable
private fun RefDivider() { WorkspaceInsetDivider() }

@Composable
private fun RefMetric(title: String, value: String, modifier: Modifier) {
    val t = LocalHetuTokens.current
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(value, color = if (MaterialTheme.colorScheme.background.luminance() < .5f) t.textPrimary else Color(0xFF0F172A), fontSize = 19.sp, lineHeight = 23.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(title, color = Color(0xFF94A3B8), fontSize = 11.sp, lineHeight = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}

@Composable
private fun RefEmptyState(title: String, description: String, icon: ImageVector) {
    val t = LocalHetuTokens.current
    Surface(shape = RoundedCornerShape(22.dp), color = t.cardBackground) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 30.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(52.dp).background(t.controlBackground, CircleShape), contentAlignment = Alignment.Center) {
                Icon(icon, null, Modifier.size(26.dp), tint = t.textSecondary)
            }
            Text(title, color = t.textPrimary, style = MaterialTheme.typography.titleMedium)
            Text(description, color = t.textSecondary, style = MaterialTheme.typography.bodyMedium,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
    }
}

@Composable
private fun RefNotice(text: String) {
    Surface(shape = RoundedCornerShape(12.dp), color = LocalHetuTokens.current.selectionBackground, shadowElevation = 0.dp) {
        Text(text, Modifier.fillMaxWidth().padding(12.dp), color = LocalHetuTokens.current.textPrimary, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun RefTopBar(title: String, onBack: () -> Unit, onRefresh: () -> Unit) {
    val t = LocalHetuTokens.current
    Row(
        Modifier.fillMaxWidth().statusBarsPadding().heightIn(min = 54.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回", tint = t.textPrimary) }
        Text(title, color = t.textPrimary, fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        IconButton(onClick = onRefresh) { Icon(Icons.Rounded.Refresh, "刷新", tint = t.textPrimary) }
    }
}

@Composable
private fun RefTitleBar(title: String) {
    Text(
        title,
        color = LocalHetuTokens.current.textPrimary,
        fontSize = 32.sp,
        lineHeight = 40.sp,
        fontWeight = FontWeight.ExtraBold,
        letterSpacing = (-.8).sp,
        modifier = Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 12.dp),
    )
}

private fun refUpdatedAt(value: String): String {
    if (value.isBlank()) return "更新于 —"
    val raw = value.trim()
    val compact = when {
        raw.length >= 16 && raw[4] == '-' && raw[7] == '-' -> raw.substring(5, 16).replace('T', ' ')
        raw.length >= 16 -> raw.take(16).replace('T', ' ')
        else -> raw.replace('T', ' ')
    }
    return "更新于 $compact"
}

internal fun refDelay(value: Long?): String = when {
    value == null -> "--"
    value <= 0L -> "超时"
    else -> "$value ms"
}

internal fun refBytes(value: Long): String {
    if (value <= 0L) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var v = value.toDouble()
    var i = 0
    while (v >= 1024.0 && i < units.lastIndex) {
        v /= 1024.0
        i++
    }
    return if (i == 0) "${v.toLong()} ${units[i]}" else String.format(java.util.Locale.US, "%.1f %s", v, units[i])
}

internal fun refSpeed(value: Long): String = "${refBytes(value)}/s"

internal fun refDuration(seconds: Long): String {
    if (seconds <= 0L) return "0s"
    val days = seconds / 86_400
    val hours = (seconds % 86_400) / 3_600
    val minutes = (seconds % 3_600) / 60
    return when {
        days > 0 -> "${days}天${hours}小时"
        hours > 0 -> "${hours}小时${minutes}分"
        minutes > 0 -> "${minutes}分${seconds % 60}秒"
        else -> "${seconds}秒"
    }
}
