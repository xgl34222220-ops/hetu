from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

# Version
build = ROOT / "android-app/app/build.gradle.kts"
text = build.read_text(encoding="utf-8")
text = text.replace('versionCode = 434', 'versionCode = 435')
text = text.replace('versionName = "0.4.0-test.34"', 'versionName = "0.4.0-test.35"')
build.write_text(text, encoding="utf-8")

# Keep the newest visual shell, but wire it to the real test.34 backends.
ref = ROOT / "android-app/app/src/main/java/io/github/xgl34222220/bichen/ReferenceProxyActivity.kt"
text = ref.read_text(encoding="utf-8")

text = text.replace('''\n    fun unavailable(message: String) {\n        android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_SHORT).show()\n    }\n''', '\n')

text = text.replace('''                    "脚本",\n                    "服务脚本管理与执行",''', '''                    "运行文件",\n                    "启动配置与运行文件",''')

text = text.replace('''                RefToolRow(Icons.Rounded.Apps, Color(0xFFF97316), "应用管理", "分应用放行与代理相关应用", trailingText = "管理", trailingColor = Color(0xFF2563EB)) {\n                    context.startActivity(Intent(context, CompactMainActivity::class.java))\n                }''', '''                RefToolRow(Icons.Rounded.Apps, Color(0xFFF97316), "应用名单", "Root 分应用代理范围", trailingText = "管理", trailingColor = Color(0xFF2563EB)) {\n                    context.startActivity(Intent(context, ProxyAppSelectionActivity::class.java))\n                }''')

text = text.replace('''                RefToolRow(Icons.Rounded.Wifi, Color(0xFF0EA5E9), "网络匹配", "按 Wi‑Fi / SSID 自动匹配", trailingText = "待接入", trailingBadge = true, trailingColor = Color(0xFF94A3B8)) {\n                    unavailable("网络匹配后端尚未接入")\n                }\n                RefDivider()\n                RefToolRow(Icons.Rounded.WifiTethering, Color(0xFF10B981), "共享网络", "热点与局域网共享", trailingText = "待接入", trailingBadge = true, trailingColor = Color(0xFF94A3B8)) {\n                    unavailable("共享网络控制后端尚未接入")\n                }\n                RefDivider()\n                RefToolRow(Icons.Rounded.AltRoute, Color(0xFFEF4444), "绕过规则", "CIDR 与接口绕过", trailingText = "待接入", trailingBadge = true, trailingColor = Color(0xFF94A3B8)) {\n                    unavailable("自定义绕过规则后端尚未接入")\n                }''', '''                RefToolRow(Icons.Rounded.Wifi, Color(0xFF0EA5E9), "网络匹配", "Wi‑Fi / SSID / 移动网络自动启停", trailingText = "自动化", trailingColor = Color(0xFF2563EB)) {\n                    context.startActivity(Intent(context, ProxyNetworkAutomationActivity::class.java))\n                }\n                RefDivider()\n                RefToolRow(Icons.Rounded.WifiTethering, Color(0xFF10B981), "共享网络", "热点与局域网共享 · Root 规则", trailingText = "设置", trailingColor = Color(0xFF2563EB)) {\n                    context.startActivity(Intent(context, ProxyAdvancedSettingsActivity::class.java).putExtra("focus", "sharing"))\n                }\n                RefDivider()\n                RefToolRow(Icons.Rounded.AltRoute, Color(0xFFEF4444), "绕过规则", "CIDR 与接口绕过 · Root 规则", trailingText = "设置", trailingColor = Color(0xFF2563EB)) {\n                    context.startActivity(Intent(context, ProxyAdvancedSettingsActivity::class.java).putExtra("focus", "bypass"))\n                }''')

text = text.replace('''                RefToolRow(Icons.Rounded.CloudDownload, Color(0xFF2563EB), "订阅管理", "链接 · User-Agent · 更新", trailingText = "管理", trailingColor = Color(0xFF2563EB)) {\n                    context.startActivity(Intent(context, ProxySubscriptionActivity::class.java))\n                }\n                RefDivider()\n                RefToolRow(Icons.Rounded.Public, Color(0xFFF97316), "CNIP 设置", "国内 IP 数据与分流", trailingText = "待接入", trailingBadge = true, trailingColor = Color(0xFF94A3B8)) {\n                    unavailable("CNIP 下载源和运行时应用后端尚未接入")\n                }''', '''                RefToolRow(Icons.Rounded.CloudDownload, Color(0xFF2563EB), "订阅管理", "链接 · User-Agent · 更新", trailingText = "管理", trailingColor = Color(0xFF2563EB)) {\n                    context.startActivity(Intent(context, ProxySubscriptionActivity::class.java))\n                }\n                RefDivider()\n                RefToolRow(Icons.Rounded.Shield, Color(0xFF2563EB), "广告过滤", "代理串联 · 规则 REJECT · 独立兜底", trailingText = "管理", trailingColor = Color(0xFF2563EB)) {\n                    context.startActivity(Intent(context, ProxyAdblockChainActivity::class.java))\n                }\n                RefDivider()\n                RefToolRow(Icons.Rounded.Public, Color(0xFFF59E0B), "CNIP 设置", "国内 IPv4/IPv6 自动直连", trailingText = "设置", trailingColor = Color(0xFF2563EB)) {\n                    context.startActivity(Intent(context, ProxyAdvancedSettingsActivity::class.java).putExtra("focus", "cnip"))\n                }''')

text = text.replace('''                RefToolRow(Icons.Rounded.Language, Color(0xFF2563EB), "更新 WebUI", "Zashboard · MetaCubeXD", trailingText = "更新", trailingColor = Color(0xFF2563EB)) {''', '''                RefToolRow(Icons.Rounded.Language, Color(0xFF2563EB), "WebUI 管理", "Zashboard · 本机面板与修复", trailingText = "管理", trailingColor = Color(0xFF2563EB)) {''')
text = text.replace('''                RefToolRow(Icons.Rounded.Memory, Color(0xFF334155), "更新核心", "下载并安装内核二进制", trailingText = state.core.ifBlank { "Mihomo" }, trailingColor = Color(0xFF2563EB)) {''', '''                RefToolRow(Icons.Rounded.Memory, Color(0xFF334155), "内核管理", "下载、更新与维护内核", trailingText = state.core.ifBlank { "Mihomo" }, trailingColor = Color(0xFF2563EB)) {''')

# Root proxy boot toggle, not the legacy standalone VPN preference.
text = text.replace('var autoStart by remember { mutableStateOf(prefs.getBoolean("autoStartVpn", false)) }', 'var autoStart by remember { mutableStateOf(prefs.getBoolean("proxyRootAutoStart", false)) }')
text = text.replace('prefs.edit().putBoolean("autoStartVpn", enabled).apply()', 'prefs.edit().putBoolean("proxyRootAutoStart", enabled).apply()')

# The settings entry must lead to the real runtime selector, not only the downloader.
text = text.replace('''                RefValueRow("核心选择", state.core, Icons.Rounded.Memory, Color(0xFF334155), highlightValue = true) {\n                    context.startActivity(Intent(context, ProxyCoreActivity::class.java))\n                }''', '''                RefValueRow("运行核心", state.core, Icons.Rounded.Memory, Color(0xFF334155), highlightValue = true) {\n                    context.startActivity(Intent(context, ProxyAdvancedSettingsActivity::class.java).putExtra("focus", "core"))\n                }''')

# Add the actual advanced runtime controls into the latest settings page.
needle = '''                RefValueRow("当前配置", state.config, Icons.Rounded.Description, Color(0xFF8B5CF6), highlightValue = true) {\n                    context.startActivity(Intent(context, ProxySubscriptionActivity::class.java))\n                }'''
replacement = needle + '''\n                RefDivider()\n                RefValueRow("高级代理配置", "应用范围 · DNS · QUIC · CNIP · 共享 · 绕过", Icons.Rounded.Tune, Color(0xFF0EA5E9), highlightValue = true) {\n                    context.startActivity(Intent(context, ProxyAdvancedSettingsActivity::class.java))\n                }'''
text = text.replace(needle, replacement)

# Strict IPv4 leak protection is a real option and must not disappear in the newest UI.
text = text.replace('''        val values = listOf(\n            ProxyRuntimeProfile.Ipv6.ENABLE to "启用 IPv6",\n            ProxyRuntimeProfile.Ipv6.BYPASS to "IPv6 不进核心",\n            ProxyRuntimeProfile.Ipv6.DISABLE to "禁用系统 IPv6",\n        )''', '''        val values = listOf(\n            ProxyRuntimeProfile.Ipv6.ENABLE to "启用 IPv6",\n            ProxyRuntimeProfile.Ipv6.BYPASS to "IPv6 不进核心",\n            ProxyRuntimeProfile.Ipv6.STRICT to "严格 IPv4 防泄漏",\n            ProxyRuntimeProfile.Ipv6.DISABLE to "禁用系统 IPv6",\n        )''')

# Hard audit: no known placeholder/cross-feature navigation may survive.
for forbidden in [
    'context.startActivity(Intent(context, CompactMainActivity::class.java))',
    '网络匹配后端尚未接入',
    '共享网络控制后端尚未接入',
    '自定义绕过规则后端尚未接入',
    'CNIP 下载源和运行时应用后端尚未接入',
]:
    if forbidden in text:
        raise SystemExit(f"test.35 audit failed; stale UI mapping remains: {forbidden}")

for required in [
    'ProxyAppSelectionActivity::class.java',
    'ProxyNetworkAutomationActivity::class.java',
    'ProxyAdblockChainActivity::class.java',
    'ProxyAdvancedSettingsActivity::class.java',
    'proxyRootAutoStart',
    'ProxyRuntimeProfile.Ipv6.STRICT',
]:
    if required not in text:
        raise SystemExit(f"test.35 audit failed; missing mapping: {required}")

ref.write_text(text, encoding="utf-8")
print("test.35 latest UI fusion patch applied")
