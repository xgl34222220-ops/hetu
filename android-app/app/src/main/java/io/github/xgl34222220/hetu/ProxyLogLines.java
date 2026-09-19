package io.github.xgl34222220.hetu;

/** Carries an unfinished line across bounded byte reads, without retaining arbitrary log data. */
final class ProxyLogLines {
    private static final int MAX_PENDING = 65536;
    final String complete;
    final String pending;
    final boolean discarding;

    private ProxyLogLines(String complete, String pending, boolean discarding) {
        this.complete = complete;
        this.pending = pending;
        this.discarding = discarding;
    }

    static ProxyLogLines read(String pending, boolean discarding, String chunk, boolean reset) {
        if (reset) { pending = ""; discarding = false; }
        if (chunk == null) chunk = "";
        if (pending == null) pending = "";
        if (discarding) {
            int newline = chunk.indexOf('\n');
            if (newline < 0) return new ProxyLogLines("", "", true);
            chunk = chunk.substring(newline + 1);
            pending = "";
        }
        String text = pending + chunk;
        int newline = text.lastIndexOf('\n');
        String complete = newline < 0 ? "" : text.substring(0, newline + 1);
        String remainder = text.substring(newline + 1);
        boolean overflow = remainder.length() > MAX_PENDING;
        return new ProxyLogLines(complete, overflow ? "" : remainder, overflow);
    }
}
