package io.github.xgl34222220.hetu;

import android.app.*;
import android.content.*;
import android.graphics.Color;
import android.os.*;
import android.text.*;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.util.*;
import java.util.concurrent.*;

/** Root proxy console using LuoShu MIUIX visual language. */
public final class ProxyActivity extends Activity {
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final ExecutorService speedPool=Executors.newFixedThreadPool(4);
    private final Handler ui=new Handler(Looper.getMainLooper());
    private final Map<String,Long> delays=new ConcurrentHashMap<>();
    private ProxyUi u;private SharedPreferences prefs;private RootProxyManager root;private ProxyConfigLibrary configs;private MihomoControllerClient controller;
    private LuoShuDockView nav;private FrameLayout content;private TextView title,subtitle,notice,status,detail,power,downMetric,upMetric,countMetric,strategySummary;
    private ListView list;private BaseAdapter adapter;private int page,panelTab;private boolean busy,running,resumed,closed,polling,panelReady;private String nodeSearch="",message="";
    private JSONObject groups=new JSONObject(),connections=new JSONObject();
    private final Runnable ticker=new Runnable(){public void run(){if(!resumed||closed)return;poll();ui.postDelayed(this,2500);}};

    @Override public void onCreate(Bundle state){prefs=getSharedPreferences("hetu",0);setTheme(ProxyUi.isDark(this)?R.style.AppThemeDark:R.style.AppTheme);super.onCreate(state);u=new ProxyUi(this);root=new RootProxyManager(this);configs=new ProxyConfigLibrary(this);controller=new MihomoControllerClient(this);if(state!=null){page=state.getInt("page",0);panelTab=state.getInt("panelTab",0);nodeSearch=state.getString("search","");}build();showPage(page);}
    @Override public void onSaveInstanceState(Bundle b){super.onSaveInstanceState(b);b.putInt("page",page);b.putInt("panelTab",panelTab);b.putString("search",nodeSearch);}

    private void build(){
        LinearLayout shell=u.col();u.window(shell);
        LinearLayout head=u.row();head.setPadding(u.dp(20),u.dp(10),u.dp(20),u.dp(10));head.addView(u.icon("back","返回",this::finish),new LinearLayout.LayoutParams(u.dp(46),u.dp(46)));
        LinearLayout labels=u.col();labels.setPadding(u.dp(12),0,0,0);title=u.text("代理",30,u.text,true);labels.addView(title);u.gap(labels,3);subtitle=u.text("Root 服务与透明代理",12,u.muted,false);labels.addView(subtitle);head.addView(labels,new LinearLayout.LayoutParams(0,-2,1));shell.addView(head);
        notice=u.text("",12,u.accent,false);notice.setPadding(u.dp(14),u.dp(10),u.dp(14),u.dp(10));notice.setBackground(u.bg(u.soft,18));notice.setMaxLines(2);notice.setEllipsize(TextUtils.TruncateAt.END);notice.setVisibility(View.GONE);notice.setOnClickListener(v->new AlertDialog.Builder(this).setTitle("状态详情").setMessage(message).setPositiveButton("关闭",null).show());LinearLayout.LayoutParams np=new LinearLayout.LayoutParams(-1,-2);np.setMargins(u.dp(20),0,u.dp(20),u.dp(8));shell.addView(notice,np);
        content=new FrameLayout(this);shell.addView(content,new LinearLayout.LayoutParams(-1,0,1));
        nav=new LuoShuDockView(this,new String[]{"首页","面板","工具","设置"},new String[]{"shield","globe","apps","settings"},page,u.accent,u.muted,u.surface,u.dark,index->showPage(index));LinearLayout.LayoutParams bp=new LinearLayout.LayoutParams(-1,u.dp(72));bp.setMargins(u.dp(20),u.dp(6),u.dp(20),u.dp(12));shell.addView(nav,bp);
        setContentView(shell);shell.requestApplyInsets();renderNav();
    }

    private void renderNav(){if(nav!=null)nav.setSelected(page,true);}

    private void showPage(int n){
        page=Math.max(0,Math.min(3,n));content.removeAllViews();list=null;adapter=null;status=detail=power=downMetric=upMetric=countMetric=strategySummary=null;
        title.setText(new String[]{"代理","面板","工具","设置"}[page]);subtitle.setText(new String[]{"Root 服务与当前状态","策略组 · 节点 · 连接","核心 · 日志 · 运行工具","基础代理与网络设置"}[page]);
        if(page==1){if(panelTab==0)strategyPage();else connectionsPage();}
        else{ScrollView s=new ScrollView(this);s.setVerticalScrollBarEnabled(false);s.setClipToPadding(false);LinearLayout body=u.col();body.setPadding(u.dp(20),u.dp(4),u.dp(20),u.dp(18));s.addView(body);content.addView(s,new FrameLayout.LayoutParams(-1,-1));if(page==0)home(body);else if(page==2)tools(body);else settings(body);}
        renderNav();updateVisible();u.enter(content);poll();
    }

    private TextView metric(LinearLayout row,String name){LinearLayout box=u.col();TextView v=u.text("—",18,u.text,true);box.addView(v);u.gap(box,4);box.addView(u.text(name,11,u.muted,false));row.addView(box,new LinearLayout.LayoutParams(0,-2,1));return v;}
    private void sectionHeading(LinearLayout body,String head,String sub){LinearLayout section=u.col();section.setPadding(u.dp(4),u.dp(4),u.dp(4),u.dp(10));section.addView(u.text(head,13,u.muted,true));if(sub!=null&&!sub.isEmpty()){u.gap(section,4);section.addView(u.text(sub,11,u.muted,false));}body.addView(section,new LinearLayout.LayoutParams(-1,-2));}

    private void home(LinearLayout body){
        LinearLayout hero=u.emphasizedCard(body);hero.setPadding(u.dp(20),u.dp(20),u.dp(20),u.dp(20));LinearLayout top=u.row();LinearLayout left=u.col();status=u.text("正在读取",30,u.text,true);left.addView(status);u.gap(left,6);detail=u.text(profileLine(),12,u.muted,false);left.addView(detail);top.addView(left,new LinearLayout.LayoutParams(0,-2,1));power=u.button("启动",true,this::toggle);top.addView(power,new LinearLayout.LayoutParams(u.dp(92),u.dp(52)));hero.addView(top);u.gap(hero,20);LinearLayout metrics=u.row();downMetric=metric(metrics,"下载");upMetric=metric(metrics,"上传");countMetric=metric(metrics,"连接");hero.addView(metrics);
        sectionHeading(body,"代理控制","策略与常用入口");
        LinearLayout policy=u.card(body);LinearLayout h=u.row();h.addView(u.text("当前策略",17,u.text,true),new LinearLayout.LayoutParams(0,-2,1));h.addView(u.chip("打开面板",false,()->{panelTab=0;showPage(1);}));policy.addView(h);u.gap(policy,10);strategySummary=u.text("启动代理后读取实际策略组",12,u.muted,false);strategySummary.setLineSpacing(u.dp(4),1);policy.addView(strategySummary);
        LinearLayout quick=u.card(body);u.action(quick,"globe","节点选择","策略组 · 节点切换 · 单节点/整组测速",()->{panelTab=0;showPage(1);});u.separator(quick);u.action(quick,"activity","连接活动","目标 · 规则 · 代理链 · 流量",()->{panelTab=1;showPage(1);});u.separator(quick);u.action(quick,"settings","基础代理配置",profile().summary(),()->startActivity(new Intent(this,RootTproxyActivity.class)));
    }

    private void panelTabs(LinearLayout p){LinearLayout shell=u.row();shell.setPadding(u.dp(4),u.dp(4),u.dp(4),u.dp(4));shell.setBackground(u.bg(u.surface,22));TextView groupsTab=u.text("策略组",13,panelTab==0?u.accent:u.muted,panelTab==0);groupsTab.setGravity(Gravity.CENTER);groupsTab.setBackground(u.touch(panelTab==0?u.soft:Color.TRANSPARENT,18));groupsTab.setOnClickListener(v->{panelTab=0;showPage(1);});TextView connectionsTab=u.text("连接",13,panelTab==1?u.accent:u.muted,panelTab==1);connectionsTab.setGravity(Gravity.CENTER);connectionsTab.setBackground(u.touch(panelTab==1?u.soft:Color.TRANSPARENT,18));connectionsTab.setOnClickListener(v->{panelTab=1;showPage(1);});shell.addView(groupsTab,new LinearLayout.LayoutParams(0,u.dp(46),1));shell.addView(connectionsTab,new LinearLayout.LayoutParams(0,u.dp(46),1));LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,u.dp(54));lp.bottomMargin=u.dp(12);p.addView(shell,lp);}
    private LinearLayout listShell(){LinearLayout p=u.col();p.setPadding(u.dp(20),u.dp(2),u.dp(20),0);content.addView(p,new FrameLayout.LayoutParams(-1,-1));return p;}
    private void addList(LinearLayout p,BaseAdapter a){list=new ListView(this);list.setDivider(null);list.setDividerHeight(u.dp(10));list.setSelector(u.touch(Color.TRANSPARENT,18));list.setPadding(0,u.dp(10),0,u.dp(14));list.setClipToPadding(false);list.setAdapter(a);p.addView(list,new LinearLayout.LayoutParams(-1,0,1));adapter=a;}
    private LinearLayout rowCard(){LinearLayout c=u.col();c.setPadding(u.dp(18),u.dp(16),u.dp(18),u.dp(16));c.setBackground(new LuoShuSurfaceDrawable(u.surface,u.accent,u.dark?0x14ffffff:0x36ffffff,u.dp(24),.018f,u.dark?.035f:.10f,false));if(Build.VERSION.SDK_INT>=21)c.setElevation(u.dp(1));return c;}
    private View empty(String head,String sub){LinearLayout c=rowCard();c.addView(u.text(head,17,u.text,true));u.gap(c,8);c.addView(u.text(sub,12,u.muted,false));return c;}

    private void strategyPage(){LinearLayout p=listShell();panelTabs(p);EditText search=u.search("搜索策略组或当前节点",nodeSearch);p.addView(search);search.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int a,int b,int c){}public void onTextChanged(CharSequence s,int a,int b,int c){nodeSearch=s.toString();if(adapter!=null)adapter.notifyDataSetChanged();}public void afterTextChanged(Editable e){}});LinearLayout tools=u.row();tools.setPadding(0,u.dp(8),0,0);tools.addView(u.text(panelReady?"已连接当前运行核心":"正在连接运行核心…",11,u.muted,false),new LinearLayout.LayoutParams(0,-2,1));tools.addView(u.chip("刷新",false,this::poll));p.addView(tools);addList(p,new GroupAdapter());}
    private List<String> groupNames(){ArrayList<String> out=new ArrayList<>();String q=nodeSearch.trim().toLowerCase(Locale.ROOT);Iterator<String> it=groups.keys();while(it.hasNext()){String n=it.next();JSONObject g=groups.optJSONObject(n);if(g!=null&&g.optJSONArray("all")!=null&&!"GLOBAL".equals(n)&&(q.isEmpty()||(n+" "+g.optString("now")).toLowerCase(Locale.ROOT).contains(q)))out.add(n);}Collections.sort(out,String.CASE_INSENSITIVE_ORDER);return out;}
    private final class GroupAdapter extends BaseAdapter{
        private List<String> names=groupNames();@Override public void notifyDataSetChanged(){names=groupNames();super.notifyDataSetChanged();}
        public int getCount(){return Math.max(1,names.size());}public Object getItem(int p){return names.isEmpty()?null:names.get(p);}public long getItemId(int p){Object o=getItem(p);return o==null?0:o.hashCode();}
        public View getView(int pos,View old,ViewGroup parent){if(names.isEmpty())return empty(!running?"代理尚未运行":!panelReady?"面板正在就绪":"没有可显示的策略组",!running?"启动代理后可选择策略组与节点。":!panelReady?"Mihomo 已运行，正在连接策略控制接口…":"当前配置没有可切换的策略组。");String name=names.get(pos);JSONObject g=groups.optJSONObject(name);LinearLayout c=rowCard();LinearLayout h=u.row();h.addView(u.text(name,16,u.text,true),new LinearLayout.LayoutParams(0,-2,1));h.addView(u.text(g.optString("type","Group"),11,u.muted,false));c.addView(h);u.gap(c,7);c.addView(u.text(g.optString("now","未选择"),13,u.accent,true));u.gap(c,5);JSONArray all=g.optJSONArray("all");c.addView(u.text((all==null?0:all.length())+" 个节点 · 点击选择与测速",12,u.muted,false));c.setOnClickListener(v->picker(name));return c;}
    }

    private void picker(String group){
        if(!running){setMessage("请先启动代理");return;}JSONObject g=groups.optJSONObject(group);if(g==null)return;JSONArray a=g.optJSONArray("all");if(a==null)return;ArrayList<String> names=new ArrayList<>();for(int i=0;i<a.length();i++)names.add(a.optString(i));boolean selectable="Selector".equalsIgnoreCase(g.optString("type"));
        LinearLayout box=u.col();box.setPadding(u.dp(16),u.dp(4),u.dp(16),u.dp(8));EditText q=u.search("搜索节点","");box.addView(q);LinearLayout bar=u.row();bar.setPadding(0,u.dp(8),0,u.dp(5));bar.addView(u.text(selectable?"点击节点名称切换":"自动策略仅查看/测速",11,u.muted,false),new LinearLayout.LayoutParams(0,-2,1));TextView allTest=u.chip("全部测速",false,()->{});bar.addView(allTest);box.addView(bar);ListView choices=new ListView(this);choices.setDividerHeight(0);box.addView(choices,new LinearLayout.LayoutParams(-1,u.dp(390)));AlertDialog dialog=new AlertDialog.Builder(this).setTitle(group).setView(box).setNegativeButton("关闭",null).create();
        BaseAdapter na=new BaseAdapter(){List<String> filtered(){ArrayList<String> o=new ArrayList<>();String s=q.getText().toString().toLowerCase(Locale.ROOT);for(String n:names)if(n.toLowerCase(Locale.ROOT).contains(s))o.add(n);return o;}public int getCount(){return filtered().size();}public Object getItem(int p){return filtered().get(p);}public long getItemId(int p){return getItem(p).hashCode();}public View getView(int p,View old,ViewGroup parent){String n=(String)getItem(p);JSONObject current=groups.optJSONObject(group);boolean selected=current!=null&&n.equals(current.optString("now"));LinearLayout r=u.row();r.setPadding(u.dp(6),u.dp(8),u.dp(2),u.dp(8));LinearLayout labels=u.col();TextView name=u.text(n,14,selected?u.accent:u.text,selected);labels.addView(name);u.gap(labels,4);Long d=delays.get(n);labels.addView(u.text(d==null?"未测速":d<0?"超时":d+" ms",11,u.muted,false));r.addView(labels,new LinearLayout.LayoutParams(0,-2,1));if(selectable)labels.setOnClickListener(v->runAction(()->{controller.select(group,n);groups=controller.proxies();ui.post(ProxyActivity.this::notifyData);return"已切换："+n;}));r.addView(u.chip("测速",false,()->testNode(n,this)),new LinearLayout.LayoutParams(u.dp(64),-2));return r;}};
        choices.setAdapter(na);q.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int a,int b,int c){}public void onTextChanged(CharSequence s,int a,int b,int c){na.notifyDataSetChanged();}public void afterTextChanged(Editable e){}});allTest.setOnClickListener(v->{for(String n:names)testNode(n,na);});dialog.show();
    }
    private void testNode(String node,BaseAdapter a){speedPool.execute(()->{long d;try{d=controller.delay(node);}catch(Exception e){d=-1;}delays.put(node,d);ui.post(a::notifyDataSetChanged);});}

    private void connectionsPage(){LinearLayout p=listShell();panelTabs(p);LinearLayout head=u.row();downMetric=metric(head,"下载");upMetric=metric(head,"上传");countMetric=metric(head,"连接");p.addView(head);LinearLayout tools=u.row();tools.setPadding(0,u.dp(8),0,0);tools.addView(u.text("实时连接与流量",11,u.muted,false),new LinearLayout.LayoutParams(0,-2,1));tools.addView(u.chip("清空连接",false,()->runAction(()->{controller.closeAll();return"已关闭全部连接";})));p.addView(tools);addList(p,new ConnectionAdapter());}
    private final class ConnectionAdapter extends BaseAdapter{
        public int getCount(){JSONArray a=connections.optJSONArray("connections");return Math.max(1,a==null?0:a.length());}public Object getItem(int p){JSONArray a=connections.optJSONArray("connections");return a==null||a.length()==0?null:a.optJSONObject(p);}public long getItemId(int p){return p;}
        public View getView(int p,View old,ViewGroup parent){JSONObject c=(JSONObject)getItem(p);if(c==null)return empty(running?"当前没有活动连接":"代理尚未运行",running?"新的连接会自动出现在这里。":"启动代理后可查看目标、规则、代理链和流量。");JSONObject m=c.optJSONObject("metadata");if(m==null)m=new JSONObject();String host=m.optString("host");if(host.isEmpty())host=m.optString("destinationIP");String port=m.optString("destinationPort");LinearLayout card=rowCard();card.addView(u.text(host+(port.isEmpty()?"":":"+port),14,u.text,true));u.gap(card,6);JSONArray chain=c.optJSONArray("chains");String chainText="";if(chain!=null)for(int i=0;i<chain.length();i++){if(i>0)chainText+=" → ";chainText+=chain.optString(i);}card.addView(u.text(m.optString("network","-")+" · "+c.optString("rule","-")+(chainText.isEmpty()?"":" · "+chainText),11,u.muted,false));u.gap(card,5);card.addView(u.text("↑ "+bytes(c.optLong("upload"))+"   ↓ "+bytes(c.optLong("download")),11,u.muted,false));return card;}
    }

    private void tools(LinearLayout body){sectionHeading(body,"运行与诊断",null);LinearLayout runtime=u.card(body);u.action(runtime,"activity","运行日志","核心启动 · Root · 透明代理规则",this::showLog);u.separator(runtime);u.action(runtime,"rules","最终启动配置","查看实际交给核心的 startup-config",this::showStartup);sectionHeading(body,"管理与控制",null);LinearLayout manage=u.card(body);u.action(manage,"folder","核心与配置","核心导入 · 配置选择 · 自动覆写",()->startActivity(new Intent(this,RootTproxyActivity.class)));u.separator(manage);u.action(manage,"globe","策略组与节点","节点切换 · 单节点/整组测速",()->{panelTab=0;showPage(1);});u.separator(manage);u.action(manage,"activity","实时连接","目标 · 规则 · 代理链 · 流量",()->{panelTab=1;showPage(1);});}
    private void showLog(){runAction(()->{String d=root.diagnostics();ui.post(()->new AlertDialog.Builder(this).setTitle("运行日志").setMessage(TextUtils.isEmpty(d)?"暂无运行日志":d).setPositiveButton("关闭",null).show());return"";});}
    private void showStartup(){runAction(()->{RootProxyManager.Prepared p=root.prepare(profile());String text=p.startup;ui.post(()->new AlertDialog.Builder(this).setTitle("最终启动配置").setMessage(text.length()>14000?text.substring(0,14000)+"\n…已截断":text).setPositiveButton("关闭",null).show());return"";});}

    private void settings(LinearLayout body){
        ProxyRuntimeProfile p=profile();ProxyConfigLibrary.Entry e=configs.selected(p.core);
        LinearLayout overview=u.emphasizedCard(body);LinearLayout h=u.row();LinearLayout labels=u.col();labels.addView(u.text("当前代理",18,u.text,true));u.gap(labels,5);labels.addView(u.text(p.summary(),12,u.muted,false));h.addView(labels,new LinearLayout.LayoutParams(0,-2,1));h.addView(u.chip(running?"运行中":"未运行",running,()->{page=0;showPage(0);}));overview.addView(h);u.gap(overview,14);overview.addView(u.text(e==null?"尚未选择配置":e.name,14,e==null?u.muted:u.accent,e!=null));
        sectionHeading(body,"代理设置","核心、运行模式与网络行为");
        LinearLayout runtime=u.card(body);u.action(runtime,"settings","基础代理配置","核心 · 运行模式 · IPv6 · 自动覆写 · 配置选择",()->startActivity(new Intent(this,RootTproxyActivity.class)));u.separator(runtime);u.action(runtime,"rules","最终启动配置","查看实际交给核心的 startup-config",this::showStartup);
        sectionHeading(body,"面板与诊断","节点、连接与运行状态");
        LinearLayout control=u.card(body);u.action(control,"globe","策略组与节点","切换节点 · 单节点/整组测速",()->{panelTab=0;showPage(1);});u.separator(control);u.action(control,"activity","实时连接","目标 · 规则 · 代理链 · 实时流量",()->{panelTab=1;showPage(1);});u.separator(control);u.action(control,"activity","运行日志","核心启动 · Root · 透明代理规则",this::showLog);
    }

    private ProxyRuntimeProfile profile(){return ProxyRuntimeProfile.load(prefs);}
    private String profileLine(){ProxyRuntimeProfile p=profile();ProxyConfigLibrary.Entry e=configs.selected(p.core);return p.summary()+" · "+(e==null?"未选择配置":e.name);}
    private void toggle(){if(busy)return;if(running)stopProxy();else startProxy();}
    private void startProxy(){ProxyRuntimeProfile p=profile();if(p.mode==ProxyRuntimeProfile.Mode.TUN){startActivity(new Intent(this,RootTproxyActivity.class));setMessage("TUN 请在基础代理配置中启动");return;}busy=true;setMessage("正在启动 · 检查配置与 Root…");updateVisible();worker.execute(()->{try{root.start(p,s->ui.post(()->setMessage(s)));prefs.edit().putBoolean("rootProxyWanted",true).apply();ui.post(()->{busy=false;setMessage("代理已启动");poll();});}catch(Throwable e){String m="启动失败："+safe(e);ui.post(()->{busy=false;setMessage(m);updateVisible();});}});}
    private void stopProxy(){busy=true;setMessage("正在停止并恢复网络规则…");updateVisible();worker.execute(()->{try{root.stop();prefs.edit().putBoolean("rootProxyWanted",false).apply();ui.post(()->{busy=false;running=false;panelReady=false;groups=new JSONObject();connections=new JSONObject();setMessage("代理已停止");updateVisible();notifyData();});}catch(Throwable e){String m="停止失败："+safe(e);ui.post(()->{busy=false;setMessage(m);updateVisible();});}});}
    private void poll(){if(polling||closed)return;polling=true;worker.execute(()->{boolean live=false,ready=false;JSONObject g=new JSONObject(),c=new JSONObject();String error="";try{live=root.status().optBoolean("running",false);if(live){try{g=controller.proxies();c=controller.connections();ready=true;}catch(Exception api){error=safe(api);}}}catch(Exception e){error=safe(e);}boolean r=live,pr=ready;JSONObject gg=g,cc=c;String err=error;ui.post(()->{polling=false;if(closed)return;running=r;panelReady=pr;groups=gg;connections=cc;if(!err.isEmpty()&&running)setMessage("代理运行中 · 面板正在连接核心");else if(running&&message.startsWith("代理运行中 · 面板"))setMessage("");updateVisible();notifyData();});});}
    private void runAction(Callable<String> job){if(busy)return;busy=true;worker.execute(()->{String result;try{result=job.call();}catch(Exception e){result=safe(e);}String text=result;ui.post(()->{busy=false;if(!text.isEmpty())setMessage(text);poll();});});}
    private void notifyData(){if(adapter!=null)adapter.notifyDataSetChanged();if(strategySummary!=null)strategySummary.setText(strategyText());}
    private void updateVisible(){if(status!=null){status.setText(busy?(running?"正在停止":"正在启动"):running?"运行中":"未运行");detail.setText(profileLine());power.setText(busy?"处理中":running?"停止":"启动");power.setEnabled(!busy);power.setAlpha(busy?.6f:1f);}if(downMetric!=null){downMetric.setText(running?bytes(connections.optLong("downloadTotal")):"—");upMetric.setText(running?bytes(connections.optLong("uploadTotal")):"—");JSONArray a=connections.optJSONArray("connections");countMetric.setText(running?String.valueOf(a==null?0:a.length()):"—");}if(strategySummary!=null)strategySummary.setText(strategyText());}
    private String strategyText(){if(!running)return"启动代理后可选择策略组和节点";if(!panelReady)return"代理已运行，策略面板正在连接核心…";List<String> names=groupNames();if(names.isEmpty())return"当前配置没有可显示的策略组";StringBuilder b=new StringBuilder();for(int i=0;i<Math.min(3,names.size());i++){JSONObject g=groups.optJSONObject(names.get(i));if(i>0)b.append('\n');b.append(names.get(i)).append("  →  ").append(g==null?"—":g.optString("now","—"));}if(names.size()>3)b.append("\n还有 ").append(names.size()-3).append(" 个策略组");return b.toString();}
    private void setMessage(String v){message=v==null?"":v;notice.setText(message);notice.setVisibility(message.isEmpty()?View.GONE:View.VISIBLE);}
    private String safe(Throwable e){String s=e.getMessage();if(TextUtils.isEmpty(s))s=e.getClass().getSimpleName();s=s.replace('\n',' ').replace('\r',' ');return s.length()>240?s.substring(0,240)+"…":s;}
    private String bytes(long n){if(n<1024)return n+" B";double v=n;String[] x={"B","KB","MB","GB","TB"};int i=0;while(v>=1024&&i<x.length-1){v/=1024;i++;}return String.format(Locale.US,v>=100?"%.0f %s":v>=10?"%.1f %s":"%.2f %s",v,x[i]);}

    @Override protected void onResume(){super.onResume();resumed=true;poll();ui.removeCallbacks(ticker);ui.postDelayed(ticker,1000);}
    @Override protected void onPause(){resumed=false;ui.removeCallbacks(ticker);super.onPause();}
    @Override protected void onDestroy(){closed=true;worker.shutdownNow();speedPool.shutdownNow();super.onDestroy();}
}
