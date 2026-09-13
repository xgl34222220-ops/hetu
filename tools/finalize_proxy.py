#!/usr/bin/env python3
"""Checked UI ownership and stream-bound fixes; this file is removed after apply."""
from pathlib import Path
r=Path(__file__).resolve().parents[1]
j=r/'android-app/app/src/main/java/io/github/xgl34222220/bichen'
def change(name,old,new):
 p=j/name;s=p.read_text();assert s.count(old)==1,(name,s.count(old));p.write_text(s.replace(old,new))
change('MainActivity.java','private void rootCommand(String command){','private void rootCommand(String command){if(MihomoVpnService.engaged){message("请先停止 Mihomo，再修改模块挂载");return;}')
change('MainActivity.java','RootBridge.Result r=ModuleInstaller.install(this);','if(MihomoVpnService.engaged)throw new IOException("请先停止 Mihomo，再安装模块");RootBridge.Result r=ModuleInstaller.install(this);')
change('MainActivity.java','if("requestLogs".equals(key)||','if("proxyWanted".equals(key)||"proxyError".equals(key)||"requestLogs".equals(key)||')
change('MainActivity.java','private void activityPage(){','''private void activityPage(){
        if(MihomoVpnService.engaged){LinearLayout p=column();pad(p,22);p.addView(text("当前为 Mihomo 代理",23,TEXT,true));p.addView(text("真实活跃连接在代理面板中查看。这里不把旧 DNS 引擎的计数作为 Mihomo 的记录。",14,MUTED,false));addButton(p,"查看 Mihomo 当前连接",true,()->startActivity(new Intent(this,ProxyActivity.class)));content.addView(p,new FrameLayout.LayoutParams(-1,-1));return;}
''')
change('MainActivity.java','ProtectionState.State currentState=protectionState();boolean active=currentState.active();','''if(MihomoVpnService.engaged){LinearLayout c=card("Mihomo 正在接管流量",MihomoVpnService.state);c.addView(text("代理和域名过滤由同一内核处理；节点与连接请到上方代理面板查看。旧 DNS 引擎未启动。",13,MUTED,false));return;}ProtectionState.State currentState=protectionState();boolean active=currentState.active();''')
change('MihomoVpnService.java','running=true;state="代理与去广告运行中";','running=true;state=prefs.getBoolean("proxyFilter",true)?"代理与去广告运行中":"代理运行中（辟尘过滤关闭）";')
change('ProxyActivity.java','private void launch(){startForegroundService(new Intent(this,MihomoVpnService.class).setAction("START"));ui.postDelayed(this::refreshState,250);}','private void launch(){try{startForegroundService(new Intent(this,MihomoVpnService.class).setAction("START"));ui.postDelayed(this::refreshState,250);}catch(RuntimeException e){coreInfo.setText("系统拒绝启动前台 VPN 服务，请保持页面打开后重试");}}')
