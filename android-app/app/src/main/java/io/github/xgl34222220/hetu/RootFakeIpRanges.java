package io.github.xgl34222220.hetu;

import java.io.IOException;
import java.math.BigInteger;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.NodeTuple;
import org.yaml.snakeyaml.nodes.ScalarNode;
import org.yaml.snakeyaml.nodes.SequenceNode;
import org.yaml.snakeyaml.nodes.Tag;

/** Read-only Fake-IP routing metadata from the final, generated Mihomo YAML. */
final class RootFakeIpRanges {
    private static final String DEFAULT_IPV4 = "198.18.0.1/16";
    private static final String PRIVATE_IPV4 = "0.0.0.0/8,10.0.0.0/8,100.64.0.0/10,127.0.0.0/8,169.254.0.0/16,172.16.0.0/12,192.168.0.0/16,224.0.0.0/4,240.0.0.0/4";
    private static final String PRIVATE_IPV6 = "::1/128,fc00::/7,fe80::/10,ff00::/8";
    final String ipv4;
    final String ipv6;
    final String ipv4BypassCidrs;
    final String ipv6BypassCidrs;

    private RootFakeIpRanges(String ipv4, String ipv6) throws IOException {
        this.ipv4 = ipv4;
        this.ipv6 = ipv6;
        ipv4BypassCidrs = subtract(PRIVATE_IPV4, ipv4, 32);
        ipv6BypassCidrs = subtract(PRIVATE_IPV6, ipv6, 128);
    }

    /**
     * Annotate only the private generated startup copy, never the selected source.
     * Remove pre-existing owned comments before appending the one final policy
     * block, so subscription comments cannot impersonate our routing metadata.
     * All unrelated bytes and YAML node semantics remain unchanged.
     */
    String privateStartup(String generatedYaml) throws IOException {
        if (generatedYaml == null) throw failure("FAKE_IP_YAML");
        List<int[]> scalars = scalarSpans(generatedYaml);
        StringBuilder result = new StringBuilder(generatedYaml.length() + 512);
        int start = 0;
        int codePointStart = 0;
        int scalarIndex = 0;
        while (start < generatedYaml.length()) {
            int newline = generatedYaml.indexOf('\n', start);
            int end = newline < 0 ? generatedYaml.length() : newline;
            String line = generatedYaml.substring(start, end);
            int comment = ownedComment(line);
            int point = codePointStart + Math.max(0, comment);
            while (scalarIndex < scalars.size() && scalars.get(scalarIndex)[1] <= point) scalarIndex++;
            boolean scalarContent = scalarIndex < scalars.size() && scalars.get(scalarIndex)[0] <= point;
            if (comment < 0 || scalarContent) {
                result.append(line);
                if (newline >= 0) result.append('\n');
            }
            codePointStart += generatedYaml.codePointCount(start, newline < 0 ? end : end + 1);
            start = newline < 0 ? generatedYaml.length() : newline + 1;
        }
        if (result.length() > 0 && result.charAt(result.length() - 1) != '\n') result.append('\n');
        result.append("# HETU_FAKE_IP_POLICY=1\n")
                .append("# HETU_FAKE_IP_V4=").append(ipv4).append('\n')
                .append("# HETU_FAKE_IP_V6=").append(ipv6).append('\n')
                .append("# HETU_LAN_RETURN_V4=").append(ipv4BypassCidrs).append('\n')
                .append("# HETU_LAN_RETURN_V6=").append(ipv6BypassCidrs).append('\n');
        return result.toString();
    }

    private static int ownedComment(String line) {
        int start = 0;
        while (start < line.length() && (line.charAt(start) == ' ' || line.charAt(start) == '\t')) start++;
        boolean owned = line.startsWith("# HETU_FAKE_IP_POLICY=", start)
                || line.startsWith("# HETU_FAKE_IP_V4=", start)
                || line.startsWith("# HETU_FAKE_IP_V6=", start)
                || line.startsWith("# HETU_LAN_RETURN_V4=", start)
                || line.startsWith("# HETU_LAN_RETURN_V6=", start);
        return owned ? start : -1;
    }

    private static List<int[]> scalarSpans(String source) throws IOException {
        final Node root;
        try { root = RuntimeYaml15.compose(source); }
        catch (IOException invalid) { throw failure("FAKE_IP_YAML"); }
        List<int[]> spans = new ArrayList<>();
        Set<Node> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        Deque<Node> pending = new ArrayDeque<>();
        pending.push(root);
        while (!pending.isEmpty()) {
            Node node = pending.pop();
            if (!visited.add(node)) continue;
            if (node instanceof ScalarNode) {
                spans.add(new int[]{node.getStartMark().getIndex(), node.getEndMark().getIndex()});
            } else if (node instanceof MappingNode) {
                for (NodeTuple entry : ((MappingNode) node).getValue()) {
                    pending.push(entry.getKeyNode());
                    pending.push(entry.getValueNode());
                }
            } else if (node instanceof SequenceNode) {
                for (Node item : ((SequenceNode) node).getValue()) pending.push(item);
            }
        }
        spans.sort((a, b) -> Integer.compare(a[0], b[0]));
        return spans;
    }

    /**
     * Defaults match MetaCubeX/mihomo v1.19.30 config/config.go DefaultRawConfig:
     * global IPv6 true, DNS enable/IPv6 false, redir-host, v4 198.18.0.1/16,
     * no v6 Fake-IP range. Explicit empty ranges remain empty; no source edits.
     * Only built-in private bypass ranges are subtracted. User CIDRs stay under
     * the existing, explicit shell bypass rules and are never read here.
     */
    static RootFakeIpRanges parse(String generatedYaml, ProxyRuntimeProfile profile) throws IOException {
        if (profile == null) throw failure("FAKE_IP_PROFILE");
        boolean mihomo = profile.core == ProxyRuntimeProfile.Core.MIHOMO
                || profile.core == ProxyRuntimeProfile.Core.MIHOMO_SMART;
        boolean mode = profile.mode == ProxyRuntimeProfile.Mode.TPROXY
                || profile.mode == ProxyRuntimeProfile.Mode.REDIRECT
                || profile.mode == ProxyRuntimeProfile.Mode.ENHANCE;
        if (!mihomo || !mode) return new RootFakeIpRanges("", "");

        final Node root;
        try {
            root = RuntimeYaml15.compose(generatedYaml);
        } catch (IOException invalid) {
            // Even parser line/column details are excluded from this API.
            throw failure("FAKE_IP_YAML");
        }
        Node dns = RuntimeYaml15.inherited(root, "dns");
        if (isNull(dns)) return new RootFakeIpRanges("", "");
        if (!(dns instanceof MappingNode)) throw failure("FAKE_IP_DNS");
        if (!bool(RuntimeYaml15.inherited(dns, "enable"), false))
            return new RootFakeIpRanges("", "");
        // Mihomo DNSMode.UnmarshalText folds case, but does not trim input.
        String enhancedMode = scalar(RuntimeYaml15.inherited(dns, "enhanced-mode"), "redir-host")
                .toLowerCase(Locale.ROOT);
        if (!"fake-ip".equals(enhancedMode)) return new RootFakeIpRanges("", "");

        String ipv4 = range(RuntimeYaml15.inherited(dns, "fake-ip-range"), DEFAULT_IPV4, 32);
        String ipv6 = "";
        if (profile.ipv6 == ProxyRuntimeProfile.Ipv6.ENABLE
                && bool(RuntimeYaml15.inherited(root, "ipv6"), true)
                && bool(RuntimeYaml15.inherited(dns, "ipv6"), false)) {
            ipv6 = range(RuntimeYaml15.inherited(dns, "fake-ip-range6"), "", 128);
        }
        return new RootFakeIpRanges(ipv4, ipv6);
    }

    private static boolean isNull(Node node) {
        return node == null || Tag.NULL.equals(node.getTag());
    }

    private static String scalar(Node node, String fallback) throws IOException {
        if (isNull(node)) return fallback;
        if (!(node instanceof ScalarNode)) throw failure("FAKE_IP_FIELD");
        return ((ScalarNode) node).getValue();
    }

    private static boolean bool(Node node, boolean fallback) throws IOException {
        if (isNull(node)) return fallback;
        String value = scalar(node, "").toLowerCase(Locale.ROOT);
        // go-yaml accepts YAML 1.1 bool spellings when decoding into a bool.
        switch (value) {
            case "true": case "yes": case "y": case "on": return true;
            case "false": case "no": case "n": case "off": return false;
            default: throw failure("FAKE_IP_BOOLEAN");
        }
    }

    private static String range(Node node, String fallback, int width) throws IOException {
        String value = scalar(node, fallback);
        if (value.isEmpty()) return "";
        Cidr parsed = Cidr.parse(value, width);
        // Validation permits ASCII digits, hexadecimal digits, '.', ':', '/'
        // only. Do not trim unsafe whitespace or permit names/scopes/quotes.
        // ip6tables policy metadata accepts hexadecimal IPv6 notation. Numeric
        // embedded IPv4 remains valid source YAML; emit its equivalent prefix
        // in pure IPv6 notation for the shell without changing that source.
        return width == 128 && value.indexOf('.') >= 0 ? parsed.text() : value.toLowerCase(Locale.ROOT);
    }

    private static IOException failure(String category) {
        return new IOException("Fake-IP 运行配置无效；未修改源文件 [" + category + "]");
    }

    private static String subtract(String defaults, String protectedRange, int width) throws IOException {
        if (protectedRange.isEmpty()) return defaults;
        Cidr fake = Cidr.parse(protectedRange, width);
        List<String> result = new ArrayList<>();
        for (String item : defaults.split(",")) {
            Cidr bypass = Cidr.parse(item, width);
            if (!bypass.contains(fake) && !fake.contains(bypass)) {
                result.add(item);
            } else if (!fake.contains(bypass)) {
                // Aligned CIDRs overlap only by containment. At each split,
                // retain the sibling outside fake, then follow its one child.
                // Work is bounded by 32/128 bits, without recursive expansion.
                Cidr current = bypass;
                List<Cidr> pieces = new ArrayList<>();
                while (current.bits < fake.bits) {
                    int childBits = current.bits + 1;
                    BigInteger step = BigInteger.ONE.shiftLeft(width - childBits);
                    Cidr low = new Cidr(current.network, childBits, width);
                    Cidr high = new Cidr(current.network.add(step), childBits, width);
                    if (low.contains(fake)) {
                        pieces.add(high);
                        current = low;
                    } else {
                        pieces.add(low);
                        current = high;
                    }
                }
                pieces.sort((a, b) -> a.network.compareTo(b.network));
                for (Cidr piece : pieces) result.add(piece.text());
            }
        }
        return String.join(",", result);
    }

    private static final class Cidr {
        final BigInteger network;
        final int bits;
        final int width;

        Cidr(BigInteger address, int bits, int width) {
            this.bits = bits;
            this.width = width;
            int hostBits = width - bits;
            network = address.shiftRight(hostBits).shiftLeft(hostBits);
        }

        boolean contains(Cidr other) {
            return width == other.width && bits <= other.bits
                    && network.equals(other.network.shiftRight(width - bits).shiftLeft(width - bits));
        }

        static Cidr parse(String value, int width) throws IOException {
            int slash = value.indexOf('/');
            if (slash < 1 || slash != value.lastIndexOf('/') || slash == value.length() - 1)
                throw failure("FAKE_IP_CIDR");
            String prefix = value.substring(slash + 1);
            if (prefix.length() > 3) throw failure("FAKE_IP_CIDR");
            int bits = decimal(prefix, width, false);
            String address = value.substring(0, slash);
            BigInteger numeric = width == 32 ? ipv4(address) : ipv6(address);
            return new Cidr(numeric, bits, width);
        }

        private static int decimal(String text, int maximum, boolean noLeadingZero) throws IOException {
            if (text.isEmpty() || (noLeadingZero && text.length() > 1 && text.charAt(0) == '0'))
                throw failure("FAKE_IP_CIDR");
            int value = 0;
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                if (c < '0' || c > '9') throw failure("FAKE_IP_CIDR");
                value = value * 10 + c - '0';
                if (value > maximum) throw failure("FAKE_IP_CIDR");
            }
            return value;
        }

        private static BigInteger ipv4(String text) throws IOException {
            String[] octets = text.split("\\.", -1);
            if (octets.length != 4) throw failure("FAKE_IP_CIDR");
            long result = 0;
            for (String octet : octets) {
                if (octet.length() > 3) throw failure("FAKE_IP_CIDR");
                result = (result << 8) | decimal(octet, 255, true);
            }
            return BigInteger.valueOf(result);
        }

        private static BigInteger ipv6(String text) throws IOException {
            if (text.indexOf(':') < 0 || text.length() > 45) throw failure("FAKE_IP_CIDR");
            int compression = text.indexOf("::");
            if (compression != text.lastIndexOf("::")) throw failure("FAKE_IP_CIDR");
            List<Integer> left;
            List<Integer> right;
            if (compression < 0) {
                left = groups(text, true);
                right = new ArrayList<>();
                if (left.size() != 8) throw failure("FAKE_IP_CIDR");
            } else {
                left = groups(text.substring(0, compression), false);
                right = groups(text.substring(compression + 2), true);
                if (left.size() + right.size() >= 8) throw failure("FAKE_IP_CIDR");
            }
            BigInteger result = BigInteger.ZERO;
            for (int group : left) result = result.shiftLeft(16).or(BigInteger.valueOf(group));
            result = result.shiftLeft(16 * (8 - left.size() - right.size()));
            for (int group : right) result = result.shiftLeft(16).or(BigInteger.valueOf(group));
            return result;
        }

        private static List<Integer> groups(String text, boolean allowIpv4End) throws IOException {
            List<Integer> result = new ArrayList<>();
            if (text.isEmpty()) return result;
            String[] items = text.split(":", -1);
            for (int index = 0; index < items.length; index++) {
                String item = items[index];
                if (item.indexOf('.') >= 0) {
                    if (!allowIpv4End || index != items.length - 1) throw failure("FAKE_IP_CIDR");
                    long v4 = ipv4(item).longValue();
                    result.add((int) (v4 >>> 16));
                    result.add((int) (v4 & 0xffff));
                } else {
                    if (item.isEmpty() || item.length() > 4) throw failure("FAKE_IP_CIDR");
                    int value = 0;
                    for (int i = 0; i < item.length(); i++) {
                        char c = item.charAt(i);
                        int digit = c >= '0' && c <= '9' ? c - '0'
                                : c >= 'a' && c <= 'f' ? c - 'a' + 10
                                : c >= 'A' && c <= 'F' ? c - 'A' + 10 : -1;
                        if (digit < 0) throw failure("FAKE_IP_CIDR");
                        value = (value << 4) | digit;
                    }
                    result.add(value);
                }
                if (result.size() > 8) throw failure("FAKE_IP_CIDR");
            }
            return result;
        }

        String text() {
            if (width == 32) {
                long address = network.longValue();
                return ((address >>> 24) & 255) + "." + ((address >>> 16) & 255) + "."
                        + ((address >>> 8) & 255) + "." + (address & 255) + "/" + bits;
            }
            int[] groups = new int[8];
            for (int i = 0; i < 8; i++) groups[i] = network.shiftRight(16 * (7 - i)).intValue() & 0xffff;
            int bestStart = -1, bestLength = 1;
            for (int i = 0; i < 8;) {
                if (groups[i] != 0) { i++; continue; }
                int start = i;
                while (i < 8 && groups[i] == 0) i++;
                if (i - start > bestLength) { bestStart = start; bestLength = i - start; }
            }
            StringBuilder text = new StringBuilder();
            for (int i = 0; i < 8;) {
                if (i == bestStart) {
                    text.append("::");
                    i += bestLength;
                } else {
                    if (text.length() > 0 && text.charAt(text.length() - 1) != ':') text.append(':');
                    text.append(Integer.toHexString(groups[i++]));
                }
            }
            return text.append('/').append(bits).toString();
        }
    }
}
