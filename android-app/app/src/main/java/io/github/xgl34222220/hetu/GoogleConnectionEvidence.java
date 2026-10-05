package io.github.xgl34222220.hetu;

import java.util.Locale;
import java.util.Set;

/** Read-only connection classification. No token, cookie, account or HTTP-body collection. */
final class GoogleConnectionEvidence {
    static final String LIMITATION = "仅连接元数据：Google 可达、204 响应或双向流量均不能证明帐号认证成功；未读取帐号、Cookie 或认证令牌。";
    private static final String[] DOMAINS = {"google.com", "googleapis.com", "gstatic.com", "googleusercontent.com", "ggpht.com", "gvt1.com"};
    private static final String[] PACKAGES = {"com.google.android.gms", "com.google.android.gsf", "com.android.vending"};

    static boolean matches(String process, String host, int uid, Set<Integer> knownUids) {
        if (uid >= 0 && knownUids != null && knownUids.contains(uid)) return true;
        String name = process == null ? "" : process.trim().toLowerCase(Locale.ROOT);
        for (String pkg : PACKAGES) if (name.equals(pkg) || name.startsWith(pkg + ":")) return true;
        String domain = host == null ? "" : host.trim().toLowerCase(Locale.ROOT);
        if (domain.endsWith(".")) domain = domain.substring(0, domain.length() - 1);
        // Metadata host only; URLs, ports and suffix lookalikes are not Google identity.
        if (domain.indexOf('/') >= 0 || domain.indexOf(':') >= 0 || domain.indexOf('@') >= 0) return false;
        for (String suffix : DOMAINS) if (domain.equals(suffix) || domain.endsWith("." + suffix)) return true;
        return false;
    }
}
