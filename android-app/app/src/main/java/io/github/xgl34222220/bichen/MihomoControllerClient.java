package io.github.xgl34222220.bichen;

import android.content.Context;
import android.net.Uri;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;

/** Authenticated localhost-only Mihomo Clash API client for strategy, delay and connections UI. */
final class MihomoControllerClient {
    private final Context context;
    MihomoControllerClient(Context c){context=c.getApplicationContext();}

    private String secret()throws IOException{
        String value=context.getSharedPreferences("bichen",0).getString("proxyControllerSecret","");
        if(value==null||value.isEmpty())throw new IOException("策略控制接口尚未初始化");
        return value;
    }

    JSONObject proxies()throws Exception{
        JSONObject root=request("GET","/proxies",null);
        JSONObject p=root.optJSONObject("proxies");
        return p==null?new JSONObject():p;
    }
    JSONObject connections()throws Exception{return request("GET","/connections",null);}
    JSONObject version()throws Exception{return request("GET","/version",null);}
    void select(String group,String node)throws Exception{request("PUT","/proxies/"+Uri.encode(group),new JSONObject().put("name",node));}
    long delay(String node)throws Exception{
        String test=URLEncoder.encode("https://www.gstatic.com/generate_204","UTF-8");
        JSONObject v=request("GET","/proxies/"+Uri.encode(node)+"/delay?timeout=5000&url="+test,null);
        long d=v.optLong("delay",-1);if(d<0)throw new IOException("测速超时");return d;
    }
    void closeAll()throws Exception{request("DELETE","/connections",null);}
    void waitReady(long timeoutMs)throws Exception{
        long end=android.os.SystemClock.elapsedRealtime()+timeoutMs;Exception last=null;
        do{try{version();return;}catch(Exception e){last=e;}android.os.SystemClock.sleep(120);}while(android.os.SystemClock.elapsedRealtime()<end);
        throw new IOException("Mihomo 已启动，但策略控制接口尚未就绪",last);
    }

    private JSONObject request(String method,String path,JSONObject body)throws Exception{
        HttpURLConnection c=(HttpURLConnection)new URL("http://127.0.0.1:"+MihomoStartupConfig.CONTROLLER_PORT+path).openConnection();
        c.setConnectTimeout(2200);c.setReadTimeout(6500);c.setRequestMethod(method);c.setRequestProperty("Authorization","Bearer "+secret());c.setRequestProperty("Accept","application/json");
        if(body!=null){byte[] bytes=body.toString().getBytes(StandardCharsets.UTF_8);c.setDoOutput(true);c.setRequestProperty("Content-Type","application/json; charset=utf-8");c.setFixedLengthStreamingMode(bytes.length);try(OutputStream out=c.getOutputStream()){out.write(bytes);}}
        int code=c.getResponseCode();InputStream in=code>=200&&code<300?c.getInputStream():c.getErrorStream();String text=read(in);c.disconnect();
        if(code<200||code>=300)throw new IOException("Mihomo 控制接口返回 "+code+(text.isEmpty()?"":"："+compact(text)));
        if(text.trim().isEmpty())return new JSONObject();return new JSONObject(text);
    }
    private static String read(InputStream in)throws IOException{if(in==null)return"";ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[4096];int n,total=0;while((n=in.read(b))!=-1){total+=n;if(total>2*1024*1024)throw new IOException("控制接口响应过大");out.write(b,0,n);}return new String(out.toByteArray(),StandardCharsets.UTF_8);}
    private static String compact(String s){s=s.replace('\n',' ').replace('\r',' ').trim();return s.length()>220?s.substring(0,220)+"…":s;}
}
