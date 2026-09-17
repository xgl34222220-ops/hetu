from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
BUILD = ROOT / "android-app/app/build.gradle.kts"
STARTUP = ROOT / "android-app/app/src/main/java/io/github/xgl34222220/bichen/MihomoStartupConfig.java"
ROOTM = ROOT / "android-app/app/src/main/java/io/github/xgl34222220/bichen/RootProxyManager.java"
SERVICE = ROOT / "android-app/app/src/main/java/io/github/xgl34222220/bichen/ProxyNetworkMatchService.java"
SCRIPT = ROOT / "android-app/app/src/main/assets/proxy-root-v3.sh"
MANIFEST = ROOT / "android-app/app/src/main/AndroidManifest.xml"

def replace_once(text, old, new, label):
    if old not in text:
        raise SystemExit(f"missing patch anchor: {label}")
    return text.replace(old, new, 1)

# version
text = BUILD.read_text()
text = replace_once(text, 'versionCode = 453', 'versionCode = 454', 'versionCode')
text = replace_once(text, 'versionName = "0.4.0-test.53"', 'versionName = "0.4.0-test.54"', 'versionName')
BUILD.write_text(text)

# Respect the user's routing policy: Bichen adblock becomes a supplemental rule right
# before a terminal MATCH/FINAL instead of overriding every explicit DIRECT rule.
text = STARTUP.read_text()
text = replace_once(text, 'yaml=prependAdblockRule(yaml);', 'yaml=insertAdblockRuleRespectingUserPolicy(yaml);', 'adblock call')
pattern = re.compile(r'''    private static String prependAdblockRule\(String source\)throws IOException\{.*?\n    \}\n\n    /\*\* Merge Bichen CN IP''', re.S)
replacement = r'''    private static String insertAdblockRuleRespectingUserPolicy(String source)throws IOException{
        String[] lines=normalize(source).split("\\n",-1);
        int index=-1;Matcher found=null;Pattern top=Pattern.compile("^rules\\s*:(.*)$");
        for(int i=0;i<lines.length;i++){if(indent(lines[i])!=0)continue;Matcher m=top.matcher(lines[i]);if(m.find()){index=i;found=m;break;}}
        String rule="  - RULE-SET,"+ProxyAdblockRules.PROVIDER_NAME+",REJECT\\n";
        if(index<0){String base=trimOne(source);return base+(base.isEmpty()?"":"\\n")+"rules:\\n"+rule;}
        String rest=found.group(1).trim();
        if(rest.startsWith("#"))rest="";
        if(!rest.isEmpty()&&!rest.equals("[]"))throw new IOException("代理串联去广告需要普通 rules: 列表；当前源配置使用行内 rules 写法");
        int end=lines.length;
        for(int i=index+1;i<lines.length;i++){
            String t=lines[i].trim();
            if(t.isEmpty()||t.startsWith("#"))continue;
            if(indent(lines[i])==0){end=i;break;}
        }
        int insert=end;
        for(int i=index+1;i<end;i++){
            String t=lines[i].trim();
            if(t.startsWith("- MATCH,")||t.equals("- MATCH")||t.startsWith("- FINAL,")||t.equals("- FINAL")){
                insert=i;break;
            }
        }
        StringBuilder out=new StringBuilder();
        for(int i=0;i<lines.length;i++){
            if(i==insert)out.append(rule);
            out.append(lines[i]).append('\\n');
        }
        if(insert==lines.length)out.append(rule);
        return trimOne(out.toString());
    }

    /** Merge Bichen CN IP'''
text, count = pattern.subn(replacement, text, count=1)
if count != 1:
    raise SystemExit('failed replacing adblock policy method')
STARTUP.write_text(text)

# Do not send Android system UIDs through ordinary transparent proxy chains. DNS chains
# intentionally stay untouched so plaintext DNS is still captured.
text = SCRIPT.read_text()
anchor = 'blacklist_returns(){ BIN="$1"; T="$2"; C="$3"; S="$4"; LIST="$5"; [ "$S" = blacklist ] || return 0; [ -z "$LIST" ] && return 0; OLDIFS=$IFS; IFS=,; set -- $LIST; IFS=$OLDIFS; for U in "$@"; do "$BIN" -t "$T" -A "$C" -m owner --uid-owner "$U" -j RETURN || return 1; done; }\n'
helper = anchor + 'system_uid_return(){ BIN="$1"; T="$2"; C="$3"; S="$4"; [ "$S" = whitelist ] && return 0; "$BIN" -t "$T" -A "$C" -m owner --uid-owner 0-9999 -j RETURN || return 1; }\n'
text = replace_once(text, anchor, helper, 'system uid helper')
text = replace_once(text, '  xt4 -t mangle -A "$MOUT" -m owner --uid-owner 0 -j RETURN || return 1\n', '  system_uid_return xt4 mangle "$MOUT" "$S" || return 1\n', 'mangle4 system uid')
text = replace_once(text, '  xt6 -t mangle -A "$MOUT" -m owner --uid-owner 0 -j RETURN || return 1\n', '  system_uid_return xt6 mangle "$MOUT" "$S" || return 1\n', 'mangle6 system uid')
text = replace_once(text, 'xt4 -t nat -A "$NOUT" -m owner --uid-owner 0 -j RETURN || return 1; iface_out xt4 nat "$NOUT"', 'system_uid_return xt4 nat "$NOUT" "$S" || return 1; iface_out xt4 nat "$NOUT"', 'redirect4 system uid')
text = replace_once(text, 'xt6 -t nat -A "$NOUT" -m owner --uid-owner 0 -j RETURN || return 1; iface_out xt6 nat "$NOUT"', 'system_uid_return xt6 nat "$NOUT" "$S" || return 1; iface_out xt6 nat "$NOUT"', 'redirect6 system uid')
text = replace_once(text, 'xt4 -t filter -A "$QUICOUT" -m owner --uid-owner 0 -j RETURN || return 1; iface_out xt4 filter "$QUICOUT"', 'system_uid_return xt4 filter "$QUICOUT" "$S" || return 1; iface_out xt4 filter "$QUICOUT"', 'quic4 system uid')
text = replace_once(text, 'xt6 -t filter -A "$QUICOUT" -m owner --uid-owner 0 -j RETURN || return 1; iface_out xt6 filter "$QUICOUT"', 'system_uid_return xt6 filter "$QUICOUT" "$S" || return 1; iface_out xt6 filter "$QUICOUT"', 'quic6 system uid')
preflight_anchor = '  if [ "$SCOPE" != core ] && [ -n "$UIDS" ]; then U=$(first_uid "$UIDS"); probeowner "$U" || fail "当前 iptables 不支持 owner UID 匹配"; fi\n'
preflight_new = preflight_anchor + '  [ "$SCOPE" = whitelist ] || probeowner "0-9999" || fail "当前 iptables 不支持系统 UID 范围绕过"\n'
text = replace_once(text, preflight_anchor, preflight_new, 'preflight system uid range')
SCRIPT.write_text(text)

# A lightweight always-on continuity guard while Root proxy is requested. On an actual
# default-network switch, close old Mihomo sessions once so long-lived IM sockets reconnect
# immediately on the new path. Optional network matching automation remains opt-in.
SERVICE.write_text(r'''package io.github.xgl34222220.bichen;

import android.app.*;
import android.content.*;
import android.net.*;
import android.net.wifi.*;
import android.os.*;
import java.util.*;
import java.util.concurrent.*;

public final class ProxyNetworkMatchService extends Service {
    private static final String CHANNEL="bichen-network-match";
    private SharedPreferences prefs;
    private ConnectivityManager cm;
    private ConnectivityManager.NetworkCallback cb;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private Network lastDefaultNetwork;
    private boolean defaultNetworkSeen;

    @Override public void onCreate(){
        super.onCreate();
        prefs=getSharedPreferences("bichen",MODE_PRIVATE);
        cm=(ConnectivityManager)getSystemService(CONNECTIVITY_SERVICE);
        ensureChannel();
        startForeground(92,note("代理网络守护已就绪"));
        register();
    }

    @Override public int onStartCommand(Intent i,int f,int id){
        if(!prefs.getBoolean("networkMatchEnabled",false)&&!prefs.getBoolean("proxyRootWanted",false)){
            stopSelf();return START_NOT_STICKY;
        }
        evaluate();
        return START_STICKY;
    }

    @Override public void onDestroy(){
        if(cb!=null)try{cm.unregisterNetworkCallback(cb);}catch(Exception ignored){}
        worker.shutdownNow();
        super.onDestroy();
    }
    @Override public android.os.IBinder onBind(Intent i){return null;}

    private void register(){
        cb=new ConnectivityManager.NetworkCallback(){
            @Override public void onAvailable(Network n){handleDefaultNetwork(n);evaluate();}
            @Override public void onLost(Network n){evaluate();}
            @Override public void onCapabilitiesChanged(Network n,NetworkCapabilities c){evaluate();}
        };
        try{cm.registerDefaultNetworkCallback(cb);}
        catch(Exception e){prefs.edit().putString("networkMatchLastEnvironment","监听失败："+e.getClass().getSimpleName()).apply();}
    }

    private void handleDefaultNetwork(Network n){
        worker.execute(()->{
            boolean changed=defaultNetworkSeen&&lastDefaultNetwork!=null&&n!=null&&!lastDefaultNetwork.equals(n);
            lastDefaultNetwork=n;
            defaultNetworkSeen=true;
            if(!changed||!prefs.getBoolean("proxyRootWanted",false))return;
            SystemClock.sleep(220);
            try{
                new MihomoControllerClient(getApplicationContext()).closeAll();
                prefs.edit().putLong("proxyLastNetworkSessionReset",System.currentTimeMillis()).remove("proxyNetworkSessionResetError").apply();
            }catch(Exception e){
                prefs.edit().putString("proxyNetworkSessionResetError",e.getMessage()==null?e.getClass().getSimpleName():e.getMessage()).apply();
            }
        });
    }

    private void evaluate(){
        worker.execute(()->{
            try{
                boolean automation=prefs.getBoolean("networkMatchEnabled",false);
                Network n=cm.getActiveNetwork();
                NetworkCapabilities c=n==null?null:cm.getNetworkCapabilities(n);
                boolean wifi=c!=null&&c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
                boolean mobile=c!=null&&c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR);
                String ssid="",bssid="";
                if(wifi)try{
                    WifiManager wm=(WifiManager)getApplicationContext().getSystemService(WIFI_SERVICE);
                    WifiInfo info=wm==null?null:wm.getConnectionInfo();
                    if(info!=null){ssid=clean(info.getSSID());bssid=clean(info.getBSSID());}
                }catch(SecurityException ignored){}
                String env=wifi?("Wi‑Fi"+(ssid.isEmpty()?"":" · "+ssid)+(bssid.isEmpty()?"":" · "+bssid)):(mobile?"移动数据":"其他/离线");
                prefs.edit().putString("networkMatchLastEnvironment",env).apply();
                if(automation){
                    boolean matched=(wifi&&matches(ssid,set("networkMatchSsids"),false)&&matches(bssid,set("networkMatchBssids"),true))||(mobile&&prefs.getBoolean("networkMatchMobile",false));
                    String action=prefs.getString(matched?"networkMatchAction":"networkUnmatchAction",matched?"start":"none");
                    String sig=env+"|"+matched+"|"+action;
                    String old=prefs.getString("networkMatchLastSig","");
                    boolean force=prefs.getBoolean("networkMatchForceEval",false);
                    if(!sig.equals(old)||force){
                        prefs.edit().putString("networkMatchLastSig",sig).putBoolean("networkMatchForceEval",false).apply();
                        RootProxyManager root=new RootProxyManager(getApplicationContext());
                        boolean running=root.status().optBoolean("running",false);
                        String owner=prefs.getString("proxyRootSessionOwner","");
                        if("start".equals(action)){
                            if(!running&&!"manual".equals(owner)){
                                root.start(ProxyRuntimeProfile.load(prefs));
                                prefs.edit().putString("proxyRootSessionOwner","automation").apply();
                            }
                        }else if("stop".equals(action)){
                            if(running&&"automation".equals(owner)){
                                root.stop();prefs.edit().remove("proxyRootSessionOwner").apply();
                            }
                        }
                    }
                    ((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).notify(92,note(env+(matched?" · 已匹配":" · 未匹配")));
                }else{
                    ((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).notify(92,note(env+" · 长连接守护"));
                }
            }catch(Exception e){
                prefs.edit().putString("networkMatchLastEnvironment","执行失败："+(e.getMessage()==null?e.getClass().getSimpleName():e.getMessage())).apply();
            }
        });
    }

    private Set<String> set(String key){Set<String>s=prefs.getStringSet(key,Collections.emptySet());return s==null?Collections.emptySet():new HashSet<>(s);}
    private boolean matches(String value,Set<String>s,boolean ignore){if(s.isEmpty())return true;for(String x:s)if(ignore?x.equalsIgnoreCase(value):x.equals(value))return true;return false;}
    private String clean(String s){if(s==null||"<unknown ssid>".equalsIgnoreCase(s))return"";if(s.length()>1&&s.startsWith("\"")&&s.endsWith("\""))return s.substring(1,s.length()-1);return s;}
    private void ensureChannel(){if(Build.VERSION.SDK_INT>=26)((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(new NotificationChannel(CHANNEL,"辟尘代理网络守护",NotificationManager.IMPORTANCE_LOW));}
    private Notification note(String text){Notification.Builder b=Build.VERSION.SDK_INT>=26?new Notification.Builder(this,CHANNEL):new Notification.Builder(this);PendingIntent p=PendingIntent.getActivity(this,0,new Intent(this,ReferenceProxyActivity.class),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);return b.setSmallIcon(R.drawable.ic_bichen).setContentTitle("辟尘 · 代理守护").setContentText(text).setContentIntent(p).setOngoing(true).build();}
}
''')

# Start/stop the continuity service with the Root runtime.
text = ROOTM.read_text()
text = replace_once(text, 'import android.content.Context;\nimport android.content.SharedPreferences;', 'import android.content.Context;\nimport android.content.Intent;\nimport android.content.SharedPreferences;', 'Intent import')
old = '''            if(existing.optBoolean("running",false)){
                prefs.edit().putBoolean("proxyRootWanted",true).apply();
                existing.put("ok",true).put("alreadyRunning",true).put("message","Root 代理已在运行，已忽略重复启动请求");
                return existing;
            }'''
new = '''            if(existing.optBoolean("running",false)){
                prefs.edit().putBoolean("proxyRootWanted",true).apply();
                ensureContinuityService(true);
                existing.put("ok",true).put("alreadyRunning",true).put("message","Root 代理已在运行，已忽略重复启动请求");
                return existing;
            }'''
text = replace_once(text, old, new, 'already-running continuity')
text = replace_once(text, '        prefs.edit().putBoolean("proxyRootWanted",true).apply();\n        return result;', '        prefs.edit().putBoolean("proxyRootWanted",true).apply();\n        ensureContinuityService(true);\n        return result;', 'start continuity')
text = replace_once(text, '            prefs.edit().putBoolean("proxyRootWanted",false).apply();\n            return r;', '            prefs.edit().putBoolean("proxyRootWanted",false).apply();\n            ensureContinuityService(false);\n            return r;', 'stop continuity')
marker = '    String diagnostics(){\n'
method = '''    private void ensureContinuityService(boolean running){
        try{
            Intent intent=new Intent(context,ProxyNetworkMatchService.class);
            if(running){
                if(Build.VERSION.SDK_INT>=26)context.startForegroundService(intent);else context.startService(intent);
            }else if(!prefs.getBoolean("networkMatchEnabled",false)){
                context.stopService(intent);
            }
        }catch(Exception ignored){}
    }

'''
text = replace_once(text, marker, method + marker, 'continuity method')
ROOTM.write_text(text)

text = MANIFEST.read_text()
text = replace_once(text,
    'android:value="User enabled network environment matching for Root proxy automation"',
    'android:value="Root proxy default-network continuity and optional environment matching"',
    'FGS subtype')
MANIFEST.write_text(text)

print('Applied Bichen 0.4.0-test.54 WeChat/connection lifecycle fix')
