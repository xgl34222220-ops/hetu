package io.github.xgl34222220.hetu;

import android.content.SharedPreferences;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Compares saved network settings with the snapshot of a completed transaction. */
final class ProxyRuntimeSettings {
    static final String DIRTY_KEY = "proxyRootSettingsDirty";
    private static final Set<String> RESTART_KEYS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "proxyBaseCore","proxyBaseMode","proxyBaseIpv6","proxyAppScope","proxyDnsHijack",
            "proxyBaseAutoOverwrite","proxyTcp","proxyUdp","proxyQuicBlocked","proxyCnIpDirect",
            "proxyAdblockChain","proxySharedNetwork","proxyKillSwitch","proxyAppPackages",
            "proxyDirectGids","proxyBypassCidrs","proxyBypassInterfaces","proxySharedBypassMacs"
    )));

    static void markDirty(SharedPreferences prefs, String key) {
        if (prefs != null && RESTART_KEYS.contains(key)) {
            prefs.edit().putBoolean(DIRTY_KEY, true).apply();
        }
    }

    static void clearDirty(SharedPreferences prefs) {
        if (prefs != null) prefs.edit().remove(DIRTY_KEY).apply();
    }

    static boolean pending(boolean running, SharedPreferences prefs) {
        if (!running || prefs == null) return false;
        return prefs.getBoolean(DIRTY_KEY, false) ||
                prefs.getBoolean("proxyRootRuntimeRefreshPending", false);
    }

    static String signature(SharedPreferences prefs) {
        return signature(ProxyRuntimeProfile.load(prefs), prefs.getAll());
    }

    static String signature(ProxyRuntimeProfile p, Map<String, ?> values) {
        StringBuilder state=new StringBuilder("runtime-settings-v1");
        for(String value:new String[]{p.core.id,p.mode.id,p.ipv6.id,p.appScope.id,p.dnsHijack.id,
                String.valueOf(p.tcp),String.valueOf(p.udp),String.valueOf(p.quicBlocked),
                String.valueOf(p.cnIpDirect),String.valueOf(p.adblockChain)}) append(state,value);
        for(String key:new String[]{"proxySharedNetwork","proxyKillSwitch"})
            append(state,String.valueOf(Boolean.TRUE.equals(values.get(key))));
        for(String key:new String[]{"proxyAppPackages","proxyDirectGids","proxyBypassCidrs","proxyBypassInterfaces","proxySharedBypassMacs"}) {
            append(state,key);
            TreeSet<String> sorted=new TreeSet<>();
            Object raw=values.get(key);
            if(raw instanceof Set<?>)for(Object item:(Set<?>)raw)if(item instanceof String)sorted.add((String)item);
            append(state,String.valueOf(sorted.size()));
            for(String item:sorted)append(state,item);
        }
        try {
            byte[] digest=MessageDigest.getInstance("SHA-256").digest(state.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder out=new StringBuilder();
            for(byte b:digest)out.append(String.format(Locale.ROOT,"%02x",b&255));
            return out.toString();
        } catch(Exception impossible) { throw new IllegalStateException(impossible); }
    }

    private static void append(StringBuilder text,String value) { text.append('|').append(value.length()).append(':').append(value); }

    static boolean pending(boolean running,String desired,String applied) {
        return running&&(applied==null||applied.isEmpty()||!applied.equals(desired));
    }

    static String ipv6Label(String id) {
        if("enable".equals(id))return "启用 IPv6";
        if("bypass".equals(id))return "IPv6 不进核心";
        if("strict".equals(id))return "严格 IPv4 防泄漏";
        if("disable".equals(id))return "禁用本机 IPv6";
        return "等待状态确认";
    }
}
