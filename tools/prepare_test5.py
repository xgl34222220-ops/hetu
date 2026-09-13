#!/usr/bin/env python3
"""One-time readable source migration, restricted to the exact test.4 baseline.
No encoded payloads, execution of downloaded code, Android commands or user-data writes.
"""
from pathlib import Path
import hashlib
r=Path(__file__).resolve().parents[1]
j=r/'android-app/app/src/main/java/io/github/xgl34222220/bichen'
expected={'MainActivity.java':'87d793a8edf40f69c1c3b1f3ad9d0c0e29d4cf48999161857851f64207ed2d2c','RuleStore.java':'4a04e62d71c3e4e1f32e868293c6b1975098e64d8f85b2fc32266164e5a41d38','DnsVpnService.java':'22668b92ebb2c06f5854213ae6acde16c9416865162a84f1e07160535f4e35c9'}
for name,digest in expected.items():
    assert hashlib.sha256((j/name).read_bytes()).hexdigest()==digest, name+' baseline changed'
p=j/'MainActivity.java';s=p.read_text()
s=s.replace('private String bundledModuleVersion="";','private String bundledModuleVersion="";\n    private TextView homeRuleMetric, homeSecondaryMetric;\n    private boolean refreshAfterReturn;\n    private int vpnMonitorSequence;')
s=s.replace('if(!"dnsLogs".equals(key))homeRefreshNeeded=true;','if(!"dnsLogs".equals(key)&&!"queries".equals(key)&&!"blocked".equals(key)&&!"errors".equals(key))homeRefreshNeeded=true;')
s=s.replace('if(homeRefreshNeeded&&tab==0)showPage(true);homeRefreshNeeded=false;','if(homeRefreshNeeded&&tab==0)showPage(true);else if(tab==0)updateHomeMetrics();homeRefreshNeeded=false;')
s=s.replace('ui.post(refreshLive);}}','ui.post(refreshLive);if(refreshAfterReturn&&statusLoaded&&!busy&&status.optBoolean("rootGranted",false)){refreshAfterReturn=false;loadStatus();}}}\n    @Override public void onStop(){refreshAfterReturn=true;super.onStop();}')
s=s.replace('np=new LinearLayout.LayoutParams(-1,dp(70))','np=new LinearLayout.LayoutParams(-1,dp(70+Math.max(0,Math.min(2,getResources().getConfiguration().fontScale)-1)*22))')
s=s.replace('item.addView(text(labels[n],11,n==tab?ACCENT:MUTED,n==tab));','TextView label=text(labels[n],11,n==tab?ACCENT:MUTED,n==tab);label.setGravity(Gravity.CENTER);label.setSingleLine(true);item.addView(label,new LinearLayout.LayoutParams(-1,-2));')
s=s.replace('appsPending=null;appsApply=null;heading.setText(', 'appsPending=null;appsApply=null;homeRuleMetric=null;homeSecondaryMetric=null;heading.setText(')
s=s.replace('private boolean protectedNow(){return vpn()||(status.optBoolean("enabled")&&status.optBoolean("mounted"));}', '''private ProtectionState.State protectionState(){return ProtectionState.resolve(statusLoaded,status.optBoolean("ok",false),status.has("installed"),installed(),status.optBoolean("enabled"),status.optBoolean("mounted"),status.optBoolean("moduleDisabled"),status.optBoolean("moduleRemovalPending"),pendingReboot(),vpn(),prefs.getBoolean("vpnWanted",false),prefs.getBoolean("vpnRestoreHosts",false));}
    private boolean protectedNow(){return protectionState().active();}
    private void updateHomeMetrics(){if(homeRuleMetric!=null)homeRuleMetric.setText(format(rules==null?0:vpn()?rules.count():status.optInt("ruleCount")));if(homeSecondaryMetric!=null)homeSecondaryMetric.setText(vpn()?format(prefs.getLong("blocked",0)):bytes(status.optLong("hostsBytes")));}
    private String protectionDetail(ProtectionState.State state){switch(state){
        case VPN_ACTIVE:return networkSummary()+" · "+prefs.getInt("activeBypassCount",0)+" 个应用放行";
        case VPN_STARTING:return "正在确认规则与 VPN 接口，可点击停止";
        case RESTORE_PENDING:return "应用保护已停，原模块状态尚未恢复；点击重试恢复";
        case CHECKING:return "正在读取实际状态，不代表模块未安装";
        case UNKNOWN:return "状态读取未完成；不需要据此卸载或重复刷模块";
        case PENDING_REBOOT:return "已检测到暂存更新，重启后重新检查";
        case REMOVAL:return "请在 Root 管理器中取消卸载，或重启完成卸载";
        case DISABLED:return "请在 Root 管理器中启用；App 不会擅自重新启用";
        case ABSENT:return "App 内置模块可安装，也可选择应用保护";
        case UNVERIFIED:return "规则开关与挂载状态不一致，请检查实际挂载";
        case MODULE_ACTIVE:return "系统 hosts 已验证生效";
        default:return "模块已安装，当前暂停拦截";
    }}''')
s=s.replace('boolean active=protectedNow();LinearLayout hero=', 'ProtectionState.State currentState=protectionState();boolean active=currentState.active();LinearLayout hero=')
s=s.replace('text(active?"保护已开启":vpnEngaged()?"保护正在切换":statusLoaded?"保护未开启":"正在检查状态",27,TEXT,true)', 'text(currentState.title,27,TEXT,true)')
old='String detail=vpn()?networkSummary()+" · "+prefs.getInt("activeBypassCount",0)+" 个应用放行":active?"系统 hosts 已验证生效":!statusLoaded?"请完成 Root 授权，稍候查看结果":installed()?(status.optBoolean("enabled")?"规则已启用，实际挂载尚未验证":"随时开启，恢复清净日常"):"安装内置模块，开启系统保护";'
assert old in s;s=s.replace(old,'String detail=protectionDetail(currentState);')
s=s.replace('power.setContentDescription(active?"停止保护":"开启保护");','power.setContentDescription(vpnEngaged()?"停止应用保护或重试恢复":active?"停止保护":currentState==ProtectionState.State.UNKNOWN?"重新检查模块状态":"开启保护");')
s=s.replace('metric(metrics,format(rules==null?0:vpn()?rules.count():status.optInt("ruleCount")),"有效过滤规则");metric(metrics,vpn()?format(prefs.getLong("blocked",0)):bytes(status.optLong("hostsBytes")),vpn()?"累计 DNS 拦截":"规则文件大小");','homeRuleMetric=metric(metrics,format(rules==null?0:vpn()?rules.count():status.optInt("ruleCount")),"有效过滤规则");homeSecondaryMetric=metric(metrics,vpn()?format(prefs.getLong("blocked",0)):bytes(status.optLong("hostsBytes")),vpn()?"累计 DNS 拦截":"规则文件大小");')
s=s.replace('actionRow(warning,"refresh","重新检查","完成 Root 授权后再试",this::loadStatus);','actionRow(warning,"refresh","重新检查","已安装的模块无需重复刷入",this::loadStatus);if(status.has("details"))actionRow(warning,"info","查看诊断详情","完整返回只在这里展示",()->showLong("模块状态详情",status.optString("details")));')
s=s.replace('installed()?"模块已安装 · "+status.optString("version","未知版本"):"安装辟尘模块"','installed()?"模块已安装 · "+status.optString("version","未知版本"):statusLoaded&&status.optBoolean("ok",false)&&status.has("installed")?"安装辟尘模块":"模块状态待确认"')
s=s.replace('private void toggleProtection(){if(vpnEngaged()){stopVpn();return;}','private void toggleProtection(){if(vpnEngaged()){stopVpn();return;}if(protectionState()==ProtectionState.State.UNKNOWN||!statusLoaded){loadStatus();return;}if(pendingReboot()||status.optBoolean("moduleDisabled")||status.optBoolean("moduleRemovalPending")){message(protectionDetail(protectionState()));return;}')
s=s.replace('private void loadStatus(){if(busy)return;', 'private void loadStatus(){if(busy)return;refreshAfterReturn=false;')
old='work("更新保护状态",()->checked(RootBridge.run(this,command)),this::loadStatus);'
new='''work("更新保护状态",()->{status=RootBridge.status(this);statusLoaded=true;if(!status.optBoolean("ok",false))throw new IOException(status.optString("error","模块状态未确认"));if(pendingReboot())throw new IOException("模块等待重启，请重启后操作");if(!status.optBoolean("installed",false))throw new IOException("模块未安装，未执行操作");if(!command.equals("pause")&&(status.optBoolean("moduleDisabled")||status.optBoolean("moduleRemovalPending")))throw new IOException("模块已在 Root 管理器中停用或待卸载，App 不会擅自重新启用");return checked(RootBridge.run(this,command));},this::loadStatus);'''
assert old in s;s=s.replace(old,new)
s=s.replace('private void monitorVpn(boolean expected,int attempt){ui.postDelayed(()->{if(destroyed)return;', 'private void monitorVpn(boolean expected,int attempt){monitorVpn(expected,attempt,++vpnMonitorSequence);}\n    private void monitorVpn(boolean expected,int attempt,int sequence){ui.postDelayed(()->{if(destroyed||sequence!=vpnMonitorSequence)return;')
s=s.replace('monitorVpn(expected,attempt+1);','monitorVpn(expected,attempt+1,sequence);')
s=s.replace('catch(Exception e){message("应用保护未启动："+e.getMessage());}', 'catch(Exception e){prefs.edit().putBoolean("vpnWanted",false).apply();message("应用保护未启动："+e.getMessage());}')
assert hashlib.sha256(s.encode()).hexdigest()=='2009b98d1cceb4e07108fd44d3a36049cf7057a5028d7f7d0d5256568e660cfa'
p.write_text(s)
p=j/'RuleStore.java';s=p.read_text()
old='''            config=rootJson("export-config"); domains=rootJson("export-domains");
            if(config.getString("configRevision").equals(domains.getString("configRevision"))) break;'''
new='''            config=rootJson("export-config");
            // A generation is immutable. Recheck its compact config first; unchanged
            // generations do not need a second su process or the complete domain list.
            if(replacementSources==null && live.fromModule && config.getString("configRevision").equals(live.revision)) {
                mirrorPrefs(live); clearModulePending(); return;
            }
            domains=rootJson("export-domains");
            if(config.getString("configRevision").equals(domains.getString("configRevision"))) break;'''
assert old in s;s=s.replace(old,new)
s=s.replace('        if(replacementSources==null && live.fromModule && revision.equals(live.revision)) { mirrorPrefs(live); clearModulePending();return; }\n','')
assert hashlib.sha256(s.encode()).hexdigest()=='1ba784e07794cfaa0d05a674949f32f74d042628eccdec03f320f7ac5e9281aa'
p.write_text(s)
p=j/'DnsVpnService.java';s=p.read_text()
old='''            if (before.optBoolean("moduleDisabled", false) || before.optBoolean("moduleRemovalPending", false))
                return "模块已在 Root 管理器中停用或待卸载，原 hosts 未恢复";'''
new='''            if (before.optBoolean("moduleDisabled", false) || before.optBoolean("moduleRemovalPending", false)) {
                if (!prefs.edit().putBoolean("vpnRestoreHosts", false).commit())
                    return "模块已停用，无法保存取消恢复状态；未重新启用 hosts";
                return "模块已在 Root 管理器中停用或待卸载，已取消自动恢复；未重新启用 hosts";
            }
            if (before.optBoolean("pendingReboot", false))
                return "模块更新等待重启，原 hosts 恢复已暂停；请重启后检查状态";'''
assert old in s;s=s.replace(old,new)
assert hashlib.sha256(s.encode()).hexdigest()=='8d2e7508eefc8bc2b6e8887ccb91c774601563572eea9a8be4bf1efe35d8195f'
p.write_text(s)
p=r/'tools/build_preview.py';s=p.read_text().replace("VERSION = '0.3.0-test.4'","VERSION = '0.3.0-test.5'").replace('CODE = 304','CODE = 305');p.write_text(s)
p=r/'tools/test_java.py';s=p.read_text().replace("'ModuleArchiveTest')","'ModuleArchiveTest', 'ProtectionStateTest')").replace("'ModuleArchive')]","'ModuleArchive', 'ProtectionState')]");p.write_text(s)
