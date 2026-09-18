package io.github.xgl34222220.hetu;
import org.json.JSONObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
/** Private JNI boundary; no external controller port. */
public final class MihomoNative {
 private static boolean loaded;
 private static synchronized void load()throws IOException{if(loaded)return;try{System.loadLibrary("hetu_core");loaded=true;}catch(UnsatisfiedLinkError e){throw new IOException("本安装包缺少适配当前 CPU 的 Mihomo 内核",e);}}
 private static native byte[] invoke(byte[] request);
 public static JSONObject call(JSONObject request)throws Exception{
  RootBridge.requireWorkerThread();load();byte[] response=invoke(request.toString().getBytes(StandardCharsets.UTF_8));if(response==null)throw new IOException("内核没有返回完整结果");
  JSONObject value=new JSONObject(new String(response,StandardCharsets.UTF_8));if(!value.optBoolean("ok"))throw new IOException(value.optString("error","内核操作失败"));return value;
 }
 public static JSONObject call(String action)throws Exception{return call(new JSONObject().put("action",action));}
}
