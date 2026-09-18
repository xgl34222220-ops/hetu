from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
BUILD = ROOT / "android-app/app/build.gradle.kts"
MANAGER = ROOT / "android-app/app/src/main/java/io/github/xgl34222220/hetu/RootProxyManager.java"
SCRIPT = ROOT / "android-app/app/src/main/assets/proxy-root-v3.sh"

# ---------------------------------------------------------------------------
# 1) Version: test.38 is based directly on test.37 (the proxy + adblock chain build).
# ---------------------------------------------------------------------------
build = BUILD.read_text(encoding="utf-8")
build = build.replace('versionCode = 437', 'versionCode = 438', 1)
build = build.replace('versionName = "0.4.0-test.37"', 'versionName = "0.4.0-test.38"', 1)
if 'versionCode = 438' not in build or 'versionName = "0.4.0-test.38"' not in build:
    raise SystemExit("test38: version patch failed")
BUILD.write_text(build, encoding="utf-8")

# ---------------------------------------------------------------------------
# 2) Root runtime deployment must be hot-swap safe.
#    Android/Linux returns ETXTBSY (Text file busy) when cp truncates an executable
#    that is still mapped by an old Mihomo process. Copy to a new inode and rename it
#    over the path atomically; the old process keeps its old inode until stopcore()
#    terminates it, while the next launch gets the new binary.
# ---------------------------------------------------------------------------
manager = MANAGER.read_text(encoding="utf-8")
old_prefix = '''        StringBuilder cmd=new StringBuilder("set -e; mkdir -p ")
                .append(RootBridge.quote(ROOT+"/bin")).append(' ').append(RootBridge.quote(ROOT+"/run/state")).append(' ').append(RootBridge.quote(ROOT+"/run/ruleset"))
                .append("; cp ").append(RootBridge.quote(script.getAbsolutePath())).append(' ').append(RootBridge.quote(SCRIPT))
                .append("; chmod 700 ").append(RootBridge.quote(SCRIPT)).append("; chown 0:0 ").append(RootBridge.quote(SCRIPT))
                .append("; cp ").append(RootBridge.quote(binary.getAbsolutePath())).append(' ').append(RootBridge.quote(BIN))
                .append("; chmod 700 ").append(RootBridge.quote(BIN)).append("; chown 0:0 ").append(RootBridge.quote(BIN));'''
new_prefix = '''        String deploySuffix=".new."+Long.toHexString(System.nanoTime());
        String scriptTmp=SCRIPT+deploySuffix;
        String binTmp=BIN+deploySuffix;
        StringBuilder cmd=new StringBuilder("set -e; mkdir -p ")
                .append(RootBridge.quote(ROOT+"/bin")).append(' ').append(RootBridge.quote(ROOT+"/run/state")).append(' ').append(RootBridge.quote(ROOT+"/run/ruleset"))
                .append("; cp ").append(RootBridge.quote(script.getAbsolutePath())).append(' ').append(RootBridge.quote(scriptTmp))
                .append("; chmod 700 ").append(RootBridge.quote(scriptTmp)).append("; chown 0:0 ").append(RootBridge.quote(scriptTmp))
                .append("; mv -f ").append(RootBridge.quote(scriptTmp)).append(' ').append(RootBridge.quote(SCRIPT))
                .append("; cp ").append(RootBridge.quote(binary.getAbsolutePath())).append(' ').append(RootBridge.quote(binTmp))
                .append("; chmod 700 ").append(RootBridge.quote(binTmp)).append("; chown 0:0 ").append(RootBridge.quote(binTmp))
                .append("; mv -f ").append(RootBridge.quote(binTmp)).append(' ').append(RootBridge.quote(BIN));'''
if old_prefix not in manager:
    raise SystemExit("test38: RootProxyManager direct runtime cp block not found")
manager = manager.replace(old_prefix, new_prefix, 1)

# Config is not executable, but use the same atomic deployment rule so a retry can
# never leave a half-written startup config after a killed shell/root transaction.
old_cfg = '''        if(includeConfig)cmd.append("; cp ").append(RootBridge.quote(cfg.getAbsolutePath())).append(' ').append(RootBridge.quote(CONFIG))
                .append("; chmod 600 ").append(RootBridge.quote(CONFIG)).append("; chown 0:0 ").append(RootBridge.quote(CONFIG));'''
new_cfg = '''        if(includeConfig){
            String configTmp=CONFIG+deploySuffix;
            cmd.append("; cp ").append(RootBridge.quote(cfg.getAbsolutePath())).append(' ').append(RootBridge.quote(configTmp))
                    .append("; chmod 600 ").append(RootBridge.quote(configTmp)).append("; chown 0:0 ").append(RootBridge.quote(configTmp))
                    .append("; mv -f ").append(RootBridge.quote(configTmp)).append(' ').append(RootBridge.quote(CONFIG));
        }'''
if old_cfg not in manager:
    raise SystemExit("test38: startup config deploy block not found")
manager = manager.replace(old_cfg, new_cfg, 1)
MANAGER.write_text(manager, encoding="utf-8")

# ---------------------------------------------------------------------------
# 3) Shell function variables are global in /system/bin/sh. test.37's start() used
#    BIN/M/P/etc.; cleanup()/unhook()/loadnet() reused the same names. That can turn
#    the intended Mihomo launch into ip6tables -d ... -f ..., or corrupt mode/PID.
#    Give the start/status state unique names so helper functions cannot overwrite it.
# ---------------------------------------------------------------------------
text = SCRIPT.read_text(encoding="utf-8")
start_marker = "start(){\n"
status_marker = "\nstatus(){\n"
case_marker = '\ncase "${1:-status}" in\n'
if start_marker not in text or status_marker not in text or case_marker not in text:
    raise SystemExit("test38: controller function markers missing")
start_at = text.index(start_marker)
status_at = text.index(status_marker, start_at)
case_at = text.index(case_marker, status_at)
start = text[start_at:status_at]
status = text[status_at:case_at]

old_start_assign = (
    '  BIN="$1"; CFG="$2"; M="$3"; TP="$4"; RP="$5"; V6="$6"; TCP="$7"; UDP="$8"; '
    'DNS="$9"; QUIC="${10}"; DP="${11}"; CP="${12}"; S="${13}"; UIDS="${14}"; '
    'SHARE="${15}"; KILL="${16}"; CIDRS="${17}"; IFACES="${18}"'
)
new_start_assign = (
    '  START_BIN="$1"; START_CFG="$2"; START_MODE="$3"; START_TP="$4"; START_RP="$5"; '
    'START_V6="$6"; START_TCP="$7"; START_UDP="$8"; START_DNS="$9"; START_QUIC="${10}"; '
    'START_DP="${11}"; START_CP="${12}"; START_SCOPE="${13}"; START_UIDS="${14}"; '
    'START_SHARE="${15}"; START_KILL="${16}"; START_CIDRS="${17}"; START_IFACES="${18}"'
)
if old_start_assign not in start:
    raise SystemExit("test38: start() argument layout changed")
start = start.replace(old_start_assign, new_start_assign, 1)

start_vars = {
    "BIN": "START_BIN", "CFG": "START_CFG", "M": "START_MODE", "TP": "START_TP",
    "RP": "START_RP", "V6": "START_V6", "TCP": "START_TCP", "UDP": "START_UDP",
    "DNS": "START_DNS", "QUIC": "START_QUIC", "DP": "START_DP", "CP": "START_CP",
    "S": "START_SCOPE", "UIDS": "START_UIDS", "SHARE": "START_SHARE", "KILL": "START_KILL",
    "CIDRS": "START_CIDRS", "IFACES": "START_IFACES",
}
for old, new in sorted(start_vars.items(), key=lambda item: -len(item[0])):
    start = re.sub(rf"\${re.escape(old)}\b", f"${new}", start)
if " P=$!;" not in start:
    raise SystemExit("test38: core PID capture changed")
start = start.replace(" P=$!;", " START_PID=$!;", 1)
start = re.sub(r"\$P\b", "$START_PID", start)

# status() has the same collision after loadnet(), so isolate PID + mode + running.
if "  root; R=false; P=0" not in status:
    raise SystemExit("test38: status() state layout changed")
status = status.replace("  root; R=false; P=0", "  root; STATUS_RUNNING=false; STATUS_PID=0", 1)
status = status.replace('R=true; P="$X"', 'STATUS_RUNNING=true; STATUS_PID="$X"')
if '  M=$(cat "$MODEFILE" 2>/dev/null || echo none);' not in status:
    raise SystemExit("test38: status mode layout changed")
status = status.replace(
    '  M=$(cat "$MODEFILE" 2>/dev/null || echo none);',
    '  STATUS_MODE=$(cat "$MODEFILE" 2>/dev/null || echo none);',
    1,
)
status = status.replace("M=none", "STATUS_MODE=none")
status = re.sub(r"\$R\b", "$STATUS_RUNNING", status)
status = re.sub(r"\$P\b", "$STATUS_PID", status)
status = re.sub(r"\$M\b", "$STATUS_MODE", status)

text = text[:start_at] + start + status + text[case_at:]

# ---------------------------------------------------------------------------
# 4) A previous crashed/reinstalled build can leave a Hetu core alive while PIDFILE
#    is missing, which is exactly consistent with UI='stopped' + executable='busy'.
#    stopcore() must also kill only stale processes whose cmdline points at Hetu's
#    private core path. Never kill generic mihomo/clash processes from other apps.
# ---------------------------------------------------------------------------
old_stopcore = '''stopcore(){
  if [ -f "$PIDFILE" ]; then P=$(cat "$PIDFILE" 2>/dev/null || true); case "$P" in ''|*[!0-9]*) ;; *)
    if pidcore "$P" && kill -0 "$P" >/dev/null 2>&1; then kill "$P" >/dev/null 2>&1 || true; N=0; while kill -0 "$P" >/dev/null 2>&1 && [ "$N" -lt 20 ]; do sleep 0.1; N=$((N+1)); done; if pidcore "$P" && kill -0 "$P" >/dev/null 2>&1; then kill -9 "$P" >/dev/null 2>&1 || true; fi; fi;; esac; fi
  rm -f "$PIDFILE" "$MODEFILE"
}'''
new_stopcore = '''stopcore(){
  if [ -f "$PIDFILE" ]; then P=$(cat "$PIDFILE" 2>/dev/null || true); case "$P" in ''|*[!0-9]*) ;; *)
    if pidcore "$P" && kill -0 "$P" >/dev/null 2>&1; then kill "$P" >/dev/null 2>&1 || true; N=0; while kill -0 "$P" >/dev/null 2>&1 && [ "$N" -lt 20 ]; do sleep 0.1; N=$((N+1)); done; if pidcore "$P" && kill -0 "$P" >/dev/null 2>&1; then kill -9 "$P" >/dev/null 2>&1 || true; fi; fi;; esac; fi
  # Recover orphaned Hetu cores left by a killed/reinstalled app. Match the private
  # absolute path only; do not touch Mihomo/Clash processes owned by other apps.
  for PROC in /proc/[0-9]*; do
    OPID=${PROC#/proc/}; [ "$OPID" != "$$" ] || continue; [ -r "$PROC/cmdline" ] || continue
    OCMD=$(tr '\\000' ' ' < "$PROC/cmdline" 2>/dev/null || true)
    case "$OCMD" in *"$BASE/bin/core"*)
      kill "$OPID" >/dev/null 2>&1 || true
      N=0; while kill -0 "$OPID" >/dev/null 2>&1 && [ "$N" -lt 20 ]; do sleep 0.1; N=$((N+1)); done
      kill -0 "$OPID" >/dev/null 2>&1 && kill -9 "$OPID" >/dev/null 2>&1 || true
    ;; esac
  done
  rm -f "$PIDFILE" "$MODEFILE"
}'''
if old_stopcore not in text:
    raise SystemExit("test38: stopcore block not found")
text = text.replace(old_stopcore, new_stopcore, 1)
SCRIPT.write_text(text, encoding="utf-8")

# ---------------------------------------------------------------------------
# Hard audit: keep the adblock chain intact and reject the two real-device failures.
# ---------------------------------------------------------------------------
checks = {
    BUILD: ['versionCode = 438', 'versionName = "0.4.0-test.38"'],
    MANAGER: [
        'ProxyAdblockCoordinator.enter(context)',
        'profile.adblockChain',
        'String binTmp=BIN+deploySuffix;',
        'mv -f ',
        'RootBridge.quote(binTmp)',
    ],
    SCRIPT: [
        'START_BIN="$1"; START_CFG="$2"; START_MODE="$3"',
        '"$START_BIN" -d "$RUN" -f "$START_CFG"',
        'STATUS_RUNNING=false; STATUS_PID=0',
        '*"$BASE/bin/core"*',
        'Mihomo 初始化超过 90 秒',
    ],
}
for path, needles in checks.items():
    body = path.read_text(encoding="utf-8")
    for needle in needles:
        if needle not in body:
            raise SystemExit(f"test38: missing invariant {needle} in {path}")

# No direct truncating copy into the live executable is allowed anymore.
manager = MANAGER.read_text(encoding="utf-8")
for bad in [
    '.append("; cp ").append(RootBridge.quote(binary.getAbsolutePath())).append(\' \').append(RootBridge.quote(BIN))',
]:
    if bad in manager:
        raise SystemExit("test38: live executable direct cp still present")

print("test.38 chained adblock/root runtime hot-swap fix applied")
