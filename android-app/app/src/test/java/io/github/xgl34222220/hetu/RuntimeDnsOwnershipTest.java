package io.github.xgl34222220.hetu;

import java.util.*;
import org.junit.Test;
import org.yaml.snakeyaml.Yaml;
import static org.junit.Assert.*;

/** Runs the production startup generator. No resolver, socket or Root is contacted. */
public class RuntimeDnsOwnershipTest {
    private static final String OLD_A = "https://1.12.12.12/dns-query";
    private static final String OLD_B = "https://120.53.53.53/dns-query";
    private static final String SOURCE = "mode: rule\ndns:\n  enable: false\n  listen: '127.0.0.1:1053'\n  enhanced-mode: fake-ip\n"
            + "  nameserver: ['" + OLD_A + "', 'https://resolver.example.invalid/dns-query']\n"
            + "  fallback: ['" + OLD_B + "']\n"
            + "  default-nameserver: ['" + OLD_A + "']\n"
            + "  proxy-server-nameserver: ['" + OLD_B + "']\n"
            + "  direct-nameserver: ['" + OLD_A + "']\n"
            + "  nameserver-policy: {'+.example.invalid': ['" + OLD_B + "']}\n"
            + "  fake-ip-filter: ['+.local']\n"
            + "rules: ['DOMAIN,example.invalid,DIRECT', 'MATCH,DIRECT']\n";

    private static ProxyRuntimeProfile profile(ProxyRuntimeProfile.Core core, ProxyRuntimeProfile.Mode mode,
            ProxyRuntimeProfile.DnsHijack dns, boolean adblock) {
        return new ProxyRuntimeProfile(core, mode, ProxyRuntimeProfile.Ipv6.BYPASS,
                ProxyRuntimeProfile.AppScope.CORE, dns, true, true, true, false, false, adblock);
    }
    private static String generate(String source) throws Exception {
        return MihomoStartupConfig.generate(source, profile(ProxyRuntimeProfile.Core.MIHOMO,
                ProxyRuntimeProfile.Mode.TPROXY, ProxyRuntimeProfile.DnsHijack.TPROXY, false)).yaml;
    }
    private static Map<?, ?> parse(String text) { return new Yaml().load(text); }

    @Test public void configuredResolversSurviveEverySupportedIngressAndDnsHijackMode() throws Exception {
        Map<?, ?> before = (Map<?, ?>) parse(SOURCE).get("dns");
        for (ProxyRuntimeProfile.Core core : Arrays.asList(ProxyRuntimeProfile.Core.MIHOMO, ProxyRuntimeProfile.Core.MIHOMO_SMART)) {
            for (ProxyRuntimeProfile.Mode mode : Arrays.asList(ProxyRuntimeProfile.Mode.TPROXY,
                    ProxyRuntimeProfile.Mode.REDIRECT, ProxyRuntimeProfile.Mode.ENHANCE, ProxyRuntimeProfile.Mode.TUN,
                    ProxyRuntimeProfile.Mode.EBPF)) {
                for (ProxyRuntimeProfile.DnsHijack hijack : ProxyRuntimeProfile.DnsHijack.values()) {
                    String result = MihomoStartupConfig.generate(SOURCE, profile(core, mode, hijack, false),
                            "synthetic-only", 29090, ProxyRuntimeProfile.AppScope.CORE, Collections.emptySet(),
                            Collections.emptySet(), "synthetic0").yaml;
                    Map<?, ?> after = (Map<?, ?>) parse(result).get("dns");
                    assertEquals(hijack != ProxyRuntimeProfile.DnsHijack.OFF, after.get("enable"));
                    assertEquals(hijack == ProxyRuntimeProfile.DnsHijack.OFF ? "127.0.0.1:1053" : ":11053", after.get("listen"));
                    for (String key : Arrays.asList("nameserver", "fallback", "default-nameserver",
                            "proxy-server-nameserver", "direct-nameserver", "nameserver-policy", "fake-ip-filter", "enhanced-mode")) {
                        assertEquals(core + "/" + mode + "/" + hijack + ": " + key, before.get(key), after.get(key));
                    }
                }
            }
        }
    }

    @Test public void identicalTextOutsideDnsIsNeverReplaced() throws Exception {
        String provider = "https://source.example.invalid/import?source=" + OLD_A;
        String raw = SOURCE + "proxy-providers:\n  synthetic:\n    type: http\n    url: '" + provider + "'\n"
                + "x-description: 'DNS example " + OLD_B + "'\n# example " + OLD_A + "\n";
        String result = generate(raw);
        assertEquals(parse(raw).get("proxy-providers"), parse(result).get("proxy-providers"));
        assertEquals(parse(raw).get("x-description"), parse(result).get("x-description"));
        assertTrue(result.contains("# example " + OLD_A));
        assertFalse(result.contains("https://223.6.6.6/dns-query"));
    }

    @Test public void resolverPreservationDoesNotChangeExplicitTlsSnifferOrRoutingPolicy() throws Exception {
        String raw = SOURCE + "sniffer:\n  enable: true\n  override-destination: false\n  sniff:\n    TLS:\n      ports: [443]\n"
                + "proxies:\n  - {name: synthetic, type: trojan, server: proxy.example.invalid, port: 443, "
                + "password: synthetic-only, skip-cert-verify: false, client-fingerprint: firefox}\n";
        Map<?, ?> before = parse(raw), after = parse(generate(raw));
        for (String key : Arrays.asList("sniffer", "proxies", "rules")) assertEquals(key, before.get(key), after.get(key));
    }

    @Test public void missingDnsRetainsExistingSystemFallbackWithoutExternalOverride() throws Exception {
        String raw = "mode: rule\nproxies: []\nrules: ['MATCH,DIRECT']\n";
        for (ProxyRuntimeProfile.DnsHijack hijack : ProxyRuntimeProfile.DnsHijack.values()) {
            String result = MihomoStartupConfig.generate(raw, profile(ProxyRuntimeProfile.Core.MIHOMO,
                    ProxyRuntimeProfile.Mode.TPROXY, hijack, false)).yaml;
            Map<?, ?> dns = (Map<?, ?>) parse(result).get("dns");
            if (hijack == ProxyRuntimeProfile.DnsHijack.OFF) assertNull(dns);
            else {
                assertEquals(true, dns.get("enable"));
                assertEquals(":11053", dns.get("listen"));
                assertEquals(Collections.singletonList("system"), dns.get("nameserver"));
                for (String key : Arrays.asList("fallback", "default-nameserver",
                        "proxy-server-nameserver", "direct-nameserver", "nameserver-policy")) assertFalse(dns.containsKey(key));
            }
            assertFalse(result.contains(OLD_A) || result.contains(OLD_B) || result.contains("https://223.6.6.6/dns-query"));
        }
    }
}
