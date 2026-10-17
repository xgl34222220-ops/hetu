package io.github.xgl34222220.hetu;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Pure rules of network-match automation (no Android API, host-tested): conditions, the action
 * encoding, the Root command of the "run script" action and the debounce/loop limiter.
 *
 * Conditions: the historical Wi-Fi rule (SSID set AND BSSID set, empty = any) OR "any cellular",
 * plus SIM slot ("the default data SIM sits in slot N and the device is on cellular") and MCC+MNC
 * ("on cellular and the default data SIM operator or the registered network operator is one of
 * them"). The SIM facts come from permission-free APIs (SubscriptionManager default data
 * subscription, TelephonyManager#getSimOperator/#getNetworkOperator) or Root getprop
 * gsm.sim.operator.numeric / gsm.operator.numeric; READ_PHONE_STATE is never needed.
 *
 * Actions: start | stop | none | switch_config:&lt;config name&gt; | run_script:&lt;managed script&gt;.
 */
final class NetworkMatchRules {
    private NetworkMatchRules() {}

    static final String SCRIPTS_ROOT = "/data/adb/hetu/scripts";
    static final String SCRIPTS_LOG = "/data/adb/hetu/run/scripts.log";
    static final int SCRIPT_TIMEOUT_SECONDS = 12;
    static final int MAX_MCC_MNC = 32;
    private static final Pattern MCC_MNC = Pattern.compile("[0-9]{5,6}");
    private static final Pattern SCRIPT_NAME = Pattern.compile("[A-Za-z0-9._-]{1,75}");

    // ------------------------------------------------------------------ actions
    static final class Action {
        final String kind;   // start | stop | none | switch | script
        final String target; // config or script name for switch/script, else ""
        Action(String kind, String target) { this.kind = kind; this.target = target; }
        boolean background() { return "switch".equals(kind) || "script".equals(kind); }
        String encode() {
            if ("switch".equals(kind)) return "switch_config:" + target;
            if ("script".equals(kind)) return "run_script:" + target;
            return kind;
        }
    }

    /** Unknown or malformed values decay to "none" so a bad preference never acts. */
    static Action parseAction(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.equals("start") || value.equals("stop") || value.equals("none")) return new Action(value, "");
        if (value.startsWith("switch_config:")) {
            String name = value.substring("switch_config:".length());
            return validConfigName(name) ? new Action("switch", name) : new Action("none", "");
        }
        if (value.startsWith("run_script:")) {
            String name = value.substring("run_script:".length());
            return validScriptName(name) ? new Action("script", name) : new Action("none", "");
        }
        return new Action("none", "");
    }

    static boolean validConfigName(String name) {
        if (name == null) return false;
        String n = name.trim();
        return n.equals(name) && n.length() >= 3 && n.length() <= 120 && n.indexOf('/') < 0 && n.indexOf('\\') < 0
                && n.indexOf('\u0000') < 0 && n.indexOf('\n') < 0 && n.indexOf('\r') < 0 && !n.equals(".") && !n.equals("..");
    }

    static boolean validScriptName(String name) {
        return name != null && SCRIPT_NAME.matcher(name).matches() && name.toLowerCase(Locale.ROOT).endsWith(".sh")
                && !name.equals("pre-start.sh") && !name.equals("post-stop.sh") && !name.equals(".sh") && !name.startsWith(".");
    }

    // ------------------------------------------------------------------ conditions
    static final class Observation {
        final boolean wifi, cellular;
        final String ssid, bssid;
        /** 1-based slot of the default data SIM, or -1 when unknown. */
        final int dataSlot;
        final String simOperator, networkOperator;
        Observation(boolean wifi, boolean cellular, String ssid, String bssid, int dataSlot, String simOperator, String networkOperator) {
            this.wifi = wifi; this.cellular = cellular; this.ssid = ssid == null ? "" : ssid; this.bssid = bssid == null ? "" : bssid;
            this.dataSlot = dataSlot; this.simOperator = normalizeMccMnc(simOperator); this.networkOperator = normalizeMccMnc(networkOperator);
        }
    }

    static final class Conditions {
        final Set<String> ssids, bssids, slots, mccMncs;
        final boolean mobile;
        Conditions(Collection<String> ssids, Collection<String> bssids, boolean mobile, Collection<String> slots, Collection<String> mccMncs) {
            this.ssids = copy(ssids); this.bssids = copy(bssids); this.mobile = mobile;
            this.slots = sanitizeSlots(slots); this.mccMncs = sanitizeMccMncs(mccMncs);
        }
        boolean needsOperator() { return !mccMncs.isEmpty(); }
        boolean needsSlot() { return !slots.isEmpty(); }
    }

    static boolean matched(Observation o, Conditions c) {
        boolean wifi = o.wifi && member(o.ssid, c.ssids, false) && member(o.bssid, c.bssids, true);
        boolean anyCellular = o.cellular && c.mobile;
        boolean slot = o.cellular && o.dataSlot >= 1 && c.slots.contains(Integer.toString(o.dataSlot));
        boolean operator = o.cellular && !c.mccMncs.isEmpty()
                && ((!o.simOperator.isEmpty() && c.mccMncs.contains(o.simOperator))
                || (!o.networkOperator.isEmpty() && c.mccMncs.contains(o.networkOperator)));
        return wifi || anyCellular || slot || operator;
    }

    static String label(Observation o) {
        if (o.wifi) return "Wi‑Fi" + (o.ssid.isEmpty() ? "" : " · " + o.ssid) + (o.bssid.isEmpty() ? "" : " · " + o.bssid);
        if (!o.cellular) return "其他/离线";
        StringBuilder out = new StringBuilder("移动数据");
        if (o.dataSlot >= 1) out.append(" · 卡").append(o.dataSlot);
        String op = !o.simOperator.isEmpty() ? o.simOperator : o.networkOperator;
        if (!op.isEmpty()) out.append(" · ").append(op);
        if (!o.networkOperator.isEmpty() && !o.simOperator.isEmpty() && !o.networkOperator.equals(o.simOperator))
            out.append(" · 漫游 ").append(o.networkOperator);
        return out.toString();
    }

    private static boolean member(String value, Set<String> set, boolean ignoreCase) {
        if (set.isEmpty()) return true;
        for (String x : set) if (ignoreCase ? x.equalsIgnoreCase(value) : x.equals(value)) return true;
        return false;
    }

    private static Set<String> copy(Collection<String> values) {
        if (values == null || values.isEmpty()) return Collections.emptySet();
        TreeSet<String> out = new TreeSet<>();
        for (String v : values) if (v != null && !v.trim().isEmpty()) out.add(v.trim());
        return out;
    }

    static Set<String> sanitizeSlots(Collection<String> values) {
        TreeSet<String> out = new TreeSet<>();
        if (values != null) for (String v : values) if ("1".equals(v == null ? "" : v.trim()) || "2".equals(v == null ? "" : v.trim())) out.add(v.trim());
        return out;
    }

    static Set<String> sanitizeMccMncs(Collection<String> values) {
        TreeSet<String> out = new TreeSet<>();
        if (values != null) for (String v : values) {
            String n = normalizeMccMnc(v);
            if (!n.isEmpty() && out.size() < MAX_MCC_MNC) out.add(n);
        }
        return out;
    }

    /** "460-00", "460 00", "46000" -> "46000"; anything that is not 5-6 digits -> "". */
    static String normalizeMccMnc(String raw) {
        if (raw == null) return "";
        String digits = raw.trim().replace("-", "").replace(" ", "").replace("_", "");
        return MCC_MNC.matcher(digits).matches() ? digits : "";
    }

    /** One slot of a getprop list ("46000,46011"; empty fields for an empty slot). 1-based. */
    static String operatorForSlot(String propValue, int slot) {
        if (propValue == null || slot < 1) return "";
        String[] parts = propValue.trim().split(",", -1);
        return slot <= parts.length ? normalizeMccMnc(parts[slot - 1]) : "";
    }

    // ------------------------------------------------------------------ run script
    /**
     * Root command for a managed script under {@link #SCRIPTS_ROOT}: bounded by timeout(1), stdout/
     * stderr and the exit code appended to scripts.log, exit code preserved (124 = timeout, 44 =
     * missing). The script never runs inside a start/stop transaction lock.
     */
    static String scriptCommand(String name, String event, String network, String mode, String config) {
        if (!validScriptName(name)) throw new IllegalArgumentException("脚本文件名无效");
        if (!"network-match".equals(event) && !"network-unmatch".equals(event)) throw new IllegalArgumentException("未知事件");
        String path = SCRIPTS_ROOT + "/" + name;
        StringBuilder out = new StringBuilder();
        out.append("test -f ").append(q(path)).append(" || exit 44; ");
        out.append("export HETU_HOOK=network; ");
        out.append("export HETU_EVENT=").append(q(event)).append("; ");
        out.append("export HETU_NETWORK=").append(q(clean(network))).append("; ");
        out.append("export HETU_MODE=").append(q(clean(mode))).append("; ");
        out.append("export HETU_CONFIG=").append(q(clean(config))).append("; ");
        out.append("export HETU_BASE=/data/adb/hetu; export HETU_RUN_DIR=/data/adb/hetu/run; ");
        out.append("export HETU_SCRIPT_DIR=").append(q(SCRIPTS_ROOT)).append("; ");
        out.append("mkdir -p /data/adb/hetu/run; ");
        out.append("{ printf '\\n[%s] network-match event=%s script=%s network=%s\\n' \"$(date '+%Y-%m-%d %H:%M:%S')\" ")
                .append(q(event)).append(' ').append(q(name)).append(' ').append(q(clean(network))).append("; ");
        out.append("if command -v timeout >/dev/null 2>&1; then timeout ").append(SCRIPT_TIMEOUT_SECONDS).append(" sh ").append(q(path))
                .append("; else sh ").append(q(path)).append("; fi; HETU_RC=$?; ");
        out.append("printf '[network-match] exit=%s\\n' \"$HETU_RC\"; } >> ").append(q(SCRIPTS_LOG)).append(" 2>&1; ");
        out.append("exit $HETU_RC");
        return out.toString();
    }

    private static String clean(String value) {
        if (value == null) return "";
        StringBuilder out = new StringBuilder();
        for (char c : value.toCharArray()) if (c >= 32 && c != 127) out.append(c);
        return out.length() > 160 ? out.substring(0, 160) : out.toString();
    }

    static String q(String value) { return "'" + value.replace("'", "'\\''") + "'"; }

    // ------------------------------------------------------------------ debounce / loop limiter
    /**
     * Background actions (switch config, run script) wait for a stable network and are rate
     * limited: the same decision never re-runs inside {@code repeatMs}, and at most {@code burst}
     * background actions run inside {@code windowMs}. A script or restart that itself flips the
     * network therefore cannot drive an endless switch/script loop.
     */
    static final class Limiter {
        final long repeatMs, windowMs; final int burst;
        private final ArrayDeque<Long> recent = new ArrayDeque<>();
        private final Map<String, Long> last = new HashMap<>();
        Limiter(long repeatMs, long windowMs, int burst) { this.repeatMs = repeatMs; this.windowMs = windowMs; this.burst = burst; }
        synchronized boolean allow(String decision, long nowMs) {
            Long previous = last.get(decision);
            if (previous != null && nowMs - previous < repeatMs) return false;
            while (!recent.isEmpty() && nowMs - recent.peekFirst() >= windowMs) recent.pollFirst();
            if (recent.size() >= burst) return false;
            recent.addLast(nowMs); last.put(decision, nowMs);
            return true;
        }
    }

    static final long STABLE_DELAY_MS = 2500L;
}
