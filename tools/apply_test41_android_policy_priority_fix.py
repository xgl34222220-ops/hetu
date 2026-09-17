from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
BUILD = ROOT / "android-app/app/build.gradle.kts"
SCRIPT = ROOT / "android-app/app/src/main/assets/proxy-root-v3.sh"

# Version
build = BUILD.read_text(encoding="utf-8")
build = build.replace('versionCode = 440', 'versionCode = 441', 1)
build = build.replace('versionName = "0.4.0-test.40"', 'versionName = "0.4.0-test.41"', 1)
if 'versionCode = 441' not in build or 'versionName = "0.4.0-test.41"' not in build:
    raise SystemExit('test41: version patch failed')
BUILD.write_text(build, encoding='utf-8')

# test.40 placed the Bichen fwmark policy rule at 28700. On Android 16,
# normal network selection rules (including the socket's netId) run before that,
# so marked local OUTPUT traffic can be routed to wlan/rmnet before Bichen's
# local-route table is consulted. Result: Mihomo is healthy but sees 0 B/s.
script = SCRIPT.read_text(encoding='utf-8')
old = '''  P=28700; while [ "$P" -le 28799 ]; do if ! prefused "$P"; then PREF="$P"; break; fi; P=$((P+1)); done; [ -n "$PREF" ] || return 1
  savenet
}'''
new = '''  # Keep Android VPN/lockdown guards ahead of Bichen, but run before ordinary
  # network selection. 14500..14949 is intentionally a free searched range on
  # current Android 16 and prefused() still avoids OEM/custom collisions.
  P=14500; while [ "$P" -le 14949 ]; do if ! prefused "$P"; then PREF="$P"; break; fi; P=$((P+1)); done; [ -n "$PREF" ] || return 1
  savenet
}'''
if old not in script:
    raise SystemExit('test41: allocnet priority block not found')
script = script.replace(old, new, 1)

# Include the selected rule preference in status/diagnostics so future screenshots
# immediately show whether policy routing is installed ahead of Android netd rules.
old_status = '''  printf '{"ok":true,"running":%s,"pid":%s,"mode":"%s","ipv4Rules":%s,"ipv6Rules":%s,"killSwitchActive":%s,"ipv6DisabledByBichen":%s,"watchdog":%s,"recoveredStaleRules":%s,"mark":"%s","table":"%s","appScope":"%s","sharedNetwork":"%s","killSwitchRequested":"%s","log":"%s","configCheckLog":"%s"}\\n' "$STATUS_RUNNING" "$STATUS_PID" "$STATUS_MODE" "$T4" "$T6" "$([ "$K4" = true ] || [ "$K6" = true ] && echo true || echo false)" "$V6OFF" "$WD" "$RECOVERED" "$SM" "$ST" "$SCOPEV" "$SHAREV" "$KILLV" "$LOG" "$CHECKLOG"'''
new_status = '''  SP=$(state_value PREF 2>/dev/null || true)
  printf '{"ok":true,"running":%s,"pid":%s,"mode":"%s","ipv4Rules":%s,"ipv6Rules":%s,"killSwitchActive":%s,"ipv6DisabledByBichen":%s,"watchdog":%s,"recoveredStaleRules":%s,"mark":"%s","table":"%s","pref":"%s","appScope":"%s","sharedNetwork":"%s","killSwitchRequested":"%s","log":"%s","configCheckLog":"%s"}\\n' "$STATUS_RUNNING" "$STATUS_PID" "$STATUS_MODE" "$T4" "$T6" "$([ "$K4" = true ] || [ "$K6" = true ] && echo true || echo false)" "$V6OFF" "$WD" "$RECOVERED" "$SM" "$ST" "$SP" "$SCOPEV" "$SHAREV" "$KILLV" "$LOG" "$CHECKLOG"'''
if old_status not in script:
    raise SystemExit('test41: status JSON block not found')
script = script.replace(old_status, new_status, 1)
SCRIPT.write_text(script, encoding='utf-8')

checks = {
    BUILD: ['versionCode = 441', 'versionName = "0.4.0-test.41"'],
    SCRIPT: [
        'P=14500; while [ "$P" -le 14949 ]',
        'SP=$(state_value PREF',
        '"pref":"%s"',
    ],
}
for path, needles in checks.items():
    body = path.read_text(encoding='utf-8')
    for needle in needles:
        if needle not in body:
            raise SystemExit(f'test41: missing invariant {needle} in {path}')

print('test.41 Android policy-routing priority fix applied')
