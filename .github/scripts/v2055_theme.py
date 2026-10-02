from pathlib import Path

root = Path(".")
build = root / "android-app/app/build.gradle.kts"
s = build.read_text()
assert 'versionCode = 2054' in s and 'versionName = "0.10.4-v20"' in s
s = s.replace('versionCode = 2054', 'versionCode = 2055', 1)
s = s.replace('versionName = "0.10.4-v20"', 'versionName = "0.10.5-v20"', 1)
build.write_text(s)

p = root / "android-app/app/src/main/java/io/github/xgl34222220/hetu/app/SettingsScreen.kt"
s = p.read_text()

if "import androidx.compose.material.icons.rounded.LightMode\n" not in s:
    anchor = "import androidx.compose.material.icons.rounded.LinkOff\n"
    assert anchor in s
    s = s.replace(anchor, anchor + "import androidx.compose.material.icons.rounded.LightMode\n", 1)

anchor = '    val uiScale = remember(tick) { prefs.getFloat("uiScale", 1f).coerceIn(.8f, 1.2f) }\n'
assert anchor in s
s = s.replace(
    anchor,
    anchor + '    val appLanguage = remember(tick) { prefs.getString("appLanguage", "system").orEmpty().ifBlank { "system" } }\n',
    1,
)

old = '''    HxPage(
        title = "主题设置",
        subtitle = null,
        onBack = onBack,
        largeTitle = false,
    ) {
        item(key = "theme") {
            HxSection {
                HxGroup {
                    HxNavRow("界面风格", subtitle = "河图的 Miuix / Liquid Glass 界面体系", icon = Icons.Rounded.GridView, iconTint = c.textMuted, value = "Miuix") { vm.toast("当前使用 Miuix 界面风格") }
                    HxDivider()
                    HxNavRow("主题模式", icon = Icons.Rounded.DarkMode, iconTint = c.textMuted, value = when (vm.appearance) {
                        "light" -> "浅色"; "dark" -> "深色"; else -> "跟随系统"
                    }, dropdown = true) { choice = "appearance" }
                    if (Build.VERSION.SDK_INT >= 31) {
                        HxDivider()
                        HxSwitchRow("Monet 动态取色", vm.dynamicColor, { vm.setDynamic(it); vm.bumpSettings(); revision++ }, subtitle = "Android 12+ 使用系统动态色", icon = Icons.Rounded.Palette)
                    }
                    HxDivider()
                    HxSwitchRow("深色纯黑背景", vm.pureBlack, { vm.updatePureBlack(it); vm.bumpSettings(); revision++ }, subtitle = "OLED 模式使用纯黑画布", icon = Icons.Rounded.Contrast, iconTint = c.textMuted)
                    if (!vm.dynamicColor) {
                        HxDivider()
                        HxAccentSwatches(vm)
                    }
                }
            }
        }
'''
new = '''    HxPage(
        title = "主题设置",
        subtitle = null,
        onBack = onBack,
        largeTitle = false,
        actions = { Text("示例数据", style = MaterialTheme.typography.bodySmall, color = c.textFaint) },
    ) {
        item(key = "language") {
            HxSection {
                HxGroup {
                    HxNavRow(
                        "语言",
                        subtitle = "切换应用语言",
                        icon = Icons.Rounded.Public,
                        iconTint = c.text,
                        value = when (appLanguage) {
                            "zh-CN" -> "简体中文"
                            "zh-TW" -> "繁體中文"
                            "en" -> "English"
                            "ru" -> "Русский"
                            else -> "跟随系统"
                        },
                        onClick = { choice = "language" },
                    )
                }
            }
        }
        item(key = "theme") {
            HxSection {
                HxGroup {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)) {
                        Text("主题模式", style = MaterialTheme.typography.titleSmall, color = c.text)
                        Spacer(Modifier.height(8.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(
                                Triple("system", "跟随系统", Icons.Rounded.AutoAwesome),
                                Triple("light", "浅色模式", Icons.Rounded.LightMode),
                                Triple("dark", "深色模式", Icons.Rounded.DarkMode),
                            ).forEach { (value, label, icon) ->
                                val selected = vm.appearance == value
                                Column(
                                    Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(12.dp))
                                        .then(
                                            if (selected) Modifier.border(1.5.dp, c.accent, RoundedCornerShape(12.dp))
                                            else Modifier.border(1.dp, c.line, RoundedCornerShape(12.dp))
                                        )
                                        .background(if (selected) c.accentSoft else Color.Transparent)
                                        .clickable {
                                            vm.setAppearanceMode(value)
                                            vm.bumpSettings()
                                            revision++
                                        }
                                        .padding(horizontal = 8.dp, vertical = 10.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                ) {
                                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopEnd) {
                                        if (selected) {
                                            Icon(Icons.Rounded.Check, null, tint = c.accent, modifier = Modifier.size(16.dp))
                                        }
                                        Icon(icon, null, tint = c.text, modifier = Modifier.align(Alignment.Center).size(25.dp))
                                    }
                                    Spacer(Modifier.height(5.dp))
                                    Text(label, style = MaterialTheme.typography.labelMedium, color = c.text, maxLines = 1)
                                }
                            }
                        }
                    }
                    if (Build.VERSION.SDK_INT >= 31) {
                        HxDivider()
                        HxSwitchRow("Monet 动态取色", vm.dynamicColor, { vm.setDynamic(it); vm.bumpSettings(); revision++ }, subtitle = "使用系统壁纸提供的配色", icon = Icons.Rounded.Palette)
                    }
                    HxDivider()
                    HxSwitchRow("深色纯黑背景", vm.pureBlack, { vm.updatePureBlack(it); vm.bumpSettings(); revision++ }, subtitle = "OLED 模式使用纯黑画布", icon = Icons.Rounded.Contrast, iconTint = c.textMuted)
                    if (!vm.dynamicColor) {
                        HxDivider()
                        HxAccentSwatches(vm)
                    }
                }
            }
        }
'''
assert old in s
s = s.replace(old, new, 1)

note = '''        item(key = "note") {
            HxBanner("这些设置只影响界面层；代理核心、规则、订阅与运行配置不会被修改。", tone = HxTone.Accent, modifier = Modifier.padding(horizontal = Hx.gutter))
        }
'''
assert note in s
s = s.replace(note, "", 1)

choice_anchor = '''    when (choice) {
        "appearance" -> HxChoiceSheet(
'''
assert choice_anchor in s
language_case = '''    when (choice) {
        "language" -> HxChoiceSheet(
            title = "语言",
            choices = listOf(
                HxChoice("system", "跟随系统"),
                HxChoice("zh-CN", "简体中文"),
                HxChoice("zh-TW", "繁體中文"),
                HxChoice("en", "English"),
                HxChoice("ru", "Русский"),
            ),
            selected = appLanguage,
            onPick = { value ->
                prefs.edit().putString("appLanguage", value).apply()
                vm.bumpSettings()
                revision++
                choice = null
            },
            onDismiss = { choice = null },
        )
        "appearance" -> HxChoiceSheet(
'''
s = s.replace(choice_anchor, language_case, 1)

p.write_text(s)
print("V20.55 theme reference parity applied")
