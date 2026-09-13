#!/usr/bin/env python3
"""Apply the request-log fix to the checked test.5 baseline; no user data accessed."""
from pathlib import Path
import hashlib
r=Path(__file__).resolve().parents[1]
j=r/'android-app/app/src/main/java/io/github/xgl34222220/bichen'
for n,h in {'MainActivity.java':'2009b98d1cceb4e07108fd44d3a36049cf7057a5028d7f7d0d5256568e660cfa','DnsVpnService.java':'8d2e7508eefc8bc2b6e8887ccb91c774601563572eea9a8be4bf1efe35d8195f'}.items():
    assert hashlib.sha256((j/n).read_bytes()).hexdigest()==h, n+' changed: review instead of overwriting'
def replace(s,a,b):
    assert s.count(a)==1, 'Expected one occurrence: '+a[:90]
    return s.replace(a,b)
p=j/'DnsVpnService.java';s=p.read_text()
s=replace(s,'edit().putString("dnsLogs", "[]").apply();','edit().putString("dnsLogs", "[]").remove("dnsLogError").remove("dnsLogNotice").apply();')
s=replace(s,'JSONArray previous = new JSONArray(prefs.getString("dnsLogs", "[]")), next = new JSONArray();\n                for (int i = Math.max(0, previous.length() - 99); i < previous.length(); i++) next.put(previous.get(i));', '''JSONArray previous;
                boolean repaired = false;
                try { previous = new JSONArray(prefs.getString("dnsLogs", "[]")); }
                catch (org.json.JSONException invalid) { previous = new JSONArray(); repaired = true; }
                JSONArray next = new JSONArray();
                for (int i = Math.max(0, previous.length() - 99); i < previous.length(); i++) {
                    JSONObject entry = previous.optJSONObject(i);
                    if (entry != null && !entry.optString("domain").isEmpty() && !entry.optString("result").isEmpty()) next.put(entry);
                    else repaired = true;
                }''')
s=replace(s,'prefs.edit().putString("dnsLogs", next.toString()).apply();\n            } catch (Exception ignored) { }','''SharedPreferences.Editor edit = prefs.edit().putString("dnsLogs", next.toString()).remove("dnsLogError");
                if (repaired) edit.putString("dnsLogNotice", "旧请求记录损坏，已重建记录；丢失内容不会补造");
                edit.apply();
            } catch (Exception failure) {
                // Do not silently stop logging forever. No domain or payload is included.
                prefs.edit().putString("dnsLogError", "请求记录写入失败：" + failure.getClass().getSimpleName()).apply();
            }''')
p.write_text(s)
p=j/'MainActivity.java';s=p.read_text()
s=replace(s,'private boolean refreshAfterReturn;','private boolean refreshAfterReturn;\n    private boolean liveRefreshScheduled;\n    private TextView requestLogHint;')
s=replace(s,'if("dnsNetworkState".equals(key)||','if("requestLogs".equals(key)||"dnsLogError".equals(key)||"dnsLogNotice".equals(key)||"dnsNetworkState".equals(key)||')
s=replace(s,'ui.removeCallbacks(this.refreshLive); ui.postDelayed(this.refreshLive,180);','scheduleLiveRefresh();')
s=replace(s,'private final Runnable refreshLive=()->{\n        if(destroyed)return;','private final Runnable refreshLive=()->{\n        liveRefreshScheduled=false;\n        if(destroyed)return;')
s=replace(s,'    private interface Job {','''    // Schedule once from the first event; later packets cannot postpone the refresh.
    private void scheduleLiveRefresh(){if(!destroyed&&!liveRefreshScheduled){liveRefreshScheduled=true;ui.postDelayed(refreshLive,180);}}
    private interface Job {''')
s=replace(s,'ui.post(refreshLive);if(refreshAfterReturn','scheduleLiveRefresh();if(refreshAfterReturn')
s=replace(s,'homeRuleMetric=null;homeSecondaryMetric=null;heading.setText(', 'homeRuleMetric=null;homeSecondaryMetric=null;requestLogHint=null;heading.setText(')
s=replace(s,'stats.addView(text(vpn()?"只记录本机域名，不记录网页内容。请求无法可靠归属到具体应用。":"以下为历史应用保护数据；模块模式不能统计真实请求。",11,MUTED,false));', 'requestLogHint=text(requestLogContext(),11,MUTED,false);stats.addView(requestLogHint);')
s=replace(s,'actions.addView(clear);page.addView(actions);','TextView help=text("无记录排查",12,ACCENT,true);help.setPadding(dp(8),dp(11),dp(8),dp(11));help.setOnClickListener(v->showRequestLogHelp());actions.addView(help);actions.addView(clear);page.addView(actions);')
a='catch(Exception ignored){}empty.setText(!logSearch.trim().isEmpty()?"没有匹配的请求":prefs.getBoolean("requestLogs",false)?"还没有请求记录\\n开启应用保护并使用应用后，会在这里显示":"请求记录未开启\\n打开上方开关后，记录仅保存在本机");notifyDataSetChanged();'
b='catch(Exception invalid){readFailed=true;}empty.setText(readFailed?"本地请求记录损坏\\n开启记录后，下一条真实查询将重建记录":requestLogEmpty());if(requestLogHint!=null)requestLogHint.setText(requestLogContext());notifyDataSetChanged();'
s=replace(s,'shown.clear();try{JSONArray logs=new JSONArray(prefs.getString("dnsLogs","[]"));','shown.clear();boolean readFailed=false;try{JSONArray logs=new JSONArray(prefs.getString("dnsLogs","[]"));')
s=replace(s,a,b)
methods='''    private String requestLogContext(){
        String state=!vpn()?(prefs.getBoolean("vpnWanted",false)?"应用保护尚未启动完成":installed()?"当前没有运行应用保护；hosts 模块不产生 DNS 请求日志":"应用保护未运行；这里只显示历史 DNS 请求"):
                prefs.getBoolean("requestLogs",false)?"正在记录经过辟尘的 DNS · 最近 100 条，仅本机保存":"应用保护运行中，但请求记录开关已关闭";
        String error=prefs.getString("dnsLogError","");String note=prefs.getString("dnsLogNotice","");
        return state+(error.isEmpty()?note.isEmpty()?"":"\\n"+note:"\\n"+error);
    }
    private String requestLogEmpty(){
        if(!logSearch.trim().isEmpty())return "没有匹配的请求\\n清除搜索内容后查看全部记录";
        if(!prefs.getString("dnsLogError","").isEmpty())return prefs.getString("dnsLogError","")+"\\n点击“无记录排查”查看详情";
        if(!vpn())return (prefs.getBoolean("vpnWanted",false)?"等待应用保护启动":installed()?"hosts 模块不产生请求记录":"应用保护尚未开启")+
                "\\n仅打开记录开关不会切换保护方式\\n点击“无记录排查”查看可用方式";
        if(!prefs.getBoolean("requestLogs",false))return "请求记录开关已关闭\\n打开上方开关，仅从现在开始记录";
        return "尚未收到经过辟尘的 DNS 查询\\n缓存、应用放行或其他解析通道可能使列表为空\\n点击“无记录排查”查看详情";
    }
    private void showRequestLogHelp(){
        LinearLayout c=column();c.addView(text("为什么没有请求记录",22,TEXT,true));gap(c,10);
        c.addView(text(requestLogContext(),13,ACCENT,true));gap(c,10);
        c.addView(text("这里不是打开软件的历史，也不是所有网络连接记录。只有经过辟尘应用保护的 DNS 查询，且记录开关已开启时，才会保存。hosts 模块本身不记录这些查询；记录为空不等于广告拦截无效。",12,MUTED,false));
        gap(c,10);c.addView(text("系统缓存、已放行的应用、应用自带加密 DNS、私人 DNS 或代理远端解析，都可能不经过这里。不会为了显示记录而关闭你的私人 DNS、截取页面内容或补造数据。",12,MUTED,false));
        final Dialog d=sheet(c);
        if(!prefs.getBoolean("requestLogs",false))addButton(c,"开启本地请求记录",true,()->{DnsVpnService.setRequestLogging(this,true);d.dismiss();if(tab==3)showPage(false);});
        if(!vpn()&&!prefs.getBoolean("vpnWanted",false))actionRow(c,"apps","开启应用保护以记录","会使用系统 VPN 槽位；需另行确认，不会直接切换",()->{d.dismiss();prepareVpn();});
        if(!logSearch.trim().isEmpty())actionRow(c,"search","清除搜索过滤","查看全部已有请求",()->{logSearch="";d.dismiss();showPage(false);});
        actionRow(c,"settings","查看 DNS 设置","检查应用保护的上游设置",()->{d.dismiss();showDnsSettings();});
        if(installed())actionRow(c,"clock","查看模块操作日志","安装、规则更新与挂载日志，不是逐应用请求",()->{d.dismiss();work("读取模块日志",()->checked(RootBridge.run(this,"logs")),()->{});});
    }
'''
s=replace(s,'    private void activityPage(){',methods+'    private void activityPage(){')
p.write_text(s)
p=r/'tools/build_preview.py';s=p.read_text();s=replace(s,"VERSION = '0.3.0-test.5'","VERSION = '0.3.0-test.6'");s=replace(s,'CODE = 305','CODE = 306');p.write_text(s)
p=r/'tests/device/Smoke.java';s=p.read_text()
s=replace(s,'        // Fixtures exercise production rendering, not a pretend Root framework.', '''        RequestLogs.run(this, activity, log);
        // Fixtures exercise production rendering, not a pretend Root framework.''');p.write_text(s)
p=r/'tools/test_device.py';s=p.read_text();s=replace(s,"ROOT/'tests/device/Smoke.java')","*sorted((ROOT/'tests/device').glob('*.java')))");p.write_text(s)
print('Request logging test.6 source migration applied')
