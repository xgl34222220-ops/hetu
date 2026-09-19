package io.github.xgl34222220.hetu;

import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.LongSupplier;

/** Nonblocking, single-flight cache whose results belong to one network/runtime generation. */
final class ProxyAsyncValue<T> {
    interface Fetch<T> { T run() throws Exception; }
    private final Executor executor;
    private final LongSupplier clock;
    private final Fetch<T> fetch;
    private final T unknown;
    private final long lifetime;
    private final long retryDelay;
    private String key;
    private long generation;
    private long nextAttempt;
    private boolean inFlight;
    private T value;

    ProxyAsyncValue(Executor executor, LongSupplier clock, Fetch<T> fetch, T unknown,
                    long lifetime, long retryDelay) {
        this.executor = executor;
        this.clock = clock;
        this.fetch = fetch;
        this.unknown = unknown;
        this.value = unknown;
        this.lifetime = lifetime;
        this.retryDelay = retryDelay;
    }

    synchronized T get(String currentKey, boolean online) {
        if (!Objects.equals(key, currentKey)) {
            key = currentKey;
            generation++;
            value = unknown;
            nextAttempt = 0L;
        }
        long now = clock.getAsLong();
        if (!online || inFlight || now < nextAttempt) return value;
        inFlight = true;
        long ticket = generation;
        try {
            executor.execute(() -> refresh(ticket));
        } catch (RejectedExecutionException stopped) {
            inFlight = false;
            nextAttempt = now + retryDelay;
        }
        return value;
    }

    private void refresh(long ticket) {
        T fresh = null;
        try { fresh = fetch.run(); } catch (Exception unavailable) { }
        synchronized (this) {
            inFlight = false;
            if (ticket != generation) return;
            if (fresh != null) value = fresh;
            nextAttempt = clock.getAsLong() + (fresh == null ? retryDelay : lifetime);
        }
    }
}
