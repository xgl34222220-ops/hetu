package io.github.xgl34222220.hetu;

public final class ProxyLogLinesTest {
    private static int checks;
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
        checks++;
    }

    public static void main(String[] args) {
        String hit = "info --> ads.example:443 match RuleSet(hetu-adblock) using REJECT\n";
        ProxyLogLines split = ProxyLogLines.read("", false, hit.substring(0, 21), false);
        check(split.complete.isEmpty(), "incomplete hit must not be counted early");
        split = ProxyLogLines.read(split.pending, split.discarding, hit.substring(21), false);
        check(split.complete.equals(hit) && split.pending.isEmpty(), "split hit is counted once with complete domain");
        String text = hit + "unrelated\r\n" + hit + "partial";
        StringBuilder recovered = new StringBuilder();
        split = ProxyLogLines.read("", false, "", false);
        for (int i = 0; i < text.length(); i++) {
            split = ProxyLogLines.read(split.pending, split.discarding, text.substring(i, i + 1), false);
            recovered.append(split.complete);
        }
        check(recovered.toString().equals(hit + "unrelated\r\n" + hit), "one-character chunks preserve every complete line without duplicates");
        check(split.pending.equals("partial"), "remaining fragment is retained for next poll");
        split = ProxyLogLines.read(split.pending, false, hit, true);
        check(split.complete.equals(hit), "truncated log does not join an old fragment onto new session");
        split = ProxyLogLines.read("", false, "x".repeat(65537), false);
        check(split.pending.isEmpty() && split.discarding, "oversized unfinished line is not persisted");
        split = ProxyLogLines.read(split.pending, split.discarding, "ignored", false);
        check(split.complete.isEmpty() && split.discarding, "discard continues until a real line boundary");
        split = ProxyLogLines.read(split.pending, split.discarding, "ignored\n" + hit, false);
        check(split.complete.equals(hit) && !split.discarding, "normal parsing resumes after oversized line");
        check(ProxyAdblockSession.canCommit(3L, 3L, true, 100L, 100L), "current poll can publish counters");
        check(!ProxyAdblockSession.canCommit(3L, 4L, true, 100L, 100L), "restart invalidates old poll even when offsets coincide");
        check(!ProxyAdblockSession.canCommit(3L, 3L, false, 100L, 100L), "stop invalidates in-flight poll");
        check(!ProxyAdblockSession.canCommit(3L, 3L, true, 100L, 200L), "advanced cursor cannot be overwritten by stale poll");
        System.out.println("ProxyLogLinesTest passed: " + checks);
    }
}
