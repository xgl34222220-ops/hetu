from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
BUILD = ROOT / "android-app/app/build.gradle.kts"
SCRIPT = ROOT / "android-app/app/src/main/assets/proxy-root-v3.sh"
UI = ROOT / "android-app/app/src/main/java/io/github/xgl34222220/hetu/ReferenceProxyActivity.kt"

# ---------------------------------------------------------------------------
# 1) Version
# ---------------------------------------------------------------------------
text = BUILD.read_text(encoding="utf-8")
text = text.replace('versionCode = 445', 'versionCode = 446', 1)
text = text.replace('versionName = "0.4.0-test.45"', 'versionName = "0.4.0-test.46"', 1)
if 'versionCode = 446' not in text or 'versionName = "0.4.0-test.46"' not in text:
    raise SystemExit("test46: version patch failed")
BUILD.write_text(text, encoding="utf-8")

# ---------------------------------------------------------------------------
# 2) Android shares one global xtables lock between every iptables client.
#    Network managers / firewall apps / OEM services can briefly own it while Hetu
#    installs its TPROXY transaction. All Hetu iptables/ip6tables calls must wait
#    instead of treating a transient lock as a fatal rule-install failure.
# ---------------------------------------------------------------------------
text = SCRIPT.read_text(encoding="utf-8")
anchor = '''LOCK_HELD=0\n\nLEGACY_MARK=0x2333\n'''
insert = '''LOCK_HELD=0\n\n# Android uses a global xtables lock. Another firewall/VPN/root app (or netd helper)\n# can hold it for a short window. Capture the real binaries before defining wrappers,\n# then make every direct and indirect iptables invocation wait for that lock.\n# A bounded wait avoids both spurious startup rollback and an infinite Root transaction.\nIPTABLES_REAL=$(command -v iptables 2>/dev/null || true)\nIP6TABLES_REAL=$(command -v ip6tables 2>/dev/null || true)\nif [ -n "$IPTABLES_REAL" ]; then\n  iptables(){ "$IPTABLES_REAL" -w 15 "$@"; }\nfi\nif [ -n "$IP6TABLES_REAL" ]; then\n  ip6tables(){ "$IP6TABLES_REAL" -w 15 "$@"; }\nfi\n\nLEGACY_MARK=0x2333\n'''
if anchor not in text:
    raise SystemExit("test46: root wrapper insertion point not found")
text = text.replace(anchor, insert, 1)
# Persist a useful stage while waiting/installing, so a genuine long-running lock can be diagnosed.
old_stage = '  start_stage "install-ipv4-tproxy"\n  install_mangle4'
new_stage = '  start_stage "install-ipv4-tproxy (xtables wait enabled)"\n  install_mangle4'
if old_stage not in text:
    raise SystemExit("test46: ipv4 tproxy stage marker missing")
text = text.replace(old_stage, new_stage, 1)
SCRIPT.write_text(text, encoding="utf-8")

# ---------------------------------------------------------------------------
# 3) Do not render a persisted 'wanted' bit as proof that the core is running.
#    test45 could show RUNNING behind an error sheet after an iptables rollback because
#    proxyRootWanted survived from an older successful session. Introduce a resolved
#    status state, refresh after failures, and build startup diagnostics from the action
#    that was actually attempted rather than from a stale Compose snapshot.
# ---------------------------------------------------------------------------
text = UI.read_text(encoding="utf-8")
old_state = '''    var state by remember { mutableStateOf(ProxyComposeState(running = prefs.getBoolean("proxyRootWanted", false))) }\n    var runtime by remember { mutableStateOf(ProxyRuntimeSnapshot()) }\n'''
new_state = '''    var state by remember { mutableStateOf(ProxyComposeState()) }\n    var statusResolved by remember { mutableStateOf(false) }\n    var runtime by remember { mutableStateOf(ProxyRuntimeSnapshot()) }\n'''
if old_state not in text:
    raise SystemExit("test46: initial proxy state block not found")
text = text.replace(old_state, new_state, 1)

old_refresh = '''            state = next\n            message = next.message\n            lastAt = now\n'''
new_refresh = '''            state = next\n            statusResolved = true\n            message = next.message\n            lastAt = now\n'''
if old_refresh not in text:
    raise SystemExit("test46: refresh assignment block not found")
text = text.replace(old_refresh, new_refresh, 1)

old_toggle = '''    fun toggle() {\n        if (operation.isNotBlank()) return\n        scope.launch {\n            operation = if (state.running) "正在停止…" else "正在启动…"\n            try {\n                if (state.running) controller.stop { operation = it } else controller.start { operation = it }\n                delay(250)\n                refresh()\n            } catch (cancel: CancellationException) {\n                throw cancel\n            } catch (error: Exception) {\n                val reason = error.message ?: "操作失败"\n                message = reason\n                if (!state.running) {\n                    val diagnostics = runCatching { controller.diagnostics() }.getOrDefault("").trim()\n                    startupError = buildString {\n                        append(reason)\n                        if (diagnostics.isNotBlank()) {\n                            append("\\n\\n--- Root / Mihomo 诊断 ---\\n")\n                            append(diagnostics)\n                        }\n                    }\n                }\n            } finally {\n                operation = ""\n            }\n        }\n    }\n'''
new_toggle = '''    fun toggle() {\n        if (operation.isNotBlank() || !statusResolved) return\n        val wasRunning = state.running\n        scope.launch {\n            operation = if (wasRunning) "正在停止…" else "正在启动…"\n            try {\n                if (wasRunning) controller.stop { operation = it } else controller.start { operation = it }\n                delay(250)\n                refresh()\n            } catch (cancel: CancellationException) {\n                throw cancel\n            } catch (error: Exception) {\n                val reason = error.message ?: "操作失败"\n                message = reason\n                // Always reconcile with the real Root state after a failed transaction.\n                // This prevents an old proxyRootWanted value from leaving a fake RUNNING card.\n                runCatching { refresh() }\n                if (!wasRunning) {\n                    val diagnostics = runCatching { controller.diagnostics() }.getOrDefault("").trim()\n                    startupError = buildString {\n                        append(reason)\n                        if (diagnostics.isNotBlank()) {\n                            append("\\n\\n--- Root / Mihomo 诊断 ---\\n")\n                            append(diagnostics)\n                        }\n                    }\n                }\n            } finally {\n                operation = ""\n            }\n        }\n    }\n'''
if old_toggle not in text:
    raise SystemExit("test46: toggle block not found")
text = text.replace(old_toggle, new_toggle, 1)

old_home_call = '''                RefProxyPage.Home -> RefHome(\n                    state = state,\n                    runtime = runtime,\n'''
new_home_call = '''                RefProxyPage.Home -> RefHome(\n                    state = state,\n                    statusResolved = statusResolved,\n                    runtime = runtime,\n'''
if old_home_call not in text:
    raise SystemExit("test46: RefHome call not found")
text = text.replace(old_home_call, new_home_call, 1)

old_home_sig = '''private fun RefHome(\n    state: ProxyComposeState,\n    runtime: ProxyRuntimeSnapshot,\n'''
new_home_sig = '''private fun RefHome(\n    state: ProxyComposeState,\n    statusResolved: Boolean,\n    runtime: ProxyRuntimeSnapshot,\n'''
if old_home_sig not in text:
    raise SystemExit("test46: RefHome signature not found")
text = text.replace(old_home_sig, new_home_sig, 1)

old_dot = '''                                Box(Modifier.size(9.dp).background(if (state.running) scheme.primary else t.danger, CircleShape))\n'''
new_dot = '''                                Box(Modifier.size(9.dp).background(if (!statusResolved) t.textMuted else if (state.running) scheme.primary else t.danger, CircleShape))\n'''
if old_dot not in text:
    raise SystemExit("test46: status dot not found")
text = text.replace(old_dot, new_dot, 1)

old_title = '''                                        if (state.running) "运行中" else "已停止",\n'''
new_title = '''                                        if (!statusResolved) "同步中" else if (state.running) "运行中" else "已停止",\n'''
if old_title not in text:
    raise SystemExit("test46: status title not found")
text = text.replace(old_title, new_title, 1)

old_subtitle = '''                                        if (state.running) refDuration(runtime.elapsedSeconds) else "等待启动",\n'''
new_subtitle = '''                                        if (!statusResolved) "读取 Root 实际状态" else if (state.running) refDuration(runtime.elapsedSeconds) else "等待启动",\n'''
if old_subtitle not in text:
    raise SystemExit("test46: status subtitle not found")
text = text.replace(old_subtitle, new_subtitle, 1)

UI.write_text(text, encoding="utf-8")

# ---------------------------------------------------------------------------
# Hard audit
# ---------------------------------------------------------------------------
checks = {
    BUILD: ['versionCode = 446', 'versionName = "0.4.0-test.46"'],
    SCRIPT: [
        'IPTABLES_REAL=$(command -v iptables',
        'iptables(){ "$IPTABLES_REAL" -w 15 "$@"; }',
        'ip6tables(){ "$IP6TABLES_REAL" -w 15 "$@"; }',
        'install-ipv4-tproxy (xtables wait enabled)',
    ],
    UI: [
        'var statusResolved by remember { mutableStateOf(false) }',
        'statusResolved = true',
        'val wasRunning = state.running',
        'runCatching { refresh() }',
        'if (!statusResolved) "同步中"',
        'if (!statusResolved) "读取 Root 实际状态"',
    ],
}
for path, needles in checks.items():
    body = path.read_text(encoding="utf-8")
    for needle in needles:
        if needle not in body:
            raise SystemExit(f"test46: missing invariant {needle} in {path}")
print("test46 xtables-lock wait + real runtime status synchronization applied")
