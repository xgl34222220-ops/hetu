from pathlib import Path
import re

root = Path('.')

# version
build = root / 'android-app/app/build.gradle.kts'
s = build.read_text()
assert 'versionCode = 2050' in s and 'versionName = "0.10.0-v20"' in s
s = s.replace('versionCode = 2050', 'versionCode = 2051', 1)
s = s.replace('versionName = "0.10.0-v20"', 'versionName = "0.10.1-v20"', 1)
build.write_text(s)

# HxTabbedPage: keep panel on exact same cool canvas as rest of concept suite.
tabbed = root / 'android-app/app/src/main/java/io/github/xgl34222220/hetu/app/HxTabbedPage.kt'
s = tabbed.read_text()
old = '    val pageCanvas = if (panelReferenceStyle && MaterialTheme.colorScheme.background.luminance() > .5f) Color(0xFFEBEDFA) else c.canvas\n'
assert old in s
s = s.replace(old, '    val pageCanvas = c.canvas\n', 1)
tabbed.write_text(s)

# Overview: the three-dot on the rank card owns the “显示数量” popup; no redundant top-right settings button.
overview = root / 'android-app/app/src/main/java/io/github/xgl34222220/hetu/app/PanelOverviewScreen.kt'
s = overview.read_text()
old = '''        actions = {
            Box(Modifier.hxAnchorSource()) {
                HxBarAction(Icons.Rounded.Settings, "概览设置", onClick = { showOverviewMenu = true })
            }
        },
'''
assert old in s
s = s.replace(old, '        actions = {},\n', 1)
old = '''                HxCard(
                    onClick = { vm.openPanel("rank") },
                    padding = PaddingValues(horizontal = 15.dp, vertical = 13.dp),
                ) {
'''
assert old in s
s = s.replace(old, '''                HxCard(
                    padding = PaddingValues(horizontal = 15.dp, vertical = 13.dp),
                ) {
''', 1)
old = '''                        Box(
                            Modifier.size(28.dp).clip(CircleShape).clickable { vm.openPanel("rank") },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(Icons.Rounded.MoreHoriz, "查看排行", tint = c.textMuted, modifier = Modifier.size(18.dp))
                        }
'''
assert old in s
s = s.replace(old, '''                        Box(
                            Modifier
                                .hxAnchorSource()
                                .size(28.dp)
                                .clip(CircleShape)
                                .clickable { showOverviewMenu = true },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(Icons.Rounded.MoreHoriz, "显示数量", tint = c.textMuted, modifier = Modifier.size(18.dp))
                        }
''', 1)
overview.write_text(s)


print('V20.51 overview parity applied')
