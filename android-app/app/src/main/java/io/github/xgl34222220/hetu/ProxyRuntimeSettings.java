package io.github.xgl34222220.hetu;

import android.content.SharedPreferences;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Compares saved network settings with the snapshot of a completed transaction. */
final class ProxyRuntimeSettings {
    static final String DIRTY_KEY = "proxyRootSettingsDirty";
    // Bump only when deployed Root scripts/core behavior changes, never for UI-only APKs.
    static final int RUNTIME_REVISION = 151;
    static final String APPLIED_RUNTIME_REVISION_KEY = "proxyRootAppliedRuntimeRevision";
    private static final Set<String> RESTART_KEYS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "proxyBaseCore","proxyBaseMode","proxyBaseIpv6","proxyAppScope","proxyDnsHijack",
            "proxyBaseAutoOverwrite","proxyTcp","proxyUdp","proxyQuicBlocked","proxyCnIpDirect",
            "proxyAdblockChain","proxySharedNetwork","proxyKillSwitch","proxyAppPackages",
            "proxyDirectGids","proxyBypassCidrs","proxyBypassInterfaces","proxySharedBypassMacs",
            "proxyMihomoDnsForward","proxyDnsHijackTcp","proxyDnsHijackUdp","proxyPerformanceMode",
            "proxyCpuAffinityEnabled","proxyCpuAffinity","proxyMemoryLimitEnabled","proxyMemoryLimit",
            "proxyIoWeightEnabled","proxyIoWeight","proxyVendorFirewallCleanup"
    )));

    static final String APPLIED_SETTINGS_KEY = "proxyRootAppliedSettings";
    static final String APPLY_ERROR_KEY = "proxyRootSettingsApplyError";
    // SharedPreferences is application-scoped and does not retain an Activity.
    // The existing settings composable calls pending before rendering this notice.
    private static volatile SharedPreferences noticePreferences;

    static void markDirty(SharedPreferences prefs, String key) {
        if (prefs != null && RESTART_KEYS.contains(key)) {
            String applied = prefs.getString(APPLIED_SETTINGS_KEY, "");
            boolean changed = applied == null || applied.isEmpty() || !applied.equals(signature(prefs));
            prefs.edit().putBoolean(DIRTY_KEY, changed).remove(APPLY_ERROR_KEY).apply();
        }
    }

    static void clearDirty(SharedPreferences prefs) {
        if (prefs != null) prefs.edit().remove(DIRTY_KEY).apply();
    }

    static boolean pending(boolean running, SharedPreferences prefs) {
        noticePreferences = prefs;
        if (!running || prefs == null) return false;
        String applied = prefs.getString(APPLIED_SETTINGS_KEY, "");
        // A real applied snapshot is authoritative. A leftover boolean cannot
        // require another restart when the settings already match that snapshot.
        return applied == null || applied.isEmpty() ? prefs.getBoolean(DIRTY_KEY, false)
                : !applied.equals(signature(prefs));
    }

    static boolean runtimeUpgradePending(boolean running, SharedPreferences prefs) { return false; }
    static boolean runtimeUpgradePending(boolean running, int revision, boolean recordedPending) { return false; }

    static boolean acknowledgeApplied(SharedPreferences prefs, boolean completedReplacement) {
        if (prefs == null || !completedReplacement) return false;
        String applied = prefs.getString(APPLIED_SETTINGS_KEY, "");
        if (applied == null || applied.isEmpty()) return false;
        // RootProxyManager records the captured request after success. Never
        // overwrite it with settings the user changed while restart was running.
        return prefs.edit().putInt(APPLIED_RUNTIME_REVISION_KEY, RUNTIME_REVISION)
                .putBoolean(DIRTY_KEY, !applied.equals(signature(prefs)))
                .remove("proxyRootRuntimeRefreshPending").remove("proxyRootRuntimeRefreshPendingAt")
                .remove("proxyRootUpgradeError").remove(APPLY_ERROR_KEY).commit();
    }

    static void beginApply(SharedPreferences prefs) {
        noticePreferences = prefs;
        if (prefs != null) prefs.edit().remove(APPLY_ERROR_KEY).apply();
    }

    static void recordFailure(SharedPreferences prefs, Exception error) {
        noticePreferences = prefs;
        if (prefs == null) return;
        String message = error == null ? "未返回错误原因" : error.getMessage();
        if (message == null || message.trim().isEmpty()) message = error == null ? "未知错误" : error.getClass().getSimpleName();
        prefs.edit().putString(APPLY_ERROR_KEY, message).apply();
    }

    static String applyDescription() {
        SharedPreferences prefs = noticePreferences;
        if (prefs == null) return "应用后会短暂重连代理。";
        String error = prefs.getString(APPLY_ERROR_KEY, "");
        if (error != null && !error.isEmpty()) {
            // Previous versions stored mixed stderr and JSON as one giant error.
            if (error.contains("/proc/") && error.contains("\"ok\":true"))
                return "上次控制响应混入进程扫描警告。应用一次以同步状态。";
            String readable = error.replace('\n', ' ').replace('\r', ' ').trim();
            return "应用失败：" + (readable.length() > 140 ? readable.substring(0, 140) + "…" : readable);
        }
        String fallback = prefs.getString("proxyAdblockLastError", "");
        if (fallback != null && !fallback.isEmpty() && prefs.getBoolean("proxyAdblockChain", true))
            return "广告串联尚未生效：" + fallback;
        return "应用后会短暂重连代理。";
    }

    static String signature(SharedPreferences prefs) {
        return signature(ProxyRuntimeProfile.load(prefs), prefs.getAll());
    }

    static String captureRequest(ProxyRuntimeProfile profile, SharedPreferences prefs) {
        return signature(profile, prefs.getAll());
    }

    static String signature(ProxyRuntimeProfile p, Map<String, ?> values) {
        StringBuilder state=new StringBuilder("runtime-settings-v1");
        for(String value:new String[]{p.core.id,p.mode.id,p.ipv6.id,p.appScope.id,p.dnsHijack.id,
                String.valueOf(p.tcp),String.valueOf(p.udp),String.valueOf(p.quicBlocked),
                String.valueOf(p.cnIpDirect),String.valueOf(p.adblockChain)}) append(state,value);
        for(String key:new String[]{"proxySharedNetwork","proxyKillSwitch"})
            append(state,String.valueOf(Boolean.TRUE.equals(values.get(key))));
        for(String key:new String[]{"proxyMihomoDnsForward","proxyDnsHijackTcp","proxyDnsHijackUdp"})
            append(state,String.valueOf(!Boolean.FALSE.equals(values.get(key))));
        for(String key:new String[]{"proxyPerformanceMode","proxyCpuAffinityEnabled","proxyMemoryLimitEnabled","proxyIoWeightEnabled","proxyVendorFirewallCleanup"})
            append(state,String.valueOf(Boolean.TRUE.equals(values.get(key))));
        for(String key:new String[]{"proxyCpuAffinity","proxyMemoryLimit","proxyIoWeight"})
            append(state,String.valueOf(values.get(key)==null?"":values.get(key)));
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
