from pathlib import Path

root = Path('.')
p = root / 'android-app/app/src/main/java/io/github/xgl34222220/hetu/app/ConfigScreens.kt'
s = p.read_text()

imports = [
    ('import androidx.compose.material.icons.rounded.Add\n', 'import androidx.compose.material.icons.Icons\n'),
    ('import androidx.compose.foundation.shape.RoundedCornerShape\n', 'import androidx.compose.foundation.shape.CircleShape\n'),
    ('import androidx.compose.ui.graphics.Color\n', 'import androidx.compose.ui.draw.clip\n'),
]
for imp, anchor in imports:
    if imp not in s:
        assert anchor in s
        s = s.replace(anchor, anchor + imp, 1)

old = '''    var urlImport by remember { mutableStateOf(false) }
    var editSub by remember { mutableStateOf<ProxySubscriptionUi?>(null) }
'''
new = '''    var urlImport by remember { mutableStateOf(false) }
    var showAddMenu by remember { mutableStateOf(false) }
    var editSub by remember { mutableStateOf<ProxySubscriptionUi?>(null) }
'''
assert old in s
s = s.replace(old, new, 1)

old = '''        title = "配置与订阅",
        largeTitle = false,
        subtitle = "当前：${vm.state.config}",
        onBack = { nav.pop() },
        actions = { if (busy) Box(Modifier.padding(12.dp)) { HxSpinner(18.dp) } },
    ) {
        item(key = "import") {
            HxSection("导入") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    HxButton("从文件", onClick = { importFile.launch(arrayOf("*/*")) }, icon = Icons.Rounded.FileOpen, filled = false, modifier = Modifier.weight(1f))
                    HxButton("从链接", onClick = { urlImport = true }, icon = Icons.Rounded.Link, filled = false, modifier = Modifier.weight(1f))
                }
            }
        }
'''
new = '''        title = "配置与订阅",
        largeTitle = false,
        subtitle = "管理源配置与当前配置中的订阅链接。",
        onBack = { nav.pop() },
        actions = {
            if (busy) Box(Modifier.padding(12.dp)) { HxSpinner(18.dp) }
            else HxBarAction(Icons.Rounded.Add, "添加", onClick = { showAddMenu = true })
        },
    ) {
'''
assert old in s
s = s.replace(old, new, 1)

s = s.replace('HxGroup(title = "配置文件") {', 'HxGroup(title = "配置管理") {', 1)
old = '''                            HxSelectMark(config.selected, Modifier.padding(horizontal = 14.dp))
                            Column(Modifier.weight(1f).padding(vertical = 10.dp)) {
'''
new = '''                            Icon(
                                Icons.Rounded.Description,
                                null,
                                tint = if (config.selected) c.text else c.textMuted,
                                modifier = Modifier.padding(start = 14.dp, end = 12.dp).size(22.dp),
                            )
                            Column(Modifier.weight(1f).padding(vertical = 10.dp)) {
'''
assert old in s
s = s.replace(old, new, 1)

old = '''                            IconButton(onClick = { menuFor = config }, modifier = Modifier.hxAnchorSource()) {
                                Icon(Icons.Rounded.MoreHoriz, "更多", tint = c.textMuted)
                            }
'''
new = '''                            if (config.selected) Icon(Icons.Rounded.Check, "当前配置", tint = c.accent, modifier = Modifier.size(20.dp))
                            IconButton(onClick = { menuFor = config }, modifier = Modifier.hxAnchorSource()) {
                                Icon(Icons.Rounded.MoreHoriz, "更多", tint = c.textMuted)
                            }
'''
assert old in s
s = s.replace(old, new, 1)

needle = '''                                .padding(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
'''
repl = '''                                .padding(horizontal = 6.dp, vertical = 3.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (config.selected) c.accentSoft else Color.Transparent)
                                .padding(horizontal = 2.dp, vertical = 1.dp),
                            verticalAlignment = Alignment.CenterVertically,
'''
assert needle in s
s = s.replace(needle, repl, 1)

old = '''                    }
                }
            }
        }
        item(key = "subs") {
'''
new = '''                    }
                    HxDivider(52.dp)
                    HxNavRow("编辑当前 YAML", icon = Icons.Rounded.Edit, iconTint = c.accent) { nav.push(HxRoute.ConfigEditor) }
                }
            }
        }
        item(key = "subs") {
'''
assert old in s
s = s.replace(old, new, 1)
s = s.replace('                "订阅链接（proxy-providers）",', '                "订阅管理",', 1)

old = '''        item(key = "more") {
            HxSection() {
                HxGroup(title = "编辑") {
                    HxNavRow("编辑 YAML", subtitle = "语法大纲跳转、撤销重做，保存前自动校验", icon = Icons.Rounded.Edit) { nav.push(HxRoute.ConfigEditor) }
                }
            }
        }
'''
assert old in s
s = s.replace(old, '', 1)

anchor = '''    menuFor?.let { config ->
'''
insert = '''    if (showAddMenu) {
        HxActionMenu(
            title = "添加配置",
            actions = listOf(
                HxMenuAction("从文件导入", Icons.Rounded.FileOpen) { showAddMenu = false; importFile.launch(arrayOf("*/*")) },
                HxMenuAction("从链接导入", Icons.Rounded.Link) { showAddMenu = false; urlImport = true },
            ),
            onDismiss = { showAddMenu = false },
        )
    }

'''
assert anchor in s
s = s.replace(anchor, insert + anchor, 1)

p.write_text(s)
print('V20.53 config list parity applied')
