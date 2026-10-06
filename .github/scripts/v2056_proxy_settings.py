from pathlib import Path
import re

root = Path(".")

build = root / "android-app/app/build.gradle.kts"
s = build.read_text()
assert 'versionCode = 2055' in s and 'versionName = "0.10.5-v20"' in s
s = s.replace('versionCode = 2055', 'versionCode = 2056', 1)
s = s.replace('versionName = "0.10.5-v20"', 'versionName = "0.10.6-v20"', 1)
build.write_text(s)

p = root / "android-app/app/src/main/java/io/github/xgl34222220/hetu/RootTproxyActivity.java"
s = p.read_text()
s = s.replace(
    "    private TextView coreValue,modeValue,ipv6Value;\n    private Switch overwriteSwitch;\n",
    "    private TextView coreValue,modeValue,ipv6Value,currentConfigValue;\n    private Switch overwriteSwitch;\n    private LinearLayout restartNotice;\n",
    1,
)

start = s.index("    private void build(){")
end = s.index("\n    private ProxyRuntimeProfile profile()", start)
new_block = r'''    private void build(){
        LinearLayout shell=u.col();u.window(shell);
        ScrollView scroll=new ScrollView(this);scroll.setVerticalScrollBarEnabled(false);scroll.setClipToPadding(false);
        LinearLayout body=u.col();body.setPadding(u.dp(14),u.dp(4),u.dp(14),u.dp(40));scroll.addView(body);shell.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));

        LinearLayout top=u.row();top.setMinimumHeight(u.dp(54));top.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(u.icon("back","返回",this::finish),new LinearLayout.LayoutParams(u.dp(46),u.dp(46)));
        TextView title=u.text("基础代理配置",21,u.text,true);title.setGravity(Gravity.CENTER);
        top.addView(title,new LinearLayout.LayoutParams(0,-1,1));
        TextView sample=u.text("示例数据",11.5f,u.muted,false);sample.setGravity(Gravity.CENTER);
        top.addView(sample,new LinearLayout.LayoutParams(u.dp(70),-1));
        body.addView(top);u.gap(body,7);

        LinearLayout basic=u.card(body);basic.setPadding(u.dp(16),0,u.dp(16),0);
        coreValue=settingRow(basic,"server","代理核心","选择代理核心程序","",this::chooseCore);u.separator(basic);
        modeValue=settingRow(basic,"activity","运行模式","选择代理运行模式","",this::chooseMode);u.separator(basic);
        ipv6Value=settingRow(basic,"globe","IPv6","启用或禁用 IPv6 支持","",this::chooseIpv6);u.separator(basic);
        overwriteSwitch=switchRow(basic,"refresh","自动覆写","启动时将必要的设置参数覆写到运行配置",prefs.getBoolean("proxyBaseAutoOverwrite",true),value->{
            prefs.edit().putBoolean("proxyBaseAutoOverwrite",value).apply();changed("proxyBaseAutoOverwrite");refresh();
        });

        LinearLayout startup=u.card(body);startup.setPadding(u.dp(16),0,u.dp(16),0);
        plainAction(startup,"folder","查看启动配置","查看当前生成的运行配置文件",()->startActivity(new Intent(this,ProxyStartupConfigActivity.class)));

        LinearLayout cfg=u.card(body);cfg.setPadding(u.dp(16),0,u.dp(16),0);
        currentConfigValue=plainValueAction(cfg,"folder","当前配置","当前选中的运行配置","",this::chooseConfig);

        restartNotice=u.row();restartNotice.setGravity(Gravity.CENTER_VERTICAL);restartNotice.setPadding(u.dp(14),u.dp(9),u.dp(10),u.dp(9));
        restartNotice.setBackground(u.bg(u.dark?0xff3b2e18:0xfffff1d4,14));
        restartNotice.addView(new IconView(this,"info",u.dark?0xffffd17a:0xffdc8a00),new LinearLayout.LayoutParams(u.dp(20),u.dp(20)));
        TextView notice=u.text("设置已修改，重启代理后生效",12.5f,u.dark?0xffffd998:0xff8e6200,true);
        LinearLayout.LayoutParams np=new LinearLayout.LayoutParams(0,-2,1);np.leftMargin=u.dp(9);restartNotice.addView(notice,np);
        TextView restart=u.text("立即重启",12.5f,u.accent,true);restart.setGravity(Gravity.CENTER);restart.setPadding(u.dp(10),u.dp(7),u.dp(10),u.dp(7));restart.setBackground(u.touch(android.graphics.Color.TRANSPARENT,12));restart.setOnClickListener(v->restartNow());restartNotice.addView(restart,new LinearLayout.LayoutParams(-2,-2));
        body.addView(restartNotice,new LinearLayout.LayoutParams(-1,-2));

        setContentView(shell);shell.requestApplyInsets();
    }

    private FrameLayout rowIcon(String kind){
        FrameLayout well=new FrameLayout(this);
        well.setBackground(u.bg(u.soft,13));
        well.addView(new IconView(this,kind,u.text),new FrameLayout.LayoutParams(u.dp(21),u.dp(21),Gravity.CENTER));
        return well;
    }

    private TextView settingRow(LinearLayout parent,String icon,String label,String subtitle,String value,Runnable action){
        LinearLayout row=u.row();row.setMinimumHeight(u.dp(66));row.setPadding(0,u.dp(3),0,u.dp(3));row.setGravity(Gravity.CENTER_VERTICAL);row.setBackground(u.touch(android.graphics.Color.TRANSPARENT,14));
        row.addView(rowIcon(icon),new LinearLayout.LayoutParams(u.dp(40),u.dp(40)));
        LinearLayout labels=u.col();labels.setPadding(u.dp(11),0,u.dp(8),0);labels.addView(u.text(label,16,u.text,true));labels.addView(u.text(subtitle,11.8f,u.muted,false));
        row.addView(labels,new LinearLayout.LayoutParams(0,-2,1));
        TextView right=u.text(value,14.5f,u.muted,true);right.setGravity(Gravity.RIGHT|Gravity.CENTER_VERTICAL);right.setMaxWidth(u.dp(135));right.setEllipsize(TextUtils.TruncateAt.END);right.setSingleLine(true);row.addView(right,new LinearLayout.LayoutParams(-2,-1));
        LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(u.dp(18),u.dp(18));cp.leftMargin=u.dp(6);row.addView(new IconView(this,"chevron",u.muted),cp);row.setOnClickListener(v->action.run());parent.addView(row,new LinearLayout.LayoutParams(-1,-2));return right;
    }

    private Switch switchRow(LinearLayout parent,String icon,String label,String subtitle,boolean checked,java.util.function.Consumer<Boolean> listener){
        LinearLayout row=u.row();row.setMinimumHeight(u.dp(66));row.setGravity(Gravity.CENTER_VERTICAL);row.setPadding(0,u.dp(3),0,u.dp(3));
        row.addView(rowIcon(icon),new LinearLayout.LayoutParams(u.dp(40),u.dp(40)));
        LinearLayout labels=u.col();labels.setPadding(u.dp(11),0,u.dp(8),0);labels.addView(u.text(label,16,u.text,true));labels.addView(u.text(subtitle,11.8f,u.muted,false));
        row.addView(labels,new LinearLayout.LayoutParams(0,-2,1));
        Switch sw=new Switch(this);sw.setChecked(checked);sw.setOnCheckedChangeListener((button,value)->listener.accept(value));row.addView(sw,new LinearLayout.LayoutParams(-2,-2));parent.addView(row,new LinearLayout.LayoutParams(-1,-2));return sw;
    }

    private void plainAction(LinearLayout parent,String icon,String title,String subtitle,Runnable action){
        LinearLayout row=u.row();row.setMinimumHeight(u.dp(66));row.setGravity(Gravity.CENTER_VERTICAL);row.setBackground(u.touch(android.graphics.Color.TRANSPARENT,14));
        row.addView(rowIcon(icon),new LinearLayout.LayoutParams(u.dp(40),u.dp(40)));
        LinearLayout labels=u.col();labels.setPadding(u.dp(11),0,u.dp(8),0);labels.addView(u.text(title,16,u.text,true));labels.addView(u.text(subtitle,11.8f,u.muted,false));row.addView(labels,new LinearLayout.LayoutParams(0,-2,1));
        row.addView(new IconView(this,"chevron",u.muted),new LinearLayout.LayoutParams(u.dp(18),u.dp(18)));row.setOnClickListener(v->action.run());parent.addView(row,new LinearLayout.LayoutParams(-1,-2));
    }

    private TextView plainValueAction(LinearLayout parent,String icon,String title,String subtitle,String value,Runnable action){
        LinearLayout row=u.row();row.setMinimumHeight(u.dp(66));row.setGravity(Gravity.CENTER_VERTICAL);row.setBackground(u.touch(android.graphics.Color.TRANSPARENT,14));
        row.addView(rowIcon(icon),new LinearLayout.LayoutParams(u.dp(40),u.dp(40)));
        LinearLayout labels=u.col();labels.setPadding(u.dp(11),0,u.dp(8),0);labels.addView(u.text(title,16,u.text,true));TextView sub=u.text(subtitle,11.8f,u.muted,false);labels.addView(sub);row.addView(labels,new LinearLayout.LayoutParams(0,-2,1));
        TextView right=u.text(value,13.5f,u.muted,false);right.setSingleLine(true);right.setEllipsize(TextUtils.TruncateAt.MIDDLE);right.setMaxWidth(u.dp(120));row.addView(right,new LinearLayout.LayoutParams(-2,-1));
        LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(u.dp(18),u.dp(18));cp.leftMargin=u.dp(6);row.addView(new IconView(this,"chevron",u.muted),cp);row.setOnClickListener(v->action.run());parent.addView(row,new LinearLayout.LayoutParams(-1,-2));return right;
    }
'''
s = s[:start] + new_block + s[end:]

s = s.replace(
    '    private String ipv6Label(ProxyRuntimeProfile.Ipv6 v){switch(v){case BYPASS:return"IPv6 不进核心";case STRICT:return"严格 IPv4 防泄漏";case DISABLE:return"禁用系统 IPv6";default:return"启用 IPv6";}}',
    '    private String ipv6Label(ProxyRuntimeProfile.Ipv6 v){switch(v){case BYPASS:return"不进核心";case STRICT:return"严格防泄漏";case DISABLE:return"禁用";default:return"启用";}}',
    1,
)
s = s.replace(
    'String[] labels={"启用 IPv6","IPv6 不进核心","严格 IPv4 防泄漏","禁用系统 IPv6"};',
    'String[] labels={"启用","不进核心","严格防泄漏","禁用系统 IPv6"};',
    1,
)

insert_at = s.index("\n    private void renderConfigs(){")
extra = r'''
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

'''
s = s[:insert_at] + extra + s[insert_at:]

old_refresh = '''    private void refresh(){
        if(destroyed)return;ProxyRuntimeProfile p=profile();coreValue.setText(p.core.label);modeValue.setText(p.mode.label);ipv6Value.setText(ipv6Label(p.ipv6));
        overwriteSwitch.setOnCheckedChangeListener(null);overwriteSwitch.setChecked(p.autoOverwrite);overwriteSwitch.setOnCheckedChangeListener((button,value)->{prefs.edit().putBoolean("proxyBaseAutoOverwrite",value).apply();changed("proxyBaseAutoOverwrite");});renderConfigs();
    }
'''
new_refresh = '''    private void refresh(){
        if(destroyed)return;ProxyRuntimeProfile p=profile();coreValue.setText(p.core.label);modeValue.setText(p.mode.label);ipv6Value.setText(ipv6Label(p.ipv6));
        overwriteSwitch.setOnCheckedChangeListener(null);overwriteSwitch.setChecked(p.autoOverwrite);overwriteSwitch.setOnCheckedChangeListener((button,value)->{prefs.edit().putBoolean("proxyBaseAutoOverwrite",value).apply();changed("proxyBaseAutoOverwrite");refresh();});
        ProxyConfigLibrary.Entry selected=configs.selected(p.core);if(currentConfigValue!=null)currentConfigValue.setText(selected==null?"未选择":selected.name);
        if(restartNotice!=null)restartNotice.setVisibility(prefs.getBoolean(ProxyRuntimeSettings.DIRTY_KEY,false)?View.VISIBLE:View.GONE);
    }
'''
assert old_refresh in s
s = s.replace(old_refresh, new_refresh, 1)
p.write_text(s)

p = root / "android-app/app/src/main/java/io/github/xgl34222220/hetu/ProxyAdvancedSettingsActivity.kt"
s = p.read_text()
s = s.replace('Text("其他代理配置", fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.Bold, color = t.textPrimary, modifier = Modifier.padding(start = 8.dp))',
'''Text(
                    "高级代理配置",
                    fontSize = 21.sp,
                    lineHeight = 28.sp,
                    fontWeight = FontWeight.Bold,
                    color = t.textPrimary,
                    modifier = Modifier.weight(1f),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
                Text("示例数据", fontSize = 11.5.sp, color = t.textSecondary, modifier = Modifier.width(70.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)''', 1)
s = s.replace('padding(start = 8.dp, end = 18.dp, top = 8.dp, bottom = 14.dp)', 'padding(start = 8.dp, end = 14.dp, top = 4.dp, bottom = 6.dp)', 1)
s = s.replace('RoundedCornerShape(22.dp)).padding(horizontal = 20.dp, vertical = 12.dp)', 'RoundedCornerShape(14.dp)).padding(horizontal = 18.dp, vertical = 10.dp)', 1)
s = s.replace('fontSize = 18.sp, lineHeight = 24.sp', 'fontSize = 17.sp, lineHeight = 23.sp', 1)
p.write_text(s)

print("V20.56 basic and advanced proxy settings parity applied")
