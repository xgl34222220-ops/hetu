from pathlib import Path

ROOT = Path('android-app/app/src/main/java/io/github/xgl34222220/bichen')


def req(text: str, old: str, new: str, label: str) -> str:
    if old not in text:
        raise RuntimeError(f'missing {label}')
    return text.replace(old, new, 1)

bp = Path('android-app/app/build.gradle.kts')
b = bp.read_text()
b = req(b, 'versionCode = 452', 'versionCode = 453', 'versionCode')
b = req(b, 'versionName = "0.4.0-test.52"', 'versionName = "0.4.0-test.53"', 'versionName')
bp.write_text(b)

rp = ROOT / 'RootProxyManager.java'
r = rp.read_text()
r = req(
    r,
    'import java.util.*;\nimport java.util.concurrent.locks.ReentrantLock;',
    'import java.util.*;\nimport java.util.regex.*;\nimport java.util.concurrent.locks.ReentrantLock;',
    'regex import',
)
old_choose = '''    private int chooseControllerPort()throws IOException{\n        int preferred=prefs.getInt("proxyControllerPort",MihomoStartupConfig.CONTROLLER_PORT);\n        LinkedHashSet<Integer> candidates=new LinkedHashSet<>();\n        int span=60;\n        int start=(int)(Math.abs(System.nanoTime())%span);\n        for(int offset=0;offset<span;offset++)candidates.add(29090+((start+offset)%span));\n        if(preferred>=29090&&preferred<=29149)candidates.add(preferred);\n        for(int port:candidates){\n            try(ServerSocket socket=new ServerSocket()){\n                socket.setReuseAddress(false);\n                socket.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"),port));\n                prefs.edit().putInt("proxyControllerPort",port).commit();\n                return port;\n            }catch(IOException occupied){ }\n        }\n        throw new IOException("辟尘控制接口动态端口 29090-29149 均被占用，请关闭冲突代理后重试");\n    }'''
new_choose = '''    private int chooseControllerPort()throws IOException{\n        int preferred=prefs.getInt("proxyControllerPort",MihomoStartupConfig.CONTROLLER_PORT);\n        LinkedHashSet<Integer> candidates=new LinkedHashSet<>();\n        // Prefer the last successful port when it is free. Preparing/preflighting must\n        // never publish a candidate port to the dashboard client.\n        if(preferred>=29090&&preferred<=29149)candidates.add(preferred);\n        int span=60;\n        int start=(int)(Math.abs(System.nanoTime())%span);\n        for(int offset=0;offset<span;offset++)candidates.add(29090+((start+offset)%span));\n        for(int port:candidates){\n            try(ServerSocket socket=new ServerSocket()){\n                socket.setReuseAddress(false);\n                socket.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"),port));\n                return port;\n            }catch(IOException occupied){ }\n        }\n        throw new IOException("辟尘控制接口动态端口 29090-29149 均被占用，请关闭冲突代理后重试");\n    }'''
r = req(r, old_choose, new_choose, 'chooseControllerPort')

old_status = '''    JSONObject status()throws Exception{\n        return runJsonAllowMissing("status",new JSONObject().put("ok",true).put("running",false).put("state","idle").put("message","尚未启动"));\n    }'''
new_status = '''    private int liveControllerPort(JSONObject state){\n        int port=state.optInt("controllerPort",0);\n        if(port>=29090&&port<=29149)return port;\n        if(!state.optBoolean("running",false))return 0;\n        // Recovery for old sessions: test.45-test.52 preflight could overwrite prefs\n        // while the live Mihomo still listened on the old controller port. The deployed\n        // Root startup config is authoritative because preflight does not replace CONFIG.\n        try{\n            RootBridge.Result result=RootBridge.rootShell(context,"cat "+RootBridge.quote(CONFIG)+" 2>/dev/null || true",4000L);\n            Matcher matcher=Pattern.compile("(?m)^\\s*external-controller:\\s*127\\.0\\.0\\.1:(\\d+)\\s*$").matcher(result.output==null?"":result.output);\n            int found=0;\n            while(matcher.find()){\n                int candidate=Integer.parseInt(matcher.group(1));\n                if(candidate>=29090&&candidate<=29149)found=candidate;\n            }\n            return found;\n        }catch(Exception ignored){return 0;}\n    }\n\n    JSONObject status()throws Exception{\n        JSONObject state=runJsonAllowMissing("status",new JSONObject().put("ok",true).put("running",false).put("state","idle").put("message","尚未启动"));\n        int livePort=liveControllerPort(state);\n        if(livePort>0&&prefs.getInt("proxyControllerPort",MihomoStartupConfig.CONTROLLER_PORT)!=livePort){\n            prefs.edit().putInt("proxyControllerPort",livePort).apply();\n            state.put("controllerPort",livePort);\n        }\n        return state;\n    }'''
r = req(r, old_status, new_status, 'status recovery')

old_running = '''        JSONObject state=status();\n        if(!state.optBoolean("running",false)){\n            if(adblockCoordinatorEntered)ProxyAdblockCoordinator.exit(context);\n            throw new IOException("启动命令已返回，但未检测到辟尘私有核心进程"+(diagnostics().isEmpty()?"":"："+diagnostics()));\n        }\n        MihomoControllerClient controller=new MihomoControllerClient(context);'''
new_running = '''        JSONObject state=status();\n        if(!state.optBoolean("running",false)){\n            if(adblockCoordinatorEntered)ProxyAdblockCoordinator.exit(context);\n            throw new IOException("启动命令已返回，但未检测到辟尘私有核心进程"+(diagnostics().isEmpty()?"":"："+diagnostics()));\n        }\n        // Publish only the port of a successfully running core.\n        prefs.edit().putInt("proxyControllerPort",p.controllerPort).commit();\n        MihomoControllerClient controller=new MihomoControllerClient(context);'''
r = req(r, old_running, new_running, 'publish successful port')
rp.write_text(r)

sp = Path('android-app/app/src/main/assets/proxy-root-v3.sh')
s = sp.read_text()
s = req(
    s,
    '''write_session(){ M="$1"; V6="$2"; S="$3"; SHARE="$4"; KILL="$5"; { printf 'MODE=%s\\n' "$M"; printf 'IPV6=%s\\n' "$V6"; printf 'APP_SCOPE=%s\\n' "$S"; printf 'SHARE=%s\\n' "$SHARE"; printf 'KILL=%s\\n' "$KILL"; } > "$SESSION.new.$$" && mv -f "$SESSION.new.$$" "$SESSION"; }''',
    '''write_session(){ M="$1"; V6="$2"; S="$3"; SHARE="$4"; KILL="$5"; CP="$6"; { printf 'MODE=%s\\n' "$M"; printf 'IPV6=%s\\n' "$V6"; printf 'APP_SCOPE=%s\\n' "$S"; printf 'SHARE=%s\\n' "$SHARE"; printf 'KILL=%s\\n' "$KILL"; printf 'CONTROLLER_PORT=%s\\n' "$CP"; } > "$SESSION.new.$$" && mv -f "$SESSION.new.$$" "$SESSION"; }''',
    'write_session',
)
s = req(
    s,
    'write_session "$START_MODE" "$START_V6" "$START_SCOPE" "$START_SHARE" "$START_KILL"',
    'write_session "$START_MODE" "$START_V6" "$START_SCOPE" "$START_SHARE" "$START_KILL" "$START_CP"',
    'write_session call',
)
s = req(
    s,
    '''  SCOPEV=$(sed -n 's/^APP_SCOPE=//p' "$SESSION" 2>/dev/null | head -n 1); SHAREV=$(sed -n 's/^SHARE=//p' "$SESSION" 2>/dev/null | head -n 1); KILLV=$(sed -n 's/^KILL=//p' "$SESSION" 2>/dev/null | head -n 1)\n  SP=$(state_value PREF 2>/dev/null || true)\n  printf '{"ok":true,"running":%s,"pid":%s,"mode":"%s","ipv4Rules":%s,"ipv6Rules":%s,"killSwitchActive":%s,"ipv6DisabledByBichen":%s,"watchdog":%s,"recoveredStaleRules":%s,"mark":"%s","table":"%s","pref":"%s","appScope":"%s","sharedNetwork":"%s","killSwitchRequested":"%s","log":"%s","configCheckLog":"%s"}\\n' "$STATUS_RUNNING" "$STATUS_PID" "$STATUS_MODE" "$T4" "$T6" "$([ "$K4" = true ] || [ "$K6" = true ] && echo true || echo false)" "$V6OFF" "$WD" "$RECOVERED" "$SM" "$ST" "$SP" "$SCOPEV" "$SHAREV" "$KILLV" "$LOG" "$CHECKLOG"''',
    '''  SCOPEV=$(sed -n 's/^APP_SCOPE=//p' "$SESSION" 2>/dev/null | head -n 1); SHAREV=$(sed -n 's/^SHARE=//p' "$SESSION" 2>/dev/null | head -n 1); KILLV=$(sed -n 's/^KILL=//p' "$SESSION" 2>/dev/null | head -n 1)\n  CPV=$(sed -n 's/^CONTROLLER_PORT=//p' "$SESSION" 2>/dev/null | head -n 1); case "$CPV" in ''|*[!0-9]*) CPV=0;; esac\n  if [ "$CPV" = 0 ]; then CPV=$(sed -n 's/^[[:space:]]*external-controller:[[:space:]]*127\\.0\\.0\\.1:\\([0-9][0-9]*\\)[[:space:]]*$/\\1/p' "$RUN/state/startup-config" 2>/dev/null | tail -n 1); case "$CPV" in ''|*[!0-9]*) CPV=0;; esac; fi\n  SP=$(state_value PREF 2>/dev/null || true)\n  printf '{"ok":true,"running":%s,"pid":%s,"mode":"%s","ipv4Rules":%s,"ipv6Rules":%s,"killSwitchActive":%s,"ipv6DisabledByBichen":%s,"watchdog":%s,"recoveredStaleRules":%s,"mark":"%s","table":"%s","pref":"%s","controllerPort":%s,"appScope":"%s","sharedNetwork":"%s","killSwitchRequested":"%s","log":"%s","configCheckLog":"%s"}\\n' "$STATUS_RUNNING" "$STATUS_PID" "$STATUS_MODE" "$T4" "$T6" "$([ "$K4" = true ] || [ "$K6" = true ] && echo true || echo false)" "$V6OFF" "$WD" "$RECOVERED" "$SM" "$ST" "$SP" "$CPV" "$SCOPEV" "$SHAREV" "$KILLV" "$LOG" "$CHECKLOG"''',
    'status controller port',
)
sp.write_text(s)
print('Applied Bichen 0.4.0-test.53 controller port state fix')
