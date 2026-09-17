package io.github.xgl34222220.bichen;

import android.app.*;
import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import android.os.*;
import android.provider.OpenableColumns;
import android.text.*;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

/** Basic proxy configuration backed by the same immutable policy used by RootProxyManager. */
public final class RootTproxyActivity extends Activity {
    private static final int PICK_CONFIG=701;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final Handler ui=new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private ProxyUi u;
    private ProxyCoreStore cores;
    private ProxyConfigLibrary configs;
    private RootProxyManager root;
    private TextView coreValue,modeValue,ipv6Value,overwriteValue,appScopeValue,tcpValue,udpValue,dnsValue,quicValue,shareValue,killValue,cnIpValue,cidrValue,ifaceValue;
    private LinearLayout configRows;
    private boolean destroyed,busy;

    private interface Work{String run()throws Exception;}

    @Override public void onCreate(Bundle state){
        prefs=getSharedPreferences("bichen",MODE_PRIVATE);
        setTheme(ProxyUi.isDark(this)?R.style.AppThemeDark:R.style.AppTheme);
        super.onCreate(state);
        u=new ProxyUi(this);cores=new ProxyCoreStore(this);configs=new ProxyConfigLibrary(this);root=new RootProxyManager(this);
        build();refresh();
    }

    private void build(){
        LinearLayout shell=u.col();u.window(shell);
        ScrollView scroll=new ScrollView(this);scroll.setVerticalScrollBarEnabled(false);scroll.setClipToPadding(false);
        LinearLayout body=u.col();body.setPadding(u.dp(14),u.dp(18),u.dp(14),u.dp(34));scroll.addView(body);shell.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));

        LinearLayout top=u.row();top.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(u.icon("back","返回",this::finish),new LinearLayout.LayoutParams(u.dp(46),u.dp(46)));
        TextView title=u.text("基础代理配置",31,u.text,true);LinearLayout.LayoutParams tp=new LinearLayout.LayoutParams(0,-2,1);tp.leftMargin=u.dp(8);top.addView(title,tp);body.addView(top);u.gap(body,18);

        TextView tip=u.text("所有修改都会在下一次启动/重启代理时作为一个事务应用；失败会回滚，不直接改源订阅。",12,u.muted,false);tip.setPadding(u.dp(8),0,u.dp(8),u.dp(12));body.addView(tip);

        LinearLayout basic=u.card(body);basic.setPadding(u.dp(18),0,u.dp(18),0);
        coreValue=settingRow(basic,"核心选择","",this::chooseCore);u.separator(basic);
        modeValue=settingRow(basic,"运行模式","",this::chooseMode);u.separator(basic);
        ipv6Value=settingRow(basic,"IPv6","",this::chooseIpv6);u.separator(basic);
        overwriteValue=settingRow(basic,"自动覆写","",this::chooseOverwrite);

        LinearLayout traffic=u.card(body);traffic.setPadding(u.dp(18),0,u.dp(18),0);
        appScopeValue=settingRow(traffic,"应用范围","",this::chooseAppScope);u.separator(traffic);
        plainAction(traffic,"应用名单（主界面 → 应用）",()->toast("当前名单与主界面的应用选择同步；黑名单=所选直连，白名单=仅所选代理"));u.separator(traffic);
        tcpValue=settingRow(traffic,"TCP 接管","",()->toggle("proxyTcp",true));u.separator(traffic);
        udpValue=settingRow(traffic,"UDP 接管","",()->toggle("proxyUdp",true));u.separator(traffic);
        dnsValue=settingRow(traffic,"DNS 劫持","",this::chooseDns);u.separator(traffic);
        quicValue=settingRow(traffic,"拦截 QUIC","",()->toggle("proxyQuicBlocked",false));u.separator(traffic);
        shareValue=settingRow(traffic,"共享网络","",()->toggle("proxySharedNetwork",false));u.separator(traffic);
        killValue=settingRow(traffic,"Kill Switch","",()->toggle("proxyKillSwitch",false));

        LinearLayout bypass=u.card(body);bypass.setPadding(u.dp(18),0,u.dp(18),0);
        cnIpValue=settingRow(bypass,"中国 IP 自动直连","",()->toggle("proxyCnIpDirect",false));u.separator(bypass);
        cidrValue=settingRow(bypass,"CIDR 绕过","",()->editSet("proxyBypassCidrs","CIDR 绕过","每行一个 IPv4/IPv6 CIDR。这里保留少量 Root 层自定义绕过；大规模中国 IP 段请使用上面的自动直连规则。"));u.separator(bypass);
        ifaceValue=settingRow(bypass,"接口绕过","",()->editSet("proxyBypassInterfaces","接口绕过","每行一个接口名，例如 wlan0、rmnet_data0、tun+。不能填写 lo。"));

        LinearLayout startup=u.card(body);startup.setPadding(u.dp(18),0,u.dp(18),0);
        plainAction(startup,"查看启动配置",this::showStartupConfig);u.separator(startup);
        plainAction(startup,"运行预检",this::runPreflight);u.separator(startup);
        plainAction(startup,"查看 Root 诊断",this::showDiagnostics);u.separator(startup);
        plainAction(startup,"清理残留并恢复网络",this::confirmRepairNetwork);

        LinearLayout cfg=u.card(body);cfg.setPadding(u.dp(18),u.dp(4),u.dp(18),u.dp(6));
        LinearLayout head=u.row();head.setMinimumHeight(u.dp(66));
        head.addView(u.text("配置选择",18,u.text,true),new LinearLayout.LayoutParams(0,-2,1));
        TextView plus=u.text("＋",32,u.text,false);plus.setGravity(Gravity.CENTER);plus.setContentDescription("导入新配置");plus.setBackground(u.touch(u.soft,24));plus.setOnClickListener(v->importConfig());head.addView(plus,new LinearLayout.LayoutParams(u.dp(50),u.dp(50)));cfg.addView(head);u.separator(cfg);
        configRows=u.col();cfg.addView(configRows,new LinearLayout.LayoutParams(-1,-2));

        setContentView(shell);shell.requestApplyInsets();
    }

    private TextView settingRow(LinearLayout parent,String label,String value,Runnable action){
        LinearLayout row=u.row();row.setMinimumHeight(u.dp(70));row.setPadding(0,u.dp(5),0,u.dp(5));row.setBackground(u.touch(android.graphics.Color.TRANSPARENT,14));
        row.addView(u.text(label,16.5f,u.text,true),new LinearLayout.LayoutParams(0,-2,1));
        TextView right=u.text(value,15,u.muted,false);right.setGravity(Gravity.RIGHT|Gravity.CENTER_VERTICAL);right.setMaxWidth(u.dp(210));right.setEllipsize(TextUtils.TruncateAt.END);right.setSingleLine(true);row.addView(right,new LinearLayout.LayoutParams(-2,-1));
        LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(u.dp(18),u.dp(18));cp.leftMargin=u.dp(7);row.addView(new IconView(this,"chevron",u.muted),cp);row.setOnClickListener(v->action.run());parent.addView(row,new LinearLayout.LayoutParams(-1,-2));return right;
    }

    private void plainAction(LinearLayout parent,String title,Runnable action){
        LinearLayout row=u.row();row.setMinimumHeight(u.dp(68));row.setPadding(0,u.dp(6),0,u.dp(6));row.setBackground(u.touch(android.graphics.Color.TRANSPARENT,14));
        row.addView(u.text(title,16,u.text,true),new LinearLayout.LayoutParams(0,-2,1));row.addView(new IconView(this,"chevron",u.muted),new LinearLayout.LayoutParams(u.dp(18),u.dp(18)));row.setOnClickListener(v->action.run());parent.addView(row,new LinearLayout.LayoutParams(-1,-2));
    }

    private ProxyRuntimeProfile profile(){return ProxyRuntimeProfile.load(prefs);}
    private String ipv6Label(ProxyRuntimeProfile.Ipv6 v){switch(v){case BYPASS:return"IPv6 不进核心";case STRICT:return"严格 IPv4 防泄漏";case DISABLE:return"禁用系统 IPv6";default:return"启用 IPv6";}}
    private String scopeLabel(ProxyRuntimeProfile.AppScope v){switch(v){case WHITELIST:return"仅所选应用代理";case BLACKLIST:return"所选应用直连";default:return"核心配置";}}
    private String dnsLabel(ProxyRuntimeProfile.DnsHijack v){switch(v){case OFF:return"关闭";case REDIRECT:return"Redirect 1053";default:return"TPROXY";}}
    private String on(boolean value){return value?"开启":"关闭";}

    private void chooseCore(){
        ProxyRuntimeProfile.Core[] values=ProxyRuntimeProfile.Core.values();String[] labels=new String[values.length];int selected=0;
        for(int i=0;i<values.length;i++){labels[i]=values[i].label;if(values[i]==profile().core)selected=i;}
        new AlertDialog.Builder(this).setTitle("核心选择").setSingleChoiceItems(labels,selected,(d,w)->{
            ProxyRuntimeProfile.Core chosen=values[w];prefs.edit().putString("proxyBaseCore",chosen.id).apply();d.dismiss();refresh();changed();
            if(chosen!=ProxyRuntimeProfile.Core.MIHOMO&&!cores.installed(chosen))toast(chosen.label+" 尚未安装核心");
        }).setNegativeButton("取消",null).show();
    }

    private void chooseMode(){
        ProxyRuntimeProfile.Core core=profile().core;ProxyRuntimeProfile.Mode[] values=ProxyRuntimeProfile.Mode.values();String[] labels=new String[values.length];int selected=0;
        for(int i=0;i<values.length;i++){ProxyRuntimeProfile.Capability c=ProxyRuntimeProfile.capability(core,values[i]);labels[i]=values[i].label+(c.available?"":"  ·  不可用");if(values[i]==profile().mode)selected=i;}
        new AlertDialog.Builder(this).setTitle("运行模式").setSingleChoiceItems(labels,selected,(d,w)->{
            ProxyRuntimeProfile.Capability c=ProxyRuntimeProfile.capability(core,values[w]);d.dismiss();if(!c.available){toast(c.reason);return;}prefs.edit().putString("proxyBaseMode",values[w].id).apply();refresh();changed();
        }).setNegativeButton("取消",null).show();
    }

    private void chooseIpv6(){
        ProxyRuntimeProfile.Ipv6[] values=ProxyRuntimeProfile.Ipv6.values();
        String[] labels={"启用 IPv6","IPv6 不进核心","严格 IPv4 防泄漏","禁用系统 IPv6"};
        int selected=profile().ipv6.ordinal();
        new AlertDialog.Builder(this).setTitle("IPv6").setSingleChoiceItems(labels,selected,(d,w)->{
            prefs.edit().putString("proxyBaseIpv6",values[w].id).apply();d.dismiss();refresh();changed();
        }).setNegativeButton("取消",null).show();
    }

    private void chooseAppScope(){
        ProxyRuntimeProfile.AppScope[] values={ProxyRuntimeProfile.AppScope.CORE,ProxyRuntimeProfile.AppScope.BLACKLIST,ProxyRuntimeProfile.AppScope.WHITELIST};
        String[] labels={"核心配置（不按 App 过滤）","所选应用直连，其余代理","仅所选应用代理，其余直连"};
        ProxyRuntimeProfile.AppScope current=profile().appScope;int selected=0;for(int i=0;i<values.length;i++)if(values[i]==current)selected=i;
        new AlertDialog.Builder(this).setTitle("应用范围").setSingleChoiceItems(labels,selected,(d,w)->{
            prefs.edit().putString("proxyAppScope",values[w].id).apply();d.dismiss();refresh();changed();
        }).setNegativeButton("取消",null).show();
    }

    private void chooseDns(){
        ProxyRuntimeProfile.DnsHijack[] values={ProxyRuntimeProfile.DnsHijack.TPROXY,ProxyRuntimeProfile.DnsHijack.REDIRECT,ProxyRuntimeProfile.DnsHijack.OFF};
        String[] labels={"TPROXY（跟随透明代理端口）","Redirect 到本地 1053（TCP + UDP）","关闭 DNS 劫持"};
        ProxyRuntimeProfile.DnsHijack current=profile().dnsHijack;int selected=0;for(int i=0;i<values.length;i++)if(values[i]==current)selected=i;
        new AlertDialog.Builder(this).setTitle("DNS 劫持").setSingleChoiceItems(labels,selected,(d,w)->{
            prefs.edit().putString("proxyDnsHijack",values[w].id).apply();d.dismiss();refresh();changed();
        }).setNegativeButton("取消",null).show();
    }

    private void chooseOverwrite(){boolean now=prefs.getBoolean("proxyBaseAutoOverwrite",true);prefs.edit().putBoolean("proxyBaseAutoOverwrite",!now).apply();refresh();changed();}
    private void toggle(String key,boolean defaultValue){boolean now=prefs.getBoolean(key,defaultValue);prefs.edit().putBoolean(key,!now).apply();refresh();changed();}
    private void changed(){toast("已保存；代理正在运行时请重启后应用");}

    private Set<String> stringSet(String key){Set<String> s=prefs.getStringSet(key,Collections.emptySet());return s==null?new TreeSet<>():new TreeSet<>(s);}
    private void editSet(String key,String title,String hint){
        EditText input=new EditText(this);input.setText(TextUtils.join("\n",stringSet(key)));input.setHint(hint);input.setMinLines(5);input.setMaxLines(12);input.setGravity(Gravity.TOP|Gravity.START);input.setPadding(u.dp(14),u.dp(12),u.dp(14),u.dp(12));
        FrameLayout wrap=new FrameLayout(this);int m=u.dp(20);wrap.setPadding(m,u.dp(4),m,0);wrap.addView(input,new FrameLayout.LayoutParams(-1,-2));
        new AlertDialog.Builder(this).setTitle(title).setMessage(hint).setView(wrap).setPositiveButton("保存",(d,w)->{
            TreeSet<String> out=new TreeSet<>();String raw=input.getText().toString();for(String part:raw.split("[\\n,;]+")){String v=part.trim();if(!v.isEmpty())out.add(v);}
            prefs.edit().putStringSet(key,out).apply();refresh();changed();
        }).setNegativeButton("取消",null).show();
    }

    private void runPreflight(){
        task(()->{ProxyRuntimeProfile p=profile();RootProxyManager.Prepared prepared=root.prepare(p);root.preflight(prepared);String warning=prepared.policy.warning();return warning.isEmpty()?"预检通过：当前设备支持这组 Root 代理设置":"预检通过；"+warning;});
    }

    private void renderConfigs(){
        if(configRows==null)return;configRows.removeAllViews();ProxyRuntimeProfile.Core core=profile().core;List<ProxyConfigLibrary.Entry> list=configs.list(core);ProxyConfigLibrary.Entry selected=configs.selected(core);
        if(list.isEmpty()){TextView empty=u.text("尚无配置",15,u.muted,false);empty.setGravity(Gravity.CENTER_VERTICAL);empty.setPadding(0,u.dp(14),0,u.dp(14));configRows.addView(empty,new LinearLayout.LayoutParams(-1,u.dp(58)));return;}
        for(int i=0;i<list.size();i++){
            ProxyConfigLibrary.Entry entry=list.get(i);boolean checked=selected!=null&&selected.name.equals(entry.name);LinearLayout row=u.row();row.setMinimumHeight(u.dp(68));row.setPadding(0,u.dp(6),0,u.dp(6));row.setBackground(u.touch(android.graphics.Color.TRANSPARENT,14));
            TextView name=u.text(entry.name,15.5f,u.text,false);name.setSingleLine(true);name.setEllipsize(TextUtils.TruncateAt.MIDDLE);row.addView(name,new LinearLayout.LayoutParams(0,-2,1));
            if(checked)row.addView(new IconView(this,"check",u.accent),new LinearLayout.LayoutParams(u.dp(24),u.dp(24)));else row.addView(new View(this),new LinearLayout.LayoutParams(u.dp(24),u.dp(24)));
            row.setOnClickListener(v->{try{configs.select(core,entry.name);refresh();changed();}catch(Exception e){toast(safe(e));}});configRows.addView(row,new LinearLayout.LayoutParams(-1,-2));if(i<list.size()-1)u.separator(configRows);
        }
    }

    private void importConfig(){Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE);startActivityForResult(i,PICK_CONFIG);}

    private void showStartupConfig(){
        task(()->{RootProxyManager.Prepared prepared=root.prepare(profile());String text=prepared.startup;ui.post(()->new AlertDialog.Builder(this).setTitle("启动配置").setMessage(text.length()>14000?text.substring(0,14000)+"\n…已截断":text).setPositiveButton("关闭",null).show());return null;});
    }

    private void showDiagnostics(){
        task(()->{String text=root.diagnostics();if(TextUtils.isEmpty(text))text="未取得 Root 诊断信息";String finalText=text;ui.post(()->new AlertDialog.Builder(this).setTitle("Root 诊断").setMessage(finalText).setPositiveButton("关闭",null).show());return null;});
    }

    private void confirmRepairNetwork(){
        new AlertDialog.Builder(this).setTitle("清理残留并恢复网络").setMessage("这会停止当前 Root 代理，并清理辟尘创建的透明代理、Kill Switch、IPv6 临时状态和残留规则。不会删除订阅或节点配置。")
                .setPositiveButton("清理并停止",(d,w)->repairNetwork()).setNegativeButton("取消",null).show();
    }

    private void repairNetwork(){task(()->{root.stop();return "已停止 Root 代理并恢复辟尘网络规则";});}

    private void refresh(){
        if(destroyed)return;ProxyRuntimeProfile p=profile();coreValue.setText(p.core.label);modeValue.setText(p.mode.label);ipv6Value.setText(ipv6Label(p.ipv6));overwriteValue.setText(on(p.autoOverwrite));
        appScopeValue.setText(scopeLabel(p.appScope));tcpValue.setText(on(p.tcp));udpValue.setText(on(p.udp));dnsValue.setText(dnsLabel(p.dnsHijack));quicValue.setText(on(p.quicBlocked));shareValue.setText(on(prefs.getBoolean("proxySharedNetwork",false)));killValue.setText(on(prefs.getBoolean("proxyKillSwitch",false)));
        cnIpValue.setText(on(p.cnIpDirect));cidrValue.setText(stringSet("proxyBypassCidrs").isEmpty()?"未设置":stringSet("proxyBypassCidrs").size()+" 条");ifaceValue.setText(stringSet("proxyBypassInterfaces").isEmpty()?"未设置":stringSet("proxyBypassInterfaces").size()+" 个");renderConfigs();
    }

    private void task(Work work){if(busy||destroyed)return;busy=true;worker.execute(()->{String result=null;Throwable failure=null;try{result=work.run();}catch(Throwable e){failure=e;}String text=result;Throwable error=failure;ui.post(()->{busy=false;if(destroyed)return;if(error!=null)toast(safe(error));else if(!TextUtils.isEmpty(text))toast(text);});});}
    private void toast(String text){Toast.makeText(this,text,Toast.LENGTH_SHORT).show();}
    private String safe(Throwable e){String s=e.getMessage();if(TextUtils.isEmpty(s))s=e.getClass().getSimpleName();s=s.replace('\n',' ').replace('\r',' ');return s.length()>260?s.substring(0,260)+"…":s;}
    private String displayName(Uri uri){String name=null;try(Cursor c=getContentResolver().query(uri,new String[]{OpenableColumns.DISPLAY_NAME},null,null,null)){if(c!=null&&c.moveToFirst())name=c.getString(0);}catch(Exception ignored){}if(TextUtils.isEmpty(name))name=uri.getLastPathSegment();return TextUtils.isEmpty(name)?"config.yaml":name;}

    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(request!=PICK_CONFIG||result!=RESULT_OK||data==null||data.getData()==null)return;Uri uri=data.getData();String name=displayName(uri);ProxyRuntimeProfile.Core core=profile().core;task(()->{try(InputStream in=getContentResolver().openInputStream(uri)){configs.importConfig(core,name,in);}ui.post(this::refresh);return"已导入："+name;});}
    @Override protected void onResume(){super.onResume();if(prefs!=null)refresh();}
    @Override public void onDestroy(){destroyed=true;worker.shutdownNow();super.onDestroy();}
}
