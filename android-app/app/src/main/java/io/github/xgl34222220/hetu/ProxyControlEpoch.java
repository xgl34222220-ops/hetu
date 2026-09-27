package io.github.xgl34222220.hetu;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
/** Nonblocking publication of observations, invalidated by every control transaction. */
final class ProxyControlEpoch {
    private final ReentrantLock gate = new ReentrantLock(true);
    private final AtomicLong generation = new AtomicLong();
    void lock() { gate.lock(); generation.incrementAndGet(); }
    boolean tryLock() {
        if (!gate.tryLock()) return false;
        generation.incrementAndGet(); return true;
    }
    void unlock() { gate.unlock(); }
    long observe() {
        if (gate.isLocked()) return -1;
        long ticket = generation.get();
        return gate.isLocked() ? -1 : ticket;
    }
    boolean publish(long ticket, Runnable action) {
        if (ticket < 0 || !gate.tryLock()) return false;
        try {
            if (generation.get() != ticket) return false;
            action.run(); return true;
        } finally { gate.unlock(); }
    }
}
