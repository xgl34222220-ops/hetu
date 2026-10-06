package io.github.xgl34222220.hetu.panel

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.xgl34222220.hetu.home.HomeCheckMark
import io.github.xgl34222220.hetu.home.HomeDims
import io.github.xgl34222220.hetu.home.HomeFlag
import io.github.xgl34222220.hetu.home.HomeHaptic
import io.github.xgl34222220.hetu.home.HomeMotion
import io.github.xgl34222220.hetu.home.HomePop
import io.github.xgl34222220.hetu.home.HomeRegions
import io.github.xgl34222220.hetu.home.HomeSkeleton
import io.github.xgl34222220.hetu.home.HomeSpinner
import io.github.xgl34222220.hetu.home.HomeType
import io.github.xgl34222220.hetu.home.LocalHomeColors
import io.github.xgl34222220.hetu.home.LocalHomeHaptics
import io.github.xgl34222220.hetu.home.LocalHomeMotionEnabled
import io.github.xgl34222220.hetu.home.fill
import io.github.xgl34222220.hetu.home.homeEnter
import io.github.xgl34222220.hetu.home.homeTap
import io.github.xgl34222220.hetu.ui.HetuStaggerState
import io.github.xgl34222220.hetu.ui.hetuPressScale
import io.github.xgl34222220.hetu.ui.ht

/**
 * 策略 tab as lazy-list rows. Group cards sit in rows of one or two; an expanded group's nodes
 * appear in one white container right below the card row that holds the group.
 *
 * Tap a group card → expand / collapse. Tap a node → select. Tap its latency → test that node.
 * Long-press a node → node info sheet.
 */
internal fun LazyListScope.panelGroupsTab(
    rows: List<PanelGroupRow>,
    data: PanelData,
    view: PanelViewState,
    stagger: HetuStaggerState,
    onToggleGroup: (String) -> Unit,
    onSelectNode: (group: String, node: String) -> Unit,
    onTestNode: (String) -> Unit,
    onTestGroup: (String) -> Unit,
    onNodeInfo: (String) -> Unit,
) {
    if (rows.isEmpty()) {
        item(key = "groups-empty") {
            if (view.needle.isEmpty()) PanelEmptyState(PanelIcons.Layers, "没有策略组", "当前配置没有可显示的策略组")
            else PanelEmptyState(PanelIcons.SearchX, "没有匹配的策略", "尝试其他关键词")
        }
        return
    }
    val columns = view.layout.groupColumns.coerceIn(1, 2)
    val nodeColumns = view.layout.nodeColumns.coerceIn(1, 2)
    items(
        count = rows.size,
        key = { index ->
            when (val row = rows[index]) {
                is PanelGroupRow.Cards -> "g:" + row.groups.first().name
                is PanelGroupRow.NodesHeader -> "h:" + row.group.name
                is PanelGroupRow.Provider -> "p:" + row.group.name + ":" + row.provider
                is PanelGroupRow.Nodes -> "n:" + row.group.name + ":" + row.nodes.first().name
            }
        },
        contentType = { index -> rows[index]::class },
    ) { index ->
        val motion = LocalHomeMotionEnabled.current
        Box(if (motion) Modifier.animateItem(
            fadeInSpec = tween(180),
            placementSpec = spring(dampingRatio = .9f, stiffness = 420f),
            fadeOutSpec = tween(100),
        ) else Modifier) {
            when (val row = rows[index]) {
                is PanelGroupRow.Cards -> Row(
                    Modifier.panelGutter().padding(bottom = PanelDims.gap).fillMaxWidth().height(IntrinsicSize.Min).homeEnter(stagger, index),
                    horizontalArrangement = Arrangement.spacedBy(PanelDims.gap),
                ) {
                    row.groups.forEach { group ->
                        GroupCard(
                            group = group, data = data, view = view, wide = columns == 1,
                            modifier = Modifier.weight(1f).fillMaxHeight(), onClick = { onToggleGroup(group.name) },
                        )
                    }
                    repeat(columns - row.groups.size) { Spacer(Modifier.weight(1f)) }
                }
                is PanelGroupRow.NodesHeader -> NodesHeader(row.group, row.group.name in data.testingGroups || data.testingAll, onTestGroup)
                is PanelGroupRow.Provider -> NodesPiece(last = false) {
                    Text("${row.provider} · ${row.count}", Modifier.padding(start = 4.dp, top = 12.dp), color = LocalHomeColors.current.t2, style = HomeType.note)
                }
                is PanelGroupRow.Nodes -> NodesPiece(last = row.last) {
                    Row(Modifier.fillMaxWidth().padding(top = 9.dp).height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                        row.nodes.forEach { node ->
                            NodeCard(
                                node = node, selected = row.group.now == node.name, switching = data.switching[row.group.name] == node.name,
                                delay = data.delayOf(node.name), view = view,
                                modifier = Modifier.weight(1f).fillMaxHeight(),
                                onSelect = { onSelectNode(row.group.name, node.name) }, onTest = { onTestNode(node.name) }, onInfo = { onNodeInfo(node.name) },
                            )
                        }
                        repeat(nodeColumns - row.nodes.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }
}

/** Placeholder grid shown while the core is up but its first snapshot has not arrived. */
internal fun LazyListScope.panelLoadingTab(columns: Int) {
    items(count = 4, key = { "loading:$it" }) {
        Row(Modifier.panelGutter().padding(bottom = PanelDims.gap).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(PanelDims.gap)) {
            repeat(columns.coerceIn(1, 2)) { HomeSkeleton(Modifier.weight(1f).height(if (columns <= 1) 72.dp else 96.dp), PanelDims.groupShape) }
        }
    }
}

/* ------------------------------------------------------------------ */
/*  Group card                                                          */
/* ------------------------------------------------------------------ */

@Composable
private fun GroupCard(group: PanelGroup, data: PanelData, view: PanelViewState, wide: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val c = LocalHomeColors.current
    val haptics = LocalHomeHaptics.current
    val expanded = group.name in view.expandedGroups
    val compact = view.layout.compactGroups
    val motion = LocalHomeMotionEnabled.current
    val fill by animateColorAsState(if (expanded) lerp(c.surface, c.accent, if (c.dark) .14f else .055f) else c.surface, HomeMotion.fade(motion), label = "groupFill")
    val outline by animateColorAsState(if (expanded) c.accent.copy(alpha = .58f) else Color.Transparent, HomeMotion.fade(motion), label = "groupOutline")
    val opened = ht("已展开")
    val closed = ht("已收起")
    val shell = modifier
        .testTag("panel-group:${group.name}")
        .semantics { stateDescription = if (expanded) opened else closed }
        .homeTap(onClickLabel = ht(if (expanded) "收起节点" else "展开节点"), role = Role.Button) { haptics(HomeHaptic.Tap); onClick() }
        .clip(PanelDims.groupShape)
        .background(fill)
        .border(1.5.dp, outline, PanelDims.groupShape)
        .padding(start = 13.dp, end = 12.dp, top = if (compact) 13.dp else 17.dp, bottom = if (compact) 10.dp else 14.dp)
    val title: @Composable (Modifier) -> Unit = { m ->
        Column(m) {
            Text(panelHighlight(group.name, view.needle), color = c.t1, style = PanelType.groupName, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(group.summary, color = c.t2, style = PanelType.groupSummary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
    val current: @Composable (Modifier) -> Unit = { m ->
        val leaf = data.leafOf(group.now)
        val pending = data.switching[group.name]
        Row(m.heightIn(min = 28.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            HomeFlag(HomeRegions.codeOf(leaf), height = 16.dp)
            Text(
                panelHighlight(HomeRegions.withoutFlag(group.now), view.needle), Modifier.weight(1f, fill = !wide),
                color = c.t1, style = PanelType.groupNow, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            if (pending != null) HomeSpinner(size = 16.dp)
            else PanelDelayLabel(data.delayOf(group.now), onCard = true)
        }
    }
    if (wide) {
        Row(shell, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            GroupTile(group)
            title(Modifier.weight(1f))
            current(Modifier.weight(1f, fill = false))
        }
    } else {
        Column(shell, verticalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                title(Modifier.weight(1f))
                GroupTile(group)
            }
            current(Modifier.fillMaxWidth())
        }
    }
}

/** The configured image supplied by the adapter takes precedence over the region flag and the type mark. */
@Composable
private fun GroupTile(group: PanelGroup) {
    val c = LocalHomeColors.current
    val slot = LocalPanelGroupIcon.current
    Box(Modifier.size(34.dp), contentAlignment = Alignment.Center) {
        val code = PanelRegions.codeOf(group.name)
        when {
            slot != null -> slot(group, Modifier.size(34.dp))
            code.isNotEmpty() -> HomeFlag(code, height = 23.dp)
            else -> Box(Modifier.size(34.dp).background(c.accentSoft, RoundedCornerShape(11.dp)), contentAlignment = Alignment.Center) {
                Icon(
                    when {
                        group.isGlobal -> PanelIcons.Globe
                        group.type.equals("URLTest", true) -> PanelIcons.Zap
                        group.type.equals("Fallback", true) -> PanelIcons.ShieldCheck
                        group.type.equals("LoadBalance", true) -> PanelIcons.Route
                        else -> PanelIcons.Pointer
                    },
                    null, Modifier.size(20.dp), tint = c.accent,
                )
            }
        }
    }
}

/* ------------------------------------------------------------------ */
/*  Node container pieces                                               */
/* ------------------------------------------------------------------ */

@Composable
private fun NodesHeader(group: PanelGroup, testing: Boolean, onTestGroup: (String) -> Unit) {
    val c = LocalHomeColors.current
    val haptics = LocalHomeHaptics.current
    Row(
        Modifier.panelGutter().fillMaxWidth()
            .background(c.surface, RoundedCornerShape(topStart = PanelDims.nodesShape, topEnd = PanelDims.nodesShape))
            .padding(start = 16.dp, end = 6.dp, top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            group.name + " · " + ht("%d 个节点").fill(group.nodes.size), Modifier.weight(1f),
            color = c.t2, style = HomeType.note.copy(fontWeight = PanelType.tab.fontWeight), maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        Row(
            Modifier.heightIn(min = 44.dp).clip(PanelDims.tabShape)
                .homeTap(enabled = !testing, onClickLabel = ht("测试该组全部节点"), role = Role.Button) { haptics(HomeHaptic.Tap); onTestGroup(group.name) }
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (testing) HomeSpinner(size = 18.dp) else Icon(PanelIcons.Gauge, null, Modifier.size(20.dp), tint = c.accent)
            Text(ht(if (testing) "测速中" else "测速"), color = c.accent, style = HomeType.buttonSmall)
        }
    }
}

/** A slice of the white node container. The last slice rounds the bottom corners and adds the gap below. */
@Composable
private fun NodesPiece(last: Boolean, content: @Composable () -> Unit) {
    val c = LocalHomeColors.current
    Box(
        Modifier.panelGutter().padding(bottom = if (last) PanelDims.gap else 0.dp).fillMaxWidth()
            .background(c.surface, if (last) RoundedCornerShape(bottomStart = PanelDims.nodesShape, bottomEnd = PanelDims.nodesShape) else RoundedCornerShape(0.dp))
            .padding(start = 12.dp, end = 12.dp, bottom = if (last) 12.dp else 0.dp),
    ) { content() }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NodeCard(
    node: PanelNode,
    selected: Boolean,
    switching: Boolean,
    delay: PanelDelay?,
    view: PanelViewState,
    modifier: Modifier,
    onSelect: () -> Unit,
    onTest: () -> Unit,
    onInfo: () -> Unit,
) {
    val c = LocalHomeColors.current
    val haptics = LocalHomeHaptics.current
    val compact = view.layout.compactNodes
    val source = remember { MutableInteractionSource() }
    val motion = LocalHomeMotionEnabled.current
    val rest = if (c.dark) c.sunken else c.sunken
    val fill by animateColorAsState(if (selected) lerp(rest, c.accent, if (c.dark) .20f else .09f) else rest, HomeMotion.fade(motion), label = "nodeFill")
    val outline by animateColorAsState(
        when { selected -> c.accent.copy(alpha = .62f); switching -> c.accent.copy(alpha = .28f); else -> Color.Transparent },
        HomeMotion.fade(motion), label = "nodeOutline",
    )
    val info = ht("节点信息")
    val shell = modifier
        .heightIn(min = if (compact) 64.dp else 92.dp)
        .testTag("panel-node:${node.name}")
        .semantics { this.selected = selected }
        .hetuPressScale(source, pressedScale = .975f, motion = motion)
        .clip(PanelDims.nodeShape)
        .background(fill)
        .border(1.5.dp, outline, PanelDims.nodeShape)
        .combinedClickable(
            interactionSource = source,
            indication = null,
            role = Role.RadioButton,
            onLongClickLabel = info,
            onLongClick = { haptics(HomeHaptic.Tap); onInfo() },
            onClick = { haptics(HomeHaptic.Confirm); onSelect() },
        )
        .padding(start = 12.dp, end = 8.dp, top = 10.dp, bottom = 8.dp)
    val name: @Composable (Modifier) -> Unit = { m ->
        Row(m, verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                node.name, Modifier.weight(1f), color = c.t1, style = PanelType.nodeName,
                maxLines = if (view.layout.wrapNames) 3 else if (compact) 1 else 2, overflow = TextOverflow.Ellipsis,
            )
            if (switching) HomeSpinner(Modifier.padding(top = 2.dp, end = 2.dp), size = 18.dp)
            else HomePop(selected, Modifier.padding(end = 2.dp)) { HomeCheckMark(true, size = 20.dp) }
        }
    }
    if (compact) {
        Row(shell, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            name(Modifier.weight(1f))
            PanelDelayLabel(delay, onClick = onTest)
        }
    } else {
        Column(shell) {
            name(Modifier.fillMaxWidth())
            Spacer(Modifier.weight(1f).heightIn(min = 4.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                Column(Modifier.weight(1f).padding(bottom = 1.dp)) {
                    if (node.udp) Text("UDP", color = c.t2, style = PanelType.nodeMeta, maxLines = 1)
                    if (node.protocol.isNotBlank()) Text(node.protocol.uppercase(), color = c.t2, style = PanelType.nodeMeta, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                PanelDelayLabel(delay, onClick = onTest)
            }
        }
    }
}
