package io.github.xgl34222220.hetu;

import android.content.Context;
import android.os.Build;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Installs and controls the Root Mihomo TPROXY runtime without touching the user's original YAML. */
final class RootTproxyManager {
    private static final String ROOT = "/data/adb/hetu";
    private static final String BIN = ROOT + "/bin/mihomo";
    private static final String CONFIG = ROOT + "/config.yaml";
    private static final String SCRIPT = ROOT + "/hetu-route.sh";
    private static final Pattern TOP_TPROXY = Pattern.compile("(?m)^\\s*tproxy-port\\s*:\\s*([0-9]{1,5})\\s*(?:#.*)?$");
    private static final Pattern PORT = Pattern.compile("^\\s*port\\s*:\\s*([0-9]{1,5})\\s*(?:#.*)?$");
    private static final Pattern TYPE = Pattern.compile("^\\s*type\\s*:\\s*[\"']?tproxy[\"']?\\s*(?:#.*)?$", Pattern.CASE_INSENSITIVE);

    private final Context context;

    RootTproxyManager(Context context) { this.context = context.getApplicationContext(); }

    int detectPort(String yaml) throws IOException {
        Matcher top = TOP_TPROXY.matcher(yaml == null ? "" : yaml);
        if (top.find()) return checkedPort(top.group(1));

        String[] lines = (yaml == null ? "" : yaml).split("\\r?\\n", -1);
        int itemIndent = -1;
        int candidatePort = -1;
        boolean tproxy = false;
        for (String line : lines) {
            String trim = line.trim();
            if (trim.startsWith("- ")) {
                if (tproxy && candidatePort > 0) return candidatePort;
                itemIndent = indent(line);
                candidatePort = -1;
                tproxy = false;
                String rest = trim.substring(2).trim();
                if (TYPE.matcher(rest).matches()) tproxy = true;
                Matcher pm = PORT.matcher(rest);
                if (pm.matches()) candidatePort = checkedPort(pm.group(1));
                continue;
            }
            if (itemIndent >= 0 && !trim.isEmpty() && indent(line) <= itemIndent) {
                if (tproxy && candidatePort > 0) return candidatePort;
                itemIndent = -1; candidatePort = -1; tproxy = false;
            }
            if (itemIndent >= 0) {
                if (TYPE.matcher(trim).matches()) tproxy = true;
                Matcher pm = PORT.matcher(trim);
                if (pm.matches()) candidatePort = checkedPort(pm.group(1));
            }
        }
        if (tproxy && candidatePort > 0) return candidatePort;
        throw new IOException("配置里没有找到 TPROXY 监听端口；需要 tproxy-port 或 listeners 中 type: tproxy + port");
    }

    JSONObject preflight(String yaml) throws Exception { return preflight(yaml, "enable"); }
    JSONObject preflight(String yaml, String ipv6Mode) throws Exception {
        int port = detectPort(yaml);
        installRuntimeFiles(yaml, false);
        return runJson("preflight", String.valueOf(port), normalizeIpv6Mode(ipv6Mode));
    }

    JSONObject start(String yaml) throws Exception { return start(yaml, "enable"); }
    JSONObject start(String yaml, String ipv6Mode) throws Exception {
        int port = detectPort(yaml);
        installRuntimeFiles(yaml, true);
        JSONObject result = runJson("start", BIN, CONFIG, String.valueOf(port), normalizeIpv6Mode(ipv6Mode));
        if (!result.optBoolean("ok")) throw new IOException(result.optString("message", "Root TPROXY 启动失败"));
        return result;
    }

    JSONObject stop() throws Exception { return runJson("stop"); }
    JSONObject status() throws Exception { return runJson("status"); }

    private JSONObject runJson(String... args) throws Exception {
        RootBridge.requireWorkerThread();
        StringBuilder command = new StringBuilder("exec ").append(RootBridge.quote(SCRIPT));
        for (String arg : args) command.append(' ').append(RootBridge.quote(arg));
        RootBridge.Result result = RootBridge.rootShell(context, command.toString(), 45_000L);
        JSONObject json;
        try { json = RootBridge.parseObject(result.output.trim()); }
        catch (Exception malformed) { throw new IOException(result.output.isEmpty() ? "Root TPROXY 没有返回状态" : result.output); }
        if (result.code != 0) throw new IOException(json.optString("message", "Root TPROXY 命令失败，退出码 " + result.code));
        return json;
    }

    private void installRuntimeFiles(String yaml, boolean includeConfig) throws Exception {
        RootBridge.requireWorkerThread();
        File staging = new File(context.getCacheDir(), "tproxy-stage");
        if (!staging.exists() && !staging.mkdirs()) throw new IOException("无法创建 TPROXY 临时目录");
        File script = new File(staging, "hetu-route.sh");
        copyAsset("hetu-route.sh", script);
        File binary = new File(staging, "mihomo");
        copyAsset(rootBinaryAsset(), binary);
        File config = new File(staging, "config.yaml");
        if (includeConfig) Files.write(config.toPath(), yaml.getBytes(StandardCharsets.UTF_8));

        StringBuilder command = new StringBuilder()
                .append("set -e; mkdir -p ").append(RootBridge.quote(ROOT + "/bin")).append(' ')
                .append(RootBridge.quote(ROOT + "/run")).append("; ")
                .append("cp ").append(RootBridge.quote(script.getAbsolutePath())).append(' ').append(RootBridge.quote(SCRIPT)).append("; ")
                .append("cp ").append(RootBridge.quote(binary.getAbsolutePath())).append(' ').append(RootBridge.quote(BIN)).append("; ")
                .append("chmod 700 ").append(RootBridge.quote(SCRIPT)).append(' ').append(RootBridge.quote(BIN)).append("; ")
                .append("chown 0:0 ").append(RootBridge.quote(SCRIPT)).append(' ').append(RootBridge.quote(BIN))
                .append("; rm -f ").append(RootBridge.quote(ROOT + "/tproxy-root.sh")).append(' ').append(RootBridge.quote(ROOT + "/proxy-root.sh"));
        if (includeConfig) command.append("; cp ").append(RootBridge.quote(config.getAbsolutePath())).append(' ').append(RootBridge.quote(CONFIG))
                .append("; chmod 600 ").append(RootBridge.quote(CONFIG)).append("; chown 0:0 ").append(RootBridge.quote(CONFIG));
        RootBridge.Result installed = RootBridge.rootShell(context, command.toString(), 45_000L);
        if (!installed.ok()) throw new IOException("无法安装 Root TPROXY 运行文件：" + installed.output.trim());
    }

    private String rootBinaryAsset() throws IOException {
        for (String abi : Build.SUPPORTED_ABIS) {
            String normalized = abi.toLowerCase(Locale.ROOT);
            if (normalized.equals("arm64-v8a")) return "mihomo-root/arm64-v8a/mihomo";
            if (normalized.equals("x86_64")) return "mihomo-root/x86_64/mihomo";
        }
        throw new IOException("当前 CPU 架构暂未提供 Root Mihomo：" + java.util.Arrays.toString(Build.SUPPORTED_ABIS));
    }

    private void copyAsset(String name, File out) throws IOException {
        try (InputStream input = context.getAssets().open(name); FileOutputStream output = new FileOutputStream(out, false)) {
            byte[] buffer = new byte[32 * 1024];
            int n;
            while ((n = input.read(buffer)) != -1) output.write(buffer, 0, n);
            output.getFD().sync();
        }
        if (!out.setReadable(true, true) || !out.setExecutable(true, true)) throw new IOException("无法设置临时运行文件权限");
    }

    private static String normalizeIpv6Mode(String mode) throws IOException {
        if ("enable".equals(mode) || "bypass".equals(mode) || "disable".equals(mode)) return mode;
        throw new IOException("IPv6 模式无效");
    }

    private static int checkedPort(String raw) throws IOException {
        int port;
        try { port = Integer.parseInt(raw); }
        catch (NumberFormatException invalid) { throw new IOException("TPROXY 端口格式无效"); }
        if (port < 1 || port > 65535) throw new IOException("TPROXY 端口超出范围");
        return port;
    }

    private static int indent(String line) {
        int n = 0;
        while (n < line.length() && Character.isWhitespace(line.charAt(n))) n++;
        return n;
    }
}
