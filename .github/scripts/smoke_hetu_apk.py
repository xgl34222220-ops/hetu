#!/usr/bin/env python3
"""Navigation smoke in a fresh non-root Android emulator.
Changes fixture display language and exercises denied Root setup in non-root AOSP.
Never starts/stops a proxy, imports user data, or clears app data.
"""
import hashlib, json, os, re, subprocess, time, xml.etree.ElementTree as ET
from pathlib import Path
from contextlib import contextmanager
PKG='io.github.xgl34222220.hetu'
ADB=str(Path(os.environ['ANDROID_HOME'])/'platform-tools'/'adb')
OUT=Path(os.environ.get('HETU_SMOKE_OUT','out/android-smoke')); OUT.mkdir(parents=True,exist_ok=True)
from mock_webview_controller import controller_fixture
checks=[]
def adb(*args, timeout=90, check=True):
 try:
  return subprocess.run([ADB,*args],stdout=subprocess.PIPE,stderr=subprocess.STDOUT,timeout=timeout,check=check).stdout.decode(errors='replace')
 except subprocess.TimeoutExpired as error:
  (OUT/'adb-timeout.txt').write_text(' '.join(args)+'\n'+(error.output or b'').decode(errors='replace'))
  raise
ui_sequence=0
def ui():
 global ui_sequence
 deadline=time.monotonic()+60
 last='No snapshot attempted'
 while time.monotonic()<deadline:
  ui_sequence+=1
  path=f'/data/local/tmp/hetu-smoke-{ui_sequence}.xml'
  try:
   dumped=adb('shell','uiautomator','dump',path,timeout=min(20,max(1,deadline-time.monotonic())),check=False)
   if 'Permission denied' in dumped:raise PermissionError(dumped)
   raw=adb('shell','cat',path,timeout=15,check=False)
   start=raw.find('<?xml')
   if start>=0:
    try:return ET.fromstring(raw[start:]),raw[start:]
    except ET.ParseError as error:last='Incomplete hierarchy: '+str(error)
   else:
    if 'Permission denied' in raw:raise PermissionError(raw)
    last=dumped+'\n'+raw
   with (OUT/'ui-snapshot-retries.txt').open('a') as log:log.write(path+'\n'+last+'\n')
  except subprocess.TimeoutExpired as error:last='Snapshot command timeout: '+str(error)
  finally:
   adb('shell','rm','-f',path,timeout=10,check=False)
  time.sleep(1)
 raise AssertionError('No fresh accessibility hierarchy within60s: '+last)

def save_png(name):
 png=subprocess.run([ADB,'exec-out','screencap','-p'],check=True,stdout=subprocess.PIPE,timeout=45).stdout
 assert png.startswith(b'\x89PNG\r\n\x1a\n');(OUT/(name+'.png')).write_bytes(png)

def capture(name):
 try:root,raw=ui()
 except Exception:
  save_png(name)
  raise
 (OUT/(name+'.xml')).write_text(raw)
 save_png(name)
 return root

def click(label,scroll=False,bottom=False):
 for attempt in range(7 if scroll else 1):
  root,_=ui();found=[n for n in root.iter('node') if n.get('text')==label or n.get('content-desc')==label]
  if bottom: found.sort(key=lambda n: int(re.findall(r'\d+',n.get('bounds','[0,0][0,0]'))[1]),reverse=True)
  if found:
   x1,y1,x2,y2=map(int,re.findall(r'\d+',found[0].get('bounds','')))
   assert x2>x1 and y2>y1,(label,found[0].attrib)
   if scroll and (y1 < 65 or y2 > 742):
    adb('shell','input','swipe','196','710','196','260','400');time.sleep(1);continue
   adb('shell','input','tap',str((x1+x2)//2),str((y1+y2)//2));time.sleep(2);return
  if scroll: adb('shell','input','swipe','196','710','196','260','400');time.sleep(1)
 raise AssertionError('UI label unavailable: '+label)

def expect(label,name):
 root=capture(name);assert any(label in n.get('text','') or label in n.get('content-desc','') for n in root.iter('node')),(label,name)
 checks.append({'name':name,'result':'passed'})

def switch_node(root,label):
 parents={child:parent for parent in root.iter() for child in parent}
 titles=[n for n in root.iter('node') if n.get('text')==label]
 assert len(titles)==1,('Expected one switch label',label,len(titles))
 row=titles[0]
 while row in parents:
  row=parents[row]
  switches=[n for n in row.iter('node') if n.get('checkable')=='true' and n.get('clickable')=='true']
  if switches:
   assert len(switches)==1,('Ambiguous switch row',label)
   return switches[0]
 raise AssertionError('No accessible switch for '+label)

def click_switch(label):
 root,_=ui();node=switch_node(root,label)
 assert node.get('enabled')=='true' and node.get('checked')=='false',node.attrib
 x1,y1,x2,y2=map(int,re.findall(r'\d+',node.get('bounds','')))
 assert x2>x1 and y2>y1,node.attrib
 adb('shell','input','tap',str((x1+x2)//2),str((y1+y2)//2));time.sleep(2)

def expect_eventually(label,name):
 deadline=time.monotonic()+120
 while time.monotonic()<deadline:
  root,_=ui()
  if any(label in n.get('text','') or label in n.get('content-desc','') for n in root.iter('node')):
   expect(label,name);return
  time.sleep(2)
 raise AssertionError('Timed out waiting for accessibility label: '+label)

def wait_for_home():
 # Software-emulated AOSP may present its own first-boot System UI ANR.
 # Preserve evidence, wait for that exact system process at most twice, and
 # never dismiss an application ANR or turn off Android's ANR detector.
 deadline=time.monotonic()+300
 recoveries=0
 while time.monotonic()<deadline:
  root,_=ui()
  nodes=list(root.iter('node'))
  titles=[n.get('text','') for n in nodes if n.get('resource-id')=='android:id/alertTitle']
  if "System UI isn't responding" in titles:
   assert recoveries<2,'System UI repeatedly failed to recover'
   capture(f'system-ui-anr-{recoveries+1}')
   waiters=[n for n in nodes if n.get('package')=='android' and n.get('resource-id')=='android:id/aerr_wait' and n.get('text')=='Wait']
   assert len(waiters)==1,[(n.get('text'),n.get('resource-id')) for n in nodes]
   x1,y1,x2,y2=map(int,re.findall(r'\d+',waiters[0].get('bounds','')))
   adb('shell','input','tap',str((x1+x2)//2),str((y1+y2)//2));recoveries+=1
   time.sleep(20);continue
  assert not any("isn't responding" in title for title in titles),('Unexpected application ANR',titles)
  if any(n.get('package')==PKG and n.get('text')=='河图' for n in nodes):
   (OUT/'first-frame-ready.json').write_text(json.dumps({'system_ui_wait_recoveries':recoveries,'app_visible':True}))
   return
  assert adb('shell','pidof',PKG,check=False).strip(),'App process exited before first frame'
  time.sleep(5)
 raise AssertionError('Hetu did not become visible after system startup settled')

@contextmanager
def webview_cdp():
 pid=adb('shell','pidof',PKG).strip()
 assert pid.isdigit(),('Expected one app process',pid)
 port=adb('forward','tcp:0','localabstract:webview_devtools_remote_'+pid).strip()
 assert port.isdigit(),port
 try:yield int(port)
 finally:
  adb('forward','--remove','tcp:'+port)
  remaining=adb('forward','--list')
  assert not any(len(parts)>1 and parts[1]=='tcp:'+port for parts in (line.split() for line in remaining.splitlines())),remaining
  proof=OUT/'webview-forward-cleanup.json'
  entries=json.loads(proof.read_text()) if proof.exists() else []
  entries.append({'app_pid':int(pid),'local_port':int(port),'removed':True})
  proof.write_text(json.dumps(entries,indent=2))

def web_command(port,action='read',label=None):
 command=['node',str(Path(__file__).with_name('check_hetu_webview.cjs')),str(port),action]
 if label is not None:command.append(label)
 result=subprocess.run(command,check=False,stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=45)
 if result.returncode:
  detail=result.stderr.decode(errors='replace')+'\n'+result.stdout.decode(errors='replace')
  (OUT/'webview-driver-error.txt').write_text(detail)
  raise AssertionError('Native WebView driver failed: '+detail)
 return json.loads(result.stdout.decode())['value']

def web_expect(port,label,name,required=None):
 deadline=time.monotonic()+120
 value={}
 while time.monotonic()<deadline:
  value=web_command(port)
  if label in value.get('text','') and all(value.get(k)==v for k,v in (required or {}).items()):
   # DOM readiness precedes Android's compositor. The CDP read awaits two
   # animation frames; then recheck after a short surface-settle interval.
   time.sleep(.4)
   value=web_command(port)
   if label not in value.get('text','') or not all(value.get(k)==v for k,v in (required or {}).items()):continue
   (OUT/(name+'.dom.json')).write_text(json.dumps(value,ensure_ascii=False,indent=2))
   save_png(name)
   checks.append({'name':name,'result':'passed','evidence':'actual Android WebView DOM + native adb screenshot'})
   return
  time.sleep(1)
 raise AssertionError(('Actual WebView did not reach expected state',label,required,value))

def verify_distinct_webview_frames(output):
 expected={f'reference-03B-{number:03d}-native-{state}.png' for number,state in [(40,'overview'),(41,'groups'),(42,'connections'),(43,'node-dialog'),(44,'switch-failure')]}
 frames=sorted(output.glob('reference-03B-04*-native-*.png'))
 assert {p.name for p in frames}==expected,('Missing native frame', [p.name for p in frames])
 frame_hashes={p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in frames}
 (output/'native-webview-frame-hashes.json').write_text(json.dumps(frame_hashes,indent=2))
 assert len(set(frame_hashes.values()))==5,('Stale duplicate native frames',frame_hashes)

def web_click(port,label):
 assert web_command(port,'click',label).get('clicked')==label

def palette_preferences(original,accent,palette,appearance,monet=False):
 # Only a fresh disposable emulator fixture is edited. Preserve unrelated keys.
 root=ET.fromstring(original) if original.lstrip().startswith('<?xml') else ET.Element('map')
 changes={'accentHex':('string',accent),'colorPalette':('string',palette),
          'appearance':('string',appearance),'enableMonet':('boolean',str(monet).lower())}
 for key,(kind,value) in changes.items():
  for node in list(root):
   if node.get('name')==key:root.remove(node)
  node=ET.SubElement(root,kind,{'name':key})
  if kind=='string':node.text=value
  else:node.set('value',value)
 return ET.tostring(root,encoding='utf-8',xml_declaration=True)

def write_fixture_preferences(data):
 adb('shell','am','force-stop',PKG)
 subprocess.run([ADB,'shell','run-as',PKG,'sh','-c',"'cat > shared_prefs/hetu.xml'"],
                input=data,stdout=subprocess.PIPE,stderr=subprocess.PIPE,check=True,timeout=30)

def palette_scripts_cases(component,apk,expect_crash=False):
 original=adb('shell','run-as',PKG,'cat','shared_prefs/hetu.xml',check=False)
 palettes=['TonalSpot','Neutral','Vibrant','Expressive','Rainbow','FruitSalad','Monochrome','Fidelity']
 cases=[('#EF4444',palette,appearance,False) for appearance in ['light','dark'] for palette in palettes]
 cases += [('#2A62E8','Vibrant',appearance,False) for appearance in ['light','dark']]
 cases += [('#22C55E','Fidelity','dark',False),('#A855F7','FruitSalad','light',False),('#22C55E','Expressive','dark',True)]
 if expect_crash:cases=cases[:1]
 try:
  for index,(accent,palette,appearance,monet) in enumerate(cases):
   write_fixture_preferences(palette_preferences(original,accent,palette,appearance,monet))
   adb('shell','am','start','-W','-n',component,timeout=240)
   wait_for_home()
   click('工具',bottom=True);click('脚本',scroll=True)
   if expect_crash:
    log=adb('logcat','-d','-v','brief',timeout=30)
    (OUT/'expected-v2074-palette-crash-logcat.txt').write_text(log)
    report=adb('shell','run-as',PKG,'cat','files/last-app-crash.txt',check=False)
    (OUT/'expected-v2074-palette-crash-record.txt').write_text(report)
    save_png('expected-v2074-palette-crash')
    assert re.search(r'FATAL EXCEPTION[\s\S]{0,1000}Process: '+re.escape(PKG),log),log[-4000:]
    assert 'NoSuchMethodError' in log and 'com.materialkolor.scheme.SchemeTonalSpot' in log,log[-4000:]
    assert 'screen=ProxyScriptsActivity' in report and 'app=0.12.4-v20 (2074)' in report,report
    (OUT/'results.json').write_text(json.dumps({'result':'expected_failure_reproduced','apkSha256':hashlib.sha256(apk.read_bytes()).hexdigest(),
       'versionCode':2074,'accent':accent,'palette':palette,'appearance':appearance,
       'screen':'ProxyScriptsActivity','exception':'NoSuchMethodError','apiLevel':int(adb('shell','getprop','ro.build.version.sdk').strip()),
       'fixtureOnly':True,'limitations':'Controlled AOSP reproduction of historical APK. No phone Root or network validation.'},indent=2))
    return
   expect('服务启动前',f'custom-palette-{index:02d}-{palette}-{appearance}')
   assert adb('shell','pidof',PKG,check=False).strip(),'Custom palette exited app process'
   assert 'ProxyScriptsActivity' in adb('shell','dumpsys','activity','activities'), 'Expected actual scripts activity'
 finally:
  if original.lstrip().startswith('<?xml'):
   write_fixture_preferences(original.encode())
  else:
   adb('shell','am','force-stop',PKG)
   adb('shell','run-as',PKG,'rm','-f','shared_prefs/hetu.xml')
 if not expect_crash:
  adb('shell','am','start','-W','-n',component,timeout=240)
  wait_for_home()

def main():
 end=time.monotonic()+900
 while time.monotonic()<end:
  try:
   if adb('shell','getprop','sys.boot_completed',timeout=10,check=False).strip()=='1':break
  except subprocess.TimeoutExpired:pass
  time.sleep(5)
 else: raise AssertionError('Emulator boot did not complete in 15 minutes')
 # Boot-completed precedes some first-boot package/role work under software emulation.
 ready_until=time.monotonic()+180
 stable=0
 while time.monotonic()<ready_until:
  try:
   packages=adb('shell','cmd','package','list','packages','android',timeout=30,check=False)
   animation=adb('shell','getprop','init.svc.bootanim',timeout=15,check=False).strip()
   stable=stable+1 if 'package:android' in packages and animation in ('stopped','') else 0
   if stable>=3:
    (OUT/'boot-ready.json').write_text(json.dumps({'boot_completed':True,'boot_animation':animation,'package_manager_stable_samples':stable}))
    break
  except subprocess.TimeoutExpired:stable=0
  time.sleep(5)
 else:raise AssertionError('Package manager/boot animation did not settle')
 adb('shell','input','keyevent','82');time.sleep(3)
 apk=next(Path('candidate').glob('*.apk'))
 installed=adb('install','--no-streaming','-r',str(apk),timeout=600)
 (OUT/'install.txt').write_text(installed)
 assert 'Success' in installed,installed
 version=adb('shell','dumpsys','package',PKG);assert 'versionCode='+os.environ.get('HETU_EXPECTED_VERSION','2070') in version
 (OUT/'version.txt').write_text('\n'.join(line for line in version.splitlines() if 'versionCode=' in line or 'versionName=' in line))
 components=adb('shell','cmd','package','query-activities','--brief','--components','--query-flags','0','--user','0','-a','android.intent.action.MAIN','-c','android.intent.category.LAUNCHER','-p',PKG)
 choices=[line.strip() for line in components.splitlines() if line.strip().startswith(PKG+'/')];assert choices,components
 adb('logcat','-c')
 (OUT/'launch.txt').write_text(adb('shell','am','start','-W','-n',choices[0],timeout=240))
 wait_for_home()
 assert adb('shell','pidof',PKG,check=False).strip(),'App process exited at launch'
 expect('河图','01-home')
 # New language binding follows the actual system locale. Exercise the visible
 # preference picker before collecting the Chinese-reference navigation set.
 root,_=ui()
 if any(n.get('text')=='Settings' for n in root.iter('node')):
  click('Settings',bottom=True);click('Language and appearance');click('Language')
  click('简体中文');expect('主题设置','00-language-selected')
  adb('shell','input','keyevent','4');time.sleep(2);click('首页',bottom=True)
  expect('河图','01-home-language-restored')
 if os.environ.get('HETU_EXPECT_PALETTE_CRASH')=='1':
  palette_scripts_cases(choices[0],apk,expect_crash=True)
  return
 click('面板',bottom=True);expect('代理未运行','02-panel-stopped')
 click('工具',bottom=True);expect('文件管理','03-tools')
 click('脚本',scroll=True);expect('服务启动前','scripts-entry');expect('服务停止后','scripts-stop')
 expect('详情','scripts-root-error-details');click('详情');expect('复制诊断','scripts-root-error-expanded')
 click('脚本环境');expect('HETU_HOOK','scripts-environment')
 adb('shell','input','keyevent','4');time.sleep(2)
 click('更多');expect('导入自定义脚本','scripts-overflow')
 click('导入自定义脚本');root=capture('scripts-document-picker')
 assert any(n.get('package','').endswith('.documentsui') for n in root.iter('node')),'OS document picker did not open'
 checks.append({'name':'scripts-document-picker','result':'passed'})
 adb('shell','input','keyevent','4');time.sleep(2)
 expect('服务启动前','scripts-import-cancelled')
 adb('shell','input','keyevent','4');time.sleep(2)

 click('设置',bottom=True);expect('基础代理配置','04-settings')
 click('开机启动与下载',scroll=True);expect('开机自启','boot-settings')
 click_switch('开机自启');expect_eventually('无法取得 Root 权限','boot-root-denial-visible')
 root,_=ui();assert switch_node(root,'开机自启').get('checked')=='false','Root denial left switch enabled'
 raw=adb('shell','run-as',PKG,'cat','shared_prefs/hetu.xml')
 prefs=ET.fromstring(raw)
 assert not any(n.get('name')=='proxyRootAutoStart' and n.get('value')=='true' for n in prefs),'Failed setup was falsely saved as enabled'
 checks.append({'name':'boot-denial-keeps-toggle-off','result':'passed'})
 adb('shell','input','keyevent','4');time.sleep(2)

 click('关于',scroll=True);expect('内置核心','05-about')
 adb('shell','input','keyevent','4');time.sleep(2)
 click('工具',bottom=True);click('诊断工具',scroll=True)
 expect('修复运行记录','safe-session-repair-entry')
 click('修复运行记录',scroll=True);expect_eventually('无法取得 Root 权限','safe-session-repair-root-denial')
 adb('shell','input','keyevent','4');time.sleep(2)
 adb('shell','input','keyevent','4');time.sleep(2)
 click('工具',bottom=True);click('Web面板',scroll=True);expect('河图本地面板','06-web-panels')
 click('河图本地面板');time.sleep(3)
 with webview_cdp() as web_port:
  web_expect(web_port,'河图 WebUI','07-native-webview')
  web_expect(web_port,'未连接','08-native-webview-disconnected',{'status':'未连接','metricCount':0})
 adb('shell','input','keyevent','4');time.sleep(2);expect('河图本地面板','09-webview-back')
 # Fresh emulator only: no proxy process, subscription, network rules or Root.
 # Server listens solely on host127.0.0.1; always remove the test-only reverse.
 with controller_fixture() as (fixture_port, requests):
  device_port=29090
  adb('reverse',f'tcp:{device_port}',f'tcp:{fixture_port}')
  try:
   click('河图本地面板')
   with webview_cdp() as web_port:
    web_expect(web_port,'Mihomo v1.19.0','reference-03B-040-native-overview',{'metricCount':4})
    web_click(web_port,'策略组');web_expect(web_port,'节点选择','reference-03B-041-native-groups',{'groupCount':12})
    web_click(web_port,'连接');web_expect(web_port,'api.example.com','reference-03B-042-native-connections',{'connectionCount':18})
    web_click(web_port,'策略组');web_click(web_port,'节点选择：选择节点')
    web_expect(web_port,'香港 02','reference-03B-043-native-node-dialog',{'nodeDialogOpen':True})
    web_click(web_port,'香港 02');web_expect(web_port,'切换失败：HTTP 503','reference-03B-044-native-switch-failure',{'noticeOpen':True,'firstSelected':'香港 01'})
    assert any(r.get('method')=='PUT' and r.get('valid_fixture_request') for r in requests),requests
    verify_distinct_webview_frames(OUT)
    web_click(web_port,'确定')
  finally:
   adb('reverse','--remove',f'tcp:{device_port}')
   (OUT/'native-webview-fixture-requests.json').write_text(json.dumps(requests,ensure_ascii=False,indent=2))
 palette_scripts_cases(choices[0],apk)
 log=adb('logcat','-d','-v','brief',timeout=30);(OUT/'logcat.txt').write_text(log)
 assert not re.search(r'FATAL EXCEPTION[\s\S]{0,1000}Process: '+re.escape(PKG),log),'Application crash in logcat'
 assert not re.search(r'ANR in '+re.escape(PKG)+r'(?:\s|\(|:)',log),'Application ANR recorded during navigation'
 (OUT/'last-anr.txt').write_text(adb('shell','dumpsys','activity','lastanr',timeout=60,check=False))
 assert adb('shell','pidof',PKG,check=False).strip(),'App process exited after navigation'
 (OUT/'results.json').write_text(json.dumps({'checks':checks,'passed':len(checks),'fixtureOnly':True,'rootMutationActions':0,'rootSetupDenialAttempts':1,'apiLevel':int(adb('shell','getprop','ro.build.version.sdk').strip()),'limitations':'Fresh AOSP emulator. Five populated native-WebView states use an isolated loopback fixture with deliberate HTTP503 switch rejection. No K80, real core, Root boot or actual network validation.'},ensure_ascii=False,indent=2))
try:main()
except Exception:
 (OUT/'failure-preferences.xml').write_text(adb('shell','run-as',PKG,'cat','shared_prefs/hetu.xml',check=False))
 (OUT/'last-app-crash.txt').write_text(adb('shell','run-as',PKG,'cat','files/last-app-crash.txt',check=False))
 try:capture('failure')
 except Exception:pass
 (OUT/'failure-logcat.txt').write_text(adb('logcat','-d','-v','threadtime',timeout=60,check=False))
 try:
  (OUT/'last-anr.txt').write_text(adb('shell','dumpsys','activity','lastanr',timeout=60,check=False))
  # Fresh disposable emulator contains only test fixtures. Keep the original
  # app/system ANR trace; never dismiss an app ANR or disable its detector.
  (OUT/'bugreport-command.txt').write_text(adb('bugreport',str(OUT/'bugreport.zip'),timeout=240,check=False))
 except Exception as diagnostic_error:
  (OUT/'bugreport-error.txt').write_text(repr(diagnostic_error))
 raise
