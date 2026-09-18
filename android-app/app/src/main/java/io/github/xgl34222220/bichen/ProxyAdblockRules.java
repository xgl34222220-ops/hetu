package io.github.xgl34222220.bichen;

import android.content.Context;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Exports the exact effective Bichen ad-block snapshot for Mihomo's local domain rule-provider. */
final class ProxyAdblockRules {
    static final String PROVIDER_NAME = "bichen-adblock";
    static final String PROVIDER_PATH = "./ruleset/bichen-adblock.txt";

    static final class Snapshot {
        final File file;
        final int count;
        final String revision;
        Snapshot(File file, int count, String revision) {
            this.file = file; this.count = count; this.revision = revision == null ? "" : revision;
        }
    }

    static Snapshot export(Context context) throws Exception {
        RuleStore rules = new RuleStore(context.getApplicationContext());
        rules.reload();
        ArrayList<String> domains = new ArrayList<>(rules.effectiveDomains());
        Collections.sort(domains);
        if (domains.size() > 500000) throw new IOException("广告规则超过安全上限");
        ArrayList<String> allow = new ArrayList<>(rules.userList(true));

        File dir = new File(context.getCacheDir(), "proxy-adblock");
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("无法创建代理广告规则目录");
        File target = new File(dir, "bichen-adblock.txt");
        File temp = new File(dir, "bichen-adblock.txt.new");
        try (FileOutputStream raw = new FileOutputStream(temp, false);
             OutputStreamWriter writer = new OutputStreamWriter(raw, StandardCharsets.UTF_8);
             BufferedWriter out = new BufferedWriter(writer, 64 * 1024)) {
            for (String domain : domains) {
                if (domain == null || domain.isEmpty()) continue;
                // Mihomo domain-provider wildcard '+.' is suffix-aware: it matches the
                // base domain and every subdomain. That is much closer to what DNS
                // blocklists intend than an exact-host-only provider.
                //
                // If the user explicitly allowlisted a child hostname, keep this one
                // rule exact instead of widening it, so the allow exception cannot be
                // swallowed by a parent suffix rule.
                boolean childAllow = false;
                String suffix = "." + domain;
                for (String allowed : allow) {
                    if (allowed != null && allowed.endsWith(suffix)) {
                        childAllow = true;
                        break;
                    }
                }
                out.write(childAllow ? domain : "+." + domain);
                out.newLine();
            }
            out.flush();
            raw.getFD().sync();
        }
        try {
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception atomic) {
            if (!temp.renameTo(target)) { temp.delete(); throw new IOException("无法保存代理广告规则快照", atomic); }
        }
        return new Snapshot(target, domains.size(), rules.currentRevision());
    }
}
