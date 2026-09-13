package io.github.xgl34222220.bichen;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.AtomicFile;
import java.io.*;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.*;
import javax.net.ssl.HttpsURLConnection;

/** Optional anti-bypass list for Mihomo VPN. Never downloads on VPN startup. */
final class EncryptedDnsGuard {
    static final String SOURCE="https://raw.githubusercontent.com/hagezi/dns-blocklists/main/wildcard/doh-onlydomains.txt";
    private static final int MAX_BYTES=2*1024*1024;
    private static final int MIN_DOMAINS=500;
    private static final int MAX_DOMAINS=50000;
    private static final Set<String> SEED=Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList(
            "dns.google","dns.google.com","cloudflare-dns.com","dns.cloudflare.com","one.one.one.one",
            "dns.quad9.net","dns10.quad9.net","dns11.quad9.net","doh.opendns.com","doh.familyshield.opendns.com",
            "dns.adguard.com","dns-family.adguard.com","dns-unfiltered.adguard.com","doh.cleanbrowsing.org",
            "doh.360.cn","dot.360.cn","dns.alidns.com","dns.aliyun.com","doh.pub","doh.dns.sb"
    )));
    private final Context context;
    private final SharedPreferences prefs;
    private final AtomicFile file;

    EncryptedDnsGuard(Context context){
        this.context=context.getApplicationContext();
        prefs=this.context.getSharedPreferences("bichen",Context.MODE_PRIVATE);
        File dir=new File(this.context.getFilesDir(),"dns-guard");
        if(!dir.isDirectory())dir.mkdirs();
        file=new AtomicFile(new File(dir,"hagezi-doh.txt"));
    }

    Set<String> domains(){
        try{
            if(file.getBaseFile().isFile()){
                Set<String> parsed=parse(file.openRead());
                if(parsed.size()>=MIN_DOMAINS)return Collections.unmodifiableSet(parsed);
            }
        }catch(Exception ignored){}
        return SEED;
    }

    int count(){return domains().size();}
    long updatedAt(){return prefs.getLong("proxyDnsGuardUpdatedAt",0L);}
    String source(){return SOURCE;}

    boolean update()throws Exception{
        HttpsURLConnection c=(HttpsURLConnection)new URL(SOURCE).openConnection();
        c.setConnectTimeout(12000);c.setReadTimeout(20000);c.setInstanceFollowRedirects(false);
        c.setRequestProperty("Accept","text/plain");c.setRequestProperty("User-Agent","Bichen-EncryptedDnsGuard/1");
        int code=c.getResponseCode();if(code!=200)throw new IOException("加密 DNS 列表下载失败：HTTP "+code);
        int declared=c.getContentLength();if(declared>MAX_BYTES)throw new IOException("加密 DNS 列表超过 2 MiB");
        byte[] data;
        try(InputStream in=c.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()){
            byte[] buf=new byte[16384];int n,total=0;
            while((n=in.read(buf))!=-1){total+=n;if(total>MAX_BYTES)throw new IOException("加密 DNS 列表超过 2 MiB");out.write(buf,0,n);}data=out.toByteArray();
        }finally{c.disconnect();}
        Set<String> parsed=parse(new ByteArrayInputStream(data));
        if(parsed.size()<MIN_DOMAINS||parsed.size()>MAX_DOMAINS)throw new IOException("加密 DNS 列表数量异常，保留上一份");
        // Do not publish a list that somehow dropped major public resolvers.
        if(!containsSuffix(parsed,"dns.google")||!containsSuffix(parsed,"cloudflare-dns.com"))throw new IOException("加密 DNS 列表完整性校验失败，保留上一份");
        FileOutputStream out=null;
        try{out=file.startWrite();ArrayList<String> sorted=new ArrayList<>(parsed);Collections.sort(sorted);for(String d:sorted)out.write((d+"\n").getBytes(StandardCharsets.UTF_8));file.finishWrite(out);}
        catch(Exception e){if(out!=null)file.failWrite(out);throw e;}
        prefs.edit().putLong("proxyDnsGuardUpdatedAt",System.currentTimeMillis()).putInt("proxyDnsGuardCount",parsed.size()).apply();
        return true;
    }

    private static boolean containsSuffix(Set<String> list,String domain){
        if(list.contains(domain))return true;
        for(String d:list)if(domain.endsWith("."+d))return true;
        return false;
    }
    private static Set<String> parse(InputStream in)throws Exception{
        Set<String> parsed=RuleStore.parseRules(in,false);
        if(parsed.size()>MAX_DOMAINS)throw new IOException("加密 DNS 列表超过 50000 条");
        return new LinkedHashSet<>(parsed);
    }
}
