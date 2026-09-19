package io.github.xgl34222220.hetu;

/** Shared with runtime start/stop so an old log poll cannot overwrite a new session. */
final class ProxyAdblockSession {
    static final Object LOCK = new Object();

    static boolean canCommit(long generation, long currentGeneration, boolean armed,
                             long offset, long currentOffset) {
        return armed && generation == currentGeneration && offset == currentOffset;
    }
}
