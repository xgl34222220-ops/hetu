package io.github.xgl34222220.hetu;

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
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** Reference-style basic proxy configuration. Only basic settings live here. */
public final class RootTproxyActivity extends Activity {
    private static final int PICK_CONFIG=701, EXPORT_CONFIG=702;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final Handler ui=new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private ProxyUi u;
    private ProxyCoreStore cores;
    private ProxyConfigLibrary configs;
    private TextView coreValue,modeValue,ipv6Value;
    private Switch overwriteSwitch;
    private LinearLayout configRows;
    private ProxyConfigLibrary.Entry pendingExport;
    private boolean destroyed,busy;

    private interface Work{String run()throws Exception;}

    @Override public void onCreate(Bundle state){
        prefs=getSharedPreferences("hetu",MODE_PRIVATE);
        setTheme(ProxyUi.isDark(this)?R.style.AppThemeDark:R.style.AppTheme);
        super.onCreate(state);
        u=new ProxyUi(this);cores=new ProxyCoreStore(this);configs=new ProxyConfigLibrary(this);
        build();refresh();
    }

    private void build(){
        LinearLayout shell=u.col();u.window(shell);
        ScrollView scroll=new ScrollView(this);scroll.setVerticalScrollBarEnabled(false);scroll.setClipToPadding(false);
        LinearLayout body=u.col();body.setPadding(u.dp(14),u.dp(18),u.dp(14),u.dp(40));scroll.addView(body);shell.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));

        LinearLayout top=u.row();top.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(u.icon("back","返回",this::finish),new LinearLayout.LayoutParams(u.dp(46),u.dp(46)));
        TextView title=u.text("基础代理配置",31,u.text,true);LinearLayout.LayoutParams tp=new LinearLayout.LayoutParams(0,-2,1);tp.leftMargin=u.dp(8);top.addView(title,tp);body.addView(top);u.gap(body,18);

        LinearLayout basic=u.card(body);basic.setPadding(u.dp(18),0,u.dp(18),0);
        coreValue=settingRow(basic,"核心选择","",this::chooseCore);u.separator(basic);
        modeValue=settingRow(basic,"运行模式","",this::chooseMode);u.separator(basic);
        ipv6Value=settingRow(basic,"IPv6","",this::chooseIpv6);u.separator(basic);
        overwriteSwitch=switchRow(basic,"自动覆写",prefs.getBoolean("proxyBaseAutoOverwrite",true),value->{
            prefs.edit().putBoolean("proxyBaseAutoOverwrite",value).apply();changed("proxyBaseAutoOverwrite");
        });

        u.gap(body,12);
        LinearLayout startup=u.card(body);startup.setPadding(u.dp(18),0,u.dp(18),0);
        plainAction(startup,"查看启动配置",()->startActivity(new Intent(this,ProxyStartupConfigActivity.class)));

        // Configuration objects are managed once, in Tools > Configurations.
        // Keep this page focused on runtime settings; old handlers remain for compatibility.

        setContentView(shell);shell.requestApplyInsets();
    }

    private TextView settingRow(LinearLayout parent,String label,String value,Runnable action){
        LinearLayout row=u.row();row.setMinimumHeight(u.dp(66));row.setPadding(0,u.dp(4),0,u.dp(4));row.setGravity(Gravity.CENTER_VERTICAL);row.setBackground(u.touch(android.graphics.Color.TRANSPARENT,14));
        row.addView(u.text(label,16.5f,u.text,true),new LinearLayout.LayoutParams(0,-2,1));
        TextView right=u.text(value,14.5f,u.muted,false);right.setGravity(Gravity.RIGHT|Gravity.CENTER_VERTICAL);right.setMaxWidth(u.dp(210));right.setEllipsize(TextUtils.TruncateAt.END);right.setSingleLine(true);row.addView(right,new LinearLayout.LayoutParams(-2,-1));
        LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(u.dp(18),u.dp(18));cp.leftMargin=u.dp(7);row.addView(new IconView(this,"chevron",u.muted),cp);row.setOnClickListener(v->action.run());parent.addView(row,new LinearLayout.LayoutParams(-1,-2));return right;
    }

    private Switch switchRow(LinearLayout parent,String label,boolean checked,java.util.function.Consumer<Boolean> listener){
        LinearLayout row=u.row();row.setMinimumHeight(u.dp(66));row.setGravity(Gravity.CENTER_VERTICAL);row.setPadding(0,u.dp(3),0,u.dp(3));
        row.addView(u.text(label,16.5f,u.text,true),new LinearLayout.LayoutParams(0,-2,1));
        Switch sw=new Switch(this);sw.setChecked(checked);sw.setOnCheckedChangeListener((button,value)->listener.accept(value));row.addView(sw,new LinearLayout.LayoutParams(-2,-2));parent.addView(row,new LinearLayout.LayoutParams(-1,-2));return sw;
    }

    private void plainAction(LinearLayout parent,String title,Runnable action){
        LinearLayout row=u.row();row.setMinimumHeight(u.dp(66));row.setGravity(Gravity.CENTER_VERTICAL);row.setBackground(u.touch(android.graphics.Color.TRANSPARENT,14));
        row.addView(u.text(title,16.5f,u.text,true),new LinearLayout.LayoutParams(0,-2,1));row.addView(new IconView(this,"chevron",u.muted),new LinearLayout.LayoutParams(u.dp(18),u.dp(18)));row.setOnClickListener(v->action.run());parent.addView(row,new LinearLayout.LayoutParams(-1,-2));
    }

    private ProxyRuntimeProfile profile(){return ProxyRuntimeProfile.load(prefs);}
    private String ipv6Label(ProxyRuntimeProfile.Ipv6 v){switch(v){case BYPASS:return"IPv6 不进核心";case STRICT:return"严格 IPv4 防泄漏";case DISABLE:return"禁用系统 IPv6";default:return"启用 IPv6";}}

    private void chooseCore(){
        ProxyRuntimeProfile.Core[] values=ProxyRuntimeProfile.Core.values();String[] labels=new String[values.length];int selected=0;
        for(int i=0;i<values.length;i++){labels[i]=values[i].label;if(values[i]==profile().core)selected=i;}
        new AlertDialog.Builder(this).setTitle("核心选择").setSingleChoiceItems(labels,selected,(d,w)->{
            ProxyRuntimeProfile.Core chosen=values[w];
            try{ProxyConfigLibrary.selectCore(prefs,chosen.id);}catch(IOException busy){toast(safe(busy));return;}
            d.dismiss();changed("proxyBaseCore");refresh();
            if(chosen!=ProxyRuntimeProfile.Core.MIHOMO&&!cores.installed(chosen))toast(chosen.label+" 尚未安装或后端未接入");
        }).setNegativeButton("取消",null).show();
    }

    private void chooseMode(){
        ProxyRuntimeProfile.Core core=profile().core;ProxyRuntimeProfile.Mode[] values=ProxyRuntimeProfile.Mode.values();String[] labels=new String[values.length];int selected=0;
        for(int i=0;i<values.length;i++){ProxyRuntimeProfile.Capability c=ProxyRuntimeProfile.capability(core,values[i]);labels[i]=values[i].label+(c.available?"":"  ·  不可用");if(values[i]==profile().mode)selected=i;}
        new AlertDialog.Builder(this).setTitle("运行模式").setSingleChoiceItems(labels,selected,(d,w)->{
            ProxyRuntimeProfile.Capability c=ProxyRuntimeProfile.capability(core,values[w]);d.dismiss();if(!c.available){toast(c.reason);return;}prefs.edit().putString("proxyBaseMode",values[w].id).apply();changed("proxyBaseMode");refresh();
        }).setNegativeButton("取消",null).show();
    }

    private void chooseIpv6(){
        ProxyRuntimeProfile.Ipv6[] values=ProxyRuntimeProfile.Ipv6.values();String[] labels={"启用 IPv6","IPv6 不进核心","严格 IPv4 防泄漏","禁用系统 IPv6"};int selected=profile().ipv6.ordinal();
        new AlertDialog.Builder(this).setTitle("IPv6").setSingleChoiceItems(labels,selected,(d,w)->{prefs.edit().putString("proxyBaseIpv6",values[w].id).apply();d.dismiss();changed("proxyBaseIpv6");refresh();}).setNegativeButton("取消",null).show();
    }

    private void changed(String key){ProxyRuntimeSettings.markDirty(prefs,key);toast("已保存；代理运行中时重启后生效");}

    private void renderConfigs(){
        if(configRows==null)return;configRows.removeAllViews();ProxyRuntimeProfile.Core core=profile().core;List<ProxyConfigLibrary.Entry> list=configs.list(core);ProxyConfigLibrary.Entry selected=configs.selected(core);
        if(list.isEmpty()){TextView empty=u.text("尚无配置",15,u.muted,false);empty.setGravity(Gravity.CENTER_VERTICAL);empty.setPadding(0,u.dp(14),0,u.dp(14));configRows.addView(empty,new LinearLayout.LayoutParams(-1,u.dp(58)));return;}
        for(int i=0;i<list.size();i++){
            ProxyConfigLibrary.Entry entry=list.get(i);boolean checked=selected!=null&&selected.name.equals(entry.name);LinearLayout row=u.row();row.setMinimumHeight(u.dp(62));row.setGravity(Gravity.CENTER_VERTICAL);row.setPadding(0,u.dp(4),0,u.dp(4));row.setBackground(u.touch(android.graphics.Color.TRANSPARENT,14));
            TextView name=u.text(entry.name,15.5f,u.text,false);name.setSingleLine(true);name.setEllipsize(TextUtils.TruncateAt.MIDDLE);row.addView(name,new LinearLayout.LayoutParams(0,-2,1));
            if(checked)row.addView(new IconView(this,"check",u.accent),new LinearLayout.LayoutParams(u.dp(24),u.dp(24)));else row.addView(new View(this),new LinearLayout.LayoutParams(u.dp(24),u.dp(24)));
            row.setOnClickListener(v->{try{configs.select(core,entry.name);refresh();changed("proxyBaseCore");}catch(Exception e){toast(safe(e));}});
            row.setOnLongClickListener(v->{showConfigMenu(entry);return true;});
            configRows.addView(row,new LinearLayout.LayoutParams(-1,-2));if(i<list.size()-1)u.separator(configRows);
        }
    }

    private void showConfigMenu(ProxyConfigLibrary.Entry entry){
        String[] actions={"编辑","导出","重命名","删除"};
        new AlertDialog.Builder(this).setItems(actions,(d,which)->{
            switch(which){
                case 0: editConfig(entry); break;
                case 1: exportConfig(entry); break;
                case 2: renameConfig(entry); break;
                case 3: deleteConfig(entry); break;
            }
        }).show();
    }

    private void editConfig(ProxyConfigLibrary.Entry entry){
        try{configs.select(entry.core,entry.name);refresh();startActivity(new Intent(this,ProxyConfigEditorActivity.class));}
        catch(Exception e){toast(safe(e));}
    }

    private void exportConfig(ProxyConfigLibrary.Entry entry){
        pendingExport=entry;
        Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("text/yaml").addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE,entry.name);
        startActivityForResult(i,EXPORT_CONFIG);
    }

    private void renameConfig(ProxyConfigLibrary.Entry entry){
        EditText input=new EditText(this);input.setSingleLine(true);input.setText(entry.name);input.setSelection(0,Math.max(0,entry.name.lastIndexOf('.')));
        FrameLayout wrap=new FrameLayout(this);int m=u.dp(20);wrap.setPadding(m,u.dp(4),m,0);wrap.addView(input,new FrameLayout.LayoutParams(-1,-2));
        new AlertDialog.Builder(this).setTitle("重命名").setView(wrap).setPositiveButton("保存",(d,w)->task(()->{ProxyConfigLibrary.Entry renamed=configs.rename(entry,input.getText().toString().trim());ui.post(this::refresh);return"已重命名："+renamed.name;})).setNegativeButton("取消",null).show();
    }

    private void deleteConfig(ProxyConfigLibrary.Entry entry){
        new AlertDialog.Builder(this).setTitle("删除配置？").setMessage(entry.name).setPositiveButton("删除",(d,w)->task(()->{configs.delete(entry);ui.post(this::refresh);return"已删除："+entry.name;})).setNegativeButton("取消",null).show();
    }

    private void importConfig(){Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE);startActivityForResult(i,PICK_CONFIG);}

    private void refresh(){
        if(destroyed)return;ProxyRuntimeProfile p=profile();coreValue.setText(p.core.label);modeValue.setText(p.mode.label);ipv6Value.setText(ipv6Label(p.ipv6));
        overwriteSwitch.setOnCheckedChangeListener(null);overwriteSwitch.setChecked(p.autoOverwrite);overwriteSwitch.setOnCheckedChangeListener((button,value)->{prefs.edit().putBoolean("proxyBaseAutoOverwrite",value).apply();changed("proxyBaseAutoOverwrite");});renderConfigs();
    }

    private void task(Work work){if(busy||destroyed)return;busy=true;worker.execute(()->{String result=null;Throwable failure=null;try{result=work.run();}catch(Throwable e){failure=e;}String text=result;Throwable error=failure;ui.post(()->{busy=false;if(destroyed)return;if(error!=null)toast(safe(error));else if(!TextUtils.isEmpty(text))toast(text);});});}
    private void toast(String text){Toast.makeText(this,text,Toast.LENGTH_SHORT).show();}
    private String safe(Throwable e){String s=e.getMessage();if(TextUtils.isEmpty(s))s=e.getClass().getSimpleName();s=s.replace('\n',' ').replace('\r',' ');return s.length()>260?s.substring(0,260)+"…":s;}
    private String displayName(Uri uri){String name=null;try(Cursor c=getContentResolver().query(uri,new String[]{OpenableColumns.DISPLAY_NAME},null,null,null)){if(c!=null&&c.moveToFirst())name=c.getString(0);}catch(Exception ignored){}if(TextUtils.isEmpty(name))name=uri.getLastPathSegment();return TextUtils.isEmpty(name)?"config.yaml":name;}

    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);if(result!=RESULT_OK||data==null||data.getData()==null)return;Uri uri=data.getData();
        if(request==PICK_CONFIG){String name=displayName(uri);ProxyRuntimeProfile.Core core=profile().core;task(()->{try(InputStream in=getContentResolver().openInputStream(uri)){configs.importConfig(core,name,in);}ui.post(this::refresh);return"已导入："+name;});return;}
        if(request==EXPORT_CONFIG&&pendingExport!=null){ProxyConfigLibrary.Entry entry=pendingExport;pendingExport=null;task(()->{String text=configs.read(entry);try(OutputStream out=getContentResolver().openOutputStream(uri)){if(out==null)throw new IOException("无法打开导出文件");out.write(text.getBytes(StandardCharsets.UTF_8));out.flush();}return"已导出："+entry.name;});}
    }
    @Override protected void onResume(){super.onResume();if(prefs!=null)refresh();}
    @Override public void onDestroy(){destroyed=true;worker.shutdownNow();super.onDestroy();}
}
