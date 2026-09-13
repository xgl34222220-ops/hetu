package io.github.xgl34222220.bichen;
import android.content.Context;
import android.util.AtomicFile;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import javax.net.ssl.HttpsURLConnection;
/** Imported YAML and subscription stay in app-private storage, out of diagnostics. */
final class ProxyStore {
 private static final int LIMIT=4*1024*1024;private final File root;
 ProxyStore(Context c){root=new File(c.getFilesDir(),"proxy");}
 File home(){return new File(root,"runtime");}
 boolean exists(){return new File(root,"source.yaml").isFile();}
 static String read(InputStream stream)throws IOException{
  try(InputStream in=stream;ByteArrayOutputStream out=new ByteArrayOutputStream()){
   byte[] b=new byte[8192];int n,total=0;while((n=in.read(b))!=-1){total+=n;if(total>LIMIT)throw new IOException("配置超过 4 MiB");out.write(b,0,n);}
   try{return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(out.toByteArray())).toString();}catch(CharacterCodingException e){throw new IOException("配置必须是 UTF-8 文本");}
  }
 }
 synchronized String yaml()throws IOException{return read(new AtomicFile(new File(root,"source.yaml")).openRead());}
 synchronized void save(String yaml,String subscription)throws Exception{
  MihomoNative.call(new JSONObject().put("action","inspect").put("yaml",yaml));
  if(!root.isDirectory()&&!root.mkdirs())throw new IOException("无法创建配置私有目录");if(exists())write(new File(root,"previous.yaml"),yaml());
  write(new File(root,"source.yaml"),yaml);write(new File(root,"subscription.txt"),subscription==null?"":subscription);
 }
 synchronized String subscription()throws IOException{File f=new File(root,"subscription.txt");return f.isFile()?read(new FileInputStream(f)):"";}
 synchronized void restore()throws Exception{File f=new File(root,"previous.yaml");if(!f.isFile())throw new IOException("没有上一份配置");String old=read(new FileInputStream(f));save(old,"");}
 private static void write(File path,String value)throws IOException{AtomicFile file=new AtomicFile(path);FileOutputStream out=null;try{out=file.startWrite();out.write(value.getBytes(StandardCharsets.UTF_8));file.finishWrite(out);}catch(IOException e){if(out!=null)file.failWrite(out);throw e;}}
 static String fetch(String address)throws IOException{
  URL url;try{url=new URL(address.trim());}catch(Exception e){throw new IOException("订阅地址无效");}long deadline=System.nanoTime()+45000000000L;
  for(int hop=0;hop<4;hop++){
   if(!"https".equals(url.getProtocol())||url.getUserInfo()!=null)throw new IOException("订阅必须使用 HTTPS，且不能包含网址用户名密码");
   HttpsURLConnection c=(HttpsURLConnection)url.openConnection(Proxy.NO_PROXY);c.setInstanceFollowRedirects(false);c.setConnectTimeout(10000);c.setReadTimeout(15000);c.setRequestProperty("User-Agent","Bichen/0.4");
   try{
    if(System.nanoTime()>deadline)throw new IOException("订阅下载超时");int code=c.getResponseCode();
    if(code==301||code==302||code==303||code==307||code==308){String next=c.getHeaderField("Location");if(next==null)throw new IOException("订阅重定向缺少地址");url=new URL(url,next);continue;}
    if(code!=200)throw new IOException("订阅服务器返回 HTTP "+code);if(c.getContentLengthLong()>LIMIT)throw new IOException("订阅超过 4 MiB");
    try(InputStream in=c.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] b=new byte[8192];int n,total=0;while((n=in.read(b))!=-1){if(System.nanoTime()>deadline)throw new IOException("订阅下载超时");total+=n;if(total>LIMIT)throw new IOException("订阅超过 4 MiB");out.write(b,0,n);}return read(new ByteArrayInputStream(out.toByteArray()));}
   }catch(IOException e){throw new IOException("订阅读取失败；原配置保留。请检查网络、地址及服务器返回的配置格式");}finally{c.disconnect();}
  }throw new IOException("订阅跳转次数过多");
 }
}
