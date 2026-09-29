package io.github.xgl34222220.hetu
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.util.Locale

@Composable
internal fun ProxiesScreen(vm: HetuViewModel, bottomPadding: Dp) {
    val context = LocalContext.current
    val state = vm.state
    val options = rememberPanelOptions11(vm.prefs)
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var selectedGroupName by rememberSaveable { mutableStateOf("") }
    var showOptions by remember { mutableStateOf(false) }
    var showApi by remember { mutableStateOf(false) }

    val visibleGroups = panelGroups11(state.groups, query, state.trafficMode, options)
    val defaultGroup = visibleGroups.firstOrNull { it.name == "节点选择" }
        ?: visibleGroups.firstOrNull { it.type.equals("Selector", true) && !it.name.equals("GLOBAL", true) }
        ?: visibleGroups.firstOrNull()
    val selectedGroup = visibleGroups.firstOrNull { it.name == selectedGroupName } ?: defaultGroup
    val selectedNodes = selectedGroup
        ?.let { projectStrategyNodes(it.nodes, options.sort, options.descending, vm.delays) }
        .orEmpty()
    val sections = if (options.providers) {
        selectedNodes.groupBy { it.provider.ifBlank { "配置内节点" } }
    } else {
        linkedMapOf("" to selectedNodes)
    }

    LaunchedEffect(defaultGroup?.name, visibleGroups.size, selectedGroupName) {
        if (selectedGroupName.isBlank() || visibleGroups.none { it.name == selectedGroupName }) {
            selectedGroupName = defaultGroup?.name.orEmpty()
        }
    }

    HxPage(
        title = "代理",
        scrollToTopSignal = vm.reselect,
        subtitle = if (state.running) "${visibleGroups.size} 个策略组 · ${visibleGroups.sumOf { it.nodes.size }} 个节点" else null,
        bottomPadding = bottomPadding,
        actions = {
            if (state.running) {
                HxBarAction(if (searching) Icons.Rounded.SearchOff else Icons.Rounded.Search, "搜索", onClick = {
                    searching = !searching
                    if (!searching) query = ""
                })
                HxBarAction(Icons.Rounded.Tune, "排序与显示", onClick = { showOptions = true })
            }
            HxBarAction(Icons.Rounded.Settings, "测速与 API", onClick = { showApi = true })
        },
    ) {
        if (!state.running) {
            item(key = "stopped") {
                HxEmpty(Icons.Rounded.Hub, "代理未运行", "启动后即可选择策略组、切换节点和测速") {
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
                    "搜索策略组、节点、订阅或协议",
                    Modifier.padding(horizontal = Hx.gutter).padding(bottom = 10.dp),
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

        item(key = "strategy-tabs") {
            StrategyGroupTabs(
                groups = visibleGroups,
                selected = selectedGroup?.name.orEmpty(),
                onSelect = { selectedGroupName = it },
            )
        }

        selectedGroup?.let { group ->
            item(key = "strategy-summary") {
                AnimatedContent(
                    targetState = group,
                    transitionSpec = {
                        (fadeIn(spring(stiffness = Spring.StiffnessMediumLow)) +
                            slideInVertically(spring(dampingRatio = .86f, stiffness = Spring.StiffnessMediumLow)) { it / 5 })
                            .togetherWith(
                                fadeOut(spring(stiffness = Spring.StiffnessMediumLow)) +
                                    slideOutVertically(spring(dampingRatio = .9f, stiffness = Spring.StiffnessMediumLow)) { -it / 7 },
                            )
                    },
                    label = "strategy-summary",
                ) { targetGroup ->
                    StrategySummary(
                        vm = vm,
                        group = targetGroup,
                        nodeCount = if (targetGroup.name == group.name) selectedNodes.size else targetGroup.nodes.size,
                    )
                }
            }

            sections.forEach { (provider, members) ->
                if (provider.isNotBlank()) {
                    item(key = "provider:${group.name}:$provider") {
                        Text(
                            provider,
                            style = MaterialTheme.typography.labelMedium,
                            color = Hx.colors.textMuted,
                            modifier = Modifier.padding(horizontal = Hx.gutter + 4.dp).padding(top = 8.dp, bottom = 6.dp),
                        )
                    }
                }
                items(
                    items = members,
                    key = { node -> "node:${group.name}:${provider}:${node.name}" },
                ) { node ->
                    NodeTile(
                        vm = vm,
                        group = group,
                        node = node,
                        modifier = Modifier
                            .animateItem(
                                fadeInSpec = spring<Float>(stiffness = Spring.StiffnessMediumLow),
                                placementSpec = spring<IntOffset>(dampingRatio = .8f, stiffness = 400f),
                                fadeOutSpec = spring<Float>(stiffness = Spring.StiffnessMediumLow),
                            )
                            .padding(horizontal = Hx.gutter, vertical = 4.dp),
                    )
                }
            }
            item(key = "node-bottom-space:${group.name}") { Spacer(Modifier.height(8.dp)) }
        }
    }

    if (showOptions) {
        HxSheet(onDismiss = { showOptions = false }, title = "排序与显示") {
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("节点排序", style = MaterialTheme.typography.labelLarge, color = Hx.colors.textMuted)
                HxSegmented(
                    options = listOf("config" to "配置", "latency" to "延迟", "name" to "名称"),
                    selected = if (options.sort == "delay") "latency" else options.sort,
                    onSelect = { options.copy(sort = it).save(vm.prefs) },
                )
                HxGroup {
                    HxSwitchRow("倒序", options.descending, { options.copy(descending = it).save(vm.prefs) }, subtitle = "名称与延迟排序时生效")
                    HxDivider(16.dp)
                    HxSwitchRow("按订阅分组", options.providers, { options.copy(providers = it).save(vm.prefs) })
                    HxDivider(16.dp)
                    HxSwitchRow("显示隐藏策略组", options.showHidden, { options.copy(showHidden = it).save(vm.prefs) })
                }
                HxButton(
                    if (vm.testingAll) "正在测速全部节点" else "测速全部节点",
                    onClick = vm::testAll,
                    icon = Icons.Rounded.Speed,
                    busy = vm.testingAll,
                    filled = false,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }

    if (showApi) {
        ApiSettingsSheet(vm, onDismiss = { showApi = false })
    }
}

@Composable
private fun StrategyGroupTabs(
    groups: List<ProxyGroupUi>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    val c = Hx.colors
    val haptics = rememberHetuHaptics()
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = Hx.gutter, vertical = 4.dp)
            .padding(bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        groups.forEach { group ->
            val active = group.name == selected
            val bg by animateColorAsState(
                targetValue = if (active) {
                    if (c.dark) c.surfaceMuted else c.text
                } else c.surfaceMuted,
                animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                label = "strategy-tab-bg",
            )
            val fg by animateColorAsState(
                targetValue = if (active) {
                    if (c.dark) c.text else c.canvas
                } else c.textMuted,
                animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                label = "strategy-tab-fg",
            )
            val source = remember(group.name) { MutableInteractionSource() }
            Box(
                Modifier
                    .hxPressScale(source, .97f)
                    .clip(CircleShape)
                    .background(bg)
                    .clickable(
                        interactionSource = source,
                        indication = LocalIndication.current,
                        enabled = !active,
                    ) {
                        haptics.perform(HetuHaptic.Tick)
                        onSelect(group.name)
                    }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    group.name,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                    color = fg,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun StrategySummary(vm: HetuViewModel, group: ProxyGroupUi, nodeCount: Int) {
    val c = Hx.colors
    val current = group.now.ifBlank { "未选择节点" }
    val delay = vm.delays[group.now] ?: group.nodes.firstOrNull { it.name == group.now }?.lastDelay
    Row(
        Modifier.fillMaxWidth().padding(horizontal = Hx.gutter, vertical = 6.dp).padding(bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(group.name, style = MaterialTheme.typography.titleMedium, color = c.text, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(2.dp))
            Text(
                "$current · $nodeCount 个节点",
                style = MaterialTheme.typography.bodySmall,
                color = c.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (delay != null && group.now.isNotBlank()) {
            HxDelayPill(delay, false) { vm.testNode(group.now) }
            Spacer(Modifier.width(8.dp))
        }
        if (vm.testingGroups[group.name] == true) {
            HxSpinner(16.dp)
        } else {
            Text(
                "测速",
                style = MaterialTheme.typography.labelMedium,
                color = c.accent,
                modifier = Modifier
                    .clip(Hx.chipShape)
                    .clickable { vm.testGroup(group) }
                    .padding(horizontal = 9.dp, vertical = 6.dp),
            )
        }
    }
}

/** 测速 URL、Clash API 后端与历史采集 — the same preferences the backend client reads. */
@Composable
internal fun ApiSettingsSheet(vm: HetuViewModel, onDismiss: () -> Unit) {
    val c = Hx.colors
    var draft by remember { mutableStateOf(PanelApiDraft11.read(vm.prefs)) }
    var error by remember { mutableStateOf<String?>(null) }
    HxSheet(onDismiss = onDismiss, title = "测速与 API") {
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            HxGroup {
                HxSwitchRow("自定义测速地址", draft.customDelay, { draft = draft.copy(customDelay = it); error = null },
                    subtitle = "节点测速使用此 URL（默认使用订阅/策略组自带地址）")
                if (draft.customDelay) {
                    OutlinedTextField(
                        value = draft.delayUrl,
                        onValueChange = { draft = draft.copy(delayUrl = it); error = null },
                        singleLine = true,
                        label = { Text("测速 URL") },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp).padding(bottom = 10.dp),
                        shape = Hx.rowShape,
                    )
                }
                HxDivider(16.dp)
                HxSwitchRow("流量与连接历史", draft.history, { draft = draft.copy(history = it); error = null },
                    subtitle = "在本机保存 24 小时排行所需的数据，关闭后不再新增")
                HxDivider(16.dp)
                HxSwitchRow("使用外部 Clash API", draft.customApi, { draft = draft.copy(customApi = it); error = null },
                    subtitle = "不使用河图内置控制器 127.0.0.1:${vm.state.controllerPort}")
                if (draft.customApi) {
                    Column(Modifier.padding(horizontal = 14.dp).padding(bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(draft.host, { draft = draft.copy(host = it); error = null }, singleLine = true,
                            label = { Text("主机") }, modifier = Modifier.fillMaxWidth(), shape = Hx.rowShape)
                        OutlinedTextField(draft.port, { draft = draft.copy(port = it.filter(Char::isDigit).take(5)); error = null }, singleLine = true,
                            label = { Text("端口") }, modifier = Modifier.fillMaxWidth(), shape = Hx.rowShape,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                        OutlinedTextField(draft.secret, { draft = draft.copy(secret = it); error = null }, singleLine = true,
                            label = { Text("Secret") }, modifier = Modifier.fillMaxWidth(), shape = Hx.rowShape)
                    }
                }
            }
            error?.let { Text(it, color = c.bad, style = MaterialTheme.typography.bodySmall) }
            HxButton("保存", onClick = {
                val problem = draft.validationError()
                if (problem != null) error = problem else {
                    draft.save(vm.prefs)
                    vm.toast("已保存")
                    onDismiss()
                }
            }, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun NodeTile(
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
    val source = remember(node.name) { MutableInteractionSource() }
    val haptics = rememberHetuHaptics()

    val bg by animateColorAsState(
        targetValue = if (selected) c.accentSoft.copy(alpha = if (c.dark) .42f else .62f) else c.surface,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "node-bg",
    )
    val outline by animateColorAsState(
        targetValue = if (selected) c.accent.copy(alpha = .48f) else c.line.copy(alpha = .55f),
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "node-outline",
    )

    Surface(
        modifier = modifier.hxPressScale(source, .985f),
        shape = RoundedCornerShape(16.dp),
        color = bg,
        border = BorderStroke(if (selected) 1.dp else 0.5.dp, outline),
        shadowElevation = 0.dp,
        onClick = {
            if (selectable && pending == null && !selected) {
                haptics.perform(HetuHaptic.Tap)
                vm.select(group.name, node.name)
            }
        },
        enabled = selectable && pending == null && !selected,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 15.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.width(18.dp), contentAlignment = Alignment.CenterStart) {
                AnimatedVisibility(
                    visible = selected || pendingThis,
                    enter = scaleIn(spring(dampingRatio = .55f, stiffness = Spring.StiffnessMediumLow), initialScale = .35f) +
                        fadeIn(spring(stiffness = Spring.StiffnessMediumLow)),
                    exit = scaleOut(spring(dampingRatio = .8f, stiffness = Spring.StiffnessMediumLow), targetScale = .45f) +
                        fadeOut(spring(stiffness = Spring.StiffnessMediumLow)),
                ) {
                    if (pendingThis) HxSpinner(12.dp)
                    else Box(Modifier.size(8.dp).clip(CircleShape).background(c.accent))
                }
            }
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    node.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                    color = c.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    listOf(node.type.uppercase(Locale.ROOT), node.provider)
                        .filter { it.isNotBlank() }
                        .joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = c.textFaint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(10.dp))
            HxDelayPill(delay, testing) { vm.testNode(node.name) }
        }
    }
}

/** Compact, low-saturation latency indicator. */
@Composable
internal fun HxDelayPill(delay: Long?, testing: Boolean, onClick: () -> Unit) {
    val c = Hx.colors
    val bg = when {
        delay == null || delay == 0L -> c.surfaceMuted
        delay < 0L -> c.badSoft.copy(alpha = .62f)
        delay < 100L -> c.goodSoft.copy(alpha = .68f)
        delay < 200L -> c.warnSoft.copy(alpha = .62f)
        delay < 500L -> c.warnSoft.copy(alpha = .48f)
        else -> c.badSoft.copy(alpha = .58f)
    }
    val fg = when {
        delay == null || delay == 0L -> c.textFaint
        delay < 0L -> c.bad
        delay < 100L -> c.good
        delay < 500L -> c.warn
        else -> c.bad
    }
    Box(
        Modifier
            .width(62.dp)
            .height(26.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .clickable(enabled = !testing, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (testing) {
            HxSpinner(11.dp, fg)
        } else {
            Text(
                when {
                    delay != null && delay > 0 -> "${delay}ms"
                    delay == -1L -> "超时"
                    delay != null && delay < 0 -> "失败"
                    else -> "测速"
                },
                style = MaterialTheme.typography.labelSmall.merge(HxNumberStyle),
                fontWeight = FontWeight.SemiBold,
                color = fg,
                maxLines = 1,
            )
        }
    }
}
