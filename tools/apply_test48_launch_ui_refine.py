from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
UI = ROOT / 'android-app/app/src/main/java/io/github/xgl34222220/bichen/ReferenceProxyActivity.kt'
SUB = ROOT / 'android-app/app/src/main/java/io/github/xgl34222220/bichen/ProxySubscriptionActivity.kt'
BUILD = ROOT / 'android-app/app/build.gradle.kts'
STYLES = ROOT / 'android-app/app/src/main/res/values/styles.xml'


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
# Version
# ---------------------------------------------------------------------------
build = BUILD.read_text(encoding='utf-8')
build = replace_once(build, 'versionCode = 447', 'versionCode = 448', 'version code')
build = replace_once(build, 'versionName = "0.4.0-test.47"', 'versionName = "0.4.0-test.48"', 'version name')
BUILD.write_text(build, encoding='utf-8')

text = UI.read_text(encoding='utf-8')

# ---------------------------------------------------------------------------
# 1) Desktop cold start: render a truthful persistent snapshot immediately,
#    then reconcile Root/API/provider/CPU state after the first frame.
# ---------------------------------------------------------------------------
text = replace_once(
    text,
    '''    val prefs = remember { context.getSharedPreferences("bichen", 0) }\n    val showPanelTab = prefs.getBoolean("showPanelTab", true)''',
    '''    val prefs = remember { context.getSharedPreferences("bichen", 0) }\n    val showPanelTab = prefs.getBoolean("showPanelTab", true)\n    val startupProfile = remember { ProxyRuntimeProfile.load(prefs) }\n    val startupConfig = remember(startupProfile.core) {\n        prefs.getString("proxySelectedConfig.${startupProfile.core.id}", "").orEmpty().ifBlank { "尚未选择配置" }\n    }''',
    'startup snapshot inputs',
)
text = replace_once(
    text,
    '''    var state by remember { mutableStateOf(ProxyComposeState(running = prefs.getBoolean("proxyRootWanted", false))) }''',
    '''    var state by remember {\n        mutableStateOf(\n            ProxyComposeState(\n                running = prefs.getBoolean("proxyUiLastRunning", prefs.getBoolean("proxyRootWanted", false)),\n                core = startupProfile.core.label,\n                mode = startupProfile.mode.label,\n                ipv6 = startupProfile.ipv6.id,\n                autoOverwrite = startupProfile.autoOverwrite,\n                config = startupConfig,\n            ),\n        )\n    }''',
    'startup snapshot state',
)
text = replace_once(
    text,
    '''            state = next\n            message = next.message''',
    '''            state = next\n            prefs.edit()\n                .putBoolean("proxyUiLastRunning", next.running)\n                .putString("proxyUiLastCore", next.core)\n                .putString("proxyUiLastMode", next.mode)\n                .putString("proxyUiLastConfig", next.config)\n                .apply()\n            message = next.message''',
    'persist runtime snapshot',
)
text = replace_once(
    text,
    '''    LaunchedEffect(Unit) {\n        // Runtime state is more important than decorative icon downloads. Reopening the app\n        // must not show a false "已停止" card while network icon assets are being fetched.\n        refresh()\n        launch { runCatching { repo.ensureIcons() } }\n        while (true) {\n            delay(2200)\n            if (operation.isBlank()) refresh()\n        }\n    }''',
    '''    LaunchedEffect(Unit) {\n        // First frame must never wait for Root shell + controller API + provider/CPU probes.\n        // Paint the persisted snapshot first, then reconcile the live state asynchronously.\n        launch {\n            delay(34)\n            refresh()\n        }\n        launch {\n            delay(420)\n            runCatching { repo.ensureIcons() }\n        }\n        while (true) {\n            delay(2200)\n            if (operation.isBlank()) refresh()\n        }\n    }''',
    'defer live cold-start refresh',
)
text = replace_once(
    text,
    '''        measureSitesInternal(reportError = false)\n        while (true) {''',
    '''        // Avoid competing with the first visible frame and first live state reconciliation.\n        delay(520)\n        if (state.running) measureSitesInternal(reportError = false)\n        while (true) {''',
    'defer first site latency pass',
)

# Keep every docked screen comfortably clear of the 64dp dock + 24dp air gap + nav inset.
text = text.replace('calculateBottomPadding() + 132.dp', 'calculateBottomPadding() + 148.dp')

# ---------------------------------------------------------------------------
# 2) Home: aligned industrial identity, denser hero, instrument typography.
# ---------------------------------------------------------------------------
old_header = '''                Text(\n                    "辟尘",\n                    color = if (scheme.background.luminance() < .5f) t.textPrimary else Color(0xFF0F172A),\n                    fontSize = 24.sp,\n                    lineHeight = 30.sp,\n                    fontWeight = FontWeight.ExtraBold,\n                    letterSpacing = (-0.45).sp,\n                )\n                Spacer(Modifier.width(8.dp))\n                Surface(\n                    shape = CircleShape,\n                    color = if (scheme.background.luminance() < .5f) Color.White.copy(alpha = .07f) else Color(0xFFE2E8F0).copy(alpha = .62f),\n                    tonalElevation = 0.dp,\n                ) {\n                    Text(\n                        "Mihomo Core",\n                        Modifier.padding(horizontal = 8.dp, vertical = 3.dp),\n                        color = Color(0xFF94A3B8),\n                        fontSize = 9.sp,\n                        lineHeight = 12.sp,\n                        fontWeight = FontWeight.SemiBold,\n                        letterSpacing = .2.sp,\n                    )\n                }'''
new_header = '''                Text(\n                    "辟尘",\n                    color = if (scheme.background.luminance() < .5f) t.textPrimary else Color(0xFF0F172A),\n                    fontSize = 25.sp,\n                    lineHeight = 30.sp,\n                    fontWeight = FontWeight.Black,\n                    letterSpacing = (-0.70).sp,\n                )\n                Spacer(Modifier.width(10.dp))\n                Surface(\n                    shape = CircleShape,\n                    color = if (scheme.background.luminance() < .5f) Color(0xFF1D4ED8).copy(alpha = .16f) else Color(0xFFEAF2FF).copy(alpha = .90f),\n                    border = BorderStroke(.7.dp, if (scheme.background.luminance() < .5f) Color(0xFF60A5FA).copy(alpha = .18f) else Color(0xFFBFDBFE).copy(alpha = .72f)),\n                    shadowElevation = 1.dp,\n                    tonalElevation = 0.dp,\n                ) {\n                    Text(\n                        "Mihomo Core",\n                        Modifier.padding(horizontal = 8.dp, vertical = 3.dp),\n                        color = if (scheme.background.luminance() < .5f) Color(0xFF93C5FD) else Color(0xFF2563EB),\n                        fontSize = 10.sp,\n                        lineHeight = 13.sp,\n                        fontWeight = FontWeight.Bold,\n                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,\n                        letterSpacing = .15.sp,\n                    )\n                }'''
text = replace_once(text, old_header, new_header, 'home identity capsule')

old_hero_status = '''                            Row(verticalAlignment = Alignment.CenterVertically) {\n                                Box(Modifier.size(9.dp).background(if (state.running) scheme.primary else t.danger, CircleShape))\n                                Spacer(Modifier.width(9.dp))\n                                Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {\n                                    Text(\n                                        if (state.running) "运行中" else "已停止",\n                                        color = t.textPrimary,\n                                        fontSize = 20.sp,\n                                        lineHeight = 24.sp,\n                                        fontWeight = FontWeight.ExtraBold,\n                                        maxLines = 1,\n                                    )\n                                    Text(\n                                        if (state.running) refDuration(runtime.elapsedSeconds) else "等待启动",\n                                        color = t.textMuted,\n                                        fontSize = 11.sp,\n                                        lineHeight = 15.sp,\n                                        fontWeight = FontWeight.Medium,\n                                        maxLines = 1,\n                                    )\n                                }\n                            }'''
new_hero_status = '''                            Row(verticalAlignment = Alignment.CenterVertically) {\n                                val statusPulse = androidx.compose.animation.core.rememberInfiniteTransition(label = "heroStatusPulse")\n                                val statusAlpha by statusPulse.animateFloat(\n                                    initialValue = .52f,\n                                    targetValue = 1f,\n                                    animationSpec = androidx.compose.animation.core.infiniteRepeatable(\n                                        animation = androidx.compose.animation.core.tween(820),\n                                        repeatMode = androidx.compose.animation.core.RepeatMode.Reverse,\n                                    ),\n                                    label = "heroStatusAlpha",\n                                )\n                                Box(\n                                    Modifier.size(9.dp)\n                                        .graphicsLayer { alpha = if (state.running) statusAlpha else 1f }\n                                        .background(if (state.running) Color(0xFF002FA7) else t.danger, CircleShape),\n                                )\n                                Spacer(Modifier.width(9.dp))\n                                Text(\n                                    if (state.running) "运行中" else "已停止",\n                                    color = t.textPrimary,\n                                    fontSize = 20.sp,\n                                    lineHeight = 24.sp,\n                                    fontWeight = FontWeight.Black,\n                                    maxLines = 1,\n                                )\n                                Spacer(Modifier.width(8.dp))\n                                Surface(\n                                    shape = CircleShape,\n                                    color = if (scheme.background.luminance() < .5f) Color.White.copy(alpha = .08f) else Color.White.copy(alpha = .82f),\n                                    border = BorderStroke(.6.dp, if (scheme.background.luminance() < .5f) Color.White.copy(alpha = .08f) else Color(0xFFE2E8F0).copy(alpha = .78f)),\n                                    tonalElevation = 0.dp,\n                                ) {\n                                    Text(\n                                        if (state.running) refDuration(runtime.elapsedSeconds) else "等待启动",\n                                        Modifier.padding(horizontal = 8.dp, vertical = 3.dp),\n                                        color = t.textMuted,\n                                        fontSize = 11.sp,\n                                        lineHeight = 14.sp,\n                                        fontWeight = FontWeight.Medium,\n                                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,\n                                        maxLines = 1,\n                                    )\n                                }\n                            }'''
text = replace_once(text, old_hero_status, new_hero_status, 'hero status row')
text = replace_once(text, 'if (state.running) 12.dp else 0.dp,', 'if (state.running) 16.dp else 0.dp,', 'hero badge glow')
text = replace_once(text, 'ambientColor = scheme.primary.copy(alpha = .26f),\n                                    spotColor = scheme.primary.copy(alpha = .38f),', 'ambientColor = Color(0xFF002FA7).copy(alpha = .28f),\n                                    spotColor = Color(0xFF002FA7).copy(alpha = .42f),', 'hero badge shadow color')

new_latency_column = r'''@Composable
private fun RefLatencyColumn(label: String, value: Long?, testing: Boolean, modifier: Modifier) {
    val valueColor = when {
        value == null -> Color(0xFF94A3B8)
        value <= 0L -> Color(0xFFF43F5E)
        value < 100L -> Color(0xFF10B981)
        value <= 300L -> Color(0xFFF59E0B)
        else -> Color(0xFFF43F5E)
    }
    val alpha by animateFloatAsState(
        targetValue = if (testing) .58f else 1f,
        animationSpec = androidx.compose.animation.core.tween(180),
        label = "homeLatencyTestingAlpha",
    )
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, color = Color(0xFF64748B), fontSize = 11.sp, lineHeight = 15.sp, maxLines = 1)
        Row(
            Modifier.graphicsLayer { this.alpha = alpha },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                when {
                    value == null -> "--"
                    value <= 0L -> "超时"
                    else -> value.toString()
                },
                color = valueColor,
                fontSize = if (value != null && value > 0L) 18.sp else 14.sp,
                lineHeight = 21.sp,
                fontWeight = FontWeight.Black,
                maxLines = 1,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                modifier = Modifier.alignByBaseline(),
            )
            if (value != null && value > 0L) {
                Text(
                    "ms",
                    color = Color(0xFF94A3B8),
                    fontSize = 10.sp,
                    lineHeight = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    modifier = Modifier.alignByBaseline(),
                )
            }
        }
    }
}'''
text = replace_regex(text, r'@Composable\nprivate fun RefLatencyColumn\(.*?\n\}\n\n@Composable\nprivate fun RefNetworkIdentityCard', new_latency_column + '\n\n@Composable\nprivate fun RefNetworkIdentityCard', 'latency numeric/unit split')

text = replace_once(
    text,
    '                Icon(Icons.Rounded.SwapHoriz, "切换 LAN/WAN", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(15.dp))',
    '''                Box(\n                    Modifier.size(24.dp)\n                        .background(if (MaterialTheme.colorScheme.background.luminance() < .5f) Color.White.copy(alpha = .07f) else Color(0xFFF1F5F9), CircleShape)\n                        .border(.6.dp, if (MaterialTheme.colorScheme.background.luminance() < .5f) Color.White.copy(alpha = .08f) else Color.White.copy(alpha = .90f), CircleShape),\n                    contentAlignment = Alignment.Center,\n                ) {\n                    Icon(Icons.Rounded.SwapHoriz, "切换 LAN/WAN", tint = Color(0xFF2563EB), modifier = Modifier.size(14.dp))\n                }''',
    'LAN glass swap',
)

new_resource = r'''@Composable
private fun RefResourceCard(memory: Long, cpuPercent: Float, modifier: Modifier) {
    val t = LocalBichenTokens.current
    val scheme = MaterialTheme.colorScheme
    val valueColor = if (scheme.background.luminance() < .5f) t.textPrimary else Color(0xFF0F172A)
    val progress = (cpuPercent / 100f).coerceIn(0f, 1f)
    Surface(modifier = modifier.height(100.dp), shape = RoundedCornerShape(20.dp), color = t.cardBackground, shadowElevation = 1.dp) {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("资源占用", color = Color(0xFF64748B), fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Canvas(Modifier.size(22.dp)) {
                    drawCircle(Color(0xFFE2E8F0).copy(alpha = .64f), style = Stroke(width = 2.dp.toPx()))
                    drawArc(
                        color = Color(0xFF002FA7).copy(alpha = .68f),
                        startAngle = -90f,
                        sweepAngle = 360f * progress,
                        useCenter = false,
                        style = Stroke(width = 2.2.dp.toPx()),
                    )
                    drawCircle(Color(0xFF2563EB).copy(alpha = if (progress > .02f) .22f else .10f), radius = 2.4.dp.toPx())
                }
            }
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
text = replace_regex(text, r'@Composable\nprivate fun RefResourceCard\(.*?\n\}\n\nprivate fun countryEmoji', new_resource + '\n\nprivate fun countryEmoji', 'resource micro gauge')

# ---------------------------------------------------------------------------
# 3) Dashboard: physically separate expansion and ping hit targets.
# ---------------------------------------------------------------------------
new_group_card = r'''@Composable
private fun RefGroupCard(group: ProxyGroupUi, selected: String, expanded: Boolean, delay: Long?, testing: Boolean, modifier: Modifier, onClick: () -> Unit, onDelay: () -> Unit) {
    val t = LocalBichenTokens.current
    val scheme = MaterialTheme.colorScheme
    val dark = scheme.background.luminance() < .5f
    val source = remember(group.name) { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) .97f else 1f, spring(dampingRatio = .74f, stiffness = 580f), label = "group${group.name}")
    val shape = RoundedCornerShape(18.dp)
    val nodeName = selected.ifBlank { "未选择" }
    val nodeFlag = refNodeFlag(nodeName)
    val premiumBrush = if (dark) Brush.verticalGradient(listOf(t.elevatedCardBackground, t.cardBackground))
    else Brush.verticalGradient(listOf(Color.White, Color(0xFFFAFBFD)))
    Column(
        modifier
            .height(84.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale; alpha = if (pressed) .95f else 1f }
            .shadow(if (expanded) 5.dp else 3.dp, shape, clip = false)
            .background(premiumBrush, shape)
            .border(
                if (expanded) 1.4.dp else .8.dp,
                if (expanded) Color(0xFF002FA7).copy(alpha = .64f) else Color.White.copy(alpha = if (dark) .10f else .92f),
                shape,
            )
            .clip(shape)
            .padding(horizontal = 11.dp, vertical = 9.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(
            Modifier.fillMaxWidth().height(40.dp).clip(RoundedCornerShape(10.dp))
                .clickable(interactionSource = source, indication = null, onClick = onClick),
            verticalAlignment = Alignment.Top,
        ) {
            Column(Modifier.weight(1f).padding(end = 5.dp), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(group.name, color = t.textPrimary, fontSize = 14.sp, lineHeight = 17.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${refGroupTypeCompact(group.type).uppercase()} 0/${group.nodes.size}", color = Color(0xFF94A3B8), fontSize = 10.sp, lineHeight = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1)
            }
            RefGroupCornerVisual(group, Modifier.size(27.dp))
            Spacer(Modifier.width(3.dp))
            Icon(
                if (expanded) Icons.Rounded.KeyboardArrowUp else Icons.Rounded.KeyboardArrowDown,
                if (expanded) "收起" else "展开",
                tint = if (expanded) Color(0xFF002FA7) else Color(0xFF94A3B8),
                modifier = Modifier.size(15.dp),
            )
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.weight(1f).height(24.dp).clip(RoundedCornerShape(8.dp))
                    .clickable(interactionSource = source, indication = null, onClick = onClick)
                    .padding(end = 5.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                Text(
                    if (nodeFlag.isBlank()) nodeName else "$nodeFlag $nodeName",
                    color = if (dark) t.textSecondary else Color(0xFF475569),
                    fontSize = 11.sp,
                    lineHeight = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // This button is a sibling, not a child of the accordion click target.
            // Ping can therefore never toggle expansion.
            RefDelayBadge(delay, testing, onDelay)
        }
    }
}'''
text = replace_regex(text, r'@Composable\nprivate fun RefGroupCard\(.*?\n\}\n\n@Composable\nprivate fun RefInlineGroupExpansion', new_group_card + '\n\n@Composable\nprivate fun RefInlineGroupExpansion', 'decouple group ping and accordion')

# Glass/recessed accordion tray.
text = replace_once(
    text,
    '''    val trayBrush = if (dark) {\n        Brush.verticalGradient(listOf(Color.White.copy(alpha = .065f), t.elevatedCardBackground, t.cardBackground))\n    } else {\n        Brush.verticalGradient(listOf(Color(0xFFE2E8F0).copy(alpha = .46f), Color(0xFFF8FAFC).copy(alpha = .94f), Color.White.copy(alpha = .86f)))\n    }''',
    '''    val trayBrush = if (dark) {\n        Brush.verticalGradient(listOf(Color.White.copy(alpha = .075f), t.elevatedCardBackground.copy(alpha = .94f), t.cardBackground.copy(alpha = .90f)))\n    } else {\n        Brush.verticalGradient(listOf(Color(0xFFE2E8F0).copy(alpha = .40f), Color(0xFFF1F5F9).copy(alpha = .74f), Color.White.copy(alpha = .68f)))\n    }''',
    'accordion glass trough brush',
)
text = replace_once(text, '        shadowElevation = 1.dp,\n    ) {\n        Column(\n            Modifier.fillMaxWidth().background(trayBrush, shape).padding(12.dp),', '        shadowElevation = 2.dp,\n    ) {\n        Column(\n            Modifier.fillMaxWidth().background(trayBrush, shape).padding(12.dp),', 'accordion trough depth')

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
        delay((index * 18L).coerceAtMost(220L))
        revealed = true
    }
    val backgroundBrush = when {
        dark && active -> Brush.verticalGradient(listOf(Color(0xFF172554), Color(0xFF111C38)))
        dark -> Brush.verticalGradient(listOf(t.elevatedCardBackground, t.cardBackground))
        active -> Brush.verticalGradient(listOf(Color.White, Color(0xFFF3F7FF)))
        else -> Brush.verticalGradient(listOf(Color.White.copy(alpha = .94f), Color(0xFFFAFCFF).copy(alpha = .90f)))
    }
    Box(modifier.height(64.dp)) {
        androidx.compose.animation.AnimatedVisibility(
            visible = revealed,
            modifier = Modifier.fillMaxSize(),
            enter = androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(170)) +
                androidx.compose.animation.slideInVertically(androidx.compose.animation.core.tween(210)) { it / 3 },
        ) {
            Box(
                Modifier.fillMaxSize()
                    .graphicsLayer { scaleX = scale; scaleY = scale; alpha = if (pressed) .88f else 1f }
                    .shadow(if (active) 4.dp else 2.dp, shape, clip = false)
                    .background(backgroundBrush, shape)
                    .border(
                        if (active) 1.35.dp else .7.dp,
                        if (active) Color(0xFF002FA7) else if (dark) Color.White.copy(alpha = .08f) else Color.White.copy(alpha = .90f),
                        shape,
                    )
                    .clip(shape)
                    .clickable(interactionSource = source, indication = null, onClick = onSelect),
            ) {
                Column(
                    Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        val flag = refNodeFlag(node.name)
                        if (flag.isNotBlank()) {
                            Text(flag, fontSize = 14.sp)
                            Spacer(Modifier.width(4.dp))
                        }
                        Text(
                            node.name,
                            color = if (active && !dark) Color(0xFF1E3A8A) else t.textPrimary,
                            fontSize = 12.sp,
                            lineHeight = 15.sp,
                            fontWeight = if (active) FontWeight.Bold else FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            shape = RoundedCornerShape(7.dp),
                            color = if (active) Color(0xFFEFF6FF).copy(alpha = if (dark) .12f else .92f) else if (dark) Color.White.copy(alpha = .055f) else Color(0xFFF1F5F9),
                            tonalElevation = 0.dp,
                        ) {
                            Text(
                                refNodeProtocol(node),
                                Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                color = if (active) Color(0xFF2563EB) else Color(0xFF64748B),
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
                androidx.compose.animation.AnimatedVisibility(
                    visible = active,
                    modifier = Modifier.align(Alignment.TopEnd).offset(x = 3.dp, y = (-3).dp),
                    enter = androidx.compose.animation.scaleIn(initialScale = .15f, animationSpec = spring(dampingRatio = .56f, stiffness = 520f)) + androidx.compose.animation.fadeIn(),
                    exit = androidx.compose.animation.scaleOut(targetScale = .45f) + androidx.compose.animation.fadeOut(),
                ) {
                    Box(
                        Modifier.size(17.dp).shadow(4.dp, CircleShape, clip = false).background(Color(0xFF002FA7), CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Rounded.Check, "已选择", tint = Color.White, modifier = Modifier.size(11.dp))
                    }
                }
            }
        }
    }
}'''
text = replace_regex(text, r'@Composable\nprivate fun RefInlineNodeCard\(.*?\n\}\n\n@Composable\nprivate fun RefGroupCornerVisual', new_inline_node + '\n\n@Composable\nprivate fun RefGroupCornerVisual', '64dp overlay-check inline nodes')
# Full-screen node cards use the same compact height.
text = text.replace('modifier.height(68.dp)', 'modifier.height(64.dp)', 1)

new_delay_badge = r'''@Composable
private fun RefDelayBadge(value: Long?, testing: Boolean, onClick: (() -> Unit)?) {
    val text = refDelay(value)
    val (background, textColor) = when {
        value == null -> Color(0xFFF1F5F9) to Color(0xFF64748B)
        value <= 0L -> Color(0xFFFFF1F2) to Color(0xFFE11D48)
        value < 100L -> Color(0xFFECFDF5) to Color(0xFF059669)
        value <= 300L -> Color(0xFFFFFBEB) to Color(0xFFD97706)
        else -> Color(0xFFFFF1F2) to Color(0xFFE11D48)
    }
    val source = remember(onClick) { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val pulseTransition = androidx.compose.animation.core.rememberInfiniteTransition(label = "latencyPulse")
    val pulse by pulseTransition.animateFloat(
        initialValue = .62f,
        targetValue = 1f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
            animation = androidx.compose.animation.core.tween(620),
            repeatMode = androidx.compose.animation.core.RepeatMode.Reverse,
        ),
        label = "latencyPulseAlpha",
    )
    val pressScale by animateFloatAsState(if (pressed) .90f else 1f, spring(dampingRatio = .68f, stiffness = 680f), label = "latencyPress")
    val shape = CircleShape
    Row(
        Modifier.width(if (onClick != null) 68.dp else 62.dp).height(22.dp)
            .graphicsLayer {
                scaleX = pressScale
                scaleY = pressScale
                alpha = if (pressed) .84f else if (testing) .76f + .24f * pulse else 1f
            }
            .shadow(if (onClick != null) 2.dp else 0.dp, shape, clip = false, ambientColor = textColor.copy(alpha = .10f), spotColor = textColor.copy(alpha = .12f))
            .background(if (testing) background.copy(alpha = .78f) else background, shape)
            .border(.7.dp, if (testing) textColor.copy(alpha = .22f + .22f * pulse) else textColor.copy(alpha = if (onClick != null) .10f else .04f), shape)
            .then(if (onClick != null) Modifier.clickable(enabled = !testing, interactionSource = source, indication = null, onClick = onClick) else Modifier)
            .padding(horizontal = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Text(
            text,
            color = textColor,
            fontSize = 10.sp,
            lineHeight = 13.sp,
            fontWeight = FontWeight.ExtraBold,
            maxLines = 1,
            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
        )
        if (onClick != null) {
            Spacer(Modifier.width(3.dp))
            if (testing) {
                CircularProgressIndicator(
                    modifier = Modifier.size(9.dp),
                    strokeWidth = 1.25.dp,
                    color = textColor,
                    trackColor = textColor.copy(alpha = .14f),
                )
            } else {
                Icon(Icons.Rounded.Bolt, "单独测速", tint = textColor.copy(alpha = .86f), modifier = Modifier.size(10.dp))
            }
        }
    }
}'''
text = replace_regex(text, r'@Composable\nprivate fun RefDelayBadge\(.*?\n\}\n\n@Composable\nprivate fun RefTrafficOverview', new_delay_badge + '\n\n@Composable\nprivate fun RefTrafficOverview', 'raised independent delay badge')

# Traffic overview: never leave a dead blank chart when the history has not accumulated yet.
text = replace_once(
    text,
    '''                        drawPath(smooth(upload, false), color = t.success, style = Stroke(width = 2.25.dp.toPx()))\n                        drawPath(smooth(download, false), color = scheme.primary, style = Stroke(width = 2.25.dp.toPx()))\n                    }''',
    '''                        drawPath(smooth(upload, false), color = t.success, style = Stroke(width = 2.25.dp.toPx()))\n                        drawPath(smooth(download, false), color = scheme.primary, style = Stroke(width = 2.25.dp.toPx()))\n                    } else {\n                        val y = size.height - 5.dp.toPx()\n                        drawLine(\n                            color = scheme.primary.copy(alpha = .10f),\n                            start = Offset(0f, y),\n                            end = Offset(size.width, y),\n                            strokeWidth = 7.dp.toPx(),\n                        )\n                        drawLine(\n                            brush = Brush.horizontalGradient(listOf(t.success.copy(alpha = .34f), scheme.primary.copy(alpha = .48f))),\n                            start = Offset(0f, y),\n                            end = Offset(size.width, y),\n                            strokeWidth = 2.dp.toPx(),\n                        )\n                    }''',
    'traffic empty baseline',
)

# ---------------------------------------------------------------------------
# 4) Rules: grouped inset cards instead of dozens of detached strips.
# ---------------------------------------------------------------------------
text = replace_once(
    text,
    '''                RefPanelTab.Rules -> items(rules, key = { it.index }) { RefRuleRow(it) }''',
    '''                RefPanelTab.Rules -> itemsIndexed(rules.chunked(12), key = { index, _ -> "rule-group-$index" }) { _, batch ->\n                    RefRuleGroupCard(batch)\n                }''',
    'group rule list',
)

new_rule_group = r'''@Composable
private fun RefRuleGroupCard(items: List<ProxyRuleUi>) {
    val t = LocalBichenTokens.current
    val dark = MaterialTheme.colorScheme.background.luminance() < .5f
    val shape = RoundedCornerShape(22.dp)
    Surface(
        shape = shape,
        color = t.cardBackground,
        border = BorderStroke(.7.dp, if (dark) t.outline.copy(alpha = .36f) else Color.White.copy(alpha = .92f)),
        shadowElevation = if (dark) 0.dp else 3.dp,
    ) {
        Column(Modifier.fillMaxWidth()) {
            items.forEachIndexed { index, item ->
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            item.payload.ifBlank { item.type },
                            color = t.textPrimary,
                            fontSize = 12.sp,
                            lineHeight = 15.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            item.type,
                            color = Color(0xFF94A3B8),
                            fontSize = 10.sp,
                            lineHeight = 13.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    val reject = item.proxy.equals("REJECT", true) || item.proxy.startsWith("REJECT-", true)
                    val direct = item.proxy.equals("DIRECT", true)
                    val badgeBg = when {
                        reject -> Color(0xFFFFF1F2)
                        direct -> Color(0xFFEFF6FF)
                        else -> Color(0xFFF1F5F9)
                    }
                    val badgeText = when {
                        reject -> Color(0xFFE11D48)
                        direct -> Color(0xFF2563EB)
                        else -> Color(0xFF64748B)
                    }
                    Surface(shape = RoundedCornerShape(6.dp), color = badgeBg, tonalElevation = 0.dp) {
                        Text(
                            item.proxy,
                            Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            color = badgeText,
                            fontSize = 11.sp,
                            lineHeight = 13.sp,
                            fontWeight = FontWeight.ExtraBold,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            maxLines = 1,
                        )
                    }
                }
                if (index != items.lastIndex) {
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 14.dp),
                        thickness = 1.dp,
                        color = if (dark) t.outline.copy(alpha = .30f) else Color(0xFFF1F5F9),
                    )
                }
            }
        }
    }
}'''
text = replace_regex(text, r'@Composable\nprivate fun RefRuleRow\(.*?\n\}\n\n@Composable\nprivate fun RefRuleSetRow', new_rule_group + '\n\n@Composable\nprivate fun RefRuleSetRow', 'grouped rules card')

# ---------------------------------------------------------------------------
# 5) Tools/settings trailing information: one neutral alignment language.
# ---------------------------------------------------------------------------
text = replace_once(
    text,
    '''                Text(trailingText, color = trailingColor, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 92.dp))\n                Spacer(Modifier.width(3.dp))\n                Icon(Icons.Rounded.ChevronRight, null, tint = trailingColor.copy(alpha = .78f), modifier = Modifier.size(15.dp))''',
    '''                Text(trailingText, color = Color(0xFF94A3B8), fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 110.dp))\n                Spacer(Modifier.width(4.dp))\n                Icon(Icons.Rounded.ChevronRight, null, tint = Color(0xFFCBD5E1), modifier = Modifier.size(15.dp))''',
    'tool trailing alignment',
)
text = replace_once(
    text,
    '''                color = if (highlightValue) Color(0xFF2563EB) else Color(0xFF64748B),\n                fontSize = 11.sp,\n                fontWeight = if (highlightValue) FontWeight.Bold else FontWeight.Medium,''',
    '''                color = Color(0xFF94A3B8),\n                fontSize = 13.sp,\n                fontWeight = FontWeight.Medium,''',
    'settings trailing text style',
)
text = replace_once(
    text,
    '''            Icon(Icons.Rounded.ChevronRight, null, tint = if (highlightValue) Color(0xFF2563EB).copy(alpha = .78f) else Color(0xFFCBD5E1), modifier = Modifier.size(16.dp))''',
    '''            Icon(Icons.Rounded.ChevronRight, null, tint = Color(0xFFCBD5E1), modifier = Modifier.size(16.dp))''',
    'settings chevron style',
)

# Hard audit of the large Compose screen before writing it.
required_ui = [
    'startupProfile = remember { ProxyRuntimeProfile.load(prefs) }',
    'delay(34)',
    'heroStatusPulse',
    'Color(0xFF002FA7)',
    'fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace',
    'private fun RefRuleGroupCard(items: List<ProxyRuleUi>)',
    'rules.chunked(12)',
    'brush = Brush.horizontalGradient(listOf(t.success.copy(alpha = .34f)',
    'modifier.height(64.dp)',
    'Icons.Rounded.Bolt, "单独测速"',
    'calculateBottomPadding() + 148.dp',
]
for token in required_ui:
    if token not in text:
        raise SystemExit(f'missing test48 UI invariant: {token}')
UI.write_text(text, encoding='utf-8')

# ---------------------------------------------------------------------------
# 6) YAML advanced editor: fixed frosted control dock + Klein-blue save anchor.
# ---------------------------------------------------------------------------
sub = SUB.read_text(encoding='utf-8')
sub = replace_once(
    sub,
    '''                        modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp)\n                            .shadow(10.dp, RoundedCornerShape(26.dp), clip = false),\n                        shape = RoundedCornerShape(26.dp),\n                        color = if (dark) tokens.elevatedCardBackground.copy(alpha = .96f) else Color.White.copy(alpha = .94f),\n                        border = BorderStroke(.7.dp, if (dark) tokens.outline.copy(alpha = .55f) else Color.White.copy(alpha = .92f)),''',
    '''                        modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp)\n                            .shadow(18.dp, RoundedCornerShape(28.dp), clip = false, ambientColor = Color(0xFF0F172A).copy(alpha = .10f), spotColor = Color(0xFF0F172A).copy(alpha = .14f)),\n                        shape = RoundedCornerShape(28.dp),\n                        color = if (dark) tokens.elevatedCardBackground.copy(alpha = .88f) else Color.White.copy(alpha = .84f),\n                        border = BorderStroke(.8.dp, if (dark) Color.White.copy(alpha = .10f) else Color.White.copy(alpha = .96f)),''',
    'yaml frosted dock shell',
)
sub = replace_once(
    sub,
    '''                            FilledTonalButton(\n                                onClick = { yamlOpen = false },\n                                enabled = !yamlSaving,\n                                modifier = Modifier.weight(1f).height(44.dp),\n                                shape = CircleShape,\n                            ) { Text("取消", fontWeight = FontWeight.Bold) }''',
    '''                            FilledTonalButton(\n                                onClick = { yamlOpen = false },\n                                enabled = !yamlSaving,\n                                modifier = Modifier.weight(1f).height(44.dp),\n                                shape = CircleShape,\n                                colors = ButtonDefaults.filledTonalButtonColors(\n                                    containerColor = if (dark) Color.White.copy(alpha = .08f) else Color(0xFFF1F5F9),\n                                    contentColor = if (dark) Color(0xFFE2E8F0) else Color(0xFF64748B),\n                                ),\n                            ) { Text("取消", fontWeight = FontWeight.Bold) }''',
    'yaml cancel pill',
)
sub = replace_once(
    sub,
    '''                                modifier = Modifier.weight(1f).height(44.dp),\n                                shape = CircleShape,\n                            ) {\n                                if (yamlSaving) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)\n                                else Text("保存", fontWeight = FontWeight.Bold)''',
    '''                                modifier = Modifier.weight(1f).height(44.dp)\n                                    .shadow(9.dp, CircleShape, clip = false, ambientColor = Color(0xFF002FA7).copy(alpha = .22f), spotColor = Color(0xFF002FA7).copy(alpha = .30f)),\n                                shape = CircleShape,\n                                colors = ButtonDefaults.buttonColors(\n                                    containerColor = Color(0xFF002FA7),\n                                    contentColor = Color.White,\n                                    disabledContainerColor = Color(0xFF002FA7).copy(alpha = .46f),\n                                ),\n                            ) {\n                                if (yamlSaving) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White)\n                                else Text("保存", fontWeight = FontWeight.Bold)''',
    'yaml Klein-blue save pill',
)
for token in ['shadow(18.dp, RoundedCornerShape(28.dp)', 'containerColor = Color(0xFF002FA7)', 'containerColor = if (dark) Color.White.copy(alpha = .08f) else Color(0xFFF1F5F9)']:
    if token not in sub:
        raise SystemExit(f'missing YAML invariant: {token}')
SUB.write_text(sub, encoding='utf-8')

# Starting-window canvas matches the actual light shell so desktop launches do not flash
# an old warm-grey preview before Compose attaches.
styles = STYLES.read_text(encoding='utf-8')
styles = styles.replace('<item name="android:statusBarColor">#F4F5F2</item>', '<item name="android:statusBarColor">#F1F5F9</item>', 1)
styles = styles.replace('<item name="android:navigationBarColor">#F4F5F2</item>', '<item name="android:navigationBarColor">#F1F5F9</item>', 1)
styles = styles.replace('<item name="android:windowBackground">#F4F5F2</item>', '<item name="android:windowBackground">#F1F5F9</item>', 1)
if '#F1F5F9' not in styles:
    raise SystemExit('starting window color patch failed')
STYLES.write_text(styles, encoding='utf-8')

print('test.48 launch + liquid-glass UI refinement applied')
