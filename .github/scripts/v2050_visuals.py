from pathlib import Path
import re

root = Path('.')

build = root / 'android-app/app/build.gradle.kts'
s = build.read_text()
assert 'versionCode = 2049' in s and 'versionName = "0.9.9-v20"' in s
s = s.replace('versionCode = 2049', 'versionCode = 2050', 1)
s = s.replace('versionName = "0.9.9-v20"', 'versionName = "0.10.0-v20"', 1)
build.write_text(s)

theme = root / 'android-app/app/src/main/java/io/github/xgl34222220/hetu/app/HxTheme.kt'
s = theme.read_text()
repls = {
    'canvas = Color(0xFFF2F0F9),': 'canvas = Color(0xFFECEEFB),',
    'surface = Color(0xFFFCFBFF),': 'surface = Color(0xFFF8F7FD),',
    'surfaceMuted = Color(0xFFF0EDF7),': 'surfaceMuted = Color(0xFFEEEFFA),',
    'line = Color(0xFFE7E2EF),': 'line = Color(0xFFDCE0EC),',
    'text = Color(0xFF171820),': 'text = Color(0xFF171A24),',
    'textMuted = Color(0xFF686C79),': 'textMuted = Color(0xFF5F6473),',
    'textFaint = Color(0xFFA4A6B0),': 'textFaint = Color(0xFF989DAB),',
    'accent = Color(0xFF2A62E8),': 'accent = Color(0xFF2E70E2),',
    'accentSoft = Color(0xFFE9EDFC),': 'accentSoft = Color(0xFFDCE5FF),',
}
for old, new in repls.items():
    assert old in s, old
    s = s.replace(old, new, 1)
theme.write_text(s)

comp = root / 'android-app/app/src/main/java/io/github/xgl34222220/hetu/app/HxComponents.kt'
s = comp.read_text()
assert '    canvasColor: Color? = null,\n    content: LazyListScope.() -> Unit,' in s
s = s.replace(
    '    canvasColor: Color? = null,\n    content: LazyListScope.() -> Unit,',
    '    canvasColor: Color? = null,\n    referenceLabel: String? = null,\n    content: LazyListScope.() -> Unit,',
    1,
)
assert '    Box(Modifier.fillMaxSize().background(pageCanvas)) {' in s
s = s.replace(
    '    Box(Modifier.fillMaxSize().background(pageCanvas)) {',
    '''    val pageBrush = if (c.dark) {
        Brush.verticalGradient(listOf(pageCanvas, pageCanvas))
    } else {
        Brush.verticalGradient(
            listOf(
                Color(0xFFF0F2FD),
                Color(0xFFECEFFB),
                Color(0xFFE9ECF9),
            ),
        )
    }
    Box(Modifier.fillMaxSize().background(pageBrush)) {''',
    1,
)
old = '''                if (largeTitle) item(key = "hx-page-header") {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(start = largeTitleStartPadding, end = Hx.gutter, bottom = largeTitleBottomPadding)
                            .graphicsLayer {
                                val p = headerProgress.value
                                alpha = 1f - p * .92f
                                val s = 1f - .05f * p
                                scaleX = s
                                scaleY = s
                                transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 1f)
                                translationY = p * 10.dp.toPx()
                            },
                    ) {
                        Text(
                            title,
                            style = MaterialTheme.typography.headlineMedium.copy(
                                fontSize = largeTitleFontSizeSp?.sp ?: MaterialTheme.typography.headlineMedium.fontSize,
                                lineHeight = largeTitleFontSizeSp?.let { (it + 8f).sp } ?: MaterialTheme.typography.headlineMedium.lineHeight,
                                letterSpacing = (-0.35).sp,
                            ),
                            color = c.text,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (!subtitle.isNullOrBlank()) {
                            Spacer(Modifier.height(2.dp))
                            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = c.textMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                } else if (!subtitle.isNullOrBlank()) item(key = "hx-page-compact-subtitle") {'''
new = '''                if (largeTitle) item(key = "hx-page-header") {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(start = largeTitleStartPadding, end = Hx.gutter + 2.dp, bottom = largeTitleBottomPadding)
                            .graphicsLayer {
                                val p = headerProgress.value
                                alpha = 1f - p * .92f
                                val scale = 1f - .05f * p
                                scaleX = scale
                                scaleY = scale
                                transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 1f)
                                translationY = p * 10.dp.toPx()
                            },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                title,
                                style = MaterialTheme.typography.headlineMedium.copy(
                                    fontSize = largeTitleFontSizeSp?.sp ?: MaterialTheme.typography.headlineMedium.fontSize,
                                    lineHeight = largeTitleFontSizeSp?.let { (it + 8f).sp } ?: MaterialTheme.typography.headlineMedium.lineHeight,
                                    letterSpacing = (-0.35).sp,
                                ),
                                color = c.text,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (!subtitle.isNullOrBlank()) {
                                Spacer(Modifier.height(2.dp))
                                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = c.textMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        if (!referenceLabel.isNullOrBlank()) {
                            Text(
                                referenceLabel,
                                style = MaterialTheme.typography.bodySmall,
                                color = c.textFaint,
                                maxLines = 1,
                            )
                        }
                    }
                } else if (!subtitle.isNullOrBlank()) item(key = "hx-page-compact-subtitle") {'''
assert old in s
s = s.replace(old, new, 1)
comp.write_text(s)

home = root / 'android-app/app/src/main/java/io/github/xgl34222220/hetu/app/HomeScreen.kt'
s = home.read_text()
if 'import androidx.compose.foundation.layout.heightIn\n' not in s:
    s = s.replace('import androidx.compose.foundation.layout.height\n', 'import androidx.compose.foundation.layout.height\nimport androidx.compose.foundation.layout.heightIn\n', 1)
assert '        canvasColor = if (Hx.colors.dark) Hx.colors.canvas else Color(0xFFEBEDFA),\n    ) {' in s
s = s.replace(
    '        canvasColor = if (Hx.colors.dark) Hx.colors.canvas else Color(0xFFEBEDFA),\n    ) {',
    '        canvasColor = Hx.colors.canvas,\n        referenceLabel = "示例数据",\n        actions = { Text("示例数据", style = MaterialTheme.typography.bodySmall, color = Hx.colors.textFaint) },\n    ) {',
    1,
)
start = s.index('@Composable\nprivate fun HomeHero(')
end = s.index('\n@Composable\nprivate fun HeroAction', start)
new_hero = r'''@Composable
private fun HomeHero(vm: HetuViewModel, expanded: Boolean, onToggleExpanded: () -> Unit) {
    val c = Hx.colors
    val state = vm.state
    val running = state.running
    val op = vm.operation
    val healthy = running && state.message.isBlank()
    val tint by animateColorAsState(
        when {
            op != null -> c.warn
            running -> c.accent
            else -> c.textFaint
        },
        tween(HxMotion.Medium),
        label = "heroTint",
    )
    val wash by animateColorAsState(
        when {
            op != null -> Color(0xFFFFF0D2)
            running -> Color(0xFFE0DFFE)
            else -> Color(0xFFE9EAF3)
        },
        tween(HxMotion.Long),
        label = "heroWash",
    )
    val glyphScale = remember { androidx.compose.animation.core.Animatable(1f) }
    LaunchedEffect(running) {
        glyphScale.snapTo(.76f)
        glyphScale.animateTo(1f, spring(dampingRatio = .56f, stiffness = 320f))
    }
    val status = when (op) {
        HxRunOp.Start -> "正在启动"
        HxRunOp.Stop -> "正在停止"
        HxRunOp.Restart -> "正在重启"
        HxRunOp.Reload -> "正在重载"
        null -> if (running) "运行中" else "未运行"
    }

    HxSection {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 132.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(wash)
                    .clickable(enabled = running && op == null, onClick = onToggleExpanded),
            ) {
                Icon(
                    if (running || op != null) Icons.Rounded.TaskAlt else Icons.Rounded.PowerSettingsNew,
                    null,
                    tint = tint.copy(alpha = if (running) .96f else .38f),
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .size(118.dp)
                        .graphicsLayer {
                            translationX = 27.dp.toPx()
                            translationY = 18.dp.toPx()
                            scaleX = glyphScale.value
                            scaleY = glyphScale.value
                        },
                )
                Column(Modifier.padding(start = 18.dp, end = 116.dp, top = 15.dp, bottom = 14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        PulseDot(tint, pulsing = op != null || healthy)
                        Spacer(Modifier.width(6.dp))
                        AnimatedContent(
                            targetState = status,
                            transitionSpec = { fadeIn(tween(HxMotion.Medium)).togetherWith(fadeOut(tween(HxMotion.Short))) },
                            label = "heroStatus",
                        ) { text ->
                            Text(text, style = MaterialTheme.typography.titleLarge.copy(fontSize = 20.sp), fontWeight = FontWeight.Bold, color = tint)
                        }
                    }
                    Spacer(Modifier.height(9.dp))
                    Text(
                        when {
                            op != null -> vm.operationText.ifBlank { "请稍候…" }
                            running -> "已运行 " + HxFormat.duration(vm.runtime.elapsedSeconds)
                            else -> "尚未启动代理"
                        },
                        style = MaterialTheme.typography.titleMedium.merge(HxNumberStyle),
                        fontWeight = FontWeight.SemiBold,
                        color = c.text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(state.core + " · " + state.mode, style = MaterialTheme.typography.bodyMedium, color = c.text, maxLines = 1)
                    Text(state.config, style = MaterialTheme.typography.bodyMedium, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }

            Row(
                Modifier
                    .fillMaxWidth()
                    .height(54.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(c.surface),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (running) {
                    HeroAction("重载", c.accent, op == HxRunOp.Reload, op == null, Modifier.weight(1f), vm::reload)
                    Box(Modifier.width(0.5.dp).height(22.dp).background(c.line))
                    HeroAction("停止", c.bad, op == HxRunOp.Stop, op == null, Modifier.weight(1f), vm::toggle)
                    Box(Modifier.width(0.5.dp).height(22.dp).background(c.line))
                    HeroAction("重启", c.warn, op == HxRunOp.Restart, op == null, Modifier.weight(1f), vm::restart)
                } else {
                    HeroAction("启动", c.accent, op == HxRunOp.Start, op == null, Modifier.weight(1f), vm::toggle)
                }
            }

            val pending = running && vm.settingsRevision >= 0 && vm.settingsPending()
            AnimatedVisibility(pending && op == null) {
                HxBanner("设置已修改，重启后生效", tone = HxTone.Warn)
            }
            AnimatedVisibility(state.message.isNotBlank() && op == null) {
                HxBanner(state.message, tone = if (running) HxTone.Warn else HxTone.Neutral)
            }
            AnimatedVisibility(
                visible = expanded && running && op == null,
                enter = fadeIn(tween(HxMotion.Medium)) + expandVertically(tween(HxMotion.Medium, easing = HxMotion.Emphasized)),
                exit = fadeOut(tween(HxMotion.Short)) + shrinkVertically(tween(HxMotion.Medium, easing = HxMotion.Emphasized)),
            ) {
                HxSegmented(
                    options = listOf("rule" to "规则", "global" to "全局", "direct" to "直连"),
                    selected = state.trafficMode.lowercase().ifBlank { "rule" },
                    onSelect = vm::setTrafficMode,
                    enabled = true,
                )
            }
        }
    }
}
'''
s = s[:start] + new_hero + s[end:]
start = s.index('@Composable\nprivate fun HomeOutboundNode(')
end = s.index('\n/* ---------------------------- shortcuts', start)
new_node = r'''@Composable
private fun HomeOutboundNode(vm: HetuViewModel, group: ProxyGroupUi?) {
    val c = Hx.colors
    val selected = group?.now.orEmpty()
    val node = group?.nodes?.firstOrNull { it.name == selected }
    val delay = if (selected.isNotBlank()) vm.delays[selected] ?: node?.lastDelay else null
    val testing = group != null && vm.testingGroups[group.name] == true

    HxSection {
        HxCard(onClick = { vm.openPanel("proxies") }, padding = androidx.compose.foundation.layout.PaddingValues(horizontal = 15.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(38.dp), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Inventory2, null, tint = c.text, modifier = Modifier.size(24.dp))
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("当前节点", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = c.text, maxLines = 1)
                    Text(
                        selected.ifBlank { if (vm.state.running) "等待节点信息" else "节点信息尚未确认" },
                        style = MaterialTheme.typography.bodySmall,
                        color = c.textMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (group != null && vm.state.running) {
                    HxDelayPill(delay, testing) { vm.testGroup(group) }
                    Spacer(Modifier.width(4.dp))
                }
                HxChevron()
            }
        }
    }
}
'''
s = s[:start] + new_node + s[end:]
home.write_text(s)

settings = root / 'android-app/app/src/main/java/io/github/xgl34222220/hetu/app/SettingsScreen.kt'
s = settings.read_text()
old = '            canvasColor = if (c.dark) c.canvas else Color(0xFFEBEDFA),\n        ) {'
assert old in s
s = s.replace(old, '            canvasColor = c.canvas,\n            referenceLabel = "示例数据",\n        ) {', 1)
settings.write_text(s)

tools = root / 'android-app/app/src/main/java/io/github/xgl34222220/hetu/app/PanelToolsScreens.kt'
s = tools.read_text()
old = '        canvasColor = if (Hx.colors.dark) Hx.colors.canvas else Color(0xFFEBEDFA),\n    ) {'
assert old in s
s = s.replace(old, '        canvasColor = Hx.colors.canvas,\n        referenceLabel = "示例数据",\n    ) {', 1)
tools.write_text(s)

print('V20.50 visual parity phase 2 applied')
