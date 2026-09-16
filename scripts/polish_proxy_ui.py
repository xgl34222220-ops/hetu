from pathlib import Path

ui_path = Path('android-app/app/src/main/java/io/github/xgl34222220/bichen/ReferenceProxyActivity.kt')
dock_path = Path('android-app/app/src/main/java/io/github/xgl34222220/bichen/ui/BichenGlassDock.kt')
s = ui_path.read_text()
d = dock_path.read_text()


def one(old: str, new: str, label: str):
    global s
    if old not in s:
        raise SystemExit(f'missing exact UI block: {label}')
    s = s.replace(old, new, 1)


def dock_one(old: str, new: str, label: str):
    global d
    if old not in d:
        raise SystemExit(f'missing exact dock block: {label}')
    d = d.replace(old, new, 1)


# Compose drawing primitives used by the smooth sparkline.
if 'import androidx.compose.ui.geometry.Offset\n' not in s:
    one('import androidx.compose.ui.draw.shadow\n', 'import androidx.compose.ui.draw.shadow\nimport androidx.compose.ui.geometry.Offset\n', 'offset import')
if 'import androidx.compose.ui.graphics.Path\n' not in s:
    one('import androidx.compose.ui.graphics.Color\n', 'import androidx.compose.ui.graphics.Color\nimport androidx.compose.ui.graphics.Path\n', 'path import')
if 'import androidx.compose.ui.graphics.drawscope.Stroke\n' not in s:
    one('import androidx.compose.ui.graphics.graphicsLayer\n', 'import androidx.compose.ui.graphics.graphicsLayer\nimport androidx.compose.ui.graphics.drawscope.Stroke\n', 'stroke import')

# LuoShu uses a full-height content viewport under the floating glass dock. The old 88dp parent
# padding cut the page off before it ever reached the backdrop, which made the dock read as a white block.
one(
'''        Box(
            Modifier.fillMaxSize()
                .then(if (!liquid) Modifier.hazeSource(haze) else Modifier)
                .then(if (liquid) Modifier.layerBackdrop(liquidBackdrop) else Modifier)
                .padding(bottom = 88.dp),
        ) {''',
'''        Box(
            Modifier.fillMaxSize()
                .then(if (!liquid) Modifier.hazeSource(haze) else Modifier)
                .then(if (liquid) Modifier.layerBackdrop(liquidBackdrop) else Modifier),
        ) {''',
'edge-to-edge dock backdrop',
)

# Lists own trailing clearance, just like LuoShu: content passes behind glass but can still scroll clear.
one(
'''        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),''',
'''        contentPadding = PaddingValues(
            start = 16.dp,
            top = 8.dp,
            end = 16.dp,
            bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 108.dp,
        ),''',
'home dock content padding',
)
one(
'''            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),''',
'''            contentPadding = PaddingValues(
                start = 12.dp,
                top = 8.dp,
                end = 12.dp,
                bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 108.dp,
            ),''',
'panel dock content padding',
)

# Segmented control: gray capsule track + animated raised white pill, never an underline/blue slab.
one(
'''@Composable
private fun RefPanelTabs(selected: RefPanelTab, onSelect: (RefPanelTab) -> Unit) {
    val t = LocalBichenTokens.current
    val scheme = MaterialTheme.colorScheme
    val dark = scheme.background.luminance() < .5f
    val tabs = RefPanelTab.entries
    BoxWithConstraints(
        Modifier.fillMaxWidth().height(38.dp).background(t.controlBackground.copy(alpha = .72f), CircleShape).padding(2.dp),
    ) {
        val itemWidth = maxWidth / tabs.size.toFloat()
        val index = tabs.indexOf(selected).coerceAtLeast(0)
        val indicatorX by animateDpAsState(
            targetValue = itemWidth * index.toFloat(),
            animationSpec = spring(dampingRatio = .78f, stiffness = 480f),
            label = "panelTabIndicator",
        )
        Box(
            Modifier.offset(x = indicatorX).width(itemWidth).fillMaxHeight()
                .clip(CircleShape)
                .background(if (dark) scheme.primary.copy(alpha = .22f) else Color(0xFFE0EDFF)),
        )
        Row(Modifier.fillMaxSize()) {
            tabs.forEach { tab ->
                val active = tab == selected
                val source = remember(tab) { MutableInteractionSource() }
                val pressed by source.collectIsPressedAsState()
                val scale by animateFloatAsState(if (pressed) .95f else 1f, spring(dampingRatio = .78f, stiffness = 560f), label = "tab${tab.name}")
                Box(
                    Modifier.width(itemWidth).fillMaxHeight().graphicsLayer { scaleX = scale; scaleY = scale; alpha = if (pressed) .90f else 1f }
                        .clip(CircleShape)
                        .clickable(interactionSource = source, indication = null) { onSelect(tab) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(tab.label, color = if (active) scheme.primary else t.textSecondary, fontSize = 11.sp, fontWeight = if (active) FontWeight.Bold else FontWeight.Medium, maxLines = 1)
                }
            }
        }
    }
}''',
'''@Composable
private fun RefPanelTabs(selected: RefPanelTab, onSelect: (RefPanelTab) -> Unit) {
    val t = LocalBichenTokens.current
    val scheme = MaterialTheme.colorScheme
    val dark = scheme.background.luminance() < .5f
    val tabs = RefPanelTab.entries
    val track = if (dark) t.controlBackground.copy(alpha = .86f) else Color(0xFFE2E8F0)
    val activeFill = if (dark) scheme.surfaceContainerHigh.copy(alpha = .94f) else Color.White
    BoxWithConstraints(
        Modifier.fillMaxWidth().height(42.dp).background(track, CircleShape).padding(4.dp),
    ) {
        val itemWidth = maxWidth / tabs.size.toFloat()
        val index = tabs.indexOf(selected).coerceAtLeast(0)
        val indicatorX by animateDpAsState(
            targetValue = itemWidth * index.toFloat(),
            animationSpec = spring(dampingRatio = .80f, stiffness = 440f),
            label = "panelSegmentIndicator",
        )
        Box(
            Modifier.offset(x = indicatorX).width(itemWidth).fillMaxHeight()
                .shadow(3.dp, CircleShape, clip = false)
                .clip(CircleShape)
                .background(activeFill),
        )
        Row(Modifier.fillMaxSize()) {
            tabs.forEach { tab ->
                val active = tab == selected
                val source = remember(tab) { MutableInteractionSource() }
                val pressed by source.collectIsPressedAsState()
                val scale by animateFloatAsState(if (pressed) .95f else 1f, spring(dampingRatio = .78f, stiffness = 560f), label = "tab${tab.name}")
                Box(
                    Modifier.width(itemWidth).fillMaxHeight()
                        .graphicsLayer { scaleX = scale; scaleY = scale; alpha = if (pressed) .90f else 1f }
                        .clip(CircleShape)
                        .clickable(interactionSource = source, indication = null) { onSelect(tab) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        tab.label,
                        color = if (active) (if (dark) scheme.onSurface else Color(0xFF0F172A)) else (if (dark) t.textSecondary else Color(0xFF64748B)),
                        fontSize = 11.sp,
                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}''',
'pill segmented control',
)

# Sparkline: no axes/grid/ticks; smooth Catmull-Rom-to-Bezier curves with soft area gradients.
one(
'''      val points = history.toList()
      Canvas(Modifier.fillMaxWidth().height(150.dp)) {
          if (points.size > 1) {
              val maxRate = points.maxOf { maxOf(it.second, it.third) }.coerceAtLeast(1L).toFloat()
              val end = points.last().first
              val start = end - 60_000L
              fun makePath(index: Int): Path {
                  val path = Path()
                  points.forEachIndexed { i, point ->
                      val x = (((point.first - start).coerceIn(0L, 60_000L)).toFloat() / 60_000f) * size.width
                      val value = if (index == 1) point.second else point.third
                      val y = size.height - (value.toFloat() / maxRate * size.height * .88f)
                      if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                  }
                  return path
              }
              drawPath(makePath(1), color = t.success, style = Stroke(width = 2.5.dp.toPx()))
              drawPath(makePath(2), color = scheme.primary, style = Stroke(width = 2.5.dp.toPx()))
          }
      }''',
'''      val points = history.toList()
      Canvas(Modifier.fillMaxWidth().height(150.dp)) {
          if (points.size > 1) {
              val maxRate = points.maxOf { maxOf(it.second, it.third) }.coerceAtLeast(1L).toFloat()
              val end = points.last().first
              val start = end - 60_000L
              fun series(index: Int): List<Offset> = points.map { point ->
                  val x = (((point.first - start).coerceIn(0L, 60_000L)).toFloat() / 60_000f) * size.width
                  val value = if (index == 1) point.second else point.third
                  val y = size.height - (value.toFloat() / maxRate * size.height * .86f)
                  Offset(x, y)
              }
              fun smooth(coords: List<Offset>, closeArea: Boolean): Path {
                  val path = Path()
                  if (coords.isEmpty()) return path
                  path.moveTo(coords.first().x, coords.first().y)
                  for (i in 0 until coords.lastIndex) {
                      val p0 = if (i == 0) coords[i] else coords[i - 1]
                      val p1 = coords[i]
                      val p2 = coords[i + 1]
                      val p3 = if (i + 2 < coords.size) coords[i + 2] else p2
                      val c1 = Offset(p1.x + (p2.x - p0.x) / 6f, p1.y + (p2.y - p0.y) / 6f)
                      val c2 = Offset(p2.x - (p3.x - p1.x) / 6f, p2.y - (p3.y - p1.y) / 6f)
                      path.cubicTo(c1.x, c1.y, c2.x, c2.y, p2.x, p2.y)
                  }
                  if (closeArea) {
                      path.lineTo(coords.last().x, size.height)
                      path.lineTo(coords.first().x, size.height)
                      path.close()
                  }
                  return path
              }
              val upload = series(1)
              val download = series(2)
              drawPath(
                  smooth(upload, true),
                  brush = Brush.verticalGradient(listOf(t.success.copy(alpha = .18f), Color.Transparent), 0f, size.height),
              )
              drawPath(
                  smooth(download, true),
                  brush = Brush.verticalGradient(listOf(scheme.primary.copy(alpha = .25f), Color.Transparent), 0f, size.height),
              )
              drawPath(smooth(upload, false), color = t.success, style = Stroke(width = 2.25.dp.toPx()))
              drawPath(smooth(download, false), color = scheme.primary, style = Stroke(width = 2.25.dp.toPx()))
          }
      }''',
'smooth area sparkline',
)

# Home four-card hierarchy: tiny muted labels, large heavy values, lightweight status icons.
one(
'''        Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (lanMode) "LAN" else "WAN", color = t.textPrimary, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Box(Modifier.size(27.dp).background(MaterialTheme.colorScheme.primary.copy(alpha = .09f), CircleShape), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.SwapHoriz, "切换 LAN/WAN", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(15.dp))
                }
            }
            if (lanMode) {
                Text("IP", color = t.textSecondary, fontSize = 11.sp)
                Text(runtime.lanAddress, color = t.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${runtime.lanInterface} · $connections 连接", color = t.textMuted, fontSize = 11.sp, maxLines = 1)
            } else {
                Text("公网 IP", color = t.textSecondary, fontSize = 11.sp)
                Text(runtime.wanAddress, color = t.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${countryEmoji(runtime.wanCountryCode)} ${runtime.wanRegion}", color = t.textMuted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }''',
'''        Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (lanMode) "LAN" else "WAN", color = Color(0xFF94A3B8), fontSize = 12.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                Icon(Icons.Rounded.SwapHoriz, "切换 LAN/WAN", tint = t.textMuted.copy(alpha = .62f), modifier = Modifier.size(15.dp))
            }
            if (lanMode) {
                Text(runtime.lanAddress, color = if (MaterialTheme.colorScheme.background.luminance() < .5f) t.textPrimary else Color(0xFF0F172A), fontSize = 19.sp, lineHeight = 23.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${runtime.lanInterface} · $connections 连接", color = Color(0xFF94A3B8), fontSize = 11.sp, maxLines = 1)
            } else {
                Text(runtime.wanAddress, color = if (MaterialTheme.colorScheme.background.luminance() < .5f) t.textPrimary else Color(0xFF0F172A), fontSize = 19.sp, lineHeight = 23.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${countryEmoji(runtime.wanCountryCode)} ${runtime.wanRegion}", color = Color(0xFF94A3B8), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }''',
'network hierarchy',
)
one(
'''        Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
  Text("网速", color = t.textPrimary, style = MaterialTheme.typography.titleSmall)
  Row(verticalAlignment = Alignment.CenterVertically) {
      Icon(Icons.Rounded.ArrowUpward, null, tint = t.success, modifier = Modifier.size(16.dp))
      Spacer(Modifier.width(5.dp))
      Text(refSpeed(up), color = t.textPrimary, style = MaterialTheme.typography.bodyMedium)
  }
  Row(verticalAlignment = Alignment.CenterVertically) {
      Icon(Icons.Rounded.ArrowDownward, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
      Spacer(Modifier.width(5.dp))
      Text(refSpeed(down), color = t.textPrimary, style = MaterialTheme.typography.bodyMedium)
  }
        }''',
'''        Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
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
        }''',
'speed hierarchy',
)
one(
'''        Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
  Row(verticalAlignment = Alignment.CenterVertically) {
      Text("订阅", color = t.textPrimary, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
      if (total > 0L) Surface(shape = RoundedCornerShape(999.dp), color = t.selectionBackground) {
          Text("剩余 ${((1f - ratio) * 100f).toInt()}%", Modifier.padding(horizontal = 8.dp, vertical = 3.dp), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall)
      }
  }
  Text(if (total > 0L) "已用 ${refBytes(used)}" else "${items.size} 个远程订阅", color = t.textSecondary, style = MaterialTheme.typography.labelSmall, maxLines = 1)
  Text(if (total > 0L) "总量 ${refBytes(total)}" else "剩余 —", color = t.textPrimary, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
  if (total > 0L) LinearProgressIndicator(progress = { ratio }, modifier = Modifier.fillMaxWidth().height(4.dp).clip(CircleShape))
  if (total > 0L) Text("剩余 ${refBytes(remain)}", color = t.textMuted, style = MaterialTheme.typography.labelSmall, maxLines = 1)
        }''',
'''        Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("订阅", color = Color(0xFF94A3B8), fontSize = 12.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                if (total > 0L) Surface(shape = RoundedCornerShape(999.dp), color = t.selectionBackground) {
                    Text("${((1f - ratio) * 100f).toInt()}%", Modifier.padding(horizontal = 7.dp, vertical = 2.dp), color = MaterialTheme.colorScheme.primary, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            Text(if (total > 0L) refBytes(remain) else "${items.size} 个", color = if (MaterialTheme.colorScheme.background.luminance() < .5f) t.textPrimary else Color(0xFF0F172A), fontSize = 19.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            Text(if (total > 0L) "剩余 · 已用 ${refBytes(used)}" else "远程订阅", color = Color(0xFF94A3B8), fontSize = 11.sp, maxLines = 1)
            if (total > 0L) LinearProgressIndicator(progress = { ratio }, modifier = Modifier.fillMaxWidth().height(4.dp).clip(CircleShape))
        }''',
'subscription hierarchy',
)
one(
'''        Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Text("资源占用", color = t.textPrimary, style = MaterialTheme.typography.titleSmall)
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("内存", color = t.textSecondary, fontSize = 12.sp)
                Text(refBytes(memory), color = t.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("CPU", color = t.textSecondary, fontSize = 12.sp)
                Text(String.format(java.util.Locale.US, "%.1f%%", cpuPercent), color = t.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }
        }''',
'''        Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
        }''',
'resource hierarchy',
)

# Metrics everywhere use a real hierarchy instead of two nearly identical text sizes.
one(
'''@Composable
private fun RefMetric(title: String, value: String, modifier: Modifier) {
    val t = LocalBichenTokens.current
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(value, color = t.textPrimary, fontSize = 16.sp, lineHeight = 21.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(title, color = t.textSecondary, fontSize = 12.sp, lineHeight = 16.sp, maxLines = 1)
    }
}''',
'''@Composable
private fun RefMetric(title: String, value: String, modifier: Modifier) {
    val t = LocalBichenTokens.current
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(value, color = if (MaterialTheme.colorScheme.background.luminance() < .5f) t.textPrimary else Color(0xFF0F172A), fontSize = 19.sp, lineHeight = 23.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(title, color = Color(0xFF94A3B8), fontSize = 11.sp, lineHeight = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}''',
'metric hierarchy v3',
)

# Tools page: iOS/MIUI inset-grouped card on #F1F5F9, with saturated rounded icon badges.
one(
'''private fun RefTools(state: ProxyComposeState, onLog: (String) -> Unit) {
    val context = LocalContext.current
    val inspector = remember { ProxyRuntimeInspector(context) }
    val scope = rememberCoroutineScope()
    var unavailable by remember { mutableStateOf<String?>(null) }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {''',
'''private fun RefTools(state: ProxyComposeState, onLog: (String) -> Unit) {
    val context = LocalContext.current
    val inspector = remember { ProxyRuntimeInspector(context) }
    val scope = rememberCoroutineScope()
    val t = LocalBichenTokens.current
    val dark = MaterialTheme.colorScheme.background.luminance() < .5f
    var unavailable by remember { mutableStateOf<String?>(null) }
    LazyColumn(
        Modifier.fillMaxSize().background(if (dark) t.pageBackground else Color(0xFFF1F5F9)),
        contentPadding = PaddingValues(
            start = 16.dp,
            top = 8.dp,
            end = 16.dp,
            bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 108.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {''',
'tools grouped page',
)
one(
'''      RefToolRow(Icons.Rounded.Terminal, "脚本", "启动配置与运行文件") { context.startActivity(Intent(context, ReferenceFileManagerActivity::class.java)) }
      RefDivider()
      RefToolRow(Icons.Rounded.Article, "日志查看", "实时查看 Mihomo stdout / stderr") { scope.launch { onLog(runCatching { inspector.runtimeLog() }.getOrElse { it.message ?: "日志读取失败" }) } }
      RefDivider()
      RefToolRow(Icons.Rounded.Apps, "应用管理", "分应用放行与代理相关应用列表") { context.startActivity(Intent(context, CompactMainActivity::class.java)) }
      RefDivider()
      RefToolRow(Icons.Rounded.Wifi, "网络匹配", "后端尚未接入 · 不再跳错页面") { unavailable = "网络匹配目前只有规格，没有真实 SSID 自动切换后端。我已取消错误的基础代理跳转，接入完成前不会假装可用。" }
      RefDivider()
      RefToolRow(Icons.Rounded.WifiTethering, "共享网络", "后端尚未接入 · 不再跳错页面") { unavailable = "共享网络开关目前没有独立后端控制。我已取消错误跳转，避免看起来能设置但实际无效。" }
      RefDivider()
      RefToolRow(Icons.Rounded.AltRoute, "绕过规则", "后端尚未接入 · 不再跳错页面") { unavailable = "自定义 CIDR/接口绕过还没有接入运行时规则生成器，因此不再把你带到基础代理页。" }
      RefDivider()
      RefToolRow(Icons.Rounded.CloudDownload, "订阅管理", state.config) { context.startActivity(Intent(context, ProxySubscriptionActivity::class.java)) }
      RefDivider()
      RefToolRow(Icons.Rounded.Public, "CNIP 设置", "后端尚未接入 · 不再跳错页面") { unavailable = "CNIP 下载源和运行时应用后端尚未接入；当前不再提供假入口。" }
      RefDivider()
      RefToolRow(Icons.Rounded.Language, "更新 WebUI", "本地面板 · Zashboard · MetaCubeXD") { context.startActivity(Intent(context, ProxyWebUiActivity::class.java)) }
      RefDivider()
      RefToolRow(Icons.Rounded.Memory, "更新核心", state.core) { context.startActivity(Intent(context, ProxyCoreActivity::class.java)) }''',
'''      RefToolRow(Icons.Rounded.Terminal, Color(0xFF2563EB), "脚本", "启动配置与运行文件") { context.startActivity(Intent(context, ReferenceFileManagerActivity::class.java)) }
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
      RefToolRow(Icons.Rounded.Memory, Color(0xFF334155), "更新核心", state.core) { context.startActivity(Intent(context, ProxyCoreActivity::class.java)) }''',
'tool accent rows',
)

# Settings page uses the same inset-grouped visual system, plus quiet icon badges for scanning.
one(
'''    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {''',
'''    val t = LocalBichenTokens.current
    val dark = MaterialTheme.colorScheme.background.luminance() < .5f
    LazyColumn(
        Modifier.fillMaxSize().background(if (dark) t.pageBackground else Color(0xFFF1F5F9)),
        contentPadding = PaddingValues(
            start = 16.dp,
            top = 8.dp,
            end = 16.dp,
            bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 108.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {''',
'settings grouped page',
)
one(
'''                RefValueRow("核心选择", state.core) { context.startActivity(Intent(context, ProxyCoreActivity::class.java)) }
                RefDivider()
                RefValueRow("运行模式", state.mode) { modePicker = true }
                RefDivider()
                RefValueRow("IPv6", state.ipv6) { ipv6Picker = true }
                RefDivider()
                RefValueRow("端口细则", "TProxy ${MihomoStartupConfig.TPROXY_PORT} · Redir ${MihomoStartupConfig.REDIRECT_PORT}") { portsInfo = true }
                RefDivider()
                RefValueRow("当前配置", state.config) { context.startActivity(Intent(context, ProxySubscriptionActivity::class.java)) }''',
'''                RefValueRow("核心选择", state.core, Icons.Rounded.Memory, Color(0xFF334155)) { context.startActivity(Intent(context, ProxyCoreActivity::class.java)) }
                RefDivider()
                RefValueRow("运行模式", state.mode, Icons.Rounded.Tune, Color(0xFF2563EB)) { modePicker = true }
                RefDivider()
                RefValueRow("IPv6", state.ipv6, Icons.Rounded.Public, Color(0xFF10B981)) { ipv6Picker = true }
                RefDivider()
                RefValueRow("端口细则", "TProxy ${MihomoStartupConfig.TPROXY_PORT} · Redir ${MihomoStartupConfig.REDIRECT_PORT}", Icons.Rounded.Hub, Color(0xFFF97316)) { portsInfo = true }
                RefDivider()
                RefValueRow("当前配置", state.config, Icons.Rounded.Description, Color(0xFF8B5CF6)) { context.startActivity(Intent(context, ProxySubscriptionActivity::class.java)) }''',
'settings first group icons',
)
one(
'''                RefValueRow("主题与界面", "Miuix · Material · Monet · OLED") { context.startActivity(Intent(context, ThemeSettingsActivity::class.java)) }
                RefDivider()
                RefValueRow("WebUI 面板", "本地 Zashboard · 同源控制器") { context.startActivity(Intent(context, ProxyWebUiActivity::class.java)) }
                RefDivider()
                RefValueRow("文件管理", "代理运行目录") { context.startActivity(Intent(context, ReferenceFileManagerActivity::class.java)) }
                RefDivider()
                RefValueRow("订阅与配置", "链接 · User-Agent · 更新") { context.startActivity(Intent(context, ProxySubscriptionActivity::class.java)) }''',
'''                RefValueRow("主题与界面", "Miuix · Material · Monet · OLED", Icons.Rounded.Palette, Color(0xFFEC4899)) { context.startActivity(Intent(context, ThemeSettingsActivity::class.java)) }
                RefDivider()
                RefValueRow("WebUI 面板", "本地 Zashboard · 同源控制器", Icons.Rounded.Language, Color(0xFF3B82F6)) { context.startActivity(Intent(context, ProxyWebUiActivity::class.java)) }
                RefDivider()
                RefValueRow("文件管理", "代理运行目录", Icons.Rounded.Folder, Color(0xFFF59E0B)) { context.startActivity(Intent(context, ReferenceFileManagerActivity::class.java)) }
                RefDivider()
                RefValueRow("订阅与配置", "链接 · User-Agent · 更新", Icons.Rounded.CloudDownload, Color(0xFF0EA5E9)) { context.startActivity(Intent(context, ProxySubscriptionActivity::class.java)) }''',
'settings second group icons',
)

# Modern rounded grouped card and indented dividers.
one(
'''@Composable
private fun RefGroup(content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = RoundedCornerShape(16.dp), color = LocalBichenTokens.current.cardBackground, shadowElevation = 0.dp) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp), content = content)
    }
}''',
'''@Composable
private fun RefGroup(content: @Composable ColumnScope.() -> Unit) {
    val t = LocalBichenTokens.current
    val dark = MaterialTheme.colorScheme.background.luminance() < .5f
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = if (dark) t.elevatedCardBackground else Color.White,
        shadowElevation = if (dark) 0.dp else 3.dp,
        tonalElevation = 0.dp,
    ) {
        Column(Modifier.fillMaxWidth(), content = content)
    }
}''',
'inset grouped card',
)
one(
'''@Composable
private fun RefToolRow(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    val t = LocalBichenTokens.current
    val source = remember(title) { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) .97f else 1f, spring(dampingRatio = .78f, stiffness = 560f), label = "tool$title")
    Row(
        Modifier.fillMaxWidth().graphicsLayer { scaleX = scale; scaleY = scale }
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(21.dp))
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = t.textPrimary, style = MaterialTheme.typography.titleSmall)
            Text(subtitle, color = t.textSecondary, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Icon(Icons.Rounded.ChevronRight, null, tint = t.textSecondary, modifier = Modifier.size(18.dp))
    }
}''',
'''@Composable
private fun RefToolRow(icon: ImageVector, accent: Color, title: String, subtitle: String, onClick: () -> Unit) {
    val t = LocalBichenTokens.current
    val source = remember(title) { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) .97f else 1f, spring(dampingRatio = .78f, stiffness = 560f), label = "tool$title")
    val badgeShape = RoundedCornerShape(10.dp)
    Row(
        Modifier.fillMaxWidth()
            .graphicsLayer { scaleX = scale; scaleY = scale; alpha = if (pressed) .90f else 1f }
            .background(if (pressed) t.controlBackground.copy(alpha = .46f) else Color.Transparent)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(36.dp)
                .shadow(5.dp, badgeShape, clip = false, ambientColor = accent.copy(alpha = .22f), spotColor = accent.copy(alpha = .22f))
                .background(accent, badgeShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, tint = Color.White, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, color = t.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = Color(0xFF94A3B8), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Icon(Icons.Rounded.ChevronRight, null, tint = Color(0xFFCBD5E1), modifier = Modifier.size(17.dp))
    }
}''',
'colored tool icon badges',
)
one(
'''@Composable
private fun RefValueRow(title: String, value: String, onClick: (() -> Unit)? = null) {
    val t = LocalBichenTokens.current
    val source = remember(title) { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed && onClick != null) .98f else 1f, spring(dampingRatio = .80f, stiffness = 560f), label = "value$title")
    val modifier = if (onClick == null) Modifier.fillMaxWidth() else Modifier.fillMaxWidth().graphicsLayer { scaleX = scale; scaleY = scale }
        .clickable(interactionSource = source, indication = null, onClick = onClick)
    Row(modifier.padding(vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = t.textPrimary, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        if (value.isNotBlank()) Text(value, color = t.textSecondary, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 190.dp))
        if (onClick != null) {
            Spacer(Modifier.width(6.dp))
            Icon(Icons.Rounded.ChevronRight, null, tint = t.textSecondary, modifier = Modifier.size(18.dp))
        }
    }
}''',
'''@Composable
private fun RefValueRow(title: String, value: String, icon: ImageVector? = null, accent: Color = Color(0xFF64748B), onClick: (() -> Unit)? = null) {
    val t = LocalBichenTokens.current
    val source = remember(title) { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed && onClick != null) .98f else 1f, spring(dampingRatio = .80f, stiffness = 560f), label = "value$title")
    val modifier = if (onClick == null) Modifier.fillMaxWidth() else Modifier.fillMaxWidth()
        .graphicsLayer { scaleX = scale; scaleY = scale; alpha = if (pressed) .90f else 1f }
        .background(if (pressed) t.controlBackground.copy(alpha = .46f) else Color.Transparent)
        .clickable(interactionSource = source, indication = null, onClick = onClick)
    Row(modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) {
            val badgeShape = RoundedCornerShape(10.dp)
            Box(Modifier.size(36.dp).background(accent, badgeShape), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = Color.White, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(14.dp))
        }
        Text(title, color = t.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        if (value.isNotBlank()) Text(value, color = Color(0xFF94A3B8), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 164.dp))
        if (onClick != null) {
            Spacer(Modifier.width(7.dp))
            Icon(Icons.Rounded.ChevronRight, null, tint = Color(0xFFCBD5E1), modifier = Modifier.size(17.dp))
        }
    }
}''',
'settings grouped rows',
)
one(
'''@Composable
private fun RefDivider() { HorizontalDivider(color = LocalBichenTokens.current.outline) }''',
'''@Composable
private fun RefDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(start = 64.dp, end = 16.dp),
        thickness = 1.dp,
        color = if (MaterialTheme.colorScheme.background.luminance() < .5f) LocalBichenTokens.current.outline else Color(0xFFF1F5F9),
    )
}''',
'indented grouped divider',
)

# Force this proxy dock onto LuoShu's actual liquid-glass path; stale device prefs can no longer fall back
# to the opaque .98 surface that looked like a white plastic slab.
dock_one(
'''    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("bichen", 0) }
    val scheme = MaterialTheme.colorScheme
    val tokens = LocalBichenTokens.current
    val dark = scheme.background.luminance() < .5f
    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val floating = prefs.getBoolean("floatingBottomBar", true)
    val enableBlur = prefs.getBoolean("enableBlur", true)
    val activeGlass = prefs.getBoolean("liquidGlass", true)''',
'''    val scheme = MaterialTheme.colorScheme
    val tokens = LocalBichenTokens.current
    val dark = scheme.background.luminance() < .5f
    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val floating = true
    val enableBlur = true
    val activeGlass = true''',
'force LuoShu glass dock',
)

# Remove the now-unused preference/context import path from dock only if no longer referenced.
d = d.replace('import androidx.compose.ui.platform.LocalContext\n', '')

ui_path.write_text(s)
dock_path.write_text(d)
print('proxy visual polish v3 applied: LuoShu edge-to-edge glass, inset groups, badges, segmented tabs, smooth charts')