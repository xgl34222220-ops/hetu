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
    private boolean hasValue;
    private long succeededAt;
    private String failure = "";

    static final class View<T> {
        final T value;
        final String state;
        final long succeededAt;
        final String failure;
        View(T value, String state, long succeededAt, String failure) {
            this.value=value; this.state=state; this.succeededAt=succeededAt; this.failure=failure;
        }
    }
    synchronized View<T> view() {
        String state=hasValue ? ((inFlight || !failure.isEmpty() || clock.getAsLong()-succeededAt>=lifetime) ? "stale" : "success")
                : (inFlight ? "loading" : !failure.isEmpty() ? "failed" : "idle");
        return new View<>(value,state,succeededAt,failure);
    }

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
            hasValue=false; succeededAt=0L; failure="";
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
            failure="检测任务暂不可用";
            nextAttempt = now + retryDelay;
        }
        return value;
    }

    private void refresh(long ticket) {
        T fresh = null;
        String problem="";
        try { fresh = fetch.run(); } catch (Exception unavailable) { problem=unavailable.getClass().getSimpleName(); }
        synchronized (this) {
            inFlight = false;
            if (ticket != generation) return;
            if (fresh != null) { value = fresh; hasValue=true; succeededAt=clock.getAsLong(); failure=""; }
            else failure=problem.isEmpty()?"检测未返回结果":problem;
            nextAttempt = clock.getAsLong() + (fresh == null ? retryDelay : lifetime);
        }
    }
}
