package io.github.xgl34222220.hetu;

import java.util.ArrayDeque;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

public final class ProxyNetworkEventsTest {
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
        ProxyNetworkHandover<String> networks = new ProxyNetworkHandover<>();
        check(networks.available("wifi") == 0L, "initial callback does not interrupt established sessions");
        check(networks.available("wifi") == 0L, "duplicate callback does not interrupt sessions");
        long cellular = networks.available("cellular");
        check(networks.isCurrent("cellular", cellular), "new default network schedules recovery");
        networks.lost("wifi");
        check(networks.isCurrent("cellular", cellular), "late loss of old wifi must not cancel cellular recovery");
        networks.available("cellular");
        check(networks.isCurrent("cellular", cellular), "duplicate availability must not cancel pending recovery");
        networks.lost("cellular");
        check(!networks.isCurrent("cellular", cellular), "actual loss cancels obsolete recovery");
        long wifi = networks.available("wifi");
        check(networks.isCurrent("wifi", wifi), "loss then availability also schedules recovery");
        long secondCellular = networks.available("cellular");
        check(!networks.isCurrent("wifi", wifi) && networks.isCurrent("cellular", secondCellular), "rapid handover preserves only latest recovery");
        networks.lost("unknown");
        check(networks.isCurrent("cellular", secondCellular), "unrelated network loss does not cancel recovery");

        Queue queue = new Queue();
        int[] runs = {0};
        ProxyTaskCoalescer[] events = new ProxyTaskCoalescer[1];
        events[0] = new ProxyTaskCoalescer(queue, () -> {
            if (++runs[0] == 1) {
                for (int i = 0; i < 1000; i++) events[0].request();
            }
        });
        for (int i = 0; i < 1000; i++) events[0].request();
        check(queue.tasks.size() == 1, "capability callback burst queues one evaluation");
        queue.runNext();
        check(runs[0] == 1 && queue.tasks.size() == 1, "events during evaluation retain one fresh followup");
        queue.runNext();
        check(runs[0] == 2 && queue.tasks.isEmpty(), "stable environment does not loop");
        queue.reject = true;
        events[0].request();
        queue.reject = false;
        events[0].request();
        check(queue.tasks.size() == 1, "rejected dispatch releases queue gate");
        events[0].close();
        queue.runNext();
        events[0].request();
        check(runs[0] == 2 && queue.tasks.isEmpty(), "destroyed service ignores queued and late callbacks");
        System.out.println("ProxyNetworkEventsTest passed: " + checks);
    }
}
