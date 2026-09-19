package io.github.xgl34222220.hetu

import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.ui.Alignment
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
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
import io.github.xgl34222220.hetu.ui.DockItem
import io.github.xgl34222220.hetu.ui.LocalHetuTokens
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
private data class RefSubscriptionCache(val used: Long = 0L, val total: Long = 0L, val count: Int = 0)
private enum class RefPanelTab(val label: String) {
    Groups("节点"), Overview("概览"), Subscriptions("订阅"), Connections("连接"), Rules("规则"), RuleSets("规则集")
}

@Composable
private fun RefProxyShell(resumeRevision: Int, onBack: () -> Unit) {
    val context = LocalContext.current
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
            if (key == "enableBlur" || key == "liquidGlass" || key == "showPanelTab") uiPrefsRevision++
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    uiPrefsRevision
    val blurEnabled = prefs.getBoolean("enableBlur", true)
    val liquidGlassEnabled = prefs.getBoolean("liquidGlass", true)
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
    var runtime by remember { mutableStateOf(ProxyRuntimeSnapshot(
        running = prefs.getBoolean("proxyUiLastRunning", prefs.getBoolean("proxyRootWanted", false)),
        elapsedSeconds = prefs.getLong("proxyUiLastElapsed", 0L),
        rssBytes = prefs.getLong("proxyUiLastRss", 0L),
        lanAddress = prefs.getString("proxyUiLastLan", "—") ?: "—",
        lanInterface = prefs.getString("proxyUiLastLanIf", "—") ?: "—",
        wanAddress = prefs.getString("proxyUiLastWan", "—") ?: "—",
        wanCountryCode = prefs.getString("proxyUiLastWanCountry", "") ?: "",
        wanRegion = prefs.getString("proxyUiLastWanRegion", "—") ?: "—",
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
                operation = ""
                launch { delay(120); refresh() }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                message = error.message ?: "重启失败"
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

    LaunchedEffect(Unit) {
        // First frame must never wait for Root shell + controller API + provider/CPU probes.
        // Paint the persisted snapshot first, then reconcile the live state asynchronously.
        launch {
            delay(320)
            refresh()
        }
        launch {
            delay(420)
            runCatching { repo.ensureIcons() }
        }
        while (true) {
            delay(3000)
            if (operation.isBlank()) refresh()
        }
    }

    // Returning from a secondary activity refreshes local/runtime state only.
    // Do not manufacture three WAN probe connections just because the user came back.
    LaunchedEffect(resumeRevision) {
        if (resumeRevision > 1) refresh()
    }

    // Auto site probes are opt-in. The default is off; manual refresh remains available.
    // This prevents Hetu itself from constantly adding probe traffic to Mihomo.
    LaunchedEffect(state.running) {
        if (!state.running) {
            siteDelays = emptyMap()
            return@LaunchedEffect
        }
        while (true) {
            val seconds = prefs.getInt("latencyAutoRefreshSeconds", 0).takeIf { it == 0 || it == 30 || it == 60 } ?: 0
            if (seconds <= 0) {
                delay(1_000)
                continue
            }
            delay(seconds * 1_000L)
            measureSitesInternal(reportError = false)
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

    val shellBackground = if (MaterialTheme.colorScheme.background.luminance() < .5f) {
        LocalHetuTokens.current.pageBackground
    } else {
        Color(0xFFF1F5F9)
    }
    Box(Modifier.fillMaxSize().background(shellBackground)) {
        Box(
            Modifier.fillMaxSize()
                .then(if (blurEnabled) Modifier.hazeSource(haze) else Modifier)
                .then(if (liquid) Modifier.layerBackdrop(liquidBackdrop) else Modifier),
        ) {
            // Match LuoShu's backdrop architecture: the full-screen page backdrop must live
            // INSIDE the layerBackdrop source. Keeping it only on the parent leaves transparent
            // pixels near the Home tail, which the RuntimeShader can stretch into a white strip.
            Box(Modifier.matchParentSize().background(shellBackground))
            key(page) {
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
                    onBack = onBack,
                    onRefresh = { scope.launch { refresh() } },
                    onToggle = ::toggle,
                    onReload = ::reload,
                    onRestart = ::restart,
                    onDelay = ::measureSites,
                    onLog = { scope.launch { logText = runCatching { inspector.runtimeLog() }.getOrElse { it.message ?: "日志读取失败" } } },
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
                    onOpenSettings = { page = RefProxyPage.Settings },
                    onDetailVisibleChanged = { panelDetailVisible = it },
                )
                RefProxyPage.Tools -> RefTools(state) { logText = it }
                RefProxyPage.Settings -> RefSettings(state) { scope.launch { refresh() } }
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
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }

    logText?.let { text ->
        RefInfoBottomSheet(
            title = "运行日志",
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
private fun refHomeLiquidModifier(
    base: Modifier,
    hazeState: HazeState,
    glassEnabled: Boolean,
    shape: RoundedCornerShape,
): Modifier {
    val dark = MaterialTheme.colorScheme.background.luminance() < .5f
    val brush = if (dark) {
        Brush.verticalGradient(
            listOf(
                Color(0xFF1B2431).copy(alpha = .91f),
                Color(0xFF151D29).copy(alpha = .84f),
            ),
        )
    } else {
        Brush.verticalGradient(
            listOf(
                Color.White.copy(alpha = .94f),
                Color(0xFFF8FAFE).copy(alpha = .85f),
            ),
        )
    }
    return base
        .shadow(
            8.dp,
            shape,
            clip = false,
            ambientColor = Color(0xFF0F172A).copy(alpha = if (dark) .10f else .028f),
            spotColor = Color(0xFF0F172A).copy(alpha = if (dark) .13f else .050f),
        )
        .clip(shape)
        .then(
            if (glassEnabled) Modifier.hazeEffect(state = hazeState, style = HazeMaterials.ultraThin()) {
                blurRadius = 20.dp
                noiseFactor = .012f
            } else Modifier,
        )
        .background(brush, shape)
        .border(
            .8.dp,
            if (dark) Color.White.copy(alpha = .10f) else Color.White.copy(alpha = .78f),
            shape,
        )
}

@Composable
private fun RefHome(
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
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onToggle: () -> Unit,
    onReload: () -> Unit,
    onRestart: () -> Unit,
    onDelay: () -> Unit,
    onLog: () -> Unit,
    onSubscription: () -> Unit,
) {
    val t = LocalHetuTokens.current
    val scheme = MaterialTheme.colorScheme
    val memory = runtime.rssBytes.takeIf { it > 0L } ?: state.memoryBytes

    LazyColumn(
        Modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = PaddingValues(
            start = 16.dp,
            top = 8.dp,
            end = 16.dp,
            bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 94.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Row(
                Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 10.dp).height(44.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "河图",
                    color = if (scheme.background.luminance() < .5f) t.textPrimary else Color(0xFF0F172A),
                    fontSize = 25.sp,
                    lineHeight = 30.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = (-0.70).sp,
                )
                Spacer(Modifier.width(10.dp))
                Surface(
                    shape = CircleShape,
                    color = if (scheme.background.luminance() < .5f) Color(0xFF1D4ED8).copy(alpha = .16f) else Color(0xFFEAF2FF).copy(alpha = .90f),
                    border = BorderStroke(.7.dp, if (scheme.background.luminance() < .5f) Color(0xFF60A5FA).copy(alpha = .18f) else Color(0xFFBFDBFE).copy(alpha = .72f)),
                    shadowElevation = 1.dp,
                    tonalElevation = 0.dp,
                ) {
                    Text(
                        "Mihomo Core",
                        Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        color = if (scheme.background.luminance() < .5f) Color(0xFF93C5FD) else Color(0xFF2563EB),
                        fontSize = 10.sp,
                        lineHeight = 13.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        letterSpacing = .15.sp,
                    )
                }
            }
        }
        item {
            val heroShape = RoundedCornerShape(26.dp)
            Surface(
                modifier = refHomeLiquidModifier(Modifier.fillMaxWidth(), hazeState, glassEnabled, heroShape),
                shape = heroShape,
                color = Color.Transparent,
                shadowElevation = 0.dp,
            ) {
                Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Row(verticalAlignment = Alignment.Top) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                val statusPulse = androidx.compose.animation.core.rememberInfiniteTransition(label = "heroStatusPulse")
                                val statusAlpha by statusPulse.animateFloat(
                                    initialValue = .52f,
                                    targetValue = 1f,
                                    animationSpec = androidx.compose.animation.core.infiniteRepeatable(
                                        animation = androidx.compose.animation.core.tween(820),
                                        repeatMode = androidx.compose.animation.core.RepeatMode.Reverse,
                                    ),
                                    label = "heroStatusAlpha",
                                )
                                Box(
                                    Modifier.size(9.dp)
                                        .graphicsLayer { alpha = if (state.running) statusAlpha else 1f }
                                        .background(if (state.running) Color(0xFF002FA7) else t.danger, CircleShape),
                                )
                                Spacer(Modifier.width(9.dp))
                                Text(
                                    if (state.running) "运行中" else "已停止",
                                    color = t.textPrimary,
                                    fontSize = 20.sp,
                                    lineHeight = 24.sp,
                                    fontWeight = FontWeight.Black,
                                    maxLines = 1,
                                )
                                Spacer(Modifier.width(8.dp))
                                Surface(
                                    shape = CircleShape,
                                    color = if (scheme.background.luminance() < .5f) Color.White.copy(alpha = .10f) else Color.White.copy(alpha = .92f),
                                    border = BorderStroke(.6.dp, if (scheme.background.luminance() < .5f) Color.White.copy(alpha = .10f) else Color(0xFFE2E8F0).copy(alpha = .72f)),
                                    shadowElevation = 1.dp,
                                    tonalElevation = 0.dp,
                                ) {
                                    Text(
                                        if (state.running) refDuration(runtime.elapsedSeconds) else "等待启动",
                                        Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                        color = if (scheme.background.luminance() < .5f) Color(0xFFE2E8F0) else Color(0xFF475569),
                                        fontSize = 12.sp,
                                        lineHeight = 15.sp,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                        maxLines = 1,
                                    )
                                }
                            }
                            Text("${state.core} · ${state.mode}", color = t.textSecondary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                            Text(state.config, color = t.textMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Surface(
                            onClick = onLog,
                            modifier = Modifier.size(36.dp),
                            shape = CircleShape,
                            color = scheme.primary.copy(alpha = if (scheme.background.luminance() < .5f) .12f else .075f),
                            border = BorderStroke(.6.dp, scheme.primary.copy(alpha = .14f)),
                            shadowElevation = 0.dp,
                            tonalElevation = 0.dp,
                        ) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Icon(Icons.Rounded.Article, "运行日志", tint = scheme.primary, modifier = Modifier.size(18.dp))
                            }
                        }
                        Spacer(Modifier.width(8.dp))
                        val checkPulse = androidx.compose.animation.core.rememberInfiniteTransition(label = "heroCheckGlow")
                        val checkGlow by checkPulse.animateFloat(initialValue = 0f, targetValue = 1f, animationSpec = androidx.compose.animation.core.infiniteRepeatable(animation = androidx.compose.animation.core.tween(1500, easing = androidx.compose.animation.core.FastOutSlowInEasing), repeatMode = androidx.compose.animation.core.RepeatMode.Reverse), label = "heroCheckGlowValue")
                        Box(
                            Modifier
                                .size(56.dp)
                                .graphicsLayer { if (state.running) { scaleX = .99f + checkGlow * .018f; scaleY = .99f + checkGlow * .018f } }
                                .shadow(
                                    if (state.running) (13f + checkGlow * 10f).dp else 0.dp,
                                    CircleShape,
                                    clip = false,
                                    ambientColor = Color(0xFF002FA7).copy(alpha = .28f),
                                    spotColor = Color(0xFF002FA7).copy(alpha = .42f),
                                )
                                .background(if (state.running) scheme.primary else t.controlBackground, CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                if (state.running) Icons.Rounded.Check else Icons.Rounded.PowerSettingsNew,
                                null,
                                tint = if (state.running) Color.White else t.textMuted,
                                modifier = Modifier.size(30.dp),
                            )
                        }
                    }
                    HorizontalDivider(color = scheme.primary.copy(alpha = .10f))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        val neutralAction = if (scheme.background.luminance() < .5f) Color(0xFFE2E8F0) else Color(0xFF334155)
                        RefActionText("重载", state.running && operation.isBlank(), onReload, Modifier.weight(1f), neutralAction, Icons.Rounded.Refresh)
                        RefActionText(
                            if (state.running) "停止" else "启动",
                            operation.isBlank(),
                            onToggle,
                            Modifier.weight(1f),
                            if (state.running) Color(0xFFE11D48) else Color(0xFF2563EB),
                            if (state.running) null else Icons.Rounded.PlayArrow,
                            danger = state.running,
                        )
                        RefActionText("重启", state.running && operation.isBlank(), onRestart, Modifier.weight(1f), neutralAction, Icons.Rounded.RestartAlt)
                    }
                }
            }
        }
        item {
            RefLatencyPanel(
                baidu = siteDelays["Baidu"],
                cloudflare = siteDelays["Cloudflare"],
                google = siteDelays["Google"],
                testing = testing,
                hazeState = hazeState,
                glassEnabled = glassEnabled,
                onClick = onDelay,
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                RefNetworkIdentityCard(runtime, if (state.panelReady) state.connections.size else cachedConnections, Modifier.weight(1f), hazeState, glassEnabled)
                RefSpeedCard(upRate, downRate, Modifier.weight(1f), hazeState, glassEnabled)
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                RefSubscriptionCompact(providers, cachedSubscription, Modifier.weight(1f), hazeState, glassEnabled, onSubscription)
                RefResourceCard(memory, cpuPercent, Modifier.weight(1f), hazeState, glassEnabled)
            }
        }
        if (operation.isNotBlank() || message.isNotBlank()) {
            item { Text(operation.ifBlank { message }, color = t.textSecondary, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)) }
        }
    }
}

@Composable
private fun RefLatencyPanel(
    baidu: Long?,
    cloudflare: Long?,
    google: Long?,
    testing: Boolean,
    hazeState: HazeState,
    glassEnabled: Boolean,
    onClick: () -> Unit,
) {
    val t = LocalHetuTokens.current
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(20.dp)
    Surface(
        modifier = refHomeLiquidModifier(Modifier.fillMaxWidth(), hazeState, glassEnabled, shape),
        shape = shape,
        color = Color.Transparent,
        shadowElevation = 0.dp,
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 13.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("延迟", color = t.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                val spinner = androidx.compose.animation.core.rememberInfiniteTransition(label = "homeLatencyRefresh")
                val rotation by spinner.animateFloat(
                    initialValue = 0f,
                    targetValue = 360f,
                    animationSpec = androidx.compose.animation.core.infiniteRepeatable(
                        animation = androidx.compose.animation.core.tween(900, easing = androidx.compose.animation.core.LinearEasing),
                    ),
                    label = "homeLatencyRefreshRotation",
                )
                IconButton(onClick = onClick, enabled = !testing, modifier = Modifier.size(32.dp)) {
                    Icon(
                        Icons.Rounded.Refresh,
                        "全部测速",
                        tint = t.textSecondary,
                        modifier = Modifier.size(18.dp).graphicsLayer {
                            rotationZ = if (testing) rotation else 0f
                            alpha = if (testing) .62f else 1f
                        },
                    )
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                RefLatencyColumn("Baidu", baidu, testing, Modifier.weight(1f))
                RefLatencyColumn("Cloudflare", cloudflare, testing, Modifier.weight(1f))
                RefLatencyColumn("Google", google, testing, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun RefLatencyColumn(label: String, value: Long?, testing: Boolean, modifier: Modifier) {
    val valueColor = when {
        value == null -> Color(0xFF94A3B8)
        value <= 0L -> Color(0xFFF43F5E)
        value < 100L -> Color(0xFF10B981)
        value <= 300L -> Color(0xFFF59E0B)
        else -> Color(0xFFF43F5E)
    }
    val alpha by animateFloatAsState(
        targetValue = if (testing) .58f else 1f,
        animationSpec = androidx.compose.animation.core.tween(180),
        label = "homeLatencyTestingAlpha",
    )
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, color = Color(0xFF64748B), fontSize = 11.sp, lineHeight = 15.sp, maxLines = 1)
        Row(
            Modifier.height(22.dp).graphicsLayer { this.alpha = alpha },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Box(Modifier.width(46.dp), contentAlignment = Alignment.CenterEnd) {
                androidx.compose.animation.AnimatedContent(
                    targetState = value,
                    transitionSpec = {
                        androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(180))
                            .togetherWith(androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(120)))
                    },
                    label = "latencyValueFade${label}",
                ) { shown ->
                    Text(
                        when {
                            shown == null -> "--"
                            shown <= 0L -> "超时"
                            else -> shown.toString()
                        },
                        color = when {
                            shown == null -> Color(0xFF94A3B8)
                            shown <= 0L -> Color(0xFFF43F5E)
                            shown < 100L -> Color(0xFF10B981)
                            shown <= 300L -> Color(0xFFF59E0B)
                            else -> Color(0xFFF43F5E)
                        },
                        fontSize = if (shown != null && shown > 0L) 18.sp else 14.sp,
                        lineHeight = 21.sp,
                        fontWeight = FontWeight.Black,
                        maxLines = 1,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    )
                }
            }
            Text(
                if (value != null && value > 0L) "ms" else "",
                color = Color(0xFF94A3B8),
                fontSize = 10.sp,
                lineHeight = 13.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                modifier = Modifier.width(14.dp),
            )
        }
    }
}

@Composable
private fun RefNetworkIdentityCard(
    runtime: ProxyRuntimeSnapshot,
    connections: Int,
    modifier: Modifier,
    hazeState: HazeState,
    glassEnabled: Boolean,
) {
    val t = LocalHetuTokens.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val density = androidx.compose.ui.platform.LocalDensity.current.density
    var renderedLan by rememberSaveable { mutableStateOf(true) }
    var flipping by remember { mutableStateOf(false) }
    val flip = remember { Animatable(0f) }
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) .97f else 1f, spring(dampingRatio = .74f, stiffness = 560f), label = "networkCardPress")
    val shape = RoundedCornerShape(20.dp)
    val valueColor = if (MaterialTheme.colorScheme.background.luminance() < .5f) t.textPrimary else Color(0xFF0F172A)

    Surface(
        modifier = refHomeLiquidModifier(
            modifier.height(112.dp)
                .graphicsLayer { scaleX = scale; scaleY = scale; alpha = if (pressed) .94f else 1f },
            hazeState,
            glassEnabled,
            shape,
        ).clickable(interactionSource = source, indication = null) {
                if (!flipping) {
                    view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    scope.launch {
                        flipping = true
                        flip.animateTo(90f, androidx.compose.animation.core.tween(105, easing = androidx.compose.animation.core.FastOutSlowInEasing))
                        renderedLan = !renderedLan
                        flip.snapTo(-90f)
                        flip.animateTo(0f, spring(dampingRatio = .72f, stiffness = 520f))
                        flipping = false
                    }
                }
            },
        shape = shape,
        color = Color.Transparent,
        shadowElevation = 0.dp,
    ) {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(Modifier.fillMaxWidth().height(24.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f).widthIn(min = 40.dp), contentAlignment = Alignment.CenterStart) {
                    Text(
                        if (renderedLan) "LAN" else "WAN",
                        color = Color(0xFF64748B),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    )
                }
                Box(
                    Modifier.size(24.dp).background(Color(0xFF2563EB).copy(alpha = .08f), CircleShape)
                        .border(.6.dp, Color(0xFF2563EB).copy(alpha = .12f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Rounded.SwapHoriz, "切换 LAN/WAN", tint = Color(0xFF2563EB), modifier = Modifier.size(14.dp))
                }
            }
            Box(
                Modifier.fillMaxWidth().height(42.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                Column(
                    Modifier.fillMaxWidth().widthIn(min = 130.dp).graphicsLayer {
                        rotationY = flip.value
                        cameraDistance = 18f * density
                        alpha = .94f + .06f * (1f - kotlin.math.abs(flip.value) / 90f)
                    },
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                Text(
                    if (renderedLan) runtime.lanAddress else runtime.wanAddress,
                    color = valueColor,
                    fontSize = 15.sp,
                    lineHeight = 18.sp,
                    fontWeight = FontWeight.ExtraBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth().height(20.dp),
                )
                Text(
                    if (renderedLan) "${runtime.lanInterface} · $connections 连接"
                    else "${countryEmoji(runtime.wanCountryCode)} ${runtime.wanRegion}",
                    color = Color(0xFF64748B),
                    fontSize = 11.sp,
                    lineHeight = 14.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth().height(18.dp),
                )
                }
            }
        }
    }
}

@Composable
private fun RefSpeedCard(up: Long, down: Long, modifier: Modifier, hazeState: HazeState, glassEnabled: Boolean) {
    val t = LocalHetuTokens.current
    val valueColor = if (MaterialTheme.colorScheme.background.luminance() < .5f) t.textPrimary else Color(0xFF0F172A)
    val shape = RoundedCornerShape(20.dp)
    Surface(
        modifier = refHomeLiquidModifier(modifier.height(112.dp), hazeState, glassEnabled, shape),
        shape = shape,
        color = Color.Transparent,
        shadowElevation = 0.dp,
    ) {
        Column(
            Modifier.fillMaxSize().padding(14.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("实时网速", color = Color(0xFF64748B), fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Row(Modifier.fillMaxWidth().height(20.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("↑ 上行", color = Color(0xFF059669), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text(refSpeed(up), color = valueColor, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
            }
            Row(Modifier.fillMaxWidth().height(20.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("↓ 下行", color = Color(0xFF2563EB), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text(refSpeed(down), color = valueColor, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
            }
        }
    }
}

@Composable
private fun RefSubscriptionCompact(
    items: List<DashboardProviderUi>,
    cached: RefSubscriptionCache,
    modifier: Modifier,
    hazeState: HazeState,
    glassEnabled: Boolean,
    onClick: () -> Unit,
) {
    val t = LocalHetuTokens.current
    val haptic = LocalHapticFeedback.current
    val tracked = items.filter { it.hasSubscriptionInfo && it.total > 0L }
    val liveUsed = tracked.sumOf { it.used }
    val liveTotal = tracked.sumOf { it.total }
    val used = if (liveTotal > 0L) liveUsed else cached.used
    val total = if (liveTotal > 0L) liveTotal else cached.total
    val itemCount = if (items.isNotEmpty()) items.size else cached.count
    val ratio = if (total <= 0L) 0f else (used.toDouble() / total.toDouble()).toFloat().coerceIn(0f, 1f)
    val valueColor = if (MaterialTheme.colorScheme.background.luminance() < .5f) t.textPrimary else Color(0xFF0F172A)
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) .96f else 1f, spring(dampingRatio = .74f, stiffness = 560f), label = "subscriptionCompactPress")
    val shape = RoundedCornerShape(20.dp)
    Surface(
        modifier = refHomeLiquidModifier(
            modifier.height(112.dp)
                .graphicsLayer { scaleX = scale; scaleY = scale; alpha = if (pressed) .92f else 1f },
            hazeState,
            glassEnabled,
            shape,
        ).clickable(interactionSource = source, indication = null) {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onClick()
            },
        shape = shape,
        color = Color.Transparent,
        shadowElevation = 0.dp,
    ) {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(Modifier.fillMaxWidth().height(20.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("订阅", color = Color(0xFF64748B), fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                if (total > 0L) Surface(shape = RoundedCornerShape(999.dp), color = Color(0xFF2563EB).copy(alpha = .08f)) {
                    Text("剩余 ${((1f - ratio) * 100f).toInt()}%", Modifier.padding(horizontal = 7.dp, vertical = 2.dp), color = MaterialTheme.colorScheme.primary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
            }
            Text(if (total > 0L) refBytes(used) else "—", color = valueColor, fontSize = 16.sp, lineHeight = 19.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, modifier = Modifier.height(20.dp))
            Box(Modifier.fillMaxWidth().height(4.dp).background(Color(0xFF94A3B8).copy(alpha = .12f), CircleShape)) {
                if (total > 0L && ratio > 0f) {
                    Box(
                        Modifier.fillMaxWidth(ratio.coerceIn(.001f, 1f)).fillMaxHeight()
                            .background(Brush.horizontalGradient(listOf(Color(0xFF3B82F6), Color(0xFF6366F1))), CircleShape),
                    )
                }
            }
            Row(Modifier.fillMaxWidth().height(18.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("已用流量", color = Color(0xFF94A3B8), fontSize = 11.sp, modifier = Modifier.weight(1f))
                Text(if (total > 0L) "总 ${refBytes(total)}" else "$itemCount 个订阅", color = Color(0xFF64748B), fontSize = 11.sp, fontWeight = FontWeight.Medium, maxLines = 1)
            }
        }
    }
}

@Composable
private fun RefResourceCard(memory: Long, cpuPercent: Float, modifier: Modifier, hazeState: HazeState, glassEnabled: Boolean) {
    val t = LocalHetuTokens.current
    val scheme = MaterialTheme.colorScheme
    val valueColor = if (scheme.background.luminance() < .5f) t.textPrimary else Color(0xFF0F172A)
    val progress = (cpuPercent / 100f).coerceIn(0f, 1f)
    val pulseTransition = androidx.compose.animation.core.rememberInfiniteTransition(label = "resourceCpuPulse")
    val pulse by pulseTransition.animateFloat(
        initialValue = .42f,
        targetValue = 1f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
            animation = androidx.compose.animation.core.tween(1200),
            repeatMode = androidx.compose.animation.core.RepeatMode.Reverse,
        ),
        label = "resourceCpuPulseAlpha",
    )
    val shape = RoundedCornerShape(20.dp)
    Surface(
        modifier = refHomeLiquidModifier(modifier.height(112.dp), hazeState, glassEnabled, shape),
        shape = shape,
        color = Color.Transparent,
        shadowElevation = 0.dp,
    ) {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(Modifier.fillMaxWidth().height(24.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("资源占用", color = Color(0xFF64748B), fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Canvas(Modifier.size(24.dp)) {
                    val halo = 10.3.dp.toPx() + 1.2.dp.toPx() * pulse
                    drawCircle(Color(0xFF2563EB).copy(alpha = .035f + .055f * pulse), radius = halo, style = Stroke(width = 1.dp.toPx()))
                    drawCircle(Color(0xFFE2E8F0).copy(alpha = .68f), style = Stroke(width = 2.dp.toPx()))
                    drawArc(
                        color = Color(0xFF002FA7).copy(alpha = .62f + .18f * pulse),
                        startAngle = -90f,
                        sweepAngle = 360f * progress,
                        useCenter = false,
                        style = Stroke(width = 2.2.dp.toPx()),
                    )
                    drawCircle(Color(0xFF2563EB).copy(alpha = if (progress > .02f) .18f + .12f * pulse else .08f), radius = 2.5.dp.toPx())
                }
            }
            Row(Modifier.fillMaxWidth().height(20.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("内存", color = Color(0xFF94A3B8), fontSize = 11.sp, modifier = Modifier.weight(1f))
                Text(refBytes(memory), color = valueColor, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
            }
            Row(Modifier.fillMaxWidth().height(20.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("CPU", color = Color(0xFF94A3B8), fontSize = 11.sp, modifier = Modifier.weight(1f))
                Text(String.format(java.util.Locale.US, "%.1f%%", cpuPercent), color = valueColor, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
            }
        }
    }
}

private fun countryEmoji(code: String): String {
    val upper = code.trim().uppercase(java.util.Locale.ROOT)
    if (upper.length != 2 || upper.any { it !in 'A'..'Z' }) return "🌐"
    val first = Character.toChars(0x1F1E6 + (upper[0] - 'A'))
    val second = Character.toChars(0x1F1E6 + (upper[1] - 'A'))
    return String(first) + String(second)
}

@Composable
private fun RefActionText(
    text: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier,
    color: Color = LocalHetuTokens.current.textPrimary,
    icon: ImageVector? = null,
    danger: Boolean = false,
) {
    val t = LocalHetuTokens.current
    val dark = MaterialTheme.colorScheme.background.luminance() < .5f
    val view = LocalView.current
    val source = remember(text) { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(
        if (pressed) .95f else 1f,
        spring(dampingRatio = .68f, stiffness = 650f),
        label = "heroAction$text",
    )
    val shape = CircleShape
    val background = when {
        danger && dark -> Color(0xFF4C1724).copy(alpha = .78f)
        danger -> Color(0xFFFFF1F2).copy(alpha = .82f)
        dark -> Color.White.copy(alpha = .075f)
        else -> Color.White.copy(alpha = .76f)
    }
    val borderColor = when {
        danger && dark -> Color(0xFFFB7185).copy(alpha = .18f)
        danger -> Color(0xFFFFE4E6)
        dark -> Color.White.copy(alpha = .09f)
        else -> Color.White.copy(alpha = .90f)
    }
    val shadowColor = if (danger) Color(0xFFF43F5E).copy(alpha = .12f) else Color(0xFF0F172A).copy(alpha = .055f)
    Row(
        modifier
            .height(42.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale; alpha = if (pressed) .86f else if (enabled) 1f else .50f }
            .shadow(7.dp, shape, clip = false, ambientColor = shadowColor, spotColor = shadowColor)
            .background(background, shape)
            .border(.7.dp, borderColor, shape)
            .clip(shape)
            .clickable(enabled = enabled, interactionSource = source, indication = null) {
                view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                onClick()
            }
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (danger) {
            val pulse = androidx.compose.animation.core.rememberInfiniteTransition(label = "stopPulse")
            val dotAlpha by pulse.animateFloat(
                initialValue = .52f,
                targetValue = 1f,
                animationSpec = androidx.compose.animation.core.infiniteRepeatable(
                    animation = androidx.compose.animation.core.tween(760),
                    repeatMode = androidx.compose.animation.core.RepeatMode.Reverse,
                ),
                label = "stopPulseAlpha",
            )
            Box(Modifier.size(6.dp).graphicsLayer { alpha = dotAlpha }.background(Color(0xFFF43F5E), CircleShape))
            Spacer(Modifier.width(6.dp))
        } else if (icon != null) {
            Icon(icon, null, tint = color.copy(alpha = .68f), modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(text, color = color, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1)
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
    var ruleSets by remember { mutableStateOf<List<DashboardRuleSetUi>>(emptyList()) }
    var selectedGroupName by rememberSaveable { mutableStateOf<String?>(null) }
    val selectedLocal = remember { mutableStateMapOf<String, String>() }
    val testing = remember { mutableStateMapOf<String, Boolean>() }
    val providerRefreshing = remember { mutableStateMapOf<String, Boolean>() }
    val providerSucceeded = remember { mutableStateMapOf<String, Boolean>() }
    val ruleSetRefreshing = remember { mutableStateMapOf<String, Boolean>() }
    val ruleSetSucceeded = remember { mutableStateMapOf<String, Boolean>() }
    var capsuleText by remember { mutableStateOf("") }
    var capsuleError by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var connectionView by rememberSaveable { mutableStateOf("active") }
    val expandedConnectionApps = remember { mutableStateMapOf<String, Boolean>() }
    val closedConnections = remember { mutableStateListOf<ProxyConnectionUi>() }
    var previousConnections by remember { mutableStateOf<List<ProxyConnectionUi>>(emptyList()) }
    val activeConnectionGroups = remember(state.connections) { refConnectionGroups(state.connections) }
    val closedConnectionSnapshot = closedConnections.toList()
    val closedConnectionGroups = remember(closedConnectionSnapshot) { refConnectionGroups(closedConnectionSnapshot) }

    LaunchedEffect(capsuleText) {
        if (capsuleText.isNotBlank()) {
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

    val filteredGroups = remember(state.groups, query) {
        state.groups.filter { group ->
            query.isBlank() || group.name.contains(query, true) || group.now.contains(query, true) ||
                group.nodes.any { it.name.contains(query, true) }
        }
    }
    val filteredNodes = remember(state.groups, query) {
        state.groups.flatMap { it.nodes }.distinctBy { it.name }.filter { node ->
            query.isBlank() || node.name.contains(query, true) || node.type.contains(query, true)
        }
    }

    suspend fun loadTab() {
        if (!state.running) return
        try {
            when (tab) {
                RefPanelTab.Subscriptions -> providers = repo.providers()
                RefPanelTab.Rules -> rules = repo.rules()
                RefPanelTab.RuleSets -> ruleSets = repo.ruleSets()
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
                    } catch (_: Exception) {
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
                        } catch (_: Exception) {
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

    Box(Modifier.fillMaxSize()) {
            PullToRefreshBox(
                isRefreshing = refreshing,
                onRefresh = ::refresh,
                modifier = Modifier.fillMaxSize(),
            ) {
            LazyColumn(
            Modifier.fillMaxSize().statusBarsPadding(),
            contentPadding = PaddingValues(
                start = 16.dp,
                top = 8.dp,
                end = 16.dp,
                bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 94.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Box(Modifier.padding(top = 14.dp, bottom = 4.dp)) {
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
                        onOpenSettings = onOpenSettings,
                        hazeState = hazeState,
                        backdrop = backdrop,
                    )
                }
            }
            if (error.isNotBlank()) item { RefNotice(error) }
            if (!state.running) {
                item { RefNotice("代理未运行") }
            } else when (tab) {
                RefPanelTab.Groups -> {
                    itemsIndexed(filteredGroups.chunked(2), key = { index, _ -> "${tab.name}-groups-$index" }, contentType = { _, _ -> "group-row" }) { _, pair ->
                        val expandedGroup = pair.firstOrNull { it.name == selectedGroupName }
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
                                if (pair.size == 1) Spacer(Modifier.weight(1f))
                            }
                            androidx.compose.animation.AnimatedVisibility(
                                visible = expandedGroup != null,
                                enter = androidx.compose.animation.expandVertically(
                                    expandFrom = Alignment.Top,
                                    animationSpec = spring(dampingRatio = .72f, stiffness = 360f),
                                    clip = false,
                                ) + androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(180)) +
                                    androidx.compose.animation.slideInVertically(
                                        animationSpec = spring(dampingRatio = .74f, stiffness = 420f),
                                    ) { -it / 10 },
                                exit = androidx.compose.animation.shrinkVertically(
                                    shrinkTowards = Alignment.Top,
                                    animationSpec = androidx.compose.animation.core.tween(
                                        durationMillis = 220,
                                        easing = androidx.compose.animation.core.CubicBezierEasing(.16f, 1f, .30f, 1f),
                                    ),
                                    clip = false,
                                ) + androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(140)),
                            ) {
                                expandedGroup?.let { group ->
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
                RefPanelTab.Overview -> item(key = "${tab.name}-traffic-overview", contentType = "traffic-overview") { RefTrafficOverview(state) }
                RefPanelTab.Subscriptions -> items(providers, key = { "${tab.name}-provider-${it.name}-${it.updatedAt}-${it.upload}-${it.download}-${it.total}-${it.expire}" }, contentType = { "subscription-provider" }) { item ->
                    RefProviderRow(
                        item = item,
                        refreshing = providerRefreshing[item.name] == true,
                        success = providerSucceeded[item.name] == true,
                        onRefresh = {
                            if (providerRefreshing[item.name] != true) scope.launch {
                                providerRefreshing[item.name] = true
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
                                } catch (e: Exception) {
                                    capsuleError = true
                                    capsuleText = e.message ?: "订阅更新失败，请检查网络"
                                } finally {
                                    providerRefreshing.remove(item.name)
                                }
                            }
                        },
                        onClick = { context.startActivity(Intent(context, ProxySubscriptionActivity::class.java)) },
                    )
                }
                RefPanelTab.Connections -> {
                    val shownGroups = if (connectionView == "active") activeConnectionGroups else closedConnectionGroups
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilterChip(
                                    selected = connectionView == "active",
                                    onClick = { connectionView = "active" },
                                    label = { Text("应用 ${activeConnectionGroups.size} · 连接 ${state.connections.size}") },
                                )
                                FilterChip(
                                    selected = connectionView == "closed",
                                    onClick = { connectionView = "closed" },
                                    label = { Text("已关闭 ${closedConnections.size}") },
                                )
                                Spacer(Modifier.weight(1f))
                                if (connectionView == "active" && state.connections.isNotEmpty()) {
                                    TextButton(onClick = { scope.launch { repo.closeAll(); onRefreshState() } }) {
                                        Text("终止全部", color = t.danger)
                                    }
                                }
                            }
                            if (connectionView == "active" && state.connections.isNotEmpty()) {
                                Text(
                                    "按应用聚合显示；点应用展开具体目标。一个应用同时访问多个域名时会产生多条底层连接。",
                                    color = t.textMuted,
                                    fontSize = 10.sp,
                                    lineHeight = 14.sp,
                                )
                            }
                        }
                    }
                    items(shownGroups, key = { "${tab.name}-app-" + it.key }, contentType = { "connection-app-group" }) { group ->
                        RefConnectionAppCard(
                            group = group,
                            expanded = expandedConnectionApps[group.key] == true,
                            onToggle = {
                                expandedConnectionApps[group.key] = expandedConnectionApps[group.key] != true
                            },
                            onCloseAll = if (connectionView == "active") ({
                                scope.launch {
                                    group.connections.forEach { item -> runCatching { repo.closeConnection(item.id) } }
                                    onRefreshState()
                                }
                            }) else null,
                            onCloseConnection = if (connectionView == "active") ({ item ->
                                scope.launch {
                                    repo.closeConnection(item.id)
                                    onRefreshState()
                                }
                            }) else null,
                        )
                    }
                }
                RefPanelTab.Rules -> itemsIndexed(rules.chunked(15), key = { index, _ -> "${tab.name}-rule-group-$index" }, contentType = { _, _ -> "rule-group" }) { _, batch ->
                    RefRuleGroupCard(batch)
                }
                RefPanelTab.RuleSets -> items(ruleSets, key = { "${tab.name}-ruleset-${it.name}" }, contentType = { "ruleset-row" }) { item ->
                    RefRuleSetRow(item, refreshing = ruleSetRefreshing[item.name] == true, success = ruleSetSucceeded[item.name] == true) {
                        if (ruleSetRefreshing[item.name] != true) scope.launch {
                            ruleSetRefreshing[item.name] = true
                            try {
                                val updated = repo.refreshRuleSet(item.name)
                                if (updated != null) ruleSets = ruleSets.map { if (it.name == updated.name) updated else it }
                                capsuleError = false
                                capsuleText = "规则集更新完成"
                                ruleSetSucceeded[item.name] = true
                                view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                                delay(70)
                                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                delay(1200)
                                ruleSetSucceeded.remove(item.name)
                            } catch (e: Exception) {
                                capsuleError = true
                                capsuleText = e.message ?: "规则集更新失败，请检查网络"
                            } finally {
                                ruleSetRefreshing.remove(item.name)
                            }
                        }
                    }
                }
            }
            }
        }
        androidx.compose.animation.AnimatedVisibility(
            visible = capsuleText.isNotBlank(),
            modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 10.dp),
            enter = androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(160)) +
                androidx.compose.animation.slideInVertically(androidx.compose.animation.core.tween(190)) { -it / 2 },
            exit = androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(150)) +
                androidx.compose.animation.slideOutVertically(androidx.compose.animation.core.tween(170)) { -it / 3 },
        ) {
            RefFloatingCapsule(capsuleText, capsuleError)
        }
    }


}


@Composable
private fun RefFloatingCapsule(text: String, error: Boolean) {
    val pulse = androidx.compose.animation.core.rememberInfiniteTransition(label = "capsulePulse")
    val dotAlpha by pulse.animateFloat(
        initialValue = .58f,
        targetValue = 1f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
            animation = androidx.compose.animation.core.tween(720),
            repeatMode = androidx.compose.animation.core.RepeatMode.Reverse,
        ),
        label = "capsulePulseAlpha",
    )
    Surface(
        shape = CircleShape,
        color = Color(0xFF0F172A).copy(alpha = .88f),
        border = BorderStroke(1.dp, Color.White.copy(alpha = .10f)),
        shadowElevation = 12.dp,
    ) {
        Row(
            Modifier.padding(horizontal = 15.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                Modifier.size(7.dp).graphicsLayer { alpha = dotAlpha }
                    .background(if (error) Color(0xFFFB7185) else Color(0xFF34D399), CircleShape),
            )
            Text(text, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
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
    BackHandler(onBack = onBack)
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
    val pageBackground = if (dark) t.pageBackground else Color(0xFFF4F6F9)

    Column(
        Modifier.fillMaxSize().offset(x = enterX).background(pageBackground).navigationBarsPadding(),
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
                IconButton(onClick = onBack, modifier = Modifier.size(40.dp)) {
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
                IconButton(onClick = onRefresh, modifier = Modifier.size(38.dp)) {
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
                                "节点选择",
                                color = t.textPrimary,
                                fontSize = 23.sp,
                                lineHeight = 29.sp,
                                fontWeight = FontWeight.ExtraBold,
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "${refGroupTypeCompact(group.type).uppercase()} · $testedCount/${group.nodes.size}",
                                    color = Color(0xFF94A3B8),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                )
                                IconButton(
                                    onClick = { searchOpen = !searchOpen; if (!searchOpen) query = "" },
                                    modifier = Modifier.size(30.dp),
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
                            Text("⚡ ${refSpeed(liveRate)}", color = Color(0xFF94A3B8), fontSize = 10.sp, fontWeight = FontWeight.Medium)
                            if (anyTesting) {
                                Text("$testedCount/${group.nodes.size}", color = Color(0xFFF59E0B), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                        Spacer(Modifier.width(10.dp))
                        val rocketSource = remember { MutableInteractionSource() }
                        val rocketPressed by rocketSource.collectIsPressedAsState()
                        val rocketScale by animateFloatAsState(if (rocketPressed) .94f else 1f, label = "rocketPress")
                        Box(
                            Modifier.size(44.dp)
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
                                modifier = Modifier.clickable { activeTag = tag },
                            ) {
                                Text(
                                    tag,
                                    Modifier.padding(horizontal = 11.dp, vertical = 6.dp),
                                    color = if (active) scheme.primary else t.textSecondary,
                                    fontSize = 11.sp,
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
                itemsIndexed(filteredNodes.chunked(2), key = { index, _ -> "detail-row-$index" }) { _, pair ->
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
                        if (pair.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun RefDetailNodeCard(
    node: ProxyNodeUi,
    active: Boolean,
    delay: Long?,
    testing: Boolean,
    modifier: Modifier,
    onSelect: () -> Unit,
    onDelay: () -> Unit,
) {
    val t = LocalHetuTokens.current
    val scheme = MaterialTheme.colorScheme
    val dark = scheme.background.luminance() < .5f
    val source = remember(node.name) { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) .96f else 1f, spring(dampingRatio = .72f, stiffness = 580f), label = "detailNode${node.name}")
    val shape = RoundedCornerShape(16.dp)
    val premiumBrush = if (dark) {
        Brush.verticalGradient(
            listOf(
                t.elevatedCardBackground.copy(alpha = .94f),
                t.cardBackground.copy(alpha = .86f),
            ),
        )
    } else {
        Brush.verticalGradient(
            listOf(
                Color.White.copy(alpha = .95f),
                Color(0xFFF8FAFC).copy(alpha = .82f),
            ),
        )
    }
    Column(
        modifier.height(64.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale; alpha = if (pressed) .94f else 1f }
            .shadow(if (active) 5.dp else 3.dp, shape, clip = false)
            .background(premiumBrush, shape)
            .border(
                if (active) 1.8.dp else .8.dp,
                if (active) Color(0xFF2563EB) else Color.White.copy(alpha = if (dark) .10f else .92f),
                shape,
            )
            .clip(shape)
            .clickable(interactionSource = source, indication = null, onClick = onSelect)
            .padding(horizontal = 11.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        val flag = refNodeFlag(node.name)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (flag.isNotBlank()) {
                Text(flag, fontSize = 14.sp)
                Spacer(Modifier.width(4.dp))
            }
            Text(
                node.name,
                color = t.textPrimary,
                fontSize = 13.sp,
                lineHeight = 16.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Box(Modifier.size(16.dp), contentAlignment = Alignment.Center) {
                androidx.compose.animation.AnimatedVisibility(
                    visible = active,
                    enter = androidx.compose.animation.scaleIn(
                        initialScale = .15f,
                        animationSpec = spring(dampingRatio = .56f, stiffness = 520f),
                    ) + androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(140)),
                    exit = androidx.compose.animation.scaleOut(targetScale = .45f) + androidx.compose.animation.fadeOut(),
                ) {
                    Box(
                        Modifier.size(16.dp).background(Color(0xFF2563EB), CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Rounded.Check, "已选择", tint = Color.White, modifier = Modifier.size(11.dp))
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = if (active) Color(0xFFEFF6FF) else Color(0xFFF1F5F9),
            ) {
                Text(
                    refNodeProtocol(node),
                    Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    color = if (active) Color(0xFF2563EB) else Color(0xFF64748B),
                    fontSize = 9.sp,
                    lineHeight = 12.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                )
            }
            Spacer(Modifier.weight(1f))
            RefDelayBadge(delay, testing, onDelay)
        }
    }
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
            Modifier.fillMaxWidth().height(44.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "面板",
                color = t.textPrimary,
                fontSize = 24.sp,
                lineHeight = 30.sp,
                fontWeight = FontWeight.ExtraBold,
                modifier = Modifier.weight(1f),
            )
            RefPanelHeaderAction(
                icon = if (searchOpen) Icons.Rounded.Close else Icons.Rounded.Search,
                contentDescription = if (searchOpen) "关闭搜索" else "搜索",
                active = searchOpen,
                onClick = onSearchToggle,
            )
        }
        RefPanelTabs(
            selected = selected,
            liquidGlass = true,
            onSelect = onSelect,
        )
        androidx.compose.animation.AnimatedVisibility(
            visible = searchOpen,
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
                placeholder = { Text("搜索策略组或节点") },
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
        Modifier.size(36.dp)
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
    val scheme = MaterialTheme.colorScheme
    val dark = scheme.background.luminance() < .5f
    val tabs = RefPanelTab.entries
    val trackBrush = Brush.verticalGradient(
        if (dark) listOf(Color.White.copy(alpha = .085f), Color.White.copy(alpha = .035f))
        else listOf(Color(0xFFE2E8F0).copy(alpha = .58f), Color.White.copy(alpha = .46f)),
    )
    val trackBorder = if (dark) Color.White.copy(alpha = .08f) else Color.White.copy(alpha = .82f)
    BoxWithConstraints(
        Modifier.fillMaxWidth().height(38.dp)
            .background(trackBrush, CircleShape)
            .border(.5.dp, trackBorder, CircleShape)
            .padding(3.dp),
    ) {
        val itemWidth = maxWidth / tabs.size.toFloat()
        val targetIndex = tabs.indexOf(selected).coerceAtLeast(0)
        val indicatorX by animateDpAsState(
            targetValue = itemWidth * targetIndex.toFloat(),
            animationSpec = spring(dampingRatio = .74f, stiffness = 380f),
            label = "panelTabIndicator",
        )
        val indicatorShape = RoundedCornerShape(18.dp)
        val lensBrush = Brush.verticalGradient(
            if (dark) listOf(Color.White.copy(alpha = .10f), Color(0xFF2563EB).copy(alpha = .18f))
            else listOf(Color.White.copy(alpha = .98f), Color(0xFFF8FAFC).copy(alpha = .94f)),
        )
        Box(
            Modifier.offset(x = indicatorX)
                .width(itemWidth)
                .fillMaxHeight()
                .shadow(if (liquidGlass) 3.dp else 1.dp, indicatorShape, clip = false)
                .background(lensBrush, indicatorShape)
                .border(.6.dp, Color.White.copy(alpha = if (dark) .14f else .96f), indicatorShape),
        )
        Row(Modifier.fillMaxSize()) {
            tabs.forEach { tab ->
                val active = tab == selected
                val source = remember(tab) { MutableInteractionSource() }
                val pressed by source.collectIsPressedAsState()
                val scale by animateFloatAsState(
                    if (pressed) .96f else 1f,
                    spring(dampingRatio = .76f, stiffness = 560f),
                    label = "tab${tab.name}",
                )
                Box(
                    Modifier.width(itemWidth).fillMaxHeight()
                        .graphicsLayer { scaleX = scale; scaleY = scale; alpha = if (pressed) .88f else 1f }
                        .clip(CircleShape)
                        .clickable(interactionSource = source, indication = null) { if (!active) onSelect(tab) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        tab.label,
                        color = if (active) Color(0xFF2563EB) else if (dark) t.textSecondary else Color(0xFF64748B),
                        fontSize = 12.sp,
                        fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                        maxLines = 1,
                    )
                }
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
private fun RefGroupCard(
    group: ProxyGroupUi,
    selected: String,
    expanded: Boolean,
    delay: Long?,
    testing: Boolean,
    hazeState: HazeState,
    glassEnabled: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
    onDelay: () -> Unit,
) {
    val t = LocalHetuTokens.current
    val dark = MaterialTheme.colorScheme.background.luminance() < .5f
    val source = remember(group.name) { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(
        if (pressed) .97f else 1f,
        spring(dampingRatio = .74f, stiffness = 580f),
        label = "group${group.name}",
    )
    val shape = RoundedCornerShape(22.dp)
    val nodeName = selected.ifBlank { "未选择" }
    val nodeFlag = refNodeFlag(nodeName)
    val premiumBrush = when {
        glassEnabled && dark -> Brush.verticalGradient(
            listOf(Color(0xFF1B2431).copy(alpha = .94f), Color(0xFF151D29).copy(alpha = .90f)),
        )
        glassEnabled -> Brush.verticalGradient(
            listOf(Color.White.copy(alpha = .95f), Color(0xFFF8FAFC).copy(alpha = .85f)),
        )
        dark -> Brush.verticalGradient(listOf(t.elevatedCardBackground, t.cardBackground))
        else -> Brush.verticalGradient(listOf(Color.White, Color(0xFFFAFBFD)))
    }
    val safeGlassModifier = if (glassEnabled) {
        Modifier.hazeEffect(state = hazeState, style = HazeMaterials.ultraThin()) {
            blurRadius = 16.dp
            noiseFactor = .010f
        }
    } else Modifier
    val arrowRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = androidx.compose.animation.core.tween(
            durationMillis = 280,
            easing = androidx.compose.animation.core.CubicBezierEasing(.16f, 1f, .30f, 1f),
        ),
        label = "groupArrow${group.name}",
    )

    Column(
        modifier
            .height(96.dp)
            .zIndex(1f)
            .graphicsLayer { scaleX = scale; scaleY = scale; alpha = if (pressed) .95f else 1f }
            .shadow(
                if (expanded) 7.dp else 4.dp,
                shape,
                clip = false,
                ambientColor = Color(0xFF0F172A).copy(alpha = if (dark) .12f else .032f),
                spotColor = Color(0xFF0F172A).copy(alpha = if (dark) .15f else .065f),
            )
            .clip(shape)
            .then(safeGlassModifier)
            .background(premiumBrush, shape)
            .border(
                if (expanded) 1.25.dp else .9.dp,
                if (expanded) Color(0xFF2563EB).copy(alpha = .52f)
                else if (glassEnabled) {
                    if (dark) Color.White.copy(alpha = .13f) else Color.White.copy(alpha = .82f)
                } else if (dark) Color.White.copy(alpha = .10f) else Color(0xFFE2E8F0).copy(alpha = .80f),
                shape,
            )
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .padding(start = 16.dp, top = 10.dp, end = 14.dp, bottom = 9.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(
            Modifier.fillMaxWidth().height(23.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                group.name,
                color = t.textPrimary,
                fontSize = 14.sp,
                lineHeight = 17.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            RefGroupCornerVisual(group, Modifier.size(32.dp))
            Spacer(Modifier.width(4.dp))
            Icon(
                Icons.Rounded.KeyboardArrowDown,
                if (expanded) "收起" else "展开",
                tint = if (expanded) Color(0xFF2563EB) else Color(0xFF94A3B8),
                modifier = Modifier.size(15.dp).graphicsLayer {
                    transformOrigin = TransformOrigin.Center
                    rotationZ = arrowRotation
                },
            )
        }

        Row(
            Modifier.fillMaxWidth().height(18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (nodeFlag.isNotBlank()) {
                Text(nodeFlag, fontSize = 13.sp, modifier = Modifier.padding(end = 4.dp))
            }
            Text(
                nodeName,
                color = if (dark) t.textSecondary else Color(0xFF334155),
                fontSize = 12.sp,
                lineHeight = 15.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }

        Row(
            Modifier.fillMaxWidth().height(22.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                shape = RoundedCornerShape(7.dp),
                color = if (dark) Color.White.copy(alpha = .055f) else Color(0xFFF1F5F9).copy(alpha = .86f),
                tonalElevation = 0.dp,
            ) {
                Text(
                    "${refGroupTypeCompact(group.type).lowercase()} · ${group.nodes.size}",
                    Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    color = Color(0xFF94A3B8),
                    fontSize = 9.sp,
                    lineHeight = 12.sp,
                    fontWeight = FontWeight.Medium,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    maxLines = 1,
                )
            }
            Spacer(Modifier.weight(1f))
            Box(
                Modifier.clickable(
                    enabled = !testing,
                    interactionSource = remember(group.name, "delay") { MutableInteractionSource() },
                    indication = null,
                ) { onDelay() },
            ) {
                RefDelayBadge(delay, testing, null)
            }
        }
    }
}

@Composable
private fun RefInlineGroupExpansion(
    group: ProxyGroupUi,
    selected: String,
    delays: Map<String, Long>,
    testing: Map<String, Boolean>,
    onSelect: (String) -> Unit,
    onDelay: (String) -> Unit,
    onTestAll: () -> Unit,
) {
    val t = LocalHetuTokens.current
    val dark = MaterialTheme.colorScheme.background.luminance() < .5f
    val shape = RoundedCornerShape(22.dp)
    val wellColor = if (dark) Color(0xFF18212E) else Color(0xFFEEF2F6)
    val wellBorder = if (dark) Color.White.copy(alpha = .08f) else Color(0xFFCBD5E1).copy(alpha = .62f)
    val wellBrush = if (dark) {
        Brush.verticalGradient(listOf(Color(0xFF141C27), wellColor, Color(0xFF202A37)))
    } else {
        Brush.verticalGradient(listOf(Color(0xFFE7EDF4), wellColor, Color(0xFFF2F5F8)))
    }
    Surface(
        modifier = Modifier.padding(top = 6.dp, bottom = 16.dp).fillMaxWidth().zIndex(0f),
        shape = shape,
        color = Color.Transparent,
        border = BorderStroke(.8.dp, wellBorder),
        shadowElevation = 0.dp,
        tonalElevation = 0.dp,
    ) {
        Column(
            Modifier.fillMaxWidth().background(wellBrush, shape).padding(start = 14.dp, top = 16.dp, end = 14.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            // A thin top compression line gives the well a visual inset without a second white shell.
            Box(
                Modifier.fillMaxWidth().height(3.dp)
                    .background(
                        Brush.horizontalGradient(
                            listOf(Color.Transparent, Color(0xFF0F172A).copy(alpha = if (dark) .20f else .10f), Color.Transparent),
                        ),
                        CircleShape,
                    ),
            )
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    Text("切换落地节点", color = if (dark) t.textSecondary else Color(0xFF64748B), fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(6.dp))
                    Text("· 点击即生效", color = Color(0xFF94A3B8), fontSize = 11.sp, lineHeight = 16.sp, fontWeight = FontWeight.Normal)
                }
                val allTestSource = remember(group.name) { MutableInteractionSource() }
                val allPressed by allTestSource.collectIsPressedAsState()
                val allScale by animateFloatAsState(if (allPressed) .95f else 1f, label = "allDelay${group.name}")
                Row(
                    Modifier.graphicsLayer { scaleX = allScale; scaleY = allScale }
                        .shadow(2.dp, CircleShape, clip = false, ambientColor = Color(0xFF0F172A).copy(alpha = .04f), spotColor = Color(0xFF0F172A).copy(alpha = .05f))
                        .background(if (dark) Color.White.copy(alpha = .08f) else Color.White.copy(alpha = .92f), CircleShape)
                        .border(.6.dp, if (dark) Color.White.copy(alpha = .08f) else Color.White.copy(alpha = .92f), CircleShape)
                        .clip(CircleShape)
                        .clickable(interactionSource = allTestSource, indication = null, onClick = onTestAll)
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text("全测速", color = Color(0xFF2563EB), fontSize = 11.sp, fontWeight = FontWeight.ExtraBold)
                    Text("⚡", color = Color(0xFFF59E0B), fontSize = 10.sp)
                }
            }
            group.nodes.withIndex().toList().chunked(2).forEach { pair ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    pair.forEach { entry ->
                        val node = entry.value
                        RefInlineNodeCard(
                            index = entry.index,
                            node = node,
                            active = node.name == selected,
                            delay = delays[node.name] ?: node.lastDelay,
                            testing = testing[node.name] == true,
                            modifier = Modifier.weight(1f),
                            onSelect = { onSelect(node.name) },
                            onDelay = { onDelay(node.name) },
                        )
                    }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun RefInlineNodeCard(
    index: Int,
    node: ProxyNodeUi,
    active: Boolean,
    delay: Long?,
    testing: Boolean,
    modifier: Modifier,
    onSelect: () -> Unit,
    onDelay: () -> Unit,
) {
    val t = LocalHetuTokens.current
    val dark = MaterialTheme.colorScheme.background.luminance() < .5f
    val shape = RoundedCornerShape(14.dp)
    val source = remember(node.name) { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) .97f else 1f, spring(dampingRatio = .72f, stiffness = 580f), label = "inlineNode${node.name}")
    var revealed by remember(node.name) { mutableStateOf(false) }
    LaunchedEffect(node.name) {
        delay((index * 15L).coerceAtMost(180L))
        revealed = true
    }
    val backgroundBrush = when {
        dark && active -> Brush.verticalGradient(listOf(Color(0xFF172554), Color(0xFF111C38)))
        dark -> Brush.verticalGradient(listOf(Color(0xFF242E3C), Color(0xFF202936)))
        active -> Brush.verticalGradient(listOf(Color(0xFFF7FBFF), Color(0xFFEFF6FF)))
        else -> Brush.verticalGradient(listOf(Color.White, Color.White))
    }
    val borderColor = when {
        dark && active -> Color(0xFF60A5FA).copy(alpha = .44f)
        dark -> Color.White.copy(alpha = .08f)
        active -> Color(0xFFBFDBFE)
        else -> Color.White.copy(alpha = .88f)
    }
    Box(modifier.height(72.dp)) {
        androidx.compose.animation.AnimatedVisibility(
            visible = revealed,
            modifier = Modifier.fillMaxSize(),
            enter = androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(180)) +
                androidx.compose.animation.slideInVertically(androidx.compose.animation.core.tween(230)) { it / 3 },
        ) {
            Box(
                Modifier.fillMaxSize()
                    .graphicsLayer { scaleX = scale; scaleY = scale; alpha = if (pressed) .90f else 1f }
                    .shadow(
                        0.dp,
                        shape,
                        clip = false,
                        ambientColor = if (active) Color(0xFF2563EB).copy(alpha = .07f) else Color(0xFF0F172A).copy(alpha = .035f),
                        spotColor = if (active) Color(0xFF2563EB).copy(alpha = .10f) else Color(0xFF0F172A).copy(alpha = .05f),
                    )
                    .background(backgroundBrush, shape)
                    .border(if (active) 1.dp else .7.dp, borderColor, shape)
                    .clip(shape)
                    .clickable(interactionSource = source, indication = null, onClick = onSelect),
            ) {
                Column(
                    Modifier.fillMaxSize().padding(horizontal = 11.dp, vertical = 9.dp),
                    verticalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        val flag = refNodeFlag(node.name)
                        if (flag.isNotBlank()) {
                            Text(flag, fontSize = 14.sp)
                            Spacer(Modifier.width(5.dp))
                        }
                        Text(
                            node.name,
                            color = if (active && !dark) Color(0xFF2563EB) else t.textPrimary,
                            fontSize = 12.sp,
                            lineHeight = 14.sp,
                            fontWeight = if (active) FontWeight.ExtraBold else FontWeight.Bold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f).padding(end = if (active) 12.dp else 0.dp),
                        )
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            refNodeProtocol(node),
                            color = if (active) Color(0xFF60A5FA) else Color(0xFF94A3B8),
                            fontSize = 10.sp,
                            lineHeight = 12.sp,
                            fontWeight = FontWeight.Medium,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            maxLines = 1,
                        )
                        Spacer(Modifier.weight(1f))
                        RefDelayBadge(delay, testing, onDelay, selected = active)
                    }
                }
                androidx.compose.animation.AnimatedVisibility(
                    visible = active,
                    modifier = Modifier.align(Alignment.TopEnd).padding(top = 5.dp, end = 5.dp),
                    enter = androidx.compose.animation.scaleIn(initialScale = .15f, animationSpec = spring(dampingRatio = .56f, stiffness = 520f)) + androidx.compose.animation.fadeIn(),
                    exit = androidx.compose.animation.scaleOut(targetScale = .45f) + androidx.compose.animation.fadeOut(),
                ) {
                    Icon(Icons.Rounded.Check, "已选择", tint = Color(0xFF2563EB), modifier = Modifier.size(13.dp).graphicsLayer { alpha = .92f })
                }
            }
        }
    }
}

@Composable
private fun RefGroupCornerVisual(group: ProxyGroupUi, modifier: Modifier) {
    val dark = MaterialTheme.colorScheme.background.luminance() < .5f
    val knownFlags = listOf("🇭🇰", "🇹🇼", "🇯🇵", "🇸🇬", "🇰🇷", "🇺🇸", "🇬🇧", "🇩🇪", "🇫🇷")
    val flag = knownFlags.firstOrNull { group.name.contains(it) } ?: refNodeFlag(group.name)
    val palette = refGroupBadgePalette(group.name, dark)
    val shape = RoundedCornerShape(11.dp)
    Box(
        modifier
            .shadow(
                2.dp,
                shape,
                clip = false,
                ambientColor = palette.third.copy(alpha = .06f),
                spotColor = palette.third.copy(alpha = .08f),
            )
            .background(palette.first, shape)
            .border(.7.dp, palette.second, shape)
            .clip(shape),
        contentAlignment = Alignment.Center,
    ) {
        if (flag.isNotBlank()) {
            Text(flag, fontSize = 20.sp, lineHeight = 23.sp)
        } else {
            RefGroupVisualIcon(group, palette.third, Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun RefGroupVisualIcon(group: ProxyGroupUi, tint: Color, modifier: Modifier) {
    val bitmap = remember(group.iconPath) {
        runCatching {
            group.iconPath.takeIf { it.isNotBlank() }?.let { BitmapFactory.decodeFile(it)?.asImageBitmap() }
        }.getOrNull()
    }
    Box(modifier, contentAlignment = Alignment.Center) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = group.name,
                modifier = Modifier.fillMaxSize().padding(5.dp),
                contentScale = ContentScale.Fit,
            )
        } else {
            Icon(refScenarioIcon(group.name, group.type), null, tint = tint, modifier = Modifier.size(21.dp))
        }
    }
}

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
        modifier.background(t.cardBackground, shape).border(.7.dp, t.outline.copy(alpha = .45f), shape).clickable(onClick = onClick).padding(11.dp),
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

private fun refScenarioIcon(name: String, type: String): ImageVector = when {
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

private fun refNodeFlag(name: String): String {
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
    val text = refDelay(value)
    val (background, textColor) = when {
        value == null -> Color(0xFFF1F5F9) to Color(0xFF64748B)
        value <= 0L -> Color(0xFFFFF1F2) to Color(0xFFE11D48)
        value < 100L -> Color(0xFFECFDF5) to Color(0xFF059669)
        value <= 300L -> Color(0xFFFFFBEB) to Color(0xFFD97706)
        else -> Color(0xFFFFF1F2) to Color(0xFFE11D48)
    }
    val source = remember(onClick) { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val pulseTransition = androidx.compose.animation.core.rememberInfiniteTransition(label = "latencyPulse")
    val pulse by pulseTransition.animateFloat(
        initialValue = .62f,
        targetValue = 1f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
            animation = androidx.compose.animation.core.tween(620),
            repeatMode = androidx.compose.animation.core.RepeatMode.Reverse,
        ),
        label = "latencyPulseAlpha",
    )
    val pressScale by animateFloatAsState(if (pressed) .90f else 1f, spring(dampingRatio = .68f, stiffness = 680f), label = "latencyPress")
    val shape = CircleShape
    val finalBackground = if (selected) Color.White else background
    val finalTextColor = if (selected) Color(0xFF2563EB) else textColor
    Row(
        Modifier.width(if (onClick != null) 68.dp else 62.dp).height(22.dp)
            .graphicsLayer {
                scaleX = pressScale
                scaleY = pressScale
                alpha = if (pressed) .84f else if (testing) .76f + .24f * pulse else 1f
            }
            .shadow(if (onClick != null) 2.dp else 0.dp, shape, clip = false, ambientColor = finalTextColor.copy(alpha = .10f), spotColor = finalTextColor.copy(alpha = .12f))
            .background(if (testing) finalBackground.copy(alpha = .82f) else finalBackground, shape)
            .border(.7.dp, if (selected) Color(0xFFDBEAFE) else if (testing) finalTextColor.copy(alpha = .22f + .22f * pulse) else finalTextColor.copy(alpha = if (onClick != null) .10f else .04f), shape)
            .then(if (onClick != null) Modifier.clickable(enabled = !testing, interactionSource = source, indication = null, onClick = onClick) else Modifier)
            .padding(horizontal = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Text(
            text,
            color = finalTextColor,
            fontSize = 10.sp,
            lineHeight = 13.sp,
            fontWeight = FontWeight.ExtraBold,
            maxLines = 1,
            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
        )
        if (onClick != null) {
            Spacer(Modifier.width(3.dp))
            if (testing) {
                CircularProgressIndicator(
                    modifier = Modifier.size(9.dp),
                    strokeWidth = 1.25.dp,
                    color = finalTextColor,
                    trackColor = finalTextColor.copy(alpha = .14f),
                )
            } else {
                Icon(Icons.Rounded.Bolt, "单独测速", tint = finalTextColor.copy(alpha = .86f), modifier = Modifier.size(10.dp))
            }
        }
    }
}

@Composable
private fun RefTrafficOverview(state: ProxyComposeState) {
    val t = LocalHetuTokens.current
    val scheme = MaterialTheme.colorScheme
    val history = remember { mutableStateListOf<Triple<Long, Long, Long>>() }
    var lastUpload by remember { mutableLongStateOf(state.uploadTotal) }
    var lastDownload by remember { mutableLongStateOf(state.downloadTotal) }
    var lastAt by remember { mutableLongStateOf(0L) }
    var upRate by remember { mutableLongStateOf(0L) }
    var downRate by remember { mutableLongStateOf(0L) }
    val pulse = androidx.compose.animation.core.rememberInfiniteTransition(label = "trafficIdlePulse")
    val idlePhase by pulse.animateFloat(
        initialValue = 0f,
        targetValue = 6.2831855f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
            animation = androidx.compose.animation.core.tween(3200, easing = androidx.compose.animation.core.LinearEasing),
        ),
        label = "trafficIdlePhase",
    )

    LaunchedEffect(state.uploadTotal, state.downloadTotal) {
        val now = SystemClock.elapsedRealtime()
        if (lastAt > 0L && now > lastAt && state.uploadTotal >= lastUpload && state.downloadTotal >= lastDownload) {
            val elapsed = now - lastAt
            upRate = ((state.uploadTotal - lastUpload) * 1000L / elapsed).coerceAtLeast(0L)
            downRate = ((state.downloadTotal - lastDownload) * 1000L / elapsed).coerceAtLeast(0L)
            // The 1s ticker below owns chart sampling so the graph keeps moving even at 0 B/s.
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
            while (history.isNotEmpty() && history.first().first < now - 60_000L) history.removeAt(0)
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            RefRateCard("上行速度", upRate, Icons.Rounded.ArrowUpward, t.success, Modifier.weight(1f))
            RefRateCard("下行速度", downRate, Icons.Rounded.ArrowDownward, scheme.primary, Modifier.weight(1f))
        }
        Surface(shape = RoundedCornerShape(18.dp), color = t.cardBackground, shadowElevation = 1.dp) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 13.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("总流量", color = t.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text("上行 ${refBytes(state.uploadTotal)}", color = t.success, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold)
                Text("  /  ", color = Color(0xFFCBD5E1), fontSize = 11.sp)
                Text("下行 ${refBytes(state.downloadTotal)}", color = scheme.primary, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold)
            }
        }
        Surface(shape = RoundedCornerShape(18.dp), color = t.cardBackground, shadowElevation = 1.dp) {
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("最近 60 秒流量", color = t.textPrimary, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    Text("↑ 上行   ↓ 下行", color = t.textSecondary, style = MaterialTheme.typography.labelSmall)
                }
                val points = history.toList()
                Canvas(Modifier.fillMaxWidth().height(138.dp)) {
                    fun closeArea(coords: List<Offset>): Path {
                        val path = Path()
                        if (coords.isEmpty()) return path
                        path.moveTo(coords.first().x, coords.first().y)
                        coords.drop(1).forEach { path.lineTo(it.x, it.y) }
                        path.lineTo(coords.last().x, size.height)
                        path.lineTo(coords.first().x, size.height)
                        path.close()
                        return path
                    }
                    fun smooth(coords: List<Offset>, area: Boolean): Path {
                        val path = Path()
                        if (coords.isEmpty()) return path
                        path.moveTo(coords.first().x, coords.first().y)
                        for (i in 0 until coords.lastIndex) {
                            val p0 = if (i == 0) coords[i] else coords[i - 1]
                            val p1 = coords[i]
                            val p2 = coords[i + 1]
                            val p3 = if (i + 2 < coords.size) coords[i + 2] else p2
                            val c1 = Offset(p1.x + (p2.x - p0.x) / 6f, p1.y + (p2.y - p0.y) / 6f)
                            val c2 = Offset(p2.x - (p3.x - p1.x) / 6f, p2.y - (p3.y - p1.y) / 6f)
                            path.cubicTo(c1.x, c1.y, c2.x, c2.y, p2.x, p2.y)
                        }
                        if (area) {
                            path.lineTo(coords.last().x, size.height)
                            path.lineTo(coords.first().x, size.height)
                            path.close()
                        }
                        return path
                    }

                    val peak = points.maxOfOrNull { maxOf(it.second, it.third) } ?: 0L
                    val simulate = points.size < 2 || peak < 4096L
                    if (!simulate) {
                        val maxRate = peak.coerceAtLeast(1L).toFloat()
                        val end = points.last().first
                        val start = end - 60_000L
                        fun series(index: Int): List<Offset> = points.map { point ->
                            val x = (((point.first - start).coerceIn(0L, 60_000L)).toFloat() / 60_000f) * size.width
                            val value = if (index == 1) point.second else point.third
                            val y = size.height - (value.toFloat() / maxRate * size.height * .86f)
                            Offset(x, y)
                        }
                        val upload = series(1)
                        val download = series(2)
                        drawPath(smooth(upload, true), brush = Brush.verticalGradient(listOf(t.success.copy(alpha = .20f), Color.Transparent), 0f, size.height))
                        drawPath(smooth(download, true), brush = Brush.verticalGradient(listOf(scheme.primary.copy(alpha = .20f), Color.Transparent), 0f, size.height))
                        drawPath(smooth(upload, false), color = t.success, style = Stroke(width = 2.15.dp.toPx()))
                        drawPath(smooth(download, false), color = scheme.primary, style = Stroke(width = 2.15.dp.toPx()))
                    } else {
                        fun wave(phaseOffset: Float, base: Float, amplitude: Float): List<Offset> = (0..28).map { i ->
                            val ratio = i / 28f
                            val angle = ratio * 8.2f + idlePhase + phaseOffset
                            val harmonic = kotlin.math.sin((angle * 1.67f).toDouble()).toFloat() * .34f
                            val y = size.height * base - (kotlin.math.sin(angle.toDouble()).toFloat() + harmonic) * size.height * amplitude
                            Offset(size.width * ratio, y)
                        }
                        val upload = wave(0f, .76f, .028f)
                        val download = wave(1.18f, .82f, .036f)
                        drawPath(closeArea(upload), brush = Brush.verticalGradient(listOf(t.success.copy(alpha = .18f), Color.Transparent), size.height * .68f, size.height))
                        drawPath(closeArea(download), brush = Brush.verticalGradient(listOf(scheme.primary.copy(alpha = .20f), Color.Transparent), size.height * .70f, size.height))
                        drawPath(smooth(upload, false), color = t.success.copy(alpha = .78f), style = Stroke(width = 1.8.dp.toPx()))
                        drawPath(smooth(download, false), color = scheme.primary.copy(alpha = .84f), style = Stroke(width = 1.8.dp.toPx()))
                    }
                }
            }
        }
        val tcpCount = state.connections.count { it.network.contains("tcp", ignoreCase = true) }
        val udpCount = state.connections.count { it.network.contains("udp", ignoreCase = true) }
        val protocolCount = tcpCount + udpCount
        val tcpPercent = if (protocolCount > 0) (tcpCount * 100 / protocolCount) else 0
        val udpPercent = if (protocolCount > 0) (udpCount * 100 / protocolCount) else 0
        val tcpRatio = if (protocolCount > 0) tcpCount.toFloat() / protocolCount.toFloat() else 0f
        val inboundCount = state.connections.count { it.inbound.isNotBlank() }
        val routedHits = state.connections.count { it.rule.isNotBlank() || it.chain.isNotBlank() }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Surface(
                modifier = Modifier.weight(1f).height(92.dp),
                shape = RoundedCornerShape(18.dp),
                color = t.cardBackground,
                shadowElevation = 1.dp,
            ) {
                Column(Modifier.fillMaxSize().padding(13.dp), verticalArrangement = Arrangement.SpaceBetween) {
                    Text("连接协议", color = Color(0xFF94A3B8), fontSize = 11.sp, fontWeight = FontWeight.Medium)
                    Text("TCP $tcpPercent%  ·  UDP $udpPercent%", color = t.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
                    Box(Modifier.fillMaxWidth().height(4.dp).background(Color(0xFFE2E8F0), CircleShape)) {
                        if (protocolCount > 0) {
                            Row(Modifier.fillMaxSize().clip(CircleShape)) {
                                if (tcpCount > 0) Box(Modifier.weight(tcpRatio.coerceAtLeast(.01f)).fillMaxHeight().background(Color(0xFF2563EB)))
                                if (udpCount > 0) Box(Modifier.weight((1f - tcpRatio).coerceAtLeast(.01f)).fillMaxHeight().background(Color(0xFFF59E0B)))
                            }
                        }
                    }
                }
            }
            Surface(
                modifier = Modifier.weight(1f).height(92.dp),
                shape = RoundedCornerShape(18.dp),
                color = t.cardBackground,
                shadowElevation = 1.dp,
            ) {
                Column(Modifier.fillMaxSize().padding(13.dp), verticalArrangement = Arrangement.SpaceBetween) {
                    Text("活动会话", color = Color(0xFF94A3B8), fontSize = 11.sp, fontWeight = FontWeight.Medium)
                    Text(state.connections.size.toString(), color = Color(0xFF002FA7), fontSize = 22.sp, lineHeight = 25.sp, fontWeight = FontWeight.Black)
                    Text("入站 $inboundCount  ·  分流命中 $routedHits", color = Color(0xFF64748B), fontSize = 10.sp, lineHeight = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                }
            }
        }

    }
}

@Composable
private fun RefRateCard(title: String, value: Long, icon: ImageVector, color: Color, modifier: Modifier) {
    val t = LocalHetuTokens.current
    Surface(modifier = modifier, shape = RoundedCornerShape(18.dp), color = t.cardBackground, shadowElevation = 1.dp) {
        Row(
            Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(34.dp).background(color.copy(alpha = .10f), CircleShape), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = color, modifier = Modifier.size(19.dp))
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(title, color = Color(0xFF94A3B8), fontSize = 11.sp, fontWeight = FontWeight.Medium)
                Text(refSpeed(value), color = t.textPrimary, fontSize = 19.sp, lineHeight = 23.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
            }
        }
    }
}

@Composable
private fun RefProviderRow(item: DashboardProviderUi, refreshing: Boolean, success: Boolean, onRefresh: () -> Unit, onClick: () -> Unit) {
    val t = LocalHetuTokens.current
    val scheme = MaterialTheme.colorScheme
    val spinTransition = androidx.compose.animation.core.rememberInfiniteTransition(label = "providerRefreshSpin${item.name}")
    val spin by spinTransition.animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(760, easing = androidx.compose.animation.core.LinearEasing)),
        label = "providerRefreshRotation${item.name}",
    )
    val source = remember(item.name) { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) .98f else 1f, spring(dampingRatio = .78f, stiffness = 520f), label = "provider${item.name}")
    val shape = RoundedCornerShape(22.dp)
    val usedRatio = if (item.hasSubscriptionInfo && item.total > 0L) item.ratio.coerceIn(0f, 1f) else 0f
    val progress by animateFloatAsState(usedRatio, spring(dampingRatio = .82f, stiffness = 300f), label = "providerProgress${item.name}")
    val remainingPercent = if (item.hasSubscriptionInfo && item.total > 0L) ((1f - usedRatio) * 100f).toInt() else 0
    Surface(
        modifier = Modifier.graphicsLayer { scaleX = scale; scaleY = scale; alpha = if (pressed) .92f else 1f }
            .clip(shape)
            .clickable(interactionSource = source, indication = null, onClick = onClick),
        shape = shape,
        color = t.cardBackground,
        shadowElevation = 1.dp,
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(item.name, color = t.textPrimary, fontSize = 17.sp, lineHeight = 21.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Surface(
                    onClick = { if (!refreshing) onRefresh() },
                    shape = RoundedCornerShape(999.dp),
                    color = if (success) Color(0xFFECFDF5) else Color(0xFFEFF6FF),
                    border = BorderStroke(1.dp, if (success) Color(0xFFA7F3D0) else Color(0xFFDBEAFE)),
                ) {
                    Row(Modifier.padding(horizontal = 9.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            if (success) "已更新" else if (item.hasSubscriptionInfo) "$remainingPercent%" else "同步",
                            color = if (success) Color(0xFF059669) else scheme.primary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        Icon(
                            if (success) Icons.Rounded.CheckCircle else Icons.Rounded.Sync,
                            if (success) "更新完成" else "同步更新",
                            tint = if (success) Color(0xFF10B981) else scheme.primary,
                            modifier = Modifier.size(14.dp).graphicsLayer { rotationZ = if (refreshing) spin else 0f },
                        )
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("到期 ${refExpireDate(item.expire)}", color = Color(0xFF94A3B8), fontSize = 11.sp, modifier = Modifier.weight(1f), maxLines = 1)
                Text(refUpdatedAt(item.updatedAt), color = Color(0xFF94A3B8), fontSize = 11.sp, maxLines = 1)
            }
            if (item.hasSubscriptionInfo && item.total > 0L) {
                Box(Modifier.fillMaxWidth().height(6.dp).background(Color(0xFFF1F5F9), CircleShape)) {
                    if (progress > 0f) {
                        Box(
                            Modifier.fillMaxWidth(progress.coerceIn(.001f, 1f)).fillMaxHeight()
                                .background(Brush.horizontalGradient(listOf(Color(0xFF3B82F6), Color(0xFF6366F1))), CircleShape),
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(refBytes(item.upload), color = t.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
                        Text("已上传", color = Color(0xFF94A3B8), fontSize = 10.sp)
                    }
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(refBytes(item.download), color = t.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
                        Text("已下载", color = Color(0xFF94A3B8), fontSize = 10.sp)
                    }
                    Surface(modifier = Modifier.weight(1f), shape = RoundedCornerShape(14.dp), color = Color(0xFFEFF6FF), border = BorderStroke(1.dp, Color(0xFFDBEAFE))) {
                        Column(Modifier.padding(horizontal = 7.dp, vertical = 7.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(refBytes(item.remaining), color = Color(0xFF2563EB), fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
                            Text("剩余流量", color = Color(0xFF2563EB), fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
                HorizontalDivider(color = Color(0xFFF1F5F9))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("已用 ${refBytes(item.used)}", color = Color(0xFF64748B), fontSize = 11.sp, modifier = Modifier.weight(1f))
                    Text("总计 ${refBytes(item.total)}", color = Color(0xFF64748B), fontSize = 11.sp)
                }
            } else {
                Text("该订阅没有上报流量信息", color = t.textMuted, style = MaterialTheme.typography.bodySmall)
            }
        }
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
            else -> "无法从 Mihomo 元数据识别进程"
        }
        RefConnectionAppGroup(key, title, subtitle, first.appIcon, connections)
    }.sortedWith(compareByDescending<RefConnectionAppGroup> { it.connections.size }.thenBy { it.title.lowercase() })
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
                        .background(if (dark) Color.White.copy(alpha = .06f) else Color(0xFFF1F5F9), RoundedCornerShape(13.dp))
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
                            maxLines = 1,
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
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            )
                        }
                    }
                    Text(group.subtitle, color = t.textSecondary, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        "↑ ${refBytes(group.upload)}   ↓ ${refBytes(group.download)}",
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
                                Text("终止该应用全部连接", color = t.danger, fontSize = 11.sp)
                            }
                        }
                    }
                    group.connections.take(30).forEachIndexed { index, item ->
                        androidx.compose.animation.AnimatedVisibility(
                            visible = expanded,
                            enter = androidx.compose.animation.fadeIn(
                                androidx.compose.animation.core.tween(150, delayMillis = (index * 12).coerceAtMost(180)),
                            ) + androidx.compose.animation.slideInVertically(
                                androidx.compose.animation.core.tween(180, delayMillis = (index * 12).coerceAtMost(180)),
                            ) { it / 5 },
                        ) {
                            RefConnectionRow(item, onCloseConnection?.let { action -> { action(item) } })
                        }
                    }
                    if (group.connections.size > 30) {
                        Text(
                            "还有 ${group.connections.size - 30} 条连接未展开，避免一次渲染过多。",
                            color = t.textMuted,
                            fontSize = 10.sp,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                        )
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
                Text("↑ ${refBytes(item.upload)}   ↓ ${refBytes(item.download)}", color = t.textMuted, style = MaterialTheme.typography.labelSmall)
            }
            if (onClose != null) {
                Spacer(Modifier.width(8.dp))
                Box(
                    Modifier.size(28.dp)
                        .graphicsLayer { scaleX = closeScale; scaleY = closeScale }
                        .background(if (dark) Color.White.copy(alpha = .07f) else Color(0xFFF1F5F9).copy(alpha = .82f), CircleShape)
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
private fun RefRuleGroupCard(items: List<ProxyRuleUi>) {
    val t = LocalHetuTokens.current
    val dark = MaterialTheme.colorScheme.background.luminance() < .5f
    val shape = RoundedCornerShape(22.dp)
    val ruleGlassBrush = if (dark) {
        Brush.verticalGradient(listOf(Color(0xFF1B2431).copy(alpha = .88f), Color(0xFF151D29).copy(alpha = .82f)))
    } else {
        Brush.verticalGradient(listOf(Color.White.copy(alpha = .95f), Color(0xFFF8FAFC).copy(alpha = .85f)))
    }
    Surface(
        modifier = Modifier.background(ruleGlassBrush, shape),
        shape = shape,
        color = Color.Transparent,
        border = BorderStroke(.5.dp, if (dark) t.outline.copy(alpha = .32f) else Color(0xFFF1F5F9)),
        shadowElevation = 0.dp,
    ) {
        Column(Modifier.fillMaxWidth()) {
            items.forEachIndexed { index, item ->
                val expression = item.payload.ifBlank { item.type }
                val composite = expression.contains("&&") || expression.contains("||") ||
                    expression.count { it == '(' } >= 2
                var expanded by remember(item.type, item.payload, item.proxy) { mutableStateOf(false) }
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 9.dp),
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
                                    maxLines = if (open) 5 else 1,
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
                        modifier = Modifier.padding(horizontal = 14.dp),
                        thickness = .5.dp,
                        color = if (dark) t.outline.copy(alpha = .28f) else Color(0xFFF1F5F9).copy(alpha = .84f),
                    )
                }
            }
        }
    }
}

@Composable
private fun RefRuleSetRow(item: DashboardRuleSetUi, refreshing: Boolean, success: Boolean, onRefresh: () -> Unit) {
    val t = LocalHetuTokens.current
    val scheme = MaterialTheme.colorScheme
    val spinTransition = androidx.compose.animation.core.rememberInfiniteTransition(label = "ruleSetRefreshSpin${item.name}")
    val spin by spinTransition.animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(760, easing = androidx.compose.animation.core.LinearEasing)),
        label = "ruleSetRefreshRotation${item.name}",
    )
    Surface(shape = RoundedCornerShape(20.dp), color = t.cardBackground, shadowElevation = 1.dp) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(item.name, color = t.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.width(7.dp))
                    Surface(shape = RoundedCornerShape(6.dp), color = Color(0xFFEBF3FF)) {
                        Text(
                            if (item.ruleCount > 0) "${item.ruleCount} 条规则" else "规则数 —",
                            Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                            color = scheme.primary,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                        )
                    }
                }
                Text(listOf(item.behavior, item.format, item.vehicleType).filter { it.isNotBlank() }.joinToString(" / "), color = Color(0xFF64748B), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(refUpdatedAt(item.updatedAt), color = Color(0xFF94A3B8), fontSize = 11.sp, maxLines = 1)
            }
            IconButton(onClick = { if (!refreshing) onRefresh() }, modifier = Modifier.size(38.dp)) {
                Icon(if (success) Icons.Rounded.CheckCircle else Icons.Rounded.Download, if (success) "更新完成" else "远端更新", tint = if (success) Color(0xFF10B981) else scheme.primary, modifier = Modifier.size(20.dp).graphicsLayer { rotationZ = if (refreshing) spin else 0f })
            }
        }
    }
}

@Composable
private fun RefTools(state: ProxyComposeState, onLog: (String) -> Unit) {
    val context = LocalContext.current
    val inspector = remember { ProxyRuntimeInspector(context) }
    val scope = rememberCoroutineScope()
    val t = LocalHetuTokens.current
    val dark = MaterialTheme.colorScheme.background.luminance() < .5f


    LazyColumn(
        Modifier.fillMaxSize().statusBarsPadding().background(if (dark) t.pageBackground else Color(0xFFF1F5F9)),
        contentPadding = PaddingValues(
            start = 16.dp,
            top = 8.dp,
            end = 16.dp,
            bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 94.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { RefTitleBar("工具") }
        item { RefSectionLabel("系统服务") }
        item {
            RefGroup {
                RefToolRow(
                    Icons.Rounded.Terminal,
                    Color(0xFF2563EB),
                    "运行文件",
                    "启动配置与运行文件",
                    trailingText = if (state.running) "运行中" else "待机",
                    trailingBadge = true,
                    trailingColor = if (state.running) Color(0xFF059669) else Color(0xFF64748B),
                ) { context.startActivity(Intent(context, ReferenceFileManagerActivity::class.java)) }
                RefDivider()
                RefToolRow(Icons.Rounded.Article, Color(0xFF9333EA), "日志查看", "查看实时运行日志与调试") {
                    scope.launch { onLog(runCatching { inspector.runtimeLog() }.getOrElse { it.message ?: "日志读取失败" }) }
                }
                RefDivider()
                RefToolRow(Icons.Rounded.Apps, Color(0xFFF97316), "应用名单", "Root 分应用代理范围", trailingText = "管理", trailingColor = Color(0xFF2563EB)) {
                    context.startActivity(Intent(context, ProxyAppSelectionActivity::class.java))
                }
            }
        }
        item { RefSectionLabel("网络与共享") }
        item {
            RefGroup {
                RefToolRow(Icons.Rounded.Wifi, Color(0xFF0EA5E9), "网络匹配", "Wi‑Fi / SSID / 移动网络自动启停", trailingText = "自动化", trailingColor = Color(0xFF2563EB)) {
                    context.startActivity(Intent(context, ProxyNetworkAutomationActivity::class.java))
                }
                RefDivider()
                RefToolRow(Icons.Rounded.WifiTethering, Color(0xFF10B981), "共享网络", "热点与局域网共享 · Root 规则", trailingText = "设置", trailingColor = Color(0xFF2563EB)) {
                    context.startActivity(Intent(context, ProxySharedNetworkSettingsActivity::class.java))
                }
                RefDivider()
                RefToolRow(Icons.Rounded.AltRoute, Color(0xFFEF4444), "绕过规则", "CIDR 与接口绕过 · Root 规则", trailingText = "设置", trailingColor = Color(0xFF2563EB)) {
                    context.startActivity(Intent(context, ProxyBypassRulesActivity::class.java))
                }
            }
        }
        item { RefSectionLabel("订阅与数据") }
        item {
            RefGroup {
                RefToolRow(Icons.Rounded.CloudDownload, Color(0xFF2563EB), "订阅管理", "链接 · 多配置 · YAML", trailingText = "管理", trailingColor = Color(0xFF2563EB)) {
                    context.startActivity(Intent(context, ProxySubscriptionActivity::class.java))
                }
                RefDivider()
                RefToolRow(Icons.Rounded.Shield, Color(0xFF2563EB), "广告过滤", "代理串联 · 规则 REJECT · 独立兜底", trailingText = "管理", trailingColor = Color(0xFF2563EB)) {
                    context.startActivity(Intent(context, ProxyAdblockChainActivity::class.java))
                }
                RefDivider()
                RefToolRow(Icons.Rounded.Public, Color(0xFFF59E0B), "CNIP 设置", "国内 IPv4/IPv6 自动直连", trailingText = "设置", trailingColor = Color(0xFF2563EB)) {
                    context.startActivity(Intent(context, ProxyCnIpSettingsActivity::class.java))
                }
            }
        }
        item { RefSectionLabel("核心与更新") }
        item {
            RefGroup {
                RefToolRow(Icons.Rounded.Memory, Color(0xFF334155), "内核管理", "下载、更新与维护内核", trailingText = state.core.ifBlank { "Mihomo" }, trailingBadge = true, trailingColor = Color(0xFF2563EB)) {
                    context.startActivity(Intent(context, ProxyCoreActivity::class.java))
                }
            }
        }
    }
}

@Composable
private fun RefSettings(state: ProxyComposeState, onChanged: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("hetu", 0) }
    var modePicker by remember { mutableStateOf(false) }
    var ipv6Picker by remember { mutableStateOf(false) }
    var latencyPicker by remember { mutableStateOf(false) }
    var portsInfo by remember { mutableStateOf(false) }
    var autoStart by remember { mutableStateOf(prefs.getBoolean("proxyRootAutoStart", false)) }
    var blurEnabled by remember { mutableStateOf(prefs.getBoolean("enableBlur", true)) }
    var latencyInterval by remember { mutableIntStateOf(prefs.getInt("latencyAutoRefreshSeconds", 0).takeIf { it == 0 || it == 30 || it == 60 } ?: 0) }

    val t = LocalHetuTokens.current
    val dark = MaterialTheme.colorScheme.background.luminance() < .5f
    LazyColumn(
        Modifier.fillMaxSize().statusBarsPadding().background(if (dark) t.pageBackground else Color(0xFFF1F5F9)),
        contentPadding = PaddingValues(
            start = 16.dp,
            top = 8.dp,
            end = 16.dp,
            bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 94.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { RefTitleBar("设置") }
        item { RefSectionLabel("核心与运行") }
        item {
            RefGroup {
                RefValueRow("运行核心", state.core, Icons.Rounded.Memory, Color(0xFF334155), highlightValue = true) {
                    context.startActivity(Intent(context, ProxyRuntimeCoreSettingsActivity::class.java))
                }
                RefDivider()
                RefValueRow("运行模式", state.mode, Icons.Rounded.Tune, Color(0xFF2563EB), highlightValue = true) { modePicker = true }
                RefDivider()
                RefValueRow("IPv6", state.ipv6, Icons.Rounded.Public, Color(0xFF10B981), highlightValue = true) { ipv6Picker = true }
                RefDivider()
                RefSwitchRow(
                    icon = Icons.Rounded.Bolt,
                    accent = Color(0xFF2563EB),
                    title = "开机自启",
                    subtitle = "重启后自动恢复上次启用的代理保护",
                    checked = autoStart,
                ) { enabled ->
                    autoStart = enabled
                    prefs.edit().putBoolean("proxyRootAutoStart", enabled).apply()
                }
            }
        }
        item { RefSectionLabel("网络与配置") }
        item {
            RefGroup {
                RefValueRow(
                    "延迟自动刷新",
                    if (latencyInterval <= 0) "关闭" else "${latencyInterval} 秒",
                    Icons.Rounded.Speed,
                    Color(0xFF2563EB),
                    highlightValue = latencyInterval > 0,
                ) { latencyPicker = true }
                RefDivider()
                RefValueRow(
                    "端口细则",
                    "${MihomoStartupConfig.TPROXY_PORT} / ${MihomoStartupConfig.REDIRECT_PORT}",
                    Icons.Rounded.Hub,
                    Color(0xFFF97316),
                    highlightValue = true,
                ) { portsInfo = true }
                RefDivider()
                RefValueRow("高级代理配置", "应用范围 · DNS · QUIC · CNIP · 共享 · 绕过", Icons.Rounded.Tune, Color(0xFF0EA5E9), highlightValue = true) {
                    context.startActivity(Intent(context, ProxyAdvancedSettingsActivity::class.java))
                }
            }
        }
        item { RefSectionLabel("界面") }
        item {
            RefGroup {
                RefSwitchRow(
                    icon = Icons.Rounded.BlurOn,
                    accent = Color(0xFF8B5CF6),
                    title = "模糊效果",
                    subtitle = "控制应用内磨砂与背景模糊",
                    checked = blurEnabled,
                ) { enabled ->
                    blurEnabled = enabled
                    prefs.edit().putBoolean("enableBlur", enabled).apply()
                }
                RefDivider()
                RefValueRow("主题与界面", "Miuix · Monet · OLED", Icons.Rounded.Palette, Color(0xFFEC4899)) {
                    context.startActivity(Intent(context, ThemeSettingsActivity::class.java))
                }
            }
        }
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
private fun RefInfoBottomSheet(
    title: String,
    text: String,
    actionLabel: String,
    onDismiss: () -> Unit,
) {
    val t = LocalHetuTokens.current
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
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.size(width = 38.dp, height = 30.dp).background(if (terminal) Color.White.copy(alpha = .07f) else t.controlBackground.copy(alpha = .72f), RoundedCornerShape(12.dp)),
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
    val t = LocalHetuTokens.current
    val dark = MaterialTheme.colorScheme.background.luminance() < .5f
    val shape = RoundedCornerShape(22.dp)
    val brush = if (dark) {
        Brush.verticalGradient(listOf(t.elevatedCardBackground, t.cardBackground))
    } else {
        Brush.verticalGradient(listOf(Color.White, Color(0xFFFAFBFD)))
    }
    Column(
        Modifier.fillMaxWidth()
            .shadow(if (dark) 0.dp else 5.dp, shape, clip = false, ambientColor = Color(0xFF0F172A).copy(alpha = .035f), spotColor = Color(0xFF0F172A).copy(alpha = .045f))
            .background(brush, shape)
            .border(.7.dp, if (dark) t.outline.copy(alpha = .45f) else Color.White.copy(alpha = .92f), shape)
            .clip(shape),
        content = content,
    )
}

@Composable
private fun RefSectionLabel(text: String) {
    Text(
        text,
        color = Color(0xFF94A3B8),
        fontSize = 11.sp,
        lineHeight = 14.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = .6.sp,
        modifier = Modifier.padding(start = 12.dp, top = 2.dp, bottom = 0.dp),
    )
}

@Composable
private fun RefGradientIcon(icon: ImageVector, accent: Color) {
    val shape = RoundedCornerShape(11.dp)
    val brush = Brush.linearGradient(
        listOf(
            accent.copy(alpha = .76f),
            accent,
        ),
    )
    Box(
        Modifier.size(36.dp)
            .shadow(7.dp, shape, clip = false, ambientColor = accent.copy(alpha = .24f), spotColor = accent.copy(alpha = .28f))
            .background(brush, shape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun RefToolRow(
    icon: ImageVector,
    accent: Color,
    title: String,
    subtitle: String,
    trailingText: String = "",
    trailingBadge: Boolean = false,
    trailingColor: Color = Color(0xFF2563EB),
    onClick: () -> Unit,
) {
    val t = LocalHetuTokens.current
    val source = remember(title) { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) .96f else 1f, spring(dampingRatio = .78f, stiffness = 560f), label = "tool$title")
    Row(
        Modifier.fillMaxWidth()
            .graphicsLayer { scaleX = scale; scaleY = scale; alpha = if (pressed) .91f else 1f }
            .background(if (pressed) t.controlBackground.copy(alpha = .42f) else Color.Transparent)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RefGradientIcon(icon, accent)
        Spacer(Modifier.width(13.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, color = t.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Text(subtitle, color = Color(0xFF94A3B8), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(8.dp))
        if (trailingText.isNotBlank()) {
            if (trailingBadge) {
                Surface(shape = CircleShape, color = trailingColor.copy(alpha = .10f), tonalElevation = 0.dp) {
                    Row(
                        Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        Box(Modifier.size(6.dp).background(trailingColor, CircleShape))
                        Text(trailingText, color = trailingColor, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            } else {
                Text(trailingText, color = Color(0xFF94A3B8), fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 110.dp))
                Spacer(Modifier.width(4.dp))
                Icon(Icons.Rounded.ChevronRight, null, tint = Color(0xFFCBD5E1), modifier = Modifier.size(15.dp))
            }
        } else {
            Icon(Icons.Rounded.ChevronRight, null, tint = Color(0xFFCBD5E1), modifier = Modifier.size(17.dp))
        }
    }
}

@Composable
private fun RefValueRow(
    title: String,
    value: String,
    icon: ImageVector? = null,
    accent: Color = Color(0xFF64748B),
    highlightValue: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val t = LocalHetuTokens.current
    val source = remember(title) { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed && onClick != null) .96f else 1f, spring(dampingRatio = .80f, stiffness = 560f), label = "value$title")
    val modifier = if (onClick == null) Modifier.fillMaxWidth() else Modifier.fillMaxWidth()
        .graphicsLayer { scaleX = scale; scaleY = scale; alpha = if (pressed) .91f else 1f }
        .background(if (pressed) t.controlBackground.copy(alpha = .42f) else Color.Transparent)
        .clickable(interactionSource = source, indication = null, onClick = onClick)
    Row(modifier.padding(horizontal = 14.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) {
            RefGradientIcon(icon, accent)
            Spacer(Modifier.width(13.dp))
        }
        Text(title, color = t.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        if (value.isNotBlank()) {
            Text(
                value,
                color = Color(0xFF94A3B8),
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 138.dp),
            )
        }
        if (onClick != null) {
            Spacer(Modifier.width(5.dp))
            Icon(Icons.Rounded.ChevronRight, null, tint = Color(0xFFCBD5E1), modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
private fun RefSwitchRow(
    icon: ImageVector,
    accent: Color,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val t = LocalHetuTokens.current
    val view = LocalView.current
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RefGradientIcon(icon, accent)
        Spacer(Modifier.width(13.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, color = t.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Text(subtitle, color = Color(0xFF94A3B8), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(10.dp))
        Switch(
            checked = checked,
            onCheckedChange = { value ->
                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                onCheckedChange(value)
            },
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = Color(0xFF2563EB),
                uncheckedThumbColor = Color.White,
                uncheckedTrackColor = Color(0xFFE2E8F0),
                uncheckedBorderColor = Color.Transparent,
            ),
        )
    }
}

@Composable
private fun RefDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(start = 58.dp, end = 14.dp),
        thickness = 1.dp,
        color = if (MaterialTheme.colorScheme.background.luminance() < .5f) LocalHetuTokens.current.outline else Color(0xFFF4F6F9),
    )
}

@Composable
private fun RefMetric(title: String, value: String, modifier: Modifier) {
    val t = LocalHetuTokens.current
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(value, color = if (MaterialTheme.colorScheme.background.luminance() < .5f) t.textPrimary else Color(0xFF0F172A), fontSize = 19.sp, lineHeight = 23.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(title, color = Color(0xFF94A3B8), fontSize = 11.sp, lineHeight = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1)
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
        Text(title, color = t.textPrimary, fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        IconButton(onClick = onRefresh) { Icon(Icons.Rounded.Refresh, "刷新", tint = t.textPrimary) }
    }
}

@Composable
private fun RefTitleBar(title: String) {
    Text(
        title,
        color = LocalHetuTokens.current.textPrimary,
        fontSize = 26.sp,
        lineHeight = 32.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 8.dp),
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

private fun refDelay(value: Long?): String = when {
    value == null -> "--"
    value <= 0L -> "超时"
    else -> "$value ms"
}

private fun refBytes(value: Long): String {
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

private fun refSpeed(value: Long): String = "${refBytes(value)}/s"

private fun refDuration(seconds: Long): String {
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
