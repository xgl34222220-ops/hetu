from pathlib import Path

root = Path('.')
build = root / 'android-app/app/build.gradle.kts'
s = build.read_text()
assert 'versionCode = 2053' in s and 'versionName = "0.10.3-v20"' in s
s = s.replace('versionCode = 2053', 'versionCode = 2054', 1)
s = s.replace('versionName = "0.10.3-v20"', 'versionName = "0.10.4-v20"', 1)
build.write_text(s)

p = root / 'android-app/app/src/main/java/io/github/xgl34222220/hetu/app/SettingsScreen.kt'
s = p.read_text()
old = '''    var revision by remember { mutableIntStateOf(0) }
    var choice by remember { mutableStateOf<String?>(null) }
    var restoreUri by remember { mutableStateOf<Uri?>(null) }
'''
new = '''    var revision by remember { mutableIntStateOf(0) }
    var choice by remember { mutableStateOf<String?>(null) }
    var subPage by remember { mutableStateOf<String?>(null) }
    var restoreUri by remember { mutableStateOf<Uri?>(null) }
'''
assert old in s
s = s.replace(old, new, 1)
s = s.replace('onClick = { choice = "defaultPanel" },', 'onClick = { subPage = "defaultPanel" },', 1)
s = s.replace('onClick = { choice = "backup" },', 'onClick = { subPage = "backup" },', 1)
s = s.replace('onClick = { choice = "startupDownload" },', 'onClick = { subPage = "startupDownload" },', 1)

anchor = '''    top.yukonga.miuix.kmp.theme.MiuixTheme(colors = miuixColors) {
'''
assert anchor in s
subpages = r'''    if (subPage == "backup") {
        HxPage(
            title = "备份与恢复",
            subtitle = "管理配置与偏好数据",
            largeTitle = false,
            onBack = { subPage = null },
            actions = { Text("示例数据", style = MaterialTheme.typography.bodySmall, color = c.textFaint) },
        ) {
            item(key = "create") {
                HxSection {
                    HxGroup {
                        HxRow("创建备份", subtitle = "导出当前配置与偏好设置", icon = Icons.Rounded.CloudSync, iconTint = c.text)
                        Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                            HxButton("导出备份", onClick = { exporter.launch("Hetu-backup.json") }, modifier = Modifier.fillMaxWidth(), icon = Icons.Rounded.UploadFile)
                        }
                    }
                }
            }
            item(key = "restore") {
                HxSection {
                    HxGroup {
                        HxRow("从文件恢复", subtitle = "选择河图备份文件，恢复前会再次确认", icon = Icons.Rounded.Restore, iconTint = c.text)
                        Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                            HxButton("选择文件", onClick = { importer.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }, modifier = Modifier.fillMaxWidth(), filled = false)
                        }
                    }
                }
            }
            item(key = "contents") {
                HxSection {
                    HxGroup(title = "备份内容") {
                        HxRow("应用配置", subtitle = "配置库与运行偏好", icon = Icons.Rounded.Description, iconTint = c.text)
                        HxDivider()
                        HxRow("界面偏好", subtitle = "主题、语言与显示设置", icon = Icons.Rounded.Palette, iconTint = c.text)
                        HxDivider()
                        HxRow("订阅与连接", subtitle = "订阅链接与配置来源", icon = Icons.Rounded.LinkOff, iconTint = c.text)
                    }
                }
            }
            item(key = "warning") {
                HxBanner(
                    "备份文件可能包含配置中的订阅链接与认证信息，请保存至可信位置。",
                    tone = HxTone.Warn,
                    modifier = Modifier.padding(horizontal = Hx.gutter),
                )
            }
        }
    } else if (subPage == "startupDownload") {
        val autoStart = prefs.getBoolean("proxyRootAutoStart", false)
        val mirrorOn = prefs.getBoolean("downloadMirrorEnabled", false)
        val mirrorPrefix = prefs.getString("downloadMirrorPrefix", "").orEmpty()
        HxPage(
            title = "开机启动与下载",
            largeTitle = false,
            onBack = { subPage = null },
            actions = { Text("示例数据", style = MaterialTheme.typography.bodySmall, color = c.textFaint) },
        ) {
            item(key = "autostart") {
                HxSection {
                    HxGroup {
                        HxSwitchRow(
                            "开机自启",
                            autoStart,
                            { prefs.edit().putBoolean("proxyRootAutoStart", it).apply(); bump() },
                            subtitle = "安装 Root 开机脚本，开机后自动启动服务",
                            icon = Icons.Rounded.RestartAlt,
                            iconTint = c.text,
                        )
                    }
                }
            }
            item(key = "download") {
                HxSection {
                    HxGroup {
                        HxSwitchRow(
                            "加速下载",
                            mirrorOn,
                            { prefs.edit().putBoolean("downloadMirrorEnabled", it).apply(); bump() },
                            subtitle = "通过已配置的镜像下载资源",
                            icon = Icons.Rounded.Download,
                            iconTint = c.text,
                        )
                        HxDivider()
                        HxNavRow(
                            "加速地址",
                            subtitle = mirrorPrefix.ifBlank { "尚未设置" },
                            icon = Icons.Rounded.LinkOff,
                            iconTint = c.text,
                            onClick = { choice = "mirror" },
                        )
                    }
                }
            }
        }
    } else if (subPage == "defaultPanel") {
        HxPage(
            title = "默认面板",
            largeTitle = false,
            onBack = { subPage = null },
            actions = { Text("示例数据", style = MaterialTheme.typography.bodySmall, color = c.textFaint) },
        ) {
            item(key = "panel") {
                HxSection {
                    HxGroup {
                        HxNavRow(
                            "默认面板页面",
                            icon = Icons.Rounded.GridView,
                            iconTint = c.text,
                            value = when (panelDefault) {
                                "overview" -> "概览"; "proxies" -> "策略"; "providers" -> "订阅"; "conn" -> "连接"
                                "rules" -> "规则"; "sets" -> "规则集"; "logs" -> "日志"; else -> "概览"
                            },
                            dropdown = true,
                            onClick = { choice = "defaultPanel" },
                        )
                        HxDivider()
                        HxSwitchRow(
                            "启动时打开面板",
                            startOnPanel,
                            { prefs.edit().putBoolean("startOnPanel", it).apply(); bump() },
                            icon = Icons.Rounded.Dashboard,
                            iconTint = c.text,
                        )
                        HxDivider()
                        HxSwitchRow(
                            "显示底栏面板入口",
                            showPanelDock,
                            { prefs.edit().putBoolean("showPanelDock", it).apply(); bump() },
                            icon = Icons.Rounded.GridView,
                            iconTint = c.text,
                        )
                    }
                }
            }
        }
    }

    if (subPage == null) {
'''
s = s.replace(anchor, subpages + anchor, 1)
needle = '''    }

    when (choice) {
'''
assert needle in s
s = s.replace(needle, '''    }
    }

    when (choice) {
''', 1)

start = s.index('        "backup" -> HxChoiceSheet(')
end = s.index('        "mirror" -> HxFormDialog(', start)
s = s[:start] + s[end:]
start = s.index('        "startupDownload" -> HxChoiceSheet(')
end = s.index('\n        "defaultPanel" -> HxChoiceSheet(', start)
s = s[:start] + s[end:]
s = s.replace('val panelDefault = prefs.getString("defaultPanelSection", "proxies").orEmpty()', 'val panelDefault = prefs.getString("defaultPanelSection", "overview").orEmpty()', 1)

p.write_text(s)
print('V20.54 settings subpages parity applied')
