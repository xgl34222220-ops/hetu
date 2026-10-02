#!/usr/bin/env python3
"""Read-only navigation smoke in a fresh non-root Android emulator.
Never starts/stops a proxy, requests permissions, imports user data, or clears app data.
"""
import json, os, re, subprocess, time, xml.etree.ElementTree as ET
from pathlib import Path
PKG='io.github.xgl34222220.hetu'
ADB=str(Path(os.environ['ANDROID_HOME'])/'platform-tools'/'adb')
OUT=Path(os.environ.get('HETU_SMOKE_OUT','out/android-smoke')); OUT.mkdir(parents=True,exist_ok=True)
checks=[]
def adb(*args, timeout=90, check=True):
 return subprocess.run([ADB,*args],stdout=subprocess.PIPE,stderr=subprocess.STDOUT,timeout=timeout,check=check).stdout.decode(errors='replace')
def ui():
 adb('shell','uiautomator','dump','/sdcard/hetu-smoke.xml',timeout=60)
 raw=adb('shell','cat','/sdcard/hetu-smoke.xml')
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

def main():
 end=time.monotonic()+900
 while time.monotonic()<end:
  try:
   if adb('shell','getprop','sys.boot_completed',timeout=10,check=False).strip()=='1':break
  except subprocess.TimeoutExpired:pass
  time.sleep(5)
 else: raise AssertionError('Emulator boot did not complete in 15 minutes')
 adb('shell','input','keyevent','82');time.sleep(3)
 apk=next(Path('candidate').glob('*.apk'));adb('install','-r',str(apk),timeout=180)
 version=adb('shell','dumpsys','package',PKG);assert 'versionCode=2065' in version
 (OUT/'version.txt').write_text('\n'.join(line for line in version.splitlines() if 'versionCode=' in line or 'versionName=' in line))
 components=adb('shell','cmd','package','query-activities','--brief','--components','--query-flags','0','--user','0','-a','android.intent.action.MAIN','-c','android.intent.category.LAUNCHER','-p',PKG)
 choices=[line.strip() for line in components.splitlines() if line.strip().startswith(PKG+'/')];assert choices,components
 adb('logcat','-c');adb('shell','am','start','-W','-n',choices[0],timeout=60);time.sleep(8)
 assert adb('shell','pidof',PKG,check=False).strip(),'App process exited at launch'
 expect('河图','01-home')
 click('面板',bottom=True);expect('代理未运行','02-panel-stopped')
 click('工具',bottom=True);expect('文件管理','03-tools')
 click('设置',bottom=True);expect('基础代理配置','04-settings')
 click('关于',scroll=True);expect('内置核心','05-about')
 adb('shell','input','keyevent','4');time.sleep(2)
 click('工具',bottom=True);click('Web面板',scroll=True);expect('河图本地面板','06-web-panels')
 click('河图本地面板');time.sleep(8);expect('河图 WebUI','07-native-webview')
 expect('未连接','08-native-webview-disconnected')
 adb('shell','input','keyevent','4');time.sleep(2);expect('河图本地面板','09-webview-back')
 log=adb('logcat','-d','-v','brief',timeout=30);(OUT/'logcat.txt').write_text(log)
 assert not re.search(r'FATAL EXCEPTION[\s\S]{0,1000}Process: '+re.escape(PKG),log),'Application crash in logcat'
 assert adb('shell','pidof',PKG,check=False).strip(),'App process exited after navigation'
 (OUT/'results.json').write_text(json.dumps({'checks':checks,'passed':len(checks),'fixtureOnly':True,'rootActions':0,'limitations':'Fresh AOSP emulator, offline backend. No K80, Root boot, real network or populated native-WebView state acceptance.'},ensure_ascii=False,indent=2))
try:main()
except Exception:
 try:capture('failure')
 except Exception:pass
 (OUT/'failure-logcat.txt').write_text(adb('logcat','-d','-v','brief',timeout=30,check=False))
 raise
