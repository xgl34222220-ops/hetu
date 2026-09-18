package io.github.xgl34222220.hetu;

/** Foreground Root dispatch. No shell background watchdog and no /proc children dependency. */
final class RootShellCommand {
    private RootShellCommand() { }

    static String build(String command, long seconds) {
        if (command == null || command.indexOf('\0') >= 0 || seconds < 1 || seconds > 300) {
            throw new IllegalArgumentException("Invalid Root command or timeout");
        }
        String quoted = "'" + command.replace("'", "'\\''") + "'";
        // timeout is the framework's native applet. It reaps/detaches its own timer
        // rather than asking Android's outer shell to wait for a background sleep.
        // Keep the real command exit status: printed JSON is NOT proof of completion.
        return "bc_run() { exec \"$1\" timeout -s TERM -k 1 " + seconds
                + " \"$1\" ash -c " + quoted + "; }\n"
                + "for bc_bb in /data/adb/ksu/bin/busybox /data/adb/ap/bin/busybox /data/adb/magisk/busybox; do\n"
                + "  if [ -x \"$bc_bb\" ]; then bc_run \"$bc_bb\"; fi\ndone\n"
                + "bc_mp=$(magisk --path 2>/dev/null)\n"
                + "if [ -n \"$bc_mp\" ] && [ -x \"$bc_mp/.magisk/busybox\" ]; then bc_run \"$bc_mp/.magisk/busybox\"; fi\n"
                + "if [ -x /system/bin/timeout ]; then exec /system/bin/timeout -s TERM -k 1 "
                + seconds + " /system/bin/sh -c " + quoted + "; fi\n"
                + "printf '%s\\n' 'Root 环境缺少可用的限时执行器，未执行模块操作；请检查 Root 框架 BusyBox。'\nexit 127\n";
    }
}
