package io.github.xgl34222220.bichen;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.util.AtomicFile;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.*;

/** Bounded, opt-in observations of active snapshots. NOT a DNS/rejection event log. */
final class ProxyRecords {
    private static ProxyRecords instance;
    static synchronized ProxyRecords get(Context c){if(instance==null)instance=new ProxyRecords(c.getApplicationContext());return instance;}
    private final AtomicFile file; private final SharedPreferences prefs;
    private final LinkedHashMap<String,JSONObject> items=new LinkedHashMap<>();
    private boolean loaded,dirty; private long generation,lastFlush; private String warning="";
    private ProxyRecords(Context c){file=new AtomicFile(new File(c.getFilesDir(),"proxy-observations.json"));prefs=c.getSharedPreferences("bichen",0);}
    synchronized long ticket(){return generation;}
    synchronized boolean enabled(){return prefs.getBoolean("proxyRecordConnections",false);}
    synchronized String warning(){return warning;}
    synchronized void reportFailure(){warning="连接采样或存储暂不可用；不会把失败记成拦截";}
    synchronized void setEnabled(boolean on)throws IOException{load();if(!prefs.edit().putBoolean("proxyRecordConnections",on).commit())throw new IOException("记录设置未保存");generation++;flush();}
    synchronized void clear(){generation++;loaded=true;items.clear();dirty=false;warning="";file.delete();}
    private void load(){if(loaded)return;loaded=true;try(InputStream in=file.openRead();ByteArrayOutputStream out=new ByteArrayOutputStream()){
        byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1){if(out.size()+n>2*1024*1024)throw new IOException("size");out.write(b,0,n);}
        JSONArray a=new JSONArray(new String(out.toByteArray(),StandardCharsets.UTF_8));
        for(int i=Math.max(0,a.length()-300);i<a.length();i++){JSONObject v=a.optJSONObject(i);if(v!=null&&!v.optString("id").isEmpty())items.put(v.optString("id"),v);}
    }catch(FileNotFoundException ignored){}catch(Exception e){items.clear();warning="旧采样记录损坏，已重新建立；不会补造丢失记录";}}
    private static String cut(String v,int max){return v==null?"":v.substring(0,Math.min(max,v.length()));}
    synchronized void ingest(JSONObject snapshot,long ticket)throws Exception{
        load();if(ticket!=generation||!enabled())return;
        JSONArray all=snapshot.optJSONArray("connections");if(all==null)return;long now=System.currentTimeMillis();
        for(int i=0;i<Math.min(4096,all.length());i++){
            JSONObject c=all.optJSONObject(i);if(c==null)continue;String id=cut(c.optString("id"),100);JSONObject m=c.optJSONObject("metadata");if(id.isEmpty()||m==null)continue;
            JSONObject previous=items.remove(id);JSONObject v=new JSONObject().put("id",id).put("firstSeen",previous==null?now:previous.optLong("firstSeen",now)).put("lastSeen",now)
                .put("host",cut(m.optString("host"),253)).put("ip",cut(m.optString("destinationIP"),64)).put("port",cut(m.optString("destinationPort"),8))
                .put("network",cut(m.optString("network"),12)).put("rule",cut(c.optString("rule"),100)).put("upload",Math.max(0,c.optLong("upload"))).put("download",Math.max(0,c.optLong("download")));
            JSONArray chain=c.optJSONArray("chains"),safe=new JSONArray();if(chain!=null)for(int j=0;j<Math.min(6,chain.length());j++)safe.put(cut(chain.optString(j),100));v.put("chains",safe);items.put(id,v);
            while(items.size()>300)items.remove(items.keySet().iterator().next());dirty=true;
        }
        if(SystemClock.elapsedRealtime()-lastFlush>=10000)flush();
    }
    synchronized JSONArray snapshot()throws Exception{load();ArrayList<JSONObject> values=new ArrayList<>(items.values());Collections.reverse(values);return new JSONArray(values.toString());}
    synchronized void flush()throws IOException{
        if(!dirty)return;FileOutputStream out=null;try{out=file.startWrite();out.write(new JSONArray(items.values()).toString().getBytes(StandardCharsets.UTF_8));file.finishWrite(out);dirty=false;lastFlush=SystemClock.elapsedRealtime();warning="";}catch(IOException e){if(out!=null)file.failWrite(out);warning="采样记录保存失败；内存记录仍可查看";throw e;}
    }
}
