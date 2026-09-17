from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

def replace_once(path: Path, old: str, new: str, label: str):
    text = path.read_text(encoding='utf-8')
    if old not in text:
        raise SystemExit(f'missing anchor: {label}')
    text = text.replace(old, new, 1)
    path.write_text(text, encoding='utf-8')

# Version bump.
build = ROOT / 'android-app/app/build.gradle.kts'
text = build.read_text(encoding='utf-8')
text = text.replace('versionCode = 435', 'versionCode = 436')
text = text.replace('versionName = "0.4.0-test.35"', 'versionName = "0.4.0-test.36"')
build.write_text(text, encoding='utf-8')

# 1) Make startup failures visible and diagnostic instead of looking like a dead button.
ref = ROOT / 'android-app/app/src/main/java/io/github/xgl34222220/bichen/ReferenceProxyActivity.kt'
replace_once(ref,
'''    var testing by remember { mutableStateOf(false) }\n    var logText by remember { mutableStateOf<String?>(null) }''',
'''    var testing by remember { mutableStateOf(false) }\n    var logText by remember { mutableStateOf<String?>(null) }\n    var startupError by remember { mutableStateOf<String?>(null) }''',
'ref startupError state')
replace_once(ref,
'''            } catch (error: Exception) {\n                message = error.message ?: "操作失败"\n            } finally {\n                operation = ""\n            }\n        }\n    }\n\n    fun reload()''',
'''            } catch (error: Exception) {\n                val reason = error.message ?: "操作失败"\n                message = reason\n                if (!state.running) {\n                    val diagnostics = runCatching { controller.diagnostics() }.getOrDefault("").trim()\n                    startupError = buildString {\n                        append(reason)\n                        if (diagnostics.isNotBlank()) {\n                            append("\\n\\n--- Root / Mihomo 诊断 ---\\n")\n                            append(diagnostics)\n                        }\n                    }\n                }\n            } finally {\n                operation = ""\n            }\n        }\n    }\n\n    fun reload()''',
'ref startup catch')
replace_once(ref,
'''    logText?.let { text ->\n        RefInfoBottomSheet(\n            title = "运行日志",\n            text = text,\n            actionLabel = "关闭",\n            onDismiss = { logText = null },\n        )\n    }\n}''',
'''    logText?.let { text ->\n        RefInfoBottomSheet(\n            title = "运行日志",\n            text = text,\n            actionLabel = "关闭",\n            onDismiss = { logText = null },\n        )\n    }\n    startupError?.let { text ->\n        RefInfoBottomSheet(\n            title = "启动失败",\n            text = text,\n            actionLabel = "关闭",\n            onDismiss = { startupError = null },\n        )\n    }\n}''',
'ref startup error sheet')

# 2) Handle stale hidden core/mode preferences before starting.
controller = ROOT / 'android-app/app/src/main/java/io/github/xgl34222220/bichen/ProxyComposeController.kt'
replace_once(controller,
'''    suspend fun start(onProgress: (String) -> Unit = {}) = withContext(Dispatchers.IO) {\n        val profile = ProxyRuntimeProfile.load(prefs)\n        val selected = configs.selected(profile.core) ?: error("尚未选择配置")\n        if (!configs.hasConfiguredSubscription(selected)) {\n            error("默认配置不内置私人订阅，请先到「订阅」添加或编辑自己的订阅")\n        }\n        root.start(profile) { onProgress(it) }\n    }''',
'''    suspend fun start(onProgress: (String) -> Unit = {}) = withContext(Dispatchers.IO) {\n        var profile = ProxyRuntimeProfile.load(prefs)\n        if (!profile.capability().available) {\n            onProgress("检测到旧版本遗留的不可用核心/模式，回退到 Mihomo · TPROXY…")\n            prefs.edit().putString("proxyBaseCore", "mihomo").putString("proxyBaseMode", "tproxy").apply()\n            profile = ProxyRuntimeProfile.load(prefs)\n        }\n        if (profile.core == ProxyRuntimeProfile.Core.MIHOMO_SMART && !ProxyCoreStore(app).installed(profile.core)) {\n            onProgress("Mihomo Smart 尚未安装，先使用内置 Mihomo 启动…")\n            prefs.edit().putString("proxyBaseCore", "mihomo").apply()\n            profile = ProxyRuntimeProfile.load(prefs)\n        }\n        val selected = configs.selected(profile.core) ?: error("尚未选择配置")\n        if (!configs.hasConfiguredSubscription(selected)) {\n            error("当前是辟尘内置占位配置，尚未填写真实订阅。请打开「面板 → 订阅」添加订阅，或导入一份完整可运行的 YAML 配置。")\n        }\n        root.start(profile) { onProgress(it) }\n    }''',
'controller start migration')

# 3) If chained-adblock injection is the only reason a valid user config cannot start,
#    retry with the source config unchanged (adblock off) instead of killing the whole proxy.
manager = ROOT / 'android-app/app/src/main/java/io/github/xgl34222220/bichen/RootProxyManager.java'
replace_once(manager,
'''    private static void stage(Progress p,String text){if(p!=null)p.onStage(text);}\n    private static String bit(boolean value){return value?"1":"0";}''',
'''    private static void stage(Progress p,String text){if(p!=null)p.onStage(text);}\n    private static String bit(boolean value){return value?"1":"0";}\n    private static ProxyRuntimeProfile withoutAdblock(ProxyRuntimeProfile p){\n        return new ProxyRuntimeProfile(p.core,p.mode,p.ipv6,p.appScope,p.dnsHijack,p.autoOverwrite,p.tcp,p.udp,p.quicBlocked,p.cnIpDirect,false);\n    }\n    private static boolean adblockPreparationFailure(Exception error){\n        String m=error==null||error.getMessage()==null?"":error.getMessage();\n        return m.contains("代理串联去广告")||m.contains("广告 provider")||m.contains("广告规则")||m.contains("bichen-adblock");\n    }\n    private void rememberAdblockFallback(Exception error){\n        String m=error==null||error.getMessage()==null?"广告串联与当前配置不兼容":error.getMessage();\n        if(m.length()>600)m=m.substring(0,600)+"…";\n        prefs.edit().putString("proxyAdblockLastError",m).apply();\n    }''',
'manager helpers')
replace_once(manager,
'''        stage(progress,"检查配置、应用范围与绕过策略…");\n        Prepared p=prepare(profile);\n        RootProxyPolicy policy=p.policy;\n        stage(progress,"部署 Root 核心与事务控制器…");\n        installRuntimeFiles(p,true);\n        stage(progress,"用 Mihomo 校验最终启动配置…");\n        validateRuntimeConfig();\n        stage(progress,"检查 TPROXY / Redirect / UID / IPv6 能力…");''',
'''        stage(progress,"检查配置、应用范围与绕过策略…");\n        Prepared p;\n        try{\n            p=prepare(profile);\n        }catch(Exception prepareFailure){\n            if(!profile.adblockChain||!adblockPreparationFailure(prepareFailure))throw prepareFailure;\n            rememberAdblockFallback(prepareFailure);\n            stage(progress,"广告串联与当前 YAML 结构不兼容，先保留原配置启动代理…");\n            profile=withoutAdblock(profile);\n            p=prepare(profile);\n        }\n        RootProxyPolicy policy=p.policy;\n        stage(progress,"部署 Root 核心与事务控制器…");\n        installRuntimeFiles(p,true);\n        stage(progress,"用 Mihomo 校验最终启动配置…");\n        try{\n            validateRuntimeConfig();\n            if(profile.adblockChain)prefs.edit().remove("proxyAdblockLastError").apply();\n        }catch(Exception fullFailure){\n            if(!profile.adblockChain)throw fullFailure;\n            stage(progress,"串联广告配置校验失败，尝试不修改源 YAML 启动代理…");\n            ProxyRuntimeProfile fallbackProfile=withoutAdblock(profile);\n            Prepared fallback=prepare(fallbackProfile);\n            installRuntimeFiles(fallback,true);\n            try{validateRuntimeConfig();}\n            catch(Exception fallbackFailure){\n                throw new IOException((fullFailure.getMessage()==null?"最终配置校验失败":fullFailure.getMessage())+"；关闭广告串联后仍失败："+(fallbackFailure.getMessage()==null?"未知错误":fallbackFailure.getMessage()),fullFailure);\n            }\n            rememberAdblockFallback(fullFailure);\n            profile=fallbackProfile;\n            p=fallback;\n            policy=p.policy;\n            stage(progress,"代理配置可用；本次仅关闭串联广告过滤继续启动…");\n        }\n        stage(progress,"检查 TPROXY / Redirect / UID / IPv6 能力…");''',
'manager startup validation fallback')
replace_once(manager,
'''        boolean independentFallback=prefs.getBoolean("proxyAdblockFallbackEnabled",false);\n        if(profile.adblockChain||independentFallback){''',
'''        boolean independentFallback=prefs.getBoolean("proxyAdblockFallbackEnabled",false);\n        if(profile.adblockChain||independentFallback||DnsVpnService.running){''',
'manager dns conflict coordinator')
replace_once(manager,
'''        String warning=policy.warning();\n        result.put("sourceConfig",p.source.name)''',
'''        String warning=policy.warning();\n        String adblockFallbackReason=prefs.getString("proxyAdblockLastError","");\n        if(!profile.adblockChain&&adblockFallbackReason!=null&&!adblockFallbackReason.isEmpty()){\n            warning=(warning.isEmpty()?"":warning+"；")+"代理已启动，但广告串联本次降级："+adblockFallbackReason;\n        }\n        result.put("sourceConfig",p.source.name)''',
'manager fallback warning')

# 4) Common YAML style: allow a top-level key followed only by an inline comment.
mihomo = ROOT / 'android-app/app/src/main/java/io/github/xgl34222220/bichen/MihomoStartupConfig.java'
text = mihomo.read_text(encoding='utf-8')
text = text.replace('String rest=found.group(1).trim();\n        if(!rest.isEmpty()&&!rest.equals("{}"))throw new IOException("代理串联去广告需要普通 rule-providers: 配置块；当前源配置使用行内写法");',
'''String rest=found.group(1).trim();\n        if(rest.startsWith("#"))rest="";\n        if(!rest.isEmpty()&&!rest.equals("{}"))throw new IOException("代理串联去广告需要普通 rule-providers: 配置块；当前源配置使用行内写法");''',1)
text = text.replace('String rest=found.group(1).trim();\n        if(!rest.isEmpty()&&!rest.equals("[]"))throw new IOException("代理串联去广告需要普通 rules: 列表；当前源配置使用行内 rules 写法");',
'''String rest=found.group(1).trim();\n        if(rest.startsWith("#"))rest="";\n        if(!rest.isEmpty()&&!rest.equals("[]"))throw new IOException("代理串联去广告需要普通 rules: 列表；当前源配置使用行内 rules 写法");''',1)
mihomo.write_text(text, encoding='utf-8')

# Hard audit.
checks = {
    'android-app/app/build.gradle.kts': ['versionCode = 436', 'versionName = "0.4.0-test.36"'],
    'android-app/app/src/main/java/io/github/xgl34222220/bichen/ReferenceProxyActivity.kt': ['startupError', 'title = "启动失败"', 'controller.diagnostics()'],
    'android-app/app/src/main/java/io/github/xgl34222220/bichen/ProxyComposeController.kt': ['旧版本遗留的不可用核心/模式', '当前是辟尘内置占位配置'],
    'android-app/app/src/main/java/io/github/xgl34222220/bichen/RootProxyManager.java': ['withoutAdblock', 'proxyAdblockLastError', '串联广告配置校验失败'],
}
for rel, needles in checks.items():
    data=(ROOT/rel).read_text(encoding='utf-8')
    for needle in needles:
        if needle not in data:
            raise SystemExit(f'test.36 audit missing {needle} in {rel}')
print('test.36 startup recovery patch applied')
