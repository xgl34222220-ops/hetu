from pathlib import Path

ROOT = Path('android-app/app/src/main/java/io/github/xgl34222220/bichen')


def require_replace(text: str, old: str, new: str, label: str) -> str:
    if old not in text:
        raise RuntimeError(f'Missing {label}: {old[:160]!r}')
    return text.replace(old, new, 1)


def replace_region(text: str, start_marker: str, end_marker: str, replacement: str, label: str) -> str:
    start = text.find(start_marker)
    if start < 0:
        raise RuntimeError(f'Missing {label} start: {start_marker!r}')
    end = text.find(end_marker, start + len(start_marker))
    if end < 0:
        raise RuntimeError(f'Missing {label} end: {end_marker!r}')
    return text[:start] + replacement.rstrip() + '\n\n' + text[end:]


# -----------------------------------------------------------------------------
# Version
# -----------------------------------------------------------------------------
build_path = Path('android-app/app/build.gradle.kts')
build = build_path.read_text()
build = require_replace(build, 'versionCode = 451', 'versionCode = 452', 'versionCode')
build = require_replace(build, 'versionName = "0.4.0-test.51"', 'versionName = "0.4.0-test.52"', 'versionName')
build_path.write_text(build)


# -----------------------------------------------------------------------------
# Main proxy UI: physical LAN/WAN flip, tab isolation, stagger and grouped rules
# -----------------------------------------------------------------------------
ref_path = ROOT / 'ReferenceProxyActivity.kt'
ref = ref_path.read_text()

# Stronger duration pill hierarchy.
ref = require_replace(
    ref,
    '''                                    color = if (scheme.background.luminance() < .5f) Color.White.copy(alpha = .08f) else Color.White.copy(alpha = .82f),
                                    border = BorderStroke(.6.dp, if (scheme.background.luminance() < .5f) Color.White.copy(alpha = .08f) else Color(0xFFE2E8F0).copy(alpha = .78f)),
                                    tonalElevation = 0.dp,''',
    '''                                    color = if (scheme.background.luminance() < .5f) Color.White.copy(alpha = .10f) else Color.White.copy(alpha = .92f),
                                    border = BorderStroke(.6.dp, if (scheme.background.luminance() < .5f) Color.White.copy(alpha = .10f) else Color(0xFFE2E8F0).copy(alpha = .72f)),
                                    shadowElevation = 1.dp,
                                    tonalElevation = 0.dp,''',
    'hero duration surface',
)
ref = require_replace(
    ref,
    '''                                        Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                                        color = if (scheme.background.luminance() < .5f) Color(0xFFCBD5E1) else Color(0xFF64748B),
                                        fontSize = 11.sp,
                                        lineHeight = 14.sp,
                                        fontWeight = FontWeight.SemiBold,''',
    '''                                        Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                        color = if (scheme.background.luminance() < .5f) Color(0xFFE2E8F0) else Color(0xFF475569),
                                        fontSize = 12.sp,
                                        lineHeight = 15.sp,
                                        fontWeight = FontWeight.Bold,''',
    'hero duration text',
)

network_card = r'''@Composable
private fun RefNetworkIdentityCard(runtime: ProxyRuntimeSnapshot, connections: Int, modifier: Modifier) {
    val t = LocalBichenTokens.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    var showLan by rememberSaveable { mutableStateOf(true) }
    var flipping by remember { mutableStateOf(false) }
    val flipRotation = remember { Animatable(0f) }
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) .97f else 1f, spring(dampingRatio = .74f, stiffness = 560f), label = "networkCardPress")
    val shape = RoundedCornerShape(20.dp)
    val valueColor = if (MaterialTheme.colorScheme.background.luminance() < .5f) t.textPrimary else Color(0xFF0F172A)

    fun flipCard() {
        if (flipping) return
        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        scope.launch {
            flipping = true
            try {
                flipRotation.animateTo(
                    89.5f,
                    animationSpec = androidx.compose.animation.core.tween(
                        durationMillis = 115,
                        easing = androidx.compose.animation.core.FastOutSlowInEasing,
                    ),
                )
                showLan = !showLan
                flipRotation.snapTo(-89.5f)
                flipRotation.animateTo(
                    0f,
                    animationSpec = androidx.compose.animation.core.tween(
                        durationMillis = 155,
                        easing = androidx.compose.animation.core.FastOutSlowInEasing,
                    ),
                )
            } finally {
                flipRotation.snapTo(0f)
                flipping = false
            }
        }
    }

    Surface(
        modifier = modifier
            .height(112.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale; alpha = if (pressed) .94f else 1f }
            .clip(shape)
            .clickable(enabled = !flipping, interactionSource = source, indication = null, onClick = ::flipCard),
        shape = shape,
        color = t.cardBackground,
        shadowElevation = 1.dp,
    ) {
        Column(
            Modifier.fillMaxSize()
                .graphicsLayer {
                    rotationY = flipRotation.value
                    val edge = kotlin.math.abs(flipRotation.value) / 90f
                    alpha = 1f - edge * .10f
                    scaleX = 1f - edge * .025f
                }
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(Modifier.fillMaxWidth().height(24.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (showLan) "LAN" else "WAN", color = Color(0xFF64748B), fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Box(
                    Modifier.size(24.dp)
                        .background(if (MaterialTheme.colorScheme.background.luminance() < .5f) Color.White.copy(alpha = .07f) else Color(0xFFF1F5F9), CircleShape)
                        .border(.6.dp, if (MaterialTheme.colorScheme.background.luminance() < .5f) Color.White.copy(alpha = .08f) else Color.White.copy(alpha = .90f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Rounded.SwapHoriz, "切换 LAN/WAN", tint = Color(0xFF2563EB), modifier = Modifier.size(14.dp))
                }
            }
            Text(
                if (showLan) runtime.lanAddress else runtime.wanAddress,
                color = valueColor,
                fontSize = 15.sp,
                lineHeight = 18.sp,
                fontWeight = FontWeight.ExtraBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.height(20.dp),
            )
            Text(
                if (showLan) "${runtime.lanInterface} · $connections 连接" else "${countryEmoji(runtime.wanCountryCode)} ${runtime.wanRegion}",
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
}'''
ref = replace_region(
    ref,
    '@Composable\nprivate fun RefNetworkIdentityCard',
    '@Composable\nprivate fun RefSpeedCard',
    network_card,
    'network identity card',
)

# Make chevron animation deterministic and visible in recordings.
ref = require_replace(
    ref,
    '''                animationSpec = spring(dampingRatio = .58f, stiffness = 250f),
                label = "groupArrow${group.name}",''',
    '''                animationSpec = androidx.compose.animation.core.tween(
                    durationMillis = 280,
                    easing = androidx.compose.animation.core.FastOutSlowInEasing,
                ),
                label = "groupArrow${group.name}",''',
    'group arrow tween',
)

# 15ms cascade instead of all-at-once feeling.
ref = require_replace(
    ref,
    'delay((index * 22L).coerceAtMost(260L))',
    'delay((index * 15L).coerceAtMost(180L))',
    'node stagger delay',
)

# Recreate the whole list tree per tab and fade it in. This prevents Lazy slot reuse
# from showing one frame of the previous tab on top of the new tab.
ref = require_replace(
    ref,
    '    val tab = selectedTab\n    var refreshing by remember { mutableStateOf(false) }',
    '    val tab = selectedTab\n    val tabFade = remember { Animatable(1f) }\n    var refreshing by remember { mutableStateOf(false) }',
    'panel tab fade state',
)
ref = require_replace(
    ref,
    '    LaunchedEffect(tab, state.running) { loadTab() }\n    LaunchedEffect(searchRequest)',
    '''    LaunchedEffect(tab, state.running) { loadTab() }
    LaunchedEffect(tab) {
        tabFade.snapTo(0f)
        tabFade.animateTo(
            1f,
            animationSpec = androidx.compose.animation.core.tween(
                durationMillis = 110,
                easing = androidx.compose.animation.core.FastOutSlowInEasing,
            ),
        )
    }
    LaunchedEffect(searchRequest)''',
    'panel tab fade effect',
)
ref = require_replace(
    ref,
    '        PullToRefreshBox(isRefreshing = refreshing, onRefresh = ::refresh, modifier = Modifier.fillMaxSize()) {',
    '''        key(tab) {
            PullToRefreshBox(
                isRefreshing = refreshing,
                onRefresh = ::refresh,
                modifier = Modifier.fillMaxSize().graphicsLayer { alpha = tabFade.value },
            ) {''',
    'panel keyed pull-to-refresh',
)
ref = require_replace(
    ref,
    '''        androidx.compose.animation.AnimatedVisibility(
            visible = capsuleText.isNotBlank(),''',
    '''        }
        androidx.compose.animation.AnimatedVisibility(
            visible = capsuleText.isNotBlank(),''',
    'panel keyed scope close',
)

# Group rules into slightly shorter blocks and reduce the horizontal blind effect.
ref = ref.replace('rules.chunked(18)', 'rules.chunked(15)')
rule_start = ref.index('@Composable\nprivate fun RefRuleGroupCard')
rule_end = ref.index('@Composable\nprivate fun RefRuleSetRow', rule_start)
rule_block = ref[rule_start:rule_end]
rule_block = rule_block.replace('border = BorderStroke(.7.dp,', 'border = BorderStroke(.5.dp,', 1)
rule_block = rule_block.replace('shadowElevation = if (dark) 0.dp else 1.dp,', 'shadowElevation = 0.dp,', 1)
rule_block = rule_block.replace('padding(horizontal = 14.dp, vertical = 12.dp)', 'padding(horizontal = 14.dp, vertical = 10.dp)')
ref = ref[:rule_start] + rule_block + ref[rule_end:]

ref_path.write_text(ref)


# -----------------------------------------------------------------------------
# YAML editor: state-based BasicTextField with internal viewport scrolling.
# The old parent verticalScroll measured/drew the entire document and could expose
# blank frames on fast flings. The TextField now owns the vertical scroll viewport;
# line numbers follow the same ScrollState via a cheap translated gutter.
# -----------------------------------------------------------------------------
sub_path = ROOT / 'ProxySubscriptionActivity.kt'
sub = sub_path.read_text()

sub = require_replace(
    sub,
    'import androidx.compose.ui.draw.drawBehind\n',
    'import androidx.compose.ui.draw.drawBehind\nimport androidx.compose.ui.draw.clipToBounds\n',
    'clipToBounds import',
)
sub = require_replace(
    sub,
    'import androidx.compose.foundation.text.BasicTextField\n',
    '''import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.OutputTransformation
import androidx.compose.foundation.text.input.TextFieldBuffer
''',
    'text input imports',
)

syntax_output = r'''private object YamlSyntaxHighlightOutputTransformation : OutputTransformation {
    private val keyRegex = Regex("(?m)^[\\t -]*([A-Za-z0-9_.-]+)(?=\\s*:)")
    private val scalarRegex = Regex("(?<![A-Za-z0-9_.-])(?:true|false|null|-?\\d+(?:\\.\\d+)?)(?![A-Za-z0-9_.-])", RegexOption.IGNORE_CASE)
    private val commentRegex = Regex("(?m)#.*$")

    override fun TextFieldBuffer.transformOutput() {
        val source = asCharSequence().toString()
        keyRegex.findAll(source).forEach { match ->
            val group = match.groups[1] ?: return@forEach
            addStyle(
                SpanStyle(color = Color(0xFF0E7490), fontWeight = FontWeight.SemiBold),
                group.range.first,
                group.range.last + 1,
            )
        }
        scalarRegex.findAll(source).forEach { match ->
            addStyle(
                SpanStyle(color = Color(0xFF6366F1), fontWeight = FontWeight.Medium),
                match.range.first,
                match.range.last + 1,
            )
        }
        commentRegex.findAll(source).forEach { match ->
            addStyle(
                SpanStyle(color = Color(0xFF64748B)),
                match.range.first,
                match.range.last + 1,
            )
        }
    }
}'''
sub = replace_region(
    sub,
    'private object YamlSyntaxHighlightTransformation',
    '@Composable\nprivate fun ProxySubscriptionScreen',
    syntax_output,
    'yaml syntax transformation',
)

editor_block = r'''                    val editorScroll = rememberScrollState()
                    val editorHorizontalScroll = rememberScrollState()
                    val editorState = androidx.compose.foundation.text.input.rememberTextFieldState(yamlText)
                    val editorText = editorState.text.toString()
                    val lineCount = remember(editorText) { maxOf(1, editorText.count { it == '\n' } + 1) }
                    LaunchedEffect(editorState) {
                        snapshotFlow { editorState.text.toString() }.collect {
                            if (yamlError.isNotBlank()) yamlError = ""
                        }
                    }
                    Surface(
                        modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 16.dp, vertical = 8.dp),
                        shape = editorShape,
                        color = if (dark) tokens.cardBackground else Color(0xFFF8FAFC),
                        border = BorderStroke(.7.dp, if (dark) tokens.outline.copy(alpha = .42f) else Color(0xFFE2E8F0)),
                        tonalElevation = 0.dp,
                    ) {
                        Row(Modifier.fillMaxSize()) {
                            Box(
                                Modifier.width(46.dp).fillMaxHeight().clipToBounds()
                                    .background(if (dark) Color.White.copy(alpha = .035f) else Color(0xFFF1F5F9)),
                            ) {
                                Text(
                                    (1..lineCount).joinToString("\n"),
                                    modifier = Modifier.fillMaxWidth()
                                        .graphicsLayer { translationY = -editorScroll.value.toFloat() }
                                        .padding(top = 12.dp, end = 9.dp, bottom = 12.dp),
                                    color = if (dark) tokens.textMuted else Color(0xFFB0BAC8),
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    lineHeight = 21.sp,
                                    textAlign = TextAlign.End,
                                )
                            }
                            BasicTextField(
                                state = editorState,
                                modifier = Modifier.weight(1f).fillMaxHeight()
                                    .horizontalScroll(editorHorizontalScroll)
                                    .drawBehind {
                                        val guideColor = if (dark) Color.White.copy(alpha = .04f) else Color(0xFFE2E8F0).copy(alpha = .86f)
                                        val step = 16.dp.toPx()
                                        for (index in 1..5) {
                                            val x = index * step
                                            drawLine(guideColor, Offset(x, 0f), Offset(x, size.height), strokeWidth = .5.dp.toPx())
                                        }
                                    }
                                    .padding(horizontal = 12.dp, vertical = 12.dp),
                                textStyle = MaterialTheme.typography.bodySmall.copy(
                                    color = tokens.textPrimary,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 13.sp,
                                    lineHeight = 21.sp,
                                ),
                                cursorBrush = SolidColor(scheme.primary),
                                outputTransformation = YamlSyntaxHighlightOutputTransformation,
                                scrollState = editorScroll,
                            )
                        }
                    }'''
sub = replace_region(
    sub,
    '                    val editorScroll = rememberScrollState()',
    '                    if (yamlError.isNotBlank()) {',
    editor_block,
    'yaml editor viewport',
)
sub = require_replace(
    sub,
    'controller.saveConfigText(yamlText)',
    'controller.saveConfigText(editorState.text.toString())',
    'yaml save current state',
)
sub_path.write_text(sub)


# -----------------------------------------------------------------------------
# Advanced settings: dedicated preflight result sheet with shield state.
# -----------------------------------------------------------------------------
adv_path = ROOT / 'ProxyAdvancedSettingsActivity.kt'
adv = adv_path.read_text()
adv = require_replace(
    adv,
    '    var infoText by remember { mutableStateOf<String?>(null) }\n    var busy by remember { mutableStateOf(false) }',
    '''    var infoText by remember { mutableStateOf<String?>(null) }
    var preflightText by remember { mutableStateOf<String?>(null) }
    var preflightPassed by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }''',
    'preflight state',
)
adv = require_replace(
    adv,
    '''    fun runRoot(title: String, block: suspend () -> String) {
        if (busy) return
        scope.launch {
            busy = true
            val text = runCatching { withContext(Dispatchers.IO) { block() } }
                .getOrElse { it.message ?: it.javaClass.simpleName }
            busy = false
            infoTitle = title
            infoText = text.ifBlank { "完成" }
        }
    }
''',
    '''    fun runRoot(title: String, block: suspend () -> String) {
        if (busy) return
        scope.launch {
            busy = true
            val text = runCatching { withContext(Dispatchers.IO) { block() } }
                .getOrElse { it.message ?: it.javaClass.simpleName }
            busy = false
            infoTitle = title
            infoText = text.ifBlank { "完成" }
        }
    }

    fun runPreflight() {
        if (busy) return
        scope.launch {
            busy = true
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val prepared = root.prepare(ProxyRuntimeProfile.load(prefs))
                    root.preflight(prepared)
                }
            }
            busy = false
            result.onSuccess { json ->
                preflightPassed = json.optBoolean("ok", false)
                preflightText = if (preflightPassed) {
                    "预检通过：当前设备支持这组 Root 代理设置"
                } else {
                    json.optString("message", "预检未通过")
                }
            }.onFailure { error ->
                preflightPassed = false
                preflightText = error.message ?: "预检执行失败"
            }
        }
    }
''',
    'runPreflight helper',
)
adv = require_replace(
    adv,
    '''                AdvancedActionRow(Icons.Rounded.HealthAndSafety, Color(0xFF10B981), "运行预检", "验证 Root / TPROXY / UID / IPv6 / 绕过规则") {
                    runRoot("运行预检") {
                        val prepared = root.prepare(ProxyRuntimeProfile.load(prefs))
                        val result = root.preflight(prepared)
                        if (result.optBoolean("ok", false)) "预检通过：当前设备支持这组 Root 代理设置" else result.optString("message", "预检失败")
                    }
                }''',
    '''                AdvancedActionRow(Icons.Rounded.HealthAndSafety, Color(0xFF10B981), "运行预检", "验证 Root / TPROXY / UID / IPv6 / 绕过规则") {
                    runPreflight()
                }''',
    'preflight action',
)
adv = require_replace(
    adv,
    '''    if (infoTitle != null && infoText != null) {
        AdvancedInfoSheet(infoTitle!!, infoText!!, onDismiss = { infoTitle = null; infoText = null })
    }
}''',
    '''    preflightText?.let { text ->
        AdvancedPreflightSheet(
            passed = preflightPassed,
            text = text,
            onDismiss = { preflightText = null },
        )
    }

    if (infoTitle != null && infoText != null) {
        AdvancedInfoSheet(infoTitle!!, infoText!!, onDismiss = { infoTitle = null; infoText = null })
    }
}''',
    'preflight sheet call',
)

preflight_sheet = r'''@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AdvancedPreflightSheet(passed: Boolean, text: String, onDismiss: () -> Unit) {
    val t = LocalBichenTokens.current
    val pulseTransition = androidx.compose.animation.core.rememberInfiniteTransition(label = "preflightShieldPulse")
    val pulse by pulseTransition.animateFloat(
        initialValue = .94f,
        targetValue = 1.06f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
            animation = androidx.compose.animation.core.tween(850),
            repeatMode = androidx.compose.animation.core.RepeatMode.Reverse,
        ),
        label = "preflightShieldScale",
    )
    val accent = if (passed) Color(0xFF10B981) else Color(0xFFF59E0B)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = t.elevatedCardBackground,
        tonalElevation = 0.dp,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        dragHandle = {
            Box(
                Modifier.padding(top = 10.dp, bottom = 5.dp).size(width = 36.dp, height = 4.dp)
                    .background(Color(0xFFCBD5E1), CircleShape),
            )
        },
    ) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 20.dp, end = 20.dp, bottom = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                Modifier.size(62.dp).graphicsLayer { scaleX = pulse; scaleY = pulse }
                    .background(accent.copy(alpha = .11f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (passed) Icons.Rounded.VerifiedUser else Icons.Rounded.HealthAndSafety,
                    null,
                    tint = accent,
                    modifier = Modifier.size(31.dp),
                )
            }
            Text(
                if (passed) "预检通过" else "预检结果",
                color = t.textPrimary,
                fontSize = 20.sp,
                lineHeight = 25.sp,
                fontWeight = FontWeight.ExtraBold,
            )
            Surface(shape = RoundedCornerShape(18.dp), color = t.controlBackground.copy(alpha = .52f)) {
                Text(
                    text,
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                    color = t.textSecondary,
                    fontSize = 12.sp,
                    lineHeight = 19.sp,
                )
            }
            Text("下滑即可关闭", color = Color(0xFF94A3B8), fontSize = 10.sp, fontWeight = FontWeight.Medium)
        }
    }
}'''
adv = adv.replace(
    '@Composable\nprivate fun AdvancedSectionLabel',
    preflight_sheet + '\n\n@Composable\nprivate fun AdvancedSectionLabel',
    1,
)
adv_path.write_text(adv)

print('Applied Bichen 0.4.0-test.52 motion/render fixes')
