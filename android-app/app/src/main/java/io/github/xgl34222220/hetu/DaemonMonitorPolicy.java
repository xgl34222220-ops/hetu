package io.github.xgl34222220.hetu;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.CRC32;

/**
 * Pure rules of the Root monitor daemon (no Android API, host-tested): the policy file the App
 * exports to {@code $BASE/policy/monitor}, the {@code monitor-status} reply, the daemon's App
 * hand-off (stop / switch config) and the traffic batches handed out by {@code traffic-take}.
 *
 * <p>The daemon (hetu-root.sh monitor) runs only while a Root session runs. It evaluates network
 * match itself (same OR semantics as {@link NetworkMatchRules}), runs network scripts itself, and
 * wakes the App service for the actions that change App-owned intent. It also polls the core's
 * Clash API and aggregates traffic per minute by app / host / destination IP / outbound / rule.
 */
final class DaemonMonitorPolicy {
    private DaemonMonitorPolicy() {}

    static final String POLICY_PATH = "/data/adb/hetu/policy/monitor";
    static final String ACTION_DAEMON = "io.github.xgl34222220.hetu.DAEMON_NETWORK_ACTION";
    static final int DEFAULT_INTERVAL_SECONDS = 5;
    static final int MAX_LIST = 64;
    static final long HANDOFF_MAX_AGE_MS = 60_000L;
    static final String[] DIMENSIONS = {"app", "host", "ip", "outbound", "rule"};

    // ------------------------------------------------------------------ policy body
    static final class Inputs {
        boolean traffic, netmatch, mobile;
        int intervalSeconds = DEFAULT_INTERVAL_SECONDS, apiPort;
        String apiSecret = "", matchAction = "start", unmatchAction = "none", mode = "", config = "";
        Collection<String> ssids = Collections.emptyList(), bssids = Collections.emptyList(),
                slots = Collections.emptyList(), mccMncs = Collections.emptyList();
    }

    /**
     * Line-oriented body ("KEY=value", list keys repeated). Values carrying control characters are
     * dropped, never escaped, so a crafted SSID or secret cannot add a line. A secret that cannot be
     * written disables traffic instead of polling unauthenticated. GEN identifies the content.
     */
    static String body(Inputs in) {
        StringBuilder lines = new StringBuilder();
        boolean traffic = in.traffic && in.apiPort >= 1 && in.apiPort <= 65535;
        String secret = in.apiSecret == null ? "" : in.apiSecret;
        if (!lineSafe(secret) || secret.length() > 256) traffic = false;
        int interval = Math.max(3, Math.min(60, in.intervalSeconds));
        append(lines, "VERSION", "1");
        append(lines, "TRAFFIC", traffic ? "1" : "0");
        append(lines, "TRAFFIC_INTERVAL", Integer.toString(interval));
        append(lines, "API_PORT", traffic ? Integer.toString(in.apiPort) : "0");
        append(lines, "API_SECRET", traffic ? secret : "");
        append(lines, "NETMATCH", in.netmatch ? "1" : "0");
        append(lines, "MOBILE", in.mobile ? "1" : "0");
        append(lines, "MATCH_ACTION", NetworkMatchRules.parseAction(in.matchAction == null || in.matchAction.isEmpty() ? "start" : in.matchAction).encode());
        append(lines, "UNMATCH_ACTION", NetworkMatchRules.parseAction(in.unmatchAction == null || in.unmatchAction.isEmpty() ? "none" : in.unmatchAction).encode());
        append(lines, "MODE", lineSafe(in.mode) ? clip(in.mode, 40) : "");
        append(lines, "CONFIG", lineSafe(in.config) ? clip(in.config, 160) : "");
        for (String v : listValues(in.ssids, false)) append(lines, "SSID", v);
        for (String v : listValues(in.bssids, true)) append(lines, "BSSID", v);
        for (String v : NetworkMatchRules.sanitizeSlots(in.slots)) append(lines, "SLOT", v);
        for (String v : NetworkMatchRules.sanitizeMccMncs(in.mccMncs)) append(lines, "MCCMNC", v);
        CRC32 crc = new CRC32();
        crc.update(lines.toString().getBytes(StandardCharsets.UTF_8));
        return "GEN=" + String.format(Locale.ROOT, "%08x", crc.getValue()) + "\n" + lines;
    }

    static boolean wanted(String body) {
        return body.contains("\nTRAFFIC=1\n") || body.contains("\nNETMATCH=1\n");
    }

    static String generation(String body) {
        if (body == null || !body.startsWith("GEN=")) return "";
        int end = body.indexOf('\n');
        return end < 0 ? body.substring(4) : body.substring(4, end);
    }

    /** Root command that installs the body atomically (0600 root) or removes the policy when unused. */
    static String installCommand(String body) {
        String path = POLICY_PATH, tmp = POLICY_PATH + ".new";
        if (body == null || !wanted(body)) return "rm -f " + NetworkMatchRules.q(path);
        return "mkdir -p /data/adb/hetu/policy && chmod 700 /data/adb/hetu/policy && printf %s " + NetworkMatchRules.q(body)
                + " > " + NetworkMatchRules.q(tmp) + " && chmod 600 " + NetworkMatchRules.q(tmp)
                + " && chown 0:0 " + NetworkMatchRules.q(tmp) + " && mv -f " + NetworkMatchRules.q(tmp) + " " + NetworkMatchRules.q(path);
    }

    private static List<String> listValues(Collection<String> values, boolean lower) {
        TreeSet<String> out = new TreeSet<>();
        if (values != null) for (String v : values) {
            if (v == null) continue;
            String t = v.trim();
            if (t.isEmpty() || !lineSafe(t) || t.length() > 64) continue;
            out.add(lower ? t.toLowerCase(Locale.ROOT) : t);
            if (out.size() >= MAX_LIST) break;
        }
        return new ArrayList<>(out);
    }

    static boolean lineSafe(String value) {
        if (value == null) return true;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < 32 || c == 127) return false;
        }
        return true;
    }

    private static String clip(String value, int max) { return value == null ? "" : value.length() > max ? value.substring(0, max) : value; }
    private static void append(StringBuilder out, String key, String value) { out.append(key).append('=').append(value).append('\n'); }

    // ------------------------------------------------------------------ monitor-status
    static final class Status {
        final boolean alive, netmatch, traffic;
        final String policy, lastAction;
        Status(boolean alive, boolean netmatch, boolean traffic, String policy, String lastAction) {
            this.alive = alive; this.netmatch = netmatch; this.traffic = traffic; this.policy = policy; this.lastAction = lastAction;
        }
        /** The daemon owns network-match actions only while it is alive with network match enabled. */
        boolean ownsNetworkMatch() { return alive && netmatch; }
    }

    static final Status DOWN = new Status(false, false, false, "", "");

    /** Last JSON line of monitor-status; anything unreadable means "not alive" (the App acts). */
    static Status parseStatus(String output) {
        if (output == null) return DOWN;
        String line = "";
        for (String l : output.split("\\r?\\n")) if (l.trim().startsWith("{")) line = l.trim();
        if (!line.contains("\"ok\":true")) return DOWN;
        return new Status(line.contains("\"alive\":true"), line.contains("\"netmatch\":true"), line.contains("\"traffic\":true"),
                jsonString(line, "policy"), jsonString(line, "lastAction"));
    }

    private static String jsonString(String line, String key) {
        Matcher m = Pattern.compile("\"" + key + "\":\"([^\"]*)\"").matcher(line);
        return m.find() ? m.group(1) : "";
    }

    // ------------------------------------------------------------------ hand-off
    static final class Handoff {
        final String kind, target, label, id;
        Handoff(String kind, String target, String label, String id) { this.kind = kind; this.target = target; this.label = label; this.id = id; }
    }

    private static final Pattern HANDOFF_ID = Pattern.compile("[0-9]{1,10}-[0-9]{1,20}-[0-9]{1,9}");

    /**
     * Validates a daemon hand-off (only Root/system can start the non-exported service). Unknown
     * kinds, malformed ids, invalid config names and stale requests are refused.
     */
    static Handoff parseHandoff(String kind, String target, String label, String id, long atMs, long nowMs) {
        if (id == null || !HANDOFF_ID.matcher(id).matches()) return null;
        if (atMs <= 0 || nowMs - atMs > HANDOFF_MAX_AGE_MS || atMs - nowMs > HANDOFF_MAX_AGE_MS) return null;
        String safeLabel = label == null || !lineSafe(label) ? "" : clip(label, 160);
        if ("stop".equals(kind)) return new Handoff("stop", "", safeLabel, id);
        if ("switch".equals(kind) && NetworkMatchRules.validConfigName(target)) return new Handoff("switch", target, safeLabel, id);
        return null;
    }

    // ------------------------------------------------------------------ traffic batches
    static final class Row {
        final long minute; final String dimension, key; final long upload, download; final int connections;
        Row(long minute, String dimension, String key, long upload, long download, int connections) {
            this.minute = minute; this.dimension = dimension; this.key = key; this.upload = upload; this.download = download; this.connections = connections;
        }
    }

    static final class Batch {
        final String id;
        final List<Row> rows = new ArrayList<>();
        /** minute (epoch seconds, minute aligned) -> {upload, download} from the controller counters. */
        final Map<Long, long[]> totals = new LinkedHashMap<>();
        int rejected;
        Batch(String id) { this.id = id; }
    }

    private static final Pattern BATCH_ID = Pattern.compile("[0-9]{1,20}-[0-9]{1,10}");
    private static final Set<String> DIMENSION_SET = new TreeSet<>(java.util.Arrays.asList(DIMENSIONS));

    /**
     * Parses one traffic-take batch. Rows of a minute follow its "M" line; malformed lines are
     * counted and skipped, keys are clipped to 200 characters, negative or absurd counters dropped.
     * Returns null for an empty or foreign reply.
     */
    static Batch parseBatch(String text) {
        if (text == null) return null;
        String[] lines = text.split("\\r?\\n");
        if (lines.length == 0) return null;
        String[] head = lines[0].split("\t", -1);
        if (head.length != 3 || !"HETU-TRAFFIC".equals(head[0]) || !"1".equals(head[1]) || !BATCH_ID.matcher(head[2]).matches()) return null;
        Batch batch = new Batch(head[2]);
        long minute = -1;
        for (int i = 1; i < lines.length; i++) {
            String[] f = lines[i].split("\t", -1);
            try {
                if (f.length == 2 && "M".equals(f[0])) {
                    long m = Long.parseLong(f[1]);
                    minute = m > 0 && m % 60 == 0 ? m : -1;
                    if (minute < 0) batch.rejected++;
                } else if (f.length == 3 && "T".equals(f[0]) && minute > 0) {
                    long up = counter(f[1]), down = counter(f[2]);
                    long[] t = batch.totals.get(minute);
                    if (t == null) batch.totals.put(minute, new long[]{up, down}); else { t[0] += up; t[1] += down; }
                } else if (f.length == 6 && "B".equals(f[0]) && minute > 0 && DIMENSION_SET.contains(f[1])) {
                    String key = f[2].isEmpty() ? "-" : f[2].length() > 200 ? f[2].substring(0, 200) : f[2];
                    batch.rows.add(new Row(minute, f[1], key, counter(f[3]), counter(f[4]), (int) Math.min(Integer.MAX_VALUE, counter(f[5]))));
                } else if (!lines[i].isEmpty()) batch.rejected++;
            } catch (NumberFormatException bad) { batch.rejected++; }
        }
        return batch;
    }

    private static long counter(String raw) {
        long v = Long.parseLong(raw);
        if (v < 0 || v > (1L << 50)) throw new NumberFormatException("counter");
        return v;
    }
}
