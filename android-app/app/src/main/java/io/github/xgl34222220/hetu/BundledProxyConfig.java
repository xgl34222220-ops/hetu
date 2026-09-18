package io.github.xgl34222220.hetu;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Base64;
import java.util.zip.GZIPInputStream;

/** Public-safe bundled Mihomo profile. Private subscription URLs are deliberately absent. */
final class BundledProxyConfig {
    static InputStream open() throws IOException {
        try {
            String data = BundledProxyConfigData1.DATA +
                    BundledProxyConfigData2.DATA +
                    BundledProxyConfigData3.DATA +
                    BundledProxyConfigData4.DATA +
                    BundledProxyConfigData5.DATA +
                    BundledProxyConfigData6.DATA;
            return new GZIPInputStream(new ByteArrayInputStream(Base64.getDecoder().decode(data)));
        } catch (IllegalArgumentException e) {
            throw new IOException("内置代理配置损坏", e);
        }
    }
}
