package io.github.xgl34222220.hetu.panel

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.os.Build
import android.os.SystemClock
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import io.github.xgl34222220.hetu.DashboardProviderUi
import io.github.xgl34222220.hetu.ConfiguredGroupIcon
import io.github.xgl34222220.hetu.DashboardRuleSetUi
import io.github.xgl34222220.hetu.ProxyComposeState
import io.github.xgl34222220.hetu.ProxyConnectionUi
import io.github.xgl34222220.hetu.ProxyDashboardRepository
import io.github.xgl34222220.hetu.ProxyPolicyIconsActivity
import io.github.xgl34222220.hetu.ProxyRuleUi
import io.github.xgl34222220.hetu.ProxyRuntimeInspector
import io.github.xgl34222220.hetu.RefLogEntry
import io.github.xgl34222220.hetu.RefPanelTab
import io.github.xgl34222220.hetu.home.HetuHomeThemeFromPrefs
import io.github.xgl34222220.hetu.home.HomeHaptic
import io.github.xgl34222220.hetu.home.LocalHomeHaptics
import io.github.xgl34222220.hetu.refParseLogs19
import io.github.xgl34222220.hetu.ui.HetuHaptic
import io.github.xgl34222220.hetu.ui.rememberHetuHaptics
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/*
 * The only file in this module that touches existing source types. If a signature in the repo
 * differs from test/v20.48-concept-parity as shipped in hetu-source-v20.48.zip, fix it here;
 * nothing else in `panel/` depends on the rest of the app (only on `home/` tokens and components).
 *
 * Preferences read / written (all existing keys unless marked new):
 *   "hetu":                        show_hidden_groups, display_global_by_mode, group_by_provider,
 *                                  collapse_previous_group_on_expand, disconnect_on_select,
 *                                  proxyCustomDelayUrlEnabled, proxyCustomDelayUrl, proxyApiHistoryEnabled,
 *                                  panelExternalApiEnabled / Host / Port / Secret   (new, see README)
 *   "proxy_selector_preferences":  node_sort_mode, node_sort_descending, group_column_mode, group_column_count,
 *                                  node_column_mode, node_column_count, group_density, node_density, name_overflow_mode
 */

/**
 * Drop-in replacement for `RefPanel(...)` in RefProxyShell.
 *
 * @param delays the shell's shared latency map (node → ms; ≤ 0 = timeout / failure). Written on every test.
 * @param starting true while a start is in flight (drives the empty state's button spinner).
 * @param bottomPadding dock clearance, same value the other tabs get.
 * @param expandGroup group to open on entry, e.g. when arriving from 首页 › 当前节点.
 */
@Composable
internal fun HetuPanelV2(
    state: ProxyComposeState,
    repo: ProxyDashboardRepository,
    delays: MutableMap<String, Long>,
    selectedTab: RefPanelTab,
    onSelectedTabChange: (RefPanelTab) -> Unit,
    onRefreshState: suspend () -> Unit,
    onStart: () -> Unit,
    starting: Boolean,
    bottomPadding: Dp,
    modifier: Modifier = Modifier,
    expandGroup: String? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember(context) { context.getSharedPreferences("hetu", Context.MODE_PRIVATE) }
    val selectorPrefs = remember(context) { context.getSharedPreferences("proxy_selector_preferences", Context.MODE_PRIVATE) }
    val hetuHaptics = rememberHetuHaptics()
    val inspector = remember(context) { ProxyRuntimeInspector(context) }

    var providers by remember { mutableStateOf<List<DashboardProviderUi>>(emptyList()) }
    var rules by remember { mutableStateOf<List<ProxyRuleUi>>(emptyList()) }
    var ruleSets by remember { mutableStateOf<List<DashboardRuleSetUi>>(emptyList()) }
    var logs by remember { mutableStateOf<List<RefLogEntry>>(emptyList()) }
    var refreshing by remember { mutableStateOf(false) }
    val testing = remember { mutableStateMapOf<String, Boolean>() }
    val selectedLocal = remember { mutableStateMapOf<String, String>() }
    val subscriptionUpdates = remember { mutableStateMapOf<String, PanelUpdate>() }
    val ruleSetUpdates = remember { mutableStateMapOf<String, PanelUpdate>() }
    val sampler = remember { PanelTrafficSampler() }
    val projector = remember { PanelDataProjector() }
    val initialView = remember { loadView(prefs, selectorPrefs) }
    var latestView by remember { mutableStateOf(initialView) }
    val tab = PanelTab.entries.firstOrNull { it.name == selectedTab.name } ?: PanelTab.Groups

    fun say(text: String) = Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
    fun launchSafely(failure: String, block: suspend CoroutineScope.() -> Unit) {
        scope.launch {
            try { block() } catch (cancel: CancellationException) { throw cancel } catch (error: Exception) { say(error.message ?: failure) }
        }
    }

    // Load what the visible tab needs; the overview also needs providers and the rule count.
    LaunchedEffect(tab, state.running) {
        if (!state.running) { providers = emptyList(); rules = emptyList(); ruleSets = emptyList(); sampler.reset(); return@LaunchedEffect }
        try {
            when (tab) {
                PanelTab.Overview -> { providers = repo.providers(); if (rules.isEmpty()) rules = repo.rules() }
                PanelTab.Subscriptions -> providers = repo.providers()
                PanelTab.Rules -> rules = repo.rules()
                PanelTab.RuleSets -> ruleSets = repo.ruleSets()
                PanelTab.Logs -> logs = refParseLogs19(inspector.runtimeLog())
                PanelTab.Groups, PanelTab.Connections -> Unit
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            say(error.message ?: "数据读取失败")
        }
    }
    LaunchedEffect(state.uploadTotal, state.downloadTotal, state.connections) {
        if (state.running && state.panelReady && !state.controllerReadFailed)
            sampler.sample(SystemClock.elapsedRealtime(), state.uploadTotal, state.downloadTotal, state.connections)
    }
    // An optimistic selection is dropped as soon as the core reports the same node.
    LaunchedEffect(state.groups) { state.groups.forEach { g -> if (selectedLocal[g.name] == g.now) selectedLocal.remove(g.name) } }

    val icons = remember(state.connections) { state.connections.mapNotNull { c -> c.appIcon?.let { c.packageName to it } }.toMap() }
    val data = projector.project(
        state = state, starting = starting, providers = providers, rules = rules, ruleSets = ruleSets, logs = logs,
        delays = delays, testing = testing, selectedLocal = selectedLocal,
        subscriptionUpdates = subscriptionUpdates, ruleSetUpdates = ruleSetUpdates, traffic = sampler.snapshot(),
    ).copy(refreshing = refreshing)
    val groupsByName = remember(state.groups) { state.groups.associateBy { it.name } }

    val actions = PanelActions(
        onStart = onStart,
        onSelectNode = { group, node ->
            val before = selectedLocal[group]
            selectedLocal[group] = node
            scope.launch {
                try {
                    repo.select(group, node, latestView.display.disconnectOnSelect)
                    onRefreshState()
                    say("$group 已切换到 $node" + if (latestView.display.disconnectOnSelect) "，旧连接已断开" else "")
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (error: Exception) {
                    if (before == null) selectedLocal.remove(group) else selectedLocal[group] = before
                    say(error.message ?: "切换失败")
                }
            }
        },
        onTestNode = { node -> scope.launch { testNode(repo, node, delays, testing) } },
        onTestGroup = { group ->
            val names = state.groups.firstOrNull { it.name == group }?.nodes.orEmpty().map { it.name }
                .filter { name -> state.groups.none { it.name == name } && !name.equals("DIRECT", true) && !name.equals("REJECT", true) }
            scope.launch {
                for (chunk in names.chunked(8)) coroutineScope { chunk.map { async { testNode(repo, it, delays, testing) } }.awaitAll() }
            }
        },
        onUpdateSubscription = { name ->
            subscriptionUpdates[name] = PanelUpdate.Updating
            scope.launch {
                try {
                    val fresh = repo.refreshProvider(name)
                    if (fresh != null) providers = providers.map { if (it.name == name) fresh else it }
                    subscriptionUpdates.remove(name)
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (error: Exception) {
                    subscriptionUpdates[name] = PanelUpdate.Failed(error.message ?: "更新失败")
                }
            }
        },
        onUpdateAllSubscriptions = {
            providers.forEach { subscriptionUpdates[it.name] = PanelUpdate.Updating }
            scope.launch {
                try { providers = repo.refreshSubscriptions(); say("订阅已更新") }
                catch (cancel: CancellationException) { throw cancel }
                catch (error: Exception) { say(error.message ?: "订阅更新失败") }
                finally { subscriptionUpdates.clear() }
            }
        },
        onCloseConnection = { id -> launchSafely("连接关闭失败") { repo.closeConnection(id); onRefreshState(); say("连接已断开") } },
        onCloseAllConnections = { launchSafely("连接关闭失败") { repo.closeAll(); onRefreshState(); say("已断开全部连接") } },
        onRefreshRules = { launchSafely("规则读取失败") { rules = repo.rules(); say("规则已刷新") } },
        onUpdateRuleSet = { name ->
            ruleSetUpdates[name] = PanelUpdate.Updating
            scope.launch {
                try {
                    val fresh = repo.refreshRuleSet(name)
                    if (fresh != null) ruleSets = ruleSets.map { if (it.name == name) fresh else it }
                    ruleSetUpdates.remove(name)
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (error: Exception) {
                    ruleSetUpdates[name] = PanelUpdate.Failed(error.message ?: "更新失败")
                }
            }
        },
        onUpdateAllRuleSets = {
            ruleSets.forEach { ruleSetUpdates[it.name] = PanelUpdate.Updating }
            scope.launch {
                try { ruleSets = repo.refreshRuleSets(); say("规则集已更新") }
                catch (cancel: CancellationException) { throw cancel }
                catch (error: Exception) { say(error.message ?: "规则集更新失败") }
                finally { ruleSetUpdates.clear() }
            }
        },
        onRefreshLogs = { launchSafely("日志读取失败") { logs = refParseLogs19(inspector.runtimeLog()); say("日志已刷新") } },
        onSaveApi = { settings -> saveApi(prefs, settings); say("测速与 API 设置已保存") },
        onOpenPolicyIcons = { context.startActivity(Intent(context, ProxyPolicyIconsActivity::class.java)) },
        onCopy = { label, text -> copyToClipboard(context, label, text) },
        onRefresh = {
            if (!refreshing) {
                refreshing = true
                scope.launch {
                    try {
                        onRefreshState()
                        if (state.running) when (tab) {
                            PanelTab.Overview -> { providers = repo.providers(); rules = repo.rules() }
                            PanelTab.Subscriptions -> providers = repo.providers()
                            PanelTab.Rules -> rules = repo.rules()
                            PanelTab.RuleSets -> ruleSets = repo.ruleSets()
                            PanelTab.Logs -> logs = refParseLogs19(inspector.runtimeLog())
                            PanelTab.Groups, PanelTab.Connections -> Unit
                        }
                    } catch (cancel: CancellationException) {
                        throw cancel
                    } catch (error: Exception) {
                        say(error.message ?: "刷新失败")
                    } finally {
                        refreshing = false
                    }
                }
            }
        },
    )

    HetuHomeThemeFromPrefs(prefs) {
        CompositionLocalProvider(
            LocalHomeHaptics provides { kind ->
                hetuHaptics.perform(
                    when (kind) {
                        HomeHaptic.Tap -> HetuHaptic.Tap
                        HomeHaptic.Tick -> HetuHaptic.Tick
                        HomeHaptic.Confirm -> HetuHaptic.Confirm
                        HomeHaptic.Reject -> HetuHaptic.Reject
                    },
                )
            },
            LocalPanelAppIcons provides PanelAppIcons(
                has = { it in icons },
                draw = { packageName, m -> icons[packageName]?.let { AppIcon(it, m) } },
            ),
            LocalPanelGroupIcon provides { group, m -> groupsByName[group.name]?.let { ConfiguredGroupIcon(it, m) } },
        ) {
            PanelRoute(
                data = data,
                tab = tab,
                onTabChange = { next -> RefPanelTab.values().firstOrNull { it.name == next.name }?.let(onSelectedTabChange) },
                actions = actions,
                modifier = modifier,
                initialView = initialView,
                onViewChange = { next ->
                    if (next.display != latestView.display || next.layout != latestView.layout) saveView(prefs, selectorPrefs, next)
                    latestView = next
                },
                expandGroup = expandGroup,
                contentPadding = PaddingValues(bottom = bottomPadding),
            )
        }
    }
}

@Composable
private fun AppIcon(bitmap: Bitmap, modifier: Modifier) {
    Image(bitmap = bitmap.asImageBitmap(), contentDescription = null, modifier = modifier)
}

private suspend fun testNode(repo: ProxyDashboardRepository, node: String, delays: MutableMap<String, Long>, testing: MutableMap<String, Boolean>) {
    testing[node] = true
    try {
        delays[node] = repo.delay(node)
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (_: Exception) {
        delays[node] = -1L
    } finally {
        testing.remove(node)
    }
}

/* ------------------------------------------------------------------ */
/*  Traffic sampling                                                    */
/* ------------------------------------------------------------------ */

/**
 * Turns the cumulative counters of successive state snapshots into rates: overall upload / download,
 * a rolling trend (60 points) and per-connection rates.
 */
private class PanelTrafficSampler {
    var upload by mutableStateOf(0L)
        private set
    var download by mutableStateOf(0L)
        private set
    var connectionRates by mutableStateOf<Map<String, Pair<Long, Long>>>(emptyMap())
        private set
    val uploadTrend = mutableStateListOf<Float>()
    val downloadTrend = mutableStateListOf<Float>()
    private var lastAt = 0L
    private var lastUpload = 0L
    private var lastDownload = 0L
    private var lastConnections: Map<String, Pair<Long, Long>> = emptyMap()

    fun sample(now: Long, uploadTotal: Long, downloadTotal: Long, connections: List<ProxyConnectionUi>) {
        val elapsed = now - lastAt
        if (lastAt > 0L && elapsed < 200L) return
        if (lastAt > 0L && elapsed <= 15_000L) {
            if (uploadTotal >= lastUpload && downloadTotal >= lastDownload) {
                upload = (uploadTotal - lastUpload) * 1000L / elapsed
                download = (downloadTotal - lastDownload) * 1000L / elapsed
                uploadTrend.add(upload.toFloat()); downloadTrend.add(download.toFloat())
                while (uploadTrend.size > 60) { uploadTrend.removeAt(0); downloadTrend.removeAt(0) }
            }
            connectionRates = connections.associate { c ->
                val before = lastConnections[c.id]
                c.id to if (before == null) 0L to 0L
                else ((c.upload - before.first).coerceAtLeast(0L) * 1000L / elapsed) to ((c.download - before.second).coerceAtLeast(0L) * 1000L / elapsed)
            }
        }
        lastAt = now; lastUpload = uploadTotal; lastDownload = downloadTotal
        lastConnections = connections.associate { it.id to (it.upload to it.download) }
    }

    fun snapshot() = PanelTrafficSnapshot(upload, download, connectionRates, uploadTrend.toList(), downloadTrend.toList())

    fun reset() {
        upload = 0L; download = 0L; connectionRates = emptyMap(); uploadTrend.clear(); downloadTrend.clear()
        lastAt = 0L; lastConnections = emptyMap()
    }
}

/* ------------------------------------------------------------------ */
/*  Preferences                                                         */
/* ------------------------------------------------------------------ */

private fun loadView(prefs: SharedPreferences, selector: SharedPreferences): PanelViewState {
    fun columns(modeKey: String, countKey: String): Int =
        if (selector.getString(modeKey, "auto") == "auto") 2 else selector.getInt(countKey, 2).coerceIn(1, 2)
    return PanelViewState(
        display = PanelGroupDisplay(
            showHidden = prefs.getBoolean("show_hidden_groups", false),
            globalByMode = prefs.getBoolean("display_global_by_mode", true),
            groupByProvider = prefs.getBoolean("group_by_provider", false),
            collapsePrevious = prefs.getBoolean("collapse_previous_group_on_expand", true),
            disconnectOnSelect = prefs.getBoolean("disconnect_on_select", false),
        ),
        layout = PanelGroupLayout(
            sort = when (selector.getString("node_sort_mode", "defaultsort")) {
                "name" -> PanelNodeSort.Name
                "latency" -> PanelNodeSort.Delay
                else -> PanelNodeSort.Config
            },
            descending = selector.getBoolean("node_sort_descending", false),
            groupColumns = columns("group_column_mode", "group_column_count"),
            compactGroups = selector.getString("group_density", "compact") == "compact",
            nodeColumns = columns("node_column_mode", "node_column_count"),
            compactNodes = selector.getString("node_density", "standard") == "compact",
            wrapNames = selector.getString("name_overflow_mode", "clip") == "wrap",
        ),
        api = PanelApiSettings(
            customTestUrl = prefs.getBoolean("proxyCustomDelayUrlEnabled", false),
            testUrl = prefs.getString("proxyCustomDelayUrl", "").orEmpty().ifBlank { PanelApiSettings().testUrl },
            history = prefs.getBoolean("proxyApiHistoryEnabled", false),
            externalApi = prefs.getBoolean("panelExternalApiEnabled", false),
            host = prefs.getString("panelExternalApiHost", "127.0.0.1").orEmpty().ifBlank { "127.0.0.1" },
            port = prefs.getString("panelExternalApiPort", "9090").orEmpty().ifBlank { "9090" },
            secret = prefs.getString("panelExternalApiSecret", "").orEmpty(),
        ),
    )
}

private fun saveView(prefs: SharedPreferences, selector: SharedPreferences, view: PanelViewState) {
    val d = view.display
    prefs.edit()
        .putBoolean("show_hidden_groups", d.showHidden)
        .putBoolean("display_global_by_mode", d.globalByMode)
        .putBoolean("group_by_provider", d.groupByProvider)
        .putBoolean("collapse_previous_group_on_expand", d.collapsePrevious)
        .putBoolean("disconnect_on_select", d.disconnectOnSelect)
        .apply()
    val l = view.layout
    selector.edit()
        .putString("node_sort_mode", when (l.sort) { PanelNodeSort.Config -> "defaultsort"; PanelNodeSort.Name -> "name"; PanelNodeSort.Delay -> "latency" })
        .putBoolean("node_sort_descending", l.descending)
        .putString("group_column_mode", "fixed").putInt("group_column_count", l.groupColumns)
        .putString("node_column_mode", "fixed").putInt("node_column_count", l.nodeColumns)
        .putString("group_density", if (l.compactGroups) "compact" else "standard")
        .putString("node_density", if (l.compactNodes) "compact" else "standard")
        .putString("name_overflow_mode", if (l.wrapNames) "wrap" else "clip")
        .apply()
}

private fun saveApi(prefs: SharedPreferences, api: PanelApiSettings) {
    prefs.edit()
        .putBoolean("proxyCustomDelayUrlEnabled", api.customTestUrl)
        .putString("proxyCustomDelayUrl", api.testUrl)
        .putBoolean("proxyApiHistoryEnabled", api.history)
        .putBoolean("panelExternalApiEnabled", api.externalApi)
        .putString("panelExternalApiHost", api.host)
        .putString("panelExternalApiPort", api.port)
        .putString("panelExternalApiSecret", api.secret)
        .apply()
}

private fun copyToClipboard(context: Context, label: String, text: String) {
    val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    manager.setPrimaryClip(ClipData.newPlainText(label, text))
    // Android 13+ shows its own clipboard confirmation.
    if (Build.VERSION.SDK_INT < 33) Toast.makeText(context, "已复制$label", Toast.LENGTH_SHORT).show()
}


/** Launcher adapter: all mutations go through the V20.81 ViewModel safety gates. */
@Composable
internal fun NewUiPanel(vm: io.github.xgl34222220.hetu.HetuViewModel, bottom: Dp) {
    val context = LocalContext.current
    val haptics = rememberHetuHaptics()
    val selector = remember { context.getSharedPreferences("proxy_selector_preferences", Context.MODE_PRIVATE) }
    val initial = remember {
        val old = io.github.xgl34222220.hetu.PanelOptions11.read(vm.prefs)
        val api = io.github.xgl34222220.hetu.PanelApiDraft11.read(vm.prefs)
        loadView(vm.prefs, selector).copy(
            display = PanelGroupDisplay(old.showHidden, old.globalByMode, old.providers, old.collapsePrevious,
                vm.prefs.getBoolean("proxySelectorDisconnectOnSelect", false)),
            layout = PanelGroupLayout(
                when (old.sort) { "name" -> PanelNodeSort.Name; "delay", "latency" -> PanelNodeSort.Delay; else -> PanelNodeSort.Config },
                old.descending, old.groupColumns, old.groupCompact, old.columns,
                if (vm.prefs.contains("proxySelectorDensity")) old.compact else false, old.nameOverflow == "wrap"),
            api = PanelApiSettings(api.customDelay, api.delayUrl, api.history, api.customApi, api.host, api.port, api.secret),
            rankMode = if (vm.prefs.getString("panelOverviewRankSort", "connections") == "traffic") PanelRankMode.Total else PanelRankMode.Connections,
            rankCount = vm.prefs.getInt("panelOverviewRankCount", 5),
        )
    }
    var view by remember { mutableStateOf(initial) }
    val sampler = remember { PanelTrafficSampler() }
    val projector = remember { PanelDataProjector() }
    val tab = when (vm.panelSection) {
        "overview" -> PanelTab.Overview
        "providers" -> PanelTab.Subscriptions
        "conn" -> PanelTab.Connections
        "rules" -> PanelTab.Rules
        "sets" -> PanelTab.RuleSets
        "logs" -> PanelTab.Logs
        else -> PanelTab.Groups
    }
    LaunchedEffect(tab, vm.state.running, vm.state.controllerReadFailed, vm.contentRevision) {
        if (!vm.state.running) { sampler.reset(); return@LaunchedEffect }
        if (vm.state.controllerReadFailed) return@LaunchedEffect
        when (tab) {
            PanelTab.Overview -> { vm.loadProviders(); if (vm.rules.isEmpty()) vm.loadRules() }
            PanelTab.Subscriptions -> vm.loadProviders()
            PanelTab.Rules -> if (vm.rules.isEmpty()) vm.loadRules()
            PanelTab.RuleSets -> if (vm.ruleSets.isEmpty()) vm.loadRuleSets()
            PanelTab.Logs -> {
                vm.loadLogs()
                while (true) { delay(3_000); vm.loadLogs(quiet = true) }
            }
            else -> Unit
        }
    }
    LaunchedEffect(vm.state.running) {
        if (!vm.state.running) { sampler.reset(); return@LaunchedEffect }
        while (true) {
            if (vm.state.panelReady && !vm.state.controllerReadFailed) {
                sampler.sample(SystemClock.elapsedRealtime(), vm.state.uploadTotal, vm.state.downloadTotal, vm.state.connections)
            }
            delay(1_000)
        }
    }
    fun updates(tasks: Map<String, io.github.xgl34222220.hetu.HxTask>) = tasks.mapValues { (_, t) ->
        if (t.running) PanelUpdate.Updating else if (t.ok == false) PanelUpdate.Failed(t.message) else PanelUpdate.Idle
    }
    val data = projector.project(vm.state, vm.operation == io.github.xgl34222220.hetu.HxRunOp.Start,
        vm.providers, vm.rules, vm.ruleSets, vm.logEntries, vm.delays, vm.testingNodes,
        emptyMap(), updates(vm.providerTasks), updates(vm.ruleSetTasks), sampler.snapshot(),
        testingGroups = vm.testingGroups.filterValues { it }.keys, testingAll = vm.testingAll,
        switching = vm.pendingSelection.toMap()).copy(
        refreshing = if (vm.state.controllerReadFailed) vm.refreshing else when (tab) {
            PanelTab.Rules -> vm.rulesLoading
            PanelTab.RuleSets -> vm.ruleSetsLoading
            PanelTab.Logs -> vm.logsLoading
            else -> vm.refreshing
        },
        updatingAllSubscriptions = vm.providersUpdatingAll,
        updatingAllRuleSets = vm.ruleSetsUpdatingAll,
        syncing = vm.state.running && !vm.state.panelReady && !vm.state.controllerReadFailed && vm.state.groups.isNotEmpty(),
    )
    val groupsByName = remember(vm.state.groups) { vm.state.groups.associateBy { it.name } }
    val actions = PanelActions(
        onStart = vm::toggle, onSelectNode = vm::select, onTestNode = vm::testNode,
        onTestGroup = { name -> vm.state.groups.firstOrNull { it.name == name }?.let(vm::testGroup) },
        onTestAll = vm::testAll,
        onUpdateSubscription = vm::updateProvider, onUpdateAllSubscriptions = vm::updateAllProviders,
        onCloseConnection = vm::closeConnection, onCloseAllConnections = vm::closeAll,
        onRefreshRules = vm::loadRules, onUpdateRuleSet = vm::updateRuleSet,
        onUpdateAllRuleSets = vm::updateAllRuleSets, onRefreshLogs = { vm.loadLogs() },
        onSaveApi = {
            io.github.xgl34222220.hetu.PanelApiDraft11(it.customTestUrl, it.testUrl, it.history,
                it.externalApi, it.host, it.port, it.secret).save(vm.prefs)
            vm.bumpSettings()
            vm.pullRefresh()
        },
        onOpenPolicyIcons = { context.startActivity(Intent(context, ProxyPolicyIconsActivity::class.java)) },
        onCopy = { label, text -> copyToClipboard(context, label, text) },
        onRefresh = {
            if (vm.state.controllerReadFailed) vm.pullRefresh() else when (tab) {
                PanelTab.Rules -> vm.loadRules()
                PanelTab.RuleSets -> vm.loadRuleSets()
                PanelTab.Logs -> vm.loadLogs()
                else -> vm.pullRefresh()
            }
        },
    )
    val icons = remember(vm.state.connections) {
        vm.state.connections.mapNotNull { it.appIcon?.let { icon -> it.packageName to icon } }.toMap()
    }
    HetuHomeThemeFromPrefs(vm.prefs) {
        CompositionLocalProvider(
            LocalPanelAppIcons provides PanelAppIcons(
                has = { it in icons }, draw = { name, m -> icons[name]?.let { AppIcon(it, m) } },
            ),
            LocalPanelGroupIcon provides { group, m -> groupsByName[group.name]?.let { ConfiguredGroupIcon(it, m) } },
            LocalHomeHaptics provides { kind ->
                haptics.perform(when (kind) {
                    HomeHaptic.Tap -> HetuHaptic.Tap
                    HomeHaptic.Tick -> HetuHaptic.Tick
                    HomeHaptic.Confirm -> HetuHaptic.Confirm
                    HomeHaptic.Reject -> HetuHaptic.Reject
                })
            },
        ) {
            PanelRoute(data, tab, { next -> vm.openPanel(when (next) {
                PanelTab.Overview -> "overview"; PanelTab.Groups -> "proxies"; PanelTab.Subscriptions -> "providers"
                PanelTab.Connections -> "conn"; PanelTab.Rules -> "rules"; PanelTab.RuleSets -> "sets"; PanelTab.Logs -> "logs"
            }) }, actions, initialView = initial, onViewChange = { next ->
                if (next.display != view.display || next.layout != view.layout) saveView(vm.prefs, selector, next)
                val d = next.display; val l = next.layout
                io.github.xgl34222220.hetu.PanelOptions11(d.showHidden, d.globalByMode, d.groupByProvider, d.collapsePrevious,
                    when (l.sort) { PanelNodeSort.Config -> "config"; PanelNodeSort.Name -> "name"; PanelNodeSort.Delay -> "delay" },
                    l.descending, l.nodeColumns, l.compactNodes, l.groupColumns, l.compactGroups, if (l.wrapNames) "wrap" else "clip").save(vm.prefs)
                vm.prefs.edit().putBoolean("proxySelectorDisconnectOnSelect", d.disconnectOnSelect)
                    .putString("panelOverviewRankSort", if (next.rankMode == PanelRankMode.Total) "traffic" else "connections")
                    .putInt("panelOverviewRankCount", next.rankCount).apply()
                view = next
            }, contentPadding = PaddingValues(bottom = bottom))
        }
    }
}
