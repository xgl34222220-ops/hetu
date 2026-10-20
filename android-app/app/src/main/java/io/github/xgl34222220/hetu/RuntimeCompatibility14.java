package io.github.xgl34222220.hetu;

import java.io.*;
import java.security.MessageDigest;
import java.util.*;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.error.Mark;
import org.yaml.snakeyaml.nodes.*;

/** Narrow migrations in the private startup copy; source YAML is never saved or reserialized. */
final class RuntimeCompatibility14 {
    private RuntimeCompatibility14() {}
    private static Node tree(String text) throws IOException {
        return RuntimeYaml15.compose(text);
    }
    private static Node field(Node node, String key) {
        if (!(node instanceof MappingNode)) return null;
        for (NodeTuple tuple : ((MappingNode) node).getValue())
            if (tuple.getKeyNode() instanceof ScalarNode && key.equals(((ScalarNode) tuple.getKeyNode()).getValue()))
                return tuple.getValueNode();
        return null;
    }
    private static String scalar(Node node) { return node instanceof ScalarNode ? ((ScalarNode) node).getValue() : ""; }
    private static Node inherited(Node node, String key) {
        return RuntimeYaml15.inherited(node, key);
    }
    static boolean ruleFakeIpFilter(String source) throws IOException {
        return "rule".equalsIgnoreCase(scalar(field(field(tree(source), "dns"), "fake-ip-filter-mode")));
    }

    static String migrateFingerprint(String source) throws IOException {
        if (!source.contains("global-client-fingerprint")) return source;
        Node root = tree(source);
        String fingerprint = scalar(field(root, "global-client-fingerprint")).trim();
        if (fingerprint.isEmpty()) return source;
        if (!fingerprint.matches("[A-Za-z0-9_-]{1,32}"))
            throw new IOException("旧全局 TLS 指纹格式无效，请在具体节点设置 client-fingerprint");
        TreeMap<Integer,String> edits = new TreeMap<>(Collections.reverseOrder());
        Set<Node> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        migrateList(source, field(root, "proxies"), fingerprint, edits, visited);
        Node providers = field(root, "proxy-providers");
        if (providers instanceof MappingNode) for (NodeTuple provider : ((MappingNode)providers).getValue())
            migrateList(source, field(provider.getValueNode(), "payload"), fingerprint, edits, visited);
        // Remote provider overrides overwrite explicit per-node values, so do NOT inject one.
        StringBuilder result = new StringBuilder(source);
        for (Map.Entry<Integer,String> edit : edits.entrySet()) result.insert(edit.getKey(), edit.getValue());
        return result.toString();
    }
    /** Proxy types the removed global-client-fingerprint applied to. */
    private static final List<String> TLS_TYPES = Arrays.asList("vmess", "vless", "trojan", "anytls");

    /**
     * 7.0.0: migrateFingerprint can only write the removed global value onto nodes in this file. Each remote
     * (http/file) provider gets an override-expr item instead, which the core applies to every node it loads, on
     * every refresh: TLS_TYPES nodes whose client-fingerprint is missing or empty get the global value, as the
     * removed option did, and explicit values stay. A provider whose override names client-fingerprint itself, or
     * that cannot be extended in place, is left as written.
     */
    static String migrateProviderFingerprint(String source) throws IOException {
        if (!source.contains("global-client-fingerprint")) return source;
        Node root = tree(source);
        String fingerprint = scalar(field(root, "global-client-fingerprint")).trim();
        if (fingerprint.isEmpty()) return source;
        if (!fingerprint.matches("[A-Za-z0-9_-]{1,32}"))
            throw new IOException("旧全局 TLS 指纹格式无效，请在具体节点设置 client-fingerprint");
        Node providers = field(root, "proxy-providers");
        if (!(providers instanceof MappingNode)) return source;
        String item = providerFingerprintExpr(fingerprint);
        TreeMap<Integer,String> edits = new TreeMap<>(Collections.reverseOrder());
        Set<Node> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        for (NodeTuple provider : ((MappingNode)providers).getValue())
            migrateProvider(source, provider.getValueNode(), item, edits, visited);
        StringBuilder result = new StringBuilder(source);
        for (Map.Entry<Integer,String> edit : edits.entrySet()) result.insert(edit.getKey(), edit.getValue());
        return result.toString();
    }
    /** The override-expr item, YAML single-quoted, that gives TLS_TYPES nodes without one [fingerprint]. */
    static String providerFingerprintExpr(String fingerprint) {
        StringBuilder types = new StringBuilder();
        for (String type : TLS_TYPES) types.append(types.length() == 0 ? "" : " or ").append(".type == \"").append(type).append('"');
        return "'(select(((.client-fingerprint // \"\") == \"\") and (" + types + ")) | .client-fingerprint) = \""
                + fingerprint + "\"'";
    }
    private static void migrateProvider(String source, Node provider, String item,
            Map<Integer,String> edits, Set<Node> visited) {
        if (!(provider instanceof MappingNode)) return;
        String type = scalar(inherited(provider, "type")).toLowerCase(Locale.ROOT);
        if (!type.equals("http") && !type.equals("file")) return;
        Node override = inherited(provider, "override");
        if (override == null) {
            addEntry(source, (MappingNode)provider, "override: {override-expr: [" + item + "]}", edits, visited);
            return;
        }
        if (!(override instanceof MappingNode) || inherited(override, "client-fingerprint") != null) return;
        Node list = inherited(override, "override-expr");
        if (list == null) addEntry(source, (MappingNode)override, "override-expr: [" + item + "]", edits, visited);
        else if (list instanceof SequenceNode && !mentions((SequenceNode)list, "client-fingerprint"))
            addItem(source, (SequenceNode)list, item, edits, visited);
    }
    private static boolean mentions(SequenceNode list, String text) {
        for (Node item : list.getValue()) if (scalar(item).contains(text)) return true;
        return false;
    }
    private static int offset(String source, Mark mark) { return source.offsetByCodePoints(0, mark.getIndex()); }
    private static String spaces(int count) { return String.join("", Collections.nCopies(count, " ")); }
    /** ", " before a new entry at [close] (a flow collection's closing bracket), " " after a trailing comma, "" when empty. */
    private static String separator(String source, Node last, int close) {
        if (last == null) return "";
        int lastEnd = offset(source, last.getEndMark());
        // Only the suffix after the final value is inspected (see migrateList).
        String tail = lastEnd <= close ? source.substring(lastEnd, close).replaceAll("(?m)#.*$", "").trim() : "";
        return tail.equals(",") ? " " : ", ";
    }
    /** [entry] into a flow mapping before its closing brace, or into a block mapping as a line before its first key. */
    private static void addEntry(String source, MappingNode mapping, String entry,
            Map<Integer,String> edits, Set<Node> visited) {
        if (!visited.add(mapping)) return;
        List<NodeTuple> tuples = mapping.getValue();
        if (mapping.getFlowStyle() == DumperOptions.FlowStyle.FLOW) {
            int close = offset(source, mapping.getEndMark()) - 1;
            if (close < 0 || source.charAt(close) != '}') return;
            Node last = tuples.isEmpty() ? null : tuples.get(tuples.size() - 1).getValueNode();
            edits.merge(close, separator(source, last, close) + entry, String::concat);
        } else if (!tuples.isEmpty()) {
            Mark key = tuples.get(0).getKeyNode().getStartMark();
            edits.merge(offset(source, key), entry + "\n" + spaces(key.getColumn()), String::concat);
        }
    }
    /** [item] at the end of a flow sequence, or as a "- " line after the last one-line item of a block sequence. */
    private static void addItem(String source, SequenceNode list, String item,
            Map<Integer,String> edits, Set<Node> visited) {
        if (!visited.add(list)) return;
        List<Node> items = list.getValue();
        if (list.getFlowStyle() == DumperOptions.FlowStyle.FLOW) {
            int close = offset(source, list.getEndMark()) - 1;
            if (close < 0 || source.charAt(close) != ']') return;
            edits.merge(close, separator(source, items.isEmpty() ? null : items.get(items.size() - 1), close) + item,
                    String::concat);
            return;
        }
        if (items.isEmpty()) return;
        Node last = items.get(items.size() - 1);
        int dash = offset(source, list.getStartMark());
        // A multi-line last item would leave no line of its own to append after.
        if (!(last instanceof ScalarNode) || last.getStartMark().getLine() != last.getEndMark().getLine()
                || dash >= source.length() || source.charAt(dash) != '-') return;
        String line = spaces(list.getStartMark().getColumn()) + "- " + item + "\n";
        int newline = source.indexOf('\n', offset(source, last.getEndMark()));
        if (newline < 0) edits.merge(source.length(), "\n" + line, String::concat);
        else edits.merge(newline + 1, line, String::concat);
    }

    private static void migrateList(String source, Node sequence, String fingerprint,
            Map<Integer,String> edits, Set<Node> visited) throws IOException {
        if (!(sequence instanceof SequenceNode)) return;
        for (Node node : ((SequenceNode)sequence).getValue()) {
            if (!(node instanceof MappingNode) || !visited.add(node)) continue;
            String type = scalar(inherited(node, "type")).toLowerCase(Locale.ROOT);
            if (!TLS_TYPES.contains(type)
                    || inherited(node, "client-fingerprint") != null) continue;
            MappingNode mapping = (MappingNode)node;
            String entry = "client-fingerprint: '" + fingerprint + "'";
            if (mapping.getFlowStyle() == DumperOptions.FlowStyle.FLOW) {
                int end = source.offsetByCodePoints(0, mapping.getEndMark().getIndex());
                if (end < 1 || source.charAt(end - 1) != '}') throw new IOException("无法定位节点指纹迁移位置");
                Node lastValue = mapping.getValue().get(mapping.getValue().size()-1).getValueNode();
                int lastEnd = source.offsetByCodePoints(0, lastValue.getEndMark().getIndex());
                // Only the suffix after the final value is inspected: commas inside
                // quoted fields and comments cannot be mistaken for separators.
                String tail = lastEnd <= end-1 ? source.substring(lastEnd,end-1).replaceAll("(?m)#.*$","").trim() : "";
                edits.put(end - 1, (tail.equals(",") ? " " : ", ") + entry);
            } else {
                NodeTuple anchor = null;
                for (NodeTuple tuple : mapping.getValue()) {
                    Node value = tuple.getValueNode();
                    if (tuple.getKeyNode() instanceof ScalarNode && value instanceof ScalarNode
                            && value.getStartMark().getLine() == value.getEndMark().getLine()) { anchor = tuple; break; }
                }
                if (anchor == null) throw new IOException("旧全局指纹节点没有可安全迁移的字段，请给节点添加 client-fingerprint");
                int end = source.offsetByCodePoints(0, anchor.getValueNode().getEndMark().getIndex());
                int newline = source.indexOf('\n', end);
                int indent = anchor.getKeyNode().getStartMark().getColumn();
                String line = String.join("", Collections.nCopies(indent, " ")) + entry + "\n";
                if (newline < 0) edits.put(source.length(), "\n" + line);
                else edits.put(newline + 1, line);
            }
        }
    }

    static String sha256(File file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = new BufferedInputStream(new FileInputStream(file))) {
                byte[] buffer = new byte[32768]; int count;
                while ((count = in.read(buffer)) != -1) digest.update(buffer, 0, count);
            }
            StringBuilder result = new StringBuilder();
            for (byte value : digest.digest()) result.append(String.format(Locale.ROOT, "%02x", value & 255));
            return result.toString();
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IOException(impossible); }
    }
    static boolean cachedPairValid(Properties meta, File block, File allow) {
        try {
            String a = meta.getProperty("blockSha256", ""), b = meta.getProperty("allowSha256", "");
            return a.matches("[0-9a-f]{64}") && b.matches("[0-9a-f]{64}")
                    && a.equals(sha256(block)) && b.equals(sha256(allow));
        } catch (IOException missing) { return false; }
    }
    static String filterReloadMessage(String mode, int count) {
        if (count <= 0) return "规则文件已更新，但有效广告规则为空；请更新或启用规则源";
        if (!AdblockRuleInspection.isRuleMode(mode))
            return "已更新 " + count + " 条规则，但核心处于 " + mode + " 模式，广告规则不参与分流；切回规则模式后生效";
        return "已热更新 " + count + " 条广告规则（核心已加载）";
    }
    static String safeConfigSummary(String source) {
        try {
            Node root = tree(source); StringBuilder result = new StringBuilder();
            for (String key : Arrays.asList("mode", "ipv6", "disable-keep-alive", "keep-alive-idle", "keep-alive-interval", "global-client-fingerprint")) {
                String value = scalar(field(root,key));
                if (!value.isEmpty()) result.append(key).append('=').append(value.matches("[A-Za-z0-9_.-]{1,40}") ? value : "<非标准标量>").append('\n');
            }
            Node dns = field(root,"dns");
            for (String key : Arrays.asList("enable", "ipv6", "enhanced-mode", "fake-ip-filter-mode", "respect-rules", "prefer-h3")) {
                String value = scalar(field(dns,key));
                if (!value.isEmpty()) result.append("dns.").append(key).append('=').append(value.matches("[A-Za-z0-9_.-]{1,40}") ? value : "<非标准标量>").append('\n');
            }
            Node providers = field(root,"proxy-providers");
            if (field(root,"global-client-fingerprint") != null && providers instanceof MappingNode)
                result.append("注意：订阅（远程提供商）里没有自己 client-fingerprint 的 vmess/vless/trojan/anytls 节点，由启动副本的 override-expr 补上这个全局指纹；节点已有的值不变。\n");
            if ("true".equalsIgnoreCase(scalar(field(root,"disable-keep-alive"))))
                result.append("注意：源配置明确禁用了 TCP 保活，河图未擅自改写；长连接问题需结合计时器排查。\n");
            return result.toString();
        } catch (IOException error) { return error.getMessage() + "\n未输出配置或凭据。"; }
    }
}
