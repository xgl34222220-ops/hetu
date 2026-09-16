from pathlib import Path

ui_path = Path('android-app/app/src/main/java/io/github/xgl34222220/bichen/ReferenceProxyActivity.kt')
dock_path = Path('android-app/app/src/main/java/io/github/xgl34222220/bichen/ui/BichenGlassDock.kt')
s = ui_path.read_text()
dock = dock_path.read_text()


def one(old: str, new: str, label: str):
    global s
    if old not in s:
        raise SystemExit(f'missing exact block: {label}')
    s = s.replace(old, new, 1)


# The proxy dock must stay locked to LuoShu's current MIUIX liquid-glass geometry/physics.
for needle in (
    'RoundedCornerShape(31.dp)',
    'blurRadius = 30.dp',
    'noiseFactor = .018f',
    'refractionHeight = 17.dp.toPx()',
    'refractionAmount = 13.dp.toPx()',
    'chromaticAberration = .045f',
    '.padding(horizontal = 20.dp).padding(bottom = bottomInset + 12.dp)',
    '.height(72.dp + if (floating) 0.dp else bottomInset)',
    'val liquidExtra = if (liquidGlass) 13.dp * liquidStretch.value else 0.dp',
    'dampingRatio = if (liquidGlass) .68f else .84f',
    'stiffness = if (liquidGlass) 310f else Spring.StiffnessMediumLow',
    'pressed -> .92f',
    'active && liquidGlass -> 1.035f',
    '.size(22.dp)',
):
    if needle not in dock:
        raise SystemExit(f'BichenGlassDock drifted from LuoShu: {needle}')

# Hero: keep the 56dp status medallion but give it the soft depth used by the LuoShu language.
one(
'''                        Box(
                            Modifier.size(56.dp).background(if (state.running) scheme.primary else t.controlBackground, CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {''',
'''                        Box(
                            Modifier
                                .size(56.dp)
                                .shadow(if (state.running) 10.dp else 0.dp, CircleShape, clip = false)
                                .background(if (state.running) scheme.primary else t.controlBackground, CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {''',
'hero status medallion',
)

# Hero actions: no colored outlines; neutral actions use slate, stop/start uses semantic red.
one(
'''                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        RefActionText("重载", state.running && operation.isBlank(), onReload, Modifier.weight(1f), t.textPrimary)
                        RefActionText(if (state.running) "停止" else "启动", operation.isBlank(), onToggle, Modifier.weight(1f), t.danger)
                        RefActionText("重启", state.running && operation.isBlank(), onRestart, Modifier.weight(1f), t.textPrimary)
                    }''',
'''                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val neutralAction = if (scheme.background.luminance() < .5f) Color(0xFFE2E8F0) else Color(0xFF334155)
                        RefActionText("重载", state.running && operation.isBlank(), onReload, Modifier.weight(1f), neutralAction)
                        RefActionText(if (state.running) "停止" else "启动", operation.isBlank(), onToggle, Modifier.weight(1f), Color(0xFFEF4444))
                        RefActionText("重启", state.running && operation.isBlank(), onRestart, Modifier.weight(1f), neutralAction)
                    }''',
'hero action colors',
)

# Remove the debug-level PID plumbing completely from the resource card.
one('RefResourceCard(memory, cpuPercent, runtime.pid, Modifier.weight(1f))', 'RefResourceCard(memory, cpuPercent, Modifier.weight(1f))', 'resource card call')
one(
'private fun RefResourceCard(memory: Long, cpuPercent: Float, pid: Int, modifier: Modifier) {',
'private fun RefResourceCard(memory: Long, cpuPercent: Float, modifier: Modifier) {',
'resource card signature',
)

# Home latency values use the same Material 3 tonal badge system as strategy/node latency.
one(
'''@Composable
private fun RefLatencyColumn(label: String, value: Long?, testing: Boolean, modifier: Modifier) {
    val t = LocalBichenTokens.current
    val color = when {
        testing -> MaterialTheme.colorScheme.primary
        value == null -> t.textMuted
        value <= 0L -> t.danger
        value < 100L -> t.success
        value <= 300L -> t.warning
        else -> t.danger
    }
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(label, color = t.textSecondary, style = MaterialTheme.typography.labelSmall, maxLines = 1)
        Text(if (testing) "…" else refDelay(value), color = color, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}''',
'''@Composable
private fun RefLatencyColumn(label: String, value: Long?, testing: Boolean, modifier: Modifier) {
    val t = LocalBichenTokens.current
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(label, color = t.textSecondary, fontSize = 11.sp, lineHeight = 15.sp, maxLines = 1)
        RefDelayBadge(value = value, testing = testing, onClick = null)
    }
}''',
'home tonal latency badges',
)

# Resource/value hierarchy: 16sp semibold values + 12sp muted labels.
one(
'''@Composable
private fun RefMetric(title: String, value: String, modifier: Modifier) {
    val t = LocalBichenTokens.current
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(value, color = t.textPrimary, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(title, color = t.textSecondary, style = MaterialTheme.typography.labelSmall)
    }
}''',
'''@Composable
private fun RefMetric(title: String, value: String, modifier: Modifier) {
    val t = LocalBichenTokens.current
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(value, color = t.textPrimary, fontSize = 16.sp, lineHeight = 21.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(title, color = t.textSecondary, fontSize = 12.sp, lineHeight = 16.sp, maxLines = 1)
    }
}''',
'metric hierarchy',
)

# Strategy cards: three clean vertical layers and a readable landing-node row with country cue.
one(
'''    val scale by animateFloatAsState(if (pressed) .97f else 1f, spring(dampingRatio = .76f, stiffness = 560f), label = "group${group.name}")
    val shape = RoundedCornerShape(18.dp)
    Column(
        modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .heightIn(min = 106.dp)''',
'''    val scale by animateFloatAsState(if (pressed) .97f else 1f, spring(dampingRatio = .76f, stiffness = 560f), label = "group${group.name}")
    val shape = RoundedCornerShape(18.dp)
    val nodeName = selected.ifBlank { "未选择" }
    val nodeFlag = refNodeFlag(nodeName)
    Column(
        modifier
            .graphicsLayer { scaleX = scale; scaleY = scale; alpha = if (pressed) .90f else 1f }
            .heightIn(min = 106.dp)''',
'group press feedback',
)
one(
'''        Text("${group.type.uppercase(java.util.Locale.ROOT).ifBlank { refGroupType(group.type) }} · ${group.nodes.size} 节点", color = t.textMuted, fontSize = 11.sp, maxLines = 1)
        Text(selected.ifBlank { "未选择" }, color = t.textSecondary, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)''',
'''        Text("${refGroupTypeCompact(group.type)} · ${group.nodes.size} 节点", color = Color(0xFF94A3B8), fontSize = 11.sp, maxLines = 1)
        Text(if (nodeFlag.isBlank()) nodeName else "$nodeFlag $nodeName", color = t.textSecondary, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)''',
'group vertical metadata',
)

# Tonal badges keep their exact requested palette and gain the 0.90 pressed alpha.
one(
'''    val modifier = Modifier
        .graphicsLayer { scaleX = scale; scaleY = scale }
        .background(background, CircleShape)''',
'''    val modifier = Modifier
        .graphicsLayer { scaleX = scale; scaleY = scale; alpha = if (pressed) .90f else 1f }
        .background(background, CircleShape)''',
'tonal badge press alpha',
)

# Apply the same press deformation/opacity to key interactive cards and actions.
one(
'''.graphicsLayer { scaleX = scale; scaleY = scale }.clip(shape)
            .clickable(interactionSource = source, indication = null) { lanMode = !lanMode }''',
'''.graphicsLayer { scaleX = scale; scaleY = scale; alpha = if (pressed) .90f else 1f }.clip(shape)
            .clickable(interactionSource = source, indication = null) { lanMode = !lanMode }''',
'network card press alpha',
)
one(
'''            .graphicsLayer { scaleX = scale; scaleY = scale }
            .height(38.dp)''',
'''            .graphicsLayer { scaleX = scale; scaleY = scale; alpha = if (pressed) .90f else 1f }
            .height(38.dp)''',
'hero action press alpha',
)
one(
'''        modifier = Modifier.graphicsLayer { scaleX = scale; scaleY = scale }.clip(shape)
            .clickable(interactionSource = source, indication = null, onClick = onClick),''',
'''        modifier = Modifier.graphicsLayer { scaleX = scale; scaleY = scale; alpha = if (pressed) .90f else 1f }.clip(shape)
            .clickable(interactionSource = source, indication = null, onClick = onClick),''',
'provider row press alpha',
)
one(
'''                            .graphicsLayer { scaleX = pressScale; scaleY = pressScale }
                            .background(fill, shape)''',
'''                            .graphicsLayer { scaleX = pressScale; scaleY = pressScale; alpha = if (pressed) .90f else 1f }
                            .background(fill, shape)''',
'node sheet press alpha',
)
one(
'''                    Modifier.width(itemWidth).fillMaxHeight().graphicsLayer { scaleX = scale; scaleY = scale }
                        .clip(CircleShape)''',
'''                    Modifier.width(itemWidth).fillMaxHeight().graphicsLayer { scaleX = scale; scaleY = scale; alpha = if (pressed) .90f else 1f }
                        .clip(CircleShape)''',
'tab press alpha',
)

# Subscription timestamp: compact ISO -> MM-dd HH:mm is already handled by refUpdatedAt; pin its visual tone.
one(
'''            Text("到期 ${refExpireDate(item.expire)} · ${refUpdatedAt(item.updatedAt)}", color = t.textMuted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)''',
'''            Text("到期 ${refExpireDate(item.expire)} · ${refUpdatedAt(item.updatedAt)}", color = Color(0xFF94A3B8), fontSize = 11.sp, lineHeight = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)''',
'provider timestamp tone',
)

# Helpers for the compact strategy type and current landing flag.
one(
'''private fun refGroupType(type: String): String = when (type.lowercase()) {
    "urltest" -> "自动测速"
    "selector" -> "手动选择"
    "fallback" -> "故障转移"
    "loadbalance" -> "负载均衡"
    else -> type.ifBlank { "策略组" }
}
''',
'''private fun refGroupType(type: String): String = when (type.lowercase()) {
    "urltest" -> "自动测速"
    "selector" -> "手动选择"
    "fallback" -> "故障转移"
    "loadbalance" -> "负载均衡"
    else -> type.ifBlank { "策略组" }
}

private fun refGroupTypeCompact(type: String): String = when (type.lowercase()) {
    "urltest", "url-test" -> "URLTest"
    "selector" -> "手动选择"
    "fallback" -> "Fallback"
    "loadbalance", "load-balance" -> "LoadBalance"
    else -> type.ifBlank { "Group" }
}

private fun refNodeFlag(name: String): String {
    val value = name.trim()
    if (listOf("🇭🇰", "🇹🇼", "🇯🇵", "🇸🇬", "🇰🇷", "🇺🇸", "🇬🇧", "🇩🇪", "🇫🇷").any(value::contains)) return ""
    val upper = value.uppercase(java.util.Locale.ROOT)
    return when {
        value.contains("香港") || upper.contains("HONG KONG") || upper.startsWith("HK") -> "🇭🇰"
        value.contains("台湾") || value.contains("台灣") || upper.contains("TAIWAN") || upper.startsWith("TW") -> "🇹🇼"
        value.contains("日本") || upper.contains("JAPAN") || upper.startsWith("JP") -> "🇯🇵"
        value.contains("新加坡") || upper.contains("SINGAPORE") || upper.startsWith("SG") -> "🇸🇬"
        value.contains("韩国") || value.contains("韓國") || upper.contains("KOREA") || upper.startsWith("KR") -> "🇰🇷"
        value.contains("美国") || value.contains("美國") || upper.contains("UNITED STATES") || upper.startsWith("US") -> "🇺🇸"
        value.contains("英国") || value.contains("英國") || upper.contains("UNITED KINGDOM") || upper.startsWith("UK") -> "🇬🇧"
        value.contains("德国") || value.contains("德國") || upper.contains("GERMANY") || upper.startsWith("DE") -> "🇩🇪"
        value.contains("法国") || value.contains("法國") || upper.contains("FRANCE") || upper.startsWith("FR") -> "🇫🇷"
        else -> ""
    }
}
''',
'group helpers',
)

# Needed for the hero medallion depth.
if 'import androidx.compose.ui.draw.shadow\n' not in s:
    one('import androidx.compose.ui.draw.blur\n', 'import androidx.compose.ui.draw.blur\nimport androidx.compose.ui.draw.shadow\n', 'shadow import')

ui_path.write_text(s)
print('premium proxy visual polish applied; LuoShu dock parity verified')
