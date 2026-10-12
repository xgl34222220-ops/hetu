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
    /** Per-node core timeout passed as ?timeout= to /proxies/{name}/delay and /group/{name}/delay. */
    static final long NODE_TIMEOUT_MS = 5_000;
    /** Leaf probes in flight per wave (pinned by the V20.95 scheduling contract). */
    static final int WAVE_CONCURRENCY = 6;
    /** Upper bound of one wave, however many nodes it holds. */
    static final long MAX_WAVE_TIMEOUT_MS = 150_000;
    // One leaf slot: the core's 5 s timeout plus the controller response margin.
    private static final long LEAF_SLOT_MS = NODE_TIMEOUT_MS + 1_500;
    // Snapshot reads (/proxies + /providers/proxies) before the first leaf starts.
    private static final long METADATA_MS = 4_000;

    /**
     * Total budget of one wave over {@code targets} leaves. The old fixed 20 s budget let only
     * ~22 dead nodes (6 in flight x 5 s each) finish before the whole test reported 已到时限,
     * so large subscriptions always ended as a partial wave. A wave now gets one full slot per
     * round of {@link #WAVE_CONCURRENCY} leaves, plus half again for one retry of transport
     * failures; small waves keep the established 20 s floor.
     */
    static long waveTimeoutMs(int targets) {
        if (targets <= 0) return DEFAULT_TIMEOUT_MS;
        long rounds = (targets + WAVE_CONCURRENCY - 1) / WAVE_CONCURRENCY;
        long firstPass = METADATA_MS + rounds * LEAF_SLOT_MS;
        long retry = Math.max(LEAF_SLOT_MS, firstPass / 2);
        return Math.max(DEFAULT_TIMEOUT_MS, Math.min(MAX_WAVE_TIMEOUT_MS, firstPass + retry));
    }
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
