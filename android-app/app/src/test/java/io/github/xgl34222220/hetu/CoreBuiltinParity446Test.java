package io.github.xgl34222220.hetu;

import android.app.Application;
import androidx.test.core.app.ApplicationProvider;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import static org.junit.Assert.*;

/**
 * 2026-10-18 用户要求：sing-box / Xray / V2Fly / Hysteria 的内置配置要像 Mihomo 内置一样能用。
 * 未修改的内置模板（占位节点）启动时跟随 Mihomo 内置配置（同一订阅、策略组与规则）；转换时 mrs 规则集换成各核心
 * 自己能读的同一份数据，Mihomo 的具名直连 / 拒绝出口与 DNS 规则集照常生效；SnakeYAML 2.3 的分块边界 emoji 不再报错。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {35}, application = Application.class)
public class CoreBuiltinParity446Test {
    private static final String SUB = "https://sub.fixture.test/nodes";
    private static final String NODES = "proxies:\n"
            + "  - {name: HK 01, type: ss, server: 192.0.2.1, port: 8388, cipher: aes-128-gcm, password: p, udp: true}\n"
            + "  - {name: JP 01, type: trojan, server: 192.0.2.2, port: 443, password: p, sni: a.example.com}\n"
            + "  - {name: US 01, type: ss, server: 192.0.2.3, port: 8388, cipher: aes-128-gcm, password: p}\n";
    private static final String META = "https://testingcf.jsdelivr.net/gh/MetaCubeX/meta-rules-dat@meta/geo/";
    private static final String SING = "https://testingcf.jsdelivr.net/gh/MetaCubeX/meta-rules-dat@sing/geo/";
    private static final List<ProxyRuntimeProfile.Core> NON_MIHOMO = Arrays.asList(ProxyRuntimeProfile.Core.SING_BOX,
            ProxyRuntimeProfile.Core.SING_BOX_REF1ND, ProxyRuntimeProfile.Core.XRAY, ProxyRuntimeProfile.Core.V2FLY, ProxyRuntimeProfile.Core.HYSTERIA);

    private final List<String> fetched = new ArrayList<>();
    private ProxyConfigLibrary library;

    @Before public void reset() throws Exception {
        Application app = ApplicationProvider.getApplicationContext();
        app.getSharedPreferences("hetu", 0).edit().clear().commit();
        delete(new File(app.getFilesDir(), "hetu/configs"));
        library = new ProxyConfigLibrary(app);
        fetched.clear();
    }

    private static void delete(File f) {
        File[] children = f.listFiles();
        if (children != null) for (File c : children) delete(c);
        f.delete();
    }

    private String fetch(String url) {
        fetched.add(url);
        if (url.equals(SUB)) return NODES;
        if (url.endsWith("/geoip/telegram.list")) return "91.108.4.0/22\n149.154.160.0/20\n";
        if (url.endsWith("/geoip/netflix.list")) return "23.246.0.0/18\n";
        if (url.endsWith("/Steam_OCD_Domain.yaml")) return "payload:\n  - '+.steamstatic.com'\n  - 'store.steampowered.com'\n";
        return null;
    }

    private static ProxyCoreConfig.Options options() {
        ProxyCoreConfig.Options o = new ProxyCoreConfig.Options();
        o.tproxyPort = 19898; o.dnsPort = 11053; o.controllerPort = 29090; o.egressPort = 29150; o.secret = "fixture-secret";
        return o;
    }

    private ProxyCoreConfig.Result build(ProxyRuntimeProfile.Core core, String source, ProxyCoreConfig.Options o) throws Exception {
        return ProxyCoreConfig.build(core, source, o, this::fetch);
    }

    @SuppressWarnings("unchecked") private static Map<String, Object> map(Object o) { return (Map<String, Object>) o; }
    @SuppressWarnings("unchecked") private static List<Object> list(Object o) { return (List<Object>) o; }

    private static Map<String, Object> json(ProxyCoreConfig.Result r) throws Exception { return CoreJson.parseObject(r.config); }

    private static Map<String, Object> ruleSet(Map<String, Object> config, String tag) {
        for (Object o : list(map(config.get("route")).get("rule_set"))) if (tag.equals(map(o).get("tag"))) return map(o);
        fail("rule_set " + tag + " missing");
        return null;
    }

    private static Map<String, Object> outbound(Map<String, Object> config, String tag) {
        for (Object o : list(config.get("outbounds"))) if (tag.equals(map(o).get("tag"))) return map(o);
        return null;
    }

    /** The first route rule whose JSON names {@code needle}. */
    private static Map<String, Object> ruleWith(List<Object> rules, String needle) {
        for (Object o : rules) if (CoreJson.write(o).contains(needle)) return map(o);
        fail("no rule names " + needle);
        return null;
    }

    private static void noWarningMentions(ProxyCoreConfig.Result r, String... words) {
        for (String w : r.warnings) for (String word : words) assertFalse(w, w.contains(word));
    }

    private static final String MRS_SOURCE = NODES
            + "rule-providers:\n"
            + "  CN: {type: http, behavior: domain, format: mrs, proxy: DIRECT, url: '" + META + "geosite/cn.mrs', path: ./r/cn.mrs}\n"
            + "  NotCN: {type: http, behavior: domain, format: mrs, url: 'https://raw.githubusercontent.com/MetaCubeX/meta-rules-dat/meta/geo/geosite/geolocation-!cn.mrs', path: ./r/x.mrs}\n"
            + "  CN_IP: {type: http, behavior: ipcidr, format: mrs, proxy: DIRECT, url: '" + META + "geoip/cn.mrs', path: ./r/ip.mrs}\n"
            + "  TG_IP: {type: http, behavior: ipcidr, format: mrs, proxy: DIRECT, url: '" + META + "geoip/telegram.mrs', path: ./r/tg.mrs}\n"
            + "proxy-groups:\n  - {name: PROXY, type: select, proxies: [HK 01, JP 01]}\n"
            + "rules:\n"
            + "  - RULE-SET,CN,DIRECT\n"
            + "  - RULE-SET,NotCN,PROXY\n"
            + "  - AND,((DST-PORT,853),(RULE-SET,CN_IP,no-resolve)),DIRECT\n"
            + "  - RULE-SET,TG_IP,PROXY,no-resolve\n"
            + "  - GEOIP,netflix,PROXY\n"
            + "  - MATCH,PROXY\n";

    @Test public void metaRulesDatMrsBecomesTheSameSingBoxRuleSetOnTheSameMirror() throws Exception {
        ProxyCoreConfig.Result r = build(ProxyRuntimeProfile.Core.SING_BOX, MRS_SOURCE, options());
        noWarningMentions(r, "mrs", "规则集");
        Map<String, Object> config = json(r);
        Map<String, Object> cn = ruleSet(config, "rp-CN");
        assertEquals("remote", cn.get("type"));
        assertEquals("binary", cn.get("format"));
        assertEquals(SING + "geosite/cn.srs", cn.get("url"));
        assertEquals("proxy: DIRECT downloads direct, as in Mihomo", "DIRECT", cn.get("download_detour"));
        Map<String, Object> notCn = ruleSet(config, "rp-NotCN");
        assertEquals("https://raw.githubusercontent.com/MetaCubeX/meta-rules-dat/sing/geo/geosite/geolocation-!cn.srs", notCn.get("url"));
        assertEquals("no proxy: the final outbound, like the other remote rule-sets", "PROXY", notCn.get("download_detour"));
        assertEquals(SING + "geoip/cn.srs", ruleSet(config, "rp-CN_IP").get("url"));
        List<Object> rules = list(map(config.get("route")).get("rules"));
        Map<String, Object> direct = ruleWith(rules, "\"rp-CN\"");
        assertEquals("route", direct.get("action"));
        assertEquals("DIRECT", direct.get("outbound"));
        assertEquals("logical", ruleWith(rules, "rp-CN_IP").get("type"));
        assertEquals("PROXY", ruleWith(rules, "rp-TG_IP").get("outbound"));
        assertTrue("sing-box reads the srs itself: nothing is downloaded at conversion", fetched.isEmpty());
    }

    @Test public void xrayAndV2flyUseTheirOwnDatAndInlineOnlyWhatItLacks() throws Exception {
        ProxyCoreConfig.Options o = options();
        o.geositeNames = new HashSet<>(Arrays.asList("cn", "geolocation-!cn", "private"));
        o.geoipNames = new HashSet<>(Arrays.asList("cn", "private")); // V2Fly's geoip.dat has no telegram / netflix
        ProxyCoreConfig.Result r = build(ProxyRuntimeProfile.Core.V2FLY, MRS_SOURCE, o);
        String config = r.config;
        assertTrue(config.contains("\"geosite:cn\""));
        assertTrue(config.contains("\"geosite:geolocation-!cn\""));
        assertTrue(config.contains("\"geoip:cn\""));
        assertTrue("telegram is not in the dat: its .list is inlined", config.contains("\"91.108.4.0/22\"") && config.contains("\"149.154.160.0/20\""));
        assertFalse(config.contains("geoip:telegram"));
        assertEquals(Collections.singletonList(META + "geoip/telegram.list"), fetched);
        assertFalse("a GEOIP the dat lacks would stop V2Fly at start", config.contains("geoip:netflix"));
        assertTrue(r.warnings.stream().anyMatch(w -> w.contains("geoip.dat 中没有 netflix")));
        noWarningMentions(r, "mrs");

        // Names that could not be read count as present (the dat is trusted, as before).
        fetched.clear();
        ProxyCoreConfig.Result unknown = build(ProxyRuntimeProfile.Core.XRAY, MRS_SOURCE, options());
        assertTrue(unknown.config.contains("\"geoip:telegram\""));
        assertTrue(unknown.config.contains("\"geoip:netflix\""));
        assertTrue(fetched.isEmpty());
        // Without geo assets the text twins are inlined instead.
        fetched.clear();
        ProxyCoreConfig.Options bare = options();
        bare.geoAssets = false;
        ProxyCoreConfig.Result inlined = build(ProxyRuntimeProfile.Core.XRAY, MRS_SOURCE, bare);
        assertFalse(inlined.config.contains("geosite:"));
        assertTrue(fetched.contains(META + "geosite/cn.list"));
        assertTrue(fetched.contains("https://raw.githubusercontent.com/MetaCubeX/meta-rules-dat/meta/geo/geosite/geolocation-!cn.list"));
    }

    @Test public void otherMrsSourcesUseTheirPublishedTwins() throws Exception {
        String source = NODES + "rule-providers:\n"
                + "  Steam: {type: http, behavior: domain, format: mrs, url: 'https://testingcf.jsdelivr.net/gh/peiyingyao/Rule-for-OCD@master/rule/Clash/Steam/Steam_OCD_Domain.mrs', path: ./r/s.mrs}\n"
                + "  Ads: {type: http, behavior: domain, format: mrs, proxy: DIRECT, url: 'https://gcore.jsdelivr.net/gh/217heidai/adblockfilters@main/rules/adblockmihomo.mrs', path: ./r/a.mrs}\n"
                + "  Gone: {type: http, behavior: domain, format: mrs, url: 'https://rules.fixture.test/gone.mrs', path: ./r/g.mrs}\n"
                + "proxy-groups:\n  - {name: PROXY, type: select, proxies: [HK 01]}\n"
                + "rules:\n  - RULE-SET,Ads,REJECT\n  - RULE-SET,Steam,PROXY\n  - RULE-SET,Gone,PROXY\n  - MATCH,PROXY\n";
        ProxyCoreConfig.Result sing = build(ProxyRuntimeProfile.Core.SING_BOX, source, options());
        Map<String, Object> config = json(sing);
        Map<String, Object> steam = ruleSet(config, "rp-Steam");
        assertEquals("inline", steam.get("type"));
        String steamJson = CoreJson.write(steam);
        assertTrue(steamJson.contains("steamstatic.com") && steamJson.contains("store.steampowered.com"));
        Map<String, Object> ads = ruleSet(config, "rp-Ads");
        assertEquals("https://gcore.jsdelivr.net/gh/217heidai/adblockfilters@main/rules/adblocksingbox.srs", ads.get("url"));
        assertEquals("DIRECT", ads.get("download_detour"));
        assertEquals("reject", ruleWith(list(map(config.get("route")).get("rules")), "rp-Ads").get("action"));
        assertTrue(sing.warnings.stream().anyMatch(w -> w.contains("规则集 Gone") && w.contains("无法下载")));
        assertTrue(fetched.contains("https://testingcf.jsdelivr.net/gh/peiyingyao/Rule-for-OCD@master/rule/Clash/Steam/Steam_OCD_Domain.yaml"));
        assertFalse("the ad-block srs is not fetched as text", fetched.stream().anyMatch(u -> u.contains("adblockfilters")));

        fetched.clear();
        ProxyCoreConfig.Result xray = build(ProxyRuntimeProfile.Core.XRAY, source, options());
        assertTrue(xray.config.contains("\"domain:steamstatic.com\"") && xray.config.contains("\"full:store.steampowered.com\""));
        assertTrue(xray.warnings.stream().anyMatch(w -> w.contains("规则集 Ads") && w.contains("没有当前核心可读取的版本")));
        assertFalse("the 7 MB text list is never downloaded for Xray", fetched.stream().anyMatch(u -> u.contains("adblockfilters")));
    }

    @Test public void namedDirectAndRejectExitsKeepTheirMeaning() throws Exception {
        String source = "proxies:\n"
                + "  - {name: 本地直连, type: direct, udp: true}\n"
                + "  - {name: 拒绝连接, type: reject}\n"
                + "  - {name: HK 01, type: ss, server: 192.0.2.1, port: 8388, cipher: aes-128-gcm, password: p}\n"
                + "proxy-groups:\n"
                + "  - {name: PROXY, type: select, proxies: [HK 01, 本地直连]}\n"
                + "  - {name: ADS, type: select, proxies: [拒绝连接, 本地直连]}\n"
                + "  - {name: CN, type: select, proxies: [本地直连, PROXY]}\n"
                + "rules:\n"
                + "  - DOMAIN-SUFFIX,ads.example,ADS\n"
                + "  - DOMAIN-SUFFIX,blocked.example,拒绝连接\n"
                + "  - DOMAIN-SUFFIX,cn.example,CN\n"
                + "  - DOMAIN-SUFFIX,home.example,本地直连\n"
                + "  - MATCH,PROXY\n";
        ProxyCoreConfig.Result sing = build(ProxyRuntimeProfile.Core.SING_BOX, source, options());
        noWarningMentions(sing, "本地直连", "拒绝连接 不可用");
        assertEquals("named exits are not servers", 1, sing.nodes);
        Map<String, Object> config = json(sing);
        assertEquals(Arrays.asList("HK 01", "DIRECT"), outbound(config, "PROXY").get("outbounds"));
        assertEquals("DIRECT", outbound(config, "CN").get("default"));
        assertNull("a selector that rejects by default becomes reject rules", outbound(config, "ADS"));
        assertTrue(sing.warnings.stream().anyMatch(w -> w.contains("策略组 ADS 默认拒绝连接")));
        List<Object> rules = list(map(config.get("route")).get("rules"));
        assertEquals("reject", ruleWith(rules, "ads.example").get("action"));
        assertEquals("reject", ruleWith(rules, "blocked.example").get("action"));
        assertEquals("DIRECT", ruleWith(rules, "home.example").get("outbound"));
        assertEquals("CN", ruleWith(rules, "cn.example").get("outbound"));

        ProxyCoreConfig.Result xray = build(ProxyRuntimeProfile.Core.XRAY, source, options());
        assertEquals(1, xray.nodes);
        List<Object> xrules = list(map(CoreJson.parseObject(xray.config).get("routing")).get("rules"));
        assertEquals(ProxyCoreConfig.TAG_BLOCK, ruleWith(xrules, "ads.example").get("outboundTag"));
        assertEquals(ProxyCoreConfig.TAG_BLOCK, ruleWith(xrules, "blocked.example").get("outboundTag"));
        assertEquals(ProxyCoreConfig.TAG_DIRECT, ruleWith(xrules, "cn.example").get("outboundTag"));
        assertEquals(ProxyCoreConfig.TAG_DIRECT, ruleWith(xrules, "home.example").get("outboundTag"));
    }

    @Test public void singBoxDnsFollowsTheFakeIpFilterAndNameserverPolicyRuleSets() throws Exception {
        String source = "proxies:\n"
                + "  - {name: 本地直连, type: direct}\n"
                + "  - {name: HK 01, type: ss, server: 192.0.2.1, port: 8388, cipher: aes-128-gcm, password: p}\n"
                + "rule-providers:\n"
                + "  CN: {type: http, behavior: domain, format: mrs, url: '" + META + "geosite/cn.mrs', path: ./r/cn.mrs}\n"
                + "  Bank: {type: http, behavior: domain, format: mrs, url: '" + META + "geosite/category-bank-cn.mrs', path: ./r/b.mrs}\n"
                + "  Private: {type: http, behavior: domain, format: mrs, url: '" + META + "geosite/private.mrs', path: ./r/p.mrs}\n"
                + "  CN_IP: {type: http, behavior: ipcidr, format: mrs, url: '" + META + "geoip/cn.mrs', path: ./r/ip.mrs}\n"
                + "proxy-groups:\n  - {name: PROXY, type: select, proxies: [HK 01]}\n"
                + "dns:\n"
                + "  enable: true\n"
                + "  enhanced-mode: fake-ip\n"
                + "  fake-ip-range: 198.18.0.1/16\n"
                + "  fake-ip-filter: ['rule-set:CN,Private', 'rule-set:Bank', 'rule-set:CN_IP', '+.lan']\n"
                + "  default-nameserver: [223.5.5.5]\n"
                + "  nameserver: ['https://1.1.1.1/dns-query#PROXY']\n"
                + "  nameserver-policy:\n"
                + "    'rule-set:CN': ['https://223.5.5.5/dns-query#DIRECT&disable-ipv6=true', 'https://1.12.12.12/dns-query#DIRECT']\n"
                + "    'rule-set:Bank': ['https://223.5.5.5/dns-query#本地直连']\n"
                + "    'rule-set:Private': ['https://1.1.1.1/dns-query#PROXY']\n"
                + "rules:\n  - RULE-SET,CN,DIRECT\n  - MATCH,PROXY\n";
        Map<String, Object> config = json(build(ProxyRuntimeProfile.Core.SING_BOX, source, options()));
        List<Object> rules = list(map(config.get("dns")).get("rules"));
        assertEquals(Arrays.asList("rp-CN", "rp-Bank"), map(rules.get(0)).get("rule_set"));
        assertEquals("dns-direct", map(rules.get(0)).get("server"));
        assertEquals(Collections.singletonList("rp-Private"), map(rules.get(1)).get("rule_set"));
        assertEquals("dns-remote", map(rules.get(1)).get("server"));
        assertTrue(CoreJson.write(rules.get(2)).contains("\"lan\""));
        assertEquals("dns-fake", map(rules.get(rules.size() - 1)).get("server"));
        assertFalse("an IP rule-set is no DNS condition", CoreJson.write(map(config.get("dns"))).contains("rp-CN_IP"));
        // Rule-sets only the DNS names are defined for the route as well.
        assertEquals(SING + "geosite/category-bank-cn.srs", ruleSet(config, "rp-Bank").get("url"));

        // Without fake-ip the policy alone picks the direct DNS.
        Map<String, Object> real = json(build(ProxyRuntimeProfile.Core.SING_BOX, source.replace("enhanced-mode: fake-ip", "enhanced-mode: redir-host"), options()));
        List<Object> realRules = list(map(real.get("dns")).get("rules"));
        assertEquals(1, realRules.size());
        assertEquals(Arrays.asList("rp-CN", "rp-Bank"), map(realRules.get(0)).get("rule_set"));
    }

    private static byte[] varint(int v) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        while (v >= 0x80) { out.write((v & 0x7f) | 0x80); v >>>= 7; }
        out.write(v);
        return out.toByteArray();
    }

    /** One GeoSite / GeoIP entry: country_code (field 1) followed by a body field. */
    private static byte[] entry(String name, int body) {
        ByteArrayOutputStream inner = new ByteArrayOutputStream();
        byte[] n = name.getBytes(StandardCharsets.UTF_8);
        inner.write(0x0a); inner.writeBytes(varint(n.length)); inner.writeBytes(n);
        inner.write(0x12); inner.writeBytes(varint(body)); inner.writeBytes(new byte[body]);
        ByteArrayOutputStream outer = new ByteArrayOutputStream();
        outer.write(0x0a); outer.writeBytes(varint(inner.size())); outer.writeBytes(inner.toByteArray());
        return outer.toByteArray();
    }

    @Test public void datNamesReadOnlyTheCategoryNames() throws Exception {
        File dir = Files.createTempDirectory("dat").toFile();
        File dat = new File(dir, "geosite.dat");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        bytes.writeBytes(entry("CN", 300));
        bytes.writeBytes(entry("GEOLOCATION-!CN", 5));
        Files.write(dat.toPath(), bytes.toByteArray());
        assertEquals(new HashSet<>(Arrays.asList("cn", "geolocation-!cn")), ProxyCoreConfig.datNames(dat));
        byte[] whole = bytes.toByteArray();
        Files.write(dat.toPath(), Arrays.copyOf(whole, whole.length - 3));
        assertNull("a truncated file is not a dat", ProxyCoreConfig.datNames(dat));
        Files.write(dat.toPath(), "{\"not\":\"a dat\"}".getBytes(StandardCharsets.UTF_8));
        assertNull(ProxyCoreConfig.datNames(dat));
        Files.write(dat.toPath(), new byte[0]);
        assertNull(ProxyCoreConfig.datNames(dat));
        assertNull(ProxyCoreConfig.datNames(new File(dir, "missing.dat")));
        assertTrue(ProxyCoreConfig.inDat(new HashSet<>(Collections.singletonList("microsoft")), "Microsoft@CN"));
        assertFalse(ProxyCoreConfig.inDat(new HashSet<>(Collections.singletonList("cn")), "telegram"));
        assertTrue("unknown names count as present", ProxyCoreConfig.inDat(null, "telegram"));
    }

    @Test public void snakeYamlChunkEdgeEmojiNoLongerBreaksParsing() throws Exception {
        // 🎯's high surrogate is the 1024th char: SnakeYAML 2.3 then reads past its full char[1024].
        String text = "a: '" + "x".repeat(1019) + "🎯'\nproxy-groups: []\n";
        assertTrue(Character.isHighSurrogate(text.charAt(1023)));
        try {
            new Yaml(new SafeConstructor(new LoaderOptions())).load(text);
            fail("the fixture no longer reaches SnakeYAML's chunk edge; re-check RuntimeYaml15.reader");
        } catch (IndexOutOfBoundsException expected) {
            // The library defect the reader works around.
        }
        assertNotNull(RuntimeYaml15.compose(text));
        assertEquals(text, ConfigReferenceCleaner.mihomo(text).text);
        assertEquals("x".repeat(1019) + "🎯", ProxyCoreConfig.Profile.loadYaml(text).get("a"));
        // The built-in itself, with every subscription URL length that moves its emoji across a chunk edge.
        String builtin = builtin();
        for (int pad = 0; pad < 1024; pad++) {
            String url = "https://sub.fixture.test/" + "x".repeat(pad);
            String source = builtin.replace("https://example.invalid/bichen-subscription-1", url);
            ConfigReferenceCleaner.mihomo(source);
            RuntimeYaml15.compose(source);
        }
    }

    private static String builtin() throws Exception {
        try (InputStream in = BundledProxyConfig.open()) { return new String(in.readAllBytes(), StandardCharsets.UTF_8); }
    }

    @Test public void theMihomoBuiltInConvertsWithAllItsRuleSets() throws Exception {
        String source = ProxyConfigLibrary.repairedBuiltin(builtin()).replace("https://example.invalid/bichen-subscription-1", SUB);
        ProxyCoreConfig.Result sing = build(ProxyRuntimeProfile.Core.SING_BOX, source, options());
        noWarningMentions(sing, "mrs", "规则集", "本地直连", "拒绝连接 不可用", "节点选择 没有", "手动选择 没有", "故障转移 没有", "AI 稳定 没有");
        Map<String, Object> config = json(sing);
        for (String group : Arrays.asList("节点选择", "手动选择", "故障转移"))
            assertEquals(group, Arrays.asList("HK 01 [订阅]", "JP 01 [订阅]", "US 01 [订阅]"), outbound(config, group).get("outbounds"));
        assertEquals("every rule-provider of the built-in", 36, list(map(config.get("route")).get("rule_set")).size());
        assertEquals(Arrays.asList("rp-Bank_CN", "rp-CN_域", "rp-BiliBili", "rp-DouYin"), map(list(map(config.get("dns")).get("rules")).get(0)).get("rule_set"));
        assertEquals(3, sing.nodes);

        fetched.clear();
        ProxyCoreConfig.Options v2fly = options();
        v2fly.geositeNames = null;
        v2fly.geoipNames = new HashSet<>(Arrays.asList("cn", "private"));
        ProxyCoreConfig.Result v2 = build(ProxyRuntimeProfile.Core.V2FLY, source, v2fly);
        assertTrue(v2.config.contains("\"geosite:cn\""));
        assertTrue(v2.config.contains("\"geosite:microsoft@cn\""));
        assertTrue(fetched.contains(META + "geoip/telegram.list"));
        assertTrue(fetched.contains(META + "geoip/netflix.list"));
        // Only the ad-block list has no form Xray / V2Fly read.
        for (String w : v2.warnings) if (w.contains("规则集")) assertTrue(w, w.startsWith("规则集 去广告 "));
    }

    @Test public void anUneditedBuiltInTemplateFollowsMihomosBuiltIn() throws Exception {
        for (ProxyRuntimeProfile.Core core : NON_MIHOMO) {
            String name = BundledCoreTemplates.name(core);
            library.select(core, name);
            assertEquals(name, library.selected(core).name);
            ProxyConfigLibrary.Entry source = library.source(core);
            assertEquals(ProxyRuntimeProfile.Core.MIHOMO, source.core);
            assertEquals(ProxyConfigLibrary.BUNDLED_NAME, source.name);
        }
        // A template the user filled with a server stands on its own.
        ProxyConfigLibrary.Entry xray = library.selected(ProxyRuntimeProfile.Core.XRAY);
        library.write(xray, BundledCoreTemplates.XRAY.replace(BundledCoreTemplates.PLACEHOLDER_HOST, "node.example.com"));
        assertEquals(BundledCoreTemplates.XRAY_NAME, library.source(ProxyRuntimeProfile.Core.XRAY).name);
        assertEquals(ProxyRuntimeProfile.Core.XRAY, library.source(ProxyRuntimeProfile.Core.XRAY).core);
        // So does the user's own file, and Mihomo keeps its own selection.
        ProxyConfigLibrary.Entry own = library.importConfig(ProxyRuntimeProfile.Core.SING_BOX, "mine.json",
                new ByteArrayInputStream("{\"outbounds\":[{\"type\":\"direct\",\"tag\":\"d\"}]}".getBytes(StandardCharsets.UTF_8)));
        assertEquals(own.name, library.source(ProxyRuntimeProfile.Core.SING_BOX).name);
        assertEquals(library.selected(ProxyRuntimeProfile.Core.MIHOMO).name, library.source(ProxyRuntimeProfile.Core.MIHOMO).name);
    }

    @Test public void theBuiltInSubscriptionSlotIsAPlaceholderUntilFilled() throws Exception {
        ProxyConfigLibrary.Entry mihomo = library.selected(ProxyRuntimeProfile.Core.MIHOMO);
        List<ProxyConfigLibrary.Subscription> slots = library.subscriptions(mihomo);
        assertEquals(1, slots.size());
        assertTrue("the shipped example.invalid slot is unfilled", slots.get(0).placeholder);
        assertFalse(library.hasConfiguredSubscription(mihomo));
        library.select(ProxyRuntimeProfile.Core.SING_BOX, BundledCoreTemplates.SING_BOX_NAME);
        assertFalse(library.hasConfiguredSubscription(library.source(ProxyRuntimeProfile.Core.SING_BOX)));
        // Filling it once serves Mihomo and every core whose built-in follows Mihomo's.
        library.updateSubscription(library.source(ProxyRuntimeProfile.Core.SING_BOX), slots.get(0).name, SUB);
        assertTrue(library.hasConfiguredSubscription(mihomo));
        assertEquals(SUB, library.subscriptions(library.source(ProxyRuntimeProfile.Core.SING_BOX)).get(0).url);
        assertTrue(BundledCoreTemplates.hasPlaceholderNode(library.read(library.selected(ProxyRuntimeProfile.Core.SING_BOX))));
    }

    @Test public void theBuiltInNoJunkFilterNoLongerEmptiesTheMainGroups() throws Exception {
        String shipped = builtin();
        assertTrue("4.4.4 and earlier shipped the old line", shipped.contains(ProxyConfigLibrary.OLD_NO_JUNK_FILTER));
        String repaired = ProxyConfigLibrary.repairedBuiltin(shipped);
        assertFalse(repaired.contains(ProxyConfigLibrary.OLD_NO_JUNK_FILTER));
        assertTrue(repaired.contains(ProxyConfigLibrary.NO_JUNK_FILTER));
        assertEquals("only that line changes", shipped.replace(ProxyConfigLibrary.OLD_NO_JUNK_FILTER, ""), repaired.replace(ProxyConfigLibrary.NO_JUNK_FILTER, ""));
        // The provider filter, which runs before the " [订阅]" suffix, still drops announcement entries.
        String provider = shipped.substring(shipped.indexOf("BaseProvider: &BaseProvider"), shipped.indexOf("proxy-providers:"));
        assertTrue(provider.contains("exclude-filter:") && provider.contains("订阅") && provider.contains("剩余"));
        // Under the old line every 订阅 node is excluded from 节点选择 (Mihomo's semantics, which the converter keeps).
        String old = shipped.replace("https://example.invalid/bichen-subscription-1", SUB);
        ProxyCoreConfig.Result before = build(ProxyRuntimeProfile.Core.SING_BOX, old, options());
        assertTrue(before.warnings.stream().anyMatch(w -> w.contains("策略组 节点选择 没有可用成员")));
    }

    @Test public void theLibraryWritesAndRepairsTheBuiltIn() throws Exception {
        ProxyConfigLibrary.Entry fresh = library.selected(ProxyRuntimeProfile.Core.MIHOMO);
        String text = library.read(fresh);
        assertTrue(text.contains(ProxyConfigLibrary.NO_JUNK_FILTER));
        assertFalse(text.contains(ProxyConfigLibrary.OLD_NO_JUNK_FILTER));
        // An existing built-in from 4.4.4 with the user's subscription: only the filter line changes.
        String user = builtin().replace("https://example.invalid/bichen-subscription-1", SUB);
        Files.write(fresh.file.toPath(), user.getBytes(StandardCharsets.UTF_8));
        assertTrue(fresh.file.setLastModified(fresh.file.lastModified() - 10_000));
        ProxyConfigLibrary again = new ProxyConfigLibrary(ApplicationProvider.getApplicationContext());
        String repaired = again.read(again.selected(ProxyRuntimeProfile.Core.MIHOMO));
        assertEquals(ProxyConfigLibrary.repairedBuiltin(user), repaired);
        assertEquals(SUB, again.subscriptions(again.selected(ProxyRuntimeProfile.Core.MIHOMO)).get(0).url);
        // A filter the user rewrote is theirs.
        String custom = user.replace(ProxyConfigLibrary.OLD_NO_JUNK_FILTER, "NoJunkFilter: &NoJunkFilter '(?i)(到期|订阅)'");
        Files.write(fresh.file.toPath(), custom.getBytes(StandardCharsets.UTF_8));
        assertTrue(fresh.file.setLastModified(fresh.file.lastModified() - 20_000));
        ProxyConfigLibrary third = new ProxyConfigLibrary(ApplicationProvider.getApplicationContext());
        assertEquals(custom, third.read(third.selected(ProxyRuntimeProfile.Core.MIHOMO)));
    }
}
