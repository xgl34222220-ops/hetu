package io.github.xgl34222220.hetu;

import java.net.InetAddress;
import java.security.SecureRandom;
import java.util.Locale;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * A fake-IP answer is evidence that this lookup reached a fake-IP resolver. A negative
 * answer cannot establish bypass: fake-ip-filter, NXDOMAIN, IPv6-only answers and resolver
 * failures are all legitimate alternatives. This check never changes the running policy.
 */
final class DnsTakeoverProbe {
    static final String CAPTURED = "captured";
    static final String INCONCLUSIVE = "inconclusive";
    interface Lookup { InetAddress[] resolve(String name) throws Exception; }
    // A system resolver may ignore interruption. Repeated diagnostics must not leave
    // an unbounded number of blocked threads behind after their visible deadlines.
    private static final ThreadPoolExecutor LOOKUPS = new ThreadPoolExecutor(2, 2, 30L,
            TimeUnit.SECONDS, new ArrayBlockingQueue<>(1), task -> {
                Thread thread = new Thread(task, "hetu-dns-check");
                thread.setDaemon(true);
                return thread;
            });
    static { LOOKUPS.allowCoreThreadTimeOut(true); }

    private DnsTakeoverProbe() { }

    static final class Result {
        final String verdict, name, answer;
        Result(String verdict, String name, String answer) { this.verdict = verdict; this.name = name; this.answer = answer; }

        String describe() {
            switch (verdict) {
                case CAPTURED: return "本次系统解析返回运行配置的 fake-ip 网段：" + name + " → " + answer;
                default: return "DNS 接管暂无法判断：" + answer
                        + "。单次解析不能确定绕过、污染或断网原因，请结合运行记录与规则计数核对。";
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
        return answer != null && inCidr(answer, fakeCidr) ? CAPTURED : INCONCLUSIVE;
    }

    /** Blocking; call from a worker thread. Never throws. */
    static Result run(String fakeCidr, long timeoutMs) {
        return run(fakeCidr, timeoutMs, InetAddress::getAllByName);
    }

    static Result run(String fakeCidr, long timeoutMs, Lookup resolver) {
        if (Thread.currentThread().isInterrupted()) return new Result(INCONCLUSIVE, "", "已中断");
        if (fakeCidr == null || fakeCidr.trim().isEmpty())
            return new Result(INCONCLUSIVE, "", "当前运行配置没有 fake-ip 网段（redir-host 或核心未运行），这种方式分辨不出来");
        byte[] random = new byte[6];
        new SecureRandom().nextBytes(random);
        StringBuilder label = new StringBuilder("connect-");
        for (byte b : random) label.append(String.format(Locale.ROOT, "%02x", b & 255));
        final String name = label.append(".hetu-dns-probe.invalid").toString();
        Future<InetAddress[]> lookup;
        try {
            lookup = LOOKUPS.submit(() -> resolver.resolve(name));
        } catch (RejectedExecutionException busy) {
            return new Result(INCONCLUSIVE, name, "前一次系统解析仍未结束，请稍后检查");
        }
        try {
            InetAddress[] answers = lookup.get(Math.max(500L, timeoutMs), TimeUnit.MILLISECONDS);
            String answer = "";
            if (answers != null) for (InetAddress address : answers) {
                if (address == null) continue;
                String value = address.getHostAddress();
                if (CAPTURED.equals(verdict(value, fakeCidr))) return new Result(CAPTURED, name, value);
                if (answer.isEmpty()) answer = value;
            }
            return new Result(INCONCLUSIVE, name, answer.isEmpty() ? "没有得到地址，可能是域名不存在或解析规则过滤"
                    : "本次地址 " + answer + " 不在 fake-ip 网段，解析规则过滤等情况也会产生此结果");
        } catch (java.util.concurrent.TimeoutException slow) {
            lookup.cancel(true); return new Result(INCONCLUSIVE, name, "解析超时");
        } catch (java.util.concurrent.ExecutionException failed) {
            return new Result(INCONCLUSIVE, name, "解析没有完成，可能是域名不存在或解析服务不可用");
        } catch (InterruptedException interrupted) {
            lookup.cancel(true); Thread.currentThread().interrupt(); return new Result(INCONCLUSIVE, name, "已中断");
        }
    }
}
