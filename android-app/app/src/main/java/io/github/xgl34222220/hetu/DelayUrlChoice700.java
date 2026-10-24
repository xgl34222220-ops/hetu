package io.github.xgl34222220.hetu;

import java.util.List;

/**
 * 7.0.0: delay-URL choice. Cloudflare-Worker (edgetunnel) VLESS-WS nodes cannot reach
 * Cloudflare-hosted targets, so a group/health-check URL such as cp.cloudflare.com reports a
 * live node as a timeout. The implementation lives in MihomoControllerClient (nested) so the
 * controller compiles standalone; this facade keeps the package-level API.
 */
final class DelayUrlChoice700 {
    static final String NEUTRAL_URL = MihomoControllerClient.DelayUrlChoice700.NEUTRAL_URL;

    private DelayUrlChoice700() {}

    static boolean isCloudflareTarget(String url) { return MihomoControllerClient.DelayUrlChoice700.isCloudflareTarget(url); }

    static List<String> delayUrls(String custom, String preferred, String[] defaults) {
        return MihomoControllerClient.DelayUrlChoice700.delayUrls(custom, preferred, defaults);
    }

    static String retryUrl(String custom, String groupUrl, String fallback) {
        return MihomoControllerClient.DelayUrlChoice700.retryUrl(custom, groupUrl, fallback);
    }
}
