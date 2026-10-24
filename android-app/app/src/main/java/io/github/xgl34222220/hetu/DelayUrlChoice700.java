package io.github.xgl34222220.hetu;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 7.0.0: delay-URL choice. Cloudflare-Worker (edgetunnel) VLESS-WS nodes cannot reach
 * Cloudflare-hosted targets, so a group/health-check URL such as cp.cloudflare.com reports a
 * live node as a timeout. When no custom URL is set and the effective URL is a Cloudflare test
 * target, gstatic is tried after it. Pure Java (no Android types) so it is unit-testable.
 */
final class DelayUrlChoice700 {
    static final String NEUTRAL_URL = "https://www.gstatic.com/generate_204";

    private DelayUrlChoice700() {}

    static boolean isCloudflareTarget(String url) {
        if (url == null) return false;
        String host;
        try { host = new URI(url.trim()).getHost(); } catch (Exception ignored) { return false; }
        if (host == null) return false;
        host = host.toLowerCase(Locale.ROOT);
        if (host.startsWith("[") && host.endsWith("]")) host = host.substring(1, host.length() - 1);
        if (host.endsWith(".")) host = host.substring(0, host.length() - 1);
        return host.equals("cloudflare.com") || host.endsWith(".cloudflare.com")
            || host.equals("1.1.1.1") || host.equals("1.0.0.1")
            || host.equals("2606:4700:4700::1111") || host.equals("2606:4700:4700:0:0:0:0:1111");
    }

    /** Ordered per-node delay URLs: custom only when set; else preferred (+gstatic after a Cloudflare target); else defaults. */
    static List<String> delayUrls(String custom, String preferred, String[] defaults) {
        ArrayList<String> urls = new ArrayList<>();
        String c = custom == null ? "" : custom.trim();
        if (!c.isEmpty()) urls.add(c);
        String p = preferred == null ? "" : preferred.trim();
        if (!p.isEmpty()) {
            if (!urls.contains(p)) urls.add(p);
            if (c.isEmpty() && isCloudflareTarget(p) && !urls.contains(NEUTRAL_URL)) urls.add(NEUTRAL_URL);
        } else {
            for (String item : defaults) if (!urls.contains(item)) urls.add(item);
        }
        return urls;
    }

    /** URL for the one failed-node retry: custom wins; a Cloudflare group URL is swapped for gstatic. */
    static String retryUrl(String custom, String groupUrl, String fallback) {
        String c = custom == null ? "" : custom.trim();
        if (!c.isEmpty()) return c;
        String g = groupUrl == null ? "" : groupUrl.trim();
        if (g.isEmpty()) return fallback;
        return isCloudflareTarget(g) ? NEUTRAL_URL : g;
    }
}
