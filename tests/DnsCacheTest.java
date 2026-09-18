package io.github.xgl34222220.hetu;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;

public final class DnsCacheTest {
    private static int checks;
    static void check(boolean value, String why) { checks++; if (!value) throw new AssertionError(why); }
    static void put16(byte[] b, int p, int n) { b[p] = (byte)(n >>> 8); b[p + 1] = (byte)n; }
    static void put32(byte[] b, int p, long n) { put16(b, p, (int)(n >>> 16)); put16(b, p + 2, (int)n); }
    static long u32(byte[] b, int p) { return ((long)DnsPacket.u16(b, p) << 16) | DnsPacket.u16(b, p + 2); }
    static DnsPacket.Query query(String host, int id) {
        ByteArrayOutputStream wire = new ByteArrayOutputStream();
        byte[] header = new byte[12]; put16(header, 0, id); put16(header, 2, 0x100); put16(header, 4, 1);
        wire.write(header, 0, header.length);
        for (String label : host.split("\\.")) { wire.write(label.length()); for (char c : label.toCharArray()) wire.write(c); }
        wire.write(0); wire.write(0); wire.write(1); wire.write(0); wire.write(1);
        return fromDns(wire.toByteArray());
    }
    static DnsPacket.Query fromDns(byte[] dns) {
        byte[] p = new byte[28 + dns.length]; p[0] = 0x45; put16(p, 2, p.length); p[9] = 17;
        p[12] = 10; p[15] = 1; p[16] = 10; p[19] = 2;
        put16(p, 20, 49999); put16(p, 22, 53); put16(p, 24, dns.length + 8);
        System.arraycopy(dns, 0, p, 28, dns.length);
        return DnsPacket.parse(p, p.length);
    }
    static byte[] answer(DnsPacket.Query q, long ttl) {
        byte[] a = Arrays.copyOf(q.dns, q.dns.length + 16); a[2] = (byte)0x81; a[3] = (byte)0x80; put16(a, 6, 1);
        int p = q.dns.length; a[p] = (byte)0xc0; a[p + 1] = 12; put16(a, p + 2, 1); put16(a, p + 4, 1);
        put32(a, p + 6, ttl); put16(a, p + 10, 4); a[p + 12] = 1; a[p + 13] = 2; a[p + 14] = 3; a[p + 15] = 4;
        return a;
    }
    public static void main(String[] args) {
        AtomicLong time = new AtomicLong(1000);
        DnsCache cache = new DnsCache(2, 4096, time::get);
        DnsPacket.Query q = query("www.example.com", 121), otherId = query("www.example.com", 122);
        byte[] a = answer(q, 60); int ttl = q.dns.length + 6;
        cache.put(q, a, "r1"); check(cache.size() == 1, "positive response stored");
        byte[] hit = cache.get(otherId, "r1"); check(hit != null && DnsPacket.validResponse(otherId, hit), "cache rewrites transaction id");
        check(u32(hit, ttl) == 60, "initial TTL unchanged");
        time.addAndGet(1501); hit = cache.get(q, "r1"); check(u32(hit, ttl) == 58, "TTL decreases conservatively with elapsed monotonic time");
        hit[hit.length - 1] = 99; check(cache.get(q, "r1")[hit.length - 1] == 4, "response clone protects cache");
        time.addAndGet(58499); check(cache.get(q, "r1") == null, "expired answer never served");
        cache.put(q, a, "r1"); check(cache.get(q, "r2") == null && cache.size() == 0, "rule revision changes invalidate all entries");
        cache.put(q, a, "old-vpn:r2");
        check(cache.get(q, "new-vpn:r2") == null, "late old-session response cannot leak into new upstream session");
        cache.put(q, a, "r2"); byte[] changed = q.dns.clone(); changed[3] = 0x10;
        check(cache.get(fromDns(changed), "r2") == null, "CD flags do not collide");
        changed = q.dns.clone(); changed[2] = 0;
        check(cache.get(fromDns(changed), "r2") == null, "RD flags do not collide");
        check(cache.get(query("WWW.example.com", 121), "r2") == null, "0x20 question casing remains distinct");
        cache.put(query("two.example", 5), answer(query("two.example", 5), 20), "r2");
        cache.get(q, "r2"); cache.put(query("three.example", 6), answer(query("three.example", 6), 20), "r2");
        check(cache.size() == 2 && cache.get(query("two.example", 5), "r2") == null, "LRU capacity evicts least recently used");
        cache.clear(); check(cache.size() == 0 && cache.byteSize() == 0, "clear frees accounting");
        DnsCache bytes = new DnsCache(20, 250, time::get); bytes.put(q, a, "r");
        bytes.put(query("four.example", 5), answer(query("four.example", 5), 20), "r");
        check(bytes.byteSize() <= 250 && bytes.size() <= 1, "memory byte bound independent from entry bound");
        for (long invalid : new long[]{0, 0x80000001L, 0xffffffffL}) {
            cache.put(q, answer(q, invalid), "r"); check(cache.size() == 0, "invalid TTL rejected " + invalid);
        }
        cache.put(q, DnsPacket.error(q, 3), "r"); check(cache.size() == 0, "negative answer not cached without SOA policy");
        byte[] bad = a.clone(); bad[2] |= 2; cache.put(q, bad, "r"); check(cache.size() == 0, "truncated response rejected");
        bad = a.clone(); bad[3] |= 2; cache.put(q, bad, "r"); check(cache.size() == 0, "SERVFAIL rejected");
        bad = a.clone(); bad[0] ^= 1; cache.put(q, bad, "r"); check(cache.size() == 0, "wrong transaction rejected");
        bad = a.clone(); put16(bad, q.dns.length + 10, 3); cache.put(q, bad, "r"); check(cache.size() == 0, "malformed A RDATA rejected");
        bad = a.clone(); bad[q.dns.length + 1] = (byte)q.dns.length; cache.put(q, bad, "r"); check(cache.size() == 0, "owner compression cycle rejected");
        bad = Arrays.copyOf(a, a.length - 1); cache.put(q, bad, "r"); check(cache.size() == 0, "short record rejected");
        bad = Arrays.copyOf(a, a.length + 1); cache.put(q, bad, "r"); check(cache.size() == 0, "unaccounted trailing bytes rejected");
        // A valid OPT with DO=1 has zero high 16 bits, not a zero DNS TTL.
        byte[] opt = Arrays.copyOf(a, a.length + 11); put16(opt, 10, 1); put16(opt, a.length + 1, 41);
        put16(opt, a.length + 3, 1232); put32(opt, a.length + 5, 0x8000);
        cache.put(q, opt, "r"); check(cache.get(q, "r") != null, "OPT flag field excluded from TTL minimum");
        time.addAndGet(1000); hit = cache.get(q, "r"); check(u32(hit, a.length + 5) == 0x8000, "cache never rewrites DNSSEC OPT flags");
        cache.clear(); cache.put(q, answer(q, 100000), "r"); time.addAndGet(599000);
        check(cache.get(q, "r") != null && u32(cache.get(q, "r"), ttl) == 1, "large TTL capped at 600 seconds");
        time.addAndGet(1000); check(cache.get(q, "r") == null, "cap expiry enforced");
        Random random = new Random(809);
        for (int i = 0; i < 25000; i++) {
            byte[] fuzz = new byte[random.nextInt(600)]; random.nextBytes(fuzz);
            DnsPacket.ttlOffsets(fuzz); cache.put(q, fuzz, "r");
        }
        check(cache.size() == 0, "malformed response fuzz never populated cache");
        System.out.println("PASS: DNS cache " + checks + " assertions, 25000 malformed responses");
    }
}
