#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

def read(rel):
    return (ROOT / rel).read_text(encoding='utf-8')

def write(rel, text):
    path = ROOT / rel
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding='utf-8')

def replace_once(rel, old, new):
    text = read(rel)
    if old not in text:
        raise SystemExit(f'patch anchor missing in {rel}: {old[:120]!r}')
    write(rel, text.replace(old, new, 1))

# test.30 version (P2 patch first turns 27 into 28).
replace_once('android-app/app/build.gradle.kts', 'versionCode = 428\n        versionName = "0.4.0-test.28"', 'versionCode = 430\n        versionName = "0.4.0-test.30"')

# Latest Compose UI: remove fake unavailable dialogs for features that already have a real Root backend.
ui = 'android-app/app/src/main/java/io/github/xgl34222220/bichen/ReferenceProxyActivity.kt'
text = read(ui)
text = text.replace('    var unavailable by remember { mutableStateOf<String?>(null) }\n', '')
replacements = {
    'RefToolRow(Icons.Rounded.Wifi, Color(0xFF0EA5E9), "网络匹配", "按 Wi‑Fi / SSID 自动匹配") { unavailable = "网络匹配目前只有规格，没有真实 SSID 自动切换后端。我已取消错误的基础代理跳转，接入完成前不会假装可用。" }':
    'RefToolRow(Icons.Rounded.Wifi, Color(0xFF0EA5E9), "网络匹配", "Wi‑Fi / SSID / 移动网络自动启停") { context.startActivity(Intent(context, ProxyNetworkMatchActivity::class.java)) }',
    'RefToolRow(Icons.Rounded.WifiTethering, Color(0xFF10B981), "共享网络", "热点与局域网共享") { unavailable = "共享网络开关目前没有独立后端控制。我已取消错误跳转，避免看起来能设置但实际无效。" }':
    'RefToolRow(Icons.Rounded.WifiTethering, Color(0xFF10B981), "共享网络", "热点与局域网共享 · Root 规则") { context.startActivity(Intent(context, RootTproxyActivity::class.java).putExtra("focus", "sharing")) }',
    'RefToolRow(Icons.Rounded.AltRoute, Color(0xFFEF4444), "绕过规则", "CIDR 与接口绕过") { unavailable = "自定义 CIDR/接口绕过还没有接入运行时规则生成器，因此不再把你带到基础代理页。" }':
    'RefToolRow(Icons.Rounded.AltRoute, Color(0xFFEF4444), "绕过规则", "CIDR 与接口绕过 · Root 规则") { context.startActivity(Intent(context, RootTproxyActivity::class.java).putExtra("focus", "bypass")) }',
    'RefToolRow(Icons.Rounded.Public, Color(0xFFF59E0B), "CNIP 设置", "国内 IP 数据与分流") { unavailable = "CNIP 下载源和运行时应用后端尚未接入；当前不再提供假入口。" }':
    'RefToolRow(Icons.Rounded.Public, Color(0xFFF59E0B), "CNIP 设置", "国内 IPv4/IPv6 自动直连") { context.startActivity(Intent(context, RootTproxyActivity::class.java).putExtra("focus", "cnip")) }',
}
for old,new in replacements.items():
    if old not in text: raise SystemExit('UI placeholder anchor missing: '+old[:80])
    text=text.replace(old,new,1)
start = text.find('    unavailable?.let { text ->\n')
if start >= 0:
    marker = '\n}\n\n@Composable\nprivate fun RefSettings'
    end = text.find(marker, start)
    if end < 0: raise SystemExit('failed to remove unavailable bottom sheet')
    text = text[:start] + marker[1:] + text[end + len(marker):]
needle='RefValueRow("当前配置", state.config, Icons.Rounded.Description, Color(0xFF8B5CF6)) { context.startActivity(Intent(context, ProxySubscriptionActivity::class.java)) }'
if needle not in text: raise SystemExit('settings anchor missing')
text=text.replace(needle, needle+'\n                RefDivider()\n                RefValueRow("高级代理配置", "应用范围 · DNS · QUIC · CNIP · 共享 · 绕过", Icons.Rounded.SettingsEthernet, Color(0xFF14B8A6)) { context.startActivity(Intent(context, RootTproxyActivity::class.java)) }',1)
write(ui,text)

# Root settings: real boot auto-start toggle.
root_ui='android-app/app/src/main/java/io/github/xgl34222220/bichen/RootTproxyActivity.java'
replace_once(root_ui,
 'private TextView coreValue,modeValue,ipv6Value,overwriteValue,appScopeValue,tcpValue,udpValue,dnsValue,quicValue,shareValue,killValue,cnIpValue,cidrValue,ifaceValue;',
 'private TextView coreValue,modeValue,ipv6Value,overwriteValue,appScopeValue,tcpValue,udpValue,dnsValue,quicValue,shareValue,killValue,bootValue,cnIpValue,cidrValue,ifaceValue;')
replace_once(root_ui,
 'killValue=settingRow(traffic,"Kill Switch","",()->toggle("proxyKillSwitch",false));',
 'killValue=settingRow(traffic,"Kill Switch","",()->toggle("proxyKillSwitch",false));u.separator(traffic);\n        bootValue=settingRow(traffic,"Root 开机自启","",()->toggle("proxyRootAutoStart",false));')
replace_once(root_ui,
 'killValue.setText(on(prefs.getBoolean("proxyKillSwitch",false)));',
 'killValue.setText(on(prefs.getBoolean("proxyKillSwitch",false)));bootValue.setText(on(prefs.getBoolean("proxyRootAutoStart",false)));')

# Root manager remembers explicit wanted state for boot restoration.
manager='android-app/app/src/main/java/io/github/xgl34222220/bichen/RootProxyManager.java'
replace_once(manager,
 'if(!warning.isEmpty())result.put("warning",warning);\n        return result;',
 'if(!warning.isEmpty())result.put("warning",warning);\n        prefs.edit().putBoolean("proxyRootWanted",true).apply();\n        return result;')
replace_once(manager,
 'stage(progress,"网络规则与临时 IPv6 状态已恢复");\n        return r;',
 'stage(progress,"网络规则与临时 IPv6 状态已恢复");\n        prefs.edit().putBoolean("proxyRootWanted",false).apply();\n        return r;')

# BootReceiver: Root transparent proxy restore does not depend on VpnService authorization.
boot='android-app/app/src/main/java/io/github/xgl34222220/bichen/BootReceiver.java'
text=read(boot)
anchor='        boolean autoStart = prefs.getBoolean("autoStartVpn", false);\n'
insert='''        if (prefs.getBoolean("proxyRootAutoStart", false) && prefs.getBoolean("proxyRootWanted", false)) {\n            final PendingResult pending = goAsync();\n            new Thread(() -> {\n                try {\n                    new RootProxyManager(context.getApplicationContext()).start(ProxyRuntimeProfile.load(prefs));\n                } catch (Exception e) {\n                    prefs.edit().putString("proxyRootBootError", "Root 代理开机恢复失败：" + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage())).apply();\n                } finally { pending.finish(); }\n            }, "bichen-root-boot").start();\n            return;\n        }\n\n'''
if anchor not in text: raise SystemExit('BootReceiver anchor missing')
text=text.replace(anchor,insert+anchor,1)
write(boot,text)

# Real network-matching activity.
write('android-app/app/src/main/java/io/github/xgl34222220/bichen/ProxyNetworkMatchActivity.java', r'''package io.github.xgl34222220.bichen;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.util.*;

public final class ProxyNetworkMatchActivity extends Activity {
    private SharedPreferences prefs; private ProxyUi u;
    private TextView enabledValue,statusValue,matchValue,unmatchValue,ssidValue,bssidValue,mobileValue;
    @Override public void onCreate(Bundle s){prefs=getSharedPreferences("bichen",MODE_PRIVATE);setTheme(ProxyUi.isDark(this)?R.style.AppThemeDark:R.style.AppTheme);super.onCreate(s);u=new ProxyUi(this);build();refresh();}
    private void build(){LinearLayout shell=u.col();u.window(shell);ScrollView scroll=new ScrollView(this);LinearLayout body=u.col();body.setPadding(u.dp(14),u.dp(18),u.dp(14),u.dp(34));scroll.addView(body);shell.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));LinearLayout top=u.row();top.setGravity(Gravity.CENTER_VERTICAL);top.addView(u.icon("back","返回",this::finish),new LinearLayout.LayoutParams(u.dp(46),u.dp(46)));TextView title=u.text("网络匹配",31,u.text,true);LinearLayout.LayoutParams tp=new LinearLayout.LayoutParams(0,-2,1);tp.leftMargin=u.dp(8);top.addView(title,tp);body.addView(top);u.gap(body,16);TextView tip=u.text("持续监听默认网络。可按 Wi‑Fi SSID/BSSID 或移动数据匹配，并自动启动/停止 Root 代理。",12,u.muted,false);tip.setPadding(u.dp(8),0,u.dp(8),u.dp(12));body.addView(tip);LinearLayout c=u.card(body);c.setPadding(u.dp(18),0,u.dp(18),0);enabledValue=row(c,"启用网络匹配",this::toggleEnabled);u.separator(c);statusValue=row(c,"当前环境",this::evaluate);u.separator(c);matchValue=row(c,"匹配成功动作",()->chooseAction("networkMatchAction",true));u.separator(c);unmatchValue=row(c,"失配动作",()->chooseAction("networkUnmatchAction",false));LinearLayout r=u.card(body);r.setPadding(u.dp(18),0,u.dp(18),0);ssidValue=row(r,"Wi‑Fi SSID",()->editSet("networkMatchSsids","Wi‑Fi SSID","每行一个 SSID；留空表示不限"));u.separator(r);bssidValue=row(r,"Wi‑Fi BSSID",()->editSet("networkMatchBssids","Wi‑Fi BSSID","每行一个 BSSID；留空表示不限"));u.separator(r);mobileValue=row(r,"移动数据匹配",()->{prefs.edit().putBoolean("networkMatchMobile",!prefs.getBoolean("networkMatchMobile",false)).apply();refresh();evaluate();});setContentView(shell);shell.requestApplyInsets();}
    private TextView row(LinearLayout p,String label,Runnable a){LinearLayout r=u.row();r.setMinimumHeight(u.dp(70));r.setGravity(Gravity.CENTER_VERTICAL);r.setBackground(u.touch(android.graphics.Color.TRANSPARENT,14));r.addView(u.text(label,16.5f,u.text,true),new LinearLayout.LayoutParams(0,-2,1));TextView v=u.text("",14,u.muted,false);v.setGravity(Gravity.RIGHT|Gravity.CENTER_VERTICAL);v.setMaxWidth(u.dp(210));v.setSingleLine(true);r.addView(v,new LinearLayout.LayoutParams(-2,-1));LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(u.dp(18),u.dp(18));cp.leftMargin=u.dp(7);r.addView(new IconView(this,"chevron",u.muted),cp);r.setOnClickListener(x->a.run());p.addView(r);return v;}
    private void toggleEnabled(){boolean n=!prefs.getBoolean("networkMatchEnabled",false);prefs.edit().putBoolean("networkMatchEnabled",n).apply();if(n){requestWifiPermission();startForegroundService(new Intent(this,ProxyNetworkMatchService.class).setAction("START"));}else stopService(new Intent(this,ProxyNetworkMatchService.class));refresh();}
    private void requestWifiPermission(){if(Build.VERSION.SDK_INT>=33){ArrayList<String> q=new ArrayList<>();if(checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES)!=PackageManager.PERMISSION_GRANTED)q.add(Manifest.permission.NEARBY_WIFI_DEVICES);if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED)q.add(Manifest.permission.ACCESS_FINE_LOCATION);if(!q.isEmpty())requestPermissions(q.toArray(new String[0]),908);}else if(Build.VERSION.SDK_INT>=23&&checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED)requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION},908);}
    private void chooseAction(String key,boolean match){String[] labels={"不操作","启动 Root 代理","停止 Root 代理"},values={"none","start","stop"};String cur=prefs.getString(key,match?"start":"none");int checked=Arrays.asList(values).indexOf(cur);new AlertDialog.Builder(this).setTitle(match?"匹配成功动作":"失配动作").setSingleChoiceItems(labels,Math.max(0,checked),(d,w)->{prefs.edit().putString(key,values[w]).apply();d.dismiss();refresh();evaluate();}).setNegativeButton("取消",null).show();}
    private Set<String> values(String key){Set<String>s=prefs.getStringSet(key,Collections.emptySet());return s==null?new TreeSet<>():new TreeSet<>(s);}
    private void editSet(String key,String title,String hint){EditText in=new EditText(this);in.setText(android.text.TextUtils.join("\n",values(key)));in.setHint(hint);in.setMinLines(5);FrameLayout wrap=new FrameLayout(this);int m=u.dp(20);wrap.setPadding(m,u.dp(4),m,0);wrap.addView(in,new FrameLayout.LayoutParams(-1,-2));new AlertDialog.Builder(this).setTitle(title).setMessage(hint).setView(wrap).setPositiveButton("保存",(d,w)->{TreeSet<String>out=new TreeSet<>();for(String x:in.getText().toString().split("[\\n,;]+")){String v=x.trim();if(!v.isEmpty())out.add(v);}prefs.edit().putStringSet(key,out).apply();refresh();evaluate();}).setNegativeButton("取消",null).show();}
    private String action(String key,String def){String v=prefs.getString(key,def);return "start".equals(v)?"启动代理":"stop".equals(v)?"停止代理":"不操作";}
    private void evaluate(){prefs.edit().putBoolean("networkMatchForceEval",true).apply();if(prefs.getBoolean("networkMatchEnabled",false))startForegroundService(new Intent(this,ProxyNetworkMatchService.class).setAction("EVAL"));new Handler(Looper.getMainLooper()).postDelayed(this::refresh,500);}
    private void refresh(){enabledValue.setText(prefs.getBoolean("networkMatchEnabled",false)?"开启":"关闭");statusValue.setText(prefs.getString("networkMatchLastEnvironment","点击刷新"));matchValue.setText(action("networkMatchAction","start"));unmatchValue.setText(action("networkUnmatchAction","none"));ssidValue.setText(values("networkMatchSsids").isEmpty()?"不限":values("networkMatchSsids").size()+" 个");bssidValue.setText(values("networkMatchBssids").isEmpty()?"不限":values("networkMatchBssids").size()+" 个");mobileValue.setText(prefs.getBoolean("networkMatchMobile",false)?"开启":"关闭");}
}
''')

write('android-app/app/src/main/java/io/github/xgl34222220/bichen/ProxyNetworkMatchService.java', r'''package io.github.xgl34222220.bichen;

import android.app.*;
import android.content.*;
import android.net.*;
import android.net.wifi.*;
import android.os.*;
import java.util.*;
import java.util.concurrent.*;

public final class ProxyNetworkMatchService extends Service {
    private static final String CHANNEL="bichen-network-match"; private SharedPreferences prefs; private ConnectivityManager cm; private ConnectivityManager.NetworkCallback cb; private final ExecutorService worker=Executors.newSingleThreadExecutor();
    @Override public void onCreate(){super.onCreate();prefs=getSharedPreferences("bichen",MODE_PRIVATE);cm=(ConnectivityManager)getSystemService(CONNECTIVITY_SERVICE);ensureChannel();startForeground(92,note("监听网络环境"));register();}
    @Override public int onStartCommand(Intent i,int f,int id){if(!prefs.getBoolean("networkMatchEnabled",false)){stopSelf();return START_NOT_STICKY;}evaluate();return START_STICKY;}
    @Override public void onDestroy(){if(cb!=null)try{cm.unregisterNetworkCallback(cb);}catch(Exception ignored){}worker.shutdownNow();super.onDestroy();}
    @Override public android.os.IBinder onBind(Intent i){return null;}
    private void register(){cb=new ConnectivityManager.NetworkCallback(){@Override public void onAvailable(Network n){evaluate();}@Override public void onLost(Network n){evaluate();}@Override public void onCapabilitiesChanged(Network n,NetworkCapabilities c){evaluate();}};try{cm.registerDefaultNetworkCallback(cb);}catch(Exception e){prefs.edit().putString("networkMatchLastEnvironment","监听失败："+e.getClass().getSimpleName()).apply();}}
    private void evaluate(){worker.execute(()->{try{Network n=cm.getActiveNetwork();NetworkCapabilities c=n==null?null:cm.getNetworkCapabilities(n);boolean wifi=c!=null&&c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI),mobile=c!=null&&c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR);String ssid="",bssid="";if(wifi)try{WifiManager wm=(WifiManager)getApplicationContext().getSystemService(WIFI_SERVICE);WifiInfo info=wm==null?null:wm.getConnectionInfo();if(info!=null){ssid=clean(info.getSSID());bssid=clean(info.getBSSID());}}catch(SecurityException ignored){}String env=wifi?("Wi‑Fi"+(ssid.isEmpty()?"":" · "+ssid)+(bssid.isEmpty()?"":" · "+bssid)):(mobile?"移动数据":"其他/离线");prefs.edit().putString("networkMatchLastEnvironment",env).apply();boolean matched=(wifi&&matches(ssid,set("networkMatchSsids"),false)&&matches(bssid,set("networkMatchBssids"),true))||(mobile&&prefs.getBoolean("networkMatchMobile",false));String action=prefs.getString(matched?"networkMatchAction":"networkUnmatchAction",matched?"start":"none");String sig=env+"|"+matched+"|"+action;String old=prefs.getString("networkMatchLastSig","");boolean force=prefs.getBoolean("networkMatchForceEval",false);if(sig.equals(old)&&!force)return;prefs.edit().putString("networkMatchLastSig",sig).putBoolean("networkMatchForceEval",false).apply();RootProxyManager root=new RootProxyManager(getApplicationContext());if("start".equals(action)){if(!root.status().optBoolean("running",false))root.start(ProxyRuntimeProfile.load(prefs));}else if("stop".equals(action)){if(root.status().optBoolean("running",false))root.stop();}((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).notify(92,note(env+(matched?" · 已匹配":" · 未匹配")));}catch(Exception e){prefs.edit().putString("networkMatchLastEnvironment","执行失败："+(e.getMessage()==null?e.getClass().getSimpleName():e.getMessage())).apply();}});}
    private Set<String> set(String key){Set<String>s=prefs.getStringSet(key,Collections.emptySet());return s==null?Collections.emptySet():new HashSet<>(s);}
    private boolean matches(String value,Set<String>s,boolean ignore){if(s.isEmpty())return true;for(String x:s)if(ignore?x.equalsIgnoreCase(value):x.equals(value))return true;return false;}
    private String clean(String s){if(s==null||"<unknown ssid>".equalsIgnoreCase(s))return"";if(s.length()>1&&s.startsWith("\"")&&s.endsWith("\""))return s.substring(1,s.length()-1);return s;}
    private void ensureChannel(){if(Build.VERSION.SDK_INT>=26)((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(new NotificationChannel(CHANNEL,"辟尘网络匹配",NotificationManager.IMPORTANCE_LOW));}
    private Notification note(String text){Notification.Builder b=Build.VERSION.SDK_INT>=26?new Notification.Builder(this,CHANNEL):new Notification.Builder(this);PendingIntent p=PendingIntent.getActivity(this,0,new Intent(this,ReferenceProxyActivity.class),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);return b.setSmallIcon(R.drawable.ic_bichen).setContentTitle("辟尘 · 网络匹配").setContentText(text).setContentIntent(p).setOngoing(true).build();}
}
''')

manifest='android-app/app/src/main/AndroidManifest.xml'; text=read(manifest)
if 'android.permission.ACCESS_WIFI_STATE' not in text:
    text=text.replace('    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />','    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />\n    <uses-permission android:name="android.permission.ACCESS_WIFI_STATE" />\n    <uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />\n    <uses-permission android:name="android.permission.NEARBY_WIFI_DEVICES" android:usesPermissionFlags="neverForLocation" />')
if '.ProxyNetworkMatchActivity' not in text:
    text=text.replace('        <activity android:name=".RootTproxyActivity" android:label="基础代理配置" android:exported="false" />','        <activity android:name=".RootTproxyActivity" android:label="基础代理配置" android:exported="false" />\n        <activity android:name=".ProxyNetworkMatchActivity" android:label="网络匹配" android:exported="false" />\n        <service android:name=".ProxyNetworkMatchService" android:exported="false" android:foregroundServiceType="specialUse">\n            <property android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE" android:value="User enabled network environment matching for Root proxy automation" />\n        </service>')
write(manifest,text)

print('test.30 document-feature wiring applied')
