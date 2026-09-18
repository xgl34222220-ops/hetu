from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
BUILD = ROOT / "android-app/app/build.gradle.kts"
UI = ROOT / "android-app/app/src/main/java/io/github/xgl34222220/hetu/ReferenceProxyActivity.kt"
AD = ROOT / "android-app/app/src/main/java/io/github/xgl34222220/hetu/ProxyAdblockChainActivity.kt"
SUB = ROOT / "android-app/app/src/main/java/io/github/xgl34222220/hetu/ProxySubscriptionActivity.kt"
ADV = ROOT / "android-app/app/src/main/java/io/github/xgl34222220/hetu/ProxyAdvancedSettingsActivity.kt"


def require_replace(text: str, old: str, new: str, count: int = 1, label: str = "replacement") -> str:
    if old not in text:
        raise RuntimeError(f"Missing {label}: {old[:120]!r}")
    return text.replace(old, new, count)


def get_block(text: str, start_marker: str, end_marker: str) -> tuple[int, int, str]:
    start = text.index(start_marker)
    end = text.index(end_marker, start)
    return start, end, text[start:end]


def replace_block(text: str, start_marker: str, end_marker: str, new_block: str) -> str:
    start, end, _ = get_block(text, start_marker, end_marker)
    return text[:start] + new_block.rstrip() + "\n" + text[end:]


# Version ---------------------------------------------------------------------
build = BUILD.read_text()
build = require_replace(build, "versionCode = 449", "versionCode = 450", label="versionCode")
build = require_replace(build, 'versionName = "0.4.0-test.49"', 'versionName = "0.4.0-test.50"', label="versionName")
BUILD.write_text(build)

# Main proxy UI ---------------------------------------------------------------
ui = UI.read_text()

# 1) Strategy-group arrow: one glyph, physically rotating 180 degrees.
old_arrow = '''            Icon(
                if (expanded) Icons.Rounded.KeyboardArrowUp else Icons.Rounded.KeyboardArrowDown,
                if (expanded) "收起" else "展开",
                tint = if (expanded) Color(0xFF002FA7) else Color(0xFF94A3B8),
                modifier = Modifier.size(15.dp),
            )'''
new_arrow = '''            val arrowRotation by animateFloatAsState(
                targetValue = if (expanded) 180f else 0f,
                animationSpec = spring(dampingRatio = .76f, stiffness = 420f),
                label = "groupArrow${group.name}",
            )
            Icon(
                Icons.Rounded.KeyboardArrowDown,
                if (expanded) "收起" else "展开",
                tint = if (expanded) Color(0xFF002FA7) else Color(0xFF94A3B8),
                modifier = Modifier.size(15.dp).graphicsLayer { rotationZ = arrowRotation },
            )'''
ui = require_replace(ui, old_arrow, new_arrow, label="strategy arrow")

# 2) Strategy-group expansion: clearly recessed cool-gray well, never white-on-white.
old_well = '''    val shape = RoundedCornerShape(22.dp)
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
        ) {'''
new_well = '''    val shape = RoundedCornerShape(22.dp)
    val wellColor = if (dark) Color(0xFF18212E) else Color(0xFFEEF2F6)
    val wellBorder = if (dark) Color.White.copy(alpha = .08f) else Color(0xFFCBD5E1).copy(alpha = .62f)
    val wellBrush = if (dark) {
        Brush.verticalGradient(listOf(Color(0xFF141C27), wellColor, Color(0xFF202A37)))
    } else {
        Brush.verticalGradient(listOf(Color(0xFFE7EDF4), wellColor, Color(0xFFF2F5F8)))
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = shape,
        color = Color.Transparent,
        border = BorderStroke(.8.dp, wellBorder),
        shadowElevation = 0.dp,
        tonalElevation = 0.dp,
    ) {
        Column(
            Modifier.fillMaxWidth().background(wellBrush, shape).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp),
        ) {'''
ui = require_replace(ui, old_well, new_well, label="sunken well")
ui = require_replace(
    ui,
    'Modifier.fillMaxWidth().height(2.dp)\n                    .background(',
    'Modifier.fillMaxWidth().height(3.dp)\n                    .background(',
    label="well compression line",
)
ui = require_replace(
    ui,
    'Color(0xFF0F172A).copy(alpha = if (dark) .18f else .07f)',
    'Color(0xFF0F172A).copy(alpha = if (dark) .20f else .10f)',
    label="well inset alpha",
)

# Child-node cards: compact 62dp; selected card uses explicit ice-blue liner.
s, e, node_block = get_block(ui, "@Composable\nprivate fun RefInlineNodeCard(", "\n@Composable\nprivate fun RefGroupCornerVisual")
node_block = require_replace(node_block, "Box(modifier.height(64.dp))", "Box(modifier.height(62.dp))", label="node card height")
node_block = require_replace(
    node_block,
    'active -> Brush.verticalGradient(listOf(Color(0xFFF8FBFF), Color(0xFFEEF6FF)))',
    'active -> Brush.verticalGradient(listOf(Color(0xFFF7FBFF), Color(0xFFEFF6FF)))',
    label="active node liner",
)
ui = ui[:s] + node_block + ui[e:]

# 3) Home: remove orphan Tune icon from latency card and reserve numeric height.
ui = require_replace(
    ui,
    '                Icon(Icons.Rounded.Tune, null, tint = t.textSecondary, modifier = Modifier.size(17.dp))\n',
    '',
    label="home latency orphan tune",
)
ui = require_replace(
    ui,
    '            Modifier.graphicsLayer { this.alpha = alpha },\n            verticalAlignment = Alignment.CenterVertically,',
    '            Modifier.height(22.dp).graphicsLayer { this.alpha = alpha },\n            verticalAlignment = Alignment.CenterVertically,',
    label="latency numeric height",
)

# Network identity card.
s, e, block = get_block(ui, "@Composable\nprivate fun RefNetworkIdentityCard", "\n@Composable\nprivate fun RefSpeedCard")
block = require_replace(block, ".height(100.dp)", ".height(106.dp)", label="network card height")
block = require_replace(block, ".padding(horizontal = 14.dp, vertical = 12.dp)", ".padding(14.dp)", label="network card padding")
block = require_replace(
    block,
    '''                fontWeight = FontWeight.ExtraBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )''',
    '''                fontWeight = FontWeight.ExtraBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.height(20.dp),
            )''',
    label="network value slot",
)
ui = ui[:s] + block + ui[e:]

# Speed card.
s, e, block = get_block(ui, "@Composable\nprivate fun RefSpeedCard", "\n@Composable\nprivate fun RefSubscriptionCompact")
block = require_replace(block, ".height(100.dp)", ".height(106.dp)", label="speed card height")
block = require_replace(block, ".padding(horizontal = 14.dp, vertical = 12.dp)", ".padding(14.dp)", label="speed card padding")
block = block.replace("Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically)", "Row(Modifier.fillMaxWidth().height(20.dp), verticalAlignment = Alignment.CenterVertically)")
ui = ui[:s] + block + ui[e:]

# Subscription card.
s, e, block = get_block(ui, "@Composable\nprivate fun RefSubscriptionCompact", "\n@Composable\nprivate fun RefResourceCard")
block = require_replace(block, ".height(100.dp)", ".height(106.dp)", label="subscription card height")
block = require_replace(block, ".padding(horizontal = 14.dp, vertical = 12.dp)", ".padding(14.dp)", label="subscription card padding")
block = require_replace(
    block,
    '            Text(if (total > 0L) refBytes(used) else "—", color = valueColor, fontSize = 16.sp, lineHeight = 19.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)',
    '            Text(if (total > 0L) refBytes(used) else "—", color = valueColor, fontSize = 16.sp, lineHeight = 19.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, modifier = Modifier.height(20.dp))',
    label="subscription value slot",
)
block = require_replace(
    block,
    '            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {\n                Text("已用流量"',
    '            Row(Modifier.fillMaxWidth().height(18.dp), verticalAlignment = Alignment.CenterVertically) {\n                Text("已用流量"',
    label="subscription footer slot",
)
ui = ui[:s] + block + ui[e:]

# Resource card.
s, e, block = get_block(ui, "@Composable\nprivate fun RefResourceCard", "\nprivate fun countryEmoji")
block = require_replace(block, ".height(100.dp)", ".height(106.dp)", label="resource card height")
block = require_replace(block, ".padding(horizontal = 14.dp, vertical = 12.dp)", ".padding(14.dp)", label="resource card padding")
block = block.replace("Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically)", "Row(Modifier.fillMaxWidth().height(20.dp), verticalAlignment = Alignment.CenterVertically)")
ui = ui[:s] + block + ui[e:]

# 4) Overview: turn dead space into compact protocol + session instrumentation.
s, e, traffic_block = get_block(ui, "@Composable\nprivate fun RefTrafficOverview", "\n@Composable\nprivate fun RefRateCard")
insert_pos = traffic_block.rfind("\n    }\n}")
if insert_pos < 0:
    raise RuntimeError("Unable to locate RefTrafficOverview final Column")
overview_info = '''
        val tcpCount = state.connections.count { it.network.contains("tcp", ignoreCase = true) }
        val udpCount = state.connections.count { it.network.contains("udp", ignoreCase = true) }
        val protocolCount = tcpCount + udpCount
        val tcpPercent = if (protocolCount > 0) (tcpCount * 100 / protocolCount) else 0
        val udpPercent = if (protocolCount > 0) (udpCount * 100 / protocolCount) else 0
        val tcpRatio = if (protocolCount > 0) tcpCount.toFloat() / protocolCount.toFloat() else 0f
        val inboundCount = state.connections.count { it.inbound.isNotBlank() }
        val routedHits = state.connections.count { it.rule.isNotBlank() || it.chain.isNotBlank() }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Surface(
                modifier = Modifier.weight(1f).height(92.dp),
                shape = RoundedCornerShape(18.dp),
                color = t.cardBackground,
                shadowElevation = 1.dp,
            ) {
                Column(Modifier.fillMaxSize().padding(13.dp), verticalArrangement = Arrangement.SpaceBetween) {
                    Text("连接协议", color = Color(0xFF94A3B8), fontSize = 11.sp, fontWeight = FontWeight.Medium)
                    Text("TCP $tcpPercent%  ·  UDP $udpPercent%", color = t.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
                    Box(Modifier.fillMaxWidth().height(5.dp).background(Color(0xFFE2E8F0), CircleShape)) {
                        if (protocolCount > 0) {
                            Row(Modifier.fillMaxSize().clip(CircleShape)) {
                                if (tcpCount > 0) Box(Modifier.weight(tcpRatio.coerceAtLeast(.01f)).fillMaxHeight().background(Color(0xFF2563EB)))
                                if (udpCount > 0) Box(Modifier.weight((1f - tcpRatio).coerceAtLeast(.01f)).fillMaxHeight().background(Color(0xFF10B981)))
                            }
                        }
                    }
                }
            }
            Surface(
                modifier = Modifier.weight(1f).height(92.dp),
                shape = RoundedCornerShape(18.dp),
                color = t.cardBackground,
                shadowElevation = 1.dp,
            ) {
                Column(Modifier.fillMaxSize().padding(13.dp), verticalArrangement = Arrangement.SpaceBetween) {
                    Text("活动会话", color = Color(0xFF94A3B8), fontSize = 11.sp, fontWeight = FontWeight.Medium)
                    Text(state.connections.size.toString(), color = Color(0xFF002FA7), fontSize = 22.sp, lineHeight = 25.sp, fontWeight = FontWeight.Black)
                    Text("入站 $inboundCount  ·  分流命中 $routedHits", color = Color(0xFF64748B), fontSize = 10.sp, lineHeight = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                }
            }
        }
'''
traffic_block = traffic_block[:insert_pos] + overview_info + traffic_block[insert_pos:]
ui = ui[:s] + traffic_block + ui[e:]

# 5) Connections: gray micro-button by default, red only while pressed.
new_connection = '''@Composable
private fun RefConnectionRow(item: ProxyConnectionUi, onClose: (() -> Unit)?) {
    val t = LocalHetuTokens.current
    val dark = MaterialTheme.colorScheme.background.luminance() < .5f
    val closeSource = remember(item.id) { MutableInteractionSource() }
    val closePressed by closeSource.collectIsPressedAsState()
    val closeScale by animateFloatAsState(
        targetValue = if (closePressed) .88f else 1f,
        animationSpec = spring(dampingRatio = .70f, stiffness = 650f),
        label = "closeConnectionPress${item.id}",
    )
    Surface(shape = RoundedCornerShape(16.dp), color = t.cardBackground, shadowElevation = 0.dp) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(item.host, color = t.textPrimary, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(listOf(item.network, item.inbound).filter { it.isNotBlank() }.joinToString(" · "), color = t.textSecondary, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                Text(item.chain.ifBlank { item.rule.ifBlank { "DIRECT" } }, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("↑ ${refBytes(item.upload)}   ↓ ${refBytes(item.download)}", color = t.textMuted, style = MaterialTheme.typography.labelSmall)
            }
            if (onClose != null) {
                Spacer(Modifier.width(8.dp))
                Box(
                    Modifier.size(28.dp)
                        .graphicsLayer { scaleX = closeScale; scaleY = closeScale }
                        .background(if (dark) Color.White.copy(alpha = .07f) else Color(0xFFF1F5F9).copy(alpha = .82f), CircleShape)
                        .clip(CircleShape)
                        .clickable(interactionSource = closeSource, indication = null, onClick = onClose),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Rounded.Close,
                        "终止连接",
                        tint = if (closePressed) t.danger else Color(0xFF64748B),
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    }
}
'''
ui = replace_block(ui, "@Composable\nprivate fun RefConnectionRow", "\n@Composable\nprivate fun RefRuleGroupCard", new_connection)

# Rules: keep 18-per-card but make separators quieter and card shadow subtler.
s, e, rules_block = get_block(ui, "@Composable\nprivate fun RefRuleGroupCard", "\n@Composable\nprivate fun RefRuleSetRow")
rules_block = require_replace(rules_block, "shadowElevation = if (dark) 0.dp else 3.dp", "shadowElevation = if (dark) 0.dp else 1.dp", label="rule card shadow")
rules_block = require_replace(rules_block, "thickness = 1.dp", "thickness = .5.dp", label="rule divider thickness")
ui = ui[:s] + rules_block + ui[e:]

UI.write_text(ui)

# Ad-filter detail stats -------------------------------------------------------
ad = AD.read_text()
new_metric = '''@Composable
private fun ChainMetric(label: String, value: String, modifier: Modifier = Modifier) {
    val t = LocalHetuTokens.current
    val dark = MaterialTheme.colorScheme.background.luminance() < .5f
    val accent = when (label) {
        "规则源" -> Color(0xFF059669)
        else -> Color(0xFF002FA7)
    }
    Surface(
        modifier = modifier.height(64.dp),
        shape = RoundedCornerShape(16.dp),
        color = if (dark) t.controlBackground.copy(alpha = .72f) else Color(0xFFF8FAFC),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 11.dp, vertical = 9.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(value, color = accent, fontSize = 18.sp, lineHeight = 21.sp, fontWeight = FontWeight.Black, maxLines = 1)
            Text(label, color = t.textSecondary, fontSize = 10.sp, lineHeight = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1)
        }
    }
}
'''
ad = replace_block(ad, "@Composable\nprivate fun ChainMetric", "\n@Composable\nprivate fun ChainSectionLabel", new_metric)
AD.write_text(ad)

# YAML editor -----------------------------------------------------------------
sub = SUB.read_text()
sub = require_replace(sub, "import androidx.compose.foundation.layout.*\n", "import androidx.compose.foundation.layout.*\nimport androidx.compose.foundation.horizontalScroll\nimport androidx.compose.foundation.rememberScrollState\nimport androidx.compose.foundation.text.BasicTextField\nimport androidx.compose.foundation.verticalScroll\n", label="yaml foundation imports")
sub = require_replace(sub, "import androidx.compose.ui.graphics.luminance\n", "import androidx.compose.ui.graphics.luminance\nimport androidx.compose.ui.graphics.SolidColor\n", label="SolidColor import")
sub = require_replace(sub, "import androidx.compose.ui.text.style.TextOverflow\n", "import androidx.compose.ui.text.style.TextOverflow\nimport androidx.compose.ui.text.style.TextAlign\n", label="TextAlign import")

pattern = re.compile(r'''                    OutlinedTextField\(\n                        value = yamlText,.*?\n                    \)\n                    if \(yamlError\.isNotBlank\(\)\) \{''', re.S)
match = pattern.search(sub)
if not match:
    raise RuntimeError("Unable to find YAML OutlinedTextField")
new_editor = '''                    val editorScroll = rememberScrollState()
                    val editorHorizontalScroll = rememberScrollState()
                    val lineCount = remember(yamlText) { maxOf(1, yamlText.count { it == '\\n' } + 1) }
                    Surface(
                        modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 16.dp, vertical = 8.dp),
                        shape = editorShape,
                        color = if (dark) tokens.cardBackground else Color(0xFFF8FAFC),
                        border = BorderStroke(.7.dp, if (dark) tokens.outline.copy(alpha = .42f) else Color(0xFFE2E8F0)),
                        tonalElevation = 0.dp,
                    ) {
                        Row(
                            Modifier.fillMaxSize().verticalScroll(editorScroll),
                            verticalAlignment = Alignment.Top,
                        ) {
                            Text(
                                (1..lineCount).joinToString("\\n"),
                                modifier = Modifier.width(44.dp)
                                    .background(if (dark) Color.White.copy(alpha = .035f) else Color(0xFFF1F5F9))
                                    .padding(top = 12.dp, end = 9.dp, bottom = 12.dp),
                                color = if (dark) tokens.textMuted else Color(0xFF94A3B8),
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                lineHeight = 21.sp,
                                textAlign = TextAlign.End,
                            )
                            BasicTextField(
                                value = yamlText,
                                onValueChange = { yamlText = it; yamlError = "" },
                                modifier = Modifier.weight(1f)
                                    .horizontalScroll(editorHorizontalScroll)
                                    .padding(horizontal = 12.dp, vertical = 12.dp),
                                textStyle = MaterialTheme.typography.bodySmall.copy(
                                    color = tokens.textPrimary,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 13.sp,
                                    lineHeight = 21.sp,
                                ),
                                cursorBrush = SolidColor(scheme.primary),
                            )
                        }
                    }
                    if (yamlError.isNotBlank()) {'''
sub = sub[:match.start()] + new_editor + sub[match.end():]
SUB.write_text(sub)

# Advanced settings header ----------------------------------------------------
adv = ADV.read_text()
old_header = '''                Column(Modifier.weight(1f)) {
                    Text("高级代理配置", color = t.textPrimary, fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.ExtraBold)
                    Text("Root 数据面 · 修改后重启代理生效", color = t.textSecondary, fontSize = 11.sp)
                }'''
new_header = '''                Text(
                    "高级代理配置",
                    color = t.textPrimary,
                    fontSize = 22.sp,
                    lineHeight = 28.sp,
                    fontWeight = FontWeight.ExtraBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )'''
adv = require_replace(adv, old_header, new_header, label="advanced header")
old_first_group = '''        item { AdvancedSectionLabel("流量接管") }
        item {
            AdvancedGroup {
                AdvancedValueRow(Icons.Rounded.Memory, Color(0xFF334155), "运行核心", profile.core.label) {'''
new_first_group = '''        item { AdvancedSectionLabel("流量接管") }
        item {
            AdvancedGroup {
                Text(
                    "Root 数据面 · 修改后重启代理生效",
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                    color = t.textSecondary,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    fontWeight = FontWeight.Medium,
                )
                AdvancedDivider()
                AdvancedValueRow(Icons.Rounded.Memory, Color(0xFF334155), "运行核心", profile.core.label) {'''
adv = require_replace(adv, old_first_group, new_first_group, label="advanced first-card note")
ADV.write_text(adv)

print("Applied Hetu 0.4.0-test.50 commercial polish")
