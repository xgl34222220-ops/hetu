package io.github.xgl34222220.hetu
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.draw.shadow
import kotlinx.coroutines.launch
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.animation.Crossfade
import androidx.compose.ui.unit.IntOffset
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.animation.togetherWith
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.animation.AnimatedContent

import io.github.xgl34222220.hetu.ui.HetuHaptic
import io.github.xgl34222220.hetu.ui.rememberHetuHaptics
import android.content.Intent
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.animation.scaleOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material3.HorizontalDivider
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.FilterList
import androidx.compose.material.icons.rounded.Sort
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

/* One lazy item of the strategy grid. Kept as data so the "locate current node" button
 * can compute the exact list index of any row without it being on screen. */
private sealed interface PxEntry {
    val key: String
}

private data class PxGroupRow(val groups: List<ProxyGroupUi>) : PxEntry {
    override val key: String get() = "pg:" + groups.joinToString("|") { it.name }
}

private data class PxPanelHead(val group: ProxyGroupUi, val shown: Int) : PxEntry {
    override val key: String get() = "pn-head:" + group.name
}

private data class PxProviderHead(val group: ProxyGroupUi, val provider: String) : PxEntry {
    override val key: String get() = "pn-provider:" + group.name + ":" + provider
}

private data class PxNodeRow(val group: ProxyGroupUi, val nodes: List<ProxyNodeUi>, val last: Boolean) : PxEntry {
    override val key: String get() = "pn:" + group.name + ":" + nodes.joinToString("|") { it.name }
}

private data class PxPanelFoot(val group: ProxyGroupUi, val empty: Boolean) : PxEntry {
    override val key: String get() = "pn-foot:" + group.name
}

@Composable
internal fun ProxiesScreen(vm: HetuViewModel, bottomPadding: Dp) {
    val context = LocalContext.current
    val c = Hx.colors
    val state = vm.state
    val options = rememberPanelOptions11(vm.prefs)
    val haptics = rememberHetuHaptics()
    val scope = rememberCoroutineScope()
    val embed = LocalHxEmbed.current
    val ownListState = rememberLazyListState()
    val listState = embed?.listState ?: ownListState
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var expandedNames by rememberSaveable { mutableStateOf(listOf<String>()) }
    var activeExpandedName by rememberSaveable { mutableStateOf<String?>(null) }
    var showFilter by remember { mutableStateOf(false) }
    var showLayout by remember { mutableStateOf(false) }
    var showApi by remember { mutableStateOf(false) }
    var nodeInfo by remember { mutableStateOf<Pair<String, String>?>(null) }
    // Layout controls are real: strategy and node grids each honor their own preference.
    val forceSingleColumn = LocalDensity.current.fontScale > 1.3f
    val groupColumns = if (forceSingleColumn) 1 else options.groupColumns.coerceIn(1, 2)
    val nodeColumns = if (forceSingleColumn) 1 else options.columns.coerceIn(1, 2)

    // Filtering selects visible groups/nodes, never the authoritative group stats
    // or the target set for a whole-group latency action.
    val groupsByName = state.groups.associateBy { it.name }
    val visibleGroups = panelGroups11(state.groups, query, state.trafficMode, options)
    val expanded = visibleGroups.firstOrNull { it.name == activeExpandedName && it.name in expandedNames }
        ?: visibleGroups.lastOrNull { it.name in expandedNames }

    // Build the grid: group rows, and under the row holding the expanded group, its node
    // panel (head, node rows, foot) spanning the full width.
    val entries = buildList<PxEntry> {
        visibleGroups.chunked(groupColumns).forEach { row ->
            add(PxGroupRow(row))
            row.filter { it.name in expandedNames }.forEach { expanded ->
                val ordered = projectStrategyNodes(expanded.nodes, options.sort, options.descending, vm.delays)
                val terms = query.trim().split(Regex("\\s+")).filter(String::isNotEmpty)
                val nodes = if (terms.isEmpty() || terms.all { expanded.name.contains(it, true) }) ordered else ordered.filter { node ->
                    terms.all { term -> node.name.contains(term, true) || node.provider.contains(term, true) || node.type.contains(term, true) }
                }
                add(PxPanelHead(expanded, nodes.size))
                val sections = if (options.providers) nodes.groupBy { it.provider.ifBlank { "配置内节点" } }
                    else linkedMapOf("" to nodes)
                sections.entries.forEachIndexed { sectionIndex, (provider, providerNodes) ->
                    if (provider.isNotBlank()) add(PxProviderHead(expanded, provider))
                    val nodeRows = providerNodes.chunked(nodeColumns)
                    nodeRows.forEachIndexed { i, chunk ->
                        add(PxNodeRow(expanded, chunk, sectionIndex == sections.size - 1 && i == nodeRows.lastIndex))
                    }
                }
                add(PxPanelFoot(expanded, nodes.isEmpty()))
            }
        }
    }
    // Lazy index = page header (1) + fixed items emitted before the grid.
    val gridOffset = (if (embed == null) 1 else 0) + (if (searching) 1 else 0)

    fun toggleGroup(group: ProxyGroupUi) {
        haptics.perform(HetuHaptic.Tick)
        val opening = group.name !in expandedNames
        expandedNames = panelExpanded11(expandedNames.toSet(), group.name, options.collapsePrevious).toList()
        activeExpandedName = if (opening) group.name else expandedNames.lastOrNull()
        if (opening) {
            val rowIndex = entries.indexOfFirst { it is PxGroupRow && it.groups.any { g -> g.name == group.name } }
            if (rowIndex >= 0) scope.launch {
                kotlinx.coroutines.delay(60)
                val info = listState.layoutInfo
                val item = info.visibleItemsInfo.firstOrNull { it.index == gridOffset + rowIndex }
                // Only move when the tapped card sits in the lower half; never yank the page otherwise.
                if (item == null || item.offset > info.viewportEndOffset * .45f) {
                    listState.animateScrollToItem(gridOffset + rowIndex)
                }
            }
        }
    }

    fun locateCurrent() {
        val group = expanded ?: return
        val index = entries.indexOfFirst { it is PxNodeRow && it.group.name == group.name && it.nodes.any { n -> n.name == group.now } }
        haptics.perform(HetuHaptic.Tick)
        if (index >= 0) scope.launch { listState.animateScrollToItem(gridOffset + index) }
        else vm.toast("当前节点不在筛选结果中")
    }

    val panelVisible by remember(listState) {
        derivedStateOf { listState.layoutInfo.visibleItemsInfo.any { (it.key as? String)?.startsWith("pn") == true } }
    }

    HxPage(
        title = "代理",
        scrollToTopSignal = vm.reselect,
        bottomPadding = bottomPadding,
        listState = listState,
        showScrollTop = expanded == null,
        leadingActions = {
            HxBarAction(if (searching) Icons.Rounded.Close else Icons.Rounded.Search, "搜索", onClick = {
                    searching = !searching
                    if (!searching) query = ""
                })
            if (state.running) Box(Modifier.hxAnchorSource()) { HxBarAction(Icons.Rounded.FilterList, "筛选", onClick = { showFilter = true }) }
        },
        actions = {
            if (state.running) HxBarAction(Icons.Rounded.Sort, "排序与布局", onClick = { showLayout = true })
        },
        overlay = {
            AnimatedVisibility(
                visible = expanded != null && panelVisible,
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = bottomPadding + 32.dp),
                enter = fadeIn(tween(HxMotion.Short)) + scaleIn(HxMotion.pop(), initialScale = .7f, transformOrigin = androidx.compose.ui.graphics.TransformOrigin(1f, 1f)),
                exit = fadeOut(tween(HxMotion.Short)) + scaleOut(tween(HxMotion.Short), targetScale = .7f, transformOrigin = androidx.compose.ui.graphics.TransformOrigin(1f, 1f)),
            ) {
                PanelFloatingControls(
                    groupName = expanded?.name.orEmpty(),
                    onLocate = ::locateCurrent,
                    onCollapse = {
                        haptics.perform(HetuHaptic.Tick)
                        val name = expanded?.name
                        expandedNames = expandedNames.filterNot { it == name }
                        activeExpandedName = expandedNames.lastOrNull()
                        val rowIndex = entries.indexOfFirst { it is PxGroupRow && it.groups.any { g -> g.name == name } }
                        if (rowIndex >= 0) scope.launch { listState.animateScrollToItem(gridOffset + rowIndex) }
                    },
                )
            }
        },
    ) {
        if (!state.running) {
            item(key = "stopped") {
                PanelReferenceEmpty(Icons.Rounded.Dns, "代理未运行", "启动代理后可查看策略与节点", bottomPadding)
            }
            return@HxPage
        }

        if (searching) {
            item(key = "search") {
                HxSearchField(
                    query,
                    { query = it },
                    "搜索策略组、节点或订阅",
                    Modifier.animateItem().padding(horizontal = Hx.gutter).padding(bottom = 10.dp),
                    autoFocus = true,
                )
            }
        }


        if (visibleGroups.isEmpty()) {
            item(key = "empty") {
                if (!state.panelReady && state.groups.isEmpty()) HxSkeletonRows(6)
                else HxEmpty(Icons.Rounded.SearchOff, if (query.isBlank()) "没有策略组" else "没有匹配结果")
            }
            return@HxPage
        }

        entries.forEach { entry ->
            val type = when (entry) {
                is PxGroupRow -> "group-row"
                is PxPanelHead -> "panel-head"
                is PxNodeRow -> "node-row"
                is PxProviderHead -> "provider-head"
                is PxPanelFoot -> "panel-foot"
            }
            item(key = entry.key, contentType = type) {
                val motion = Modifier.animateItem(
                    fadeInSpec = tween(HxMotion.Medium),
                    placementSpec = HxMotion.glide(),
                    fadeOutSpec = tween(HxMotion.Short),
                )
                when (entry) {
                    is PxGroupRow -> Row(
                        motion
                            .fillMaxWidth()
                            .padding(horizontal = Hx.gutter)
                            .padding(bottom = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        val groupRatio = when {
                            groupColumns == 1 && options.groupCompact -> 4.4f
                            groupColumns == 1 -> 3.7f
                            options.groupCompact -> 1.94f
                            else -> 1.82f
                        }
                        entry.groups.forEach { group ->
                            StrategyGroupCard(
                                vm = vm,
                                group = groupsByName[group.name] ?: group,
                                expanded = group.name in expandedNames,
                                compact = options.groupCompact,
                                nameOverflow = options.nameOverflow,
                                modifier = Modifier.weight(1f).aspectRatio(groupRatio),
                                onClick = { toggleGroup(group) },
                            )
                        }
                        repeat(groupColumns - entry.groups.size) { Spacer(Modifier.weight(1f)) }
                    }
                    is PxPanelHead -> PanelHead(vm, entry.group, entry.shown, motion)
                    is PxProviderHead -> Text(
                        entry.provider,
                        style = MaterialTheme.typography.labelMedium,
                        color = c.textMuted,
                        modifier = motion.fillMaxWidth().padding(horizontal = Hx.gutter)
                            .background(c.surface).padding(horizontal = 14.dp, vertical = 8.dp),
                    )
                    is PxNodeRow -> Row(
                        motion
                            .fillMaxWidth()
                            .height(IntrinsicSize.Min)
                            .padding(horizontal = Hx.gutter)
                            .clip(if (entry.last) RoundedCornerShape(bottomStart = 18.dp, bottomEnd = 18.dp) else RoundedCornerShape(0.dp))
                            .background(c.surface)
                            .padding(horizontal = 12.dp)
                            .padding(bottom = if (entry.last) 12.dp else 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        entry.nodes.forEach { node ->
                            StrategyNodeCard(
                                vm = vm,
                                group = entry.group,
                                node = node,
                                compact = options.compact,
                                nameOverflow = options.nameOverflow,
                                modifier = Modifier.weight(1f),
                                onInfo = { nodeInfo = entry.group.name to node.name },
                            )
                        }
                        repeat(nodeColumns - entry.nodes.size) { Spacer(Modifier.weight(1f)) }
                    }
                    is PxPanelFoot -> if (entry.empty) {
                        Box(
                            motion
                                .fillMaxWidth()
                                .padding(horizontal = Hx.gutter)
                                .padding(bottom = 14.dp)
                                .clip(RoundedCornerShape(bottomStart = 24.dp, bottomEnd = 24.dp))
                                .background(c.surface)
                                .height(64.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("没有匹配的节点", style = MaterialTheme.typography.bodySmall, color = c.textMuted)
                        }
                    } else {
                        Spacer(motion.fillMaxWidth().height(14.dp))
                    }
                }
            }
        }
    }

    if (showFilter) {
        StrategyFilterMenu(
            options = options,
            onChange = { it.save(vm.prefs) },
            onDismiss = { showFilter = false },
        )
    }

    if (showLayout) {
        StrategyLayoutSheet(
            vm = vm,
            options = options,
            onDismiss = { showLayout = false },
            onOpenApi = {
                showLayout = false
                showApi = true
            },
        )
    }

    val infoPair = nodeInfo
    if (infoPair != null) {
        val infoGroup = state.groups.firstOrNull { it.name == infoPair.first }
        val infoNode = infoGroup?.nodes?.firstOrNull { it.name == infoPair.second }
        if (infoGroup == null || infoNode == null) {
            LaunchedEffect(infoPair) { nodeInfo = null }
        } else {
            StrategyNodeInfoSheet(infoGroup, infoNode, onDismiss = { nodeInfo = null })
        }
    }

    if (showApi) ApiSettingsSheet(vm, onDismiss = { showApi = false })
}


/** Strategy group tile: name, type + reachable/total, big icon, current node and latency. */
@Composable
private fun StrategyGroupCard(
    vm: HetuViewModel,
    group: ProxyGroupUi,
    expanded: Boolean,
    compact: Boolean,
    nameOverflow: String,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val haptics = rememberHetuHaptics()
    val currentNode = group.nodes.firstOrNull { it.name == group.now }
    val delay = vm.delays[group.now] ?: currentNode?.lastDelay
    val testing = vm.testingGroups[group.name] == true
    val online = group.nodes.count { (vm.delays[it.name] ?: it.lastDelay ?: 0L) > 0L }

    val c = Hx.colors
    val source = remember { MutableInteractionSource() }
    Column(
        modifier.hxPressScale(source, .97f).clip(RoundedCornerShape(18.dp)).background(c.surface)
            .hxCombinedClickSource(source = source,
                onLongClick = { haptics.perform(HetuHaptic.LongPress); vm.testGroup(group) },
                onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(group.name, fontSize = 16.sp, lineHeight = 19.sp, fontWeight = FontWeight.SemiBold,
                    color = c.text, maxLines = if (nameOverflow == "wrap" && !compact) 2 else 1, overflow = TextOverflow.Ellipsis)
                Text("${group.type.ifBlank { "Selector" }}  $online/${group.nodes.size}",
                    fontSize = 13.sp, lineHeight = 16.sp, color = c.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(5.dp))
            ConfiguredGroupIcon(group, Modifier.size(34.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            val flag = hxNameFlag(group.now)
            if (flag != null) {
                Text(flag, fontSize = 16.sp, lineHeight = 18.sp)
                Spacer(Modifier.width(4.dp))
            }
            Text(group.now.removePrefix(flag.orEmpty()).trim().ifBlank { "未选择" },
                fontSize = 13.5.sp, lineHeight = 17.sp, color = c.text, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(5.dp))
            StrategyCompactDelayPill(delay, testing) { vm.testGroup(group) }
        }
    }
}

/** Top of the expanded node panel: count, sort hint and a per-group latency test. */
@Composable
private fun PanelHead(vm: HetuViewModel, group: ProxyGroupUi, shown: Int, modifier: Modifier) {
    val c = Hx.colors
    // Reference layout: the expanded node grid grows directly under the group cards.
    // Keep only a slim rounded cap so the white panel reads as one surface instead of
    // inserting a second toolbar/header between the group and its nodes.
    Box(
        modifier
            .fillMaxWidth()
            .padding(horizontal = Hx.gutter)
            .clip(RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp))
            .background(c.surface)
            .height(10.dp),
    )
}

/** A node tile inside the expanded panel. Tap to switch; the selected tile is outlined. */
@Composable
private fun StrategyNodeCard(
    vm: HetuViewModel,
    group: ProxyGroupUi,
    node: ProxyNodeUi,
    compact: Boolean,
    nameOverflow: String,
    modifier: Modifier,
    onInfo: () -> Unit,
) {
    val c = Hx.colors
    val haptics = rememberHetuHaptics()
    val source = remember { MutableInteractionSource() }
    val selectable = HxFormat.isSelectable(group.type)
    val pending = vm.pendingSelection[group.name]
    val selected = group.now == node.name
    val pendingThis = pending == node.name
    val delay = vm.delays[node.name] ?: node.lastDelay
    val testing = vm.testingNodes[node.name] == true
    val shape = RoundedCornerShape(15.dp)
    val selectedLight = Color(0xFFE5ECFF)
    val selectedDark = androidx.compose.ui.graphics.lerp(c.surfaceMuted, c.accentSoft, .42f)
    val idleLight = Color(0xFFE7E8F3)
    val bg by animateColorAsState(
        if (selected) { if (c.dark) selectedDark else selectedLight }
        else { if (c.dark) c.surfaceMuted else idleLight },
        tween(HxMotion.Medium),
        label = "nodeCardBg",
    )
    val border by animateColorAsState(
        if (selected) { if (c.dark) c.accent.copy(alpha = .55f) else Color(0xFF72A5FF) } else Color.Transparent,
        tween(HxMotion.Medium),
        label = "nodeCardBorder",
    )

    Column(
        modifier
            .heightIn(min = if (compact) 78.dp else 90.dp)
            .hxPressScale(source, .965f)
            .clip(shape)
            .background(bg)
            .border(1.dp, border, shape)
            .hxCombinedClickSource(
                source = source,
                enabled = pending == null,
                onLongClick = {
                    haptics.perform(HetuHaptic.LongPress)
                    onInfo()
                },
                onClick = {
                    if (selectable && !selected) {
                        haptics.perform(HetuHaptic.Confirm)
                        vm.select(group.name, node.name)
                    }
                },
            )
            .padding(horizontal = 10.dp, vertical = if (compact) 8.dp else 10.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                node.name,
                fontSize = 14.sp,
                lineHeight = 17.sp,
                fontWeight = FontWeight.SemiBold,
                color = c.text,
                maxLines = if (nameOverflow == "wrap" && !compact) 2 else 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (pendingThis) {
                Spacer(Modifier.width(4.dp))
                HxSpinner(11.dp)
            } else if (selected) {
                Spacer(Modifier.width(4.dp))
                Icon(Icons.Rounded.CheckCircle, "已选择", tint = c.accent, modifier = Modifier.size(16.dp))
            }
        }
        Text(
            if (node.udp) "UDP" else "",
            fontSize = 10.5.sp,
            lineHeight = 11.5.sp,
            fontWeight = FontWeight.Medium,
            color = c.textMuted,
            maxLines = 1,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                node.type.uppercase(Locale.ROOT),
                fontSize = 10.5.sp,
                lineHeight = 12.sp,
                fontWeight = FontWeight.Medium,
                color = c.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            StrategyCompactDelayPill(delay, testing) { vm.testNode(node.name) }
        }
    }
}

@Composable
private fun StrategyCompactDelayPill(delay: Long?, testing: Boolean, onClick: () -> Unit) {
    val c = Hx.colors
    val timeout = !testing && delay != null && delay < 0L
    val unknown = !testing && (delay == null || delay == 0L)
    val bg = when {
        testing -> c.accentSoft
        timeout -> c.badSoft
        unknown -> c.surface
        delay != null && delay < 800L -> c.accentSoft
        else -> c.warnSoft
    }
    val fg = when {
        testing -> c.accent
        timeout -> c.bad
        unknown -> c.textFaint
        delay != null && delay < 800L -> c.accent
        else -> c.warn
    }
    val source = remember { MutableInteractionSource() }
    Row(
        Modifier
            .hxPressScale(source, .92f)
            .clip(Hx.pillShape)
            .background(bg)
            .clickable(interactionSource = source, indication = null, enabled = !testing, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (testing) HxSpinner(9.dp)
        Text(
            when {
                testing -> "测速中"
                timeout -> "超时"
                unknown -> "未知"
                else -> "$delay ms"
            },
            fontSize = 12.sp,
            lineHeight = 15.sp,
            fontWeight = FontWeight.SemiBold,
            color = fg,
            maxLines = 1,
        )
    }
}

/** Floating controls while a node panel is open: jump to the selected node, or fold the panel. */
@Composable
private fun PanelFloatingControls(groupName: String, onLocate: () -> Unit, onCollapse: () -> Unit) {
    val c = Hx.colors
    val collapseSource = remember { MutableInteractionSource() }
    val locateSource = remember { MutableInteractionSource() }
    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(
            Modifier.size(56.dp).hxPressScale(locateSource, .94f)
                .shadow(7.dp, CircleShape, clip = false).clip(CircleShape).background(c.accent)
                .clickable(interactionSource = locateSource, indication = LocalIndication.current, onClick = onLocate),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Rounded.MyLocation, "定位当前节点", tint = c.onAccent, modifier = Modifier.size(27.dp)) }
        Row(
            Modifier.hxPressScale(collapseSource, .95f).height(56.dp).widthIn(max = 180.dp)
                .shadow(7.dp, Hx.pillShape, clip = false, ambientColor = c.accent.copy(alpha = .22f), spotColor = c.accent.copy(alpha = .28f))
                .clip(Hx.pillShape).background(c.accent)
                .clickable(interactionSource = collapseSource, indication = LocalIndication.current, onClick = onCollapse)
                .padding(start = 17.dp, end = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.ExpandMore, "收起", tint = c.onAccent, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(groupName, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold,
                color = c.onAccent, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun StrategyNodeSheet(
    vm: HetuViewModel,
    group: ProxyGroupUi,
    options: PanelOptions11,
    onDismiss: () -> Unit,
) {
    val c = Hx.colors
    var nodeQuery by rememberSaveable(group.name) { mutableStateOf("") }
    val initialNode = remember(group.name) { group.now }
    val pending = vm.pendingSelection[group.name]
    val ordered = projectStrategyNodes(group.nodes, options.sort, options.descending, vm.delays)
    val nodes = if (nodeQuery.isBlank()) ordered else ordered.filter {
        it.name.contains(nodeQuery, true) || it.provider.contains(nodeQuery, true) || it.type.contains(nodeQuery, true)
    }

    val listState = rememberLazyListState()
    LaunchedEffect(group.name) {
        // Open with the current node in view instead of always at the top.
        val index = nodes.indexOfFirst { it.name == group.now }
        if (index > 2) listState.scrollToItem((index - 2).coerceAtLeast(0))
    }

    HxSheet(onDismiss = onDismiss) {
        val close = LocalHxSheetClose.current
        LaunchedEffect(group.now, pending) {
            // A successful switch: let the check mark land, then glide the sheet away.
            if (pending == null && group.now.isNotBlank() && group.now != initialNode) {
                kotlinx.coroutines.delay(260)
                close(onDismiss)
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(group.name, style = MaterialTheme.typography.titleLarge, color = c.text, fontWeight = FontWeight.SemiBold)
                Text(
                    HxFormat.groupType(group.type) + " · " + group.nodes.size + " 个节点",
                    style = MaterialTheme.typography.bodySmall,
                    color = c.textMuted,
                )
            }
            if (vm.testingGroups[group.name] == true) Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) { HxSpinner(17.dp) }
            else HxBarAction(Icons.Rounded.Speed, "测速本组", onClick = { vm.testGroup(group) })
        }

        if (group.nodes.size > 8) {
            HxSearchField(
                nodeQuery,
                { nodeQuery = it },
                "搜索节点",
                Modifier.padding(horizontal = 14.dp, vertical = 4.dp),
            )
            Spacer(Modifier.height(4.dp))
        }

        Surface(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp),
            shape = RoundedCornerShape(16.dp),
            color = c.surface,
            border = BorderStroke(0.5.dp, c.line.copy(alpha = if (c.dark) .72f else .42f)),
            shadowElevation = 0.dp,
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxWidth().heightIn(min = 240.dp, max = 560.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 2.dp),
            ) {
                items(
                    items = nodes,
                    key = { node -> "sheet-node:" + group.name + ":" + node.name },
                ) { node ->
                    StrategySheetNodeRow(
                        vm = vm,
                        group = group,
                        node = node,
                        modifier = Modifier.animateItem(
                            fadeInSpec = spring<Float>(stiffness = Spring.StiffnessMediumLow),
                            placementSpec = spring<IntOffset>(dampingRatio = .82f, stiffness = 420f),
                            fadeOutSpec = spring<Float>(stiffness = Spring.StiffnessMediumLow),
                        ),
                    )
                }
            }
        }
    }
}

@Composable
private fun StrategySheetNodeRow(
    vm: HetuViewModel,
    group: ProxyGroupUi,
    node: ProxyNodeUi,
    modifier: Modifier = Modifier,
) {
    val c = Hx.colors
    val selectable = HxFormat.isSelectable(group.type)
    val pending = vm.pendingSelection[group.name]
    val selected = group.now == node.name
    val pendingThis = pending == node.name
    val delay = vm.delays[node.name] ?: node.lastDelay
    val testing = vm.testingNodes[node.name] == true
    val haptics = rememberHetuHaptics()
    val rowBg by animateColorAsState(
        if (selected) c.accentSoft.copy(alpha = if (c.dark) .28f else .40f) else Color.Transparent,
        tween(HxMotion.Medium),
        label = "nodeBg",
    )
    val checkScale by animateFloatAsState(if (selected) 1f else 0f, HxMotion.pop(), label = "nodeCheck")

    Column(
        modifier
            .fillMaxWidth()
            .background(rowBg)
            .clickable(enabled = selectable && pending == null && !selected) {
                haptics.perform(HetuHaptic.Tap)
                vm.select(group.name, node.name)
            },
    ) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 54.dp).padding(horizontal = 13.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.width(22.dp), contentAlignment = Alignment.CenterStart) {
                if (pendingThis) HxSpinner(12.dp)
                else Icon(
                    Icons.Rounded.Check,
                    null,
                    tint = c.accent,
                    modifier = Modifier.size(17.dp).graphicsLayer {
                        scaleX = checkScale
                        scaleY = checkScale
                        alpha = checkScale.coerceIn(0f, 1f)
                    },
                )
            }
            Column(Modifier.weight(1f)) {
                Text(
                    node.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    color = c.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    listOf(node.type.uppercase(Locale.ROOT), node.provider).filter { it.isNotBlank() }.joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = c.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            HxDelayPill(delay, testing) { vm.testNode(node.name) }
        }
        HxDivider(35.dp)
    }
}

/** 测速 URL、Clash API 后端与历史采集 — the same preferences the backend client reads. */
@Composable
internal fun ApiSettingsSheet(vm: HetuViewModel, onDismiss: () -> Unit) {
    val c = Hx.colors
    var draft by remember { mutableStateOf(PanelApiDraft11.read(vm.prefs)) }
    var error by remember { mutableStateOf<String?>(null) }

    HxSheet(onDismiss = onDismiss, containerColor = c.canvas) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(Modifier.padding(horizontal = 6.dp, vertical = 2.dp)) {
                Text(
                    "测速与 API",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = c.text,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    "测速、历史采集与控制器连接",
                    style = MaterialTheme.typography.bodySmall,
                    color = c.textMuted,
                )
            }

            ApiSettingCard(
                icon = Icons.Rounded.Speed,
                title = "测速地址",
                subtitle = if (draft.customDelay) "正在使用自定义测速 URL" else "跟随订阅或策略组自带地址",
                checked = draft.customDelay,
                onCheckedChange = { draft = draft.copy(customDelay = it); error = null },
            ) {
                if (draft.customDelay) {
                    ApiInlineField(
                        label = "测速 URL",
                        value = draft.delayUrl,
                        onValueChange = { draft = draft.copy(delayUrl = it); error = null },
                    )
                }
            }

            ApiSettingCard(
                icon = Icons.Rounded.Hub,
                title = "流量与连接历史",
                subtitle = "保存最近 24 小时排行所需的数据",
                checked = draft.history,
                onCheckedChange = { draft = draft.copy(history = it); error = null },
            )

            ApiSettingCard(
                icon = Icons.Rounded.Settings,
                title = "外部 Clash API",
                subtitle = if (draft.customApi) "使用自定义控制器" else "内置 127.0.0.1:${vm.state.controllerPort}",
                checked = draft.customApi,
                onCheckedChange = { draft = draft.copy(customApi = it); error = null },
            ) {
                if (draft.customApi) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ApiInlineField(
                            label = "主机",
                            inlineLabel = true,
                            value = draft.host,
                            onValueChange = { draft = draft.copy(host = it); error = null },
                            modifier = Modifier.weight(1f),
                        )
                        ApiInlineField(
                            label = "端口",
                            inlineLabel = true,
                            value = draft.port,
                            onValueChange = { draft = draft.copy(port = it.filter(Char::isDigit).take(5)); error = null },
                            modifier = Modifier.width(104.dp),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    ApiInlineField(
                        label = "密钥",
                        inlineLabel = true,
                        value = draft.secret,
                        onValueChange = { draft = draft.copy(secret = it); error = null },
                        placeholder = "未设置",
                    )
                }
            }

            AnimatedVisibility(
                visible = error != null,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                error?.let {
                    Text(
                        it,
                        color = c.bad,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 8.dp),
                    )
                }
            }

            Row(
                Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                HxButton(
                    "取消",
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                    tone = if (draft.customDelay || draft.customApi) HxTone.Accent else HxTone.Neutral,
                    filled = !draft.customDelay && !draft.customApi,
                )
                HxButton(
                    "保存",
                    onClick = {
                        val problem = draft.validationError()
                        if (problem != null) error = problem else {
                            draft.save(vm.prefs)
                            vm.toast("已保存")
                            onDismiss()
                        }
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun ApiSettingCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    content: (@Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit)? = null,
) {
    val c = Hx.colors
    val background by animateColorAsState(
        if (checked) c.surface else c.surfaceMuted,
        tween(HxMotion.Medium),
        label = "apiCardBg",
    )
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = background,
        shape = RoundedCornerShape(15.dp),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        title,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = c.text,
                        maxLines = 1,
                    )
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = c.textMuted,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.width(8.dp))
                HxSwitch(checked = checked, onChange = onCheckedChange)
            }
            AnimatedVisibility(
                visible = checked && content != null,
                enter = fadeIn(tween(HxMotion.Short)) + expandVertically(tween(HxMotion.Medium)),
                exit = fadeOut(tween(HxMotion.Short)) + shrinkVertically(tween(HxMotion.Medium)),
            ) {
                Column(Modifier.padding(top = 10.dp)) { content?.invoke(this) }
            }
        }
    }
}

@Composable
private fun ApiInlineField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    inlineLabel: Boolean = false,
) {
    val c = Hx.colors
    val input: @Composable (Modifier) -> Unit = { inputModifier ->
        BasicTextField(
            value = value, onValueChange = onValueChange,
            modifier = inputModifier.clip(RoundedCornerShape(10.dp))
                .background(c.surface).border(.8.dp, c.line, RoundedCornerShape(10.dp))
                .padding(horizontal = 12.dp, vertical = 9.dp),
            textStyle = MaterialTheme.typography.bodySmall.copy(color = c.text), singleLine = true,
            keyboardOptions = keyboardOptions, cursorBrush = SolidColor(c.accent),
            decorationBox = { inner ->
                Box(Modifier.fillMaxWidth()) {
                    if (value.isBlank() && placeholder.isNotBlank()) Text(placeholder,
                        style = MaterialTheme.typography.bodySmall, color = c.textFaint)
                    inner()
                }
            },
        )
    }
    if (inlineLabel) {
        Row(modifier, verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodySmall, color = c.textMuted,
                modifier = Modifier.width(if (label == "密钥") 46.dp else 38.dp))
            input(Modifier.weight(1f))
        }
    } else {
        Column(modifier) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = c.textMuted)
            Spacer(Modifier.height(4.dp))
            input(Modifier.fillMaxWidth())
        }
    }
}

/** Empty states occupy the free panel viewport, as in the concept's stopped/search pages. */
@Composable
internal fun PanelReferenceEmpty(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    description: String? = null,
    bottomPadding: Dp = 0.dp,
) {
    val c = Hx.colors
    val top = LocalHxEmbed.current?.top ?: 120.dp
    val available = (LocalConfiguration.current.screenHeightDp.dp - top - bottomPadding - 24.dp).coerceAtLeast(280.dp)
    Box(Modifier.fillMaxWidth().heightIn(min = available).padding(horizontal = 24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, null, tint = c.textFaint, modifier = Modifier.size(56.dp))
            Spacer(Modifier.height(20.dp))
            Text(title, fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold, color = c.text,
                textAlign = TextAlign.Center)
            if (!description.isNullOrBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(description, style = MaterialTheme.typography.bodyMedium, color = c.textMuted, textAlign = TextAlign.Center)
            }
        }
    }
}

/** Fixed-size latency indicator. Crossfade keeps spinner and text from deforming. */
@Composable
internal fun HxDelayPill(delay: Long?, testing: Boolean, onClick: () -> Unit) {
    val c = Hx.colors
    val bg by animateColorAsState(
        when {
            testing || delay == null || delay == 0L -> c.surfaceMuted
            delay < 0L -> c.badSoft
            delay < 800L -> c.accentSoft
            else -> c.warnSoft
        },
        tween(HxMotion.Medium),
        label = "delayBg",
    )
    val fg by animateColorAsState(
        when {
            testing || delay == null || delay == 0L -> c.textFaint
            delay < 0L -> c.bad
            delay < 800L -> c.accent
            else -> c.warn
        },
        tween(HxMotion.Medium),
        label = "delayFg",
    )
    val haptics = rememberHetuHaptics()
    val source = remember { MutableInteractionSource() }

    Box(
        Modifier
            .hxPressScale(source, .9f)
            .width(66.dp)
            .height(26.dp)
            .clip(Hx.pillShape)
            .background(bg)
            .clickable(interactionSource = source, indication = LocalIndication.current, enabled = !testing) {
                haptics.perform(HetuHaptic.Tick)
                onClick()
            },
        contentAlignment = Alignment.Center,
    ) {
        // A fresh result pops in (slightly scaled) so the eye catches which values changed.
        AnimatedContent(
            targetState = if (testing) null else (delay ?: 0L),
            transitionSpec = {
                (fadeIn(tween(HxMotion.Short)) + scaleIn(HxMotion.pop(), initialScale = .72f))
                    .togetherWith(fadeOut(tween(100)))
            },
            contentAlignment = Alignment.Center,
            label = "latency-value",
        ) { value ->
            val isTesting = value == null
            if (isTesting) {
                Box(Modifier.size(16.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(13.dp),
                        strokeWidth = 2.dp,
                        color = c.accent,
                    )
                }
            } else {
                Text(
                    when {
                        value != null && value > 0 -> value.toString() + " ms"
                        value == -1L -> "超时"
                        value != null && value < 0 -> "失败"
                        else -> "---"
                    },
                    style = MaterialTheme.typography.labelSmall.merge(HxNumberStyle),
                    fontWeight = FontWeight.SemiBold,
                    color = fg,
                    maxLines = 1,
                )
            }
        }
    }
}
