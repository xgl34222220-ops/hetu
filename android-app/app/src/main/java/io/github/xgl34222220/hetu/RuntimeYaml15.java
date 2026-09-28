package io.github.xgl34222220.hetu;

import java.io.IOException;
import java.io.StringReader;
import java.util.*;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.Mark;
import org.yaml.snakeyaml.error.MarkedYAMLException;
import org.yaml.snakeyaml.error.YAMLException;
import org.yaml.snakeyaml.nodes.*;

/**
 * A bounded, non-expanding YAML node graph for private runtime edits.
 * UI14 imposed the SafeConstructor object-loading default (50 collection aliases)
 * on compose(), rejecting ordinary reused provider/group templates.
 * Do NOT reuse these options with load(): object construction/merge expansion is
 * intentionally never performed here. The selected file is not rewritten and
 * the existing Mihomo preflight remains the final runtime validator.
 */
final class RuntimeYaml15 {
    static final int MAX_COLLECTION_ALIASES = 4096;
    static final int MAX_CODE_POINTS = 4 * 1024 * 1024;
    static final int MAX_NESTING_DEPTH = 50;
    private RuntimeYaml15() {}

    static Node compose(String source) throws IOException {
        if (source == null || source.trim().isEmpty())
            throw failure("YAML_EMPTY", "启动配置为空", null);
        try {
            LoaderOptions options = new LoaderOptions();
            options.setCodePointLimit(MAX_CODE_POINTS);
            options.setNestingDepthLimit(MAX_NESTING_DEPTH);
            options.setMaxAliasesForCollections(MAX_COLLECTION_ALIASES);
            options.setAllowDuplicateKeys(false);
            Node root = new Yaml(new SafeConstructor(options)).compose(new StringReader(source));
            if (!(root instanceof MappingNode))
                throw failure("YAML_ROOT", "启动配置根节点应为键值映射", root == null ? null : root.getStartMark());
            return root;
        } catch (YAMLException invalid) {
            // SnakeYAML messages/Marks may contain source lines and credentials.
            // Inspect them only to choose a constant category; never expose them,
            // attach them as a cause, or call Mark.toString() in a diagnostic.
            String message = String.valueOf(invalid.getMessage()).toLowerCase(Locale.ROOT);
            Mark mark = invalid instanceof MarkedYAMLException
                    ? ((MarkedYAMLException)invalid).getProblemMark() : null;
            if (message.contains("aliases for non-scalar nodes exceeds"))
                throw failure("YAML_ALIAS_LIMIT", "启动配置集合别名引用超过 " + MAX_COLLECTION_ALIASES + " 次解析限额（非语法错误）", mark);
            if (message.contains("nesting depth"))
                throw failure("YAML_DEPTH_LIMIT", "启动配置嵌套超过 " + MAX_NESTING_DEPTH + " 层解析限额", mark);
            if (message.contains("code points"))
                throw failure("YAML_SIZE_LIMIT", "启动配置超过 4 Mi 字符解析限额", mark);
            if (message.contains("single document"))
                throw failure("YAML_DOCUMENTS", "启动配置包含多个 YAML 文档", mark);
            throw failure("YAML_SYNTAX", "启动配置 YAML 结构或语法解析失败", mark);
        }
    }

    private static IOException failure(String category, String explanation, Mark mark) {
        String location = mark == null ? "" : "（第 " + (mark.getLine() + 1) + " 行，第 " + (mark.getColumn() + 1) + " 列）";
        return new IOException(explanation + location + "；未修改源文件 [" + category + "]");
    }

    private static Node field(Node node, String key) {
        if (!(node instanceof MappingNode)) return null;
        for (NodeTuple tuple : ((MappingNode)node).getValue())
            if (tuple.getKeyNode() instanceof ScalarNode && key.equals(((ScalarNode)tuple.getKeyNode()).getValue()))
                return tuple.getValueNode();
        return null;
    }

    /** YAML merge priority: explicit fields first, then earlier maps in << sequences.
     * Identity-based iteration prevents recursive aliases/DAGs from expanding or
     * overflowing the Java stack. Source anchors and all node identities remain intact.
     */
    static Node inherited(Node node, String key) {
        if (node == null) return null;
        Set<Node> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        Deque<Node> pending = new ArrayDeque<>();
        pending.push(node);
        while (!pending.isEmpty()) {
            Node current = pending.pop();
            if (!visited.add(current)) continue;
            Node own = field(current, key);
            if (own != null) return own;
            Node merge = field(current, "<<");
            if (merge instanceof SequenceNode) {
                List<Node> maps = ((SequenceNode)merge).getValue();
                for (int index = maps.size() - 1; index >= 0; index--) pending.push(maps.get(index));
            } else if (merge != null) {
                pending.push(merge);
            }
        }
        return null;
    }
}
