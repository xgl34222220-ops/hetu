package io.github.xgl34222220.hetu;

import java.util.Locale;

/**
 * 资源限制 → cgroup 硬限制 (default off). The existing memory limit (GOMEMLIMIT, a soft Go GC target)
 * and I/O priority (ionice) stay as they are; when this switch is on, the same values are also enforced
 * by the kernel: memcg {@code memory.max} and an I/O weight, cgroup v2 first with a v1 fallback
 * ({@code memory.limit_in_bytes} / {@code blkio.weight}). hetu-root.sh reports what applied in
 * tuning-status and core.log; a partial failure only warns. Pure JVM.
 */
final class CoreResourceLimits {
    private CoreResourceLimits() {}

    static final String PREF_ENABLED = "proxyCgroupLimitEnabled";
    static final String PREFIX = "cg:";

    /** The start argument: VALUE unchanged when off or empty, "cg:VALUE" when the cgroup switch is on. */
    static String argument(String value, boolean cgroup) {
        String v = value == null ? "" : value.trim();
        if (v.isEmpty() || !cgroup) return v;
        return v.startsWith(PREFIX) ? v : PREFIX + v;
    }

    /** cgroup v2 io.weight for an ionice best-effort level 0-7 (0 = highest): 200 … 25, default 100 at level 4. */
    static int ioWeightV2(int level) { return (8 - clamp(level)) * 25; }

    /** cgroup v1 blkio.weight for the same level: 1000 … 125, default 500 at level 4. */
    static int blkioWeightV1(int level) { return (8 - clamp(level)) * 125; }

    private static int clamp(int level) { return Math.max(0, Math.min(7, level)); }

    /** Readable summary of a tuning-status line, e.g. "memcg=v2:268435456 iocg=failed". */
    static String describe(String tuningStatus) {
        if (tuningStatus == null || tuningStatus.trim().isEmpty() || "none".equals(tuningStatus.trim())) return "";
        StringBuilder out = new StringBuilder();
        for (String part : tuningStatus.trim().split("\\s+")) {
            String text = null;
            if (part.startsWith("memcg=v2:") || part.startsWith("memcg=v1:")) {
                text = "内存硬限制（cgroup " + part.substring(6, 8) + "）" + mib(part.substring(9));
            } else if (part.equals("memcg=failed")) {
                text = "内存硬限制未生效（设备不支持 memcg）";
            } else if (part.startsWith("iocg=v2:") || part.startsWith("iocg=v1:")) {
                text = "I/O 权重（cgroup " + part.substring(5, 7) + "）" + part.substring(8);
            } else if (part.equals("iocg=failed")) {
                text = "I/O 权重未生效（设备不支持 io/blkio 控制器）";
            }
            if (text != null) out.append(out.length() == 0 ? "" : "；").append(text);
        }
        return out.toString();
    }

    private static String mib(String bytes) {
        try {
            return String.format(Locale.ROOT, " %d MiB", Long.parseLong(bytes) / (1024L * 1024L));
        } catch (NumberFormatException invalid) {
            return " " + bytes;
        }
    }
}
