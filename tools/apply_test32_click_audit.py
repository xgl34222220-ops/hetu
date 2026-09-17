#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

def replace_once(rel, old, new):
    path = ROOT / rel
    text = path.read_text(encoding='utf-8')
    if old not in text:
        raise SystemExit(f'patch anchor missing in {rel}: {old[:160]!r}')
    path.write_text(text.replace(old, new, 1), encoding='utf-8')

# Version.
replace_once(
    'android-app/app/build.gradle.kts',
    'versionCode = 431\n        versionName = "0.4.0-test.31"',
    'versionCode = 432\n        versionName = "0.4.0-test.32"',
)

# Register the proxy-only application selector.
manifest = 'android-app/app/src/main/AndroidManifest.xml'
replace_once(
    manifest,
    '        <activity android:name=".ProxyNetworkAutomationActivity" android:label="网络匹配" android:exported="false" />',
    '        <activity android:name=".ProxyNetworkAutomationActivity" android:label="网络匹配" android:exported="false" />\n'
    '        <activity android:name=".ProxyAppSelectionActivity" android:label="代理应用名单" android:exported="false" />',
)

# Make every visible proxy-tool label match its real destination.
ref = 'android-app/app/src/main/java/io/github/xgl34222220/bichen/ReferenceProxyActivity.kt'
replace_once(
    ref,
    'RefToolRow(Icons.Rounded.Terminal, Color(0xFF2563EB), "脚本", "启动配置与运行文件")',
    'RefToolRow(Icons.Rounded.Terminal, Color(0xFF2563EB), "运行文件", "启动配置与运行文件")',
)
replace_once(
    ref,
    'RefToolRow(Icons.Rounded.Apps, Color(0xFFF97316), "应用管理", "分应用放行与代理相关应用列表") { context.startActivity(Intent(context, CompactMainActivity::class.java)) }',
    'RefToolRow(Icons.Rounded.Apps, Color(0xFFF97316), "应用名单", "Root 分应用代理范围") { context.startActivity(Intent(context, ProxyAppSelectionActivity::class.java)) }',
)
replace_once(
    ref,
    'RefToolRow(Icons.Rounded.Language, Color(0xFF6366F1), "更新 WebUI", "Zashboard · MetaCubeXD")',
    'RefToolRow(Icons.Rounded.Language, Color(0xFF6366F1), "WebUI 管理", "Zashboard · 本机面板与修复")',
)
replace_once(
    ref,
    'RefToolRow(Icons.Rounded.Memory, Color(0xFF334155), "更新核心", state.core)',
    'RefToolRow(Icons.Rounded.Memory, Color(0xFF334155), "内核管理", state.core)',
)
replace_once(
    ref,
    'RefValueRow("核心选择", state.core, Icons.Rounded.Memory, Color(0xFF334155))',
    'RefValueRow("内核管理", state.core, Icons.Rounded.Memory, Color(0xFF334155))',
)

# Advanced proxy settings: app list goes to the proxy-only selector and expose a real runtime-core picker.
adv = 'android-app/app/src/main/java/io/github/xgl34222220/bichen/ProxyAdvancedSettingsActivity.kt'
replace_once(
    adv,
    'context.startActivity(Intent(context, CompactMainActivity::class.java))',
    'context.startActivity(Intent(context, ProxyAppSelectionActivity::class.java))',
)
replace_once(
    adv,
    '            AdvancedGroup {\n                AdvancedValueRow(Icons.Rounded.Apps, Color(0xFFF97316), "应用范围", when (profile.appScope) {',
    '''            AdvancedGroup {
                AdvancedValueRow(Icons.Rounded.Memory, Color(0xFF334155), "运行核心", profile.core.label) {
                    showChoices("运行核心", "proxyBaseCore", listOf(
                        AdvancedChoice("Mihomo", "mihomo"),
                        AdvancedChoice("Mihomo Smart", "mihomo-smart"),
                    ))
                }
                AdvancedDivider()
                AdvancedValueRow(Icons.Rounded.Apps, Color(0xFFF97316), "应用范围", when (profile.appScope) {''',
)
replace_once(
    adv,
    '            "proxyAppScope" -> profile.appScope.id\n            "proxyDnsHijack" -> profile.dnsHijack.id',
    '            "proxyBaseCore" -> profile.core.id\n            "proxyAppScope" -> profile.appScope.id\n            "proxyDnsHijack" -> profile.dnsHijack.id',
)

# Core manager must not fall back to the legacy View settings activity.
core = 'android-app/app/src/main/java/io/github/xgl34222220/bichen/ProxyCoreActivity.kt'
replace_once(
    core,
    'context.startActivity(Intent(context, RootTproxyActivity::class.java))',
    'context.startActivity(Intent(context, ProxyAdvancedSettingsActivity::class.java))',
)

print('test.32 proxy click mapping patch applied')
