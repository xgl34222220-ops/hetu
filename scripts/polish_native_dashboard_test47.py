from pathlib import Path
import re

UI = Path('android-app/app/src/main/java/io/github/xgl34222220/bichen/ReferenceProxyActivity.kt')
SUB = Path('android-app/app/src/main/java/io/github/xgl34222220/bichen/ProxySubscriptionActivity.kt')
text = UI.read_text(encoding='utf-8')
sub = SUB.read_text(encoding='utf-8')


def replace_once(src: str, old: str, new: str, label: str) -> str:
    count = src.count(old)
    if count != 1:
        raise SystemExit(f'{label}: expected 1 match, found {count}')
    return src.replace(old, new, 1)


def replace_regex(src: str, pattern: str, repl: str, label: str) -> str:
    out, count = re.subn(pattern, repl, src, count=1, flags=re.S)
    if count != 1:
        raise SystemExit(f'{label}: expected 1 regex match, found {count}')
    return out

# ---------------------------------------------------------------------------
# 1) Home identity: project name only, no redundant Home actions.
# ---------------------------------------------------------------------------
text = text.replace(
    '''                    onSubscription = {\n                        panelTab = RefPanelTab.Subscriptions\n                        page = RefProxyPage.Panel\n                    },\n                    onSearch = {\n                        panelTab = RefPanelTab.Overview\n                        panelSearchRequest++\n                        page = RefProxyPage.Panel\n                    },\n                    onSettings = { page = RefProxyPage.Settings },''',
    '''                    onSubscription = {\n                        panelTab = RefPanelTab.Subscriptions\n                        page = RefProxyPage.Panel\n                    },''',
    1,
)
text = replace_once(
    text,
    '''    onLog: () -> Unit,\n    onSubscription: () -> Unit,\n    onSearch: () -> Unit,\n    onSettings: () -> Unit,\n) {''',
    '''    onLog: () -> Unit,\n    onSubscription: () -> Unit,\n) {''',
    'remove redundant home action params',
)
old_home_header = '''        item {\n            Box(\n                Modifier.fillMaxWidth().statusBarsPadding().padding(top = 16.dp, bottom = 10.dp).height(44.dp),\n                contentAlignment = Alignment.CenterStart,\n            ) {\n                Text(\n                    "BoxProxy",\n                    color = if (scheme.background.luminance() < .5f) t.textPrimary else Color(0xFF0F172A),\n                    fontSize = 26.sp,\n                    lineHeight = 32.sp,\n                    fontWeight = FontWeight.ExtraBold,\n                    letterSpacing = (-0.6).sp,\n                )\n            }\n        }'''
new_home_header = '''        item {\n            Row(\n                Modifier.fillMaxWidth().statusBarsPadding().padding(top = 16.dp, bottom = 10.dp).height(44.dp),\n                verticalAlignment = Alignment.CenterVertically,\n            ) {\n                Text(\n                    "辟尘",\n                    color = if (scheme.background.luminance() < .5f) t.textPrimary else Color(0xFF0F172A),\n                    fontSize = 24.sp,\n                    lineHeight = 30.sp,\n                    fontWeight = FontWeight.ExtraBold,\n                    letterSpacing = (-0.45).sp,\n                )\n                Spacer(Modifier.width(8.dp))\n                Surface(\n                    shape = CircleShape,\n                    color = if (scheme.background.luminance() < .5f) Color.White.copy(alpha = .07f) else Color(0xFFE2E8F0).copy(alpha = .62f),\n                    tonalElevation = 0.dp,\n                ) {\n                    Text(\n                        "Mihomo Core",\n                        Modifier.padding(horizontal = 8.dp, vertical = 3.dp),\n                        color = Color(0xFF94A3B8),\n                        fontSize = 9.sp,\n                        lineHeight = 12.sp,\n                        fontWeight = FontWeight.SemiBold,\n                        letterSpacing = .2.sp,\n                    )\n                }\n            }\n        }'''
text = replace_once(text, old_home_header, new_home_header, 'rename home title')

# Dashboard keeps only the clean search ghost icon.
text = replace_regex(
    text,
    r'''            RefPanelHeaderAction\(\n                icon = if \(searchOpen\) Icons\.Rounded\.Close else Icons\.Rounded\.Search,.*?\n            \)\n            Spacer\(Modifier\.width\(8\.dp\)\)\n            RefPanelHeaderAction\(\n                icon = Icons\.Rounded\.MoreHoriz,.*?\n            \)''',
    '''            RefPanelHeaderAction(\n                icon = if (searchOpen) Icons.Rounded.Close else Icons.Rounded.Search,\n                contentDescription = if (searchOpen) "关闭搜索" else "搜索",\n                active = searchOpen,\n                onClick = onSearchToggle,\n            )''',
    'remove dashboard more action',
)

# ---------------------------------------------------------------------------
# 2) Site latency: silent refresh on core start/resume + user selectable heartbeat.
# ---------------------------------------------------------------------------
old_measure = '''    fun measureSites() {\n        if (!state.running || testing) return\n        scope.launch {\n            testing = true\n            try {\n                siteDelays = repo.siteLatencies()\n                if (siteDelays.values.none { it > 0L }) message = "关键站点测速失败，请检查当前网络"\n            } catch (cancel: CancellationException) {\n                throw cancel\n            } catch (error: Exception) {\n                message = error.message ?: "测速失败"\n            } finally {\n                testing = false\n            }\n        }\n    }'''
new_measure = '''    suspend fun measureSitesInternal(reportError: Boolean) {\n        if (!state.running || testing) return\n        testing = true\n        try {\n            val measured = repo.siteLatencies()\n            if (measured.isNotEmpty()) siteDelays = measured\n            if (reportError && measured.values.none { it > 0L }) {\n                message = "关键站点测速失败，请检查当前网络"\n            }\n        } catch (cancel: CancellationException) {\n            throw cancel\n        } catch (error: Exception) {\n            if (reportError) message = error.message ?: "测速失败"\n        } finally {\n            testing = false\n        }\n    }\n\n    fun measureSites() {\n        if (!state.running || testing) return\n        scope.launch { measureSitesInternal(reportError = true) }\n    }'''
text = replace_once(text, old_measure, new_measure, 'replace latency measurement core')

old_resume = '''    // Returning from Theme/secondary activities should refresh data in place. Never\n    // replace the composition or reset state/runtime/providers to their empty defaults.\n    LaunchedEffect(resumeRevision) {\n        if (resumeRevision > 1) refresh()\n    }\n\n    LaunchedEffect(state.running) {\n        if (!state.running) { siteDelays = emptyMap(); return@LaunchedEffect }\n        siteDelays = runCatching { repo.siteLatencies() }.getOrDefault(siteDelays)\n        while (true) {\n            delay(15_000)\n            siteDelays = runCatching { repo.siteLatencies() }.getOrDefault(siteDelays)\n        }\n    }'''
new_resume = '''    // Returning from a secondary activity refreshes runtime state and silently\n    // measures the three Home endpoints without clearing the previous values.\n    LaunchedEffect(resumeRevision) {\n        if (resumeRevision > 1) {\n            refresh()\n            if (state.running) measureSitesInternal(reportError = false)\n        }\n    }\n\n    // Start transition triggers one silent measurement immediately. The optional\n    // heartbeat is user controlled: off / 30 s / 60 s. Keep the previous numbers\n    // on screen while testing so there is no layout flash.\n    LaunchedEffect(state.running) {\n        if (!state.running) {\n            siteDelays = emptyMap()\n            return@LaunchedEffect\n        }\n        measureSitesInternal(reportError = false)\n        while (true) {\n            val seconds = prefs.getInt("latencyAutoRefreshSeconds", 60).takeIf { it == 0 || it == 30 || it == 60 } ?: 60\n            if (seconds <= 0) {\n                delay(1_000)\n                continue\n            }\n            delay(seconds * 1_000L)\n            measureSitesInternal(reportError = false)\n        }\n    }'''
text = replace_once(text, old_resume, new_resume, 'replace latency lifecycle polling')

# Home refresh glyph rotates rather than swapping into a generic loader.
old_latency_header = '''                IconButton(onClick = onClick, enabled = !testing, modifier = Modifier.size(32.dp)) {\n                    if (testing) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)\n                    else Icon(Icons.Rounded.Refresh, "全部测速", tint = t.textSecondary, modifier = Modifier.size(18.dp))\n                }'''
new_latency_header = '''                val spinner = androidx.compose.animation.core.rememberInfiniteTransition(label = "homeLatencyRefresh")\n                val rotation by spinner.animateFloat(\n                    initialValue = 0f,\n                    targetValue = 360f,\n                    animationSpec = androidx.compose.animation.core.infiniteRepeatable(\n                        animation = androidx.compose.animation.core.tween(900, easing = androidx.compose.animation.core.LinearEasing),\n                    ),\n                    label = "homeLatencyRefreshRotation",\n                )\n                IconButton(onClick = onClick, enabled = !testing, modifier = Modifier.size(32.dp)) {\n                    Icon(\n                        Icons.Rounded.Refresh,\n                        "全部测速",\n                        tint = t.textSecondary,\n                        modifier = Modifier.size(18.dp).graphicsLayer {\n                            rotationZ = if (testing) rotation else 0f\n                            alpha = if (testing) .62f else 1f\n                        },\n                    )\n                }'''
text = replace_once(text, old_latency_header, new_latency_header, 'rotating home latency refresh')

# ---------------------------------------------------------------------------
# 3) Frosted pill action bar for reload / stop-start / restart.
# ---------------------------------------------------------------------------
old_action_calls = '''                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {\n                        val neutralAction = if (scheme.background.luminance() < .5f) Color(0xFFE2E8F0) else Color(0xFF334155)\n                        RefActionText("重载", state.running && operation.isBlank(), onReload, Modifier.weight(1f), neutralAction)\n                        RefActionText(if (state.running) "停止" else "启动", operation.isBlank(), onToggle, Modifier.weight(1f), Color(0xFFEF4444))\n                        RefActionText("重启", state.running && operation.isBlank(), onRestart, Modifier.weight(1f), neutralAction)\n                    }'''
new_action_calls = '''                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {\n                        val neutralAction = if (scheme.background.luminance() < .5f) Color(0xFFE2E8F0) else Color(0xFF334155)\n                        RefActionText("重载", state.running && operation.isBlank(), onReload, Modifier.weight(1f), neutralAction, Icons.Rounded.Refresh)\n                        RefActionText(\n                            if (state.running) "停止" else "启动",\n                            operation.isBlank(),\n                            onToggle,\n                            Modifier.weight(1f),\n                            if (state.running) Color(0xFFE11D48) else Color(0xFF2563EB),\n                            if (state.running) null else Icons.Rounded.PlayArrow,\n                            danger = state.running,\n                        )\n                        RefActionText("重启", state.running && operation.isBlank(), onRestart, Modifier.weight(1f), neutralAction, Icons.Rounded.RestartAlt)\n                    }'''
text = replace_once(text, old_action_calls, new_action_calls, 'upgrade hero action calls')

new_action_fn = r'''@Composable
private fun RefActionText(
    text: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier,
    color: Color = LocalBichenTokens.current.textPrimary,
    icon: ImageVector? = null,
    danger: Boolean = false,
) {
    val t = LocalBichenTokens.current
    val dark = MaterialTheme.colorScheme.background.luminance() < .5f
    val view = LocalView.current
    val source = remember(text) { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(
        if (pressed) .95f else 1f,
        spring(dampingRatio = .68f, stiffness = 650f),
        label = "heroAction$text",
    )
    val shape = CircleShape
    val background = when {
        danger && dark -> Color(0xFF4C1724).copy(alpha = .78f)
        danger -> Color(0xFFFFF1F2).copy(alpha = .94f)
        dark -> Color.White.copy(alpha = .075f)
        else -> Color.White.copy(alpha = .92f)
    }
    val borderColor = when {
        danger && dark -> Color(0xFFFB7185).copy(alpha = .18f)
        danger -> Color(0xFFFFE4E6)
        dark -> Color.White.copy(alpha = .09f)
        else -> Color.White.copy(alpha = .90f)
    }
    val shadowColor = if (danger) Color(0xFFF43F5E).copy(alpha = .12f) else Color(0xFF0F172A).copy(alpha = .055f)
    Row(
        modifier
            .height(40.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale; alpha = if (pressed) .86f else if (enabled) 1f else .50f }
            .shadow(5.dp, shape, clip = false, ambientColor = shadowColor, spotColor = shadowColor)
            .background(background, shape)
            .border(.7.dp, borderColor, shape)
            .clip(shape)
            .clickable(enabled = enabled, interactionSource = source, indication = null) {
                view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                onClick()
            }
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (danger) {
            val pulse = androidx.compose.animation.core.rememberInfiniteTransition(label = "stopPulse")
            val dotAlpha by pulse.animateFloat(
                initialValue = .52f,
                targetValue = 1f,
                animationSpec = androidx.compose.animation.core.infiniteRepeatable(
                    animation = androidx.compose.animation.core.tween(760),
                    repeatMode = androidx.compose.animation.core.RepeatMode.Reverse,
                ),
                label = "stopPulseAlpha",
            )
            Box(Modifier.size(6.dp).graphicsLayer { alpha = dotAlpha }.background(Color(0xFFF43F5E), CircleShape))
            Spacer(Modifier.width(6.dp))
        } else if (icon != null) {
            Icon(icon, null, tint = color.copy(alpha = .68f), modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(text, color = color, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}'''
text = replace_regex(
    text,
    r'@Composable\nprivate fun RefActionText\(.*?\n\}\n\n@Composable\nprivate fun RefMetricCard',
    new_action_fn + '\n\n@Composable\nprivate fun RefMetricCard',
    'rewrite frosted hero action component',
)

# ---------------------------------------------------------------------------
# 4) Inline nodes: vertical two-tier 66dp cards so names get a full row.
# ---------------------------------------------------------------------------
new_inline_node = r'''@Composable
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
    val shape = RoundedCornerShape(16.dp)
    val source = remember(node.name) { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) .96f else 1f, spring(dampingRatio = .72f, stiffness = 580f), label = "inlineNode${node.name}")
    var revealed by remember(node.name) { mutableStateOf(false) }
    LaunchedEffect(node.name) {
        delay((index * 20L).coerceAtMost(260L))
        revealed = true
    }
    val backgroundBrush = when {
        dark && active -> Brush.verticalGradient(listOf(Color(0xFF172554), Color(0xFF111C38)))
        dark -> Brush.verticalGradient(listOf(t.elevatedCardBackground, t.cardBackground))
        active -> Brush.verticalGradient(listOf(Color.White, Color(0xFFF3F7FF)))
        else -> Brush.verticalGradient(listOf(Color.White, Color(0xFFFAFCFF)))
    }
    Box(modifier.height(66.dp)) {
        androidx.compose.animation.AnimatedVisibility(
            visible = revealed,
            modifier = Modifier.fillMaxSize(),
            enter = androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(180)) +
                androidx.compose.animation.slideInVertically(androidx.compose.animation.core.tween(220)) { it / 3 },
        ) {
            Column(
                Modifier.fillMaxSize()
                    .graphicsLayer { scaleX = scale; scaleY = scale; alpha = if (pressed) .86f else 1f }
                    .shadow(if (active) 4.dp else 2.dp, shape, clip = false)
                    .background(backgroundBrush, shape)
                    .border(
                        if (active) 1.4.dp else .8.dp,
                        if (active) Color(0xFF2563EB) else if (dark) Color.White.copy(alpha = .08f) else Color.White.copy(alpha = .90f),
                        shape,
                    )
                    .clip(shape)
                    .clickable(interactionSource = source, indication = null, onClick = onSelect)
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    val flag = refNodeFlag(node.name)
                    if (flag.isNotBlank()) {
                        Text(flag, fontSize = 14.sp, modifier = Modifier.width(20.dp))
                        Spacer(Modifier.width(4.dp))
                    }
                    Text(
                        node.name,
                        color = if (active) Color(0xFF1E3A8A) else t.textPrimary,
                        fontSize = 12.sp,
                        lineHeight = 15.sp,
                        fontWeight = if (active) FontWeight.Bold else FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Box(Modifier.size(17.dp), contentAlignment = Alignment.Center) {
                        androidx.compose.animation.AnimatedVisibility(
                            visible = active,
                            enter = androidx.compose.animation.scaleIn(
                                initialScale = .05f,
                                animationSpec = spring(dampingRatio = .50f, stiffness = 520f),
                            ) + androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(130)),
                            exit = androidx.compose.animation.scaleOut(targetScale = .45f) + androidx.compose.animation.fadeOut(),
                        ) {
                            Box(Modifier.size(16.dp).background(Color(0xFF2563EB), CircleShape), contentAlignment = Alignment.Center) {
                                Icon(Icons.Rounded.Check, "已选择", tint = Color.White, modifier = Modifier.size(11.dp))
                            }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        shape = RoundedCornerShape(7.dp),
                        color = if (dark) Color.White.copy(alpha = .055f) else Color(0xFFF8FAFC),
                        tonalElevation = 0.dp,
                    ) {
                        Text(
                            refNodeProtocol(node),
                            Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            color = Color(0xFF94A3B8),
                            fontSize = 9.sp,
                            lineHeight = 11.sp,
                            fontWeight = FontWeight.Medium,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            maxLines = 1,
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    RefDelayBadge(delay, testing, onDelay)
                }
            }
        }
    }
}'''
text = replace_regex(
    text,
    r'@Composable\nprivate fun RefInlineNodeCard\(.*?\n\}\n\n@Composable\nprivate fun RefGroupCornerVisual',
    new_inline_node + '\n\n@Composable\nprivate fun RefGroupCornerVisual',
    'rewrite inline node cards',
)

# ---------------------------------------------------------------------------
# 5) Home subscription card gets a compact geometric usage rail.
# ---------------------------------------------------------------------------
old_subscription_middle = '''            Text(if (total > 0L) refBytes(used) else "—", color = valueColor, fontSize = 16.sp, lineHeight = 19.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)\n            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {'''
new_subscription_middle = '''            Text(if (total > 0L) refBytes(used) else "—", color = valueColor, fontSize = 16.sp, lineHeight = 19.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)\n            Box(Modifier.fillMaxWidth().height(4.dp).background(Color(0xFFEFF3F8), CircleShape)) {\n                if (total > 0L && ratio > 0f) {\n                    Box(\n                        Modifier.fillMaxWidth(ratio.coerceIn(.001f, 1f)).fillMaxHeight()\n                            .background(Brush.horizontalGradient(listOf(Color(0xFF3B82F6), Color(0xFF6366F1))), CircleShape),\n                    )\n                }\n            }\n            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {'''
text = replace_once(text, old_subscription_middle, new_subscription_middle, 'home subscription usage rail')

# ---------------------------------------------------------------------------
# 6) Auto refresh interval setting: off / 30 s / 60 s.
# ---------------------------------------------------------------------------
text = replace_once(
    text,
    '''    var modePicker by remember { mutableStateOf(false) }\n    var ipv6Picker by remember { mutableStateOf(false) }\n    var portsInfo by remember { mutableStateOf(false) }''',
    '''    var modePicker by remember { mutableStateOf(false) }\n    var ipv6Picker by remember { mutableStateOf(false) }\n    var latencyPicker by remember { mutableStateOf(false) }\n    var portsInfo by remember { mutableStateOf(false) }''',
    'add latency picker state',
)
text = replace_once(
    text,
    '''    var autoStart by remember { mutableStateOf(prefs.getBoolean("autoStartVpn", false)) }\n    var blurEnabled by remember { mutableStateOf(prefs.getBoolean("enableBlur", true)) }''',
    '''    var autoStart by remember { mutableStateOf(prefs.getBoolean("autoStartVpn", false)) }\n    var blurEnabled by remember { mutableStateOf(prefs.getBoolean("enableBlur", true)) }\n    var latencyInterval by remember { mutableIntStateOf(prefs.getInt("latencyAutoRefreshSeconds", 60).takeIf { it == 0 || it == 30 || it == 60 } ?: 60) }''',
    'add latency interval state',
)
old_network_group = '''            RefGroup {\n                RefValueRow(\n                    "端口细则",'''
new_network_group = '''            RefGroup {\n                RefValueRow(\n                    "延迟自动刷新",\n                    if (latencyInterval <= 0) "关闭" else "${latencyInterval} 秒",\n                    Icons.Rounded.Speed,\n                    Color(0xFF2563EB),\n                    highlightValue = latencyInterval > 0,\n                ) { latencyPicker = true }\n                RefDivider()\n                RefValueRow(\n                    "端口细则",'''
text = replace_once(text, old_network_group, new_network_group, 'insert latency refresh setting')

before_ports = '''    if (portsInfo) {\n        RefPortsBottomSheet(onDismiss = { portsInfo = false })\n    }'''
latency_sheet = '''    if (latencyPicker) {\n        val intervals = listOf(0 to "关闭", 30 to "30 秒", 60 to "60 秒")\n        RefChoiceBottomSheet(\n            title = "延迟自动刷新间隔",\n            options = intervals.map { (seconds, label) -> label to (latencyInterval == seconds) },\n            onDismiss = { latencyPicker = false },\n            onSelect = { index ->\n                latencyInterval = intervals[index].first\n                prefs.edit().putInt("latencyAutoRefreshSeconds", latencyInterval).apply()\n                latencyPicker = false\n                onChanged()\n            },\n        )\n    }\n\n    if (portsInfo) {\n        RefPortsBottomSheet(onDismiss = { portsInfo = false })\n    }'''
text = replace_once(text, before_ports, latency_sheet, 'add latency interval bottom sheet')

# Dark Glass log sheet: make the whole terminal drawer obsidian, not a white drawer with a black rectangle.
text = replace_once(
    text,
    '''        containerColor = t.elevatedCardBackground,\n        contentColor = t.textPrimary,\n        tonalElevation = 0.dp,\n        scrimColor = Color.Black.copy(alpha = .35f),\n        dragHandle = { RefSheetDragHandle() },''',
    '''        containerColor = if (terminal) Color(0xFF0B1220) else t.elevatedCardBackground,\n        contentColor = if (terminal) Color(0xFFE2E8F0) else t.textPrimary,\n        tonalElevation = 0.dp,\n        scrimColor = Color.Black.copy(alpha = if (terminal) .48f else .35f),\n        dragHandle = {\n            Box(\n                Modifier.padding(top = 10.dp, bottom = 6.dp).size(width = 36.dp, height = 4.dp)\n                    .background(if (terminal) Color(0xFF334155) else Color(0xFFCBD5E1), CircleShape),\n            )\n        },''',
    'dark terminal bottom sheet shell',
)
text = text.replace(
    'Text(title, color = t.textPrimary, fontSize = 20.sp, lineHeight = 25.sp, fontWeight = FontWeight.Bold)',
    'Text(title, color = if (terminal) Color(0xFFF8FAFC) else t.textPrimary, fontSize = 20.sp, lineHeight = 25.sp, fontWeight = FontWeight.Bold)',
    1,
)
text = text.replace(
    'shape = RoundedCornerShape(16.dp),\n                colors = ButtonDefaults.filledTonalButtonColors(\n                    containerColor = if (terminal) Color(0xFF1E293B) else t.controlBackground,',
    'shape = CircleShape,\n                colors = ButtonDefaults.filledTonalButtonColors(\n                    containerColor = if (terminal) Color.White.copy(alpha = .08f) else t.controlBackground,',
    1,
)

# Sanity checks for ReferenceProxyActivity.
required = [
    '"辟尘"',
    '"Mihomo Core"',
    'measureSitesInternal(reportError = false)',
    'latencyAutoRefreshSeconds',
    'homeLatencyRefreshRotation',
    'private fun RefActionText(',
    'danger: Boolean = false',
    'Modifier.fillMaxWidth().height(4.dp)',
    'modifier.height(66.dp)',
    'fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace',
    'containerColor = if (terminal) Color(0xFF0B1220)',
]
for token in required:
    if token not in text:
        raise SystemExit(f'missing UI invariant: {token}')
if '"BoxProxy"' in text:
    raise SystemExit('BoxProxy home title still present')

# ---------------------------------------------------------------------------
# 7) YAML editor: full-width native editor with 16dp breathing room and fixed action dock.
# ---------------------------------------------------------------------------
# Add Dialog imports.
if 'import androidx.compose.ui.window.Dialog' not in sub:
    anchor = 'import androidx.compose.ui.unit.sp\n'
    if anchor not in sub:
        raise SystemExit('subscription imports anchor missing')
    sub = sub.replace(anchor, anchor + 'import androidx.compose.ui.window.Dialog\nimport androidx.compose.ui.window.DialogProperties\n', 1)

new_yaml = r'''    if (yamlOpen) {
        Dialog(
            onDismissRequest = { if (!yamlSaving) yamlOpen = false },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            val editorShape = RoundedCornerShape(18.dp)
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = tokens.pageBackground,
            ) {
                Column(Modifier.fillMaxSize()) {
                    Row(
                        Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp).height(48.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = { if (!yamlSaving) yamlOpen = false }, enabled = !yamlSaving) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回")
                        }
                        Column(Modifier.weight(1f)) {
                            Text("编辑当前 YAML", color = tokens.textPrimary, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
                            Text("保存后重启代理生效", color = tokens.textSecondary, fontSize = 10.sp)
                        }
                    }
                    OutlinedTextField(
                        value = yamlText,
                        onValueChange = { yamlText = it; yamlError = "" },
                        modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 16.dp, vertical = 8.dp),
                        textStyle = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp,
                            lineHeight = 20.sp,
                        ),
                        shape = editorShape,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = tokens.cardBackground,
                            unfocusedContainerColor = tokens.cardBackground,
                            focusedBorderColor = scheme.primary.copy(alpha = .34f),
                            unfocusedBorderColor = tokens.outline.copy(alpha = .55f),
                        ),
                    )
                    if (yamlError.isNotBlank()) {
                        Text(
                            yamlError,
                            color = scheme.error,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp),
                        )
                    }
                    Surface(
                        modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp)
                            .shadow(10.dp, RoundedCornerShape(26.dp), clip = false),
                        shape = RoundedCornerShape(26.dp),
                        color = if (dark) tokens.elevatedCardBackground.copy(alpha = .96f) else Color.White.copy(alpha = .94f),
                        border = BorderStroke(.7.dp, if (dark) tokens.outline.copy(alpha = .55f) else Color.White.copy(alpha = .92f)),
                        tonalElevation = 0.dp,
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(6.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            FilledTonalButton(
                                onClick = { yamlOpen = false },
                                enabled = !yamlSaving,
                                modifier = Modifier.weight(1f).height(44.dp),
                                shape = CircleShape,
                            ) { Text("取消", fontWeight = FontWeight.Bold) }
                            Button(
                                onClick = {
                                    yamlSaving = true
                                    scope.launch {
                                        runCatching { controller.saveConfigText(yamlText) }
                                            .onSuccess { yamlOpen = false; revision++; message = "YAML 已保存；重启代理后生效" }
                                            .onFailure { yamlError = it.message ?: "保存失败" }
                                        yamlSaving = false
                                    }
                                },
                                enabled = !yamlSaving,
                                modifier = Modifier.weight(1f).height(44.dp),
                                shape = CircleShape,
                            ) {
                                if (yamlSaving) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                else Text("保存", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
    }'''
sub = replace_regex(
    sub,
    r'    if \(yamlOpen\) \{\n        AlertDialog\(.*?\n        \)\n    \}',
    new_yaml,
    'replace YAML AlertDialog with full editor',
)

for token in ['DialogProperties(usePlatformDefaultWidth = false)', 'lineHeight = 20.sp', 'padding(horizontal = 16.dp, vertical = 8.dp)', 'navigationBarsPadding()', 'Text("保存", fontWeight = FontWeight.Bold)']:
    if token not in sub:
        raise SystemExit(f'missing YAML editor invariant: {token}')

UI.write_text(text, encoding='utf-8')
SUB.write_text(sub, encoding='utf-8')
print('Applied test47 native dashboard, latency lifecycle, action pills, node layout and YAML editor polish')
