@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package io.github.xgl34222220.hetu

import android.content.SharedPreferences
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
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
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.xgl34222220.hetu.ui.*
import kotlinx.coroutines.*

/** A single immutable source for every panel page. Enum declaration order is not UI order. */
internal val PanelTabs13 = listOf(RefPanelTab.Overview, RefPanelTab.Groups, RefPanelTab.Subscriptions,
    RefPanelTab.Connections, RefPanelTab.Rules, RefPanelTab.RuleSets)

@Composable
internal fun FixedPanelTabs13(selected: RefPanelTab, change: (RefPanelTab) -> Unit) {
    val t = LocalHetuTokens.current
    val fontScale = LocalDensity.current.fontScale
    BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
        .testTag("panel11-subtabs")) {
        val minimum = maxOf(48f, 36f * fontScale + 16f).dp
        val scrollable = maxWidth < minimum * PanelTabs13.size
        val width = if (scrollable) minimum else maxWidth / PanelTabs13.size
        Row(Modifier.then(if (scrollable) Modifier.horizontalScroll(rememberScrollState()) else Modifier.fillMaxWidth())) {
            PanelTabs13.forEach { tab ->
                key(tab.name) {
                    val active = selected == tab
                    Box(Modifier.width(width).heightIn(min = 48.dp).clip(CircleShape)
                        .background(if (active) t.cardBackground else Color.Transparent)
                        .semantics { this.selected = active; role = Role.Tab }
                        .nativePress(label = tab.label, onClick = { if (!active) change(tab) })
                        .testTag("panel11-tab-${tab.name}"),
                        contentAlignment = Alignment.Center) {
                        Text(tab.label, color = if (active) Color(0xFF2563EB) else t.textSecondary,
                            fontSize = 12.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    }
                }
            }
        }
    }
}

/** Presentation-only back stack, independent of primary navigation and acknowledged core state. */
internal fun panelPush13(path: List<String>, name: String): List<String> =
    if (name in path) path.take(path.indexOf(name) + 1) else path + name

/** Extract a flag explicitly present in the configured group name, never infer a server country. */
internal fun explicitGroupFlag13(name: String): String? {
    val points = name.codePoints().toArray()
    for (i in 0 until points.size - 1) {
        if (points[i] in 0x1F1E6..0x1F1FF && points[i + 1] in 0x1F1E6..0x1F1FF)
            return String(Character.toChars(points[i])) + String(Character.toChars(points[i + 1]))
    }
    return null
}

@Composable
internal fun StrategyPanel13(state: ProxyComposeState, delays: MutableMap<String, Long>, prefs: SharedPreferences,
    searchRequest: Int, onTab: (RefPanelTab) -> Unit, refresh: suspend () -> Unit,
    select: suspend (String, String) -> String, measure: suspend (String) -> Long,
    onDetailVisible: (Boolean) -> Unit) {
    val t = LocalHetuTokens.current
    val motion = homeMotionAvailable(LocalHetuMotionEnabled.current)
    val scope = rememberCoroutineScope()
    val options = rememberPanelOptions11(prefs)
    val selection = remember { PanelSelection11() }
    val list = rememberLazyListState()
    var path by rememberSaveable { mutableStateOf(listOf<String>()) }
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var settings by remember { mutableStateOf(false) }
    var detail by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf("") }
    var noticeError by remember { mutableStateOf(false) }
    var refreshing by remember { mutableStateOf(false) }
    val testing = remember { mutableStateMapOf<String, Boolean>() }
    val batchTesting = remember { mutableStateMapOf<String, Boolean>() }
    val batchDone14 = remember { mutableStateMapOf<String, Int>() }
    val fontScale = LocalDensity.current.fontScale
    val columns = if (fontScale >= 1.5f) 1 else options.columns
    val outerColumns = if (fontScale >= 1.5f) 1 else 2
    val allGroups = remember(state.groups) { state.groups.associateBy { it.name } }
    // Filtering finds groups by any real node/provider, but does not change their actual counts.
    val groups = remember(state.groups, query, state.trafficMode, options) {
        panelGroups11(state.groups, query, state.trafficMode, options).map { allGroups.getValue(it.name) }
    }
    val rows = remember(groups, outerColumns) { groups.chunked(outerColumns) }
    val onRefreshLatest by rememberUpdatedState(refresh)
    val onSelectLatest by rememberUpdatedState(select)
    val onMeasureLatest by rememberUpdatedState(measure)
    val currentState by rememberUpdatedState(state)
    val accessibility = LocalAccessibilityManager.current
    LaunchedEffect(state.groups) {
        selection.reconcile(state.groups)
        path = path.filter { it in allGroups }
    }
    LaunchedEffect(searchRequest) { if (searchRequest > 0) searchOpen = true }
    LaunchedEffect(settings, detail, path.isNotEmpty()) { onDetailVisible(settings || detail != null || path.isNotEmpty()) }
    DisposableEffect(Unit) { onDispose { onDetailVisible(false) } }
    LaunchedEffect(notice) {
        if (notice.isNotEmpty()) {
            val old = notice
            delay(accessibility?.calculateRecommendedTimeoutMillis(5000, true, true, true) ?: 5000)
            if (notice == old) notice = ""
        }
    }
    fun notify(text: String, failed: Boolean = false) { noticeError = failed; notice = text }
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
        catch (e: MihomoControllerClient.DelayFailure) { delays[name] = if (e.timedOut) -1L else -2L }
        catch (e: Exception) { notify(e.message ?: "测速连接失败，保留上次结果", true) }
        finally { testing.remove(name) }
    }
    fun testAll(group: ProxyGroupUi) {
        if (batchTesting[group.name] == true || !state.running) return
        batchTesting[group.name] = true
        batchDone14[group.name] = 0
        scope.launch {
            try {
                group.nodes.map { it.name }.distinct().chunked(4).forEach { chunk ->
                    coroutineScope { chunk.map { name -> async { try { testNode(name) } finally { batchDone14[group.name] = (batchDone14[group.name] ?: 0) + 1 } } }.awaitAll() }
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
    val (bottom, bottomConnection) = rememberBottomRebound12(list, motion)
    val density = LocalDensity.current.density
    val clearance = LocalHomeDockClearance.current ?: (86.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding())
    Column(Modifier.fillMaxSize().background(t.pageBackground).padding(bottom = clearance)
        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
        .testTag("panel11-root")) {
        PanelToolbar11(searchOpen, options, onSearch = { searchOpen = !searchOpen; if (!searchOpen) query = "" },
            onOptions = { it.save(prefs) }, onSettings = { settings = true })
        FixedPanelTabs13(RefPanelTab.Groups, onTab)
        AnimatedVisibility(searchOpen, enter = expandVertically(HetuMotion12.spatial(motion)) + fadeIn(),
            exit = shrinkVertically(HetuMotion12.spatial(motion)) + fadeOut()) {
            OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                .testTag("panel11-search-input"), singleLine = true, shape = HomeContinuousShape(18.dp),
                textStyle = LocalTextStyle.current.copy(fontSize = 13.sp),
                placeholder = { Text("搜索策略组、节点或提供商", fontSize = 12.sp) },
                leadingIcon = { Icon(Icons.Rounded.Search, null, Modifier.size(18.dp)) },
                trailingIcon = { if (query.isNotEmpty()) IconButton({ query = "" }) { Icon(Icons.Rounded.Close, "清除搜索") } })
        }
        if (path.isEmpty()) PanelNotice13(notice, noticeError, "panel11-notice") { detail = notice }
        Box(Modifier.weight(1f).fillMaxWidth().clipToBounds().testTag("panel11-viewport")) {
            LazyColumn(Modifier.fillMaxSize().graphicsLayer { translationY = pull.offsetPx + bottom.offsetPx }
                .nestedScroll(connection).nestedScroll(bottomConnection)
                .semantics { this[EdgeOffset12] = bottom.offsetPx / density }.testTag("panel11-list"),
                state = list, overscrollEffect = null,
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (groups.isEmpty()) item("empty") {
                    Column(Modifier.fillMaxWidth().padding(vertical = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(when { query.isNotBlank() -> "没有匹配的策略或节点"; !state.running -> "代理未运行"; else -> "当前筛选条件下没有策略组" },
                            color = t.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        Text(if (!state.running) "启动代理后读取真实节点；不会展示演示节点。" else "可调整筛选条件，或下拉刷新。",
                            color = t.textSecondary, fontSize = 12.sp)
                        TextButton(::requestRefresh, enabled = !refreshing, modifier = Modifier.testTag("panel11-empty-refresh")) { Text("刷新状态") }
                    }
                }
                items(rows, key = { row -> row.joinToString("") { "${it.name.length}:${it.name}" } }) { row ->
                    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        row.forEach { group -> key(group.name) {
                            PanelGroupCard13(group, selection.current(group), delays, Modifier.weight(1f).fillMaxHeight()) {
                                notice = ""; path = listOf(group.name)
                            }
                        } }
                        if (row.size < outerColumns) Spacer(Modifier.weight(1f))
                    }
                }
            }
            HomePullIndicator(pull, motion, Modifier.align(Alignment.TopCenter))
        }
        PanelShortcut14(state.running && groups.isNotEmpty()) {
            val target = groups.firstOrNull { it.name == "节点选择" }
                ?: groups.firstOrNull { it.type.equals("Selector", true) && it.name != "GLOBAL" }
                ?: groups.firstOrNull()
            if (target != null) { notice = ""; path = listOf(target.name) }
        }
    }
    val activeGroup = path.lastOrNull()?.let(allGroups::get)
    if (activeGroup != null) PanelGroupSheet13(activeGroup, path.size, options, columns,
        selection.current(activeGroup), selection.pending[activeGroup.name], delays, testing,
        batchTesting[activeGroup.name] == true, state.running, notice, noticeError,
        onDismiss = { path = emptyList() }, onPop = { path = path.dropLast(1) },
        onSelect = { selectNode(activeGroup, it) }, onDelay = { scope.launch { testNode(it) } },
        onTestAll = { testAll(activeGroup) }, onName = { detail = it },
        nestedNames = allGroups.keys, onNested = { path = panelPush13(path, it); notice = "" },
        batchCompleted14 = batchDone14[activeGroup.name] ?: 0)
    if (settings) PanelApiSheet11(prefs, state.controllerPort, { settings = false }) {
        notify("设置已保存，正在读取接口状态"); requestRefresh()
    }
    detail?.let { value -> NativeDetailsSheet("详细信息", { detail = null }) {
        SelectionContainer { Text(value, color = t.textPrimary, fontSize = 13.sp, lineHeight = 20.sp) }
    } }
}

@Composable
internal fun PanelGroupCard13(group: ProxyGroupUi, current: String, delays: Map<String, Long>,
    modifier: Modifier = Modifier, onClick: () -> Unit) {
    val t = LocalHetuTokens.current
    val delay = delays[current] ?: group.nodes.firstOrNull { it.name == current }?.lastDelay
    val tested = group.nodes.count { (delays[it.name] ?: it.lastDelay ?: 0L) > 0 }
    val shape = HomeContinuousShape(20.dp)
    Column(modifier.heightIn(min = 80.dp).diffuseCardShadow(shape).clip(shape).background(t.cardBackground)
        .nativePress(label = "打开${group.name}节点", onClick = onClick).testTag("panel11-group:${group.name}")
        .semantics { stateDescription = "${group.type}，${group.nodes.size}个节点，当前$current" }
        .padding(horizontal = 10.dp, vertical = 9.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(group.name, Modifier.weight(1f), color = t.textPrimary, fontSize = 13.sp, lineHeight = 20.sp,
                fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.width(4.dp)); ConfiguredGroupIcon(group, Modifier.size(22.dp))
        }
        Text("${group.type} · 已测 $tested/${group.nodes.size}", color = t.textSecondary, fontSize = 10.sp, lineHeight = 14.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(current.ifBlank { "当前节点未知" }, Modifier.weight(1f).testTag("panel11-current:${group.name}"),
                color = t.textPrimary.copy(alpha = .82f), fontSize = 11.sp, lineHeight = 18.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.width(4.dp)); PanelDelayLabel11(delay, false)
        }
    }
}

@Composable
private fun PanelNotice13(text: String, failed: Boolean, tag: String, onClick: () -> Unit) {
    val t = LocalHetuTokens.current
    if (text.isNotBlank()) Text(text, Modifier.padding(horizontal = 16.dp, vertical = 4.dp).fillMaxWidth()
        .clip(HomeContinuousShape(14.dp)).background((if (failed) t.danger else Color(0xFF2563EB)).copy(alpha = .07f))
        .testTag(tag).nativePress(label = "查看完整反馈", onClick = onClick).padding(12.dp)
        .semantics { liveRegion = LiveRegionMode.Polite }, color = if (failed) t.danger else t.textPrimary,
        fontSize = 12.sp, lineHeight = 18.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
}

/** Secondary modal destination. No child entry is ever inserted into the parent grid. */
@Composable
internal fun PanelGroupSheet13(group: ProxyGroupUi, depth: Int, options: PanelOptions11, columns: Int,
    current: String, pending: String?, delays: Map<String, Long>, testing: Map<String, Boolean>,
    batchTesting: Boolean, running: Boolean, notice: String, noticeError: Boolean,
    onDismiss: () -> Unit, onPop: () -> Unit, onSelect: (String) -> Unit, onDelay: (String) -> Unit,
    onTestAll: () -> Unit, onName: (String) -> Unit, nestedNames: Set<String>, onNested: (String) -> Unit,
    batchCompleted14: Int = 0) {
    val t = LocalHetuTokens.current
    val sheet = rememberInteractiveSheetState()
    val scope = rememberCoroutineScope()
    var closing by remember { mutableStateOf(false) }
    fun close() { if (!closing) { closing = true; scope.launch { sheet.hide(); onDismiss() } } }
    MotionModalSheet12(onDismissRequest = onDismiss, sheetState = sheet,
        modifier = Modifier.testTag("panel13-group-sheet"), containerColor = t.pageBackground,
        dragHandle = { Box(Modifier.fillMaxWidth().height(32.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.width(40.dp).height(5.dp).background(t.textMuted.copy(alpha = .3f), CircleShape))
        } }) {
        BackHandler(enabled = depth > 1 && !closing, onBack = onPop)
        Column(Modifier.fillMaxWidth().fillMaxHeight(.76f).navigationBarsPadding().imePadding()) {
            Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 8.dp)
                .testTag("panel13-detail-header"), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { if (depth > 1) onPop() else close() }, enabled = !closing,
                    modifier = Modifier.size(48.dp).testTag("panel13-back")) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回节点分组", tint = t.textPrimary)
                }
                Column(Modifier.weight(1f)) {
                    Text(group.name, color = t.textPrimary, fontSize = 18.sp, lineHeight = 24.sp,
                        fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag("panel13-group-title"))
                    Text("${group.type} · ${group.nodes.size} 个节点", color = t.textSecondary, fontSize = 11.sp, lineHeight = 16.sp)
                }
                TextButton(onTestAll, enabled = running && !batchTesting && !closing,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("panel11-test-all:${group.name}")) {
                    Text(if (batchTesting) "${batchCompleted14}/${group.nodes.map { it.name }.distinct().size}" else "全部测速", fontSize = 12.sp)
                }
            }
            key(group.name) {
                var query by rememberSaveable { mutableStateOf("") }
                CompactNodeSearch14(query) { query = it }
                PanelNotice13(notice, noticeError, "panel13-detail-notice") { onName(notice) }
                val filtered = panelGroups11(listOf(group.copy(hidden = false)), query, "global", options.copy(globalByMode = false, showHidden = true))
                val snapshot = delays.toMap()
                val entries = remember(filtered, query, options, columns, snapshot) {
                    panelEntries11(filtered, setOf(group.name), "", options, snapshot, columns)
                        .filter { it is PanelEntry11.Nodes || it is PanelEntry11.Provider }
                }
                LazyColumn(Modifier.fillMaxWidth().weight(1f).testTag("panel13-node-list"),
                    contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (entries.isEmpty()) item { Text("没有匹配的节点", color = t.textSecondary, modifier = Modifier.padding(16.dp)) }
                    items(entries, key = { it.key }, contentType = { it.javaClass.simpleName }) { entry ->
                        when (entry) {
                            is PanelEntry11.Provider -> Text(entry.provider, color = t.textSecondary, fontSize = 12.sp,
                                modifier = Modifier.testTag("panel11-provider:${group.name}:${entry.provider}").padding(vertical = 6.dp))
                            is PanelEntry11.Nodes -> Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                entry.nodes.forEach { node -> key(node.name) {
                                    PanelNode11(group.name, node, node.name == current, snapshot[node.name] ?: node.lastDelay,
                                        testing[node.name] == true, pending == node.name, running && pending == null && !closing,
                                        options.compact, Modifier.weight(1f).fillMaxHeight(), { onSelect(node.name) }, { onDelay(node.name) },
                                        { onName(node.name + "\n" + node.type + if (node.provider.isNotBlank()) "\n提供商：${node.provider}" else "") },
                                        if (node.name in nestedNames && node.name != group.name) ({ onNested(node.name) }) else null)
                                } }
                                if (entry.nodes.size < columns) Spacer(Modifier.weight(1f))
                            }
                            else -> Unit
                        }
                    }
                }
            }
        }
    }
}
