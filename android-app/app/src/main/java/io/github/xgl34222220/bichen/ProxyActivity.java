package io.github.xgl34222220.bichen;

import android.app.*;
import android.content.*;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.VpnService;
import android.os.*;
import android.text.*;
import android.view.*;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

/** Native proxy workspace: explicit pages, reusable rows, local opt-in observations. */
public final class ProxyActivity extends Activity {
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final Handler ui=new Handler(Looper.getMainLooper());
    private ProxyUi u; private ProxyStore store; private ProxyRecords records; private SharedPreferences prefs;
    private LinearLayout shell,nav; private FrameLayout content; private TextView heading,notice,status,power,detail,upload,download,count,configLabel,listHint;
    private ListView list; private Rows adapter; private EditText search;
    private JSONObject proxies=new JSONObject(),traffic=new JSONObject(),config=new JSONObject(); private JSONArray observed=new JSONArray();
    private int page; private boolean history,frozen,closed,resumed,busy,polling,changingSwitch;
    private String nodeQuery="",connectionQuery="",message="",coreRevision="",dataError=""; private long lastGroups,seenSession;
    private final Runnable refresh=()->{if(!resumed||closed)return;updateStatus();poll(false);schedule();};
    private interface Task { String run()throws Exception; }
    private void schedule(){ui.removeCallbacks(refresh);if(resumed&&!closed)ui.postDelayed(refresh,1800);}
    @Override public void onCreate(Bundle state){
        super.onCreate(state);u=new ProxyUi(this);setTheme(u.dark?R.style.AppThemeDark:R.style.AppTheme);
        store=new ProxyStore(this);records=ProxyRecords.get(this);prefs=getSharedPreferences("bichen",0);
        if(state!=null){page=state.getInt("page");history=state.getBoolean("history");nodeQuery=state.getString("nodes","");connectionQuery=state.getString("connections","");}
        buildShell();choosePage(page);
        runTask(()->{coreRevision=MihomoNative.call("version").getJSONObject("data").optString("revision");config=store.summary();observed=records.snapshot();return "";});
    }
    private void buildShell(){
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        getWindow().setStatusBarColor(u.bg);getWindow().setNavigationBarColor(u.bg);
        getWindow().getDecorView().setSystemUiVisibility(u.dark?0:View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR|View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        shell=u.col();shell.setBackgroundColor(u.bg);setContentView(shell);
        shell.setOnApplyWindowInsetsListener((v,in)->{if(Build.VERSION.SDK_INT>=30){android.graphics.Insets s=in.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout()|WindowInsets.Type.ime());v.setPadding(s.left,s.top,s.right,s.bottom);}else v.setPadding(in.getSystemWindowInsetLeft(),in.getSystemWindowInsetTop(),in.getSystemWindowInsetRight(),in.getSystemWindowInsetBottom());return in;});
        shell.requestApplyInsets();
        LinearLayout top=u.row();u.pad(top,20,12);top.addView(u.icon("back","返回辟尘",this::finish),new LinearLayout.LayoutParams(u.dp(48),u.dp(48)));
        LinearLayout titles=u.col();titles.addView(u.text("辟尘 / MIHOMO",10,u.muted,true));u.gap(titles,5);heading=u.text("代理概览",24,u.ink,true);titles.addView(heading);
        LinearLayout.LayoutParams titleLp=new LinearLayout.LayoutParams(0,-2,1);titleLp.leftMargin=u.dp(13);top.addView(titles,titleLp);top.addView(u.icon("refresh","刷新当前代理数据",()->{frozen=false;poll(true);}),new LinearLayout.LayoutParams(u.dp(48),u.dp(48)));shell.addView(top);
        notice=u.text("",12,u.accent,false);u.pad(notice,24,8);notice.setMaxLines(2);notice.setEllipsize(TextUtils.TruncateAt.END);notice.setOnClickListener(v->new AlertDialog.Builder(this).setTitle("操作详情").setMessage(message).setPositiveButton("知道了",null).show());shell.addView(notice);notice.setVisibility(View.GONE);
        content=new FrameLayout(this);shell.addView(content,new LinearLayout.LayoutParams(-1,0,1));
        nav=u.row();u.pad(nav,6,6);GradientDrawable glass=new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,new int[]{u.surface,u.soft});glass.setCornerRadius(u.dp(30));nav.setBackground(glass);nav.setElevation(u.dp(5));
        LinearLayout.LayoutParams np=new LinearLayout.LayoutParams(-1,u.dp(72+Math.max(0,getResources().getConfiguration().fontScale-1)*24));np.setMargins(u.dp(22),u.dp(10),u.dp(22),u.dp(12));shell.addView(nav,np);
    }
    private void renderNav(){nav.removeAllViews();String[] names={"概览","节点","连接","设置"},icons={"shield","nodes","activity","settings"};
        for(int i=0;i<4;i++){final int n=i;LinearLayout item=u.col();item.setGravity(Gravity.CENTER);item.setMinimumHeight(u.dp(48));item.setBackground(u.touch(page==i?u.soft:Color.TRANSPARENT,24));item.addView(new IconView(this,icons[i],page==i?u.accent:u.muted),new LinearLayout.LayoutParams(u.dp(22),u.dp(22)));u.gap(item,5);TextView label=u.text(names[i],11,page==i?u.accent:u.muted,page==i);label.setGravity(Gravity.CENTER);item.addView(label,new LinearLayout.LayoutParams(-1,-2));item.setSelected(i==page);item.setContentDescription(names[i]);item.setFocusable(true);item.setOnClickListener(v->{if(page!=n)choosePage(n);});nav.addView(item,new LinearLayout.LayoutParams(0,-1,1));}
    }
    private LinearLayout scrollPage(){ScrollView s=new ScrollView(this);s.setClipToPadding(false);s.setFillViewport(true);s.setVerticalScrollBarEnabled(false);u.pad(s,20,5);LinearLayout b=u.col();s.addView(b);content.addView(s,new FrameLayout.LayoutParams(-1,-1));return b;}
    private void choosePage(int next){
        if(closed)return;((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(content.getWindowToken(),0);
        page=Math.max(0,Math.min(3,next));content.removeAllViews();status=power=detail=upload=download=count=configLabel=listHint=null;adapter=null;list=null;search=null;
        heading.setText(new String[]{"代理概览","策略与节点","连接活动","代理设置"}[page]);renderNav();
        if(page==0)overview();else if(page==3)settings();else listing();updateStatus();poll(false);
    }
    private void overview(){
        LinearLayout b=scrollPage();LinearLayout hero=u.panel(b);hero.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,new int[]{u.dark?0xff294837:0xffdeede2,u.dark?0xff22372c:0xffeff3e6}));((GradientDrawable)hero.getBackground()).setCornerRadius(u.dp(29));
        LinearLayout eyebrow=u.row();eyebrow.addView(u.text("少一点打扰，多一点清净",12,u.muted,false),new LinearLayout.LayoutParams(0,-2,1));eyebrow.addView(u.badge("VPN"));hero.addView(eyebrow);u.gap(hero,23);
        status=u.text("未连接",28,u.ink,true);hero.addView(status);u.gap(hero,9);detail=u.text("",13,u.muted,false);hero.addView(detail);u.gap(hero,23);
        power=u.button("连接代理",true,this::toggle);hero.addView(power,new LinearLayout.LayoutParams(-1,-2));u.gap(hero,12);hero.addView(u.text("代理与辟尘过滤共用一个内核",11,u.muted,false));
        LinearLayout stats=u.panel(b);LinearLayout row=u.row();upload=metric(row,"↑ 上行流量");download=metric(row,"↓ 下行流量");count=metric(row,"活跃连接");stats.addView(row);u.gap(stats,10);stats.addView(u.text("内核当前累计值 · 不代表广告拦截次数",10,u.muted,false));
        LinearLayout cfg=u.panel(b);cfg.addView(u.text("当前配置",17,u.ink,true));u.gap(cfg,9);configLabel=u.text("读取本机配置…",13,u.muted,false);cfg.addView(configLabel);u.gap(cfg,8);u.action(cfg,"download","导入与管理配置","文件、订阅与上一份恢复",()->choosePage(3));
        LinearLayout quick=u.panel(b);u.action(quick,"nodes","选择节点","显示内核选中状态，支持搜索",()->choosePage(1));u.action(quick,"activity","查看连接","当前连接与可选本地采样记录",()->choosePage(2));
    }
    private TextView metric(LinearLayout parent,String label){LinearLayout c=u.col();TextView value=u.text("—",20,u.ink,true);c.addView(value);u.gap(c,7);c.addView(u.text(label,11,u.muted,false));parent.addView(c,new LinearLayout.LayoutParams(0,-2,1));return value;}
    private void settings(){
        LinearLayout b=scrollPage();LinearLayout c=u.panel(b);c.addView(u.text("配置管理",18,u.ink,true));u.gap(c,8);configLabel=u.text("",12,u.muted,false);c.addView(configLabel);
        u.action(c,"download","导入 YAML 文件","保留原文件，在本机生成运行副本",()->{if(locked())return;startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE),901);});
        u.action(c,"refresh","添加或更新订阅","只接收完整 YAML · HTTPS",this::subscription);
        u.action(c,"clock","恢复上一份配置","配置与订阅地址一起恢复",()->{if(locked())return;new AlertDialog.Builder(this).setTitle("恢复上一份配置？").setMessage("当前配置会保留为可回退的一份，原导入文件不变。").setNegativeButton("取消",null).setPositiveButton("恢复",(d,w)->runTask(()->{store.restore();config=store.summary();return "已恢复上一份配置";})).show();});
        c=u.panel(b);c.addView(u.text("保护与记录",18,u.ink,true));u.gap(c,7);toggleRow(c,"同时过滤广告域名","名单在连接时加载；白名单不强制直连","proxyFilter",true,false);
        toggleRow(c,"联动辟尘 hosts 模块","启动时暂停自身模块，停止后尝试恢复","proxyManageHosts",false,false);
        toggleRow(c,"保留已观察连接","本机最多 300 条，每 2 秒采样；关闭不清空","proxyRecordConnections",false,true);
        u.action(c,"close","清空采样记录","不清空规则、节点或内核流量统计",this::clearRecords);
        c=u.panel(b);u.action(c,"settings","外观","跟随系统、浅色与深色",this::appearance);u.action(c,"module","重试恢复原模块","只恢复辟尘自己的 hosts，不改 Box",()->{if(!MihomoVpnService.engaged)startService(new Intent(this,MihomoVpnService.class).setAction("RESTORE"));else feedback("先停止代理再恢复模块");});
        u.action(c,"info","兼容与能力说明","记录范围、Root 模式与配置安全",()->new AlertDialog.Builder(this).setTitle("能力说明").setMessage("当前使用 Android VPN，不占第二个 VPN 槽位。Root TPROXY / 特殊 eBPF 尚未接通。\n\n采样记录只保留观察到的活跃连接；极短连接可能遗漏，不是 DNS / 拒绝事件历史，也不猜测应用归属。\n\n配置与私人订阅只保存在本机。导入只做结构检查，连接时执行内核完整解析。自己的广告名单与原 YAML 规则分别生效，修改后需重连。\n\n不宣称 DNS / WebRTC 零泄漏或异常退出阻断已完成。\n\n内核 "+coreRevision).setPositiveButton("知道了",null).show());
    }
    private void toggleRow(LinearLayout c,String title,String sub,String key,boolean def,boolean live){
        LinearLayout r=u.row();u.pad(r,0,14);LinearLayout label=u.col();label.addView(u.text(title,14,u.ink,true));u.gap(label,6);label.addView(u.text(sub,11,u.muted,false));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,-2,1);p.rightMargin=u.dp(12);r.addView(label,p);Switch sw=new Switch(this);sw.setChecked(prefs.getBoolean(key,def));sw.setContentDescription(title);sw.setMinHeight(u.dp(48));r.addView(sw);c.addView(r);
        sw.setOnCheckedChangeListener((v,on)->{if(changingSwitch)return;if(busy||!live&&MihomoVpnService.engaged){changingSwitch=true;sw.setChecked(!on);changingSwitch=false;feedback("请先停止代理并等待当前操作完成");return;}
            if(live)runTask(()->{records.setEnabled(on);return on?"开始保留之后观察到的连接；不会补回未采集的数据":"已停止新增记录，已有采样保留";});else prefs.edit().putBoolean(key,on).apply();});
    }
    private void listing(){
        LinearLayout b=u.col();u.pad(b,20,5);content.addView(b,new FrameLayout.LayoutParams(-1,-1));
        if(page==2){LinearLayout modes=u.row();TextView live=u.button("当前连接",!history,()->{history=false;choosePage(2);});TextView past=u.button("最近观察",history,()->{history=true;choosePage(2);});modes.addView(live,new LinearLayout.LayoutParams(0,-2,1));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,-2,1);p.leftMargin=u.dp(8);modes.addView(past,p);b.addView(modes);u.gap(b,12);}
        search=u.search(page==1?"搜索策略组、节点名称":"搜索域名、地址、规则或出口");search.setText(page==1?nodeQuery:connectionQuery);b.addView(search,new LinearLayout.LayoutParams(-1,-2));u.gap(b,10);
        LinearLayout toolbar=u.row();listHint=u.text("",11,u.muted,false);toolbar.addView(listHint,new LinearLayout.LayoutParams(0,-2,1));
        if(page==2){TextView pause=u.button(frozen?"继续刷新":"暂停刷新",false,()->{frozen=!frozen;choosePage(2);});pause.setSingleLine(true);toolbar.addView(pause,new LinearLayout.LayoutParams(-2,-2));}b.addView(toolbar);
        list=new ListView(this);list.setDivider(null);list.setClipToPadding(false);list.setPadding(0,u.dp(8),0,u.dp(8));list.setCacheColorHint(Color.TRANSPARENT);adapter=new Rows();list.setAdapter(adapter);b.addView(list,new LinearLayout.LayoutParams(-1,0,1));list.setOnItemClickListener((p,v,pos,id)->{JSONObject item=adapter.shown.get(pos);if(page==1)groupDialog(item.optString("name"));else connectionDialog(item);});
        search.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int st,int n,int a){}public void onTextChanged(CharSequence s,int st,int before,int n){if(page==1)nodeQuery=s.toString();else connectionQuery=s.toString();renderRows();}public void afterTextChanged(Editable e){}});
        if(page==2){TextView help=u.text(history?"采样记录最多 300 条；可能遗漏极短连接，非 DNS / 拒绝历史。":"活跃连接结束后会消失；开启采样后，可在「最近观察」回看。",11,u.muted,false);u.pad(help,0,8);b.addView(help);if(history&&!records.enabled())u.button(b,"开启本机采样记录",false,()->runTask(()->{records.setEnabled(true);return "记录已开启，后续采样在本机保留";}));}
        renderRows();
    }
    private void renderRows(){if(adapter==null)return;ArrayList<JSONObject> result=new ArrayList<>();String q=(page==1?nodeQuery:connectionQuery).trim().toLowerCase(Locale.ROOT);
        if(page==1){ArrayList<String> names=new ArrayList<>();Iterator<String> keys=proxies.keys();while(keys.hasNext())names.add(keys.next());Collections.sort(names);for(String name:names){JSONObject p=proxies.optJSONObject(name);if(p==null||p.optJSONArray("all")==null)continue;try{JSONObject v=new JSONObject(p.toString()).put("name",name);if((name+" "+p.optString("now")+" "+p.optString("all")).toLowerCase(Locale.ROOT).contains(q))result.add(v);}catch(JSONException ignored){}}}
        else{JSONArray data=history?observed:traffic.optJSONArray("connections");if(data!=null)for(int i=0;i<data.length()&&result.size()<300;i++){JSONObject v=data.optJSONObject(i);if(v!=null&&v.toString().toLowerCase(Locale.ROOT).contains(q))result.add(v);}}
        adapter.shown=result;adapter.notifyDataSetChanged();
        String hint=result.isEmpty()?(!q.isEmpty()?"没有匹配项，试试其他关键词":page==1?(MihomoVpnService.running?"内核未返回策略组":"连接后自动加载策略组"):history?(records.enabled()?"尚未采样到连接":"采样关闭，未开始记录"):(MihomoVpnService.running?"当前没有活跃连接":"代理未连接")):page==1?result.size()+" 个策略组 · 点击查看成员":result.size()+" 条"+(history?"本机采样":"活跃连接");
        if(page==1&&!MihomoVpnService.running&&!result.isEmpty())hint+=" · 离线快照";if(!dataError.isEmpty())hint="读取失败 · "+dataError;else if(history&&page==2&&!records.warning().isEmpty())hint=records.warning();else if(frozen&&page==2)hint+=" · 已暂停刷新";listHint.setText(hint);
    }
    private final class Rows extends BaseAdapter{
        ArrayList<JSONObject> shown=new ArrayList<>();public int getCount(){return shown.size();}public Object getItem(int i){return shown.get(i);}public long getItemId(int i){return i;}
        public View getView(int i,View recycled,ViewGroup parent){Holder h;if(recycled==null){h=new Holder();LinearLayout outer=u.col();outer.setPadding(0,0,0,u.dp(10));LinearLayout card=u.col();u.pad(card,16,16);card.setBackground(u.touch(u.surface,21));card.setClickable(false);outer.addView(card,new LinearLayout.LayoutParams(-1,-2));LinearLayout top=u.row();h.title=u.text("",15,u.ink,true);h.title.setMaxLines(2);h.title.setEllipsize(TextUtils.TruncateAt.MIDDLE);top.addView(h.title,new LinearLayout.LayoutParams(0,-2,1));h.badge=u.badge("");LinearLayout.LayoutParams bp=new LinearLayout.LayoutParams(-2,-2);bp.leftMargin=u.dp(8);top.addView(h.badge,bp);card.addView(top);u.gap(card,9);h.sub=u.text("",12,u.muted,false);h.sub.setMaxLines(3);h.sub.setEllipsize(TextUtils.TruncateAt.END);card.addView(h.sub);outer.setTag(h);recycled=outer;}else h=(Holder)recycled.getTag();JSONObject v=shown.get(i);
            if(page==1){h.title.setText(v.optString("name"));h.badge.setText(type(v.optString("type")));h.sub.setText("当前  "+v.optString("now","尚未选择")+"\n"+v.optJSONArray("all").length()+" 个成员");}
            else{JSONObject m=v.optJSONObject("metadata");if(m==null)m=v;h.title.setText(host(m));h.badge.setText(m.optString("network","连接").toUpperCase(Locale.ROOT));h.sub.setText(chain(v)+"  ·  "+v.optString("rule","规则未知")+"\n↑ "+bytes(v.optLong("upload"))+"   ↓ "+bytes(v.optLong("download"))+(history?"  ·  "+clock(v.optLong("lastSeen")):""));}return recycled;}
    }
    private static final class Holder{TextView title,sub,badge;}
    private String type(String s){if(s.equals("Selector"))return "手动选择";if(s.equals("URLTest"))return "自动测速";if(s.equals("Fallback"))return "故障切换";if(s.equals("LoadBalance"))return "负载均衡";return s;}
    private void groupDialog(String name){
        if(!MihomoVpnService.running){feedback("代理未连接，旧节点快照仅供查看；连接后才可切换");return;}JSONObject p=proxies.optJSONObject(name);if(p==null)return;boolean selectable="Selector".equals(p.optString("type"));JSONArray all=p.optJSONArray("all");if(all==null)return;String now=p.optString("now");
        LinearLayout body=u.col();u.pad(body,20,16);body.addView(u.text(name,20,u.ink,true));u.gap(body,7);body.addView(u.text(selectable?"点击成员立即选择；勾选项来自内核":"此组由内核自动管理；下方只查看成员",12,u.muted,false));u.gap(body,14);EditText input=u.search("搜索此组节点");body.addView(input);ListView members=new ListView(this);members.setDivider(null);body.addView(members,new LinearLayout.LayoutParams(-1,u.dp(280)));ArrayList<String> filtered=new ArrayList<>();
        BaseAdapter a=new BaseAdapter(){public int getCount(){return filtered.size();}public Object getItem(int i){return filtered.get(i);}public long getItemId(int i){return i;}public View getView(int i,View v,ViewGroup parent){String n=filtered.get(i);LinearLayout row=u.row();u.pad(row,10,14);row.setMinimumHeight(u.dp(54));row.setBackground(u.touch(n.equals(now)?u.soft:Color.TRANSPARENT,16));TextView t=u.text(n,14,u.ink,n.equals(now));row.addView(t,new LinearLayout.LayoutParams(0,-2,1));if(n.equals(now))row.addView(new IconView(ProxyActivity.this,"check",u.accent),new LinearLayout.LayoutParams(u.dp(22),u.dp(22)));return row;}};members.setAdapter(a);
        Runnable filter=()->{filtered.clear();String q=input.getText().toString().toLowerCase(Locale.ROOT);for(int i=0;i<all.length();i++){String n=all.optString(i);if(n.toLowerCase(Locale.ROOT).contains(q))filtered.add(n);}a.notifyDataSetChanged();};filter.run();input.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int st,int c,int after){}public void onTextChanged(CharSequence s,int st,int before,int count){filter.run();}public void afterTextChanged(Editable e){}});
        AlertDialog d=new AlertDialog.Builder(this).setView(body).setNegativeButton("关闭",null).setNeutralButton("测当前组延迟",(x,w)->runTask(()->"「"+name+"」延迟 "+MihomoNative.call(new JSONObject().put("action","delay").put("name",name)).getJSONObject("data").getLong("delay")+" ms")).create();
        members.setOnItemClickListener((parent,v,pos,id)->{if(!selectable){feedback("该策略组由内核自动选择，没有执行手动切换");return;}String selected=filtered.get(pos);d.dismiss();runTask(()->{MihomoNative.call(new JSONObject().put("action","select").put("group",name).put("name",selected));proxies=MihomoNative.call("proxies").getJSONObject("data");return "已切换到「"+selected+"」";});});d.show();
    }
    private void connectionDialog(JSONObject v){JSONObject m=v.optJSONObject("metadata");if(m==null)m=v;String text="目标  "+host(m)+"\n端口  "+m.optString("destinationPort",m.optString("port"))+"\n协议  "+m.optString("network")+"\n命中类型  "+v.optString("rule")+"\n出口链  "+chain(v)+"\n上传  "+bytes(v.optLong("upload"))+"\n下载  "+bytes(v.optLong("download"))+"\n\n"+(history?"首次观察  "+clock(v.optLong("firstSeen"))+"\n最近观察  "+clock(v.optLong("lastSeen"))+"\n此记录是采样，不表示拦截，也不能确定请求来自哪个应用。":"这是内核的当前连接快照，不是完整请求内容。");new AlertDialog.Builder(this).setTitle("连接详情").setMessage(text).setPositiveButton("知道了",null).show();}
    private void updateStatus(){
        if(closed)return;if(seenSession!=MihomoVpnService.session){seenSession=MihomoVpnService.session;proxies=new JSONObject();traffic=new JSONObject();lastGroups=0;}boolean active=MihomoVpnService.running,engaged=MihomoVpnService.engaged;
        if(status!=null){status.setText(active?"已连接":engaged?"正在切换":"未连接");detail.setText(engaged?MihomoVpnService.state:!MihomoVpnService.failure.isEmpty()?MihomoVpnService.failure:store.exists()?"配置已就绪，连接后接管流量":"导入你的配置，开启代理与过滤");power.setText(engaged?"停止代理":"连接代理");}
        if(configLabel!=null)configLabel.setText(config.optBoolean("exists")?config.optString("kind")+" · "+bytes(config.optLong("bytes"))+"\n"+(config.optLong("modified")>0?"本机保存 "+clock(config.optLong("modified")):"配置原文保留"):"尚未导入 · 支持完整 Mihomo YAML");
        if(upload!=null){upload.setText(active?bytes(traffic.optLong("uploadTotal")):"—");download.setText(active?bytes(traffic.optLong("downloadTotal")):"—");JSONArray a=traffic.optJSONArray("connections");count.setText(active?String.valueOf(a==null?0:a.length()):"—");}
    }
    private void poll(boolean forced){
        if(polling||busy||closed||!resumed)return;if(frozen&&page==2&&!forced)return;polling=true;long session=MihomoVpnService.session;boolean running=MihomoVpnService.running;boolean getGroups=running&&(forced||proxies.length()==0||page==1&&SystemClock.elapsedRealtime()-lastGroups>8000);
        worker.execute(()->{JSONObject nextTraffic=null,nextGroups=null;JSONArray historyData=null;String error="";try{if(running){nextTraffic=MihomoNative.call("connections").getJSONObject("data");if(getGroups)nextGroups=MihomoNative.call("proxies").getJSONObject("data");}if(page==2||page==0)historyData=records.snapshot();}catch(Exception|LinkageError e){error="请刷新重试，或检查代理状态";}final JSONObject t=nextTraffic,g=nextGroups;final JSONArray h=historyData;final String err=error;
            ui.post(()->{polling=false;if(closed)return;if(session!=MihomoVpnService.session)return;if(!frozen||page!=2||forced){if(t!=null&&MihomoVpnService.running)traffic=t;else if(!MihomoVpnService.running)traffic=new JSONObject();if(g!=null){proxies=g;lastGroups=SystemClock.elapsedRealtime();}if(h!=null)observed=h;dataError=err;updateStatus();renderRows();}});
        });
    }
    private void feedback(String s){message=s;notice.setText(s);notice.setVisibility(s.isEmpty()?View.GONE:View.VISIBLE);}
    private void runTask(Task task){if(busy||closed){feedback("已有操作进行中，请稍候");return;}busy=true;feedback("正在处理…");worker.execute(()->{String outcome;try{outcome=task.run();}catch(Exception|LinkageError e){outcome=e.getMessage()==null?"操作失败，原设置未确认变更":e.getMessage();}final String value=outcome;ui.post(()->{busy=false;if(closed)return;feedback(value);updateStatus();renderRows();poll(true);});});}
    private boolean locked(){if(busy||MihomoVpnService.engaged){feedback("先停止代理并等待当前操作完成，再修改运行配置");return true;}return false;}
    private void toggle(){if(MihomoVpnService.engaged){startService(new Intent(this,MihomoVpnService.class).setAction("STOP"));return;}if(busy)return;if(!store.exists()){choosePage(3);feedback("先导入完整 YAML 文件或兼容订阅");return;}if(DnsVpnService.running||prefs.getBoolean("vpnWanted",false)||prefs.getBoolean("vpnRestoreHosts",false)){feedback("先回辟尘保护页停止旧应用保护并完成恢复");return;}
        new AlertDialog.Builder(this).setTitle("连接代理与去广告？").setMessage("将使用系统 VPN，可能替换其他 VPN。请先停止 Box 等 Root 透明代理，避免重复接管；不删除它们的配置。").setNegativeButton("取消",null).setPositiveButton("连接",(d,w)->{Intent intent=VpnService.prepare(this);if(intent==null)launch();else startActivityForResult(intent,902);}).show();}
    private void launch(){try{startForegroundService(new Intent(this,MihomoVpnService.class).setAction("START"));ui.postDelayed(()->{updateStatus();poll(true);},300);}catch(RuntimeException e){feedback("系统拒绝启动 VPN，请保持页面打开后重试");}}
    private void subscription(){if(locked())return;LinearLayout body=u.col();u.pad(body,22,12);EditText input=u.search("HTTPS 完整 YAML 订阅");input.setInputType(0x81);body.addView(input);u.gap(body,12);body.addView(u.text("留空更新已保存的订阅。地址只保存在本机，不交给第三方转换站。",12,u.muted,false));new AlertDialog.Builder(this).setTitle("添加或更新订阅").setView(body).setNegativeButton("取消",null).setPositiveButton("导入 / 更新",(d,w)->{final String typed=input.getText().toString().trim();runTask(()->{String url=typed.isEmpty()?store.subscription():typed;if(url.isEmpty())throw new Exception("尚未保存订阅，请粘贴 HTTPS 地址");String yaml=ProxyStore.fetch(url);store.save(yaml,url);config=store.summary();return "订阅已保存，下次连接加载";});}).show();}
    private void clearRecords(){new AlertDialog.Builder(this).setTitle("清空采样记录？").setMessage("只清除本机已观察连接，不修改配置、名单或流量统计。").setNegativeButton("取消",null).setPositiveButton("清空",(d,w)->runTask(()->{records.clear();observed=records.snapshot();return "采样记录已清空";})).show();}
    private void appearance(){String[] values={"system","light","dark"};new AlertDialog.Builder(this).setTitle("外观").setSingleChoiceItems(new String[]{"跟随系统","浅色","深色"},Arrays.asList(values).indexOf(prefs.getString("appearance","system")),(d,i)->{prefs.edit().putString("appearance",values[i]).apply();d.dismiss();recreate();}).setNegativeButton("取消",null).show();}
    private static String host(JSONObject m){String h=m.optString("host");if(h.isEmpty())h=m.optString("destinationIP",m.optString("ip"));return h.isEmpty()?"未提供目标域名":h;}
    private static String chain(JSONObject v){JSONArray a=v.optJSONArray("chains");if(a==null||a.length()==0)return "出口未知";ArrayList<String> names=new ArrayList<>();for(int i=0;i<a.length();i++)names.add(a.optString(i));return TextUtils.join(" › ",names);}
    private static String bytes(long n){if(n<1024)return n+" B";if(n<1048576)return String.format(Locale.ROOT,"%.1f KB",n/1024d);if(n<1073741824)return String.format(Locale.ROOT,"%.1f MB",n/1048576d);return String.format(Locale.ROOT,"%.2f GB",n/1073741824d);}
    private static String clock(long t){return t<=0?"—":new java.text.SimpleDateFormat("MM-dd HH:mm:ss",Locale.CHINA).format(new Date(t));}
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(request==902){if(result==RESULT_OK)launch();else feedback("未授权 VPN，配置未改变");}else if(request==901&&result==RESULT_OK&&data!=null&&!locked()){final android.net.Uri uri=data.getData();runTask(()->{store.save(ProxyStore.read(getContentResolver().openInputStream(uri)),"");config=store.summary();return "YAML 已导入，原文件没有修改";});}}
    @Override public void onResume(){super.onResume();resumed=true;if(u!=null&&new ProxyUi(this).dark!=u.dark){recreate();return;}ui.post(refresh);}
    @Override public void onPause(){resumed=false;ui.removeCallbacks(refresh);super.onPause();}
    @Override public void onSaveInstanceState(Bundle b){super.onSaveInstanceState(b);b.putInt("page",page);b.putBoolean("history",history);b.putString("nodes",nodeQuery);b.putString("connections",connectionQuery);}
    @Override public void onDestroy(){closed=true;resumed=false;ui.removeCallbacksAndMessages(null);worker.shutdown();super.onDestroy();}
}
