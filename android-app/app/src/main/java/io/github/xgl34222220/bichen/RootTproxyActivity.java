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
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

/** BoxProxy-style single proxy control surface backed by separate core/config/runtime stores. */
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
    private TextView coreValue,modeValue,ipv6Value,overwriteValue,configValue,coreStatus,stateValue,message;
    private boolean busy,destroyed;

    private interface Work{String run()throws Exception;}

    @Override public void onCreate(Bundle state){
        prefs=getSharedPreferences("bichen",MODE_PRIVATE);setTheme(ProxyUi.isDark(this)?R.style.AppThemeDark:R.style.AppTheme);super.onCreate(state);
        u=new ProxyUi(this);cores=new ProxyCoreStore(this);configs=new ProxyConfigLibrary(this);root=new RootProxyManager(this);tunStore=new ProxyStore(this);build();refresh();
    }

    private void build(){
        LinearLayout shell=u.col();u.window(shell);ScrollView scroll=new ScrollView(this);scroll.setVerticalScrollBarEnabled(false);scroll.setClipToPadding(false);LinearLayout body=u.col();body.setPadding(u.dp(21),u.dp(8),u.dp(21),u.dp(34));scroll.addView(body);shell.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout top=u.row();top.addView(u.icon("back","返回",this::finish),new LinearLayout.LayoutParams(u.dp(48),u.dp(48)));LinearLayout titles=u.col();titles.setPadding(u.dp(10),0,0,0);titles.addView(u.text("基础代理配置",27,u.text,true));u.gap(titles,6);titles.addView(u.text("Root 控制 · 核心、源配置、启动配置相互独立",12,u.muted,false));top.addView(titles,new LinearLayout.LayoutParams(0,-2,1));body.addView(top);u.gap(body,14);

        LinearLayout runtime=u.card(body);runtime.setPadding(u.dp(20),u.dp(18),u.dp(20),u.dp(18));LinearLayout rh=u.row();LinearLayout rl=u.col();rl.addView(u.text("代理服务",18,u.text,true));u.gap(rl,7);stateValue=u.text("正在读取状态",12,u.muted,false);rl.addView(stateValue);rh.addView(rl,new LinearLayout.LayoutParams(0,-2,1));runtime.addView(rh);u.gap(runtime,16);LinearLayout actions=u.row();TextView stop=u.button("停止",false,this::stopSelectedMode),start=u.button("启动",true,this::startSelectedMode);LinearLayout.LayoutParams a=new LinearLayout.LayoutParams(0,u.dp(52),1);a.rightMargin=u.dp(7);actions.addView(stop,a);LinearLayout.LayoutParams b=new LinearLayout.LayoutParams(0,u.dp(52),1);b.leftMargin=u.dp(7);actions.addView(start,b);runtime.addView(actions);

        LinearLayout settings=u.card(body);settings.setPadding(u.dp(20),u.dp(7),u.dp(20),u.dp(7));coreValue=settingRow(settings,"核心选择","",this::chooseCore);separator(settings);modeValue=settingRow(settings,"运行模式","",this::chooseMode);separator(settings);ipv6Value=settingRow(settings,"IPv6","",this::chooseIpv6);separator(settings);overwriteValue=settingRow(settings,"自动覆写","",this::chooseOverwrite);

        LinearLayout cfg=u.card(body);cfg.setPadding(u.dp(20),u.dp(14),u.dp(20),u.dp(12));LinearLayout ch=u.row();ch.addView(u.text("配置选择",18,u.text,true),new LinearLayout.LayoutParams(0,-2,1));TextView plus=u.text("＋",28,u.text,false);plus.setGravity(Gravity.CENTER);plus.setBackground(u.touch(u.soft,22));plus.setContentDescription("导入配置");plus.setOnClickListener(v->importConfig());ch.addView(plus,new LinearLayout.LayoutParams(u.dp(44),u.dp(44)));cfg.addView(ch);u.gap(cfg,6);separator(cfg);LinearLayout cr=u.row();cr.setMinimumHeight(u.dp(78));cr.setPadding(u.dp(4),u.dp(8),0,u.dp(8));LinearLayout ct=u.col();configValue=u.text("尚未选择配置",17,u.text,true);ct.addView(configValue);u.gap(ct,7);ct.addView(u.text("点击切换多份配置 · ＋ 导入新配置",12,u.muted,false));cr.addView(ct,new LinearLayout.LayoutParams(0,-2,1));cr.addView(new IconView(this,"chevron",u.muted),new LinearLayout.LayoutParams(u.dp(18),u.dp(18)));cr.setOnClickListener(v->chooseConfig());cfg.addView(cr);

        LinearLayout tools=u.card(body);tools.setPadding(u.dp(20),u.dp(4),u.dp(20),u.dp(4));coreStatus=settingRow(tools,"核心管理","",this::manageCore);separator(tools);settingRow(tools,"查看启动配置","源配置不会被自动覆写",this::showStartupConfig);separator(tools);settingRow(tools,"当前运行日志","Root 核心启动与规则错误",this::showRootLog);

        LinearLayout info=u.card(body);info.setPadding(u.dp(20),u.dp(16),u.dp(20),u.dp(16));info.addView(u.text("当前阶段",15,u.text,true));u.gap(info,8);info.addView(u.text("Mihomo 已接通 TUN、TPROXY、Redirect、Enhance。Mixed、eBPF 和其他核心会在后端真正接通后再开放，不做假按钮。",12,u.muted,false));
        message=u.text("",12,u.accent,false);message.setPadding(u.dp(16),u.dp(12),u.dp(16),u.dp(12));message.setBackground(u.bg(u.soft,16));message.setVisibility(View.GONE);body.addView(message);setContentView(shell);
    }

    private TextView settingRow(LinearLayout parent,String label,String value,Runnable action){LinearLayout row=u.row();row.setMinimumHeight(u.dp(66));row.setPadding(u.dp(2),u.dp(7),0,u.dp(7));row.setBackground(u.touch(android.graphics.Color.TRANSPARENT,15));row.addView(u.text(label,16,u.text,true),new LinearLayout.LayoutParams(0,-2,1));TextView right=u.text(value,13,u.muted,false);right.setGravity(Gravity.RIGHT|Gravity.CENTER_VERTICAL);right.setMaxWidth(u.dp(210));right.setEllipsize(TextUtils.TruncateAt.END);right.setSingleLine(true);row.addView(right,new LinearLayout.LayoutParams(-2,-1));row.addView(new IconView(this,"chevron",u.muted),new LinearLayout.LayoutParams(u.dp(18),u.dp(18)));row.setOnClickListener(v->action.run());parent.addView(row,new LinearLayout.LayoutParams(-1,-2));return right;}
    private void separator(LinearLayout p){View line=new View(this);line.setBackgroundColor(u.dark?0x24364b40:0x12000000);p.addView(line,new LinearLayout.LayoutParams(-1,u.dp(1)));}

    private ProxyRuntimeProfile profile(){return ProxyRuntimeProfile.load(prefs);}
    private String ipv6Label(ProxyRuntimeProfile.Ipv6 v){switch(v){case BYPASS:return"IPv6 不进核心";case DISABLE:return"禁用系统 IPv6";default:return"启用 IPv6";}}

    private void chooseCore(){ProxyRuntimeProfile.Core[] values=ProxyRuntimeProfile.Core.values();String[] labels=new String[values.length];int selected=0;for(int i=0;i<values.length;i++){labels[i]=values[i].label+(cores.installed(values[i])?" · 已安装":values[i]==ProxyRuntimeProfile.Core.MIHOMO?" · 内置可用":" · 未安装");if(values[i]==profile().core)selected=i;}new AlertDialog.Builder(this).setTitle("核心选择").setSingleChoiceItems(labels,selected,(d,w)->{prefs.edit().putString("proxyBaseCore",values[w].id).apply();d.dismiss();refresh();if(values[w]!=ProxyRuntimeProfile.Core.MIHOMO&&!cores.installed(values[w]))showMessage(values[w].label+" 尚未安装核心，可在“核心管理”导入；不会假报可运行。");}).setNegativeButton("取消",null).show();}
    private void chooseMode(){ProxyRuntimeProfile.Core core=profile().core;ProxyRuntimeProfile.Mode[] values=ProxyRuntimeProfile.Mode.values();String[] labels=new String[values.length];int selected=0;for(int i=0;i<values.length;i++){ProxyRuntimeProfile.Capability c=ProxyRuntimeProfile.capability(core,values[i]);labels[i]=values[i].label+(c.available?"":" · 未接通");if(values[i]==profile().mode)selected=i;}new AlertDialog.Builder(this).setTitle("运行模式").setSingleChoiceItems(labels,selected,(d,w)->{ProxyRuntimeProfile.Capability c=ProxyRuntimeProfile.capability(core,values[w]);if(!c.available){showMessage(c.reason);d.dismiss();return;}prefs.edit().putString("proxyBaseMode",values[w].id).apply();d.dismiss();refresh();}).setNegativeButton("取消",null).show();}
    private void chooseIpv6(){ProxyRuntimeProfile.Ipv6[] values=ProxyRuntimeProfile.Ipv6.values();String[] labels={"启用 IPv6","IPv6 不进核心","禁用系统 IPv6"};int selected=profile().ipv6.ordinal();new AlertDialog.Builder(this).setTitle("IPv6").setSingleChoiceItems(labels,selected,(d,w)->{prefs.edit().putString("proxyBaseIpv6",values[w].id).apply();d.dismiss();refresh();}).setNegativeButton("取消",null).show();}
    private void chooseOverwrite(){boolean now=prefs.getBoolean("proxyBaseAutoOverwrite",true);prefs.edit().putBoolean("proxyBaseAutoOverwrite",!now).apply();refresh();showMessage(!now?"自动覆写已开启：只生成独立启动配置，源配置不修改":"自动覆写已关闭：启动配置直接使用源配置，端口和入站需自行维护");}

    private void chooseConfig(){ProxyRuntimeProfile.Core core=profile().core;List<ProxyConfigLibrary.Entry> list=configs.list(core);String[] items=new String[list.size()+1];items[0]="＋ 导入新配置";for(int i=0;i<list.size();i++)items[i+1]=(configs.selected(core)!=null&&configs.selected(core).name.equals(list.get(i).name)?"✓ ":"")+list.get(i).name;new AlertDialog.Builder(this).setTitle(core.label+" 配置").setItems(items,(d,w)->{if(w==0){importConfig();return;}try{configs.select(core,list.get(w-1).name);refresh();showMessage("已选择："+list.get(w-1).name);}catch(Exception e){showMessage(safe(e));}}).setNegativeButton("取消",null).show();}
    private void importConfig(){Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE);startActivityForResult(i,PICK_CONFIG);}

    private void manageCore(){ProxyRuntimeProfile.Core core=profile().core;String state=cores.installed(core)?cores.summary(core):(core==ProxyRuntimeProfile.Core.MIHOMO?"使用 APK 内置 Mihomo；导入后会覆盖为自定义核心":"尚未安装");new AlertDialog.Builder(this).setTitle(core.label+" 核心").setMessage(state).setItems(new String[]{"导入 / 覆盖核心文件","删除已导入核心"},(d,w)->{if(w==0){Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("application/octet-stream").addCategory(Intent.CATEGORY_OPENABLE);startActivityForResult(i,PICK_CORE);}else task(()->{cores.remove(core);return"已删除自定义 "+core.label+" 核心";},this::refresh);}).setNegativeButton("取消",null).show();}

    private void startSelectedMode(){if(busy)return;ProxyRuntimeProfile p=profile();ProxyRuntimeProfile.Capability c=p.capability();if(!c.available){showMessage(c.reason);return;}if(configs.selected(p.core)==null){showMessage("请先为 "+p.core.label+" 导入并选择配置");return;}if(p.mode==ProxyRuntimeProfile.Mode.TUN){prepareTun(p);return;}task(()->{JSONObject result=root.start(p);prefs.edit().putBoolean("rootProxyWanted",true).putString("rootProxyMode",p.mode.id).apply();return result.optString("message","Root 代理已启动")+" · "+p.summary();},this::refresh);}
    private void prepareTun(ProxyRuntimeProfile p){task(()->{ProxyConfigLibrary.Entry selected=configs.selected(p.core);String source=configs.read(selected);String revision=tunStore.revision();tunStore.saveIfUnchanged(source,"",revision);return null;},()->{Intent permission=VpnService.prepare(this);if(permission==null)launchTun();else startActivityForResult(permission,REQ_TUN);});}
    private void launchTun(){try{startForegroundService(new Intent(this,MihomoVpnService.class).setAction("START"));showMessage("Mihomo TUN 启动请求已发送");ui.postDelayed(this::refresh,400);}catch(Exception e){showMessage("TUN 未启动："+safe(e));}}
    private void stopSelectedMode(){ProxyRuntimeProfile p=profile();if(p.mode==ProxyRuntimeProfile.Mode.TUN){startService(new Intent(this,MihomoVpnService.class).setAction("STOP"));showMessage("TUN 停止请求已发送");ui.postDelayed(this::refresh,350);return;}task(()->{String text=root.stop().optString("message","Root 代理已停止");prefs.edit().putBoolean("rootProxyWanted",false).remove("rootProxyMode").apply();return text;},this::refresh);}

    private void showStartupConfig(){ProxyRuntimeProfile p=profile();if(p.mode==ProxyRuntimeProfile.Mode.TUN){new AlertDialog.Builder(this).setTitle("启动配置").setMessage("TUN 仍由 Android VPN 适配器生成私有运行副本；源配置不会写回。Root TUN 完成后会与透明代理模式统一使用 startup-config。").setPositiveButton("知道了",null).show();return;}task(()->{RootProxyManager.Prepared prepared=root.prepare(p);final String text=prepared.startup;ui.post(()->new AlertDialog.Builder(this).setTitle("最终启动配置 · "+p.summary()).setMessage(text.length()>12000?text.substring(0,12000)+"\n…内容过长，已截断显示":text).setPositiveButton("关闭",null).show());return"启动配置已重新生成，源配置未修改";},null);}
    private void showRootLog(){task(()->{RootBridge.Result r=RootBridge.rootShell(this,"tail -n 200 /data/adb/bichen/proxy/run/core.log 2>/dev/null || true",10_000L);String text=r.output.trim();ui.post(()->new AlertDialog.Builder(this).setTitle("Root 运行日志").setMessage(text.isEmpty()?"暂无日志":text).setPositiveButton("关闭",null).show());return null;},null);}

    private void refresh(){if(destroyed)return;ProxyRuntimeProfile p=profile();if(coreValue!=null)coreValue.setText(p.core.label);if(modeValue!=null)modeValue.setText(p.mode.label);if(ipv6Value!=null)ipv6Value.setText(ipv6Label(p.ipv6));if(overwriteValue!=null)overwriteValue.setText(p.autoOverwrite?"开启":"关闭");ProxyConfigLibrary.Entry selected=configs.selected(p.core);if(configValue!=null)configValue.setText(selected==null?"尚未选择配置":selected.name);if(coreStatus!=null)coreStatus.setText(cores.installed(p.core)?cores.summary(p.core):(p.core==ProxyRuntimeProfile.Core.MIHOMO?"内置核心可用":"未安装"));if(stateValue==null)return;if(p.mode==ProxyRuntimeProfile.Mode.TUN){stateValue.setText(MihomoVpnService.running?p.summary()+" 运行中":MihomoVpnService.engaged?MihomoVpnService.state:p.summary()+" 未运行");return;}worker.execute(()->{try{JSONObject s=root.status();ui.post(()->{if(!destroyed&&stateValue!=null)stateValue.setText(s.optBoolean("running",false)?p.core.label+" · "+s.optString("mode",p.mode.id)+" 运行中 · PID "+s.optInt("pid"):p.summary()+" 未运行");});}catch(Exception e){ui.post(()->{if(!destroyed&&stateValue!=null)stateValue.setText(p.summary()+" · 状态读取失败");});}});}

    private void task(Work work,Runnable success){if(busy||destroyed)return;busy=true;worker.execute(()->{String result=null;Exception failure=null;try{result=work.run();}catch(Exception e){failure=e;}final String text=result;final Exception error=failure;ui.post(()->{busy=false;if(destroyed)return;if(error!=null)showMessage(safe(error));else{if(!TextUtils.isEmpty(text))showMessage(text);if(success!=null)success.run();}});});}
    private void showMessage(String text){if(message==null)return;message.setText(text);message.setVisibility(TextUtils.isEmpty(text)?View.GONE:View.VISIBLE);}
    private String safe(Throwable e){String s=e.getMessage();if(TextUtils.isEmpty(s))s=e.getClass().getSimpleName();return s.length()>600?s.substring(0,600)+"…":s;}

    private String displayName(Uri uri){String name=null;try(Cursor c=getContentResolver().query(uri,new String[]{OpenableColumns.DISPLAY_NAME},null,null,null)){if(c!=null&&c.moveToFirst())name=c.getString(0);}catch(Exception ignored){}if(TextUtils.isEmpty(name))name=uri.getLastPathSegment();return TextUtils.isEmpty(name)?"config.yaml":name;}

    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(request==REQ_TUN){if(result==RESULT_OK)launchTun();else showMessage("VPN 未授权，未启动 TUN");return;}if(result!=RESULT_OK||data==null||data.getData()==null)return;Uri uri=data.getData();if(request==PICK_CONFIG){String name=displayName(uri);ProxyRuntimeProfile.Core core=profile().core;task(()->{try(InputStream in=getContentResolver().openInputStream(uri)){configs.importConfig(core,name,in);}return"已导入并选择配置："+name;},this::refresh);}else if(request==PICK_CORE){ProxyRuntimeProfile.Core core=profile().core;task(()->{try(InputStream in=getContentResolver().openInputStream(uri)){cores.importCore(core,in);}return"已导入 "+core.label+" 核心";},this::refresh);}}
    @Override public void onResume(){super.onResume();if(prefs!=null)refresh();}
    @Override public void onDestroy(){destroyed=true;worker.shutdownNow();super.onDestroy();}
}
