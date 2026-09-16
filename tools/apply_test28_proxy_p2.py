#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def replace_once(rel, old, new):
    path = ROOT / rel
    text = path.read_text(encoding="utf-8")
    if old not in text:
        raise SystemExit(f"patch anchor missing in {rel}: {old[:120]!r}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


def insert_before(rel, anchor, addition):
    replace_once(rel, anchor, addition + anchor)


# Version.
replace_once(
    "android-app/app/build.gradle.kts",
    'versionCode = 427\n        versionName = "0.4.0-test.27"',
    'versionCode = 428\n        versionName = "0.4.0-test.28"',
)

# Runtime profile: add automatic CN IP direct switch, default off for safe upgrades.
profile = "android-app/app/src/main/java/io/github/xgl34222220/bichen/ProxyRuntimeProfile.java"
replace_once(
    profile,
    "final Core core;final Mode mode;final Ipv6 ipv6;final AppScope appScope;final DnsHijack dnsHijack;final boolean autoOverwrite,tcp,udp,quicBlocked;\n    ProxyRuntimeProfile(Core core,Mode mode,Ipv6 ipv6,AppScope appScope,DnsHijack dnsHijack,boolean autoOverwrite,boolean tcp,boolean udp,boolean quicBlocked){this.core=core;this.mode=mode;this.ipv6=ipv6;this.appScope=appScope;this.dnsHijack=dnsHijack;this.autoOverwrite=autoOverwrite;this.tcp=tcp;this.udp=udp;this.quicBlocked=quicBlocked;}",
    "final Core core;final Mode mode;final Ipv6 ipv6;final AppScope appScope;final DnsHijack dnsHijack;final boolean autoOverwrite,tcp,udp,quicBlocked,cnIpDirect;\n    ProxyRuntimeProfile(Core core,Mode mode,Ipv6 ipv6,AppScope appScope,DnsHijack dnsHijack,boolean autoOverwrite,boolean tcp,boolean udp,boolean quicBlocked,boolean cnIpDirect){this.core=core;this.mode=mode;this.ipv6=ipv6;this.appScope=appScope;this.dnsHijack=dnsHijack;this.autoOverwrite=autoOverwrite;this.tcp=tcp;this.udp=udp;this.quicBlocked=quicBlocked;this.cnIpDirect=cnIpDirect;}",
)
replace_once(
    profile,
    'p.getBoolean("proxyBaseAutoOverwrite",true),p.getBoolean("proxyTcp",true),p.getBoolean("proxyUdp",true),p.getBoolean("proxyQuicBlocked",false));}',
    'p.getBoolean("proxyBaseAutoOverwrite",true),p.getBoolean("proxyTcp",true),p.getBoolean("proxyUdp",true),p.getBoolean("proxyQuicBlocked",false),p.getBoolean("proxyCnIpDirect",false));}',
)

# Mihomo startup copy: merge two reserved http ipcidr rule-providers into ordinary block YAML.
startup = "android-app/app/src/main/java/io/github/xgl34222220/bichen/MihomoStartupConfig.java"
replace_once(startup, "import java.util.regex.*;", "import java.util.*;\nimport java.util.regex.*;")
replace_once(
    startup,
    'static final String EXTERNAL_UI_URL="https://github.com/Zephyruso/zashboard/releases/latest/download/dist-no-fonts.zip";',
    'static final String EXTERNAL_UI_URL="https://github.com/Zephyruso/zashboard/releases/latest/download/dist-no-fonts.zip";\n    static final String CNIP_V4_URL="https://raw.githubusercontent.com/gaoyifan/china-operator-ip/ip-lists/china.txt";\n    static final String CNIP_V6_URL="https://raw.githubusercontent.com/gaoyifan/china-operator-ip/ip-lists/china6.txt";\n    static final String CNIP_V4_PATH="./ruleset/bichen-cn-v4.txt";\n    static final String CNIP_V6_PATH="./ruleset/bichen-cn-v6.txt";',
)
replace_once(
    startup,
    'if(profile.dnsHijack==ProxyRuntimeProfile.DnsHijack.REDIRECT)\n            yaml=ensureDnsListener(yaml,DNS_PORT);',
    'if(profile.dnsHijack==ProxyRuntimeProfile.DnsHijack.REDIRECT)\n            yaml=ensureDnsListener(yaml,DNS_PORT);\n        if(profile.cnIpDirect)\n            yaml=ensureCnIpDirect(yaml);',
)
cn_methods = r'''    /** Merge Bichen CN IP providers into the private startup copy without editing the subscription. */
    private static String ensureCnIpDirect(String source)throws IOException{
        String yaml=normalize(source);
        yaml=injectCnProviders(yaml);
        yaml=prependCnRules(yaml);
        return yaml;
    }

    private static String injectCnProviders(String source)throws IOException{
        String[] lines=normalize(source).split("\\n",-1);
        int index=-1;
        Matcher found=null;
        Pattern top=Pattern.compile("^rule-providers\\s*:(.*)$");
        for(int i=0;i<lines.length;i++){
            if(indent(lines[i])!=0)continue;
            Matcher m=top.matcher(lines[i]);
            if(m.find()){index=i;found=m;break;}
        }
        String providerBlock=
                "  bichen-cn-v4:\n"+
                "    type: http\n"+
                "    behavior: ipcidr\n"+
                "    format: text\n"+
                "    path: "+CNIP_V4_PATH+"\n"+
                "    url: '"+CNIP_V4_URL+"'\n"+
                "    interval: 86400\n"+
                "  bichen-cn-v6:\n"+
                "    type: http\n"+
                "    behavior: ipcidr\n"+
                "    format: text\n"+
                "    path: "+CNIP_V6_PATH+"\n"+
                "    url: '"+CNIP_V6_URL+"'\n"+
                "    interval: 86400\n";
        if(index<0){
            String base=trimOne(source);
            return base+(base.isEmpty()?"":"\n")+"rule-providers:\n"+providerBlock;
        }
        String rest=found.group(1).trim();
        if(!rest.isEmpty()&&!rest.equals("{}"))
            throw new IOException("中国 IP 自动直连需要普通 rule-providers: 配置块；当前源配置使用行内写法");
        int end=lines.length;
        for(int i=index+1;i<lines.length;i++){
            String t=lines[i].trim();
            if(t.isEmpty()||t.startsWith("#"))continue;
            if(indent(lines[i])==0){end=i;break;}
        }
        for(int i=index+1;i<end;i++){
            String t=lines[i].trim();
            if(t.startsWith("bichen-cn-v4:")||t.startsWith("bichen-cn-v6:"))
                throw new IOException("源配置占用了辟尘保留的 CNIP provider 名称");
        }
        StringBuilder out=new StringBuilder();
        for(int i=0;i<lines.length;i++){
            if(i==index){out.append("rule-providers:\n").append(providerBlock);continue;}
            out.append(lines[i]).append('\n');
        }
        return trimOne(out.toString());
    }

    private static String prependCnRules(String source)throws IOException{
        String[] lines=normalize(source).split("\\n",-1);
        int index=-1;
        Matcher found=null;
        Pattern top=Pattern.compile("^rules\\s*:(.*)$");
        for(int i=0;i<lines.length;i++){
            if(indent(lines[i])!=0)continue;
            Matcher m=top.matcher(lines[i]);
            if(m.find()){index=i;found=m;break;}
        }
        String cnRules="  - RULE-SET,bichen-cn-v4,DIRECT,no-resolve\n  - RULE-SET,bichen-cn-v6,DIRECT,no-resolve\n";
        if(index<0){
            String base=trimOne(source);
            return base+(base.isEmpty()?"":"\n")+"rules:\n"+cnRules;
        }
        String rest=found.group(1).trim();
        if(!rest.isEmpty()&&!rest.equals("[]"))
            throw new IOException("中国 IP 自动直连需要普通 rules: 列表；当前源配置使用行内 rules 写法");
        StringBuilder out=new StringBuilder();
        for(int i=0;i<lines.length;i++){
            if(i==index){out.append("rules:\n").append(cnRules);continue;}
            out.append(lines[i]).append('\n');
        }
        return trimOne(out.toString());
    }

'''
insert_before(startup, "    /** Ensure Mihomo's built-in DNS server owns a dedicated TCP+UDP loop used by DNS REDIRECT. */", cn_methods)

# Root runtime: install bundled CNIP snapshot once, then let Mihomo's provider update the same cache path.
manager = "android-app/app/src/main/java/io/github/xgl34222220/bichen/RootProxyManager.java"
replace_once(
    manager,
    '.put("killSwitch",policy.killSwitch)\n                .put("bypassCidrs",policy.cidrs)',
    '.put("killSwitch",policy.killSwitch)\n                .put("cnIpDirect",profile.cnIpDirect)\n                .put("bypassCidrs",policy.cidrs)',
)
replace_once(
    manager,
    '"echo \'--- session ---\'; cat "+RootBridge.quote(ROOT+"/run/session.state")+" 2>/dev/null || true; "+',
    '"echo \'--- session ---\'; cat "+RootBridge.quote(ROOT+"/run/session.state")+" 2>/dev/null || true; "+\n                    "echo \'--- cnip cache ---\'; ls -lh "+RootBridge.quote(ROOT+"/run/ruleset")+" 2>&1 || true; "+',
)
replace_once(
    manager,
    'File cfg=new File(stage,"startup-config");\n        if(includeConfig)Files.write(cfg.toPath(),p.startup.getBytes(StandardCharsets.UTF_8));',
    'File cfg=new File(stage,"startup-config");\n        File cn4=null,cn6=null;\n        if(p.profile.cnIpDirect){\n            cn4=new File(stage,"bichen-cn-v4.txt");copyAsset("cnip/bichen-cn-v4.txt",cn4,false);\n            cn6=new File(stage,"bichen-cn-v6.txt");copyAsset("cnip/bichen-cn-v6.txt",cn6,false);\n        }\n        if(includeConfig)Files.write(cfg.toPath(),p.startup.getBytes(StandardCharsets.UTF_8));',
)
replace_once(
    manager,
    '.append(RootBridge.quote(ROOT+"/bin")).append(\' \').append(RootBridge.quote(ROOT+"/run/state"))',
    '.append(RootBridge.quote(ROOT+"/bin")).append(\' \').append(RootBridge.quote(ROOT+"/run/state")).append(\' \').append(RootBridge.quote(ROOT+"/run/ruleset"))',
)
replace_once(
    manager,
    'if(includeConfig)cmd.append("; cp ").append(RootBridge.quote(cfg.getAbsolutePath())).append(\' \').append(RootBridge.quote(CONFIG))\n                .append("; chmod 600 ").append(RootBridge.quote(CONFIG)).append("; chown 0:0 ").append(RootBridge.quote(CONFIG));',
    'if(p.profile.cnIpDirect){\n            String dst4=ROOT+"/run/ruleset/bichen-cn-v4.txt",dst6=ROOT+"/run/ruleset/bichen-cn-v6.txt";\n            cmd.append("; if [ ! -s ").append(RootBridge.quote(dst4)).append(" ]; then cp ").append(RootBridge.quote(cn4.getAbsolutePath())).append(\' \').append(RootBridge.quote(dst4)).append("; chmod 600 ").append(RootBridge.quote(dst4)).append("; chown 0:0 ").append(RootBridge.quote(dst4)).append("; fi")\n                    .append("; if [ ! -s ").append(RootBridge.quote(dst6)).append(" ]; then cp ").append(RootBridge.quote(cn6.getAbsolutePath())).append(\' \').append(RootBridge.quote(dst6)).append("; chmod 600 ").append(RootBridge.quote(dst6)).append("; chown 0:0 ").append(RootBridge.quote(dst6)).append("; fi");\n        }\n        if(includeConfig)cmd.append("; cp ").append(RootBridge.quote(cfg.getAbsolutePath())).append(\' \').append(RootBridge.quote(CONFIG))\n                .append("; chmod 600 ").append(RootBridge.quote(CONFIG)).append("; chown 0:0 ").append(RootBridge.quote(CONFIG));',
)

# Settings/UI: explicit CNIP toggle + diagnostics + deliberate recovery action.
activity = "android-app/app/src/main/java/io/github/xgl34222220/bichen/RootTproxyActivity.java"
replace_once(
    activity,
    'private TextView coreValue,modeValue,ipv6Value,overwriteValue,appScopeValue,tcpValue,udpValue,dnsValue,quicValue,shareValue,killValue,cidrValue,ifaceValue;',
    'private TextView coreValue,modeValue,ipv6Value,overwriteValue,appScopeValue,tcpValue,udpValue,dnsValue,quicValue,shareValue,killValue,cnIpValue,cidrValue,ifaceValue;',
)
replace_once(
    activity,
    'LinearLayout bypass=u.card(body);bypass.setPadding(u.dp(18),0,u.dp(18),0);\n        cidrValue=settingRow(bypass,"CIDR 绕过","",()->editSet("proxyBypassCidrs","CIDR 绕过","每行一个 IPv4/IPv6 CIDR。可用于自定义 CNIP 段；无自动 CNIP 数据源时不会假装已更新。"));u.separator(bypass);',
    'LinearLayout bypass=u.card(body);bypass.setPadding(u.dp(18),0,u.dp(18),0);\n        cnIpValue=settingRow(bypass,"中国 IP 自动直连","",()->toggle("proxyCnIpDirect",false));u.separator(bypass);\n        cidrValue=settingRow(bypass,"CIDR 绕过","",()->editSet("proxyBypassCidrs","CIDR 绕过","每行一个 IPv4/IPv6 CIDR。这里保留少量 Root 层自定义绕过；大规模中国 IP 段请使用上面的自动直连规则。"));u.separator(bypass);',
)
replace_once(
    activity,
    'plainAction(startup,"查看启动配置",this::showStartupConfig);u.separator(startup);\n        plainAction(startup,"运行预检",this::runPreflight);',
    'plainAction(startup,"查看启动配置",this::showStartupConfig);u.separator(startup);\n        plainAction(startup,"运行预检",this::runPreflight);u.separator(startup);\n        plainAction(startup,"查看 Root 诊断",this::showDiagnostics);u.separator(startup);\n        plainAction(startup,"清理残留并恢复网络",this::confirmRepairNetwork);',
)
replace_once(
    activity,
    'cidrValue.setText(stringSet("proxyBypassCidrs").isEmpty()?"未设置":stringSet("proxyBypassCidrs").size()+" 条");ifaceValue.setText(stringSet("proxyBypassInterfaces").isEmpty()?"未设置":stringSet("proxyBypassInterfaces").size()+" 个");renderConfigs();',
    'cnIpValue.setText(on(p.cnIpDirect));cidrValue.setText(stringSet("proxyBypassCidrs").isEmpty()?"未设置":stringSet("proxyBypassCidrs").size()+" 条");ifaceValue.setText(stringSet("proxyBypassInterfaces").isEmpty()?"未设置":stringSet("proxyBypassInterfaces").size()+" 个");renderConfigs();',
)
diag_methods = r'''    private void showDiagnostics(){
        task(()->{String text=root.diagnostics();if(TextUtils.isEmpty(text))text="未取得 Root 诊断信息";String finalText=text;ui.post(()->new AlertDialog.Builder(this).setTitle("Root 诊断").setMessage(finalText).setPositiveButton("关闭",null).show());return null;});
    }

    private void confirmRepairNetwork(){
        new AlertDialog.Builder(this).setTitle("清理残留并恢复网络").setMessage("这会停止当前 Root 代理，并清理辟尘创建的透明代理、Kill Switch、IPv6 临时状态和残留规则。不会删除订阅或节点配置。")
                .setPositiveButton("清理并停止",(d,w)->repairNetwork()).setNegativeButton("取消",null).show();
    }

    private void repairNetwork(){task(()->{root.stop();return "已停止 Root 代理并恢复辟尘网络规则";});}

'''
insert_before(activity, "    private void refresh(){", diag_methods)

# Host regression constructor + dedicated block-YAML CNIP merge test.
test = "tests/MihomoStartupConfigTest.java"
replace_once(
    test,
    'ProxyRuntimeProfile.DnsHijack.TPROXY,overwrite,true,true,false);}',
    'ProxyRuntimeProfile.DnsHijack.TPROXY,overwrite,true,true,false,false);}',
)
replace_once(
    test,
    'boolean denied=false;try{MihomoStartupConfig.generate(source,p(ProxyRuntimeProfile.Mode.EBPF,true));}catch(Exception expected){denied=true;}check(denied,"unsupported eBPF is not faked");',
    'String cnSource="mode: rule\\nproxies: []\\nproxy-groups: []\\nrules:\\n  - MATCH,DIRECT\\n";\n  ProxyRuntimeProfile cnProfile=new ProxyRuntimeProfile(ProxyRuntimeProfile.Core.MIHOMO,ProxyRuntimeProfile.Mode.TPROXY,ProxyRuntimeProfile.Ipv6.BYPASS,ProxyRuntimeProfile.AppScope.BLACKLIST,ProxyRuntimeProfile.DnsHijack.TPROXY,true,true,true,false,true);\n  MihomoStartupConfig.Result cn=MihomoStartupConfig.generate(cnSource,cnProfile);\n  check(cn.yaml.contains("bichen-cn-v4:")&&cn.yaml.contains("bichen-cn-v6:"),"CNIP providers injected");\n  check(cn.yaml.contains("RULE-SET,bichen-cn-v4,DIRECT,no-resolve")&&cn.yaml.indexOf("RULE-SET,bichen-cn-v4")<cn.yaml.indexOf("MATCH,DIRECT"),"CNIP direct rules precede source fallback");\n  check(cn.yaml.contains("./ruleset/bichen-cn-v4.txt")&&cn.yaml.contains("interval: 86400"),"CNIP cache path and refresh interval configured");\n  boolean denied=false;try{MihomoStartupConfig.generate(source,p(ProxyRuntimeProfile.Mode.EBPF,true));}catch(Exception expected){denied=true;}check(denied,"unsupported eBPF is not faked");',
)

# Any other host tests constructing the profile directly need the new final boolean.
for rel in ["tests/ProxyRuntimeProfileTest.java"]:
    path=ROOT/rel
    if path.exists():
        text=path.read_text(encoding="utf-8")
        text=text.replace(",false);",",false,false);")
        path.write_text(text,encoding="utf-8")

# Main feature CI metadata for future pushes.
workflow = ".github/workflows/compose-ui.yml"
replace_once(workflow, "versionName='0.4.0-test.27-preview'", "versionName='0.4.0-test.28-preview'")
replace_once(workflow, "name: Bichen-0.4.0-test.27-proxy-P1", "name: Bichen-0.4.0-test.28-proxy-P2")
replace_once(
    workflow,
    "grep -q 'proxySharedNetwork' android-app/app/src/main/java/io/github/xgl34222220/bichen/RootProxyPolicy.java",
    "grep -q 'proxySharedNetwork' android-app/app/src/main/java/io/github/xgl34222220/bichen/RootProxyPolicy.java\n          grep -q 'proxyCnIpDirect' android-app/app/src/main/java/io/github/xgl34222220/bichen/ProxyRuntimeProfile.java\n          grep -q 'bichen-cn-v4' android-app/app/src/main/java/io/github/xgl34222220/bichen/MihomoStartupConfig.java",
)
replace_once(
    workflow,
    "grep -q 'assets/proxy-root-v3.sh' compose-apk-files.txt",
    "grep -q 'assets/proxy-root-v3.sh' compose-apk-files.txt\n          grep -q 'assets/cnip/bichen-cn-v4.txt' compose-apk-files.txt\n          grep -q 'assets/cnip/bichen-cn-v6.txt' compose-apk-files.txt",
)

print("test.28 Proxy P2 source patch applied")
