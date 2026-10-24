package io.github.xgl34222220.hetu;

import java.util.*;
import org.yaml.snakeyaml.error.YAMLException;

/**
 * Where each startup value came from: listener ports, the DNS listener and mode, and the fake-IP range,
 * each tagged as set by the selected config file, by Hetu's runtime override, or by a default.
 *
 * <p>The lines are recorded at every prepare (the 启动配置 page shows them as 启动诊断, and they are
 * kept in the app's startup-diagnostics log). Reference-cleanup notices additionally go into the private
 * startup copy as {@code # HETU_NOTICE: } comments, which hetu-root.sh copies into core.log right after it
 * truncates the log; without a cleanup the startup copy stays byte-identical. Pure JVM.
 */
final class StartupDiagnostics {
    private StartupDiagnostics() {}

    static final String NOTICE_PREFIX = "# HETU_NOTICE: ";
    static final int MAX_LINES = 64;
    static final String SRC_CONFIG = "配置文件", SRC_HETU = "河图运行时覆写", SRC_DEFAULT = "默认值", SRC_APP = "河图设置";

    /** The full diagnostics text: cleanup notices first, then the source lines. */
    static String report(List<String> cleanupNotices, List<String> sourceLines) {
        StringBuilder out = new StringBuilder();
        for (String s : cleanupNotices) out.append("[清理] ").append(sanitize(s)).append('\n');
        for (String s : sourceLines) out.append(sanitize(s)).append('\n');
        return out.toString();
    }

    static String line(String what, String value, String source, String detail) {
        return "[来源] " + what + " = " + value + " ← " + source + (detail == null || detail.isEmpty() ? "" : "（" + detail + "）");
    }

    /** Mihomo / Mihomo Smart: SOURCE is the selected file (after reference cleanup), FINAL the generated copy. */
    static List<String> mihomo(String source, String finalYaml, ProxyRuntimeProfile profile, int tproxyPort, int redirectPort,
                               boolean dnsTakeover, int dnsPort, int controllerPort, boolean controllerReused,
                               String fakeIpV4, String fakeIpV6) {
        List<String> out = new ArrayList<>();
        int sourceTp = MihomoStartupConfig.detectScalarPort(source, "tproxy-port");
        int sourceRp = MihomoStartupConfig.detectScalarPort(source, "redir-port");
        out.add(portLine("TPROXY 端口", tproxyPort, sourceTp, "tproxy-port", profile.mode.label));
        out.add(portLine("Redirect 端口", redirectPort, sourceRp, "redir-port", profile.mode.label));
        Map<String, Object> srcDns = dns(source), finalDns = dns(finalYaml);
        String srcListen = scalar(srcDns, "listen");
        if (dnsTakeover) {
            out.add(line("DNS 监听", "127.0.0.1:" + dnsPort, SRC_HETU,
                    srcListen.isEmpty() ? "配置文件未设置 dns.listen" : "配置文件的 dns.listen " + srcListen + " 不用于 Root 接管"));
        } else {
            out.add(line("DNS 监听", srcListen.isEmpty() ? "未设置" : srcListen, srcListen.isEmpty() ? SRC_DEFAULT : SRC_CONFIG, "DNS 接管已关闭"));
        }
        String srcMode = scalar(srcDns, "enhanced-mode"), finalMode = scalar(finalDns, "enhanced-mode");
        boolean dnsEnabled = finalDns != null && !"false".equalsIgnoreCase(scalar(finalDns, "enable"));
        if (finalDns == null) out.add(line("DNS 模式", "未启用内置 DNS", SRC_DEFAULT, "配置文件没有 dns 段"));
        else if (!dnsEnabled) out.add(line("DNS 模式", "内置 DNS 已关闭", SRC_CONFIG, "dns.enable: false"));
        else if (finalMode.isEmpty()) out.add(line("DNS 模式", "redir-host", SRC_DEFAULT, "配置文件未设置 dns.enhanced-mode，按 Mihomo 默认"));
        else if (finalMode.equals(srcMode)) out.add(line("DNS 模式", finalMode, SRC_CONFIG, "dns.enhanced-mode"));
        else out.add(line("DNS 模式", finalMode, SRC_HETU, srcMode.isEmpty() ? "配置文件未设置" : "配置文件为 " + srcMode));
        boolean fakeIp = dnsEnabled && "fake-ip".equalsIgnoreCase(finalMode);
        if (fakeIp) {
            String srcRange = scalar(srcDns, "fake-ip-range");
            String v4 = fakeIpV4 == null || fakeIpV4.isEmpty() ? "未使用" : fakeIpV4;
            out.add(line("Fake-IP 范围（IPv4）", v4, srcRange.isEmpty() ? SRC_DEFAULT : SRC_CONFIG,
                    srcRange.isEmpty() ? "配置文件未设置 dns.fake-ip-range" : "dns.fake-ip-range"));
            String srcRange6 = scalar(srcDns, "fake-ip-range6");
            if (fakeIpV6 != null && !fakeIpV6.isEmpty())
                out.add(line("Fake-IP 范围（IPv6）", fakeIpV6, srcRange6.isEmpty() ? SRC_DEFAULT : SRC_CONFIG,
                        srcRange6.isEmpty() ? "配置文件未设置 dns.fake-ip-range6" : "dns.fake-ip-range6"));
        } else {
            out.add(line("Fake-IP 范围", "未使用", finalDns == null || finalMode.isEmpty() ? SRC_DEFAULT : SRC_CONFIG, "DNS 模式不是 fake-ip"));
        }
        out.add(line("控制接口端口", "127.0.0.1:" + controllerPort, SRC_HETU, controllerReused ? "沿用当前运行的端口" : "启动时自动选择空闲端口"));
        return out;
    }

    /** sing-box / Xray / V2Fly / Hysteria: Hetu owns every ingress; the fake-IP range comes from the source or a default. */
    static List<String> nativeCore(String coreLabel, boolean nativeSource, String source, int tproxyPort, int redirectPort, int dnsPort,
                                   int controllerPort, boolean controllerReused, String fakeIpV4) {
        List<String> out = new ArrayList<>();
        String taken = nativeSource ? "已接管 " + coreLabel + " 配置中的入站" : "由 Clash/Mihomo 配置转换";
        out.add(tproxyPort > 0 ? line("TPROXY 端口", String.valueOf(tproxyPort), SRC_HETU, taken) : line("TPROXY 端口", "未启用", SRC_HETU, "当前模式不需要"));
        out.add(redirectPort > 0 ? line("Redirect 端口", String.valueOf(redirectPort), SRC_HETU, taken) : line("Redirect 端口", "未启用", SRC_HETU, "当前模式不需要"));
        out.add(dnsPort > 0 ? line("DNS 监听", "127.0.0.1:" + dnsPort, SRC_HETU, "河图 DNS 入站") : line("DNS 监听", "未启用", SRC_APP, "DNS 接管已关闭"));
        if (fakeIpV4 == null || fakeIpV4.isEmpty()) {
            out.add(line("Fake-IP 范围", "未使用", nativeSource ? SRC_CONFIG : SRC_DEFAULT, nativeSource ? "原生配置未声明河图可识别的 fake-IP" : "转换后的 DNS 不使用 fake-ip"));
        } else {
            String range = nativeSource ? "" : scalar(dns(source), "fake-ip-range");
            boolean fromSource = !range.isEmpty();
            out.add(line("Fake-IP 范围（IPv4）", fakeIpV4, fromSource ? SRC_CONFIG : SRC_DEFAULT,
                    fromSource ? "dns.fake-ip-range" : "配置文件未设置，使用默认 " + ProxyCoreConfig.DEFAULT_FAKE_IP_V4));
        }
        out.add(controllerPort > 0 ? line("控制接口端口", "127.0.0.1:" + controllerPort, SRC_HETU, controllerReused ? "沿用当前运行的端口" : "启动时自动选择空闲端口")
                : line("控制接口端口", "未启用", SRC_HETU, coreLabel + " 无控制接口"));
        return out;
    }

    /** Comment header for the private startup copy; empty when no reference was cleaned. */
    static String header(List<String> cleanupNotices) {
        StringBuilder out = new StringBuilder();
        int n = 0;
        for (String s : cleanupNotices) {
            if (n++ >= MAX_LINES) break;
            out.append(NOTICE_PREFIX).append(sanitize("[清理] " + s)).append('\n');
        }
        return out.toString();
    }

    /** One line, no control characters, bounded; safe inside a YAML comment and a shell `sed`. */
    static String sanitize(String s) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length() && b.length() < 400; i++) {
            char c = s.charAt(i);
            b.append(c < 0x20 || c == 0x7f || c == 0x2028 || c == 0x2029 ? ' ' : c);
        }
        return b.toString();
    }

    private static String portLine(String what, int port, int sourcePort, String key, String modeLabel) {
        if (port <= 0) return line(what, "未启用", SRC_HETU, modeLabel + " 模式不需要" + (sourcePort > 0 ? "；配置文件的 " + key + ": " + sourcePort + " 已忽略" : ""));
        return line(what, String.valueOf(port), SRC_HETU, sourcePort > 0 ? "配置文件的 " + key + ": " + sourcePort + " 已被替换" : "配置文件未设置 " + key);
    }

    static Map<String, Object> dns(String yaml) {
        if (yaml == null || ProxyCoreConfig.looksJson(yaml) || !MihomoStartupConfig.hasTopLevelKey(yaml, "dns")) return null;
        try {
            Object root = ConfigReferenceCleaner.loader().load(RuntimeYaml15.reader(yaml));
            return root instanceof Map ? ConfigReferenceCleaner.map(((Map<?, ?>) root).get("dns")) : null;
        } catch (YAMLException | ClassCastException invalid) {
            return null;
        }
    }

    static String scalar(Map<String, Object> m, String key) {
        if (m == null) return "";
        Object v = m.get(key);
        return v == null || v instanceof Map || v instanceof List ? "" : String.valueOf(v).trim();
    }
}
