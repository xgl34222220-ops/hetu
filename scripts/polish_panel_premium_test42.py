from pathlib import Path

p = Path('android-app/app/src/main/java/io/github/xgl34222220/bichen/ReferenceProxyActivity.kt')
s = p.read_text()


def replace_fun(start_marker: str, end_marker: str, new_block: str):
    global s
    a = s.index(start_marker)
    b = s.index(end_marker, a)
    s = s[:a] + new_block.rstrip() + '\n\n' + s[b:]

# 1) Remove the giant full-width header shell entirely. Keep title row + compact frosted tabs only.
replace_fun(
    '@OptIn(ExperimentalHazeMaterialsApi::class)\n@Composable\nprivate fun RefPanelGlassHeader(',
    '@Composable\nprivate fun RefPanelHeaderAction(',
'''@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
private fun RefPanelGlassHeader(
    selected: RefPanelTab,
    onSelect: (RefPanelTab) -> Unit,
    searchOpen: Boolean,
    query: String,
    onQueryChange: (String) -> Unit,
    onSearchToggle: () -> Unit,
    onOpenSettings: () -> Unit,
    hazeState: HazeState,
    backdrop: LayerBackdrop?,
) {
    val t = LocalBichenTokens.current
    val scheme = MaterialTheme.colorScheme
    val dark = scheme.background.luminance() < .5f

    // Keep the header structurally transparent. Previous full-width glass shells created
    // an oversized white slab on some OEM renderers. Only the compact controls carry glass.
    Column(
        Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().height(44.dp),
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
                onClick = onSearchToggle,
            )
            Spacer(Modifier.width(8.dp))
            RefPanelHeaderAction(
                icon = Icons.Rounded.Settings,
                contentDescription = "设置",
                onClick = onOpenSettings,
            )
        }
        RefPanelTabs(
            selected = selected,
            liquidGlass = true,
            onSelect = onSelect,
        )
        if (searchOpen) {
            TextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text("搜索策略组或节点") },
                leadingIcon = { Icon(Icons.Rounded.Search, null, Modifier.size(18.dp)) },
                shape = RoundedCornerShape(18.dp),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = if (dark) Color.White.copy(alpha = .07f) else Color.White.copy(alpha = .82f),
                    unfocusedContainerColor = if (dark) Color.White.copy(alpha = .05f) else Color.White.copy(alpha = .72f),
                    disabledContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
            )
        }
    }
}''')

# 2) Premium circular header actions: brighter inner highlight, soft shadow, no large backdrop layer.
start = s.index('@Composable\nprivate fun RefPanelHeaderAction(')
end = s.index('@Composable\nprivate fun RefPanelTabs(', start)
block = s[start:end]
block = block.replace(
'''    val bubbleBrush = Brush.radialGradient(
        colors = if (dark) {
            listOf(Color.White.copy(alpha = .13f), Color.White.copy(alpha = .055f))
        } else {
            listOf(Color.White.copy(alpha = .16f), Color.White.copy(alpha = .055f))
        },
        center = Offset(.28f, .12f),
    )''',
'''    val bubbleBrush = Brush.radialGradient(
        colors = if (dark) {
            listOf(Color.White.copy(alpha = .16f), Color.White.copy(alpha = .055f))
        } else {
            listOf(Color.White.copy(alpha = .88f), Color(0xFFF8FAFC).copy(alpha = .72f))
        },
        center = Offset(.24f, .10f),
    )''')
block = block.replace('.shadow(2.dp, shape, clip = false)', '.shadow(4.dp, shape, clip = false)')
block = block.replace('Color.White.copy(alpha = if (dark) .10f else .22f)', 'Color.White.copy(alpha = if (dark) .12f else .92f)')
s = s[:start] + block + s[end:]

# 3) Tabs: thin frosted track + white floating active capsule using the dock blue.
start = s.index('@Composable\nprivate fun RefPanelTabs(')
end = s.index('@Composable\nprivate fun RefPanelOverview(', start)
block = s[start:end]
old_track = '''    val trackBrush = Brush.verticalGradient(
        if (dark) {
            listOf(Color.White.copy(alpha = .075f), Color.White.copy(alpha = .028f))
        } else {
            listOf(Color.White.copy(alpha = .085f), Color.White.copy(alpha = .028f))
        },
    )
    val trackBorder = if (dark) Color.White.copy(alpha = .075f) else Color.White.copy(alpha = .15f)'''
new_track = '''    val trackBrush = Brush.verticalGradient(
        if (dark) {
            listOf(Color.White.copy(alpha = .085f), Color.White.copy(alpha = .035f))
        } else {
            listOf(Color(0xFFE2E8F0).copy(alpha = .58f), Color.White.copy(alpha = .46f))
        },
    )
    val trackBorder = if (dark) Color.White.copy(alpha = .08f) else Color.White.copy(alpha = .82f)'''
block = block.replace(old_track, new_track)
old_lens = '''        val indicatorTint = scheme.primary.copy(alpha = if (dark) .22f else .13f)
        val lensBrush = Brush.verticalGradient(
            listOf(
                Color.White.copy(alpha = if (dark) .07f else .16f),
                indicatorTint.copy(alpha = (indicatorTint.alpha * 1.08f).coerceAtMost(1f)),
                indicatorTint.copy(alpha = indicatorTint.alpha * .66f),
            ),
        )'''
new_lens = '''        val indicatorTint = Color(0xFF2563EB)
        val lensBrush = Brush.verticalGradient(
            if (dark) {
                listOf(Color.White.copy(alpha = .10f), indicatorTint.copy(alpha = .20f))
            } else {
                listOf(Color.White.copy(alpha = .98f), Color(0xFFF8FAFC).copy(alpha = .94f))
            },
        )'''
block = block.replace(old_lens, new_lens)
block = block.replace('Color.White.copy(alpha = if (dark) .12f else .24f)', 'Color.White.copy(alpha = if (dark) .14f else .96f)')
block = block.replace('color = if (active) scheme.primary else if (dark) t.textSecondary else Color(0xFF64748B)', 'color = if (active) Color(0xFF2563EB) else if (dark) t.textSecondary else Color(0xFF64748B)')
s = s[:start] + block + s[end:]

# 4) Main strategy cards: white->soft-white gradient, inner edge highlight, diffuse shadow, dock blue delay pill.
replace_fun(
    '@Composable\nprivate fun RefGroupCard(',
    '@Composable\nprivate fun RefGroupCornerVisual',
'''@Composable
private fun RefGroupCard(group: ProxyGroupUi, selected: String, expanded: Boolean, delay: Long?, modifier: Modifier, onClick: () -> Unit) {
    val t = LocalBichenTokens.current
    val scheme = MaterialTheme.colorScheme
    val dark = scheme.background.luminance() < .5f
    val source = remember(group.name) { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) .98f else 1f, spring(dampingRatio = .76f, stiffness = 560f), label = "group${group.name}")
    val shape = RoundedCornerShape(18.dp)
    val nodeName = selected.ifBlank { "未选择" }
    val nodeFlag = refNodeFlag(nodeName)
    val premiumBrush = if (dark) {
        Brush.verticalGradient(listOf(t.elevatedCardBackground, t.cardBackground))
    } else {
        Brush.verticalGradient(listOf(Color.White, Color(0xFFFAFBFD)))
    }
    Column(
        modifier
            .height(82.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale; alpha = if (pressed) .94f else 1f }
            .shadow(if (expanded) 5.dp else 3.dp, shape, clip = false)
            .background(premiumBrush, shape)
            .border(
                if (expanded) 1.4.dp else .8.dp,
                if (expanded) Color(0xFF2563EB).copy(alpha = .72f) else Color.White.copy(alpha = if (dark) .10f else .92f),
                shape,
            )
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
                color = if (dark) t.textSecondary else Color(0xFF475569),
                fontSize = 11.sp,
                lineHeight = 14.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(end = 5.dp),
            )
            Surface(shape = RoundedCornerShape(999.dp), color = Color(0xFFEFF6FF)) {
                Text(
                    refDelay(delay),
                    Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                    color = Color(0xFF2563EB),
                    fontSize = 10.sp,
                    lineHeight = 13.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                )
            }
        }
    }
}''')

# 5) Full-screen node cards: premium surface, no blue slab, 2dp selected outline + check badge, natural-width ms pill.
replace_fun(
    '@Composable\nprivate fun RefDetailNodeCard(',
    'private fun refNodeProtocol(',
'''@Composable
private fun RefDetailNodeCard(
    node: ProxyNodeUi,
    active: Boolean,
    delay: Long?,
    testing: Boolean,
    modifier: Modifier,
    onSelect: () -> Unit,
    onDelay: () -> Unit,
) {
    val t = LocalBichenTokens.current
    val scheme = MaterialTheme.colorScheme
    val dark = scheme.background.luminance() < .5f
    val source = remember(node.name) { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) .98f else 1f, label = "detailNode${node.name}")
    val shape = RoundedCornerShape(16.dp)
    val premiumBrush = if (dark) {
        Brush.verticalGradient(listOf(t.elevatedCardBackground, t.cardBackground))
    } else {
        Brush.verticalGradient(listOf(Color.White, Color(0xFFFAFBFD)))
    }
    Column(
        modifier.height(68.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale; alpha = if (pressed) .94f else 1f }
            .shadow(if (active) 5.dp else 3.dp, shape, clip = false)
            .background(premiumBrush, shape)
            .border(
                if (active) 1.8.dp else .8.dp,
                if (active) Color(0xFF2563EB) else Color.White.copy(alpha = if (dark) .10f else .92f),
                shape,
            )
            .clip(shape)
            .clickable(interactionSource = source, indication = null, onClick = onSelect)
            .padding(horizontal = 11.dp, vertical = 8.dp),
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
                color = t.textPrimary,
                fontSize = 13.sp,
                lineHeight = 16.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (active) {
                Box(
                    Modifier.size(16.dp).background(Color(0xFF2563EB), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Rounded.Check, "已选择", tint = Color.White, modifier = Modifier.size(11.dp))
                }
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = if (active) Color(0xFFEFF6FF) else Color(0xFFF1F5F9),
            ) {
                Text(
                    refNodeProtocol(node),
                    Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    color = if (active) Color(0xFF2563EB) else Color(0xFF64748B),
                    fontSize = 9.sp,
                    lineHeight = 12.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                )
            }
            Spacer(Modifier.weight(1f))
            val delayBg = when {
                testing -> Color(0xFFEFF6FF)
                delay == null -> Color(0xFFF1F5F9)
                delay <= 0L -> Color(0xFFFEF2F2)
                delay > 200L -> Color(0xFFFFF7ED)
                else -> Color(0xFFEFF6FF)
            }
            val delayColor = when {
                testing -> Color(0xFF2563EB)
                delay == null -> Color(0xFF64748B)
                delay <= 0L -> Color(0xFFDC2626)
                delay > 200L -> Color(0xFFD97706)
                else -> Color(0xFF2563EB)
            }
            Surface(
                shape = CircleShape,
                color = delayBg,
                modifier = Modifier.clickable(enabled = !testing, onClick = onDelay),
            ) {
                Box(
                    Modifier.height(24.dp).padding(horizontal = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    if (testing) CircularProgressIndicator(Modifier.size(13.dp), strokeWidth = 2.dp, color = delayColor)
                    else Text(refDelay(delay), color = delayColor, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
                }
            }
        }
    }
}''')

# 6) Unify shared delay badges with dock royal blue; only slow/error states diverge.
old = '''    val (background, textColor) = when {
        testing -> scheme.primary.copy(alpha = .12f) to scheme.primary
        value == null -> Color(0x1F94A3B8) to Color(0xFF64748B)
        value <= 0L || value > 300L -> Color(0x1FEF4444) to Color(0xFFDC2626)
        value < 100L -> Color(0x1F10B981) to Color(0xFF059669)
        else -> Color(0x1FF59E0B) to Color(0xFFD97706)
    }'''
new = '''    val (background, textColor) = when {
        testing -> Color(0xFFEFF6FF) to Color(0xFF2563EB)
        value == null -> Color(0xFFF1F5F9) to Color(0xFF64748B)
        value <= 0L -> Color(0xFFFEF2F2) to Color(0xFFDC2626)
        value > 200L -> Color(0xFFFFF7ED) to Color(0xFFD97706)
        else -> Color(0xFFEFF6FF) to Color(0xFF2563EB)
    }'''
if old not in s:
    raise SystemExit('delay badge palette block not found')
s = s.replace(old, new, 1)

p.write_text(s)

# Hard assertions for test42.
s = p.read_text()
a = s.index('private fun RefPanelGlassHeader(')
b = s.index('private fun RefPanelHeaderAction(', a)
header = s[a:b]
assert '.shadow(7.dp, shape' not in header
assert 'Modifier.background(shellBrush)' not in header
assert 'RefPanelTabs(' in header
assert 'Brush.verticalGradient(listOf(Color.White, Color(0xFFFAFBFD)))' in s
assert 'Color(0xFFEFF6FF)' in s
assert 'Color(0xFF2563EB)' in s
assert 'if (active) 1.8.dp else .8.dp' in s
assert 'Text(refDelay(delay)' in s
assert 'Modifier.size(26.dp).clip(CircleShape)' not in s[s.index('private fun RefDetailNodeCard('):s.index('private fun refNodeProtocol(')]
print('test42 premium panel polish applied')
