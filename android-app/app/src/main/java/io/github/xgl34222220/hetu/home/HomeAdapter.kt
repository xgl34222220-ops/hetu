package io.github.xgl34222220.hetu.home

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.SharedPreferences
import android.net.TrafficStats
import android.os.Build
import android.os.SystemClock
import android.widget.Toast
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import io.github.xgl34222220.hetu.CompactHomeData
import io.github.xgl34222220.hetu.HomeOperation
import io.github.xgl34222220.hetu.HomeResourceSample
import io.github.xgl34222220.hetu.LocalHxBlur
import io.github.xgl34222220.hetu.ProxyGroupUi
import io.github.xgl34222220.hetu.ProxyLatencyTarget
import io.github.xgl34222220.hetu.ProxyLatencyTargets
import io.github.xgl34222220.hetu.ui.HetuHaptic
import io.github.xgl34222220.hetu.ui.rememberHetuHaptics
import kotlinx.coroutines.delay

/*
 * The only file in this module that touches existing source types. If a signature in the repo
 * differs from test/v20.48-concept-parity as shipped in hetu-source-v20.48.zip, fix it here;
 * nothing else in `home/` depends on the rest of the app.
 *
 * Preference keys read: appearance, pureBlackDark, accentHex, enableMonet, latencyAutoRefreshSeconds.
 * Preference keys added: homeNetSide ("wan" | "lan"), homeSpeedSource ("api" | "local").
 */

private const val PrefNetSide = "homeNetSide"
private const val PrefSpeedSource = "homeSpeedSource"
private const val PrefAutoRefresh = "latencyAutoRefreshSeconds"

/**
 * Drop-in replacement for the `CompactHomeDashboard(...)` call inside `RefHome`.
 *
 * @param data the same [CompactHomeData] RefHome already builds.
 * @param groups `state.groups`, used to resolve the current exit node.
 * @param trafficMode `state.trafficMode` ("rule" | "global" | "direct").
 * @param startupError the shell's `startupError`; non-null shows the failure sheet instead of HxTextSheet.
 * @param corePid `runtime.pid`; [coreVersion] e.g. from `ProxyDashboardRepository.coreVersion()`.
 * @param cpuAffinity / [currentCpu] are not sampled by the source yet; null renders “—”.
 * @param bottomPadding the shell's dock clearance (`HxDockHeight + insets`), same value other tabs get.
 * @param wanExtras the public-address fields CompactHomeData does not carry (city, organisation, IP type,
 *        time zone, coordinates) plus the lookup state; without them the IP page shows “未知”.
 * @param operationText what the running start / stop / restart is doing, shown under the status title.
 * @param onTrafficMode call `repo.setTrafficMode(id)` and refresh, as PanelExtras19 does.
 */
@Composable
internal fun HetuHomeV2(
    data: CompactHomeData,
    groups: List<ProxyGroupUi>,
    trafficMode: String,
    startupError: String?,
    corePid: Int,
    coreVersion: String,
    bottomPadding: Dp,
    onToggle: () -> Unit,
    onReload: () -> Unit,
    onRestart: () -> Unit,
    onDelay: () -> Unit,
    onRefresh: () -> Unit,
    onTrafficMode: (String) -> Unit,
    onOpenNode: () -> Unit,
    onOpenSubscription: () -> Unit,
    onOpenBasicSettings: () -> Unit,
    onOpenConfigs: () -> Unit,
    onViewConfig: () -> Unit,
    onDismissStartupError: () -> Unit,
    modifier: Modifier = Modifier,
    cpuAffinity: String? = null,
    currentCpu: Int? = null,
    resourceSamples: List<HomeResourceSample>? = null,
    onDetailVisibleChange: (Boolean) -> Unit = {},
    wanExtras: HomeWanExtras? = null,
    operationText: String? = null,
    connection: HomeConnectionObservation = HomeConnectionObservation(),
) {
    val context = LocalContext.current
    val prefs = remember(context) { context.getSharedPreferences("hetu", Context.MODE_PRIVATE) }
    val hetuHaptics = rememberHetuHaptics()
    val motion = homeMotionEnabled()

    var netSide by remember { mutableStateOf(if (prefs.getString(PrefNetSide, "wan") == "lan") HomeNetSide.Lan else HomeNetSide.Wan) }
    var speedSource by remember { mutableStateOf(HomeSpeedSource.fromId(prefs.getString(PrefSpeedSource, "api"))) }
    val localSpeed = rememberLocalSpeed(enabled = speedSource == HomeSpeedSource.Local)
    val status = homeStatusOf(data, startupError)
    val sampling = status is HomeStatus.Running || status is HomeStatus.PendingRestart
    val history = rememberResourceHistory(sampling && resourceSamples == null, data.cpu, data.memory)

    val mapped = data.toHomeUiState(
        status = status,
        groups = groups,
        trafficMode = trafficMode,
        netSide = netSide,
        speedSource = speedSource,
        localSpeed = localSpeed,
        resource = HomeResource(
            memoryBytes = data.memory.takeIf { it > 0L },
            cpuPercent = data.cpu.takeIf { !it.isNaN() },
            pid = corePid.takeIf { it > 0 },
            coreVersion = listOf(data.core, coreVersion).filter { it.isNotBlank() }.joinToString(" "),
            cpuAffinity = cpuAffinity,
            currentCpu = currentCpu,
            connections = data.connections,
            cpuHistory = resourceSamples?.map { it.cpuPercent } ?: history.first,
            memoryHistoryMb = resourceSamples?.map { it.memoryBytes?.div(1024f * 1024f) } ?: history.second,
        ),
    )
    val state = mapped.copy(
        connection = connection,
        statusDetail = operationText?.trim()?.takeIf { it.isNotEmpty() && status.isBusy },
        wan = if (wanExtras == null) mapped.wan else mapped.wan.copy(
            city = wanExtras.city.known(),
            organization = wanExtras.organization.known(),
            ipType = wanExtras.ipType.known(),
            timezone = wanExtras.timezone.known(),
            coordinates = wanExtras.coordinates.known(),
            state = HomeWanState.fromId(wanExtras.state),
            error = wanExtras.error.known(),
        ),
    )

    // Rebuilt on every recomposition on purpose: the lambdas close over the caller's latest callbacks.
    val actions = run {
        HomeActions(
            onStart = onToggle,
            onStop = onToggle,
            onReload = onReload,
            onRestart = onRestart,
            onProxyModeChange = { onTrafficMode(it.id) },
            onOpenNode = onOpenNode,
            onProbe = onDelay,
            onNetSideChange = { side ->
                netSide = side
                prefs.edit().putString(PrefNetSide, if (side == HomeNetSide.Lan) "lan" else "wan").apply()
            },
            onSpeedSourceChange = { source ->
                speedSource = source
                prefs.edit().putString(PrefSpeedSource, source.id).apply()
            },
            onRefreshIp = onRefresh,
            onOpenSubscription = onOpenSubscription,
            onOpenBasicSettings = onOpenBasicSettings,
            onOpenConfigs = onOpenConfigs,
            onViewConfig = onViewConfig,
            onDismissStartFailure = onDismissStartupError,
            onCopy = { label, text -> copyToClipboard(context, label, text) },
            loadTargets = { loadTargets(prefs) },
            saveTargets = { config -> saveTargets(prefs, config) },
            resetTargets = { ProxyLatencyTargets.reset(prefs); loadTargets(prefs) },
        )
    }

    // Static local: a fresh lambda on every poll would invalidate the whole home tree.
    val homeHaptics = remember(hetuHaptics) { hetuHaptics.asHomeHaptics() }
    HetuHomeThemeFromPrefs(prefs) {
        CompositionLocalProvider(
            LocalHomeHaptics provides homeHaptics,
        ) {
            HomeRoute(
                state = state.copy(ipRefreshing = data.refreshing),
                actions = actions,
                modifier = modifier,
                contentPadding = PaddingValues(bottom = bottomPadding),
                motion = motion,
                onDetailVisibleChange = onDetailVisibleChange,
            )
        }
    }
}

/* ------------------------------------------------------------------ */
/*  Mapping                                                             */
/* ------------------------------------------------------------------ */

/** Public-address details that travel next to [CompactHomeData]. Blank or “—” fields count as unknown. */
internal data class HomeWanExtras(
    val city: String = "",
    val organization: String = "",
    val ipType: String = "",
    val timezone: String = "",
    val coordinates: String = "",
    /** `ProxyRuntimeSnapshot.wanState`: idle, loading, success, stale or failed. */
    val state: String = "",
    val error: String = "",
)

internal fun homeStatusOf(data: CompactHomeData, startupError: String?): HomeStatus = when {
    data.operation == HomeOperation.Restart -> HomeStatus.Restarting
    data.operation == HomeOperation.Stop -> HomeStatus.Stopping
    data.operation == HomeOperation.Start -> HomeStatus.Starting
    data.busy && !data.running -> HomeStatus.Starting
    !data.running && !startupError.isNullOrBlank() -> HomeStatus.StartFailed(startupError)
    data.running && data.pendingSettings -> HomeStatus.PendingRestart(data.uptimeSeconds)
    data.running -> HomeStatus.Running(data.uptimeSeconds)
    else -> HomeStatus.NotRunning
}

private fun String?.known(): String? = this?.trim()?.takeIf { it.isNotEmpty() && it != "—" && it != "-" }

internal fun CompactHomeData.toHomeUiState(
    status: HomeStatus,
    groups: List<ProxyGroupUi>,
    trafficMode: String,
    netSide: HomeNetSide,
    speedSource: HomeSpeedSource,
    localSpeed: Pair<Long?, Long?>,
    resource: HomeResource,
): HomeUiState {
    val proxyMode = HomeProxyMode.fromId(trafficMode)
    val api = speedSource == HomeSpeedSource.Api
    return HomeUiState(
        status = status,
        core = core.ifBlank { "Mihomo" },
        runMode = mode.ifBlank { "TPROXY" },
        config = config,
        proxyMode = proxyMode,
        node = currentNode(groups, proxyMode),
        probes = latencyTargets.map { name -> HomeProbe(name, delays[name]?.let { if (it > 0L) it else HomeDelay.Timeout }) },
        probing = testing,
        netSide = netSide,
        wan = HomeWan(
            ip = wan.known(),
            countryCode = countryCode,
            region = region.known(),
            isp = isp.known(),
            asn = asn.known(),
        ),
        lan = HomeLan(lan.known(), lanInterface.known()),
        speedSource = speedSource,
        uploadBytesPerSecond = if (api) up else localSpeed.first,
        downloadBytesPerSecond = if (api) down else localSpeed.second,
        subscription = if (total > 0L || used > 0L) HomeSubscription(used, total) else null,
        resource = resource,
    )
}

/** Follows `now` through nested groups (at most five hops) to the leaf node and its last delay. */
internal fun currentNode(groups: List<ProxyGroupUi>, mode: HomeProxyMode): HomeNode? {
    if (groups.isEmpty()) return null
    val byName = groups.associateBy { it.name }
    val entry = (if (mode == HomeProxyMode.Global) byName["GLOBAL"] else null)
        ?: groups.firstOrNull { !it.hidden && it.name != "GLOBAL" }
        ?: return null
    var owner = entry
    var leaf = entry.now
    var hops = 0
    while (hops++ < 5) {
        val next = byName[leaf] ?: break
        owner = next
        leaf = next.now
    }
    if (leaf.isBlank()) return null
    val delay = owner.nodes.firstOrNull { it.name == leaf }?.lastDelay
    return HomeNode(entry.name, leaf, delay?.let { if (it > 0L) it else HomeDelay.Timeout })
}

/* ------------------------------------------------------------------ */
/*  Small stateful helpers                                              */
/* ------------------------------------------------------------------ */

/** Device-wide throughput from TrafficStats, sampled once a second while 本地模式 is selected. */
@Composable
private fun rememberLocalSpeed(enabled: Boolean): Pair<Long?, Long?> {
    var rates by remember { mutableStateOf<Pair<Long?, Long?>>(null to null) }
    val visible = rememberScreenVisible()
    LaunchedEffect(enabled, visible) {
        if (!enabled) { rates = null to null; return@LaunchedEffect }
        // Stopped screen: no 1 s TrafficStats wake-ups; resume from a fresh baseline.
        if (!visible) return@LaunchedEffect
        var lastTx = TrafficStats.getTotalTxBytes()
        var lastRx = TrafficStats.getTotalRxBytes()
        var lastAt = SystemClock.elapsedRealtime()
        while (true) {
            delay(1000L)
            val tx = TrafficStats.getTotalTxBytes()
            val rx = TrafficStats.getTotalRxBytes()
            val now = SystemClock.elapsedRealtime()
            val elapsed = (now - lastAt).coerceAtLeast(1L)
            val unsupported = TrafficStats.UNSUPPORTED.toLong()
            if (tx != unsupported && rx != unsupported && tx >= lastTx && rx >= lastRx) {
                rates = ((tx - lastTx) * 1000L / elapsed) to ((rx - lastRx) * 1000L / elapsed)
            }
            lastTx = tx; lastRx = rx; lastAt = now
        }
    }
    return rates
}

/**
 * Rolling window (30 points, one every 2 s) of CPU % and memory MB for the 资源占用 charts.
 * A sample the source could not provide is stored as null so the curve shows the gap.
 */
@Composable
private fun rememberResourceHistory(sampling: Boolean, cpu: Float, memoryBytes: Long): Pair<List<Float?>, List<Float?>> {
    val cpuPoints = remember { mutableStateListOf<Float?>() }
    val memoryPoints = remember { mutableStateListOf<Float?>() }
    val latestCpu by rememberUpdatedState(cpu)
    val latestMemory by rememberUpdatedState(memoryBytes)
    val visible = rememberScreenVisible()
    LaunchedEffect(sampling, visible) {
        if (!sampling) { cpuPoints.clear(); memoryPoints.clear(); return@LaunchedEffect }
        // Keep the window while stopped, but do not wake every 2 s to append to it.
        if (!visible) return@LaunchedEffect
        while (true) {
            cpuPoints.add(latestCpu.takeIf { !it.isNaN() })
            memoryPoints.add(latestMemory.takeIf { it > 0L }?.let { it / 1_048_576f })
            while (cpuPoints.size > 30) { cpuPoints.removeAt(0); memoryPoints.removeAt(0) }
            delay(2000L)
        }
    }
    return cpuPoints.toList() to memoryPoints.toList()
}

private fun loadTargets(prefs: SharedPreferences): HomeTargetsConfig = HomeTargetsConfig(
    targets = ProxyLatencyTargets.load(prefs).map { HomeTarget(it.name, it.url) },
    autoRefreshSeconds = prefs.getInt(PrefAutoRefresh, 0).takeIf { it == 30 || it == 60 } ?: 0,
)

private fun saveTargets(prefs: SharedPreferences, config: HomeTargetsConfig) {
    ProxyLatencyTargets.save(prefs, config.targets.map { ProxyLatencyTarget(it.name, it.url) })
    prefs.edit().putInt(PrefAutoRefresh, config.autoRefreshSeconds).apply()
}

private fun copyToClipboard(context: Context, label: String, text: String) {
    val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    manager.setPrimaryClip(ClipData.newPlainText(label, text))
    // Android 13+ shows its own clipboard confirmation.
    if (Build.VERSION.SDK_INT < 33) Toast.makeText(context, "已复制$label", Toast.LENGTH_SHORT).show()
}

/* ------------------------------------------------------------------ */
/*  Theme bridge                                                        */
/* ------------------------------------------------------------------ */

/**
 * Resolves the home palette from the same preferences HetuTheme reads, and follows changes live.
 * Blank `accentHex` → prototype blue; a preset hex → that preset; any other valid hex → custom;
 * Monet on Android 12+ → the dynamic primary already provided by HetuTheme's MaterialTheme.
 */
@Composable
internal fun HetuHomeThemeFromPrefs(prefs: SharedPreferences, content: @Composable () -> Unit) {
    var revision by remember { mutableIntStateOf(0) }
    DisposableEffect(prefs) {
        val watched = setOf("appearance", "pureBlackDark", "accentHex", "enableMonet", "topBarBlurStyle")
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key -> if (key in watched) revision++ }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    val systemDark = isSystemInDarkTheme()
    val monetPrimary = MaterialTheme.colorScheme.primary
    val (dark, pureBlack, preset, custom) = remember(revision, systemDark, monetPrimary) {
        val appearance = prefs.getString("appearance", "system") ?: "system"
        val isDark = appearance == "dark" || (appearance == "system" && systemDark)
        val hex = prefs.getString("accentHex", "").orEmpty()
        val presetAccent = HomeAccent.fromHex(hex)
        val customAccent: Color? = when {
            prefs.getBoolean("enableMonet", false) && Build.VERSION.SDK_INT >= 31 -> monetPrimary
            presetAccent == null && hex.isNotBlank() -> runCatching { Color(android.graphics.Color.parseColor(hex)) }.getOrNull()
            else -> null
        }
        ThemeChoice(isDark, isDark && prefs.getBoolean("pureBlackDark", false), presetAccent ?: HomeAccent.Default, customAccent)
    }
    HetuHomeTheme(dark = dark, accent = preset, pureBlack = pureBlack, customAccent = custom) {
        // Pinned bars blur only when the user's「模糊效果」switch allows it, in the style they chose.
        CompositionLocalProvider(
            LocalHomeBlur provides LocalHxBlur.current,
            LocalHomeBarProgressive provides remember(revision) { prefs.getString("topBarBlurStyle", "progressive") != "gaussian" },
            content = content,
        )
    }
}

/**
 * The whole kit for a host that has nothing but the preferences: palette and bar style from
 * [HetuHomeThemeFromPrefs], plus haptics. HetuTheme wraps its content in this.
 */
@Composable
internal fun HetuHomeKit(prefs: SharedPreferences, content: @Composable () -> Unit) {
    val hetuHaptics = rememberHetuHaptics()
    val haptics = remember(hetuHaptics) {
        { kind: HomeHaptic ->
            hetuHaptics.perform(
                when (kind) {
                    HomeHaptic.Tap -> HetuHaptic.Tap
                    HomeHaptic.Tick -> HetuHaptic.Tick
                    HomeHaptic.Confirm -> HetuHaptic.Confirm
                    HomeHaptic.Reject -> HetuHaptic.Reject
                },
            )
        }
    }
    // The user's「模糊效果」switch, for hosts that have no view model to read it from.
    CompositionLocalProvider(LocalHxBlur provides prefs.getBoolean("enableBlur", true)) {
        HetuHomeThemeFromPrefs(prefs) { CompositionLocalProvider(LocalHomeHaptics provides haptics, content = content) }
    }
}

private data class ThemeChoice(val dark: Boolean, val pureBlack: Boolean, val accent: HomeAccent, val custom: Color?)

/** One stable [HomeHaptic] sink per [HetuHaptics]; callers remember it so static locals stay put. */
internal fun io.github.xgl34222220.hetu.ui.HetuHaptics.asHomeHaptics(): (HomeHaptic) -> Unit = { kind ->
    perform(
        when (kind) {
            HomeHaptic.Tap -> HetuHaptic.Tap
            HomeHaptic.Tick -> HetuHaptic.Tick
            HomeHaptic.Confirm -> HetuHaptic.Confirm
            HomeHaptic.Reject -> HetuHaptic.Reject
        },
    )
}
