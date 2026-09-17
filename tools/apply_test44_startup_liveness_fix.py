from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
BUILD = ROOT / "android-app/app/build.gradle.kts"
SCRIPT = ROOT / "android-app/app/src/main/assets/proxy-root-v3.sh"
MGR = ROOT / "android-app/app/src/main/java/io/github/xgl34222220/bichen/RootProxyManager.java"
INSPECTOR = ROOT / "android-app/app/src/main/java/io/github/xgl34222220/bichen/ProxyRuntimeInspector.kt"

# Version
build = BUILD.read_text(encoding="utf-8")
build = build.replace('versionCode = 443', 'versionCode = 444', 1)
build = build.replace('versionName = "0.4.0-test.43"', 'versionName = "0.4.0-test.44"', 1)
if 'versionCode = 444' not in build or 'versionName = "0.4.0-test.44"' not in build:
    raise SystemExit('test44: version patch failed')
BUILD.write_text(build, encoding='utf-8')

# Root controller: persist exact start phase/errors and make process liveness recovery tolerant.
script = SCRIPT.read_text(encoding='utf-8')
script = script.replace(
    'CRASH_STATE="$RUN/last-crash"\nLOCK_DIR="$RUN/.txn.lock"',
    'CRASH_STATE="$RUN/last-crash"\nSTART_STATE="$RUN/start-state"\nSTART_ERROR="$RUN/last-start-error"\nLOCK_DIR="$RUN/.txn.lock"',
    1,
)
script = script.replace(
    'ok(){ printf \'{"ok":true,"message":"%s"}\\n\' "$1"; }\nfail(){ printf \'{"ok":false,"message":"%s"}\\n\' "$1"; exit 1; }',
    '''ok(){ printf '{"ok":true,"message":"%s"}\\n' "$1"; }\nstart_stage(){ mkdir -p "$RUN" >/dev/null 2>&1 || true; printf '%s %s\\n' "$(date '+%Y-%m-%dT%H:%M:%S%z')" "$1" > "$START_STATE" 2>/dev/null || true; }\nfail(){ MSG="$1"; mkdir -p "$RUN" >/dev/null 2>&1 || true; printf '%s %s\\n' "$(date '+%Y-%m-%dT%H:%M:%S%z')" "$MSG" > "$START_ERROR" 2>/dev/null || true; printf '{"ok":false,"message":"%s"}\\n' "$MSG"; exit 1; }''',
    1,
)
old_pidcore = '''pidcore(){
  P="$1"; [ -d "/proc/$P" ] || return 1
  CMD=$(tr '\\000' ' ' < "/proc/$P/cmdline" 2>/dev/null || true)
  EXE=$(readlink "/proc/$P/exe" 2>/dev/null || true)
  case "$CMD $EXE" in *"$BASE/bin/core"*) return 0;; *) return 1;; esac
}'''
new_pidcore = old_pidcore + '''
findcorepid(){
  for PROC in /proc/[0-9]*; do
    CAND=${PROC#/proc/}; case "$CAND" in ''|*[!0-9]*) continue;; esac
    if pidcore "$CAND" && kill -0 "$CAND" >/dev/null 2>&1; then printf '%s\\n' "$CAND"; return 0; fi
  done
  return 1
}'''
if old_pidcore not in script:
    raise SystemExit('test44: pidcore block missing')
script = script.replace(old_pidcore, new_pidcore, 1)
old_watch = '''  mkdir -p "$RUN" || exit 0; printf '%s\\n' "$$" > "$WATCHDOG_PID"; while pidcore "$COREPID" && kill -0 "$COREPID" >/dev/null 2>&1; do sleep 2; done; acquire_lock || exit 0'''
new_watch = '''  mkdir -p "$RUN" || exit 0; printf '%s\\n' "$$" > "$WATCHDOG_PID"; MISS=0; while [ "$MISS" -lt 3 ]; do if pidcore "$COREPID" && kill -0 "$COREPID" >/dev/null 2>&1; then MISS=0; sleep 2; else MISS=$((MISS+1)); sleep 0.20; fi; done; acquire_lock || exit 0'''
if old_watch not in script:
    raise SystemExit('test44: watchdog loop missing')
script = script.replace(old_watch, new_watch, 1)

# Record major startup stages. This survives rollback, unlike core.log/watchdog.log.
script = script.replace(
    '  START_BIN="$1"; START_CFG="$2"; START_MODE="$3"; START_TP="$4"; START_RP="$5"; START_V6="$6"; START_TCP="$7"; START_UDP="$8"; START_DNS="$9"; START_QUIC="${10}"; START_DP="${11}"; START_CP="${12}"; START_SCOPE="${13}"; START_UIDS="${14}"; START_SHARE="${15}"; START_KILL="${16}"; START_CIDRS="${17}"; START_IFACES="${18}"\n  preflight',
    '  START_BIN="$1"; START_CFG="$2"; START_MODE="$3"; START_TP="$4"; START_RP="$5"; START_V6="$6"; START_TCP="$7"; START_UDP="$8"; START_DNS="$9"; START_QUIC="${10}"; START_DP="${11}"; START_CP="${12}"; START_SCOPE="${13}"; START_UIDS="${14}"; START_SHARE="${15}"; START_KILL="${16}"; START_CIDRS="${17}"; START_IFACES="${18}"\n  rm -f "$START_ERROR"; start_stage "preflight"\n  preflight',
    1,
)
script = script.replace('  : > "$LOG"; "$START_BIN"', '  start_stage "launch-core"\n  : > "$LOG"; "$START_BIN"', 1)
script = script.replace('  wait_ready "$START_PID"', '  start_stage "wait-listeners"\n  wait_ready "$START_PID"', 1)
script = script.replace('  install_mangle4 "$START_TP"', '  start_stage "install-ipv4-tproxy"\n  install_mangle4 "$START_TP"', 1)
script = script.replace('  install_redirect4 "$START_RP"', '  start_stage "install-ipv4-redirect"\n  install_redirect4 "$START_RP"', 1)
script = script.replace('  [ "$START_DNS" = off ] || install_dns_redirect4', '  start_stage "install-ipv4-dns"\n  [ "$START_DNS" = off ] || install_dns_redirect4', 1)
script = script.replace('  [ "$START_QUIC" = 0 ] || install_quic4', '  start_stage "install-ipv4-quic"\n  [ "$START_QUIC" = 0 ] || install_quic4', 1)
script = script.replace('  if [ "$START_V6" = enable ]; then', '  start_stage "install-ipv6"\n  if [ "$START_V6" = enable ]; then', 1)
script = script.replace('  start_watchdog "$START_PID"', '  start_stage "start-watchdog"\n  start_watchdog "$START_PID"', 1)
script = script.replace('  DESC="tcp=$START_TCP', '  rm -f "$START_ERROR"; start_stage "running"\n  DESC="tcp=$START_TCP', 1)

# Recover the private core even if pidfile/proc metadata had a transient miss.
old_status = '''  if [ -f "$PIDFILE" ]; then X=$(cat "$PIDFILE" 2>/dev/null || true); case "$X" in ''|*[!0-9]*) ;; *) if pidcore "$X" && kill -0 "$X" >/dev/null 2>&1; then STATUS_RUNNING=true; STATUS_PID="$X"; fi;; esac; fi
  STATUS_MODE=$(cat "$MODEFILE" 2>/dev/null || echo none); T4=false; T6=false; K4=false; K6=false'''
new_status = '''  if [ -f "$PIDFILE" ]; then X=$(cat "$PIDFILE" 2>/dev/null || true); case "$X" in ''|*[!0-9]*) ;; *) if pidcore "$X" && kill -0 "$X" >/dev/null 2>&1; then STATUS_RUNNING=true; STATUS_PID="$X"; fi;; esac; fi
  if [ "$STATUS_RUNNING" = false ]; then RECOVER_PID=$(findcorepid 2>/dev/null || true); case "$RECOVER_PID" in ''|*[!0-9]*) ;; *) STATUS_RUNNING=true; STATUS_PID="$RECOVER_PID"; printf '%s\\n' "$RECOVER_PID" > "$PIDFILE" 2>/dev/null || true;; esac; fi
  STATUS_MODE=$(cat "$MODEFILE" 2>/dev/null || echo none); T4=false; T6=false; K4=false; K6=false'''
if old_status not in script:
    raise SystemExit('test44: status pid block missing')
script = script.replace(old_status, new_status, 1)
SCRIPT.write_text(script, encoding='utf-8')

# App-side verification: the Root start transaction already requires the controller TCP listener.
# A late /version/API handshake must never kill an otherwise healthy transparent proxy.
mgr = MGR.read_text(encoding='utf-8')
old_verify = '''        stage(progress,"确认策略控制接口、守护与回滚状态…");
        try{
            JSONObject state=status();
            if(!state.optBoolean("running",false))
                throw new IOException("启动命令已返回，但核心未保持运行"+(diagnostics().isEmpty()?"":"："+diagnostics()));
            MihomoControllerClient controller=new MihomoControllerClient(context);
            if(!controller.waitReady(4500))throw new IOException("Mihomo 控制接口未在启动窗口内就绪");
            try{
                controller.delay("DIRECT","https://connectivitycheck.platform.hicloud.com/generate_204","200-399");
                prefs.edit().remove("proxyRootEgressWarning").apply();
            }catch(Exception firstProbe){
                try{
                    controller.delay("DIRECT","https://cp.cloudflare.com/generate_204","200-399");
                    prefs.edit().remove("proxyRootEgressWarning").apply();
                }catch(Exception secondProbe){
                    String detail=secondProbe.getMessage()==null?secondProbe.getClass().getSimpleName():secondProbe.getMessage();
                    prefs.edit().putString("proxyRootEgressWarning","核心已保持运行，但启动联网探测失败："+detail).apply();
                }
            }
        }catch(Exception verify){
            try{stop();}catch(Exception ignored){}
            throw new IOException("核心启动后健康检查失败，网络已回滚："+(verify.getMessage()==null?"控制接口不可用":verify.getMessage()),verify);
        }
'''
new_verify = '''        stage(progress,"确认策略控制接口、守护与回滚状态…");
        JSONObject state=status();
        if(!state.optBoolean("running",false)){
            if(adblockCoordinatorEntered)ProxyAdblockCoordinator.exit(context);
            throw new IOException("启动命令已返回，但未检测到辟尘私有核心进程"+(diagnostics().isEmpty()?"":"："+diagnostics()));
        }
        MihomoControllerClient controller=new MihomoControllerClient(context);
        if(!controller.waitReady(12000)){
            prefs.edit().putString("proxyRootEgressWarning","Root 代理核心已运行；本地控制接口仍在初始化，面板数据可能稍后出现").apply();
        }else{
            try{
                controller.delay("DIRECT","https://connectivitycheck.platform.hicloud.com/generate_204","200-399");
                prefs.edit().remove("proxyRootEgressWarning").apply();
            }catch(Exception firstProbe){
                try{
                    controller.delay("DIRECT","https://cp.cloudflare.com/generate_204","200-399");
                    prefs.edit().remove("proxyRootEgressWarning").apply();
                }catch(Exception secondProbe){
                    String detail=secondProbe.getMessage()==null?secondProbe.getClass().getSimpleName():secondProbe.getMessage();
                    prefs.edit().putString("proxyRootEgressWarning","核心已保持运行，但启动联网探测失败："+detail).apply();
                }
            }
        }
'''
if old_verify not in mgr:
    raise SystemExit('test44: app verification block missing')
mgr = mgr.replace(old_verify, new_verify, 1)
MGR.write_text(mgr, encoding='utf-8')

# Runtime log must show controller transaction failures even when watchdog never started.
inspector = INSPECTOR.read_text(encoding='utf-8')
old_log = '''        val command = "echo '--- core.log ---'; tail -n 140 /data/adb/bichen/proxy/run/core.log 2>&1 || true; echo '--- watchdog.log ---'; tail -n 30 /data/adb/bichen/proxy/run/watchdog.log 2>/dev/null || true; echo '--- last-crash ---'; cat /data/adb/bichen/proxy/run/last-crash 2>/dev/null || true"'''
new_log = '''        val command = "echo '--- start-state ---'; cat /data/adb/bichen/proxy/run/start-state 2>/dev/null || true; echo '--- last-start-error ---'; cat /data/adb/bichen/proxy/run/last-start-error 2>/dev/null || true; echo '--- core.log ---'; tail -n 140 /data/adb/bichen/proxy/run/core.log 2>&1 || true; echo '--- watchdog.log ---'; tail -n 30 /data/adb/bichen/proxy/run/watchdog.log 2>/dev/null || true; echo '--- last-crash ---'; cat /data/adb/bichen/proxy/run/last-crash 2>/dev/null || true"'''
if old_log not in inspector:
    raise SystemExit('test44: runtime log command missing')
inspector = inspector.replace(old_log, new_log, 1)
INSPECTOR.write_text(inspector, encoding='utf-8')

# Audits
checks = {
    BUILD: ['versionCode = 444', 'versionName = "0.4.0-test.44"'],
    SCRIPT: ['START_STATE="$RUN/start-state"', 'START_ERROR="$RUN/last-start-error"', 'findcorepid(){', 'MISS=0; while [ "$MISS" -lt 3 ]', 'start_stage "running"'],
    MGR: ['if(!controller.waitReady(12000))', '本地控制接口仍在初始化', 'if(adblockCoordinatorEntered)ProxyAdblockCoordinator.exit(context);'],
    INSPECTOR: ["--- start-state ---", "--- last-start-error ---"],
}
for path, needles in checks.items():
    body = path.read_text(encoding='utf-8')
    for needle in needles:
        if needle not in body:
            raise SystemExit(f'test44: missing invariant {needle} in {path}')

print('test.44 startup liveness/status recovery diagnostics applied')
