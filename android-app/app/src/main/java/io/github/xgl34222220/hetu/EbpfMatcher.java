package io.github.xgl34222220.hetu;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * eBPF 规则匹配 (opt-in, default off): the App side of hetu-root.sh's matcher.
 *
 * <ul>
 *   <li>Deployment: the per-ABI loader {@code assets/hetu-bpf/<abi>/hetu-bpf} becomes
 *   {@code /data/adb/hetu/bin/hetu-bpf} (0700 root) and {@code assets/hetu-bpf/hetu_match.o}
 *   becomes {@code bin/hetu_match.o} (0600 root), each through a temporary file and {@code mv};
 *   the switch itself is the policy file {@code policy/ebpf} ({@code ENABLED=1}), the same
 *   mechanism as {@code policy/cnip}, so boot plans keep it.</li>
 *   <li>Status: {@code ebpf}/{@code ebpfReason} from {@code hetu-root.sh status} as Chinese text.</li>
 *   <li>Hot update: when only the app list, DIRECT apps or bypass CIDRs changed and the running
 *   session reports {@code ebpf=active}, {@code hetu-root.sh ebpf-update} rewrites the pinned maps
 *   with the same strings as start arguments 19/14/17; anything else falls back to a restart.</li>
 * </ul>
 * Pure Java (no Android API) so it is host-testable.
 */
final class EbpfMatcher {
    private EbpfMatcher() {}

    static final String PREF_ENABLED = "proxyEbpfMatcher";
    /** Cached from the status probe: off | active | fallback, and the fallback reason token. */
    static final String PREF_STATE = "proxyRootEbpfMatch";
    static final String PREF_REASON = "proxyRootEbpfReason";
    /** Settings signature without the hot-updatable lists, recorded by the last completed start. */
    static final String APPLIED_BASE_KEY = "proxyRootAppliedBaseSettings";

    static final String OBJECT_ASSET = "hetu-bpf/hetu_match.o";
    static final String LOADER_NAME = "hetu-bpf";
    static final String OBJECT_NAME = "hetu_match.o";
    static final String POLICY_NAME = "ebpf";
    static final String POLICY_BODY = "ENABLED=1\n";

    /** The settings keys a running eBPF session can take without a restart. */
    static final String[] LIST_KEYS = {"proxyAppPackages", "proxyBypassCidrs"};
    /** Topology fingerprint fields the maps carry: app-scope UIDs, bypass CIDRs, DIRECT UIDs. */
    private static final String[] LIST_FIELDS = {"uids=", "cidrs=", "direct="};

    /** The loader asset for the first supported ABI that has one, or null. */
    static String loaderAsset(String[] supportedAbis) {
        return loaderAsset(supportedAbis, null);
    }

    /**
     * Audit5 G13: whether a packaged asset is really there. An ABI-split (arm64-only) APK installed
     * on an x86_64 device that translates ARM no longer carries the x86_64 payloads, so the first
     * supported ABI is not enough. {@code null} = not checked (the old first-match behaviour).
     */
    interface AssetProbe { boolean exists(String path); }

    /** {@link #loaderAsset(String[])} that skips ABIs whose loader is not packaged. */
    static String loaderAsset(String[] supportedAbis, AssetProbe probe) {
        return firstAsset(supportedAbis, "hetu-bpf/", "/" + LOADER_NAME, probe);
    }

    /** prefix + abi + suffix for the first arm64-v8a / x86_64 entry of {@code supportedAbis} the probe accepts. */
    static String firstAsset(String[] supportedAbis, String prefix, String suffix, AssetProbe probe) {
        if (supportedAbis == null) return null;
        for (String abi : supportedAbis) {
            String value = abi == null ? "" : abi.toLowerCase(Locale.ROOT);
            if (!value.equals("arm64-v8a") && !value.equals("x86_64")) continue;
            String path = prefix + value + suffix;
            if (probe == null || probe.exists(path)) return path;
        }
        return null;
    }

    /** Writes or removes {@code policy/ebpf} exactly like {@code policy/cnip} (0600 root, temp + mv). */
    static String policyCommand(String policyDir, boolean enabled) {
        String policy = policyDir + "/" + POLICY_NAME, tmp = policy + ".new";
        if (!enabled) return "; rm -f " + quote(policy);
        return "; printf %s " + quote(POLICY_BODY) + " > " + quote(tmp)
                + "; chmod 600 " + quote(tmp) + "; chown 0:0 " + quote(tmp)
                + "; mv -f " + quote(tmp) + ' ' + quote(policy);
    }

    /**
     * Installs the loader (0700) and the object (0600) under {@code binDir} when the installed
     * bytes differ from the staged ones: copy to a temporary name, set mode/owner, verify the
     * digest, then {@code mv}. The mode/owner of an unchanged file is re-asserted.
     */
    static String payloadCommand(String binDir, String loaderSource, String loaderSha256,
                                 String objectSource, String objectSha256, String suffix) {
        StringBuilder cmd = new StringBuilder("; mkdir -p ").append(quote(binDir));
        install(cmd, loaderSource, loaderSha256, binDir + "/" + LOADER_NAME, suffix, "700");
        install(cmd, objectSource, objectSha256, binDir + "/" + OBJECT_NAME, suffix, "600");
        return cmd.toString();
    }

    private static void install(StringBuilder cmd, String source, String sha, String target, String suffix, String mode) {
        if (!sha.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("eBPF 载荷摘要无效");
        String tmp = target + suffix;
        cmd.append("; if [ \"$(sha256sum ").append(quote(target)).append(" 2>/dev/null | cut -d ' ' -f 1)\" != ").append(quote(sha)).append(" ]; then")
                .append(" cp ").append(quote(source)).append(' ').append(quote(tmp))
                .append("; chmod ").append(mode).append(' ').append(quote(tmp)).append("; chown 0:0 ").append(quote(tmp))
                .append("; test \"$(sha256sum ").append(quote(tmp)).append(" | cut -d ' ' -f 1)\" = ").append(quote(sha))
                .append("; mv -f ").append(quote(tmp)).append(' ').append(quote(target))
                .append("; fi; chmod ").append(mode).append(' ').append(quote(target)).append("; chown 0:0 ").append(quote(target));
    }

    /** {@code hetu-root.sh ebpf-update <direct> <app uids> <cidrs>}: start arguments 19, 14 and 17. */
    static String[] updateArgs(String directUidRanges, String uidRanges, String cidrs) {
        return new String[]{"ebpf-update", nz(directUidRanges), nz(uidRanges), nz(cidrs)};
    }

    /** Settings values without the hot-updatable lists (an absent list reads as empty). */
    static Map<String, ?> withoutLists(Map<String, ?> values) {
        Map<String, Object> out = new HashMap<>(values == null ? new HashMap<String, Object>() : values);
        for (String key : LIST_KEYS) out.remove(key);
        return out;
    }

    /**
     * True when two topology fingerprints differ at most in the fields the maps carry. Any other
     * field (mode, scope, IPv6, DNS, interfaces, MACs, fake-IP, the eBPF switch itself) needs a restart.
     */
    static boolean onlyListsChanged(String live, String next) {
        if (live == null || next == null || live.isEmpty() || next.isEmpty()) return false;
        String[] a = live.split("\\|", -1), b = next.split("\\|", -1);
        if (a.length != b.length) return false;
        for (int i = 0; i < a.length; i++) {
            if (a[i].equals(b[i])) continue;
            String field = listField(a[i]);
            if (field == null || !field.equals(listField(b[i]))) return false;
        }
        return true;
    }

    private static String listField(String part) {
        for (String field : LIST_FIELDS) if (part.startsWith(field)) return field;
        return null;
    }

    /** Only an active matcher has maps to rewrite. */
    static boolean hotUpdatable(String state) { return "active".equals(state); }

    /** The switch's subtitle: what the matcher does, then what the running session reported. */
    static String describe(boolean enabled, boolean running, String state, String reason) {
        String about = "用 eBPF 映射匹配分应用名单与绕过网段，名单变化时无需重启；设备不支持时自动回退到原 iptables 规则。默认关闭，重启代理后生效";
        if (!enabled) return about;
        if (!running || state == null || state.isEmpty()) return about;
        if ("active".equals(state)) {
            if ("pins-missing".equals(reason)) return "已启用但映射丢失：" + reasonText(reason);
            return "已生效：本次会话由 eBPF 匹配名单；只改应用名单或绕过网段时，「立即重启」会改为热更新，不中断连接";
        }
        if ("fallback".equals(state)) return "未生效，已回退到 iptables 规则：" + reasonText(reason);
        return "本次会话未启用 eBPF 匹配，重启代理后生效";
    }

    /** Chinese text for a reason token from hetu-root.sh / the hetu-bpf loader. */
    static String reasonText(String reason) {
        String r = reason == null ? "" : reason.trim();
        switch (r) {
            case "": return "未返回原因";
            case "selinux-denied": return "SELinux 拒绝了 bpf 调用";
            case "bpffs-missing": return "系统没有挂载 bpf 文件系统";
            case "bpf-syscall-unavailable": return "内核不提供 bpf 系统调用";
            case "socket-uid-helper-unavailable": return "内核不支持按应用 UID 匹配";
            case "lpm-unsupported": return "内核不支持网段前缀映射";
            case "xt-bpf-unsupported": return "iptables 不支持 bpf 匹配";
            case "xt6-bpf-unsupported": return "ip6tables 不支持 bpf 匹配";
            case "payload-missing": return "eBPF 组件未安装";
            case "uid-list-invalid": return "应用名单无效";
            case "cidr-list-invalid": return "绕过网段无效";
            case "object-invalid": return "eBPF 程序文件无效";
            case "pins-missing": return "eBPF 映射已丢失，请重启代理";
            case "unpin-failed": return "旧的 eBPF 映射无法清理";
            case "usage": return "eBPF 组件版本不匹配";
            case "error": return "eBPF 组件运行出错";
            default: break;
        }
        if (r.startsWith("mode-")) return "当前运行模式不支持（仅 TPROXY / Redirect / Enhance）";
        String[][] prefixes = {{"prog-load-", "内核拒绝加载 eBPF 程序"}, {"map-create-", "内核无法创建 eBPF 映射"},
                {"map-update-", "eBPF 映射写入失败"}, {"map-read-", "eBPF 映射读取失败"}, {"pin-", "eBPF 映射无法固定到 bpf 文件系统"},
                {"bpf-syscall-", "bpf 系统调用失败"}};
        for (String[] item : prefixes) {
            if (!r.startsWith(item[0])) continue;
            if (r.endsWith("-permission")) return item[1] + "（权限不足）";
            return item[1];
        }
        return "未知原因（" + r + "）";
    }

    /** Run modes hetu-root.sh can match with eBPF (tproxy | redirect | enhance); any other records mode-<m> and falls back. */
    static boolean modeSupported(String modeId) {
        return "tproxy".equals(modeId) || "redirect".equals(modeId) || "enhance".equals(modeId);
    }

    /**
     * True while the switch differs from what the running session was started with. The applied
     * topology fingerprint ends in {@code |ebpf=1} only when the matcher was on; the settings
     * signature does not carry the switch, so the 重启后生效 notice asks this as well. Without a
     * running session or a recorded fingerprint nothing is pending.
     */
    static boolean switchPending(boolean running, boolean enabled, String liveFingerprint) {
        if (!running || liveFingerprint == null || liveFingerprint.isEmpty()) return false;
        return enabled != liveFingerprint.endsWith("|ebpf=1");
    }

    /**
     * The switch's subtitle as translatable parts (each part is one vocabulary entry; the UI
     * translates and joins them). Unlike {@link #describe} it also covers an unsupported run mode
     * and a matcher that was switched off but is still active until the next restart.
     */
    static String[] describe(boolean enabled, boolean running, String state, String reason, String modeId) {
        if (!modeSupported(modeId)) return new String[]{"当前运行模式不支持 eBPF 规则匹配（仅 TPROXY / Redirect / Enhance）"};
        if (!enabled && running && "active".equals(state)) return new String[]{"已关闭，重启代理后停用 eBPF 匹配；本次会话仍由 eBPF 匹配名单"};
        if (enabled && running && "active".equals(state) && "pins-missing".equals(reason))
            return new String[]{"已启用但映射丢失：", reasonText(reason)};
        if (enabled && running && "fallback".equals(state)) return new String[]{"未生效，已回退到 iptables 规则：", reasonText(reason)};
        return new String[]{describe(enabled, running, state, reason)};
    }

    private static String nz(String value) { return value == null ? "" : value; }

    /** Same quoting as RootBridge.quote, kept local so this class stays free of Android types. */
    static String quote(String value) {
        if (value == null || value.indexOf('\0') >= 0) throw new IllegalArgumentException("Shell 参数不能为空或包含 NUL");
        return "'" + value.replace("'", "'\\''") + "'";
    }
}
