package io.github.xgl34222220.hetu;

import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

/** Narrow subscription exceptions for message transport, never an app-wide DIRECT rule. */
final class MessagingFilterPolicy {
    // Transport hosts documented by Tencent/mars hostorder_test.cc and H3C's
    // Cloudnet WeChat authentication guide. Advertising/telemetry hosts are excluded.
    private static final String[] TRANSPORT_HOSTS = {
            "long.weixin.qq.com", "short.weixin.qq.com", "sh2tjlong.weixin.qq.com",
            "szlong.weixin.qq.com", "szshort.weixin.qq.com", "szextshort.weixin.qq.com",
            "extshort.weixin.qq.com", "minorshort.weixin.qq.com", "dns.weixin.qq.com"
    };

    static Set<String> subscriptionExceptions(Collection<String> userBlocks) {
        Set<String> result = new HashSet<>(Arrays.asList(TRANSPORT_HOSTS));
        // Exported exceptions match suffixes. A user block anywhere inside an
        // exception would otherwise be silently bypassed by that parent exception.
        if (userBlocks != null) result.removeIf(host -> blockedBy(userBlocks, host));
        return result;
    }

    private static boolean blockedBy(Collection<String> blocks, String host) {
        for (String block : blocks) {
            if (block != null && !block.isEmpty()
                    && (host.equals(block) || host.endsWith("." + block) || block.endsWith("." + host))) return true;
        }
        return false;
    }
}
