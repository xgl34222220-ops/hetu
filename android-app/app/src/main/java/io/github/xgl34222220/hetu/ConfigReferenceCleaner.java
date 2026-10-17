package io.github.xgl34222220.hetu;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;
import org.yaml.snakeyaml.representer.Representer;

/**
 * Removes dangling references before a core starts: a Mihomo proxy group that names a node, group or
 * proxy provider that is not defined, or a sing-box selector/urltest outbound that names a missing tag.
 * Such a reference makes the core refuse the whole file ("proxy not found"), typically after a
 * subscription update dropped a few nodes.
 *
 * <p>Only the private startup copy is changed; the user's file stays untouched. A group that ends up
 * with no member falls back to the built-in DIRECT (Mihomo) or a direct outbound (sing-box), so rules and
 * other groups that point at it stay valid. Every removal is reported as one readable notice.
 * Pure JVM: no Android API.
 */
final class ConfigReferenceCleaner {
    private ConfigReferenceCleaner() {}

    static final int MAX_NOTICES = 40;
    static final Set<String> MIHOMO_BUILTINS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "DIRECT", "REJECT", "REJECT-DROP", "PASS", "COMPATIBLE", "GLOBAL")));

    static final class Result {
        final String text;
        final List<String> notices;
        final boolean changed;
        Result(String text, List<String> notices, boolean changed) {
            this.text = text; this.notices = Collections.unmodifiableList(new ArrayList<>(notices)); this.changed = changed;
        }
    }

    // ------------------------------------------------------------------ Mihomo YAML

    static Result mihomo(String yaml) {
        List<String> notices = new ArrayList<>();
        if (yaml == null || !MihomoStartupConfig.hasTopLevelKey(yaml, "proxy-groups")) return new Result(yaml, notices, false);
        Map<String, Object> root;
        try {
            Object loaded = loader().load(yaml);
            if (!(loaded instanceof Map)) return new Result(yaml, notices, false);
            @SuppressWarnings("unchecked") Map<String, Object> m = (Map<String, Object>) loaded;
            root = m;
        } catch (YAMLException | ClassCastException invalid) {
            // The core check reports a malformed file itself; never guess at a repair.
            return new Result(yaml, notices, false);
        }
        List<Object> groups = list(root.get("proxy-groups"));
        if (groups == null || groups.isEmpty()) return new Result(yaml, notices, false);
        Set<String> names = new HashSet<>();
        List<Object> proxies = list(root.get("proxies"));
        if (proxies != null) for (Object p : proxies) { String n = name(p); if (n != null) names.add(n); }
        for (Object g : groups) { String n = name(g); if (n != null) names.add(n); }
        Set<String> providers = new HashSet<>();
        Object providerMap = root.get("proxy-providers");
        if (providerMap instanceof Map) for (Object k : ((Map<?, ?>) providerMap).keySet()) providers.add(String.valueOf(k));

        boolean changed = false;
        List<Object> cleaned = new ArrayList<>();
        for (Object g : groups) {
            if (!(g instanceof Map)) { cleaned.add(g); continue; }
            @SuppressWarnings("unchecked") Map<String, Object> group = new LinkedHashMap<>((Map<String, Object>) g);
            String groupName = name(group) == null ? "?" : name(group);
            boolean touched = false;
            List<Object> members = list(group.get("proxies"));
            if (members != null) {
                List<Object> kept = new ArrayList<>();
                for (Object member : members) {
                    String target = member == null ? "" : String.valueOf(member);
                    if (names.contains(target) || MIHOMO_BUILTINS.contains(target.toUpperCase(Locale.ROOT))) kept.add(member);
                    else { touched = true; note(notices, "代理组「" + groupName + "」引用的节点或组「" + target + "」未定义，启动前已从组内移除"); }
                }
                if (kept.size() != members.size()) group.put("proxies", kept);
            }
            List<Object> uses = list(group.get("use"));
            if (uses != null) {
                List<Object> kept = new ArrayList<>();
                for (Object use : uses) {
                    String target = use == null ? "" : String.valueOf(use);
                    if (providers.contains(target)) kept.add(use);
                    else { touched = true; note(notices, "代理组「" + groupName + "」引用的节点集合（proxy-provider）「" + target + "」未定义，启动前已移除"); }
                }
                if (kept.size() != uses.size()) { if (kept.isEmpty()) group.remove("use"); else group.put("use", kept); }
            }
            if (touched && emptyMihomoGroup(group)) {
                group.put("proxies", new ArrayList<>(Collections.singletonList("DIRECT")));
                note(notices, "代理组「" + groupName + "」清理后没有可用成员，已回退为 DIRECT（引用它的规则和组保持有效）");
            }
            changed |= touched;
            cleaned.add(touched ? group : g);
        }
        if (!changed) return new Result(yaml, notices, false);

        int[] block = topLevelBlock(yaml, "proxy-groups");
        if (block == null) {
            notices.clear();
            note(notices, "检测到失效的代理组引用，但 proxy-groups 段出现多次或格式特殊，未自动清理");
            return new Result(yaml, notices, false);
        }
        String original = yaml.substring(block[0], block[1]);
        if (anchorUsedOutside(original, yaml.substring(0, block[0]) + yaml.substring(block[1]))) {
            notices.clear();
            note(notices, "检测到失效的代理组引用，但 proxy-groups 段定义了被其他段引用的 YAML 锚点，未自动清理");
            return new Result(yaml, notices, false);
        }
        Map<String, Object> wrapper = new LinkedHashMap<>();
        wrapper.put("proxy-groups", cleaned);
        String dumped = dumper().dump(wrapper);
        if (!dumped.endsWith("\n")) dumped += "\n";
        String rewritten = yaml.substring(0, block[0]) + dumped + yaml.substring(block[1]);
        return new Result(rewritten, notices, true);
    }

    static boolean emptyMihomoGroup(Map<String, Object> group) {
        List<Object> members = list(group.get("proxies"));
        List<Object> uses = list(group.get("use"));
        if (members != null && !members.isEmpty()) return false;
        if (uses != null && !uses.isEmpty()) return false;
        for (String key : new String[]{"include-all", "include-all-proxies", "include-all-providers"})
            if (Boolean.TRUE.equals(group.get(key))) return false;
        return true;
    }

    // ------------------------------------------------------------------ sing-box JSON (parsed)

    /** Cleans selector/urltest members in place; returns the notices (empty when nothing changed). */
    static List<String> singBox(Map<String, Object> root) {
        List<String> notices = new ArrayList<>();
        if (root == null) return notices;
        List<Object> outbounds = list(root.get("outbounds"));
        if (outbounds == null) return notices;
        Set<String> tags = new HashSet<>();
        String direct = null;
        for (Object o : outbounds) {
            Map<String, Object> m = map(o);
            if (m == null || m.get("tag") == null) continue;
            String tag = String.valueOf(m.get("tag"));
            tags.add(tag);
            if (direct == null && "direct".equals(String.valueOf(m.get("type")))) direct = tag;
        }
        List<Object> endpoints = list(root.get("endpoints"));
        if (endpoints != null) for (Object o : endpoints) { Map<String, Object> m = map(o); if (m != null && m.get("tag") != null) tags.add(String.valueOf(m.get("tag"))); }
        boolean addDirect = false;
        String fallback = direct == null ? "hetu-direct" : direct;
        for (Object o : outbounds) {
            Map<String, Object> m = map(o);
            if (m == null) continue;
            String type = String.valueOf(m.get("type"));
            if (!"selector".equals(type) && !"urltest".equals(type)) continue;
            String tag = m.get("tag") == null ? "?" : String.valueOf(m.get("tag"));
            List<Object> members = list(m.get("outbounds"));
            boolean touched = false;
            if (members != null) {
                List<Object> kept = new ArrayList<>();
                for (Object member : members) {
                    String target = member == null ? "" : String.valueOf(member);
                    if (tags.contains(target)) kept.add(member);
                    else { touched = true; note(notices, "sing-box 出站「" + tag + "」引用的出站「" + target + "」未定义，启动前已移除"); }
                }
                if (touched) m.put("outbounds", kept);
                members = kept;
            }
            Object def = m.get("default");
            if (def != null && (members == null || !members.contains(def))) {
                m.remove("default");
                touched = true;
                note(notices, "sing-box 出站「" + tag + "」的默认出站「" + def + "」已失效，启动前已移除");
            }
            boolean providerBacked = m.containsKey("providers") || m.containsKey("use_all_providers") || m.containsKey("include");
            if (touched && (members == null || members.isEmpty()) && !providerBacked) {
                m.put("outbounds", new ArrayList<>(Collections.singletonList(fallback)));
                if (direct == null) addDirect = true;
                note(notices, "sing-box 出站「" + tag + "」清理后没有可用成员，已回退为直连出站「" + fallback + "」");
            }
        }
        if (addDirect && !tags.contains(fallback)) {
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("type", "direct"); d.put("tag", fallback);
            outbounds.add(d);
            root.put("outbounds", outbounds);
        }
        return notices;
    }

    // ------------------------------------------------------------------ helpers

    private static void note(List<String> notices, String text) {
        if (notices.size() < MAX_NOTICES) notices.add(text);
        else if (notices.size() == MAX_NOTICES) notices.add("……其余失效引用同样已移除");
    }

    static Yaml loader() {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(true);
        options.setMaxAliasesForCollections(10_000);
        options.setCodePointLimit(16 * 1024 * 1024);
        return new Yaml(new SafeConstructor(options));
    }

    private static Yaml dumper() {
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setIndent(2);
        options.setIndicatorIndent(0);
        options.setAllowUnicode(true);
        options.setWidth(Integer.MAX_VALUE);
        options.setSplitLines(false);
        options.setPrettyFlow(false);
        return new Yaml(new SafeConstructor(new LoaderOptions()), new Representer(options), options);
    }

    @SuppressWarnings("unchecked")
    static List<Object> list(Object o) { return o instanceof List ? (List<Object>) o : null; }

    @SuppressWarnings("unchecked")
    static Map<String, Object> map(Object o) { return o instanceof Map ? (Map<String, Object>) o : null; }

    private static String name(Object o) {
        Map<String, Object> m = map(o);
        return m == null || m.get("name") == null ? null : String.valueOf(m.get("name"));
    }

    /**
     * [start, end) of the single top-level block KEY (key line through the line before the next top-level
     * key). Column-0 sequence items, comments and blank lines stay inside. Null when the key occurs more
     * than once or not at all.
     */
    static int[] topLevelBlock(String text, String key) {
        Pattern head = Pattern.compile("^" + Pattern.quote(key) + "\\s*:");
        int start = -1, end = -1, pos = 0, found = 0;
        while (pos <= text.length()) {
            int nl = text.indexOf('\n', pos);
            int lineEnd = nl < 0 ? text.length() : nl + 1;
            String line = text.substring(pos, nl < 0 ? text.length() : nl);
            boolean topLevel = !line.isEmpty() && line.charAt(0) != ' ' && line.charAt(0) != '\t' && line.charAt(0) != '#'
                    && line.charAt(0) != '-' && line.charAt(0) != '\r' && line.charAt(0) != ']' && line.charAt(0) != '}';
            if (topLevel) {
                if (head.matcher(line).find()) {
                    found++;
                    if (start >= 0 && end < 0) end = pos;
                    start = pos; end = -1;
                } else if (start >= 0 && end < 0) {
                    end = pos;
                }
            }
            if (nl < 0) break;
            pos = lineEnd;
        }
        if (found != 1 || start < 0) return null;
        if (end < 0) end = text.length();
        // Leave trailing blank/comment lines to the following block (they are not group content).
        while (end > start) {
            int prevEnd = end;
            if (prevEnd > start && text.charAt(prevEnd - 1) == '\n') prevEnd--;
            int lineStart = text.lastIndexOf('\n', prevEnd - 1) + 1;
            if (lineStart <= start) break;
            String line = text.substring(lineStart, prevEnd).trim();
            if (!line.isEmpty() && !line.startsWith("#")) break;
            end = lineStart;
        }
        return new int[]{start, end};
    }

    private static final Pattern ANCHOR = Pattern.compile("(?:^|[\\s\\[{,:-])&([^\\s\\[\\]{},]+)");

    static boolean anchorUsedOutside(String block, String rest) {
        Matcher m = ANCHOR.matcher(block);
        while (m.find()) {
            if (Pattern.compile("\\*" + Pattern.quote(m.group(1)) + "(?![^\\s\\[\\]{},])").matcher(rest).find()) return true;
        }
        return false;
    }
}
