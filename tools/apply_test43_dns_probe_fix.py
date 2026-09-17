from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
BUILD = ROOT / "android-app/app/build.gradle.kts"
STARTUP = ROOT / "android-app/app/src/main/java/io/github/xgl34222220/bichen/MihomoStartupConfig.java"
MANAGER = ROOT / "android-app/app/src/main/java/io/github/xgl34222220/bichen/RootProxyManager.java"

# Version
build = BUILD.read_text(encoding="utf-8")
build = build.replace('versionCode = 442', 'versionCode = 443', 1)
build = build.replace('versionName = "0.4.0-test.42"', 'versionName = "0.4.0-test.43"', 1)
if 'versionCode = 443' not in build or 'versionName = "0.4.0-test.43"' not in build:
    raise SystemExit('test43: version patch failed')
BUILD.write_text(build, encoding='utf-8')

# DNSPod stopped publicly advertising raw-IP DoH endpoints such as 1.12.12.12.
# Older user configs can therefore fail with connection refused even though the rest
# of the YAML is valid. Keep the source file untouched and normalize only Bichen's
# private runtime copy to Alibaba's secondary encrypted IP endpoint, which avoids a
# domain-bootstrap recursion in default-nameserver/proxy-server-nameserver.
startup = STARTUP.read_text(encoding='utf-8')
needle = '        String yaml=normalize(source);\n'
replacement = '''        String yaml=normalize(source);\n        yaml=normalizeDeprecatedEncryptedDns(yaml);\n'''
if needle not in startup:
    raise SystemExit('test43: startup normalize hook not found')
startup = startup.replace(needle, replacement, 1)

insert_before = '''    /** Merge the local effective Bichen ad-block snapshot into the private startup copy. */\n'''
helper = '''    /**\n     * Runtime-only compatibility for public DNS providers that retired raw-IP DoH access.\n     * The user's selected YAML remains byte-for-byte unchanged. Using 223.6.6.6 here keeps\n     * bootstrap encrypted without creating a resolver-domain bootstrap loop.\n     */\n    private static String normalizeDeprecatedEncryptedDns(String source){\n        return source\n                .replace("https://1.12.12.12/dns-query","https://223.6.6.6/dns-query")\n                .replace("https://120.53.53.53/dns-query","https://223.6.6.6/dns-query");\n    }\n\n'''
if insert_before not in startup:
    raise SystemExit('test43: helper insertion point not found')
startup = startup.replace(insert_before, helper + insert_before, 1)
STARTUP.write_text(startup, encoding='utf-8')

# test.42 treated a DNS-dependent DIRECT delay probe as a fatal startup criterion.
# With an unavailable bootstrap DoH endpoint this intentionally called stop(), which
# is exactly why the real-device log showed "Mihomo shutting down" ~15 s after start.
# Keep controller/process readiness fatal, but make internet reachability a soft
# diagnostic: transient DNS/provider failures must never kill a healthy transparent core.
manager = MANAGER.read_text(encoding='utf-8')
old = '''            MihomoControllerClient controller=new MihomoControllerClient(context);\n            if(!controller.waitReady(4500))throw new IOException("Mihomo 控制接口未在启动窗口内就绪");\n            try{controller.delay("DIRECT","https://connectivitycheck.platform.hicloud.com/generate_204","200-399");}\n            catch(Exception firstProbe){\n                try{controller.delay("DIRECT","https://www.gstatic.com/generate_204","200-399");}\n                catch(Exception secondProbe){throw new IOException("Mihomo 已启动，但 DIRECT 出站不可用；已回滚网络规则",secondProbe);}\n            }\n'''
new = '''            MihomoControllerClient controller=new MihomoControllerClient(context);\n            if(!controller.waitReady(4500))throw new IOException("Mihomo 控制接口未在启动窗口内就绪");\n            try{\n                controller.delay("DIRECT","https://connectivitycheck.platform.hicloud.com/generate_204","200-399");\n                prefs.edit().remove("proxyRootEgressWarning").apply();\n            }catch(Exception firstProbe){\n                try{\n                    controller.delay("DIRECT","https://cp.cloudflare.com/generate_204","200-399");\n                    prefs.edit().remove("proxyRootEgressWarning").apply();\n                }catch(Exception secondProbe){\n                    String detail=secondProbe.getMessage()==null?secondProbe.getClass().getSimpleName():secondProbe.getMessage();\n                    prefs.edit().putString("proxyRootEgressWarning","核心已保持运行，但启动联网探测失败："+detail).apply();\n                }\n            }\n'''
if old not in manager:
    raise SystemExit('test43: fatal egress probe block not found')
manager = manager.replace(old, new, 1)

# Surface the soft probe warning through the existing warning path without changing
# the success/running state.
warning_anchor = '''        String warning=policy.warning();\n        String adblockFallbackReason=prefs.getString("proxyAdblockLastError","");\n'''
warning_repl = '''        String warning=policy.warning();\n        String egressWarning=prefs.getString("proxyRootEgressWarning","");\n        if(egressWarning!=null&&!egressWarning.isEmpty())warning=(warning.isEmpty()?"":warning+"；")+egressWarning;\n        String adblockFallbackReason=prefs.getString("proxyAdblockLastError","");\n'''
if warning_anchor not in manager:
    raise SystemExit('test43: warning insertion point not found')
manager = manager.replace(warning_anchor, warning_repl, 1)
MANAGER.write_text(manager, encoding='utf-8')

checks = {
    BUILD: ['versionCode = 443', 'versionName = "0.4.0-test.43"'],
    STARTUP: [
        'yaml=normalizeDeprecatedEncryptedDns(yaml);',
        'https://1.12.12.12/dns-query',
        'https://223.6.6.6/dns-query',
    ],
    MANAGER: [
        'proxyRootEgressWarning',
        'https://cp.cloudflare.com/generate_204',
        'if(!controller.waitReady(4500))throw new IOException',
    ],
}
for path, needles in checks.items():
    body = path.read_text(encoding='utf-8')
    for needle in needles:
        if needle not in body:
            raise SystemExit(f'test43: missing invariant {needle} in {path}')

if 'DIRECT 出站不可用；已回滚网络规则' in MANAGER.read_text(encoding='utf-8'):
    raise SystemExit('test43: old fatal egress rollback remains')

print('test.43 DNS compatibility + nonfatal egress probe fix applied')
