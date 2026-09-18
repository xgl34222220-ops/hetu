package io.github.xgl34222220.hetu;

public final class MihomoStartupConfigTest {
 private static int checks;
 private static void check(boolean ok,String label){if(!ok)throw new AssertionError(label);checks++;System.out.println("PASS "+label);}
 private static ProxyRuntimeProfile p(ProxyRuntimeProfile.Mode mode,boolean overwrite){return new ProxyRuntimeProfile(ProxyRuntimeProfile.Core.MIHOMO,mode,ProxyRuntimeProfile.Ipv6.BYPASS,ProxyRuntimeProfile.AppScope.BLACKLIST,ProxyRuntimeProfile.DnsHijack.TPROXY,overwrite,true,true,false,false);}
 private static int count(String s,String part){int n=0,i=0;while((i=s.indexOf(part,i))>=0){n++;i+=part.length();}return n;}
 public static void main(String[] args)throws Exception{
  String source="mode: rule\nglobal-client-fingerprint: chrome\ntproxy-port: 1234\nredir-port: 2345\ntun:\n  enable: true\n  stack: gvisor\nlisteners:\n  - name: old-tproxy\n    type: tproxy\n    port: 1234\n  - name: old-ebpf\n    type: ebpf\n    port: 2234\nproxies: []\nproxy-groups: [{name: SELECT, type: select, proxies: [DIRECT]}]\nrules: ['MATCH,SELECT']\n";
  MihomoStartupConfig.Result t=MihomoStartupConfig.generate(source,p(ProxyRuntimeProfile.Mode.TPROXY,true));
  check(t.tproxyPort==9898&&t.redirectPort==0,"TPROXY ports generated");check(count(t.yaml,"tproxy-port:")==1&&t.yaml.contains("tproxy-port: 9898"),"old TPROXY port replaced once");check(t.yaml.contains("redir-port: 0"),"redirect disabled in TPROXY mode");check(!t.yaml.contains("stack: gvisor")&&t.yaml.contains("tun:\n  enable: false"),"source TUN block replaced in startup copy");
  check(!t.yaml.contains("listeners:")&&!t.yaml.contains("type: ebpf")&&!t.yaml.contains("old-tproxy"),"source listener block removed from mode-managed startup copy");
  check(!t.yaml.contains("global-client-fingerprint:"),"removed Mihomo global fingerprint key stripped from startup copy");
  check(source.contains("tproxy-port: 1234")&&source.contains("stack: gvisor")&&source.contains("type: ebpf")&&source.contains("global-client-fingerprint: chrome"),"source text remains unchanged");
  MihomoStartupConfig.Result r=MihomoStartupConfig.generate(source,p(ProxyRuntimeProfile.Mode.REDIRECT,true));check(r.redirectPort==9797&&r.tproxyPort==9898,"Redirect plus TPROXY DNS ports generated");check(r.yaml.contains("redir-port: 9797")&&r.yaml.contains("tproxy-port: 9898"),"Redirect startup keeps TPROXY listener for TPROXY DNS");check(!r.yaml.contains("listeners:"),"Redirect startup also excludes source transparent listeners");
  MihomoStartupConfig.Result e=MihomoStartupConfig.generate(source,p(ProxyRuntimeProfile.Mode.ENHANCE,true));check(e.redirectPort==9797&&e.tproxyPort==9898,"Enhance has redirect and TPROXY ports");check(!e.yaml.contains("type: ebpf"),"Enhance startup excludes eBPF listener");
  MihomoStartupConfig.Result raw=MihomoStartupConfig.generate(source,p(ProxyRuntimeProfile.Mode.TPROXY,false));
  check(raw.tproxyPort==1234&&raw.redirectPort==0,"overwrite off preserves selected TPROXY port but keeps mode authoritative");
  check(raw.yaml.contains("tproxy-port: 1234")&&raw.yaml.contains("redir-port: 0"),"overwrite off still writes a coherent TPROXY runtime copy");
  check(!raw.yaml.contains("listeners:")&&!raw.yaml.contains("type: ebpf")&&!raw.yaml.contains("global-client-fingerprint:")&&!raw.yaml.contains("stack: gvisor"),"overwrite off still isolates conflicting runtime features");
  check(source.contains("type: ebpf")&&source.contains("global-client-fingerprint: chrome"),"overwrite off still leaves source file untouched");
  String flow="mode: rule\nlisteners: [{name: e, type: ebpf, port: 9898}]\nrules: ['MATCH,DIRECT']\n";
  MihomoStartupConfig.Result flowResult=MihomoStartupConfig.generate(flow,p(ProxyRuntimeProfile.Mode.TPROXY,true));check(!flowResult.yaml.contains("listeners:")&&!flowResult.yaml.contains("type: ebpf"),"flow-style listeners removed from startup copy");
  String indentless="mode: rule\nlisteners:\n- name: e\n  type: ebpf\n  port: 9898\nrules: ['MATCH,DIRECT']\n";
  MihomoStartupConfig.Result indentlessResult=MihomoStartupConfig.generate(indentless,p(ProxyRuntimeProfile.Mode.TPROXY,true));check(!indentlessResult.yaml.contains("listeners:")&&!indentlessResult.yaml.contains("type: ebpf")&&indentlessResult.yaml.contains("rules:"),"indentless listener sequence removed without eating following rules");
  String cnSource="mode: rule\nproxies: []\nproxy-groups: []\nrules:\n  - MATCH,DIRECT\n";
  ProxyRuntimeProfile cnProfile=new ProxyRuntimeProfile(ProxyRuntimeProfile.Core.MIHOMO,ProxyRuntimeProfile.Mode.TPROXY,ProxyRuntimeProfile.Ipv6.BYPASS,ProxyRuntimeProfile.AppScope.BLACKLIST,ProxyRuntimeProfile.DnsHijack.TPROXY,true,true,true,false,true);
  MihomoStartupConfig.Result cn=MihomoStartupConfig.generate(cnSource,cnProfile);
  check(cn.yaml.contains("hetu-cn-v4:")&&cn.yaml.contains("hetu-cn-v6:"),"CNIP providers injected");
  check(cn.yaml.contains("RULE-SET,hetu-cn-v4,DIRECT,no-resolve")&&cn.yaml.indexOf("RULE-SET,hetu-cn-v4")<cn.yaml.indexOf("MATCH,DIRECT"),"CNIP direct rules precede source fallback");
  check(cn.yaml.contains("./ruleset/hetu-cn-v4.txt")&&cn.yaml.contains("interval: 86400"),"CNIP cache path and refresh interval configured");
  ProxyRuntimeProfile adProfile=new ProxyRuntimeProfile(ProxyRuntimeProfile.Core.MIHOMO,ProxyRuntimeProfile.Mode.TPROXY,ProxyRuntimeProfile.Ipv6.BYPASS,ProxyRuntimeProfile.AppScope.BLACKLIST,ProxyRuntimeProfile.DnsHijack.TPROXY,true,true,true,false,false,true);
  MihomoStartupConfig.Result ad=MihomoStartupConfig.generate(cnSource,adProfile);
  check(ad.yaml.contains("hetu-adblock:")&&ad.yaml.contains("type: file")&&ad.yaml.contains("behavior: domain")&&ad.yaml.contains("format: text"),"adblock local domain provider injected");
  check(ad.yaml.contains("path: ./ruleset/hetu-adblock.txt"),"adblock provider stays inside Mihomo HomeDir");
  check(ad.yaml.contains("RULE-SET,hetu-adblock,REJECT")&&ad.yaml.indexOf("RULE-SET,hetu-adblock")<ad.yaml.indexOf("MATCH,DIRECT"),"adblock REJECT precedes source routing");
  ProxyRuntimeProfile bothProfile=new ProxyRuntimeProfile(ProxyRuntimeProfile.Core.MIHOMO,ProxyRuntimeProfile.Mode.TPROXY,ProxyRuntimeProfile.Ipv6.BYPASS,ProxyRuntimeProfile.AppScope.BLACKLIST,ProxyRuntimeProfile.DnsHijack.TPROXY,true,true,true,false,true,true);
  MihomoStartupConfig.Result both=MihomoStartupConfig.generate(cnSource,bothProfile);
  check(both.yaml.indexOf("RULE-SET,hetu-adblock")<both.yaml.indexOf("RULE-SET,hetu-cn-v4")&&both.yaml.indexOf("RULE-SET,hetu-cn-v4")<both.yaml.indexOf("MATCH,DIRECT"),"adblock executes before CNIP and source fallback");
  boolean denied=false;try{MihomoStartupConfig.generate(source,p(ProxyRuntimeProfile.Mode.EBPF,true));}catch(Exception expected){denied=true;}check(denied,"unsupported eBPF is not faked");
  System.out.println("MihomoStartupConfigTest passed: "+checks);
 }
}
