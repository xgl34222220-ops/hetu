package bichen.probe;
import android.app.Activity;
import android.os.Bundle;
import android.content.Intent;
import java.net.*;
import java.io.*;
/** Separate application UID: these requests must traverse the actual VPN. */
public final class Probe extends Activity {
 @Override public void onCreate(Bundle b){super.onCreate(b);new Thread(()->{
  boolean allowed=false,blocked=false;String detail="";
  try{allowed=fetch("http://normal.integration.test/check").equals("BICHEN_PROXY_OK");}catch(Exception e){detail=e.getClass().getSimpleName();}
  try{fetch("http://ads.integration.test/check");}catch(IOException expected){blocked=true;}
  sendBroadcast(new Intent("bichen.integration.RESULT").setPackage("io.github.xgl34222220.bichen.preview").putExtra("allowed",allowed).putExtra("blocked",blocked).putExtra("detail",detail));runOnUiThread(this::finish);
 }).start();}
 private String fetch(String url)throws IOException{HttpURLConnection c=(HttpURLConnection)new URL(url).openConnection(Proxy.NO_PROXY);c.setConnectTimeout(6000);c.setReadTimeout(6000);try(InputStream i=c.getInputStream();ByteArrayOutputStream o=new ByteArrayOutputStream()){byte[] b=new byte[1024];int n;while((n=i.read(b))!=-1)o.write(b,0,n);return o.toString("UTF-8");}finally{c.disconnect();}}
}
