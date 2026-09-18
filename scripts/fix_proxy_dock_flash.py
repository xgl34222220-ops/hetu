from pathlib import Path

ACTIVITY = Path('android-app/app/src/main/java/io/github/xgl34222220/hetu/ReferenceProxyActivity.kt')
DOCK = Path('android-app/app/src/main/java/io/github/xgl34222220/hetu/ui/HetuGlassDock.kt')


def swap(text: str, old: str, new: str, label: str) -> str:
    if old in text:
        return text.replace(old, new, 1)
    if new in text:
        print(f'{label}: already applied')
        return text
    raise SystemExit(f'missing exact UI block: {label}')


# 1) Bottom dock: restore LuoShu's current three-layer glass implementation exactly.
d = DOCK.read_text()
old_dock = '''            liquidGlass = activeGlass,
            // Keep LuoShu's outer RuntimeShader glass, but force the moving selection lens
            // through LuoShu's own non-nested fallback path. On some HyperOS/GPU stacks,
            // nesting drawBackdrop inside a layerBackdrop shell flashes a wide white strip
            // exactly when the indicator travels back to Home.
            indicatorBackdrop = null,
            dark = dark,
'''
new_dock = '''            liquidGlass = activeGlass,
            indicatorBackdrop = dockSurfaceBackdrop.takeIf { runtimeLiquid },
            dark = dark,
'''
d = swap(d, old_dock, new_dock, 'LuoShu moving glass lens')
DOCK.write_text(d)


s = ACTIVITY.read_text()

# 2) Match LuoShu page switching: only the destination page survives the transition.
if 'import androidx.compose.animation.core.Animatable\n' not in s:
    s = s.replace('import androidx.compose.animation.core.Spring\n', 'import androidx.compose.animation.core.Animatable\nimport androidx.compose.animation.core.Spring\n', 1)

old_shell_open = '''        ) {
            when (page) {
'''
new_shell_open = '''        ) {
            key(page) {
                val pageEnter = remember { Animatable(0f) }
                LaunchedEffect(Unit) {
                    pageEnter.animateTo(
                        targetValue = 1f,
                        animationSpec = spring(dampingRatio = .86f, stiffness = 430f),
                    )
                }
                Box(
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            alpha = 1f
                            translationY = (1f - pageEnter.value) * 10.dp.toPx()
                        },
                ) {
            when (page) {
'''
s = swap(s, old_shell_open, new_shell_open, 'LuoShu single-page transition')

old_shell_close = '''            }
        }
        HetuGlassDock(
'''
new_shell_close = '''            }
                }
            }
        }
        HetuGlassDock(
'''
s = swap(s, old_shell_close, new_shell_close, 'LuoShu page transition close')

old_bg = '    Box(Modifier.fillMaxSize().background(LocalHetuTokens.current.pageBackground)) {\n'
new_bg = '''    val shellBackground = if (MaterialTheme.colorScheme.background.luminance() < .5f) {
        LocalHetuTokens.current.pageBackground
    } else {
        Color(0xFFF4F6F9)
    }
    Box(Modifier.fillMaxSize().background(shellBackground)) {
'''
s = swap(s, old_bg, new_bg, 'commercial page background')
s = s.replace('Color(0xFFF1F5F9)', 'Color(0xFFF4F6F9)')

# All primary pages use the same 16dp horizontal content gutter.
s = swap(
    s,
    '''                start = 12.dp,
                top = 8.dp,
                end = 12.dp,
''',
    '''                start = 16.dp,
                top = 8.dp,
                end = 16.dp,
''',
    'panel 16dp gutters',
)

# 3) WebUI / Logs: 56dp horizontal compact cards.
old_small_tool = '''@Composable
private fun RefSmallTool(title: String, subtitle: String, icon: ImageVector, onClick: () -> Unit, modifier: Modifier) {
    val t = LocalHetuTokens.current
    val source = remember(title) { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) .97f else 1f, spring(dampingRatio = .78f, stiffness = 520f), label = "smallTool$title")
    val shape = RoundedCornerShape(16.dp)
    Surface(
        modifier = modifier.graphicsLayer { scaleX = scale; scaleY = scale }.clip(shape)
            .clickable(interactionSource = source, indication = null, onClick = onClick),
        shape = shape,
        color = t.cardBackground,
        shadowElevation = 0.dp,
    ) {
        Row(Modifier.padding(13.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(32.dp).background(MaterialTheme.colorScheme.primary.copy(alpha = .09f), RoundedCornerShape(11.dp)), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(9.dp))
            Column(Modifier.weight(1f)) {
                Text(title, color = t.textPrimary, style = MaterialTheme.typography.titleSmall)
                Text(subtitle, color = t.textSecondary, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
'''
new_small_tool = '''@Composable
private fun RefSmallTool(title: String, subtitle: String, icon: ImageVector, onClick: () -> Unit, modifier: Modifier) {
    val t = LocalHetuTokens.current
    val source = remember(title) { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) .98f else 1f, spring(dampingRatio = .78f, stiffness = 520f), label = "smallTool$title")
    val shape = RoundedCornerShape(16.dp)
    Surface(
        modifier = modifier
            .height(56.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale; alpha = if (pressed) .90f else 1f }
            .clip(shape)
            .clickable(interactionSource = source, indication = null, onClick = onClick),
        shape = shape,
        color = t.cardBackground,
        shadowElevation = 1.dp,
    ) {
        Row(
            Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(32.dp).background(MaterialTheme.colorScheme.primary.copy(alpha = .09f), RoundedCornerShape(11.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                Text(title, color = t.textPrimary, fontSize = 14.sp, lineHeight = 17.sp, fontWeight = FontWeight.Bold)
                Text(subtitle, color = Color(0xFF94A3B8), fontSize = 11.sp, lineHeight = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
'''
s = swap(s, old_small_tool, new_small_tool, '56dp WebUI/log cards')

# 4) Home latency values: no pill/background; color alone expresses health.
old_latency_column = '''@Composable
private fun RefLatencyColumn(label: String, value: Long?, testing: Boolean, modifier: Modifier) {
    val t = LocalHetuTokens.current
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(label, color = t.textSecondary, fontSize = 11.sp, lineHeight = 15.sp, maxLines = 1)
        RefDelayBadge(value = value, testing = testing, onClick = null)
    }
}
'''
new_latency_column = '''@Composable
private fun RefLatencyColumn(label: String, value: Long?, testing: Boolean, modifier: Modifier) {
    val t = LocalHetuTokens.current
    val valueColor = when {
        testing -> MaterialTheme.colorScheme.primary
        value == null -> Color(0xFF94A3B8)
        value <= 0L || value > 300L -> Color(0xFFDC2626)
        value < 100L -> Color(0xFF059669)
        else -> Color(0xFFD97706)
    }
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(label, color = t.textSecondary, fontSize = 11.sp, lineHeight = 15.sp, maxLines = 1)
        Text(
            if (testing) "…" else refDelay(value),
            color = valueColor,
            fontSize = 16.sp,
            lineHeight = 20.sp,
            fontWeight = FontWeight.ExtraBold,
            maxLines = 1,
        )
    }
}
'''
s = swap(s, old_latency_column, new_latency_column, 'naked latency values')

# 5) 2x2 dashboard: fixed 96dp height and tighter typography.
s = s.replace('modifier = modifier.graphicsLayer { scaleX = scale; scaleY = scale; alpha = if (pressed) .90f else 1f }.clip(shape)',
              'modifier = modifier.height(96.dp).graphicsLayer { scaleX = scale; scaleY = scale; alpha = if (pressed) .90f else 1f }.clip(shape)', 1)

# LAN/WAN large address type.
s = s.replace('fontSize = 19.sp, lineHeight = 23.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)',
              'fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)', 2)

old_speed = '''@Composable
private fun RefSpeedCard(up: Long, down: Long, modifier: Modifier) {
    val t = LocalHetuTokens.current
    Surface(modifier = modifier, shape = RoundedCornerShape(18.dp), color = t.cardBackground, shadowElevation = 0.dp) {
        Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("网速", color = Color(0xFF94A3B8), fontSize = 12.sp, fontWeight = FontWeight.Medium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.ArrowUpward, null, tint = t.success, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(5.dp))
                Text(refSpeed(up), color = if (MaterialTheme.colorScheme.background.luminance() < .5f) t.textPrimary else Color(0xFF0F172A), fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.ArrowDownward, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(5.dp))
                Text(refSpeed(down), color = if (MaterialTheme.colorScheme.background.luminance() < .5f) t.textPrimary else Color(0xFF0F172A), fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            }
        }
    }
}
'''
new_speed = '''@Composable
private fun RefSpeedCard(up: Long, down: Long, modifier: Modifier) {
    val t = LocalHetuTokens.current
    Surface(modifier = modifier.height(96.dp), shape = RoundedCornerShape(18.dp), color = t.cardBackground, shadowElevation = 0.dp) {
        Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text("网速", color = Color(0xFF94A3B8), fontSize = 12.sp, fontWeight = FontWeight.Medium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.ArrowUpward, null, tint = t.success, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(5.dp))
                Text(refSpeed(up), color = if (MaterialTheme.colorScheme.background.luminance() < .5f) t.textPrimary else Color(0xFF0F172A), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.ArrowDownward, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(5.dp))
                Text(refSpeed(down), color = if (MaterialTheme.colorScheme.background.luminance() < .5f) t.textPrimary else Color(0xFF0F172A), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            }
        }
    }
}
'''
s = swap(s, old_speed, new_speed, '96dp speed card')

old_sub_compact = '''@Composable
private fun RefSubscriptionCompact(items: List<DashboardProviderUi>, modifier: Modifier) {
    val t = LocalHetuTokens.current
    val tracked = items.filter { it.hasSubscriptionInfo && it.total > 0L }
    val used = tracked.sumOf { it.used }
    val total = tracked.sumOf { it.total }
    val remain = (total - used).coerceAtLeast(0L)
    val ratio = if (total <= 0L) 0f else (used.toDouble() / total.toDouble()).toFloat().coerceIn(0f, 1f)
    Surface(modifier = modifier, shape = RoundedCornerShape(18.dp), color = t.cardBackground, shadowElevation = 0.dp) {
        Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("订阅", color = Color(0xFF94A3B8), fontSize = 12.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                if (total > 0L) Surface(shape = RoundedCornerShape(999.dp), color = t.selectionBackground) {
                    Text("${((1f - ratio) * 100f).toInt()}%", Modifier.padding(horizontal = 7.dp, vertical = 2.dp), color = MaterialTheme.colorScheme.primary, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            Text(if (total > 0L) refBytes(remain) else "${items.size} 个", color = if (MaterialTheme.colorScheme.background.luminance() < .5f) t.textPrimary else Color(0xFF0F172A), fontSize = 19.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            Text(if (total > 0L) "剩余 · 已用 ${refBytes(used)}" else "远程订阅", color = Color(0xFF94A3B8), fontSize = 11.sp, maxLines = 1)
            if (total > 0L) LinearProgressIndicator(progress = { ratio }, modifier = Modifier.fillMaxWidth().height(4.dp).clip(CircleShape))
        }
    }
}
'''
new_sub_compact = '''@Composable
private fun RefSubscriptionCompact(items: List<DashboardProviderUi>, modifier: Modifier) {
    val t = LocalHetuTokens.current
    val tracked = items.filter { it.hasSubscriptionInfo && it.total > 0L }
    val used = tracked.sumOf { it.used }
    val total = tracked.sumOf { it.total }
    val ratio = if (total <= 0L) 0f else (used.toDouble() / total.toDouble()).toFloat().coerceIn(0f, 1f)
    Surface(modifier = modifier.height(96.dp), shape = RoundedCornerShape(18.dp), color = t.cardBackground, shadowElevation = 0.dp) {
        Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("订阅", color = Color(0xFF94A3B8), fontSize = 12.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                if (total > 0L) Surface(shape = RoundedCornerShape(999.dp), color = t.selectionBackground) {
                    Text("${((1f - ratio) * 100f).toInt()}%", Modifier.padding(horizontal = 7.dp, vertical = 2.dp), color = MaterialTheme.colorScheme.primary, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            Text(
                if (total > 0L) refBytes(used) else "${items.size} 个",
                color = if (MaterialTheme.colorScheme.background.luminance() < .5f) t.textPrimary else Color(0xFF0F172A),
                fontSize = 17.sp,
                lineHeight = 21.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
            )
            Text(if (total > 0L) "总 ${refBytes(total)}" else "远程订阅", color = Color(0xFF94A3B8), fontSize = 11.sp, maxLines = 1)
        }
    }
}
'''
s = swap(s, old_sub_compact, new_sub_compact, '96dp subscription card')

old_resource = '''@Composable
private fun RefResourceCard(memory: Long, cpuPercent: Float, modifier: Modifier) {
    val t = LocalHetuTokens.current
    Surface(modifier = modifier, shape = RoundedCornerShape(18.dp), color = t.cardBackground, shadowElevation = 0.dp) {
        Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("资源占用", color = Color(0xFF94A3B8), fontSize = 12.sp, fontWeight = FontWeight.Medium)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    Text(refBytes(memory), color = if (MaterialTheme.colorScheme.background.luminance() < .5f) t.textPrimary else Color(0xFF0F172A), fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("内存", color = Color(0xFF94A3B8), fontSize = 11.sp)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    Text(String.format(java.util.Locale.US, "%.1f%%", cpuPercent), color = if (MaterialTheme.colorScheme.background.luminance() < .5f) t.textPrimary else Color(0xFF0F172A), fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                    Text("CPU", color = Color(0xFF94A3B8), fontSize = 11.sp)
                }
            }
        }
    }
}
'''
new_resource = '''@Composable
private fun RefResourceCard(memory: Long, cpuPercent: Float, modifier: Modifier) {
    val t = LocalHetuTokens.current
    Surface(modifier = modifier.height(96.dp), shape = RoundedCornerShape(18.dp), color = t.cardBackground, shadowElevation = 0.dp) {
        Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("资源占用", color = Color(0xFF94A3B8), fontSize = 12.sp, fontWeight = FontWeight.Medium)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    Text(refBytes(memory), color = if (MaterialTheme.colorScheme.background.luminance() < .5f) t.textPrimary else Color(0xFF0F172A), fontSize = 15.sp, lineHeight = 19.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    Text("内存", color = Color(0xFF94A3B8), fontSize = 11.sp)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    Text(String.format(java.util.Locale.US, "%.1f%%", cpuPercent), color = if (MaterialTheme.colorScheme.background.luminance() < .5f) t.textPrimary else Color(0xFF0F172A), fontSize = 15.sp, lineHeight = 19.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    Text("CPU", color = Color(0xFF94A3B8), fontSize = 11.sp)
                }
            }
        }
    }
}
'''
s = swap(s, old_resource, new_resource, '96dp resource card')

# 6) Strategy cards: fixed 92dp and compact top/middle/bottom hierarchy.
old_group = '''@Composable
private fun RefGroupCard(group: ProxyGroupUi, selected: String, expanded: Boolean, delay: Long?, modifier: Modifier, onClick: () -> Unit) {
    val t = LocalHetuTokens.current
    val scheme = MaterialTheme.colorScheme
    val source = remember(group.name) { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) .97f else 1f, spring(dampingRatio = .76f, stiffness = 560f), label = "group${group.name}")
    val shape = RoundedCornerShape(18.dp)
    val nodeName = selected.ifBlank { "未选择" }
    val nodeFlag = refNodeFlag(nodeName)
    Column(
        modifier
            .graphicsLayer { scaleX = scale; scaleY = scale; alpha = if (pressed) .90f else 1f }
            .heightIn(min = 106.dp)
            .background(if (expanded) t.selectionBackground else t.cardBackground, shape)
            .border(.7.dp, if (expanded) scheme.primary.copy(alpha = .18f) else t.outline.copy(alpha = .34f), shape)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .padding(12.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RefGroupVisualIcon(group, Modifier.size(26.dp))
            Spacer(Modifier.width(8.dp))
            Text(group.name, color = t.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(6.dp))
            RefDelayBadge(delay, false, null)
        }
        Text("${refGroupTypeCompact(group.type)} · ${group.nodes.size} 节点", color = Color(0xFF94A3B8), fontSize = 11.sp, maxLines = 1)
        Text(if (nodeFlag.isBlank()) nodeName else "$nodeFlag $nodeName", color = t.textSecondary, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
'''
new_group = '''@Composable
private fun RefGroupCard(group: ProxyGroupUi, selected: String, expanded: Boolean, delay: Long?, modifier: Modifier, onClick: () -> Unit) {
    val t = LocalHetuTokens.current
    val source = remember(group.name) { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) .97f else 1f, spring(dampingRatio = .76f, stiffness = 560f), label = "group${group.name}")
    val shape = RoundedCornerShape(18.dp)
    val nodeName = selected.ifBlank { "未选择" }
    val nodeFlag = refNodeFlag(nodeName)
    Column(
        modifier
            .height(92.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale; alpha = if (pressed) .90f else 1f }
            .shadow(if (expanded) 3.dp else 1.dp, shape, clip = false)
            .background(if (expanded) t.selectionBackground else t.cardBackground, shape)
            .clip(shape)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 9.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RefGroupVisualIcon(group, Modifier.size(22.dp))
            Spacer(Modifier.width(7.dp))
            Text(group.name, color = t.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(5.dp))
            RefDelayBadge(delay, false, null)
        }
        Text("${refGroupTypeCompact(group.type)} · ${group.nodes.size} 节点", color = Color(0xFF94A3B8), fontSize = 11.sp, lineHeight = 14.sp, maxLines = 1)
        Text(if (nodeFlag.isBlank()) nodeName else "$nodeFlag $nodeName", color = t.textSecondary, fontSize = 12.sp, lineHeight = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
'''
s = swap(s, old_group, new_group, '92dp strategy group cards')

# 7) Tools page: business-grouped floating cards, not one endless list.
old_tools_items = '''        item { RefTitleBar("工具") }
        item {
  RefGroup {
      RefToolRow(Icons.Rounded.Terminal, Color(0xFF2563EB), "脚本", "启动配置与运行文件") { context.startActivity(Intent(context, ReferenceFileManagerActivity::class.java)) }
      RefDivider()
      RefToolRow(Icons.Rounded.Article, Color(0xFF8B5CF6), "日志查看", "实时查看 Mihomo stdout / stderr") { scope.launch { onLog(runCatching { inspector.runtimeLog() }.getOrElse { it.message ?: "日志读取失败" }) } }
      RefDivider()
      RefToolRow(Icons.Rounded.Apps, Color(0xFFF97316), "应用管理", "分应用放行与代理相关应用列表") { context.startActivity(Intent(context, CompactMainActivity::class.java)) }
      RefDivider()
      RefToolRow(Icons.Rounded.Wifi, Color(0xFF0EA5E9), "网络匹配", "按 Wi‑Fi / SSID 自动匹配") { unavailable = "网络匹配目前只有规格，没有真实 SSID 自动切换后端。我已取消错误的基础代理跳转，接入完成前不会假装可用。" }
      RefDivider()
      RefToolRow(Icons.Rounded.WifiTethering, Color(0xFF10B981), "共享网络", "热点与局域网共享") { unavailable = "共享网络开关目前没有独立后端控制。我已取消错误跳转，避免看起来能设置但实际无效。" }
      RefDivider()
      RefToolRow(Icons.Rounded.AltRoute, Color(0xFFEF4444), "绕过规则", "CIDR 与接口绕过") { unavailable = "自定义 CIDR/接口绕过还没有接入运行时规则生成器，因此不再把你带到基础代理页。" }
      RefDivider()
      RefToolRow(Icons.Rounded.CloudDownload, Color(0xFF3B82F6), "订阅管理", state.config) { context.startActivity(Intent(context, ProxySubscriptionActivity::class.java)) }
      RefDivider()
      RefToolRow(Icons.Rounded.Public, Color(0xFFF59E0B), "CNIP 设置", "国内 IP 数据与分流") { unavailable = "CNIP 下载源和运行时应用后端尚未接入；当前不再提供假入口。" }
      RefDivider()
      RefToolRow(Icons.Rounded.Language, Color(0xFF6366F1), "更新 WebUI", "Zashboard · MetaCubeXD") { context.startActivity(Intent(context, ProxyWebUiActivity::class.java)) }
      RefDivider()
      RefToolRow(Icons.Rounded.Memory, Color(0xFF334155), "更新核心", state.core) { context.startActivity(Intent(context, ProxyCoreActivity::class.java)) }
  }
        }
'''
new_tools_items = '''        item { RefTitleBar("工具") }
        item { RefSectionLabel("系统服务") }
        item {
            RefGroup {
                RefToolRow(Icons.Rounded.Terminal, Color(0xFF2563EB), "脚本", "启动配置与运行文件") { context.startActivity(Intent(context, ReferenceFileManagerActivity::class.java)) }
                RefDivider()
                RefToolRow(Icons.Rounded.Article, Color(0xFF8B5CF6), "日志查看", "实时查看 Mihomo stdout / stderr") { scope.launch { onLog(runCatching { inspector.runtimeLog() }.getOrElse { it.message ?: "日志读取失败" }) } }
                RefDivider()
                RefToolRow(Icons.Rounded.Apps, Color(0xFFF97316), "应用管理", "分应用放行与代理相关应用列表") { context.startActivity(Intent(context, CompactMainActivity::class.java)) }
            }
        }
        item { RefSectionLabel("网络与共享") }
        item {
            RefGroup {
                RefToolRow(Icons.Rounded.Wifi, Color(0xFF0EA5E9), "网络匹配", "按 Wi‑Fi / SSID 自动匹配") { unavailable = "网络匹配目前只有规格，没有真实 SSID 自动切换后端。我已取消错误的基础代理跳转，接入完成前不会假装可用。" }
                RefDivider()
                RefToolRow(Icons.Rounded.WifiTethering, Color(0xFF10B981), "共享网络", "热点与局域网共享") { unavailable = "共享网络开关目前没有独立后端控制。我已取消错误跳转，避免看起来能设置但实际无效。" }
                RefDivider()
                RefToolRow(Icons.Rounded.AltRoute, Color(0xFFEF4444), "绕过规则", "CIDR 与接口绕过") { unavailable = "自定义 CIDR/接口绕过还没有接入运行时规则生成器，因此不再把你带到基础代理页。" }
            }
        }
        item { RefSectionLabel("订阅与数据") }
        item {
            RefGroup {
                RefToolRow(Icons.Rounded.CloudDownload, Color(0xFF3B82F6), "订阅管理", state.config) { context.startActivity(Intent(context, ProxySubscriptionActivity::class.java)) }
                RefDivider()
                RefToolRow(Icons.Rounded.Public, Color(0xFFF59E0B), "CNIP 设置", "国内 IP 数据与分流") { unavailable = "CNIP 下载源和运行时应用后端尚未接入；当前不再提供假入口。" }
            }
        }
        item { RefSectionLabel("维护") }
        item {
            RefGroup {
                RefToolRow(Icons.Rounded.Language, Color(0xFF6366F1), "更新 WebUI", "Zashboard · MetaCubeXD") { context.startActivity(Intent(context, ProxyWebUiActivity::class.java)) }
                RefDivider()
                RefToolRow(Icons.Rounded.Memory, Color(0xFF334155), "更新核心", state.core) { context.startActivity(Intent(context, ProxyCoreActivity::class.java)) }
            }
        }
'''
s = swap(s, old_tools_items, new_tools_items, 'grouped tools page')

# 8) Settings page: 2-3 items per group.
old_settings_items = '''        item { RefTitleBar("设置") }
        item {
            RefGroup {
                RefValueRow("核心选择", state.core, Icons.Rounded.Memory, Color(0xFF334155)) { context.startActivity(Intent(context, ProxyCoreActivity::class.java)) }
                RefDivider()
                RefValueRow("运行模式", state.mode, Icons.Rounded.Tune, Color(0xFF2563EB)) { modePicker = true }
                RefDivider()
                RefValueRow("IPv6", state.ipv6, Icons.Rounded.Public, Color(0xFF10B981)) { ipv6Picker = true }
                RefDivider()
                RefValueRow("端口细则", "TProxy ${MihomoStartupConfig.TPROXY_PORT} · Redir ${MihomoStartupConfig.REDIRECT_PORT}", Icons.Rounded.Hub, Color(0xFFF97316)) { portsInfo = true }
                RefDivider()
                RefValueRow("当前配置", state.config, Icons.Rounded.Description, Color(0xFF8B5CF6)) { context.startActivity(Intent(context, ProxySubscriptionActivity::class.java)) }
            }
        }
        item {
            RefGroup {
                RefValueRow("主题与界面", "Miuix · Material · Monet · OLED", Icons.Rounded.Palette, Color(0xFFEC4899)) { context.startActivity(Intent(context, ThemeSettingsActivity::class.java)) }
                RefDivider()
                RefValueRow("WebUI 面板", "本地 Zashboard · 同源控制器", Icons.Rounded.Language, Color(0xFF3B82F6)) { context.startActivity(Intent(context, ProxyWebUiActivity::class.java)) }
                RefDivider()
                RefValueRow("文件管理", "代理运行目录", Icons.Rounded.Folder, Color(0xFFF59E0B)) { context.startActivity(Intent(context, ReferenceFileManagerActivity::class.java)) }
                RefDivider()
                RefValueRow("订阅与配置", "链接 · User-Agent · 更新", Icons.Rounded.CloudDownload, Color(0xFF0EA5E9)) { context.startActivity(Intent(context, ProxySubscriptionActivity::class.java)) }
            }
        }
'''
new_settings_items = '''        item { RefTitleBar("设置") }
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
'''
s = swap(s, old_settings_items, new_settings_items, 'grouped settings page')

# 9) Floating list card shape and section labels.
old_ref_group = '''@Composable
private fun RefGroup(content: @Composable ColumnScope.() -> Unit) {
    val t = LocalHetuTokens.current
    val dark = MaterialTheme.colorScheme.background.luminance() < .5f
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = if (dark) t.elevatedCardBackground else Color.White,
        shadowElevation = if (dark) 0.dp else 3.dp,
        tonalElevation = 0.dp,
    ) {
        Column(Modifier.fillMaxWidth(), content = content)
    }
}
'''
new_ref_group = '''@Composable
private fun RefGroup(content: @Composable ColumnScope.() -> Unit) {
    val t = LocalHetuTokens.current
    val dark = MaterialTheme.colorScheme.background.luminance() < .5f
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = if (dark) t.elevatedCardBackground else Color.White,
        shadowElevation = if (dark) 0.dp else 2.dp,
        tonalElevation = 0.dp,
    ) {
        Column(Modifier.fillMaxWidth(), content = content)
    }
}

@Composable
private fun RefSectionLabel(text: String) {
    Text(
        text,
        color = Color(0xFF94A3B8),
        fontSize = 11.sp,
        lineHeight = 14.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = .6.sp,
        modifier = Modifier.padding(start = 12.dp, top = 2.dp, bottom = 0.dp),
    )
}
'''
s = swap(s, old_ref_group, new_ref_group, '16dp grouped list cards')

# Row density matching the supplied commercial layout.
s = s.replace('.padding(horizontal = 16.dp, vertical = 12.dp)', '.padding(horizontal = 14.dp, vertical = 13.dp)')
s = s.replace('modifier.padding(horizontal = 16.dp, vertical = 12.dp)', 'modifier.padding(horizontal = 14.dp, vertical = 13.dp)')
s = s.replace('modifier = Modifier.padding(start = 64.dp, end = 16.dp)', 'modifier = Modifier.padding(start = 58.dp, end = 14.dp)')

ACTIVITY.write_text(s)
print('applied LuoShu dock parity + compact commercial proxy UI')
