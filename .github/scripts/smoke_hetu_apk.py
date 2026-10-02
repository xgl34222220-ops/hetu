#!/usr/bin/env python3
"""Read-only navigation smoke in a fresh non-root Android emulator.
Never starts/stops a proxy, requests permissions, imports user data, or clears app data.
"""
import json, os, re, subprocess, time, xml.etree.ElementTree as ET
from pathlib import Path
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
def ui():
 adb('shell','uiautomator','dump','/data/local/tmp/hetu-smoke.xml',timeout=60)
 raw=adb('shell','cat','/data/local/tmp/hetu-smoke.xml')
 start=raw.find('<?xml'); assert start>=0,raw[:300]
 return ET.fromstring(raw[start:]),raw[start:]
def capture(name):
 root,raw=ui();(OUT/(name+'.xml')).write_text(raw)
 png=subprocess.run([ADB,'exec-out','screencap','-p'],check=True,stdout=subprocess.PIPE,timeout=45).stdout
 assert png.startswith(b'\x89PNG\r\n\x1a\n');(OUT/(name+'.png')).write_bytes(png)
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

def expect_eventually(label,name):
 deadline=time.monotonic()+120
 while time.monotonic()<deadline:
  root,_=ui()
  if any(label in n.get('text','') or label in n.get('content-desc','') for n in root.iter('node')):
   expect(label,name);return
  time.sleep(2)
 raise AssertionError('Timed out waiting for native WebView label: '+label)

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
 version=adb('shell','dumpsys','package',PKG);assert 'versionCode='+os.environ.get('HETU_EXPECTED_VERSION','2067') in version
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
  click('简体中文');expect('语言与主题','00-language-selected')
  adb('shell','input','keyevent','4');time.sleep(2);click('首页',bottom=True)
  expect('河图','01-home-language-restored')
 click('面板',bottom=True);expect('代理未运行','02-panel-stopped')
 click('工具',bottom=True);expect('文件管理','03-tools')
 click('设置',bottom=True);expect('基础代理配置','04-settings')
 click('关于',scroll=True);expect('内置核心','05-about')
 adb('shell','input','keyevent','4');time.sleep(2)
 click('工具',bottom=True);click('Web面板',scroll=True);expect('河图本地面板','06-web-panels')
 click('河图本地面板');time.sleep(8);expect('河图 WebUI','07-native-webview')
 expect('未连接','08-native-webview-disconnected')
 adb('shell','input','keyevent','4');time.sleep(2);expect('河图本地面板','09-webview-back')
 # Fresh emulator only: no proxy process, subscription, network rules or Root.
 # Server listens solely on host127.0.0.1; always remove the test-only reverse.
 with controller_fixture() as (fixture_port, requests):
  device_port=29090
  adb('reverse',f'tcp:{device_port}',f'tcp:{fixture_port}')
  try:
   click('河图本地面板')
   expect_eventually('Mihomo v1.19.0','reference-03B-040-native-overview')
   click('策略组');expect_eventually('节点选择','reference-03B-041-native-groups')
   click('连接');expect_eventually('api.example.com','reference-03B-042-native-connections')
   click('策略组');click('节点选择：选择节点')
   expect_eventually('香港 02','reference-03B-043-native-node-dialog')
   click('香港 02');expect_eventually('切换失败：HTTP 503','reference-03B-044-native-switch-failure')
   assert any(r.get('method')=='PUT' and r.get('valid_fixture_request') for r in requests),requests
   click('确定')
  finally:
   adb('reverse','--remove',f'tcp:{device_port}')
   (OUT/'native-webview-fixture-requests.json').write_text(json.dumps(requests,ensure_ascii=False,indent=2))
 log=adb('logcat','-d','-v','brief',timeout=30);(OUT/'logcat.txt').write_text(log)
 assert not re.search(r'FATAL EXCEPTION[\s\S]{0,1000}Process: '+re.escape(PKG),log),'Application crash in logcat'
 assert adb('shell','pidof',PKG,check=False).strip(),'App process exited after navigation'
 (OUT/'results.json').write_text(json.dumps({'checks':checks,'passed':len(checks),'fixtureOnly':True,'rootActions':0,'limitations':'Fresh AOSP emulator. Five populated native-WebView states use an isolated loopback fixture with deliberate HTTP503 switch rejection. No K80, real core, Root boot or actual network validation.'},ensure_ascii=False,indent=2))
try:main()
except Exception:
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
