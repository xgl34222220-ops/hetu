package io.github.xgl34222220.hetu;

import java.util.*;
import org.json.JSONObject;

/** Metadata only. Candidate process names are evidence of coexistence, never ownership proof. */
final class ConcurrentCoreDiagnostics {
    interface Packages { String[] forUid(int uid) throws Exception; }
    private static final Set<String> NAMES = new HashSet<>(Arrays.asList("core", "mihomo", "mihomo-smart",
            "clash", "clash.meta", "sing-box", "singbox", "xray", "v2ray", "hysteria", "hysteria2"));
    static final String LIMITS = "只读候选进程快照；不读取命令行或配置、不停止进程、不改网络。未检出不代表没有其他代理，"
            + "改名进程、权限限制、采样期间退出与采样上限均可能漏检。TUN 名称和父进程仅为线索，不能单独证明归属。";

    static String focusedLogCommand(String file) {
        String quoted = "'" + file.replace("'", "'\\''") + "'";
        return "HETU_DIAG_LOG=$(tail -c 262144 " + quoted + " 2>/dev/null) || exit 3; "
                + "printf '%s\\n' \"$HETU_DIAG_LOG\" | grep -Ei 'github[.]io|github[.]com|google[.]com|googleapis[.]com|gstatic[.]com|googlevideo[.]com' | tail -n 24; true";
    }

    static String render(String raw, int exit, String completedIdentity, Packages packages) {
        StringBuilder result = new StringBuilder("河图网络完整性只说明自身规则/监听状态，不证明网站可达，也不排除其他代理并存。\n");
        if (raw == null || raw.length() > 32768 || exit != 0) return result.append("候选核心读取未完成，当前并存状态未知。\n").append(LIMITS).toString();
        String[] lines = raw.split("\\n", -1);
        boolean header = false, complete = false, bounded = false;
        int missing = -1, scanned = -1;
        String boot = "";
        List<String[]> processes = new ArrayList<>();
        Set<Integer> seenPids = new HashSet<>();
        List<String> interfaces = new ArrayList<>();
        for (String line : lines) {
            String[] fields = line.split("\\t", -1);
            if (fields.length == 2 && fields[0].equals("inventory") && fields[1].equals("1")) header = true;
            else if (fields.length == 2 && fields[0].equals("boot") && fields[1].matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")) boot = fields[1];
            else if (fields.length == 4 && fields[0].equals("complete")) {
                scanned = number(fields[1]); missing = number(fields[2]);
                complete = scanned >= 0 && scanned <= 2049 && missing >= 0 && missing <= scanned
                        && (fields[3].equals("0") || fields[3].equals("1"));
                bounded = fields[3].equals("1");
            } else if (fields.length == 9 && fields[0].equals("process") && processes.size() < 16) {
                int pid = number(fields[1]);
                if (pid > 0 && fields[2].matches("[1-9][0-9]{0,19}") && NAMES.contains(fields[5])
                        && (NAMES.contains(fields[6]) || fields[6].equals("unknown"))
                        && Arrays.asList("hetu", "module", "app-private", "unknown").contains(fields[7])
                        && fields[8].matches("[A-Za-z0-9_.:-]{1,64}") && seenPids.add(pid)) processes.add(fields);
            } else if (fields.length == 2 && fields[0].equals("tun") && interfaces.size() < 16
                    && fields[1].matches("[A-Za-z0-9_.:-]{1,32}")) interfaces.add(fields[1]);
        }
        if (!header || !complete) return result.append("采样回执不完整，当前并存状态未知。\n").append(LIMITS).toString();
        JSONObject identity;
        try { identity = new JSONObject(completedIdentity == null ? "" : completedIdentity); }
        catch (Exception invalid) { identity = new JSONObject(); }
        int confirmed = 0;
        for (String[] item : processes) {
            int pid = number(item[1]), uid = number(item[3]);
            boolean ours = item[7].equals("hetu") && item[6].equals("core")
                    && identity.optBoolean("running", false) && identity.optInt("pid", -1) == pid
                    && item[2].equals(identity.optString("processStartTicks"))
                    && !boot.isEmpty() && boot.equals(identity.optString("bootId"));
            if (ours) confirmed++;
            result.append("PID=").append(pid).append(" UID=").append(uid < 0 ? "未知" : uid)
                    .append(" 名称=").append(item[5]).append(" 可执行文件=").append(item[6])
                    .append(" 来源=").append(ours ? "河图已绑定启动进程" : origin(item[7]))
                    .append(" 父PID=").append(number(item[4]) < 0 ? "未知" : number(item[4]))
                    .append(" 父进程=").append(item[8])
                    .append(" 应用归属=").append(apps(uid, packages)).append('\n');
        }
        if (processes.size() > 1) result.append("检测到 ").append(processes.size()).append(" 个代理核心候选进程同时存在")
                .append(confirmed > 0 ? "（包含已确认河图核心）" : "（河图启动身份未确认）")
                .append("。多套接管可能改变 DNS、Fake-IP 或分流路径；这不是对具体冲突机制的判定，请自行核对其它代理/模块的运行状态。\n");
        else if (processes.isEmpty()) result.append("本次未检出已知名称的代理核心候选；并存状态未能证实。\n");
        else result.append("本次仅检出一个已知名称候选，不能据此排除其他代理。\n");
        result.append("TUN 接口线索：").append(interfaces.isEmpty() ? "未检出或不可读" : String.join(", ", interfaces)).append('\n');
        result.append("扫描进程=").append(scanned).append("，不可读/变化项=").append(missing)
                .append("，达到采样上限=").append(bounded ? "是" : "否").append('\n');
        return result.append(LIMITS).toString();
    }
    private static int number(String value) {
        if (!value.matches("[0-9]{1,10}")) return -1;
        try { return Integer.parseInt(value); } catch (NumberFormatException invalid) { return -1; }
    }
    private static String origin(String origin) {
        switch (origin) {
            case "hetu": return "河图目录，启动身份未确认";
            case "module": return "Root 模块目录，具体归属未确认";
            case "app-private": return "应用私有目录，需结合UID";
            default: return "未确认";
        }
    }
    private static String apps(int uid, Packages packages) {
        if (uid < 0) return "未知（UID不可读）";
        if (uid < 10000) return "系统/Root UID，不能唯一对应应用";
        try {
            String[] names = packages.forUid(uid);
            List<String> safe = new ArrayList<>();
            if (names != null) for (String name : names) {
                if (name != null && name.matches("[A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)+") && name.length() <= 255 && safe.size() < 4) safe.add(name);
            }
            if (safe.isEmpty()) return "未知（系统未提供可验证包名）";
            return String.join(", ", safe) + (names.length > 1 ? "（共享UID，不能唯一归属）" : "（系统UID映射）");
        } catch (Exception unavailable) { return "未知（读取失败）"; }
    }
}
