package io.github.xgl34222220.hetu;

import android.content.Context;
import android.os.Build;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/** One bounded local report. Exception messages and user configuration are excluded. */
final class AppCrashReport {
    private static final int LIMIT = 16384;
    private static volatile String screen = "startup";
    static void screen(String value) {
        screen = value == null ? "unknown" : value.substring(0, Math.min(value.length(), 160));
    }
    static Thread.UncaughtExceptionHandler handler(Context context, Thread.UncaughtExceptionHandler previous) {
        Context app = context.getApplicationContext();
        return (thread, error) -> {
            try { save(app, thread, error); } catch (Exception ignored) { }
            finally { previous.uncaughtException(thread, error); }
        };
    }
    static void save(Context context, Thread thread, Throwable error) throws java.io.IOException {
        StringBuilder text = new StringBuilder("time=").append(System.currentTimeMillis())
                .append("\napp=").append(BuildConfig.VERSION_NAME).append(" (").append(BuildConfig.VERSION_CODE).append(")")
                .append("\nandroid=").append(Build.VERSION.SDK_INT).append("\nmodel=").append(Build.MODEL)
                .append("\nscreen=").append(screen).append("\nthread=").append(thread.getName()).append('\n');
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        int depth = 0;
        for (Throwable current = error; current != null && seen.add(current) && depth < 8; current = current.getCause(), depth++) {
            text.append(depth == 0 ? "exception=" : "cause=").append(current.getClass().getName()).append('\n');
            StackTraceElement[] trace = current.getStackTrace();
            for (int i = 0; i < Math.min(trace.length, 48) && text.length() < LIMIT; i++)
                text.append("  at ").append(trace[i]).append('\n');
        }
        byte[] data = text.substring(0, Math.min(text.length(), LIMIT / 2)).getBytes(StandardCharsets.UTF_8);
        try (FileOutputStream stream = new FileOutputStream(new File(context.getFilesDir(), "last-app-crash.txt"))) {
            stream.write(data, 0, Math.min(data.length, LIMIT));
        }
    }
    static String read(Context context) {
        File file = new File(context.getFilesDir(), "last-app-crash.txt");
        if (!file.isFile()) return "尚无已记录的应用闪退";
        try (java.io.InputStream input = Files.newInputStream(file.toPath())) {
            byte[] buffer = new byte[LIMIT];
            int total = 0, count;
            while (total < buffer.length && (count = input.read(buffer, total, buffer.length - total)) > 0) total += count;
            return new String(buffer, 0, total, StandardCharsets.UTF_8);
        } catch (java.io.IOException error) { return "上次闪退记录暂不可读"; }
    }
}
