package io.github.xgl34222220.hetu;

import java.util.ArrayDeque;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

public final class ProxyAsyncValueTest {
    private static int checks;
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
        checks++;
    }
    private static final class Queue implements Executor {
        final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
        boolean reject;
        public void execute(Runnable task) {
            if (reject) throw new RejectedExecutionException();
            tasks.addLast(task);
        }
        void runNext() { tasks.removeFirst().run(); }
    }
    public static void main(String[] args) {
        Queue queue = new Queue();
        long[] now = {1L};
        int[] calls = {0};
        String[] response = {"IPv6-old"};
        ProxyAsyncValue<String> cache = new ProxyAsyncValue<>(queue, () -> now[0], () -> {
            calls[0]++;
            return response[0];
        }, "unknown", 900_000L, 60_000L);
        check(cache.get("wifi:enabled", true).equals("unknown") && calls[0] == 0, "dashboard returns before WAN fetch runs");
        for (int i = 0; i < 1000; i++) cache.get("wifi:enabled", true);
        check(queue.tasks.size() == 1, "refresh bursts share one request");
        queue.runNext();
        check(cache.get("wifi:enabled", true).equals("IPv6-old") && queue.tasks.isEmpty(), "successful lookup is cached");
        check(cache.get("wifi:disabled", true).equals("unknown") && queue.tasks.size() == 1, "applied IPv6 settings immediately invalidate old address");
        check(cache.get("cellular:disabled", true).equals("unknown") && queue.tasks.size() == 1, "handover coalesces with existing lookup");
        queue.runNext();
        response[0] = "IPv4-current";
        check(cache.get("cellular:disabled", true).equals("unknown") && queue.tasks.size() == 1, "stale result is ignored and current generation can retry");
        queue.runNext();
        check(cache.get("cellular:disabled", true).equals("IPv4-current"), "current generation publishes its result");
        check(cache.get("offline", false).equals("unknown") && queue.tasks.isEmpty(), "offline clears old network data without a lookup");
        response[0] = null;
        cache.get("wifi:disabled", true);
        queue.runNext();
        int failedCalls = calls[0];
        for (int i = 0; i < 1000; i++) cache.get("wifi:disabled", true);
        check(queue.tasks.isEmpty() && calls[0] == failedCalls, "failed lookup backs off instead of blocking every dashboard refresh");
        now[0] += 60_000L;
        cache.get("wifi:disabled", true);
        check(queue.tasks.size() == 1, "failed lookup becomes eligible after backoff");
        response[0] = "IPv4-recovered";
        queue.runNext();
        now[0] += 899_999L;
        check(cache.get("wifi:disabled", true).equals("IPv4-recovered") && queue.tasks.isEmpty(), "successful lookup uses full cache lifetime");
        now[0]++;
        cache.get("wifi:disabled", true);
        check(queue.tasks.size() == 1, "expired cache refreshes asynchronously");
        queue.runNext();
        queue.reject = true;
        check(cache.get("new-network", true).equals("unknown"), "executor rejection does not break dashboard sampling");
        queue.reject = false;
        cache.get("new-network", true);
        check(queue.tasks.isEmpty(), "dispatch failure also backs off");
        System.out.println("ProxyAsyncValueTest passed: " + checks);
    }
}
