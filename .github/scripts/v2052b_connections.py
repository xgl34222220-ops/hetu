from pathlib import Path

root = Path('.')
p = root / 'android-app/app/src/main/java/io/github/xgl34222220/hetu/app/ConnectionsScreen.kt'
s = p.read_text()
assert '    val state = vm.state\n' in s
s = s.replace('    val state = vm.state\n', '    val state = vm.state\n    val c = Hx.colors\n', 1)
assert '    var sortBy by rememberSaveable { mutableStateOf("traffic") }\n' in s
s = s.replace('    var sortBy by rememberSaveable { mutableStateOf("traffic") }\n', '    var sortBy by rememberSaveable { mutableStateOf("down") }\n', 1)

old = '''                    item(key = "filters") {
                        Column(Modifier.padding(horizontal = Hx.gutter).padding(bottom = 12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                HxSegmented(
                                    options = listOf("all" to "全部", "proxy" to "代理", "direct" to "直连"),
                                    selected = filter,
                                    onSelect = { filter = it },
                                    modifier = Modifier.weight(1f),
                                )
                                Spacer(Modifier.width(8.dp))
                                HxToggleChip("按应用", byApp) { byApp = it }
                            }
                            if (searching && section == "conn") {
                                Spacer(Modifier.height(10.dp))
                                HxSearchField(query, { query = it }, "搜索域名、应用、规则", autoFocus = true)
                            }
                        }
                    }
'''
new = '''                    item(key = "conn-summary") {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 18.dp).padding(bottom = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("连接详情", style = MaterialTheme.typography.labelLarge, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, color = c.accent)
                            Spacer(Modifier.weight(1f))
                            Text(filtered.size.toString(), style = MaterialTheme.typography.labelLarge.merge(HxNumberStyle), color = c.text)
                            Spacer(Modifier.width(10.dp))
                            Box(
                                Modifier.size(28.dp).clip(RoundedCornerShape(10.dp)).clickable { confirmCloseAll = true },
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(Icons.Rounded.Close, "断开全部", tint = c.textMuted, modifier = Modifier.size(19.dp))
                            }
                        }
                    }
                    if (searching && section == "conn") {
                        item(key = "filters") {
                            Column(Modifier.padding(horizontal = Hx.gutter).padding(bottom = 10.dp)) {
                                HxSearchField(query, { query = it }, "搜索域名、应用、规则", autoFocus = true)
                                Spacer(Modifier.height(8.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    HxSegmented(
                                        options = listOf("all" to "全部", "proxy" to "代理", "direct" to "直连"),
                                        selected = filter,
                                        onSelect = { filter = it },
                                        modifier = Modifier.weight(1f),
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    HxToggleChip("按应用", byApp) { byApp = it }
                                }
                            }
                        }
                    }
'''
assert old in s
s = s.replace(old, new, 1)
assert '            title = "活动",\n            subtitle = pageSubtitle,' in s
s = s.replace('            title = "活动",\n            subtitle = pageSubtitle,', '            title = "连接",\n            subtitle = null,', 1)

start = s.index('@Composable\nprivate fun ConnectionRow(')
end = s.index('\n@Composable\nprivate fun AppConnectionGroup', start)
chunk = s[start:end]
chunk = chunk.replace('    val shape = RoundedCornerShape(22.dp)', '    val shape = RoundedCornerShape(14.dp)', 1)
chunk = chunk.replace('.padding(bottom = 9.dp)', '.padding(bottom = 6.dp)', 1)
chunk = chunk.replace('.hxSoftShadow(shape, 2.dp)\n', '', 1)
chunk = chunk.replace('.padding(horizontal = 15.dp, vertical = 12.dp)', '.padding(horizontal = 13.dp, vertical = 8.dp)', 1)
chunk = chunk.replace('Modifier.size(30.dp).clip(RoundedCornerShape(9.dp))', 'Modifier.size(18.dp).clip(RoundedCornerShape(6.dp))', 1)
chunk = chunk.replace('Spacer(Modifier.width(9.dp))', 'Spacer(Modifier.width(6.dp))', 1)
chunk = chunk.replace('Spacer(Modifier.height(8.dp))', 'Spacer(Modifier.height(5.dp))', 2)
s = s[:start] + chunk + s[end:]
p.write_text(s)
print('V20.52 connections parity applied')
