from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
BUILD = ROOT / "android-app/app/build.gradle.kts"
MGR = ROOT / "android-app/app/src/main/java/io/github/xgl34222220/bichen/RootProxyManager.java"
STARTUP = ROOT / "android-app/app/src/main/java/io/github/xgl34222220/bichen/MihomoStartupConfig.java"
CLIENT = ROOT / "android-app/app/src/main/java/io/github/xgl34222220/bichen/MihomoControllerClient.java"
INSPECTOR = ROOT / "android-app/app/src/main/java/io/github/xgl34222220/bichen/ProxyRuntimeInspector.kt"

# version
text = BUILD.read_text(encoding="utf-8")
text = text.replace('versionCode = 444', 'versionCode = 445', 1)
text = text.replace('versionName = "0.4.0-test.44"', 'versionName = "0.4.0-test.45"', 1)
if 'versionCode = 445' not in text or 'versionName = "0.4.0-test.45"' not in text:
    raise SystemExit('test45: version patch failed')
BUILD.write_text(text, encoding="utf-8")

# Startup config: allow a runtime-selected API port while keeping old overloads for tests/callers.
text = STARTUP.read_text(encoding="utf-8")
old = '''    static Result generate(String source,ProxyRuntimeProfile profile)throws IOException{\n        return generate(source,profile,"bichen-test-controller");\n    }\n\n    static Result generate(String source,ProxyRuntimeProfile profile,String controllerSecret)throws IOException{\n'''
new = '''    static Result generate(String source,ProxyRuntimeProfile profile)throws IOException{\n        return generate(source,profile,"bichen-test-controller",CONTROLLER_PORT);\n    }\n\n    static Result generate(String source,ProxyRuntimeProfile profile,String controllerSecret)throws IOException{\n        return generate(source,profile,controllerSecret,CONTROLLER_PORT);\n    }\n\n    static Result generate(String source,ProxyRuntimeProfile profile,String controllerSecret,int controllerPort)throws IOException{\n'''
if old not in text:
    raise SystemExit('test45: startup generate overload block not found')
text = text.replace(old, new, 1)
needle = '        if(controllerSecret==null||controllerSecret.trim().isEmpty())throw new IOException("控制接口密钥为空");\n'
if needle not in text:
    raise SystemExit('test45: secret validation not found')
text = text.replace(needle, needle + '        if(controllerPort<1024||controllerPort>65535)throw new IOException("控制接口端口无效");\n', 1)
old_ctl = '        override.append("external-controller: 127.0.0.1:").append(CONTROLLER_PORT).append(\'\\n\');\n'
new_ctl = '        override.append("external-controller: 127.0.0.1:").append(controllerPort).append(\'\\n\');\n'
if old_ctl not in text:
    raise SystemExit('test45: fixed controller output not found')
text = text.replace(old_ctl, new_ctl, 1)
STARTUP.write_text(text, encoding="utf-8")

# Root manager: select the first free localhost API port before writing startup config.
text = MGR.read_text(encoding="utf-8")
if 'import java.net.*;' not in text:
    text = text.replace('import java.io.*;\n', 'import java.io.*;\nimport java.net.*;\n', 1)
old = '''        final String startup;\n        final int tproxyPort,redirectPort;\n        Prepared(ProxyRuntimeProfile p,ProxyConfigLibrary.Entry s,RootProxyPolicy policy,ProxyAdblockRules.Snapshot adblock,String y,int tp,int rp){\n            profile=p;source=s;this.policy=policy;this.adblock=adblock;startup=y;tproxyPort=tp;redirectPort=rp;\n        }\n'''
new = '''        final String startup;\n        final int tproxyPort,redirectPort,controllerPort;\n        Prepared(ProxyRuntimeProfile p,ProxyConfigLibrary.Entry s,RootProxyPolicy policy,ProxyAdblockRules.Snapshot adblock,String y,int tp,int rp,int cp){\n            profile=p;source=s;this.policy=policy;this.adblock=adblock;startup=y;tproxyPort=tp;redirectPort=rp;controllerPort=cp;\n        }\n'''
if old not in text:
    raise SystemExit('test45: Prepared block not found')
text = text.replace(old, new, 1)
anchor = '''    String controllerSecret(){\n        String s=prefs.getString("proxyControllerSecret","");\n        if(s==null||s.isEmpty()){\n            s=UUID.randomUUID().toString().replace("-","")+Long.toHexString(System.nanoTime());\n            prefs.edit().putString("proxyControllerSecret",s).commit();\n        }\n        return s;\n    }\n\n'''
if anchor not in text:
    raise SystemExit('test45: controllerSecret block not found')
chooser = anchor + '''    private int chooseControllerPort()throws IOException{\n        int preferred=prefs.getInt("proxyControllerPort",MihomoStartupConfig.CONTROLLER_PORT);\n        LinkedHashSet<Integer> candidates=new LinkedHashSet<>();\n        if(preferred>=29090&&preferred<=29149)candidates.add(preferred);\n        for(int port=29090;port<=29149;port++)candidates.add(port);\n        for(int port:candidates){\n            try(ServerSocket socket=new ServerSocket()){\n                socket.setReuseAddress(false);\n                socket.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"),port));\n                prefs.edit().putInt("proxyControllerPort",port).commit();\n                return port;\n            }catch(IOException occupied){ }\n        }\n        throw new IOException("辟尘控制接口动态端口 29090-29149 均被占用，请关闭冲突代理后重试");\n    }\n\n'''
text = text.replace(anchor, chooser, 1)
old_gen = '        MihomoStartupConfig.Result generated=MihomoStartupConfig.generate(source,profile,controllerSecret());\n        writeStartupCopy(generated.yaml);\n        return new Prepared(profile,selected,policy,adblock,generated.yaml,generated.tproxyPort,generated.redirectPort);\n'
new_gen = '        int controllerPort=chooseControllerPort();\n        MihomoStartupConfig.Result generated=MihomoStartupConfig.generate(source,profile,controllerSecret(),controllerPort);\n        writeStartupCopy(generated.yaml);\n        return new Prepared(profile,selected,policy,adblock,generated.yaml,generated.tproxyPort,generated.redirectPort,controllerPort);\n'
if old_gen not in text:
    raise SystemExit('test45: prepare generation block not found')
text = text.replace(old_gen, new_gen, 1)
count = text.count('String.valueOf(MihomoStartupConfig.CONTROLLER_PORT)')
if count < 3:
    raise SystemExit(f'test45: expected at least 3 fixed controller-port args, found {count}')
text = text.replace('String.valueOf(MihomoStartupConfig.CONTROLLER_PORT)', 'String.valueOf(p.controllerPort)')
put_anchor = '                .put("adblockRevision",p.adblock==null?0:p.adblock.revision)'
# branch currently stores adblockRevision as string; patch using safer existing neighboring field.
if '.put("controllerPort",p.controllerPort)' not in text:
    marker = '                .put("adblockRuleCount",p.adblock==null?0:p.adblock.count)\n'
    if marker not in text:
        raise SystemExit('test45: result metadata insertion point not found')
    text = text.replace(marker, marker + '                .put("controllerPort",p.controllerPort)\n', 1)
MGR.write_text(text, encoding="utf-8")

# Clash API client: every request reads the port chosen for the current runtime.
text = CLIENT.read_text(encoding="utf-8")
text = text.replace('    private static final int PORT=MihomoStartupConfig.CONTROLLER_PORT;\n', '', 1)
secret = '''    private String secret()throws IOException{\n        String value=context.getSharedPreferences("bichen",0).getString("proxyControllerSecret","");\n        if(value==null||value.isEmpty())throw new IOException("策略控制接口尚未初始化");\n        return value;\n    }\n\n'''
if secret not in text:
    raise SystemExit('test45: client secret block not found')
port_method = secret + '''    private int port(){\n        int value=context.getSharedPreferences("bichen",0).getInt("proxyControllerPort",MihomoStartupConfig.CONTROLLER_PORT);\n        return value>=1024&&value<=65535?value:MihomoStartupConfig.CONTROLLER_PORT;\n    }\n\n'''
text = text.replace(secret, port_method, 1)
old_req = '''        byte[] payload=body==null?new byte[0]:body.toString().getBytes(StandardCharsets.UTF_8);\n        Socket socket=new Socket();\n        try{\n            socket.connect(new InetSocketAddress(InetAddress.getByName("127.0.0.1"),PORT),2200);\n'''
new_req = '''        byte[] payload=body==null?new byte[0]:body.toString().getBytes(StandardCharsets.UTF_8);\n        int port=port();\n        Socket socket=new Socket();\n        try{\n            socket.connect(new InetSocketAddress(InetAddress.getByName("127.0.0.1"),port),2200);\n'''
if old_req not in text:
    raise SystemExit('test45: client request connect block not found')
text = text.replace(old_req, new_req, 1)
text = text.replace('.append("Host: 127.0.0.1:").append(PORT).append("\\r\\n")', '.append("Host: 127.0.0.1:").append(port).append("\\r\\n")', 1)
if 'PORT)' in text or 'append(PORT)' in text:
    raise SystemExit('test45: stale fixed controller PORT remains in request path')
CLIENT.write_text(text, encoding="utf-8")

# Runtime log: make future port/stage collisions visible immediately.
text = INSPECTOR.read_text(encoding="utf-8")
old = '''        val command = "echo '--- start-state ---'; cat /data/adb/bichen/proxy/run/start-state 2>/dev/null || true; echo '--- last-start-error ---'; cat /data/adb/bichen/proxy/run/last-start-error 2>/dev/null || true; echo '--- core.log ---'; tail -n 140 /data/adb/bichen/proxy/run/core.log 2>&1 || true; echo '--- watchdog.log ---'; tail -n 30 /data/adb/bichen/proxy/run/watchdog.log 2>/dev/null || true; echo '--- last-crash ---'; cat /data/adb/bichen/proxy/run/last-crash 2>/dev/null || true"\n'''
if old not in text:
    # test44 may have only old log string if workflow patch changed a different exact form
    old = '''        val command = "echo '--- core.log ---'; tail -n 140 /data/adb/bichen/proxy/run/core.log 2>&1 || true; echo '--- watchdog.log ---'; tail -n 30 /data/adb/bichen/proxy/run/watchdog.log 2>/dev/null || true; echo '--- last-crash ---'; cat /data/adb/bichen/proxy/run/last-crash 2>/dev/null || true"\n'''
new = '''        val port = prefs.getInt("proxyControllerPort", MihomoStartupConfig.CONTROLLER_PORT)\n        val command = "echo '--- controller-port ---'; echo ${'$'}port; (ss -lntp 2>/dev/null || netstat -lntp 2>/dev/null || true) | grep -E ':${'$'}port([[:space:]]|${'$'})' || true; echo '--- start-state ---'; cat /data/adb/bichen/proxy/run/start-state 2>/dev/null || true; echo '--- last-start-error ---'; cat /data/adb/bichen/proxy/run/last-start-error 2>/dev/null || true; echo '--- core.log ---'; tail -n 140 /data/adb/bichen/proxy/run/core.log 2>&1 || true; echo '--- watchdog.log ---'; tail -n 30 /data/adb/bichen/proxy/run/watchdog.log 2>/dev/null || true; echo '--- last-crash ---'; cat /data/adb/bichen/proxy/run/last-crash 2>/dev/null || true"\n'''
if old not in text:
    raise SystemExit('test45: runtime log command not found')
text = text.replace(old, new, 1)
INSPECTOR.write_text(text, encoding="utf-8")

# Hard checks.
checks = {
    BUILD: ['versionCode = 445', 'versionName = "0.4.0-test.45"'],
    STARTUP: ['generate(String source,ProxyRuntimeProfile profile,String controllerSecret,int controllerPort)', '.append(controllerPort)'],
    MGR: ['chooseControllerPort()', 'p.controllerPort', 'putInt("proxyControllerPort",port)'],
    CLIENT: ['private int port()', 'int port=port();', '.append(port).append("\\r\\n")'],
    INSPECTOR: ['--- controller-port ---', 'proxyControllerPort'],
}
for path, needles in checks.items():
    body = path.read_text(encoding="utf-8")
    for needle in needles:
        if needle not in body:
            raise SystemExit(f'test45: missing {needle} in {path}')
print('test45 dynamic localhost controller-port allocation applied')
