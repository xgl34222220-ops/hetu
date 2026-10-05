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
    private TextView coreValue,modeValue,ipv6Value,currentConfigValue;
    private CompoundButton overwriteSwitch;
    private LinearLayout restartNotice;
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
        if(!u.dark)shell.setBackground(new android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,new int[]{0xffeeeffb,0xffeceefb}));
        ScrollView scroll=new ScrollView(this);scroll.setVerticalScrollBarEnabled(false);scroll.setClipToPadding(false);
        LinearLayout body=u.col();body.setPadding(u.dp(12),0,u.dp(12),u.dp(32));scroll.addView(body);shell.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));

        LinearLayout top=u.row();top.setMinimumHeight(u.dp(58));top.setGravity(Gravity.CENTER_VERTICAL);
        FrameLayout back=new FrameLayout(this);back.setContentDescription("返回");back.setBackground(u.touch(android.graphics.Color.TRANSPARENT,24));back.addView(new IconView(this,"back",u.text),new FrameLayout.LayoutParams(u.dp(22),u.dp(22),Gravity.CENTER));back.setOnClickListener(v->finish());top.addView(back,new LinearLayout.LayoutParams(u.dp(46),u.dp(48)));
        TextView title=u.text("基础代理配置",20,u.text,true);title.setGravity(Gravity.CENTER);
        top.addView(title,new LinearLayout.LayoutParams(0,-1,1));
        top.addView(new View(this),new LinearLayout.LayoutParams(u.dp(46),u.dp(48)));
        body.addView(top);u.gap(body,4);

        LinearLayout basic=settingsCard(body);basic.setPadding(u.dp(16),0,u.dp(16),0);
        coreValue=settingRow(basic,"cpu","代理核心","选择代理核心程序","",this::chooseCore);settingsDivider(basic);
        modeValue=settingRow(basic,"route","运行模式","选择代理运行模式","",this::chooseMode);settingsDivider(basic);
        ipv6Value=settingRow(basic,"globe","IPv6","启用或禁用 IPv6 支持","",this::chooseIpv6);settingsDivider(basic);
        overwriteSwitch=switchRow(basic,"refresh","自动覆写","启动时将必要的河图参数覆写到运行配置",prefs.getBoolean("proxyBaseAutoOverwrite",true),value->{
            prefs.edit().putBoolean("proxyBaseAutoOverwrite",value).apply();changed("proxyBaseAutoOverwrite");refresh();
        });

        LinearLayout startup=settingsCard(body);startup.setPadding(u.dp(16),0,u.dp(16),0);
        plainAction(startup,"file","查看启动配置","查看当前生成的运行配置文件",()->startActivity(new Intent(this,ProxyStartupConfigActivity.class)));

        LinearLayout cfg=settingsCard(body);cfg.setPadding(u.dp(16),0,u.dp(16),0);
        currentConfigValue=plainValueAction(cfg,"file","当前配置","当前选中的运行配置","",this::chooseConfig);

        restartNotice=u.row();restartNotice.setGravity(Gravity.CENTER_VERTICAL);restartNotice.setPadding(u.dp(14),u.dp(9),u.dp(10),u.dp(9));
        restartNotice.setBackground(u.bg(u.dark?0xff3b2e18:0xfffff3e6,16));
        restartNotice.addView(new IconView(this,"info",u.dark?0xffffd17a:0xffdc8a00),new LinearLayout.LayoutParams(u.dp(20),u.dp(20)));
        TextView notice=u.text("设置已修改，重启代理后生效",12.5f,u.dark?0xffffd998:0xff8e6200,true);
        LinearLayout.LayoutParams np=new LinearLayout.LayoutParams(0,-2,1);np.leftMargin=u.dp(9);restartNotice.addView(notice,np);
        TextView restart=u.text("立即重启",12.5f,u.accent,true);restart.setGravity(Gravity.CENTER);restart.setPadding(u.dp(10),u.dp(7),u.dp(10),u.dp(7));restart.setBackground(u.touch(android.graphics.Color.TRANSPARENT,12));restart.setOnClickListener(v->restartNow());restartNotice.addView(restart,new LinearLayout.LayoutParams(-2,-2));
        body.addView(restartNotice,new LinearLayout.LayoutParams(-1,-2));

        setContentView(shell);shell.requestApplyInsets();
    }

    private FrameLayout rowIcon(String kind){
        FrameLayout well=new FrameLayout(this);
        well.addView(new SettingsLineIcon(this,kind,u.text),new FrameLayout.LayoutParams(u.dp(26),u.dp(26),Gravity.CENTER));
        return well;
    }

    private TextView settingsSubtitle(String value){
        return settingsSubtitle(value,14);
    }

    private TextView settingsSubtitle(String value,float size){
        TextView text=u.text(value,size,u.muted,false);
        if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.P)text.setTypeface(android.graphics.Typeface.create(text.getTypeface(),600,false));
        else text.setFontVariationSettings("'wght' 600");
        return text;
    }

    private TextView settingRow(LinearLayout parent,String icon,String label,String subtitle,String value,Runnable action){
        LinearLayout row=u.row();row.setMinimumHeight(u.dp(76));row.setPadding(0,u.dp(3),0,u.dp(3));row.setGravity(Gravity.CENTER_VERTICAL);row.setBackground(u.touch(android.graphics.Color.TRANSPARENT,14));
        row.addView(rowIcon(icon),new LinearLayout.LayoutParams(u.dp(30),u.dp(30)));
        LinearLayout labels=u.col();labels.setPadding(u.dp(20),0,u.dp(8),0);labels.addView(u.text(label,18,u.text,true));labels.addView(settingsSubtitle(subtitle));
        row.addView(labels,new LinearLayout.LayoutParams(0,-2,1));
        TextView right=u.text(value,15,u.muted,false);right.setGravity(Gravity.RIGHT|Gravity.CENTER_VERTICAL);right.setMaxWidth(u.dp(135));right.setEllipsize(TextUtils.TruncateAt.END);right.setSingleLine(true);row.addView(right,new LinearLayout.LayoutParams(-2,-1));
        LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(u.dp(18),u.dp(18));cp.leftMargin=u.dp(6);row.addView(new SettingsLineIcon(this,"unfold",u.muted),cp);row.setOnClickListener(v->action.run());parent.addView(row,new LinearLayout.LayoutParams(-1,-2));return right;
    }

    private CompoundButton switchRow(LinearLayout parent,String icon,String label,String subtitle,boolean checked,java.util.function.Consumer<Boolean> listener){
        LinearLayout row=u.row();row.setMinimumHeight(u.dp(76));row.setGravity(Gravity.CENTER_VERTICAL);row.setPadding(0,u.dp(3),0,u.dp(3));
        row.addView(rowIcon(icon),new LinearLayout.LayoutParams(u.dp(30),u.dp(30)));
        LinearLayout labels=u.col();labels.setPadding(u.dp(20),0,u.dp(8),0);labels.addView(u.text(label,18,u.text,true));labels.addView(settingsSubtitle(subtitle,12.5f));
        row.addView(labels,new LinearLayout.LayoutParams(0,-2,1));
        CompoundButton sw=new SettingsToggle(this);sw.setContentDescription(label);sw.setChecked(checked);sw.setOnCheckedChangeListener((button,value)->listener.accept(value));row.addView(sw,new LinearLayout.LayoutParams(u.dp(50),u.dp(48)));row.setOnClickListener(v->sw.toggle());parent.addView(row,new LinearLayout.LayoutParams(-1,-2));return sw;
    }

    private void plainAction(LinearLayout parent,String icon,String title,String subtitle,Runnable action){
        LinearLayout row=u.row();row.setMinimumHeight(u.dp(76));row.setGravity(Gravity.CENTER_VERTICAL);row.setBackground(u.touch(android.graphics.Color.TRANSPARENT,14));
        row.addView(rowIcon(icon),new LinearLayout.LayoutParams(u.dp(30),u.dp(30)));
        LinearLayout labels=u.col();labels.setPadding(u.dp(20),0,u.dp(8),0);labels.addView(u.text(title,18,u.text,true));labels.addView(settingsSubtitle(subtitle));row.addView(labels,new LinearLayout.LayoutParams(0,-2,1));
        row.addView(new IconView(this,"chevron",u.muted),new LinearLayout.LayoutParams(u.dp(18),u.dp(18)));row.setOnClickListener(v->action.run());parent.addView(row,new LinearLayout.LayoutParams(-1,-2));
    }

    private TextView plainValueAction(LinearLayout parent,String icon,String title,String subtitle,String value,Runnable action){
        LinearLayout row=u.row();row.setMinimumHeight(u.dp(76));row.setGravity(Gravity.CENTER_VERTICAL);row.setBackground(u.touch(android.graphics.Color.TRANSPARENT,14));
        row.addView(rowIcon(icon),new LinearLayout.LayoutParams(u.dp(30),u.dp(30)));
        LinearLayout labels=u.col();labels.setPadding(u.dp(20),0,u.dp(8),0);labels.addView(u.text(title,18,u.text,true));
        TextView current=settingsSubtitle(value);current.setSingleLine(true);current.setEllipsize(TextUtils.TruncateAt.MIDDLE);labels.addView(current);
        row.addView(labels,new LinearLayout.LayoutParams(0,-2,1));
        row.addView(new IconView(this,"chevron",u.muted),new LinearLayout.LayoutParams(u.dp(18),u.dp(18)));row.setOnClickListener(v->action.run());parent.addView(row,new LinearLayout.LayoutParams(-1,-2));return current;
    }

    private ProxyRuntimeProfile profile(){return ProxyRuntimeProfile.load(prefs);}
    private String ipv6Label(ProxyRuntimeProfile.Ipv6 v){switch(v){case BYPASS:return"不进核心";case STRICT:return"严格防泄漏";case DISABLE:return"禁用";default:return"启用";}}

    private void chooseCore(){
        // Only the two implemented engines are advertised as runnable. Other core models
        // remain in the library and core manager; an unavailable engine is never selected.
        ProxyRuntimeProfile.Core[] values={ProxyRuntimeProfile.Core.MIHOMO,ProxyRuntimeProfile.Core.MIHOMO_SMART};
        String[] labels=new String[values.length],descriptions=new String[values.length];boolean[] enabled=new boolean[values.length];int selected=0;
        for(int i=0;i<values.length;i++){labels[i]=values[i].label;enabled[i]=values[i]==ProxyRuntimeProfile.Core.MIHOMO||cores.installed(values[i]);descriptions[i]=enabled[i]?"":"尚未安装，请先在核心管理下载";if(values[i]==profile().core)selected=i;}
        showChoice(coreValue,labels,descriptions,enabled,selected,174,index->{prefs.edit().putString("proxyBaseCore",values[index].id).apply();changed("proxyBaseCore");refresh();});
    }

    private void chooseMode(){
        ProxyRuntimeProfile.Mode[] values={ProxyRuntimeProfile.Mode.TUN,ProxyRuntimeProfile.Mode.TPROXY,ProxyRuntimeProfile.Mode.EBPF,ProxyRuntimeProfile.Mode.REDIRECT,ProxyRuntimeProfile.Mode.ENHANCE};
        String[] labels=new String[values.length],descriptions={"Root 下的 TUN 虚拟网卡","推荐。TCP/UDP 全接管，性能最好","eBPF 重定向到 TUN","仅 TCP，兼容性最好","TCP 走 Redirect，UDP 走 TPROXY"};boolean[] enabled=new boolean[values.length];int selected=0;
        for(int i=0;i<values.length;i++){labels[i]=values[i].label;enabled[i]=ProxyRuntimeProfile.capability(profile().core,values[i]).available;if(values[i]==profile().mode)selected=i;}
        showChoice(modeValue,labels,descriptions,enabled,selected,214,index->{prefs.edit().putString("proxyBaseMode",values[index].id).apply();changed("proxyBaseMode");refresh();});
    }

    private void chooseIpv6(){
        ProxyRuntimeProfile.Ipv6[] values=ProxyRuntimeProfile.Ipv6.values();String[] labels={"启用","不进核心","严格防泄漏","禁用系统 IPv6"};
        String[] descriptions={"IPv6 流量同样进入代理","IPv6 直连，不经过代理","仅用 IPv4，拦截 IPv6","关闭系统 IPv6 外联"};
        showChoice(ipv6Value,labels,descriptions,new boolean[]{true,true,true,true},profile().ipv6.ordinal(),214,index->{prefs.edit().putString("proxyBaseIpv6",values[index].id).apply();changed("proxyBaseIpv6");refresh();});
    }

    private void showChoice(View anchor,String[] labels,String[] descriptions,boolean[] enabled,int selected,int widthDp,java.util.function.IntConsumer pick){
        LinearLayout options=u.col();options.setPadding(u.dp(5),u.dp(5),u.dp(5),u.dp(5));options.setBackground(u.bg(u.dark?u.surface:0xfffbfaff,16));
        ScrollView viewport=new ScrollView(this);viewport.setVerticalScrollBarEnabled(false);viewport.addView(options);
        int width=Math.min(u.dp(widthDp),getResources().getDisplayMetrics().widthPixels-u.dp(24));
        PopupWindow popup=new PopupWindow(viewport,width,ViewGroup.LayoutParams.WRAP_CONTENT,true);
        popup.setBackgroundDrawable(u.bg(u.dark?u.surface:0xfffbfaff,16));popup.setElevation(u.dp(12));popup.setOutsideTouchable(true);
        for(int i=0;i<labels.length;i++){
            final int index=i;LinearLayout row=u.row();row.setMinimumHeight(u.dp(descriptions[i].isEmpty()?44:58));row.setPadding(u.dp(10),u.dp(8),u.dp(8),u.dp(8));row.setBackground(u.touch(i==selected?(u.dark?u.soft:0xffe9edff):android.graphics.Color.TRANSPARENT,10));row.setAlpha(enabled[i]?1f:.45f);
            LinearLayout lines=u.col();lines.addView(u.text(labels[i],14.5f,u.text,true));if(!descriptions[i].isEmpty()){u.gap(lines,3);lines.addView(u.text(descriptions[i],10.5f,u.muted,false));}row.addView(lines,new LinearLayout.LayoutParams(0,-2,1));
            if(i==selected)row.addView(new IconView(this,"check",u.dark?u.accent:0xff005cff),new LinearLayout.LayoutParams(u.dp(19),u.dp(19)));
            row.setEnabled(enabled[i]);row.setOnClickListener(v->{popup.dismiss();pick.accept(index);});options.addView(row,new LinearLayout.LayoutParams(-1,-2));
            if(i<labels.length-1)settingsDivider(options);
        }
        View row=(View)anchor.getParent();
        popup.showAsDropDown(row,row.getWidth()-width, -u.dp(8),Gravity.START);
        // Concept pages 15-17 use a compact anchored picker with a soft page scrim.
        // PopupWindow does not dim its host by default, so apply the same 26% scrim used
        // by Compose anchored choice menus without turning this into a full-screen dialog.
        try{
            View container=(View)popup.getContentView().getParent();
            WindowManager wm=(WindowManager)getSystemService(WINDOW_SERVICE);
            WindowManager.LayoutParams lp=(WindowManager.LayoutParams)container.getLayoutParams();
            lp.flags|=WindowManager.LayoutParams.FLAG_DIM_BEHIND;
            lp.dimAmount=0f;
            wm.updateViewLayout(container,lp);
        }catch(Exception ignored){}
    }

    private LinearLayout settingsCard(LinearLayout parent){
        LinearLayout card=u.col();card.setBackground(u.bg(u.dark?u.surface:0xfff8f7fd,20));
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.bottomMargin=u.dp(12);parent.addView(card,lp);return card;
    }
    private void settingsDivider(LinearLayout parent){View line=new View(this);line.setBackgroundColor(u.dark?u.divider:0xffeeedf8);parent.addView(line,new LinearLayout.LayoutParams(-1,u.dp(.5f)));}

    private final class SettingsToggle extends CompoundButton {
        private final android.graphics.Paint paint=new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        SettingsToggle(Context context){super(context);setButtonDrawable((android.graphics.drawable.Drawable)null);setBackground(null);setFocusable(true);}
        @Override protected void onDraw(android.graphics.Canvas canvas){
            float top=(getHeight()-u.dp(28))/2f;paint.setColor(isChecked()?(u.dark?u.accent:0xff005cff):(u.dark?u.soft:0xffc8c9d9));canvas.drawRoundRect(0,top,getWidth(),top+u.dp(28),u.dp(14),u.dp(14),paint);
            paint.setColor(isChecked()?android.graphics.Color.WHITE:(u.dark?u.muted:0xff858798));canvas.drawCircle(isChecked()?getWidth()-u.dp(14):u.dp(14),top+u.dp(14),u.dp(10),paint);
        }
        @Override public CharSequence getAccessibilityClassName(){return "android.widget.Switch";}
        @Override public void setChecked(boolean checked){super.setChecked(checked);invalidate();}
    }

    private static final class SettingsLineIcon extends View {
        private final String kind;private final android.graphics.Paint p=new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        SettingsLineIcon(Context context,String kind,int color){super(context);this.kind=kind;p.setColor(color);p.setStyle(android.graphics.Paint.Style.STROKE);p.setStrokeWidth(1.8f);p.setStrokeCap(android.graphics.Paint.Cap.ROUND);p.setStrokeJoin(android.graphics.Paint.Join.ROUND);setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);}
        @Override protected void onDraw(android.graphics.Canvas c){super.onDraw(c);c.save();c.scale(getWidth()/24f,getHeight()/24f);
            if(kind.equals("cpu")){c.drawRoundRect(5,5,19,19,2,2,p);for(int n=8;n<=16;n+=4){c.drawLine(n,2,n,5,p);c.drawLine(n,19,n,22,p);c.drawLine(2,n,5,n,p);c.drawLine(19,n,22,n,p);}}
            else if(kind.equals("file")){android.graphics.Path path=new android.graphics.Path();path.moveTo(6,2);path.lineTo(14,2);path.lineTo(20,8);path.lineTo(20,22);path.lineTo(6,22);path.close();c.drawPath(path,p);c.drawLine(14,2,14,8,p);c.drawLine(14,8,20,8,p);c.drawLine(10,12,16,12,p);c.drawLine(10,16,16,16,p);}
            else if(kind.equals("route")){c.drawCircle(6,18,3,p);c.drawCircle(18,6,3,p);android.graphics.Path path=new android.graphics.Path();path.moveTo(9,18);path.lineTo(15,18);path.cubicTo(21,18,21,12,15,12);path.lineTo(9,12);path.cubicTo(3,12,3,6,9,6);path.lineTo(15,6);c.drawPath(path,p);}
            else if(kind.equals("globe")){c.drawCircle(12,12,10,p);c.drawOval(8,2,16,22,p);c.drawLine(2,12,22,12,p);}
            else if(kind.equals("refresh")){c.drawArc(3,3,21,21,195,135,false,p);c.drawArc(3,3,21,21,15,135,false,p);c.drawLine(21,3,21,9,p);c.drawLine(15,9,21,9,p);c.drawLine(3,21,3,15,p);c.drawLine(3,15,9,15,p);}
            else if(kind.equals("unfold")){c.drawLine(7,8,12,3,p);c.drawLine(12,3,17,8,p);c.drawLine(7,16,12,21,p);c.drawLine(12,21,17,16,p);}
            c.restore();
        }
    }

    private void changed(String key){ProxyRuntimeSettings.markDirty(prefs,key);toast("已保存；代理运行中时重启后生效");}

    private void chooseConfig(){
        ProxyRuntimeProfile.Core core=profile().core;
        List<ProxyConfigLibrary.Entry> list=configs.list(core);
        if(list.isEmpty()){importConfig();return;}
        ProxyConfigLibrary.Entry selected=configs.selected(core);
        String[] names=new String[list.size()+1];
        int checked=-1;
        for(int i=0;i<list.size();i++){names[i]=list.get(i).name;if(selected!=null&&selected.name.equals(list.get(i).name))checked=i;}
        names[list.size()]="＋ 导入配置";
        new AlertDialog.Builder(this).setTitle("当前配置").setSingleChoiceItems(names,checked,(d,w)->{
            d.dismiss();
            if(w==list.size()){importConfig();return;}
            try{configs.select(core,list.get(w).name);changed("proxyBaseCore");refresh();}catch(Exception e){toast(safe(e));}
        }).setNegativeButton("取消",null).show();
    }

    private void restartNow(){
        task(()->{
            RootProxyManager manager=new RootProxyManager(this);
            org.json.JSONObject state=manager.status();
            if(!state.optBoolean("running",false))return"代理未运行，设置将在下次启动生效";
            ProxyRuntimeSettings.beginApply(prefs);
            try{
                manager.stop();
                manager.start(profile());
                ui.post(this::refresh);
                return"已重启，设置已生效";
            }catch(Exception e){
                ProxyRuntimeSettings.recordFailure(prefs,e);
                throw e;
            }
        });
    }


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
        overwriteSwitch.setOnCheckedChangeListener(null);overwriteSwitch.setChecked(p.autoOverwrite);overwriteSwitch.setOnCheckedChangeListener((button,value)->{prefs.edit().putBoolean("proxyBaseAutoOverwrite",value).apply();changed("proxyBaseAutoOverwrite");refresh();});
        ProxyConfigLibrary.Entry selected=configs.selected(p.core);if(currentConfigValue!=null)currentConfigValue.setText(selected==null?"未选择":selected.name);
        if(restartNotice!=null)restartNotice.setVisibility(prefs.getBoolean(ProxyRuntimeSettings.DIRTY_KEY,false)?View.VISIBLE:View.GONE);
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
