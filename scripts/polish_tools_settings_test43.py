from pathlib import Path

p = Path('android-app/app/src/main/java/io/github/xgl34222220/bichen/ReferenceProxyActivity.kt')
s = p.read_text()


def replace_between(start_marker: str, end_marker: str, replacement: str):
    global s
    a = s.index(start_marker)
    b = s.index(end_marker, a)
    s = s[:a] + replacement.rstrip() + '\n\n' + s[b:]


tools = r'''@Composable
private fun RefTools(state: ProxyComposeState, onLog: (String) -> Unit) {
    val context = LocalContext.current
    val inspector = remember { ProxyRuntimeInspector(context) }
    val scope = rememberCoroutineScope()
    val t = LocalBichenTokens.current
    val dark = MaterialTheme.colorScheme.background.luminance() < .5f

    fun unavailable(message: String) {
        android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_SHORT).show()
    }

    LazyColumn(
        Modifier.fillMaxSize().background(if (dark) t.pageBackground else Color(0xFFF1F5F9)),
        contentPadding = PaddingValues(
            start = 16.dp,
            top = 8.dp,
            end = 16.dp,
            bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 132.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { RefTitleBar("工具") }
        item { RefSectionLabel("系统服务") }
        item {
            RefGroup {
                RefToolRow(
                    Icons.Rounded.Terminal,
                    Color(0xFF2563EB),
                    "脚本",
                    "服务脚本管理与执行",
                    trailingText = if (state.running) "运行中" else "待机",
                    trailingBadge = true,
                    trailingColor = if (state.running) Color(0xFF059669) else Color(0xFF64748B),
                ) { context.startActivity(Intent(context, ReferenceFileManagerActivity::class.java)) }
                RefDivider()
                RefToolRow(Icons.Rounded.Article, Color(0xFF9333EA), "日志查看", "查看实时运行日志与调试") {
                    scope.launch { onLog(runCatching { inspector.runtimeLog() }.getOrElse { it.message ?: "日志读取失败" }) }
                }
                RefDivider()
                RefToolRow(Icons.Rounded.Apps, Color(0xFFF97316), "应用管理", "分应用放行与代理相关应用", trailingText = "管理", trailingColor = Color(0xFF2563EB)) {
                    context.startActivity(Intent(context, CompactMainActivity::class.java))
                }
            }
        }
        item { RefSectionLabel("网络与共享") }
        item {
            RefGroup {
                RefToolRow(Icons.Rounded.Wifi, Color(0xFF0EA5E9), "网络匹配", "按 Wi‑Fi / SSID 自动匹配", trailingText = "待接入", trailingBadge = true, trailingColor = Color(0xFFD97706)) {
                    unavailable("网络匹配后端尚未接入")
                }
                RefDivider()
                RefToolRow(Icons.Rounded.WifiTethering, Color(0xFF10B981), "共享网络", "热点与局域网共享", trailingText = "待接入", trailingBadge = true, trailingColor = Color(0xFFD97706)) {
                    unavailable("共享网络控制后端尚未接入")
                }
                RefDivider()
                RefToolRow(Icons.Rounded.AltRoute, Color(0xFFEF4444), "绕过规则", "CIDR 与接口绕过", trailingText = "待接入", trailingBadge = true, trailingColor = Color(0xFFD97706)) {
                    unavailable("自定义绕过规则后端尚未接入")
                }
            }
        }
        item { RefSectionLabel("订阅与数据") }
        item {
            RefGroup {
                RefToolRow(Icons.Rounded.CloudDownload, Color(0xFF2563EB), "订阅管理", "链接 · User-Agent · 更新", trailingText = "管理", trailingColor = Color(0xFF2563EB)) {
                    context.startActivity(Intent(context, ProxySubscriptionActivity::class.java))
                }
                RefDivider()
                RefToolRow(Icons.Rounded.Public, Color(0xFFF97316), "CNIP 设置", "国内 IP 数据与分流", trailingText = "待接入", trailingBadge = true, trailingColor = Color(0xFFD97706)) {
                    unavailable("CNIP 下载源和运行时应用后端尚未接入")
                }
            }
        }
        item { RefSectionLabel("核心与更新") }
        item {
            RefGroup {
                RefToolRow(Icons.Rounded.Language, Color(0xFF2563EB), "更新 WebUI", "Zashboard · MetaCubeXD", trailingText = "更新", trailingColor = Color(0xFF2563EB)) {
                    context.startActivity(Intent(context, ProxyWebUiActivity::class.java))
                }
                RefDivider()
                RefToolRow(Icons.Rounded.Memory, Color(0xFF334155), "更新核心", "下载并安装内核二进制", trailingText = state.core.ifBlank { "Mihomo" }, trailingColor = Color(0xFF2563EB)) {
                    context.startActivity(Intent(context, ProxyCoreActivity::class.java))
                }
            }
        }
    }
}'''

settings = r'''@Composable
private fun RefSettings(state: ProxyComposeState, onChanged: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("bichen", 0) }
    var modePicker by remember { mutableStateOf(false) }
    var ipv6Picker by remember { mutableStateOf(false) }
    var portsInfo by remember { mutableStateOf(false) }
    var autoStart by remember { mutableStateOf(prefs.getBoolean("autoStartVpn", false)) }
    var blurEnabled by remember { mutableStateOf(prefs.getBoolean("enableBlur", true)) }

    val t = LocalBichenTokens.current
    val dark = MaterialTheme.colorScheme.background.luminance() < .5f
    LazyColumn(
        Modifier.fillMaxSize().background(if (dark) t.pageBackground else Color(0xFFF1F5F9)),
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
                RefValueRow("核心选择", state.core, Icons.Rounded.Memory, Color(0xFF334155), highlightValue = true) {
                    context.startActivity(Intent(context, ProxyCoreActivity::class.java))
                }
                RefDivider()
                RefValueRow("运行模式", state.mode, Icons.Rounded.Tune, Color(0xFF2563EB), highlightValue = true) { modePicker = true }
                RefDivider()
                RefValueRow("IPv6", state.ipv6, Icons.Rounded.Public, Color(0xFF10B981), highlightValue = true) { ipv6Picker = true }
                RefDivider()
                RefSwitchRow(
                    icon = Icons.Rounded.Bolt,
                    accent = Color(0xFF2563EB),
                    title = "开机自启",
                    subtitle = "重启后自动恢复上次启用的代理保护",
                    checked = autoStart,
                ) { enabled ->
                    autoStart = enabled
                    prefs.edit().putBoolean("autoStartVpn", enabled).apply()
                }
            }
        }
        item { RefSectionLabel("网络与配置") }
        item {
            RefGroup {
                RefValueRow(
                    "端口细则",
                    "${MihomoStartupConfig.TPROXY_PORT} / ${MihomoStartupConfig.REDIRECT_PORT}",
                    Icons.Rounded.Hub,
                    Color(0xFFF97316),
                    highlightValue = true,
                ) { portsInfo = true }
                RefDivider()
                RefValueRow("当前配置", state.config, Icons.Rounded.Description, Color(0xFF8B5CF6), highlightValue = true) {
                    context.startActivity(Intent(context, ProxySubscriptionActivity::class.java))
                }
            }
        }
        item { RefSectionLabel("界面") }
        item {
            RefGroup {
                RefSwitchRow(
                    icon = Icons.Rounded.BlurOn,
                    accent = Color(0xFF8B5CF6),
                    title = "模糊效果",
                    subtitle = "控制应用内磨砂与背景模糊",
                    checked = blurEnabled,
                ) { enabled ->
                    blurEnabled = enabled
                    prefs.edit().putBoolean("enableBlur", enabled).apply()
                }
                RefDivider()
                RefValueRow("主题与界面", "Miuix · Monet · OLED", Icons.Rounded.Palette, Color(0xFFEC4899)) {
                    context.startActivity(Intent(context, ThemeSettingsActivity::class.java))
                }
                RefDivider()
                RefValueRow("WebUI 面板", "本地", Icons.Rounded.Language, Color(0xFF2563EB), highlightValue = true) {
                    context.startActivity(Intent(context, ProxyWebUiActivity::class.java))
                }
            }
        }
        item { RefSectionLabel("管理") }
        item {
            RefGroup {
                RefValueRow("文件管理", "代理运行目录", Icons.Rounded.Folder, Color(0xFFF59E0B)) {
                    context.startActivity(Intent(context, ReferenceFileManagerActivity::class.java))
                }
                RefDivider()
                RefValueRow("订阅与配置", "链接 · 更新", Icons.Rounded.CloudDownload, Color(0xFF2563EB)) {
                    context.startActivity(Intent(context, ProxySubscriptionActivity::class.java))
                }
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
                android.widget.Toast.makeText(
                    context,
                    if (state.running) "运行模式已保存，重启代理后生效" else "运行模式已保存",
                    android.widget.Toast.LENGTH_SHORT,
                ).show()
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
                android.widget.Toast.makeText(
                    context,
                    if (state.running) "IPv6 设置已保存，重启代理后生效" else "IPv6 设置已保存",
                    android.widget.Toast.LENGTH_SHORT,
                ).show()
                onChanged()
            },
        )
    }

    if (portsInfo) {
        RefPortsBottomSheet(onDismiss = { portsInfo = false })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RefPortsBottomSheet(onDismiss: () -> Unit) {
    val t = LocalBichenTokens.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp),
        containerColor = t.elevatedCardBackground,
        contentColor = t.textPrimary,
        tonalElevation = 0.dp,
        scrimColor = Color.Black.copy(alpha = .32f),
        dragHandle = { RefSheetDragHandle() },
    ) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 18.dp, end = 18.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("端口与控制器细则", color = t.textPrimary, fontSize = 20.sp, lineHeight = 25.sp, fontWeight = FontWeight.Black)
            Text("当前代理运行时副本使用的网络端口与外部控制接口", color = t.textSecondary, fontSize = 12.sp, lineHeight = 18.sp)
            Surface(shape = RoundedCornerShape(18.dp), color = t.controlBackground.copy(alpha = .58f), tonalElevation = 0.dp) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp)) {
                    RefPortDetailRow("TProxy 端口", MihomoStartupConfig.TPROXY_PORT.toString())
                    HorizontalDivider(color = t.outline.copy(alpha = .32f))
                    RefPortDetailRow("Redirect 端口", MihomoStartupConfig.REDIRECT_PORT.toString())
                    HorizontalDivider(color = t.outline.copy(alpha = .32f))
                    RefPortDetailRow("外部控制器", "127.0.0.1:${MihomoStartupConfig.CONTROLLER_PORT}", Color(0xFF2563EB))
                }
            }
            FilledTonalButton(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = CircleShape,
                colors = ButtonDefaults.filledTonalButtonColors(containerColor = t.controlBackground, contentColor = t.textPrimary),
            ) {
                Text("完成", fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun RefPortDetailRow(label: String, value: String, valueColor: Color = LocalBichenTokens.current.textPrimary) {
    val t = LocalBichenTokens.current
    Row(Modifier.fillMaxWidth().padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = t.textSecondary, fontSize = 12.sp, modifier = Modifier.weight(1f))
        Text(value, color = valueColor, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}'''

helpers = r'''@Composable
private fun RefGradientIcon(icon: ImageVector, accent: Color) {
    val shape = RoundedCornerShape(11.dp)
    val brush = Brush.linearGradient(
        listOf(
            accent.copy(alpha = .76f),
            accent,
        ),
    )
    Box(
        Modifier.size(36.dp)
            .shadow(7.dp, shape, clip = false, ambientColor = accent.copy(alpha = .24f), spotColor = accent.copy(alpha = .28f))
            .background(brush, shape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun RefToolRow(
    icon: ImageVector,
    accent: Color,
    title: String,
    subtitle: String,
    trailingText: String = "",
    trailingBadge: Boolean = false,
    trailingColor: Color = Color(0xFF2563EB),
    onClick: () -> Unit,
) {
    val t = LocalBichenTokens.current
    val source = remember(title) { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) .985f else 1f, spring(dampingRatio = .78f, stiffness = 560f), label = "tool$title")
    Row(
        Modifier.fillMaxWidth()
            .graphicsLayer { scaleX = scale; scaleY = scale; alpha = if (pressed) .91f else 1f }
            .background(if (pressed) t.controlBackground.copy(alpha = .42f) else Color.Transparent)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RefGradientIcon(icon, accent)
        Spacer(Modifier.width(13.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, color = t.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Text(subtitle, color = Color(0xFF94A3B8), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(8.dp))
        if (trailingText.isNotBlank()) {
            if (trailingBadge) {
                Surface(shape = CircleShape, color = trailingColor.copy(alpha = .10f), tonalElevation = 0.dp) {
                    Row(
                        Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        Box(Modifier.size(6.dp).background(trailingColor, CircleShape))
                        Text(trailingText, color = trailingColor, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            } else {
                Text(trailingText, color = trailingColor, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 92.dp))
                Spacer(Modifier.width(3.dp))
                Icon(Icons.Rounded.ChevronRight, null, tint = trailingColor.copy(alpha = .78f), modifier = Modifier.size(15.dp))
            }
        } else {
            Icon(Icons.Rounded.ChevronRight, null, tint = Color(0xFFCBD5E1), modifier = Modifier.size(17.dp))
        }
    }
}

@Composable
private fun RefValueRow(
    title: String,
    value: String,
    icon: ImageVector? = null,
    accent: Color = Color(0xFF64748B),
    highlightValue: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val t = LocalBichenTokens.current
    val source = remember(title) { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed && onClick != null) .985f else 1f, spring(dampingRatio = .80f, stiffness = 560f), label = "value$title")
    val modifier = if (onClick == null) Modifier.fillMaxWidth() else Modifier.fillMaxWidth()
        .graphicsLayer { scaleX = scale; scaleY = scale; alpha = if (pressed) .91f else 1f }
        .background(if (pressed) t.controlBackground.copy(alpha = .42f) else Color.Transparent)
        .clickable(interactionSource = source, indication = null, onClick = onClick)
    Row(modifier.padding(horizontal = 14.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) {
            RefGradientIcon(icon, accent)
            Spacer(Modifier.width(13.dp))
        }
        Text(title, color = t.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        if (value.isNotBlank()) {
            Text(
                value,
                color = if (highlightValue) Color(0xFF2563EB) else Color(0xFF64748B),
                fontSize = 11.sp,
                fontWeight = if (highlightValue) FontWeight.Bold else FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 138.dp),
            )
        }
        if (onClick != null) {
            Spacer(Modifier.width(5.dp))
            Icon(Icons.Rounded.ChevronRight, null, tint = if (highlightValue) Color(0xFF2563EB).copy(alpha = .78f) else Color(0xFFCBD5E1), modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
private fun RefSwitchRow(
    icon: ImageVector,
    accent: Color,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val t = LocalBichenTokens.current
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RefGradientIcon(icon, accent)
        Spacer(Modifier.width(13.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, color = t.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Text(subtitle, color = Color(0xFF94A3B8), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(10.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = Color(0xFF2563EB),
                uncheckedThumbColor = Color.White,
                uncheckedTrackColor = Color(0xFFE2E8F0),
                uncheckedBorderColor = Color.Transparent,
            ),
        )
    }
}'''

group = r'''@Composable
private fun RefGroup(content: @Composable ColumnScope.() -> Unit) {
    val t = LocalBichenTokens.current
    val dark = MaterialTheme.colorScheme.background.luminance() < .5f
    val shape = RoundedCornerShape(22.dp)
    val brush = if (dark) {
        Brush.verticalGradient(listOf(t.elevatedCardBackground, t.cardBackground))
    } else {
        Brush.verticalGradient(listOf(Color.White, Color(0xFFFAFBFD)))
    }
    Column(
        Modifier.fillMaxWidth()
            .shadow(if (dark) 0.dp else 5.dp, shape, clip = false, ambientColor = Color(0xFF0F172A).copy(alpha = .035f), spotColor = Color(0xFF0F172A).copy(alpha = .045f))
            .background(brush, shape)
            .border(.7.dp, if (dark) t.outline.copy(alpha = .45f) else Color.White.copy(alpha = .92f), shape)
            .clip(shape),
        content = content,
    )
}'''

replace_between('@Composable\nprivate fun RefTools(', '@Composable\nprivate fun RefSettings', tools)
replace_between('@Composable\nprivate fun RefSettings(', '@OptIn(ExperimentalMaterial3Api::class)\n@Composable\nprivate fun RefChoiceBottomSheet', settings)
replace_between('@Composable\nprivate fun RefGroup(', '@Composable\nprivate fun RefSectionLabel', group)
replace_between('@Composable\nprivate fun RefToolRow(', '@Composable\nprivate fun RefDivider', helpers)

# Hard invariants for this polish pass.
assert 'putBoolean("autoStartVpn"' in s
assert 'putBoolean("enableBlur"' in s
assert 'RefPortsBottomSheet' in s
assert 'android.widget.Toast.makeText' in s
assert 'Brush.linearGradient' in s
assert 'private fun RefGradientIcon' in s
assert 'private fun RefSwitchRow' in s
assert 'private fun RefToolRow' in s
assert 'private fun RefValueRow' in s
assert 'AlertDialog(' not in s

p.write_text(s)
print('polished tools/settings commercial native UI')
