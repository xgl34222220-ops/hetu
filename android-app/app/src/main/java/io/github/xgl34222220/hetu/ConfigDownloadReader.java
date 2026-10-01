package io.github.xgl34222220.hetu;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/** Bounds untrusted subscription bodies before decoding or writing a configuration. */
final class ConfigDownloadReader {
    // Matches ProxyConfigLibrary's 4 MiB source limit; the host regression checks parity.
    static final int LIMIT_BYTES = 4 * 1024 * 1024;

    static final class Content {
        final byte[] bytes;
        final String text;

        private Content(byte[] bytes, String text) {
            this.bytes = bytes;
            this.text = text;
        }
    }

    private ConfigDownloadReader() {}

    /** The caller owns source. Reads at most LIMIT_BYTES + 1 bytes, regardless of headers. */
    static Content read(InputStream source, long declaredLength, Runnable checkActive) throws IOException {
        checkActive.run();
        if (declaredLength > LIMIT_BYTES) throw tooLarge();
        ByteArrayOutputStream out = new ByteArrayOutputStream(8192);
        byte[] buffer = new byte[8192];
        while (true) {
            checkActive.run();
            int remaining = LIMIT_BYTES - out.size();
            int count = source.read(buffer, 0, Math.min(buffer.length, remaining + 1));
            if (count == -1) break;
            // InputStream implementations should not return zero for a nonempty buffer,
            // but a single-byte fallback prevents spinning if one does.
            if (count == 0) {
                checkActive.run();
                int value = source.read();
                if (value == -1) break;
                if (remaining == 0) throw tooLarge();
                out.write(value);
            } else {
                if (count > remaining) throw tooLarge();
                out.write(buffer, 0, count);
            }
        }
        checkActive.run();
        byte[] bytes = out.toByteArray();
        try {
            String text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
            checkActive.run();
            return new Content(bytes, text);
        } catch (CharacterCodingException invalidText) {
            throw new IOException("配置必须是 UTF-8 文本", invalidText);
        }
    }

    private static IOException tooLarge() {
        return new IOException("配置超过 4 MiB");
    }
}
