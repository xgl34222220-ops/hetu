package io.github.xgl34222220.hetu;

import android.app.Application;
import androidx.test.core.app.ApplicationProvider;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/**
 * 2026-10-18 用户反馈：其它核心根本启动不了。sing-box 1.14.3 启动时下载全部远程规则集，任一下载失败即退出
 * （FATAL start router: initialize rule-set[n]）。河图在启动前自己下载（镜像、上次成功副本），配置改写为部署后的本地文件；
 * 拿不到的规则集在转换配置中连同依赖它的规则一起跳过（与 Mihomo 缺少 provider 时照常启动一致），原生配置保留远程。
 * 另：选了其它核心而保存的模式是 TUN / Enhance / eBPF 时，启动不再把核心改回 Mihomo，只把模式换成 TPROXY；内置模板跟随
 * Mihomo 当前选中的配置（而不只是内置配置）；Hysteria 先监听再连服务器；Xray / V2Fly 自己应答看门狗的存活查询。
 * 5.5.5：Enhance 对所有核心开放，sing-box 可用自己的 TUN；DNS 端口改为监听回环地址（另加通配地址给热点设备），
 * 应答从被 REDIRECT 到的地址发出，Android 解析器的已连接 UDP 套接字才收得到（之前其它核心的应用 DNS 全部超时）。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {35}, application = Application.class)
public class CoreStart510Test {
    private static final String SING = "https://testingcf.jsdelivr.net/gh/MetaCubeX/meta-rules-dat@sing/geo/";
    private static final String DIR = "/data/adb/hetu/run/ruleset/sing";
    private static final byte[] SRS = "SRS\u0002body".getBytes(StandardCharsets.ISO_8859_1);

    @SuppressWarnings("unchecked") private static Map<String, Object> map(Object o) { return (Map<String, Object>) o; }
    @SuppressWarnings("unchecked") private static List<Object> list(Object o) { return (List<Object>) o; }

    private static Map<String, Object> remote(String tag, String url) {
        return ProxyCoreConfig.map("type", "remote", "tag", tag, "format", "binary", "url", url, "download_detour", "DIRECT", "update_interval", "72h");
    }

    /** A converted-shaped config: CN direct, a NotCN proxy rule, an AND, an OR, an inverted rule and a DNS rule. */
    private static String config() {
        List<Object> sets = Arrays.asList(remote("rp-CN", SING + "geosite/cn.srs"), remote("rp-NotCN", SING + "geosite/geolocation-!cn.srs"),
                remote("rp-CN_IP", SING + "geoip/cn.srs"),
                ProxyCoreConfig.map("type", "inline", "tag", "hetu-adblock", "rules", Collections.singletonList(ProxyCoreConfig.map("domain_suffix", Collections.singletonList("ads.test")))));
        List<Object> rules = Arrays.asList(
                ProxyCoreConfig.map("action", "sniff"),
                ProxyCoreConfig.map("rule_set", Collections.singletonList("hetu-adblock"), "action", "reject"),
                ProxyCoreConfig.map("rule_set", Collections.singletonList("rp-CN"), "action", "route", "outbound", "DIRECT"),
                ProxyCoreConfig.map("rule_set", Arrays.asList("rp-NotCN", "rp-CN"), "action", "route", "outbound", "PROXY"),
                ProxyCoreConfig.map("domain_suffix", Collections.singletonList("cn"), "rule_set", Collections.singletonList("rp-CN"), "action", "route", "outbound", "DIRECT"),
                ProxyCoreConfig.map("type", "logical", "mode", "and", "rules", Arrays.asList(ProxyCoreConfig.map("port", Collections.singletonList(853)),
                        ProxyCoreConfig.map("rule_set", Collections.singletonList("rp-CN_IP"))), "action", "route", "outbound", "DIRECT"),
                ProxyCoreConfig.map("type", "logical", "mode", "or", "rules", Arrays.asList(ProxyCoreConfig.map("rule_set", Collections.singletonList("rp-CN")),
                        ProxyCoreConfig.map("domain_suffix", Collections.singletonList("example.cn"))), "action", "route", "outbound", "DIRECT"),
                ProxyCoreConfig.map("rule_set", Collections.singletonList("rp-CN"), "invert", true, "action", "route", "outbound", "PROXY"));
        Map<String, Object> root = ProxyCoreConfig.map(
                "dns", ProxyCoreConfig.map("rules", Collections.singletonList(ProxyCoreConfig.map("rule_set", Collections.singletonList("rp-CN"), "action", "route", "server", "dns-direct"))),
                "route", ProxyCoreConfig.map("rules", rules, "rule_set", sets, "final", "PROXY"));
        return CoreJson.write(root);
    }

    private static ProxyCoreConfig.LocalRuleSets localize(String json, ProxyCoreConfig.BinaryFetcher f, boolean drop) throws IOException {
        return ProxyCoreConfig.localizeSingBoxRuleSets(json, f, DIR, drop, 4, 10_000L);
    }

    @Test public void everyRemoteRuleSetBecomesTheDeployedLocalFile() throws Exception {
        List<String> asked = Collections.synchronizedList(new ArrayList<>());
        ProxyCoreConfig.LocalRuleSets l = localize(config(), url -> { asked.add(url); return SRS; }, true);
        assertTrue(l.missing.isEmpty());
        assertEquals(3, l.files.size());
        assertEquals(3, asked.size());
        Map<String, Object> route = map(CoreJson.parseObject(l.config).get("route"));
        int local = 0;
        for (Object o : list(route.get("rule_set"))) {
            Map<String, Object> s = map(o);
            assertNotEquals("no remote rule-set is left for sing-box to download at start", "remote", s.get("type"));
            if ("local".equals(s.get("type"))) {
                local++;
                assertEquals("binary", s.get("format"));
                assertTrue(String.valueOf(s.get("path")).startsWith(DIR + "/"));
                assertFalse("no download options on a local rule-set", s.containsKey("url") || s.containsKey("download_detour"));
            }
        }
        assertEquals(3, local);
        assertEquals("every rule stays", 8, list(route.get("rules")).size());
        Map<String, Object> inline = null;
        for (Object o : list(route.get("rule_set"))) if ("hetu-adblock".equals(map(o).get("tag"))) inline = map(o);
        assertEquals("inline rule-sets are untouched", "inline", inline.get("type"));
        for (ProxyCoreConfig.RuleSetFile f : l.files) assertArrayEquals(SRS, f.body);
        assertEquals("one URL, one stable file", ProxyCoreConfig.ruleSetFileName(SING + "geosite/cn.srs", "binary"),
                ProxyCoreConfig.ruleSetFileName(SING + "geosite/cn.srs", "binary"));
    }

    @Test public void aRuleSetThatCannotBeFetchedIsDroppedWithTheRulesThatNeedIt() throws Exception {
        ProxyCoreConfig.LocalRuleSets l = localize(config(), url -> url.contains("geosite/cn.srs") || url.contains("geoip/cn.srs") ? null : SRS, true);
        assertEquals(new HashSet<>(Arrays.asList("rp-CN", "rp-CN_IP")), new HashSet<>(l.missing));
        Map<String, Object> root = CoreJson.parseObject(l.config);
        Map<String, Object> route = map(root.get("route"));
        Set<String> tags = new HashSet<>();
        for (Object o : list(route.get("rule_set"))) tags.add(String.valueOf(map(o).get("tag")));
        assertEquals(new HashSet<>(Arrays.asList("rp-NotCN", "hetu-adblock")), tags);
        String rules = CoreJson.write(route.get("rules"));
        assertFalse(rules, rules.contains("rp-CN\"") || rules.contains("rp-CN_IP"));
        List<Object> kept = list(route.get("rules"));
        assertEquals("sniff, adblock, NotCN (CN removed from its list), domain_suffix cn (rule-set removed), OR without the rule-set",
                5, kept.size());
        assertEquals(Collections.singletonList("rp-NotCN"), map(kept.get(2)).get("rule_set"));
        assertEquals(Collections.singletonList("cn"), map(kept.get(3)).get("domain_suffix"));
        assertFalse(map(kept.get(3)).containsKey("rule_set"));
        assertEquals("logical", map(kept.get(4)).get("type"));
        assertEquals("or", map(kept.get(4)).get("mode"));
        assertEquals(1, list(map(kept.get(4)).get("rules")).size());
        assertFalse("an AND that needs the rule-set and an inverted rule never match more than before: both dropped",
                rules.contains("853") || rules.contains("invert"));
        assertTrue("the DNS rule that needed the set is gone", list(map(root.get("dns")).get("rules")).isEmpty());
    }

    @Test public void aNativeConfigKeepsAnUnfetchedRuleSetRemote() throws Exception {
        ProxyCoreConfig.LocalRuleSets l = localize(config(), url -> url.contains("geoip/cn.srs") ? null : SRS, false);
        assertEquals(Collections.singletonList("rp-CN_IP"), l.missing);
        Map<String, Object> route = map(CoreJson.parseObject(l.config).get("route"));
        Map<String, Object> cnIp = null;
        for (Object o : list(route.get("rule_set"))) if ("rp-CN_IP".equals(map(o).get("tag"))) cnIp = map(o);
        assertNotNull(cnIp);
        assertEquals("remote", cnIp.get("type"));
        assertEquals("the user's rules are not changed", 8, list(route.get("rules")).size());
    }

    @Test public void anHtmlErrorPageIsNotARuleSet() throws Exception {
        ProxyCoreConfig.LocalRuleSets l = localize(config(), url -> "<html>404</html>".getBytes(StandardCharsets.UTF_8), true);
        assertEquals(3, l.missing.size());
        assertTrue(l.files.isEmpty());
        assertTrue(ProxyCoreConfig.ruleSetBody(SRS));
        assertTrue(ProxyCoreConfig.ruleSetBody(" {\"version\":3}".getBytes(StandardCharsets.UTF_8), "source"));
        assertFalse(ProxyCoreConfig.ruleSetBody(" {\"version\":3}".getBytes(StandardCharsets.UTF_8), "binary"));
        assertFalse(ProxyCoreConfig.ruleSetBody(new byte[0]));
    }

    @Test public void aSlowMirrorCannotHoldTheStartLongerThanTheBudget() throws Exception {
        CountDownLatch never = new CountDownLatch(1);
        AtomicInteger fast = new AtomicInteger();
        long t0 = System.nanoTime();
        ProxyCoreConfig.LocalRuleSets l = ProxyCoreConfig.localizeSingBoxRuleSets(config(), url -> {
            if (url.contains("geoip/cn.srs")) { try { never.await(); } catch (InterruptedException stopped) { throw new IOException(stopped); } return SRS; }
            fast.incrementAndGet(); return SRS;
        }, DIR, true, 4, 600L);
        long ms = (System.nanoTime() - t0) / 1_000_000L;
        assertTrue("returned after " + ms + " ms", ms < 5_000L);
        assertEquals(Collections.singletonList("rp-CN_IP"), l.missing);
        assertEquals(2, fast.get());
        assertEquals(2, l.files.size());
    }

    @Test public void githubRuleSetsHaveTheirJsDelivrAndRawMirrors() {
        List<String> m = ProxyCoreConfig.ruleSetMirrors(SING + "geoip/cn.srs");
        assertEquals(SING + "geoip/cn.srs", m.get(0));
        assertTrue(m.contains("https://fastly.jsdelivr.net/gh/MetaCubeX/meta-rules-dat@sing/geo/geoip/cn.srs"));
        assertTrue(m.contains("https://raw.githubusercontent.com/MetaCubeX/meta-rules-dat/sing/geo/geoip/cn.srs"));
        assertEquals("no duplicates", new LinkedHashSet<>(m).size(), m.size());
        List<String> raw = ProxyCoreConfig.ruleSetMirrors(String.format(Locale.ROOT, ProxyCoreConfig.SING_GEOSITE, "netflix"));
        assertEquals("https://raw.githubusercontent.com/SagerNet/sing-geosite/rule-set/geosite-netflix.srs", raw.get(0));
        assertTrue(raw.contains("https://testingcf.jsdelivr.net/gh/SagerNet/sing-geosite@rule-set/geosite-netflix.srs"));
        assertEquals(Collections.singletonList("https://rules.example.test/a.srs"), ProxyCoreConfig.ruleSetMirrors("https://rules.example.test/a.srs"));
    }

    @Test public void aConfigWithoutRemoteRuleSetsIsReturnedUnchanged() throws Exception {
        String json = CoreJson.write(ProxyCoreConfig.map("route", ProxyCoreConfig.map("rules", new ArrayList<>(), "final", "DIRECT")));
        ProxyCoreConfig.LocalRuleSets l = localize(json, url -> { throw new AssertionError("nothing to fetch"); }, true);
        assertSame(json, l.config);
        assertTrue(l.files.isEmpty() && l.missing.isEmpty());
    }

    @Test public void aNonMihomoCoreKeepsItsCoreAndRunsInTproxyInsteadOfTurningIntoMihomo() throws Exception {
        // 5.5.5: Enhance runs on every core, TUN on sing-box (its own TUN); eBPF stays Mihomo-only.
        assertEquals(ProxyRuntimeProfile.Mode.TPROXY, ProxyRuntimeProfile.runnableMode(ProxyRuntimeProfile.Core.SING_BOX, ProxyRuntimeProfile.Mode.EBPF));
        assertEquals(ProxyRuntimeProfile.Mode.TUN, ProxyRuntimeProfile.runnableMode(ProxyRuntimeProfile.Core.SING_BOX_REF1ND, ProxyRuntimeProfile.Mode.TUN));
        for (ProxyRuntimeProfile.Mode m : new ProxyRuntimeProfile.Mode[]{ProxyRuntimeProfile.Mode.TUN, ProxyRuntimeProfile.Mode.EBPF})
            for (ProxyRuntimeProfile.Core c : new ProxyRuntimeProfile.Core[]{ProxyRuntimeProfile.Core.XRAY, ProxyRuntimeProfile.Core.V2FLY, ProxyRuntimeProfile.Core.HYSTERIA})
                assertEquals(c.label + " " + m.label, ProxyRuntimeProfile.Mode.TPROXY, ProxyRuntimeProfile.runnableMode(c, m));
        for (ProxyRuntimeProfile.Core c : ProxyRuntimeProfile.Core.values())
            assertEquals(c.label, ProxyRuntimeProfile.Mode.ENHANCE, ProxyRuntimeProfile.runnableMode(c, ProxyRuntimeProfile.Mode.ENHANCE));
        assertEquals(ProxyRuntimeProfile.Mode.REDIRECT, ProxyRuntimeProfile.runnableMode(ProxyRuntimeProfile.Core.XRAY, ProxyRuntimeProfile.Mode.REDIRECT));
        assertEquals(ProxyRuntimeProfile.Mode.TUN, ProxyRuntimeProfile.runnableMode(ProxyRuntimeProfile.Core.MIHOMO, ProxyRuntimeProfile.Mode.TUN));
        assertEquals(ProxyRuntimeProfile.Mode.TPROXY, ProxyRuntimeProfile.runnableMode(ProxyRuntimeProfile.Core.MIHOMO, ProxyRuntimeProfile.Mode.MIXED));
        String start = new String(Files.readAllBytes(new File("src/main/java/io/github/xgl34222220/hetu/ProxyComposeController.kt").toPath()), StandardCharsets.UTF_8);
        String body = start.substring(start.indexOf("suspend fun start(onProgress"), start.indexOf("configs.selected(profile.core) ?: throw ProxyStartProblem.NoConfig()"));
        assertFalse("an unavailable mode never rewrites the chosen core", body.contains("putString(\"proxyBaseCore\", \"mihomo\").putString(\"proxyBaseMode\""));
        assertTrue(body.contains("ProxyRuntimeProfile.runnableMode(profile.core, profile.mode)"));
    }

    @Test public void theBuiltInTemplateFollowsTheConfigMihomoActuallyStartsFrom() throws Exception {
        Application app = ApplicationProvider.getApplicationContext();
        app.getSharedPreferences("hetu", 0).edit().clear().commit();
        ProxyConfigLibrary library = new ProxyConfigLibrary(app);
        library.select(ProxyRuntimeProfile.Core.SING_BOX, BundledCoreTemplates.SING_BOX_NAME);
        assertEquals(ProxyConfigLibrary.BUNDLED_NAME, library.source(ProxyRuntimeProfile.Core.SING_BOX).name);
        ProxyConfigLibrary.Entry mine = library.importConfig(ProxyRuntimeProfile.Core.MIHOMO, "airport.yaml",
                new ByteArrayInputStream("proxies:\n  - {name: HK, type: ss, server: 192.0.2.1, port: 8388, cipher: aes-128-gcm, password: p}\nrules:\n  - MATCH,HK\n"
                        .getBytes(StandardCharsets.UTF_8)));
        assertEquals(mine.name, library.selected(ProxyRuntimeProfile.Core.MIHOMO).name);
        ProxyConfigLibrary.Entry followed = library.source(ProxyRuntimeProfile.Core.SING_BOX);
        assertEquals(ProxyRuntimeProfile.Core.MIHOMO, followed.core);
        assertEquals("the imported Mihomo config, not the built-in that never got a subscription", "airport.yaml", followed.name);
        assertTrue("no placeholder subscription refuses the start", library.hasConfiguredSubscription(followed));
    }

    @Test public void hysteriaListensBeforeItReachesTheServerAndXrayAnswersTheLivenessName() throws Exception {
        ProxyCoreConfig.Options o = new ProxyCoreConfig.Options();
        o.tproxyPort = 19898; o.dnsPort = 11053; o.controllerPort = 29090; o.egressPort = 29150; o.secret = "s";
        String hy2 = "proxies:\n  - {name: HY, type: hysteria2, server: 192.0.2.9, port: 443, password: pw-long, sni: a.example.com}\nrules:\n  - MATCH,HY\n";
        String hysteria = ProxyCoreConfig.build(ProxyRuntimeProfile.Core.HYSTERIA, hy2, o, u -> null).config;
        assertTrue(hysteria, hysteria.contains("\nlazy: true\n"));
        String nativeLazy = ProxyCoreConfig.build(ProxyRuntimeProfile.Core.HYSTERIA, "server: 192.0.2.9:443\nauth: x\nlazy: false\n", o, u -> null).config;
        assertEquals("the user's own lazy stays the only one", 1, nativeLazy.split("\nlazy:", -1).length - 1);
        String ss = "proxies:\n  - {name: HK, type: ss, server: 192.0.2.1, port: 8388, cipher: aes-128-gcm, password: p}\nrules:\n  - MATCH,HK\n";
        for (ProxyRuntimeProfile.Core core : new ProxyRuntimeProfile.Core[]{ProxyRuntimeProfile.Core.XRAY, ProxyRuntimeProfile.Core.V2FLY}) {
            Map<String, Object> dns = map(CoreJson.parseObject(ProxyCoreConfig.build(core, ss, o, u -> null).config).get("dns"));
            assertEquals(core.label, "127.0.0.1", map(dns.get("hosts")).get(ProxyCoreConfig.LIVENESS_HOST));
            Map<String, Object> own = map(CoreJson.parseObject(ProxyCoreConfig.build(core,
                    "{\"outbounds\":[{\"protocol\":\"freedom\",\"tag\":\"d\"}],\"dns\":{\"hosts\":{\"a.test\":\"10.0.0.1\"},\"servers\":[\"1.1.1.1\"]}}", o, u -> null).config).get("dns"));
            assertEquals("the user's hosts are kept", "10.0.0.1", map(own.get("hosts")).get("a.test"));
            assertEquals("127.0.0.1", map(own.get("hosts")).get(ProxyCoreConfig.LIVENESS_HOST));
        }
    }

    @Test public void fakeIpAnswersAreRecognisedSoHysteriaNeverDialsOne() {
        assertTrue(ProxyCoreConfig.inIpv4Cidrs("198.18.0.7", ProxyCoreConfig.FAKE_IP_POOLS_V4));
        assertTrue(ProxyCoreConfig.inIpv4Cidrs("198.19.255.1", ProxyCoreConfig.FAKE_IP_POOLS_V4));
        assertFalse(ProxyCoreConfig.inIpv4Cidrs("198.20.0.1", ProxyCoreConfig.FAKE_IP_POOLS_V4));
        assertTrue(ProxyCoreConfig.inIpv4Cidrs("28.0.0.9", ProxyCoreConfig.FAKE_IP_POOLS_V4 + ",28.0.0.1/8"));
        assertFalse(ProxyCoreConfig.inIpv4Cidrs("203.0.113.5", ProxyCoreConfig.FAKE_IP_POOLS_V4 + ","));
        assertFalse(ProxyCoreConfig.inIpv4Cidrs("2001:db8::1", "0.0.0.0/0"));
        assertTrue(ProxyCoreConfig.inIpv4Cidrs("203.0.113.5", "0.0.0.0/0"));
        assertFalse(ProxyCoreConfig.inIpv4Cidrs("256.1.1.1", "0.0.0.0/0"));
    }

    @Test public void pickingAnotherCoreRemembersMihomosModeAndPickingMihomoBringsItBack() {
        Application app = ApplicationProvider.getApplicationContext();
        android.content.SharedPreferences p = app.getSharedPreferences("hetu", 0);
        p.edit().clear().commit();
        assertEquals(ProxyRuntimeProfile.Mode.TPROXY, ProxyRuntimeProfile.modeForPick(p, ProxyRuntimeProfile.Core.XRAY, ProxyRuntimeProfile.Mode.TUN));
        assertEquals("tun", p.getString(ProxyRuntimeProfile.MIHOMO_MODE_KEY, null));
        assertEquals(ProxyRuntimeProfile.Mode.TUN, ProxyRuntimeProfile.modeForPick(p, ProxyRuntimeProfile.Core.MIHOMO, ProxyRuntimeProfile.Mode.TPROXY));
        assertNull(p.getString(ProxyRuntimeProfile.MIHOMO_MODE_KEY, null));
        // A mode the user chose for the other core meanwhile is kept, and the memory is spent.
        ProxyRuntimeProfile.modeForPick(p, ProxyRuntimeProfile.Core.XRAY, ProxyRuntimeProfile.Mode.EBPF);
        assertEquals(ProxyRuntimeProfile.Mode.REDIRECT, ProxyRuntimeProfile.modeForPick(p, ProxyRuntimeProfile.Core.MIHOMO, ProxyRuntimeProfile.Mode.REDIRECT));
        assertNull(p.getString(ProxyRuntimeProfile.MIHOMO_MODE_KEY, null));
        // A mode the picked core runs is neither changed nor remembered.
        assertEquals(ProxyRuntimeProfile.Mode.REDIRECT, ProxyRuntimeProfile.modeForPick(p, ProxyRuntimeProfile.Core.HYSTERIA, ProxyRuntimeProfile.Mode.REDIRECT));
        assertEquals(ProxyRuntimeProfile.Mode.ENHANCE, ProxyRuntimeProfile.modeForPick(p, ProxyRuntimeProfile.Core.V2FLY, ProxyRuntimeProfile.Mode.ENHANCE));
        assertEquals(ProxyRuntimeProfile.Mode.TUN, ProxyRuntimeProfile.modeForPick(p, ProxyRuntimeProfile.Core.SING_BOX, ProxyRuntimeProfile.Mode.TUN));
        assertNull(p.getString(ProxyRuntimeProfile.MIHOMO_MODE_KEY, null));
    }

    private static final String SS = "proxies:\n  - {name: HK, type: ss, server: 192.0.2.1, port: 8388, cipher: aes-128-gcm, password: p}\nrules:\n  - MATCH,HK\n";

    private static ProxyCoreConfig.Options modeOptions(ProxyRuntimeProfile.Mode mode) {
        ProxyCoreConfig.Options o = new ProxyCoreConfig.Options();
        o.mode = mode; o.dnsPort = 11053; o.controllerPort = 29090; o.egressPort = 29150; o.secret = "s";
        if (mode == ProxyRuntimeProfile.Mode.TPROXY) o.tproxyPort = 19898;
        if (mode == ProxyRuntimeProfile.Mode.REDIRECT || mode == ProxyRuntimeProfile.Mode.ENHANCE) o.redirectPort = 19897;
        if (mode == ProxyRuntimeProfile.Mode.ENHANCE) o.tproxyPort = 19898;
        return o;
    }

    private static Map<String, Object> inbound(List<Object> inbounds, String tag) {
        for (Object i : inbounds) if (tag.equals(map(i).get("tag"))) return map(i);
        return null;
    }

    @Test public void enhanceTakesTcpThroughRedirectAndUdpThroughTproxyOnEveryCore() throws Exception {
        ProxyCoreConfig.Options o = modeOptions(ProxyRuntimeProfile.Mode.ENHANCE);
        List<Object> sing = list(CoreJson.parseObject(ProxyCoreConfig.build(ProxyRuntimeProfile.Core.SING_BOX, SS, o, u -> null).config).get("inbounds"));
        assertEquals("udp", inbound(sing, ProxyCoreConfig.TAG_TPROXY).get("network"));
        assertEquals(19897, ((Number) inbound(sing, ProxyCoreConfig.TAG_REDIRECT).get("listen_port")).intValue());
        assertNull("no TUN outside TUN mode", inbound(sing, ProxyCoreConfig.TAG_TUN));
        List<Object> xray = list(CoreJson.parseObject(ProxyCoreConfig.build(ProxyRuntimeProfile.Core.XRAY, SS, o, u -> null).config).get("inbounds"));
        assertEquals("udp", map(inbound(xray, ProxyCoreConfig.TAG_TPROXY).get("settings")).get("network"));
        assertEquals("tcp", map(inbound(xray, ProxyCoreConfig.TAG_REDIRECT).get("settings")).get("network"));
        String hy2 = "proxies:\n  - {name: HY, type: hysteria2, server: 192.0.2.9, port: 443, password: pw-long, sni: a.example.com}\nrules:\n  - MATCH,HY\n";
        String hysteria = ProxyCoreConfig.build(ProxyRuntimeProfile.Core.HYSTERIA, hy2, o, u -> null).config;
        assertTrue(hysteria, hysteria.contains("udpTProxy:\n  listen: :19898\n") && hysteria.contains("tcpRedirect:\n  listen: :19897\n"));
        assertFalse("TCP is redirected, not TPROXY'd", hysteria.contains("tcpTProxy:"));
        // TPROXY mode is unchanged: one listener for TCP and UDP.
        List<Object> tproxy = list(CoreJson.parseObject(ProxyCoreConfig.build(ProxyRuntimeProfile.Core.SING_BOX, SS,
                modeOptions(ProxyRuntimeProfile.Mode.TPROXY), u -> null).config).get("inbounds"));
        assertFalse(inbound(tproxy, ProxyCoreConfig.TAG_TPROXY).containsKey("network"));
    }

    @Test public void singBoxTunModeRunsItsOwnTunLikeMihomosTunBlock() throws Exception {
        ProxyCoreConfig.Options o = modeOptions(ProxyRuntimeProfile.Mode.TUN);
        o.tunExcludePackages = Arrays.asList("com.example.bank", "com.tencent.mm");
        Map<String, Object> root = CoreJson.parseObject(ProxyCoreConfig.build(ProxyRuntimeProfile.Core.SING_BOX, SS, o, u -> null).config);
        List<Object> inbounds = list(root.get("inbounds"));
        Map<String, Object> tun = inbound(inbounds, ProxyCoreConfig.TAG_TUN);
        assertNotNull(tun);
        assertEquals("tun", tun.get("type"));
        assertEquals("hetu0", tun.get("interface_name"));
        assertEquals(Boolean.TRUE, tun.get("auto_route"));
        assertEquals(Boolean.TRUE, tun.get("strict_route"));
        assertEquals(Collections.singletonList("0:9999"), tun.get("exclude_uid_range"));
        assertTrue(((List<?>) tun.get("route_exclude_address")).containsAll(Arrays.asList("192.168.0.0/16", "10.0.0.0/8", "fc00::/7")));
        assertEquals(Arrays.asList("com.example.bank", "com.tencent.mm"), tun.get("exclude_package"));
        assertFalse(tun.containsKey("include_package"));
        assertEquals(2, ((List<?>) tun.get("address")).size());
        assertNull("no netfilter listener in TUN mode", inbound(inbounds, ProxyCoreConfig.TAG_TPROXY));
        assertNull(inbound(inbounds, ProxyCoreConfig.TAG_REDIRECT));
        assertNotNull("Hetu's DNS port stays", inbound(inbounds, ProxyCoreConfig.TAG_DNS_IN));
        Map<String, Object> route = map(root.get("route"));
        assertEquals(Boolean.TRUE, route.get("auto_detect_interface"));
        List<Object> rules = list(route.get("rules"));
        assertTrue(((List<?>) map(rules.get(0)).get("inbound")).contains(ProxyCoreConfig.TAG_TUN));
        assertEquals("sniff", map(rules.get(0)).get("action"));
        boolean dnsHijack = false;
        for (Object r : rules) if ("dns".equals(map(r).get("protocol")) && "hijack-dns".equals(map(r).get("action"))) dnsHijack = true;
        assertTrue("DNS inside the TUN is answered by sing-box", dnsHijack);

        // A native sing-box file: Hetu's TUN replaces its inbounds, and auto_detect_interface is forced on.
        o.ipv6 = false; o.tunExcludePackages = Collections.emptyList(); o.tunIncludePackages = Collections.singletonList("org.example.browser");
        String own = "{\"inbounds\":[{\"type\":\"mixed\",\"listen_port\":7890}],\"outbounds\":[{\"type\":\"direct\",\"tag\":\"d\"}],\"route\":{\"auto_detect_interface\":false}}";
        ProxyCoreConfig.Result r = ProxyCoreConfig.build(ProxyRuntimeProfile.Core.SING_BOX, own, o, u -> null);
        Map<String, Object> nat = CoreJson.parseObject(r.config);
        Map<String, Object> ntun = inbound(list(nat.get("inbounds")), ProxyCoreConfig.TAG_TUN);
        assertEquals(Collections.singletonList("org.example.browser"), ntun.get("include_package"));
        assertEquals(Collections.singletonList(ProxyCoreConfig.TUN_ADDRESS_V4), ntun.get("address"));
        assertEquals(Boolean.TRUE, map(nat.get("route")).get("auto_detect_interface"));
        assertTrue(r.warningText(), r.warningText().contains("auto_detect_interface"));
    }

    @Test public void tunAppScopeFollowsMihomosIncludeAndExcludeRule() throws Exception {
        Set<String> picked = new TreeSet<>(Arrays.asList("com.b", "com.a"));
        Set<String> direct = Collections.singleton("com.d");
        assertEquals(Arrays.asList(Collections.emptyList(), Arrays.asList("com.a", "com.b", "com.d")),
                ProxyCoreConfig.tunPackages(ProxyRuntimeProfile.AppScope.BLACKLIST, picked, direct));
        assertEquals(Arrays.asList(Collections.emptyList(), Collections.singletonList("com.d")),
                ProxyCoreConfig.tunPackages(ProxyRuntimeProfile.AppScope.CORE, picked, direct));
        assertEquals(Arrays.asList(Arrays.asList("com.a", "com.b"), Collections.emptyList()),
                ProxyCoreConfig.tunPackages(ProxyRuntimeProfile.AppScope.WHITELIST, new TreeSet<>(Arrays.asList("com.a", "com.b", "com.d")), direct));
        try {
            ProxyCoreConfig.tunPackages(ProxyRuntimeProfile.AppScope.WHITELIST, Collections.singleton("com.d"), direct);
            fail("a whitelist emptied by DIRECT rules would route nothing");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("DIRECT"));
        }
        // The startup options take the packages only in TUN mode, from the same policy the start uses.
        String root = new String(Files.readAllBytes(new File("src/main/java/io/github/xgl34222220/hetu/RootProxyManager.java").toPath()), StandardCharsets.UTF_8);
        String options = root.substring(root.indexOf("private ProxyCoreConfig.Options nativeOptions("), root.indexOf("o.dnsPort=profile.dnsHijack"));
        assertTrue(options, options.contains("ProxyCoreConfig.tunPackages(profile.appScope,selectedTunPackages(),policy.directPackages)"));
        assertTrue(options, options.contains("profile.mode==ProxyRuntimeProfile.Mode.ENHANCE||profile.dnsHijack==ProxyRuntimeProfile.DnsHijack.TPROXY"));
    }

    @Test public void theDnsPortAnswersFromTheAddressHetuRedirectedTheQueryTo() throws Exception {
        // hetu-root.sh REDIRECTs an app's DNS to 127.0.0.1 (::1); a wildcard listener answered from the phone's own
        // address, which Android's connected resolver socket drops. Loopback listeners + a wildcard one for hotspot clients.
        ProxyCoreConfig.Options o = modeOptions(ProxyRuntimeProfile.Mode.TPROXY);
        List<Object> sing = list(CoreJson.parseObject(ProxyCoreConfig.build(ProxyRuntimeProfile.Core.SING_BOX, SS, o, u -> null).config).get("inbounds"));
        assertEquals("127.0.0.1", inbound(sing, ProxyCoreConfig.TAG_DNS_IN).get("listen"));
        assertEquals("::1", inbound(sing, ProxyCoreConfig.TAG_DNS_IN6).get("listen"));
        assertEquals("::", inbound(sing, ProxyCoreConfig.TAG_DNS_LAN).get("listen"));
        for (String tag : ProxyCoreConfig.dnsInTags(o)) {
            assertEquals(tag, Boolean.TRUE, inbound(sing, tag).get("reuse_addr"));
            assertEquals(tag, 11053, ((Number) inbound(sing, tag).get("listen_port")).intValue());
        }
        Map<String, Object> route = map(CoreJson.parseObject(ProxyCoreConfig.build(ProxyRuntimeProfile.Core.SING_BOX, SS, o, u -> null).config).get("route"));
        boolean hijack = false;
        for (Object r : list(route.get("rules")))
            if ("hijack-dns".equals(map(r).get("action")) && ProxyCoreConfig.dnsInTags(o).equals(map(r).get("inbound"))) hijack = true;
        assertTrue("every DNS listener is answered by sing-box's DNS", hijack);

        o.loopback6 = false;
        assertEquals(Arrays.asList(ProxyCoreConfig.TAG_DNS_IN, ProxyCoreConfig.TAG_DNS_LAN), ProxyCoreConfig.dnsInTags(o));
        Map<String, Object> xray = CoreJson.parseObject(ProxyCoreConfig.build(ProxyRuntimeProfile.Core.XRAY, SS, o, u -> null).config);
        assertEquals("127.0.0.1", inbound(list(xray.get("inbounds")), ProxyCoreConfig.TAG_DNS_IN).get("listen"));
        assertEquals("::", inbound(list(xray.get("inbounds")), ProxyCoreConfig.TAG_DNS_LAN).get("listen"));
        assertNull("no ::1 listener where ::1 cannot be bound", inbound(list(xray.get("inbounds")), ProxyCoreConfig.TAG_DNS_IN6));
        boolean dnsOut = false;
        for (Object r : list(map(xray.get("routing")).get("rules")))
            if (ProxyCoreConfig.TAG_DNS_OUT.equals(map(r).get("outboundTag")) && ProxyCoreConfig.dnsInTags(o).equals(map(r).get("inboundTag"))) dnsOut = true;
        assertTrue(dnsOut);

        // Hysteria's forwarders cannot share the port with a wildcard listener: loopback only.
        o.loopback6 = true;
        String hy2 = "proxies:\n  - {name: HY, type: hysteria2, server: 192.0.2.9, port: 443, password: pw-long, sni: a.example.com}\nrules:\n  - MATCH,HY\n";
        String hysteria = ProxyCoreConfig.build(ProxyRuntimeProfile.Core.HYSTERIA, hy2, o, u -> null).config;
        assertTrue(hysteria, hysteria.contains("udpForwarding:\n  - listen: 127.0.0.1:11053\n") && hysteria.contains("  - listen: '[::1]:11053'\n"));
        assertFalse(hysteria, hysteria.contains("listen: :11053"));
        assertFalse(ProxyCoreConfig.build(ProxyRuntimeProfile.Core.HYSTERIA, hy2, o, u -> null).warningText().contains("共享网络"));
        o.sharedNetwork = true;
        assertTrue("hotspot clients are told their DNS is not served", ProxyCoreConfig.build(ProxyRuntimeProfile.Core.HYSTERIA, hy2, o, u -> null).warningText().contains("共享网络"));
        o.ipv6 = false;
        assertFalse(ProxyCoreConfig.build(ProxyRuntimeProfile.Core.HYSTERIA, hy2, o, u -> null).config.contains("[::1]"));

        String root = new String(Files.readAllBytes(new File("src/main/java/io/github/xgl34222220/hetu/RootProxyManager.java").toPath()), StandardCharsets.UTF_8);
        assertTrue(root.contains("o.loopback6=loopback6Bindable();"));
    }
}
