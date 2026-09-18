package io.github.xgl34222220.hetu;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Presets change subscriptions only. User exceptions and the hosts pause state survive. */
public final class RuleProfiles {
    private RuleProfiles() {}

    public static Map<String, Boolean> flags(String id) {
        final boolean adaway;
        final boolean hagezi;
        final boolean tracking;
        switch (id) {
            // Lightweight keeps the China ad list only for maximum compatibility.
            case "lite":
                adaway = false;
                hagezi = false;
                tracking = false;
                break;
            // Daily default: HaGeZi Normal is the main DNS filter; the China list
            // complements it for local ad domains. Avoid stacking redundant main lists.
            case "balanced":
                adaway = false;
                hagezi = true;
                tracking = false;
                break;
            // Enhanced adds the local privacy/tracking source on top of the same main list.
            case "enhanced":
                adaway = false;
                hagezi = true;
                tracking = true;
                break;
            default:
                throw new IllegalArgumentException("未知保护档位");
        }
        LinkedHashMap<String, Boolean> result = new LinkedHashMap<>();
        result.put("adaway", adaway);
        result.put("china", true);
        result.put("tracking", tracking);
        result.put("hagezi", hagezi);
        return Collections.unmodifiableMap(result);
    }

    public static String identify(Map<String, Boolean> current) {
        for (String id : new String[]{"lite", "balanced", "enhanced"})
            if (flags(id).equals(current)) return id;

        // Keep the old two-source default recognizable instead of silently relabeling it.
        if (Boolean.TRUE.equals(current.get("adaway"))
                && Boolean.TRUE.equals(current.get("china"))
                && !Boolean.TRUE.equals(current.get("tracking"))
                && !Boolean.TRUE.equals(current.get("hagezi"))) {
            return "legacy-balanced";
        }
        return "custom";
    }

    public static String title(String id) {
        switch (id) {
            case "lite": return "轻量";
            case "balanced": return "均衡";
            case "enhanced": return "加强";
            case "legacy-balanced": return "旧均衡";
            default: return "自定义";
        }
    }
}
