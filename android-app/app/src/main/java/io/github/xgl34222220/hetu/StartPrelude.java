package io.github.xgl34222220.hetu;

/**
 * Shell fragments that let a manual start run in ONE Root invocation: cancellation receipt,
 * optional pre-start hook, file deployment, then `exec hetu-root.sh start`.
 *
 * Every fragment either falls through to the next one or prints a single refusal line
 * ({@link #ABORT}) and exits before the native transaction is dispatched, so the caller knows
 * nothing native ran. Pure Java (no Android API) so it is host-testable.
 */
final class StartPrelude {
    private StartPrelude() {}

    static final String ABORT = "HETU_PRENATIVE";
    static final String SCRIPT_DIR = "/data/adb/hetu/scripts";
    static final String PRE_START = SCRIPT_DIR + "/pre-start.sh";
    static final String SCRIPT_LOG = "/data/adb/hetu/run/scripts.log";

    /**
     * Reads hetu-root.sh's cancellation marker first thing in the start shell and exports it
     * exactly like the former separate read: empty, or "digits-digits" of at most 64 chars.
     */
    static String cancelReceipt(String markerPath) {
        String m = quote(markerPath);
        return "hetu_t=''; if [ -e " + m + " ]; then hetu_t=$(cat " + m + ") || { printf '" + ABORT + "\\ttoken\\n'; exit 0; }; fi; "
                + "case \"$hetu_t\" in '') ;; *[!0-9-]*|-*|*-|*-*-*) printf '" + ABORT + "\\ttoken\\n'; exit 0;; *-*) ;; "
                + "*) printf '" + ABORT + "\\ttoken\\n'; exit 0;; esac; "
                + "[ \"${#hetu_t}\" -le 64 ] || { printf '" + ABORT + "\\ttoken\\n'; exit 0; }; "
                + "export HETU_START_CANCEL_TOKEN=\"$hetu_t\"; ";
    }

    /**
     * The pre-start hook, same environment, log and 12 s limit as ProxyScriptHooks.run, but
     * without any extra Root shell: when no hook exists this is one `[ -s ]` test.
     */
    static String preStartHook(String mode, String config) {
        String path = quote(PRE_START);
        return "if [ -s " + path + " ]; then ( "
                + "mkdir -p " + quote(SCRIPT_DIR) + " /data/adb/hetu/run && chmod 700 " + quote(SCRIPT_DIR) + " || exit 45; "
                + "export HETU_HOOK='pre-start'; "
                + "export HETU_MODE=" + quote(mode) + "; "
                + "export HETU_CONFIG=" + quote(config) + "; "
                + "export HETU_BASE=/data/adb/hetu; "
                + "export HETU_RUN_DIR=/data/adb/hetu/run; "
                + "export HETU_SCRIPT_DIR=" + quote(SCRIPT_DIR) + "; "
                + "{ printf '\\n[%s] hook=%s mode=%s config=%s\\n' \"$(date '+%Y-%m-%d %H:%M:%S')\" 'pre-start' "
                + quote(mode) + " " + quote(config) + "; "
                + "if command -v timeout >/dev/null 2>&1; then timeout 12 sh " + path + "; else sh " + path + "; fi; "
                + "} >> " + quote(SCRIPT_LOG) + " 2>&1 ); hetu_h=$?; "
                + "[ \"$hetu_h\" = 0 ] || { printf '" + ABORT + "\\thook\\t%s\\n' \"$hetu_h\"; exit 0; }; fi; ";
    }

    /** RootProxyManager's deployment command in its own `sh -c`, so its `set -e` really applies. */
    static String deployment(String command) {
        return "hetu_d=$(sh -c " + quote(command) + " 2>&1); hetu_rc=$?; "
                + "if [ \"$hetu_rc\" != 0 ]; then printf '" + ABORT + "\\tdeploy\\t%s\\n' "
                + "\"$(printf '%s' \"$hetu_d\" | tr '\\r\\n\\t' '   ' | head -c 300)\"; exit 0; fi; ";
    }

    /** Settings.Global private_dns_mode as hetu-root.sh's whitelist understands it. */
    static String privateDnsMode(String raw) {
        String value = raw == null ? "" : raw.trim();
        switch (value) {
            case "off": case "opportunistic": case "hostname": return value;
            default: return "unknown";
        }
    }

    /** The user-facing reason when the prelude refused before the native start, else null. */
    static String refusal(String output) {
        if (output == null) return null;
        String text = output.trim();
        int line = text.lastIndexOf('\n');
        String last = (line >= 0 ? text.substring(line + 1) : text).trim();
        if (!last.startsWith(ABORT + "\t")) return null;
        String[] parts = last.split("\t", 3);
        String kind = parts.length > 1 ? parts[1] : "";
        String detail = parts.length > 2 ? parts[2].trim() : "";
        switch (kind) {
            case "token": return "无法确认启动撤销代次，已拒绝启动";
            case "hook":
                if ("124".equals(detail)) return "脚本执行超时，已停止本次操作";
                if ("45".equals(detail)) return "无法创建脚本目录";
                return "脚本执行失败（" + (detail.matches("[0-9]{1,3}") ? detail : "?") + "），请查看 scripts.log";
            case "deploy": return "无法安装 Root 运行文件：" + (detail.isEmpty() ? "未知 Root 错误" : detail);
            default: return "启动前准备未完成，已拒绝启动";
        }
    }

    static String quote(String value) {
        if (value == null) value = "";
        if (value.indexOf('\0') >= 0) throw new IllegalArgumentException("Invalid shell value");
        return "'" + value.replace("'", "'\\''") + "'";
    }
}
