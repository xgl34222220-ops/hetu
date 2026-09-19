package io.github.xgl34222220.hetu;

import java.util.*;

public final class ProxyRuntimeSettingsTest {
    private static int checks;
    private static void check(boolean value,String label){if(!value)throw new AssertionError(label);checks++;}
    private static ProxyRuntimeProfile profile(ProxyRuntimeProfile.Ipv6 ipv6){
        return new ProxyRuntimeProfile(ProxyRuntimeProfile.Core.MIHOMO,ProxyRuntimeProfile.Mode.TPROXY,ipv6,
                ProxyRuntimeProfile.AppScope.BLACKLIST,ProxyRuntimeProfile.DnsHijack.TPROXY,true,true,true,false,false,true);
    }
    public static void main(String[] args){
        Map<String,Object> values=new HashMap<>();
        String applied=ProxyRuntimeSettings.signature(profile(ProxyRuntimeProfile.Ipv6.ENABLE),values);
        check(!ProxyRuntimeSettings.pending(true,applied,applied),"unchanged applied settings are current");
        String disabled=ProxyRuntimeSettings.signature(profile(ProxyRuntimeProfile.Ipv6.DISABLE),values);
        check(ProxyRuntimeSettings.pending(true,disabled,applied),"saving disable is not proof of live disable");
        check(!ProxyRuntimeSettings.pending(false,disabled,applied),"stopped runtime applies settings next start");
        check(ProxyRuntimeSettings.pending(true,disabled,""),"old runtime with no applied record is not silently current");
        values.put("enableBlur",false);values.put("proxyUiLastCpu",77f);
        check(applied.equals(ProxyRuntimeSettings.signature(profile(ProxyRuntimeProfile.Ipv6.ENABLE),values)),"UI and telemetry changes never require restart");
        values.put("proxyKillSwitch",true);
        check(!applied.equals(ProxyRuntimeSettings.signature(profile(ProxyRuntimeProfile.Ipv6.ENABLE),values)),"kill switch needs network transaction");
        values.remove("proxyKillSwitch");values.put("proxyAppPackages",new LinkedHashSet<>(Arrays.asList("example.a","example.b")));
        String selected=ProxyRuntimeSettings.signature(profile(ProxyRuntimeProfile.Ipv6.ENABLE),values);
        values.put("proxyAppPackages",new LinkedHashSet<>(Arrays.asList("example.b","example.a")));
        check(selected.equals(ProxyRuntimeSettings.signature(profile(ProxyRuntimeProfile.Ipv6.ENABLE),values)),"set ordering cannot manufacture pending settings");
        values.put("proxyAppPackages",Collections.singleton("example.b"));
        check(!selected.equals(ProxyRuntimeSettings.signature(profile(ProxyRuntimeProfile.Ipv6.ENABLE),values)),"changed app selection stays pending");
        check(ProxyRuntimeSettings.ipv6Label("disable").contains("本机"),"label distinguishes local policy from remote node egress");
        check(ProxyRuntimeSettings.ipv6Label("").contains("确认"),"unknown is not advertised as enabled or disabled");
        System.out.println("ProxyRuntimeSettingsTest passed: "+checks);
    }
}
