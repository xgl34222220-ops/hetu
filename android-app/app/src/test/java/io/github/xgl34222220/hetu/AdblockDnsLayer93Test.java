package io.github.xgl34222220.hetu;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;

import static org.junit.Assert.*;

/** DNS-layer ad blocking (nameserver-policy rcode + fake-ip-filter) and the sniffer merge. */
public class AdblockDnsLayer93Test {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    private static final String BASE =
            "proxies:\n" +
            "  - {name: a, type: ss, server: 1.1.1.1, port: 443, cipher: aes-128-gcm, password: x}\n" +
            "rules:\n" +
            "  - MATCH,DIRECT\n";

    private static ProxyRuntimeProfile profile(boolean adblock, ProxyRuntimeProfile.DnsHijack hijack) {
        return new ProxyRuntimeProfile(ProxyRuntimeProfile.Core.MIHOMO, ProxyRuntimeProfile.Mode.TPROXY,
                ProxyRuntimeProfile.Ipv6.ENABLE, ProxyRuntimeProfile.AppScope.CORE, hijack,
                true, true, true, false, false, adblock);
    }

    private static String gen(String source) throws Exception {
        return MihomoStartupConfig.generate(source, profile(true, ProxyRuntimeProfile.DnsHijack.TPROXY)).yaml;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> load(String yaml) {
        return (Map<String, Object>) new Yaml(new LoaderOptions()).load(yaml);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> dns(String yaml) { return (Map<String, Object>) load(yaml).get("dns"); }

    @Test public void blockAnswerIsTheFirstNameserverPolicyAndItsProviderIsDeclared() throws Exception {
        String out = gen(BASE + "dns:\n  enable: true\n  nameserver-policy:\n    'geosite:cn': 223.5.5.5\n    '+.corp.test': 10.0.0.1\n  nameserver:\n    - 223.5.5.5\n");
        @SuppressWarnings("unchecked")
        Map<String, Object> policy = (Map<String, Object>) dns(out).get("nameserver-policy");
        List<String> keys = new ArrayList<>(policy.keySet());
        assertEquals("rule-set:hetu-adblock-dns", keys.get(0));
        assertEquals("rcode://success", policy.get("rule-set:hetu-adblock-dns"));
        assertEquals(Arrays.asList("rule-set:hetu-adblock-dns", "geosite:cn", "+.corp.test"), keys);
        @SuppressWarnings("unchecked")
        Map<String, Object> providers = (Map<String, Object>) load(out).get("rule-providers");
        @SuppressWarnings("unchecked")
        Map<String, Object> dnsSet = (Map<String, Object>) providers.get("hetu-adblock-dns");
        assertEquals("domain", dnsSet.get("behavior"));
        assertEquals("./ruleset/hetu-adblock-dns.txt", dnsSet.get("path"));
        assertTrue(AdblockDnsPolicy.isInjected(out));
        assertTrue(AdblockRuleInspection.isInjected(out));
    }

    @Test public void listenerOnlyDnsGetsAPolicyBlock() throws Exception {
        String out = gen(BASE);
        @SuppressWarnings("unchecked")
        Map<String, Object> policy = (Map<String, Object>) dns(out).get("nameserver-policy");
        assertEquals(Collections.singletonList("rule-set:hetu-adblock-dns"), new ArrayList<>(policy.keySet()));
        assertEquals(":11053", dns(out).get("listen"));
    }

    @Test public void withoutCoreDnsTheConnectionLayerStaysAlone() throws Exception {
        String out = MihomoStartupConfig.generate(BASE, profile(true, ProxyRuntimeProfile.DnsHijack.OFF)).yaml;
        assertFalse(out.contains("hetu-adblock-dns"));
        assertNull(load(out).get("dns"));
        assertTrue(AdblockRuleInspection.isInjected(out));
    }

    @Test public void noAdblockNoDnsLayer() throws Exception {
        String out = MihomoStartupConfig.generate(BASE + "dns:\n  enable: true\n  nameserver:\n    - 223.5.5.5\n",
                profile(false, ProxyRuntimeProfile.DnsHijack.TPROXY)).yaml;
        assertFalse(out.contains("hetu-adblock"));
    }

    @Test public void fakeIpBlacklistExemptsTheSetFromFakeIps() throws Exception {
        String out = gen(BASE + "dns:\n  enable: true\n  enhanced-mode: fake-ip\n  fake-ip-filter:\n    - '*.lan'\n  nameserver:\n    - 223.5.5.5\n");
        assertEquals(Arrays.asList("rule-set:hetu-adblock-dns", "*.lan"), dns(out).get("fake-ip-filter"));
    }

    @Test public void fakeIpWithoutFilterKeepsMihomoDefaults() throws Exception {
        String out = gen(BASE + "dns:\n  enable: true\n  enhanced-mode: fake-ip\n  nameserver:\n    - 223.5.5.5\n");
        List<String> expected = new ArrayList<>();
        expected.add("rule-set:hetu-adblock-dns");
        expected.addAll(AdblockDnsPolicy.MIHOMO_DEFAULT_FAKE_IP_FILTER);
        assertEquals(expected, dns(out).get("fake-ip-filter"));
    }

    @Test public void fakeIpRuleModeReturnsRealIpFirst() throws Exception {
        String out = gen(BASE + "dns:\n  enable: true\n  enhanced-mode: fake-ip\n  fake-ip-filter-mode: rule\n  fake-ip-filter:\n  - GEOSITE,cn,real-ip\n  - MATCH,fake-ip\n  nameserver:\n  - 223.5.5.5\n");
        assertEquals(Arrays.asList("RULE-SET,hetu-adblock-dns,real-ip", "GEOSITE,cn,real-ip", "MATCH,fake-ip"), dns(out).get("fake-ip-filter"));
    }

    @Test public void fakeIpWhitelistAndRedirHostLeaveTheFilterAlone() throws Exception {
        String white = gen(BASE + "dns:\n  enable: true\n  enhanced-mode: fake-ip\n  fake-ip-filter-mode: whitelist\n  fake-ip-filter:\n    - '+.google.com'\n  nameserver:\n    - 223.5.5.5\n");
        assertEquals(Collections.singletonList("+.google.com"), dns(white).get("fake-ip-filter"));
        String redir = gen(BASE + "dns:\n  enable: true\n  enhanced-mode: redir-host\n  nameserver:\n    - 223.5.5.5\n");
        assertNull(dns(redir).get("fake-ip-filter"));
        assertTrue(AdblockDnsPolicy.isInjected(redir));
    }

    @Test public void inlinePolicyIsLeftUntouchedAndNoDnsProviderIsDeclared() throws Exception {
        String out = gen(BASE + "dns:\n  enable: true\n  nameserver-policy: {'geosite:cn': 223.5.5.5}\n  nameserver:\n    - 223.5.5.5\n");
        assertFalse(out.contains("hetu-adblock-dns"));
        assertTrue(AdblockRuleInspection.isInjected(out));
    }

    @Test public void dnsSetSkipsParentsAndChildrenOfAllowedDomains() {
        List<String> dns = AdblockDnsPolicy.dnsBlockDomains(
                Arrays.asList("example.com", "ads.example.com", "tracker.test", "cdn.allowed.test", "allowed.test", "plain.ad"),
                Arrays.asList("good.ads.example.com", "allowed.test"));
        // example.com and ads.example.com are parents of an allowed child; allowed.test and
        // cdn.allowed.test are covered by the allow entry. Only the rest is answered empty.
        assertEquals(Arrays.asList("plain.ad", "tracker.test"), dns);
    }

    @Test public void exportPublishesTheDnsSetAndReusesItOnlyWhenHashVerified() throws Exception {
        File root = temp.newFolder();
        ProxyAdblockRules.Snapshot first = ProxyAdblockRules.exportSnapshot(root, "g.dns",
                new HashSet<>(Arrays.asList("ads.example.com", "plain.ad")), new HashSet<>(Collections.singletonList("good.ads.example.com")));
        assertNotNull(first.dnsFile);
        assertEquals(AdblockDnsPolicy.FILE_NAME, first.dnsFile.getName());
        assertEquals(first.file.getParentFile(), first.dnsFile.getParentFile());
        assertEquals("+.plain.ad\n", new String(Files.readAllBytes(first.dnsFile.toPath()), StandardCharsets.UTF_8));
        assertEquals(1, first.dnsCount);
        ProxyAdblockRules.Snapshot again = ProxyAdblockRules.exportSnapshot(root, "g.dns",
                new HashSet<>(Arrays.asList("ads.example.com", "plain.ad")), new HashSet<>(Collections.singletonList("good.ads.example.com")));
        assertEquals(first.dnsFile, again.dnsFile);
        Files.write(first.dnsFile.toPath(), "+.tampered.test\n".getBytes(StandardCharsets.UTF_8));
        ProxyAdblockRules.Snapshot repaired = ProxyAdblockRules.exportSnapshot(root, "g.dns",
                new HashSet<>(Arrays.asList("ads.example.com", "plain.ad")), new HashSet<>(Collections.singletonList("good.ads.example.com")));
        assertNotEquals(first.dnsFile, repaired.dnsFile);
        assertEquals("+.plain.ad\n", new String(Files.readAllBytes(repaired.dnsFile.toPath()), StandardCharsets.UTF_8));
    }

    @Test public void enabledSubscriptionSnifferGetsParsePureIp() throws Exception {
        String out = gen(BASE + "sniffer:\n  enable: true\n  sniff:\n    TLS:\n      ports: [443]\n");
        @SuppressWarnings("unchecked")
        Map<String, Object> sniffer = (Map<String, Object>) load(out).get("sniffer");
        assertEquals(Boolean.TRUE, sniffer.get("parse-pure-ip"));
        assertEquals("enabled", AdblockDnsPolicy.snifferState(out));
    }

    @Test public void explicitSnifferChoicesAreRespected() throws Exception {
        String off = gen(BASE + "sniffer:\n  enable: false\n");
        assertFalse(off.contains("parse-pure-ip"));
        assertEquals("disabled", AdblockDnsPolicy.snifferState(off));
        String pureOff = gen(BASE + "sniffer:\n  enable: true\n  parse-pure-ip: false\n");
        assertFalse(pureOff.contains("parse-pure-ip: true"));
        assertEquals("pure-ip-off", AdblockDnsPolicy.snifferState(pureOff));
        assertEquals("none", AdblockDnsPolicy.snifferState(BASE));
    }

    @Test public void recommendedDnsIsOptInAndOnlyForSubscriptionsWithoutDns() throws Exception {
        ProxyRuntimeProfile p = profile(true, ProxyRuntimeProfile.DnsHijack.TPROXY);
        String off = MihomoStartupConfig.generate(BASE, p, "s", 29090, ProxyRuntimeProfile.AppScope.CORE,
                Collections.<String>emptySet(), Collections.<String>emptySet(), "", false).yaml;
        assertEquals("redir-host", AdblockDnsPolicy.enhancedMode(off));
        String on = MihomoStartupConfig.generate(BASE, p, "s", 29090, ProxyRuntimeProfile.AppScope.CORE,
                Collections.<String>emptySet(), Collections.<String>emptySet(), "", true).yaml;
        assertEquals("fake-ip", AdblockDnsPolicy.enhancedMode(on));
        assertEquals(":11053", dns(on).get("listen"));
        assertTrue(((List<?>) dns(on).get("fake-ip-filter")).contains("rule-set:hetu-adblock-dns"));
        String own = BASE + "dns:\n  enable: true\n  enhanced-mode: redir-host\n  nameserver:\n    - 1.1.1.1\n";
        String kept = MihomoStartupConfig.generate(own, p, "s", 29090, ProxyRuntimeProfile.AppScope.CORE,
                Collections.<String>emptySet(), Collections.<String>emptySet(), "", true).yaml;
        assertEquals("redir-host", AdblockDnsPolicy.enhancedMode(kept));
        assertTrue(MihomoStartupConfig.sourceHasDns(own));
        assertFalse(MihomoStartupConfig.sourceHasDns(BASE));
    }
}
