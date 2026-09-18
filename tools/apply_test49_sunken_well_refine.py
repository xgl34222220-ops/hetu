from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
UI = ROOT / 'android-app/app/src/main/java/io/github/xgl34222220/hetu/ReferenceProxyActivity.kt'
AD = ROOT / 'android-app/app/src/main/java/io/github/xgl34222220/hetu/ProxyAdblockChainActivity.kt'
BUILD = ROOT / 'android-app/app/build.gradle.kts'


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
build = replace_once(build, 'versionCode = 448', 'versionCode = 449', 'version code')
build = replace_once(build, 'versionName = "0.4.0-test.48"', 'versionName = "0.4.0-test.49"', 'version name')
BUILD.write_text(build, encoding='utf-8')

text = UI.read_text(encoding='utf-8')

# ---------------------------------------------------------------------------
# 1) Strategy expansion: real cold-grey sunken well, never white-on-white.
# ---------------------------------------------------------------------------
new_expansion = r'''@Composable
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
    val shape = RoundedCornerShape(22.dp)
    val wellColor = if (dark) Color(0xFF18212E) else Color(0xFFEEF2F6)
    val wellBorder = if (dark) Color.White.copy(alpha = .08f) else Color(0xFFCBD5E1).copy(alpha = .52f)
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = shape,
        color = wellColor,
        border = BorderStroke(.8.dp, wellBorder),
        shadowElevation = 0.dp,
        tonalElevation = 0.dp,
    ) {
        Column(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            // A thin top compression line gives the well a visual inset without a second white shell.
            Box(
                Modifier.fillMaxWidth().height(2.dp)
                    .background(
                        Brush.horizontalGradient(
                            listOf(Color.Transparent, Color(0xFF0F172A).copy(alpha = if (dark) .18f else .07f), Color.Transparent),
                        ),
                        CircleShape,
                    ),
            )
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    Text("切换落地节点", color = if (dark) t.textSecondary else Color(0xFF64748B), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(6.dp))
                    Text("· 点击即生效", color = Color(0xFF94A3B8), fontSize = 10.sp, fontWeight = FontWeight.Normal)
                }
                val allTestSource = remember(group.name) { MutableInteractionSource() }
                val allPressed by allTestSource.collectIsPressedAsState()
                val allScale by animateFloatAsState(if (allPressed) .95f else 1f, label = "allDelay${group.name}")
                Row(
                    Modifier.graphicsLayer { scaleX = allScale; scaleY = allScale }
                        .shadow(2.dp, CircleShape, clip = false, ambientColor = Color(0xFF0F172A).copy(alpha = .04f), spotColor = Color(0xFF0F172A).copy(alpha = .05f))
                        .background(if (dark) Color.White.copy(alpha = .08f) else Color.White.copy(alpha = .92f), CircleShape)
                        .border(.6.dp, if (dark) Color.White.copy(alpha = .08f) else Color.White.copy(alpha = .92f), CircleShape)
                        .clip(CircleShape)
                        .clickable(interactionSource = allTestSource, indication = null, onClick = onTestAll)
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text("全测速", color = Color(0xFF2563EB), fontSize = 11.sp, fontWeight = FontWeight.ExtraBold)
                    Text("⚡", color = Color(0xFFF59E0B), fontSize = 10.sp)
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
}'''
text = replace_regex(text, r'@Composable\nprivate fun RefInlineGroupExpansion\(.*?\n\}\n\n@Composable\nprivate fun RefInlineNodeCard', new_expansion + '\n\n@Composable\nprivate fun RefInlineNodeCard', 'sunken well expansion')

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
    val t = LocalHetuTokens.current
    val dark = MaterialTheme.colorScheme.background.luminance() < .5f
    val shape = RoundedCornerShape(14.dp)
    val source = remember(node.name) { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) .97f else 1f, spring(dampingRatio = .72f, stiffness = 580f), label = "inlineNode${node.name}")
    var revealed by remember(node.name) { mutableStateOf(false) }
    LaunchedEffect(node.name) {
        delay((index * 22L).coerceAtMost(260L))
        revealed = true
    }
    val backgroundBrush = when {
        dark && active -> Brush.verticalGradient(listOf(Color(0xFF172554), Color(0xFF111C38)))
        dark -> Brush.verticalGradient(listOf(Color(0xFF242E3C), Color(0xFF202936)))
        active -> Brush.verticalGradient(listOf(Color(0xFFF8FBFF), Color(0xFFEEF6FF)))
        else -> Brush.verticalGradient(listOf(Color.White, Color.White))
    }
    val borderColor = when {
        dark && active -> Color(0xFF60A5FA).copy(alpha = .44f)
        dark -> Color.White.copy(alpha = .08f)
        active -> Color(0xFFBFDBFE)
        else -> Color.White.copy(alpha = .88f)
    }
    Box(modifier.height(64.dp)) {
        androidx.compose.animation.AnimatedVisibility(
            visible = revealed,
            modifier = Modifier.fillMaxSize(),
            enter = androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(180)) +
                androidx.compose.animation.slideInVertically(androidx.compose.animation.core.tween(230)) { it / 3 },
        ) {
            Box(
                Modifier.fillMaxSize()
                    .graphicsLayer { scaleX = scale; scaleY = scale; alpha = if (pressed) .90f else 1f }
                    .shadow(
                        if (active) 5.dp else 3.dp,
                        shape,
                        clip = false,
                        ambientColor = if (active) Color(0xFF2563EB).copy(alpha = .07f) else Color(0xFF0F172A).copy(alpha = .035f),
                        spotColor = if (active) Color(0xFF2563EB).copy(alpha = .10f) else Color(0xFF0F172A).copy(alpha = .05f),
                    )
                    .background(backgroundBrush, shape)
                    .border(if (active) 1.dp else .7.dp, borderColor, shape)
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
                            Spacer(Modifier.width(5.dp))
                        }
                        Text(
                            node.name,
                            color = if (active && !dark) Color(0xFF2563EB) else t.textPrimary,
                            fontSize = 12.sp,
                            lineHeight = 15.sp,
                            fontWeight = if (active) FontWeight.ExtraBold else FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            refNodeProtocol(node),
                            color = if (active) Color(0xFF60A5FA) else Color(0xFF94A3B8),
                            fontSize = 10.sp,
                            lineHeight = 12.sp,
                            fontWeight = FontWeight.Medium,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            maxLines = 1,
                        )
                        Spacer(Modifier.weight(1f))
                        RefDelayBadge(delay, testing, onDelay, selected = active)
                    }
                }
                androidx.compose.animation.AnimatedVisibility(
                    visible = active,
                    modifier = Modifier.align(Alignment.TopEnd).padding(top = 6.dp, end = 6.dp),
                    enter = androidx.compose.animation.scaleIn(initialScale = .15f, animationSpec = spring(dampingRatio = .56f, stiffness = 520f)) + androidx.compose.animation.fadeIn(),
                    exit = androidx.compose.animation.scaleOut(targetScale = .45f) + androidx.compose.animation.fadeOut(),
                ) {
                    Icon(Icons.Rounded.Check, "已选择", tint = Color(0xFF2563EB), modifier = Modifier.size(15.dp))
                }
            }
        }
    }
}'''
text = replace_regex(text, r'@Composable\nprivate fun RefInlineNodeCard\(.*?\n\}\n\n@Composable\nprivate fun RefGroupCornerVisual', new_inline_node + '\n\n@Composable\nprivate fun RefGroupCornerVisual', 'floating child nodes')

# Selected child uses a white/blue latency badge; other latency badges keep semantic colors.
text = replace_once(
    text,
    'private fun RefDelayBadge(value: Long?, testing: Boolean, onClick: (() -> Unit)?) {',
    'private fun RefDelayBadge(value: Long?, testing: Boolean, onClick: (() -> Unit)?, selected: Boolean = false) {',
    'delay badge selected parameter',
)
text = replace_once(
    text,
    '''    val shape = CircleShape\n    Row(\n        Modifier.width(if (onClick != null) 68.dp else 62.dp).height(22.dp)''',
    '''    val shape = CircleShape\n    val finalBackground = if (selected) Color.White else background\n    val finalTextColor = if (selected) Color(0xFF2563EB) else textColor\n    Row(\n        Modifier.width(if (onClick != null) 68.dp else 62.dp).height(22.dp)''',
    'selected badge colors',
)
text = replace_once(
    text,
    '''            .shadow(if (onClick != null) 2.dp else 0.dp, shape, clip = false, ambientColor = textColor.copy(alpha = .10f), spotColor = textColor.copy(alpha = .12f))\n            .background(if (testing) background.copy(alpha = .78f) else background, shape)\n            .border(.7.dp, if (testing) textColor.copy(alpha = .22f + .22f * pulse) else textColor.copy(alpha = if (onClick != null) .10f else .04f), shape)''',
    '''            .shadow(if (onClick != null) 2.dp else 0.dp, shape, clip = false, ambientColor = finalTextColor.copy(alpha = .10f), spotColor = finalTextColor.copy(alpha = .12f))\n            .background(if (testing) finalBackground.copy(alpha = .82f) else finalBackground, shape)\n            .border(.7.dp, if (selected) Color(0xFFDBEAFE) else if (testing) finalTextColor.copy(alpha = .22f + .22f * pulse) else finalTextColor.copy(alpha = if (onClick != null) .10f else .04f), shape)''',
    'selected badge surface',
)
text = text.replace('            color = textColor,\n            fontSize = 10.sp,', '            color = finalTextColor,\n            fontSize = 10.sp,', 1)
text = text.replace('                    color = textColor,\n                    trackColor = textColor.copy(alpha = .14f),', '                    color = finalTextColor,\n                    trackColor = finalTextColor.copy(alpha = .14f),', 1)
text = text.replace('                Icon(Icons.Rounded.Bolt, "单独测速", tint = textColor.copy(alpha = .86f), modifier = Modifier.size(10.dp))', '                Icon(Icons.Rounded.Bolt, "单独测速", tint = finalTextColor.copy(alpha = .86f), modifier = Modifier.size(10.dp))', 1)

# ---------------------------------------------------------------------------
# 2) Rules: 18 rows per grouped card, action is pure colored type instead of pills.
# ---------------------------------------------------------------------------
text = replace_once(text, 'rules.chunked(12)', 'rules.chunked(18)', 'rule chunk size')
new_rules = r'''@Composable
private fun RefRuleGroupCard(items: List<ProxyRuleUi>) {
    val t = LocalHetuTokens.current
    val dark = MaterialTheme.colorScheme.background.luminance() < .5f
    val shape = RoundedCornerShape(22.dp)
    Surface(
        shape = shape,
        color = t.cardBackground,
        border = BorderStroke(.7.dp, if (dark) t.outline.copy(alpha = .32f) else Color(0xFFF1F5F9)),
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
                            fontSize = 13.sp,
                            lineHeight = 16.sp,
                            fontWeight = FontWeight.ExtraBold,
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
                    Spacer(Modifier.width(12.dp))
                    val reject = item.proxy.equals("REJECT", true) || item.proxy.startsWith("REJECT-", true)
                    val direct = item.proxy.equals("DIRECT", true)
                    Text(
                        item.proxy,
                        color = when {
                            reject -> Color(0xFFF43F5E)
                            direct -> Color(0xFF2563EB)
                            else -> Color(0xFF64748B)
                        },
                        fontSize = 12.sp,
                        lineHeight = 15.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        letterSpacing = .45.sp,
                        maxLines = 1,
                    )
                }
                if (index != items.lastIndex) {
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 14.dp),
                        thickness = 1.dp,
                        color = if (dark) t.outline.copy(alpha = .28f) else Color(0xFFF1F5F9).copy(alpha = .84f),
                    )
                }
            }
        }
    }
}'''
text = replace_regex(text, r'@Composable\nprivate fun RefRuleGroupCard\(.*?\n\}\n\n@Composable\nprivate fun RefRuleSetRow', new_rules + '\n\n@Composable\nprivate fun RefRuleSetRow', 'rules pure action text')

# ---------------------------------------------------------------------------
# 3) Overview: animated micro-wave + area aurora when throughput is near zero.
# ---------------------------------------------------------------------------
new_traffic = r'''@Composable
private fun RefTrafficOverview(state: ProxyComposeState) {
    val t = LocalHetuTokens.current
    val scheme = MaterialTheme.colorScheme
    val history = remember { mutableStateListOf<Triple<Long, Long, Long>>() }
    var lastUpload by remember { mutableLongStateOf(state.uploadTotal) }
    var lastDownload by remember { mutableLongStateOf(state.downloadTotal) }
    var lastAt by remember { mutableLongStateOf(0L) }
    var upRate by remember { mutableLongStateOf(0L) }
    var downRate by remember { mutableLongStateOf(0L) }
    val pulse = androidx.compose.animation.core.rememberInfiniteTransition(label = "trafficIdlePulse")
    val idlePhase by pulse.animateFloat(
        initialValue = 0f,
        targetValue = 6.2831855f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
            animation = androidx.compose.animation.core.tween(3200, easing = androidx.compose.animation.core.LinearEasing),
        ),
        label = "trafficIdlePhase",
    )

    LaunchedEffect(state.uploadTotal, state.downloadTotal) {
        val now = SystemClock.elapsedRealtime()
        if (lastAt > 0L && now > lastAt && state.uploadTotal >= lastUpload && state.downloadTotal >= lastDownload) {
            val elapsed = now - lastAt
            upRate = ((state.uploadTotal - lastUpload) * 1000L / elapsed).coerceAtLeast(0L)
            downRate = ((state.downloadTotal - lastDownload) * 1000L / elapsed).coerceAtLeast(0L)
            history += Triple(now, upRate, downRate)
            while (history.isNotEmpty() && history.first().first < now - 60_000L) history.removeAt(0)
        }
        lastAt = now
        lastUpload = state.uploadTotal
        lastDownload = state.downloadTotal
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            RefRateCard("上行速度", upRate, Icons.Rounded.ArrowUpward, t.success, Modifier.weight(1f))
            RefRateCard("下行速度", downRate, Icons.Rounded.ArrowDownward, scheme.primary, Modifier.weight(1f))
        }
        Surface(shape = RoundedCornerShape(18.dp), color = t.cardBackground, shadowElevation = 1.dp) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 13.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("总流量", color = t.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text("上行 ${refBytes(state.uploadTotal)}", color = t.success, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold)
                Text("  /  ", color = Color(0xFFCBD5E1), fontSize = 11.sp)
                Text("下行 ${refBytes(state.downloadTotal)}", color = scheme.primary, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold)
            }
        }
        Surface(shape = RoundedCornerShape(18.dp), color = t.cardBackground, shadowElevation = 1.dp) {
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("最近 60 秒流量", color = t.textPrimary, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    Text("↑ 上行   ↓ 下行", color = t.textSecondary, style = MaterialTheme.typography.labelSmall)
                }
                val points = history.toList()
                Canvas(Modifier.fillMaxWidth().height(138.dp)) {
                    fun closeArea(coords: List<Offset>): Path {
                        val path = Path()
                        if (coords.isEmpty()) return path
                        path.moveTo(coords.first().x, coords.first().y)
                        coords.drop(1).forEach { path.lineTo(it.x, it.y) }
                        path.lineTo(coords.last().x, size.height)
                        path.lineTo(coords.first().x, size.height)
                        path.close()
                        return path
                    }
                    fun smooth(coords: List<Offset>, area: Boolean): Path {
                        val path = Path()
                        if (coords.isEmpty()) return path
                        path.moveTo(coords.first().x, coords.first().y)
                        for (i in 0 until coords.lastIndex) {
                            val p0 = if (i == 0) coords[i] else coords[i - 1]
                            val p1 = coords[i]
                            val p2 = coords[i + 1]
                            val p3 = if (i + 2 < coords.size) coords[i + 2] else p2
                            val c1 = Offset(p1.x + (p2.x - p0.x) / 6f, p1.y + (p2.y - p0.y) / 6f)
                            val c2 = Offset(p2.x - (p3.x - p1.x) / 6f, p2.y - (p3.y - p1.y) / 6f)
                            path.cubicTo(c1.x, c1.y, c2.x, c2.y, p2.x, p2.y)
                        }
                        if (area) {
                            path.lineTo(coords.last().x, size.height)
                            path.lineTo(coords.first().x, size.height)
                            path.close()
                        }
                        return path
                    }

                    val peak = points.maxOfOrNull { maxOf(it.second, it.third) } ?: 0L
                    val simulate = points.size < 2 || peak < 1024L
                    if (!simulate) {
                        val maxRate = peak.coerceAtLeast(1L).toFloat()
                        val end = points.last().first
                        val start = end - 60_000L
                        fun series(index: Int): List<Offset> = points.map { point ->
                            val x = (((point.first - start).coerceIn(0L, 60_000L)).toFloat() / 60_000f) * size.width
                            val value = if (index == 1) point.second else point.third
                            val y = size.height - (value.toFloat() / maxRate * size.height * .86f)
                            Offset(x, y)
                        }
                        val upload = series(1)
                        val download = series(2)
                        drawPath(smooth(upload, true), brush = Brush.verticalGradient(listOf(t.success.copy(alpha = .20f), Color.Transparent), 0f, size.height))
                        drawPath(smooth(download, true), brush = Brush.verticalGradient(listOf(scheme.primary.copy(alpha = .20f), Color.Transparent), 0f, size.height))
                        drawPath(smooth(upload, false), color = t.success, style = Stroke(width = 2.15.dp.toPx()))
                        drawPath(smooth(download, false), color = scheme.primary, style = Stroke(width = 2.15.dp.toPx()))
                    } else {
                        fun wave(phaseOffset: Float, base: Float, amplitude: Float): List<Offset> = (0..28).map { i ->
                            val ratio = i / 28f
                            val angle = ratio * 8.2f + idlePhase + phaseOffset
                            val harmonic = kotlin.math.sin((angle * 1.67f).toDouble()).toFloat() * .34f
                            val y = size.height * base - (kotlin.math.sin(angle.toDouble()).toFloat() + harmonic) * size.height * amplitude
                            Offset(size.width * ratio, y)
                        }
                        val upload = wave(0f, .81f, .018f)
                        val download = wave(1.18f, .86f, .024f)
                        drawPath(closeArea(upload), brush = Brush.verticalGradient(listOf(t.success.copy(alpha = .18f), Color.Transparent), size.height * .68f, size.height))
                        drawPath(closeArea(download), brush = Brush.verticalGradient(listOf(scheme.primary.copy(alpha = .20f), Color.Transparent), size.height * .70f, size.height))
                        drawPath(smooth(upload, false), color = t.success.copy(alpha = .78f), style = Stroke(width = 1.8.dp.toPx()))
                        drawPath(smooth(download, false), color = scheme.primary.copy(alpha = .84f), style = Stroke(width = 1.8.dp.toPx()))
                    }
                }
            }
        }
    }
}'''
text = replace_regex(text, r'@Composable\nprivate fun RefTrafficOverview\(.*?\n\}\n\n@Composable\nprivate fun RefRateCard', new_traffic + '\n\n@Composable\nprivate fun RefRateCard', 'animated idle traffic baseline')

# ---------------------------------------------------------------------------
# 4) Tools: keep only actual status as pills; ordinary navigation stays grey text + chevron.
#    Test48 already de-blue'd normal rows, now keep core as the one explicit blue status capsule.
# ---------------------------------------------------------------------------
text = replace_once(
    text,
    'RefToolRow(Icons.Rounded.Memory, Color(0xFF334155), "内核管理", "下载、更新与维护内核", trailingText = state.core.ifBlank { "Mihomo" }, trailingColor = Color(0xFF2563EB)) {',
    'RefToolRow(Icons.Rounded.Memory, Color(0xFF334155), "内核管理", "下载、更新与维护内核", trailingText = state.core.ifBlank { "Mihomo" }, trailingBadge = true, trailingColor = Color(0xFF2563EB)) {',
    'core status badge only',
)

# Audit UI invariants.
required = [
    'Color(0xFFEEF2F6)',
    'shadowElevation = 0.dp',
    'RoundedCornerShape(14.dp)',
    'selected: Boolean = false',
    'rules.chunked(18)',
    'fontWeight = FontWeight.Black',
    'trafficIdlePhase',
    'peak < 1024L',
    'trailingBadge = true, trailingColor = Color(0xFF2563EB)',
]
for token in required:
    if token not in text:
        raise SystemExit(f'missing test49 UI invariant: {token}')
UI.write_text(text, encoding='utf-8')

# ---------------------------------------------------------------------------
# 5) Ad-filter detail: guaranteed visual breathing room at the bottom.
# ---------------------------------------------------------------------------
ad = AD.read_text(encoding='utf-8')
ad = replace_once(
    ad,
    'bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 28.dp,',
    'bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 64.dp,',
    'ad filter bottom breathing room',
)
if 'calculateBottomPadding() + 64.dp' not in ad:
    raise SystemExit('ad-filter bottom padding invariant missing')
AD.write_text(ad, encoding='utf-8')

print('test.49 sunken-well refinement applied')
