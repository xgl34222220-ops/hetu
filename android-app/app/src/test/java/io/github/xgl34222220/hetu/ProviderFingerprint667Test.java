package io.github.xgl34222220.hetu;

import org.junit.Test;
import static org.junit.Assert.*;
import java.io.*;
import java.util.*;
import org.yaml.snakeyaml.Yaml;

/**
 * 6.6.7: Mihomo 1.19.31 removed global-client-fingerprint, so subscription (remote provider) nodes without their
 * own client-fingerprint connected with Go's default ClientHello. The startup copy now gives each http/file
 * provider an override-expr item that fills in the global value; explicit node values stay. The item itself was
 * checked against the bundled Mihomo (nodes without a value send Chrome's ClientHello, safari stays safari).
 */
public class ProviderFingerprint667Test {
    private static final String ITEM = "(select(((.client-fingerprint // \"\") == \"\") and (.type == \"vmess\" or .type == \"vless\""
            + " or .type == \"trojan\" or .type == \"anytls\")) | .client-fingerprint) = \"chrome\"";
    private static final String GLOBAL = "global-client-fingerprint: chrome\n";
    private static final String TAIL = "proxy-groups:\n  - {name: G, type: select, use: [sub]}\nrules:\n  - MATCH,G\n";

    private static String generate(String source) throws IOException {
        ProxyRuntimeProfile profile = new ProxyRuntimeProfile(ProxyRuntimeProfile.Core.MIHOMO, ProxyRuntimeProfile.Mode.TPROXY,
                ProxyRuntimeProfile.Ipv6.DISABLE, ProxyRuntimeProfile.AppScope.BLACKLIST, ProxyRuntimeProfile.DnsHijack.TPROXY,
                true, true, true, false, false, true);
        return MihomoStartupConfig.generate(source, profile, "synthetic-controller", 29147).yaml;
    }
    @SuppressWarnings("unchecked")
    private static Map<String, Object> providers(String yaml) {
        return (Map<String, Object>) ((Map<String, Object>) new Yaml().load(yaml)).get("proxy-providers");
    }
    @SuppressWarnings("unchecked")
    private static Map<String, Object> override(String yaml, String provider) {
        return (Map<String, Object>) ((Map<String, Object>) providers(yaml).get(provider)).get("override");
    }
    @SuppressWarnings("unchecked")
    private static List<String> items(String yaml, String provider) {
        Map<String, Object> override = override(yaml, provider);
        return override == null ? Collections.emptyList() : (List<String>) override.get("override-expr");
    }
    private static String migrate(String source) throws IOException {
        return RuntimeCompatibility14.migrateProviderFingerprint(source);
    }

    @Test public void itemIsTheOneCheckedAgainstTheBundledCore() {
        assertEquals("'" + ITEM + "'", RuntimeCompatibility14.providerFingerprintExpr("chrome"));
    }

    @Test public void startupCopyGivesBlockFlowAndMergedRemoteProvidersTheItem() throws Exception {
        String source = GLOBAL + "provider: &provider {type: http, interval: 3600}\nproxy-providers:\n"
                + "  sub:\n    type: http\n    url: 'https://example.test/a'\n    path: ./a.yaml\n"
                + "  flow: {type: http, url: 'https://example.test/b', path: ./b.yaml, }\n"
                + "  merged: {<<: *provider, url: 'https://example.test/c', path: ./c.yaml}\n"
                + "  local:\n    type: file\n    path: ./d.yaml\n" + TAIL;
        String output = generate(source);
        for (String name : Arrays.asList("sub", "flow", "merged", "local")) assertEquals(name, Collections.singletonList(ITEM), items(output, name));
        @SuppressWarnings("unchecked") Map<String, Object> merged = (Map<String, Object>) providers(output).get("merged");
        assertEquals("http", merged.get("type"));
        assertEquals(3600, merged.get("interval"));
        assertEquals("https://example.test/c", merged.get("url"));
        assertFalse(output.contains("global-client-fingerprint"));
    }

    @Test public void existingOverridesAndExpressionsAreExtendedNotReplaced() throws Exception {
        String block = GLOBAL + "proxy-providers:\n  sub:\n    type: http\n    url: 'https://example.test/a'\n    path: ./a.yaml\n"
                + "    override:\n      additional-prefix: \"[A] \"\n      override-expr:\n        - '.udp = true'   # keep\n"
                + "    health-check: {enable: false}\n" + TAIL;
        String output = migrate(block);
        assertEquals(Arrays.asList(".udp = true", ITEM), items(output, "sub"));
        assertEquals("[A] ", override(output, "sub").get("additional-prefix"));
        assertTrue(output.contains("'.udp = true'   # keep\n"));
        String flow = GLOBAL + "proxy-providers:\n  sub:\n    type: http\n    url: 'https://example.test/a'\n"
                + "    override: {udp: true}\n" + TAIL;
        assertEquals(Collections.singletonList(ITEM), items(migrate(flow), "sub"));
        assertEquals(true, override(migrate(flow), "sub").get("udp"));
        String list = GLOBAL + "proxy-providers:\n  sub:\n    type: http\n    url: 'https://example.test/a'\n"
                + "    override: {override-expr: ['.udp = true']}\n" + TAIL;
        assertEquals(Arrays.asList(".udp = true", ITEM), items(migrate(list), "sub"));
    }

    @Test public void providerThatChoosesItsOwnFingerprintIsLeftAsWritten() throws Exception {
        String own = GLOBAL + "proxy-providers:\n  sub:\n    type: http\n    url: 'https://example.test/a'\n"
                + "    override: {client-fingerprint: safari}\n" + TAIL;
        assertEquals(own, migrate(own));
        String expr = GLOBAL + "proxy-providers:\n  sub:\n    type: http\n    url: 'https://example.test/a'\n"
                + "    override:\n      override-expr: ['.client-fingerprint = \"firefox\"']\n" + TAIL;
        assertEquals(expr, migrate(expr));
        String empty = GLOBAL + "proxy-providers:\n  sub:\n    type: http\n    url: 'https://example.test/a'\n    override:\n" + TAIL;
        assertEquals(empty, migrate(empty));
    }

    @Test public void inlineProvidersAndConfigsWithoutTheGlobalOptionKeepTheirProviders() throws Exception {
        String inline = GLOBAL + "proxy-providers:\n  sub:\n    type: inline\n    payload:\n"
                + "      - {name: A, type: trojan, server: a.test, port: 443, password: x}\n" + TAIL;
        assertEquals(inline, migrate(inline));
        String noGlobal = "proxy-providers:\n  sub:\n    type: http\n    url: 'https://example.test/a'\n" + TAIL;
        assertEquals(noGlobal, migrate(noGlobal));
        assertEquals(Collections.emptyList(), items(generate(noGlobal), "sub"));
    }

    @Test public void sharedAnchorsCarryTheItemOnceAndUnicodeOrCrlfSourcesStayValid() throws Exception {
        String shared = GLOBAL + "base: &base\n  type: http\n  interval: 3600\n  override:\n    additional-prefix: \"[A] \"\n"
                + "proxy-providers:\n  a:\n    <<: *base\n    url: 'https://example.test/a'\n  b:\n    <<: *base\n    url: 'https://example.test/b'\n"
                + "  c: *base\n" + TAIL;
        String output = migrate(shared);
        for (String name : Arrays.asList("a", "b", "c")) assertEquals(name, Collections.singletonList(ITEM), items(output, name));
        String unicode = (GLOBAL + "proxy-providers:\n  '🇸🇬 机场':\n    # 订阅 🚀\n    type: http\n    url: 'https://example.test/a'\n" + TAIL)
                .replace("\n", "\r\n");
        assertEquals(Collections.singletonList(ITEM), items(migrate(unicode), "🇸🇬 机场"));
    }

    @Test public void invalidGlobalValueFailsWithoutEchoingIt() {
        String raw = "global-client-fingerprint: 'chrome; secret'\nproxy-providers:\n  sub: {type: http, url: 'https://example.test/a'}\n";
        try { migrate(raw); fail("invalid fingerprint accepted"); }
        catch (IOException expected) { assertFalse(String.valueOf(expected.getMessage()).contains("secret")); }
    }
}
