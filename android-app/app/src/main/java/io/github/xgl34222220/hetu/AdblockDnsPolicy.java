package io.github.xgl34222220.hetu;

import java.util.*;

/**
 * DNS-layer ad blocking for the private Mihomo runtime copy (pure Java, no Android API).
 *
 * The connection-layer AND rule only matches flows that still carry a hostname. Flows that reach
 * the kernel as bare (often Chinese) IPs, e.g. under CNIP kernel bypass or redir-host DNS, never
 * reach it. Answering the ad hostname itself with an empty NOERROR keeps those apps from getting
 * an address at all.
 *
 * Verified against Mihomo ab405bad: dns.nameserver-policy is an ordered map evaluated first-match
 * (config.parseNameServerPolicy keeps insertion order; Resolver.matchPolicy returns the first hit,
 * for both A/AAAA and other queries); a "rule-set:" key accepts a behavior=domain provider; an
 * rcode:// nameserver answers immediately and is never cached. In fake-ip mode the fake-ip
 * middleware runs before the resolver, so the set is also exempted from fake IPs through
 * fake-ip-filter (blacklist: "rule-set:" entry; rule: "RULE-SET,…,real-ip"; whitelist: nothing).
 */
final class AdblockDnsPolicy {
    private AdblockDnsPolicy() {}

    static final String PROVIDER_NAME = "hetu-adblock-dns";
    static final String PROVIDER_PATH = "./ruleset/hetu-adblock-dns.txt";
    static final String FILE_NAME = "hetu-adblock-dns.txt";
    static final String BLOCK_ANSWER = "rcode://success";
    static final String POLICY_LINE = "'rule-set:" + PROVIDER_NAME + "': '" + BLOCK_ANSWER + "'";
    /** Mihomo's own fake-ip-filter defaults, kept when Hetu has to create the list. */
    static final List<String> MIHOMO_DEFAULT_FAKE_IP_FILTER =
            Collections.unmodifiableList(Arrays.asList("dns.msftnsci.com", "www.msftnsci.com", "www.msftconnecttest.com"));

    /**
     * Domains that may be answered empty at DNS. A blocked suffix is kept out when an allow entry
     * equals it, lies under it (blocking the parent at DNS would also kill the allowed child) or
     * covers it (the connection rule would let it through). The connection-layer AND rule still
     * judges those domains with exact allow semantics.
     */
    static List<String> dnsBlockDomains(Collection<String> block, Collection<String> allow) {
        HashSet<String> allowSet = new HashSet<>();
        HashSet<String> allowParents = new HashSet<>();
        if (allow != null) for (String raw : allow) {
            String a = normalizeDomain(raw);
            if (a.isEmpty()) continue;
            allowSet.add(a);
            for (int dot = a.indexOf('.'); dot >= 0 && dot < a.length() - 1; dot = a.indexOf('.', dot + 1))
                allowParents.add(a.substring(dot + 1));
        }
        // Network-validation probe hosts are never answered empty (and neither are their parents).
        for (String probe : CaptivePortalBypass.DEFAULT_HOSTS) {
            allowSet.add(probe);
            for (int dot = probe.indexOf('.'); dot >= 0 && dot < probe.length() - 1; dot = probe.indexOf('.', dot + 1))
                allowParents.add(probe.substring(dot + 1));
        }
        TreeSet<String> out = new TreeSet<>();
        if (block != null) for (String raw : block) {
            String d = normalizeDomain(raw);
            if (d.isEmpty() || allowParents.contains(d) || coveredBy(d, allowSet)) continue;
            out.add(d);
        }
        return new ArrayList<>(out);
    }

    static boolean coveredBy(String host, Set<String> set) {
        String current = host;
        while (true) {
            if (set.contains(current)) return true;
            int dot = current.indexOf('.');
            if (dot < 0 || dot == current.length() - 1) return false;
            current = current.substring(dot + 1);
        }
    }

    private static String normalizeDomain(String raw) {
        if (raw == null) return "";
        String d = raw.trim().toLowerCase(Locale.ROOT);
        if (d.startsWith("+.")) d = d.substring(2);
        while (d.endsWith(".")) d = d.substring(0, d.length() - 1);
        return d;
    }

    /** Outcome of [inject]: [reason] says why the DNS layer was left out. */
    static final class Result {
        final String yaml;
        final boolean injected;
        /** "", "fake-ip-filter", "fake-ip-rule", "fake-ip-whitelist" or a skipped-form note. */
        final String fakeIp;
        final String reason;
        Result(String yaml, boolean injected, String fakeIp, String reason) {
            this.yaml = yaml; this.injected = injected; this.fakeIp = fakeIp == null ? "" : fakeIp; this.reason = reason == null ? "" : reason;
        }
    }

    /**
     * Puts the block answer first in dns.nameserver-policy and, in fake-ip mode, exempts the set
     * from fake IPs. No dns block (the core's DNS is not used) or an inline dns / nameserver-policy
     * leaves the YAML untouched: connection-layer blocking still applies there.
     */
    static Result inject(String source) {
        String yaml = source == null ? "" : source.replace("\r\n", "\n").replace('\r', '\n');
        List<String> lines = new ArrayList<>(Arrays.asList(yaml.split("\n", -1)));
        int start = -1;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (indent(line) != 0 || !"dns".equals(key(line.trim()))) continue;
            if (!line.matches("^dns\\s*:\\s*(?:#.*)?$")) return new Result(yaml, false, "", "inline-dns");
            start = i; break;
        }
        if (start < 0) return new Result(yaml, false, "", "no-dns");
        int end = blockEnd(lines, start);
        int child = childIndent(lines, start, end);
        for (int i = start + 1; i < end; i++) if (lines.get(i).contains(PROVIDER_NAME)) return new Result(yaml, false, "", "already-present");

        // 1) nameserver-policy: our entry first.
        int policy = findChild(lines, start, end, child, "nameserver-policy");
        if (policy >= 0) {
            String rest = valueOf(lines.get(policy));
            if (rest.isEmpty()) {
                int g = firstNestedIndent(lines, policy, end, child, child + 2);
                lines.add(policy + 1, spaces(g) + POLICY_LINE);
            } else if (rest.equals("{}")) {
                lines.set(policy, spaces(child) + "nameserver-policy:");
                lines.add(policy + 1, spaces(child + 2) + POLICY_LINE);
            } else {
                return new Result(yaml, false, "", "inline-nameserver-policy");
            }
        } else {
            int at = lastContentLine(lines, start, end) + 1;
            lines.add(at, spaces(child) + "nameserver-policy:");
            lines.add(at + 1, spaces(child + 2) + POLICY_LINE);
        }
        end = blockEnd(lines, start);

        // 2) fake-ip: the fake-ip middleware answers before the resolver policy.
        String fakeIp = "";
        int enhanced = findChild(lines, start, end, child, "enhanced-mode");
        if (enhanced >= 0 && "fake-ip".equalsIgnoreCase(unquote(valueOf(lines.get(enhanced))))) {
            int modeLine = findChild(lines, start, end, child, "fake-ip-filter-mode");
            String mode = modeLine < 0 ? "blacklist" : unquote(valueOf(lines.get(modeLine))).toLowerCase(Locale.ROOT);
            if ("whitelist".equals(mode)) {
                fakeIp = "fake-ip-whitelist";
            } else {
                boolean rule = "rule".equals(mode);
                String item = rule ? "RULE-SET," + PROVIDER_NAME + ",real-ip" : "'rule-set:" + PROVIDER_NAME + "'";
                int filter = findChild(lines, start, end, child, "fake-ip-filter");
                if (filter >= 0) {
                    String rest = valueOf(lines.get(filter));
                    if (rest.isEmpty()) {
                        int g = firstNestedIndent(lines, filter, end, child, child + 2);
                        lines.add(filter + 1, spaces(g) + "- " + item);
                        fakeIp = rule ? "fake-ip-rule" : "fake-ip-filter";
                    } else if (rest.equals("[]")) {
                        lines.set(filter, spaces(child) + "fake-ip-filter:");
                        lines.add(filter + 1, spaces(child + 2) + "- " + item);
                        fakeIp = rule ? "fake-ip-rule" : "fake-ip-filter";
                    } else {
                        // Inline list: left untouched; ad hosts get fake IPs and the connection rule
                        // still rejects them with their hostname.
                        fakeIp = "fake-ip-inline-skipped";
                    }
                } else {
                    int at = lastContentLine(lines, start, end) + 1;
                    lines.add(at++, spaces(child) + "fake-ip-filter:");
                    lines.add(at++, spaces(child + 2) + "- " + item);
                    if (!rule) for (String d : MIHOMO_DEFAULT_FAKE_IP_FILTER) lines.add(at++, spaces(child + 2) + "- " + d);
                    fakeIp = rule ? "fake-ip-rule" : "fake-ip-filter";
                }
            }
        }
        return new Result(String.join("\n", lines), true, fakeIp, "");
    }

    /* ------------------------------ sniffer ------------------------------ */

    /** none: no sniffer block; enabled; disabled (explicit or missing enable); pure-ip-off: parse-pure-ip: false. */
    static String snifferState(String yaml) {
        List<String> lines = Arrays.asList((yaml == null ? "" : yaml.replace("\r\n", "\n")).split("\n", -1));
        int start = topLevel(lines, "sniffer");
        if (start < 0) return "none";
        if (!valueOf(lines.get(start)).isEmpty()) return "inline";
        int end = blockEnd(lines, start), child = childIndent(lines, start, end);
        int enable = findChild(lines, start, end, child, "enable");
        if (enable < 0 || !"true".equalsIgnoreCase(unquote(valueOf(lines.get(enable))))) return "disabled";
        int pure = findChild(lines, start, end, child, "parse-pure-ip");
        if (pure >= 0 && "false".equalsIgnoreCase(unquote(valueOf(lines.get(pure))))) return "pure-ip-off";
        return "enabled";
    }

    /**
     * A subscription sniffer with enable: true but no parse-pure-ip gets an explicit
     * parse-pure-ip: true (Mihomo's default at ab405bad, made explicit so forks/older cores agree).
     * enable: false and an explicit parse-pure-ip are the user's choice and stay unchanged.
     */
    static String ensureParsePureIp(String source) {
        String yaml = source == null ? "" : source.replace("\r\n", "\n").replace('\r', '\n');
        List<String> lines = new ArrayList<>(Arrays.asList(yaml.split("\n", -1)));
        int start = topLevel(lines, "sniffer");
        if (start < 0 || !valueOf(lines.get(start)).isEmpty()) return yaml;
        int end = blockEnd(lines, start), child = childIndent(lines, start, end);
        int enable = findChild(lines, start, end, child, "enable");
        if (enable < 0 || !"true".equalsIgnoreCase(unquote(valueOf(lines.get(enable))))) return yaml;
        if (findChild(lines, start, end, child, "parse-pure-ip") >= 0) return yaml;
        lines.add(enable + 1, spaces(child) + "parse-pure-ip: true");
        return String.join("\n", lines);
    }

    /* ------------------------------ DNS summary ------------------------------ */

    /** dns.enhanced-mode of a YAML ("fake-ip", "redir-host", "" without a dns block / value). */
    static String enhancedMode(String yaml) {
        List<String> lines = Arrays.asList((yaml == null ? "" : yaml.replace("\r\n", "\n")).split("\n", -1));
        int start = topLevel(lines, "dns");
        if (start < 0 || !valueOf(lines.get(start)).isEmpty()) return "";
        int end = blockEnd(lines, start), child = childIndent(lines, start, end);
        int mode = findChild(lines, start, end, child, "enhanced-mode");
        return mode < 0 ? "redir-host" : unquote(valueOf(lines.get(mode))).toLowerCase(Locale.ROOT);
    }

    static boolean hasDnsBlock(String yaml) {
        return topLevel(Arrays.asList((yaml == null ? "" : yaml.replace("\r\n", "\n")).split("\n", -1)), "dns") >= 0;
    }

    /** True when the runtime copy answers the Hetu DNS block set from nameserver-policy. */
    static boolean isInjected(String yaml) {
        if (yaml == null) return false;
        for (String raw : yaml.split("\\r?\\n")) {
            String t = raw.trim();
            if (t.startsWith("#")) continue;
            if (t.contains("rule-set:" + PROVIDER_NAME) && t.contains(BLOCK_ANSWER)) return true;
        }
        return false;
    }

    /* ------------------------------ helpers ------------------------------ */

    private static int topLevel(List<String> lines, String name) {
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (indent(line) == 0 && name.equals(key(line.trim()))) return i;
        }
        return -1;
    }

    private static int blockEnd(List<String> lines, int start) {
        for (int i = start + 1; i < lines.size(); i++) {
            String t = lines.get(i).trim();
            if (t.isEmpty() || t.startsWith("#")) continue;
            if (indent(lines.get(i)) == 0) return i;
        }
        return lines.size();
    }

    private static int childIndent(List<String> lines, int start, int end) {
        int child = Integer.MAX_VALUE;
        for (int i = start + 1; i < end; i++) {
            String t = lines.get(i).trim();
            if (t.isEmpty() || t.startsWith("#")) continue;
            child = Math.min(child, indent(lines.get(i)));
        }
        return child == Integer.MAX_VALUE || child == 0 ? 2 : child;
    }

    private static int findChild(List<String> lines, int start, int end, int child, String name) {
        for (int i = start + 1; i < end; i++) {
            String line = lines.get(i);
            if (indent(line) == child && name.equals(key(line.trim()))) return i;
        }
        return -1;
    }

    /** Indent of the first nested line under [keyLine] (a list item may sit at the key's own indent). */
    private static int firstNestedIndent(List<String> lines, int keyLine, int end, int child, int fallback) {
        for (int i = keyLine + 1; i < end; i++) {
            String t = lines.get(i).trim();
            if (t.isEmpty() || t.startsWith("#")) continue;
            int ind = indent(lines.get(i));
            if (ind > child || (ind == child && t.startsWith("-"))) return ind;
            return fallback;
        }
        return fallback;
    }

    private static int lastContentLine(List<String> lines, int start, int end) {
        int last = start;
        for (int i = start + 1; i < end; i++) {
            String t = lines.get(i).trim();
            if (!t.isEmpty()) last = i;
        }
        return last;
    }

    private static String key(String trimmed) {
        if (trimmed.startsWith("-") || trimmed.startsWith("#")) return "";
        int colon = trimmed.indexOf(':');
        if (colon <= 0) return "";
        return unquote(trimmed.substring(0, colon).trim());
    }

    private static String valueOf(String line) {
        String t = line.trim();
        int colon = t.indexOf(':');
        if (colon < 0) return "";
        String rest = t.substring(colon + 1).trim();
        boolean single = false, dbl = false;
        for (int i = 0; i < rest.length(); i++) {
            char c = rest.charAt(i);
            if (c == '\'' && !dbl) single = !single;
            else if (c == '"' && !single) dbl = !dbl;
            else if (c == '#' && !single && !dbl && (i == 0 || Character.isWhitespace(rest.charAt(i - 1)))) return rest.substring(0, i).trim();
        }
        return rest;
    }

    private static String unquote(String v) {
        String t = v == null ? "" : v.trim();
        if (t.length() >= 2 && ((t.charAt(0) == '"' && t.charAt(t.length() - 1) == '"') || (t.charAt(0) == '\'' && t.charAt(t.length() - 1) == '\'')))
            return t.substring(1, t.length() - 1).trim();
        return t;
    }

    private static int indent(String s) { int n = 0; while (n < s.length() && (s.charAt(n) == ' ' || s.charAt(n) == '\t')) n++; return n; }
    private static String spaces(int n) { StringBuilder b = new StringBuilder(n); for (int i = 0; i < n; i++) b.append(' '); return b.toString(); }
}
