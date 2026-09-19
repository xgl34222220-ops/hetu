package io.github.xgl34222220.hetu;

import java.time.Instant;

/** Decisions shared by the runtime guard and its host regression tests. */
final class ProxyContinuity {
    enum ProcessState { ALIVE, DEAD, UNKNOWN }

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
