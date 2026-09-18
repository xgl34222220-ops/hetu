package io.github.xgl34222220.hetu;

import android.content.Context;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Exports Hetu DNS block/allow snapshots for Mihomo local domain providers. */
final class ProxyAdblockRules {
    static final String PROVIDER_NAME = "hetu-adblock";
    static final String ALLOW_PROVIDER_NAME = "hetu-adblock-allow";
    static final String PROVIDER_PATH = "./ruleset/hetu-adblock.txt";
    static final String ALLOW_PROVIDER_PATH = "./ruleset/hetu-adblock-allow.txt";

    static final class Snapshot {
        final File file;
        final File allowFile;
        final int count;
        final int allowCount;
        final String revision;
        Snapshot(File file, File allowFile, int count, int allowCount, String revision) {
            this.file=file; this.allowFile=allowFile; this.count=count; this.allowCount=allowCount;
            this.revision=revision==null?"":revision;
        }
    }

    static Snapshot export(Context context) throws Exception {
        RuleStore rules=new RuleStore(context.getApplicationContext());
        rules.reload();
        ArrayList<String> domains=new ArrayList<>(rules.effectiveDomains());
        ArrayList<String> allow=new ArrayList<>(rules.effectiveAllowDomains());
        Collections.sort(domains);
        Collections.sort(allow);
        if(domains.size()>750000)throw new IOException("广告规则超过安全上限");

        File dir=new File(context.getCacheDir(),"hetu-dns-filter");
        if(!dir.isDirectory()&&!dir.mkdirs())throw new IOException("无法创建河图 DNS 过滤规则目录");
        File target=new File(dir,"hetu-adblock.txt");
        File allowTarget=new File(dir,"hetu-adblock-allow.txt");
        writeProvider(domains,target);
        writeProvider(allow,allowTarget);
        return new Snapshot(target,allowTarget,domains.size(),allow.size(),rules.currentRevision());
    }

    private static void writeProvider(List<String> domains,File target)throws IOException{
        File temp=new File(target.getParentFile(),target.getName()+".new");
        try(FileOutputStream raw=new FileOutputStream(temp,false);
            OutputStreamWriter writer=new OutputStreamWriter(raw,StandardCharsets.UTF_8);
            BufferedWriter out=new BufferedWriter(writer,64*1024)){
            for(String domain:domains){
                if(domain==null||domain.isEmpty())continue;
                // Mihomo behavior=domain accepts +.example.com as suffix match,
                // matching the DNS semantics used by Hetu and AdGuard-style lists.
                out.write("+."+domain);
                out.newLine();
            }
            out.flush();
            raw.getFD().sync();
        }
        try{
            Files.move(temp.toPath(),target.toPath(),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);
        }catch(Exception atomic){
            if(!temp.renameTo(target)){temp.delete();throw new IOException("无法保存河图 DNS 过滤快照",atomic);}
        }
    }
}
