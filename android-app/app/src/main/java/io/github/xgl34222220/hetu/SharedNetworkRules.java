package io.github.xgl34222220.hetu;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Pure rules of the shared network page and its Root policy file (no Android API, host-tested).
 *
 * Shared interfaces are the tether ingress the Root script scopes the shared chains to; they are
 * stored apart from the bypass interfaces ("proxyBypassInterfaces"), so ticking an upstream like
 * wlan0 can no longer silently make this device's own Wi-Fi traffic direct. MAC mode "direct"
 * (blacklist, "proxySharedBypassMacs") or "only" (whitelist, "proxySharedOnlyMacs").
 */
final class SharedNetworkRules {
    private SharedNetworkRules() {}

    static final int MAX_INTERFACES = 16;
    static final int MAX_MACS = 64;
    private static final Pattern IFACE_SAFE = Pattern.compile("[A-Za-z0-9_.:@-]{1,31}\\+?");
    private static final Pattern MAC_SAFE = Pattern.compile("(?i)[0-9a-f]{2}(?::[0-9a-f]{2}){5}");
    /** Typical Android tether interfaces (Wi-Fi AP, USB RNDIS/NCM, Bluetooth PAN, Ethernet). */
    private static final Pattern TETHER_NAME = Pattern.compile("(ap|swlan|softap|wlan[1-9]|rndis|ncm|bt-pan|usb|eth|wigig)[0-9A-Za-z_.:-]*\\+?");
    /** Never a tether ingress: cellular, loopback and virtual links (also the Root script refuses them). */
    private static final Pattern NEVER_SHARED = Pattern.compile("(lo|rmnet|ccmni|dummy|tun|hetu|ifb|ip6tnl|sit).*");

    static String mode(String raw) { return "only".equals(raw == null ? "" : raw.trim()) ? "only" : "direct"; }

    static String sanitizeInterfaces(Collection<String> values) throws IOException {
        if (values == null || values.isEmpty()) return "";
        LinkedHashSet<String> out = new LinkedHashSet<>();
        TreeSet<String> sorted = new TreeSet<>();
        for (String raw : nonNull(values)) sorted.add(raw.trim());
        for (String value : sorted) {
            if (value.isEmpty()) continue;
            if (!IFACE_SAFE.matcher(value).matches()) throw new IOException("共享接口名无效：" + value);
            if (NEVER_SHARED.matcher(value).matches()) throw new IOException("不能把 " + value + " 作为共享接口：蜂窝 / 回环 / 虚拟接口不是热点入口");
            out.add(value);
        }
        if (out.size() > MAX_INTERFACES) throw new IOException("共享接口最多 " + MAX_INTERFACES + " 个");
        return String.join(",", out);
    }

    static String sanitizeMacs(Collection<String> values) throws IOException {
        if (values == null || values.isEmpty()) return "";
        TreeSet<String> out = new TreeSet<>();
        for (String raw : nonNull(values)) {
            String value = raw.trim().toLowerCase(Locale.ROOT);
            if (value.isEmpty()) continue;
            if (!MAC_SAFE.matcher(value).matches()) throw new IOException("MAC 地址无效：" + value);
            if (value.equals("00:00:00:00:00:00") || value.equals("ff:ff:ff:ff:ff:ff")) throw new IOException("不能使用全零或广播 MAC：" + value);
            out.add(value);
        }
        if (out.size() > MAX_MACS) throw new IOException("共享网络 MAC 白名单超过 " + MAX_MACS + " 项");
        return String.join(",", out);
    }

    /**
     * Body of $BASE/policy/share, or "" when nothing is scoped (no interface and blacklist mode):
     * the Root script then keeps the legacy, unscoped shared chains.
     */
    static String policyBody(Collection<String> interfaces, String macMode, Collection<String> onlyMacs) throws IOException {
        String ifaces = sanitizeInterfaces(interfaces);
        String mode = mode(macMode);
        String macs = "only".equals(mode) ? sanitizeMacs(onlyMacs) : "";
        if (ifaces.isEmpty() && "direct".equals(mode)) return "";
        return "IFACES=" + ifaces + "\nMODE=" + mode + "\nMACS=" + macs + "\n";
    }

    /** A candidate for the shared-interface list: tether-like name, not cellular/virtual, not the upstream. */
    static boolean tetherCandidate(String name, Collection<String> upstream, Collection<String> tethered) {
        if (name == null || name.isEmpty() || NEVER_SHARED.matcher(name).matches()) return false;
        if (upstream != null && upstream.contains(name)) return false;
        if (tethered != null && tethered.contains(name)) return true;
        return TETHER_NAME.matcher(name).matches();
    }

    /** `ip route get` output -> the "dev" interface (upstream), or "". */
    static String routeDevice(String output) {
        if (output == null) return "";
        String[] words = output.trim().split("\\s+");
        for (int i = 0; i + 1 < words.length; i++) if (words[i].equals("dev")) return words[i + 1];
        return "";
    }

    /** Interfaces reported as tethered by `dumpsys tethering` / `dumpsys connectivity` (best effort). */
    static Set<String> tetheredInterfaces(String dumpsys) {
        TreeSet<String> out = new TreeSet<>();
        if (dumpsys == null) return out;
        for (String line : dumpsys.split("\\r?\\n")) {
            String t = line.trim();
            // "wlan2 - TetheredState - lastError = 0" / "rndis0 - TETHERED" / "Tethered: [ap0, rndis0]"
            int dash = t.indexOf(" - ");
            if (dash > 0 && t.substring(dash).toLowerCase(Locale.ROOT).contains("tethered") && !t.toLowerCase(Locale.ROOT).contains("untethered")) {
                String name = t.substring(0, dash).trim();
                if (IFACE_SAFE.matcher(name).matches()) out.add(name);
            }
            if (t.startsWith("Tethered") && t.contains("[")) {
                String inner = t.substring(t.indexOf('[') + 1, t.indexOf(']') > 0 ? t.indexOf(']') : t.length());
                for (String n : inner.split("[,\\s]+")) if (IFACE_SAFE.matcher(n.trim()).matches()) out.add(n.trim());
            }
        }
        return out;
    }

    /** One DHCP lease: dnsmasq format "expiry mac ip hostname client-id". */
    static final class Lease {
        final String mac, ip, hostname;
        Lease(String mac, String ip, String hostname) { this.mac = mac; this.ip = ip; this.hostname = hostname; }
    }

    static List<Lease> parseLeases(String text) {
        ArrayList<Lease> out = new ArrayList<>();
        if (text == null) return out;
        for (String line : text.split("\\r?\\n")) {
            String[] w = line.trim().split("\\s+");
            if (w.length < 3 || !w[0].matches("[0-9]+")) continue;
            String mac = w[1].toLowerCase(Locale.ROOT);
            if (!MAC_SAFE.matcher(mac).matches() || !w[2].matches("[0-9A-Fa-f:.]{2,64}")) continue;
            String host = w.length > 3 && !w[3].equals("*") ? w[3].replaceAll("[^A-Za-z0-9._-]", "") : "";
            out.add(new Lease(mac, w[2], host.length() > 63 ? host.substring(0, 63) : host));
        }
        return out;
    }

    private static List<String> nonNull(Collection<String> values) {
        ArrayList<String> out = new ArrayList<>();
        for (String v : values) if (v != null) out.add(v);
        return out;
    }
}
