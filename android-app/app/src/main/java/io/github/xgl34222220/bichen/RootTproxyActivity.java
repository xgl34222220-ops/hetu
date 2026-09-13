package io.github.xgl34222220.bichen;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.VpnService;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Root / VPN basic proxy settings. Only wired backends are allowed to report success. */
public final class RootTproxyActivity extends Activity {
    private static final String PREF_CORE="proxyBaseCore";
    private static final String PREF_MODE="proxyBaseMode";
    private static final String PREF_IPV6="proxyBaseIpv6";
    private static final String PREF_OVERWRITE="proxyBaseAutoOverwrite";
    private static final int PICK_YAML=701, REQ_TUN=702;

    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final Handler ui=new Handler(Looper.getMainLooper());
    private ProxyStore store;
    private RootTproxyManager root;
    private SharedPreferences prefs;
    private ProxyUi u;
    private TextView coreValue,modeValue,ipv6Value,overwriteValue,configValue,stateValue,message;
    private boolean busy,destroyed;

    @Override public void onCreate(Bundle state){
        setTheme(ProxyUi.isDark(this)?R.style.AppThemeDark:R.style.AppTheme);
        super.onCreate(state);
        prefs=getSharedPreferences("bichen",MODE_PRIVATE);
        store=new ProxyStore(this);
        root=new RootTproxyManager(this);
        build();
        refresh();
    }

    private void build(){
        u=new ProxyUi(this);
        LinearLayout shell=u.col();u.window(shell);
        ScrollView scroll=new ScrollView(this);scroll.setVerticalScrollBarEnabled(false);scroll.setClipToPadding(false);
        LinearLayout body=u.col();body.setPadding(u.dp(22),u.dp(10),u.dp(22),u.dp(34));scroll.addView(body);shell.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));

        LinearLayout top=u.row();
        top.addView(u.icon("back","返回",this::finish),new LinearLayout.LayoutParams(u.dp(48),u.dp(48)));
        body.addView(top);
        TextView title=u.text("基础代理配置",29,u.text,true);title.setPadding(u.dp(4),u.dp(13),0,u.dp(22));body.addView(title);

        LinearLayout settings=u.card(body);settings.setPadding(u.dp(20),u.dp(8),u.dp(20),u.dp(8));
        coreValue=settingRow(settings,"核心选择",currentCoreLabel(),this::chooseCore,true);separator(settings);
        modeValue=settingRow(settings,"运行模式",currentModeLabel(),this::chooseMode,true);separator(settings);
        ipv6Value=settingRow(settings,"IPv6",currentIpv6Label(),this::chooseIpv6,true);separator(settings);
        overwriteValue=settingRow(settings,"自动覆写",prefs.getBoolean(PREF_OVERWRITE,true)?"开启":"关闭",this::chooseOverwrite,true);

        LinearLayout launch=u.card(body);launch.setPadding(u.dp(20),u.dp(3),u.dp(20),u.dp(3));
        settingRow(launch,"查看启动配置","",this::showLaunchConfig,false);

        LinearLayout configs=u.card(body);configs.setPadding(u.dp(20),u.dp(16),u.dp(20),u.dp(12));
        LinearLayout head=u.row();TextView ct=u.text("配置选择",18,u.text,true);head.addView(ct,new LinearLayout.LayoutParams(0,-2,1));
        TextView plus=u.text("＋",29,u.text,false);plus.setGravity(Gravity.CENTER);plus.setContentDescription("导入配置");plus.setBackground(u.touch(u.soft,22));plus.setOnClickListener(v->importYaml());head.addView(plus,new LinearLayout.LayoutParams(u.dp(44),u.dp(44)));configs.addView(head);
        u.gap(configs,8);separator(configs);
        LinearLayout configRow=u.row();configRow.setMinimumHeight(u.dp(78));configRow.setPadding(u.dp(4),u.dp(8),0,u.dp(8));
        LinearLayout configText=u.col();configValue=u.text(store.exists()?"config.yaml":"尚未选择配置",17,u.text,true);configText.addView(configValue);u.gap(configText,7);configText.addView(u.text(store.exists()?"当前启动配置 · 点击重新导入":"导入 Mihomo / Clash YAML",12,u.muted,false));configRow.addView(configText,new LinearLayout.LayoutParams(0,-2,1));
        TextView check=u.text(store.exists()?"✓":"＋",26,store.exists()?u.accent:u.muted,true);check.setGravity(Gravity.CENTER);configRow.addView(check,new LinearLayout.LayoutParams(u.dp(46),u.dp(46)));configRow.setOnClickListener(v->importYaml());configs.addView(configRow);

        LinearLayout runtime=u.card(body);runtime.setPadding(u.dp(20),u.dp(18),u.dp(20),u.dp(18));
        LinearLayout runtimeHead=u.row();LinearLayout labels=u.col();labels.addView(u.text("运行",18,u.text,true));u.gap(labels,7);stateValue=u.text("正在读取状态",12,u.muted,false);labels.addView(stateValue);runtimeHead.addView(labels,new LinearLayout.LayoutParams(0,-2,1));runtime.addView(runtimeHead);u.gap(runtime,16);
        LinearLayout actions=u.row();TextView stop=u.button("停止",false,this::stopSelectedMode);TextView start=u.button("启动",true,this::startSelectedMode);LinearLayout.LayoutParams half=new LinearLayout.LayoutParams(0,u.dp(52),1);half.rightMargin=u.dp(7);actions.addView(stop,half);LinearLayout.LayoutParams half2=new LinearLayout.LayoutParams(0,u.dp(52),1);half2.leftMargin=u.dp(7);actions.addView(start,half2);runtime.addView(actions);

        message=u.text("",12,u.accent,false);message.setPadding(u.dp(16),u.dp(12),u.dp(16),u.dp(12));message.setBackground(u.bg(u.soft,16));message.setVisibility(View.GONE);body.addView(message);
        setContentView(shell);
    }

    private TextView settingRow(LinearLayout parent,String label,String value,Runnable action,boolean chevron){
        LinearLayout row=u.row();row.setMinimumHeight(u.dp(66));row.setPadding(u.dp(2),u.dp(7),0,u.dp(7));row.setBackground(u.touch(android.graphics.Color.TRANSPARENT,15));
        TextView left=u.text(label,16,u.text,true);row.addView(left,new LinearLayout.LayoutParams(0,-2,1));
        TextView right=u.text(value,14,u.muted,false);right.setGravity(Gravity.RIGHT|Gravity.CENTER_VERTICAL);row.addView(right,new LinearLayout.LayoutParams(-2,-1));
        if(chevron){TextView c=u.text("⌄",20,u.muted,false);c.setGravity(Gravity.CENTER);row.addView(c,new LinearLayout.LayoutParams(u.dp(30),-1));}
        row.setOnClickListener(v->action.run());parent.addView(row,new LinearLayout.LayoutParams(-1,-2));return right;
    }
    private void separator(LinearLayout parent){View line=new View(this);line.setBackgroundColor(u.dark?0x24364b40:0x12000000);parent.addView(line,new LinearLayout.LayoutParams(-1,u.dp(1)));}

    private String core(){return prefs.getString(PREF_CORE,"mihomo");}
    private String mode(){return prefs.getString(PREF_MODE,"tproxy");}
    private String ipv6(){return prefs.getString(PREF_IPV6,"enable");}
    private String currentCoreLabel(){switch(core()){case"singbox":return"Sing-Box";case"xray":return"Xray";case"v2fly":return"V2Fly";case"hysteria":return"Hysteria";default:return"Mihomo";}}
    private String currentModeLabel(){switch(mode()){case"tun":return"TUN";case"ebpf":return"eBPF";case"redirect":return"Redirect";case"mixed":return"Mixed";case"enhance":return"Enhance";default:return"TPROXY";}}
    private String currentIpv6Label(){switch(ipv6()){case"bypass":return"IPv6 不进核心";case"disable":return"禁用系统 IPv6";default:return"启用 IPv6";}}

    private void chooseCore(){
        final String[] labels={"Mihomo","Sing-Box","Xray","V2Fly","Hysteria"};final String[] values={"mihomo","singbox","xray","v2fly","hysteria"};int checked=0;for(int i=0;i<values.length;i++)if(values[i].equals(core()))checked=i;
        new AlertDialog.Builder(this).setTitle("核心选择").setSingleChoiceItems(labels,checked,(d,w)->{prefs.edit().putString(PREF_CORE,values[w]).apply();coreValue.setText(labels[w]);if(!"mihomo".equals(values[w]))showMessage(labels[w]+" 当前只完成配置入口，尚未内置运行核心；启动时会明确拒绝，不会假报成功。");d.dismiss();}).show();
    }
    private void chooseMode(){
        final String[] labels={"TUN","TPROXY","eBPF","Redirect","Mixed","Enhance"};final String[] values={"tun","tproxy","ebpf","redirect","mixed","enhance"};int checked=1;for(int i=0;i<values.length;i++)if(values[i].equals(mode()))checked=i;
        new AlertDialog.Builder(this).setTitle("运行模式").setSingleChoiceItems(labels,checked,(d,w)->{prefs.edit().putString(PREF_MODE,values[w]).apply();modeValue.setText(labels[w]);if(!"tun".equals(values[w])&&!"tproxy".equals(values[w]))showMessage(labels[w]+" 后端尚未接通；当前真正可运行的是 TUN 和 Root TPROXY。");d.dismiss();refresh();}).show();
    }
    private void chooseIpv6(){final String[] labels={"启用 IPv6","IPv6 不进核心","禁用系统 IPv6"};final String[] values={"enable","bypass","disable"};int checked="bypass".equals(ipv6())?1:"disable".equals(ipv6())?2:0;new AlertDialog.Builder(this).setTitle("IPv6").setSingleChoiceItems(labels,checked,(d,w)->{prefs.edit().putString(PREF_IPV6,values[w]).apply();ipv6Value.setText(labels[w]);d.dismiss();}).show();}
    private void chooseOverwrite(){boolean now=prefs.getBoolean(PREF_OVERWRITE,true);prefs.edit().putBoolean(PREF_OVERWRITE,!now).apply();overwriteValue.setText(!now?"开启":"关闭");}

    private void showLaunchConfig(){String text="核心："+currentCoreLabel()+"\n运行模式："+currentModeLabel()+"\nIPv6："+currentIpv6Label()+"\n自动覆写："+(prefs.getBoolean(PREF_OVERWRITE,true)?"开启":"关闭");if(store.exists()&&"tproxy".equals(mode()))try{text+="\nTPROXY 端口："+root.detectPort(store.yaml());}catch(Exception e){text+="\nTPROXY 端口：未识别";}new AlertDialog.Builder(this).setTitle("启动配置").setMessage(text).setPositiveButton("确定",null).show();}
    private void importYaml(){startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE),PICK_YAML);}

    private void startSelectedMode(){
        if(busy)return;if(!"mihomo".equals(core())){showMessage(currentCoreLabel()+" 当前尚未内置运行核心，请先选 Mihomo。");return;}if(!store.exists()){showMessage("先导入配置文件");return;}
        if("tun".equals(mode())){startTun();return;}if(!"tproxy".equals(mode())){showMessage(currentModeLabel()+" 当前尚未接通运行后端。");return;}
        task(()->{String yaml=store.yaml();int port=root.detectPort(yaml);JSONObject preflight=root.preflight(yaml,ipv6());if(!preflight.optBoolean("ok"))throw new IOException(preflight.optString("message","TPROXY 预检失败"));JSONObject result=root.start(yaml,ipv6());prefs.edit().putBoolean("rootTproxyWanted",true).apply();return result.optString("message","Root TPROXY 已启动")+" · 端口 "+port;});
    }
    private void startTun(){Intent permission=VpnService.prepare(this);if(permission==null)launchTun();else startActivityForResult(permission,REQ_TUN);}
    private void launchTun(){try{startForegroundService(new Intent(this,MihomoVpnService.class).setAction("START"));showMessage("TUN 启动请求已发送");ui.postDelayed(this::refresh,350);}catch(RuntimeException e){showMessage("系统拒绝启动 TUN，请检查 VPN 权限或其他 VPN 是否占用");}}
    private void stopSelectedMode(){if("tun".equals(mode())){startService(new Intent(this,MihomoVpnService.class).setAction("STOP"));showMessage("TUN 停止请求已发送");ui.postDelayed(this::refresh,300);return;}task(()->{String result=root.stop().optString("message","Root TPROXY 已停止");prefs.edit().putBoolean("rootTproxyWanted",false).apply();return result;});}

    private void refresh(){
        if(destroyed)return;if(coreValue!=null)coreValue.setText(currentCoreLabel());if(modeValue!=null)modeValue.setText(currentModeLabel());if(ipv6Value!=null)ipv6Value.setText(currentIpv6Label());if(overwriteValue!=null)overwriteValue.setText(prefs.getBoolean(PREF_OVERWRITE,true)?"开启":"关闭");if(configValue!=null)configValue.setText(store.exists()?"config.yaml":"尚未选择配置");
        if("tun".equals(mode())){if(stateValue!=null)stateValue.setText(MihomoVpnService.running?"Mihomo · TUN 运行中":MihomoVpnService.engaged?MihomoVpnService.state:"Mihomo · TUN 未运行");return;}
        if("tproxy".equals(mode()))task(()->{JSONObject state=root.status();ui.post(()->{if(stateValue!=null)stateValue.setText(state.optBoolean("running",false)?"Mihomo · TPROXY 运行中 · PID "+state.optInt("pid"):"Mihomo · TPROXY 未运行");});return null;});else if(stateValue!=null)stateValue.setText(currentCoreLabel()+" · "+currentModeLabel()+" · 尚未接通");
    }

    private interface Work{String run()throws Exception;}
    private void task(Work work){if(busy||destroyed)return;busy=true;worker.execute(()->{String result=null;Exception error=null;try{result=work.run();}catch(Exception e){error=e;}final String text=result;final Exception failure=error;ui.post(()->{busy=false;if(destroyed)return;if(failure!=null)showMessage(safe(failure));else if(!TextUtils.isEmpty(text))showMessage(text);});});}
    private void showMessage(String text){if(message==null)return;message.setText(text);message.setVisibility(TextUtils.isEmpty(text)?View.GONE:View.VISIBLE);}
    private String safe(Exception e){String text=e.getMessage();if(TextUtils.isEmpty(text))return"代理操作失败，请刷新状态";return text.length()>500?text.substring(0,500)+"…":text;}

    @Override protected void onActivityResult(int requestCode,int resultCode,Intent data){super.onActivityResult(requestCode,resultCode,data);if(requestCode==REQ_TUN){if(resultCode==RESULT_OK)launchTun();else showMessage("VPN 未授权，未启动 TUN");return;}if(requestCode==PICK_YAML&&resultCode==RESULT_OK&&data!=null&&data.getData()!=null){task(()->{String yaml=ProxyStore.read(getContentResolver().openInputStream(data.getData()));String revision=store.revision();store.saveIfUnchanged(yaml,"",revision);ui.post(this::refresh);return"配置已导入";});}}
    @Override public void onResume(){super.onResume();if(prefs!=null)refresh();}
    @Override public void onDestroy(){destroyed=true;worker.shutdownNow();super.onDestroy();}
}
