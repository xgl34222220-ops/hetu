from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
BUILD = ROOT / "android-app/app/build.gradle.kts"
STARTUP = ROOT / "android-app/app/src/main/java/io/github/xgl34222220/hetu/MihomoStartupConfig.java"
SCRIPT = ROOT / "android-app/app/src/main/assets/proxy-root-v3.sh"

# Version.
build = BUILD.read_text(encoding="utf-8")
build = build.replace('versionCode = 438', 'versionCode = 439', 1)
build = build.replace('versionName = "0.4.0-test.38"', 'versionName = "0.4.0-test.39"', 1)
if 'versionCode = 439' not in build or 'versionName = "0.4.0-test.39"' not in build:
    raise SystemExit("test39: version patch failed")
BUILD.write_text(build, encoding="utf-8")

# Runtime owns its own private listeners. A Root TPROXY session must not also inherit
# source mixed-port/port/socks-port listeners such as 7890: that caused the real-device
# bind collision shown in runtime.log. Move Hetu's private listeners away from the
# common Clash defaults as well, so an unrelated proxy app can coexist without making
# Hetu fail before its Root routing transaction is installed.
startup = STARTUP.read_text(encoding="utf-8")
startup = startup.replace('static final int TPROXY_PORT=9898;', 'static final int TPROXY_PORT=19898;', 1)
startup = startup.replace('static final int REDIRECT_PORT=9797;', 'static final int REDIRECT_PORT=19797;', 1)
startup = startup.replace('static final int DNS_PORT=1053;', 'static final int DNS_PORT=11053;', 1)
startup = startup.replace('static final int CONTROLLER_PORT=19090;', 'static final int CONTROLLER_PORT=29090;', 1)
needle = '''        yaml=removeTopLevelScalar(yaml,"tproxy-port");
        yaml=removeTopLevelScalar(yaml,"redir-port");'''
replacement = '''        yaml=removeTopLevelScalar(yaml,"tproxy-port");
        yaml=removeTopLevelScalar(yaml,"redir-port");
        // TPROXY/Redirect Root mode is the only ingress owned by this private runtime.
        // Never inherit local HTTP/SOCKS/Mixed listeners from the user's subscription;
        // they are unnecessary here and commonly collide with another Clash app on 7890.
        yaml=removeTopLevelScalar(yaml,"mixed-port");
        yaml=removeTopLevelScalar(yaml,"socks-port");
        yaml=removeTopLevelScalar(yaml,"port");'''
if needle not in startup:
    raise SystemExit("test39: runtime listener isolation anchor missing")
startup = startup.replace(needle, replacement, 1)

# In the app's unified Root backend the generated startup copy is intentionally private.
# Use the private ports unconditionally; the selected source YAML itself remains untouched.
startup = startup.replace('tp=profile.autoOverwrite||sourceTp==0?TPROXY_PORT:sourceTp;', 'tp=TPROXY_PORT;')
startup = startup.replace('rp=profile.autoOverwrite||sourceRp==0?REDIRECT_PORT:sourceRp;', 'rp=REDIRECT_PORT;')
if startup.count('tp=TPROXY_PORT;') < 3:
    raise SystemExit("test39: did not isolate every TPROXY runtime branch")
if startup.count('rp=REDIRECT_PORT;') < 2:
    raise SystemExit("test39: did not isolate every Redirect runtime branch")
STARTUP.write_text(startup, encoding="utf-8")

# Strengthen stale-core recognition: after atomic replacement, /proc/PID/exe can show
# '/data/adb/hetu/bin/core (deleted)' even if argv[0] is shortened by the runtime.
# Match both cmdline and executable target, still scoped strictly to Hetu's private path.
script = SCRIPT.read_text(encoding="utf-8")
old_pidcore = '''pidcore(){
  P="$1"; [ -r "/proc/$P/cmdline" ] || return 1
  CMD=$(tr '\\000' ' ' < "/proc/$P/cmdline" 2>/dev/null || true); case "$CMD" in *"$BASE/"*core*) return 0;; *) return 1;; esac
}'''
new_pidcore = '''pidcore(){
  P="$1"; [ -d "/proc/$P" ] || return 1
  CMD=$(tr '\\000' ' ' < "/proc/$P/cmdline" 2>/dev/null || true)
  EXE=$(readlink "/proc/$P/exe" 2>/dev/null || true)
  case "$CMD $EXE" in *"$BASE/bin/core"*) return 0;; *) return 1;; esac
}'''
if old_pidcore not in script:
    raise SystemExit("test39: pidcore block missing")
script = script.replace(old_pidcore, new_pidcore, 1)

old_orphan = '''    OCMD=$(tr '\\000' ' ' < "$PROC/cmdline" 2>/dev/null || true)
    case "$OCMD" in *"$BASE/bin/core"*)
      kill "$OPID" >/dev/null 2>&1 || true'''
new_orphan = '''    OCMD=$(tr '\\000' ' ' < "$PROC/cmdline" 2>/dev/null || true)
    OEXE=$(readlink "$PROC/exe" 2>/dev/null || true)
    case "$OCMD $OEXE" in *"$BASE/bin/core"*)
      kill "$OPID" >/dev/null 2>&1 || true'''
if old_orphan not in script:
    raise SystemExit("test39: orphan recovery block missing")
script = script.replace(old_orphan, new_orphan, 1)

# Give a deterministic, readable failure before launching if a non-Hetu process somehow
# owns one of the dedicated ports. This avoids a misleading pile of Mihomo bind errors.
ready_anchor = '''wait_ready(){
  PID="$1"; M="$2"; TP="$3"; RP="$4"; TCP="$5"; UDP="$6"; DNS="$7"; DP="$8"; CP="$9"'''
if ready_anchor not in script:
    raise SystemExit("test39: wait_ready anchor missing")
check_fn = '''check_start_ports(){
  M="$1"; TP="$2"; RP="$3"; TCP="$4"; UDP="$5"; DNS="$6"; DP="$7"; CP="$8"
  tcp_listen "$CP" && fail "控制接口端口 $CP 已被其他程序占用，请关闭冲突进程后重试"
  case "$M" in
    tproxy)
      [ "$TCP" = 0 ] || { tcp_listen "$TP" && fail "TPROXY TCP 端口 $TP 已被其他程序占用"; }
      [ "$UDP" = 0 ] || { udp_listen "$TP" && fail "TPROXY UDP 端口 $TP 已被其他程序占用"; }
    ;;
    redirect)
      [ "$TCP" = 0 ] || { tcp_listen "$RP" && fail "Redirect 端口 $RP 已被其他程序占用"; }
    ;;
    enhance)
      [ "$TCP" = 0 ] || { tcp_listen "$RP" && fail "Redirect 端口 $RP 已被其他程序占用"; }
      [ "$UDP" = 0 ] || { udp_listen "$TP" && fail "TPROXY UDP 端口 $TP 已被其他程序占用"; }
    ;;
  esac
  if [ "$DNS" = tproxy ]; then
    tcp_listen "$TP" && fail "DNS/TPROXY TCP 端口 $TP 已被其他程序占用"
    udp_listen "$TP" && fail "DNS/TPROXY UDP 端口 $TP 已被其他程序占用"
  elif [ "$DNS" = redirect ]; then
    tcp_listen "$DP" && fail "DNS TCP 端口 $DP 已被其他程序占用"
    udp_listen "$DP" && fail "DNS UDP 端口 $DP 已被其他程序占用"
  fi
}

'''
script = script.replace('wait_ready(){\n', check_fn + 'wait_ready(){\n', 1)

start_anchor = '''  stopwatchdog; cleanup; restorev6; stopcore; rm -f "$CRASH_STATE" "$SESSION"
  markused "$BYPASS_MARK" && fail "安全出站 mark 已被其他网络规则占用，未接管网络"'''
start_replacement = '''  stopwatchdog; cleanup; restorev6; stopcore; sleep 0.20; rm -f "$CRASH_STATE" "$SESSION"
  check_start_ports "$START_MODE" "$START_TP" "$START_RP" "$START_TCP" "$START_UDP" "$START_DNS" "$START_DP" "$START_CP"
  markused "$BYPASS_MARK" && fail "安全出站 mark 已被其他网络规则占用，未接管网络"'''
if start_anchor not in script:
    raise SystemExit("test39: start cleanup anchor missing")
script = script.replace(start_anchor, start_replacement, 1)
SCRIPT.write_text(script, encoding="utf-8")

# Hard audit.
checks = {
    BUILD: ['versionCode = 439', 'versionName = "0.4.0-test.39"'],
    STARTUP: [
        'TPROXY_PORT=19898', 'REDIRECT_PORT=19797', 'DNS_PORT=11053', 'CONTROLLER_PORT=29090',
        'removeTopLevelScalar(yaml,"mixed-port")', 'removeTopLevelScalar(yaml,"socks-port")',
        'removeTopLevelScalar(yaml,"port")', 'tp=TPROXY_PORT;', 'rp=REDIRECT_PORT;'
    ],
    SCRIPT: [
        'EXE=$(readlink "/proc/$P/exe"', 'OEXE=$(readlink "$PROC/exe"',
        'check_start_ports "$START_MODE"', 'sleep 0.20', '控制接口端口 $CP 已被其他程序占用'
    ],
}
for path, needles in checks.items():
    body = path.read_text(encoding="utf-8")
    for needle in needles:
        if needle not in body:
            raise SystemExit(f"test39: missing invariant {needle} in {path}")

print("test.39 private listener isolation + stale core detection fix applied")
