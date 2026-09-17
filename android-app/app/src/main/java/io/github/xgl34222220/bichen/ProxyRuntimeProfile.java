package io.github.xgl34222220.bichen;

import android.content.SharedPreferences;
import java.util.*;

/** One source of truth for core/mode availability and Root proxy settings. */
final class ProxyRuntimeProfile {
    enum Core {
        MIHOMO("mihomo", "Mihomo", set("yaml", "yml")),
        MIHOMO_SMART("mihomo-smart", "Mihomo Smart", set("yaml", "yml")),
        SING_BOX("sing-box", "Sing-Box", set("json", "jsonc", "yaml", "yml")),
        SING_BOX_REF1ND("sing-box-ref1nd", "Sing-Box reF1nd", set("json", "jsonc", "yaml", "yml")),
        XRAY("xray", "Xray", set("json")),
        V2FLY("v2fly", "V2Fly", set("json")),
        HYSTERIA("hysteria", "Hysteria", set("yaml", "yml"));
        final String id,label;final Set<String> extensions;
        Core(String id,String label,Set<String> extensions){this.id=id;this.label=label;this.extensions=extensions;}
        static Core from(String value){for(Core c:values())if(c.id.equals(value))return c;return MIHOMO;}
    }
    enum Mode {
        TUN("tun","TUN"),TPROXY("tproxy","TPROXY"),EBPF("ebpf","eBPF"),REDIRECT("redirect","Redirect"),MIXED("mixed","Mixed"),ENHANCE("enhance","Enhance");
        final String id,label;Mode(String id,String label){this.id=id;this.label=label;}
        static Mode from(String value){for(Mode m:values())if(m.id.equals(value))return m;return TPROXY;}
    }
    enum Ipv6 {
        ENABLE("enable"),
        BYPASS("bypass"),
        STRICT("strict"),
        DISABLE("disable");
        final String id;
        Ipv6(String id){this.id=id;}
        static Ipv6 from(String v){for(Ipv6 x:values())if(x.id.equals(v))return x;return ENABLE;}
    }
    enum AppScope { BLACKLIST("blacklist"),WHITELIST("whitelist"),CORE("core");final String id;AppScope(String id){this.id=id;}static AppScope from(String v){for(AppScope x:values())if(x.id.equals(v))return x;return BLACKLIST;} }
    enum DnsHijack { OFF("off"),TPROXY("tproxy"),REDIRECT("redirect");final String id;DnsHijack(String id){this.id=id;}static DnsHijack from(String v){for(DnsHijack x:values())if(x.id.equals(v))return x;return TPROXY;} }

    static final class Capability {
        final boolean available,tcp,udp,dnsHijack,appFilter,sharedNetwork,cidrBypass,interfaceBypass,quicControl;
        final String reason;
        Capability(boolean available,boolean tcp,boolean udp,boolean dnsHijack,boolean appFilter,boolean sharedNetwork,boolean cidrBypass,boolean interfaceBypass,boolean quicControl,String reason){this.available=available;this.tcp=tcp;this.udp=udp;this.dnsHijack=dnsHijack;this.appFilter=appFilter;this.sharedNetwork=sharedNetwork;this.cidrBypass=cidrBypass;this.interfaceBypass=interfaceBypass;this.quicControl=quicControl;this.reason=reason==null?"":reason;}
    }

    final Core core;final Mode mode;final Ipv6 ipv6;final AppScope appScope;final DnsHijack dnsHijack;final boolean autoOverwrite,tcp,udp,quicBlocked,cnIpDirect;
    ProxyRuntimeProfile(Core core,Mode mode,Ipv6 ipv6,AppScope appScope,DnsHijack dnsHijack,boolean autoOverwrite,boolean tcp,boolean udp,boolean quicBlocked,boolean cnIpDirect){this.core=core;this.mode=mode;this.ipv6=ipv6;this.appScope=appScope;this.dnsHijack=dnsHijack;this.autoOverwrite=autoOverwrite;this.tcp=tcp;this.udp=udp;this.quicBlocked=quicBlocked;this.cnIpDirect=cnIpDirect;}

    static ProxyRuntimeProfile load(SharedPreferences p){return new ProxyRuntimeProfile(Core.from(p.getString("proxyBaseCore","mihomo")),Mode.from(p.getString("proxyBaseMode","tproxy")),Ipv6.from(p.getString("proxyBaseIpv6","enable")),AppScope.from(p.getString("proxyAppScope","blacklist")),DnsHijack.from(p.getString("proxyDnsHijack","tproxy")),p.getBoolean("proxyBaseAutoOverwrite",true),p.getBoolean("proxyTcp",true),p.getBoolean("proxyUdp",true),p.getBoolean("proxyQuicBlocked",false),p.getBoolean("proxyCnIpDirect",false));}

    Capability capability(){return capability(core,mode);}
    static Capability capability(Core core,Mode mode){
        boolean mihomo=core==Core.MIHOMO||core==Core.MIHOMO_SMART;
        if(!mihomo)return new Capability(false,false,false,false,false,false,false,false,false,core.label+" 已进入核心/配置模型，但运行后端正在接入，当前不会假报可用");
        switch(mode){
            case TPROXY:return new Capability(true,true,true,true,true,true,true,true,true,"");
            case REDIRECT:return new Capability(true,true,false,true,true,true,true,true,true,"");
            case ENHANCE:return new Capability(true,true,true,true,true,true,true,true,true,"");
            case TUN:return new Capability(false,true,true,true,false,false,false,true,true,"Root TUN 正在并入统一运行时；旧 VpnService 仍保留，但这里不再假报为 Root 模式可用");
            case EBPF:return new Capability(false,false,false,false,false,false,false,false,false,"eBPF 必须完成 verifier/attach/map 能力探测并使用兼容核心；后端接通前不开放");
            case MIXED:return new Capability(false,false,false,false,false,false,false,false,false,"Mixed 的 Root TUN + Redirect 事务后端正在接入，当前不开放");
            default:return new Capability(false,false,false,false,false,false,false,false,false,"未知运行模式");
        }
    }

    boolean acceptsFilename(String name){if(name==null)return false;int dot=name.lastIndexOf('.');return dot>=0&&core.extensions.contains(name.substring(dot+1).toLowerCase(Locale.ROOT));}
    String summary(){return core.label+" · "+mode.label;}
    private static Set<String> set(String...values){return Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList(values)));}
}
