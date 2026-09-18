from pathlib import Path

ui = Path('android-app/app/src/main/java/io/github/xgl34222220/hetu/ReferenceProxyActivity.kt')
s = ui.read_text()


def one(old: str, new: str, label: str):
    global s
    if old not in s:
        raise SystemExit(f'missing block: {label}')
    s = s.replace(old, new, 1)


one(
'''                RefProxyPage.Panel -> RefPanel(
                    state = state,
                    repo = repo,
                    delays = delays,
                    onRefreshState = { scope.launch { refresh() } },
                    onDetailVisibleChanged = { panelDetailVisible = it },
                )''',
'''                RefProxyPage.Panel -> RefPanel(
                    state = state,
                    repo = repo,
                    delays = delays,
                    onRefreshState = { scope.launch { refresh() } },
                    onOpenSettings = { page = RefProxyPage.Settings },
                    onDetailVisibleChanged = { panelDetailVisible = it },
                )''',
'panel shell callback',
)

one(
'''private fun RefPanel(
    state: ProxyComposeState,
    repo: ProxyDashboardRepository,
    delays: MutableMap<String, Long>,
    onRefreshState: () -> Unit,
    onDetailVisibleChanged: (Boolean) -> Unit,
) {''',
'''private fun RefPanel(
    state: ProxyComposeState,
    repo: ProxyDashboardRepository,
    delays: MutableMap<String, Long>,
    onRefreshState: () -> Unit,
    onOpenSettings: () -> Unit,
    onDetailVisibleChanged: (Boolean) -> Unit,
) {''',
'panel signature',
)

one(
'''            item {
                Column(Modifier.statusBarsPadding(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                            Text("代理面板", color = t.textPrimary, fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.Bold)
                            Text("${state.groups.size} 个策略组", color = t.textSecondary, style = MaterialTheme.typography.labelSmall)
                        }
                        IconButton(onClick = { context.startActivity(Intent(context, ProxyWebUiActivity::class.java)) }, modifier = Modifier.size(40.dp)) {
                            Icon(Icons.Rounded.Language, "WebUI", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(19.dp))
                        }
                        IconButton(onClick = { searchOpen = !searchOpen; if (!searchOpen) query = "" }, modifier = Modifier.size(40.dp)) {
                            Icon(if (searchOpen) Icons.Rounded.Close else Icons.Rounded.Search, "搜索", tint = t.textSecondary, modifier = Modifier.size(19.dp))
                        }
                    }
                    RefPanelTabs(tab) { tab = it }
                    if (searchOpen) {
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            placeholder = { Text("搜索策略组或节点") },
                            leadingIcon = { Icon(Icons.Rounded.Search, null, Modifier.size(18.dp)) },
                            shape = RoundedCornerShape(13.dp),
                        )
                    }
                }
            }''',
'''            item {
                Column(
                    Modifier.statusBarsPadding().padding(top = 14.dp, bottom = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row(
                        Modifier.fillMaxWidth().height(48.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "面板",
                            color = t.textPrimary,
                            fontSize = 26.sp,
                            lineHeight = 32.sp,
                            fontWeight = FontWeight.Black,
                            modifier = Modifier.weight(1f),
                        )
                        RefPanelHeaderAction(
                            icon = if (searchOpen) Icons.Rounded.Close else Icons.Rounded.Search,
                            contentDescription = if (searchOpen) "关闭搜索" else "搜索",
                            active = searchOpen,
                            onClick = { searchOpen = !searchOpen; if (!searchOpen) query = "" },
                        )
                        Spacer(Modifier.width(8.dp))
                        RefPanelHeaderAction(
                            icon = Icons.Rounded.Settings,
                            contentDescription = "设置",
                            onClick = onOpenSettings,
                        )
                    }
                    RefPanelTabs(tab) { tab = it }
                    if (searchOpen) {
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            placeholder = { Text("搜索策略组或节点") },
                            leadingIcon = { Icon(Icons.Rounded.Search, null, Modifier.size(18.dp)) },
                            shape = RoundedCornerShape(14.dp),
                        )
                    }
                }
            }''',
'panel header',
)

anchor = '''@Composable
private fun RefPanelTabs(selected: RefPanelTab, onSelect: (RefPanelTab) -> Unit) {'''
if anchor not in s:
    raise SystemExit('missing panel tabs anchor')
helper = '''@Composable
private fun RefPanelHeaderAction(
    icon: ImageVector,
    contentDescription: String,
    active: Boolean = false,
    onClick: () -> Unit,
) {
    val t = LocalHetuTokens.current
    val scheme = MaterialTheme.colorScheme
    val dark = scheme.background.luminance() < .5f
    val source = remember(contentDescription) { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(
        if (pressed) .94f else 1f,
        spring(dampingRatio = .78f, stiffness = 560f),
        label = "panelHeaderAction$contentDescription",
    )
    val fill = if (dark) t.elevatedCardBackground.copy(alpha = .78f) else Color.White.copy(alpha = .82f)
    val outline = if (dark) t.outline.copy(alpha = .38f) else Color(0xFFE2E8F0).copy(alpha = .62f)
    Box(
        Modifier.size(36.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale; alpha = if (pressed) .90f else 1f }
            .shadow(1.dp, CircleShape, clip = false)
            .background(fill, CircleShape)
            .border(.7.dp, outline, CircleShape)
            .clip(CircleShape)
            .clickable(interactionSource = source, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription,
            tint = if (active) scheme.primary else if (dark) t.textSecondary else Color(0xFF64748B),
            modifier = Modifier.size(17.dp),
        )
    }
}

'''
s = s.replace(anchor, helper + anchor, 1)

one(
'''@Composable
private fun RefPanelTabs(selected: RefPanelTab, onSelect: (RefPanelTab) -> Unit) {
    val t = LocalHetuTokens.current
    val scheme = MaterialTheme.colorScheme
    val dark = scheme.background.luminance() < .5f
    val tabs = RefPanelTab.entries
    val track = if (dark) t.controlBackground.copy(alpha = .86f) else Color(0xFFE2E8F0)
    val activeFill = if (dark) scheme.surfaceContainerHigh.copy(alpha = .94f) else Color.White
    BoxWithConstraints(
        Modifier.fillMaxWidth().height(42.dp).background(track, CircleShape).padding(4.dp),
    ) {
        val itemWidth = maxWidth / tabs.size.toFloat()
        val index = tabs.indexOf(selected).coerceAtLeast(0)
        val indicatorX by animateDpAsState(
            targetValue = itemWidth * index.toFloat(),
            animationSpec = spring(dampingRatio = .80f, stiffness = 440f),
            label = "panelSegmentIndicator",
        )
        Box(
            Modifier.offset(x = indicatorX).width(itemWidth).fillMaxHeight()
                .shadow(3.dp, CircleShape, clip = false)
                .clip(CircleShape)
                .background(activeFill),
        )
        Row(Modifier.fillMaxSize()) {
            tabs.forEach { tab ->
                val active = tab == selected
                val source = remember(tab) { MutableInteractionSource() }
                val pressed by source.collectIsPressedAsState()
                val scale by animateFloatAsState(if (pressed) .95f else 1f, spring(dampingRatio = .78f, stiffness = 560f), label = "tab${tab.name}")
                Box(
                    Modifier.width(itemWidth).fillMaxHeight()
                        .graphicsLayer { scaleX = scale; scaleY = scale; alpha = if (pressed) .90f else 1f }
                        .clip(CircleShape)
                        .clickable(interactionSource = source, indication = null) { onSelect(tab) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        tab.label,
                        color = if (active) (if (dark) scheme.onSurface else Color(0xFF0F172A)) else (if (dark) t.textSecondary else Color(0xFF64748B)),
                        fontSize = 11.sp,
                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}''',
'''@Composable
private fun RefPanelTabs(selected: RefPanelTab, onSelect: (RefPanelTab) -> Unit) {
    val t = LocalHetuTokens.current
    val scheme = MaterialTheme.colorScheme
    val dark = scheme.background.luminance() < .5f
    val tabs = RefPanelTab.entries
    val track = if (dark) t.controlBackground.copy(alpha = .68f) else Color(0xFFE2E8F0).copy(alpha = .60f)
    val activeFill = if (dark) scheme.surfaceContainerHigh.copy(alpha = .90f) else Color.White.copy(alpha = .96f)
    BoxWithConstraints(
        Modifier.fillMaxWidth().height(38.dp).background(track, CircleShape).padding(3.dp),
    ) {
        val itemWidth = maxWidth / tabs.size.toFloat()
        val index = tabs.indexOf(selected).coerceAtLeast(0)
        val indicatorX by animateDpAsState(
            targetValue = itemWidth * index.toFloat(),
            animationSpec = spring(dampingRatio = .84f, stiffness = 500f),
            label = "panelSegmentIndicator",
        )
        Box(
            Modifier.offset(x = indicatorX).width(itemWidth).fillMaxHeight()
                .shadow(2.dp, CircleShape, clip = false)
                .clip(CircleShape)
                .background(activeFill),
        )
        Row(Modifier.fillMaxSize()) {
            tabs.forEach { tab ->
                val active = tab == selected
                val source = remember(tab) { MutableInteractionSource() }
                val pressed by source.collectIsPressedAsState()
                val scale by animateFloatAsState(
                    if (pressed) .96f else 1f,
                    spring(dampingRatio = .80f, stiffness = 580f),
                    label = "tab${tab.name}",
                )
                Box(
                    Modifier.width(itemWidth).fillMaxHeight()
                        .graphicsLayer { scaleX = scale; scaleY = scale; alpha = if (pressed) .90f else 1f }
                        .clip(CircleShape)
                        .clickable(interactionSource = source, indication = null) { onSelect(tab) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        tab.label,
                        color = if (active) (if (dark) scheme.onSurface else Color(0xFF0F172A)) else (if (dark) t.textSecondary else Color(0xFF64748B)),
                        fontSize = 12.sp,
                        fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}''',
'panel tabs',
)

# Guardrails: the panel must be concise and the floating dock implementation must remain untouched.
for banned in ('Text("代理面板"', '个策略组", color = t.textSecondary'):
    if banned in s:
        raise SystemExit(f'old panel header still present: {banned}')
if 'Modifier.fillMaxWidth().height(38.dp).background(track, CircleShape).padding(3.dp)' not in s:
    raise SystemExit('38dp light segmented control missing')
if 'private fun RefPanelHeaderAction' not in s or 'Icons.Rounded.Settings' not in s:
    raise SystemExit('panel header action containers missing')

ui.write_text(s)
