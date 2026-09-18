from pathlib import Path

PATH = Path('android-app/app/src/main/java/io/github/xgl34222220/hetu/ReferenceProxyActivity.kt')
s = PATH.read_text()


def swap(old: str, new: str, label: str):
    global s
    if old in s:
        s = s.replace(old, new, 1)
        print(f'{label}: applied')
    elif new in s:
        print(f'{label}: already applied')
    else:
        raise SystemExit(f'missing block: {label}')


def replace_section(start: str, end: str, new: str, label: str):
    global s
    a = s.find(start)
    if a < 0:
        if new.strip() in s:
            print(f'{label}: already applied')
            return
        raise SystemExit(f'missing start: {label}')
    b = s.find(end, a)
    if b < 0:
        raise SystemExit(f'missing end: {label}')
    s = s[:a] + new + s[b:]
    print(f'{label}: applied')


# Keep the LuoShu dock completely untouched. Only this activity is edited.
if 'import androidx.compose.foundation.verticalScroll\n' not in s:
    s = s.replace('import androidx.compose.foundation.horizontalScroll\n', 'import androidx.compose.foundation.horizontalScroll\nimport androidx.compose.foundation.verticalScroll\n', 1)

# 1) More bottom safe space on every primary scroll container.
old_pad = 'bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 108.dp,'
count = s.count(old_pad)
if count:
    s = s.replace(old_pad, 'bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 132.dp,')
    print(f'bottom safe spacing: applied to {count} containers')
elif '+ 132.dp,' in s:
    print('bottom safe spacing: already applied')
else:
    raise SystemExit('missing primary bottom padding')

# 2) Native app title: no debug refresh/close buttons.
old_top = '''        item {
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().padding(top = 10.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("代理", color = t.textPrimary, fontSize = 27.sp, lineHeight = 33.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                IconButton(onClick = onRefresh, modifier = Modifier.size(44.dp)) {
                    Icon(Icons.Rounded.Refresh, "刷新", tint = t.textSecondary, modifier = Modifier.size(20.dp))
                }
                IconButton(onClick = onBack, modifier = Modifier.size(44.dp)) {
                    Icon(Icons.Rounded.Close, "关闭", tint = t.textSecondary, modifier = Modifier.size(20.dp))
                }
            }
        }
'''
new_top = '''        item {
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().padding(top = 14.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "BoxProxy",
                    color = t.textPrimary,
                    fontSize = 22.sp,
                    lineHeight = 28.sp,
                    fontWeight = FontWeight.ExtraBold,
                    modifier = Modifier.weight(1f),
                )
            }
        }
'''
swap(old_top, new_top, 'native BoxProxy title')

# 3) Hero status/time baseline separation to eliminate glyph collision.
old_hero_line = '''                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Box(Modifier.size(9.dp).background(if (state.running) t.success else t.danger, CircleShape))
                                Text(if (state.running) "运行中" else "已停止", color = t.textPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                                Text(if (state.running) refDuration(runtime.elapsedSeconds) else "等待启动", color = t.textMuted, fontSize = 11.sp)
                            }
'''
new_hero_line = '''                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(9.dp).background(if (state.running) t.success else t.danger, CircleShape))
                                Spacer(Modifier.width(9.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Text(
                                        if (state.running) "运行中" else "已停止",
                                        color = t.textPrimary,
                                        fontSize = 20.sp,
                                        lineHeight = 24.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        modifier = Modifier.alignByBaseline(),
                                    )
                                    Text(
                                        if (state.running) refDuration(runtime.elapsedSeconds) else "等待启动",
                                        color = t.textMuted,
                                        fontSize = 11.sp,
                                        lineHeight = 15.sp,
                                        fontWeight = FontWeight.Medium,
                                        modifier = Modifier.alignByBaseline(),
                                    )
                                }
                            }
'''
swap(old_hero_line, new_hero_line, 'Hero status typography')

# 4) Latency numbers: 16sp / 700.
s = s.replace('fontWeight = FontWeight.ExtraBold,\n            maxLines = 1,\n        )\n    }\n}\n\n@Composable\nprivate fun RefNetworkIdentityCard',
              'fontWeight = FontWeight.Bold,\n            maxLines = 1,\n        )\n    }\n}\n\n@Composable\nprivate fun RefNetworkIdentityCard', 1)

# 5) Rebalance the four dashboard cards.
new_network = '''@Composable
private fun RefNetworkIdentityCard(runtime: ProxyRuntimeSnapshot, connections: Int, modifier: Modifier) {
    val t = LocalHetuTokens.current
    var lanMode by rememberSaveable { mutableStateOf(true) }
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) .97f else 1f, spring(dampingRatio = .78f, stiffness = 520f), label = "networkCardPress")
    val shape = RoundedCornerShape(18.dp)
    Surface(
        modifier = modifier
            .height(96.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale; alpha = if (pressed) .90f else 1f }
            .clip(shape)
            .clickable(interactionSource = source, indication = null) { lanMode = !lanMode },
        shape = shape,
        color = t.cardBackground,
        shadowElevation = 0.dp,
    ) {
        Column(
            Modifier.fillMaxSize().padding(13.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(if (lanMode) "LAN" else "WAN", color = Color(0xFF94A3B8), fontSize = 12.sp, fontWeight = FontWeight.Medium)
            Text(
                if (lanMode) runtime.lanAddress else runtime.wanAddress,
                color = if (MaterialTheme.colorScheme.background.luminance() < .5f) t.textPrimary else Color(0xFF0F172A),
                fontSize = 18.sp,
                lineHeight = 22.sp,
                fontWeight = FontWeight.ExtraBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                if (lanMode) "${runtime.lanInterface} · $connections 连接" else "${countryEmoji(runtime.wanCountryCode)} ${runtime.wanRegion}",
                color = Color(0xFF94A3B8),
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

'''
replace_section('@Composable\nprivate fun RefNetworkIdentityCard', '@Composable\nprivate fun RefSpeedCard', new_network, 'LAN/WAN dashboard card')

new_speed = '''@Composable
private fun RefSpeedCard(up: Long, down: Long, modifier: Modifier) {
    val t = LocalHetuTokens.current
    val valueColor = if (MaterialTheme.colorScheme.background.luminance() < .5f) t.textPrimary else Color(0xFF0F172A)
    Surface(modifier = modifier.height(96.dp), shape = RoundedCornerShape(18.dp), color = t.cardBackground, shadowElevation = 0.dp) {
        Column(Modifier.fillMaxSize().padding(13.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Text("网速", color = Color(0xFF94A3B8), fontSize = 12.sp, fontWeight = FontWeight.Medium)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.ArrowUpward, null, tint = t.success, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(5.dp))
                Text("上行", color = Color(0xFF94A3B8), fontSize = 11.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.weight(1f))
                Text(refSpeed(up), color = valueColor, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.ArrowDownward, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(5.dp))
                Text("下行", color = Color(0xFF94A3B8), fontSize = 11.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.weight(1f))
                Text(refSpeed(down), color = valueColor, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            }
        }
    }
}

'''
replace_section('@Composable\nprivate fun RefSpeedCard', '@Composable\nprivate fun RefSubscriptionCompact', new_speed, 'balanced speed card')

new_sub = '''@Composable
private fun RefSubscriptionCompact(items: List<DashboardProviderUi>, modifier: Modifier) {
    val t = LocalHetuTokens.current
    val tracked = items.filter { it.hasSubscriptionInfo && it.total > 0L }
    val used = tracked.sumOf { it.used }
    val total = tracked.sumOf { it.total }
    val ratio = if (total <= 0L) 0f else (used.toDouble() / total.toDouble()).toFloat().coerceIn(0f, 1f)
    Surface(modifier = modifier.height(96.dp), shape = RoundedCornerShape(18.dp), color = t.cardBackground, shadowElevation = 0.dp) {
        Column(Modifier.fillMaxSize().padding(13.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("订阅", color = Color(0xFF94A3B8), fontSize = 12.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                if (total > 0L) Surface(shape = RoundedCornerShape(999.dp), color = t.selectionBackground) {
                    Text("${((1f - ratio) * 100f).toInt()}%", Modifier.padding(horizontal = 7.dp, vertical = 2.dp), color = MaterialTheme.colorScheme.primary, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            Text(
                if (total > 0L) refBytes(used) else "${items.size} 个",
                color = if (MaterialTheme.colorScheme.background.luminance() < .5f) t.textPrimary else Color(0xFF0F172A),
                fontSize = 18.sp,
                lineHeight = 22.sp,
                fontWeight = FontWeight.ExtraBold,
                maxLines = 1,
            )
            Text(if (total > 0L) "总 ${refBytes(total)}" else "远程订阅", color = Color(0xFF94A3B8), fontSize = 11.sp, fontWeight = FontWeight.Medium, maxLines = 1)
        }
    }
}

'''
replace_section('@Composable\nprivate fun RefSubscriptionCompact', '@Composable\nprivate fun RefResourceCard', new_sub, 'subscription typography')

new_resource = '''@Composable
private fun RefResourceCard(memory: Long, cpuPercent: Float, modifier: Modifier) {
    val t = LocalHetuTokens.current
    val valueColor = if (MaterialTheme.colorScheme.background.luminance() < .5f) t.textPrimary else Color(0xFF0F172A)
    Surface(modifier = modifier.height(96.dp), shape = RoundedCornerShape(18.dp), color = t.cardBackground, shadowElevation = 0.dp) {
        Column(Modifier.fillMaxSize().padding(13.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Text("资源占用", color = Color(0xFF94A3B8), fontSize = 12.sp, fontWeight = FontWeight.Medium)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    Text(refBytes(memory), color = valueColor, fontSize = 16.sp, lineHeight = 20.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
                    Text("内存", color = Color(0xFF94A3B8), fontSize = 11.sp, fontWeight = FontWeight.Medium)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    Text(String.format(java.util.Locale.US, "%.1f%%", cpuPercent), color = valueColor, fontSize = 16.sp, lineHeight = 20.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
                    Text("CPU", color = Color(0xFF94A3B8), fontSize = 11.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}

'''
replace_section('@Composable\nprivate fun RefResourceCard', 'private fun countryEmoji', new_resource, 'resource typography')

# 6) Remove every centered AlertDialog in this workspace.
old_log = '''    logText?.let { text ->
        AlertDialog(
            onDismissRequest = { logText = null },
            shape = RoundedCornerShape(20.dp),
            title = { Text("运行日志") },
            text = { Text(text, style = MaterialTheme.typography.bodySmall, maxLines = 24, overflow = TextOverflow.Ellipsis) },
            confirmButton = { TextButton(onClick = { logText = null }) { Text("关闭") } },
        )
    }
'''
new_log = '''    logText?.let { text ->
        RefInfoBottomSheet(
            title = "运行日志",
            text = text,
            actionLabel = "关闭",
            onDismiss = { logText = null },
        )
    }
'''
swap(old_log, new_log, 'runtime log bottom sheet')

old_unavailable = '''    unavailable?.let { text ->
        AlertDialog(
            onDismissRequest = { unavailable = null },
            title = { Text("功能尚未接入") },
            text = { Text(text) },
            confirmButton = { TextButton(onClick = { unavailable = null }) { Text("知道了") } },
        )
    }
'''
new_unavailable = '''    unavailable?.let { text ->
        RefInfoBottomSheet(
            title = "功能尚未接入",
            text = text,
            actionLabel = "知道了",
            onDismiss = { unavailable = null },
        )
    }
'''
swap(old_unavailable, new_unavailable, 'tool notice bottom sheet')

new_settings = '''@Composable
private fun RefSettings(state: ProxyComposeState, onChanged: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("hetu", 0) }
    var modePicker by remember { mutableStateOf(false) }
    var ipv6Picker by remember { mutableStateOf(false) }
    var portsInfo by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }

    val t = LocalHetuTokens.current
    val dark = MaterialTheme.colorScheme.background.luminance() < .5f
    LazyColumn(
        Modifier.fillMaxSize().background(if (dark) t.pageBackground else Color(0xFFF4F6F9)),
        contentPadding = PaddingValues(
            start = 16.dp,
            top = 8.dp,
            end = 16.dp,
            bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 132.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { RefTitleBar("设置") }
        item { RefSectionLabel("核心与运行") }
        item {
            RefGroup {
                RefValueRow("核心选择", state.core, Icons.Rounded.Memory, Color(0xFF334155)) { context.startActivity(Intent(context, ProxyCoreActivity::class.java)) }
                RefDivider()
                RefValueRow("运行模式", state.mode, Icons.Rounded.Tune, Color(0xFF2563EB)) { modePicker = true }
                RefDivider()
                RefValueRow("IPv6", state.ipv6, Icons.Rounded.Public, Color(0xFF10B981)) { ipv6Picker = true }
            }
        }
        item { RefSectionLabel("网络与配置") }
        item {
            RefGroup {
                RefValueRow("端口细则", "TProxy ${MihomoStartupConfig.TPROXY_PORT} · Redir ${MihomoStartupConfig.REDIRECT_PORT}", Icons.Rounded.Hub, Color(0xFFF97316)) { portsInfo = true }
                RefDivider()
                RefValueRow("当前配置", state.config, Icons.Rounded.Description, Color(0xFF8B5CF6)) { context.startActivity(Intent(context, ProxySubscriptionActivity::class.java)) }
            }
        }
        item { RefSectionLabel("界面") }
        item {
            RefGroup {
                RefValueRow("主题与界面", "Miuix · Material · Monet · OLED", Icons.Rounded.Palette, Color(0xFFEC4899)) { context.startActivity(Intent(context, ThemeSettingsActivity::class.java)) }
                RefDivider()
                RefValueRow("WebUI 面板", "本地 Zashboard · 同源控制器", Icons.Rounded.Language, Color(0xFF3B82F6)) { context.startActivity(Intent(context, ProxyWebUiActivity::class.java)) }
            }
        }
        item { RefSectionLabel("管理") }
        item {
            RefGroup {
                RefValueRow("文件管理", "代理运行目录", Icons.Rounded.Folder, Color(0xFFF59E0B)) { context.startActivity(Intent(context, ReferenceFileManagerActivity::class.java)) }
                RefDivider()
                RefValueRow("订阅与配置", "链接 · User-Agent · 更新", Icons.Rounded.CloudDownload, Color(0xFF0EA5E9)) { context.startActivity(Intent(context, ProxySubscriptionActivity::class.java)) }
            }
        }
    }

    if (modePicker) {
        val profile = ProxyRuntimeProfile.load(prefs)
        val choices = ProxyRuntimeProfile.Mode.values().filter { ProxyRuntimeProfile.capability(profile.core, it).available }
        RefChoiceBottomSheet(
            title = "运行模式",
            options = choices.map { it.label to (profile.mode == it) },
            onDismiss = { modePicker = false },
            onSelect = { index ->
                val mode = choices[index]
                prefs.edit().putString("proxyBaseMode", mode.id).apply()
                modePicker = false
                notice = if (state.running) "运行模式已保存，重启代理后生效" else "运行模式已保存"
                onChanged()
            },
        )
    }

    if (ipv6Picker) {
        val profile = ProxyRuntimeProfile.load(prefs)
        val values = listOf(
            ProxyRuntimeProfile.Ipv6.ENABLE to "启用 IPv6",
            ProxyRuntimeProfile.Ipv6.BYPASS to "IPv6 不进核心",
            ProxyRuntimeProfile.Ipv6.DISABLE to "禁用系统 IPv6",
        )
        RefChoiceBottomSheet(
            title = "IPv6",
            options = values.map { (value, label) -> label to (profile.ipv6 == value) },
            onDismiss = { ipv6Picker = false },
            onSelect = { index ->
                val value = values[index].first
                prefs.edit().putString("proxyBaseIpv6", value.id).apply()
                ipv6Picker = false
                notice = if (state.running) "IPv6 设置已保存，重启代理后生效" else "IPv6 设置已保存"
                onChanged()
            },
        )
    }

    if (portsInfo) {
        RefInfoBottomSheet(
            title = "端口细则",
            text = "TProxy：${MihomoStartupConfig.TPROXY_PORT}\n" +
                "Redirect：${MihomoStartupConfig.REDIRECT_PORT}\n" +
                "控制器：127.0.0.1:${MihomoStartupConfig.CONTROLLER_PORT}\n\n" +
                "这些是河图运行副本使用的安全端口。源订阅文件不会被直接修改。",
            actionLabel = "关闭",
            onDismiss = { portsInfo = false },
        )
    }

    notice?.let { text ->
        RefInfoBottomSheet(
            title = "设置已保存",
            text = text,
            actionLabel = "知道了",
            onDismiss = { notice = null },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RefChoiceBottomSheet(
    title: String,
    options: List<Pair<String, Boolean>>,
    onDismiss: () -> Unit,
    onSelect: (Int) -> Unit,
) {
    val t = LocalHetuTokens.current
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
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(title, color = t.textPrimary, fontSize = 20.sp, lineHeight = 25.sp, fontWeight = FontWeight.Bold)
            options.forEachIndexed { index, option ->
                val selected = option.second
                val shape = RoundedCornerShape(15.dp)
                Row(
                    Modifier.fillMaxWidth()
                        .clip(shape)
                        .background(if (selected) t.selectionBackground else t.controlBackground.copy(alpha = .42f), shape)
                        .clickable { onSelect(index) }
                        .padding(horizontal = 14.dp, vertical = 13.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(option.first, color = t.textPrimary, fontSize = 15.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium, modifier = Modifier.weight(1f))
                    if (selected) Icon(Icons.Rounded.Check, "已选择", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RefInfoBottomSheet(
    title: String,
    text: String,
    actionLabel: String,
    onDismiss: () -> Unit,
) {
    val t = LocalHetuTokens.current
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
                    .background(t.controlBackground.copy(alpha = .54f), RoundedCornerShape(16.dp))
                    .padding(14.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(text, color = t.textSecondary, fontSize = 13.sp, lineHeight = 20.sp)
            }
            Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth().height(50.dp), shape = RoundedCornerShape(14.dp)) {
                Text(actionLabel, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun RefSheetDragHandle() {
    Box(
        Modifier.padding(top = 10.dp, bottom = 6.dp)
            .size(width = 36.dp, height = 4.dp)
            .background(Color(0xFFCBD5E1), CircleShape),
    )
}

'''
replace_section('@Composable\nprivate fun RefSettings', '@Composable\nprivate fun RefGroup', new_settings, 'settings bottom sheets')

if 'AlertDialog(' in s:
    raise SystemExit('centered AlertDialog still present in ReferenceProxyActivity')

PATH.write_text(s)
print('test31 proxy UI refinement complete; HetuGlassDock.kt untouched')
