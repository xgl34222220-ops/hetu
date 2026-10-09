package io.github.xgl34222220.hetu;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
/** Nonblocking publication of observations, invalidated by every control transaction. */
final class ProxyControlEpoch {
    private final ReentrantLock gate = new ReentrantLock(true);
    private final AtomicLong generation = new AtomicLong();
    // Control transactions only. A short observation publication also holds the
    // gate, but it changes no runtime and must not look like a transaction to a
    // read-only latency probe (that made speed tests fail at random on a phone).
    private volatile int transactions;
    void lock() { gate.lock(); transactions++; generation.incrementAndGet(); }
    boolean tryLock() {
        if (!gate.tryLock()) return false;
        transactions++; generation.incrementAndGet(); return true;
    }
    void unlock() { if (gate.isHeldByCurrentThread() && transactions > 0) transactions--; gate.unlock(); }
    boolean isHeldByCurrentThread() { return gate.isHeldByCurrentThread(); }
    long observe() {
        if (gate.isLocked()) return -1;
        long ticket = generation.get();
        return gate.isLocked() ? -1 : ticket;
    }
    /** -1 only while a real control transaction runs; publications never flip it. */
    long transactionEpoch() {
        if (transactions > 0) return -1;
        long ticket = generation.get();
        return transactions > 0 ? -1 : ticket;
    }
    void invalidateObservations() { generation.incrementAndGet(); }
    boolean publish(long ticket, Runnable action) {
        if (ticket < 0 || !gate.tryLock()) return false;
        try {
            if (generation.get() != ticket) return false;
            action.run(); return true;
        } finally { gate.unlock(); }
    }
}
