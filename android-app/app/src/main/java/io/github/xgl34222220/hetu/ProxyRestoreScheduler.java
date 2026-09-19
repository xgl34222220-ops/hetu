package io.github.xgl34222220.hetu;

/** Coalesces boot/network requests while allowing an attempt to schedule its own retry. */
final class ProxyRestoreScheduler {
    interface Dispatcher { void dispatch(Runnable task, long delayMs); }
    interface Attempt { long run(); }

    private final Dispatcher dispatcher;
    private final Attempt attempt;
    private boolean pending;
    private boolean closed;

    ProxyRestoreScheduler(Dispatcher dispatcher, Attempt attempt) {
        this.dispatcher = dispatcher;
        this.attempt = attempt;
    }

    synchronized void request(long delayMs) {
        if (closed || pending) return;
        pending = true;
        try {
            dispatcher.dispatch(this::runAttempt, Math.max(0L, delayMs));
        } catch (RuntimeException error) {
            pending = false;
            throw error;
        }
    }

    private void runAttempt() {
        synchronized (this) {
            if (closed) { pending = false; return; }
        }
        long retryDelay = 0L;
        try {
            retryDelay = attempt.run();
        } finally {
            synchronized (this) { pending = false; }
            // Clear the in-flight state before requesting the next attempt.
            // Requests from callbacks during this attempt remain coalesced.
            if (retryDelay > 0L) request(retryDelay);
        }
    }

    synchronized void close() { closed = true; }
}
