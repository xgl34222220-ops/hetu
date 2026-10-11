package io.github.xgl34222220.hetu;

import static org.junit.Assert.*;

import java.util.*;
import org.junit.Test;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Parity batch A, pure JVM: dangling proxy-reference cleanup (Mihomo YAML and sing-box JSON), sing-box
 * written as YAML, startup source diagnostics and the opt-in cgroup argument. Synthetic configs only;
 * no network, core or Root operation.
 */
public class ConfigHygiene103Test {
    private static final String HEAD = "mixed-port: 7890\n# keep this comment\nproxies:\n"
            + "  - {name: hk, type: ss, server: 1.1.1.1, port: 443, cipher: aes-128-gcm, password: x}\n"
            + "  - {name: jp, type: ss, server: 2.2.2.2, port: 443, cipher: aes-128-gcm, password: x}\n"
            + "proxy-providers:\n  sub:\n    type: file\n    path: ./sub.yaml\n";
    private static final String TAIL = "rules:\n  - DOMAIN-SUFFIX,example.com,Auto\n  - MATCH,Select\n";

    private static ProxyRuntimeProfile profile(ProxyRuntimeProfile.Core core) {
        return new ProxyRuntimeProfile(core, ProxyRuntimeProfile.Mode.TPROXY, ProxyRuntimeProfile.Ipv6.ENABLE,
                ProxyRuntimeProfile.AppScope.BLACKLIST, ProxyRuntimeProfile.DnsHijack.TPROXY, true, true, true, false, false);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> load(String yaml) {
        return (Map<String, Object>) new Yaml(new SafeConstructor(new LoaderOptions())).load(yaml);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> group(Map<String, Object> root, String name) {
        for (Object g : (List<Object>) root.get("proxy-groups"))
            if (name.equals(((Map<String, Object>) g).get("name"))) return (Map<String, Object>) g;
        throw new AssertionError("group missing: " + name);
    }

    @Test public void danglingMihomoMembersAndProvidersAreStrippedAndEmptyGroupFallsBackToDirect() throws Exception {
        String groups = "proxy-groups:\n"
                + "  - name: Select\n    type: select\n    proxies: [Auto, hk, gone-node, DIRECT]\n"
                + "  - name: Auto\n    type: url-test\n    use: [sub, gone-sub]\n    proxies: [jp]\n"
                + "  - name: Lost\n    type: select\n    proxies: [old-1, old-2]\n    use: [old-sub]\n";
        String yaml = HEAD + groups + TAIL;
        ConfigReferenceCleaner.Result r = ConfigReferenceCleaner.mihomo(yaml);
        assertTrue(r.changed);
        assertTrue("everything outside proxy-groups is byte-identical", r.text.startsWith(HEAD) && r.text.endsWith(TAIL));
        Map<String, Object> root = load(r.text);
        assertEquals(Arrays.asList("Auto", "hk", "DIRECT"), group(root, "Select").get("proxies"));
        assertEquals(Collections.singletonList("sub"), group(root, "Auto").get("use"));
        assertEquals(Collections.singletonList("jp"), group(root, "Auto").get("proxies"));
        assertEquals("an emptied group keeps its name for rules and falls back to DIRECT",
                Collections.singletonList("DIRECT"), group(root, "Lost").get("proxies"));
        assertFalse(group(root, "Lost").containsKey("use"));
        String all = String.join("\n", r.notices);
        for (String token : new String[]{"gone-node", "gone-sub", "old-1", "old-2", "old-sub", "回退为 DIRECT"}) assertTrue(token, all.contains(token));
        assertEquals(6, r.notices.size());
        // The cleaned copy is a valid Mihomo source for the startup generator.
        MihomoStartupConfig.Result generated = MihomoStartupConfig.generate(r.text, profile(ProxyRuntimeProfile.Core.MIHOMO));
        assertFalse(generated.yaml.contains("gone-node"));
        assertTrue(generated.yaml.contains("tproxy-port: 19898"));
    }

    @Test public void cleanConfigsAreReturnedUntouched() {
        String yaml = HEAD + "proxy-groups:\n- name: Select\n  type: select\n  proxies: [hk, jp, REJECT, PASS]\n- name: Auto\n  type: url-test\n  use: [sub]\n" + TAIL;
        ConfigReferenceCleaner.Result r = ConfigReferenceCleaner.mihomo(yaml);
        assertFalse(r.changed);
        assertSame(yaml, r.text);
        assertTrue(r.notices.isEmpty());
        assertFalse(ConfigReferenceCleaner.mihomo("mixed-port: 1\n").changed);
        assertFalse("malformed YAML is left to the core check", ConfigReferenceCleaner.mihomo("proxy-groups: [\n").changed);
    }

    @Test public void columnZeroSequencesAndIncludeAllGroupsAreHandled() {
        String yaml = HEAD + "proxy-groups:\n- name: Every\n  type: select\n  include-all: true\n  proxies: [missing]\n- name: Keep\n  type: select\n  proxies: [hk]\n\n# kept comment\n" + TAIL;
        ConfigReferenceCleaner.Result r = ConfigReferenceCleaner.mihomo(yaml);
        assertTrue(r.changed);
        Map<String, Object> root = load(r.text);
        assertEquals("include-all still supplies members: no DIRECT fallback", Collections.emptyList(), group(root, "Every").get("proxies"));
        assertEquals(Collections.singletonList("hk"), group(root, "Keep").get("proxies"));
        assertTrue("trailing blank and comment lines stay with the next block", r.text.endsWith("  - hk\n\n# kept comment\n" + TAIL));
    }

    @Test public void anchorsUsedOutsideOrDuplicatedBlocksAreNotRewritten() {
        String anchored = HEAD + "proxy-groups:\n  - &base {name: A, type: select, proxies: [nope]}\n" + TAIL + "extra: *base\n";
        ConfigReferenceCleaner.Result a = ConfigReferenceCleaner.mihomo(anchored);
        assertFalse(a.changed);
        assertSame(anchored, a.text);
        assertTrue(a.notices.get(0).contains("锚点"));
        String twice = HEAD + "proxy-groups:\n  - {name: A, type: select, proxies: [nope]}\n" + "proxy-groups:\n  - {name: B, type: select, proxies: [nope]}\n" + TAIL;
        ConfigReferenceCleaner.Result t = ConfigReferenceCleaner.mihomo(twice);
        assertFalse(t.changed);
        assertSame(twice, t.text);
        // An alias inside the block that points at an anchor elsewhere is resolved and still cleaned.
        String merged = "base: &b {type: select}\n" + HEAD + "proxy-groups:\n  - {<<: *b, name: M, proxies: [hk, nope]}\n" + TAIL;
        ConfigReferenceCleaner.Result m = ConfigReferenceCleaner.mihomo(merged);
        assertTrue(m.changed);
        assertEquals(Collections.singletonList("hk"), group(load(m.text), "M").get("proxies"));
        assertEquals("select", group(load(m.text), "M").get("type"));
    }

    @Test @SuppressWarnings("unchecked")
    public void singBoxSelectorReferencesAreCleanedWithDirectFallback() throws Exception {
        String json = "{\"outbounds\":[{\"type\":\"selector\",\"tag\":\"proxy\",\"outbounds\":[\"a\",\"gone\"],\"default\":\"gone\"},"
                + "{\"type\":\"urltest\",\"tag\":\"auto\",\"outbounds\":[\"x\",\"y\"]},"
                + "{\"type\":\"shadowsocks\",\"tag\":\"a\",\"server\":\"1.1.1.1\",\"server_port\":443,\"method\":\"aes-128-gcm\",\"password\":\"p\"}]}";
        Map<String, Object> root = CoreJson.parseObject(json);
        List<String> notices = ConfigReferenceCleaner.singBox(root);
        List<Object> outbounds = (List<Object>) root.get("outbounds");
        Map<String, Object> proxy = (Map<String, Object>) outbounds.get(0), auto = (Map<String, Object>) outbounds.get(1);
        assertEquals(Collections.singletonList("a"), proxy.get("outbounds"));
        assertFalse(proxy.containsKey("default"));
        assertEquals(Collections.singletonList("hetu-direct"), auto.get("outbounds"));
        Map<String, Object> added = (Map<String, Object>) outbounds.get(outbounds.size() - 1);
        assertEquals("direct", added.get("type"));
        assertEquals("hetu-direct", added.get("tag"));
        assertEquals(5, notices.size());
        assertTrue(ConfigReferenceCleaner.singBox(CoreJson.parseObject("{\"outbounds\":[{\"type\":\"direct\",\"tag\":\"d\"}]}")).isEmpty());

        ProxyCoreConfig.Result built = ProxyCoreConfig.build(ProxyRuntimeProfile.Core.SING_BOX, json, options(), url -> null);
        assertEquals(5, built.cleanupNotices.size());
        assertTrue(built.warningText().contains("gone"));
        assertFalse(CoreJson.write(CoreJson.parseObject(built.config)).contains("\"gone\""));
    }

    @Test @SuppressWarnings("unchecked")
    public void singBoxYamlIsConvertedToJsonBeforeCheckAndStart() throws Exception {
        String yaml = "log:\n  level: info\noutbounds:\n  - type: selector\n    tag: proxy\n    outbounds: [a]\n"
                + "  - type: shadowsocks\n    tag: a\n    server: 1.1.1.1\n    server_port: 443\n    method: aes-128-gcm\n    password: p\n"
                + "route:\n  final: proxy\n  rules:\n    - domain_suffix: [example.com]\n      outbound: a\n";
        assertTrue(ProxyCoreConfig.isSingBoxYaml(yaml));
        assertTrue(ProxyCoreConfig.isNativeFor(ProxyRuntimeProfile.Core.SING_BOX, yaml));
        assertFalse("Xray stays JSON-only", ProxyCoreConfig.isNativeFor(ProxyRuntimeProfile.Core.XRAY, yaml));
        String mihomo = HEAD + "proxy-groups:\n  - {name: S, type: select, proxies: [hk]}\n" + TAIL;
        assertFalse("a Clash/Mihomo profile is still converted, not read as sing-box YAML", ProxyCoreConfig.isSingBoxYaml(mihomo));

        ProxyCoreConfig.Result built = ProxyCoreConfig.build(ProxyRuntimeProfile.Core.SING_BOX, yaml, options(), url -> null);
        assertTrue(built.nativeSource);
        assertTrue(built.warningText().contains("YAML"));
        Map<String, Object> root = CoreJson.parseObject(built.config);
        assertEquals(443L, ((Number) ((Map<String, Object>) ((List<Object>) root.get("outbounds")).get(1)).get("server_port")).longValue());
        assertEquals("proxy", ((Map<String, Object>) root.get("route")).get("final"));
        assertNotNull("Hetu ingress replaces inbounds", root.get("inbounds"));
        assertTrue(ProxyCoreConfig.singBoxYamlToJson("a: [1, true, x]\n").replaceAll("\\s", "").equals("{\"a\":[1,true,\"x\"]}"));
        try { ProxyCoreConfig.singBoxYamlToJson("- 1\n"); fail("a top-level list is not a sing-box config"); }
        catch (java.io.IOException expected) { assertTrue(expected.getMessage().contains("映射")); }
    }

    @Test public void startupDiagnosticsNameTheSourceOfPortsDnsAndFakeIp() throws Exception {
        String source = "tproxy-port: 7893\ndns:\n  enable: true\n  listen: 0.0.0.0:1053\n  enhanced-mode: fake-ip\n  fake-ip-range: 28.0.0.1/8\n" + HEAD
                + "proxy-groups:\n  - {name: S, type: select, proxies: [hk]}\n" + TAIL;
        MihomoStartupConfig.Result generated = MihomoStartupConfig.generate(source, profile(ProxyRuntimeProfile.Core.MIHOMO));
        List<String> lines = StartupDiagnostics.mihomo(source, generated.yaml, profile(ProxyRuntimeProfile.Core.MIHOMO),
                generated.tproxyPort, generated.redirectPort, true, MihomoStartupConfig.DNS_PORT, 29090, false, "28.0.0.1/8", "");
        String all = String.join("\n", lines);
        assertTrue(all, all.contains("TPROXY 端口 = 19898 ← 河图运行时覆写（配置文件的 tproxy-port: 7893 已被替换）"));
        assertTrue(all, all.contains("Redirect 端口 = 未启用"));
        assertTrue(all, all.contains("DNS 监听 = 127.0.0.1:11053 ← 河图运行时覆写（配置文件的 dns.listen 0.0.0.0:1053 不用于 Root 接管）"));
        assertTrue(all, all.contains("DNS 模式 = fake-ip ← 配置文件"));
        assertTrue(all, all.contains("Fake-IP 范围（IPv4） = 28.0.0.1/8 ← 配置文件"));
        assertTrue(all, all.contains("控制接口端口 = 127.0.0.1:29090 ← 河图运行时覆写（启动时自动选择空闲端口）"));

        String plain = "dns:\n  enable: true\n" + HEAD + "proxy-groups:\n  - {name: S, type: select, proxies: [hk]}\n" + TAIL;
        MihomoStartupConfig.Result g2 = MihomoStartupConfig.generate(plain, profile(ProxyRuntimeProfile.Core.MIHOMO));
        String d2 = String.join("\n", StartupDiagnostics.mihomo(plain, g2.yaml, profile(ProxyRuntimeProfile.Core.MIHOMO),
                g2.tproxyPort, g2.redirectPort, false, MihomoStartupConfig.DNS_PORT, 29091, true, "", ""));
        assertTrue(d2, d2.contains("DNS 模式 = redir-host ← 默认值"));
        assertTrue(d2, d2.contains("配置文件未设置 tproxy-port"));
        assertTrue(d2, d2.contains("沿用当前运行的端口"));

        String n = String.join("\n", StartupDiagnostics.nativeCore("sing-box", false, plain, 19898, 0, 11053, 29090, false, "198.18.0.1/16"));
        assertTrue(n, n.contains("Fake-IP 范围（IPv4） = 198.18.0.1/16 ← 默认值"));
        assertTrue(n, n.contains("Redirect 端口 = 未启用"));

        String header = StartupDiagnostics.header(Arrays.asList("组「a\nb」", "x"));
        assertEquals("# HETU_NOTICE: [清理] 组「a b」\n# HETU_NOTICE: [清理] x\n", header);
        assertEquals("", StartupDiagnostics.header(Collections.emptyList()));
        assertEquals("[清理] c\n[来源] l\n", StartupDiagnostics.report(Collections.singletonList("c"), Collections.singletonList("[来源] l")));
        // A cleanup header in front of a Mihomo startup copy is only YAML comments.
        assertEquals(load(generated.yaml).get("tproxy-port"), load(header + generated.yaml).get("tproxy-port"));
    }

    @Test public void cgroupLimitArgumentIsOptInAndMapsWeights() {
        assertEquals("", CoreResourceLimits.argument("", true));
        assertEquals("256M", CoreResourceLimits.argument("256M", false));
        assertEquals("cg:256M", CoreResourceLimits.argument(" 256M ", true));
        assertEquals("cg:4", CoreResourceLimits.argument("cg:4", true));
        assertEquals(200, CoreResourceLimits.ioWeightV2(0));
        assertEquals(100, CoreResourceLimits.ioWeightV2(4));
        assertEquals(25, CoreResourceLimits.ioWeightV2(7));
        assertEquals(1000, CoreResourceLimits.blkioWeightV1(0));
        assertEquals(500, CoreResourceLimits.blkioWeightV1(4));
        assertEquals("内存硬限制（cgroup v2） 256 MiB；I/O 权重未生效（设备不支持 io/blkio 控制器）",
                CoreResourceLimits.describe("io=applied:4 memory=applied:268435456 memcg=v2:268435456 iocg=failed"));
        assertEquals("", CoreResourceLimits.describe("none"));
    }

    private static ProxyCoreConfig.Options options() {
        ProxyCoreConfig.Options o = new ProxyCoreConfig.Options();
        o.tproxyPort = 19898; o.dnsPort = 11053; o.controllerPort = 29090; o.egressPort = 29150; o.secret = "s";
        return o;
    }
}
