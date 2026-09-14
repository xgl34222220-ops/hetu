package io.github.xgl34222220.bichen;

import android.app.*;
import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import android.net.VpnService;
import android.os.*;
import android.provider.OpenableColumns;
import android.text.TextUtils;
import android.view.*;
import android.widget.*;
import org.json.JSONObject;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

/** BoxProxy-style Basic Proxy Configuration. */
public final class RootTproxyActivity extends Activity {
    private static final int PICK_CONFIG=701,REQ_TUN=702,PICK_CORE=703;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final Handler ui=new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private ProxyUi u;
    private ProxyCoreStore cores;
    private ProxyConfigLibrary configs;
    private RootProxyManager root;
    private ProxyStore tunStore;
    private TextView coreValue,modeValue,ipv6Value,overwriteValue,configValue,coreStatus;
    private TextView stateValue,stateMeta,feedback,actionButton;
    private boolean busy,destroyed,running;

    private interface Work{String run()throws Exception;}

    @Override public void onCreate(Bundle state){
        prefs=getSharedPreferences("bichen",MODE_PRIVATE);
        setTheme(ProxyUi.isDark(this)?R.style.AppThemeDark:R.style.AppTheme);
        super.onCreate(state);
        u=new ProxyUi(this);cores=new ProxyCoreStore(this);configs=new ProxyConfigLibrary(this);root=new RootProxyManager(this);tunStore=new ProxyStore(this);
        build();refresh();
    }

    private void build(){
        LinearLayout shell=u.col();u.window(shell);
        ScrollView scroll=new ScrollView(this);scroll.setVerticalScrollBarEnabled(false);scroll.setClipToPadding(false);
        LinearLayout body=u.col();body.setPadding(u.dp(18),u.dp(8),u.dp(18),u.dp(34));scroll.addView(body);shell.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));

        LinearLayout top=u.row();
        top.addView(u.icon("back","返回",this::finish),new LinearLayout.LayoutParams(u.dp(48),u.dp(48)));
        LinearLayout titles=u.col();titles.setPadding(u.dp(12),0,0,0);titles.addView(u.text("基础代理配置",30,u.text,true));u.gap(titles,5);titles.addView(u.text("Root 代理运行参数",12,u.muted,false));top.addView(titles,new LinearLayout.LayoutParams(0,-2,1));body.addView(top);u.gap(body,15);

        LinearLayout basic=u.card(body);basic.setPadding(u.dp(17),u.dp(4),u.dp(17),u.dp(4));
        coreValue=settingRow(basic,"核心选择","",this::chooseCore);separator(basic);
        modeValue=settingRow(basic,"运行模式","",this::chooseMode);separator(basic);
        ipv6Value=settingRow(basic,"IPv6","",this::chooseIpv6);separator(basic);
        overwriteValue=settingRow(basic,"自动覆写","",this::chooseOverwrite);

        LinearLayout startup=u.card(body);startup.setPadding(u.dp(17),u.dp(4),u.dp(17),u.dp(4));
        u.action(startup,"document","查看启动配置","查看实际传给核心的最终配置",this::showStartupConfig);

        LinearLayout cfg=u.card(body);cfg.setPadding(u.dp(17),u.dp(4),u.dp(17),u.dp(4));
        LinearLayout cfgHead=u.row();cfgHead.setMinimumHeight(u.dp(60));cfgHead.addView(u.text("配置选择",17,u.text,true),new LinearLayout.LayoutParams(0,-2,1));TextView plus=u.text("＋",30,u.text,false);plus.setGravity(Gravity.CENTER);plus.setBackground(u.touch(u.soft,24));plus.setOnClickListener(v->importConfig());cfgHead.addView(plus,new LinearLayout.LayoutParams(u.dp(48),u.dp(48)));cfg.addView(cfgHead);separator(cfg);
        configValue=settingRow(cfg,"当前配置","尚未选择",this::chooseConfig);

        LinearLayout hero=u.card(body);hero.setPadding(u.dp(18),u.dp(16),u.dp(18),u.dp(16));
        stateValue=u.text("● 正在读取",18,u.text,true);hero.addView(stateValue);u.gap(hero,6);stateMeta=u.text("Mihomo · TPROXY",12,u.muted,false);hero.addView(stateMeta);u.gap(hero,13);actionButton=u.button("启动代理",true,this::toggleService);hero.addView(actionButton,new LinearLayout.LayoutParams(-1,u.dp(50)));u.gap(hero,9);feedback=u.text("",12,u.muted,false);feedback.setPadding(u.dp(12),u.dp(9),u.dp(12),u.dp(9));feedback.setBackground(u.bg(u.soft,14));feedback.setMaxLines(3);feedback.setEllipsize(TextUtils.TruncateAt.END);feedback.setVisibility(View.GONE);hero.addView(feedback);

        section(body,"工具");
        LinearLayout tools=u.card(body);tools.setPadding(u.dp(17),u.dp(4),u.dp(17),u.dp(4));
        coreStatus=settingRow(tools,"核心管理","",this::manageCore);separator(tools);u.action(tools,"pulse","运行日志","查看核心与 Root 启动错误",this::showRootLog);
        setContentView(shell);
    }

    private void section(LinearLayout parent,String title){TextView t=u.text(title,12,u.muted,true);t.setPadding(u.dp(5),u.dp(4),0,u.dp(8));parent.addView(t);}
    private TextView settingRow(LinearLayout parent,String label,String value,Runnable action){
        LinearLayout row=u.row();row.setMinimumHeight(u.dp(61));row.setPadding(u.dp(2),u.dp(5),0,u.dp(5));row.setBackground(u.touch(android.graphics.Color.TRANSPARENT,14));
        row.addView(u.text(label,15,u.text,true),new LinearLayout.LayoutParams(0,-2,1));
        TextView right=u.text(value,13,u.muted,false);right.setGravity(Gravity.RIGHT|Gravity.CENTER_VERTICAL);right.setMaxWidth(u.dp(190));right.setEllipsize(TextUtils.TruncateAt.END);right.setSingleLine(true);row.addView(right,new LinearLayout.LayoutParams(-2,-1));
        row.addView(new IconView(this,"chevron",u.muted),new LinearLayout.LayoutParams(u.dp(17),u.dp(17)));row.setOnClickListener(v->action.run());parent.addView(row,new LinearLayout.LayoutParams(-1,-2));return right;
    }
    private void separator(LinearLayout p){View line=new View(this);line.setBackgroundColor(u.dark?0x203e4d43:0x0f17211b);p.addView(line,new LinearLayout.LayoutParams(-1,u.dp(1)));}

    private ProxyRuntimeProfile profile(){return ProxyRuntimeProfile.load(prefs);}
    private String ipv6Label(ProxyRuntimeProfile.Ipv6 v){switch(v){case BYPASS:return"IPv6 不进核心";case DISABLE:return"禁用系统 IPv6";default:return"启用 IPv6";}}

    private void toggleService(){if(busy)return;if(running)stopSelectedMode();else startSelectedMode();}
    private void setBusyState(boolean starting,String stage){busy=true;stateValue.setText(starting?"● 正在启动":"● 正在停止");stateValue.setTextColor(u.accent);stateMeta.setText(profile().summary());actionButton.setEnabled(false);actionButton.setAlpha(.62f);actionButton.setText(starting?"正在启动…":"正在停止…");showFeedback(stage);}
    private void postStage(String stage){ui.post(()->{if(!destroyed&&busy)showFeedback(stage);});}
    private void finishBusy(boolean nowRunning,String note){busy=false;running=nowRunning;actionButton.setEnabled(true);actionButton.setAlpha(1f);actionButton.setText(nowRunning?"停止代理":"启动代理");stateValue.setText(nowRunning?"● 运行中":"● 未运行");stateValue.setTextColor(nowRunning?u.accent:u.text);stateMeta.setText(profile().summary()+(nowRunning?" · 已接管流量":""));if(!TextUtils.isEmpty(note))showFeedback(note);}

    private void startSelectedMode(){
        if(busy)return;ProxyRuntimeProfile p=profile();ProxyRuntimeProfile.Capability c=p.capability();
        if(!c.available){showFeedback(c.reason);return;}if(configs.selected(p.core)==null){showFeedback("请先为 "+p.core.label+" 导入并选择配置");return;}
        if(p.mode==ProxyRuntimeProfile.Mode.TUN){prepareTun(p);return;}
        setBusyState(true,"检查配置与 Root 环境…");worker.execute(()->{try{JSONObject result=root.start(p,this::postStage);prefs.edit().putBoolean("rootProxyWanted",true).putString("rootProxyMode",p.mode.id).remove("rootProxyLastError").apply();String text=result.optString("message","Root 代理已启动");ui.post(()->{if(!destroyed)finishBusy(true,text+" · "+p.summary());});}catch(Throwable e){String diag=root.diagnostics();String detail="启动失败："+full(e);if(!TextUtils.isEmpty(diag))detail+="\n\n诊断："+diag;prefs.edit().putString("rootProxyLastError",detail).apply();String out="启动失败："+safe(e)+"\n点击“运行日志”查看完整详情";ui.post(()->{if(!destroyed)finishBusy(false,out);});}});
    }

    private void stopSelectedMode(){if(busy)return;ProxyRuntimeProfile p=profile();if(p.mode==ProxyRuntimeProfile.Mode.TUN){setBusyState(false,"正在停止 Android TUN…");try{startService(new Intent(this,MihomoVpnService.class).setAction("STOP"));ui.postDelayed(()->{if(!destroyed)finishBusy(false,"TUN 已停止");},450);}catch(Throwable e){finishBusy(running,"停止失败："+safe(e));}return;}setBusyState(false,"正在清理 Root 透明代理规则…");worker.execute(()->{try{String text=root.stop(this::postStage).optString("message","Root 代理已停止");prefs.edit().putBoolean("rootProxyWanted",false).remove("rootProxyMode").apply();ui.post(()->{if(!destroyed)finishBusy(false,text);});}catch(Throwable e){String msg="停止失败："+safe(e);ui.post(()->{if(!destroyed)finishBusy(running,msg);});}});}

    private void prepareTun(ProxyRuntimeProfile p){setBusyState(true,"检查 TUN 配置…");worker.execute(()->{try{ProxyConfigLibrary.Entry selected=configs.selected(p.core);String source=configs.read(selected);String revision=tunStore.revision();tunStore.saveIfUnchanged(source,"",revision);ui.post(()->{if(destroyed)return;Intent permission=VpnService.prepare(this);if(permission==null)launchTun();else startActivityForResult(permission,REQ_TUN);});}catch(Throwable e){String msg="TUN 配置失败："+safe(e);ui.post(()->{if(!destroyed)finishBusy(false,msg);});}});}
    private void launchTun(){try{showFeedback("启动 Android TUN…");startForegroundService(new Intent(this,MihomoVpnService.class).setAction("START"));ui.postDelayed(()->{if(destroyed)return;running=MihomoVpnService.running;finishBusy(running,running?"Mihomo TUN 已启动":"TUN 启动请求已发送，等待系统服务状态");},700);}catch(Throwable e){finishBusy(false,"TUN 未启动："+safe(e));}}

    private void chooseCore(){ProxyRuntimeProfile.Core[] values=ProxyRuntimeProfile.Core.values();String[] labels=new String[values.length];int selected=0;for(int i=0;i<values.length;i++){labels[i]=values[i].label+(cores.installed(values[i])?" · 已安装":values[i]==ProxyRuntimeProfile.Core.MIHOMO?" · 内置":" · 未安装");if(values[i]==profile().core)selected=i;}new AlertDialog.Builder(this).setTitle("核心").setSingleChoiceItems(labels,selected,(d,w)->{prefs.edit().putString("proxyBaseCore",values[w].id).apply();d.dismiss();refresh();if(values[w]!=ProxyRuntimeProfile.Core.MIHOMO&&!cores.installed(values[w]))showFeedback(values[w].label+" 尚未安装核心");}).setNegativeButton("取消",null).show();}
    private void chooseMode(){ProxyRuntimeProfile.Core core=profile().core;ProxyRuntimeProfile.Mode[] values=ProxyRuntimeProfile.Mode.values();String[] labels=new String[values.length];int selected=0;for(int i=0;i<values.length;i++){ProxyRuntimeProfile.Capability c=ProxyRuntimeProfile.capability(core,values[i]);labels[i]=values[i].label+(c.available?"":" · 未接通");if(values[i]==profile().mode)selected=i;}new AlertDialog.Builder(this).setTitle("运行模式").setSingleChoiceItems(labels,selected,(d,w)->{ProxyRuntimeProfile.Capability c=ProxyRuntimeProfile.capability(core,values[w]);if(!c.available){showFeedback(c.reason);d.dismiss();return;}prefs.edit().putString("proxyBaseMode",values[w].id).apply();d.dismiss();refresh();}).setNegativeButton("取消",null).show();}
    private void chooseIpv6(){ProxyRuntimeProfile.Ipv6[] values=ProxyRuntimeProfile.Ipv6.values();String[] labels={"启用 IPv6","IPv6 不进核心","禁用系统 IPv6"};int selected=profile().ipv6.ordinal();new AlertDialog.Builder(this).setTitle("IPv6").setSingleChoiceItems(labels,selected,(d,w)->{prefs.edit().putString("proxyBaseIpv6",values[w].id).apply();d.dismiss();refresh();}).setNegativeButton("取消",null).show();}
    private void chooseOverwrite(){boolean now=prefs.getBoolean("proxyBaseAutoOverwrite",true);prefs.edit().putBoolean("proxyBaseAutoOverwrite",!now).apply();refresh();showFeedback(!now?"自动覆写已开启：启动副本会按当前模式生成，源配置保持不变":"自动覆写已关闭：仍会隔离与当前模式冲突的运行入站，源配置保持不变");}

    private void chooseConfig(){ProxyRuntimeProfile.Core core=profile().core;List<ProxyConfigLibrary.Entry> list=configs.list(core);String[] items=new String[list.size()+1];items[0]="＋ 导入新配置";ProxyConfigLibrary.Entry current=configs.selected(core);for(int i=0;i<list.size();i++)items[i+1]=(current!=null&&current.name.equals(list.get(i).name)?"✓ ":"")+list.get(i).name;new AlertDialog.Builder(this).setTitle(core.label+" 配置").setItems(items,(d,w)->{if(w==0){importConfig();return;}try{configs.select(core,list.get(w-1).name);refresh();showFeedback("已选择："+list.get(w-1).name);}catch(Exception e){showFeedback(safe(e));}}).setNegativeButton("取消",null).show();}
    private void importConfig(){Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE);startActivityForResult(i,PICK_CONFIG);}

    private void manageCore(){ProxyRuntimeProfile.Core core=profile().core;String state=cores.installed(core)?cores.summary(core):(core==ProxyRuntimeProfile.Core.MIHOMO?"使用 APK 内置 Mihomo；可导入自定义核心覆盖":"尚未安装");new AlertDialog.Builder(this).setTitle(core.label+" 核心").setMessage(state).setItems(new String[]{"导入 / 覆盖核心文件","删除已导入核心"},(d,w)->{if(w==0){Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("application/octet-stream").addCategory(Intent.CATEGORY_OPENABLE);startActivityForResult(i,PICK_CORE);}else task(()->{cores.remove(core);return"已删除自定义 "+core.label+" 核心";},this::refresh);}).setNegativeButton("取消",null).show();}

    private void showStartupConfig(){ProxyRuntimeProfile p=profile();if(p.mode==ProxyRuntimeProfile.Mode.TUN){new AlertDialog.Builder(this).setTitle("最终启动配置").setMessage("当前 Android TUN 仍使用独立私有副本；Root TUN 接入后将统一使用 startup-config。").setPositiveButton("关闭",null).show();return;}task(()->{RootProxyManager.Prepared prepared=root.prepare(p);String text=prepared.startup;ui.post(()->new AlertDialog.Builder(this).setTitle("最终启动配置 · "+p.summary()).setMessage(text.length()>12000?text.substring(0,12000)+"\n…已截断":text).setPositiveButton("关闭",null).show());return"启动配置已重新生成";},null);}
    private void showRootLog(){task(()->{RootBridge.Result r=RootBridge.rootShell(this,"tail -n 200 /data/adb/bichen/proxy/run/core.log 2>/dev/null || true",10_000L);String live=r.output.trim();String saved=prefs.getString("rootProxyLastError","");StringBuilder text=new StringBuilder();if(!TextUtils.isEmpty(saved))text.append("最近一次启动错误\n").append(saved);if(!TextUtils.isEmpty(live)){if(text.length()>0)text.append("\n\n");text.append("Mihomo 运行日志\n").append(live);}String shown=text.length()==0?"暂无日志":text.toString();ui.post(()->new AlertDialog.Builder(this).setTitle("运行日志").setMessage(shown.length()>16000?shown.substring(shown.length()-16000):shown).setPositiveButton("关闭",null).show());return null;},null);}

    private void refresh(){if(destroyed)return;ProxyRuntimeProfile p=profile();coreValue.setText(p.core.label);modeValue.setText(p.mode.label);ipv6Value.setText(ipv6Label(p.ipv6));overwriteValue.setText(p.autoOverwrite?"开启":"关闭");ProxyConfigLibrary.Entry selected=configs.selected(p.core);configValue.setText(selected==null?"尚未选择":selected.name);coreStatus.setText(cores.installed(p.core)?cores.summary(p.core):(p.core==ProxyRuntimeProfile.Core.MIHOMO?"内置核心可用":"未安装"));stateMeta.setText(p.summary());if(busy)return;if(p.mode==ProxyRuntimeProfile.Mode.TUN){running=MihomoVpnService.running;finishBusy(running,"");return;}worker.execute(()->{try{JSONObject s=root.status();boolean live=s.optBoolean("running",false);ui.post(()->{if(!destroyed&&!busy){running=live;finishBusy(live,"");}});}catch(Exception e){String msg="状态检查失败："+safe(e);ui.post(()->{if(!destroyed&&!busy){running=false;finishBusy(false,msg);}});}});}

    private void task(Work work,Runnable success){if(busy||destroyed)return;busy=true;actionButton.setEnabled(false);actionButton.setAlpha(.62f);showFeedback("处理中…");worker.execute(()->{String result=null;Throwable failure=null;try{result=work.run();}catch(Throwable e){failure=e;}String text=result;Throwable error=failure;ui.post(()->{busy=false;if(destroyed)return;actionButton.setEnabled(true);actionButton.setAlpha(1f);actionButton.setText(running?"停止代理":"启动代理");if(error!=null)showFeedback(safe(error));else{if(!TextUtils.isEmpty(text))showFeedback(text);if(success!=null)success.run();}});});}
    private void showFeedback(String text){if(feedback==null)return;feedback.setText(text);feedback.setVisibility(TextUtils.isEmpty(text)?View.GONE:View.VISIBLE);}
    private String full(Throwable e){String s=e.getMessage();if(TextUtils.isEmpty(s))s=e.toString();return s;}
    private String safe(Throwable e){String s=full(e).replace('\n',' ').replace('\r',' ');return s.length()>260?s.substring(0,260)+"…":s;}
    private String displayName(Uri uri){String name=null;try(Cursor c=getContentResolver().query(uri,new String[]{OpenableColumns.DISPLAY_NAME},null,null,null)){if(c!=null&&c.moveToFirst())name=c.getString(0);}catch(Exception ignored){}if(TextUtils.isEmpty(name))name=uri.getLastPathSegment();return TextUtils.isEmpty(name)?"config.yaml":name;}

    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(request==REQ_TUN){if(result==RESULT_OK)launchTun();else finishBusy(false,"VPN 未授权，未启动 TUN");return;}if(result!=RESULT_OK||data==null||data.getData()==null)return;Uri uri=data.getData();if(request==PICK_CONFIG){String name=displayName(uri);ProxyRuntimeProfile.Core core=profile().core;task(()->{try(InputStream in=getContentResolver().openInputStream(uri)){configs.importConfig(core,name,in);}return"已导入并选择："+name;},this::refresh);}else if(request==PICK_CORE){ProxyRuntimeProfile.Core core=profile().core;task(()->{try(InputStream in=getContentResolver().openInputStream(uri)){cores.importCore(core,in);}return"已导入 "+core.label+" 核心";},this::refresh);}}
    @Override public void onResume(){super.onResume();if(prefs!=null)refresh();}
    @Override public void onDestroy(){destroyed=true;worker.shutdownNow();super.onDestroy();}
}
