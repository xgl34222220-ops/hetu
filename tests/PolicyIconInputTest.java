package io.github.xgl34222220.hetu;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;

public final class PolicyIconInputTest {
    private static int checks;
    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }

    private static final class CountingStream extends InputStream {
        private final long length;
        private final boolean zeroBulkReads;
        long read;
        boolean closed;
        CountingStream(long length, boolean zeroBulkReads) {
            this.length = length;
            this.zeroBulkReads = zeroBulkReads;
        }
        @Override public int available() { return Integer.MAX_VALUE; }
        @Override public int read() {
            if (read == length) return -1;
            read++;
            return 0x5a;
        }
        @Override public int read(byte[] data, int offset, int count) {
            if (zeroBulkReads) return 0;
            if (read == length) return -1;
            int n = (int)Math.min(count, length - read);
            Arrays.fill(data, offset, offset + n, (byte)0x5a);
            read += n;
            return n;
        }
        @Override public void close() { closed = true; }
    }

    public static void main(String[] ignored) throws Exception {
        for (int length : new int[]{0, 1, 8191, 8192, 8193, PolicyIconInput.MAX_BYTES - 1, PolicyIconInput.MAX_BYTES}) {
            byte[] expected = new byte[length];
            for (int i = 0; i < expected.length; i++) expected[i] = (byte)(i % 251);
            check(Arrays.equals(expected, PolicyIconInput.read(new ByteArrayInputStream(expected))), "exact bytes " + length);
        }
        for (long length : new long[]{PolicyIconInput.MAX_BYTES + 1L, 40L * 1024 * 1024, Long.MAX_VALUE}) {
            CountingStream input = new CountingStream(length, false);
            try {
                PolicyIconInput.read(input);
                throw new AssertionError("oversized input accepted");
            } catch (IllegalArgumentException expected) {
                check(expected.getMessage().equals("图标不能超过 1.5 MiB"), "bounded, non-sensitive error");
                check(input.read == PolicyIconInput.MAX_BYTES + 1L, "must stop at limit plus one");
                check(!input.closed, "owner retains responsibility for closing");
            }
        }
        CountingStream zeroReads = new CountingStream(123, true);
        byte[] small = PolicyIconInput.read(zeroReads);
        check(small.length == 123, "zero-length bulk reads must still make progress");
        check(small[122] == 0x5a, "fallback preserves bytes");
        CountingStream endlessZeroReads = new CountingStream(Long.MAX_VALUE, true);
        try {
            PolicyIconInput.read(endlessZeroReads);
            throw new AssertionError("zero bulk infinite input accepted");
        } catch (IllegalArgumentException expected) {
            check(endlessZeroReads.read == PolicyIconInput.MAX_BYTES + 1L, "single-byte fallback is bounded");
        }
        IOException failure = new IOException("synthetic provider failed");
        InputStream failing = new InputStream() {
            @Override public int read() throws IOException { throw failure; }
        };
        try {
            PolicyIconInput.read(failing);
            throw new AssertionError("provider failure swallowed");
        } catch (IOException actual) {
            check(actual == failure, "provider errors propagate before any file mutation");
        }
        System.out.println("Policy icon bounded input: " + checks + " checks passed");
    }
}
