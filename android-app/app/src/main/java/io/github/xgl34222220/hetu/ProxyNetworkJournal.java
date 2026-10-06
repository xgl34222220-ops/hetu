package io.github.xgl34222220.hetu;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;
import org.json.*;

/** App-private, append-only diagnostic metadata. No exception messages or network addresses. */
final class ProxyNetworkJournal {
    enum Stage { NETWORK_CHANGE, RECOVERY_REQUEST, REPAIR_RESULT, HEALTH_RESULT, EGRESS_REQUEST, EGRESS_TARGET_RESULT, EGRESS_RESULT, STALE_RESULT }
    enum Target { GOOGLE_204, CLOUDFLARE_204 }
    enum Outcome { WAITING, CHECKING, READY, UNVERIFIED, CAPTIVE, BLOCKED, REACHABLE, HEALTHY, DEGRADED, UPGRADE_REQUIRED, STOPPED, UNKNOWN, REQUESTED, ACKNOWLEDGED, FAILED, DISCARDED }
    static final class Event {
        final String id;
        final String line;
        Event(String id,String line){this.id=id;this.line=line;}
    }
    private static final Object FILE_LOCK=new Object();
    private static final int RECENT_LIMIT=64, READ_BYTES=128*1024, VIEW_BYTES=48*1024, MAX_EVENT_BYTES=8192;
    static final long STORAGE_BYTES=2*1024*1024;
    private static final int STATUS_RESERVE=16;
    private static final String CLASS_NAME="[a-zA-Z_$][a-zA-Z0-9_$]*(?:\\.[a-zA-Z_$][a-zA-Z0-9_$]*)*";
    private static final String METHOD_NAME="(?:<init>|<clinit>|[a-zA-Z_$][a-zA-Z0-9_$-]{0,159})";
    private static final Pattern CLASS_SYMBOL=Pattern.compile(CLASS_NAME);
    private static final Pattern METHOD_SYMBOL=Pattern.compile(METHOD_NAME);
    private static final Pattern FRAME_LINE=Pattern.compile("-?[0-9]{1,10}");
    private final File directory;
    private final int chunkBytes;
    private final long storageBytes;
    private final String copySecret;

    static final class JournalFullException extends IOException { JournalFullException(){super("journal-quota");} }

    ProxyNetworkJournal(Context context){this(new File(context.getNoBackupFilesDir(),"hetu-network-events"),256*1024,
            STORAGE_BYTES,context.getSharedPreferences("hetu",Context.MODE_PRIVATE).getString("proxyControllerSecret",""));}
    ProxyNetworkJournal(File directory,int chunkBytes){this(directory,chunkBytes,STORAGE_BYTES,"");}
    ProxyNetworkJournal(File directory,int chunkBytes,long storageBytes){this(directory,chunkBytes,storageBytes,"");}
    ProxyNetworkJournal(File directory,int chunkBytes,long storageBytes,String secret){
        this.directory=directory;this.chunkBytes=Math.max(1024,chunkBytes);
        this.storageBytes=Math.max(2048L,storageBytes);copySecret=secret==null?"":secret;
    }

    static boolean uuid(String text){
        if(text==null||text.length()!=36)return false;
        try{return UUID.fromString(text).toString().equals(text);}catch(IllegalArgumentException invalid){return false;}
    }
    static Event inSession(Event event,String session){
        try{return uuid(session)?new Event(event.id,new JSONObject(event.line).put("session",session).toString()):event;}
        catch(JSONException impossible){throw new IllegalStateException(impossible);}
    }

    static Outcome outcome(String value){
        try{return Outcome.valueOf(value.toUpperCase(Locale.ROOT).replace('-','_'));}
        catch(Exception ignored){return Outcome.UNKNOWN;}
    }
    static Event capture(Stage stage,long epoch,Outcome outcome,int resultCode,Throwable error,String parent){
        String id=UUID.randomUUID().toString();
        try{
            JSONObject record=new JSONObject().put("schema",1).put("id",id)
                    .put("timeMs",System.currentTimeMillis()).put("elapsedMs",SystemClock.elapsedRealtime())
                    .put("stage",stage.name()).put("epoch",epoch).put("outcome",outcome.name()).put("code",resultCode);
            // A correlation is always a generated UUID, never arbitrary user text.
            if(uuid(parent))record.put("parent",parent);
            JSONArray causes=new JSONArray();
            Set<Throwable> seen=Collections.newSetFromMap(new IdentityHashMap<>());
            Throwable cause=error;
            for(;cause!=null&&causes.length()<4&&seen.add(cause);cause=cause.getCause()){
                JSONObject entry=new JSONObject().put("type",symbol(cause.getClass().getName()));
                JSONArray frames=new JSONArray();
                StackTraceElement[] trace=cause.getStackTrace();
                for(int i=0;i<Math.min(trace.length,3);i++){
                    String cls=symbol(trace[i].getClassName()),method=methodSymbol(trace[i].getMethodName());
                    frames.put(cls.equals("redacted-symbol")||method.equals("redacted-symbol")?"redacted-frame":cls+"."+method+":"+trace[i].getLineNumber());
                }
                if(trace.length>3)entry.put("framesTruncated",true);
                entry.put("frames",frames);causes.put(entry);
            }
            if(causes.length()>0)record.put("causes",causes);
            if(cause!=null)record.put("causesTruncated",true);
            String line=record.toString();
            if(line.getBytes(StandardCharsets.UTF_8).length>MAX_EVENT_BYTES)throw new IllegalArgumentException("event too large");
            return new Event(id,line);
        }catch(JSONException impossible){throw new IllegalStateException(impossible);}
    }

    private static String symbol(String text){return text!=null&&text.length()<=160&&CLASS_SYMBOL.matcher(text).matches()?text:"redacted-symbol";}
    private static String methodSymbol(String text){return text!=null&&text.length()<=160&&METHOD_SYMBOL.matcher(text).matches()?text:"redacted-symbol";}
    private static boolean frameSymbol(String frame){
        if(frame.length()>340)return false;
        int colon=frame.lastIndexOf(':'),dot=frame.lastIndexOf('.',colon);
        return dot>0&&colon>dot+1&&!symbol(frame.substring(0,dot)).equals("redacted-symbol")
                &&!methodSymbol(frame.substring(dot+1,colon)).equals("redacted-symbol")
                &&FRAME_LINE.matcher(frame.substring(colon+1)).matches();
    }
    private static String faultCode(String code){
        boolean known=code!=null&&code.length()<=96&&code.matches("session-manifest-missing|health-read-failed|service-recreated|not-running|transaction-in-progress|core-(identity(-read)?|exited)|watchdog-(missing|exited|identity(-read)?)|routing-journal|native-device|socket-read|ipv[46]-(policy-rule|local-route|rule-read|route-read)|listener-[0-9]{1,5}-(tcp|udp)|[46]-(nat|mangle|filter)-(read|hook|HETU_(DNSOUT|DNSPRE|KFWD|KOUT|MOUT|MPRE|NOUT|NPRE|QUICFWD|QUICOUT|V6FWD|V6OUT|WRFWD|WROUT))");
        return known?code:"unclassified-fault";
    }

    static Event captureHealth(long epoch,Outcome outcome,String fault,Throwable error){
        Event base=capture(Stage.HEALTH_RESULT,epoch,outcome,0,error,null);
        try{
            JSONObject record=new JSONObject(base.line);
            JSONArray faults=new JSONArray();
            if(fault!=null&&!fault.isEmpty()){
                boolean trimmed=fault.length()>4096;
                String[] codes=(trimmed?fault.substring(0,4096):fault).split(",",17);
                for(int i=0;i<Math.min(16,codes.length);i++)faults.put(faultCode(codes[i]));
                if(trimmed||codes.length>16)record.put("faultsTruncated",true);
            }
            record.put("faults",faults);return new Event(base.id,record.toString());
        }catch(JSONException impossible){throw new IllegalStateException(impossible);}
    }

    static Event captureEgress(long epoch,Target target,int code,Throwable error,String parent){
        Event base=capture(Stage.EGRESS_TARGET_RESULT,epoch,code==204?Outcome.REACHABLE:Outcome.UNVERIFIED,code,error,parent);
        try{return new Event(base.id,new JSONObject(base.line).put("target",target.name()).toString());}
        catch(JSONException impossible){throw new IllegalStateException(impossible);}
    }

    void append(Event event)throws IOException{append(event,null);}
    void append(Event event,Runnable written)throws IOException{
        byte[] line=(event.line+"\n").getBytes(StandardCharsets.UTF_8);
        if(line.length>MAX_EVENT_BYTES+1)throw new IOException("journal-event-size");
        synchronized(FILE_LOCK){
            if(!directory.isDirectory()&&!directory.mkdirs())throw new IOException("journal-directory");
            File[] files=files();File latest=files.length==0?null:files[files.length-1];
            File paused=new File(directory,"quota-reached");long used=bytes(files);
            if(paused.exists()||used>storageBytes-STATUS_RESERVE-line.length-1){
                // Reserve the marker's space up front; preserve every historical byte.
                if(!paused.exists()&&used<storageBytes-STATUS_RESERVE)paused.createNewFile();
                throw new JournalFullException();
            }
            File active;
            if(latest!=null&&latest.length()<=chunkBytes-line.length-1)active=latest;
            else{
                long next=latest==null?1:Long.parseLong(latest.getName().substring(7,27))+1;
                if(next<=0L)throw new IOException("journal-segment-index");
                active=new File(directory,String.format(Locale.ROOT,"events-%020d.jsonl",next));
            }
            boolean incomplete=false;
            if(active.length()>0)try(RandomAccessFile tail=new RandomAccessFile(active,"r")){
                tail.seek(tail.length()-1);incomplete=tail.read()!=10;
            }
            try(FileOutputStream stream=new FileOutputStream(active,true)){
                // A killed writer's partial line remains evidence; separate the next record.
                if(incomplete)stream.write('\n');
                stream.write(line);stream.getChannel().force(false);
            }
            if(written!=null)written.run();
        }
    }

    private static long bytes(File[] files)throws IOException{
        long total=0L;
        for(File file:files){long size=file.length();if(Long.MAX_VALUE-total<size)throw new IOException("journal-size");total+=size;}
        return total;
    }

    private File[] files()throws IOException{
        File[] files=directory.listFiles((dir,name)->name.matches("events-[0-9]{20}\\.jsonl"));
        if(files==null)throw new IOException("journal-list");
        Arrays.sort(files,Comparator.comparing(File::getName));return files;
    }

    private JSONObject project(JSONObject record)throws JSONException{
        if(record.optInt("schema")!=1||!uuid(record.optString("id")))throw new JSONException("journal-schema");
        final Stage stage;
        try{stage=Stage.valueOf(record.optString("stage"));}catch(IllegalArgumentException invalid){throw new JSONException("journal-stage");}
        JSONObject safe=new JSONObject().put("schema",1).put("id",record.getString("id"))
                .put("timeMs",Math.max(0L,record.optLong("timeMs"))).put("elapsedMs",Math.max(0L,record.optLong("elapsedMs")))
                .put("epoch",Math.max(0L,record.optLong("epoch"))).put("stage",stage.name())
                .put("outcome",outcome(record.optString("outcome")).name()).put("code",record.optInt("code"));
        for(String key:new String[]{"parent","session"})if(uuid(record.optString(key)))safe.put(key,record.getString(key));
        if(record.has("target"))try{safe.put("target",Target.valueOf(record.optString("target")).name());}
        catch(IllegalArgumentException invalid){safe.put("metadataRedacted",true);}
        JSONArray faults=record.optJSONArray("faults");
        if(faults!=null){
            JSONArray allowed=new JSONArray();
            for(int i=0;i<Math.min(16,faults.length());i++)allowed.put(faultCode(faults.optString(i)));
            safe.put("faults",allowed);if(faults.length()>16)safe.put("faultsTruncated",true);
        }
        JSONArray causes=record.optJSONArray("causes");
        if(causes!=null){
            JSONArray allowed=new JSONArray();
            for(int i=0;i<Math.min(4,causes.length());i++){
                JSONObject cause=causes.optJSONObject(i);if(cause==null)continue;
                JSONObject item=new JSONObject().put("type",symbol(cause.optString("type")));
                JSONArray frames=cause.optJSONArray("frames"),locations=new JSONArray();
                if(frames!=null)for(int k=0;k<Math.min(3,frames.length());k++){
                    String frame=frames.optString(k);
                    boolean valid=frameSymbol(frame);
                    locations.put(valid?frame:"redacted-frame");if(!valid)safe.put("metadataRedacted",true);
                }
                item.put("frames",locations);
                if(cause.optBoolean("framesTruncated")||frames!=null&&frames.length()>3)item.put("framesTruncated",true);
                allowed.put(item);
            }
            safe.put("causes",allowed);if(causes.length()>4)safe.put("causesTruncated",true);
        }
        for(String flag:new String[]{"faultsTruncated","causesTruncated"})if(record.optBoolean(flag))safe.put(flag,true);
        Set<String> keys=new HashSet<>(Arrays.asList("schema","id","timeMs","elapsedMs","epoch","stage","outcome","code",
                "parent","session","target","faults","causes","faultsTruncated","causesTruncated"));
        for(Iterator<String> it=record.keys();it.hasNext();)if(!keys.contains(it.next()))safe.put("metadataRedacted",true);
        redactValues(safe);
        return safe;
    }

    private void redactValues(Object value)throws JSONException{
        if(copySecret.isEmpty())return;
        if(value instanceof JSONObject){
            JSONObject object=(JSONObject)value;
            for(Iterator<String> it=object.keys();it.hasNext();){
                String key=it.next();Object field=object.get(key);
                if(field instanceof String)object.put(key,((String)field).replace(copySecret,"[redacted]"));
                else redactValues(field);
            }
        }else if(value instanceof JSONArray){
            JSONArray array=(JSONArray)value;
            for(int i=0;i<array.length();i++){
                Object field=array.get(i);
                if(field instanceof String)array.put(i,((String)field).replace(copySecret,"[redacted]"));else redactValues(field);
            }
        }
    }

    String recent(){
        synchronized(FILE_LOCK){
            if(!directory.exists())return "尚无网络事件记录；网站可达与网络完整性分别核验。\n"
                    +"shown=0 viewTruncated=false storageBytes=0 storageLimitBytes="+storageBytes+" storagePaused=false";
            try{
                File[] files=files();
                ArrayDeque<String> recent=new ArrayDeque<>();
                int remaining=READ_BYTES,unreadable=0,renderBytes=0;
                boolean truncated=false;
                scan:for(int i=files.length-1;i>=0;i--){
                    if(remaining<=0){truncated=true;break;}
                    byte[] tail;
                    long offset;
                    try(RandomAccessFile input=new RandomAccessFile(files[i],"r")){
                        int size=(int)Math.min(input.length(),remaining);offset=input.length()-size;
                        tail=new byte[size];input.seek(offset);input.readFully(tail);remaining-=size;
                    }
                    String text=new String(tail,StandardCharsets.UTF_8);
                    if(offset>0){truncated=true;int newline=text.indexOf('\n');text=newline<0?"":text.substring(newline+1);}
                    String[] lines=text.split("\n");
                    for(int j=lines.length-1;j>=0;j--){
                        if(lines[j].isEmpty())continue;
                        try{JSONObject record=new JSONObject(lines[j]);
                            String safe=project(record).toString();
                            int size=safe.getBytes(StandardCharsets.UTF_8).length+1;
                            if(size>VIEW_BYTES-2048-renderBytes){truncated=true;break scan;}
                            recent.addFirst(safe);renderBytes+=size;
                            if(recent.size()==RECENT_LIMIT){truncated|=j>0||offset>0||i>0;break scan;}
                        }catch(JSONException invalid){unreadable++;}
                    }
                }
                long used=bytes(files);
                boolean paused=new File(directory,"quota-reached").exists()||used>=storageBytes-STATUS_RESERVE;
                return "最近网络事件（最多 64 条 / 48 KiB，读取最多 128 KiB）；旧分段保留。\n网站可达与规则 / DNS / 守护完整性分别核验。\n"
                        +"segments="+files.length+" shown="+recent.size()+" unreadableLines="+unreadable+" viewTruncated="+truncated
                        +" storageBytes="+used+" storageLimitBytes="+storageBytes+" storagePaused="+paused+"\n"
                        +(truncated?"记录视图已截断：只显示近期完整记录，旧分段仍保留。\n":"")
                        +(paused?"存储达到上限：新增记录已暂停，历史未删除；网络守护继续，缺口见丢弃计数。\n":"")
                        +(recent.isEmpty()?"尚无完整网络事件记录":String.join("\n",recent));
            }catch(IOException error){return "网络事件暂不可读："+error.getClass().getSimpleName()+"；不代表网络健康。";}
        }
    }

    private static String choice(String value,String allowed,String fallback){
        return value!=null&&Arrays.asList(allowed.split(",")).contains(value)?value:fallback;
    }
    private String trace(SharedPreferences prefs,String key){
        String value=prefs.getString(key,"");
        return uuid(value)?!copySecret.isEmpty()&&value.contains(copySecret)?"[redacted-id]":value:"unknown";
    }

    String report(SharedPreferences prefs){
        String status="\n\n当前记录状态（生成 ID 不等于已落盘；队列/限额造成的缺口单独列出）：\n"
                +"networkEpoch="+Math.max(0L,prefs.getLong("proxyNetworkEpoch",0L))+"\nnetworkSession="+trace(prefs,"proxyNetworkSessionId")
                +"\nphysicalNetwork="+choice(prefs.getString("proxyPhysicalNetworkState",""),"waiting,checking,ready,unverified,captive,blocked","unknown")
                +"\nintegrity="+choice(prefs.getString("proxyNetworkIntegrity",""),"healthy,degraded,upgrade-required,unknown,stopped","unknown")
                +"\negress="+choice(prefs.getString("proxyPolicyEgressState",""),"reachable,unverified,unknown,stopped","unverified")
                +"\nhealthTrace="+trace(prefs,"proxyNetworkHealthTraceId")+"\negressTrace="+trace(prefs,"proxyPolicyEgressTraceId")
                +"\njournalDropped="+Math.max(0L,prefs.getLong("proxyNetworkJournalDropped",0L))
                +"\njournalLastWrittenId="+trace(prefs,"proxyNetworkJournalLastWrittenId")
                +"\njournalLastDroppedId="+trace(prefs,"proxyNetworkJournalLastDroppedId")
                +"\njournalError="+choice(prefs.getString("proxyNetworkJournalError",""),"IOException,SecurityException,JournalFullException,queue-full,writer-closed","unknown");
        return recent()+status;
    }
}
