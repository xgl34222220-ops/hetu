package io.github.xgl34222220.bichen;

public final class ProxyNetworkStateTest {
    private static int checks;
    private static void check(boolean ok, String label) {
        if (!ok) throw new AssertionError(label);
        checks++; System.out.println("PASS " + label);
    }
    public static void main(String[] args) {
        ProxyNetworkState s = new ProxyNetworkState();
        check(s.state() == ProxyNetworkState.State.WAITING, "no default network is not connected");
        s.available("wifi");
        check(s.state() == ProxyNetworkState.State.CHECKING && s.underlying() == null, "onAvailable alone cannot report internet");
        s.capabilities("wifi", true, true, false, false);
        check(s.state() == ProxyNetworkState.State.UNVERIFIED && "wifi".equals(s.underlying()), "unvalidated internet still allows a tunnel, without a false verified claim");
        s.capabilities("wifi", true, true, false, true);
        check(s.state() == ProxyNetworkState.State.CAPTIVE, "captive portal explained");
        s.capabilities("wifi", true, true, true, false);
        check(s.state() == ProxyNetworkState.State.READY, "capability change restores state without a second onAvailable");
        s.available("wifi");
        check(s.state() == ProxyNetworkState.State.READY, "duplicate availability does not erase verified state");
        s.available("mobile"); s.capabilities("mobile", true, true, true, false);
        check(!s.lost("wifi") && s.state() == ProxyNetworkState.State.READY, "late Wi-Fi loss does not disconnect mobile");
        check(!s.capabilities("wifi", false, false, false, false) && "mobile".equals(s.underlying()), "late old capabilities cannot replace current network");
        check(!s.blocked("wifi", true), "late old block ignored");
        s.blocked("mobile", true);
        check(s.state() == ProxyNetworkState.State.BLOCKED && s.underlying() == null, "system blocking is not reported as connected");
        s.blocked("mobile", false);
        check(s.state() == ProxyNetworkState.State.READY, "unblock restores known state");
        s.lost("mobile");
        check(s.state() == ProxyNetworkState.State.WAITING && s.underlying() == null, "real default loss clears underlying");
        check(!s.capabilities("mobile", true, true, true, false), "late capabilities cannot revive lost network");
        s.available("vpn");s.capabilities("vpn", false, true, true, false);
        check(s.underlying() == null && s.state() == ProxyNetworkState.State.WAITING, "VPN cannot be its own physical upstream");
        s.available("local");s.capabilities("local", true, false, true, false);
        check(s.underlying() == null, "local-only network not called internet");
        for(int i=0;i<10000;i++) {
            String old="n"+i, next="n"+(i+1);
            s.available(old);s.capabilities(old,true,true,true,false);s.available(next);
            if(s.lost(old)||s.capabilities(old,true,true,true,false)||s.state()!=ProxyNetworkState.State.CHECKING)throw new AssertionError("handover "+i);
            s.capabilities(next,true,true,true,false);
        }
        check(s.state()==ProxyNetworkState.State.READY,"10000 handovers reject stale network events");
        System.out.println("ProxyNetworkStateTest passed: " + checks);
    }
}
