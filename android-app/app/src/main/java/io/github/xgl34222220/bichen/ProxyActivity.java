package io.github.xgl34222220.bichen;

import android.app.*;
import android.content.*;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.VpnService;
import android.os.*;
import android.text.*;
import android.view.*;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import org.json.*;
import java.io.*;
import java.text.*;
import java.util.*;
import java.util.concurrent.*;

/** Native four-section proxy console. Background polling never owns user action state. */
public final class ProxyActivity extends Activity {
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final Handler ui=new Handler(Looper.getMainLooper());
    private ProxyUi u;
    private ProxyStore store;
    private ProxyObservations observations;
    private SharedPreferences prefs;
    private LinearLayout shell,nav;
    private FrameLayout content;
    private TextView title,subtitle,notice,status,detail,configLabel,nodeLabel,upMetric,downMetric,countMetric,connectionHint,settingsLabel;
    private ProgressBar progress;
    private TextView power;
    private ListView list;
    private BaseAdapter adapter;
    private int page;
    private boolean history,resumed,closed,busy,polling;
    private long lastSnapshot,lastGroups,knownSession=-1;
    private String nodeSearch="",connectionSearch="",message="",coreVersion="等待加载";
    private JSONObject groups=new JSONObject(),snapshot=new JSONObject(),config=new JSONObject();
    private List<JSONObject> recent=new ArrayList<>();
    private final Map<String,Long> delays=new HashMap<>();
    private final int[] scrollPositions=new int[4];
    private final Runnable ticker=new Runnable(){public void run(){if(!resumed||closed)return;updateState();poll();ui.postDelayed(this,2000);}};
    private interface Task{String run()throws Exception;}
    @Override public void onCreate(Bundle b){
        prefs=getSharedPreferences("bichen",0);setTheme(ProxyUi.isDark(this)?R.style.AppThemeDark:R.style.AppTheme);super.onCreate(b);
        u=new ProxyUi(this);store=new ProxyStore(this);observations=new ProxyObservations(this);
        if(b!=null){page=Math.max(0,Math.min(3,b.getInt("page")));history=b.getBoolean("history");nodeSearch=b.getString("nodeSearch","");connectionSearch=b.getString("connectionSearch","");}
        buildWindow();showPage(page);
        task(()->{JSONObject v=MihomoNative.call("version").getJSONObject("data");String revision=v.optString("revision","");coreVersion=revision.length()>12?revision.substring(0,12):revision;return "";});
    }
    @Override public void onSaveInstanceState(Bundle b){super.onSaveInstanceState(b);b.putInt("page",page);b.putBoolean("history",history);b.putString("nodeSearch",nodeSearch);b.putString("connectionSearch",connectionSearch);}
    private void buildWindow(){
        shell=u.col();shell.setClipChildren(false);u.window(shell);
        LinearLayout header=u.row();header.setPadding(u.dp(19),u.dp(12),u.dp(19),u.dp(12));header.addView(u.icon("back","返回辟尘",this::finish),new LinearLayout.LayoutParams(u.dp(48),u.dp(48)));
        LinearLayout labels=u.col();labels.setPadding(u.dp(13),0,u.dp(8),0);title=u.text("代理与去广告",25,u.text,true);labels.addView(title);u.gap(labels,6);subtitle=u.text("Mihomo · 统一保护",12,u.muted,false);labels.addView(subtitle);header.addView(labels,new LinearLayout.LayoutParams(0,-2,1));
        header.addView(u.icon("more","代理帮助与使用边界",this::help),new LinearLayout.LayoutParams(u.dp(48),u.dp(48)));shell.addView(header);
        progress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);progress.setIndeterminate(true);progress.setIndeterminateTintList(ColorStateList.valueOf(u.accent));progress.setVisibility(View.GONE);shell.addView(progress,new LinearLayout.LayoutParams(-1,u.dp(3)));
        notice=u.text("",12,u.accent,false);notice.setPadding(u.dp(16),u.dp(12),u.dp(16),u.dp(12));notice.setBackground(u.bg(u.soft,16));notice.setMaxLines(3);notice.setEllipsize(TextUtils.TruncateAt.END);notice.setContentDescription("操作结果，点击查看详情");notice.setOnClickListener(v->{new AlertDialog.Builder(this).setTitle("操作结果").setMessage(message).setPositiveButton("知道了",null).setNeutralButton("收起提示",(d,n)->setMessage("")).show();});notice.setVisibility(View.GONE);LinearLayout.LayoutParams np=new LinearLayout.LayoutParams(-1,-2);np.setMargins(u.dp(20),0,u.dp(20),u.dp(10));shell.addView(notice,np);
        content=new FrameLayout(this);shell.addView(content,new LinearLayout.LayoutParams(-1,0,1));
        nav=u.row();nav.setPadding(u.dp(6),u.dp(6),u.dp(6),u.dp(6));GradientDrawable glass=new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,new int[]{u.surface,u.dark?0xff24342a:0xffeaf1eb});glass.setCornerRadius(u.dp(28));nav.setBackground(glass);nav.setElevation(u.dp(7));
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,u.dp(72+Math.max(0,Math.min(2,getResources().getConfiguration().fontScale)-1)*24));p.setMargins(u.dp(24),u.dp(8),u.dp(24),u.dp(12));shell.addView(nav,p);setContentView(shell);shell.requestApplyInsets();renderNav();
    }
    private void renderNav(){nav.removeAllViews();String[] names={"总览","节点","连接","配置"},icons={"shield","globe","activity","folder"};for(int i=0;i<4;i++){final int n=i;LinearLayout item=u.col();item.setGravity(Gravity.CENTER);item.setBackground(u.touch(page==i?u.soft:Color.TRANSPARENT,22));item.addView(new IconView(this,icons[i],page==i?u.accent:u.muted),new LinearLayout.LayoutParams(u.dp(22),u.dp(22)));u.gap(item,5);TextView label=u.text(names[i],11,page==i?u.accent:u.muted,page==i);label.setGravity(Gravity.CENTER);label.setSingleLine(true);item.addView(label,new LinearLayout.LayoutParams(-1,-2));item.setContentDescription(names[i]);item.setSelected(page==i);item.setOnClickListener(v->showPage(n));nav.addView(item,new LinearLayout.LayoutParams(0,-1,1));}}
    private void showPage(int n){
        if(closed)return;if(list!=null)scrollPositions[page]=list.getFirstVisiblePosition();((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(content.getWindowToken(),0);
        page=n;content.removeAllViews();list=null;adapter=null;status=null;detail=null;power=null;upMetric=null;downMetric=null;countMetric=null;configLabel=null;nodeLabel=null;connectionHint=null;settingsLabel=null;
        title.setText(new String[]{"代理与去广告","选择节点","连接活动","代理配置"}[n]);subtitle.setText(new String[]{"Mihomo · 统一保护","选择明确，切换有回执","实时与最近观察分别展示","原文件保留，设置留在本机"}[n]);
        if(n==1)nodePage();else if(n==2)connectionPage();else{ScrollView scroll=new ScrollView(this);scroll.setVerticalScrollBarEnabled(false);scroll.setClipToPadding(false);LinearLayout body=u.col();body.setPadding(u.dp(20),u.dp(4),u.dp(20),u.dp(12));scroll.addView(body);content.addView(scroll,new FrameLayout.LayoutParams(-1,-1));if(n==0)overview(body);else configuration(body);}
        renderNav();updateState();poll();
    }
    private TextView metric(LinearLayout parent,String label){LinearLayout box=u.col();TextView v=u.text("—",20,u.text,true);box.addView(v);u.gap(box,7);box.addView(u.text(label,11,u.muted,false));parent.addView(box,new LinearLayout.LayoutParams(0,-2,1));return v;}
    private void overview(LinearLayout body){
        LinearLayout hero=u.card(body);GradientDrawable g=new GradientDrawable(GradientDrawable.Orientation.TL_BR,new int[]{u.dark?0xff264438:0xffdceee4,u.dark?0xff1c3027:0xfff4f1e4});g.setCornerRadius(u.dp(28));hero.setBackground(g);
        hero.addView(u.text("清净上网，自由连接",12,u.accent,true));u.gap(hero,20);LinearLayout r=u.row();LinearLayout left=u.col();status=u.text("未连接",26,u.text,true);left.addView(status);u.gap(left,9);detail=u.text("导入配置，即可开始",13,u.muted,false);left.addView(detail);r.addView(left,new LinearLayout.LayoutParams(0,-2,1));
        power=u.button("连接",true,this::toggle);power.setMinWidth(u.dp(76));LinearLayout.LayoutParams pp=new LinearLayout.LayoutParams(u.dp(80),u.dp(70));pp.leftMargin=u.dp(12);r.addView(power,pp);hero.addView(r);u.gap(hero,25);LinearLayout stats=u.row();downMetric=metric(stats,"已下载");upMetric=metric(stats,"已上传");countMetric=metric(stats,"实时连接");hero.addView(stats);
        LinearLayout c=u.card(body);configLabel=u.text("尚未导入配置",17,u.text,true);c.addView(configLabel);u.gap(c,7);c.addView(u.text("订阅与节点凭据只保存在本机",12,u.muted,false));u.action(c,"folder","管理配置","导入、更新与恢复上一份",()->showPage(3));
        LinearLayout p=u.card(body);nodeLabel=u.text("选择你的出站节点",17,u.text,true);p.addView(nodeLabel);u.action(p,"globe","节点与策略组","查看当前选择 · 搜索 · 延迟测试",()->showPage(1));u.action(p,"activity","查看连接活动","目标域名、分流规则与流量",()->showPage(2));
        LinearLayout applied=u.card(body);applied.addView(u.text("当前生效设置",16,u.text,true));u.gap(applied,8);settingsLabel=u.text("",12,u.muted,false);applied.addView(settingsLabel);u.action(applied,"apps","应用放行","放行会绕过整个 VPN，不只是广告过滤",this::openApps);
        LinearLayout foot=u.card(body);foot.addView(u.text("两种引擎，不重复接管",14,u.text,true));u.gap(foot,8);foot.addView(u.text("此处使用 Mihomo 完整 VPN。旧 DNS 应用保护不会同时开启；Root 模块联动可在配置页单独设置。",12,u.muted,false));
    }
    private LinearLayout listShell(){LinearLayout p=u.col();p.setPadding(u.dp(20),u.dp(4),u.dp(20),0);content.addView(p,new FrameLayout.LayoutParams(-1,-1));return p;}
    private void addList(LinearLayout p,BaseAdapter a){list=new ListView(this);list.setDivider(null);list.setDividerHeight(u.dp(10));list.setCacheColorHint(Color.TRANSPARENT);list.setSelector(u.touch(Color.TRANSPARENT,20));list.setClipToPadding(false);list.setPadding(0,u.dp(12),0,u.dp(12));list.setVerticalScrollBarEnabled(false);list.setAdapter(a);p.addView(list,new LinearLayout.LayoutParams(-1,0,1));adapter=a;list.setSelection(scrollPositions[page]);}
    private TextWatcher watcher(Runnable action){return new TextWatcher(){public void beforeTextChanged(CharSequence s,int start,int count,int after){}public void onTextChanged(CharSequence s,int start,int before,int count){action.run();}public void afterTextChanged(Editable e){}};}
    private void nodePage(){
        LinearLayout p=listShell();EditText search=u.search("搜索策略组或当前节点",nodeSearch);p.addView(search,new LinearLayout.LayoutParams(-1,-2));search.addTextChangedListener(watcher(()->{nodeSearch=search.getText().toString();if(adapter!=null)adapter.notifyDataSetChanged();}));
        LinearLayout actions=u.row();TextView hint=u.text("连接后自动读取实际策略组",12,u.muted,false);actions.addView(hint,new LinearLayout.LayoutParams(0,-2,1));actions.addView(u.icon("refresh","刷新节点",()->{lastGroups=0;poll();}),new LinearLayout.LayoutParams(u.dp(48),u.dp(48)));p.addView(actions);
        addList(p,new GroupAdapter());
    }
    private List<String> groupNames(){ArrayList<String> out=new ArrayList<>();Iterator<String> it=groups.keys();while(it.hasNext()){String name=it.next();JSONObject p=groups.optJSONObject(name);if(p!=null&&p.optJSONArray("all")!=null&&!"GLOBAL".equals(name)&&(name+" "+p.optString("now")).toLowerCase(Locale.ROOT).contains(nodeSearch.trim().toLowerCase(Locale.ROOT)))out.add(name);}Collections.sort(out);return out;}
    private final class GroupAdapter extends BaseAdapter {
        private List<String> names=groupNames();
        @Override public void notifyDataSetChanged(){names=groupNames();super.notifyDataSetChanged();}
        public int getCount(){return Math.max(1,names.size());}public Object getItem(int pos){return names.isEmpty()?null:names.get(pos);}public long getItemId(int p){Object n=getItem(p);return n==null?0:n.hashCode();}
        public View getView(int pos,View old,android.view.ViewGroup parent){
            if(names.isEmpty())return empty(MihomoVpnService.running?(nodeSearch.isEmpty()?"暂无策略组":"没有匹配的节点组"):"连接后查看节点",MihomoVpnService.running?"配置中没有可显示的策略组，或搜索条件没有匹配。":"先在总览连接，节点信息来自实际内核。",()->showPage(MihomoVpnService.running?3:0));
            String name=names.get(pos);JSONObject p=groups.optJSONObject(name);LinearLayout c=rowCard();c.addView(u.text(name,17,u.text,true));u.gap(c,9);String now=p.optString("now","尚未选定");c.addView(u.text("当前  "+now,14,u.accent,true));u.gap(c,8);String type=p.optString("type");c.addView(u.text(("Selector".equalsIgnoreCase(type)?"手动选择":"自动策略 · "+type)+"  ·  "+p.optJSONArray("all").length()+" 个选项",12,u.muted,false));u.gap(c,10);
            c.addView(u.button("Selector".equalsIgnoreCase(type)?"选择 / 测试节点":"查看自动策略",false,()->picker(name)),new LinearLayout.LayoutParams(-1,-2));return c;
        }
    }
    private LinearLayout rowCard(){LinearLayout c=u.col();c.setPadding(u.dp(18),u.dp(17),u.dp(18),u.dp(17));c.setBackground(u.bg(u.surface,22));return c;}
    private View empty(String heading,String description,Runnable action){LinearLayout c=rowCard();u.gap(c,14);c.addView(u.text(heading,19,u.text,true));u.gap(c,12);c.addView(u.text(description,13,u.muted,false));u.gap(c,18);c.addView(u.button("返回总览 / 配置",false,action));return c;}
    private void picker(String group){
        if(!MihomoVpnService.running){setMessage("连接已停止，请重新连接后选择节点");return;}JSONObject p=groups.optJSONObject(group);if(p==null)return;
        final long session=MihomoVpnService.generation;JSONArray array=p.optJSONArray("all");if(array==null)return;ArrayList<String> names=new ArrayList<>();for(int n=0;n<array.length();n++)names.add(array.optString(n));boolean selectable="Selector".equalsIgnoreCase(p.optString("type"));
        LinearLayout box=u.col();box.setPadding(u.dp(20),u.dp(10),u.dp(20),u.dp(12));EditText query=u.search("搜索节点名称","");query.setBackground(u.bg(u.soft,16));box.addView(query);u.gap(box,8);box.addView(u.text(selectable?"点击名称选择；右侧可单独测速":"自动策略由内核决定；点击测速查看可用性",12,u.muted,false));
        ListView choices=new ListView(this);choices.setDividerHeight(0);box.addView(choices,new LinearLayout.LayoutParams(-1,u.dp(340)));
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle(group).setView(box).setNegativeButton("关闭",null).create();
        BaseAdapter nodeAdapter=new BaseAdapter(){
            private List<String> filtered(){ArrayList<String> out=new ArrayList<>();String q=query.getText().toString().toLowerCase(Locale.ROOT);for(String v:names)if(v.toLowerCase(Locale.ROOT).contains(q))out.add(v);return out;}
            public int getCount(){return filtered().size();}public Object getItem(int n){return filtered().get(n);}public long getItemId(int n){return getItem(n).hashCode();}
            public View getView(int n,View old,android.view.ViewGroup parent){String name=(String)getItem(n);JSONObject current=groups.optJSONObject(group);boolean selected=current!=null&&name.equals(current.optString("now"));LinearLayout r=u.row();r.setMinimumHeight(u.dp(70));r.setPadding(u.dp(8),u.dp(9),u.dp(4),u.dp(9));r.setBackground(u.touch(selected?u.soft:Color.TRANSPARENT,16));
                LinearLayout labels=u.col();TextView t=u.text(name,14,selected?u.accent:u.text,selected);t.setMaxLines(3);labels.addView(t);u.gap(labels,6);Long delay=delays.get(name);labels.addView(u.text((selected?"当前节点 · ":"")+(delay==null?"尚未测速":delay<0?"测试超时 / 不可达":delay+" ms"),12,u.muted,false));r.addView(labels,new LinearLayout.LayoutParams(0,-2,1));
                labels.setMinimumHeight(u.dp(48));labels.setGravity(Gravity.CENTER_VERTICAL);labels.setOnClickListener(v->{if(!selectable){setMessage("此组由内核自动选择，不强制改成手动策略");return;}task(()->{ensureSession(session);MihomoNative.call(new JSONObject().put("action","select").put("group",group).put("name",name));JSONObject result=MihomoNative.call("proxies").getJSONObject("data");ui.post(()->{if(!closed&&MihomoVpnService.generation==session){groups=result;notifyData();}});return "已选择："+name;});dialog.dismiss();});
                TextView test=u.chip("测速",false,()->task(()->{ensureSession(session);long d;try{d=MihomoNative.call(new JSONObject().put("action","delay").put("name",name)).getJSONObject("data").getLong("delay");}catch(Exception e){d=-1;}final long ms=d;ui.post(()->{if(!closed&&MihomoVpnService.generation==session){delays.put(name,ms);notifyDataSetChanged();}});return ms<0?"测速未通过，不等于已切换节点":"节点延迟："+ms+" ms";}));r.addView(test,new LinearLayout.LayoutParams(u.dp(66),-2));return r;}
        };choices.setAdapter(nodeAdapter);query.addTextChangedListener(watcher(nodeAdapter::notifyDataSetChanged));dialog.show();
    }
    private void connectionPage(){
        LinearLayout p=listShell();LinearLayout tabs=u.row();tabs.setBackground(u.bg(u.surface,19));tabs.addView(u.chip("实时连接",!history,()->{history=false;showPage(2);}),new LinearLayout.LayoutParams(0,-2,1));tabs.addView(u.chip("最近观察",history,()->{history=true;showPage(2);}),new LinearLayout.LayoutParams(0,-2,1));p.addView(tabs);u.gap(p,10);
        EditText q=u.search("搜索域名、IP、规则或代理链",connectionSearch);p.addView(q);q.addTextChangedListener(watcher(()->{connectionSearch=q.getText().toString();notifyData();}));
        connectionHint=u.text("",12,u.muted,false);connectionHint.setPadding(u.dp(3),u.dp(12),0,u.dp(12));p.addView(connectionHint);
        addList(p,new ConnectionAdapter());updateConnectionHint();
    }
    private List<JSONObject> connectionRows(){ArrayList<JSONObject> out=new ArrayList<>();if(history)out.addAll(recent);else if(MihomoVpnService.running){JSONArray a=snapshot.optJSONArray("connections");if(a!=null)for(int i=0;i<a.length();i++){JSONObject e=a.optJSONObject(i);if(e!=null)out.add(e);}}String q=connectionSearch.trim().toLowerCase(Locale.ROOT);out.removeIf(e->!connectionText(e).toLowerCase(Locale.ROOT).contains(q));if(!history)out.sort(Comparator.comparing(e->e.optString("id")));return out;}
    private JSONObject metadata(JSONObject e){JSONObject m=e.optJSONObject("metadata");return m==null?e:m;}
    private String host(JSONObject e){JSONObject m=metadata(e);String h=m.optString("host");return h.isEmpty()?m.optString("destinationIP",m.optString("ip","未知目标")):h;}
    private String connectionText(JSONObject e){JSONObject m=metadata(e);return host(e)+" "+m.optString("destinationIP",m.optString("ip"))+" "+m.optString("network")+" "+e.optString("rule")+" "+e.optString("chains");}
    private final class ConnectionAdapter extends BaseAdapter{
        private List<JSONObject> rows=connectionRows();
        @Override public void notifyDataSetChanged(){rows=connectionRows();super.notifyDataSetChanged();}
        public int getCount(){return Math.max(1,rows.size());}public Object getItem(int n){return rows.isEmpty()?null:rows.get(n);}public long getItemId(int n){JSONObject e=(JSONObject)getItem(n);return e==null?0:e.optString("id").hashCode();}public boolean hasStableIds(){return true;}
        public View getView(int n,View old,android.view.ViewGroup parent){JSONObject e=(JSONObject)getItem(n);if(e==null)return empty(!connectionSearch.isEmpty()?"没有匹配的连接":history?"还没有最近观察":MihomoVpnService.running?"当前没有活跃连接":"代理尚未连接",history?"在配置页开启“保留最近观察”后，只保存实际采样到的连接。不会补造过去的记录。":MihomoVpnService.running?"尝试在其他 App 联网。很快结束的连接可能不会出现在实时快照中。":"连接后，流量由 Mihomo 处理并在这里显示。",()->showPage(history?3:0));
            LinearLayout c;if(old instanceof LinearLayout&&"connection".equals(old.getTag()))c=(LinearLayout)old;else{c=rowCard();c.setTag("connection");c.addView(u.text("",15,u.text,true));u.gap(c,8);c.addView(u.text("",12,u.muted,false));u.gap(c,7);c.addView(u.text("",12,u.accent,false));}
            JSONObject m=metadata(e);TextView h=(TextView)c.getChildAt(0);h.setText(host(e));h.setMaxLines(2);h.setEllipsize(TextUtils.TruncateAt.END);
            ((TextView)c.getChildAt(2)).setText(m.optString("network").toUpperCase(Locale.ROOT)+" · "+e.optString("rule","未知规则")+" · "+(history?"最后观察 "+time(e.optLong("lastSeen")):"活跃"));
            ((TextView)c.getChildAt(4)).setText("↓ "+bytes(e.optLong("download"))+"    ↑ "+bytes(e.optLong("upload")));c.setOnClickListener(v->connectionDetails(e));return c;
        }
    }
    private void connectionDetails(JSONObject e){JSONObject m=metadata(e);new AlertDialog.Builder(this).setTitle(host(e)).setMessage("目标 IP："+m.optString("destinationIP",m.optString("ip","未知"))+"\n端口："+m.optString("destinationPort",m.optString("port","未知"))+"\n协议："+m.optString("network")+"\n命中规则："+e.optString("rule")+"\n代理链："+e.optString("chains")+"\n下载："+bytes(e.optLong("download"))+"\n上传："+bytes(e.optLong("upload"))+"\n\n"+(history?"这是连接采样，不是完整历史，也不是广告拦截事件。":"这是当前连接快照，不推测请求所属应用。")).setPositiveButton("关闭",null).show();}
    private void updateConnectionHint(){if(connectionHint==null)return;String observationError=prefs.getString("proxyHistoryError","");if(history&&!observationError.isEmpty()){connectionHint.setText(observationError);return;}connectionHint.setText(history?(prefs.getBoolean("proxyHistory",false)?"每 2 秒采样 · 本机最多保留 300 条":"记录已关闭 · 保留已有观察"):(MihomoVpnService.running?"来自内核的实时快照":"代理未运行 · 不显示旧连接"));}
    private void configuration(LinearLayout body){
        LinearLayout c=u.card(body);configLabel=u.text(configTitle(),19,u.text,true);c.addView(configLabel);u.gap(c,8);c.addView(u.text(configDetail(),12,u.muted,false));
        u.action(c,"folder","导入 YAML 文件","完整 Mihomo / Clash 兼容配置",()->{if(locked())return;startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE),901);});
        u.action(c,"globe","添加订阅","HTTPS 地址，仅在本机保存",this::subscription);
        u.action(c,"refresh","更新已保存订阅","内容相同时保留原回滚点",()->{if(!locked())updateSubscription("");});
        u.action(c,"clock","恢复上一份配置",config.optBoolean("hasPrevious")?"一次确认，恢复配置与订阅地址":"尚未产生历史版本",()->{if(locked())return;new AlertDialog.Builder(this).setTitle("恢复上一份配置？").setMessage("当前配置将成为可恢复的上一份。不改动你导入的原文件。").setNegativeButton("取消",null).setPositiveButton("恢复",(d,n)->task(()->{store.restore();return "已恢复上一份配置";})).show();});
        LinearLayout protection=u.card(body);protection.addView(u.text("保护选项",18,u.text,true));u.gap(protection,8);
        settingsLabel=u.text("",12,u.muted,false);protection.addView(settingsLabel);
        u.action(protection,"apps","应用放行","与首页名单共用，停止后重新连接生效",this::openApps);
        toggleRow(protection,"同时过滤广告域名","白名单不改变原来的代理分流","proxyFilter",true,true);
        toggleRow(protection,"阻止加密 DNS 绕过","默认关闭 · 拦已知 DoH 与 TCP/UDP 853；尊重配置内显式 DNS","proxyDnsGuard",false,true);
        u.action(protection,"refresh","更新防绕过列表",dnsGuardSummary(),this::updateDnsGuard);
        toggleRow(protection,"联动自己的 hosts 模块","启动时暂停，停止后尝试恢复；需 Root","proxyManageHosts",false,true);
        if(prefs.getBoolean("proxyRestoreHosts",false))u.action(protection,"refresh","重试恢复原模块","恢复未完成；不会因此卸载模块",()->{if(!MihomoVpnService.engaged)startForegroundService(new Intent(this,MihomoVpnService.class).setAction("RESTORE"));});
        LinearLayout logs=u.card(body);logs.addView(u.text("连接观察",18,u.text,true));u.gap(logs,8);toggleRow(logs,"保留最近观察","默认关闭 · 本机 300 条 · 不含完整 DNS / 拒绝历史","proxyHistory",false,false);
        u.action(logs,"close","清空最近观察","只清除观察记录，不清空名单与配置",()->new AlertDialog.Builder(this).setTitle("清空最近观察？").setNegativeButton("取消",null).setPositiveButton("清空",(d,n)->task(()->{observations.clear();return "观察记录已清空";})).show());
        LinearLayout appearance=u.card(body);appearance.addView(u.text("外观",18,u.text,true));u.gap(appearance,12);LinearLayout row=u.row();String mode=prefs.getString("appearance","system");String[] keys={"system","light","dark"},labels={"跟随系统","浅色","深色"};for(int i=0;i<3;i++){final String choice=keys[i];row.addView(u.chip(labels[i],choice.equals(mode),()->{prefs.edit().putString("appearance",choice).apply();recreate();}),new LinearLayout.LayoutParams(0,-2,1));}appearance.addView(row);
        LinearLayout about=u.card(body);u.action(about,"info","使用说明与兼容范围","内核 "+coreVersion+" · Android VPN",this::help);
    }
    private void toggleRow(LinearLayout parent,String title,String description,String key,boolean fallback,boolean requiresStop){
        LinearLayout row=u.row();row.setPadding(0,u.dp(14),0,u.dp(14));LinearLayout labels=u.col();labels.addView(u.text(title,14,u.text,true));u.gap(labels,6);labels.addView(u.text(description,12,u.muted,false));row.addView(labels,new LinearLayout.LayoutParams(0,-2,1));Switch s=new Switch(this);s.setMinimumWidth(u.dp(52));s.setMinimumHeight(u.dp(48));s.setButtonTintList(ColorStateList.valueOf(u.accent));s.setContentDescription(title);s.setChecked(prefs.getBoolean(key,fallback));
        s.setOnClickListener(v->{boolean on=s.isChecked();if((requiresStop&&MihomoVpnService.engaged)||busy){s.setChecked(!on);setMessage("请先停止代理或等待当前操作完成");return;}if("proxyHistory".equals(key))task(()->{observations.setEnabled(on);return on?"最近观察已开启：只记录后续采样到的连接":"记录已关闭；已有记录保留，可单独清空";});else{prefs.edit().putBoolean(key,on).apply();setMessage("设置已保存，下次连接生效");}});row.addView(s,new LinearLayout.LayoutParams(u.dp(58),-2));parent.addView(row);
    }
    private String dnsGuardSummary(){EncryptedDnsGuard guard=new EncryptedDnsGuard(this);long at=guard.updatedAt();return guard.count()+" 个域名 · "+(at>0?"更新 "+time(at):"内置保底 · 可下载完整 HaGeZi 列表");}
    private void updateDnsGuard(){task(()->{EncryptedDnsGuard guard=new EncryptedDnsGuard(this);guard.update();return "防绕过列表已更新："+guard.count()+" 个域名；当前连接不会热切换，重新连接后使用";});}
    private void openApps(){startActivity(new Intent(this,MainActivity.class).putExtra("proxyApps",true));}
    private String configTitle(){return config.optBoolean("exists")?(config.optBoolean("subscription")?"订阅配置已就绪":"本地配置已就绪"):"添加一份配置";}
    private String configDetail(){if(!config.optBoolean("exists"))return "不会内置或提供代理节点";long checked=config.optLong("checkedAt");return "版本 "+config.optString("revision","未知")+(checked>0?"\n最近检查 "+time(checked):"\n原配置已保留")+"\n凭据不显示在页面或操作结果中";}
    private void subscription(){if(locked())return;EditText e=u.search("HTTPS 订阅地址","");e.setInputType(0x81);e.setSaveEnabled(false);e.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);LinearLayout box=u.col();box.setPadding(u.dp(20),u.dp(12),u.dp(20),u.dp(12));box.addView(e);new AlertDialog.Builder(this).setTitle("添加订阅").setMessage("需要返回完整 YAML。原配置不会被失败下载覆盖；不发送到第三方转换站。").setView(box).setNegativeButton("取消",null).setPositiveButton("导入",(d,n)->{String value=e.getText().toString().trim();if(value.isEmpty()){setMessage("订阅地址不能为空；更新原订阅请用“更新已保存订阅”");return;}updateSubscription(value);}).show();}
    private void updateSubscription(String input){task(()->{ProxyStore.UpdateRequest request=store.updateRequest(input);String expected=request.revision;String address=request.address;if(address.isEmpty())throw new IOException("尚未保存订阅，请先添加订阅");String yaml=ProxyStore.fetch(address);boolean changed=store.saveIfUnchanged(yaml,address,expected);return changed?"订阅已更新，下次连接使用新配置":"订阅内容无变化，原回滚点已保留";});}
    private boolean locked(){if(busy||MihomoVpnService.engaged){setMessage("请先停止代理并等待当前操作完成");return true;}return false;}
    private void toggle(){if(MihomoVpnService.engaged){startService(new Intent(this,MihomoVpnService.class).setAction("STOP"));return;}start();}
    private void start(){if(busy){setMessage("正在处理配置，请稍候");return;}if(!store.exists()){showPage(3);setMessage("先导入完整 YAML 配置或兼容订阅");return;}if(DnsVpnService.running||prefs.getBoolean("vpnWanted",false)||prefs.getBoolean("vpnRestoreHosts",false)){setMessage("请先回保护页停止旧 DNS 应用保护并完成恢复");return;}new AlertDialog.Builder(this).setTitle("启动 Mihomo 代理？").setMessage("会使用系统 VPN 槽位。请先停止其他 VPN 或 Box Root 代理，避免重复接管；不会删除原配置。").setNegativeButton("取消",null).setPositiveButton("连接",(d,n)->{Intent permission=VpnService.prepare(this);if(permission==null)launch();else startActivityForResult(permission,902);}).show();}
    private void launch(){try{startForegroundService(new Intent(this,MihomoVpnService.class).setAction("START"));setMessage("");ui.postDelayed(this::updateState,250);}catch(RuntimeException e){setMessage("系统拒绝启动 VPN 服务，请保持页面打开后重试");}}
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(request==902){if(result==RESULT_OK)launch();else setMessage("VPN 未授权，配置保持不变");}else if(request==901&&result==RESULT_OK&&data!=null&&!locked()){android.net.Uri uri=data.getData();task(()->{String rev=store.revision();String yaml=ProxyStore.read(getContentResolver().openInputStream(uri));boolean changed=store.saveIfUnchanged(yaml,"",rev);return changed?"配置已导入，原文件未改动":"配置内容相同，未覆盖原回滚点";});}}
    private void ensureSession(long expected)throws IOException{if(!MihomoVpnService.running||MihomoVpnService.generation!=expected)throw new IOException("连接状态已改变，请在当前连接中重新操作");}
    private void setMessage(String value){message=value;notice.setText(value);notice.setVisibility(value.isEmpty()?View.GONE:View.VISIBLE);}
    private void task(Task t){if(busy||closed){if(!closed)setMessage("当前操作尚未结束，请稍候");return;}busy=true;progress.setVisibility(View.VISIBLE);worker.execute(()->{String result;JSONObject info=null;try{result=t.run();info=store.info();}catch(Exception|LinkageError e){result=safeError(e);}final String value=result;final JSONObject next=info;ui.post(()->{busy=false;if(closed)return;progress.setVisibility(View.GONE);if(next!=null)config=next;setMessage(value);if(page==3)showPage(3);else{updateState();notifyData();}lastGroups=0;poll();});});}
    private String safeError(Throwable e){String value=e.getMessage();if(value==null)return "操作失败，原配置不会被自动清除";if(value.contains("://")||value.length()>350)return "操作失败，请检查配置格式、订阅网络与服务状态；不输出配置凭据";return value;}
    private void updateState(){if(closed)return;
        boolean run=MihomoVpnService.running,engaged=MihomoVpnService.engaged;if(knownSession!=MihomoVpnService.generation){knownSession=MihomoVpnService.generation;groups=new JSONObject();snapshot=new JSONObject();delays.clear();lastGroups=0;lastSnapshot=0;notifyData();}
        if(!run&&snapshot.length()>0){snapshot=new JSONObject();groups=new JSONObject();lastGroups=0;notifyData();}
        if(status!=null){status.setText(run?(MihomoVpnService.networkValidated?"保护已启动":MihomoVpnService.networkLabel):engaged?"正在处理":"未连接");detail.setText(run?MihomoVpnService.state+(MihomoVpnService.startedAt>0?"\n已运行 "+duration(SystemClock.elapsedRealtime()-MihomoVpnService.startedAt):""):engaged?MihomoVpnService.state:store.exists()?"配置已就绪，随时连接":"先导入你的 YAML 或订阅");power.setText(engaged?"停止":"连接");power.setContentDescription(engaged?"停止 Mihomo 代理":"连接 Mihomo 代理");}
        if(settingsLabel!=null)settingsLabel.setText(MihomoVpnService.settingsSummary(prefs,getPackageName()));
        if(upMetric!=null){upMetric.setText(run?bytes(snapshot.optLong("uploadTotal")):"—");downMetric.setText(run?bytes(snapshot.optLong("downloadTotal")):"—");countMetric.setText(run?String.valueOf(snapshot.optJSONArray("connections")==null?0:snapshot.optJSONArray("connections").length()):"—");}
        if(configLabel!=null)configLabel.setText(configTitle());if(nodeLabel!=null){String now="";Iterator<String> keys=groups.keys();while(keys.hasNext()){String name=keys.next();JSONObject p=groups.optJSONObject(name);if(!"GLOBAL".equals(name)&&p!=null&&p.optJSONArray("all")!=null){now=p.optString("now");if(!now.isEmpty())break;}}nodeLabel.setText(now.isEmpty()?"选择你的出站节点":"当前策略  "+now);}
        String error=prefs.getString("proxyError","");if(!engaged&&!error.isEmpty()&&message.isEmpty())setMessage(error);updateConnectionHint();
    }
    private void poll(){if(!resumed||closed||busy||polling)return;boolean run=MihomoVpnService.running;final long session=MihomoVpnService.generation,now=SystemClock.elapsedRealtime();final boolean getGroups=run&&(page==0||page==1)&&(groups.length()==0||now-lastGroups>10000);final boolean getLive=run&&(page==0||(page==2&&!history))&&now-lastSnapshot>1000;final boolean getHistory=page==2&&history;
        if(!getGroups&&!getLive&&!getHistory)return;polling=true;worker.execute(()->{JSONObject gs=null,ss=null;List<JSONObject> hs=null;String error="";try{if(getGroups)gs=MihomoNative.call("proxies").getJSONObject("data");if(getLive)ss=MihomoNative.call("connections").getJSONObject("data");if(getHistory)hs=observations.readRecent();}catch(Exception|LinkageError e){error=safeError(e);}final JSONObject g=gs,s=ss;final List<JSONObject> h=hs;final String err=error;ui.post(()->{polling=false;if(closed)return;if(h!=null)recent=h;if(session==MihomoVpnService.generation&&MihomoVpnService.running){if(g!=null){groups=g;lastGroups=now;}if(s!=null){snapshot=s;lastSnapshot=now;}}if(!err.isEmpty()&&resumed)setMessage("读取未完成："+err);updateState();notifyData();});});
    }
    private void notifyData(){if(adapter!=null)adapter.notifyDataSetChanged();}
    private void help(){new AlertDialog.Builder(this).setTitle("代理与去广告").setMessage("导入完整 YAML 或 HTTPS YAML 订阅 → 总览连接 → 节点页选择策略组。\n\n实时连接来自内核快照；“最近观察”需主动开启，每 2 秒采样，本机最多 300 条，短连接可能漏记。二者都不是完整 DNS / 广告拒绝日志。\n\n修改名单、配置与过滤开关需停止后重新连接。应用放行已与首页名单共用，会绕过代理和去广告，不是只放行广告；未安装或当前不可见的应用会单独提示，系统 hosts 仍可能影响放行应用。\n\n“阻止加密 DNS 绕过”默认关闭。开启后会拦已知 DoH 解析器与 853 端口；导入配置中明确使用的 DoH/DoT/DoQ 解析器会被保留。防绕过列表只在你点更新时联网获取，VPN 启动不等待下载。\n\n本轮为 Android VPN，Root TPROXY / 特殊 eBPF 未接通。网络已验证只表示系统网络校验通过，不代表每个代理节点均可用。WebRTC、物理切网及异常退出阻断仍需真机验收，不承诺零泄漏。\n\n内核："+coreVersion+"\n订阅凭据不上传到开发者。").setPositiveButton("知道了",null).show();}
    static String bytes(long n){n=Math.max(0,n);if(n<1024)return n+" B";if(n<1048576)return String.format(Locale.ROOT,"%.1f KB",n/1024d);if(n<1073741824)return String.format(Locale.ROOT,"%.1f MB",n/1048576d);return String.format(Locale.ROOT,"%.2f GB",n/1073741824d);}
    private String time(long ms){return new SimpleDateFormat("MM-dd HH:mm:ss",Locale.CHINA).format(new Date(ms));}
    private String duration(long ms){long sec=Math.max(0,ms/1000);return sec>=3600?String.format(Locale.ROOT,"%d:%02d:%02d",sec/3600,sec/60%60,sec%60):String.format(Locale.ROOT,"%d:%02d",sec/60,sec%60);}
    @Override public void onResume(){super.onResume();if(u!=null&&u.dark!=ProxyUi.isDark(this)){recreate();return;}resumed=true;ui.removeCallbacks(ticker);ui.post(ticker);}
    @Override public void onPause(){resumed=false;ui.removeCallbacks(ticker);super.onPause();}
    @Override public void onDestroy(){closed=true;ui.removeCallbacksAndMessages(null);worker.execute(observations::close);worker.shutdown();super.onDestroy();}
}
