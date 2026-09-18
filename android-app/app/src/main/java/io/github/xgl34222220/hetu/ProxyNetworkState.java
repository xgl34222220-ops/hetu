package io.github.xgl34222220.hetu;

/** Default-network state, independent of Android for deterministic handover tests.
 * A newly available network is not evidence of working internet. Stale events
 * from the previous network never clear or revive the current one. */
final class ProxyNetworkState {
    enum State { WAITING, CHECKING, READY, UNVERIFIED, CAPTIVE, BLOCKED }
    private Object current;
    private boolean known, physical, internet, validated, captive, blocked;

    synchronized void available(Object network) {
        if (network == null || network.equals(current)) return;
        current = network;
        known = physical = internet = validated = captive = blocked = false;
    }
    synchronized boolean capabilities(Object network, boolean notVpn, boolean hasInternet,
            boolean isValidated, boolean isCaptive) {
        if (network == null || !network.equals(current)) return false;
        known = true; physical = notVpn; internet = hasInternet;
        validated = isValidated; captive = isCaptive;
        return true;
    }
    synchronized boolean blocked(Object network, boolean value) {
        if (network == null || !network.equals(current)) return false;
        blocked = value;
        return true;
    }
    synchronized boolean lost(Object network) {
        if (network == null || !network.equals(current)) return false;
        current = null;
        known = physical = internet = validated = captive = blocked = false;
        return true;
    }
    synchronized Object underlying() {
        return known && physical && internet && !blocked ? current : null;
    }
    synchronized State state() {
        if (current == null) return State.WAITING;
        if (!known) return State.CHECKING;
        if (!physical || !internet) return State.WAITING;
        if (blocked) return State.BLOCKED;
        if (captive) return State.CAPTIVE;
        return validated ? State.READY : State.UNVERIFIED;
    }
    static String label(State state) {
        switch (state) {
            case CHECKING: return "正在确认网络";
            case READY: return "网络已验证";
            case UNVERIFIED: return "网络待验证";
            case CAPTIVE: return "网络需要登录";
            case BLOCKED: return "系统限制联网";
            default: return "等待网络恢复";
        }
    }
}
