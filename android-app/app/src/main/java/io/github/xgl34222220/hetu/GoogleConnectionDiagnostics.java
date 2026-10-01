package io.github.xgl34222220.hetu;

import java.util.*;

/** Read-only, bounded connection metadata. Does not infer that a request succeeded. */
final class GoogleConnectionDiagnostics {
    static final String[] PACKAGES = {"com.android.vending", "com.google.android.gms",
            "com.google.android.gsf", "com.google.android.apps.bard",
            "com.google.android.googlequicksearchbox", "com.android.chrome"};
    private static final String[] DOMAINS = {"google.com", "googleapis.com", "gstatic.com",
            "googleusercontent.com", "ggpht.com", "gvt1.com", "gvt2.com", "googlevideo.com"};
    static final int LIMIT = 25;
    static final String SNAPSHOT_LIMIT = "仅当前活动连接快照；没有记录不能证明失败请求未发生。DNS 阶段失败、短连接、应用绕过或委派进程可能不在快照中。";
    private final Map<String,Integer> installed;
    private final Set<Integer> shared;

    /** Saved selection only: never presents preferences as observed packet routing. */
    static final class AppSelection {
        private final String scope;
        private final Set<String> selected = new HashSet<>();
        private final Map<Integer,Set<String>> owners = new HashMap<>();
        private final boolean readable;

        AppSelection(Map<String,?> settings, Map<Integer,Set<String>> verifiedUidOwners) {
            Object savedScope = settings.get("proxyAppScope");
            scope = savedScope == null ? "blacklist" : savedScope instanceof String ? (String)savedScope : "";
            Object entries = settings.get("proxyAppPackages");
            boolean valid = entries == null || entries instanceof Set<?>;
            if (entries instanceof Set<?>) for (Object entry : (Set<?>)entries) {
                if (entry instanceof String) selected.add((String)entry);
                else valid = false;
            }
            readable = valid;
            for (Map.Entry<Integer,Set<String>> entry : verifiedUidOwners.entrySet())
                if (entry.getKey() != null && entry.getValue() != null)
                    owners.put(entry.getKey(), new HashSet<>(entry.getValue()));
        }

        String description() {
            String label;
            switch (scope) {
                case "blacklist": label = "所选应用绕过代理"; break;
                case "whitelist": label = "仅所选应用代理"; break;
                case "core": label = "由配置控制，分应用名单不决定接管范围"; break;
                default: label = "范围未知";
            }
            return "已保存的分应用设置：" + label + "。\n"
                    + "下列名单状态不是实际路由结果，也未证明设置已应用；核心分流、共享 UID、其他用户或委派进程可能影响路径。\n";
        }

        String membership(String name, Integer uid) {
            if (!readable) return "名单读取异常，状态未知";
            if (contains(name, uid)) return "此包已列入名单";
            if (uid == null) return "此包未直接列入，UID 未核对";
            Set<String> sameUid = owners.get(uid);
            if (sameUid == null || !sameUid.contains(name))
                return "此包未直接列入，同 UID 名单状态未知";
            for (String owner : sameUid)
                if (!name.equals(owner) && contains(owner, uid))
                    return "同 UID 的其他应用已列入名单";
            return "此包及已核对的同 UID 应用未列入名单";
        }

        private boolean contains(String name, Integer uid) {
            return selected.contains(name) || uid != null && uid >= 0
                    && selected.contains((uid / 100000) + ":" + name);
        }
    }

    GoogleConnectionDiagnostics(Map<String,Integer> verifiedPackages, Set<Integer> sharedUids) {
        installed = new LinkedHashMap<>();
        for (String name : PACKAGES) {
            Integer uid = verifiedPackages.get(name);
            if (uid != null && uid >= 0) installed.put(name, uid);
        }
        shared = new HashSet<>(sharedUids);
    }

    /** These are the only fields accepted from the Controller response. */
    static final class Connection {
        final int uid, port;
        final String process, host, destination, network, rule, rulePayload;
        final List<String> chains;
        Connection(int uid, String process, String host, String destination, int port,
                   String network, String rule, List<String> chains) {
            this(uid,process,host,destination,port,network,rule,"",chains);
        }
        Connection(int uid, String process, String host, String destination, int port,
                   String network, String rule, String rulePayload, List<String> chains) {
            this.uid=uid; this.process=process; this.host=host; this.destination=destination;
            this.port=port; this.network=network; this.rule=rule; this.rulePayload=rulePayload;
            this.chains=chains == null ? Collections.emptyList() : new ArrayList<>(chains);
        }
    }

    String match(Connection c) {
        if (c.uid >= 0 && c.uid % 100000 >= 10000 && installed.containsValue(c.uid))
            return shared.contains(c.uid) ? "共享或归属待核对 UID（无法归属具体应用）" : "已核对包名的 UID";
        String process = c.process == null ? "" : c.process;
        int colon = process.indexOf(':');
        String name = colon < 0 ? process : process.substring(0, colon);
        Integer expected = installed.get(name);
        // A contradictory socket UID is not proof of this package's ownership.
        if (expected != null && c.uid < 0)
            return "已安装包的进程名线索（连接 UID 未知）";
        if (expected != null && c.uid == expected && c.uid % 100000 < 10000)
            return "已安装包的进程名线索（系统 UID 不足以归属请求）";
        if (googleHost(c.host)) return "Google 域名线索（应用归属未证实）";
        return "";
    }

    static boolean googleHost(String value) {
        String host = hostname(value);
        for (String domain : DOMAINS)
            if (host.equals(domain) || host.endsWith("."+domain)) return true;
        return false;
    }

    String render(List<Connection> snapshot, boolean available) {
        return render(snapshot, available, null);
    }

    String render(List<Connection> snapshot, boolean available, AppSelection selection) {
        StringBuilder out = new StringBuilder("当前 Android 用户可核对的包：\n");
        if (selection != null) out.append(selection.description());
        for (String name : PACKAGES) {
            Integer uid = installed.get(name);
            out.append(name).append('=');
            if (uid == null) out.append("未安装或当前用户不可见");
            else out.append("uid:").append(uid).append(uid % 100000 < 10000 ? "（系统 UID，不能单独归属请求）" : shared.contains(uid) ? "（共享或归属待核对）" : "");
            if (selection != null) out.append("；").append(selection.membership(name, uid));
            out.append('\n');
        }
        out.append(SNAPSHOT_LIMIT).append('\n');
        out.append("包含 DIRECT；UID 可能由共享、隔离或委派进程持有，不能据此确认发起界面。\n");
        if (!available) return out.append("Controller 活动快照读取失败，当前连接状态未知。\n").toString();
        int total=0, displayed=0;
        for (Connection c : snapshot) {
            String basis=match(c);
            if (basis.isEmpty()) continue;
            total++;
            if (displayed >= LIMIT) continue;
            out.append('#').append(++displayed).append(" 匹配=").append(basis)
                    .append(" uid=").append(c.uid < 0 ? "未知" : c.uid)
                    .append(" process=").append(packageProcess(c.process))
                    .append(" host=").append(orUnknown(hostname(c.host)))
                    .append(" destination=").append(ip(c.destination)).append(':')
                    .append(c.port >= 1 && c.port <= 65535 ? c.port : "未知")
                    .append(" network=").append(protocol(c.network))
                    .append(" rule=").append(ruleType(c.rule)).append('/').append(safe(c.rulePayload,96)).append(" chains=");
            for (int i=0;i<Math.min(c.chains.size(),6);i++) {
                if (i>0) out.append(" → ");
                out.append(safe(c.chains.get(i),64));
            }
            if(c.chains.size()>6) out.append(" …");
            out.append('\n');
        }
        if(total==0) out.append("本次快照未匹配到 Google 相关连接，故障原因仍未知。\n");
        else out.append("匹配 ").append(total).append(" 条，展示 ").append(displayed).append(" 条；仅为路径线索，不代表请求成功或故障原因。\n");
        return out.toString();
    }

    private static String hostname(String value) {
        String host=value==null ? "" : value.toLowerCase(Locale.ROOT);
        if(host.endsWith(".")) host=host.substring(0,host.length()-1);
        if(host.length()>253 || !host.matches("[a-z0-9](?:[a-z0-9.-]*[a-z0-9])?")) return "";
        for(String label:host.split("\\.",-1))
            if(label.isEmpty()||label.length()>63||label.startsWith("-")||label.endsWith("-")) return "";
        return host;
    }
    private static String packageProcess(String value) {
        return value!=null && value.length()<=160 && value.matches("[A-Za-z0-9_.]+(?::[A-Za-z0-9_.-]+)?") ? value : "未知";
    }
    private static String ip(String value) {
        return value!=null && value.length()<=45 && value.matches("[0-9a-fA-F:.]+") ? value : "未知";
    }
    private static String protocol(String value) {
        String protocol=value==null ? "" : value.toLowerCase(Locale.ROOT);
        return protocol.equals("tcp")||protocol.equals("udp") ? protocol : "未知";
    }
    private static String ruleType(String value) {
        return value!=null && value.length()<=48 && value.matches("[A-Za-z][A-Za-z0-9_-]*") ? value : "未知";
    }
    private static String orUnknown(String value) { return value.isEmpty() ? "未知" : value; }
    private static String safe(String value, int limit) {
        String text=DiagnosticReport.redact(value, "").replaceAll("[\\p{Cntrl}\\p{Cf}]", " ");
        text=text.replaceAll("(?i)(token|password|secret|authorization|cookie)\\s*[:=]\\s*\\S+", "$1=[redacted]");
        return text.length()>limit ? text.substring(0,limit)+"…" : orUnknown(text);
    }
}
