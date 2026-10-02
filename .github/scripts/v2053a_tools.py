from pathlib import Path

root = Path('.')
build = root / 'android-app/app/build.gradle.kts'
s = build.read_text()
assert 'versionCode = 2052' in s and 'versionName = "0.10.2-v20"' in s
s = s.replace('versionCode = 2052', 'versionCode = 2053', 1)
s = s.replace('versionName = "0.10.2-v20"', 'versionName = "0.10.3-v20"', 1)
build.write_text(s)

p = root / 'android-app/app/src/main/java/io/github/xgl34222220/hetu/app/PanelToolsScreens.kt'
s = p.read_text()
for imp, anchor in [
    ('import androidx.compose.material.icons.rounded.Close\n', 'import androidx.compose.material.icons.rounded.Check\n'),
    ('import androidx.compose.material.icons.rounded.Search\n', 'import androidx.compose.material.icons.rounded.Shield\n'),
]:
    if imp not in s:
        assert anchor in s
        s = s.replace(anchor, anchor + imp, 1)

old = '''    val stagger = rememberHxStagger()
    fun open(type: Class<out android.app.Activity>) { context.startActivity(Intent(context, type)) }

    HxPage(
'''
new = '''    val stagger = rememberHxStagger()
    var searching by remember { mutableStateOf(false) }
    var toolQuery by remember { mutableStateOf("") }
    fun open(type: Class<out android.app.Activity>) { context.startActivity(Intent(context, type)) }

    HxPage(
'''
assert old in s
s = s.replace(old, new, 1)

old = '''        canvasColor = Hx.colors.canvas,
        referenceLabel = "示例数据",
    ) {
        item(key = "file-run") {
'''
new = '''        canvasColor = Hx.colors.canvas,
        referenceLabel = "示例数据",
        actions = {
            HxBarAction(
                if (searching) Icons.Rounded.Close else Icons.Rounded.Search,
                if (searching) "关闭搜索" else "搜索",
                onClick = {
                    if (searching) toolQuery = ""
                    searching = !searching
                },
            )
        },
    ) {
        if (searching) {
            item(key = "tool-search") {
                Box(Modifier.padding(horizontal = 12.dp).padding(bottom = 10.dp)) {
                    HxSearchField(toolQuery, { toolQuery = it }, "搜索工具", autoFocus = true)
                }
            }
            item(key = "tool-search-results") {
                val q = toolQuery.trim()
                if (q.isNotBlank()) {
                    ToolReferenceCard {
                        fun hit(a: String, b: String) = a.contains(q, true) || b.contains(q, true)
                        if (hit("应用管理", "代理与直连")) ToolReferenceRow("应用管理", "代理与直连", Icons.Rounded.Apps) { nav.push(HxRoute.Apps) }
                        if (hit("共享网络", "热点与代理")) ToolReferenceRow("共享网络", "热点与代理", Icons.Rounded.WifiTethering) { nav.push(HxRoute.SharedNet) }
                        if (hit("网络匹配", "自动切换")) ToolReferenceRow("网络匹配", "自动切换", Icons.Rounded.Wifi) { nav.push(HxRoute.NetMatch) }
                        if (hit("绕过规则", "网段与接口")) ToolReferenceRow("绕过规则", "网段与接口", Icons.Rounded.AltRoute) { nav.push(HxRoute.Bypass) }
                        if (hit("诊断工具", "网络与环境")) ToolReferenceRow("诊断工具", "网络与环境", Icons.Rounded.HealthAndSafety) { nav.push(HxRoute.Diagnostics) }
                    }
                }
            }
        } else item(key = "file-run") {
'''
assert old in s
s = s.replace(old, new, 1)

for key in ['logs', 'apps', 'network', 'subscription', 'updates']:
    old = f'        item(key = "{key}") {{\n'
    assert old in s
    s = s.replace(old, f'        if (!searching) item(key = "{key}") {{\n', 1)

p.write_text(s)
print('V20.53 tools search parity applied')
