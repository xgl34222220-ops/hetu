package io.github.xgl34222220.hetu;

import java.time.Instant;

/** Decisions shared by the runtime guard and its host regression tests. */
final class ProxyContinuity {
    enum ProcessState { ALIVE, DEAD, UNKNOWN }

    static String coreProbeCommand(String pidFile, String corePath) {
        String pid = shellQuote(pidFile);
        String core = shellQuote(corePath);
        String replacedCore = shellQuote(corePath + " (deleted)");
        return "if P=$(cat " + pid + " 2>/dev/null); then "
                + "case \"$P\" in ''|*[!0-9]*) printf '?';; 0) printf 0;; *) "
                + "if kill -0 \"$P\" >/dev/null 2>&1; then "
                + "if EXE=$(readlink \"/proc/$P/exe\" 2>/dev/null); then "
                + "case \"$EXE\" in " + core + "|" + replacedCore + ") printf 1;; '') printf '?';; *) printf 0;; esac; "
                + "elif [ -d \"/proc/$P\" ]; then printf '?'; else printf 0; fi; "
                + "elif [ -d \"/proc/$P\" ]; then printf '?'; else printf 0; fi;; esac; "
                + "elif [ -e " + pid + " ]; then printf '?'; else printf 0; fi";
    }

    private static String shellQuote(String value) {
        if (value == null || value.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("Invalid process probe path");
        }
        return "'" + value.replace("'", "'\\''") + "'";
    }

    static ProcessState processState(boolean probeSucceeded, String output) {
        if (!probeSucceeded || output == null) return ProcessState.UNKNOWN;
        String value = output.trim();
        if ("1".equals(value)) return ProcessState.ALIVE;
        if ("0".equals(value)) return ProcessState.DEAD;
        return ProcessState.UNKNOWN;
    }

    static boolean preserveRunning(ProcessState state, boolean lastKnownRunning) {
        return state == ProcessState.ALIVE || (state == ProcessState.UNKNOWN && lastKnownRunning);
    }

    static boolean startedBeforeNetworkChange(String start, long changedAtMillis) {
        if (start == null || start.isEmpty() || changedAtMillis <= 0) return false;
        try {
            Instant value = Instant.parse(start);
            return value.isAfter(Instant.EPOCH) && value.isBefore(Instant.ofEpochMilli(changedAtMillis));
        } catch (RuntimeException invalidTime) {
            // Unknown age is not evidence of a stale connection. Never fall back to closeAll.
            return false;
        }
    }
}
