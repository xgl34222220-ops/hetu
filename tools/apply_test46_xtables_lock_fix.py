from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
BUILD = ROOT / "android-app/app/build.gradle.kts"
SHELL = ROOT / "android-app/app/src/main/assets/proxy-root-v3.sh"
UI = ROOT / "android-app/app/src/main/java/io/github/xgl34222220/bichen/ReferenceProxyActivity.kt"
INSPECTOR = ROOT / "android-app/app/src/main/java/io/github/xgl34222220/bichen/ProxyRuntimeInspector.kt"

# Version
text = BUILD.read_text(encoding="utf-8")
text = text.replace('versionCode = 445', 'versionCode = 446', 1)
text = text.replace('versionName = "0.4.0-test.45"', 'versionName = "0.4.0-test.46"', 1)
if 'versionCode = 446' not in text or 'versionName = "0.4.0-test.46"' not in text:
    raise SystemExit('test46: version patch failed')
BUILD.write_text(text, encoding="utf-8")

# Root controller: every iptables/ip6tables call must wait for xtables.lock instead of
# failing instantly when netd, another root firewall, or our own status read has the lock.
text = SHELL.read_text(encoding="utf-8")
if 'xt4(){ command iptables -w 15 "$@"; }' not in text:
    # Rewrite executable tokens first, then restore availability checks and save tools.
    text = re.sub(r'\bip6tables\b', 'xt6', text)
    text = re.sub(r'\biptables\b', 'xt4', text)
    text = text.replace('ip6xt4', 'ip6tables')
    text = text.replace('xt6-save', 'ip6tables-save')
    text = text.replace('xt4-save', 'iptables-save')
    text = text.replace('has xt6', 'has ip6tables')
    text = text.replace('has xt4', 'has iptables')
    # Preserve user-facing wording in diagnostics/errors.
    text = text.replace('系统缺少 xt4', '系统缺少 iptables')
    text = text.replace('当前 xt4', '当前 iptables')
    text = text.replace('当前 xt6', '当前 ip6tables')
    text = text.replace('内核或 xt4', '内核或 iptables')
    anchor = 'has(){ command -v "$1" >/dev/null 2>&1; }\n'
    if anchor not in text:
        raise SystemExit('test46: has() anchor not found')
    wrappers = anchor + '''# Serialize with Android netd/other root firewalls on /system/etc/xtables.lock.\n# iptables itself owns the lock; -w avoids racy fail/rollback while preserving atomic rules.\nxt4(){ command iptables -w 15 "$@"; }\nxt6(){ command ip6tables -w 15 "$@"; }\n'''
    text = text.replace(anchor, wrappers, 1)

# Hard guard: transactional calls must use wrappers; direct commands are allowed only
# inside wrapper definitions, availability checks and *tables-save diagnostics.
if 'xt4(){ command iptables -w 15 "$@"; }' not in text or 'xt6(){ command ip6tables -w 15 "$@"; }' not in text:
    raise SystemExit('test46: wait wrappers missing')
if 'install_mangle4' not in text or 'scoped_mark xt4' not in text or 'unhook xt4' not in text:
    raise SystemExit('test46: IPv4 rule path did not switch to xtables wait wrapper')
SHELL.write_text(text, encoding="utf-8")

# UI: never poll status/iptables while a start/stop transaction is modifying chains.
# Also refresh after failures so a failed start cannot leave a stale "运行中" card.
text = UI.read_text(encoding="utf-8")
old_loop = '''        while (true) {\n            delay(2200)\n            refresh()\n        }\n'''
new_loop = '''        while (true) {\n            delay(2200)\n            if (operation.isBlank()) refresh()\n        }\n'''
if old_loop in text:
    text = text.replace(old_loop, new_loop, 1)
elif new_loop not in text:
    raise SystemExit('test46: home refresh loop not found')
old_finally = '''            } finally {\n                operation = ""\n            }\n        }\n    }\n\n    fun reload() {\n'''
new_finally = '''            } finally {\n                operation = ""\n                runCatching { refresh() }\n            }\n        }\n    }\n\n    fun reload() {\n'''
if old_finally in text:
    text = text.replace(old_finally, new_finally, 1)
elif new_finally not in text:
    raise SystemExit('test46: toggle finally block not found')
UI.write_text(text, encoding="utf-8")

# Runtime log test45 accidentally emitted shell "$port" instead of the Kotlin value,
# which is why the screenshot showed a blank controller-port section.
text = INSPECTOR.read_text(encoding="utf-8")
old = '''        val command = "echo '--- controller-port ---'; echo ${'$'}port; (ss -lntp 2>/dev/null || netstat -lntp 2>/dev/null || true) | grep -E ':${'$'}port([[:space:]]|${'$'})' || true; echo '--- start-state ---';'''
new = '''        val command = "echo '--- controller-port ---'; echo $port; (ss -lntp 2>/dev/null || netstat -lntp 2>/dev/null || true) | grep -E ':$port([[:space:]]|$)' || true; echo '--- start-state ---';'''
if old in text:
    text = text.replace(old, new, 1)
elif new not in text:
    raise SystemExit('test46: controller-port log interpolation block not found')
INSPECTOR.write_text(text, encoding="utf-8")

checks = {
    BUILD: ['versionCode = 446', 'versionName = "0.4.0-test.46"'],
    SHELL: ['xt4(){ command iptables -w 15 "$@"; }', 'xt6(){ command ip6tables -w 15 "$@"; }', 'scoped_mark xt4', 'unhook xt4'],
    UI: ['if (operation.isBlank()) refresh()', 'runCatching { refresh() }'],
    INSPECTOR: ["echo '--- controller-port ---'; echo $port", "grep -E ':$port([[:space:]]|$)'"],
}
for path, needles in checks.items():
    body = path.read_text(encoding="utf-8")
    for needle in needles:
        if needle not in body:
            raise SystemExit(f'test46: missing {needle} in {path}')
print('test46 xtables lock wait + transaction-safe polling + log interpolation applied')
