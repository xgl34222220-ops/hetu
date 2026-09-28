package io.github.xgl34222220.hetu

import android.content.SharedPreferences
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.xgl34222220.hetu.ui.*
import kotlinx.coroutines.*
import java.util.Locale

/** Production adapter. All network mutations use the original146 repository, never Vue demo data. */
@Composable
internal fun PanelStrategyRoute11(state: ProxyComposeState, repo: ProxyDashboardRepository,
    delays: MutableMap<String, Long>, searchRequest: Int,
    onTab: (RefPanelTab) -> Unit, onRefresh: suspend () -> Unit,
    onDetailVisible: (Boolean) -> Unit) {
    val context = LocalContext.current
    val prefs = remember(context) { context.getSharedPreferences("hetu", 0) }
    StrategyPanel11(state, delays, prefs, searchRequest, onTab, onRefresh,
        select = { group, node ->
            repo.select(group, node, prefs.getBoolean("proxySelectorDisconnectOnSelect", false))
            val actual = repo.state().groups.firstOrNull { it.name == group }?.now.orEmpty()
            onRefresh()
            actual
        }, measure = { node -> repo.delay(node) }, onDetailVisible = onDetailVisible)
}

/** All UI and real callbacks are injectable so touch, failures and delayed acknowledgements are testable. */
@Composable
internal fun StrategyPanel11(
    state: ProxyComposeState, delays: MutableMap<String, Long>, prefs: SharedPreferences,
    searchRequest: Int = 0, onTab: (RefPanelTab) -> Unit,
    refresh: suspend () -> Unit, select: suspend (String, String) -> String,
    measure: suspend (String) -> Long, onDetailVisible: (Boolean) -> Unit = {},
) {
    val t = LocalHetuTokens.current
    val motion = homeMotionAvailable(LocalHetuMotionEnabled.current)
    val scope = rememberCoroutineScope()
    val options = rememberPanelOptions11(prefs)
    val selection = remember { PanelSelection11() }
    val list = rememberLazyListState()
    var expanded by rememberSaveable { mutableStateOf(listOf<String>()) }
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var settings by remember { mutableStateOf(false) }
    var detail by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf("") }
    var noticeError by remember { mutableStateOf(false) }
    var refreshing by remember { mutableStateOf(false) }
    var jump by remember { mutableStateOf<String?>(null) }
    val testing = remember { mutableStateMapOf<String, Boolean>() }
    val batchTesting = remember { mutableStateMapOf<String, Boolean>() }
    val fontScale = LocalDensity.current.fontScale
    val columns = if (fontScale >= 1.5f) 1 else options.columns
    val delaySnapshot = delays.toMap()
    val groups = remember(state.groups, query, state.trafficMode, options) {
        panelGroups11(state.groups, query, state.trafficMode, options)
    }
    val entries = remember(groups, expanded, query, options, delaySnapshot, columns) {
        panelEntries11(groups, expanded.toSet(), query, options, delaySnapshot, columns)
    }
    val allGroups = remember(state.groups) { state.groups.associateBy { it.name } }
    val onRefreshLatest by rememberUpdatedState(refresh)
    val onSelectLatest by rememberUpdatedState(select)
    val onMeasureLatest by rememberUpdatedState(measure)
    val currentState by rememberUpdatedState(state)
    val accessibility = LocalAccessibilityManager.current
    LaunchedEffect(state.groups) { selection.reconcile(state.groups) }
    LaunchedEffect(searchRequest) { if (searchRequest > 0) searchOpen = true }
    LaunchedEffect(settings, detail) { onDetailVisible(settings || detail != null) }
    DisposableEffect(Unit) { onDispose { onDetailVisible(false) } }
    LaunchedEffect(notice) {
        if (notice.isNotEmpty()) {
            val old = notice
            delay(accessibility?.calculateRecommendedTimeoutMillis(5000, true, true, true) ?: 5000)
            if (notice == old) notice = ""
        }
    }
    LaunchedEffect(jump, entries) {
        val name = jump ?: return@LaunchedEffect
        val index = entries.indexOfFirst { it is PanelEntry11.Header && it.group.name == name }
        if (index >= 0) {
            if (motion) list.animateScrollToItem(index) else list.scrollToItem(index)
            jump = null
        }
    }
    fun notify(text: String, failed: Boolean = false) { noticeError = failed; notice = text }
    fun expand(name: String) { expanded = panelExpanded11(expanded.toSet(), name, options.collapsePrevious).toList() }
    fun requestRefresh() {
        if (refreshing) return
        refreshing = true
        scope.launch {
            try { onRefreshLatest(); notify("面板状态已刷新") }
            catch (cancel: CancellationException) { throw cancel }
            catch (e: Exception) { notify(e.message ?: "刷新失败", true) }
            finally { refreshing = false }
        }
    }
    suspend fun testNode(name: String) {
        if (testing[name] == true || !currentState.running) return
        testing[name] = true
        try {
            val value = onMeasureLatest(name)
            if (value > 0) delays[name] = value else notify("测速接口没有返回有效延迟", true)
        } catch (cancel: CancellationException) { throw cancel }
        catch (e: MihomoControllerClient.DelayFailure) {
            delays[name] = if (e.timedOut) -1L else -2L
        } catch (e: Exception) { notify(e.message ?: "测速连接失败，保留上次结果", true) }
        finally { testing.remove(name) }
    }
    fun testAll(group: ProxyGroupUi) {
        if (batchTesting[group.name] == true || !state.running) return
        batchTesting[group.name] = true
        // Individual node probes preserve an automatic group's fixed choice.
        // Do not call the group-delay endpoint, which clears that choice.
        scope.launch {
            try {
                group.nodes.map { it.name }.distinct().chunked(4).forEach { chunk ->
                    coroutineScope { chunk.map { async { testNode(it) } }.awaitAll() }
                }
            } finally { batchTesting.remove(group.name) }
        }
    }
    fun selectNode(group: ProxyGroupUi, name: String) {
        if (!state.running || selection.pending[group.name] != null) return
        scope.launch {
            try {
                if (selection.select(group, name, onSelectLatest)) notify("已切换到 $name")
            } catch (cancel: CancellationException) { throw cancel }
            catch (e: Exception) { notify(e.message ?: "切换失败，保留当前节点", true) }
        }
    }
    val (pull, connection) = rememberHomePull(list, refreshing, true, motion, ::requestRefresh)
    val (bottomRebound, bottomConnection) = rememberBottomRebound12(list, motion)
    val pixelDensity = LocalDensity.current.density
    val clearance = LocalHomeDockClearance.current ?:
        (86.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding())
    Column(Modifier.fillMaxSize().background(t.pageBackground).padding(bottom = clearance)
        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
        .testTag("panel11-root")) {
        PanelToolbar11(searchOpen, options, onSearch = {
            searchOpen = !searchOpen
            if (!searchOpen) query = ""
        }, onOptions = { next ->
            if (next.collapsePrevious && !options.collapsePrevious && expanded.size > 1) expanded = expanded.takeLast(1)
            next.save(prefs)
        }, onSettings = { settings = true })
        AnimatedVisibility(searchOpen,
            enter = expandVertically(HetuMotion12.spatial(motion)) + fadeIn(tween(if (motion) 160 else 0)),
            exit = shrinkVertically(HetuMotion12.spatial(motion)) + fadeOut(tween(if (motion) 120 else 0))) {
            OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                .testTag("panel11-search-input"), singleLine = true, shape = RoundedCornerShape(18.dp),
                textStyle = LocalTextStyle.current.copy(fontSize = 13.sp),
                placeholder = { Text("搜索策略组、节点或提供商", fontSize = 12.sp) },
                leadingIcon = { Icon(Icons.Rounded.Search, null, Modifier.size(18.dp)) },
                trailingIcon = { if (query.isNotEmpty()) IconButton({ query = "" }) { Icon(Icons.Rounded.Close, "清除搜索") } })
        }
        PanelSubTabs11(RefPanelTab.Groups, onTab)
        if (notice.isNotBlank()) Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            .fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .background((if (noticeError) t.danger else Color(0xFF2563EB)).copy(alpha = .07f))
            .testTag("panel11-notice").nativePress(label = "查看完整反馈", onClick = { detail = notice })
            .padding(horizontal = 12.dp, vertical = 10.dp).semantics { liveRegion = LiveRegionMode.Polite },
            verticalAlignment = Alignment.CenterVertically) {
            Text(notice, Modifier.weight(1f), color = if (noticeError) t.danger else t.textPrimary,
                fontSize = 12.sp, lineHeight = 18.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Icon(Icons.Rounded.ChevronRight, null, Modifier.size(16.dp), tint = t.textSecondary)
        }
        Box(Modifier.weight(1f).fillMaxWidth().clipToBounds().testTag("panel11-viewport")) {
            LazyColumn(Modifier.fillMaxSize().graphicsLayer { translationY = pull.offsetPx + bottomRebound.offsetPx }
                .nestedScroll(connection).nestedScroll(bottomConnection).semantics { this[EdgeOffset12] = bottomRebound.offsetPx / pixelDensity }.testTag("panel11-list"), state = list, overscrollEffect = null,
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 72.dp)) {
                if (groups.isEmpty()) item("empty") {
                    Column(Modifier.fillMaxWidth().padding(vertical = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(when { query.isNotBlank() -> "没有匹配的策略或节点"; !state.running -> "代理未运行"; else -> "当前筛选条件下没有策略组" },
                            color = t.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        Text(if (!state.running) "启动代理后读取真实节点；不会展示演示节点。" else "可调整筛选条件，或下拉刷新。",
                            color = t.textSecondary, fontSize = 12.sp)
                        TextButton(onClick = ::requestRefresh, enabled = !refreshing, modifier = Modifier.testTag("panel11-empty-refresh")) { Text("刷新状态") }
                    }
                }
                items(entries, key = { it.key }, contentType = { it.javaClass.simpleName }) { entry ->
                    val group = allGroups[entry.group.name] ?: entry.group
                    val current = selection.current(group)
                    val animation = if (motion) Modifier.animateItem(
                        fadeInSpec = tween(180), placementSpec = spring(dampingRatio = .84f, stiffness = 280f), fadeOutSpec = tween(120)
                    ) else Modifier
                    when (entry) {
                        is PanelEntry11.Header -> PanelGroupHeader11(group, current, entry.expanded,
                            delaySnapshot[current] ?: group.nodes.firstOrNull { it.name == current }?.lastDelay,
                            delaySnapshot, animation.padding(top = 10.dp), onClick = { expand(group.name) })
                        is PanelEntry11.Tools -> Row(animation.fillMaxWidth().background(t.cardBackground)
                            .padding(horizontal = 14.dp).heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("切换节点 · 核心确认后生效", Modifier.weight(1f), color = t.textSecondary, fontSize = 11.sp, lineHeight = 16.sp)
                            TextButton(onClick = { testAll(group) }, enabled = state.running && batchTesting[group.name] != true,
                                modifier = Modifier.testTag("panel11-test-all:${group.name}")) {
                                if (batchTesting[group.name] == true) NativeSpinner(Color(0xFF2563EB), motion, Modifier.size(14.dp))
                                Text(if (batchTesting[group.name] == true) " 测速中" else "全测速", fontSize = 12.sp)
                            }
                        }
                        is PanelEntry11.Provider -> Text(entry.provider,
                            animation.fillMaxWidth().background(t.cardBackground).padding(horizontal = 16.dp, vertical = 8.dp)
                                .testTag("panel11-provider:${group.name}:${entry.provider}"), color = t.textSecondary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        is PanelEntry11.Nodes -> Row(animation.fillMaxWidth().background(t.cardBackground)
                            .padding(start = 12.dp, end = 12.dp, bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            entry.nodes.forEach { node ->
                                PanelNode11(group.name, node, node.name == current,
                                    delaySnapshot[node.name] ?: node.lastDelay,
                                    testing[node.name] == true, selection.pending[group.name] == node.name,
                                    state.running && selection.pending[group.name] == null,
                                    options.compact, Modifier.weight(1f),
                                    onSelect = { selectNode(group, node.name) },
                                    onDelay = { scope.launch { testNode(node.name) } },
                                    onName = { detail = node.name + "\n" + node.type + if (node.provider.isNotBlank()) "\n提供商：${node.provider}" else "" },
                                    onNested = if (node.name in allGroups && node.name != group.name) ({
                                        query = ""; expanded = if (options.collapsePrevious) listOf(node.name) else (expanded + node.name).distinct(); jump = node.name
                                    }) else null)
                            }
                            if (entry.nodes.size < entry.columns) Spacer(Modifier.weight(1f))
                        }
                        is PanelEntry11.End -> Box(animation.fillMaxWidth().height(8.dp)
                            .clip(RoundedCornerShape(bottomStart = 20.dp, bottomEnd = 20.dp)).background(t.cardBackground))
                    }
                }
            }
            HomePullIndicator(pull, motion, Modifier.align(Alignment.TopCenter))
            if (state.running && groups.isNotEmpty()) Box(Modifier.align(Alignment.BottomEnd)
                .padding(end = 16.dp, bottom = 8.dp).heightIn(min = 48.dp).clip(CircleShape)
                .background(Color(0xFF2563EB).copy(alpha = .94f))
                .nativePress(label = "定位并展开主策略", onClick = {
                    val visible = panelGroups11(state.groups, "", state.trafficMode, options)
                    val target = visible.firstOrNull { it.name == "节点选择" }
                        ?: visible.firstOrNull { it.type.equals("Selector", true) && it.name != "GLOBAL" }
                        ?: visible.firstOrNull()
                    if (target != null) {
                        query = ""
                        expanded = if (options.collapsePrevious) listOf(target.name) else (expanded + target.name).distinct()
                        jump = target.name
                    }
                }).testTag("panel11-fab").padding(horizontal = 16.dp, vertical = 10.dp), contentAlignment = Alignment.Center) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(Icons.Rounded.ExpandMore, null, Modifier.size(18.dp), tint = Color.White)
                    Text("节点选择", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
    if (settings) PanelApiSheet11(prefs, state.controllerPort, { settings = false }) {
        notify("设置已保存，正在读取接口状态")
        requestRefresh()
    }
    detail?.let { value -> NativeDetailsSheet("详细信息", { detail = null }) {
        SelectionContainer { Text(value, color = t.textPrimary, fontSize = 13.sp, lineHeight = 20.sp) }
    } }
}

@Composable
private fun PanelGroupHeader11(group: ProxyGroupUi, current: String, open: Boolean, delay: Long?,
    delays: Map<String, Long>, modifier: Modifier, onClick: () -> Unit) {
    val t = LocalHetuTokens.current
    val motion = LocalHetuMotionEnabled.current
    val rotation by animateFloatAsState(if (open) 180f else 0f, HetuMotion12.spatial(motion), label = "group-expand")
    val shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomStart = if (open) 0.dp else 20.dp, bottomEnd = if (open) 0.dp else 20.dp)
    Column(modifier.fillMaxWidth().clip(shape).background(t.cardBackground)
        .nativePress(label = if (open) "折叠${group.name}" else "展开${group.name}", onClick = onClick)
        .testTag("panel11-group:${group.name}").semantics { stateDescription = if (open) "已展开" else "已折叠" }
        .padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(group.name, Modifier.weight(1f), color = t.textPrimary, fontSize = 15.sp,
                lineHeight = 21.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.width(8.dp))
            ConfiguredGroupIcon(group, Modifier.size(24.dp))
            Spacer(Modifier.width(6.dp))
            Icon(Icons.Rounded.ExpandMore, null, Modifier.size(20.dp).graphicsLayer { rotationZ = rotation }, tint = if (open) Color(0xFF2563EB) else t.textSecondary)
        }
        val tested = group.nodes.count { (delays[it.name] ?: it.lastDelay ?: 0) > 0 }
        Text("${group.type} · 已测 $tested/${group.nodes.size}", color = t.textSecondary,
            fontSize = 11.sp, lineHeight = 16.sp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(current.ifBlank { "当前节点未知" }, Modifier.weight(1f).testTag("panel11-current:${group.name}"),
                color = t.textPrimary.copy(alpha = .8f), fontSize = 12.sp, lineHeight = 18.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.width(8.dp)); PanelDelayLabel11(delay, false)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PanelNode11(group: String, node: ProxyNodeUi, active: Boolean, delay: Long?, testing: Boolean,
    pending: Boolean, enabled: Boolean, compact: Boolean, modifier: Modifier,
    onSelect: () -> Unit, onDelay: () -> Unit, onName: () -> Unit, onNested: (() -> Unit)?) {
    val t = LocalHetuTokens.current
    val motion = LocalHetuMotionEnabled.current
    val fill by animateColorAsState(if (active) Color(0xFF2563EB).copy(alpha = .09f) else t.controlBackground.copy(alpha = .26f),
        tween(if (motion) 200 else 0), label = "selected-node")
    // nativePress owns the main click; a separate info action exposes untruncated names.
    Column(modifier.clip(RoundedCornerShape(14.dp)).background(fill)
        .border(1.dp, if (active) Color(0xFF93C5FD).copy(alpha = .65f) else t.textMuted.copy(alpha = .1f), RoundedCornerShape(14.dp))
        .panelNodePress11(enabled = enabled, onClick = onSelect, onLongClick = onName)
        .semantics { selected = active; role = Role.RadioButton; stateDescription = if (pending) "等待核心确认" else if (active) "当前节点" else "未选择" }
        .testTag("panel11-node:$group:${node.name}").padding(start = 10.dp, end = 6.dp, top = if (compact) 7.dp else 11.dp, bottom = if (compact) 3.dp else 7.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            Text(node.name, Modifier.weight(1f).testTag("panel11-node-name:$group:${node.name}"),
                color = t.textPrimary, fontSize = 12.sp, lineHeight = 18.sp,
                fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                maxLines = if (compact) 1 else 2, overflow = TextOverflow.Ellipsis)
            if (pending) NativeSpinner(Color(0xFF2563EB), motion, Modifier.size(16.dp))
            else if (active) Icon(Icons.Rounded.Check, "当前正在使用", Modifier.size(17.dp).testTag("panel11-check:$group:${node.name}"), tint = Color(0xFF2563EB))
        }
        Text(listOf(node.type.uppercase(Locale.ROOT).ifBlank { "协议未知" }, if (node.udp) "UDP" else "")
            .filter(String::isNotBlank).joinToString(" · "), Modifier.padding(top = 4.dp)
                .testTag("panel11-protocol:$group:${node.name}"), color = t.textSecondary,
            fontSize = 10.sp, lineHeight = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.End) {
            if (onNested != null) Box(Modifier.size(48.dp).clip(CircleShape)
                .nativePress(label = "展开子策略${node.name}", onClick = onNested), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.ChevronRight, null, Modifier.size(17.dp), tint = t.textSecondary)
            }
            Box(Modifier.weight(1f).heightIn(min = 48.dp).clip(RoundedCornerShape(10.dp))
                .nativePress(enabled = enabled && !testing, label = "测试${node.name}延迟", onClick = onDelay)
                .testTag("panel11-delay:$group:${node.name}"), contentAlignment = Alignment.CenterEnd) {
                PanelDelayLabel11(delay, testing)
            }
        }
    }
}

@Composable
private fun PanelDelayLabel11(value: Long?, loading: Boolean) {
    val t = LocalHetuTokens.current
    val color = when { value == -1L || value == -2L -> t.danger; value != null && value >= 300 -> Color(0xFFD97706); value != null && value > 0 -> Color(0xFF2563EB); else -> t.textSecondary }
    Box(Modifier.clip(CircleShape).background(color.copy(alpha = .07f)).padding(horizontal = 7.dp, vertical = 3.dp)) {
        if (loading) NativeSpinner(Color(0xFF2563EB), LocalHetuMotionEnabled.current, Modifier.size(14.dp))
        else Text(when { value == -1L -> "超时"; value == -2L -> "失败"; value != null && value > 0 -> "$value ms"; else -> "未测" },
            color = color, fontSize = 11.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold,
            style = LocalTextStyle.current.copy(fontFeatureSettings = "tnum"), maxLines = 1)
    }
}
