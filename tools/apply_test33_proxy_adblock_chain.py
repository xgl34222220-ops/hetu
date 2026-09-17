#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

def replace_once(rel, old, new):
    path = ROOT / rel
    text = path.read_text(encoding='utf-8')
    if old not in text:
        raise SystemExit(f'patch anchor missing in {rel}: {old[:180]!r}')
    path.write_text(text.replace(old, new, 1), encoding='utf-8')

# Version.
replace_once(
    'android-app/app/build.gradle.kts',
    'versionCode = 432\n        versionName = "0.4.0-test.32"',
    'versionCode = 433\n        versionName = "0.4.0-test.33"',
)

# Register the chained-adblock proxy page.
replace_once(
    'android-app/app/src/main/AndroidManifest.xml',
    '        <activity android:name=".ProxyAppSelectionActivity" android:label="代理应用名单" android:exported="false" />',
    '        <activity android:name=".ProxyAppSelectionActivity" android:label="代理应用名单" android:exported="false" />\n'
    '        <activity android:name=".ProxyAdblockChainActivity" android:label="代理广告过滤" android:exported="false" />',
)

# Runtime profile owns the chained-adblock switch. Keep the old constructor for host tests/callers.
profile = 'android-app/app/src/main/java/io/github/xgl34222220/bichen/ProxyRuntimeProfile.java'
replace_once(
    profile,
    '    final Core core;final Mode mode;final Ipv6 ipv6;final AppScope appScope;final DnsHijack dnsHijack;final boolean autoOverwrite,tcp,udp,quicBlocked,cnIpDirect;\n'
    '    ProxyRuntimeProfile(Core core,Mode mode,Ipv6 ipv6,AppScope appScope,DnsHijack dnsHijack,boolean autoOverwrite,boolean tcp,boolean udp,boolean quicBlocked,boolean cnIpDirect){this.core=core;this.mode=mode;this.ipv6=ipv6;this.appScope=appScope;this.dnsHijack=dnsHijack;this.autoOverwrite=autoOverwrite;this.tcp=tcp;this.udp=udp;this.quicBlocked=quicBlocked;this.cnIpDirect=cnIpDirect;}\n\n'
    '    static ProxyRuntimeProfile load(SharedPreferences p){return new ProxyRuntimeProfile(Core.from(p.getString("proxyBaseCore","mihomo")),Mode.from(p.getString("proxyBaseMode","tproxy")),Ipv6.from(p.getString("proxyBaseIpv6","enable")),AppScope.from(p.getString("proxyAppScope","blacklist")),DnsHijack.from(p.getString("proxyDnsHijack","tproxy")),p.getBoolean("proxyBaseAutoOverwrite",true),p.getBoolean("proxyTcp",true),p.getBoolean("proxyUdp",true),p.getBoolean("proxyQuicBlocked",false),p.getBoolean("proxyCnIpDirect",false));}',
    '    final Core core;final Mode mode;final Ipv6 ipv6;final AppScope appScope;final DnsHijack dnsHijack;final boolean autoOverwrite,tcp,udp,quicBlocked,cnIpDirect,adblockChain;\n'
    '    ProxyRuntimeProfile(Core core,Mode mode,Ipv6 ipv6,AppScope appScope,DnsHijack dnsHijack,boolean autoOverwrite,boolean tcp,boolean udp,boolean quicBlocked,boolean cnIpDirect){this(core,mode,ipv6,appScope,dnsHijack,autoOverwrite,tcp,udp,quicBlocked,cnIpDirect,false);}\n'
    '    ProxyRuntimeProfile(Core core,Mode mode,Ipv6 ipv6,AppScope appScope,DnsHijack dnsHijack,boolean autoOverwrite,boolean tcp,boolean udp,boolean quicBlocked,boolean cnIpDirect,boolean adblockChain){this.core=core;this.mode=mode;this.ipv6=ipv6;this.appScope=appScope;this.dnsHijack=dnsHijack;this.autoOverwrite=autoOverwrite;this.tcp=tcp;this.udp=udp;this.quicBlocked=quicBlocked;this.cnIpDirect=cnIpDirect;this.adblockChain=adblockChain;}\n\n'
    '    static ProxyRuntimeProfile load(SharedPreferences p){return new ProxyRuntimeProfile(Core.from(p.getString("proxyBaseCore","mihomo")),Mode.from(p.getString("proxyBaseMode","tproxy")),Ipv6.from(p.getString("proxyBaseIpv6","enable")),AppScope.from(p.getString("proxyAppScope","blacklist")),DnsHijack.from(p.getString("proxyDnsHijack","tproxy")),p.getBoolean("proxyBaseAutoOverwrite",true),p.getBoolean("proxyTcp",true),p.getBoolean("proxyUdp",true),p.getBoolean("proxyQuicBlocked",false),p.getBoolean("proxyCnIpDirect",false),p.getBoolean("proxyAdblockChain",true));}',
)

# Inject a local Mihomo domain rule-provider before CNIP/source routing rules.
startup = 'android-app/app/src/main/java/io/github/xgl34222220/bichen/MihomoStartupConfig.java'
replace_once(
    startup,
    '    static final String CNIP_V6_PATH="./ruleset/bichen-cn-v6.txt";',
    '    static final String CNIP_V6_PATH="./ruleset/bichen-cn-v6.txt";\n'
    '    static final String ADBLOCK_PATH=ProxyAdblockRules.PROVIDER_PATH;',
)
replace_once(
    startup,
    '        if(profile.cnIpDirect)\n            yaml=ensureCnIpDirect(yaml);',
    '        if(profile.cnIpDirect)\n            yaml=ensureCnIpDirect(yaml);\n'
    '        // Apply ad blocking last so its REJECT rule stays ahead of CNIP and all source routing.\n'
    '        if(profile.adblockChain)\n            yaml=ensureAdblock(yaml);',
)
replace_once(
    startup,
    '    /** Merge Bichen CN IP providers into the private startup copy without editing the subscription. */',
    '''    /** Merge the local effective Bichen ad-block snapshot into the private startup copy. */
    private static String ensureAdblock(String source)throws IOException{
        String yaml=normalize(source);
        yaml=injectAdblockProvider(yaml);
        yaml=prependAdblockRule(yaml);
        return yaml;
    }

    private static String injectAdblockProvider(String source)throws IOException{
        String[] lines=normalize(source).split("\\n",-1);
        int index=-1;Matcher found=null;Pattern top=Pattern.compile("^rule-providers\\s*:(.*)$");
        for(int i=0;i<lines.length;i++){if(indent(lines[i])!=0)continue;Matcher m=top.matcher(lines[i]);if(m.find()){index=i;found=m;break;}}
        String providerBlock=
                "  "+ProxyAdblockRules.PROVIDER_NAME+":\\n"+
                "    type: file\\n"+
                "    behavior: domain\\n"+
                "    format: text\\n"+
                "    path: "+ADBLOCK_PATH+"\\n";
        if(index<0){String base=trimOne(source);return base+(base.isEmpty()?"":"\\n")+"rule-providers:\\n"+providerBlock;}
        String rest=found.group(1).trim();
        if(!rest.isEmpty()&&!rest.equals("{}"))throw new IOException("代理串联去广告需要普通 rule-providers: 配置块；当前源配置使用行内写法");
        int end=lines.length;
        for(int i=index+1;i<lines.length;i++){String t=lines[i].trim();if(t.isEmpty()||t.startsWith("#"))continue;if(indent(lines[i])==0){end=i;break;}}
        for(int i=index+1;i<end;i++)if(lines[i].trim().startsWith(ProxyAdblockRules.PROVIDER_NAME+":"))throw new IOException("源配置占用了辟尘保留的广告 provider 名称");
        StringBuilder out=new StringBuilder();
        for(int i=0;i<lines.length;i++){if(i==index){out.append("rule-providers:\\n").append(providerBlock);continue;}out.append(lines[i]).append('\\n');}
        return trimOne(out.toString());
    }

    private static String prependAdblockRule(String source)throws IOException{
        String[] lines=normalize(source).split("\\n",-1);
        int index=-1;Matcher found=null;Pattern top=Pattern.compile("^rules\\s*:(.*)$");
        for(int i=0;i<lines.length;i++){if(indent(lines[i])!=0)continue;Matcher m=top.matcher(lines[i]);if(m.find()){index=i;found=m;break;}}
        String rule="  - RULE-SET,"+ProxyAdblockRules.PROVIDER_NAME+",REJECT\\n";
        if(index<0){String base=trimOne(source);return base+(base.isEmpty()?"":"\\n")+"rules:\\n"+rule;}
        String rest=found.group(1).trim();
        if(!rest.isEmpty()&&!rest.equals("[]"))throw new IOException("代理串联去广告需要普通 rules: 列表；当前源配置使用行内 rules 写法");
        StringBuilder out=new StringBuilder();
        for(int i=0;i<lines.length;i++){if(i==index){out.append("rules:\\n").append(rule);continue;}out.append(lines[i]).append('\\n');}
        return trimOne(out.toString());
    }

    /** Merge Bichen CN IP providers into the private startup copy without editing the subscription. */''',
)

# Root control plane exports and installs the current ad-block snapshot, then hands off from standalone filtering.
manager = 'android-app/app/src/main/java/io/github/xgl34222220/bichen/RootProxyManager.java'
replace_once(
    manager,
    '        final RootProxyPolicy policy;\n        final String startup;\n        final int tproxyPort,redirectPort;\n        Prepared(ProxyRuntimeProfile p,ProxyConfigLibrary.Entry s,RootProxyPolicy policy,String y,int tp,int rp){\n            profile=p;source=s;this.policy=policy;startup=y;tproxyPort=tp;redirectPort=rp;\n        }',
    '        final RootProxyPolicy policy;\n        final ProxyAdblockRules.Snapshot adblock;\n        final String startup;\n        final int tproxyPort,redirectPort;\n        Prepared(ProxyRuntimeProfile p,ProxyConfigLibrary.Entry s,RootProxyPolicy policy,ProxyAdblockRules.Snapshot adblock,String y,int tp,int rp){\n            profile=p;source=s;this.policy=policy;this.adblock=adblock;startup=y;tproxyPort=tp;redirectPort=rp;\n        }',
)
replace_once(
    manager,
    '        MihomoStartupConfig.Result generated=MihomoStartupConfig.generate(source,profile,controllerSecret());\n        writeStartupCopy(generated.yaml);\n        return new Prepared(profile,selected,policy,generated.yaml,generated.tproxyPort,generated.redirectPort);',
    '        ProxyAdblockRules.Snapshot adblock=profile.adblockChain?ProxyAdblockRules.export(context):null;\n'
    '        if(profile.adblockChain&&adblock.count<=0)throw new IOException("代理串联去广告已开启，但当前没有有效广告规则；请先启用或更新规则源");\n'
    '        MihomoStartupConfig.Result generated=MihomoStartupConfig.generate(source,profile,controllerSecret());\n'
    '        writeStartupCopy(generated.yaml);\n'
    '        return new Prepared(profile,selected,policy,adblock,generated.yaml,generated.tproxyPort,generated.redirectPort);',
)
replace_once(
    manager,
    '        stage(progress,"启动核心并等待监听端口就绪…");\n        JSONObject result=runJson("start",',
    '        boolean chainEntered=false;\n'
    '        if(profile.adblockChain){\n'
    '            stage(progress,"切换到代理串联去广告，暂停独立 DNS / hosts 过滤…");\n'
    '            ProxyAdblockCoordinator.enter(context);chainEntered=true;\n'
    '        }\n'
    '        stage(progress,"启动核心并等待监听端口就绪…");\n'
    '        JSONObject result;\n'
    '        try{result=runJson("start",',
)
replace_once(
    manager,
    '                policy.appScope,policy.uidRanges,bit(policy.sharedNetwork),bit(policy.killSwitch),policy.cidrs,policy.interfaces);\n        if(!result.optBoolean("ok"))throw new IOException(result.optString("message","Root 代理启动失败"));',
    '                policy.appScope,policy.uidRanges,bit(policy.sharedNetwork),bit(policy.killSwitch),policy.cidrs,policy.interfaces);\n'
    '            if(!result.optBoolean("ok"))throw new IOException(result.optString("message","Root 代理启动失败"));\n'
    '        }catch(Exception startFailure){if(chainEntered)ProxyAdblockCoordinator.exit(context);throw startFailure;}',
)
replace_once(
    manager,
    '                .put("cnIpDirect",profile.cnIpDirect)\n                .put("bypassCidrs",policy.cidrs)',
    '                .put("cnIpDirect",profile.cnIpDirect)\n'
    '                .put("adblockChain",profile.adblockChain)\n'
    '                .put("adblockRuleCount",p.adblock==null?0:p.adblock.count)\n'
    '                .put("adblockRevision",p.adblock==null?"":p.adblock.revision)\n'
    '                .put("bypassCidrs",policy.cidrs)',
)
replace_once(
    manager,
    '        JSONObject r=runJsonAllowMissing("stop",new JSONObject().put("ok",true).put("running",false).put("state","idle").put("message","Root 代理未运行"));\n        stage(progress,"网络规则与临时 IPv6 状态已恢复");',
    '        JSONObject r=runJsonAllowMissing("stop",new JSONObject().put("ok",true).put("running",false).put("state","idle").put("message","Root 代理未运行"));\n'
    '        ProxyAdblockCoordinator.exit(context);\n'
    '        stage(progress,"网络规则、广告过滤接管与临时 IPv6 状态已恢复");',
)
replace_once(
    manager,
    '        File cfg=new File(stage,"startup-config");\n        File cn4=null,cn6=null;',
    '        File cfg=new File(stage,"startup-config");\n        File adblock=p.adblock==null?null:p.adblock.file;\n        File cn4=null,cn6=null;',
)
replace_once(
    manager,
    '        if(p.profile.cnIpDirect){\n            String dst4=ROOT+"/run/ruleset/bichen-cn-v4.txt",dst6=ROOT+"/run/ruleset/bichen-cn-v6.txt";',
    '        if(adblock!=null){\n'
    '            String dst=ROOT+"/run/ruleset/bichen-adblock.txt",tmp=dst+".new";\n'
    '            cmd.append("; cp ").append(RootBridge.quote(adblock.getAbsolutePath())).append(\' \').append(RootBridge.quote(tmp))\n'
    '                    .append("; chmod 600 ").append(RootBridge.quote(tmp)).append("; chown 0:0 ").append(RootBridge.quote(tmp))\n'
    '                    .append("; mv -f ").append(RootBridge.quote(tmp)).append(\' \').append(RootBridge.quote(dst));\n'
    '        }\n'
    '        if(p.profile.cnIpDirect){\n'
    '            String dst4=ROOT+"/run/ruleset/bichen-cn-v4.txt",dst6=ROOT+"/run/ruleset/bichen-cn-v6.txt";',
)

# DNS-only VPN gains a proxy-pause action that preserves the user's standalone intent.
dns = 'android-app/app/src/main/java/io/github/xgl34222220/bichen/DnsVpnService.java'
replace_once(
    dns,
    '    public static final String ACTION_RELOAD = "io.github.xgl34222220.bichen.VPN_RELOAD";',
    '    public static final String ACTION_RELOAD = "io.github.xgl34222220.bichen.VPN_RELOAD";\n'
    '    public static final String ACTION_PAUSE_FOR_PROXY = "io.github.xgl34222220.bichen.VPN_PAUSE_FOR_PROXY";',
)
replace_once(
    dns,
    '        showForeground("正在准备 DNS 防护…");\n        if (ACTION_STOP.equals(action)) {',
    '        showForeground("正在准备 DNS 防护…");\n'
    '        if (ACTION_PAUSE_FOR_PROXY.equals(action)) {\n'
    '            final boolean wanted = prefs.getBoolean("vpnWanted", false);\n'
    '            requestedStop = true;\n'
    '            prefs.edit().putBoolean("proxyResumeDnsAfterChain", wanted).putBoolean("vpnWanted", wanted).apply();\n'
    '            closeNetwork();\n'
    '            submit(() -> { synchronized (MODE_LOCK) {\n'
    '                if (latestInstance == this) prefs.edit().putBoolean("vpnWanted", wanted).apply();\n'
    '                if (stopSelfResult(startId)) stopForeground(STOP_FOREGROUND_REMOVE);\n'
    '            }});\n'
    '            return START_NOT_STICKY;\n'
    '        }\n'
    '        if (ACTION_STOP.equals(action)) {',
)
replace_once(
    dns,
    '                if (latestInstance == this) { String error = restoreHosts(); if (error != null) setError(error); }',
    '                if (latestInstance == this && !prefs.getBoolean("proxyAdblockChainActive", false)) { String error = restoreHosts(); if (error != null) setError(error); }',
)

# Unified Compose advanced settings exposes the chain and its dedicated management page.
advanced = 'android-app/app/src/main/java/io/github/xgl34222220/bichen/ProxyAdvancedSettingsActivity.kt'
replace_once(
    advanced,
    '            "sharing" -> 6\n            "cnip" -> 8\n            "bypass" -> 10',
    '            "adblock" -> 4\n            "sharing" -> 10\n            "cnip" -> 12\n            "bypass" -> 14',
)
replace_once(
    advanced,
    '        item { AdvancedSectionLabel("DNS 与协议") }',
    '''        item { AdvancedSectionLabel("广告过滤") }
        item {
            AdvancedGroup {
                AdvancedSwitchRow(Icons.Rounded.Shield, Color(0xFF2563EB), "随代理串联去广告", "广告规则先 REJECT，剩余流量再进入代理分流", profile.adblockChain) { putBool("proxyAdblockChain", it) }
                AdvancedDivider()
                AdvancedActionRow(Icons.Rounded.FilterAlt, Color(0xFF8B5CF6), "广告规则与命中", "规则源 · 有效规则 · Mihomo REJECT 命中") {
                    context.startActivity(Intent(context, ProxyAdblockChainActivity::class.java))
                }
            }
        }

        item { AdvancedSectionLabel("DNS 与协议") }''',
)

# Proxy tools/settings make chained ad blocking a first-class destination.
ref = 'android-app/app/src/main/java/io/github/xgl34222220/bichen/ReferenceProxyActivity.kt'
replace_once(
    ref,
    '                RefToolRow(Icons.Rounded.CloudDownload, Color(0xFF3B82F6), "订阅管理", state.config) { context.startActivity(Intent(context, ProxySubscriptionActivity::class.java)) }\n                RefDivider()\n                RefToolRow(Icons.Rounded.Public, Color(0xFFF59E0B), "CNIP 设置", "国内 IPv4/IPv6 自动直连")',
    '                RefToolRow(Icons.Rounded.CloudDownload, Color(0xFF3B82F6), "订阅管理", state.config) { context.startActivity(Intent(context, ProxySubscriptionActivity::class.java)) }\n'
    '                RefDivider()\n'
    '                RefToolRow(Icons.Rounded.Shield, Color(0xFF2563EB), "广告过滤", "代理串联 · AdAway / 秋风 / HaGeZi / 用户规则") { context.startActivity(Intent(context, ProxyAdblockChainActivity::class.java)) }\n'
    '                RefDivider()\n'
    '                RefToolRow(Icons.Rounded.Public, Color(0xFFF59E0B), "CNIP 设置", "国内 IPv4/IPv6 自动直连")',
)
replace_once(
    ref,
    '                RefValueRow("当前配置", state.config, Icons.Rounded.Description, Color(0xFF8B5CF6)) { context.startActivity(Intent(context, ProxySubscriptionActivity::class.java)) }\n                RefDivider()\n                RefValueRow("高级代理配置",',
    '                RefValueRow("当前配置", state.config, Icons.Rounded.Description, Color(0xFF8B5CF6)) { context.startActivity(Intent(context, ProxySubscriptionActivity::class.java)) }\n'
    '                RefDivider()\n'
    '                RefValueRow("广告过滤", if (prefs.getBoolean("proxyAdblockChain", true)) "随代理串联" else "关闭", Icons.Rounded.Shield, Color(0xFF2563EB)) { context.startActivity(Intent(context, ProxyAdblockChainActivity::class.java)) }\n'
    '                RefDivider()\n'
    '                RefValueRow("高级代理配置",',
)

# Host regression: verify local file provider, ordering, and combined CNIP+adblock ordering.
test = 'tests/MihomoStartupConfigTest.java'
replace_once(
    test,
    '  check(cn.yaml.contains("./ruleset/bichen-cn-v4.txt")&&cn.yaml.contains("interval: 86400"),"CNIP cache path and refresh interval configured");',
    '''  check(cn.yaml.contains("./ruleset/bichen-cn-v4.txt")&&cn.yaml.contains("interval: 86400"),"CNIP cache path and refresh interval configured");
  ProxyRuntimeProfile adProfile=new ProxyRuntimeProfile(ProxyRuntimeProfile.Core.MIHOMO,ProxyRuntimeProfile.Mode.TPROXY,ProxyRuntimeProfile.Ipv6.BYPASS,ProxyRuntimeProfile.AppScope.BLACKLIST,ProxyRuntimeProfile.DnsHijack.TPROXY,true,true,true,false,false,true);
  MihomoStartupConfig.Result ad=MihomoStartupConfig.generate(cnSource,adProfile);
  check(ad.yaml.contains("bichen-adblock:")&&ad.yaml.contains("type: file")&&ad.yaml.contains("behavior: domain")&&ad.yaml.contains("format: text"),"adblock local domain provider injected");
  check(ad.yaml.contains("path: ./ruleset/bichen-adblock.txt"),"adblock provider stays inside Mihomo HomeDir");
  check(ad.yaml.contains("RULE-SET,bichen-adblock,REJECT")&&ad.yaml.indexOf("RULE-SET,bichen-adblock")<ad.yaml.indexOf("MATCH,DIRECT"),"adblock REJECT precedes source routing");
  ProxyRuntimeProfile bothProfile=new ProxyRuntimeProfile(ProxyRuntimeProfile.Core.MIHOMO,ProxyRuntimeProfile.Mode.TPROXY,ProxyRuntimeProfile.Ipv6.BYPASS,ProxyRuntimeProfile.AppScope.BLACKLIST,ProxyRuntimeProfile.DnsHijack.TPROXY,true,true,true,false,true,true);
  MihomoStartupConfig.Result both=MihomoStartupConfig.generate(cnSource,bothProfile);
  check(both.yaml.indexOf("RULE-SET,bichen-adblock")<both.yaml.indexOf("RULE-SET,bichen-cn-v4")&&both.yaml.indexOf("RULE-SET,bichen-cn-v4")<both.yaml.indexOf("MATCH,DIRECT"),"adblock executes before CNIP and source fallback");''',
)

print('test.33 proxy chained adblock patch applied')
