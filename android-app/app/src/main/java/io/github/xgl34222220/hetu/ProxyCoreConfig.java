package io.github.xgl34222220.hetu;

import java.io.IOException;
import java.io.StringReader;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;

/**
 * Startup configuration for the non-Mihomo cores (sing-box / sing-box reF1nd, Xray, V2Fly, Hysteria 2).
 *
 * <p>Two sources are accepted, exactly like the Mihomo path keeps the user's file untouched:
 * <ul>
 *   <li><b>native</b>: a config file written for that core (sing-box/Xray/V2Fly JSON, Hysteria client YAML).
 *       Hetu replaces only the ingress it owns (TPROXY/Redirect, DNS, local egress probe, controller) and
 *       strips socket marks, the same isolation MihomoStartupConfig applies to listeners/routing-mark.</li>
 *   <li><b>converted</b>: the user's Clash/Mihomo YAML (proxies, proxy-providers, proxy-groups, rules,
 *       rule-providers, dns). Unsupported nodes, rules and groups are skipped with an explicit warning.</li>
 * </ul>
 * Pure JVM: no Android API, network access only through the injected {@link Fetcher}.
 *
 * <p>Outbound loop prevention is owner based for every core (hetu-root.sh runs the core as
 * root:net_admin and returns that owner before interception). No SO_MARK/routing_mark is written:
 * on Android it replaces netd's netId fwmark and strands direct/DoH sockets (field bug, test42).
 */
final class ProxyCoreConfig {
    private ProxyCoreConfig() {}

    static final String TAG_TPROXY = "hetu-tproxy";
    static final String TAG_REDIRECT = "hetu-redirect";
    static final String TAG_DNS_IN = "hetu-dns-in";
    static final String TAG_EGRESS = "hetu-egress-probe";
    static final String TAG_DIRECT = "DIRECT";
    static final String TAG_BLOCK = "REJECT";
    static final String TAG_DNS_OUT = "hetu-dns-out";
    static final String DEFAULT_FAKE_IP_V4 = "198.18.0.1/16";
    static final String DEFAULT_DIRECT_DNS = "223.5.5.5";
    static final String DEFAULT_REMOTE_DNS = "1.1.1.1";
    static final String SING_GEOSITE = "https://raw.githubusercontent.com/SagerNet/sing-geosite/rule-set/geosite-%s.srs";
    static final String SING_GEOIP = "https://raw.githubusercontent.com/SagerNet/sing-geoip/rule-set/geoip-%s.srs";
    static final int MAX_PROVIDER_BYTES = 8 * 1024 * 1024;
    /** Same names as the Mihomo adblock providers (ProxyAdblockRules.PROVIDER_NAME / ALLOW_PROVIDER_NAME). */
    static final String ADBLOCK_TAG = "hetu-adblock", ADBLOCK_ALLOW_TAG = "hetu-adblock-allow";

    static boolean hasTopLevelKey(String text, String key) {
        if (text == null) return false;
        Pattern p = Pattern.compile("^" + Pattern.quote(key) + "\\s*:");
        for (String line : text.split("\n", -1)) {
            if (line.isEmpty() || line.charAt(0) == ' ' || line.charAt(0) == '\t' || line.charAt(0) == '#') continue;
            if (p.matcher(line).find()) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ core kinds / CLI

    enum Kind {
        MIHOMO("mihomo", "yaml"), SING_BOX("sing-box", "json"), XRAY("xray", "json"), V2RAY("v2ray", "json"), HYSTERIA("hysteria", "yaml");
        final String id, extension;
        Kind(String id, String extension) { this.id = id; this.extension = extension; }
    }

    static Kind kind(ProxyRuntimeProfile.Core core) {
        switch (core) {
            case SING_BOX: case SING_BOX_REF1ND: return Kind.SING_BOX;
            case XRAY: return Kind.XRAY;
            case V2FLY: return Kind.V2RAY;
            case HYSTERIA: return Kind.HYSTERIA;
            default: return Kind.MIHOMO;
        }
    }

    /** Clash-compatible controller: Mihomo natively, sing-box through experimental.clash_api. */
    static boolean clashApi(ProxyRuntimeProfile.Core core) {
        Kind k = kind(core);
        return k == Kind.MIHOMO || k == Kind.SING_BOX;
    }

    /** Core self-version arguments, used by diagnostics: `bin/core <args>`. */
    static String versionArgs(ProxyRuntimeProfile.Core core) {
        switch (kind(core)) {
            case MIHOMO: return "-v";
            default: return "version";
        }
    }

    // ------------------------------------------------------------------ options / result

    interface Fetcher {
        /** Returns the body of an HTTP(S) provider, or null when it cannot be fetched. */
        String fetch(String url) throws IOException;
    }

    static final class Options {
        ProxyRuntimeProfile.Mode mode = ProxyRuntimeProfile.Mode.TPROXY;
        int tproxyPort, redirectPort, dnsPort, controllerPort, egressPort;
        String secret = "";
        boolean ipv6 = true, tcp = true, udp = true;
        /** Xray/V2Fly geoip.dat + geosite.dat were extracted next to the binary. */
        boolean geoAssets = true;
        boolean cnIpDirect;
        List<String> adblock = Collections.emptyList(), adblockAllow = Collections.emptyList();
        /**
         * Resolves the Hysteria server host name before start. The Hysteria client looks the server up with
         * Go's resolver, which has no system DNS on Android; the App resolves it through netd instead.
         */
        HostResolver hostResolver;
    }

    interface HostResolver { String resolve(String host) throws IOException; }

    static final class Result {
        final String config;
        final boolean nativeSource;
        final List<String> warnings;
        final int nodes;
        final String fakeIpV4, fakeIpV6;
        final boolean adblockApplied;
        Result(String config, boolean nativeSource, List<String> warnings, int nodes, String fakeIpV4, String fakeIpV6, boolean adblockApplied) {
            this.config = config; this.nativeSource = nativeSource; this.warnings = Collections.unmodifiableList(new ArrayList<>(warnings));
            this.nodes = nodes; this.fakeIpV4 = fakeIpV4; this.fakeIpV6 = fakeIpV6; this.adblockApplied = adblockApplied;
        }
        String warningText() { return String.join("；", warnings); }
    }

    // ------------------------------------------------------------------ source classification

    static boolean looksJson(String text) {
        String t = text == null ? "" : text.trim();
        while (t.startsWith("//") || t.startsWith("#")) { int nl = t.indexOf('\n'); t = nl < 0 ? "" : t.substring(nl + 1).trim(); }
        return t.startsWith("{");
    }

    /** A Clash/Mihomo profile: has proxies / proxy-providers / proxy-groups / rules at top level. */
    static boolean isMihomoYaml(String text) {
        if (text == null || looksJson(text)) return false;
        for (String key : new String[]{"proxies", "proxy-providers", "proxy-groups", "rules", "rule-providers"})
            if (hasTopLevelKey(text, key)) return true;
        return false;
    }

    /** A Hysteria 2 client config: top-level server: and no Clash proxy lists. */
    static boolean isHysteriaYaml(String text) {
        return text != null && !looksJson(text) && hasTopLevelKey(text, "server") && !isMihomoYaml(text);
    }

    static boolean isNativeFor(ProxyRuntimeProfile.Core core, String text) {
        switch (kind(core)) {
            case SING_BOX: case XRAY: case V2RAY: return looksJson(text);
            case HYSTERIA: return isHysteriaYaml(text);
            default: return true;
        }
    }

    // ------------------------------------------------------------------ entry point

    static Result build(ProxyRuntimeProfile.Core core, String source, Options o, Fetcher fetcher) throws IOException {
        if (source == null || source.trim().isEmpty()) throw new IOException("源配置为空");
        Kind k = kind(core);
        if (k == Kind.MIHOMO) throw new IOException("Mihomo 使用 MihomoStartupConfig 生成启动配置");
        if (o.mode != ProxyRuntimeProfile.Mode.TPROXY && o.mode != ProxyRuntimeProfile.Mode.REDIRECT)
            throw new IOException(core.label + " 仅支持 Root TPROXY / Redirect 模式");
        List<String> warnings = new ArrayList<>();
        if (isNativeFor(core, source)) {
            switch (k) {
                case SING_BOX: return nativeSingBox(source, o, warnings);
                case XRAY: return nativeXray(source, o, warnings, false);
                case V2RAY: return nativeXray(source, o, warnings, true);
                case HYSTERIA: return nativeHysteria(source, o, warnings);
                default: break;
            }
        }
        if (!isMihomoYaml(source)) {
            String expected = k == Kind.HYSTERIA ? "Hysteria 2 客户端 YAML" : core.label + " JSON";
            throw new IOException("配置既不是 " + expected + "，也不是可转换的 Clash/Mihomo YAML");
        }
        Profile profile = Profile.parse(source, fetcher, warnings);
        switch (k) {
            case SING_BOX: return SingBox.convert(profile, o, warnings);
            case XRAY: return XrayConv.convert(profile, o, warnings, false);
            case V2RAY: return XrayConv.convert(profile, o, warnings, true);
            case HYSTERIA: return Hysteria.convert(profile, o, warnings);
            default: throw new IOException("未知核心");
        }
    }

    // ------------------------------------------------------------------ helpers

    static LinkedHashMap<String, Object> map(Object... kv) {
        LinkedHashMap<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) if (kv[i + 1] != null) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> asMap(Object o) { return o instanceof Map ? (Map<String, Object>) o : null; }

    @SuppressWarnings("unchecked")
    static List<Object> asList(Object o) {
        if (o instanceof List) return (List<Object>) o;
        if (o == null) return Collections.emptyList();
        return Collections.singletonList(o);
    }

    static String str(Map<String, Object> m, String key) {
        Object v = m == null ? null : m.get(key);
        return v == null ? "" : String.valueOf(v).trim();
    }

    static String strAny(Map<String, Object> m, String... keys) {
        for (String key : keys) { String v = str(m, key); if (!v.isEmpty()) return v; }
        return "";
    }

    static boolean bool(Map<String, Object> m, String key, boolean fallback) {
        Object v = m == null ? null : m.get(key);
        if (v == null) return fallback;
        if (v instanceof Boolean) return (Boolean) v;
        String s = String.valueOf(v).trim().toLowerCase(Locale.ROOT);
        if (s.equals("true") || s.equals("yes") || s.equals("1") || s.equals("on")) return true;
        if (s.equals("false") || s.equals("no") || s.equals("0") || s.equals("off")) return false;
        return fallback;
    }

    static int integer(Map<String, Object> m, String key, int fallback) {
        Object v = m == null ? null : m.get(key);
        if (v instanceof Number) return ((Number) v).intValue();
        if (v == null) return fallback;
        try { return Integer.parseInt(String.valueOf(v).trim()); } catch (NumberFormatException e) { return fallback; }
    }

    static List<String> strings(Object v) {
        ArrayList<String> out = new ArrayList<>();
        for (Object o : asList(v)) if (o != null && !String.valueOf(o).trim().isEmpty()) out.add(String.valueOf(o).trim());
        return out;
    }

    /** "100 Mbps", "100", 100 → 100. */
    static int mbps(Object v) {
        if (v == null) return 0;
        if (v instanceof Number) return ((Number) v).intValue();
        String s = String.valueOf(v).trim().toLowerCase(Locale.ROOT).replace(" ", "");
        StringBuilder digits = new StringBuilder();
        for (char c : s.toCharArray()) { if (Character.isDigit(c)) digits.append(c); else break; }
        if (digits.length() == 0) return 0;
        long n = Long.parseLong(digits.toString());
        if (s.contains("gbps") || s.endsWith("g")) n *= 1000;
        else if (s.contains("kbps") || s.endsWith("k")) n = Math.max(1, n / 1000);
        return (int) Math.min(n, 100000);
    }

    static String listenAll(Options o) { return o.ipv6 ? "::" : "0.0.0.0"; }

    // ================================================================== Mihomo profile model

    static final class Node {
        final String name, type; final Map<String, Object> raw;
        Node(String name, String type, Map<String, Object> raw) { this.name = name; this.type = type; this.raw = raw; }
    }

    static final class Group {
        final String name, type, url; final List<String> members; final int interval, tolerance;
        Group(String name, String type, List<String> members, String url, int interval, int tolerance) {
            this.name = name; this.type = type; this.members = members; this.url = url; this.interval = interval; this.tolerance = tolerance;
        }
    }

    /** One rule condition. Logical rules carry children; leaves carry type/value. */
    static final class Cond {
        final String type, value; final List<Cond> children; final boolean noResolve;
        Cond(String type, String value, List<Cond> children, boolean noResolve) { this.type = type; this.value = value; this.children = children; this.noResolve = noResolve; }
        boolean logical() { return children != null; }
    }

    static final class Rule {
        final Cond cond; final String target; final String text;
        Rule(Cond cond, String target, String text) { this.cond = cond; this.target = target; this.text = text; }
    }

    static final class RuleProvider {
        final String name, behavior; final List<String> payload;
        RuleProvider(String name, String behavior, List<String> payload) { this.name = name; this.behavior = behavior; this.payload = payload; }
    }

    static final class Profile {
        final LinkedHashMap<String, Node> nodes = new LinkedHashMap<>();
        final LinkedHashMap<String, Group> groups = new LinkedHashMap<>();
        final List<Rule> rules = new ArrayList<>();
        final Map<String, RuleProvider> ruleProviders = new LinkedHashMap<>();
        String matchTarget = TAG_DIRECT;
        boolean fakeIp; String fakeIpRange = DEFAULT_FAKE_IP_V4;
        final List<String> fakeIpFilter = new ArrayList<>();
        final List<String> directDns = new ArrayList<>(), remoteDns = new ArrayList<>();
        int skippedNodes;

        static Map<String, Object> loadYaml(String text) throws IOException {
            LoaderOptions options = new LoaderOptions();
            options.setCodePointLimit(16 * 1024 * 1024);
            options.setMaxAliasesForCollections(4096);
            options.setNestingDepthLimit(60);
            try {
                Object root = new Yaml(new SafeConstructor(options)).load(new StringReader(text));
                Map<String, Object> m = asMap(root);
                if (m == null) throw new IOException("YAML 根节点应为键值映射");
                return m;
            } catch (YAMLException invalid) {
                throw new IOException("YAML 语法或结构解析失败");
            }
        }

        static Profile parse(String text, Fetcher fetcher, List<String> warnings) throws IOException {
            Map<String, Object> root = loadYaml(text);
            Profile p = new Profile();
            for (Object o : asList(root.get("proxies"))) p.addNode(asMap(o), warnings, "");
            LinkedHashMap<String, List<String>> providerNodes = new LinkedHashMap<>();
            Map<String, Object> providers = asMap(root.get("proxy-providers"));
            if (providers != null) for (Map.Entry<String, Object> e : providers.entrySet())
                providerNodes.put(e.getKey(), p.loadProvider(e.getKey(), asMap(e.getValue()), fetcher, warnings));
            // Groups: two passes so forward references resolve.
            List<Map<String, Object>> rawGroups = new ArrayList<>();
            for (Object o : asList(root.get("proxy-groups"))) { Map<String, Object> g = asMap(o); if (g != null && !str(g, "name").isEmpty()) rawGroups.add(g); }
            Set<String> groupNames = new LinkedHashSet<>();
            for (Map<String, Object> g : rawGroups) groupNames.add(str(g, "name"));
            for (Map<String, Object> g : rawGroups) p.addGroup(g, groupNames, providerNodes, warnings);
            Map<String, Object> ruleProviders = asMap(root.get("rule-providers"));
            if (ruleProviders != null) for (Map.Entry<String, Object> e : ruleProviders.entrySet())
                p.loadRuleProvider(e.getKey(), asMap(e.getValue()), fetcher, warnings);
            for (Object o : asList(root.get("rules"))) {
                if (o == null) continue;
                String line = String.valueOf(o).trim();
                if (line.isEmpty()) continue;
                p.addRule(line, warnings);
            }
            Map<String, Object> dns = asMap(root.get("dns"));
            if (dns != null && bool(dns, "enable", true)) {
                p.fakeIp = "fake-ip".equalsIgnoreCase(str(dns, "enhanced-mode"));
                if (!str(dns, "fake-ip-range").isEmpty()) p.fakeIpRange = str(dns, "fake-ip-range");
                for (String f : strings(dns.get("fake-ip-filter"))) if (!f.contains(":")) p.fakeIpFilter.add(f);
                for (String s : strings(dns.get("default-nameserver"))) addDns(p.directDns, s);
                for (String s : strings(dns.get("nameserver"))) addDns(p.remoteDns, s);
                if (p.directDns.isEmpty()) for (String s : strings(dns.get("proxy-server-nameserver"))) addDns(p.directDns, s);
            }
            if (p.nodes.isEmpty()) warnings.add("没有可转换的节点");
            return p;
        }

        private static void addDns(List<String> out, String raw) {
            String s = raw.trim();
            int hash = s.indexOf('#');
            if (hash > 0) s = s.substring(0, hash);
            if (s.startsWith("system") || s.startsWith("dhcp://") || s.isEmpty()) return;
            if (!out.contains(s)) out.add(s);
        }

        void addNode(Map<String, Object> m, List<String> warnings, String prefix) {
            if (m == null) return;
            String name = str(m, "name"), type = str(m, "type").toLowerCase(Locale.ROOT);
            if (name.isEmpty() || type.isEmpty()) return;
            String unique = name;
            for (int i = 2; nodes.containsKey(unique) || TAG_DIRECT.equals(unique) || TAG_BLOCK.equals(unique); i++) unique = name + " #" + i;
            nodes.put(unique, new Node(unique, type, m));
        }

        List<String> loadProvider(String name, Map<String, Object> m, Fetcher fetcher, List<String> warnings) {
            List<String> added = new ArrayList<>();
            if (m == null) return added;
            String type = str(m, "type").toLowerCase(Locale.ROOT);
            List<Map<String, Object>> items = new ArrayList<>();
            try {
                if ("inline".equals(type)) {
                    for (Object o : asList(m.get("payload"))) { Map<String, Object> n = asMap(o); if (n != null) items.add(n); }
                } else if ("http".equals(type) && !str(m, "url").isEmpty()) {
                    String body = fetcher == null ? null : fetcher.fetch(str(m, "url"));
                    if (body == null) { warnings.add("订阅 " + name + " 无法下载，已跳过"); return added; }
                    items.addAll(ProviderParser.parse(body));
                } else {
                    warnings.add("订阅 " + name + " 为本地文件类型，转换时无法读取，已跳过");
                    return added;
                }
            } catch (IOException error) {
                warnings.add("订阅 " + name + " 解析失败：" + error.getMessage());
                return added;
            }
            Pattern include = regex(str(m, "filter"), warnings), exclude = regex(str(m, "exclude-filter"), warnings);
            Map<String, Object> override = asMap(m.get("override"));
            for (Map<String, Object> item : items) {
                String nodeName = str(item, "name");
                if (include != null && !include.matcher(nodeName).find()) continue;
                if (exclude != null && exclude.matcher(nodeName).find()) continue;
                Map<String, Object> copy = new LinkedHashMap<>(item);
                if (override != null) for (Map.Entry<String, Object> e : override.entrySet())
                    if (!"name".equals(e.getKey()) && !e.getKey().startsWith("additional-")) copy.put(e.getKey(), e.getValue());
                if (override != null && !str(override, "additional-prefix").isEmpty()) copy.put("name", str(override, "additional-prefix") + str(copy, "name"));
                if (override != null && !str(override, "additional-suffix").isEmpty()) copy.put("name", str(copy, "name") + str(override, "additional-suffix"));
                int before = nodes.size();
                addNode(copy, warnings, name);
                if (nodes.size() > before) added.add(new ArrayList<>(nodes.keySet()).get(nodes.size() - 1));
            }
            if (added.isEmpty()) warnings.add("订阅 " + name + " 没有可用节点");
            return added;
        }

        static Pattern regex(String text, List<String> warnings) {
            if (text == null || text.isEmpty()) return null;
            try { return Pattern.compile(text); }
            catch (PatternSyntaxException invalid) { warnings.add("过滤正则无法解析：" + text); return null; }
        }

        void addGroup(Map<String, Object> g, Set<String> groupNames, Map<String, List<String>> providerNodes, List<String> warnings) {
            String name = str(g, "name"), type = str(g, "type").toLowerCase(Locale.ROOT);
            List<String> members = new ArrayList<>();
            Pattern include = regex(str(g, "filter"), warnings), exclude = regex(str(g, "exclude-filter"), warnings);
            for (String m : strings(g.get("proxies"))) members.add(m);
            List<String> candidates = new ArrayList<>();
            boolean all = bool(g, "include-all", false);
            if (all || bool(g, "include-all-proxies", false)) {
                for (String n : nodes.keySet()) { boolean fromProvider = false; for (List<String> l : providerNodes.values()) if (l.contains(n)) fromProvider = true; if (!fromProvider || all) candidates.add(n); }
            }
            if (all || bool(g, "include-all-providers", false)) for (List<String> l : providerNodes.values()) candidates.addAll(l);
            for (String use : strings(g.get("use"))) {
                List<String> l = providerNodes.get(use);
                if (l == null) warnings.add("策略组 " + name + " 引用的订阅 " + use + " 不存在");
                else candidates.addAll(l);
            }
            for (String c : candidates) {
                if (include != null && !include.matcher(c).find()) continue;
                if (exclude != null && exclude.matcher(c).find()) continue;
                if (!members.contains(c)) members.add(c);
            }
            List<String> resolved = new ArrayList<>();
            for (String m : members) {
                if (nodes.containsKey(m) || groupNames.contains(m) || isBuiltin(m)) resolved.add(m);
                else warnings.add("策略组 " + name + " 的成员 " + m + " 不可用，已移除");
            }
            if (resolved.isEmpty()) { warnings.add("策略组 " + name + " 没有可用成员，改为直连"); resolved.add(TAG_DIRECT); }
            groups.put(name, new Group(name, type.isEmpty() ? "select" : type, resolved, str(g, "url"), integer(g, "interval", 300), integer(g, "tolerance", 50)));
        }

        static boolean isBuiltin(String name) {
            String n = name.toUpperCase(Locale.ROOT);
            return n.equals("DIRECT") || n.equals("REJECT") || n.equals("REJECT-DROP") || n.equals("PASS") || n.equals("COMPATIBLE");
        }

        void loadRuleProvider(String name, Map<String, Object> m, Fetcher fetcher, List<String> warnings) {
            if (m == null) return;
            String type = str(m, "type").toLowerCase(Locale.ROOT), behavior = str(m, "behavior").toLowerCase(Locale.ROOT);
            String format = str(m, "format").toLowerCase(Locale.ROOT);
            if (format.equals("mrs")) { warnings.add("规则集 " + name + " 为 mrs 二进制格式，无法转换，相关规则已跳过"); return; }
            List<String> payload = new ArrayList<>();
            try {
                String body;
                if ("inline".equals(type)) { for (String s : strings(m.get("payload"))) payload.add(s); body = null; }
                else if ("http".equals(type) && !str(m, "url").isEmpty()) {
                    body = fetcher == null ? null : fetcher.fetch(str(m, "url"));
                    if (body == null) { warnings.add("规则集 " + name + " 无法下载，相关规则已跳过"); return; }
                } else { warnings.add("规则集 " + name + " 为本地文件类型，相关规则已跳过"); return; }
                if (body != null) {
                    boolean yaml = format.equals("yaml") || (format.isEmpty() && body.contains("payload:"));
                    if (yaml) payload.addAll(strings(loadYaml(body).get("payload")));
                    else for (String line : body.split("\n")) { String t = line.trim(); if (!t.isEmpty() && !t.startsWith("#") && !t.startsWith("//")) payload.add(t); }
                }
            } catch (IOException error) { warnings.add("规则集 " + name + " 解析失败，相关规则已跳过"); return; }
            ruleProviders.put(name, new RuleProvider(name, behavior.isEmpty() ? "domain" : behavior, payload));
        }

        void addRule(String line, List<String> warnings) {
            try {
                RuleParser parser = new RuleParser(line);
                Rule r = parser.parse();
                if (r == null) return;
                if ("MATCH".equals(r.cond.type) || "FINAL".equals(r.cond.type)) { matchTarget = r.target; return; }
                rules.add(r);
            } catch (IOException invalid) {
                warnings.add("规则无法解析，已跳过：" + line);
            }
        }
    }

    /** Mihomo rule text: TYPE,payload,target[,no-resolve] and AND/OR/NOT,((..),(..)),target. */
    static final class RuleParser {
        final String text;
        RuleParser(String text) { this.text = text; }

        Rule parse() throws IOException {
            String t = text.trim();
            int comma = t.indexOf(',');
            if (comma < 0) throw new IOException("rule");
            String type = t.substring(0, comma).trim().toUpperCase(Locale.ROOT);
            String rest = t.substring(comma + 1);
            if (type.equals("MATCH") || type.equals("FINAL")) return new Rule(new Cond("MATCH", "", null, false), rest.trim(), text);
            if (type.equals("AND") || type.equals("OR") || type.equals("NOT")) {
                int[] span = balanced(rest);
                String inner = rest.substring(span[0] + 1, span[1]);
                String after = rest.substring(span[1] + 1).trim();
                if (!after.startsWith(",")) throw new IOException("rule");
                String[] tail = after.substring(1).split(",");
                return new Rule(new Cond(type, "", children(inner), false), tail[0].trim(), text);
            }
            String[] parts = rest.split(",");
            if (parts.length < 2) throw new IOException("rule");
            boolean noResolve = false;
            for (int i = 2; i < parts.length; i++) if (parts[i].trim().equalsIgnoreCase("no-resolve")) noResolve = true;
            return new Rule(new Cond(type, parts[0].trim(), null, noResolve), parts[1].trim(), text);
        }

        static int[] balanced(String s) throws IOException {
            int start = s.indexOf('(');
            if (start < 0) throw new IOException("rule");
            int depth = 0;
            for (int i = start; i < s.length(); i++) {
                char c = s.charAt(i);
                if (c == '(') depth++;
                else if (c == ')') { depth--; if (depth == 0) return new int[]{start, i}; }
            }
            throw new IOException("rule");
        }

        static List<Cond> children(String inner) throws IOException {
            List<Cond> out = new ArrayList<>();
            int i = 0;
            while (i < inner.length()) {
                int open = inner.indexOf('(', i);
                if (open < 0) break;
                int depth = 0, close = -1;
                for (int j = open; j < inner.length(); j++) {
                    char c = inner.charAt(j);
                    if (c == '(') depth++;
                    else if (c == ')') { depth--; if (depth == 0) { close = j; break; } }
                }
                if (close < 0) throw new IOException("rule");
                String item = inner.substring(open + 1, close).trim();
                int comma = item.indexOf(',');
                if (comma < 0) throw new IOException("rule");
                String type = item.substring(0, comma).trim().toUpperCase(Locale.ROOT);
                String value = item.substring(comma + 1).trim();
                if (type.equals("AND") || type.equals("OR") || type.equals("NOT")) {
                    int[] span = balanced(value);
                    out.add(new Cond(type, "", children(value.substring(span[0] + 1, span[1])), false));
                } else {
                    String[] parts = value.split(",");
                    boolean nr = parts.length > 1 && parts[parts.length - 1].trim().equalsIgnoreCase("no-resolve");
                    out.add(new Cond(type, parts[0].trim(), null, nr));
                }
                i = close + 1;
            }
            if (out.isEmpty()) throw new IOException("rule");
            return out;
        }
    }

    /** Subscription bodies: Clash YAML (proxies:), or a plain/base64 list of share links. */
    static final class ProviderParser {
        static List<Map<String, Object>> parse(String body) throws IOException {
            if (body == null) return Collections.emptyList();
            if (body.length() > MAX_PROVIDER_BYTES) throw new IOException("订阅内容过大");
            String trimmed = body.trim();
            List<Map<String, Object>> out = new ArrayList<>();
            if (hasTopLevelKey(trimmed, "proxies")) {
                for (Object o : asList(Profile.loadYaml(trimmed).get("proxies"))) { Map<String, Object> m = asMap(o); if (m != null) out.add(m); }
                return out;
            }
            String text = trimmed;
            if (!text.contains("://")) {
                try { text = new String(Base64.getMimeDecoder().decode(text.replace('-', '+').replace('_', '/')), StandardCharsets.UTF_8); }
                catch (IllegalArgumentException notBase64) { throw new IOException("订阅既不是 Clash YAML 也不是分享链接列表"); }
            }
            for (String line : text.split("\\r?\\n")) {
                String l = line.trim();
                if (l.isEmpty()) continue;
                Map<String, Object> node = ShareLink.parse(l);
                if (node != null) out.add(node);
            }
            return out;
        }
    }

    /** ss://, vmess://, vless://, trojan://, hysteria2:// (hy2://), tuic:// → Mihomo proxy maps. */
    static final class ShareLink {
        static Map<String, Object> parse(String link) {
            try {
                int scheme = link.indexOf("://");
                if (scheme <= 0) return null;
                String type = link.substring(0, scheme).toLowerCase(Locale.ROOT);
                if (type.equals("vmess")) return vmess(link.substring(scheme + 3));
                if (type.equals("ss")) return ss(link);
                if (type.equals("hy2")) type = "hysteria2";
                if (!(type.equals("vless") || type.equals("trojan") || type.equals("hysteria2") || type.equals("tuic"))) return null;
                URI uri = new URI(link.replace(" ", "%20"));
                Map<String, String> q = query(uri.getRawQuery());
                LinkedHashMap<String, Object> m = new LinkedHashMap<>();
                m.put("name", fragment(uri, uri.getHost()));
                m.put("type", type);
                m.put("server", uri.getHost());
                m.put("port", uri.getPort() > 0 ? uri.getPort() : 443);
                String user = decode(uri.getRawUserInfo());
                switch (type) {
                    case "vless": m.put("uuid", user); if (q.containsKey("flow")) m.put("flow", q.get("flow")); break;
                    case "trojan": m.put("password", user); break;
                    case "hysteria2": m.put("password", user);
                        if (q.containsKey("obfs")) { m.put("obfs", q.get("obfs")); m.put("obfs-password", q.getOrDefault("obfs-password", "")); }
                        if (q.containsKey("mport")) m.put("ports", q.get("mport"));
                        break;
                    case "tuic": { int c = user.indexOf(':'); m.put("uuid", c < 0 ? user : user.substring(0, c)); if (c >= 0) m.put("password", user.substring(c + 1));
                        if (q.containsKey("congestion_control")) m.put("congestion-controller", q.get("congestion_control"));
                        if (q.containsKey("udp_relay_mode")) m.put("udp-relay-mode", q.get("udp_relay_mode")); break; }
                    default: break;
                }
                String security = q.getOrDefault("security", type.equals("vless") ? "none" : "tls");
                if (security.equals("tls") || security.equals("reality") || !type.equals("vless")) m.put("tls", true);
                String sni = q.containsKey("sni") ? q.get("sni") : q.getOrDefault("peer", "");
                if (!sni.isEmpty()) m.put(type.equals("vless") ? "servername" : "sni", sni);
                if ("1".equals(q.get("allowInsecure")) || "1".equals(q.get("insecure")) || "true".equals(q.get("allowInsecure"))) m.put("skip-cert-verify", true);
                if (q.containsKey("fp")) m.put("client-fingerprint", q.get("fp"));
                if (q.containsKey("alpn")) m.put("alpn", Arrays.asList(q.get("alpn").split(",")));
                if (security.equals("reality")) m.put("reality-opts", map("public-key", q.getOrDefault("pbk", ""), "short-id", q.getOrDefault("sid", "")));
                transport(m, q.getOrDefault("type", "tcp"), q.getOrDefault("path", ""), q.getOrDefault("host", ""), q.getOrDefault("serviceName", ""), q.getOrDefault("headerType", ""));
                return m;
            } catch (Exception invalid) {
                return null;
            }
        }

        static void transport(Map<String, Object> m, String net, String path, String host, String service, String header) {
            switch (net) {
                case "ws": m.put("network", "ws"); m.put("ws-opts", map("path", path.isEmpty() ? "/" : path, "headers", host.isEmpty() ? null : map("Host", host))); break;
                case "grpc": m.put("network", "grpc"); m.put("grpc-opts", map("grpc-service-name", service)); break;
                case "h2": case "http": if (net.equals("h2") || !"http".equals(header)) { m.put("network", "h2"); m.put("h2-opts", map("host", host.isEmpty() ? null : Arrays.asList(host.split(",")), "path", path.isEmpty() ? "/" : path)); } break;
                case "httpupgrade": m.put("network", "ws"); m.put("ws-opts", map("path", path.isEmpty() ? "/" : path, "headers", host.isEmpty() ? null : map("Host", host), "v2ray-http-upgrade", true)); break;
                case "tcp": if ("http".equals(header)) { m.put("network", "http"); m.put("http-opts", map("path", Collections.singletonList(path.isEmpty() ? "/" : path), "headers", host.isEmpty() ? null : map("Host", Collections.singletonList(host)))); } break;
                default: m.put("network", net); break;
            }
        }

        static Map<String, Object> vmess(String body) throws IOException {
            String json = new String(Base64.getMimeDecoder().decode(body.trim().replace('-', '+').replace('_', '/')), StandardCharsets.UTF_8);
            Map<String, Object> v = CoreJson.parseObject(json);
            LinkedHashMap<String, Object> m = new LinkedHashMap<>();
            m.put("name", str(v, "ps").isEmpty() ? str(v, "add") : str(v, "ps"));
            m.put("type", "vmess");
            m.put("server", str(v, "add"));
            m.put("port", integer(v, "port", 443));
            m.put("uuid", str(v, "id"));
            m.put("alterId", integer(v, "aid", 0));
            m.put("cipher", str(v, "scy").isEmpty() ? "auto" : str(v, "scy"));
            if ("tls".equals(str(v, "tls"))) { m.put("tls", true); if (!str(v, "sni").isEmpty()) m.put("servername", str(v, "sni")); }
            if (!str(v, "alpn").isEmpty()) m.put("alpn", Arrays.asList(str(v, "alpn").split(",")));
            if (!str(v, "fp").isEmpty()) m.put("client-fingerprint", str(v, "fp"));
            transport(m, str(v, "net").isEmpty() ? "tcp" : str(v, "net"), str(v, "path"), str(v, "host"), str(v, "path"), str(v, "type"));
            return m;
        }

        static Map<String, Object> ss(String link) throws Exception {
            String rest = link.substring(5);
            String name = "";
            int hash = rest.indexOf('#');
            if (hash >= 0) { name = decode(rest.substring(hash + 1)); rest = rest.substring(0, hash); }
            String plugin = "";
            int qm = rest.indexOf('?');
            if (qm >= 0) { plugin = query(rest.substring(qm + 1)).getOrDefault("plugin", ""); rest = rest.substring(0, qm); }
            rest = rest.replaceAll("/$", "");
            String userInfo, hostPort;
            int at = rest.lastIndexOf('@');
            if (at < 0) { String decoded = new String(Base64.getMimeDecoder().decode(rest.replace('-', '+').replace('_', '/')), StandardCharsets.UTF_8); at = decoded.lastIndexOf('@'); userInfo = decoded.substring(0, at); hostPort = decoded.substring(at + 1); }
            else {
                userInfo = decode(rest.substring(0, at)); hostPort = rest.substring(at + 1);
                if (!userInfo.contains(":")) userInfo = new String(Base64.getMimeDecoder().decode(userInfo.replace('-', '+').replace('_', '/')), StandardCharsets.UTF_8);
            }
            int colon = userInfo.indexOf(':');
            int pc = hostPort.lastIndexOf(':');
            String host = hostPort.substring(0, pc).replace("[", "").replace("]", "");
            LinkedHashMap<String, Object> m = new LinkedHashMap<>();
            m.put("name", name.isEmpty() ? host : name);
            m.put("type", "ss");
            m.put("server", host);
            m.put("port", Integer.parseInt(hostPort.substring(pc + 1)));
            m.put("cipher", userInfo.substring(0, colon));
            m.put("password", userInfo.substring(colon + 1));
            if (!plugin.isEmpty()) {
                String[] parts = plugin.split(";");
                Map<String, Object> opts = new LinkedHashMap<>();
                for (int i = 1; i < parts.length; i++) { int e = parts[i].indexOf('='); if (e > 0) opts.put(parts[i].substring(0, e), parts[i].substring(e + 1)); else opts.put(parts[i], true); }
                if (parts[0].contains("obfs")) { m.put("plugin", "obfs"); m.put("plugin-opts", map("mode", opts.getOrDefault("obfs", "http"), "host", opts.get("obfs-host"))); }
                else if (parts[0].contains("v2ray")) { m.put("plugin", "v2ray-plugin"); m.put("plugin-opts", map("mode", "websocket", "host", opts.get("host"), "path", opts.get("path"), "tls", opts.containsKey("tls") ? Boolean.TRUE : null)); }
                else m.put("plugin", parts[0]);
            }
            return m;
        }

        static String fragment(URI uri, String fallback) {
            String f = uri.getRawFragment();
            return f == null || f.isEmpty() ? fallback : decode(f);
        }

        static String decode(String s) {
            if (s == null) return "";
            try { return URLDecoder.decode(s.replace("+", "%2B"), "UTF-8"); } catch (Exception e) { return s; }
        }

        static Map<String, String> query(String raw) {
            Map<String, String> out = new LinkedHashMap<>();
            if (raw == null || raw.isEmpty()) return out;
            for (String part : raw.split("&")) {
                int e = part.indexOf('=');
                if (e > 0) out.put(decode(part.substring(0, e)), decode(part.substring(e + 1)));
                else out.put(decode(part), "");
            }
            return out;
        }
    }

    // ------------------------------------------------------------------ shared rule helpers

    /** Mihomo domain-behavior payload entry → (kind, value): full / suffix / keyword / regex. */
    static String[] domainEntry(String raw) {
        String d = raw.trim();
        if (d.startsWith("'") || d.startsWith("\"")) d = d.substring(1, d.length() - 1);
        if (d.startsWith("+.")) return new String[]{"suffix", d.substring(2)};
        if (d.startsWith(".")) return new String[]{"suffix", d.substring(1)};
        if (d.startsWith("*.")) return new String[]{"regex", "^[^.]+\\." + d.substring(2).replace(".", "\\.") + "$"};
        if (d.contains("*")) return new String[]{"regex", "^" + d.replace(".", "\\.").replace("*", "[^.]*") + "$"};
        return new String[]{"full", d};
    }

    /** Classical rule-provider line "DOMAIN-SUFFIX,example.com" → leaf condition. */
    static Cond classical(String line) {
        String[] parts = line.split(",");
        if (parts.length < 2) return null;
        boolean nr = parts.length > 2 && parts[parts.length - 1].trim().equalsIgnoreCase("no-resolve");
        return new Cond(parts[0].trim().toUpperCase(Locale.ROOT), parts[1].trim(), null, nr);
    }

    static boolean blockTarget(String t) { String u = t.toUpperCase(Locale.ROOT); return u.equals("REJECT") || u.equals("REJECT-DROP"); }
    static boolean directTarget(String t) { return t.equalsIgnoreCase("DIRECT") || t.equalsIgnoreCase("COMPATIBLE"); }

    /** Adblock domains minus every entry the allow list fully covers (exact or suffix). */
    static List<String> subtractAllowed(List<String> block, List<String> allow) {
        if (allow.isEmpty()) return block;
        Set<String> allowSet = new HashSet<>(allow);
        List<String> out = new ArrayList<>(block.size());
        for (String d : block) {
            boolean covered = false;
            String probe = d;
            while (true) {
                if (allowSet.contains(probe)) { covered = true; break; }
                int dot = probe.indexOf('.');
                if (dot < 0) break;
                probe = probe.substring(dot + 1);
            }
            if (!covered) out.add(d);
        }
        return out;
    }

    // ================================================================== sing-box

    static final class SingBox {
        static Result convert(Profile p, Options o, List<String> warnings) throws IOException {
            LinkedHashMap<String, Object> root = new LinkedHashMap<>();
            root.put("log", map("level", "info", "timestamp", true));
            List<Object> outbounds = new ArrayList<>();
            Set<String> tags = new LinkedHashSet<>();
            for (Node n : p.nodes.values()) {
                Map<String, Object> ob = outbound(n, warnings);
                if (ob == null) { p.skippedNodes++; continue; }
                outbounds.add(ob); tags.add(n.name);
            }
            // Groups after nodes; members that were skipped are dropped.
            Map<String, String> aliases = new HashMap<>();
            boolean changed = true;
            Set<String> emitted = new LinkedHashSet<>();
            List<Object> groupObs = new ArrayList<>();
            // Iterate until stable so nested group references resolve in dependency order.
            while (changed) {
                changed = false;
                for (Group g : p.groups.values()) {
                    if (emitted.contains(g.name) || aliases.containsKey(g.name)) continue;
                    List<String> members = new ArrayList<>();
                    boolean pending = false;
                    for (String m : g.members) {
                        String r = resolveMember(m, tags, emitted, aliases, p);
                        if (r == null) { if (p.groups.containsKey(m) && !emitted.contains(m) && !aliases.containsKey(m)) pending = true; continue; }
                        if (r.equals(TAG_BLOCK)) { warnings.add("策略组 " + g.name + " 中的 REJECT 成员已移除"); continue; }
                        if (!members.contains(r)) members.add(r);
                    }
                    if (pending) continue;
                    changed = true;
                    if (members.isEmpty()) { aliases.put(g.name, TAG_DIRECT); warnings.add("策略组 " + g.name + " 没有可用成员，改为直连"); continue; }
                    if (members.contains(TAG_DIRECT)) tags.add(TAG_DIRECT);
                    LinkedHashMap<String, Object> ob = new LinkedHashMap<>();
                    boolean auto = g.type.equals("url-test") || g.type.equals("fallback") || g.type.equals("load-balance");
                    if (g.type.equals("fallback") || g.type.equals("load-balance"))
                        warnings.add("策略组 " + g.name + "（" + g.type + "）按 sing-box urltest 运行");
                    ob.put("type", auto ? "urltest" : "selector");
                    ob.put("tag", g.name);
                    ob.put("outbounds", members);
                    if (auto) {
                        ob.put("url", g.url.isEmpty() ? "https://www.gstatic.com/generate_204" : g.url);
                        ob.put("interval", Math.max(60, g.interval) + "s");
                        ob.put("tolerance", Math.max(0, g.tolerance));
                    } else ob.put("default", members.get(0));
                    groupObs.add(ob);
                    emitted.add(g.name);
                }
            }
            for (Group g : p.groups.values()) if (!emitted.contains(g.name) && !aliases.containsKey(g.name)) {
                aliases.put(g.name, TAG_DIRECT); warnings.add("策略组 " + g.name + " 存在循环引用，改为直连");
            }
            outbounds.addAll(groupObs);
            outbounds.add(map("type", "direct", "tag", TAG_DIRECT));
            Set<String> outboundTags = new HashSet<>(tags); outboundTags.addAll(emitted); outboundTags.add(TAG_DIRECT);
            String fin = target(p.matchTarget, outboundTags, aliases, p);
            if (fin == null || fin.equals(TAG_BLOCK)) { warnings.add("MATCH 目标不可用，改为直连"); fin = TAG_DIRECT; }

            // ---------------- route
            List<Object> rules = new ArrayList<>();
            List<Object> ruleSets = new ArrayList<>();
            Set<String> ruleSetTags = new LinkedHashSet<>();
            rules.add(map("inbound", Arrays.asList(TAG_TPROXY, TAG_REDIRECT, TAG_DNS_IN), "action", "sniff"));
            if (o.dnsPort > 0) rules.add(map("inbound", Collections.singletonList(TAG_DNS_IN), "action", "hijack-dns"));
            rules.add(map("protocol", "dns", "action", "hijack-dns"));
            boolean adblock = adblock(o, rules, ruleSets, ruleSetTags);
            for (Rule r : p.rules) {
                String t = target(r.target, outboundTags, aliases, p);
                if (t == null) { warnings.add("规则目标 " + r.target + " 不可用，已跳过：" + r.text); continue; }
                Map<String, Object> cond = condition(r.cond, p, ruleSets, ruleSetTags, warnings, r.text, fin);
                if (cond == null) continue;
                LinkedHashMap<String, Object> rule = new LinkedHashMap<>(cond);
                if (t.equals(TAG_BLOCK)) rule.put("action", "reject");
                else { rule.put("action", "route"); rule.put("outbound", t); }
                rules.add(rule);
            }
            if (o.cnIpDirect) {
                addRemoteSet(ruleSets, ruleSetTags, "geoip-cn", String.format(Locale.ROOT, SING_GEOIP, "cn"), fin);
                rules.add(map("rule_set", Collections.singletonList("geoip-cn"), "action", "route", "outbound", TAG_DIRECT));
            }
            LinkedHashMap<String, Object> route = new LinkedHashMap<>();
            route.put("rules", rules);
            if (!ruleSets.isEmpty()) route.put("rule_set", ruleSets);
            route.put("final", fin);
            route.put("auto_detect_interface", true);
            route.put("default_domain_resolver", "dns-direct");

            root.put("dns", dns(p, o, fin));
            root.put("inbounds", inbounds(o));
            root.put("outbounds", outbounds);
            root.put("route", route);
            root.put("experimental", experimental(o));
            int nodes = p.nodes.size() - p.skippedNodes;
            if (nodes == 0) throw new IOException("没有 sing-box 可用的节点：" + String.join("；", warnings));
            return new Result(CoreJson.write(root), false, warnings, nodes, p.fakeIp ? p.fakeIpRange : "", "", adblock);
        }

        static String resolveMember(String m, Set<String> tags, Set<String> emitted, Map<String, String> aliases, Profile p) {
            if (Profile.isBuiltin(m)) {
                if (blockTarget(m)) return TAG_BLOCK;
                if (m.equalsIgnoreCase("PASS")) return null;
                return TAG_DIRECT;
            }
            if (tags.contains(m) || emitted.contains(m)) return m;
            if (aliases.containsKey(m)) return aliases.get(m);
            return null;
        }

        static String target(String t, Set<String> outboundTags, Map<String, String> aliases, Profile p) {
            if (blockTarget(t)) return TAG_BLOCK;
            if (directTarget(t)) return TAG_DIRECT;
            if (aliases.containsKey(t)) return aliases.get(t);
            return outboundTags.contains(t) ? t : null;
        }

        static boolean adblock(Options o, List<Object> rules, List<Object> ruleSets, Set<String> ruleSetTags) {
            if (o.adblock.isEmpty()) return false;
            ruleSets.add(map("type", "inline", "tag", ADBLOCK_TAG, "rules", Collections.singletonList(map("domain_suffix", o.adblock))));
            ruleSetTags.add(ADBLOCK_TAG);
            if (o.adblockAllow.isEmpty()) {
                rules.add(map("rule_set", Collections.singletonList(ADBLOCK_TAG), "action", "reject"));
            } else {
                ruleSets.add(map("type", "inline", "tag", ADBLOCK_ALLOW_TAG, "rules", Collections.singletonList(map("domain_suffix", o.adblockAllow))));
                ruleSetTags.add(ADBLOCK_ALLOW_TAG);
                rules.add(map("type", "logical", "mode", "and", "rules", Arrays.asList(
                        map("rule_set", Collections.singletonList(ADBLOCK_TAG)),
                        map("rule_set", Collections.singletonList(ADBLOCK_ALLOW_TAG), "invert", true)), "action", "reject"));
            }
            return true;
        }

        /** Remote rule-sets are fetched through the profile's final proxy (raw.githubusercontent.com is often unreachable directly). */
        static void addRemoteSet(List<Object> ruleSets, Set<String> tags, String tag, String url, String detour) {
            if (!tags.add(tag)) return;
            ruleSets.add(map("type", "remote", "tag", tag, "format", "binary", "url", url, "download_detour", detour, "update_interval", "72h"));
        }

        /** Leaf or logical condition → sing-box rule fields (without action). Null = skipped with warning. */
        static Map<String, Object> condition(Cond c, Profile p, List<Object> ruleSets, Set<String> ruleSetTags, List<String> warnings, String text, String detour) {
            if (c.logical()) {
                List<Object> children = new ArrayList<>();
                for (Cond child : c.children) {
                    Map<String, Object> m = condition(child, p, ruleSets, ruleSetTags, warnings, text, detour);
                    if (m == null) return null;
                    children.add(m);
                }
                if (c.type.equals("NOT")) {
                    if (children.size() != 1) { warnings.add("NOT 规则只能包含一个条件，已跳过：" + text); return null; }
                    LinkedHashMap<String, Object> inv = new LinkedHashMap<>(asMap(children.get(0)));
                    inv.put("invert", true);
                    return inv;
                }
                return map("type", "logical", "mode", c.type.equals("AND") ? "and" : "or", "rules", children);
            }
            String v = c.value;
            switch (c.type) {
                case "DOMAIN": return map("domain", Collections.singletonList(v));
                case "DOMAIN-SUFFIX": return map("domain_suffix", Collections.singletonList(v));
                case "DOMAIN-KEYWORD": return map("domain_keyword", Collections.singletonList(v));
                case "DOMAIN-REGEX": return map("domain_regex", Collections.singletonList(v));
                case "DOMAIN-WILDCARD": return map("domain_regex", Collections.singletonList(domainEntry(v)[1]));
                case "IP-CIDR": case "IP-CIDR6": return map("ip_cidr", Collections.singletonList(v));
                case "SRC-IP-CIDR": return map("source_ip_cidr", Collections.singletonList(v));
                case "DST-PORT": return ports("port", "port_range", v);
                case "SRC-PORT": return ports("source_port", "source_port_range", v);
                case "NETWORK": return map("network", Collections.singletonList(v.toLowerCase(Locale.ROOT)));
                case "PROCESS-NAME": return v.contains(".") && !v.contains("/") ? map("package_name", Collections.singletonList(v)) : map("process_name", Collections.singletonList(v));
                case "PROCESS-PATH": return map("process_path", Collections.singletonList(v));
                case "GEOSITE": {
                    String tag = "geosite-" + v.toLowerCase(Locale.ROOT);
                    addRemoteSet(ruleSets, ruleSetTags, tag, String.format(Locale.ROOT, SING_GEOSITE, v.toLowerCase(Locale.ROOT)), detour);
                    return map("rule_set", Collections.singletonList(tag));
                }
                case "GEOIP": {
                    if (v.equalsIgnoreCase("LAN") || v.equalsIgnoreCase("private")) return map("ip_is_private", true);
                    String tag = "geoip-" + v.toLowerCase(Locale.ROOT);
                    addRemoteSet(ruleSets, ruleSetTags, tag, String.format(Locale.ROOT, SING_GEOIP, v.toLowerCase(Locale.ROOT)), detour);
                    return map("rule_set", Collections.singletonList(tag));
                }
                case "RULE-SET": {
                    RuleProvider rp = p.ruleProviders.get(v);
                    if (rp == null) { warnings.add("规则集 " + v + " 不可用，已跳过：" + text); return null; }
                    String tag = "rp-" + v;
                    if (ruleSetTags.add(tag)) {
                        List<Object> inline = providerRules(rp, warnings);
                        if (inline.isEmpty()) { warnings.add("规则集 " + v + " 为空，已跳过"); ruleSetTags.remove(tag); return null; }
                        ruleSets.add(map("type", "inline", "tag", tag, "rules", inline));
                    }
                    return map("rule_set", Collections.singletonList(tag));
                }
                default:
                    warnings.add("sing-box 不支持 " + c.type + " 规则，已跳过：" + text);
                    return null;
            }
        }

        static Map<String, Object> ports(String single, String range, String v) {
            List<Integer> list = new ArrayList<>(); List<String> ranges = new ArrayList<>();
            for (String part : v.split("[/,]")) {
                String t = part.trim();
                if (t.contains("-")) ranges.add(t.replace('-', ':'));
                else try { list.add(Integer.parseInt(t)); } catch (NumberFormatException ignored) {}
            }
            LinkedHashMap<String, Object> m = new LinkedHashMap<>();
            if (!list.isEmpty()) m.put(single, list);
            if (!ranges.isEmpty()) m.put(range, ranges);
            return m;
        }

        static List<Object> providerRules(RuleProvider rp, List<String> warnings) {
            List<Object> out = new ArrayList<>();
            if (rp.behavior.equals("domain")) {
                List<String> full = new ArrayList<>(), suffix = new ArrayList<>(), regex = new ArrayList<>();
                for (String d : rp.payload) {
                    String[] e = domainEntry(d);
                    if (e[0].equals("suffix")) suffix.add(e[1]); else if (e[0].equals("regex")) regex.add(e[1]); else full.add(e[1]);
                }
                LinkedHashMap<String, Object> m = new LinkedHashMap<>();
                if (!full.isEmpty()) m.put("domain", full);
                if (!suffix.isEmpty()) m.put("domain_suffix", suffix);
                if (!regex.isEmpty()) m.put("domain_regex", regex);
                if (!m.isEmpty()) out.add(m);
            } else if (rp.behavior.equals("ipcidr")) {
                out.add(map("ip_cidr", rp.payload));
            } else {
                for (String line : rp.payload) {
                    Cond c = classical(line);
                    if (c == null) continue;
                    Map<String, Object> m = condition(c, new Profile(), new ArrayList<>(), new HashSet<>(), warnings, line, TAG_DIRECT);
                    if (m != null && !m.containsKey("rule_set")) out.add(m);
                }
            }
            return out;
        }

        static Object dnsServer(String raw, String tag, String detour) {
            String s = raw;
            String type = "udp", server = s; Integer port = null; String path = null;
            if (s.startsWith("https://") || s.startsWith("h3://")) {
                type = s.startsWith("h3://") ? "h3" : "https";
                URI u = URI.create(s.replaceFirst("^h3://", "https://"));
                server = u.getHost(); if (u.getPort() > 0) port = u.getPort();
                path = u.getRawPath() == null || u.getRawPath().isEmpty() ? "/dns-query" : u.getRawPath();
            } else if (s.startsWith("tls://") || s.startsWith("quic://") || s.startsWith("tcp://") || s.startsWith("udp://")) {
                type = s.substring(0, s.indexOf("://"));
                String hp = s.substring(s.indexOf("://") + 3);
                int c = hp.lastIndexOf(':');
                if (c > 0 && hp.indexOf(':') == c) { server = hp.substring(0, c); try { port = Integer.parseInt(hp.substring(c + 1)); } catch (NumberFormatException e) { server = hp; } }
                else server = hp;
            } else {
                int c = s.lastIndexOf(':');
                if (c > 0 && s.indexOf(':') == c) { server = s.substring(0, c); try { port = Integer.parseInt(s.substring(c + 1)); } catch (NumberFormatException e) { server = s; } }
            }
            LinkedHashMap<String, Object> m = map("type", type, "tag", tag, "server", server);
            if (port != null) m.put("server_port", port);
            if (path != null && !path.equals("/dns-query")) m.put("path", path);
            if (!isIp(server)) m.put("domain_resolver", "dns-direct");
            if (detour != null && !detour.equals(TAG_DIRECT)) m.put("detour", detour);
            return m;
        }

        static Map<String, Object> dns(Profile p, Options o, String fin) {
            List<Object> servers = new ArrayList<>();
            String direct = DEFAULT_DIRECT_DNS;
            for (String d : p.directDns) { String host = d.replaceFirst("^udp://", ""); if (isIp(host.replaceAll(":\\d+$", ""))) { direct = host; break; } }
            servers.add(dnsServer(direct, "dns-direct", null));
            String remote = p.remoteDns.isEmpty() ? DEFAULT_REMOTE_DNS : p.remoteDns.get(0);
            servers.add(dnsServer(remote, "dns-remote", fin));
            List<Object> rules = new ArrayList<>();
            if (p.fakeIp) {
                LinkedHashMap<String, Object> fake = map("type", "fakeip", "tag", "dns-fake", "inet4_range", cidrNetwork(p.fakeIpRange));
                servers.add(fake);
                if (!p.fakeIpFilter.isEmpty()) {
                    List<String> full = new ArrayList<>(), suffix = new ArrayList<>(), regex = new ArrayList<>();
                    for (String f : p.fakeIpFilter) { String[] e = domainEntry(f); if (e[0].equals("suffix")) suffix.add(e[1]); else if (e[0].equals("regex")) regex.add(e[1]); else full.add(e[1]); }
                    LinkedHashMap<String, Object> r = new LinkedHashMap<>();
                    if (!full.isEmpty()) r.put("domain", full);
                    if (!suffix.isEmpty()) r.put("domain_suffix", suffix);
                    if (!regex.isEmpty()) r.put("domain_regex", regex);
                    r.put("action", "route"); r.put("server", "dns-remote");
                    rules.add(r);
                }
                rules.add(map("query_type", Arrays.asList("A", "AAAA"), "action", "route", "server", "dns-fake"));
            }
            LinkedHashMap<String, Object> dns = new LinkedHashMap<>();
            dns.put("servers", servers);
            if (!rules.isEmpty()) dns.put("rules", rules);
            dns.put("final", "dns-remote");
            if (!o.ipv6) dns.put("strategy", "ipv4_only");
            return dns;
        }

        static List<Object> inbounds(Options o) {
            List<Object> in = new ArrayList<>();
            String network = o.tcp && o.udp ? null : (o.udp ? "udp" : "tcp");
            if (o.tproxyPort > 0) in.add(map("type", "tproxy", "tag", TAG_TPROXY, "listen", listenAll(o), "listen_port", o.tproxyPort, "network", network));
            if (o.redirectPort > 0) in.add(map("type", "redirect", "tag", TAG_REDIRECT, "listen", listenAll(o), "listen_port", o.redirectPort));
            if (o.dnsPort > 0) in.add(map("type", "direct", "tag", TAG_DNS_IN, "listen", listenAll(o), "listen_port", o.dnsPort));
            in.add(map("type", "http", "tag", TAG_EGRESS, "listen", "127.0.0.1", "listen_port", o.egressPort));
            return in;
        }

        static Map<String, Object> experimental(Options o) {
            return map("clash_api", map("external_controller", "127.0.0.1:" + o.controllerPort, "secret", o.secret, "default_mode", "rule"),
                    "cache_file", map("enabled", true, "path", "cache.db", "store_fakeip", true));
        }

        static Map<String, Object> tls(Map<String, Object> n, boolean always, String sniKey) {
            boolean enabled = always || bool(n, "tls", false);
            if (!enabled) return null;
            LinkedHashMap<String, Object> t = new LinkedHashMap<>();
            t.put("enabled", true);
            String sni = strAny(n, sniKey, "servername", "sni");
            if (!sni.isEmpty()) t.put("server_name", sni);
            if (bool(n, "skip-cert-verify", false)) t.put("insecure", true);
            List<String> alpn = strings(n.get("alpn"));
            if (!alpn.isEmpty()) t.put("alpn", alpn);
            String fp = str(n, "client-fingerprint");
            Map<String, Object> reality = asMap(n.get("reality-opts"));
            if (reality != null && fp.isEmpty()) fp = "chrome";
            if (!fp.isEmpty() && !fp.equals("none")) t.put("utls", map("enabled", true, "fingerprint", fp.equals("random") ? "randomized" : fp));
            if (reality != null) t.put("reality", map("enabled", true, "public_key", str(reality, "public-key"), "short_id", str(reality, "short-id")));
            return t;
        }

        /** Mihomo network + opts → sing-box transport. Unsupported → throws with reason. */
        static Map<String, Object> transport(Map<String, Object> n) throws IOException {
            String net = str(n, "network").toLowerCase(Locale.ROOT);
            switch (net) {
                case "": case "tcp": return null;
                case "ws": {
                    Map<String, Object> ws = asMap(n.get("ws-opts"));
                    String path = str(ws, "path"); if (path.isEmpty()) path = "/";
                    Map<String, Object> headers = asMap(ws == null ? null : ws.get("headers"));
                    if (bool(ws, "v2ray-http-upgrade", false)) {
                        return map("type", "httpupgrade", "path", path, "host", headers == null ? null : str(headers, "Host"));
                    }
                    LinkedHashMap<String, Object> t = map("type", "ws", "path", path);
                    if (headers != null && !headers.isEmpty()) { LinkedHashMap<String, Object> h = new LinkedHashMap<>(); for (Map.Entry<String, Object> e : headers.entrySet()) h.put(e.getKey(), String.valueOf(e.getValue())); t.put("headers", h); }
                    int early = integer(ws, "max-early-data", 0);
                    if (early > 0) { t.put("max_early_data", early); t.put("early_data_header_name", str(ws, "early-data-header-name").isEmpty() ? "Sec-WebSocket-Protocol" : str(ws, "early-data-header-name")); }
                    return t;
                }
                case "grpc": {
                    Map<String, Object> g = asMap(n.get("grpc-opts"));
                    return map("type", "grpc", "service_name", str(g, "grpc-service-name"));
                }
                case "h2": {
                    Map<String, Object> h = asMap(n.get("h2-opts"));
                    List<String> host = strings(h == null ? null : h.get("host"));
                    return map("type", "http", "host", host.isEmpty() ? null : host, "path", str(h, "path").isEmpty() ? "/" : str(h, "path"));
                }
                default: throw new IOException("传输 " + net + " 不受支持");
            }
        }

        static Map<String, Object> outbound(Node node, List<String> warnings) {
            Map<String, Object> n = node.raw;
            String server = str(n, "server");
            int port = integer(n, "port", 0);
            try {
                if (server.isEmpty()) throw new IOException("缺少服务器地址");
                LinkedHashMap<String, Object> ob = new LinkedHashMap<>();
                switch (node.type) {
                    case "ss": {
                        ob.put("type", "shadowsocks"); base(ob, node, server, port);
                        ob.put("method", str(n, "cipher")); ob.put("password", str(n, "password"));
                        String plugin = str(n, "plugin");
                        Map<String, Object> po = asMap(n.get("plugin-opts"));
                        if (plugin.equals("obfs")) { ob.put("plugin", "obfs-local"); ob.put("plugin_opts", "obfs=" + (str(po, "mode").isEmpty() ? "http" : str(po, "mode")) + (str(po, "host").isEmpty() ? "" : ";obfs-host=" + str(po, "host"))); }
                        else if (plugin.equals("v2ray-plugin")) {
                            StringBuilder opts = new StringBuilder("mode=websocket");
                            if (bool(po, "tls", false)) opts.append(";tls");
                            if (!str(po, "host").isEmpty()) opts.append(";host=").append(str(po, "host"));
                            if (!str(po, "path").isEmpty()) opts.append(";path=").append(str(po, "path"));
                            ob.put("plugin", "v2ray-plugin"); ob.put("plugin_opts", opts.toString());
                        } else if (!plugin.isEmpty()) throw new IOException("SS 插件 " + plugin + " 不受支持");
                        if (bool(n, "udp-over-tcp", false)) ob.put("udp_over_tcp", true);
                        break;
                    }
                    case "vmess": {
                        ob.put("type", "vmess"); base(ob, node, server, port);
                        ob.put("uuid", str(n, "uuid")); ob.put("security", str(n, "cipher").isEmpty() ? "auto" : str(n, "cipher"));
                        ob.put("alter_id", integer(n, "alterId", 0));
                        put(ob, "tls", tls(n, false, "servername")); put(ob, "transport", transport(n));
                        break;
                    }
                    case "vless": {
                        ob.put("type", "vless"); base(ob, node, server, port);
                        ob.put("uuid", str(n, "uuid"));
                        if (!str(n, "flow").isEmpty()) ob.put("flow", str(n, "flow"));
                        put(ob, "tls", tls(n, asMap(n.get("reality-opts")) != null, "servername")); put(ob, "transport", transport(n));
                        if (!str(n, "packet-encoding").isEmpty()) ob.put("packet_encoding", str(n, "packet-encoding"));
                        break;
                    }
                    case "trojan": {
                        ob.put("type", "trojan"); base(ob, node, server, port);
                        ob.put("password", str(n, "password"));
                        put(ob, "tls", tls(n, true, "sni")); put(ob, "transport", transport(n));
                        break;
                    }
                    case "hysteria2": {
                        ob.put("type", "hysteria2"); ob.put("tag", node.name); ob.put("server", server);
                        String ports = str(n, "ports");
                        if (!ports.isEmpty()) {
                            List<String> list = new ArrayList<>();
                            for (String part : ports.split(",")) { String t = part.trim(); if (t.isEmpty()) continue; list.add(t.contains("-") ? t.replace('-', ':') : t + ":" + t); }
                            ob.put("server_ports", list);
                            if (!str(n, "hop-interval").isEmpty()) ob.put("hop_interval", str(n, "hop-interval").replaceAll("[^0-9]", "") + "s");
                        } else ob.put("server_port", port);
                        ob.put("password", strAny(n, "password", "auth"));
                        int up = mbps(n.get("up")), down = mbps(n.get("down"));
                        if (up > 0) ob.put("up_mbps", up);
                        if (down > 0) ob.put("down_mbps", down);
                        if (!str(n, "obfs").isEmpty()) ob.put("obfs", map("type", str(n, "obfs"), "password", str(n, "obfs-password")));
                        put(ob, "tls", tls(n, true, "sni"));
                        break;
                    }
                    case "tuic": {
                        if (!str(n, "token").isEmpty()) throw new IOException("TUIC v4 不受支持");
                        ob.put("type", "tuic"); base(ob, node, server, port);
                        ob.put("uuid", str(n, "uuid")); ob.put("password", str(n, "password"));
                        if (!str(n, "congestion-controller").isEmpty()) ob.put("congestion_control", str(n, "congestion-controller"));
                        if (!str(n, "udp-relay-mode").isEmpty()) ob.put("udp_relay_mode", str(n, "udp-relay-mode"));
                        if (bool(n, "reduce-rtt", false)) ob.put("zero_rtt_handshake", true);
                        put(ob, "tls", tls(n, true, "sni"));
                        break;
                    }
                    case "anytls": {
                        ob.put("type", "anytls"); base(ob, node, server, port);
                        ob.put("password", str(n, "password"));
                        put(ob, "tls", tls(n, true, "sni"));
                        break;
                    }
                    case "socks5": {
                        if (bool(n, "tls", false)) throw new IOException("SOCKS5 over TLS 不受支持");
                        ob.put("type", "socks"); base(ob, node, server, port);
                        if (!str(n, "username").isEmpty()) { ob.put("username", str(n, "username")); ob.put("password", str(n, "password")); }
                        break;
                    }
                    case "http": {
                        ob.put("type", "http"); base(ob, node, server, port);
                        if (!str(n, "username").isEmpty()) { ob.put("username", str(n, "username")); ob.put("password", str(n, "password")); }
                        put(ob, "tls", tls(n, false, "sni"));
                        break;
                    }
                    default: throw new IOException("协议 " + node.type + " 不受支持");
                }
                return ob;
            } catch (IOException unsupported) {
                warnings.add("节点 " + node.name + " 已跳过：" + unsupported.getMessage());
                return null;
            }
        }

        static void base(Map<String, Object> ob, Node node, String server, int port) throws IOException {
            if (port <= 0 || port > 65535) throw new IOException("端口无效");
            ob.put("tag", node.name); ob.put("server", server); ob.put("server_port", port);
        }

        static void put(Map<String, Object> m, String k, Object v) { if (v != null) m.put(k, v); }
    }

    static boolean isIp(String s) {
        if (s == null || s.isEmpty()) return false;
        if (s.matches("\\d{1,3}(\\.\\d{1,3}){3}")) return true;
        return s.contains(":") && s.matches("[0-9a-fA-F:\\[\\]]+");
    }

    /** "198.18.0.1/16" → "198.18.0.0/16". */
    static String cidrNetwork(String cidr) {
        try {
            String[] parts = cidr.split("/");
            String[] o = parts[0].split("\\.");
            int bits = Integer.parseInt(parts[1]);
            long ip = 0; for (String x : o) ip = (ip << 8) | Integer.parseInt(x);
            long mask = bits == 0 ? 0 : (0xffffffffL << (32 - bits)) & 0xffffffffL;
            ip &= mask;
            return ((ip >> 24) & 255) + "." + ((ip >> 16) & 255) + "." + ((ip >> 8) & 255) + "." + (ip & 255) + "/" + bits;
        } catch (Exception invalid) { return "198.18.0.0/16"; }
    }

    // ================================================================== Xray / V2Fly

    static final class XrayConv {
        static Result convert(Profile p, Options o, List<String> warnings, boolean v2ray) throws IOException {
            String label = v2ray ? "V2Fly" : "Xray";
            List<Object> outbounds = new ArrayList<>();
            // Xray balancer selectors match outbound tags by prefix ("HK" would also pick "HK 2"):
            // every node tag starts with a fixed-width index, so a full tag is never another's prefix.
            Map<String, String> tags = new LinkedHashMap<>();
            int index = 0;
            for (Node n : p.nodes.values()) {
                String tag = String.format(Locale.ROOT, "%04d %s", ++index, n.name);
                Map<String, Object> ob = outbound(n, tag, warnings, v2ray);
                if (ob == null) { p.skippedNodes++; continue; }
                outbounds.add(ob); tags.put(n.name, tag);
            }
            int nodes = tags.size();
            if (nodes == 0) throw new IOException("没有 " + label + " 可用的节点：" + String.join("；", warnings));
            // Groups: select → its default (first) member; url-test/fallback/load-balance → balancer.
            Map<String, String[]> resolved = new LinkedHashMap<>(); // name -> {kind(outbound|balancer), tag}
            List<Object> balancers = new ArrayList<>();
            boolean observe = false;
            for (Group g : p.groups.values()) {
                String[] r = resolveGroup(g, p, tags, resolved, new HashSet<>(), warnings, balancers, label);
                if (r != null && r[0].equals("balancer")) observe = true;
            }
            outbounds.add(v2ray ? map("tag", TAG_DIRECT, "protocol", "freedom", "settings", map("domainStrategy", "UseIP"))
                    : map("tag", TAG_DIRECT, "protocol", "freedom", "streamSettings", map("sockopt", map("domainStrategy", "UseIP"))));
            outbounds.add(map("tag", TAG_BLOCK, "protocol", "blackhole"));
            outbounds.add(map("tag", TAG_DNS_OUT, "protocol", "dns"));

            List<Object> rules = new ArrayList<>();
            if (o.dnsPort > 0) rules.add(map("type", "field", "inboundTag", Collections.singletonList(TAG_DNS_IN), "outboundTag", TAG_DNS_OUT));
            rules.add(map("type", "field", "inboundTag", Arrays.asList(TAG_TPROXY, TAG_REDIRECT), "port", 53, "outboundTag", TAG_DNS_OUT));
            boolean adblock = false;
            if (!o.adblock.isEmpty()) {
                List<String> blocked = subtractAllowed(o.adblock, o.adblockAllow);
                if (!blocked.isEmpty()) {
                    List<String> domains = new ArrayList<>(blocked.size());
                    for (String d : blocked) domains.add("domain:" + d);
                    rules.add(map("type", "field", "domain", domains, "outboundTag", TAG_BLOCK));
                    adblock = true;
                }
            }
            for (Rule r : p.rules) {
                String[] t = target(r.target, p, tags, resolved, warnings, balancers, label);
                if (t == null) { warnings.add("规则目标 " + r.target + " 不可用，已跳过：" + r.text); continue; }
                Map<String, Object> cond = condition(r.cond, p, o, warnings, r.text, v2ray);
                if (cond == null) continue;
                LinkedHashMap<String, Object> rule = new LinkedHashMap<>();
                rule.put("type", "field");
                rule.putAll(cond);
                rule.put(t[0].equals("balancer") ? "balancerTag" : "outboundTag", t[1]);
                rules.add(rule);
            }
            if (o.cnIpDirect) {
                if (o.geoAssets) rules.add(map("type", "field", "ip", Collections.singletonList("geoip:cn"), "outboundTag", TAG_DIRECT));
                else warnings.add("缺少 geoip.dat，「中国 IP 直连」未生效");
            }
            String[] fin = target(p.matchTarget, p, tags, resolved, warnings, balancers, label);
            if (fin == null) { warnings.add("MATCH 目标不可用，改为直连"); fin = new String[]{"outbound", TAG_DIRECT}; }
            LinkedHashMap<String, Object> last = map("type", "field", "network", "tcp,udp");
            last.put(fin[0].equals("balancer") ? "balancerTag" : "outboundTag", fin[1]);
            rules.add(last);

            LinkedHashMap<String, Object> routing = new LinkedHashMap<>();
            routing.put("domainStrategy", "IPIfNonMatch");
            routing.put("rules", rules);
            if (!balancers.isEmpty()) routing.put("balancers", balancers);

            LinkedHashMap<String, Object> root = new LinkedHashMap<>();
            root.put("log", map("loglevel", "warning"));
            root.put("dns", dns(p, o, v2ray));
            if (p.fakeIp) root.put("fakedns", Collections.singletonList(map("ipPool", cidrNetwork(p.fakeIpRange), "poolSize", 65535)));
            root.put("inbounds", inbounds(o, p.fakeIp));
            // Ordered: the first outbound is the default when no rule matches.
            List<Object> ordered = new ArrayList<>();
            for (Object ob : outbounds) if (fin[0].equals("outbound") && fin[1].equals(asMap(ob).get("tag"))) ordered.add(ob);
            for (Object ob : outbounds) if (!ordered.contains(ob)) ordered.add(ob);
            root.put("outbounds", ordered);
            root.put("routing", routing);
            if (observe) {
                List<String> selectors = new ArrayList<>();
                for (Object b : balancers) for (String s : strings(asMap(b).get("selector"))) if (!selectors.contains(s)) selectors.add(s);
                Group probe = null; for (Group g : p.groups.values()) if (!g.url.isEmpty()) { probe = g; break; }
                root.put("observatory", map("subjectSelector", selectors, "probeURL", probe == null ? "https://www.gstatic.com/generate_204" : probe.url, "probeInterval", "5m"));
            }
            return new Result(CoreJson.write(root), false, warnings, nodes, p.fakeIp ? p.fakeIpRange : "", "", adblock);
        }

        /** Returns {"outbound"|"balancer", tag}. */
        static String[] resolveGroup(Group g, Profile p, Map<String, String> tags, Map<String, String[]> resolved, Set<String> visiting,
                                     List<String> warnings, List<Object> balancers, String label) {
            if (resolved.containsKey(g.name)) return resolved.get(g.name);
            if (!visiting.add(g.name)) { warnings.add("策略组 " + g.name + " 存在循环引用，改为直连"); return new String[]{"outbound", TAG_DIRECT}; }
            String[] r;
            if (g.type.equals("select") || g.type.equals("relay")) {
                if (g.type.equals("relay")) warnings.add("策略组 " + g.name + "（relay）不受支持，按其首个成员运行");
                r = null;
                for (String m : g.members) {
                    r = member(m, p, tags, resolved, visiting, warnings, balancers, label);
                    if (r != null) break;
                }
                if (r == null) r = new String[]{"outbound", TAG_DIRECT};
                if (g.members.size() > 1) warnings.add(label + " 没有手动选择接口：策略组 " + g.name + " 固定使用默认成员 " + r[1]);
            } else {
                List<String> flat = new ArrayList<>();
                flatten(g, p, tags, flat, new HashSet<>());
                if (flat.isEmpty()) r = new String[]{"outbound", TAG_DIRECT};
                else if (flat.size() == 1) r = new String[]{"outbound", flat.get(0)};
                else {
                    String tag = "balancer-" + g.name;
                    balancers.add(map("tag", tag, "selector", flat, "strategy", map("type", g.type.equals("load-balance") ? "random" : "leastPing")));
                    r = new String[]{"balancer", tag};
                    if (g.type.equals("fallback")) warnings.add("策略组 " + g.name + "（fallback）按最低延迟均衡器运行");
                }
            }
            visiting.remove(g.name);
            resolved.put(g.name, r);
            return r;
        }

        static void flatten(Group g, Profile p, Map<String, String> tags, List<String> out, Set<String> seen) {
            if (!seen.add(g.name)) return;
            for (String m : g.members) {
                if (tags.containsKey(m)) { if (!out.contains(tags.get(m))) out.add(tags.get(m)); }
                else if (p.groups.containsKey(m)) flatten(p.groups.get(m), p, tags, out, seen);
            }
        }

        static String[] member(String m, Profile p, Map<String, String> tags, Map<String, String[]> resolved, Set<String> visiting,
                               List<String> warnings, List<Object> balancers, String label) {
            if (blockTarget(m)) return new String[]{"outbound", TAG_BLOCK};
            if (directTarget(m)) return new String[]{"outbound", TAG_DIRECT};
            if (m.equalsIgnoreCase("PASS")) return null;
            if (tags.containsKey(m)) return new String[]{"outbound", tags.get(m)};
            Group g = p.groups.get(m);
            if (g != null) return resolveGroup(g, p, tags, resolved, visiting, warnings, balancers, label);
            return null;
        }

        static String[] target(String t, Profile p, Map<String, String> tags, Map<String, String[]> resolved, List<String> warnings, List<Object> balancers, String label) {
            return member(t, p, tags, resolved, new HashSet<>(), warnings, balancers, label);
        }

        static Map<String, Object> condition(Cond c, Profile p, Options o, List<String> warnings, String text, boolean v2ray) {
            if (c.logical()) {
                if (c.type.equals("AND")) {
                    // An AND of fields that land in different Xray rule fields is expressible as one rule.
                    LinkedHashMap<String, Object> merged = new LinkedHashMap<>();
                    for (Cond child : c.children) {
                        Map<String, Object> m = condition(child, p, o, warnings, text, v2ray);
                        if (m == null) return null;
                        for (Map.Entry<String, Object> e : m.entrySet()) {
                            if (merged.containsKey(e.getKey()) && !(e.getKey().equals("domain") || e.getKey().equals("ip"))) {
                                warnings.add("AND 规则包含同类条件，Xray 无法表达，已跳过：" + text); return null;
                            }
                            if (merged.containsKey(e.getKey())) { warnings.add("AND 规则包含同类条件，Xray 无法表达，已跳过：" + text); return null; }
                            merged.put(e.getKey(), e.getValue());
                        }
                    }
                    return merged;
                }
                if (c.type.equals("OR")) {
                    LinkedHashMap<String, Object> merged = new LinkedHashMap<>();
                    for (Cond child : c.children) {
                        Map<String, Object> m = condition(child, p, o, warnings, text, v2ray);
                        if (m == null) return null;
                        if (m.size() != 1 || !(m.containsKey("domain") || m.containsKey("ip"))) { warnings.add("OR 规则包含非域名/IP 条件，Xray 无法表达，已跳过：" + text); return null; }
                        for (Map.Entry<String, Object> e : m.entrySet()) {
                            @SuppressWarnings("unchecked") List<Object> l = (List<Object>) merged.computeIfAbsent(e.getKey(), k -> new ArrayList<>());
                            l.addAll(asList(e.getValue()));
                        }
                    }
                    if (merged.size() != 1) { warnings.add("OR 规则同时包含域名与 IP，Xray 无法表达，已跳过：" + text); return null; }
                    return merged;
                }
                warnings.add("NOT 规则 Xray 无法表达，已跳过：" + text);
                return null;
            }
            String v = c.value;
            switch (c.type) {
                case "DOMAIN": return map("domain", Collections.singletonList("full:" + v));
                case "DOMAIN-SUFFIX": return map("domain", Collections.singletonList("domain:" + v));
                case "DOMAIN-KEYWORD": return map("domain", Collections.singletonList(v));
                case "DOMAIN-REGEX": return map("domain", Collections.singletonList("regexp:" + v));
                case "DOMAIN-WILDCARD": return map("domain", Collections.singletonList("regexp:" + domainEntry(v)[1]));
                case "IP-CIDR": case "IP-CIDR6": return map("ip", Collections.singletonList(v));
                case "SRC-IP-CIDR": return map("source", Collections.singletonList(v));
                case "DST-PORT": return map("port", v.replace('/', ','));
                case "SRC-PORT": return map("sourcePort", v.replace('/', ','));
                case "NETWORK": return map("network", v.toLowerCase(Locale.ROOT));
                case "GEOSITE":
                    if (!o.geoAssets) { warnings.add("缺少 geosite.dat，已跳过：" + text); return null; }
                    return map("domain", Collections.singletonList("geosite:" + v.toLowerCase(Locale.ROOT)));
                case "GEOIP":
                    if (v.equalsIgnoreCase("LAN")) v = "private";
                    if (!o.geoAssets) { warnings.add("缺少 geoip.dat，已跳过：" + text); return null; }
                    return map("ip", Collections.singletonList("geoip:" + v.toLowerCase(Locale.ROOT)));
                case "RULE-SET": {
                    RuleProvider rp = p.ruleProviders.get(v);
                    if (rp == null) { warnings.add("规则集 " + v + " 不可用，已跳过：" + text); return null; }
                    if (rp.behavior.equals("domain")) {
                        List<String> d = new ArrayList<>();
                        for (String entry : rp.payload) { String[] e = domainEntry(entry); d.add((e[0].equals("suffix") ? "domain:" : e[0].equals("regex") ? "regexp:" : "full:") + e[1]); }
                        return d.isEmpty() ? null : map("domain", d);
                    }
                    if (rp.behavior.equals("ipcidr")) return rp.payload.isEmpty() ? null : map("ip", rp.payload);
                    // classical: only a pure domain / pure ip set fits one Xray rule.
                    List<String> domains = new ArrayList<>(), ips = new ArrayList<>();
                    for (String line : rp.payload) {
                        Cond cc = classical(line); if (cc == null) continue;
                        Map<String, Object> m = condition(cc, p, o, new ArrayList<>(), line, v2ray);
                        if (m == null) continue;
                        if (m.containsKey("domain")) domains.addAll(strings(m.get("domain")));
                        else if (m.containsKey("ip")) ips.addAll(strings(m.get("ip")));
                    }
                    if (!domains.isEmpty() && !ips.isEmpty()) { warnings.add("规则集 " + v + " 同时含域名与 IP，仅保留域名部分"); }
                    if (!domains.isEmpty()) return map("domain", domains);
                    if (!ips.isEmpty()) return map("ip", ips);
                    return null;
                }
                case "PROCESS-NAME":
                    warnings.add((v2ray ? "V2Fly" : "Xray") + " 不支持按应用分流，已跳过：" + text);
                    return null;
                default:
                    warnings.add((v2ray ? "V2Fly" : "Xray") + " 不支持 " + c.type + " 规则，已跳过：" + text);
                    return null;
            }
        }

        static Map<String, Object> dns(Profile p, Options o, boolean v2ray) {
            List<Object> servers = new ArrayList<>();
            if (p.fakeIp) servers.add("fakedns");
            for (String s : p.remoteDns) {
                String x = s;
                if (x.startsWith("udp://")) x = x.substring(6);
                if (x.startsWith("tls://")) continue; // not a DoT client in Xray/V2Fly
                servers.add(x);
                if (servers.size() >= 3) break;
            }
            if (servers.isEmpty() || (servers.size() == 1 && "fakedns".equals(servers.get(0)))) servers.add(DEFAULT_REMOTE_DNS);
            String direct = DEFAULT_DIRECT_DNS;
            for (String d : p.directDns) { String host = d.replaceFirst("^udp://", ""); if (isIp(host.replaceAll(":\\d+$", ""))) { direct = host; break; } }
            servers.add(direct);
            LinkedHashMap<String, Object> m = new LinkedHashMap<>();
            m.put("servers", servers);
            m.put("queryStrategy", o.ipv6 ? "UseIP" : "UseIPv4");
            m.put("tag", "hetu-dns");
            return m;
        }

        static List<Object> inbounds(Options o, boolean fakeIp) {
            List<Object> in = new ArrayList<>();
            List<String> sniff = fakeIp ? Arrays.asList("http", "tls", "quic", "fakedns") : Arrays.asList("http", "tls", "quic");
            String network = o.tcp && o.udp ? "tcp,udp" : (o.udp ? "udp" : "tcp");
            if (o.tproxyPort > 0)
                in.add(map("tag", TAG_TPROXY, "listen", listenAll(o), "port", o.tproxyPort, "protocol", "dokodemo-door",
                        "settings", map("network", network, "followRedirect", true),
                        "streamSettings", map("sockopt", map("tproxy", "tproxy")),
                        "sniffing", map("enabled", true, "destOverride", sniff, "routeOnly", true)));
            if (o.redirectPort > 0)
                in.add(map("tag", TAG_REDIRECT, "listen", listenAll(o), "port", o.redirectPort, "protocol", "dokodemo-door",
                        "settings", map("network", "tcp", "followRedirect", true),
                        "streamSettings", map("sockopt", map("tproxy", "redirect")),
                        "sniffing", map("enabled", true, "destOverride", sniff, "routeOnly", true)));
            if (o.dnsPort > 0)
                in.add(map("tag", TAG_DNS_IN, "listen", listenAll(o), "port", o.dnsPort, "protocol", "dokodemo-door",
                        "settings", map("address", DEFAULT_REMOTE_DNS, "port", 53, "network", "tcp,udp")));
            in.add(map("tag", TAG_EGRESS, "listen", "127.0.0.1", "port", o.egressPort, "protocol", "http"));
            return in;
        }

        static Map<String, Object> stream(Map<String, Object> n, String type, boolean v2ray, String sniKey, boolean tlsAlways) throws IOException {
            LinkedHashMap<String, Object> s = new LinkedHashMap<>();
            String net = str(n, "network").toLowerCase(Locale.ROOT);
            Map<String, Object> reality = asMap(n.get("reality-opts"));
            boolean tls = tlsAlways || bool(n, "tls", false) || reality != null;
            switch (net) {
                case "": case "tcp": s.put("network", "tcp"); break;
                case "ws": {
                    Map<String, Object> ws = asMap(n.get("ws-opts"));
                    String path = str(ws, "path"); if (path.isEmpty()) path = "/";
                    Map<String, Object> headers = asMap(ws == null ? null : ws.get("headers"));
                    String host = headers == null ? "" : str(headers, "Host");
                    if (bool(ws, "v2ray-http-upgrade", false)) {
                        s.put("network", "httpupgrade");
                        s.put("httpupgradeSettings", map("path", path, "host", host.isEmpty() ? null : host));
                    } else {
                        s.put("network", "ws");
                        LinkedHashMap<String, Object> ws2 = map("path", path);
                        if (!host.isEmpty()) ws2.put(v2ray ? "headers" : "host", v2ray ? map("Host", host) : host);
                        s.put("wsSettings", ws2);
                    }
                    break;
                }
                case "grpc": {
                    Map<String, Object> g = asMap(n.get("grpc-opts"));
                    s.put("network", "grpc");
                    s.put("grpcSettings", map("serviceName", str(g, "grpc-service-name")));
                    break;
                }
                case "h2": {
                    Map<String, Object> h = asMap(n.get("h2-opts"));
                    List<String> host = strings(h == null ? null : h.get("host"));
                    if (!v2ray) {
                        // Xray dropped the h2 transport; XHTTP (stream-one) is the HTTP/2 successor.
                        s.put("network", "xhttp");
                        s.put("xhttpSettings", map("host", host.isEmpty() ? null : host.get(0), "path", str(h, "path").isEmpty() ? "/" : str(h, "path"), "mode", "stream-one"));
                    } else {
                        s.put("network", "http");
                        s.put("httpSettings", map("host", host.isEmpty() ? null : host, "path", str(h, "path").isEmpty() ? "/" : str(h, "path")));
                    }
                    break;
                }
                case "http": {
                    Map<String, Object> h = asMap(n.get("http-opts"));
                    List<String> path = strings(h == null ? null : h.get("path"));
                    Map<String, Object> headers = asMap(h == null ? null : h.get("headers"));
                    LinkedHashMap<String, Object> req = map("path", path.isEmpty() ? Collections.singletonList("/") : path);
                    if (headers != null && headers.get("Host") != null) req.put("headers", map("Host", strings(headers.get("Host"))));
                    s.put("network", "tcp");
                    s.put("tcpSettings", map("header", map("type", "http", "request", req)));
                    break;
                }
                default: throw new IOException("传输 " + net + " 不受支持");
            }
            String sni = strAny(n, sniKey, "servername", "sni");
            List<String> alpn = strings(n.get("alpn"));
            String fp = str(n, "client-fingerprint");
            if (reality != null) {
                if (v2ray) throw new IOException("REALITY 不受 V2Fly 支持");
                s.put("security", "reality");
                s.put("realitySettings", map("serverName", sni, "fingerprint", fp.isEmpty() ? "chrome" : fp, "publicKey", str(reality, "public-key"), "shortId", str(reality, "short-id"), "spiderX", ""));
            } else if (tls) {
                s.put("security", "tls");
                LinkedHashMap<String, Object> t = new LinkedHashMap<>();
                if (!sni.isEmpty()) t.put("serverName", sni);
                if (bool(n, "skip-cert-verify", false)) t.put("allowInsecure", true);
                if (!alpn.isEmpty()) t.put("alpn", alpn);
                if (!fp.isEmpty() && !v2ray) t.put("fingerprint", fp);
                s.put("tlsSettings", t);
            }
            if (!v2ray) s.put("sockopt", map("domainStrategy", "UseIP"));
            return s;
        }

        static Map<String, Object> outbound(Node node, String tag, List<String> warnings, boolean v2ray) {
            Map<String, Object> n = node.raw;
            String server = str(n, "server");
            int port = integer(n, "port", 0);
            String label = v2ray ? "V2Fly" : "Xray";
            try {
                if (server.isEmpty() || port <= 0 || port > 65535) throw new IOException("服务器或端口无效");
                LinkedHashMap<String, Object> ob = new LinkedHashMap<>();
                ob.put("tag", tag);
                switch (node.type) {
                    case "ss": {
                        String method = str(n, "cipher");
                        if (!str(n, "plugin").isEmpty()) throw new IOException("SS 插件不受 " + label + " 支持");
                        if (v2ray && method.startsWith("2022-")) throw new IOException("SS 2022 不受 V2Fly v4 配置支持");
                        ob.put("protocol", "shadowsocks");
                        LinkedHashMap<String, Object> srv = map("address", server, "port", port, "method", method, "password", str(n, "password"));
                        if (!v2ray && bool(n, "udp-over-tcp", false)) srv.put("uot", true);
                        ob.put("settings", map("servers", Collections.singletonList(srv)));
                        if (!v2ray) ob.put("streamSettings", map("sockopt", map("domainStrategy", "UseIP")));
                        break;
                    }
                    case "vmess": {
                        ob.put("protocol", "vmess");
                        ob.put("settings", map("vnext", Collections.singletonList(map("address", server, "port", port, "users",
                                Collections.singletonList(map("id", str(n, "uuid"), "alterId", integer(n, "alterId", 0), "security", str(n, "cipher").isEmpty() ? "auto" : str(n, "cipher")))))));
                        ob.put("streamSettings", stream(n, "vmess", v2ray, "servername", false));
                        break;
                    }
                    case "vless": {
                        String flow = str(n, "flow");
                        if (v2ray && !flow.isEmpty()) throw new IOException("VLESS flow 不受 V2Fly 支持");
                        ob.put("protocol", "vless");
                        LinkedHashMap<String, Object> user = map("id", str(n, "uuid"), "encryption", "none");
                        if (!flow.isEmpty()) user.put("flow", flow);
                        ob.put("settings", map("vnext", Collections.singletonList(map("address", server, "port", port, "users", Collections.singletonList(user)))));
                        ob.put("streamSettings", stream(n, "vless", v2ray, "servername", false));
                        break;
                    }
                    case "trojan": {
                        ob.put("protocol", "trojan");
                        ob.put("settings", map("servers", Collections.singletonList(map("address", server, "port", port, "password", str(n, "password")))));
                        ob.put("streamSettings", stream(n, "trojan", v2ray, "sni", true));
                        break;
                    }
                    case "socks5": {
                        if (bool(n, "tls", false)) throw new IOException("SOCKS5 over TLS 不受支持");
                        ob.put("protocol", "socks");
                        LinkedHashMap<String, Object> srv = map("address", server, "port", port);
                        if (!str(n, "username").isEmpty()) srv.put("users", Collections.singletonList(map("user", str(n, "username"), "pass", str(n, "password"))));
                        ob.put("settings", map("servers", Collections.singletonList(srv)));
                        break;
                    }
                    case "http": {
                        ob.put("protocol", "http");
                        LinkedHashMap<String, Object> srv = map("address", server, "port", port);
                        if (!str(n, "username").isEmpty()) srv.put("users", Collections.singletonList(map("user", str(n, "username"), "pass", str(n, "password"))));
                        ob.put("settings", map("servers", Collections.singletonList(srv)));
                        if (bool(n, "tls", false)) ob.put("streamSettings", stream(n, "http", v2ray, "sni", true));
                        break;
                    }
                    default: throw new IOException("协议 " + node.type + " 不受 " + label + " 支持");
                }
                return ob;
            } catch (IOException unsupported) {
                warnings.add("节点 " + node.name + " 已跳过：" + unsupported.getMessage());
                return null;
            }
        }
    }

    // ================================================================== Hysteria 2

    static final class Hysteria {
        static Result convert(Profile p, Options o, List<String> warnings) throws IOException {
            Node chosen = null;
            int count = 0;
            for (Node n : p.nodes.values()) if (n.type.equals("hysteria2")) { count++; if (chosen == null) chosen = n; }
            if (chosen == null) throw new IOException("配置中没有 hysteria2 节点；Hysteria 核心只能连接一个 Hysteria 2 服务器");
            warnings.add("Hysteria 是单服务器客户端：全部流量经节点「" + chosen.name + "」，规则分流、策略组、面板与测速不可用");
            if (count > 1) warnings.add("其余 " + (count - 1) + " 个 hysteria2 节点未使用");
            int others = p.nodes.size() - count;
            if (others > 0) warnings.add(others + " 个非 hysteria2 节点不受 Hysteria 核心支持");
            if (!p.rules.isEmpty()) warnings.add(p.rules.size() + " 条分流规则未生效");
            Map<String, Object> n = chosen.raw;
            StringBuilder y = new StringBuilder();
            y.append("# Hetu: Hysteria 2 client generated from Clash/Mihomo node ").append(yamlString(chosen.name)).append('\n');
            String server = str(n, "server");
            String sni = strAny(n, "sni", "servername");
            String address = resolveServer(server, o, warnings);
            if (!address.equals(server) && sni.isEmpty()) sni = server;
            String host = address.contains(":") && !address.startsWith("[") ? "[" + address + "]" : address;
            String ports = str(n, "ports");
            y.append("server: ").append(yamlString(host + ":" + (ports.isEmpty() ? String.valueOf(integer(n, "port", 443)) : ports.replace(" ", "")))).append('\n');
            y.append("auth: ").append(yamlString(strAny(n, "password", "auth"))).append('\n');
            y.append("tls:\n");
            if (!sni.isEmpty()) y.append("  sni: ").append(yamlString(sni)).append('\n');
            y.append("  insecure: ").append(bool(n, "skip-cert-verify", false)).append('\n');
            if (!str(n, "obfs").isEmpty()) {
                y.append("obfs:\n  type: ").append(yamlString(str(n, "obfs"))).append('\n');
                y.append("  ").append(str(n, "obfs")).append(":\n    password: ").append(yamlString(str(n, "obfs-password"))).append('\n');
            }
            int up = mbps(n.get("up")), down = mbps(n.get("down"));
            if (up > 0 || down > 0) {
                y.append("bandwidth:\n");
                if (up > 0) y.append("  up: ").append(up).append(" mbps\n");
                if (down > 0) y.append("  down: ").append(down).append(" mbps\n");
            }
            if (!ports.isEmpty() && !str(n, "hop-interval").isEmpty())
                y.append("transport:\n  udp:\n    hopInterval: ").append(str(n, "hop-interval").replaceAll("[^0-9]", "")).append("s\n");
            String direct = DEFAULT_DIRECT_DNS;
            for (String d : p.directDns) { String h = d.replaceFirst("^udp://", ""); if (isIp(h.replaceAll(":\\d+$", ""))) { direct = h; break; } }
            appendHetu(y, o, direct.contains(":") ? direct : direct + ":53", remoteDnsForForwarding(p));
            return new Result(y.toString(), false, warnings, 1, "", "", false);
        }

        static String remoteDnsForForwarding(Profile p) {
            for (String s : p.remoteDns) {
                String x = s.replaceFirst("^(udp|tcp)://", "");
                if (isIp(x.replaceAll(":\\d+$", ""))) return x.contains(":") ? x : x + ":53";
            }
            return DEFAULT_REMOTE_DNS + ":53";
        }

        static void appendHetu(StringBuilder y, Options o, String resolver, String remoteDns) {
            y.append("# --- Hetu runtime isolation ---\n");
            // Go's resolver has no system DNS on Android; resolve the server name explicitly.
            y.append("resolver:\n  type: udp\n  udp:\n    addr: ").append(resolver).append("\n    timeout: 4s\n");
            if (o.tproxyPort > 0) {
                if (o.tcp) y.append("tcpTProxy:\n  listen: :").append(o.tproxyPort).append('\n');
                if (o.udp) y.append("udpTProxy:\n  listen: :").append(o.tproxyPort).append("\n  timeout: 60s\n");
            }
            if (o.redirectPort > 0) y.append("tcpRedirect:\n  listen: :").append(o.redirectPort).append('\n');
            y.append("http:\n  listen: 127.0.0.1:").append(o.egressPort).append('\n');
            if (o.dnsPort > 0) {
                // DNS is answered by the remote resolver through the Hysteria tunnel (no fake-IP).
                y.append("tcpForwarding:\n  - listen: :").append(o.dnsPort).append("\n    remote: ").append(remoteDns).append('\n');
                y.append("udpForwarding:\n  - listen: :").append(o.dnsPort).append("\n    remote: ").append(remoteDns).append("\n    timeout: 20s\n");
            }
        }

        static String yamlString(String s) {
            StringBuilder b = new StringBuilder();
            CoreJson.string(b, s == null ? "" : s);
            return b.toString();
        }
    }

    // ================================================================== native configs

    static final Set<String> HYSTERIA_OWNED = new LinkedHashSet<>(Arrays.asList(
            "socks5", "http", "tcpForwarding", "udpForwarding", "tcpTProxy", "udpTProxy", "tcpRedirect", "tun"));

    static Result nativeHysteria(String source, Options o, List<String> warnings) throws IOException {
        Map<String, Object> root = new LinkedHashMap<>(Profile.loadYaml(source));
        String server = str(root, "server");
        if (server.isEmpty()) throw new IOException("Hysteria 配置缺少 server");
        for (String key : HYSTERIA_OWNED) if (root.remove(key) != null) warnings.add("已由河图接管 Hysteria 的 " + key + " 入站");
        boolean hasResolver = root.containsKey("resolver");
        // server is host:port, host:port-range or [v6]:port.
        String host = server, rest = "";
        if (server.startsWith("[")) { int close = server.indexOf(']'); host = server.substring(1, close); rest = server.substring(close + 1); }
        else { int colon = server.lastIndexOf(':'); if (colon > 0 && server.indexOf(':') == colon) { host = server.substring(0, colon); rest = server.substring(colon); } }
        String address = resolveServer(host, o, warnings);
        if (!address.equals(host)) {
            root.put("server", (address.contains(":") ? "[" + address + "]" : address) + rest);
            Map<String, Object> tls = asMap(root.get("tls"));
            LinkedHashMap<String, Object> t = tls == null ? new LinkedHashMap<>() : new LinkedHashMap<>(tls);
            if (str(t, "sni").isEmpty()) t.put("sni", host);
            root.put("tls", t);
        }
        org.yaml.snakeyaml.DumperOptions options = new org.yaml.snakeyaml.DumperOptions();
        options.setDefaultFlowStyle(org.yaml.snakeyaml.DumperOptions.FlowStyle.BLOCK);
        options.setIndent(2);
        options.setWidth(4096);
        StringBuilder y = new StringBuilder();
        y.append("# Hetu: native Hysteria 2 client config; ingress replaced by Hetu\n");
        y.append(new Yaml(options).dump(root));
        StringBuilder tail = new StringBuilder();
        Hysteria.appendHetu(tail, o, DEFAULT_DIRECT_DNS + ":53", DEFAULT_REMOTE_DNS + ":53");
        String tailText = tail.toString();
        if (hasResolver) tailText = tailText.replaceFirst("resolver:\\n  type: udp\\n  udp:\\n    addr: [^\\n]+\\n    timeout: 4s\\n", "");
        y.append(tailText);
        warnings.add("Hysteria 是单服务器客户端：规则分流、策略组、面板与测速不可用");
        return new Result(y.toString(), true, warnings, 1, "", "", false);
    }

    /** Host → literal IP through {@link Options#hostResolver}; unchanged when absent or already an IP. */
    static String resolveServer(String host, Options o, List<String> warnings) {
        if (host == null || host.isEmpty() || isIp(host) || o.hostResolver == null) return host;
        try {
            String ip = o.hostResolver.resolve(host);
            if (ip != null && isIp(ip)) return ip;
        } catch (IOException failed) {
            warnings.add("无法预先解析 " + host + "：" + failed.getMessage());
        }
        return host;
    }

    static Result nativeSingBox(String source, Options o, List<String> warnings) throws IOException {
        Map<String, Object> root = CoreJson.parseObject(source);
        if (root.containsKey("inbounds")) warnings.add("已由河图接管 sing-box 入站（TPROXY/DNS/本地探针）");
        root.put("inbounds", SingBox.inbounds(o));
        List<Object> outbounds = asList(root.get("outbounds"));
        if (outbounds.isEmpty()) throw new IOException("sing-box 配置缺少 outbounds");
        stripKey(outbounds, "routing_mark");
        Map<String, Object> route = asMap(root.get("route"));
        if (route == null) { route = new LinkedHashMap<>(); root.put("route", route); }
        if (route.remove("default_mark") != null) warnings.add("已移除 route.default_mark（Android 上会覆盖 netd 网络标记）");
        List<Object> rules = new ArrayList<>();
        rules.add(map("inbound", Arrays.asList(TAG_TPROXY, TAG_REDIRECT, TAG_DNS_IN), "action", "sniff"));
        if (o.dnsPort > 0) rules.add(map("inbound", Collections.singletonList(TAG_DNS_IN), "action", "hijack-dns"));
        rules.add(map("protocol", "dns", "action", "hijack-dns"));
        List<Object> ruleSets = new ArrayList<>(asList(route.get("rule_set")));
        Set<String> rsTags = new HashSet<>();
        for (Object rs : ruleSets) rsTags.add(str(asMap(rs), "tag"));
        boolean adblock = SingBox.adblock(o, rules, ruleSets, rsTags);
        rules.addAll(asList(route.get("rules")));
        route.put("rules", rules);
        if (!ruleSets.isEmpty()) route.put("rule_set", ruleSets);
        if (!route.containsKey("auto_detect_interface")) route.put("auto_detect_interface", true);
        if (!root.containsKey("dns")) {
            root.put("dns", map("servers", Arrays.asList(SingBox.dnsServer(DEFAULT_DIRECT_DNS, "dns-direct", null), SingBox.dnsServer(DEFAULT_REMOTE_DNS, "dns-remote", null)), "final", "dns-remote"));
            if (!route.containsKey("default_domain_resolver")) route.put("default_domain_resolver", "dns-direct");
            warnings.add("配置没有 dns 段，已添加默认 DNS（Android 上没有可用的系统解析）");
        }
        Map<String, Object> experimental = asMap(root.get("experimental"));
        if (experimental == null) { experimental = new LinkedHashMap<>(); root.put("experimental", experimental); }
        experimental.put("clash_api", map("external_controller", "127.0.0.1:" + o.controllerPort, "secret", o.secret, "default_mode", "rule"));
        if (!experimental.containsKey("cache_file")) experimental.put("cache_file", map("enabled", true, "path", "cache.db", "store_fakeip", true));
        return new Result(CoreJson.write(root), true, warnings, outbounds.size(), "", "", adblock);
    }

    static Result nativeXray(String source, Options o, List<String> warnings, boolean v2ray) throws IOException {
        Map<String, Object> root = CoreJson.parseObject(source);
        String label = v2ray ? "V2Fly" : "Xray";
        if (root.containsKey("inbounds") || root.containsKey("inbound")) warnings.add("已由河图接管 " + label + " 入站（TPROXY/DNS/本地探针）");
        root.remove("inbound"); root.remove("inboundDetour");
        List<Object> outbounds = new ArrayList<>(asList(root.containsKey("outbounds") ? root.get("outbounds") : root.get("outbound")));
        root.remove("outbound");
        if (outbounds.isEmpty()) throw new IOException(label + " 配置缺少 outbounds");
        for (Object ob : outbounds) {
            Map<String, Object> stream = asMap(asMap(ob) == null ? null : asMap(ob).get("streamSettings"));
            Map<String, Object> sockopt = asMap(stream == null ? null : stream.get("sockopt"));
            if (sockopt != null && sockopt.remove("mark") != null) warnings.add("已移除出站 sockopt.mark（Android 上会覆盖 netd 网络标记）");
        }
        boolean hasDnsOut = false;
        for (Object ob : outbounds) if ("dns".equals(str(asMap(ob), "protocol")) && TAG_DNS_OUT.equals(str(asMap(ob), "tag"))) hasDnsOut = true;
        if (!hasDnsOut) outbounds.add(map("tag", TAG_DNS_OUT, "protocol", "dns"));
        boolean hasBlock = false;
        for (Object ob : outbounds) if ("hetu-adblock".equals(str(asMap(ob), "tag"))) hasBlock = true;
        boolean adblock = false;
        List<Object> head = new ArrayList<>();
        if (o.dnsPort > 0) head.add(map("type", "field", "inboundTag", Collections.singletonList(TAG_DNS_IN), "outboundTag", TAG_DNS_OUT));
        head.add(map("type", "field", "inboundTag", Arrays.asList(TAG_TPROXY, TAG_REDIRECT), "port", 53, "outboundTag", TAG_DNS_OUT));
        if (!o.adblock.isEmpty()) {
            List<String> blocked = subtractAllowed(o.adblock, o.adblockAllow);
            if (!blocked.isEmpty()) {
                List<String> domains = new ArrayList<>(); for (String d : blocked) domains.add("domain:" + d);
                head.add(map("type", "field", "domain", domains, "outboundTag", "hetu-adblock"));
                if (!hasBlock) outbounds.add(map("tag", "hetu-adblock", "protocol", "blackhole"));
                adblock = true;
            }
        }
        root.put("outbounds", outbounds);
        Map<String, Object> routing = asMap(root.get("routing"));
        if (routing == null) { routing = new LinkedHashMap<>(); root.put("routing", routing); }
        List<Object> rules = new ArrayList<>(head);
        rules.addAll(asList(routing.get("rules")));
        routing.put("rules", rules);
        if (!root.containsKey("dns")) {
            root.put("dns", map("servers", Arrays.asList(DEFAULT_REMOTE_DNS, DEFAULT_DIRECT_DNS), "queryStrategy", o.ipv6 ? "UseIP" : "UseIPv4"));
            warnings.add("配置没有 dns 段，已添加默认 DNS（Android 上没有可用的系统解析）");
        }
        Map<String, Object> fake = null;
        for (Object s : asList(asMap(root.get("dns")).get("servers"))) if ("fakedns".equals(String.valueOf(s))) fake = asMap(root.get("dns"));
        root.put("inbounds", XrayConv.inbounds(o, fake != null));
        return new Result(CoreJson.write(root), true, warnings, outbounds.size(), "", "", adblock);
    }

    static void stripKey(List<Object> list, String key) {
        for (Object o : list) { Map<String, Object> m = asMap(o); if (m != null) m.remove(key); }
    }
}
