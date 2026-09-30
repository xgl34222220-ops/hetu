package io.github.xgl34222220.hetu

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import io.github.xgl34222220.hetu.ui.HetuHaptic
import io.github.xgl34222220.hetu.ui.rememberHetuHaptics
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CloudSync
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material.icons.rounded.Rule
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun RulesScreen(vm: HetuViewModel, bottomPadding: Dp, forcedSection: String? = null) {
    val state = vm.state
    var section by rememberSaveable { mutableStateOf(forcedSection ?: "sets") }
    var query by rememberSaveable { mutableStateOf("") }
    var searching by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(section, state.running) {
        if (!state.running) return@LaunchedEffect
        when (section) {
            "sets" -> if (vm.ruleSets.isEmpty()) vm.loadRuleSets()
            "providers" -> vm.loadProviders()
            "rules" -> if (vm.rules.isEmpty()) vm.loadRules()
        }
    }

    val leadingAction: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {
        HxBarAction(if (searching) Icons.Rounded.SearchOff else Icons.Rounded.Search, "搜索", onClick = {
            searching = !searching
            if (!searching) query = ""
        })
    }
    val refreshAction: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {
        if (state.running) {
            when (section) {
                "providers" -> HxBarAction(Icons.Rounded.CloudSync, "更新全部订阅", onClick = vm::updateAllProviders, busy = vm.providersUpdatingAll, enabled = vm.providers.isNotEmpty())
                "sets" -> HxBarAction(Icons.Rounded.CloudSync, "更新全部规则集", onClick = vm::updateAllRuleSets, busy = vm.ruleSetsUpdatingAll, enabled = vm.ruleSets.isNotEmpty())
                else -> HxBarAction(Icons.Rounded.Refresh, "刷新规则", onClick = vm::loadRules, busy = vm.rulesLoading)
            }
        }
    }
    if (!state.running) {
        HxPage(title = "规则", scrollToTopSignal = vm.reselect, bottomPadding = bottomPadding) {
            item(key = "stopped") { HxEmpty(Icons.Rounded.Rule, "代理未运行", "规则、规则集和订阅由运行中的 Mihomo 提供，启动后可在此更新") }
        }
        return
    }
    if (forcedSection != null) {
        HxPage(
            title = "规则",
            subtitle = "规则集 ${vm.ruleSets.size} · 订阅 ${vm.providers.size} · 规则 ${if (vm.rules.isEmpty()) "—" else vm.rules.size.toString()}",
            bottomPadding = bottomPadding,
            refreshing = when (forcedSection) {
                "sets" -> vm.ruleSetsLoading && vm.ruleSets.isNotEmpty()
                "rules" -> vm.rulesLoading && vm.rules.isNotEmpty()
                else -> false
            },
            onRefresh = {
                when (forcedSection) {
                    "sets" -> vm.loadRuleSets()
                    "providers" -> vm.loadProviders()
                    else -> vm.loadRules()
                }
            },
            leadingActions = leadingAction,
            actions = refreshAction,
        ) {
            when (forcedSection) {
                "sets" -> ruleSetItems(vm, if (searching) query else "", searching) { query = it }
                "providers" -> providerListItems(vm, if (searching) query else "", searching) { query = it }
                else -> ruleItems(vm, if (searching) query else "", searching) { query = it }
            }
        }
        return
    }
    HxTabbedPage(
        title = "规则",
        tabs = listOf(HxPageTab("sets", "规则集"), HxPageTab("providers", "订阅"), HxPageTab("rules", "规则")),
        selected = section,
        onSelect = { section = it },
        scrollToTopSignal = vm.reselect,
        subtitle = "规则集 ${vm.ruleSets.size} · 订阅 ${vm.providers.size} · 规则 ${if (vm.rules.isEmpty()) "—" else vm.rules.size.toString()}",
        bottomPadding = bottomPadding,
        actions = refreshAction,
    ) { tab ->
        when (tab) {
            "sets" -> ruleSetItems(vm, query, true) { query = it }
            "providers" -> providerListItems(vm, query, true) { query = it }
            else -> ruleItems(vm, query, true) { query = it }
        }
    }
}

/* ---------------------------- rule sets ---------------------------- */

private fun LazyListScope.ruleSetItems(vm: HetuViewModel, query: String = "", showSearch: Boolean = false, onQuery: (String) -> Unit = {}) {
    if (showSearch) item(key = "sets-search") { HxSearchField(query, onQuery, "搜索规则集", Modifier.padding(horizontal = Hx.gutter).padding(bottom = 12.dp), autoFocus = true) }
    val sets = if (query.isBlank()) vm.ruleSets else vm.ruleSets.filter { it.name.contains(query, true) || it.behavior.contains(query, true) || it.format.contains(query, true) }
    val remote = sets.count { it.vehicleType.equals("HTTP", true) }
    if (sets.isEmpty() && vm.ruleSetsLoading) {
        item(key = "sets-loading") { HxSkeletonRows(6) }
    }
    if (sets.isEmpty() && !vm.ruleSetsLoading) {
        item(key = "sets-empty") { HxEmpty(Icons.Rounded.Rule, "当前配置没有规则集", "rule-providers 为空时这里不会有内容") }
    }
    itemsIndexed(sets, key = { _, item -> "rs:" + item.name }) { _, set ->
        RuleSetCard(
            set = set,
            task = vm.ruleSetTasks[set.name],
            onUpdate = { vm.updateRuleSet(set.name) },
            modifier = Modifier.animateItem(fadeInSpec = tween(HxMotion.Medium), placementSpec = HxMotion.glide(), fadeOutSpec = null),
        )
    }
}

@Composable
private fun RuleSetCard(set: DashboardRuleSetUi, task: HxTask?, onUpdate: () -> Unit, modifier: Modifier = Modifier) {
    val c = Hx.colors
    val haptics = rememberHetuHaptics()
    val remote = set.vehicleType.equals("HTTP", true)
    val shape = RoundedCornerShape(22.dp)
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = Hx.gutter)
            .padding(bottom = 10.dp)
            .hxSoftShadow(shape, 3.dp)
            .clip(shape)
            .background(c.surface)
            .padding(start = 16.dp, end = 10.dp, top = 13.dp, bottom = 13.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        set.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                        color = c.text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Spacer(Modifier.width(7.dp))
                    Text(
                        "${HxFormat.count(set.ruleCount.toLong())} 条规则",
                        style = MaterialTheme.typography.labelLarge.merge(HxNumberStyle),
                        color = c.accent,
                        maxLines = 1,
                    )
                }
                Spacer(Modifier.height(3.dp))
                Text(
                    listOf(set.behavior.ifBlank { "Rule" }, set.format, set.vehicleType.ifBlank { "LOCAL" })
                        .filter { it.isNotBlank() }
                        .joinToString(" / "),
                    style = MaterialTheme.typography.bodySmall,
                    color = c.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    if (set.updatedAt.isBlank()) "本地规则集" else "更新于 ${HxFormat.isoAgo(set.updatedAt)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = c.textMuted,
                    maxLines = 1,
                )
            }
            if (remote) {
                Box(
                    Modifier.size(42.dp).clip(CircleShape).clickable(enabled = task?.running != true) {
                        haptics.perform(HetuHaptic.Tap)
                        onUpdate()
                    },
                    contentAlignment = Alignment.Center,
                ) {
                    AnimatedContent(
                        targetState = when {
                            task?.running == true -> 1
                            task?.ok == true -> 2
                            task?.ok == false -> 3
                            else -> 0
                        },
                        transitionSpec = {
                            (fadeIn(tween(HxMotion.Short)) + scaleIn(HxMotion.pop(), initialScale = .5f))
                                .togetherWith(fadeOut(tween(100)) + scaleOut(tween(HxMotion.Short), targetScale = .6f))
                        },
                        contentAlignment = Alignment.Center,
                        label = "ruleSetState",
                    ) { phase ->
                        when (phase) {
                            1 -> HxSpinningSync(spinning = true, tint = c.accent)
                            2 -> Icon(Icons.Rounded.CheckCircle, "已更新", tint = c.good, modifier = Modifier.size(21.dp))
                            3 -> Icon(Icons.Rounded.ErrorOutline, "更新失败", tint = c.bad, modifier = Modifier.size(21.dp))
                            else -> Icon(Icons.Rounded.CloudDownload, "更新", tint = c.accent, modifier = Modifier.size(23.dp))
                        }
                    }
                }
            }
        }
        AnimatedVisibility(
            visible = task != null && task.ok == false && task.message.isNotBlank(),
            enter = fadeIn(tween(HxMotion.Medium)) + expandVertically(tween(HxMotion.Medium, easing = HxMotion.Emphasized)),
            exit = fadeOut(tween(HxMotion.Short)) + shrinkVertically(tween(HxMotion.Short)),
        ) {
            Text(task?.message.orEmpty(), style = MaterialTheme.typography.bodySmall, color = c.bad, maxLines = 2, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

/* ---------------------------- providers ---------------------------- */

internal fun LazyListScope.providerListItems(vm: HetuViewModel, query: String = "", showSearch: Boolean = false, onQuery: (String) -> Unit = {}) {
    if (showSearch) item(key = "providers-search") { HxSearchField(query, onQuery, "搜索订阅", Modifier.padding(horizontal = Hx.gutter).padding(bottom = 12.dp), autoFocus = true) }
    val providers = if (query.isBlank()) vm.providers else vm.providers.filter { p -> p.name.contains(query, true) || p.nodes.any { it.contains(query, true) } }
    if (providers.isEmpty()) {
        item(key = "providers-empty") { HxEmpty(Icons.Rounded.CloudSync, "没有在线订阅", "在「设置 → 配置与订阅」中添加订阅链接") }
    }
    itemsIndexed(providers, key = { _, item -> "pv:" + item.name }) { _, p ->
        ProviderCard(
            p = p,
            task = vm.providerTasks[p.name],
            onUpdate = { vm.updateProvider(p.name) },
            modifier = Modifier.animateItem(fadeInSpec = tween(HxMotion.Medium), placementSpec = HxMotion.glide(), fadeOutSpec = null),
        )
    }
}

/** Subscription card: usage percentage, expiry, upload / download / remaining and a bar. */
@Composable
private fun ProviderCard(p: DashboardProviderUi, task: HxTask?, onUpdate: () -> Unit, modifier: Modifier = Modifier) {
    val c = Hx.colors
    val haptics = rememberHetuHaptics()
    val info = p.hasSubscriptionInfo && p.total > 0L
    val percent = if (info) (p.ratio * 100).toInt() else null
    val shape = RoundedCornerShape(22.dp)
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = Hx.gutter)
            .padding(bottom = 12.dp)
            .hxSoftShadow(shape, 3.dp)
            .clip(shape)
            .background(c.surface)
            .padding(start = 17.dp, end = 12.dp, top = 15.dp, bottom = 15.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                p.name,
                fontSize = 18.sp,
                lineHeight = 22.sp,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                color = c.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (percent != null) {
                val tone = when {
                    p.ratio > .9f -> HxTone.Bad
                    p.ratio > .75f -> HxTone.Warn
                    else -> HxTone.Accent
                }
                HxPill("$percent%", tone)
                Spacer(Modifier.width(3.dp))
            }
            Box(
                Modifier.size(42.dp).clip(CircleShape).clickable(enabled = task?.running != true) {
                    haptics.perform(HetuHaptic.Tap)
                    onUpdate()
                },
                contentAlignment = Alignment.Center,
            ) {
                AnimatedContent(
                    targetState = when {
                        task?.running == true -> 1
                        task?.ok == true -> 2
                        task?.ok == false -> 3
                        else -> 0
                    },
                    transitionSpec = {
                        (fadeIn(tween(HxMotion.Short)) + scaleIn(HxMotion.pop(), initialScale = .5f))
                            .togetherWith(fadeOut(tween(100)) + scaleOut(tween(HxMotion.Short), targetScale = .6f))
                    },
                    contentAlignment = Alignment.Center,
                    label = "providerState",
                ) { phase ->
                    when (phase) {
                        1 -> HxSpinningSync(spinning = true, tint = c.accent)
                        2 -> Icon(Icons.Rounded.CheckCircle, "已更新", tint = c.good, modifier = Modifier.size(21.dp))
                        3 -> Icon(Icons.Rounded.ErrorOutline, "更新失败", tint = c.bad, modifier = Modifier.size(21.dp))
                        else -> Icon(Icons.Rounded.Sync, "更新", tint = c.text, modifier = Modifier.size(23.dp))
                    }
                }
            }
        }
        Row(Modifier.padding(end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (info) "到期 ${HxFormat.expireLabel(p.expire)}" else "${p.nodes.size} 个节点",
                style = MaterialTheme.typography.bodySmall.merge(HxNumberStyle),
                color = c.textMuted,
                modifier = Modifier.weight(1f),
            )
            Text("更新于 ${HxFormat.isoAgo(p.updatedAt)}", style = MaterialTheme.typography.bodySmall.merge(HxNumberStyle), color = c.textMuted)
        }
        if (info) {
            Spacer(Modifier.height(12.dp))
            Row(Modifier.padding(end = 6.dp)) {
                ProviderFigure(HxFormat.bytes(p.upload), "上传", c.text, Modifier.weight(1f))
                ProviderFigure(HxFormat.bytes(p.download), "下载", c.text, Modifier.weight(1f))
                ProviderFigure(HxFormat.bytes(p.remaining), "剩余", c.accent, Modifier.weight(1f))
            }
            Spacer(Modifier.height(10.dp))
            HxProgressBar(p.ratio, if (p.ratio > .9f) c.bad else c.accent, Modifier.padding(end = 6.dp), height = 4.dp)
            Spacer(Modifier.height(7.dp))
            Row(Modifier.padding(end = 6.dp)) {
                Text("已用 ${HxFormat.bytes(p.used)}", style = MaterialTheme.typography.bodySmall.merge(HxNumberStyle), color = c.textMuted, modifier = Modifier.weight(1f))
                Text("总计 ${HxFormat.bytes(p.total)}", style = MaterialTheme.typography.bodySmall.merge(HxNumberStyle), color = c.textMuted)
            }
        }
        AnimatedVisibility(
            visible = task != null && task.ok == false && task.message.isNotBlank(),
            enter = fadeIn(tween(HxMotion.Medium)) + expandVertically(tween(HxMotion.Medium, easing = HxMotion.Emphasized)),
            exit = fadeOut(tween(HxMotion.Short)) + shrinkVertically(tween(HxMotion.Short)),
        ) {
            Text(task?.message.orEmpty(), style = MaterialTheme.typography.bodySmall, color = c.bad, maxLines = 2, modifier = Modifier.padding(top = 8.dp))
        }
    }
}

@Composable
private fun ProviderFigure(value: String, label: String, color: Color, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            value,
            fontSize = 18.sp,
            lineHeight = 21.sp,
            fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
            color = color,
            maxLines = 1,
        )
        Text(label, style = MaterialTheme.typography.labelSmall, color = Hx.colors.textMuted)
    }
}

/* ---------------------------- rules ---------------------------- */

private fun LazyListScope.ruleItems(vm: HetuViewModel, query: String, showSearch: Boolean = true, onQuery: (String) -> Unit) {
    if (showSearch) item(key = "rules-search") {
        HxSearchField(query, onQuery, "搜索规则内容或策略", Modifier.padding(horizontal = Hx.gutter).padding(bottom = 12.dp), autoFocus = true)
    }
    val list = if (query.isBlank()) vm.rules else vm.rules.filter {
        it.payload.contains(query, true) || it.proxy.contains(query, true) || it.type.contains(query, true)
    }
    if (vm.rulesLoading && vm.rules.isEmpty()) {
        item(key = "rules-loading") { HxSkeletonRows(8) }
    } else if (list.isEmpty()) {
        item(key = "rules-empty") { HxEmpty(Icons.Rounded.Rule, if (vm.rules.isEmpty()) "没有规则" else "没有匹配的规则") }
    }
    itemsIndexed(list, key = { _, rule -> "r:" + rule.index }) { index, rule ->
        Box(Modifier.animateItem(fadeInSpec = tween(HxMotion.Medium), placementSpec = HxMotion.glide(), fadeOutSpec = null)) {
            RuleRow(rule, first = index == 0, last = index == list.lastIndex)
        }
    }
}

@Composable
private fun RuleRow(rule: ProxyRuleUi, first: Boolean, last: Boolean) {
    val c = Hx.colors
    val context = LocalContext.current
    val shape = RoundedCornerShape(22.dp)
    val proxyColor = when {
        rule.proxy == "DIRECT" -> c.good
        rule.proxy.startsWith("REJECT") -> c.accent
        else -> c.accent
    }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Hx.gutter)
            .padding(bottom = 9.dp)
            .hxSoftShadow(shape, 2.dp)
            .clip(shape)
            .background(c.surface)
            .hxCombinedClick(
                onLongClick = { hxCopy(context, "规则", listOf(rule.type, rule.payload, rule.proxy).filter { it.isNotBlank() }.joinToString(",")) },
                onClick = {},
            )
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                rule.type,
                fontSize = 16.sp,
                lineHeight = 19.sp,
                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                color = if (rule.disabled) c.textFaint else c.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (rule.payload.isNotBlank()) {
                Text(
                    rule.payload,
                    style = MaterialTheme.typography.bodyMedium,
                    color = c.textMuted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Text(
            rule.proxy,
            fontSize = 16.sp,
            lineHeight = 19.sp,
            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
            color = proxyColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 112.dp),
        )
    }
}

/* ---------------------------- shared ---------------------------- */

@Composable
private fun UpdateAllBar(summary: String, label: String, busy: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val c = Hx.colors
    val haptics = rememberHetuHaptics()
    val source = remember { MutableInteractionSource() }
    val bg by animateColorAsState(
        when {
            busy -> c.surfaceMuted
            enabled -> c.accent
            else -> c.surfaceMuted
        },
        tween(HxMotion.Medium),
        label = "updateAllBg",
    )
    val fg by animateColorAsState(if (enabled && !busy) c.onAccent else c.textMuted, tween(HxMotion.Medium), label = "updateAllFg")
    Row(
        Modifier.fillMaxWidth().padding(horizontal = Hx.gutter).padding(bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(summary, style = MaterialTheme.typography.bodySmall, color = c.textMuted, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(8.dp))
        Row(
            Modifier
                .hxPressScale(source, .95f)
                .height(32.dp)
                .clip(Hx.pillShape)
                .background(bg)
                .clickable(interactionSource = source, indication = LocalIndication.current, enabled = enabled && !busy) {
                    haptics.perform(HetuHaptic.Confirm)
                    onClick()
                }
                .animateContentSize(tween(HxMotion.Short, easing = HxMotion.Emphasized))
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HxSpinningSync(spinning = busy, tint = fg, size = 15.dp)
            Spacer(Modifier.width(5.dp))
            Text(if (busy) "更新中" else label, style = MaterialTheme.typography.labelMedium, color = fg)
        }
    }
}

/** A sync glyph that keeps turning while work is in flight and eases to rest after. */
@Composable
internal fun HxSpinningSync(spinning: Boolean, tint: Color, size: Dp = 20.dp) {
    val angle = remember { Animatable(0f) }
    LaunchedEffect(spinning) {
        if (spinning) {
            while (true) {
                angle.animateTo(angle.value + 360f, tween(900, easing = LinearEasing))
            }
        } else {
            val rest = (kotlin.math.ceil(angle.value / 180f) * 180f)
            angle.animateTo(rest, spring(dampingRatio = .7f, stiffness = Spring.StiffnessMediumLow))
        }
    }
    Icon(Icons.Rounded.Sync, null, tint = tint, modifier = Modifier.size(size).graphicsLayer { rotationZ = angle.value })
}

@Composable
internal fun TaskRow(
    title: String,
    subtitle: String,
    task: HxTask?,
    canRun: Boolean,
    onRun: () -> Unit,
    modifier: Modifier = Modifier,
    progress: Float? = null,
    first: Boolean = false,
    last: Boolean = false,
) {
    val c = Hx.colors
    val shape = RoundedCornerShape(
        topStart = if (first) 20.dp else 0.dp,
        topEnd = if (first) 20.dp else 0.dp,
        bottomStart = if (last) 20.dp else 0.dp,
        bottomEnd = if (last) 20.dp else 0.dp,
    )
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = Hx.gutter)
            .padding(bottom = if (last) 12.dp else 0.dp)
            .clip(shape)
            .background(c.surface),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 13.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = c.textMuted)
                AnimatedVisibility(
                    visible = task != null && task.ok == false && task.message.isNotBlank(),
                    enter = fadeIn(tween(HxMotion.Medium)) + expandVertically(tween(HxMotion.Medium, easing = HxMotion.Emphasized)),
                    exit = fadeOut(tween(HxMotion.Short)) + shrinkVertically(tween(HxMotion.Short)),
                ) {
                    Text(task?.message.orEmpty(), style = MaterialTheme.typography.bodySmall, color = c.bad, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                if (progress != null) HxProgressBar(progress, if (progress > .9f) c.bad else c.accent, Modifier.padding(top = 7.dp, end = 8.dp), height = 3.dp)
            }
            if (canRun) {
                val haptics = rememberHetuHaptics()
                Box(
                    Modifier.size(40.dp).clip(CircleShape).clickable(enabled = task?.running != true) {
                        haptics.perform(HetuHaptic.Tap)
                        onRun()
                    },
                    contentAlignment = Alignment.Center,
                ) {
                    AnimatedContent(
                        targetState = when {
                            task?.running == true -> 1
                            task?.ok == true -> 2
                            task?.ok == false -> 3
                            else -> 0
                        },
                        transitionSpec = {
                            (fadeIn(tween(HxMotion.Short)) + scaleIn(HxMotion.pop(), initialScale = .5f))
                                .togetherWith(fadeOut(tween(100)) + scaleOut(tween(HxMotion.Short), targetScale = .6f))
                        },
                        contentAlignment = Alignment.Center,
                        label = "taskState",
                    ) { phase ->
                        when (phase) {
                            1 -> HxSpinningSync(spinning = true, tint = c.accent)
                            2 -> Icon(Icons.Rounded.CheckCircle, "已更新", tint = c.good, modifier = Modifier.size(20.dp))
                            3 -> Icon(Icons.Rounded.ErrorOutline, "更新失败", tint = c.bad, modifier = Modifier.size(20.dp))
                            else -> Icon(Icons.Rounded.Sync, "更新", tint = c.textMuted, modifier = Modifier.size(20.dp))
                        }
                    }
                }
            } else {
                Text("本地", style = MaterialTheme.typography.labelSmall, color = c.textFaint, modifier = Modifier.padding(end = 8.dp))
            }
        }
        if (!last) HxDivider(16.dp)
    }
}

