package io.github.xgl34222220.hetu;

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

    final Core core;final Mode mode;final Ipv6 ipv6;final AppScope appScope;final DnsHijack dnsHijack;final boolean autoOverwrite,tcp,udp,quicBlocked,cnIpDirect,adblockChain;
    ProxyRuntimeProfile(Core core,Mode mode,Ipv6 ipv6,AppScope appScope,DnsHijack dnsHijack,boolean autoOverwrite,boolean tcp,boolean udp,boolean quicBlocked,boolean cnIpDirect){this(core,mode,ipv6,appScope,dnsHijack,autoOverwrite,tcp,udp,quicBlocked,cnIpDirect,false);}
    ProxyRuntimeProfile(Core core,Mode mode,Ipv6 ipv6,AppScope appScope,DnsHijack dnsHijack,boolean autoOverwrite,boolean tcp,boolean udp,boolean quicBlocked,boolean cnIpDirect,boolean adblockChain){this.core=core;this.mode=mode;this.ipv6=ipv6;this.appScope=appScope;this.dnsHijack=dnsHijack;this.autoOverwrite=autoOverwrite;this.tcp=tcp;this.udp=udp;this.quicBlocked=quicBlocked;this.cnIpDirect=cnIpDirect;this.adblockChain=adblockChain;}

    // The core CN RULE-SET stays on whenever the kernel CNIP bypass is on: it is the fallback when
    // ipset is unavailable (status cnip=degraded) and covers fake-IP answers the kernel cannot match.
    static ProxyRuntimeProfile load(SharedPreferences p){return new ProxyRuntimeProfile(Core.from(p.getString("proxyBaseCore","mihomo")),Mode.from(p.getString("proxyBaseMode","tproxy")),Ipv6.from(p.getString("proxyBaseIpv6","enable")),AppScope.from(p.getString("proxyAppScope","blacklist")),DnsHijack.from(p.getString("proxyDnsHijack","tproxy")),p.getBoolean("proxyBaseAutoOverwrite",true),p.getBoolean("proxyTcp",true),p.getBoolean("proxyUdp",true),p.getBoolean("proxyQuicBlocked",false),p.getBoolean("proxyCnIpDirect",false)||p.getBoolean("proxyCnIpKernelV4",false)||p.getBoolean("proxyCnIpKernelV6",false),p.getBoolean("proxyAdblockChain",true));}

    Capability capability(){return capability(core,mode);}
    static Capability capability(Core core,Mode mode){
        boolean mihomo=core==Core.MIHOMO||core==Core.MIHOMO_SMART;
        if(!mihomo){
            // sing-box / Xray / V2Fly / Hysteria run behind the same Root netfilter capture:
            // a TPROXY (TCP+UDP), Redirect (TCP) or Enhance (TCP Redirect + UDP TPROXY) inbound plus Hetu's DNS
            // port. App scope, shared network, CIDR/interface bypass and QUIC control are netfilter features,
            // core-independent. TUN is the core's own device (hetu0): sing-box has one (the same sing-tun Mihomo
            // uses), Xray / V2Fly / Hysteria do not. eBPF redirects into Mihomo's TUN and stays Mihomo-only.
            boolean singBox=core==Core.SING_BOX||core==Core.SING_BOX_REF1ND;
            switch(mode){
                case TPROXY:return new Capability(true,true,true,true,true,true,true,true,true,"");
                case REDIRECT:return new Capability(true,true,false,true,true,true,true,true,true,"");
                case ENHANCE:return new Capability(true,true,true,true,true,true,true,true,true,"");
                case TUN:
                    if(singBox)return new Capability(true,true,true,true,true,false,true,true,true,"");
                    return new Capability(false,false,false,false,false,false,false,false,false,core.label+" 没有自己的 TUN 设备，可用 TPROXY、Redirect 与 Enhance；TUN 仅 Mihomo / sing-box 可用");
                default:return new Capability(false,false,false,false,false,false,false,false,false,core.label+" 在河图 Root 运行链中支持 TPROXY、Redirect 与 Enhance；"+mode.label+" 仅 Mihomo 可用");
            }
        }
        switch(mode){
            case TPROXY:return new Capability(true,true,true,true,true,true,true,true,true,"");
            case REDIRECT:return new Capability(true,true,false,true,true,true,true,true,true,"");
            case ENHANCE:return new Capability(true,true,true,true,true,true,true,true,true,"");
            case TUN:return new Capability(true,true,true,true,true,false,true,true,true,"");
            case EBPF:return new Capability(true,true,true,true,false,false,false,false,true,"");
            case MIXED:return new Capability(false,false,false,false,false,false,false,false,false,"Mixed 的 Root TUN + Redirect 事务后端正在接入，当前不开放");
            default:return new Capability(false,false,false,false,false,false,false,false,false,"未知运行模式");
        }
    }

    /**
     * The mode {@code core} runs in when the saved one is not available for it: the saved mode when it is, else
     * TPROXY (every core supports it). Picking or starting a core never turns it back into Mihomo.
     */
    static Mode runnableMode(Core core,Mode mode){return capability(core,mode).available?mode:Mode.TPROXY;}

    /** The Mihomo-only mode (eBPF, or TUN for a core without one) a pick of another core replaced with TPROXY; restored on a pick of Mihomo. */
    static final String MIHOMO_MODE_KEY="proxyMihomoModeBeforeCore";

    /**
     * The mode to save with a pick of {@code to} while {@code current} is saved: {@link #runnableMode}, remembering a
     * Mihomo-only mode it replaces, and bringing that mode back when Mihomo is picked again while TPROXY is still the
     * saved mode. Writes only the memory key.
     */
    static Mode modeForPick(SharedPreferences p,Core to,Mode current){
        boolean mihomo=to==Core.MIHOMO||to==Core.MIHOMO_SMART;
        String saved=p.getString(MIHOMO_MODE_KEY,null);
        if(mihomo){
            if(saved==null)return runnableMode(to,current);
            p.edit().remove(MIHOMO_MODE_KEY).apply();
            Mode back=Mode.from(saved);
            return current==Mode.TPROXY&&capability(to,back).available?back:runnableMode(to,current);
        }
        Mode mode=runnableMode(to,current);
        if(mode!=current)p.edit().putString(MIHOMO_MODE_KEY,current.id).apply();
        return mode;
    }

    boolean acceptsFilename(String name){if(name==null)return false;int dot=name.lastIndexOf('.');return dot>=0&&core.extensions.contains(name.substring(dot+1).toLowerCase(Locale.ROOT));}
    String summary(){return core.label+" · "+mode.label;}
    private static Set<String> set(String...values){return Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList(values)));}
}
