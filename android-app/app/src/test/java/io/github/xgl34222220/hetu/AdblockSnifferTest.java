package io.github.xgl34222220.hetu;

import org.junit.Test;
import static org.junit.Assert.*;

/** Ad-block domain rules need a hostname; the runtime copy must enable sniffing when the source doesn't. */
public class AdblockSnifferTest {
    private static final String SOURCE =
            "proxies:\n" +
            "  - {name: a, type: ss, server: 1.1.1.1, port: 443, cipher: aes-128-gcm, password: x}\n" +
            "proxy-groups:\n" +
            "  - {name: P, type: select, proxies: [a]}\n" +
            "rules:\n" +
            "  - MATCH,P\n";

    private static ProxyRuntimeProfile profile(boolean adblock) {
        return new ProxyRuntimeProfile(ProxyRuntimeProfile.Core.MIHOMO, ProxyRuntimeProfile.Mode.TPROXY,
                ProxyRuntimeProfile.Ipv6.ENABLE, ProxyRuntimeProfile.AppScope.CORE, ProxyRuntimeProfile.DnsHijack.TPROXY,
                true, true, true, false, false, adblock);
    }

    private static int count(String text, String needle) {
        int n = 0, i = 0;
        while ((i = text.indexOf(needle, i)) >= 0) { n++; i += needle.length(); }
        return n;
    }

    @Test public void adblockAddsSnifferWhenSourceHasNone() throws Exception {
        String out = MihomoStartupConfig.generate(SOURCE, profile(true)).yaml;
        assertEquals(1, count(out, "\nsniffer:\n"));
        assertTrue(out.contains("parse-pure-ip: true"));
        assertTrue(out.contains("override-destination: false"));
        assertTrue(AdblockRuleInspection.isInjected(out));
    }

    @Test public void userSnifferIsRespected() throws Exception {
        String out = MihomoStartupConfig.generate(SOURCE + "sniffer:\n  enable: false\n", profile(true)).yaml;
        assertEquals(1, count(out, "sniffer:"));
        assertFalse(out.contains("parse-pure-ip: true"));
    }

    @Test public void noSnifferWithoutAdblock() throws Exception {
        String out = MihomoStartupConfig.generate(SOURCE, profile(false)).yaml;
        assertFalse(out.contains("sniffer:"));
    }

    @Test public void topLevelKeyDetectionIgnoresNestedKeys() {
        assertTrue(MihomoStartupConfig.hasTopLevelKey("a: 1\nsniffer:\n  enable: true\n", "sniffer"));
        assertFalse(MihomoStartupConfig.hasTopLevelKey("dns:\n  sniffer: x\n# sniffer:\n", "sniffer"));
    }
}
