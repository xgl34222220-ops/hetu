package io.github.xgl34222220.bichen;

public final class MihomoStartupConfigTest {
 private static int checks;
 private static void check(boolean ok,String label){if(!ok)throw new AssertionError(label);checks++;System.out.println("PASS "+label);}
 private static ProxyRuntimeProfile p(ProxyRuntimeProfile.Mode mode,boolean overwrite){return new ProxyRuntimeProfile(ProxyRuntimeProfile.Core.MIHOMO,mode,ProxyRuntimeProfile.Ipv6.BYPASS,ProxyRuntimeProfile.AppScope.BLACKLIST,ProxyRuntimeProfile.DnsHijack.TPROXY,overwrite,true,true,false);}
 private static int count(String s,String part){int n=0,i=0;while((i=s.indexOf(part,i))>=0){n++;i+=part.length();}return n;}
 public static void main(String[] args)throws Exception{
  String source="mode: rule\nglobal-client-fingerprint: chrome\ntproxy-port: 1234\nredir-port: 2345\ntun:\n  enable: true\n  stack: gvisor\nlisteners:\n  - name: old-tproxy\n    type: tproxy\n    port: 1234\n  - name: old-ebpf\n    type: ebpf\n    port: 2234\nproxies: []\nproxy-groups: [{name: SELECT, type: select, proxies: [DIRECT]}]\nrules: ['MATCH,SELECT']\n";
  MihomoStartupConfig.Result t=MihomoStartupConfig.generate(source,p(ProxyRuntimeProfile.Mode.TPROXY,true));
  check(t.tproxyPort==9898&&t.redirectPort==0,"TPROXY ports generated");check(count(t.yaml,"tproxy-port:")==1&&t.yaml.contains("tproxy-port: 9898"),"old TPROXY port replaced once");check(t.yaml.contains("redir-port: 0"),"redirect disabled in TPROXY mode");check(!t.yaml.contains("stack: gvisor")&&t.yaml.contains("tun:\n  enable: false"),"source TUN block replaced in startup copy");
  check(!t.yaml.contains("listeners:")&&!t.yaml.contains("type: ebpf")&&!t.yaml.contains("old-tproxy"),"source listener block removed from mode-managed startup copy");
  check(!t.yaml.contains("global-client-fingerprint:"),"removed Mihomo global fingerprint key stripped from startup copy");
  check(source.contains("tproxy-port: 1234")&&source.contains("stack: gvisor")&&source.contains("type: ebpf")&&source.contains("global-client-fingerprint: chrome"),"source text remains unchanged");
  MihomoStartupConfig.Result r=MihomoStartupConfig.generate(source,p(ProxyRuntimeProfile.Mode.REDIRECT,true));check(r.redirectPort==9797&&r.tproxyPort==0,"Redirect ports generated");check(r.yaml.contains("redir-port: 9797")&&r.yaml.contains("tproxy-port: 0"),"Redirect startup values written");check(!r.yaml.contains("listeners:"),"Redirect startup also excludes source transparent listeners");
  MihomoStartupConfig.Result e=MihomoStartupConfig.generate(source,p(ProxyRuntimeProfile.Mode.ENHANCE,true));check(e.redirectPort==9797&&e.tproxyPort==9898,"Enhance has redirect and TPROXY ports");check(!e.yaml.contains("type: ebpf"),"Enhance startup excludes eBPF listener");
  MihomoStartupConfig.Result raw=MihomoStartupConfig.generate(source,p(ProxyRuntimeProfile.Mode.TPROXY,false));check(raw.yaml.equals(source)&&raw.tproxyPort==1234&&raw.redirectPort==2345,"overwrite off preserves user-managed startup config exactly");
  String flow="mode: rule\nlisteners: [{name: e, type: ebpf, port: 9898}]\nrules: ['MATCH,DIRECT']\n";
  MihomoStartupConfig.Result flowResult=MihomoStartupConfig.generate(flow,p(ProxyRuntimeProfile.Mode.TPROXY,true));check(!flowResult.yaml.contains("listeners:")&&!flowResult.yaml.contains("type: ebpf"),"flow-style listeners removed from startup copy");
  String indentless="mode: rule\nlisteners:\n- name: e\n  type: ebpf\n  port: 9898\nrules: ['MATCH,DIRECT']\n";
  MihomoStartupConfig.Result indentlessResult=MihomoStartupConfig.generate(indentless,p(ProxyRuntimeProfile.Mode.TPROXY,true));check(!indentlessResult.yaml.contains("listeners:")&&!indentlessResult.yaml.contains("type: ebpf")&&indentlessResult.yaml.contains("rules:"),"indentless listener sequence removed without eating following rules");
  boolean denied=false;try{MihomoStartupConfig.generate(source,p(ProxyRuntimeProfile.Mode.EBPF,true));}catch(Exception expected){denied=true;}check(denied,"unsupported eBPF is not faked");
  System.out.println("MihomoStartupConfigTest passed: "+checks);
 }
}
