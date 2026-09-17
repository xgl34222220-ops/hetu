#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

def read(rel):
    return (ROOT / rel).read_text(encoding="utf-8")

def write(rel, text):
    path = ROOT / rel
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8")

def replace_once(text, old, new, label):
    if old not in text:
        raise SystemExit(f"missing marker: {label}")
    return text.replace(old, new, 1)

build_path = "android-app/app/build.gradle.kts"
build = read(build_path)
build = replace_once(build, 'versionCode = 457', 'versionCode = 458', 'versionCode')
build = replace_once(build, 'versionName = "0.4.0-test.57"', 'versionName = "0.4.0-test.58"', 'versionName')
write(build_path, build)

ref_path = "android-app/app/src/main/java/io/github/xgl34222220/bichen/ReferenceProxyActivity.kt"
ref = read(ref_path)
if 'import androidx.compose.ui.zIndex' not in ref:
    ref = ref.replace('import androidx.compose.ui.unit.sp\n', 'import androidx.compose.ui.unit.sp\nimport androidx.compose.ui.zIndex\n', 1)

ref = replace_once(
    ref,
    '''        modifier
            .height(86.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale; alpha = if (pressed) .95f else 1f }
            .shadow(if (expanded) 5.dp else 3.dp, shape, clip = false)
            .background(premiumBrush, shape)
            .border(
                if (expanded) 1.4.dp else .8.dp,
                if (expanded) Color(0xFF002FA7).copy(alpha = .64f) else Color.White.copy(alpha = if (dark) .10f else .92f),
                shape,
            )
            .clip(shape)
            .padding(start = 14.dp, top = 11.dp, end = 13.dp, bottom = 10.dp),''',
    '''        modifier
            .height(86.dp)
            .zIndex(1f)
            .graphicsLayer { scaleX = scale; scaleY = scale; alpha = if (pressed) .95f else 1f }
            .shadow(if (expanded) 4.dp else 2.dp, shape, clip = false)
            .background(premiumBrush, shape)
            .border(
                if (expanded) 1.25.dp else .8.dp,
                if (expanded) Color(0xFF002FA7).copy(alpha = .58f) else Color.White.copy(alpha = if (dark) .10f else .92f),
                shape,
            )
            .padding(start = 15.dp, top = 12.dp, end = 14.dp, bottom = 11.dp),''',
    'strategy outer unclipped surface',
)
ref = replace_once(
    ref,
    'modifier = Modifier.padding(top = 8.dp, bottom = 12.dp).fillMaxWidth(),',
    'modifier = Modifier.padding(top = 8.dp, bottom = 16.dp).fillMaxWidth().zIndex(0f),',
    'tray outer bottom margin',
)
ref = replace_once(
    ref,
    'Modifier.fillMaxWidth().background(wellBrush, shape).padding(start = 14.dp, top = 16.dp, end = 14.dp, bottom = 14.dp),',
    'Modifier.fillMaxWidth().background(wellBrush, shape).padding(start = 14.dp, top = 16.dp, end = 14.dp, bottom = 18.dp),',
    'tray inner bottom padding',
)
ref = replace_once(
    ref,
    '''                        if (active) 5.dp else 3.dp,
                        shape,
                        clip = false,''',
    '''                        if (active) 3.dp else 1.dp,
                        shape,
                        clip = false,''',
    'node shadow containment',
)

ref = replace_once(
    ref,
    '''            Column(
                Modifier.fillMaxWidth().graphicsLayer {
                    rotationY = flip.value
                    cameraDistance = 18f * density
                    alpha = .94f + .06f * (1f - kotlin.math.abs(flip.value) / 90f)
                },
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {''',
    '''            Box(
                Modifier.fillMaxWidth().height(42.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                Column(
                    Modifier.fillMaxWidth().widthIn(min = 130.dp).graphicsLayer {
                        rotationY = flip.value
                        cameraDistance = 18f * density
                        alpha = .94f + .06f * (1f - kotlin.math.abs(flip.value) / 90f)
                    },
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {''',
    'LAN fixed viewport open',
)
ref = replace_once(
    ref,
    '''                    modifier = Modifier.height(18.dp),
                )
            }
        }
    }
}

@Composable
private fun RefSpeedCard''',
    '''                    modifier = Modifier.fillMaxWidth().height(18.dp),
                )
                }
            }
        }
    }
}

@Composable
private fun RefSpeedCard''',
    'LAN fixed viewport close',
)
ref = ref.replace('modifier = Modifier.height(20.dp),', 'modifier = Modifier.fillMaxWidth().height(20.dp),', 1)

old_rule = '''@Composable
private fun RefRuleGroupCard(items: List<ProxyRuleUi>) {
    val t = LocalBichenTokens.current
    val dark = MaterialTheme.colorScheme.background.luminance() < .5f
    val shape = RoundedCornerShape(22.dp)
    Surface(
        shape = shape,
        color = t.cardBackground,
        border = BorderStroke(.5.dp, if (dark) t.outline.copy(alpha = .32f) else Color(0xFFF1F5F9)),
        shadowElevation = 0.dp,
    ) {
        Column(Modifier.fillMaxWidth()) {
            items.forEachIndexed { index, item ->
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
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
                        thickness = .5.dp,
                        color = if (dark) t.outline.copy(alpha = .28f) else Color(0xFFF1F5F9).copy(alpha = .84f),
                    )
                }
            }
        }
    }
}
'''
new_rule = '''@Composable
private fun RefRuleGroupCard(items: List<ProxyRuleUi>) {
    val t = LocalBichenTokens.current
    val dark = MaterialTheme.colorScheme.background.luminance() < .5f
    val shape = RoundedCornerShape(22.dp)
    Surface(
        shape = shape,
        color = t.cardBackground,
        border = BorderStroke(.5.dp, if (dark) t.outline.copy(alpha = .32f) else Color(0xFFF1F5F9)),
        shadowElevation = 0.dp,
    ) {
        Column(Modifier.fillMaxWidth()) {
            items.forEachIndexed { index, item ->
                val expression = item.payload.ifBlank { item.type }
                val composite = expression.contains("&&") || expression.contains("||") ||
                    expression.count { it == '(' } >= 2
                var expanded by remember(item.type, item.payload, item.proxy) { mutableStateOf(false) }
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Surface(
                            modifier = Modifier.fillMaxWidth()
                                .clip(RoundedCornerShape(7.dp))
                                .clickable(
                                    enabled = composite,
                                    indication = null,
                                    interactionSource = remember(item.type, item.payload) { MutableInteractionSource() },
                                ) { expanded = !expanded },
                            shape = RoundedCornerShape(7.dp),
                            color = if (dark) Color.White.copy(alpha = .045f) else Color(0xFFF1F5F9),
                            tonalElevation = 0.dp,
                        ) {
                            androidx.compose.animation.AnimatedContent(
                                targetState = expanded,
                                transitionSpec = {
                                    androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(150))
                                        .togetherWith(androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(90)))
                                },
                                label = "ruleExpressionExpand${index}",
                            ) { open ->
                                Text(
                                    expression,
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 7.dp, vertical = 4.dp),
                                    color = if (dark) Color(0xFFCBD5E1) else Color(0xFF475569),
                                    fontSize = 11.sp,
                                    lineHeight = 15.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                    maxLines = if (open) 5 else 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        Text(
                            if (composite) "${item.type} · 点击${if (expanded) "收起" else "展开"}" else item.type,
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
                        thickness = .5.dp,
                        color = if (dark) t.outline.copy(alpha = .28f) else Color(0xFFF1F5F9).copy(alpha = .84f),
                    )
                }
            }
        }
    }
}
'''
ref = replace_once(ref, old_rule, new_rule, 'rule code pill')
write(ref_path, ref)

net_path = "android-app/app/src/main/java/io/github/xgl34222220/bichen/ProxyNetworkAutomationActivity.kt"
net = read(net_path)
old_net_editor = '''@Composable
private fun NetworkSetEditor(state: NetworkEditor, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember(state) { mutableStateOf(state.value) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(state.title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(state.hint, color = LocalBichenTokens.current.textSecondary, fontSize = 12.sp)
                OutlinedTextField(value = text, onValueChange = { text = it }, modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp, max = 320.dp), minLines = 6)
            }
        },
        confirmButton = { TextButton(onClick = { onSave(text) }) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
'''
new_net_editor = '''@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NetworkSetEditor(state: NetworkEditor, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember(state) { mutableStateOf(state.value) }
    val t = LocalBichenTokens.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = t.elevatedCardBackground,
        tonalElevation = 0.dp,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        dragHandle = {
            Box(
                Modifier.padding(top = 10.dp, bottom = 6.dp).size(width = 36.dp, height = 4.dp)
                    .background(Color(0xFFCBD5E1), RoundedCornerShape(999.dp)),
            )
        },
    ) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 18.dp, end = 18.dp, bottom = 18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(state.title, color = t.textPrimary, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
            Text(state.hint, color = t.textSecondary, fontSize = 12.sp, lineHeight = 18.sp)
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth().heightIn(min = 190.dp, max = 360.dp),
                minLines = 7,
                shape = RoundedCornerShape(18.dp),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FilledTonalButton(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f).height(44.dp),
                    shape = RoundedCornerShape(999.dp),
                ) { Text("取消", fontWeight = FontWeight.Bold) }
                Button(
                    onClick = { onSave(text) },
                    modifier = Modifier.weight(1f).height(44.dp),
                    shape = RoundedCornerShape(999.dp),
                ) { Text("保存", fontWeight = FontWeight.Bold) }
            }
        }
    }
}
'''
net = replace_once(net, old_net_editor, new_net_editor, 'network bottom sheet editor')
net = net.replace(
    'bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 28.dp',
    'bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 40.dp',
    1,
)
write(net_path, net)

adv_path = "android-app/app/src/main/java/io/github/xgl34222220/bichen/ProxyAdvancedSettingsActivity.kt"
adv = read(adv_path)
if 'import androidx.compose.foundation.BorderStroke' not in adv:
    adv = adv.replace('import androidx.compose.foundation.background\n', 'import androidx.compose.foundation.BorderStroke\nimport androidx.compose.foundation.background\n')
if 'import androidx.compose.foundation.horizontalScroll' not in adv:
    adv = adv.replace('import androidx.compose.foundation.layout.*\n', 'import androidx.compose.foundation.layout.*\nimport androidx.compose.foundation.horizontalScroll\nimport androidx.compose.foundation.verticalScroll\n')
old_adv_editor = '''@Composable
private fun SetEditorDialog(state: SetEditorState, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember(state) { mutableStateOf(state.value) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(state.title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(state.hint, color = LocalBichenTokens.current.textSecondary, fontSize = 12.sp)
                OutlinedTextField(value = text, onValueChange = { text = it }, modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp, max = 320.dp), minLines = 6)
            }
        },
        confirmButton = { TextButton(onClick = { onSave(text) }) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
'''
new_adv_editor = '''@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SetEditorDialog(state: SetEditorState, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember(state) { mutableStateOf(state.value) }
    val t = LocalBichenTokens.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = t.elevatedCardBackground,
        tonalElevation = 0.dp,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        dragHandle = {
            Box(
                Modifier.padding(top = 10.dp, bottom = 6.dp).size(width = 36.dp, height = 4.dp)
                    .background(Color(0xFFCBD5E1), CircleShape),
            )
        },
    ) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 18.dp, end = 18.dp, bottom = 18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(state.title, color = t.textPrimary, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
            Text(state.hint, color = t.textSecondary, fontSize = 12.sp, lineHeight = 18.sp)
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth().heightIn(min = 190.dp, max = 360.dp),
                minLines = 7,
                shape = RoundedCornerShape(18.dp),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FilledTonalButton(onClick = onDismiss, modifier = Modifier.weight(1f).height(44.dp), shape = CircleShape) {
                    Text("取消", fontWeight = FontWeight.Bold)
                }
                Button(onClick = { onSave(text) }, modifier = Modifier.weight(1f).height(44.dp), shape = CircleShape) {
                    Text("保存", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
'''
adv = replace_once(adv, old_adv_editor, new_adv_editor, 'advanced set editor bottom sheet')

old_info = '''@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AdvancedInfoSheet(title: String, text: String, onDismiss: () -> Unit) {
    val t = LocalBichenTokens.current
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = t.elevatedCardBackground, tonalElevation = 0.dp, shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, color = t.textPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Surface(shape = RoundedCornerShape(16.dp), color = t.controlBackground.copy(alpha = .55f)) {
                Text(text, Modifier.fillMaxWidth().padding(14.dp), color = t.textSecondary, fontSize = 12.sp, lineHeight = 18.sp)
            }
            Text("下滑即可关闭", color = Color(0xFF94A3B8), fontSize = 10.sp, fontWeight = FontWeight.Medium, modifier = Modifier.align(Alignment.CenterHorizontally))
        }
    }
}
'''
new_info = '''private fun advancedYamlPreview(text: String): androidx.compose.ui.text.AnnotatedString =
    androidx.compose.ui.text.buildAnnotatedString {
        val cyan = Color(0xFF38BDF8)
        val lime = Color(0xFFA3E635)
        val comment = Color(0xFF64748B)
        val normal = Color(0xFFE2E8F0)
        val lines = text.lines()
        lines.forEachIndexed { index, line ->
            val trimmed = line.trimStart()
            val indent = line.take(line.length - trimmed.length)
            append(indent)
            when {
                trimmed.startsWith("#") -> {
                    pushStyle(androidx.compose.ui.text.SpanStyle(color = comment))
                    append(trimmed)
                    pop()
                }
                trimmed.startsWith("- ") -> {
                    pushStyle(androidx.compose.ui.text.SpanStyle(color = normal))
                    append("- ")
                    pop()
                    val hash = trimmed.indexOf('#', 2)
                    val value = if (hash >= 0) trimmed.substring(2, hash) else trimmed.substring(2)
                    pushStyle(androidx.compose.ui.text.SpanStyle(color = lime))
                    append(value)
                    pop()
                    if (hash >= 0) {
                        pushStyle(androidx.compose.ui.text.SpanStyle(color = comment))
                        append(trimmed.substring(hash))
                        pop()
                    }
                }
                ':' in trimmed -> {
                    val colon = trimmed.indexOf(':')
                    pushStyle(androidx.compose.ui.text.SpanStyle(color = cyan))
                    append(trimmed.substring(0, colon + 1))
                    pop()
                    val rest = trimmed.substring(colon + 1)
                    val hash = rest.indexOf('#')
                    val value = if (hash >= 0) rest.substring(0, hash) else rest
                    pushStyle(androidx.compose.ui.text.SpanStyle(color = lime))
                    append(value)
                    pop()
                    if (hash >= 0) {
                        pushStyle(androidx.compose.ui.text.SpanStyle(color = comment))
                        append(rest.substring(hash))
                        pop()
                    }
                }
                else -> {
                    pushStyle(androidx.compose.ui.text.SpanStyle(color = normal))
                    append(trimmed)
                    pop()
                }
            }
            if (index != lines.lastIndex) append('\\n')
        }
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AdvancedInfoSheet(title: String, text: String, onDismiss: () -> Unit) {
    val t = LocalBichenTokens.current
    val codePreview = title == "启动配置"
    val highlighted = remember(text, codePreview) { if (codePreview) advancedYamlPreview(text) else null }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = if (codePreview) Color(0xFF0B1220) else t.elevatedCardBackground,
        contentColor = if (codePreview) Color(0xFFE2E8F0) else t.textPrimary,
        tonalElevation = 0.dp,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        dragHandle = {
            Box(
                Modifier.padding(top = 10.dp, bottom = 6.dp).size(width = 36.dp, height = 4.dp)
                    .background(if (codePreview) Color(0xFF334155) else Color(0xFFCBD5E1), CircleShape),
            )
        },
    ) {
        Column(
            Modifier.fillMaxWidth().fillMaxHeight(if (codePreview) .82f else .62f)
                .navigationBarsPadding().padding(start = 18.dp, end = 18.dp, bottom = 18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(title, color = if (codePreview) Color(0xFFF8FAFC) else t.textPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            if (codePreview) {
                val lines = remember(text) { maxOf(1, text.count { it == '\\n' } + 1) }
                val vScroll = androidx.compose.foundation.rememberScrollState()
                Surface(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    shape = RoundedCornerShape(18.dp),
                    color = Color(0xFF0F172A),
                    border = BorderStroke(.7.dp, Color(0xFF334155)),
                ) {
                    Row(Modifier.fillMaxSize().verticalScroll(vScroll)) {
                        Text(
                            (1..lines).joinToString("\\n"),
                            modifier = Modifier.width(44.dp).background(Color(0xFF111827))
                                .padding(top = 12.dp, end = 8.dp, bottom = 12.dp),
                            color = Color(0xFF475569),
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            fontSize = 10.sp,
                            lineHeight = 18.sp,
                            textAlign = androidx.compose.ui.text.style.TextAlign.End,
                        )
                        Box(Modifier.width(1.dp).fillMaxHeight().background(Color(0xFF334155)))
                        Text(
                            highlighted ?: androidx.compose.ui.text.AnnotatedString(text),
                            modifier = Modifier.weight(1f)
                                .horizontalScroll(androidx.compose.foundation.rememberScrollState())
                                .padding(horizontal = 12.dp, vertical = 12.dp),
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            fontSize = 11.sp,
                            lineHeight = 18.sp,
                            softWrap = false,
                        )
                    }
                }
            } else {
                Surface(shape = RoundedCornerShape(16.dp), color = t.controlBackground.copy(alpha = .55f)) {
                    Text(
                        text,
                        Modifier.fillMaxWidth().padding(14.dp)
                            .verticalScroll(androidx.compose.foundation.rememberScrollState()),
                        color = t.textSecondary,
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                    )
                }
            }
            Text(
                "下滑即可关闭",
                color = Color(0xFF94A3B8),
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
        }
    }
}
'''
adv = replace_once(adv, old_info, new_info, 'startup syntax preview')
adv = adv.replace(
    'bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 28.dp',
    'bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 40.dp',
    1,
)
write(adv_path, adv)

adb_path = "android-app/app/src/main/java/io/github/xgl34222220/bichen/ProxyAdblockChainActivity.kt"
adb = read(adb_path)
if 'import androidx.compose.animation.core.animateFloat' not in adb:
    adb = adb.replace('import androidx.compose.foundation.background\n', 'import androidx.compose.animation.core.animateFloat\nimport androidx.compose.foundation.background\n')
adb = replace_once(
    adb,
    '    val scope = rememberCoroutineScope()\n',
    '''    val scope = rememberCoroutineScope()
    val updateView = androidx.compose.ui.platform.LocalView.current
    val updateSpinTransition = androidx.compose.animation.core.rememberInfiniteTransition(label = "adblockUpdateSpin")
    val updateSpin by updateSpinTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
            androidx.compose.animation.core.tween(760, easing = androidx.compose.animation.core.LinearEasing),
        ),
        label = "adblockUpdateSpinValue",
    )
''',
    'adblock spin state',
)
adb = replace_once(
    adb,
    '''                            busy = true
                            updatingRules = true
                            updateSuccess = false
                            val result = runCatching { adController.updateRules() }
                            updatingRules = false
                            result
                                .onSuccess {
                                    notice = "$it；代理运行中时请重启代理应用新快照"
                                    revision++
                                    updateSuccess = true
                                    kotlinx.coroutines.delay(900)
                                    updateSuccess = false
                                }
                                .onFailure { notice = it.message ?: "规则更新失败" }
                            busy = false''',
    '''                            busy = true
                            updatingRules = true
                            updateSuccess = false
                            val startedAt = android.os.SystemClock.elapsedRealtime()
                            val result = runCatching { adController.updateRules() }
                            val elapsed = android.os.SystemClock.elapsedRealtime() - startedAt
                            if (elapsed < 650L) kotlinx.coroutines.delay(650L - elapsed)
                            updatingRules = false
                            result
                                .onSuccess {
                                    notice = "$it；代理运行中时请重启代理应用新快照"
                                    revision++
                                    updateSuccess = true
                                    updateView.performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM)
                                    kotlinx.coroutines.delay(1100)
                                    updateSuccess = false
                                }
                                .onFailure { notice = it.message ?: "规则更新失败" }
                            busy = false''',
    'adblock visible update duration',
)
adb = replace_once(
    adb,
    'updatingRules -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White)',
    '''updatingRules -> Icon(
                            Icons.Rounded.Sync,
                            "正在更新",
                            modifier = Modifier.size(19.dp).graphicsLayer { rotationZ = updateSpin },
                            tint = Color.White,
                        )''',
    'adblock rotating icon',
)
adb = adb.replace('Text("更新完成", fontWeight = FontWeight.Bold)', 'Text("已是最新", fontWeight = FontWeight.Bold)', 1)
if 'import androidx.compose.ui.graphics.graphicsLayer' not in adb:
    adb = adb.replace(
        'import androidx.compose.ui.graphics.luminance\n',
        'import androidx.compose.ui.graphics.luminance\nimport androidx.compose.ui.graphics.graphicsLayer\n',
        1,
    )
write(adb_path, adb)


# WebUI: only intercept back when the embedded dashboard has history. Otherwise the
# activity back event stays with Android so predictive cross-activity back can render.
web_path = "android-app/app/src/main/java/io/github/xgl34222220/bichen/ProxyWebUiActivity.kt"
web = read(web_path)
web = web.replace('import androidx.activity.OnBackPressedCallback\n', '')
if 'import androidx.activity.compose.BackHandler' not in web:
    web = web.replace('import androidx.activity.compose.setContent\n', 'import androidx.activity.compose.BackHandler\nimport androidx.activity.compose.setContent\n')
web = replace_once(
    web,
    '    var forceRepair by remember { mutableStateOf(false) }\n',
    '    var forceRepair by remember { mutableStateOf(false) }\n    var canGoBack by remember { mutableStateOf(false) }\n',
    'web back history state',
)
web = replace_once(
    web,
    '''                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    if (!url.isNullOrBlank() && url.startsWith("http://127.0.0.1:${MihomoStartupConfig.CONTROLLER_PORT}/ui/")) {''',
    '''                override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                    super.doUpdateVisitedHistory(view, url, isReload)
                    canGoBack = view?.canGoBack() == true
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    canGoBack = view?.canGoBack() == true
                    if (!url.isNullOrBlank() && url.startsWith("http://127.0.0.1:${MihomoStartupConfig.CONTROLLER_PORT}/ui/")) {''',
    'web history callback',
)
old_back = '''    DisposableEffect(webView) {
        val callback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) webView.goBack() else onClose()
            }
        }
        (context as? ComponentActivity)?.onBackPressedDispatcher?.addCallback(callback)
        onDispose {
            callback.remove()
            webView.stopLoading()
            webView.destroy()
        }
    }
'''
new_back = '''    BackHandler(enabled = canGoBack) {
        webView.goBack()
    }
    DisposableEffect(webView) {
        onDispose {
            webView.stopLoading()
            webView.destroy()
        }
    }
'''
web = replace_once(web, old_back, new_back, 'web predictive back delegation')
write(web_path, web)

print("test58 patch applied")
