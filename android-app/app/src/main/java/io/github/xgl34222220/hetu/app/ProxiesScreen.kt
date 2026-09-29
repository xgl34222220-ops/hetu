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
    val sheetGroup = state.groups.firstOrNull { it.name == sheetGroupName }

    HxPage(
        title = "代理",
        scrollToTopSignal = vm.reselect,
        subtitle = if (state.running) visibleGroups.size.toString() + " 个策略组 · " + visibleGroups.sumOf { it.nodes.size } + " 个节点" else null,
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
                HxEmpty(Icons.Rounded.Hub, "代理未运行", "启动后即可查看策略组、切换节点和测速") {
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

        item(key = "strategy-heading") {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = Hx.gutter).padding(bottom = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("策略组", style = MaterialTheme.typography.labelLarge, color = Hx.colors.textMuted, modifier = Modifier.weight(1f))
                if (vm.testingAll) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        HxSpinner(13.dp)
                        Spacer(Modifier.width(6.dp))
                        Text("测速中", style = MaterialTheme.typography.labelMedium, color = Hx.colors.textMuted)
                    }
                } else {
                    Text(
                        "测速全部",
                        style = MaterialTheme.typography.labelMedium,
                        color = Hx.colors.accent,
                        modifier = Modifier.clip(Hx.chipShape).clickable { vm.testAll() }.padding(horizontal = 8.dp, vertical = 5.dp),
                    )
                }
            }
        }

        if (visibleGroups.isEmpty()) {
            item(key = "empty") {
                if (!state.panelReady && state.groups.isEmpty()) HxSkeletonRows(6)
                else HxEmpty(Icons.Rounded.SearchOff, if (query.isBlank()) "没有策略组" else "没有匹配结果")
            }
            return@HxPage
        }

        val rows = visibleGroups.chunked(2)
        items(
            count = rows.size,
            key = { index -> "strategy-row:" + index + ":" + rows[index].joinToString("|") { it.name } },
        ) { index ->
            val row = rows[index]
            Row(
                Modifier.fillMaxWidth().padding(horizontal = Hx.gutter).padding(bottom = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                row.forEach { group ->
                    StrategyDashboardCard(
                        vm = vm,
                        group = group,
                        modifier = Modifier.weight(1f),
                        onClick = { sheetGroupName = group.name },
                    )
                }
                if (row.size < 2) Spacer(Modifier.weight(1f))
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

    if (showApi) {
        ApiSettingsSheet(vm, onDismiss = { showApi = false })
    }
}

@Composable
private fun StrategyDashboardCard(
    vm: HetuViewModel,
    group: ProxyGroupUi,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val c = Hx.colors
    val source = remember(group.name) { MutableInteractionSource() }
    val currentNode = group.nodes.firstOrNull { it.name == group.now }
    val delay = vm.delays[group.now] ?: currentNode?.lastDelay
    val flag = refNodeFlag(group.now).takeIf { it.isNotBlank() }
    val testing = vm.testingGroups[group.name] == true

    Surface(
        modifier = modifier.hxPressScale(source, .985f),
        shape = RoundedCornerShape(17.dp),
        color = c.surface,
        border = BorderStroke(0.5.dp, c.line.copy(alpha = if (c.dark) .72f else .52f)),
        shadowElevation = 0.dp,
        onClick = onClick,
        interactionSource = source,
    ) {
        Column(
            Modifier.fillMaxWidth().heightIn(min = 118.dp).padding(horizontal = 13.dp, vertical = 12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    group.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = c.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    HxFormat.groupType(group.type).uppercase(Locale.ROOT),
                    style = MaterialTheme.typography.labelSmall,
                    color = c.textFaint,
                    maxLines = 1,
                )
            }
            Spacer(Modifier.height(10.dp))
            Text(
                listOfNotNull(flag, group.now.ifBlank { null }).joinToString(" "),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = c.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.weight(1f))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    group.nodes.size.toString() + " 个节点",
                    style = MaterialTheme.typography.labelSmall,
                    color = c.textMuted,
                    maxLines = 1,
                )
                Spacer(Modifier.weight(1f))
                HxDelayPill(delay, testing) { vm.testGroup(group) }
            }
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
    val nodes = projectStrategyNodes(group.nodes, options.sort, options.descending, vm.delays)
    val sections = if (options.providers) {
        nodes.groupBy { it.provider.ifBlank { "配置内节点" } }
    } else {
        linkedMapOf("" to nodes)
    }

    HxSheet(onDismiss = onDismiss) {
        Row(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(group.name, style = MaterialTheme.typography.titleLarge, color = c.text, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(2.dp))
                Text(
                    group.nodes.size.toString() + " 个节点 · 当前 " + group.now.ifBlank { "未选择" },
                    style = MaterialTheme.typography.bodySmall,
                    color = c.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (vm.testingGroups[group.name] == true) {
                Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) { HxSpinner(17.dp) }
            } else {
                HxBarAction(Icons.Rounded.Speed, "测速本组", onClick = { vm.testGroup(group) })
            }
        }

        LazyColumn(
            Modifier.fillMaxWidth().heightIn(min = 300.dp, max = 620.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 14.dp, end = 14.dp, top = 4.dp, bottom = 18.dp),
        ) {
            sections.forEach { (provider, members) ->
                if (provider.isNotBlank()) {
                    item(key = "provider:" + group.name + ":" + provider) {
                        Text(
                            provider,
                            style = MaterialTheme.typography.labelMedium,
                            color = c.textMuted,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
                        )
                    }
                }
                items(
                    items = members,
                    key = { node -> "sheet-node:" + group.name + ":" + node.name },
                ) { node ->
                    StrategySheetNodeRow(
                        vm = vm,
                        group = group,
                        node = node,
                        onSelected = onDismiss,
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
    onSelected: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = Hx.colors
    val selectable = HxFormat.isSelectable(group.type)
    val pending = vm.pendingSelection[group.name]
    val selected = group.now == node.name
    val delay = vm.delays[node.name] ?: node.lastDelay
    val testing = vm.testingNodes[node.name] == true
    val source = remember(node.name) { MutableInteractionSource() }
    val haptics = rememberHetuHaptics()
    val bg by animateColorAsState(
        if (selected) c.accentSoft.copy(alpha = if (c.dark) .34f else .52f) else c.surface,
        spring(stiffness = Spring.StiffnessMediumLow),
        label = "sheet-node-bg",
    )
    val outline by animateColorAsState(
        if (selected) c.accent.copy(alpha = .38f) else c.line.copy(alpha = .45f),
        spring(stiffness = Spring.StiffnessMediumLow),
        label = "sheet-node-outline",
    )

    Surface(
        modifier = modifier.fillMaxWidth().padding(vertical = 4.dp).hxPressScale(source, .988f),
        shape = RoundedCornerShape(15.dp),
        color = bg,
        border = BorderStroke(if (selected) 1.dp else 0.5.dp, outline),
        shadowElevation = 0.dp,
        enabled = selectable && pending == null && !selected,
        interactionSource = source,
        onClick = {
            haptics.perform(HetuHaptic.Tap)
            vm.select(group.name, node.name)
            onSelected()
        },
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.width(18.dp), contentAlignment = Alignment.CenterStart) {
                if (selected) Box(Modifier.size(8.dp).clip(CircleShape).background(c.accent))
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
                    listOf(node.type.uppercase(Locale.ROOT), node.provider).filter { it.isNotBlank() }.joinToString(" · "),
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
