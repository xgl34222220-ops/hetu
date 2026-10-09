package io.github.xgl34222220.hetu;

import android.content.Context;
import android.net.Uri;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.Semaphore;

/** Authenticated Mihomo API client; each request keeps its selected endpoint and credentials together. */
final class MihomoControllerClient {
    private static final int LIMIT=6*1024*1024;
    static final String IPV6_DELAY_URL="https://[2606:4700:4700::1111]/cdn-cgi/trace";
    private static final Semaphore DELAY_SLOTS=new Semaphore(12,true);
    private static final String[] DEFAULT_DELAY_URLS={
        "https://www.gstatic.com/generate_204",
        "https://cp.cloudflare.com/generate_204"
    };

    /** Only a core-confirmed failed probe is a node failure, never an API transport error. */
    static final class DelayFailure extends IOException {
        final boolean timedOut;
        DelayFailure(boolean timedOut) {
            super(timedOut ? "节点测速超时" : "节点测速失败");
            this.timedOut=timedOut;
        }
    }
    static final class ControllerHttpException extends IOException {
        final int statusCode;
        final boolean customApi;
        ControllerHttpException(int statusCode, String detail, boolean customApi) {
            super(statusCode==401
                    ? (customApi ? "自定义 Mihomo 控制接口鉴权失败（401），请核对所填 API 地址、端口与 Secret"
                                 : "本机 Mihomo 控制接口鉴权失败（401），应用凭据与当前运行核心不一致")
                    : "Mihomo 控制接口返回 "+statusCode+(detail.isEmpty()?"":"："+compact(detail)));
            this.statusCode=statusCode;
            this.customApi=customApi;
        }
    }
    private final Context context;
    private final ControllerEndpoint frozenEndpoint;
    MihomoControllerClient(Context c){context=c.getApplicationContext();frozenEndpoint=null;}
    /** A multi-request operation keeps one endpoint/credential pair throughout. */
    MihomoControllerClient(Context c,Map<String,?> snapshot)throws IOException{
        context=c.getApplicationContext();
        frozenEndpoint=new ControllerEndpoint(new HashMap<>(snapshot));
    }

    private android.content.SharedPreferences prefs(){
        return context.getSharedPreferences("hetu",0);
    }

    private static final class ControllerEndpoint {
        final boolean customApi;
        final String host;
        final int port;
        final String secret;

        ControllerEndpoint(Map<String,?> snapshot)throws IOException {
            // SharedPreferences.getAll() takes one atomic copy. Never reread the mode
            // while choosing fields: a concurrent Save must not move a local secret
            // onto a custom endpoint, or a custom secret onto the local controller.
            customApi=Boolean.TRUE.equals(snapshot.get("proxyCustomApiEnabled"));
            host=customApi?string(snapshot,"proxyCustomApiHost","127.0.0.1").trim():"127.0.0.1";
            if(customApi&&(host.isEmpty()||host.length()>253||!host.matches("[A-Za-z0-9.-]+")))
                throw new IOException("自定义 Clash API 地址无效");
            int fallback=customApi?9090:MihomoStartupConfig.CONTROLLER_PORT;
            Object rawPort=snapshot.get(customApi?"proxyCustomApiPort":"proxyControllerPort");
            if(rawPort!=null&&!(rawPort instanceof Integer))throw new IOException("Clash API 端口设置类型无效");
            int requested=rawPort==null?fallback:(Integer)rawPort;
            port=requested>=1024&&requested<=65535?requested:fallback;
            secret=string(snapshot,customApi?"proxyCustomApiSecret":"proxyControllerSecret","");
            if(secret.indexOf('\r')>=0||secret.indexOf('\n')>=0)throw new IOException("Clash API Secret 包含非法换行");
            if(!customApi&&secret.isEmpty())throw new IOException("策略控制接口尚未初始化");
        }

        private static String string(Map<String,?> values,String key,String fallback)throws IOException {
            Object value=values.get(key);
            if(value==null)return fallback;
            if(!(value instanceof String))throw new IOException("Clash API 设置类型无效");
            return (String)value;
        }
    }

    private String customDelayUrl(){
        if(!prefs().getBoolean("proxyCustomDelayUrlEnabled",false))return "";
        String value=prefs().getString("proxyCustomDelayUrl","");
        value=value==null?"":value.trim();
        if(value.length()>2048||!(value.startsWith("https://")||value.startsWith("http://")))return "";
        return value;
    }

    private void addDelayUrls(ArrayList<String> urls,String preferredUrl){
        String custom=customDelayUrl();
        if(!custom.isEmpty())urls.add(custom);
        if(preferredUrl!=null&&!preferredUrl.trim().isEmpty()){
            String preferred=preferredUrl.trim();
            if(!urls.contains(preferred))urls.add(preferred);
        }else{
            for(String item:DEFAULT_DELAY_URLS)if(!urls.contains(item))urls.add(item);
        }
    }

    JSONObject proxies()throws Exception{
        JSONObject root=request("GET","/proxies",null);
        JSONObject p=root.optJSONObject("proxies");
        return p==null?new JSONObject():p;
    }
    JSONObject connections()throws Exception{return request("GET","/connections",null);}
    JSONObject rules()throws Exception{return request("GET","/rules",null,12000);}
    JSONObject ruleProviders()throws Exception{return request("GET","/providers/rules",null,12000);}
    JSONObject proxyProviders()throws Exception{return request("GET","/providers/proxies",null,12000);}
    JSONObject version()throws Exception{return request("GET","/version",null);}
    JSONObject configs()throws Exception{return request("GET","/configs",null);}
    void setTrafficMode(String mode)throws Exception{
        if(!"rule".equals(mode)&&!"global".equals(mode)&&!"direct".equals(mode))throw new IllegalArgumentException("无效的流量模式");
        request("PATCH","/configs",new JSONObject().put("mode",mode));
    }
    // An IPv6-only literal and no fallback: an IPv4 result must never pass this probe.
    long delayIpv6(String node)throws Exception{
        return delayIpv6(node, "");
    }
    long delayIpv6(String node,String provider)throws Exception{
        LatencyProbeBudget budget=LatencyProbeBudget.CURRENT.get();
        boolean ownBudget=budget==null;
        if(ownBudget){budget=new LatencyProbeBudget(LatencyProbeBudget.DEFAULT_TIMEOUT_MS);LatencyProbeBudget.CURRENT.set(budget);}
        boolean acquired=false;
        try{
        budget.acquire(DELAY_SLOTS); acquired=true;
        try{
            String url=URLEncoder.encode(IPV6_DELAY_URL,"UTF-8");
            String path=provider==null||provider.isEmpty()?"/proxies/"+Uri.encode(node)+"/delay":
                "/providers/proxies/"+Uri.encode(provider)+"/"+Uri.encode(node)+"/healthcheck";
            return request("GET",path+"?timeout=4000&url="+url+"&expected=200",null,5500).optLong("delay",-1L);
        }finally{DELAY_SLOTS.release(); acquired=false;}
        }finally{if(acquired)DELAY_SLOTS.release();if(ownBudget){budget.close();LatencyProbeBudget.CURRENT.remove();}}
    }

    void reloadConfig(String path)throws Exception{
        if(path==null||path.trim().isEmpty())throw new IOException("重载配置路径为空");
        request("PUT","/configs?force=true",new JSONObject().put("path",path.trim()),15000);
    }

    void select(String group,String node)throws Exception{
        request("PUT","/proxies/"+Uri.encode(group),new JSONObject().put("name",node));
    }

    void updateRuleProvider(String name)throws Exception{
        request("PUT","/providers/rules/"+Uri.encode(name),null,30000);
    }

    void reloadLocalRuleProvider(String name)throws Exception{
        request("PUT","/providers/rules/"+Uri.encode(name),null,6000);
    }

    void updateProxyProvider(String name)throws Exception{
        request("PUT","/providers/proxies/"+Uri.encode(name),null,30000);
    }

    void healthCheckProxyProvider(String name)throws Exception{
        request("GET","/providers/proxies/"+Uri.encode(name)+"/healthcheck",null,15000);
    }

    void closeConnection(String id)throws Exception{
        request("DELETE","/connections/"+Uri.encode(id),null);
    }

    long delay(String node)throws Exception{
        return delay(node,"","200-399");
    }

    long delay(String node,String preferredUrl,String expected)throws Exception{
        return delayPath("/proxies/"+Uri.encode(node)+"/delay",preferredUrl,expected);
    }

    long providerDelay(String provider,String node,String preferredUrl,String expected)throws Exception{
        return delayPath("/providers/proxies/"+Uri.encode(provider)+"/"+Uri.encode(node)+"/healthcheck",preferredUrl,expected);
    }

    private long delayPath(String path,String preferredUrl,String expected)throws Exception{
        LatencyProbeBudget budget=LatencyProbeBudget.CURRENT.get();
        boolean ownBudget=budget==null;
        if(ownBudget){budget=new LatencyProbeBudget(LatencyProbeBudget.DEFAULT_TIMEOUT_MS);LatencyProbeBudget.CURRENT.set(budget);}
        boolean acquired=false;
        try{
        budget.acquire(DELAY_SLOTS); acquired=true;
        try{
            DelayFailure last=null;
            ArrayList<String> urls=new ArrayList<>();
            addDelayUrls(urls,preferredUrl);
            String expectedRange=(expected==null||expected.trim().isEmpty())?"200-399":expected.trim();
            for(String rawUrl:urls){
                budget.check();
                try{
                    String test=URLEncoder.encode(rawUrl,"UTF-8");
                    String status=URLEncoder.encode(expectedRange,"UTF-8");
                    // A dead node used to hold one of the six probe workers for 10 s per URL.
                    // 5 s matches the group endpoint and keeps a wave of leaves moving.
                    JSONObject v=request("GET",path+"?timeout=5000&url="+test+"&expected="+status,null,6500);
                    long d=v.optLong("delay",-1);
                    if(d>0)return d;
                    if(d==0)last=new DelayFailure(false);
                    else throw new IOException("测速接口未返回延迟数据");
                }catch(ControllerHttpException error){
                    if(error.statusCode==504)last=new DelayFailure(true);
                    else if(error.statusCode==503)last=new DelayFailure(false);
                    else throw error;
                }
            }
            if(last!=null)throw last;
            throw new IOException("没有可用的测速地址");
        }finally{
            DELAY_SLOTS.release(); acquired=false;
        }
        }finally{if(acquired)DELAY_SLOTS.release();if(ownBudget){budget.close();LatencyProbeBudget.CURRENT.remove();}}
    }

    JSONObject groupDelay(String group)throws Exception{
        return groupDelay(group,"","200-399");
    }

    JSONObject groupDelay(String group,String preferredUrl,String expected)throws Exception{
        LatencyProbeBudget budget=LatencyProbeBudget.CURRENT.get();
        boolean ownBudget=budget==null;
        if(ownBudget){budget=new LatencyProbeBudget(LatencyProbeBudget.DEFAULT_TIMEOUT_MS);LatencyProbeBudget.CURRENT.set(budget);}
        try{
        Exception last=null;
        ArrayList<String> urls=new ArrayList<>();
        addDelayUrls(urls,preferredUrl);
        String expectedRange=(expected==null||expected.trim().isEmpty())?"200-399":expected.trim();
        for(String rawUrl:urls){
                budget.check();
            try{
                String test=URLEncoder.encode(rawUrl,"UTF-8");
                String status=URLEncoder.encode(expectedRange,"UTF-8");
                return request(
                    "GET",
                    "/group/"+Uri.encode(group)+"/delay?timeout=5000&url="+test+"&expected="+status,
                    null,
                    7000
                );
            }catch(ControllerHttpException e){
                if(e.statusCode==401)throw e;
                last=e;
            }catch(Exception e){last=e;}
        }
        if(last!=null)throw last;
        throw new IOException("策略组延迟测试失败");
        }finally{if(ownBudget){budget.close();LatencyProbeBudget.CURRENT.remove();}}
    }

    void closeAll()throws Exception{request("DELETE","/connections",null);}
    boolean waitReady(long timeoutMs){
        long end=android.os.SystemClock.elapsedRealtime()+timeoutMs;
        do{try{version();return true;}catch(ControllerHttpException error){if(error.statusCode==401)return false;}
            catch(Exception ignored){}android.os.SystemClock.sleep(120);}while(android.os.SystemClock.elapsedRealtime()<end);
        return false;
    }

    private JSONObject request(String method,String path,JSONObject body)throws Exception{
        return request(method,path,body,6500);
    }

    private JSONObject request(String method,String path,JSONObject body,int socketTimeoutMs)throws Exception{
        byte[] payload=body==null?new byte[0]:body.toString().getBytes(StandardCharsets.UTF_8);
        ControllerEndpoint endpoint=frozenEndpoint!=null?frozenEndpoint:new ControllerEndpoint(prefs().getAll());
        Socket socket=new Socket();
        LatencyProbeBudget budget=LatencyProbeBudget.CURRENT.get();
        try{
            if(budget!=null)budget.register(socket);
            InetAddress address=budget==null?InetAddress.getByName(endpoint.host):budget.resolve(endpoint.host);
            socket.connect(new InetSocketAddress(address,endpoint.port),budget==null?2200:Math.min(2200,budget.remainingMillis()));
            socket.setSoTimeout(budget==null?socketTimeoutMs:Math.min(socketTimeoutMs,budget.remainingMillis()));
            OutputStream raw=socket.getOutputStream();
            StringBuilder head=new StringBuilder();
            head.append(method).append(' ').append(path).append(" HTTP/1.1\r\n")
                .append("Host: ").append(endpoint.host).append(':').append(endpoint.port).append("\r\n");
            if(!endpoint.secret.isEmpty())head.append("Authorization: Bearer ").append(endpoint.secret).append("\r\n");
            head.append("Accept: application/json\r\n")
                .append("Connection: close\r\n");
            if(payload.length>0)head.append("Content-Type: application/json; charset=utf-8\r\nContent-Length: ").append(payload.length).append("\r\n");
            head.append("\r\n");
            // Go's HTTP parser retains header value bytes. Preserve a configured UTF-8
            // secret exactly instead of silently replacing every non-ASCII character.
            raw.write(head.toString().getBytes(StandardCharsets.UTF_8));
            if(payload.length>0)raw.write(payload);
            raw.flush();

            BufferedInputStream in=new BufferedInputStream(socket.getInputStream());
            String status=readLine(in);if(status==null||!status.startsWith("HTTP/"))throw new IOException("Mihomo 控制接口返回无效 HTTP 响应");
            String[] bits=status.split(" ",3);if(bits.length<2)throw new IOException("Mihomo 控制接口状态行无效");
            int code;try{code=Integer.parseInt(bits[1]);}catch(NumberFormatException e){throw new IOException("Mihomo 控制接口状态码无效");}
            HashMap<String,String> headers=new HashMap<>();String line;
            while((line=readLine(in))!=null&&!line.isEmpty()){int colon=line.indexOf(':');if(colon>0)headers.put(line.substring(0,colon).trim().toLowerCase(Locale.ROOT),line.substring(colon+1).trim());}
            // Do not echo an authentication server's body: it may contain credentials.
            if(code==401)throw new ControllerHttpException(code,"",endpoint.customApi);
            byte[] bytes;
            String transfer=headers.get("transfer-encoding");
            if(transfer!=null&&transfer.toLowerCase(Locale.ROOT).contains("chunked"))bytes=readChunked(in);
            else if(headers.containsKey("content-length")){
                int len;try{len=Integer.parseInt(headers.get("content-length"));}catch(Exception e){throw new IOException("Mihomo 控制接口 Content-Length 无效");}
                if(len<0||len>LIMIT)throw new IOException("控制接口响应过大");bytes=readFixed(in,len);
            }else bytes=readToEnd(in);
            String text=new String(bytes,StandardCharsets.UTF_8);
            if(code<200||code>=300)throw new ControllerHttpException(code,text.trim(),endpoint.customApi);
            if(budget!=null)budget.check();
            return text.trim().isEmpty()?new JSONObject():new JSONObject(text);
        }catch(Exception error){
            if(budget!=null)budget.check();
            throw error;
        }finally{try{socket.close();}catch(Exception ignored){}if(budget!=null)budget.unregister(socket);}
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
