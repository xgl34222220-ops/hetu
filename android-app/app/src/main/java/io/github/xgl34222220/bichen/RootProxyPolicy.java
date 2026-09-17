package io.github.xgl34222220.bichen;

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
    private static final int MAX_CIDRS = 256;
    private static final int MAX_INTERFACES = 32;
    private static final Pattern CIDR_SAFE = Pattern.compile("[0-9A-Fa-f:.]+/[0-9]{1,3}");
    private static final Pattern IFACE_SAFE = Pattern.compile("[A-Za-z0-9_.:@-]+\\+?");

    final String appScope;
    final String uidRanges;
    final boolean sharedNetwork;
    final boolean killSwitch;
    final String cidrs;
    final String interfaces;
    final Set<String> missingPackages;
    final Set<String> skippedSystemPackages;

    private RootProxyPolicy(
            String appScope,
            String uidRanges,
            boolean sharedNetwork,
            boolean killSwitch,
            String cidrs,
            String interfaces,
            Set<String> missingPackages,
            Set<String> skippedSystemPackages) {
        this.appScope = appScope;
        this.uidRanges = uidRanges;
        this.sharedNetwork = sharedNetwork;
        this.killSwitch = killSwitch;
        this.cidrs = cidrs;
        this.interfaces = interfaces;
        this.missingPackages = Collections.unmodifiableSet(missingPackages);
        this.skippedSystemPackages = Collections.unmodifiableSet(skippedSystemPackages);
    }

    static RootProxyPolicy load(Context context, SharedPreferences prefs, ProxyRuntimeProfile profile) throws IOException {
        String scope = profile.appScope.id;
        TreeSet<Integer> uids = new TreeSet<>();
        TreeSet<String> missing = new TreeSet<>();
        TreeSet<String> skipped = new TreeSet<>();
        Set<String> selected = prefs.getStringSet("proxyAppPackages", Collections.emptySet());
        if (selected == null) selected = Collections.emptySet();

        if (profile.appScope != ProxyRuntimeProfile.AppScope.CORE) {
            PackageManager pm = context.getPackageManager();
            for (String pkg : new TreeSet<>(selected)) {
                if (pkg == null || pkg.isEmpty() || pkg.equals(context.getPackageName())) continue;
                try {
                    ApplicationInfo info = pm.getApplicationInfo(pkg, 0);
                    if (info.uid < FIRST_APP_UID) {
                        skipped.add(pkg);
                    } else {
                        uids.add(info.uid);
                    }
                } catch (PackageManager.NameNotFoundException missingPackage) {
                    missing.add(pkg);
                }
            }
            if (uids.size() > MAX_UIDS)
                throw new IOException("Root 分应用名单超过 " + MAX_UIDS + " 个 UID，请缩小选择范围");
            if (profile.appScope == ProxyRuntimeProfile.AppScope.WHITELIST && uids.isEmpty())
                throw new IOException("当前是“仅所选应用代理”，但应用名单没有可用的普通应用 UID");
        }

        String cidrs = sanitizeCidrs(prefs.getStringSet("proxyBypassCidrs", Collections.emptySet()));
        String interfaces = sanitizeInterfaces(prefs.getStringSet("proxyBypassInterfaces", Collections.emptySet()));
        return new RootProxyPolicy(
                scope,
                compress(uids),
                prefs.getBoolean("proxySharedNetwork", false),
                prefs.getBoolean("proxyKillSwitch", false),
                cidrs,
                interfaces,
                missing,
                skipped);
    }

    String warning() {
        ArrayList<String> parts = new ArrayList<>();
        if (!missingPackages.isEmpty()) parts.add("已忽略 " + missingPackages.size() + " 个已卸载应用");
        if (!skippedSystemPackages.isEmpty()) parts.add("为避免影响系统服务，已忽略 " + skippedSystemPackages.size() + " 个系统 UID 应用");
        return String.join("；", parts);
    }

    private static String compress(SortedSet<Integer> values) {
        if (values.isEmpty()) return "";
        StringBuilder out = new StringBuilder();
        int start = -1, previous = -1;
        for (int value : values) {
            if (start < 0) {
                start = previous = value;
                continue;
            }
            if (value == previous + 1) {
                previous = value;
                continue;
            }
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
