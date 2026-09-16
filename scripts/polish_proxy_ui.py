from pathlib import Path
import re

path = Path('android-app/app/src/main/java/io/github/xgl34222220/bichen/ReferenceProxyActivity.kt')
s = path.read_text()


def one(old: str, new: str, label: str):
    global s
    if old not in s:
        raise SystemExit(f'missing exact block: {label}')
    s = s.replace(old, new, 1)


def sub(pattern: str, repl: str, label: str):
    global s
    s2, n = re.subn(pattern, repl, s, count=1, flags=re.S)
    if n != 1:
        raise SystemExit(f'{label}: expected 1 match, got {n}')
    s = s2


# Imports used by spring press feedback, animated tabs and tonal surfaces.
one('import androidx.compose.foundation.clickable\n', 'import androidx.compose.foundation.clickable\nimport androidx.compose.foundation.BorderStroke\nimport androidx.compose.foundation.interaction.MutableInteractionSource\nimport androidx.compose.foundation.interaction.collectIsPressedAsState\n', 'foundation imports')
one('import androidx.compose.material3.pulltorefresh.PullToRefreshBox\n', 'import androidx.compose.material3.pulltorefresh.PullToRefreshBox\nimport androidx.compose.animation.core.Spring\nimport androidx.compose.animation.core.animateDpAsState\nimport androidx.compose.animation.core.animateFloatAsState\nimport androidx.compose.animation.core.spring\n', 'animation imports')
one('import androidx.compose.ui.draw.clip\n', 'import androidx.compose.ui.draw.clip\nimport androidx.compose.ui.draw.blur\n', 'blur import')
one('import androidx.compose.ui.graphics.Color\n', 'import androidx.compose.ui.graphics.Color\nimport androidx.compose.ui.graphics.graphicsLayer\nimport androidx.compose.ui.graphics.luminance\n', 'graphics imports')

new_home = r'''@Composable
private fun RefHome(
    state: ProxyComposeState,
    runtime: ProxyRuntimeSnapshot,
    providers: List<DashboardProviderUi>,
    siteDelays: Map<String, Long>,
    upRate: Long,
    downRate: Long,
    cpuPercent: Float,
    operation: String,
    message: String,
    testing: Boolean,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onToggle: () -> Unit,
    onReload: () -> Unit,
    onRestart: () -> Unit,
    onDelay: () -> Unit,
    onWebUi: () -> Unit,
    onLog: () -> Unit,
) {
    val t = LocalBichenTokens.current
    val scheme = MaterialTheme.colorScheme
    val memory = runtime.rssBytes.takeIf { it > 0L } ?: state.memoryBytes

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().padding(top = 10.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("代理", color = t.textPrimary, fontSize = 27.sp, lineHeight = 33.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                IconButton(onClick = onRefresh, modifier = Modifier.size(44.dp)) {
                    Icon(Icons.Rounded.Refresh, "刷新", tint = t.textSecondary, modifier = Modifier.size(20.dp))
                }
                IconButton(onClick = onBack, modifier = Modifier.size(44.dp)) {
                    Icon(Icons.Rounded.Close, "关闭", tint = t.textSecondary, modifier = Modifier.size(20.dp))
                }
            }
        }
        item {
            Surface(
                shape = RoundedCornerShape(26.dp),
                color = t.heroBackground,
                border = BorderStroke(.7.dp, scheme.primary.copy(alpha = .10f)),
                shadowElevation = 0.dp,
            ) {
                Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Row(verticalAlignment = Alignment.Top) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Box(Modifier.size(9.dp).background(if (state.running) t.success else t.danger, CircleShape))
                                Text(if (state.running) "运行中" else "已停止", color = t.textPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                                Text(if (state.running) refDuration(runtime.elapsedSeconds) else "等待启动", color = t.textMuted, fontSize = 11.sp)
                            }
                            Text("${state.core} · ${state.mode}", color = t.textSecondary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                            Text(state.config, color = t.textMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Box(
                            Modifier.size(56.dp).background(if (state.running) scheme.primary else t.controlBackground, CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                if (state.running) Icons.Rounded.Check else Icons.Rounded.PowerSettingsNew,
                                null,
                                tint = if (state.running) Color.White else t.textMuted,
                                modifier = Modifier.size(30.dp),
                            )
                        }
                    }
                    HorizontalDivider(color = scheme.primary.copy(alpha = .10f))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        RefActionText("重载", state.running && operation.isBlank(), onReload, Modifier.weight(1f), t.textPrimary)
                        RefActionText(if (state.running) "停止" else "启动", operation.isBlank(), onToggle, Modifier.weight(1f), t.danger)
                        RefActionText("重启", state.running && operation.isBlank(), onRestart, Modifier.weight(1f), t.textPrimary)
                    }
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                RefSmallTool("WebUI", "Web 界面", Icons.Rounded.Language, onWebUi, Modifier.weight(1f))
                RefSmallTool("日志", "查看", Icons.Rounded.Article, onLog, Modifier.weight(1f))
            }
        }
        item {
            RefLatencyPanel(
                baidu = siteDelays["Baidu"],
                cloudflare = siteDelays["Cloudflare"],
                google = siteDelays["Google"],
                testing = testing,
                onClick = onDelay,
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                RefNetworkIdentityCard(runtime, state.connections.size, Modifier.weight(1f))
                RefSpeedCard(upRate, downRate, Modifier.weight(1f))
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                RefSubscriptionCompact(providers, Modifier.weight(1f))
                RefResourceCard(memory, cpuPercent, runtime.pid, Modifier.weight(1f))
            }
        }
        if (operation.isNotBlank() || message.isNotBlank()) {
            item { Text(operation.ifBlank { message }, color = t.textSecondary, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)) }
        }
    }
}

@Composable
private fun RefLatencyPanel'''
sub(r'@Composable\nprivate fun RefHome\(.*?\n\}\n\n@Composable\nprivate fun RefLatencyPanel', new_home, 'RefHome')

new_network = r'''@Composable
private fun RefNetworkIdentityCard(runtime: ProxyRuntimeSnapshot, connections: Int, modifier: Modifier) {
    val t = LocalBichenTokens.current
    var lanMode by rememberSaveable { mutableStateOf(true) }
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) .97f else 1f, spring(dampingRatio = .78f, stiffness = 520f), label = "networkCardPress")
    val shape = RoundedCornerShape(18.dp)
    Surface(
        modifier = modifier.graphicsLayer { scaleX = scale; scaleY = scale }.clip(shape)
            .clickable(interactionSource = source, indication = null) { lanMode = !lanMode },
        shape = shape,
        color = t.cardBackground,
        shadowElevation = 0.dp,
    ) {
        Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (lanMode) "LAN" else "WAN", color = t.textPrimary, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Box(Modifier.size(27.dp).background(MaterialTheme.colorScheme.primary.copy(alpha = .09f), CircleShape), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.SwapHoriz, "切换 LAN/WAN", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(15.dp))
                }
            }
            if (lanMode) {
                Text("IP", color = t.textSecondary, fontSize = 11.sp)
                Text(runtime.lanAddress, color = t.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${runtime.lanInterface} · $connections 连接", color = t.textMuted, fontSize = 11.sp, maxLines = 1)
            } else {
                Text("公网 IP", color = t.textSecondary, fontSize = 11.sp)
                Text(runtime.wanAddress, color = t.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${countryEmoji(runtime.wanCountryCode)} ${runtime.wanRegion}", color = t.textMuted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun RefSpeedCard'''
sub(r'@Composable\nprivate fun RefNetworkIdentityCard\(.*?\n\}\n\n@Composable\nprivate fun RefSpeedCard', new_network, 'RefNetworkIdentityCard')

new_resource = r'''@Composable
private fun RefResourceCard(memory: Long, cpuPercent: Float, pid: Int, modifier: Modifier) {
    val t = LocalBichenTokens.current
    Surface(modifier = modifier, shape = RoundedCornerShape(18.dp), color = t.cardBackground, shadowElevation = 0.dp) {
        Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Text("资源占用", color = t.textPrimary, style = MaterialTheme.typography.titleSmall)
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("内存", color = t.textSecondary, fontSize = 12.sp)
                Text(refBytes(memory), color = t.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("CPU", color = t.textSecondary, fontSize = 12.sp)
                Text(String.format(java.util.Locale.US, "%.1f%%", cpuPercent), color = t.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

private fun countryEmoji'''
sub(r'@Composable\nprivate fun RefResourceCard\(.*?\n\}\n\nprivate fun countryEmoji', new_resource, 'RefResourceCard')

new_action = r'''@Composable
private fun RefActionText(text: String, enabled: Boolean, onClick: () -> Unit, modifier: Modifier, color: Color = LocalBichenTokens.current.textPrimary) {
    val t = LocalBichenTokens.current
    val dark = MaterialTheme.colorScheme.background.luminance() < .5f
    val source = remember(text) { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) .95f else 1f, spring(dampingRatio = .72f, stiffness = 620f), label = "heroAction$text")
    Box(
        modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .height(38.dp)
            .clip(CircleShape)
            .background(if (dark) t.elevatedCardBackground.copy(alpha = .82f) else Color.White.copy(alpha = .84f))
            .clickable(enabled = enabled, interactionSource = source, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = if (enabled) color else t.textSecondary.copy(alpha = .42f), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun RefMetricCard'''
sub(r'@Composable\nprivate fun RefActionText\(.*?\n\}\n\n@Composable\nprivate fun RefMetricCard', new_action, 'RefActionText')

new_small_tool = r'''@Composable
private fun RefSmallTool(title: String, subtitle: String, icon: ImageVector, onClick: () -> Unit, modifier: Modifier) {
    val t = LocalBichenTokens.current
    val source = remember(title) { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) .97f else 1f, spring(dampingRatio = .78f, stiffness = 520f), label = "smallTool$title")
    val shape = RoundedCornerShape(16.dp)
    Surface(
        modifier = modifier.graphicsLayer { scaleX = scale; scaleY = scale }.clip(shape)
            .clickable(interactionSource = source, indication = null, onClick = onClick),
        shape = shape,
        color = t.cardBackground,
        shadowElevation = 0.dp,
    ) {
        Row(Modifier.padding(13.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(32.dp).background(MaterialTheme.colorScheme.primary.copy(alpha = .09f), RoundedCornerShape(11.dp)), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(9.dp))
            Column(Modifier.weight(1f)) {
                Text(title, color = t.textPrimary, style = MaterialTheme.typography.titleSmall)
                Text(subtitle, color = t.textSecondary, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun RefSubscriptionCard'''
sub(r'@Composable\nprivate fun RefSmallTool\(.*?\n\}\n\n@Composable\nprivate fun RefSubscriptionCard', new_small_tool, 'RefSmallTool')

new_tabs = r'''@Composable
private fun RefPanelTabs(selected: RefPanelTab, onSelect: (RefPanelTab) -> Unit) {
    val t = LocalBichenTokens.current
    val scheme = MaterialTheme.colorScheme
    val dark = scheme.background.luminance() < .5f
    val tabs = RefPanelTab.entries
    BoxWithConstraints(
        Modifier.fillMaxWidth().height(38.dp).background(t.controlBackground.copy(alpha = .72f), CircleShape).padding(2.dp),
    ) {
        val itemWidth = maxWidth / tabs.size.toFloat()
        val index = tabs.indexOf(selected).coerceAtLeast(0)
        val indicatorX by animateDpAsState(
            targetValue = itemWidth * index.toFloat(),
            animationSpec = spring(dampingRatio = .78f, stiffness = 480f),
            label = "panelTabIndicator",
        )
        Box(
            Modifier.offset(x = indicatorX).width(itemWidth).fillMaxHeight()
                .clip(CircleShape)
                .background(if (dark) scheme.primary.copy(alpha = .22f) else Color(0xFFE0EDFF)),
        )
        Row(Modifier.fillMaxSize()) {
            tabs.forEach { tab ->
                val active = tab == selected
                val source = remember(tab) { MutableInteractionSource() }
                val pressed by source.collectIsPressedAsState()
                val scale by animateFloatAsState(if (pressed) .95f else 1f, spring(dampingRatio = .78f, stiffness = 560f), label = "tab${tab.name}")
                Box(
                    Modifier.width(itemWidth).fillMaxHeight().graphicsLayer { scaleX = scale; scaleY = scale }
                        .clip(CircleShape)
                        .clickable(interactionSource = source, indication = null) { onSelect(tab) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(tab.label, color = if (active) scheme.primary else t.textSecondary, fontSize = 11.sp, fontWeight = if (active) FontWeight.Bold else FontWeight.Medium, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun RefPanelOverview'''
sub(r'@Composable\nprivate fun RefPanelTabs\(.*?\n\}\n\n@Composable\nprivate fun RefPanelOverview', new_tabs, 'RefPanelTabs')

new_group_card = r'''@Composable
private fun RefGroupCard(group: ProxyGroupUi, selected: String, expanded: Boolean, delay: Long?, modifier: Modifier, onClick: () -> Unit) {
    val t = LocalBichenTokens.current
    val scheme = MaterialTheme.colorScheme
    val source = remember(group.name) { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) .97f else 1f, spring(dampingRatio = .76f, stiffness = 560f), label = "group${group.name}")
    val shape = RoundedCornerShape(18.dp)
    Column(
        modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .heightIn(min = 106.dp)
            .background(if (expanded) t.selectionBackground else t.cardBackground, shape)
            .border(.7.dp, if (expanded) scheme.primary.copy(alpha = .18f) else t.outline.copy(alpha = .34f), shape)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .padding(12.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RefGroupVisualIcon(group, Modifier.size(26.dp))
            Spacer(Modifier.width(8.dp))
            Text(group.name, color = t.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(6.dp))
            RefDelayBadge(delay, false, null)
        }
        Text("${group.type.uppercase(java.util.Locale.ROOT).ifBlank { refGroupType(group.type) }} · ${group.nodes.size} 节点", color = t.textMuted, fontSize = 11.sp, maxLines = 1)
        Text(selected.ifBlank { "未选择" }, color = t.textSecondary, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun RefGroupVisualIcon'''
sub(r'@Composable\nprivate fun RefGroupCard\(.*?\n\}\n\n@Composable\nprivate fun RefGroupVisualIcon', new_group_card, 'RefGroupCard')

one('Icon(refGroupIcon(group.type), null, tint = scheme.primary, modifier = Modifier.size(19.dp))', 'Icon(refScenarioIcon(group.name, group.type), null, tint = scheme.primary, modifier = Modifier.size(19.dp))', 'scenario fallback icon')
one('private fun refGroupIcon(type: String): ImageVector = when (type.lowercase()) {', '''private fun refScenarioIcon(name: String, type: String): ImageVector = when {
    name.contains("chatgpt", true) || name.contains("openai", true) || name.contains("ai", true) -> Icons.Rounded.SmartToy
    name.contains("youtube", true) -> Icons.Rounded.PlayCircle
    name.contains("tiktok", true) -> Icons.Rounded.MusicNote
    name.contains("netflix", true) -> Icons.Rounded.Movie
    name.contains("apple", true) -> Icons.Rounded.PhoneIphone
    else -> refGroupIcon(type)
}

private fun refGroupIcon(type: String): ImageVector = when (type.lowercase()) {''', 'scenario icon helper')

new_badge = r'''@Composable
private fun RefDelayBadge(value: Long?, testing: Boolean, onClick: (() -> Unit)?) {
    val scheme = MaterialTheme.colorScheme
    val text = if (testing) "…" else refDelay(value)
    val (background, textColor) = when {
        testing -> scheme.primary.copy(alpha = .12f) to scheme.primary
        value == null -> Color(0x1F94A3B8) to Color(0xFF64748B)
        value <= 0L || value > 300L -> Color(0x1FEF4444) to Color(0xFFDC2626)
        value < 100L -> Color(0x1F10B981) to Color(0xFF059669)
        else -> Color(0x1FF59E0B) to Color(0xFFD97706)
    }
    val source = remember(text, onClick) { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) .94f else 1f, spring(dampingRatio = .78f, stiffness = 620f), label = "delayBadge")
    val modifier = Modifier
        .graphicsLayer { scaleX = scale; scaleY = scale }
        .background(background, CircleShape)
        .then(if (onClick != null) Modifier.clickable(enabled = !testing, interactionSource = source, indication = null, onClick = onClick) else Modifier)
        .padding(horizontal = 8.dp, vertical = 2.dp)
    Text(text, modifier = modifier, color = textColor, fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
}

@Composable
private fun RefTrafficOverview'''
sub(r'@Composable\nprivate fun RefDelayBadge\(.*?\n\}\n\n@Composable\nprivate fun RefTrafficOverview', new_badge, 'RefDelayBadge')

new_provider = r'''@Composable
private fun RefProviderRow(item: DashboardProviderUi, onRefresh: () -> Unit, onClick: () -> Unit) {
    val t = LocalBichenTokens.current
    val source = remember(item.name) { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) .97f else 1f, spring(dampingRatio = .78f, stiffness = 520f), label = "provider${item.name}")
    val shape = RoundedCornerShape(18.dp)
    Surface(
        modifier = Modifier.graphicsLayer { scaleX = scale; scaleY = scale }.clip(shape)
            .clickable(interactionSource = source, indication = null, onClick = onClick),
        shape = shape,
        color = t.cardBackground,
        shadowElevation = 0.dp,
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(item.name, color = t.textPrimary, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${item.nodes.size} 个节点 · 剩余 ${refExpireDays(item.expire)}", color = t.textSecondary, style = MaterialTheme.typography.labelSmall)
                }
                IconButton(onClick = onRefresh, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Rounded.Sync, "同步更新", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                }
            }
            if (item.hasSubscriptionInfo && item.total > 0L) {
                LinearProgressIndicator(progress = { item.ratio }, modifier = Modifier.fillMaxWidth().height(5.dp).clip(CircleShape))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RefMetric("已上传", refBytes(item.upload), Modifier.weight(1f))
                    RefMetric("已下载", refBytes(item.download), Modifier.weight(1f))
                    Surface(modifier = Modifier.weight(1f), shape = RoundedCornerShape(12.dp), color = t.selectionBackground) {
                        Column(Modifier.padding(horizontal = 9.dp, vertical = 8.dp)) {
                            Text(refBytes(item.remaining), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("剩余流量", color = t.textSecondary, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            } else {
                Text("该订阅没有上报流量信息", color = t.textMuted, style = MaterialTheme.typography.bodySmall)
            }
            Text("到期 ${refExpireDate(item.expire)} · ${refUpdatedAt(item.updatedAt)}", color = t.textMuted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

private fun refExpireDays'''
sub(r'@Composable\nprivate fun RefProviderRow\(.*?\n\}\n\nprivate fun refExpireDays', new_provider, 'RefProviderRow')

new_tool_row = r'''@Composable
private fun RefToolRow(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    val t = LocalBichenTokens.current
    val source = remember(title) { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) .97f else 1f, spring(dampingRatio = .78f, stiffness = 560f), label = "tool$title")
    Row(
        Modifier.fillMaxWidth().graphicsLayer { scaleX = scale; scaleY = scale }
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(21.dp))
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = t.textPrimary, style = MaterialTheme.typography.titleSmall)
            Text(subtitle, color = t.textSecondary, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Icon(Icons.Rounded.ChevronRight, null, tint = t.textSecondary, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun RefValueRow'''
sub(r'@Composable\nprivate fun RefToolRow\(.*?\n\}\n\n@Composable\nprivate fun RefValueRow', new_tool_row, 'RefToolRow')

new_value_row = r'''@Composable
private fun RefValueRow(title: String, value: String, onClick: (() -> Unit)? = null) {
    val t = LocalBichenTokens.current
    val source = remember(title) { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed && onClick != null) .98f else 1f, spring(dampingRatio = .80f, stiffness = 560f), label = "value$title")
    val modifier = if (onClick == null) Modifier.fillMaxWidth() else Modifier.fillMaxWidth().graphicsLayer { scaleX = scale; scaleY = scale }
        .clickable(interactionSource = source, indication = null, onClick = onClick)
    Row(modifier.padding(vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = t.textPrimary, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        if (value.isNotBlank()) Text(value, color = t.textSecondary, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 190.dp))
        if (onClick != null) {
            Spacer(Modifier.width(6.dp))
            Icon(Icons.Rounded.ChevronRight, null, tint = t.textSecondary, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun RefDivider'''
sub(r'@Composable\nprivate fun RefValueRow\(.*?\n\}\n\n@Composable\nprivate fun RefDivider', new_value_row, 'RefValueRow')

# Bottom-sheet details and background treatment.
one('PullToRefreshBox(isRefreshing = refreshing, onRefresh = ::refresh, modifier = Modifier.fillMaxSize()) {', 'PullToRefreshBox(isRefreshing = refreshing, onRefresh = ::refresh, modifier = Modifier.fillMaxSize().blur(if (selectedGroupName != null) 4.dp else 0.dp)) {', 'sheet background blur')
one('scrimColor = Color.Black.copy(alpha = .34f),', 'scrimColor = Color.Black.copy(alpha = .35f),', 'sheet scrim')
one('.size(width = 38.dp, height = 4.dp)\n                        .background(t.outline.copy(alpha = .75f), CircleShape),', '.size(width = 36.dp, height = 4.dp)\n                        .background(Color(0xFFCBD5E1), CircleShape),', 'sheet handle')

# Node rows also get the same spring press feedback. Regex is whitespace-tolerant.
sub(
    r'(\s+val active = node\.name == selected\n)(\s+)val shape = RoundedCornerShape\(14\.dp\)',
    r'\1\2val interactionSource = remember(node.name) { MutableInteractionSource() }\n\2val pressed by interactionSource.collectIsPressedAsState()\n\2val pressScale by animateFloatAsState(if (pressed) .97f else 1f, spring(dampingRatio = .78f, stiffness = 560f), label = "node${node.name}")\n\2val shape = RoundedCornerShape(14.dp)',
    'node press state',
)
sub(
    r'(Modifier\.fillMaxWidth\(\)\n\s+)\.background\(fill, shape\)(\n\s+\.border\(\.7\.dp, if \(active\) scheme\.primary\.copy\(alpha = \.28f\) else t\.outline\.copy\(alpha = \.34f\), shape\)\n\s+)\.clickable \{ onSelect\(node\.name\) \}',
    r'\1.graphicsLayer { scaleX = pressScale; scaleY = pressScale }\n                            .background(fill, shape)\2.clickable(interactionSource = interactionSource, indication = null) { onSelect(node.name) }',
    'node press modifier',
)

# Normalize timestamps in subscription and rule-provider cards.
s = re.sub(
    r'\$\{item\.updatedAt\.ifBlank \{ "未记录更新时间" \}\}',
    '${refUpdatedAt(item.updatedAt)}',
    s,
    count=1,
)
one('private fun refDelay(value: Long?): String = when {', '''private fun refUpdatedAt(value: String): String {
    if (value.isBlank()) return "更新于 —"
    val raw = value.trim()
    val compact = when {
        raw.length >= 16 && raw[4] == '-' && raw[7] == '-' -> raw.substring(5, 16).replace('T', ' ')
        raw.length >= 16 -> raw.take(16).replace('T', ' ')
        else -> raw.replace('T', ' ')
    }
    return "更新于 $compact"
}

private fun refDelay(value: Long?): String = when {''', 'updatedAt formatter')

path.write_text(s)
print('Proxy UI polish applied successfully')
