from pathlib import Path

root = Path('.')
build = root / 'android-app/app/build.gradle.kts'
s = build.read_text()
assert 'versionCode = 2048' in s and 'versionName = "0.9.8-v20"' in s
s = s.replace('versionCode = 2048', 'versionCode = 2049', 1)
s = s.replace('versionName = "0.9.8-v20"', 'versionName = "0.9.9-v20"', 1)
build.write_text(s)

settings = root / 'android-app/app/src/main/java/io/github/xgl34222220/hetu/app/SettingsScreen.kt'
s = settings.read_text()
page_start = s.index('        HxPage(\n            title = "设置",')
when_start = s.index('\n    when (choice) {', page_start)
prefix = s[:page_start]
suffix = s[when_start:]
new_page = '''        HxPage(
            title = "设置",
            scrollToTopSignal = vm.reselect,
            bottomPadding = bottomPadding,
            largeTitleStartPadding = 26.dp,
            largeTitleFontSizeSp = 36f,
            largeTitleBottomPadding = 18.dp,
            canvasColor = if (c.dark) c.canvas else Color(0xFFEBEDFA),
        ) {
            item(key = "proxy-config") {
                MiuixSettingsCard {
                    MiuixSettingsArrow(
                        title = "基础代理配置",
                        summary = "核心、模式与当前配置",
                        icon = Icons.Rounded.Tune,
                        onClick = { open(RootTproxyActivity::class.java) },
                    )
                    MiuixSettingsArrow(
                        title = "高级代理配置",
                        summary = "性能、DNS 与资源限制",
                        icon = Icons.Rounded.AltRoute,
                        onClick = { open(ProxyAdvancedSettingsActivity::class.java) },
                    )
                }
            }

            item(key = "appearance-panel") {
                MiuixSettingsCard {
                    MiuixSettingsArrow(
                        title = "语言与主题",
                        summary = "显示语言、主题与显示",
                        icon = Icons.Rounded.Palette,
                        onClick = { nav.push(HxRoute.Theme) },
                    )
                    MiuixSettingsArrow(
                        title = "默认面板",
                        summary = "选择面板与显示偏好",
                        icon = Icons.Rounded.GridView,
                        onClick = { choice = "defaultPanel" },
                    )
                }
            }

            item(key = "backup-startup-notify") {
                MiuixSettingsCard {
                    MiuixSettingsArrow(
                        title = "备份与恢复",
                        summary = "导出与恢复应用设置",
                        icon = Icons.Rounded.CloudSync,
                        onClick = { choice = "backup" },
                    )
                    MiuixSettingsArrow(
                        title = "开机启动与下载",
                        summary = "启动设置与资源下载",
                        icon = Icons.Rounded.RestartAlt,
                        onClick = { choice = "startupDownload" },
                    )
                    MiuixSettingsArrow(
                        title = "通知设置",
                        summary = "管理运行状态提醒",
                        icon = Icons.Rounded.Notifications,
                        onClick = { nav.push(HxRoute.Notifications) },
                    )
                }
            }

            item(key = "about") {
                MiuixSettingsCard {
                    MiuixSettingsArrow(
                        title = "关于",
                        summary = "版本与开源信息",
                        icon = Icons.Rounded.Info,
                        onClick = { nav.push(HxRoute.About) },
                    )
                }
            }
        }
    }
'''
s = prefix + new_page + suffix

old = '''            choices = listOf(
                HxChoice("proxies", "策略"),
                HxChoice("conn", "连接"),
                HxChoice("providers", "订阅"),
                HxChoice("rules", "规则"),
                HxChoice("sets", "规则集"),
            ),'''
new = '''            choices = listOf(
                HxChoice("overview", "概览"),
                HxChoice("proxies", "策略"),
                HxChoice("providers", "订阅"),
                HxChoice("conn", "连接"),
                HxChoice("rules", "规则"),
                HxChoice("sets", "规则集"),
                HxChoice("logs", "日志"),
            ),'''
assert old in s
s = s.replace(old, new, 1)

insert_before = '\n        "defaultPanel" -> HxChoiceSheet('
assert insert_before in s
startup_case = '''
        "startupDownload" -> HxChoiceSheet(
            title = "开机启动与下载",
            choices = listOf(
                HxChoice("autostart", "开机自启", if (prefs.getBoolean("proxyRootAutoStart", false)) "已开启" else "已关闭"),
                HxChoice("mirrorToggle", "加速下载", if (prefs.getBoolean("downloadMirrorEnabled", false)) "已开启" else "已关闭"),
                HxChoice("mirror", "加速地址", prefs.getString("downloadMirrorPrefix", "").orEmpty().ifBlank { "未设置" }),
            ),
            selected = null,
            onPick = { value ->
                when (value) {
                    "autostart" -> {
                        val on = !prefs.getBoolean("proxyRootAutoStart", false)
                        prefs.edit().putBoolean("proxyRootAutoStart", on).apply()
                        bump()
                    }
                    "mirrorToggle" -> {
                        val on = !prefs.getBoolean("downloadMirrorEnabled", false)
                        prefs.edit().putBoolean("downloadMirrorEnabled", on).apply()
                        bump()
                    }
                    "mirror" -> choice = "mirror"
                }
                if (value != "mirror") choice = "startupDownload"
            },
            onDismiss = { choice = null },
        )
'''
s = s.replace(insert_before, startup_case + insert_before, 1)
settings.write_text(s)

assert 'title = "语言与主题"' in s
assert 'title = "默认面板"' in s
assert 'title = "开机启动与下载"' in s
print('V20.49 settings PDF parity phase 1 applied')
