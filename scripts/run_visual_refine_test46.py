from pathlib import Path

p = Path('android-app/app/src/main/java/io/github/xgl34222220/hetu/ReferenceProxyActivity.kt')
s = p.read_text(encoding='utf-8')

if 'label = "test46GhostHeader"' in s:
    print('test46 already applied')
    raise SystemExit(0)

def replace_once(old: str, new: str, name: str) -> None:
    global s
    count = s.count(old)
    if count != 1:
        raise SystemExit(f'{name}: expected 1 match, got {count}')
    s = s.replace(old, new, 1)

# Home: remove redundant top-right search/settings bubbles and keep a clean title bar.
old = '''        item {
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
        }
'''
new = '''        item {
            Box(
                Modifier.fillMaxWidth().statusBarsPadding().padding(top = 16.dp, bottom = 10.dp).height(44.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                Text(
                    "BoxProxy",
                    color = if (scheme.background.luminance() < .5f) t.textPrimary else Color(0xFF0F172A),
                    fontSize = 26.sp,
                    lineHeight = 32.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = (-0.6).sp,
                )
            }
        }
'''
replace_once(old, new, 'home clean header')

# Home latency semantics: green <100, amber 100..300, rose >300; keep values visible while testing.
old = '''@Composable
private fun RefLatencyColumn(label: String, value: Long?, testing: Boolean, modifier: Modifier) {
    val t = LocalHetuTokens.current
    val scheme = MaterialTheme.colorScheme
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, color = Color(0xFF64748B), fontSize = 11.sp, lineHeight = 15.sp, maxLines = 1)
        Text(
            refDelay(value),
            color = if (value == null && !testing) Color(0xFF94A3B8) else scheme.primary,
            fontSize = 17.sp,
            lineHeight = 21.sp,
            fontWeight = FontWeight.ExtraBold,
            maxLines = 1,
        )
    }
}
'''
new = '''@Composable
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
        Text(
            refDelay(value),
            color = valueColor,
            fontSize = 17.sp,
            lineHeight = 21.sp,
            fontWeight = FontWeight.ExtraBold,
            maxLines = 1,
            style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"),
            modifier = Modifier.graphicsLayer { this.alpha = alpha },
        )
    }
}
'''
replace_once(old, new, 'home latency semantics')

# Runtime badge: same-color blue glow instead of a generic hard shadow.
replace_once(
    '''                                .shadow(if (state.running) 10.dp else 0.dp, CircleShape, clip = false)''',
    '''                                .shadow(
                                    if (state.running) 12.dp else 0.dp,
                                    CircleShape,
                                    clip = false,
                                    ambientColor = scheme.primary.copy(alpha = .26f),
                                    spotColor = scheme.primary.copy(alpha = .38f),
                                )''',
    'runtime glow',
)

# Panel title: slightly tighter hierarchy and a More glyph rather than a redundant settings glyph.
replace_once(
    '''                fontSize = 26.sp,
                lineHeight = 32.sp,
                fontWeight = FontWeight.Black,''',
    '''                fontSize = 24.sp,
                lineHeight = 30.sp,
                fontWeight = FontWeight.ExtraBold,''',
    'panel title scale',
)
replace_once(
    '''            RefPanelHeaderAction(
                icon = Icons.Rounded.Settings,
                contentDescription = "设置",
                onClick = onOpenSettings,
            )''',
    '''            RefPanelHeaderAction(
                icon = Icons.Rounded.MoreHoriz,
                contentDescription = "更多设置",
                onClick = onOpenSettings,
            )''',
    'panel more icon',
)

# Panel search drops down smoothly instead of popping into the layout.
old = '''        if (searchOpen) {
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
'''
new = '''        androidx.compose.animation.AnimatedVisibility(
            visible = searchOpen,
            enter = androidx.compose.animation.expandVertically(
                expandFrom = Alignment.Top,
                animationSpec = androidx.compose.animation.core.tween(240),
            ) + androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(180)) +
                androidx.compose.animation.slideInVertically(androidx.compose.animation.core.tween(220)) { -it / 4 },
            exit = androidx.compose.animation.shrinkVertically(
                shrinkTowards = Alignment.Top,
                animationSpec = androidx.compose.animation.core.tween(190),
            ) + androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(140)),
        ) {
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
'''
replace_once(old, new, 'animated panel search')

# Panel header action: true ghost icon. No resting fill, border or shadow; only press-state ripple/scale.
start = s.index('@Composable\nprivate fun RefPanelHeaderAction(')
end = s.index('\n@Composable\nprivate fun RefPanelTabs(', start)
new_func = '''@Composable
private fun RefPanelHeaderAction(
    icon: ImageVector,
    contentDescription: String,
    active: Boolean = false,
    onClick: () -> Unit,
) {
    val t = LocalHetuTokens.current
    val scheme = MaterialTheme.colorScheme
    val dark = scheme.background.luminance() < .5f
    val view = LocalView.current
    val source = remember(contentDescription) { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(
        if (pressed) .90f else 1f,
        spring(dampingRatio = .68f, stiffness = 620f),
        label = "test46GhostHeader",
    )
    val pressFill = if (dark) Color.White.copy(alpha = .08f) else Color(0xFFE2E8F0).copy(alpha = .60f)
    Box(
        Modifier.size(36.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale; alpha = if (pressed) .84f else 1f }
            .background(if (pressed) pressFill else Color.Transparent, CircleShape)
            .clip(CircleShape)
            .clickable(interactionSource = source, indication = null) {
                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                onClick()
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription,
            tint = if (active) scheme.primary else if (dark) t.textSecondary else Color(0xFF64748B),
            modifier = Modifier.size(20.dp),
        )
    }
}
'''
s = s[:start] + new_func + s[end:]

# Stronger 4% spring press on interactive cards that still used 2%.
for old_line, new_line, name in [
    ('val scale by animateFloatAsState(if (pressed) .98f else 1f, spring(dampingRatio = .80f, stiffness = 520f), label = "networkCardPress")',
     'val scale by animateFloatAsState(if (pressed) .96f else 1f, spring(dampingRatio = .74f, stiffness = 560f), label = "networkCardPress")', 'network card press'),
    ('val scale by animateFloatAsState(if (pressed) .98f else 1f, spring(dampingRatio = .78f, stiffness = 560f), label = "subscriptionCompactPress")',
     'val scale by animateFloatAsState(if (pressed) .96f else 1f, spring(dampingRatio = .74f, stiffness = 560f), label = "subscriptionCompactPress")', 'subscription press'),
    ('val scale by animateFloatAsState(if (pressed) .98f else 1f, spring(dampingRatio = .78f, stiffness = 520f), label = "smallTool$title")',
     'val scale by animateFloatAsState(if (pressed) .96f else 1f, spring(dampingRatio = .74f, stiffness = 560f), label = "smallTool$title")', 'small tool press'),
    ('val scale by animateFloatAsState(if (pressed) .98f else 1f, label = "detailNode${node.name}")',
     'val scale by animateFloatAsState(if (pressed) .96f else 1f, spring(dampingRatio = .72f, stiffness = 580f), label = "detailNode${node.name}")', 'detail node press'),
    ('val scale by animateFloatAsState(if (pressed) .98f else 1f, spring(dampingRatio = .76f, stiffness = 560f), label = "group${group.name}")',
     'val scale by animateFloatAsState(if (pressed) .96f else 1f, spring(dampingRatio = .72f, stiffness = 580f), label = "group${group.name}")', 'group card press'),
]:
    replace_once(old_line, new_line, name)

# Use the shared commercial latency pill on detail node cards as well.
old = '''            val delayBg = when {
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
'''
replace_once(old, '            RefDelayBadge(delay, testing, onDelay)\n', 'detail shared latency pill')

# Active detail check gets a pop animation while keeping a fixed slot to prevent layout shift.
old = '''            if (active) {
                Box(
                    Modifier.size(16.dp).background(Color(0xFF2563EB), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Rounded.Check, "已选择", tint = Color.White, modifier = Modifier.size(11.dp))
                }
            }
'''
new = '''            Box(Modifier.size(16.dp), contentAlignment = Alignment.Center) {
                androidx.compose.animation.AnimatedVisibility(
                    visible = active,
                    enter = androidx.compose.animation.scaleIn(
                        initialScale = .15f,
                        animationSpec = spring(dampingRatio = .56f, stiffness = 520f),
                    ) + androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(140)),
                    exit = androidx.compose.animation.scaleOut(targetScale = .45f) + androidx.compose.animation.fadeOut(),
                ) {
                    Box(
                        Modifier.size(16.dp).background(Color(0xFF2563EB), CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Rounded.Check, "已选择", tint = Color.White, modifier = Modifier.size(11.dp))
                    }
                }
            }
'''
replace_once(old, new, 'detail check pop')

# Accordion tray: recessed slate tray with a subtle top inset gradient and 1dp outline.
old = '''    val shape = RoundedCornerShape(20.dp)
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = shape,
        color = if (dark) t.elevatedCardBackground else Color(0xFFF1F5F9),
        shadowElevation = 0.dp,
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
'''
new = '''    val shape = RoundedCornerShape(20.dp)
    val trayBrush = if (dark) {
        Brush.verticalGradient(listOf(Color.White.copy(alpha = .055f), t.elevatedCardBackground, t.elevatedCardBackground))
    } else {
        Brush.verticalGradient(listOf(Color(0xFFEFF4F8), Color(0xFFF8FAFC), Color(0xFFF8FAFC)))
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = shape,
        color = Color.Transparent,
        border = BorderStroke(1.dp, if (dark) t.outline.copy(alpha = .44f) else Color(0xFFE2E8F0)),
        shadowElevation = 0.dp,
    ) {
        Column(
            Modifier.fillMaxWidth().background(trayBrush, shape).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp),
        ) {
'''
replace_once(old, new, 'accordion recessed tray')

# Inline node cards reserve a check slot and spring-pop the selected mark.
old = '''                Text(
                    node.name,
                    color = if (active) Color(0xFF1E3A8A) else t.textPrimary,
                    fontSize = 11.sp,
                    fontWeight = if (active) FontWeight.Bold else FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(end = 4.dp),
                )
                RefDelayBadge(delay, testing, onDelay)
'''
new = '''                Text(
                    node.name,
                    color = if (active) Color(0xFF1E3A8A) else t.textPrimary,
                    fontSize = 11.sp,
                    fontWeight = if (active) FontWeight.Bold else FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(end = 4.dp),
                )
                Box(Modifier.size(16.dp), contentAlignment = Alignment.Center) {
                    androidx.compose.animation.AnimatedVisibility(
                        visible = active,
                        enter = androidx.compose.animation.scaleIn(
                            initialScale = .05f,
                            animationSpec = spring(dampingRatio = .52f, stiffness = 500f),
                        ) + androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(130)),
                        exit = androidx.compose.animation.scaleOut(targetScale = .45f) + androidx.compose.animation.fadeOut(),
                    ) {
                        Box(
                            Modifier.size(15.dp).background(Color(0xFF2563EB), CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(Icons.Rounded.Check, "已选择", tint = Color.White, modifier = Modifier.size(10.dp))
                        }
                    }
                }
                Spacer(Modifier.width(4.dp))
                RefDelayBadge(delay, testing, onDelay)
'''
replace_once(old, new, 'inline check pop')

# Latency pill: fixed 62dp width, tabular figures, old value retained, micro spinner overlay during test.
start = s.index('@Composable\nprivate fun RefDelayBadge(')
end = s.index('\n@Composable\nprivate fun RefTrafficOverview(', start)
new_func = '''@Composable
private fun RefDelayBadge(value: Long?, testing: Boolean, onClick: (() -> Unit)?) {
    val text = refDelay(value)
    val (background, textColor) = when {
        value == null -> Color(0xFFF1F5F9) to Color(0xFF64748B)
        value <= 0L -> Color(0xFFFFF1F2) to Color(0xFFF43F5E)
        value < 100L -> Color(0xFFECFDF5) to Color(0xFF10B981)
        value <= 300L -> Color(0xFFFFFBEB) to Color(0xFFF59E0B)
        else -> Color(0xFFFFF1F2) to Color(0xFFF43F5E)
    }
    val source = remember(onClick) { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val infinite = androidx.compose.animation.core.rememberInfiniteTransition(label = "latencyPulse")
    val pulse by infinite.animateFloat(
        initialValue = .68f,
        targetValue = 1f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
            animation = androidx.compose.animation.core.tween(620),
            repeatMode = androidx.compose.animation.core.RepeatMode.Reverse,
        ),
        label = "latencyPulseAlpha",
    )
    var revealTarget by remember { mutableFloatStateOf(1f) }
    LaunchedEffect(text) {
        revealTarget = .95f
        delay(28)
        revealTarget = 1f
    }
    val reveal by animateFloatAsState(revealTarget, spring(dampingRatio = .58f, stiffness = 520f), label = "latencyReveal")
    val pressScale by animateFloatAsState(if (pressed) .96f else 1f, spring(dampingRatio = .70f, stiffness = 620f), label = "latencyPress")
    Box(
        Modifier.width(62.dp).height(22.dp)
            .graphicsLayer {
                scaleX = reveal * pressScale
                scaleY = reveal * pressScale
                this.alpha = if (pressed) .85f else if (testing) .78f + .22f * pulse else 1f
            }
            .background(if (testing) background.copy(alpha = .78f) else background, CircleShape)
            .border(.7.dp, if (testing) textColor.copy(alpha = .20f + .24f * pulse) else Color.Transparent, CircleShape)
            .then(if (onClick != null) Modifier.clickable(enabled = !testing, interactionSource = source, indication = null, onClick = onClick) else Modifier),
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
        if (testing) {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.CenterEnd).padding(end = 5.dp).size(9.dp),
                strokeWidth = 1.25.dp,
                color = textColor,
                trackColor = textColor.copy(alpha = .14f),
            )
        }
    }
}
'''
s = s[:start] + new_func + s[end:]

# Runtime log sheet: professional dark terminal while preserving other info sheets.
start = s.index('@OptIn(ExperimentalMaterial3Api::class)\n@Composable\nprivate fun RefInfoBottomSheet(')
end = s.index('\n@Composable\nprivate fun RefSheetDragHandle()', start)
new_func = '''@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RefInfoBottomSheet(
    title: String,
    text: String,
    actionLabel: String,
    onDismiss: () -> Unit,
) {
    val t = LocalHetuTokens.current
    val terminal = title.contains("日志")
    val renderedText = remember(text, terminal) {
        if (!terminal) {
            androidx.compose.ui.text.AnnotatedString(text)
        } else {
            androidx.compose.ui.text.buildAnnotatedString {
                val lines = text.lines()
                lines.forEachIndexed { index, line ->
                    val lower = line.lowercase(java.util.Locale.ROOT)
                    val color = when {
                        "warning" in lower || "warn" in lower -> Color(0xFFFBBF24)
                        "error" in lower || "fatal" in lower -> Color(0xFFFB7185)
                        "direct" in lower -> Color(0xFF34D399)
                        "[tcp]" in lower || " tcp " in lower -> Color(0xFF22D3EE)
                        "[udp]" in lower || " udp " in lower -> Color(0xFFA78BFA)
                        else -> Color(0xFFCBD5E1)
                    }
                    pushStyle(androidx.compose.ui.text.SpanStyle(color = color))
                    append(line)
                    pop()
                    if (index != lines.lastIndex) append('\\n')
                }
            }
        }
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp),
        containerColor = t.elevatedCardBackground,
        contentColor = t.textPrimary,
        tonalElevation = 0.dp,
        scrimColor = Color.Black.copy(alpha = .35f),
        dragHandle = { RefSheetDragHandle() },
    ) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 18.dp, end = 18.dp, bottom = 18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(title, color = t.textPrimary, fontSize = 20.sp, lineHeight = 25.sp, fontWeight = FontWeight.Bold)
            Box(
                Modifier.fillMaxWidth()
                    .heightIn(max = 430.dp)
                    .background(if (terminal) Color(0xFF0F172A) else t.controlBackground.copy(alpha = .54f), RoundedCornerShape(16.dp))
                    .border(if (terminal) .8.dp else 0.dp, if (terminal) Color(0xFF334155) else Color.Transparent, RoundedCornerShape(16.dp))
                    .padding(14.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                if (terminal) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Box(Modifier.size(7.dp).background(Color(0xFFFB7185), CircleShape))
                            Box(Modifier.size(7.dp).background(Color(0xFFFBBF24), CircleShape))
                            Box(Modifier.size(7.dp).background(Color(0xFF34D399), CircleShape))
                            Spacer(Modifier.width(3.dp))
                            Text("runtime.log", color = Color(0xFF64748B), fontSize = 10.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                        }
                        Text(
                            renderedText,
                            fontSize = 12.sp,
                            lineHeight = 18.sp,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        )
                    }
                } else {
                    Text(renderedText, color = t.textSecondary, fontSize = 13.sp, lineHeight = 20.sp)
                }
            }
            FilledTonalButton(
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
        }
    }
}
'''
s = s[:start] + new_func + s[end:]

# Technical values in the port/config sheet read as real console data.
replace_once(
    '''        Text(value, color = valueColor, fontSize = 12.sp, fontWeight = FontWeight.Bold)''',
    '''        Text(
            value,
            color = valueColor,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
        )''',
    'monospace port values',
)

p.write_text(s, encoding='utf-8')
print('Applied test46 visual/native polish')
