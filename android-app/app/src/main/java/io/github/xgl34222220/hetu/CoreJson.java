package io.github.xgl34222220.hetu;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.*;

/**
 * Small deterministic JSON reader/writer for the non-Mihomo core configs (sing-box, Xray, V2Fly).
 * Pure JVM (no org.json), so conversions are unit tested byte for byte against golden files.
 * The reader accepts the comments Xray/V2Ray/sing-box accept in their JSON (//, /* *&#47; and #).
 */
final class CoreJson {
    private CoreJson() {}

    static final int MAX_DEPTH = 64;

    // ---------------------------------------------------------------- writer

    static String write(Object value) {
        StringBuilder out = new StringBuilder(4096);
        write(out, value, 0);
        out.append('\n');
        return out.toString();
    }

    private static void indent(StringBuilder out, int level) {
        for (int i = 0; i < level; i++) out.append("  ");
    }

    @SuppressWarnings("unchecked")
    private static void write(StringBuilder out, Object value, int level) {
        if (value == null) { out.append("null"); return; }
        if (value instanceof String) { string(out, (String) value); return; }
        if (value instanceof Boolean) { out.append(((Boolean) value) ? "true" : "false"); return; }
        if (value instanceof Integer || value instanceof Long || value instanceof Short || value instanceof Byte) {
            out.append(((Number) value).longValue()); return;
        }
        if (value instanceof Number) {
            double d = ((Number) value).doubleValue();
            if (d == Math.rint(d) && !Double.isInfinite(d) && Math.abs(d) < 1e15) out.append((long) d);
            else out.append(new BigDecimal(value.toString()).stripTrailingZeros().toPlainString());
            return;
        }
        if (value instanceof Map) {
            Map<String, Object> map = (Map<String, Object>) value;
            if (map.isEmpty()) { out.append("{}"); return; }
            out.append("{\n");
            int i = 0;
            for (Map.Entry<String, Object> e : map.entrySet()) {
                indent(out, level + 1);
                string(out, String.valueOf(e.getKey()));
                out.append(": ");
                write(out, e.getValue(), level + 1);
                if (++i < map.size()) out.append(',');
                out.append('\n');
            }
            indent(out, level);
            out.append('}');
            return;
        }
        if (value instanceof Collection) {
            Collection<Object> list = (Collection<Object>) value;
            if (list.isEmpty()) { out.append("[]"); return; }
            boolean scalars = true;
            for (Object o : list) if (o instanceof Map || o instanceof Collection) { scalars = false; break; }
            if (scalars && list.size() <= 8) {
                out.append('[');
                int i = 0;
                for (Object o : list) { if (i++ > 0) out.append(", "); write(out, o, level + 1); }
                out.append(']');
                return;
            }
            out.append("[\n");
            int i = 0;
            for (Object o : list) {
                indent(out, level + 1);
                write(out, o, level + 1);
                if (++i < list.size()) out.append(',');
                out.append('\n');
            }
            indent(out, level);
            out.append(']');
            return;
        }
        string(out, String.valueOf(value));
    }

    static void string(StringBuilder out, String s) {
        out.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': out.append("\\\""); break;
                case '\\': out.append("\\\\"); break;
                case '\n': out.append("\\n"); break;
                case '\r': out.append("\\r"); break;
                case '\t': out.append("\\t"); break;
                case '\b': out.append("\\b"); break;
                case '\f': out.append("\\f"); break;
                default:
                    if (c < 0x20 || c == 0x2028 || c == 0x2029) out.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                    else out.append(c);
            }
        }
        out.append('"');
    }

    // ---------------------------------------------------------------- reader

    static Object parse(String text) throws IOException {
        if (text == null) throw new IOException("JSON 为空");
        Reader r = new Reader(text);
        r.ws();
        Object v = r.value(0);
        r.ws();
        if (!r.end()) throw r.error("JSON 末尾有多余内容");
        return v;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> parseObject(String text) throws IOException {
        Object v = parse(text);
        if (!(v instanceof Map)) throw new IOException("JSON 根节点应为对象");
        return (Map<String, Object>) v;
    }

    private static final class Reader {
        final String s; int p;
        Reader(String s) { this.s = s; }
        boolean end() { return p >= s.length(); }
        IOException error(String m) {
            int line = 1;
            for (int i = 0; i < Math.min(p, s.length()); i++) if (s.charAt(i) == '\n') line++;
            return new IOException(m + "（第 " + line + " 行）");
        }
        void ws() throws IOException {
            while (p < s.length()) {
                char c = s.charAt(p);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\uFEFF') { p++; continue; }
                if (c == '#') { while (p < s.length() && s.charAt(p) != '\n') p++; continue; }
                if (c == '/' && p + 1 < s.length()) {
                    char n = s.charAt(p + 1);
                    if (n == '/') { while (p < s.length() && s.charAt(p) != '\n') p++; continue; }
                    if (n == '*') {
                        int close = s.indexOf("*/", p + 2);
                        if (close < 0) throw error("JSON 注释未闭合");
                        p = close + 2; continue;
                    }
                }
                break;
            }
        }
        Object value(int depth) throws IOException {
            if (depth > MAX_DEPTH) throw error("JSON 嵌套过深");
            if (end()) throw error("JSON 提前结束");
            char c = s.charAt(p);
            if (c == '{') return object(depth);
            if (c == '[') return array(depth);
            if (c == '"') return str();
            if (s.startsWith("true", p)) { p += 4; return Boolean.TRUE; }
            if (s.startsWith("false", p)) { p += 5; return Boolean.FALSE; }
            if (s.startsWith("null", p)) { p += 4; return null; }
            if (c == '-' || (c >= '0' && c <= '9')) return number();
            throw error("JSON 语法错误");
        }
        Map<String, Object> object(int depth) throws IOException {
            LinkedHashMap<String, Object> map = new LinkedHashMap<>();
            p++; ws();
            if (!end() && s.charAt(p) == '}') { p++; return map; }
            while (true) {
                ws();
                if (end() || s.charAt(p) != '"') throw error("JSON 对象键应为字符串");
                String key = str();
                ws();
                if (end() || s.charAt(p) != ':') throw error("JSON 缺少冒号");
                p++; ws();
                map.put(key, value(depth + 1));
                ws();
                if (end()) throw error("JSON 对象未闭合");
                char c = s.charAt(p);
                if (c == ',') { p++; ws(); if (!end() && s.charAt(p) == '}') { p++; return map; } continue; }
                if (c == '}') { p++; return map; }
                throw error("JSON 对象语法错误");
            }
        }
        List<Object> array(int depth) throws IOException {
            ArrayList<Object> list = new ArrayList<>();
            p++; ws();
            if (!end() && s.charAt(p) == ']') { p++; return list; }
            while (true) {
                ws();
                list.add(value(depth + 1));
                ws();
                if (end()) throw error("JSON 数组未闭合");
                char c = s.charAt(p);
                if (c == ',') { p++; ws(); if (!end() && s.charAt(p) == ']') { p++; return list; } continue; }
                if (c == ']') { p++; return list; }
                throw error("JSON 数组语法错误");
            }
        }
        String str() throws IOException {
            StringBuilder b = new StringBuilder();
            p++;
            while (true) {
                if (end()) throw error("JSON 字符串未闭合");
                char c = s.charAt(p++);
                if (c == '"') return b.toString();
                if (c != '\\') { b.append(c); continue; }
                if (end()) throw error("JSON 转义未完成");
                char e = s.charAt(p++);
                switch (e) {
                    case '"': b.append('"'); break;
                    case '\\': b.append('\\'); break;
                    case '/': b.append('/'); break;
                    case 'b': b.append('\b'); break;
                    case 'f': b.append('\f'); break;
                    case 'n': b.append('\n'); break;
                    case 'r': b.append('\r'); break;
                    case 't': b.append('\t'); break;
                    case 'u':
                        if (p + 4 > s.length()) throw error("JSON \\u 转义无效");
                        try { b.append((char) Integer.parseInt(s.substring(p, p + 4), 16)); }
                        catch (NumberFormatException x) { throw error("JSON \\u 转义无效"); }
                        p += 4; break;
                    default: throw error("JSON 转义无效");
                }
            }
        }
        Object number() throws IOException {
            int start = p;
            if (s.charAt(p) == '-') p++;
            while (p < s.length() && "0123456789.eE+-".indexOf(s.charAt(p)) >= 0) p++;
            String t = s.substring(start, p);
            try {
                if (t.indexOf('.') < 0 && t.indexOf('e') < 0 && t.indexOf('E') < 0) {
                    long l = Long.parseLong(t);
                    if (l >= Integer.MIN_VALUE && l <= Integer.MAX_VALUE) return (int) l;
                    return l;
                }
                return Double.parseDouble(t);
            } catch (NumberFormatException x) { throw error("JSON 数字无效"); }
        }
    }
}
