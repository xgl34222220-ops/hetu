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
        check(AdblockRuleInspection.isBlockingRule("AND","((RULE-SET,hetu-adblock),(NOT,((RULE-SET,hetu-adblock-allow))))","REJECT",false),"live AND filtering rule is recognized");
        check(!AdblockRuleInspection.isBlockingRule("AND","((RULE-SET,hetu-adblock),(NOT,((RULE-SET,hetu-adblock-allow))))","DIRECT",false),"loaded provider routed DIRECT is not filtering");
        check(AdblockRuleInspection.isBlockingRule("AND","((RuleSet,hetu-adblock) && (NOT,(!(RuleSet,hetu-adblock-allow))))","REJECT",false),"actual Mihomo controller Logic.Payload is recognized");
        check(!AdblockRuleInspection.isBlockingRule("AND","((RuleSet,hetu-adblock) && (RuleSet,hetu-adblock-allow))","REJECT",false),"allow provider without NOT is not our filtering chain");
        check(!AdblockRuleInspection.isBlockingRule("RuleSet","hetu-adblock","REJECT",true),"disabled live filtering rule is inactive");
        check(!AdblockRuleInspection.isBlockingRule("RuleSet","hetu-adblock-allow","REJECT",false),"an unrelated provider cannot count as the block chain");
        check(AdblockRuleInspection.isRuleMode("Rule")&&!AdblockRuleInspection.isRuleMode("global")&&!AdblockRuleInspection.isRuleMode("direct"),"global/direct modes never claim rule filtering is effective");
        String prefix="mode: rule\nproxies: []\nrules:\n"
                +"  - DOMAIN,stun.example.test,REJECT\n"
                +"  - DST-PORT,853,REJECT-DROP\n"
                +"  - 'DOMAIN,allow.example.test,DIRECT' # deliberate exception\n";
        for(String route:new String[]{"GEOSITE,cn,DIRECT", "GEOIP,CN,DIRECT", "RULE-SET,China,DIRECT", "DOMAIN-KEYWORD,cdn,DIRECT"}){
            String source=prefix+"  - "+route+"\n  - RULE-SET,source-adblock,REJECT\n  - MATCH,DIRECT\n";
            String generated=MihomoStartupConfig.generate(source,profile).yaml;
            int block=generated.indexOf("AND,((RULE-SET,hetu-adblock)");
            check(block>generated.indexOf("DOMAIN,allow.example.test,DIRECT"),"explicit whitelist stays before filter: "+route);
            check(block>generated.indexOf("DST-PORT,853,REJECT-DROP"),"security rejects stay before filter: "+route);
            check(block<generated.indexOf(route),"broad route cannot shadow filtering: "+route);
            check(generated.indexOf(route)<generated.indexOf("RULE-SET,source-adblock,REJECT"),"source relative order remains unchanged: "+route);
        }
        String indentless=MihomoStartupConfig.generate("mode: rule\nrules:\n- DOMAIN,allow.example.test,DIRECT\n- GEOIP,CN,DIRECT\n- MATCH,DIRECT\n",profile).yaml;
        check(indentless.indexOf("DOMAIN,allow.example.test,DIRECT")<indentless.indexOf("AND,((RULE-SET,hetu-adblock)"),"unindented source preserves explicit exception");
        check(indentless.contains("\n- AND,((RULE-SET,hetu-adblock)"),"unindented YAML list keeps a consistent indentation");
        check(indentless.indexOf("AND,((RULE-SET,hetu-adblock)")<indentless.indexOf("GEOIP,CN,DIRECT"),"unindented broad routing no longer bypasses filtering");
        String empty=MihomoStartupConfig.generate("mode: rule\nrules: []\n",profile).yaml;
        check(!empty.contains("rules: []")&&empty.matches("(?s).*rules:\\s+- AND,.*"),"empty flow-style rules become a valid block list");
        String allowProviders="mode: rule\nrules:\n  - RULE-SET,whitelist,DIRECT\n  - RULE-SET,cn-allowlist,DIRECT,no-resolve\n  - RULE-SET,个人白名单,DIRECT\n  - RULE-SET,China,DIRECT\n  - MATCH,DIRECT\n";
        String allowGenerated=MihomoStartupConfig.generate(allowProviders,profile).yaml;
        int injected=allowGenerated.indexOf("AND,((RULE-SET,hetu-adblock)");
        check(injected>allowGenerated.indexOf("RULE-SET,个人白名单,DIRECT"),"explicitly named allowlist providers retain priority");
        check(injected<allowGenerated.indexOf("RULE-SET,China,DIRECT"),"ordinary direct-routing provider cannot pretend to be an exemption");
        System.out.println("AdblockRuleInspectionTest passed: "+checks);
    }
}
