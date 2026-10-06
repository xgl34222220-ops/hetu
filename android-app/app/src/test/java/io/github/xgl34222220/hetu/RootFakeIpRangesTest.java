package io.github.xgl34222220.hetu;

import static org.junit.Assert.*;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.Test;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.ScalarNode;

/** Original numeric-only fixtures: no subscription, credentials, network or Root operations. */
public class RootFakeIpRangesTest {
    private static final String ACTIVE = "dns:\n  enable: true\n  enhanced-mode: fake-ip\n";
    private static final String DUAL = ACTIVE + "  ipv6: true\n  fake-ip-range6: fdfe:dcba:9876::1/64\n";

    private static ProxyRuntimeProfile profile(ProxyRuntimeProfile.Mode mode, ProxyRuntimeProfile.Ipv6 ipv6) {
        return new ProxyRuntimeProfile(ProxyRuntimeProfile.Core.MIHOMO, mode, ipv6,
                ProxyRuntimeProfile.AppScope.BLACKLIST, ProxyRuntimeProfile.DnsHijack.TPROXY,
                true, true, true, false, false);
    }

    private static RootFakeIpRanges parse(String yaml) throws IOException {
        return RootFakeIpRanges.parse(yaml, profile(ProxyRuntimeProfile.Mode.TPROXY, ProxyRuntimeProfile.Ipv6.ENABLE));
    }

    private static void empty(RootFakeIpRanges ranges) {
        assertEquals("", ranges.ipv4);
        assertEquals("", ranges.ipv6);
    }

    private static IOException rejected(String yaml) {
        try { parse(yaml); fail("invalid active Fake-IP input accepted"); return null; }
        catch (IOException expected) { return expected; }
    }

    @Test public void officialDefaultsAndExplicitEmptyRangesAreDistinct() throws Exception {
        RootFakeIpRanges defaults = parse(ACTIVE);
        assertEquals("198.18.0.1/16", defaults.ipv4);
        assertEquals("", defaults.ipv6);
        assertEquals("198.18.0.1/16", parse(ACTIVE + "  fake-ip-range: null\n").ipv4);
        assertEquals("", parse(ACTIVE + "  fake-ip-range: ''\n").ipv4);
        assertEquals("", parse(ACTIVE + "  ipv6: true\n").ipv6);
        assertEquals("", parse(ACTIVE + "  ipv6: true\n  fake-ip-range6: null\n").ipv6);
    }

    @Test public void mergePriorityAliasesAndExplicitFieldsUseTheReadOnlyNodeGraph() throws Exception {
        String source = "# retained comment\r\nfirst: &first {enable: true, enhanced-mode: fake-ip, fake-ip-range: 10.9.0.1/16}\r\n"
                + "second: &second {enable: false, fake-ip-range: 172.16.0.1/12}\r\n"
                + "shared6: &shared6 fdfe:dcba:9876::1/64\r\n"
                + "base: &base {ipv6: true, dns: {<<: [*first, *second], ipv6: true, fake-ip-range6: *shared6}}\r\n"
                + "<<: *base\r\nsecret: '<synthetic-private-value>'\r\n";
        byte[] before = source.getBytes(StandardCharsets.UTF_8);
        RootFakeIpRanges merged = parse(source);
        assertEquals("10.9.0.1/16", merged.ipv4);
        assertEquals("fdfe:dcba:9876::1/64", merged.ipv6);
        assertArrayEquals(before, source.getBytes(StandardCharsets.UTF_8));
        assertEquals("192.168.2.1/24", parse("a: &a {enable: true, enhanced-mode: fake-ip, fake-ip-range: 10.0.0.1/8}\n"
                + "dns: {<<: *a, fake-ip-range: 192.168.2.1/24}\n").ipv4);
        empty(parse("a: &a {enable: true, enhanced-mode: fake-ip}\ndns: {<<: *a, enable: false}\n"));
        assertEquals("198.18.0.1/16", parse("dns: &recursive {<<: *recursive, enable: true, enhanced-mode: fake-ip}\n").ipv4);
    }

    @Test public void disabledDnsMissingEnableAndOtherEnhancedModesReturnNoProtection() throws Exception {
        for (String yaml : Arrays.asList("rules: [MATCH,DIRECT]\n", "dns: null\n",
                "dns: {enhanced-mode: fake-ip}\n", "dns: {enable: false, enhanced-mode: fake-ip}\n",
                "dns: {enable: true}\n", "dns: {enable: true, enhanced-mode: redir-host}\n")) {
            empty(parse(yaml));
        }
        String upperYaml = "dns: {enable: true, enhanced-mode: FAKE-IP, fake-ip-range: 10.8.0.42/16}\n";
        RootFakeIpRanges upper = parse(upperYaml);
        RootFakeIpRanges lower = parse(upperYaml.replace("FAKE-IP", "fake-ip"));
        assertEquals(lower.ipv4, upper.ipv4);
        assertEquals(lower.ipv4BypassCidrs, upper.ipv4BypassCidrs);
        String startup = upper.privateStartup(upperYaml);
        assertTrue(startup.startsWith(upperYaml));
        assertTrue(startup.contains("# HETU_FAKE_IP_V4=10.8.0.42/16\n"));
        assertTrue(startup.contains("# HETU_LAN_RETURN_V4=" + lower.ipv4BypassCidrs + "\n"));
        empty(parse("dns: {enable: true, enhanced-mode: ' fake-ip '}\n"));
    }

    @Test public void allUnsupportedModesAndOtherCoresAvoidParsingUnrelatedConfigs() throws Exception {
        for (ProxyRuntimeProfile.Mode mode : new ProxyRuntimeProfile.Mode[]{ProxyRuntimeProfile.Mode.TUN,
                ProxyRuntimeProfile.Mode.EBPF, ProxyRuntimeProfile.Mode.MIXED}) {
            empty(RootFakeIpRanges.parse("not valid YAML [", profile(mode, ProxyRuntimeProfile.Ipv6.ENABLE)));
        }
        ProxyRuntimeProfile singBox = new ProxyRuntimeProfile(ProxyRuntimeProfile.Core.SING_BOX,
                ProxyRuntimeProfile.Mode.TPROXY, ProxyRuntimeProfile.Ipv6.ENABLE,
                ProxyRuntimeProfile.AppScope.BLACKLIST, ProxyRuntimeProfile.DnsHijack.OFF,
                false, true, true, false, false);
        empty(RootFakeIpRanges.parse("{not: JSON/config}", singBox));
        for (ProxyRuntimeProfile.Mode mode : new ProxyRuntimeProfile.Mode[]{ProxyRuntimeProfile.Mode.TPROXY,
                ProxyRuntimeProfile.Mode.REDIRECT, ProxyRuntimeProfile.Mode.ENHANCE}) {
            assertEquals("198.18.0.1/16", RootFakeIpRanges.parse(ACTIVE, profile(mode, ProxyRuntimeProfile.Ipv6.ENABLE)).ipv4);
        }
        ProxyRuntimeProfile smart = new ProxyRuntimeProfile(ProxyRuntimeProfile.Core.MIHOMO_SMART,
                ProxyRuntimeProfile.Mode.ENHANCE, ProxyRuntimeProfile.Ipv6.ENABLE,
                ProxyRuntimeProfile.AppScope.CORE, ProxyRuntimeProfile.DnsHijack.REDIRECT,
                true, true, true, false, false);
        assertEquals("fdfe:dcba:9876::1/64", RootFakeIpRanges.parse(DUAL, smart).ipv6);
    }

    @Test public void globalDnsAndProfileIpv6FlagsControlOnlyTheIpv6Protection() throws Exception {
        assertEquals("fdfe:dcba:9876::1/64", parse(DUAL).ipv6); // global IPv6 defaults true
        for (ProxyRuntimeProfile.Ipv6 guard : new ProxyRuntimeProfile.Ipv6[]{ProxyRuntimeProfile.Ipv6.BYPASS,
                ProxyRuntimeProfile.Ipv6.STRICT, ProxyRuntimeProfile.Ipv6.DISABLE}) {
            RootFakeIpRanges actual = RootFakeIpRanges.parse(DUAL, profile(ProxyRuntimeProfile.Mode.TPROXY, guard));
            assertEquals("198.18.0.1/16", actual.ipv4);
            assertEquals("", actual.ipv6);
        }
        assertEquals("", parse("ipv6: false\n" + DUAL).ipv6);
        assertEquals("", parse(ACTIVE + "  fake-ip-range6: fdfe:dcba:9876::1/64\n").ipv6); // DNS IPv6 defaults false
        assertEquals("", parse(ACTIVE + "  ipv6: false\n  fake-ip-range6: fdfe:dcba:9876::1/64\n").ipv6);
        assertEquals("fdfe:dcba:9876::1/64", parse("ipv6: null\n" + DUAL).ipv6);
    }

    @Test public void numericCidrBoundariesAndIpv4EmbeddedIpv6AreAccepted() throws Exception {
        assertEquals("0.0.0.0/0", parse(ACTIVE + "  fake-ip-range: 0.0.0.0/0\n").ipv4);
        assertEquals("255.255.255.255/32", parse(ACTIVE + "  fake-ip-range: 255.255.255.255/32\n").ipv4);
        for (String address : Arrays.asList("::/0", "ffff:ffff:ffff:ffff:ffff:ffff:ffff:ffff/128",
                "2001:db8:0:0:1:2:3:4/64", "FD00::1/64")) {
            assertEquals(address.toLowerCase(java.util.Locale.ROOT), parse(ACTIVE
                    + "  ipv6: true\n  fake-ip-range6: '" + address + "'\n").ipv6);
        }
        String embedded = ACTIVE + "  ipv6: true\n  fake-ip-range6: ::ffff:192.0.2.1/128\n";
        RootFakeIpRanges mapped = parse(embedded);
        assertEquals("::ffff:c000:201/128", mapped.ipv6);
        String startup = mapped.privateStartup(embedded);
        assertTrue(startup.contains("  fake-ip-range6: ::ffff:192.0.2.1/128\n"));
        assertTrue(startup.contains("# HETU_FAKE_IP_V6=::ffff:c000:201/128\n"));
        assertEquals("::ffff:0:0/96", parse(ACTIVE
                + "  ipv6: true\n  fake-ip-range6: ::ffff:192.0.2.1/96\n").ipv6);
    }

    @Test public void invalidIpv4AddressesPrefixesAndShellTokensAreRejectedWithoutLookup() {
        for (String address : Arrays.asList("999.1.1.1/24", "256.0.0.1/8", "01.2.3.4/24", "127.1/8",
                "localhost/8", "192.0.2.1/33", "192.0.2.1/-1", "192.0.2.1/+8", "192.0.2.1/8/9",
                "192.0.2.1/24;echo private", " 192.0.2.1/24", "192.0.2.1/24 ", "fd00::/8")) {
            IOException error = rejected(ACTIVE + "  fake-ip-range: '" + address + "'\n");
            assertTrue(error.getMessage().contains("[FAKE_IP_CIDR]"));
            assertFalse(error.getMessage().contains(address));
            assertNull(error.getCause());
        }
    }

    @Test public void invalidIpv6CompressionScopesGroupsAndFamilyAreRejected() {
        for (String address : Arrays.asList("fd00:::1/64", "fd00::1::2/64", "fd00:1/64", "fd00::gg/64",
                "fd00::1%wlan0/64", "[fd00::1]/64", "fd00::/129", "fd00::/-1", "fd00::/64;id",
                "1:2:3:4:5:6:7:8:9/64", "1:2:3:4:5:6:7:8::/64", "::ffff:999.1.1.1/96",
                "192.0.2.1/24", "::ffff:01.2.3.4/128")) {
            IOException error = rejected(ACTIVE + "  ipv6: true\n  fake-ip-range6: '" + address + "'\n");
            assertTrue(error.getMessage().contains("[FAKE_IP_CIDR]"));
            assertFalse(error.getMessage().contains(address));
            assertNull(error.getCause());
        }
    }

    @Test public void malformedYamlShapesAndBooleansExposeOnlyConstantFailures() {
        for (String yaml : Arrays.asList("secret: 'SYNTHETIC_PRIVATE'\ndns: [}\n", "dns: [SYNTHETIC_PRIVATE]\n",
                "dns: {enable: SYNTHETIC_PRIVATE, enhanced-mode: fake-ip}\n",
                ACTIVE + "  fake-ip-range: [SYNTHETIC_PRIVATE]\n", "ipv6: [SYNTHETIC_PRIVATE]\n" + DUAL)) {
            IOException error = rejected(yaml);
            assertTrue(error.getMessage().contains("[FAKE_IP_"));
            assertFalse(error.getMessage().contains("SYNTHETIC_PRIVATE"));
            assertNull(error.getCause());
            StringWriter stack = new StringWriter();
            error.printStackTrace(new PrintWriter(stack));
            assertFalse(stack.toString().contains("SYNTHETIC_PRIVATE"));
        }
    }

    @Test public void ipv4DefaultBypassSubtractsTheActualMaskedFakeRangeOnly() throws Exception {
        RootFakeIpRanges baseline = parse(ACTIVE);
        RootFakeIpRanges ranges = parse(ACTIVE + "  fake-ip-range: 10.8.0.42/16\n");
        assertEquals("10.8.0.42/16", ranges.ipv4);
        String replacement = "10.0.0.0/13,10.9.0.0/16,10.10.0.0/15,10.12.0.0/14,"
                + "10.16.0.0/12,10.32.0.0/11,10.64.0.0/10,10.128.0.0/9";
        assertEquals(baseline.ipv4BypassCidrs.replace("10.0.0.0/8", replacement), ranges.ipv4BypassCidrs);
        assertEquals(baseline.ipv6BypassCidrs, ranges.ipv6BypassCidrs);
        assertFalse(ranges.ipv4BypassCidrs.contains("10.8.0.0/16"));
        assertEquals(baseline.ipv4BypassCidrs, parse(ACTIVE + "  fake-ip-range: 203.0.113.1/24\n").ipv4BypassCidrs);
    }

    @Test public void ipv6BypassSubtractionIsFiniteExactAndDoesNotBroadenPrivateRouting() throws Exception {
        RootFakeIpRanges simple = parse(ACTIVE + "  ipv6: true\n  fake-ip-range6: fc00::1/8\n");
        assertEquals("::1/128,fd00::/8,fe80::/10,ff00::/8", simple.ipv6BypassCidrs);
        RootFakeIpRanges narrower = parse(DUAL);
        assertEquals(60, narrower.ipv6BypassCidrs.split(",").length); // 57 fc00/7 fragments + three untouched
        BigInteger volume = BigInteger.ZERO;
        for (String range : narrower.ipv6BypassCidrs.split(",")) {
            int bits = Integer.parseInt(range.substring(range.indexOf('/') + 1));
            volume = volume.add(BigInteger.ONE.shiftLeft(128 - bits));
            assertFalse(range.contains("fdfe:dcba:9876::/64"));
        }
        BigInteger privateVolume = BigInteger.ONE.add(BigInteger.ONE.shiftLeft(121))
                .add(BigInteger.ONE.shiftLeft(118)).add(BigInteger.ONE.shiftLeft(120));
        assertEquals(privateVolume.subtract(BigInteger.ONE.shiftLeft(64)), volume);
        assertTrue(narrower.ipv6BypassCidrs.startsWith("::1/128,fc00::/8,"));
        assertTrue(narrower.ipv6BypassCidrs.endsWith(",fe80::/10,ff00::/8"));
    }

    @Test public void fullFamilyRangesRemoveAllDefaultsWhileUserBypassesRemainOutsideThisModel() throws Exception {
        RootFakeIpRanges all = parse(ACTIVE + "  fake-ip-range: 0.0.0.0/0\n"
                + "  ipv6: true\n  fake-ip-range6: ::/0\n");
        assertEquals("", all.ipv4BypassCidrs);
        assertEquals("", all.ipv6BypassCidrs);
        RootFakeIpRanges baseline = parse(ACTIVE);
        RootFakeIpRanges userEntries = parse(ACTIVE + "user-bypass-cidrs: [10.8.0.0/16, 203.0.113.0/24]\n");
        assertEquals(baseline.ipv4BypassCidrs, userEntries.ipv4BypassCidrs);
        assertEquals(baseline.ipv6BypassCidrs, userEntries.ipv6BypassCidrs);
        RootFakeIpRanges inactive = parse("dns: {enable: false, enhanced-mode: fake-ip, fake-ip-range: 0.0.0.0/0}\n");
        assertEquals(baseline.ipv4BypassCidrs, inactive.ipv4BypassCidrs);
        assertEquals(baseline.ipv6BypassCidrs, inactive.ipv6BypassCidrs);
    }

    @Test public void privateStartupCleansForgedOwnedCommentsAndAppendsOneSemanticNeutralTail() throws Exception {
        String body = "# unrelated comment \uD83D\uDE00\r\nsecret: 'synthetic-private-value'\r\n"
                + "dns: {enable: true, enhanced-mode: fake-ip, fake-ip-range: 10.8.0.42/16}\r\n"
                + "notes: '# HETU_FAKE_IP_V4=keep-this-value'\r\n"
                + "literal: |\r\n  # HETU_FAKE_IP_POLICY=keep-this-block-content\r\n"
                + "  body \uD83D\uDE00\r\nmarker: done\r\n";
        String forged = "  # HETU_FAKE_IP_POLICY=forged\r\n# HETU_FAKE_IP_V4=0.0.0.0/0\r\n"
                + body + " # HETU_FAKE_IP_V6=::/0\n# HETU_LAN_RETURN_V4=malformed\n"
                + "# HETU_LAN_RETURN_V6=forged\n# HETU_FAKE_IP_POLICY=99";
        byte[] before = forged.getBytes(StandardCharsets.UTF_8);
        RootFakeIpRanges ranges = parse(forged);
        String startup = ranges.privateStartup(forged);
        String tail = "# HETU_FAKE_IP_POLICY=1\n# HETU_FAKE_IP_V4=" + ranges.ipv4
                + "\n# HETU_FAKE_IP_V6=" + ranges.ipv6 + "\n# HETU_LAN_RETURN_V4="
                + ranges.ipv4BypassCidrs + "\n# HETU_LAN_RETURN_V6=" + ranges.ipv6BypassCidrs + "\n";
        assertEquals(body + tail, startup);
        assertEquals(startup, ranges.privateStartup(startup));
        assertArrayEquals(before, forged.getBytes(StandardCharsets.UTF_8));
        Node root = RuntimeYaml15.compose(startup);
        assertEquals("synthetic-private-value", ((ScalarNode) RuntimeYaml15.inherited(root, "secret")).getValue());
        assertEquals("# HETU_FAKE_IP_V4=keep-this-value", ((ScalarNode) RuntimeYaml15.inherited(root, "notes")).getValue());
        assertEquals("# HETU_FAKE_IP_POLICY=keep-this-block-content\nbody \uD83D\uDE00\n",
                ((ScalarNode) RuntimeYaml15.inherited(root, "literal")).getValue());
        assertEquals(ranges.ipv4, parse(startup).ipv4);
        assertFalse(ranges.toString().contains("synthetic-private-value"));
    }
}
