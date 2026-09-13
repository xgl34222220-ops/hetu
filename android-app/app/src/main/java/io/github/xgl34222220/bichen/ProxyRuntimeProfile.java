package io.github.xgl34222220.bichen;

import android.content.SharedPreferences;
import java.util.*;

/**
 * One source of truth for the Root proxy control plane.
 * UI availability, startup config generation and Root rule installation must all
 * consult the same profile instead of inventing mode-specific behaviour separately.
 */
final class ProxyRuntimeProfile {
    enum Core {
        MIHOMO("mihomo", "Mihomo", set("yaml", "yml")),
        MIHOMO_SMART("mihomo-smart", "Mihomo Smart", set("yaml", "yml")),
        SING_BOX("sing-box", "Sing-Box", set("json", "jsonc", "yaml", "yml")),
        SING_BOX_REF1ND("sing-box-ref1nd", "Sing-Box reF1nd", set("json", "jsonc", "yaml", "yml")),
        XRAY("xray", "Xray", set("json")),
        V2FLY("v2fly", "V2Fly", set("json")),
        HYSTERIA("hysteria", "Hysteria", set("yaml", "yml"));

        final String id, label;
        final Set<String> extensions;
        Core(String id, String label, Set<String> extensions) {
            this.id=id; this.label=label; this.extensions=extensions;
        }
        static Core from(String value) {
            for (Core c:values()) if (c.id.equals(value)) return c;
            return MIHOMO;
        }
    }

    enum Mode {
        TUN("tun", "TUN"),
        TPROXY("tproxy", "TPROXY"),
        EBPF("ebpf", "eBPF"),
        REDIRECT("redirect", "Redirect"),
        MIXED("mixed", "Mixed"),
        ENHANCE("enhance", "Enhance");
        final String id,label;
        Mode(String id,String label){this.id=id;this.label=label;}
        static Mode from(String value){for(Mode m:values())if(m.id.equals(value))return m;return TPROXY;}
    }

    enum Ipv6 { ENABLE("enable"), BYPASS("bypass"), DISABLE("disable");
        final String id; Ipv6(String id){this.id=id;}
        static Ipv6 from(String value){for(Ipv6 v:values())if(v.id.equals(value))return v;return ENABLE;}
    }

    enum AppScope { BLACKLIST("blacklist"), WHITELIST("whitelist"), CORE("core");
        final String id; AppScope(String id){this.id=id;}
        static AppScope from(String value){for(AppScope s:values())if(s.id.equals(value))return s;return BLACKLIST;}
    }

    enum DnsHijack { OFF("off"), TPROXY("tproxy"), REDIRECT("redirect");
        final String id; DnsHijack(String id){this.id=id;}
        static DnsHijack from(String value){for(DnsHijack d:values())if(d.id.equals(value))return d;return TPROXY;}
    }

    static final class Capability {
        final boolean available,tcp,udp,dnsHijack,appFilter,sharedNetwork,cidrBypass,interfaceBypass,quicControl;
        final String reason;
        Capability(boolean available, boolean tcp, boolean udp, boolean dnsHijack, boolean appFilter,
                   boolean sharedNetwork, boolean cidrBypass, boolean interfaceBypass, boolean quicControl, String reason) {
            this.available=available;this.tcp=tcp;this.udp=udp;this.dnsHijack=dnsHijack;this.appFilter=appFilter;
            this.sharedNetwork=sharedNetwork;this.cidrBypass=cidrBypass;this.interfaceBypass=interfaceBypass;
            this.quicControl=quicControl;this.reason=reason==null?"":reason;
        }
    }

    final Core core;
    final Mode mode;
    final Ipv6 ipv6;
    final AppScope appScope;
    final DnsHijack dnsHijack;
    final boolean autoOverwrite,tcp,udp,quicBlocked;

    ProxyRuntimeProfile(Core core, Mode mode, Ipv6 ipv6, AppScope appScope, DnsHijack dnsHijack,
                        boolean autoOverwrite, boolean tcp, boolean udp, boolean quicBlocked) {
        this.core=core;this.mode=mode;this.ipv6=ipv6;this.appScope=appScope;this.dnsHijack=dnsHijack;
        this.autoOverwrite=autoOverwrite;this.tcp=tcp;this.udp=udp;this.quicBlocked=quicBlocked;
    }

    static ProxyRuntimeProfile load(SharedPreferences p) {
        return new ProxyRuntimeProfile(
                Core.from(p.getString("proxyBaseCore","mihomo")),
                Mode.from(p.getString("proxyBaseMode","tproxy")),
                Ipv6.from(p.getString("proxyBaseIpv6","enable")),
                AppScope.from(p.getString("proxyAppScope","blacklist")),
                DnsHijack.from(p.getString("proxyDnsHijack","tproxy")),
                p.getBoolean("proxyBaseAutoOverwrite",true),
                p.getBoolean("proxyTcp",true),
                p.getBoolean("proxyUdp",true),
                p.getBoolean("proxyQuicBlocked",false));
    }

    Capability capability() { return capability(core,mode); }

    static Capability capability(Core core, Mode mode) {
        boolean mihomo = core==Core.MIHOMO || core==Core.MIHOMO_SMART;
        boolean sing = core==Core.SING_BOX || core==Core.SING_BOX_REF1ND;
        switch (mode) {
            case TUN:
                return new Capability(mihomo||sing, true,true,false,false,false,false,true,false,
                        mihomo||sing?"":"TUN 自动覆写当前只支持 Mihomo / Sing-Box");
            case TPROXY:
                return new Capability(mihomo||sing||core==Core.XRAY||core==Core.V2FLY||core==Core.HYSTERIA,
                        true,true,true,true,true,true,true,true,"");
            case EBPF:
                boolean compatible = core==Core.MIHOMO_SMART || core==Core.SING_BOX_REF1ND;
                return new Capability(compatible,true,true,true,true,true,false,true,true,
                        compatible?"":"eBPF 需要兼容核心，普通官方核心不开放此模式");
            case REDIRECT:
                return new Capability(mihomo||sing||core==Core.XRAY||core==Core.V2FLY||core==Core.HYSTERIA,
                        true,false,true,true,true,true,true,false,"");
            case MIXED:
                return new Capability(mihomo||sing,true,true,true,true,true,true,true,true,
                        mihomo||sing?"":"Mixed 自动覆写当前只支持 Mihomo / Sing-Box");
            case ENHANCE:
                return new Capability(mihomo||sing||core==Core.XRAY||core==Core.V2FLY||core==Core.HYSTERIA,
                        true,true,true,true,true,true,true,true,"");
            default:
                return new Capability(false,false,false,false,false,false,false,false,false,"未知运行模式");
        }
    }

    boolean acceptsFilename(String name) {
        if (name==null) return false;
        int dot=name.lastIndexOf('.');
        return dot>=0 && core.extensions.contains(name.substring(dot+1).toLowerCase(Locale.ROOT));
    }

    String summary() { return core.label+" · "+mode.label; }

    private static Set<String> set(String... values) {
        return Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList(values)));
    }
}
