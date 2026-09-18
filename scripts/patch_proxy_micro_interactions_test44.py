from pathlib import Path
import re

PATH = Path('android-app/app/src/main/java/io/github/xgl34222220/hetu/ReferenceProxyActivity.kt')
text = PATH.read_text(encoding='utf-8')


def replace_once(old: str, new: str, label: str) -> None:
    global text
    count = text.count(old)
    if count != 1:
        raise SystemExit(f'{label}: expected exactly 1 match, found {count}')
    text = text.replace(old, new, 1)


def replace_regex(pattern: str, repl: str, label: str) -> None:
    global text
    next_text, count = re.subn(pattern, repl, text, count=1, flags=re.S)
    if count != 1:
        raise SystemExit(f'{label}: expected exactly 1 regex match, found {count}')
    text = next_text

# Lift the panel segmented-tab state to the shell so Home can route directly to Subscriptions.
replace_once(
    '    var page by rememberSaveable { mutableStateOf(RefProxyPage.Home) }\n    var panelDetailVisible by rememberSaveable { mutableStateOf(false) }',
    '    var page by rememberSaveable { mutableStateOf(RefProxyPage.Home) }\n    var panelTab by rememberSaveable { mutableStateOf(RefPanelTab.Overview) }\n    var panelDetailVisible by rememberSaveable { mutableStateOf(false) }',
    'lift panel tab state',
)

replace_once(
    '                    onWebUi = { context.startActivity(Intent(context, ProxyWebUiActivity::class.java)) },\n                    onLog = { scope.launch { logText = runCatching { inspector.runtimeLog() }.getOrElse { it.message ?: "日志读取失败" } } },',
    '                    onWebUi = { context.startActivity(Intent(context, ProxyWebUiActivity::class.java)) },\n                    onLog = { scope.launch { logText = runCatching { inspector.runtimeLog() }.getOrElse { it.message ?: "日志读取失败" } } },\n                    onSubscription = {\n                        panelTab = RefPanelTab.Subscriptions\n                        page = RefProxyPage.Panel\n                    },',
    'wire home subscription route',
)

replace_once(
    '                RefProxyPage.Panel -> RefPanel(\n                    state = state,\n                    repo = repo,\n                    delays = delays,',
    '                RefProxyPage.Panel -> RefPanel(\n                    state = state,\n                    repo = repo,\n                    delays = delays,\n                    selectedTab = panelTab,\n                    onSelectedTabChange = { panelTab = it },',
    'pass lifted panel tab state',
)

replace_once(
    '    onWebUi: () -> Unit,\n    onLog: () -> Unit,\n) {',
    '    onWebUi: () -> Unit,\n    onLog: () -> Unit,\n    onSubscription: () -> Unit,\n) {',
    'extend RefHome signature',
)

replace_once(
    '                RefSubscriptionCompact(providers, Modifier.weight(1f))',
    '                RefSubscriptionCompact(providers, Modifier.weight(1f), onSubscription)',
    'make compact subscription clickable',
)

# Four dashboard cards: exact 100dp height and a consistent three-tier layout.
network_fn = r'''@Composable
private fun RefNetworkIdentityCard(runtime: ProxyRuntimeSnapshot, connections: Int, modifier: Modifier) {
    val t = LocalHetuTokens.current
    var lanMode by rememberSaveable { mutableStateOf(true) }
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) .98f else 1f, spring(dampingRatio = .80f, stiffness = 520f), label = "networkCardPress")
    val shape = RoundedCornerShape(20.dp)
    val valueColor = if (MaterialTheme.colorScheme.background.luminance() < .5f) t.textPrimary else Color(0xFF0F172A)
    Surface(
        modifier = modifier
            .height(100.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale; alpha = if (pressed) .92f else 1f }
            .clip(shape)
            .clickable(interactionSource = source, indication = null) { lanMode = !lanMode },
        shape = shape,
        color = t.cardBackground,
        shadowElevation = 1.dp,
    ) {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(if (lanMode) "LAN" else "WAN", color = Color(0xFF64748B), fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Icon(Icons.Rounded.SwapHoriz, "切换 LAN/WAN", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(15.dp))
            }
            Text(
                if (lanMode) runtime.lanAddress else runtime.wanAddress,
                color = valueColor,
                fontSize = 15.sp,
                lineHeight = 18.sp,
                fontWeight = FontWeight.ExtraBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                if (lanMode) "${runtime.lanInterface} · $connections 连接" else "${countryEmoji(runtime.wanCountryCode)} ${runtime.wanRegion}",
                color = Color(0xFF64748B),
                fontSize = 11.sp,
                lineHeight = 14.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}'''
replace_regex(
    r'@Composable\nprivate fun RefNetworkIdentityCard\(.*?\n\}\n\n@Composable\nprivate fun RefSpeedCard',
    network_fn + '\n\n@Composable\nprivate fun RefSpeedCard',
    'rewrite network card',
)

speed_fn = r'''@Composable
private fun RefSpeedCard(up: Long, down: Long, modifier: Modifier) {
    val t = LocalHetuTokens.current
    val valueColor = if (MaterialTheme.colorScheme.background.luminance() < .5f) t.textPrimary else Color(0xFF0F172A)
    Surface(modifier = modifier.height(100.dp), shape = RoundedCornerShape(20.dp), color = t.cardBackground, shadowElevation = 1.dp) {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("实时网速", color = Color(0xFF64748B), fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("↑ 上行", color = Color(0xFF059669), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text(refSpeed(up), color = valueColor, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("↓ 下行", color = Color(0xFF2563EB), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text(refSpeed(down), color = valueColor, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
            }
        }
    }
}'''
replace_regex(
    r'@Composable\nprivate fun RefSpeedCard\(.*?\n\}\n\n@Composable\nprivate fun RefSubscriptionCompact',
    speed_fn + '\n\n@Composable\nprivate fun RefSubscriptionCompact',
    'rewrite speed card',
)

subscription_compact_fn = r'''@Composable
private fun RefSubscriptionCompact(items: List<DashboardProviderUi>, modifier: Modifier, onClick: () -> Unit) {
    val t = LocalHetuTokens.current
    val haptic = LocalHapticFeedback.current
    val tracked = items.filter { it.hasSubscriptionInfo && it.total > 0L }
    val used = tracked.sumOf { it.used }
    val total = tracked.sumOf { it.total }
    val ratio = if (total <= 0L) 0f else (used.toDouble() / total.toDouble()).toFloat().coerceIn(0f, 1f)
    val valueColor = if (MaterialTheme.colorScheme.background.luminance() < .5f) t.textPrimary else Color(0xFF0F172A)
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) .98f else 1f, spring(dampingRatio = .78f, stiffness = 560f), label = "subscriptionCompactPress")
    val shape = RoundedCornerShape(20.dp)
    Surface(
        modifier = modifier.height(100.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale; alpha = if (pressed) .92f else 1f }
            .clip(shape)
            .clickable(interactionSource = source, indication = null) {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onClick()
            },
        shape = shape,
        color = t.cardBackground,
        shadowElevation = 1.dp,
    ) {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("订阅", color = Color(0xFF64748B), fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                if (total > 0L) Surface(shape = RoundedCornerShape(999.dp), color = Color(0xFFEBF3FF)) {
                    Text("剩余 ${((1f - ratio) * 100f).toInt()}%", Modifier.padding(horizontal = 7.dp, vertical = 2.dp), color = MaterialTheme.colorScheme.primary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
            }
            Text(if (total > 0L) refBytes(used) else "—", color = valueColor, fontSize = 16.sp, lineHeight = 19.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("已用流量", color = Color(0xFF94A3B8), fontSize = 11.sp, modifier = Modifier.weight(1f))
                Text(if (total > 0L) "总 ${refBytes(total)}" else "${items.size} 个订阅", color = Color(0xFF64748B), fontSize = 11.sp, fontWeight = FontWeight.Medium, maxLines = 1)
            }
        }
    }
}'''
replace_regex(
    r'@Composable\nprivate fun RefSubscriptionCompact\(.*?\n\}\n\n@Composable\nprivate fun RefResourceCard',
    subscription_compact_fn + '\n\n@Composable\nprivate fun RefResourceCard',
    'rewrite subscription compact card',
)

resource_fn = r'''@Composable
private fun RefResourceCard(memory: Long, cpuPercent: Float, modifier: Modifier) {
    val t = LocalHetuTokens.current
    val valueColor = if (MaterialTheme.colorScheme.background.luminance() < .5f) t.textPrimary else Color(0xFF0F172A)
    Surface(modifier = modifier.height(100.dp), shape = RoundedCornerShape(20.dp), color = t.cardBackground, shadowElevation = 1.dp) {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("资源占用", color = Color(0xFF64748B), fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("内存", color = Color(0xFF94A3B8), fontSize = 11.sp, modifier = Modifier.weight(1f))
                Text(refBytes(memory), color = valueColor, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("CPU", color = Color(0xFF94A3B8), fontSize = 11.sp, modifier = Modifier.weight(1f))
                Text(String.format(java.util.Locale.US, "%.1f%%", cpuPercent), color = valueColor, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
            }
        }
    }
}'''
replace_regex(
    r'@Composable\nprivate fun RefResourceCard\(.*?\n\}\n\nprivate fun countryEmoji',
    resource_fn + '\n\nprivate fun countryEmoji',
    'rewrite resource card',
)

# RefPanel now consumes the shell-owned tab state.
replace_once(
    '    delays: MutableMap<String, Long>,\n    hazeState: HazeState,',
    '    delays: MutableMap<String, Long>,\n    selectedTab: RefPanelTab,\n    onSelectedTabChange: (RefPanelTab) -> Unit,\n    hazeState: HazeState,',
    'extend RefPanel signature',
)
replace_once(
    '    var tab by rememberSaveable { mutableStateOf(RefPanelTab.Overview) }',
    '    val tab = selectedTab',
    'use external panel tab',
)
replace_once(
    '                        onSelect = { tab = it },',
    '                        onSelect = onSelectedTabChange,',
    'wire segmented tabs to external state',
)

# Stop entering a separate full-screen strategy page. Inline accordion keeps the dock visible.
replace_regex(
    r'    val selectedGroup = selectedGroupName\?\.let \{ name -> state\.groups\.firstOrNull \{ it\.name == name \} \}\n    if \(selectedGroup != null\) \{.*?\n    \}\n\n    PullToRefreshBox',
    '    LaunchedEffect(tab) {\n        if (tab != RefPanelTab.Overview) selectedGroupName = null\n        onDetailVisibleChanged(false)\n    }\n\n    PullToRefreshBox',
    'remove full-screen group detail routing',
)

# Add a local haptic handle for the accordion interactions.
replace_once(
    '    val context = LocalContext.current\n    val t = LocalHetuTokens.current\n    var refreshing by remember { mutableStateOf(false) }',
    '    val context = LocalContext.current\n    val t = LocalHetuTokens.current\n    val haptic = LocalHapticFeedback.current\n    var refreshing by remember { mutableStateOf(false) }',
    'add panel haptics',
)

inline_overview = r'''                RefPanelTab.Overview -> {
                    itemsIndexed(filteredGroups.chunked(2), key = { index, _ -> "groups-$index" }) { _, pair ->
                        val expandedGroup = pair.firstOrNull { it.name == selectedGroupName }
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                pair.forEach { group ->
                                    val selected = selectedLocal[group.name] ?: group.now
                                    RefGroupCard(
                                        group = group,
                                        selected = selected,
                                        expanded = selectedGroupName == group.name,
                                        delay = delays[selected] ?: group.nodes.firstOrNull { it.name == selected }?.lastDelay,
                                        modifier = Modifier.weight(1f),
                                        onClick = {
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            selectedGroupName = if (selectedGroupName == group.name) null else group.name
                                        },
                                    )
                                }
                                if (pair.size == 1) Spacer(Modifier.weight(1f))
                            }
                            androidx.compose.animation.AnimatedVisibility(
                                visible = expandedGroup != null,
                                enter = androidx.compose.animation.expandVertically(
                                    expandFrom = Alignment.Top,
                                    animationSpec = spring(dampingRatio = .78f, stiffness = 420f),
                                ) + androidx.compose.animation.fadeIn(),
                                exit = androidx.compose.animation.shrinkVertically(
                                    shrinkTowards = Alignment.Top,
                                    animationSpec = spring(dampingRatio = .86f, stiffness = 520f),
                                ) + androidx.compose.animation.fadeOut(),
                            ) {
                                expandedGroup?.let { group ->
                                    val selected = selectedLocal[group.name] ?: group.now
                                    RefInlineGroupExpansion(
                                        group = group,
                                        selected = selected,
                                        delays = delays,
                                        testing = testing,
                                        onSelect = { node ->
                                            val previous = selectedLocal[group.name] ?: group.now
                                            selectedLocal[group.name] = node
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            scope.launch {
                                                try {
                                                    repo.select(group.name, node)
                                                    onRefreshState()
                                                } catch (_: Exception) {
                                                    if (previous.isBlank()) selectedLocal.remove(group.name) else selectedLocal[group.name] = previous
                                                }
                                            }
                                        },
                                        onDelay = { node ->
                                            if (testing[node] != true) scope.launch {
                                                testing[node] = true
                                                try { delays[node] = repo.delay(node) }
                                                catch (_: Exception) { delays[node] = -1L }
                                                finally { testing.remove(node) }
                                            }
                                        },
                                        onTestAll = {
                                            group.nodes.forEach { node ->
                                                if (testing[node.name] != true) scope.launch {
                                                    testing[node.name] = true
                                                    try { delays[node.name] = repo.delay(node.name) }
                                                    catch (_: Exception) { delays[node.name] = -1L }
                                                    finally { testing.remove(node.name) }
                                                }
                                            }
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
                RefPanelTab.Nodes ->'''
replace_regex(
    r'                RefPanelTab\.Overview -> \{.*?\n                \}\n                RefPanelTab\.Nodes ->',
    inline_overview,
    'replace overview with inline accordion',
)

# Simplify segmented indicator geometry to a single capsule per tab: no overshoot can cover text.
panel_tabs_fn = r'''@Composable
private fun RefPanelTabs(
    selected: RefPanelTab,
    liquidGlass: Boolean = false,
    onSelect: (RefPanelTab) -> Unit,
) {
    val t = LocalHetuTokens.current
    val scheme = MaterialTheme.colorScheme
    val dark = scheme.background.luminance() < .5f
    val tabs = RefPanelTab.entries
    val trackBrush = Brush.verticalGradient(
        if (dark) listOf(Color.White.copy(alpha = .085f), Color.White.copy(alpha = .035f))
        else listOf(Color(0xFFE2E8F0).copy(alpha = .58f), Color.White.copy(alpha = .46f)),
    )
    val trackBorder = if (dark) Color.White.copy(alpha = .08f) else Color.White.copy(alpha = .82f)
    BoxWithConstraints(
        Modifier.fillMaxWidth().height(38.dp)
            .background(trackBrush, CircleShape)
            .border(.5.dp, trackBorder, CircleShape)
            .padding(3.dp),
    ) {
        val itemWidth = maxWidth / tabs.size.toFloat()
        val targetIndex = tabs.indexOf(selected).coerceAtLeast(0)
        val indicatorX by animateDpAsState(
            targetValue = itemWidth * targetIndex.toFloat(),
            animationSpec = spring(dampingRatio = .74f, stiffness = 380f),
            label = "panelTabIndicator",
        )
        val indicatorShape = RoundedCornerShape(18.dp)
        val lensBrush = Brush.verticalGradient(
            if (dark) listOf(Color.White.copy(alpha = .10f), Color(0xFF2563EB).copy(alpha = .18f))
            else listOf(Color.White.copy(alpha = .98f), Color(0xFFF8FAFC).copy(alpha = .94f)),
        )
        Box(
            Modifier.offset(x = indicatorX)
                .width(itemWidth)
                .fillMaxHeight()
                .shadow(if (liquidGlass) 3.dp else 1.dp, indicatorShape, clip = false)
                .background(lensBrush, indicatorShape)
                .border(.6.dp, Color.White.copy(alpha = if (dark) .14f else .96f), indicatorShape),
        )
        Row(Modifier.fillMaxSize()) {
            tabs.forEach { tab ->
                val active = tab == selected
                val source = remember(tab) { MutableInteractionSource() }
                val pressed by source.collectIsPressedAsState()
                val scale by animateFloatAsState(
                    if (pressed) .96f else 1f,
                    spring(dampingRatio = .76f, stiffness = 560f),
                    label = "tab${tab.name}",
                )
                Box(
                    Modifier.width(itemWidth).fillMaxHeight()
                        .graphicsLayer { scaleX = scale; scaleY = scale; alpha = if (pressed) .88f else 1f }
                        .clip(CircleShape)
                        .clickable(interactionSource = source, indication = null) { if (!active) onSelect(tab) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        tab.label,
                        color = if (active) Color(0xFF2563EB) else if (dark) t.textSecondary else Color(0xFF64748B),
                        fontSize = 12.sp,
                        fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}'''
replace_regex(
    r'@Composable\nprivate fun RefPanelTabs\(.*?\n\}\n\n@Composable\nprivate fun RefPanelOverview',
    panel_tabs_fn + '\n\n@Composable\nprivate fun RefPanelOverview',
    'stabilize segmented tabs',
)

# Group card now advertises inline expand/collapse instead of looking like a navigation tile.
group_card_fn = r'''@Composable
private fun RefGroupCard(group: ProxyGroupUi, selected: String, expanded: Boolean, delay: Long?, modifier: Modifier, onClick: () -> Unit) {
    val t = LocalHetuTokens.current
    val scheme = MaterialTheme.colorScheme
    val dark = scheme.background.luminance() < .5f
    val source = remember(group.name) { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) .98f else 1f, spring(dampingRatio = .76f, stiffness = 560f), label = "group${group.name}")
    val shape = RoundedCornerShape(18.dp)
    val nodeName = selected.ifBlank { "未选择" }
    val nodeFlag = refNodeFlag(nodeName)
    val premiumBrush = if (dark) Brush.verticalGradient(listOf(t.elevatedCardBackground, t.cardBackground))
    else Brush.verticalGradient(listOf(Color.White, Color(0xFFFAFBFD)))
    Column(
        modifier
            .height(82.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale; alpha = if (pressed) .94f else 1f }
            .shadow(if (expanded) 5.dp else 3.dp, shape, clip = false)
            .background(premiumBrush, shape)
            .border(
                if (expanded) 1.4.dp else .8.dp,
                if (expanded) Color(0xFF2563EB).copy(alpha = .72f) else Color.White.copy(alpha = if (dark) .10f else .92f),
                shape,
            )
            .clip(shape)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 9.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f).padding(end = 5.dp), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(group.name, color = t.textPrimary, fontSize = 14.sp, lineHeight = 17.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${refGroupTypeCompact(group.type).uppercase()} 0/${group.nodes.size}", color = Color(0xFF94A3B8), fontSize = 10.sp, lineHeight = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1)
            }
            RefGroupCornerVisual(group, Modifier.size(27.dp))
            Spacer(Modifier.width(3.dp))
            Icon(
                if (expanded) Icons.Rounded.KeyboardArrowUp else Icons.Rounded.KeyboardArrowDown,
                if (expanded) "收起" else "展开",
                tint = if (expanded) Color(0xFF2563EB) else Color(0xFF94A3B8),
                modifier = Modifier.size(15.dp),
            )
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            Text(
                if (nodeFlag.isBlank()) nodeName else "$nodeFlag $nodeName",
                color = if (dark) t.textSecondary else Color(0xFF475569),
                fontSize = 11.sp,
                lineHeight = 14.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(end = 5.dp),
            )
            Surface(shape = RoundedCornerShape(999.dp), color = Color(0xFFEFF6FF)) {
                Text(
                    refDelay(delay),
                    Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                    color = Color(0xFF2563EB),
                    fontSize = 10.sp,
                    lineHeight = 13.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                )
            }
        }
    }
}'''
replace_regex(
    r'@Composable\nprivate fun RefGroupCard\(.*?\n\}\n\n@Composable\nprivate fun RefGroupCornerVisual',
    group_card_fn + '\n\n@Composable\nprivate fun RefGroupCornerVisual',
    'rewrite group card for accordion',
)

inline_group_fns = r'''@Composable
private fun RefInlineGroupExpansion(
    group: ProxyGroupUi,
    selected: String,
    delays: Map<String, Long>,
    testing: Map<String, Boolean>,
    onSelect: (String) -> Unit,
    onDelay: (String) -> Unit,
    onTestAll: () -> Unit,
) {
    val t = LocalHetuTokens.current
    val dark = MaterialTheme.colorScheme.background.luminance() < .5f
    val shape = RoundedCornerShape(20.dp)
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = shape,
        color = if (dark) t.elevatedCardBackground else Color(0xFFF1F5F9),
        shadowElevation = 0.dp,
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("切换落地节点", color = t.textSecondary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Text("${group.nodes.size} 个节点 · 点击即生效", color = Color(0xFF94A3B8), fontSize = 10.sp)
                }
                TextButton(onClick = onTestAll, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)) {
                    Text("全测速 ⚡", color = Color(0xFF2563EB), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
            group.nodes.chunked(2).forEach { pair ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    pair.forEach { node ->
                        RefInlineNodeCard(
                            node = node,
                            active = node.name == selected,
                            delay = delays[node.name] ?: node.lastDelay,
                            testing = testing[node.name] == true,
                            modifier = Modifier.weight(1f),
                            onSelect = { onSelect(node.name) },
                            onDelay = { onDelay(node.name) },
                        )
                    }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun RefInlineNodeCard(
    node: ProxyNodeUi,
    active: Boolean,
    delay: Long?,
    testing: Boolean,
    modifier: Modifier,
    onSelect: () -> Unit,
    onDelay: () -> Unit,
) {
    val t = LocalHetuTokens.current
    val dark = MaterialTheme.colorScheme.background.luminance() < .5f
    val shape = RoundedCornerShape(14.dp)
    val source = remember(node.name) { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) .98f else 1f, spring(dampingRatio = .78f, stiffness = 560f), label = "inlineNode${node.name}")
    val background = when {
        dark && active -> Color(0xFF172554)
        dark -> t.cardBackground
        active -> Color(0xFFF8FBFF)
        else -> Color.White
    }
    Row(
        modifier.height(50.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale; alpha = if (pressed) .92f else 1f }
            .shadow(if (active) 3.dp else 1.dp, shape, clip = false)
            .background(background, shape)
            .border(if (active) 1.3.dp else .7.dp, if (active) Color(0xFF2563EB) else Color(0xFFE2E8F0), shape)
            .clip(shape)
            .clickable(interactionSource = source, indication = null, onClick = onSelect)
            .padding(horizontal = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val flag = refNodeFlag(node.name)
        if (flag.isNotBlank()) {
            Text(flag, fontSize = 13.sp)
            Spacer(Modifier.width(4.dp))
        }
        Text(
            node.name,
            color = if (active) Color(0xFF1E3A8A) else t.textPrimary,
            fontSize = 11.sp,
            fontWeight = if (active) FontWeight.Bold else FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(end = 4.dp),
        )
        RefDelayBadge(delay, testing, onDelay)
    }
}

'''
replace_once(
    '@Composable\nprivate fun RefGroupCornerVisual',
    inline_group_fns + '@Composable\nprivate fun RefGroupCornerVisual',
    'insert inline group expansion composables',
)

# Subscription cards get a slim animated used-traffic progress rail.
provider_fn = r'''@Composable
private fun RefProviderRow(item: DashboardProviderUi, onRefresh: () -> Unit, onClick: () -> Unit) {
    val t = LocalHetuTokens.current
    val scheme = MaterialTheme.colorScheme
    val source = remember(item.name) { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) .98f else 1f, spring(dampingRatio = .78f, stiffness = 520f), label = "provider${item.name}")
    val shape = RoundedCornerShape(22.dp)
    val usedRatio = if (item.hasSubscriptionInfo && item.total > 0L) item.ratio.coerceIn(0f, 1f) else 0f
    val progress by animateFloatAsState(usedRatio, spring(dampingRatio = .82f, stiffness = 300f), label = "providerProgress${item.name}")
    val remainingPercent = if (item.hasSubscriptionInfo && item.total > 0L) ((1f - usedRatio) * 100f).toInt() else 0
    Surface(
        modifier = Modifier.graphicsLayer { scaleX = scale; scaleY = scale; alpha = if (pressed) .92f else 1f }
            .clip(shape)
            .clickable(interactionSource = source, indication = null, onClick = onClick),
        shape = shape,
        color = t.cardBackground,
        shadowElevation = 1.dp,
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(item.name, color = t.textPrimary, fontSize = 17.sp, lineHeight = 21.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Surface(onClick = onRefresh, shape = RoundedCornerShape(999.dp), color = Color(0xFFEBF3FF)) {
                    Row(Modifier.padding(horizontal = 9.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(if (item.hasSubscriptionInfo) "$remainingPercent%" else "同步", color = scheme.primary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Icon(Icons.Rounded.Sync, "同步更新", tint = scheme.primary, modifier = Modifier.size(14.dp))
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("到期 ${refExpireDate(item.expire)}", color = Color(0xFF94A3B8), fontSize = 11.sp, modifier = Modifier.weight(1f), maxLines = 1)
                Text(refUpdatedAt(item.updatedAt), color = Color(0xFF94A3B8), fontSize = 11.sp, maxLines = 1)
            }
            if (item.hasSubscriptionInfo && item.total > 0L) {
                Box(Modifier.fillMaxWidth().height(6.dp).background(Color(0xFFF1F5F9), CircleShape)) {
                    if (progress > 0f) {
                        Box(
                            Modifier.fillMaxWidth(progress.coerceIn(.001f, 1f)).fillMaxHeight()
                                .background(Brush.horizontalGradient(listOf(Color(0xFF3B82F6), Color(0xFF6366F1))), CircleShape),
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(refBytes(item.upload), color = t.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
                        Text("已上传", color = Color(0xFF94A3B8), fontSize = 10.sp)
                    }
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(refBytes(item.download), color = t.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
                        Text("已下载", color = Color(0xFF94A3B8), fontSize = 10.sp)
                    }
                    Surface(modifier = Modifier.weight(1f), shape = RoundedCornerShape(14.dp), color = Color(0xFFEDF4FF)) {
                        Column(Modifier.padding(horizontal = 7.dp, vertical = 7.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(refBytes(item.remaining), color = scheme.primary, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
                            Text("剩余流量", color = scheme.primary, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
                HorizontalDivider(color = Color(0xFFF1F5F9))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("已用 ${refBytes(item.used)}", color = Color(0xFF64748B), fontSize = 11.sp, modifier = Modifier.weight(1f))
                    Text("总计 ${refBytes(item.total)}", color = Color(0xFF64748B), fontSize = 11.sp)
                }
            } else {
                Text("该订阅没有上报流量信息", color = t.textMuted, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}'''
replace_regex(
    r'@Composable\nprivate fun RefProviderRow\(.*?\n\}\n\nprivate fun refExpireDays',
    provider_fn + '\n\nprivate fun refExpireDays',
    'add provider progress bar',
)

# De-emphasize unavailable badges: neutral slate instead of warning orange.
old_badge = 'trailingText = "待接入", trailingBadge = true, trailingColor = Color(0xFFD97706)'
count = text.count(old_badge)
if count != 4:
    raise SystemExit(f'neutral unavailable badge: expected 4 matches, found {count}')
text = text.replace(old_badge, 'trailingText = "待接入", trailingBadge = true, trailingColor = Color(0xFF94A3B8)')

# Guard rails for this patch.
required = [
    'var panelTab by rememberSaveable',
    'panelTab = RefPanelTab.Subscriptions',
    'Modifier.weight(1f), onSubscription',
    'private fun RefInlineGroupExpansion',
    'private fun RefInlineNodeCard',
    'AnimatedVisibility(',
    '全测速 ⚡',
    'providerProgress',
    'Brush.horizontalGradient(listOf(Color(0xFF3B82F6), Color(0xFF6366F1)))',
    'Modifier.fillMaxWidth().height(6.dp)',
    'height(100.dp)',
]
for token in required:
    if token not in text:
        raise SystemExit(f'missing invariant after patch: {token}')

if 'selectedGroupName = group.name\n                                        onDetailVisibleChanged(true)' in text:
    raise SystemExit('full-screen group routing still present')
if 'Color(0xFFD97706)) {\n                    unavailable(' in text:
    raise SystemExit('orange unavailable badge still present')

PATH.write_text(text, encoding='utf-8')
print('Applied test44 dashboard micro-interaction polish')
