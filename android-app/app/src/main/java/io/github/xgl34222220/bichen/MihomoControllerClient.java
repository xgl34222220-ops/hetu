package io.github.xgl34222220.bichen;

import android.content.Context;
import android.net.Uri;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Authenticated localhost-only Mihomo Clash API client for strategy, delay and connections UI.
 * Uses a loopback socket instead of HttpURLConnection so Android's cleartext policy remains
 * strict for every external host while the private 127.0.0.1 controller can still be reached.
 */
final class MihomoControllerClient {
    private static final int PORT=MihomoStartupConfig.CONTROLLER_PORT;
    private static final int LIMIT=2*1024*1024;
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
    boolean waitReady(long timeoutMs){
        long end=android.os.SystemClock.elapsedRealtime()+timeoutMs;
        do{try{version();return true;}catch(Exception ignored){}android.os.SystemClock.sleep(120);}while(android.os.SystemClock.elapsedRealtime()<end);
        return false;
    }

    private JSONObject request(String method,String path,JSONObject body)throws Exception{
        byte[] payload=body==null?new byte[0]:body.toString().getBytes(StandardCharsets.UTF_8);
        Socket socket=new Socket();
        try{
            socket.connect(new InetSocketAddress(InetAddress.getByName("127.0.0.1"),PORT),2200);
            socket.setSoTimeout(6500);
            OutputStream raw=socket.getOutputStream();
            StringBuilder head=new StringBuilder();
            head.append(method).append(' ').append(path).append(" HTTP/1.1\r\n")
                .append("Host: 127.0.0.1:").append(PORT).append("\r\n")
                .append("Authorization: Bearer ").append(secret()).append("\r\n")
                .append("Accept: application/json\r\n")
                .append("Connection: close\r\n");
            if(payload.length>0)head.append("Content-Type: application/json; charset=utf-8\r\nContent-Length: ").append(payload.length).append("\r\n");
            head.append("\r\n");
            raw.write(head.toString().getBytes(StandardCharsets.US_ASCII));
            if(payload.length>0)raw.write(payload);
            raw.flush();

            BufferedInputStream in=new BufferedInputStream(socket.getInputStream());
            String status=readLine(in);if(status==null||!status.startsWith("HTTP/"))throw new IOException("Mihomo 控制接口返回无效 HTTP 响应");
            String[] bits=status.split(" ",3);if(bits.length<2)throw new IOException("Mihomo 控制接口状态行无效");
            int code;try{code=Integer.parseInt(bits[1]);}catch(NumberFormatException e){throw new IOException("Mihomo 控制接口状态码无效");}
            HashMap<String,String> headers=new HashMap<>();String line;
            while((line=readLine(in))!=null&&!line.isEmpty()){int colon=line.indexOf(':');if(colon>0)headers.put(line.substring(0,colon).trim().toLowerCase(Locale.ROOT),line.substring(colon+1).trim());}
            byte[] bytes;
            String transfer=headers.get("transfer-encoding");
            if(transfer!=null&&transfer.toLowerCase(Locale.ROOT).contains("chunked"))bytes=readChunked(in);
            else if(headers.containsKey("content-length")){
                int len;try{len=Integer.parseInt(headers.get("content-length"));}catch(Exception e){throw new IOException("Mihomo 控制接口 Content-Length 无效");}
                if(len<0||len>LIMIT)throw new IOException("控制接口响应过大");bytes=readFixed(in,len);
            }else bytes=readToEnd(in);
            String text=new String(bytes,StandardCharsets.UTF_8);
            if(code<200||code>=300)throw new IOException("Mihomo 控制接口返回 "+code+(text.trim().isEmpty()?"":"："+compact(text)));
            return text.trim().isEmpty()?new JSONObject():new JSONObject(text);
        }finally{try{socket.close();}catch(Exception ignored){}}
    }

    private static String readLine(InputStream in)throws IOException{
        ByteArrayOutputStream out=new ByteArrayOutputStream();int c,previous=-1;
        while((c=in.read())!=-1){if(previous=='\r'&&c=='\n'){byte[] b=out.toByteArray();int n=b.length;return new String(b,0,n>0&&b[n-1]=='\r'?n-1:n,StandardCharsets.ISO_8859_1);}out.write(c);previous=c;if(out.size()>16384)throw new IOException("控制接口响应头过大");}
        if(out.size()==0)return null;return new String(out.toByteArray(),StandardCharsets.ISO_8859_1);
    }
    private static byte[] readFixed(InputStream in,int len)throws IOException{byte[] b=new byte[len];int off=0,n;while(off<len&&(n=in.read(b,off,len-off))!=-1)off+=n;if(off!=len)throw new EOFException("控制接口响应提前结束");return b;}
    private static byte[] readToEnd(InputStream in)throws IOException{ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[4096];int n,total=0;while((n=in.read(b))!=-1){total+=n;if(total>LIMIT)throw new IOException("控制接口响应过大");out.write(b,0,n);}return out.toByteArray();}
    private static byte[] readChunked(InputStream in)throws IOException{
        ByteArrayOutputStream out=new ByteArrayOutputStream();int total=0;
        while(true){String line=readLine(in);if(line==null)throw new EOFException("分块响应提前结束");int semi=line.indexOf(';');String hex=(semi>=0?line.substring(0,semi):line).trim();int len;try{len=Integer.parseInt(hex,16);}catch(Exception e){throw new IOException("控制接口分块长度无效");}if(len==0){while((line=readLine(in))!=null&&!line.isEmpty()){}break;}total+=len;if(total>LIMIT)throw new IOException("控制接口响应过大");byte[] chunk=readFixed(in,len);out.write(chunk);String end=readLine(in);if(end==null)throw new EOFException("分块响应提前结束");}
        return out.toByteArray();
    }
    private static String compact(String s){s=s.replace('\n',' ').replace('\r',' ').trim();return s.length()>220?s.substring(0,220)+"…":s;}
}
