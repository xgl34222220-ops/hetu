from pathlib import Path
import re

ROOT = Path('android-app/app/src/main/java/io/github/xgl34222220/hetu')


def require_replace(text: str, old: str, new: str, label: str) -> str:
    if old not in text:
        raise RuntimeError(f'Missing {label}: {old[:120]!r}')
    return text.replace(old, new, 1)


def replace_region(text: str, start_marker: str, end_marker: str, replacement: str, label: str) -> str:
    start = text.find(start_marker)
    if start < 0:
        raise RuntimeError(f'Missing {label} start')
    end = text.find(end_marker, start + len(start_marker))
    if end < 0:
        raise RuntimeError(f'Missing {label} end')
    return text[:start] + replacement.rstrip() + '\n\n' + text[end:]


# Version
build_path = Path('android-app/app/build.gradle.kts')
build = build_path.read_text()
build = require_replace(build, 'versionCode = 450', 'versionCode = 451', 'versionCode')
build = require_replace(build, 'versionName = "0.4.0-test.50"', 'versionName = "0.4.0-test.51"', 'versionName')
build_path.write_text(build)

# Main proxy UI
ref_path = ROOT / 'ReferenceProxyActivity.kt'
ref = ref_path.read_text()
ref = require_replace(
    ref,
    'import androidx.compose.animation.core.spring\n',
    'import androidx.compose.animation.core.spring\nimport androidx.compose.animation.togetherWith\n',
    'togetherWith import',
)

# Hero duration pill: stronger contrast / baseline alignment.
ref = require_replace(
    ref,
    '''                                        Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                        color = t.textMuted,
                                        fontSize = 11.sp,
                                        lineHeight = 14.sp,
                                        fontWeight = FontWeight.Medium,''',
    '''                                        Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                                        color = if (scheme.background.luminance() < .5f) Color(0xFFCBD5E1) else Color(0xFF64748B),
                                        fontSize = 11.sp,
                                        lineHeight = 14.sp,
                                        fontWeight = FontWeight.SemiBold,''',
    'hero duration pill',
)

network_card = r'''@Composable
private fun RefNetworkIdentityCard(runtime: ProxyRuntimeSnapshot, connections: Int, modifier: Modifier) {
    val t = LocalHetuTokens.current
    var lanMode by rememberSaveable { mutableStateOf(true) }
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val view = LocalView.current
    val scale by animateFloatAsState(if (pressed) .96f else 1f, spring(dampingRatio = .74f, stiffness = 560f), label = "networkCardPress")
    val shape = RoundedCornerShape(20.dp)
    val valueColor = if (MaterialTheme.colorScheme.background.luminance() < .5f) t.textPrimary else Color(0xFF0F172A)
    Surface(
        modifier = modifier
            .height(106.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale; alpha = if (pressed) .92f else 1f }
            .clip(shape)
            .clickable(interactionSource = source, indication = null) {
                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                lanMode = !lanMode
            },
        shape = shape,
        color = t.cardBackground,
        shadowElevation = 1.dp,
    ) {
        Column(
            Modifier.fillMaxSize().padding(14.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(if (lanMode) "LAN" else "WAN", color = Color(0xFF64748B), fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Box(
                    Modifier.size(24.dp)
                        .background(if (MaterialTheme.colorScheme.background.luminance() < .5f) Color.White.copy(alpha = .07f) else Color(0xFFF1F5F9), CircleShape)
                        .border(.6.dp, if (MaterialTheme.colorScheme.background.luminance() < .5f) Color.White.copy(alpha = .08f) else Color.White.copy(alpha = .90f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Rounded.SwapHoriz, "切换 LAN/WAN", tint = Color(0xFF2563EB), modifier = Modifier.size(14.dp))
                }
            }
            androidx.compose.animation.AnimatedContent(
                targetState = lanMode,
                modifier = Modifier.fillMaxWidth().weight(1f),
                transitionSpec = {
                    val enter = androidx.compose.animation.slideInHorizontally(
                        animationSpec = androidx.compose.animation.core.tween(300),
                    ) { full -> if (targetState) -full / 4 else full / 4 } + androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(190))
                    val exit = androidx.compose.animation.slideOutHorizontally(
                        animationSpec = androidx.compose.animation.core.tween(230),
                    ) { full -> if (targetState) full / 4 else -full / 4 } + androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(150))
                    enter.togetherWith(exit)
                },
                label = "lanWanSlide",
            ) { isLan ->
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        if (isLan) runtime.lanAddress else runtime.wanAddress,
                        color = valueColor,
                        fontSize = 15.sp,
                        lineHeight = 18.sp,
                        fontWeight = FontWeight.ExtraBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.height(20.dp),
                    )
                    Text(
                        if (isLan) "${runtime.lanInterface} · $connections 连接" else "${countryEmoji(runtime.wanCountryCode)} ${runtime.wanRegion}",
                        color = Color(0xFF64748B),
                        fontSize = 11.sp,
                        lineHeight = 14.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.height(18.dp),
                    )
                }
            }
        }
    }
}'''
ref = replace_region(
    ref,
    '@Composable\nprivate fun RefNetworkIdentityCard',
    '@Composable\nprivate fun RefSpeedCard',
    network_card,
    'network identity card',
)

subscription_card = r'''@Composable
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
    val scale by animateFloatAsState(if (pressed) .96f else 1f, spring(dampingRatio = .74f, stiffness = 560f), label = "subscriptionCompactPress")
    val shape = RoundedCornerShape(20.dp)
    Surface(
        modifier = modifier.height(112.dp)
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
            Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(Modifier.fillMaxWidth().height(20.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("订阅", color = Color(0xFF64748B), fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                if (total > 0L) Surface(shape = RoundedCornerShape(999.dp), color = Color(0xFFEBF3FF)) {
                    Text("剩余 ${((1f - ratio) * 100f).toInt()}%", Modifier.padding(horizontal = 7.dp, vertical = 2.dp), color = MaterialTheme.colorScheme.primary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
            }
            Text(if (total > 0L) refBytes(used) else "—", color = valueColor, fontSize = 16.sp, lineHeight = 19.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, modifier = Modifier.height(20.dp))
            Box(Modifier.fillMaxWidth().height(4.dp).background(Color(0xFFEFF3F8), CircleShape)) {
                if (total > 0L && ratio > 0f) {
                    Box(
                        Modifier.fillMaxWidth(ratio.coerceIn(.001f, 1f)).fillMaxHeight()
                            .background(Brush.horizontalGradient(listOf(Color(0xFF3B82F6), Color(0xFF6366F1))), CircleShape),
                    )
                }
            }
            Row(Modifier.fillMaxWidth().height(18.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("已用流量", color = Color(0xFF94A3B8), fontSize = 11.sp, modifier = Modifier.weight(1f))
                Text(if (total > 0L) "总 ${refBytes(total)}" else "${items.size} 个订阅", color = Color(0xFF64748B), fontSize = 11.sp, fontWeight = FontWeight.Medium, maxLines = 1)
            }
        }
    }
}'''
ref = replace_region(
    ref,
    '@Composable\nprivate fun RefSubscriptionCompact',
    '@Composable\nprivate fun RefResourceCard',
    subscription_card,
    'subscription compact card',
)

resource_card = r'''@Composable
private fun RefResourceCard(memory: Long, cpuPercent: Float, modifier: Modifier) {
    val t = LocalHetuTokens.current
    val scheme = MaterialTheme.colorScheme
    val valueColor = if (scheme.background.luminance() < .5f) t.textPrimary else Color(0xFF0F172A)
    val progress = (cpuPercent / 100f).coerceIn(0f, 1f)
    val pulseTransition = androidx.compose.animation.core.rememberInfiniteTransition(label = "resourceCpuPulse")
    val pulse by pulseTransition.animateFloat(
        initialValue = .42f,
        targetValue = 1f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
            animation = androidx.compose.animation.core.tween(1200),
            repeatMode = androidx.compose.animation.core.RepeatMode.Reverse,
        ),
        label = "resourceCpuPulseAlpha",
    )
    Surface(modifier = modifier.height(112.dp), shape = RoundedCornerShape(20.dp), color = t.cardBackground, shadowElevation = 1.dp) {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(Modifier.fillMaxWidth().height(24.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("资源占用", color = Color(0xFF64748B), fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Canvas(Modifier.size(24.dp)) {
                    val halo = 10.3.dp.toPx() + 1.2.dp.toPx() * pulse
                    drawCircle(Color(0xFF2563EB).copy(alpha = .035f + .055f * pulse), radius = halo, style = Stroke(width = 1.dp.toPx()))
                    drawCircle(Color(0xFFE2E8F0).copy(alpha = .68f), style = Stroke(width = 2.dp.toPx()))
                    drawArc(
                        color = Color(0xFF002FA7).copy(alpha = .62f + .18f * pulse),
                        startAngle = -90f,
                        sweepAngle = 360f * progress,
                        useCenter = false,
                        style = Stroke(width = 2.2.dp.toPx()),
                    )
                    drawCircle(Color(0xFF2563EB).copy(alpha = if (progress > .02f) .18f + .12f * pulse else .08f), radius = 2.5.dp.toPx())
                }
            }
            Row(Modifier.fillMaxWidth().height(20.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("内存", color = Color(0xFF94A3B8), fontSize = 11.sp, modifier = Modifier.weight(1f))
                Text(refBytes(memory), color = valueColor, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
            }
            Row(Modifier.fillMaxWidth().height(20.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("CPU", color = Color(0xFF94A3B8), fontSize = 11.sp, modifier = Modifier.weight(1f))
                Text(String.format(java.util.Locale.US, "%.1f%%", cpuPercent), color = valueColor, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
            }
        }
    }
}'''
ref = replace_region(
    ref,
    '@Composable\nprivate fun RefResourceCard',
    'private fun countryEmoji',
    resource_card,
    'resource card',
)

# Slower, visible chevron rotation and a lighter floating selection mark.
ref = require_replace(
    ref,
    'animationSpec = spring(dampingRatio = .76f, stiffness = 420f),\n                label = "groupArrow${group.name}",',
    'animationSpec = spring(dampingRatio = .58f, stiffness = 250f),\n                label = "groupArrow${group.name}",',
    'group chevron animation',
)
ref = require_replace(
    ref,
    'modifier = Modifier.align(Alignment.TopEnd).padding(top = 6.dp, end = 6.dp),',
    'modifier = Modifier.align(Alignment.TopEnd).padding(top = 5.dp, end = 5.dp),',
    'node selected overlay position',
)
ref = require_replace(
    ref,
    'Icon(Icons.Rounded.Check, "已选择", tint = Color(0xFF2563EB), modifier = Modifier.size(15.dp))',
    'Icon(Icons.Rounded.Check, "已选择", tint = Color(0xFF2563EB), modifier = Modifier.size(13.dp).graphicsLayer { alpha = .92f })',
    'node selected icon size',
)

# Tab content gets a unique key/content type so LazyColumn never reuses stale slots across tabs.
ref = require_replace(ref, 'itemsIndexed(filteredGroups.chunked(2), key = { index, _ -> "groups-$index" })', 'itemsIndexed(filteredGroups.chunked(2), key = { index, _ -> "${tab.name}-groups-$index" }, contentType = { _, _ -> "group-row" })', 'group row keys')
ref = require_replace(ref, 'RefPanelTab.Nodes -> item { RefTrafficOverview(state) }', 'RefPanelTab.Nodes -> item(key = "${tab.name}-traffic-overview", contentType = "traffic-overview") { RefTrafficOverview(state) }', 'nodes tab key')
ref = require_replace(ref, 'RefPanelTab.Subscriptions -> items(providers, key = { it.name })', 'RefPanelTab.Subscriptions -> items(providers, key = { "${tab.name}-provider-${it.name}" }, contentType = { "subscription-provider" })', 'subscription keys')
ref = require_replace(ref, 'items(shown, key = { (if (connectionView == "active") "a-" else "c-") + it.id })', 'items(shown, key = { "${tab.name}-" + (if (connectionView == "active") "a-" else "c-") + it.id }, contentType = { "connection-row" })', 'connection keys')
ref = require_replace(ref, 'itemsIndexed(rules.chunked(18), key = { index, _ -> "rule-group-$index" })', 'itemsIndexed(rules.chunked(18), key = { index, _ -> "${tab.name}-rule-group-$index" }, contentType = { _, _ -> "rule-group" })', 'rule keys')
ref = require_replace(ref, 'RefPanelTab.RuleSets -> items(ruleSets, key = { it.name })', 'RefPanelTab.RuleSets -> items(ruleSets, key = { "${tab.name}-ruleset-${it.name}" }, contentType = { "ruleset-row" })', 'ruleset keys')

# Give the low-rate chart a larger minimum dynamic range instead of hugging the X axis.
ref = require_replace(ref, 'val simulate = points.size < 2 || peak < 1024L', 'val simulate = points.size < 2 || peak < 4096L', 'traffic minimum range')
ref = require_replace(ref, 'val upload = wave(0f, .81f, .018f)\n                        val download = wave(1.18f, .86f, .024f)', 'val upload = wave(0f, .76f, .028f)\n                        val download = wave(1.18f, .82f, .036f)', 'traffic idle wave amplitude')

# Success haptics: confirm + a short follow-up tick.
ref = ref.replace(
    'capsuleText = "订阅更新成功"\n                                    view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)',
    'capsuleText = "订阅更新成功"\n                                    view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)\n                                    delay(70)\n                                    view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)',
    1,
)
ref = ref.replace(
    'capsuleText = "规则集更新完成"\n                                view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)',
    'capsuleText = "规则集更新完成"\n                                view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)\n                                delay(70)\n                                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)',
    1,
)

# Switch rows get light tactile feedback before state mutation.
switch_start = ref.find('@Composable\nprivate fun RefSwitchRow')
switch_end = ref.find('@Composable\nprivate fun RefDivider', switch_start)
if switch_start < 0 or switch_end < 0:
    raise RuntimeError('Missing RefSwitchRow region')
switch_block = ref[switch_start:switch_end]
switch_block = require_replace(switch_block, '    val t = LocalHetuTokens.current\n', '    val t = LocalHetuTokens.current\n    val view = LocalView.current\n', 'switch local view')
switch_block = require_replace(switch_block, '            onCheckedChange = onCheckedChange,', '            onCheckedChange = { value ->\n                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)\n                onCheckedChange(value)\n            },', 'switch haptic')
ref = ref[:switch_start] + switch_block + ref[switch_end:]

# Log/info bottom sheet: keep swipe-down handle, remove full-width close button, add compact top-right X.
info_start = ref.find('@OptIn(ExperimentalMaterial3Api::class)\n@Composable\nprivate fun RefInfoBottomSheet')
info_end = ref.find('@Composable\nprivate fun RefSheetDragHandle', info_start)
if info_start < 0 or info_end < 0:
    raise RuntimeError('Missing RefInfoBottomSheet region')
info = ref[info_start:info_end]
info = require_replace(
    info,
    'Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 18.dp, end = 18.dp, bottom = 18.dp),',
    'Modifier.fillMaxWidth().fillMaxHeight(.78f).navigationBarsPadding().padding(start = 18.dp, end = 18.dp, bottom = 18.dp),',
    'info sheet height',
)
info = require_replace(
    info,
    '            Text(title, color = if (terminal) Color(0xFFF8FAFC) else t.textPrimary, fontSize = 20.sp, lineHeight = 25.sp, fontWeight = FontWeight.Bold)\n',
    '''            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(title, color = if (terminal) Color(0xFFF8FAFC) else t.textPrimary, fontSize = 20.sp, lineHeight = 25.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.size(34.dp).background(if (terminal) Color.White.copy(alpha = .07f) else t.controlBackground.copy(alpha = .72f), CircleShape),
                ) {
                    Icon(Icons.Rounded.Close, "关闭", tint = if (terminal) Color(0xFFCBD5E1) else t.textSecondary, modifier = Modifier.size(17.dp))
                }
            }
''',
    'info sheet header',
)
info = require_replace(info, '.heightIn(max = 430.dp)', '.weight(1f)', 'info scroll height')
close_button = '''            FilledTonalButton(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = if (terminal) Color(0xFF1E293B) else t.controlBackground,
                    contentColor = if (terminal) Color(0xFFE2E8F0) else t.textPrimary,
                ),
            ) {
                Text(actionLabel, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }
'''
info = require_replace(info, close_button, '', 'remove bottom close button')
ref = ref[:info_start] + info + ref[info_end:]

ref_path.write_text(ref)

# Ad-filter stats: 3 micro floating dashboard cards.
ad_path = ROOT / 'ProxyAdblockChainActivity.kt'
ad = ad_path.read_text()
chain_metric = r'''@Composable
private fun ChainMetric(label: String, value: String, modifier: Modifier = Modifier) {
    val t = LocalHetuTokens.current
    val dark = MaterialTheme.colorScheme.background.luminance() < .5f
    val accent = when (label) {
        "规则源" -> Color(0xFF059669)
        "本次命中" -> Color(0xFF2563EB)
        else -> Color(0xFF002FA7)
    }
    Surface(
        modifier = modifier.height(70.dp),
        shape = RoundedCornerShape(16.dp),
        color = if (dark) t.controlBackground.copy(alpha = .72f) else Color(0xFFF8FAFC),
        tonalElevation = 0.dp,
        shadowElevation = if (dark) 0.dp else 1.dp,
    ) {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 11.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(value, color = accent, fontSize = 20.sp, lineHeight = 23.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
            Spacer(Modifier.height(2.dp))
            Text(label, color = Color(0xFF94A3B8), fontSize = 10.sp, lineHeight = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1)
        }
    }
}'''
ad = replace_region(ad, '@Composable\nprivate fun ChainMetric', '@Composable\nprivate fun ChainSectionLabel', chain_metric, 'chain metric')
ad_path.write_text(ad)

# YAML editor: syntax tint + indentation guides while keeping line-number gutter.
sub_path = ROOT / 'ProxySubscriptionActivity.kt'
sub = sub_path.read_text()
sub = require_replace(sub, 'import androidx.compose.ui.text.font.FontFamily\n', 'import androidx.compose.ui.text.AnnotatedString\nimport androidx.compose.ui.text.SpanStyle\nimport androidx.compose.ui.text.font.FontFamily\nimport androidx.compose.ui.text.input.OffsetMapping\nimport androidx.compose.ui.text.input.TransformedText\nimport androidx.compose.ui.text.input.VisualTransformation\n', 'yaml syntax imports')
yaml_transform = r'''
private object YamlSyntaxHighlightTransformation : VisualTransformation {
    private val keyRegex = Regex("(?m)^[\\t -]*([A-Za-z0-9_.-]+)(?=\\s*:)")
    private val scalarRegex = Regex("(?<![A-Za-z0-9_.-])(?:true|false|null|-?\\d+(?:\\.\\d+)?)(?![A-Za-z0-9_.-])", RegexOption.IGNORE_CASE)
    private val commentRegex = Regex("(?m)#.*$")

    override fun filter(text: AnnotatedString): TransformedText {
        val source = text.text
        val builder = AnnotatedString.Builder(source)
        keyRegex.findAll(source).forEach { match ->
            val group = match.groups[1] ?: return@forEach
            builder.addStyle(
                SpanStyle(color = Color(0xFF0E7490), fontWeight = FontWeight.SemiBold),
                group.range.first,
                group.range.last + 1,
            )
        }
        scalarRegex.findAll(source).forEach { match ->
            builder.addStyle(
                SpanStyle(color = Color(0xFF6366F1), fontWeight = FontWeight.Medium),
                match.range.first,
                match.range.last + 1,
            )
        }
        commentRegex.findAll(source).forEach { match ->
            builder.addStyle(
                SpanStyle(color = Color(0xFF64748B)),
                match.range.first,
                match.range.last + 1,
            )
        }
        return TransformedText(builder.toAnnotatedString(), OffsetMapping.Identity)
    }
}
'''
sub = require_replace(sub, '@Composable\nprivate fun ProxySubscriptionScreen', yaml_transform + '\n@Composable\nprivate fun ProxySubscriptionScreen', 'yaml transformation object')
sub = require_replace(
    sub,
    '''                                modifier = Modifier.weight(1f)
                                    .horizontalScroll(editorHorizontalScroll)
                                    .padding(horizontal = 12.dp, vertical = 12.dp),''',
    '''                                modifier = Modifier.weight(1f)
                                    .horizontalScroll(editorHorizontalScroll)
                                    .drawBehind {
                                        val guideColor = if (dark) Color.White.copy(alpha = .04f) else Color(0xFFE2E8F0).copy(alpha = .86f)
                                        val step = 16.dp.toPx()
                                        for (index in 1..5) {
                                            val x = index * step
                                            drawLine(guideColor, Offset(x, 0f), Offset(x, size.height), strokeWidth = .5.dp.toPx())
                                        }
                                    }
                                    .padding(horizontal = 12.dp, vertical = 12.dp),''',
    'yaml indentation guides',
)
sub = require_replace(
    sub,
    '                                cursorBrush = SolidColor(scheme.primary),\n',
    '                                cursorBrush = SolidColor(scheme.primary),\n                                visualTransformation = YamlSyntaxHighlightTransformation,\n',
    'yaml visual transformation',
)
sub_path.write_text(sub)

# App list route: paint the window immediately and use a real skeleton while package scan runs.
apps_path = ROOT / 'ProxyAppSelectionActivity.kt'
apps = apps_path.read_text()
apps = require_replace(
    apps,
    '        super.onCreate(savedInstanceState)\n        enableEdgeToEdge()',
    '        super.onCreate(savedInstanceState)\n        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.rgb(241, 245, 249)))\n        enableEdgeToEdge()',
    'app list window background',
)
apps = require_replace(
    apps,
    '''    var reload by remember { mutableIntStateOf(0) }
    val apps by produceState(initialValue = controller.cachedApps(), reload) {
        value = try { controller.loadApps(forceRefresh = reload > 0) }
        catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { controller.cachedApps() }
    }''',
    '''    var reload by remember { mutableIntStateOf(0) }
    var loadingApps by remember { mutableStateOf(controller.cachedApps().isEmpty()) }
    val apps by produceState(initialValue = controller.cachedApps(), reload) {
        loadingApps = true
        value = try { controller.loadApps(forceRefresh = reload > 0) }
        catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { controller.cachedApps() }
        finally { loadingApps = false }
    }''',
    'app loading state',
)
loading_start = apps.find('        if (apps.isEmpty()) {')
loading_end = apps.find('        items(visible, key = { it.packageName })', loading_start)
if loading_start < 0 or loading_end < 0:
    raise RuntimeError('Missing app loading region')
loading_ui = r'''        if (loadingApps && apps.isEmpty()) {
            item("loading-skeleton") {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    repeat(6) { index ->
                        Surface(
                            modifier = Modifier.fillMaxWidth().height(64.dp),
                            shape = RoundedCornerShape(18.dp),
                            color = if (dark) t.elevatedCardBackground else Color.White,
                            shadowElevation = if (dark) 0.dp else 1.dp,
                        ) {
                            Row(Modifier.fillMaxSize().padding(horizontal = 13.dp), verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(42.dp).background(t.controlBackground.copy(alpha = .72f), RoundedCornerShape(12.dp)))
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                                    Box(Modifier.fillMaxWidth(if (index % 2 == 0) .52f else .66f).height(12.dp).background(t.controlBackground.copy(alpha = .76f), CircleShape))
                                    Box(Modifier.fillMaxWidth(if (index % 3 == 0) .72f else .58f).height(8.dp).background(t.controlBackground.copy(alpha = .50f), CircleShape))
                                }
                                Box(Modifier.size(22.dp).background(t.controlBackground.copy(alpha = .62f), CircleShape))
                            }
                        }
                    }
                }
            }
        }

'''
apps = apps[:loading_start] + loading_ui + apps[loading_end:]
apps_path.write_text(apps)

# WebUI route: no white frame before Compose; keep a skeleton overlay until page finish.
web_path = ROOT / 'ProxyWebUiActivity.kt'
web = web_path.read_text()
web = require_replace(
    web,
    '        super.onCreate(savedInstanceState)\n        enableEdgeToEdge()',
    '        super.onCreate(savedInstanceState)\n        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.rgb(241, 245, 249)))\n        enableEdgeToEdge()',
    'webui window background',
)
web = require_replace(
    web,
    '''                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    if (!url.isNullOrBlank() && url.startsWith("http://127.0.0.1:${MihomoStartupConfig.CONTROLLER_PORT}/ui/")) {
                        pageError = ""
                    }
                }''',
    '''                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    if (!url.isNullOrBlank() && url.startsWith("http://127.0.0.1:${MihomoStartupConfig.CONTROLLER_PORT}/ui/")) {
                        pageError = ""
                        progress = 100
                        preparing = false
                    }
                }''',
    'webui page finished',
)
web = require_replace(
    web,
    '''        } catch (error: Exception) {
            pageError = error.message ?: "Zashboard 准备失败"
        } finally {
            preparing = false
        }''',
    '''        } catch (error: Exception) {
            pageError = error.message ?: "Zashboard 准备失败"
            preparing = false
        }''',
    'webui preparing lifecycle',
)
web_when_start = web.find('        when {\n            preparing -> {')
web_when_end = web.rfind('\n    }\n}')
if web_when_start < 0 or web_when_end < 0 or web_when_end <= web_when_start:
    raise RuntimeError('Missing WebUI render region')
web_render = r'''        Box(Modifier.fillMaxSize()) {
            AndroidView(
                factory = { webView },
                modifier = Modifier.fillMaxSize(),
            )

            if ((preparing || progress < 100) && pageError.isBlank()) {
                Column(
                    Modifier.fillMaxSize().background(tokens.pageBackground).padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Surface(shape = RoundedCornerShape(20.dp), color = tokens.cardBackground) {
                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Box(Modifier.fillMaxWidth(.42f).height(14.dp).background(tokens.controlBackground, RoundedCornerShape(7.dp)))
                            Box(Modifier.fillMaxWidth(.74f).height(10.dp).background(tokens.controlBackground.copy(alpha = .72f), RoundedCornerShape(5.dp)))
                        }
                    }
                    repeat(4) { index ->
                        Surface(shape = RoundedCornerShape(18.dp), color = tokens.cardBackground) {
                            Row(Modifier.fillMaxWidth().height(66.dp).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(36.dp).background(tokens.controlBackground, RoundedCornerShape(11.dp)))
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                                    Box(Modifier.fillMaxWidth(if (index % 2 == 0) .56f else .70f).height(11.dp).background(tokens.controlBackground, RoundedCornerShape(6.dp)))
                                    Box(Modifier.fillMaxWidth(.82f).height(8.dp).background(tokens.controlBackground.copy(alpha = .64f), RoundedCornerShape(4.dp)))
                                }
                            }
                        }
                    }
                    Text("正在准备本机 Zashboard…", color = tokens.textSecondary, fontSize = 11.sp, modifier = Modifier.padding(start = 4.dp, top = 2.dp))
                }
            }

            if (pageError.isNotBlank()) {
                Box(Modifier.fillMaxSize().background(tokens.pageBackground).padding(22.dp), contentAlignment = Alignment.Center) {
                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        color = tokens.cardBackground,
                        shadowElevation = 0.dp,
                    ) {
                        Column(
                            Modifier.fillMaxWidth().padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Text("WebUI 暂时不可用", color = tokens.textPrimary, style = MaterialTheme.typography.titleMedium)
                            Text(pageError, color = tokens.textSecondary, style = MaterialTheme.typography.bodyMedium)
                            Button(
                                onClick = ::repairAndReload,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 46.dp),
                                shape = RoundedCornerShape(14.dp),
                            ) {
                                Icon(Icons.Rounded.Build, null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("重新安装并打开 Zashboard")
                            }
                        }
                    }
                }
            }
        }
'''
web = web[:web_when_start] + web_render + web[web_when_end:]
web_path.write_text(web)

print('Applied test51 flagship motion/rendering polish')
