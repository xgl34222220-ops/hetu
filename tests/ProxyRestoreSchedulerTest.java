package io.github.xgl34222220.hetu;

import java.util.ArrayDeque;
import java.util.concurrent.RejectedExecutionException;

/** Exercises retries and cancellation with a controlled clock instead of sleeping. */
public final class ProxyRestoreSchedulerTest {
    private static int checks;
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
        checks++;
    }

    private static final class Task {
        final Runnable action;
        final long delay;
        Task(Runnable action, long delay) { this.action = action; this.delay = delay; }
    }

    private static final class Queue implements ProxyRestoreScheduler.Dispatcher {
        final ArrayDeque<Task> tasks = new ArrayDeque<>();
        boolean reject;
        public void dispatch(Runnable action, long delay) {
            if (reject) throw new RejectedExecutionException();
            tasks.addLast(new Task(action, delay));
        }
        void runNext() { tasks.removeFirst().action.run(); }
    }

    public static void main(String[] args) {
        Queue queue = new Queue();
        int[] attempts = {0};
        ProxyRestoreScheduler[] scheduler = new ProxyRestoreScheduler[1];
        scheduler[0] = new ProxyRestoreScheduler(queue, () -> {
            attempts[0]++;
            scheduler[0].request(1L); // A simultaneous network callback is coalesced.
            return attempts[0] < 3 ? attempts[0] * 1000L : 0L;
        });
        scheduler[0].request(350L);
        scheduler[0].request(250L);
        check(queue.tasks.size() == 1, "duplicate boot/network events schedule one attempt");
        check(queue.tasks.peekFirst().delay == 350L, "initial restore retains its delay");
        queue.runNext();
        check(attempts[0] == 1 && queue.tasks.size() == 1, "failed attempt queues a real retry after completion");
        check(queue.tasks.peekFirst().delay == 1000L, "retry uses the requested backoff, not a duplicate event");
        queue.runNext();
        check(attempts[0] == 2 && queue.tasks.size() == 1, "multiple failures continue retrying");
        check(queue.tasks.peekFirst().delay == 2000L, "subsequent retry backoff is preserved");
        queue.runNext();
        check(attempts[0] == 3 && queue.tasks.isEmpty(), "successful restore stops retrying");

        Queue cancelledQueue = new Queue();
        int[] cancelledAttempts = {0};
        ProxyRestoreScheduler cancelled = new ProxyRestoreScheduler(cancelledQueue, () -> {
            cancelledAttempts[0]++;
            return 1000L;
        });
        cancelled.request(350L);
        cancelled.close();
        cancelledQueue.runNext();
        cancelled.request(0L);
        check(cancelledAttempts[0] == 0 && cancelledQueue.tasks.isEmpty(), "service destruction cancels queued restores and new callbacks");

        Queue stoppedQueue = new Queue();
        ProxyRestoreScheduler[] stopped = new ProxyRestoreScheduler[1];
        stopped[0] = new ProxyRestoreScheduler(stoppedQueue, () -> {
            stopped[0].close();
            return 1000L;
        });
        stopped[0].request(0L);
        stoppedQueue.runNext();
        check(stoppedQueue.tasks.isEmpty(), "destroying service during an attempt suppresses its retry");

        Queue rejectedQueue = new Queue();
        ProxyRestoreScheduler rejected = new ProxyRestoreScheduler(rejectedQueue, () -> 0L);
        rejectedQueue.reject = true;
        try { rejected.request(0L); throw new AssertionError("dispatch failure was hidden"); }
        catch (RejectedExecutionException expected) { }
        rejectedQueue.reject = false;
        rejected.request(-1L);
        check(rejectedQueue.tasks.size() == 1, "a rejected dispatch does not leave the scheduler stuck");
        check(rejectedQueue.tasks.peekFirst().delay == 0L, "negative requested delay becomes immediate");

        Queue failedQueue = new Queue();
        int[] failedAttempts = {0};
        ProxyRestoreScheduler failed = new ProxyRestoreScheduler(failedQueue, () -> {
            if (++failedAttempts[0] == 1) throw new IllegalStateException("restore failed");
            return 0L;
        });
        failed.request(0L);
        try { failedQueue.runNext(); throw new AssertionError("attempt failure was hidden"); }
        catch (IllegalStateException expected) { }
        failed.request(0L);
        failedQueue.runNext();
        check(failedAttempts[0] == 2 && failedQueue.tasks.isEmpty(), "unexpected failure releases the gate for a later network event");
        System.out.println("ProxyRestoreSchedulerTest passed: " + checks);
    }
}
