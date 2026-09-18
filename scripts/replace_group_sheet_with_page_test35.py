from pathlib import Path

path = Path('android-app/app/src/main/java/io/github/xgl34222220/hetu/ReferenceProxyActivity.kt')
s = path.read_text()


def one(old: str, new: str, label: str):
    global s
    if old not in s:
        raise SystemExit(f'missing block: {label}')
    s = s.replace(old, new, 1)

# Full-screen secondary page needs system back + haptic feedback.
if 'import androidx.activity.compose.BackHandler\n' not in s:
    one('import androidx.activity.compose.setContent\n', 'import androidx.activity.compose.setContent\nimport androidx.activity.compose.BackHandler\n', 'BackHandler import')
if 'import androidx.compose.ui.hapticfeedback.HapticFeedbackType\n' not in s:
    one('import androidx.compose.ui.geometry.Offset\n', 'import androidx.compose.ui.geometry.Offset\nimport androidx.compose.ui.hapticfeedback.HapticFeedbackType\n', 'haptic type import')
if 'import androidx.compose.ui.platform.LocalHapticFeedback\n' not in s:
    one('import androidx.compose.ui.platform.LocalContext\n', 'import androidx.compose.ui.platform.LocalContext\nimport androidx.compose.ui.platform.LocalHapticFeedback\n', 'haptic import')

# Lift detail visibility to shell so the LuoShu dock disappears on the secondary full-screen page.
one(
'''    var page by rememberSaveable { mutableStateOf(RefProxyPage.Home) }
    var state by remember { mutableStateOf(ProxyComposeState()) }''',
'''    var page by rememberSaveable { mutableStateOf(RefProxyPage.Home) }
    var panelDetailVisible by rememberSaveable { mutableStateOf(false) }
    var state by remember { mutableStateOf(ProxyComposeState()) }''',
'panel detail shell state',
)
one(
'''                RefProxyPage.Panel -> RefPanel(state, repo, delays) { scope.launch { refresh() } }''',
'''                RefProxyPage.Panel -> RefPanel(
                    state = state,
                    repo = repo,
                    delays = delays,
                    onRefreshState = { scope.launch { refresh() } },
                    onDetailVisibleChanged = { panelDetailVisible = it },
                )''',
'panel invocation',
)
one(
'''        HetuGlassDock(
            items = dock,
            selected = dockPages.indexOf(page).coerceAtLeast(0),
            onSelect = { page = dockPages[it] },
            hazeState = haze,
            backdrop = liquidBackdrop.takeIf { liquid },
            modifier = Modifier.align(Alignment.BottomCenter),
        )''',
'''        if (!panelDetailVisible) {
            HetuGlassDock(
                items = dock,
                selected = dockPages.indexOf(page).coerceAtLeast(0),
                onSelect = { page = dockPages[it] },
                hazeState = haze,
                backdrop = liquidBackdrop.takeIf { liquid },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }''',
'hide dock in detail',
)

# Panel takes a visibility callback; remove bottom-sheet-only staging state.
one(
'''private fun RefPanel(
    state: ProxyComposeState,
    repo: ProxyDashboardRepository,
    delays: MutableMap<String, Long>,
    onRefreshState: () -> Unit,
) {''',
'''private fun RefPanel(
    state: ProxyComposeState,
    repo: ProxyDashboardRepository,
    delays: MutableMap<String, Long>,
    onRefreshState: () -> Unit,
    onDetailVisibleChanged: (Boolean) -> Unit,
) {''',
'panel signature',
)
one(
'''    var selectedGroupName by rememberSaveable { mutableStateOf<String?>(null) }
    val groupSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var pendingNode by rememberSaveable { mutableStateOf("") }
    val selectedLocal = remember { mutableStateMapOf<String, String>() }''',
'''    var selectedGroupName by rememberSaveable { mutableStateOf<String?>(null) }
    val selectedLocal = remember { mutableStateMapOf<String, String>() }''',
'remove sheet staging state',
)

# Keep dock visibility correct even if this panel leaves composition unexpectedly.
one(
'''    var previousConnections by remember { mutableStateOf<List<ProxyConnectionUi>>(emptyList()) }

    LaunchedEffect(state.connections) {''',
'''    var previousConnections by remember { mutableStateOf<List<ProxyConnectionUi>>(emptyList()) }

    DisposableEffect(Unit) {
        onDispose { onDetailVisibleChanged(false) }
    }

    LaunchedEffect(state.connections) {''',
'detail visibility cleanup',
)

# Strategy card enters secondary page immediately.
one(
'''                                    onClick = { selectedGroupName = group.name; pendingNode = selected },''',
'''                                    onClick = {
                                        selectedGroupName = group.name
                                        onDetailVisibleChanged(true)
                                    },''',
'group card opens full screen',
)

# Insert full-screen detail route before the main panel list.
marker = '''    LaunchedEffect(tab, state.running) { loadTab() }

    PullToRefreshBox(isRefreshing = refreshing, onRefresh = ::refresh, modifier = Modifier.fillMaxSize().blur(if (selectedGroupName != null) 4.dp else 0.dp)) {'''
replacement = '''    LaunchedEffect(tab, state.running) { loadTab() }

    val selectedGroup = selectedGroupName?.let { name -> state.groups.firstOrNull { it.name == name } }
    if (selectedGroup != null) {
        RefGroupDetailPage(
            state = state,
            group = selectedGroup,
            selected = selectedLocal[selectedGroup.name] ?: selectedGroup.now,
            delays = delays,
            testing = testing,
            onBack = {
                selectedGroupName = null
                onDetailVisibleChanged(false)
            },
            onRefresh = onRefreshState,
            onSelect = { node ->
                val previous = selectedLocal[selectedGroup.name] ?: selectedGroup.now
                selectedLocal[selectedGroup.name] = node
                scope.launch {
                    try {
                        repo.select(selectedGroup.name, node)
                        onRefreshState()
                    } catch (error: Exception) {
                        if (previous.isBlank()) selectedLocal.remove(selectedGroup.name)
                        else selectedLocal[selectedGroup.name] = previous
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
                selectedGroup.nodes.forEach { node ->
                    if (testing[node.name] != true) scope.launch {
                        testing[node.name] = true
                        try { delays[node.name] = repo.delay(node.name) }
                        catch (_: Exception) { delays[node.name] = -1L }
                        finally { testing.remove(node.name) }
                    }
                }
            },
        )
        return
    }

    PullToRefreshBox(isRefreshing = refreshing, onRefresh = ::refresh, modifier = Modifier.fillMaxSize()) {'''
one(marker, replacement, 'secondary page route')

# Replace the entire legacy bottom sheet + single-column node form with the independent detail page.
start = s.find('''\n    val selectedGroup = selectedGroupName?.let { name -> state.groups.firstOrNull { it.name == name } }\n    if (selectedGroup != null) {\n        ModalBottomSheet(''')
end_marker = '''\n@Composable\nprivate fun RefPanelTabs'''
end = s.find(end_marker, start)
if start < 0 or end < 0:
    raise SystemExit('missing legacy group sheet range')
new_detail = r'''
}

@Composable
private fun RefGroupDetailPage(
    state: ProxyComposeState,
    group: ProxyGroupUi,
    selected: String,
    delays: Map<String, Long>,
    testing: Map<String, Boolean>,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onSelect: (String) -> Unit,
    onDelay: (String) -> Unit,
    onTestAll: () -> Unit,
) {
    BackHandler(onBack = onBack)
    val t = LocalHetuTokens.current
    val scheme = MaterialTheme.colorScheme
    val dark = scheme.background.luminance() < .5f
    val haptic = LocalHapticFeedback.current
    var searchOpen by rememberSaveable(group.name) { mutableStateOf(false) }
    var query by rememberSaveable(group.name) { mutableStateOf("") }
    val tags = remember(group.nodes) { refNodeFilterTags(group.nodes) }
    var activeTag by rememberSaveable(group.name) { mutableStateOf("全部") }
    var lastBytes by remember(group.name) { mutableLongStateOf(state.uploadTotal + state.downloadTotal) }
    var lastRateAt by remember(group.name) { mutableLongStateOf(0L) }
    var liveRate by remember(group.name) { mutableLongStateOf(0L) }
    var entered by remember(group.name) { mutableStateOf(false) }
    LaunchedEffect(group.name) { entered = true }
    val enterX by animateDpAsState(
        targetValue = if (entered) 0.dp else 28.dp,
        animationSpec = spring(dampingRatio = .86f, stiffness = 430f),
        label = "groupDetailEnter",
    )
    LaunchedEffect(state.uploadTotal, state.downloadTotal) {
        val now = SystemClock.elapsedRealtime()
        val total = state.uploadTotal + state.downloadTotal
        if (lastRateAt > 0L && now > lastRateAt && total >= lastBytes) {
            liveRate = ((total - lastBytes) * 1000L / (now - lastRateAt)).coerceAtLeast(0L)
        }
        lastBytes = total
        lastRateAt = now
    }
    val filteredNodes = remember(group.nodes, query, activeTag) {
        group.nodes.filter { node ->
            val queryMatch = query.isBlank() || node.name.contains(query, true) || node.type.contains(query, true)
            val tagMatch = activeTag == "全部" || node.name.contains(activeTag, true) || node.type.contains(activeTag, true)
            queryMatch && tagMatch
        }
    }
    val testedCount = group.nodes.count { node ->
        val delay = delays[node.name] ?: node.lastDelay
        delay != null && delay > 0L
    }
    val anyTesting = group.nodes.any { testing[it.name] == true }
    val pageBackground = if (dark) t.pageBackground else Color(0xFFF4F6F9)

    Column(
        Modifier.fillMaxSize().offset(x = enterX).background(pageBackground).navigationBarsPadding(),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = if (dark) t.elevatedCardBackground.copy(alpha = .94f) else Color.White.copy(alpha = .94f),
            shadowElevation = 1.dp,
        ) {
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().height(56.dp).padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回", tint = t.textPrimary, modifier = Modifier.size(21.dp))
                }
                Text(
                    "127.0.0.1:${MihomoStartupConfig.CONTROLLER_PORT}",
                    color = t.textSecondary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onRefresh, modifier = Modifier.size(38.dp)) {
                    Icon(Icons.Rounded.Refresh, "刷新", tint = t.textSecondary, modifier = Modifier.size(18.dp))
                }
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, top = 14.dp, end = 16.dp, bottom = 22.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "节点选择",
                                color = t.textPrimary,
                                fontSize = 23.sp,
                                lineHeight = 29.sp,
                                fontWeight = FontWeight.ExtraBold,
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "${refGroupTypeCompact(group.type).uppercase()} · $testedCount/${group.nodes.size}",
                                    color = Color(0xFF94A3B8),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                )
                                IconButton(
                                    onClick = { searchOpen = !searchOpen; if (!searchOpen) query = "" },
                                    modifier = Modifier.size(30.dp),
                                ) {
                                    Icon(
                                        if (searchOpen) Icons.Rounded.Close else Icons.Rounded.Search,
                                        "搜索节点",
                                        tint = Color(0xFF94A3B8),
                                        modifier = Modifier.size(16.dp),
                                    )
                                }
                            }
                        }
                        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text("⚡ ${refSpeed(liveRate)}", color = Color(0xFF94A3B8), fontSize = 10.sp, fontWeight = FontWeight.Medium)
                            if (anyTesting) {
                                Text("$testedCount/${group.nodes.size}", color = Color(0xFFF59E0B), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                        Spacer(Modifier.width(10.dp))
                        val rocketSource = remember { MutableInteractionSource() }
                        val rocketPressed by rocketSource.collectIsPressedAsState()
                        val rocketScale by animateFloatAsState(if (rocketPressed) .94f else 1f, label = "rocketPress")
                        Box(
                            Modifier.size(44.dp)
                                .graphicsLayer { scaleX = rocketScale; scaleY = rocketScale }
                                .clip(RoundedCornerShape(14.dp))
                                .background(Color(0xFFFFF7ED))
                                .clickable(interactionSource = rocketSource, indication = null) {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    onTestAll()
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(Icons.Rounded.RocketLaunch, "测试全部节点", tint = Color(0xFFF59E0B), modifier = Modifier.size(24.dp))
                        }
                    }

                    if (searchOpen) {
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            placeholder = { Text("搜索节点或协议") },
                            leadingIcon = { Icon(Icons.Rounded.Search, null, modifier = Modifier.size(17.dp)) },
                            shape = RoundedCornerShape(14.dp),
                        )
                    }

                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(7.dp),
                    ) {
                        tags.forEach { tag ->
                            val active = tag == activeTag
                            Surface(
                                shape = CircleShape,
                                color = if (active) Color(0xFFEBF3FF) else t.cardBackground,
                                shadowElevation = if (active) 1.dp else 0.dp,
                                modifier = Modifier.clickable { activeTag = tag },
                            ) {
                                Text(
                                    tag,
                                    Modifier.padding(horizontal = 11.dp, vertical = 6.dp),
                                    color = if (active) scheme.primary else t.textSecondary,
                                    fontSize = 11.sp,
                                    fontWeight = if (active) FontWeight.Bold else FontWeight.SemiBold,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                }
            }

            if (filteredNodes.isEmpty()) {
                item {
                    Surface(shape = RoundedCornerShape(18.dp), color = t.cardBackground, shadowElevation = 0.dp) {
                        Column(
                            Modifier.fillMaxWidth().padding(vertical = 28.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Icon(Icons.Rounded.SearchOff, null, tint = t.textMuted, modifier = Modifier.size(24.dp))
                            Text("没有匹配的节点", color = t.textPrimary, fontWeight = FontWeight.SemiBold)
                            Text("换个关键词或筛选标签", color = t.textSecondary, fontSize = 12.sp)
                        }
                    }
                }
            } else {
                itemsIndexed(filteredNodes.chunked(2), key = { index, _ -> "detail-row-$index" }) { _, pair ->
                    Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                        pair.forEach { node ->
                            RefDetailNodeCard(
                                node = node,
                                active = node.name == selected,
                                delay = delays[node.name] ?: node.lastDelay,
                                testing = testing[node.name] == true,
                                modifier = Modifier.weight(1f),
                                onSelect = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    onSelect(node.name)
                                },
                                onDelay = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    onDelay(node.name)
                                },
                            )
                        }
                        if (pair.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun RefDetailNodeCard(
    node: ProxyNodeUi,
    active: Boolean,
    delay: Long?,
    testing: Boolean,
    modifier: Modifier,
    onSelect: () -> Unit,
    onDelay: () -> Unit,
) {
    val t = LocalHetuTokens.current
    val scheme = MaterialTheme.colorScheme
    val dark = scheme.background.luminance() < .5f
    val source = remember(node.name) { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) .98f else 1f, label = "detailNode${node.name}")
    val shape = RoundedCornerShape(16.dp)
    val background = when {
        active && dark -> scheme.primary.copy(alpha = .18f)
        active -> Color(0xFFEFF6FF)
        else -> t.cardBackground
    }
    val borderColor = if (active) scheme.primary.copy(alpha = .32f) else Color(0xFFF1F5F9)
    Column(
        modifier.height(68.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale; alpha = if (pressed) .94f else 1f }
            .background(background, shape)
            .border(.8.dp, borderColor, shape)
            .clip(shape)
            .clickable(interactionSource = source, indication = null, onClick = onSelect)
            .padding(horizontal = 11.dp, vertical = 9.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        val flag = refNodeFlag(node.name)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (flag.isNotBlank()) {
                Text(flag, fontSize = 14.sp)
                Spacer(Modifier.width(4.dp))
            }
            Text(
                node.name,
                color = if (active && !dark) Color(0xFF1E3A8A) else t.textPrimary,
                fontSize = 13.sp,
                lineHeight = 16.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = if (active) scheme.primary.copy(alpha = .10f) else Color(0xFFF1F5F9),
            ) {
                Text(
                    refNodeProtocol(node),
                    Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    color = if (active) scheme.primary else Color(0xFF64748B),
                    fontSize = 9.sp,
                    lineHeight = 12.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                )
            }
            Spacer(Modifier.weight(1f))
            Box(
                Modifier.size(26.dp).clip(CircleShape).clickable(onClick = onDelay),
                contentAlignment = Alignment.Center,
            ) {
                when {
                    testing -> CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                    delay != null && delay > 0L -> Text(refDelay(delay), color = scheme.primary, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
                    else -> Icon(Icons.Rounded.Bolt, "测速", tint = Color(0xFF94A3B8), modifier = Modifier.size(15.dp))
                }
            }
        }
    }
}

private fun refNodeProtocol(node: ProxyNodeUi): String {
    val base = node.type.ifBlank { "node" }.lowercase()
    return if (node.udp) "$base / udp" else base
}

private fun refNodeFilterTags(nodes: List<ProxyNodeUi>): List<String> {
    val candidates = listOf("无限", "移动", "联通", "电信", "香港", "日本", "新加坡", "美国", "台湾", "韩国", "自动", "直连")
    return buildList {
        add("全部")
        candidates.filterTo(this) { tag -> nodes.any { node -> node.name.contains(tag, true) || node.type.contains(tag, true) } }
    }
}
'''
s = s[:start] + '\n' + new_detail + s[end:]

# Hard invariants: legacy sheet/form/confirm must be gone, new full-screen page must exist.
for forbidden in [
    'groupSheetState',
    'pendingNode',
    'private fun RefNodeSheet(',
    'onConfirm = {',
]:
    if forbidden in s:
        raise SystemExit(f'legacy strategy UI still present: {forbidden}')
for required in [
    'private fun RefGroupDetailPage(',
    'private fun RefDetailNodeCard(',
    '.height(68.dp)',
    'Icons.Rounded.RocketLaunch',
    'onDetailVisibleChanged(true)',
    'if (!panelDetailVisible)',
]:
    if required not in s:
        raise SystemExit(f'missing full-screen strategy detail invariant: {required}')

path.write_text(s)
print('Replaced strategy bottom sheet with full-screen instant-switch detail page')
