package io.github.xgl34222220.hetu;

import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/** At most one queued evaluation, with a fresh evaluation retained for events during execution. */
final class ProxyTaskCoalescer {
    private final Executor executor;
    private final Runnable task;
    private boolean pending;
    private boolean closed;

    ProxyTaskCoalescer(Executor executor, Runnable task) {
        this.executor = executor;
        this.task = task;
    }

    synchronized void request() {
        if (closed || pending) return;
        pending = true;
        try {
            executor.execute(() -> {
                synchronized (this) {
                    pending = false;
                    if (closed) return;
                }
                task.run();
            });
        } catch (RejectedExecutionException stopped) {
            pending = false;
        }
    }

    synchronized void close() { closed = true; }
}
