from pathlib import Path

path = Path('android-app/app/src/main/java/io/github/xgl34222220/hetu/ReferenceProxyActivity.kt')
s = path.read_text()


def one(old: str, new: str, label: str):
    global s
    if old not in s:
        raise SystemExit(f'missing exact block: {label}')
    s = s.replace(old, new, 1)


def replace_block(start: str, end: str, new: str, label: str):
    global s
    i = s.find(start)
    if i < 0:
        raise SystemExit(f'missing start: {label}')
    j = s.find(end, i)
    if j < 0:
        raise SystemExit(f'missing end: {label}')
    s = s[:i] + new.rstrip() + '\n' + s[j:]


def replace_in_region(start: str, end: str, old: str, new: str, label: str):
    global s
    i = s.find(start)
    j = s.find(end, i + 1)
    if i < 0 or j < 0:
        raise SystemExit(f'missing region: {label}')
    region = s[i:j]
    if old not in region:
        raise SystemExit(f'missing regional target: {label}')
    region = region.replace(old, new, 1)
    s = s[:i] + region + s[j:]


# Home hero: reference uses blue running marker and keeps uptime on its own line.
one(
    'Box(Modifier.size(9.dp).background(if (state.running) t.success else t.danger, CircleShape))',
    'Box(Modifier.size(9.dp).background(if (state.running) scheme.primary else t.danger, CircleShape))',
    'hero blue running marker',
)

# Home latency: original BoxProxy uses clean blue values without traffic-light coloring or pills.
replace_block(
    '@Composable\nprivate fun RefLatencyPanel',
    '\n@Composable\nprivate fun RefNetworkIdentityCard',
    '''@Composable
private fun RefLatencyPanel(baidu: Long?, cloudflare: Long?, google: Long?, testing: Boolean, onClick: () -> Unit) {
    val t = LocalHetuTokens.current
    val scheme = MaterialTheme.colorScheme
    Surface(shape = RoundedCornerShape(20.dp), color = t.cardBackground, shadowElevation = 1.dp) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 13.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("延迟", color = t.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                IconButton(onClick = onClick, enabled = !testing, modifier = Modifier.size(32.dp)) {
                    if (testing) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    else Icon(Icons.Rounded.Refresh, "全部测速", tint = t.textSecondary, modifier = Modifier.size(18.dp))
                }
                Icon(Icons.Rounded.Tune, null, tint = t.textSecondary, modifier = Modifier.size(17.dp))
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                RefLatencyColumn("Baidu", baidu, testing, Modifier.weight(1f))
                RefLatencyColumn("Cloudflare", cloudflare, testing, Modifier.weight(1f))
                RefLatencyColumn("Google", google, testing, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun RefLatencyColumn(label: String, value: Long?, testing: Boolean, modifier: Modifier) {
    val t = LocalHetuTokens.current
    val scheme = MaterialTheme.colorScheme
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, color = Color(0xFF64748B), fontSize = 11.sp, lineHeight = 15.sp, maxLines = 1)
        Text(
            if (testing) "…" else refDelay(value),
            color = if (value == null && !testing) Color(0xFF94A3B8) else scheme.primary,
            fontSize = 17.sp,
            lineHeight = 21.sp,
            fontWeight = FontWeight.ExtraBold,
            maxLines = 1,
        )
    }
}''',
    'latency panel clean values',
)

# Home four cards: natural height, compact label/value rows, no arrow ornaments, no clipping.
replace_block(
    '@Composable\nprivate fun RefNetworkIdentityCard',
    '\n@Composable\nprivate fun RefSpeedCard',
    '''@Composable
private fun RefNetworkIdentityCard(runtime: ProxyRuntimeSnapshot, connections: Int, modifier: Modifier) {
    val t = LocalHetuTokens.current
    var lanMode by rememberSaveable { mutableStateOf(true) }
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) .98f else 1f, spring(dampingRatio = .80f, stiffness = 520f), label = "networkCardPress")
    val shape = RoundedCornerShape(20.dp)
    Surface(
        modifier = modifier
            .heightIn(min = 98.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale; alpha = if (pressed) .92f else 1f }
            .clip(shape)
            .clickable(interactionSource = source, indication = null) { lanMode = !lanMode },
        shape = shape,
        color = t.cardBackground,
        shadowElevation = 1.dp,
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(if (lanMode) "LAN" else "WAN", color = Color(0xFF64748B), fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(if (lanMode) "IP" else "公网 IP", color = Color(0xFF94A3B8), fontSize = 10.sp, fontWeight = FontWeight.Medium)
                Text(
                    if (lanMode) runtime.lanAddress else runtime.wanAddress,
                    color = if (MaterialTheme.colorScheme.background.luminance() < .5f) t.textPrimary else Color(0xFF0F172A),
                    fontSize = 15.sp,
                    lineHeight = 19.sp,
                    fontWeight = FontWeight.ExtraBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(if (lanMode) "接口" else "地区", color = Color(0xFF94A3B8), fontSize = 11.sp, modifier = Modifier.weight(1f))
                Text(
                    if (lanMode) "${runtime.lanInterface} · $connections 连接" else "${countryEmoji(runtime.wanCountryCode)} ${runtime.wanRegion}",
                    color = Color(0xFF64748B),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}''',
    'LAN card',
)

replace_block(
    '@Composable\nprivate fun RefSpeedCard',
    '\n@Composable\nprivate fun RefSubscriptionCompact',
    '''@Composable
private fun RefSpeedCard(up: Long, down: Long, modifier: Modifier) {
    val t = LocalHetuTokens.current
    val valueColor = if (MaterialTheme.colorScheme.background.luminance() < .5f) t.textPrimary else Color(0xFF0F172A)
    Surface(modifier = modifier.heightIn(min = 98.dp), shape = RoundedCornerShape(20.dp), color = t.cardBackground, shadowElevation = 1.dp) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Text("网速", color = Color(0xFF64748B), fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("上行", color = Color(0xFF94A3B8), fontSize = 11.sp, modifier = Modifier.weight(1f))
                Text(refSpeed(up), color = valueColor, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("下行", color = Color(0xFF94A3B8), fontSize = 11.sp, modifier = Modifier.weight(1f))
                Text(refSpeed(down), color = valueColor, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            }
        }
    }
}''',
    'speed card',
)

replace_block(
    '@Composable\nprivate fun RefSubscriptionCompact',
    '\n@Composable\nprivate fun RefResourceCard',
    '''@Composable
private fun RefSubscriptionCompact(items: List<DashboardProviderUi>, modifier: Modifier) {
    val t = LocalHetuTokens.current
    val tracked = items.filter { it.hasSubscriptionInfo && it.total > 0L }
    val used = tracked.sumOf { it.used }
    val total = tracked.sumOf { it.total }
    val ratio = if (total <= 0L) 0f else (used.toDouble() / total.toDouble()).toFloat().coerceIn(0f, 1f)
    val valueColor = if (MaterialTheme.colorScheme.background.luminance() < .5f) t.textPrimary else Color(0xFF0F172A)
    Surface(modifier = modifier.heightIn(min = 98.dp), shape = RoundedCornerShape(20.dp), color = t.cardBackground, shadowElevation = 1.dp) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("订阅", color = Color(0xFF64748B), fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                if (total > 0L) Surface(shape = RoundedCornerShape(999.dp), color = Color(0xFFEBF3FF)) {
                    Text("剩余 ${((1f - ratio) * 100f).toInt()}%", Modifier.padding(horizontal = 7.dp, vertical = 2.dp), color = MaterialTheme.colorScheme.primary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("已用", color = Color(0xFF94A3B8), fontSize = 11.sp, modifier = Modifier.weight(1f))
                Text(if (total > 0L) refBytes(used) else "—", color = valueColor, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("总量", color = Color(0xFF94A3B8), fontSize = 11.sp, modifier = Modifier.weight(1f))
                Text(if (total > 0L) refBytes(total) else "${items.size} 个订阅", color = valueColor, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            }
        }
    }
}''',
    'subscription compact card',
)

replace_block(
    '@Composable\nprivate fun RefResourceCard',
    '\nprivate fun countryEmoji',
    '''@Composable
private fun RefResourceCard(memory: Long, cpuPercent: Float, modifier: Modifier) {
    val t = LocalHetuTokens.current
    val valueColor = if (MaterialTheme.colorScheme.background.luminance() < .5f) t.textPrimary else Color(0xFF0F172A)
    Surface(modifier = modifier.heightIn(min = 98.dp), shape = RoundedCornerShape(20.dp), color = t.cardBackground, shadowElevation = 1.dp) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Text("资源占用", color = Color(0xFF64748B), fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("内存", color = Color(0xFF94A3B8), fontSize = 11.sp, modifier = Modifier.weight(1f))
                Text(refBytes(memory), color = valueColor, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("CPU", color = Color(0xFF94A3B8), fontSize = 11.sp, modifier = Modifier.weight(1f))
                Text(String.format(java.util.Locale.US, "%.1f%%", cpuPercent), color = valueColor, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            }
        }
    }
}''',
    'resource compact card',
)

# WebUI / Logs are original-style flat text strips without oversized icon badges.
replace_block(
    '@Composable\nprivate fun RefSmallTool',
    '\n@Composable\nprivate fun RefSubscriptionCard',
    '''@Composable
private fun RefSmallTool(title: String, subtitle: String, icon: ImageVector, onClick: () -> Unit, modifier: Modifier) {
    val t = LocalHetuTokens.current
    val source = remember(title) { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) .98f else 1f, spring(dampingRatio = .78f, stiffness = 520f), label = "smallTool$title")
    val shape = RoundedCornerShape(18.dp)
    Surface(
        modifier = modifier
            .height(58.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale; alpha = if (pressed) .92f else 1f }
            .clip(shape)
            .clickable(interactionSource = source, indication = null, onClick = onClick),
        shape = shape,
        color = t.cardBackground,
        shadowElevation = 1.dp,
    ) {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 9.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(title, color = t.textPrimary, fontSize = 15.sp, lineHeight = 18.sp, fontWeight = FontWeight.Bold)
            Text(subtitle, color = Color(0xFF64748B), fontSize = 11.sp, lineHeight = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}''',
    'flat home quick tools',
)

# Proxies tab: delete the extra full-width strategy summary row.
one(
'''                RefPanelTab.Overview -> {
                    item {
                        Surface(shape = RoundedCornerShape(15.dp), color = t.cardBackground, shadowElevation = 0.dp) {
                            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(34.dp).background(t.selectionBackground, RoundedCornerShape(11.dp)), contentAlignment = Alignment.Center) {
                                    Icon(Icons.Rounded.Speed, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(19.dp))
                                }
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text("策略组", color = t.textPrimary, style = MaterialTheme.typography.titleMedium)
                                    Text("${filteredGroups.size} 个策略组 · 点击卡片选择节点", color = t.textSecondary, style = MaterialTheme.typography.labelSmall)
                                }
                                TextButton(onClick = ::refresh, enabled = !refreshing) { Text(if (refreshing) "测速中" else "全部测速") }
                            }
                        }
                    }
''',
'''                RefPanelTab.Overview -> {
''',
'remove proxies summary strip',
)

# Proxies card: true four-corner structure, 82dp high, large visual top-right and delay bottom-right.
replace_block(
    '@Composable\nprivate fun RefGroupCard',
    '\n@Composable\nprivate fun RefGroupVisualIcon',
    '''@Composable
private fun RefGroupCard(group: ProxyGroupUi, selected: String, expanded: Boolean, delay: Long?, modifier: Modifier, onClick: () -> Unit) {
    val t = LocalHetuTokens.current
    val scheme = MaterialTheme.colorScheme
    val source = remember(group.name) { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) .98f else 1f, spring(dampingRatio = .76f, stiffness = 560f), label = "group${group.name}")
    val shape = RoundedCornerShape(18.dp)
    val nodeName = selected.ifBlank { "未选择" }
    val nodeFlag = refNodeFlag(nodeName)
    Column(
        modifier
            .height(82.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale; alpha = if (pressed) .92f else 1f }
            .shadow(if (expanded) 3.dp else 1.dp, shape, clip = false)
            .background(if (expanded) t.selectionBackground else t.cardBackground, shape)
            .clip(shape)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 9.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f).padding(end = 6.dp), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(group.name, color = t.textPrimary, fontSize = 14.sp, lineHeight = 17.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${refGroupTypeCompact(group.type).uppercase()} 0/${group.nodes.size}", color = Color(0xFF94A3B8), fontSize = 10.sp, lineHeight = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1)
            }
            RefGroupCornerVisual(group, Modifier.size(28.dp))
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            Text(
                if (nodeFlag.isBlank()) nodeName else "$nodeFlag $nodeName",
                color = if (MaterialTheme.colorScheme.background.luminance() < .5f) t.textSecondary else Color(0xFF475569),
                fontSize = 11.sp,
                lineHeight = 14.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(end = 5.dp),
            )
            Surface(shape = RoundedCornerShape(999.dp), color = Color(0xFFE0F2FE)) {
                Text(
                    refDelay(delay),
                    Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                    color = Color(0xFF0284C7),
                    fontSize = 10.sp,
                    lineHeight = 13.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun RefGroupCornerVisual(group: ProxyGroupUi, modifier: Modifier) {
    val t = LocalHetuTokens.current
    val knownFlags = listOf("🇭🇰", "🇹🇼", "🇯🇵", "🇸🇬", "🇰🇷", "🇺🇸", "🇬🇧", "🇩🇪", "🇫🇷")
    val flag = knownFlags.firstOrNull { group.name.contains(it) } ?: refNodeFlag(group.name)
    if (flag.isNotBlank()) {
        Box(modifier.clip(CircleShape).background(t.controlBackground), contentAlignment = Alignment.Center) {
            Text(flag, fontSize = 18.sp, lineHeight = 21.sp)
        }
    } else {
        RefGroupVisualIcon(group, modifier)
    }
}''',
    'four corner proxy cards',
)

# Overview: compact speed cards, one-line total traffic strip, smooth area sparkline retained.
replace_block(
    '@Composable\nprivate fun RefTrafficOverview',
    '\n@Composable\nprivate fun RefRateCard',
    '''@Composable
private fun RefTrafficOverview(state: ProxyComposeState) {
    val t = LocalHetuTokens.current
    val scheme = MaterialTheme.colorScheme
    val history = remember { mutableStateListOf<Triple<Long, Long, Long>>() }
    var lastUpload by remember { mutableLongStateOf(state.uploadTotal) }
    var lastDownload by remember { mutableLongStateOf(state.downloadTotal) }
    var lastAt by remember { mutableLongStateOf(0L) }
    var upRate by remember { mutableLongStateOf(0L) }
    var downRate by remember { mutableLongStateOf(0L) }

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
                    if (points.size > 1) {
                        val maxRate = points.maxOf { maxOf(it.second, it.third) }.coerceAtLeast(1L).toFloat()
                        val end = points.last().first
                        val start = end - 60_000L
                        fun series(index: Int): List<Offset> = points.map { point ->
                            val x = (((point.first - start).coerceIn(0L, 60_000L)).toFloat() / 60_000f) * size.width
                            val value = if (index == 1) point.second else point.third
                            val y = size.height - (value.toFloat() / maxRate * size.height * .86f)
                            Offset(x, y)
                        }
                        fun smooth(coords: List<Offset>, closeArea: Boolean): Path {
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
                            if (closeArea) {
                                path.lineTo(coords.last().x, size.height)
                                path.lineTo(coords.first().x, size.height)
                                path.close()
                            }
                            return path
                        }
                        val upload = series(1)
                        val download = series(2)
                        drawPath(smooth(upload, true), brush = Brush.verticalGradient(listOf(t.success.copy(alpha = .16f), Color.Transparent), 0f, size.height))
                        drawPath(smooth(download, true), brush = Brush.verticalGradient(listOf(scheme.primary.copy(alpha = .20f), Color.Transparent), 0f, size.height))
                        drawPath(smooth(upload, false), color = t.success, style = Stroke(width = 2.25.dp.toPx()))
                        drawPath(smooth(download, false), color = scheme.primary, style = Stroke(width = 2.25.dp.toPx()))
                    }
                }
            }
        }
    }
}''',
    'overview screen',
)

replace_block(
    '@Composable\nprivate fun RefRateCard',
    '\n@Composable\nprivate fun RefProviderRow',
    '''@Composable
private fun RefRateCard(title: String, value: Long, icon: ImageVector, color: Color, modifier: Modifier) {
    val t = LocalHetuTokens.current
    Surface(modifier = modifier, shape = RoundedCornerShape(18.dp), color = t.cardBackground, shadowElevation = 1.dp) {
        Row(
            Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(34.dp).background(color.copy(alpha = .10f), CircleShape), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = color, modifier = Modifier.size(19.dp))
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(title, color = Color(0xFF94A3B8), fontSize = 11.sp, fontWeight = FontWeight.Medium)
                Text(refSpeed(value), color = t.textPrimary, fontSize = 19.sp, lineHeight = 23.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
            }
        }
    }
}''',
    'overview speed cards',
)

# Subscription cards: original three-column balance, highlighted remaining quota, sync inside percentage pill.
replace_block(
    '@Composable\nprivate fun RefProviderRow',
    '\nprivate fun refExpireDays',
    '''@Composable
private fun RefProviderRow(item: DashboardProviderUi, onRefresh: () -> Unit, onClick: () -> Unit) {
    val t = LocalHetuTokens.current
    val scheme = MaterialTheme.colorScheme
    val source = remember(item.name) { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) .98f else 1f, spring(dampingRatio = .78f, stiffness = 520f), label = "provider${item.name}")
    val shape = RoundedCornerShape(22.dp)
    val remainingPercent = if (item.hasSubscriptionInfo && item.total > 0L) ((1f - item.ratio.coerceIn(0f, 1f)) * 100f).toInt() else 0
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
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(refBytes(item.upload), color = t.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
                        Text("上传", color = Color(0xFF94A3B8), fontSize = 11.sp)
                    }
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(refBytes(item.download), color = t.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
                        Text("下载", color = Color(0xFF94A3B8), fontSize = 11.sp)
                    }
                    Surface(modifier = Modifier.weight(1f), shape = RoundedCornerShape(14.dp), color = Color(0xFFEDF4FF)) {
                        Column(Modifier.padding(horizontal = 7.dp, vertical = 7.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(refBytes(item.remaining), color = scheme.primary, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
                            Text("剩余", color = scheme.primary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
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
}''',
    'subscription cards',
)

# Rules: no array numbers and no outlined/pill REJECT treatment.
replace_block(
    '@Composable\nprivate fun RefRuleRow',
    '\n@Composable\nprivate fun RefRuleSetRow',
    '''@Composable
private fun RefRuleRow(item: ProxyRuleUi) {
    val t = LocalHetuTokens.current
    val scheme = MaterialTheme.colorScheme
    Surface(shape = RoundedCornerShape(18.dp), color = t.cardBackground, shadowElevation = 1.dp) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(item.type, color = t.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Text(item.payload.ifBlank { "—" }, color = t.textSecondary, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(10.dp))
            Text(
                item.proxy,
                color = if (item.proxy.equals("REJECT", true)) Color(0xFF334155) else scheme.primary,
                fontSize = 12.sp,
                fontWeight = FontWeight.ExtraBold,
                maxLines = 1,
            )
        }
    }
}''',
    'rules rows',
)

replace_block(
    '@Composable\nprivate fun RefRuleSetRow',
    '\n@Composable\nprivate fun RefTools',
    '''@Composable
private fun RefRuleSetRow(item: DashboardRuleSetUi, onRefresh: () -> Unit) {
    val t = LocalHetuTokens.current
    val scheme = MaterialTheme.colorScheme
    Surface(shape = RoundedCornerShape(20.dp), color = t.cardBackground, shadowElevation = 1.dp) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(item.name, color = t.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.width(7.dp))
                    Surface(shape = RoundedCornerShape(6.dp), color = Color(0xFFEBF3FF)) {
                        Text(
                            if (item.ruleCount > 0) "${item.ruleCount} 条规则" else "规则数 —",
                            Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                            color = scheme.primary,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                        )
                    }
                }
                Text(listOf(item.behavior, item.format, item.vehicleType).filter { it.isNotBlank() }.joinToString(" / "), color = Color(0xFF64748B), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(refUpdatedAt(item.updatedAt), color = Color(0xFF94A3B8), fontSize = 11.sp, maxLines = 1)
            }
            IconButton(onClick = onRefresh, modifier = Modifier.size(38.dp)) {
                Icon(Icons.Rounded.Download, "远端更新", tint = scheme.primary, modifier = Modifier.size(20.dp))
            }
        }
    }
}''',
    'rule set rows',
)

# Tools/settings: guarantee more than the requested 120dp clearance above floating dock.
replace_in_region(
    '@Composable\nprivate fun RefTools',
    '\n@Composable\nprivate fun RefSettings',
    'bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 132.dp,',
    'bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 148.dp,',
    'tools bottom clearance',
)
replace_in_region(
    '@Composable\nprivate fun RefSettings',
    '\n@OptIn(ExperimentalMaterial3Api::class)\n@Composable\nprivate fun RefChoiceBottomSheet',
    'bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 132.dp,',
    'bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 148.dp,',
    'settings bottom clearance',
)

# Invariants for this pass. Dock must not be modified by this script/workflow.
assert 'Color(0xFFF1F5F9)' in s
assert 'height(82.dp)' in s
assert 'RefGroupCornerVisual' in s
assert 'Icons.Rounded.Download' in s
assert 'Text("#${item.index}"' not in s
assert '${filteredGroups.size} 个策略组 · 点击卡片选择节点' not in s
assert 'AlertDialog(' not in s
assert 'RefInfoBottomSheet(\n            title = "端口细则"' in s

path.write_text(s)
print('test34 BoxProxy page parity polish applied')
