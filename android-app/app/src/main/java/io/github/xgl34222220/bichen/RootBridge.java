package io.github.xgl34222220.bichen;

import android.content.Context;
import android.os.Looper;
import android.os.SystemClock;

import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/** Bounded, background-thread-only access to the installed module CLI. */
public final class RootBridge {
    public static final String MODULE_DIR = "/data/adb/modules/bichen";
    public static final String CLI = MODULE_DIR + "/bin/bichen";
    public static final long DEFAULT_TIMEOUT_MS = 30_000L;
    private static final int MAX_OUTPUT_BYTES = 32 * 1024 * 1024;
    private static final String PRESENT = "__BICHEN_MODULE_PRESENT__";
    private static final String ABSENT = "__BICHEN_MODULE_ABSENT__";

    private RootBridge() { }

    public static final class Result {
        public final int code;
        public final String output;

        public Result(int code, String output) {
            this.code = code;
            this.output = output == null ? "" : output;
        }

        public boolean ok() { return code == 0; }
    }

    public static Result run(Context context, String... args) {
        return run(context, DEFAULT_TIMEOUT_MS, args);
    }

    public static Result run(Context context, long timeoutMs, String... args) {
        requireWorkerThread();
        StringBuilder command = new StringBuilder("exec ").append(quote(CLI));
        if (args != null) {
            for (String arg : args) command.append(' ').append(quote(arg));
        }
        return rootShell(context, command.toString(), timeoutMs);
    }

    /** Missing Root access is an error with UNKNOWN installation state, never "not installed". */
    public static JSONObject status(Context context) {
        requireWorkerThread();
        String command = "if [ \"$(id -u)\" != 0 ]; then "
                + "printf '%s\\n' 'Root 授权未授予 UID 0'; exit 126; fi\n"
                + "if [ ! -d " + quote(MODULE_DIR) + " ]; then\n"
                + "  printf '%s\\n' '" + ABSENT + "'\n"
                + "  if [ -d /data/adb/modules_update/bichen ]; then "
                + "printf '%s\\n' '{\"ok\":true,\"installed\":false,\"rootGranted\":true,\"pendingReboot\":true,\"message\":\"模块已暂存，请重启后检查状态\"}'; "
                + "else printf '%s\\n' '{\"ok\":true,\"installed\":false,\"rootGranted\":true,\"pendingReboot\":false,\"message\":\"尚未安装模块\"}'; fi\n"
                + "  exit 0\nfi\n"
                + "bc_disabled=0; bc_removed=0; bc_pending=0\n"
                + "[ ! -e " + quote(MODULE_DIR + "/disable") + " ] || bc_disabled=1\n"
                + "[ ! -e " + quote(MODULE_DIR + "/remove") + " ] || bc_removed=1\n"
                + "[ ! -d /data/adb/modules_update/bichen ] || bc_pending=1\n"
                + "printf '%s%s%s%s\\n' '" + PRESENT + "' \"$bc_disabled\" \"$bc_removed\" \"$bc_pending\"\n"
                + "if [ ! -f " + quote(CLI) + " ]; then "
                + "printf '%s\\n' '{\"ok\":false,\"message\":\"模块目录存在，但去广告引擎缺失；请重新安装完整模块\"}'; exit 127; fi\n"
                + "exec " + quote(CLI) + " status";
        Result result = rootShell(context, command, DEFAULT_TIMEOUT_MS);
        String raw = result.output.trim();
        boolean installed = raw.startsWith(PRESENT);
        boolean absent = raw.startsWith(ABSENT);
        String flags = "";
        if (installed || absent) {
            int end = raw.indexOf('\n');
            String marker = end < 0 ? raw : raw.substring(0, end).trim();
            if (installed) flags = marker.substring(PRESENT.length());
            raw = end < 0 ? "" : raw.substring(end + 1).trim();
        }
        JSONObject response;
        try {
            response = parseObject(raw);
        } catch (Exception invalidJson) {
            response = new JSONObject();
            put(response, "ok", false);
            put(response, "message", raw.isEmpty() ? "Root 命令没有返回可读状态" : raw);
        }
        if (installed) {
            put(response, "installed", true);
            put(response, "rootGranted", true);
            if (flags.length() == 3) {
                put(response, "moduleDisabled", flags.charAt(0) == '1');
                put(response, "moduleRemovalPending", flags.charAt(1) == '1');
                put(response, "pendingReboot", flags.charAt(2) == '1');
            }
        } else if (absent && result.code == 0) {
            put(response, "installed", false);
            put(response, "rootGranted", true);
        } else {
            response.remove("installed");
            // We cannot know whether the framework is absent, disabled or denied by a profile.
            put(response, "rootGranted", false);
        }
        put(response, "exitCode", result.code);
        if (result.code != 0 || !response.optBoolean("ok", false)) {
            put(response, "ok", false);
            // Preserve full evidence in details, not as a page-sized error card.
            put(response, "details", result.output);
            String detail = response.optString("message", "");
            if (result.code == 124) {
                detail = installed ? "模块已安装，但 Root 状态读取超时；这不是模块包刷写失败。"
                        : "Root 状态读取超时，暂时无法确认安装状态。";
            } else if (detail.isEmpty() || detail.startsWith("{")) {
                detail = "Root 状态读取未正常完成，请查看诊断详情。";
            }
            if (detail.length() > 160) detail = detail.substring(0, 160) + "…";
            put(response, "error", "无法读取模块状态（退出码 " + result.code + "）：" + detail);
        }
        return response;
    }

    static JSONObject parseObject(String raw) throws Exception {
        JSONTokener tokener = new JSONTokener(raw);
        Object value = tokener.nextValue();
        if (!(value instanceof JSONObject) || tokener.nextClean() != 0) {
            throw new IOException("命令没有返回完整 JSON 对象");
        }
        return (JSONObject) value;
    }

    static void put(JSONObject object, String key, Object value) {
        try { object.put(key, value); }
        catch (Exception impossible) { throw new IllegalArgumentException(impossible); }
    }

    static String quote(String value) {
        if (value == null || value.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("Shell 参数不能为空或包含 NUL");
        }
        return "'" + value.replace("'", "'\\''") + "'";
    }

    static void requireWorkerThread() {
        if (Looper.getMainLooper() != null && Looper.myLooper() == Looper.getMainLooper()) {
            throw new IllegalStateException("Root 和安装操作必须在后台线程执行，不能阻塞界面");
        }
    }

    /** Shell text is internal-only. User values must go through quote(). */
    static Result rootShell(Context context, String command, long timeoutMs) {
        requireWorkerThread();
        if (timeoutMs < 1_000L || timeoutMs > 300_000L) {
            throw new IllegalArgumentException("Root 超时必须在 1 秒至 5 分钟之间");
        }
        long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        Process process = null;
        OutputReader reader = null;
        Thread readerThread = null;
        try {
            // Run the command in the foreground under the framework's native timeout.
            // The old mksh/background watchdog could hold su/its pipe open after JSON
            // was printed, making a completed status query look like a 30s timeout.
            long seconds = Math.max(1L, (timeoutMs - 2_000L) / 1_000L);
            String script = RootShellCommand.build(command, seconds);
            process = new ProcessBuilder("su", "-c", script).redirectErrorStream(true).start();
            process.getOutputStream().close();
            reader = new OutputReader(process.getInputStream());
            readerThread = new Thread(reader, "bichen-root-output");
            readerThread.setDaemon(true);
            readerThread.start();
            long remaining = Math.max(1L, deadline - SystemClock.elapsedRealtime());
            if (!process.waitFor(remaining, TimeUnit.MILLISECONDS)) {
                process.destroy();
                process.destroyForcibly();
                return new Result(124, reader.text() + "\n操作超时（" + timeoutMs / 1000
                        + " 秒），已请求终止进程；不能确认操作完成，请重新检查模块状态。");
            }
            int code = process.exitValue();
            remaining = deadline - SystemClock.elapsedRealtime();
            if (remaining > 0) readerThread.join(Math.min(1_500L, remaining));
            if (readerThread.isAlive()) {
                process.destroyForcibly();
                String partial = reader.text();
                if (SystemClock.elapsedRealtime() >= deadline) {
                    return new Result(124, partial
                            + "\n操作超时，已请求终止进程；请检查模块实际状态。");
                }
                return new Result(125, partial + "\n命令输出流未关闭，无法确认完整结果。");
            }
            String output = reader.text();
            if (reader.overflow) {
                return new Result(125, output + "\n命令输出超过 32 MiB，结果已截断，未作为成功处理。");
            }
            if (reader.failure != null) {
                return new Result(125, output + "\n读取 Root 输出失败：" + reader.failure);
            }
            return new Result(code, output);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            if (process != null) { process.destroy(); process.destroyForcibly(); }
            return new Result(130, (reader == null ? "" : reader.text()) + "\n操作已中断，请检查模块实际状态。");
        } catch (IOException error) {
            return new Result(126, "无法启动 Root 命令：" + error.getMessage()
                    + "。请检查当前 Root 管理器是否已授予辟尘权限。");
        } finally {
            if (process != null) closeLater(process);
        }
    }

    private static void closeLater(final Process process) {
        // Closing an inherited pipe can itself wait for an unrelated surviving child.
        Thread closer = new Thread(new Runnable() {
            @Override public void run() {
                try { process.getInputStream().close(); } catch (IOException ignored) { }
                try { process.getErrorStream().close(); } catch (IOException ignored) { }
                try { process.getOutputStream().close(); } catch (IOException ignored) { }
            }
        }, "bichen-root-close");
        closer.setDaemon(true);
        closer.start();
    }

    private static final class OutputReader implements Runnable {
        private final InputStream stream;
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream(8_192);
        volatile boolean overflow;
        volatile String failure;

        OutputReader(InputStream stream) { this.stream = stream; }

        @Override public void run() {
            byte[] buffer = new byte[8_192];
            try {
                int read;
                while ((read = stream.read(buffer)) != -1) {
                    synchronized (bytes) {
                        int keep = Math.min(read, MAX_OUTPUT_BYTES - bytes.size());
                        if (keep > 0) bytes.write(buffer, 0, keep);
                        if (keep < read) overflow = true;
                    }
                }
            } catch (IOException error) {
                failure = error.toString();
            }
        }

        String text() {
            synchronized (bytes) {
                return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
            }
        }
    }
}
