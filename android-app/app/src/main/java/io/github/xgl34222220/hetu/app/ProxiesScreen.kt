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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.selected
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
    override val key: String get() = "pn-provider:${group.name.length}:${group.name}:$provider"
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
    var expandedNames by rememberSaveable { mutableStateOf(emptyList<String>()) }
    val expandedName = expandedNames.lastOrNull()
    var showFilter by remember { mutableStateOf(false) }
    var showApi by remember { mutableStateOf(false) }
    // Layout controls are real: strategy and node grids each honor their own preference.
    val forceSingleColumn = LocalDensity.current.fontScale > 1.3f
    val groupColumns = if (forceSingleColumn) 1 else options.groupColumns.coerceIn(1, 2)
    val nodeColumns = if (forceSingleColumn) 1 else options.columns.coerceIn(1, 2)

    val visibleGroups = panelGroups11(state.groups, query, state.trafficMode, options)
    val expanded = visibleGroups.firstOrNull { it.name == expandedName }

    // Build the grid: group rows, and under the row holding the expanded group, its node
    // panel (head, node rows, foot) spanning the full width.
    val entries = buildList<PxEntry> {
        visibleGroups.chunked(groupColumns).forEach { row ->
            add(PxGroupRow(row))
            row.filter { it.name in expandedNames }.forEach { expanded ->
                val ordered = projectStrategyNodes(expanded.nodes, options.sort, options.descending, vm.delays)
                val q = query.trim()
                val nodes = if (q.isBlank() || expanded.name.contains(q, true)) ordered else ordered.filter {
                    it.name.contains(q, true) || it.provider.contains(q, true) || it.type.contains(q, true)
                }
                add(PxPanelHead(expanded, nodes.size))
                val providerGroups = if (options.providers) nodes.groupBy { it.provider.ifBlank { "配置内节点" } } else linkedMapOf("" to nodes)
                providerGroups.entries.forEachIndexed { sectionIndex, (provider, members) ->
                    if (provider.isNotBlank()) add(PxProviderHead(expanded, provider))
                    val nodeRows = members.chunked(nodeColumns)
                    nodeRows.forEachIndexed { i, chunk -> add(PxNodeRow(expanded, chunk, sectionIndex == providerGroups.size - 1 && i == nodeRows.lastIndex)) }
                }
                add(PxPanelFoot(expanded, nodes.isEmpty()))
            }
        }
    }
    // Lazy index = page header (1) + fixed items emitted before the grid.
    val gridOffset = (if (embed == null) 1 else 0) + 1 + (if (searching) 1 else 0)

    fun toggleGroup(group: ProxyGroupUi) {
        haptics.perform(HetuHaptic.Tick)
        val opening = group.name !in expandedNames
        expandedNames = if (!opening) expandedNames - group.name else if (options.collapsePrevious) listOf(group.name) else expandedNames + group.name
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
            if (state.running) {
                HxBarAction(if (searching) Icons.Rounded.SearchOff else Icons.Rounded.Search, "搜索", onClick = {
                    searching = !searching
                    if (!searching) query = ""
                })
                Box(Modifier.hxAnchorSource()) { HxBarAction(Icons.Rounded.FilterList, "筛选", onClick = { showFilter = true }) }
            }
        },
        actions = {
            if (state.running) BoxProxyMiuixTheme17 { ReferenceStrategyMenu(vm.prefs) }
            HxBarAction(Icons.Rounded.Settings, "测速与 API", onClick = { showApi = true })
        },
        overlay = {
            AnimatedVisibility(
                visible = expanded != null && panelVisible,
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = bottomPadding + 6.dp),
                enter = fadeIn(tween(HxMotion.Short)) + scaleIn(HxMotion.pop(), initialScale = .7f, transformOrigin = androidx.compose.ui.graphics.TransformOrigin(1f, 1f)),
                exit = fadeOut(tween(HxMotion.Short)) + scaleOut(tween(HxMotion.Short), targetScale = .7f, transformOrigin = androidx.compose.ui.graphics.TransformOrigin(1f, 1f)),
            ) {
                PanelFloatingControls(
                    groupName = expanded?.name.orEmpty(),
                    onLocate = ::locateCurrent,
                    onCollapse = {
                        haptics.perform(HetuHaptic.Tick)
                        val name = expandedName
                        expandedNames = expandedNames.filter { it != expandedName }
                        val rowIndex = entries.indexOfFirst { it is PxGroupRow && it.groups.any { g -> g.name == name } }
                        if (rowIndex >= 0) scope.launch { listState.animateScrollToItem(gridOffset + rowIndex) }
                    },
                )
            }
        },
    ) {
        if (!state.running) {
            item(key = "stopped") {
                HxEmpty(Icons.Rounded.Hub, "代理未运行", "启动后可查看策略组、切换节点和测速") {
                    HxButton("启动代理", onClick = vm::toggle, icon = Icons.Rounded.PowerSettingsNew, busy = vm.operation != null)
                }
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

        item(key = "strategy-summary") {
            Row(Modifier.fillMaxWidth().padding(horizontal = Hx.gutter + 4.dp).padding(top = 4.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("策略分组", style = MaterialTheme.typography.titleLarge, color = c.text, modifier = Modifier.weight(1f))
                Text("共 ${visibleGroups.size} 个分组", style = MaterialTheme.typography.bodySmall, color = c.textMuted)
            }
        }
        entries.forEach { entry ->
            val type = when (entry) {
                is PxGroupRow -> "group-row"
                is PxPanelHead -> "panel-head"
                is PxProviderHead -> "provider-head"
                is PxNodeRow -> "node-row"
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
                        entry.groups.forEach { group ->
                            StrategyGroupCard(
                                vm = vm,
                                group = group,
                                expanded = group.name in expandedNames,
                                compact = options.groupCompact,
                                nameOverflow = options.nameOverflow,
                                modifier = Modifier.weight(1f),
                                onClick = { toggleGroup(group) },
                            )
                        }
                        repeat(groupColumns - entry.groups.size) { Spacer(Modifier.weight(1f)) }
                    }
                    is PxPanelHead -> PanelHead(vm, entry.group, entry.shown, motion) { expandedNames = expandedNames - entry.group.name }
                    is PxProviderHead -> Text(entry.provider, style = MaterialTheme.typography.labelLarge, color = c.textMuted,
                        modifier = motion.fillMaxWidth().padding(horizontal = Hx.gutter).background(c.surface).padding(horizontal = 14.dp, vertical = 8.dp))
                    is PxNodeRow -> Row(
                        motion
                            .fillMaxWidth()
                            .height(IntrinsicSize.Min)
                            .padding(horizontal = Hx.gutter)
                            .clip(if (entry.last) RoundedCornerShape(bottomStart = 24.dp, bottomEnd = 24.dp) else RoundedCornerShape(0.dp))
                            .background(c.surface)
                            .padding(horizontal = 9.dp)
                            .padding(bottom = if (entry.last) 9.dp else 7.dp),
                        horizontalArrangement = Arrangement.spacedBy(7.dp),
                    ) {
                        entry.nodes.forEach { node ->
                            StrategyNodeCard(
                                vm = vm,
                                group = entry.group,
                                node = node,
                                compact = options.compact,
                                nameOverflow = options.nameOverflow,
                                modifier = Modifier.weight(1f),
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
        HxActionMenu(
            title = "策略筛选",
            actions = listOf(
                HxMenuAction((if (options.showHidden) "✓ " else "") + "显示隐藏策略") { options.copy(showHidden = !options.showHidden).save(vm.prefs); showFilter = false },
                HxMenuAction((if (options.globalByMode) "✓ " else "") + "根据模式显示 GLOBAL") { options.copy(globalByMode = !options.globalByMode).save(vm.prefs); showFilter = false },
                HxMenuAction((if (options.providers) "✓ " else "") + "按订阅分组节点") { options.copy(providers = !options.providers).save(vm.prefs); showFilter = false },
                HxMenuAction((if (options.collapsePrevious) "✓ " else "") + "展开新策略时折叠上一个") { options.copy(collapsePrevious = !options.collapsePrevious).save(vm.prefs); showFilter = false },
            ),
            onDismiss = { showFilter = false },
        )
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
    val shape = RoundedCornerShape(20.dp)
    val fill by animateColorAsState(if (expanded) c.accentSoft else c.surface.copy(alpha = .84f), HxMotion.enter(), label = "groupFill")
    Column(
        modifier.heightIn(min = if (compact) 112.dp else 124.dp)
            .testTag("strategy-group-${group.name}")
            .semantics { stateDescription = if (expanded) "已展开" else "已收起" }
            .hxPressScale(source).clip(shape).background(fill)
            .border(if (expanded) 1.dp else .8.dp, if (expanded) c.accent.copy(alpha = .6f) else c.line.copy(alpha = .7f), shape)
            .hxCombinedClickSource(source, onLongClick = { vm.testGroup(group) }, onClick = onClick)
            .padding(13.dp), verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(group.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = c.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(3.dp))
                Text("${group.type} · $online/${group.nodes.size}", style = MaterialTheme.typography.bodySmall, color = c.textMuted)
            }
            Spacer(Modifier.width(6.dp))
            if (expanded) Icon(Icons.Rounded.ExpandLess, "收起 ${group.name}", tint = c.accent, modifier = Modifier.size(30.dp))
            else HxGroupIcon(group, Modifier.size(34.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(group.now.ifBlank { "未选择" }, style = MaterialTheme.typography.labelLarge, color = c.text, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(5.dp))
            StrategyCompactDelayPill(delay, testing) { vm.testGroup(group) }
        }
    }
}

/** Top of the expanded node panel: count, sort hint and a per-group latency test. */
@Composable
private fun PanelHead(vm: HetuViewModel, group: ProxyGroupUi, shown: Int, modifier: Modifier, onCollapse: () -> Unit) {
    val c = Hx.colors
    Row(
        modifier.fillMaxWidth().testTag("strategy-expanded-${group.name}")
            .padding(horizontal = Hx.gutter)
            .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)).background(c.surface)
            .padding(start = 14.dp, end = 6.dp, top = 8.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HxGroupIcon(group, Modifier.size(28.dp))
        Spacer(Modifier.width(9.dp))
        Column(Modifier.weight(1f)) {
            Text(group.name, style = MaterialTheme.typography.titleMedium, color = c.text, maxLines = 2)
            Text("$shown 个节点", style = MaterialTheme.typography.bodySmall, color = c.textMuted)
        }
        HxBarAction(Icons.Rounded.Speed, "全部测速 ${group.name}", { vm.testGroup(group) }, busy = vm.testingGroups[group.name] == true)
        HxBarAction(Icons.Rounded.ExpandLess, "收起策略 ${group.name}", onCollapse)
    }
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
    val shape = RoundedCornerShape(12.dp)
    val selectedLight = c.accentSoft
    val selectedDark = androidx.compose.ui.graphics.lerp(c.surfaceMuted, c.accentSoft, .42f)
    val idleLight = c.surfaceMuted.copy(alpha = .66f)
    val bg by animateColorAsState(
        if (selected) { if (c.dark) selectedDark else selectedLight }
        else { if (c.dark) c.surfaceMuted else idleLight },
        tween(HxMotion.Medium),
        label = "nodeCardBg",
    )
    val border by animateColorAsState(
        if (selected) { if (c.dark) c.accent.copy(alpha = .55f) else c.accent.copy(alpha = .6f) } else Color.Transparent,
        tween(HxMotion.Medium),
        label = "nodeCardBorder",
    )

    Column(
        modifier
            .heightIn(min = if (compact) 72.dp else 82.dp)
            .testTag("strategy-node-${group.name}-${node.name}")
            .semantics { this.selected = selected; stateDescription = if (pendingThis) "正在切换" else if (selected) "已选择" else "未选择" }
            .hxPressScale(source, .965f)
            .clip(shape)
            .background(bg)
            .border(.7.dp, border, shape)
            .hxCombinedClickSource(
                source = source,
                enabled = selectable && pending == null,
                onLongClick = {
                    haptics.perform(HetuHaptic.LongPress)
                    vm.testNode(node.name)
                },
                onClick = {
                    if (!selected) {
                        haptics.perform(HetuHaptic.Confirm)
                        vm.select(group.name, node.name)
                    }
                },
            )
            .padding(horizontal = 10.dp, vertical = if (compact) 5.dp else 7.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                node.name,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                fontWeight = FontWeight.SemiBold,
                color = c.text,
                maxLines = if (LocalDensity.current.fontScale > 1.3f || nameOverflow == "wrap") 3 else 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (pendingThis) {
                Spacer(Modifier.width(4.dp))
                HxSpinner(11.dp)
            } else if (selected) {
                Spacer(Modifier.width(4.dp))
                Icon(Icons.Rounded.Check, "当前节点", tint = c.onAccent, modifier = Modifier.size(20.dp).background(c.accent, CircleShape).padding(3.dp))
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                (if (node.udp) "UDP · " else "") + node.type.replaceFirstChar { it.uppercase() },
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
    val bg = when {
        testing || delay == null || delay == 0L -> c.surface
        delay < 0L -> c.badSoft
        delay < 100L -> c.goodSoft
        delay < 300L -> c.accentSoft
        else -> c.warnSoft
    }
    val fg = when {
        testing || delay == null || delay == 0L -> c.textFaint
        delay < 0L -> c.bad
        delay < 100L -> c.good
        delay < 300L -> c.accent
        else -> c.warn
    }
    val source = remember { MutableInteractionSource() }
    Box(
        Modifier
            .hxPressScale(source, .92f)
            .clip(Hx.pillShape)
            .background(bg)
            .clickable(interactionSource = source, indication = null, enabled = !testing, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (testing) HxSpinner(10.dp)
        else Text(
            if (delay == null || delay == 0L) "未知" else if (delay < 0L) "超时" else "$delay ms",
            fontSize = 10.5.sp,
            lineHeight = 12.sp,
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
    Row(
        Modifier
            .hxPressScale(collapseSource, .95f)
            .height(44.dp)
            .widthIn(max = 150.dp)
            .shadow(7.dp, Hx.pillShape, clip = false, ambientColor = c.accent.copy(alpha = .22f), spotColor = c.accent.copy(alpha = .28f))
            .clip(Hx.pillShape)
            .background(c.accent)
            .clickable(interactionSource = collapseSource, indication = LocalIndication.current, onClick = onCollapse)
            .padding(start = 14.dp, end = 17.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.ExpandMore, "收起", tint = c.onAccent, modifier = Modifier.size(19.dp))
        Spacer(Modifier.width(6.dp))
        Text(groupName, style = MaterialTheme.typography.labelLarge, color = c.onAccent, maxLines = 1, overflow = TextOverflow.Ellipsis)
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

    HxSheet(onDismiss = onDismiss) {
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
                            value = draft.host,
                            onValueChange = { draft = draft.copy(host = it); error = null },
                            modifier = Modifier.weight(1f),
                        )
                        ApiInlineField(
                            label = "端口",
                            value = draft.port,
                            onValueChange = { draft = draft.copy(port = it.filter(Char::isDigit).take(5)); error = null },
                            modifier = Modifier.width(104.dp),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    ApiInlineField(
                        label = "密钥",
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
                    tone = HxTone.Neutral,
                    filled = false,
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
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit = {},
) {
    val c = Hx.colors
    val background by animateColorAsState(
        if (checked) c.accentSoft.copy(alpha = if (c.dark) .28f else .42f) else c.surfaceMuted,
        tween(HxMotion.Medium),
        label = "apiCardBg",
    )
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = background,
        shape = RoundedCornerShape(20.dp),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(36.dp).clip(RoundedCornerShape(12.dp)).background(c.surface),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(icon, null, tint = if (checked) c.accent else c.textMuted, modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.width(11.dp))
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
                visible = checked,
                enter = fadeIn(tween(HxMotion.Short)) + expandVertically(tween(HxMotion.Medium)),
                exit = fadeOut(tween(HxMotion.Short)) + shrinkVertically(tween(HxMotion.Medium)),
            ) {
                Column(Modifier.padding(top = 12.dp)) { content() }
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
) {
    val c = Hx.colors
    Column(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(c.surface)
            .padding(horizontal = 13.dp, vertical = 10.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = c.textMuted)
        Spacer(Modifier.height(3.dp))
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = c.text, fontWeight = FontWeight.Medium),
            singleLine = true,
            keyboardOptions = keyboardOptions,
            cursorBrush = SolidColor(c.accent),
            decorationBox = { inner ->
                Box(Modifier.fillMaxWidth()) {
                    if (value.isBlank() && placeholder.isNotBlank()) {
                        Text(placeholder, style = MaterialTheme.typography.bodyMedium, color = c.textFaint)
                    }
                    inner()
                }
            },
        )
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
