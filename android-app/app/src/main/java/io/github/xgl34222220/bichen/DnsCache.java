package io.github.xgl34222220.bichen;

import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.LongSupplier;

/** Bounded, monotonic-clock positive-response cache. Call rule matching before every lookup. */
public final class DnsCache {
    private static final int DEFAULT_ENTRIES = 512, DEFAULT_BYTES = 2 * 1024 * 1024;
    private static final long MAX_TTL_SECONDS = 600;
    private final int maxEntries, maxBytes;
    private final LongSupplier clock;
    private final LinkedHashMap<Key, Entry> entries = new LinkedHashMap<>(16, 0.75f, true);
    private int bytes;
    private String revision;

    public DnsCache() { this(DEFAULT_ENTRIES, DEFAULT_BYTES, () -> System.nanoTime() / 1000000L); }
    DnsCache(int maxEntries, int maxBytes, LongSupplier clock) {
        if (maxEntries < 1 || maxBytes < 64) throw new IllegalArgumentException("cache limits");
        this.maxEntries = maxEntries; this.maxBytes = maxBytes; this.clock = clock;
    }

    public synchronized void clear() { entries.clear(); bytes = 0; revision = null; }

    private void matchRevision(String current) {
        if (revision == null || !revision.equals(current)) { clear(); revision = current; }
    }

    public synchronized byte[] get(DnsPacket.Query query, String currentRevision) {
        matchRevision(currentRevision);
        Key key = new Key(query.dns);
        Entry entry = entries.get(key);
        if (entry == null) return null;
        long elapsed = clock.getAsLong() - entry.created;
        if (elapsed < 0 || elapsed >= entry.lifetime * 1000L) {
            entries.remove(key); bytes -= entry.cost; return null;
        }
        byte[] answer = entry.answer.clone();
        answer[0] = query.dns[0]; answer[1] = query.dns[1];
        // Round elapsed time upwards: never extend the upstream's original expiry.
        long age = (elapsed + 999L) / 1000L;
        for (int offset : entry.ttls) put32(answer, offset, Math.max(0L, Math.min(MAX_TTL_SECONDS, u32(answer, offset)) - age));
        return answer;
    }

    public synchronized void put(DnsPacket.Query query, byte[] answer, String currentRevision) {
        matchRevision(currentRevision);
        // Exact wire key keeps RD/CD, EDNS, DNSSEC, ECS and question casing independent.
        // Deliberately do not cache negative answers without full RFC 2308 SOA processing.
        if (!DnsPacket.validResponse(query, answer) || DnsPacket.truncated(answer)
                || (answer[3] & 15) != 0 || DnsPacket.u16(answer, 6) == 0
                || DnsPacket.u16(query.dns, 6) != 0 || DnsPacket.u16(query.dns, 8) != 0
                || DnsPacket.ttlOffsets(query.dns) == null) return;
        int[] ttls = DnsPacket.ttlOffsets(answer);
        if (ttls == null || ttls.length == 0) return;
        long ttl = MAX_TTL_SECONDS;
        for (int offset : ttls) {
            long value = u32(answer, offset);
            if (value == 0 || value > 0x7fffffffL) return; // RFC 2181: high-bit TTL is zero.
            ttl = Math.min(ttl, value);
        }
        Key key = new Key(query.dns);
        Entry entry = new Entry(answer, ttls, ttl, clock.getAsLong(), key.wire.length);
        if (entry.cost > maxBytes) return;
        Entry old = entries.remove(key); if (old != null) bytes -= old.cost;
        entries.put(key, entry); bytes += entry.cost;
        Iterator<Map.Entry<Key, Entry>> iterator = entries.entrySet().iterator();
        while (entries.size() > maxEntries || bytes > maxBytes) {
            Map.Entry<Key, Entry> eldest = iterator.next(); bytes -= eldest.getValue().cost; iterator.remove();
        }
    }

    synchronized int size() { return entries.size(); }
    synchronized int byteSize() { return bytes; }

    private static final class Key {
        final byte[] wire;
        final int hash;
        Key(byte[] dns) { wire = dns.clone(); wire[0] = 0; wire[1] = 0; hash = Arrays.hashCode(wire); }
        @Override public int hashCode() { return hash; }
        @Override public boolean equals(Object object) { return object instanceof Key && Arrays.equals(wire, ((Key)object).wire); }
    }
    private static final class Entry {
        final byte[] answer;
        final int[] ttls;
        final int cost;
        final long lifetime, created;
        Entry(byte[] answer, int[] ttls, long lifetime, long created, int queryBytes) {
            this.answer = answer.clone(); this.ttls = ttls; this.lifetime = lifetime; this.created = created;
            cost = answer.length + queryBytes + ttls.length * 4 + 128;
        }
    }
    private static long u32(byte[] b, int p) { return ((long)DnsPacket.u16(b, p) << 16) | DnsPacket.u16(b, p + 2); }
    private static void put32(byte[] b, int p, long n) {
        b[p] = (byte)(n >>> 24); b[p + 1] = (byte)(n >>> 16); b[p + 2] = (byte)(n >>> 8); b[p + 3] = (byte)n;
    }
}
