package io.github.xgl34222220.hetu;

public final class AdblockRuleInspectionTest {
    private static int checks;
    private static void check(boolean value,String message) {
        if(!value)throw new AssertionError(message);
        checks++;
    }
    public static void main(String[] args) throws Exception {
        ProxyRuntimeProfile profile=new ProxyRuntimeProfile(ProxyRuntimeProfile.Core.MIHOMO,
                ProxyRuntimeProfile.Mode.TPROXY,ProxyRuntimeProfile.Ipv6.ENABLE,
                ProxyRuntimeProfile.AppScope.BLACKLIST,ProxyRuntimeProfile.DnsHijack.TPROXY,
                true,true,true,false,false,true);
        String yaml=MihomoStartupConfig.generate("mode: rule\nproxies: []\nrules:\n  - MATCH,DIRECT\n",profile).yaml;
        check(AdblockRuleInspection.isInjected(yaml),"generated exception-aware rule is recognized");
        check(!AdblockRuleInspection.isInjected(yaml.replace("hetu-adblock-allow:","missing-allow:")),"both providers are required for the AND rule");
        check(!AdblockRuleInspection.isInjected(yaml.replace(",REJECT",",DIRECT")),"a non-blocking rule is not reported as filtering");
        check(AdblockRuleInspection.isInjected("rule-providers:\n  hetu-adblock:\n    type: file\nrules:\n  - RULE-SET,hetu-adblock,REJECT\n"),"existing legacy core can still be reported accurately");
        check(!AdblockRuleInspection.isInjected("hetu-adblock:\n# - RULE-SET,hetu-adblock,REJECT\n"),"commented rules do not count");
        check(!AdblockRuleInspection.isInjected("# hetu-adblock:\nrules:\n  - RULE-SET,hetu-adblock,REJECT\n"),"commented provider does not count");
        check(!AdblockRuleInspection.isInjected("hetu-adblock:\n"),"provider without a routing rule is inactive");
        check(AdblockRuleInspection.isInjected("hetu-adblock:\nrules:\n - 'RULE-SET, hetu-adblock, REJECT' # current rule\n"),"quoted rules and comments are supported");
        check(!AdblockRuleInspection.isInjected(null),"missing startup config is inactive");
        System.out.println("AdblockRuleInspectionTest passed: "+checks);
    }
}
