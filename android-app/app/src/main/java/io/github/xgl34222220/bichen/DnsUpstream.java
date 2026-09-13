package io.github.xgl34222220.bichen;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Proxy;
import java.net.URL;
import java.util.Collections;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.RejectedExecutionException;
import javax.net.ssl.HttpsURLConnection;

/** RFC 8484 binary HTTPS transport. TLS/hostname validation is always the platform default. */
public final class DnsUpstream implements Closeable {
    interface ConnectionFactory { HttpsURLConnection open(URL url) throws IOException; }
    private final ConnectionFactory factory;
    private final Set<HttpsURLConnection> active = Collections.newSetFromMap(new ConcurrentHashMap<HttpsURLConnection, Boolean>());
    private final ConcurrentHashMap<String, Long> backoff = new ConcurrentHashMap<>();
    private final ScheduledThreadPoolExecutor deadlines = new ScheduledThreadPoolExecutor(1, task -> {
        Thread thread = new Thread(task, "bichen-doh-deadline"); thread.setDaemon(true); return thread;
    });
    private volatile boolean closed;

    public DnsUpstream() {
        this(url -> (HttpsURLConnection)url.openConnection(Proxy.NO_PROXY));
    }
    DnsUpstream(ConnectionFactory factory) { this.factory = factory; deadlines.setRemoveOnCancelPolicy(true); }

    static String endpoint(String provider) throws IOException {
        if ("cloudflare".equals(provider)) return "https://cloudflare-dns.com/dns-query";
        if ("google".equals(provider)) return "https://dns.google/dns-query";
        throw new IOException("未知加密 DNS 服务");
    }

    public byte[] exchange(DnsPacket.Query query, String provider) throws IOException {
        URL url = new URL(endpoint(provider));
        long started = System.nanoTime();
        if (closed || Thread.currentThread().isInterrupted()) throw new IOException("DNS 防护已停止");
        Long until = backoff.get(provider);
        if (until != null && started < until) throw new IOException(provider + " 暂时不可用，等待重试");
        HttpsURLConnection connection = factory.open(url);
        active.add(connection);
        ScheduledFuture<?> deadline = null;
        try {
            if (closed) throw new IOException("DNS 防护已停止");
            try { deadline = deadlines.schedule(connection::disconnect, 5, TimeUnit.SECONDS); }
            catch (RejectedExecutionException stopped) { throw new IOException("DNS 防护已停止"); }
            connection.setConnectTimeout(2000);
            connection.setReadTimeout(2000);
            connection.setInstanceFollowRedirects(false); // Fixed trusted endpoints; never leak a query through redirect.
            connection.setUseCaches(false);
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Accept", "application/dns-message");
            connection.setRequestProperty("Content-Type", "application/dns-message");
            connection.setRequestProperty("Cache-Control", "no-cache");
            connection.setRequestProperty("Accept-Encoding", "identity");
            connection.setRequestProperty("User-Agent", "Bichen/0.3");
            connection.setDoOutput(true);
            connection.setFixedLengthStreamingMode(query.dns.length);
            try (OutputStream output = connection.getOutputStream()) { output.write(query.dns); }
            int status = connection.getResponseCode();
            if (status != 200) {
                if (status == 429 || status == 503) {
                    long seconds = decimalHeader(connection.getHeaderField("Retry-After"), 30, 300);
                    backoff.put(provider, System.nanoTime() + TimeUnit.SECONDS.toNanos(Math.max(1, seconds)));
                }
                throw new IOException(provider + " HTTPS 状态 " + status);
            }
            String type = connection.getContentType();
            if (type == null || !"application/dns-message".equals(type.split(";", 2)[0].trim().toLowerCase(Locale.ROOT)))
                throw new IOException(provider + " 返回了非 DNS 内容");
            String encoding = connection.getContentEncoding();
            if (encoding != null && !"identity".equalsIgnoreCase(encoding)) throw new IOException("不支持的 DNS 内容编码");
            int length = connection.getContentLength();
            if (length > DnsPacket.MAX_DNS || length >= 0 && length < 12) throw new IOException("HTTPS DNS 响应长度异常");
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(length > 0 ? length : 512);
            try (InputStream input = connection.getInputStream()) {
                byte[] buffer = new byte[2048];
                while (true) {
                    if (closed || Thread.currentThread().isInterrupted()) throw new IOException("DNS 防护已停止");
                    if (System.nanoTime() - started > TimeUnit.SECONDS.toNanos(5)) throw new IOException("HTTPS DNS 总耗时超时");
                    int count = input.read(buffer);
                    if (count < 0) break;
                    if (bytes.size() + count > DnsPacket.MAX_DNS) throw new IOException("HTTPS DNS 响应过大");
                    bytes.write(buffer, 0, count);
                }
            }
            byte[] answer = bytes.toByteArray();
            if (length >= 0 && answer.length != length || !DnsPacket.validResponse(query, answer) || DnsPacket.truncated(answer)
                    || DnsPacket.ttlOffsets(answer) == null) throw new IOException("HTTPS DNS 响应无效");
            long age = decimalHeader(connection.getHeaderField("Age"), 0, 0x7fffffffL);
            answer = DnsPacket.subtractAge(answer, age);
            if (answer == null) throw new IOException("HTTPS DNS 缓存年龄无效");
            backoff.remove(provider);
            return answer;
        } catch (IOException error) {
            long now = System.nanoTime();
            backoff.compute(provider, (key, previous) -> previous != null && previous > now ? previous : now + TimeUnit.SECONDS.toNanos(2));
            throw error;
        } finally {
            if (deadline != null) deadline.cancel(false);
            active.remove(connection);
            connection.disconnect();
        }
    }

    private static long decimalHeader(String value, long fallback, long max) {
        if (value == null || value.length() > 20) return fallback;
        try { return Math.max(0, Math.min(max, Long.parseLong(value.trim()))); }
        catch (NumberFormatException error) { return fallback; }
    }

    @Override public void close() {
        closed = true;
        deadlines.shutdownNow();
        for (HttpsURLConnection connection : active) connection.disconnect();
        active.clear(); backoff.clear();
    }
}
