from pathlib import Path

p = Path('android-app/app/src/main/java/io/github/xgl34222220/bichen/ReferenceProxyActivity.kt')
s = p.read_text()
s = s.replace(
    'RefProxyPage.Settings -> RefSettings(state)',
    'RefProxyPage.Settings -> RefSettings(state) { scope.launch { refresh() } }',
    1,
)
start = s.index('@Composable\nprivate fun RefSettings(state: ProxyComposeState) {')
end = s.index('\n@Composable\nprivate fun RefGroup', start)
new = r'''@Composable
private fun RefSettings(state: ProxyComposeState, onChanged: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("bichen", 0) }
    var modePicker by remember { mutableStateOf(false) }
    var ipv6Picker by remember { mutableStateOf(false) }
    var portsInfo by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { RefTitleBar("设置") }
        item {
            RefGroup {
                RefValueRow("核心选择", state.core) { context.startActivity(Intent(context, ProxyCoreActivity::class.java)) }
                RefDivider()
                RefValueRow("运行模式", state.mode) { modePicker = true }
                RefDivider()
                RefValueRow("IPv6", state.ipv6) { ipv6Picker = true }
                RefDivider()
                RefValueRow("端口细则", "TProxy ${MihomoStartupConfig.TPROXY_PORT} · Redir ${MihomoStartupConfig.REDIRECT_PORT}") { portsInfo = true }
                RefDivider()
                RefValueRow("当前配置", state.config) { context.startActivity(Intent(context, ProxySubscriptionActivity::class.java)) }
            }
        }
        item {
            RefGroup {
                RefValueRow("主题与界面", "Miuix · Material · Monet · OLED") { context.startActivity(Intent(context, ThemeSettingsActivity::class.java)) }
                RefDivider()
                RefValueRow("WebUI 面板", "本地 Zashboard · 同源控制器") { context.startActivity(Intent(context, ProxyWebUiActivity::class.java)) }
                RefDivider()
                RefValueRow("文件管理", "代理运行目录") { context.startActivity(Intent(context, ReferenceFileManagerActivity::class.java)) }
                RefDivider()
                RefValueRow("订阅与配置", "链接 · User-Agent · 更新") { context.startActivity(Intent(context, ProxySubscriptionActivity::class.java)) }
            }
        }
    }

    if (modePicker) {
        val profile = ProxyRuntimeProfile.load(prefs)
        val choices = ProxyRuntimeProfile.Mode.values().filter {
            ProxyRuntimeProfile.capability(profile.core, it).available
        }
        AlertDialog(
            onDismissRequest = { modePicker = false },
            title = { Text("运行模式") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    choices.forEach { mode ->
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                prefs.edit().putString("proxyBaseMode", mode.id).apply()
                                modePicker = false
                                notice = if (state.running) "运行模式已保存，重启代理后生效" else "运行模式已保存"
                                onChanged()
                            }.padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = profile.mode == mode, onClick = null)
                            Spacer(Modifier.width(8.dp))
                            Text(mode.label, modifier = Modifier.weight(1f))
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { modePicker = false }) { Text("取消") } },
        )
    }

    if (ipv6Picker) {
        val profile = ProxyRuntimeProfile.load(prefs)
        val values = listOf(
            ProxyRuntimeProfile.Ipv6.ENABLE to "启用 IPv6",
            ProxyRuntimeProfile.Ipv6.BYPASS to "IPv6 不进核心",
            ProxyRuntimeProfile.Ipv6.DISABLE to "禁用系统 IPv6",
        )
        AlertDialog(
            onDismissRequest = { ipv6Picker = false },
            title = { Text("IPv6") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    values.forEach { (value, label) ->
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                prefs.edit().putString("proxyBaseIpv6", value.id).apply()
                                ipv6Picker = false
                                notice = if (state.running) "IPv6 设置已保存，重启代理后生效" else "IPv6 设置已保存"
                                onChanged()
                            }.padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = profile.ipv6 == value, onClick = null)
                            Spacer(Modifier.width(8.dp))
                            Text(label, modifier = Modifier.weight(1f))
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { ipv6Picker = false }) { Text("取消") } },
        )
    }

    if (portsInfo) {
        AlertDialog(
            onDismissRequest = { portsInfo = false },
            title = { Text("端口细则") },
            text = {
                Text(
                    "TProxy：${MihomoStartupConfig.TPROXY_PORT}\n" +
                        "Redirect：${MihomoStartupConfig.REDIRECT_PORT}\n" +
                        "控制器：127.0.0.1:${MihomoStartupConfig.CONTROLLER_PORT}\n\n" +
                        "这些是辟尘运行副本使用的安全端口。源订阅文件不会被直接修改。",
                )
            },
            confirmButton = { TextButton(onClick = { portsInfo = false }) { Text("关闭") } },
        )
    }

    notice?.let { text ->
        AlertDialog(
            onDismissRequest = { notice = null },
            text = { Text(text) },
            confirmButton = { TextButton(onClick = { notice = null }) { Text("知道了") } },
        )
    }
}
'''
p.write_text(s[:start] + new + s[end:])
