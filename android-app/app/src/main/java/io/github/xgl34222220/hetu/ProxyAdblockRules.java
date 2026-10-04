package io.github.xgl34222220.hetu;

import android.content.Context;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Exports Hetu DNS block/allow snapshots for Mihomo local domain providers. */
final class ProxyAdblockRules {
    private static final String EXPORT_VERSION = "filter-v4-sha256:";
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
        RuleStore rules=new RuleStore(context.getApplicationContext());
        rules.reload();
        RuleStore.ExportRules effective=RuleStore.currentExportRules();
        return exportSnapshot(context.getNoBackupFilesDir(),effective.revision,effective.domains,effective.allowDomains);
    }

    /** Immutable pairs survive cache eviction and cannot change during Root handoff. */
    static synchronized Snapshot exportSnapshot(File stableRoot,String generation,Set<String> block,Set<String> exceptions) throws IOException {
        return exportSnapshot(stableRoot,generation,block,exceptions,ProxyAdblockRules::writeProvider);
    }
    interface ProviderWriter { void write(List<String> domains,File target)throws IOException; }
    static synchronized Snapshot exportSnapshot(File stableRoot,String generation,Set<String> block,Set<String> exceptions,ProviderWriter writer) throws IOException {
        File dir=new File(stableRoot,"hetu-dns-filter");
        if(!dir.isDirectory()&&!dir.mkdirs())throw new IOException("无法创建河图 DNS 过滤规则目录");
        String persisted=generation.isEmpty()?"":EXPORT_VERSION+generation;
        File index=new File(dir,"current.meta");
        Properties cached=new Properties();
        if(index.isFile())try(FileInputStream in=new FileInputStream(index)){cached.load(in);}
        catch(IOException ignored){cached.clear();}
        String directory=cached.getProperty("directory","");
        // Index data is local but still must not escape our export directory.
        File previous=new File(dir,directory.matches("snapshot-[0-9a-f-]{36}")?directory:"invalid");
        File target=new File(previous,"hetu-adblock.txt");
        File allowTarget=new File(previous,"hetu-adblock-allow.txt");
        File meta=new File(previous,"hetu-adblock.meta");
        if(!persisted.isEmpty()&&target.isFile()&&allowTarget.isFile()&&meta.isFile()){
            try(FileInputStream in=new FileInputStream(meta)){cached.load(in);}
            catch(Exception ignored){cached.clear();}
            if(persisted.equals(cached.getProperty("revision",""))&&RuntimeCompatibility14.cachedPairValid(cached,target,allowTarget)){
                try{
                    int count=Integer.parseInt(cached.getProperty("count","-1"));
                    int allowCount=Integer.parseInt(cached.getProperty("allowCount","-1"));
                    if(count>=0&&allowCount>=0)return new Snapshot(target,allowTarget,count,allowCount,persisted);
                }catch(NumberFormatException ignored){}
            }
        }

        String revision=EXPORT_VERSION+generation;
        ArrayList<String> domains=new ArrayList<>(block);
        ArrayList<String> allow=new ArrayList<>(exceptions);
        Collections.sort(domains);
        Collections.sort(allow);
        if(domains.size()>1500000||allow.size()>1500000)throw new IOException("广告规则超过 150 万条安全上限");
        String name="snapshot-"+UUID.randomUUID();
        File stage=new File(dir,name+".new");
        if(!stage.mkdir())throw new IOException("无法创建广告过滤导出事务");
        target=new File(stage,"hetu-adblock.txt"); allowTarget=new File(stage,"hetu-adblock-allow.txt");
        try {
        writer.write(domains,target); writer.write(allow,allowTarget);
        Properties saved=new Properties();
        saved.setProperty("directory",name);
        saved.setProperty("revision",revision);
        saved.setProperty("blockSha256",RuntimeCompatibility14.sha256(target));
        saved.setProperty("allowSha256",RuntimeCompatibility14.sha256(allowTarget));
        saved.setProperty("count",String.valueOf(domains.size()));
        saved.setProperty("allowCount",String.valueOf(allow.size()));
        try(FileOutputStream out=new FileOutputStream(new File(stage,"hetu-adblock.meta"),false)){
            saved.store(out,null);out.getFD().sync();
        }
        File published=new File(dir,name);
        move(stage,published);
        File metaTmp=new File(dir,"current.meta.new");
        try(FileOutputStream out=new FileOutputStream(metaTmp,false)){saved.store(out,null);out.getFD().sync();}
        move(metaTmp,index);
        return new Snapshot(new File(published,target.getName()),new File(published,allowTarget.getName()),domains.size(),allow.size(),revision);
        } finally {
            // Only our unpublished transaction is disposable. Previously returned
            // generations remain immutable, including during a failed refresh.
            File[] abandoned=stage.listFiles();
            if(abandoned!=null)for(File file:abandoned)file.delete();
            stage.delete();
        }
    }

    private static void move(File source,File target)throws IOException{
        try{Files.move(source.toPath(),target.toPath(),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}
        catch(AtomicMoveNotSupportedException unsupported){Files.move(source.toPath(),target.toPath(),StandardCopyOption.REPLACE_EXISTING);}
    }

    static void writeProvider(List<String> domains,File target)throws IOException{
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
