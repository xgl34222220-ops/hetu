package io.github.xgl34222220.bichen;

import java.io.*;
import java.util.Base64;
import java.util.zip.GZIPInputStream;

/** Sanitized built-in Mihomo template derived from the user-provided TPROXY profile. */
final class BundledProxyConfig {
    static InputStream open() throws IOException {
        try {
            return new GZIPInputStream(new ByteArrayInputStream(Base64.getDecoder().decode(DATA)));
        } catch (IllegalArgumentException e) {
            throw new IOException("内置代理配置损坏", e);
        }
    }

    private static final String DATA =
        "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";
}
