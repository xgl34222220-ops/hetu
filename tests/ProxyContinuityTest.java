package io.github.xgl34222220.hetu;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.Collections;
import java.util.Set;

public final class ProxyContinuityTest {
    private static int checks;
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
        checks++;
    }

    public static void main(String[] args) throws Exception {
        check(ProxyContinuity.processState(false,"0")==ProxyContinuity.ProcessState.UNKNOWN,"failed root query is not evidence of death");
        check(ProxyContinuity.processState(false,"1")==ProxyContinuity.ProcessState.UNKNOWN,"partial output from timed out query is ignored");
        check(ProxyContinuity.processState(true,"")==ProxyContinuity.ProcessState.UNKNOWN,"empty root output is unknown");
        check(ProxyContinuity.processState(true,"permission denied")==ProxyContinuity.ProcessState.UNKNOWN,"unexpected output is unknown");
        check(ProxyContinuity.processState(true,"1\n")==ProxyContinuity.ProcessState.ALIVE,"successful alive query");
        check(ProxyContinuity.processState(true,"0\n")==ProxyContinuity.ProcessState.DEAD,"successful dead query");
        check(ProxyContinuity.preserveRunning(ProxyContinuity.ProcessState.UNKNOWN,true),"probe timeout preserves running core");
        check(!ProxyContinuity.preserveRunning(ProxyContinuity.ProcessState.DEAD,true),"confirmed exit still permits recovery");
        check(!ProxyContinuity.preserveRunning(ProxyContinuity.ProcessState.UNKNOWN,false),"unknown probe does not claim a stopped core is alive");

        long changed=Instant.parse("2026-09-19T12:00:00Z").toEpochMilli();
        check(ProxyContinuity.startedBeforeNetworkChange("2026-09-19T11:59:59.999999Z",changed),"old transport is eligible for reset");
        check(!ProxyContinuity.startedBeforeNetworkChange("2026-09-19T12:00:00Z",changed),"connection established at handover is retained");
        check(!ProxyContinuity.startedBeforeNetworkChange("2026-09-19T12:00:01.250Z",changed),"new connection during debounce survives");
        check(!ProxyContinuity.startedBeforeNetworkChange("2026-09-19T20:00:01+08:00",changed),"offset timestamps compare by instant");
        check(!ProxyContinuity.startedBeforeNetworkChange("",changed),"missing age does not close a connection");
        check(!ProxyContinuity.startedBeforeNetworkChange("invalid",changed),"malformed age does not close a connection");
        check(!ProxyContinuity.startedBeforeNetworkChange("0001-01-01T00:00:00Z",changed),"unset Go timestamp is not a real connection age");

        Set<String> exceptions=MessagingFilterPolicy.subscriptionExceptions(Collections.emptySet());
        check(exceptions.contains("szlong.weixin.qq.com"),"bundled HaGeZi false positive is exempted");
        check(exceptions.contains("long.weixin.qq.com")&&exceptions.contains("short.weixin.qq.com"),"message transports are protected");
        check(!exceptions.contains("weixin.qq.com")&&!exceptions.contains("qq.com"),"no blanket Tencent domain exception");
        check(!exceptions.contains("ad.weixin.qq.com")&&!exceptions.contains("log.weixin.qq.com"),"ads and telemetry remain filterable");
        check(!MessagingFilterPolicy.subscriptionExceptions(Collections.singleton("szlong.weixin.qq.com")).contains("szlong.weixin.qq.com"),"explicit user block wins over automatic exception");
        check(MessagingFilterPolicy.subscriptionExceptions(Collections.singleton("weixin.qq.com")).isEmpty(),"explicit parent block is respected");

        String source="mode: rule\ndisable-keep-alive: false\nkeep-alive-idle: 120\nkeep-alive-interval: 60\nproxies: []\nproxy-groups:\n  - name: SELECT\n    type: select\n    proxies: [DIRECT]\n"
                +"rule-providers:\n  source-adblock:\n    type: inline\n    behavior: domain\n    payload: ['+.source-ad.example.test']\n"
                +"rules:\n  - DOMAIN,stun.example.test,REJECT\n  - DST-PORT,853,REJECT\n"
                +"  - RULE-SET,source-adblock,REJECT\n  - DOMAIN-SUFFIX,weixin.qq.com,DIRECT\n  - MATCH,SELECT\n";
        ProxyRuntimeProfile profile=new ProxyRuntimeProfile(ProxyRuntimeProfile.Core.MIHOMO,ProxyRuntimeProfile.Mode.TPROXY,
                ProxyRuntimeProfile.Ipv6.ENABLE,ProxyRuntimeProfile.AppScope.BLACKLIST,ProxyRuntimeProfile.DnsHijack.TPROXY,
                true,true,true,false,false,true);
        String yaml=MihomoStartupConfig.generate(source,profile).yaml;
        int guard=yaml.indexOf("DST-PORT,853,REJECT");
        int filter=yaml.indexOf("AND,((RULE-SET,hetu-adblock)");
        int originalFilter=yaml.indexOf("RULE-SET,source-adblock,REJECT");
        check(guard>=0&&filter>guard&&originalFilter>filter,"source privacy guards precede injected filtering");
        check(yaml.contains("(NOT,((RULE-SET,hetu-adblock-allow)))"),"child allow exception excludes a parent suffix match");
        check(!yaml.contains("RULE-SET,hetu-adblock-allow,DIRECT"),"filter exceptions never force direct routing");
        check(yaml.indexOf("DOMAIN-SUFFIX,weixin.qq.com,DIRECT")>originalFilter,"user routing order is preserved");
        check(source.contains("MATCH,SELECT")&&!source.contains("hetu-adblock"),"source configuration remains untouched");
        check(yaml.contains("listen: ':11053'"),"DNS accepts IPv4 and IPv6 redirects");
        check(yaml.contains("disable-keep-alive: false")&&yaml.contains("keep-alive-idle: 120")&&yaml.contains("keep-alive-interval: 60"),"Root configuration preserves user keepalive settings");
        if(args.length>0) Files.write(Paths.get(args[0]),yaml.getBytes(StandardCharsets.UTF_8));
        System.out.println("ProxyContinuityTest passed: "+checks);
    }
}
