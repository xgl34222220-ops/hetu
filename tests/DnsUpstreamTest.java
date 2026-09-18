package io.github.xgl34222220.hetu;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.security.cert.Certificate;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.HttpsURLConnection;

/** Fake HTTPS connection verifies protocol behavior without network or bypassing real TLS. */
public final class DnsUpstreamTest {
    private static int checks;
    private static void check(boolean value, String why) { checks++; if (!value) throw new AssertionError(why); }
    private static class Connection extends HttpsURLConnection {
        final ByteArrayOutputStream sent = new ByteArrayOutputStream();
        final Map<String, String> responseHeaders = new HashMap<>();
        byte[] body;
        int status = 200, length = -1;
        String type = "application/dns-message";
        boolean disconnected;
        Connection(byte[] body) throws Exception { super(new URL("https://dns.google/dns-query")); this.body = body; }
        @Override public void connect() {}
        @Override public void disconnect() { disconnected = true; }
        @Override public boolean usingProxy() { return false; }
        @Override public String getCipherSuite() { return "fake-test-transport"; }
        @Override public Certificate[] getLocalCertificates() { return null; }
        @Override public Certificate[] getServerCertificates() { return null; }
        @Override public OutputStream getOutputStream() throws IOException { return sent; }
        @Override public InputStream getInputStream() { return new ByteArrayInputStream(body); }
        @Override public int getResponseCode() { return status; }
        @Override public String getContentType() { return type; }
        @Override public int getContentLength() { return length; }
        @Override public String getHeaderField(String key) { return responseHeaders.get(key); }
    }
    private static void reject(DnsPacket.Query q, Connection c, String why) throws Exception {
        try (DnsUpstream upstream = new DnsUpstream(url -> c)) {
            try { upstream.exchange(q, "google"); throw new AssertionError(why); }
            catch (IOException expected) { check(c.disconnected, why + " and releases connection"); }
        }
    }
    public static void main(String[] args) throws Exception {
        DnsPacket.Query q = DnsCacheTest.query("example.com", 533);
        byte[] answer = DnsCacheTest.answer(q, 60);
        Connection c = new Connection(answer); final Connection good = c;
        try (DnsUpstream upstream = new DnsUpstream(url -> {
            check(url.toString().equals("https://cloudflare-dns.com/dns-query"), "Cloudflare fixed HTTPS endpoint"); return good;
        })) {
            byte[] received = upstream.exchange(q, "cloudflare");
            check(Arrays.equals(answer, received), "binary DNS payload returned intact");
            check(Arrays.equals(q.dns, good.sent.toByteArray()), "POST contains raw DNS query");
            check(good.getRequestMethod().equals("POST") && "application/dns-message".equals(good.getRequestProperty("Content-Type")), "RFC 8484 headers and method");
            check(!good.getInstanceFollowRedirects() && !good.getUseCaches(), "redirect and local HTTP cache disabled");
            check(good.getConnectTimeout() <= 2500 && good.getReadTimeout() <= 2500 && good.disconnected, "timeouts and connection release");
            check(good.getSSLSocketFactory() == HttpsURLConnection.getDefaultSSLSocketFactory(), "TLS factory remains platform default");
            check(good.getHostnameVerifier() == HttpsURLConnection.getDefaultHostnameVerifier(), "hostname verifier remains platform default");
        }
        c = new Connection(answer); c.type = "text/html"; reject(q, c, "reject HTML captive portal");
        c = new Connection(answer); c.status = 302; reject(q, c, "never follow redirect or downgrade");
        c = new Connection(answer); c.length = DnsPacket.MAX_DNS + 1; reject(q, c, "reject announced oversized body");
        c = new Connection(new byte[DnsPacket.MAX_DNS + 1]); reject(q, c, "stream length bounded without content length");
        c = new Connection(answer); c.length = answer.length + 1; reject(q, c, "reject short HTTP body");
        byte[] bad = answer.clone(); bad[0] ^= 1; reject(q, new Connection(bad), "reject wrong query id");
        bad = answer.clone(); bad[2] |= 2; reject(q, new Connection(bad), "reject truncated DNS over HTTPS");
        bad = answer.clone(); DnsCacheTest.put16(bad, q.dns.length + 10, 3); reject(q, new Connection(bad), "reject malformed answer record");
        Connection aged = new Connection(answer); aged.responseHeaders.put("Age", "25");
        try (DnsUpstream upstream = new DnsUpstream(url -> aged)) {
            check(DnsCacheTest.u32(upstream.exchange(q, "google"), q.dns.length + 6) == 35, "HTTP Age subtracted from DNS TTL");
        }
        Connection throttled = new Connection(answer); throttled.status = 429; throttled.responseHeaders.put("Retry-After", "30");
        int[] connections = {0};
        try (DnsUpstream upstream = new DnsUpstream(url -> { connections[0]++; return throttled; })) {
            for (int i = 0; i < 2; i++) try { upstream.exchange(q, "google"); throw new AssertionError("429 not rejected"); } catch (IOException expected) { }
            check(connections[0] == 1, "Retry-After prevents request flood");
        }
        try { DnsUpstream.endpoint("http://attacker.invalid"); throw new AssertionError("custom endpoint accepted"); }
        catch (IOException expected) { check(true, "only explicit trusted provider names accepted"); }
        DnsUpstream stopped = new DnsUpstream(url -> { throw new AssertionError("connection after close"); }); stopped.close();
        try { stopped.exchange(q, "google"); throw new AssertionError("closed transport accepted"); }
        catch (IOException expected) { check(true, "stopped transport cannot open connection"); }
        CountDownLatch connecting = new CountDownLatch(1), cancelled = new CountDownLatch(1);
        Connection waiting = new Connection(answer) {
            @Override public OutputStream getOutputStream() throws IOException {
                connecting.countDown();
                try { if (!cancelled.await(2, TimeUnit.SECONDS)) throw new IOException("test timed out"); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IOException("interrupted"); }
                throw new IOException("disconnected");
            }
            @Override public void disconnect() { super.disconnect(); cancelled.countDown(); }
        };
        DnsUpstream cancellable = new DnsUpstream(url -> waiting);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try { cancellable.exchange(q, "google"); failure.set(new AssertionError("cancelled query succeeded")); }
            catch (IOException expected) { }
            catch (Throwable unexpected) { failure.set(unexpected); }
        });
        worker.start(); check(connecting.await(2, TimeUnit.SECONDS), "request began connection");
        cancellable.close(); worker.join(2000);
        check(!worker.isAlive() && waiting.disconnected && failure.get() == null, "closing transport disconnects an in-flight request and releases worker");
        System.out.println("PASS: DNS HTTPS transport " + checks + " assertions");
    }
}
