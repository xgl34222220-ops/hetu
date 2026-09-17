from pathlib import Path
import re

PATH = Path('android-app/app/src/main/java/io/github/xgl34222220/bichen/ReferenceProxyActivity.kt')
text = PATH.read_text(encoding='utf-8')


def once(old: str, new: str, label: str):
    global text
    n = text.count(old)
    if n != 1:
        raise SystemExit(f'{label}: expected 1 match, got {n}')
    text = text.replace(old, new, 1)


def sub(pattern: str, repl: str, label: str):
    global text
    text2, n = re.subn(pattern, repl, text, count=1, flags=re.S)
    if n != 1:
        raise SystemExit(f'{label}: expected 1 regex match, got {n}')
    text = text2

# Imports for native haptics, fixed-width latency animation, and concurrent all-test completion.
once('import android.os.SystemClock\n', 'import android.os.SystemClock\nimport android.view.HapticFeedbackConstants\n', 'haptic constants import')
once('import androidx.compose.ui.platform.LocalHapticFeedback\n', 'import androidx.compose.ui.platform.LocalHapticFeedback\nimport androidx.compose.ui.platform.LocalView\n', 'LocalView import')
once('import kotlinx.coroutines.CancellationException\n', 'import kotlinx.coroutines.CancellationException\nimport kotlinx.coroutines.async\nimport kotlinx.coroutines.awaitAll\n', 'coroutine imports')

# Shell state: Home search button can route to Panel and open the inline search field.
once(
    '    var panelTab by rememberSaveable { mutableStateOf(RefPanelTab.Overview) }\n    var panelDetailVisible by rememberSaveable { mutableStateOf(false) }',
    '    var panelTab by rememberSaveable { mutableStateOf(RefPanelTab.Overview) }\n    var panelSearchRequest by rememberSaveable { mutableIntStateOf(0) }\n    var panelDetailVisible by rememberSaveable { mutableStateOf(false) }',
    'panel search request state',
)

once(
    '                    onSubscription = {\n                        panelTab = RefPanelTab.Subscriptions\n                        page = RefProxyPage.Panel\n                    },',
    '                    onSubscription = {\n                        panelTab = RefPanelTab.Subscriptions\n                        page = RefProxyPage.Panel\n                    },\n                    onSearch = {\n                        panelTab = RefPanelTab.Overview\n                        panelSearchRequest++\n                        page = RefProxyPage.Panel\n                    },\n                    onSettings = { page = RefProxyPage.Settings },',
    'home header routes',
)

once(
    '                    selectedTab = panelTab,\n                    onSelectedTabChange = { panelTab = it },\n                    hazeState = haze,',
    '                    selectedTab = panelTab,\n                    onSelectedTabChange = { panelTab = it },\n                    searchRequest = panelSearchRequest,\n                    hazeState = haze,',
    'pass panel search request',
)

once(
    '    onLog: () -> Unit,\n    onSubscription: () -> Unit,\n) {',
    '    onLog: () -> Unit,\n    onSubscription: () -> Unit,\n    onSearch: () -> Unit,\n    onSettings: () -> Unit,\n) {',
    'extend home callbacks',
)

# Upgrade Home top bar: stronger typography, safe-area breathing room, two contained actions.
sub(
    r'''        item \{\n            Row\(\n                Modifier\.fillMaxWidth\(\)\.statusBarsPadding\(\)\.padding\(top = 14\.dp, bottom = 10\.dp\),\n                verticalAlignment = Alignment\.CenterVertically,\n            \) \{\n                Text\(\n                    "BoxProxy",\n                    color = t\.textPrimary,\n                    fontSize = 22\.sp,\n                    lineHeight = 28\.sp,\n                    fontWeight = FontWeight\.ExtraBold,\n                    modifier = Modifier\.weight\(1f\),\n                \)\n            \}\n        \}''',
    '''        item {
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().padding(top = 12.dp, bottom = 10.dp).height(44.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "BoxProxy",
                    color = if (scheme.background.luminance() < .5f) t.textPrimary else Color(0xFF0F172A),
                    fontSize = 26.sp,
                    lineHeight = 32.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = (-0.6).sp,
                    modifier = Modifier.weight(1f),
                )
                RefPanelHeaderAction(
                    icon = Icons.Rounded.Search,
                    contentDescription = "搜索",
                    onClick = onSearch,
                )
                Spacer(Modifier.width(8.dp))
                RefPanelHeaderAction(
                    icon = Icons.Rounded.Settings,
                    contentDescription = "设置",
                    onClick = onSettings,
                )
            }
        }''',
    'home premium topbar',
)

# Home latency panel also keeps the previous value while testing instead of flashing to ellipsis.
once(
    '            if (testing) "…" else refDelay(value),',
    '            refDelay(value),',
    'keep home latency value during testing',
)

# RefPanel consumes a search request token from Home.
once(
    '    selectedTab: RefPanelTab,\n    onSelectedTabChange: (RefPanelTab) -> Unit,\n    hazeState: HazeState,',
    '    selectedTab: RefPanelTab,\n    onSelectedTabChange: (RefPanelTab) -> Unit,\n    searchRequest: Int,\n    hazeState: HazeState,',
    'extend RefPanel search request',
)

once(
    '    val t = LocalBichenTokens.current\n    val haptic = LocalHapticFeedback.current\n    val tab = selectedTab',
    '    val t = LocalBichenTokens.current\n    val haptic = LocalHapticFeedback.current\n    val view = LocalView.current\n    val tab = selectedTab',
    'panel native haptics',
)

once(
    '    LaunchedEffect(tab, state.running) { loadTab() }\n\n    LaunchedEffect(tab) {',
    '    LaunchedEffect(tab, state.running) { loadTab() }\n    LaunchedEffect(searchRequest) { if (searchRequest > 0) searchOpen = true }\n\n    LaunchedEffect(tab) {',
    'open search on home route',
)

# Make group-expand and node-select haptics more like native selection ticks.
once(
    '                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)\n                                            selectedGroupName = if (selectedGroupName == group.name) null else group.name',
    '                                            view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)\n                                            selectedGroupName = if (selectedGroupName == group.name) null else group.name',
    'accordion haptic',
)
once(
    '                                            selectedLocal[group.name] = node\n                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)\n                                            scope.launch {',
    '                                            selectedLocal[group.name] = node\n                                            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)\n                                            scope.launch {',
    'node selection haptic',
)

# All-test runs concurrently, does not blank previous numbers, and gives one completion confirmation.
sub(
    r'''                                        onTestAll = \{\n                                            group\.nodes\.forEach \{ node ->\n                                                if \(testing\[node\.name\] != true\) scope\.launch \{\n                                                    testing\[node\.name\] = true\n                                                    try \{ delays\[node\.name\] = repo\.delay\(node\.name\) \}\n                                                    catch \(_: Exception\) \{ delays\[node\.name\] = -1L \}\n                                                    finally \{ testing\.remove\(node\.name\) \}\n                                                \}\n                                            \}\n                                        \},''',
    '''                                        onTestAll = {
                                            val pending = group.nodes.filter { testing[it.name] != true }
                                            if (pending.isNotEmpty()) scope.launch {
                                                pending.forEach { testing[it.name] = true }
                                                try {
                                                    pending.map { node ->
                                                        async {
                                                            try { delays[node.name] = repo.delay(node.name) }
                                                            catch (_: Exception) { delays[node.name] = -1L }
                                                        }
                                                    }.awaitAll()
                                                    view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                                                } finally {
                                                    pending.forEach { testing.remove(it.name) }
                                                }
                                            }
                                        },''',
    'all-test completion haptic',
)

# Stagger subnodes by 20ms while preserving the two-column grid's physical footprint.
inline = r'''@Composable
private fun RefInlineGroupExpansion(
    group: ProxyGroupUi,
    selected: String,
    delays: Map<String, Long>,
    testing: Map<String, Boolean>,
    onSelect: (String) -> Unit,
    onDelay: (String) -> Unit,
    onTestAll: () -> Unit,
) {
    val t = LocalBichenTokens.current
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
            group.nodes.withIndex().toList().chunked(2).forEach { pair ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    pair.forEach { entry ->
                        val node = entry.value
                        RefInlineNodeCard(
                            index = entry.index,
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
    index: Int,
    node: ProxyNodeUi,
    active: Boolean,
    delay: Long?,
    testing: Boolean,
    modifier: Modifier,
    onSelect: () -> Unit,
    onDelay: () -> Unit,
) {
    val t = LocalBichenTokens.current
    val dark = MaterialTheme.colorScheme.background.luminance() < .5f
    val shape = RoundedCornerShape(14.dp)
    val source = remember(node.name) { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) .96f else 1f, spring(dampingRatio = .74f, stiffness = 560f), label = "inlineNode${node.name}")
    var revealed by remember(node.name) { mutableStateOf(false) }
    LaunchedEffect(node.name) {
        delay((index * 20L).coerceAtMost(260L))
        revealed = true
    }
    val background = when {
        dark && active -> Color(0xFF172554)
        dark -> t.cardBackground
        active -> Color(0xFFF8FBFF)
        else -> Color.White
    }
    Box(modifier.height(50.dp)) {
        androidx.compose.animation.AnimatedVisibility(
            visible = revealed,
            modifier = Modifier.fillMaxSize(),
            enter = androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(180)) +
                androidx.compose.animation.slideInVertically(androidx.compose.animation.core.tween(220)) { it / 3 },
        ) {
            Row(
                Modifier.fillMaxSize()
                    .graphicsLayer { scaleX = scale; scaleY = scale; alpha = if (pressed) .88f else 1f }
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
    }
}
'''
sub(
    r'@Composable\nprivate fun RefInlineGroupExpansion\(.*?\n\}\n\n@Composable\nprivate fun RefGroupCornerVisual',
    inline + '\n@Composable\nprivate fun RefGroupCornerVisual',
    'stagger inline nodes',
)

# Commercial latency pill: 58dp minimum width, tabular digits, old value retained during testing,
# pulsing border/opacity, and 220ms cross-fade + spring reveal when a fresh number lands.
delay_badge = r'''@Composable
private fun RefDelayBadge(value: Long?, testing: Boolean, onClick: (() -> Unit)?) {
    val text = refDelay(value)
    val (background, textColor) = when {
        value == null -> Color(0xFFF1F5F9) to Color(0xFF64748B)
        value <= 0L -> Color(0xFFFEF2F2) to Color(0xFFDC2626)
        value > 200L -> Color(0xFFFFF7ED) to Color(0xFFD97706)
        else -> Color(0xFFEFF6FF) to Color(0xFF2563EB)
    }
    val source = remember(onClick) { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val infinite = androidx.compose.animation.core.rememberInfiniteTransition(label = "latencyPulse")
    val pulse by infinite.animateFloat(
        initialValue = .66f,
        targetValue = 1f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
            animation = androidx.compose.animation.core.tween(620),
            repeatMode = androidx.compose.animation.core.RepeatMode.Reverse,
        ),
        label = "latencyPulseAlpha",
    )
    var revealTarget by remember { mutableFloatStateOf(1f) }
    LaunchedEffect(text) {
        revealTarget = .94f
        delay(26)
        revealTarget = 1f
    }
    val reveal by animateFloatAsState(revealTarget, spring(dampingRatio = .64f, stiffness = 520f), label = "latencyReveal")
    val pressScale by animateFloatAsState(if (pressed) .96f else 1f, spring(dampingRatio = .74f, stiffness = 620f), label = "latencyPress")
    val alpha = if (testing) pulse else 1f
    Box(
        Modifier.widthIn(min = 58.dp).height(22.dp)
            .graphicsLayer { scaleX = reveal * pressScale; scaleY = reveal * pressScale; this.alpha = if (pressed) .86f else alpha }
            .background(if (testing) background.copy(alpha = .72f) else background, CircleShape)
            .border(.7.dp, if (testing) Color(0xFF2563EB).copy(alpha = .24f + .28f * pulse) else Color.Transparent, CircleShape)
            .then(if (onClick != null) Modifier.clickable(enabled = !testing, interactionSource = source, indication = null, onClick = onClick) else Modifier)
            .padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.animation.Crossfade(
            targetState = text,
            animationSpec = androidx.compose.animation.core.tween(220),
            label = "latencyCrossFade",
        ) { shown ->
            Text(
                shown,
                color = textColor,
                fontSize = 11.sp,
                lineHeight = 14.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
            )
        }
    }
}'''
sub(
    r'@Composable\nprivate fun RefDelayBadge\(.*?\n\}\n\n@Composable\nprivate fun RefTrafficOverview',
    delay_badge + '\n\n@Composable\nprivate fun RefTrafficOverview',
    'latency badge polish',
)

# Header actions receive a native tick in addition to their spring press animation.
once(
    '    val t = LocalBichenTokens.current\n    val scheme = MaterialTheme.colorScheme\n    val dark = scheme.background.luminance() < .5f\n    val source = remember(contentDescription) { MutableInteractionSource() }',
    '    val t = LocalBichenTokens.current\n    val scheme = MaterialTheme.colorScheme\n    val dark = scheme.background.luminance() < .5f\n    val view = LocalView.current\n    val source = remember(contentDescription) { MutableInteractionSource() }',
    'header native haptic view',
)
once(
    '            .clickable(interactionSource = source, indication = null, onClick = onClick),',
    '            .clickable(interactionSource = source, indication = null) {\n                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)\n                onClick()\n            },',
    'header haptic click',
)

# Primary home action pills get a light native haptic while preserving existing spring behavior.
once(
    '    val t = LocalBichenTokens.current\n    val dark = MaterialTheme.colorScheme.background.luminance() < .5f\n    val source = remember(text) { MutableInteractionSource() }',
    '    val t = LocalBichenTokens.current\n    val dark = MaterialTheme.colorScheme.background.luminance() < .5f\n    val view = LocalView.current\n    val source = remember(text) { MutableInteractionSource() }',
    'action haptic view',
)
once(
    '            .clickable(enabled = enabled, interactionSource = source, indication = null, onClick = onClick),',
    '            .clickable(enabled = enabled, interactionSource = source, indication = null) {\n                view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)\n                onClick()\n            },',
    'action haptic click',
)

# Existing list rows already have press feedback; bring them to the same 0.96 spring depth.
text = text.replace('if (pressed) .985f else 1f', 'if (pressed) .96f else 1f')
text = text.replace('if (pressed && onClick != null) .985f else 1f', 'if (pressed && onClick != null) .96f else 1f')

# Ensure test44 visual decisions survive.
required = [
    'private fun RefInlineGroupExpansion',
    'panelTab = RefPanelTab.Subscriptions',
    'providerProgress',
    'trailingColor = Color(0xFF94A3B8)',
    'widthIn(min = 58.dp).height(22.dp)',
    'fontFeatureSettings = "tnum"',
    'latencyCrossFade',
    'latencyPulseAlpha',
    'panelSearchRequest++',
    'fontSize = 26.sp',
    'letterSpacing = (-0.6).sp',
    'HapticFeedbackConstants.CONFIRM',
    'index * 20L',
]
for token in required:
    if token not in text:
        raise SystemExit(f'missing invariant: {token}')

PATH.write_text(text, encoding='utf-8')
print('Applied test45 micro-motion polish')
