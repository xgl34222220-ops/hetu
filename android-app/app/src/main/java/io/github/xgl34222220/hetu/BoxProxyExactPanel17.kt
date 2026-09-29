package io.github.xgl34222220.hetu

import android.content.SharedPreferences
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Icon
import androidx.compose.material3.TextButton
import kotlinx.coroutines.*
import top.yukonga.miuix.kmp.basic.Card as MiuixCard
import top.yukonga.miuix.kmp.basic.CardDefaults as MiuixCardDefaults
import top.yukonga.miuix.kmp.basic.FloatingActionButton as MiuixFab
import top.yukonga.miuix.kmp.basic.IconButton as MiuixIconButton
import top.yukonga.miuix.kmp.basic.InputField as MiuixInputField
import top.yukonga.miuix.kmp.basic.SearchBar as MiuixSearchBar
import top.yukonga.miuix.kmp.basic.TabRow as MiuixTabRow
import top.yukonga.miuix.kmp.basic.TopAppBar as MiuixTopAppBar
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.theme.MiuixTheme as MiuixThemeHost
import top.yukonga.miuix.kmp.theme.darkColorScheme as miuixDarkColors
import top.yukonga.miuix.kmp.theme.lightColorScheme as miuixLightColors
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Search
import top.yukonga.miuix.kmp.icon.extended.Settings

/**
 * Reconstructed only from the user's BoxProxy APK resources/bytecode.
 * Runtime actions stay on Hetu's real repository callbacks.
 */
private data class BoxProxyPrefs17(
    val showHidden: Boolean,
    val globalByMode: Boolean,
    val groupByProvider: Boolean,
    val disconnectOnSelect: Boolean,
    val collapsePrevious: Boolean,
    val expandInSheet: Boolean,
    val groupColumnMode: String,
    val groupColumns: Int,
    val nodeColumnMode: String,
    val nodeColumns: Int,
    val groupDensity: String,
    val nodeDensity: String,
    val nodeSort: String,
    val descending: Boolean,
    val nameOverflow: String,
) {
    companion object {
        fun read(p: SharedPreferences) = BoxProxyPrefs17(
            showHidden = p.getBoolean("show_hidden_groups", true),
            globalByMode = p.getBoolean("display_global_by_mode", false),
            groupByProvider = p.getBoolean("group_by_provider", false),
            disconnectOnSelect = p.getBoolean("disconnect_on_select", false),
            collapsePrevious = p.getBoolean("collapse_previous_group_on_expand", false),
            // V18 removes inline accordion expansion entirely. Strategy cards always open
            // the secondary node surface so a large provider can never push the parent grid.
            expandInSheet = true,
            groupColumnMode = p.getString("group_column_mode", "auto").orEmpty().ifBlank { "auto" },
            groupColumns = p.getInt("group_column_count", 1).coerceIn(1, 12),
            nodeColumnMode = p.getString("node_column_mode", "auto").orEmpty().ifBlank { "auto" },
            nodeColumns = p.getInt("node_column_count", 1).coerceIn(1, 12),
            groupDensity = p.getString("group_density", "standard").orEmpty().ifBlank { "standard" },
            nodeDensity = p.getString("node_density", "standard").orEmpty().ifBlank { "standard" },
            nodeSort = p.getString("node_sort_mode", "defaultsort").orEmpty().ifBlank { "defaultsort" },
            descending = p.getBoolean("node_sort_descending", false),
            nameOverflow = p.getString("name_overflow_mode", "clip").orEmpty().ifBlank { "clip" },
        )
    }

    fun legacy() = PanelOptions11(
        showHidden = showHidden,
        globalByMode = globalByMode,
        providers = groupByProvider,
        collapsePrevious = collapsePrevious,
        sort = when (nodeSort) { "name" -> "name"; "latency" -> "latency"; else -> "config" },
        descending = descending,
        columns = nodeColumns.coerceIn(1, 2),
        compact = nodeDensity == "compact",
    )
}

private fun boxProxyAutoColumns18(screenWidthDp: Float, horizontalPaddingDp: Float, mode: String, fixed: Int): Int =
    if (mode == "fixed") fixed.coerceIn(1, 12)
    else (((screenWidthDp - horizontalPaddingDp) / 180f).toInt()).coerceIn(2, 12)

@Composable
private fun rememberBoxProxyPrefs17(prefs: SharedPreferences): BoxProxyPrefs17 {
    var value by remember(prefs) { mutableStateOf(BoxProxyPrefs17.read(prefs)) }
    DisposableEffect(prefs) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { p, _ ->
            value = BoxProxyPrefs17.read(p)
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return value
}

@Composable
internal fun BoxProxyMiuixTheme17(content: @Composable () -> Unit) {
    val dark = MaterialTheme.colorScheme.background.luminance() < .5f
    MiuixThemeHost(colors = if (dark) miuixDarkColors() else miuixLightColors(), content = content)
}

internal val BoxProxyTabs17 = listOf(
    RefPanelTab.Overview,
    RefPanelTab.Groups,
    RefPanelTab.Subscriptions,
    RefPanelTab.Connections,
    RefPanelTab.Rules,
    RefPanelTab.RuleSets,
    RefPanelTab.Logs,
)

internal fun boxProxyTabLabel17(tab: RefPanelTab): String = when (tab) {
    RefPanelTab.Overview -> "概览"
    RefPanelTab.Groups -> "策略"
    RefPanelTab.Connections -> "连接"
    RefPanelTab.Subscriptions -> "订阅"
    RefPanelTab.Rules -> "规则"
    RefPanelTab.RuleSets -> "规则集"
    RefPanelTab.Logs -> "日志"
}

@Composable
internal fun BoxProxyPanelTabs17(selected: RefPanelTab, onSelect: (RefPanelTab) -> Unit) {
    // V19: content-sized scrolling tabs with a liquid selection capsule (haptics inside).
    io.github.xgl34222220.hetu.ui.HetuScrollTabs(
        labels = BoxProxyTabs17.map(::boxProxyTabLabel17),
        selected = BoxProxyTabs17.indexOf(selected).coerceAtLeast(0),
        onSelect = { index -> BoxProxyTabs17.getOrNull(index)?.let(onSelect) },
        modifier = Modifier.padding(bottom = 4.dp),
    )
}

@Composable
internal fun BoxProxyPanelHeader17(
    selected: RefPanelTab,
    onSelect: (RefPanelTab) -> Unit,
    searchOpen: Boolean,
    query: String,
    onQueryChange: (String) -> Unit,
    onSearchToggle: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    BoxProxyMiuixTheme17 {
        Column(
            Modifier.fillMaxWidth().background(
                if (MaterialTheme.colorScheme.background.luminance() < .5f)
                    MiuixTheme.colorScheme.surface else Color(0xFFECEBFA)
            )
        ) {
            Row(
                Modifier.fillMaxWidth().height(40.dp).padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (selected != RefPanelTab.Overview) {
                    MiuixIconButton(onClick = onSearchToggle, minWidth = 38.dp, minHeight = 38.dp) {
                        Icon(
                            MiuixIcons.Search,
                            if (searchOpen) "关闭搜索" else "搜索",
                            Modifier.size(19.dp),
                            tint = MiuixTheme.colorScheme.onSurface,
                        )
                    }
                } else {
                    Spacer(Modifier.width(38.dp))
                }
                if (selected == RefPanelTab.Groups) {
                    ReferenceStrategyFilterMenu()
                }
                Spacer(Modifier.weight(1f))
                if (selected == RefPanelTab.Groups) {
                    ReferenceStrategyMenu()
                }
                MiuixIconButton(onClick = onOpenSettings, minWidth = 38.dp, minHeight = 38.dp) {
                    Icon(
                        MiuixIcons.Settings,
                        "面板设置",
                        Modifier.size(19.dp),
                        tint = MiuixTheme.colorScheme.onSurface,
                    )
                }
            }

            Text(
                "面板",
                color = MiuixTheme.colorScheme.onSurface,
                fontSize = 32.sp,
                lineHeight = 38.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 14.dp, top = 1.dp, bottom = 5.dp),
            )

            BoxProxyPanelTabs17(selected, onSelect)

            AnimatedVisibility(searchOpen && selected != RefPanelTab.Overview) {
                MiuixSearchBar(
                    inputField = {
                        MiuixInputField(
                            query = query,
                            onQueryChange = onQueryChange,
                            onSearch = {},
                            expanded = true,
                            onExpandedChange = {},
                            label = when (selected) {
                                RefPanelTab.Groups -> "搜索策略组或节点"
                                RefPanelTab.Connections -> "搜索应用、域名或分流规则"
                                RefPanelTab.Subscriptions -> "搜索订阅"
                                RefPanelTab.Rules -> "搜索规则"
                                RefPanelTab.RuleSets -> "搜索规则集"
                                RefPanelTab.Logs -> "搜索日志内容"
                                else -> "搜索"
                            },
                        )
                    },
                    onExpandedChange = { if (!it) onSearchToggle() },
                    modifier = Modifier.fillMaxWidth(),
                    insideMargin = DpSize(12.dp, 6.dp),
                    expanded = true,
                    outsideEndAction = {
                        TextButton(onClick = onSearchToggle) { Text("取消") }
                    },
                ) {}
            }
        }
    }
}

@Composable
internal fun BoxProxyExactStrategy17(
    state: ProxyComposeState,
    delays: MutableMap<String, Long>,
    legacyPrefs: SharedPreferences,
    searchRequest: Int,
    onTab: (RefPanelTab) -> Unit,
    refresh: suspend () -> Unit,
    select: suspend (String, String) -> String,
    measure: suspend (String) -> Long,
    onDetailVisible: (Boolean) -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember(context) { context.getSharedPreferences("proxy_selector_preferences", 0) }
    val options = rememberBoxProxyPrefs17(prefs)
    val legacy = options.legacy()
    val selection = remember { PanelSelection11() }
    val scope = rememberCoroutineScope()
    val accessibility = LocalAccessibilityManager.current
    val listState = rememberLazyListState()
    val testing = remember { mutableStateMapOf<String, Boolean>() }
    val batchTesting = remember { mutableStateMapOf<String, Boolean>() }
    val batchDone = remember { mutableStateMapOf<String, Int>() }
    var query by rememberSaveable { mutableStateOf("") }
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var expanded by rememberSaveable { mutableStateOf(setOf<String>()) }
    var sheetGroup by rememberSaveable { mutableStateOf<String?>(null) }
    var detail by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf("") }
    var noticeError by remember { mutableStateOf(false) }
    var refreshing by remember { mutableStateOf(false) }

    val currentState by rememberUpdatedState(state)
    val selectLatest by rememberUpdatedState(select)
    val measureLatest by rememberUpdatedState(measure)
    val refreshLatest by rememberUpdatedState(refresh)
    val fontScale = LocalDensity.current.fontScale
    val groupColumns = if (fontScale >= 1.45f) 1 else 2
    val nodeColumns = if (fontScale >= 1.45f) 1 else 2
    val allGroups = remember(state.groups) { state.groups.associateBy { it.name } }
    val groups = remember(state.groups, query, state.trafficMode, options) {
        panelGroups11(state.groups, query, state.trafficMode, legacy).map { allGroups.getValue(it.name) }
    }

    LaunchedEffect(state.groups) {
        selection.reconcile(state.groups)
        expanded = expanded.filterTo(mutableSetOf()) { it in allGroups }
        if (sheetGroup !in allGroups) sheetGroup = null
    }
    LaunchedEffect(searchRequest) { if (searchRequest > 0) searchOpen = true }
    LaunchedEffect(sheetGroup, detail) { onDetailVisible(sheetGroup != null || detail != null) }
    DisposableEffect(Unit) { onDispose { onDetailVisible(false) } }
    LaunchedEffect(notice) {
        if (notice.isNotBlank()) {
            val old = notice
            delay(accessibility?.calculateRecommendedTimeoutMillis(4000, true, true, true) ?: 4000)
            if (notice == old) notice = ""
        }
    }

    fun notify(text: String, failed: Boolean = false) { notice = text; noticeError = failed }
    fun requestRefresh() {
        if (refreshing) return
        refreshing = true
        scope.launch {
            try { refreshLatest(); notify("刷新完成") }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { notify(e.message ?: "刷新失败", true) }
            finally { refreshing = false }
        }
    }
    suspend fun testNode(name: String) {
        if (testing[name] == true || !currentState.running) return
        testing[name] = true
        try {
            val value = measureLatest(name)
            if (value > 0) delays[name] = value
        } catch (e: MihomoControllerClient.DelayFailure) {
            delays[name] = if (e.timedOut) -1L else -2L
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            notify(e.message ?: "测速失败", true)
        } finally {
            testing.remove(name)
        }
    }
    fun testAll(group: ProxyGroupUi) {
        if (batchTesting[group.name] == true || !state.running) return
        batchTesting[group.name] = true
        batchDone[group.name] = 0
        scope.launch {
            try {
                group.nodes.map { it.name }.distinct().chunked(4).forEach { chunk ->
                    coroutineScope {
                        chunk.map { name -> async {
                            try { testNode(name) }
                            finally { batchDone[group.name] = (batchDone[group.name] ?: 0) + 1 }
                        } }.awaitAll()
                    }
                }
            } finally {
                batchTesting.remove(group.name)
            }
        }
    }
    fun choose(group: ProxyGroupUi, node: String) {
        if (!state.running || selection.pending[group.name] != null) return
        legacyPrefs.edit().putBoolean("proxySelectorDisconnectOnSelect", options.disconnectOnSelect).apply()
        scope.launch {
            try {
                if (selection.select(group, node, selectLatest)) notify("已选择 $node")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                notify(e.message ?: "操作失败", true)
            }
        }
    }

    BoxProxyMiuixTheme17 {
        top.yukonga.miuix.kmp.basic.Scaffold(
            modifier = Modifier.fillMaxSize().testTag("boxproxy17-strategy"),
            topBar = {
                BoxProxyPanelHeader17(
                    selected = RefPanelTab.Groups,
                    onSelect = onTab,
                    searchOpen = searchOpen,
                    query = query,
                    onQueryChange = { query = it },
                    onSearchToggle = { searchOpen = !searchOpen; if (!searchOpen) query = "" },
                    onOpenSettings = {},
                )
            },
            floatingActionButton = {
                if (state.running && groups.isNotEmpty() && !options.expandInSheet) {
                    MiuixFab(
                        onClick = {
                            val target = groups.firstOrNull { it.name == "节点选择" }
                                ?: groups.firstOrNull { it.type.equals("Selector", true) && !it.name.equals("GLOBAL", true) }
                                ?: groups.first()
                            expanded = if (options.collapsePrevious) setOf(target.name) else expanded + target.name
                            scope.launch {
                                val index = groups.indexOfFirst { it.name == target.name }
                                if (index >= 0) listState.animateScrollToItem(index)
                            }
                        },
                        minWidth = 60.dp,
                        minHeight = 48.dp,
                    ) {
                        Text("当前策略组", color = MiuixTheme.colorScheme.onPrimary, fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 14.dp))
                    }
                }
            },
            containerColor = MiuixTheme.colorScheme.surface,
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                if (notice.isNotBlank()) {
                    Text(
                        notice,
                        color = if (noticeError) MiuixTheme.colorScheme.error else MiuixTheme.colorScheme.onSurface,
                        fontSize = 12.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)
                            .testTag("boxproxy17-notice"),
                    )
                }
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = 14.dp, end = 14.dp, top = 12.dp,
                        bottom = (LocalHomeDockClearance.current ?: 86.dp) + 18.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (!state.running || groups.isEmpty()) item("empty") {
                        Column(
                            Modifier.fillMaxWidth().padding(vertical = 36.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(if (!state.running) "代理未运行" else "没有匹配的策略",
                                color = MiuixTheme.colorScheme.onSurface)
                            TextButton(onClick = ::requestRefresh, enabled = !refreshing) {
                                Text(if (refreshing) "刷新中…" else "刷新")
                            }
                        }
                    }
                    groups.chunked(groupColumns).forEachIndexed { rowIndex, row ->
                        item("group-row:$rowIndex") {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                row.forEach { group ->
                                    val current = selection.current(group)
                                    BoxProxyGroupCard17(
                                        group = group,
                                        selected = current,
                                        delay = delays[current] ?: group.nodes.firstOrNull { it.name == current }?.lastDelay,
                                        online = group.nodes.count { node ->
                                            ((delays[node.name] ?: node.lastDelay) ?: 0L) > 0L
                                        },
                                        compact = options.groupDensity == "compact",
                                        modifier = Modifier.weight(1f).aspectRatio(1.50f),
                                    ) {
                                        sheetGroup = group.name
                                    }
                                }
                                repeat(groupColumns - row.size) { Spacer(Modifier.weight(1f)) }
                            }
                        }
                        if (!options.expandInSheet) {
                            row.forEach { group ->
                                if (group.name in expanded || query.isNotBlank()) {
                                    item("tools:${group.name}") {
                                        Row(
                                            Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                "${group.name} · ${group.nodes.size} 个节点",
                                                Modifier.weight(1f),
                                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                                fontSize = 11.sp
                                            )
                                            TextButton(
                                                onClick = { testAll(group) },
                                                enabled = batchTesting[group.name] != true && state.running
                                            ) {
                                                Text(
                                                    if (batchTesting[group.name] == true)
                                                        "${batchDone[group.name] ?: 0}/${group.nodes.size}"
                                                    else "组测速"
                                                )
                                            }
                                        }
                                    }
                                    val projected = projectStrategyNodes(group.nodes, legacy.sort, legacy.descending, delays)
                                    val sections = if (options.groupByProvider) {
                                        projected.groupBy { it.provider.ifBlank { "配置内节点" } }
                                    } else linkedMapOf("" to projected)
                                    sections.forEach { (provider, members) ->
                                        if (provider.isNotBlank()) item("provider:${group.name}:$provider") {
                                            Text(
                                                provider,
                                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                                fontSize = 11.sp,
                                                modifier = Modifier.padding(start = 6.dp, top = 4.dp)
                                            )
                                        }
                                        members.chunked(nodeColumns).forEachIndexed { nodeRow, nodes ->
                                            item("nodes:${group.name}:$provider:$nodeRow") {
                                                BoxProxyNodeGridRow18(
                                                    nodes = nodes,
                                                    columns = nodeColumns,
                                                    current = selection.current(group),
                                                    pending = selection.pending[group.name],
                                                    delays = delays,
                                                    testing = testing,
                                                    compact = options.nodeDensity == "compact",
                                                    overflowMode = options.nameOverflow,
                                                    onSelect = { choose(group, it) },
                                                    onDelay = { scope.launch { testNode(it) } },
                                                    onLong = { detail = it },
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        sheetGroup?.let(allGroups::get)?.let { group ->
            BoxProxyPolicySheet17(
                group = group,
                current = selection.current(group),
                pending = selection.pending[group.name],
                delays = delays,
                testing = testing,
                running = state.running,
                options = options,
                onDismiss = { sheetGroup = null },
                onSelect = { choose(group, it) },
                onDelay = { scope.launch { testNode(it) } },
                onTestAll = { testAll(group) },
            )
        }
    }

    detail?.let { value ->
        NativeDetailsSheet("详细信息", { detail = null }) {
            Text(value, color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp)
        }
    }
}

private fun boxProxyFlag17(value: String): String {
    val name = value.lowercase()
    return when {
        listOf("新加坡", "singapore", " sg ", "🇸🇬").any(name::contains) -> "🇸🇬"
        listOf("日本", "japan", "tokyo", "osaka", " jp ", "🇯🇵").any(name::contains) -> "🇯🇵"
        listOf("香港", "hong kong", " hk ", "🇭🇰").any(name::contains) -> "🇭🇰"
        listOf("台湾", "taiwan", "taipei", " tw ", "🇹🇼").any(name::contains) -> "🇹🇼"
        listOf("韩国", "korea", "seoul", " kr ", "🇰🇷").any(name::contains) -> "🇰🇷"
        listOf("美国", "united states", "los angeles", "san jose", " us ", "🇺🇸").any(name::contains) -> "🇺🇸"
        listOf("英国", "united kingdom", "london", " uk ", "🇬🇧").any(name::contains) -> "🇬🇧"
        listOf("德国", "germany", "frankfurt", " de ", "🇩🇪").any(name::contains) -> "🇩🇪"
        else -> "◉"
    }
}

private fun boxProxyGroupAccent17(group: ProxyGroupUi): Color {
    val key = (group.name + " " + group.type).lowercase()
    return when {
        "urltest" in key || "url-test" in key || "自动" in key -> Color(0xFFFF9F43)
        "fallback" in key || "故障" in key -> Color(0xFF4ACB86)
        "youtube" in key -> Color(0xFFFF334B)
        "google" in key -> Color(0xFF4285F4)
        "openai" in key || "chatgpt" in key -> Color(0xFF10A37F)
        "telegram" in key -> Color(0xFF229ED9)
        else -> Color(0xFF4F8CFF)
    }
}

@Composable
private fun BoxProxyGroupCard17(
    group: ProxyGroupUi,
    selected: String,
    delay: Long?,
    online: Int,
    compact: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val accent = boxProxyGroupAccent17(group)
    val interaction = remember(group.name) { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) .97f else 1f,
        animationSpec = spring(dampingRatio = .72f, stiffness = 520f),
        label = "strategy-card-${group.name}",
    )
    Box(
        modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                alpha = if (pressed) .965f else 1f
            }
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            )
            .testTag("boxproxy17-group:${group.name}"),
    ) {
        MiuixCard(
            modifier = Modifier.fillMaxSize(),
            cornerRadius = 13.dp,
            colors = MiuixCardDefaults.defaultColors(),
        ) {
            Column(
                Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(0.dp),
            ) {
                Row(verticalAlignment = Alignment.Top) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            group.name,
                            color = MiuixTheme.colorScheme.onSurfaceContainer,
                            fontSize = 14.sp,
                            lineHeight = 15.5.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            "${group.type.ifBlank { "Selector" }}  ${online}/${group.nodes.size}",
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            fontSize = 10.5.sp,
                            lineHeight = 12.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Spacer(Modifier.width(6.dp))
                    Box(
                        Modifier.size(26.dp).background(accent.copy(alpha = .12f), RoundedCornerShape(8.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        ConfiguredGroupIcon(group, Modifier.size(19.dp))
                    }
                }
                Spacer(Modifier.weight(1f))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(boxProxyFlag17(selected), fontSize = 13.sp, lineHeight = 15.sp)
                    Spacer(Modifier.width(4.dp))
                    Text(
                        selected.ifBlank { "未选择" },
                        Modifier.weight(1f),
                        color = MiuixTheme.colorScheme.onSurfaceSecondary,
                        fontSize = 11.5.sp,
                        lineHeight = 14.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.width(6.dp))
                    BoxProxyDelay17(delay, false)
                }
            }
        }
    }
}

@Composable
private fun BoxProxyNodeGridRow18(
    nodes: List<ProxyNodeUi>,
    columns: Int,
    current: String,
    pending: String?,
    delays: Map<String, Long>,
    testing: Map<String, Boolean>,
    compact: Boolean,
    overflowMode: String,
    onSelect: (String) -> Unit,
    onDelay: (String) -> Unit,
    onLong: (String) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        nodes.forEach { node ->
            BoxProxyNodeCell18(
                node = node,
                active = node.name == current,
                pending = pending == node.name,
                delay = delays[node.name] ?: node.lastDelay,
                testing = testing[node.name] == true,
                compact = compact,
                overflowMode = overflowMode,
                modifier = Modifier.weight(1f),
                onClick = { onSelect(node.name) },
                onDelay = { onDelay(node.name) },
                onLong = { onLong(node.name) },
            )
        }
        repeat((columns - nodes.size).coerceAtLeast(0)) {
            Spacer(Modifier.weight(1f))
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun BoxProxyNodeCell18(
    node: ProxyNodeUi,
    active: Boolean,
    pending: Boolean,
    delay: Long?,
    testing: Boolean,
    compact: Boolean,
    overflowMode: String,
    modifier: Modifier,
    onClick: () -> Unit,
    onDelay: () -> Unit,
    onLong: () -> Unit,
) {
    val selectedBg = if (active) MiuixTheme.colorScheme.tertiaryContainer else MiuixTheme.colorScheme.surfaceContainer
    MiuixCard(
        modifier = modifier
            .height(if (compact) 62.dp else 72.dp)
            .semantics {
                selected = active
                stateDescription = if (pending) "切换中" else if (active) "已选择" else "未选择"
            },
        cornerRadius = 12.dp,
        colors = MiuixCardDefaults.defaultColors(
            color = selectedBg,
            contentColor = MiuixTheme.colorScheme.onSurfaceContainer,
        ),
        pressFeedbackType = PressFeedbackType.Sink,
        onClick = onClick,
        onLongPress = onLong,
    ) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 7.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val flag = boxProxyExplicitFlag18(node.name)
            if (flag != null) {
                Text(flag, fontSize = 22.sp, modifier = Modifier.width(28.dp))
                Spacer(Modifier.width(8.dp))
            }
            Text(
                node.name,
                Modifier.weight(1f),
                color = MiuixTheme.colorScheme.onSurfaceContainer,
                fontSize = 12.sp,
                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = if (overflowMode == "wrap") 2 else 1,
                overflow = if (overflowMode == "clip") TextOverflow.Clip else TextOverflow.Ellipsis,
            )
            if (pending) {
                Text("…", color = MiuixTheme.colorScheme.primary, fontSize = 14.sp,
                    modifier = Modifier.padding(start = 6.dp))
            } else if (active) {
                Text("✓", color = MiuixTheme.colorScheme.primary, fontSize = 18.sp,
                    fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 6.dp))
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                listOf(node.type.uppercase(), if (node.udp) "UDP" else "")
                    .filter { it.isNotBlank() }.joinToString(" · "),
                Modifier.weight(1f),
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                fontSize = 10.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Box(
                Modifier.heightIn(min = 24.dp).padding(start = 5.dp)
                    .clickable(enabled = !testing, onClick = onDelay),
                contentAlignment = Alignment.CenterEnd,
            ) {
                BoxProxyDelay17(delay, testing)
            }
        }
        }
    }
}

private fun boxProxyExplicitFlag18(name: String): String? {
    val points = name.codePoints().toArray()
    for (i in 0 until points.size - 1) {
        if (points[i] in 0x1F1E6..0x1F1FF && points[i + 1] in 0x1F1E6..0x1F1FF) {
            return String(Character.toChars(points[i])) + String(Character.toChars(points[i + 1]))
        }
    }
    return null
}

@Composable
private fun BoxProxyDelay17(value: Long?, testing: Boolean) {
    val text = when {
        testing -> "…"
        value == null || value <= 0L -> "--"
        else -> "$value ms"
    }
    val foreground: Color
    val background: Color
    when {
        testing || value == null || value <= 0L -> {
            foreground = Color(0xFF9CA3AF)
            background = Color(0xFFF3F4F6)
        }
        value < 100L -> {
            foreground = Color(0xFF00B578)
            background = Color(0xFFE8F8F0)
        }
        value < 200L -> {
            foreground = Color(0xFF1E6FFF)
            background = Color(0xFFEBF3FF)
        }
        else -> {
            foreground = Color(0xFFFF8F1F)
            background = Color(0xFFFFF4E6)
        }
    }
    Box(
        Modifier.background(background, RoundedCornerShape(999.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = foreground, fontSize = 10.sp, lineHeight = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
private fun BoxProxyPolicySheet17(
    group: ProxyGroupUi,
    current: String,
    pending: String?,
    delays: Map<String, Long>,
    testing: Map<String, Boolean>,
    running: Boolean,
    options: BoxProxyPrefs17,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
    onDelay: (String) -> Unit,
    onTestAll: () -> Unit,
) {
    var query by rememberSaveable(group.name) { mutableStateOf("") }
    val nodeColumns = if (LocalDensity.current.fontScale >= 1.45f) 1 else 2
    val legacy = options.legacy()
    val nodes = remember(group.nodes, query, options, delays) {
        val filtered = if (query.isBlank()) group.nodes else group.nodes.filter {
            it.name.contains(query, true) || it.provider.contains(query, true) || it.type.contains(query, true)
        }
        projectStrategyNodes(filtered, legacy.sort, legacy.descending, delays)
    }

    OverlayBottomSheet(
        show = true,
        title = "${group.name} · 子节点选择",
        backgroundColor = if (MaterialTheme.colorScheme.background.luminance() < .5f)
            MiuixTheme.colorScheme.surface.copy(alpha = .94f) else Color(0xFFF6F8FA),
        sheetMaxWidth = 720.dp,
        onDismissRequest = onDismiss,
        endAction = {
            TextButton(onClick = onDismiss) { Text("完成") }
        },
    ) {
        Box(Modifier.fillMaxWidth().fillMaxHeight(.82f)) {
            Column(Modifier.fillMaxSize().padding(bottom = 68.dp)) {
            MiuixSearchBar(
                inputField = {
                    MiuixInputField(
                        query = query,
                        onQueryChange = { query = it },
                        onSearch = {},
                        expanded = true,
                        onExpandedChange = {},
                        label = "搜索策略组或节点"
                    )
                },
                onExpandedChange = {},
                modifier = Modifier.fillMaxWidth(),
                insideMargin = DpSize(12.dp, 8.dp),
                expanded = true,
            ) {}
            LazyColumn(
                Modifier.fillMaxWidth().weight(1f),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                val sections = if (options.groupByProvider) {
                    nodes.groupBy { it.provider.ifBlank { "配置内节点" } }
                } else linkedMapOf("" to nodes)
                sections.forEach { (provider, members) ->
                    if (provider.isNotBlank()) item("sheet-provider:$provider") {
                        Text(
                            provider,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 3.dp)
                        )
                    }
                    members.chunked(nodeColumns).forEachIndexed { rowIndex, row ->
                        item("sheet-row:$provider:$rowIndex") {
                            BoxProxyNodeGridRow18(
                                nodes = row,
                                columns = nodeColumns,
                                current = current,
                                pending = pending,
                                delays = delays,
                                testing = testing,
                                compact = options.nodeDensity == "compact",
                                overflowMode = options.nameOverflow,
                                onSelect = onSelect,
                                onDelay = onDelay,
                                onLong = {},
                            )
                        }
                    }
                }
            }
            }
            MiuixFab(
                onClick = onTestAll,
                minWidth = 64.dp,
                minHeight = 48.dp,
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 24.dp),
            ) {
                Text("组测速", color = MiuixTheme.colorScheme.onPrimary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}
