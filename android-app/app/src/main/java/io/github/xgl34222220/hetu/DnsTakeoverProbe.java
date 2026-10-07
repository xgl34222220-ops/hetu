package io.github.xgl34222220.hetu;

import java.net.InetAddress;
import java.security.SecureRandom;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Asks the system resolver, the way every application does, for a name that does not exist.
 * A core in fake-IP mode answers any name from its fake range, so the answer tells whether
 * ordinary lookups on this device reach the core or still go to the network's DNS server.
 */
final class DnsTakeoverProbe {
    static final String CAPTURED = "captured";
    static final String DIRECT = "direct";
    static final String INCONCLUSIVE = "inconclusive";

    private DnsTakeoverProbe() { }

    static final class Result {
        final String verdict, name, answer;
        Result(String verdict, String name, String answer) { this.verdict = verdict; this.name = name; this.answer = answer; }

        String describe() {
            switch (verdict) {
                case CAPTURED: return "系统解析已进入核心：" + name + " → " + answer + "（fake-ip）";
                case DIRECT: return "系统解析没有进入核心：" + name + " → " + answer
                        + "。应用拿到的是网络 DNS 的应答，被污染的域名会连到错误地址。";
                default: return "无法判断：" + answer;
            }
        }
    }

    /** IPv4 only: the probe is compared with the IPv4 fake range. */
    static boolean inCidr(String address, String cidr) {
        try {
            String[] parts = cidr.trim().split("/");
            if (parts.length != 2) return false;
            int bits = Integer.parseInt(parts[1]);
            if (bits < 0 || bits > 32) return false;
            long mask = bits == 0 ? 0L : (0xFFFFFFFFL << (32 - bits)) & 0xFFFFFFFFL;
            return (value(address) & mask) == (value(parts[0]) & mask);
        } catch (RuntimeException invalid) {
            return false;
        }
    }

    private static long value(String address) {
        String[] octets = address.trim().split("\\.");
        if (octets.length != 4) throw new IllegalArgumentException(address);
        long out = 0;
        for (String octet : octets) {
            int n = Integer.parseInt(octet);
            if (n < 0 || n > 255) throw new IllegalArgumentException(address);
            out = (out << 8) | n;
        }
        return out;
    }

    static String verdict(String answer, String fakeCidr) {
        if (fakeCidr == null || fakeCidr.trim().isEmpty()) return INCONCLUSIVE;
        if (answer == null || answer.isEmpty()) return DIRECT;
        return inCidr(answer, fakeCidr) ? CAPTURED : DIRECT;
    }

    /** Blocking; call from a worker thread. Never throws. */
    static Result run(String fakeCidr, long timeoutMs) {
        if (fakeCidr == null || fakeCidr.trim().isEmpty())
            return new Result(INCONCLUSIVE, "", "当前运行配置没有 fake-ip 网段（redir-host 或核心未运行），这种方式分辨不出来");
        byte[] random = new byte[6];
        new SecureRandom().nextBytes(random);
        StringBuilder label = new StringBuilder("connect-");
        for (byte b : random) label.append(String.format(Locale.ROOT, "%02x", b & 255));
        final String name = label.append(".hetu-dns-probe.com").toString();
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            Future<String> lookup = worker.submit(() -> {
                for (InetAddress address : InetAddress.getAllByName(name))
                    if (address.getAddress().length == 4) return address.getHostAddress();
                return "";
            });
            String answer;
            try { answer = lookup.get(Math.max(500L, timeoutMs), TimeUnit.MILLISECONDS); }
            catch (java.util.concurrent.TimeoutException slow) { lookup.cancel(true); return new Result(INCONCLUSIVE, name, "解析超时"); }
            catch (java.util.concurrent.ExecutionException failed) { answer = ""; }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return new Result(INCONCLUSIVE, name, "已中断"); }
            String verdict = verdict(answer, fakeCidr);
            return new Result(verdict, name, answer.isEmpty() ? "域名不存在" : answer);
        } finally {
            worker.shutdownNow();
        }
    }
}
