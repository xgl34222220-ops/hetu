package io.github.xgl34222220.hetu

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
    var expanded by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var showOptions by remember { mutableStateOf(false) }
    var showApi by remember { mutableStateOf(false) }

    val allGroups = state.groups
    val visibleGroups = panelGroups11(allGroups, query, state.trafficMode, options)
    val q = query.trim()
    val stagger = rememberHxStagger()
    val fontScale = LocalDensity.current.fontScale
    val columns = if (fontScale > 1.3f) 1 else options.columns
    val groupColumns = if (fontScale > 1.3f) 1 else 2

    HxPage(
        title = "代理", scrollToTopSignal = vm.reselect,
        subtitle = if (state.running) "${visibleGroups.size} 个策略组 · ${visibleGroups.sumOf { it.nodes.size }} 个节点" else null,
        bottomPadding = bottomPadding,
        actions = {
            if (state.running) {
                HxBarAction(if (searching) Icons.Rounded.SearchOff else Icons.Rounded.Search, "搜索", onClick = {
                    searching = !searching
                    if (!searching) query = ""
                })
                HxBarAction(Icons.Rounded.Tune, "显示与排序", onClick = { showOptions = true })
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
                HxSearchField(query, { query = it }, "搜索策略组、节点、订阅或协议", Modifier.padding(horizontal = Hx.gutter).padding(bottom = 12.dp))
            }
        }
        item(key = "group-heading") {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = Hx.gutter).padding(bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("策略组", style = MaterialTheme.typography.labelLarge, color = Hx.colors.textMuted, modifier = Modifier.weight(1f))
                HxButton(
                    if (vm.testingAll) "测速中" else "测速全部",
                    onClick = vm::testAll,
                    icon = Icons.Rounded.Speed,
                    busy = vm.testingAll,
                    filled = false,
                )
            }
        }
        if (visibleGroups.isEmpty()) {
            item(key = "empty") {
                if (!state.panelReady && allGroups.isEmpty()) {
                    HxSkeletonRows(6)
                } else {
                    HxEmpty(Icons.Rounded.SearchOff, if (q.isBlank()) "没有策略组" else "没有匹配的节点")
                }
            }
        }
        val groupRows = visibleGroups.chunked(groupColumns)
        groupRows.forEachIndexed { rowIndex, groupRow ->
            item(key = "group-row:$rowIndex:${groupRow.joinToString("|") { it.name }}") {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = Hx.gutter).padding(bottom = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    groupRow.forEachIndexed { columnIndex, group ->
                        val open = q.isNotBlank() || group.name in expanded
                        GroupHeader(
                            vm = vm, group = group, open = open,
                            modifier = Modifier.weight(1f).hxEnter(stagger, rowIndex * groupColumns + columnIndex),
                            onToggle = {
                                expanded = panelExpanded11(expanded.toSet(), group.name, options.collapsePrevious).toList()
                            },
                        )
                    }
                    if (groupRow.size < groupColumns) Spacer(Modifier.weight(1f))
                }
            }

            groupRow.forEach { group ->
                val open = q.isNotBlank() || group.name in expanded
                if (open) {
                    val nodes = projectStrategyNodes(group.nodes, options.sort, options.descending, vm.delays)
                    val sections = if (options.providers) nodes.groupBy { it.provider.ifBlank { "配置内节点" } } else linkedMapOf("" to nodes)
                    item(key = "expanded-label:${group.name}") {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = Hx.gutter + 4.dp).padding(top = 2.dp, bottom = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(group.name, style = MaterialTheme.typography.labelLarge, color = Hx.colors.text)
                            Spacer(Modifier.width(8.dp))
                            HxPill("${nodes.size} 个节点", HxTone.Neutral)
                        }
                    }
                    sections.forEach { (provider, members) ->
                        if (provider.isNotEmpty()) {
                            item(key = "p:${group.name}:$provider") {
                                Text(
                                    provider,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = Hx.colors.textMuted,
                                    modifier = Modifier.animateItem(fadeInSpec = tween(HxMotion.Medium), placementSpec = null, fadeOutSpec = null)
                                        .padding(horizontal = Hx.gutter + 4.dp).padding(top = 4.dp, bottom = 6.dp),
                                )
                            }
                        }
                        val rows = members.chunked(columns)
                        items(rows.size, key = { index -> "n:${group.name}:$provider:$index:${rows[index].first().name}" }) { index ->
                            val row = rows[index]
                            Row(
                                Modifier.animateItem(fadeInSpec = tween(HxMotion.Medium), placementSpec = null, fadeOutSpec = null)
                                    .fillMaxWidth().padding(horizontal = Hx.gutter)
                                    .padding(bottom = if (options.compact) 6.dp else 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                row.forEach { node -> NodeTile(vm, group, node, options.compact, Modifier.weight(1f)) }
                                if (row.size < columns) Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                    item(key = "gap:${group.name}") { Spacer(Modifier.height(10.dp)) }
                }
            }
        }

    }

    if (showOptions) {
        HxSheet(onDismiss = { showOptions = false }, title = "显示与排序") {
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("节点排序", style = MaterialTheme.typography.labelLarge, color = Hx.colors.textMuted)
                HxSegmented(
                    options = listOf("config" to "配置顺序", "latency" to "延迟", "name" to "名称"),
                    selected = if (options.sort == "delay") "latency" else options.sort,
                    onSelect = { options.copy(sort = it).save(vm.prefs) },
                )
                Text("每行节点", style = MaterialTheme.typography.labelLarge, color = Hx.colors.textMuted)
                HxSegmented(
                    options = listOf("1" to "单列", "2" to "双列"),
                    selected = options.columns.toString(),
                    onSelect = { options.copy(columns = it.toInt()).save(vm.prefs) },
                )
                HxGroup {
                    HxSwitchRow("倒序", options.descending, { options.copy(descending = it).save(vm.prefs) }, subtitle = "对名称和延迟排序生效")
                    HxDivider(16.dp)
                    HxSwitchRow("紧凑卡片", options.compact, { options.copy(compact = it).save(vm.prefs) })
                    HxDivider(16.dp)
                    HxSwitchRow("按订阅分组节点", options.providers, { options.copy(providers = it).save(vm.prefs) })
                    HxDivider(16.dp)
                    HxSwitchRow("展开时收起其它组", options.collapsePrevious, { options.copy(collapsePrevious = it).save(vm.prefs) })
                    HxDivider(16.dp)
                    HxSwitchRow("显示隐藏的策略组", options.showHidden, { options.copy(showHidden = it).save(vm.prefs) })
                }
                HxGroup {
                    HxNavRow("更多策略显示设置", subtitle = "名称溢出、组列数、弹出面板行为等") {
                        showOptions = false
                        context.startActivity(Intent(context, ProxySelectorPreferencesActivity::class.java))
                    }
                    HxDivider(16.dp)
                    HxNavRow("策略图标", subtitle = "为策略组设置自定义图标") {
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
private fun GroupHeader(vm: HetuViewModel, group: ProxyGroupUi, open: Boolean, modifier: Modifier, onToggle: () -> Unit) {
    val c = Hx.colors
    val rotation by animateFloatAsState(if (open) 180f else 0f, tween(HxMotion.Medium, easing = HxMotion.Emphasized), label = "chevron")
    val testing = vm.testingGroups[group.name] == true
    val pending = vm.pendingSelection[group.name]
    val nowDelay = vm.delays[group.now]
    val source = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(20.dp)

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .hxPressScale(source, .975f)
            .hxSoftShadow(shape, if (open) 8.dp else 4.dp),
        shape = shape,
        color = if (open) c.accentSoft.copy(alpha = if (c.dark) .34f else .52f) else c.surface,
        border = if (c.dark) BorderStroke(0.5.dp, c.line.copy(alpha = .72f)) else null,
    ) {
        Column(
            Modifier
                .clickable(interactionSource = source, indication = LocalIndication.current, onClick = onToggle)
                .padding(horizontal = 14.dp, vertical = 13.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(34.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (open) c.surface.copy(alpha = .86f) else c.surfaceMuted),
                    contentAlignment = Alignment.Center,
                ) {
                    HxGroupIcon(group, Modifier.size(20.dp))
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        group.name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = c.text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(1.dp))
                    Text(
                        HxFormat.groupType(group.type),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (open) c.accent else c.textFaint,
                        maxLines = 1,
                    )
                }
                Icon(
                    Icons.Rounded.ExpandMore,
                    contentDescription = if (open) "收起" else "展开",
                    tint = if (open) c.accent else c.textFaint,
                    modifier = Modifier.size(20.dp).graphicsLayer { rotationZ = rotation },
                )
            }

            Spacer(Modifier.height(12.dp))
            if (pending != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    HxSpinner(11.dp)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "切换到 $pending…",
                        style = MaterialTheme.typography.bodySmall,
                        color = c.accent,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            } else {
                Text(
                    group.now.ifBlank { "未选择节点" },
                    style = MaterialTheme.typography.bodySmall,
                    color = c.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${group.nodes.size} 节点",
                    style = MaterialTheme.typography.labelSmall,
                    color = c.textFaint,
                )
                if (nowDelay != null && pending == null) {
                    Spacer(Modifier.width(8.dp))
                    Box(Modifier.size(3.dp).clip(CircleShape).background(c.line))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        HxFormat.delay(nowDelay) + if (nowDelay > 0) " ms" else "",
                        style = MaterialTheme.typography.labelSmall.merge(HxNumberStyle),
                        color = HxFormat.delayColor(nowDelay),
                    )
                }
                Spacer(Modifier.weight(1f))
                Box(
                    Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(if (testing) c.accentSoft else Color.Transparent)
                        .clickable(enabled = !testing) { vm.testGroup(group) },
                    contentAlignment = Alignment.Center,
                ) {
                    if (testing) HxSpinner(13.dp)
                    else Icon(Icons.Rounded.Bolt, "测速 ${group.name}", tint = c.textMuted, modifier = Modifier.size(17.dp))
                }
            }
        }
    }
}

@Composable
private fun NodeTile(vm: HetuViewModel, group: ProxyGroupUi, node: ProxyNodeUi, compact: Boolean, modifier: Modifier) {
    val c = Hx.colors
    val selectable = HxFormat.isSelectable(group.type)
    val pending = vm.pendingSelection[group.name]
    val selected = if (pending != null) pending == node.name else group.now == node.name
    val delay = vm.delays[node.name] ?: node.lastDelay
    val testing = vm.testingNodes[node.name] == true
    val bg by animateColorAsState(if (selected) c.accentSoft else c.surface, tween(HxMotion.Medium), label = "nodeBg")
    val border by animateColorAsState(if (selected) c.accent else if (c.dark) c.line else Color.Transparent, tween(HxMotion.Medium), label = "nodeBorder")
    val source = remember { MutableInteractionSource() }
    val haptics = rememberHetuHaptics()
    Column(
        modifier
            .hxPressScale(source, .96f)
            .clip(Hx.rowShape)
            .background(bg)
            .border(if (selected) 1.dp else 0.5.dp, border, Hx.rowShape)
            .clickable(interactionSource = source, indication = LocalIndication.current, enabled = selectable && pending == null && !selected) {
                haptics.perform(HetuHaptic.Tap)
                vm.select(group.name, node.name)
            }
            .heightIn(min = if (compact) 48.dp else 64.dp)
            .padding(horizontal = 12.dp, vertical = if (compact) 7.dp else 10.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Text(
                node.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = c.text,
                maxLines = if (compact) 1 else 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            AnimatedVisibility(
                selected,
                enter = scaleIn(androidx.compose.animation.core.spring(dampingRatio = .45f, stiffness = 500f), initialScale = .3f) + fadeIn(tween(HxMotion.Short)),
                exit = scaleOut(tween(HxMotion.Short), targetScale = .5f) + fadeOut(tween(HxMotion.Short)),
            ) {
                if (pending == node.name) HxSpinner(14.dp)
                else Box(Modifier.padding(start = 4.dp).size(18.dp).clip(CircleShape).background(c.accent), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Check, null, tint = c.onAccent, modifier = Modifier.size(12.dp))
                }
            }
        }
        Spacer(Modifier.height(if (compact) 2.dp else 6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                listOf(node.type, if (node.udp) "UDP" else "").filter { it.isNotBlank() }.joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = c.textFaint,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            HxDelayPill(delay, testing) { vm.testNode(node.name) }
        }
    }
}

/** Tinted latency chip: colour follows quality, digits roll to new values, tap to re-test. */
@Composable
internal fun HxDelayPill(delay: Long?, testing: Boolean, onClick: () -> Unit) {
    val c = Hx.colors
    val tone = when {
        delay == null || delay == 0L -> HxTone.Neutral
        delay < 0L -> HxTone.Bad
        delay < 300L -> HxTone.Good
        delay < 800L -> HxTone.Warn
        else -> HxTone.Bad
    }
    val bg by animateColorAsState(tone.bg(), tween(HxMotion.Medium), label = "delayBg")
    val fg by animateColorAsState(if (tone == HxTone.Neutral) c.textFaint else tone.fg(), tween(HxMotion.Medium), label = "delayFg")
    Box(
        Modifier
            .clip(Hx.pillShape)
            .background(bg)
            .clickable(enabled = !testing, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 3.dp)
            .animateContentSize(tween(HxMotion.Medium, easing = HxMotion.Emphasized)),
        contentAlignment = Alignment.Center,
    ) {
        if (testing) {
            HxSpinner(11.dp, fg)
        } else {
            val text = when {
                delay != null && delay > 0 -> "$delay"
                delay == -1L -> "超时"
                delay != null && delay < 0 -> "失败"
                else -> "测速"
            }
            Row(verticalAlignment = Alignment.Bottom) {
                HxRollingText(text, MaterialTheme.typography.labelMedium, fg)
                if (delay != null && delay > 0) Text("ms", style = MaterialTheme.typography.labelSmall, color = fg.copy(alpha = .75f))
            }
        }
    }
}