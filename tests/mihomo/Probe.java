package hetu.proxyprobe;
import android.app.*;
import android.os.Bundle;
import java.net.*;
import java.io.*;
import java.util.*;
import javax.net.ssl.*;
/** Independent app UID, not excluded by Hetu's VPN. No public network target. */
public final class Probe extends Instrumentation {
 private String mode;private StringBuilder log=new StringBuilder();
 @Override public void onCreate(Bundle args){super.onCreate(args);mode=args.getString("mode","path");start();}
 private String get(String host,int port,boolean hold)throws Exception{return get(host,port,hold,4000);}
 private String get(String host,int port,boolean hold,int timeout)throws Exception{
  try(Socket s=new Socket()){s.connect(new InetSocketAddress(host,port),timeout);s.setSoTimeout(timeout);s.getOutputStream().write(("GET /hetu-e2e HTTP/1.0\r\nHost: "+host+"\r\n\r\n").getBytes("US-ASCII"));byte[] bytes=new byte[2048];int n=s.getInputStream().read(bytes);String value=n<0?"":new String(bytes,0,n,"US-ASCII");if(hold)Thread.sleep(2200);return value;}
 }
 private boolean blocked(String host,int port)throws Exception{try{return !get(host,port,false).contains("HETU_PROXY_E2E");}catch(IOException expected){return true;}}
 private boolean blocked(String host)throws Exception{return blocked(host,18080);}
 private void pureIpTlsSniAttempt()throws Exception{
  try{
   SSLSocketFactory factory=(SSLSocketFactory)SSLSocketFactory.getDefault();
   try(SSLSocket socket=(SSLSocket)factory.createSocket()){
    socket.connect(new InetSocketAddress("198.51.100.7",443),4000);socket.setSoTimeout(4000);
    SSLParameters params=socket.getSSLParameters();params.setServerNames(Collections.singletonList(new SNIHostName("doh.360.cn")));socket.setSSLParameters(params);
    try{socket.startHandshake();}catch(IOException expected){/* REJECT or the non-TLS fixture both end the probe; host-side evidence distinguishes them. */}
   }
  }catch(IOException expected){/* A guard rejection may fail before the TLS handshake begins. */}
  check(true,"pure-IP TLS ClientHello with DoH SNI completed for host-side leak verification");
 }
 private boolean waitForDirectNetwork()throws InterruptedException{
  long deadline=android.os.SystemClock.elapsedRealtime()+6000;
  do{
   try{if(get("10.0.2.2",19089,false,900).contains("HETU_HEALTH"))return true;}catch(Exception transition){/* Android may briefly keep the torn-down VPN route while switching defaults. */}
   Thread.sleep(150);
  }while(android.os.SystemClock.elapsedRealtime()<deadline);
  return false;
 }
 private void check(boolean pass,String what){if(!pass)throw new AssertionError(what);log.append("PASS ").append(what).append('\n');}
 @Override public void onStart(){Bundle b=new Bundle();try{
  if("stopped".equals(mode)||"bypass".equals(mode)){boolean routed=false;try{routed=get("198.51.100.7",18080,false).contains("HETU_PROXY_E2E");}catch(Exception expected){}check(!routed,"bypass".equals(mode)?"excluded UID does not travel through running proxy":"reserved-IP request no longer travels through proxy after stop");if("stopped".equals(mode))check(waitForDirectNetwork(),"normal local networking restored after stop");else check(get("10.0.2.2",19089,false).contains("HETU_HEALTH"),"excluded UID reaches direct local server while VPN is active");}
  else{
   check(get("198.51.100.7",18080,true).contains("HETU_PROXY_E2E"),"TCP from independent UID traverses Android TUN and imported SOCKS proxy");
   check(get("allowed.hetu.test",18080,true).contains("HETU_PROXY_E2E"),"system DNS and hostname request traverse Mihomo path");
   check(blocked("ads.hetu.test"),"exact ad domain rejected while ordinary proxy traffic succeeds");
   check(blocked("child.0.0-02.net"),"HaGeZi parent domain blocks an unseen child through DOMAIN-SUFFIX");
   check(get("safe.0.0-02.net",18080,true).contains("HETU_PROXY_E2E"),"exact whitelist PASS bypasses suffix rejection and preserves original proxy route");
   check(blocked("doh.360.cn"),"encrypted DNS resolver domain is rejected by optional anti-bypass guard");
   check(blocked("allowed.hetu.test",853),"TCP 853 is rejected by optional DoT/DoQ anti-bypass guard");
   pureIpTlsSniAttempt();
  }
  b.putString("stream",log+"HETU_MIHOMO_PROBE_PASS "+mode+"\n");finish(Activity.RESULT_OK,b);
 }catch(Throwable e){b.putString("stream",log+"HETU_MIHOMO_PROBE_FAIL\n"+android.util.Log.getStackTraceString(e));finish(Activity.RESULT_CANCELED,b);}}
}
