package io.github.xgl34222220.hetu;

/** Tracks callback order without allowing a late loss of the old network to cancel recovery. */
final class ProxyNetworkHandover<T> {
    private T current;
    private boolean seen;
    private long generation;

    synchronized long available(T network) {
        if (network == null || network.equals(current)) return 0L;
        boolean changed = seen;
        seen = true;
        current = network;
        generation++;
        return changed ? generation : 0L;
    }

    synchronized void lost(T network) {
        if (network != null && network.equals(current)) {
            current = null;
            generation++;
        }
    }

    synchronized boolean isCurrent(T network, long ticket) {
        return ticket > 0L && ticket == generation && network != null && network.equals(current);
    }
}
