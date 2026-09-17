from pathlib import Path


def replace_once(path: str, old: str, new: str) -> None:
    p = Path(path)
    text = p.read_text(encoding="utf-8")
    if old not in text:
        raise SystemExit(f"missing patch anchor in {path}: {old[:120]!r}")
    text = text.replace(old, new, 1)
    p.write_text(text, encoding="utf-8")


# Version
replace_once(
    "android-app/app/build.gradle.kts",
    'versionCode = 433\n        versionName = "0.4.0-test.33"',
    'versionCode = 434\n        versionName = "0.4.0-test.34"',
)

# Make proxy workspace the only launcher. Keep the old compact ad-block UI internal only
# so no rule-management capability is destroyed while the new integrated page absorbs it.
replace_once(
    "android-app/app/src/main/AndroidManifest.xml",
    '''        <activity android:name=".CompactMainActivity" android:exported="true" android:windowSoftInputMode="adjustResize">\n            <intent-filter><action android:name="android.intent.action.MAIN" /><category android:name="android.intent.category.LAUNCHER" /></intent-filter>\n        </activity>''',
    '''        <activity android:name=".CompactMainActivity" android:exported="false" android:windowSoftInputMode="adjustResize" />''',
)
replace_once(
    "android-app/app/src/main/AndroidManifest.xml",
    '''        <activity android:name=".ReferenceProxyActivity" android:label="代理" android:exported="false" />''',
    '''        <activity android:name=".ReferenceProxyActivity" android:label="辟尘" android:exported="true">\n            <intent-filter><action android:name="android.intent.action.MAIN" /><category android:name="android.intent.category.LAUNCHER" /></intent-filter>\n        </activity>''',
)

# Brand the real launcher as Bichen rather than the temporary BoxProxy working title.
replace_once(
    "android-app/app/src/main/java/io/github/xgl34222220/bichen/ReferenceProxyActivity.kt",
    '                    "BoxProxy",',
    '                    "辟尘",',
)

# Root proxy must suspend an explicitly configured independent fallback even when the
# chained REJECT provider itself is turned off. This prevents two DNS/routing paths from
# running at the same time.
root_path = Path("android-app/app/src/main/java/io/github/xgl34222220/bichen/RootProxyManager.java")
root = root_path.read_text(encoding="utf-8")
root = root.replace(
    '''        boolean chainEntered=false;\n        if(profile.adblockChain){\n            stage(progress,"切换到代理串联去广告，暂停独立 DNS / hosts 过滤…");\n            ProxyAdblockCoordinator.enter(context);chainEntered=true;\n        }''',
    '''        boolean adblockCoordinatorEntered=false;\n        boolean independentFallback=prefs.getBoolean("proxyAdblockFallbackEnabled",false);\n        if(profile.adblockChain||independentFallback){\n            stage(progress,profile.adblockChain?"切换到代理串联去广告，暂停独立 DNS / hosts 过滤…":"暂停独立广告过滤，避免与 Root 代理并行…");\n            ProxyAdblockCoordinator.enter(context);adblockCoordinatorEntered=true;\n        }''',
    1,
)
root = root.replace(
    '        }catch(Exception startFailure){if(chainEntered)ProxyAdblockCoordinator.exit(context);throw startFailure;}',
    '        }catch(Exception startFailure){if(adblockCoordinatorEntered)ProxyAdblockCoordinator.exit(context);throw startFailure;}',
    1,
)
if "chainEntered" in root:
    raise SystemExit("stale chainEntered marker remains in RootProxyManager.java")
root_path.write_text(root, encoding="utf-8")

# Persistent fallback semantics: once the integrated ad-filter page owns fallback state,
# proxy shutdown restores the selected DNS VPN or Root hosts mode. Legacy users without
# the new preference retain the old resume behavior.
coord_path = Path("android-app/app/src/main/java/io/github/xgl34222220/bichen/ProxyAdblockCoordinator.java")
coord = coord_path.read_text(encoding="utf-8")
old_exit = '''    static void exit(Context context) {\n        Context app = context.getApplicationContext();\n        SharedPreferences prefs = app.getSharedPreferences("bichen", Context.MODE_PRIVATE);\n        boolean active = prefs.getBoolean("proxyAdblockChainActive", false);\n        boolean resumeDns = prefs.getBoolean("proxyResumeDnsAfterChain", false);\n        boolean restoreHosts = prefs.getBoolean("proxyRestoreHostsAfterChain", false);\n        if (!active && !resumeDns && !restoreHosts) return;\n\n        prefs.edit().putBoolean("proxyAdblockChainActive", false).apply();\n        if (resumeDns && prefs.getBoolean("vpnWanted", false) && VpnService.prepare(app) == null) {\n            try {\n                Intent start = new Intent(app, DnsVpnService.class).setAction(DnsVpnService.ACTION_START);\n                if (Build.VERSION.SDK_INT >= 26) app.startForegroundService(start); else app.startService(start);\n                prefs.edit().remove("proxyResumeDnsAfterChain").remove("proxyRestoreHostsAfterChain").apply();\n                return;\n            } catch (Exception ignored) { }\n        }\n\n        if (restoreHosts || prefs.getBoolean("vpnRestoreHosts", false)) {\n            try {\n                JSONObject before = RootBridge.status(app);\n                boolean disabled = before.optBoolean("moduleDisabled", false) || before.optBoolean("moduleRemovalPending", false);\n                if (before.optBoolean("installed", false) && !disabled) RootBridge.run(app, "enable");\n            } catch (Exception ignored) { }\n        }\n        prefs.edit().remove("proxyResumeDnsAfterChain").remove("proxyRestoreHostsAfterChain").apply();\n    }'''
new_exit = '''    static void exit(Context context) {\n        Context app = context.getApplicationContext();\n        SharedPreferences prefs = app.getSharedPreferences("bichen", Context.MODE_PRIVATE);\n        boolean active = prefs.getBoolean("proxyAdblockChainActive", false);\n        boolean resumeDns = prefs.getBoolean("proxyResumeDnsAfterChain", false);\n        boolean restoreHosts = prefs.getBoolean("proxyRestoreHostsAfterChain", false);\n        boolean fallbackConfigured = prefs.contains("proxyAdblockFallbackEnabled");\n        boolean fallbackEnabled = prefs.getBoolean("proxyAdblockFallbackEnabled", false);\n        String fallbackMode = prefs.getString("proxyAdblockFallbackMode", "vpn");\n        if (!active && !resumeDns && !restoreHosts && !fallbackEnabled) return;\n\n        prefs.edit().putBoolean("proxyAdblockChainActive", false).apply();\n\n        if (fallbackConfigured) {\n            if (fallbackEnabled && "vpn".equals(fallbackMode)) {\n                if (VpnService.prepare(app) == null) {\n                    try {\n                        prefs.edit().putBoolean("vpnWanted", true).remove("vpnError").apply();\n                        Intent start = new Intent(app, DnsVpnService.class).setAction(DnsVpnService.ACTION_START);\n                        if (Build.VERSION.SDK_INT >= 26) app.startForegroundService(start); else app.startService(start);\n                    } catch (Exception e) {\n                        prefs.edit().putString("vpnError", "独立 DNS 去广告恢复失败：" + e.getClass().getSimpleName()).apply();\n                    }\n                } else {\n                    prefs.edit().putString("vpnError", "独立 DNS 去广告需要重新确认 VPN 授权").apply();\n                }\n            } else if (fallbackEnabled && "module".equals(fallbackMode)) {\n                try {\n                    JSONObject before = RootBridge.status(app);\n                    boolean disabled = before.optBoolean("moduleDisabled", false) || before.optBoolean("moduleRemovalPending", false);\n                    if (before.optBoolean("installed", false) && !disabled) RootBridge.run(app, "enable");\n                } catch (Exception ignored) { }\n            }\n            prefs.edit().remove("proxyResumeDnsAfterChain").remove("proxyRestoreHostsAfterChain").apply();\n            return;\n        }\n\n        if (resumeDns && prefs.getBoolean("vpnWanted", false) && VpnService.prepare(app) == null) {\n            try {\n                Intent start = new Intent(app, DnsVpnService.class).setAction(DnsVpnService.ACTION_START);\n                if (Build.VERSION.SDK_INT >= 26) app.startForegroundService(start); else app.startService(start);\n                prefs.edit().remove("proxyResumeDnsAfterChain").remove("proxyRestoreHostsAfterChain").apply();\n                return;\n            } catch (Exception ignored) { }\n        }\n\n        if (restoreHosts || prefs.getBoolean("vpnRestoreHosts", false)) {\n            try {\n                JSONObject before = RootBridge.status(app);\n                boolean disabled = before.optBoolean("moduleDisabled", false) || before.optBoolean("moduleRemovalPending", false);\n                if (before.optBoolean("installed", false) && !disabled) RootBridge.run(app, "enable");\n            } catch (Exception ignored) { }\n        }\n        prefs.edit().remove("proxyResumeDnsAfterChain").remove("proxyRestoreHostsAfterChain").apply();\n    }'''
if old_exit not in coord:
    raise SystemExit("ProxyAdblockCoordinator.exit anchor not found")
coord_path.write_text(coord.replace(old_exit, new_exit, 1), encoding="utf-8")

# Integrated ad-filter page now owns the independent fallback controls.
ad_path = Path("android-app/app/src/main/java/io/github/xgl34222220/bichen/ProxyAdblockChainActivity.kt")
ad = ad_path.read_text(encoding="utf-8")
ad = ad.replace(
    '''import android.os.Bundle\nimport androidx.activity.ComponentActivity\nimport androidx.activity.compose.setContent''',
    '''import android.app.Activity\nimport android.os.Bundle\nimport androidx.activity.ComponentActivity\nimport androidx.activity.compose.rememberLauncherForActivityResult\nimport androidx.activity.compose.setContent\nimport androidx.activity.result.contract.ActivityResultContracts''',
    1,
)
ad = ad.replace(
    '''    val running: Boolean = false,\n    val hitCount: Long = 0L,\n    val message: String = "",''',
    '''    val running: Boolean = false,\n    val hitCount: Long = 0L,\n    val vpnFallbackRunning: Boolean = false,\n    val hostsFallbackRunning: Boolean = false,\n    val message: String = "",''',
    1,
)
ad = ad.replace(
    '''    var notice by remember { mutableStateOf("") }\n    var chainEnabled by remember(revision) { mutableStateOf(prefs.getBoolean("proxyAdblockChain", true)) }''',
    '''    var notice by remember { mutableStateOf("") }\n    var chainEnabled by remember(revision) { mutableStateOf(prefs.getBoolean("proxyAdblockChain", true)) }\n    var fallbackEnabled by remember(revision) {\n        mutableStateOf(prefs.getBoolean("proxyAdblockFallbackEnabled", prefs.getBoolean("vpnWanted", false)))\n    }\n    var fallbackMode by remember(revision) {\n        mutableStateOf((prefs.getString("proxyAdblockFallbackMode", adController.protectionMode()) ?: "vpn").let { if (it == "module") "module" else "vpn" })\n    }\n    val vpnPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->\n        if (result.resultCode == Activity.RESULT_OK && prefs.getBoolean("proxyAdblockFallbackEnabled", false)) {\n            adController.startVpn()\n            notice = "独立 DNS 去广告已启用"\n            revision++\n        } else if (result.resultCode != Activity.RESULT_OK) {\n            prefs.edit().putBoolean("proxyAdblockFallbackEnabled", false).apply()\n            notice = "未获得 VPN 授权，独立去广告保持关闭"\n            revision++\n        }\n    }''',
    1,
)
ad = ad.replace(
    '''            val rules = adController.rulesSnapshot()\n            val state = proxyController.state()''',
    '''            val rules = adController.rulesSnapshot()\n            val state = proxyController.state()\n            val independent = adController.homeSnapshot()''',
    1,
)
ad = ad.replace(
    '''            ChainSnapshot(rules, state.running, hits, state.message)''',
    '''            ChainSnapshot(rules, state.running, hits, independent.vpnRunning, independent.moduleEnabled, state.message)''',
    1,
)
anchor = '''    fun toggleSource(item: RuleSourceItem) {\n        if (busy) return\n        scope.launch {'''
if anchor not in ad:
    raise SystemExit("toggleSource anchor missing")
fallback_fn = '''    fun applyFallback(enabled: Boolean, mode: String) {\n        if (busy) return\n        prefs.edit()\n            .putBoolean("proxyAdblockFallbackEnabled", enabled)\n            .putString("proxyAdblockFallbackMode", mode)\n            .putBoolean("autoStartVpn", enabled && mode == "vpn")\n            .apply()\n        fallbackEnabled = enabled\n        fallbackMode = mode\n        if (snapshot.running) {\n            notice = if (enabled) "已保存；Root 代理停止后自动恢复${if (mode == "vpn") "独立 DNS 去广告" else "Root hosts 去广告"}" else "已关闭代理停止后的独立去广告"\n            revision++\n            return\n        }\n        scope.launch {\n            busy = true\n            try {\n                if (!enabled) {\n                    if (snapshot.vpnFallbackRunning || prefs.getBoolean("vpnWanted", false)) adController.stopVpn()\n                    if (snapshot.hostsFallbackRunning) adController.toggleModuleProtection(true)\n                    notice = "独立去广告已关闭"\n                } else if (mode == "vpn") {\n                    if (snapshot.hostsFallbackRunning) adController.toggleModuleProtection(true)\n                    val prepare = adController.prepareVpn()\n                    if (prepare != null) {\n                        busy = false\n                        vpnPermissionLauncher.launch(prepare)\n                        return@launch\n                    }\n                    adController.startVpn()\n                    notice = "独立 DNS 去广告已启用；代理启动时会自动暂停"\n                } else {\n                    if (snapshot.vpnFallbackRunning || prefs.getBoolean("vpnWanted", false)) adController.stopVpn()\n                    if (!snapshot.hostsFallbackRunning) adController.toggleModuleProtection(false)\n                    notice = "Root hosts 去广告已启用；代理启动时会自动暂停"\n                }\n            } catch (cancel: CancellationException) {\n                throw cancel\n            } catch (error: Exception) {\n                notice = error.message ?: "独立去广告切换失败"\n                prefs.edit().putBoolean("proxyAdblockFallbackEnabled", false).apply()\n                fallbackEnabled = false\n            } finally {\n                busy = false\n                revision++\n            }\n        }\n    }\n\n'''
ad = ad.replace(anchor, fallback_fn + anchor, 1)
flow_anchor = '''        item("source-title") { ChainSectionLabel("规则源") }'''
if flow_anchor not in ad:
    raise SystemExit("source-title anchor missing")
fallback_ui = '''        item("fallback") {\n            Surface(shape = RoundedCornerShape(22.dp), color = if (dark) t.elevatedCardBackground else Color.White, shadowElevation = if (dark) 0.dp else 1.dp) {\n                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {\n                    Row(verticalAlignment = Alignment.CenterVertically) {\n                        Box(Modifier.size(42.dp).background(MaterialTheme.colorScheme.secondary.copy(alpha = .10f), RoundedCornerShape(13.dp)), contentAlignment = Alignment.Center) {\n                            Icon(Icons.Rounded.Dns, null, tint = MaterialTheme.colorScheme.secondary)\n                        }\n                        Spacer(Modifier.width(12.dp))\n                        Column(Modifier.weight(1f)) {\n                            Text("代理关闭后的独立去广告", color = t.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold)\n                            val fallbackState = when {\n                                snapshot.running && fallbackEnabled -> "代理运行中 · 独立模式已暂停"\n                                !fallbackEnabled -> "关闭"\n                                fallbackMode == "vpn" && snapshot.vpnFallbackRunning -> "DNS VPN 正在运行"\n                                fallbackMode == "module" && snapshot.hostsFallbackRunning -> "Root hosts 正在运行"\n                                else -> "已启用 · 等待恢复"\n                            }\n                            Text(fallbackState, color = t.textSecondary, fontSize = 11.sp)\n                        }\n                        Switch(checked = fallbackEnabled, onCheckedChange = { applyFallback(it, fallbackMode) }, enabled = !busy)\n                    }\n                    if (fallbackEnabled) {\n                        HorizontalDivider(color = if (dark) t.outline else Color(0xFFF1F5F9))\n                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {\n                            FilterChip(\n                                selected = fallbackMode == "vpn",\n                                onClick = { if (fallbackMode != "vpn") applyFallback(true, "vpn") },\n                                label = { Text("DNS VPN") },\n                                leadingIcon = { Icon(Icons.Rounded.Dns, null, Modifier.size(16.dp)) },\n                                modifier = Modifier.weight(1f),\n                                enabled = !busy,\n                            )\n                            FilterChip(\n                                selected = fallbackMode == "module",\n                                onClick = { if (fallbackMode != "module") applyFallback(true, "module") },\n                                label = { Text("Root hosts") },\n                                leadingIcon = { Icon(Icons.Rounded.AdminPanelSettings, null, Modifier.size(16.dp)) },\n                                modifier = Modifier.weight(1f),\n                                enabled = !busy,\n                            )\n                        }\n                        Text("只在代理停止时运行；启动 Root 代理会自动暂停，停止代理后按这里的选择恢复。", color = t.textSecondary, fontSize = 10.sp, lineHeight = 15.sp)\n                    }\n                }\n            }\n        }\n\n'''
ad = ad.replace(flow_anchor, fallback_ui + flow_anchor, 1)
ad_path.write_text(ad, encoding="utf-8")

print("test.34 proxy-first patch applied")
