package io.github.xgl34222220.hetu;

import android.content.Context;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Exports Hetu DNS block/allow snapshots for Mihomo local domain providers. */
final class ProxyAdblockRules {
    private static final String EXPORT_VERSION = "filter-v3:";
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

    static synchronized Snapshot export(Context context) throws Exception {
        File dir=new File(context.getCacheDir(),"hetu-dns-filter");
        if(!dir.isDirectory()&&!dir.mkdirs())throw new IOException("无法创建河图 DNS 过滤规则目录");
        File target=new File(dir,"hetu-adblock.txt");
        File allowTarget=new File(dir,"hetu-adblock-allow.txt");
        File meta=new File(dir,"hetu-adblock.meta");

        RuleStore rules=new RuleStore(context.getApplicationContext());
        rules.reload();
        RuleStore.ExportRules effective=RuleStore.currentExportRules();
        String generation=effective.revision;
        String persisted=generation.isEmpty()?"":EXPORT_VERSION+generation;
        if(!persisted.isEmpty()&&target.isFile()&&allowTarget.isFile()&&meta.isFile()){
            Properties cached=new Properties();
            try(FileInputStream in=new FileInputStream(meta)){cached.load(in);}
            catch(Exception ignored){cached.clear();}
            if(persisted.equals(cached.getProperty("revision",""))){
                try{
                    int count=Integer.parseInt(cached.getProperty("count","-1"));
                    int allowCount=Integer.parseInt(cached.getProperty("allowCount","-1"));
                    if(count>=0&&allowCount>=0)return new Snapshot(target,allowTarget,count,allowCount,persisted);
                }catch(NumberFormatException ignored){}
            }
        }

        String revision=EXPORT_VERSION+generation;
        ArrayList<String> domains=new ArrayList<>(effective.domains);
        ArrayList<String> allow=new ArrayList<>(effective.allowDomains);
        Collections.sort(domains);
        Collections.sort(allow);
        if(domains.size()>1500000)throw new IOException("广告规则超过 150 万条安全上限");
        // A failed second write must not leave an old cache index pointing at a
        // partially replaced pair (including when the user rolls back a revision).
        Files.deleteIfExists(meta.toPath());
        writeProvider(domains,target);
        writeProvider(allow,allowTarget);

        Properties saved=new Properties();
        saved.setProperty("revision",revision);
        saved.setProperty("count",String.valueOf(domains.size()));
        saved.setProperty("allowCount",String.valueOf(allow.size()));
        File metaTmp=new File(dir,"hetu-adblock.meta.new");
        try(FileOutputStream out=new FileOutputStream(metaTmp,false)){
            saved.store(out,null);out.getFD().sync();
        }
        try{Files.move(metaTmp.toPath(),meta.toPath(),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}
        catch(Exception atomic){if(!metaTmp.renameTo(meta)){metaTmp.delete();throw new IOException("无法保存广告规则缓存索引",atomic);}}
        return new Snapshot(target,allowTarget,domains.size(),allow.size(),revision);
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
