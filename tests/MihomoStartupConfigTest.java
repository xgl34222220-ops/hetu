package io.github.xgl34222220.bichen;

public final class MihomoStartupConfigTest {
 private static int checks;
 private static void check(boolean ok,String label){if(!ok)throw new AssertionError(label);checks++;System.out.println("PASS "+label);}
 private static ProxyRuntimeProfile p(ProxyRuntimeProfile.Mode mode,boolean overwrite){return new ProxyRuntimeProfile(ProxyRuntimeProfile.Core.MIHOMO,mode,ProxyRuntimeProfile.Ipv6.BYPASS,ProxyRuntimeProfile.AppScope.BLACKLIST,ProxyRuntimeProfile.DnsHijack.TPROXY,overwrite,true,true,false);}
 private static int count(String s,String part){int n=0,i=0;while((i=s.indexOf(part,i))>=0){n++;i+=part.length();}return n;}
 public static void main(String[] args)throws Exception{
  String source="mode: rule\ntproxy-port: 1234\nredir-port: 2345\ntun:\n  enable: true\n  stack: gvisor\nproxies: []\nproxy-groups: [{name: SELECT, type: select, proxies: [DIRECT]}]\nrules: ['MATCH,SELECT']\n";
  MihomoStartupConfig.Result t=MihomoStartupConfig.generate(source,p(ProxyRuntimeProfile.Mode.TPROXY,true));
  check(t.tproxyPort==9898&&t.redirectPort==0,"TPROXY ports generated");check(count(t.yaml,"tproxy-port:")==1&&t.yaml.contains("tproxy-port: 9898"),"old TPROXY port replaced once");check(t.yaml.contains("redir-port: 0"),"redirect disabled in TPROXY mode");check(!t.yaml.contains("stack: gvisor")&&t.yaml.contains("tun:\n  enable: false"),"source TUN block replaced in startup copy");check(source.contains("tproxy-port: 1234")&&source.contains("stack: gvisor"),"source text remains unchanged");
  MihomoStartupConfig.Result r=MihomoStartupConfig.generate(source,p(ProxyRuntimeProfile.Mode.REDIRECT,true));check(r.redirectPort==9797&&r.tproxyPort==0,"Redirect ports generated");check(r.yaml.contains("redir-port: 9797")&&r.yaml.contains("tproxy-port: 0"),"Redirect startup values written");
  MihomoStartupConfig.Result e=MihomoStartupConfig.generate(source,p(ProxyRuntimeProfile.Mode.ENHANCE,true));check(e.redirectPort==9797&&e.tproxyPort==9898,"Enhance has redirect and TPROXY ports");
  MihomoStartupConfig.Result raw=MihomoStartupConfig.generate(source,p(ProxyRuntimeProfile.Mode.TPROXY,false));check(raw.yaml.equals(source)&&raw.tproxyPort==1234&&raw.redirectPort==2345,"overwrite off preserves user-managed startup config");
  boolean denied=false;try{MihomoStartupConfig.generate(source,p(ProxyRuntimeProfile.Mode.EBPF,true));}catch(Exception expected){denied=true;}check(denied,"unsupported eBPF is not faked");
  System.out.println("MihomoStartupConfigTest passed: "+checks);
 }
}
