package io.github.xgl34222220.hetu
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.foundation.lazy.LazyColumn
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
    var sheetGroupName by rememberSaveable { mutableStateOf<String?>(null) }
    var showOptions by remember { mutableStateOf(false) }
    var showApi by remember { mutableStateOf(false) }

    val visibleGroups = panelGroups11(state.groups, query, state.trafficMode, options)
    val manualGroups = visibleGroups.filter {
        val type = it.type.lowercase(Locale.ROOT)
        type.contains("select") || type == "selector"
    }
    val automaticGroups = visibleGroups.filterNot { it in manualGroups }
    val sheetGroup = state.groups.firstOrNull { it.name == sheetGroupName }

    HxPage(
        title = "代理",
        scrollToTopSignal = vm.reselect,
        subtitle = if (state.running) visibleGroups.size.toString() + " 个策略组" else null,
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

        if (manualGroups.isNotEmpty()) {
            item(key = "manual-groups") {
                StrategyPolicySection(
                    title = "手动策略",
                    groups = manualGroups,
                    vm = vm,
                    showTestAll = true,
                    onOpen = { sheetGroupName = it },
                )
            }
        }
        if (automaticGroups.isNotEmpty()) {
            item(key = "automatic-groups") {
                StrategyPolicySection(
                    title = if (manualGroups.isEmpty()) "策略组" else "自动策略",
                    groups = automaticGroups,
                    vm = vm,
                    showTestAll = manualGroups.isEmpty(),
                    onOpen = { sheetGroupName = it },
                )
            }
        }
    }

    sheetGroup?.let { group ->
        StrategyNodeSheet(
            vm = vm,
            group = group,
            options = options,
            onDismiss = { sheetGroupName = null },
        )
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
                HxGroup {
                    HxNavRow("策略图标", subtitle = "为策略组配置图标") {
                        showOptions = false
                        context.startActivity(Intent(context, ProxyPolicyIconsActivity::class.java))
                    }
                }
            }
        }
    }

    if (showApi) ApiSettingsSheet(vm, onDismiss = { showApi = false })
}

@Composable
private fun StrategyPolicySection(
    title: String,
    groups: List<ProxyGroupUi>,
    vm: HetuViewModel,
    showTestAll: Boolean,
    onOpen: (String) -> Unit,
) {
    HxSection(
        title,
        trailing = {
            if (showTestAll) {
                if (vm.testingAll) HxSpinner(14.dp)
                else Text(
                    "全部测速",
                    style = MaterialTheme.typography.labelMedium,
                    color = Hx.colors.accent,
                    modifier = Modifier
                        .clip(Hx.chipShape)
                        .clickable { vm.testAll() }
                        .padding(horizontal = 7.dp, vertical = 4.dp),
                )
            }
        },
    ) {
        HxGroup {
            groups.forEachIndexed { index, group ->
                if (index > 0) HxDivider(46.dp)
                StrategyPolicyRow(
                    vm = vm,
                    group = group,
                    onClick = { onOpen(group.name) },
                )
            }
        }
    }
}

@Composable
private fun StrategyPolicyRow(vm: HetuViewModel, group: ProxyGroupUi, onClick: () -> Unit) {
    val c = Hx.colors
    val currentNode = group.nodes.firstOrNull { it.name == group.now }
    val delay = vm.delays[group.now] ?: currentNode?.lastDelay
    val testing = vm.testingGroups[group.name] == true
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .heightIn(min = 58.dp)
            .padding(start = 13.dp, end = 10.dp, top = 9.dp, bottom = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(26.dp), contentAlignment = Alignment.Center) {
            HxGroupIcon(group, Modifier.size(21.dp))
        }
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(
                group.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = c.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                HxFormat.groupType(group.type) + " · " + group.now.ifBlank { "未选择" } + " · " + group.nodes.size + " 节点",
                style = MaterialTheme.typography.bodySmall,
                color = c.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        HxDelayPill(delay, testing) { vm.testGroup(group) }
        Spacer(Modifier.width(3.dp))
        HxChevron()
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

    LaunchedEffect(group.now, pending) {
        if (pending == null && group.now.isNotBlank() && group.now != initialNode) onDismiss()
    }

    HxSheet(onDismiss = onDismiss) {
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
                Modifier.fillMaxWidth().heightIn(min = 240.dp, max = 560.dp),
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

    Column(
        modifier
            .fillMaxWidth()
            .background(if (selected) c.accentSoft.copy(alpha = if (c.dark) .28f else .40f) else Color.Transparent)
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
                when {
                    pendingThis -> HxSpinner(12.dp)
                    selected -> Icon(Icons.Rounded.Check, null, tint = c.accent, modifier = Modifier.size(17.dp))
                }
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

/** Fixed-size latency indicator. Crossfade keeps spinner and text from deforming. */
@Composable
internal fun HxDelayPill(delay: Long?, testing: Boolean, onClick: () -> Unit) {
    val c = Hx.colors
    val bg = when {
        testing || delay == null || delay == 0L -> c.surfaceMuted
        delay < 0L -> c.badSoft.copy(alpha = .62f)
        delay < 100L -> c.goodSoft.copy(alpha = .68f)
        delay < 200L -> c.warnSoft.copy(alpha = .62f)
        else -> c.badSoft.copy(alpha = .58f)
    }
    val fg = when {
        testing || delay == null || delay == 0L -> c.textFaint
        delay < 0L -> c.bad
        delay < 100L -> c.good
        delay < 200L -> c.warn
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
        Crossfade(
            targetState = testing,
            animationSpec = tween(150),
            label = "latency-crossfade",
        ) { isTesting ->
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
                        delay != null && delay > 0 -> delay.toString() + "ms"
                        delay == -1L -> "超时"
                        delay != null && delay < 0 -> "失败"
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
