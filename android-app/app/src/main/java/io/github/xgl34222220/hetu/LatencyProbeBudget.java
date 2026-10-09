package io.github.xgl34222220.hetu;

import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.*;

/** One monotonic budget for metadata, queueing, fallback URLs and every leaf in a wave. */
final class LatencyProbeBudget implements AutoCloseable {
    static final long DEFAULT_TIMEOUT_MS = 20_000;
    static final ThreadLocal<LatencyProbeBudget> CURRENT = new ThreadLocal<>();
    private static final ScheduledExecutorService TIMER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "hetu-probe-deadline"); t.setDaemon(true); return t;
    });
    // DNS can be non-interruptible on some systems. Keep both its workers and backlog bounded.
    private static final ThreadPoolExecutor DNS = new ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS,
        new ArrayBlockingQueue<>(12), r -> { Thread t = new Thread(r, "hetu-probe-dns"); t.setDaemon(true); return t; });
    private final long deadline;
    private final boolean hasDeadline;
    private final Set<Socket> sockets = new HashSet<>();
    private final ScheduledFuture<?> alarm;
    private volatile boolean closed;

    LatencyProbeBudget(long timeoutMs) {
        hasDeadline = true;
        deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        alarm = TIMER.schedule(this::close, timeoutMs, TimeUnit.MILLISECONDS);
    }
    // Ordinary controller snapshots retain their existing per-request timeouts.
    // This variant only supplies cancellation-aware DNS/socket cleanup; no timer.
    private LatencyProbeBudget() {
        hasDeadline = false;
        deadline = 0L;
        alarm = null;
    }
    static LatencyProbeBudget cancellationOnly() { return new LatencyProbeBudget(); }
    boolean expired() { return closed || (hasDeadline && System.nanoTime() >= deadline); }
    void check() throws IOException {
        if (expired() || Thread.currentThread().isInterrupted())
            throw new IOException(hasDeadline ? "测速请求已取消或达到总时限，已保留完成的结果，请重试" : "控制接口请求已取消");
    }
    int remainingMillis() throws IOException {
        check();
        if (!hasDeadline) return Integer.MAX_VALUE;
        // Round up: truncation can fire SO_TIMEOUT just before the shared deadline,
        // incorrectly exposing a generic socket error instead of the terminal budget outcome.
        long nanos = Math.max(1, deadline - System.nanoTime());
        return (int)Math.min(Integer.MAX_VALUE, Math.max(1, (nanos + 999_999) / 1_000_000));
    }
    void acquire(Semaphore slots) throws Exception {
        while (true) {
            check();
            if (slots.tryAcquire(Math.min(50, remainingMillis()), TimeUnit.MILLISECONDS)) {
                try { check(); return; } catch (IOException e) { slots.release(); throw e; }
            }
        }
    }
    synchronized void register(Socket socket) throws IOException { check(); sockets.add(socket); }
    synchronized void unregister(Socket socket) { sockets.remove(socket); }
    InetAddress resolve(String host) throws Exception {
        check();
        Future<InetAddress> result;
        try { result = DNS.submit(() -> InetAddress.getByName(host)); }
        catch (RejectedExecutionException e) { throw new IOException("测速地址解析繁忙，请重试", e); }
        try {
            while (true) {
                check();
                try { return result.get(Math.min(50, remainingMillis()), TimeUnit.MILLISECONDS); }
                catch (TimeoutException retry) { check(); }
                catch (ExecutionException e) { throw new IOException("测速控制接口地址解析失败", e.getCause()); }
            }
        } finally { result.cancel(true); DNS.purge(); }
    }
    @Override public synchronized void close() {
        closed = true;
        for (Socket socket : sockets) try { socket.close(); } catch (IOException ignored) { }
        sockets.clear();
        // The timer may run before construction has assigned alarm with a tiny test budget.
        if (alarm != null) alarm.cancel(false);
    }
}
