from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
BUILD = ROOT / "android-app/app/build.gradle.kts"
MGR = ROOT / "android-app/app/src/main/java/io/github/xgl34222220/bichen/RootProxyManager.java"

# Version
text = BUILD.read_text(encoding="utf-8")
text = text.replace('versionCode = 446', 'versionCode = 447', 1)
text = text.replace('versionName = "0.4.0-test.46"', 'versionName = "0.4.0-test.47"', 1)
if 'versionCode = 447' not in text or 'versionName = "0.4.0-test.47"' not in text:
    raise SystemExit('test47: version patch failed')
BUILD.write_text(text, encoding="utf-8")

text = MGR.read_text(encoding="utf-8")

# One process-wide control-plane lock. RootProxyManager is instantiated independently by
# Compose, network automation and boot recovery, so an instance synchronized method is not enough.
if 'import java.util.concurrent.locks.ReentrantLock;' not in text:
    text = text.replace('import java.util.*;\n', 'import java.util.*;\nimport java.util.concurrent.locks.ReentrantLock;\n', 1)

anchor = '    private static final String SCRIPT=ROOT+"/proxy-root.sh";\n'
if anchor not in text:
    raise SystemExit('test47: manager constants anchor missing')
if 'CONTROL_LOCK' not in text:
    text = text.replace(anchor, anchor + '    private static final ReentrantLock CONTROL_LOCK=new ReentrantLock(true);\n', 1)

# Controller port selection should not always race on 29090 if a second installed Bichen build
# or another Clash frontend wakes at the same time. Scan the whole private range from a rotating
# start point, and only then fall back to the previously remembered port ordering.
old_chooser = '''    private int chooseControllerPort()throws IOException{\n        int preferred=prefs.getInt("proxyControllerPort",MihomoStartupConfig.CONTROLLER_PORT);\n        LinkedHashSet<Integer> candidates=new LinkedHashSet<>();\n        if(preferred>=29090&&preferred<=29149)candidates.add(preferred);\n        for(int port=29090;port<=29149;port++)candidates.add(port);\n        for(int port:candidates){\n            try(ServerSocket socket=new ServerSocket()){\n                socket.setReuseAddress(false);\n                socket.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"),port));\n                prefs.edit().putInt("proxyControllerPort",port).commit();\n                return port;\n            }catch(IOException occupied){ }\n        }\n        throw new IOException("辟尘控制接口动态端口 29090-29149 均被占用，请关闭冲突代理后重试");\n    }\n'''
new_chooser = '''    private int chooseControllerPort()throws IOException{\n        int preferred=prefs.getInt("proxyControllerPort",MihomoStartupConfig.CONTROLLER_PORT);\n        LinkedHashSet<Integer> candidates=new LinkedHashSet<>();\n        int span=60;\n        int start=(int)(Math.abs(System.nanoTime())%span);\n        for(int offset=0;offset<span;offset++)candidates.add(29090+((start+offset)%span));\n        if(preferred>=29090&&preferred<=29149)candidates.add(preferred);\n        for(int port:candidates){\n            try(ServerSocket socket=new ServerSocket()){\n                socket.setReuseAddress(false);\n                socket.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"),port));\n                prefs.edit().putInt("proxyControllerPort",port).commit();\n                return port;\n            }catch(IOException occupied){ }\n        }\n        throw new IOException("辟尘控制接口动态端口 29090-29149 均被占用，请关闭冲突代理后重试");\n    }\n'''
if old_chooser not in text:
    raise SystemExit('test47: controller-port chooser block not found')
text = text.replace(old_chooser, new_chooser, 1)

# Serialize the *whole* start transaction before prepare() chooses/writes a controller port.
# A second request arriving while the first is starting must wait; once it gets the lock it
# re-checks real Root state and becomes an idempotent no-op instead of killing the good core.
old_start_head = '    JSONObject start(ProxyRuntimeProfile profile,Progress progress)throws Exception{\n        stage(progress,"检查配置、应用范围与绕过策略…");\n'
new_start_head = '''    JSONObject start(ProxyRuntimeProfile profile,Progress progress)throws Exception{\n        CONTROL_LOCK.lock();\n        try{\n            JSONObject existing=status();\n            if(existing.optBoolean("running",false)){\n                prefs.edit().putBoolean("proxyRootWanted",true).apply();\n                existing.put("ok",true).put("alreadyRunning",true).put("message","Root 代理已在运行，已忽略重复启动请求");\n                return existing;\n            }\n            stage(progress,"检查配置、应用范围与绕过策略…");\n'''
if old_start_head not in text:
    raise SystemExit('test47: start head not found')
text = text.replace(old_start_head, new_start_head, 1)

old_start_tail = '''        prefs.edit().putBoolean("proxyRootWanted",true).apply();\n        return result;\n    }\n\n    JSONObject stop()throws Exception{return stop(null);}\n'''
new_start_tail = '''        prefs.edit().putBoolean("proxyRootWanted",true).apply();\n        return result;\n        }finally{\n            CONTROL_LOCK.unlock();\n        }\n    }\n\n    JSONObject stop()throws Exception{return stop(null);}\n'''
if old_start_tail not in text:
    raise SystemExit('test47: start tail not found')
text = text.replace(old_start_tail, new_start_tail, 1)

# Stop uses the same lock so an automation callback cannot stop halfway through a manual start.
old_stop = '''    JSONObject stop(Progress progress)throws Exception{\n        stage(progress,"停止守护、Kill Switch、核心并回滚透明代理规则…");\n        JSONObject r=runJsonAllowMissing("stop",new JSONObject().put("ok",true).put("running",false).put("state","idle").put("message","Root 代理未运行"));\n        ProxyAdblockCoordinator.exit(context);\n        stage(progress,"网络规则、广告过滤接管与临时 IPv6 状态已恢复");\n        prefs.edit().putBoolean("proxyRootWanted",false).apply();\n        return r;\n    }\n'''
new_stop = '''    JSONObject stop(Progress progress)throws Exception{\n        CONTROL_LOCK.lock();\n        try{\n            stage(progress,"停止守护、Kill Switch、核心并回滚透明代理规则…");\n            JSONObject r=runJsonAllowMissing("stop",new JSONObject().put("ok",true).put("running",false).put("state","idle").put("message","Root 代理未运行"));\n            ProxyAdblockCoordinator.exit(context);\n            stage(progress,"网络规则、广告过滤接管与临时 IPv6 状态已恢复");\n            prefs.edit().putBoolean("proxyRootWanted",false).apply();\n            return r;\n        }finally{\n            CONTROL_LOCK.unlock();\n        }\n    }\n'''
if old_stop not in text:
    raise SystemExit('test47: stop block not found')
text = text.replace(old_stop, new_stop, 1)

MGR.write_text(text, encoding="utf-8")

checks = {
    BUILD: ['versionCode = 447', 'versionName = "0.4.0-test.47"'],
    MGR: [
        'private static final ReentrantLock CONTROL_LOCK=new ReentrantLock(true);',
        'JSONObject existing=status();',
        'alreadyRunning',
        'CONTROL_LOCK.lock();',
        'CONTROL_LOCK.unlock();',
        'int start=(int)(Math.abs(System.nanoTime())%span);',
    ],
}
for path, needles in checks.items():
    body = path.read_text(encoding="utf-8")
    for needle in needles:
        if needle not in body:
            raise SystemExit(f'test47: missing invariant {needle} in {path}')

print('test47 duplicate-start/start-stop race guard applied')
