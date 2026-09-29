package io.github.xgl34222220.hetu

import androidx.compose.animation.AnimatedContent
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
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Refresh
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

@Composable
internal fun RulesScreen(vm: HetuViewModel, bottomPadding: Dp) {
    val state = vm.state
    var section by rememberSaveable { mutableStateOf("sets") }
    var query by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(section, state.running) {
        if (!state.running) return@LaunchedEffect
        when (section) {
            "sets" -> if (vm.ruleSets.isEmpty()) vm.loadRuleSets()
            "providers" -> vm.loadProviders()
            "rules" -> if (vm.rules.isEmpty()) vm.loadRules()
        }
    }

    HxPage(
        title = "规则", scrollToTopSignal = vm.reselect,
        subtitle = if (state.running) "规则集 ${vm.ruleSets.size} · 订阅 ${vm.providers.size} · 规则 ${if (vm.rules.isEmpty()) "—" else vm.rules.size.toString()}" else null,
        bottomPadding = bottomPadding,
        actions = {
            if (state.running) {
                HxBarAction(Icons.Rounded.Refresh, "刷新列表", onClick = {
                    when (section) {
                        "sets" -> vm.loadRuleSets()
                        "providers" -> vm.loadProviders()
                        else -> vm.loadRules()
                    }
                }, busy = (section == "sets" && vm.ruleSetsLoading) || (section == "rules" && vm.rulesLoading))
            }
        },
    ) {
        if (!state.running) {
            item(key = "stopped") { HxEmpty(Icons.Rounded.Rule, "代理未运行", "规则、规则集和订阅由运行中的 Mihomo 提供，启动后可在此更新") }
            return@HxPage
        }
        item(key = "segments") {
            HxSegmented(
                options = listOf("sets" to "规则集", "providers" to "订阅", "rules" to "规则"),
                selected = section,
                onSelect = { section = it; query = "" },
                modifier = Modifier.padding(horizontal = Hx.gutter).padding(bottom = 14.dp),
            )
        }
        when (section) {
            "sets" -> ruleSetItems(vm)
            "providers" -> providerListItems(vm)
            else -> ruleItems(vm, query) { query = it }
        }
    }
}

/* ---------------------------- rule sets ---------------------------- */

private fun LazyListScope.ruleSetItems(vm: HetuViewModel) {
    val sets = vm.ruleSets
    val remote = sets.count { it.vehicleType.equals("HTTP", true) }
    item(key = "sets-head") {
        UpdateAllBar(
            summary = if (sets.isEmpty()) "暂无规则集" else "共 ${sets.size} 个，$remote 个可在线更新",
            label = "全部更新",
            busy = vm.ruleSetsUpdatingAll,
            enabled = remote > 0,
            onClick = vm::updateAllRuleSets,
        )
    }
    if (sets.isEmpty() && vm.ruleSetsLoading) {
        item(key = "sets-loading") { HxSkeletonRows(6) }
    }
    if (sets.isEmpty() && !vm.ruleSetsLoading) {
        item(key = "sets-empty") { HxEmpty(Icons.Rounded.Rule, "当前配置没有规则集", "rule-providers 为空时这里不会有内容") }
    }
    itemsIndexed(sets, key = { _, item -> "rs:" + item.name }) { index, set ->
        val remoteSet = set.vehicleType.equals("HTTP", true)
        TaskRow(
            modifier = Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null),
            first = index == 0,
            last = index == sets.lastIndex,
            title = set.name,
            subtitle = listOf(
                set.behavior.ifBlank { "rule" },
                set.format,
                "${HxFormat.count(set.ruleCount.toLong())} 条",
                if (remoteSet) HxFormat.isoAgo(set.updatedAt) else if (set.vehicleType.isBlank()) "本地" else set.vehicleType,
            ).filter { it.isNotBlank() }.joinToString(" · "),
            task = vm.ruleSetTasks[set.name],
            canRun = remoteSet,
            onRun = { vm.updateRuleSet(set.name) },
        )
    }
}

/* ---------------------------- providers ---------------------------- */

internal fun LazyListScope.providerListItems(vm: HetuViewModel) {
    val providers = vm.providers
    item(key = "providers-head") {
        UpdateAllBar(
            summary = if (providers.isEmpty()) "当前配置没有在线订阅" else "共 ${providers.size} 个订阅 · ${providers.sumOf { it.nodes.size }} 个节点",
            label = "全部更新",
            busy = vm.providersUpdatingAll,
            enabled = providers.isNotEmpty(),
            onClick = vm::updateAllProviders,
        )
    }
    if (providers.isEmpty()) {
        item(key = "providers-empty") { HxEmpty(Icons.Rounded.CloudSync, "没有在线订阅", "在「设置 → 配置与订阅」中添加订阅链接") }
    }
    itemsIndexed(providers, key = { _, item -> "pv:" + item.name }) { index, p ->
        Column(Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null)) {
            TaskRow(
                first = index == 0,
                last = index == providers.lastIndex,
                title = p.name,
                subtitle = buildString {
                    append("${p.nodes.size} 个节点 · ")
                    append(HxFormat.isoAgo(p.updatedAt))
                    if (p.hasSubscriptionInfo && p.total > 0L) {
                        append("\n已用 ${HxFormat.bytes(p.used)} / ${HxFormat.bytes(p.total)} · ${HxFormat.expireLabel(p.expire)}")
                    }
                },
                task = vm.providerTasks[p.name],
                canRun = true,
                onRun = { vm.updateProvider(p.name) },
                progress = if (p.hasSubscriptionInfo && p.total > 0L) p.ratio else null,
            )
        }
    }
}

/* ---------------------------- rules ---------------------------- */

private fun LazyListScope.ruleItems(vm: HetuViewModel, query: String, onQuery: (String) -> Unit) {
    item(key = "rules-search") {
        HxSearchField(query, onQuery, "搜索规则内容或策略", Modifier.padding(horizontal = Hx.gutter).padding(bottom = 12.dp))
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
        RuleRow(rule, first = index == 0, last = index == list.lastIndex)
    }
}

@Composable
private fun RuleRow(rule: ProxyRuleUi, first: Boolean, last: Boolean) {
    val c = Hx.colors
    val shape = RoundedCornerShape(
        topStart = if (first) 16.dp else 0.dp,
        topEnd = if (first) 16.dp else 0.dp,
        bottomStart = if (last) 16.dp else 0.dp,
        bottomEnd = if (last) 16.dp else 0.dp,
    )
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Hx.gutter)
            .clip(shape)
            .background(c.surface),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 13.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                (rule.index + 1).toString(),
                style = MaterialTheme.typography.labelSmall.merge(HxNumberStyle),
                color = c.textFaint,
                modifier = Modifier.widthIn(min = 30.dp),
            )
            Column(Modifier.weight(1f)) {
                Text(
                    rule.payload.ifBlank { rule.type },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (rule.disabled) c.textFaint else c.text,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    rule.type + (if (rule.size >= 0) " · " + rule.size + " 条" else "") + (if (rule.hitCount > 0) " · 命中 " + rule.hitCount else ""),
                    style = MaterialTheme.typography.labelSmall,
                    color = c.textMuted,
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                rule.proxy,
                style = MaterialTheme.typography.labelMedium,
                color = when {
                    rule.proxy.startsWith("REJECT") -> c.bad
                    rule.proxy == "DIRECT" -> c.good
                    else -> c.textMuted
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 92.dp),
            )
        }
        if (!last) HxDivider(43.dp)
    }
}

/* ---------------------------- shared ---------------------------- */

@Composable
private fun UpdateAllBar(summary: String, label: String, busy: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = Hx.gutter).padding(bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(summary, style = MaterialTheme.typography.bodySmall, color = Hx.colors.textMuted, modifier = Modifier.weight(1f))
        if (busy) HxSpinner(14.dp)
        else Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = if (enabled) Hx.colors.accent else Hx.colors.textFaint,
            modifier = Modifier
                .clip(Hx.chipShape)
                .clickable(enabled = enabled, onClick = onClick)
                .padding(horizontal = 8.dp, vertical = 5.dp),
        )
    }
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
        topStart = if (first) 16.dp else 0.dp,
        topEnd = if (first) 16.dp else 0.dp,
        bottomStart = if (last) 16.dp else 0.dp,
        bottomEnd = if (last) 16.dp else 0.dp,
    )
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = Hx.gutter)
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
                if (task != null && task.ok == false && task.message.isNotBlank()) {
                    Text(task.message, style = MaterialTheme.typography.bodySmall, color = c.bad, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                if (progress != null) HxProgressBar(progress, if (progress > .9f) c.bad else c.accent, Modifier.padding(top = 7.dp, end = 8.dp), height = 3.dp)
            }
            if (canRun) {
                Box(
                    Modifier.size(40.dp).clip(CircleShape).clickable(enabled = task?.running != true, onClick = onRun),
                    contentAlignment = Alignment.Center,
                ) {
                    when {
                        task?.running == true -> HxSpinner(16.dp)
                        task?.ok == true -> Icon(Icons.Rounded.CheckCircle, "已更新", tint = c.good, modifier = Modifier.size(20.dp))
                        task?.ok == false -> Icon(Icons.Rounded.ErrorOutline, "更新失败", tint = c.bad, modifier = Modifier.size(20.dp))
                        else -> Icon(Icons.Rounded.Sync, "更新", tint = c.textMuted, modifier = Modifier.size(20.dp))
                    }
                }
            } else {
                Text("本地", style = MaterialTheme.typography.labelSmall, color = c.textFaint, modifier = Modifier.padding(end = 8.dp))
            }
        }
        if (!last) HxDivider(13.dp)
    }
}

