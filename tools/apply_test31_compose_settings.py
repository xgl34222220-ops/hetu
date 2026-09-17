#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

def replace_once(rel, old, new):
    path = ROOT / rel
    text = path.read_text(encoding='utf-8')
    if old not in text:
        raise SystemExit(f'patch anchor missing in {rel}: {old[:140]!r}')
    path.write_text(text.replace(old, new, 1), encoding='utf-8')

# Version.
replace_once(
    'android-app/app/build.gradle.kts',
    'versionCode = 430\n        versionName = "0.4.0-test.30"',
    'versionCode = 431\n        versionName = "0.4.0-test.31"',
)

# Latest Compose shell must never jump back into the legacy View settings pages.
ref = 'android-app/app/src/main/java/io/github/xgl34222220/bichen/ReferenceProxyActivity.kt'
replace_once(ref,
    'context.startActivity(Intent(context, ProxyNetworkMatchActivity::class.java))',
    'context.startActivity(Intent(context, ProxyNetworkAutomationActivity::class.java))')
replace_once(ref,
    'context.startActivity(Intent(context, RootTproxyActivity::class.java).putExtra("focus", "sharing"))',
    'context.startActivity(Intent(context, ProxyAdvancedSettingsActivity::class.java).putExtra("focus", "sharing"))')
replace_once(ref,
    'context.startActivity(Intent(context, RootTproxyActivity::class.java).putExtra("focus", "bypass"))',
    'context.startActivity(Intent(context, ProxyAdvancedSettingsActivity::class.java).putExtra("focus", "bypass"))')
replace_once(ref,
    'context.startActivity(Intent(context, RootTproxyActivity::class.java).putExtra("focus", "cnip"))',
    'context.startActivity(Intent(context, ProxyAdvancedSettingsActivity::class.java).putExtra("focus", "cnip"))')
replace_once(ref,
    'context.startActivity(Intent(context, RootTproxyActivity::class.java))',
    'context.startActivity(Intent(context, ProxyAdvancedSettingsActivity::class.java))')
replace_once(ref,
    'ProxyRuntimeProfile.Ipv6.BYPASS to "IPv6 不进核心",\n            ProxyRuntimeProfile.Ipv6.DISABLE to "禁用系统 IPv6",',
    'ProxyRuntimeProfile.Ipv6.BYPASS to "IPv6 不进核心",\n            ProxyRuntimeProfile.Ipv6.STRICT to "严格 IPv4 防泄漏",\n            ProxyRuntimeProfile.Ipv6.DISABLE to "禁用系统 IPv6",')

# New Compose activities.
manifest = 'android-app/app/src/main/AndroidManifest.xml'
replace_once(manifest,
    '        <activity android:name=".RootTproxyActivity" android:label="基础代理配置" android:exported="false" />',
    '        <activity android:name=".ProxyAdvancedSettingsActivity" android:label="高级代理配置" android:exported="false" />\n'
    '        <activity android:name=".ProxyNetworkAutomationActivity" android:label="网络匹配" android:exported="false" />\n'
    '        <activity android:name=".RootTproxyActivity" android:label="基础代理配置（旧）" android:exported="false" />')

# Root desired-running state belongs in the control plane, not in one particular UI.
manager = 'android-app/app/src/main/java/io/github/xgl34222220/bichen/RootProxyManager.java'
replace_once(manager,
    '        if(!warning.isEmpty())result.put("warning",warning);\n        return result;',
    '        if(!warning.isEmpty())result.put("warning",warning);\n        prefs.edit().putBoolean("proxyRootWanted",true).remove("proxyRootBootError").apply();\n        return result;')
replace_once(manager,
    '        stage(progress,"网络规则与临时 IPv6 状态已恢复");\n        return r;',
    '        prefs.edit().putBoolean("proxyRootWanted",false).apply();\n        stage(progress,"网络规则与临时 IPv6 状态已恢复");\n        return r;')

# Do not expose preferences that are not yet wired into the shared-network data plane.
advanced = 'android-app/app/src/main/java/io/github/xgl34222220/bichen/ProxyAdvancedSettingsActivity.kt'
replace_once(advanced,
'''                AdvancedSwitchRow(Icons.Rounded.WifiTethering, Color(0xFF10B981), "接管共享网络", "将热点/USB 转发流量纳入 Root 透明代理", prefs.getBoolean("proxySharedNetwork", false)) { putBool("proxySharedNetwork", it) }
                AdvancedDivider()
                AdvancedValueRow(Icons.Rounded.Router, Color(0xFF0EA5E9), "共享接口", setSummary("proxySharedInterfaces")) {
                    editSet("proxySharedInterfaces", "共享接口", "每行一个热点/USB 接口，例如 ap+、wlan1、rndis0。留空表示自动识别。")
                }
                AdvancedDivider()
                AdvancedValueRow(Icons.Rounded.Devices, Color(0xFF8B5CF6), "客户端 MAC", setSummary("proxySharedMacs")) {
                    editSet("proxySharedMacs", "客户端 MAC", "每行一个 MAC 地址，例如 AA:BB:CC:DD:EE:FF。test.31 仅保存策略，MAC 数据面在下一阶段接入前不会假报已过滤。")
                }''',
'''                AdvancedSwitchRow(Icons.Rounded.WifiTethering, Color(0xFF10B981), "接管共享网络", "将热点/USB 转发流量纳入 Root 透明代理", prefs.getBoolean("proxySharedNetwork", false)) { putBool("proxySharedNetwork", it) }
                AdvancedInfoRow(Icons.Rounded.Router, Color(0xFF0EA5E9), "当前接管方式", "自动处理进入 PREROUTING 的共享流量；接口/MAC 精细过滤尚未开放")''')

# The network page is a new standalone Compose file; keep it dependency-light and explicit.
network = 'android-app/app/src/main/java/io/github/xgl34222220/bichen/ProxyNetworkAutomationActivity.kt'
replace_once(network,
    'import androidx.compose.foundation.layout.*\nimport androidx.compose.foundation.shape.RoundedCornerShape',
    'import androidx.compose.foundation.layout.*\nimport androidx.compose.foundation.lazy.LazyColumn\nimport androidx.compose.foundation.shape.RoundedCornerShape')
replace_once(network, 'import androidx.core.content.ContextCompat\n', '')
replace_once(network, 'ContextCompat.checkSelfPermission(context, Manifest.permission.NEARBY_WIFI_DEVICES)', 'context.checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES)')
replace_once(network, 'ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)', 'context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)')

print('test.31 Compose settings migration applied')
