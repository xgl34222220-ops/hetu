package io.github.xgl34222220.hetu;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/** Limits document-provider reads before allocation or any existing icon is changed. */
final class PolicyIconInput {
    static final int MAX_BYTES = 1_572_864;

    private PolicyIconInput() {}

    static byte[] read(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(8192);
        byte[] buffer = new byte[8192];
        while (true) {
            // Consume at most one byte beyond the limit, including providers with no size metadata.
            int count = input.read(buffer, 0, Math.min(buffer.length, MAX_BYTES - output.size() + 1));
            if (count < 0) return output.toByteArray();
            if (count == 0) {
                int one = input.read();
                if (one < 0) return output.toByteArray();
                if (output.size() == MAX_BYTES) throw oversized();
                output.write(one);
            } else {
                if (count > MAX_BYTES - output.size()) throw oversized();
                output.write(buffer, 0, count);
            }
        }
    }

    private static IllegalArgumentException oversized() {
        return new IllegalArgumentException("图标不能超过 1.5 MiB");
    }
}
