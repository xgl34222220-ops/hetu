package io.github.xgl34222220.hetu;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import java.io.IOException;
import java.util.*;
import java.util.regex.Pattern;

/** Immutable Root transparent-proxy policy snapshot built before a network transaction starts. */
final class RootProxyPolicy {
    private static final int FIRST_APP_UID = 10000;
    private static final int MAX_UIDS = 512;
    private static final int MAX_DIRECT_UIDS = 256;
    private static final int MAX_DIRECT_GID_RANGES = 64;
    private static final int MAX_CIDRS = 256;
    private static final int MAX_INTERFACES = 32;
    private static final int MAX_SHARED_MACS = 64;
    private static final Pattern CIDR_SAFE = Pattern.compile("[0-9A-Fa-f:.]+/[0-9]{1,3}");
    private static final Pattern IFACE_SAFE = Pattern.compile("[A-Za-z0-9_.:@-]+\\+?");
    private static final Pattern MAC_SAFE = Pattern.compile("(?i)[0-9a-f]{2}(?::[0-9a-f]{2}){5}");
    private static final Pattern PKG_SAFE = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)+(?:\\*)?");

    final String appScope;
    final String uidRanges;
    final String directUidRanges;
    final String directGidRanges;
    final boolean sharedNetwork;
    final boolean killSwitch;
    final String cidrs;
    final String interfaces;
    final String sharedBypassMacs;
    final Set<String> missingPackages;
    final Set<String> skippedSystemPackages;
    final Set<String> directPackages;

    private RootProxyPolicy(
            String appScope,
            String uidRanges,
            String directUidRanges,
            String directGidRanges,
            boolean sharedNetwork,
            boolean killSwitch,
            String cidrs,
            String interfaces,
            String sharedBypassMacs,
            Set<String> missingPackages,
            Set<String> skippedSystemPackages,
            Set<String> directPackages) {
        this.appScope = appScope;
        this.uidRanges = uidRanges;
        this.directUidRanges = directUidRanges;
        this.directGidRanges = directGidRanges;
        this.sharedNetwork = sharedNetwork;
        this.killSwitch = killSwitch;
        this.cidrs = cidrs;
        this.interfaces = interfaces;
        this.sharedBypassMacs = sharedBypassMacs;
        this.missingPackages = Collections.unmodifiableSet(new TreeSet<>(missingPackages));
        this.skippedSystemPackages = Collections.unmodifiableSet(new TreeSet<>(skippedSystemPackages));
        this.directPackages = Collections.unmodifiableSet(new TreeSet<>(directPackages));
    }

    static RootProxyPolicy load(Context context, SharedPreferences prefs, ProxyRuntimeProfile profile) throws IOException {
        return load(context, prefs, profile, Collections.emptySet());
    }

    static RootProxyPolicy load(Context context, SharedPreferences prefs, ProxyRuntimeProfile profile, Set<String> directPatterns) throws IOException {
        String scope = profile.appScope.id;
        TreeSet<Integer> uids = new TreeSet<>();
        TreeSet<Integer> directUids = new TreeSet<>();
        TreeSet<String> missing = new TreeSet<>();
        TreeSet<String> skipped = new TreeSet<>();
        TreeSet<String> resolvedDirectPackages = new TreeSet<>();
        Set<String> selected = prefs.getStringSet("proxyAppPackages", Collections.emptySet());
        if (selected == null) selected = Collections.emptySet();
        PackageManager pm = context.getPackageManager();

        // The Android control app must never proxy its own WAN probes, rule downloads,
        // subscription traffic or diagnostics back through Mihomo. Mature transparent
        // proxy clients exclude their controller UID for the same reason: self-generated
        // traffic otherwise inflates /connections and can create feedback loops.
        try {
            ApplicationInfo self = pm.getApplicationInfo(context.getPackageName(), 0);
            if (self.uid >= FIRST_APP_UID) {
                directUids.add(self.uid);
                resolvedDirectPackages.add(context.getPackageName());
            }
        } catch (PackageManager.NameNotFoundException impossible) { }

        if (profile.appScope != ProxyRuntimeProfile.AppScope.CORE) {
            for (String pkg : new TreeSet<>(selected)) {
                if (pkg == null || pkg.isEmpty() || pkg.equals(context.getPackageName())) continue;
                try {
                    ApplicationInfo info = pm.getApplicationInfo(pkg, 0);
                    if (info.uid < FIRST_APP_UID) skipped.add(pkg);
                    else uids.add(info.uid);
                } catch (PackageManager.NameNotFoundException missingPackage) {
                    missing.add(pkg);
                }
            }
            if (uids.size() > MAX_UIDS)
                throw new IOException("Root 分应用名单超过 " + MAX_UIDS + " 个 UID，请缩小选择范围");
            if (profile.appScope == ProxyRuntimeProfile.AppScope.WHITELIST && uids.isEmpty())
                throw new IOException("当前是“仅所选应用代理”，但应用名单没有可用的普通应用 UID");
        }

        if (directPatterns != null && !directPatterns.isEmpty()) {
            List<ApplicationInfo> installed = null;
            for (String raw : new TreeSet<>(directPatterns)) {
                String pattern = raw == null ? "" : raw.trim();
                if (pattern.isEmpty() || !PKG_SAFE.matcher(pattern).matches()) continue;
                boolean wildcard = pattern.endsWith("*");
                String prefix = wildcard ? pattern.substring(0, pattern.length() - 1) : pattern;
                if (wildcard) {
                    if (installed == null) installed = pm.getInstalledApplications(0);
                    for (ApplicationInfo info : installed) {
                        String pkg = info.packageName;
                        if (pkg == null || !pkg.startsWith(prefix) || pkg.equals(context.getPackageName())) continue;
                        if (info.uid >= FIRST_APP_UID) {
                            directUids.add(info.uid);
                            resolvedDirectPackages.add(pkg);
                        }
                    }
                } else {
                    if (prefix.equals(context.getPackageName())) continue;
                    try {
                        ApplicationInfo info = pm.getApplicationInfo(prefix, 0);
                        if (info.uid >= FIRST_APP_UID) {
                            directUids.add(info.uid);
                            resolvedDirectPackages.add(prefix);
                        }
                    } catch (PackageManager.NameNotFoundException ignored) { }
                }
            }
        }
        if (directUids.size() > MAX_DIRECT_UIDS)
            throw new IOException("配置中的 DIRECT 应用超过 " + MAX_DIRECT_UIDS + " 个 UID，请缩小 PROCESS-NAME 规则范围");

        String cidrs = sanitizeCidrs(prefs.getStringSet("proxyBypassCidrs", Collections.emptySet()));
        String interfaces = sanitizeInterfaces(prefs.getStringSet("proxyBypassInterfaces", Collections.emptySet()));
        String sharedBypassMacs = sanitizeMacs(prefs.getStringSet("proxySharedBypassMacs", Collections.emptySet()));
        String directGids = sanitizeDirectGids(prefs.getStringSet("proxyDirectGids", Collections.emptySet()));
        if (!directGids.isEmpty() &&
                profile.mode != ProxyRuntimeProfile.Mode.TPROXY &&
                profile.mode != ProxyRuntimeProfile.Mode.REDIRECT &&
                profile.mode != ProxyRuntimeProfile.Mode.ENHANCE) {
            throw new IOException("GID 直连仅支持 TPROXY / Redirect / Enhance；当前 " + profile.mode.label + " 不会假装应用 GID 规则");
        }
        return new RootProxyPolicy(
                scope,
                compress(uids),
                compress(directUids),
                directGids,
                prefs.getBoolean("proxySharedNetwork", false),
                prefs.getBoolean("proxyKillSwitch", false),
                cidrs,
                interfaces,
                sharedBypassMacs,
                missing,
                skipped,
                resolvedDirectPackages);
    }

    String warning() {
        ArrayList<String> parts = new ArrayList<>();
        if (!missingPackages.isEmpty()) parts.add("已忽略 " + missingPackages.size() + " 个已卸载应用");
        if (!skippedSystemPackages.isEmpty()) parts.add("为避免影响系统服务，已忽略 " + skippedSystemPackages.size() + " 个系统 UID 应用");
        if (!directPackages.isEmpty()) parts.add("已将 " + directPackages.size() + " 个应用下沉为 Root 直连（包含河图自身控制进程）");
        if (!directGidRanges.isEmpty()) parts.add("已启用 GID 直连：" + directGidRanges);
        if (!sharedBypassMacs.isEmpty()) parts.add("共享网络已有 " + sharedBypassMacs.split(",").length + " 个 MAC 直连设备");
        return String.join("；", parts);
    }

    private static String compress(SortedSet<Integer> values) {
        if (values.isEmpty()) return "";
        StringBuilder out = new StringBuilder();
        int start = -1, previous = -1;
        for (int value : values) {
            if (start < 0) { start = previous = value; continue; }
            if (value == previous + 1) { previous = value; continue; }
            appendRange(out, start, previous);
            start = previous = value;
        }
        appendRange(out, start, previous);
        return out.toString();
    }

    private static void appendRange(StringBuilder out, int start, int end) {
        if (out.length() > 0) out.append(',');
        out.append(start);
        if (end != start) out.append('-').append(end);
    }

    private static String sanitizeDirectGids(Set<String> values) throws IOException {
        if (values == null || values.isEmpty()) return "";
        if (values.size() > MAX_DIRECT_GID_RANGES)
            throw new IOException("GID 直连列表超过 " + MAX_DIRECT_GID_RANGES + " 项");
        TreeSet<String> out = new TreeSet<>((a, b) -> {
            int ai = a.indexOf('-'), bi = b.indexOf('-');
            long aa = Long.parseLong(ai < 0 ? a : a.substring(0, ai));
            long bb = Long.parseLong(bi < 0 ? b : b.substring(0, bi));
            int cmp = Long.compare(aa, bb);
            return cmp != 0 ? cmp : a.compareTo(b);
        });
        for (String raw : values) {
            String value = raw == null ? "" : raw.trim();
            if (value.isEmpty()) continue;
            if (!value.matches("[0-9]{1,10}(?:-[0-9]{1,10})?"))
                throw new IOException("GID 格式无效：" + value);
            String[] parts = value.split("-", -1);
            long start;
            long end;
            try {
                start = Long.parseLong(parts[0]);
                end = parts.length == 2 ? Long.parseLong(parts[1]) : start;
            } catch (NumberFormatException bad) {
                throw new IOException("GID 数值无效：" + value);
            }
            if (start < FIRST_APP_UID || end < start || end > Integer.MAX_VALUE)
                throw new IOException("GID 仅允许普通应用范围（>=10000）：" + value);
            out.add(start == end ? Long.toString(start) : start + "-" + end);
        }
        return String.join(",", out);
    }

    private static String sanitizeCidrs(Set<String> values) throws IOException {
        if (values == null || values.isEmpty()) return "";
        if (values.size() > MAX_CIDRS) throw new IOException("CIDR 绕过列表超过 " + MAX_CIDRS + " 项");
        TreeSet<String> out = new TreeSet<>();
        for (String raw : values) {
            String value = raw == null ? "" : raw.trim();
            if (value.isEmpty()) continue;
            if (!CIDR_SAFE.matcher(value).matches()) throw new IOException("CIDR 格式不安全或无效：" + value);
            int slash = value.lastIndexOf('/');
            int prefix;
            try { prefix = Integer.parseInt(value.substring(slash + 1)); }
            catch (NumberFormatException bad) { throw new IOException("CIDR 前缀无效：" + value); }
            boolean ipv6 = value.indexOf(':') >= 0;
            if ((!ipv6 && prefix > 32) || (ipv6 && prefix > 128)) throw new IOException("CIDR 前缀越界：" + value);
            out.add(value);
        }
        return String.join(",", out);
    }

    private static String sanitizeMacs(Set<String> values) throws IOException {
        if (values == null || values.isEmpty()) return "";
        if (values.size() > MAX_SHARED_MACS)
            throw new IOException("共享网络 MAC 直连列表超过 " + MAX_SHARED_MACS + " 项");
        TreeSet<String> out = new TreeSet<>();
        for (String raw : values) {
            String value = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
            if (value.isEmpty()) continue;
            if (!MAC_SAFE.matcher(value).matches()) throw new IOException("MAC 地址无效：" + value);
            if (value.equals("00:00:00:00:00:00") || value.equals("ff:ff:ff:ff:ff:ff"))
                throw new IOException("不能使用全零或广播 MAC：" + value);
            out.add(value);
        }
        return String.join(",", out);
    }

    private static String sanitizeInterfaces(Set<String> values) throws IOException {
        if (values == null || values.isEmpty()) return "";
        if (values.size() > MAX_INTERFACES) throw new IOException("接口绕过列表超过 " + MAX_INTERFACES + " 项");
        TreeSet<String> out = new TreeSet<>();
        for (String raw : values) {
            String value = raw == null ? "" : raw.trim();
            if (value.isEmpty()) continue;
            if (value.length() > 32 || !IFACE_SAFE.matcher(value).matches()) throw new IOException("接口名无效：" + value);
            if (value.equals("lo") || value.equals("lo+")) throw new IOException("不能绕过 lo：TPROXY 本机回环接管依赖 loopback");
            out.add(value);
        }
        return String.join(",", out);
    }
}