from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

# Version
build = ROOT / "android-app/app/build.gradle.kts"
text = build.read_text(encoding="utf-8")
text = text.replace('versionCode = 436', 'versionCode = 437')
text = text.replace('versionName = "0.4.0-test.36"', 'versionName = "0.4.0-test.37"')
if 'versionCode = 437' not in text or 'versionName = "0.4.0-test.37"' not in text:
    raise SystemExit('version patch failed')
build.write_text(text, encoding="utf-8")

# Root controller: cold configs can contain many remote proxy/rule providers. 8 s was
# far too aggressive and killed a healthy Mihomo process while it was still building
# provider caches in Bichen's private HomeDir.
script = ROOT / "android-app/app/src/main/assets/proxy-root-v3.sh"
text = script.read_text(encoding="utf-8")
old = '''wait_ready(){ PID="$1"; M="$2"; TP="$3"; RP="$4"; TCP="$5"; UDP="$6"; DNS="$7"; DP="$8"; CP="$9"; N=0; while [ "$N" -lt 80 ]; do ready "$PID" "$M" "$TP" "$RP" "$TCP" "$UDP" "$DNS" "$DP" "$CP" && return 0; pidcore "$PID" && kill -0 "$PID" >/dev/null 2>&1 || return 1; sleep 0.1; N=$((N+1)); done; return 1; }'''
new = '''wait_ready(){
  PID="$1"; M="$2"; TP="$3"; RP="$4"; TCP="$5"; UDP="$6"; DNS="$7"; DP="$8"; CP="$9"
  # A real user config may have dozens of remote proxy/rule providers. On the first
  # run inside Bichen's private HomeDir their caches are cold; Mihomo keeps the process
  # alive while initial configuration is still loading. Do not mistake that for a dead
  # listener after only 8 seconds. Keep network rules detached until every required
  # listener is actually ready, so a slow cold start cannot black-hole traffic.
  N=0
  while [ "$N" -lt 900 ]; do
    ready "$PID" "$M" "$TP" "$RP" "$TCP" "$UDP" "$DNS" "$DP" "$CP" && return 0
    pidcore "$PID" && kill -0 "$PID" >/dev/null 2>&1 || return 2
    sleep 0.1
    N=$((N+1))
  done
  return 3
}'''
if old not in text:
    raise SystemExit('wait_ready block not found')
text = text.replace(old, new)
old_launch = ''': > "$LOG"; "$BIN" -d "$RUN" -f "$CFG" >>"$LOG" 2>&1 & P=$!; printf '%s\\n' "$P" > "$PIDFILE"; printf '%s\\n' "$M" > "$MODEFILE"; write_session "$M" "$V6" "$S" "$SHARE" "$KILL"
  if ! wait_ready "$P" "$M" "$TP" "$RP" "$TCP" "$UDP" "$DNS" "$DP" "$CP"; then stopcore; cleanup; restorev6; rm -f "$SESSION"; fail "核心进程已启动但透明代理/DNS/API 监听未就绪，网络未被接管"; fi'''
new_launch = '''mkdir -p "$RUN/rules" "$RUN/proxy_provider" "$RUN/ruleset" "$RUN/ui" || { cleanup; restorev6; rm -f "$SESSION"; fail "无法创建 Mihomo 运行缓存目录"; }
  : > "$LOG"; "$BIN" -d "$RUN" -f "$CFG" >>"$LOG" 2>&1 & P=$!; printf '%s\\n' "$P" > "$PIDFILE"; printf '%s\\n' "$M" > "$MODEFILE"; write_session "$M" "$V6" "$S" "$SHARE" "$KILL"
  wait_ready "$P" "$M" "$TP" "$RP" "$TCP" "$UDP" "$DNS" "$DP" "$CP"; READY_RC=$?
  if [ "$READY_RC" -ne 0 ]; then
    if [ "$READY_RC" -eq 2 ]; then READY_MSG="Mihomo 启动后提前退出，请查看核心日志"; else READY_MSG="Mihomo 初始化超过 90 秒，透明代理/DNS/API 监听仍未就绪；首次加载大量远程订阅或规则时请检查网络与核心日志"; fi
    stopcore; cleanup; restorev6; rm -f "$SESSION"; fail "$READY_MSG"
  fi'''
if old_launch not in text:
    raise SystemExit('core launch block not found')
text = text.replace(old_launch, new_launch)
script.write_text(text, encoding="utf-8")

# App -> Root command timeout must exceed the Root controller's cold-start window.
manager = ROOT / "android-app/app/src/main/java/io/github/xgl34222220/bichen/RootProxyManager.java"
text = manager.read_text(encoding="utf-8")
text = text.replace('stage(progress,"启动核心并等待监听端口就绪…");', 'stage(progress,"启动核心并等待订阅、规则与监听就绪（首次可能较慢）…");')
text = text.replace('try{result=runJson("start",', 'try{result=runJsonWithTimeout(125000L,"start",')
old_runjson = '''    private JSONObject runJson(String...args)throws Exception{
        RootBridge.requireWorkerThread();
        StringBuilder cmd=new StringBuilder("exec ").append(RootBridge.quote(SCRIPT));
        for(String a:args)cmd.append(' ').append(RootBridge.quote(a==null?"":a));
        RootBridge.Result r=RootBridge.rootShell(context,cmd.toString(),55000L);
        JSONObject j;
        try{j=RootBridge.parseObject(r.output.trim());}
        catch(Exception e){throw new IOException(r.output.isEmpty()?"Root 控制器没有返回状态":r.output);}
        if(r.code!=0)throw new IOException(j.optString("message","Root 代理命令失败，退出码 "+r.code));
        return j;
    }'''
new_runjson = '''    private JSONObject runJson(String...args)throws Exception{return runJsonWithTimeout(55000L,args);}
    private JSONObject runJsonWithTimeout(long timeoutMs,String...args)throws Exception{
        RootBridge.requireWorkerThread();
        StringBuilder cmd=new StringBuilder("exec ").append(RootBridge.quote(SCRIPT));
        for(String a:args)cmd.append(' ').append(RootBridge.quote(a==null?"":a));
        RootBridge.Result r=RootBridge.rootShell(context,cmd.toString(),timeoutMs);
        JSONObject j;
        try{j=RootBridge.parseObject(r.output.trim());}
        catch(Exception e){throw new IOException(r.output.isEmpty()?"Root 控制器没有返回状态":r.output);}
        if(r.code!=0)throw new IOException(j.optString("message","Root 代理命令失败，退出码 "+r.code));
        return j;
    }'''
if old_runjson not in text:
    raise SystemExit('runJson block not found')
text = text.replace(old_runjson, new_runjson)
manager.write_text(text, encoding="utf-8")

# Hard audit.
checks = {
    build: ['versionCode = 437', 'versionName = "0.4.0-test.37"'],
    script: ['while [ "$N" -lt 900 ]', 'READY_RC=$?', 'Mihomo 初始化超过 90 秒', '"$RUN/proxy_provider"'],
    manager: ['runJsonWithTimeout(125000L,"start"', '首次可能较慢', 'private JSONObject runJsonWithTimeout(long timeoutMs'],
}
for path, needles in checks.items():
    body = path.read_text(encoding='utf-8')
    for needle in needles:
        if needle not in body:
            raise SystemExit(f'missing {needle} in {path}')

print('test.37 cold-start readiness patch applied')
