package io.github.xgl34222220.bichen;

public final class ProxyRuntimeProfileTest {
    private static int checks;
    private static void check(boolean ok,String label){if(!ok)throw new AssertionError(label);checks++;System.out.println("PASS "+label);}
    public static void main(String[] args){
        ProxyRuntimeProfile.Capability tproxy=ProxyRuntimeProfile.capability(ProxyRuntimeProfile.Core.MIHOMO,ProxyRuntimeProfile.Mode.TPROXY);
        check(tproxy.available&&tproxy.tcp&&tproxy.udp,"Mihomo TPROXY handles TCP and UDP");
        check(tproxy.dnsHijack&&tproxy.appFilter&&tproxy.sharedNetwork,"Mihomo TPROXY exposes network controls");
        ProxyRuntimeProfile.Capability redirect=ProxyRuntimeProfile.capability(ProxyRuntimeProfile.Core.MIHOMO,ProxyRuntimeProfile.Mode.REDIRECT);
        check(redirect.available&&redirect.tcp&&!redirect.udp,"Redirect is TCP transparent proxy only");
        ProxyRuntimeProfile.Capability enhance=ProxyRuntimeProfile.capability(ProxyRuntimeProfile.Core.MIHOMO,ProxyRuntimeProfile.Mode.ENHANCE);
        check(enhance.available&&enhance.tcp&&enhance.udp,"Enhance exposes TCP and UDP only after real backend wiring");
        ProxyRuntimeProfile.Capability ebpfOfficial=ProxyRuntimeProfile.capability(ProxyRuntimeProfile.Core.MIHOMO,ProxyRuntimeProfile.Mode.EBPF);
        check(!ebpfOfficial.available,"official Mihomo does not falsely claim eBPF support");
        ProxyRuntimeProfile.Capability ebpfSmart=ProxyRuntimeProfile.capability(ProxyRuntimeProfile.Core.MIHOMO_SMART,ProxyRuntimeProfile.Mode.EBPF);
        check(!ebpfSmart.available,"Mihomo Smart eBPF remains hidden until the compatible eBPF runtime is actually wired");
        ProxyRuntimeProfile.Capability mixed=ProxyRuntimeProfile.capability(ProxyRuntimeProfile.Core.MIHOMO,ProxyRuntimeProfile.Mode.MIXED);
        check(!mixed.available,"Mixed remains hidden until Root TUN plus Redirect is actually wired");
        ProxyRuntimeProfile.Capability tunXray=ProxyRuntimeProfile.capability(ProxyRuntimeProfile.Core.XRAY,ProxyRuntimeProfile.Mode.TUN);
        check(!tunXray.available,"Xray TUN auto-overwrite is not falsely enabled");
        check(ProxyRuntimeProfile.Core.SING_BOX.extensions.contains("jsonc")&&ProxyRuntimeProfile.Core.SING_BOX.extensions.contains("yaml"),"Sing-Box accepts documented formats");
        check(ProxyRuntimeProfile.Core.XRAY.extensions.size()==1&&ProxyRuntimeProfile.Core.XRAY.extensions.contains("json"),"Xray remains JSON-only");
        System.out.println("ProxyRuntimeProfileTest passed: "+checks);
    }
}
