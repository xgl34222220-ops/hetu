package io.github.xgl34222220.bichen;
import android.app.*;
import android.content.*;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.VpnService;
import android.os.*;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.util.*;
import java.util.concurrent.*;
/** Current connections are live snapshots, not fabricated history. */
public final class ProxyActivity extends Activity {
 private final ExecutorService work=Executors.newSingleThreadExecutor();private final Handler ui=new Handler(Looper.getMainLooper());
 private LinearLayout body,groups,connections;private TextView status,summary,coreInfo;private Button power;private ProxyStore store;private SharedPreferences prefs;
 private boolean busy,resumed,closed,changingSwitch;
 private final Runnable refresh=new Runnable(){public void run(){if(!resumed||closed)return;refreshState();ui.postDelayed(this,2000);}};
 private interface Task{String run()throws Exception;}
 @Override public void onCreate(Bundle saved){super.onCreate(saved);store=new ProxyStore(this);prefs=getSharedPreferences("bichen",0);build();task(()->"内核已加载 · "+MihomoNative.call("version").getJSONObject("data").getString("revision").substring(0,12));}
 private int dp(int x){return(int)(x*getResources().getDisplayMetrics().density+.5f);}
 private TextView text(String value,int size,boolean bold){TextView t=new TextView(this);t.setText(value);t.setTextSize(size);t.setTextColor(0xff233a33);if(bold)t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);t.setPadding(0,dp(7),0,dp(7));return t;}
 private LinearLayout section(){LinearLayout c=new LinearLayout(this);c.setOrientation(1);c.setPadding(dp(18),dp(12),dp(18),dp(12));GradientDrawable bg=new GradientDrawable(GradientDrawable.Orientation.TL_BR,new int[]{0xffffffff,0xffeef5ef});bg.setCornerRadius(dp(24));c.setBackground(bg);c.setElevation(dp(2));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.bottomMargin=dp(16);body.addView(c,p);return c;}
 private Button button(LinearLayout c,String title,Runnable action){Button b=new Button(this);b.setText(title);b.setAllCaps(false);b.setTextColor(0xff146b59);b.setOnClickListener(v->action.run());c.addView(b,new LinearLayout.LayoutParams(-1,-2));return b;}
 private void build(){
  ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setBackgroundColor(0xfff4f7f3);
  scroll.setOnApplyWindowInsetsListener((v,insets)->{v.setPadding(insets.getSystemWindowInsetLeft(),insets.getSystemWindowInsetTop(),insets.getSystemWindowInsetRight(),insets.getSystemWindowInsetBottom());return insets.consumeSystemWindowInsets();});
  getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR|View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
  body=new LinearLayout(this);body.setOrientation(1);body.setPadding(dp(20),dp(14),dp(20),dp(24));scroll.addView(body);setContentView(scroll);scroll.requestApplyInsets();
  TextView back=text("‹ 返回辟尘",14,true);back.setMinHeight(dp(48));back.setGravity(Gravity.CENTER_VERTICAL);back.setOnClickListener(v->finish());body.addView(back,new LinearLayout.LayoutParams(-1,-2));
  body.addView(text("代理与去广告",28,true));body.addView(text("Mihomo 内核 · Android VPN",13,false));
  LinearLayout c=section();status=text("未启动",21,true);c.addView(status);summary=text("导入你自己的完整 Mihomo YAML 或兼容订阅；不提供节点。",13,false);c.addView(summary);
  power=button(c,"连接",()->{if(MihomoVpnService.engaged){startService(new Intent(this,MihomoVpnService.class).setAction("STOP"));return;}start();});coreInfo=text("正在加载内核…",12,false);c.addView(coreInfo);
  c=section();c.addView(text("配置",20,true));button(c,"导入 YAML 文件",()->{if(locked())return;Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE);startActivityForResult(i,901);});button(c,"添加 / 更新订阅",this::subscription);
  button(c,"恢复上一份配置",()->{if(!locked())task(()->{store.restore();return "已恢复上一份原始配置";});});
  Switch ads=new Switch(this);ads.setText("同时启用辟尘广告过滤");ads.setChecked(prefs.getBoolean("proxyFilter",true));ads.setOnCheckedChangeListener((b,on)->{if(changingSwitch)return;if(MihomoVpnService.engaged){changingSwitch=true;b.setChecked(!on);changingSwitch=false;toast("停止后修改，避免运行配置与界面不一致");return;}prefs.edit().putBoolean("proxyFilter",on).apply();});c.addView(ads);
  Switch hosts=new Switch(this);hosts.setText("联动已安装的 Root hosts 模块");hosts.setChecked(prefs.getBoolean("proxyManageHosts",false));hosts.setOnCheckedChangeListener((b,on)->{if(changingSwitch)return;if(MihomoVpnService.engaged){changingSwitch=true;b.setChecked(!on);changingSwitch=false;return;}prefs.edit().putBoolean("proxyManageHosts",on).apply();});c.addView(hosts);
  c.addView(text("配置原文件保留。运行时关闭额外监听端口，由 Android 管理 TUN；原 DNS、STUN 与分流规则不被默认模板覆盖。广告白名单不会强制直连。规则变更在下一次连接时加载。",12,false));
  groups=section();groups.addView(text("策略组 / 节点",20,true));groups.addView(text("连接后读取内核实际策略组",13,false));button(groups,"刷新节点",this::loadGroups);
  connections=section();connections.addView(text("当前连接",20,true));connections.addView(text("仅展示内核活跃连接，不是完整历史或 DNS 拦截日志。",12,false));
  c=section();c.addView(text("本轮工作范围",19,true));c.addView(text("已接入的入口是 Android VPN。Root TPROXY / 特殊 eBPF 入站仍未接通，不会改动你原来的 Box 配置。请先停止其他 Root 代理接管；不要同时开启旧 DNS 保护。系统私人 DNS、配置中的 DIRECT 和应用自带解析仍需单独验收，不宣称零泄漏。",13,false));
 }
 private boolean locked(){if(busy||MihomoVpnService.engaged){toast("请先停止代理并等待当前操作完成");return true;}return false;}
 private void toast(String value){Toast.makeText(this,value,Toast.LENGTH_LONG).show();}
 private void task(Task action){if(busy||closed)return;busy=true;coreInfo.setText("正在处理…");work.execute(()->{String result;try{result=action.run();}catch(Exception|LinkageError e){result=e.getMessage()==null?"操作失败":e.getMessage();}final String output=result;ui.post(()->{busy=false;if(!closed){coreInfo.setText(output);refreshState();}});});}
 private void subscription(){
  if(locked())return;EditText input=new EditText(this);input.setSingleLine(true);input.setHint("HTTPS 订阅，凭据仅保存在本机");input.setInputType(0x81);
  new AlertDialog.Builder(this).setTitle("Mihomo YAML 订阅").setView(input).setMessage("粘贴新订阅，留空可更新已保存订阅。需要返回完整 YAML，不上传到第三方转换站。")
   .setNegativeButton("取消",null).setPositiveButton("导入 / 更新",(d,n)->task(()->{String address=input.getText().toString().trim();if(address.isEmpty())address=store.subscription();if(address.isEmpty())throw new Exception("尚未保存订阅");String value=ProxyStore.fetch(address);store.save(value,address);return "订阅已保存在本机，连接时使用新配置";})).show();
 }
 private void start(){
  if(busy)return;if(!store.exists()){toast("先导入完整 YAML 配置或兼容订阅");return;}
  if(DnsVpnService.running||prefs.getBoolean("vpnWanted",false)||prefs.getBoolean("vpnRestoreHosts",false)){toast("请先回保护页停止旧的应用保护并完成恢复");return;}
  new AlertDialog.Builder(this).setTitle("启动代理与去广告？").setMessage("这将使用系统 VPN 槽位，可能替换其他 VPN。请先停止 Box 等 Root 透明代理，避免重复接管。原 TPROXY/eBPF 文件不变。本轮不提供跨引擎无缝切换或零泄漏保证。")
   .setNegativeButton("取消",null).setPositiveButton("启动",(d,n)->{Intent permission=VpnService.prepare(this);if(permission!=null)startActivityForResult(permission,902);else launch();}).show();
 }
 private void launch(){try{startForegroundService(new Intent(this,MihomoVpnService.class).setAction("START"));ui.postDelayed(this::refreshState,250);}catch(RuntimeException e){coreInfo.setText("系统拒绝启动前台 VPN 服务，请保持页面打开后重试");}}
 @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(request==902){if(result==RESULT_OK)launch();else toast("未授权 VPN，配置没有改变");}else if(request==901&&result==RESULT_OK&&data!=null){task(()->{String yaml=ProxyStore.read(getContentResolver().openInputStream(data.getData()));store.save(yaml,"");return "已导入原始 YAML，未上传或覆盖原文件";});}}
 private void refreshState(){if(closed)return;status.setText(MihomoVpnService.state);power.setText(MihomoVpnService.engaged?"停止":"连接");summary.setText(MihomoVpnService.engaged?"当前使用完整 IP 隧道，旧 DNS VPN 不同时运行":store.exists()?"已保存配置；未连接时不代理手机流量":"尚未导入配置");if(!busy&&MihomoVpnService.running)loadConnections();}
 private void loadGroups(){if(!MihomoVpnService.running){toast("请先连接");return;}task(()->{JSONObject proxies=MihomoNative.call("proxies").getJSONObject("data");ui.post(()->renderGroups(proxies));return "已读取内核策略组；点击组选择节点";});}
 private void renderGroups(JSONObject proxies){
  if(closed)return;groups.removeAllViews();groups.addView(text("策略组 / 节点",20,true));button(groups,"刷新节点",this::loadGroups);ArrayList<String> names=new ArrayList<>();Iterator<String> keys=proxies.keys();while(keys.hasNext())names.add(keys.next());Collections.sort(names);
  for(String name:names){JSONObject p=proxies.optJSONObject(name);if(p==null||p.optJSONArray("all")==null)continue;String now=p.optString("now","自动");
   button(groups,name+"\n当前："+now,()->{JSONArray choices=p.optJSONArray("all");String[] list=new String[choices.length()];for(int i=0;i<list.length;i++)list[i]=choices.optString(i);new AlertDialog.Builder(this).setTitle(name).setItems(list,(d,n)->task(()->{MihomoNative.call(new JSONObject().put("action","select").put("group",name).put("name",list[n]));return "已选择节点，刷新可查看当前状态";})).setNegativeButton("取消",null).show();});
   button(groups,"测试「"+name+"」延迟",()->task(()->"延迟 "+MihomoNative.call(new JSONObject().put("action","delay").put("name",name)).getJSONObject("data").getLong("delay")+" ms"));
  }
 }
 private void loadConnections(){if(busy)return;busy=true;work.execute(()->{JSONObject snapshot=null;try{snapshot=MihomoNative.call("connections").getJSONObject("data");}catch(Exception ignored){}final JSONObject data=snapshot;ui.post(()->{busy=false;if(closed||!MihomoVpnService.running||data==null)return;connections.removeAllViews();connections.addView(text("当前连接",20,true));JSONArray entries=data.optJSONArray("connections");int count=entries==null?0:entries.length();connections.addView(text(count+" 条活跃连接 · 非完整历史",12,false));for(int i=0;i<Math.min(count,80);i++){JSONObject e=entries.optJSONObject(i);if(e==null)continue;JSONObject m=e.optJSONObject("metadata");if(m==null)continue;String host=m.optString("host");if(host.isEmpty())host=m.optString("destinationIP");connections.addView(text(host+":"+m.optString("destinationPort")+"\n"+m.optString("network")+" · "+e.optString("rule")+" · "+e.optString("chains"),12,false));}});});}
 @Override public void onResume(){super.onResume();resumed=true;ui.post(refresh);}
 @Override public void onPause(){resumed=false;ui.removeCallbacks(refresh);super.onPause();}
 @Override public void onDestroy(){closed=true;ui.removeCallbacksAndMessages(null);work.shutdown();super.onDestroy();}
}
