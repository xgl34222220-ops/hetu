package io.github.xgl34222220.bichen;
import android.content.Context;
import android.util.AtomicFile;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import javax.net.ssl.HttpsURLConnection;
/** One atomic private document stores YAML, subscription and previous revision together. */
final class ProxyStore {
 private static final int LIMIT=4*1024*1024;private static final Object LOCK=new Object();private final File root;
 ProxyStore(Context c){root=new File(c.getFilesDir(),"proxy");}
 File home(){return new File(root,"runtime");}
 private AtomicFile file(){return new AtomicFile(new File(root,"config.json"));}
 boolean exists(){return new File(root,"config.json").isFile()||new File(root,"config.json.bak").isFile();}
 static String read(InputStream stream)throws IOException{
  if(stream==null)throw new IOException("无法读取所选文件");
  try(InputStream in=stream;ByteArrayOutputStream out=new ByteArrayOutputStream()){
   byte[] b=new byte[8192];int n,total=0;while((n=in.read(b))!=-1){if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("读取已取消");total+=n;if(total>LIMIT)throw new IOException("配置超过 4 MiB");out.write(b,0,n);}
   try{return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(out.toByteArray())).toString();}catch(CharacterCodingException e){throw new IOException("配置必须是 UTF-8 文本");}
  }
 }
 private JSONObject document()throws Exception{
  if(!exists())return new JSONObject();
  try(InputStream in=file().openRead();ByteArrayOutputStream out=new ByteArrayOutputStream()){
   byte[] b=new byte[8192];int n,total=0;while((n=in.read(b))!=-1){total+=n;if(total>32*1024*1024)throw new IOException("配置存储异常");out.write(b,0,n);}return new JSONObject(new String(out.toByteArray(),StandardCharsets.UTF_8));
  }
 }
 String yaml()throws Exception{synchronized(LOCK){String value=document().optString("yaml","");if(value.isEmpty())throw new IOException("请先导入 YAML 配置");return value;}}
 void save(String yaml,String subscription)throws Exception{
  if(yaml.getBytes(StandardCharsets.UTF_8).length>LIMIT)throw new IOException("配置超过 4 MiB");
  MihomoNative.call(new JSONObject().put("action","inspect").put("yaml",yaml));
  synchronized(LOCK){if(MihomoVpnService.engaged)throw new IOException("代理运行中，不替换配置；请先停止");JSONObject current=document();JSONObject next=new JSONObject().put("yaml",yaml).put("subscription",subscription==null?"":subscription);
   if(current.has("yaml"))next.put("previousYaml",current.getString("yaml")).put("previousSubscription",current.optString("subscription",""));write(next);
  }
 }
 String subscription()throws Exception{synchronized(LOCK){return document().optString("subscription","");}}
 void restore()throws Exception{synchronized(LOCK){if(MihomoVpnService.engaged)throw new IOException("请先停止代理");JSONObject d=document();if(!d.has("previousYaml"))throw new IOException("没有上一份配置");JSONObject next=new JSONObject().put("yaml",d.getString("previousYaml")).put("subscription",d.optString("previousSubscription","")).put("previousYaml",d.getString("yaml")).put("previousSubscription",d.optString("subscription",""));write(next);}}
 private void write(JSONObject document)throws IOException{
  if(!root.isDirectory()&&!root.mkdirs())throw new IOException("无法建立配置私有目录");AtomicFile f=file();FileOutputStream out=null;
  try{out=f.startWrite();out.write(document.toString().getBytes(StandardCharsets.UTF_8));f.finishWrite(out);}catch(IOException e){if(out!=null)f.failWrite(out);throw e;}
 }
 static String fetch(String address)throws IOException{
  URL url;try{url=new URL(address.trim());}catch(Exception e){throw new IOException("订阅地址无效");}final long deadline=System.nanoTime()+45000000000L;
  for(int hop=0;hop<4;hop++){
   if(!"https".equals(url.getProtocol())||url.getUserInfo()!=null)throw new IOException("订阅必须使用 HTTPS，不能包含网址用户名密码");
   HttpsURLConnection c=(HttpsURLConnection)url.openConnection(Proxy.NO_PROXY);c.setInstanceFollowRedirects(false);c.setConnectTimeout(10000);c.setReadTimeout(15000);c.setRequestProperty("User-Agent","Bichen/0.4");c.setRequestProperty("Accept-Encoding","identity");
   try{if(System.nanoTime()>deadline)throw new IOException("下载超时");int code=c.getResponseCode();
    if(code==301||code==302||code==303||code==307||code==308){String next=c.getHeaderField("Location");if(next==null)throw new IOException("重定向无地址");url=new URL(url,next);continue;}
    if(code!=200)throw new IOException("HTTP "+code);long expected=c.getContentLengthLong();if(expected>LIMIT)throw new IOException("订阅过大");
    try(InputStream in=c.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] b=new byte[8192];int n,total=0;while(true){long left=(deadline-System.nanoTime())/1000000;if(left<=0||Thread.currentThread().isInterrupted())throw new InterruptedIOException("下载超时或取消");c.setReadTimeout((int)Math.min(15000,Math.max(1,left)));n=in.read(b);if(n<0)break;total+=n;if(total>LIMIT)throw new IOException("订阅过大");out.write(b,0,n);}if(expected>=0&&total!=expected)throw new IOException("下载不完整");return read(new ByteArrayInputStream(out.toByteArray()));}
   }catch(IOException e){throw new IOException("订阅读取失败；原配置保留。检查网络、地址和服务器响应；不在日志输出订阅凭据");}finally{c.disconnect();}
  }throw new IOException("订阅跳转次数过多");
 }
}
