from pathlib import Path

p = Path('android-app/app/src/main/java/io/github/xgl34222220/bichen/ReferenceProxyActivity.kt')
s = p.read_text()

# Replace the home dashboard with the reference video's information hierarchy.
start = s.index('@Composable\nprivate fun RefHome(')
end = s.index('@Composable\nprivate fun RefActionText', start)
replacement = '''@Composable
private fun RefHome(
    state: ProxyComposeState,
    runtime: ProxyRuntimeSnapshot,
    providers: List<DashboardProviderUi>,
    delays: Map<String, Long>,
    upRate: Long,
    downRate: Long,
    operation: String,
    message: String,
    testing: Boolean,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onToggle: () -> Unit,
    onReload: () -> Unit,
    onRestart: () -> Unit,
    onDelay: () -> Unit,
    onWebUi: () -> Unit,
    onLog: () -> Unit,
) {
    val t = LocalBichenTokens.current
    val scheme = MaterialTheme.colorScheme
    val selectedDelays = state.groups.mapNotNull { group ->
        val name = group.now
        (delays[name] ?: group.nodes.firstOrNull { it.name == name }?.lastDelay)?.takeIf { it > 0L }
    }
    val measured = delays.values.filter { it > 0L }
    val current = selectedDelays.firstOrNull()
    val avg = selectedDelays.takeIf { it.isNotEmpty() }?.average()?.toInt()?.toLong()
        ?: measured.takeIf { it.isNotEmpty() }?.average()?.toInt()?.toLong()
    val fastest = measured.minOrNull()
    val memory = runtime.rssBytes.takeIf { it > 0L } ?: state.memoryBytes

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { RefTopBar("辟尘代理", onBack, onRefresh) }
        item {
            val heroShape = RoundedCornerShape(20.dp)
            Column(
                Modifier.fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                if (state.running) t.selectionBackground.copy(alpha = .96f) else t.elevatedCardBackground.copy(alpha = .92f),
                                t.cardBackground.copy(alpha = .86f),
                            ),
                        ),
                        heroShape,
                    )
                    .border(.7.dp, if (state.running) scheme.primary.copy(alpha = .18f) else t.outline.copy(alpha = .36f), heroShape)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                            Box(Modifier.size(8.dp).background(if (state.running) t.success else t.warning, CircleShape))
                            Text(if (state.running) "运行中" else "已停止", color = t.textPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        }
                        Text(
                            if (state.running) "${refDuration(runtime.elapsedSeconds)} · ${state.core} · ${state.mode}" else "${state.core} · ${state.mode}",
                            color = t.textSecondary,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(state.config, color = t.textPrimary, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    Icon(
                        if (state.running) Icons.Rounded.CheckCircle else Icons.Rounded.PowerSettingsNew,
                        contentDescription = null,
                        tint = scheme.primary.copy(alpha = .66f),
                        modifier = Modifier.size(68.dp),
                    )
                }
                HorizontalDivider(color = t.outline.copy(alpha = .65f))
                Row(Modifier.fillMaxWidth()) {
                    RefActionText("重载", state.running && operation.isBlank(), onReload, Modifier.weight(1f))
                    VerticalDivider(Modifier.height(24.dp), color = t.outline)
                    RefActionText(if (state.running) "停止" else "启动", operation.isBlank(), onToggle, Modifier.weight(1f), scheme.primary)
                    VerticalDivider(Modifier.height(24.dp), color = t.outline)
                    RefActionText("重启", state.running && operation.isBlank(), onRestart, Modifier.weight(1f))
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RefSmallTool("WebUI", "多面板控制台", Icons.Rounded.Language, onWebUi, Modifier.weight(1f))
                RefSmallTool("日志", "实时核心输出", Icons.Rounded.Article, onLog, Modifier.weight(1f))
            }
        }
        item { RefLatencyPanel(current, avg, fastest, testing, onDelay) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RefNetworkCard(runtime.lanAddress, upRate, downRate, Modifier.weight(1f))
                RefResourceCard(memory, runtime.pid, state.connections.size, Modifier.weight(1f))
            }
        }
        if (providers.isNotEmpty()) item { RefSubscriptionCard(providers) }
        if (operation.isNotBlank() || message.isNotBlank()) {
            item {
                Text(
                    operation.ifBlank { message },
                    color = t.textSecondary,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                )
            }
        }
    }
}

@Composable
private fun RefLatencyPanel(current: Long?, average: Long?, fastest: Long?, testing: Boolean, onClick: () -> Unit) {
    val t = LocalBichenTokens.current
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(16.dp)
    Column(
        Modifier.fillMaxWidth()
            .background(
                Brush.verticalGradient(listOf(t.elevatedCardBackground.copy(alpha = .82f), t.cardBackground.copy(alpha = .72f))),
                shape,
            )
            .border(.7.dp, t.outline.copy(alpha = .34f), shape)
            .clickable(enabled = !testing, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("延迟", color = t.textPrimary, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Icon(Icons.Rounded.Refresh, if (testing) "测速中" else "测速", tint = scheme.primary, modifier = Modifier.size(19.dp))
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            RefLatencyColumn("当前", if (testing) "…" else refDelay(current), Modifier.weight(1f))
            VerticalDivider(Modifier.height(38.dp), color = t.outline)
            RefLatencyColumn("平均", if (testing) "…" else refDelay(average), Modifier.weight(1f))
            VerticalDivider(Modifier.height(38.dp), color = t.outline)
            RefLatencyColumn("最快", if (testing) "…" else refDelay(fastest), Modifier.weight(1f))
        }
    }
}

@Composable
private fun RefLatencyColumn(label: String, value: String, modifier: Modifier) {
    val t = LocalBichenTokens.current
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, color = t.textSecondary, style = MaterialTheme.typography.labelSmall)
        Text(value, color = MaterialTheme.colorScheme.primary, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
private fun RefNetworkCard(lan: String, up: Long, down: Long, modifier: Modifier) {
    val t = LocalBichenTokens.current
    Surface(modifier = modifier, shape = RoundedCornerShape(15.dp), color = t.cardBackground, shadowElevation = 0.dp) {
        Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("网络", color = t.textPrimary, style = MaterialTheme.typography.titleSmall)
            Text("LAN  ${lan.ifBlank { "—" }}", color = t.textSecondary, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("上行  ${refSpeed(up)}", color = t.textPrimary, style = MaterialTheme.typography.bodySmall)
            Text("下行  ${refSpeed(down)}", color = t.textPrimary, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun RefResourceCard(memory: Long, pid: Int, connections: Int, modifier: Modifier) {
    val t = LocalBichenTokens.current
    Surface(modifier = modifier, shape = RoundedCornerShape(15.dp), color = t.cardBackground, shadowElevation = 0.dp) {
        Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("资源占用", color = t.textPrimary, style = MaterialTheme.typography.titleSmall)
            Text("内存  ${refBytes(memory)}", color = t.textPrimary, style = MaterialTheme.typography.bodySmall)
            Text("PID  ${if (pid > 0) pid else "—"}", color = t.textSecondary, style = MaterialTheme.typography.labelSmall)
            Text("连接  $connections", color = t.textSecondary, style = MaterialTheme.typography.labelSmall)
        }
    }
}

'''
s = s[:start] + replacement + s[end:]

# Tools: add the reference file manager and make WebUI explicitly multi-dashboard.
tools_start = s.index('@Composable\nprivate fun RefTools(')
settings_start = s.index('@Composable\nprivate fun RefSettings', tools_start)
tools = s[tools_start:settings_start]
marker = 'RefToolRow(Icons.Rounded.Apps, "应用管理", "去广告应用放行与规则")'
if marker not in tools:
    raise SystemExit('tools marker missing')
tools = tools.replace(
    marker,
    'RefToolRow(Icons.Rounded.Folder, "文件管理", "代理目录 · 配置 · 运行文件") { context.startActivity(Intent(context, ReferenceFileManagerActivity::class.java)) }\n                RefDivider()\n                ' + marker,
    1,
)
tools = tools.replace(
    'RefToolRow(Icons.Rounded.Language, "MetaCubeXD", "本机控制台")',
    'RefToolRow(Icons.Rounded.Language, "WebUI", "本地 · Zashboard · MetaCubeXD · 自定义")',
    1,
)
s = s[:tools_start] + tools + s[settings_start:]

# Settings: expose file manager and new WebUI manager.
settings_start = s.index('@Composable\nprivate fun RefSettings')
group_start = s.index('@Composable\nprivate fun RefGroup', settings_start)
settings = s[settings_start:group_start]
settings = settings.replace('RefValueRow("WebUI", "MetaCubeXD")', 'RefValueRow("WebUI", "多面板 · 可自定义")', 1)
settings_marker = 'RefGroup {\n                RefValueRow("WebUI", "多面板 · 可自定义")'
if settings_marker not in settings:
    raise SystemExit('settings marker missing')
settings = settings.replace(
    settings_marker,
    'RefGroup {\n                RefValueRow("文件管理", "代理运行目录") { context.startActivity(Intent(context, ReferenceFileManagerActivity::class.java)) }\n                RefDivider()\n                RefValueRow("WebUI", "多面板 · 可自定义")',
    1,
)
s = s[:settings_start] + settings + s[group_start:]

p.write_text(s)
