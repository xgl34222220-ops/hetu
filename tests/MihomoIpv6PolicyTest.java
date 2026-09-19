package io.github.xgl34222220.hetu;

import java.io.IOException;
import java.util.Collections;

/** Exercises the actual runtime generator rather than a duplicate policy model. */
public final class MihomoIpv6PolicyTest {
    private static int checks;
    private static void check(boolean value,String message){
        if(!value)throw new AssertionError(message);
        checks++;
    }
    private static ProxyRuntimeProfile profile(ProxyRuntimeProfile.Mode mode,ProxyRuntimeProfile.Ipv6 ipv6,
            ProxyRuntimeProfile.DnsHijack dns){
        return new ProxyRuntimeProfile(ProxyRuntimeProfile.Core.MIHOMO,mode,ipv6,
                ProxyRuntimeProfile.AppScope.CORE,dns,true,true,true,false,false,false);
    }
    private static String generate(String source,ProxyRuntimeProfile.Mode mode,ProxyRuntimeProfile.Ipv6 ipv6,
            ProxyRuntimeProfile.DnsHijack dns)throws Exception{
        return MihomoStartupConfig.generate(source,profile(mode,ipv6,dns),"ipv6-test-secret",29090,
                ProxyRuntimeProfile.AppScope.CORE,Collections.emptySet(),"wlan0").yaml;
    }
    private static int count(String value,String needle){return value.split(java.util.regex.Pattern.quote(needle),-1).length-1;}
    public static void main(String[] args)throws Exception{
        String routing="rules:\n  - DOMAIN,stun.example.test,REJECT\n  - DST-PORT,853,REJECT\n  - DOMAIN-SUFFIX,weixin.qq.com,DIRECT\n  - MATCH,DIRECT\n";
        String source="mode: rule\nipv6: true\nproxies: []\ndns:\n  enable: true\n  ipv6: true\n"
                +"  respect-rules: true\n  enhanced-mode: fake-ip\n  nameserver: [https://dns.example.test/dns-query]\n"
                +"  nameserver-policy:\n    '+.example.test': [https://private.example.test/dns-query]\n"+routing;
        for(ProxyRuntimeProfile.Mode mode:new ProxyRuntimeProfile.Mode[]{ProxyRuntimeProfile.Mode.TPROXY,
                ProxyRuntimeProfile.Mode.REDIRECT,ProxyRuntimeProfile.Mode.ENHANCE,
                ProxyRuntimeProfile.Mode.TUN,ProxyRuntimeProfile.Mode.EBPF}){
            for(ProxyRuntimeProfile.Ipv6 policy:new ProxyRuntimeProfile.Ipv6[]{ProxyRuntimeProfile.Ipv6.DISABLE,ProxyRuntimeProfile.Ipv6.STRICT}){
                String yaml=generate(source,mode,policy,ProxyRuntimeProfile.DnsHijack.TPROXY);
                check(yaml.contains("\nipv6: false\n"),mode+" "+policy+" disables core IPv6");
                check(yaml.contains("\n  ipv6: false\n"),"AAAA answers follow selected IPv4 policy");
                check(!yaml.contains("ipv6: true"),"no conflicting inherited IPv6 setting");
                check(count(yaml,"ipv6: false")==2,"exactly one setting per core and DNS");
                check(yaml.contains(routing),"DNS, WebRTC and message routing order is preserved");
                check(yaml.contains("respect-rules: true")&&yaml.contains("enhanced-mode: fake-ip")
                        &&yaml.contains("'+.example.test': [https://private.example.test/dns-query]"),"DNS privacy policy is retained");
            }
        }
        for(ProxyRuntimeProfile.Ipv6 policy:new ProxyRuntimeProfile.Ipv6[]{ProxyRuntimeProfile.Ipv6.ENABLE,ProxyRuntimeProfile.Ipv6.BYPASS}){
            String yaml=generate(source,ProxyRuntimeProfile.Mode.TPROXY,policy,ProxyRuntimeProfile.DnsHijack.TPROXY);
            check(count(yaml,"ipv6: true")==2&&!yaml.contains("ipv6: false"),"enabled/bypass honors source family preference");
        }
        check(source.contains("\nipv6: true\n")&&source.contains("\n  ipv6: true\n"),"private overrides never edit original source");
        String withoutDns=generate("mode: rule\nipv6: true\nproxies: []\n"+routing,ProxyRuntimeProfile.Mode.TUN,
                ProxyRuntimeProfile.Ipv6.DISABLE,ProxyRuntimeProfile.DnsHijack.OFF);
        check(withoutDns.contains("dns:\n  ipv6: false\n"),"disabled DNS hijack still disables core DNS IPv6 resolution");
        check(!withoutDns.contains("dns-hijack:"),"does not silently enable DNS hijack");
        String indented=source.replace("  ipv6: true","  \"ipv6\": true").replace("\nipv6: true","\n'ipv6': true");
        String quoted=generate(indented,ProxyRuntimeProfile.Mode.TPROXY,ProxyRuntimeProfile.Ipv6.DISABLE,ProxyRuntimeProfile.DnsHijack.OFF);
        check(count(quoted,"ipv6: false")==2&&!quoted.contains("ipv6': true")&&!quoted.contains("ipv6\": true"),"quoted scalar keys cannot retain conflicting IPv6");
        String duplicate=generate(source.replace("  ipv6: true","  ipv6: true\n  ipv6: true"),ProxyRuntimeProfile.Mode.TPROXY,
                ProxyRuntimeProfile.Ipv6.DISABLE,ProxyRuntimeProfile.DnsHijack.OFF);
        check(count(duplicate,"ipv6: false")==2,"removes all old same-level DNS IPv6 values");
        try{
            generate("mode: rule\ndns: {enable: true, ipv6: true}\n"+routing,ProxyRuntimeProfile.Mode.TPROXY,
                    ProxyRuntimeProfile.Ipv6.DISABLE,ProxyRuntimeProfile.DnsHijack.OFF);
            throw new AssertionError("unsupported inline DNS must not silently bypass the selected IPv6 policy");
        }catch(IOException expected){checks++;}
        System.out.println("MihomoIpv6PolicyTest passed: "+checks);
    }
}
