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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.xgl34222220.hetu.home.HomeDims
import io.github.xgl34222220.hetu.home.HomeHaptic
import io.github.xgl34222220.hetu.home.HomeIcons
import io.github.xgl34222220.hetu.home.HomeRegionCode
import io.github.xgl34222220.hetu.home.HomeType
import io.github.xgl34222220.hetu.home.LocalHomeColors
import io.github.xgl34222220.hetu.home.LocalHomeHaptics
import io.github.xgl34222220.hetu.home.LocalHomeMotionEnabled
import io.github.xgl34222220.hetu.home.homeTap
import io.github.xgl34222220.hetu.ui.hetuPressScale

/**
 * 策略 tab as lazy-list rows. Group cards sit in rows of one or two; an expanded group's node
 * panel (sunken, 16 dp corners) is inserted right below the card row that contains it.
 *
 * Tap a group card → expand / collapse. Tap a node → select. Tap its latency → test that node.
 * Long-press a node → node info sheet.
 */
internal fun LazyListScope.panelGroupsTab(
    rows: List<PanelGroupRow>,
    data: PanelData,
    view: PanelViewState,
    onToggleGroup: (String) -> Unit,
    onSelectNode: (group: String, node: String) -> Unit,
    onTestNode: (String) -> Unit,
    onTestGroup: (String) -> Unit,
    onNodeInfo: (String) -> Unit,
) {
    if (rows.isEmpty()) {
        item(key = "groups-empty") { PanelEmptyState(PanelIcons.Search, "没有匹配的策略", "尝试其他关键词") }
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
            fadeInSpec = tween(160),
            placementSpec = spring(dampingRatio = .9f, stiffness = 420f),
            fadeOutSpec = tween(100),
        ) else Modifier) {
            when (val row = rows[index]) {
                is PanelGroupRow.Cards -> Row(
                    Modifier.panelGutter().padding(bottom = 8.dp).fillMaxWidth().height(IntrinsicSize.Min),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    row.groups.forEach { group ->
                        GroupCard(
                            group = group, data = data, view = view, wide = columns == 1,
                            modifier = Modifier.weight(1f).fillMaxHeight(), onClick = { onToggleGroup(group.name) },
                        )
                    }
                    repeat(columns - row.groups.size) { Spacer(Modifier.weight(1f)) }
                }
                is PanelGroupRow.NodesHeader -> NodesHeader(row.group, onTestGroup)
                is PanelGroupRow.Provider -> NodesPiece(last = false) {
                    Text("${row.provider} · ${row.count}", Modifier.padding(start = 6.dp, top = 8.dp), color = LocalHomeColors.current.t3, style = HomeType.caption)
                }
                is PanelGroupRow.Nodes -> NodesPiece(last = row.last) {
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp).height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.nodes.forEach { node ->
                            NodeCard(
                                node = node, selected = row.group.now == node.name, delay = data.delayOf(node.name), view = view,
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

/* ------------------------------------------------------------------ */
/*  Group card                                                          */
/* ------------------------------------------------------------------ */

@Composable
private fun GroupCard(group: PanelGroup, data: PanelData, view: PanelViewState, wide: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val c = LocalHomeColors.current
    val haptics = LocalHomeHaptics.current
    val expanded = group.name in view.expandedGroups
    val compact = view.layout.compactGroups
    val pad = if (compact) 12.dp else 16.dp
    val motion = LocalHomeMotionEnabled.current
    val fill by animateColorAsState(if (expanded) c.accentSoft else c.surface, tween(if (motion) 160 else 0), label = "groupFill")
    val outline by animateColorAsState(if (expanded) c.accent.copy(alpha = .55f) else c.line, tween(if (motion) 160 else 0), label = "groupOutline")
    val shell = modifier
        .testTag("panel-group:${group.name}")
        .semantics { stateDescription = if (expanded) "已展开" else "已收起" }
        .clip(HomeDims.cardShape)
        .background(fill)
        .border(1.dp, outline, HomeDims.cardShape)
        .homeTap(onClickLabel = if (expanded) "收起节点" else "展开节点", role = Role.Button) { haptics(HomeHaptic.Tap); onClick() }
        .padding(pad)
    val title: @Composable (Modifier) -> Unit = { m ->
        Column(m) {
            Text(panelHighlight(group.name, view.needle), color = c.t1, style = PanelType.groupName, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(group.summary, color = c.t3, style = PanelType.tiny, maxLines = 1)
        }
    }
    val current: @Composable (Modifier) -> Unit = { m ->
        Row(m, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            HomeRegionCode(nodeOf(data, group.now)?.regionCode.orEmpty())
            Text(panelHighlight(group.now, view.needle), Modifier.weight(1f, fill = !wide), color = c.t2, style = HomeType.rowSub, maxLines = 1, overflow = TextOverflow.Ellipsis)
            PanelDelayLabel(data.delayOf(group.now))
        }
    }
    if (wide) {
        Row(shell, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            GroupTile(group)
            title(Modifier.weight(1f))
            current(Modifier.weight(1f, fill = false))
        }
    } else {
        Column(shell, verticalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                title(Modifier.weight(1f))
                GroupTile(group)
            }
            current(Modifier.fillMaxWidth())
        }
    }
}

private fun nodeOf(data: PanelData, name: String): PanelNode? {
    for (g in data.groups) g.nodes.firstOrNull { it.name == name }?.let { return it }
    return null
}

/** The configured image supplied by the adapter takes precedence over name/type fallbacks. */
@Composable
private fun GroupTile(group: PanelGroup) {
    val c = LocalHomeColors.current
    val slot = LocalPanelGroupIcon.current
    Box(Modifier.size(32.dp).clip(PanelDims.tileShape).background(c.sunken), contentAlignment = Alignment.Center) {
        val code = PanelRegions.codeOf(group.name)
        when {
            slot != null -> slot(group, Modifier.size(28.dp))
            code.isNotEmpty() -> Text(code, color = c.t2, style = PanelType.tileCode)
            else -> Icon(
                when {
                    group.isGlobal -> PanelIcons.Globe
                    group.type.equals("URLTest", true) -> PanelIcons.Zap
                    group.type.equals("Fallback", true) -> PanelIcons.ShieldCheck
                    else -> PanelIcons.Pointer
                },
                null, Modifier.size(16.dp), tint = c.t2,
            )
        }
    }
}

/* ------------------------------------------------------------------ */
/*  Node panel pieces                                                   */
/* ------------------------------------------------------------------ */

@Composable
private fun NodesHeader(group: PanelGroup, onTestGroup: (String) -> Unit) {
    val c = LocalHomeColors.current
    val haptics = LocalHomeHaptics.current
    Row(
        Modifier.panelGutter().fillMaxWidth()
            .background(c.sunken, RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
            .padding(start = 14.dp, end = 6.dp, top = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("${group.name} · ${group.nodes.size} 个节点", Modifier.weight(1f), color = c.t2, style = PanelType.chip, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Row(
            Modifier.heightIn(min = 48.dp).clip(PanelDims.tabShape)
                .homeTap(onClickLabel = "测试该组全部节点", role = Role.Button) { haptics(HomeHaptic.Tap); onTestGroup(group.name) }
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(PanelIcons.Gauge, null, Modifier.size(18.dp), tint = c.accent)
            Text("测速", color = c.accent, style = HomeType.buttonSmall)
        }
    }
}

/** A slice of the sunken node panel. The last slice rounds the bottom corners and adds the 8 dp gap below. */
@Composable
private fun NodesPiece(last: Boolean, content: @Composable () -> Unit) {
    val c = LocalHomeColors.current
    Box(
        Modifier.panelGutter().padding(bottom = if (last) 8.dp else 0.dp).fillMaxWidth()
            .background(c.sunken, if (last) RoundedCornerShape(bottomStart = 16.dp, bottomEnd = 16.dp) else RoundedCornerShape(0.dp))
            .padding(start = 8.dp, end = 8.dp, bottom = if (last) 8.dp else 0.dp),
    ) { content() }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NodeCard(
    node: PanelNode,
    selected: Boolean,
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
    val fill by animateColorAsState(if (selected) c.accentSoft else c.surface, tween(if (motion) 160 else 0), label = "nodeFill")
    val shell = modifier
        .heightIn(min = if (compact) 64.dp else 92.dp)
        .testTag("panel-node:${node.name}")
        .semantics { this.selected = selected }
        .hetuPressScale(source, pressedScale = .975f, motion = motion)
        .clip(PanelDims.nodeShape)
        .background(fill)
        .border(if (selected) 2.dp else 1.dp, if (selected) c.accent else c.line, PanelDims.nodeShape)
        .combinedClickable(
            interactionSource = source,
            indication = null,
            role = Role.RadioButton,
            onLongClickLabel = "节点信息",
            onLongClick = { haptics(HomeHaptic.Tap); onInfo() },
            onClick = { haptics(HomeHaptic.Confirm); onSelect() },
        )
        .padding(horizontal = 14.dp, vertical = if (compact) 10.dp else 12.dp)
    val name: @Composable (Modifier) -> Unit = { m ->
        Row(m, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            HomeRegionCode(node.regionCode)
            Text(
                node.name, Modifier.weight(1f), color = c.t1, style = PanelType.nodeName,
                maxLines = if (view.layout.wrapNames) 3 else if (compact) 1 else 2, overflow = TextOverflow.Ellipsis,
            )
            if (selected) Icon(HomeIcons.CircleCheck, "当前节点", Modifier.size(18.dp), tint = c.accent)
        }
    }
    if (compact) {
        Row(shell, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            name(Modifier.weight(1f))
            PanelDelayLabel(delay, onClick = onTest)
        }
    } else {
        Column(shell, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            name(Modifier.fillMaxWidth())
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(node.meta, Modifier.weight(1f), color = c.t3, style = PanelType.tiny, maxLines = 1, overflow = TextOverflow.Ellipsis)
                PanelDelayLabel(delay, onClick = onTest)
            }
        }
    }
}
