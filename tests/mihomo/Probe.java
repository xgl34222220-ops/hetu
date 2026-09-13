package bichen.proxyprobe;
import android.app.*;
import android.os.Bundle;
import java.net.*;
import java.io.*;
/** Independent app UID, not excluded by Bichen's VPN. No public network target. */
public final class Probe extends Instrumentation {
 private String mode;private StringBuilder log=new StringBuilder();
 @Override public void onCreate(Bundle args){super.onCreate(args);mode=args.getString("mode","path");start();}
 private String get(String host,int port,boolean hold)throws Exception{
  try(Socket s=new Socket()){s.connect(new InetSocketAddress(host,port),4000);s.setSoTimeout(4000);s.getOutputStream().write(("GET /bichen-e2e HTTP/1.0\r\nHost: "+host+"\r\n\r\n").getBytes("US-ASCII"));byte[] bytes=new byte[2048];int n=s.getInputStream().read(bytes);String value=n<0?"":new String(bytes,0,n,"US-ASCII");if(hold)Thread.sleep(2200);return value;}
 }
 private void check(boolean pass,String what){if(!pass)throw new AssertionError(what);log.append("PASS ").append(what).append('\n');}
 @Override public void onStart(){Bundle b=new Bundle();try{
  if("stopped".equals(mode)||"bypass".equals(mode)){boolean routed=false;try{routed=get("198.51.100.7",18080,false).contains("BICHEN_PROXY_E2E");}catch(Exception expected){}check(!routed,"bypass".equals(mode)?"excluded UID does not travel through running proxy":"reserved-IP request no longer travels through proxy after stop");check(get("10.0.2.2",19089,false).contains("BICHEN_HEALTH"),"bypass".equals(mode)?"excluded UID reaches direct local server while VPN is active":"normal local networking restored after stop");}
  else{
   check(get("198.51.100.7",18080,true).contains("BICHEN_PROXY_E2E"),"TCP from independent UID traverses Android TUN and imported SOCKS proxy");
   check(get("allowed.bichen.test",18080,true).contains("BICHEN_PROXY_E2E"),"system DNS and hostname request traverse Mihomo path");
   boolean blocked=false;try{String reply=get("ads.bichen.test",18080,false);blocked=!reply.contains("BICHEN_PROXY_E2E");}catch(IOException expected){blocked=true;}
   check(blocked,"ad domain rejected while ordinary proxy traffic succeeds");
  }
  b.putString("stream",log+"BICHEN_MIHOMO_PROBE_PASS "+mode+"\n");finish(Activity.RESULT_OK,b);
 }catch(Throwable e){b.putString("stream",log+"BICHEN_MIHOMO_PROBE_FAIL\n"+android.util.Log.getStackTraceString(e));finish(Activity.RESULT_CANCELED,b);}}
}
