package io.github.xgl34222220.hetu;

import java.io.*;
import java.security.MessageDigest;
import java.util.*;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.nodes.*;

/** Narrow migrations in the private startup copy; source YAML is never saved or reserialized. */
final class RuntimeCompatibility14 {
    private RuntimeCompatibility14() {}
    private static Node tree(String text) throws IOException {
        try {
            LoaderOptions options = new LoaderOptions();
            options.setCodePointLimit(4 * 1024 * 1024);
            options.setNestingDepthLimit(50);
            options.setMaxAliasesForCollections(50);
            options.setAllowDuplicateKeys(false);
            return new Yaml(new SafeConstructor(options)).compose(new StringReader(text));
        } catch (Exception failure) {
            throw new IOException("启动配置结构解析失败，请检查 YAML；未修改源文件", failure);
        }
    }
    private static Node field(Node node, String key) {
        if (!(node instanceof MappingNode)) return null;
        for (NodeTuple tuple : ((MappingNode) node).getValue())
            if (tuple.getKeyNode() instanceof ScalarNode && key.equals(((ScalarNode) tuple.getKeyNode()).getValue()))
                return tuple.getValueNode();
        return null;
    }
    private static String scalar(Node node) { return node instanceof ScalarNode ? ((ScalarNode) node).getValue() : ""; }
    private static Node inherited(Node node, String key, Set<Node> visited) {
        if (node == null || !visited.add(node)) return null;
        Node own = field(node, key);
        if (own != null) return own;
        Node merge = field(node, "<<");
        if (merge instanceof SequenceNode) {
            for (Node part : ((SequenceNode) merge).getValue()) {
                Node result = inherited(part, key, visited);
                if (result != null) return result;
            }
            return null;
        }
        return inherited(merge, key, visited);
    }
    private static Node inherited(Node node, String key) {
        return inherited(node, key, Collections.newSetFromMap(new IdentityHashMap<>()));
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
    private static void migrateList(String source, Node sequence, String fingerprint,
            Map<Integer,String> edits, Set<Node> visited) throws IOException {
        if (!(sequence instanceof SequenceNode)) return;
        for (Node node : ((SequenceNode)sequence).getValue()) {
            if (!(node instanceof MappingNode) || !visited.add(node)) continue;
            String type = scalar(inherited(node, "type")).toLowerCase(Locale.ROOT);
            if (!Arrays.asList("vmess", "vless", "trojan", "anytls").contains(type)
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
                result.append("注意：远程提供商须自行提供节点级 TLS 指纹，不用全局 override 覆盖明确值。\n");
            if ("true".equalsIgnoreCase(scalar(field(root,"disable-keep-alive"))))
                result.append("注意：源配置明确禁用了 TCP 保活，河图未擅自改写；长连接问题需结合计时器排查。\n");
            return result.toString();
        } catch (IOException error) { return "配置结构不可解析；未输出配置或凭据。"; }
    }
}
